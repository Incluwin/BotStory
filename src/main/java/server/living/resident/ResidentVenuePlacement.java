package server.living.resident;

import server.living.config.Content.RoutineSlot;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Where a Resident stands at a {@code venue:<id>} spot of its routine, so that no two Residents of one town stand on
 * one point at the same hour (found 2026-11-08: Hayate stood under Fumiko, so a double-click opened only the top one;
 * a scan of every town's routines on 2026-11-22 found about 120 such pairs). Before, the venue spot was the Resident's
 * {@code hash(id) % 7}, whoever else stood there.
 *
 * <p>The rule reads content only, never who is in town, so it gives the same point across restarts and re-plans and
 * no Resident moves because another arrives. The town's venue claims (every Resident's routine slot at a venue) are
 * taken in persona-id order: each keeps its hashed spot unless an earlier claim of overlapping hours, or a fixed spot
 * ({@code rest:<n>}, {@code x,y}) of overlapping hours, holds it; then it takes the venue's next free spot. With every
 * spot of the venue held it steps {@link #STEP_PX} along the same ground (never past {@link #MAX_STEPS} steps beyond
 * the venue's spots, never onto a held point or a stock NPC), and with no such step it keeps its hashed spot.</p>
 */
public final class ResidentVenuePlacement {
    /** One offset step when every spot of a venue is held: about one character's width. */
    static final int STEP_PX = 45;
    /** Two points closer than this count as one: a double-click opens only the top character. */
    static final int MIN_GAP_PX = 40;
    /** The farthest an offset goes past the venue's outermost spot, in steps. */
    static final int MAX_STEPS = 2;
    /** A step lands on the venue's ground when the footing there is within this of the spot's own height. */
    static final int GROUND_TOLERANCE_PX = 12;

    /** The town's ground: the footing height at {@code x} on or below {@code aboveY}, or null for none. */
    public interface Ground {
        Integer footingY(int x, int aboveY);
    }

    /** One Resident's routine slot in the town. */
    public record Claim(String personaId, RoutineSlot slot) {
    }

    private ResidentVenuePlacement() {
    }

    /**
     * The point for {@code personaId}'s {@code slot} at a venue, given every claim of the town ({@code claims}, the
     * Resident's own among them), the venue spots ({@code venueSpots}: id to spots, empty for an unknown venue),
     * the point of any other spot ({@code fixedSpot}: null when it has none), the ground and the stock NPCs' points.
     * Null when the slot is not at a known venue.
     */
    public static Point place(String personaId, RoutineSlot slot, List<Claim> claims,
                              Function<String, List<Point>> venueSpots, Function<String, Point> fixedSpot,
                              Ground ground, List<Point> npcPoints) {
        String venue = venueOf(slot);
        if (venue == null || venueSpots.apply(venue).isEmpty()) {
            return null;
        }
        List<Claim> fixed = new ArrayList<>();
        List<Claim> atVenues = new ArrayList<>();
        for (Claim claim : claims) {
            if (claim.slot() == null || !claim.slot().materialize() || claim.slot().spot() == null) {
                continue;
            }
            (venueOf(claim.slot()) != null ? atVenues : fixed).add(claim);
        }
        atVenues.sort(Comparator.comparing(Claim::personaId).thenComparingInt(claim -> claim.slot().fromHour())
                .thenComparingInt(claim -> claim.slot().toHour()));
        List<Point> placed = new ArrayList<>();
        for (Claim claim : atVenues) {
            List<Point> held = new ArrayList<>();
            for (Claim other : fixed) {
                Point at = fixedSpot.apply(other.slot().spot());
                if (at != null && !other.personaId().equals(claim.personaId()) && overlap(other.slot(), claim.slot())) {
                    held.add(at);
                }
            }
            for (int i = 0; i < placed.size(); i++) {
                Claim earlier = atVenues.get(i);
                if (placed.get(i) != null && !earlier.personaId().equals(claim.personaId())
                        && overlap(earlier.slot(), claim.slot())) {
                    held.add(placed.get(i));
                }
            }
            Point at = pick(claim.personaId(), venueSpots.apply(venueOf(claim.slot())), held, npcPoints, ground);
            if (claim.personaId().equals(personaId) && claim.slot().equals(slot)) {
                return at;
            }
            placed.add(at);
        }
        return pick(personaId, venueSpots.apply(venue), List.of(), npcPoints, ground); // its slot was not among the claims
    }

    /** The venue id of a {@code venue:<id>} spot, else null. */
    static String venueOf(RoutineSlot slot) {
        String spot = slot == null ? null : slot.spot();
        return spot != null && spot.startsWith("venue:") ? spot.substring("venue:".length()).trim() : null;
    }

    /** True when the two slots share at least one hour. */
    static boolean overlap(RoutineSlot a, RoutineSlot b) {
        for (int hour = 0; hour < 24; hour++) {
            if (a.covers(hour) && b.covers(hour)) {
                return true;
            }
        }
        return false;
    }

    /** The hashed spot index the Resident had before this rule: still its spot whenever nobody holds it. */
    static int hashedIndex(String personaId, int spots) {
        return Math.floorMod(Math.floorMod(personaId.hashCode(), 7), spots);
    }

    private static Point pick(String personaId, List<Point> spots, List<Point> held, List<Point> npcPoints,
                              Ground ground) {
        int first = hashedIndex(personaId, spots.size());
        for (int i = 0; i < spots.size(); i++) {
            Point spot = spots.get((first + i) % spots.size());
            if (free(spot, held)) {
                return new Point(spot);
            }
        }
        Point base = spots.get(first);
        int minX = spots.stream().mapToInt(point -> point.x).min().orElse(base.x) - MAX_STEPS * STEP_PX;
        int maxX = spots.stream().mapToInt(point -> point.x).max().orElse(base.x) + MAX_STEPS * STEP_PX;
        int reach = (maxX - minX) / STEP_PX + 1;
        for (int k = 1; k <= reach; k++) {
            for (int sign : new int[]{1, -1}) {
                int x = base.x + sign * k * STEP_PX;
                if (x < minX || x > maxX) {
                    continue;
                }
                Integer y = ground == null ? null : ground.footingY(x, base.y - GROUND_TOLERANCE_PX);
                if (y == null || Math.abs(y - base.y) > GROUND_TOLERANCE_PX) {
                    continue; // off the venue's ground: a drop, a wall or another platform
                }
                Point step = new Point(x, y);
                if (free(step, held) && free(step, npcPoints)) {
                    return step;
                }
            }
        }
        return new Point(base); // nowhere to step: stacked as before, rather than off the ground
    }

    private static boolean free(Point spot, List<Point> held) {
        for (Point other : held) {
            if (Math.abs(other.x - spot.x) < MIN_GAP_PX && Math.abs(other.y - spot.y) < MIN_GAP_PX) {
                return false;
            }
        }
        return true;
    }
}
