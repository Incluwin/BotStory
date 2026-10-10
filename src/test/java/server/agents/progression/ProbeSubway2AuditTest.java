package server.agents.progression;

import client.Character;
import client.Job;
import client.QuestStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Temporary audit probe. Not part of the branch. */
class ProbeSubway2AuditTest {
    static final Path OUT = Path.of(
            "C:/Users/ganre/AppData/Local/Temp/claude/C--Users-ganre-Desktop-MapleServer/"
                    + "57350396-c11f-43a7-ab35-10105dd00020/scratchpad/subway2-audit");
    static Map<Integer, Integer> FACTS;
    static final AgentVictoriaQuestHuntIndexRepository INDEX =
            AgentVictoriaQuestHuntIndexRepository.defaultRepository();
    static final int WINDOW = config.AgentTuning.intValue(
            "server.agents.progression.AgentAdaptiveQuestHuntSelector.LEVEL_WINDOW_ABOVE");

    record Pick(int mapId, int maximumAgents, String source) {
    }

    interface Sel {
        Optional<Pick> pick(AgentHuntSelectionRequest request);
    }

    interface SelFactory {
        Sel create(BiFunction<Character, Set<Integer>, Map<Integer, Integer>> population);
    }

    static final SelFactory HEAD = population -> {
        AgentAdaptiveQuestHuntSelector selector = new AgentAdaptiveQuestHuntSelector(
                AgentVictoriaQuestHuntPolicyRepository.defaultRepository(), INDEX, population);
        return request -> selector.select(request).map(s -> new Pick(
                s.map().mapId(), s.map().maximumAgents(), s.source().name()));
    };
    static final SelFactory OLD = population -> {
        ProbeOldHuntSelector249 selector = new ProbeOldHuntSelector249(
                AgentVictoriaQuestHuntPolicyRepository.defaultRepository(), INDEX);
        selector.population = population;
        return request -> selector.select(request).map(s -> new Pick(
                s.map().mapId(), s.map().maximumAgents(), s.source().name()));
    };
    static final SelFactory BASE = population -> {
        ProbeBaseHuntSelector9ae selector = new ProbeBaseHuntSelector9ae(
                AgentVictoriaQuestHuntPolicyRepository.defaultRepository(), INDEX);
        selector.population = population;
        return request -> selector.select(request).map(s -> new Pick(
                s.map().mapId(), s.map().maximumAgents(), s.source().name()));
    };

    /** A mutable mock Agent. */
    static final class Agent {
        final int[] state; // level, mapId
        final Character character;
        final AgentRuntimeEntry entry;

        Agent(int id, int level, int mapId) {
            state = new int[] {level, mapId};
            character = mock(Character.class);
            when(character.getId()).thenReturn(id);
            when(character.getName()).thenReturn("Probe" + id);
            when(character.getJob()).thenReturn(Job.THIEF);
            when(character.getLevel()).thenAnswer(ignored -> state[0]);
            when(character.getMapId()).thenAnswer(ignored -> state[1]);
            entry = new AgentRuntimeEntry(character, null, null);
        }

        void at(int level, int mapId) {
            state[0] = level;
            state[1] = mapId;
        }

        int map() {
            return state[1];
        }
    }

    /** Request shapes the per-tick callers build. */
    enum Shape { SCHEDULER, QUEST_PACK, QUEST_PACK_RECOVERY, INSTRUCTOR }

    record Hunt(int questId, AgentVictoriaQuestRuntimeCatalog.HuntingObjective objective, List<Integer> starts,
                int baseLevel) {
    }

    static List<Hunt> hunts() {
        List<Hunt> hunts = new ArrayList<>();
        for (AgentVictoriaQuestRuntimeCatalog.Entry entry
                : AgentVictoriaQuestRuntimeCatalogRepository.defaultRepository().catalog().entries()) {
            int baseLevel = entry.minLevel() == null ? 1 : Math.max(1, entry.minLevel());
            for (AgentVictoriaQuestRuntimeCatalog.HuntingObjective objective : entry.huntingObjectives()) {
                hunts.add(new Hunt(entry.questId(), objective, entry.startMapIds(), baseLevel));
            }
        }
        return hunts;
    }

    static AgentHuntSelectionRequest request(Agent agent, Hunt hunt, Shape shape, Set<Integer> excluded,
                                             long nowMs) {
        AgentVictoriaQuestRuntimeCatalog.HuntingObjective objective = hunt.objective();
        List<AgentHuntSelectionRequest.ObjectiveDemand> demands = List.of(
                new AgentHuntSelectionRequest.ObjectiveDemand(
                        hunt.questId(), objective.objectiveId(), objective.type(),
                        objective.targetId(), objective.requiredCount(), 0,
                        Set.copyOf(objective.sourceMobIds())));
        return switch (shape) {
            case SCHEDULER -> new AgentHuntSelectionRequest(agent.entry, agent.character,
                    "scheduler:" + hunt.questId() + ":" + objective.objectiveId(), demands,
                    objective.huntMaps(), excluded, false, AgentHuntSelectionRequest.Reason.NORMAL, nowMs);
            case QUEST_PACK -> new AgentHuntSelectionRequest(agent.entry, agent.character,
                    "pack:x:" + hunt.questId() + ":" + objective.objectiveId(), demands,
                    objective.huntMaps(), excluded, true, AgentHuntSelectionRequest.Reason.NORMAL, nowMs);
            case QUEST_PACK_RECOVERY -> new AgentHuntSelectionRequest(agent.entry, agent.character,
                    "pack:x:" + hunt.questId() + ":" + objective.objectiveId(), demands,
                    objective.huntMaps(), excluded, true,
                    AgentHuntSelectionRequest.Reason.EXHAUSTION_FALLBACK, nowMs);
            case INSTRUCTOR -> new AgentHuntSelectionRequest(agent.entry, agent.character,
                    "instructor:" + hunt.questId() + ":" + objective.objectiveId(), demands,
                    List.of(), excluded, true, AgentHuntSelectionRequest.Reason.EXHAUSTION_FALLBACK, nowMs);
        };
    }

    static int coverage(int mapId, List<AgentHuntSelectionRequest.ObjectiveDemand> demands) {
        int covered = 0;
        for (AgentHuntSelectionRequest.ObjectiveDemand demand : demands) {
            if (demand.remainingCount() <= 0) {
                continue;
            }
            boolean found = INDEX.findObjective(demand.questId(), demand.objectiveId())
                    .map(AgentVictoriaQuestHuntIndex.Objective::candidates)
                    .orElseGet(() -> INDEX.findCandidatesForMobs(demand.sourceMobIds()))
                    .stream().anyMatch(candidate -> candidate.mapId() == mapId);
            if (found) {
                covered++;
            }
        }
        return covered;
    }

    static int strongest(int mapId) {
        Integer facts = FACTS.get(mapId);
        if (facts != null) {
            return facts;
        }
        return INDEX.maxMobLevel(mapId).orElse(-1);
    }

    static int above(int mapId, int level) {
        int s = strongest(mapId);
        return s < 0 ? 999 : Math.max(0, s - level - WINDOW);
    }

    @BeforeAll
    static void setUp() throws Exception {
        Configurator.setLevel("server.agents.progression", Level.WARN);
        Configurator.setLevel("server.agents", Level.WARN);
        Files.createDirectories(OUT);
        try (InputStream input = ProbeSubway2AuditTest.class
                .getResourceAsStream("/agents/catalogs/adaptive/victoria-map-facts.json")) {
            Map<Integer, Integer> strongest = new HashMap<>();
            for (JsonNode entry : new ObjectMapper().readTree(input).path("entries")) {
                strongest.put(entry.path("mapId").asInt(), entry.path("maxMobLevel").asInt());
            }
            FACTS = Map.copyOf(strongest);
        }
    }

    static BiFunction<Character, Set<Integer>, Map<Integer, Integer>> population(Map<Integer, Integer> others,
                                                                                 List<Agent> agents) {
        return (asking, mapIds) -> {
            Map<Integer, Integer> counts = new HashMap<>();
            for (int mapId : mapIds) {
                int count = others.getOrDefault(mapId, 0);
                for (Agent agent : agents) {
                    if (agent.map() == mapId) {
                        count++;
                    }
                }
                counts.put(mapId, count);
            }
            return counts;
        };
    }

    static final BiFunction<Character, Set<Integer>, Map<Integer, Integer>> EMPTY = (agent, ids) -> Map.of();
    static Agent P1;
    static Agent P2;
    static Agent P3;

    static Agent p1(int level, int mapId) {
        if (P1 == null) {
            P1 = new Agent(990101, level, mapId);
        }
        P1.at(level, mapId);
        return P1;
    }

    static Agent p2(int level, int mapId) {
        if (P2 == null) {
            P2 = new Agent(990102, level, mapId);
        }
        P2.at(level, mapId);
        return P2;
    }

    static Agent p3(int level, int mapId) {
        if (P3 == null) {
            P3 = new Agent(990103, level, mapId);
        }
        P3.at(level, mapId);
        return P3;
    }

    // ---------------------------------------------------------------------------------------------
    // Scan 1: catalog-wide fresh choices, HEAD vs 249f50d5b vs 9ae48b696.
    // ---------------------------------------------------------------------------------------------
    @Test
    void scanCatalog() throws Exception {
        List<String> all = new ArrayList<>();
        List<String> diffs = new ArrayList<>();
        Map<String, Integer> tally = new TreeMap<>();
        Agent agent = p1(1, 0);
        for (Hunt hunt : hunts()) {
            List<AgentHuntSelectionRequest.ObjectiveDemand> demands =
                    request(agent, hunt, Shape.SCHEDULER, Set.of(), 100L).objectives();
            Set<Integer> origins = new LinkedHashSet<>(hunt.starts());
            origins.addAll(List.of(100000000, 101000000, 101010102, 102000000, 103000000, 103000101, 104000000,
                    105040300));
            for (int start : origins) {
                for (int level = hunt.baseLevel(); level <= hunt.baseLevel() + 45; level++) {
                    agent.at(level, start);
                    for (Shape shape : List.of(Shape.SCHEDULER)) {
                        Optional<Pick> head = HEAD.create(EMPTY).pick(request(agent, hunt, shape, Set.of(), 100L));
                        Optional<Pick> old = OLD.create(EMPTY).pick(request(agent, hunt, shape, Set.of(), 100L));
                        Optional<Pick> base = Optional.empty();
                        int h = head.map(Pick::mapId).orElse(0);
                        int o = old.map(Pick::mapId).orElse(0);
                        int b = base.map(Pick::mapId).orElse(0);
                        String line = shape + "|" + hunt.questId() + "|" + hunt.objective().objectiveId() + "|"
                                + start + "|" + level + "|head=" + h + "(" + strongest(h) + ",cov"
                                + coverage(h, demands) + ")|old=" + o + "(" + strongest(o) + ",cov"
                                + coverage(o, demands) + ")|base=" + b + "(" + strongest(b) + ")";
                        all.add(line);
                        if (h != o) {
                            String kind;
                            if (h == 0) {
                                kind = "HEAD_EMPTY";
                            } else if (coverage(h, demands) < coverage(o, demands)) {
                                kind = "WORSE_COVERAGE";
                            } else if (strongest(h) > strongest(o)) {
                                kind = coverage(h, demands) > coverage(o, demands)
                                        ? "STRONGER_BUT_GAINS_COVERAGE" : "WORSE_STRONGER";
                            } else {
                                kind = "OTHER";
                            }
                            tally.merge(shape + ":" + kind, 1, Integer::sum);
                            diffs.add(kind + "|" + line);
                        }
                    }
                }
            }
        }
        Files.write(OUT.resolve("scan-catalog-all.txt"), all);
        Files.write(OUT.resolve("scan-catalog-diffs.txt"), diffs);
        Files.writeString(OUT.resolve("scan-catalog-tally.txt"), all.size() + " rows\n" + tally + "\n");
    }

    // ---------------------------------------------------------------------------------------------
    // Scan 2: shared quest packs (multi-objective), HEAD vs 249f50d5b.
    // ---------------------------------------------------------------------------------------------
    @Test
    void scanSharedPacks() throws Exception {
        List<String> diffs = new ArrayList<>();
        List<String> all = new ArrayList<>();
        Map<String, Integer> tally = new TreeMap<>();
        Agent agent = p1(1, 0);
        for (AgentVictoriaSharedQuestPackCatalog.Pack pack : AgentVictoriaSharedQuestPackCatalog.packs()) {
            List<Integer> questIds = pack.steps().stream()
                    .filter(step -> "QUEST".equals(step.type()) && step.questId() > 0)
                    .map(AgentVictoriaSharedQuestPackCatalog.Step::questId).distinct().toList();
            List<Set<Integer>> scenarios = new ArrayList<>();
            scenarios.add(Set.copyOf(questIds));
            questIds.forEach(id -> scenarios.add(Set.of(id)));
            int minLevel = pack.steps().stream().mapToInt(AgentVictoriaSharedQuestPackCatalog.Step::requiredLevel)
                    .filter(level -> level > 0).min().orElse(1);
            for (Set<Integer> active : scenarios) {
                PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
                when(gateway.questStatus(any(), anyInt())).thenAnswer(invocation ->
                        active.contains((Integer) invocation.getArgument(1))
                                ? QuestStatus.Status.STARTED.getId() : QuestStatus.Status.NOT_STARTED.getId());
                AgentQuestPackDebtSnapshot debt = AgentQuestPackDebtSnapshot.capture(pack, agent.character, gateway);
                List<AgentHuntSelectionRequest.ObjectiveDemand> demands = debt.demands();
                if (demands.isEmpty()) {
                    continue;
                }
                for (AgentVictoriaSharedQuestPackCatalog.Step step : pack.steps()) {
                    if (!"HUNT".equals(step.type()) || step.mapId() <= 0 || step.preferredMobIds().isEmpty()) {
                        continue;
                    }
                    AgentHuntObjectiveSpec spec = AgentHuntObjectiveCompiler.sharedQuestPack(
                            "shared:" + pack.packId(), pack, step, demands);
                    Set<Integer> origins = new LinkedHashSet<>();
                    origins.add(pack.homeTownMapId());
                    origins.add(step.mapId());
                    for (int origin : origins) {
                        for (int level = Math.max(1, minLevel); level <= minLevel + 45; level++) {
                            agent.at(level, origin);
                            AgentHuntSelectionRequest request = new AgentHuntSelectionRequest(
                                    agent.entry, agent.character, spec, Set.of(),
                                    AgentHuntSelectionRequest.Reason.NORMAL, 100L);
                            int h = HEAD.create(EMPTY).pick(request).map(Pick::mapId).orElse(0);
                            int o = OLD.create(EMPTY).pick(request).map(Pick::mapId).orElse(0);
                            String line = pack.packId() + "|active=" + active + "|step=" + step.mapId() + "|from="
                                    + origin + "|lv" + level + "|head=" + h + "(" + strongest(h) + ",cov"
                                    + coverage(h, demands) + "/" + demands.size() + ")|old=" + o + "("
                                    + strongest(o) + ",cov" + coverage(o, demands) + ")";
                            all.add(line);
                            if (h != o) {
                                String kind;
                                if (h == 0) {
                                    kind = "HEAD_EMPTY";
                                } else if (coverage(h, demands) < coverage(o, demands)) {
                                    kind = "WORSE_COVERAGE";
                                } else if (strongest(h) > strongest(o)) {
                                    kind = coverage(h, demands) > coverage(o, demands)
                                            ? "STRONGER_BUT_GAINS_COVERAGE" : "WORSE_STRONGER";
                                } else {
                                    kind = "OTHER";
                                }
                                tally.merge(kind, 1, Integer::sum);
                                diffs.add(kind + "|" + line);
                            }
                        }
                    }
                }
            }
        }
        Files.write(OUT.resolve("scan-packs-all.txt"), all);
        Files.write(OUT.resolve("scan-packs-diffs.txt"), diffs);
        Files.writeString(OUT.resolve("scan-packs-tally.txt"), all.size() + " rows\n" + tally + "\n");
    }

    // ---------------------------------------------------------------------------------------------
    // Scan 3: per-tick walk simulation with occupancy (livelock / overfill), HEAD vs 249f50d5b.
    // ---------------------------------------------------------------------------------------------
    enum Occupancy { NEARLY_FULL, EXACTLY_FULL, NEARLY_FULL_TOP_TWO, HUMAN_ARRIVES }

    record WalkResult(boolean settled, boolean overfill, List<Integer> trail, String note) {
    }

    interface ReqMaker {
        AgentHuntSelectionRequest make(Agent agent, long nowMs);
    }

    static WalkResult walk(SelFactory factory, Hunt hunt, Shape shape, int level, int start,
                           Map<Integer, Integer> others, boolean humanArrives, int agentId) {
        return walk(factory, (agent, nowMs) -> request(agent, hunt, shape, Set.of(), nowMs), level, start, others,
                humanArrives);
    }

    static WalkResult walk(SelFactory factory, ReqMaker maker, int level, int start,
                           Map<Integer, Integer> others, boolean humanArrives) {
        Agent agent = p2(level, start);
        Map<Integer, Integer> live = new HashMap<>(others);
        Sel selector = factory.create(population(live, List.of(agent)));
        List<Integer> trail = new ArrayList<>();
        boolean overfill = false;
        long nowMs = 100L;
        int huntingSince = -1;
        boolean humanDone = !humanArrives;
        int ticks = 60;
        for (int tick = 0; tick < ticks; tick++) {
            Optional<Pick> pick = selector.pick(maker.make(agent, nowMs));
            nowMs += 1_000L;
            if (pick.isEmpty()) {
                trail.add(-1);
                return new WalkResult(true, overfill, trail, "empty");
            }
            int target = pick.get().mapId();
            trail.add(target);
            if (target == agent.map()) {
                int total = live.getOrDefault(target, 0) + 1;
                if (total > pick.get().maximumAgents()) {
                    overfill = true;
                }
                if (huntingSince < 0) {
                    huntingSince = tick;
                }
                if (humanDone && tick - huntingSince >= 15) {
                    return new WalkResult(true, overfill, trail, "hunting");
                }
                if (!humanDone && tick - huntingSince >= 3) {
                    // A human and friends arrive: the map now holds maximumAgents others besides the Agent.
                    live.put(target, pick.get().maximumAgents());
                    humanDone = true;
                    huntingSince = -1;
                }
                continue;
            }
            huntingSince = -1;
            Integer next = AgentVictoriaTrainingRouteCatalog.nextHop(agent.map(), target);
            if (next == null) {
                return new WalkResult(false, overfill, trail, "nohop");
            }
            agent.at(level, next);
        }
        // Settled: the last 15 ticks hunt on one map the Agent stands on.
        List<Integer> tail = trail.subList(trail.size() - 15, trail.size());
        boolean settled = tail.stream().distinct().count() == 1 && tail.getFirst() == agent.map();
        return new WalkResult(settled, overfill, trail, "");
    }

    @Test
    void walkWithOccupancy() throws Exception {
        List<String> out = new ArrayList<>();
        Map<String, int[]> tally = new TreeMap<>(); // key -> {runs, unsettled, overfill}
        int agentId = 991000;
        for (Hunt hunt : hunts()) {
            for (int start : hunt.starts()) {
                for (int level = hunt.baseLevel(); level <= hunt.baseLevel() + 45; level += 15) {
                    for (Shape shape : List.of(Shape.SCHEDULER, Shape.QUEST_PACK, Shape.INSTRUCTOR)) {
                        Agent probe = p1(level, start);
                        Optional<Pick> fresh = HEAD.create(EMPTY).pick(request(probe, hunt, shape, Set.of(), 100L));
                        if (fresh.isEmpty() || fresh.get().mapId() == start) {
                            continue;
                        }
                        Pick a = fresh.get();
                        for (Occupancy occupancy : Occupancy.values()) {
                            Map<Integer, Integer> others = new HashMap<>();
                            boolean human = false;
                            switch (occupancy) {
                                case NEARLY_FULL -> others.put(a.mapId(), a.maximumAgents() - 1);
                                case EXACTLY_FULL -> others.put(a.mapId(), a.maximumAgents());
                                case NEARLY_FULL_TOP_TWO -> {
                                    others.put(a.mapId(), a.maximumAgents() - 1);
                                    Map<Integer, Integer> blockA = Map.of(a.mapId(), a.maximumAgents());
                                    Agent second = p1(level, start);
                                    HEAD.create(population(blockA, List.of())).pick(
                                                    request(second, hunt, shape, Set.of(), 100L))
                                            .ifPresent(b -> others.put(b.mapId(), b.maximumAgents() - 1));
                                }
                                case HUMAN_ARRIVES -> human = true;
                            }
                            for (Map.Entry<String, SelFactory> version
                                    : (occupancy == Occupancy.NEARLY_FULL ? Map.of("HEAD", HEAD, "OLD", OLD)
                                    : Map.of("HEAD", HEAD)).entrySet()) {
                                WalkResult result = walk(version.getValue(), hunt, shape, level, start, others,
                                        human, agentId++);
                                String key = version.getKey() + ":" + shape + ":" + occupancy;
                                int[] counts = tally.computeIfAbsent(key, ignored -> new int[3]);
                                counts[0]++;
                                if (!result.settled()) {
                                    counts[1]++;
                                }
                                if (result.overfill()) {
                                    counts[2]++;
                                }
                                if (!result.settled() || result.overfill()) {
                                    out.add(key + "|" + hunt.questId() + "|" + hunt.objective().objectiveId()
                                            + "|from " + start + "|lv" + level + "|A=" + a.mapId() + " max "
                                            + a.maximumAgents() + "|settled=" + result.settled() + "|overfill="
                                            + result.overfill() + "|" + result.note() + "|trail="
                                            + result.trail());
                                }
                            }
                        }
                    }
                }
            }
        }
        StringBuilder summary = new StringBuilder();
        tally.forEach((key, counts) -> summary.append(key).append(" runs=").append(counts[0])
                .append(" unsettled=").append(counts[1]).append(" overfill=").append(counts[2]).append('\n'));
        Files.write(OUT.resolve("walk-failures.txt"), out);
        Files.writeString(OUT.resolve("walk-tally.txt"), summary.toString());
    }

    @Test
    void walkSharedPacks() throws Exception {
        List<String> out = new ArrayList<>();
        Map<String, int[]> tally = new TreeMap<>();
        Agent probe = p1(1, 0);
        for (AgentVictoriaSharedQuestPackCatalog.Pack pack : AgentVictoriaSharedQuestPackCatalog.packs()) {
            List<Integer> questIds = pack.steps().stream()
                    .filter(step -> "QUEST".equals(step.type()) && step.questId() > 0)
                    .map(AgentVictoriaSharedQuestPackCatalog.Step::questId).distinct().toList();
            List<Set<Integer>> scenarios = new ArrayList<>();
            scenarios.add(Set.copyOf(questIds));
            questIds.forEach(id -> scenarios.add(Set.of(id)));
            int minLevel = pack.steps().stream().mapToInt(AgentVictoriaSharedQuestPackCatalog.Step::requiredLevel)
                    .filter(level -> level > 0).min().orElse(1);
            for (Set<Integer> active : scenarios) {
                PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
                when(gateway.questStatus(any(), anyInt())).thenAnswer(invocation ->
                        active.contains((Integer) invocation.getArgument(1))
                                ? QuestStatus.Status.STARTED.getId() : QuestStatus.Status.NOT_STARTED.getId());
                List<AgentHuntSelectionRequest.ObjectiveDemand> demands =
                        AgentQuestPackDebtSnapshot.capture(pack, probe.character, gateway).demands();
                if (demands.isEmpty()) {
                    continue;
                }
                AgentVictoriaSharedQuestPackCatalog.Step step = pack.steps().stream()
                        .filter(candidate -> "HUNT".equals(candidate.type()) && candidate.mapId() > 0
                                && !candidate.preferredMobIds().isEmpty())
                        .findFirst().orElse(null);
                if (step == null) {
                    continue;
                }
                AgentHuntObjectiveSpec spec = AgentHuntObjectiveCompiler.sharedQuestPack(
                        "shared:" + pack.packId(), pack, step, demands);
                ReqMaker maker = (agent, nowMs) -> new AgentHuntSelectionRequest(agent.entry, agent.character, spec,
                        Set.of(), AgentHuntSelectionRequest.Reason.NORMAL, nowMs);
                int start = pack.homeTownMapId();
                for (int level = Math.max(1, minLevel); level <= minLevel + 45; level += 9) {
                    probe.at(level, start);
                    Optional<Pick> fresh = HEAD.create(EMPTY).pick(maker.make(probe, 100L));
                    if (fresh.isEmpty() || fresh.get().mapId() == start) {
                        continue;
                    }
                    Pick a = fresh.get();
                    for (Occupancy occupancy : Occupancy.values()) {
                        Map<Integer, Integer> others = new HashMap<>();
                        boolean human = false;
                        switch (occupancy) {
                            case NEARLY_FULL -> others.put(a.mapId(), a.maximumAgents() - 1);
                            case EXACTLY_FULL -> others.put(a.mapId(), a.maximumAgents());
                            case NEARLY_FULL_TOP_TWO -> {
                                others.put(a.mapId(), a.maximumAgents() - 1);
                                probe.at(level, start);
                                HEAD.create(population(Map.of(a.mapId(), a.maximumAgents()), List.of()))
                                        .pick(maker.make(probe, 100L))
                                        .ifPresent(b -> others.put(b.mapId(), b.maximumAgents() - 1));
                            }
                            case HUMAN_ARRIVES -> human = true;
                        }
                        for (Map.Entry<String, SelFactory> version
                                : (occupancy == Occupancy.NEARLY_FULL ? Map.of("HEAD", HEAD, "OLD", OLD)
                                : Map.of("HEAD", HEAD)).entrySet()) {
                            WalkResult result = walk(version.getValue(), maker, level, start, others, human);
                            String key = version.getKey() + ":SHARED:" + occupancy;
                            int[] counts = tally.computeIfAbsent(key, ignored -> new int[3]);
                            counts[0]++;
                            if (!result.settled()) {
                                counts[1]++;
                            }
                            if (result.overfill()) {
                                counts[2]++;
                            }
                            if (!result.settled() || result.overfill()) {
                                out.add(key + "|" + pack.packId() + "|active=" + active + "|from " + start + "|lv"
                                        + level + "|A=" + a.mapId() + " max " + a.maximumAgents() + "|settled="
                                        + result.settled() + "|overfill=" + result.overfill() + "|"
                                        + result.note() + "|trail=" + result.trail());
                            }
                        }
                    }
                }
            }
        }
        StringBuilder summary = new StringBuilder();
        tally.forEach((key, counts) -> summary.append(key).append(" runs=").append(counts[0])
                .append(" unsettled=").append(counts[1]).append(" overfill=").append(counts[2]).append('\n'));
        Files.write(OUT.resolve("walk-packs-failures.txt"), out);
        Files.writeString(OUT.resolve("walk-packs-tally.txt"), summary.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // Scan 6: a lease on a map that covers no objective, when a covering map reopens.
    // ---------------------------------------------------------------------------------------------
    @Test
    void leaseOnZeroCoverage() throws Exception {
        List<String> out = new ArrayList<>();
        int checked = 0;
        int kept = 0;
        Agent agent = p1(1, 0);
        for (AgentVictoriaSharedQuestPackCatalog.Pack pack : AgentVictoriaSharedQuestPackCatalog.packs()) {
            List<Integer> questIds = pack.steps().stream()
                    .filter(step -> "QUEST".equals(step.type()) && step.questId() > 0)
                    .map(AgentVictoriaSharedQuestPackCatalog.Step::questId).distinct().toList();
            List<Set<Integer>> scenarios = new ArrayList<>();
            scenarios.add(Set.copyOf(questIds));
            questIds.forEach(id -> scenarios.add(Set.of(id)));
            int minLevel = pack.steps().stream().mapToInt(AgentVictoriaSharedQuestPackCatalog.Step::requiredLevel)
                    .filter(level -> level > 0).min().orElse(1);
            for (Set<Integer> active : scenarios) {
                PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
                when(gateway.questStatus(any(), anyInt())).thenAnswer(invocation ->
                        active.contains((Integer) invocation.getArgument(1))
                                ? QuestStatus.Status.STARTED.getId() : QuestStatus.Status.NOT_STARTED.getId());
                List<AgentHuntSelectionRequest.ObjectiveDemand> demands =
                        AgentQuestPackDebtSnapshot.capture(pack, agent.character, gateway).demands();
                if (demands.isEmpty()) {
                    continue;
                }
                Set<Integer> covering = new LinkedHashSet<>();
                for (AgentHuntSelectionRequest.ObjectiveDemand demand : demands) {
                    INDEX.findObjective(demand.questId(), demand.objectiveId())
                            .map(AgentVictoriaQuestHuntIndex.Objective::candidates)
                            .orElseGet(() -> INDEX.findCandidatesForMobs(demand.sourceMobIds()))
                            .forEach(candidate -> covering.add(candidate.mapId()));
                }
                for (AgentVictoriaSharedQuestPackCatalog.Step step : pack.steps()) {
                    if (!"HUNT".equals(step.type()) || step.mapId() <= 0 || step.preferredMobIds().isEmpty()) {
                        continue;
                    }
                    AgentHuntObjectiveSpec spec = AgentHuntObjectiveCompiler.sharedQuestPack(
                            "shared:" + pack.packId(), pack, step, demands);
                    for (int origin : Set.of(pack.homeTownMapId(), step.mapId())) {
                        for (int level = Math.max(1, minLevel); level <= minLevel + 45; level += 3) {
                            agent.at(level, origin);
                            Sel selector = HEAD.create(EMPTY);
                            Optional<Pick> zero = selector.pick(new AgentHuntSelectionRequest(agent.entry,
                                    agent.character, spec, covering, AgentHuntSelectionRequest.Reason.NORMAL, 100L));
                            if (zero.isEmpty() || coverage(zero.get().mapId(), demands) > 0) {
                                continue;
                            }
                            int z = zero.get().mapId();
                            // The Agent stands on the zero-coverage map; the covering maps reopen.
                            agent.at(level, z);
                            Optional<Pick> after = selector.pick(new AgentHuntSelectionRequest(agent.entry,
                                    agent.character, spec, Set.of(), AgentHuntSelectionRequest.Reason.NORMAL, 200L));
                            Optional<Pick> fresh = HEAD.create(EMPTY).pick(new AgentHuntSelectionRequest(agent.entry,
                                    agent.character, spec, Set.of(), AgentHuntSelectionRequest.Reason.NORMAL, 200L));
                            checked++;
                            if (after.isPresent() && after.get().mapId() == z && fresh.isPresent()
                                    && coverage(fresh.get().mapId(), demands) > 0) {
                                kept++;
                                out.add(pack.packId() + "|active=" + active + "|step=" + step.mapId() + "|from "
                                        + origin + "|lv" + level + "|leased zero-coverage " + z + " (strongest "
                                        + strongest(z) + ") kept; fresh choice " + fresh.get().mapId() + " (strongest "
                                        + strongest(fresh.get().mapId()) + ", cov "
                                        + coverage(fresh.get().mapId(), demands) + "/" + demands.size() + ")");
                            }
                        }
                    }
                }
            }
        }
        Files.write(OUT.resolve("lease-zero-coverage.txt"), out);
        Files.writeString(OUT.resolve("lease-zero-coverage-tally.txt"),
                "checked=" + checked + " keptZeroCoverage=" + kept + "\n");
    }

    // ---------------------------------------------------------------------------------------------
    // Scan 4: lease pin. Lease a map with every in-window map failed, then reopen them.
    // ---------------------------------------------------------------------------------------------
    @Test
    void leasePin() throws Exception {
        List<String> out = new ArrayList<>();
        Map<Integer, Integer> pinnedByLevelsAbove = new TreeMap<>();
        int switched = 0;
        int agentId = 995000;
        for (Hunt hunt : hunts()) {
            Set<Integer> choiceMapIds = new LinkedHashSet<>();
            INDEX.findObjective(hunt.questId(), hunt.objective().objectiveId())
                    .map(AgentVictoriaQuestHuntIndex.Objective::candidates)
                    .orElseGet(() -> INDEX.findCandidatesForMobs(Set.copyOf(hunt.objective().sourceMobIds())))
                    .forEach(candidate -> choiceMapIds.add(candidate.mapId()));
            hunt.objective().huntMaps().forEach(map -> choiceMapIds.add(map.mapId()));
            for (int start : hunt.starts()) {
                for (int level = hunt.baseLevel(); level <= hunt.baseLevel() + 45; level += 3) {
                    int lv = level;
                    Set<Integer> inWindow = new LinkedHashSet<>();
                    for (int mapId : choiceMapIds) {
                        if (above(mapId, lv) == 0) {
                            inWindow.add(mapId);
                        }
                    }
                    if (inWindow.isEmpty()) {
                        continue;
                    }
                    Agent agent = p1(lv, start);
                    Sel selector = HEAD.create(EMPTY);
                    Optional<Pick> leased = selector.pick(request(agent, hunt, Shape.SCHEDULER, inWindow, 100L));
                    if (leased.isEmpty() || above(leased.get().mapId(), lv) == 0) {
                        continue;
                    }
                    int leasedMap = leased.get().mapId();
                    // The Agent walks to the leased map and stands on it; then the in-window maps reopen.
                    agent.at(lv, leasedMap);
                    Optional<Pick> after = selector.pick(request(agent, hunt, Shape.SCHEDULER, Set.of(), 200L));
                    Optional<Pick> fresh = HEAD.create(EMPTY).pick(
                            request(agent, hunt, Shape.SCHEDULER, Set.of(), 200L));
                    if (after.isPresent() && after.get().mapId() == leasedMap && fresh.isPresent()
                            && fresh.get().mapId() != leasedMap) {
                        int levelsAbove = above(leasedMap, lv) - above(fresh.get().mapId(), lv);
                        pinnedByLevelsAbove.merge(levelsAbove, 1, Integer::sum);
                        out.add("PINNED|" + hunt.questId() + "|" + hunt.objective().objectiveId() + "|lv" + lv
                                + "|leased " + leasedMap + " (" + above(leasedMap, lv) + " above) vs fresh "
                                + fresh.get().mapId() + " (" + above(fresh.get().mapId(), lv) + " above)");
                    } else {
                        switched++;
                    }
                }
            }
        }
        Files.write(OUT.resolve("lease-pin.txt"), out);
        Files.writeString(OUT.resolve("lease-pin-tally.txt"),
                "switched or same=" + switched + " pinnedByWindowLevelGap=" + pinnedByLevelsAbove + "\n");
    }

    // ---------------------------------------------------------------------------------------------
    // Scan 5: two Agents with the same hunt, sequential ticks, A nearly full (max-1 others).
    // ---------------------------------------------------------------------------------------------
    @Test
    void twoAgents() throws Exception {
        List<String> out = new ArrayList<>();
        Map<String, int[]> tally = new TreeMap<>();
        int agentId = 997000;
        for (Hunt hunt : hunts()) {
            for (int start : hunt.starts()) {
                for (int level = hunt.baseLevel(); level <= hunt.baseLevel() + 45; level += 9) {
                    Agent probe = p1(level, start);
                    Optional<Pick> fresh = HEAD.create(EMPTY).pick(
                            request(probe, hunt, Shape.SCHEDULER, Set.of(), 100L));
                    if (fresh.isEmpty() || fresh.get().mapId() == start) {
                        continue;
                    }
                    for (int othersOnA : List.of(fresh.get().maximumAgents() - 1, fresh.get().maximumAgents() - 2)) {
                        for (Map.Entry<String, SelFactory> version : Map.of("HEAD", HEAD, "OLD", OLD).entrySet()) {
                            Map<Integer, Integer> others = new HashMap<>();
                            others.put(fresh.get().mapId(), Math.max(0, othersOnA));
                            Agent x = p2(level, start);
                            Agent y = p3(level, start);
                            List<Agent> agents = List.of(x, y);
                            Sel selector = version.getValue().create(population(others, agents));
                            boolean overfill = false;
                            List<String> trail = new ArrayList<>();
                            long nowMs = 100L;
                            List<int[]> picks = new ArrayList<>();
                            for (int tick = 0; tick < 60; tick++) {
                                int[] tickPicks = new int[2];
                                for (int i = 0; i < 2; i++) {
                                    Agent agent = agents.get(i);
                                    Optional<Pick> pick = selector.pick(
                                            request(agent, hunt, Shape.SCHEDULER, Set.of(), nowMs));
                                    int target = pick.map(Pick::mapId).orElse(-1);
                                    tickPicks[i] = target;
                                    if (target > 0 && target != agent.map()) {
                                        Integer next = AgentVictoriaTrainingRouteCatalog.nextHop(agent.map(), target);
                                        if (next != null) {
                                            agent.at(level, next);
                                        }
                                    }
                                    if (target > 0 && target == agent.map()) {
                                        int total = others.getOrDefault(target, 0)
                                                + (int) agents.stream().filter(a -> a.map() == target).count();
                                        if (total > pick.get().maximumAgents()) {
                                            overfill = true;
                                        }
                                    }
                                }
                                picks.add(tickPicks);
                                trail.add(x.map() + "/" + y.map());
                                nowMs += 1_000L;
                            }
                            boolean settled = true;
                            for (int i = 0; i < 2; i++) {
                                Set<Integer> tail = new LinkedHashSet<>();
                                for (int t = 45; t < 60; t++) {
                                    tail.add(picks.get(t)[i]);
                                }
                                if (tail.size() != 1 || tail.iterator().next() != agents.get(i).map()) {
                                    settled = false;
                                }
                            }
                            String key = version.getKey() + ":others=max-" + (fresh.get().maximumAgents() - othersOnA);
                            int[] counts = tally.computeIfAbsent(key, ignored -> new int[3]);
                            counts[0]++;
                            if (!settled) {
                                counts[1]++;
                            }
                            if (overfill) {
                                counts[2]++;
                            }
                            if (!settled || overfill) {
                                out.add(key + "|" + hunt.questId() + "|" + hunt.objective().objectiveId() + "|from "
                                        + start + "|lv" + level + "|A=" + fresh.get().mapId() + "|settled=" + settled
                                        + "|overfill=" + overfill + "|trail=" + trail.subList(40, 60));
                            }
                        }
                    }
                }
            }
        }
        StringBuilder summary = new StringBuilder();
        tally.forEach((key, counts) -> summary.append(key).append(" runs=").append(counts[0])
                .append(" unsettled=").append(counts[1]).append(" overfill=").append(counts[2]).append('\n'));
        Files.write(OUT.resolve("two-agents-failures.txt"), out);
        Files.writeString(OUT.resolve("two-agents-tally.txt"), summary.toString());
    }
}
