package server.knowledge;

/**
 * One NPC with the maps its WZ placements put it on (DB-placed life is added by the overlay, see
 * {@link WorldKnowledge#npcMaps(int)}).
 *
 * @param func String.wz function text ("Grocer", "Weapon Seller", "Storage Keeper"), empty when none
 */
public record NpcFact(int id, String name, String func, IntList maps) {
}
