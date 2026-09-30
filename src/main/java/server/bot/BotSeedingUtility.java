package server.bot;

import client.DefaultDates;
import config.BotEconomyConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.Map;

/**
 * One-shot seeder that creates the bot character pool (BOT_ECONOMY.md §
 * Bot character seeding). Idempotent — existing bots are untouched; only
 * missing slots are filled in.
 *
 * <p>Invoked by {@code !boteconomy seed} GM command or standalone via the
 * server's maintenance mode.
 *
 * <p>Pool size and persona distribution come from the SMT 6 target counts in
 * bot-personas.yml (the largest configured pool); bots beyond the live SMT
 * stay dormant via the persona's {@code activeFromSmt} gate.
 */
public final class BotSeedingUtility {
    private static final Logger log = LoggerFactory.getLogger(BotSeedingUtility.class);

    private BotSeedingUtility() {}

    public static SeedResult seed() {
        BotEconomyConfig cfg = YamlConfig.config.bot_economy;
        Map<BotPersona, Integer> targets = personaTargetCountsAtSmt6();
        Map<BotPersona, Integer> existing = existingCountByPersona();

        BotNameGenerator nameGen = new BotNameGenerator();
        int created = 0;
        int skipped = 0;

        for (var entry : targets.entrySet()) {
            BotPersona persona = entry.getKey();
            int target = entry.getValue();
            int have = existing.getOrDefault(persona, 0);
            int need = Math.max(0, target - have);
            log.info("Seeding persona {}: target={} existing={} need={}", persona, target, have, need);

            for (int i = 0; i < need; i++) {
                String name = nameGen.generate(persona);
                try {
                    int accountId = ensureBotAccount(name);
                    int charId = createBotCharacter(accountId, name, persona);
                    if (charId > 0) created++;
                    else skipped++;
                } catch (SQLException e) {
                    log.warn("Bot seed failed for {} persona {} ({})", name, persona, e.toString());
                    skipped++;
                }
            }
        }

        int total = countAllBots();
        if (cfg.BOT_POOL_SIZE > 0 && total > cfg.BOT_POOL_SIZE) {
            log.warn("Seeded bots ({}) exceed BOT_POOL_SIZE ({}); cap is advisory — excess remains",
                    total, cfg.BOT_POOL_SIZE);
        }
        return new SeedResult(created, skipped, total);
    }

    private static Map<BotPersona, Integer> personaTargetCountsAtSmt6() {
        Map<BotPersona, Integer> m = new EnumMap<>(BotPersona.class);
        m.put(BotPersona.NPCVendor,       16);
        m.put(BotPersona.ScrollMerchant,  35);
        m.put(BotPersona.BulkVendor,      11);
        m.put(BotPersona.WarriorGear,     19);
        m.put(BotPersona.MagicianGear,    19);
        m.put(BotPersona.ArcherGear,      19);
        m.put(BotPersona.ThiefGear,       19);
        m.put(BotPersona.PirateGear,      19);
        m.put(BotPersona.RareSpecialist,  11);
        m.put(BotPersona.AccessoryVendor, 11);
        m.put(BotPersona.Generalist,       0);
        return m;
    }

    private static Map<BotPersona, Integer> existingCountByPersona() {
        Map<BotPersona, Integer> m = new EnumMap<>(BotPersona.class);
        for (BotPersona p : BotPersona.values()) m.put(p, 0);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT bot_persona, COUNT(*) FROM characters WHERE bot_flag = 1 GROUP BY bot_persona");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String raw = rs.getString(1);
                if (raw == null) continue;
                try {
                    m.put(BotPersona.valueOf(raw), rs.getInt(2));
                } catch (IllegalArgumentException ignored) {
                    // unknown persona value — skip
                }
            }
        } catch (SQLException e) {
            log.warn("existingCountByPersona failed ({})", e.toString());
        }
        return m;
    }

    private static int countAllBots() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT COUNT(*) FROM characters WHERE bot_flag = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warn("countAllBots failed ({})", e.toString());
        }
        return 0;
    }

    private static int ensureBotAccount(String botName) throws SQLException {
        String accountName = "bot_" + botName.toLowerCase();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement sel = con.prepareStatement(
                    "SELECT id FROM accounts WHERE name = ? LIMIT 1")) {
                sel.setString(1, accountName);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
            // Insert a new account. Banned=1 is belt-and-suspenders; the bot_flag
            // guard in Client.login also rejects login attempts before password check.
            try (PreparedStatement ins = con.prepareStatement(
                    "INSERT INTO accounts (name, password, birthday, tempban, bot_flag, banned) " +
                    "VALUES (?, 'bot-no-login', '1970-01-01', ?, 1, 1)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, accountName);
                // Not '1970-01-01 00:00:00': in a UTC session that is epoch 0, below TIMESTAMP's range.
                ins.setTimestamp(2, Timestamp.valueOf(DefaultDates.getTempban()));
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    if (keys.next()) return keys.getInt(1);
                }
            }
        }
        return -1;
    }

    private static int createBotCharacter(int accountId, String name, BotPersona persona) throws SQLException {
        if (accountId <= 0) return -1;
        String sql =
                "INSERT INTO characters (accountid, world, name, level, job, map, bot_flag, bot_persona, gender) " +
                "VALUES (?, 0, ?, 1, 0, 910000000, 1, ?, 0)";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, accountId);
            ps.setString(2, name);
            ps.setString(3, persona.name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate")) {
                log.debug("Bot character name {} already exists, skipping", name);
                return -1;
            }
            throw e;
        }
        return -1;
    }

    public record SeedResult(int created, int skipped, int totalBots) {}

    // ────────────────────────────────────────────────────────────────
    // Item-seed path: pre-populates bot_inventory with enough pending
    // items that newly-seeded merchants can stock immediately instead
    // of waiting 15-30 minutes for the drop-sim tick to accumulate.
    // Each persona gets a curated pool of known-good v83 item ids.
    // ────────────────────────────────────────────────────────────────

    /** Curated item pools per persona. IDs chosen from standard v83 ranges
     *  that exist in the community Mob.wz; keeps the seed robust even if
     *  specific items are missing (missing WZ entries render as blank
     *  but don't crash the merchant). */
    private static final Map<BotPersona, int[]> PERSONA_ITEM_POOL = buildPersonaPool();

    private static Map<BotPersona, int[]> buildPersonaPool() {
        Map<BotPersona, int[]> m = new EnumMap<>(BotPersona.class);

        m.put(BotPersona.ScrollMerchant, new int[]{
                2040002, 2040402, 2040602, 2040702, 2040802, 2040902, 2041002,
                2043002, 2043102, 2043202, 2043302, 2043702, 2043802,
                2044102, 2044202, 2044302, 2044402, 2044502, 2044602, 2044702
        });
        m.put(BotPersona.WarriorGear, new int[]{
                1302000, 1302004, 1302020, 1302030, 1302050,
                1312004, 1312009, 1312013, 1322000, 1322005,
                1402001, 1402009, 1412001, 1412009, 1422001, 1422009,
                1432001, 1432009, 1442001, 1442009,
                1040002, 1040036, 1040103, 1060002, 1060026,
                1002140, 1002141, 1002159
        });
        m.put(BotPersona.MagicianGear, new int[]{
                1372000, 1372003, 1372004, 1372005, 1372012, 1372013,
                1382000, 1382003, 1382009, 1382012, 1382017,
                1002275, 1002276, 1050018, 1050020,
                1050003, 1050004, 1050005, 1050006
        });
        m.put(BotPersona.ArcherGear, new int[]{
                1452000, 1452003, 1452009, 1452013, 1452020,
                1462000, 1462003, 1462009, 1462013, 1462020,
                1040036, 1040089, 1002207, 1002208,
                1060002, 1060089
        });
        m.put(BotPersona.ThiefGear, new int[]{
                1332000, 1332004, 1332009, 1332013, 1332020,
                1472000, 1472003, 1472009, 1472013,
                1040089, 1040090, 1002277, 1002278
        });
        m.put(BotPersona.PirateGear, new int[]{
                1482000, 1482003, 1482009, 1482013,
                1492000, 1492003, 1492009, 1492013,
                1050018, 1002565, 1002566
        });
        m.put(BotPersona.AccessoryVendor, new int[]{
                1112000, 1112001, 1112002, 1112004, 1112005, 1112006,
                1122000, 1122001, 1122002, 1122003,
                1032000, 1032001, 1032002, 1032003,
                1132000, 1132001, 1132002
        });
        m.put(BotPersona.BulkVendor, new int[]{
                4003000, 4003001, 4011000, 4011001, 4011002,
                4021000, 4021001, 4021002, 4021003,
                2022000, 2022003, 2022017, 2022121
        });
        m.put(BotPersona.RareSpecialist, new int[]{
                1302050, 1402039, 1412026, 1432017, 1432038,
                1452023, 1462023, 1472030,
                1122000, 1122001, 1112004, 1112005,
                2022179
        });
        m.put(BotPersona.Generalist, new int[]{
                1302020, 1382009, 1452009, 1332009, 1482009,
                2040002, 2040402, 2043002, 2044502,
                4003000, 4011001, 2022003, 1112000
        });
        // NPCVendor: skip — NPC-stock path writes its own rows with source='npc_stock'
        return m;
    }

    /**
     * Pre-populate bot_inventory with pending rows for each drop-sim persona
     * so seeded merchants can stock immediately. Idempotent-ish: existing
     * pending rows are counted toward the target; deficit is filled in.
     *
     * @param perPersonaTarget total pending rows desired per persona
     */
    public static InventorySeedResult seedInventory(int perPersonaTarget) {
        int totalCreated = 0;
        Map<BotPersona, Integer> perPersona = new EnumMap<>(BotPersona.class);
        String countSql =
                "SELECT COUNT(*) FROM bot_inventory WHERE status = 'pending' AND bot_character_id IS NULL";
        int initialPending;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(countSql);
             ResultSet rs = ps.executeQuery()) {
            initialPending = rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("seedInventory: initial count failed ({})", e.toString());
            initialPending = 0;
        }

        for (var entry : PERSONA_ITEM_POOL.entrySet()) {
            BotPersona persona = entry.getKey();
            int[] pool = entry.getValue();
            if (pool.length == 0) continue;

            int existing = countPendingMatching(pool);
            int need = Math.max(0, perPersonaTarget - existing);
            int created = insertPendingForPool(pool, need, BotSmtService.currentSmt());
            perPersona.put(persona, created);
            totalCreated += created;
            log.info("seedInventory: persona={} target={} existing_eligible={} inserted={}",
                    persona, perPersonaTarget, existing, created);
        }

        return new InventorySeedResult(totalCreated, initialPending, perPersona);
    }

    /** Count existing pending rows whose item_id is in this persona's pool. */
    private static int countPendingMatching(int[] pool) {
        if (pool.length == 0) return 0;
        StringBuilder sb = new StringBuilder(
                "SELECT COUNT(*) FROM bot_inventory " +
                "WHERE status = 'pending' AND bot_character_id IS NULL AND item_id IN (");
        for (int i = 0; i < pool.length; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        sb.append(")");
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sb.toString())) {
            for (int i = 0; i < pool.length; i++) ps.setInt(i + 1, pool[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.warn("countPendingMatching failed ({})", e.toString());
        }
        return 0;
    }

    private static int insertPendingForPool(int[] pool, int count, int smt) {
        if (count <= 0 || pool.length == 0) return 0;
        String sql =
                "INSERT INTO bot_inventory (item_id, quantity, source, status, generated_at_smt) " +
                "VALUES (?, ?, 'manual_gm', 'pending', ?)";
        int inserted = 0;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < count; i++) {
                int itemId = pool[BotRandomService.nextInt(pool.length)];
                int qty = isStackable(itemId)
                        ? BotRandomService.nextIntInclusive(3, 8)
                        : 1;
                ps.setInt(1, itemId);
                ps.setInt(2, qty);
                ps.setInt(3, smt);
                ps.addBatch();
                if ((i + 1) % 200 == 0) {
                    int[] r = ps.executeBatch();
                    for (int v : r) if (v > 0) inserted += v;
                }
            }
            int[] r = ps.executeBatch();
            for (int v : r) if (v > 0) inserted += v;
        } catch (SQLException e) {
            log.warn("insertPendingForPool failed ({})", e.toString());
        }
        return inserted;
    }

    /** Heuristic: equips (item id 10xxxxx-19xxxxx) are never stackable; the
     *  rest of our pools (scrolls, mats, consumables) stack fine. */
    private static boolean isStackable(int itemId) {
        return itemId < 1000000 || itemId >= 2000000;
    }

    public record InventorySeedResult(int totalCreated, int initialPending,
                                      Map<BotPersona, Integer> perPersona) {}
}
