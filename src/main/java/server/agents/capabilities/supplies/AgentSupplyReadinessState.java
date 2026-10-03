package server.agents.capabilities.supplies;

import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.runtime.state.AgentCapabilityStateKey;

/**
 * The standing readiness duty's memory (AgentSupplyReadinessRuntime): when the Agent next looks at its potions, the
 * readiness trip under way, the last time it could not go (on which map, and how many mesos it lacked), and the
 * cooldown that keeps it from trying again too soon. Written on the Agent tick; a host only asks for an early look
 * ({@link #checkSoon}) and reads it.
 */
public final class AgentSupplyReadinessState {
    public static final AgentCapabilityStateKey<AgentSupplyReadinessState> STATE_KEY =
            new AgentCapabilityStateKey<>("supplies.readiness", AgentSupplyReadinessState.class,
                    AgentSupplyReadinessState::new);

    /** Why the last assessment did not set off. */
    public enum Verdict { CANNOT_AFFORD, NO_SPACE, NO_SUPPLIER }

    /** What keeps it from trying again, and what lifts it. */
    enum Cooldown {
        /** Until its purse grows by what it lacked. */
        CANNOT_AFFORD,
        /** A trip ran out of mesos: until its purse grows by what it still lacks. */
        SHORT_MESOS,
        /** Until a Use slot more is free. */
        NO_SPACE,
        /** A trip ran out of Use slots: until a Use slot more is free. */
        SHORT_SPACE,
        /** A trip was cut short: until its time is up. */
        INTERRUPTED
    }

    private long nextCheckAtMs;
    private AgentProcurementRequest request;
    private AgentSupplyReadiness.Activity activity;
    private int hops;
    private long etaAtMs;
    private Verdict verdict;
    private int verdictMapId;
    private int verdictMissingMesos;
    private long verdictAtMs;
    private Cooldown cooldown;
    private long cooldownUntilMs;
    private int cooldownMesosAt;
    private int cooldownMissingMesos;
    private int cooldownFreeUseSlotsAt;

    synchronized long nextCheckAtMs() {
        return nextCheckAtMs;
    }

    synchronized void scheduleNextCheck(long atMs) {
        nextCheckAtMs = atMs;
    }

    /** The next tick looks (a host nudged it). */
    synchronized void checkSoon() {
        nextCheckAtMs = 0L;
    }

    // ---- the readiness trip ----------------------------------------------------------------

    synchronized void offer(AgentProcurementRequest offered, AgentSupplyReadiness.Activity forActivity,
                            int tripHops, long etaAt) {
        request = offered;
        activity = forActivity;
        hops = tripHops;
        etaAtMs = etaAt;
    }

    /** The readiness request under way (or offered to begin), null when none. */
    synchronized AgentProcurementRequest request() {
        return request;
    }

    synchronized AgentSupplyReadiness.Activity activity() {
        return activity;
    }

    synchronized int hops() {
        return hops;
    }

    /** When the readiness trip {@code requestId} should be back; 0 when that is not the trip under way. */
    synchronized long etaAtMs(String requestId) {
        return request != null && request.requestId().equals(requestId) ? etaAtMs : 0L;
    }

    synchronized void clearRequest() {
        request = null;
        activity = null;
        hops = 0;
        etaAtMs = 0L;
    }

    // ---- the last verdict ------------------------------------------------------------------

    /** Records {@code next} on {@code mapId}; true when it differs from the last one (kind or map). */
    synchronized boolean verdict(Verdict next, int mapId, int missingMesos, long nowMs) {
        boolean changed = verdict != next || verdictMapId != mapId;
        verdict = next;
        verdictMapId = mapId;
        verdictMissingMesos = Math.max(0, missingMesos);
        verdictAtMs = nowMs;
        return changed;
    }

    /** The last verdict when it was found on {@code mapId}; else null. */
    synchronized Verdict verdictOn(int mapId) {
        return verdict != null && verdictMapId == mapId ? verdict : null;
    }

    synchronized Verdict verdict() {
        return verdict;
    }

    synchronized int verdictMissingMesos() {
        return verdictMissingMesos;
    }

    synchronized long verdictAtMs() {
        return verdictAtMs;
    }

    synchronized void clearVerdict() {
        verdict = null;
        verdictMapId = 0;
        verdictMissingMesos = 0;
        verdictAtMs = 0L;
    }

    // ---- the cooldown ----------------------------------------------------------------------

    synchronized void cooldown(Cooldown kind, long untilMs, int mesosAt, int missingMesos, int freeUseSlotsAt) {
        cooldown = kind;
        cooldownUntilMs = untilMs;
        cooldownMesosAt = Math.max(0, mesosAt);
        cooldownMissingMesos = Math.max(0, missingMesos);
        cooldownFreeUseSlotsAt = Math.max(0, freeUseSlotsAt);
    }

    /**
     * The cooldown in force now, or null once lifted (and then forgotten): a meso cooldown lifts when the purse has
     * grown by what it lacked, a space cooldown when more Use slots are free than when it was set, an interrupted
     * trip's when its time is up.
     */
    synchronized Cooldown cooldownInForce(long nowMs, int mesos, int freeUseSlots) {
        if (cooldown == null) {
            return null;
        }
        boolean lifted = switch (cooldown) {
            case CANNOT_AFFORD, SHORT_MESOS -> (long) mesos >= (long) cooldownMesosAt + cooldownMissingMesos;
            case NO_SPACE, SHORT_SPACE -> freeUseSlots > cooldownFreeUseSlotsAt;
            case INTERRUPTED -> nowMs >= cooldownUntilMs;
        };
        if (lifted) {
            cooldown = null;
        }
        return cooldown;
    }

    synchronized Cooldown cooldown() {
        return cooldown;
    }
}
