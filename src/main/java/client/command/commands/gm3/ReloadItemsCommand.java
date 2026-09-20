package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.ItemInformationProvider;
import tools.PacketCreator;

/**
 * Flush ItemInformationProvider caches so new !item spawns and new drops
 * read fresh Item.wz / Character.wz / String.wz values.
 *
 * Already-equipped items retain their per-instance stats — those live on
 * the Equip object and persist to inventoryequipment, independent of WZ.
 * We broadcast an appearance refresh so String.wz name/desc changes and
 * cosmetic WZ changes become visible without a relog.
 *
 * Pass 'all' to broadcast the refresh for every connected channel player.
 */
public class ReloadItemsCommand extends Command {
    {
        setDescription("Reload Item/Character/String WZ caches. Syntax: !reloaditems [all]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        long t0 = System.currentTimeMillis();
        ItemInformationProvider.getInstance().clearCaches();
        long dt = System.currentTimeMillis() - t0;

        boolean allPlayers = params.length >= 1 && "all".equalsIgnoreCase(params[0]);
        int refreshed = refreshLook(player);
        if (allPlayers) {
            try {
                for (Character other : c.getChannelServer().getPlayerStorage().getAllCharacters()) {
                    if (other != player) {
                        refreshed += refreshLook(other);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        player.dropMessage(5, String.format(
                "Reloaded items in %d ms — refreshed %d character look(s). "
                + "Newly-spawned items use the new WZ stats; existing equips "
                + "keep their stored stats (re-spawn via !item to test).",
                dt, refreshed));
    }

    private static int refreshLook(Character chr) {
        Inventory equipped = chr.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) return 0;
        int count = 0;
        for (Item item : equipped.list()) {
            if (item instanceof Equip) count++;
        }
        chr.equipChanged();
        chr.getMap().broadcastMessage(chr,
                PacketCreator.updateCharLook(chr.getClient(), chr), false);
        return count;
    }
}
