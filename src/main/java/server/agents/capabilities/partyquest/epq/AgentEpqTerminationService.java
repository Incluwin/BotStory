package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentCombatSkillConstraintState;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

/** Idempotent EPQ cleanup; it cannot address another PQ's state. */
final class AgentEpqTerminationService {
    private static final Logger log = LoggerFactory.getLogger(AgentEpqTerminationService.class);

    private AgentEpqTerminationService() { }

    /**
     * Ends the run. A stock run ends as it always has: with {@code dispose} every player leaves the event and the event
     * is disposed, and every member, humans included, leaves the party. A companion run keeps its owner's party
     * whatever the outcome, and once the event reports its clear only its companions leave the event, through the
     * event's own exit; the event and the players still in it stay, so a failure after the clear takes nobody's reward.
     * A companion run that did not complete leaves the event so with or without {@code dispose}.
     */
    static void release(AgentEpqSession session, String reason, long nowMs, boolean dispose) {
        release(session, reason, nowMs, dispose, true);
    }

    /**
     * Ends a companion run whose companions all left while its humans carry on in the event (Step 4.3): today's
     * release, but nobody leaves the event and it is not disposed. The host hears a failure.
     */
    static void detach(AgentEpqSession session, String reason, long nowMs) {
        release(session, reason, nowMs, false, false);
    }

    private static void release(AgentEpqSession session, String reason, long nowMs, boolean dispose,
                                boolean mayLeaveEvent) {
        if (session == null || !session.beginTermination()) return;
        AgentEpqSession.Phase phaseAtRelease = session.phase();
        session.members().forEach(member -> {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) {
                entry.capabilityStates().require(AgentCombatSkillConstraintState.STATE_KEY).clear();
                AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
            }
        });
        AgentEpqFullRateSimulation.releaseAll(session); // Step 2.12: the run's full-rate holds end with it
        EventInstanceManager event = session.eventInstance();
        boolean companionRun = session.companionAdopted();
        boolean pastTheClear = pastTheClear(event);
        if (companionRun) logPotions(session);
        log.info("EPQ vitals: session={} phase={} members={}", session.sessionId(), phaseAtRelease, vitals(session));
        // A companion run keeps its party, so no party leave runs EllinPQ's own exit for it as it does for a stock run:
        // one that did not complete leaves the event as the tick's failure release has it leave, whoever releases it
        // (AgentEpqRuntime.requestStop passes no dispose, and can land between the fail and the tick's release).
        boolean leaveEvent = mayLeaveEvent
                && (dispose || (companionRun && phaseAtRelease != AgentEpqSession.Phase.COMPLETED));
        if (leaveEvent && event != null) {
            if (companionRun && pastTheClear) {
                exitAgentsOnly(session);
            } else {
                for (Character participant : new ArrayList<>(event.getPlayers())) {
                    try { event.exitPlayer(participant); } catch (RuntimeException ignored) { }
                }
                try { event.dispose(); } catch (RuntimeException ignored) { }
            }
        }
        if (!keepPartyAfterRelease(companionRun)) {
            session.members().stream().map(member -> AgentEpqCoordinator.character(member.characterId()))
                    .filter(java.util.Objects::nonNull).filter(AgentPartyGatewayRuntime.party()::hasParty)
                    .forEach(AgentPartyGatewayRuntime.party()::leaveCurrentParty);
        }
        session.clearEventInstance();
        boolean completed = session.phase() == AgentEpqSession.Phase.COMPLETED;
        // Step 2.10: a companion run past the clear is cleared whoever ends it (a stall, the host's stop, an Agent
        // removed): it reads COMPLETED and its host hears success. Every other run that did not complete fails.
        boolean cleared = completed || (companionRun && pastTheClear);
        if (cleared && !completed) {
            session.complete(nowMs);
            log.info("EPQ cleared run wrapped up: session={} reason={}", session.sessionId(), reason);
        } else if (!completed) {
            session.fail(reason, nowMs);
        }
        AgentEpqSessionRegistry.remove(session);
        // A companion run's pace goes to the host's clear-time records; a stock run reports none.
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), cleared, reason, nowMs,
                companionRun ? pace(session, nowMs) : null);
        log.info("EPQ session released: session={} outcome={} phase={} members={} companionRun={} pastTheClear={}"
                        + " rescues={}", session.sessionId(), completed ? "completed" : reason, phaseAtRelease,
                session.memberCount(), companionRun, pastTheClear, session.rescueCounts());
    }

    /**
     * True once the bound event reports its clear. Never read from the session's phase: 930000800 is also every
     * stage's return map, where a leader who died or quit stands before any clear.
     */
    static boolean pastTheClear(EventInstanceManager event) {
        if (event == null) return false;
        try {
            return event.isEventCleared();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Takes a companion run's Agents out of its event, through the event's own exit (EllinPQ sends them to 930000800,
     * from which the host's handback has them follow their owner), and leaves the event and its humans as they are.
     */
    static void exitAgentsOnly(AgentEpqSession session) {
        EventInstanceManager event = session == null ? null : session.eventInstance();
        if (event == null) return;
        Set<Integer> agents = session.members().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                .map(AgentEpqMemberState::characterId)
                .collect(Collectors.toSet());
        for (Character participant : new ArrayList<>(event.getPlayers())) {
            if (participant == null || !agents.contains(participant.getId())) continue;
            try {
                event.exitPlayer(participant);
            } catch (RuntimeException failure) {
                log.warn("EPQ companion exit failed: event={} member={}", event.getName(), participant.getId(), failure);
            }
        }
        closeIfEmpty(session, event);
    }

    /**
     * Closes an event no player is left in (Step 2.9): only dispose frees EllinPQ's one lobby on the channel, and
     * neither the event's exit nor its unregister ever disposes. Read from the event's players after the exits (never
     * getPlayerCount): a human still registered (alive, or dead before he presses OK) keeps it open, and the stock exit
     * ends it when he leaves.
     */
    private static void closeIfEmpty(AgentEpqSession session, EventInstanceManager event) {
        try {
            if (!event.getPlayers().isEmpty()) return;
            event.dispose();
            log.info("EPQ companion exit closed the empty event: session={} event={}", session.sessionId(),
                    event.getName());
        } catch (RuntimeException failure) {
            log.warn("EPQ empty event close failed: session={}", session.sessionId(), failure);
        }
    }

    /** The companions' HP/MP potions, by name, as 'EPQ potions:' logs them: {Mira:12/30, Tobi:0/5}. */
    static String potions(AgentEpqSession session) {
        var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        return session.members().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                .map(member -> AgentEpqCoordinator.character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .map(agent -> agent.getName() + ":" + gateway.hpPotionCount(agent) + "/" + gateway.mpPotionCount(agent))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /**
     * Every online member's HP and potions, in session order and humans included, as 'EPQ vitals:' logs them:
     * {Ben:480/500 hpPots=3 mpPots=2, Mira:50/4400 hpPots=113 mpPots=40} (HP over the current maximum, as the autopot
     * reads it); "vitals unavailable" when a member cannot be read. Never throws.
     */
    static String vitals(AgentEpqSession session) {
        try {
            var gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
            return session.members().stream()
                    .map(member -> AgentEpqCoordinator.character(member.characterId()))
                    .filter(java.util.Objects::nonNull)
                    .map(member -> member.getName() + ":" + member.getHp() + "/" + member.getCurrentMaxHp()
                            + " hpPots=" + gateway.hpPotionCount(member) + " mpPots=" + gateway.mpPotionCount(member))
                    .collect(Collectors.joining(", ", "{", "}"));
        } catch (RuntimeException failure) {
            return "vitals unavailable";
        }
    }

    /**
     * A companion run's party is its owner's own: his companions stay in it whatever the outcome, as KPQ and HPQ keep
     * a party they did not form. Every other run leaves it, humans included, as it always has.
     */
    static boolean keepPartyAfterRelease(boolean companionRun) {
        return companionRun;
    }

    /** A companion run's pace for the host's records; none yet (Step 5: AgentEpqPaceReport.of). */
    static AgentPqPace pace(AgentEpqSession session, long nowMs) {
        return null;
    }

    private static void logPotions(AgentEpqSession session) {
        try {
            log.info("EPQ potions: session={} start={} end={}", session.sessionId(), session.potionsAtEntry(),
                    potions(session));
        } catch (RuntimeException failure) {
            log.warn("EPQ potions could not be read: session={}", session.sessionId(), failure);
        }
    }
}
