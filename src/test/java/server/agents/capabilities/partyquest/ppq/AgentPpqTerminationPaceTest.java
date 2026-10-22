package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.UNA;

/**
 * P6i Step 5 (plan 10): a companion run marks its pace as its passes see the leader move through the ship (the medal
 * map, each wave's twenty and hand-in, the second deck, the doors, the boss and his fall, the clear), and its release
 * hands the host that record with one 'PPQ pace span:' line per span; a stock run hands none.
 */
class AgentPpqTerminationPaceTest {
    private static void at(PpqRunFixture run, int mapId, long nowMs) {
        run.onMap(BEN, mapId);
        run.tick(nowMs);
    }

    @Test
    void aCompanionRunMarksTheShipAsItsLeaderGoesThroughIt() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.ENTRY_MAP)) {
            when(run.event.getTimeLeft()).thenReturn(200_000L);
            run.properties.put("stage2", "0");
            run.tick(2_000L);
            at(run, AgentPpqDefinition.MEDAL_MAP, 3_000L);
            when(run.actions.liveMonsterCount(any(), eq(java.util.Set.of(9_300_114)))).thenReturn(30);
            run.tick(4_000L);
            when(run.actions.liveMonsterCount(any(), eq(java.util.Set.of(9_300_114)))).thenReturn(27);
            run.tick(5_000L);
            when(run.actions.itemCount(run.character(BEN), AgentPpqDefinition.ROOKIE_MEDAL)).thenReturn(20);
            run.tick(6_000L);
            when(run.actions.itemCount(run.character(BEN), AgentPpqDefinition.ROOKIE_MEDAL)).thenReturn(0);
            run.properties.put("stage2", "1");
            run.tick(7_000L);
            run.properties.put("stage2", "2");
            run.tick(8_000L);
            run.properties.put("stage2", "3");
            at(run, AgentPpqDefinition.DECK_ONE_MAP, 9_000L);
            at(run, AgentPpqDefinition.CHEST_ONE_MAP, 10_000L);
            at(run, AgentPpqDefinition.DECK_TWO_MAP, 11_000L);
            at(run, AgentPpqDefinition.DOOR_MAP, 12_000L);
            when(run.actions.liveMonsterCount(any(), eq(AgentPpqDefinition.LORD_PIRATES))).thenReturn(1);
            at(run, AgentPpqDefinition.BOSS_MAP, 13_000L);
            when(run.actions.liveMonsterCount(any(), eq(AgentPpqDefinition.LORD_PIRATES))).thenReturn(0);
            run.tick(14_000L);

            AgentPpqPaceMarks.Snapshot marks = run.session.pace().snapshot();
            assertEquals(3_000L, marks.at(AgentPpqPaceMarks.Boundary.MEDALS));
            assertEquals(6_000L, marks.at(AgentPpqPaceMarks.Boundary.WAVE_ONE_FULL));
            assertEquals(7_000L, marks.at(AgentPpqPaceMarks.Boundary.WAVE_ONE_IN));
            assertEquals(0L, marks.at(AgentPpqPaceMarks.Boundary.WAVE_TWO_FULL), "handed in within one pass");
            assertEquals(8_000L, marks.at(AgentPpqPaceMarks.Boundary.WAVE_TWO_IN));
            assertEquals(9_000L, marks.at(AgentPpqPaceMarks.Boundary.WAVE_THREE_IN));
            assertEquals(11_000L, marks.at(AgentPpqPaceMarks.Boundary.DECK_TWO));
            assertEquals(12_000L, marks.at(AgentPpqPaceMarks.Boundary.DOORS));
            assertEquals(13_000L, marks.at(AgentPpqPaceMarks.Boundary.BOSS));
            assertEquals(14_000L, marks.at(AgentPpqPaceMarks.Boundary.BOSS_DOWN));
            assertEquals(java.util.Set.of(AgentPpqDefinition.DECK_ONE_MAP), marks.detourDecks());
            assertEquals(3, marks.waveKills().get(0));
        }
    }

    @Test
    void theReleaseHandsTheHostTheRunsPace() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.ENTRY_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
             PpqLogLines lines = new PpqLogLines(AgentPpqPaceReport.class)) {
            AgentPpqCompanionRun.markEntered(run.session, 2_000L);
            run.session.pace().mark(AgentPpqPaceMarks.Boundary.MEDALS, 60_000L);
            run.session.pace().markDown(UNA);

            AgentPpqTerminationService.release(run.session, "why", 90_000L, true);

            ArgumentCaptor<AgentPqPace> pace = ArgumentCaptor.forClass(AgentPqPace.class);
            lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(run.session.sessionId()),
                    eq(false), eq("why"), eq(90_000L), pace.capture()));
            assertEquals("ppq", pace.getValue().questKey());
            assertEquals(List.of("stage1", "medals1"),
                    pace.getValue().spans().stream().map(AgentPqPace.Span::key).toList());
            assertTrue(pace.getValue().spans().get(0).complete());
            assertFalse(pace.getValue().spans().get(1).complete(), "the medals were still running");
            assertEquals(1, pace.getValue().runs().stream().filter(r -> r.characterId() == UNA).findFirst()
                    .orElseThrow().deaths());
            assertTrue(pace.getValue().runs().stream().anyMatch(r -> r.characterId() == MIRA));
            assertEquals(2, lines.startingWith("PPQ pace span:").size());
        }
    }

    @Test
    void pinAStockRunHandsNoPace() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.ENTRY_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            AgentPpqTerminationService.release(run.session, "why", 90_000L, true);

            lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(anyString(), eq(false), anyString(),
                    anyLong(), eq(null)));
        }
    }
}
