var mode_instant = false;

function start() {
    if (!cm.haveItem(4031045)) {
        cm.sendOk("Make sure you got a Orbis ticket to travel. Check your inventory.");
        cm.dispose();
        return;
    }
    cm.sendSimple("How would you like to travel to #bOrbis#k?\r\n"
        + "#L0#Take me there #rinstantly#k.#l\r\n"
        + "#L1#Board the ship (may encounter #rCrimson Balrog#k on the way).#l");
}

function action(mode, type, selection) {
    if (mode < 1) {
        cm.sendOk("Okay, talk to me if you change your mind!");
        cm.dispose();
        return;
    }

    if (selection == 0) {
        cm.gainItem(4031045, -1);
        cm.warp(200000100, 0);
        cm.dispose();
        return;
    }

    var em = cm.getEventManager("Boats");
    if (em.getProperty("entry") == "true") {
        cm.warp(101000301);
        cm.gainItem(4031045, -1);
    } else {
        cm.sendOk("The boat to Orbis is already travelling, please be patient for the next one.");
    }
    cm.dispose();
}
