package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyEvents.PartyChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The only slot authority (docs/BOT_MANAGEMENT_PLAN.md, "CompanionSlots"). A party's 6 seats hold its members plus
 * the reservations of its online humans: HOLD (outside a run), AWAY (on an errand), TRAVEL (on the way in) and GUEST
 * (a citizen charged to the human who invited it). A reservation counts only while its holder is not a member.
 *
 * <p>Fair share (decision 14): with H online humans each is guaranteed floor((6-H)/H) seats for companions and
 * guests; the leftover seats go first come. The share is also a cap: seats a friend leaves unused stay theirs, so
 * they can call their own at any time (lending them is P9). Within one human's crew the usual lineup keeps its seats
 * first and guests are the first to step out. A leader who expels another human's companion caps that human's seats
 * in the party at one fewer than they were using, so the seat stays empty until the party disbands or that human
 * leaves it. Offline members and Agents nobody is charged for just occupy a seat.</p>
 *
 * <p>The seat arithmetic is pure ({@link #allocate}, {@link #free(View, int)}, {@link #makeRoom(View, int)},
 * {@link #canAdmit(View, boolean)}, {@link #decideJoin}) over a {@link View} the live methods build from a party.
 * Reservations and leader caps live in memory; the roster rebuilds AWAY ones at boot.</p>
 */
public final class CompanionSlots {
    public static final int PARTY_CAP = 6;
    /** A seat taken for a load that joins at once; the spawn and the join run inside it, so this only bounds a crash. */
    public static final long SEAT_HOLD_MS = 30_000L;
    /** A travel seat is kept this long past the ETA, in case the arrival runs late. */
    public static final long TRAVEL_GRACE_MS = 30_000L;
    private static final Logger log = LoggerFactory.getLogger(CompanionSlots.class);
    private static final String CHARACTER_KEY = "character:";

    public enum Kind { HOLD, AWAY, TRAVEL, GUEST }

    /** A seat held for {@code key} (a persona id, or {@link #characterKey} for anyone else) and charged to its owner. */
    public record Reservation(int ownerId, String key, Kind kind, long untilMs, long seq) {
        public boolean live(long nowMs) {
            return untilMs > nowMs;
        }
    }

    /**
     * One party member as the seat arithmetic sees it. {@code human}: an online human, who gets a fair share.
     * {@code chargedTo}: the online human in this party whose companion or guest it is, 0 for anyone else.
     */
    public record Member(String key, int characterId, boolean human, int chargedTo, boolean guest) { }

    /**
     * A party for the seat arithmetic: members in join order, counted reservations, leader caps (owner id -> the most
     * seats that owner may use), usual lineups.
     */
    public record View(List<Member> members, List<Reservation> reservations, Map<Integer, Integer> caps,
                       Map<String, Integer> lineup) {
        public View {
            members = List.copyOf(members);
            reservations = List.copyOf(reservations);
            caps = Map.copyOf(caps);
            lineup = Map.copyOf(lineup);
        }

        /** With {@code member} in it; a seat still held for it is its own seat, not a second claim (as in view()). */
        View with(Member member) {
            List<Member> joined = new ArrayList<>(members);
            joined.add(member);
            List<Reservation> held = new ArrayList<>(reservations);
            held.removeIf(reservation -> reservation.key().equals(member.key()));
            return new View(joined, held, caps, lineup);
        }
    }

    /** A seat one human's crew uses: a member ({@code characterId} != 0) or a reservation. */
    public record Claim(String key, int characterId, int ownerId, boolean guest) {
        public boolean member() {
            return characterId != 0;
        }
    }

    /**
     * The seats of a view. {@code share}: each human's cap (its base share within its leader cap, plus the leftover it
     * holds); {@code leftoverFree}: leftover seats nobody holds yet; {@code overflow}: claims past their owner's cap,
     * and {@code kept} the others, both in step-out order (guests first, then each crew from the back of its lineup).
     */
    public record Allocation(int humans, int occupied, Map<Integer, Integer> share, Map<Integer, Integer> usage,
                             int leftoverFree, List<Claim> overflow, List<Claim> kept) {
        public int shareOf(int ownerId) {
            return share.getOrDefault(ownerId, 0);
        }

        public int usageOf(int ownerId) {
            return usage.getOrDefault(ownerId, 0);
        }
    }

    public enum RoomOutcome { NOT_NEEDED, STEP_OUT, IMPOSSIBLE }

    /** Make-room for a human joining: who steps out, when someone must. */
    public record MakeRoom(RoomOutcome outcome, Claim stepOut) { }

    /** Why a bot's join was let through, or not. */
    public enum JoinVerdict {
        ADMIT_BOT_ONLY, ADMIT_UNCLAIMED, ADMIT_SEATED, ADMIT_PQ, REFUSE_UNSEATED;

        public boolean admits() {
            return this != REFUSE_UNSEATED;
        }
    }

    private final LivingWorld world;
    private final LongSupplier clock;
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    /** party id -> owner id -> the most seats its leader's expels left that owner there. */
    private final Map<Integer, Map<Integer, Integer>> leaderCaps = new ConcurrentHashMap<>();
    /** returnWhenRoom: companion key -> the party it may not come back into until that party disbands (0 = none). */
    private final Map<String, Integer> waitingForRoom = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public CompanionSlots(LivingWorld world) {
        this(world, System::currentTimeMillis);
    }

    CompanionSlots(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
    }

    public static String characterKey(int characterId) {
        return CHARACTER_KEY + characterId;
    }

    // ---- live seats -----------------------------------------------------------------------

    /** Seats this owner may still fill with companions or guests: in their party, or in one they would make. */
    public int free(Character owner) {
        return free(view(owner), owner.getId());
    }

    /**
     * Seats taken where the owner is: the party's members and the seats held for its humans' companions and guests
     * not in it yet (the owner and theirs, when partyless). Below 6 while {@link #free} is 0, the rest are a friend's
     * share: P2a's share is a cap, and lending unused seats is P9.
     */
    public int occupied(Character owner) {
        View view = view(owner);
        return view.members().size() + view.reservations().size();
    }

    /** The owner's cap in {@code party} (as if they joined it, when they are not in it). */
    public int shareFor(Character owner, Party party) {
        View view = party == null ? view(owner) : view(party, false);
        if (!isHuman(view, owner.getId())) {
            view = view.with(new Member(characterKey(owner.getId()), owner.getId(), true, 0, false));
            view = withReservationsOf(view, owner.getId());
        }
        return allocate(view).shareOf(owner.getId());
    }

    /** Claims past their owner's cap in {@code party}, in step-out order (what a rebalance would bench). */
    public List<Claim> overflow(Party party) {
        return party == null ? List.of() : allocate(view(party, true)).overflow();
    }

    /** Make-room at a human's accept into {@code party} (P0 probe 12: never at the invite). */
    public MakeRoom makeRoom(Party party, Character invitee) {
        return makeRoom(view(party, true), invitee == null ? 0 : invitee.getId());
    }

    /** Humans come first: true when {@code joiningHuman} can join now, or once someone steps out for them. */
    public boolean canAdmit(Party party, Character joiningHuman) {
        return party != null && canAdmit(view(party, true), party.isStarterParty());
    }

    // ---- reservations ---------------------------------------------------------------------

    /**
     * Holds a seat for {@code key} until {@code untilMs}, charged to {@code ownerId}; the owner's own reservation of
     * the key is replaced. False while another owner holds it.
     */
    public boolean reserve(int ownerId, String key, Kind kind, long untilMs) {
        long now = clock.getAsLong();
        boolean[] taken = {false};
        reservations.compute(key, (k, held) -> {
            if (held != null && held.live(now) && held.ownerId() != ownerId) {
                return held;
            }
            taken[0] = true;
            return new Reservation(ownerId, key, kind, untilMs, sequence.incrementAndGet());
        });
        return taken[0];
    }

    /** The live reservation of {@code key}, or null. */
    public Reservation reservation(String key) {
        Reservation held = key == null ? null : reservations.get(key);
        if (held != null && !held.live(clock.getAsLong())) {
            reservations.remove(key, held);
            return null;
        }
        return held;
    }

    /** Gives the seat back, whatever kind it was. */
    public boolean release(String key) {
        return key != null && reservations.remove(key) != null;
    }

    /** Gives the seat back only if it is held as {@code kind}. */
    public boolean release(String key, Kind kind) {
        Reservation held = key == null ? null : reservations.get(key);
        return held != null && held.kind() == kind && reservations.remove(key, held);
    }

    /** The owner's live reservations, oldest first. */
    public List<Reservation> reservations(int ownerId) {
        long now = clock.getAsLong();
        List<Reservation> out = new ArrayList<>();
        for (Reservation held : reservations.values()) {
            if (held.ownerId() == ownerId && held.live(now)) {
                out.add(held);
            }
        }
        out.sort(Comparator.comparingLong(Reservation::seq));
        return out;
    }

    // ---- leader caps ----------------------------------------------------------------------

    /**
     * The leader of {@code partyId} expelled one of {@code ownerId}'s companions: that owner may use at most
     * {@code cap} seats there ({@link #capAfterExpel}) until the party disbands or the owner leaves it.
     */
    public void recordLeaderExpel(int partyId, int ownerId, int cap) {
        if (partyId != 0 && ownerId != 0) {
            leaderCaps.computeIfAbsent(partyId, id -> new ConcurrentHashMap<>())
                    .merge(ownerId, Math.max(0, cap), Math::min);
        }
    }

    /** The owner's leader cap in the party, or -1 when its leader has not capped them. */
    public int leaderCap(int partyId, int ownerId) {
        Map<Integer, Integer> caps = leaderCaps.get(partyId);
        return caps == null ? -1 : caps.getOrDefault(ownerId, -1);
    }

    /** True when a leader cap is what keeps the owner from seating one more in their party. */
    public boolean leaderCapped(Character owner) {
        Party party = owner.getParty();
        return party != null && leaderCap(party.getId(), owner.getId()) >= 0
                && leaderCapped(view(party, false), owner.getId());
    }

    // ---- returnWhenRoom -------------------------------------------------------------------

    /**
     * A companion the party pushed out (a merge, a rebalance, make-room, another leader's expel) comes back by itself
     * once its owner has room again, except into {@code blockedPartyId} (the party whose leader expelled it) until
     * that party disbands.
     */
    public void waitForRoom(String key, int blockedPartyId) {
        if (key != null) {
            waitingForRoom.put(key, blockedPartyId);
        }
    }

    /** Called or benched on purpose: it no longer waits for room. */
    public void stopWaiting(String key) {
        if (key != null) {
            waitingForRoom.remove(key);
        }
    }

    public boolean waitingForRoom(String key) {
        return key != null && waitingForRoom.containsKey(key);
    }

    /** True when {@code key} waits for room but may not come back into {@code partyId} yet. */
    public boolean returnBlocked(String key, int partyId) {
        Integer blocked = key == null ? null : waitingForRoom.get(key);
        return blocked != null && blocked != 0 && blocked == partyId;
    }

    // ---- party events and the seat gate ---------------------------------------------------

    /**
     * A committed party change, on companion-ops. A join uses up the joiner's reservation (a guest keeps its GUEST
     * seat, which charges it to its host); a guest that leaves gives its seat back; an owner who leaves takes their
     * leader cap along (a leader who invites them back starts them afresh), and a disband resets them all.
     */
    public void onPartyChanged(PartyChangeEvent event) {
        switch (event.op()) {
            case JOIN -> {
                String key = keyOf(event.targetId());
                Reservation held = reservation(key);
                if (held != null && held.kind() != Kind.GUEST && memberNow(event.targetId(), event.partyId())) {
                    release(key);
                }
            }
            case LEAVE, EXPEL -> {
                release(keyOf(event.targetId()), Kind.GUEST);
                Map<Integer, Integer> caps = leaderCaps.get(event.partyId());
                if (caps != null) {
                    caps.remove(event.targetId());
                }
            }
            case DISBAND -> {
                leaderCaps.remove(event.partyId());
                waitingForRoom.replaceAll((key, blocked) -> blocked == event.partyId() ? 0 : blocked);
                for (int memberId : event.before().memberIds()) {
                    release(keyOf(memberId), Kind.GUEST);
                }
            }
            default -> { }
        }
    }

    /**
     * The seat gate ({@code PartySeatGate}): a bot joining a party with a human must hold a reservation charged to a
     * human in it, unless the party is bot-only, the bot is nobody's (a GM tool or a fixture), or an Agent PQ runs
     * the party (a lobby waiter's DELAY_ACCEPT, a registered engagement such as {@code !kpqtest}, or a citizen a PQ
     * coordinator's lobby sends in). A refusal is logged as {@code companion.unseated_join}.
     */
    public boolean admitsJoin(Character bot, Party party) {
        Set<Integer> humans = new HashSet<>();
        for (PartyCharacter member : party.getMembers()) {
            if (!member.isBot()) {
                humans.add(member.getId());
            }
        }
        if (humans.isEmpty()) {
            return true; // decideJoin's ADMIT_BOT_ONLY, before any lookup: citizens party up all the time
        }
        String key = keyOf(bot.getId());
        JoinVerdict verdict = decideJoin(!humans.isEmpty(), world.directory().isClaimed(bot.getId()),
                reservation(key), humans, () -> runByPartyQuest(bot, party)
                        || world.directory().ownerOfCharacter(bot.getId()) == 0 && ledByPartyQuest(party));
        if (verdict.admits()) {
            return true;
        }
        int chargedTo = world.directory().ownerOfCharacter(bot.getId());
        String personaId = key.startsWith(CHARACTER_KEY) ? null : key;
        String detail = bot.getName() + " into party " + party.getId() + " led by " + party.getLeaderId();
        log.warn("companion.unseated_join: {}", detail);
        world.executor().submit("companion.unseated_join", () -> world.store().event(
                chargedTo != 0 ? chargedTo : party.getLeaderId(), personaId, "companion.unseated_join", detail));
        return false;
    }

    /** A persona's id for its character, else {@link #characterKey}. */
    public String keyOf(int characterId) {
        Persona persona = world.residents().personaOfCharacterId(characterId);
        return persona == null ? characterKey(characterId) : persona.id();
    }

    // ---- views ----------------------------------------------------------------------------

    private View view(Character owner) {
        Party party = owner.getParty();
        if (party != null) {
            return view(party, false);
        }
        View alone = new View(List.of(new Member(characterKey(owner.getId()), owner.getId(), true, 0, false)),
                List.of(), Map.of(), Map.of());
        return withReservationsOf(alone, owner.getId());
    }

    private View withReservationsOf(View view, int ownerId) {
        List<Reservation> held = new ArrayList<>(view.reservations());
        held.addAll(reservations(ownerId));
        return new View(view.members(), held, view.caps(), view.lineup());
    }

    /** {@code withLineup}: read the usual lineups (the step-out order needs them; the seat counts do not). */
    View view(Party party, boolean withLineup) {
        List<PartyCharacter> partyMembers = new ArrayList<>(party.getMembers());
        Set<Integer> humans = new HashSet<>();
        for (PartyCharacter member : partyMembers) {
            if (!member.isBot() && member.isOnline()) {
                humans.add(member.getId());
            }
        }
        List<Member> members = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (PartyCharacter member : partyMembers) {
            int id = member.getId();
            if (!member.isBot()) {
                members.add(new Member(characterKey(id), id, humans.contains(id), 0, false));
                continue;
            }
            String key = keyOf(id);
            Reservation held = reservation(key);
            boolean guest = held != null && held.kind() == Kind.GUEST;
            int owner = guest ? held.ownerId() : key.startsWith(CHARACTER_KEY) ? 0 : world.roster().cachedOwnerOf(key);
            members.add(new Member(key, id, false, humans.contains(owner) ? owner : 0, guest));
            keys.add(key);
        }
        List<Reservation> counted = new ArrayList<>();
        Map<String, Integer> lineup = new HashMap<>();
        for (int human : humans) {
            for (Reservation held : reservations(human)) {
                if (!keys.contains(held.key())) {
                    counted.add(held);
                }
            }
            if (withLineup) {
                lineup.putAll(world.store().lineupSlots(human));
            }
        }
        return new View(members, counted, leaderCaps.getOrDefault(party.getId(), Map.of()), lineup);
    }

    private boolean memberNow(int characterId, int partyId) {
        Character character = world.roster().onlineCharacter(characterId);
        return character != null && character.getParty() != null && character.getParty().getId() == partyId;
    }

    /** An Agent PQ runs this party: a registered engagement ({@code !kpqtest}), or a lobby waiter's DELAY_ACCEPT. */
    private static boolean runByPartyQuest(Character bot, Party party) {
        if (AgentPartyQuestEngagementRegistry.forMember(bot.getId()) != null) {
            return true;
        }
        PartyCharacter leader = party.getLeader();
        Character inviter = leader == null ? null : leader.getPlayer();
        return inviter != null && AgentPartyQuestLobbyRuntime.decidePartyInvite(bot, inviter)
                == AgentPartyQuestLobbyRuntime.InviteDecision.DELAY_ACCEPT;
    }

    /**
     * The party's leader coordinates an Agent PQ lobby or engagement. Its directed joins (the KPQ/HPQ/EPQ/LMPQ lobby
     * admissions, AgentPartyQuestDirectedAdmissionRuntime) join first and index the member after, so the joiner is
     * not registered yet; the gate asks this only for a bot that is nobody's roster companion.
     */
    private static boolean ledByPartyQuest(Party party) {
        int leaderId = party.getLeaderId();
        return AgentPartyQuestEngagementRegistry.forMember(leaderId) != null
                || AgentPartyQuestLobbyRegistry.forMember(leaderId) != null;
    }

    // ---- pure seat arithmetic -------------------------------------------------------------

    private static boolean isHuman(View view, int characterId) {
        for (Member member : view.members()) {
            if (member.human() && member.characterId() == characterId) {
                return true;
            }
        }
        return false;
    }

    /** One claim with what orders it: guests last, then the usual lineup, members before reservations, arrival. */
    private record Ranked(Claim claim, int lineupSlot, boolean reservation, long arrival) { }

    private static final Comparator<Ranked> KEEP_ORDER = Comparator.comparing((Ranked r) -> r.claim().guest())
            .thenComparingInt(Ranked::lineupSlot)
            .thenComparing(Ranked::reservation)
            .thenComparingLong(Ranked::arrival);

    private static final Comparator<Ranked> FIRST_COME = Comparator.comparing(Ranked::reservation)
            .thenComparingLong(Ranked::arrival);

    public static Allocation allocate(View view) {
        List<Integer> humans = new ArrayList<>();
        for (Member member : view.members()) {
            if (member.human()) {
                humans.add(member.characterId());
            }
        }
        int occupied = view.members().size() + view.reservations().size();
        if (humans.isEmpty()) {
            return new Allocation(0, occupied, Map.of(), Map.of(), 0, List.of(), List.of());
        }
        int base = Math.max(0, (PARTY_CAP - humans.size()) / humans.size());
        int leftover = Math.max(0, (PARTY_CAP - humans.size()) % humans.size());
        Map<Integer, List<Ranked>> crews = new LinkedHashMap<>();
        for (int human : humans) {
            crews.put(human, new ArrayList<>());
        }
        for (int i = 0; i < view.members().size(); i++) {
            Member member = view.members().get(i);
            List<Ranked> crew = member.human() || member.chargedTo() == 0 ? null : crews.get(member.chargedTo());
            if (crew != null) {
                crew.add(new Ranked(new Claim(member.key(), member.characterId(), member.chargedTo(), member.guest()),
                        slot(view, member.key()), false, i));
            }
        }
        for (Reservation held : view.reservations()) {
            List<Ranked> crew = crews.get(held.ownerId());
            if (crew != null) {
                crew.add(new Ranked(new Claim(held.key(), 0, held.ownerId(), held.kind() == Kind.GUEST),
                        slot(view, held.key()), true, held.seq()));
            }
        }
        Map<Integer, Integer> share = new HashMap<>();
        Map<Integer, Integer> usage = new HashMap<>();
        List<Ranked> overBase = new ArrayList<>();
        Map<Claim, Integer> position = new HashMap<>();
        for (Map.Entry<Integer, List<Ranked>> crew : crews.entrySet()) {
            List<Ranked> claims = crew.getValue();
            claims.sort(KEEP_ORDER);
            int within = Math.min(base, capOf(view, crew.getKey()));
            share.put(crew.getKey(), within);
            usage.put(crew.getKey(), claims.size());
            for (int i = 0; i < claims.size(); i++) {
                position.put(claims.get(i).claim(), i);
                if (i >= within) {
                    overBase.add(claims.get(i));
                }
            }
        }
        overBase.sort(FIRST_COME);
        Set<Claim> overflowing = new HashSet<>();
        for (Ranked over : overBase) {
            int owner = over.claim().ownerId();
            if (leftover > 0 && share.get(owner) < capOf(view, owner)) {
                leftover--;
                share.merge(owner, 1, Integer::sum);
            } else {
                overflowing.add(over.claim());
            }
        }
        // step-out order: guests first, the latest first; then companions from the back of each lineup
        Comparator<Ranked> stepOut = Comparator.comparing((Ranked r) -> !r.claim().guest())
                .thenComparing(r -> r.claim().guest() ? -r.arrival() : -position.get(r.claim()))
                .thenComparingLong(r -> -r.arrival());
        List<Ranked> all = new ArrayList<>();
        crews.values().forEach(all::addAll);
        all.sort(stepOut);
        List<Claim> overflow = new ArrayList<>();
        List<Claim> kept = new ArrayList<>();
        for (Ranked ranked : all) {
            (overflowing.contains(ranked.claim()) ? overflow : kept).add(ranked.claim());
        }
        return new Allocation(humans.size(), occupied, share, usage, leftover, overflow, kept);
    }

    /**
     * Seats {@code ownerId} may still fill: their cap plus unheld leftover, never past their leader cap, less what
     * they use, within the room left.
     */
    public static int free(View view, int ownerId) {
        if (!isHuman(view, ownerId)) {
            return 0;
        }
        Allocation seats = allocate(view);
        int byShare = Math.min(seats.shareOf(ownerId) + seats.leftoverFree(), capOf(view, ownerId))
                - seats.usageOf(ownerId);
        return Math.max(0, Math.min(byShare, PARTY_CAP - seats.occupied()));
    }

    /**
     * True when {@code ownerId}'s leader cap is what holds them back: they use every seat it leaves them, and without
     * it their share, the leftover nobody holds and the room left would give them more. A friend's share or a full
     * party is not the leader's doing.
     */
    static boolean leaderCapped(View view, int ownerId) {
        Integer cap = view.caps().get(ownerId);
        if (cap == null) {
            return false;
        }
        Map<Integer, Integer> others = new HashMap<>(view.caps());
        others.remove(ownerId);
        Allocation uncapped = allocate(new View(view.members(), view.reservations(), others, view.lineup()));
        int usage = uncapped.usageOf(ownerId);
        int withoutCap = Math.min(uncapped.shareOf(ownerId) + uncapped.leftoverFree(),
                usage + Math.max(0, PARTY_CAP - uncapped.occupied()));
        return usage >= cap && withoutCap > cap;
    }

    /**
     * The leader cap an expel leaves {@code ownerId}: one seat fewer than they were using in {@code before} (the
     * party with the expelled companion still in it), never more than their share less one, and never below 0.
     */
    public static int capAfterExpel(View before, int ownerId) {
        Allocation seats = allocate(before);
        return Math.max(0, Math.min(seats.shareOf(ownerId), seats.usageOf(ownerId)) - 1);
    }

    /**
     * Make-room for human {@code inviteeId} joining: nothing while a member seat is open; else, under the shares
     * with them in, the first member past its owner's cap in step-out order, or failing that the first member
     * anyone is charged for. Impossible only when every member is a human or nobody's Agent.
     */
    public static MakeRoom makeRoom(View view, int inviteeId) {
        if (view.members().size() < PARTY_CAP) {
            return new MakeRoom(RoomOutcome.NOT_NEEDED, null);
        }
        Allocation joined = allocate(view.with(new Member(characterKey(inviteeId), inviteeId, true, 0, false)));
        for (List<Claim> order : List.of(joined.overflow(), joined.kept())) {
            for (Claim claim : order) {
                if (claim.member()) {
                    return new MakeRoom(RoomOutcome.STEP_OUT, claim);
                }
            }
        }
        return new MakeRoom(RoomOutcome.IMPOSSIBLE, null);
    }

    /** A human may join unless it is a starter party, or the party is full and nobody can step out. */
    public static boolean canAdmit(View view, boolean starterParty) {
        return !starterParty && makeRoom(view, 0).outcome() != RoomOutcome.IMPOSSIBLE;
    }

    /**
     * The seat gate's rule. {@code held}: the bot's live reservation, or null; {@code humans}: the human members.
     * {@code partyQuest} is asked last, since it takes the PQ locks.
     */
    public static JoinVerdict decideJoin(boolean partyHasHuman, boolean claimed, Reservation held, Set<Integer> humans,
                                         BooleanSupplier partyQuest) {
        if (!partyHasHuman) {
            return JoinVerdict.ADMIT_BOT_ONLY;
        }
        if (!claimed) {
            return JoinVerdict.ADMIT_UNCLAIMED;
        }
        if (held != null && humans.contains(held.ownerId())) {
            return JoinVerdict.ADMIT_SEATED;
        }
        return partyQuest.getAsBoolean() ? JoinVerdict.ADMIT_PQ : JoinVerdict.REFUSE_UNSEATED;
    }

    /** The owner's leader cap in the view, or the whole party when no leader capped them. */
    private static int capOf(View view, int ownerId) {
        return view.caps().getOrDefault(ownerId, PARTY_CAP);
    }

    private static int slot(View view, String key) {
        Integer slot = view.lineup().get(key);
        return slot == null ? Integer.MAX_VALUE : slot;
    }
}
