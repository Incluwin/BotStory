package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ review fix (OB10 follow-up): past the clear an exit can leave the event with no player, and the event never
 * ends itself then (an exit unregisters its player before OrbisPQ's changedMap could end it, and an empty instance
 * never disposes itself). Every such exit now disposes an event it left empty: a run's completion, and the refused
 * Agent's own exit, as a failure after the clear already did. An event a human is still in, or already disposed, is
 * not touched.
 */
class AgentOpqEmptyEventDisposeTest {
    private final OpqRunFixture run = new OpqRunFixture();

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentOpqSession claimingSixAgents() {
        Character[] agents = {run.companion(11, "Lea"), run.companion(12, "Mira"), run.companion(13, "Kes"),
                run.companion(14, "Pel"), run.companion(15, "Hal"), run.companion(16, "Gus")};
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, AgentOpqSession.Phase.CLAIMING_REWARD,
                agents);
        for (Character agent : agents) when(agent.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);
        return session;
    }

    @Test
    void aCompletionThatLeftTheEventEmptyDisposesIt() {
        AgentOpqSession session = claimingSixAgents();
        when(run.event.getPlayers()).thenReturn(List.of()); // every Agent claimed and was unregistered

        AgentOpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentOpqSession.Phase.COMPLETED, session.phase());
        verify(run.event, times(1)).dispose();
    }

    @Test
    void aCompletionLeavesAnEventAHumanIsStillInAlone() {
        Character ben = run.human(1, "Ben");
        Character[] agents = {run.companion(2, "Mira"), run.companion(3, "Kes"), run.companion(4, "Pel"),
                run.companion(5, "Hal")};
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                AgentOpqSession.Phase.CLAIMING_REWARD, ben, agents[0], agents[1], agents[2], agents[3]);
        when(run.event.getPlayers()).thenReturn(List.of(ben)); // he stays for the jail bonus
        for (Character agent : agents) when(agent.getMapId()).thenReturn(AgentOpqDefinition.RECRUIT_MAP);

        AgentOpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentOpqSession.Phase.COMPLETED, session.phase());
        verify(run.event, never()).dispose();
    }

    @Test
    void anEventThatAlreadyEndedIsNotDisposedAgain() {
        AgentOpqSession session = claimingSixAgents();
        when(run.event.getPlayers()).thenReturn(List.of());
        when(run.event.isEventDisposed()).thenReturn(true); // OrbisPQ's end() already disposed it

        AgentOpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentOpqSession.Phase.COMPLETED, session.phase());
        verify(run.event, never()).dispose();
    }

    @Test
    void theLastRefusedAgentsExitDisposesTheEvent() {
        Character mira = refusedAgent(false);

        for (long now = 10_000L; now <= 13_000L; now += 1_000L) {
            AgentOpqCoordinator.tickMember(session, run.entries.get(mira.getId()), mira, now);
        }

        verify(run.event).exitPlayer(mira);
        verify(run.event, times(1)).dispose();
    }

    @Test
    void aRefusedAgentsExitLeavesAnEventWithPlayersAlone() {
        Character mira = refusedAgent(true);

        for (long now = 10_000L; now <= 13_000L; now += 1_000L) {
            AgentOpqCoordinator.tickMember(session, run.entries.get(mira.getId()), mira, now);
        }

        verify(run.event).exitPlayer(mira);
        verify(run.event, never()).dispose();
    }

    private AgentOpqSession session;

    /** Mira, refused by Minerva on 920011300; once she exits, the event holds only Ben when {@code benInside}. */
    private Character refusedAgent(boolean benInside) {
        Character ben = run.human(1, "Ben");
        Character mira = run.companion(2, "Mira");
        session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, AgentOpqSession.Phase.CLAIMING_REWARD,
                ben, mira, run.companion(3, "Kes"), run.companion(4, "Pel"), run.companion(5, "Hal"));
        AtomicBoolean exited = new AtomicBoolean();
        when(run.event.getPlayers()).thenAnswer(call -> exited.get()
                ? (benInside ? List.of(ben) : List.of()) : List.of(mira));
        doAnswer(call -> {
            exited.set(true);
            return null;
        }).when(run.event).exitPlayer(mira);
        when(mira.getMapId()).thenReturn(AgentOpqDefinition.CLEAR_MAP);
        when(mira.getPosition()).thenReturn(new Point(0, 0));
        when(run.actions.npcPosition(mira, AgentOpqDefinition.MINERVA_NPC)).thenReturn(new Point(20, 0));
        when(run.partyQuest.runNpc(mira, AgentOpqDefinition.MINERVA_NPC)).thenReturn(true); // refused: she stays
        return mira;
    }
}
