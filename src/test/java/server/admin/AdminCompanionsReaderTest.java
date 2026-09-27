package server.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import server.living.roster.CompanionDriftReport;
import server.living.roster.CompanionDriftReport.AgentRef;
import server.living.roster.CompanionDriftReport.Companion;
import server.living.roster.CompanionDriftReport.Live;
import server.living.roster.CompanionDriftReport.Member;
import server.living.roster.CompanionDriftReport.Owner;
import server.living.roster.CompanionDriftReport.Row;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCompanionsReaderTest {
    @Test
    void reportRendersOwnerRosterLiveViewAndDrift() throws Exception {
        Owner owner = new Owner(1, "Ann", 1, 100000000, 50,
                List.of(new Member(1, "Ann", false), new Member(101, "Dorran", true)));
        CompanionDriftReport report = CompanionDriftReport.of(owner, 3, List.of(
                new Companion(new Row("dorran", "Dorran", RosterState.ACTIVE, Assignment.TRAIN, 104040000, 101),
                        new Live(true, 2, 100000000, 400, 800, 50, 1)),
                new Companion(new Row("mira", "Mira \"the quiet\"", RosterState.BENCHED, Assignment.FARM_MESO, 0, 102),
                        Live.unloaded(-1))),
                List.of(new AgentRef(101, "Dorran"), new AgentRef(303, "Spawned")));

        JsonNode json = new ObjectMapper().readTree(AdminCompanionsReader.json(report));

        JsonNode jsonOwner = json.get("owner");
        assertEquals(1, jsonOwner.get("id").asInt());
        assertEquals(50, jsonOwner.get("partyId").asInt());
        assertTrue(jsonOwner.get("party").get(1).get("bot").asBoolean());
        assertEquals(3, json.get("freeSlots").asInt());

        JsonNode dorran = json.get("roster").get(0);
        assertEquals("ACTIVE", dorran.get("state").asText());
        assertEquals("TRAIN", dorran.get("assignment").asText());
        assertEquals(101, dorran.get("characterId").asInt());
        JsonNode live = dorran.get("live");
        assertTrue(live.get("loaded").asBoolean());
        assertEquals(2, live.get("channel").asInt());
        assertEquals(800, live.get("maxHp").asInt());
        assertTrue(live.get("inOwnerParty").asBoolean());
        assertEquals(1, live.get("registeredLeaderId").asInt());

        JsonNode mira = json.get("roster").get(1);
        assertEquals("Mira \"the quiet\"", mira.get("name").asText());
        assertFalse(mira.get("live").get("loaded").asBoolean());
        assertFalse(mira.get("live").has("mapId"), "no live fields for an unloaded companion");

        assertEquals(303, json.get("unrostered").get(0).get("characterId").asInt());
        assertEquals("CHANNEL_MISMATCH", json.get("drift").get(0).get("code").asText());
        assertFalse(json.get("drift").get(0).get("info").asBoolean());
        assertEquals("UNROSTERED_AGENT", json.get("drift").get(1).get("code").asText());
        assertTrue(json.get("drift").get(1).get("personaId").isNull());
        assertEquals(1, json.get("driftCount").asInt());
        assertFalse(json.get("clean").asBoolean());
    }
}
