/*
    Evolving Ring chain — a 4-tier ring progression every new character carries
    in their second ring slot (slot -13). The ring auto-evolves to the next tier
    when its equipment level (gained via mob kills like any levelable equip)
    hits the cap, until the character reaches the terminal Fully Evolved Ring.

    Chain layout (item IDs deliberately sequential so next = current + 1):
        1112499  Evolving Ring I       — base 0/0/0/0 all stats, +1 all per item-level
        1112500  Evolving Ring II      — base +30 all, +250 HP/MP, +5 PAD/MAD, +1 all per item-level
        1112501  Evolving Ring III     — base +60 all, +750 HP/MP, +20 PAD/MAD, +1 all per item-level
        1112502  Fully Evolved Ring    — terminal: +100 all, +1500 HP/MP, +30 PAD/MAD; cannot level

    Pacing model (rev 2 — synced to player level curve):
      Ring exp gain is the player's earned EXP from any source, 1:1, with no
      element / mastery / jitter modifier (see Equip.gainItemExp evolving-ring
      branch). That makes ring progression piggy-back exactly on the player's
      level-up progression, with these transmutation thresholds:
        Ring I   -> Ring II              at player exp earned to reach lvl 50
        Ring II  -> Ring III             at player exp earned from lvl 50 -> 110
        Ring III -> Fully Evolved Ring   at player exp earned from lvl 110 -> 160
      Total exp to fully evolve == sum of player exp[1..159] == player exp 1->160.
      Per-ring-level threshold within a tier is flat: tierTotal / 28 (28
      level-ups per tier since CAP_LEVEL = 29 and rings start at level 1).
      All personal-exp sources feed the ring — mob kills, quests, cheer, family
      rep, etc. — because Character.gainExp now calls increaseEquipExp on every
      positive personal-exp gain. Party-share exp is intentionally excluded so
      a leech can't accelerate someone else's ring.

      Stat-ceiling parity: each level grants +1 STR/DEX/INT/LUK plus a one-time
      +1 milestone bonus at item-level 15 (see Equip.gainLevel evolving-ring
      branch), giving 27×1 + 1×2 = 29 stat per stat per tier — matches the
      original pre-rev-2 ceiling (29 level-ups × +1) so the CAP_LEVEL change
      doesn't shrink the ring's endgame value.

    Key invariants enforced elsewhere:
      - Granted at character creation (CharacterFactory)
      - Cannot be unequipped, dropped, or moved (ItemMoveHandler)
      - Ignores reqLevel/reqStat wear checks (ItemInformationProvider) — like the Quest Ring
      - Stat gain on level up is +1 STR/DEX/INT/LUK only (Equip.gainLevel) — overrides the
        generic milestone + per-slot bonus path for these IDs
      - Fully Evolved Ring has no info/level scaffold in the WZ → naturally can't accumulate
        item-exp; this class also returns 0 for getMaxLevelFor(FULLY_EVOLVED_RING)
*/
package client.inventory;

import constants.game.ExpTable;

public final class EvolvingRing {
    private EvolvingRing() {}

    public static final int EVOLVING_RING_I    = 1112499;
    public static final int EVOLVING_RING_II   = 1112500;
    public static final int EVOLVING_RING_III  = 1112501;
    public static final int FULLY_EVOLVED_RING = 1112502;

    /** Cap that triggers transmutation to the next tier.
     *  Rings start at level 1 → 28 level-ups (level 1 → CAP_LEVEL) per tier. */
    public static final int CAP_LEVEL = 29;

    /** Player-level transmutation anchors for tier I → II, II → III, III → fully evolved.
     *  Per-tier total ring exp == sum of player exp[startLevel .. endLevel-1]. */
    private static final int TIER_I_PLAYER_LEVEL_START   = 1;
    private static final int TIER_I_PLAYER_LEVEL_END     = 50;
    private static final int TIER_II_PLAYER_LEVEL_END    = 110;
    private static final int TIER_III_PLAYER_LEVEL_END   = 160;

    private static final long TIER_I_TOTAL_EXP   =
            sumPlayerExp(TIER_I_PLAYER_LEVEL_START, TIER_I_PLAYER_LEVEL_END);
    private static final long TIER_II_TOTAL_EXP  =
            sumPlayerExp(TIER_I_PLAYER_LEVEL_END,   TIER_II_PLAYER_LEVEL_END);
    private static final long TIER_III_TOTAL_EXP =
            sumPlayerExp(TIER_II_PLAYER_LEVEL_END,  TIER_III_PLAYER_LEVEL_END);

    private static long sumPlayerExp(int startLevel, int endLevel) {
        long total = 0L;
        for (int n = startLevel; n < endLevel; n++) {
            total += ExpTable.getExpNeededForLevel(n);
        }
        return total;
    }

    /** Returns true if the given itemId is any ring in the evolving chain (including terminal). */
    public static boolean isChainRing(int itemId) {
        return itemId == EVOLVING_RING_I
            || itemId == EVOLVING_RING_II
            || itemId == EVOLVING_RING_III
            || itemId == FULLY_EVOLVED_RING;
    }

    /** Returns true if the given itemId can still gain item-exp & evolve. */
    public static boolean isLevelable(int itemId) {
        return itemId == EVOLVING_RING_I
            || itemId == EVOLVING_RING_II
            || itemId == EVOLVING_RING_III;
    }

    /**
     * Returns the next ring in the chain, or -1 if the input is the terminal ring or not a chain ring.
     * Uses sequential ID layout: I→II→III→FullyEvolved.
     */
    public static int nextRingId(int currentItemId) {
        if (currentItemId == EVOLVING_RING_I)   return EVOLVING_RING_II;
        if (currentItemId == EVOLVING_RING_II)  return EVOLVING_RING_III;
        if (currentItemId == EVOLVING_RING_III) return FULLY_EVOLVED_RING;
        return -1;
    }

    /**
     * Maximum item-level cap for chain rings (overrides the WZ reqLevel-based tier).
     * The 3 levelable rings cap at CAP_LEVEL; the Fully Evolved Ring is non-levelable.
     */
    public static int getMaxLevelFor(int itemId) {
        return isLevelable(itemId) ? CAP_LEVEL : 0;
    }

    /** Total ring exp needed to cap this tier (sum of player exp across the tier's player-level span). */
    public static long getTierTotalExp(int itemId) {
        if (itemId == EVOLVING_RING_I)   return TIER_I_TOTAL_EXP;
        if (itemId == EVOLVING_RING_II)  return TIER_II_TOTAL_EXP;
        if (itemId == EVOLVING_RING_III) return TIER_III_TOTAL_EXP;
        return 0L;
    }

    /**
     * Flat ring exp required for one item-level-up within this tier.
     * 28 level-ups (level 1 → CAP_LEVEL=29) per tier, so threshold = tierTotal / 28.
     * Returns 0 for non-levelable IDs so the caller knows to skip.
     */
    public static int getExpPerLevel(int itemId) {
        if (!isLevelable(itemId)) return 0;
        long total = getTierTotalExp(itemId);
        int transitions = CAP_LEVEL - 1;  // ring 1 -> CAP_LEVEL == 28 transitions
        return (int) Math.max(1L, total / transitions);
    }
}
