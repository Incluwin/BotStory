package server.admin;

import client.Character;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.maps.HiredMerchant;
import server.maps.PlayerShop;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Loopback-only JSON HTTP server that exposes Cosmic's live runtime state to the MapleEdit
 * admin dashboard. Read-only (Phase A of the admin-dashboard plan).
 *
 * Auth: Bearer token from the {@code ADMIN_CONTROL_TOKEN} environment variable, which the
 * caller (MapleEdit) also reads from the same source. If the env var is unset, every request
 * is rejected with 503 — this is the intended fail-closed behaviour, not a bug.
 *
 * Binding: explicitly {@code 127.0.0.1}. Remote access is expected via Tailscale to MapleEdit,
 * which then hits this port locally. Never bind to 0.0.0.0.
 *
 * Backed by the JDK's built-in {@code com.sun.net.httpserver} rather than Javalin/Jetty to
 * keep dependency surface flat — this service handles a handful of endpoints at low QPS,
 * not a public web app.
 */
public final class AdminControlPort {
    private static final Logger log = LoggerFactory.getLogger(AdminControlPort.class);
    private static final String LOOPBACK = "127.0.0.1";
    private static final String TOKEN_ENV = "ADMIN_CONTROL_TOKEN";
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final int port;
    private final String bearerToken;
    private HttpServer server;

    AdminControlPort(int port, String bearerToken) {
        this.port = port;
        this.bearerToken = bearerToken == null ? "" : bearerToken;
    }

    public static AdminControlPort start(int port) {
        String token = System.getenv(TOKEN_ENV);
        if (token == null || token.isEmpty()) {
            log.warn("AdminControlPort: {} env var not set; port will run but reject all requests", TOKEN_ENV);
            token = "";
        }
        AdminControlPort acp = new AdminControlPort(port, token);
        try {
            acp.startServer();
            return acp;
        } catch (IOException e) {
            log.warn("AdminControlPort: failed to bind {}:{}: {}", LOOPBACK, port, e.getMessage());
            return null;
        }
    }

    void startServer() throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress(LOOPBACK, port), 0);
        srv.createContext("/runtime/", this::handleRuntime);
        srv.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "admin-control-port");
            t.setDaemon(true);
            return t;
        }));
        srv.start();
        this.server = srv;
        log.info("AdminControlPort listening on {}:{}", LOOPBACK, port);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
    }

    int getPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    // ---------- request handling ----------

    private void handleRuntime(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                respond(ex, 405, Json.obj("error", "method_not_allowed"));
                return;
            }
            if (!authenticate(ex)) {
                return;
            }
            String path = ex.getRequestURI().getPath();
            String body = dispatch(path);
            respond(ex, 200, body);
        } catch (Throwable t) {
            log.warn("AdminControlPort handler error for {}", ex.getRequestURI(), t);
            try {
                respond(ex, 500, Json.obj("error", "internal"));
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private boolean authenticate(HttpExchange ex) throws IOException {
        if (bearerToken.isEmpty()) {
            respond(ex, 503, Json.obj("error", "auth_disabled"));
            return false;
        }
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.equals("Bearer " + bearerToken)) {
            respond(ex, 401, Json.obj("error", "unauthorized"));
            return false;
        }
        return true;
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String dispatch(String path) {
        if (path.equals("/runtime/uptime"))           return uptime();
        if (path.equals("/runtime/online/summary"))   return onlineSummary();
        if (path.equals("/runtime/online/list"))      return onlineList();
        if (path.startsWith("/runtime/character/"))   return character(path.substring("/runtime/character/".length()));
        if (path.equals("/runtime/rates"))            return rates();
        if (path.equals("/runtime/events"))           return events();
        if (path.equals("/runtime/shutdown-status"))  return shutdownStatus();
        if (path.equals("/runtime/shops"))            return shops();
        if (path.equals("/runtime/merchants"))        return merchants();
        if (path.equals("/runtime/coupons"))          return coupons();
        if (path.equals("/runtime/jvm"))              return jvm();
        if (path.equals("/runtime/gm-online"))        return gmOnline();
        return Json.obj("error", "not_found", "path", path);
    }

    // ---------- endpoint implementations ----------

    private String uptime() {
        long now = System.currentTimeMillis();
        return Json.obj(
                "millis", now - Server.uptime,
                "started_at", Server.uptime
        );
    }

    private String onlineSummary() {
        List<String> worldJson = new ArrayList<>();
        for (World w : Server.getInstance().getWorlds()) {
            List<String> channelJson = new ArrayList<>();
            for (Channel ch : w.getChannels()) {
                channelJson.add(Json.obj(
                        "id", ch.getId(),
                        "count", w.getPlayerStorage().getAllCharacters().size() / Math.max(1, w.getChannels().size()),
                        "capacity", config.YamlConfig.config.server.CHANNEL_LOAD
                ));
            }
            worldJson.add(Json.obj(
                    "id", w.getId(),
                    "channels", Json.raw(Json.arr(channelJson.stream().map(Json::raw).toList()))
            ));
        }
        return Json.obj("worlds", Json.raw(Json.arr(worldJson.stream().map(Json::raw).toList())));
    }

    private String onlineList() {
        List<String> entries = new ArrayList<>();
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (!chr.isLoggedinWorld()) continue;
                entries.add(Json.obj(
                        "charId", chr.getId(),
                        "name", chr.getName(),
                        "level", chr.getLevel(),
                        "job", chr.getJob().getId(),
                        "map", chr.getMapId(),
                        "world", w.getId(),
                        "channel", chr.getClient() != null ? chr.getClient().getChannel() : -1,
                        "ip", chr.getClient() != null ? nullToEmpty(chr.getClient().getRemoteAddress()) : "",
                        "gmLevel", chr.gmLevel()
                ));
            }
        }
        return Json.arr(entries.stream().map(Json::raw).toList());
    }

    private String character(String rawId) {
        int id;
        try {
            id = Integer.parseInt(rawId);
        } catch (NumberFormatException e) {
            return Json.obj("error", "bad_id");
        }
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (chr.getId() == id) {
                    return Json.obj(
                            "charId", chr.getId(),
                            "name", chr.getName(),
                            "level", chr.getLevel(),
                            "job", chr.getJob().getId(),
                            "map", chr.getMapId(),
                            "world", w.getId(),
                            "channel", chr.getClient() != null ? chr.getClient().getChannel() : -1,
                            "gmLevel", chr.gmLevel(),
                            "hp", chr.getHp(),
                            "maxHp", chr.getCurrentMaxHp(),
                            "mp", chr.getMp(),
                            "maxMp", chr.getCurrentMaxMp(),
                            "meso", chr.getMeso(),
                            "exp", chr.getExp()
                    );
                }
            }
        }
        return Json.obj("error", "not_found");
    }

    private String rates() {
        List<String> worlds = new ArrayList<>();
        for (World w : Server.getInstance().getWorlds()) {
            worlds.add(Json.obj(
                    "worldId", w.getId(),
                    "exp", w.getExpRate(),
                    "meso", w.getMesoRate(),
                    "drop", w.getDropRate(),
                    "bossDrop", w.getBossDropRate(),
                    "quest", w.getQuestRate(),
                    "travel", w.getTravelRate(),
                    "fish", w.getFishingRate()
            ));
        }
        return Json.arr(worlds.stream().map(Json::raw).toList());
    }

    private String events() {
        // EventScriptManager's instance list is internal; report channel count only for now.
        // Filling in per-instance detail is a follow-up that needs EventScriptManager.getInstances() plumbing.
        List<String> rows = new ArrayList<>();
        for (World w : Server.getInstance().getWorlds()) {
            for (Channel ch : w.getChannels()) {
                rows.add(Json.obj(
                        "worldId", w.getId(),
                        "channelId", ch.getId(),
                        "status", ch.getEventSM() != null ? "ready" : "unavailable"
                ));
            }
        }
        return Json.arr(rows.stream().map(Json::raw).toList());
    }

    private String shutdownStatus() {
        // Cosmic doesn't track scheduled-shutdown state today (Server.shutdown runs synchronously).
        // The admin dashboard polls this; we return a stable shape so the client can just read it.
        return Json.obj("scheduled", false, "remaining_seconds", 0, "reason", "");
    }

    private String shops() {
        List<String> rows = new ArrayList<>();
        for (World w : Server.getInstance().getWorlds()) {
            for (PlayerShop shop : safeList(w.getActivePlayerShops())) {
                Character owner = shop.getOwner();
                rows.add(Json.obj(
                        "worldId", w.getId(),
                        "owner", owner != null ? owner.getName() : "",
                        "ownerId", owner != null ? owner.getId() : -1,
                        "mapId", shop.getMapId(),
                        "itemCount", shop.getItems() != null ? shop.getItems().size() : 0
                ));
            }
        }
        return Json.arr(rows.stream().map(Json::raw).toList());
    }

    private String merchants() {
        List<String> rows = new ArrayList<>();
        for (World w : Server.getInstance().getWorlds()) {
            for (HiredMerchant hm : safeList(w.getActiveMerchants())) {
                rows.add(Json.obj(
                        "worldId", w.getId(),
                        "owner", nullToEmpty(hm.getOwner()),
                        "ownerId", hm.getOwnerId(),
                        "itemCount", hm.getItems() != null ? hm.getItems().size() : 0
                ));
            }
        }
        return Json.arr(rows.stream().map(Json::raw).toList());
    }

    private String coupons() {
        Server srv = Server.getInstance();
        List<Integer> active = srv.getActiveCoupons();
        Map<Integer, Integer> rates = srv.getCouponRates();
        // Emit rates as a JSON object keyed by coupon id (string).
        Map<String, Object> rateMap = new LinkedHashMap<>();
        if (rates != null) {
            for (Map.Entry<Integer, Integer> e : rates.entrySet()) {
                rateMap.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return Json.obj(
                "active", active != null ? active : List.of(),
                "rates", rateMap
        );
    }

    private String jvm() {
        Runtime rt = Runtime.getRuntime();
        long heapUsedBytes = rt.totalMemory() - rt.freeMemory();
        TimerManager tm = TimerManager.getInstance();
        return Json.obj(
                "heap_used_mb", heapUsedBytes / (1024 * 1024),
                "heap_total_mb", rt.totalMemory() / (1024 * 1024),
                "heap_max_mb", rt.maxMemory() / (1024 * 1024),
                "threads", Thread.activeCount(),
                "timer_active", tm.getActiveCount(),
                "timer_queued", tm.getQueuedTasks(),
                "cores", rt.availableProcessors()
        );
    }

    private String gmOnline() {
        List<String> names = new ArrayList<>();
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (chr.isGM()) {
                    names.add(chr.getName());
                }
            }
        }
        return Json.obj("count", names.size(), "names", names);
    }

    // ---------- helpers ----------

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static <T> Collection<T> safeList(Collection<T> list) {
        return list == null ? List.of() : list;
    }
}
