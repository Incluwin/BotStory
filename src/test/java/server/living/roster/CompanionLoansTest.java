package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import net.server.world.PartyEvents.PartyChangeEvent;
import net.server.world.PartyEvents.Snapshot;
import net.server.world.PartyOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionLeases.Kind;
import server.living.roster.CompanionLeases.Lease;
import server.living.roster.CompanionLoans.End;
import server.living.roster.CompanionLoans.Loan;
import server.living.roster.CompanionLoans.Order;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lending a companion to a friend (docs/COMPANION_LENDING_PLAN.md, section 3), over a fake game: who may lend to whom,
 * the friend's Yes and its rechecks, the hand-over and its rollback at each step, every way a loan ends, and the sweep.
 * The leases are real, so a test sees exactly who holds the companion at each step.
 */
class CompanionLoansTest {
    private static final int ALICE = 10;   // the owner
    private static final int BOB = 20;     // Alice's mutual buddy
    private static final int CARL = 30;    // a stranger on the same map
    private static final int OLWEN = 900;  // Alice's companion
    private static final int ALICE_PARTY = 1;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingStore store = mock(LivingStore.class);
    private final LivingContent content = mock(LivingContent.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final RosterService roster = mock(RosterService.class);
    private final CompanionSlots slots = mock(CompanionSlots.class);
    private final CompanionBudget budget = mock(CompanionBudget.class);
    private final CompanionExecutor executor = mock(CompanionExecutor.class);
    private final CompanionLeases leases = new CompanionLeases();
    private final Persona persona = mock(Persona.class);
    private final Character alice = character(ALICE, "Alice");
    private final Character bob = character(BOB, "Bob");
    private final Character carl = character(CARL, "Carl");
    private final Character olwen = character(OLWEN, "Olwen");
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final FakeHands hands = new FakeHands();
    private RosterState rowState = RosterState.ACTIVE;
    private CompanionLoans loans;

    @BeforeEach
    void wireWorld() {
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(store);
        when(world.content()).thenReturn(content);
        when(world.residents()).thenReturn(residents);
        when(world.roster()).thenReturn(roster);
        when(world.slots()).thenReturn(slots);
        when(world.budget()).thenReturn(budget);
        when(world.executor()).thenReturn(executor);
        when(world.leases()).thenReturn(leases);
        when(executor.submit(anyString(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return CompletableFuture.completedFuture(null);
        });
        when(persona.id()).thenReturn("olwen");
        when(persona.name()).thenReturn("Olwen");
        when(content.persona("olwen")).thenReturn(persona);
        when(residents.characterIdOf("olwen")).thenReturn(OLWEN);
        when(residents.personaOfCharacterId(OLWEN)).thenReturn(persona);
        when(roster.cachedOwnerOf("olwen")).thenReturn(ALICE);
        when(store.rosterEntry(ALICE, "olwen")).thenAnswer(inv -> row(rowState));
        when(roster.lendOut(alice, persona)).thenAnswer(inv -> {
            rowState = RosterState.BENCHED;
            return true;
        });
        when(roster.backFromLoan(eq(alice), eq(persona), any())).thenAnswer(inv -> {
            rowState = RosterState.ACTIVE;
            return true;
        });
        when(roster.canSeat(eq(alice), any())).thenReturn(true);
        when(slots.free(any())).thenReturn(4);
        when(slots.reserve(anyInt(), anyString(), any(), anyLong())).thenReturn(true);
        when(budget.admitInPlace(any(), any())).thenReturn(new CompanionBudget.Admission(true, null));
        AgentPresence.install(chr -> chr.getId() == OLWEN);

        for (Character player : List.of(alice, bob, carl)) {
            hands.online.put(player.getId(), player);
            hands.here.add(player.getId());
        }
        hands.loaded.put(OLWEN, olwen);
        hands.here.add(OLWEN);
        hands.leaders.put(OLWEN, ALICE);
        hands.parties.put(ALICE, ALICE_PARTY);
        hands.parties.put(OLWEN, ALICE_PARTY);
        hands.buddies.add(Set.of(ALICE, BOB));
        leases.tryAcquire(OLWEN, Kind.COMPANION, ALICE);
        loans = new CompanionLoans(world, hands, now::get);
    }

    @AfterEach
    void restore() {
        AgentPresence.install(null);
    }

    // ---- offers -------------------------------------------------------------------------------

    @Test
    void theFriendsYesHandsTheCompanionOverWhereItStands() {
        assertTrue(loans.offer(alice, persona, BOB, 30).ok());
        assertEquals(1, hands.asked.size());
        assertFalse(loans.onLoan(OLWEN), "offered, not out yet");

        hands.asked.get(0).yes.run();

        assertTrue(loans.onLoan(OLWEN));
        assertEquals(new Lease(Kind.LOAN, BOB), leases.holder(OLWEN));
        assertEquals(BOB, hands.leaders.get(OLWEN));
        assertEquals(hands.parties.get(BOB), hands.parties.get(OLWEN));
        assertTrue(hands.parties.get(BOB) != ALICE_PARTY, "it left Alice's party for one of Bob's");
        assertEquals(RosterState.BENCHED, rowState);
        verify(slots).reserve(eq(BOB), eq("olwen"), eq(CompanionSlots.Kind.GUEST), anyLong());
        assertEquals(List.of(), hands.unloaded, "the one loaded character throughout: nothing is copied or reloaded");
        Loan loan = loans.loanOf(OLWEN);
        assertEquals(now.get() + 30 * 60_000L, loan.endsAtMs());
        assertEquals(List.of(loan), loans.borrowedBy(BOB));
        assertEquals(List.of(loan), loans.lentBy(ALICE));
    }

    @Test
    void onlyAFriendStandingHereWithRoomMayBeOffered() {
        assertEquals("Carl is not on your buddy list or in your guild.", failed(loans.offer(alice, persona, CARL, 30)));
        hands.here.remove(BOB);
        assertEquals("They have to be here with you.", failed(loans.offer(alice, persona, BOB, 30)));
        hands.here.add(BOB);
        when(slots.free(bob)).thenReturn(0);
        assertEquals("Bob has no room in their party.", failed(loans.offer(alice, persona, BOB, 30)));
        when(slots.free(bob)).thenReturn(4);
        assertEquals("They have to be here with you.", failed(loans.offer(alice, persona, OLWEN, 30))); // an Agent
        assertEquals("They have to be here with you.", failed(loans.offer(alice, persona, ALICE, 30)));
        assertEquals("That is longer than a loan can be.", failed(loans.offer(alice, persona, BOB, 45)));
        assertTrue(hands.asked.isEmpty());

        when(alice.getGuildId()).thenReturn(7);
        when(carl.getGuildId()).thenReturn(7);
        assertEquals(List.of(new CompanionLoans.Friend(BOB, "Bob", "buddy"), new CompanionLoans.Friend(CARL, "Carl", "guild")),
                loans.friendsFor(alice, List.of(bob, carl, olwen, alice)));
    }

    @Test
    void onlyACompanionOutWithItsOwnerMayBeLent() {
        hands.leaders.put(OLWEN, BOB);
        assertEquals("Olwen has to be out here with you first.", failed(loans.offer(alice, persona, BOB, 30)));
        hands.leaders.put(OLWEN, ALICE);
        assertEquals("Olwen is not in your roster.", failed(loans.offer(bob, persona, ALICE, 30)));
        rowState = RosterState.BENCHED;
        assertEquals("Olwen has to be out here with you first.", failed(loans.offer(alice, persona, BOB, 30)));
        rowState = RosterState.ACTIVE;
        config.COMPANION_LENDING_ENABLED = false;
        assertEquals("Lending is switched off.", failed(loans.offer(alice, persona, BOB, 30)));
        config.COMPANION_LENDING_ENABLED = true;
        config.COMPANION_LOAN_MAX_MINUTES = 60;
        assertEquals(List.of(30, 60), loans.lengths());
        assertEquals("That is longer than a loan can be.", failed(loans.offer(alice, persona, BOB, 120)));
    }

    @Test
    void anOfferIsHeldOnceAndTheFriendHasOneAtATime() {
        assertTrue(loans.offer(alice, persona, BOB, 30).ok());

        assertEquals("Olwen is already spoken for.", failed(loans.offer(alice, persona, BOB, 30)));
        assertEquals("Bob already has someone along.", loans.refusalToBorrow(alice, bob));
    }

    @Test
    void aWindowThatCannotOpenHoldsNoOffer() {
        hands.askShown = false;

        assertEquals("Bob looks busy right now.", failed(loans.offer(alice, persona, BOB, 30)));
        assertNull(loans.loanOf(OLWEN));
    }

    @Test
    void noOrNoAnswerLeavesEverythingAsItWas() {
        loans.offer(alice, persona, BOB, 30);
        hands.asked.get(0).no.run();
        assertNull(loans.loanOf(OLWEN));
        assertEquals(new Lease(Kind.COMPANION, ALICE), leases.holder(OLWEN));
        verify(residents).whisper(eq(persona), eq(alice), anyString());

        loans.offer(alice, persona, BOB, 30);
        now.addAndGet(config.COMPANION_LOAN_OFFER_MS);
        loans.sweep();
        assertNull(loans.loanOf(OLWEN), "lapsed");
        hands.asked.get(1).yes.run(); // a late Yes finds nothing to accept
        assertFalse(loans.onLoan(OLWEN));
        assertEquals(ALICE, hands.leaders.get(OLWEN));
    }

    @Test
    void onlyTheNamedFriendsAnswerCounts() {
        loans.offer(alice, persona, BOB, 30);
        long id = loans.loanOf(OLWEN).id();

        loans.answered(id, CARL, true);

        assertFalse(loans.onLoan(OLWEN));
        assertNotNull(loans.loanOf(OLWEN), "still waiting for Bob");
        assertEquals(new Lease(Kind.COMPANION, ALICE), leases.holder(OLWEN));
    }

    @Test
    void theYesIsCheckedAgain() {
        loans.offer(alice, persona, BOB, 30);
        hands.here.remove(BOB); // Bob walked off before answering

        hands.asked.get(0).yes.run();

        assertFalse(loans.onLoan(OLWEN));
        assertEquals(new Lease(Kind.COMPANION, ALICE), leases.holder(OLWEN));
        assertEquals(ALICE, hands.leaders.get(OLWEN));
        assertEquals(RosterState.ACTIVE, rowState);
    }

    // ---- the hand-over's rollback -------------------------------------------------------------

    @Test
    void aFailedPartyJoinPutsEverythingBack() {
        hands.joinsFail.add(BOB);
        loans.offer(alice, persona, BOB, 30);

        hands.asked.get(0).yes.run();

        assertNoLoan();
        verify(roster).backFromLoan(eq(alice), eq(persona), eq(olwen));
        verify(slots).release("olwen", CompanionSlots.Kind.GUEST);
    }

    @Test
    void aFailedLeaderChangePutsEverythingBack() {
        hands.handOversFail = true;
        loans.offer(alice, persona, BOB, 30);

        hands.asked.get(0).yes.run();

        assertNoLoan();
        verify(roster).backFromLoan(eq(alice), eq(persona), eq(olwen));
    }

    @Test
    void aRowThatWillNotMovePutsEverythingBack() {
        org.mockito.Mockito.doReturn(false).when(roster).lendOut(alice, persona); // not through the stubbed answer
        loans.offer(alice, persona, BOB, 30);

        hands.asked.get(0).yes.run();

        assertNoLoan();
        assertEquals(ALICE_PARTY, hands.parties.get(OLWEN), "back in Alice's party");
        verify(roster, never()).backFromLoan(any(), any(), any());
    }

    @Test
    void aLeaseSomeoneElseHoldsStopsTheHandOver() {
        loans.offer(alice, persona, BOB, 30);
        leases.release(OLWEN, Kind.COMPANION);
        leases.tryAcquire(OLWEN, Kind.BENCH_LIVE, ALICE);

        hands.asked.get(0).yes.run();

        assertFalse(loans.onLoan(OLWEN));
        assertEquals(new Lease(Kind.BENCH_LIVE, ALICE), leases.holder(OLWEN));
        assertEquals(ALICE, hands.leaders.get(OLWEN));
    }

    // ---- the friend's card ----------------------------------------------------------------------

    @Test
    void onlyTheFriendDirectsItAndSendsItBack() {
        lend();

        assertTrue(loans.mayDirect(bob, OLWEN));
        assertFalse(loans.mayDirect(alice, OLWEN));
        assertFalse(loans.mayDirect(carl, OLWEN));
        assertFalse(loans.direct(alice, persona, Order.WAIT).ok());
        assertFalse(loans.direct(carl, persona, Order.WAIT).ok());
        assertFalse(loans.sendBack(alice, persona).ok());
        assertFalse(loans.sendBack(carl, persona).ok());
        assertFalse(loans.reseat(carl, persona).ok());
        assertTrue(hands.orders.isEmpty());

        assertTrue(loans.direct(bob, persona, Order.WAIT).ok());
        assertEquals(List.of("WAIT for Bob"), hands.orders);
        assertTrue(loans.reseat(bob, persona).ok(), "with Bob already");
        assertTrue(loans.sendBack(bob, persona).ok());
        assertFalse(loans.onLoan(OLWEN));
    }

    @Test
    void noTradeOpensWithItWhileItIsOut() {
        lend();

        assertTrue(loans.blocksTrade(bob, olwen));
        assertTrue(loans.blocksTrade(olwen, alice));
        assertTrue(loans.blocksTrade(carl, olwen));
        assertFalse(loans.blocksTrade(alice, bob));
    }

    // ---- the way home ---------------------------------------------------------------------------

    @Test
    void calledBackWhileItsOwnerStandsHereItRejoinsInPlace() {
        lend();
        assertFalse(loans.recall(bob, persona).ok(), "only its owner calls it back");
        assertFalse(loans.recall(carl, persona).ok());

        assertTrue(loans.recall(alice, persona).ok());

        assertFalse(loans.onLoan(OLWEN));
        assertEquals(new Lease(Kind.COMPANION, ALICE), leases.holder(OLWEN));
        assertEquals(ALICE, hands.leaders.get(OLWEN));
        assertEquals(RosterState.ACTIVE, rowState);
        assertEquals(List.of(), hands.unloaded);
        verify(slots).release("olwen", CompanionSlots.Kind.GUEST);
    }

    @Test
    void endingAwayFromItsOwnerItWalksOutAndIsUnloadedAlreadyBenched() {
        lend();
        hands.here.remove(ALICE); // Alice went to another map

        loans.recall(alice, persona);

        assertWalkedHome();
    }

    @Test
    void itsFriendLoggingOffSendsItHome() {
        lend();
        hands.here.remove(ALICE);

        loans.loggedOut(BOB);

        assertWalkedHome();
    }

    @Test
    void itsOwnerLoggingOffSendsItHomeNotBackIntoTheirParty() {
        lend();

        loans.loggedOut(ALICE); // still on the map while the logout runs

        assertWalkedHome();
        verify(roster, never()).backFromLoan(any(), any(), any());
    }

    @Test
    void theFriendChangingChannelSendsItHome() {
        lend();
        hands.here.remove(ALICE);

        loans.borrowerLeaving(BOB);

        assertWalkedHome();
    }

    @Test
    void expelledByTheFriendItGoesHome() {
        lend();
        hands.here.remove(ALICE);
        int party = hands.parties.get(BOB);

        loans.onPartyChanged(new PartyChangeEvent(party, PartyOperation.EXPEL, OLWEN, BOB,
                new Snapshot(List.of(BOB, OLWEN), BOB), new Snapshot(List.of(BOB), BOB), null, true));

        assertWalkedHome();
    }

    @Test
    void theSweepEndsItWhenTheTimeIsUpAfterTwoWarnings() {
        lend();
        hands.here.remove(ALICE);
        now.addAndGet(25 * 60_000L);
        loans.sweep();
        assertEquals(1, loans.loanOf(OLWEN).warned());
        now.addAndGet(4 * 60_000L + 1);
        loans.sweep();
        loans.sweep();
        assertEquals(2, loans.loanOf(OLWEN).warned());
        verify(residents, times(2)).whisper(eq(persona), eq(bob), anyString());

        now.addAndGet(60_000L);
        loans.sweep();

        assertWalkedHome();
    }

    @Test
    void theSweepEndsItWhenTheSwitchGoesOffOrItsLeaderChanged() {
        lend();
        hands.here.remove(ALICE);
        config.COMPANION_LENDING_ENABLED = false;
        loans.sweep();
        assertWalkedHome();

        config.COMPANION_LENDING_ENABLED = true;
        hands.unloaded.clear();
        leases.release(OLWEN, Kind.LOAN);
        leases.tryAcquire(OLWEN, Kind.COMPANION, ALICE);
        hands.loaded.put(OLWEN, olwen);
        hands.leaders.put(OLWEN, ALICE);
        hands.parties.put(OLWEN, ALICE_PARTY);
        hands.here.add(ALICE);
        rowState = RosterState.ACTIVE;
        lend();
        hands.leaders.put(OLWEN, CARL); // something re-pointed its leader
        loans.sweep();
        assertNull(loans.loanOf(OLWEN));
    }

    @Test
    void aCompanionNoLongerLoadedEndsItsLoanAndFreesItsLease() {
        lend();
        hands.loaded.remove(OLWEN);

        loans.sweep();

        assertNull(loans.loanOf(OLWEN));
        assertNull(leases.holder(OLWEN));
        verify(roster).loanEnded(ALICE, "olwen");
    }

    @Test
    void aLoanEndsOnce() {
        lend();
        hands.here.remove(ALICE);
        Loan loan = loans.loanOf(OLWEN);

        loans.end(loan, End.RECALLED);
        loans.end(loan, End.TIME_UP);

        assertEquals(List.of(OLWEN), hands.unloaded);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void lend() {
        assertTrue(loans.offer(alice, persona, BOB, 30).ok());
        hands.asked.get(hands.asked.size() - 1).yes.run();
        assertTrue(loans.onLoan(OLWEN));
    }

    private void assertNoLoan() {
        assertFalse(loans.onLoan(OLWEN));
        assertNull(loans.loanOf(OLWEN));
        assertEquals(new Lease(Kind.COMPANION, ALICE), leases.holder(OLWEN));
        assertEquals(ALICE, hands.leaders.get(OLWEN));
    }

    private void assertWalkedHome() {
        assertFalse(loans.onLoan(OLWEN));
        assertEquals(List.of(OLWEN), hands.unloaded);
        assertNull(leases.holder(OLWEN), "its LOAN lease goes once it is off the server");
        assertEquals(RosterState.BENCHED, rowState);
        assertEquals(0, hands.parties.getOrDefault(OLWEN, 0));
        verify(roster).loanEnded(ALICE, "olwen");
    }

    private static String failed(RosterResult result) {
        assertFalse(result.ok(), result::toString);
        return ((RosterResult.Failed) result).reason();
    }

    private static RosterEntry row(RosterState state) {
        return new RosterEntry(ALICE, "olwen", state, Assignment.TRAIN, 0, 0, 104000000, 0L, 0L, 0L, 1_000L, 1_000L,
                1_000L, 0, null);
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        return character;
    }

    /** The friend's window: what it would do on Yes and on No. */
    private record Asked(Character friend, Runnable yes, Runnable no) { }

    /** A game of one map: who is online and here, who leads whom, who is in which party. */
    private static final class FakeHands implements CompanionLoans.Hands {
        final Map<Integer, Character> online = new HashMap<>();
        final Map<Integer, Character> loaded = new HashMap<>();
        final Set<Integer> here = new HashSet<>();
        final Map<Integer, Integer> leaders = new HashMap<>();
        final Map<Integer, Integer> parties = new HashMap<>();
        final Set<Set<Integer>> buddies = new HashSet<>();
        final Set<Integer> joinsFail = new HashSet<>();
        final List<Asked> asked = new ArrayList<>();
        final List<String> orders = new ArrayList<>();
        final List<Integer> unloaded = new ArrayList<>();
        boolean handOversFail;
        boolean askShown = true;
        int nextParty = 100;

        @Override
        public Character online(int characterId) {
            Character human = online.get(characterId);
            return human != null ? human : loaded.get(characterId);
        }

        @Override
        public Character loaded(int characterId) {
            return loaded.get(characterId);
        }

        @Override
        public int leaderOf(int agentCharacterId) {
            return loaded.containsKey(agentCharacterId) ? leaders.getOrDefault(agentCharacterId, 0) : 0;
        }

        @Override
        public boolean together(Character a, Character b) {
            return a != null && b != null && here.contains(a.getId()) && here.contains(b.getId());
        }

        @Override
        public boolean inEventInstance(Character chr) {
            return false;
        }

        @Override
        public boolean buddies(Character a, Character b) {
            return buddies.contains(Set.of(a.getId(), b.getId()));
        }

        @Override
        public int partyOf(Character chr) {
            return chr == null ? 0 : parties.getOrDefault(chr.getId(), 0);
        }

        @Override
        public void leaveParty(Character bot) {
            parties.remove(bot.getId());
        }

        @Override
        public boolean joinParty(Character leader, Character bot) {
            if (joinsFail.contains(leader.getId())) {
                return false;
            }
            int party = parties.computeIfAbsent(leader.getId(), id -> nextParty++);
            parties.put(bot.getId(), party);
            return true;
        }

        @Override
        public boolean handOver(Character bot, int fromLeaderId, Character to) {
            if (handOversFail || to == null || leaders.getOrDefault(bot.getId(), 0) != fromLeaderId) {
                return false;
            }
            leaders.put(bot.getId(), to.getId());
            return true;
        }

        @Override
        public void order(Character bot, Character leader, Order order) {
            orders.add(order + " for " + leader.getName());
        }

        @Override
        public void walkOut(Character bot, Runnable then) {
            then.run();
        }

        @Override
        public void unload(Character bot) {
            unloaded.add(bot.getId());
            loaded.remove(bot.getId());
            leaders.remove(bot.getId());
            parties.remove(bot.getId());
        }

        @Override
        public void cancelTrade(Character bot) {
        }

        @Override
        public boolean ask(Character friend, Persona persona, String text, Runnable yes, Runnable no) {
            if (askShown) {
                asked.add(new Asked(friend, yes, no));
            }
            return askShown;
        }

        @Override
        public void later(String name, Runnable task, long delayMs) {
            task.run();
        }
    }
}
