package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.field.AgentFieldIntent;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.field.AgentFieldAdmissionMode;
import server.agents.runtime.field.AgentFieldEntryRequest;
import server.agents.runtime.field.AgentFieldSessionResult;
import server.agents.runtime.field.AgentFieldVisitLeaseRequest;
import server.agents.runtime.field.AgentFieldVisitLeaseRuntime;
import server.agents.runtime.field.AgentFieldVisitRequest;
import server.knowledge.Decisions;
import server.knowledge.FarmSites;
import server.knowledge.WorldKnowledge;
import server.life.SpawnPoint;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;
import server.living.store.LivingStore.RosterEntry;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The GATHER form of the bench (docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench", decision 12 a): a visible drop-pickup
 * field lease at the companion's camp. While its owner's quests still need items it can gather (the ledger's GATHER
 * rule: active COLLECT_ITEM steps of other personas' quests, less what the haul already holds), it loads under a
 * BENCH_LIVE seat at its camp field when a monster there drops one of them, else at the best field for them that it can
 * reach (BENCH_GATHER_AT_DROPPERS, {@link #site}: as the ledger credits a dropper anywhere), registered director-idle,
 * with a field visit lease whose targets are the mobs there that drop those items (it fights only them, or whatever
 * the camp has when none of them lives there and the switch is off or knowledge is not loaded). What it picks
 * up is harvested into the haul by the field form's rules (kill loot and meso only, BenchSessions.harvest). Its lease
 * is started on its own mailbox, and renewed there before it runs out while a player watches. It ends with what its
 * owner's quests need, back in town, off its camp, at its owner's level or the daily farming cap.
 */
public final class BenchGatherForm implements BenchDutyDispatcher.Form {
    private static final Logger log = LoggerFactory.getLogger(BenchGatherForm.class);
    /** Agents one camp's field session takes (the plans' default). */
    private static final int FIELD_PARTICIPANTS = 6;
    private static final long GRACEFUL_EXIT_MS = 30_000L;

    private final LivingWorld world;
    /** persona id -> the field visit lease its slice runs on now (renewed as the same request). */
    private final Map<String, AgentFieldVisitLeaseRequest> visits = new ConcurrentHashMap<>();

    public BenchGatherForm(LivingWorld world) {
        this.world = world;
    }

    @Override
    public CompanionBudget.Kind pool() {
        return CompanionBudget.Kind.BENCH_LIVE;
    }

    /**
     * Where it gathers while its owner's quests need something and it may farm (below its owner's level, within the cap):
     * its field camp, or with BENCH_GATHER_AT_DROPPERS and server knowledge the best field for what it needs
     * ({@link #site}); 0 otherwise.
     */
    @Override
    public int mapFor(RosterEntry entry, Persona persona, int characterId) {
        int ownerLevel = world.store().characterLevel(entry.characterId());
        int level = world.store().characterLevel(characterId);
        Map<Integer, Integer> needs = ownerLevel <= 0 || level >= ownerLevel || !world.bench().withinDailyCap(entry, 0.0d)
                ? Map.of() : needs(entry, persona);
        if (needs.isEmpty()) {
            return 0;
        }
        int camp = BenchDutyDispatcher.fieldCamp(world, entry, persona);
        WorldKnowledge k = Decisions.active();
        if (camp == 0 || k == null || !world.config().BENCH_GATHER_AT_DROPPERS) {
            return camp; // its camp, whatever lives there
        }
        return site(k, entry, persona, needs.keySet(), level, camp, characterId);
    }

    /**
     * Where a gatherer goes (user, 2026-10-25: the best map for what it needs, not the nearest): its camp when a monster
     * there drops one of {@code items} for it; else the field it can reach where a round of kills brings the most of them
     * (FarmSites.gatherSites, summed over the items; the nearer one on a tie), passing over a field closed for now, one
     * its kind keeps dying at (RosterService.roughCampFor) and one it died at itself in its last slices there
     * (BenchDutyDispatcher.diesAt), and one that does not load. Its camp when every monster there drops what it needs (a
     * global drop); 0 when nothing it could farm drops it anywhere it can go, and the ledger keeps it.
     */
    private int site(WorldKnowledge k, RosterEntry entry, Persona persona, Set<Integer> items, int level, int camp,
                     int characterId) {
        Map<Integer, Double> drops = new HashMap<>();
        boolean anyMonster = false;
        for (int itemId : items) {
            FarmSites.gatherSites(k, itemId, level, camp, true).forEach((mapId, score) -> drops.merge(mapId, score, Double::sum));
            anyMonster |= FarmSites.anyMonsterDrops(k, itemId, camp);
        }
        if (drops.getOrDefault(camp, 0.0d) > 0.0d || drops.isEmpty() && anyMonster) {
            return camp;
        }
        List<Integer> sites = new ArrayList<>(drops.keySet());
        sites.sort(Comparator.comparingDouble((Integer mapId) -> -drops.get(mapId))
                .thenComparingInt(mapId -> k.distance(camp, mapId)).thenComparingInt(mapId -> mapId));
        RosterService roster = world.roster();
        for (int mapId : sites) {
            if (!BenchDutyDispatcher.closed(mapId) && (roster == null || !roster.roughCampFor(characterId, mapId))
                    && !world.benchDuty().diesAt(entry.characterId(), persona.id(), mapId)
                    && world.residents().map(mapId) != null) {
                return mapId;
            }
        }
        return 0;
    }

    @Override
    public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
        AgentRuntimeEntry entry = AgentInteractionRuntime.registerDirectorIdleAgent(agent);
        return entry != null && visit(slice, entry, agent, nowMs);
    }

    @Override
    public String check(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry roster, long nowMs) {
        MapleMap map = agent.getMap();
        if (map == null || map.isTown()) {
            return "back in town"; // died and respawned, or wandered off the field
        }
        if (map.getId() != slice.mapId()) {
            return "left its camp";
        }
        roster = world.benchSessions().harvest(slice, agent, roster);
        int ownerLevel = world.store().characterLevel(slice.ownerId());
        if (ownerLevel > 0 && agent.getLevel() >= ownerLevel) {
            return "would outlevel owner; back to the ledger";
        }
        if (!world.bench().withinDailyCap(roster, 0.0d)) {
            return "daily farming cap reached";
        }
        Persona persona = world.content().persona(slice.personaId());
        if (persona != null && needs(roster, persona).isEmpty()) {
            return "gathered what your quests need";
        }
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(slice.characterId());
        boolean watched = ResidentRuntime.humansIn(map) > 0;
        if (!AgentFieldVisitLeaseRuntime.active(entry)
                && (watched || nowMs - slice.startedAtMs() < world.config().BENCH_LIVE_SLICE_MS)) {
            // its visit ended while the slice runs on (a player is watching): another visit at the same camp
            return visit(slice, entry, agent, nowMs) ? null : "field visit ended";
        }
        if (watched) {
            renew(slice, entry, agent, nowMs); // its visit goes on past the next look
        }
        return null;
    }

    @Override
    public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
        visits.remove(slice.personaId());
        RosterEntry roster = agent == null ? null : world.store().rosterEntry(slice.ownerId(), slice.personaId());
        if (roster != null) {
            world.benchSessions().harvest(slice, agent, roster); // its last pickups; the hand-back ends the visit
        }
    }

    /**
     * Starts a field visit lease at the slice's camp for one field slice, aimed at the mobs dropping what it gathers, on
     * its own mailbox; true when it started.
     */
    private boolean visit(BenchDutyDispatcher.Slice slice, AgentRuntimeEntry entry, Character agent, long nowMs) {
        RosterEntry roster = world.store().rosterEntry(slice.ownerId(), slice.personaId());
        Persona persona = world.content().persona(slice.personaId());
        if (entry == null || roster == null || persona == null) {
            return false;
        }
        String requestId = "bench-gather:" + slice.personaId() + ':' + nowMs;
        AgentFieldIntent intent = new AgentFieldIntent(AgentFieldIntent.Type.FREE_GRIND, requestId,
                targets(needs(roster, persona).keySet(), agent.getMap()), Map.of(), false);
        AgentFieldVisitLeaseRequest lease = new AgentFieldVisitLeaseRequest(
                new AgentFieldEntryRequest(requestId, "living-bench", new AgentFieldVisitRequest(slice.mapId(), intent,
                        true, FIELD_PARTICIPANTS, true, null)),
                AgentFieldAdmissionMode.CREATE_OR_JOIN, nowMs + world.config().BENCH_LIVE_SLICE_MS, GRACEFUL_EXIT_MS,
                "bench gather slice elapsed");
        AgentFieldSessionResult result = BenchDutyDispatcher.onAgent(entry,
                runtime -> AgentFieldVisitLeaseRuntime.start(runtime, agent, lease, nowMs));
        if (!started(result)) {
            log.debug("bench gather for {} not started at {}: {}", slice.personaId(), slice.mapId(),
                    result == null ? "no result" : result.status() + " " + result.reason());
            return false;
        }
        visits.put(slice.personaId(), lease);
        return true;
    }

    /**
     * Its visit lease, renewed as the same field request when it would run out before the next look: the lease's watcher
     * is cleared and started again with a later exit, so the visit itself goes on without a break.
     */
    private void renew(BenchDutyDispatcher.Slice slice, AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentFieldVisitLeaseRequest lease = visits.get(slice.personaId());
        if (entry == null || lease == null || lease.exitAtMs() > nowMs + BenchDutyDispatcher.TICK_MS) {
            return;
        }
        AgentFieldVisitLeaseRequest later = new AgentFieldVisitLeaseRequest(lease.entryRequest(), lease.admissionMode(),
                nowMs + world.config().BENCH_LIVE_SLICE_MS, lease.gracefulTimeoutMs(), lease.exitReason());
        if (started(BenchDutyDispatcher.onAgent(entry, runtime -> {
            AgentFieldVisitLeaseRuntime.clear(runtime, agent);
            return AgentFieldVisitLeaseRuntime.start(runtime, agent, later, nowMs);
        }))) {
            visits.put(slice.personaId(), later);
        }
    }

    private static boolean started(AgentFieldSessionResult result) {
        return result != null && (result.started() || result.status() == AgentFieldSessionResult.Status.ALREADY_ACTIVE_SAME_REQUEST);
    }

    /** The mobs spawning on {@code map} that drop one of {@code items}; none when no dropper lives there. */
    private Set<Integer> targets(Set<Integer> items, MapleMap map) {
        Set<Integer> spawned = new HashSet<>();
        if (map != null) {
            for (SpawnPoint spawn : map.getMonsterSpawn()) {
                spawned.add(spawn.getMonsterId());
            }
        }
        Set<Integer> targets = new HashSet<>();
        for (int itemId : items) {
            for (int[] dropper : world.store().mobsDropping(itemId)) {
                if (spawned.contains(dropper[0])) {
                    targets.add(dropper[0]);
                }
            }
        }
        return targets;
    }

    /**
     * Item id to how many more its owner's quests need (BenchLedger's GATHER rule): each active COLLECT_ITEM step of a
     * quest that is not the companion's own, less the step's progress and what the haul already holds.
     */
    private Map<Integer, Integer> needs(RosterEntry entry, Persona persona) {
        Map<Integer, Integer> needs = new LinkedHashMap<>();
        for (QuestState state : world.store().questStates(entry.characterId(), QuestStatus.ACTIVE)) {
            Quest quest = world.content().quest(state.questId());
            Step step = quest == null ? null : quest.step(state.stepIndex());
            if (step == null || step.type() != StepType.COLLECT_ITEM || quest.persona().equals(persona.id())) {
                continue;
            }
            int remaining = step.count() - state.progress() - world.bench().haulCount(entry, step.itemId());
            if (remaining > 0) {
                needs.merge(step.itemId(), remaining, Math::max);
            }
        }
        return needs;
    }
}
