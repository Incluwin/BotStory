package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.roster.CompanionPartySync.Reason;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Make-room (docs/BOT_MANAGEMENT_PLAN.md, "Make-room"; P0 probe 12: a party at 6/6 cannot invite, so it happens
 * when a friend accepts into it, or through the Crew window's "Make room for a friend..."). Guests step out first,
 * then the last of the usual lineup, in character; fair share rebalances after the join.
 */
class MakeRoomTest {
    private static final int ANN = 1;
    private static final int BEN = 2;
    private static final int MIRA = 950;

    private final FakeParties world = new FakeParties()
            .human(ANN).human(BEN)
            .companion("vex", 101, ANN, 0).companion("olwen", 102, ANN, 1).companion("brin", 103, ANN, 2)
            .companion("hadrik", 104, ANN, 3).companion("tobi", 105, ANN, 4)
            .citizen(MIRA);

    @Test
    void atSixOfSixTheLastOfTheLineupStepsOutForAFriendWhoAccepts() {
        int anns = out("vex", "olwen", "brin", "hadrik", "tobi");

        world.accept(BEN, anns);
        world.settle();

        assertEquals(List.of("MAKE_ROOM tobi", "join 2 " + anns, "OVERFLOW hadrik", "OVERFLOW brin"), world.changes);
        assertEquals(List.of(ANN, 101, 102, BEN), world.members(anns));
        assertEquals(0, world.waiting.get("tobi")); // back once Ann has room again
    }

    @Test
    void aGuestStepsOutBeforeAnyCompanion() {
        int anns = out("vex", "olwen", "brin", "hadrik");
        assertTrue(world.inviteCitizen(ANN, MIRA)); // 6/6 with her guest
        world.settle();
        world.changes.clear();

        world.accept(BEN, anns);
        world.settle();

        assertEquals(List.of("guest " + MIRA, "join 2 " + anns, "OVERFLOW hadrik", "OVERFLOW brin"), world.changes);
        assertFalse(world.guests.containsKey(MIRA));
        assertEquals(0, world.partyOf(MIRA));
    }

    @Test
    void nobodyStepsOutOfAPartyOfPeopleNobodyIsChargedFor() {
        int lobby = world.create(900); // a citizen-led lobby at 6/6
        for (int citizen = 901; citizen <= 905; citizen++) {
            assertTrue(world.joinParty(citizen, lobby));
        }
        world.settle();

        world.accept(BEN, lobby);
        world.settle();

        assertEquals(List.of("full " + BEN), world.refusals);
        assertEquals(6, world.members(lobby).size());
        assertEquals(List.of(), world.changes);
    }

    @Test
    void noMakeRoomInsideAnEventInstance() {
        int anns = out("vex", "olwen", "brin", "hadrik", "tobi");
        world.committed.add(105);

        world.accept(BEN, anns);
        world.settle();

        assertEquals(List.of("full " + BEN), world.refusals);
        assertEquals(RosterState.ACTIVE, world.companion("tobi").state);
    }

    @Test
    void theCrewWindowsMakeRoomBenchesOneSoAFriendCanBeInvited() {
        int anns = out("vex", "olwen", "brin", "hadrik", "tobi");

        assertTrue(world.sync.makeRoom(ANN, "hadrik"));
        world.settle();

        assertEquals(List.of("MAKE_ROOM hadrik"), world.changes);
        assertEquals(5, world.members(anns).size()); // the invite can go out now
        assertEquals(0, world.waiting.get("hadrik"));

        world.accept(BEN, anns); // a stock join now; its JOIN rebalances
        world.settle();

        assertEquals(List.of(ANN, 101, 102, BEN), world.members(anns));
        assertFalse(world.sync.makeRoom(ANN, "hadrik"), "benched already: nothing to do");
    }

    @Test
    void theCrewWindowsMakeRoomNeverStandsDownOneTiedToARun() {
        int anns = out("vex", "olwen", "brin", "hadrik", "tobi");
        world.committed.add(104); // Hadrik is in the instance

        assertFalse(world.sync.makeRoom(ANN, "hadrik"), "it waits for the run, as bench and swap do");
        world.committed.clear();
        world.committed.add(ANN); // Ann is in the run herself
        assertFalse(world.sync.makeRoom(ANN, "hadrik"));
        world.settle();

        assertEquals(List.of(), world.changes);
        assertEquals(RosterState.ACTIVE, world.companion("hadrik").state);
        assertEquals(6, world.members(anns).size());
    }

    @Test
    void theFriendLeavingBringsTheCrewBack() {
        int anns = out("vex", "olwen", "brin", "hadrik", "tobi");
        world.accept(BEN, anns);
        world.settle();
        world.changes.clear();

        world.quit(BEN);
        world.settle();

        assertEquals(List.of("recall brin", "recall hadrik", "recall tobi"), world.changes);
        assertEquals(6, world.members(anns).size());
    }

    // ---- in character, on the live roster ------------------------------------------------

    @Test
    void aCompanionSteppingOutForAFriendSaysSoAndWaitsForRoom() {
        LivingWorld living = mock(LivingWorld.class);
        RosterService roster = mock(RosterService.class);
        CompanionSlots slots = mock(CompanionSlots.class);
        LivingContent content = mock(LivingContent.class);
        Persona tobi = mock(Persona.class);
        Character ann = mock(Character.class);
        when(living.roster()).thenReturn(roster);
        when(living.slots()).thenReturn(slots);
        when(living.content()).thenReturn(content);
        when(content.persona("tobi")).thenReturn(tobi);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);

        new LiveCrew(living).standDown(ANN, "tobi", Reason.MAKE_ROOM, 77);
        new LiveCrew(living).standDown(ANN, "tobi", Reason.EXPELLED, 77);
        new LiveCrew(living).standDown(ANN, "tobi", Reason.BENCHED, 77);

        verify(roster).standDown(ann, tobi, "i'll step out for your friend.", "companion.make_room");
        verify(slots).waitForRoom("tobi", 0);
        verify(roster).standDown(ann, tobi, "the leader asked me to step out. i'll be at camp.", "companion.expel");
        verify(slots).waitForRoom("tobi", 77); // not back into the party whose leader expelled it
        verify(roster).standDown(ann, tobi, null, "companion.bench");
        verify(slots, times(2)).waitForRoom(eq("tobi"), anyInt()); // the owner's own bench does not come back by itself
    }

    @Test
    void companionsWithNoSeatLeftSayWhereTheyGoAndOnlyTheFirstOfABurstWhispers() {
        LivingWorld living = mock(LivingWorld.class);
        RosterService roster = mock(RosterService.class);
        CompanionSlots slots = mock(CompanionSlots.class);
        LivingContent content = mock(LivingContent.class);
        Persona brin = mock(Persona.class);
        Persona tobi = mock(Persona.class);
        Character ann = mock(Character.class);
        when(ann.getId()).thenReturn(ANN);
        when(living.roster()).thenReturn(roster);
        when(living.slots()).thenReturn(slots);
        when(living.content()).thenReturn(content);
        when(content.persona("brin")).thenReturn(brin);
        when(content.persona("tobi")).thenReturn(tobi);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        when(roster.entry(ANN, "brin")).thenReturn(active("brin"));
        when(roster.entry(ANN, "tobi")).thenReturn(active("tobi"));
        when(roster.overflowLine(brin, ANN)).thenReturn("party's full. i'll farm meso at camp till there's room.");
        when(roster.standDown(eq(ann), eq(tobi), anyString(), anyString()))
                .thenReturn(new RosterResult.Ok("Tobi heads back to camp to train."));
        LiveCrew crew = new LiveCrew(living);

        crew.standDown(ANN, "brin", Reason.OVERFLOW, 77); // a merge benches two of Ann's at once
        crew.standDown(ANN, "tobi", Reason.OVERFLOW, 77);

        verify(roster).standDown(ann, brin, "party's full. i'll farm meso at camp till there's room.", "companion.overflow");
        verify(roster).standDown(ann, tobi, "", "companion.overflow"); // no second whisper in the same breath
        verify(ann).dropMessage(5, "Tobi heads back to camp to train.");
        verify(slots).waitForRoom("brin", 0);
        verify(slots).waitForRoom("tobi", 0);
    }

    @Test
    void companionsALeaderCapPutsOutSaySoOnceABurst() {
        LivingWorld living = mock(LivingWorld.class);
        RosterService roster = mock(RosterService.class);
        CompanionSlots slots = mock(CompanionSlots.class);
        LivingContent content = mock(LivingContent.class);
        Persona brin = mock(Persona.class);
        Persona tobi = mock(Persona.class);
        Character ann = mock(Character.class);
        when(ann.getId()).thenReturn(ANN);
        when(living.roster()).thenReturn(roster);
        when(living.slots()).thenReturn(slots);
        when(living.content()).thenReturn(content);
        when(content.persona("brin")).thenReturn(brin);
        when(content.persona("tobi")).thenReturn(tobi);
        when(roster.onlineCharacter(ANN)).thenReturn(ann);
        when(roster.entry(ANN, "brin")).thenReturn(active("brin"));
        when(roster.entry(ANN, "tobi")).thenReturn(active("tobi"));
        when(slots.leaderCapped(ann)).thenReturn(true); // a regroup back into the party whose leader capped Ann
        when(roster.standDown(eq(ann), eq(tobi), anyString(), anyString()))
                .thenReturn(new RosterResult.Ok("Tobi heads back to camp to train."));
        LiveCrew crew = new LiveCrew(living);

        crew.standDown(ANN, "brin", Reason.OVERFLOW, 77);
        crew.standDown(ANN, "tobi", Reason.OVERFLOW, 77);

        verify(roster).standDown(ann, brin, RosterService.LEADER_CAP_LINE, "companion.overflow");
        verify(roster).standDown(ann, tobi, "", "companion.overflow"); // the leader's doing is said once
        verify(ann).dropMessage(5, "Tobi heads back to camp to train.");
        verify(roster, never()).overflowLine(any(), anyInt());
    }

    private static LivingStore.RosterEntry active(String key) {
        return new LivingStore.RosterEntry(ANN, key, RosterState.ACTIVE, LivingStore.Assignment.FARM_MESO, 0, 0,
                104000000, 0L, 0L, 0L, 1_000L, 1_000L, 1_000L, 0, null);
    }

    /** Ann's own party with these companions out, settled; its id. */
    private int out(String... keys) {
        world.create(ANN);
        for (String key : keys) {
            assertTrue(world.call(ANN, key), key);
        }
        world.settle();
        world.changes.clear();
        return world.partyOf(ANN);
    }
}
