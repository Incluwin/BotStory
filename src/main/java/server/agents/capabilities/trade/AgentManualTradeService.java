package server.agents.capabilities.trade;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentTradeGatewayRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Agent-owned manual trade state bookkeeping while the enclosing trade tick is
 * still hosted by the temporary bot inventory shell.
 *
 * <p>The Agent's trade clock: it waits {@code IDLE_TIMEOUT_MS} for something new in its partner's side of the window
 * and starts that wait over whenever an item or mesos go in, but keeps no window open longer than {@code MAX_OPEN_MS}
 * (both in agent-engine.yaml). A window closed by the clock gets a {@link TimeoutNotice}: one log line, and one line in
 * the Agent's chat to a human whose window it had joined ({@link #speakingNotice}). It never presses Trade itself
 * here: it completes only after its partner did, as before.</p>
 */
public final class AgentManualTradeService {
    private static final Logger log = LoggerFactory.getLogger(AgentManualTradeService.class);
    private static final String TUNING_PREFIX = "server.agents.capabilities.trade.AgentManualTradeService.";
    private static final Set<Integer> GREETING_SENT = ConcurrentHashMap.newKeySet();

    /** Why the Agent closed a trade window by its own clock. */
    public enum CloseReason {
        /** Nothing new was put in for a whole idle wait. */
        IDLE,
        /** The window stayed open as long as one window may, however often something went in. */
        MAX_OPEN
    }

    /** What follows once the Agent closed a trade window by its clock (a log line, a line in its chat). */
    @FunctionalInterface
    public interface TimeoutNotice {
        TimeoutNotice NONE = (agent, trade, reason, openedForMs) -> {
        };

        void closed(Character agent, AgentTradeWindow trade, CloseReason reason, int openedForMs);
    }

    private AgentManualTradeService() {
    }

    public static void clearAgentRuntimeState(int agentId) {
        GREETING_SENT.remove(agentId);
    }

    /** How long the Agent waits for something new in its partner's side of the window (agent-engine.yaml). */
    public static int idleTimeoutMs() {
        return tuningInt("IDLE_TIMEOUT_MS");
    }

    /** The longest the Agent keeps one window open, however often something goes in (agent-engine.yaml). */
    public static int maxOpenMs() {
        return tuningInt("MAX_OPEN_MS");
    }

    public static boolean beginOrTickTimeout(AgentRuntimeEntry entry,
                                             Character agent,
                                             AgentTradeWindow trade,
                                             IntUnaryOperator tickDown) {
        return beginOrTickTimeout(entry, agent, trade, idleTimeoutMs(), maxOpenMs(), tickDown, TimeoutNotice.NONE);
    }

    public static boolean beginOrTickTimeout(AgentRuntimeEntry entry,
                                             Character agent,
                                             AgentTradeWindow trade,
                                             int timeoutMs,
                                             IntUnaryOperator tickDown) {
        return beginOrTickTimeout(entry, agent, trade, timeoutMs, maxOpenMs(), tickDown, TimeoutNotice.NONE);
    }

    /**
     * One tick of the Agent's trade clock. A window it has not seen yet starts the clock: the idle wait at
     * {@code idleMs}, open at most {@code maxOpenMs} in all. Afterwards each tick first looks at its partner's side:
     * an item or mesos put in since the last look start the idle wait over; then both count down by {@code tickDown}.
     * When either runs out the Agent cancels the trade (NO_RESPONSE, as before), forgets the window and tells
     * {@code notice}; true then.
     */
    public static boolean beginOrTickTimeout(AgentRuntimeEntry entry,
                                             Character agent,
                                             AgentTradeWindow trade,
                                             int idleMs,
                                             int maxOpenMs,
                                             IntUnaryOperator tickDown,
                                             TimeoutNotice notice) {
        if (trade.identity() != AgentManualTradeStateRuntime.tradeRef(entry)) {
            clearGreeting(agent);
            AgentManualTradeStateRuntime.beginTrade(entry, trade.identity(), idleMs, maxOpenMs);
            observeOffer(entry, trade.partner());
            return false;
        }

        if (AgentManualTradeStateRuntime.timeoutMs(entry) <= 0) {
            return false;
        }

        if (observeOffer(entry, trade.partner())) {
            AgentManualTradeStateRuntime.restartIdle(entry);
        }
        AgentManualTradeStateRuntime.setTimeoutMs(
                entry,
                tickDown.applyAsInt(AgentManualTradeStateRuntime.timeoutMs(entry)));
        AgentManualTradeStateRuntime.setOpenLeftMs(
                entry,
                tickDown.applyAsInt(AgentManualTradeStateRuntime.openLeftMs(entry)));
        boolean idle = AgentManualTradeStateRuntime.timeoutMs(entry) <= 0;
        boolean capped = AgentManualTradeStateRuntime.openLeftMs(entry) <= 0;
        if (!idle && !capped) {
            return false;
        }

        int openedForMs = AgentManualTradeStateRuntime.openedForMs(entry);
        AgentTradeGatewayRuntime.trade().cancelNoResponse(agent);
        clearState(entry, agent);
        (notice == null ? TimeoutNotice.NONE : notice).closed(
                agent, trade, idle ? CloseReason.IDLE : CloseReason.MAX_OPEN, openedForMs);
        return true;
    }

    /** True when the partner's side holds more than at the last look (an item or mesos put in). */
    private static boolean observeOffer(AgentRuntimeEntry entry, AgentTradeWindow partner) {
        if (partner == null) {
            return false;
        }
        List<?> items = partner.items();
        return AgentManualTradeStateRuntime.observeOffer(entry, items == null ? 0 : items.size(), partner.meso());
    }

    /**
     * The notice the live tick gives: one INFO log line for every window closed by the clock, and one line from
     * {@code line} said through {@code say} when the Agent had joined the window and its partner is a human (not
     * {@code isAgent}): an invite it never took, or another Agent, gets no line.
     */
    public static TimeoutNotice speakingNotice(Predicate<Character> isAgent,
                                               Supplier<String> line,
                                               Consumer<String> say) {
        return (agent, trade, reason, openedForMs) -> {
            AgentTradeWindow partner = trade == null ? null : trade.partner();
            Character other = partner == null ? null : partner.character();
            boolean speaks = trade != null && trade.isFullTrade() && other != null && !isAgent.test(other);
            String said = speaks ? line.get() : null;
            log.info("Agent {} closed its trade with {} by timeout: reason={} openMs={} said={}",
                    name(agent), name(other), reason, openedForMs, said == null ? "nothing" : "'" + said + "'");
            if (said != null) {
                say.accept(said);
            }
        };
    }

    private static String name(Character character) {
        return character == null ? "nobody" : character.getName();
    }

    public static void clearState(AgentRuntimeEntry entry, Character agent) {
        clearGreeting(agent);
        AgentManualTradeStateRuntime.clear(entry);
    }

    public static void clearGreeting(Character agent) {
        if (agent != null) {
            GREETING_SENT.remove(agent.getId());
        }
    }

    public static void sendGreetingOnce(Character agent, AgentTradeWindow trade, Supplier<String> greeting) {
        if (agent != null && trade != null && GREETING_SENT.add(agent.getId())) {
            trade.chat(greeting.get());
        }
    }

    public static AgentTradeWindow acceptInviteWhenReady(AgentRuntimeEntry entry,
                                                         Character agent,
                                                         Character inviter,
                                                         AgentTradeWindow trade,
                                                         int delayMs,
                                                         IntUnaryOperator tickDown,
                                                         Function<Character, AgentTradeWindow> currentTradeWindow) {
        if (trade.number() != 1) {
            return trade;
        }

        AgentManualTradeStateRuntime.ensureAcceptDelay(entry, delayMs);
        AgentManualTradeStateRuntime.setAcceptDelayMs(
                entry,
                tickDown.applyAsInt(AgentManualTradeStateRuntime.acceptDelayMs(entry)));
        if (AgentManualTradeStateRuntime.acceptDelayMs(entry) > 0) {
            return trade;
        }

        AgentTradeGatewayRuntime.trade().visitTrade(agent, inviter);
        AgentTradeWindow joined = currentTradeWindow.apply(agent);
        if (joined == null || !joined.isFullTrade()) {
            return null;
        }
        return joined;
    }

    private static int tuningInt(String name) {
        return config.AgentTuning.intValue(TUNING_PREFIX + name);
    }
}
