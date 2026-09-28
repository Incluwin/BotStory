package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.CashShop;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.portalX;

/**
 * Channel follow (docs/BOT_MANAGEMENT_PLAN.md flow 6, "CompanionChannelFollow"). Ann changes from channel 1 to 2 with
 * Hadrik and Jinx out and Olwen on a live bench slice. The change is no logout: no goodbye, no PARKED, no lineup save.
 * Once Ann is in on channel 2, each companion out moves there 1-3 s apart: it logs off on channel 1 as an offline
 * member of her party, logs in on 2 at the portal nearest her and walks in (a traveller where it stood, arriving on
 * time); the party row updates without a leave or a join. One not there 30 s after her login is moved the hard way.
 * The bench slice stays on its channel. A Cash Shop visit leaves them idle; past the grace they log off as at a
 * logout. An owner who never comes in on the new channel logged off after all.
 */
class ChannelFollowTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private Persona hadrik;
    private Persona jinx;
    private Persona olwen;

    @BeforeEach
    void outWithAnnOnChannelOne() {
        hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        jinx = game.companion("jinx", "Jinx", 902, RosterState.ACTIVE, LITH);
        olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
        game.outWithAnn(hadrik);
        game.outWithAnn(jinx);
        for (Persona persona : List.of(hadrik, jinx, olwen)) {
            game.companionOnChannel(persona, 1);
        }
        game.loaded.add(900); // Olwen's live bench slice
        game.roster();
    }

    @AfterEach
    void close() {
        game.close();
    }

    /** Client.changeChannel's hook, the old session's logout, then the login on the new channel. */
    private void changeChannel(int channel) {
        game.roster().ownerChangingChannel(game.ann, channel);
        game.roster().onOwnerLogout(game.ann, null);
        game.annOnChannel(channel);
        game.roster().onOwnerLogin(game.ann);
    }

    /** The moves the login scheduled (the fallback and the login wait come later). */
    private List<LifecycleHarness.Timer> moves() {
        return game.timers.stream().filter(timer -> timer.delayMs() < CompanionChannelFollow.FALLBACK_MS).toList();
    }

    private void run(long delayMs) {
        for (LifecycleHarness.Timer timer : List.copyOf(game.timers)) {
            if (timer.delayMs() == delayMs) {
                game.timers.remove(timer);
                timer.task().run();
            }
        }
    }

    @Test
    void aChannelChangeIsNoLogout() {
        changeChannel(2);

        assertEquals(List.of(), game.calls, "nothing logs off with the old session");
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertEquals(RosterState.ACTIVE, game.row(jinx).state());
        assertTrue(game.partyChat.isEmpty(), "no goodbye");
        assertTrue(game.store.lineup.isEmpty(), "no lineup save");
        assertTrue(game.store.events("companion.parked").isEmpty());
        assertTrue(game.roster().inFlight(ANN, "hadrik"), "the auditor leaves a companion changing channel alone");
    }

    @Test
    void eachCompanionMovesOverOneToThreeSecondsApartAndWalksInFromThePortal() {
        changeChannel(2);

        List<LifecycleHarness.Timer> moves = moves();
        assertEquals(2, moves.size());
        long first = moves.get(0).delayMs();
        long gap = moves.get(1).delayMs() - first;
        assertTrue(first >= 1_000L && first <= 3_000L, "first after 1-3 s: " + first);
        assertTrue(gap >= 1_000L && gap <= 3_000L, "1-3 s apart: " + gap);
        assertTrue(game.timers.stream().anyMatch(timer -> timer.delayMs() == CompanionChannelFollow.FALLBACK_MS));

        moves.forEach(timer -> timer.task().run());

        String portal = HENESYS_FIELD + " " + portalX(HENESYS_FIELD) + ",200";
        assertEquals(List.of("remove Hadrik", "spawn Hadrik at " + portal, "follow Hadrik",
                "remove Jinx", "spawn Jinx at " + portal, "follow Jinx"), game.calls, "no leave and no join");
        assertEquals(List.of(ANN, 901, 902), game.members, "still her party's members");
        assertEquals(List.of(RosterService.CAUSE, RosterService.CAUSE), game.causes, "the party row updates as an echo");
        assertEquals(2, game.companionChannel(hadrik));
        assertEquals(2, game.companionChannel(jinx));
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertEquals(2, game.store.events("companion.channel_follow").size());
        assertFalse(game.roster().inFlight(ANN, "hadrik"));
    }

    @Test
    void oneNotOverThirtySecondsAfterTheLoginIsMovedTheHardWay() {
        when(game.budget.admitReplacing(any(), any(client.Character.class)))
                .thenReturn(CompanionBudget.Admission.deny("test: no load now"));
        changeChannel(2);
        moves().forEach(timer -> timer.task().run());
        assertEquals(List.of(), game.calls, "neither could move");

        run(CompanionChannelFollow.FALLBACK_MS);

        String portal = HENESYS_FIELD + " " + portalX(HENESYS_FIELD) + ",200";
        assertEquals(List.of("leave Hadrik", "remove Hadrik", "spawn Hadrik at " + portal, "join Hadrik", "follow Hadrik",
                "leave Jinx", "remove Jinx", "spawn Jinx at " + portal, "join Jinx", "follow Jinx"), game.calls);
        assertEquals(2, game.companionChannel(hadrik));
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertEquals(RosterState.ACTIVE, game.row(jinx).state());
    }

    @Test
    void aLiveBenchSliceStaysOnItsChannel() {
        changeChannel(2);
        game.runTimers();

        assertTrue(game.calls.stream().noneMatch(call -> call.contains("Olwen")), String.valueOf(game.calls));
        assertEquals(1, game.companionChannel(olwen));
        assertTrue(game.loaded.contains(900));
        assertEquals(RosterState.BENCHED, game.row(olwen).state());
    }

    @Test
    void aTravellerMovesWhereItStandsAndStillArrivesOnTime() {
        Persona vex = game.companion("vex", "Vexa", 903, RosterState.BENCHED, LITH);
        game.companionOnChannel(vex, 1);
        game.roster().call(game.ann, vex); // join-then-travel from Lith Harbor on channel 1
        List<LifecycleHarness.Timer> trip = List.copyOf(game.timers);
        game.timers.clear();
        game.calls.clear();

        changeChannel(2);
        moves().forEach(timer -> timer.task().run());
        trip.forEach(timer -> timer.task().run()); // its ETA ends

        String camp = LITH + " " + portalX(LITH) + ",200";
        List<String> vexa = game.calls.stream().filter(call -> call.contains("Vexa")).toList();
        assertEquals(List.of("remove Vexa", "spawn Vexa at " + camp,
                "warp Vexa to " + HENESYS_FIELD + " at " + portalX(HENESYS_FIELD) + ",200", "follow Vexa"), vexa,
                "it logs in at its camp on channel 2 without follow, then arrives by the portal");
        assertEquals(RosterState.ACTIVE, game.row(vex).state());
        assertEquals(2, game.companionChannel(vex));
    }

    @Test
    void theCashShopLeavesThemIdleAndAVisitPastTheGraceLogsThemOff() {
        CashShop shop = mock(CashShop.class);
        when(shop.isOpened()).thenReturn(true);
        when(game.ann.getCashShop()).thenReturn(shop);

        game.roster().ownerEnteredCashShop(game.ann);

        assertEquals(1, game.timers.size());
        assertEquals(game.config.LEADER_OFFLINE_BENCH_MS, game.timers.get(0).delayMs());
        assertEquals(List.of(), game.calls, "they idle where they stand");

        game.runTimers();

        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        assertEquals(RosterState.PARKED, game.row(jinx).state());
        assertEquals(List.of("remove Hadrik", "remove Jinx"), game.calls);
    }

    @Test
    void leavingTheCashShopInTimeKeepsThemOut() {
        CashShop shop = mock(CashShop.class);
        when(shop.isOpened()).thenReturn(true);
        when(game.ann.getCashShop()).thenReturn(shop);
        game.roster().ownerEnteredCashShop(game.ann);

        changeChannel(1); // the way out is a change to the same channel
        game.runTimers();

        assertEquals(List.of(), game.calls, "on her channel already: nothing moves, nothing logs off");
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertEquals(RosterState.ACTIVE, game.row(jinx).state());
    }

    @Test
    void anOwnerWhoNeverComesInOnTheNewChannelLoggedOffAfterAll() {
        game.roster().ownerChangingChannel(game.ann, 2);
        game.roster().onOwnerLogout(game.ann, null);
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());

        run(CompanionChannelFollow.LOGIN_WAIT_MS);

        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        assertEquals(RosterState.PARKED, game.row(jinx).state());
    }

    @Test
    void aRealLogoutAfterTheChangeIsALogout() {
        changeChannel(2);
        moves().forEach(timer -> timer.task().run());

        game.roster().onOwnerLogout(game.ann, null);

        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        assertEquals(RosterState.PARKED, game.row(jinx).state());
        assertEquals(List.of("Hadrik: gn!"), game.partyChat);
    }
}
