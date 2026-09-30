package server.living.roster;

import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback.RunEnded;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback.TapOut;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.LifecycleHarness.KERNING;

/**
 * A companion run ends (P6, runEnded): on companion-ops its commitments end first, then the owners already out of the
 * event get their held companions back, the one that tapped out and the extras beside Lakelis alike. An owner still
 * inside gets them as they come out. A second end (a runEnded again, or an abort's own end after the stop it made),
 * or one for a session no run holds, does nothing.
 */
class CompanionRunEndedTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private Persona tobi;
    private Persona casse;
    private CompanionRun run;

    @BeforeEach
    void tobiTappedOutAndCasseWaitsBesideLakelis() {
        fx.companion("mira", "Mira", 900, 24, Job.HUNTER);
        tobi = fx.companion("tobi", "Tobi", 901, 27, Job.PAGE);
        fx.companion("pip", "Pip", 902, 26, Job.FP_WIZARD);
        casse = fx.companion("casse", "Casse", 903, 25, Job.BANDIT);
        fx.potions.put(903, 0);
        fx.ready();
        run = fx.yes();
        game.annEntersInstance(103000802);
        game.bot(tobi).changeMap(game.map(CompanionPqFixture.EXIT), new Point(100, 200));
        fx.lifecycle.tapOut(new TapOut("kpq", run.sessionId, 901, CompanionPqFixture.EXIT, 3_000L));
        game.commitments.addListener(ended -> game.calls.add("released " + ended.key()));
        game.calls.clear();
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void releaseKeyThenRejoinHoldsForOwnersOutside() {
        game.moveAnn(KERNING, 1500, 120); // the run failed: Ann is back at Lakelis
        List<Runnable> ops = fx.holdCompanionOps();

        fx.sessionEnds(false);

        assertEquals(List.of(), game.calls, "nothing on the thread that ended the run");
        CompanionPqFixture.drain(ops);
        int released = game.calls.indexOf("released " + run.key);
        assertTrue(released >= 0, game.calls.toString());
        assertTrue(released < game.calls.indexOf("join Casse"), "the commitments end before the holds rejoin");
        assertTrue(released < game.calls.indexOf("join Tobi"));
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "beside Lakelis, on Ann's map: it walks over");
        assertEquals(RosterState.TRAVELLING, game.row(tobi).state(), "from the exit map: on its way");
        assertTrue(game.members.containsAll(List.of(901, 903)));
        assertFalse(game.commitments.isCommitted(900));
    }

    @Test
    void ownerStillInsideRejoinsOnMapEntry() {
        fx.sessionEnds(true); // Ann is still on the bonus map

        assertFalse(game.commitments.isCommitted(903));
        assertEquals(RosterState.HOLD, game.row(casse).state(), "not while Ann is inside");

        game.moveAnn(KERNING, 1500, 120);
        game.roster().ownerEnteredMap(game.ann, game.map(KERNING));

        assertEquals(RosterState.ACTIVE, game.row(casse).state());
        assertTrue(game.members.contains(903));
    }

    @Test
    void secondRunEndedIsANoOp() {
        game.moveAnn(KERNING, 1500, 120);
        fx.sessionEnds(false);
        game.calls.clear();
        List<Runnable> ops = fx.holdCompanionOps();

        fx.lifecycle.runEnded(new RunEnded("kpq", run.sessionId, 10, false, "KPQ stopped", 6_000L));
        fx.service.endRun(run); // an abort after the stop it made ended the run already

        assertEquals(List.of(), ops);
        assertEquals(List.of(), game.calls);
    }

    @Test
    void unknownSessionIgnored() {
        List<Runnable> ops = fx.holdCompanionOps();

        fx.lifecycle.runEnded(new RunEnded("", "kpq-somebody-else", 0, true, "completed", 6_000L));

        assertEquals(List.of(), ops);
        assertTrue(game.commitments.isCommitted(903));
        assertEquals(run, fx.service.runForSession(run.sessionId));
        assertNull(fx.service.runForSession("kpq-somebody-else"));
    }
}
