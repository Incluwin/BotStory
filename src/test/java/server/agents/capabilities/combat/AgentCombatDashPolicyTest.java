package server.agents.capabilities.combat;

import constants.skills.Brawler;
import constants.skills.Crusader;
import constants.skills.Hero;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentCombatDashPolicyTest {
    @Test
    void rushChargesThroughItsWholeBox() {
        // Rush level 30: lt (-250,-50) rb (0,0), laid out facing left.
        assertEquals(250, AgentCombatDashPolicy.dashDistancePx(Hero.RUSH, new Rectangle(-250, -50, 250, 50)));
    }

    @Test
    void backspinBlowSlidesBackIntoTheBoxBehind() {
        // Backspin Blow level 20: lt (0,-50) rb (140,0).
        assertEquals(-140, AgentCombatDashPolicy.dashDistancePx(Brawler.BACK_SPIN_BLOW, new Rectangle(0, -50, 140, 50)));
    }

    @Test
    void ordinarySkillsStayPut() {
        assertEquals(0, AgentCombatDashPolicy.dashDistancePx(Crusader.SWORD_PANIC, new Rectangle(-200, -50, 200, 50)));
    }
}
