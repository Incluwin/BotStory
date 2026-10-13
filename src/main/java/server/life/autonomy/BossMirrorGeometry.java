package server.life.autonomy;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Pure shape rules for an attack that a human client reported for a monster it controls.
 * The WZ boxes are stored for a monster that faces left; a noFlip part (Mob.wz info/noFlip,
 * every Zakum part) always uses them as stored, whatever facing bit its client sends.
 */
public final class BossMirrorGeometry {
    /**
     * How tall a character stands above its foot point when an attack box is tested against it. The client hits
     * the body, not the feet: a boss a few px above its floor has boxes that end above everyone on that floor
     * (Capt. Latanica's attack1 and attack2 end at y 0 and 1, and his client hits came 2 to 33 px lower, P14 live
     * 2026-10-13). About an avatar's height.
     */
    static final int BODY_HEIGHT = 60;

    private BossMirrorGeometry() {
    }

    /**
     * The regions a region attack (WZ range/areaCount with range/attackCount) strikes.
     * <ul>
     *   <li>A box or circle attack has none.</li>
     *   <li>An attack that picks at least as many regions as it has uses all of them.</li>
     *   <li>A valid mask (exactly attackCount bits, none at or past areaCount) gives its bits.
     *       The server writes bit i for region i in the same pOption field.</li>
     *   <li>Any other mask (a refused attack sends 0) gives a random sample of attackCount
     *       regions, the same way a server-owned boss picks them.</li>
     * </ul>
     * The list is sorted.
     */
    public static List<Integer> regions(BossAction.OrdinaryAttack attack, int pOption,
                                        RandomGenerator random) {
        if (!usesRegions(attack)) {
            return List.of();
        }
        int areaCount = attack.areaCount();
        int wanted = attack.selectedAreaCount();
        if (wanted >= areaCount) {
            return firstRegions(areaCount);
        }
        int mask = pOption & 0xFFFF;
        boolean inRange = areaCount >= Short.SIZE || (mask >>> areaCount) == 0;
        if (Integer.bitCount(mask) == wanted && inRange) {
            List<Integer> regions = new ArrayList<>();
            for (int region = 0; region < Short.SIZE; region++) {
                if ((mask & (1 << region)) != 0) {
                    regions.add(region);
                }
            }
            return List.copyOf(regions);
        }
        return sample(areaCount, wanted, random);
    }

    /**
     * True when the attack's shape reaches the target: its foot point, or the body {@link #BODY_HEIGHT}
     * px above it. A noFlip part reads as facing left. A region attack tests the given regions, any
     * other attack its box.
     */
    public static boolean hits(BossAction.OrdinaryAttack attack, Point origin, Point target,
                               boolean noFlip, boolean facingLeft, List<Integer> regions) {
        boolean left = noFlip || facingLeft;
        if (usesRegions(attack)) {
            return BossActionGeometry.containsRegions(attack, origin, target, left, regions, BODY_HEIGHT);
        }
        return BossActionGeometry.contains(attack, origin, target, left, List.of(), BODY_HEIGHT);
    }

    /**
     * P15: true when the target stands where an attack whose effect falls at random spots strikes (Papulatus's Clock
     * attack3, its lightning). The spots stand {@code spacing} px apart (the attack's randomSpotSpacing, for a boss
     * that reads it) from the left edge of its box plus {@code phase} (one random phase per attack, 0 to the
     * spacing), and a target within {@code halfWidth} px of one is struck. The controlling client picks the spots and
     * never reports them, so this approximates them. True for a spacing of 0 (no random spots); check {@link #hits}
     * as well.
     */
    public static boolean onRandomSpot(BossAction.OrdinaryAttack attack, Point origin, Point target, boolean noFlip,
                                       boolean facingLeft, int spacing, int phase, int halfWidth) {
        if (spacing <= 0) {
            return true;
        }
        if (origin == null || target == null || attack.lt() == null || attack.rb() == null) {
            return false;
        }
        boolean left = noFlip || facingLeft;
        int minX = left ? origin.x + attack.lt().x : origin.x - attack.rb().x;
        int offset = Math.floorMod(target.x - minX - phase, spacing);
        return Math.min(offset, spacing - offset) <= halfWidth;
    }

    private static boolean usesRegions(BossAction.OrdinaryAttack attack) {
        return attack.areaCount() > 0 && attack.selectedAreaCount() > 0;
    }

    private static List<Integer> firstRegions(int count) {
        List<Integer> regions = new ArrayList<>();
        for (int region = 0; region < count; region++) {
            regions.add(region);
        }
        return List.copyOf(regions);
    }

    /** A Fisher-Yates shuffle cut to the wanted count, as ServerMobAutonomyService does it. */
    private static List<Integer> sample(int areaCount, int wanted, RandomGenerator random) {
        List<Integer> regions = new ArrayList<>(firstRegions(areaCount));
        for (int i = regions.size() - 1; i > 0; i--) {
            int swap = random.nextInt(i + 1);
            int value = regions.get(i);
            regions.set(i, regions.get(swap));
            regions.set(swap, value);
        }
        return regions.stream().limit(wanted).sorted().toList();
    }
}
