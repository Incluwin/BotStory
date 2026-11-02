package server.living.population.casino;

import server.living.config.ContentException;
import server.living.config.ContentLoader;
import server.living.config.Node;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** scripts/config/population/casino.yaml: the venues' spots and the drop game's and gachapon's pools. */
public record CasinoContent(int gameParkMap, Point cashier, Point bar, Point dice, List<Integer> drinks, List<Table> tables,
                            int dropGameMap, Point dropHost, List<Point> dropRoute, long dropGameMs, List<DropTier> dropTiers,
                            Map<String, DropPool> dropPools, Gacha gacha, int nxSellerMap) {
    public static final String FILE = "casino.yaml";

    /** A blackjack table: where its dealer stands and its five seats. */
    public record Table(Point dealer, List<Point> seats) {
    }

    /** A drop game tier: its fee and how long its drops lie. */
    public record DropTier(String id, int fee, long despawnMs) {
    }

    /** Weighted items; {@code equip} items drop as fresh equips. */
    public record Weighted(int itemId, int weight, boolean equip) {
    }

    public record DropPool(List<Weighted> items, List<Weighted> special) {
    }

    public record Gacha(int mapId, Point spot, int reactor, int size, double fillerChance, double prizeChance,
                        List<Integer> mesos, Map<String, Integer> typeWeights, Map<String, Integer> tierWeights,
                        Map<String, Map<String, List<Integer>>> filler, List<Weighted> prizes) {
    }

    /** Reads casino.yaml from the population content directory. */
    public static CasinoContent load(Path root) {
        Path file = root.resolve(FILE);
        if (!Files.exists(file)) {
            throw new ContentException("casino content not found: " + file.toAbsolutePath());
        }
        Node node = ContentLoader.read(file);
        Node park = node.get("gamePark");
        List<Table> tables = new ArrayList<>();
        for (Node table : park.get("tables").items()) {
            List<Point> seats = new ArrayList<>();
            for (Node seat : table.get("seats").items()) {
                seats.add(point(seat));
            }
            tables.add(new Table(point(table.get("dealer")), seats));
        }
        Node drop = node.get("dropGame");
        List<Point> route = new ArrayList<>();
        for (Node step : drop.get("route").items()) {
            route.add(point(step));
        }
        List<DropTier> tiers = new ArrayList<>();
        for (Node tier : drop.get("tiers").items()) {
            tiers.add(new DropTier(tier.get("id").string(), tier.get("fee").integer(), tier.get("despawnMs").integer()));
        }
        Map<String, DropPool> pools = new LinkedHashMap<>();
        for (Map.Entry<String, Node> pool : drop.get("pools").entries().entrySet()) {
            pools.put(pool.getKey(), new DropPool(weighted(pool.getValue().get("items")),
                    weighted(pool.getValue().get("special_items"))));
        }
        Node gacha = node.get("gacha");
        Map<String, Map<String, List<Integer>>> filler = new LinkedHashMap<>();
        for (Map.Entry<String, Node> type : gacha.get("filler").entries().entrySet()) {
            Map<String, List<Integer>> tiersOf = new LinkedHashMap<>();
            for (Map.Entry<String, Node> tier : type.getValue().entries().entrySet()) {
                tiersOf.put(tier.getKey(), tier.getValue().integers());
            }
            filler.put(type.getKey(), tiersOf);
        }
        Gacha show = new Gacha(gacha.get("mapId").integer(), point(gacha.get("spot")), gacha.get("reactor").integer(),
                gacha.get("size").integer(10), gacha.get("fillerChance").decimal(0.3), gacha.get("prizeChance").decimal(0),
                gacha.get("mesos").integers(), weights(gacha.get("typeWeights")), weights(gacha.get("tierWeights")), filler,
                weighted(gacha.get("prizes")));
        return new CasinoContent(park.get("mapId").integer(), point(park.get("cashier")), point(park.get("bar")),
                point(park.get("dice")), park.get("drinks").integers(), tables, drop.get("mapId").integer(),
                point(drop.get("host")), route, drop.get("gameMs").integer(120000), tiers, pools, show,
                node.get("nxSeller").get("mapId").integer());
    }

    private static Point point(Node node) {
        List<Integer> xy = node.integers();
        if (xy.size() != 2) {
            throw new ContentException(node.path() + ": a spot is [x, y]");
        }
        return new Point(xy.get(0), xy.get(1));
    }

    private static List<Weighted> weighted(Node list) {
        List<Weighted> out = new ArrayList<>();
        if (list.isMissing()) {
            return out;
        }
        for (Node entry : list.items()) {
            out.add(new Weighted(entry.get("id").integer(), entry.get("weight").integer(1), entry.get("equip").bool(false)));
        }
        return out;
    }

    private static Map<String, Integer> weights(Node map) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : map.entries().entrySet()) {
            out.put(entry.getKey(), entry.getValue().integer());
        }
        return out;
    }
}
