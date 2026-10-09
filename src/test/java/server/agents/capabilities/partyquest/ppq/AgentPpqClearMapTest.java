package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.UNA;

/**
 * PPQ Step 1b.1 (D1, R6; every mode): the clear warps everyone to 925100600 and ends the event under them, so an
 * Agent there with no instance is not in a different one: it talks to Wu Yang. Once the bound event is cleared, the
 * run's cap and its stall never fail it; it completes by the stock rule (nobody left on 925100600).
 */
class AgentPpqClearMapTest {
    private static final long MINUTE = 60_000L;

    @Test
    void aCompanionWithNoInstanceOnTheClearMapTalksToWuYang() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.CLEAR_MAP)) {
            when(run.event.isEventCleared()).thenReturn(true);
            Character mira = run.character(MIRA);
            when(mira.getEventInstance()).thenReturn(null);
            Point wuYang = new Point(120, 60);
            when(mira.getPosition()).thenReturn(wuYang);
            when(run.actions.npcPosition(mira, AgentPpqDefinition.RESCUE_NPC)).thenReturn(wuYang);
            when(run.partyQuest.runNpc(mira, AgentPpqDefinition.RESCUE_NPC, 0)).thenReturn(true);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            assertFalse(run.session.terminal(), run.session.failure());
            verify(run.partyQuest).runNpc(mira, AgentPpqDefinition.RESCUE_NPC, 0);
        }
    }

    @Test
    void pinACompanionInAnotherInstanceStillFailsTheRun() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP)) {
            Character mira = run.character(MIRA);
            when(mira.getEventInstance()).thenReturn(mock(EventInstanceManager.class));

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            assertEquals("PPQ member entered a different event instance", run.session.failure());
        }
    }

    @Test
    void aClearedRunOutlivesTheCap() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.CLEAR_MAP)) {
            when(run.event.isEventCleared()).thenReturn(true);

            run.tick(1_000L + 200 * MINUTE);

            assertFalse(run.session.terminal(), run.session.failure());
            assertEquals(AgentPpqSession.Phase.CLEARING, run.session.phase());
        }
    }

    @Test
    void aClearedRunWithAMemberStillByWuYangNeverStalls() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.CLEAR_MAP)) {
            when(run.event.isEventCleared()).thenReturn(true);
            run.tick(2_000L);
            assertEquals(AgentPpqSession.Phase.CLEARING, run.session.phase());
            // Ben took Wu Yang's exit; Mira is still by Wu Yang, so the run is not complete yet.
            run.onMap(BEN, AgentPpqDefinition.EXIT_MAP);
            run.onMap(TOBI, AgentPpqDefinition.EXIT_MAP);
            run.onMap(UNA, AgentPpqDefinition.EXIT_MAP);

            run.tick(2_000L + 11 * MINUTE);

            assertFalse(run.session.terminal(), run.session.failure());
            run.onMap(MIRA, AgentPpqDefinition.EXIT_MAP);
            run.tick(2_000L + 12 * MINUTE);
            assertEquals(AgentPpqSession.Phase.COMPLETED, run.session.phase(), "the stock completion still holds");
        }
    }

    @Test
    void pinAnUnclearedRunStallsAfterTenIdleMinutes() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP)) {
            run.tick(1_000L + 10 * MINUTE);

            assertEquals("PPQ stalled", run.session.failure());
        }
    }
}
