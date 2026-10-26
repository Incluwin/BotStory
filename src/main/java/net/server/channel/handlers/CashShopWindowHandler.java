package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.packet.Packet;
import server.CashShop;
import server.cashshop.CashShopWindowCatalog;
import server.cashshop.CashShopWindowPackets;
import server.cashshop.CashShopWindowPurchase;
import server.mods.ServerMods;
import server.mods.features.FeatureHandshake;
import tools.PacketCreator;

/**
 * The Cash Shop window (Recv opcode CASHSHOP_WINDOW 0x11A, docs/CASH_SHOP_WINDOW.md): the catalogue on demand,
 * purchases, and the way through to the stock Cash Shop. Ignored unless the cash_shop_window mod is on and the
 * client has the cash_shop_window feature active, and while the player is inside the stock Cash Shop.
 */
public final class CashShopWindowHandler extends AbstractPacketHandler {

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        if (chr == null || !wanted(c) || chr.getCashShop().isOpened() || p.available() < 1) {
            return;
        }
        switch (p.readByte()) {
            case CashShopWindowPackets.ACTION_REQUEST_CATALOG -> sendIndex(c, chr);
            case CashShopWindowPackets.ACTION_REQUEST_CATEGORY -> sendCategory(c, p);
            case CashShopWindowPackets.ACTION_BUY -> buy(c, chr, p);
            case CashShopWindowPackets.ACTION_BUY_CART -> buyCart(c, chr, p);
            case CashShopWindowPackets.ACTION_ENTER_STAGE -> EnterCashShopHandler.enterStageFromWindow(c);
            default -> {
            }
        }
    }

    /** Whether this client's Cash Shop button opens the window. */
    public static boolean wanted(Client c) {
        return ServerMods.get().isOn(ServerMods.CASH_SHOP_WINDOW)
                && FeatureHandshake.get().isActive(c, ServerMods.CASH_SHOP_WINDOW.id());
    }

    /** Opens the window over the field: no stage change, and the button's input lock released. */
    static void open(Client c, Character chr) {
        c.sendPacket(PacketCreator.enableActions());
        c.sendPacket(CashShopWindowPackets.open(chr));
        sendIndex(c, chr);
    }

    private static void sendIndex(Client c, Character chr) {
        c.sendPacket(CashShopWindowPackets.index(CashShopWindowCatalog.get().index()));
        c.sendPacket(CashShopWindowPackets.cash(chr));
    }

    private static void sendCategory(Client c, InPacket p) {
        if (p.available() < 2) {
            return;
        }
        int tab = p.readByte() & 0xFF;
        int category = p.readByte() & 0xFF;
        for (Packet packet : CashShopWindowPackets.category(tab, category,
                CashShopWindowCatalog.get().bucket(tab, category))) {
            c.sendPacket(packet);
        }
    }

    private static void buy(Client c, Character chr, InPacket p) {
        if (p.available() < 4) {
            return;
        }
        int itemId = p.readInt();
        int currency = p.available() >= 1 ? p.readByte() : CashShop.NX_CREDIT;
        CashShopWindowPurchase.Result result = CashShopWindowPurchase.buy(c, chr, new int[]{itemId}, currency);
        c.sendPacket(CashShopWindowPackets.buyResult(result.code(), itemId));
        c.sendPacket(CashShopWindowPackets.cash(chr));
    }

    private static void buyCart(Client c, Character chr, InPacket p) {
        if (p.available() < 1) {
            return;
        }
        int count = p.readByte() & 0xFF;
        if (count == 0 || count > CashShopWindowPurchase.MAX_CART || p.available() < count * 4L) {
            c.sendPacket(CashShopWindowPackets.cartResult(CashShopWindowPackets.BUY_BAD_CART, 0, 0, 0));
            return;
        }
        int[] itemIds = new int[count];
        for (int i = 0; i < count; i++) {
            itemIds[i] = p.readInt();
        }
        int currency = p.available() >= 1 ? p.readByte() : CashShop.NX_CREDIT;
        CashShopWindowPurchase.Result result = CashShopWindowPurchase.buy(c, chr, itemIds, currency);
        c.sendPacket(CashShopWindowPackets.cartResult(result.code(), result.itemId(), result.delivered(),
                result.spent()));
        c.sendPacket(CashShopWindowPackets.cash(chr));
    }
}
