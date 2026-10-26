/*
 * An NPC of the Community Repack's maps with no script of its own (NPCTalkHandler, docs/REPACK_IMPORT_PLAN.md): it
 * says one of its own String.wz lines. In a town with no road back to the old maps, the town's guide also offers the
 * way back to Three Doors in the Temple of Time (server.mods.RepackGuide).
 */
var RepackGuide = Java.type("server.mods.RepackGuide");
var wayBack = false;

function start() {
    var line = RepackGuide.talkLine(cm.getNpc());
    if (RepackGuide.offersWayBack(cm.getMapId(), cm.getNpc())) {
        wayBack = true;
        cm.sendYesNo((line == "" ? "" : line + "\r\n\r\n")
                + "If you are looking for the road home, I can send you back to #m" + RepackGuide.THREE_DOORS
                + "# in the Temple of Time. Shall I?");
        return;
    }
    cm.sendOk(line == "" ? "..." : line);
    cm.dispose();
}

function action(mode, type, selection) {
    if (wayBack && mode == 1) {
        cm.warp(RepackGuide.THREE_DOORS, 0);
    }
    cm.dispose();
}
