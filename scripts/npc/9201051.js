/**
 * 9201051 - John Barricade (Bigger Ben: Lobby)
 * iTCG Set 2: draws a forging manual from the ancient artifacts (Spindle in Omega Sector forges from it) and forges
 * the Bosshunter set himself (server.itcg, docs/ITCG_MASTERIA_PLAN.md). The first visit opens quest 4927 "Ancient
 * Artifacts" in the quest log; the first manual or Bosshunter piece closes it. His stock quests go through the
 * quest window as before.
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');
var ItcgRecipes = Java.type('server.itcg.ItcgRecipes');

var status;
var branch;
var list;
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
        if (!cm.isQuestStarted(ItcgRecipes.BARRICADE_QUEST) && !cm.isQuestCompleted(ItcgRecipes.BARRICADE_QUEST)) {
            cm.forceStartQuest(ItcgRecipes.BARRICADE_QUEST);
        }
        cm.sendSimple("Never fear! John Barricade, infamous treasure hunter, is here!\r\n\r\n"
            + "You've been finding ancient artifacts, haven't you? I can see it in your pockets. Bring me a valid set and I'll "
            + "tell you what can be made of them, and how.\r\n\r\n"
            + "#b#L0#Draw me a forging manual.#l\r\n#L1#Forge me a piece of the Bosshunter set.#l\r\n#L2#What are you working on?#l");
    } else if (status == 1) {
        branch = selection;
        if (branch == 2) {
            cm.sendOk("These darn Hyper Glyphs! Have you spoken to the others in NLC? Professor Foxwit, Mayor Slimm, Lita... "
                + "Cover all the angles, kid! And if you're forging: my manuals go to #bSpindle#k in #bOmega Sector#k. "
                + "The Bosshunter set I make right here. It wants #bRidley's Book of Rituals#k, an #bAncient#k piece and two "
                + "trophies from the bosses of this world.");
            cm.dispose();
            return;
        }
        list = branch == 0 ? ItcgRecipes.BARRICADE_MANUALS : ItcgRecipes.BOSSHUNTER;
        cm.sendSimple((branch == 0 ? "Which manual?" : "Helm, Armor and Greaves for warriors, bowmen and pirates; Faceguard, Gi and Boots for magicians and thieves. Which?")
            + "\r\n\r\n#b" + ItcgForge.menu(cm, list));
    } else if (status == 2) {
        if (selection < 0 || selection >= list.size()) {
            cm.dispose();
            return;
        }
        pick = list.get(selection);
        var text = "The #b#t" + pick.result() + "##k. I'll need:\r\n\r\n" + ItcgForge.inputs(cm, pick);
        if (branch == 0) {
            var forge = ItcgForge.forgeFor(pick.result());
            text += "\r\nSpindle will want this for the #b#t" + forge.result() + "##k:\r\n\r\n" + ItcgForge.inputs(cm, forge);
        }
        cm.sendYesNo(text + "\r\nDeal?");
    } else if (status == 3) {
        var made = ItcgForge.make(cm, pick);
        if (made.ok()) {
            if (!cm.isQuestCompleted(ItcgRecipes.BARRICADE_QUEST)) {
                cm.forceCompleteQuest(ItcgRecipes.BARRICADE_QUEST);
            }
            if (branch == 0) {
                cm.sendOk("Here's your #b#t" + made.itemId() + "##k. Take it to Spindle in #bOmega Sector#k. Tell him Barricade sent you; he'll grumble anyway.");
            } else {
                cm.sendOk("The #b#t" + made.itemId() + "##k! Made for hunting the big ones. Go show them who's boss, kid.");
            }
        } else {
            cm.sendOk("Not so fast: " + made.refusal());
        }
        cm.dispose();
    }
}
