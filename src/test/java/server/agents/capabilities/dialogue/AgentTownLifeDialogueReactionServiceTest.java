package server.agents.capabilities.dialogue;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.townlife.AgentTownLifeEncounterEvent;
import server.agents.capabilities.townlife.AgentTownLifeEncounterState;
import server.agents.capabilities.townlife.AgentTownLifeArrivalEvent;
import server.agents.capabilities.townlife.AgentTownLifeVisitRequest;
import server.agents.events.AgentEvent;
import server.agents.events.BoundedAgentEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTownLifeDialogueReactionServiceTest {
    @Test
    void arrivalProducesOneObserverGatedTownIntention() {
        BoundedAgentEventBus bus = new BoundedAgentEventBus();
        AgentTownLifeDialogueReactionService listener = new AgentTownLifeDialogueReactionService(bus);
        List<AgentEvent> intents = new ArrayList<>();
        bus.subscribe(AgentDialogueIntentEvent.TYPE, intents::add);

        listener.onAgentEvent(new AgentTownLifeArrivalEvent(
                11, 1_000L, 104000000, "lith-harbor",
                AgentTownLifeVisitRequest.Purpose.LEISURE, "Shanks transfer", "Lith Harbor"));
        bus.drain(10);

        assertEquals(1, intents.size());
        AgentDialogueIntentEvent intent = (AgentDialogueIntentEvent) intents.getFirst();
        assertEquals(AgentTownLifeDialogueReactionService.ARRIVAL_INTENT, intent.intentKey());
        assertEquals(AgentDialogueAudience.NEARBY_REAL_PLAYER, intent.audience());
        assertEquals("townlife-arrival", intent.dedupeKey());
    }

    @Test
    void arrivalInKerningNeverTalksAboutLithHarborOrTheShip() {
        for (int variant = 0; variant < 4; variant++) {
            String line = AgentDialogueProjectionRuntime.render(new AgentDialogueIntentEvent(
                    11, 1_000L, AgentTownLifeDialogueReactionService.ARRIVAL_INTENT,
                    AgentDialogueAudience.NEARBY_REAL_PLAYER, "townlife-arrival", 0L,
                    Map.of("mapId", "103000000", "townName", "Kerning City",
                            "purpose", "LEISURE", "reason", "",
                            "variant", String.valueOf(variant))));

            assertFalse(line.isBlank());
            assertFalse(line.contains("Lith Harbor"), line);
            assertFalse(line.contains("Victoria Island"), line);
            assertFalse(line.toLowerCase().contains("ship"), line);
        }
    }

    @Test
    void arrivalIntentNamesTheTownAndMarksOnlyARealLithHarborShipArrival() {
        AgentDialogueIntentEvent kerning = arrivalIntent(new AgentTownLifeArrivalEvent(
                11, 1_000L, 103000000, "kerning-city",
                AgentTownLifeVisitRequest.Purpose.LEISURE, "", "Kerning City"));
        AgentDialogueIntentEvent lithByShip = arrivalIntent(new AgentTownLifeArrivalEvent(
                11, 1_000L, 104000000, "lith-harbor",
                AgentTownLifeVisitRequest.Purpose.LEISURE,
                AgentTownLifeVisitRequest.SHIP_ARRIVAL_REASON, "Lith Harbor"));
        AgentDialogueIntentEvent lithOnFoot = arrivalIntent(new AgentTownLifeArrivalEvent(
                11, 1_000L, 104000000, "lith-harbor",
                AgentTownLifeVisitRequest.Purpose.LEISURE, "", "Lith Harbor"));
        AgentDialogueIntentEvent shipReasonElsewhere = arrivalIntent(new AgentTownLifeArrivalEvent(
                11, 1_000L, 103000000, "kerning-city",
                AgentTownLifeVisitRequest.Purpose.LEISURE,
                AgentTownLifeVisitRequest.SHIP_ARRIVAL_REASON, "Kerning City"));

        assertEquals("Kerning City", kerning.parameters().get("townName"));
        assertEquals("false", kerning.parameters().get("shipArrival"));
        assertEquals("true", lithByShip.parameters().get("shipArrival"));
        assertEquals("false", lithOnFoot.parameters().get("shipArrival"));
        assertEquals("false", shipReasonElsewhere.parameters().get("shipArrival"));
        String kerningLine = AgentDialogueProjectionRuntime.render(kerning);
        assertFalse(kerningLine.contains("Lith Harbor"), kerningLine);
    }

    @Test
    void arrivalLinesNameTheCurrentTownAndKeepShipWordingForTheCrossing() {
        List<String> kerning = new ArrayList<>();
        List<String> lithByShip = new ArrayList<>();
        for (int variant = 0; variant < 4; variant++) {
            kerning.add(AgentDialogueProjectionRuntime.render(arrival(
                    "Kerning City", "false", variant)));
            lithByShip.add(AgentDialogueProjectionRuntime.render(arrival(
                    "Lith Harbor", "true", variant)));
        }

        assertTrue(kerning.stream().anyMatch(line -> line.contains("Kerning City")), kerning::toString);
        assertTrue(lithByShip.stream().anyMatch(line -> line.contains("ship")), lithByShip::toString);
        assertTrue(lithByShip.stream().anyMatch(line -> line.contains("Victoria Island")),
                lithByShip::toString);
        String unnamed = AgentDialogueProjectionRuntime.render(arrival("", "false", 0));
        assertFalse(unnamed.isBlank());
        assertFalse(unnamed.contains("Lith Harbor"), unnamed);
    }

    @Test
    void socialLinesDoNotAssumeTheHarbor() {
        for (int variant = 0; variant < 4; variant++) {
            String line = AgentDialogueProjectionRuntime.render(new AgentDialogueIntentEvent(
                    11, 1_000L, AgentTownLifeDialogueReactionService.SOCIAL_INTENT,
                    AgentDialogueAudience.NEARBY_REAL_PLAYER, "townlife-ambient", 0L,
                    Map.of("variant", String.valueOf(variant))));

            assertFalse(line.toLowerCase().contains("harbor"), line);
        }
    }

    @Test
    void onlyAStableSubsetOfInitiatorsProduceObserverGatedDialogueIntents() {
        BoundedAgentEventBus bus = new BoundedAgentEventBus();
        AgentTownLifeDialogueReactionService listener = new AgentTownLifeDialogueReactionService(bus);
        List<AgentEvent> intents = new ArrayList<>();
        bus.subscribe(AgentDialogueIntentEvent.TYPE, intents::add);

        for (int index = 0; index < 20 && intents.isEmpty(); index++) {
            listener.onAgentEvent(encounter("encounter-" + index,
                    AgentTownLifeEncounterState.Role.INITIATOR,
                    AgentTownLifeEncounterState.Phase.ACTIVE));
            bus.drain(10);
        }

        assertFalse(intents.isEmpty());
        AgentDialogueIntentEvent intent = (AgentDialogueIntentEvent) intents.getFirst();
        assertEquals(AgentDialogueAudience.NEARBY_REAL_PLAYER, intent.audience());
        assertEquals("townlife-ambient", intent.dedupeKey());
    }

    @Test
    void responderAndNonActivePhasesStaySilent() {
        BoundedAgentEventBus bus = new BoundedAgentEventBus();
        AgentTownLifeDialogueReactionService listener = new AgentTownLifeDialogueReactionService(bus);

        listener.onAgentEvent(encounter("responder", AgentTownLifeEncounterState.Role.RESPONDER,
                AgentTownLifeEncounterState.Phase.ACTIVE));
        listener.onAgentEvent(encounter("approaching", AgentTownLifeEncounterState.Role.INITIATOR,
                AgentTownLifeEncounterState.Phase.APPROACHING));

        assertEquals(0, bus.snapshot().queued());
    }

    private static AgentDialogueIntentEvent arrivalIntent(AgentTownLifeArrivalEvent arrival) {
        BoundedAgentEventBus bus = new BoundedAgentEventBus();
        List<AgentEvent> intents = new ArrayList<>();
        bus.subscribe(AgentDialogueIntentEvent.TYPE, intents::add);
        new AgentTownLifeDialogueReactionService(bus).onAgentEvent(arrival);
        bus.drain(10);
        assertEquals(1, intents.size());
        return (AgentDialogueIntentEvent) intents.getFirst();
    }

    private static AgentDialogueIntentEvent arrival(String townName, String shipArrival, int variant) {
        return new AgentDialogueIntentEvent(
                11, 1_000L, AgentTownLifeDialogueReactionService.ARRIVAL_INTENT,
                AgentDialogueAudience.NEARBY_REAL_PLAYER, "townlife-arrival", 0L,
                Map.of("townName", townName, "shipArrival", shipArrival,
                        "variant", String.valueOf(variant)));
    }

    private static AgentTownLifeEncounterEvent encounter(
            String id,
            AgentTownLifeEncounterState.Role role,
            AgentTownLifeEncounterState.Phase phase) {
        return new AgentTownLifeEncounterEvent(
                11, 1_000L, 104000000, id,
                AgentTownLifeEncounterState.Type.SOCIAL_CHAT, role, phase,
                22, 11, "central-benches", id);
    }
}
