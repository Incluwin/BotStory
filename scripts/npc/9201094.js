/**
 * 9201094 - Corine (New Leaf City Town Center), Taru Spirit
 * iTCG Set 3: wakes the spirit in a Taru weapon. Level 10 -> -suma (a Taru Spirit Feather, 5 Wisdom Crystals,
 * 10 Jungle Lilies, a pet of level 15+ out), -suma -> -magna (2 feathers, 15 crystals, 50 lilies, a pet of level 30+)
 * (server.itcg, docs/ITCG_MASTERIA_PLAN.md).
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');

var status;
var options;
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
        options = ItcgForge.corineOptions(cm);
        if (options.isEmpty()) {
            cm.sendOk("Salu o'kahari, friend! Stop scratching, Khaffy, that's bad manners.\r\n\r\n"
                + "The Taru weapons, Akha, Xaru, Kuma, Maku and Umaru, sleep until a spirit wakes them. Bring me one, a "
                + "#bTaru Spirit Feather#k, #bWisdom Crystals#k and #bJungle Lilies#k, and have a pet with you that has grown with you. "
                + "The spirit only answers someone who has cared for a creature a long while.");
            cm.dispose();
            return;
        }
        cm.sendSimple("Salu o'kahari, friend! I can feel a sleeping spirit on you. Which weapon shall we wake?\r\n\r\n#b"
            + ItcgForge.menu(cm, options));
    } else if (status == 1) {
        if (selection < 0 || selection >= options.size()) {
            cm.dispose();
            return;
        }
        pick = options.get(selection);
        cm.sendYesNo("To wake #v" + pick.inputs().get(0).itemId() + "# into the #b#t" + pick.result() + "##k we'll need:\r\n\r\n"
            + ItcgForge.inputs(cm, pick) + "\r\nThe weapon you carry becomes the new one; what it was goes back to the forest. Shall we?");
    } else if (status == 2) {
        var made = ItcgForge.make(cm, pick);
        if (made.ok()) {
            cm.sendOk("O'kahari! The #b#t" + made.itemId() + "##k is awake. Khaffy likes it. Khaffy doesn't like anything.");
        } else {
            cm.sendOk("The spirit won't come yet: " + made.refusal());
        }
        cm.dispose();
    }
}
