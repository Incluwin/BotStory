package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.bag.StorageBagRequests;

/**
 * The Storage Bag window (Recv opcode BAG_WINDOW 0x119, docs/STORAGE_BAG.md): the client asks, the server decides and
 * answers with the bag's snapshot (Send opcode BAG_WINDOW 0x17E). Ignored unless the storage_bag mod is on and the
 * client's storage_bag feature is active.
 *
 * <pre>
 * byte action  0 OPEN, 1 WITHDRAW, 2 DEPOSIT, 3 ORGANIZE, 4 SET_AUTO, 5 MOVE, 6 CLOSE
 * byte bag     0 ore, 1 scroll, 2 chair, 3 mount
 * WITHDRAW  short bag slot, short inventory slot dropped on (unused: the stack merges like a pickup)
 * DEPOSIT   short inventory type, short inventory slot, short bag slot dropped on (-1: anywhere)
 * SET_AUTO  byte 1 on / 0 off
 * MOVE      short from bag slot, short to bag slot
 * </pre>
 */
public final class StorageBagHandler extends AbstractPacketHandler {
    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }
        int action = p.readByte();
        int bag = p.readByte();
        int a = 0, b = 0, d = 0;
        switch (action) {
            case StorageBagRequests.WITHDRAW, StorageBagRequests.MOVE -> {
                a = p.readShort();
                b = p.readShort();
            }
            case StorageBagRequests.DEPOSIT -> {
                a = p.readShort();
                b = p.readShort();
                d = p.readShort();
            }
            case StorageBagRequests.SET_AUTO -> a = p.readByte();
            default -> {
            }
        }
        StorageBagRequests.handle(chr, action, bag, a, b, d);
    }
}
