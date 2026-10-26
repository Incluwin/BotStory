/**
 *9201103 - Sage
 *@author Ronan
 *
 * iTCG Set 4 (BotStory): Ridley reads a Ridley's Stone with one boss trophy and gives one of 25 random things
 * (server.itcg, docs/ITCG_MASTERIA_PLAN.md). His CWKPQ directions stay as the first line.
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');
var ItcgRecipes = Java.type('server.itcg.ItcgRecipes');

var status;
var offers;
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
        cm.sendSimple("Yes? What do you want?\r\n\r\n#b#L0#What happens inside the keep?#l\r\n#L1#I found a Ridley's Stone.#l");
    } else if (status == 1) {
        if (selection == 0) {
            if (cm.getLevel() >= 100) {
                cm.sendOk("Expeditions are frequently being held inside the Crimsonwood Keep by adventurers like you, where many people from many parties cooperate together, solving puzzles therein and taking down strong enemies, being able to get many prizes in the process. To find more info about this, go ahead inside the keep at the top-right room there.");
            } else {
                cm.sendOk("Inside the Keep, expeditions can be formed to attempt the Crimsonwood Keep PQ, which requires maplers from level 100 or more. It seems you are not suitable for attempting it yet, train some more if you want to attempt it.");
            }
            cm.dispose();
            return;
        }
        offers = [];
        var text = "";
        var recipes = ItcgRecipes.RIDLEY;
        for (var i = 0; i < recipes.size(); i++) {
            if (ItcgForge.ready(cm, recipes.get(i))) {
                var trophy = recipes.get(i).inputs().get(1).itemId();
                text += "#L" + offers.length + "##v" + trophy + "# #t" + trophy + "##l\r\n";
                offers.push(recipes.get(i));
            }
        }
        if (offers.length == 0) {
            cm.sendOk("Ah... an excellent choice of reading, my friend. A #bRidley's Stone#k speaks only beside a trophy of something "
                + "great that fell: a #bBalrog Claw#k, a #bBigfoot's Toe#k, #bErgoth's Jawbone#k, a #bJack O'Lantern#k, a "
                + "#bPapulatus Curl#k, a #bPianus Scale#k or a #bTengu Nose#k. Bring the stone and one of those.");
            cm.dispose();
            return;
        }
        cm.sendSimple("Ah... an excellent choice of reading. Which trophy shall the stone read beside?\r\n\r\n#b" + text);
    } else if (status == 2) {
        if (selection < 0 || selection >= offers.length) {
            cm.dispose();
            return;
        }
        pick = offers[selection];
        cm.sendYesNo("The stone and the #b#t" + pick.inputs().get(1).itemId() + "##k will both be spent, and what the stone "
            + "remembers is not mine to choose. Shall I read it?");
    } else if (status == 3) {
        var made = ItcgForge.make(cm, pick);
        if (made.ok()) {
            cm.sendOk("The stone remembers... #v" + made.itemId() + "# #b#t" + made.itemId() + "##k. It is yours. The Keep keeps what it is given; the stone gives back.");
        } else {
            cm.sendOk("Not now: " + made.refusal());
        }
        cm.dispose();
    }
}
