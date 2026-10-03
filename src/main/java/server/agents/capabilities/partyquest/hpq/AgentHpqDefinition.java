package server.agents.capabilities.partyquest.hpq;

import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Authoritative local Henesys PQ content contract. */
public final class AgentHpqDefinition {
    public static final int RECRUIT_MAP = 100_000_200;
    public static final int ENTRY_NPC = 101_211_2;
    public static final int TOMMY_NPC = 101_211_3;
    public static final int GROWLIE_NPC = 101_211_4;
    public static final int STAGE_MAP = 910_010_000;
    public static final int CLEAR_MAP = 910_010_100;
    public static final int BONUS_MAP = 910_010_200;
    public static final int EXIT_MAP = 910_010_300;
    public static final int REWARD_EXIT_MAP = 910_010_400;

    public static final int MOON_BUNNY = 9_300_061;
    public static final int RICE_CAKE = 4_001_101;
    public static final int REQUIRED_RICE_CAKES = 10;
    public static final int FLOWER_DROP_X_PX = 18;
    public static final int FLOWER_DROP_Y_PX = 40;

    /** Breakable primroses that produce the six seed colors used by the flower beds. */
    public static final Set<Integer> SEED_SOURCE_REACTORS = Set.of(
            9_102_002, 9_102_003, 9_102_004, 9_102_005, 9_102_006, 9_102_007);

    public static final Point MOON_BUNNY_POSITION = new Point(-183, -433);
    /** Growlie's spot on the spawn floor, where the rice cakes are handed in. */
    public static final Point GROWLIE_POSITION = new Point(429, 83);
    /** HenesysPQ.js minPlayers: an instance with fewer members ends. */
    public static final int MINIMUM_PARTY_SIZE = 3;
    /**
     * A dropped rice cake lies 180 s (config.yaml ITEM_EXPIRE_TIME) and goes on the next 10 s expiry sweep after that;
     * a stack left lying is picked back up no later than this, so the walk to it still ends before it can vanish.
     */
    public static final long RICE_CAKE_RECOLLECT_LATEST_MS = 170_000L;

    /** The six moonflower pads by where they lie, as a companion names them to a player. */
    private static final List<PadPosition> PAD_POSITIONS = List.of(
            new PadPosition(new Point(-359, -692), "up top left"),
            new PadPosition(new Point(4, -690), "up top middle"),
            new PadPosition(new Point(182, -452), "upper right"),
            new PadPosition(new Point(-535, -449), "upper left"),
            new PadPosition(new Point(0, -210), "middle"),
            new PadPosition(new Point(-358, -210), "left of the middle"));

    /** Reachable upper footholds used as sparse guard posts after the Moon Bunny appears. */
    private static final List<Point> DEFENSE_GUARD_POINTS = List.of(
            new Point(-800, -493),
            new Point(-501, -636),
            new Point(-184, -732),
            new Point(120, -637),
            new Point(300, -575),
            new Point(400, -388));

    private static final List<SeedBed> SEED_BEDS = List.of(
            new SeedBed(4_001_095, 9_108_000, "moonflower1"),
            new SeedBed(4_001_096, 9_108_001, "moonflower2"),
            new SeedBed(4_001_097, 9_108_002, "moonflower3"),
            new SeedBed(4_001_098, 9_108_003, "moonflower4"),
            new SeedBed(4_001_099, 9_108_004, "moonflower5"),
            new SeedBed(4_001_100, 9_108_005, "moonflower6"));
    private static final Map<Integer, SeedBed> BED_BY_SEED = SEED_BEDS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(SeedBed::seedItemId, bed -> bed));

    private AgentHpqDefinition() {
    }

    public static List<SeedBed> seedBeds() {
        return SEED_BEDS;
    }

    public static SeedBed seedBed(int seedItemId) {
        SeedBed bed = BED_BY_SEED.get(seedItemId);
        if (bed == null) throw new IllegalArgumentException("not an HPQ seed: " + seedItemId);
        return bed;
    }

    public static boolean isSeed(int itemId) {
        return BED_BY_SEED.containsKey(itemId);
    }

    /**
     * The primrose whose break drops this seed: reactordrops pairs 9102002..9102007 with 4001095..4001100 in order,
     * so the reactor is 9102002 + (seed - 4001095). A non-seed throws.
     */
    public static int sourceReactorFor(int seedItemId) {
        seedBed(seedItemId);
        return Collections.min(SEED_SOURCE_REACTORS) + (seedItemId - SEED_BEDS.getFirst().seedItemId());
    }

    public static List<Point> defenseGuardPoints() {
        return DEFENSE_GUARD_POINTS.stream().map(Point::new).toList();
    }

    /** The name of the pad nearest this point (a moonflower's live position); null for no point. */
    public static String padPositionName(Point point) {
        if (point == null) return null;
        return PAD_POSITIONS.stream()
                .min(java.util.Comparator.comparingDouble(pad -> pad.position().distanceSq(point)))
                .map(PadPosition::name).orElse(null);
    }

    private record PadPosition(Point position, String name) {
    }

    public record SeedBed(int seedItemId, int reactorId, String reactorName) {
        public SeedBed {
            if (seedItemId <= 0 || reactorId <= 0 || reactorName == null || reactorName.isBlank()) {
                throw new IllegalArgumentException("valid HPQ seed-bed values are required");
            }
            reactorName = reactorName.trim();
        }
    }
}
