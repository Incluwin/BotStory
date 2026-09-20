package net.security;

import client.Character;
import client.autoban.AutobanFactory;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side validators that reject physically-impossible client claims.
 *
 * <p>Each validator returns a {@link Verdict}; callers decide whether to
 * drop the event or let it through after flagging. The module piggybacks
 * on the existing {@code AutobanFactory} strike system so hits accumulate
 * across sessions without a parallel persistence layer.
 *
 * <p>Scope, by kind:
 * <ul>
 *   <li>{@link #checkDamage} — single attack-line sanity cap.
 *       Plugged into {@code AbstractDealDamageHandler} / {@code SummonDamageHandler}.</li>
 *   <li>{@link #checkMesoGain} — mesos/min ceiling. Plugged into
 *       {@code Character.gainMeso}.</li>
 *   <li>{@link #checkMovement} — per-packet position delta in pixels.
 *       Plugged into {@code AbstractMovementPacketHandler.parseMovement}
 *       (or equivalent). Takes previous + current position.</li>
 *   <li>{@link #checkInventoryDelta} — item stack growing without a drop/trade
 *       source. Called during save.</li>
 * </ul>
 *
 * <p>Behavior knob: when {@code ANTICHEAT_LOG_ONLY} is true (the default
 * for new deployments), violations are logged + a strike added but the
 * game action is still allowed. Flip to false once you've watched the logs
 * for a week and trust the tuning.
 */
public final class AntiCheat {
    private static final Logger log = LoggerFactory.getLogger(AntiCheat.class);

    // --- tunables ---------------------------------------------------------

    private static volatile boolean enabled = true;
    private static volatile boolean logOnly = true;
    private static volatile int maxSingleHitDamage = 500_000;
    private static volatile int maxMesoPerMin = 5_000_000;
    private static volatile int maxMoveDeltaPx = 1_200;

    // --- state -----------------------------------------------------------

    private static final Map<Integer, MesoRate> mesoRateByChr = new ConcurrentHashMap<>();
    private static final Path LOG_PATH = Path.of("logs", "anticheat.log");

    private AntiCheat() {}

    public static void reloadFromYaml() {
        try {
            enabled = YamlConfig.config.server.ENABLE_ANTICHEAT;
            logOnly = YamlConfig.config.server.ANTICHEAT_LOG_ONLY;
            maxSingleHitDamage = Math.max(1, YamlConfig.config.server.ANTICHEAT_MAX_SINGLE_HIT_DAMAGE);
            maxMesoPerMin = Math.max(1, YamlConfig.config.server.ANTICHEAT_MAX_MESO_PER_MIN);
            maxMoveDeltaPx = Math.max(100, YamlConfig.config.server.ANTICHEAT_MAX_MOVE_DELTA_PX);
            log.info("AntiCheat: enabled={} logOnly={} maxHit={} maxMeso/min={} maxMoveDelta={}",
                    enabled, logOnly, maxSingleHitDamage, maxMesoPerMin, maxMoveDeltaPx);
        } catch (Throwable t) {
            log.debug("AntiCheat: YAML not loaded, using defaults");
        }
    }

    // --- verdict ---------------------------------------------------------

    public enum Action { ALLOW, FLAG_ONLY, BLOCK }

    public record Verdict(Action action, String reason) {
        public static final Verdict OK = new Verdict(Action.ALLOW, "ok");
    }

    // --- damage ----------------------------------------------------------

    /**
     * Check a single attack line against the configured hit cap. Intended
     * to be called inside the damage handlers for every {@code dmg[i]}
     * value the client sent, before the damage is applied.
     *
     * @param chr attacking character (may be null during tests)
     * @param lineDamage single hit value the client claims
     * @return Verdict; {@link Action#BLOCK} only when {@code logOnly} is false
     */
    public static Verdict checkDamage(Character chr, long lineDamage) {
        if (!enabled) return Verdict.OK;
        if (lineDamage <= maxSingleHitDamage) return Verdict.OK;
        if (chr != null && chr.isGM()) return Verdict.OK;
        String reason = "single-hit " + lineDamage + " > cap " + maxSingleHitDamage;
        record(chr, "damage", reason);
        if (chr != null) {
            try {
                chr.getAutobanManager().addPoint(AutobanFactory.DAMAGE_HACK, reason);
            } catch (Exception ignore) {}
        }
        return logOnly ? new Verdict(Action.FLAG_ONLY, reason) : new Verdict(Action.BLOCK, reason);
    }

    // --- meso ------------------------------------------------------------

    /**
     * Track meso gains per character and flag if the rolling per-minute
     * total exceeds the configured ceiling.
     */
    public static Verdict checkMesoGain(Character chr, int gain) {
        if (!enabled) return Verdict.OK;
        if (chr == null || gain <= 0 || chr.isGM()) return Verdict.OK;
        MesoRate rate = mesoRateByChr.computeIfAbsent(chr.getId(), k -> new MesoRate());
        long perMin = rate.record(gain);
        if (perMin <= maxMesoPerMin) return Verdict.OK;
        String reason = "meso/min " + perMin + " > cap " + maxMesoPerMin;
        record(chr, "meso", reason);
        try {
            chr.getAutobanManager().addPoint(AutobanFactory.GENERAL, reason);
        } catch (Exception ignore) {}
        return logOnly ? new Verdict(Action.FLAG_ONLY, reason) : new Verdict(Action.BLOCK, reason);
    }

    // --- movement --------------------------------------------------------

    /**
     * Check a positional delta from one move packet to the next. Large
     * deltas are legitimate when portaling or using Haste-like skills; the
     * cap should only catch teleports that bypass map-edge collision or
     * jump hundreds of tiles at once.
     */
    public static Verdict checkMovement(Character chr, int prevX, int prevY,
                                        int curX, int curY) {
        if (!enabled) return Verdict.OK;
        if (chr == null || chr.isGM()) return Verdict.OK;
        int dx = Math.abs(curX - prevX);
        int dy = Math.abs(curY - prevY);
        int delta = (int) Math.sqrt((long) dx * dx + (long) dy * dy);
        if (delta <= maxMoveDeltaPx) return Verdict.OK;
        String reason = "move delta " + delta + "px > cap " + maxMoveDeltaPx
                + " (prev=" + prevX + "," + prevY + " cur=" + curX + "," + curY + ")";
        record(chr, "move", reason);
        try {
            chr.getAutobanManager().addPoint(AutobanFactory.DISTANCE_HACK, reason);
        } catch (Exception ignore) {}
        return logOnly ? new Verdict(Action.FLAG_ONLY, reason) : new Verdict(Action.BLOCK, reason);
    }

    // --- inventory -------------------------------------------------------

    /**
     * Check an inventory delta for impossible growth (a stack jumped up by
     * more than {@code maxDelta} without a matching meso / drop event).
     *
     * <p>The caller provides both counts and the expected source; sources
     * like "drop" or "trade" have explicit caps, "unknown" is the suspicious
     * path.
     */
    public static Verdict checkInventoryDelta(Character chr, int itemId, int oldQty,
                                              int newQty, String source) {
        if (!enabled) return Verdict.OK;
        if (chr == null || chr.isGM()) return Verdict.OK;
        int growth = newQty - oldQty;
        if (growth <= 0) return Verdict.OK;
        // "drop", "trade", "quest", "shop" are all allowed to any cap — they
        // went through a validator elsewhere. "unknown" / unspecified means
        // the client supplied the delta without a known event.
        if (source == null || source.isEmpty() || source.equals("unknown")) {
            int cap = Math.max(1000, Math.min(oldQty * 10, 30_000));
            if (growth > cap) {
                String reason = "item " + itemId + " +" + growth + " from unknown source (cap " + cap + ")";
                record(chr, "inventory", reason);
                try {
                    chr.getAutobanManager().addPoint(AutobanFactory.ITEM_VAC, reason);
                } catch (Exception ignore) {}
                return logOnly ? new Verdict(Action.FLAG_ONLY, reason) : new Verdict(Action.BLOCK, reason);
            }
        }
        return Verdict.OK;
    }

    // --- lifecycle -------------------------------------------------------

    /** Called on character logout to free the rolling-rate window. */
    public static void forgetCharacter(int chrId) {
        mesoRateByChr.remove(chrId);
    }

    /** Test / admin helper. */
    public static void resetAll() {
        mesoRateByChr.clear();
    }

    // --- logging ---------------------------------------------------------

    private static void record(Character chr, String kind, String reason) {
        String chrName = chr != null ? chr.getName() : "?";
        int chrId = chr != null ? chr.getId() : -1;
        log.warn("AntiCheat.{} chr={}({}) : {}", kind, chrName, chrId, reason);
        try {
            String line = Instant.now() + "\t" + kind + "\t" + chrId + "\t" + chrName + "\t" + reason + "\n";
            Files.createDirectories(LOG_PATH.getParent());
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignore) {
            // Log file is best-effort; the slf4j line above is authoritative.
        }
    }

    // --- internal: rolling meso rate ------------------------------------

    /** Rolling 60-second sum of meso gains. Thread-safe via synchronized push. */
    private static final class MesoRate {
        private final Deque<long[]> buckets = new ArrayDeque<>();  // [ts, gain]
        private long sum = 0;

        synchronized long record(int gain) {
            long now = System.currentTimeMillis();
            long cutoff = now - 60_000L;
            while (!buckets.isEmpty() && buckets.peekFirst()[0] < cutoff) {
                sum -= buckets.pollFirst()[1];
            }
            buckets.addLast(new long[]{now, gain});
            sum += gain;
            return sum;
        }
    }
}
