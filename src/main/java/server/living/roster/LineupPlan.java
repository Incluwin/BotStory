package server.living.roster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who a party quest's entry NPC would send in (P6, flow 3): the party's humans, always, and the companions that fit best,
 * one seat per owner in turn (the leader's first: the leader's pick covers every companion in the party); the rest wait
 * outside as extras. It is startable when nothing blocks it, nobody chosen is still on the way, and it is three or four.
 * Built on the leader's I/O thread from memory ({@link CompanionPartyQuestService#plan}); it writes nothing.
 * {@code picked}: the companions the leader chose (null: her own choice), so a Yes or "Are they here yet?" plans it
 * again the same way: her choice takes whoever fits best by then, the leader's pick stays theirs.
 */
public record LineupPlan(String pqKey, int leaderId, List<Seat> lineup, List<Seat> extras, List<Seat> onTheWay,
                         List<Waiting> waiting, String blocker, boolean benchUseful, Set<Integer> picked) {
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
     * One party member as the lineup shows it; a human has no owner (0), no persona and no record. {@code here}: it
     * stands at the entrance on the leader's channel (an extra waits beside the NPC then, else where it is).
     * {@code runs} and {@code clears}: a companion's KPQ record (CompanionRunRecorder).
     */
    public record Seat(int characterId, String name, int level, int jobId, String jobName, boolean human, int ownerId,
                       String personaId, int mapId, boolean here, long etaMs, int runs, int clears) {
        /** "cleared 2 of 3" once the companion has three runs on record (RECORD_RUNS); null before. */
        public String clearRecord() {
            return runs >= RECORD_RUNS ? "cleared " + clears + " of " + runs : null;
        }
    }

    /** A companion that waits outside, and why ("bags full", "Lv32, too seasoned for this"). */
    public record Waiting(int characterId, String name, String reason) { }

    /** How a party member stands: it can go in, it is on its way, it waits outside, or it keeps the run from starting. */
    enum Fit { READY, ON_THE_WAY, WAITS, BLOCKS }

    /**
     * A party member for {@link #build}: its seat, how it fits, the line that says why when it waits or blocks,
     * whether it has a place in its owner's usual lineup (the tie-break after its score), and what it hits for in KPQ
     * (a companion that can go: its expected damage per second against KPQ's monsters; else 0).
     */
    record Candidate(Seat seat, Fit fit, String line, boolean usual, double dps) {
        Candidate(Seat seat, Fit fit, String line, boolean usual) {
            this(seat, fit, line, usual, 0.0d);
        }
    }

    public boolean startable() {
        return blocker == null && onTheWay.isEmpty() && lineup.size() >= MIN_SIZE && lineup.size() <= MAX_SIZE;
    }

    /** The character ids of the lineup, humans too: the party the run goes in with. */
    public Set<Integer> memberIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Seat seat : lineup) {
            ids.add(seat.characterId());
        }
        return ids;
    }

    /**
     * The lineup of {@code party} (in party order) for a leader at {@code leaderLevel}. Four go in when the humans and
     * the companions that can go reach four, else three; {@code picked} (the companion ids the leader chose, null: the
     * best) overrides the choice. Within an owner the best companion is the closest in level to the leader, 2 better
     * for a job branch nobody in the lineup has yet, up to 3 better by its clear rate once it has 3 runs, and up to
     * {@link #HITTING} better by what it hits for in KPQ.
     */
    static LineupPlan build(String pqKey, int leaderId, int leaderLevel, List<Candidate> party, Set<Integer> picked) {
        List<Seat> humans = new ArrayList<>();
        List<Candidate> seatable = new ArrayList<>();
        List<Waiting> waiting = new ArrayList<>();
        String blocker = null;
        for (Candidate member : party) {
            Seat seat = member.seat();
            if (seat.human()) {
                humans.add(seat.characterId() == leaderId ? 0 : humans.size(), seat);
            } else if (member.fit() == Fit.READY || member.fit() == Fit.ON_THE_WAY) {
                seatable.add(member);
            } else if (member.fit() == Fit.WAITS) {
                waiting.add(new Waiting(seat.characterId(), seat.name(), member.line()));
            }
            if (member.fit() == Fit.BLOCKS && blocker == null) {
                blocker = member.line();
            }
        }
        int room = Math.max(0, (picked != null || humans.size() + seatable.size() >= MAX_SIZE ? MAX_SIZE : MIN_SIZE)
                - humans.size());
        List<Candidate> pool = picked == null ? seatable
                : seatable.stream().filter(member -> picked.contains(member.seat().characterId())).toList();
        List<Candidate> chosen = roundRobin(leaderId, leaderLevel, humans, pool, room);
        List<Seat> lineup = new ArrayList<>(humans);
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
        if (blocker == null && humans.size() > MAX_SIZE) {
            blocker = LakelisLines.THREE_OR_FOUR;
        } else if (blocker == null && lineup.size() < MIN_SIZE) {
            blocker = tooFew(waiting);
        }
        boolean benchUseful = lineup.size() < MAX_SIZE && party.size() < RosterService.PARTY_CAP;
        return new LineupPlan(pqKey, leaderId, List.copyOf(lineup), List.copyOf(extras), List.copyOf(onTheWay),
                List.copyOf(waiting), blocker, benchUseful, picked == null ? null : Set.copyOf(picked));
    }

    /**
     * Up to {@code room} of {@code pool}, one per owner in turn, the leader's first, then in party order; each time the
     * owner's best by {@link #score} given the job branches the lineup has by then.
     */
    private static List<Candidate> roundRobin(int leaderId, int leaderLevel, List<Seat> humans, List<Candidate> pool,
                                              int room) {
        Map<Integer, List<Candidate>> byOwner = new LinkedHashMap<>();
        byOwner.put(leaderId, new ArrayList<>());
        for (Candidate member : pool) {
            byOwner.computeIfAbsent(member.seat().ownerId(), owner -> new ArrayList<>()).add(member);
        }
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

    /** Too few can go in: a companion that waits for potions says so (it is never sent in without), else the rule. */
    private static String tooFew(List<Waiting> waiting) {
        for (Waiting member : waiting) {
            if (LakelisLines.LOW_ON_POTIONS.equals(member.reason())) {
                return LakelisLines.named(LakelisLines.LOW_ON_POTIONS_LINE, member.name());
            }
            if (LakelisLines.LOW_ON_MANA.equals(member.reason())) {
                return LakelisLines.named(LakelisLines.LOW_ON_MANA_LINE, member.name());
            }
        }
        return LakelisLines.TOO_FEW;
    }
}
