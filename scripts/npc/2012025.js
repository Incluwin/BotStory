function start() {
    if (cm.haveItem(4031576)) {
        cm.sendYesNo("Do you wish to travel to #bAriant#k?");
    } else {
        cm.sendOk("Make sure you got an Ariant ticket to travel. Check your inventory.");
        cm.dispose();
    }
}

function action(mode, type, selection) {
    if (mode <= 0) {
        cm.sendOk("Okay, talk to me if you change your mind!");
        cm.dispose();
        return;
    }
    cm.gainItem(4031576, -1);
    cm.warp(260000100, 0);
    cm.dispose();
}
