package server.knowledge;

/**
 * One map as the server loads it. Life and exits come from the {@code info/link} target when the map is a
 * stub, the name/street/flags from the map itself.
 *
 * @param region     region key from the id prefix ("victoria", "orbis", ...); null for event/instanced ids
 * @param town       {@code info/town}; set on interiors too
 * @param hub        {@code town && returnMap == id}: the town itself, not a shop inside it
 * @param returnMap  {@code info/returnMap} (999999999 = none)
 * @param forcedReturn {@code info/forcedReturn}, 0 when absent (999999999): instanced and PQ maps set it
 * @param mobs       distinct mob ids placed on the map, ascending
 * @param mobCounts  spawn points per entry of {@code mobs}
 * @param npcs       NPC ids placed on the map, ascending
 * @param exits      maps a portal leads to, ascending
 * @param mobLevel   spawn-weighted average level of the non-boss mobs, 0 without any
 */
public record MapFact(int id, String name, String street, String region, boolean town, boolean hub,
                      int returnMap, int forcedReturn, IntList mobs, IntList mobCounts, IntList npcs, IntList exits,
                      int mobLevel) {
    /** Total mob spawn points. */
    public int spawnCount() {
        int sum = 0;
        for (int i = 0; i < mobCounts.size(); i++) {
            sum += mobCounts.getInt(i);
        }
        return sum;
    }
}
