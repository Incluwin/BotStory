package server.agents.capabilities.partyquest.ppq;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
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
 * PPQ as the clear-time estimate sees it (P6i Step 5, plan 10), from the party inside to Wu Yang's thanks, the same
 * for four to six members:
 * <pre>
 * stage1 + medals1 + handin1 + medals2 + handin2 + medals3 + handin3 + deck1 + deck2 + doors + boss + wuyang
 * </pre>
 * stage1, deck1, deck2 and doors are Clears of their Map.wz census (the decks' as PiratePQ.js setup spawns them: ten
 * of each deck pirate; the door map's spawn points, the keys' kills inside its efficiency); each medal wave is a Quota
 * of its pirate's kills until twenty medals drop (a negative binomial at the medals' 30%), with the human leader's
 * kills counted at LEADER_MEDAL_CREDIT since he also picks up and hands in; boss is a Clear of the Lord Pirate
 * (9300119, Mob.wz HP) that every member fights, the leader included. The three hand-ins (his talks to Guon) and Wu
 * Yang's are the leader's own fixed times, learned per leader. Each stage's stock timer budget (PiratePQ.js, 2094002.js)
 * is the segment's slow threshold. The Lord Pirate's touch decides who is fragile. A host registers it by
 * {@link #QUEST_KEY} with {@link #load} (on companion-ops). Its priors are guesses until a live run measures them.
 */
public final class AgentPpqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = "ppq";

    public static final String STAGE_ONE = "stage1";
    public static final String MEDALS_ONE = "medals1";
    public static final String HANDIN_ONE = "handin1";
    public static final String MEDALS_TWO = "medals2";
    public static final String HANDIN_TWO = "handin2";
    public static final String MEDALS_THREE = "medals3";
    public static final String HANDIN_THREE = "handin3";
    public static final String DECK_ONE = "deck1";
    public static final String DECK_TWO = "deck2";
    public static final String DOORS = "doors";
    public static final String BOSS = "boss";
    public static final String WU_YANG = "wuyang";
    /** The calibration term the three hand-ins share: one leader, one talk to Guon each time. */
    public static final String HANDIN_TERM = "handin";

    /** The kill count past which a wave's distribution is cut (its tail beyond carries far under 1e-9). */
    public static final int MEDAL_KILLS_CAP = 240;

    private static final double STAGE_ONE_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.STAGE_ONE_EFFICIENCY");
    private static final double MEDALS_SEEK_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.MEDALS_SEEK_MS");
    private static final double MEDALS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.MEDALS_EFFICIENCY");
    private static final double LEADER_MEDAL_CREDIT = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.LEADER_MEDAL_CREDIT");
    private static final double HANDIN_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.HANDIN_MS");
    private static final double DECK_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.DECK_EFFICIENCY");
    private static final double DOORS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.DOORS_EFFICIENCY");
    private static final double BOSS_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.BOSS_EFFICIENCY");
    private static final double WUYANG_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.WUYANG_MS");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.WEAK_HIT_CHANCE");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile.PACE_EPOCH");

    private final List<Integer> mobIds;
    private final List<AgentPqPaceSegment> segments;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentPpqPaceProfile(Map<Integer, Integer> stageOne, Map<Integer, Integer> doors, Map<Integer, Integer> hp) {
        Map<Integer, Integer> deck = new TreeMap<>();
        deck.put(AgentPpqDefinition.DECK_PIRATE_MOB, AgentPpqDefinition.DECK_PIRATES_PER_TYPE);
        deck.put(AgentPpqDefinition.DECK_GUNNER_MOB, AgentPpqDefinition.DECK_PIRATES_PER_TYPE);
        List<Integer> mobs = new ArrayList<>(stageOne.keySet());
        mobs.addAll(AgentPpqDefinition.MEDAL_MOBS);
        mobs.addAll(deck.keySet());
        mobs.addAll(doors.keySet());
        mobs.add(AgentPpqDefinition.LORD_PIRATE_MOB);
        this.mobIds = List.copyOf(mobs);
        Pmf kills = medalKills();
        this.segments = List.of(
                new AgentPqPaceSegment.Clear(STAGE_ONE, stageOne, hp, AgentPpqPaceProfile::everyoneOnTheLowest, true),
                new AgentPqPaceSegment.Quota(MEDALS_ONE, AgentPpqDefinition.MEDAL_MOBS.get(0),
                        hp.get(AgentPpqDefinition.MEDAL_MOBS.get(0)), kills, LEADER_MEDAL_CREDIT),
                new AgentPqPaceSegment.Fixed(HANDIN_ONE, AgentPqPaceSegment.Side.LEADER, HANDIN_TERM),
                new AgentPqPaceSegment.Quota(MEDALS_TWO, AgentPpqDefinition.MEDAL_MOBS.get(1),
                        hp.get(AgentPpqDefinition.MEDAL_MOBS.get(1)), kills, LEADER_MEDAL_CREDIT),
                new AgentPqPaceSegment.Fixed(HANDIN_TWO, AgentPqPaceSegment.Side.LEADER, HANDIN_TERM),
                new AgentPqPaceSegment.Quota(MEDALS_THREE, AgentPpqDefinition.MEDAL_MOBS.get(2),
                        hp.get(AgentPpqDefinition.MEDAL_MOBS.get(2)), kills, LEADER_MEDAL_CREDIT),
                new AgentPqPaceSegment.Fixed(HANDIN_THREE, AgentPqPaceSegment.Side.LEADER, HANDIN_TERM),
                new AgentPqPaceSegment.Clear(DECK_ONE, deck, hp, AgentPpqPaceProfile::everyoneOnTheLowest, true),
                new AgentPqPaceSegment.Clear(DECK_TWO, deck, hp, AgentPpqPaceProfile::everyoneOnTheLowest, true),
                new AgentPqPaceSegment.Clear(DOORS, doors, hp, AgentPpqPaceProfile::everyoneOnTheLowest, true),
                new AgentPqPaceSegment.Clear(BOSS, Map.of(AgentPpqDefinition.LORD_PIRATE_MOB, 1), hp,
                        AgentPpqPaceProfile::everyoneOnTheLowest, true),
                new AgentPqPaceSegment.Fixed(WU_YANG, AgentPqPaceSegment.Side.LEADER));
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(STAGE_ONE, STAGE_ONE_EFFICIENCY);
        for (String wave : List.of(MEDALS_ONE, MEDALS_TWO, MEDALS_THREE)) {
            values.put(wave + ".seek", MEDALS_SEEK_MS);
            values.put(wave + ".combat", MEDALS_EFFICIENCY);
        }
        values.put(HANDIN_TERM, HANDIN_MS);
        values.put(DECK_ONE, DECK_EFFICIENCY);
        values.put(DECK_TWO, DECK_EFFICIENCY);
        values.put(DOORS, DOORS_EFFICIENCY);
        values.put(BOSS, BOSS_EFFICIENCY);
        values.put(WU_YANG, WUYANG_MS);
        this.priors = Collections.unmodifiableMap(new TreeMap<>(values));
        Map<String, Double> slow = new LinkedHashMap<>();
        slow.put(STAGE_ONE, (double) AgentPpqDefinition.STAGE_ONE_BUDGET_MS);
        slow.put(MEDALS_ONE, (double) AgentPpqDefinition.MEDAL_WAVE_BUDGET_MS);
        slow.put(MEDALS_TWO, (double) AgentPpqDefinition.MEDAL_WAVE_BUDGET_MS);
        slow.put(MEDALS_THREE, (double) AgentPpqDefinition.MEDAL_WAVE_BUDGET_MS);
        slow.put(DECK_ONE, (double) AgentPpqDefinition.DECK_BUDGET_MS);
        slow.put(DECK_TWO, (double) AgentPpqDefinition.DECK_BUDGET_MS);
        slow.put(DOORS, (double) AgentPpqDefinition.DOORS_BUDGET_MS);
        slow.put(BOSS, (double) AgentPpqDefinition.BOSS_BUDGET_MS);
        this.thresholds = new Thresholds(FRAGILE_TOUCHES, WEAK_HIT_CHANCE, slow);
        // Walked once here: the estimate asks for them on every lineup.
        this.terms = Collections.unmodifiableSet(AgentPqPaceProfile.super.terms());
        this.leaderTerms = Collections.unmodifiableSet(AgentPqPaceProfile.super.leaderTerms());
    }

    /**
     * The kills until a wave's twentieth medal: a negative binomial of {@link AgentPpqDefinition#MEDALS_PER_WAVE}
     * successes at {@link AgentPpqDefinition#MEDAL_DROP_CHANCE}, cut at {@link #MEDAL_KILLS_CAP} and renormalised.
     */
    static Pmf medalKills() {
        int needed = AgentPpqDefinition.MEDALS_PER_WAVE;
        double p = AgentPpqDefinition.MEDAL_DROP_CHANCE;
        double[] mass = new double[MEDAL_KILLS_CAP - needed + 1];
        double total = 0.0d;
        // P(the n-th success on kill k) = C(k-1, n-1) p^n (1-p)^(k-n), built up by the ratio between neighbours
        double term = Math.pow(p, needed);
        for (int kills = needed; kills <= MEDAL_KILLS_CAP; kills++) {
            if (kills > needed) {
                term *= (double) (kills - 1) / (kills - needed) * (1.0d - p);
            }
            mass[kills - needed] = term;
            total += term;
        }
        for (int i = 0; i < mass.length; i++) {
            mass[i] /= total;
        }
        return new Pmf(needed, mass);
    }

    /**
     * Builds the profile from Map.wz and Mob.wz, then loads its mobs into LifeFactory, so a member's power read later
     * on a leader's I/O thread finds them in memory. On companion-ops: it parses game data.
     */
    public static AgentPpqPaceProfile load() {
        AgentPpqPaceProfile profile = load(Census.WZ);
        for (int mobId : profile.mobIds()) {
            if (LifeFactory.getMonster(mobId) == null) {
                throw new IllegalStateException("LifeFactory cannot load PPQ mob " + mobId);
            }
        }
        return profile;
    }

    /** The profile from this census: stage one and the door map as Map.wz places them, every mob's HP from Mob.wz. */
    static AgentPpqPaceProfile load(Census census) {
        Map<Integer, Integer> stageOne = census(census, AgentPpqDefinition.ENTRY_MAP);
        Map<Integer, Integer> doors = census(census, AgentPpqDefinition.DOOR_MAP);
        List<Integer> hpOf = new ArrayList<>(stageOne.keySet());
        hpOf.addAll(AgentPpqDefinition.MEDAL_MOBS);
        hpOf.add(AgentPpqDefinition.DECK_PIRATE_MOB);
        hpOf.add(AgentPpqDefinition.DECK_GUNNER_MOB);
        hpOf.addAll(doors.keySet());
        hpOf.add(AgentPpqDefinition.LORD_PIRATE_MOB);
        Map<Integer, Integer> hp = new TreeMap<>();
        for (int mobId : hpOf) {
            Integer mobHp = census.mobHp(mobId);
            if (mobHp == null || mobHp <= 0) throw new IllegalStateException("Mob.wz has no HP for " + mobId);
            hp.put(mobId, mobHp);
        }
        return new AgentPpqPaceProfile(stageOne, doors, hp);
    }

    /** A map's Map.wz census of the run's fighting mobs; it must hold some. */
    private static Map<Integer, Integer> census(Census census, int mapId) {
        Map<Integer, Integer> counts = new TreeMap<>();
        census.mobCounts(mapId).forEach((mobId, count) -> {
            if (AgentPpqDefinition.COMBAT_MOBS.contains(mobId) && count != null && count > 0) counts.put(mobId, count);
        });
        if (counts.isEmpty()) throw new IllegalStateException("Map.wz places no PPQ mob on " + mapId);
        return counts;
    }

    /** Every member on the lowest live mob id it can hurt: the estimate knows no positions, so types fall in turn. */
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
        return QUEST_KEY;
    }

    /** No timer: PiratePQ.js restarts its own per stage; the stock budgets are the segments' slow thresholds. */
    @Override
    public long timerMs() {
        return 0L;
    }

    @Override
    public int minSize() {
        return AgentPpqDefinition.COMPANION_MIN_PARTY_SIZE;
    }

    @Override
    public int maxSize() {
        return AgentPpqDefinition.COMPANION_MAX_PARTY_SIZE;
    }

    @Override
    public List<Integer> mobIds() {
        return mobIds;
    }

    @Override
    public int dangerMobId() {
        return AgentPpqDefinition.LORD_PIRATE_MOB;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        if (partySize < minSize() || partySize > maxSize()) {
            throw new IllegalArgumentException("PPQ takes " + minSize() + "-" + maxSize() + " members, not "
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
                if (map == null) throw new IllegalStateException("Map.wz has no map " + mapId);
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
