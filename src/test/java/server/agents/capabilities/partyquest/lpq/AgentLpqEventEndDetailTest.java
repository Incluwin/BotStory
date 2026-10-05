package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LPQ base fix 1: when a run fails because its event "ended or expired", the coordinator first logs what it can still
 * see (where the leader stands, his HP, whether the event was disposed, how many dead members were held), and that
 * detail never throws.
 */
class AgentLpqEventEndDetailTest {
    @Test
    void theDetailNamesTheLeaderTheEventAndTheHeldDead() {
        try (LpqRunFixture run = new LpqRunFixture()) {
            Character leader = run.human(762_200, "Crewfriend");
            AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, true, AgentLpqSession.Phase.STAGE_9,
                    leader, run.companion(762_201, "Halvard"), run.companion(762_202, "Lioren"),
                    run.companion(762_203, "Pell"), run.companion(762_204, "Kestrel"),
                    run.companion(762_205, "Casse"));
            when(leader.getMapId()).thenReturn(AgentLpqDefinition.EXIT_MAP);
            when(leader.getHp()).thenReturn(0);
            when(run.event.isEventDisposed()).thenReturn(true);
            session.reserveDeparture(762_201, () -> java.util.Set.of(762_200, 762_201, 762_202, 762_203, 762_204),
                    AgentLpqDefinition.MIN_PARTY_SIZE, 5_000L);

            assertEquals("leaderMap=922010000 leaderHp=0 eventDisposed=true heldDead=1",
                    AgentLpqCoordinator.eventEndDetail(session, leader));

            session.clearEventInstance();
            assertEquals("leaderMap=922010000 leaderHp=0 eventDisposed=unbound heldDead=1",
                    AgentLpqCoordinator.eventEndDetail(session, leader));
        }
    }

    @Test
    void aLeaderThatCannotBeReadYieldsDetailUnavailable() {
        try (LpqRunFixture run = new LpqRunFixture()) {
            Character leader = run.human(762_210, "Crewfriend");
            AgentLpqSession session = run.session(AgentLpqSession.Mode.PRODUCTION, AgentLpqSession.Phase.STAGE_9,
                    leader, run.companion(762_211, "Halvard"), run.companion(762_212, "Lioren"),
                    run.companion(762_213, "Pell"), run.companion(762_214, "Kestrel"),
                    run.companion(762_215, "Casse"));
            Character broken = mock(Character.class);
            when(broken.getMapId()).thenThrow(new IllegalStateException("logged out"));

            assertEquals("detail unavailable", AgentLpqCoordinator.eventEndDetail(session, broken));
        }
    }

    @Test
    void theTickLogsTheDetailBeforeFailingAnEndedEvent() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java")).replace("\r\n", "\n");
        int tick = source.indexOf("    static void tick(AgentLpqSession session, long nowMs) {");
        String body = source.substring(tick, source.indexOf("\n    }\n", tick)).replaceAll("\\s+", " ");
        int detail = body.indexOf("log.warn(\"LPQ event end detail: session={} {}\", session.sessionId(),"
                + " eventEndDetail(session, leader))");
        int fail = body.indexOf("AgentLpqTerminationService.fail(session, eventFailure, nowMs)");
        assertTrue(detail > 0 && fail > detail, "the end detail precedes the failure");
        assertTrue(body.substring(body.indexOf("String eventFailure"), detail)
                .contains("EVENT_ENDED_OR_EXPIRED.equals(eventFailure)"), "only for an ended or expired event");
        assertTrue(source.contains("return EVENT_ENDED_OR_EXPIRED;"));
        assertTrue(source.contains(
                "private static final String EVENT_ENDED_OR_EXPIRED = \"The LPQ event instance ended or expired\";"));
    }
}
