/**
 * 9201054 - Lita Lawless (New Leaf City Town Center)
 * The sheriff's bounty board (BotStory): she impounded a smuggler's crate of iTCG card-code goods and pays them out
 * for trophies off the roads she is cleaning (server.itcg, docs/ITCG_MASTERIA_PLAN.md section 4). Her stock quests
 * (Cleaning Up The Streets, Fool's Gold) go through the quest window as before.
 */
var ItcgForge = Java.type('server.itcg.ItcgForge');
var ItcgRecipes = Java.type('server.itcg.ItcgRecipes');

var PAYS = ["one of the space artifacts (a Set 1 piece)",
    "one of the ancient pieces (a Set 2 piece)",
    "one of the ancient pieces (a Set 2 piece)",
    "something rare: a Taru Spirit Feather, White Versal Materia, Ridley's Stone, a Triferium Fuel Cell, a LeFay Jewel or Pharoah's Wrappings"];

var status;
var bounties;
var pick;

function start() {
    status = -1;
    bounties = ItcgRecipes.LITA_BOUNTIES;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    status++;

    if (status == 0) {
        var text = "I'm the new sheriff in town. I'm going to corral this town into a crime-free zone. Can you help me?\r\n\r\n"
            + "I busted a smuggler last month: a crate full of goods from those card-code shops. Can't sell it, can't burn it. "
            + "So it's bounty money now. Bring me proof you've been cleaning up the roads and you get something out of the crate.\r\n\r\n#b";
        for (var i = 0; i < bounties.size(); i++) {
            var input = bounties.get(i).inputs().get(0);
            text += "#L" + i + "##v" + input.itemId() + "# " + input.count() + " #t" + input.itemId() + "# (#c" + input.itemId() + "#)#l\r\n";
        }
        cm.sendSimple(text);
    } else if (status == 1) {
        if (selection < 0 || selection >= bounties.size()) {
            cm.dispose();
            return;
        }
        pick = bounties.get(selection);
        cm.sendYesNo("That bounty pays " + PAYS[selection] + ", whatever comes out of the crate first. I'll need:\r\n\r\n"
            + ItcgForge.inputs(cm, pick) + "\r\nHand them over?");
    } else if (status == 2) {
        var made = ItcgForge.make(cm, pick);
        if (made.ok()) {
            cm.sendOk("Good work, deputy. Out of the crate: #v" + made.itemId() + "# #b#t" + made.itemId() + "##k. "
                + "Folks say Professor Foxwit and John Barricade know what these are for. I just keep the peace.");
        } else {
            cm.sendOk("The law's the law: " + made.refusal());
        }
        cm.dispose();
    }
}
