package server.agents.capabilities.dialogue;

import org.junit.jupiter.api.Test;
import server.agents.events.AgentEventBus;
import server.agents.progression.events.AgentProgressionDialogueReactionService;
import server.agents.progression.events.AgentQuestProgressDialogueReactionService;
import server.agents.resources.events.AgentInventoryThresholdChangedEvent;
import server.agents.resources.events.AgentResourceDialogueReactionService;
import server.agents.operations.events.AgentOperationalDialogueReactionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDialogueProjectionRuntimeTest {
    @Test
    void suppressesInternalCombatPostureChatEverywhere() {
        AgentDialogueIntentEvent posture = new AgentDialogueIntentEvent(
                1, 1L, AgentFieldNarrationService.POSTURE_INTENT,
                AgentDialogueAudience.NEARBY_REAL_PLAYER, "posture", 0L,
                java.util.Map.of("phase", "KITING"));

        assertFalse(AgentDialogueProjectionRuntime.shouldProject(posture, true));
        assertFalse(AgentDialogueProjectionRuntime.shouldProject(posture, false));
    }

    @Test
    void suppressesGenericDialogueDuringAnActiveHpqSession() {
        AgentDialogueIntentEvent level = new AgentDialogueIntentEvent(
                1, 1L, AgentProgressionDialogueReactionService.LEVEL_INTENT,
                AgentDialogueAudience.NEARBY_REAL_PLAYER, "level", 0L,
                Map.of("level", "30"));

        assertFalse(AgentDialogueProjectionRuntime.shouldProject(level, true));
        assertTrue(AgentDialogueProjectionRuntime.shouldProject(level, false));
    }

    @Test
    void rendersSupportedSupplyIntentFromStructuredParameters() {
        AgentDialogueIntentEvent intent = new AgentDialogueIntentEvent(
                1,
                100L,
                AgentSupplyDialogueReactionService.INTENT_KEY,
                AgentDialogueAudience.NEARBY_REAL_PLAYER,
                "supply:HP_POTION",
                1_000L,
                Map.of("category", "HP_POTION", "urgency", "CRITICAL"));

        assertFalse(AgentDialogueProjectionRuntime.render(intent).isBlank());
    }

    @Test
    void ignoresMalformedOrUnknownIntentInsteadOfProjectingRawData() {
        AgentDialogueIntentEvent malformed = new AgentDialogueIntentEvent(
                1,
                100L,
                AgentSupplyDialogueReactionService.INTENT_KEY,
                AgentDialogueAudience.NEARBY_REAL_PLAYER,
                "malformed",
                1_000L,
                Map.of("category", "UNKNOWN", "urgency", "CRITICAL"));

        assertTrue(AgentDialogueProjectionRuntime.render(malformed).isBlank());
    }

    @Test
    void rendersProgressionIntentsWithoutExposingTechnicalContext() {
        AgentDialogueIntentEvent level = new AgentDialogueIntentEvent(
                1, 100L, AgentProgressionDialogueReactionService.LEVEL_INTENT,
                AgentDialogueAudience.NEARBY_REAL_PLAYER, "level", 1_000L,
                Map.of("level", "15"));
        AgentDialogueIntentEvent quest = new AgentDialogueIntentEvent(
                1, 100L, AgentProgressionDialogueReactionService.QUEST_INTENT,
                AgentDialogueAudience.NEARBY_REAL_PLAYER, "quest", 1_000L,
                Map.of("questId", "1001"));

        assertTrue(AgentDialogueProjectionRuntime.render(level).contains("15"));
        assertEquals("quest complete!", AgentDialogueProjectionRuntime.render(quest));
    }

    @Test
    void rendersQuestProgressMilestonesWithCounts() {
        AgentDialogueIntentEvent halfway = new AgentDialogueIntentEvent(
                1, 100L, AgentQuestProgressDialogueReactionService.INTENT_KEY,
                AgentDialogueAudience.NEARBY_REAL_PLAYER, "quest-progress", 1_000L,
                Map.of("targetId", "1210100", "currentCount", "15",
                        "targetName", "Ribbon Pig", "requiredCount", "30",
                        "milestonePercent", "50"));
        AgentDialogueIntentEvent nearlyDone = new AgentDialogueIntentEvent(
                1, 100L, AgentQuestProgressDialogueReactionService.INTENT_KEY,
                AgentDialogueAudience.NEARBY_REAL_PLAYER, "quest-progress", 1_000L,
                Map.of("targetId", "4000004", "currentCount", "27",
                        "targetName", "Squishy Liquid", "requiredCount", "30",
                        "milestonePercent", "90"));

        assertEquals("Quest progress: 15/30 Ribbon Pig - halfway there.",
                AgentDialogueProjectionRuntime.render(halfway));
        assertEquals("Quest progress: 27/30 Squishy Liquid - almost done.",
                AgentDialogueProjectionRuntime.render(nearlyDone));
    }

    @Test
    void rendersResourceIntentsWithoutExposingTechnicalContext() {
        AgentDialogueIntentEvent inventory = new AgentDialogueIntentEvent(
                1, 100L, AgentResourceDialogueReactionService.INVENTORY_FULL_INTENT,
                AgentDialogueAudience.NEARBY_REAL_PLAYER, "inventory", 1_000L,
                Map.of("inventoryType", "USE"));
        AgentDialogueIntentEvent scroll = new AgentDialogueIntentEvent(
                1, 100L, AgentResourceDialogueReactionService.SCROLL_INTENT,
                AgentDialogueAudience.NEARBY_REAL_PLAYER, "scroll", 1_000L,
                Map.of("result", "SUCCESS"));

        String bagFull = AgentDialogueProjectionRuntime.render(inventory);
        assertTrue(AgentDialogueCatalog.nearbyBagFullReplies().contains(bagFull),
                bagFull + " (was \"use inventory is full!\" until 2026-10-10)");
        assertEquals("the scroll worked!", AgentDialogueProjectionRuntime.render(scroll));
    }

    @Test
    void anAgentsFullBagIsSaidInAPlayersWordsAtMostOncePerCooldown() {
        // deploy review 2026-10-10: "equip inventory is full!" read like a system notice. An Agent that is not an owned
        // companion out with its owner still tells nearby players, in a player's words, at most once per
        // INVENTORY_COOLDOWN_MS (agent-engine.yaml) for each bag
        AgentEventBus bus = mock(AgentEventBus.class);
        List<AgentDialogueIntentEvent> intents = new ArrayList<>();
        when(bus.publish(any(), any())).thenAnswer(inv -> intents.add(inv.getArgument(0)));
        AgentResourceDialogueReactionService reaction = new AgentResourceDialogueReactionService(bus, () -> false);
        List<String> said = new ArrayList<>();
        AgentDialogueProjectionService projection = new AgentDialogueProjectionService(
                (agentId, audience) -> true, intent -> said.add(AgentDialogueProjectionRuntime.render(intent)));

        for (long atMs : new long[]{1_000L, 2_000L, 61_000L}) {
            reaction.onAgentEvent(new AgentInventoryThresholdChangedEvent(7, atMs, "EQUIP", 0, 96, "FULL", ""));
        }
        intents.forEach(projection::onAgentEvent);

        assertEquals(2, said.size(), "once per cooldown: " + said);
        assertTrue(AgentDialogueCatalog.nearbyBagFullReplies().containsAll(said), said.toString());
        for (String line : AgentDialogueCatalog.nearbyBagFullReplies()) {
            assertEquals(line.toLowerCase(Locale.ROOT), line, line);
            assertFalse(line.contains("inventory"), line);
            assertFalse(line.chars().anyMatch(java.lang.Character::isDigit), line);
        }
    }

    @Test
    void rendersOperationalLifeStateIntent() {
        AgentDialogueIntentEvent respawn = new AgentDialogueIntentEvent(
                1, 100L, AgentOperationalDialogueReactionService.LIFE_STATE_INTENT,
                AgentDialogueAudience.NEARBY_REAL_PLAYER, "respawn", 1_000L,
                Map.of("state", "ALIVE"));

        assertEquals("back!", AgentDialogueProjectionRuntime.render(respawn));
    }
}
