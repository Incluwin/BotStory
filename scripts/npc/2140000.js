/*
 * Temple Keeper at Three Doors: the doors to the Future (Gate to the Future, Crack in Time, Dimensional Schism,
 * Twilight Perion) and to the towns of Arcane River. All are Community Repack maps, so only an account that has the
 * Repack game files goes through (server.mods.RepackMaps, docs/REPACK_IMPORT_PLAN.md); the keeper asks an account he
 * does not know yet. Arcane River is quiet in this age: no monster walks there.
 */
var RepackMaps = Java.type("server.mods.RepackMaps");
var RepackGuide = Java.type("server.mods.RepackGuide");

var PLACE = "The world beyond these doors";
var FUTURE = [
    [271000000, "Gate to the Future", "Ereve and Henesys in ruins, around level 160 to 185"],
    [272000000, "Crack in Time", "Leafre of the past and the Dark Temple of Time, around level 160"],
    [272030000, "Dimensional Schism", "the Path of Darkness, around level 165"],
    [273000000, "Twilight Perion", "Perion at the end of days, around level 190"]
];
var ARCANE = [
    [450001000, "Nameless Town"],
    [450002000, "Chu Chu Village"],
    [450003000, "Lachelein"],
    [450005000, "Arcana"],
    [450006130, "Trueffet Square, Morass"],
    [450007040, "Base Camp, Esfera"],
    [450009050, "Outpost, Moonbridge"],
    [450011120, "Crash Site, Labyrinth of Suffering"],
    [450012000, "World's Sorrow, Limina"]
];
var state = "";

function start() {
    var gate = RepackMaps.get();
    if (!gate.open()) {
        cm.sendOk("The doors are sealed. Time does not flow past them today.");
        cm.dispose();
        return;
    }
    var files = gate.filesWord(cm.getClient());
    if (files == "repack") {
        menu();
    } else if (files == "cosmic") {
        cm.sendOk(RepackGuide.cosmicLine(PLACE));
        cm.dispose();
    } else {
        state = "ask";
        cm.sendSimple("These doors open on times that have not come yet. " + RepackGuide.question(PLACE));
    }
}

function menu() {
    state = "menu";
    var text = "Time flows through these doors to places that have not happened yet. Where will you go?\r\n";
    for (var i = 0; i < FUTURE.length; i++) {
        text += "#L" + i + "##b" + FUTURE[i][1] + "#k (" + FUTURE[i][2] + ")#l\r\n";
    }
    text += "#L" + FUTURE.length + "##bArcane River#k (the river at the end of time; nothing hunts there now)#l";
    cm.sendSimple(text);
}

function arcaneMenu() {
    state = "arcane";
    var text = "Arcane River sleeps in this age: its spirits and monsters have not woken, so you may walk its towns in"
            + " peace. Which town?\r\n";
    for (var i = 0; i < ARCANE.length; i++) {
        text += "#L" + i + "#" + ARCANE[i][1] + "#l\r\n";
    }
    cm.sendSimple(text);
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    if (state == "ask") {
        var gate = RepackMaps.get();
        if (selection == 0) {
            gate.recordWord(cm.getClient(), "repack");
            menu();
            return;
        }
        if (selection == 1) {
            gate.recordWord(cm.getClient(), "cosmic");
            cm.sendOk(RepackGuide.cosmicLine(PLACE));
        } else {
            cm.sendOk(RepackGuide.howToCheck());
        }
        cm.dispose();
        return;
    }
    if (state == "menu") {
        if (selection == FUTURE.length) {
            arcaneMenu();
            return;
        }
        if (selection >= 0 && selection < FUTURE.length) {
            cm.warp(FUTURE[selection][0], 0);
        }
    } else if (state == "arcane") {
        if (selection >= 0 && selection < ARCANE.length) {
            cm.warp(ARCANE[selection][0], 0);
        }
    }
    cm.dispose();
}
