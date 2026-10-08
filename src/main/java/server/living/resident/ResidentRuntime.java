package server.living.resident;

import client.Character;
import net.server.Server;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeEntryRequest;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.capabilities.townlife.AgentTownLifeSessionResult;
import server.agents.capabilities.townlife.AgentTownLifeVisitRequest;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRequest;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.integration.AgentPresence;
import server.knowledge.PersonalKnowledge;
import server.living.LivingWorld;
import server.living.config.Content.Chapter;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;
import server.living.config.Content.RoutineSlot;
import server.living.config.LivingContent;
import server.living.roster.CompanionLeases;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps every Persona that is not somebody's companion alive as a Resident of its home town:
 * materialized (a headless character standing at its routine spot or living a TownLife
 * session) while players are around, in standby (disconnected, zero cost) otherwise.
 */
public final class ResidentRuntime {
    private static final Logger log = LoggerFactory.getLogger(ResidentRuntime.class);
    private static final long RETRY_BACKOFF_MS = 60_000L;
    private static final long LEASE_MS = 20 * 60_000L;
    private static final int SPOT_DRIFT_PX = 160;

    /** Runtime view of one Persona. */
    public static final class Session {
        public final Persona persona;
        public final int characterId;
        volatile AgentRuntimeEntry entry;
        volatile String slotKey = "";
        volatile Point spot;
        volatile long leaseUntilMs;
        volatile long materializedAtMs;

        Session(Persona persona, int characterId) {
            this.persona = persona;
            this.characterId = characterId;
        }

        public boolean materialized() {
            return entry != null;
        }

        public Character agent() {
            AgentRuntimeEntry current = entry;
            return current == null ? null : AgentRuntimeIdentityRuntime.bot(current);
        }
    }

    private final LivingWorld world;
    private final ResidentProvisioner provisioner;
    private final ResidentLooks looks = new ResidentLooks();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, String> personaByCharacter = new ConcurrentHashMap<>();
    /** Agent character ids known not to be personas (spawnbot companions, ambient pool), so kills skip the DB. */
    private final java.util.Set<Integer> notPersona = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> lastPlayerSeenMs = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAfterMs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> forcedAwake = new ConcurrentHashMap<>();
    /** Per persona, the quest-gear pieces already made for it (ResidentKit.ISSUED events), loaded on first use. */
    private final Map<String, java.util.Set<Integer>> kitIssued = new ConcurrentHashMap<>();
    private final ResidentKit.Ledger kitLedger = new ResidentKit.Ledger() {
        @Override
        public boolean claim(String personaId, int itemId) {
            java.util.Set<Integer> issued = issuedFor(personaId);
            return issued != null && issued.add(itemId); // unreadable ledger: nothing is made this time
        }

        @Override
        public void record(String personaId, int itemId) {
            world.store().event(0, personaId, ResidentKit.ISSUED, String.valueOf(itemId));
        }

        @Override
        public void release(String personaId, int itemId) {
            java.util.Set<Integer> issued = kitIssued.get(personaId);
            if (issued != null) {
                issued.remove(itemId);
            }
        }
    };

    public ResidentRuntime(LivingWorld world) {
        this.world = world;
        this.provisioner = new ResidentProvisioner(world);
    }

    // ---- queries -------------------------------------------------------------------------

    public Session session(String personaId) {
        return sessions.get(personaId);
    }

    public AgentRuntimeEntry entryOf(String personaId) {
        Session session = sessions.get(personaId);
        return session == null ? null : session.entry;
    }

    public Character agentOf(String personaId) {
        Session session = sessions.get(personaId);
        return session == null ? null : session.agent();
    }

    public Persona personaOf(Character character) {
        if (character == null) {
            return null;
        }
        return personaOfCharacterId(character.getId());
    }

    /** True when the runtime already knows this character as a persona's (no store read: safe on any thread). */
    public boolean isPersonaCharacter(int characterId) {
        return personaByCharacter.containsKey(characterId);
    }

    public Persona personaOfCharacterId(int characterId) {
        String personaId = personaByCharacter.get(characterId);
        if (personaId != null) {
            return hireLapsed(personaId) ? null : world.content().persona(personaId);
        }
        if (notPersona.contains(characterId)) {
            return null;
        }
        for (Map.Entry<String, LivingStore.PersonaState> entry : world.store().allPersonaStates().entrySet()) {
            if (entry.getValue().characterId() == characterId && !hireLapsed(entry.getKey())) {
                personaByCharacter.put(characterId, entry.getKey());
                return world.content().persona(entry.getKey());
            }
        }
        notPersona.add(characterId);
        return null;
    }

    /** Materialized Residents standing in this map. */
    public List<Session> residentsIn(int mapId) {
        List<Session> out = new ArrayList<>();
        for (Session session : sessions.values()) {
            Character agent = session.agent();
            if (agent != null && agent.getMap() != null && agent.getMap().getId() == mapId) {
                out.add(session);
            }
        }
        return out;
    }

    public List<Session> sessions() {
        return new ArrayList<>(sessions.values());
    }

    public int characterIdOf(String personaId) {
        if (LivingContent.isHire(personaId)) {
            return LivingContent.hireCharacterId(personaId); // a hired citizen is its own character
        }
        Session session = sessions.get(personaId);
        if (session != null) {
            return session.characterId;
        }
        return world.store().personaState(personaId).characterId();
    }

    /**
     * Provisions the backing character if needed and returns its id (0 on failure). A hired citizen's id names its
     * character: it is never provisioned, and its look stays its own.
     */
    public int ensureCharacter(Persona persona) {
        boolean hire = LivingContent.isHire(persona.id());
        int id = hire ? LivingContent.hireCharacterId(persona.id()) : provisioner.ensureCharacter(persona);
        if (id > 0) {
            personaByCharacter.put(id, persona.id());
            notPersona.remove(id);
            if (!hire) {
                looks.apply(persona, id);
            }
        }
        return id;
    }

    /** Writes every provisioned persona's look to its character (those not loaded right now); never a hire's. */
    public void syncLooks() {
        for (Persona persona : world.content().personas().values()) {
            if (!LivingContent.isHire(persona.id())) {
                looks.apply(persona, world.store().personaState(persona.id()).characterId());
            }
        }
    }

    /** A hired citizen's id nobody employs any more: its character is a citizen again, no persona. */
    private boolean hireLapsed(String personaId) {
        if (!LivingContent.isHire(personaId)) {
            return false;
        }
        RosterService roster = world.roster();
        return roster == null || roster.cachedOwnerOf(personaId) == 0;
    }

    // ---- speech --------------------------------------------------------------------------

    public void say(Persona persona, String text) {
        Character agent = agentOf(persona.id());
        if (agent != null && text != null && !text.isBlank()) {
            AgentDialogueTransportRuntime.sayMapNow(agent, text);
        }
    }

    public void whisper(Persona persona, Character player, String text) {
        if (player == null || text == null || text.isBlank() || !AgentClientGatewayRuntime.clients().hasClient(player)) {
            return;
        }
        AgentPacketGatewayRuntime.packets().sendWhisperReceive(player, persona.name(), world.config().CHANNEL - 1, false, text);
    }

    /** Short lines go to the chat bubble, long ones to a whisper so the town is not spammed. */
    public void reply(Persona persona, Character player, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (text.length() <= world.config().RESIDENT_BUBBLE_MAX_CHARS && agentOf(persona.id()) != null) {
            say(persona, text);
        } else {
            whisper(persona, player, text);
        }
    }

    // ---- presence ------------------------------------------------------------------------

    public void onPlayerPresence(Character chr, MapleMap map) {
        if (AgentPresence.isAgent(chr)) {
            return;
        }
        lastPlayerSeenMs.put(map.getId(), System.currentTimeMillis());
        boolean hasResidents = false;
        for (Persona persona : world.content().personas().values()) {
            if (ResidentSpots.townMapId(persona.homeTown()) == map.getId()) {
                hasResidents = true;
                break;
            }
        }
        if (hasResidents) {
            TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, this::tick, 250L);
        }
    }

    public void setForcedAwake(String town, boolean awake) {
        if (awake) {
            forcedAwake.put(town, Boolean.TRUE);
        } else {
            forcedAwake.remove(town);
        }
    }

    // ---- tick ----------------------------------------------------------------------------

    public synchronized void tick() {
        long now = System.currentTimeMillis();
        Channel channel = channel();
        if (channel == null) {
            return;
        }
        int hour = world.routineHour();
        for (Persona persona : world.content().personas().values()) {
            try {
                reconcile(persona, channel, hour, now);
            } catch (RuntimeException failure) {
                log.warn("resident reconcile failed for {}", persona.id(), failure);
                retryAfterMs.put(persona.id(), now + RETRY_BACKOFF_MS);
            }
        }
        for (Session session : sessions.values()) {
            if (world.content().persona(session.persona.id()) == null) {
                standby(session, "persona removed from content");
            }
        }
    }

    private void reconcile(Persona persona, Channel channel, int hour, long now) {
        Session session = sessions.get(persona.id());
        if (world.roster().isOwned(persona.id())) {
            if (session != null && session.materialized()) {
                standby(session, "recruited");
            }
            return;
        }
        Long retry = retryAfterMs.get(persona.id());
        if (retry != null && retry > now) {
            return;
        }
        int mapId = ResidentSpots.townMapId(persona.homeTown());
        MapleMap map = mapId == 0 ? null : channel.getMapFactory().getMap(mapId);
        if (map == null) {
            retryAfterMs.put(persona.id(), now + RETRY_BACKOFF_MS);
            return;
        }
        int humans = humansIn(map);
        if (humans > 0) {
            lastPlayerSeenMs.put(mapId, now);
        }
        long lastSeen = lastPlayerSeenMs.getOrDefault(mapId, 0L);
        boolean awake = humans > 0 || now - lastSeen < world.config().RESIDENT_SLEEP_AFTER_MS
                || forcedAwake.containsKey(persona.homeTown()) || alwaysAwake(persona.homeTown());
        RoutineSlot slot = currentSlot(persona, hour);
        boolean want = awake && (slot == null || slot.materialize());
        if (log.isDebugEnabled()) {
            log.debug("reconcile {} hour={} humans={} awake={} slot={} want={} session={}", persona.id(), hour, humans, awake,
                    slot, want, session == null ? "none" : session.materialized() ? "materialized" : "standby");
        }
        if (want) {
            if (session == null || !session.materialized()) {
                session = materialize(persona, map, slot, now);
                if (session == null) {
                    retryAfterMs.put(persona.id(), now + RETRY_BACKOFF_MS);
                    return;
                }
            }
            maintain(session, map, slot, now);
        } else if (session != null && session.materialized()) {
            standby(session, humans == 0 ? "town asleep" : "routine says away");
        }
    }

    /** Package-private for the load-guard test. */
    Session materialize(Persona persona, MapleMap map, RoutineSlot slot, long now) {
        int characterId = ensureCharacter(persona);
        if (characterId <= 0) {
            return null;
        }
        if (!world.leases().tryAcquire(characterId, CompanionLeases.Kind.RESIDENT, 0)) {
            return null; // leased for a companion, a bench slice or a party quest: never pulled out from under it
        }
        Session session = new Session(persona, characterId);
        int ordinal = Math.floorMod(persona.id().hashCode(), 7);
        Point spot = slot == null ? null : ResidentSpots.resolve(slot.spot(), map, ordinal);
        Point arrival = spot != null ? spot : ResidentSpots.fallback(map, ordinal);
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        Character agent = null;
        if (entry != null) {
            agent = AgentRuntimeIdentityRuntime.bot(entry);
            if (agent == null) {
                AgentRuntimeCleanupService.removeAgentByCharacterId(characterId);
                entry = null;
            } else if (AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId) != characterId) {
                world.leases().release(characterId, CompanionLeases.Kind.RESIDENT);
                return null; // loaded for someone else (a spawnbot, a party quest): not ours to take over
            }
        }
        if (entry == null) {
            try {
                agent = CosmicAgentOfflineLoader.loadOfflineAgent(characterId, world.config().WORLD, world.config().CHANNEL, map, arrival);
            } catch (Exception failure) {
                world.leases().release(characterId, CompanionLeases.Kind.RESIDENT);
                log.warn("Persona {}: loading character {} failed", persona.id(), characterId, failure);
                return null;
            }
            if (agent == null) {
                world.leases().release(characterId, CompanionLeases.Kind.RESIDENT);
                return null;
            }
            entry = AgentInteractionRuntime.registerDirectorIdleAgent(agent);
        }
        session.entry = entry;
        session.spot = spot;
        session.slotKey = slotKey(slot);
        session.materializedAtMs = now;
        sessions.put(persona.id(), session);
        personaByCharacter.put(characterId, persona.id());
        shapeIfNeeded(session, agent);
        world.dialogue().openKnowledge(persona, characterId, agent);
        dress(session.persona, agent);
        applySlot(session, map, slot, now);
        log.info("Resident {} ({}) materialized in {} {}", persona.id(), persona.name(), persona.homeTown(),
                spot == null ? "(town life)" : "at " + spot.x + "," + spot.y);
        return session;
    }

    /**
     * Shapes the character to its persona (recipe and loadout) when it was shaped to another version. Not while a GM
     * level set holds it (GmLevelHolds): shaping levels it to its persona's level, so it waits until the set is restored
     * or kept, and is shaped the next time it materializes. One a GM kept at its level is shaped at that level.
     */
    void shapeIfNeeded(Session session, Character agent) {
        LivingStore.PersonaState state = world.store().personaState(session.persona.id());
        String loadoutId = currentLoadoutId(session.persona);
        if (state.shapedVersion() == shapedVersion(loadoutId)) {
            wearMissingEquips(session.persona, agent, loadoutId);
            return;
        }
        if (heldAtGmLevel(agent, session.persona)) {
            log.info("Resident {}: not shaped while a GM level set holds it", session.persona.id());
            return;
        }
        boolean kept = keptAtGmLevel(agent, session.persona);
        shape(session.persona, session.characterId, agent, state, loadoutId, kept, kept ? " at its kept level" : "");
    }

    /**
     * Shapes the character to its persona and loadout {@code loadoutId} ({@code keepLevel}: at the level it has, not
     * its persona's), and records that version on {@code state}.
     */
    private void shape(Persona persona, int characterId, Character agent, LivingStore.PersonaState state, String loadoutId,
                       boolean keepLevel, String how) {
        Loadout loadout = world.content().loadout(loadoutId);
        if (keepLevel) {
            ResidentShaper.shapeKeepingLevel(agent, persona, loadout, world.content().loadouts().values());
        } else {
            ResidentShaper.shape(agent, persona, loadout, persona.id().hashCode());
        }
        world.store().savePersonaState(state.withCharacter(characterId).withShaped(shapedVersion(loadoutId)));
        world.store().event(0, persona.id(), "persona.shaped", "level " + agent.getLevel() + " job " + agent.getJob()
                + (loadoutId == null ? "" : " loadout " + loadoutId) + how);
    }

    /** The version a persona with loadout {@code loadoutId} is shaped to; never 0, which is a character never shaped. */
    private static int shapedVersion(String loadoutId) {
        return ResidentShaper.RECIPE_VERSION * 1000 + Math.abs(loadoutId == null ? 0 : loadoutId.hashCode() % 1000);
    }

    /** The persona's loadout, or its arc chapter's while that chapter overrides it. */
    private String currentLoadoutId(Persona persona) {
        Chapter chapter = world.arcs().currentChapter(persona);
        return chapter != null && chapter.loadout() != null ? chapter.loadout() : persona.loadout();
    }

    /** Pieces its loadout gained since it was shaped (an edit keeps the shaped version); a hire keeps its own gear. */
    private void wearMissingEquips(Persona persona, Character agent, String loadoutId) {
        Loadout loadout = world.content().loadout(loadoutId);
        if (agent == null || loadout == null || LivingContent.isHire(persona.id())) {
            return;
        }
        try {
            ResidentShaper.wearMissingEquips(agent, loadout, true);
        } catch (RuntimeException failure) {
            log.warn("Resident {}: wearing missing loadout equips failed", persona.id(), failure);
        }
    }

    private void dress(Persona persona, Character agent) {
        try {
            Loadout loadout = world.content().loadout(currentLoadoutId(persona));
            // its own quest gear first; the wardrobe then dresses every slot the kit does not actually hold
            if (kitAllowed(agent, persona)) {
                ResidentKit.apply(agent, persona, world.content(), world.config(), loadout, kitLedger);
            }
            var held = ResidentKit.slotsHeld(agent, ResidentKit.picks(agent, persona, world.content(), world.config()));
            ResidentWardrobe.dress(agent, persona, loadout, held, keptByContract(persona),
                    ResidentWardrobe.handedOut(world.content().loadouts().values()));
        } catch (RuntimeException failure) {
            log.warn("Resident {}: dressing failed", persona.id(), failure);
        }
    }

    /**
     * A companion the roster or bench has just loaded ({@code loading}), or one a GM level set no longer holds: a persona
     * whose character was never shaped is shaped to it now, then it puts on its quest gear ({@link #refreshKit}). A
     * Resident is shaped when it materializes in its home town; one granted (!grantcompanion) or called before it ever
     * did had its character made at its first call, a level-1 beginner with the creator's sword (found live 2026-10-07:
     * Flint, a Gunslinger, fought Easy Balrog with it). Not reshaped here: a companion shaped to any version (it has grown
     * since), one a GM level set holds (shaped once the set is restored or kept), a hired citizen (its gear is its own).
     * One a GM kept at its level ({@code !setlevel <name> keep}, GmLevelHolds.kept) is shaped at that level: its job,
     * AP, skills and loadout follow and its level stays. Registered, it is shaped on its own mailbox, which its next
     * tick drains before it acts; loaded and not registered yet, right here. A character not live now is shaped when it
     * next loads.
     */
    public void prepareCompanion(Character agent, Persona persona, boolean loading) {
        if (agent != null && persona != null && !LivingContent.isHire(persona.id())
                && neverShaped(world.store().personaState(persona.id()), agent)) {
            AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
            if (runtime == null) {
                if (loading && agent.getClient() != null) {
                    shapeCompanion(agent, persona);
                }
            } else {
                server.agents.runtime.AgentMailboxRuntime.dispatch(runtime, entry -> {
                    Character live = entry.bot();
                    if (live != null && live.getId() == agent.getId() && live.getClient() != null) {
                        shapeCompanion(live, persona);
                    }
                    return null;
                });
            }
        }
        refreshKit(agent, persona, loading);
    }

    /** Its persona state names this character and records no shaping; a state that cannot be read names none. */
    private static boolean neverShaped(LivingStore.PersonaState state, Character agent) {
        return state.characterId() == agent.getId() && state.shapedVersion() == 0;
    }

    /** {@link #prepareCompanion}'s shaping where it runs, its state read again there; never in the way of the load. */
    private void shapeCompanion(Character agent, Persona persona) {
        try {
            LivingStore.PersonaState state = world.store().personaState(persona.id());
            if (!neverShaped(state, agent)) {
                return;
            }
            if (heldAtGmLevel(agent, persona)) {
                log.info("Companion {}: not shaped while a GM level set holds it", persona.id());
                return;
            }
            boolean kept = keptAtGmLevel(agent, persona);
            shape(persona, agent.getId(), agent, state, currentLoadoutId(persona), kept,
                    kept ? " as a companion at its kept level" : " as a companion");
        } catch (RuntimeException failure) {
            log.warn("Companion {}: shaping failed", persona.id(), failure);
        }
    }

    /**
     * The quest gear a Resident's level now allows (ResidentKit), after a level gained or when the roster or bench loads
     * it as a companion: the same character is the companion, so what it wears goes with it. Runs on the Agent's own
     * mailbox, as its loot and trades do; a character not live now is dressed when it next appears.
     */
    public void refreshKit(Character agent, Persona persona) {
        refreshKit(agent, persona, false);
    }

    /**
     * As {@link #refreshKit(Character, Persona)}; with {@code loading} a character the roster or bench has just loaded and
     * not yet registered (nothing ticks it yet) is dressed right here, on the loading thread.
     */
    public void refreshKit(Character agent, Persona persona, boolean loading) {
        if (agent == null || persona == null || LivingContent.isHire(persona.id())) {
            return;
        }
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
        if (runtime == null) {
            if (loading && agent.getClient() != null && kitAllowed(agent, persona)) {
                try {
                    ResidentKit.apply(agent, persona, world.content(), world.config(),
                            world.content().loadout(currentLoadoutId(persona)), kitLedger);
                } catch (RuntimeException failure) {
                    log.warn("Resident {}: quest gear failed", persona.id(), failure);
                }
            }
            return;
        }
        server.agents.runtime.AgentMailboxRuntime.dispatch(runtime, entry -> {
            Character live = entry.bot();
            if (live != null && live.getId() == agent.getId() && live.getClient() != null && kitAllowed(live, persona)) {
                try {
                    ResidentKit.apply(live, persona, world.content(), world.config(),
                            world.content().loadout(currentLoadoutId(persona)), kitLedger);
                } catch (RuntimeException failure) {
                    log.warn("Resident {}: quest gear failed", persona.id(), failure);
                }
            }
            return null;
        });
    }

    /**
     * False while a GM level set holds the character (GmLevelHolds; a record that cannot be read counts as held): its
     * quest gear is for the level it really has, so it waits until the set is restored or kept.
     */
    boolean kitAllowed(Character agent, Persona persona) {
        return !heldAtGmLevel(agent, persona);
    }

    /** True while a GM level set holds the character (GmLevelHolds; a record that cannot be read counts as held). */
    private boolean heldAtGmLevel(Character agent, Persona persona) {
        server.living.roster.GmLevelHolds holds = world.gmLevelHolds();
        return holds != null && agent != null && persona != null && holds.held(agent.getId(), persona.id());
    }

    /** True when a GM kept the character at the level it has ({@code !setlevel <name> keep}, GmLevelHolds.kept). */
    private boolean keptAtGmLevel(Character agent, Persona persona) {
        server.living.roster.GmLevelHolds holds = world.gmLevelHolds();
        return holds != null && agent != null && persona != null && holds.kept(agent.getId(), persona.id());
    }

    /** The pieces made for the persona so far, or null while the ledger cannot be read (nothing is cached then). */
    private java.util.Set<Integer> issuedFor(String personaId) {
        java.util.Set<Integer> known = kitIssued.get(personaId);
        if (known != null) {
            return known;
        }
        List<String> details = world.store().personaEventDetails(personaId, ResidentKit.ISSUED);
        if (details == null) {
            return null;
        }
        java.util.Set<Integer> ids = ConcurrentHashMap.newKeySet();
        for (String detail : details) {
            try {
                ids.add(Integer.parseInt(detail.trim()));
            } catch (NumberFormatException | NullPointerException ignored) {
                // not one of ours
            }
        }
        java.util.Set<Integer> raced = kitIssued.putIfAbsent(personaId, ids);
        return raced != null ? raced : ids;
    }

    /** The items the persona's contract lets it keep (a hire's price), which the wardrobe never sweeps away. */
    private java.util.Set<Integer> keptByContract(Persona persona) {
        java.util.Set<Integer> kept = new java.util.HashSet<>();
        var contract = persona.contract() == null ? null : world.content().contract(persona.contract());
        if (contract != null) {
            for (var item : contract.price().items()) {
                if (item.keep()) {
                    kept.add(item.itemId());
                }
            }
        }
        return kept;
    }

    private void maintain(Session session, MapleMap map, RoutineSlot slot, long now) {
        Character agent = session.agent();
        if (agent == null) {
            sessions.remove(session.persona.id());
            PersonalKnowledge.shared().close(session.characterId);
            world.leases().release(session.characterId, CompanionLeases.Kind.RESIDENT);
            return;
        }
        if (agent.getMap() == null || agent.getMap().getId() != map.getId()) {
            // wandered off (portal, taxi test, GM warp): bring it home
            standby(session, "left home map");
            return;
        }
        String key = slotKey(slot);
        if (!key.equals(session.slotKey)) {
            session.slotKey = key;
            int ordinal = Math.floorMod(session.persona.id().hashCode(), 7);
            session.spot = slot == null ? null : ResidentSpots.resolve(slot.spot(), map, ordinal);
            applySlot(session, map, slot, now);
            return;
        }
        if (session.spot != null) {
            if (agent.getPosition().distance(session.spot) > SPOT_DRIFT_PX && !world.rumors().performing(session.persona.id())) {
                AgentMovementCommandRuntime.moveTo(session.entry, session.spot, true);
            }
        } else if (session.leaseUntilMs - now < 60_000L || !AgentTownLifeRuntime.active(session.entry)) {
            startTownLife(session, map, slot, now);
        }
    }

    private void applySlot(Session session, MapleMap map, RoutineSlot slot, long now) {
        if (session.spot != null) {
            if (AgentTownLifeRuntime.active(session.entry)) {
                AgentTownLifeRuntime.stop(session.entry, session.agent());
                AgentTownLifeVisitLeaseRuntime.clear(session.entry, session.agent());
            }
            AgentMovementCommandRuntime.moveTo(session.entry, session.spot, true);
        } else {
            startTownLife(session, map, slot, now);
        }
    }

    private void startTownLife(Session session, MapleMap map, RoutineSlot slot, long now) {
        Character agent = session.agent();
        if (agent == null) {
            return;
        }
        String requestId = "living:" + session.persona.id() + ':' + now;
        AgentTownLifeVisitRequest visit = new AgentTownLifeVisitRequest(map.getId(),
                AgentTownLifeVisitRequest.Purpose.LEISURE, "resident routine", 0L);
        AgentTownLifeVisitLeaseRequest lease = new AgentTownLifeVisitLeaseRequest(
                AgentTownLifeEntryRequest.external(requestId, "living-world", visit),
                AgentTownLifeAdmissionMode.AMBIENT, now + LEASE_MS, 30_000L, "resident lease elapsed");
        AgentTownLifeSessionResult result = AgentTownLifeVisitLeaseRuntime.start(session.entry, agent, lease, now,
                session.characterId);
        if (result.started() || result.status() == AgentTownLifeSessionResult.Status.ALREADY_ACTIVE
                || result.status() == AgentTownLifeSessionResult.Status.ALREADY_ACTIVE_SAME_REQUEST) {
            session.leaseUntilMs = now + LEASE_MS;
        } else {
            log.debug("Resident {}: town life not started ({} {})", session.persona.id(), result.status(), result.reason());
            session.leaseUntilMs = now + 60_000L;
            AgentMovementCommandRuntime.stop(session.entry);
        }
    }

    // ---- standby -------------------------------------------------------------------------

    public void standby(Session session, String reason) {
        AgentRuntimeEntry entry = session.entry;
        session.entry = null;
        sessions.remove(session.persona.id());
        PersonalKnowledge.shared().close(session.characterId);
        if (entry == null) {
            return;
        }
        Character agent = AgentRuntimeIdentityRuntime.bot(entry);
        try {
            if (agent != null && AgentTownLifeRuntime.active(entry)) {
                AgentTownLifeRuntime.stop(entry, agent);
                AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
            }
        } catch (RuntimeException failure) {
            log.debug("Resident {}: town life stop failed", session.persona.id(), failure);
        }
        AgentRuntimeCleanupService.removeAgentByCharacterId(session.characterId);
        if (agent != null && agent.getClient() != null) {
            agent.saveCharToDB();
            agent.getClient().forceDisconnect();
        }
        world.leases().release(session.characterId, CompanionLeases.Kind.RESIDENT); // saved first, so the next load sees it
        log.info("Resident {} to standby: {}", session.persona.id(), reason);
    }

    public void standbyAll(String reason) {
        for (Session session : new ArrayList<>(sessions.values())) {
            standby(session, reason);
        }
    }

    /** Called by the roster when a Persona becomes a companion: hand the character over cleanly. */
    public void releaseForRecruit(String personaId) {
        Session session = sessions.get(personaId);
        if (session != null) {
            standby(session, "recruited");
        }
    }

    public void contentChanged() {
        for (Session session : new ArrayList<>(sessions.values())) {
            Persona current = world.content().persona(session.persona.id());
            if (current == null || !current.homeTown().equals(session.persona.homeTown())) {
                standby(session, "content reloaded");
            } else if (!wearsLook(session.agent(), looks.resolve(current))) {
                standby(session, "look changed"); // reloads from the database with the new look
            } else {
                wearMissingEquips(current, session.agent(), currentLoadoutId(current));
                dress(current, session.agent());
            }
        }
        retryAfterMs.clear();
        syncLooks();
    }

    private static boolean wearsLook(Character agent, ResidentLooks.Resolved look) {
        return agent == null || (agent.getGender() == look.gender() && agent.getSkinColor().getId() == look.skin()
                && agent.getFace() == look.face() && agent.getHair() == look.hair());
    }

    // ---- helpers -------------------------------------------------------------------------

    private RoutineSlot currentSlot(Persona persona, int hour) {
        Chapter chapter = world.arcs().currentChapter(persona);
        List<RoutineSlot> routine = chapter != null && !chapter.routineOverride().isEmpty()
                ? chapter.routineOverride() : persona.routine();
        for (RoutineSlot slot : routine) {
            if (slot.covers(hour)) {
                return slot;
            }
        }
        return null;
    }

    private boolean alwaysAwake(String town) {
        String configured = world.config().ALWAYS_AWAKE_TOWNS;
        if (configured == null || configured.isBlank()) {
            return false;
        }
        for (String entry : configured.split(",")) {
            if (entry.trim().equalsIgnoreCase(town)) {
                return true;
            }
        }
        return false;
    }

    private static String slotKey(RoutineSlot slot) {
        return slot == null ? "" : slot.fromHour() + "-" + slot.toHour() + ":" + slot.spot() + ":" + slot.activity();
    }

    private Channel channel() {
        try {
            return Server.getInstance().getChannel(world.config().WORLD, world.config().CHANNEL);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    public static int humansIn(MapleMap map) {
        int count = 0;
        for (Character chr : map.getAllPlayers()) {
            if (!AgentPresence.isAgent(chr)) {
                count++;
            }
        }
        return count;
    }

    public MapleMap map(int mapId) {
        Channel channel = channel();
        return channel == null || mapId <= 0 ? null : channel.getMapFactory().getMap(mapId);
    }
}
