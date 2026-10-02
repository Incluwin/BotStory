package server.agents.integration;

import client.BuffStat;
import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.data.AgentDefenseDataProvider;
import server.life.Monster;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDefenseDataProviderTest {
    private final AgentDefenseDataProvider provider = AgentDefenseDataProvider.getInstance();

    @Test
    void shouldFloorWarriorLevelLookupToLowerBucket() {
        assertEquals(57, provider.getStandardPdd(Job.WARRIOR, 14));
    }

    @Test
    void shouldCapLookupsAboveLevel100() {
        assertEquals(309, provider.getStandardPdd(Job.PIRATE, 150));
    }

    @Test
    void shouldPreserveExactTableValues() {
        assertEquals(57, provider.getStandardPdd(Job.WARRIOR, 13));
        assertEquals(116, provider.getStandardPdd(Job.THIEF, 33));
        assertEquals(57, provider.getStandardPdd(Job.WARRIOR, 12));
        assertEquals(85, provider.getStandardPdd(Job.THIEF, 22));
        assertEquals(356, provider.getStandardPdd(Job.WARRIOR, 75));
        assertEquals(331, provider.getStandardPdd(Job.THIEF, 100));
        assertEquals(266, provider.getStandardPdd(Job.MAGICIAN, 100));
    }

    @Test
    void shouldMapCygnusAndAranFamiliesToBaseTables() {
        assertEquals(54, provider.getStandardPdd(Job.DAWNWARRIOR1, 10));
        assertEquals(31, provider.getStandardPdd(Job.BLAZEWIZARD1, 10));
        assertEquals(298, provider.getStandardPdd(Job.WINDARCHER4, 100));
        assertEquals(42, provider.getStandardPdd(Job.NIGHTWALKER1, 10));
        assertEquals(24, provider.getStandardPdd(Job.THUNDERBREAKER1, 10));
        assertEquals(54, provider.getStandardPdd(Job.ARAN1, 10));
        assertEquals(25, provider.getStandardPdd(Job.EVAN1, 8));
    }

    /**
     * The touch a member takes, on average: King Slime (PADamage 130, level 40) against a Lv25 warrior with 60 weapon
     * defense, whose roll spans 149.1-157.6 before the floor. The clear-time estimate divides effective HP by it.
     */
    @Test
    void expectedTouchDamageIsTheMeanOfTheSeededRollAndTheRollIsUnchanged() {
        Character warrior = touchTarget();
        Monster kingSlime = touchingMonster(130);
        double expected = provider.expectedPhysicalTouchDamage(warrior, kingSlime);

        Random seeded = new Random(20_261_001L);
        long total = 0L;
        int rolls = 40_000;
        for (int roll = 0; roll < rolls; roll++) {
            total += provider.rollPhysicalTouchDamage(warrior, kingSlime, 130, seeded);
        }
        assertEquals(total / (double) rolls, expected, 0.05d);
        assertTrue(expected > 149.0d && expected < 158.0d, "expected " + expected);

        double min = config.AgentTuning.doubleValue(
                "server.agents.capabilities.combat.data.AgentDefenseDataProvider.MIN_DAMAGE_FACTOR");
        double max = config.AgentTuning.doubleValue(
                "server.agents.capabilities.combat.data.AgentDefenseDataProvider.MAX_DAMAGE_FACTOR");
        double c = 106 / 2800.0d + 36 / 3200.0d + 4 / 7200.0d + 4 / 3200.0d;
        double a = c + 0.28d;
        double b = 1.3d * (c + 25 / 550.0d + 0.28d);
        Random rolled = new Random(7L);
        Random byHand = new Random(7L);
        for (int roll = 0; roll < 1_000; roll++) {
            double factor = byHand.nextDouble(min, Math.nextUp(max));
            int hand = Math.max(1, (int) Math.floor((130 * 130.0d * factor) - (60 * a) - ((60 - 129) * b)));
            assertEquals(hand, provider.rollPhysicalTouchDamage(warrior, kingSlime, 130, rolled));
        }
        int live = provider.rollPhysicalTouchDamage(warrior, kingSlime);
        assertTrue(live >= 149 && live <= 157, "live roll " + live);
    }

    @Test
    void expectedTouchDamageKeepsTheRollsFloorOfOne() {
        Character warrior = touchTarget();

        assertEquals(1.0d, provider.expectedPhysicalTouchDamage(warrior, touchingMonster(0)), 1e-12);
        // 400 x 0.008..0.0085 plus the defense terms' 13.9 stays within 17.1-17.3: always 17.
        assertEquals(17.0d, provider.expectedPhysicalTouchDamage(warrior, touchingMonster(20)), 1e-9);
    }

    /** A Lv25 Fighter (STR 106, DEX 36) with 60 weapon defense, under his class's standard 129 at that level. */
    private static Character touchTarget() {
        Character warrior = mock(Character.class);
        when(warrior.getJob()).thenReturn(Job.FIGHTER);
        when(warrior.getLevel()).thenReturn(25);
        when(warrior.getTotalWdef()).thenReturn(60);
        when(warrior.getTotalStr()).thenReturn(106);
        when(warrior.getTotalDex()).thenReturn(36);
        when(warrior.getTotalInt()).thenReturn(4);
        when(warrior.getTotalLuk()).thenReturn(4);
        return warrior;
    }

    /** A level-40 mob with 140 accuracy, which always lands on that warrior's 11 avoidability. */
    private static Monster touchingMonster(int physicalAttack) {
        Monster monster = mock(Monster.class);
        when(monster.getPADamage()).thenReturn(physicalAttack);
        when(monster.getLevel()).thenReturn(40);
        when(monster.getAccuracy()).thenReturn(140);
        return monster;
    }

    @Test
    void effectiveMaximumHpCountsMagicGuardUntilEitherPoolRunsOut() {
        Character agent = mock(Character.class);
        when(agent.getCurrentMaxHp()).thenReturn(1_000);
        when(agent.getBuffedValue(BuffStat.MAGIC_GUARD)).thenReturn(80);
        when(agent.getMp()).thenReturn(3_200);
        assertEquals(4_000, AgentDefenseDataProvider.effectiveMaximumHp(agent));

        when(agent.getMp()).thenReturn(10_000);
        assertEquals(5_000, AgentDefenseDataProvider.effectiveMaximumHp(agent));
    }
}
