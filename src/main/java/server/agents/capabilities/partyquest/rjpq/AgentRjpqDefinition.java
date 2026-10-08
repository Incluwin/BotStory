package server.agents.capabilities.partyquest.rjpq;

import java.awt.Point;
import java.util.List;
import java.util.Set;

/**
 * Authoritative Romeo and Juliet PQ content, given as the Zenumist side (AgentRjpqSide.map shifts a map to the
 * Alcadno side). Sources: scripts/event/MagatiaPQ_Z.js, the stage NPC, portal and reactor scripts, Map.wz 926100xxx.
 */
public final class AgentRjpqDefinition {
    public static final int BOOKS_MAP = 926_100_000;
    public static final int TUNNEL_MAP = 926_100_001;
    public static final int BEAKER_MAP = 926_100_100;
    public static final int SPECIAL_LAB_MAP = 926_100_200;
    public static final int DARK_LAB_ONE_MAP = 926_100_201;
    public static final int DARK_LAB_TWO_MAP = 926_100_202;
    public static final int OFFICE_MAP = 926_100_203;
    public static final int LAB_TUNNEL_MAP = 926_100_300;
    public static final int FIRST_LAB_UNIT_MAP = 926_100_301;
    public static final int LAST_LAB_UNIT_MAP = 926_100_304;
    public static final int CENTER_ENTRANCE_MAP = 926_100_400;
    public static final int CENTER_LAB_MAP = 926_100_401;
    public static final int TRACES_MAP = 926_100_500;
    public static final int REWARD_MAP = 926_100_600;
    public static final int EXIT_MAP = 926_100_700;

    /** The reward-room NPC "Romeo & Juliet" on both sides (2112018). */
    public static final int REWARD_NPC_ID = 2_112_018;

    public static final int LIQUID_ITEM_ID = 4_001_132;
    public static final int CARD_KEY_ITEM_ID = 4_001_133;
    /** Dropped by Dark Lab 1's real box (2612002). */
    public static final int ALCADNO_FILES_ITEM_ID = 4_001_134;
    /** Dropped by Dark Lab 2's real box (2612001). */
    public static final int ZENUMIST_FILES_ITEM_ID = 4_001_135;
    /** MagatiaPQ_Z.js :73: the items a player loses when he leaves the event (letters, liquid, key, files). */
    public static final Set<Integer> EXCLUSIVE_ITEM_IDS =
            Set.of(4_001_130, 4_001_131, LIQUID_ITEM_ID, CARD_KEY_ITEM_ID, ALCADNO_FILES_ITEM_ID,
                    ZENUMIST_FILES_ITEM_ID);

    public static final int HOMUN_MOB_ID = 9_300_145;
    public static final int CYTI_MOB_ID = 9_300_146;
    public static final int HOMUNCULUS_MOB_ID = 9_300_147;
    public static final int NEO_HUROID_MOB_ID = 9_300_148;
    public static final int IRON_MUTAE_MOB_ID = 9_300_143;
    public static final int MITHRIL_MUTAE_MOB_ID = 9_300_144;
    public static final int GUARD_HUROID_MOB_ID = 9_300_150;
    public static final Set<Integer> TUNNEL_MOB_IDS = Set.of(HOMUN_MOB_ID, CYTI_MOB_ID);
    public static final Set<Integer> OFFICE_MOB_IDS = Set.of(IRON_MUTAE_MOB_ID, MITHRIL_MUTAE_MOB_ID);

    public static final int BEAKER_REACTOR_ID = 2_618_000;
    public static final List<String> BEAKER_NAMES = List.of("beaker1", "beaker2", "beaker3");
    /** A beaker that reached this state was counted by its hit() (2618000.js; Reactor.wz 2618000 states 7-8). */
    public static final int BEAKER_COUNTED_STATE = 7;
    /**
     * An item reactor's trigger area around its position (Reactor.wz state 0 event lt/rb): an item dropped so that it
     * lands inside it, edges included, is the one the reactor takes.
     */
    public record ItemArea(int left, int top, int right, int bottom) {
        public boolean contains(Point reactor, Point at) {
            return reactor != null && at != null && at.x >= reactor.x + left && at.x <= reactor.x + right
                    && at.y >= reactor.y + top && at.y <= reactor.y + bottom;
        }
    }

    /** Reactor.wz 2618000 item area around a beaker: lt(-26,-24) rb(24,9); a liquid dropped inside it is poured. */
    public static final ItemArea BEAKER_AREA = new ItemArea(-26, -24, 24, 9);
    /** The exit portal of stages 1-4: the books, the tunnel, the beakers and the Special Lab (rnj1_pt00 ... rnj3_out). */
    public static final String STAGE_EXIT_PORTAL = "pt00";
    /** The office's exit to the Lab Units (rnj6_out; open when the reactor rnj6_out is). Its pt00 is a script spot. */
    public static final String OFFICE_EXIT_PORTAL = "out00";
    /** The office spawn point (-540,200), far from Yulete (252,243): where companions stand back while he is there. */
    public static final String OFFICE_STAND_BACK_PORTAL = "sp";
    public static final int REAL_BOX_LAB_ONE_REACTOR_ID = 2_612_002;
    public static final int REAL_BOX_LAB_TWO_REACTOR_ID = 2_612_001;
    public static final int DECOY_BOX_REACTOR_ID = 2_612_003;
    public static final Set<Integer> BOX_REACTOR_IDS =
            Set.of(REAL_BOX_LAB_ONE_REACTOR_ID, REAL_BOX_LAB_TWO_REACTOR_ID, DECOY_BOX_REACTOR_ID);

    /** Reactor and portal-script name suffixes, after the side's prefix (rnj / jnr). */
    public static final String BEAKER_DOOR = "2_door";
    public static final String LAB_ONE_DOOR = "3_out1";
    public static final String LAB_TWO_DOOR = "3_out2";
    public static final String OFFICE_DOOR = "3_out3";
    public static final String LAB_ONE_EXIT = "31_out";
    public static final String LAB_TWO_EXIT = "32_out";
    public static final String OFFICE_EXIT = "6_out";

    /** Reactor.wz 2618002 (rnj3_out1, the Lab 1 door) and 2618001 (rnj3_out2, Lab 2): one card key opens each. */
    public static final ItemArea LAB_ONE_DOOR_AREA = new ItemArea(-26, -16, 32, 9);
    public static final ItemArea LAB_TWO_DOOR_AREA = new ItemArea(-32, -16, 25, 9);
    /** Map.wz 926100200: where each card-key door stands. */
    public static final Point LAB_ONE_DOOR_POSITION = new Point(-1310, 176);
    public static final Point LAB_TWO_DOOR_POSITION = new Point(812, 175);
    /** MagatiaPQ_Z.js :157: Yulete in his office, and 2112000.js's rule that nobody stands within 310 px of him. */
    public static final Point OFFICE_YULETE_POSITION = new Point(252, 243);
    public static final int YULETE_ALONE_RADIUS_PX = 310;
    /** MagatiaPQ_Z.js :158: Yulete in the Center Lab, whose talk calls the boss. */
    public static final Point CENTER_YULETE_POSITION = new Point(200, 100);

    /** Lab Units 926100301-304: ten rows of four row portals, named pt<row><column>, portal id 15 + 4*row + col. */
    public static final int LAB_UNIT_ROWS = 10;
    public static final int LAB_UNIT_COLUMNS = 4;
    public static final int LAB_UNIT_FIRST_ROW_PORTAL_ID = 15;
    public static final List<Integer> LAB_UNIT_ROW_Y =
            List.of(-52, -352, -656, -944, -1250, -1534, -1842, -2128, -2434, -2720);
    public static final String LAB_UNIT_FAIL_PORTAL = "fail";
    public static final String LAB_UNIT_SUCCESS_PORTAL = "success";
    public static final String LAB_UNIT_EXIT_PORTAL = "out00";

    private AgentRjpqDefinition() {
    }

    /** One of the Zenumist event maps, from the first stage to the exit (926100000-926100700). */
    public static boolean isEventMap(int zenumistMapId) {
        return zenumistMapId == BOOKS_MAP || zenumistMapId == TUNNEL_MAP
                || (zenumistMapId >= BEAKER_MAP && zenumistMapId <= OFFICE_MAP)
                || (zenumistMapId >= LAB_TUNNEL_MAP && zenumistMapId <= LAST_LAB_UNIT_MAP)
                || zenumistMapId == CENTER_ENTRANCE_MAP || zenumistMapId == CENTER_LAB_MAP
                || zenumistMapId == TRACES_MAP || zenumistMapId == REWARD_MAP || zenumistMapId == EXIT_MAP;
    }

    public static boolean isLabUnit(int zenumistMapId) {
        return zenumistMapId >= FIRST_LAB_UNIT_MAP && zenumistMapId <= LAST_LAB_UNIT_MAP;
    }

    /** The room number (1-4) of a Lab Unit map, the N of its entry portal rnj4_rN. */
    public static int labUnitRoom(int zenumistMapId) {
        return isLabUnit(zenumistMapId) ? zenumistMapId - LAB_TUNNEL_MAP : -1;
    }

    /** The portal id of row portal pt<row><column> (rnj5_rp.js warps a right guess to this id + 4). */
    public static int labUnitPortalId(int row, int column) {
        if (row < 0 || row >= LAB_UNIT_ROWS || column < 0 || column >= LAB_UNIT_COLUMNS) {
            throw new IllegalArgumentException("no Lab Unit row portal " + row + "/" + column);
        }
        return LAB_UNIT_FIRST_ROW_PORTAL_ID + LAB_UNIT_COLUMNS * row + column;
    }

    public static String labUnitPortalName(int row, int column) {
        labUnitPortalId(row, column);
        return "pt" + row + column;
    }
}
