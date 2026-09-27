package server.living.roster;

import client.Character;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.processor.npc.DueyProcessor;
import constants.inventory.ItemConstants;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's recruited companions: who is active in the party, who is benched at a camp, slot
 * accounting under the 6-member cap, recall/bench/swap, the haul hand-over, and the apprentice
 * EXP bonus. Companion spawning reuses the {@code !spawnbot} path so the bot behaves exactly
 * like a hand-spawned companion once it is in the party.
 */
public final class RosterService {
    private static final Logger log = LoggerFactory.getLogger(RosterService.class);
    public static final int PARTY_CAP = 6;

    private final LivingWorld world;
    /** persona id -> owner character id, cached from the store. */
    private final Map<String, Integer> ownerByPersona = new ConcurrentHashMap<>();
    private final Map<String, Long> ownerLogoutAtMs = new ConcurrentHashMap<>();
    /** owner:persona -> arrival time of a companion travelling from its camp. */
    private final Map<String, Long> travelling = new ConcurrentHashMap<>();
    /** Character ids with at least one roster entry; consulted on every chat line, so cached. */
    private final java.util.Set<Integer> owners = ConcurrentHashMap.newKeySet();

    public RosterService(LivingWorld world) {
        this.world = world;
        for (RosterEntry entry : world.store().allActive()) {
            ownerByPersona.put(entry.personaId(), entry.characterId());
            owners.add(entry.characterId());
        }
        for (RosterEntry entry : world.store().allBenched()) {
            ownerByPersona.put(entry.personaId(), entry.characterId());
            owners.add(entry.characterId());
        }
    }

    /** True when the player has recruited anyone (grants companion-command authority). */
    public boolean hasCompanions(Character chr) {
        return chr != null && owners.contains(chr.getId());
    }

    // ---- queries -------------------------------------------------------------------------

    public boolean isOwned(String personaId) {
        return ownerOf(personaId) != 0;
    }

    public int ownerOf(String personaId) {
        Integer cached = ownerByPersona.get(personaId);
        if (cached != null) {
            return cached;
        }
        RosterEntry entry = world.store().rosterOfPersona(personaId);
        if (entry == null) {
            return 0;
        }
        ownerByPersona.put(personaId, entry.characterId());
        return entry.characterId();
    }

    /**
     * The owner from memory only (0 = unowned), for hot paths: the cache is loaded with every
     * non-released row at boot and kept in step by recruit, escort and release.
     */
    public int cachedOwnerOf(String personaId) {
        Integer cached = personaId == null ? null : ownerByPersona.get(personaId);
        return cached == null ? 0 : cached;
    }

    public List<RosterEntry> roster(Character chr) {
        return world.store().roster(chr.getId());
    }

    public RosterEntry entry(int characterId, String personaId) {
        return world.store().rosterEntry(characterId, personaId);
    }

    public Character onlineCharacter(int characterId) {
        try {
            return Server.getInstance().getWorld(world.config().WORLD).getPlayerStorage().getCharacterById(characterId);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Free party slots for this owner: 6 minus everyone in the party minus companions any human
     * in the party sent away on delegated work, whose slot stays reserved (plan 11.2).
     */
    public int freeSlots(Character owner) {
        Party party = owner.getParty();
        if (party == null) {
            return PARTY_CAP - 1 - awayCount(owner.getId());
        }
        int used = 0;
        for (PartyCharacter member : party.getMembers()) {
            used++;
            Character human = member.getPlayer();
            if (human != null && !AgentPresence.isAgent(human)) {
                used += awayCount(human.getId());
            }
        }
        return Math.max(0, PARTY_CAP - used);
    }

    private int awayCount(int characterId) {
        if (!owners.contains(characterId)) {
            return 0;
        }
        int away = 0;
        for (RosterEntry entry : world.store().roster(characterId)) {
            if (entry.state() == RosterState.AWAY) {
                away++;
            }
        }
        return away;
    }

    /** The companion character of a roster entry when it is online, else null. */
    public Character companion(RosterEntry entry) {
        int characterId = world.residents().characterIdOf(entry.personaId());
        if (characterId == 0) {
            return null;
        }
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        return runtime == null ? null : AgentRuntimeIdentityRuntime.bot(runtime);
    }

    /** True when the persona is active in the owner's party and on the owner's map (plan 5.3). */
    public boolean coPresent(Character owner, String personaId) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), personaId);
        if (entry == null || entry.state() != RosterState.ACTIVE) {
            return false;
        }
        Character bot = companion(entry);
        if (bot == null || bot.getMap() == null || owner.getMap() == null) {
            return false;
        }
        return bot.getMap().getId() == owner.getMap().getId() && sameParty(owner, bot);
    }

    private static boolean sameParty(Character owner, Character bot) {
        Party party = owner.getParty();
        return party != null && bot.getParty() != null && party.getId() == bot.getParty().getId();
    }

    // ---- recruit / release ---------------------------------------------------------------

    /** Adds the persona to the owner's roster and spawns it as an active companion. */
    public String recruit(Character owner, Persona persona) {
        if (isOwned(persona.id())) {
            int current = ownerOf(persona.id());
            return current == owner.getId() ? persona.name() + " is already yours." : persona.name() + " already travels with someone else.";
        }
        if (roster(owner).size() >= world.config().ROSTER_MAX) {
            return "Your roster is full (" + world.config().ROSTER_MAX + "). Release someone first.";
        }
        RosterEntry entry = enroll(owner, persona, "roster.recruit");
        if (freeSlots(owner) <= 0) {
            return persona.name() + " joins your roster but your party is full; call them when there is room.";
        }
        return spawnCompanion(owner, persona, entry);
    }

    /**
     * GM grant ({@code !grantcompanion}, for tests and demos): the persona joins the owner's roster
     * benched at its home-town camp, with no contract and no change to its character. It counts
     * toward {@code ROSTER_MAX} and keeps one owner per persona like a recruit; the owner calls it
     * in the normal way.
     */
    public String grant(Character owner, Persona persona) {
        if (isOwned(persona.id())) {
            return persona.name() + (ownerOf(persona.id()) == owner.getId()
                    ? " is already on " + owner.getName() + "'s roster." : " already travels with someone else.");
        }
        if (roster(owner).size() >= world.config().ROSTER_MAX) {
            return owner.getName() + "'s roster is full (" + world.config().ROSTER_MAX + ").";
        }
        RosterEntry entry = enroll(owner, persona, "roster.grant");
        return persona.name() + " joins " + owner.getName() + "'s roster, benched at map " + entry.campMapId() + ".";
    }

    /** Writes a BENCHED roster row and hands the persona over from its Resident life. */
    private RosterEntry enroll(Character owner, Persona persona, String eventType) {
        long now = System.currentTimeMillis();
        int campMap = server.living.resident.ResidentSpots.townMapId(persona.homeTown());
        RosterEntry entry = new RosterEntry(owner.getId(), persona.id(), RosterState.BENCHED, Assignment.TRAIN, 0, 0,
                campMap, 0L, 0L, 0L, now, now, now, 0, null);
        world.store().saveRoster(entry);
        ownerByPersona.put(persona.id(), owner.getId());
        owners.add(owner.getId());
        world.store().savePersonaState(world.store().personaState(persona.id()).withEmployer(owner.getId()));
        world.store().setWorldFlag("persona." + persona.id() + ".employed", owner.getId());
        world.residents().releaseForRecruit(persona.id());
        world.store().event(owner.getId(), persona.id(), eventType, null);
        return entry;
    }

    /** Drops the persona from the roster; it goes home as a Resident. */
    public String release(Character owner, Persona persona, String reason) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return persona.name() + " is not in your roster.";
        }
        world.benchSessions().endFor(persona.id(), "released");
        despawn(entry, "released");
        world.store().saveRoster(entry.with(RosterState.RELEASED, System.currentTimeMillis()));
        world.store().deleteRoster(owner.getId(), persona.id());
        ownerByPersona.remove(persona.id());
        if (roster(owner).isEmpty()) {
            owners.remove(owner.getId());
        }
        world.store().savePersonaState(world.store().personaState(persona.id()).withEmployer(0));
        world.store().clearWorldFlag("persona." + persona.id() + ".employed");
        world.contracts().onReleased(owner, persona, reason);
        world.store().event(owner.getId(), persona.id(), "roster.release", reason);
        return persona.name() + " heads home to " + persona.homeTown().replace('-', ' ') + ".";
    }

    // ---- call / bench / swap -------------------------------------------------------------

    /** Calls a companion: benched ones travel from their camp first (plan 11.3) unless a whistle is used. */
    public String call(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return persona.name() + " is not in your roster.";
        }
        if (entry.state() == RosterState.ACTIVE && companion(entry) != null) {
            return persona.name() + " is already with you.";
        }
        boolean away = entry.state() == RosterState.AWAY;
        if (!away && freeSlots(owner) <= 0) {
            return "Your party is full. Bench someone or name who to swap.";
        }
        String key = owner.getId() + ":" + persona.id();
        Long arriving = travelling.get(key);
        if (arriving != null && arriving > System.currentTimeMillis()) {
            return persona.name() + " is already on the way (" + RecallTravel.describe(arriving - System.currentTimeMillis()) + ").";
        }
        if ((entry.state() == RosterState.BENCHED || away) && owner.getMap() != null) {
            int campMap = entry.campMapId() == 0 ? server.living.resident.ResidentSpots.townMapId(persona.homeTown()) : entry.campMapId();
            long eta = RecallTravel.etaMs(campMap, owner.getMapId());
            if (eta > 0L && !consumeWhistle(owner)) {
                travelling.put(key, System.currentTimeMillis() + eta);
                List<String> lines = world.arcs().lines(persona, "onMyWay");
                world.residents().whisper(persona, owner, (lines.isEmpty() ? "on my way." : lines.get(Math.floorMod(owner.getId(), lines.size())))
                        + " " + RecallTravel.describe(eta) + ".");
                world.store().event(owner.getId(), persona.id(), "roster.travel", "eta " + eta + " ms from map " + campMap);
                server.TimerManager.getInstance().schedule(server.TimerManager.SchedulerLane.LOW_PRIORITY, () -> arrive(owner.getId(), persona), eta);
                return persona.name() + " is on the way (" + RecallTravel.describe(eta) + ").";
            }
        }
        travelling.remove(key);
        return spawnCompanion(owner, persona, entry);
    }

    private void arrive(int ownerId, Persona persona) {
        travelling.remove(ownerId + ":" + persona.id());
        Character owner = onlineCharacter(ownerId);
        if (owner == null) {
            return;
        }
        RosterEntry entry = world.store().rosterEntry(ownerId, persona.id());
        if (entry == null || !(entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY)) {
            return;
        }
        if (entry.state() == RosterState.BENCHED && freeSlots(owner) <= 0) {
            world.residents().whisper(persona, owner, "your party's full. i'll head back to camp.");
            return;
        }
        owner.dropMessage(5, spawnCompanion(owner, persona, entry));
    }

    /** True when the owner carried a recall whistle and one was consumed. */
    private boolean consumeWhistle(Character owner) {
        int whistle = world.config().RECALL_WHISTLE_ITEM_ID;
        if (whistle <= 0) {
            return false;
        }
        client.inventory.InventoryType type = constants.inventory.ItemConstants.getInventoryType(whistle);
        if (owner.getInventory(type).countById(whistle) <= 0) {
            return false;
        }
        InventoryManipulator.removeById(owner.getClient(), type, whistle, 1, false, false);
        owner.dropMessage(5, "You blow the whistle.");
        return true;
    }

    private String spawnCompanion(Character owner, Persona persona, RosterEntry entry) {
        int characterId = world.residents().ensureCharacter(persona);
        if (characterId == 0) {
            return persona.name() + " cannot be reached right now.";
        }
        world.benchSessions().endFor(persona.id(), "called by owner");
        world.residents().releaseForRecruit(persona.id());
        if (AgentRuntimeRegistry.hasActiveAgentCharacterId(characterId)) {
            AgentRuntimeCleanupService.removeAgentByCharacterId(characterId);
        }
        AgentLifecycleService.AgentSpawnResult result = AgentInteractionRuntime.spawnAgentForLeader(owner, persona.name());
        if (!result.success() || result.agent() == null) {
            log.warn("call {} for {} failed: {}", persona.id(), owner.getName(), result.errorMessage());
            return persona.name() + " could not join right now (" + result.errorMessage() + ").";
        }
        AgentPartyLifecycleService.joinAgentToLeaderParty(owner, result.agent());
        world.bench().applyAccrued(entry, result.agent());
        RosterEntry active = entry.with(RosterState.ACTIVE, System.currentTimeMillis());
        world.store().saveRoster(active);
        ownerByPersona.put(persona.id(), owner.getId());
        world.store().event(owner.getId(), persona.id(), "roster.call", null);
        List<String> lines = world.arcs().lines(persona, "join");
        world.residents().reply(persona, owner, lines.isEmpty() ? "here." : lines.get(Math.floorMod(owner.getId(), lines.size())));
        return persona.name() + " joins your party.";
    }

    public String bench(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return persona.name() + " is not in your roster.";
        }
        if (entry.state() == RosterState.BENCHED) {
            return persona.name() + " is already benched.";
        }
        List<String> lines = world.arcs().lines(persona, "bench");
        world.residents().reply(persona, owner, lines.isEmpty() ? "alright. i'll keep busy." : lines.get(Math.floorMod(owner.getId(), lines.size())));
        despawn(entry, "benched");
        RosterEntry benched = entry.with(RosterState.BENCHED, System.currentTimeMillis());
        world.store().saveRoster(benched);
        world.store().event(owner.getId(), persona.id(), "roster.bench", benched.assignment().name());
        return persona.name() + " heads to camp (" + describeAssignment(benched) + ").";
    }

    public String swap(Character owner, Persona out, Persona in) {
        String benched = bench(owner, out);
        String called = call(owner, in);
        return benched + " " + called;
    }

    public String setCamp(Character owner, Persona persona, int mapId) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), mapId, entry.accruedExp(), entry.haulMeso(),
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(),
                entry.farmSecondsToday(), entry.farmDay());
        world.store().saveRoster(updated);
        return persona.name() + " will camp at map " + mapId + ".";
    }

    public String assign(Character owner, Persona persona, Assignment assignment, int itemId, int count) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        if (assignment == Assignment.FARM_ITEM && world.quests().isOwnQuestTarget(owner, persona, itemId)) {
            List<String> lines = world.arcs().lines(persona, "refuseOwnErrand");
            return lines.isEmpty() ? persona.name() + ": that one's your errand, not mine." : persona.name() + ": " + lines.get(0);
        }
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), assignment,
                itemId, count, entry.campMapId(), entry.accruedExp(), entry.haulMeso(), entry.wagesPaidUntilMs(),
                entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay());
        world.store().saveRoster(updated);
        world.store().event(owner.getId(), persona.id(), "roster.assign", describeAssignment(updated));
        return persona.name() + " will " + describeAssignment(updated) + (entry.state() == RosterState.ACTIVE ? " once benched." : ".");
    }

    /**
     * Delegated remote work (plan 11.2): an active companion leaves for a work map and keeps its
     * party slot; from there the bench rules govern it (ledger, live sessions, haul, no own-quest
     * credit) until {@code come back} calls it. A benched companion is simply reassigned, and
     * {@code mapId} 0 means "where it already camps".
     */
    public String delegate(Character owner, Persona persona, Assignment assignment, int itemId, int count, int mapId) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return persona.name() + " is not in your roster.";
        }
        if (assignment == Assignment.FARM_ITEM && world.quests().isOwnQuestTarget(owner, persona, itemId)) {
            List<String> lines = world.arcs().lines(persona, "refuseOwnErrand");
            return lines.isEmpty() ? persona.name() + ": that one's your errand, not mine." : persona.name() + ": " + lines.get(0);
        }
        int workMap = mapId != 0 ? mapId : entry.campMapId() != 0 ? entry.campMapId() : server.living.resident.ResidentSpots.townMapId(persona.homeTown());
        boolean fighting = assignment == Assignment.TRAIN || assignment == Assignment.FARM_MESO
                || assignment == Assignment.FARM_ITEM || assignment == Assignment.GATHER;
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), assignment,
                itemId, count, workMap, entry.accruedExp(), entry.haulMeso(), entry.wagesPaidUntilMs(),
                entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay());
        server.maps.MapleMap map = world.residents().map(workMap);
        if (fighting && (map == null || map.isTown())) {
            return persona.name() + ": where? take me to a field and say 'camp here', or send me from one with 'go "
                    + describeAssignment(updated) + " here'.";
        }
        if (entry.state() != RosterState.ACTIVE) {
            world.store().saveRoster(updated);
            world.store().event(owner.getId(), persona.id(), "roster.assign", describeAssignment(updated) + " at map " + workMap);
            return persona.name() + " will " + describeAssignment(updated) + " at map " + workMap + ".";
        }
        List<String> lines = world.arcs().lines(persona, "delegate");
        world.residents().reply(persona, owner, lines.isEmpty() ? "on it. call me when you want me back." : lines.get(Math.floorMod(owner.getId(), lines.size())));
        despawn(entry, "delegated");
        RosterEntry away = updated.with(RosterState.AWAY, System.currentTimeMillis());
        world.store().saveRoster(away);
        world.store().event(owner.getId(), persona.id(), "roster.delegate", describeAssignment(away) + " at map " + workMap);
        return persona.name() + " leaves to " + describeAssignment(away) + (mapId != 0 ? " here" : " at map " + workMap)
                + ", keeping the party slot. Say '" + persona.name().toLowerCase(Locale.ROOT) + " come back' to recall.";
    }

    public String describeAssignment(RosterEntry entry) {
        return switch (entry.assignment()) {
            case TRAIN -> "train";
            case FARM_MESO -> "farm meso";
            case FARM_ITEM -> "farm " + itemName(entry.assignmentItem())
                    + (entry.assignmentCount() > 0 ? " x" + entry.assignmentCount() : "");
            case GATHER -> "gather for your quests";
            case SELL -> "sell the haul";
            case STALL -> "run a stall";
            case REST -> "rest";
        };
    }

    // ---- wages ---------------------------------------------------------------------------

    /** Prepays upkeep so bench work is not charged to the haul; returns the new paid-until description. */
    public String payWages(Character owner, Persona persona, long meso) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        if (meso <= 0 || owner.getMeso() < meso) {
            return "you don't have " + meso + " meso.";
        }
        int level = Math.max(1, world.store().characterLevel(world.residents().characterIdOf(persona.id())));
        long hoursMs = Math.round(meso / BenchLedger.upkeepPerHour(level) * 3_600_000.0d);
        long from = Math.max(System.currentTimeMillis(), entry.wagesPaidUntilMs());
        owner.gainMeso((int) -meso, true);
        RosterEntry paid = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), entry.accruedExp(), entry.haulMeso(),
                from + hoursMs, entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay());
        world.store().saveRoster(paid);
        world.store().event(owner.getId(), persona.id(), "roster.wages", meso + " meso -> " + (hoursMs / 3_600_000L) + " h");
        return persona.name() + " is paid up for " + RecallTravel.describe(paid.wagesPaidUntilMs() - System.currentTimeMillis()) + ".";
    }

    public String wagesStatus(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        long left = entry.wagesPaidUntilMs() - System.currentTimeMillis();
        int level = Math.max(1, world.store().characterLevel(world.residents().characterIdOf(persona.id())));
        return persona.name() + (left > 0 ? " is paid up for " + RecallTravel.describe(left) : " is working off the haul")
                + " (upkeep " + Math.round(BenchLedger.upkeepPerHour(level)) + " meso/hour).";
    }

    // ---- haul ----------------------------------------------------------------------------

    public List<String> haulSummary(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        List<String> lines = new ArrayList<>();
        if (entry == null) {
            lines.add(persona.name() + " is not in your roster.");
            return lines;
        }
        lines.add(persona.name() + ": " + entry.haulMeso() + " meso" + (entry.accruedExp() > 0 ? ", " + entry.accruedExp() + " exp banked" : ""));
        for (HaulItem item : world.store().haul(owner.getId(), persona.id())) {
            lines.add("  " + itemName(item.itemId()) + " x" + item.quantity());
        }
        return lines;
    }

    /** Moves the haul into the owner's inventory and purse; provenance is recorded per stack. */
    public String handOver(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        int moved = 0;
        int skipped = 0;
        for (HaulItem item : world.store().haul(owner.getId(), persona.id())) {
            if (InventoryManipulator.checkSpace(owner.getClient(), item.itemId(), item.quantity(), "")) {
                InventoryManipulator.addById(owner.getClient(), item.itemId(), (short) Math.min(item.quantity(), Short.MAX_VALUE));
                world.store().removeHaul(owner.getId(), persona.id(), item.itemId(), item.quantity());
                world.store().recordTransfer(owner.getId(), persona.id(), item.itemId(), item.quantity());
                moved++;
            } else {
                skipped++;
            }
        }
        long meso = entry.haulMeso();
        if (meso > 0) {
            owner.gainMeso((int) Math.min(meso, Integer.MAX_VALUE), true);
            saveHaulMeso(entry, 0L);
        }
        world.store().event(owner.getId(), persona.id(), "haul.handover", moved + " stacks, " + meso + " meso, skipped " + skipped);
        return persona.name() + " hands over " + moved + " item stack(s) and " + meso + " meso"
                + (skipped > 0 ? " (" + skipped + " stack(s) did not fit)" : "") + ".";
    }

    /** Mails the haul through Duey, one package per stack, the meso in the first. */
    public String sendIt(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        List<HaulItem> haul = world.store().haul(owner.getId(), persona.id());
        long meso = entry.haulMeso();
        if (haul.isEmpty() && meso <= 0) {
            return persona.name() + " has nothing to send.";
        }
        boolean first = true;
        for (HaulItem stack : haul) {
            Item item = ItemConstants.isEquipment(stack.itemId())
                    ? ItemInformationProvider.getInstance().getEquipById(stack.itemId())
                    : new Item(stack.itemId(), (short) 0, (short) Math.min(stack.quantity(), Short.MAX_VALUE));
            DueyProcessor.dueyCreatePackage(item, first ? (int) Math.min(meso, Integer.MAX_VALUE) : 0, persona.name(), owner.getId());
            world.store().removeHaul(owner.getId(), persona.id(), stack.itemId(), stack.quantity());
            world.store().recordTransfer(owner.getId(), persona.id(), stack.itemId(), stack.quantity());
            first = false;
        }
        if (first && meso > 0) {
            DueyProcessor.dueyCreatePackage(new Item(4000000, (short) 0, (short) 1), (int) Math.min(meso, Integer.MAX_VALUE),
                    persona.name(), owner.getId());
        }
        saveHaulMeso(entry, 0L);
        world.store().event(owner.getId(), persona.id(), "haul.duey", haul.size() + " packages, " + meso + " meso");
        return persona.name() + " sends " + haul.size() + " package(s) through Duey.";
    }

    public String bankIt(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        long meso = entry.haulMeso();
        if (meso <= 0) {
            return persona.name() + " has no meso to bank.";
        }
        server.Storage storage = owner.getStorage();
        storage.setMeso((int) Math.min((long) storage.getMeso() + meso, Integer.MAX_VALUE));
        owner.saveCharToDB();
        saveHaulMeso(entry, 0L);
        world.store().event(owner.getId(), persona.id(), "haul.bank", meso + " meso");
        return persona.name() + " banks " + meso + " meso into your storage.";
    }

    void saveHaulMeso(RosterEntry entry, long meso) {
        world.store().saveRoster(new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), entry.accruedExp(), meso,
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(),
                entry.farmSecondsToday(), entry.farmDay()));
    }

    // ---- escort (quest step) -------------------------------------------------------------

    /** A quest ESCORT step: the persona follows the player as a temporary companion until the step ends. */
    public void beginEscort(Character owner, Persona persona, Quest quest, Step step) {
        if (persona == null || isOwned(persona.id())) {
            return;
        }
        long now = System.currentTimeMillis();
        RosterEntry entry = new RosterEntry(owner.getId(), persona.id(), RosterState.AWAY, Assignment.REST, 0, 0,
                owner.getMapId(), 0L, 0L, 0L, now, 0L, now, 0, null); // camp = here, so the call below is instant
        world.store().saveRoster(entry);
        ownerByPersona.put(persona.id(), owner.getId());
        owners.add(owner.getId());
        world.residents().releaseForRecruit(persona.id());
        String result = call(owner, persona);
        owner.dropMessage(5, result + " (escort for " + quest.title() + ")");
    }

    // ---- login / logout ------------------------------------------------------------------

    public void onOwnerLogin(Character owner) {
        ownerLogoutAtMs.remove(String.valueOf(owner.getId()));
        for (RosterEntry entry : roster(owner)) {
            if (entry.state() == RosterState.ACTIVE) {
                Persona persona = world.content().persona(entry.personaId());
                if (persona != null && companion(entry) == null) {
                    call(owner, persona);
                }
            }
        }
    }

    public void onOwnerLogout(Character owner) {
        for (RosterEntry entry : roster(owner)) {
            if (entry.state() == RosterState.ACTIVE) {
                despawn(entry, "owner offline");
            }
        }
        ownerLogoutAtMs.put(String.valueOf(owner.getId()), System.currentTimeMillis());
    }

    private void despawn(RosterEntry entry, String reason) {
        Character bot = companion(entry);
        int characterId = world.residents().characterIdOf(entry.personaId());
        if (bot != null) {
            try {
                AgentPartyLifecycleService.leaveAgentParty(bot);
            } catch (RuntimeException failure) {
                log.debug("leave party failed for {}", entry.personaId(), failure);
            }
            bot.saveCharToDB();
        }
        if (characterId != 0) {
            AgentRuntimeCleanupService.removeAgentByCharacterId(characterId);
        }
        if (bot != null && bot.getClient() != null) {
            bot.getClient().forceDisconnect();
        }
        log.info("companion {} despawned: {}", entry.personaId(), reason);
    }

    // ---- exp -----------------------------------------------------------------------------

    /** Apprentice bonus for an active companion at least two levels below a co-present owner. */
    public double apprenticeMultiplier(Character attacker) {
        if (!AgentPresence.isAgent(attacker)) {
            return 1.0d;
        }
        Persona persona = world.residents().personaOf(attacker);
        if (persona == null) {
            return 1.0d;
        }
        int ownerId = ownerOf(persona.id());
        if (ownerId == 0) {
            return 1.0d;
        }
        Character owner = attacker.getMap() == null ? null : attacker.getMap().getCharacterById(ownerId);
        if (owner == null || !sameParty(owner, attacker)) {
            return 1.0d;
        }
        int gap = owner.getLevel() - attacker.getLevel();
        if (gap < 2) {
            return 1.0d;
        }
        return 1.0d + world.config().APPRENTICE_BONUS_PER_LEVEL * Math.min(world.config().APPRENTICE_MAX_LEVELS, gap);
    }

    // ---- listing -------------------------------------------------------------------------

    public List<String> summary(Character owner) {
        List<String> lines = new ArrayList<>();
        List<RosterEntry> entries = roster(owner);
        if (entries.isEmpty()) {
            lines.add("Nobody travels with you yet. Earn a townsperson's trust and ask them to join.");
            return lines;
        }
        lines.add("Roster " + entries.size() + "/" + world.config().ROSTER_MAX + ", free party slots " + freeSlots(owner) + ":");
        for (RosterEntry entry : entries) {
            Persona persona = world.content().persona(entry.personaId());
            String name = persona == null ? entry.personaId() : persona.name();
            int level = world.store().characterLevel(world.residents().characterIdOf(entry.personaId()));
            lines.add("  " + name + " lv" + level + " " + entry.state().name().toLowerCase(Locale.ROOT)
                    + (entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY
                    ? " - " + describeAssignment(entry) + " at map " + entry.campMapId() : "")
                    + (entry.haulMeso() > 0 ? ", haul " + entry.haulMeso() + " meso" : ""));
        }
        return lines;
    }

    private static String itemName(int itemId) {
        String name = itemId == 0 ? null : ItemInformationProvider.getInstance().getName(itemId);
        return name == null ? "item " + itemId : name;
    }

    public LivingStore store() {
        return world.store();
    }
}
