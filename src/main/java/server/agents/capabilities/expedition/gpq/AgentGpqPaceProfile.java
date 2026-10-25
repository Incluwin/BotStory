package server.agents.capabilities.expedition.gpq;

import provider.Data;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.agents.capabilities.expedition.boss.AgentBossRules;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.life.LifeFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Sharenian as the clear-time records see it (GPQ plan G5), from the guild called in to the Rubian turned in:
 * <pre>
 * strategy + rocks + statues + spears + vassals + waterway + corridor + ergoth
 * </pre>
 * strategy is GuildQuest.js's three minutes before the way in opens; rocks the Valley to the Gatekeeper; statues the
 * Gatekeeper's three patterns (the leader's talks among them); spears the Hall of the Knight to its gate; vassals the
 * fountain's offerings; waterway the four rooms and the grave to the ghost's word; corridor the walk and the rock given
 * to the King's Corridor gate; all fixed times of the companions, priors until runs teach them. ergoth is the throne:
 * the Rubian's seal and Ergoth (Mob.wz 9300028) fought by everyone, his HP divided by the boss rules'
 * companionDamagePercent as the fight is fought. No Treasure Warehouse: its 30 s are the event's, after the clear.
 * The guild quest runs on one 90-minute event timer from the end of the joining ({@link #timerMs}). A host registers
 * it under {@link #QUEST_KEY} with {@link #load}.
 */
public final class AgentGpqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = AgentGpqRun.KEY;

    public static final String STRATEGY = "strategy";
    public static final String ROCKS = "rocks";
    public static final String STATUES = "statues";
    public static final String SPEARS = "spears";
    public static final String VASSALS = "vassals";
    public static final String WATERWAY = "waterway";
    public static final String CORRIDOR = "corridor";
    public static final String ERGOTH = "ergoth";

    /** GuildQuest.js: minPlayers 6; maxPlayers 30 is never enforced, so the records take up to 30. */
    static final int MIN_SIZE = 6;
    static final int MAX_SIZE = 30;
    /** GuildQuest.js eventTime: 90 minutes from the guild called in. */
    static final long TIMER_MS = 90 * 60_000L;
    static final List<Integer> MOB_IDS = List.of(AgentGpqThronePlays.ERGOTH);

    private static final double STRATEGY_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.STRATEGY_MS");
    private static final double ROCKS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.ROCKS_MS");
    private static final double STATUES_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.STATUES_MS");
    private static final double SPEARS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.SPEARS_MS");
    private static final double VASSALS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.VASSALS_MS");
    private static final double WATERWAY_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.WATERWAY_MS");
    private static final double CORRIDOR_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.CORRIDOR_MS");
    private static final double ERGOTH_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.ERGOTH_EFFICIENCY");
    private static final double ERGOTH_SLOW_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.ERGOTH_SLOW_MS");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.WEAK_HIT_CHANCE");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqPaceProfile.PACE_EPOCH");

    private final List<AgentPqPaceSegment> segments;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentGpqPaceProfile(int ergothHp) {
        AgentPqPaceSegment.Side companion = AgentPqPaceSegment.Side.COMPANION;
        this.segments = List.of(
                new AgentPqPaceSegment.Fixed(STRATEGY, companion),
                new AgentPqPaceSegment.Fixed(ROCKS, companion),
                new AgentPqPaceSegment.Fixed(STATUES, companion),
                new AgentPqPaceSegment.Fixed(SPEARS, companion),
                new AgentPqPaceSegment.Fixed(VASSALS, companion),
                new AgentPqPaceSegment.Fixed(WATERWAY, companion),
                new AgentPqPaceSegment.Fixed(CORRIDOR, companion),
                new AgentPqPaceSegment.Clear(ERGOTH, Map.of(AgentGpqThronePlays.ERGOTH, 1),
                        Map.of(AgentGpqThronePlays.ERGOTH, ergothHp), AgentGpqPaceProfile::onErgoth, true));
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(STRATEGY, STRATEGY_MS);
        values.put(ROCKS, ROCKS_MS);
        values.put(STATUES, STATUES_MS);
        values.put(SPEARS, SPEARS_MS);
        values.put(VASSALS, VASSALS_MS);
        values.put(WATERWAY, WATERWAY_MS);
        values.put(CORRIDOR, CORRIDOR_MS);
        values.put(ERGOTH, ERGOTH_EFFICIENCY);
        this.priors = Collections.unmodifiableMap(new TreeMap<>(values));
        this.thresholds = new Thresholds(FRAGILE_TOUCHES, WEAK_HIT_CHANCE, Map.of(ERGOTH, ERGOTH_SLOW_MS));
        this.terms = Collections.unmodifiableSet(AgentPqPaceProfile.super.terms());
        this.leaderTerms = Collections.unmodifiableSet(AgentPqPaceProfile.super.leaderTerms());
    }

    /** Every member that can hurt him is on Ergoth. */
    static Map<Integer, Integer> onErgoth(Map<Integer, Map<Integer, Double>> damageByMember) {
        Map<Integer, Integer> choice = new TreeMap<>();
        damageByMember.forEach((memberId, damage) -> {
            Double onHim = damage == null ? null : damage.get(AgentGpqThronePlays.ERGOTH);
            if (onHim != null && onHim > 0.0d) {
                choice.put(memberId, AgentGpqThronePlays.ERGOTH);
            }
        });
        return choice;
    }

    /**
     * Builds the profile from Mob.wz, then loads Ergoth into LifeFactory, so a member's power read later on a leader's
     * I/O thread finds him in memory. On companion-ops: it parses game data.
     */
    public static AgentGpqPaceProfile load() {
        Data mob = DataProviderFactory.getDataProvider(WZFiles.MOB)
                .getData(String.format("%07d", AgentGpqThronePlays.ERGOTH) + ".img");
        Data info = mob == null ? null : mob.getChildByPath("info");
        Data hp = info == null ? null : info.getChildByPath("maxHP");
        if (hp == null) {
            throw new IllegalStateException("Mob.wz has no HP for Ergoth " + AgentGpqThronePlays.ERGOTH);
        }
        AgentGpqPaceProfile profile = of(Integer.parseInt(String.valueOf(hp.getData()).trim()));
        if (LifeFactory.getMonster(AgentGpqThronePlays.ERGOTH) == null) {
            throw new IllegalStateException("LifeFactory cannot load Ergoth " + AgentGpqThronePlays.ERGOTH);
        }
        return profile;
    }

    /** The profile for Ergoth's Mob.wz HP, read at the boss rules' companionDamagePercent. */
    static AgentGpqPaceProfile of(int ergothMobHp) {
        if (ergothMobHp <= 0) {
            throw new IllegalArgumentException("Ergoth needs positive HP, not " + ergothMobHp);
        }
        AgentBossRules.Boss rules = AgentBossRules.of(QUEST_KEY);
        int hp = rules == null ? ergothMobHp
                : (int) (ergothMobHp * 100L / rules.damagePercentOn(AgentGpqThronePlays.ERGOTH));
        return new AgentGpqPaceProfile(hp);
    }

    @Override
    public String questKey() {
        return QUEST_KEY;
    }

    @Override
    public long timerMs() {
        return TIMER_MS;
    }

    @Override
    public int minSize() {
        return MIN_SIZE;
    }

    @Override
    public int maxSize() {
        return MAX_SIZE;
    }

    @Override
    public List<Integer> mobIds() {
        return MOB_IDS;
    }

    @Override
    public int dangerMobId() {
        return AgentGpqThronePlays.ERGOTH;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        if (partySize < MIN_SIZE || partySize > MAX_SIZE) {
            throw new IllegalArgumentException("Sharenian takes " + MIN_SIZE + "-" + MAX_SIZE + " members, not "
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
}
