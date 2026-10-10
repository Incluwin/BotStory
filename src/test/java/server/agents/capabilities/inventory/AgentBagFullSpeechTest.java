package server.agents.capabilities.inventory;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Full bags (live 2026-10-10: Hadrik, Casse, Jinx, HappyWolf, IceCookie and Tamsin had 96/96 Equip slots; a gift trade
 * failed with only the stock "Trade unsuccessful." and a drop was left in silence). An owned companion out with its
 * owner in their party says so: once per refused trade, and for a drop it would pick up at most once per
 * LOOT_LINE_EVERY_MS. A citizen, or any Agent the host does not name, says nothing.
 */
class AgentBagFullSpeechTest {
    @AfterEach
    void uninstall() {
        AgentBagFullSpeech.install(null);
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        return character;
    }

    /** Runs whatever the Agent schedules at once and records what it says. */
    private static MockedStatic<AgentInventoryRuntime> voice(List<String> said) {
        MockedStatic<AgentInventoryRuntime> inventory = mockStatic(AgentInventoryRuntime.class);
        inventory.when(() -> AgentInventoryRuntime.afterDelay(any(), anyLong(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(2).run();
                    return null;
                });
        inventory.when(() -> AgentInventoryRuntime.visibleSayNow(any(), ArgumentMatchers.anyString()))
                .thenAnswer(inv -> said.add(inv.getArgument(1)));
        return inventory;
    }

    @Test
    void aTradeRefusedForSpaceGetsOneLineFromAnOwnedCompanion() {
        // no thanks after it: the trade never settles (AgentTradeSettlementThanksTest)
        Character hadrik = character(5101, "Hadrik");
        AgentRuntimeEntry entry = new AgentRuntimeEntry(hadrik, null, null);
        AgentBagFullSpeech.install(agent -> agent == hadrik);
        List<String> said = new ArrayList<>();
        try (MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentInventoryRuntime> ignored = voice(said)) {
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(5101)).thenReturn(entry);

            AgentBagFullSpeech.tradeRefused(hadrik);

            assertEquals(1, said.size(), said.toString());
            assertTrue(AgentDialogueCatalog.tradeBagFullReplies().contains(said.get(0)), said.get(0));
        }
    }

    @Test
    void aCitizenWhoseTradeIsRefusedSaysNothing() {
        Character citizen = character(5102, "Marlo");
        AgentRuntimeEntry entry = new AgentRuntimeEntry(citizen, null, null);
        AgentBagFullSpeech.install(agent -> false);
        List<String> said = new ArrayList<>();
        try (MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentInventoryRuntime> ignored = voice(said)) {
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(5102)).thenReturn(entry);

            AgentBagFullSpeech.tradeRefused(citizen);

            assertEquals(List.of(), said);
        }
    }

    @Test
    void aHumanWhoseTradeIsRefusedIsLeftToTheStockMessage() {
        Character human = character(5103, "Ann");
        AgentBagFullSpeech.install(agent -> true);
        List<String> said = new ArrayList<>();
        try (MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentInventoryRuntime> ignored = voice(said)) {
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenReturn(null);

            AgentBagFullSpeech.tradeRefused(human);

            assertEquals(List.of(), said);
        }
    }

    @Test
    void theLootLineComesAtMostOncePerIntervalPerCompanion() {
        Character casse = character(5111, "Casse");
        Character jinx = character(5112, "Jinx");
        AgentRuntimeEntry casseEntry = new AgentRuntimeEntry(casse, null, null);
        AgentRuntimeEntry jinxEntry = new AgentRuntimeEntry(jinx, null, null);
        AgentBagFullSpeech.install(agent -> true);
        long every = AgentBagFullSpeech.lootLineEveryMs();
        long t0 = 10_000_000L;
        List<String> said = new ArrayList<>();
        try (MockedStatic<AgentInventoryRuntime> ignored = voice(said)) {
            assertTrue(AgentBagFullSpeech.lootLeft(casseEntry, casse, t0, () -> true));
            assertFalse(AgentBagFullSpeech.lootLeft(casseEntry, casse, t0 + 1_000L, () -> true),
                    "a second skip within the interval");
            assertFalse(AgentBagFullSpeech.lootLeft(casseEntry, casse, t0 + every - 1, () -> true));
            assertTrue(AgentBagFullSpeech.lootLeft(jinxEntry, jinx, t0 + 1_000L, () -> true),
                    "each companion keeps its own interval");
            assertTrue(AgentBagFullSpeech.lootLeft(casseEntry, casse, t0 + every, () -> true),
                    "after the interval it says it again");

            assertEquals(3, said.size(), said.toString());
            said.forEach(line -> assertTrue(AgentDialogueCatalog.lootBagFullReplies().contains(line), line));
        }
    }

    @Test
    void aCitizenNeverMentionsADropItLeaves() {
        Character citizen = character(5113, "Marlo");
        AgentRuntimeEntry entry = new AgentRuntimeEntry(citizen, null, null);
        AgentBagFullSpeech.install(agent -> false);
        AtomicBoolean asked = new AtomicBoolean();
        List<String> said = new ArrayList<>();
        try (MockedStatic<AgentInventoryRuntime> ignored = voice(said)) {
            assertFalse(AgentBagFullSpeech.lootLeft(entry, citizen, 10_000_000L, () -> {
                asked.set(true);
                return true;
            }));

            assertEquals(List.of(), said);
            assertFalse(asked.get(), "the loot rules are not even asked for an Agent that never says it");
        }
    }

    @Test
    void withNoRuleInstalledNobodySpeaks() {
        Character agent = character(5114, "Tamsin");
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        List<String> said = new ArrayList<>();
        try (MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentInventoryRuntime> inventory = voice(said)) {
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(5114)).thenReturn(entry);

            assertFalse(AgentBagFullSpeech.lootLeft(entry, agent, 10_000_000L, () -> true));
            AgentBagFullSpeech.tradeRefused(agent);

            assertEquals(List.of(), said);
            inventory.verify(() -> AgentInventoryRuntime.visibleSayNow(any(), any()), never());
        }
    }

    @Test
    void aDropItWouldNotPickUpAnywayIsNotMentionedAndKeepsTheLineForLater() {
        Character iceCookie = character(5115, "IceCookie");
        AgentRuntimeEntry entry = new AgentRuntimeEntry(iceCookie, null, null);
        AgentBagFullSpeech.install(agent -> true);
        List<String> said = new ArrayList<>();
        try (MockedStatic<AgentInventoryRuntime> inventory = voice(said)) {
            assertFalse(AgentBagFullSpeech.lootLeft(entry, iceCookie, 10_000_000L, () -> false),
                    "its loot setting leaves that drop anyway");
            assertTrue(AgentBagFullSpeech.lootLeft(entry, iceCookie, 10_000_001L, () -> true));

            inventory.verify(() -> AgentInventoryRuntime.visibleSayNow(any(), any()), times(1));
        }
    }

    @Test
    void theNewLinesSoundLikeAPlayerAndTheIntervalComesFromAgentEngineYaml() {
        for (String line : Set.copyOf(concat(AgentDialogueCatalog.tradeBagFullReplies(),
                AgentDialogueCatalog.lootBagFullReplies()))) {
            assertEquals(line.toLowerCase(Locale.ROOT), line, "lower case: " + line);
            assertFalse(line.chars().anyMatch(java.lang.Character::isDigit), "no digits: " + line);
        }
        assertEquals(1_800_000L, AgentBagFullSpeech.lootLineEveryMs(), "30 minutes");
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
