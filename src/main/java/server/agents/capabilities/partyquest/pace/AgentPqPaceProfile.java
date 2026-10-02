package server.agents.capabilities.partyquest.pace;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one party quest asks of a party, as the clear-time estimate sees it: its segments per party size, the mobs its
 * members are weighed against, and the prior value of every calibration term the segments name. A profile is built
 * once from game data on companion-ops ({@link AgentPqPaceProfiles#warm}) and read from memory afterwards.
 */
public interface AgentPqPaceProfile {
    String questKey();

    /** The event timer, in ms; 0 when unknown. */
    long timerMs();

    int minSize();

    int maxSize();

    /** The mobs a member's damage, hit chance and attack time are taken against. */
    List<Integer> mobIds();

    /** The mob whose touch decides whether a member is fragile; 0 for none. */
    int dangerMobId();

    boolean warmed();

    /** The run, in order, for a party of this size (every member, humans included). */
    List<AgentPqPaceSegment> segments(int partySize);

    /** The prior of every term the segments name: times in milliseconds, efficiencies as fractions. */
    Map<String, Double> priors();

    /** Records of another epoch are not learned from; bumped when a pace change lands. */
    int epoch();

    Thresholds thresholds();

    /**
     * When the estimate names a weakness: a member is FRAGILE below {@code fragileTouches} touches of the danger mob,
     * MISSES below {@code weakHitChance} against a mob, and the party is SLOW when a segment's mean time passes its
     * entry in {@code slowMs}.
     */
    record Thresholds(double fragileTouches, double weakHitChance, Map<String, Double> slowMs) {
        public Thresholds {
            slowMs = Map.copyOf(slowMs);
        }
    }

    /** The calibration terms that are the leader's (fixed leader segments and every puzzle's check and move). */
    default Set<String> leaderTerms() {
        Set<String> terms = new TreeSet<>();
        for (int size = minSize(); size <= maxSize(); size++) {
            for (AgentPqPaceSegment segment : AgentPqPaceSegment.leaves(segments(size))) {
                if (segment instanceof AgentPqPaceSegment.Fixed fixed && fixed.side() == AgentPqPaceSegment.Side.LEADER) {
                    terms.add(fixed.term());
                } else if (segment instanceof AgentPqPaceSegment.Puzzle puzzle) {
                    terms.add(puzzle.checkTerm());
                    terms.add(puzzle.moveTerm());
                }
            }
        }
        return terms;
    }

    /** Every calibration term the segments name, either side. */
    default Set<String> terms() {
        Set<String> terms = new TreeSet<>();
        for (int size = minSize(); size <= maxSize(); size++) {
            for (AgentPqPaceSegment segment : AgentPqPaceSegment.leaves(segments(size))) {
                switch (segment) {
                    case AgentPqPaceSegment.Quota quota -> {
                        terms.add(quota.seekTerm());
                        terms.add(quota.combatTerm());
                    }
                    case AgentPqPaceSegment.Clear clear -> terms.add(clear.key());
                    case AgentPqPaceSegment.Puzzle puzzle -> {
                        terms.add(puzzle.checkTerm());
                        terms.add(puzzle.moveTerm());
                    }
                    case AgentPqPaceSegment.Fixed fixed -> terms.add(fixed.term());
                    case AgentPqPaceSegment.Parallel ignored -> {
                    }
                }
            }
        }
        return terms;
    }

    /** The keys a recorded run reports spans under: every leaf segment, in run order. */
    default List<String> segmentKeys(int partySize) {
        return AgentPqPaceSegment.leaves(segments(partySize)).stream().map(AgentPqPaceSegment::key).toList();
    }
}
