package server.agents.capabilities.partyquest.rjpq;

import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;

import java.awt.Point;
import java.util.Locale;
import java.util.Set;

/**
 * The two mirrored Romeo and Juliet PQs of Magatia. Zenumist (Romeo's hidden room, event MagatiaPQ_Z) and Alcadno
 * (Juliet's hidden room, MagatiaPQ_A) run the same seven stages on maps 10000 apart; only the NPCs, the letter, the
 * marble, the escort, the boss and the reactor and portal names differ. Every stage map below is given as the
 * Zenumist map and shifted with {@link #map(int)}.
 */
public enum AgentRjpqSide {
    ZENUMIST("rjpq", "MagatiaPQ_Z", 261_000_011, 0,
            2_112_004, 2_112_007, 2_112_006, 2_112_000, 2_112_001,
            4_001_131, 4_001_159, 9_300_137, Set.of(9_300_139, 9_300_140), "rnj",
            new Point(-414, -118), "romeo", "juliet"),
    ALCADNO("rjpqj", "MagatiaPQ_A", 261_000_021, 10_000,
            2_112_003, 2_112_013, 2_112_005, 2_112_010, 2_112_011,
            4_001_130, 4_001_160, 9_300_138, Set.of(9_300_151, 9_300_152), "jnr",
            new Point(-412, -130), "juliet", "romeo");

    /** Both sides: four players, level 71 to 85, a 45-minute event timer (MagatiaPQ_Z.js / _A.js :27-37). */
    public static final int PARTY_SIZE = 4;
    public static final int MIN_LEVEL = 71;
    public static final int MAX_LEVEL = 85;
    public static final long EVENT_TIMER_MS = 45L * 60_000L;

    private final String key;
    private final String eventManagerName;
    private final int recruitMapId;
    private final int mapOffset;
    private final int entryNpcId;
    private final int bookNpcId;
    private final int claimNpcId;
    private final int officeYuleteNpcId;
    private final int tracesYuleteNpcId;
    private final int letterItemId;
    private final int marbleItemId;
    private final int escortMobId;
    private final Set<Integer> bossMobIds;
    private final String reactorPrefix;
    private final Point escortLedge;
    private final String npcName;
    private final String captiveName;

    AgentRjpqSide(String key, String eventManagerName, int recruitMapId, int mapOffset,
                  int entryNpcId, int bookNpcId, int claimNpcId, int officeYuleteNpcId, int tracesYuleteNpcId,
                  int letterItemId, int marbleItemId, int escortMobId, Set<Integer> bossMobIds, String reactorPrefix,
                  Point escortLedge, String npcName, String captiveName) {
        this.key = key;
        this.eventManagerName = eventManagerName;
        this.recruitMapId = recruitMapId;
        this.mapOffset = mapOffset;
        this.entryNpcId = entryNpcId;
        this.bookNpcId = bookNpcId;
        this.claimNpcId = claimNpcId;
        this.officeYuleteNpcId = officeYuleteNpcId;
        this.tracesYuleteNpcId = tracesYuleteNpcId;
        this.letterItemId = letterItemId;
        this.marbleItemId = marbleItemId;
        this.escortMobId = escortMobId;
        this.bossMobIds = bossMobIds;
        this.reactorPrefix = reactorPrefix;
        this.escortLedge = escortLedge;
        this.npcName = npcName;
        this.captiveName = captiveName;
    }

    /** The party-quest key: "rjpq" for Romeo's side, "rjpqj" for Juliet's. */
    public String key() {
        return key;
    }

    public String eventManagerName() {
        return eventManagerName;
    }

    public int recruitMapId() {
        return recruitMapId;
    }

    /** The side's copy of a Zenumist stage map (926100xxx -> 926110xxx on the Alcadno side). */
    public int map(int zenumistMapId) {
        return zenumistMapId + mapOffset;
    }

    /** The Zenumist map that {@code mapId} mirrors, or -1 when it is not one of this side's event maps. */
    public int zenumistMap(int mapId) {
        int zenumist = mapId - mapOffset;
        return AgentRjpqDefinition.isEventMap(zenumist) ? zenumist : -1;
    }

    /** Romeo (2112004) or Juliet (2112003) in the hidden room; inside the PQ the same NPC only offers a retreat. */
    public int entryNpcId() {
        return entryNpcId;
    }

    /** The stage-1 "Investigation Result" books (2112007 / 2112013), hidden NPCs keyed by their object id. */
    public int bookNpcId() {
        return bookNpcId;
    }

    /** Romeo (2112006) or Juliet (2112005): the file hand-in on the Special Lab and the claim after the boss. */
    public int claimNpcId() {
        return claimNpcId;
    }

    /** Yulete in the office and the Center Lab (2112000 / 2112010). */
    public int officeYuleteNpcId() {
        return officeYuleteNpcId;
    }

    /** Yulete on the Traces map (2112001 / 2112011), the hard-mode claim step. */
    public int tracesYuleteNpcId() {
        return tracesYuleteNpcId;
    }

    /** The stage-1 letter the side's claim NPC takes first: Juliet's (4001131) or Romeo's (4001130). */
    public int letterItemId() {
        return letterItemId;
    }

    public int marbleItemId() {
        return marbleItemId;
    }

    /** The friendly to keep alive in the Center Lab: Juliet (9300137) or Romeo (9300138). */
    public int escortMobId() {
        return escortMobId;
    }

    /** Frankenroid and Angry Frankenroid of this side. */
    public Set<Integer> bossMobIds() {
        return bossMobIds;
    }

    /** "rnj" or "jnr": the side's reactor and portal-script name prefix (rnj3_out1, jnr6_out...). */
    public String reactorName(String suffix) {
        return reactorPrefix + suffix;
    }

    /** Where the escort stands in the Center Lab (Map.wz 926100401 / 926110401). */
    public Point escortLedge() {
        return new Point(escortLedge);
    }

    /** The side's NPC as companions name him or her: "romeo" or "juliet". */
    public String npcName() {
        return npcName;
    }

    /** The one Yulete took, as companions name her or him: "juliet" or "romeo". */
    public String captiveName() {
        return captiveName;
    }

    public AgentPartyQuestDefinition definition() {
        int exit = map(AgentRjpqDefinition.EXIT_MAP);
        return new AgentPartyQuestDefinition(key, eventManagerName, recruitMapId, map(AgentRjpqDefinition.BOOKS_MAP),
                exit, exit, recruitMapId, MIN_LEVEL, MAX_LEVEL, PARTY_SIZE, PARTY_SIZE, EVENT_TIMER_MS);
    }

    public static AgentRjpqSide fromKey(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        for (AgentRjpqSide side : values()) {
            if (side.key.equals(normalized)) {
                return side;
            }
        }
        return null;
    }

    public static AgentRjpqSide fromRecruitMap(int mapId) {
        for (AgentRjpqSide side : values()) {
            if (side.recruitMapId == mapId) {
                return side;
            }
        }
        return null;
    }

    /** The side whose event maps (926100000-926100700 or 926110000-926110700) hold {@code mapId}, else null. */
    public static AgentRjpqSide fromEventMap(int mapId) {
        for (AgentRjpqSide side : values()) {
            if (side.zenumistMap(mapId) >= 0) {
                return side;
            }
        }
        return null;
    }
}
