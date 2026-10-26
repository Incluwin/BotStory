package server.living.population;

import server.living.population.CitizenStore.GuildMeta;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * How close two citizen guilds are, 0..1 (docs/BOT_GUILD_ALLIANCES_PLAN.md), from what the population already keeps, so
 * the same population gives the same ties: circles with members in both guilds (circle mates party up and are buddies;
 * full at {@value #CIRCLES_FULL}), the same cohort (they play at the same hours), where their members live (the overlap
 * of their home-region shares), and the overlap of the level ranges they take.
 */
public final class GuildTies {
    static final double CIRCLE_WEIGHT = 0.40d;
    static final double COHORT_WEIGHT = 0.20d;
    static final double HOME_WEIGHT = 0.25d;
    static final double LEVEL_WEIGHT = 0.15d;
    static final int CIRCLES_FULL = 2;

    private GuildTies() {
    }

    /** One guild as the tie reads it: its meta row and its active members. */
    public record Side(GuildMeta meta, Collection<Citizen> members) { }

    public static double tie(Side a, Side b) {
        return CIRCLE_WEIGHT * Math.min(1d, sharedCircles(a.members(), b.members()) / (double) CIRCLES_FULL)
                + COHORT_WEIGHT * (sameCohort(a.meta(), b.meta()) ? 1d : 0d)
                + HOME_WEIGHT * homeOverlap(a.members(), b.members())
                + LEVEL_WEIGHT * levelOverlap(a.meta(), b.meta());
    }

    /** Circles (non-zero) that have members in both guilds. */
    static int sharedCircles(Collection<Citizen> a, Collection<Citizen> b) {
        Set<Integer> circles = new HashSet<>();
        for (Citizen citizen : a) {
            if (citizen.circleId() > 0) {
                circles.add(citizen.circleId());
            }
        }
        Set<Integer> shared = new HashSet<>();
        for (Citizen citizen : b) {
            if (circles.contains(citizen.circleId())) {
                shared.add(citizen.circleId());
            }
        }
        return shared.size();
    }

    static boolean sameCohort(GuildMeta a, GuildMeta b) {
        return a.cohort() != null && !a.cohort().isBlank() && a.cohort().equals(b.cohort());
    }

    /** The overlap of the two guilds' home-region shares: the sum over regions of the smaller share, 0..1. */
    static double homeOverlap(Collection<Citizen> a, Collection<Citizen> b) {
        Map<String, Double> sharesA = shares(a);
        Map<String, Double> sharesB = shares(b);
        double overlap = 0d;
        for (Map.Entry<String, Double> entry : sharesA.entrySet()) {
            overlap += Math.min(entry.getValue(), sharesB.getOrDefault(entry.getKey(), 0d));
        }
        return Math.min(1d, overlap);
    }

    private static Map<String, Double> shares(Collection<Citizen> members) {
        Map<String, Double> shares = new HashMap<>();
        int counted = 0;
        for (Citizen citizen : members) {
            if (citizen.homeRegion() != null && !citizen.homeRegion().isBlank()) {
                shares.merge(citizen.homeRegion(), 1d, Double::sum);
                counted++;
            }
        }
        if (counted > 0) {
            int total = counted;
            shares.replaceAll((region, count) -> count / total);
        }
        return shares;
    }

    /** The shared part of the two level ranges over the narrower one, 0..1. */
    static double levelOverlap(GuildMeta a, GuildMeta b) {
        int shared = Math.min(a.levelMax(), b.levelMax()) - Math.max(a.levelMin(), b.levelMin());
        int narrower = Math.min(a.levelMax() - a.levelMin(), b.levelMax() - b.levelMin());
        if (shared < 0) {
            return 0d;
        }
        return narrower <= 0 ? 1d : Math.min(1d, shared / (double) narrower);
    }
}
