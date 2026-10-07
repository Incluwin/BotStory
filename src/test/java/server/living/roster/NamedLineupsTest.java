package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.roster.CompanionManager.NamedLineup;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Named lineups (P9; decisions 2026-10-06): the Crew window keeps three crews, Hunting, PQ and Boss, each up to five
 * companions in party order. "Remember this crew as..." saves whoever is out with the owner; calling one swaps: the
 * companions out who are not in it go back to the bench, and its own come (join-then-travel). A companion released
 * since is passed over. The usual lineup is left as it was.
 */
class NamedLineupsTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private CompanionManager manager;
    private Persona tobi;
    private Persona hadrik;
    private Persona mira;
    private Persona jinx;

    @BeforeEach
    void crew() {
        when(game.ann.getLevel()).thenReturn(30);
        tobi = game.companion("tobi", "Tobi", 901, RosterState.ACTIVE, LITH);
        hadrik = game.companion("hadrik", "Hadrik", 902, RosterState.ACTIVE, LITH);
        mira = game.companion("mira", "Mira", 950, RosterState.BENCHED, LITH);
        jinx = game.companion("jinx", "Jinx", 951, RosterState.BENCHED, LITH);
        game.outWithAnn(tobi);
        game.outWithAnn(hadrik);
        game.roster();
        manager = new CompanionManager(game.world);
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void rememberThisCrewSavesWhoIsOutInPartyOrderAndLeavesTheUsualLineup() {
        game.store.lineup.put(ANN + ":jinx", 1);

        RosterResult saved = manager.saveNamed(game.ann, NamedLineup.PQ);

        assertEquals(new RosterResult.Ok("Your PQ lineup: Tobi and Hadrik."), saved);
        assertEquals(List.of("tobi", "hadrik"), game.store.namedLineups.get(ANN + ":PQ"));
        assertEquals(1, game.store.lineup.get(ANN + ":jinx"), "the usual lineup is its own");
        assertEquals(List.of("Tobi", "Hadrik"), manager.namedLineups(game.ann).get(NamedLineup.PQ));
    }

    @Test
    void callingALineupSwapsTheOthersBackToTheBenchAndCallsItsOwn() {
        game.store.namedLineups.put(ANN + ":BOSS", List.of("mira", "tobi"));

        RosterResult called = manager.callNamed(game.ann, NamedLineup.BOSS);

        assertTrue(called.ok(), called.toString());
        assertEquals(RosterState.TRAVELLING, game.row(mira).state());
        assertEquals(RosterState.ACTIVE, game.row(tobi).state(), "in the lineup: it stays");
        assertEquals(RosterState.BENCHED, game.row(hadrik).state(), "not in the lineup: back to the bench");
        assertEquals(RosterState.BENCHED, game.row(jinx).state());
        assertTrue(((RosterResult.Ok) called).line().contains("Mira is on the way"), called.toString());
        assertTrue(((RosterResult.Ok) called).line().contains("Hadrik heads back to camp"), called.toString());
    }

    @Test
    void aLineupLargerThanTheCrewOutFillsFreeSeatsAndBenchesNobodyInIt() {
        game.store.namedLineups.put(ANN + ":HUNTING", List.of("mira", "jinx", "tobi", "hadrik"));

        assertTrue(manager.callNamed(game.ann, NamedLineup.HUNTING).ok());

        assertEquals(RosterState.TRAVELLING, game.row(mira).state());
        assertEquals(RosterState.TRAVELLING, game.row(jinx).state());
        assertEquals(RosterState.ACTIVE, game.row(tobi).state());
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
    }

    @Test
    void noLineupYetOrEveryoneOutAlreadySaysSoAndChangesNothing() {
        assertEquals(new RosterResult.Failed("You have no Boss lineup yet."), manager.callNamed(game.ann, NamedLineup.BOSS));

        game.store.namedLineups.put(ANN + ":PQ", List.of("tobi", "hadrik"));
        assertEquals(new RosterResult.Failed("Everyone in your PQ lineup is out already."),
                manager.callNamed(game.ann, NamedLineup.PQ));

        assertEquals(RosterState.BENCHED, game.row(mira).state());
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
    }

    @Test
    void aCompanionReleasedSinceIsPassedOver() {
        game.store.namedLineups.put(ANN + ":PQ", List.of("jinx", "mira"));
        game.store.seed(game.row(mira).with(RosterState.RELEASED, 0L));

        RosterResult called = manager.callNamed(game.ann, NamedLineup.PQ);

        assertEquals(RosterState.TRAVELLING, game.row(jinx).state());
        assertEquals(RosterState.RELEASED, game.row(mira).state());
        assertFalse(((RosterResult.Ok) called).line().contains("Mira"), called.toString());
        assertEquals(List.of("Jinx"), manager.namedLineups(game.ann).get(NamedLineup.PQ));
    }

    @Test
    void aSuggestedCrewCalledIntoAFullPartyTradesPlacesOnlyWithOneNotInIt() {
        List<Persona> extras = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Persona extra = game.companion("extra" + i, "Extra" + i, 960 + i, RosterState.ACTIVE, LITH);
            game.outWithAnn(extra);
            extras.add(extra);
        }

        RosterResult called = manager.callSuggested(game.ann, List.of(tobi, mira));

        assertTrue(called.ok(), called.toString());
        assertEquals(RosterState.TRAVELLING, game.row(mira).state());
        assertEquals(RosterState.ACTIVE, game.row(tobi).state(), "in the crew: never traded away");
        long benched = java.util.stream.Stream.concat(extras.stream(), java.util.stream.Stream.of(hadrik))
                .filter(persona -> game.row(persona).state() == RosterState.BENCHED).count();
        assertEquals(1, benched, "one seat was needed, so one left");
        assertEquals(RosterState.BENCHED, game.row(jinx).state());
    }

    @Test
    void nobodyOutLeavesNothingToRemember() {
        RosterResult benchTobi = game.roster().bench(game.ann, tobi);
        RosterResult benchHadrik = game.roster().bench(game.ann, hadrik);
        assertTrue(benchTobi.ok() && benchHadrik.ok());

        assertInstanceOf(RosterResult.Failed.class, manager.saveNamed(game.ann, NamedLineup.HUNTING));
        assertFalse(game.store.namedLineups.containsKey(ANN + ":HUNTING"));
    }
}
