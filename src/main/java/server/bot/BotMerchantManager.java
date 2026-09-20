package server.bot;

import config.BotEconomyConfig;
import config.YamlConfig;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.HiredMerchant;
import server.maps.MapleMap;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Merchant lifecycle manager (BOT_ECONOMY.md § Component 3 — Merchant Lifecycle Manager).
 *
 * <p>One tick runs four phases:
 * <ol>
 *   <li><b>Collect</b> — drain {@code characters.MerchantMesos} into the treasury.</li>
 *   <li><b>Rotate</b> — close merchants past TTL.</li>
 *   <li><b>Spawn</b> — open new merchants up to the SMT target.</li>
 *   <li><b>Jitter guard</b> — caps per-tick spawns.</li>
 * </ol>
 *
 * <p>All DB-facing work. Actual {@code HiredMerchant} instantiation and
 * attachment to the FM map is a follow-up wire-up through
 * {@code BotHiredMerchantFactory} (not yet implemented in v1 —
 * {@code spawnPhase} writes {@code bot_merchants} rows with status='opening'
 * and leaves the FM-visible instantiation to a downstream helper).
 */
public final class BotMerchantManager {
    private static final Logger log = LoggerFactory.getLogger(BotMerchantManager.class);

    private static final AtomicLong LAST_TICK_MS = new AtomicLong();

    private BotMerchantManager() {}

    public static void tick() {
        BotEconomyConfig cfg = YamlConfig.config.bot_economy;
        if (!cfg.ENABLED || !cfg.LIFECYCLE_ENABLED) return;
        long start = System.currentTimeMillis();
        try {
            if (cfg.TREASURY_COLLECTION_ENABLED) collectPhase(cfg);
            rotatePhase(cfg);
            if (!cfg.DRAIN) spawnPhase(cfg);
            LAST_TICK_MS.set(System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("BotMerchantManager.tick failed", e);
        }
    }

    public static long lastTickMs() { return LAST_TICK_MS.get(); }

    // ─── Phase 1: collect ───
    private static void collectPhase(BotEconomyConfig cfg) {
        String sql =
                "SELECT id, MerchantMesos FROM characters " +
                "WHERE bot_flag = 1 AND MerchantMesos > 0";
        long totalCollected = 0;
        int countCollected = 0;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int charId = rs.getInt("id");
                long meso = rs.getInt("MerchantMesos");
                if (meso <= 0) continue;
                BotTreasuryService.Partition p = BotTreasuryService.credit(meso);
                zeroMerchantMesos(charId);
                totalCollected += meso;
                countCollected++;
                if (log.isDebugEnabled()) {
                    log.debug("bot collect: bot={} meso={} sink={} event={} gm={} overflow={}",
                            charId, meso, p.sink(), p.eventPool(), p.gmReserve(), p.eventOverflow());
                }
            }
        } catch (SQLException e) {
            log.error("collectPhase query failed", e);
        }
        if (countCollected > 0) {
            log.info("bot lifecycle collect: bots={} total={}", countCollected, totalCollected);
        }
    }

    private static void zeroMerchantMesos(int characterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE characters SET MerchantMesos = 0 WHERE id = ? AND bot_flag = 1")) {
            ps.setInt(1, characterId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("zeroMerchantMesos failed for id {}", characterId, e);
        }
    }

    // ─── Phase 2: rotate ───
    private static void rotatePhase(BotEconomyConfig cfg) {
        int rotated = 0;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, bot_character_id FROM bot_merchants " +
                     "WHERE status = 'active' AND expires_at <= NOW()")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int merchantId = rs.getInt(1);
                    int charId = rs.getInt(2);
                    archiveMerchant(merchantId);
                    returnStockToPending(merchantId);
                    BotCharacterFactory.setNextSpawnAt(charId,
                            cfg.BOT_MERCHANT_RESPAWN_COOLDOWN_MINUTES,
                            cfg.BOT_MERCHANT_SPAWN_JITTER_PCT);
                    rotated++;
                }
            }
        } catch (SQLException e) {
            log.error("rotatePhase query failed", e);
        }
        if (rotated > 0) log.info("bot lifecycle rotate: merchants={}", rotated);
    }

    private static void archiveMerchant(int merchantId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE bot_merchants SET status = 'archived' " +
                     "WHERE id = ? AND status IN ('opening','active','closing')")) {
            ps.setInt(1, merchantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("archiveMerchant failed for id {}", merchantId, e);
        }
    }

    private static void returnStockToPending(int merchantId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE bot_inventory SET status = 'pending', bot_merchant_id = NULL, " +
                     "source = IF(source = 'drop_sim', 'rotation_return', source) " +
                     "WHERE bot_merchant_id = ? AND status = 'stocked'")) {
            ps.setInt(1, merchantId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("returnStockToPending failed for merchant {}", merchantId, e);
        }
    }

    // ─── Phase 3: spawn ───
    private static void spawnPhase(BotEconomyConfig cfg) {
        int smt = BotSmtService.currentSmt();
        int target = BotPersona.smtMerchantTarget(smt);
        int active = countActiveMerchants();
        if (active >= target) return;
        int roomToSpawn = Math.min(cfg.BOT_MERCHANT_SPAWN_PER_TICK, target - active);

        var candidates = BotCharacterFactory.loadEligibleForSpawn(roomToSpawn * 3);
        int spawned = 0;
        for (var cand : candidates) {
            if (spawned >= roomToSpawn) break;
            if (cand.persona() == null || !cand.persona().activeAt(smt)) continue;
            if (!openMerchantRecord(cand, cfg)) continue;
            spawned++;
        }
        if (spawned > 0) log.info("bot lifecycle spawn: smt={} target={} active_before={} spawned={}",
                smt, target, active, spawned);
    }

    private static int countActiveMerchants() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT COUNT(*) FROM bot_merchants WHERE status = 'active'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warn("countActiveMerchants failed ({})", e.toString());
        }
        return 0;
    }

    private static boolean openMerchantRecord(BotCharacterFactory.Snapshot snap, BotEconomyConfig cfg) {
        int permit = pickPermit(snap.persona(), cfg);
        int mapId = pickFmRoom();
        String desc = pickDescription(snap.persona());

        // Step 1: reserve a row in bot_merchants so we have a stable id for
        // the HiredMerchant/bot_inventory binding.
        int merchantId;
        String insert =
                "INSERT INTO bot_merchants " +
                "(bot_character_id, channel, world, map_id, permit_item_id, description, opened_at, expires_at, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW() + INTERVAL ? HOUR, 'active')";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, snap.id());
            ps.setInt(2, snap.channel());
            ps.setInt(3, snap.world());
            ps.setInt(4, mapId);
            ps.setInt(5, permit);
            ps.setString(6, desc);
            ps.setInt(7, cfg.BOT_MERCHANT_TTL_HOURS);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                merchantId = keys.next() ? keys.getInt(1) : -1;
            }
        } catch (SQLException e) {
            log.warn("openMerchantRecord: DB insert failed for bot {} ({})", snap.id(), e.toString());
            return false;
        }
        if (merchantId <= 0) return false;

        // Step 2: instantiate the HiredMerchant and attach it to the FM map.
        // This is the step that makes the shop visible to real players.
        HiredMerchant merchant = spawnIntoMap(snap, mapId, permit, desc);
        if (merchant == null) {
            archiveMerchant(merchantId);
            return false;
        }

        // Step 3: bind bot_inventory stock to this merchant. Pulls 3-8
        // pending items from the queue (persona-filtered) and marks them
        // stocked.
        stockFromInventory(merchant, snap, merchantId, cfg);

        markHasMerchant(snap.id(), true);
        return true;
    }

    /**
     * Construct a HiredMerchant with the owner-snapshot ctor, register it
     * with world + channel, and drop it into the FM sub-map. Returns null
     * if the map/channel isn't available.
     */
    private static HiredMerchant spawnIntoMap(BotCharacterFactory.Snapshot snap,
                                              int mapId, int permit, String desc) {
        try {
            Server srv = Server.getInstance();
            World world = srv.getWorld(snap.world());
            if (world == null) return null;
            List<Channel> channels = world.getChannels();
            if (channels.isEmpty()) return null;
            Channel ch = channels.get(Math.min(snap.channel() - 1, channels.size() - 1));
            if (ch == null) ch = channels.get(0);
            MapleMap map = ch.getMapFactory().getMap(mapId);
            if (map == null) return null;

            HiredMerchant merchant = new HiredMerchant(
                    snap.id(), snap.name(), ch.getId(), snap.world(), map, permit, desc);
            merchant.setOpen(true);
            world.registerHiredMerchant(merchant);
            ch.addHiredMerchant(snap.id(), merchant);
            map.addMapObject(merchant);
            return merchant;
        } catch (Throwable t) {
            log.warn("spawnIntoMap failed for bot {} ({})", snap.id(), t.toString());
            return null;
        }
    }

    /**
     * Move 3-8 pending bot_inventory rows onto the given merchant and add
     * each as a PlayerShopItem via HiredMerchant.addItem. Uses a simple
     * fixed price per item for v1 — the full BotPricingService integration
     * is deferred until stat-roll generation lands in DropSimulationTask.
     */
    private static void stockFromInventory(HiredMerchant merchant,
                                           BotCharacterFactory.Snapshot snap,
                                           int merchantId, BotEconomyConfig cfg) {
        int want = BotRandomService.nextIntInclusive(
                Math.max(1, cfg.BOT_MERCHANT_ITEMS_MIN),
                Math.max(cfg.BOT_MERCHANT_ITEMS_MIN, cfg.BOT_MERCHANT_ITEMS_MAX));
        // Pull a larger candidate set than we need, then filter in-memory by
        // persona eligibility. The allowlist filter is per-item-id so a JOIN
        // can't cheaply express it; 4× headroom keeps stocking persona-shaped
        // without a schema addition.
        int candidatePool = Math.max(want * 4, 20);
        String sel =
                "SELECT id, item_id, quantity FROM bot_inventory " +
                "WHERE status = 'pending' AND bot_character_id IS NULL " +
                "ORDER BY generated_at ASC LIMIT ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sel)) {
            ps.setInt(1, candidatePool);
            int bound = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && bound < want) {
                    int rowId = rs.getInt(1);
                    int itemId = rs.getInt(2);
                    if (BotItemAllowlist.isGloballyEligible(snap.persona(), itemId)) {
                        bindInventoryRow(rowId, snap.id(), merchantId);
                        bound++;
                    }
                }
            }
            // Fallback: if persona eligibility left us with < min items, bind
            // whatever's left so the merchant isn't empty. Prefer shape, but
            // empty is worse than shape drift.
            if (bound < cfg.BOT_MERCHANT_ITEMS_MIN) {
                try (PreparedStatement fb = con.prepareStatement(sel)) {
                    fb.setInt(1, want);
                    try (ResultSet rs2 = fb.executeQuery()) {
                        while (rs2.next() && bound < want) {
                            bindInventoryRow(rs2.getInt(1), snap.id(), merchantId);
                            bound++;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("stockFromInventory failed for merchant {} ({})", merchantId, e.toString());
        }
    }

    private static void bindInventoryRow(int rowId, int botCharId, int merchantId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE bot_inventory SET status = 'stocked', " +
                     "bot_character_id = ?, bot_merchant_id = ? " +
                     "WHERE id = ? AND status = 'pending'")) {
            ps.setInt(1, botCharId);
            ps.setInt(2, merchantId);
            ps.setInt(3, rowId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("bindInventoryRow failed for row {} ({})", rowId, e.toString());
        }
    }

    private static String pickDescription(BotPersona persona) {
        // v1: small inline pool. bot-descriptions.yml is loaded on seed for
        // the full pool; this fallback keeps the shop title populated even
        // before the dashboard Import-from-YAML lands.
        String[] pool = switch (persona) {
            case ScrollMerchant  -> new String[]{"Scrolls for days!", "Fresh stock", "Roll your luck"};
            case NPCVendor       -> new String[]{"Pots & returns", "Town prices", "Stock up!"};
            case BulkVendor      -> new String[]{"Stock up!", "Bulk deals", "Mats & stacks"};
            case RareSpecialist  -> new String[]{"Collector's selection", "Rare finds"};
            case AccessoryVendor -> new String[]{"Rings & earrings", "Accessory kit"};
            default              -> new String[]{"Good luck!", "Take a look", "Cheap!"};
        };
        return pool[BotRandomService.nextInt(pool.length)];
    }

    private static void markHasMerchant(int characterId, boolean on) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE characters SET HasMerchant = ? WHERE id = ? AND bot_flag = 1")) {
            ps.setInt(1, on ? 1 : 0);
            ps.setInt(2, characterId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("markHasMerchant failed for id {} ({})", characterId, e.toString());
        }
    }

    private static int pickPermit(BotPersona persona, BotEconomyConfig cfg) {
        String mode = cfg.BOT_VISUAL_MODE == null ? "distinct" : cfg.BOT_VISUAL_MODE;
        return switch (mode.toLowerCase()) {
            case "blend" -> cfg.BOT_BLEND_PERMIT_ID;
            case "hybrid" -> cfg.BOT_HYBRID_PERSONA_MAP.getOrDefault(persona.name(),
                    cfg.BOT_HYBRID_PERSONA_MAP.getOrDefault("default", cfg.BOT_BLEND_PERMIT_ID));
            default -> {
                var pool = cfg.BOT_DISTINCT_PERMIT_POOL;
                if (pool == null || pool.isEmpty()) yield cfg.BOT_BLEND_PERMIT_ID;
                yield pool.get(BotRandomService.nextInt(pool.size()));
            }
        };
    }

    /** FM sub-rooms 910000001 - 910000022. */
    private static int pickFmRoom() {
        return 910000001 + BotRandomService.nextInt(22);
    }

    /** Close all active bot merchants — invoked by {@code !boteconomy closeall} / emergency pause. */
    public static int forceCloseAll() {
        int count = 0;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE bot_merchants SET status = 'archived' WHERE status = 'active'")) {
            count = ps.executeUpdate();
            try (PreparedStatement ps2 = con.prepareStatement(
                    "UPDATE characters SET HasMerchant = 0 WHERE bot_flag = 1")) {
                ps2.executeUpdate();
            }
            try (PreparedStatement ps3 = con.prepareStatement(
                    "UPDATE bot_inventory SET status = 'pending', bot_merchant_id = NULL, " +
                    "source = IF(source = 'drop_sim', 'rotation_return', source) " +
                    "WHERE status = 'stocked'")) {
                ps3.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("forceCloseAll failed", e);
        }
        return count;
    }
}
