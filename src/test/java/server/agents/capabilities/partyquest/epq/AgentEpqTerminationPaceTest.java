package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Step 5.3: a companion run's release hands its host the run's pace (AgentEpqPaceReport.of) and logs one 'EPQ pace
 * span:' line per begun span; a stock run reports no pace and logs no span.
 */
class AgentEpqTerminationPaceTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_TWO_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_TWO_MAP);

    AgentEpqTerminationPaceTest() {
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, tobi, una));
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aCompanionRunsHostGetsItsPaceAndTheSpansAreLogged() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, true,
                AgentEpqSession.Phase.STAGE_TWO, ben, mira, tobi, una);
        session.pace().markEntered(List.of(), List.of(1, 2, 3, 4), 3_000L);
        session.pace().markPhase(AgentEpqSession.Phase.STAGE_TWO, 170_000L);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqPaceReport.class)) {
            AgentEpqTerminationService.release(session, "stopped by operator", 200_000L, true);

            assertEquals(List.of(
                    "EPQ pace span: session=" + session.sessionId()
                            + " key=yes seconds=2.0 theorySeconds=-1 complete=true why=-",
                    "EPQ pace span: session=" + session.sessionId()
                            + " key=trees seconds=167.0 theorySeconds=-1 complete=true why=-",
                    "EPQ pace span: session=" + session.sessionId()
                            + " key=bottles seconds=30.0 theorySeconds=-1 complete=false why=open"),
                    lines.startingWith("EPQ pace span:"));
        }
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()), eq(false),
                eq("stopped by operator"), eq(200_000L), argThat((AgentPqPace pace) -> pace != null
                        && pace.questKey().equals("epq") && pace.spans().size() == 3)));
    }

    @Test
    void pinAStockRunReportsNoPaceAndLogsNoSpan() {
        AgentEpqSession session = run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, false,
                AgentEpqSession.Phase.STAGE_TWO, ben, mira, tobi, una);
        session.pace().markEntered(List.of(), List.of(1, 2, 3, 4), 3_000L);
        try (EpqLogLines lines = new EpqLogLines(AgentEpqPaceReport.class)) {
            AgentEpqTerminationService.release(session, "stopped by operator", 200_000L, true);

            assertEquals(List.of(), lines.startingWith("EPQ pace span:"));
        }
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()), eq(false),
                eq("stopped by operator"), eq(200_000L), isNull()));
    }

    @Test
    void noRunNoPace() {
        assertEquals(null, AgentEpqTerminationService.pace(null, 0L));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(anyString(), anyBoolean(),
                anyString(), anyLong(), isNull()), org.mockito.Mockito.never());
    }
}
