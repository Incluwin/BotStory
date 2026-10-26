/**
 * 9201101 - T-1337 (New Leaf City Town Center)
 * iTCG Set 4: brews a batch of one random T-1337 juice from a fuel cell, 50 Boomer Cores, a Jumper Cable, a
 * Sparkplug and a T-1 Socket Adapter; a Triferium cell gives half as many again (server.itcg,
 * docs/ITCG_MASTERIA_PLAN.md).
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');
var ItcgRecipes = Java.type('server.itcg.ItcgRecipes');

var status;
var recipes;
var pick;

function start() {
    status = -1;
    recipes = ItcgRecipes.T1337;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    status++;

    if (status == 0) {
        cm.sendSimple("<Spark> I am cybernetic automaton model T-1337 *BZZ* I have been sent to... CRITICAL SYSTEM ERROR! <Blue Screen>\r\n\r\n"
            + "...REBOOT COMPLETE. JUICE SYNTHESIS MODULE: ONLINE. INSERT FUEL CELL AND PARTS.\r\n\r\n"
            + "#b#L0##v" + ItcgRecipes.DIFERIUM_FUEL_CELL + "# Brew on a #t" + ItcgRecipes.DIFERIUM_FUEL_CELL + "# (10 juices)#l\r\n"
            + "#L1##v" + ItcgRecipes.TRIFERIUM_FUEL_CELL + "# Brew on a #t" + ItcgRecipes.TRIFERIUM_FUEL_CELL + "# (15 juices)#l");
    } else if (status == 1) {
        if (selection < 0 || selection >= recipes.size()) {
            cm.dispose();
            return;
        }
        pick = recipes.get(selection);
        cm.sendYesNo("REQUIRED INPUT *BZZ*:\r\n\r\n" + ItcgForge.inputs(cm, pick)
            + "\r\nOUTPUT: ONE JUICE TYPE, SELECTED BY... RANDOM NUMBER GENERATOR. PROCEED?");
    } else if (status == 2) {
        var made = ItcgForge.make(cm, pick);
        if (made.ok()) {
            cm.sendOk("*WHIRR* *CLUNK* SYNTHESIS COMPLETE: " + made.count() + " #v" + made.itemId() + "# #b#t" + made.itemId() + "##k. HAVE A NICE DAY. *BZZ*");
        } else {
            cm.sendOk("ERROR: " + made.refusal());
        }
        cm.dispose();
    }
}
