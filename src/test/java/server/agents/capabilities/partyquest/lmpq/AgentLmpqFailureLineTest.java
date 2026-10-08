package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P6h LMPQ step 1, the 'LMPQ session failure:' line: every coordinator failure goes through
 * AgentLmpqCoordinator.fail, which logs the phase the run was in (not FAILED) with the reason, the run's size and
 * whether it is a companion run, then fails it with the same reason as before. Each of the six failure sites is
 * driven once, and a source scan holds that no site fails the session directly.
 */
class AgentLmpqFailureLineTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentLmpqSession farming() {
        return run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING, ben, mira, kes, pel);
    }

    private static String line(AgentLmpqSession session, String reason) {
        return "LMPQ session failure: session=" + session.sessionId() + " phase=FARMING reason=" + reason
                + " members=4 companionRun=false";
    }

    private void assertFailedWith(AgentLmpqSession session, LmpqLogLines lines, String reason) {
        assertEquals(List.of(line(session, reason)), lines.startingWith("LMPQ session failure:"));
        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        assertEquals(reason, session.failure());
    }

    @Test
    void theEventTimer() {
        AgentLmpqSession session = farming();
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 1_000_000L);
            assertFailedWith(session, lines, "LMPQ event timer expired");
        }
    }

    @Test
    void aLeaderWithNoHpLeft() {
        AgentLmpqSession session = farming();
        when(ben.getHp()).thenReturn(0);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);
            assertFailedWith(session, lines, "LMPQ event leader is unavailable");
        }
    }

    @Test
    void anEventDisposedBeforeTheClear() {
        AgentLmpqSession session = farming();
        when(run.event.isEventDisposed()).thenReturn(true);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);
            assertFailedWith(session, lines, "LMPQ event ended before the clear");
        }
    }

    @Test
    void aMemberInAnotherInstance() {
        AgentLmpqSession session = farming();
        when(kes.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);
            assertFailedWith(session, lines, "An LMPQ participant left the active maze");
        }
    }

    @Test
    void aStall() {
        AgentLmpqSession session = farming();
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);
            assertEquals(List.of(), lines.startingWith("LMPQ session failure:"));
            AgentLmpqCoordinator.tickSession(session, 250_000L);
            assertFailedWith(session, lines, "LMPQ stalled in FARMING");
        }
    }

    @Test
    void anAgentEnteringADifferentInstance() {
        AgentLmpqSession session = farming();
        when(mira.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 10_000L);
            assertFailedWith(session, lines, "LMPQ member entered a different event instance");
        }
    }

    @Test
    void aCompanionRunSaysSo() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.CLEARING, ben, mira, kes, pel);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.fail(session, "stopped by operator", 10_000L);
            assertEquals(List.of("LMPQ session failure: session=" + session.sessionId()
                            + " phase=CLEARING reason=stopped by operator members=4 companionRun=true"),
                    lines.startingWith("LMPQ session failure:"));
        }
    }

    @Test
    void noSiteFailsTheSessionDirectly() throws Exception {
        assertEquals(Map.of("fail", 1), LmpqSource.of("AgentLmpqCoordinator").callers("\\bsession\\s*\\.\\s*fail\\s*\\("));
        Map<String, Integer> sites = LmpqSource.of("AgentLmpqCoordinator").callers("\\bfail\\s*\\(\\s*session\\s*,");
        assertEquals(Map.of("tickMember", 1, "tickSession", 7), sites);
    }
}
