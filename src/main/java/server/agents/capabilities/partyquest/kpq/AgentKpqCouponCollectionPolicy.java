package server.agents.capabilities.partyquest.kpq;

/** Pure scheduling policy for Stage 1's map-wide coupon sweeps; a repick hands out every collector's coupon again. */
final class AgentKpqCouponCollectionPolicy {
    private AgentKpqCouponCollectionPolicy() {
    }

    static Decision decide(long nowMs,
                           long stageEnteredAtMs,
                           long sweepStartedAtMs,
                           long nextSweepAtMs,
                           int couponsOnGround,
                           int remainingPartyNeed,
                           long sweepIntervalMs,
                           long sweepMaximumMs) {
        long nextSweep = nextSweepAtMs > 0L
                ? nextSweepAtMs
                : stageEnteredAtMs + sweepIntervalMs;
        if (remainingPartyNeed <= 0) {
            return new Decision(false, false, 0L, nowMs + sweepIntervalMs);
        }
        long sweepStarted = sweepStartedAtMs;
        if (couponsOnGround <= 0) {
            if (sweepStarted > 0L || nowMs >= nextSweep) {
                nextSweep = nowMs + sweepIntervalMs;
            }
            return new Decision(false, false, 0L, nextSweep);
        }
        if (sweepStarted > 0L) {
            boolean repick = nowMs - sweepStarted >= sweepMaximumMs;
            return new Decision(true, repick,
                    repick ? nowMs : sweepStarted, nextSweep);
        }
        if (nowMs >= nextSweep) {
            return new Decision(true, true, nowMs, nextSweep);
        }
        return new Decision(false, false, 0L, nextSweep);
    }

    record Decision(boolean sweepActive,
                    boolean repick,
                    long sweepStartedAtMs,
                    long nextSweepAtMs) {
    }
}
