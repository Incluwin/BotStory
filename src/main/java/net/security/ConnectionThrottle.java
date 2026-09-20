package net.security;

import config.YamlConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP connection and packet rate throttle for the Cosmic game server.
 *
 * <p>Three independent guards, applied in order:
 * <ol>
 *   <li><b>Connection rate</b>: a new TCP connection from the same IP counts
 *       against a sliding window. More than {@code MAX_CONNECTS_PER_WINDOW}
 *       in {@code CONNECT_WINDOW_MS} and the connection is refused.</li>
 *   <li><b>Concurrent connection cap</b>: the same IP can't hold more than
 *       {@code MAX_CONCURRENT_PER_IP} live sockets at once. Enforced in
 *       {@link #shouldAccept(Channel)} and released in {@link #release(Channel)}.</li>
 *   <li><b>Packet rate</b>: via {@link PacketRateGuard}, a pipeline handler
 *       that closes the channel if it exceeds
 *       {@code MAX_PACKETS_PER_WINDOW} in {@code PACKET_WINDOW_MS}.</li>
 * </ol>
 *
 * <p>Loopback (127/8) is exempt so the AdminControlPort, test harness, and
 * GM character (which is loopback-only per {@code GmLoginSecurity}) never
 * get throttled regardless of how hammer-y a test script is.
 *
 * <p>Designed for a 6–12 friend private server on Tailscale. The numbers
 * accommodate ~3 concurrent clients per friend (2 HWID slots + 1 launcher
 * probe) and burst ~50 pps during map transitions, then sustain < 15 pps
 * during normal play. Tune via {@code config.yaml} if anything legitimate
 * gets clipped.
 */
public final class ConnectionThrottle {
    private static final Logger log = LoggerFactory.getLogger(ConnectionThrottle.class);

    // --- tunables (read from YAML on first use with sensible defaults) ----

    private static volatile boolean enabled = true;
    private static volatile int maxConcurrentPerIp = 6;
    private static volatile int maxConnectsPerWindow = 20;
    private static volatile long connectWindowMs = 10_000L;  // 10 s
    private static volatile int maxPacketsPerWindow = 120;   // 120 pps burst
    private static volatile long packetWindowMs = 1_000L;    // 1 s

    // --- state -----------------------------------------------------------

    private static final Map<String, AtomicInteger> liveByIp = new ConcurrentHashMap<>();
    private static final Map<String, Deque<Long>> connectHistory = new ConcurrentHashMap<>();
    private static final Map<String, Long> bannedUntil = new ConcurrentHashMap<>();
    private static final long BAN_DURATION_MS = TimeUnit.MINUTES.toMillis(5);

    private ConnectionThrottle() {}

    // --- config wiring --------------------------------------------------

    /** Invoked by {@code Server.init()} after YAML is parsed. Safe to call
     *  repeatedly — picks up live tuning without a restart. */
    public static void reloadFromYaml() {
        try {
            enabled = YamlConfig.config.server.ENABLE_CONNECTION_THROTTLE;
            maxConcurrentPerIp = clamp(YamlConfig.config.server.CT_MAX_CONCURRENT_PER_IP, 1, 200);
            maxConnectsPerWindow = clamp(YamlConfig.config.server.CT_MAX_CONNECTS_PER_WINDOW, 1, 10_000);
            connectWindowMs = clampLong(YamlConfig.config.server.CT_CONNECT_WINDOW_MS, 500L, 600_000L);
            maxPacketsPerWindow = clamp(YamlConfig.config.server.CT_MAX_PACKETS_PER_WINDOW, 10, 100_000);
            packetWindowMs = clampLong(YamlConfig.config.server.CT_PACKET_WINDOW_MS, 100L, 600_000L);
            log.info("ConnectionThrottle: enabled={} concurrent/ip={} connects={}x{}ms packets={}x{}ms",
                    enabled, maxConcurrentPerIp, maxConnectsPerWindow, connectWindowMs,
                    maxPacketsPerWindow, packetWindowMs);
        } catch (Throwable t) {
            // Accessing YAML before it's loaded falls back to defaults.
            log.debug("ConnectionThrottle: YAML not yet loaded, using defaults");
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static long clampLong(long v, long lo, long hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // --- per-connection gatekeeper ---------------------------------------

    /** True if an incoming TCP connection should be accepted. Callers that
     *  return false should close the socket without sending a response. */
    public static boolean shouldAccept(Channel channel) {
        if (!enabled) return true;
        String ip = remoteIp(channel);
        if (ip == null || isLoopback(ip)) return true;

        long now = System.currentTimeMillis();

        Long banExpiry = bannedUntil.get(ip);
        if (banExpiry != null) {
            if (banExpiry > now) {
                log.debug("ConnectionThrottle: refused {} (banned for {} ms more)",
                        ip, banExpiry - now);
                return false;
            }
            bannedUntil.remove(ip);
        }

        // 1) connection-rate sliding window
        Deque<Long> hist = connectHistory.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (hist) {
            long cutoff = now - connectWindowMs;
            while (!hist.isEmpty() && hist.peekFirst() < cutoff) {
                hist.pollFirst();
            }
            if (hist.size() >= maxConnectsPerWindow) {
                bannedUntil.put(ip, now + BAN_DURATION_MS);
                log.warn("ConnectionThrottle: {} exceeded connect rate ({} in {} ms), banned {} min",
                        ip, hist.size(), connectWindowMs, BAN_DURATION_MS / 60_000);
                return false;
            }
            hist.addLast(now);
        }

        // 2) concurrent-connection cap
        AtomicInteger live = liveByIp.computeIfAbsent(ip, k -> new AtomicInteger(0));
        int cur = live.incrementAndGet();
        if (cur > maxConcurrentPerIp) {
            live.decrementAndGet();
            bannedUntil.put(ip, now + BAN_DURATION_MS);
            log.warn("ConnectionThrottle: {} exceeded concurrent cap ({} > {}), banned {} min",
                    ip, cur, maxConcurrentPerIp, BAN_DURATION_MS / 60_000);
            return false;
        }
        return true;
    }

    /** Called by the channel close listener. Idempotent. */
    public static void release(Channel channel) {
        if (!enabled) return;
        String ip = remoteIp(channel);
        if (ip == null || isLoopback(ip)) return;
        AtomicInteger live = liveByIp.get(ip);
        if (live != null) {
            int n = live.decrementAndGet();
            if (n <= 0) {
                liveByIp.remove(ip, live);
            }
        }
    }

    /** Hook for admin commands / unit tests to reset a single IP or clear all. */
    public static void resetIp(String ip) {
        liveByIp.remove(ip);
        connectHistory.remove(ip);
        bannedUntil.remove(ip);
    }

    public static void resetAll() {
        liveByIp.clear();
        connectHistory.clear();
        bannedUntil.clear();
    }

    /** Returns a tuple-style diagnostic string for the admin control port. */
    public static String snapshot() {
        return String.format("enabled=%s, ips_tracked=%d, active_bans=%d, tunables={conc=%d, conn=%d/%dms, pkt=%d/%dms}",
                enabled, liveByIp.size(), bannedUntil.size(),
                maxConcurrentPerIp, maxConnectsPerWindow, connectWindowMs,
                maxPacketsPerWindow, packetWindowMs);
    }

    // --- packet-rate handler ---------------------------------------------

    /** Current tuning exposed to the pipeline handler. */
    static int packetWindowMax() { return maxPacketsPerWindow; }
    static long packetWindowMs() { return packetWindowMs; }
    static boolean enabled() { return enabled; }

    /** Pipeline handler — per-channel, closes the connection if packet rate
     *  exceeds the configured threshold. Loopback is exempt. */
    public static final class PacketRateGuard extends ChannelInboundHandlerAdapter {
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private boolean loopback;
        private boolean resolved;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!resolved) {
                String ip = remoteIp(ctx.channel());
                loopback = (ip == null) || isLoopback(ip);
                resolved = true;
            }
            if (!enabled() || loopback) {
                ctx.fireChannelRead(msg);
                return;
            }
            long now = System.currentTimeMillis();
            long cutoff = now - packetWindowMs();
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= packetWindowMax()) {
                String ip = remoteIp(ctx.channel());
                log.warn("PacketRateGuard: {} sent {} packets in {} ms, closing",
                        ip, timestamps.size(), packetWindowMs());
                ctx.close();
                if (ip != null && !isLoopback(ip)) {
                    bannedUntil.put(ip, now + BAN_DURATION_MS);
                }
                return;
            }
            timestamps.addLast(now);
            ctx.fireChannelRead(msg);
        }
    }

    // --- utility ---------------------------------------------------------

    private static String remoteIp(Channel ch) {
        try {
            if (ch == null || ch.remoteAddress() == null) return null;
            return ((InetSocketAddress) ch.remoteAddress()).getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isLoopback(String ip) {
        if (ip == null) return false;
        return ip.startsWith("127.") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1");
    }

    /** For housekeeping: drop history entries older than 10× connect window. */
    public static void sweepStaleHistory() {
        long cutoff = System.currentTimeMillis() - (10L * connectWindowMs);
        Iterator<Map.Entry<String, Deque<Long>>> it = connectHistory.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<Long>> e = it.next();
            Deque<Long> dq = e.getValue();
            synchronized (dq) {
                while (!dq.isEmpty() && dq.peekFirst() < cutoff) {
                    dq.pollFirst();
                }
                if (dq.isEmpty()) it.remove();
            }
        }
    }
}
