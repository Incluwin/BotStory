package server.cashshop;

import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.CashShop.CashItem;
import server.CashShop.CashItemFactory;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * What the Cash Shop window sells (docs/CASH_SHOP_WINDOW.md): the stock Cash Shop's own on-sale offers
 * (Etc.wz Commodity.img, through {@link CashItemFactory}), so prices, counts, serial numbers and durations are the
 * stock shop's, not a second list to keep in step. One row per item, the better buy where an item is offered twice
 * ({@link #cheaper}). Each row sits in the window's tab and category, from the item id the way the community release's catalogue
 * generator did it.
 *
 * <p>Left out, beyond offers that are off sale or free: packages (no single item to deliver), the ETC family (quest
 * items), Maple Life, and rings, which the stock shop sells only through its couple, friendship and wedding flows with
 * a partner.
 */
public final class CashShopWindowCatalog {
    private static final Logger log = LoggerFactory.getLogger(CashShopWindowCatalog.class);

    /** One offer. {@code sn} is the stock commodity whose {@link CashItem#toItem()} is delivered. */
    public record Row(int sn, int itemId, int price, int count, int tab, int category, String name) {
    }

    /** A commodity as the catalogue reads it; {@code days} as {@link CashItem#getPeriod()} gives them. */
    public record Offer(int sn, int itemId, int price, int count, long days, boolean onSale) {
    }

    /** The categories the client window has, by (tab, category); anything else it cannot show. */
    static final Set<Integer> WINDOW_CATEGORIES = Set.of(
            key(2, 0), key(2, 1), key(2, 2), key(2, 3), key(2, 4), key(2, 5), key(2, 6), key(2, 7), key(2, 8),
            key(2, 9), key(2, 11), key(3, 1), key(3, 2), key(5, 0), key(5, 1), key(5, 2), key(5, 3), key(5, 4),
            key(5, 5), key(5, 6), key(6, 0), key(6, 1), key(6, 2), key(7, 0));

    private static final Map<Integer, Integer> EQUIP_PREFIX = equipPrefix();

    private static volatile Catalog loaded;

    private CashShopWindowCatalog() {
    }

    /** The built catalogue: rows by item id, each category's rows, and the (tab, category, count) index. */
    public record Catalog(Map<Integer, Row> byItemId, Map<Integer, List<Row>> buckets, List<int[]> index) {
        public Row row(int itemId) {
            return byItemId.get(itemId);
        }

        public List<Row> bucket(int tab, int category) {
            return buckets.getOrDefault(key(tab, category), List.of());
        }
    }

    public static Catalog get() {
        Catalog catalog = loaded;
        if (catalog == null) {
            synchronized (CashShopWindowCatalog.class) {
                catalog = loaded;
                if (catalog == null) {
                    List<Offer> offers = new ArrayList<>();
                    for (CashItem item : CashItemFactory.allItems()) {
                        offers.add(new Offer(item.getSN(), item.getItemId(), item.getPrice(), item.getCount(),
                                item.getPeriod(), item.isOnSale()));
                    }
                    ItemInformationProvider ii = ItemInformationProvider.getInstance();
                    catalog = build(offers, ii::getName);
                    loaded = catalog;
                    log.info("Cash Shop window catalogue: {} items in {} categories, from {} commodities",
                            catalog.byItemId().size(), catalog.index().size(), offers.size());
                }
            }
        }
        return catalog;
    }

    static Catalog build(Collection<Offer> offers, IntFunction<String> names) {
        Map<Integer, Row> byItem = new HashMap<>();
        Map<Integer, Offer> chosen = new HashMap<>();
        for (Offer offer : offers) {
            if (!offer.onSale() || offer.price() <= 0 || offer.count() <= 0 || !sold(offer.itemId())
                    || CashItemFactory.isPackage(offer.itemId())) {
                continue;
            }
            int[] place = categorise(offer.itemId());
            if (place == null || !WINDOW_CATEGORIES.contains(key(place[0], place[1]))) {
                continue;
            }
            String name = names.apply(offer.itemId());
            if (name == null || name.isEmpty()) {
                continue;
            }
            Row row = new Row(offer.sn(), offer.itemId(), offer.price(), offer.count(), place[0], place[1], name);
            Offer had = chosen.get(offer.itemId());
            if (had == null || cheaper(offer, had)) {
                chosen.put(offer.itemId(), offer);
                byItem.put(offer.itemId(), row);
            }
        }
        Map<Integer, List<Row>> buckets = new TreeMap<>();
        for (Row row : byItem.values()) {
            buckets.computeIfAbsent(key(row.tab(), row.category()), k -> new ArrayList<>()).add(row);
        }
        List<int[]> index = new ArrayList<>();
        for (Map.Entry<Integer, List<Row>> bucket : buckets.entrySet()) {
            bucket.getValue().sort(Comparator.comparingInt(Row::itemId));
            bucket.setValue(Collections.unmodifiableList(bucket.getValue()));
            index.add(new int[]{bucket.getKey() >> 8, bucket.getKey() & 0xFF, bucket.getValue().size()});
        }
        return new Catalog(Collections.unmodifiableMap(byItem), Collections.unmodifiableMap(buckets),
                Collections.unmodifiableList(index));
    }

    /**
     * Where the shop offers an item twice, the better buy: the lower price per item per day (a 7-day offer for 6,000 beats a
     * 1-day one for 1,000).
     */
    static boolean cheaper(Offer a, Offer b) {
        long aUnits = (long) a.count() * Math.max(1, a.days());
        long bUnits = (long) b.count() * Math.max(1, b.days());
        return (long) a.price() * bUnits < (long) b.price() * aUnits;
    }

    /** Whether the window sells this item at all (the curation beyond on sale and priced). */
    static boolean sold(int itemId) {
        int family = itemId / 1000000;
        if (family == 4 || family == 9) {
            return false; // quest items; cash packages
        }
        if (itemId / 10000 == 111) {
            return false; // rings: the stock shop sells them with a partner
        }
        return !ItemConstants.isMapleLife(itemId);
    }

    /** (tab, category) for an item, or null where the window has no home for it. */
    static int[] categorise(int itemId) {
        int prefix = itemId / 10000;
        int family = itemId / 1000000;
        if (family == 1) {
            if (prefix >= 180 && prefix <= 183) {
                return new int[]{6, 1}; // pet equipment, label and quote rings
            }
            Integer category = EQUIP_PREFIX.get(prefix);
            return category == null ? null : new int[]{2, category};
        }
        if (family != 5) {
            return null;
        }
        return switch (itemId / 1000) {
            case 5000 -> new int[]{6, 0};             // pets
            case 5010 -> new int[]{6, 1};             // pet equip
            case 5240, 5241 -> new int[]{6, 2};       // pet food
            case 5150 -> new int[]{3, 1};             // messenger
            case 5120 -> new int[]{3, 2};             // weather
            case 5152, 5160 -> new int[]{5, 3};       // facial expressions
            case 5210, 5211 -> new int[]{5, 0};       // beauty parlor coupons
            case 5220 -> new int[]{7, 0};             // gachapon tickets
            case 5170 -> new int[]{5, 6};             // character (name and look)
            case 5190 -> new int[]{5, 4};             // wedding
            case 5140 -> new int[]{5, 1};             // store permits
            case 5202, 5203, 5204 -> new int[]{5, 5}; // effects
            default -> new int[]{5, 2};               // the rest of the cash family: game
        };
    }

    private static Map<Integer, Integer> equipPrefix() {
        Map<Integer, Integer> map = new HashMap<>(Map.of(100, 0, 101, 1, 102, 2, 103, 9, 104, 4, 105, 3, 106, 5,
                107, 6, 108, 7, 109, 8));
        map.put(110, 11);
        for (int weapon = 130; weapon <= 170; weapon++) {
            map.put(weapon, 8);
        }
        return map;
    }

    static int key(int tab, int category) {
        return (tab << 8) | (category & 0xFF);
    }
}
