/**
 * 9201083 - The Glimmer Man (New Leaf City Town Center)
 * Reduces White Versal Materia and 7 Black Crystals to a Materia Orb (quest 4924's synthesis, 20,000 EXP);
 * Spindle in Omega Sector turns the orb into a Versal weapon (server.itcg, docs/ITCG_MASTERIA_PLAN.md).
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');
var ItcgRecipes = Java.type('server.itcg.ItcgRecipes');

var status;

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
        cm.sendSimple("Behold, I am the mighty Glimmer Man! One of the most powerful mages in the world! But you know that already, right?\r\n\r\n"
            + "#b#L0#Make me a Materia Orb.#l\r\n#L1#What is Versal Materia?#l");
    } else if (status == 1) {
        if (selection == 1) {
            cm.sendOk("Versal is a dimension beyond this one. I've been there: diamond skin travels well. Its #bWhite Versal Materia#k "
                + "is unstable, wonderfully so. Bring me a piece and #b7 Black Crystals#k and I'll reduce it to a #bMateria Orb#k. "
                + "My precocious friend #bSpindle#k in #bOmega Sector#k can turn an orb into a weapon of your choosing: "
                + "a blade, a staff, a bow, a claw... eleven of them. Every time you find more Materia, come back.");
            cm.dispose();
            return;
        }
        cm.sendYesNo("For a #bMateria Orb#k I'll need:\r\n\r\n" + ItcgForge.inputs(cm, ItcgRecipes.MATERIA_ORB)
            + "\r\nStand back. Shall I?");
    } else if (status == 2) {
        var made = ItcgForge.orb(cm);
        if (made.ok()) {
            cm.sendOk("BEHOLD! A #bMateria Orb#k, stable as you like. Take it to Spindle in #bOmega Sector#k "
                + "and choose your weapon. Don't drop it. Actually, you may drop it. It's very sturdy now.");
        } else {
            cm.sendOk("Even I can't make an orb from nothing: " + made.refusal());
        }
        cm.dispose();
    }
}
