package server.pvp;

import client.Character;
import constants.game.GameConstants;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AranComboHandler;
import net.server.channel.handlers.CloseRangeDamageHandler;
import server.ItemInformationProvider;
import server.StatEffect;

/**
 * The calls the rest of the server makes into duels. Each returns at once while no duel runs
 * ({@link DuelRegistry#active()}), so the hot paths pay one map check.
 */
public final class PvpHooks {
    private PvpHooks() {
    }

    /** {@code AbstractDealDamageHandler.applyAttack}, for players' and Agents' attacks alike. */
    public static void onAttack(Character attacker, AttackInfo attack) {
        if (!DuelRegistry.active()) {
            return;
        }
        DuelField field = DuelRegistry.of(attacker.getId());
        if (field == null) {
            return;
        }
        int landed = field.attack(attacker, attack);
        if (landed > 0) {
            // after the field's lock is let go: these change the attacker's own buffs and send its client packets
            earnOnHit(attacker, attack, landed);
        }
    }

    /**
     * What a landed duel hit earns, as a monster hit would: combo orbs, energy charge and Aran combo build only when the
     * client lists a monster, so in a duel no Crusader could use Panic or Coma and no Aran its combo skills (practice
     * 2026-11-09: Lv 100+ Crusaders refused every finisher and dealt 4-6% of the model).
     */
    static void earnOnHit(Character attacker, AttackInfo attack, int landed) {
        if (!attack.ranged && !attack.magic) {
            CloseRangeDamageHandler.gainOnHit(attacker, attacker.getClient(), attack.skill, landed);
        }
        if (GameConstants.isAran(attacker.getJob().getId())) {
            for (int i = 0; i < landed; i++) {
                AranComboHandler.countHit(attacker);
            }
        }
    }

    /**
     * Item use and autopot: true refuses a healing item while a duel's heal window since the last drink runs. A
     * refused item is not used up.
     */
    public static boolean refusesDrink(Character user, int itemId) {
        if (!DuelRegistry.active() || user == null) {
            return false;
        }
        DuelField field = DuelRegistry.of(user.getId());
        if (field == null || !heals(itemId)) {
            return false;
        }
        return field.refusesDrink(user);
    }

    static boolean heals(int itemId) {
        StatEffect effect = ItemInformationProvider.getInstance().getItemEffect(itemId);
        return effect != null && (effect.getHp() > 0 || effect.getMp() > 0 || effect.getHpRate() > 0
                || effect.getMpRate() > 0);
    }
}
