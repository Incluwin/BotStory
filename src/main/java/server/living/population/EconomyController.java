package server.living.population;

import client.Character;
import config.EconomyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.looting.AgentLootPolicy;
import server.economy.ledger.EconomyLedger;
import server.maps.MapItem;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * The economy's controller (docs/ECONOMY_PLAN.md E4). At each daily close it sets the next day's citizen levers from
 * the day's numbers, within the bounds in economy.CONTROLLER_*, logs each setting to economy_controller_daily, and
 * applies it unless CONTROLLER_SHADOW. A restart keeps the last applied setting.
 *
 * <ul>
 * <li><b>Pickup selectivity</b>: the share of monster equip drops a citizen picks up (AgentLootPolicy.Selectivity).
 * Citizens' NPC sales scale with it, so the share that would have held the day's organic growth to the target is
 * today's share x (target meso - everything but those sales) / those sales; it moves toward that by at most a step.</li>
 * <li><b>Luxury spending</b>: the daily chance a citizen well over the meso its level should carry spends on a
 * luxury. It rises only while pickup cannot hold growth down alone, and eases off before pickup rises again.</li>
 * <li><b>Market buy budgets</b>: a scale on MARKET_BUY_BUDGET_PER_DAY, moved against the basket index's day-on-day
 * change outside CONTROLLER_CPI_BAND. It moves meso between holders only; the supply is the other two's.</li>
 * </ul>
 */
final class EconomyController implements AgentLootPolicy.Selectivity {
    private static final Logger log = LoggerFactory.getLogger(EconomyController.class);

    record Levers(double pickupShare, double luxuryRate, double budgetScale) {
        static final Levers NEUTRAL = new Levers(1.0d, 0.0d, 1.0d);
    }

    /** One close's setting: the organic growth and basket change it read (null when unknown) and the levers. */
    record Decision(LocalDate day, Double growth, Double cpiChange, Levers levers) { }

    private final Population population;
    private final EconomyConfig config;
    private volatile Levers levers = Levers.NEUTRAL;

    EconomyController(Population population, EconomyConfig config) {
        this.population = population;
        this.config = config;
    }

    Levers levers() {
        return levers;
    }

    /**
     * At start: the last applied setting, or neutral with the controller off; then the latest close, when it never heard
     * it (the server stopped between the close and its setting, or the ledger closed a missed day at this start).
     */
    void start() {
        apply(config.CONTROLLER_ENABLED ? lastApplied() : Levers.NEUTRAL);
        if (!config.CONTROLLER_ENABLED) {
            return;
        }
        try {
            LocalDate closed = EconomyLedger.lastClosedDay();
            if (unheard(closed, lastDecidedDay(), LocalDate.now(ZoneOffset.UTC))) {
                EconomyLedger.Close close = EconomyLedger.readClose(closed);
                if (close != null) {
                    log.info("economy controller: the close of {} was never heard; setting from it now", closed);
                    onClose(close);
                }
            }
        } catch (SQLException failure) {
            log.warn("economy controller: the unheard-close check failed", failure);
        }
    }

    /** A close to set from at start: one after the last setting, and recent (yesterday's or the day before's). */
    static boolean unheard(LocalDate closed, LocalDate decided, LocalDate today) {
        return closed != null && (decided == null || closed.isAfter(decided)) && !closed.isBefore(today.minusDays(2));
    }

    private static LocalDate lastDecidedDay() throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT MAX(day) FROM economy_controller_daily");
             ResultSet rs = ps.executeQuery()) {
            Date day = rs.next() ? rs.getDate(1) : null;
            return day == null ? null : day.toLocalDate();
        }
    }

    /** From the ledger's daily close (EconomyLedger.onClose), on its timer thread. */
    void onClose(EconomyLedger.Close close) {
        if (!config.CONTROLLER_ENABLED) {
            return;
        }
        Decision decision = decide(close, levers, config);
        boolean applied = !config.CONTROLLER_SHADOW;
        save(decision, applied);
        log.info("economy controller {}{}: growth {} (target {}), basket {}, pickup {}, luxury {}, buy budget x{}",
                decision.day(), applied ? "" : " (shadow)", percent(decision.growth()),
                percent(config.CONTROLLER_TARGET_GROWTH), percent(decision.cpiChange()),
                round(decision.levers().pickupShare()), round(decision.levers().luxuryRate()),
                round(decision.levers().budgetScale()));
        if (applied) {
            apply(decision.levers());
        }
    }

    static Decision decide(EconomyLedger.Close close, Levers now, EconomyConfig config) {
        Double growth = close.supply() > 0L ? (double) close.organicNet() / close.supply() : null;
        double pickup = now.pickupShare();
        double luxury = now.luxuryRate();
        if (growth != null) {
            double target = config.CONTROLLER_TARGET_GROWTH * close.supply();
            boolean vended = close.citizenNpcSale() > 0L;
            double wanted = vended // with no NPC sales, pickup is not what the day's growth came from
                    ? now.pickupShare() * (target - (close.organicNet() - close.citizenNpcSale())) / close.citizenNpcSale()
                    : 1.0d;
            if (growth > config.CONTROLLER_TARGET_GROWTH) {
                pickup = Math.max(config.CONTROLLER_PICKUP_MIN,
                        Math.max(wanted, now.pickupShare() - config.CONTROLLER_PICKUP_STEP));
                if (!vended || wanted < config.CONTROLLER_PICKUP_MIN) { // pickup alone cannot hold it
                    luxury = Math.min(config.CONTROLLER_LUXURY_MAX, luxury + config.CONTROLLER_LUXURY_STEP);
                }
            } else if (luxury > 0.0d) {
                luxury = Math.max(0.0d, luxury - config.CONTROLLER_LUXURY_STEP); // the sink eases off first
            } else {
                pickup = Math.min(1.0d, Math.min(Math.max(wanted, now.pickupShare()),
                        now.pickupShare() + config.CONTROLLER_PICKUP_STEP));
            }
        }
        Double cpiChange = close.cpi() == null || close.previousCpi() == null || close.previousCpi() <= 0.0d
                ? null : close.cpi() / close.previousCpi() - 1.0d;
        double budget = now.budgetScale();
        if (cpiChange != null && Math.abs(cpiChange) > config.CONTROLLER_CPI_BAND) {
            double step = Math.max(-config.CONTROLLER_BUDGET_STEP, Math.min(config.CONTROLLER_BUDGET_STEP, -cpiChange * budget));
            budget = Math.max(config.CONTROLLER_BUDGET_MIN, Math.min(config.CONTROLLER_BUDGET_MAX, budget + step));
        }
        return new Decision(close.day(), growth, cpiChange, new Levers(pickup, luxury, budget));
    }

    void apply(Levers next) {
        levers = next;
        population.market().setBuyBudgetScale(next.budgetScale());
    }

    /** Pickup selectivity: a citizen (not a player's hired hand) leaves the drops the share leaves, the same each ask. */
    @Override
    public boolean leaves(Character agent, MapItem drop) {
        double share = levers.pickupShare();
        if (share >= 1.0d) {
            return false;
        }
        Citizen citizen = population.citizen(agent.getId());
        return citizen != null && !citizen.hired() && unit(drop.getObjectId(), agent.getId()) >= share;
    }

    /** A number in [0, 1) fixed by the drop and the character (a SplitMix64 finish). */
    static double unit(int dropObjectId, int characterId) {
        long h = dropObjectId * 0x9E3779B97F4A7C15L ^ characterId * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static Levers lastApplied() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT pickup_share, luxury_rate, budget_scale"
                     + " FROM economy_controller_daily WHERE applied = 1 ORDER BY day DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new Levers(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3)) : Levers.NEUTRAL;
        } catch (SQLException failure) {
            log.warn("economy controller: no last setting read; starting neutral", failure);
            return Levers.NEUTRAL;
        }
    }

    private static void save(Decision decision, boolean applied) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("REPLACE INTO economy_controller_daily"
                     + " (day, growth, cpi_change, pickup_share, luxury_rate, budget_scale, applied)"
                     + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setDate(1, Date.valueOf(decision.day()));
            ps.setObject(2, decision.growth());
            ps.setObject(3, decision.cpiChange());
            ps.setDouble(4, decision.levers().pickupShare());
            ps.setDouble(5, decision.levers().luxuryRate());
            ps.setDouble(6, decision.levers().budgetScale());
            ps.setBoolean(7, applied);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("economy controller: the setting for {} was not saved", decision.day(), failure);
        }
    }

    /** The levers in force and the last {@code days} settings, for the admin port's /runtime/stats/controller. */
    String statsJson(int days) {
        Levers now = levers;
        StringBuilder out = new StringBuilder("{\"ok\":true,\"enabled\":").append(config.CONTROLLER_ENABLED)
                .append(",\"shadow\":").append(config.CONTROLLER_SHADOW)
                .append(",\"target_growth\":").append(config.CONTROLLER_TARGET_GROWTH)
                .append(",\"levers\":").append(json(now)).append(",\"days\":[");
        StringJoiner rows = new StringJoiner(",");
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT day, growth, cpi_change, pickup_share, luxury_rate,"
                     + " budget_scale, applied FROM economy_controller_daily WHERE day >= ? ORDER BY day")) {
            ps.setDate(1, Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(days)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add("{\"day\":\"" + rs.getDate(1).toLocalDate() + "\",\"growth\":" + rs.getObject(2)
                            + ",\"cpi_change\":" + rs.getObject(3) + ",\"levers\":"
                            + json(new Levers(rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)))
                            + ",\"applied\":" + rs.getBoolean(7) + "}");
                }
            }
        } catch (SQLException failure) {
            return "{\"ok\":false,\"error\":\"sql\"}";
        }
        return out.append(rows).append("]}").toString();
    }

    private static String json(Levers levers) {
        return "{\"pickup_share\":" + levers.pickupShare() + ",\"luxury_rate\":" + levers.luxuryRate()
                + ",\"budget_scale\":" + levers.budgetScale() + "}";
    }

    private static String percent(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.2f%%", value * 100.0d);
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
