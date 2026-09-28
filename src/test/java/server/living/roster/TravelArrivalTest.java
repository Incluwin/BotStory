package server.living.roster;

import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.party.JoinOutcome;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.PERION;
import static server.living.roster.LifecycleHarness.portalX;

/**
 * Join-then-travel (docs/BOT_MANAGEMENT_PLAN.md, "Join-then-travel arrival", flow 1, decision 7): a companion called
 * from a camp elsewhere is loaded at once without follow at its camp on the owner's channel, joins the party at once
 * (the party window shows where it is), whispers its ETA and is TRAVELLING with eta_at persisted. When the ETA ends it
 * comes into the owner's current map at the portal nearest them, walks in and only then follows. Each way a trip can
 * go otherwise has its outcome: the owner in a run (it waits at the entrance), benched on the way (it turns back),
 * the owner offline (it parks with them and walks in at the login), the party full or the join failing
 * (nothing stays loaded), the owner on another channel (it comes in on theirs); a whistle still seats it at once.
 */
class TravelArrivalTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private final Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aCallFromAFarCampJoinsAtOnceAndWhispersItsEta() {
        long before = System.currentTimeMillis();

        RosterResult result = game.roster().call(game.ann, olwen);

        // Lith Harbor to a Henesys field is one road (80 s by the road estimate), capped at 60 s (decision 7)
        assertEquals(new RosterResult.Travelling(60_000L, LITH), result);
        assertEquals(List.of("spawn Olwen at " + LITH + " " + portalX(LITH) + ",200", "join Olwen"), game.calls);
        assertTrue(game.members.contains(900), "the party window lists it at once");
        assertEquals(List.of(RosterService.CAUSE), game.causes, "its join is the roster's own echo");
        RosterEntry row = game.row(olwen);
        assertEquals(RosterState.TRAVELLING, row.state());
        assertTrue(row.etaAtMs() >= before + 60_000L && row.etaAtMs() <= System.currentTimeMillis() + 60_000L, "eta_at");
        verify(game.residents).whisper(olwen, game.ann, "on my way. ~60 s.");
        assertNull(game.slots.reservation("olwen"), "a member now: the seat held for the load went with the join");
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, ANN), game.leases.holder(900));
        assertEquals(1, game.timers.size());
        assertEquals(60_000L, game.timers.get(0).delayMs());
        long left = game.roster().travelMsLeft(ANN, "olwen"); // the ETA the Crew window shows
        assertTrue(left > 50_000L && left <= 60_000L, "left " + left);
    }

    @Test
    void itArrivesAtThePortalNearestTheOwnerOnTheMapTheyAreOnWhenTheEtaEnds() {
        game.roster().call(game.ann, olwen);
        game.moveAnn(PERION, 400, 150);  // the owner changes map during the ETA...
        game.moveAnn(KERNING, 1300, 90); // ...twice
        assertEquals(game.map(LITH), game.botMap(olwen), "nothing moves it while it travels");

        game.runTimers();

        MapleMap kerning = game.map(KERNING);
        assertEquals(kerning, game.botMap(olwen));
        assertEquals(List.of("spawn Olwen at " + LITH + " " + portalX(LITH) + ",200", "join Olwen",
                "warp Olwen to " + KERNING + " at " + portalX(KERNING) + ",200", "follow Olwen"), game.calls);
        RosterEntry row = game.row(olwen);
        assertEquals(RosterState.ACTIVE, row.state());
        assertEquals(0L, row.etaAtMs());
        verify(game.residents).reply(olwen, game.ann, "here.");
    }

    @Test
    void oneAlreadyOnTheOwnersMapWhenItArrivesWalksOverFromWhereItStands() {
        game.roster().call(game.ann, olwen);
        game.moveAnn(LITH, 900, 200); // the owner walked to its camp meanwhile

        game.runTimers();

        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("warp")), "no pop from where it stood");
        assertEquals("follow Olwen", game.calls.get(game.calls.size() - 1));
        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
    }

    @Test
    void anOwnerInsideAnInstanceAtTheEtaFindsItWaitingAtTheEntrance() {
        game.roster().call(game.ann, olwen);
        game.moveAnn(KERNING, 1500, 120);
        game.roster().ownerEnteredMap(game.ann, game.map(KERNING)); // the way in: Lakelis's map
        game.annEntersInstance(103000800);

        game.runTimers();

        RosterEntry row = game.row(olwen);
        assertEquals(RosterState.HOLD, row.state());
        assertEquals(KERNING, row.holdMapId());
        assertEquals(0L, row.etaAtMs());
        assertEquals(game.map(KERNING), game.botMap(olwen));
        assertFalse(game.members.contains(900), "out of the party while it waits");
        assertEquals(CompanionSlots.Kind.HOLD, game.slots.reservation("olwen").kind(), "its seat is kept");
        assertTrue(game.calls.contains("leave Olwen"));
        assertTrue(game.calls.contains("warp Olwen to " + KERNING + " at " + portalX(KERNING) + ",200"));
        assertFalse(game.calls.contains("follow Olwen"));
    }

    @Test
    void benchingItOnTheWayTurnsItBackAndItNeverArrives() {
        game.roster().call(game.ann, olwen);

        RosterResult benched = game.roster().bench(game.ann, olwen);

        assertEquals(new RosterResult.Ok("Olwen turns back to camp."), benched);
        assertEquals(List.of("spawn Olwen at " + LITH + " " + portalX(LITH) + ",200", "join Olwen", "leave Olwen",
                "remove Olwen"), game.calls); // off the owner's map, so it goes at once
        assertEquals(RosterState.BENCHED, game.row(olwen).state());
        assertEquals(0L, game.row(olwen).etaAtMs());
        assertNull(game.slots.reservation("olwen"));
        assertNull(game.leases.holder(900));
        assertEquals(0L, game.roster().travelMsLeft(ANN, "olwen"));

        game.runTimers(); // the trip's arrival comes due anyway

        assertEquals(4, game.calls.size(), "nothing is loaded or seated again");
        assertEquals(RosterState.BENCHED, game.row(olwen).state());
    }

    @Test
    void anOwnerOfflineDuringTheTripParksItAndItWalksInFromThePortalAtTheirLogin() {
        game.roster().call(game.ann, olwen);
        List<LifecycleHarness.Timer> trip = List.copyOf(game.timers);
        game.timers.clear();

        game.roster().onOwnerLogout(game.ann);
        game.annOnline = false;
        trip.forEach(timer -> timer.task().run()); // the ETA ends while the owner is away: nothing arrives

        assertTrue(game.calls.contains("remove Olwen"), "it logs off with its owner");
        assertFalse(game.calls.contains("leave Olwen"), "in Ann's own party it stays, an offline member");
        assertTrue(game.members.contains(900));
        RosterEntry parked = game.row(olwen);
        assertEquals(RosterState.PARKED, parked.state(), "its trip ends there: it parks (decision 8a)");
        assertEquals(0L, parked.etaAtMs());

        game.annOnline = true;
        game.calls.clear();
        game.roster().onOwnerLogin(game.ann);
        game.runDueTimers(); // the login restore, a moment after the field loads

        assertEquals(List.of("spawn Olwen at " + HENESYS_FIELD + " " + portalX(HENESYS_FIELD) + ",200", "follow Olwen"),
                game.calls, "it walks in from the portal nearest Ann, back in the party it stayed in");
        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
    }

    @Test
    void aFullPartyRefusesTheCallWithNothingLoaded() {
        for (int bot = 1; bot <= 5; bot++) {
            game.members.add(500 + bot); // five of a friend's crew
        }

        RosterResult result = game.roster().call(game.ann, olwen);

        assertInstanceOf(RosterResult.PartyFull.class, result);
        assertEquals(List.of(), game.calls);
        assertEquals(RosterState.BENCHED, game.row(olwen).state());
    }

    @Test
    void aFailedJoinLeavesNothingLoadedAndGivesItsSeatBack() {
        game.nextJoin = JoinOutcome.FAILED;

        RosterResult result = game.roster().call(game.ann, olwen);

        assertEquals("Olwen could not join right now.", RosterVoice.line(olwen, result));
        assertEquals(List.of("spawn Olwen at " + LITH + " " + portalX(LITH) + ",200", "join Olwen FAILED", "remove Olwen"),
                game.calls);
        assertEquals(RosterState.BENCHED, game.row(olwen).state());
        assertNull(game.slots.reservation("olwen"));
        assertNull(game.leases.holder(900));
        assertTrue(game.timers.isEmpty(), "no arrival is due");
        verify(game.residents).whisper(olwen, game.ann, "i couldn't join your party. try again in a bit.");
    }

    @Test
    void anOwnerOnAnotherChannelWhenItArrivesHasItComeInOnTheirs() {
        game.roster().call(game.ann, olwen);
        game.companionOnChannel(olwen, 1);
        game.annOnChannel(2); // the owner changed channel on the way

        game.runTimers();

        assertEquals(List.of("spawn Olwen at " + LITH + " " + portalX(LITH) + ",200", "join Olwen", "leave Olwen",
                "remove Olwen", "spawn Olwen at " + HENESYS_FIELD + " " + portalX(HENESYS_FIELD) + ",200", "join Olwen",
                "follow Olwen"), game.calls);
        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
    }

    @Test
    void itTurnsBackWithReturnWhenRoomWhenItsPartyWasGoneAndNoSeatIsLeft() {
        game.roster().call(game.ann, olwen);
        game.members.remove((Integer) 900); // the party it joined broke up on the way (a merge)
        for (int bot = 1; bot <= 5; bot++) {
            game.members.add(500 + bot);
        }

        game.runTimers();

        assertEquals(RosterState.BENCHED, game.row(olwen).state());
        assertTrue(game.slots.waitingForRoom("olwen"));
        assertTrue(game.calls.contains("remove Olwen"));
        verify(game.residents).whisper(olwen, game.ann, "your party's full. i'll head back to camp.");
    }

    @Test
    void aWhistleStillSeatsItAtOnceAtThePortal() {
        game.config.RECALL_WHISTLE_ITEM_ID = 2030000;
        Inventory use = mock(Inventory.class);
        when(use.countById(2030000)).thenReturn(1);
        when(game.ann.getInventory(InventoryType.USE)).thenReturn(use);

        RosterResult result;
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            result = game.roster().call(game.ann, olwen);
            inventory.verify(() -> InventoryManipulator.removeById(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()));
        }

        assertEquals("Olwen joins your party.", RosterVoice.line(olwen, result));
        assertEquals(List.of("spawn Olwen at " + HENESYS_FIELD + " " + portalX(HENESYS_FIELD) + ",200", "join Olwen",
                "follow Olwen"), game.calls);
        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
    }

    @Test
    void aTravellerIsNotCalledTwice() {
        game.roster().call(game.ann, olwen);

        RosterResult again = game.roster().call(game.ann, olwen);

        assertTrue(RosterVoice.line(olwen, again).startsWith("Olwen is already on the way (~"));
        assertEquals(1, game.calls.stream().filter(call -> call.startsWith("spawn")).count());
        verify(game.residents, never()).whisper(olwen, game.ann, "give me a moment. i'm in the middle of something.");
    }

    @Test
    void aCampOnTheSameMapIsNoTrip() {
        game.moveAnn(LITH, 1000, 200);

        RosterResult result = game.roster().call(game.ann, olwen);

        assertEquals("Olwen joins your party.", RosterVoice.line(olwen, result));
        assertEquals(List.of("spawn Olwen at " + LITH + " " + portalX(LITH) + ",200", "join Olwen", "follow Olwen"),
                game.calls);
        assertEquals(new Point(portalX(LITH), 200), game.bot(olwen).getPosition());
    }
}
