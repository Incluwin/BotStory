package server.agents.capabilities.partyquest.pace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One piece of a party quest's run, in the order a run meets them. A profile lists them per party size; the estimate
 * turns each into time, and a recorded run ({@link AgentPqPace}) reports one span per leaf segment under the same key.
 *
 * <p>Calibration terms: a {@link Quota} learns {@code <key>.seek} (ms of walking, looting and role work per kill) and
 * {@code <key>.combat} (the share of the expected-damage policy's rate a member reaches while it fights); a
 * {@link Clear} learns its efficiency under its own key; a {@link Fixed} learns its term (segments may share one, as
 * KPQ's three puzzle entries share "puzzle.entry"); a {@link Puzzle} learns its leader's per-check and per-move time.
 * Leader-side terms are learned per leader.</p>
 */
public sealed interface AgentPqPaceSegment {
    String key();

    /** Whose time a fixed segment is: the companions' (moves, walks) or the leader's (talks, hand-ins, climbs). */
    enum Side {
        COMPANION,
        LEADER
    }

    /** Which mob type each member fights first: member id to mob id, given each member's damage per live mob type. */
    @FunctionalInterface
    interface Assigner {
        Map<Integer, Integer> assign(Map<Integer, Map<Integer, Double>> damageByMember);
    }

    /**
     * Kill {@code kills} of one respawning mob type (KPQ stage 1: one coupon per Ligator). A member kills at
     * {@code 1 / (seek + mobHp / (combat x dps))} a second; the human leader counts {@code leaderCredit} of that, since
     * the kills are asked of the others and his only help when he hands over what they drop. Every other member counts
     * in full, a human beside the leader included: he collects his own share.
     */
    record Quota(String key, int mobId, int mobHp, Pmf kills, double leaderCredit) implements AgentPqPaceSegment {
        public Quota {
            requireKey(key);
            if (mobId <= 0 || mobHp <= 0 || kills == null || !(leaderCredit >= 0.0d)) {
                throw new IllegalArgumentException("a quota needs a mob, its HP, a kill distribution and a credit >= 0");
            }
        }

        public String seekTerm() {
            return key + ".seek";
        }

        public String combatTerm() {
            return key + ".combat";
        }
    }

    /**
     * Kill every mob of a fixed census once (KPQ stage 5's normals, then King Slime). The theory time is a water-fill:
     * each fighter works the type {@code assigner} gives it until a type dies, then they choose again. Every member
     * fights but the human leader, who fights only when {@code leaderFights} (KPQ parks him by Cloto); a human beside
     * him fights like a companion. Its calibration term is the efficiency under {@link #key()}.
     */
    record Clear(String key, Map<Integer, Integer> counts, Map<Integer, Integer> hp, Assigner assigner,
                 boolean leaderFights) implements AgentPqPaceSegment {
        public Clear {
            requireKey(key);
            if (counts == null || counts.isEmpty() || hp == null || assigner == null) {
                throw new IllegalArgumentException("a clear needs a census, the HP of each type and an assigner");
            }
            for (Map.Entry<Integer, Integer> count : counts.entrySet()) {
                Integer mobHp = hp.get(count.getKey());
                if (count.getValue() == null || count.getValue() <= 0 || mobHp == null || mobHp <= 0) {
                    throw new IllegalArgumentException("every counted mob needs a positive count and HP");
                }
            }
            counts = Collections.unmodifiableMap(new TreeMap<>(counts));
            hp = Collections.unmodifiableMap(new TreeMap<>(hp));
        }

        public long totalHp() {
            long total = 0L;
            for (Map.Entry<Integer, Integer> count : counts.entrySet()) total += (long) count.getValue() * hp.get(count.getKey());
            return total;
        }
    }

    /**
     * A puzzle whose right answer is one of {@code combinations}, tried in a fixed order: the number of judged checks is
     * uniform on 1..combinations, and every check after the first costs the leader {@code checkTerm} plus
     * {@code leaderMoveShare} of a {@code moveTerm} (the share of formation changes that move the leader himself).
     */
    record Puzzle(String key, int combinations, double leaderMoveShare, String checkTerm, String moveTerm)
            implements AgentPqPaceSegment {
        public Puzzle {
            requireKey(key);
            requireKey(checkTerm);
            requireKey(moveTerm);
            if (combinations < 1 || !(leaderMoveShare >= 0.0d && leaderMoveShare <= 1.0d)) {
                throw new IllegalArgumentException("a puzzle needs at least one combination and a move share in [0, 1]");
            }
        }

        public Pmf checks() {
            return Pmf.uniform(1, combinations);
        }
    }

    /** A time that does not depend on the party's damage: its calibrated {@code term}, in milliseconds. */
    record Fixed(String key, Side side, String term) implements AgentPqPaceSegment {
        public Fixed {
            requireKey(key);
            requireKey(term);
            if (side == null) throw new IllegalArgumentException("a fixed segment needs a side");
        }

        public Fixed(String key, Side side) {
            this(key, side, key);
        }
    }

    /** Branches that run at the same time; the segment lasts as long as the slowest branch. */
    record Parallel(String key, List<List<AgentPqPaceSegment>> branches) implements AgentPqPaceSegment {
        public Parallel {
            requireKey(key);
            if (branches == null || branches.size() < 2) throw new IllegalArgumentException("parallel needs two branches");
            branches = branches.stream().map(List::copyOf).toList();
        }
    }

    /** Every non-parallel segment in run order, each parallel's branches in turn. */
    static List<AgentPqPaceSegment> leaves(List<AgentPqPaceSegment> segments) {
        List<AgentPqPaceSegment> leaves = new ArrayList<>();
        for (AgentPqPaceSegment segment : segments) {
            if (segment instanceof Parallel parallel) {
                parallel.branches().forEach(branch -> leaves.addAll(leaves(branch)));
            } else {
                leaves.add(segment);
            }
        }
        return leaves;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("a segment key is required");
    }
}
