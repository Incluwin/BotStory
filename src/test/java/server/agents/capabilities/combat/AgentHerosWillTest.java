package server.agents.capabilities.combat;

import client.Character;
import client.Disease;
import client.Skill;
import constants.skills.Hero;
import org.junit.jupiter.api.Test;
import server.agents.integration.SkillGateway;
import server.agents.runtime.AgentRuntimeEntry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 2026-10-15 (P20 run 11): a seduced boss member casts its Hero's Will as a player presses it, but never while sealed
 * (no skill then), without one learned, or with it cooling.
 */
class AgentHerosWillTest {
    private final Character hero = mock(Character.class);
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(hero, null, null);
    private final SkillGateway skills = mock(SkillGateway.class);
    private final Skill herosWill = mock(Skill.class);

    @Test
    void aSealedMemberCastsNothing() {
        when(hero.isAlive()).thenReturn(true);
        when(hero.hasDisease(Disease.SEAL)).thenReturn(true);

        assertFalse(AgentCombatBuffRuntime.tryCastHerosWill(entry, hero, skills));
        verifyNoInteractions(skills);
    }

    @Test
    void oneWithoutHerosWillCastsNothing() {
        when(hero.isAlive()).thenReturn(true);

        assertFalse(AgentCombatBuffRuntime.tryCastHerosWill(entry, hero, skills));
    }

    @Test
    void oneWhoseHerosWillIsCoolingWaits() {
        when(hero.isAlive()).thenReturn(true);
        when(skills.getSkill(Hero.HEROS_WILL)).thenReturn(herosWill);
        when(hero.getSkillLevel(herosWill)).thenReturn((byte) 5);
        when(hero.skillIsCooling(Hero.HEROS_WILL)).thenReturn(true);

        assertFalse(AgentCombatBuffRuntime.tryCastHerosWill(entry, hero, skills));
        verify(herosWill, never()).getEffect(anyInt());
    }

    @Test
    void aDeadMemberCastsNothing() {
        assertFalse(AgentCombatBuffRuntime.tryCastHerosWill(entry, hero, skills));
        verifyNoInteractions(skills);
    }
}
