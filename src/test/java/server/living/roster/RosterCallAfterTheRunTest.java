package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.roster.CompanionBossAllies.State;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static server.living.roster.BossAllyHarness.SLEEPYWOOD_CAMP;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * A call for a bench ally that is out with a boss run (P7 Part B step 8, RosterService.call): its row stays BENCHED for
 * the whole trip, so the roster's call (chat, the Crew window, the party window's invite) asks the boss runs first. It
 * answers "after the run." in its own whisper and loads nothing; its lease, its commitment and its trip stay as they
 * were.
 */
class RosterCallAfterTheRunTest {
    private static final int ORRIN = 911;

    private final BossAllyHarness h = new BossAllyHarness();
    private Persona orrin;

    @BeforeEach
    void orrinIsOnHisWayDownToMuYoung() {
        orrin = h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.game.roster();
        when(h.game.world.bossRuns()).thenReturn(h.bossRuns);
        h.allies.send(h.game.ann, "easy_balrog", List.of("orrin"), h.now);
        assertEquals(State.TRAVELLING, h.allies.trip("orrin").state);
        h.whispers.clear();
        h.game.calls.clear();
    }

    @AfterEach
    void close() {
        h.close();
    }

    @Test
    void aBenchedRowWithATripAnswersAfterTheRunAndLoadsNothing() {
        RosterResult result = h.game.roster().call(h.game.ann, orrin);

        assertInstanceOf(RosterResult.InEventInstance.class, result);
        assertEquals(List.of("Orrin -> Ann: " + RosterService.AFTER_THE_RUN_LINE), h.whispers);
        assertEquals(List.of(), h.game.calls, "no load, no join, no walk");
        assertEquals(RosterState.BENCHED, h.game.row(orrin).state());
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.EXPEDITION, ANN), h.game.leases.holder(ORRIN));
        assertEquals(BossAllyHarness.KEY, h.game.commitments.keyOf(ORRIN));
        assertEquals(State.TRAVELLING, h.allies.trip("orrin").state);
    }

    @Test
    void onceItsTripIsOverTheCallGoesThroughAsBefore() {
        h.open.phases.put(ORRIN, server.agents.capabilities.expedition.boss.AgentBossRunMember.Phase.REFUSED);
        h.game.runDueTimers(); // its arrival finds it refused: it turns back, and its trip is over
        assertFalse(h.allies.onTrip("orrin"));
        h.whispers.clear();

        RosterResult result = h.game.roster().call(h.game.ann, orrin);

        assertTrue(result.ok(), result.toString());
        assertFalse(h.whispers.contains("Orrin -> Ann: " + RosterService.AFTER_THE_RUN_LINE), h.whispers.toString());
    }
}
