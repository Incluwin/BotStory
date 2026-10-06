package server.living.roster;

import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Who a party quest's entry NPC would send in (P6, flow 3): the party's humans, always, and the companions that fit best,
 * one seat per owner in turn (the leader's first: the leader's pick covers every companion in the party); the rest wait
 * outside as extras. It is startable when nothing blocks it, nobody chosen is still on the way, and its size is within
 * its quest's seats (PqEntrance.SeatRules: KPQ three or four, HPQ three to six).
 * Built on the leader's I/O thread from memory ({@link CompanionPartyQuestService#plan}); it writes nothing.
 * {@code picked}: the companions the leader chose (null: her own choice), so a Yes or "Are they here yet?" plans it
 * again the same way: her choice takes whoever fits best by then, the leader's pick stays theirs.
 * {@code clearTime}: how long the lineup should take, as she weighs and words it; null without an estimate (the quest's
 * pace data is cold, the estimate failed, living_world.COMPANION_PQ_CLEAR_TIME is off, or no companion goes in).
 */
public record LineupPlan(String pqKey, int leaderId, List<Seat> lineup, List<Seat> extras, List<Seat> onTheWay,
                         List<Waiting> waiting, String blocker, boolean benchUseful, Set<Integer> picked,
                         ClearTime clearTime) {
    /** KPQ's seats (Lakelis's lines and windows count them); every quest's are its entrance's (PqEntrance.seats). */
    public static final int MIN_SIZE = 3;
    public static final int MAX_SIZE = 4;
    /** A companion's clear record is shown, and ranks it, from this many runs on. */
    static final int RECORD_RUNS = 3;
    /**
     * A companion is up to this much better by what it hits for in KPQ, against the best of those that can go. Live
     * (2026-09-30): Rurik went in on his level and job, and hit King Slime 0 times in 95.
     */
    static final double HITTING = 5.0d;
    /**
     * Lineups whose objectives are this close to the quickest are told apart by the lineup's own fit instead (level
     * closeness, a new job branch, the clear rate): the estimate is not finer than a minute, and a lineup does not
     * churn on a buff that wore off.
     */
    static final long TIME_TIE_MS = 60_000L;
    /** More lineups than this to weigh (the product over owners of their choices) and she chooses as before. */
    static final int MAX_LINEUPS = 64;
    /** Her own choice is this much quicker than the leader's pick before she says so. */
    static final long SOONER_MS = 120_000L;

    /** A lineup as it was before the clear-time estimate: none. */
    public LineupPlan(String pqKey, int leaderId, List<Seat> lineup, List<Seat> extras, List<Seat> onTheWay,
                      List<Waiting> waiting, String blocker, boolean benchUseful, Set<Integer> picked) {
        this(pqKey, leaderId, lineup, extras, onTheWay, waiting, blocker, benchUseful, picked, null);
    }

    /**
     * One party member as the lineup shows it; a human has no owner (0), no persona and no record. {@code here}: it
     * stands at the entrance on the leader's channel (an extra waits beside the NPC then, else where it is).
     * {@code runs} and {@code clears}: a companion's KPQ record (CompanionRunRecorder). {@code errand}: what a
     * companion on its way is doing (LakelisLines.AT_THE_STORE: gone for potions); null for a bench call and the rest.
     */
    public record Seat(int characterId, String name, int level, int jobId, String jobName, boolean human, int ownerId,
                       String personaId, int mapId, boolean here, long etaMs, int runs, int clears, String errand) {
        /** A seat on no errand. */
        public Seat(int characterId, String name, int level, int jobId, String jobName, boolean human, int ownerId,
                    String personaId, int mapId, boolean here, long etaMs, int runs, int clears) {
            this(characterId, name, level, jobId, jobName, human, ownerId, personaId, mapId, here, etaMs, runs, clears,
                    null);
        }

        /** "cleared 2 of 3" once the companion has three runs on record (RECORD_RUNS); null before. */
        public String clearRecord() {
            return runs >= RECORD_RUNS ? "cleared " + clears + " of " + runs : null;
        }
    }

    /** A companion that waits outside, and why ("bags full", "Lv32, too seasoned for this"). */
    public record Waiting(int characterId, String name, String reason) { }

    /**
     * How long the lineup should take: its estimate, the quest's timer (0: unknown), its objective (seconds, what she
     * weighs lineups by; infinite when it cannot clear) and its time (the longest wait for one still on its way plus
     * the median; infinite when it cannot clear); the objective and time of the lineup she would choose herself (the
     * same for her own choice; for the leader's pick hers, so she can say how much sooner hers is; NaN when hers has
     * none); and how long the leader's last clear with exactly these companions took (seconds; 0: none on record).
     */
    public record ClearTime(AgentPqPaceEstimate estimate, long timerMs, double objectiveS, double timeS,
                            double ownObjectiveS, double ownTimeS, double lastClearS) { }

    /**
     * What a lineup's clear time is read from: the estimate of its members' power (humans first, the leader first of
     * them; null when there is none), the quest's timer, and the leader's last clear with a set of companions (their
     * character ids; seconds, 0: none on record).
     */
    record Pace(Function<List<AgentPqMemberPower>, AgentPqPaceEstimate> estimator, long timerMs,
                ToDoubleFunction<Set<Integer>> lastClearS) { }

    /** How a party member stands: it can go in, it is on its way, it waits outside, or it keeps the run from starting. */
    enum Fit { READY, ON_THE_WAY, WAITS, BLOCKS }

    /**
     * A party member for {@link #build}: its seat, how it fits, the line that says why when it waits or blocks,
     * whether it has a place in its owner's usual lineup (the tie-break after its score), what it hits for in KPQ
     * (a companion that can go: its expected damage per second against KPQ's monsters; else 0) and its power for the
     * clear-time estimate (null when there is none: the estimate is cold or off, or the member cannot go).
     */
    record Candidate(Seat seat, Fit fit, String line, boolean usual, double dps, AgentPqMemberPower power) {
        Candidate(Seat seat, Fit fit, String line, boolean usual, double dps) {
            this(seat, fit, line, usual, dps, null);
        }

        Candidate(Seat seat, Fit fit, String line, boolean usual) {
            this(seat, fit, line, usual, 0.0d, null);
        }
    }

    public boolean startable() {
        PqEntrance.SeatRules seats = PqEntrance.seats(pqKey);
        return blocker == null && onTheWay.isEmpty() && lineup.size() >= seats.min() && lineup.size() <= seats.max();
    }

    /** The character ids of the lineup, humans too: the party the run goes in with. */
    public Set<Integer> memberIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Seat seat : lineup) {
            ids.add(seat.characterId());
        }
        return ids;
    }

    /** The lineup's clear-time estimate; null without one. */
    public AgentPqPaceEstimate estimate() {
        return clearTime == null ? null : clearTime.estimate();
    }

    /** {@link #build(String, int, int, List, Set, Pace)} without a clear-time estimate: the lineup as before it. */
    static LineupPlan build(String pqKey, int leaderId, int leaderLevel, List<Candidate> party, Set<Integer> picked) {
        return build(pqKey, leaderId, leaderLevel, party, picked, null);
    }

    /**
     * The lineup of {@code party} (in party order) for a leader at {@code leaderLevel}, seated by {@code pqKey}'s
     * entrance: her own choice fills the quest's preferred size when the humans and the companions that can go reach
     * it, else its minimum (KPQ: four, else three; HPQ: four, else three); {@code picked} (the companion ids the leader
     * chose, null: the best) overrides the choice and may fill the quest's most (KPQ four, HPQ six). Within an owner
     * the best companion is the closest in level to the leader, 2 better for a job branch nobody in the lineup has yet,
     * up to 3 better by its clear rate once it has 3 runs, and up to {@link #HITTING} better by what it hits for.
     *
     * <p>With {@code pace} (null: none), her own choice weighs every lineup the owners' seats allow (each owner keeps
     * the seats the turns above give it) by its objective: the longest wait for one of it still on its way, plus the
     * estimate's median, plus the timer times the chance it runs out. The quickest wins; lineups within
     * {@link #TIME_TIE_MS} of it go by their fit (as above, without what they hit for), then usual places, then
     * persona. More than {@link #MAX_LINEUPS} lineups, no estimate, or no lineup that can clear: the choice above.
     * The leader's pick is never changed; it gets its own estimate, and hers to compare with.</p>
     */
    static LineupPlan build(String pqKey, int leaderId, int leaderLevel, List<Candidate> party, Set<Integer> picked,
                            Pace pace) {
        PqEntrance entrance = PqEntrance.of(pqKey);
        PqEntrance.SeatRules seats = entrance.seatRules();
        List<Candidate> humans = new ArrayList<>();
        List<Candidate> seatable = new ArrayList<>();
        List<Waiting> waiting = new ArrayList<>();
        String blocker = null;
        for (Candidate member : party) {
            Seat seat = member.seat();
            if (seat.human()) {
                humans.add(seat.characterId() == leaderId ? 0 : humans.size(), member);
            } else if (member.fit() == Fit.READY || member.fit() == Fit.ON_THE_WAY) {
                seatable.add(member);
            } else if (member.fit() == Fit.WAITS) {
                waiting.add(new Waiting(seat.characterId(), seat.name(), member.line()));
            }
            if (member.fit() == Fit.BLOCKS && blocker == null) {
                blocker = member.line();
            }
        }
        List<Seat> humanSeats = humans.stream().map(Candidate::seat).toList();
        int ownRoom = Math.max(0, Math.clamp(humans.size() + seatable.size(), seats.min(), seats.preferred())
                - humans.size());
        int room = picked != null ? Math.max(0, seats.max() - humans.size()) : ownRoom;
        List<Candidate> pool = picked == null ? seatable
                : seatable.stream().filter(member -> picked.contains(member.seat().characterId())).toList();
        List<Candidate> chosen = roundRobin(leaderId, leaderLevel, humanSeats, pool, room);
        ClearTime clearTime = null;
        if (pace != null) {
            Weighing weighing = new Weighing(pace, leaderId, leaderLevel, humans);
            if (picked == null) {
                Weighed best = weighing.best(seatable, ownRoom, chosen);
                if (best != null) {
                    chosen = best.chosen();
                    clearTime = weighing.clearTime(best, best);
                }
            } else {
                Weighed pick = weighing.weigh(chosen);
                if (pick != null) {
                    Weighed own = weighing.best(seatable, ownRoom,
                            roundRobin(leaderId, leaderLevel, humanSeats, seatable, ownRoom));
                    clearTime = weighing.clearTime(pick, own);
                }
            }
        }
        List<Seat> lineup = new ArrayList<>(humanSeats);
        List<Seat> onTheWay = new ArrayList<>();
        for (Candidate member : chosen) {
            lineup.add(member.seat());
            if (member.fit() == Fit.ON_THE_WAY) {
                onTheWay.add(member.seat());
            }
        }
        List<Seat> extras = new ArrayList<>();
        for (Candidate member : party) {
            if (!member.seat().human() && member.fit() != Fit.BLOCKS && !chosen.contains(member)) {
                extras.add(member.seat());
            }
        }
        if (blocker == null && humans.size() > seats.max()) {
            blocker = entrance.voice().sizeRule();
        } else if (blocker == null && lineup.size() < seats.min()) {
            blocker = tooFew(waiting, entrance.voice());
        }
        boolean benchUseful = lineup.size() < seats.preferred() && party.size() < RosterService.PARTY_CAP;
        return new LineupPlan(pqKey, leaderId, List.copyOf(lineup), List.copyOf(extras), List.copyOf(onTheWay),
                List.copyOf(waiting), blocker, benchUseful, picked == null ? null : Set.copyOf(picked), clearTime);
    }

    /**
     * Up to {@code room} of {@code pool}, one per owner in turn, the leader's first, then in party order; each time the
     * owner's best by {@link #score} given the job branches the lineup has by then.
     */
    private static List<Candidate> roundRobin(int leaderId, int leaderLevel, List<Seat> humans, List<Candidate> pool,
                                              int room) {
        Map<Integer, List<Candidate>> byOwner = byOwner(leaderId, pool);
        Set<Integer> branches = new HashSet<>();
        for (Seat human : humans) {
            branches.add(human.jobId() / 100);
        }
        double bestDps = pool.stream().mapToDouble(Candidate::dps).max().orElse(0.0d);
        List<Candidate> chosen = new ArrayList<>();
        boolean picking = true;
        while (picking && chosen.size() < room) {
            picking = false;
            for (List<Candidate> own : byOwner.values()) {
                if (chosen.size() >= room || own.isEmpty()) {
                    continue;
                }
                Candidate best = own.stream().min(Comparator
                        .comparingDouble((Candidate member) -> -score(member, leaderLevel, branches, bestDps))
                        .thenComparing(member -> !member.usual())
                        .thenComparing(member -> member.seat().personaId())).orElseThrow();
                own.remove(best);
                chosen.add(best);
                branches.add(best.seat().jobId() / 100);
                picking = true;
            }
        }
        return chosen;
    }

    /** The pool by owner, the leader's first, then in party order; each owner's own in party order. */
    private static Map<Integer, List<Candidate>> byOwner(int leaderId, List<Candidate> pool) {
        Map<Integer, List<Candidate>> byOwner = new LinkedHashMap<>();
        byOwner.put(leaderId, new ArrayList<>());
        for (Candidate member : pool) {
            byOwner.computeIfAbsent(member.seat().ownerId(), owner -> new ArrayList<>()).add(member);
        }
        return byOwner;
    }

    /**
     * What it hits for counts in whole points against the best of the pool ({@code bestDps}), so a buff that wears off
     * between the lineup and the Yes rarely reorders it.
     */
    static double score(Candidate member, int leaderLevel, Set<Integer> branches, double bestDps) {
        Seat seat = member.seat();
        return -Math.abs(seat.level() - leaderLevel) + (branches.contains(seat.jobId() / 100) ? 0 : 2)
                + (seat.runs() >= RECORD_RUNS ? 3.0d * seat.clears() / seat.runs() : 0.0d)
                + (bestDps > 0.0d ? Math.round(HITTING * member.dps() / bestDps) : 0L);
    }

    /**
     * Too few can go in: a companion that waits for potions (or ammo) says so (it is never sent in without), and that
     * it cannot afford them when its own restock cannot; else the entrance's rule.
     */
    private static String tooFew(List<Waiting> waiting, DeskVoice voice) {
        for (Waiting member : waiting) {
            if (DeskLines.LOW_ON_POTIONS.equals(member.reason())) {
                return DeskLines.named(DeskLines.LOW_ON_POTIONS_LINE, member.name());
            }
            if (DeskLines.LOW_ON_MANA.equals(member.reason())) {
                return DeskLines.named(DeskLines.LOW_ON_MANA_LINE, member.name());
            }
            if (DeskLines.LOW_ON_POTIONS_BROKE.equals(member.reason())) {
                return DeskLines.named(DeskLines.LOW_ON_POTIONS_BROKE_LINE, member.name());
            }
            if (DeskLines.LOW_ON_MANA_BROKE.equals(member.reason())) {
                return DeskLines.named(DeskLines.LOW_ON_MANA_BROKE_LINE, member.name());
            }
            if (DeskLines.LOW_ON_AMMO.equals(member.reason())) {
                return DeskLines.named(DeskLines.LOW_ON_AMMO_LINE, member.name());
            }
        }
        return voice.tooFew();
    }

    /**
     * A lineup's companions (in the order shown), its estimate, its objective and its time (the wait plus the median),
     * in seconds; both infinite when it cannot clear.
     */
    private record Weighed(List<Candidate> chosen, AgentPqPaceEstimate estimate, double objectiveS, double timeS) { }

    /** Weighs lineups of the party's companions beside its humans by their clear-time estimate. */
    private record Weighing(Pace pace, int leaderId, int leaderLevel, List<Candidate> humans) {
        /**
         * Her choice from {@code pool} for {@code room} seats: the quickest lineup the owners' seats allow, or
         * {@code greedy} (the choice without an estimate) when there are too many to weigh or none can clear; null
         * when a lineup has no estimate.
         */
        Weighed best(List<Candidate> pool, int room, List<Candidate> greedy) {
            Map<Integer, List<Candidate>> byOwner = byOwner(leaderId, pool);
            List<List<Candidate>> owners = new ArrayList<>(byOwner.values());
            int[] quotas = quotas(owners, room);
            long lineups = 1L;
            for (int owner = 0; owner < owners.size(); owner++) {
                lineups *= choose(owners.get(owner).size(), quotas[owner]);
                if (lineups > MAX_LINEUPS) {
                    return weigh(greedy);
                }
            }
            List<Weighed> weighed = new ArrayList<>();
            for (List<Candidate> lineup : lineups(owners, quotas)) {
                Weighed one = weigh(lineup);
                if (one == null) {
                    return null;
                }
                weighed.add(one);
            }
            double quickest = weighed.stream().mapToDouble(Weighed::objectiveS).min().orElse(Double.POSITIVE_INFINITY);
            if (!Double.isFinite(quickest)) {
                return weigh(greedy);
            }
            List<Seat> humanSeats = humans.stream().map(Candidate::seat).toList();
            Set<Integer> humanBranches = new HashSet<>();
            for (Seat human : humanSeats) {
                humanBranches.add(human.jobId() / 100);
            }
            Weighed best = weighed.stream()
                    .filter(one -> one.objectiveS() <= quickest + TIME_TIE_MS / 1000.0d)
                    .min(Comparator.comparingDouble((Weighed one) -> -fit(one.chosen(), humanBranches))
                            .thenComparingLong(one -> -one.chosen().stream().filter(Candidate::usual).count())
                            .thenComparing(one -> personas(one.chosen())))
                    .orElseThrow();
            // shown in the order her turns seat them: the choice without an estimate's, when it is the same lineup
            List<Candidate> shown = Set.copyOf(best.chosen()).equals(Set.copyOf(greedy)) ? greedy
                    : roundRobin(leaderId, leaderLevel, humanSeats, best.chosen(), best.chosen().size());
            return new Weighed(List.copyOf(shown), best.estimate(), best.objectiveS(), best.timeS());
        }

        /**
         * The lineup of {@code chosen} beside the humans: its estimate and objective; null without an estimate, and
         * when no companion is chosen (the clear time is the companions' to weigh: a party of humans alone gets none).
         */
        Weighed weigh(List<Candidate> chosen) {
            if (chosen.isEmpty()) {
                return null;
            }
            List<AgentPqMemberPower> members = new ArrayList<>();
            double waitS = 0.0d;
            for (Candidate member : humans) {
                members.add(member.power());
            }
            for (Candidate member : chosen) {
                members.add(member.power());
                if (member.fit() == Fit.ON_THE_WAY) {
                    waitS = Math.max(waitS, member.seat().etaMs() / 1000.0d);
                }
            }
            if (members.contains(null)) {
                return null;
            }
            AgentPqPaceEstimate estimate = pace.estimator().apply(List.copyOf(members));
            if (estimate == null) {
                return null;
            }
            double timeS = !estimate.clearable() ? Double.POSITIVE_INFINITY : waitS + estimate.p50S();
            double objectiveS = timeS + pace.timerMs() / 1000.0d * estimate.overTimerChance();
            return new Weighed(List.copyOf(chosen), estimate, objectiveS, timeS);
        }

        /** The clear time of {@code lineup}, beside {@code own}: her own choice (null: she has none). */
        ClearTime clearTime(Weighed lineup, Weighed own) {
            Set<Integer> companions = new LinkedHashSet<>();
            for (Candidate member : lineup.chosen()) {
                companions.add(member.seat().characterId());
            }
            return new ClearTime(lineup.estimate(), pace.timerMs(), lineup.objectiveS(), lineup.timeS(),
                    own == null ? Double.NaN : own.objectiveS(), own == null ? Double.NaN : own.timeS(),
                    pace.lastClearS().applyAsDouble(Set.copyOf(companions)));
        }

        /** Each owner's seats as the turns of {@link #roundRobin} hand them out: they depend only on the pool sizes. */
        private static int[] quotas(List<List<Candidate>> owners, int room) {
            int[] quotas = new int[owners.size()];
            int seated = 0;
            boolean seating = true;
            while (seating && seated < room) {
                seating = false;
                for (int owner = 0; owner < owners.size() && seated < room; owner++) {
                    if (quotas[owner] < owners.get(owner).size()) {
                        quotas[owner]++;
                        seated++;
                        seating = true;
                    }
                }
            }
            return quotas;
        }

        /** Every lineup with each owner's quota of its own (in party order within an owner). */
        private static List<List<Candidate>> lineups(List<List<Candidate>> owners, int[] quotas) {
            List<List<Candidate>> lineups = new ArrayList<>();
            lineups.add(List.of());
            for (int owner = 0; owner < owners.size(); owner++) {
                List<List<Candidate>> picks = combinations(owners.get(owner), quotas[owner]);
                List<List<Candidate>> next = new ArrayList<>();
                for (List<Candidate> lineup : lineups) {
                    for (List<Candidate> pick : picks) {
                        List<Candidate> grown = new ArrayList<>(lineup);
                        grown.addAll(pick);
                        next.add(grown);
                    }
                }
                lineups = next;
            }
            return lineups;
        }

        private static List<List<Candidate>> combinations(List<Candidate> own, int size) {
            List<List<Candidate>> picks = new ArrayList<>();
            combine(own, size, 0, new ArrayList<>(), picks);
            return picks;
        }

        private static void combine(List<Candidate> own, int size, int from, List<Candidate> pick,
                                    List<List<Candidate>> picks) {
            if (pick.size() == size) {
                picks.add(List.copyOf(pick));
                return;
            }
            for (int index = from; index <= own.size() - (size - pick.size()); index++) {
                pick.add(own.get(index));
                combine(own, size, index + 1, pick, picks);
                pick.removeLast();
            }
        }

        private static long choose(int n, int k) {
            long ways = 1L;
            for (int i = 1; i <= k; i++) {
                ways = ways * (n - k + i) / i;
            }
            return ways;
        }

        /**
         * A lineup's fit as {@link #score} counts it, without what they hit for: each companion's level closeness and
         * clear rate, and 2 for each job branch it adds to the humans'.
         */
        private double fit(List<Candidate> chosen, Set<Integer> humanBranches) {
            double fit = 0.0d;
            Set<Integer> added = new HashSet<>();
            for (Candidate member : chosen) {
                Seat seat = member.seat();
                fit += -Math.abs(seat.level() - leaderLevel)
                        + (seat.runs() >= RECORD_RUNS ? 3.0d * seat.clears() / seat.runs() : 0.0d);
                int branch = seat.jobId() / 100;
                if (!humanBranches.contains(branch) && added.add(branch)) {
                    fit += 2.0d;
                }
            }
            return fit;
        }

        private static String personas(List<Candidate> chosen) {
            return String.join(",", chosen.stream()
                    .map(member -> member.seat().personaId() == null ? "" : member.seat().personaId()).sorted().toList());
        }
    }
}
