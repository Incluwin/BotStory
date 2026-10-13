package server.life.autonomy;

import java.awt.Point;
import java.util.List;

/** Coordinate helpers for WZ attack regions, including horizontal mirroring. */
public final class BossActionGeometry {
    private BossActionGeometry() {
    }

    public static boolean contains(BossAction.OrdinaryAttack attack, Point origin,
                                   Point target, boolean facingLeft) {
        return contains(attack, origin, target, facingLeft, List.of());
    }

    public static boolean contains(BossAction.OrdinaryAttack attack, Point origin,
                                   Point target, boolean facingLeft,
                                   List<Integer> selectedRegions) {
        return contains(attack, origin, target, facingLeft, selectedRegions, 0);
    }

    /**
     * Like {@link #contains(BossAction.OrdinaryAttack, Point, Point, boolean, List)} for a target whose
     * body stands {@code bodyHeight} px tall on its foot point: the box hits it when it reaches any
     * part of that height, not only the feet. 0 tests the point.
     */
    public static boolean contains(BossAction.OrdinaryAttack attack, Point origin,
                                   Point target, boolean facingLeft,
                                   List<Integer> selectedRegions, int bodyHeight) {
        Point lt = attack.lt();
        Point rb = attack.rb();
        if (origin == null || target == null || lt == null || rb == null) {
            return false;
        }
        if (attack.hasDistributedRegions()) {
            return selectedRegions.stream().anyMatch(region -> containsRegion(
                    attack, origin, target, facingLeft, region, bodyHeight));
        }
        int minX = facingLeft ? origin.x + lt.x : origin.x - rb.x;
        int maxX = facingLeft ? origin.x + rb.x : origin.x - lt.x;
        return target.x >= minX && target.x <= maxX
                && reaches(origin.y + lt.y, origin.y + rb.y, target.y, bodyHeight);
    }

    /** True when a box from {@code top} to {@code bottom} reaches a body from its feet up {@code height} px. */
    private static boolean reaches(int top, int bottom, int feet, int height) {
        return feet >= top && feet - height <= bottom;
    }

    private static boolean containsRegion(BossAction.OrdinaryAttack attack, Point origin,
                                          Point target, boolean facingLeft, int region, int bodyHeight) {
        if (region < 0 || region >= attack.areaCount()) {
            return false;
        }
        int offset = (attack.areaStart() + region) * 100;
        if (!facingLeft) {
            offset = -offset;
        }
        int minX = origin.x + offset + attack.lt().x;
        int maxX = origin.x + offset + attack.rb().x;
        return target.x >= Math.min(minX, maxX) && target.x <= Math.max(minX, maxX)
                && reaches(origin.y + attack.lt().y, origin.y + attack.rb().y, target.y, bodyHeight);
    }

    /**
     * Tests only the given regions of a region attack, also when the attack uses every region
     * (where {@link #contains} reads the attack as one box). An empty list hits nothing.
     */
    public static boolean containsRegions(BossAction.OrdinaryAttack attack, Point origin,
                                          Point target, boolean facingLeft,
                                          List<Integer> regions) {
        return containsRegions(attack, origin, target, facingLeft, regions, 0);
    }

    /** {@link #containsRegions(BossAction.OrdinaryAttack, Point, Point, boolean, List)} for a body that tall. */
    public static boolean containsRegions(BossAction.OrdinaryAttack attack, Point origin,
                                          Point target, boolean facingLeft,
                                          List<Integer> regions, int bodyHeight) {
        if (origin == null || target == null || attack.lt() == null || attack.rb() == null
                || regions == null) {
            return false;
        }
        return regions.stream().anyMatch(region -> containsRegion(
                attack, origin, target, facingLeft, region, bodyHeight));
    }
}
