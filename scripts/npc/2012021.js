function start() {
    if (cm.haveItem(4031331)) {
        cm.sendYesNo("Do you wish to travel to #bLeafre#k?");
    } else {
        cm.sendOk("Make sure you got a Leafre ticket to travel. Check your inventory.");
        cm.dispose();
    }
}

function action(mode, type, selection) {
    if (mode <= 0) {
        cm.sendOk("Okay, talk to me if you change your mind!");
        cm.dispose();
        return;
    }
    cm.gainItem(4031331, -1);
    cm.warp(240000100, 0);
    cm.dispose();
}
