package server.living.roster;

import client.Character;
import client.inventory.Item;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 2/3 of the bench (plan 11.4): a bounded, rotating set of benched or away companions on
 * a fighting assignment (TRAIN, FARM_MESO, FARM_ITEM) are materialized at their camp field as
 * self-directed grinders. The Agent simulation policy runs them at background priority when no
 * player shares the map and at full fidelity when someone walks in (the daycare visit). What a
 * farming session kills and loots for real is harvested into the haul each tick; while a
 * companion is live the offline ledger only moves its clock, so nothing is credited twice.
 */
public final class BenchSessions {
    private static final Logger log = LoggerFactory.getLogger(BenchSessions.class);
    private static final Set<Assignment> LIVE_ASSIGNMENTS = EnumSet.of(Assignment.TRAIN, Assignment.FARM_MESO, Assignment.FARM_ITEM);
    private static final InventoryType[] HARVESTED = {InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC};

    private static final class Session {
        final String personaId;
        final int ownerId;
        final int characterId;
        final Assignment assignment;
        final long startedAtMs;
        int meso;
        Map<Integer, Integer> items;

        Session(String personaId, int ownerId, int characterId, Assignment assignment, long startedAtMs) {
            this.personaId = personaId;
            this.ownerId = ownerId;
            this.characterId = characterId;
            this.assignment = assignment;
            this.startedAtMs = startedAtMs;
        }
    }

    private final LivingWorld world;
    private final Map<String, Session> live = new ConcurrentHashMap<>();
    private final Map<String, Long> lastLiveAtMs = new ConcurrentHashMap<>();

    public BenchSessions(LivingWorld world) {
        this.world = world;
    }

    public boolean live(String personaId) {
        return live.containsKey(personaId);
    }

    public int liveCount() {
        return live.size();
    }

    /** Live sessions charged to this owner, for the per-owner bench-live budget. */
    public int liveCountFor(int ownerId) {
        int count = 0;
        for (Session session : live.values()) {
            if (session.ownerId == ownerId) {
                count++;
            }
        }
        return count;
    }

    /** Character ids of the live sessions (a copy), for the operator budget. */
    public Set<Integer> liveCharacterIds() {
        Set<Integer> ids = new HashSet<>();
        for (Session session : live.values()) {
            ids.add(session.characterId);
        }
        return ids;
    }

    /** True for roster rows the bench model governs: benched, or away on delegated work (plan 11.2). */
    static boolean benchGoverned(RosterEntry entry) {
        return entry != null && (entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY);
    }

    /**
     * Character loads and unloads run on companion-ops like every companion load (plan "CompanionExecutor"): called
     * from any other thread (a timer, a shutdown), the work is handed over there and this returns true.
     */
    private boolean handedOver(String name, Runnable work) {
        CompanionExecutor ops = world.executor();
        if (ops == null || ops.onExecutorThread()) {
            return false;
        }
        ops.submit(name, work);
        return true;
    }

    public void tick() {
        if (handedOver("bench_sessions.tick", this::tick)) {
            return;
        }
        long now = System.currentTimeMillis();
        int max = world.config().BENCH_LIVE_MAX;
        for (Session session : new ArrayList<>(live.values())) {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(session.characterId);
            Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
            RosterEntry roster = world.store().rosterEntry(session.ownerId, session.personaId);
            if (agent == null || !benchGoverned(roster) || roster.assignment() != session.assignment) {
                end(session, "assignment changed");
                continue;
            }
            if (agent.getMap() == null || agent.getMap().isTown()) {
                end(session, "back in town"); // died and respawned, or wandered off the field
                continue;
            }
            roster = harvest(session, agent, roster);
            boolean watched = ResidentRuntime.humansIn(agent.getMap()) > 0;
            if (!watched && now - session.startedAtMs > world.config().BENCH_LIVE_SLICE_MS) {
                end(session, "slice elapsed");
                continue;
            }
            int ownerLevel = world.store().characterLevel(session.ownerId);
            Persona persona = world.content().persona(session.personaId);
            int paceGap = persona != null && persona.paceGap() >= 0 ? persona.paceGap() : world.config().PACE_GAP;
            if (session.assignment == Assignment.TRAIN) {
                if (ownerLevel > 0 && agent.getLevel() >= ownerLevel - paceGap) {
                    end(session, "reached pace target");
                }
            } else if (ownerLevel > 0 && agent.getLevel() >= ownerLevel) {
                end(session, "would outlevel owner; back to the ledger");
            } else if (!world.bench().withinDailyCap(roster, 0.0d)) {
                end(session, "daily farming cap reached");
            } else if (session.assignment == Assignment.FARM_ITEM && roster.assignmentCount() > 0
                    && world.bench().haulCount(roster, roster.assignmentItem()) >= roster.assignmentCount()) {
                end(session, "collected what was asked");
            }
        }
        if (max <= 0 || live.size() >= max) {
            return;
        }
        List<RosterEntry> candidates = new ArrayList<>();
        for (RosterEntry entry : world.store().allBenched()) {
            if (benchGoverned(entry) && LIVE_ASSIGNMENTS.contains(entry.assignment())
                    && !live.containsKey(entry.personaId()) && world.roster().companion(entry) == null
                    && world.residents().agentOf(entry.personaId()) == null) {
                candidates.add(entry);
            }
        }
        candidates.sort(Comparator.comparingLong(entry -> lastLiveAtMs.getOrDefault(entry.personaId(), 0L)));
        for (RosterEntry entry : candidates) {
            if (live.size() >= max) {
                break;
            }
            start(entry, now);
        }
    }

    private void start(RosterEntry entry, long now) {
        Persona persona = world.content().persona(entry.personaId());
        if (persona == null) {
            return;
        }
        int ownerLevel = world.store().characterLevel(entry.characterId());
        int characterId = world.residents().characterIdOf(persona.id());
        if (characterId == 0 || ownerLevel <= 0) {
            return;
        }
        int botLevel = world.store().characterLevel(characterId);
        int paceGap = persona.paceGap() >= 0 ? persona.paceGap() : world.config().PACE_GAP;
        boolean farming = entry.assignment() != Assignment.TRAIN;
        if (farming ? botLevel >= ownerLevel : botLevel >= ownerLevel - paceGap) {
            lastLiveAtMs.put(persona.id(), now);
            return;
        }
        if (farming && (!world.bench().withinDailyCap(entry, 0.0d)
                || entry.assignment() == Assignment.FARM_ITEM && entry.assignmentCount() > 0
                && world.bench().haulCount(entry, entry.assignmentItem()) >= entry.assignmentCount())) {
            lastLiveAtMs.put(persona.id(), now);
            return;
        }
        int campMapId = entry.campMapId() == 0 ? ResidentSpots.townMapId(persona.homeTown()) : entry.campMapId();
        MapleMap map = world.residents().map(campMapId);
        if (map == null || map.isTown()) {
            return; // towns have nothing to fight; the ledger covers those camps
        }
        // never a second copy of a character that is loaded, or leased for something else
        if (AgentRuntimeRegistry.hasActiveAgentCharacterId(characterId)
                || !world.leases().tryAcquire(characterId, CompanionLeases.Kind.BENCH_LIVE, entry.characterId())) {
            return;
        }
        if (!world.budget().admit(CompanionBudget.Kind.BENCH_LIVE, entry.characterId(), now).allowed()) {
            world.leases().release(characterId, CompanionLeases.Kind.BENCH_LIVE);
            return;
        }
        try {
            Character agent = CosmicAgentOfflineLoader.loadOfflineAgent(characterId, world.config().WORLD, world.config().CHANNEL,
                    map, ResidentSpots.fallback(map, 0));
            if (agent == null) {
                world.leases().release(characterId, CompanionLeases.Kind.BENCH_LIVE);
                return;
            }
            world.bench().applyAccrued(entry, agent);
            AgentInteractionRuntime.registerSelfDirectedAgent(agent);
            Session session = new Session(persona.id(), entry.characterId(), characterId, entry.assignment(), now);
            snapshot(session, agent);
            live.put(persona.id(), session);
            lastLiveAtMs.put(persona.id(), now);
            world.store().event(entry.characterId(), persona.id(), "bench.live",
                    world.roster().describeAssignment(entry) + " live at map " + campMapId);
            log.info("bench session: {} {} live at {} for {}", persona.name(), world.roster().describeAssignment(entry), campMapId, entry.characterId());
        } catch (Exception failure) {
            world.leases().release(characterId, CompanionLeases.Kind.BENCH_LIVE);
            log.warn("bench session for {} failed", persona.id(), failure);
        }
    }

    private static void snapshot(Session session, Character agent) {
        session.meso = agent.getMeso();
        session.items = countItems(agent);
    }

    private static Map<Integer, Integer> countItems(Character agent) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (InventoryType type : HARVESTED) {
            for (Item item : agent.getInventory(type).list()) {
                counts.merge(item.getItemId(), (int) item.getQuantity(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Moves what the companion gained since the last look into the haul: meso above the
     * starting purse and every stack that grew. Its own supplies (potions it drank, gear it
     * put on) only ever shrink, so they are never taken.
     */
    private RosterEntry harvest(Session session, Character agent, RosterEntry entry) {
        if (session.assignment == Assignment.TRAIN) {
            return entry; // a trainee keeps its pocket money and drops; only EXP is the point
        }
        long haulMeso = entry.haulMeso();
        int gainedMeso = agent.getMeso() - session.meso;
        if (gainedMeso > 0) {
            agent.gainMeso(-gainedMeso, false);
            haulMeso += gainedMeso;
        }
        Map<Integer, Integer> counts = countItems(agent);
        int gainedStacks = 0;
        for (Map.Entry<Integer, Integer> count : counts.entrySet()) {
            int gained = count.getValue() - session.items.getOrDefault(count.getKey(), 0);
            if (gained <= 0) {
                continue;
            }
            int itemId = count.getKey();
            if (world.bench().haulStacks(entry) >= world.config().HAUL_MAX_STACKS && world.bench().haulCount(entry, itemId) == 0) {
                continue; // bag is full; the companion keeps it
            }
            InventoryManipulator.removeById(agent.getClient(), ItemConstants.getInventoryType(itemId), itemId, gained, false, false);
            world.store().addHaul(entry.characterId(), entry.personaId(), itemId, gained);
            gainedStacks++;
        }
        snapshot(session, agent);
        if (gainedMeso <= 0 && gainedStacks == 0) {
            return entry;
        }
        // the ledger owns the clock and the daily-cap seconds, even while the companion is live
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), entry.accruedExp(), haulMeso,
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(),
                entry.farmSecondsToday(), entry.farmDay());
        world.store().saveRosterProgress(updated);
        world.store().event(entry.characterId(), entry.personaId(), "bench.harvest", "+" + Math.max(0, gainedMeso) + " meso, " + gainedStacks + " stacks");
        return updated;
    }

    private void end(Session session, String reason) {
        live.remove(session.personaId);
        lastLiveAtMs.put(session.personaId, System.currentTimeMillis());
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(session.characterId);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        if (agent != null) {
            RosterEntry roster = world.store().rosterEntry(session.ownerId, session.personaId);
            if (roster != null) {
                try {
                    harvest(session, agent, roster);
                } catch (RuntimeException failure) {
                    log.warn("final harvest for {} failed", session.personaId, failure);
                }
            }
        }
        AgentRuntimeCleanupService.removeAgentByCharacterId(session.characterId);
        if (agent != null && agent.getClient() != null) {
            agent.saveCharToDB();
            agent.getClient().forceDisconnect();
        }
        world.leases().release(session.characterId, CompanionLeases.Kind.BENCH_LIVE); // saved first, so the next load sees it
        world.store().event(session.ownerId, session.personaId, "bench.live-end", reason);
    }

    /** Ends a live session before the character is needed elsewhere (call, release, shutdown). */
    public void endFor(String personaId, String reason) {
        if (handedOver("bench_sessions.end " + personaId, () -> endFor(personaId, reason))) {
            return;
        }
        Session session = live.get(personaId);
        if (session != null) {
            end(session, reason);
        }
    }

    public void endAll(String reason) {
        if (handedOver("bench_sessions.end_all", () -> endAll(reason))) {
            return;
        }
        for (Session session : new ArrayList<>(live.values())) {
            end(session, reason);
        }
    }
}
