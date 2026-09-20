function start() {
    if (cm.haveItem(4031074)) {
        cm.sendYesNo("Do you want to go to #bLudibrium#k?");
    } else {
        cm.sendOk("Make sure you got a Ludibrium ticket to travel. Check your inventory.");
        cm.dispose();
    }
}

function action(mode, type, selection) {
    if (mode <= 0) {
        cm.sendOk("Okay, talk to me if you change your mind!");
        cm.dispose();
        return;
    }
    cm.gainItem(4031074, -1);
    cm.warp(220000100, 0);
    cm.dispose();
}
