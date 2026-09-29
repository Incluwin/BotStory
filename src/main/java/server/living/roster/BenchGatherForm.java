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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The GATHER form of the bench (docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench", decision 12 a): a visible drop-pickup
 * field lease at the companion's camp. While its owner's quests still need items it can gather (the ledger's GATHER
 * rule: active COLLECT_ITEM steps of other personas' quests, less what the haul already holds), it loads at its camp
 * field under a BENCH_LIVE seat, registered director-idle, with a field visit lease whose targets are the mobs there
 * that drop those items (it fights only them, or whatever the camp has when none of them lives there). What it picks
 * up is harvested into the haul by the field form's rules (kill loot and meso only, BenchSessions.harvest). It ends
 * with what its owner's quests need, back in town, off its camp, at its owner's level or the daily farming cap.
 */
public final class BenchGatherForm implements BenchDutyDispatcher.Form {
    private static final Logger log = LoggerFactory.getLogger(BenchGatherForm.class);
    /** Agents one camp's field session takes (the plans' default). */
    private static final int FIELD_PARTICIPANTS = 6;
    private static final long GRACEFUL_EXIT_MS = 30_000L;

    private final LivingWorld world;

    public BenchGatherForm(LivingWorld world) {
        this.world = world;
    }

    @Override
    public CompanionBudget.Kind pool() {
        return CompanionBudget.Kind.BENCH_LIVE;
    }

    /** Its field camp while its owner's quests need something and it may farm: below its owner's level, within the cap. */
    @Override
    public int mapFor(RosterEntry entry, Persona persona, int characterId) {
        int ownerLevel = world.store().characterLevel(entry.characterId());
        if (ownerLevel <= 0 || world.store().characterLevel(characterId) >= ownerLevel
                || !world.bench().withinDailyCap(entry, 0.0d) || needs(entry, persona).isEmpty()) {
            return 0;
        }
        return BenchDutyDispatcher.fieldCamp(world, entry, persona);
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
        if (!AgentFieldVisitLeaseRuntime.active(entry)
                && (ResidentRuntime.humansIn(map) > 0 || nowMs - slice.startedAtMs() < world.config().BENCH_LIVE_SLICE_MS)) {
            // its visit ended while the slice runs on (a player is watching): another visit at the same camp
            return visit(slice, entry, agent, nowMs) ? null : "field visit ended";
        }
        return null;
    }

    @Override
    public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
        RosterEntry roster = agent == null ? null : world.store().rosterEntry(slice.ownerId(), slice.personaId());
        if (roster != null) {
            world.benchSessions().harvest(slice, agent, roster); // its last pickups; the hand-back ends the visit
        }
    }

    /** Starts a field visit lease at the slice's camp for one field slice, aimed at the mobs dropping what it gathers. */
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
        AgentFieldSessionResult result = AgentFieldVisitLeaseRuntime.start(entry, agent, lease, nowMs);
        if (result == null || !result.started() && result.status() != AgentFieldSessionResult.Status.ALREADY_ACTIVE_SAME_REQUEST) {
            log.debug("bench gather for {} not started at {}: {}", slice.personaId(), slice.mapId(),
                    result == null ? "no result" : result.status() + " " + result.reason());
            return false;
        }
        return true;
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
