package server.living.roster;

import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.agents.capabilities.partyquest.pace.Pmf;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A warm pace profile for the lineup tests, shaped like a short KPQ: a minute's opening, 100 Ligators of 1000 HP (a
 * kill a second per 1000 damage a second, no seek, full efficiency), then King Slime's 5000 HP; three or four
 * members, half an hour. {@link #broken()} throws from every segment list, as a profile gone wrong would.
 */
final class PaceTestProfile implements AgentPqPaceProfile {
    static final int LIGATOR = 9_300_001;
    static final int KING_SLIME = 9_300_003;

    private final boolean broken;

    private PaceTestProfile(boolean broken) {
        this.broken = broken;
    }

    static PaceTestProfile warm() {
        return new PaceTestProfile(false);
    }

    static PaceTestProfile broken() {
        return new PaceTestProfile(true);
    }

    @Override
    public String questKey() {
        return "kpq";
    }

    @Override
    public long timerMs() {
        return 1_800_000L;
    }

    @Override
    public int minSize() {
        return 3;
    }

    @Override
    public int maxSize() {
        return 4;
    }

    @Override
    public List<Integer> mobIds() {
        return List.of(LIGATOR, KING_SLIME);
    }

    @Override
    public int dangerMobId() {
        return KING_SLIME;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        if (broken) {
            throw new IllegalStateException("no segments");
        }
        if (partySize < 3 || partySize > 4) {
            throw new IllegalArgumentException("3-4 members, not " + partySize);
        }
        AgentPqPaceSegment.Assigner allOnTheBoss = damage -> {
            Map<Integer, Integer> choice = new TreeMap<>();
            damage.keySet().forEach(member -> choice.put(member, KING_SLIME));
            return choice;
        };
        return List.of(new AgentPqPaceSegment.Fixed("s1.open", AgentPqPaceSegment.Side.COMPANION),
                new AgentPqPaceSegment.Quota("s1.grind", LIGATOR, 1_000, Pmf.point(100), 0.5d),
                new AgentPqPaceSegment.Clear("s5.boss", Map.of(KING_SLIME, 1), Map.of(KING_SLIME, 5_000), allOnTheBoss,
                        false));
    }

    @Override
    public Map<String, Double> priors() {
        return Map.of("s1.open", 60_000.0d, "s1.grind.seek", 0.0d, "s1.grind.combat", 1.0d, "s5.boss", 1.0d);
    }

    @Override
    public int epoch() {
        return 1;
    }

    @Override
    public Thresholds thresholds() {
        return new Thresholds(7.0d, 0.6d, Map.of());
    }
}
