package server.agents.capabilities.partyquest.pace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The theory both the estimate and the calibration use: kill rates, water-fill times and term lookups. Pure. */
final class AgentPqPaceModel {
    private AgentPqPaceModel() {
    }

    /**
     * Kills per second of a quota's mob: each member with damage on it kills one every
     * {@code seek + mobHp / (combat x dps)} seconds, the human leader at the quota's credit and everyone else (a human
     * beside him too) in full. 0 when nobody can hurt it.
     */
    static double killsPerSecond(AgentPqPaceSegment.Quota quota, List<AgentPqMemberPower> members, double seekMs,
                                 double combat) {
        double seekSeconds = Math.max(0.0d, seekMs) / 1000.0d;
        double rate = 0.0d;
        if (!(combat > 0.0d)) return 0.0d;
        for (AgentPqMemberPower member : members) {
            double dps = member.dps(quota.mobId());
            if (!(dps > 0.0d)) continue;
            double secondsPerKill = seekSeconds + quota.mobHp() / (combat * dps);
            rate += (member.humanLeader() ? quota.leaderCredit() : 1.0d) / secondsPerKill;
        }
        return rate;
    }

    /**
     * The water-fill of a clear: each fighter (every member but a human leader the clear parks) works the live type the
     * assigner gives it until a type dies, then they choose again. {@code seconds} is infinite when a live type has no
     * damage from any fighter; those types are named.
     */
    record Fill(double seconds, int lastMobId, List<Integer> unhittable) {
        boolean clearable() {
            return Double.isFinite(seconds);
        }
    }

    static Fill waterFill(AgentPqPaceSegment.Clear clear, List<AgentPqMemberPower> members) {
        List<AgentPqMemberPower> fighters = members.stream().filter(clear::fights).toList();
        Map<Integer, Double> hp = new TreeMap<>();
        clear.counts().forEach((mobId, count) -> hp.put(mobId, (double) count * clear.hp().get(mobId)));
        List<Integer> ids = memberIds(fighters);
        double seconds = 0.0d;
        int last = 0;
        while (!hp.isEmpty()) {
            Map<Integer, Map<Integer, Double>> damage = new TreeMap<>();
            for (int index = 0; index < fighters.size(); index++) {
                Map<Integer, Double> byMob = new TreeMap<>();
                for (int mobId : hp.keySet()) byMob.put(mobId, fighters.get(index).dps(mobId));
                damage.put(ids.get(index), byMob);
            }
            Map<Integer, Double> rate = rates(damage, damage.isEmpty() ? Map.of() : clear.assigner().assign(damage), hp);
            if (rate.isEmpty()) rate = rates(damage, bestTypes(damage), hp);
            if (rate.isEmpty()) {
                List<Integer> unhittable = new ArrayList<>();
                for (int mobId : hp.keySet()) {
                    if (fighters.stream().noneMatch(member -> member.dps(mobId) > 0.0d)) unhittable.add(mobId);
                }
                return new Fill(Double.POSITIVE_INFINITY, 0, List.copyOf(unhittable.isEmpty()
                        ? new ArrayList<>(hp.keySet()) : unhittable));
            }
            int first = 0;
            double step = Double.POSITIVE_INFINITY;
            for (Map.Entry<Integer, Double> entry : rate.entrySet()) {
                double time = hp.get(entry.getKey()) / entry.getValue();
                if (time < step) {
                    step = time;
                    first = entry.getKey();
                }
            }
            seconds += step;
            for (Map.Entry<Integer, Double> entry : rate.entrySet()) {
                double left = entry.getKey() == first ? 0.0d : hp.get(entry.getKey()) - entry.getValue() * step;
                if (left <= 1e-9 * clear.hp().get(entry.getKey())) {
                    hp.remove(entry.getKey());
                    last = entry.getKey();
                } else {
                    hp.put(entry.getKey(), left);
                }
            }
        }
        return new Fill(seconds, last, List.of());
    }

    /** Positive damage per live type from the members assigned to it. */
    private static Map<Integer, Double> rates(Map<Integer, Map<Integer, Double>> damage, Map<Integer, Integer> choice,
                                              Map<Integer, Double> hp) {
        Map<Integer, Double> rate = new TreeMap<>();
        choice.forEach((memberId, mobId) -> {
            Map<Integer, Double> byMob = damage.get(memberId);
            double dps = byMob == null || mobId == null ? 0.0d : byMob.getOrDefault(mobId, 0.0d);
            if (dps > 0.0d && hp.containsKey(mobId)) rate.merge(mobId, dps, Double::sum);
        });
        return rate;
    }

    /** Each member on the live type it hurts most (the fallback when the assigner leaves every live type idle). */
    private static Map<Integer, Integer> bestTypes(Map<Integer, Map<Integer, Double>> damage) {
        Map<Integer, Integer> choice = new TreeMap<>();
        damage.forEach((memberId, byMob) -> byMob.entrySet().stream()
                .filter(entry -> entry.getValue() > 0.0d)
                .max(Map.Entry.comparingByValue())
                .ifPresent(best -> choice.put(memberId, best.getKey())));
        return choice;
    }

    /** Character ids when they are distinct (as the live assigner sees them), else list positions. */
    private static List<Integer> memberIds(List<AgentPqMemberPower> members) {
        Set<Integer> seen = new HashSet<>();
        List<Integer> ids = new ArrayList<>();
        for (AgentPqMemberPower member : members) {
            if (!seen.add(member.characterId())) {
                List<Integer> positions = new ArrayList<>();
                for (int index = 0; index < members.size(); index++) positions.add(index);
                return positions;
            }
            ids.add(member.characterId());
        }
        return ids;
    }

    /**
     * Calibrated term values: a leader term from the leader map, any other from the companion map, else the profile's
     * prior. Built once per estimate or fit (the leader-term set walks every party size's segments).
     */
    static final class Terms {
        private final String questKey;
        private final java.util.Set<String> leaderTerms;
        private final Map<String, Double> priors;
        private final Map<String, Double> companion;
        private final Map<String, Double> leader;

        Terms(AgentPqPaceProfile profile, Map<String, Double> companion, Map<String, Double> leader) {
            this.questKey = profile.questKey();
            this.leaderTerms = profile.leaderTerms();
            this.priors = profile.priors();
            this.companion = companion;
            this.leader = leader;
        }

        Terms(AgentPqPaceProfile profile, AgentPqPaceCalibration calibration) {
            this(profile, calibration.companion(), calibration.leader());
        }

        boolean isLeaderTerm(String term) {
            return leaderTerms.contains(term);
        }

        double value(String term) {
            Double value = (leaderTerms.contains(term) ? leader : companion).get(term);
            if (value == null) value = priors.get(term);
            if (value == null) throw new IllegalStateException("no value for pace term " + term + " of " + questKey);
            return value;
        }
    }
}
