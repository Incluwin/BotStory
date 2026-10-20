package server.agents.capabilities.expedition.cwkpq;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.expeditions.ExpeditionType;
import server.life.LifeFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CWKPQ as the clear-time estimate sees it (CWKPQ plan C8), from Start at Jack to the Grandmasters down:
 * <pre>
 * hallway + storage + agility + wit + unity(warriorRoom | bowmanRoom | mageRoom | thiefRoom | pirateRoom)
 *         + relics + statue + guards + masters
 * </pre>
 * The four puzzle stages, the thief and pirate rooms (reactor work, not damage) and the relics are the companions'
 * fixed times; statue is the event leader's three talks to the Battle Statue (9201115.js), learned per leader. The
 * warrior, bowman and mage rooms are Clears that only their class fights (glpqPortal00-04 let one job niche in): the
 * warrior room and the mage room as Map.wz places them, the bowman room as CWKPQ.js setup spawns its five Master
 * Guardians. guards is the Council Hall's 10 Master and 20 Crimson Guardians and masters the four Grandmasters with
 * Red Nirg's two revives (9201115.js; no Mikhal in this data), both fought by everyone. The run restarts its clock at
 * every stage, so the profile has no single timer ({@link #timerMs} 0); the slow limits stand in for the per-stage
 * ones. A host registers it under {@link #QUEST_KEY} with {@link #load}.
 *
 * <p>Not modelled: the damage policy treats an element a mob is immune to as normal (Master Guardians are immune to
 * all five, Margana to holy), so a party of elemental attackers reads faster than it is.</p>
 */
public final class AgentCwkpqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = "cwkpq";

    public static final String HALLWAY = "hallway";
    public static final String STORAGE = "storage";
    public static final String AGILITY = "agility";
    public static final String WIT = "wit";
    public static final String UNITY = "unity";
    public static final String WARRIOR_ROOM = "warriorRoom";
    public static final String BOWMAN_ROOM = "bowmanRoom";
    public static final String MAGE_ROOM = "mageRoom";
    public static final String THIEF_ROOM = "thiefRoom";
    public static final String PIRATE_ROOM = "pirateRoom";
    public static final String RELICS = "relics";
    public static final String STATUE = "statue";
    public static final String GUARDS = "guards";
    public static final String MASTERS = "masters";

    public static final int CRIMSON_GUARDIAN_MOB_ID = 9_400_582;
    public static final int MASTER_GUARDIAN_MOB_ID = 9_400_594;
    public static final int FIREBRAND_MOB_ID = 9_400_578;
    public static final int ELDERWRAITH_MOB_ID = 9_400_580;
    public static final int STORMBREAKER_MOB_ID = 9_400_581;
    public static final int MARGANA_MOB_ID = 9_400_590;
    public static final int RED_NIRG_MOB_ID = 9_400_591;
    public static final int RELLIK_MOB_ID = 9_400_592;
    public static final int HSALF_MOB_ID = 9_400_593;
    /** Red Nirg's revives (Mob.wz 9400591 revive). */
    public static final int SCARLET_PHOENIX_MOB_ID = 9_400_596;
    public static final int AZURE_OCELOT_MOB_ID = 9_400_597;

    public static final int WARRIOR_ROOM_MAP_ID = 610_030_510;
    public static final int MAGE_ROOM_MAP_ID = 610_030_521;
    /** CWKPQ.js setup: five Master Guardians in the bowman room 610030540. */
    public static final int BOWMAN_ROOM_GUARDIANS = 5;
    /** 9201115.js, the statue's first talk: ten Master and twenty Crimson Guardians in the hall. */
    public static final int HALL_MASTER_GUARDIANS = 10;
    public static final int HALL_CRIMSON_GUARDIANS = 20;

    static final List<Integer> MOB_IDS = List.of(CRIMSON_GUARDIAN_MOB_ID, MASTER_GUARDIAN_MOB_ID, FIREBRAND_MOB_ID,
            ELDERWRAITH_MOB_ID, STORMBREAKER_MOB_ID, MARGANA_MOB_ID, RED_NIRG_MOB_ID, RELLIK_MOB_ID, HSALF_MOB_ID,
            SCARLET_PHOENIX_MOB_ID, AZURE_OCELOT_MOB_ID);
    static final List<Integer> WARRIOR_ROOM_MOB_IDS = List.of(CRIMSON_GUARDIAN_MOB_ID);
    static final List<Integer> MAGE_ROOM_MOB_IDS = List.of(FIREBRAND_MOB_ID, ELDERWRAITH_MOB_ID, STORMBREAKER_MOB_ID);

    private static final double HALLWAY_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.HALLWAY_MS");
    private static final double STORAGE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.STORAGE_MS");
    private static final double AGILITY_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.AGILITY_MS");
    private static final double WIT_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.WIT_MS");
    private static final double ROOM_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.ROOM_EFFICIENCY");
    private static final double BOWMAN_ROOM_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.BOWMAN_ROOM_EFFICIENCY");
    private static final double THIEF_ROOM_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.THIEF_ROOM_MS");
    private static final double PIRATE_ROOM_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.PIRATE_ROOM_MS");
    private static final double RELICS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.RELICS_MS");
    private static final double STATUE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.STATUE_MS");
    private static final double GUARDS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.GUARDS_EFFICIENCY");
    private static final double MASTERS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.MASTERS_EFFICIENCY");
    private static final double ROOM_SLOW_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.ROOM_SLOW_MS");
    private static final double MASTERS_SLOW_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.MASTERS_SLOW_MS");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.WEAK_HIT_CHANCE");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqPaceProfile.PACE_EPOCH");

    private final List<AgentPqPaceSegment> segments;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentCwkpqPaceProfile(Map<Integer, Integer> warriorRoom, Map<Integer, Integer> mageRoom,
                                  Map<Integer, Integer> hp) {
        Map<Integer, Integer> masters = new TreeMap<>();
        for (int mobId : List.of(MARGANA_MOB_ID, RED_NIRG_MOB_ID, RELLIK_MOB_ID, HSALF_MOB_ID, SCARLET_PHOENIX_MOB_ID,
                AZURE_OCELOT_MOB_ID)) {
            masters.put(mobId, 1);
        }
        AgentPqPaceSegment.Side companion = AgentPqPaceSegment.Side.COMPANION;
        this.segments = List.of(
                new AgentPqPaceSegment.Fixed(HALLWAY, companion),
                new AgentPqPaceSegment.Fixed(STORAGE, companion),
                new AgentPqPaceSegment.Fixed(AGILITY, companion),
                new AgentPqPaceSegment.Fixed(WIT, companion),
                new AgentPqPaceSegment.Parallel(UNITY, List.of(
                        List.of(room(WARRIOR_ROOM, warriorRoom, hp, AgentCwkpqSigils.Archetype.WARRIOR)),
                        List.of(room(BOWMAN_ROOM, Map.of(MASTER_GUARDIAN_MOB_ID, BOWMAN_ROOM_GUARDIANS), hp,
                                AgentCwkpqSigils.Archetype.BOWMAN)),
                        List.of(room(MAGE_ROOM, mageRoom, hp, AgentCwkpqSigils.Archetype.MAGICIAN)),
                        List.of(new AgentPqPaceSegment.Fixed(THIEF_ROOM, companion)),
                        List.of(new AgentPqPaceSegment.Fixed(PIRATE_ROOM, companion)))),
                new AgentPqPaceSegment.Fixed(RELICS, companion),
                new AgentPqPaceSegment.Fixed(STATUE, AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Clear(GUARDS, Map.of(MASTER_GUARDIAN_MOB_ID, HALL_MASTER_GUARDIANS,
                        CRIMSON_GUARDIAN_MOB_ID, HALL_CRIMSON_GUARDIANS), hp,
                        AgentCwkpqPaceProfile::lowestItCanHurt, true),
                new AgentPqPaceSegment.Clear(MASTERS, masters, hp, AgentCwkpqPaceProfile::lowestItCanHurt, true));
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(HALLWAY, HALLWAY_MS);
        values.put(STORAGE, STORAGE_MS);
        values.put(AGILITY, AGILITY_MS);
        values.put(WIT, WIT_MS);
        values.put(WARRIOR_ROOM, ROOM_EFFICIENCY);
        values.put(BOWMAN_ROOM, BOWMAN_ROOM_EFFICIENCY);
        values.put(MAGE_ROOM, ROOM_EFFICIENCY);
        values.put(THIEF_ROOM, THIEF_ROOM_MS);
        values.put(PIRATE_ROOM, PIRATE_ROOM_MS);
        values.put(RELICS, RELICS_MS);
        values.put(STATUE, STATUE_MS);
        values.put(GUARDS, GUARDS_EFFICIENCY);
        values.put(MASTERS, MASTERS_EFFICIENCY);
        this.priors = Collections.unmodifiableMap(new TreeMap<>(values));
        this.thresholds = new Thresholds(FRAGILE_TOUCHES, WEAK_HIT_CHANCE, Map.of(WARRIOR_ROOM, ROOM_SLOW_MS,
                BOWMAN_ROOM, ROOM_SLOW_MS, MAGE_ROOM, ROOM_SLOW_MS, MASTERS, MASTERS_SLOW_MS));
        this.terms = Collections.unmodifiableSet(AgentPqPaceProfile.super.terms());
        this.leaderTerms = Collections.unmodifiableSet(AgentPqPaceProfile.super.leaderTerms());
    }

    /** A class room: only members of {@code archetype} go in (the human leader too, when he is one). */
    private static AgentPqPaceSegment.Clear room(String key, Map<Integer, Integer> counts, Map<Integer, Integer> hp,
                                                 AgentCwkpqSigils.Archetype archetype) {
        return new AgentPqPaceSegment.Clear(key, counts, hp, AgentCwkpqPaceProfile::lowestItCanHurt, true,
                member -> inRoom(member, archetype));
    }

    /** True when the member's job niche is the room's: the class portal lets it in. */
    static boolean inRoom(AgentPqMemberPower member, AgentCwkpqSigils.Archetype archetype) {
        return AgentCwkpqSigils.archetypeOf(member.jobId()) == archetype;
    }

    /**
     * Builds the profile from Map.wz and Mob.wz, then loads its mobs into LifeFactory, so a member's power read later
     * on a leader's I/O thread finds them in memory. On companion-ops: it parses game data.
     */
    public static AgentCwkpqPaceProfile load() {
        AgentCwkpqPaceProfile profile = load(Census.WZ);
        for (int mobId : MOB_IDS) {
            if (LifeFactory.getMonster(mobId) == null) {
                throw new IllegalStateException("LifeFactory cannot load CWKPQ mob " + mobId);
            }
        }
        return profile;
    }

    /** The profile from this census: the warrior and mage rooms as Map.wz places them, HP as Mob.wz has it. */
    static AgentCwkpqPaceProfile load(Census census) {
        Map<Integer, Integer> hp = new TreeMap<>();
        for (int mobId : MOB_IDS) {
            Integer mobHp = census.mobHp(mobId);
            if (mobHp == null || mobHp <= 0) {
                throw new IllegalStateException("Mob.wz has no HP for " + mobId);
            }
            hp.put(mobId, mobHp);
        }
        return new AgentCwkpqPaceProfile(placed(census, WARRIOR_ROOM_MAP_ID, WARRIOR_ROOM_MOB_IDS),
                placed(census, MAGE_ROOM_MAP_ID, MAGE_ROOM_MOB_IDS), hp);
    }

    private static Map<Integer, Integer> placed(Census census, int mapId, List<Integer> mobIds) {
        Map<Integer, Integer> life = census.mobCounts(mapId);
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int mobId : mobIds) {
            int count = life.getOrDefault(mobId, 0);
            if (count <= 0) {
                throw new IllegalStateException("Map.wz places no " + mobId + " in " + mapId);
            }
            counts.put(mobId, count);
        }
        return counts;
    }

    /**
     * Every member on the lowest live mob id it can hurt (a mage's only target may be a Grandmaster its element is not
     * resisted by): the rooms and the hall are cleared type by type.
     */
    static Map<Integer, Integer> lowestItCanHurt(Map<Integer, Map<Integer, Double>> damageByMember) {
        Map<Integer, Integer> choice = new TreeMap<>();
        new TreeMap<>(damageByMember).forEach((memberId, damage) -> {
            if (damage != null) {
                damage.entrySet().stream().filter(entry -> entry.getValue() != null && entry.getValue() > 0.0d)
                        .map(Map.Entry::getKey).min(Integer::compare)
                        .ifPresent(mobId -> choice.put(memberId, mobId));
            }
        });
        return choice;
    }

    @Override
    public String questKey() {
        return QUEST_KEY;
    }

    @Override
    public long timerMs() {
        return 0L;
    }

    @Override
    public int minSize() {
        return ExpeditionType.CWKPQ.getMinSize();
    }

    @Override
    public int maxSize() {
        return ExpeditionType.CWKPQ.getMaxSize();
    }

    @Override
    public List<Integer> mobIds() {
        return MOB_IDS;
    }

    @Override
    public int dangerMobId() {
        return CRIMSON_GUARDIAN_MOB_ID;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        if (partySize < minSize() || partySize > maxSize()) {
            throw new IllegalArgumentException("CWKPQ takes " + minSize() + "-" + maxSize() + " members, not "
                    + partySize);
        }
        return segments;
    }

    @Override
    public Map<String, Double> priors() {
        return priors;
    }

    @Override
    public int epoch() {
        return PACE_EPOCH;
    }

    @Override
    public Thresholds thresholds() {
        return thresholds;
    }

    @Override
    public Set<String> terms() {
        return terms;
    }

    @Override
    public Set<String> leaderTerms() {
        return leaderTerms;
    }

    /** Where the profile reads its content: Map.wz life and Mob.wz HP, or a test's stub. */
    interface Census {
        /** Mob id to the number of its spawn points in the map's WZ life. */
        Map<Integer, Integer> mobCounts(int mapId);

        /** The mob's max HP; null when Mob.wz has none. */
        Integer mobHp(int mobId);

        Census WZ = new Census() {
            @Override
            public Map<Integer, Integer> mobCounts(int mapId) {
                Data map = DataProviderFactory.getDataProvider(WZFiles.MAP).getData(
                        "Map/Map" + mapId / 100_000_000 + "/" + String.format("%09d", mapId) + ".img");
                if (map == null) {
                    throw new IllegalStateException("Map.wz has no map " + mapId);
                }
                Map<Integer, Integer> counts = new TreeMap<>();
                Data life = map.getChildByPath("life");
                if (life != null) {
                    for (Data entry : life) {
                        if ("m".equals(DataTool.getString(entry.getChildByPath("type"), ""))) {
                            counts.merge(Integer.parseInt(DataTool.getString(entry.getChildByPath("id"), "").trim()),
                                    1, Integer::sum);
                        }
                    }
                }
                return counts;
            }

            @Override
            public Integer mobHp(int mobId) {
                Data mob = DataProviderFactory.getDataProvider(WZFiles.MOB)
                        .getData(String.format("%07d", mobId) + ".img");
                Data info = mob == null ? null : mob.getChildByPath("info");
                Data hp = info == null ? null : info.getChildByPath("maxHP");
                return hp == null ? null
                        : (int) Math.min(Integer.MAX_VALUE, Long.parseLong(String.valueOf(hp.getData())));
            }
        };
    }
}
