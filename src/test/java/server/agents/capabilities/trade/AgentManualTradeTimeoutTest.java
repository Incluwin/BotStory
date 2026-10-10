package server.agents.capabilities.trade;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import server.Trade;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.trade.AgentManualTradeService.CloseReason;
import server.agents.capabilities.trade.AgentManualTradeService.TimeoutNotice;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * The companion's trade clock (live 2026-10-10: an owner opened "Let's trade", put a cap in at +22 s and pressed Trade
 * at +68 s, but the companion had closed the window at +60 s without a word). The idle wait restarts whenever the
 * partner puts an item or mesos in, a total cap still closes a window kept busy, and a window closed by the clock gets
 * one player-like line to a human it had joined and one INFO log line. The companion still waits for the owner's
 * Trade press: nothing here presses it.
 */
class AgentManualTradeTimeoutTest {
    private static final int IDLE_MS = 1_000;
    private static final int MAX_OPEN_MS = 3_000;
    private static final IntUnaryOperator TICK = remaining -> Math.max(0, remaining - 100);

    private final Character agent = character(4101, "Hadrik");
    private final Character owner = character(4102, "Ann");
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, owner, null);
    private final Object window = new Object();
    private final List<Item> offered = new ArrayList<>();
    private int offeredMeso;
    private AgentTradeWindow trade;
    private AgentTradeWindow ownerSide;

    @BeforeEach
    void openWindow() {
        ownerSide = mock(AgentTradeWindow.class);
        when(ownerSide.character()).thenReturn(owner);
        when(ownerSide.items()).thenAnswer(inv -> List.copyOf(offered));
        when(ownerSide.meso()).thenAnswer(inv -> offeredMeso);
        trade = mock(AgentTradeWindow.class);
        when(trade.identity()).thenReturn(window);
        when(trade.character()).thenReturn(agent);
        when(trade.partner()).thenReturn(ownerSide);
        when(trade.isFullTrade()).thenReturn(true);
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        return character;
    }

    private boolean tick(TimeoutNotice notice) {
        return AgentManualTradeService.beginOrTickTimeout(entry, agent, trade, IDLE_MS, MAX_OPEN_MS, TICK, notice);
    }

    /**
     * The tick (1 = the first after the window appeared) on which the Agent closes the window, or -1 within
     * {@code limit} ticks; {@code beforeTick} runs before each tick (the owner putting something in).
     */
    private int closingTick(int limit, IntConsumer beforeTick, TimeoutNotice notice) {
        assertFalse(tick(notice), "the window appears: the clock starts");
        for (int n = 1; n <= limit; n++) {
            beforeTick.accept(n);
            if (tick(notice)) {
                return n;
            }
        }
        return -1;
    }

    @Test
    void withNothingPutInItClosesAfterTheIdleWait() {
        try (MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            assertEquals(10, closingTick(100, n -> { }, TimeoutNotice.NONE));

            trades.verify(() -> Trade.cancelTrade(agent, Trade.TradeResult.NO_RESPONSE), times(1));
        }
    }

    @Test
    void anItemPutInRestartsTheWait() {
        try (MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            int closed = closingTick(100, n -> {
                if (n == 5) {
                    offered.add(new Item(1002000, (short) 1, (short) 1)); // a cap, at tick 5
                }
            }, TimeoutNotice.NONE);

            assertEquals(14, closed, "a whole idle wait from the tick the cap went in");
            trades.verify(() -> Trade.cancelTrade(agent, Trade.TradeResult.NO_RESPONSE), times(1));
        }
    }

    @Test
    void mesosPutInRestartTheWaitToo() {
        try (MockedStatic<Trade> ignored = mockStatic(Trade.class)) {
            int closed = closingTick(100, n -> {
                if (n == 8) {
                    offeredMeso = 5_000;
                }
            }, TimeoutNotice.NONE);

            assertEquals(17, closed);
        }
    }

    @Test
    void theTotalCapClosesAWindowKeptBusy() {
        AtomicReference<CloseReason> reason = new AtomicReference<>();
        try (MockedStatic<Trade> trades = mockStatic(Trade.class)) {
            int closed = closingTick(1_000, n -> offeredMeso++,
                    (closedAgent, closedTrade, why, openedForMs) -> reason.set(why));

            assertEquals(30, closed, "MAX_OPEN_MS / tick, however often something goes in");
            assertEquals(CloseReason.MAX_OPEN, reason.get());
            trades.verify(() -> Trade.cancelTrade(agent, Trade.TradeResult.NO_RESPONSE), times(1));
        }
    }

    @Test
    void closingByTheClockSaysOneLineToItsOwnerAndLogsIt() {
        List<String> said = new ArrayList<>();
        TimeoutNotice notice = AgentManualTradeService.speakingNotice(
                ignored -> false, AgentTradeDialogueService::manualTradeTimeoutReply, said::add);
        try (MockedStatic<Trade> ignored = mockStatic(Trade.class);
             TradeLogLines log = new TradeLogLines(AgentManualTradeService.class)) {
            assertEquals(10, closingTick(100, n -> { }, notice));
            assertFalse(tick(notice), "the next window starts a fresh clock, it says nothing");

            assertEquals(1, said.size(), said.toString());
            assertTrue(AgentDialogueCatalog.manualTradeTimeoutReplies().contains(said.get(0)), said.get(0));
            List<String> lines = log.startingWith("Agent Hadrik closed its trade with Ann by timeout");
            assertEquals(1, lines.size(), lines.toString());
            assertTrue(lines.get(0).contains("reason=IDLE"), lines.get(0));
        }
    }

    @Test
    void noLineToAnotherAgentNorForAnInviteItNeverJoined() {
        List<String> said = new ArrayList<>();
        try (MockedStatic<Trade> ignored = mockStatic(Trade.class);
             TradeLogLines log = new TradeLogLines(AgentManualTradeService.class)) {
            TimeoutNotice toAnAgent = AgentManualTradeService.speakingNotice(
                    other -> other == owner, AgentTradeDialogueService::manualTradeTimeoutReply, said::add);
            assertEquals(10, closingTick(100, n -> { }, toAnAgent));

            when(trade.isFullTrade()).thenReturn(false); // a stranger's invite it never took
            TimeoutNotice toAHuman = AgentManualTradeService.speakingNotice(
                    other -> false, AgentTradeDialogueService::manualTradeTimeoutReply, said::add);
            assertEquals(10, closingTick(100, n -> { }, toAHuman));

            assertEquals(List.of(), said);
            assertEquals(2, log.startingWith("Agent Hadrik closed its trade with Ann by timeout").size(),
                    "each close is still logged");
        }
    }

    @Test
    void anOwnerTradeLeftIdleClosesWithOneLineInItsChat() {
        Trade agentTrade = new Trade((byte) 1, agent);
        Trade ownerTrade = new Trade((byte) 0, owner);
        agentTrade.setPartner(ownerTrade);
        ownerTrade.setPartner(agentTrade);
        agentTrade.setFullTrade(true);
        ownerTrade.setFullTrade(true);
        AtomicReference<Trade> current = new AtomicReference<>(agentTrade);
        when(agent.getTrade()).thenAnswer(inv -> current.get());
        when(owner.getTrade()).thenReturn(ownerTrade);
        try (MockedStatic<Trade> trades = mockStatic(Trade.class);
             MockedStatic<AgentDialogueTransportRuntime> chat = mockStatic(AgentDialogueTransportRuntime.class)) {
            trades.when(() -> Trade.cancelTrade(agent, Trade.TradeResult.NO_RESPONSE))
                    .thenAnswer(inv -> {
                        current.set(null);
                        return null;
                    });
            int ticks = 0;
            while (current.get() != null && ticks < 10_000) {
                AgentManualTradeRuntimeService.tickManualTrade(entry, agent, owner, runtimeCallbacks(), lifecycle());
                ticks++;
            }
            for (int n = 0; n < 5; n++) {
                AgentManualTradeRuntimeService.tickManualTrade(entry, agent, owner, runtimeCallbacks(), lifecycle());
            }

            assertEquals(1 + AgentManualTradeService.idleTimeoutMs() / 100, ticks,
                    "the configured idle wait at 100 ms a tick");
            chat.verify(() -> AgentDialogueTransportRuntime.visibleSayNow(ArgumentMatchers.eq(entry),
                    ArgumentMatchers.argThat(line -> AgentDialogueCatalog.manualTradeTimeoutReplies().contains(line))),
                    times(1));
        }
    }

    @Test
    void theNewLinesSoundLikeAPlayer() {
        for (String line : AgentDialogueCatalog.manualTradeTimeoutReplies()) {
            assertEquals(line.toLowerCase(Locale.ROOT), line, "lower case: " + line);
            assertFalse(line.chars().anyMatch(java.lang.Character::isDigit), "no digits: " + line);
        }
    }

    @Test
    void theWaitsComeFromAgentEngineYaml() {
        assertEquals(60_000, AgentManualTradeService.idleTimeoutMs(), "the wait before this change");
        assertEquals(300_000, AgentManualTradeService.maxOpenMs());
    }

    private static AgentManualTradeRuntimeService.RuntimeCallbacks runtimeCallbacks() {
        return AgentManualTradeRuntimeService.RuntimeCallbacks.of(
                () -> false,
                remaining -> Math.max(0, remaining - 100),
                () -> 100,
                ignored -> false,
                (peerId, ownerId) -> false,
                () -> "?",
                (tradeAgent, tradeOwner) -> {
                });
    }

    private static AgentTradeLifecycleService.LifecycleCallbacks lifecycle() {
        return AgentTradeLifecycleService.LifecycleCallbacks.of(
                (lifecycleEntry, lifecycleAgent) -> {
                },
                (lifecycleEntry, lifecycleAgent) -> {
                },
                lifecycleEntry -> null,
                (lifecycleAgent, lifecycleOwner) -> {
                },
                (minMs, maxMs) -> minMs,
                () -> "thanks",
                () -> "free",
                () -> 100,
                () -> false);
    }
}
