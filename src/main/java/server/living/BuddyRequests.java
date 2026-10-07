package server.living;

import client.BuddyList;
import client.BuddylistEntry;
import client.Character;
import client.Client;

/**
 * A bot asking a player to be buddies, the way a player does (P9 buddy friends; probe 8): it lists the player on its
 * own buddy list first, so the player's accept makes them buddies both ways (the stock accept tells the bot, and the
 * player sees it come and go), then the player gets the stock request: the "Buddy request from ..." notice with its
 * accept and decline buttons. The citizens' requests (SocialFabric) and the companions' (CompanionBuddies) both ask here.
 */
public final class BuddyRequests {
    private BuddyRequests() {
    }

    /**
     * True when the request went out. Nothing is asked of a player with no client, who has the bot on their list
     * already (a buddy, or a request still open), or whose list is full.
     */
    public static boolean ask(Character bot, Character player) {
        Client playerClient = player == null ? null : player.getClient();
        Client botClient = bot == null ? null : bot.getClient();
        if (playerClient == null || botClient == null) {
            return false;
        }
        BuddyList theirs = player.getBuddylist();
        if (theirs.contains(bot.getId()) || theirs.isFull()) {
            return false;
        }
        BuddyList own = bot.getBuddylist();
        if (!own.contains(player.getId())) {
            own.put(new BuddylistEntry(player.getName(), "Default Group", player.getId(), playerClient.getChannel(), true));
        }
        theirs.addBuddyRequest(playerClient, bot.getId(), bot.getName(), botClient.getChannel());
        return true;
    }
}
