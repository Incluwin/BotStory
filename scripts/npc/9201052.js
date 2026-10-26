/**
 * 9201052 - Professor Foxwit (New Leaf City Town Center)
 * iTCG Set 1: draws a forging manual from the space artifacts; Spindle in Omega Sector forges from it
 * (server.itcg, docs/ITCG_MASTERIA_PLAN.md). The first visit opens quest 4926 "Interstellar Relics" in the
 * quest log; the first manual closes it.
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');
var ItcgRecipes = Java.type('server.itcg.ItcgRecipes');

var status;
var manuals;
var pick;

function start() {
    status = -1;
    manuals = ItcgRecipes.FOXWIT_MANUALS;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    status++;

    if (status == 0) {
        if (!cm.isQuestStarted(ItcgRecipes.FOXWIT_QUEST) && !cm.isQuestCompleted(ItcgRecipes.FOXWIT_QUEST)) {
            cm.forceStartQuest(ItcgRecipes.FOXWIT_QUEST);
        }
        cm.sendSimple("I'm Professor Foxwit and I know what you're thinking... crackpot or genius? I get it all the time!\r\n\r\n"
            + "Artifacts from beyond the sky have been turning up all over Masteria. Dark Matter, Zeta Residue, Versal Materia... "
            + "Bring me the right ones and I'll draw you a forging manual. Years of time travel teach a fellow what things are for.\r\n\r\n"
            + "#b#L0#Draw me a forging manual.#l\r\n#L1#What do the manuals make?#l");
    } else if (status == 1) {
        if (selection == 1) {
            cm.sendOk("Each manual is a blueprint for one piece: the #bSirius Cloak#k, the #bZeta Cape#k, the #bBlack Phoenix Shield#k "
                + "and the #bDark Shards#k. I only draw them. The building is Spindle's job: he works out of #bOmega Sector#k, "
                + "and he'll want the manual and some honest ore. My colleague John Barricade draws manuals too, from the ancient "
                + "artifacts; he's in the Bigger Ben lobby.");
            cm.dispose();
            return;
        }
        cm.sendSimple("Which one? Show me the artifacts and I'll show you the drawing.\r\n\r\n#b" + ItcgForge.menu(cm, manuals));
    } else if (status == 2) {
        if (selection < 0 || selection >= manuals.size()) {
            cm.dispose();
            return;
        }
        pick = manuals.get(selection);
        var forge = ItcgForge.forgeFor(pick.result());
        cm.sendYesNo("The #b#t" + pick.result() + "##k. I'll need these artifacts:\r\n\r\n" + ItcgForge.inputs(cm, pick)
            + "\r\nAnd so you know what's coming, Spindle will want this for the #b#t" + forge.result() + "##k:\r\n\r\n"
            + ItcgForge.inputs(cm, forge) + "\r\nShall I draw it?");
    } else if (status == 3) {
        var made = ItcgForge.make(cm, pick);
        if (made.ok()) {
            if (!cm.isQuestCompleted(ItcgRecipes.FOXWIT_QUEST)) {
                cm.forceCompleteQuest(ItcgRecipes.FOXWIT_QUEST);
            }
            cm.sendOk("There! The #b#t" + made.itemId() + "##k. A work of genius, if I say so myself, and I do. "
                + "Take it to Spindle in #bOmega Sector#k. Mind you don't fold it.");
        } else {
            cm.sendOk("Hmm, not yet: " + made.refusal());
        }
        cm.dispose();
    }
}
