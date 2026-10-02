package server.agents.capabilities.partyquest.pace;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How long a lineup should take, as a distribution: seconds from the start of the run to the clear. When the party
 * cannot clear (a mob nobody can hurt), {@code clearable} is false, every quantile and the mean are the estimate's
 * horizon and {@code overTimerChance} is 1; no field is ever NaN.
 *
 * @param segmentMeanS   mean seconds per segment key (leaves and parallels)
 * @param partyDpsByMob  per profile mob, the sum of the companions' expected damage per second
 * @param weaknesses     what makes this lineup slow or risky, for the NPC to put in her own words
 * @param recordsUsed    recorded runs behind the calibration (0: priors only)
 */
public record AgentPqPaceEstimate(boolean clearable, double p10S, double p50S, double p90S, double meanS,
                                  double overTimerChance, Map<String, Double> segmentMeanS,
                                  Map<Integer, Double> partyDpsByMob, List<Weakness> weaknesses,
                                  AgentPqPaceCalibration.Source source, int recordsUsed) {
    public AgentPqPaceEstimate {
        segmentMeanS = Collections.unmodifiableMap(new TreeMap<>(segmentMeanS));
        partyDpsByMob = Collections.unmodifiableMap(new TreeMap<>(partyDpsByMob));
        weaknesses = List.copyOf(weaknesses);
    }

    /**
     * MISSES: the member's best attack lands under the profile's weak hit chance on a mob it fights. FRAGILE: the member
     * goes down in fewer touches of the danger mob than the profile allows. SLOW: the party's mean time on a segment is
     * over the profile's limit ({@code mobId}: the type that holds it up longest). UNHITTABLE: nobody can hurt the mob,
     * so the party cannot clear.
     */
    public enum Kind {
        MISSES,
        FRAGILE,
        SLOW,
        UNHITTABLE
    }

    /** @param characterId the member, or 0 for the party as a whole */
    public record Weakness(int characterId, int mobId, String segmentKey, Kind kind) {
    }
}
