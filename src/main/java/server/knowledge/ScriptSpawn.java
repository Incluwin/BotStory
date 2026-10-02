package server.knowledge;

/**
 * A mob that a script spawns by literal id, with the maps it appears on when the script's placement is
 * known (npc/&lt;npcId&gt;.js from that NPC's maps, reactor/&lt;id&gt;.js from the reactor's maps, portal and
 * map-enter scripts from the maps that name them, event scripts from their one literal map).
 *
 * @param viaNpc      the NPC whose script spawns it, 0 for other scripts
 * @param revivedFrom 0 for the spawned mob itself; otherwise the spawned mob this one revives from
 */
public record ScriptSpawn(int mobId, String script, IntList maps, int viaNpc, int revivedFrom) {
}
