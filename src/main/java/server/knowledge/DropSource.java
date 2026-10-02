package server.knowledge;

/**
 * One way to get an item from monsters: a {@code drop_data} row with the mob's spawn sites, or a
 * {@code drop_data_global} row ({@link #global()}, mob 0) that any monster on the continent can drop.
 *
 * @param chance     raw chance out of 1,000,000 (before the drop rate)
 * @param questId    nonzero: only drops while that quest is active
 * @param maps       where the mob spawns: WZ and DB life, script spawns and revive-inherited sites
 * @param scriptOnly the mob has no map placement of its own; it only appears through a script or a revive
 * @param viaNpc     an NPC whose script spawns the mob (or its revive parent), 0 when none
 * @param continent  global drops: the continent id ({@code mapId / 100000000}), -1 for all
 */
public record DropSource(int itemId, int mobId, int chance, int minQuantity, int maxQuantity, int questId,
                         boolean boss, IntList maps, boolean scriptOnly, int viaNpc, boolean global, int continent) {
    private static final double CHANCE_SCALE = 1_000_000d;

    /**
     * Chance per kill after the drop rate, capped at 1. Global drops are rolled flat ({@code MapleMap}
     * ignores the rate for them); boss mobs use the boss drop rate.
     */
    public double effectiveChance(int dropRate, int bossDropRate) {
        if (global) {
            return Math.min(1d, chance / CHANCE_SCALE);
        }
        return Math.min(1d, chance * (double) (boss ? bossDropRate : dropRate) / CHANCE_SCALE);
    }
}
