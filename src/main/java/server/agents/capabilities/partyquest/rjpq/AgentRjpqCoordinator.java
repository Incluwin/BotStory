package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

/**
 * One RJPQ run's coordination. The session tick (the lease holder) binds the run's event when the leader enters it,
 * moves the run's phase forward from the maps its members stand on, and ends the run when the event does. Each
 * companion's own tick keeps it out of FOLLOW for the whole run (map-sync would otherwise pull it into its leader's
 * Dark Lab or Lab Unit, past the doors and the one-room rule) and holds it at full rate while it is in the event.
 * Once its last action has settled, a living companion in the event plays the stage it stands on
 * ({@link AgentRjpqStagePlay}).
 */
final class AgentRjpqCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqCoordinator.class);
    private static final long COMPANION_ENTRY_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqCoordinator.COMPANION_ENTRY_WAIT_MS");

    private AgentRjpqCoordinator() {
    }

    static PrimitiveCapabilityGateway actions() {
        return AgentPrimitiveCapabilityGatewayRuntime.gateway();
    }

    static PartyQuestGateway partyQuest() {
        return AgentPartyQuestGatewayRuntime.partyQuest();
    }

    static Character character(int id) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(id);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }

    /**
     * One companion's own tick: never FOLLOW inside a run, full rate while it stands in the run's event, and its stage
     * play there while it lives.
     */
    static void tickMember(AgentRjpqSession session, AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentRjpqMemberState member = session.member(agent.getId());
        if (member == null || !member.agent() || session.terminal()) {
            return;
        }
        if (AgentModeStateRuntime.following(entry)) {
            actions().stop(entry);
            log.info("RJPQ follow stopped: session={} side={} member={}", session.sessionId(), session.side().key(),
                    agent.getId());
        }
        EventInstanceManager event = session.eventInstance();
        boolean inEvent = event != null && agent.getEventInstance() == event;
        AgentRjpqFullRateSimulation.apply(session, agent.getId(), entry, inEvent);
        if (inEvent && actions().alive(agent) && nowMs >= member.nextActionAtMs()) {
            AgentRjpqStagePlay.play(session, entry, agent, member, nowMs);
        }
    }

    /** The run's own tick, on the lease holder. */
    static void tickSession(AgentRjpqSession session, long nowMs) {
        if (session.terminal() || session.paused()) {
            return;
        }
        Character leader = character(session.eventLeaderId());
        EventInstanceManager event = bind(session, leader, nowMs);
        if (event == null) {
            if (nowMs - session.startedAtMs() > COMPANION_ENTRY_WAIT_MS) {
                fail(session, "The RJPQ leader did not start the event in time", nowMs);
            }
            return;
        }
        boolean cleared = AgentRjpqTerminationService.pastTheClear(event);
        if (cleared) {
            session.observeCleared(nowMs);
        }
        AgentRjpqSession.Phase next = cleared ? AgentRjpqSession.Phase.CLAIMING : furthestPhase(session);
        logChange(session, session.advance(next, nowMs));
        if (event.isEventDisposed()) {
            if (cleared) {
                session.complete(nowMs);
            } else {
                fail(session, "The RJPQ event ended before the clear", nowMs);
            }
            return;
        }
        if (!cleared && leader == null) {
            fail(session, "The RJPQ event leader is offline", nowMs);
        }
    }

    /** The run's event: bound once, when the leader stands in an instance on one of the side's event maps. */
    private static EventInstanceManager bind(AgentRjpqSession session, Character leader, long nowMs) {
        EventInstanceManager bound = session.eventInstance();
        if (bound != null || leader == null) {
            return bound;
        }
        EventInstanceManager candidate = leader.getEventInstance();
        if (candidate == null || session.side().zenumistMap(leader.getMapId()) < 0) {
            return null;
        }
        if (session.bindEventInstance(candidate, nowMs)) {
            AgentRjpqSide side = session.side();
            partyQuest().prepareNpcScripts(side.bookNpcId(), side.officeYuleteNpcId(), side.claimNpcId(),
                    side.tracesYuleteNpcId(), AgentRjpqDefinition.REWARD_NPC_ID);
            session.setPotionsAtEntry(AgentRjpqTerminationService.potions(session));
            logChange(session, session.advance(AgentRjpqSession.Phase.ENTERING, nowMs));
        }
        return session.eventInstance();
    }

    /** The furthest stage any member of the run stands on; null when none stands on a stage map. */
    private static AgentRjpqSession.Phase furthestPhase(AgentRjpqSession session) {
        AgentRjpqSession.Phase furthest = null;
        for (AgentRjpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            AgentRjpqSession.Phase phase = character == null ? null
                    : phaseFor(session.side().zenumistMap(character.getMapId()));
            if (phase != null && (furthest == null || phase.ordinal() > furthest.ordinal())) {
                furthest = phase;
            }
        }
        return furthest;
    }

    /** The stage a Zenumist event map belongs to; null for the exit and anything else. */
    static AgentRjpqSession.Phase phaseFor(int zenumistMapId) {
        if (zenumistMapId == AgentRjpqDefinition.BOOKS_MAP) {
            return AgentRjpqSession.Phase.BOOKS;
        }
        if (zenumistMapId == AgentRjpqDefinition.TUNNEL_MAP) {
            return AgentRjpqSession.Phase.TUNNEL;
        }
        if (zenumistMapId == AgentRjpqDefinition.BEAKER_MAP) {
            return AgentRjpqSession.Phase.BEAKERS;
        }
        if (zenumistMapId == AgentRjpqDefinition.SPECIAL_LAB_MAP) {
            return AgentRjpqSession.Phase.KEYS;
        }
        if (zenumistMapId == AgentRjpqDefinition.DARK_LAB_ONE_MAP || zenumistMapId == AgentRjpqDefinition.DARK_LAB_TWO_MAP) {
            return AgentRjpqSession.Phase.LABS;
        }
        if (zenumistMapId == AgentRjpqDefinition.OFFICE_MAP) {
            return AgentRjpqSession.Phase.OFFICE;
        }
        if (zenumistMapId == AgentRjpqDefinition.LAB_TUNNEL_MAP || AgentRjpqDefinition.isLabUnit(zenumistMapId)) {
            return AgentRjpqSession.Phase.UNITS;
        }
        if (zenumistMapId == AgentRjpqDefinition.CENTER_ENTRANCE_MAP
                || zenumistMapId == AgentRjpqDefinition.CENTER_LAB_MAP) {
            return AgentRjpqSession.Phase.CENTER;
        }
        if (zenumistMapId == AgentRjpqDefinition.TRACES_MAP || zenumistMapId == AgentRjpqDefinition.REWARD_MAP) {
            return AgentRjpqSession.Phase.CLAIMING;
        }
        return null;
    }

    /** Fails the run once, whoever asks first; the release runs on the next tick that sees it terminal. */
    static void fail(AgentRjpqSession session, String reason, long nowMs) {
        if (!session.claimFailure()) {
            return;
        }
        log.warn("RJPQ session failure: session={} side={} reason={}", session.sessionId(), session.side().key(),
                reason);
        session.fail(reason, nowMs);
    }

    private static void logChange(AgentRjpqSession session, AgentRjpqSession.Change change) {
        if (change == null) {
            return;
        }
        log.info("RJPQ phase: session={} side={} {}->{} phaseMs={} runMs={} companionRun=true", session.sessionId(),
                session.side().key(), change.from(), change.to(), change.phaseMs(), change.runMs());
    }
}
