package net.packet;

import config.YamlConfig;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntSupplier;

public final class PacketRateLimiter {
    private static final long WINDOW_MS = 1_000;
    private static final long VIOLATION_WINDOW_MS = 10_000;

    private final int violationLimit;
    private final Map<PacketFamily, Integer> familyRates;
    private final Map<PacketFamily, TokenBucket> familyBuckets = new EnumMap<>(PacketFamily.class);
    private final TokenBucket globalBucket;
    private final MobControlBudget mobControlBudget;
    private final Counter violationCounter = new Counter();

    public PacketRateLimiter(int globalRate, int globalBurst, int movementRate, int combatRate,
                             int chatRate, int economyRate, int crewRate, int otherRate, int violationLimit,
                             MobControlBudget mobControlBudget) {
        int safeGlobalRate = positive(globalRate);
        this.globalBucket = new TokenBucket(safeGlobalRate, Math.max(safeGlobalRate, globalBurst));
        this.violationLimit = positive(violationLimit);
        this.mobControlBudget = mobControlBudget;
        this.familyRates = new EnumMap<>(PacketFamily.class);
        familyRates.put(PacketFamily.AUTH, otherRate);
        familyRates.put(PacketFamily.MOVEMENT, movementRate);
        familyRates.put(PacketFamily.MOB_CONTROL, mobControlBudget.rateFor(0));
        familyRates.put(PacketFamily.COMBAT, combatRate);
        familyRates.put(PacketFamily.CHAT, chatRate);
        familyRates.put(PacketFamily.ECONOMY, economyRate);
        familyRates.put(PacketFamily.CREW, crewRate);
        familyRates.put(PacketFamily.OTHER, otherRate);
        for (PacketFamily family : PacketFamily.values()) {
            familyRates.compute(family, (ignored, value) -> positive(value == null ? otherRate : value));
            int rate = familyRates.get(family);
            familyBuckets.put(family, new TokenBucket(rate, rate * 2));
        }
    }

    public static PacketRateLimiter fromConfig() {
        return new PacketRateLimiter(
                YamlConfig.config.server.PACKET_GLOBAL_RATE_PER_SECOND,
                YamlConfig.config.server.PACKET_GLOBAL_BURST,
                YamlConfig.config.server.PACKET_MOVEMENT_RATE_PER_SECOND,
                YamlConfig.config.server.PACKET_COMBAT_RATE_PER_SECOND,
                YamlConfig.config.server.PACKET_CHAT_RATE_PER_SECOND,
                YamlConfig.config.server.PACKET_ECONOMY_RATE_PER_SECOND,
                YamlConfig.config.server.PACKET_CREW_RATE_PER_SECOND,
                YamlConfig.config.server.PACKET_OTHER_RATE_PER_SECOND,
                YamlConfig.config.server.PACKET_RATE_LIMIT_VIOLATIONS_BEFORE_DISCONNECT,
                MobControlBudget.fromConfig());
    }

    public Decision allow(short opcode, long nowMs) {
        return allow(opcode, nowMs, () -> 0);
    }

    /**
     * @param controlledMobs number of monsters this connection currently controls; only read for
     *                       {@link PacketFamily#MOB_CONTROL} packets.
     */
    public synchronized Decision allow(short opcode, long nowMs, IntSupplier controlledMobs) {
        PacketFamily family = PacketFamily.classify(opcode);
        TokenBucket global = globalBucket;
        TokenBucket familyBucket = familyBuckets.get(family);
        int familyLimit;
        boolean globalAllowed;
        if (family == PacketFamily.MOB_CONTROL) {
            familyLimit = mobControlBudget.rateFor(controlledMobs.getAsInt());
            familyBucket.retarget(familyLimit, nowMs);
            // Bounded by its own scaled budget instead; drawing from the fixed global bucket would
            // re-cap the controller of a crowded map at the global rate.
            globalAllowed = true;
        } else {
            familyLimit = familyRates.get(family);
            globalAllowed = global.tryConsume(nowMs);
        }
        boolean familyAllowed = familyBucket.tryConsume(nowMs);
        boolean allowed = globalAllowed && familyAllowed;
        if (allowed) {
            return new Decision(true, false, family, global.consumedInWindow(),
                    familyBucket.consumedInWindow(), familyLimit);
        }
        int violations = violationCounter.increment(nowMs, VIOLATION_WINDOW_MS);
        return new Decision(false, violations >= violationLimit, family, global.consumedInWindow(),
                familyBucket.consumedInWindow(), familyLimit);
    }

    private static int positive(int value) {
        return Math.max(1, value);
    }

    public record Decision(boolean allowed, boolean disconnect, PacketFamily family,
                           int globalCount, int familyCount, int familyLimit) {
    }

    /**
     * Per-second allowance for {@link PacketFamily#MOB_CONTROL}: {@code baseRate} plus
     * {@code ratePerControlledMob} for every monster the connection controls, capped at {@code maxRate}.
     */
    public record MobControlBudget(int baseRate, int ratePerControlledMob, int maxRate) {
        public static MobControlBudget fromConfig() {
            return new MobControlBudget(
                    YamlConfig.config.server.PACKET_MOB_CONTROL_RATE_PER_SECOND,
                    YamlConfig.config.server.PACKET_MOB_CONTROL_RATE_PER_CONTROLLED_MOB,
                    YamlConfig.config.server.PACKET_MOB_CONTROL_MAX_RATE_PER_SECOND);
        }

        int rateFor(int controlledMobs) {
            int base = positive(baseRate);
            long scaled = base + (long) Math.max(0, ratePerControlledMob) * Math.max(0, controlledMobs);
            return (int) Math.min(scaled, Math.max(base, maxRate));
        }

        /** Per-second allowance that controlling {@code controlledMobs} monsters adds on top of the base rate. */
        public int extraRateFor(int controlledMobs) {
            return rateFor(controlledMobs) - rateFor(0);
        }
    }

    private static final class Counter {
        private long startedMs;
        private int count;

        int increment(long nowMs, long windowMs) {
            if (startedMs == 0 || nowMs - startedMs >= windowMs) {
                startedMs = nowMs;
                count = 0;
            }
            return ++count;
        }
    }

    private static final class TokenBucket {
        private int ratePerSecond;
        private int capacity;
        private double tokens;
        private long lastRefillMs = -1;
        private long countWindowStartedMs = -1;
        private int consumedInWindow;

        TokenBucket(int ratePerSecond, int capacity) {
            this.ratePerSecond = ratePerSecond;
            this.capacity = capacity;
            this.tokens = capacity;
        }

        /** Changes the refill rate (capacity stays two seconds' worth), accruing at the old rate up to now. */
        void retarget(int newRatePerSecond, long nowMs) {
            if (newRatePerSecond == ratePerSecond) {
                return;
            }
            refill(nowMs);
            ratePerSecond = newRatePerSecond;
            capacity = newRatePerSecond * 2;
            tokens = Math.min(tokens, capacity);
        }

        boolean tryConsume(long nowMs) {
            refill(nowMs);
            if (nowMs - countWindowStartedMs >= WINDOW_MS) {
                countWindowStartedMs = nowMs;
                consumedInWindow = 0;
            }
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            consumedInWindow++;
            return true;
        }

        int consumedInWindow() {
            return consumedInWindow;
        }

        private void refill(long nowMs) {
            if (lastRefillMs < 0) {
                lastRefillMs = nowMs;
                countWindowStartedMs = nowMs;
            } else if (nowMs > lastRefillMs) {
                tokens = Math.min(capacity, tokens + (nowMs - lastRefillMs) * ratePerSecond / 1_000.0);
                lastRefillMs = nowMs;
            }
        }
    }
}
