package server.economy.ledger;

import client.inventory.Item;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import server.maps.HiredMerchant;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The price basket (docs/ECONOMY_PLAN.md E0): each basket item's price for a day, from the day's player-shop sales
 * when it had economy.CPI_MIN_SALES of them, else the median of what is listed in the Free Market now, else the price
 * index (living_price_index). The index of a day is the geometric mean of each priced item's price over its first
 * recorded one, x 100: a basket that costs what it cost on its first day reads 100.
 */
public final class PriceBasket {
    public enum Source { SALES, LISTINGS, INDEX }

    public record Price(int itemId, long price, Source source, int samples) {
    }

    private static final int FREE_MARKET_ENTRANCE = 910_000_000;
    private static final int FREE_MARKET_ROOMS = 22;

    private PriceBasket() {
    }

    public static List<Integer> basket(String csv) {
        List<Integer> ids = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                ids.add(Integer.parseInt(part.trim()));
            }
        }
        return ids;
    }

    /** Prices the basket for {@code day} (UTC). */
    public static List<Price> price(LocalDate day, List<Integer> basket, int minSales) throws SQLException {
        Map<Integer, List<Long>> sales = sales(day, basket);
        Map<Integer, List<Long>> listings = listings(basket);
        Map<Integer, Long> index = indexPrices(basket);
        List<Price> out = new ArrayList<>();
        for (int itemId : basket) {
            List<Long> sold = sales.getOrDefault(itemId, List.of());
            List<Long> listed = listings.getOrDefault(itemId, List.of());
            if (sold.size() >= minSales) {
                out.add(new Price(itemId, median(sold), Source.SALES, sold.size()));
            } else if (!listed.isEmpty()) {
                out.add(new Price(itemId, median(listed), Source.LISTINGS, listed.size()));
            } else if (index.containsKey(itemId)) {
                out.add(new Price(itemId, index.get(itemId), Source.INDEX, 0));
            }
        }
        return out;
    }

    /** The basket index: 100 x the geometric mean of today's price over each item's first recorded price. */
    public static Double index(List<Price> today, Map<Integer, Long> firstPrices) {
        double logSum = 0d;
        int n = 0;
        for (Price price : today) {
            Long first = firstPrices.get(price.itemId());
            if (first != null && first > 0 && price.price() > 0) {
                logSum += Math.log((double) price.price() / first);
                n++;
            }
        }
        return n == 0 ? null : 100d * Math.exp(logSum / n);
    }

    static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    /** Unit prices of the day's player-shop sales, from the economy journal (item=, quantity=, gross=). */
    private static Map<Integer, List<Long>> sales(LocalDate day, List<Integer> basket) throws SQLException {
        Map<Integer, List<Long>> out = new HashMap<>();
        String sql = "SELECT summary FROM economy_transaction_journal WHERE operation_kind = 'PLAYER_SHOP_SALE'"
                + " AND status = 'COMMITTED' AND created_at >= ? AND created_at < ?";
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()));
            ps.setTimestamp(2, Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Long> fields = fields(rs.getString(1));
                    Long item = fields.get("item");
                    Long quantity = fields.get("quantity");
                    Long gross = fields.get("gross");
                    if (item != null && quantity != null && gross != null && quantity > 0
                            && basket.contains(item.intValue())) {
                        out.computeIfAbsent(item.intValue(), id -> new ArrayList<>()).add(gross / quantity);
                    }
                }
            }
        }
        return out;
    }

    static Map<String, Long> fields(String summary) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String part : summary.split(" ")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                try {
                    out.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1)));
                } catch (NumberFormatException ignored) {
                    // not a number (escrow=, shop ids are numbers too): skipped
                }
            }
        }
        return out;
    }

    /** Unit asking prices of every open Free Market listing (player shops and hired merchants) on every channel. */
    private static Map<Integer, List<Long>> listings(List<Integer> basket) {
        Map<Integer, List<Long>> out = new HashMap<>();
        for (World world : Server.getInstance().getWorlds()) {
            for (Channel channel : world.getChannels()) {
                for (int room = FREE_MARKET_ENTRANCE; room <= FREE_MARKET_ENTRANCE + FREE_MARKET_ROOMS; room++) {
                    if (!channel.getMapFactory().isMapLoaded(room)) {
                        continue;
                    }
                    MapleMap map = channel.getMapFactory().getMap(room);
                    for (PlayerShop shop : map.getAllPlayerShops()) {
                        for (PlayerShop.ListingView view : shop.listingSnapshot()) {
                            if (basket.contains(view.itemId()) && view.perBundle() > 0) {
                                out.computeIfAbsent(view.itemId(), id -> new ArrayList<>())
                                        .add((long) view.bundlePrice() / view.perBundle());
                            }
                        }
                    }
                }
                listMerchants(channel.getHiredMerchants().values(), basket, out);
            }
        }
        return out;
    }

    private static void listMerchants(Collection<HiredMerchant> merchants, List<Integer> basket,
                                      Map<Integer, List<Long>> out) {
        for (HiredMerchant merchant : merchants) {
            for (PlayerShopItem listing : merchant.getItems()) {
                Item item = listing.getItem();
                if (listing.isExist() && listing.getBundles() > 0 && item != null && item.getQuantity() > 0
                        && basket.contains(item.getItemId())) {
                    out.computeIfAbsent(item.getItemId(), id -> new ArrayList<>())
                            .add((long) listing.getPrice() / item.getQuantity());
                }
            }
        }
    }

    private static Map<Integer, Long> indexPrices(List<Integer> basket) throws SQLException {
        Map<Integer, Long> out = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT item_id, price FROM living_price_index");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (basket.contains(rs.getInt(1))) {
                    out.put(rs.getInt(1), rs.getLong(2));
                }
            }
        }
        return out;
    }
}
