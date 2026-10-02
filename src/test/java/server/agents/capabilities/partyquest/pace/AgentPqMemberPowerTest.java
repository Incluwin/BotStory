package server.agents.capabilities.partyquest.pace;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.AgentExpectedDamagePolicy;
import server.life.Monster;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.BOSS;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.MOB_A;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.MOB_B;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.MOB_C;

/**
 * A member's power is read once per profile mob from the damage policy and once from the danger mob's touch; a policy
 * call that throws counts as no damage (and an unknown touch as never fragile), so an estimate never fails a lineup.
 */
class AgentPqMemberPowerTest {
    @Test
    void readsEachProfileMobsBestAttackAndTheDangerMobsTouch() {
        AgentPqMemberPower power = AgentPqMemberPower.of(character(), PaceStubProfile.standard(), false, false,
                new StubSources(false, false));

        assertEquals(543, power.characterId());
        assertEquals("SilentSin", power.name());
        assertEquals(25, power.level());
        assertEquals(Job.FIGHTER.getId(), power.jobId());
        assertClose(Map.of(MOB_A, 236.0d, MOB_B, 205.0d, MOB_C, 259.0d, BOSS, 120.0d), power.dpsByMob());
        assertEquals(Map.of(MOB_A, 0.88d, MOB_B, 0.65d, MOB_C, 1.0d, BOSS, 0.56d), power.hitByMob());
        assertEquals(Map.of(MOB_A, 800.0d, MOB_B, 800.0d, MOB_C, 800.0d, BOSS, 800.0d), power.attackMsByMob());
        assertEquals(1_200.0d / 150.0d, power.touchesToDown(), 1e-12);
        assertFalse(power.human());
    }

    @Test
    void aFailedPolicyCallCountsAsNothingAndAnUnknownTouchAsNeverFragile() {
        AgentPqMemberPower power = AgentPqMemberPower.of(character(), PaceStubProfile.standard(), true, true,
                new StubSources(true, true));

        assertEquals(0.0d, power.dps(BOSS));
        assertEquals(0.0d, power.hit(BOSS));
        assertEquals(0.0d, power.attackMs(BOSS));
        assertEquals(236.0d, power.dps(MOB_A), 1e-9);
        assertEquals(Double.MAX_VALUE, power.touchesToDown());
        assertTrue(power.human() && power.leader());
    }

    @Test
    void nonFiniteOrNegativeReadingsAreStoredAsZero() {
        AgentPqMemberPower power = new AgentPqMemberPower(1, null, false, false, 1, 0,
                Map.of(MOB_A, Double.NaN, MOB_B, -3.0d, MOB_C, Double.POSITIVE_INFINITY), Map.of(), Map.of(),
                Double.NaN);

        assertEquals(Map.of(MOB_A, 0.0d, MOB_B, 0.0d, MOB_C, 0.0d), power.dpsByMob());
        assertEquals("", power.name());
        assertEquals(Double.MAX_VALUE, power.touchesToDown());
        assertEquals(472.0d, new AgentPqMemberPower(1, "x", false, false, 1, 0, Map.of(MOB_A, 236.0d), Map.of(),
                Map.of(), 1.0d).withDpsScaled(2.0d).dps(MOB_A));
    }

    private static void assertClose(Map<Integer, Double> expected, Map<Integer, Double> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((mobId, value) -> assertEquals(value, actual.get(mobId), 1e-9, "mob " + mobId));
    }

    private static Character character() {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(543);
        when(character.getName()).thenReturn("SilentSin");
        when(character.getLevel()).thenReturn(25);
        when(character.getJob()).thenReturn(Job.FIGHTER);
        return character;
    }

    /** SilentSin's policy figures against the stub's mobs; optionally the boss's attack and the touch throw. */
    private record StubSources(boolean bossFails, boolean touchFails) implements AgentPqMemberPower.Sources {
        private static final Map<Integer, double[]> ATTACKS = Map.of(
                MOB_A, new double[]{236.0d * 0.8d, 0.88d}, MOB_B, new double[]{205.0d * 0.8d, 0.65d},
                MOB_C, new double[]{259.0d * 0.8d, 1.0d}, BOSS, new double[]{120.0d * 0.8d, 0.56d});

        @Override
        public Monster monster(int mobId) {
            Monster monster = mock(Monster.class);
            when(monster.getId()).thenReturn(mobId);
            return monster;
        }

        @Override
        public AgentExpectedDamagePolicy.ExpectedAttack attack(Character character, Monster monster) {
            if (bossFails && monster.getId() == BOSS) throw new IllegalStateException("no attack data");
            double[] attack = ATTACKS.get(monster.getId());
            return new AgentExpectedDamagePolicy.ExpectedAttack(attack[0], 800, attack[1]);
        }

        @Override
        public double touchDamage(Character character, Monster monster) {
            if (touchFails) throw new IllegalStateException("no touch data");
            return 150.0d;
        }

        @Override
        public int effectiveMaxHp(Character character) {
            return 1_200;
        }
    }
}
