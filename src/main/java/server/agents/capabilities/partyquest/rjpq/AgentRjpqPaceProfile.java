package server.agents.capabilities.partyquest.rjpq;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.agents.capabilities.partyquest.pace.Pmf;
import server.life.LifeFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * RJPQ as the clear-time estimate sees it, from the party inside to the boss down, for one side (exactly four):
 * <pre>
 * books + tunnel + beakers + keys + labs + handin + office + units + unitsLeader + boss
 * </pre>
 * tunnel and office are Clears of their Map.wz census (no respawn in the tunnel; the office's Mutae as the event
 * spawns them); keys is a Quota of Neo Huroids for the two card keys (a key drops on 30% of kills: a seek time and a
 * combat efficiency per kill); boss is a Clear of
 * the side's normal boss that every member fights, the leader included. books, beakers and units are the companions'
 * fixed times; labs, handin and unitsLeader are the leader's, learned per leader (the Dark Labs stay his until the
 * first live test). A host registers it per side key with {@link #load}.
 */
public final class AgentRjpqPaceProfile implements AgentPqPaceProfile {
    public static final String BOOKS = "books";
    public static final String TUNNEL = "tunnel";
    public static final String BEAKERS = "beakers";
    public static final String KEYS = "keys";
    public static final String LABS = "labs";
    public static final String HANDIN = "handin";
    public static final String OFFICE = "office";
    public static final String UNITS = "units";
    public static final String UNITS_LEADER = "unitsLeader";
    public static final String BOSS = "boss";

    /** MagatiaPQ_Z.js / _A.js yuleteAction: five Iron and five Mithril Mutae at each of three points. */
    public static final int OFFICE_MUTAE_PER_TYPE = 15;
    /** Card keys the two Dark Lab doors take, one each. */
    public static final int KEYS_NEEDED = 2;
    /** Card Key 4001133 drops on 100000 per million kills at the server's x3 drop rate. */
    public static final double KEY_DROP_CHANCE = 0.3d;
    /** The kill count past which the keys' distribution is cut (its tail beyond carries < 1e-9). */
    public static final int KEY_KILLS_CAP = 80;

    private static final double BOOKS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.BOOKS_MS");
    private static final double TUNNEL_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.TUNNEL_EFFICIENCY");
    private static final double BEAKERS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.BEAKERS_MS");
    private static final double KEYS_SEEK_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.KEYS_SEEK_MS");
    private static final double KEYS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.KEYS_EFFICIENCY");
    private static final double LEADER_KEY_CREDIT = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.LEADER_KEY_CREDIT");
    private static final double LABS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.LABS_MS");
    private static final double HANDIN_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.HANDIN_MS");
    private static final double OFFICE_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.OFFICE_EFFICIENCY");
    private static final double UNITS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.UNITS_MS");
    private static final double UNITS_LEADER_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.UNITS_LEADER_MS");
    private static final double BOSS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.BOSS_EFFICIENCY");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.WEAK_HIT_CHANCE");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile.PACE_EPOCH");

    private final AgentRjpqSide side;
    private final AgentPartyQuestDefinition definition;
    private final List<Integer> mobIds;
    private final int bossId;
    private final List<AgentPqPaceSegment> segments;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentRjpqPaceProfile(AgentRjpqSide side, Map<Integer, Integer> tunnel, Map<Integer, Integer> hp,
                                 int bossId) {
        this.side = side;
        this.definition = side.definition();
        this.bossId = bossId;
        this.mobIds = List.of(AgentRjpqDefinition.HOMUN_MOB_ID, AgentRjpqDefinition.CYTI_MOB_ID,
                AgentRjpqDefinition.NEO_HUROID_MOB_ID, AgentRjpqDefinition.IRON_MUTAE_MOB_ID,
                AgentRjpqDefinition.MITHRIL_MUTAE_MOB_ID, bossId);
        Map<Integer, Integer> office = new TreeMap<>();
        office.put(AgentRjpqDefinition.IRON_MUTAE_MOB_ID, OFFICE_MUTAE_PER_TYPE);
        office.put(AgentRjpqDefinition.MITHRIL_MUTAE_MOB_ID, OFFICE_MUTAE_PER_TYPE);
        this.segments = List.of(
                new AgentPqPaceSegment.Fixed(BOOKS, AgentPqPaceSegment.Side.COMPANION),
                new AgentPqPaceSegment.Clear(TUNNEL, tunnel, hp, AgentRjpqPaceProfile::everyoneOnTheLowest, true),
                new AgentPqPaceSegment.Fixed(BEAKERS, AgentPqPaceSegment.Side.COMPANION),
                new AgentPqPaceSegment.Quota(KEYS, AgentRjpqDefinition.NEO_HUROID_MOB_ID,
                        hp.get(AgentRjpqDefinition.NEO_HUROID_MOB_ID), keyKills(), LEADER_KEY_CREDIT),
                new AgentPqPaceSegment.Fixed(LABS, AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Fixed(HANDIN, AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Clear(OFFICE, office, hp, AgentRjpqPaceProfile::everyoneOnTheLowest, true),
                new AgentPqPaceSegment.Fixed(UNITS, AgentPqPaceSegment.Side.COMPANION),
                new AgentPqPaceSegment.Fixed(UNITS_LEADER, AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Clear(BOSS, Map.of(bossId, 1), hp, AgentRjpqPaceProfile::everyoneOnTheLowest,
                        true));
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(BOOKS, BOOKS_MS);
        values.put(TUNNEL, TUNNEL_EFFICIENCY);
        values.put(BEAKERS, BEAKERS_MS);
        values.put(KEYS + ".seek", KEYS_SEEK_MS);
        values.put(KEYS + ".combat", KEYS_EFFICIENCY);
        values.put(LABS, LABS_MS);
        values.put(HANDIN, HANDIN_MS);
        values.put(OFFICE, OFFICE_EFFICIENCY);
        values.put(UNITS, UNITS_MS);
        values.put(UNITS_LEADER, UNITS_LEADER_MS);
        values.put(BOSS, BOSS_EFFICIENCY);
        this.priors = Collections.unmodifiableMap(new TreeMap<>(values));
        this.thresholds = new Thresholds(FRAGILE_TOUCHES, WEAK_HIT_CHANCE, Map.of());
        this.terms = Collections.unmodifiableSet(AgentPqPaceProfile.super.terms());
        this.leaderTerms = Collections.unmodifiableSet(AgentPqPaceProfile.super.leaderTerms());
    }

    /**
     * The kills until the second card key: a negative binomial of {@link #KEYS_NEEDED} successes at
     * {@link #KEY_DROP_CHANCE}, cut at {@link #KEY_KILLS_CAP} and renormalised.
     */
    static Pmf keyKills() {
        double[] mass = new double[KEY_KILLS_CAP - KEYS_NEEDED + 1];
        double total = 0.0d;
        for (int kills = KEYS_NEEDED; kills <= KEY_KILLS_CAP; kills++) {
            // P(the second success on kill n) = (n - 1) p^2 (1 - p)^(n - 2)
            double p = (kills - 1) * KEY_DROP_CHANCE * KEY_DROP_CHANCE * Math.pow(1.0d - KEY_DROP_CHANCE, kills - 2);
            mass[kills - KEYS_NEEDED] = p;
            total += p;
        }
        for (int i = 0; i < mass.length; i++) {
            mass[i] /= total;
        }
        return new Pmf(KEYS_NEEDED, mass);
    }

    /**
     * Builds the profile from Map.wz and Mob.wz, then loads its mobs into LifeFactory, so a member's power read later
     * on a leader's I/O thread finds them in memory. On companion-ops: it parses game data.
     */
    public static AgentRjpqPaceProfile load(AgentRjpqSide side) {
        AgentRjpqPaceProfile profile = load(side, Census.WZ);
        for (int mobId : profile.mobIds()) {
            if (LifeFactory.getMonster(mobId) == null) {
                throw new IllegalStateException("LifeFactory cannot load RJPQ mob " + mobId);
            }
        }
        return profile;
    }

    /** The profile from this census: the tunnel as Map.wz places it, every mob's HP as Mob.wz has it. */
    static AgentRjpqPaceProfile load(AgentRjpqSide side, Census census) {
        Map<Integer, Integer> placed = census.mobCounts(side.map(AgentRjpqDefinition.TUNNEL_MAP));
        Map<Integer, Integer> tunnel = new TreeMap<>();
        for (int mobId : AgentRjpqDefinition.TUNNEL_MOB_IDS) {
            int count = placed.getOrDefault(mobId, 0);
            if (count <= 0) {
                throw new IllegalStateException("Map.wz places no " + mobId + " in the RJPQ tunnel");
            }
            tunnel.put(mobId, count);
        }
        int bossId = side.bossMobIds().stream().min(Integer::compare).orElseThrow();
        List<Integer> hpOf = new ArrayList<>(List.of(AgentRjpqDefinition.HOMUN_MOB_ID, AgentRjpqDefinition.CYTI_MOB_ID,
                AgentRjpqDefinition.NEO_HUROID_MOB_ID, AgentRjpqDefinition.IRON_MUTAE_MOB_ID,
                AgentRjpqDefinition.MITHRIL_MUTAE_MOB_ID, bossId));
        Map<Integer, Integer> hp = new TreeMap<>();
        for (int mobId : hpOf) {
            Integer mobHp = census.mobHp(mobId);
            if (mobHp == null || mobHp <= 0) {
                throw new IllegalStateException("Mob.wz has no HP for " + mobId);
            }
            hp.put(mobId, mobHp);
        }
        return new AgentRjpqPaceProfile(side, tunnel, hp, bossId);
    }

    /** Every member on the lowest live mob id it can hurt: the stages are cleared type by type, the boss alone. */
    static Map<Integer, Integer> everyoneOnTheLowest(Map<Integer, Map<Integer, Double>> damageByMember) {
        Map<Integer, Integer> choice = new TreeMap<>();
        new TreeMap<>(damageByMember).forEach((memberId, damage) -> {
            if (damage != null && !damage.isEmpty()) {
                choice.put(memberId, damage.keySet().stream().min(Integer::compare).orElseThrow());
            }
        });
        return choice;
    }

    @Override
    public String questKey() {
        return side.key();
    }

    @Override
    public long timerMs() {
        return definition.eventTimerMs();
    }

    @Override
    public int minSize() {
        return definition.minimumPartySize();
    }

    @Override
    public int maxSize() {
        return definition.maximumPartySize();
    }

    @Override
    public List<Integer> mobIds() {
        return mobIds;
    }

    @Override
    public int dangerMobId() {
        return bossId;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        if (partySize < minSize() || partySize > maxSize()) {
            throw new IllegalArgumentException("RJPQ takes " + minSize() + "-" + maxSize() + " members, not "
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
