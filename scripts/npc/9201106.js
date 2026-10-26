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

/**
 * 9201106 - Adonis (El Nath)
 * iTCG Set 5 (BotStory): his expedition line, the Stone Denari exchange (one of eight random things) and the four
 * Set 5 upgrades, which always succeed (docs/ITCG_MASTERIA_PLAN.md section 9; server.itcg).
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');
var ItcgRecipes = Java.type('server.itcg.ItcgRecipes');

var status;
var branch;
var pick;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    status++;

    if (status == 0) {
        cm.sendSimple("I came from far-away places looking for people powerful enough to join my expedition against the evil that lays waste on this land. Are you, by any chance, one of those people?\r\n\r\n"
            + "#b#L0#Tell me about the expedition.#l\r\n#L1#I have a Stone Denari.#l\r\n#L2#Can you strengthen an expedition piece?#l");
    } else if (status == 1) {
        branch = selection;
        if (branch == 0) {
            cm.sendOk("The evil under El Nath stirs. Those who would face it need the strongest gear the mountain can give: "
                + "the Cloak of Corruption, the Crystal Blade, the El Nathian Cape, the Miner's Hat. Bring me one, and the "
                + "#bZakum Diamond#k from the beast's own altar, and I'll make it worthy of the fight.");
            cm.dispose();
            return;
        }
        if (branch == 1) {
            pick = ItcgRecipes.STONE_DENARI_DRAW;
            cm.sendYesNo("A #bStone Denari#k. The old coin of the mountain. Give it to me and I'll give you one thing from my "
                + "packs; which thing, the mountain decides.\r\n\r\n" + ItcgForge.inputs(cm, pick) + "\r\nAgreed?");
            return;
        }
        cm.sendSimple("Which piece?\r\n\r\n#b" + ItcgForge.menu(cm, ItcgRecipes.ADONIS_UPGRADES));
    } else if (status == 2) {
        if (branch == 1) {
            finish();
            return;
        }
        var upgrades = ItcgRecipes.ADONIS_UPGRADES;
        if (selection < 0 || selection >= upgrades.size()) {
            cm.dispose();
            return;
        }
        pick = upgrades.get(selection);
        cm.sendYesNo("The #b#t" + pick.result() + "##k. It takes:\r\n\r\n" + ItcgForge.inputs(cm, pick) + "\r\nShall I begin?");
    } else if (status == 3) {
        finish();
    }
}

function finish() {
    var made = ItcgForge.make(cm, pick);
    if (made.ok()) {
        cm.sendOk("There. #v" + made.itemId() + "# #b#t" + made.itemId() + "##k" + (made.count() > 1 ? " (" + made.count() + ")" : "")
            + ". May it serve you on the expedition.");
    } else {
        cm.sendOk("Not yet: " + made.refusal());
    }
    cm.dispose();
}
