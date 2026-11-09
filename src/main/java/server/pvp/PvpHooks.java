package server.pvp;

import client.Character;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
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
        if (field != null) {
            field.attack(attacker, attack);
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
