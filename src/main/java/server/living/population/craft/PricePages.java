package server.living.population.craft;

import client.Character;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.economy.ledger.PriceBasket;
import server.living.population.Market;
import server.living.population.Population;
import server.living.population.needs.NeedBoards;
import server.living.population.paper.PaperDesk;
import server.living.resident.LivingTalk;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * The board's prices page (docs/WORLD_SYSTEMS_PLAN.md N8.4, decision D14: the 43 basket goods, no more): for each, its
 * price at the last close, a week and a month before it, in words and numbers, from economy_cpi_daily; and the month's
 * biggest move, told to the paper once a week. Shown while BUY_ORDERS_ENABLED is on.
 */
public final class PricePages {
    private static final Logger log = LoggerFactory.getLogger(PricePages.class);
    /** A day with no close takes the last one within this many days before it. */
    static final int SLACK_DAYS = 3;
    /** A month's move the paper tells of. */
    static final int NEWS_PERCENT = 20;
    private static final String NEWS = "market.month";

    /** One basket item's recorded price. */
    public record Row(LocalDate day, int itemId, long price) {
    }

    /** One item's line: 0 where nothing was recorded. */
    public record Line(int itemId, long now, long week, long month) {
    }

    /** Where the rows come from; tests bring their own. */
    interface Source {
        List<Row> since(LocalDate from);

        /** Whether the paper was told a month's move in the last six days. */
        boolean toldLately();
    }

    private final Population population;
    private final Source source;
    private final IntFunction<String> names;
    private LocalDate newsDay;
    /** The board's lines, read again each {@link #READ_MS}; the board only shows them (no query on a click). */
    private volatile List<Line> shown = List.of();
    private long readAtMs;
    private static final long READ_MS = 30 * 60_000L;

    public PricePages(Population population) {
        this(population, new Sql(), itemId -> server.ItemInformationProvider.getInstance().getName(itemId));
    }

    PricePages(Population population, Source source, IntFunction<String> names) {
        this.population = population;
        this.source = source;
        this.names = names;
    }

    public boolean on() {
        return population.config().BUY_ORDERS_ENABLED;
    }

    private static List<Integer> basket() {
        return PriceBasket.basket(YamlConfig.config.economy.CPI_BASKET);
    }

    /** The basket's lines as of {@code today}, in basket order; items with no recent price are left out. */
    List<Line> lines(List<Integer> basket, LocalDate today) {
        Map<Integer, List<Row>> rows = new HashMap<>();
        for (Row row : source.since(today.minusDays(30L + 2L * SLACK_DAYS))) {
            rows.computeIfAbsent(row.itemId(), id -> new ArrayList<>()).add(row);
        }
        List<Line> out = new ArrayList<>();
        for (int itemId : basket) {
            List<Row> own = rows.getOrDefault(itemId, List.of());
            Row now = at(own, today);
            if (now != null) {
                Row week = at(own, now.day().minusDays(7));
                Row month = at(own, now.day().minusDays(30));
                out.add(new Line(itemId, now.price(), week == null ? 0L : week.price(), month == null ? 0L : month.price()));
            }
        }
        return out;
    }

    /** The last price on {@code day} or up to {@link #SLACK_DAYS} before it. */
    private static Row at(List<Row> rows, LocalDate day) {
        return rows.stream().filter(row -> row.price() > 0L && !row.day().isAfter(day)
                && !row.day().isBefore(day.minusDays(SLACK_DAYS))).max(Comparator.comparing(Row::day)).orElse(null);
    }

    /** "up a little", "down a lot" against {@code then}. */
    static String words(long now, long then) {
        if (then <= 0L) {
            return "new here";
        }
        double change = (now - then) / (double) then;
        double size = Math.abs(change);
        if (size < 0.02d) {
            return "about the same";
        }
        String much = size < 0.10d ? " a little" : size < 0.25d ? "" : size < 0.60d ? " a lot" : " wildly";
        return (change > 0 ? "up" : "down") + much;
    }

    /** One item's page text. */
    static String text(Line line) {
        return "#i" + line.itemId() + "# #e#t" + line.itemId() + "##n\r\nToday: #b" + Market.formatPrice(line.now()) + "#k."
                + "\r\nA week ago: " + then(line.now(), line.week()) + "\r\nA month ago: " + then(line.now(), line.month());
    }

    private static String then(long now, long then) {
        return then <= 0L ? "nobody wrote it down." : Market.formatPrice(then) + ", so " + words(now, then) + ".";
    }

    public void board(Character player, Runnable back) {
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Line line : shown) {
            options.add(new LivingTalk.Option("#t" + line.itemId() + "#: " + Market.formatPrice(line.now())
                    + (line.week() > 0L ? ", " + words(line.now(), line.week()) : ""),
                    () -> LivingTalk.open(player, NeedBoards.BOARD_NPC).back(() -> board(player, back)).ok(text(line)).show()));
        }
        LivingTalk talk = LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back);
        if (options.isEmpty()) {
            talk.ok("Nobody has written the prices down yet.").show();
        } else {
            talk.menu("What things go for, and since last week:", options).show();
        }
    }

    /** The population's minute: the board's lines are read again now and then, and the day's news looked for. */
    public void minute(long nowMs) {
        if (!on()) {
            return;
        }
        LocalDate today = java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate();
        try {
            if (readAtMs == 0L || nowMs - readAtMs >= READ_MS) {
                readAtMs = nowMs;
                shown = lines(basket(), today);
            }
            news(today);
        } catch (RuntimeException failure) {
            log.warn("prices page minute failed", failure);
        }
    }

    /** Once a day: the month's biggest move of a basket good is filed with the paper, unless one was this week. */
    void news(LocalDate today) {
        if (!on() || today.equals(newsDay)) {
            return;
        }
        newsDay = today;
        Line biggest = null;
        for (Line line : shown) {
            if (line.month() > 0L && (biggest == null || move(line) > move(biggest))) {
                biggest = line;
            }
        }
        String name = biggest == null ? null : names.apply(biggest.itemId());
        if (name == null || move(biggest) * 100.0d < NEWS_PERCENT || source.toldLately()) {
            return;
        }
        PaperDesk.file(NEWS, 25, Map.of("item", name, "way", biggest.now() > biggest.month() ? "up" : "down",
                "percent", String.valueOf(Math.round(move(biggest) * 100.0d))));
    }

    private static double move(Line line) {
        return Math.abs(line.now() - line.month()) / (double) line.month();
    }

    private static final class Sql implements Source {
        @Override
        public List<Row> since(LocalDate from) {
            List<Row> out = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT day, item_id, price FROM economy_cpi_daily WHERE day >= ?")) {
                ps.setDate(1, Date.valueOf(from));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Row(rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getLong(3)));
                    }
                }
            } catch (SQLException failure) {
                log.warn("economy_cpi_daily read failed", failure);
            }
            return out;
        }

        @Override
        public boolean toldLately() {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT 1 FROM living_event_log WHERE kind = ? AND detail LIKE ?"
                         + " AND at > NOW() - INTERVAL 6 DAY LIMIT 1")) {
                ps.setString(1, PaperDesk.ITEM_EVENT);
                ps.setString(2, "%" + NEWS + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException failure) {
                log.warn("the week's price news could not be read; none is filed", failure);
                return true;
            }
        }
    }
}
