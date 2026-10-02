package server.knowledge;

/**
 * One resolved game thing with the name bots say for it.
 *
 * @param id       ITEM..QUEST: the game id; REGION/PQ/ROLE: a stable index into {@link NameIndex}'s composites
 * @param name     the String.wz name (the authored name for composites)
 * @param say      the spoken name: the aliases.yaml {@code say}, else the name lowercased without "The ",
 *                 bracketed suffixes or series numerals (plural for mobs)
 * @param ids      REGION: its maps; PQ: lobby map first, then stage maps; ROLE: the NPCs doing the job;
 *                 otherwise just {@code id}
 * @param npc      PQ: the NPC that starts it, else 0
 * @param minLevel PQ: the level band, else 0
 */
public record Entity(EntityKind kind, int id, String name, String say, IntList ids, int npc, int minLevel, int maxLevel) {
    static Entity simple(EntityKind kind, int id, String name, String say) {
        return new Entity(kind, id, name, say, IntList.of(id), 0, 0, 0);
    }

    /** {@code kind << 32 | id}: one long per entity for maps and seeds. */
    public long key() {
        return (long) kind.ordinal() << 32 | (id & 0xffffffffL);
    }
}
