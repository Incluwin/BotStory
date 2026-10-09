package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ base fix OB5: past the clear nothing is disposed while a player remains, in every mode. A failure after the
 * clear (a stall, a missing leader, a force stop) takes only the Agents out through the event's own exit; the humans
 * keep the jail bonus and their reward, and an event left with no player is disposed. Before the clear a failure ends
 * the event as before (PHASE_TIMEOUT_MS keeps its 720 s there).
 */
class AgentOpqAfterClearNoDisposeTest {
    private final OpqRunFixture run = new OpqRunFixture();

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aStockRunWithAHumanLetsOnlyItsAgentsGoAfterTheClear() {
        Character ben = run.human(1, "Ben");
        Character mira = run.companion(2, "Mira");
        Character kes = run.companion(3, "Kes");
        Character pel = run.companion(4, "Pel");
        Character hal = run.companion(5, "Hal");
        Character gus = run.companion(6, "Gus");
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED,
                AgentOpqSession.Phase.RESTORING_MINERVA, ben, mira, kes, pel, hal, gus);
        when(run.event.getProperty("statusStg8")).thenReturn("1"); // Minerva is free

        AgentOpqTerminationService.fail(session, "No legal OPQ progress before timeout", 5_000L);

        for (Character agent : new Character[]{mira, kes, pel, hal, gus}) verify(run.event).exitPlayer(agent);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void aStockForceStopAfterTheClearKeepsTheHumanInside() {
        Character ben = run.human(1, "Ben");
        Character mira = run.companion(2, "Mira");
        Character kes = run.companion(3, "Kes");
        Character pel = run.companion(4, "Pel");
        Character hal = run.companion(5, "Hal");
        Character gus = run.companion(6, "Gus");
        run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, AgentOpqSession.Phase.CLAIMING_REWARD,
                ben, mira, kes, pel, hal, gus);

        AgentOpqRuntime.forceStop(kes.getId(), "Agent runtime removed", 5_000L);

        verify(run.event).exitPlayer(kes);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
    }

    @Test
    void anAgentOnlyRunAfterTheClearEndsItsEmptyEvent() {
        Character[] agents = {run.companion(11, "Lea"), run.companion(12, "Mira"), run.companion(13, "Kes"),
                run.companion(14, "Pel"), run.companion(15, "Hal"), run.companion(16, "Gus")};
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS,
                AgentOpqSession.Phase.RESTORING_MINERVA, agents);
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.event.getPlayers()).thenReturn(List.of(agents), List.of());

        AgentOpqTerminationService.fail(session, "No legal OPQ progress before timeout", 5_000L);

        for (Character agent : agents) verify(run.event).exitPlayer(agent);
        verify(run.event).dispose();
    }

    @Test
    void beforeTheClearAStockFailureStillEndsTheEvent() {
        Character ben = run.human(1, "Ben");
        Character mira = run.companion(2, "Mira");
        Character kes = run.companion(3, "Kes");
        Character pel = run.companion(4, "Pel");
        Character hal = run.companion(5, "Hal");
        Character gus = run.companion(6, "Gus");
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, AgentOpqSession.Phase.GARDEN,
                ben, mira, kes, pel, hal, gus);

        AgentOpqTerminationService.fail(session, "No legal OPQ progress before timeout", 5_000L);

        verify(run.event).exitPlayer(ben);
        verify(run.event).dispose();
    }
}
