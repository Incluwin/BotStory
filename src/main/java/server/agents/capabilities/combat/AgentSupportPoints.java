package server.agents.capabilities.combat;

import client.Character;
import server.agents.capabilities.behavior.AgentImperfectionPolicy;
import server.agents.capabilities.behavior.AgentImperfections;

/**
 * Seam for learned support points (docs/WORLD_SYSTEMS_PLAN.md I1.4): the two numbers the support rules already use, the
 * HP share under which an Agent heals and how far through its duration it recasts a buff. The host installs the rule
 * that learns them; with no rule installed, with one that is off, and in content where nothing is ever calibrated
 * (AgentImperfections.excluded: event instances, boss maps, party quests), each answer is today's number exactly. The
 * bounds here are the engine's, whatever the rule says: a heal point stays within {@link #HEAL_MIN}..{@link #HEAL_MAX}
 * (far above the 0.45 potion floor, above the 0.70 potion point), a survival buff is never recast later than today's 90%.
 */
public final class AgentSupportPoints {
    /** The installed rule. Any Agent tick thread; memory only. */
    public interface Rule {
        boolean on();

        /** The HP share {@code agent} heals under instead of {@code base}. */
        float healRatio(Character agent, float base);

        /** The share of a buff's duration {@code agent} recasts it at instead of {@code base}. */
        double refreshShare(Character agent, double base);

        /** {@code agent} committed an attack. */
        void attacked(Character agent, long nowMs);
    }

    public static final float HEAL_MIN = 0.80f;
    public static final float HEAL_MAX = 0.95f;

    private static volatile Rule rule;

    private AgentSupportPoints() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    private static Rule active(Character agent) {
        Rule current = rule;
        return current == null || agent == null || !current.on() || AgentImperfections.excluded(agent) ? null : current;
    }

    /** The HP share to heal under: {@code base}, or the learned point within the bounds. */
    public static float healRatio(Character agent, float base) {
        Rule current = active(agent);
        return current == null ? base : Math.max(HEAL_MIN, Math.min(HEAL_MAX, current.healRatio(agent, base)));
    }

    /**
     * When a buff cast at {@code now} is due again: {@code today}, what the rules say now, moved by how far the learned
     * share is from the usual 90%. Never before 85% of the duration; a survival buff never after {@code today}, the rest
     * never after it runs out (a lapse F8.3 chose is left alone).
     */
    public static long refreshAt(Character agent, long now, long duration, long today, boolean survival) {
        Rule current = active(agent);
        if (current == null || today > now + duration) {
            return today;
        }
        double shift = current.refreshShare(agent, AgentImperfectionPolicy.BUFF_REFRESH_SHARE)
                - AgentImperfectionPolicy.BUFF_REFRESH_SHARE;
        long latest = survival ? today : now + duration;
        long earliest = Math.min(latest, now + (long) (duration * AgentImperfectionPolicy.BUFF_REFRESH_MIN_SHARE));
        return Math.max(earliest, Math.min(latest, today + Math.round(shift * duration)));
    }

    public static void attacked(Character agent, long nowMs) {
        Rule current = active(agent);
        if (current != null) {
            current.attacked(agent, nowMs);
        }
    }
}
