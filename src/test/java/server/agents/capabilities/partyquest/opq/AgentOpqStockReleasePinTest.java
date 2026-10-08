package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P6h OPQ step 1, C1 pin (green on the base and after): how a run that is not a companion run is released. The
 * companion seam changes the release of a companion run; these runs must not notice. The Step 1b base fixes change
 * stock runs on purpose, and each says so where it changed a pin here (OB2: the human keeps his party; OB5: after
 * the clear only the Agents leave the event).
 */
class AgentOpqStockReleasePinTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character gus = run.companion(6, "Gus");

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aStockFailureExitsEveryoneDisposesAndTheAgentsLeaveTheParty() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, AgentOpqSession.Phase.SPLIT_ROOMS,
                ben, mira, kes, pel, hal, gus);

        AgentOpqRuntime.forceStop(mira.getId(), "Agent runtime removed", 5_000L);

        for (Character member : new Character[]{ben, mira, kes, pel, hal, gus}) {
            verify(run.event).exitPlayer(member);
        }
        // OB2: only the Agents leave the party; the human keeps his own (until OB2 he left it too).
        for (Character agent : new Character[]{mira, kes, pel, hal, gus}) verify(run.party).leaveCurrentParty(agent);
        verify(run.party, never()).leaveCurrentParty(ben);
        for (Character agent : new Character[]{mira, kes, pel, hal, gus}) {
            verify(run.actions).stop(run.entries.get(agent.getId()));
        }
        verify(run.event).dispose();
        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
        assertEquals("Agent runtime removed", session.failure());
        assertNull(session.eventInstance());
        assertNull(AgentOpqSessionRegistry.forMember(ben.getId()));
    }

    @Test
    void aStockFailureAfterTheClearLetsOnlyTheAgentsGo() {
        // OB5: past the clear nothing is disposed while a player remains (until OB5 everyone left and the event was
        // disposed).
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS,
                AgentOpqSession.Phase.CLAIMING_REWARD, mira, kes, pel, hal, gus, ben);

        AgentOpqRuntime.forceStop(kes.getId(), "Agent runtime removed", 5_000L);

        verify(run.event, never()).exitPlayer(ben);
        verify(run.event).exitPlayer(mira);
        verify(run.event, never()).dispose();
        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void aStockRunReportsNoPace() {
        run.lifecycle.when(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                anyString(), anyBoolean(), anyString(), anyLong())).thenCallRealMethod();
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, AgentOpqSession.Phase.GARDEN,
                ben, mira, kes, pel, hal, gus);

        AgentOpqRuntime.forceStop(mira.getId(), "Agent runtime removed", 5_000L);

        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(false), eq("Agent runtime removed"), eq(5_000L), isNull()), times(1));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                anyString(), anyBoolean(), anyString(), anyLong(), notNull()), never());
    }

    @Test
    void aCompletedStockRunIsReleasedByTheNextStopRequestWithoutDisposing() {
        run.lifecycle.when(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                anyString(), anyBoolean(), anyString(), anyLong())).thenCallRealMethod();
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS,
                AgentOpqSession.Phase.CLAIMING_REWARD, mira, kes, pel, hal, gus, ben);
        session.complete(5_000L);

        assertTrue(AgentOpqRuntime.requestStop(mira.getId(), "activity admitted", 6_000L));

        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
        verify(run.party).leaveCurrentParty(mira);
        assertEquals(AgentOpqSession.Phase.COMPLETED, session.phase());
        assertNull(AgentOpqSessionRegistry.forMember(mira.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(true), eq("activity admitted"), eq(6_000L), isNull()), times(1));
    }

    @Test
    void aLiveStockRunIgnoresAStopRequest() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, AgentOpqSession.Phase.ENTRANCE,
                mira, kes, pel, hal, gus, ben);

        assertEquals(false, AgentOpqRuntime.requestStop(mira.getId(), "activity admitted", 6_000L));

        verify(run.event, never()).dispose();
        assertEquals(AgentOpqSession.Phase.ENTRANCE, session.phase());
    }

    @Test
    void aSecondReleaseDoesNothing() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, AgentOpqSession.Phase.SPLIT_ROOMS,
                mira, kes, pel, hal, gus, ben);
        AgentOpqRuntime.forceStop(mira.getId(), "first", 5_000L);
        AgentOpqTerminationService.release(session, "second", 6_000L, true);

        verify(run.event, times(1)).dispose();
        assertEquals("first", session.failure());
        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void registrationRefusesAStockSessionOfFive() {
        AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.AUTONOMOUS, 7L, 99, 1_000L);
        for (Character member : new Character[]{mira, kes, pel, hal, gus}) {
            session.addMember(member.getId(), AgentOpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(mira.getId(), mira.getId());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AgentOpqSessionRegistry.registerComplete(session));

        assertEquals("complete six-member OPQ session required", refused.getMessage());
        assertNull(AgentOpqSessionRegistry.forOperator(99));
    }
}
