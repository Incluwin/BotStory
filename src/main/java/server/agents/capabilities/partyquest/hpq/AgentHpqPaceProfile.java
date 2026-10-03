package server.agents.capabilities.partyquest.hpq;

import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.life.LifeFactory;
import server.life.Monster;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * HPQ as the clear-time estimate sees it, from Tory's Yes to Growlie's clear, the same for every party size:
 * <pre>
 * yes + seeds + bunny + handin
 * </pre>
 * yes, seeds and bunny are the companions' times (the party inside Primrose Hill, the six moonflowers up, the Moon
 * Bunny's ten rice cakes); handin is the leader's, from the tenth cake to Growlie taking them, learned per leader. The
 * bunny's prior is content, not a tunable: ten cakes at the Moon Bunny's drop period over three (MapleMap drops a cake
 * every dropPeriod / 3 for it), read from LifeFactory when the profile warms. No segment is a fight, so the members'
 * power only names a companion that the stage's Goblin Fires would put down (FRAGILE). A host registers it by
 * {@link #QUEST_KEY} with {@link #load} (on companion-ops).
 */
public final class AgentHpqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = "hpq";

    public static final String YES = "yes";
    public static final String SEEDS = "seeds";
    public static final String BUNNY = "bunny";
    public static final String HANDIN = "handin";

    /**
     * Flyeye, Stirge and Goblin Fire as Mob.wz has them at 9300062-64: the same stats as the 9300081-83 HenesysPQ
     * spawns on Primrose Hill. Goblin Fire hits hardest, so its touch decides who is fragile.
     */
    private static final List<Integer> MOB_IDS = List.of(9_300_062, 9_300_063, 9_300_064);
    private static final int DANGER_MOB_ID = 9_300_064;

    private static final double YES_TO_STAGE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile.YES_TO_STAGE_MS");
    private static final double SEEDS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile.SEEDS_MS");
    private static final double HANDIN_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile.HANDIN_MS");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile.WEAK_HIT_CHANCE");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile.PACE_EPOCH");

    private final AgentPartyQuestDefinition definition;
    private final List<AgentPqPaceSegment> segments;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentHpqPaceProfile(AgentPartyQuestDefinition definition, Map<String, Double> priors) {
        this.definition = definition;
        this.segments = List.of(
                new AgentPqPaceSegment.Fixed(YES, AgentPqPaceSegment.Side.COMPANION, YES, false),
                new AgentPqPaceSegment.Fixed(SEEDS, AgentPqPaceSegment.Side.COMPANION, SEEDS, false),
                new AgentPqPaceSegment.Fixed(BUNNY, AgentPqPaceSegment.Side.COMPANION, BUNNY, false),
                // The hand-in ends with Growlie's clear at the leader's own pace: where he takes the cakes shapes it.
                new AgentPqPaceSegment.Fixed(HANDIN, AgentPqPaceSegment.Side.LEADER, HANDIN, true));
        this.priors = Collections.unmodifiableMap(new TreeMap<>(priors));
        this.thresholds = new Thresholds(FRAGILE_TOUCHES, WEAK_HIT_CHANCE, Map.of());
        // Walked once here: the estimate asks for them on every lineup.
        this.terms = Collections.unmodifiableSet(AgentPqPaceProfile.super.terms());
        this.leaderTerms = Collections.unmodifiableSet(AgentPqPaceProfile.super.leaderTerms());
    }

    /**
     * Reads the Moon Bunny's drop period from LifeFactory, then loads the profile's mobs there too, so a member's power
     * read later on a leader's I/O thread finds them in memory. On companion-ops: it parses game data.
     */
    public static AgentHpqPaceProfile load() {
        Monster bunny = LifeFactory.getMonster(AgentHpqDefinition.MOON_BUNNY);
        if (bunny == null) {
            throw new IllegalStateException("LifeFactory cannot load the Moon Bunny " + AgentHpqDefinition.MOON_BUNNY);
        }
        AgentHpqPaceProfile profile = load(bunny.getDropPeriodTime());
        for (int mobId : profile.mobIds()) {
            if (LifeFactory.getMonster(mobId) == null) {
                throw new IllegalStateException("LifeFactory cannot load HPQ mob " + mobId);
            }
        }
        return profile;
    }

    /** The profile for a Moon Bunny of this drop period (ms, as LifeFactory reads Mob.wz dropItemPeriod). */
    static AgentHpqPaceProfile load(long bunnyDropPeriodMs) {
        if (bunnyDropPeriodMs <= 0L) {
            throw new IllegalStateException("the Moon Bunny has no drop period, so its cakes cannot be priced");
        }
        double cakeMs = (double) bunnyDropPeriodMs / AgentHpqDefinition.MOON_BUNNY_DROP_PERIOD_DIVISOR;
        Map<String, Double> priors = new LinkedHashMap<>();
        priors.put(YES, YES_TO_STAGE_MS);
        priors.put(SEEDS, SEEDS_MS);
        priors.put(BUNNY, AgentHpqDefinition.REQUIRED_RICE_CAKES * cakeMs);
        priors.put(HANDIN, HANDIN_MS);
        return new AgentHpqPaceProfile(AgentPartyQuestCatalog.require(QUEST_KEY), priors);
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
        return DANGER_MOB_ID;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        if (partySize < minSize() || partySize > maxSize()) {
            throw new IllegalArgumentException("HPQ takes " + minSize() + "-" + maxSize() + " members, not "
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
