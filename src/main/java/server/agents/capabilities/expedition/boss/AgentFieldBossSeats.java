package server.agents.capabilities.expedition.boss;

import client.Character;
import server.life.Monster;

import java.util.function.BiPredicate;

/**
 * Citizens at field bosses (docs/WORLD_SYSTEMS_PLAN.md N6.3): the one widening of the P13 G14 field engagement rule
 * ({@link AgentBossTargetGate}), where a citizen never hits a listed field boss. The population installs who may
 * (population.CITIZEN_FIELD_BOSS_ENABLED: a camping citizen whose manners and potions allow it now); nobody until then,
 * so the gate is as it was. Memory only, any thread.
 */
public final class AgentFieldBossSeats {
    private static volatile BiPredicate<Character, Monster> allowed = (agent, boss) -> false;

    private AgentFieldBossSeats() {
    }

    public static void install(BiPredicate<Character, Monster> rule) {
        allowed = rule == null ? (agent, boss) -> false : rule;
    }

    /** True when {@code agent} may hit field boss {@code boss} though the boss-run host says no. */
    public static boolean allows(Character agent, Monster boss) {
        try {
            return allowed.test(agent, boss);
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
