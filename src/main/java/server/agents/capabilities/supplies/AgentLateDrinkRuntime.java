package server.agents.capabilities.supplies;

import client.Character;
import server.agents.capabilities.behavior.AgentImperfectionPolicy;
import server.agents.capabilities.behavior.AgentImperfections;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

/**
 * F8.2 late potion (docs/BOT_CONCEPT_PLAN.md F8): now and then an autopot HP drink of a citizen or companion goes down
 * 0.3-1.5 s after the hit that called for it, as a player's thumb is a beat late. Only while it is safe
 * (AgentImperfectionPolicy.lateDrinkSafe: HP at 50% or more, no mob in reach that could take it under 40%), asked
 * again at every later hit: unsafe, the drink goes down at once. A due drink goes down on the Agent's next tick
 * ({@link #dueNow}), the 1 s low top-up backstop staying as it was. Any thread.
 */
public final class AgentLateDrinkRuntime {
    private AgentLateDrinkRuntime() {
    }

    /** True while this HP drink waits; false = drink it now (a due or unsafe wait is ended here). */
    public static boolean holds(AgentRuntimeEntry entry, Character agent, long nowMs) {
        return holds(entry, agent, nowMs, AgentImperfectionPolicy::lateDrinkSafe,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    static boolean holds(AgentRuntimeEntry entry, Character agent, long nowMs, Predicate<Character> safe,
                         DoubleSupplier draw) {
        if (entry == null || agent == null) {
            return false;
        }
        AgentPotionSupplyState state = entry.potionSupplyState();
        long due = state.lateHpDrinkAtMs();
        if (due > 0L) {
            if (nowMs < due && safe.test(agent)) {
                return true;
            }
            state.setLateHpDrinkAtMs(0L);
            return false;
        }
        if (!AgentImperfections.on() || !safe.test(agent)
                || !AgentImperfections.slips(agent, AgentImperfections.Slip.LATE_POTION)) {
            return false;
        }
        state.setLateHpDrinkAtMs(nowMs + AgentImperfectionPolicy.lateDrinkDelayMs(draw.getAsDouble()));
        return true;
    }

    /** On the Agent's tick: true when a waiting drink is due now (the caller runs the autopot). */
    public static boolean dueNow(AgentRuntimeEntry entry, long nowMs) {
        long due = entry.potionSupplyState().lateHpDrinkAtMs();
        return due > 0L && nowMs >= due;
    }

    /** The waiting drink is no longer needed (HP came back over the alert meanwhile). */
    public static void cancel(AgentRuntimeEntry entry) {
        entry.potionSupplyState().setLateHpDrinkAtMs(0L);
    }
}
