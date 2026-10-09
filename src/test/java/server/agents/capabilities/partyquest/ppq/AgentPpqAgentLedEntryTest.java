package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;

/**
 * The PPQ review's findings 2 and 3 (Agent-led modes): the Agent event leader talks to Guon only before the run has
 * entered. After the clear, Wu Yang's exit puts it back beside Guon while the run is still clearing, and a talk then
 * would start a new instance in the lobby setEventCleared freed. A Guon talk the lobby refuses changes no phase and is
 * not progress, so the stall watch bounds an Agent leader that cannot get in.
 */
class AgentPpqAgentLedEntryTest {
    private static final long MINUTE = 60_000L;

    /** Mira, the Agent event leader, stands beside Guon; Guon answers every talk (the lobby may refuse it). */
    private static PpqRunFixture agentLedAtGuon() {
        PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.RECRUIT_MAP, AgentPpqSession.Mode.HUMAN_MEMBER);
        Character mira = run.character(MIRA);
        Point guon = new Point(-300, 150);
        when(mira.getPosition()).thenReturn(guon);
        when(run.actions.npcPosition(mira, AgentPpqDefinition.ENTRY_NPC)).thenReturn(guon);
        when(run.partyQuest.runNpc(mira, AgentPpqDefinition.ENTRY_NPC, 0)).thenReturn(true);
        return run;
    }

    @Test
    void aClearedRunsAgentLeaderBackBesideGuonNeverTalksToHim() {
        try (PpqRunFixture run = agentLedAtGuon()) {
            run.session.transition(AgentPpqSession.Phase.ACTIVE, 2_000L);
            run.session.transition(AgentPpqSession.Phase.CLEARING, 3_000L);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), 5_000L);

            verify(run.actions, never()).npcPosition(any(), eq(AgentPpqDefinition.ENTRY_NPC));
            verify(run.actions).stop(run.entries.get(MIRA));
        }
    }

    @Test
    void pinBeforeEntryTheAgentLeaderTalksToGuon() {
        try (PpqRunFixture run = agentLedAtGuon()) {
            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), 5_000L);

            verify(run.partyQuest).runNpc(run.character(MIRA), AgentPpqDefinition.ENTRY_NPC, 0);
            assertEquals(AgentPpqSession.Phase.ENTERING, run.session.phase());
        }
    }

    @Test
    void askingForThePhaseTheRunIsInIsNotProgress() {
        AgentPpqSession session = new AgentPpqSession(AgentPpqSession.Mode.HUMAN_MEMBER, 1L, 7, false, 1_000L);

        session.transition(AgentPpqSession.Phase.ENTERING, 2_000L);
        session.transition(AgentPpqSession.Phase.ENTERING, 9_000L);

        assertEquals(2_000L, session.lastProgressAtMs());
    }

    @Test
    void anAgentLeaderTheLobbyKeepsRefusingStallsAfterTenMinutes() {
        try (PpqRunFixture run = agentLedAtGuon()) {
            run.session.clearEventInstance();
            for (int minute = 1; minute <= 10; minute++) {
                long now = 1_000L + minute * MINUTE;
                AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), now);
                run.tick(now);
            }
            assertFalse(run.session.terminal(), "the first talk, at one minute, was the change");

            long now = 1_000L + 11 * MINUTE;
            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), now);
            run.tick(now);

            assertEquals("PPQ stalled", run.session.failure());
        }
    }
}
