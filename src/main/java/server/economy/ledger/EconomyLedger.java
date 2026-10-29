package server.economy.ledger;

import config.EconomyConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/**
 * The economy ledger's clock (docs/ECONOMY_PLAN.md E0). Every minute it drains {@link MesoFlows} into
 * economy_flow_daily; at the first minute of a new UTC day it closes the day before: the supply by pool and holder
 * ({@link MesoSupply}), the net of the external accounts, the residual the two leave against the previous close, and
 * the price basket ({@link PriceBasket}); then it prunes the economy journal and outbox past
 * economy.JOURNAL_RETENTION_DAYS. It also keeps the supply at boot and the external net since, so
 * /runtime/stats/meso-supply can show the balance within the hour, before the first close.
 */
public final class EconomyLedger {
    private static final Logger log = LoggerFactory.getLogger(EconomyLedger.class);
    private static final long TICK_MS = 60_000L;
    private static final int PRUNE_BATCH = 5_000;
    static final Set<MesoAccount> EXTERNAL = EnumSet.noneOf(MesoAccount.class);

    static {
        for (MesoAccount account : MesoAccount.values()) {
            if (account.external()) {
                EXTERNAL.add(account);
            }
        }
    }

    private static volatile EconomyLedger instance;

    /**
     * A closed day as the E4 controller reads it (docs/ECONOMY_PLAN.md E4): the supply, the external net without the
     * one-off purse reset (RESET), what citizens sold to NPC shops, and the basket index and the day before's (null
     * when that day has no close).
     */
    public record Close(LocalDate day, long supply, long organicNet, long citizenNpcSale, Double cpi,
                        Double previousCpi) { }

    private static volatile Consumer<Close> closeListener;

    private final EconomyConfig config;
    private ScheduledFuture<?> timer;
    private LocalDate openDay;
    private volatile long bootSupply;
    private volatile Instant bootAt;
    private volatile long externalSinceBoot;

    private EconomyLedger(EconomyConfig config) {
        this.config = config;
    }

    /**
     * At boot, after the DB migrations and before anything loads a character. It never touches the TimerManager,
     * which is not running yet: {@link #scheduleTicks()} starts the minute tick once it is.
     */
    public static synchronized void start() {
        EconomyConfig config = YamlConfig.config.economy;
        MesoFlows.setEnabled(config.LEDGER_ENABLED);
        if (!config.LEDGER_ENABLED || instance != null) {
            return;
        }
        EconomyLedger ledger = new EconomyLedger(config);
        try {
            ledger.bootSupply = MesoSupply.take().total();
            ledger.bootAt = Instant.now();
        } catch (SQLException failure) {
            log.warn("economy ledger: no boot supply (the since-boot balance stays empty)", failure);
        }
        ledger.openDay = today();
        instance = ledger;
        log.info("economy ledger started: supply at boot {} meso", ledger.bootSupply);
    }

    /** With the server's other timers, once the TimerManager runs: the minute flush and the daily close. */
    public static synchronized void scheduleTicks() {
        EconomyLedger ledger = instance;
        if (ledger == null || ledger.timer != null) {
            return;
        }
        ledger.timer = TimerManager.getInstance().register(TimerManager.SchedulerLane.LOW_PRIORITY, ledger::tick,
                TICK_MS, TICK_MS);
    }

    /** At shutdown: the last minute's flows go in before the timers stop. */
    public static synchronized void stop() {
        EconomyLedger ledger = instance;
        if (ledger == null) {
            return;
        }
        instance = null;
        if (ledger.timer != null) {
            ledger.timer.cancel(false);
        }
        ledger.flush(today());
    }

    public static EconomyLedger get() {
        return instance;
    }

    /** Who hears each daily close after it is written (the E4 controller); null for no one. */
    public static void onClose(Consumer<Close> listener) {
        closeListener = listener;
    }

    synchronized void tick() {
        try {
            LocalDate today = today();
            if (!today.equals(openDay)) {
                LocalDate closing = openDay;
                flush(closing); // the minute that ran past midnight books to the day it began
                close(closing);
                prune();
                openDay = today;
            } else {
                flush(today);
            }
        } catch (RuntimeException failure) {
            log.warn("economy ledger tick failed", failure);
        }
    }

    private void flush(LocalDate day) {
        Map<MesoAccount, Map<MesoHolder, MesoFlows.Sum>> sums = MesoFlows.drain();
        if (sums.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO economy_flow_daily (day, account, holder, meso_in, meso_out, events) VALUES (?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE meso_in = meso_in + VALUES(meso_in), meso_out = meso_out + VALUES(meso_out),"
                + " events = events + VALUES(events)";
        long external = 0L;
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (Map.Entry<MesoAccount, Map<MesoHolder, MesoFlows.Sum>> account : sums.entrySet()) {
                for (Map.Entry<MesoHolder, MesoFlows.Sum> holder : account.getValue().entrySet()) {
                    MesoFlows.Sum sum = holder.getValue();
                    ps.setDate(1, Date.valueOf(day));
                    ps.setString(2, account.getKey().name());
                    ps.setString(3, holder.getKey().name());
                    ps.setLong(4, sum.in());
                    ps.setLong(5, sum.out());
                    ps.setLong(6, sum.events());
                    ps.addBatch();
                    if (account.getKey().external()) {
                        external += sum.net();
                    }
                }
            }
            ps.executeBatch();
            externalSinceBoot += external;
        } catch (SQLException failure) {
            log.warn("economy ledger: {} accounts' flows for {} were lost", sums.size(), day, failure);
        }
    }

    private void close(LocalDate day) {
        try {
            MesoSupply.Snapshot supply = MesoSupply.take();
            List<PriceBasket.Price> prices = PriceBasket.price(day, PriceBasket.basket(config.CPI_BASKET), config.CPI_MIN_SALES);
            try (Connection con = DatabaseConnection.getConnection()) {
                writeSupply(con, day, supply);
                writePrices(con, day, prices);
                long externalNet = externalNet(con, day);
                Long previous = previousSupply(con, day);
                Long residual = previous == null ? null : supply.total() - previous - externalNet;
                Double cpi = PriceBasket.index(prices, firstPrices(con));
                try (PreparedStatement ps = con.prepareStatement("REPLACE INTO economy_close_daily"
                        + " (day, supply, external_net, residual, cpi) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setDate(1, Date.valueOf(day));
                    ps.setLong(2, supply.total());
                    ps.setLong(3, externalNet);
                    ps.setObject(4, residual);
                    ps.setObject(5, cpi);
                    ps.executeUpdate();
                }
                log.info("economy close {}: supply {} meso, external net {}, residual {}, basket index {}",
                        day, supply.total(), externalNet, residual, cpi == null ? "-" : String.format("%.1f", cpi));
                Consumer<Close> listener = closeListener;
                if (listener != null) {
                    listener.accept(new Close(day, supply.total(),
                            externalNet - accountNet(con, day, MesoAccount.RESET),
                            accountIn(con, day, MesoAccount.NPC_SALE, MesoHolder.CITIZEN), cpi, previousCpi(con, day)));
                }
            }
        } catch (SQLException | RuntimeException failure) {
            log.warn("economy close for {} failed", day, failure);
        }
    }

    private static long accountNet(Connection con, LocalDate day, MesoAccount account) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COALESCE(SUM(meso_in - meso_out), 0)"
                + " FROM economy_flow_daily WHERE day = ? AND account = ?")) {
            ps.setDate(1, Date.valueOf(day));
            ps.setString(2, account.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static long accountIn(Connection con, LocalDate day, MesoAccount account, MesoHolder holder)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COALESCE(SUM(meso_in), 0)"
                + " FROM economy_flow_daily WHERE day = ? AND account = ? AND holder = ?")) {
            ps.setDate(1, Date.valueOf(day));
            ps.setString(2, account.name());
            ps.setString(3, holder.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static Double previousCpi(Connection con, LocalDate day) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT cpi FROM economy_close_daily WHERE day = ?")) {
            ps.setDate(1, Date.valueOf(day.minusDays(1)));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (Double) rs.getObject(1, Double.class) : null;
            }
        }
    }

    private static void writeSupply(Connection con, LocalDate day, MesoSupply.Snapshot supply) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "REPLACE INTO economy_supply_daily (day, pool, holder, meso) VALUES (?, ?, ?, ?)")) {
            for (Map.Entry<MesoSupply.Pool, Map<MesoHolder, Long>> pool : supply.meso().entrySet()) {
                for (Map.Entry<MesoHolder, Long> holder : pool.getValue().entrySet()) {
                    ps.setDate(1, Date.valueOf(day));
                    ps.setString(2, pool.getKey().name());
                    ps.setString(3, holder.getKey().name());
                    ps.setLong(4, holder.getValue());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private static void writePrices(Connection con, LocalDate day, List<PriceBasket.Price> prices) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "REPLACE INTO economy_cpi_daily (day, item_id, price, source, samples) VALUES (?, ?, ?, ?, ?)")) {
            for (PriceBasket.Price price : prices) {
                ps.setDate(1, Date.valueOf(day));
                ps.setInt(2, price.itemId());
                ps.setLong(3, price.price());
                ps.setString(4, price.source().name());
                ps.setInt(5, price.samples());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static long externalNet(Connection con, LocalDate day) throws SQLException {
        StringJoiner accounts = new StringJoiner(",");
        EXTERNAL.forEach(account -> accounts.add("'" + account.name() + "'"));
        try (PreparedStatement ps = con.prepareStatement("SELECT COALESCE(SUM(meso_in - meso_out), 0)"
                + " FROM economy_flow_daily WHERE day = ? AND account IN (" + accounts + ")")) {
            ps.setDate(1, Date.valueOf(day));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static Long previousSupply(Connection con, LocalDate day) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT supply FROM economy_close_daily WHERE day = ?")) {
            ps.setDate(1, Date.valueOf(day.minusDays(1)));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** Each basket item's first recorded price, the base of the index. */
    private static Map<Integer, Long> firstPrices(Connection con) throws SQLException {
        Map<Integer, Long> out = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT c.item_id, c.price FROM economy_cpi_daily c"
                + " JOIN (SELECT item_id, MIN(day) AS first_day FROM economy_cpi_daily GROUP BY item_id) f"
                + " ON f.item_id = c.item_id AND f.first_day = c.day");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getInt(1), rs.getLong(2));
            }
        }
        return out;
    }

    /** Journal and outbox rows past economy.JOURNAL_RETENTION_DAYS go, a batch at a time. */
    private void prune() {
        if (config.JOURNAL_RETENTION_DAYS <= 0) {
            return;
        }
        Timestamp before = Timestamp.from(Instant.now().minus(config.JOURNAL_RETENTION_DAYS, ChronoUnit.DAYS));
        long removed = 0L;
        try (Connection con = DatabaseConnection.getConnection()) {
            for (String table : List.of("economy_transaction_outbox", "economy_transaction_journal")) {
                int batch;
                do {
                    try (PreparedStatement ps = con.prepareStatement(
                            "DELETE FROM " + table + " WHERE created_at < ? LIMIT " + PRUNE_BATCH)) {
                        ps.setTimestamp(1, before);
                        batch = ps.executeUpdate();
                        removed += batch;
                    }
                } while (batch == PRUNE_BATCH);
            }
        } catch (SQLException failure) {
            log.warn("economy journal prune failed", failure);
        }
        if (removed > 0) {
            log.info("economy journal prune: {} rows older than {} days", removed, config.JOURNAL_RETENTION_DAYS);
        }
    }

    /** The supply now by pool and holder, and the balance since boot, as JSON (/runtime/stats/meso-supply). */
    public static String supplyJson() throws SQLException {
        EconomyLedger ledger = instance;
        if (ledger != null) {
            ledger.tick(); // the flows up to now, before the snapshot they are measured against
        }
        MesoSupply.Snapshot supply = MesoSupply.take();
        long characters = supply.characters();
        StringBuilder json = new StringBuilder("{\"ok\":true,\"total_mesos\":").append(supply.total())
                .append(",\"character_count\":").append(characters)
                .append(",\"mean\":").append(characters > 0 ? supply.pool(MesoSupply.Pool.PURSE) / characters : 0);
        json.append(",\"pools\":{");
        StringJoiner pools = new StringJoiner(",");
        for (MesoSupply.Pool pool : MesoSupply.Pool.values()) {
            pools.add("\"" + pool.name() + "\":" + supply.pool(pool));
        }
        json.append(pools).append("},\"holders\":{");
        StringJoiner holders = new StringJoiner(",");
        for (MesoHolder holder : MesoHolder.values()) {
            holders.add("\"" + holder.name() + "\":" + supply.holder(holder));
        }
        json.append(holders).append('}');
        if (ledger != null && ledger.bootAt != null) {
            long residual = supply.total() - ledger.bootSupply - ledger.externalSinceBoot;
            json.append(",\"since_boot\":{\"at\":\"").append(ledger.bootAt).append("\",\"supply_at_boot\":")
                    .append(ledger.bootSupply).append(",\"external_net\":").append(ledger.externalSinceBoot)
                    .append(",\"residual\":").append(residual).append('}');
        }
        return json.append('}').toString();
    }

    /** The last {@code days} days' flows by account and holder (/runtime/stats/meso-flows). */
    public static String flowsJson(int days) throws SQLException {
        EconomyLedger ledger = instance;
        if (ledger != null) {
            ledger.tick();
        }
        StringJoiner rows = new StringJoiner(",");
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT day, account, holder, meso_in, meso_out, events"
                     + " FROM economy_flow_daily WHERE day >= ? ORDER BY day, account, holder")) {
            ps.setDate(1, Date.valueOf(today().minusDays(Math.max(0, days - 1))));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String account = rs.getString(2);
                    boolean external = EXTERNAL.stream().anyMatch(a -> a.name().equals(account));
                    rows.add("{\"day\":\"" + rs.getDate(1) + "\",\"account\":\"" + account + "\",\"holder\":\""
                            + rs.getString(3) + "\",\"external\":" + external + ",\"in\":" + rs.getLong(4)
                            + ",\"out\":" + rs.getLong(5) + ",\"events\":" + rs.getLong(6) + "}");
                }
            }
        }
        return "{\"ok\":true,\"days\":" + days + ",\"flows\":[" + rows + "]}";
    }

    /** The last {@code days} daily closes and the latest basket prices (/runtime/stats/cpi). */
    public static String closesJson(int days) throws SQLException {
        StringJoiner closes = new StringJoiner(",");
        StringJoiner prices = new StringJoiner(",");
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT day, supply, external_net, residual, cpi"
                    + " FROM economy_close_daily WHERE day >= ? ORDER BY day")) {
                ps.setDate(1, Date.valueOf(today().minusDays(Math.max(0, days - 1))));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Object residual = rs.getObject(4);
                        Object cpi = rs.getObject(5);
                        closes.add("{\"day\":\"" + rs.getDate(1) + "\",\"supply\":" + rs.getLong(2) + ",\"external_net\":"
                                + rs.getLong(3) + ",\"residual\":" + residual + ",\"cpi\":" + cpi + "}");
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT item_id, price, source, samples FROM economy_cpi_daily"
                    + " WHERE day = (SELECT MAX(day) FROM economy_cpi_daily) ORDER BY item_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    prices.add("{\"item\":" + rs.getInt(1) + ",\"price\":" + rs.getLong(2) + ",\"source\":\""
                            + rs.getString(3) + "\",\"samples\":" + rs.getInt(4) + "}");
                }
            }
        }
        return "{\"ok\":true,\"closes\":[" + closes + "],\"basket\":[" + prices + "]}";
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
