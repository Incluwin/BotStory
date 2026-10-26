/**
 * 9201082 - Spindle (Omega Sector)
 * Forges the iTCG gear: every piece from Professor Foxwit's and John Barricade's forging manuals, and one of the
 * eleven Versal weapons from the Glimmer Man's Materia Orb (server.itcg, docs/ITCG_MASTERIA_PLAN.md).
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');
var ItcgRecipes = Java.type('server.itcg.ItcgRecipes');

var status;
var branch;
var options;
var pick;
var weapon;

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
        cm.sendSimple("Hey, there. The name's Spindle. I'm good at fixing stuff when they break, and building things so they won't. Need something? If not, I'm kinda busy here...\r\n\r\n"
            + "#b#L0#Forge something from a forging manual.#l\r\n#L1#Make a weapon from a Materia Orb.#l\r\n#L2#Have you heard of the MapleStory iTCG?#l");
    } else if (status == 1) {
        branch = selection;
        if (branch == 2) {
            cm.sendOk("The card game? Sure. Half the stuff people bring me started as a picture on a card. Professor Foxwit and "
                + "John Barricade draw the manuals from the artifacts, the Glimmer Man makes the orbs, and I do the actual work. "
                + "Nobody ever draws a card of the guy with the hammer.");
            cm.dispose();
            return;
        }
        if (branch == 0) {
            options = ItcgForge.forgeable(cm);
            if (options.isEmpty()) {
                cm.sendOk("No manual, no forging. Professor Foxwit in #bNew Leaf City#k draws them from the space artifacts, "
                    + "John Barricade in the #bBigger Ben lobby#k from the ancient ones. Bring me one and the ore it asks for.");
                cm.dispose();
                return;
            }
            cm.sendSimple("Let's see what you've got.\r\n\r\n#b" + ItcgForge.menu(cm, options));
        } else {
            if (ItcgForge.have(cm, ItcgRecipes.MATERIA_ORB_ID) < 1) {
                cm.sendOk("A weapon from Versal Materia needs a #bMateria Orb#k first. The Glimmer Man in #bNew Leaf City#k "
                    + "makes them from White Versal Materia and seven Black Crystals.");
                cm.dispose();
                return;
            }
            var text = "One orb, one weapon. Which?\r\n\r\n#b";
            var weapons = ItcgRecipes.VERSAL_WEAPONS;
            for (var i = 0; i < weapons.size(); i++) {
                text += "#L" + i + "##v" + weapons.get(i) + "# #t" + weapons.get(i) + "##l\r\n";
            }
            cm.sendSimple(text);
        }
    } else if (status == 2) {
        if (branch == 0) {
            if (selection < 0 || selection >= options.size()) {
                cm.dispose();
                return;
            }
            pick = options.get(selection);
            weapon = pick.result();
            cm.sendYesNo("The #b#t" + weapon + "##k. That takes:\r\n\r\n" + ItcgForge.inputs(cm, pick) + "\r\nGo ahead?");
        } else {
            var weapons = ItcgRecipes.VERSAL_WEAPONS;
            if (selection < 0 || selection >= weapons.size()) {
                cm.dispose();
                return;
            }
            weapon = weapons.get(selection);
            pick = ItcgForge.versal(weapon);
            cm.sendYesNo("#v" + weapon + "# #b#t" + weapon + "##k, from one Materia Orb. Once it's shaped, it stays shaped. Go ahead?");
        }
    } else if (status == 3) {
        var made = ItcgForge.make(cm, pick, weapon);
        if (made.ok()) {
            cm.sendOk("Done. One #b#t" + made.itemId() + "##k" + (made.count() > 1 ? " (" + made.count() + ")" : "")
                + ". Built so it won't break. If it does, that's on you.");
        } else {
            cm.sendOk("Can't do it yet: " + made.refusal());
        }
        cm.dispose();
    }
}
