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
import client.autoban.AutobanFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.inventory.manipulator.KarmaManipulator;
import config.YamlConfig;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.CashItemTradePolicy;
import server.ItemRestrictionPolicy;
import server.Trade;
import server.agents.auth.AgentAuthorityService;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.FieldLimit;
import server.maps.HiredMerchant;
import server.maps.MapObject;
import server.maps.MiniGame;
import server.maps.MiniGameRules;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import server.maps.reservation.CharacterSpaceReservation;
import server.maps.reservation.FreeMarketStorePlacementService;
import server.monitoring.CharacterSaveDiagnostics.SaveReason;
import tools.PacketCreator;

import java.sql.SQLException;
import java.util.Optional;

/**
 * @author Matze
 * @author Ronan - concurrency safety and reviewed minigames
 */
public final class PlayerInteractionHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(PlayerInteractionHandler.class);

    public enum Action {
        CREATE(0),
        INVITE(2),
        DECLINE(3),
        VISIT(4),
        ROOM(5),
        CHAT(6),
        CHAT_THING(8),
        EXIT(0xA),
        OPEN_STORE(0xB),
        OPEN_CASH(0xE),
        SET_ITEMS(0xF),
        SET_MESO(0x10),
        CONFIRM(0x11),
        TRANSACTION(0x14),
        ADD_ITEM(0x16),
        BUY(0x17),
        UPDATE_MERCHANT(0x19),
        UPDATE_PLAYERSHOP(0x1A),
        REMOVE_ITEM(0x1B),
        BAN_PLAYER(0x1C),
        MERCHANT_THING(0x1D),
        OPEN_THING(0x1E),
        PUT_ITEM(0x21),
        MERCHANT_BUY(0x22),
        TAKE_ITEM_BACK(0x26),
        MAINTENANCE_OFF(0x27),
        MERCHANT_ORGANIZE(0x28),
        CLOSE_MERCHANT(0x29),
        REAL_CLOSE_MERCHANT(0x2A),
        MERCHANT_MESO(0x2B),
        SOMETHING(0x2D),
        VIEW_VISITORS(0x2E),
        VIEW_BLACKLIST(0x2F),
        ADD_TO_BLACKLIST(0x30),
        REMOVE_FROM_BLACKLIST(0x31),
        REQUEST_TIE(0x32),
        ANSWER_TIE(0x33),
        GIVE_UP(0x34),
        EXIT_AFTER_GAME(0x38),
        CANCEL_EXIT_AFTER_GAME(0x39),
        READY(0x3A),
        UN_READY(0x3B),
        EXPEL(0x3C),
        START(0x3D),
        GET_RESULT(0x3E),
        SKIP(0x3F),
        MOVE_OMOK(0x40),
        SELECT_CARD(0x44);
        final byte code;

        Action(int code) {
            this.code = (byte) code;
        }

        public byte getCode() {
            return code;
        }
    }

    private static int establishMiniroomStatus(Character chr, boolean isMinigame) {
        if (isMinigame && FieldLimit.CANNOTMINIGAME.check(chr.getMap().getFieldLimit())) {
            return 11;
        }

        if (chr.getChalkboard() != null) {
            return 13;
        }

        if (chr.getEventInstance() != null) {
            return 5;
        }

        return 0;
    }

    @Override
    public final void handlePacket(InPacket p, Client c) {
        if (!c.tryacquireClient()) {    // thanks GabrielSin for pointing dupes within player interactions
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        try {
            byte mode = p.readByte();
            final Character chr = c.getPlayer();

            if (mode == Action.CREATE.getCode()) {
                if (!chr.isAlive()) {    // thanks GabrielSin for pointing this
                    chr.sendPacket(PacketCreator.getMiniRoomError(4));
                    return;
                }

                byte createType = p.readByte();
                if (createType == 3) {  // trade
                    Trade.startTrade(chr);
                } else if (createType == 1 || createType == 2) { // omok, match cards (MiniGameRules)
                    String desc = p.readString();
                    String pw = p.readByte() != 0 ? p.readString() : "";
                    int type = p.readByte();
                    if (createType == 1) {
                        MiniGameRules.createOmok(chr, desc, pw, type);
                    } else {
                        MiniGameRules.createMatchCard(chr, desc, pw, type);
                    }
                } else if (createType == 4 || createType == 5) { // shop
                    if (!GameConstants.isFreeMarketRoom(chr.getMapId())) {
                        chr.sendPacket(PacketCreator.getMiniRoomError(15));
                        return;
                    }

                    int status = establishMiniroomStatus(chr, false);
                    if (status > 0) {
                        chr.sendPacket(PacketCreator.getMiniRoomError(status));
                        return;
                    }

                    String desc = p.readString();
                    p.skip(3);
                    int itemId = p.readInt();
                    if (chr.getInventory(InventoryType.CASH).countById(itemId) < 1) {
                        chr.sendPacket(PacketCreator.getMiniRoomError(6));
                        return;
                    }

                    if (ItemConstants.isPlayerShop(itemId)) {
                        Optional<CharacterSpaceReservation> placement = reserveStorePlacement(chr);
                        if (placement.isEmpty()) {
                            return;
                        }
                        PlayerShop shop = new PlayerShop(chr, desc, itemId);
                        shop.setPosition(placement.get().position());
                        chr.setPlayerShop(shop);
                        chr.getMap().addMapObject(shop);
                        shop.sendShop(c);
                        c.getWorldServer().registerPlayerShop(shop);
                        //c.sendPacket(PacketCreator.getPlayerShopRemoveVisitor(1));
                    } else if (ItemConstants.isHiredMerchant(itemId)) {
                        if (chr.getHiredMerchant() != null || chr.hasMerchant()
                                || c.getWorldServer().getHiredMerchant(chr.getId()) != null
                                || c.getChannelServer().getHiredMerchants().containsKey(chr.getId())) {
                            chr.dropMessage(1, "You already have a hired merchant open.");
                            chr.sendPacket(PacketCreator.enableActions());
                            return;
                        }

                        Optional<CharacterSpaceReservation> placement = reserveStorePlacement(chr);
                        if (placement.isEmpty()) {
                            return;
                        }
                        HiredMerchant merchant = new HiredMerchant(chr, desc, itemId);
                        merchant.setPosition(placement.get().position());
                        if (!c.getWorldServer().tryRegisterHiredMerchant(merchant)) {
                            FreeMarketStorePlacementService.release(chr);
                            chr.dropMessage(1, "You already have a hired merchant open.");
                            chr.sendPacket(PacketCreator.enableActions());
                            return;
                        }
                        if (!chr.getClient().getChannelServer().tryAddHiredMerchant(chr.getId(), merchant)) {
                            c.getWorldServer().unregisterHiredMerchant(merchant);
                            FreeMarketStorePlacementService.release(chr);
                            chr.dropMessage(1, "You already have a hired merchant open.");
                            chr.sendPacket(PacketCreator.enableActions());
                            return;
                        }
                        chr.setHiredMerchant(merchant);
                        chr.sendPacket(PacketCreator.getHiredMerchant(chr, merchant, true));
                    }
                }
            } else if (mode == Action.INVITE.getCode()) {
                int otherCid = p.readInt();
                Character other = chr.getMap().getCharacterById(otherCid);
                if (other == null || chr.getId() == other.getId()) {
                    return;
                }

                if (AgentCharacterGatewayRuntime.characters().isHeadlessControlled(other)
                        && !AgentCharacterGatewayRuntime.characters().isHeadlessControlled(chr)) {
                    if (!AgentAuthorityService.isTrustedTradePlayer(chr)) {
                        chr.dropMessage(1, "That Agent is not configured to accept your trade requests.");
                        return;
                    }
                    AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(other.getId());
                    if (server.agents.runtime.AgentForeignWriteRuntime.foreign(entry)) {
                        // the packet thread: the Agent's own tick takes the trade partner (mailbox bypass 1)
                        server.agents.runtime.AgentForeignWriteRuntime.defer(entry, "interaction-target",
                                owner -> AgentRelationshipRuntime.setInteractionTarget(owner, chr));
                    } else {
                        AgentRelationshipRuntime.setInteractionTarget(entry, chr);
                    }
                }

                Trade.inviteTrade(chr, other);
            } else if (mode == Action.DECLINE.getCode()) {
                Trade.declineTrade(chr);
            } else if (mode == Action.VISIT.getCode()) {
                if (chr.getTrade() != null && chr.getTrade().getPartner() != null) {
                    if (!chr.getTrade().isFullTrade() && !chr.getTrade().getPartner().isFullTrade()) {
                        Trade.visitTrade(chr, chr.getTrade().getPartner().getChr());
                    } else {
                        chr.sendPacket(PacketCreator.getMiniRoomError(2));
                        return;
                    }
                } else {
                    if (isTradeOpen(chr)) {
                        return;
                    }

                    int oid = p.readInt();
                    MapObject ob = chr.getMap().getMapObject(oid);
                    if (ob instanceof PlayerShop shop) {
                        shop.visitShop(chr);
                    } else if (ob instanceof MiniGame game) {
                        p.skip(1);
                        String pw = p.available() > 1 ? p.readString() : "";
                        MiniGameRules.visit(chr, game, pw);
                    } else if (ob instanceof HiredMerchant merchant && chr.getHiredMerchant() == null) {
                        merchant.visitShop(chr);
                    }
                }
            } else if (mode == Action.CHAT.getCode()) { // chat lol
                HiredMerchant merchant = chr.getHiredMerchant();
                if (chr.getTrade() != null) {
                    chr.getTrade().chat(p.readString());
                } else if (chr.getPlayerShop() != null) { //mini game
                    PlayerShop shop = chr.getPlayerShop();
                    if (shop != null) {
                        shop.chat(c, p.readString());
                    }
                } else if (chr.getMiniGame() != null) {
                    MiniGameRules.chat(chr, p.readString());
                } else if (merchant != null) {
                    merchant.sendMessage(chr, p.readString());
                }
            } else if (mode == Action.EXIT.getCode()) {
                if (chr.getTrade() != null) {
                    Trade.cancelTrade(chr, Trade.TradeResult.PARTNER_CANCEL);
                } else {
                    chr.closePlayerShop();
                    chr.closeMiniGame(false);
                    chr.closeHiredMerchant(true);
                }
            } else if (mode == Action.OPEN_STORE.getCode() || mode == Action.OPEN_CASH.getCode()) {
                if (isTradeOpen(chr)) {
                    return;
                }

                if (mode == Action.OPEN_STORE.getCode()) {
                    p.readByte();    //01
                } else {
                    p.readShort();
                    int birthday = p.readInt();
                    if (!CashOperationHandler.checkBirthday(c, birthday)) { // birthday check here found thanks to lucasziron
                        c.sendPacket(PacketCreator.serverNotice(1, "Please check again the birthday date."));
                        return;
                    }

                    c.sendPacket(PacketCreator.hiredMerchantOwnerMaintenanceLeave());
                }

                if (FreeMarketStorePlacementService.reservation(chr).isEmpty()) {
                    chr.sendPacket(PacketCreator.getMiniRoomError(13));
                    chr.dropMessage(5, "Your reserved Free Market store spot is no longer available.");
                    return;
                }

                PlayerShop shop = chr.getPlayerShop();
                HiredMerchant merchant = chr.getHiredMerchant();
                if (shop != null && shop.isOwner(chr)) {
                    if (YamlConfig.config.server.USE_ERASE_PERMIT_ON_OPENSHOP) {
                        try {
                            InventoryManipulator.removeById(c, InventoryType.CASH, shop.getItemId(), 1, true, false);
                        } catch (RuntimeException re) {
                        } // fella does not have a player shop permit...
                    }

                    chr.getMap().broadcastMessage(PacketCreator.updatePlayerShopBox(shop));
                    shop.setOpen(true);
                } else if (merchant != null && merchant.isOwner(chr)) {
                    chr.setHasMerchant(true);
                    merchant.setOpen(true);
                    chr.getMap().addMapObject(merchant);
                    chr.setHiredMerchant(null);
                    chr.getMap().broadcastMessage(PacketCreator.spawnHiredMerchantBox(merchant));
                }
            } else if (mode == Action.READY.getCode()) {
                MiniGameRules.ready(chr, true);
            } else if (mode == Action.UN_READY.getCode()) {
                MiniGameRules.ready(chr, false);
            } else if (mode == Action.START.getCode()) {
                MiniGameRules.start(chr);
            } else if (mode == Action.GIVE_UP.getCode()) {
                MiniGameRules.giveUp(chr);
            } else if (mode == Action.REQUEST_TIE.getCode()) {
                MiniGameRules.requestTie(chr);
            } else if (mode == Action.ANSWER_TIE.getCode()) {
                MiniGameRules.answerTie(chr, p.readByte() != 0);
            } else if (mode == Action.SKIP.getCode()) {
                MiniGameRules.skip(chr);
            } else if (mode == Action.MOVE_OMOK.getCode()) {
                int x = p.readInt(); // x point
                int y = p.readInt(); // y point
                int type = p.readByte(); // piece ( 1 or 2; Owner has one piece, visitor has another, it switches every game.)
                MiniGameRules.moveOmok(chr, x, y, type);
            } else if (mode == Action.SELECT_CARD.getCode()) {
                int turn = p.readByte(); // 1st turn = 1; 2nd turn = 0
                int slot = p.readByte(); // slot
                MiniGameRules.selectCard(chr, turn, slot);
            } else if (mode == Action.SET_MESO.getCode()) {
                chr.getTrade().setMeso(p.readInt());
            } else if (mode == Action.SET_ITEMS.getCode()) {
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                InventoryType ivType = InventoryType.getByType(p.readByte());
                short pos = p.readShort();
                Item item = chr.getInventory(ivType).getItem(pos);
                short quantity = p.readShort();
                byte targetSlot = p.readByte();

                if (targetSlot < 1 || targetSlot > 9) {
                    log.warn("[Hack] Chr {} Trying to dupe on trade slot.", chr.getName());
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                if (item == null) {
                    c.sendPacket(PacketCreator.serverNotice(1, "Invalid item description."));
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                if (ii.isUnmerchable(item.getItemId())) {
                    Trade open = chr.getTrade();
                    Character partner = open == null || open.getPartner() == null ? null : open.getPartner().getChr();
                    String refusal = CashItemTradePolicy.refusal(chr, partner, item, ii.isCash(item.getItemId()));
                    if (refusal != null) {
                        c.sendPacket(PacketCreator.serverNotice(1, refusal));
                        c.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                }

                if (quantity < 1 || quantity > item.getQuantity()) {
                    c.sendPacket(PacketCreator.serverNotice(1, "You don't have enough quantity of the item."));
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                Trade trade = chr.getTrade();
                if (trade != null) {
                    if ((quantity <= item.getQuantity() && quantity >= 0) || ItemConstants.isRechargeable(item.getItemId())) {
                        if (ii.isDropRestricted(item.getItemId()) && !ItemRestrictionPolicy.allowsUntradeable(chr, item.getItemId())) { // ensure that undroppable items do not make it to the trade window
                            if (!KarmaManipulator.hasKarmaFlag(item)) {
                                c.sendPacket(PacketCreator.serverNotice(1, "That item is untradeable."));
                                c.sendPacket(PacketCreator.enableActions());
                                return;
                            }
                        }

                        Inventory inv = chr.getInventory(ivType);
                        inv.lockInventory();
                        try {
                            Item checkItem = chr.getInventory(ivType).getItem(pos);
                            if (checkItem != item || checkItem.getPosition() != item.getPosition()) {
                                c.sendPacket(PacketCreator.serverNotice(1, "Invalid item description."));
                                c.sendPacket(PacketCreator.enableActions());
                                return;
                            }

                            Item tradeItem = item.copy();
                            if (ItemConstants.isRechargeable(item.getItemId())) {
                                quantity = item.getQuantity();
                            }

                            tradeItem.setQuantity(quantity);
                            tradeItem.setPosition(targetSlot);

                            if (trade.addItem(tradeItem)) {
                                InventoryManipulator.removeFromSlot(c, ivType, item.getPosition(), quantity, true);

                                trade.getChr().sendPacket(PacketCreator.getTradeItemAdd((byte) 0, tradeItem));
                                if (trade.getPartner() != null) {
                                    trade.getPartner().getChr().sendPacket(PacketCreator.getTradeItemAdd((byte) 1, tradeItem));
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Chr {} tried to add {}x {} in trade (slot {}), then exception occurred", chr, ii.getName(item.getItemId()), item.getQuantity(), targetSlot, e);
                        } finally {
                            inv.unlockInventory();
                        }
                    }
                }
            } else if (mode == Action.CONFIRM.getCode()) {
                Trade.completeTrade(chr);
            } else if (mode == Action.ADD_ITEM.getCode() || mode == Action.PUT_ITEM.getCode()) {
                if (isTradeOpen(chr)) {
                    return;
                }

                InventoryType ivType = InventoryType.getByType(p.readByte());
                short slot = p.readShort();
                short bundles = p.readShort();
                Item ivItem = chr.getInventory(ivType).getItem(slot);

                if (ivItem == null || (ivItem.isUntradeable() && !ItemRestrictionPolicy.allowsUntradeable(chr, ivItem.getItemId()))) {
                    c.sendPacket(PacketCreator.serverNotice(1, "Could not perform shop operation with that item."));
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                } else if (ItemInformationProvider.getInstance().isUnmerchable(ivItem.getItemId())) {
                    if (ItemConstants.isPet(ivItem.getItemId())) {
                        c.sendPacket(PacketCreator.serverNotice(1, "Pets are not allowed to be sold on the Player Store."));
                    } else {
                        c.sendPacket(PacketCreator.serverNotice(1, "Cash items are not allowed to be sold on the Player Store."));
                    }

                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                short perBundle = p.readShort();

                if (ItemConstants.isRechargeable(ivItem.getItemId())) {
                    perBundle = 1;
                    bundles = 1;
                } else if (ivItem.getQuantity() < (bundles * perBundle)) {     // thanks GabrielSin for finding a dupe here
                    c.sendPacket(PacketCreator.serverNotice(1, "Could not perform shop operation with that item."));
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                int price = p.readInt();
                if (perBundle <= 0 || perBundle * bundles > 2000 || bundles <= 0 || price <= 0 || price > Integer.MAX_VALUE) {
                    AutobanFactory.PACKET_EDIT.alert(chr, chr.getName() + " tried to packet edit with hired merchants.");
                    log.warn("Chr {} might possibly have packet edited Hired Merchants. perBundle: {}, perBundle * bundles (This multiplied cannot be greater than 2000): {}, bundles: {}, price: {}",
                            chr.getName(), perBundle, perBundle * bundles, bundles, price);
                    return;
                }

                Item sellItem = ivItem.copy();
                if (!ItemConstants.isRechargeable(ivItem.getItemId())) {
                    sellItem.setQuantity(perBundle);
                }

                PlayerShopItem shopItem = new PlayerShopItem(sellItem, bundles, price);
                PlayerShop shop = chr.getPlayerShop();
                HiredMerchant merchant = chr.getHiredMerchant();
                if (shop != null && shop.isOwner(chr)) {
                    if (shop.isOpen() || !shop.addItem(shopItem)) { // thanks Vcoc for pointing an exploit with unlimited shop slots
                        c.sendPacket(PacketCreator.serverNotice(1, "You can't sell it anymore."));
                        return;
                    }

                    if (ItemConstants.isRechargeable(ivItem.getItemId())) {
                        InventoryManipulator.removeFromSlot(c, ivType, slot, ivItem.getQuantity(), true);
                    } else {
                        InventoryManipulator.removeFromSlot(c, ivType, slot, (short) (bundles * perBundle), true);
                    }

                    c.sendPacket(PacketCreator.getPlayerShopItemUpdate(shop));
                } else if (merchant != null && merchant.isOwner(chr)) {
                    if (ivType.equals(InventoryType.CASH) && merchant.isPublished()) {
                        c.sendPacket(PacketCreator.serverNotice(1, "Cash items are only allowed to be sold when first opening the store."));
                        return;
                    }

                    if (merchant.isOpen() || !merchant.addItem(shopItem)) { // thanks Vcoc for pointing an exploit with unlimited shop slots
                        c.sendPacket(PacketCreator.serverNotice(1, "You can't sell it anymore."));
                        return;
                    }

                    if (ItemConstants.isRechargeable(ivItem.getItemId())) {
                        InventoryManipulator.removeFromSlot(c, ivType, slot, ivItem.getQuantity(), true);
                    } else {
                        InventoryManipulator.removeFromSlot(c, ivType, slot, (short) (bundles * perBundle), true);
                    }

                    c.sendPacket(PacketCreator.updateHiredMerchant(merchant, chr));

                    if (YamlConfig.config.server.USE_ENFORCE_MERCHANT_SAVE) {
                        chr.saveCharToDB(false, SaveReason.MERCHANT);
                    }

                    try {
                        merchant.saveItems(false);   // thanks Masterrulax for realizing yet another dupe with merchants/Fredrick
                    } catch (SQLException ex) {
                        monitoring.RuntimeFailureLogger.log(ex);
                    }
                } else {
                    c.sendPacket(PacketCreator.serverNotice(1, "You can't sell without owning a shop."));
                }
            } else if (mode == Action.REMOVE_ITEM.getCode()) {
                if (isTradeOpen(chr)) {
                    return;
                }

                PlayerShop shop = chr.getPlayerShop();
                if (shop != null && shop.isOwner(chr)) {
                    if (shop.isOpen()) {
                        c.sendPacket(PacketCreator.serverNotice(1, "You can't take it with the store open."));
                        return;
                    }

                    int slot = p.readShort();
                    if (slot >= shop.getItems().size() || slot < 0) {
                        AutobanFactory.PACKET_EDIT.alert(chr, chr.getName() + " tried to packet edit with a player shop.");
                        log.warn("Chr {} tried to remove item at slot {}", chr.getName(), slot);
                        c.disconnect(true, false);
                        return;
                    }

                    shop.takeItemBack(slot, chr);
                }
            } else if (mode == Action.MERCHANT_MESO.getCode()) {
                HiredMerchant merchant = chr.getHiredMerchant();
                if (merchant == null) {
                    return;
                }

                merchant.withdrawMesos(chr);

            } else if (mode == Action.VIEW_VISITORS.getCode()) {
                HiredMerchant merchant = chr.getHiredMerchant();
                if (merchant == null || !merchant.isOwner(chr)) {
                    return;
                }
                c.sendPacket(PacketCreator.viewMerchantVisitorHistory(merchant.getVisitorHistory()));
            } else if (mode == Action.VIEW_BLACKLIST.getCode()) {
                HiredMerchant merchant = chr.getHiredMerchant();
                if (merchant == null || !merchant.isOwner(chr)) {
                    return;
                }

                c.sendPacket(PacketCreator.viewMerchantBlacklist(merchant.getBlacklist()));
            } else if (mode == Action.ADD_TO_BLACKLIST.getCode()) {
                HiredMerchant merchant = chr.getHiredMerchant();
                if (merchant == null || !merchant.isOwner(chr)) {
                    return;
                }
                String chrName = p.readString();
                merchant.addToBlacklist(chrName);
            } else if (mode == Action.REMOVE_FROM_BLACKLIST.getCode()) {
                HiredMerchant merchant = chr.getHiredMerchant();
                if (merchant == null || !merchant.isOwner(chr)) {
                    return;
                }
                String chrName = p.readString();
                merchant.removeFromBlacklist(chrName);
            } else if (mode == Action.MERCHANT_ORGANIZE.getCode()) {
                HiredMerchant merchant = chr.getHiredMerchant();
                if (merchant == null || !merchant.isOwner(chr)) {
                    return;
                }

                merchant.withdrawMesos(chr);
                merchant.clearInexistentItems();

                if (merchant.getItems().isEmpty()) {
                    merchant.closeOwnerMerchant(chr);
                    return;
                }
                c.sendPacket(PacketCreator.updateHiredMerchant(merchant, chr));

            } else if (mode == Action.BUY.getCode() || mode == Action.MERCHANT_BUY.getCode()) {
                if (isTradeOpen(chr)) {
                    return;
                }

                int itemid = p.readByte();
                short quantity = p.readShort();
                if (quantity < 1) {
                    AutobanFactory.PACKET_EDIT.alert(chr, chr.getName() + " tried to packet edit with a hired merchant and or player shop.");
                    log.warn("Chr {} tried to buy item {} with quantity {}", chr.getName(), itemid, quantity);
                    c.disconnect(true, false);
                    return;
                }
                PlayerShop shop = chr.getPlayerShop();
                HiredMerchant merchant = chr.getHiredMerchant();
                if (shop != null && shop.isVisitor(chr)) {
                    if (shop.buy(c, itemid, quantity)) {
                        shop.broadcast(PacketCreator.getPlayerShopItemUpdate(shop));
                    }
                } else if (merchant != null && !merchant.isOwner(chr)) {
                    merchant.buy(c, itemid, quantity);
                    merchant.broadcastToVisitorsThreadsafe(PacketCreator.updateHiredMerchant(merchant, chr));
                }
            } else if (mode == Action.TAKE_ITEM_BACK.getCode()) {
                if (isTradeOpen(chr)) {
                    return;
                }

                HiredMerchant merchant = chr.getHiredMerchant();
                if (merchant != null && merchant.isOwner(chr)) {
                    if (merchant.isOpen()) {
                        c.sendPacket(PacketCreator.serverNotice(1, "You can't take it with the store open."));
                        return;
                    }

                    int slot = p.readShort();
                    if (slot >= merchant.getItems().size() || slot < 0) {
                        AutobanFactory.PACKET_EDIT.alert(chr, chr.getName() + " tried to packet edit with a hired merchant.");
                        log.warn("Chr {} tried to remove item at slot {}", chr.getName(), slot);
                        c.disconnect(true, false);
                        return;
                    }

                    merchant.takeItemBack(slot, chr);
                }
            } else if (mode == Action.CLOSE_MERCHANT.getCode()) {
                if (isTradeOpen(chr)) {
                    return;
                }

                HiredMerchant merchant = chr.getHiredMerchant();
                if (merchant != null) {
                    merchant.closeOwnerMerchant(chr);
                }
            } else if (mode == Action.MAINTENANCE_OFF.getCode()) {
                if (isTradeOpen(chr)) {
                    return;
                }

                HiredMerchant merchant = chr.getHiredMerchant();
                if (merchant != null) {
                    if (merchant.isOwner(chr)) {
                        if (merchant.getItems().isEmpty()) {
                            merchant.closeOwnerMerchant(chr);
                        } else {
                            merchant.clearMessages();
                            merchant.setOpen(true);
                            merchant.getMap().broadcastMessage(PacketCreator.updateHiredMerchantBox(merchant));
                        }
                    }
                }

                chr.setHiredMerchant(null);
                c.sendPacket(PacketCreator.enableActions());
            } else if (mode == Action.BAN_PLAYER.getCode()) {
                p.skip(1);

                PlayerShop shop = chr.getPlayerShop();
                if (shop != null && shop.isOwner(chr)) {
                    shop.banPlayer(p.readString());
                }
            } else if (mode == Action.EXPEL.getCode()) {
                MiniGameRules.expel(chr);
            } else if (mode == Action.EXIT_AFTER_GAME.getCode()) {
                MiniGameRules.leaveAfterGame(chr, true);
            } else if (mode == Action.CANCEL_EXIT_AFTER_GAME.getCode()) {
                MiniGameRules.leaveAfterGame(chr, false);
            }
        } finally {
            c.releaseClient();
        }
    }

    private static boolean isTradeOpen(Character chr) {
        if (chr.getTrade() != null) {   // thanks to Rien dev team
            //Apparently there is a dupe exploit that causes racing conditions when saving/retrieving from the db with stuff like trade open.
            chr.sendPacket(PacketCreator.enableActions());
            return true;
        }

        return false;
    }

    private static Optional<CharacterSpaceReservation> reserveStorePlacement(Character chr) {
        Optional<CharacterSpaceReservation> placement = FreeMarketStorePlacementService.reserveNearest(chr);
        if (placement.isEmpty()) {
            chr.sendPacket(PacketCreator.getMiniRoomError(13));
            chr.dropMessage(5, "No designated Free Market store spot is available nearby.");
        }
        return placement;
    }
}
