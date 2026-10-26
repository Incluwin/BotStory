/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
                       Matthias Butz <matze@odinms.de>
                       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License version 3
    as published by the Free Software Foundation. You may not use, modify
    or distribute this program under any other version of the
    GNU Affero General Public License.

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
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.Server;
import server.living.LivingWorldHooks;
import server.cashshop.CashShopWindowPackets;
import server.maps.MiniDungeonInfo;
import server.monitoring.CharacterSaveDiagnostics.SaveReason;
import tools.PacketCreator;

/**
 * @author Flav
 *
 * <p>BotStory: a client with the cash_shop_window feature active gets the Cash Shop window over the field instead
 * (docs/CASH_SHOP_WINDOW.md); the window's own Cash Shop button comes back through {@link #enterStageFromWindow}.
 * Both pass the same refusals first.
 */
public class EnterCashShopHandler extends AbstractPacketHandler {
    @Override
    public void handlePacket(InPacket p, Client c) {
        try {
            Character mc = c.getPlayer();
            if (!passes(c, mc)) {
                return;
            }
            if (CashShopWindowHandler.wanted(c)) {
                CashShopWindowHandler.open(c, mc);
                return;
            }
            enterStage(c, mc);
        } catch (Exception e) {
            monitoring.RuntimeFailureLogger.log(e);
        }
    }

    /** The Cash Shop window's Cash Shop button: the stock stage, behind the same refusals. */
    public static void enterStageFromWindow(Client c) {
        try {
            Character mc = c.getPlayer();
            if (mc == null || !passes(c, mc)) {
                return;
            }
            c.sendPacket(CashShopWindowPackets.close());
            enterStage(c, mc);
        } catch (Exception e) {
            monitoring.RuntimeFailureLogger.log(e);
        }
    }

    /**
     * Why this character may not shop right now (an event, a mini-dungeon, a map that refuses the Cash Shop, or
     * already inside it), or null when it may. Empty when the refusal has no message.
     */
    public static String refusal(Character mc) {
        if (mc.cannotEnterCashShop()) {
            return "";
        }
        if (mc.getEventInstance() != null) {
            return "Entering Cash Shop or MTS are disabled when registered on an event.";
        }
        if (MiniDungeonInfo.isDungeonMap(mc.getMapId())) {
            return "Changing channels or entering Cash Shop or MTS are disabled when inside a Mini-Dungeon.";
        }
        if (mc.getCashShop().isOpened()) {
            return "";
        }
        return null;
    }

    private static boolean passes(Client c, Character mc) {
        String refusal = refusal(mc);
        if (refusal == null) {
            return true;
        }
        if (mc.getCashShop().isOpened()) {
            return false;
        }
        if (!refusal.isEmpty()) {
            c.sendPacket(PacketCreator.serverNotice(5, refusal));
        }
        c.sendPacket(PacketCreator.enableActions());
        return false;
    }

    private static void enterStage(Client c, Character mc) throws Exception {
        mc.closePlayerInteractions();
        mc.closePartySearchInteractions();

        mc.unregisterChairBuff();
        Server.getInstance().getPlayerBuffStorage().addBuffsToStorage(mc.getId(), mc.getAllBuffs());
        Server.getInstance().getPlayerBuffStorage().addDiseasesToStorage(mc.getId(), mc.getAllDiseases());
        mc.setAwayFromChannelWorld();
        mc.notifyMapTransferToPartner(-1);
        mc.removeIncomingInvites();
        mc.cancelAllBuffs(true);
        mc.cancelAllDebuffs();
        mc.cancelBuffExpireTask();
        mc.cancelDiseaseExpireTask();
        mc.cancelSkillCooldownTask();
        mc.cancelExpirationTask();

        mc.forfeitExpirableQuests();
        mc.cancelQuestExpirationTask();

        c.sendPacket(PacketCreator.openCashShop(c, false));
        c.sendPacket(PacketCreator.showCashInventory(c));
        c.sendPacket(PacketCreator.showGifts(mc.getCashShop().loadGifts()));
        c.sendPacket(PacketCreator.showWishList(mc, false));
        c.sendPacket(PacketCreator.showCash(mc));

        c.getChannelServer().removePlayer(mc);
        mc.getMap().removePlayer(mc);
        mc.getCashShop().open(true);
        mc.saveCharToDB(true, SaveReason.CASHSHOP);
        LivingWorldHooks.playerEnteredCashShop(mc); // companions idle where they stand meanwhile
    }
}
