package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentAttackRoute;
import server.agents.capabilities.combat.AgentAttackTransactionResult;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqSigilCast.Outcome;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqSigils.Archetype;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWKPQ plan B3: a member turns a sigil by casting one of its skills for real, then hitting the sigil with that skill's
 * id. Attacks come first (swung at the sigil with no monster), then summons, then buffs; Meso Explosion never; a cast
 * whose area does not meet the sigil's does not count; a Paladin puts a charge up for Charged Blow; a member held by
 * its own attack lock waits.
 */
class AgentCwkpqSigilCastTest {
    private static final Point SIGIL_AT = new Point(63, 86);
    private final AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
    private final Reactor sigil = mock(Reactor.class);
    private final FakeCasting casting = new FakeCasting();

    AgentCwkpqSigilCastTest() {
        when(sigil.getPosition()).thenReturn(SIGIL_AT);
        when(sigil.getObjectId()).thenReturn(77);
    }

    @Test
    void attacksComeFirstThenSummonsThenBuffsAndNeverMesoExplosion() {
        Character shadower = member(Job.SHADOWER, 4_211_006, 4_211_001, 4_211_004);
        assertEquals(List.of(4_211_004, 4_211_001), AgentCwkpqSigilCast.candidates(shadower, Archetype.THIEF));

        Character bowmaster = member(Job.BOWMASTER, 3_111_005, 3_111_006);
        assertEquals(List.of(3_111_006, 3_111_005), AgentCwkpqSigilCast.candidates(bowmaster, Archetype.BOWMAN));

        Character nightLord = member(Job.NIGHTLORD, 4_111_002, 4_111_005);
        assertEquals(List.of(4_111_005, 4_111_002), AgentCwkpqSigilCast.candidates(nightLord, Archetype.THIEF));
        assertTrue(AgentCwkpqSigilCast.candidates(nightLord, Archetype.PIRATE).isEmpty(), "not its archetype");
    }

    @Test
    void anAttackIsSwungAtTheSigilThenTheSigilIsHitWithItsSkill() {
        Character hermit = member(Job.HERMIT, 4_111_005);

        assertEquals(Outcome.CAST, AgentCwkpqSigilCast.cast(casting, entry, hermit, sigil, Archetype.THIEF));

        assertEquals(List.of("plan 4111005", "swing 4111005", "hit 77 4111005"), casting.calls);
    }

    @Test
    void aSwingWhoseAreaMissesTheSigilFallsToTheNextSkill() {
        Character bowmaster = member(Job.BOWMASTER, 3_111_005, 3_111_006);
        casting.boxes.put(3_111_006, new Rectangle(1000, 1000, 50, 50));

        assertEquals(Outcome.CAST, AgentCwkpqSigilCast.cast(casting, entry, bowmaster, sigil, Archetype.BOWMAN));

        assertEquals(List.of("plan 3111006", "summon 3111005", "hit 77 3111005"), casting.calls);
    }

    @Test
    void theSigilsAreaReachesFromTheFloorBelowIt() {
        Rectangle area = AgentCwkpqSigils.area(SIGIL_AT);
        assertTrue(AgentCwkpqSigilCast.reaches(new Rectangle(0, 180, 80, 20), SIGIL_AT, area),
                "a swing on the floor 100 px below meets it");
        assertFalse(AgentCwkpqSigilCast.reaches(new Rectangle(0, 260, 80, 20), SIGIL_AT, area), "too far below");
        assertTrue(AgentCwkpqSigilCast.reaches(new Rectangle(SIGIL_AT.x, SIGIL_AT.y, 1, 1), SIGIL_AT, area),
                "no area of its own: held close");
    }

    @Test
    void aPaladinPutsAChargeUpForChargedBlowAndCastsOnALaterTick() {
        Character paladin = member(Job.PALADIN, 1_211_002);
        casting.needsCharge.add(1_211_002);

        assertEquals(Outcome.CHARGING, AgentCwkpqSigilCast.cast(casting, entry, paladin, sigil, Archetype.WARRIOR));
        assertEquals(List.of("plan 1211002", "charge"), casting.calls);

        casting.needsCharge.clear();
        assertEquals(Outcome.CAST, AgentCwkpqSigilCast.cast(casting, entry, paladin, sigil, Archetype.WARRIOR));
    }

    @Test
    void aMemberHeldByItsAttackLockWaitsAndOneWithNoSkillSaysSo() {
        Character hermit = member(Job.HERMIT, 4_111_005);
        casting.deferred = true;
        assertEquals(Outcome.NOT_READY, AgentCwkpqSigilCast.cast(casting, entry, hermit, sigil, Archetype.THIEF));
        assertFalse(casting.calls.stream().anyMatch(call -> call.startsWith("hit")));

        assertEquals(Outcome.NO_SKILL, AgentCwkpqSigilCast.cast(casting, entry, member(Job.HERMIT), sigil,
                Archetype.THIEF));
    }

    @Test
    void aProjectileStandsBackAndEveryOtherCastStandsClose() {
        casting.rangedSkills.add(3_111_006);
        assertEquals(AgentCwkpqSigilCast.RANGED_STAND_PX, AgentCwkpqSigilCast.standOffsetPx(casting,
                member(Job.BOWMASTER, 3_111_006), Archetype.BOWMAN));
        assertEquals(AgentCwkpqSigilCast.CLOSE_STAND_PX, AgentCwkpqSigilCast.standOffsetPx(casting,
                member(Job.BISHOP, 2_001_005), Archetype.MAGICIAN));
        assertEquals(0, AgentCwkpqSigilCast.standOffsetPx(casting, member(Job.BISHOP), Archetype.MAGICIAN));
    }

    private static Character member(Job job, int... skills) {
        Character member = mock(Character.class);
        when(member.getJob()).thenReturn(job);
        when(member.getSkillLevel(anyInt())).thenReturn(0);
        for (int skill : skills) {
            when(member.getSkillLevel(skill)).thenReturn(10);
        }
        return member;
    }

    private static final class FakeCasting implements AgentCwkpqSigilCast.Casting {
        final List<String> calls = new ArrayList<>();
        final Map<Integer, Rectangle> boxes = new HashMap<>();
        final Set<Integer> needsCharge = new HashSet<>();
        final Set<Integer> rangedSkills = new HashSet<>();
        boolean deferred;
        private String rejection;

        @Override
        public AgentAttackPlan planAt(Character member, Point aim, int skillId) {
            calls.add("plan " + skillId);
            if (needsCharge.contains(skillId)) {
                rejection = "needs-charge";
                return null;
            }
            Rectangle box = boxes.getOrDefault(skillId, new Rectangle(aim.x - 40, aim.y + 60, 120, 60));
            return new AgentAttackPlan(skillId, 10, 1, box, List.of(), AgentAttackRoute.CLOSE, 0, 0, 0, 0, 4, 0, 600,
                    null);
        }

        @Override
        public String lastRejection() {
            return rejection;
        }

        @Override
        public AgentAttackTransactionResult attackAtNothing(AgentRuntimeEntry entry, Character member,
                                                            AgentAttackPlan plan) {
            calls.add("swing " + plan.skillId);
            return new AgentAttackTransactionResult(deferred ? AgentAttackTransactionResult.Status.DEFERRED
                    : AgentAttackTransactionResult.Status.COMMITTED, deferred
                    ? AgentAttackTransactionResult.Reason.ATTACK_COOLDOWN : AgentAttackTransactionResult.Reason.NONE,
                    0, plan.skillId, List.of(), 0, 0, 0L);
        }

        @Override
        public boolean summon(AgentRuntimeEntry entry, Character member, int skillId) {
            calls.add("summon " + skillId);
            return true;
        }

        @Override
        public boolean buff(AgentRuntimeEntry entry, Character member, int skillId) {
            calls.add("buff " + skillId);
            return true;
        }

        @Override
        public boolean chakra(AgentRuntimeEntry entry, Character member, int skillId) {
            calls.add("chakra " + skillId);
            return true;
        }

        @Override
        public boolean charge(AgentRuntimeEntry entry, Character member) {
            calls.add("charge");
            return true;
        }

        @Override
        public boolean ranged(Character member, int skillId) {
            return rangedSkills.contains(skillId);
        }

        @Override
        public boolean hitWithSkill(Character member, int reactorObjectId, int skillId) {
            calls.add("hit " + reactorObjectId + " " + skillId);
            return true;
        }
    }
}
