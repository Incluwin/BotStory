/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

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
package client.creator;

import client.Character;
import client.Client;
import client.SkinColor;
import client.inventory.EvolvingRing;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import tools.PacketCreator;

/**
 * @author RonanLana
 */
public abstract class CharacterFactory {
    private static final Logger log = LoggerFactory.getLogger(CharacterFactory.class);

    protected synchronized static int createNewCharacter(Client c, String name, int face, int hair, int skin, int gender, CharacterFactoryRecipe recipe) {
        if (YamlConfig.config.server.COLLECTIVE_CHARSLOT ? c.getAvailableCharacterSlots() <= 0 : c.getAvailableCharacterWorldSlots() <= 0) {
            return -3;
        }

        if (!Character.canCreateChar(name)) {
            return -1;
        }

        Character newCharacter = Character.getDefault(c);
        newCharacter.setWorld(c.getWorld());
        newCharacter.setSkinColor(SkinColor.getById(skin));
        newCharacter.setGender(gender);
        newCharacter.setName(name);
        newCharacter.setHair(hair);
        newCharacter.setFace(face);

        newCharacter.setLevel(recipe.getLevel());
        newCharacter.setJob(recipe.getJob());
        newCharacter.setMapId(recipe.getMap());

        Inventory equipped = newCharacter.getInventory(InventoryType.EQUIPPED);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        int top = recipe.getTop(), bottom = recipe.getBottom(), shoes = recipe.getShoes(), weapon = recipe.getWeapon();

        if (top > 0) {
            Item eq_top = ii.getEquipById(top);
            eq_top.setPosition((byte) -5);
            equipped.addItemFromDB(eq_top);
        }

        // Growing Quest Ring: auto-equip on character creation in Ring slot 1 (-12).
        try {
            Item eq_ring = ii.getEquipById(Character.QUEST_RING_ID);
            if (eq_ring != null) {
                eq_ring.setPosition((byte) -12);
                equipped.addItemFromDB(eq_ring);
            }
        } catch (Exception e) {
            log.warn("Failed to auto-equip quest ring {} on new character", Character.QUEST_RING_ID, e);
        }

        // Evolving Ring I: auto-equip in Ring slot 2 (-13). The ring auto-evolves at item-level 30
        // (see Equip.transmuteToNextTier) through Ring II → Ring III → Fully Evolved Ring. Like
        // the Quest Ring, it's locked to the slot — see ItemMoveHandler / canWearEquipment.
        try {
            Item eq_evo = ii.getEquipById(EvolvingRing.EVOLVING_RING_I);
            if (eq_evo != null) {
                eq_evo.setPosition((byte) -13);
                equipped.addItemFromDB(eq_evo);
            }
        } catch (Exception e) {
            log.warn("Failed to auto-equip Evolving Ring I {} on new character",
                    EvolvingRing.EVOLVING_RING_I, e);
        }

        // Hunter's Codex: auto-equip in cash medal slot (-149) so a regular medal can
        // occupy -49 alongside it. Requires WZ cash=1 on img 1142165 (set by
        // install_monster_book_medal.py). Stats grow with monster-book discoveries (see
        // Character.applyMonsterBookMedalBoost). Locked like the Quest Ring —
        // ItemMoveHandler.isLockedEquippedItem prevents drop/move.
        //
        // GATED: leave commented until install_monster_book_medal.py has been run.
        // Stock v83 WZ has medalTag=95 on this img, which crashes the BotStory
        // client ~3s after spawn. The install script zeros it. Mirror the gate in
        // PlayerLoggedinHandler — re-enable both together.
        // try {
        //     Item eq_medal = ii.getEquipById(Character.MONSTER_BOOK_MEDAL_ID);
        //     if (eq_medal != null) {
        //         eq_medal.setPosition((short) -149);
        //         equipped.addItemFromDB(eq_medal);
        //     }
        // } catch (Exception e) {
        //     log.warn("Failed to auto-equip Hunter's Codex {} on new character",
        //             Character.MONSTER_BOOK_MEDAL_ID, e);
        // }

        if (bottom > 0) {
            Item eq_bottom = ii.getEquipById(bottom);
            eq_bottom.setPosition((byte) -6);
            equipped.addItemFromDB(eq_bottom);
        }

        if (shoes > 0) {
            Item eq_shoes = ii.getEquipById(shoes);
            eq_shoes.setPosition((byte) -7);
            equipped.addItemFromDB(eq_shoes);
        }

        if (weapon > 0) {
            Item eq_weapon = ii.getEquipById(weapon);
            eq_weapon.setPosition((byte) -11);
            equipped.addItemFromDB(eq_weapon.copy());
        }

        if (!MakeCharInfoValidator.isNewCharacterValid(newCharacter)) {
            log.warn("Owner from account {} tried to packet edit in character creation", c.getAccountName());
            return -2;
        }

        if (!newCharacter.insertNewChar(recipe)) {
            return -2;
        }

//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5000000, (short) 5, (short)1)); // brownKitty
//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5190001, (short) 6, (short)1)); // petHPSkill
//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5190006, (short) 7, (short)1)); // petMPSkill
//
//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5190000, (short) 8, (short)1)); // Item Pick-up Skill
//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5190002, (short) 9, (short)1)); // Expand Range Skill
//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5190003, (short) 10, (short)1)); // Automatic Pick-Up Skill

        c.sendPacket(PacketCreator.addNewCharEntry(newCharacter));

        Server.getInstance().createCharacterEntry(newCharacter);
        Server.getInstance().broadcastGMMessage(c.getWorld(), PacketCreator.sendYellowTip("[New Char]: " + c.getAccountName() + " has created a new character with IGN " + name));
        log.info("Account {} created chr with name {}", c.getAccountName(), name);

        return 0;
    }
}
