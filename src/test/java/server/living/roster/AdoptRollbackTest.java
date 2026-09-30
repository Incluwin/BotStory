package server.living.roster;

import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A start that fails after admission (P6, flow 3 step 6: both lobbies of the channel taken): the session is stopped
 * through the first companion, its end hands everyone back and ends the run, and Lakelis gives 9020000.js's own line.
 * Nothing stays committed and the companions that waited outside rejoin. With no session left to stop, the run is
 * ended here all the same. A start that throws ends as 9020000.js's event error does.
 */
class AdoptRollbackTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private Persona casse;

    @BeforeEach
    void bothLobbiesAreTaken() {
        fx.companion("mira", "Mira", 900, 24, Job.HUNTER);
        fx.companion("tobi", "Tobi", 901, 27, Job.PAGE);
        fx.companion("pip", "Pip", 902, 26, Job.FP_WIZARD);
        casse = fx.companion("casse", "Casse", 903, 32, Job.BANDIT);
        fx.ready();
        fx.doors.start = PqEntryPort.Start.NO_LOBBY;
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void aStartThatFailsStopsTheSessionThroughTheFirstCompanionAndAnswersTheStockLine() {
        fx.doors.onStop = () -> fx.sessionEnds(false); // the Agent layer's stop: the session ends and says so

        fx.yes();

        assertEquals(List.of("stop 900: companion KPQ start failed"),
                game.calls.stream().filter(call -> call.startsWith("stop 9")).toList(), "once, with the first companion");
        assertTrue(game.calls.contains("answer " + LakelisLines.NO_LOBBY));
        assertEquals(List.of(), game.commitments.members("pq:kpq:" + fx.doors.session.sessionId()));
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "the one that waited outside rejoins");
        assertTrue(game.members.contains(903));
        assertNull(fx.service.runForSession(fx.doors.session.sessionId()));
    }

    @Test
    void aStartThatThrowsIsStoppedAndAnsweredAsAnEventError() {
        fx.doors.startThrows = new NullPointerException("setup"); // EventManager.startInstance rethrows a setup failure
        fx.doors.onStop = () -> fx.sessionEnds(false);

        fx.yes();

        assertEquals(List.of("stop 900: companion KPQ start failed"),
                game.calls.stream().filter(call -> call.startsWith("stop 9")).toList());
        assertTrue(game.calls.contains("answer " + LakelisLines.NO_EVENT));
        assertEquals(List.of(), game.commitments.members("pq:kpq:" + fx.doors.session.sessionId()));
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "the one that waited outside rejoins");
        assertNull(fx.service.runForSession(fx.doors.session.sessionId()));
    }

    @Test
    void withNoSessionLeftToStopTheRunIsEndedHere() {
        fx.doors.onStop = () -> AgentKpqSessionRegistry.remove(fx.doors.session); // gone without a word

        fx.yes();

        assertTrue(game.calls.contains("answer " + LakelisLines.NO_LOBBY));
        assertEquals(List.of(), game.commitments.members("pq:kpq:" + fx.doors.session.sessionId()));
        assertEquals(RosterState.ACTIVE, game.row(casse).state());
        assertNull(fx.service.runForSession(fx.doors.session.sessionId()));
    }
}
