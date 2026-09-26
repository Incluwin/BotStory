package server.agents.capabilities.combat;

import client.Character;
import constants.skills.Brawler;
import constants.skills.Buccaneer;
import constants.skills.Marauder;
import constants.skills.Paladin;
import constants.skills.WhiteKnight;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentClientSkillRulesTest {
    private static AgentRuntimeEntry entry() {
        return new AgentRuntimeEntry(mock(Character.class), null, null);
    }

    @Test
    void aLandedBarrageCannotBeRecastRightAwayButDragonStrikeCan() {
        AgentRuntimeEntry entry = entry();
        // Barrage animates for over two seconds; the delay counts from the end of that, so it is still on.
        AgentClientSkillRules.afterLandedHit(entry, Buccaneer.BARRAGE, System.currentTimeMillis() - 2_000, 2_300);
        assertTrue(AgentClientSkillRules.reuseLocked(entry, Buccaneer.BARRAGE));
        assertFalse(AgentClientSkillRules.reuseLocked(entry, Buccaneer.DRAGON_STRIKE));
        // The lock is over once its time has passed.
        AgentClientSkillRules.afterLandedHit(entry, Brawler.DOUBLE_UPPERCUT, System.currentTimeMillis() - 60_000, 1_200);
        assertFalse(AgentClientSkillRules.reuseLocked(entry, Brawler.DOUBLE_UPPERCUT));
    }

    @Test
    void onlyTheListedPirateSkillsLockAndOnlyCorkscrewGrantsInvincibility() {
        AgentRuntimeEntry entry = entry();
        AgentClientSkillRules.afterLandedHit(entry, Buccaneer.DEMOLITION, System.currentTimeMillis(), 3_000);
        assertFalse(AgentClientSkillRules.reuseLocked(entry, Buccaneer.DEMOLITION));
        assertFalse(AgentClientSkillRules.invincible(entry));
        AgentClientSkillRules.afterLandedHit(entry, Brawler.CORKSCREW_BLOW, System.currentTimeMillis(), 900);
        assertTrue(AgentClientSkillRules.invincible(entry));
        assertTrue(AgentClientSkillRules.reuseLocked(entry, Brawler.CORKSCREW_BLOW));
        AgentClientSkillRules.reset(entry);
        assertFalse(AgentClientSkillRules.invincible(entry));
        assertFalse(AgentClientSkillRules.reuseLocked(entry, Brawler.CORKSCREW_BLOW));
    }

    @Test
    void demolitionAndSnatchNeedSuperTransformationNotJustAnyMorph() {
        assertFalse(AgentClientSkillRules.usableInForm(Buccaneer.DEMOLITION, 0));
        assertFalse(AgentClientSkillRules.usableInForm(Buccaneer.SNATCH, Marauder.TRANSFORMATION));
        assertTrue(AgentClientSkillRules.usableInForm(Buccaneer.DEMOLITION, Buccaneer.SUPER_TRANSFORMATION));
        assertTrue(AgentClientSkillRules.usableInForm(Buccaneer.BARRAGE, 0));
    }

    @Test
    void chargedBlowNeedsAChargeOnTheWeaponAndOtherSkillsDoNot() {
        assertFalse(AgentClientSkillRules.chargeReady(WhiteKnight.CHARGE_BLOW, false));
        assertTrue(AgentClientSkillRules.chargeReady(WhiteKnight.CHARGE_BLOW, true));
        assertTrue(AgentClientSkillRules.chargeReady(Paladin.BLAST, false));
    }
}
