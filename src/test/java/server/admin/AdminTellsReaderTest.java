package server.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.behavior.AgentTellProbe;
import server.living.audit.TellAudit;
import server.living.audit.TellAuditTestAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /runtime/tells and /runtime/tells/history through the admin port's router. */
class AdminTellsReaderTest {
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void tearDown() {
        TellAudit.uninstall();
    }

    @Test
    void withTheAuditOffTheEndpointSaysSo() throws Exception {
        JsonNode body = json.readTree(AdminControlPort.route("GET", "/runtime/tells", null, ""));
        assertTrue(body.get("ok").asBoolean());
        assertFalse(body.get("enabled").asBoolean());
    }

    @Test
    void theEndpointShowsEachMeasureWithItsBandAndVerdict() throws Exception {
        TellAuditTestAccess.startNow("abc123def");
        for (int bot = 1; bot <= 4; bot++) {
            for (int i = 0; i < 12; i++) {
                AgentTellProbe.combatReaction(bot, 100); // every reaction after the same 100 ms
            }
        }

        JsonNode body = json.readTree(AdminControlPort.route("GET", "/runtime/tells", null, ""));

        assertTrue(body.get("enabled").asBoolean());
        assertEquals("abc123def", body.get("build").asText());
        JsonNode reaction = metric(body, "reaction.combat.cv");
        assertEquals("rc.cv", reaction.get("code").asText());
        assertEquals(0.0, reaction.get("value").asDouble(), 1e-9);
        assertEquals(4, reaction.get("n").asInt(), "four bots");
        assertEquals("RED", reaction.get("verdict").asText());
        assertEquals(0.25, reaction.get("band").get("red").asDouble(), 1e-9);
        assertTrue(reaction.get("band").get("higherIsHuman").asBoolean());
        JsonNode fastest = metric(body, "reaction.combat.p5");
        assertEquals("RED", fastest.get("verdict").asText());
        assertEquals(100, fastest.get("detail").get("min").asInt());
        JsonNode potion = metric(body, "potion.point");
        assertEquals("NO_DATA", potion.get("verdict").asText());
        assertTrue(metric(body, "route.repeat").get("value").isNull(), "not measured yet: null, not NaN");
        assertFalse(metric(body, "presence.session_minutes").has("band"), "information only");
        assertEquals(2, body.get("red").asInt());
        assertTrue(body.get("line").asText().startsWith("b=abc123def "));
        assertTrue(body.get("lastFold").isNull(), "no hour is up yet");
        assertTrue(body.get("deaths").isArray());
    }

    @Test
    void withoutTheLivingWorldTheHistoryIsEmpty() throws Exception {
        JsonNode body = json.readTree(AdminControlPort.route("GET", "/runtime/tells/history", null, ""));
        assertTrue(body.get("ok").asBoolean());
        assertEquals(0, body.get("rows").size());
    }

    private static JsonNode metric(JsonNode body, String id) {
        for (JsonNode metric : body.get("metrics")) {
            if (metric.get("id").asText().equals(id)) {
                return metric;
            }
        }
        throw new AssertionError("no metric " + id);
    }
}
