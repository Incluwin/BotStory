/**
 * 9010018 - Cryssea (New Leaf City Town Center; only placed while server mod cryssea is on, server.mods.GatedNpcs)
 * The iTCG card swap (BotStory): three pieces of one set for one piece of the player's choice from the same set
 * (server.itcg, docs/ITCG_MASTERIA_PLAN.md).
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');
var ItcgRecipes = Java.type('server.itcg.ItcgRecipes');

var GROUPS = ["the space artifacts (Set 1)", "the ancient pieces (Set 2)", "the spirit and hero pieces (Sets 3 and 4)"];

var status;
var group;
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
        var text = "MapleStory iTCG is in stores!! You can find it at various places!\r\n\r\n"
            + "Do you want to know more about MapleStory iTCG? Here's the best part: everyone ends up with doubles. "
            + "Give me any three pieces from one set and I'll trade you the one you're missing.\r\n\r\n#b";
        var groups = ItcgRecipes.SWAP_GROUPS;
        for (var i = 0; i < groups.size(); i++) {
            text += "#L" + i + "#Trade " + GROUPS[i] + " (you have " + ItcgForge.swapHeld(cm, groups.get(i)) + ")#l\r\n";
        }
        cm.sendSimple(text);
    } else if (status == 1) {
        var groups = ItcgRecipes.SWAP_GROUPS;
        if (selection < 0 || selection >= groups.size()) {
            cm.dispose();
            return;
        }
        group = groups.get(selection);
        var text = "Which one do you want?\r\n\r\n#b";
        for (var i = 0; i < group.size(); i++) {
            text += "#L" + i + "##v" + group.get(i) + "# #t" + group.get(i) + "# (#c" + group.get(i) + "#)#l\r\n";
        }
        cm.sendSimple(text);
    } else if (status == 2) {
        if (selection < 0 || selection >= group.size()) {
            cm.dispose();
            return;
        }
        pick = ItcgForge.swap(cm, group.get(selection));
        if (pick == null) {
            cm.sendOk("You need three other pieces from that set to trade. Keep collecting!");
            cm.dispose();
            return;
        }
        cm.sendYesNo("For the #b#t" + pick.result() + "##k I'll take:\r\n\r\n" + ItcgForge.inputs(cm, pick) + "\r\nDeal?");
    } else if (status == 3) {
        var made = ItcgForge.make(cm, pick);
        if (made.ok()) {
            cm.sendOk("Deal! One #v" + made.itemId() + "# #b#t" + made.itemId() + "##k. Your collection's coming along!");
        } else {
            cm.sendOk("Hmm: " + made.refusal());
        }
        cm.dispose();
    }
}
