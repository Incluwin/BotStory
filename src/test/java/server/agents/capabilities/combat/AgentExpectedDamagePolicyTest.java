package server.agents.capabilities.combat;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.skills.Magician;
import constants.skills.Warrior;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.life.Element;
import server.life.Monster;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What an Agent's attacks are worth against one KPQ monster (the lineup at Lakelis ranks by it): hit chance decides
 * it before damage does, as it did live when Rurik swung 95 times at King Slime and never hit, and a magician is
 * weighed by its spell and its magic accuracy, not by its wand swing, and a bowman by its shot. Each attack counts per
 * second of its animation.
 */
class AgentExpectedDamagePolicyTest {
    /** Item.wz 1452002, a War Bow. */
    private static final int WAR_BOW = 1_452_002;

    @Test
    void aWarriorThatCannotHitALigatorIsWorthNothingThere() {
        Monster ligator = monster(32, 10); // Mob.wz 9300001

        double accuracy22 = AgentExpectedDamagePolicy.damagePerSecond(fighter(25), ligator);
        double accuracy34 = AgentExpectedDamagePolicy.damagePerSecond(fighter(40), ligator);

        assertTrue(accuracy34 > 20 * accuracy22, accuracy22 + " vs " + accuracy34);
    }

    @Test
    void aMagicianIsWeighedByItsSpellAndItsMagicAccuracy() {
        Monster necki = monster(21, 15); // Mob.wz 9300000
        Skill magicClaw = mock(Skill.class);
        StatEffect claw = mock(StatEffect.class);
        when(magicClaw.getId()).thenReturn(Magician.MAGIC_CLAW);
        when(magicClaw.getElement()).thenReturn(Element.NEUTRAL);
        when(magicClaw.getEffect(10)).thenReturn(claw);
        when(claw.hasMatk()).thenReturn(true);
        when(claw.getMatk()).thenReturn((short) 30);
        when(claw.getMpCon()).thenReturn((short) 14);
        when(claw.getAttackCount()).thenReturn(2);
        Character withSpell = wizard();
        when(withSpell.getSkills()).thenReturn(Map.of(magicClaw, new Character.SkillEntry((byte) 10, 20, -1L)));
        when(withSpell.getSkillLevel(magicClaw)).thenReturn((byte) 10);

        try (MockedStatic<SkillFactory> skills = mockStatic(SkillFactory.class)) {
            skills.when(() -> SkillFactory.getSkill(Magician.MAGIC_CLAW)).thenReturn(magicClaw);
            double swing = AgentExpectedDamagePolicy.damagePerSecond(wizard(), necki);
            double spell = AgentExpectedDamagePolicy.damagePerSecond(withSpell, necki);

            assertTrue(spell > 20 * swing, swing + " vs " + spell);
        }
    }

    @Test
    void anAttackIsWeighedPerSecondOfItsAnimation() {
        Monster ligator = monster(32, 10);
        Skill slowStrike = mock(Skill.class); // twice the swing's damage, after a three-second charge
        StatEffect strike = mock(StatEffect.class);
        when(slowStrike.getId()).thenReturn(Warrior.POWER_STRIKE);
        when(slowStrike.getElement()).thenReturn(Element.NEUTRAL);
        when(slowStrike.getKeydownTime()).thenReturn(3_000);
        when(slowStrike.getEffect(10)).thenReturn(strike);
        when(strike.hasDamage()).thenReturn(true);
        when(strike.getDamagePercent()).thenReturn(200);
        when(strike.getMpCon()).thenReturn((short) 8);
        Character withStrike = fighter(40);
        when(withStrike.getSkills()).thenReturn(Map.of(slowStrike, new Character.SkillEntry((byte) 10, 20, -1L)));
        when(withStrike.getSkillLevel(slowStrike)).thenReturn((byte) 10);

        try (MockedStatic<SkillFactory> skills = mockStatic(SkillFactory.class)) {
            skills.when(() -> SkillFactory.getSkill(Warrior.POWER_STRIKE)).thenReturn(slowStrike);

            assertEquals(AgentExpectedDamagePolicy.damagePerSecond(fighter(40), ligator),
                    AgentExpectedDamagePolicy.damagePerSecond(withStrike, ligator), 1e-9, "the swing is still its best");
        }
    }

    @Test
    void aBowmanIsWeighedByItsShot() {
        Monster ligator = monster(32, 10);
        InventoryGateway items = mock(InventoryGateway.class);
        when(items.getWeaponType(WAR_BOW)).thenReturn(WeaponType.BOW);
        when(items.isTwoHandedWeapon(WAR_BOW)).thenReturn(true);

        try (MockedStatic<AgentInventoryGatewayRuntime> gateway = mockStatic(AgentInventoryGatewayRuntime.class)) {
            gateway.when(AgentInventoryGatewayRuntime::inventory).thenReturn(items);
            double accuracy22 = AgentExpectedDamagePolicy.damagePerSecond(hunter(25), ligator);
            double accuracy50 = AgentExpectedDamagePolicy.damagePerSecond(hunter(60), ligator);

            assertTrue(accuracy22 > 0.0d && accuracy50 > 20 * accuracy22, accuracy22 + " vs " + accuracy50);
        }
    }

    /** A Lv29 Fighter with no weapon mastery: physical accuracy floor(DEX * 0.8 + 4 * 0.5), 22 at DEX 25. */
    private static Character fighter(int dex) {
        Character bot = bare(29, Job.FIGHTER);
        when(bot.getTotalDex()).thenReturn(dex);
        when(bot.getTotalLuk()).thenReturn(4);
        when(bot.getTotalWatk()).thenReturn(60);
        when(bot.calculateMaxBaseDamage(anyInt())).thenReturn(250);
        when(bot.calculateMinBaseDamage(anyInt(), anyDouble())).thenReturn(100);
        return bot;
    }

    /** A Lv25 Hunter shooting a War Bow: physical accuracy 22 at DEX 25, 50 at DEX 60. */
    private static Character hunter(int dex) {
        Character bot = bare(25, Job.HUNTER);
        Item bow = mock(Item.class);
        when(bow.getItemId()).thenReturn(WAR_BOW);
        when(bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11)).thenReturn(bow);
        when(bot.getTotalDex()).thenReturn(dex);
        when(bot.getTotalLuk()).thenReturn(4);
        when(bot.getTotalWatk()).thenReturn(60);
        when(bot.calculateMaxBaseDamage(anyInt())).thenReturn(250);
        when(bot.calculateMinBaseDamage(anyInt(), anyDouble())).thenReturn(100);
        return bot;
    }

    /** A Lv25 Wizard: DEX 4 swings with physical accuracy 5, INT 100 casts with magic accuracy 50. */
    private static Character wizard() {
        Character bot = bare(25, Job.FP_WIZARD);
        when(bot.getTotalDex()).thenReturn(4);
        when(bot.getTotalLuk()).thenReturn(4);
        when(bot.getTotalInt()).thenReturn(100);
        when(bot.getTotalMagic()).thenReturn(130);
        when(bot.getTotalWatk()).thenReturn(20);
        when(bot.calculateMaxBaseDamage(anyInt())).thenReturn(40);
        when(bot.calculateMinBaseDamage(anyInt(), anyDouble())).thenReturn(10);
        return bot;
    }

    private static Character bare(int level, Job job) {
        Character bot = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.iterator()).thenAnswer(inv -> List.<Item>of().iterator());
        when(bot.getLevel()).thenReturn(level);
        when(bot.getJob()).thenReturn(job);
        return bot;
    }

    private static Monster monster(int level, int avoid) {
        Monster monster = mock(Monster.class);
        when(monster.getLevel()).thenReturn(level);
        when(monster.getAvoidability()).thenReturn(avoid);
        return monster;
    }
}
