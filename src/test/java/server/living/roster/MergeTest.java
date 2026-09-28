package server.living.roster;

import net.server.world.PartyEvents.PartyChangeEvent;
import net.server.world.PartyOperation;
import org.junit.jupiter.api.Test;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merge (docs/BOT_MANAGEMENT_PLAN.md flow 5, P0 probe 1): an owner alone with their companions accepts a
 * friend's invite, leaves their own party under a cause token, joins the friend's, and the crew follows up to the
 * owner's fair share there; the rest stand down with returnWhenRoom. The op 3 and op 4 routing that leads here is in
 * PartyInviteRoutingTest.
 */
class MergeTest {
    private static final int ANN = 1;
    private static final int BEN = 2;
    private static final int CARL = 3;

    private final FakeParties world = new FakeParties()
            .human(ANN).human(BEN).human(CARL)
            .companion("vex", 101, ANN, 0).companion("olwen", 102, ANN, 1).companion("brin", 103, ANN, 2)
            .companion("hadrik", 104, ANN, 3).companion("tobi", 105, ANN, 4)
            .companion("kai", 201, BEN, 0).companion("lune", 202, BEN, 1).companion("sol", 203, BEN, 2)
            .companion("pip", 204, BEN, 3)
            .companion("ash", 301, CARL, 0).companion("elm", 302, CARL, 1)
            .citizen(950);

    @Test
    void twoFriendsOneParty() {
        int anns = out(ANN, "vex", "olwen", "brin", "hadrik", "tobi"); // 6/6
        int bens = out(BEN, "kai", "lune");

        world.accept(ANN, bens);
        world.settle();

        assertFalse(world.parties.containsKey(anns));
        // fair share with 2 humans: 2 seats each; Ben keeps his, Ann's first 2 in usual order follow her
        assertEquals(List.of(BEN, 201, 202, ANN, 101, 102), world.members(bens));
        for (String key : List.of("brin", "hadrik", "tobi")) {
            assertEquals(RosterState.BENCHED, world.companion(key).state, key);
            assertEquals(0, world.waiting.get(key), key); // returnWhenRoom
        }
        assertEquals(List.of("leave 1", "join 1 " + bens, "seat vex", "seat olwen",
                "OVERFLOW brin", "OVERFLOW hadrik", "OVERFLOW tobi"), world.changes);
        assertEquals(List.of(), world.drifts);
    }

    @Test
    void herGuestStepsOutBeforeTheMergeSoItsSeatIsNotHeldAgainstHerShare() {
        int anns = out(ANN, "vex", "olwen", "brin", "hadrik");
        assertTrue(world.inviteCitizen(ANN, 950)); // 6/6: Ann, four of hers and her guest
        world.settle();
        int bens = out(BEN, "kai", "lune");

        world.accept(ANN, bens);
        world.settle();

        assertFalse(world.parties.containsKey(anns));
        assertEquals(0, world.partyOf(950));
        assertEquals(List.of(CompanionPartySync.GUEST_LINE), world.guestLines);
        // her two seats go to Vex and Olwen, not to a seat still held for the guest: the party is full, not 5/6
        assertEquals(List.of(BEN, 201, 202, ANN, 101, 102), world.members(bens));
        assertEquals(List.of("guest 950", "leave 1", "join 1 " + bens, "seat vex", "seat olwen",
                "OVERFLOW brin", "OVERFLOW hadrik"), world.changes);
    }

    @Test
    void herOwnPartysDisbandIsAnEchoSoItsCrewIsNotReformedAroundHer() {
        int anns = out(ANN, "vex", "olwen");
        int bens = out(BEN, "kai");

        world.accept(ANN, bens);

        PartyChangeEvent disband = world.published.stream()
                .filter(event -> event.op() == PartyOperation.DISBAND && event.partyId() == anns).findFirst().orElseThrow();
        assertEquals(CompanionPartySync.CAUSE, disband.cause());
        assertEquals(ANN, disband.actorId());
        world.settle();
        assertEquals(List.of(BEN, 201, ANN, 101, 102), world.members(bens));
        assertEquals(0, world.loads - 3, "nobody was loaded again: the crew walked over as it was");
    }

    @Test
    void theCrewFollowsTheUsualLineupNotTheOrderItJoinedIn() {
        int bens = out(BEN, "kai", "lune");
        out(ANN, "tobi", "hadrik", "vex"); // joined last-first; the usual lineup puts Vex first

        world.accept(ANN, bens);
        world.settle();

        assertEquals(List.of(BEN, 201, 202, ANN, 101, 104), world.members(bens));
        assertEquals(RosterState.BENCHED, world.companion("tobi").state);
    }

    @Test
    void theFriendsCrewPastItsNewShareStepsOutForHer() {
        int bens = out(BEN, "kai", "lune", "sol", "pip"); // 5/6
        out(ANN, "vex");

        world.accept(ANN, bens);
        world.settle();

        // Ann's join leaves 6/6; fair share then benches two of Ben's and Vex takes one of those seats
        assertEquals(List.of(BEN, 201, 202, ANN, 101), world.members(bens));
        assertEquals(RosterState.BENCHED, world.companion("sol").state);
        assertEquals(RosterState.BENCHED, world.companion("pip").state);
    }

    @Test
    void aFriendsPartyThatFilledUpMeanwhileMakesRoomFirst() {
        int bens = out(BEN, "kai", "lune");
        world.accept(CARL, bens);
        world.settle();
        assertTrue(world.call(CARL, "ash"));
        assertTrue(world.call(CARL, "elm")); // 6/6 by the time Ann accepts
        world.settle();
        out(ANN, "vex", "olwen");

        world.accept(ANN, bens);
        world.settle();

        // make-room with Ann counted in (3 humans, 1 seat each): the last one past its share steps out first,
        // then fair share after her join, then her own crew up to her one seat
        assertEquals(List.of("MAKE_ROOM elm", "leave 1", "join 1 " + bens, "OVERFLOW lune", "seat vex",
                "OVERFLOW olwen"), world.changes);
        assertEquals(List.of(BEN, 201, CARL, 301, ANN, 101), world.members(bens));
        assertEquals(0, world.waiting.get("elm"));
    }

    @Test
    void aFriendWhoLeftAndIsInvitedBackGetsTheirWholeShareAgain() {
        // the P2a live run: Ann led one party throughout; Ben merged in, she expelled his companion, he left with his
        // crew, called a second one, and merged back into the same party
        int anns = out(ANN, "vex", "olwen", "brin", "hadrik"); // 5/6
        out(BEN, "kai");
        world.accept(BEN, anns);
        world.settle();
        world.expel(ANN, 201);
        world.settle();
        world.quit(BEN);
        world.settle();
        assertTrue(world.call(BEN, "lune"));
        world.settle();
        assertEquals(List.of(BEN, 201, 202), world.members(world.partyOf(BEN)));
        world.changes.clear();

        world.accept(BEN, anns);
        world.settle();

        // her overflow steps out, his crew fills his share: nobody walks out while his seats are free
        assertEquals(List.of(ANN, 101, 102, BEN, 201, 202), world.members(anns));
        assertEquals(RosterState.ACTIVE, world.companion("lune").state);
        assertEquals(List.of("leave 2", "join 2 " + anns, "OVERFLOW hadrik", "OVERFLOW brin", "seat kai", "seat lune"),
                world.changes);
    }

    @Test
    void aSharedPartyIsNotMergedAndKeepsTheStockRefusal() {
        int anns = out(ANN, "vex");
        world.accept(CARL, anns); // Ann's party now has another human: it is not hers alone
        world.settle();
        int bens = out(BEN, "kai");

        world.accept(ANN, bens);
        world.settle();

        assertEquals(List.of("already in a party " + ANN), world.refusals);
        assertEquals(anns, world.partyOf(ANN));
        assertEquals(List.of(BEN, 201), world.members(bens));
    }

    @Test
    void noMergeWhileHerPartyIsInAnEventInstance() {
        int anns = out(ANN, "vex");
        int bens = out(BEN, "kai");
        world.committed.add(101);

        world.sync.accept(ANN, bens);

        assertEquals(List.of("already in a party " + ANN), world.refusals);
        assertEquals(List.of(ANN, 101), world.members(anns));
    }

    @Test
    void aPartyGoneBeforeTheMergeRunsLeavesHerCrewAsItWas() {
        int anns = out(ANN, "vex");
        int bens = out(BEN, "kai");
        world.quit(BEN); // disbanded between the accept and companion-ops
        world.settle();
        world.changes.clear();

        world.sync.accept(ANN, bens);

        assertEquals(List.of(), world.changes);
        assertEquals(List.of(ANN, 101), world.members(anns));
    }

    @Test
    void whenSheLeavesHerCrewReformsAroundHerAndTheWaitingOnesComeBack() {
        out(ANN, "vex", "olwen", "brin", "hadrik", "tobi");
        int bens = out(BEN, "kai", "lune");
        world.accept(ANN, bens);
        world.settle();
        world.changes.clear();

        world.quit(ANN);
        world.settle();

        int anns = world.partyOf(ANN);
        assertEquals(List.of(ANN, 101, 102, 103, 104, 105), world.members(anns));
        assertEquals(List.of("seat vex", "seat olwen", "recall brin", "recall hadrik", "recall tobi"), world.changes);
        assertEquals(List.of(BEN, 201, 202), world.members(bens));
        assertTrue(world.waiting.isEmpty());
    }

    @Test
    void anOwnerWithoutCompanionsOutJustJoins() {
        world.create(ANN);
        int bens = out(BEN, "kai");

        world.accept(ANN, bens);
        world.settle();

        assertEquals(List.of(BEN, 201, ANN), world.members(bens));
        assertEquals(0, world.doubleLoads);
    }

    /** The owner's own party with these companions out, settled; its id. */
    private int out(int owner, String... keys) {
        if (world.partyOf(owner) == 0) {
            world.create(owner);
        }
        for (String key : keys) {
            assertTrue(world.call(owner, key), key);
        }
        world.settle();
        world.changes.clear();
        return world.partyOf(owner);
    }
}
