package server.agents.capabilities.trade;

import server.agents.runtime.AgentRuntimeEntry;

/**
 * Agent-owned adapter for AgentRuntimeEntry-backed manual trade invite state.
 */
public final class AgentManualTradeStateRuntime {
    private AgentManualTradeStateRuntime() {
    }

    public static Object tradeRef(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        return state == null ? null : state.tradeRef();
    }

    public static void beginTrade(AgentRuntimeEntry entry, Object trade, int timeoutMs) {
        AgentManualTradeState state = state(entry);
        if (state != null) {
            state.begin(trade, timeoutMs);
        }
    }

    /** A new window whose idle wait restarts on every new offer, open at most {@code maxOpenMs} in all. */
    public static void beginTrade(AgentRuntimeEntry entry, Object trade, int idleMs, int maxOpenMs) {
        AgentManualTradeState state = state(entry);
        if (state != null) {
            state.begin(trade, idleMs, maxOpenMs);
        }
    }

    /** What the partner holds in the window now: true when it changed since the last look. */
    public static boolean observeOffer(AgentRuntimeEntry entry, int items, int meso) {
        AgentManualTradeState state = state(entry);
        return state != null && state.observeOffer(items, meso);
    }

    public static void restartIdle(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        if (state != null) {
            state.restartIdle();
        }
    }

    public static int openLeftMs(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        return state == null ? 0 : state.openLeftMs();
    }

    public static void setOpenLeftMs(AgentRuntimeEntry entry, int openLeftMs) {
        AgentManualTradeState state = state(entry);
        if (state != null) {
            state.setOpenLeftMs(openLeftMs);
        }
    }

    public static int openedForMs(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        return state == null ? 0 : state.openedForMs();
    }

    public static int offerItems(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        return state == null ? 0 : state.offerItems();
    }

    public static int offerMeso(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        return state == null ? 0 : state.offerMeso();
    }

    public static int timeoutMs(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        return state == null ? 0 : state.timeoutMs();
    }

    public static void setTimeoutMs(AgentRuntimeEntry entry, int timeoutMs) {
        AgentManualTradeState state = state(entry);
        if (state != null) {
            state.setTimeoutMs(timeoutMs);
        }
    }

    public static int acceptDelayMs(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        return state == null ? 0 : state.acceptDelayMs();
    }

    public static void ensureAcceptDelay(AgentRuntimeEntry entry, int delayMs) {
        AgentManualTradeState state = state(entry);
        if (state != null) {
            state.ensureAcceptDelay(delayMs);
        }
    }

    public static void setAcceptDelayMs(AgentRuntimeEntry entry, int delayMs) {
        AgentManualTradeState state = state(entry);
        if (state != null) {
            state.setAcceptDelayMs(delayMs);
        }
    }

    /** server.Trade refused the Agent's trade for lack of bag space: its completion says no thanks for it. */
    public static void markRefusedForSpace(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        if (state != null) {
            state.markRefusedForSpace();
        }
    }

    /** True once after {@link #markRefusedForSpace}. */
    public static boolean takeRefusedForSpace(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        return state != null && state.takeRefusedForSpace();
    }

    public static void clear(AgentRuntimeEntry entry) {
        AgentManualTradeState state = state(entry);
        if (state != null) {
            state.clear();
        }
    }

    private static AgentManualTradeState state(AgentRuntimeEntry entry) {
        return entry == null ? null : entry.manualTradeState();
    }
}
