/*
 * Ralph the wanderer: on the top right ledge of Suburban Area 2 he knows the way into Ulu City, a Community Repack map,
 * so only an account that has the Repack game files goes in (server.mods.RepackMaps, docs/REPACK_IMPORT_PLAN.md); he
 * asks an account he does not know yet. Ulu City's own west portal leads back here. Elsewhere he only talks.
 */
var RepackMaps = Java.type("server.mods.RepackMaps");
var RepackGuide = Java.type("server.mods.RepackGuide");

var HOME = 540020100;      // Suburban Area 2, where he stands (spawned by RepackMaps.ENTRIES)
var PLACE_MAP = 541020000; // Ulu City Entrance
var PLACE_PORTAL = "west00";
var PLACE = "Ulu City";
var state = "";

function start() {
    if (cm.getMapId() != HOME) {
        var line = RepackGuide.talkLine(cm.getNpc());
        cm.sendOk(line == "" ? "I have walked every road on this island. Well, nearly every road." : line);
        cm.dispose();
        return;
    }
    var gate = RepackMaps.get();
    if (!gate.open()) {
        cm.sendOk("The path over the ridge is blocked today. Come back another time.");
        cm.dispose();
        return;
    }
    var files = gate.filesWord(cm.getClient());
    if (files == "repack") {
        state = "go";
        cm.sendYesNo("Past this ridge lies Ulu City, an old estate swallowed by the jungle. The creatures there grow"
                + " stronger the deeper you go, from around level 30 to past 100. Shall I show you the way in?");
    } else if (files == "cosmic") {
        cm.sendOk(RepackGuide.cosmicLine(PLACE));
        cm.dispose();
    } else {
        state = "ask";
        cm.sendSimple("Looking for the path to Ulu City? " + RepackGuide.question(PLACE));
    }
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    if (state == "go") {
        cm.warp(PLACE_MAP, PLACE_PORTAL);
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
        cm.sendYesNo("Good. Follow me over the ridge to Ulu City?");
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
