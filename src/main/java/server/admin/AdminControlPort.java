package server.admin;

import client.Character;
import client.Client;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import config.YamlConfig;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import scripting.event.EventScriptManager;
import server.ShopFactory;
import server.TimerManager;
import server.living.LivingWorld;
import server.living.config.ContentLint;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.population.PopulationContent;
import server.living.resident.ResidentRuntime;
import tools.PacketCreator;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loopback-bound JSON control port for the MapleEdit admin dashboard.
 *
 * See ADMIN_DASHBOARD_PLAN.md §6 (/runtime/*) and §7 (/actions/*).
 *
 * Auth: Authorization: Bearer &lt;token&gt; where token is
 *   ServerConfig.ADMIN_CONTROL_TOKEN or (if blank) the ADMIN_CONTROL_TOKEN env var.
 * If both are blank, the port refuses every request.
 *
 * Dependencies: none beyond the JDK (com.sun.net.httpserver) — no Javalin needed.
 */
public final class AdminControlPort {
    private static final Logger log = LoggerFactory.getLogger(AdminControlPort.class);
    private static final AtomicReference<ScheduledFuture<?>> PENDING_SHUTDOWN = new AtomicReference<>();
    private static final AtomicReference<ShutdownSchedule> SHUTDOWN_INFO = new AtomicReference<>();

    private static HttpServer server;
    private static String authToken;

    private AdminControlPort() {}

    public static synchronized void start() {
        if (server != null) return;

        authToken = YamlConfig.config.server.ADMIN_CONTROL_TOKEN;
        if (authToken == null || authToken.isBlank()) {
            authToken = System.getenv("ADMIN_CONTROL_TOKEN");
        }
        if (authToken == null || authToken.isBlank()) {
            log.warn("AdminControlPort: no ADMIN_CONTROL_TOKEN set. Port will reject every request.");
        }

        int port = YamlConfig.config.server.ADMIN_CONTROL_PORT_BIND;
        if (port <= 0) port = 8480;

        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            log.error("AdminControlPort: failed to bind 127.0.0.1:{}", port, e);
            return;
        }

        AdminHandler handler = new AdminHandler();
        server.createContext("/runtime/", handler);
        server.createContext("/actions/", handler);
        server.createContext("/", handler);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "admin-control-port");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        log.info("AdminControlPort listening on 127.0.0.1:{}", port);
        // LOW_PRIORITY lag for /runtime/budget; if timers are not up yet, the first read starts it.
        AdminTimerLagProbe.LOW_PRIORITY.ensureStarted();

        try {
            AdminSsoKey.ensure();
            log.info("AdminSsoKey ready — address {}", AdminSsoKey.addressHex());
        } catch (Exception e) {
            log.warn("AdminSsoKey init failed; SSO attestations will be unavailable", e);
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // --- request handling ------------------------------------------------

    private static final class AdminHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                // SSO endpoints are credential-gated (both cosmic-login and
                // cosmic-sso-attest re-verify account+password; the pubkey
                // read is public by definition), so they don't need the admin
                // bearer — the game client calls them directly and shouldn't
                // ship with the admin token.
                if (!isPublicPath(method, path) && !authed(ex)) {
                    writeJson(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                    return;
                }
                String body = "GET".equals(method) ? "" : readBody(ex);
                String result = route(method, path, ex.getRequestURI().getRawQuery(), body);
                if (result == null) {
                    writeJson(ex, 404, "{\"ok\":false,\"error\":\"not_found\"}");
                    return;
                }
                writeJson(ex, 200, result);
            } catch (Exception e) {
                log.warn("AdminControlPort: {} {} threw", ex.getRequestMethod(), ex.getRequestURI(), e);
                writeJson(ex, 500, "{\"ok\":false,\"error\":\"server_error\",\"detail\":"
                        + AdminJson.str(e.getClass().getSimpleName() + ": " + e.getMessage()) + "}");
            }
        }

        private boolean isPublicPath(String method, String path) {
            if ("GET".equals(method) && "/runtime/sso-pubkey".equals(path)) return true;
            if ("POST".equals(method) && "/actions/cosmic-sso-attest".equals(path)) return true;
            if ("POST".equals(method) && "/actions/cosmic-login".equals(path)) return true;
            return false;
        }

        private boolean authed(HttpExchange ex) {
            if (authToken == null || authToken.isBlank()) return false;
            String h = ex.getRequestHeaders().getFirst("Authorization");
            if (h == null) return false;
            if (!h.startsWith("Bearer ")) return false;
            String presented = h.substring("Bearer ".length()).trim();
            return constantTimeEquals(presented, authToken);
        }

        private static boolean constantTimeEquals(String a, String b) {
            if (a.length() != b.length()) return false;
            int r = 0;
            for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
            return r == 0;
        }

        private String readBody(HttpExchange ex) throws IOException {
            byte[] bytes = ex.getRequestBody().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private void writeJson(HttpExchange ex, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static String route(String method, String path, String query, String body) {
        if ("GET".equals(method)) {
            switch (path) {
                case "/runtime/uptime": return rUptime();
                case "/runtime/online/summary": return rOnlineSummary();
                case "/runtime/online/list": return rOnlineList();
                case "/runtime/positions": return rPositions();
                case "/runtime/rates": return rRates();
                case "/runtime/events": return rEvents();
                case "/runtime/shutdown-status": return rShutdownStatus();
                case "/runtime/server-message": return rServerMessage();
                case "/runtime/shops": return rShops();
                case "/runtime/merchants": return rMerchants();
                case "/runtime/coupons": return rCoupons();
                case "/runtime/jvm": return rJvm();
                case "/runtime/gm-online": return rGmOnline();
                case "/runtime/ping": return "{\"ok\":true,\"pong\":true}";
                case "/runtime/gm-command": return rGmCommand();
                case "/runtime/stats/player-samples":    return AdminStatsReader.playerSamples(24);
                case "/runtime/stats/login-events":      return AdminStatsReader.loginEventsHourly(168);
                case "/runtime/stats/level-distribution":return AdminStatsReader.levelDistribution();
                case "/runtime/stats/job-distribution":  return AdminStatsReader.jobDistribution();
                case "/runtime/stats/top-maps":          return AdminStatsReader.topMaps(20);
                case "/runtime/stats/meso-supply":       return AdminStatsReader.mesoSupply();
                case "/runtime/stats/new-accounts":      return AdminStatsReader.newAccountsPerDay(30);
                case "/runtime/stats/retention":         return AdminStatsReader.retention();
                case "/runtime/reports/list":            return AdminReportsReader.list(200);
                case "/runtime/service-bot":             return rServiceBot();
                case "/runtime/living":                  return rLiving();
                case "/runtime/citizens":                return rCitizens();
                case "/runtime/budget":                  return AdminBudgetReader.budget(AdminBudgetReader.gcRequested(query));
                case "/runtime/companions":              return AdminCompanionsReader.all();
                case "/runtime/companions/audit":        return AdminCompanionsReader.audit();
                case "/runtime/tells":                   return AdminTellsReader.now();
                case "/runtime/tells/history":           return AdminTellsReader.history();
                case "/runtime/imperfections":           return AdminImperfectionsReader.now();
                case "/runtime/sso-pubkey":              return AdminSsoAuth.pubkeyBlob();
                default:
                    if (path.startsWith("/runtime/character/")) {
                        String idStr = path.substring("/runtime/character/".length());
                        return rCharacter(idStr);
                    }
                    if (path.startsWith("/runtime/buffs/")) {
                        return rBuffs(path.substring("/runtime/buffs/".length()));
                    }
                    if (path.startsWith("/runtime/companions/")) {
                        return AdminCompanionsReader.owner(path.substring("/runtime/companions/".length()));
                    }
                    if (path.startsWith("/runtime/stats/player-samples/")) {
                        String hoursStr = path.substring("/runtime/stats/player-samples/".length());
                        try { return AdminStatsReader.playerSamples(Integer.parseInt(hoursStr)); }
                        catch (NumberFormatException nfe) { return "{\"ok\":false,\"error\":\"bad_hours\"}"; }
                    }
                    if (path.startsWith("/runtime/cosmic-max-level/")) {
                        String idStr = path.substring("/runtime/cosmic-max-level/".length());
                        try { return AdminCosmicAuth.maxLevel(Integer.parseInt(idStr)); }
                        catch (NumberFormatException nfe) { return "{\"ok\":false,\"error\":\"bad_account\"}"; }
                    }
                    return null;
            }
        }
        if ("POST".equals(method)) {
            switch (path) {
                case "/actions/broadcast": return aBroadcast(body);
                case "/actions/shutdown": return aShutdown(body);
                case "/actions/cancel-shutdown": return aCancelShutdown();
                case "/actions/living-reload": return aLivingReload();
                case "/actions/citizens": return aCitizens(body);
                case "/actions/saveall": return aSaveAll();
                case "/actions/dcall": return aDcAll();
                case "/actions/whisper": return aWhisper(body);
                case "/actions/gm-command": return aGmCommand(body);
                case "/actions/sql/run": return aSqlRun(body);
                case "/actions/cosmic-login": return aCosmicLogin(body);
                case "/actions/cosmic-sso-attest": return aCosmicSsoAttest(body);
                case "/actions/toggle/auto-register": return aToggleAutoRegister(body);
                case "/actions/toggle/autoban":       return aToggleAutoban(body);
                case "/actions/toggle/chat-log":      return aToggleChatLog(body);
                case "/actions/supply-rate-coupon":   return aSupplyRateCoupon(body);
                case "/actions/gm-shop/reload":       return aGmShopReload();
                default:
                    if (path.startsWith("/actions/rates/")) {
                        String worldIdStr = path.substring("/actions/rates/".length());
                        return aSetRates(worldIdStr, body);
                    }
                    return null;
            }
        }
        return null;
    }

    // --- read handlers ---------------------------------------------------

    private static String rUptime() {
        long nowMs = System.currentTimeMillis();
        long startedAt = Server.uptime;
        return "{\"ok\":true,\"millis\":" + (nowMs - startedAt) + ",\"started_at\":" + startedAt + "}";
    }

    private static String rOnlineSummary() {
        AdminJson.Arr worlds = new AdminJson.Arr();
        for (World w : Server.getInstance().getWorlds()) {
            AdminJson.Arr channels = new AdminJson.Arr();
            int total = 0;
            for (Channel ch : w.getChannels()) {
                int count = ch.getPlayerStorage().getAllCharacters().size();
                total += count;
                channels.obj()
                        .num("id", ch.getId())
                        .num("count", count)
                        .num("capacity", ch.getChannelCapacity());
            }
            worlds.obj()
                    .num("id", w.getId())
                    .num("total", total)
                    .raw("channels", channels.toString());
        }
        return "{\"ok\":true,\"worlds\":" + worlds + "}";
    }

    private static String rOnlineList() {
        AdminJson.Arr list = new AdminJson.Arr();
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                appendPlayer(list.obj(), chr, w, true);
            }
        }
        return "{\"ok\":true,\"players\":" + list + "}";
    }

    /** Map coordinates of every loaded character (players and bots): lets an operator camera track a bot. */
    /** Active buffs of one online character: source skill/item, time used and full duration (ms). */
    private static String rBuffs(String name) {
        for (World w : Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterByName(name);
            if (chr == null) {
                continue;
            }
            AdminJson.Arr list = new AdminJson.Arr();
            for (net.server.PlayerBuffValueHolder buff : chr.getAllBuffs()) {
                list.obj().num("source", buff.effect.getBuffSourceId())
                        .num("usedMs", buff.usedTime)
                        .num("durationMs", buff.effect.getDuration());
            }
            return "{\"ok\":true,\"name\":" + AdminJson.str(chr.getName()) + ",\"buffs\":" + list + "}";
        }
        return "{\"ok\":false,\"error\":\"not online\"}";
    }

    private static String rPositions() {
        AdminJson.Arr list = new AdminJson.Arr();
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                java.awt.Point pos = chr.getPosition();
                net.server.world.Party party = chr.getParty();
                list.obj().str("name", chr.getName())
                        .num("map", chr.getMapId())
                        .num("x", pos == null ? 0 : pos.x)
                        .num("y", pos == null ? 0 : pos.y)
                        .num("hp", chr.getHp())
                        .num("maxHp", chr.getMaxHp())
                        .num("partySize", party == null ? 0 : party.getMembers().size())
                        .bool("hidden", chr.isHidden())
                        .bool("darkSight", chr.getBuffedValue(client.BuffStat.DARKSIGHT) != null);
            }
        }
        return "{\"ok\":true,\"positions\":" + list + "}";
    }

    private static void appendPlayer(AdminJson.Obj o, Character chr, World w, boolean brief) {
        Client c = chr.getClient();
        o.num("charId", chr.getId())
                .str("name", chr.getName())
                .num("level", chr.getLevel())
                .num("job", chr.getJob().getId())
                .num("map", chr.getMapId())
                .num("world", w.getId())
                .num("gmLevel", chr.gmLevel());
        if (c != null) {
            try {
                o.num("channel", c.getChannel());
            } catch (Throwable ignored) { }
            try {
                o.str("ip", c.getRemoteAddress());
            } catch (Throwable ignored) { }
            try {
                String hwid = c.getHwid() == null ? null : c.getHwid().hwid();
                if (hwid != null) o.str("hwid", hwid);
            } catch (Throwable ignored) { }
        }
        if (!brief) {
            try { o.num("exp", chr.getExp()); } catch (Throwable ignored) {}
            try { o.num("hp", chr.getHp()); } catch (Throwable ignored) {}
            try { o.num("mp", chr.getMp()); } catch (Throwable ignored) {}
            try { o.num("meso", chr.getMeso()); } catch (Throwable ignored) {}
            if (chr.getGuild() != null) {
                try { o.num("guildId", chr.getGuild().getId()); } catch (Throwable ignored) {}
                try { o.str("guildName", chr.getGuild().getName()); } catch (Throwable ignored) {}
            }
        }
    }

    private static String rCharacter(String idStr) {
        int id;
        try { id = Integer.parseInt(idStr); } catch (NumberFormatException nfe) {
            return "{\"ok\":false,\"error\":\"bad_id\"}";
        }
        for (World w : Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterById(id);
            if (chr != null) {
                AdminJson.Obj o = new AdminJson.Obj();
                appendPlayer(o, chr, w, false);
                return "{\"ok\":true,\"player\":" + o + "}";
            }
        }
        return "{\"ok\":false,\"error\":\"offline\"}";
    }

    private static String rRates() {
        AdminJson.Arr arr = new AdminJson.Arr();
        for (World w : Server.getInstance().getWorlds()) {
            arr.obj()
                    .num("worldId", w.getId())
                    .num("exp", w.getExpRate())
                    .num("meso", w.getMesoRate())
                    .num("drop", w.getDropRate())
                    .num("bossDrop", w.getBossDropRate())
                    .num("quest", w.getQuestRate())
                    .num("travel", w.getTravelRate())
                    .num("fish", w.getFishingRate());
        }
        return "{\"ok\":true,\"rates\":" + arr + "}";
    }

    private static String rEvents() {
        // Per-channel script activity plus per-instance detail for any running
        // event/PQ. Used by the admin dashboard's boss-lockout / PQ-monitor
        // panel. The channels[] shape is preserved for back-compat; instances[]
        // is the new payload.
        //
        // Iteration: World -> Channel -> EventScriptManager.getRegisteredEventManagers()
        // -> EventManager.getInstances() -> per-instance snapshot.
        long now = System.currentTimeMillis();
        AdminJson.Arr channels = new AdminJson.Arr();
        AdminJson.Arr instances = new AdminJson.Arr();

        for (World w : Server.getInstance().getWorlds()) {
            for (Channel ch : w.getChannels()) {
                EventScriptManager esm;
                boolean scriptsActive = false;
                try {
                    esm = ch.getEventSM();
                    scriptsActive = esm != null && esm.isActive();
                } catch (Throwable t) {
                    esm = null;
                }

                int channelInstanceCount = 0;
                if (esm != null) {
                    Collection<EventManager> managers;
                    try {
                        managers = esm.getRegisteredEventManagers();
                    } catch (Throwable t) {
                        managers = java.util.Collections.emptyList();
                    }
                    for (EventManager em : managers) {
                        Collection<EventInstanceManager> eims;
                        try {
                            eims = em.getInstances();
                        } catch (Throwable t) {
                            continue;
                        }
                        for (EventInstanceManager eim : eims) {
                            channelInstanceCount++;
                            appendInstance(instances.obj(), w, ch, em, eim, now);
                        }
                    }
                }

                channels.obj()
                        .num("worldId", w.getId())
                        .num("channelId", ch.getId())
                        .bool("scriptsActive", scriptsActive)
                        .num("instances", channelInstanceCount);
            }
        }
        return "{\"ok\":true,\"channels\":" + channels + ",\"instances\":" + instances + "}";
    }

    private static void appendInstance(AdminJson.Obj o, World w, Channel ch,
                                       EventManager em, EventInstanceManager eim, long nowMs) {
        o.num("worldId", w.getId()).num("channelId", ch.getId());
        try { o.str("eventName", em.getName()); } catch (Throwable ignored) {}
        try { o.str("instanceName", eim.getName()); } catch (Throwable ignored) {}

        int playerCount = 0;
        AdminJson.Arr players = new AdminJson.Arr();
        try {
            List<Character> list = eim.getPlayers();
            if (list != null) {
                playerCount = list.size();
                for (Character chr : list) {
                    AdminJson.Obj p = players.obj();
                    try { p.num("charId", chr.getId()); } catch (Throwable ignored) {}
                    try { p.str("name", chr.getName()); } catch (Throwable ignored) {}
                    try { p.num("level", chr.getLevel()); } catch (Throwable ignored) {}
                    try { p.num("job", chr.getJob().getId()); } catch (Throwable ignored) {}
                    try { p.num("map", chr.getMapId()); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        o.num("playerCount", playerCount).raw("players", players.toString());

        try { o.num("leaderId", eim.getLeaderId()); } catch (Throwable ignored) {}
        try { o.bool("eventStarted", eim.isEventStarted()); } catch (Throwable ignored) {}
        try { o.bool("eventCleared", eim.isEventCleared()); } catch (Throwable ignored) {}
        try { o.bool("disposed", eim.isEventDisposed()); } catch (Throwable ignored) {}
        try { o.bool("timerStarted", eim.isTimerStarted()); } catch (Throwable ignored) {}

        long timeStarted = 0, eventTime = 0;
        try { timeStarted = eim.getTimeStarted(); } catch (Throwable ignored) {}
        try { eventTime = eim.getEventTime(); } catch (Throwable ignored) {}
        o.num("timeStartedMs", timeStarted);
        o.num("eventTimeMs", eventTime);
        long elapsedMs = timeStarted > 0 ? (nowMs - timeStarted) : 0;
        o.num("elapsedMs", elapsedMs);
        long remainingMs = (timeStarted > 0 && eventTime > 0) ? (eventTime - elapsedMs) : 0;
        if (remainingMs < 0) remainingMs = 0;
        o.num("remainingMs", remainingMs);

        AdminJson.Arr mapIds = new AdminJson.Arr();
        try {
            List<Integer> mids = eim.getMapIds();
            if (mids != null) for (Integer id : mids) mapIds.rawEl(String.valueOf(id));
        } catch (Throwable ignored) {}
        o.raw("mapIds", mapIds.toString());
    }

    /** Living world (server.living): content census, materialized Residents, roster and bench. */
    private static String rLiving() {
        LivingWorld world = LivingWorld.get();
        if (world == null) {
            return "{\"ok\":true,\"enabled\":false}";
        }
        AdminJson.Arr residents = new AdminJson.Arr();
        for (ResidentRuntime.Session session : world.residents().sessions()) {
            Character agent = session.agent();
            AdminJson.Obj row = residents.obj()
                    .str("persona", session.persona.id())
                    .str("name", session.persona.name())
                    .str("town", session.persona.homeTown())
                    .bool("materialized", agent != null);
            if (agent != null) {
                row.num("map", agent.getMapId()).num("level", agent.getLevel())
                        .num("x", agent.getPosition().x).num("y", agent.getPosition().y);
            }
        }
        AdminJson.Arr warnings = new AdminJson.Arr();
        for (String finding : world.content().warnings()) {
            warnings.rawEl(AdminJson.str(finding));
        }
        return new AdminJson.Obj()
                .bool("ok", true)
                .bool("enabled", true)
                .str("census", world.content().census())
                .num("personas", world.content().personas().size())
                .num("quests", world.content().quests().size())
                .num("contracts", world.content().contracts().size())
                .num("arcs", world.content().arcs().size())
                .num("rumors", world.content().rumors().size())
                .num("materialized", world.residents().sessions().stream().filter(session -> session.agent() != null).count())
                .num("benchLive", world.benchDuty().liveCount())
                .num("routineHour", world.routineHour())
                .raw("residents", residents.toString())
                .raw("warnings", warnings.toString())
                .finished();
    }

    /** Population (server.living.population): census, online sessions, cohorts, chatter/conversation/market counters. */
    private static String rCitizens() {
        Population population = Population.get();
        if (population == null) {
            return "{\"ok\":true,\"enabled\":false}";
        }
        AdminJson.Arr online = new AdminJson.Arr();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.agent();
            AdminJson.Obj row = online.obj().str("name", session.name()).bool("virtual", session.virtual)
                    .str("activity", session.activity == null ? "" : session.activity.name())
                    .num("map", session.mapId).num("channel", session.channel).str("cohort", session.citizen.cohort());
            if (agent != null) {
                row.num("level", agent.getLevel()).str("mapName", population.fields().name(session.mapId));
            }
        }
        AdminJson.Arr cohorts = new AdminJson.Arr();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        for (PopulationContent.Cohort cohort : population.content().cohorts().values()) {
            int members = 0;
            int onlineCount = 0;
            for (Citizen citizen : population.citizens()) {
                if (citizen.cohort().equals(cohort.id()) && citizen.state() == Citizen.State.ACTIVE) {
                    members++;
                    if (population.presence().session(citizen.characterId()) != null) {
                        onlineCount++;
                    }
                }
            }
            cohorts.obj().str("id", cohort.id()).num("members", members).num("online", onlineCount).num("hourWeight", cohort.weight(now));
        }
        AdminJson.Arr recentChatter = new AdminJson.Arr();
        for (String line : population.chatter().recentLines()) {
            recentChatter.rawEl(AdminJson.str(line));
        }
        AdminJson.Arr recentRuns = new AdminJson.Arr();
        for (String line : population.partyQuests().recentRuns()) {
            recentRuns.rawEl(AdminJson.str(line));
        }
        return new AdminJson.Obj()
                .bool("ok", true)
                .bool("enabled", true)
                .num("active", population.activeCount())
                .num("target", population.config().TARGET_CITIZENS)
                .num("loaded", population.presence().loadedCount())
                .num("virtual", population.presence().virtualCount())
                .num("onlineMax", population.config().ONLINE_MAX)
                .num("guilds", population.social().guildCount())
                .num("chatterLines", population.chatter().linesSpoken())
                .num("sweepMs", population.presence().lastSweepDurationMs())
                .bool("seeding", population.churn().seeding())
                .raw("seedQueue", seedQueue(population))
                .num("seedSkipped", population.churn().seedsSkipped())
                .str("census", population.content().census())
                .str("visibleCap", population.presence().visibleCapReport())
                .str("conversation", population.conversation().report())
                .str("market", population.market().report())
                .str("partyQuests", population.partyQuests().status())
                .raw("partyQuestRuns", recentRuns.toString())
                .raw("online", online.toString())
                .raw("cohorts", cohorts.toString())
                .raw("chatter", recentChatter.toString())
                .finished();
    }

    /**
     * Population ops for the dashboard: {"force": n} keeps n citizens loaded, {"sweep": true} runs a sweep (on this thread),
     * {"reload": true} re-reads content, {"seed": regionId, "count": n} queues n citizens homed in that region (in memory,
     * 1..200 a call; checked after a reload in the same request, before force and sweep are applied).
     */
    private static String aCitizens(String body) {
        Population population = Population.get();
        if (population == null) {
            return "{\"ok\":false,\"error\":\"population_disabled\"}";
        }
        Map<String, String> req = AdminJson.parseFlat(body);
        try {
            Integer force = req.containsKey("force") ? Integer.valueOf(req.get("force")) : null;
            int queued = req.containsKey("seed") ? Integer.parseInt(req.getOrDefault("count", "0")) : 0;
            java.util.List<String> findings = java.util.List.of();
            if ("true".equalsIgnoreCase(req.getOrDefault("reload", "false"))) {
                findings = population.reload();
            }
            String band = "";
            if (req.containsKey("seed")) {
                band = population.churn().describe(population.churn().requestSeeds(req.get("seed"), queued));
            }
            if (force != null) {
                population.presence().forceOnline(force);
            }
            if ("true".equalsIgnoreCase(req.getOrDefault("sweep", "false"))) {
                population.presence().sweep();
            }
            AdminJson.Arr arr = new AdminJson.Arr();
            for (String finding : findings) {
                arr.rawEl(AdminJson.str(finding));
            }
            return new AdminJson.Obj().bool("ok", true).num("loaded", population.presence().loadedCount())
                    .num("virtual", population.presence().virtualCount()).num("forced", population.presence().forcedOnline())
                    .num("sweepMs", population.presence().lastSweepDurationMs()).raw("findings", arr.toString())
                    .num("queued", queued).str("band", band).raw("seedQueue", seedQueue(population)).finished();
        } catch (RuntimeException failure) {
            return "{\"ok\":false,\"error\":" + AdminJson.str(String.valueOf(failure.getMessage())) + "}";
        }
    }

    private static String seedQueue(Population population) {
        AdminJson.Obj queue = new AdminJson.Obj();
        population.churn().seedQueue().forEach(queue::num);
        return queue.finished();
    }

    /** Re-reads living-world content from disk (same as {@code !world reload}); reports lint findings. */
    private static String aLivingReload() {
        LivingWorld world = LivingWorld.get();
        if (world == null) {
            return "{\"ok\":false,\"error\":\"living_world_disabled\"}";
        }
        try {
            java.util.List<String> findings = world.reload();
            AdminJson.Arr arr = new AdminJson.Arr();
            for (String finding : findings) {
                arr.rawEl(AdminJson.str(finding));
            }
            return new AdminJson.Obj()
                    .bool("ok", true)
                    .bool("errors", ContentLint.hasErrors(findings))
                    .str("census", world.content().census())
                    .raw("findings", arr.toString())
                    .finished();
        } catch (RuntimeException failure) {
            return "{\"ok\":false,\"error\":" + AdminJson.str(failure.getMessage()) + "}";
        }
    }

    private static String rShutdownStatus() {
        ShutdownSchedule s = SHUTDOWN_INFO.get();
        if (s == null) return "{\"ok\":true,\"scheduled\":false}";
        long remainingMs = s.runAtEpochMs - System.currentTimeMillis();
        if (remainingMs < 0) remainingMs = 0;
        return "{\"ok\":true,\"scheduled\":true,\"remaining_seconds\":" + (remainingMs / 1000)
                + ",\"run_at_epoch_ms\":" + s.runAtEpochMs
                + ",\"reason\":" + AdminJson.str(s.reason) + "}";
    }

    private static String rServerMessage() {
        // The scrolling banner (notice type 4) is stored per-channel, but
        // World.setServerMessage fans out to every channel, so channel 0's
        // copy is a faithful representative unless a GM used @servermessage
        // on a specific channel. We report every channel so the dashboard
        // can flag divergence when it happens.
        AdminJson.Arr worlds = new AdminJson.Arr();
        for (World w : Server.getInstance().getWorlds()) {
            AdminJson.Arr channels = new AdminJson.Arr();
            String worldMsg = null;
            for (Channel ch : w.getChannels()) {
                String msg = ch.getServerMessage();
                if (msg == null) msg = "";
                if (worldMsg == null) worldMsg = msg;
                channels.obj()
                        .num("id", ch.getId())
                        .str("message", msg);
            }
            worlds.obj()
                    .num("id", w.getId())
                    .str("message", worldMsg == null ? "" : worldMsg)
                    .raw("channels", channels.toString());
        }
        return "{\"ok\":true,\"worlds\":" + worlds + "}";
    }

    private static String rShops() {
        AdminJson.Arr arr = new AdminJson.Arr();
        try {
            for (World w : Server.getInstance().getWorlds()) {
                if (w.getActivePlayerShops() == null) continue;
                for (var ps : w.getActivePlayerShops()) {
                    AdminJson.Obj o = arr.obj();
                    try { if (ps.getOwner() != null) o.str("owner", ps.getOwner().getName()); } catch (Throwable ignored) {}
                    try { o.num("mapId", ps.getMapId()); } catch (Throwable ignored) {}
                    try { o.num("itemCount", ps.getItems() == null ? 0 : ps.getItems().size()); } catch (Throwable ignored) {}
                    o.num("worldId", w.getId());
                }
            }
        } catch (Throwable t) {
            return "{\"ok\":true,\"shops\":[],\"note\":\"unavailable\"}";
        }
        return "{\"ok\":true,\"shops\":" + arr + "}";
    }

    private static String rMerchants() {
        AdminJson.Arr arr = new AdminJson.Arr();
        try {
            for (World w : Server.getInstance().getWorlds()) {
                if (w.getActiveMerchants() == null) continue;
                for (var hm : w.getActiveMerchants()) {
                    AdminJson.Obj o = arr.obj();
                    try { o.str("owner", hm.getOwner()); } catch (Throwable ignored) {}
                    try { o.num("mapId", hm.getMapId()); } catch (Throwable ignored) {}
                    try { o.num("itemCount", hm.getItems() == null ? 0 : hm.getItems().size()); } catch (Throwable ignored) {}
                    try { o.num("mesos", hm.getMesos()); } catch (Throwable ignored) {}
                    o.num("worldId", w.getId());
                }
            }
        } catch (Throwable t) {
            return "{\"ok\":true,\"merchants\":[],\"note\":\"unavailable\"}";
        }
        return "{\"ok\":true,\"merchants\":" + arr + "}";
    }

    private static String rCoupons() {
        // Plan §6 shape: {active: [ids], rates: {id: rate}}. Active coupons
        // are the subset of all configured coupons currently in effect; rates
        // is the full {couponItemId -> rate} table loaded from nxcoupons.
        AdminJson.Arr active = new AdminJson.Arr();
        try {
            List<Integer> ids = Server.getInstance().getActiveCoupons();
            if (ids != null) for (Integer id : ids) active.rawEl(String.valueOf(id));
        } catch (Throwable ignored) { }

        StringBuilder rates = new StringBuilder("{");
        boolean first = true;
        try {
            Map<Integer, Integer> couponRates = Server.getInstance().getCouponRates();
            if (couponRates != null) {
                for (Map.Entry<Integer, Integer> e : couponRates.entrySet()) {
                    if (!first) rates.append(',');
                    rates.append('"').append(e.getKey()).append("\":").append(e.getValue());
                    first = false;
                }
            }
        } catch (Throwable ignored) { }
        rates.append('}');

        return "{\"ok\":true,\"active\":" + active + ",\"rates\":" + rates + "}";
    }

    private static String rJvm() {
        Runtime r = Runtime.getRuntime();
        long heapMb = (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
        long maxMb = r.maxMemory() / (1024 * 1024);
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        int timerQueued = -1;
        long timerActive = -1;
        try {
            timerQueued = TimerManager.getInstance().getQueuedTasks();
            timerActive = TimerManager.getInstance().getActiveCount();
        } catch (Throwable ignored) { }
        return "{\"ok\":true,\"heap_mb\":" + heapMb
                + ",\"heap_max_mb\":" + maxMb
                + ",\"threads\":" + threads
                + ",\"timer_active\":" + timerActive
                + ",\"timer_queued\":" + timerQueued + "}";
    }

    private static String rServiceBot() {
        String name = AdminServiceBot.currentBotName();
        boolean enabled = config.YamlConfig.config.server.ENABLE_ADMIN_SERVICE_BOT;
        if (!enabled) {
            return "{\"ok\":true,\"enabled\":false,\"name\":null}";
        }
        return "{\"ok\":true,\"enabled\":true,\"name\":"
                + (name == null ? "null" : AdminJson.str(name)) + "}";
    }

    private static String rGmOnline() {
        AdminJson.Arr arr = new AdminJson.Arr();
        int count = 0;
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (chr.isGM()) {
                    arr.obj()
                            .str("name", chr.getName())
                            .num("gmLevel", chr.gmLevel())
                            .num("worldId", w.getId());
                    count++;
                }
            }
        }
        return "{\"ok\":true,\"count\":" + count + ",\"names\":" + arr + "}";
    }

    // --- mutation handlers -----------------------------------------------

    private static String aBroadcast(String body) {
        Map<String, String> req = AdminJson.parseFlat(body);
        String type = req.getOrDefault("type", "notice").toLowerCase(Locale.ROOT);
        String message = req.getOrDefault("message", "");

        int noticeType;
        boolean serverMsg = false;
        switch (type) {
            case "notice":     noticeType = 0; break;
            case "popup":      noticeType = 1; break;
            case "pink":       noticeType = 5; break;
            case "blue":       noticeType = 6; break;
            case "servermsg":  noticeType = 4; serverMsg = true; break;
            default: return "{\"ok\":false,\"error\":\"bad_type\"}";
        }

        // Empty message is legitimate for servermsg (clears the scrolling banner);
        // every other notice type needs something to display.
        if (message.isEmpty() && !serverMsg) {
            return "{\"ok\":false,\"error\":\"missing_message\"}";
        }

        int sent = 0;
        for (World w : Server.getInstance().getWorlds()) {
            if (serverMsg) {
                w.setServerMessage(message);
                sent += w.getPlayerStorage().getAllCharacters().size();
            } else {
                w.broadcastPacket(PacketCreator.serverNotice(noticeType, message));
                sent += w.getPlayerStorage().getAllCharacters().size();
            }
        }
        return "{\"ok\":true,\"sent_to\":" + sent + "}";
    }

    private static String aSetRates(String worldIdStr, String body) {
        int worldId;
        try { worldId = Integer.parseInt(worldIdStr); } catch (NumberFormatException nfe) {
            return "{\"ok\":false,\"error\":\"bad_world_id\"}";
        }
        World w = Server.getInstance().getWorld(worldId);
        if (w == null) return "{\"ok\":false,\"error\":\"no_such_world\"}";

        Map<String, String> req = AdminJson.parseFlat(body);
        AdminJson.Obj applied = new AdminJson.Obj();
        if (req.containsKey("exp"))      { int v = Integer.parseInt(req.get("exp"));      w.setExpRate(v);       applied.num("exp", v); }
        if (req.containsKey("meso"))     { int v = Integer.parseInt(req.get("meso"));     w.setMesoRate(v);      applied.num("meso", v); }
        if (req.containsKey("drop"))     { int v = Integer.parseInt(req.get("drop"));     w.setDropRate(v);      applied.num("drop", v); }
        if (req.containsKey("bossDrop")) { int v = Integer.parseInt(req.get("bossDrop")); w.setBossDropRate(v);  applied.num("bossDrop", v); }
        if (req.containsKey("quest"))    { int v = Integer.parseInt(req.get("quest"));    w.setQuestRate(v);     applied.num("quest", v); }
        if (req.containsKey("travel"))   { int v = Integer.parseInt(req.get("travel"));   w.setTravelRate(v);    applied.num("travel", v); }
        if (req.containsKey("fish"))     { int v = Integer.parseInt(req.get("fish"));     w.setFishingRate(v);   applied.num("fish", v); }
        return "{\"ok\":true,\"worldId\":" + worldId + ",\"applied\":" + applied + "}";
    }

    private static String aShutdown(String body) {
        Map<String, String> req = AdminJson.parseFlat(body);
        int minutes;
        try { minutes = Integer.parseInt(req.getOrDefault("minutes", "0")); }
        catch (NumberFormatException nfe) { return "{\"ok\":false,\"error\":\"bad_minutes\"}"; }
        if (minutes < 0) return "{\"ok\":false,\"error\":\"bad_minutes\"}";
        String reason = req.getOrDefault("reason", "");

        long delayMs = Math.max(1L, minutes * 60_000L);
        if (minutes > 0) {
            for (World w : Server.getInstance().getWorlds()) {
                w.broadcastPacket(PacketCreator.serverNotice(0,
                        "Server will shut down in " + minutes + " minute(s). Please return to town."));
            }
        }
        ScheduledFuture<?> old = PENDING_SHUTDOWN.get();
        if (old != null) old.cancel(false);
        ScheduledFuture<?> f = TimerManager.getInstance().schedule(
                Server.getInstance().shutdown(false), delayMs);
        PENDING_SHUTDOWN.set(f);
        SHUTDOWN_INFO.set(new ShutdownSchedule(System.currentTimeMillis() + delayMs, reason));
        return "{\"ok\":true,\"run_at_epoch_ms\":" + (System.currentTimeMillis() + delayMs)
                + ",\"minutes\":" + minutes + "}";
    }

    private static String aCancelShutdown() {
        ScheduledFuture<?> f = PENDING_SHUTDOWN.getAndSet(null);
        SHUTDOWN_INFO.set(null);
        boolean cancelled = f != null && f.cancel(false);
        return "{\"ok\":true,\"cancelled\":" + cancelled + "}";
    }

    private static String aSaveAll() {
        int saved = 0;
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                try { chr.saveCharToDB(); saved++; } catch (Throwable ignored) {}
            }
        }
        return "{\"ok\":true,\"saved\":" + saved + "}";
    }

    private static String aDcAll() {
        int dced = 0;
        for (World w : Server.getInstance().getWorlds()) {
            List<Character> copy = new ArrayList<>(w.getPlayerStorage().getAllCharacters());
            for (Character chr : copy) {
                if (chr.isGM()) continue;
                try {
                    Client c = chr.getClient();
                    if (c != null) { c.disconnect(false, false); dced++; }
                } catch (Throwable ignored) {}
            }
        }
        return "{\"ok\":true,\"disconnected\":" + dced + "}";
    }

    private static String aSqlRun(String body) {
        Map<String, String> req = AdminJson.parseFlat(body);
        String sql = req.getOrDefault("sql", "");
        boolean allowMutation = "true".equalsIgnoreCase(req.getOrDefault("allow_mutation", "false"));
        if (sql.isEmpty()) return "{\"ok\":false,\"error\":\"missing_sql\"}";
        return AdminSqlRunner.run(sql, allowMutation);
    }

    private static String aCosmicLogin(String body) {
        Map<String, String> req = AdminJson.parseFlat(body);
        String account = req.getOrDefault("account", "");
        String password = req.getOrDefault("password", "");
        return AdminCosmicAuth.login(account, password);
    }

    private static String aCosmicSsoAttest(String body) {
        Map<String, String> req = AdminJson.parseFlat(body);
        String account = req.getOrDefault("account", "");
        String password = req.getOrDefault("password", "");
        String character = req.getOrDefault("character", "");
        return AdminSsoAuth.attest(account, password, character);
    }

    /**
     * Runs an allowlisted harness command as the headless operator (see HeadlessOperatorService).
     * Output keeps accumulating after this returns; poll GET /runtime/gm-command for it.
     */
    private static String aGmCommand(String body) {
        Map<String, String> req = AdminJson.parseFlat(body);
        String command = req.getOrDefault("command", "").trim();
        if (command.startsWith("!")) command = command.substring(1);
        if (command.isEmpty()) return "{\"ok\":false,\"error\":\"missing_command\"}";
        String operator = req.getOrDefault("operator", "").trim();
        if (operator.isEmpty()) {
            String admins = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES;
            operator = admins == null ? "" : admins.split(",")[0].trim();
        }
        if (operator.isEmpty()) return "{\"ok\":false,\"error\":\"no_operator\"}";
        try {
            return gmRunJson(HeadlessOperatorService.run(operator, command));
        } catch (Exception e) {
            log.warn("AdminControlPort: gm-command '{}' as {} failed", command, operator, e);
            return "{\"ok\":false,\"error\":" + AdminJson.str(String.valueOf(e)) + "}";
        }
    }

    private static String rGmCommand() {
        HeadlessOperatorService.Run run = HeadlessOperatorService.latest();
        return run == null ? "{\"ok\":true,\"run\":null}" : gmRunJson(run);
    }

    private static String gmRunJson(HeadlessOperatorService.Run run) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"id\":").append(run.id())
                .append(",\"operator\":").append(AdminJson.str(run.operator()))
                .append(",\"command\":").append(AdminJson.str(run.command()))
                .append(",\"started_at\":").append(run.startedAtMs())
                .append(",\"lines\":[");
        boolean first = true;
        for (String line : run.lines()) {
            if (!first) sb.append(',');
            sb.append(AdminJson.str(line));
            first = false;
        }
        return sb.append("]}").toString();
    }

    private static String aWhisper(String body) {
        Map<String, String> req = AdminJson.parseFlat(body);
        String target = req.getOrDefault("target", "");
        String message = req.getOrDefault("message", "");
        if (target.isEmpty() || message.isEmpty()) return "{\"ok\":false,\"error\":\"missing_fields\"}";
        for (World w : Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterByName(target);
            if (chr != null) {
                try {
                    chr.dropMessage(5, "[Admin] " + message);
                    return "{\"ok\":true,\"delivered\":true}";
                } catch (Throwable t) {
                    return "{\"ok\":false,\"error\":\"send_failed\"}";
                }
            }
        }
        return "{\"ok\":false,\"error\":\"offline\"}";
    }

    // --- in-memory toggles (mirrors §7 plan) -----------------------------
    //
    // These mutate YamlConfig at runtime only — they do NOT persist to
    // config.yaml. That's intentional: a dashboard toggle should be fast and
    // reversible, and should survive only as long as the process does. Use
    // the config PUT pipeline for durable changes.

    /**
     * Parse a JSON body's boolean-ish {@code enabled} field: accepts
     * "true"/"false", "1"/"0", "on"/"off", "yes"/"no". Returns null if the
     * field is absent or unparseable, so the caller can distinguish "toggle
     * current value" from "set explicitly".
     */
    private static Boolean parseEnabledFlag(Map<String, String> req) {
        if (!req.containsKey("enabled")) return null;
        String raw = req.get("enabled");
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "true": case "1": case "on":  case "yes": return Boolean.TRUE;
            case "false": case "0": case "off": case "no": return Boolean.FALSE;
            default: return null;
        }
    }

    private static String aToggleAutoRegister(String body) {
        Map<String, String> req = AdminJson.parseFlat(body);
        Boolean explicit = parseEnabledFlag(req);
        boolean next = explicit != null ? explicit : !YamlConfig.config.server.AUTOMATIC_REGISTER;
        YamlConfig.config.server.AUTOMATIC_REGISTER = next;
        return "{\"ok\":true,\"enabled\":" + next + "}";
    }

    private static String aToggleAutoban(String body) {
        Map<String, String> req = AdminJson.parseFlat(body);
        Boolean explicit = parseEnabledFlag(req);
        boolean next = explicit != null ? explicit : !YamlConfig.config.server.USE_AUTOBAN;
        YamlConfig.config.server.USE_AUTOBAN = next;
        return "{\"ok\":true,\"enabled\":" + next + "}";
    }

    private static String aToggleChatLog(String body) {
        Map<String, String> req = AdminJson.parseFlat(body);
        Boolean explicit = parseEnabledFlag(req);
        boolean next = explicit != null ? explicit : !YamlConfig.config.server.USE_ENABLE_CHAT_LOG;
        YamlConfig.config.server.USE_ENABLE_CHAT_LOG = next;
        return "{\"ok\":true,\"enabled\":" + next + "}";
    }

    private static String aSupplyRateCoupon(String body) {
        // Plan §7 spec is {enabled: bool}, not a toggle. Mirror the !supplyratecoupon
        // command semantics: if the flag is present, set it; otherwise toggle.
        Map<String, String> req = AdminJson.parseFlat(body);
        Boolean explicit = parseEnabledFlag(req);
        boolean next = explicit != null ? explicit : !YamlConfig.config.server.USE_SUPPLY_RATE_COUPONS;
        YamlConfig.config.server.USE_SUPPLY_RATE_COUPONS = next;
        return "{\"ok\":true,\"enabled\":" + next + "}";
    }

    private static String aGmShopReload() {
        // Equivalent of !reloadshops (gm3 command). Reloads popup + NPC shops
        // from DB without dropping anyone — no in-game actor required, which is
        // why this is a control-port action rather than queue-file.
        try {
            ShopFactory.getInstance().reloadShops();
            return "{\"ok\":true,\"reloaded\":true}";
        } catch (Throwable t) {
            log.warn("AdminControlPort: gm-shop reload failed", t);
            return "{\"ok\":false,\"error\":\"reload_failed\",\"detail\":"
                    + AdminJson.str(t.getClass().getSimpleName() + ": " + t.getMessage()) + "}";
        }
    }

    // --- internal types --------------------------------------------------

    private static final class ShutdownSchedule {
        final long runAtEpochMs;
        final String reason;
        ShutdownSchedule(long runAtEpochMs, String reason) {
            this.runAtEpochMs = runAtEpochMs;
            this.reason = reason == null ? "" : reason;
        }
    }
}
