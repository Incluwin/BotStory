package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Decision 8a in the owner's own party (docs/BOT_MANAGEMENT_PLAN.md flow 7 steps 1-2): Ann is its only human. At her
 * logout the usual lineup is saved from the party window's order and one companion says goodbye in party chat; every
 * companion out logs off with her and stays in the party as an offline member (nobody leaves it, so the roster makes
 * no party event of its own beyond the stock log-off), and its row is PARKED from the logout. A trip on its way ends
 * there and a companion waiting outside a run parks too, giving its seat back. Benched and away rows are left alone.
 * The lead stays with Ann by the P1 successor rule (LeaderSuccessionTest.aHumanLeaderWithOnlyBotsOnlineStaysLeader).
 */
class LogoutOwnPartyTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private Persona hadrik;
    private Persona jinx;
    private Persona olwen;
    private Persona vex;

    @BeforeEach
    void twoOutWithAnn() {
        hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        jinx = game.companion("jinx", "Jinx", 902, RosterState.ACTIVE, LITH);
        olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
        vex = game.companion("vex", "Vex", 903, RosterState.AWAY, LITH);
        game.outWithAnn(jinx); // Jinx joined first, so the party window lists her first
        game.outWithAnn(hadrik);
        game.roster();
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void companionsLogOffWithTheirOwnerAndStayInThePartyAsOfflineMembers() {
        long before = System.currentTimeMillis();

        game.roster().onOwnerLogout(game.ann, null);

        assertEquals(List.of("remove Jinx", "remove Hadrik"), game.calls, "they log off: nobody leaves the party");
        assertEquals(List.of(ANN, 902, 901), game.members, "the party keeps them, as offline members");
        for (Persona persona : List.of(hadrik, jinx)) {
            RosterEntry row = game.row(persona);
            assertEquals(RosterState.PARKED, row.state(), persona.name());
            assertTrue(row.parkedAtMs() >= before - 1_000L && row.parkedAtMs() <= System.currentTimeMillis() + 1_000L, "parked_at (a whole-second column)");
        }
        assertEquals(2, game.store.events("companion.parked").size());
        assertEquals(0, game.leases.count(CompanionLeases.Kind.COMPANION), "their leases go with them");
    }

    @Test
    void oneCompanionSaysGoodbyeInPartyChat() {
        game.roster().onOwnerLogout(game.ann, null);

        assertEquals(List.of("Jinx: gn!"), game.partyChat, "one goodbye, from the first in the lineup");
    }

    @Test
    void theUsualLineupIsSavedFromThePartyWindowsOrder() {
        game.store.lineup.put(FakeLivingStore.key(ANN, "hadrik"), 1); // an older lineup
        game.store.lineup.put(FakeLivingStore.key(ANN, "olwen"), 2);

        game.roster().onOwnerLogout(game.ann, null);

        assertEquals(Map.of("jinx", 1, "hadrik", 2), game.store.store.lineupSlots(ANN),
                "those out, in party order; a benched one loses its place");
    }

    @Test
    void aTravellerParksWhereItsTripEndsAndAHoldGivesItsSeatBack() {
        game.roster().bench(game.ann, jinx);
        game.runTimers(); // she walks out
        game.roster().call(game.ann, olwen); // join-then-travel from Lith Harbor
        game.roster().hold(game.ann, hadrik, HENESYS_FIELD, null);
        assertNotNull(game.slots.reservation("hadrik"));
        List<LifecycleHarness.Timer> trip = List.copyOf(game.timers);
        game.timers.clear();
        game.calls.clear();

        game.roster().onOwnerLogout(game.ann, null);
        trip.forEach(timer -> timer.task().run()); // Olwen's ETA ends while Ann is away

        RosterEntry traveller = game.row(olwen);
        assertEquals(RosterState.PARKED, traveller.state());
        assertEquals(0L, traveller.etaAtMs(), "its trip ends");
        assertTrue(game.members.contains(900), "a traveller was a member: it stays one, offline");
        RosterEntry held = game.row(hadrik);
        assertEquals(RosterState.PARKED, held.state());
        assertEquals(0, held.holdMapId());
        assertNull(game.slots.reservation("hadrik"), "its HOLD seat went back");
        assertEquals(List.of("remove Olwen", "remove Hadrik"), game.calls, "nothing arrives after the logout");
        assertEquals(List.of("Olwen: gn!"), game.partyChat, "a hold is out of the party chat");
    }

    @Test
    void benchedAndAwayRowsAreLeftAlone() {
        RosterEntry benched = game.row(olwen);
        RosterEntry away = game.row(vex);

        game.roster().onOwnerLogout(game.ann, null);

        assertEquals(benched, game.row(olwen));
        assertEquals(away, game.row(vex));
        assertNotNull(game.slots.reservation("vex"), "an errand keeps its seat");
        assertFalse(game.calls.stream().anyMatch(call -> call.contains("Olwen") || call.contains("Vex")));
    }

    @Test
    void anOwnerWithNobodyOutLeavesTheLineupEmpty() {
        game.roster().bench(game.ann, jinx);
        game.roster().bench(game.ann, hadrik);
        game.runTimers();
        game.store.lineup.put(FakeLivingStore.key(ANN, "hadrik"), 1);

        game.roster().onOwnerLogout(game.ann, null);

        assertEquals(Map.of(), game.store.store.lineupSlots(ANN));
        assertTrue(game.partyChat.isEmpty());
        assertEquals(0, game.store.events("companion.parked").size());
    }
}
