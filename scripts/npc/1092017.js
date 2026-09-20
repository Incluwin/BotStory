/*
    Anonymous Merchant — FM item level & EXP inspector.

    Reads the server-side itemLevel / itemExp on each equipped piece and
    reports them in a plain NPC dialog. Bypasses the v83 client tooltip
    entirely (which shows dashes for any equip whose Character.wz
    `info/level` node is absent — true of every Community-Repack shoulder
    and many other non-GMS-leveling equips the server levels via its
    tier-based curve).

    Output format mirrors Equip.showEquipFeatures:
      '<item>' -> LV: X    EXP: Y / Z     (or MAX LEVEL once capped)

    Cash items are excluded by Equip.showEquipFeatures' cash-guard, so
    they won't appear here even if legacy rows carry non-zero itemLevel.
*/

function start() {
    try {
        const InventoryType = Java.type('client.inventory.InventoryType');
        var chr = cm.getPlayer();
        var equipped = chr.getInventory(InventoryType.EQUIPPED).list();

        var text = "Hmm... every piece of gear carries a trace of its journey. Let me read yours.\r\n\r\n";
        var anyShown = 0;

        var it = equipped.iterator();
        while (it.hasNext()) {
            var eq = it.next();
            var line = eq.showEquipFeatures(cm.getClient());
            if (line !== null && ("" + line).length > 0) {
                text += line;
                anyShown++;
            }
        }

        if (anyShown === 0) {
            text += "#r...but none of your equipped items have awakened yet.#k\r\n\r\n"
                  + "Wear gear with stats and slay monsters \u2014 experience accrues on whatever you're wearing at the time.";
        } else {
            text += "\r\nKeep training, and I'll read them again anytime.";
        }

        cm.sendOk(text);
    } catch (e) {
        // Surface any runtime error in the dialog so we can diagnose instead
        // of the NPC silently doing nothing (see NPCScriptManager.start — a
        // thrown exception from start() is swallowed into the server log).
        cm.sendOk("[dbg] start() threw: " + (e && e.toString ? e.toString() : e));
    }
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
