package server.agents.capabilities.partyquest.kpq;

import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * When each boundary of one KPQ run's pace was first seen, for {@link AgentKpqPaceReport}: the phase entries, stage 1's
 * grind (its first coupon question, each pass), every puzzle stage's first judged check and its accept, stage 5's race
 * (the companions' arrival, the normals and King Slime down, the loot delay, the passes at the leader, the leader's own
 * arrival), and what happened to each member on the way (downs, tap-outs, departures). Only marks: the report turns
 * them into spans. Each boundary keeps its first sighting, so a mark seen again on a later tick changes nothing.
 * Owned by its session; every method locks this object, since the run's termination may read it from another thread.
 */
final class AgentKpqPaceMarks {
    private final Map<AgentKpqSession.Phase, Long> phaseEnteredAtMs = new EnumMap<>(AgentKpqSession.Phase.class);
    private List<AgentPqMemberPower> members = List.of();
    private final Set<Integer> collectors = new LinkedHashSet<>();
    private long grindStartMs;
    private final Map<Integer, Integer> couponTargets = new TreeMap<>();
    private final Map<Integer, Long> passCreatedAtMs = new TreeMap<>();
    private boolean stageOnePassesRestored;
    private final Map<Integer, StageOneCombat> stageOneCombat = new TreeMap<>();
    private boolean stageOneCombatRecorded;
    private final Map<Integer, Long> firstJudgedAtMs = new TreeMap<>();
    private final Map<Integer, Long> acceptedAtMs = new TreeMap<>();
    private final Map<Integer, Integer> checks = new TreeMap<>();
    private long companionsOnStageFiveAtMs;
    private long leaderOnStageFiveAtMs;
    private long normalsClearedAtMs;
    private long bossDeadAtMs;
    private long lootDelayFromMs;
    private long lootDelayUntilMs;
    private long passesAtLeaderAtMs;
    private boolean stageFivePassesRestored;
    private final List<Long> downAtMs = new ArrayList<>();
    private final Map<Integer, Integer> deaths = new TreeMap<>();
    private final Set<Integer> tappedOut = new TreeSet<>();
    private long firstDepartureAtMs;
    private final Map<String, Simulation> killSimulation = new TreeMap<>();
    private long simulationSampledAtMs;
    private final Set<String> loggedSpans = new HashSet<>();
    private int revision;
    private int loggedRevision = -1;

    /** A member's stage-1 swings and kills, as the stage-1 summary counted them. */
    record StageOneCombat(long attacks, long kills) {
    }

    /** Member-milliseconds of the Agents standing in the run during a kill segment, and how many of them throttled. */
    record Simulation(long agentMs, long throttledMs) {
        double throttledShare() {
            return agentMs <= 0L ? 0.0d : throttledMs / (double) agentMs;
        }
    }

    synchronized void markPhase(AgentKpqSession.Phase phase, long nowMs) {
        if (phase != null && phaseEnteredAtMs.putIfAbsent(phase, nowMs) == null) revision++;
    }

    /** Stage 1 starts: each member's power from warmed data (empty while cold), and who answers a Cloto question. */
    synchronized void beginStageOne(List<AgentPqMemberPower> power, Collection<Integer> nonLeaders) {
        members = power == null ? List.of() : List.copyOf(power);
        collectors.clear();
        if (nonLeaders != null) collectors.addAll(nonLeaders);
        revision++;
    }

    /** A collector got its coupon number: the first one ends the opening and starts the grind. */
    synchronized void markCouponTarget(int characterId, int target, long nowMs) {
        if (target <= 0) return;
        couponTargets.putIfAbsent(characterId, target);
        if (grindStartMs == 0L) grindStartMs = Math.max(1L, nowMs);
        revision++;
    }

    synchronized void markPassCreated(int characterId, long nowMs) {
        if (passCreatedAtMs.putIfAbsent(characterId, Math.max(1L, nowMs)) == null) revision++;
    }

    /** Stage 1 cleared on passes the run made up or forced, not on its own hand-in. */
    synchronized void markStageOnePassesRestored() {
        stageOnePassesRestored = true;
        revision++;
    }

    synchronized void recordStageOneCombat(int characterId, long attacks, long kills) {
        stageOneCombat.put(characterId, new StageOneCombat(Math.max(0L, attacks), Math.max(0L, kills)));
    }

    /** The stage-1 summary has counted every Agent's swings and kills, which the grind's line reads. */
    synchronized void markStageOneCombatRecorded() {
        stageOneCombatRecorded = true;
        revision++;
    }

    /** A Cloto verdict on a puzzle stage's formation, accepted or not, reached the coordinator. */
    synchronized void markJudged(int stage, long nowMs) {
        if (firstJudgedAtMs.putIfAbsent(stage, Math.max(1L, nowMs)) == null) revision++;
    }

    /** Cloto accepted the stage on its {@code checkCount}-th judged formation. */
    synchronized void markAccepted(int stage, int checkCount, long nowMs) {
        if (acceptedAtMs.containsKey(stage)) return;
        long at = Math.max(1L, nowMs);
        firstJudgedAtMs.putIfAbsent(stage, at);
        acceptedAtMs.put(stage, at);
        checks.put(stage, Math.max(1, checkCount));
        revision++;
    }

    synchronized long acceptedAtMs(int stage) {
        return acceptedAtMs.getOrDefault(stage, 0L);
    }

    synchronized void markCompanionsOnStageFive(long nowMs) {
        if (companionsOnStageFiveAtMs != 0L) return;
        companionsOnStageFiveAtMs = Math.max(1L, nowMs);
        revision++;
    }

    synchronized boolean companionsOnStageFive() {
        return companionsOnStageFiveAtMs != 0L;
    }

    synchronized void markLeaderOnStageFive(long nowMs) {
        if (leaderOnStageFiveAtMs != 0L) return;
        leaderOnStageFiveAtMs = Math.max(1L, nowMs);
        revision++;
    }

    synchronized boolean leaderOnStageFive() {
        return leaderOnStageFiveAtMs != 0L;
    }

    /** Stage 5's mob counts as a member there sees them, once the companions are there. */
    synchronized void observeStageFiveMobs(int normalAlive, int bossAlive, long nowMs) {
        if (companionsOnStageFiveAtMs == 0L) return;
        long at = Math.max(1L, nowMs);
        if (normalsClearedAtMs == 0L && normalAlive == 0) {
            normalsClearedAtMs = at;
            revision++;
        }
        if (normalsClearedAtMs != 0L && bossDeadAtMs == 0L && bossAlive == 0) {
            bossDeadAtMs = at;
            revision++;
        }
    }

    /** King Slime's loot delay began at {@code fromMs} and lets the passes be looted from {@code untilMs}. */
    synchronized void markLootDelay(long fromMs, long untilMs) {
        if (lootDelayUntilMs != 0L) return;
        lootDelayFromMs = Math.max(1L, fromMs);
        lootDelayUntilMs = Math.max(lootDelayFromMs, untilMs);
        revision++;
    }

    synchronized void markPassesAtLeader(long nowMs) {
        if (passesAtLeaderAtMs != 0L) return;
        passesAtLeaderAtMs = Math.max(1L, nowMs);
        revision++;
    }

    /** Stage 5's passes were made up from nothing, so the carry did not end on its own. */
    synchronized void markStageFivePassesRestored() {
        stageFivePassesRestored = true;
        revision++;
    }

    /** A member went down (each down once); the stage logic pauses for it. */
    synchronized void markDown(int characterId, long nowMs) {
        downAtMs.add(Math.max(1L, nowMs));
        deaths.merge(characterId, 1, Integer::sum);
        revision++;
    }

    synchronized void markTappedOut(int characterId) {
        if (tappedOut.add(characterId)) revision++;
    }

    /** A member left the run mid-way (tapped out, dropped, ejected): the fighters are no longer the ones measured. */
    synchronized void markDeparture(long nowMs) {
        if (firstDepartureAtMs == 0L) firstDepartureAtMs = Math.max(1L, nowMs);
        revision++;
    }

    /**
     * Adds the time since the last sample to the kill segment open now: {@code agents} Agents stood in the run, of
     * which {@code throttled} were not simulated at the presentation rate. A tick outside a kill segment only moves
     * the sample clock.
     */
    synchronized void sampleSimulation(String killSegment, int agents, int throttled, long nowMs) {
        long elapsedMs = simulationSampledAtMs == 0L ? 0L : Math.max(0L, nowMs - simulationSampledAtMs);
        simulationSampledAtMs = Math.max(1L, nowMs);
        if (killSegment == null || elapsedMs == 0L || agents <= 0) return;
        Simulation before = killSimulation.getOrDefault(killSegment, new Simulation(0L, 0L));
        killSimulation.put(killSegment, new Simulation(before.agentMs() + elapsedMs * agents,
                before.throttledMs() + elapsedMs * Math.max(0, Math.min(agents, throttled))));
    }

    /** The kill segment running now, or null: stage 1's grind, then stage 5's normals and King Slime. */
    synchronized String openKillSegment(AgentKpqSession.Phase phase) {
        if (phase == AgentKpqSession.Phase.STAGE_1 && grindStartMs != 0L && !everyPassCreated()) {
            return AgentKpqPaceProfile.S1_GRIND;
        }
        if (companionsOnStageFiveAtMs != 0L && normalsClearedAtMs == 0L) return AgentKpqPaceProfile.S5_NORMALS;
        if (normalsClearedAtMs != 0L && bossDeadAtMs == 0L) return AgentKpqPaceProfile.S5_BOSS;
        return null;
    }

    private boolean everyPassCreated() {
        return !collectors.isEmpty() && passCreatedAtMs.keySet().containsAll(collectors);
    }

    /** True once per span key: the one line each span gets. */
    synchronized boolean claimSpanLine(String key) {
        return loggedSpans.add(key);
    }

    /** Whether a mark changed since the last time the lines were written; claims the current state if so. */
    synchronized boolean claimRevision() {
        if (loggedRevision == revision) return false;
        loggedRevision = revision;
        return true;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(Collections.unmodifiableMap(new EnumMap<>(phaseEnteredAtMs)), members, List.copyOf(collectors), grindStartMs,
                Map.copyOf(couponTargets), Map.copyOf(passCreatedAtMs), stageOnePassesRestored,
                Map.copyOf(stageOneCombat), stageOneCombatRecorded, Map.copyOf(firstJudgedAtMs),
                Map.copyOf(acceptedAtMs), Map.copyOf(checks), companionsOnStageFiveAtMs, leaderOnStageFiveAtMs,
                normalsClearedAtMs, bossDeadAtMs, lootDelayFromMs, lootDelayUntilMs, passesAtLeaderAtMs,
                stageFivePassesRestored, List.copyOf(downAtMs), Map.copyOf(deaths), Set.copyOf(tappedOut),
                firstDepartureAtMs, Map.copyOf(killSimulation));
    }

    /** The marks at one moment; 0 is a boundary not seen. */
    record Snapshot(Map<AgentKpqSession.Phase, Long> phaseEnteredAtMs, List<AgentPqMemberPower> members,
                    List<Integer> collectors, long grindStartMs, Map<Integer, Integer> couponTargets,
                    Map<Integer, Long> passCreatedAtMs, boolean stageOnePassesRestored,
                    Map<Integer, StageOneCombat> stageOneCombat, boolean stageOneCombatRecorded,
                    Map<Integer, Long> firstJudgedAtMs, Map<Integer, Long> acceptedAtMs, Map<Integer, Integer> checks,
                    long companionsOnStageFiveAtMs, long leaderOnStageFiveAtMs, long normalsClearedAtMs,
                    long bossDeadAtMs, long lootDelayFromMs, long lootDelayUntilMs, long passesAtLeaderAtMs,
                    boolean stageFivePassesRestored, List<Long> downAtMs, Map<Integer, Integer> deaths,
                    Set<Integer> tappedOut, long firstDepartureAtMs, Map<String, Simulation> killSimulation) {
        long phase(AgentKpqSession.Phase phase) {
            return phaseEnteredAtMs.getOrDefault(phase, 0L);
        }

        /** When the last collector's pass came; 0 until every collector has one. */
        long lastPassAtMs() {
            if (collectors.isEmpty() || !passCreatedAtMs.keySet().containsAll(collectors)) return 0L;
            long last = 0L;
            for (int collector : collectors) last = Math.max(last, passCreatedAtMs.get(collector));
            return last;
        }

        /** The coupons Cloto asked the collectors for; -1 while any collector's number is unknown. */
        int couponsAsked() {
            if (collectors.isEmpty() || !couponTargets.keySet().containsAll(collectors)) return -1;
            int total = 0;
            for (int collector : collectors) total += couponTargets.get(collector);
            return total;
        }
    }
}
