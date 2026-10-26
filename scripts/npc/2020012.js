/*
 * Spirit of Snow Statue: at the top of Wolf Territory II it opens the way to the Desolate Moor before Lion King's
 * Castle, a Community Repack map, so only an account that has the Repack game files goes in (server.mods.RepackMaps,
 * docs/REPACK_IMPORT_PLAN.md); it asks an account it does not know yet. The moor's own portal leads back here.
 * Elsewhere the statue is only snow.
 */
var RepackMaps = Java.type("server.mods.RepackMaps");
var RepackGuide = Java.type("server.mods.RepackGuide");

var HOME = 211040600;      // Wolf Territory II, where it stands (spawned by RepackMaps.ENTRIES)
var PLACE_MAP = 211060000; // Desolate Moor
var PLACE_PORTAL = "out00";
var PLACE = "Lion King's Castle";
var state = "";

function start() {
    if (cm.getMapId() != HOME) {
        cm.sendOk("A statue of packed snow. It is very cold to the touch.");
        cm.dispose();
        return;
    }
    var gate = RepackMaps.get();
    if (!gate.open()) {
        cm.sendOk("The statue is silent. Snow has buried the path behind it.");
        cm.dispose();
        return;
    }
    var files = gate.filesWord(cm.getClient());
    if (files == "repack") {
        state = "go";
        cm.sendYesNo("The statue's eyes glow faintly. Behind it a narrow path climbs to the Desolate Moor, where the"
                + " Lion King's Castle rises out of the storm. Its guards are fit only for adventurers past level"
                + " 115. Will you take the path?");
    } else if (files == "cosmic") {
        cm.sendOk(RepackGuide.cosmicLine(PLACE));
        cm.dispose();
    } else {
        state = "ask";
        cm.sendSimple("The statue's eyes glow faintly, and a voice asks: " + RepackGuide.question(PLACE));
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
        cm.sendYesNo("The path behind the statue opens. Will you take it to the Desolate Moor?");
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
