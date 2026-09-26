package server.agents.capabilities.combat;

import constants.skills.Paladin;
import constants.skills.WhiteKnight;
import org.junit.jupiter.api.Test;
import server.life.Element;
import server.life.ElementalEffectiveness;
import server.life.Monster;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWeaponChargePolicyTest {
    private static final List<Integer> SWORD_CHARGES = List.of(
            WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.SWORD_ICE_CHARGE,
            WhiteKnight.SWORD_LIT_CHARGE, Paladin.SWORD_HOLY_CHARGE);
    /** Level-30 White Knight charges and a level-20 Holy Charge, as a Paladin holds them. */
    private static final Map<Integer, Integer> DAMAGE = Map.of(
            WhiteKnight.SWORD_FIRE_CHARGE, 150, WhiteKnight.SWORD_ICE_CHARGE, 150,
            WhiteKnight.SWORD_LIT_CHARGE, 125, Paladin.SWORD_HOLY_CHARGE, 170);

    @Test
    void withAChargeUpOnlyThatChargeIsRefreshedAndTheOthersAreSkipped() {
        int active = WhiteKnight.SWORD_ICE_CHARGE;
        assertEquals(AgentWeaponChargePolicy.Decision.REFRESH_ON_TIMER,
                AgentWeaponChargePolicy.decide(active, active, () -> fail("no pick while a charge is up")));
        assertEquals(AgentWeaponChargePolicy.Decision.SKIP,
                AgentWeaponChargePolicy.decide(Paladin.SWORD_HOLY_CHARGE, active, () -> fail("no pick while a charge is up")));
        assertEquals(AgentWeaponChargePolicy.Decision.NOT_A_CHARGE,
                AgentWeaponChargePolicy.decide(Paladin.MAPLE_WARRIOR, active, () -> fail("not a charge")));
    }

    @Test
    void withNoChargeUpOnlyThePickIsCastAndAtOnce() {
        int pick = Paladin.SWORD_HOLY_CHARGE;
        assertEquals(AgentWeaponChargePolicy.Decision.CAST_NOW, AgentWeaponChargePolicy.decide(pick, -1, () -> pick));
        for (int other : List.of(WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.SWORD_ICE_CHARGE, WhiteKnight.SWORD_LIT_CHARGE)) {
            assertEquals(AgentWeaponChargePolicy.Decision.SKIP, AgentWeaponChargePolicy.decide(other, -1, () -> pick));
        }
    }

    @Test
    void picksTheHighestDamageChargeWhenNoMonsterHasAWeakness() {
        List<Monster> snails = List.of(monster(null, null), monster(null, null));
        assertEquals(Paladin.SWORD_HOLY_CHARGE,
                AgentWeaponChargePolicy.pick(SWORD_CHARGES, id -> true, DAMAGE::get, snails));
    }

    @Test
    void picksTheElementTheMonstersAreWeakToAndAvoidsOnesTheyResist() {
        // Two monsters weak to fire, one strong against holy: Fire Charge beats the stronger Holy Charge.
        List<Monster> mobs = List.of(
                monster(Element.FIRE, ElementalEffectiveness.WEAK),
                monster(Element.FIRE, ElementalEffectiveness.WEAK),
                monster(Element.HOLY, ElementalEffectiveness.STRONG));
        assertEquals(WhiteKnight.SWORD_FIRE_CHARGE,
                AgentWeaponChargePolicy.pick(SWORD_CHARGES, id -> true, DAMAGE::get, mobs));
    }

    @Test
    void skipsChargesTheAgentCannotCastAndReportsNoneWhenAllAreOut() {
        List<Monster> snails = List.of(monster(null, null));
        assertEquals(WhiteKnight.SWORD_FIRE_CHARGE, AgentWeaponChargePolicy.pick(SWORD_CHARGES,
                id -> id != Paladin.SWORD_HOLY_CHARGE, DAMAGE::get, snails));
        assertEquals(-1, AgentWeaponChargePolicy.pick(SWORD_CHARGES, id -> false, DAMAGE::get, snails));
    }

    /** A living monster that is {@code effectiveness} to {@code element} and normal to everything else. */
    private static Monster monster(Element element, ElementalEffectiveness effectiveness) {
        Monster monster = mock(Monster.class);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getElementalEffectiveness(any())).thenReturn(ElementalEffectiveness.NORMAL);
        if (element != null) {
            when(monster.getElementalEffectiveness(element)).thenReturn(effectiveness);
        }
        return monster;
    }
}
