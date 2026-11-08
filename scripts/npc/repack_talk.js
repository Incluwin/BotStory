/*
 * An NPC of the Community Repack's maps with no script of its own (NPCTalkHandler, docs/REPACK_IMPORT_PLAN.md): it
 * says one of its own String.wz lines. In a town with no road back to the old maps, the town's guide also offers the
 * way back to Three Doors in the Temple of Time (server.mods.RepackGuide). Where a town's neighbour lies past fields
 * far above its visitors, an NPC who stands in both takes them the guarded way (Cobie: Twilight Perion and the Tribal
 * Refugee Camp, docs/REPACK_RESIDENTS_PLAN.md 3.3).
 */
var RepackGuide = Java.type("server.mods.RepackGuide");
var wayBack = false;
var escort = 0;

function start() {
    var line = RepackGuide.talkLine(cm.getNpc());
    escort = RepackGuide.escortTo(cm.getMapId(), cm.getNpc());
    if (escort > 0) {
        cm.sendYesNo((line == "" ? "" : line + "\r\n\r\n")
                + "The open ground between here and #m" + escort + "# belongs to the wild things now. The scouts"
                + " have a way round them. I can take you. Shall we go?");
        return;
    }
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
    if (escort > 0 && mode == 1) {
        cm.warp(escort, 0);
    } else if (wayBack && mode == 1) {
        cm.warp(RepackGuide.THREE_DOORS, 0);
    }
    cm.dispose();
}
