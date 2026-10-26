/**
 * 9201102 - Stirgeman (New Leaf City Town Center)
 * iTCG Set 4: takes a Stirgeman piece one rung up for Haunted House parts. Every attempt succeeds (user decision
 * 2026-10-25); where a line forks the player picks the branch (server.itcg, docs/ITCG_MASTERIA_PLAN.md).
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');

var status;
var owned;
var step;
var next;
var target;

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
        owned = ItcgForge.stirgeOwned(cm);
        if (owned.isEmpty()) {
            cm.sendOk("Never fear, Stirgeman is here! Stirgehood HOOO~\r\n\r\n"
                + "A hero's kit grows with the hero! Bring me a #bStirgeman Cape#k, my #bUtility Pants#k or #bSkirt#k, or a "
                + "#bStirge-on-a-String#k, and the parts the ghouls of the #bHaunted House#k leave lying about: Stretchy Material, "
                + "Duct Tape, Coat Hangers, Wads of Gum, Loaded Springs. And Screws. A hero always needs Screws.");
            cm.dispose();
            return;
        }
        var text = "Never fear, Stirgeman is here! Which piece of your kit shall we improve?\r\n\r\n#b";
        for (var i = 0; i < owned.size(); i++) {
            text += "#L" + i + "##v" + owned.get(i) + "# #t" + owned.get(i) + "##l\r\n";
        }
        cm.sendSimple(text);
    } else if (status == 1) {
        if (selection < 0 || selection >= owned.size()) {
            cm.dispose();
            return;
        }
        step = ItcgForge.stirgeStep(owned.get(selection));
        next = step.results();
        if (next.size() > 1) {
            var text = "Ah, a crossroads in a hero's journey! Which way?\r\n\r\n#b";
            for (var i = 0; i < next.size(); i++) {
                text += "#L" + i + "##v" + next.get(i) + "# #t" + next.get(i) + "##l\r\n";
            }
            cm.sendSimple(text);
        } else {
            target = next.get(0);
            status++;
            confirm();
        }
    } else if (status == 2) {
        if (selection < 0 || selection >= next.size()) {
            cm.dispose();
            return;
        }
        target = next.get(selection);
        confirm();
    } else if (status == 3) {
        var made = ItcgForge.make(cm, step, target);
        if (made.ok()) {
            cm.sendOk("Behold! The #b#t" + made.itemId() + "##k! Stirgehood HOOO~");
        } else {
            cm.sendOk("Even heroes must wait: " + made.refusal());
        }
        cm.dispose();
    }
}

function confirm() {
    cm.sendYesNo("Into the #b#t" + target + "##k! That takes:\r\n\r\n" + ItcgForge.inputs(cm, step) + "\r\nReady, citizen?");
}
