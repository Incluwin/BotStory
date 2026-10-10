package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OPQ Step 0 Run 0B (live 2026-10-09): Ben, a human, led his five companions into the centre tower with the Root of
 * Life at 07:42:53 and needed 12 minutes to climb to the Minerva statue. Only he can act in that phase, so nothing
 * marked progress; he freed Minerva at 07:54:46, and at 07:54:52 the 720 s stall timer (checked first in each pass)
 * failed the run and sent the companions out to 920011200 before they could claim. Now a companion run whose human
 * leader is in its event is never timed out (the stock event's own timers bound it), the first sight of the clear is
 * progress, and the companions go on to claim when he reaches Minerva on 920011300.
 */
class AgentOpqHumanLeaderStallTest {
    private static final long TIMEOUT = AgentOpqCoordinator.phaseTimeoutMs();
    /** The phase began at 2 000 ms (OpqRunFixture), so this pass is one millisecond past the stall timer. */
    private static final long STALL = 2_000L + TIMEOUT + 1L;

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

    private AgentOpqSession benLeads(AgentOpqSession.Phase phase) {
        return run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, kes, pel, hal, gus);
    }

    private void minervaFreed() {
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getProperty("statusStg8")).thenReturn("1");
    }

    private void verifyNobodyLeft() {
        verify(run.event, never()).exitPlayer(any());
        verify(run.event, never()).dispose();
    }

    @ParameterizedTest
    @EnumSource(value = AgentOpqSession.Phase.class, names = {"SPLIT_ROOMS", "RESTORING_STATUE", "RESTORING_MINERVA"})
    void aHumanLeaderInTheEventIsNeverTimedOut(AgentOpqSession.Phase phase) {
        AgentOpqSession session = benLeads(phase);
        when(ben.getItemQuantity(AgentOpqDefinition.ROOT_OF_LIFE, false)).thenReturn(1); // he climbs with it

        AgentOpqCoordinator.tickSession(session, STALL);

        assertEquals(phase, session.phase());
        assertSame(session, AgentOpqSessionRegistry.forMember(mira.getId()));
        verifyNobodyLeft();
    }

    @Test
    void theLiveClimbGoesOnToTheCompanionsClaims() {
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.RESTORING_MINERVA); // 07:42:53
        long clear = STALL - 6_000L;
        minervaFreed(); // 07:54:46

        AgentOpqCoordinator.tickSession(session, clear);
        AgentOpqCoordinator.tickSession(session, STALL); // 07:54:52, where the stall timer failed the run

        assertEquals(AgentOpqSession.Phase.RESTORING_MINERVA, session.phase());
        verifyNobodyLeft();

        // Ben says yes to Minerva and is on 920011300: the claims begin, and Mira, beside her there, claims.
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.CLEAR_MAP);
        AgentOpqCoordinator.tickSession(session, STALL + 30_000L);
        assertEquals(AgentOpqSession.Phase.CLAIMING_REWARD, session.phase());

        Point minerva = new Point(20, 0);
        when(mira.getMapId()).thenReturn(AgentOpqDefinition.CLEAR_MAP);
        when(mira.getPosition()).thenReturn(minerva);
        when(run.actions.npcPosition(mira, AgentOpqDefinition.MINERVA_NPC)).thenReturn(minerva);
        when(run.partyQuest.runNpc(mira, AgentOpqDefinition.MINERVA_NPC)).thenReturn(true);
        AgentOpqCoordinator.tickMember(session, run.entries.get(mira.getId()), mira, STALL + 31_000L);

        verify(run.partyQuest).runNpc(mira, AgentOpqDefinition.MINERVA_NPC);
        verify(run.event, never()).exitPlayer(any());
    }

    @Test
    void aDeadHumanLeaderStillInTheEventIsWaitedFor() {
        // As OB6: the stock event ends the run on his revive; a stall here would dispose the event under him.
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.RESTORING_MINERVA);
        when(ben.getHp()).thenReturn(0);

        AgentOpqCoordinator.tickSession(session, STALL);

        assertEquals(AgentOpqSession.Phase.RESTORING_MINERVA, session.phase());
        verifyNobodyLeft();
    }

    @Test
    void theFirstSightOfTheClearIsProgress() {
        Character[] agents = {run.companion(11, "Lea"), run.companion(12, "Ari"), run.companion(13, "Bo"),
                run.companion(14, "Cy"), run.companion(15, "Di"), run.companion(16, "Ed")};
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS,
                AgentOpqSession.Phase.RESTORING_MINERVA, agents);
        long clear = STALL - 6_000L;
        minervaFreed();

        AgentOpqCoordinator.tickSession(session, clear);
        AgentOpqCoordinator.tickSession(session, STALL);

        assertEquals(AgentOpqSession.Phase.RESTORING_MINERVA, session.phase(), "the clear restarted the timer");
        assertEquals(clear, session.lastProgressAtMs());
        verify(run.event, never()).exitPlayer(any());

        AgentOpqCoordinator.tickSession(session, clear + TIMEOUT + 1L);

        assertEquals("No legal OPQ progress before timeout", session.failure(), "a timer still bounds the claims");
    }

    @Test
    void aHumanLedRunStillTimesOutWaitingForItsEntry() {
        // The admission tells him "the run waits up to 12 minutes for you": he is not in an event yet.
        AgentOpqSession session = benLeads(AgentOpqSession.Phase.ENTERING);
        session.clearEventInstance();
        when(ben.getEventInstance()).thenReturn(null);
        when(ben.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);

        AgentOpqCoordinator.tickSession(session, STALL);

        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
        assertEquals("No legal OPQ progress before timeout", session.failure());
    }
}
