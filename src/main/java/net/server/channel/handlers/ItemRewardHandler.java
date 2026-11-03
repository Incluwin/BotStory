/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.channel.handlers;

import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.Server;
import server.ItemInformationProvider;
import server.ItemInformationProvider.RewardItem;
import tools.PacketCreator;
import tools.Pair;
import tools.Randomizer;

import java.util.List;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * @author Jay Estrella
 * @author kevintjuh93
 */
public final class ItemRewardHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        byte slot = (byte) p.readShort();
        int itemId = p.readInt(); // will load from xml I don't care.

        Item it = c.getPlayer().getInventory(InventoryType.USE).getItem(slot);   // null check here thanks to Thora
        if (it == null || it.getItemId() != itemId || c.getPlayer().getInventory(InventoryType.USE).countById(itemId) < 1) {
            return;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Pair<Integer, List<RewardItem>> rewards = ii.getItemReward(itemId);
        // one weighted pick, each row's share its prob (rolling every row against the total gave nothing on about a
        // third of the clicks and favored the first rows)
        RewardItem reward = rewards.getLeft() <= 0 ? null : pick(rewards.getRight(), Randomizer.nextInt(rewards.getLeft()));
        if (reward != null && !InventoryManipulator.checkSpace(c, reward.itemid, reward.quantity, "")) {
            c.sendPacket(PacketCreator.getShowInventoryFull());
        } else if (reward != null) {
            if (ItemConstants.getInventoryType(reward.itemid) == InventoryType.EQUIP) {
                final Item item = ii.getEquipById(reward.itemid);
                if (reward.period != -1) {
                    // Item.wz reward periods are minutes: the box equips carry 7200 (5 days), which the old
                    // period * 60 * 60 * 10 cut to 3 days
                    item.setExpiration(currentServerTime() + rewardPeriodMillis(reward.period));
                }
                InventoryManipulator.addFromDrop(c, item, false);
            } else {
                InventoryManipulator.addById(c, reward.itemid, reward.quantity, "", -1);
            }
            InventoryManipulator.removeById(c, InventoryType.USE, itemId, 1, false, false);
            if (reward.worldmsg != null) {
                String msg = reward.worldmsg;
                msg.replaceAll("/name", c.getPlayer().getName());
                msg.replaceAll("/item", ii.getName(reward.itemid));
                Server.getInstance().broadcastMessage(c.getWorld(), PacketCreator.serverNotice(6, msg));
            }
        }
        c.sendPacket(PacketCreator.enableActions());
    }

    /** The row {@code roll} (0 up to the total of the probs) lands on; null past the last row. */
    static RewardItem pick(List<RewardItem> rewards, int roll) {
        for (RewardItem reward : rewards) {
            roll -= reward.prob;
            if (roll < 0) {
                return reward;
            }
        }
        return null;
    }

    static long rewardPeriodMillis(int periodMinutes) {
        return MINUTES.toMillis(periodMinutes);
    }
}
