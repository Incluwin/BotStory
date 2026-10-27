package server.living.audit;

import client.Character;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.behavior.AgentTellProbe;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.integration.AgentPresence;
import server.living.audit.TellSnapshot.Deaths;
import server.living.audit.TellSnapshot.Row;
import server.living.audit.TellStats.Dist;
import server.living.audit.TellStats.Mode;
import server.living.audit.TellStats.PerBot;
import server.living.audit.TellStats.Percents;
import server.living.audit.TellStats.Spread;
import server.living.audit.TellStats.View;
import server.living.store.LivingStore;
import server.maps.MapleMap;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The tell audit (docs/BOT_CONCEPT_PLAN.md C2): how bot-like the bots look, measured where they act. The Agent engine
 * reports reactions, chat reply delays, potion points, buff recasts and walked routes through {@link AgentTellProbe};
 * chatter, presence and deaths call here. Each report adds to bounded running totals and allocates little; the
 * population social tick (every minute) samples where idle bots stand and how many bot-minutes each class spent
 * online, and folds every hour: GET /runtime/tells reads a fold made on the spot, and each hour one {@code audit.tells}
 * row goes to living_event_log with the build it measured. Every measure runs from the audit's start (the boot) on, so
 * a build is judged by its last row. Off unless living_world.TELL_AUDIT_ENABLED; off, each report is one volatile read.
 */
public final class TellAudit implements AgentTellProbe.Sink {
    private static final Logger log = LoggerFactory.getLogger(TellAudit.class);
    public static final String KIND = "audit.tells";
    static final long FOLD_MS = 3_600_000L;
    /** A line said again on one map within this long is a repeat (the plan's 10 min). */
    static final long REPEAT_WINDOW_MS = 600_000L;
    /** A gap between one speaker's lines longer than this is a break, not its chat cadence. */
    static final long CADENCE_GAP_MAX_MS = 1_800_000L;
    static final int STACK_RADIUS_PX = 30;
    static final int STACK_GROUP = 3;
    /** A bot that moved no further than this on either axis since the last sample stood idle. */
    static final int IDLE_MOVE_PX = 8;
    static final long FIELD_AFK_MAX_MS = 120_000L;
    static final long DAY_MS = 86_400_000L;
    static final long PRESENCE_WINDOW_MS = 7 * DAY_MS;
    /** Reactions a bot needs, and gaps a speaker needs, before its own spread counts. */
    static final int REACTIONS_PER_BOT = 10;
    static final int GAPS_PER_SPEAKER = 5;
    static final int ROUTE_KEYS_MAX = 20_000;
    static final int ROUTE_HASHES_MAX = 16;
    /** A sample later than this after the last credits this much bot time, not the stall between them. */
    static final long EXPOSURE_STEP_MAX_MS = 120_000L;
    /** Job branches by the hundreds digit of the job id (Cygnus and Aran fall in with their Explorer branch). */
    static final String[] CLASSES = {"beginner", "warrior", "magician", "bowman", "thief", "pirate", "other"};

    private static volatile TellAudit active;

    private final String build;
    private final long startedAtMs;
    private final Consumer<String> rowWriter;

    private final Dist combatReaction = new Dist();
    private final PerBot combatReactionByBot = new PerBot();
    private final Dist chatReply = new Dist();
    private final Percents potionPoints = new Percents();
    private final Percents buffRecasts = new Percents();

    private final Object chat = new Object();
    private final Map<Integer, Long> lastLineAt = new HashMap<>();
    private final PerBot chatGaps = new PerBot();
    private final Map<String, Long> recentMapLines = new HashMap<>();
    private long mapLines;
    private long mapRepeats;

    private final Map<RouteKey, Route> routes = new HashMap<>();
    private long repeatWalks;
    private long retraced;

    private final Map<Integer, Deque<long[]>> sessions = new HashMap<>();
    private final Dist sessionMinutes = new Dist();
    private final Map<String, Long> afks = new TreeMap<>();
    private long fieldAfksOverMax;

    private final Map<String, Long> deaths = new TreeMap<>();

    // the minute sample's, guarded by this
    private Map<Integer, Seen> lastSeen = new HashMap<>();
    private long lastSampleAtMs;
    private final long[] botMsByClass = new long[CLASSES.length];
    private long idleSamples;
    private long stackedSamples;
    private long stackedMapSamples;

    private volatile long lastFoldAtMs;
    private volatile TellSnapshot lastFold;

    TellAudit(String build, long startedAtMs, Consumer<String> rowWriter) {
        this.build = build;
        this.startedAtMs = startedAtMs;
        this.rowWriter = rowWriter;
        this.lastFoldAtMs = startedAtMs;
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /** LivingWorld.start: measures from now when {@code enabled}, its hourly rows going to {@code store}. */
    public static void install(LivingStore store, boolean enabled) {
        uninstall();
        if (enabled) {
            start(new TellAudit(buildSha(Path.of("")), System.currentTimeMillis(),
                    line -> store.event(0, null, KIND, line)));
            log.info("Tell audit on (build {})", active.build);
        }
    }

    static void start(TellAudit audit) {
        active = audit;
        AgentTellProbe.install(audit);
    }

    public static void uninstall() {
        active = null;
        AgentTellProbe.install(null);
    }

    /** The running audit, or null while it is off. */
    public static TellAudit active() {
        return active;
    }

    // ---- reports from the living world -------------------------------------------------------

    /** A citizen's chat line went out (ChatterEngine) on {@code channel} ("map", "party", ...). */
    public static void chatLine(int speakerId, int mapId, String channel, String text, long nowMs) {
        TellAudit audit = active;
        if (audit != null) {
            audit.line(speakerId, mapId, channel, text, nowMs);
        }
    }

    /** A citizen's session ended and was booked (PresenceScheduler). */
    public static void session(int characterId, long startedAtMs, long endedAtMs) {
        TellAudit audit = active;
        if (audit != null) {
            audit.sessionEnded(characterId, startedAtMs, endedAtMs);
        }
    }

    /** A citizen went AFK for {@code plannedMs} on a map of kind {@code where} (FieldCatalog.kind: field, town, ...). */
    public static void afk(String where, long plannedMs) {
        TellAudit audit = active;
        if (audit != null) {
            audit.afkStarted(where, plannedMs);
        }
    }

    /** A character died (LivingWorldHooks.playerDied); a bot's death counts by its class and what it was doing. */
    public static void died(Character chr) {
        TellAudit audit = active;
        if (audit == null || chr == null || !AgentPresence.isAgent(chr)) {
            return;
        }
        MapleMap map = chr.getMap();
        String activity = chr.getEventInstance() != null ? "instance"
                : map != null && AgentBossField.near(map) ? "boss" : "field";
        audit.died(CLASSES[classIndex(chr.getJob().getId())], activity);
    }

    /** The population social tick, every minute: samples where idle bots stand, and folds once an hour is up. */
    public static void tick(long nowMs) {
        TellAudit audit = active;
        if (audit == null) {
            return;
        }
        try {
            audit.sample(seenBots(), nowMs);
            audit.prune(nowMs);
            audit.foldIfDue(nowMs);
        } catch (RuntimeException failure) {
            log.warn("tell audit tick failed", failure);
        }
    }

    private static List<Seen> seenBots() {
        List<Seen> seen = new ArrayList<>();
        for (World world : Server.getInstance().getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                Point at = chr.getPosition();
                MapleMap map = chr.getMap();
                if (at != null && map != null && AgentPresence.isAgent(chr)) {
                    seen.add(new Seen(map, chr.getId(), at.x, at.y, chr.getJob().getId(), chr.isAlive()));
                }
            }
        }
        return seen;
    }

    // ---- reports from the Agent engine (AgentTellProbe) ---------------------------------------

    @Override
    public void combatReaction(int agentId, long reactionMs) {
        combatReaction.add(reactionMs);
        combatReactionByBot.add(agentId, reactionMs);
    }

    @Override
    public void chatReply(long delayMs) {
        chatReply.add(delayMs);
    }

    @Override
    public void potionPoint(int alertPercent) {
        potionPoints.add(alertPercent);
    }

    @Override
    public void buffRecast(int percentOfDuration) {
        buffRecasts.add(percentOfDuration);
    }

    @Override
    public void routeWalked(int agentId, int fromMapId, int toMapId, long routeHash) {
        synchronized (routes) {
            RouteKey key = new RouteKey(agentId, fromMapId, toMapId);
            Route route = routes.get(key);
            if (route == null) {
                if (routes.size() < ROUTE_KEYS_MAX) {
                    route = new Route();
                    route.add(routeHash);
                    routes.put(key, route);
                }
                return;
            }
            repeatWalks++;
            if (route.has(routeHash)) {
                retraced++;
            } else {
                route.add(routeHash);
            }
        }
    }

    // ---- collectors ------------------------------------------------------------------------

    void line(int speakerId, int mapId, String channel, String text, long nowMs) {
        synchronized (chat) {
            Long last = lastLineAt.put(speakerId, nowMs);
            if (last != null && nowMs - last <= CADENCE_GAP_MAX_MS) {
                chatGaps.add(speakerId, nowMs - last);
            }
            if (!"map".equals(channel) || text == null) {
                return;
            }
            Long said = recentMapLines.put(mapId + "|" + text.trim().toLowerCase(Locale.ROOT), nowMs);
            mapLines++;
            if (said != null && nowMs - said <= REPEAT_WINDOW_MS) {
                mapRepeats++;
            }
        }
    }

    void sessionEnded(int characterId, long startedAtMs, long endedAtMs) {
        if (endedAtMs <= startedAtMs) {
            return;
        }
        sessionMinutes.add((endedAtMs - startedAtMs) / 60_000L);
        synchronized (sessions) {
            sessions.computeIfAbsent(characterId, id -> new ArrayDeque<>()).addLast(new long[]{startedAtMs, endedAtMs});
        }
    }

    void afkStarted(String where, long plannedMs) {
        synchronized (afks) {
            afks.merge(where, 1L, Long::sum);
            if ("field".equals(where) && plannedMs > FIELD_AFK_MAX_MS) {
                fieldAfksOverMax++;
            }
        }
    }

    void died(String jobClass, String activity) {
        synchronized (deaths) {
            deaths.merge(jobClass + "/" + activity, 1L, Long::sum);
        }
    }

    /** One bot in the minute sample: the map instance it stands on (compared by identity), where, its job, alive. */
    record Seen(Object map, int characterId, int x, int y, int jobId, boolean alive) {
    }

    /**
     * Credits each bot's class with the time since the last sample, and counts the bots that stood idle (alive, on the
     * same map, within {@link #IDLE_MOVE_PX}) and how many of them stood in a stack of {@link #STACK_GROUP} or more.
     */
    synchronized void sample(List<Seen> seen, long nowMs) {
        long step = lastSampleAtMs == 0 ? 0 : Math.max(0, Math.min(EXPOSURE_STEP_MAX_MS, nowMs - lastSampleAtMs));
        lastSampleAtMs = nowMs;
        Map<Integer, Seen> now = new HashMap<>();
        Map<Object, List<Seen>> idleByMap = new HashMap<>();
        for (Seen bot : seen) {
            botMsByClass[classIndex(bot.jobId())] += step;
            Seen before = lastSeen.get(bot.characterId());
            now.put(bot.characterId(), bot);
            if (bot.alive() && before != null && before.map() == bot.map()
                    && Math.abs(before.x() - bot.x()) <= IDLE_MOVE_PX && Math.abs(before.y() - bot.y()) <= IDLE_MOVE_PX) {
                idleByMap.computeIfAbsent(bot.map(), map -> new ArrayList<>()).add(bot);
            }
        }
        lastSeen = now;
        for (List<Seen> idle : idleByMap.values()) {
            idleSamples += idle.size();
            if (idle.size() < STACK_GROUP) {
                continue;
            }
            int[] xs = new int[idle.size()];
            int[] ys = new int[idle.size()];
            for (int i = 0; i < idle.size(); i++) {
                xs[i] = idle.get(i).x();
                ys[i] = idle.get(i).y();
            }
            int stacked = TellStats.stacked(xs, ys, idle.size(), STACK_RADIUS_PX, STACK_GROUP);
            stackedSamples += stacked;
            if (stacked > 0) {
                stackedMapSamples++;
            }
        }
    }

    /** Drops lines past the repeat window and sessions past the presence window. */
    void prune(long nowMs) {
        synchronized (chat) {
            recentMapLines.values().removeIf(at -> nowMs - at > REPEAT_WINDOW_MS);
        }
        synchronized (sessions) {
            for (Deque<long[]> spans : sessions.values()) {
                while (!spans.isEmpty() && spans.peekFirst()[1] < nowMs - PRESENCE_WINDOW_MS) {
                    spans.removeFirst();
                }
            }
            sessions.values().removeIf(Deque::isEmpty);
        }
    }

    void foldIfDue(long nowMs) {
        if (nowMs - lastFoldAtMs < FOLD_MS) {
            return;
        }
        lastFoldAtMs = nowMs;
        TellSnapshot snapshot = snapshot(nowMs);
        lastFold = snapshot;
        rowWriter.accept(snapshot.compact());
    }

    /** The last hourly fold, or null before the first hour is up. */
    public TellSnapshot lastFold() {
        return lastFold;
    }

    // ---- the fold --------------------------------------------------------------------------

    /** Every measure, judged against its band, from the audit's start to {@code nowMs}. */
    public TellSnapshot snapshot(long nowMs) {
        List<Row> rows = new ArrayList<>();
        View combat = combatReaction.view();
        Spread combatSpread = combatReactionByBot.medianCv(REACTIONS_PER_BOT);
        rows.add(Row.judged("reaction.combat.cv", "rc.cv", combatSpread.medianCv(), combatSpread.bots(),
                TellBands.COMBAT_REACTION_CV, detail("bots", combatSpread.bots(), "pooledCv", combat.cv())));
        rows.add(Row.judged("reaction.combat.p5", "rc.p5", combat.p5(), combat.n(), TellBands.REACTION_P5_MS,
                distribution(combat)));
        View reply = chatReply.view();
        rows.add(Row.judged("reaction.chat.cv", "rh.cv", reply.cv(), reply.n(), TellBands.CHAT_REPLY_CV,
                distribution(reply)));
        rows.add(Row.judged("reaction.chat.p5", "rh.p5", reply.p5(), reply.n(), TellBands.REACTION_P5_MS,
                detail("min", reply.min())));
        Spread cadence = chatGaps.medianCv(GAPS_PER_SPEAKER);
        rows.add(Row.judged("chat.cadence.cv", "cc.cv", cadence.medianCv(), cadence.bots(), TellBands.CHAT_CADENCE_CV,
                detail("speakers", cadence.bots())));
        synchronized (chat) {
            rows.add(Row.judged("chat.repeat", "cr", share(mapRepeats, mapLines), mapLines, TellBands.CHAT_REPEAT,
                    detail("lines", mapLines, "repeats", mapRepeats)));
        }
        Map<String, Double> botHours = new LinkedHashMap<>();
        synchronized (this) {
            rows.add(Row.judged("stacking", "st", share(stackedSamples, idleSamples), idleSamples, TellBands.STACKING,
                    detail("idleSamples", idleSamples, "stackedSamples", stackedSamples,
                            "stackedMapSamples", stackedMapSamples)));
            for (int i = 0; i < CLASSES.length; i++) {
                if (botMsByClass[i] > 0) {
                    botHours.put(CLASSES[i], botMsByClass[i] / 3_600_000.0);
                }
            }
        }
        rows.add(onlineShare(nowMs));
        synchronized (afks) {
            Map<String, Double> where = new LinkedHashMap<>();
            afks.forEach((kind, count) -> where.put(kind, count.doubleValue()));
            long total = afks.values().stream().mapToLong(Long::longValue).sum();
            rows.add(Row.judged("presence.field_afk", "fa", fieldAfksOverMax, total, TellBands.FIELD_AFK, where));
        }
        View session = sessionMinutes.view();
        rows.add(Row.info("presence.session_minutes", "se", session.p50(), session.n(), distribution(session)));
        synchronized (routes) {
            rows.add(Row.judged("route.repeat", "ro", share(retraced, repeatWalks), repeatWalks, TellBands.ROUTE_REPEAT,
                    detail("routes", routes.size(), "retraced", retraced)));
        }
        Mode potion = potionPoints.mode();
        rows.add(Row.judged("potion.point", "pp", potion.share(), potion.n(), TellBands.POTION_POINT,
                detail("percent", potion.value())));
        Mode buff = buffRecasts.mode();
        rows.add(Row.judged("buff.recast", "br", buff.share(), buff.n(), TellBands.BUFF_RECAST,
                detail("percent", buff.value())));
        List<Deaths> died = new ArrayList<>();
        synchronized (deaths) {
            deaths.forEach((key, count) -> {
                int slash = key.indexOf('/');
                died.add(new Deaths(key.substring(0, slash), key.substring(slash + 1), count));
            });
        }
        return new TellSnapshot(build, startedAtMs, nowMs, (nowMs - startedAtMs) / 3_600_000.0, List.copyOf(rows),
                List.copyOf(died), botHours);
    }

    /**
     * The citizen online the most: its booked minutes inside the last 7 days (or since the audit started) over that
     * window, at least a day, so a citizen online 8 h of the audit's first 8 h reads a third, not all of its time.
     */
    private Row onlineShare(long nowMs) {
        long windowStart = Math.max(startedAtMs, nowMs - PRESENCE_WINDOW_MS);
        double window = Math.max(DAY_MS, nowMs - windowStart);
        int citizens = 0;
        int over = 0;
        double max = 0;
        double sum = 0;
        synchronized (sessions) {
            for (Deque<long[]> spans : sessions.values()) {
                long online = 0;
                for (long[] span : spans) {
                    online += Math.max(0, Math.min(span[1], nowMs) - Math.max(span[0], windowStart));
                }
                double share = online / window;
                citizens++;
                sum += share;
                max = Math.max(max, share);
                if (share > TellBands.ONLINE_SHARE.red()) {
                    over++;
                }
            }
        }
        return Row.judged("presence.online_share", "po", citizens == 0 ? Double.NaN : max, citizens,
                TellBands.ONLINE_SHARE, detail("citizens", citizens, "over20h", over,
                        "meanShare", citizens == 0 ? 0 : sum / citizens, "windowDays", window / DAY_MS));
    }

    private static Map<String, Double> distribution(View view) {
        return detail("n", view.n(), "mean", view.mean(), "cv", view.cv(), "min", view.min(), "p5", view.p5(),
                "p50", view.p50(), "p90", view.p90(), "max", view.max());
    }

    /** Name, value pairs in order. */
    private static Map<String, Double> detail(Object... pairs) {
        Map<String, Double> detail = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            detail.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return detail;
    }

    private static double share(long part, long whole) {
        return whole == 0 ? Double.NaN : (double) part / whole;
    }

    static int classIndex(int jobId) {
        int branch = jobId / 100 % 10;
        return branch < CLASSES.length - 1 ? branch : CLASSES.length - 1;
    }

    /** One walk key: the same bot walking from one map to another again. */
    private record RouteKey(int agentId, int fromMapId, int toMapId) {
    }

    /** The routes one key has walked (up to {@link #ROUTE_HASHES_MAX} distinct ones). */
    private static final class Route {
        private final long[] hashes = new long[ROUTE_HASHES_MAX];
        private int count;

        boolean has(long hash) {
            for (int i = 0; i < count; i++) {
                if (hashes[i] == hash) {
                    return true;
                }
            }
            return false;
        }

        void add(long hash) {
            if (count < hashes.length) {
                hashes[count++] = hash;
            }
        }
    }

    // ---- the build -------------------------------------------------------------------------

    /**
     * The commit the server runs: launch.bat builds the checkout it starts in, so its HEAD at boot (a worktree's
     * {@code .git} file followed, a packed ref read), 9 characters; "unknown" without a checkout.
     */
    static String buildSha(Path workDir) {
        try {
            Path git = workDir.resolve(".git");
            Path gitDir = Files.isDirectory(git) ? git
                    : workDir.resolve(Files.readString(git).trim().substring("gitdir:".length()).trim());
            Path commonFile = gitDir.resolve("commondir");
            Path common = Files.exists(commonFile) ? gitDir.resolve(Files.readString(commonFile).trim()) : gitDir;
            String head = Files.readString(gitDir.resolve("HEAD")).trim();
            if (!head.startsWith("ref:")) {
                return shortSha(head);
            }
            String ref = head.substring("ref:".length()).trim();
            for (Path dir : List.of(gitDir, common)) {
                if (Files.exists(dir.resolve(ref))) {
                    return shortSha(Files.readString(dir.resolve(ref)).trim());
                }
            }
            for (String line : Files.readAllLines(common.resolve("packed-refs"))) {
                if (line.endsWith(" " + ref)) {
                    return shortSha(line.substring(0, line.indexOf(' ')));
                }
            }
        } catch (IOException | RuntimeException failure) {
            log.debug("tell audit: no build sha in {}", workDir.toAbsolutePath(), failure);
        }
        return "unknown";
    }

    private static String shortSha(String sha) {
        return sha.length() > 9 ? sha.substring(0, 9) : sha;
    }
}
