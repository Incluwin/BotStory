package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentBuffConsumableSupplyPolicyTest {
    private static final int SNIPER_POTION_ITEM_ID = 2_002_005;
    private static final int WARRIOR_POTION_ITEM_ID = 2_002_004;
    private static final int MAGIC_POTION_ITEM_ID = 2_002_002;

    @Test
    void warriorsCarryAccuracyAndAttackPotionsAtEveryLevel() {
        assertEquals(Map.of(SNIPER_POTION_ITEM_ID, target(), WARRIOR_POTION_ITEM_ID, target()),
                targets(Job.FIGHTER, Job.WARRIOR, 15));
        assertEquals(Map.of(SNIPER_POTION_ITEM_ID, target(), WARRIOR_POTION_ITEM_ID, target()),
                targets(Job.HERO, Job.WARRIOR, 150));
    }

    @Test
    void brawlersCarryAccuracyPotionsOnlyBelowLevelFifty() {
        assertEquals(Map.of(SNIPER_POTION_ITEM_ID, target(), WARRIOR_POTION_ITEM_ID, target()),
                targets(Job.BRAWLER, Job.BRAWLER, 49));
        assertEquals(Map.of(WARRIOR_POTION_ITEM_ID, target()), targets(Job.MARAUDER, Job.BRAWLER, 50));
        assertEquals(Map.of(WARRIOR_POTION_ITEM_ID, target()), targets(Job.GUNSLINGER, Job.GUNSLINGER, 30));
    }

    @Test
    void everyJobCarriesTheAttackPotionForItsDamageTypeEvenWithRageOrMeditation() {
        // Item W.ATT/M.ATT stacks with skill buffs on this server (Character.getStackedBuffedValue).
        assertEquals(Map.of(MAGIC_POTION_ITEM_ID, target()), targets(Job.FP_WIZARD, Job.MAGICIAN, 40));
        assertEquals(Map.of(MAGIC_POTION_ITEM_ID, target()), targets(Job.CLERIC, Job.MAGICIAN, 40));
        assertEquals(Map.of(WARRIOR_POTION_ITEM_ID, target()), targets(Job.HUNTER, Job.BOWMAN, 40));
        assertEquals(Map.of(WARRIOR_POTION_ITEM_ID, target()), targets(Job.ASSASSIN, Job.THIEF, 40));
    }

    @Test
    void beginnersAndAgentsWithBuffPotionsOffCarryNone() {
        assertTrue(targets(Job.BEGINNER, Job.BEGINNER, 9).isEmpty());
        Character fighter = agent(Job.FIGHTER, Job.WARRIOR, 40);
        assertTrue(AgentBuffConsumableSupplyPolicy.targets(fighter, false).isEmpty());
    }

    private static Map<Integer, Integer> targets(Job job, Job style, int level) {
        return AgentBuffConsumableSupplyPolicy.targets(agent(job, style, level), true);
    }

    private static Character agent(Job job, Job style, int level) {
        Character agent = mock(Character.class);
        when(agent.getJob()).thenReturn(job);
        when(agent.getJobStyle()).thenReturn(style);
        when(agent.getLevel()).thenReturn(level);
        return agent;
    }

    private static int target() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.supplies.AgentBuffConsumableSupplyPolicy.TARGET_QUANTITY");
    }
}
