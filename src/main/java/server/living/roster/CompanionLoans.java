package server.living.roster;

import client.Character;
import net.server.world.CauseScope;
import net.server.world.PartyEvents;
import net.server.world.PartyEvents.PartyChangeEvent;
import net.server.world.PartyOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Lending a companion to a friend (docs/COMPANION_LENDING_PLAN.md, section 3). An owner lends a companion out with them
 * to a mutual buddy or guild mate standing on their map; the friend says Yes in a stock window, and the companion is
 * handed over where it stands: its lease moves from (COMPANION, owner) to (LOAN, friend) in one step, it takes a GUEST
 * seat charged to the friend, its roster row goes ACTIVE to BENCHED (the row is all a restart sees: it is home), and
 * its Agent is re-registered under the friend with no despawn. It comes back when the time is up, when its owner calls
 * it back or the friend sends it back or expels it, when the friend's party has no seat for it, when the friend logs
 * off or changes channel, when its owner logs off, and when the switch goes off. Back with its owner on their map it
 * rejoins them in place; anywhere else it walks out and is unloaded, already benched.
 *
 * <p>Security (plan 3.3): the friend never gets {@code mayControl} or {@code mayLoad}. What they get is
 * {@link #mayDirect}, which only the borrowed card's field orders and the party router's re-seat ask. Their typed lines
 * never reach it (CompanionDirectory.mayCommandByChat), no trade opens with it (LivingWorldHooks.tradeAllowed), and
 * every owner-only surface stays keyed by the owner's own roster row. Nothing is copied: it is the one loaded character
 * throughout, and its bag and purse never move between the players.</p>
 *
 * <p>State changes run on companion-ops only; the lookups are safe from any thread (immutable loans in concurrent
 * maps). Loans live in memory: a restart ends them all, and the row says BENCHED.</p>
 */
public final class CompanionLoans {
    /** The cause token of the loans' own party changes: the sync takes them as echoes. */
    public static final String CAUSE = "companion.loan";
    /** How often loans are looked at for their end (time up, someone gone, the switch off). */
    public static final long SWEEP_MS = 10_000L;
    /** The lengths a card offers, in minutes, up to COMPANION_LOAN_MAX_MINUTES. */
    static final int[] LENGTHS_MINUTES = {30, 60, 120};
    /** Warnings before the time is up. */
    static final long WARN_FIRST_MS = 5 * 60_000L;
    static final long WARN_LAST_MS = 60_000L;
    /** A GUEST seat outlives its loan by this much; the sweep ends the loan long before. */
    static final long SEAT_SLACK_MS = 10 * 60_000L;
    /** One refusal line per friend and companion per this long (a whisper, a trade). */
    static final long REFUSAL_GAP_MS = 60_000L;
    /** A companion that had to step out tries to rejoin its friend this long after the party settled. */
    static final long REJOIN_DELAY_MS = 1_500L;
    /** How long after its friend went into an event instance a borrowed companion not taken along starts to wait. */
    static final long INSTANCE_SETTLE_MS = 2_000L;
    /** A companion waiting outside while its friend stands on an open map this long rejoins them (the run never began). */
    static final long STALE_WAIT_MS = 60_000L;
    private static final Logger log = LoggerFactory.getLogger(CompanionLoans.class);

    public enum Stage { OFFERED, OUT }

    /** Why a loan ended: what is logged, and how the companion words it. */
    public enum End { TIME_UP, RECALLED, SENT_BACK, EXPELLED, NO_SEAT, BORROWER_AWAY, OWNER_AWAY, RELEASED, SWITCHED_OFF, GONE }

    /** A field order the friend gives from the borrowed card. */
    public enum Order { FOLLOW, WAIT, HUNT }

    /**
     * One loan. {@code endsAtMs} is set once it is OUT (0 while OFFERED, when {@code offeredAtMs} counts);
     * {@code waitingSinceMs}: since when it waits out of the party, off follow, because the friend went into a run
     * without it (D2 b), 0 when it does not; {@code warned}: how many of the two warnings it gave.
     */
    public record Loan(long id, int ownerId, int borrowerId, String personaId, int characterId, int minutes,
                       long offeredAtMs, long endsAtMs, Stage stage, long waitingSinceMs, int warned) {
        public boolean out() {
            return stage == Stage.OUT;
        }

        public boolean waitingOutside() {
            return waitingSinceMs != 0L;
        }

        public long msLeft(long nowMs) {
            return out() ? Math.max(0L, endsAtMs - nowMs) : 0L;
        }

        Loan started(long nowMs) {
            return new Loan(id, ownerId, borrowerId, personaId, characterId, minutes, offeredAtMs,
                    nowMs + minutes * 60_000L, Stage.OUT, 0L, 0);
        }

        /** Waiting outside since {@code sinceMs}; 0: back with its friend. */
        Loan waiting(long sinceMs) {
            return new Loan(id, ownerId, borrowerId, personaId, characterId, minutes, offeredAtMs, endsAtMs, stage,
                    sinceMs, warned);
        }

        Loan warnedTimes(int times) {
            return new Loan(id, ownerId, borrowerId, personaId, characterId, minutes, offeredAtMs, endsAtMs, stage,
                    waitingSinceMs, times);
        }
    }

    /** A friend the owner may lend to: mutual buddies ("buddy") or guild mates ("guild"). */
    public record Friend(int characterId, String name, String tie) { }

    /**
     * The game a loan acts on: the agent runtime, the party, the windows. The live one is {@link LiveLoanHands}; the
     * tests use fakes. Party changes run under the caller's cause token.
     */
    interface Hands {
        /** A logged-in human or Agent on this server, or null. */
        Character online(int characterId);

        /** The Agent's character while it is loaded, else null. */
        Character loaded(int characterId);

        /** Its runtime leader, 0 when it is not loaded. */
        int leaderOf(int agentCharacterId);

        /** True when both stand on the same map instance (so the same channel too). */
        boolean together(Character a, Character b);

        boolean inEventInstance(Character chr);

        /** Mutual buddies: each lists the other, accepted. */
        boolean buddies(Character a, Character b);

        /** The party a character is in, 0 for none. */
        int partyOf(Character chr);

        void leaveParty(Character bot);

        /** The bot joins the leader's party, made when the leader has none. */
        boolean joinParty(Character leader, Character bot);

        /** Re-registers the loaded Agent under {@code to} where it stands; it then follows them. */
        boolean handOver(Character bot, int fromLeaderId, Character to);

        void order(Character bot, Character leader, Order order);

        /** Walks it to the nearest portal and runs {@code then} once it is there (at once when it cannot walk). */
        void walkOut(Character bot, Runnable then);

        /** Saves it and takes it off the server as a player logging off. */
        void unload(Character bot);

        void cancelTrade(Character bot);

        /** The friend's Yes/No in the companion's voice; false (nothing shown) when a window may not open now. */
        boolean ask(Character friend, Persona persona, String text, Runnable yes, Runnable no);

        /** Runs {@code task} on companion-ops after {@code delayMs}. */
        void later(String name, Runnable task, long delayMs);
    }

    private final LivingWorld world;
    private final Hands hands;
    private final LongSupplier clock;
    private final Map<Integer, Loan> byCharacter = new ConcurrentHashMap<>();
    private final Map<String, Long> refusalsAt = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public CompanionLoans(LivingWorld world) {
        this.world = world;
        this.hands = new LiveLoanHands(world);
        this.clock = System::currentTimeMillis;
    }

    CompanionLoans(LivingWorld world, Hands hands, LongSupplier clock) {
        this.world = world;
        this.hands = hands;
        this.clock = clock;
    }

    // ---- lookups (any thread) --------------------------------------------------------------

    /** True while any loan is offered or out: the claim guard's and the hooks' fast path. */
    public boolean any() {
        return !byCharacter.isEmpty();
    }

    /** The character's loan, offered or out, or null. */
    public Loan loanOf(int characterId) {
        return byCharacter.get(characterId);
    }

    /** True while the character is out on loan. */
    public boolean onLoan(int characterId) {
        Loan loan = byCharacter.get(characterId);
        return loan != null && loan.out();
    }

    /** The persona's loan while it is out, else null. */
    public Loan lentOut(String personaId) {
        if (personaId == null || byCharacter.isEmpty()) {
            return null;
        }
        for (Loan loan : byCharacter.values()) {
            if (loan.out() && loan.personaId().equals(personaId)) {
                return loan;
            }
        }
        return null;
    }

    /** True while the persona is out on loan: its owner's row stays BENCHED and only the loan moves it. */
    public boolean lent(String personaId) {
        return lentOut(personaId) != null;
    }

    /**
     * {@link #lent} for the bench's readers (the ledger, live slices, rotation, errands, boss allies, the desks'
     * suggestions): a BENCHED row out on loan is not at camp, so none of them counts or calls it. False with no loans.
     */
    public static boolean lent(LivingWorld world, String personaId) {
        CompanionLoans loans = world == null ? null : world.loans();
        return loans != null && loans.any() && loans.lent(personaId);
    }

    /**
     * True only for the friend a companion is out on loan to: the borrowed card's orders and the party router's re-seat
     * ask this, nothing else does. Its owner, administrators and everyone else: false.
     */
    public boolean mayDirect(Character actor, int characterId) {
        Loan loan = byCharacter.get(characterId);
        return actor != null && loan != null && loan.out() && loan.borrowerId() == actor.getId();
    }

    /** The loans out to this friend. */
    public List<Loan> borrowedBy(int borrowerId) {
        List<Loan> out = new ArrayList<>();
        for (Loan loan : byCharacter.values()) {
            if (loan.out() && loan.borrowerId() == borrowerId) {
                out.add(loan);
            }
        }
        return out;
    }

    /** The owner's companions out on loan. */
    public List<Loan> lentBy(int ownerId) {
        List<Loan> out = new ArrayList<>();
        for (Loan loan : byCharacter.values()) {
            if (loan.out() && loan.ownerId() == ownerId) {
                out.add(loan);
            }
        }
        return out;
    }

    /** True while the companion waits outside a run its friend went into (the follow guard holds it). */
    public boolean waitingOutside(int characterId) {
        Loan loan = byCharacter.get(characterId);
        return loan != null && loan.out() && loan.waitingOutside();
    }

    /** "an hour", "30 minutes", "2 hours": a loan's length as the windows say it. */
    public static String describeLength(int minutes) {
        return LoanLines.length(minutes);
    }

    /** The lengths the card offers now. */
    public List<Integer> lengths() {
        List<Integer> out = new ArrayList<>();
        for (int minutes : LENGTHS_MINUTES) {
            if (minutes <= world.config().COMPANION_LOAN_MAX_MINUTES) {
                out.add(minutes);
            }
        }
        return out;
    }

    // ---- lending (companion-ops) --------------------------------------------------------------

    /** Null when the owner may lend this companion now (plan 3.1, the companion's half); else the line why not. */
    public String refusalToLend(Character owner, Persona persona) {
        if (!world.config().COMPANION_LENDING_ENABLED) {
            return "Lending is switched off.";
        }
        if (owner == null || persona == null || world.roster().cachedOwnerOf(persona.id()) != owner.getId()) {
            return persona == null ? "They are not in your roster." : persona.name() + " is not in your roster.";
        }
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        int characterId = world.residents().characterIdOf(persona.id());
        Character bot = characterId == 0 ? null : hands.loaded(characterId);
        if (entry == null || entry.state() != RosterState.ACTIVE || bot == null || !hands.together(owner, bot)
                || hands.leaderOf(characterId) != owner.getId() || hands.partyOf(owner) == 0
                || hands.partyOf(bot) != hands.partyOf(owner)) {
            return persona.name() + " has to be out here with you first.";
        }
        if (byCharacter.containsKey(characterId)) {
            return persona.name() + " is already spoken for.";
        }
        if (committed(characterId) || committed(owner.getId()) || hands.inEventInstance(owner)
                || server.agents.capabilities.follow.AgentFollowGuard.holdsOffFollow(characterId)) {
            return persona.name() + ": not right now.";
        }
        if (lentBy(owner.getId()).size() >= world.config().COMPANION_LOANS_PER_OWNER) {
            return "You have lent out as many as you can for now.";
        }
        return null;
    }

    /** Null when the friend may borrow from this owner now (plan 3.1, the friend's half); else the line why not. */
    String refusalToBorrow(Character owner, Character friend) {
        if (friend == null || friend.getId() == owner.getId()
                || server.integration.AgentPresence.isAgent(friend) || !hands.together(owner, friend)) {
            return "They have to be here with you.";
        }
        if (tie(owner, friend) == null) {
            return friend.getName() + " is not on your buddy list or in your guild.";
        }
        if (hands.inEventInstance(friend)) {
            return friend.getName() + " is busy right now.";
        }
        if (borrowedBy(friend.getId()).size() >= world.config().COMPANION_LOANS_PER_BORROWER
                || offeredTo(friend.getId())) {
            return friend.getName() + " already has someone along.";
        }
        if (world.slots().free(friend) <= 0) {
            return friend.getName() + " has no room in their party.";
        }
        return null;
    }

    /** The friends standing with the owner the companion could go with now, in the map's order. */
    public List<Friend> friendsFor(Character owner, List<Character> here) {
        List<Friend> out = new ArrayList<>();
        for (Character other : here) {
            String tie = refusalToBorrow(owner, other) == null ? tie(owner, other) : null;
            if (tie != null) {
                out.add(new Friend(other.getId(), other.getName(), tie));
            }
        }
        return out;
    }

    /** "buddy", "guild", or null when they are neither. */
    String tie(Character owner, Character friend) {
        if (hands.buddies(owner, friend)) {
            return "buddy";
        }
        return owner.getGuildId() > 0 && owner.getGuildId() == friend.getGuildId() ? "guild" : null;
    }

    /**
     * The owner's "Lend to a friend...": asks the friend in the companion's voice, and holds the offer until they
     * answer or it lapses. Ok when the friend is asked; the owner reads the line either way.
     */
    public RosterResult offer(Character owner, Persona persona, int friendId, int minutes) {
        String refusal = refusalToLend(owner, persona);
        Character friend = hands.online(friendId);
        if (refusal == null) {
            refusal = refusalToBorrow(owner, friend);
        }
        if (refusal == null && !lengths().contains(minutes)) {
            refusal = "That is longer than a loan can be.";
        }
        if (refusal != null) {
            return new RosterResult.Failed(refusal);
        }
        int characterId = world.residents().characterIdOf(persona.id());
        Loan offered = new Loan(ids.incrementAndGet(), owner.getId(), friend.getId(), persona.id(), characterId, minutes,
                clock.getAsLong(), 0L, Stage.OFFERED, 0L, 0);
        if (byCharacter.putIfAbsent(characterId, offered) != null) {
            return new RosterResult.Failed(persona.name() + " is already spoken for.");
        }
        String text = LoanLines.ask(owner.getName(), persona.name(), minutes);
        boolean shown = hands.ask(friend, persona, text,
                () -> world.executor().submit("loan.answer " + offered.id(), () -> answered(offered.id(), friendId, true)),
                () -> world.executor().submit("loan.answer " + offered.id(), () -> answered(offered.id(), friendId, false)));
        if (!shown) {
            byCharacter.remove(characterId, offered);
            return new RosterResult.Failed(friend.getName() + " looks busy right now.");
        }
        world.store().event(owner.getId(), persona.id(), "companion.loan_offered", friend.getName() + " " + minutes + " min");
        return new RosterResult.Ok("You ask " + friend.getName() + " if " + persona.name() + " may come along.");
    }

    /** The friend's answer to offer {@code loanId}, on companion-ops: Yes hands it over, if everything still holds. */
    void answered(long loanId, int friendId, boolean yes) {
        Loan offered = null;
        for (Loan loan : byCharacter.values()) {
            if (loan.id() == loanId) {
                offered = loan;
            }
        }
        if (offered == null || offered.out() || offered.borrowerId() != friendId) {
            return; // lapsed, or answered already
        }
        byCharacter.remove(offered.characterId(), offered);
        Character owner = hands.online(offered.ownerId());
        Character friend = hands.online(friendId);
        Persona persona = world.content().persona(offered.personaId());
        if (owner == null || persona == null) {
            return;
        }
        if (!yes) {
            world.residents().whisper(persona, owner, LoanLines.declined(friend == null ? "they" : friend.getName()));
            world.store().event(owner.getId(), persona.id(), "companion.loan_declined", friend == null ? "" : friend.getName());
            return;
        }
        String refusal = refusalToLend(owner, persona);
        if (refusal == null) {
            refusal = refusalToBorrow(owner, friend);
        }
        if (refusal != null) {
            world.residents().whisper(persona, owner, LoanLines.cannotGo());
            if (friend != null) {
                world.residents().whisper(persona, friend, LoanLines.cannotGo());
            }
            world.store().event(owner.getId(), persona.id(), "companion.loan_refused", refusal);
            return;
        }
        handOver(offered, owner, friend, persona);
    }

    /**
     * Plan 3.2, the hand-over, in one step on companion-ops. Any failure puts everything back: the lease, the seat, the
     * row (ACTIVE in the owner's party) and the Agent's leader.
     */
    private void handOver(Loan offered, Character owner, Character friend, Persona persona) {
        int characterId = offered.characterId();
        Character bot = hands.loaded(characterId);
        if (bot == null) {
            return;
        }
        CompanionBudget.Admission admission = world.budget().admitInPlace(CompanionBudget.Kind.COMPANION, friend);
        if (!admission.allowed()) {
            world.residents().whisper(persona, owner, LoanLines.tooCrowded());
            return;
        }
        CompanionLeases.Lease asCompanion = new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, owner.getId());
        CompanionLeases.Lease asLoan = new CompanionLeases.Lease(CompanionLeases.Kind.LOAN, friend.getId());
        if (!world.leases().transfer(characterId, asCompanion, asLoan)) {
            world.residents().whisper(persona, owner, LoanLines.cannotGo());
            log.warn("companion {} not lent to {}: its lease is {}", persona.id(), friend.getName(),
                    world.leases().holder(characterId));
            return;
        }
        long now = clock.getAsLong();
        Loan loan = offered.started(now);
        CompanionSlots.Reservation stale = world.slots().reservation(persona.id());
        if (stale != null && stale.ownerId() == owner.getId() && stale.kind() != CompanionSlots.Kind.GUEST) {
            world.slots().release(persona.id()); // a join's leftover seat of its owner's
        }
        if (!world.slots().reserve(friend.getId(), persona.id(), CompanionSlots.Kind.GUEST, loan.endsAtMs() + SEAT_SLACK_MS)) {
            world.leases().transfer(characterId, asLoan, asCompanion);
            world.residents().whisper(persona, owner, LoanLines.cannotGo());
            return;
        }
        byCharacter.put(characterId, loan); // lent from here on: the sync, the guards and the owner's verbs see it
        hands.cancelTrade(bot);
        boolean sameParty = hands.partyOf(friend) != 0 && hands.partyOf(friend) == hands.partyOf(owner);
        boolean moved = false;
        boolean lent = false;
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            if (!sameParty) {
                hands.leaveParty(bot);
            }
            lent = world.roster().lendOut(owner, persona);
            moved = lent && hands.handOver(bot, owner.getId(), friend);
            boolean joined = moved && (sameParty || hands.joinParty(friend, bot));
            if (!joined) {
                byCharacter.remove(characterId, loan);
                world.slots().release(persona.id(), CompanionSlots.Kind.GUEST);
                if (moved) {
                    hands.handOver(bot, friend.getId(), owner);
                }
                world.leases().transfer(characterId, asLoan, asCompanion);
                if (lent) {
                    world.roster().backFromLoan(owner, persona, bot);
                } else if (!sameParty) {
                    hands.joinParty(owner, bot);
                }
                world.residents().whisper(persona, owner, LoanLines.cannotGo());
                log.warn("companion {} not lent to {}: lent {}, moved {}", persona.id(), friend.getName(), lent, moved);
                return;
            }
        }
        world.store().event(owner.getId(), persona.id(), "companion.loan_out",
                friend.getName() + " for " + offered.minutes() + " min");
        world.residents().reply(persona, owner, LoanLines.goingWith(friend.getName(), offered.minutes()));
        world.roster().sayInParty(bot, LoanLines.greeting(friend.getName(), owner.getName()));
    }

    // ---- the friend's card (companion-ops) ---------------------------------------------------------

    /** A field order from the borrowed card: only the friend it is out on loan to gives one. */
    public RosterResult direct(Character friend, Persona persona, Order order) {
        Loan loan = persona == null ? null : lentOut(persona.id());
        if (loan == null || !mayDirect(friend, loan.characterId())) {
            return new RosterResult.Failed("That is not yours to ask.");
        }
        Character bot = hands.loaded(loan.characterId());
        if (bot == null || loan.waitingOutside() || committed(loan.characterId())) {
            return new RosterResult.Failed(persona.name() + ": not right now.");
        }
        hands.order(bot, friend, order);
        return new RosterResult.Ok(persona.name() + switch (order) {
            case FOLLOW -> " sticks with you.";
            case WAIT -> " waits here.";
            case HUNT -> " hunts here while you're busy.";
        });
    }

    /** "Head back to <owner>": the friend sends it home before its time. */
    public RosterResult sendBack(Character friend, Persona persona) {
        Loan loan = persona == null ? null : lentOut(persona.id());
        if (loan == null || !mayDirect(friend, loan.characterId())) {
            return new RosterResult.Failed("That is not yours to ask.");
        }
        end(loan, End.SENT_BACK);
        return new RosterResult.Ok(persona.name() + " heads back.");
    }

    /** The owner's "Call <name> back from <friend>". */
    public RosterResult recall(Character owner, Persona persona) {
        Loan loan = persona == null ? null : lentOut(persona.id());
        if (owner == null || loan == null || loan.ownerId() != owner.getId()) {
            return new RosterResult.Failed(persona == null ? "Nobody is out on loan." : persona.name() + " is not out on loan.");
        }
        end(loan, End.RECALLED);
        return new RosterResult.Ok(persona.name() + " is on the way back.");
    }

    // ---- reactions (companion-ops) --------------------------------------------------------------

    /**
     * A committed party change (after CompanionSlots and the sync took it). A borrowed companion the friend expelled, or
     * that left on its own, goes home; a party of the friend's that disbanded re-forms around the friend. The loans' and
     * the sync's own changes are echoes: a guest the sync stepped out came through {@link #guestLeft}.
     */
    public void onPartyChanged(PartyChangeEvent event) {
        if (byCharacter.isEmpty()) {
            return;
        }
        String cause = event.cause();
        if (cause != null && cause.startsWith(CompanionPartySync.CAUSE_PREFIX)) {
            return;
        }
        if (event.op() == PartyOperation.LEAVE || event.op() == PartyOperation.EXPEL) {
            Loan loan = byCharacter.get(event.targetId());
            if (loan != null && loan.out() && !loan.waitingOutside()) {
                end(loan, End.EXPELLED);
            }
        } else if (event.op() == PartyOperation.DISBAND) {
            for (int member : event.before().memberIds()) {
                Loan loan = byCharacter.get(member);
                if (loan != null && loan.out() && !loan.waitingOutside()) {
                    rejoinLater(loan);
                }
            }
        }
    }

    /**
     * The sync stepped a borrowed companion out of its friend's party (CompanionPartySync, through LiveCrew): fair share
     * after a human joined, make-room, a merge, its friend leaving or going offline. It leaves now, its GUEST seat goes
     * back, and once the change settles it rejoins its friend if there is room, else goes home.
     */
    public void guestLeft(int hostId, int guestId) {
        Loan loan = byCharacter.get(guestId);
        Character bot = hands.loaded(guestId);
        if (bot != null) {
            hands.leaveParty(bot);
        }
        world.slots().release(loan == null ? world.slots().keyOf(guestId) : loan.personaId(), CompanionSlots.Kind.GUEST);
        if (loan != null && loan.out()) {
            rejoinLater(loan);
        }
    }

    private void rejoinLater(Loan loan) {
        hands.later("loan.rejoin " + loan.personaId(), () -> rejoinOrReturn(loan.id()), REJOIN_DELAY_MS);
    }

    /** Rejoins its friend's party on a GUEST seat (one made when they have none); with no room, it goes home. */
    void rejoinOrReturn(long loanId) {
        Loan loan = byId(loanId);
        if (loan == null || !loan.out()) {
            return;
        }
        Character friend = hands.online(loan.borrowerId());
        Character bot = hands.loaded(loan.characterId());
        if (friend == null || bot == null) {
            end(loan, friend == null ? End.BORROWER_AWAY : End.GONE);
            return;
        }
        if (hands.partyOf(bot) != 0 && hands.partyOf(bot) == hands.partyOf(friend)) {
            return; // back with its friend already
        }
        if (hands.inEventInstance(friend)) {
            waitOutside(loan, bot, friend);
            return;
        }
        if (world.slots().free(friend) <= 0 || !world.slots().reserve(friend.getId(), loan.personaId(),
                CompanionSlots.Kind.GUEST, loan.endsAtMs() + SEAT_SLACK_MS)) {
            end(loan, End.NO_SEAT);
            return;
        }
        boolean joined;
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            if (hands.partyOf(bot) != 0) {
                hands.leaveParty(bot);
            }
            joined = hands.joinParty(friend, bot);
        }
        if (!joined) {
            end(loan, End.NO_SEAT);
            return;
        }
        byCharacter.replace(loan.characterId(), loan, loan.waiting(0L));
        hands.order(bot, friend, Order.FOLLOW);
    }

    /**
     * Its friend went into a run it is not part of (D2 b: a desk's Yes, or any event instance): it steps out of the
     * party and waits where it is, off follow, until its friend comes back out to an open map ({@link #enteredMap}).
     */
    void waitOutside(Loan loan, Character bot, Character friend) {
        if (loan.waitingOutside()) {
            return;
        }
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            if (hands.partyOf(bot) != 0) {
                hands.leaveParty(bot);
            }
        }
        world.slots().release(loan.personaId(), CompanionSlots.Kind.GUEST);
        byCharacter.replace(loan.characterId(), loan, loan.waiting(clock.getAsLong()));
        hands.order(bot, friend, Order.WAIT);
        Persona persona = world.content().persona(loan.personaId());
        if (persona != null) {
            world.residents().whisper(persona, friend, LoanLines.waitingOutside());
        }
    }

    /**
     * A party-quest desk's Yes (CompanionPartyQuestService.prepareEntry): a companion out on loan, an extra of the
     * lineup, steps out to wait outside for its friend, so the party is the lineup. False when it is not out on loan
     * (the desk holds it as its own).
     */
    public boolean waitOutsideForRun(int characterId) {
        Loan loan = byCharacter.get(characterId);
        if (loan == null || !loan.out()) {
            return false;
        }
        Character bot = hands.loaded(characterId);
        Character friend = hands.online(loan.borrowerId());
        if (bot == null || friend == null) {
            end(loan, friend == null ? End.BORROWER_AWAY : End.GONE);
            return true;
        }
        waitOutside(loan, bot, friend);
        return true;
    }

    /**
     * A friend (or anyone) came into a map, on the thread that moves them: into an event instance a borrowed companion
     * of theirs is not in, it waits outside; out to an open map, one waiting comes back (both on companion-ops).
     */
    public void enteredMap(Character chr, boolean eventInstance) {
        if (byCharacter.isEmpty() || chr == null) {
            return;
        }
        for (Loan loan : byCharacter.values()) {
            if (!loan.out() || loan.borrowerId() != chr.getId() || loan.waitingOutside() == eventInstance) {
                continue;
            }
            long id = loan.id();
            // into a run, a moment later: a companion the run takes along (a stock warp of the party) is moved by then
            hands.later("loan.entered " + loan.personaId(), () -> {
                Loan now = byId(id);
                Character friend = hands.online(chr.getId());
                Character bot = now == null ? null : hands.loaded(now.characterId());
                if (now == null || friend == null || bot == null) {
                    return;
                }
                if (hands.inEventInstance(friend)) {
                    if (!hands.together(friend, bot)) {
                        waitOutside(now, bot, friend);
                    }
                } else if (now.waitingOutside()) {
                    rejoinOrReturn(id);
                }
            }, eventInstance ? INSTANCE_SETTLE_MS : 0L);
        }
    }

    /** A player logged off: loans out to them, and loans of their companions, come home. */
    public void loggedOut(int characterId) {
        for (Loan loan : List.copyOf(byCharacter.values())) {
            if (loan.borrowerId() == characterId) {
                endOrDrop(loan, End.BORROWER_AWAY);
            } else if (loan.ownerId() == characterId) {
                endOrDrop(loan, End.OWNER_AWAY);
            }
        }
    }

    /** A friend changing channel or going into the Cash Shop: what they borrowed comes home (it cannot follow). */
    public void borrowerLeaving(int characterId) {
        for (Loan loan : List.copyOf(byCharacter.values())) {
            if (loan.borrowerId() == characterId) {
                endOrDrop(loan, End.BORROWER_AWAY);
            }
        }
    }

    /** A release or a deletion of the persona's owner (or of the friend): its loan ends first. */
    public void releasing(String personaId) {
        Loan loan = personaId == null ? null : lentOut(personaId);
        if (loan != null) {
            end(loan, End.RELEASED);
        }
    }

    /**
     * Every {@link #SWEEP_MS}: offers that lapsed go; loans whose time is up, whose friend or owner is gone, whose
     * Agent is no longer loaded, or all of them when the switch is off, come home; the friend hears the warnings.
     */
    public void sweep() {
        long now = clock.getAsLong();
        boolean off = !world.config().COMPANION_LENDING_ENABLED;
        for (Loan loan : List.copyOf(byCharacter.values())) {
            if (!loan.out()) {
                if (off || now - loan.offeredAtMs() >= world.config().COMPANION_LOAN_OFFER_MS) {
                    lapse(loan);
                }
                continue;
            }
            Character friend = hands.online(loan.borrowerId());
            Character owner = hands.online(loan.ownerId());
            Character bot = hands.loaded(loan.characterId());
            if (off) {
                end(loan, End.SWITCHED_OFF);
            } else if (bot == null || hands.leaderOf(loan.characterId()) != loan.borrowerId()) {
                end(loan, End.GONE);
            } else if (friend == null) {
                end(loan, End.BORROWER_AWAY);
            } else if (owner == null) {
                end(loan, End.OWNER_AWAY);
            } else if (now >= loan.endsAtMs()) {
                end(loan, End.TIME_UP);
            } else if (loan.waitingOutside() && now - loan.waitingSinceMs() >= STALE_WAIT_MS
                    && !hands.inEventInstance(friend) && !committed(friend.getId())) {
                rejoinOrReturn(loan.id()); // the run it waited for never started (a desk's Yes that fell through)
            } else {
                warn(loan, friend, now);
            }
        }
    }

    /** Every loan comes home now (the world stops). */
    public void endAll() {
        for (Loan loan : List.copyOf(byCharacter.values())) {
            endOrDrop(loan, End.SWITCHED_OFF);
        }
    }

    private void warn(Loan loan, Character friend, long now) {
        long left = loan.msLeft(now);
        int due = left <= WARN_LAST_MS ? 2 : left <= WARN_FIRST_MS ? 1 : 0;
        if (due <= loan.warned() || !byCharacter.replace(loan.characterId(), loan, loan.warnedTimes(due))) {
            return;
        }
        Persona persona = world.content().persona(loan.personaId());
        Character owner = hands.online(loan.ownerId());
        if (persona != null) {
            world.residents().whisper(persona, friend, LoanLines.timeLeft(due == 2 ? 1 : 5,
                    owner == null ? "home" : owner.getName()));
        }
    }

    private void lapse(Loan offered) {
        if (!byCharacter.remove(offered.characterId(), offered)) {
            return;
        }
        Character owner = hands.online(offered.ownerId());
        Character friend = hands.online(offered.borrowerId());
        Persona persona = world.content().persona(offered.personaId());
        if (owner != null && persona != null) {
            world.residents().whisper(persona, owner, LoanLines.noAnswer(friend == null ? "they" : friend.getName()));
        }
    }

    private void endOrDrop(Loan loan, End why) {
        if (loan.out()) {
            end(loan, why);
        } else {
            byCharacter.remove(loan.characterId(), loan);
        }
    }

    // ---- the way home -------------------------------------------------------------------------

    /**
     * Plan 3.2, return. The loan is gone at once (its owner's verbs work again; a call meets the LOAN lease until the
     * walk-out ends). With its owner on its map, a free seat and its row still BENCHED, it rejoins them in place;
     * otherwise it walks out and is unloaded, and the LOAN lease goes once it is off the server.
     */
    void end(Loan loan, End why) {
        if (!byCharacter.remove(loan.characterId(), loan)) {
            return; // ended already
        }
        world.slots().release(loan.personaId(), CompanionSlots.Kind.GUEST);
        Persona persona = world.content().persona(loan.personaId());
        Character owner = hands.online(loan.ownerId());
        Character friend = hands.online(loan.borrowerId());
        Character bot = hands.loaded(loan.characterId());
        String ownerName = owner != null ? owner.getName() : "home";
        String friendName = friend != null ? friend.getName() : "my friend";
        world.store().event(loan.ownerId(), loan.personaId(), "companion.loan_end", why.name() + " (" + friendName + ")");
        CompanionLeases.Lease asLoan = new CompanionLeases.Lease(CompanionLeases.Kind.LOAN, loan.borrowerId());
        if (bot == null) {
            world.roster().loanEnded(loan.ownerId(), loan.personaId());
            world.leases().release(loan.characterId(), CompanionLeases.Kind.LOAN);
            if (owner != null && persona != null) {
                world.residents().whisper(persona, owner, LoanLines.backAtCamp(friendName));
            }
            return;
        }
        hands.cancelTrade(bot);
        LootTerms terms = world.lootTerms();
        if (terms != null) {
            terms.settle(loan.characterId()); // its owner's share of what it looted out with the friend, to the haul
        }
        if (persona != null && friend != null && why != End.BORROWER_AWAY) {
            world.roster().sayInParty(bot, LoanLines.farewell(why, ownerName, friendName));
        }
        // an owner logging off is not taken back in place: it walks out, and their own logout benches nothing twice
        if (persona != null && owner != null && why != End.OWNER_AWAY && backInPlace(loan, owner, persona, bot, asLoan)) {
            world.residents().reply(persona, owner, LoanLines.backWithYou(friendName));
            return;
        }
        world.roster().loanEnded(loan.ownerId(), loan.personaId());
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            if (hands.partyOf(bot) != 0) {
                hands.leaveParty(bot);
            }
        }
        hands.walkOut(bot, () -> {
            hands.unload(bot);
            world.leases().release(loan.characterId(), CompanionLeases.Kind.LOAN); // saved first: the next load sees it
        });
        if (owner != null && persona != null) {
            world.residents().whisper(persona, owner, LoanLines.backAtCamp(friendName));
        }
    }

    /** Back with its owner where it stands: the lease, the leader and the row move back; false when it cannot. */
    private boolean backInPlace(Loan loan, Character owner, Persona persona, Character bot, CompanionLeases.Lease asLoan) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() != RosterState.BENCHED || !hands.together(owner, bot)
                || hands.inEventInstance(owner) || committed(owner.getId()) || !world.roster().canSeat(owner, entry)
                || hands.leaderOf(loan.characterId()) != loan.borrowerId()) {
            return false;
        }
        CompanionLeases.Lease asCompanion = new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, owner.getId());
        if (!world.leases().transfer(loan.characterId(), asLoan, asCompanion)) {
            return false;
        }
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            if (hands.partyOf(bot) != 0 && hands.partyOf(bot) != hands.partyOf(owner)) {
                hands.leaveParty(bot);
            }
            if (hands.handOver(bot, loan.borrowerId(), owner) && world.roster().backFromLoan(owner, persona, bot)) {
                return true;
            }
            if (hands.leaderOf(loan.characterId()) == owner.getId()) {
                hands.handOver(bot, owner.getId(), hands.online(loan.borrowerId()));
            }
        }
        world.leases().transfer(loan.characterId(), asCompanion, asLoan); // it walks out under its LOAN lease
        return false;
    }

    // ---- refusals and helpers -------------------------------------------------------------------

    /**
     * True when either side of a trade is out on loan (LivingWorldHooks.tradeAllowed): no trade opens with it, so
     * nothing in its bag or purse moves between the players. A human it turns away hears why, once a minute.
     */
    public boolean blocksTrade(Character c1, Character c2) {
        Character lent = onLoan(c1.getId()) ? c1 : onLoan(c2.getId()) ? c2 : null;
        if (lent == null) {
            return false;
        }
        Character other = lent == c1 ? c2 : c1;
        Loan loan = byCharacter.get(lent.getId());
        Persona persona = world.residents().personaOfCharacterId(lent.getId());
        if (loan != null && persona != null && !server.integration.AgentPresence.isAgent(other)
                && mayAnswerRefusal(other.getId(), lent.getId())) {
            Character owner = hands.online(loan.ownerId());
            world.residents().whisper(persona, other, LoanLines.noTrades(owner == null ? "my owner" : owner.getName()));
        }
        return true;
    }

    /**
     * A whisper reached a companion out on loan (CompanionDirectory.refused): to the friend it is lent to it answers,
     * once a minute, that it is directed from its card; to its owner, that it is out with the friend. Anyone else's
     * refused whisper stays unanswered.
     */
    public void whisperRefused(Character actor, Character agent) {
        Loan loan = byCharacter.get(agent.getId());
        Persona persona = world.residents().personaOfCharacterId(agent.getId());
        if (loan == null || !loan.out() || persona == null
                || loan.borrowerId() != actor.getId() && loan.ownerId() != actor.getId()
                || !mayAnswerRefusal(actor.getId(), agent.getId())) {
            return;
        }
        if (loan.ownerId() == actor.getId()) {
            Character friend = hands.online(loan.borrowerId());
            world.residents().whisper(persona, actor, LoanLines.outWith(friend == null ? "a friend" : friend.getName()));
            return;
        }
        Character owner = hands.online(loan.ownerId());
        world.residents().whisper(persona, actor, LoanLines.onLoan(owner == null ? "my owner" : owner.getName()));
    }

    /**
     * The friend's party-window invite of a companion out on loan to them (CompanionPartyRouter): one that stepped out
     * of their party comes back on a GUEST seat when there is room. Only that friend may ask.
     */
    public RosterResult reseat(Character friend, Persona persona) {
        Loan loan = persona == null ? null : lentOut(persona.id());
        if (loan == null || !mayDirect(friend, loan.characterId())) {
            return new RosterResult.Failed("That is not yours to ask.");
        }
        Character bot = hands.loaded(loan.characterId());
        if (bot == null || hands.inEventInstance(friend)) {
            return new RosterResult.Failed(persona.name() + ": not right now.");
        }
        if (hands.partyOf(bot) != 0 && hands.partyOf(bot) == hands.partyOf(friend)) {
            return new RosterResult.Ok(persona.name() + " is with you already.");
        }
        // waiting outside a run that never began, or stepped out for a full party: the friend's invite brings it back
        if (world.slots().free(friend) <= 0 || !world.slots().reserve(friend.getId(), loan.personaId(),
                CompanionSlots.Kind.GUEST, loan.endsAtMs() + SEAT_SLACK_MS)) {
            return new RosterResult.PartyFull();
        }
        boolean joined;
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            if (hands.partyOf(bot) != 0) {
                hands.leaveParty(bot);
            }
            joined = hands.joinParty(friend, bot);
        }
        if (!joined) {
            world.slots().release(loan.personaId(), CompanionSlots.Kind.GUEST);
            return new RosterResult.Failed(persona.name() + " could not join right now.");
        }
        byCharacter.replace(loan.characterId(), loan, loan.waiting(0L));
        hands.order(bot, friend, Order.FOLLOW);
        return new RosterResult.Ok(persona.name() + " joins your party.");
    }

    /**
     * True once per {@link #REFUSAL_GAP_MS} for this friend and companion: the friend's whisper or trade reached a
     * companion on loan to them, which answers once in its own words (CompanionDirectory.refused, the trade hook).
     */
    public boolean mayAnswerRefusal(int friendId, int characterId) {
        long now = clock.getAsLong();
        String key = friendId + ":" + characterId;
        Long last = refusalsAt.get(key);
        if (last != null && now - last < REFUSAL_GAP_MS) {
            return false;
        }
        refusalsAt.put(key, now);
        refusalsAt.values().removeIf(at -> now - at >= REFUSAL_GAP_MS * 10);
        return true;
    }

    private boolean offeredTo(int friendId) {
        for (Loan loan : byCharacter.values()) {
            if (!loan.out() && loan.borrowerId() == friendId) {
                return true;
            }
        }
        return false;
    }

    private Loan byId(long loanId) {
        for (Loan loan : byCharacter.values()) {
            if (loan.id() == loanId) {
                return loan;
            }
        }
        return null;
    }

    private boolean committed(int characterId) {
        Commitments commitments = world.commitments();
        return commitments != null && (commitments.isCommitted(characterId) || commitments.ownerCommitted(characterId));
    }
}
