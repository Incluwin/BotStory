package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 4.3: deaths and departures in a companion run, at the stock floor. A departed companion (the departure gate let
 * it go) is tapped out through its host once; one its host took out of the party is taken out of the run (E18); a
 * refused tap-out leaves it departed and idle and the run goes on. A dead companion at the floor waits with no time
 * limit; a departed player is dropped (E18); an unloaded companion is taken out after MEMBER_RECOVERY_TIMEOUT_MS; with
 * no companion left the humans carry on (detach, no dispose). Nothing is asked after the clear, and a stock run hears
 * none of it.
 */
class AgentEpqDeathPolicyTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character pia = run.companion(5, "Pia", AgentEpqDefinition.STAGE_ONE_MAP);
    private final Character rex = run.companion(6, "Rex", AgentEpqDefinition.STAGE_ONE_MAP);
    private final MockedStatic<AgentPartyQuestHandback> handback = mockStatic(AgentPartyQuestHandback.class);

    AgentEpqDeathPolicyTest() {
        when(run.actions.liveMonsterCount(any(), any())).thenReturn(5);
    }

    @AfterEach
    void tearDown() {
        handback.close();
        run.close();
    }

    private AgentEpqSession companionRun(Character... members) {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_ONE, members);
        session.setMemberCoordinationChatEnabled(true);
        return session;
    }

    /** The gate let this member go: the stock respawn carried it out of the event to the normal 930000800. */
    private void departed(Character member) {
        when(member.getEventInstance()).thenReturn(null);
        run.onMap(member, AgentEpqDefinition.REWARD_MAP);
    }

    private void tapOutAnswers(boolean accepted) {
        handback.when(() -> AgentPartyQuestHandback.tapOut(any())).thenReturn(accepted);
    }

    @Test
    void aDepartedCompanionIsTappedOutOnce() {
        AgentEpqSession session = companionRun(ben, mira, tobi, una, pia, rex);
        departed(mira);
        tapOutAnswers(true);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 3_000L);

        handback.verify(() -> AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut("epq",
                session.sessionId(), mira.getId(), AgentEpqDefinition.REWARD_MAP, 2_000L)), times(1));
        handback.verify(() -> AgentPartyQuestHandback.tapOut(any()), times(1));
        assertFalse(session.terminal());
    }

    @Test
    void aCompanionItsHostTookOutOfThePartyLeavesTheRunAndTheOthersSaySo() {
        AgentEpqSession session = companionRun(ben, mira, tobi, una, pia, rex);
        departed(mira);
        tapOutAnswers(true);
        AgentEpqCoordinator.tickSession(session, 2_000L);

        run.party(ben, tobi, una, pia, rex); // the host took Mira out
        AgentEpqCoordinator.tickSession(session, 3_000L);

        assertNull(session.member(mira.getId()));
        assertEquals(5, session.memberCount());
        verify(run.packets, times(1)).broadcastChatText(tobi, "Mira had to go. we'll keep going with five.", false, 0);
        assertFalse(session.terminal());
    }

    @Test
    void aRefusedTapOutLeavesTheCompanionDepartedAndTheRunGoesOn() {
        AgentEpqSession session = companionRun(ben, mira, tobi, una, pia, rex);
        departed(mira);
        tapOutAnswers(false);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqCompanionRun.class);
             EpqLogLines coordinator = new EpqLogLines(AgentEpqCoordinator.class)) {
            AgentEpqCoordinator.tickSession(session, 2_000L);
            AgentEpqCoordinator.tickSession(session, 3_000L);

            assertEquals(List.of("EPQ tap-out: session=" + session.sessionId() + " member=2 map="
                    + AgentEpqDefinition.REWARD_MAP + " accepted=false"), lines.startingWith("EPQ tap-out:"));
            assertEquals(List.of(), coordinator.startingWith("EPQ session failure:"));
        }
        assertNotNull(session.member(mira.getId()));
        assertTrue(session.departed(mira.getId()));
    }

    @Test
    void aCorpseAtTheFloorWaitsAndTheRunGoesOnAnHourLater() {
        AgentEpqSession session = companionRun(ben, mira, tobi, una);
        when(una.getHp()).thenReturn(0); // held by the gate: still registered

        assertTrue(AgentEpqCompanionRun.members(session, ben, 2_000L));
        assertTrue(AgentEpqCompanionRun.members(session, ben, 2_000L + 3_600_000L));

        assertFalse(session.terminal());
        assertTrue(session.companionDownSinceMs(una.getId()) > 0L);
    }

    @Test
    void aDepartedPlayerIsDroppedAndTheOthersSaySo() {
        Character ann = run.human(7, "Ann", AgentEpqDefinition.STAGE_ONE_MAP);
        AgentEpqSession session = companionRun(ben, ann, mira, tobi, una, pia);
        departed(ann);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertNull(session.member(ann.getId()));
        verify(run.packets, times(1)).broadcastChatText(mira, "Ann had to go. we'll keep going with five.", false, 0);
        assertFalse(session.terminal());
    }

    @Test
    void anUnloadedCompanionIsTakenOutAfterTheRecoveryTimeout() {
        AgentEpqSession session = companionRun(ben, mira, tobi, una, pia);
        run.offline(pia);

        AgentEpqCoordinator.tickSession(session, 2_000L);
        AgentEpqCoordinator.tickSession(session, 2_000L + 44_999L);
        assertNotNull(session.member(pia.getId()));

        AgentEpqCoordinator.tickSession(session, 2_000L + 45_000L);
        assertNull(session.member(pia.getId()));
        assertFalse(session.terminal());
    }

    @Test
    void withTheLastCompanionGoneTheHumansCarryOn() {
        Character ann = run.human(7, "Ann", AgentEpqDefinition.STAGE_ONE_MAP);
        Character cal = run.human(8, "Cal", AgentEpqDefinition.STAGE_ONE_MAP);
        AgentEpqSession session = companionRun(ben, ann, cal, mira, tobi);
        run.party(ben, ann, cal); // both companions tapped out by their hosts

        AgentEpqCoordinator.tickSession(session, 2_000L);

        assertEquals(AgentEpqSession.Phase.FAILED, session.phase());
        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(ben);
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()), eq(false),
                eq("every companion left; the humans carry on"), anyLong(), any()));
    }

    @Test
    void afterTheClearNothingIsAskedOfADeadOrDepartedMember() {
        AgentEpqSession session = companionRun(ben, mira, tobi, una, pia, rex);
        when(run.event.isEventCleared()).thenReturn(true);
        departed(mira);
        when(tobi.getHp()).thenReturn(0);
        tapOutAnswers(true);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        handback.verify(() -> AgentPartyQuestHandback.tapOut(any()), never());
        assertEquals(0L, session.companionDownSinceMs(tobi.getId()));
    }

    @Test
    void pinAStockRunHearsNoneOfIt() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_ONE, ben, mira, tobi, una, pia, rex);
        departed(mira);
        tapOutAnswers(true);

        AgentEpqCoordinator.tickSession(session, 2_000L);

        handback.verify(() -> AgentPartyQuestHandback.tapOut(any()), never());
        assertNotNull(session.member(mira.getId()));
    }
}
