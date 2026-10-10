package server.agents.capabilities.trade;

/**
 * One manual trade window's bookkeeping on its Agent: the join delay, the idle wait (restarted whenever the partner puts
 * something new in, {@link #observeOffer}), the time the window may still stay open whatever is added, and whether the
 * last completion was refused because the Agent's bag had no room ({@link #takeRefusedForSpace}).
 */
public final class AgentManualTradeState {
    private int acceptDelayMs = 0;
    private Object tradeRef = null;
    private int timeoutMs = 0;
    private int idleMs = 0;
    private int maxOpenMs = 0;
    private int openLeftMs = 0;
    private int offerItems = 0;
    private int offerMeso = 0;
    private volatile boolean refusedForSpace = false;

    public int acceptDelayMs() {
        return acceptDelayMs;
    }

    public void setAcceptDelayMs(int acceptDelayMs) {
        this.acceptDelayMs = acceptDelayMs;
    }

    public Object tradeRef() {
        return tradeRef;
    }

    public void setTradeRef(Object tradeRef) {
        this.tradeRef = tradeRef;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** The time the window may still stay open, however often something is put in. */
    public int openLeftMs() {
        return openLeftMs;
    }

    public void setOpenLeftMs(int openLeftMs) {
        this.openLeftMs = openLeftMs;
    }

    /** How long the window has been open by its clock. */
    public int openedForMs() {
        return Math.max(0, maxOpenMs - openLeftMs);
    }

    public int offerItems() {
        return offerItems;
    }

    public int offerMeso() {
        return offerMeso;
    }

    /** A window whose idle wait never restarts: it may stay open {@code timeoutMs} in all. */
    public void begin(Object trade, int timeoutMs) {
        begin(trade, timeoutMs, timeoutMs);
    }

    /**
     * A new window: the idle wait starts at {@code idleMs}, and the window may stay open {@code maxOpenMs} in all (never
     * less than one idle wait). Nothing offered yet.
     */
    public void begin(Object trade, int idleMs, int maxOpenMs) {
        this.acceptDelayMs = 0;
        this.tradeRef = trade;
        this.timeoutMs = idleMs;
        this.idleMs = idleMs;
        this.maxOpenMs = Math.max(idleMs, maxOpenMs);
        this.openLeftMs = this.maxOpenMs;
        this.offerItems = 0;
        this.offerMeso = 0;
    }

    /**
     * What the partner's side of the window holds now: true when it differs from the last look (an item or mesos put
     * in; a trade window never gives anything back before it closes).
     */
    public boolean observeOffer(int items, int meso) {
        if (items == offerItems && meso == offerMeso) {
            return false;
        }
        offerItems = items;
        offerMeso = meso;
        return true;
    }

    /** The idle wait starts over (something new was put in). */
    public void restartIdle() {
        timeoutMs = idleMs;
    }

    public void ensureAcceptDelay(int delayMs) {
        if (acceptDelayMs == 0) {
            acceptDelayMs = delayMs;
        }
    }

    /** The trade's completion was refused because the Agent's bag had no room (server.Trade's space check). */
    public void markRefusedForSpace() {
        refusedForSpace = true;
    }

    /** True once after {@link #markRefusedForSpace}: the trade the Agent just completed did not go through. */
    public boolean takeRefusedForSpace() {
        boolean refused = refusedForSpace;
        refusedForSpace = false;
        return refused;
    }

    public void clear() {
        acceptDelayMs = 0;
        tradeRef = null;
        timeoutMs = 0;
        idleMs = 0;
        maxOpenMs = 0;
        openLeftMs = 0;
        offerItems = 0;
        offerMeso = 0;
        refusedForSpace = false;
    }
}
