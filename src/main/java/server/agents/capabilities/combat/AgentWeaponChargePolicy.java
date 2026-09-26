package server.agents.capabilities.combat;

import constants.skills.Paladin;
import constants.skills.WhiteKnight;
import server.life.Element;
import server.life.ElementalEffectiveness;
import server.life.Monster;

import java.util.Collection;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

/**
 * White Knight and Paladin charges: a weapon holds one charge at a time and a new one replaces the
 * old (StatEffect overwrites the singleton WK_CHARGE stat). The buff loop treated every charge as a
 * separate buff with its own timer, so a Paladin went up Ice, Fire and Holy Charge back to back and
 * then recast each on its own clock. The Agent now keeps one charge: the one on the weapon, recast
 * on its timer; with none up, it casts one pick and skips the rest.
 *
 * <p>The pick is the charge the map's monsters are weakest to, since this server only adds charge
 * damage against a weak element ({@code CombatFormulaProvider.applyWkChargeElementalBonus}), and a
 * player does not swing Fire Charge at a fire monster. Ties go to the higher-damage charge, which
 * makes a Paladin's own Holy Charge the default.
 */
public final class AgentWeaponChargePolicy {
    private static final Set<Integer> CHARGE_SKILL_IDS = Set.of(
            WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.BW_FIRE_CHARGE,
            WhiteKnight.SWORD_ICE_CHARGE, WhiteKnight.BW_ICE_CHARGE,
            WhiteKnight.SWORD_LIT_CHARGE, WhiteKnight.BW_LIT_CHARGE,
            Paladin.SWORD_HOLY_CHARGE, Paladin.BW_HOLY_CHARGE);

    /** What the buff loop does with one buff skill. */
    public enum Decision {
        /** Not a charge: the ordinary per-buff timer applies. */
        NOT_A_CHARGE,
        /** This charge is on the weapon: recast it when its timer is due. */
        REFRESH_ON_TIMER,
        /** No charge is up and this is the pick: cast it now, whatever its old timer says. */
        CAST_NOW,
        /** Another charge is up, or this one is not the pick. */
        SKIP
    }

    private AgentWeaponChargePolicy() {
    }

    public static boolean isCharge(int skillId) {
        return CHARGE_SKILL_IDS.contains(skillId);
    }

    /**
     * @param activeChargeSourceId skill id of the charge on the weapon, or -1 for none
     * @param pick                 the charge to cast when none is up; asked only in that case
     */
    public static Decision decide(int skillId, int activeChargeSourceId, IntSupplier pick) {
        if (!isCharge(skillId)) {
            return Decision.NOT_A_CHARGE;
        }
        if (activeChargeSourceId > 0) {
            return activeChargeSourceId == skillId ? Decision.REFRESH_ON_TIMER : Decision.SKIP;
        }
        return pick.getAsInt() == skillId ? Decision.CAST_NOW : Decision.SKIP;
    }

    /**
     * The charge to cast among {@code candidates} the Agent can use now ({@code usable} filters
     * level, weapon and cost), or -1 when none qualifies.
     *
     * @param damageOf a charge's damage percent at the Agent's level, the tie-break
     */
    public static int pick(Collection<Integer> candidates, IntPredicate usable, IntUnaryOperator damageOf,
                           Collection<Monster> monsters) {
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        int bestDamage = Integer.MIN_VALUE;
        for (int skillId : candidates) {
            if (!isCharge(skillId) || !usable.test(skillId)) {
                continue;
            }
            int score = matchupScore(elementOf(skillId), monsters);
            int damage = damageOf.applyAsInt(skillId);
            if (score > bestScore || (score == bestScore && damage > bestDamage)
                    || (score == bestScore && damage == bestDamage && skillId < best)) {
                best = skillId;
                bestScore = score;
                bestDamage = damage;
            }
        }
        return best;
    }

    /** How well an element suits the living monsters: weak counts for, strong and immune against. */
    static int matchupScore(Element element, Collection<Monster> monsters) {
        if (element == null) {
            return 0;
        }
        int score = 0;
        for (Monster monster : monsters) {
            if (monster == null || !monster.isAlive()) {
                continue;
            }
            score += weight(monster.getElementalEffectiveness(element));
        }
        return score;
    }

    static int weight(ElementalEffectiveness effectiveness) {
        if (effectiveness == null) {
            return 0;
        }
        return switch (effectiveness) {
            case WEAK -> 2;
            case STRONG -> -1;
            case IMMUNE -> -2;
            default -> 0;
        };
    }

    /**
     * The element a charge puts on the weapon. Skill.wz gives charges no elemAttr, so this mirrors
     * {@code CombatFormulaProvider.applyWkChargeElementalBonus}.
     */
    static Element elementOf(int skillId) {
        return switch (skillId) {
            case WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.BW_FIRE_CHARGE -> Element.FIRE;
            case WhiteKnight.SWORD_ICE_CHARGE, WhiteKnight.BW_ICE_CHARGE -> Element.ICE;
            case WhiteKnight.SWORD_LIT_CHARGE, WhiteKnight.BW_LIT_CHARGE -> Element.LIGHTING;
            case Paladin.SWORD_HOLY_CHARGE, Paladin.BW_HOLY_CHARGE -> Element.HOLY;
            default -> null;
        };
    }
}
