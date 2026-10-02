package server.living.roster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.living.store.LivingStore.PqRunPaceRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A run's pace as a pq_run_pace row keeps it, and back (PqPaceLedger): its spans, its members and its drawn content as
 * JSON. A row read back is the run the calibration learned from when it was written, so a restart learns the same.
 * <pre>
 * spans   [{"key", "fromMs", "toMs", "work", "complete"}], in the run's order
 * members [{"id", "companion", "run": {"deaths", "tappedOut", "stage1Attacks", "stage1Kills"},
 *           "power": {"name", "human", "leader", "level", "jobId", "dps": {mob: v}, "hit": {..}, "attackMs": {..},
 *                     "touchesToDown"}}]
 *         the members with power first, in their order, then the rest of the run's members, then the rest of the
 *         lineup; "run" and "power" only when the pace has them; "companion": a lineup companion (else a human)
 * drawn   {segment key: count}
 * </pre>
 */
final class PqPaceJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PqPaceJson() {
    }

    static String spans(AgentPqPace pace) {
        ArrayNode spans = JSON.createArrayNode();
        for (AgentPqPace.Span span : pace.spans()) {
            spans.addObject().put("key", span.key()).put("fromMs", span.fromMs()).put("toMs", span.toMs())
                    .put("work", span.work()).put("complete", span.complete());
        }
        return write(spans);
    }

    /** {@code lineup}: everyone the run went in with; {@code companions}: its lineup companions. */
    static String members(AgentPqPace pace, Set<Integer> lineup, Set<Integer> companions) {
        Map<Integer, ObjectNode> byId = new LinkedHashMap<>();
        for (AgentPqMemberPower power : pace.members()) {
            member(byId, power.characterId(), companions).set("power", power(power));
        }
        for (AgentPqPace.MemberRun run : pace.runs()) {
            member(byId, run.characterId(), companions).putObject("run").put("deaths", run.deaths())
                    .put("tappedOut", run.tappedOut()).put("stage1Attacks", run.stage1Attacks())
                    .put("stage1Kills", run.stage1Kills());
        }
        for (int id : lineup) {
            member(byId, id, companions);
        }
        ArrayNode members = JSON.createArrayNode();
        byId.values().forEach(members::add);
        return write(members);
    }

    static String drawn(AgentPqPace pace) {
        ObjectNode drawn = JSON.createObjectNode();
        pace.drawn().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> drawn.put(entry.getKey(), entry.getValue()));
        return write(drawn);
    }

    /**
     * The run a row records, as it was reported.
     *
     * @throws IllegalArgumentException when a JSON column cannot be read
     */
    static AgentPqPace pace(PqRunPaceRow row) {
        List<AgentPqPace.Span> spans = new ArrayList<>();
        for (JsonNode span : array(row.spans(), "spans")) {
            spans.add(new AgentPqPace.Span(span.path("key").asText(), span.path("fromMs").asLong(),
                    span.path("toMs").asLong(), span.path("work").asDouble(), span.path("complete").asBoolean()));
        }
        List<AgentPqMemberPower> members = new ArrayList<>();
        List<AgentPqPace.MemberRun> runs = new ArrayList<>();
        for (JsonNode member : array(row.members(), "members")) {
            int id = member.path("id").asInt();
            JsonNode power = member.get("power");
            if (power != null && power.isObject()) {
                members.add(new AgentPqMemberPower(id, power.path("name").asText(), power.path("human").asBoolean(),
                        power.path("leader").asBoolean(), power.path("level").asInt(), power.path("jobId").asInt(),
                        byMob(power.path("dps")), byMob(power.path("hit")), byMob(power.path("attackMs")),
                        power.path("touchesToDown").asDouble()));
            }
            JsonNode run = member.get("run");
            if (run != null && run.isObject()) {
                runs.add(new AgentPqPace.MemberRun(id, run.path("deaths").asInt(), run.path("tappedOut").asBoolean(),
                        run.path("stage1Attacks").asInt(), run.path("stage1Kills").asInt()));
            }
        }
        Map<String, Integer> drawn = new LinkedHashMap<>();
        JsonNode drawnNode = read(row.drawn(), "drawn");
        if (!drawnNode.isObject()) {
            throw new IllegalArgumentException("pq_run_pace drawn is not a JSON object");
        }
        for (Map.Entry<String, JsonNode> entry : drawnNode.properties()) {
            drawn.put(entry.getKey(), entry.getValue().asInt());
        }
        return new AgentPqPace(row.pqKey(), row.sessionId(), row.leaderId(), row.startedAtMs(), row.clearedAtMs(),
                spans, members, runs, drawn);
    }

    /** The lineup companions a row's members JSON names. */
    static Set<Integer> companions(String members) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (JsonNode member : array(members, "members")) {
            if (member.path("companion").asBoolean()) {
                ids.add(member.path("id").asInt());
            }
        }
        return ids;
    }

    private static ObjectNode member(Map<Integer, ObjectNode> byId, int id, Set<Integer> companions) {
        return byId.computeIfAbsent(id, key -> JSON.createObjectNode().put("id", key)
                .put("companion", companions.contains(key)));
    }

    private static ObjectNode power(AgentPqMemberPower power) {
        ObjectNode node = JSON.createObjectNode().put("name", power.name()).put("human", power.human())
                .put("leader", power.leader()).put("level", power.level()).put("jobId", power.jobId());
        node.set("dps", byMob(power.dpsByMob()));
        node.set("hit", byMob(power.hitByMob()));
        node.set("attackMs", byMob(power.attackMsByMob()));
        return node.put("touchesToDown", power.touchesToDown());
    }

    private static ObjectNode byMob(Map<Integer, Double> values) {
        ObjectNode node = JSON.createObjectNode();
        new TreeMap<>(values).forEach((mobId, value) -> node.put(Integer.toString(mobId), value));
        return node;
    }

    private static Map<Integer, Double> byMob(JsonNode node) {
        Map<Integer, Double> values = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            values.put(Integer.parseInt(entry.getKey()), entry.getValue().asDouble());
        }
        return values;
    }

    private static JsonNode array(String json, String column) {
        JsonNode node = read(json, column);
        if (!node.isArray()) {
            throw new IllegalArgumentException("pq_run_pace " + column + " is not a JSON array");
        }
        return node;
    }

    private static JsonNode read(String json, String column) {
        try {
            return JSON.readTree(json == null ? "" : json);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("pq_run_pace " + column + " is not JSON", failure);
        }
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("pace JSON could not be written", failure);
        }
    }
}
