package server.agents.capabilities.partyquest.apq;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Set;

/**
 * Authoritative Amorian Challenge maps, NPCs, portals, areas, reactors, items and monsters, as AmoriaPQ.js, the stage
 * NPC scripts (9201043-9201048), the portal scripts (gendergo, apqDoor) and Map.wz place them on this server (APQ plan
 * A3). AgentApqDefinitionTest pins each one against its source.
 */
public final class AgentApqDefinition {
    /** Amos' Training Ground, where Amos the Strong takes one Entrance Ticket for the warp to the entrance. */
    public static final int GATE_MAP = 670_010_000;
    public static final int GATE_NPC = 9_201_043;
    /** The Entrance of the Amorian Challenge, where Amos registers the party (AmoriaPQ.js recruitMap). */
    public static final int RECRUIT_MAP = 670_010_100;
    public static final int ENTRY_NPC = 9_201_048;
    /** Stage 1, the Magik Mirror (AmoriaPQ.js entryMap). */
    public static final int MIRROR_MAP = 670_010_200;
    /** Stage 2: Heart Strings, one of three as the Mirror's open gate (go00-go02, apq00-apq02.js) leads. */
    public static final List<Integer> ROPE_MAPS = List.of(670_010_300, 670_010_301, 670_010_302);
    public static final int PLATFORM_MAP = 670_010_400;
    public static final int PASS_MAP = 670_010_500;
    public static final int GATE_RUN_MAP = 670_010_600;
    public static final int BOSS_MAP = 670_010_700;
    /** The all-couples bonus (9201045.js with marriedGroup 1), two minutes. */
    public static final int COUPLES_VAULT_MAP = 670_010_750;
    /** Amos' Vault, the bonus stage (AmoriaPQ.js clearMap), one minute. */
    public static final int BONUS_MAP = 670_010_800;
    /** Where the event leaves everyone (AmoriaPQ.js exitMap); the Glimmer Man there sends a player to the gate map. */
    public static final int EXIT_MAP = 670_011_000;

    /** Amos on stages 1-3 (9201044.js: the Mirror's report, the rope and platform combinations). */
    public static final int PUZZLE_NPC = 9_201_044;
    /** Amos on stages 4-6 and in the couples' vault (9201045.js). */
    public static final int LATE_NPC = 9_201_045;
    /** Amos in the bonus vault (9201046.js). */
    public static final int BONUS_NPC = 9_201_046;
    /** The Glimmer Man: the Mirror's fierry and shard on stage 1, the exit on 670011000 (9201047.js). */
    public static final int GLIMMER_NPC = 9_201_047;

    public static final int ENTRANCE_TICKET = 4_031_592;
    public static final int LIP_LOCK_KEY = 4_031_593;
    /** Lip Lock Keys Amos the Strong takes for one ticket (9201043.js). */
    public static final int KEYS_PER_TICKET = 10;
    /** Geist Balrog's last phase drops it; the leader hands it to Amos for the clear. */
    public static final int GEIST_FANG = 4_031_594;
    /** The Magik Mirror drops it once the Wing Hammer lands on it; the leader hands it to the Glimmer Man. */
    public static final int MIRROR_SHARD = 4_031_595;
    /** Magik Fierry B drops it. */
    public static final int WING_HAMMER = 4_031_596;
    /** Stage 4's mobs drop them; the leader hands Amos fifty, the couples' vault takes thirty-five. */
    public static final int CUPID_CODE_PIECE = 4_031_597;
    public static final int PIECES_FOR_GATES = 50;
    public static final int PIECES_FOR_CAPE = 35;
    /** AmoriaPQ.js setEventExclusives: taken from a member as it leaves the event. */
    public static final Set<Integer> EXCLUSIVE_ITEMS = Set.of(GEIST_FANG, MIRROR_SHARD, WING_HAMMER, CUPID_CODE_PIECE);

    /** AmoriaPQ.js minPlayers and maxPlayers: exactly six, to the end. */
    public static final int PARTY_SIZE = 6;
    public static final int MIN_LEVEL = 40;
    public static final int MAX_LEVEL = 255;
    /** AmoriaPQ.js eventTime: 75 minutes. */
    public static final long EVENT_TIMER_MS = 4_500_000L;
    /** 9201045.js: handing in the pieces with five minutes or more left restarts the timer at four for stage 5. */
    public static final long GATE_RUN_TIMER_MS = 240_000L;
    public static final long GATE_RUN_BANK_THRESHOLD_MS = 300_000L;
    /** 9201045.js: the bonus vault's timer, and the couples' vault's. */
    public static final long BONUS_TIMER_MS = 60_000L;
    public static final long COUPLES_VAULT_TIMER_MS = 120_000L;

    // ---- stage 1, the Magik Mirror (670010200) ----
    /** gendergo.js: female00 takes a girl (gender 1) to female01, male00 a boy (gender 0) to male01. */
    public static final int FEMALE_PORTAL_ID = 16;
    public static final int MALE_PORTAL_ID = 15;
    /** The three gates out of the Mirror: one opens (9201044.js clearStage), linked to apq00-apq02.js. */
    public static final List<String> MIRROR_GATES = List.of("go00", "go01", "go02");
    /** The Magik Mirror's item reactor, and the Wing Hammer's drop point on it (Map.wz 670010200 reactor 0). */
    public static final int MIRROR_REACTOR = 6_702_000;
    public static final Point MIRROR_AT = new Point(0, 152);
    /** Mobs of the gender rooms (AmoriaPQ.js setup instanceMapForceRespawn: they come back while rooms stand empty). */
    public static final Set<Integer> MIRROR_MOBS = Set.of(9_400_533, 9_400_534, 9_400_535);
    public static final int FIERRY = 9_400_518;
    /** The gender rooms lie below the middle floor (y 810-870): Map.wz spawns at y 1097-2070. */
    public static final int MIRROR_ROOMS_BELOW_Y = 950;

    // ---- stage 2, Heart Strings (670010300-302) ----
    /** The floor gates up to the ropes (gate00-02, ids 5-7 on every Heart Strings map): gate n leads above rope n. */
    public static final List<Integer> ROPE_GATE_PORTAL_IDS = List.of(5, 6, 7);
    /** The rope areas (Map.wz area 1-3, getAreas() order): rope 0 left, 1 middle, 2 right. */
    public static final List<Rectangle> ROPE_AREAS = List.of(
            new Rectangle(-672, -1892, 87, 241), new Rectangle(-92, -2010, 94, 298),
            new Rectangle(495, -2130, 90, 269));
    /** Heart Strings' floor (footholds at y -840): a member above it is on a rope, a door ledge or in the air. */
    public static final int ROPE_FLOOR_Y = -840;
    /** 9201044.js: five on the ropes; seven misses spawn a wave and redraw the combination. */
    public static final int PUZZLE_BODIES = 5;
    public static final int ROPE_MISSES_PER_DRAW = 7;
    /** The wave a seventh miss spawns (9201044.js spawnMobs, stage 2): Lip Lock Keys drop here too. */
    public static final Set<Integer> ROPE_WAVE_MOBS = Set.of(9_400_515, 9_400_516, 9_400_517);

    // ---- stage 3, the Twisted Switcher (670010400) ----
    /** The nine platform areas (Map.wz area 0-8): four upper (0-3) at y 43-55, five lower (4-8) at y 194-208. */
    public static final List<Rectangle> PLATFORM_AREAS = List.of(
            new Rectangle(1420, 43, 90, 11), new Rectangle(1587, 43, 89, 12), new Rectangle(1748, 44, 88, 10),
            new Rectangle(1908, 43, 89, 11), new Rectangle(1333, 196, 91, 11), new Rectangle(1500, 195, 87, 12),
            new Rectangle(1663, 196, 90, 12), new Rectangle(1826, 194, 90, 12), new Rectangle(1985, 196, 91, 11));
    /** A miss spawns one slime per member standing right (9201044.js spawnMobs, stage 3). */
    public static final Set<Integer> SLIMES = Set.of(9_400_519, 9_400_520, 9_400_521, 9_400_522);

    // ---- stage 4, Last Man Standing (670010500) ----
    public static final Set<Integer> PASS_MOBS = Set.of(9_400_524, 9_400_530, 9_400_531);

    // ---- stage 5, Fluttering Hearts (670010600) ----
    /** The gates' reactor (6702001, gate00-06): four hits open one (state 4, 9201045.js isAllGatesOpen). */
    public static final int GATE_REACTOR = 6_702_001;
    public static final int GATE_OPEN_STATE = 4;
    public static final int GATE_COUNT = 7;
    /** Each gate's portal (gtXXWP, apqDoor.js: only an open gate passes), ids 16-22, west to east. */
    public static final List<Integer> GATE_PORTAL_IDS = List.of(16, 17, 18, 19, 20, 21, 22);
    /** The shortcut gm00 -> gm01 (portal 14): Amos refuses a party that came that way. */
    public static final int SHORTCUT_PORTAL_ID = 14;
    /** Where everyone alive gathers by Amos with every gate open (Map.wz area 2, 9201045.js getArea(2)). */
    public static final Rectangle GATHER_AREA = new Rectangle(8536, -126, 482, 276);
    public static final Point GATHER_AT = new Point(8800, 90);

    // ---- stage 6, Love Hurts (670010700) ----
    /** Where a member talks to Amos to be sent into the arena (Map.wz area 0, 9201045.js getArea(0)). */
    public static final Rectangle ARENA_DOOR_AREA = new Rectangle(-353, 14, 422, 67);
    public static final Point ARENA_DOOR_AT = new Point(-150, 60);
    /** Geist Balrog: 9400536 in AmoriaPQ.js setup, then its revives 9400537 and 9400514 (Mob.wz revive). */
    public static final List<Integer> BALROG_PHASES = List.of(9_400_536, 9_400_537, 9_400_514);
    public static final int BALROG_LAST_PHASE = 9_400_514;
    /**
     * The arena lies east of Amos (st01 at x 499, the boss at x 942, its teleports in00-in15 from x 360); the arena door
     * side, area 0, ends at x 69, and no walk leads from the arena back to it.
     */
    public static final int ARENA_WEST_X = 300;

    // ---- the vaults ----
    /** The couples' vault's box mobs (reactors 6701000-6701002 spawn them; they drop Cupid Code Pieces). */
    public static final Set<Integer> VAULT_MOBS = Set.of(9_400_523, 9_400_524, 9_400_525);
    public static final Set<Integer> VAULT_BOXES = Set.of(6_701_000, 6_701_001, 6_701_002);
    /** The bonus vault's boxes (reactors 6702003-6702012, rm.sprayItems). */
    public static final Set<Integer> BONUS_BOXES = Set.of(6_702_003, 6_702_004, 6_702_005, 6_702_006, 6_702_007,
            6_702_008, 6_702_009, 6_702_010, 6_702_011, 6_702_012);

    /** Every monster an APQ companion fights. */
    public static final Set<Integer> COMBAT_MOBS = Set.of(
            9_400_533, 9_400_534, 9_400_535, 9_400_518,
            9_400_515, 9_400_516, 9_400_517,
            9_400_519, 9_400_520, 9_400_521, 9_400_522,
            9_400_524, 9_400_530, 9_400_531,
            9_400_536, 9_400_537, 9_400_514,
            9_400_523, 9_400_525);

    private AgentApqDefinition() {
    }

    /** True for a map inside the event (AmoriaPQ.js minMapId to maxMapId). */
    public static boolean isEventMap(int mapId) {
        return mapId >= MIRROR_MAP && mapId <= BONUS_MAP;
    }

    /** Where a member on this map stands in the run. */
    public static AgentApqStage stageOf(int mapId) {
        if (ROPE_MAPS.contains(mapId)) {
            return AgentApqStage.ROPES;
        }
        return switch (mapId) {
            case RECRUIT_MAP -> AgentApqStage.LOBBY;
            case MIRROR_MAP -> AgentApqStage.MIRROR;
            case PLATFORM_MAP -> AgentApqStage.PLATFORMS;
            case PASS_MAP -> AgentApqStage.PASSES;
            case GATE_RUN_MAP -> AgentApqStage.GATES;
            case BOSS_MAP -> AgentApqStage.BOSS;
            case COUPLES_VAULT_MAP -> AgentApqStage.COUPLES_VAULT;
            case BONUS_MAP -> AgentApqStage.BONUS;
            case EXIT_MAP -> AgentApqStage.EXIT;
            default -> AgentApqStage.OUTSIDE;
        };
    }

    /** The stage number AmoriaPQ.js and Amos count by ((map - 670010200) / 100 + 1), 0 off the stage maps. */
    public static int stageNumber(int mapId) {
        if (mapId < MIRROR_MAP || mapId > BOSS_MAP) {
            return 0;
        }
        return (mapId - MIRROR_MAP) / 100 + 1;
    }

    /** The stage's own way on once Amos opened it (next00), or -1 where Amos sends the party on himself. */
    public static int nextPortalId(int mapId) {
        if (mapId == ROPE_MAPS.get(1)) {
            return 11;
        }
        if (ROPE_MAPS.contains(mapId)) {
            return 8;
        }
        return switch (mapId) {
            case PLATFORM_MAP, PASS_MAP -> 2;
            default -> -1;
        };
    }
}
