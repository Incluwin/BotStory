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
    /** Footwork while in reach: steps to another spot, and hops. */
    final AtomicInteger steps = new AtomicInteger();
    final AtomicInteger hops = new AtomicInteger();
    /** Potions its duel brain drank (the autopot's own drinks are not counted). */
    final AtomicInteger drinks = new AtomicInteger();
    /** Its duel brain's potion tries, the ones with no potion to pick, and the ones that left HP where it was. */
    final AtomicInteger drinkTries = new AtomicInteger();
    final AtomicInteger drinkNoPotion = new AtomicInteger();
    final AtomicInteger drinkNoGain = new AtomicInteger();
    /** The item of the last try that left HP where it was, and whether the duel refused it. */
    volatile String lastNoGain = "-";
    /** Swings sent with its strongest attack skill, its second, and any weaker one (the brain's own order). */
    final AtomicInteger swingsTop = new AtomicInteger();
    final AtomicInteger swingsSecond = new AtomicInteger();
    final AtomicInteger swingsWeaker = new AtomicInteger();
    /** The foe's distance (px across) summed over swings sent; the strongest skill's reach summed over thinks. */
    final AtomicLong swingDistancePx = new AtomicLong();
    final AtomicLong topReachPx = new AtomicLong();
    final AtomicInteger topReachLooks = new AtomicInteger();

    void swung(int rank, int distancePx) {
        (rank == 0 ? swingsTop : rank == 1 ? swingsSecond : swingsWeaker).incrementAndGet();
        swingDistancePx.addAndGet(Math.max(0, distancePx));
    }
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
    /** Per skill id the duel saw in this fighter's attack packets: {packets, raw rolled}. */
    final Map<Integer, long[]> skillRaw = new ConcurrentHashMap<>();

    void skillRolled(int skillId, long raw) {
        long[] tally = skillRaw.computeIfAbsent(skillId, s -> new long[2]);
        synchronized (tally) {
            tally[0]++;
            tally[1] += raw;
        }
    }

    /** The three skills seen most, as "id x packets @ raw a packet", or "-". */
    String topSkills() {
        String line = skillRaw.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(3)
                .map(e -> e.getKey() + "x" + e.getValue()[0] + "@" + perOne(e.getValue()[1], (int) e.getValue()[0]))
                .collect(java.util.stream.Collectors.joining(","));
        return line.isEmpty() ? "-" : line;
    }

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

    private static long perOne(long sum, int count) {
        return count <= 0 ? -1L : Math.round(sum / (double) count);
    }

    String summary() {
        return "thinks=" + thinks + " buffs=" + buffsCast + " sent=" + swingsSent + " deferred=" + swingsDeferred
                + " refused=" + swingsRefused + " noSkill=" + noSkillThinks + " walks=" + walks + " steps=" + steps + " hops=" + hops + " drinks=" + drinks + " drinkTries=" + drinkTries + " noPotion=" + drinkNoPotion + " noGain=" + drinkNoGain
                + " lastNoGain=" + lastNoGain + " top1=" + swingsTop + " top2=" + swingsSecond + " weaker="
                + swingsWeaker + " swingPx=" + perOne(swingDistancePx.get(), swingsTop.get() + swingsSecond.get()
                + swingsWeaker.get()) + " topReachPx=" + perOne(topReachPx.get(), topReachLooks.get())
                + " packets=" + packets
                + " whiffs=" + whiffs + " dodged=" + dodged + " hits=" + hits + " raw=" + rawRolled
                + " top=" + topRefusal() + " waited=" + topDeferral() + " skills=" + topSkills();
    }
}
