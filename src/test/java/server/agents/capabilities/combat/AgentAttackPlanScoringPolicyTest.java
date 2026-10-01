package server.agents.capabilities.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import java.util.List;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.state.AgentCapabilityStateRegistry;
import server.life.Monster;

class AgentAttackPlanScoringPolicyTest {
    @Test
    void shouldUseLegacyTieBreakWhenEmptyPlansHaveEqualScore() {
        Character agent = agent();
        AgentAttackPlan slower = plan(2001005, 900);
        AgentAttackPlan faster = plan(1001005, 600);

        assertEquals(faster, AgentAttackPlanScoringPolicy.selectBestAttackPlan(agent, List.of(slower, faster)));
    }

    @Test
    void shouldUseLowerSkillIdWhenCooldownsTie() {
        Character agent = agent();
        AgentAttackPlan higherSkill = plan(2001005, 720);
        AgentAttackPlan lowerSkill = plan(1001005, 720);

        assertEquals(lowerSkill, AgentAttackPlanScoringPolicy.selectBestAttackPlan(agent,
                List.of(higherSkill, lowerSkill)));
    }

    @Test
    void expectedLineDamageIsTheHitChanceTimesTheDamageOnThatMonster() {
        Character agent = agent();
        when(agent.getLevel()).thenReturn(29);
        when(agent.getTotalDex()).thenReturn(20);
        when(agent.getTotalLuk()).thenReturn(20);
        when(agent.getInventory(InventoryType.EQUIPPED).iterator()).thenAnswer(call -> List.of().iterator());
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        when(entry.capabilityStates()).thenReturn(new AgentCapabilityStateRegistry());

        // Same level and no defense: only the avoid differs, and accuracy 26 never misses the one and hits the
        // other 1% of the time.
        double sure = AgentAttackPlanScoringPolicy.expectedLineDamage(entry, agent, monster(0));
        double hopeless = AgentAttackPlanScoringPolicy.expectedLineDamage(entry, agent, monster(100));

        assertTrue(sure > 0.0d, () -> "no damage: " + sure);
        assertEquals(sure * 0.01d, hopeless, 1e-9);
    }

    private static Monster monster(int avoid) {
        Monster monster = mock(Monster.class);
        when(monster.getLevel()).thenReturn(29);
        when(monster.getAvoidability()).thenReturn(avoid);
        return monster;
    }

    private static AgentAttackPlan plan(int skillId, int cooldownMs) {
        return new AgentAttackPlan(skillId, 0, 1, null, List.of(), AgentAttackRoute.CLOSE,
                0, 0, 0, 0, 0, 0, cooldownMs, WeaponType.SWORD1H);
    }

    private static Character agent() {
        Character agent = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(agent.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(agent.getJob()).thenReturn(Job.BEGINNER);
        when(agent.getStr()).thenReturn(100);
        when(agent.getDex()).thenReturn(20);
        when(agent.getLuk()).thenReturn(20);
        when(agent.getInt()).thenReturn(20);
        when(agent.getTotalWatk()).thenReturn(30);
        return agent;
    }
}
