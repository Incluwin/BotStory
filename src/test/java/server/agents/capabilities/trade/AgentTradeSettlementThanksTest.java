package server.agents.capabilities.trade;

import client.Character;
import client.inventory.Inventory;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Trade;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.inventory.AgentBagFullSpeech;
import server.agents.capabilities.inventory.AgentInventoryRuntime;
import server.agents.integration.cosmic.CosmicAgentTradeWindow;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A gift trade into a companion that server.Trade refuses (deploy review 2026-10-10): the companion said "ty!" with a
 * happy face after every refusal except the plain bag-space one, because its completion only knew about that one. Each
 * case here runs the real server.Trade checks between an owner who put a gift in and pressed Trade first, and the
 * companion's own completion (AgentTradeLifecycleService, through the real Cosmic trade gateway).
 */
class AgentTradeSettlementThanksTest {
    /** Protector Rock: a one-of-a-kind earring. */
    private static final int PROTECTOR_ROCK = 1032033;

    private final Character owner = character(1, "Bossfriend");
    private final Character companion = character(2, "Isolde");
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(companion, owner, null);
    private final List<String> said = new ArrayList<>();

    @AfterEach
    void uninstall() {
        AgentBagFullSpeech.install(null);
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        AtomicReference<Trade> trade = new AtomicReference<>();
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.getLevel()).thenReturn(70);
        when(character.getMeso()).thenReturn(5_000_000);
        when(character.canHoldMeso(anyInt())).thenReturn(true);
        when(character.canHoldUniques(anyList())).thenReturn(true);
        when(character.getTrade()).thenAnswer(invocation -> trade.get());
        doAnswer(invocation -> {
            trade.set(invocation.getArgument(0));
            return null;
        }).when(character).setTrade(any());
        return character;
    }

    /** The owner opens a trade with the companion, puts the gift in and presses Trade first, as an owner does. */
    private AgentTradeWindow ownerGivesAndPressesTrade(Item item, int meso) {
        Trade ownerTrade = new Trade((byte) 0, owner);
        Trade companionTrade = new Trade((byte) 1, companion);
        owner.setTrade(ownerTrade);
        companion.setTrade(companionTrade);
        ownerTrade.setPartner(companionTrade);
        companionTrade.setPartner(ownerTrade);
        ownerTrade.setFullTrade(true);
        companionTrade.setFullTrade(true);
        if (item != null) {
            ownerTrade.addItem(item);
        }
        if (meso > 0) {
            ownerTrade.setMeso(meso);
        }
        Trade.completeTrade(owner);
        return CosmicAgentTradeWindow.wrap(companionTrade);
    }

    /** The companion's tick: its partner pressed Trade, so it completes and reacts (AgentManualOwnerTradeService). */
    private void companionCompletes(AgentTradeWindow window) {
        AgentTradeLifecycleService.completeTradeAndReact(entry, companion, window,
                AgentTradeLifecycleService.LifecycleCallbacks.of(
                        (currentEntry, agent) -> { },
                        (currentEntry, agent) -> { },
                        currentEntry -> owner,
                        (agent, currentOwner) -> { },
                        (minMs, maxMs) -> 0L,
                        () -> "ty!",
                        () -> "np",
                        () -> 0,
                        () -> false));
    }

    /** Runs what the companion schedules at once and records what it says. */
    private MockedStatic<AgentInventoryRuntime> voice() {
        MockedStatic<AgentInventoryRuntime> inventory = mockStatic(AgentInventoryRuntime.class);
        inventory.when(() -> AgentInventoryRuntime.afterDelay(any(), anyLong(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(2).run();
                    return null;
                });
        inventory.when(() -> AgentInventoryRuntime.visibleSayNow(any(), anyString()))
                .thenAnswer(invocation -> said.add(invocation.getArgument(1)));
        return inventory;
    }

    @Test
    void aGiftStoppedByTheOneOfAKindLimitGetsNoThanksAndNoHappyFace() {
        // allow_multiple_one_of_a_kind_items false and the companion already holds one: Inventory's check fails, and
        // so does canHoldUniques, which picks the one-of-a-kind refusal over the bag-space one
        when(companion.canHoldUniques(anyList())).thenReturn(false);
        try (MockedStatic<Inventory> bags = mockStatic(Inventory.class);
             MockedStatic<InventoryManipulator> ignored = mockStatic(InventoryManipulator.class);
             MockedStatic<AgentInventoryRuntime> ignoredVoice = voice()) {
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(companion), anyList())).thenReturn(false);
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(owner), anyList())).thenReturn(true);

            companionCompletes(ownerGivesAndPressesTrade(new Item(PROTECTOR_ROCK, (short) 1, (short) 1), 0));

            verify(owner).message("Partner cannot hold more than one one-of-a-kind item at a time.");
            assertEquals(List.of(), said, "no \"ty!\" for a gift that never arrived");
            verify(companion, never()).changeFaceExpression(anyInt());
        }
    }

    @Test
    void mesosItCannotHoldGetNoThanks() {
        when(companion.canHoldMeso(anyInt())).thenReturn(false); // its mesos are at the cap
        try (MockedStatic<Inventory> bags = mockStatic(Inventory.class);
             MockedStatic<InventoryManipulator> ignored = mockStatic(InventoryManipulator.class);
             MockedStatic<AgentInventoryRuntime> ignoredVoice = voice()) {
            bags.when(() -> Inventory.checkSpotsAndOwnership(any(), anyList())).thenReturn(true);

            companionCompletes(ownerGivesAndPressesTrade(null, 50_000));

            verify(owner).message("Partner does not have enough meso inventory space to complete the trade.");
            assertEquals(List.of(), said);
            verify(companion, never()).changeFaceExpression(anyInt());
        }
    }

    @Test
    void mesosOverTheUnderLevelFifteenDailyLimitGetNoThanks() {
        when(companion.getLevel()).thenReturn(10);
        try (MockedStatic<Inventory> bags = mockStatic(Inventory.class);
             MockedStatic<InventoryManipulator> ignored = mockStatic(InventoryManipulator.class);
             MockedStatic<AgentInventoryRuntime> ignoredVoice = voice()) {
            bags.when(() -> Inventory.checkSpotsAndOwnership(any(), anyList())).thenReturn(true);

            companionCompletes(ownerGivesAndPressesTrade(null, 1_500_000));

            verify(companion, atLeastOnce()).getMesosTraded(); // the daily limit was checked
            verify(companion, never()).addMesosTraded(anyInt()); // and it stopped the trade
            assertEquals(List.of(), said);
            verify(companion, never()).changeFaceExpression(anyInt());
        }
    }

    @Test
    void aGiftItsBagHasNoRoomForGetsItsBagFullLineAndNoThanks() {
        AgentBagFullSpeech.install(agent -> agent == companion);
        try (MockedStatic<Inventory> bags = mockStatic(Inventory.class);
             MockedStatic<InventoryManipulator> ignored = mockStatic(InventoryManipulator.class);
             MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentInventoryRuntime> ignoredVoice = voice()) {
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(2)).thenReturn(entry);
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(companion), anyList())).thenReturn(false);
            bags.when(() -> Inventory.checkSpotsAndOwnership(eq(owner), anyList())).thenReturn(true);

            companionCompletes(ownerGivesAndPressesTrade(new Item(1002000, (short) 1, (short) 1), 0));

            assertEquals(1, said.size(), said.toString());
            assertTrue(AgentDialogueCatalog.tradeBagFullReplies().contains(said.getFirst()), said.getFirst());
            verify(companion, never()).changeFaceExpression(anyInt());
        }
    }
}
