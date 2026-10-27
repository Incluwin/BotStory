package server.agents.capabilities.combat;

import client.Character;
import config.YamlConfig;
import server.agents.behavior.AgentBehaviorCalibrationState;
import server.agents.behavior.AgentBehaviorPolicyProfile;
import server.agents.behavior.AgentBehaviorRuntime;
import server.agents.behavior.AgentBehaviorFeatureProfile;
import server.agents.capabilities.behavior.AgentCrowdScalingPolicy;
import server.agents.integration.cosmic.CosmicAgentPerceptionSnapshotFactory;
import server.agents.model.AgentPosition;
import server.agents.perception.AgentPerceptionSnapshot;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import server.agents.capabilities.behavior.AgentBehaviorTelemetry;
import server.agents.capabilities.behavior.AgentImperfectionPolicy;
import server.agents.capabilities.behavior.AgentImperfections;
import server.agents.capabilities.behavior.AgentTellProbe;

/** Combat adapter for response latency, claim tolerance, and profile target diversity. */
public final class AgentCombatBehaviorRuntime {
    private static final int AVAILABLE_MOB_RESPONSE_MAX_MS = config.AgentTuning.intValue(
            "server.agents.capabilities.combat.AgentCombatBehaviorRuntime.AVAILABLE_MOB_RESPONSE_MAX_MS");

    private AgentCombatBehaviorRuntime() {
    }

    public static boolean responseReady(
            AgentRuntimeEntry entry, Character agent, List<Monster> candidates, long nowMs) {
        if (!AgentBehaviorRuntime.enabled(entry) || !AgentBehaviorFeatureProfile.current().enabled()) {
            if ((AgentTellProbe.on() || AgentImperfections.on()) && entry != null && candidates != null
                    && !candidates.isEmpty()) {
                // no calibration: it acts on the tick it first sees them (0 ms in the tell audit), unless it is a
                // citizen or companion the imperfections gate calibrates (F8.1): then a human reaction time first
                AgentCombatBehaviorState state = entry.capabilityStates().require(AgentCombatBehaviorState.STATE_KEY);
                boolean hit = AgentCombatCooldownStateRuntime.hasMobHitCooldown(entry);
                int delayMs = state.waiting() || hit ? 0 : humanReactionMs(agent, candidates);
                if (hit) {
                    state.respondNow(nowMs); // being hit is answered at once
                }
                if (!state.ready(1L, nowMs, delayMs)) {
                    AgentBehaviorTelemetry.responseDeferred();
                    return false;
                }
                tellReaction(state, agent, nowMs);
            }
            return true;
        }
        if (candidates == null || candidates.isEmpty()) {
            entry.capabilityStates().require(AgentCombatBehaviorState.STATE_KEY).clearStimulus();
            return false;
        }
        entry.capabilityStates().require(AgentCombatBehaviorState.STATE_KEY).markCandidateOpportunity();
        long stimulus = candidates.stream().mapToLong(Monster::getObjectId).sorted()
                .reduce(candidates.size(), (left, right) -> left * 31L + right);
        AgentBehaviorCalibrationState calibration = AgentBehaviorRuntime.calibration(entry);
        AgentPerceptionSnapshot perception =
                CosmicAgentPerceptionSnapshotFactory.capture(agent, nowMs);
        int jitterPercent = calibration.stablePercent("response", stimulus) / 5;
        int responseDelayMs = AgentCrowdScalingPolicy.responseDelayMs(
                calibration.responseBaselineMs(),
                jitterPercent,
                AgentCrowdScalingPolicy.totalCharacters(perception));
        responseDelayMs = Math.min(responseDelayMs, AVAILABLE_MOB_RESPONSE_MAX_MS);
        AgentCombatBehaviorState state = entry.capabilityStates().require(AgentCombatBehaviorState.STATE_KEY);
        boolean ready = state.ready(stimulus, nowMs, responseDelayMs);
        if (!ready) AgentBehaviorTelemetry.responseDeferred();
        else tellReaction(state, agent, nowMs);
        return ready;
    }

    /**
     * F8.1: a calibrated citizen's or companion's time to go for a new set of mobs (AgentImperfectionPolicy), within
     * AVAILABLE_MOB_RESPONSE_MAX_MS; 0 for every other Agent and in excluded content. Only new targets wait: a hit
     * Agent answers at once, and heals, potions and survival buffs never come through here.
     */
    private static int humanReactionMs(Character agent, List<Monster> candidates) {
        if (agent == null || !AgentImperfections.calibrated(agent)) {
            return 0;
        }
        long sighting = candidates.stream().mapToLong(Monster::getObjectId).sorted()
                .reduce(candidates.size(), (left, right) -> left * 31L + right);
        return AgentImperfectionPolicy.reactionDelayMs(agent.getId(), sighting, AVAILABLE_MOB_RESPONSE_MAX_MS);
    }

    /** The tell audit times the first ready tick of each wait for a target, from the wait's start. */
    private static void tellReaction(AgentCombatBehaviorState state, Character agent, long nowMs) {
        if (!AgentTellProbe.on() || agent == null) return;
        long reactionMs = state.takeReactionMs(nowMs);
        if (reactionMs >= 0) AgentTellProbe.combatReaction(agent.getId(), reactionMs);
    }

    public static List<Monster> respectClaims(AgentRuntimeEntry entry,
                                               List<Monster> candidates,
                                               Map<Monster, Integer> occupancy) {
        if (!AgentBehaviorRuntime.enabled(entry) || !AgentBehaviorFeatureProfile.current().enabled()
                || candidates.size() < 2) return candidates;
        AgentBehaviorPolicyProfile policy = AgentBehaviorRuntime.policy(entry);
        int driveBonus = AgentBehaviorRuntime.adaptation(entry).combatDrive() >= 75 ? 1 : 0;
        int tolerance = policy.targeting().claimTolerance() + driveBonus;
        List<Monster> available = candidates.stream()
                .filter(candidate -> occupancy.getOrDefault(candidate, 0) < tolerance)
                .toList();
        if (!available.isEmpty() && available.size() < candidates.size()
                && AgentBehaviorRuntime.calibration(entry).nextPercent("claim-avoid")
                < policy.targeting().claimAvoidPercent()) {
            AgentBehaviorTelemetry.claimAlternative();
            return new ArrayList<>(available);
        }
        return candidates;
    }

    /**
     * Field territories use the existing peer target observation as a deterministic soft claim.
     * An unclaimed alternative wins; sharing remains legal when every candidate is occupied.
     */
    public static List<Monster> respectFieldClaims(
            List<Monster> candidates, Map<Monster, Integer> occupancy) {
        if (candidates == null || candidates.size() < 2 || occupancy == null || occupancy.isEmpty()) {
            return candidates;
        }
        List<Monster> unclaimed = candidates.stream()
                .filter(candidate -> occupancy.getOrDefault(candidate, 0) == 0)
                .toList();
        return unclaimed.isEmpty() ? candidates : new ArrayList<>(unclaimed);
    }

    public static int selectTargetIndex(
            AgentRuntimeEntry entry, Character agent, int candidateCount) {
        if (!AgentBehaviorRuntime.enabled(entry) || !AgentBehaviorFeatureProfile.current().enabled()
                || candidateCount < 2) return -1;
        AgentPerceptionSnapshot perception =
                CosmicAgentPerceptionSnapshotFactory.capture(agent, System.currentTimeMillis());
        AgentPosition position = agent == null || agent.getPosition() == null
                ? null : new AgentPosition(agent.getPosition().x, agent.getPosition().y);
        int variationPercent = AgentCrowdScalingPolicy.targetVariationPercent(
                AgentCrowdScalingPolicy.localCharacters(perception, position));
        if (variationPercent <= 0) {
            return 0;
        }
        AgentBehaviorPolicyProfile.Targeting policy = AgentBehaviorRuntime.policy(entry).targeting();
        int nearWeight = policy.nearWeight() * variationPercent / 100;
        int middleWeight = policy.middleWeight() * variationPercent / 100;
        int bestWeight = policy.bestWeight()
                + policy.nearWeight() - nearWeight
                + policy.middleWeight() - middleWeight;
        int total = bestWeight + nearWeight + middleWeight;
        int roll = AgentBehaviorRuntime.calibration(entry).nextPercent("target") * total / 100;
        if (roll < bestWeight) return 0;
        if (roll < bestWeight + nearWeight) {
            return Math.min(candidateCount - 1, 1 + candidateCount / 4);
        }
        return Math.min(candidateCount - 1, candidateCount / 2);
    }

    public static boolean anchorRole(AgentRuntimeEntry entry, Character agent) {
        if (!AgentBehaviorRuntime.enabled(entry)
                || !AgentBehaviorFeatureProfile.current().enabled()) {
            return false;
        }
        AgentPerceptionSnapshot perception =
                CosmicAgentPerceptionSnapshotFactory.capture(agent, System.currentTimeMillis());
        AgentPosition position = agent == null || agent.getPosition() == null
                ? null : new AgentPosition(agent.getPosition().x, agent.getPosition().y);
        int effectivePercent = AgentCrowdScalingPolicy.anchorPercent(
                AgentBehaviorRuntime.policy(entry).targeting().anchorPercent(),
                AgentCrowdScalingPolicy.localCharacters(perception, position));
        return AgentBehaviorRuntime.calibration(entry).stablePercent("anchor", 0L)
                < effectivePercent;
    }

    public static boolean waitingForResponse(AgentRuntimeEntry entry) {
        return entry != null && entry.capabilityStates().find(AgentCombatBehaviorState.STATE_KEY)
                .map(AgentCombatBehaviorState::responseDeferred).orElse(false);
    }

    public static boolean candidateOpportunity(AgentRuntimeEntry entry) {
        return entry != null && entry.capabilityStates().find(AgentCombatBehaviorState.STATE_KEY)
                .map(AgentCombatBehaviorState::candidateOpportunity).orElse(false);
    }

    public static void noCandidateOpportunity(AgentRuntimeEntry entry) {
        if (entry != null) entry.capabilityStates().find(AgentCombatBehaviorState.STATE_KEY)
                .ifPresent(AgentCombatBehaviorState::clearStimulus);
    }

    public static void targetAcquired(AgentRuntimeEntry entry) {
        if (entry != null) entry.capabilityStates().find(AgentCombatBehaviorState.STATE_KEY)
                .ifPresent(AgentCombatBehaviorState::clearStimulus);
    }
}
