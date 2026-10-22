package server.agents.capabilities.partyquest.opq;

import provider.Data;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
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
 * OPQ as the clear-time estimate sees it, from Wonky's Yes to the rewards claimed, the same for five or six members:
 * <pre>
 * entrance + rooms + statue + garden + root + claim
 * </pre>
 * entrance is the leader's (his talk to Eak at the entrance, then the walk to the centre); rooms (the six rooms worked
 * by the companions, five of them ending at his click on Eak) and statue (the pieces at his feet put up, and his talk
 * to Eak in the centre) are the companions' times, bounded by the leader; garden is a Clear of Papa Pixie that every
 * member fights, the leader included (the seeds and the Nependeaths are inside its efficiency); root is the leader's,
 * the Root of Life carried to the statue until Minerva is free; claim is the companions' rewards. These are the
 * report's spans (AgentOpqPaceReport), one per part of the run. Papa Pixie's touch decides who is fragile. A host
 * registers it by {@link #QUEST_KEY} with {@link #load} (on companion-ops).
 */
public final class AgentOpqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = "opq";

    public static final String ENTRANCE = "entrance";
    public static final String ROOMS = "rooms";
    public static final String STATUE = "statue";
    public static final String GARDEN = "garden";
    public static final String ROOT = "root";
    public static final String CLAIM = "claim";

    /**
     * The mobs a member is weighed against: the walkway's Lunar Pixie, the lounge's Cellion, the garden's Nependeath
     * and Papa Pixie, in that order.
     */
    static final List<Integer> MOB_IDS = List.of(9_300_045, 9_300_041, 9_300_048, AgentOpqDefinition.PAPA_PIXIE);

    private static final double ENTRANCE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile.ENTRANCE_MS");
    private static final double ROOMS_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile.ROOMS_MS");
    private static final double STATUE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile.STATUE_MS");
    private static final double GARDEN_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile.GARDEN_EFFICIENCY");
    private static final double ROOT_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile.ROOT_MS");
    private static final double CLAIM_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile.CLAIM_MS");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile.WEAK_HIT_CHANCE");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile.PACE_EPOCH");

    private final AgentPartyQuestDefinition definition;
    private final List<AgentPqPaceSegment> segments;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentOpqPaceProfile(AgentPartyQuestDefinition definition, Map<Integer, Integer> hp) {
        this.definition = definition;
        this.segments = List.of(
                new AgentPqPaceSegment.Fixed(ENTRANCE, AgentPqPaceSegment.Side.LEADER),
                // Five rooms end at his click on Eak, and the statue's pieces at his feet: where he waits shapes both.
                new AgentPqPaceSegment.Fixed(ROOMS, AgentPqPaceSegment.Side.COMPANION, ROOMS, true),
                new AgentPqPaceSegment.Fixed(STATUE, AgentPqPaceSegment.Side.COMPANION, STATUE, true),
                new AgentPqPaceSegment.Clear(GARDEN, Map.of(AgentOpqDefinition.PAPA_PIXIE, 1), hp,
                        AgentOpqPaceProfile::everyoneOnPapaPixie, true),
                new AgentPqPaceSegment.Fixed(ROOT, AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Fixed(CLAIM, AgentPqPaceSegment.Side.COMPANION));
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(ENTRANCE, ENTRANCE_MS);
        values.put(ROOMS, ROOMS_MS);
        values.put(STATUE, STATUE_MS);
        values.put(GARDEN, GARDEN_EFFICIENCY);
        values.put(ROOT, ROOT_MS);
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
    public static AgentOpqPaceProfile load() {
        AgentOpqPaceProfile profile = load(Census.WZ);
        for (int mobId : profile.mobIds()) {
            if (LifeFactory.getMonster(mobId) == null) {
                throw new IllegalStateException("LifeFactory cannot load OPQ mob " + mobId);
            }
        }
        return profile;
    }

    /** The profile from this census: every mob's HP as Mob.wz has it. */
    static AgentOpqPaceProfile load(Census census) {
        Map<Integer, Integer> hp = new TreeMap<>();
        for (int mobId : MOB_IDS) {
            int mobHp = census.hp(mobId);
            if (mobHp <= 0) throw new IllegalStateException("Mob.wz has no HP for " + mobId);
            hp.put(mobId, mobHp);
        }
        return new AgentOpqPaceProfile(AgentPartyQuestCatalog.require(QUEST_KEY), hp);
    }

    /** Every member that can hurt Papa Pixie on him: the garden's clear has no other type. */
    static Map<Integer, Integer> everyoneOnPapaPixie(Map<Integer, Map<Integer, Double>> damageByMember) {
        Map<Integer, Integer> choice = new TreeMap<>();
        damageByMember.forEach((memberId, damage) -> {
            if (damage != null && damage.containsKey(AgentOpqDefinition.PAPA_PIXIE)) {
                choice.put(memberId, AgentOpqDefinition.PAPA_PIXIE);
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
        return AgentOpqDefinition.PAPA_PIXIE;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        if (partySize < minSize() || partySize > maxSize()) {
            throw new IllegalArgumentException("OPQ takes " + minSize() + "-" + maxSize() + " members, not "
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
