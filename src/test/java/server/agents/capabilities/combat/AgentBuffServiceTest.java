package server.agents.capabilities.combat;

import client.BuffStat;
import client.Character;
import client.Job;
import net.server.PlayerBuffValueHolder;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import tools.Pair;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentBuffServiceTest {
    @Test
    void shouldOnlyUseMatkForMagesAndWatkForNonMages() {
        Character mage = mock(Character.class);
        when(mage.getJobStyle()).thenReturn(Job.MAGICIAN);

        Character nonMage = mock(Character.class);
        when(nonMage.getJobStyle()).thenReturn(Job.WARRIOR);

        assertTrue(AgentBuffService.isRelevantBuffStat(mage, BuffStat.MATK));
        assertFalse(AgentBuffService.isRelevantBuffStat(mage, BuffStat.WATK));

        assertFalse(AgentBuffService.isRelevantBuffStat(nonMage, BuffStat.MATK));
        assertTrue(AgentBuffService.isRelevantBuffStat(nonMage, BuffStat.WATK));

        // Magic accuracy ignores ACC buffs (CombatFormulaProvider), so a Sniper Potion does nothing for a mage.
        assertFalse(AgentBuffService.isRelevantBuffStat(mage, BuffStat.ACC));
        assertTrue(AgentBuffService.isRelevantBuffStat(nonMage, BuffStat.ACC));
    }

    @Test
    void attackPotionStacksOnRageButNotOnAnotherAttackPotion() {
        Character fighter = mock(Character.class);
        when(fighter.getJobStyle()).thenReturn(Job.WARRIOR);
        when(fighter.getBuffedValue(BuffStat.WATK)).thenReturn(20);
        PlayerBuffValueHolder rage = new PlayerBuffValueHolder(0, activeBuff(true, BuffStat.WATK, 20));
        when(fighter.getAllBuffs()).thenReturn(List.of(rage));
        StatEffect warriorPotion = fxWith(BuffStat.WATK, 5);

        assertTrue(AgentBuffService.needsAnyBuffStat(null, fighter, warriorPotion));

        PlayerBuffValueHolder drunk = new PlayerBuffValueHolder(0, activeBuff(false, BuffStat.WATK, 5));
        when(fighter.getAllBuffs()).thenReturn(List.of(rage, drunk));

        assertFalse(AgentBuffService.needsAnyBuffStat(null, fighter, warriorPotion));
    }

    @Test
    void accuracyPotionIsDrunkOnlyWhenItLiftsTheHitChanceOnTheCurrentTarget() {
        // King Slime: Lv40, avoid 10. A Lv29 warrior at DEX 26 sits on the 1% floor even with +5 ACC.
        Monster kingSlime = mock(Monster.class);
        when(kingSlime.getLevel()).thenReturn(40);
        when(kingSlime.getAvoidability()).thenReturn(10);
        StatEffect sniperPotion = fxWith(BuffStat.ACC, 5);

        Character lowDex = warrior(29, 26);
        AgentRuntimeEntry lowDexEntry = new AgentRuntimeEntry(lowDex, null, null);
        AgentGrindTargetStateRuntime.setTarget(lowDexEntry, kingSlime);
        assertFalse(AgentBuffService.needsAnyBuffStat(lowDexEntry, lowDex, sniperPotion));

        Character dex40 = warrior(29, 40);
        AgentRuntimeEntry dex40Entry = new AgentRuntimeEntry(dex40, null, null);
        AgentGrindTargetStateRuntime.setTarget(dex40Entry, kingSlime);
        assertTrue(AgentBuffService.needsAnyBuffStat(dex40Entry, dex40, sniperPotion));
    }

    private static Character warrior(int level, int dex) {
        Character warrior = mock(Character.class);
        when(warrior.getJobStyle()).thenReturn(Job.WARRIOR);
        when(warrior.getLevel()).thenReturn(level);
        when(warrior.getTotalDex()).thenReturn(dex);
        when(warrior.getTotalLuk()).thenReturn(4);
        when(warrior.getBuffedValue(BuffStat.ACC)).thenReturn(null); // a mock's Integer default is 0, i.e. "buffed"
        return warrior;
    }

    private static StatEffect activeBuff(boolean skill, BuffStat stat, int value) {
        StatEffect fx = fxWith(stat, value);
        when(fx.isSkill()).thenReturn(skill);
        return fx;
    }

    @Test
    void cheapCapSkipsAtkBuffsStrongerThanTwelve() {
        Character warrior = mock(Character.class);
        when(warrior.getJobStyle()).thenReturn(Job.WARRIOR);

        Character mage = mock(Character.class);
        when(mage.getJobStyle()).thenReturn(Job.MAGICIAN);

        // +12 WATK is the cap: kept.
        assertFalse(AgentBuffService.exceedsCheapAtkCap(warrior, fxWith(BuffStat.WATK, 12)));
        // +13 WATK exceeds the cap: skipped in cheap mode.
        assertTrue(AgentBuffService.exceedsCheapAtkCap(warrior, fxWith(BuffStat.WATK, 13)));
        // +20 MATK for a mage exceeds the cap.
        assertTrue(AgentBuffService.exceedsCheapAtkCap(mage, fxWith(BuffStat.MATK, 20)));

        // Irrelevant stat for the job is ignored: a warrior never cares about MATK.
        assertFalse(AgentBuffService.exceedsCheapAtkCap(warrior, fxWith(BuffStat.MATK, 99)));
        // Non-atk stats are never capped.
        assertFalse(AgentBuffService.exceedsCheapAtkCap(warrior, fxWith(BuffStat.ACC, 99)));
    }

    private static StatEffect fxWith(BuffStat stat, int value) {
        StatEffect fx = mock(StatEffect.class);
        when(fx.getStatups()).thenReturn(List.of(new Pair<>(stat, value)));
        return fx;
    }
}
