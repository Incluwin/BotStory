package server.agents.capabilities.partyquest.ppq;

import java.util.Set;

/** Authoritative Pirate PQ maps, drops, reactors, and combat templates. */
public final class AgentPpqDefinition {
    public static final int RECRUIT_MAP = 251_010_404;
    public static final int ENTRY_NPC = 2_094_000;
    public static final int GUIDE_NPC = 2_094_002;
    public static final int RESCUE_NPC = 2_094_001;
    public static final int ENTRY_MAP = 925_100_000;
    public static final int MEDAL_MAP = 925_100_100;
    public static final int DECK_ONE_MAP = 925_100_200;
    public static final int CHEST_ONE_MAP = 925_100_201;
    /** The hidden side room off the first deck (PiratePQ.js resets it with the chest room). */
    public static final int SIDE_ONE_MAP = 925_100_202;
    public static final int DECK_TWO_MAP = 925_100_300;
    public static final int CHEST_TWO_MAP = 925_100_301;
    public static final int SIDE_TWO_MAP = 925_100_302;
    public static final int DOOR_MAP = 925_100_400;
    public static final int BOSS_MAP = 925_100_500;
    public static final int CLEAR_MAP = 925_100_600;
    public static final int EXIT_MAP = 925_100_700;
    public static final int ROOKIE_MEDAL = 4_001_120;
    public static final int RISING_MEDAL = 4_001_121;
    public static final int VETERAN_MEDAL = 4_001_122;
    public static final int OLD_METAL_KEY = 4_001_117;
    public static final int CHEST_KEY = 4_031_437;
    public static final int MEDALS_PER_WAVE = 20;
    /** Free Etc slots an Agent needs for the clear: its Perfect Pitch (setEventCleared) and Wu Yang's Feather. */
    public static final int CLEAR_ETC_SLOTS = 2;
    public static final int DOOR_COUNT = 4;
    public static final int PARTY_SIZE = 6;
    /** A companion run seats four to six (the user's stock party sizes; PiratePQ.js admits three to six). */
    public static final int COMPANION_MIN_PARTY_SIZE = 4;
    public static final int COMPANION_MAX_PARTY_SIZE = 6;
    /** PiratePQ.js minPlayers: the event ends when a departure would leave fewer registered. */
    public static final int STOCK_FLOOR = 3;
    public static final Set<Integer> MEDALS = Set.of(ROOKIE_MEDAL, RISING_MEDAL, VETERAN_MEDAL);
    /** The items whose count changing in a member's bag is progress: the medals and both keys. */
    public static final Set<Integer> PROGRESS_ITEMS =
            Set.of(ROOKIE_MEDAL, RISING_MEDAL, VETERAN_MEDAL, OLD_METAL_KEY, CHEST_KEY);
    public static final Set<Integer> EXCLUSIVE_ITEMS = Set.of(
            ROOKIE_MEDAL, RISING_MEDAL, VETERAN_MEDAL, OLD_METAL_KEY, CHEST_KEY);
    public static final Set<Integer> DOOR_REACTORS = Set.of(2_519_000, 2_519_001, 2_519_002, 2_519_003);
    public static final Set<Integer> CHEST_REACTORS = Set.of(2_512_001);
    public static final Set<Integer> LORD_PIRATES = Set.of(9_300_105, 9_300_106, 9_300_107, 9_300_119);
    public static final Set<Integer> COMBAT_MOBS = Set.of(
            9_300_108, 9_300_109, 9_300_110, 9_300_111, 9_300_112,
            9_300_113, 9_300_114, 9_300_115, 9_300_116, 9_300_117,
            9_300_118, 9_300_120, 9_300_121, 9_300_122, 9_300_123,
            9_300_124, 9_300_125, 9_300_126,
            9_300_105, 9_300_106, 9_300_107, 9_300_119);

    private AgentPpqDefinition() { }

    public static boolean isEventMap(int mapId) {
        return mapId >= ENTRY_MAP && mapId <= CLEAR_MAP;
    }

    /** Where a member on this map stands in the run. */
    public static AgentPpqStage stageOf(int mapId) {
        return switch (mapId) {
            case RECRUIT_MAP -> AgentPpqStage.LOBBY;
            case ENTRY_MAP -> AgentPpqStage.STAGE_ONE;
            case MEDAL_MAP -> AgentPpqStage.MEDALS;
            case DECK_ONE_MAP -> AgentPpqStage.DECK_ONE;
            case CHEST_ONE_MAP -> AgentPpqStage.CHEST_ONE;
            case SIDE_ONE_MAP -> AgentPpqStage.SIDE_ONE;
            case DECK_TWO_MAP -> AgentPpqStage.DECK_TWO;
            case CHEST_TWO_MAP -> AgentPpqStage.CHEST_TWO;
            case SIDE_TWO_MAP -> AgentPpqStage.SIDE_TWO;
            case DOOR_MAP -> AgentPpqStage.DOORS;
            case BOSS_MAP -> AgentPpqStage.BOSS;
            case CLEAR_MAP -> AgentPpqStage.CLEAR;
            default -> AgentPpqStage.OUTSIDE;
        };
    }

    public static int nextPortalId(int mapId) {
        return switch (mapId) {
            case ENTRY_MAP -> 3;
            case MEDAL_MAP, DOOR_MAP -> 1;
            case DECK_ONE_MAP, DECK_TWO_MAP -> 2;
            default -> -1;
        };
    }
}
