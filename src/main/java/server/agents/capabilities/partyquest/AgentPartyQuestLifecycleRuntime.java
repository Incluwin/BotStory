package server.agents.capabilities.partyquest;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.movement.AgentMovementBroadcastService;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.movement.AgentMovementStateResetService;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestTestDeploymentRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.capabilities.townlife.AgentTownLifeVisitRequest;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps party-quest Agents owned until an active event, test hold, or ordinary system receives them. */
public final class AgentPartyQuestLifecycleRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentPartyQuestLifecycleRuntime.class);
    private static final long RECOVERY_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime.RECOVERY_RETRY_MS");
    private static final long RECOVERY_WARN_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime.RECOVERY_WARN_MS");
    private static final long POST_RUN_HOLD_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime.POST_RUN_HOLD_MS");

    private AgentPartyQuestLifecycleRuntime() {
    }

    public static boolean active(int characterId) {
        return AgentPartyQuestEngagementRegistry.active(characterId);
    }

    public static boolean tick(int characterId, long nowMs) {
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forMember(characterId);
        if (engagement == null || !engagement.ownsAgent(characterId)) return false;
        if (engagement.state() == AgentPartyQuestEngagement.State.POST_RUN_HOLD
                && nowMs - engagement.stateEnteredAtMs() >= Math.max(1_000L, POST_RUN_HOLD_MS)) {
            if (engagement.mode() == AgentPartyQuestEngagement.Mode.TEST_OBSERVATION
                    && AgentPartyQuestTestDeploymentRuntime.transition(engagement, nowMs)) {
                return true;
            }
            engagement.beginRecovery("post-run lobby hold completed", nowMs);
        }
        if (engagement.claimRecoveryAttempt(nowMs, Math.max(1_000L, RECOVERY_RETRY_MS))) {
            recover(engagement, nowMs);
        }
        return true;
    }

    public static void childFinished(String sessionId, boolean success, String reason, long nowMs) {
        childFinished(sessionId, success, reason, nowMs, null);
    }

    /** As above, with the run's pace for the host's clear-time records (null when the quest reports none). */
    public static void childFinished(String sessionId, boolean success, String reason, long nowMs, AgentPqPace pace) {
        if (sessionId == null || sessionId.isBlank()) return;
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.engagements().stream()
                .filter(candidate -> sessionId.equals(candidate.activeSessionId()))
                .findFirst().orElse(null);
        if (engagement == null) {
            AgentPartyQuestHandback.runEnded(
                    new AgentPartyQuestHandback.RunEnded("", sessionId, 0, success, reason, nowMs, pace));
            return;
        }
        engagement.finishRun(success, reason, nowMs);
        // A cleared PRODUCTION run stood its companions idle through the hold and kept the operator's engagement,
        // so the leader's next run was refused ("operator already owns a party-quest engagement"). With every
        // Agent handed back there is nothing to hold.
        if (skipsPostRunHold(engagement.mode(), engagement.state(),
                AgentPartyQuestHandback.anyTownLife(engagement.agentIds(), engagement.questKey()))) {
            engagement.beginRecovery("post-run hold skipped: every Agent is handed back", nowMs);
        }
        if (engagement.state() == AgentPartyQuestEngagement.State.RECOVERING) recover(engagement, nowMs);
        AgentPartyQuestHandback.runEnded(new AgentPartyQuestHandback.RunEnded(
                engagement.questKey(), sessionId, engagement.operatorId(), success, reason, nowMs, pace));
    }

    /** Only a test run (its next run starts from the hold) or an Agent bound for TownLife still needs the hold. */
    static boolean skipsPostRunHold(AgentPartyQuestEngagement.Mode mode,
                                    AgentPartyQuestEngagement.State state,
                                    boolean anyTownLife) {
        return mode != AgentPartyQuestEngagement.Mode.TEST_OBSERVATION
                && state == AgentPartyQuestEngagement.State.POST_RUN_HOLD && !anyTownLife;
    }

    public static boolean requestStop(int characterId, String reason, long nowMs) {
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forMember(characterId);
        if (engagement == null) return true;
        if (engagement.state() == AgentPartyQuestEngagement.State.ACTIVE_EVENT) return false;
        if (!engagement.lobbyId().isBlank()) AgentPartyQuestLobbyRuntime.unregister(engagement.lobbyId(), nowMs);
        engagement.beginRecovery(reason, nowMs);
        recover(engagement, nowMs);
        return AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) == null;
    }

    public static void forceStop(int characterId, String reason, long nowMs) {
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forMember(characterId);
        if (engagement == null) return;
        if (!engagement.lobbyId().isBlank()) AgentPartyQuestLobbyRuntime.unregister(engagement.lobbyId(), nowMs);
        engagement.beginRecovery(reason, nowMs);
        recover(engagement, nowMs);
    }

    /** Runtime teardown is terminal for the whole engagement; surviving Agents remain owned through recovery. */
    public static void runtimeRemoved(int characterId, String reason, long nowMs) {
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forMember(characterId);
        if (engagement == null) return;
        if (!engagement.lobbyId().isBlank()) AgentPartyQuestLobbyRuntime.unregister(engagement.lobbyId(), nowMs);
        engagement.beginRecovery(reason, nowMs);
        recover(engagement, nowMs);
    }

    /** Test-owned Agents are disconnected by the harness, so no fallback activity is needed. */
    public static void closeTest(AgentPartyQuestEngagement engagement, long nowMs) {
        if (engagement == null) return;
        if (!engagement.lobbyId().isBlank()) AgentPartyQuestLobbyRuntime.unregister(engagement.lobbyId(), nowMs);
        engagement.close(nowMs);
        AgentPartyQuestEngagementRegistry.remove(engagement);
    }

    public static boolean recover(AgentPartyQuestEngagement engagement, long nowMs) {
        if (engagement == null || engagement.state() != AgentPartyQuestEngagement.State.RECOVERING) return false;
        boolean allRecovered = true;
        List<AgentRuntimeEntry> released = new ArrayList<>();
        Map<AgentRuntimeEntry, Character> followers = new LinkedHashMap<>();
        for (int agentId : engagement.agentIds()) {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agentId);
            Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
            if (entry == null || agent == null) continue;
            if (AgentTownLifeRuntime.active(entry)) continue;
            AgentPartyQuestHandback.Handback handback =
                    AgentPartyQuestHandback.handback(agentId, engagement.questKey());
            if (handback == AgentPartyQuestHandback.Handback.RELEASE) {
                released.add(entry);
                continue;
            }
            if (handback == AgentPartyQuestHandback.Handback.FOLLOW_LEADER) {
                // Never warped: a follower leaves the event by the event's own exit or its respawn, then follows.
                if (agent.getHp() <= 0 || agent.getEventInstance() != null) {
                    allRecovered = false;
                    engagement.addDiagnostic("Handback deferred for " + agent.getName()
                            + ": still in the event or down", nowMs);
                } else {
                    followers.put(entry, agent);
                }
                continue;
            }
            AgentPartyQuestDefinition definition = AgentPartyQuestCatalog.find(engagement.questKey());
            if (definition == null) {
                allRecovered = false;
                engagement.addDiagnostic("No recovery definition for " + engagement.questKey(), nowMs);
                continue;
            }
            if (agent.getMapId() != definition.recoveryMapId()) {
                moveToRecoveryMap(agent, definition.recoveryMapId());
            }
            var result = AgentTownLifeRuntime.requestLocal(
                    entry, agent, AgentTownLifeVisitRequest.leisure(agent.getMapId()),
                    AgentTownLifeAdmissionMode.MANUAL_ONLY, nowMs, agent.getId());
            if (!result.started()) {
                allRecovered = false;
                engagement.addDiagnostic("TownLife recovery deferred for " + agent.getName()
                        + ": " + result.status() + " " + result.reason(), nowMs);
            }
        }
        if (allRecovered) {
            engagement.close(nowMs);
            AgentPartyQuestEngagementRegistry.remove(engagement);
            // Handed-back Agents move only once the engagement is gone, so the party-quest tick no longer owns them.
            released.forEach(AgentMovementCommandRuntime::stop);
            followers.forEach(AgentPartyQuestLifecycleRuntime::followLeader);
            return true;
        }
        long warningIntervalMs = Math.max(5_000L, RECOVERY_WARN_MS);
        if (nowMs - engagement.stateEnteredAtMs() >= warningIntervalMs
                && engagement.claimRecoveryWarning(nowMs, warningIntervalMs)) {
            log.warn("Party-quest engagement remains in owned recovery: engagement={} quest={} members={} diagnostics={}",
                    engagement.engagementId(), engagement.questKey(), engagement.memberIds(),
                    engagement.diagnostics());
        }
        return false;
    }

    /** Rehomes one member removed from an active party without ending the remaining party quest (a KPQ member). */
    public static void recoverDetachedMember(int characterId, long nowMs) {
        recoverDetachedMember(characterId, "kpq", nowMs);
    }

    /**
     * Rehomes one member removed from an active party without ending the remaining party quest. {@code questKey} is
     * the caller's quest, used when the member is no longer in its engagement's index; an indexed member is rehomed
     * by its engagement's own quest.
     */
    public static void recoverDetachedMember(int characterId, String questKey, long nowMs) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        if (entry == null || agent == null) return;
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forMember(characterId);
        String quest = engagement == null ? questKey : engagement.questKey();
        AgentPartyQuestHandback.Handback handback = AgentPartyQuestHandback.handback(characterId, quest);
        if (handback == AgentPartyQuestHandback.Handback.RELEASE) {
            AgentMovementCommandRuntime.stop(entry);
            return;
        }
        if (handback == AgentPartyQuestHandback.Handback.FOLLOW_LEADER) {
            if (agent.getHp() > 0 && agent.getEventInstance() == null) {
                followLeader(entry, agent);
            } else {
                AgentMovementCommandRuntime.stop(entry);
            }
            return;
        }
        AgentPartyQuestDefinition definition = AgentPartyQuestCatalog.find(quest);
        // A detached member may already have been removed from the engagement index: it is rehomed by the
        // caller's quest, and an unknown quest keeps the legacy KPQ recovery destination.
        int recoveryMapId = definition == null
                ? AgentPartyQuestCatalog.require("kpq").recoveryMapId()
                : definition.recoveryMapId();
        if (agent.getMapId() != recoveryMapId) moveToRecoveryMap(agent, recoveryMapId);
        if (!AgentTownLifeRuntime.active(entry)) {
            AgentTownLifeRuntime.requestLocal(
                    entry, agent, AgentTownLifeVisitRequest.leisure(agent.getMapId()),
                    AgentTownLifeAdmissionMode.MANUAL_ONLY, nowMs, agent.getId());
        }
    }

    /**
     * Follows the Agent's runtime leader, resolved here because termination's stop cleared the follow target
     * (chat routing may have re-pointed the interaction target); with the leader offline it only stops.
     */
    private static void followLeader(AgentRuntimeEntry entry, Character agent) {
        int leaderId = AgentRuntimeRegistry.leaderIdForAgentCharacter(agent.getId());
        Character leader = leaderId <= 0 || leaderId == agent.getId()
                ? null : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(leaderId);
        if (leader == null) {
            AgentMovementCommandRuntime.stop(entry);
        } else {
            AgentMovementCommandRuntime.follow(entry, leader);
        }
    }

    private static void moveToRecoveryMap(Character agent, int recoveryMapId) {
        MapleMap map = AgentMapGatewayRuntime.map().resolveMap(
                AgentClientGatewayRuntime.clients().world(agent),
                AgentClientGatewayRuntime.clients().channel(agent), recoveryMapId);
        var portal = map == null ? null : map.getRandomPlayerSpawnpoint();
        Point spawn = portal == null ? new Point(0, 0) : portal.getPosition();
        if (map != null) {
            AgentMapGatewayRuntime.map().changeMapNear(agent, map, spawn);
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
            if (entry != null) {
                AgentMovementStateResetService.resetEntryState(entry);
                AgentMovementBroadcastService.broadcastMovement(entry);
            }
        }
    }
}
