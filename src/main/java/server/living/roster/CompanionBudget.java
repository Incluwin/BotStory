package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admission for every companion load (docs/BOT_MANAGEMENT_PLAN.md, "CompanionBudget"). Always on and
 * independent of the Agent load shedding. Budget v1 comes from the P0 measurement: companions per
 * online human, live bench bots per owner and server-wide, one expedition burst, a registered-Agent
 * ceiling, and a per-owner gap between two loads. {@link #decide} is the pure rule.
 */
public final class CompanionBudget {
    public enum Kind { COMPANION, BENCH_LIVE, EXPEDITION }

    /** ALLOW, or DENY with a reason for the event log and the companion's line. */
    public record Admission(boolean allowed, String reason) {
        public static final Admission ALLOW = new Admission(true, "");

        public static Admission deny(String reason) {
            return new Admission(false, reason);
        }
    }

    /** What is loaded right now, as one admission sees it. */
    record Usage(int agents, int ownerCompanions, int benchLive, int ownerBenchLive, int expedition) { }

    private final LivingWorld world;
    /** owner character id -> time of its last admitted companion or bench load. */
    private final Map<Integer, Long> lastLoadAtMs = new ConcurrentHashMap<>();

    public CompanionBudget(LivingWorld world) {
        this.world = world;
    }

    /** Checks the pools and, when they have room, takes the owner's load slot (EXPEDITION is a burst and is not spaced). */
    public Admission admit(Kind kind, Character owner) {
        return admit(kind, owner, System.currentTimeMillis());
    }

    public Admission admit(Kind kind, Character owner, long nowMs) {
        if (owner == null) {
            return Admission.deny("no owner");
        }
        Admission admission = decide(kind, usage(owner.getId()), world.config());
        if (!admission.allowed()) {
            world.store().event(owner.getId(), null, "companion.budget_denied", kind + ": " + admission.reason());
            return admission;
        }
        if (kind != Kind.EXPEDITION && !tryTakeLoadSlot(owner.getId(), nowMs)) {
            return Admission.deny("one load every " + world.config().COMPANION_SPAWN_INTERVAL_MS + " ms");
        }
        return Admission.ALLOW;
    }

    /** Ms until this owner's next companion or bench load can be admitted (0 = now), for spacing a batch. */
    public long loadDelayMs(Character owner, long nowMs) {
        return owner == null ? 0L : waitMs(lastLoadAtMs.get(owner.getId()), nowMs, world.config().COMPANION_SPAWN_INTERVAL_MS);
    }

    Usage usage(int ownerId) {
        BenchSessions bench = world.benchSessions();
        return new Usage(AgentRuntimeRegistry.activeAgentCount(), AgentRuntimeRegistry.activeAgentCountForLeader(ownerId),
                bench.liveCount(), bench.liveCountFor(ownerId), world.leases().count(CompanionLeases.Kind.EXPEDITION));
    }

    static Admission decide(Kind kind, Usage usage, LivingWorldConfig config) {
        if (usage.agents() >= config.COMPANION_BUDGET_CEILING) {
            return Admission.deny("Agent ceiling " + config.COMPANION_BUDGET_CEILING + " reached");
        }
        return switch (kind) {
            case COMPANION -> usage.ownerCompanions() >= config.COMPANION_PER_HUMAN
                    ? Admission.deny(config.COMPANION_PER_HUMAN + " companions already out") : Admission.ALLOW;
            case BENCH_LIVE -> usage.benchLive() >= config.BENCH_LIVE_GLOBAL
                    ? Admission.deny("server bench pool " + config.BENCH_LIVE_GLOBAL + " full")
                    : usage.ownerBenchLive() >= config.BENCH_LIVE_PER_OWNER
                    ? Admission.deny(config.BENCH_LIVE_PER_OWNER + " live bench slots already used") : Admission.ALLOW;
            case EXPEDITION -> usage.expedition() >= config.EXPEDITION_BURST_MAX
                    ? Admission.deny("expedition burst " + config.EXPEDITION_BURST_MAX + " reached") : Admission.ALLOW;
        };
    }

    /** Ms left before a load is allowed after one at {@code lastAtMs} (null = never loaded). */
    static long waitMs(Long lastAtMs, long nowMs, long intervalMs) {
        return lastAtMs == null ? 0L : Math.max(0L, lastAtMs + intervalMs - nowMs);
    }

    private boolean tryTakeLoadSlot(int ownerId, long nowMs) {
        long intervalMs = world.config().COMPANION_SPAWN_INTERVAL_MS;
        boolean[] taken = {false};
        lastLoadAtMs.compute(ownerId, (id, last) -> {
            if (waitMs(last, nowMs, intervalMs) > 0L) {
                return last;
            }
            taken[0] = true;
            return nowMs;
        });
        return taken[0];
    }
}
