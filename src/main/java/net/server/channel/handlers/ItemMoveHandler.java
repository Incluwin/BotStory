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

import client.Character;
import client.Client;
import client.inventory.EvolvingRing;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import tools.PacketCreator;

/**
 * @author Matze
 */
public final class ItemMoveHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        p.skip(4);
        if (c.getPlayer().getAutobanManager().getLastSpam(6) + 300 > currentServerTime()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        InventoryType type = InventoryType.getByType(p.readByte());
        short src = p.readShort();     //is there any reason to use byte instead of short in src and action?
        short action = p.readShort();
        short quantity = p.readShort();

        // Locked rings (Quest Ring + Evolving Ring chain) cannot be unequipped, dropped, or moved.
        // The unequip path is the only one a normal player can hit (src < 0 means EQUIPPED slot),
        // but we also guard drop/move so a packet edit can't dislodge them. Equip-into a locked
        // slot from inventory is impossible anyway because the rings are never in EQUIP — they
        // live exclusively in EQUIPPED from character creation onward.
        if (isLockedEquippedItem(c, type, src) || isLockedEquippedItem(c, InventoryType.EQUIPPED, src)) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (action == 0 && server.living.LivingWorldHooks.rosterBookDropped(c.getPlayer(), type, src)) {
            c.sendPacket(PacketCreator.enableActions());
            return; // the Adventurer's Roster stays with its owner
        }

        Item dropping = action == 0 && type != null ? c.getPlayer().getInventory(type).getItem(src) : null;
        short held = dropping == null ? 0 : dropping.getQuantity();
        InventoryManipulator.handleItemMove(c, type, src, action, quantity);
        if (dropping != null && (c.getPlayer().getInventory(type).getItem(src) != dropping || dropping.getQuantity() < held)) {
            server.living.LivingWorldHooks.itemDropped(c.getPlayer(), dropping.getItemId()); // an owner's loot taste (F2)
        }

        c.getPlayer().getAutobanManager().spam(6);
    }

    /**
     * True if `src` references a slot in the EQUIPPED inventory that holds a locked item
     * (Quest Ring, Hunter's Codex monster-book medal, or any Evolving Ring chain entry).
     * Cheap reject — only does inventory lookups when src is in the equipped negative-slot range.
     */
    private static boolean isLockedEquippedItem(Client c, InventoryType type, short src) {
        if (src >= 0) return false;
        if (type != InventoryType.EQUIPPED) return false;
        Inventory inv = c.getPlayer().getInventory(InventoryType.EQUIPPED);
        Item it = inv.getItem(src);
        if (it == null) return false;
        int id = it.getItemId();
        return id == Character.QUEST_RING_ID
                || id == Character.MONSTER_BOOK_MEDAL_ID
                || EvolvingRing.isChainRing(id);
    }
}
