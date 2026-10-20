package server.agents.capabilities.partyquest.lmpq;

import provider.Data;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.agents.capabilities.partyquest.pace.Pmf;
import server.life.LifeFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * LMPQ as the clear-time estimate sees it (P6h Step 4), from Rolly's Yes to the companions' rewards, for three to six
 * members:
 * <pre>
 * maze(farm | walk) + handin + pietri + claim
 * </pre>
 * farm and walk run at the same time: the companions farm the rooms they land in until the party's coupons make
 * Pietri's thirty to thirty-six (a Quota of coupons on the box mobs, so it scales with the seats; the human leader
 * counts FARM_LEADER_CREDIT of a member, since he walks rather than farms), while the leader walks to Room 16 by
 * their directions (his own time, about 3.2 portal hops from a random start room). handin is the companions' walk to
 * Room 16 and their coupons at his feet (bounded by where he waits); pietri is his talk to Pietri; claim is the
 * companions' rewards from Rolly. These are the report's spans (AgentLmpqPaceReport), one per leaf segment.
 * Tauromacis's touch decides who is fragile. Every prior is a guess until live runs refit it (Step 0 never ran). A
 * host registers it by {@link #QUEST_KEY} with {@link #load} (on companion-ops).
 */
public final class AgentLmpqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = "lmpq";

    public static final String MAZE = "maze";
    public static final String FARM = "farm";
    public static final String WALK = "walk";
    public static final String HANDIN = "handin";
    public static final String PIETRI = "pietri";
    public static final String CLAIM = "claim";

    /** The box mob a coupon is priced on (Mob.wz 9400211: 4800 HP), then the other dps mobs, then Tauromacis. */
    static final List<Integer> MOB_IDS = List.of(9_400_211, 9_400_213, 9_400_217, AgentLmpqDefinition.TAUROMACIS);

    private static final double FARM_SEEK_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile.FARM_SEEK_MS");
    private static final double FARM_COMBAT_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile.FARM_COMBAT_EFFICIENCY");
    private static final double FARM_LEADER_CREDIT = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile.FARM_LEADER_CREDIT");
    private static final double WALK_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile.WALK_MS");
    private static final double HANDIN_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile.HANDIN_MS");
    private static final double PIETRI_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile.PIETRI_MS");
    private static final double CLAIM_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile.CLAIM_MS");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile.WEAK_HIT_CHANCE");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile.PACE_EPOCH");

    private final AgentPartyQuestDefinition definition;
    private final List<AgentPqPaceSegment> segments;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentLmpqPaceProfile(AgentPartyQuestDefinition definition, Map<Integer, Integer> hp) {
        this.definition = definition;
        int couponMob = MOB_IDS.getFirst();
        this.segments = List.of(
                new AgentPqPaceSegment.Parallel(MAZE, List.of(
                        List.of(new AgentPqPaceSegment.Quota(FARM, couponMob, hp.get(couponMob),
                                Pmf.uniform(AgentLmpqDefinition.REQUIRED_COUPONS,
                                        AgentLmpqDefinition.SAFE_COUPON_TARGET), FARM_LEADER_CREDIT)),
                        List.of(new AgentPqPaceSegment.Fixed(WALK, AgentPqPaceSegment.Side.LEADER)))),
                // The coupons end at his feet: where he waits shapes the hand-in.
                new AgentPqPaceSegment.Fixed(HANDIN, AgentPqPaceSegment.Side.COMPANION, HANDIN, true),
                new AgentPqPaceSegment.Fixed(PIETRI, AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Fixed(CLAIM, AgentPqPaceSegment.Side.COMPANION));
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(FARM + ".seek", FARM_SEEK_MS);
        values.put(FARM + ".combat", FARM_COMBAT_EFFICIENCY);
        values.put(WALK, WALK_MS);
        values.put(HANDIN, HANDIN_MS);
        values.put(PIETRI, PIETRI_MS);
        values.put(CLAIM, CLAIM_MS);
        this.priors = Collections.unmodifiableMap(new TreeMap<>(values));
        this.thresholds = new Thresholds(FRAGILE_TOUCHES, WEAK_HIT_CHANCE, Map.of());
        // Walked once here: the estimate asks for them on every lineup.
        this.terms = Collections.unmodifiableSet(AgentPqPaceProfile.super.terms());
        this.leaderTerms = Collections.unmodifiableSet(AgentPqPaceProfile.super.leaderTerms());
    }

    /**
     * Builds the profile from Mob.wz, then loads its mobs into LifeFactory, so a member's power read later on a
     * leader's I/O thread finds them in memory. On companion-ops: it parses game data.
     */
    public static AgentLmpqPaceProfile load() {
        AgentLmpqPaceProfile profile = load(Census.WZ);
        for (int mobId : profile.mobIds()) {
            if (LifeFactory.getMonster(mobId) == null) {
                throw new IllegalStateException("LifeFactory cannot load LMPQ mob " + mobId);
            }
        }
        return profile;
    }

    /** The profile from this census: every mob's HP as Mob.wz has it. */
    static AgentLmpqPaceProfile load(Census census) {
        Map<Integer, Integer> hp = new TreeMap<>();
        for (int mobId : MOB_IDS) {
            int mobHp = census.hp(mobId);
            if (mobHp <= 0) throw new IllegalStateException("Mob.wz has no HP for " + mobId);
            hp.put(mobId, mobHp);
        }
        return new AgentLmpqPaceProfile(AgentPartyQuestCatalog.require(QUEST_KEY), hp);
    }

    @Override
    public String questKey() {
        return QUEST_KEY;
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
        return MOB_IDS;
    }

    @Override
    public int dangerMobId() {
        return AgentLmpqDefinition.TAUROMACIS;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        if (partySize < minSize() || partySize > maxSize()) {
            throw new IllegalArgumentException("LMPQ takes " + minSize() + "-" + maxSize() + " members, not "
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

    /** Where the profile reads its mobs' HP: Mob.wz, or a test's stub. */
    interface Census {
        /** The mob's maxHP in Mob.wz; 0 when Mob.wz has no such mob or no maxHP for it. */
        int hp(int mobId);

        Census WZ = mobId -> {
            Data mob = DataProviderFactory.getDataProvider(WZFiles.MOB).getData(String.format("%07d", mobId) + ".img");
            Data hp = mob == null ? null : mob.getChildByPath("info/maxHP");
            return hp == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Long.parseLong(String.valueOf(hp.getData())));
        };
    }
}
