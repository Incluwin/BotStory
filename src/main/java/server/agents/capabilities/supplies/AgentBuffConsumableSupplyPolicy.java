package server.agents.capabilities.supplies;

import client.Character;
import client.Job;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Buff potions an Agent keeps on hand so shop visits top them up beside its recovery stock. Warriors
 * carry accuracy potions at every level and brawlers until their accuracy skills catch up; every job
 * carries the attack potion for its damage type, since item W.ATT/M.ATT stacks with Rage and
 * Meditation on this server (Character.getStackedBuffedValue). Whether one is drunk is decided in
 * combat by AgentBuffService, against the current target.
 */
public final class AgentBuffConsumableSupplyPolicy {
    private static final int SNIPER_POTION_ITEM_ID = 2_002_005;
    private static final int WARRIOR_POTION_ITEM_ID = 2_002_004;
    private static final int MAGIC_POTION_ITEM_ID = 2_002_002;
    private static final int TARGET_QUANTITY = config.AgentTuning.intValue(
            "server.agents.capabilities.supplies.AgentBuffConsumableSupplyPolicy.TARGET_QUANTITY");
    private static final int BRAWLER_ACCURACY_LEVEL_LIMIT = config.AgentTuning.intValue(
            "server.agents.capabilities.supplies.AgentBuffConsumableSupplyPolicy.BRAWLER_ACCURACY_LEVEL_LIMIT");

    private AgentBuffConsumableSupplyPolicy() {
    }

    /** Item id -> quantity to carry; empty for Beginners and while the Agent's buff potions are off. */
    public static Map<Integer, Integer> targets(Character agent, boolean buffPotionsEnabled) {
        Map<Integer, Integer> targets = new LinkedHashMap<>();
        if (!buffPotionsEnabled || agent == null || agent.getJob() == null) {
            return targets;
        }
        Job style = agent.getJobStyle();
        if (style == Job.BEGINNER) {
            return targets;
        }
        if (style == Job.WARRIOR
                || style == Job.BRAWLER && agent.getLevel() < BRAWLER_ACCURACY_LEVEL_LIMIT) {
            targets.put(SNIPER_POTION_ITEM_ID, TARGET_QUANTITY);
        }
        targets.put(style == Job.MAGICIAN ? MAGIC_POTION_ITEM_ID : WARRIOR_POTION_ITEM_ID, TARGET_QUANTITY);
        return targets;
    }
}
