package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LMPQ base fix 3, the hand-back: a departed Agent (its death respawn took it out of the maze) leaves the LMPQ index
 * and its engagement and is rehomed by the party-quest lifecycle at once, in every mode, instead of standing frozen
 * until the run ends; it stays in the session, marked departed, for the run's counts and lines. The run's release
 * and its watchdog leave it to its new activity. A departed human is only marked.
 */
class AgentLmpqDepartedHandBackTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character ann = run.human(5, "Ann");
    private AgentPartyQuestEngagement engagement;

    @AfterEach
    void tearDown() {
        if (engagement != null) AgentPartyQuestEngagementRegistry.remove(engagement);
        run.close();
    }

    /** A farming run whose engagement indexes every member, seen whole by the event once. */
    private AgentLmpqSession farming() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel, ann);
        engagement = new AgentPartyQuestEngagement("lmpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L,
                ben.getId(), 5, 1_000L);
        AgentPartyQuestEngagementRegistry.register(engagement);
        for (Character member : List.of(ben, mira, kes, pel, ann)) {
            AgentPartyQuestEngagementRegistry.addAndIndexMember(engagement, member.getId(),
                    member == ben || member == ann ? AgentPartyQuestEngagement.MemberType.HUMAN
                            : AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        }
        AgentLmpqCoordinator.tickSession(session, 9_000L);
        return session;
    }

    private void kesRespawnedOutside() {
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
        when(kes.getEventInstance()).thenReturn(null);
    }

    @Test
    void aDepartedAgentIsHandedBackOnce() {
        AgentLmpqSession session = farming();
        kesRespawnedOutside();
        run.livePlayers(ben, mira, pel, ann);

        AgentLmpqCoordinator.tickSession(session, 10_000L);
        AgentLmpqCoordinator.tickSession(session, 11_000L);

        assertNull(AgentLmpqSessionRegistry.forMember(kes.getId()), "no longer indexed for LMPQ");
        assertSame(session, AgentLmpqSessionRegistry.forMember(mira.getId()));
        assertNull(AgentPartyQuestEngagementRegistry.forMember(kes.getId()), "out of the engagement");
        assertFalse(engagement.memberIds().contains(kes.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(kes.getId(), "lmpq", 10_000L),
                times(1));
        assertNotNull(session.member(kes.getId()), "still in the session for its counts and lines");
        assertTrue(session.departed(kes.getId()));
        assertFalse(session.terminal(), session.failure());
        // Its own tick no longer reaches the LMPQ runtime; a stop request for it goes to its new activity.
        assertFalse(AgentLmpqSessionRegistry.active(kes.getId()));
    }

    @Test
    void aDepartedHumanIsOnlyMarked() {
        AgentLmpqSession session = farming();
        when(run.characters.findOnlineCharacterById(ann.getId())).thenReturn(null);
        run.livePlayers(ben, mira, kes, pel);

        AgentLmpqCoordinator.tickSession(session, 10_000L);

        assertTrue(session.departed(ann.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(
                anyInt(), anyString(), anyLong()), never());
    }

    @Test
    void theReleaseAndTheWatchdogLeaveAHandedBackAgentAlone() {
        AgentLmpqSession session = farming();
        kesRespawnedOutside();
        run.livePlayers(ben, mira, pel, ann);
        AgentLmpqCoordinator.tickSession(session, 10_000L);
        for (Character agent : List.of(mira, pel)) when(agent.getHp()).thenReturn(0);

        AgentLmpqWatchdogRuntime.tick(session, 20_000L);
        assertEquals(mira.getId(), session.executionAgentId(), "the run is driven by its own Agents only");

        session.fail("LMPQ stalled in FARMING", 21_000L);
        AgentLmpqTerminationService.finish(session, session.failure(), 21_000L, true);

        verify(run.actions, never()).stop(run.entry(kes));
        verify(run.actions).stop(run.entry(mira));
    }
}
