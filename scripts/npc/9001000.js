/*
 * Cokebear operator: Henesys <-> Coke Town. Coke Town is a Community Repack map, so only an account that has the
 * Repack game files goes in (server.mods.RepackMaps, docs/REPACK_IMPORT_PLAN.md); he asks an account he does not know
 * yet. In the old event maps he only cheers.
 */
var RepackMaps = Java.type("server.mods.RepackMaps");
var RepackGuide = Java.type("server.mods.RepackGuide");

var HOME = 100000000;      // Henesys, where he stands (spawned by RepackMaps.ENTRIES)
var PLACE_MAP = 219000000; // Coke Town
var PLACE = "Coke Town";
var state = "";

function start() {
    if (RepackMaps.listed(cm.getMapId())) {
        state = "back";
        cm.sendYesNo("Had enough snow and fizz for one day? I can take you back to #m" + HOME + "#.");
        return;
    }
    if (cm.getMapId() != HOME) {
        cm.sendOk("Always Coca-Cola!");
        cm.dispose();
        return;
    }
    offer();
}

function offer() {
    var gate = RepackMaps.get();
    if (!gate.open()) {
        cm.sendOk("The road to Coke Town is snowed in today. Come back another time!");
        cm.dispose();
        return;
    }
    var files = gate.filesWord(cm.getClient());
    if (files == "repack") {
        state = "go";
        cm.sendYesNo("How about going to Coke Town to play!? It's a snowy town full of polar bears, Coke Snails and"
                + " the odd Coke Golem, for adventurers from level 10 up to around 70. Shall I take you there?");
    } else if (files == "cosmic") {
        cm.sendOk(RepackGuide.cosmicLine(PLACE));
        cm.dispose();
    } else {
        state = "ask";
        cm.sendSimple("How about going to Coke Town to play!? " + RepackGuide.question(PLACE));
    }
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    if (state == "back") {
        cm.warp(HOME, 0);
    } else if (state == "go") {
        cm.warp(PLACE_MAP, 0);
    } else if (state == "ask") {
        answer(selection);
        return;
    }
    cm.dispose();
}

function answer(selection) {
    var gate = RepackMaps.get();
    if (selection == 0) {
        gate.recordWord(cm.getClient(), "repack");
        state = "go";
        cm.sendYesNo("Wonderful! Then off we go to Coke Town?");
        return;
    }
    if (selection == 1) {
        gate.recordWord(cm.getClient(), "cosmic");
        cm.sendOk(RepackGuide.cosmicLine(PLACE));
    } else {
        cm.sendOk(RepackGuide.howToCheck());
    }
    cm.dispose();
}
