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
import client.FamilyBuffs;
import client.FamilyEntitlement;
import client.FamilyEntry;
import config.YamlConfig;
import constants.id.MapId;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import server.maps.FieldLimit;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Moogra
 * @author Ubaware
 */
public final class FamilyUseHandler extends AbstractPacketHandler {
    private static final long MINUTE_MS = 60_000L;
    private static final int FAMILY_BONDING_MIN_JUNIORS = 6;

    @Override
    public final void handlePacket(InPacket p, Client c) {
        if (!YamlConfig.config.server.USE_FAMILY_SYSTEM) {
            return;
        }
        FamilyEntitlement type = FamilyEntitlement.values()[p.readInt()];
        int cost = type.getRepCost();
        FamilyEntry entry = c.getPlayer().getFamilyEntry();
        if (entry.getReputation() < cost || entry.isEntitlementUsed(type)) {
            return; // shouldn't even be able to request it
        }
        c.sendPacket(PacketCreator.getFamilyInfo(entry));
        Character victim;
        if (type == FamilyEntitlement.FAMILY_REUINION || type == FamilyEntitlement.SUMMON_FAMILY) {
            victim = c.getChannelServer().getPlayerStorage().getCharacterByName(p.readString());
            if (victim != null && victim != c.getPlayer()) {
                if (victim.getFamily() == c.getPlayer().getFamily()) {
                    MapleMap targetMap = victim.getMap();
                    MapleMap ownMap = c.getPlayer().getMap();
                    if (targetMap != null) {
                        if (type == FamilyEntitlement.FAMILY_REUINION) {
                            if (!FieldLimit.CANNOTMIGRATE.check(ownMap.getFieldLimit()) && !FieldLimit.CANNOTVIPROCK.check(targetMap.getFieldLimit())
                                    && (targetMap.getForcedReturnId() == MapId.NONE || MapId.isMapleIsland(targetMap.getId())) && targetMap.getEventInstance() == null) {

                                c.getPlayer().changeMap(victim.getMap(), victim.getMap().getPortal(0));
                                useEntitlement(entry, type);
                            } else {
                                c.sendPacket(PacketCreator.sendFamilyMessage(75, 0)); // wrong message, but close enough. (client should check this first anyway)
                                return;
                            }
                        } else {
                            if (!FieldLimit.CANNOTMIGRATE.check(targetMap.getFieldLimit()) && !FieldLimit.CANNOTVIPROCK.check(ownMap.getFieldLimit())
                                    && (ownMap.getForcedReturnId() == MapId.NONE || MapId.isMapleIsland(ownMap.getId())) && ownMap.getEventInstance() == null) {

                                if (InviteCoordinator.hasInvite(InviteType.FAMILY_SUMMON, victim.getId())) {
                                    c.sendPacket(PacketCreator.sendFamilyMessage(74, 0));
                                    return;
                                }
                                InviteCoordinator.createInvite(InviteType.FAMILY_SUMMON, c.getPlayer(), victim, victim.getId(), c.getPlayer().getMap());
                                victim.sendPacket(PacketCreator.sendFamilySummonRequest(c.getPlayer().getFamily().getName(), c.getPlayer().getName()));
                                useEntitlement(entry, type);
                            } else {
                                c.sendPacket(PacketCreator.sendFamilyMessage(75, 0));
                                return;
                            }
                        }
                    }
                } else {
                    c.sendPacket(PacketCreator.sendFamilyMessage(67, 0));
                }
            }
        } else if (type == FamilyEntitlement.FAMILY_BONDING) {
            List<Character> members = new ArrayList<>();
            collectOnlineJuniors(entry, members);
            if (members.size() < FAMILY_BONDING_MIN_JUNIORS) {
                c.getPlayer().dropMessage(5, "Family Bonding needs at least " + FAMILY_BONDING_MIN_JUNIORS
                        + " Family members online below you in the Pedigree.");
                return;
            }
            if (useEntitlement(entry, type)) {
                long until = System.currentTimeMillis() + 30 * MINUTE_MS;
                members.add(c.getPlayer());
                for (Character member : members) {
                    FamilyBuffs.grantExp(member.getId(), 2.0f, until);
                    FamilyBuffs.grantDrop(member.getId(), 2.0f, until);
                    member.dropMessage(5, c.getPlayer().getName() + " used " + type.getName()
                            + ": EXP from hunting and monster drop rate are 2x for 30 minutes.");
                }
            }
        } else {
            final FamilyEntitlement entitlement = type;
            boolean party = false;
            boolean isExp = false;
            float rate = 1.5f;
            int duration = 15;
            do {
                switch (type) {
                    case PARTY_EXP_2_30MIN:
                        party = true;
                        isExp = true;
                        type = FamilyEntitlement.SELF_EXP_2_30MIN;
                        continue;
                    case PARTY_DROP_2_30MIN:
                        party = true;
                        type = FamilyEntitlement.SELF_DROP_2_30MIN;
                        continue;
                    case SELF_DROP_2_30MIN:
                        duration = 30;
                    case SELF_DROP_2:
                        rate = 2.0f;
                    case SELF_DROP_1_5:
                        break;
                    case SELF_EXP_2_30MIN:
                        duration = 30;
                    case SELF_EXP_2:
                        rate = 2.0f;
                    case SELF_EXP_1_5:
                        isExp = true;
                    default:
                        break;
                }
                break;
            } while (true);

            if (!useEntitlement(entry, entitlement)) {
                return;
            }
            long until = System.currentTimeMillis() + duration * MINUTE_MS;
            List<Character> targets = party ? c.getPlayer().getPartyMembersOnline() : List.of();
            if (targets.isEmpty()) {
                targets = List.of(c.getPlayer());
            }
            String effect = (isExp ? "EXP from hunting is " : "Monster drop rate is ") + FamilyBuffs.formatRate(rate)
                    + "x for " + duration + " minutes.";
            for (Character target : targets) {
                if (isExp) {
                    FamilyBuffs.grantExp(target.getId(), rate, until);
                } else {
                    FamilyBuffs.grantDrop(target.getId(), rate, until);
                }
                target.dropMessage(5, (target == c.getPlayer() ? "" : c.getPlayer().getName() + " used ")
                        + entitlement.getName() + ": " + effect);
            }
        }
    }

    private static void collectOnlineJuniors(FamilyEntry senior, List<Character> out) {
        for (FamilyEntry junior : senior.getJuniors()) {
            Character chr = junior.getChr();
            if (chr != null) {
                out.add(chr);
            }
            collectOnlineJuniors(junior, out);
        }
    }

    private boolean useEntitlement(FamilyEntry entry, FamilyEntitlement entitlement) {
        if (entry.useEntitlement(entitlement)) {
            entry.gainReputation(-entitlement.getRepCost(), false);
            entry.getChr().sendPacket(PacketCreator.getFamilyInfo(entry));
            return true;
        }
        return false;
    }
}
