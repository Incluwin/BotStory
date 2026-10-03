package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * A companion run hands its pace to the host as it is released (plan 2.6: childFinished(..., companionRun ? pace :
 * null)), so the host's records can teach the clear-time estimate; a stock run hands none. Each span of a companion
 * run gets one 'HPQ pace span:' line at the release, beside its theory at the priors while the profile is warm.
 */
class AgentHpqTerminationPaceTest {
    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(0, -210));
    private final Character mira = run.companion(2, "Mira", new Point(-183, -188));
    private final Character tobi = run.companion(3, "Tobi", new Point(-293, -188));

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentHpqSession clearedCompanionRun() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.EXITING, ben, mira, tobi);
        session.pace().markEntered(power(), List.of(1, 2, 3), 3_000L);
        session.pace().markBunny(160_000L);
        session.pace().observeBunnyCakes(10, 361_000L);
        session.pace().markCleared(401_000L);
        return session;
    }

    private static List<AgentPqMemberPower> power() {
        return List.of(power(1, true), power(2, false), power(3, false));
    }

    private static AgentPqMemberPower power(int id, boolean human) {
        return new AgentPqMemberPower(id, "m" + id, human, human, 30, 100, Map.of(9_300_064, 100.0d),
                Map.of(9_300_064, 0.9d), Map.of(9_300_064, 800.0d), 20.0d);
    }

    private AgentPqPace handedOver(AgentHpqSession session, boolean completed, String reason, long nowMs) {
        ArgumentCaptor<AgentPqPace> pace = ArgumentCaptor.forClass(AgentPqPace.class);
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(completed), eq(reason), eq(nowMs), pace.capture()));
        return pace.getValue();
    }

    @Test
    void aCompletedCompanionRunHandsItsPaceToTheHost() {
        AgentHpqSession session = clearedCompanionRun();

        AgentHpqTerminationService.complete(session, 420_000L);

        AgentPqPace pace = handedOver(session, true, "completed", 420_000L);
        assertEquals("hpq", pace.questKey());
        assertEquals(session.sessionId(), pace.sessionId());
        assertEquals(401_000L, pace.clearedAtMs());
        assertEquals(List.of(
                new AgentPqPace.Span("yes", 1_000L, 3_000L, 0.0d, true),
                new AgentPqPace.Span("seeds", 3_000L, 160_000L, 0.0d, true),
                new AgentPqPace.Span("bunny", 160_000L, 361_000L, 0.0d, true),
                new AgentPqPace.Span("handin", 361_000L, 401_000L, 0.0d, true)), pace.spans());
        assertEquals(power(), pace.members());
    }

    @Test
    void aFailedCompanionRunHandsItsPaceWithTheOpenSpanIncomplete() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);
        session.pace().markEntered(power(), List.of(1, 2, 3), 3_000L);
        session.pace().markBunny(160_000L);

        AgentHpqTerminationService.fail(session, "The Moon Bunny was defeated", 250_000L);

        AgentPqPace pace = handedOver(session, false, "The Moon Bunny was defeated", 250_000L);
        assertEquals(0L, pace.clearedAtMs());
        assertEquals(new AgentPqPace.Span("bunny", 160_000L, 250_000L, 0.0d, false), pace.span("bunny"));
        assertNull(pace.span("handin"));
    }

    @Test
    void aStockRunHandsNoPace() {
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.DEFENDING_BUNNY, mira, tobi, ben);
        session.pace().markEntered(power(), List.of(1, 2, 3), 3_000L);

        AgentHpqTerminationService.fail(session, "The HPQ event leader is unavailable", 250_000L);

        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(false), eq("The HPQ event leader is unavailable"), eq(250_000L), isNull()));
    }

    @Test
    void aPaceReportThatThrowsLeavesThePaceOutAndTheReleaseFinished() {
        AgentHpqSession session = clearedCompanionRun();
        try (MockedStatic<AgentHpqPaceReport> report = mockStatic(AgentHpqPaceReport.class, CALLS_REAL_METHODS)) {
            report.when(() -> AgentHpqPaceReport.of(any(AgentHpqSession.class), anyLong()))
                    .thenThrow(new IllegalStateException("broken marks"));

            AgentHpqTerminationService.complete(session, 420_000L);
        }

        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(true), eq("completed"), eq(420_000L), isNull()));
        assertEquals(AgentHpqSession.Phase.COMPLETED, session.phase());
    }

    @Test
    void eachSpanOfACompanionRunIsLoggedOnceAtTheReleaseBesideItsTheory() {
        AgentHpqSession session = clearedCompanionRun();
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class);
             HpqLogLines log = new HpqLogLines(AgentHpqPaceReport.class)) {
            profiles.when(() -> AgentPqPaceProfiles.cached(anyString())).thenReturn(AgentHpqPaceProfile.load(60_000L));

            AgentHpqTerminationService.complete(session, 420_000L);
            // A stock run of the same members afterwards logs no span.
            AgentHpqSession stock = run.session(false, AgentHpqSession.Phase.DEFENDING_BUNNY, mira, tobi, ben);
            stock.pace().markEntered(power(), List.of(2, 3, 1), 3_000L);
            AgentHpqTerminationService.fail(stock, "The HPQ event leader is unavailable", 430_000L);

            String prefix = "HPQ pace span: session=" + session.sessionId();
            assertEquals(List.of(
                    prefix + " key=yes seconds=2.0 theorySeconds=1.0 complete=true why=-",
                    prefix + " key=seeds seconds=157.0 theorySeconds=150.0 complete=true why=-",
                    prefix + " key=bunny seconds=201.0 theorySeconds=200.0 complete=true why=-",
                    prefix + " key=handin seconds=40.0 theorySeconds=40.0 complete=true why=-"),
                    log.startingWith("HPQ pace span: "));
        }
    }

    @Test
    void aColdProfileOrAnUnreadPartyLogsNoTheory() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);
        session.pace().markEntered(List.of(), List.of(1, 2, 3), 3_000L);
        session.pace().markBunny(160_000L);
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class);
             HpqLogLines log = new HpqLogLines(AgentHpqPaceReport.class)) {
            profiles.when(() -> AgentPqPaceProfiles.cached(anyString())).thenReturn(null);

            AgentHpqTerminationService.fail(session, "The Moon Bunny was defeated", 250_000L);

            assertEquals("HPQ pace span: session=" + session.sessionId()
                            + " key=bunny seconds=90.0 theorySeconds=-1 complete=false why=open",
                    log.startingWith("HPQ pace span: ").get(2));
        }
    }
}
