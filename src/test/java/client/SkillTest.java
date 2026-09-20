package client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillTest {
    @Test
    void shouldResolveSingleActionSkillAnimation() {
        Skill skill = new Skill(1001004);
        skill.setAction0("blast");

        assertEquals("blast", skill.resolveAnimationAction(1, false));
        assertEquals("blast", skill.resolveAnimationAction(1, true));
    }

    @Test
    void shouldResolveTwoHandedSkillAnimationByWeaponState() {
        Skill skill = new Skill(1121008);
        skill.setAction0("brandish1");
        skill.setAction1("brandish2");

        assertEquals("brandish1", skill.resolveAnimationAction(1, false));
        assertEquals("brandish2", skill.resolveAnimationAction(1, true));
    }

    @Test
    void shouldResolveLevelBasedSkillAnimation() {
        Skill skill = new Skill(2221007);
        skill.addLevelAction(1, "magic1");
        skill.addLevelAction(2, "magic2");

        assertEquals("magic1", skill.resolveAnimationAction(1, false));
        assertEquals("magic2", skill.resolveAnimationAction(2, false));
    }

    @Test
    void shouldReturnNullWhenNoExplicitAnimationActionExists() {
        Skill skill = new Skill(1001005);

        assertNull(skill.resolveAnimationAction(1, false));
    }
}
