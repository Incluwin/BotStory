package server;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.mods.ServerMods;
import soloMapling.ArtificialPlayer.BotHelpers;

/**
 * Which cash items a player may put in a trade window while the {@code cash_item_trade} mod is on (the client's
 * cash_item_trade feature removes its own refusal). Only trades between two people: player stores, hired merchants,
 * Duey and dropped items keep USE_ENFORCE_UNMERCHABLE_CASH, and companions and other bots are never handed one. Pets
 * stay blocked, and so does a ring tied to a partner (couple, friendship, wedding), whose link would break.
 */
public final class CashItemTradePolicy {
    private CashItemTradePolicy() {
    }

    /** Why the item stays out of a trade between these two, or null when it may go in. */
    public static String refusal(Character from, Character to, Item item, boolean cash) {
        if (ItemConstants.isPet(item.getItemId())) {
            return "Pets are not allowed to be traded.";
        }
        if (!cash || !ServerMods.get().isOn(ServerMods.CASH_ITEM_TRADE)) {
            return "Cash items are not allowed to be traded.";
        }
        if (item instanceof Equip equip && equip.getRingId() > 0) {
            return "A ring tied to a partner can't be traded.";
        }
        if (isBot(from) || to == null || isBot(to)) {
            return "Cash items can only be traded between players.";
        }
        return null;
    }

    private static boolean isBot(Character chr) {
        return chr == null || chr.getClient() instanceof BotClient || BotHelpers.isBot(chr);
    }
}
