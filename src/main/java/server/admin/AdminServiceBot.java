package server.admin;

import client.Character;
import client.Client;
import config.YamlConfig;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Always-logged-in GM character for the MapleEdit admin queue dispatch path.
 *
 * ADMIN_DASHBOARD_PLAN.md section 14 ("Service-bot GM character"). When no
 * human GM is logged in, the GM-command-queue dispatch path can still work
 * if a bot character is registered in PlayerStorage. This class loads the
 * configured character from the DB, attaches a stub Client (no io channel),
 * and adds it to the channel's PlayerStorage so
 * {@code GmTestQueueWatcher.findCharacter} sees it.
 *
 * The bot's character must already exist in the accounts + characters
 * tables with {@code gm >= 2} and the expected name. Use the companion
 * Python CLI at {@code mapleedit/service_bot.py} to provision one.
 *
 * Packets sent to the bot's client are dropped by
 * {@link Client#sendPacket(tools.Packet)} — the bot never sees game state,
 * and no network side-effects happen when GM commands run. Commands that
 * don't need packet responses (ban, jail, give, level, warp, reload*, etc.)
 * work exactly the same as when a human GM issues them.
 */
public final class AdminServiceBot {
    private static final Logger log = LoggerFactory.getLogger(AdminServiceBot.class);

    private static Character bot;
    private static Client botClient;

    private AdminServiceBot() {}

    /** Idempotent; safe to call during server init and during re-wire. */
    public static synchronized void startIfEnabled() {
        if (!YamlConfig.config.server.ENABLE_ADMIN_SERVICE_BOT) return;
        if (bot != null) return;

        String name = YamlConfig.config.server.ADMIN_SERVICE_BOT_CHARACTER;
        int worldId = YamlConfig.config.server.ADMIN_SERVICE_BOT_WORLD;
        int channelId = Math.max(1, YamlConfig.config.server.ADMIN_SERVICE_BOT_CHANNEL);
        int mapId = YamlConfig.config.server.ADMIN_SERVICE_BOT_MAP;

        if (name == null || name.isBlank()) {
            log.warn("AdminServiceBot: character name blank; disabling.");
            return;
        }

        World w = Server.getInstance().getWorld(worldId);
        if (w == null) {
            log.warn("AdminServiceBot: world {} not found; disabling.", worldId);
            return;
        }
        Channel ch = w.getChannel(channelId);
        if (ch == null) {
            log.warn("AdminServiceBot: world {} channel {} not found; disabling.",
                    worldId, channelId);
            return;
        }

        int[] idx = lookupCharAndAccount(name);
        if (idx == null) {
            log.warn("AdminServiceBot: character {} not found (provision with service_bot.py).",
                    name);
            return;
        }
        int charId = idx[0];
        int accId = idx[1];
        int gmLevel = idx[2];
        if (gmLevel < 2) {
            log.warn("AdminServiceBot: character {} has gm={}; must be >= 2. Disabling.",
                    name, gmLevel);
            return;
        }

        try {
            Client stub = Client.createChannelClient(-1L, "127.0.0.1:service-bot", null,
                    worldId, channelId);
            stub.setAccID(accId);
            Character chr = Character.loadCharFromDB(charId, stub, true);
            stub.setPlayer(chr);

            // Park on the configured map without routing packets. We don't
            // call the full changeMap / addPlayer flow (that triggers spawn
            // packets, quest hooks, etc.) — we just want the character object
            // to be findable in PlayerStorage.
            try {
                chr.setMapId(mapId);
            } catch (Throwable t) {
                log.debug("AdminServiceBot: could not set mapId {}: {}", mapId, t.toString());
            }
            ch.getPlayerStorage().addPlayer(chr);

            bot = chr;
            botClient = stub;
            log.info("AdminServiceBot ready: {} (charId={}, world={}, ch={}, gm={})",
                    name, charId, worldId, channelId, gmLevel);
        } catch (SQLException e) {
            log.warn("AdminServiceBot: failed to load char {}", name, e);
        } catch (Throwable t) {
            log.warn("AdminServiceBot: unexpected init failure", t);
        }
    }

    public static synchronized void stop() {
        if (bot == null) return;
        try {
            int worldId = YamlConfig.config.server.ADMIN_SERVICE_BOT_WORLD;
            int channelId = Math.max(1, YamlConfig.config.server.ADMIN_SERVICE_BOT_CHANNEL);
            Server.getInstance().getWorld(worldId).getChannel(channelId)
                    .getPlayerStorage().removePlayer(bot.getId());
        } catch (Throwable ignored) { }
        bot = null;
        botClient = null;
    }

    public static String currentBotName() {
        return bot == null ? null : bot.getName();
    }

    private static int[] lookupCharAndAccount(String name) {
        String sql =
                "SELECT c.id AS cid, c.accountid AS aid, a.gm AS gm " +
                "FROM characters c LEFT JOIN accounts a ON a.id = c.accountid " +
                "WHERE c.name = ? LIMIT 1";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new int[]{rs.getInt("cid"), rs.getInt("aid"), rs.getInt("gm")};
            }
        } catch (SQLException e) {
            log.debug("AdminServiceBot: lookup failed", e);
            return null;
        }
    }
}
