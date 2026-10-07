package server.agents.capabilities.partyquest.epq;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
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
 * EPQ as the clear-time estimate sees it, from Ellin's Yes to the golem's clear, the same for every party size (four to
 * six):
 * <pre>
 * yes + trees + bottles + maze + marbles + handin + climb + sprite + altar + golem
 * </pre>
 * trees is a Clear of stage 1's Map.wz census (50 Poisoned Lord Trees) that every member fights, the leader included;
 * golem is a Clear of the three Poison Golems fought one after another (the revive chain: everyone on the lowest live
 * golem). bottles, maze, marbles and climb are the companions' fixed times (marbles and climb end at the leader's
 * feet); handin (the marbles to Ellin), sprite (the stone to the sprite) and altar (the stone under the altar) are the
 * leader's, learned per leader. The Super-Charged Poison Golem's touch decides who is fragile. A host registers it by
 * {@link #QUEST_KEY} with {@link #load} (on companion-ops).
 */
public final class AgentEpqPaceProfile implements AgentPqPaceProfile {
    public static final String QUEST_KEY = "epq";

    public static final String YES = "yes";
    public static final String TREES = "trees";
    public static final String BOTTLES = "bottles";
    public static final String MAZE = "maze";
    public static final String MARBLES = "marbles";
    public static final String HANDIN = "handin";
    public static final String CLIMB = "climb";
    public static final String SPRITE = "sprite";
    public static final String ALTAR = "altar";
    public static final String GOLEM = "golem";

    /** The mobs a member is weighed against: stage 1's trees and the three golems, in that order. */
    static final List<Integer> MOB_IDS = List.of(AgentEpqDefinition.STAGE_ONE_MOB, AgentEpqDefinition.POISON_GOLEM,
            AgentEpqDefinition.CHARGED_POISON_GOLEM, AgentEpqDefinition.SUPER_CHARGED_POISON_GOLEM);

    private static final double YES_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.YES_MS");
    private static final double TREES_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.TREES_EFFICIENCY");
    private static final double BOTTLES_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.BOTTLES_MS");
    private static final double MAZE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.MAZE_MS");
    private static final double MARBLES_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.MARBLES_MS");
    private static final double HANDIN_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.HANDIN_MS");
    private static final double CLIMB_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.CLIMB_MS");
    private static final double SPRITE_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.SPRITE_MS");
    private static final double ALTAR_MS = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.ALTAR_MS");
    private static final double GOLEM_EFFICIENCY = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.GOLEM_EFFICIENCY");
    private static final double FRAGILE_TOUCHES = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.FRAGILE_TOUCHES");
    private static final double WEAK_HIT_CHANCE = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.WEAK_HIT_CHANCE");
    private static final int PACE_EPOCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile.PACE_EPOCH");

    private final AgentPartyQuestDefinition definition;
    private final List<AgentPqPaceSegment> segments;
    private final Map<String, Double> priors;
    private final Thresholds thresholds;
    private final Set<String> terms;
    private final Set<String> leaderTerms;

    private AgentEpqPaceProfile(AgentPartyQuestDefinition definition, Map<Integer, Integer> trees,
                                Map<Integer, Integer> golems, Map<Integer, Integer> hp) {
        this.definition = definition;
        this.segments = List.of(
                new AgentPqPaceSegment.Fixed(YES, AgentPqPaceSegment.Side.COMPANION),
                new AgentPqPaceSegment.Clear(TREES, trees, hp, AgentEpqPaceProfile::everyoneOnTheLowest, true),
                new AgentPqPaceSegment.Fixed(BOTTLES, AgentPqPaceSegment.Side.COMPANION),
                new AgentPqPaceSegment.Fixed(MAZE, AgentPqPaceSegment.Side.COMPANION),
                // The twentieth marble ends at the leader's feet: where he waits shapes it.
                new AgentPqPaceSegment.Fixed(MARBLES, AgentPqPaceSegment.Side.COMPANION, MARBLES, true),
                new AgentPqPaceSegment.Fixed(HANDIN, AgentPqPaceSegment.Side.LEADER),
                // So does the stone, carried down the climb to his feet.
                new AgentPqPaceSegment.Fixed(CLIMB, AgentPqPaceSegment.Side.COMPANION, CLIMB, true),
                new AgentPqPaceSegment.Fixed(SPRITE, AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Fixed(ALTAR, AgentPqPaceSegment.Side.LEADER),
                new AgentPqPaceSegment.Clear(GOLEM, golems, hp, AgentEpqPaceProfile::everyoneOnTheLowest, true));
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(YES, YES_MS);
        values.put(TREES, TREES_EFFICIENCY);
        values.put(BOTTLES, BOTTLES_MS);
        values.put(MAZE, MAZE_MS);
        values.put(MARBLES, MARBLES_MS);
        values.put(HANDIN, HANDIN_MS);
        values.put(CLIMB, CLIMB_MS);
        values.put(SPRITE, SPRITE_MS);
        values.put(ALTAR, ALTAR_MS);
        values.put(GOLEM, GOLEM_EFFICIENCY);
        this.priors = Collections.unmodifiableMap(new TreeMap<>(values));
        this.thresholds = new Thresholds(FRAGILE_TOUCHES, WEAK_HIT_CHANCE, Map.of());
        // Walked once here: the estimate asks for them on every lineup.
        this.terms = Collections.unmodifiableSet(AgentPqPaceProfile.super.terms());
        this.leaderTerms = Collections.unmodifiableSet(AgentPqPaceProfile.super.leaderTerms());
    }

    /**
     * Builds the profile from Map.wz and Mob.wz, then loads its mobs into LifeFactory, so a member's power read later
     * on a leader's I/O thread finds them in memory. On companion-ops: it parses game data.
     */
    public static AgentEpqPaceProfile load() {
        AgentEpqPaceProfile profile = load(Census.WZ);
        for (int mobId : profile.mobIds()) {
            if (LifeFactory.getMonster(mobId) == null) {
                throw new IllegalStateException("LifeFactory cannot load EPQ mob " + mobId);
            }
        }
        return profile;
    }

    /** The profile from this census: stage 1's trees as Map.wz places them, every mob's HP as Mob.wz has it. */
    static AgentEpqPaceProfile load(Census census) {
        int treeCount = census.mobCounts(AgentEpqDefinition.STAGE_ONE_MAP)
                .getOrDefault(AgentEpqDefinition.STAGE_ONE_MOB, 0);
        if (treeCount <= 0) {
            throw new IllegalStateException("Map.wz places no Poisoned Lord Tree on " + AgentEpqDefinition.STAGE_ONE_MAP);
        }
        Map<Integer, Integer> hp = new TreeMap<>();
        for (int mobId : MOB_IDS) {
            Census.MobFacts facts = census.mob(mobId);
            if (facts == null || facts.hp() <= 0) throw new IllegalStateException("Mob.wz has no HP for " + mobId);
            hp.put(mobId, facts.hp());
        }
        Map<Integer, Integer> golems = new TreeMap<>();
        for (int golem : MOB_IDS.subList(1, MOB_IDS.size())) golems.put(golem, 1);
        return new AgentEpqPaceProfile(AgentPartyQuestCatalog.require(QUEST_KEY),
                Map.of(AgentEpqDefinition.STAGE_ONE_MOB, treeCount), golems, hp);
    }

    /**
     * Every member on the lowest live mob id it can hurt: stage 1 has one type, and the golems revive one into the next
     * (9300180, then 9300181, then 9300182), so the water-fill kills them one after another as the chain does.
     */
    static Map<Integer, Integer> everyoneOnTheLowest(Map<Integer, Map<Integer, Double>> damageByMember) {
        Map<Integer, Integer> choice = new TreeMap<>();
        new TreeMap<>(damageByMember).forEach((memberId, damage) -> {
            if (damage == null || damage.isEmpty()) return;
            choice.put(memberId, damage.keySet().stream().min(Integer::compare).orElseThrow());
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
        return AgentEpqDefinition.SUPER_CHARGED_POISON_GOLEM;
    }

    @Override
    public boolean warmed() {
        return true;
    }

    @Override
    public List<AgentPqPaceSegment> segments(int partySize) {
        if (partySize < minSize() || partySize > maxSize()) {
            throw new IllegalArgumentException("EPQ takes " + minSize() + "-" + maxSize() + " members, not "
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

    /** Where the profile reads its content: Map.wz life and Mob.wz stats, or a test's stub (KPQ's shape, EPQ's copy). */
    interface Census {
        /** Mob id to the number of its spawn points in the map's WZ life. */
        Map<Integer, Integer> mobCounts(int mapId);

        MobFacts mob(int mobId);

        /** A mob's HP and whether it is a boss. */
        record MobFacts(int hp, boolean boss) {
        }

        Census WZ = new Census() {
            @Override
            public Map<Integer, Integer> mobCounts(int mapId) {
                Data map = DataProviderFactory.getDataProvider(WZFiles.MAP).getData(mapImage(mapId));
                if (map == null) throw new IllegalStateException("Map.wz has no map " + mapId);
                Map<Integer, Integer> counts = new TreeMap<>();
                Data life = map.getChildByPath("life");
                if (life == null) return counts;
                for (Data entry : life) {
                    if ("m".equals(DataTool.getString(entry.getChildByPath("type"), ""))) {
                        counts.merge(Integer.parseInt(DataTool.getString(entry.getChildByPath("id"), "").trim()), 1,
                                Integer::sum);
                    }
                }
                return counts;
            }

            /** Mob.wz info as LifeFactory reads it (maxHP and boss of the mob itself, boss falling back to its link). */
            @Override
            public MobFacts mob(int mobId) {
                Data info = mobInfo(mobId);
                if (info == null) return null;
                Data hp = info.getChildByPath("maxHP");
                long maxHp = hp == null ? 1L : Long.parseLong(String.valueOf(hp.getData()));
                int link = DataTool.getIntConvert("link", info, 0);
                Data linked = link == 0 ? null : mobInfo(link);
                boolean linkedBoss = linked != null && DataTool.getIntConvert("boss", linked, 0) > 0;
                return new MobFacts((int) Math.min(Integer.MAX_VALUE, maxHp),
                        DataTool.getIntConvert("boss", info, linkedBoss ? 1 : 0) > 0);
            }
        };

        private static String mapImage(int mapId) {
            return "Map/Map" + mapId / 100_000_000 + "/" + String.format("%09d", mapId) + ".img";
        }

        private static Data mobInfo(int mobId) {
            Data mob = DataProviderFactory.getDataProvider(WZFiles.MOB).getData(String.format("%07d", mobId) + ".img");
            return mob == null ? null : mob.getChildByPath("info");
        }
    }
}
