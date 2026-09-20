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
import server.living.LivingWorld;
import server.living.config.Content.Chapter;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;
import server.living.config.Content.RoutineSlot;
import server.living.store.LivingStore;
import server.maps.MapleMap;

import java.awt.Point;
import java.time.LocalTime;
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
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, String> personaByCharacter = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastPlayerSeenMs = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAfterMs = new ConcurrentHashMap<>();
    private final Map<String, Boolean> forcedAwake = new ConcurrentHashMap<>();

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
        String personaId = personaByCharacter.get(character.getId());
        if (personaId == null) {
            LivingStore.PersonaState state = null;
            for (Map.Entry<String, LivingStore.PersonaState> entry : world.store().allPersonaStates().entrySet()) {
                if (entry.getValue().characterId() == character.getId()) {
                    state = entry.getValue();
                    break;
                }
            }
            if (state == null) {
                return null;
            }
            personaByCharacter.put(character.getId(), state.personaId());
            personaId = state.personaId();
        }
        return world.content().persona(personaId);
    }

    public Persona personaOfCharacterId(int characterId) {
        String personaId = personaByCharacter.get(characterId);
        if (personaId != null) {
            return world.content().persona(personaId);
        }
        for (Map.Entry<String, LivingStore.PersonaState> entry : world.store().allPersonaStates().entrySet()) {
            if (entry.getValue().characterId() == characterId) {
                personaByCharacter.put(characterId, entry.getKey());
                return world.content().persona(entry.getKey());
            }
        }
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
        Session session = sessions.get(personaId);
        if (session != null) {
            return session.characterId;
        }
        return world.store().personaState(personaId).characterId();
    }

    /** Provisions the backing character if needed and returns its id (0 on failure). */
    public int ensureCharacter(Persona persona) {
        int id = provisioner.ensureCharacter(persona);
        if (id > 0) {
            personaByCharacter.put(id, persona.id());
        }
        return id;
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
        int hour = LocalTime.now().getHour();
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
                || forcedAwake.containsKey(persona.homeTown());
        RoutineSlot slot = currentSlot(persona, hour);
        boolean want = awake && (slot == null || slot.materialize());
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

    private Session materialize(Persona persona, MapleMap map, RoutineSlot slot, long now) {
        int characterId = ensureCharacter(persona);
        if (characterId <= 0) {
            return null;
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
            }
        }
        if (entry == null) {
            try {
                agent = CosmicAgentOfflineLoader.loadOfflineAgent(characterId, world.config().WORLD, world.config().CHANNEL, map, arrival);
            } catch (Exception failure) {
                log.warn("Persona {}: loading character {} failed", persona.id(), characterId, failure);
                return null;
            }
            if (agent == null) {
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
        applySlot(session, map, slot, now);
        log.info("Resident {} ({}) materialized in {} {}", persona.id(), persona.name(), persona.homeTown(),
                spot == null ? "(town life)" : "at " + spot.x + "," + spot.y);
        return session;
    }

    private void shapeIfNeeded(Session session, Character agent) {
        LivingStore.PersonaState state = world.store().personaState(session.persona.id());
        Chapter chapter = world.arcs().currentChapter(session.persona);
        String loadoutId = chapter != null && chapter.loadout() != null ? chapter.loadout() : session.persona.loadout();
        int wanted = ResidentShaper.RECIPE_VERSION * 1000 + Math.abs(loadoutId == null ? 0 : loadoutId.hashCode() % 1000);
        if (state.shapedVersion() == wanted) {
            return;
        }
        Loadout loadout = world.content().loadout(loadoutId);
        ResidentShaper.shape(agent, session.persona, loadout, session.persona.id().hashCode());
        world.store().savePersonaState(state.withCharacter(session.characterId).withShaped(wanted));
        world.store().event(0, session.persona.id(), "persona.shaped", "level " + agent.getLevel() + " job " + agent.getJob()
                + (loadoutId == null ? "" : " loadout " + loadoutId));
    }

    private void maintain(Session session, MapleMap map, RoutineSlot slot, long now) {
        Character agent = session.agent();
        if (agent == null) {
            sessions.remove(session.persona.id());
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
            }
        }
        retryAfterMs.clear();
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
