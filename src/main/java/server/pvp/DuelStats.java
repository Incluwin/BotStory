package server.pvp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What one fighter did in one duel (docs/PVP_PLAN.md V2): the bot's choices (thinks, buffs, swings sent, deferred,
 * refused and why) and what its attacks met (packets, whiffs, dodges, hits, raw damage rolled). The brain counts on the
 * Agent's thread and the field under its lock, so every counter is atomic.
 */
public final class DuelStats {
    final AtomicInteger thinks = new AtomicInteger();
    final AtomicInteger buffsCast = new AtomicInteger();
    final AtomicInteger swingsSent = new AtomicInteger();
    final AtomicInteger swingsDeferred = new AtomicInteger();
    final AtomicInteger swingsRefused = new AtomicInteger();
    final AtomicInteger noSkillThinks = new AtomicInteger();
    final AtomicInteger walks = new AtomicInteger();
    final AtomicInteger packets = new AtomicInteger();
    final AtomicInteger whiffs = new AtomicInteger();
    final AtomicInteger dodged = new AtomicInteger();
    final AtomicInteger hits = new AtomicInteger();
    /** Raw damage rolled on every victim an attack met, before the PvP scaling and before the dodge roll. */
    final AtomicLong rawRolled = new AtomicLong();
    final AtomicLong firstPacketMs = new AtomicLong();
    final Map<String, AtomicInteger> refusals = new ConcurrentHashMap<>();
    /** Why swings waited (the attack lock, the skill's cost or cooldown, the ground), counted apart from refusals. */
    final Map<String, AtomicInteger> deferrals = new ConcurrentHashMap<>();

    void refused(String reason) {
        count(refusals, reason);
    }

    void deferred(String reason) {
        swingsDeferred.incrementAndGet();
        count(deferrals, reason);
    }

    private static void count(Map<String, AtomicInteger> into, String reason) {
        into.computeIfAbsent(reason == null || reason.isBlank() ? "unknown" : reason, r -> new AtomicInteger())
                .incrementAndGet();
    }

    void packet(long nowMs) {
        packets.incrementAndGet();
        firstPacketMs.compareAndSet(0L, nowMs);
    }

    /** Seconds from this fighter's first attack packet to {@code endMs}; 0 when it never attacked. */
    double engagedSeconds(long endMs) {
        long first = firstPacketMs.get();
        return first <= 0L ? 0d : Math.max(0L, endMs - first) / 1000d;
    }

    /** The commonest refusal reason, or "-". */
    String topRefusal() {
        return top(refusals);
    }

    /** The commonest reason a swing waited, or "-". */
    String topDeferral() {
        return top(deferrals);
    }

    private static String top(Map<String, AtomicInteger> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue((a, b) -> Integer.compare(a.get(), b.get())))
                .map(e -> e.getKey() + "x" + e.getValue().get())
                .orElse("-");
    }

    String summary() {
        return "thinks=" + thinks + " buffs=" + buffsCast + " sent=" + swingsSent + " deferred=" + swingsDeferred
                + " refused=" + swingsRefused + " noSkill=" + noSkillThinks + " walks=" + walks + " packets=" + packets
                + " whiffs=" + whiffs + " dodged=" + dodged + " hits=" + hits + " raw=" + rawRolled
                + " top=" + topRefusal() + " waited=" + topDeferral();
    }
}
