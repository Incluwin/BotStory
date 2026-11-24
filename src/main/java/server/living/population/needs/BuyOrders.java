package server.living.population.needs;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.PopulationConfig;
import constants.inventory.ItemConstants;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.itcg.ItcgRecipes.Stack;
import server.living.population.ChatterEngine;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.PersonMemory;
import server.living.population.Population;
import server.living.population.craft.Profession;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Players' buy orders (docs/WORLD_SYSTEMS_PLAN.md N8.3): a player posts a material, a count and a price at a board, and
 * the whole price is held from then on (living_need, kind BUY, poster_character_id). A citizen in town or at the market
 * that carries the item sells into it when it pays more than the citizen's own reservation ({@link #reservation}); what
 * it sells comes by Duey with a note. The player may take an order down for what is left of the meso; one past its
 * deadline goes back by Duey. Crafters' own orders are plain requests (CrafterNeeds). Behind
 * population.BUY_ORDERS_ENABLED (and NEEDS_ENABLED).
 */
public final class BuyOrders {
    private static final Logger log = LoggerFactory.getLogger(BuyOrders.class);
    private static final long DAY_MS = 86_400_000L;
    /** A citizen's stall asks no less than about this share of the index, so that is what waiting is worth to it. */
    static final double RESERVE_SHARE = 0.9d;
    static final int QUANTITY_MAX = 200;
    private static final int SENDER_MAX = 13;

    private final Population population;
    private final NeedStore store;
    private final BuyOrderHands hands;
    private final IntFunction<String> names;
    private final Map<Integer, Need> open = new ConcurrentHashMap<>();
    private final Object desk = new Object();
    private final BuyOrderScreens screens;
    private volatile boolean started;

    public BuyOrders(Population population) {
        this(population, new NeedStore(), null, itemId -> {
            String name = server.ItemInformationProvider.getInstance().getName(itemId);
            return name == null ? "item " + itemId : name;
        });
    }

    BuyOrders(Population population, NeedStore store, BuyOrderHands hands, IntFunction<String> names) {
        this.population = population;
        this.store = store;
        this.hands = hands == null ? new BuyOrderHands(store) : hands;
        this.names = names;
        this.screens = new BuyOrderScreens(population, this);
    }

    public boolean on() {
        return config().BUY_ORDERS_ENABLED && population.needs() != null && population.needs().on();
    }

    private PopulationConfig config() {
        return population.config();
    }

    public BuyOrderScreens screens() {
        return screens;
    }

    /** Reads the open orders once; false when they could not be read (nothing is posted or sold then). */
    private boolean start() {
        if (started) {
            return true;
        }
        synchronized (desk) {
            if (!started) {
                List<Need> rows = store.posted();
                if (rows == null) {
                    return false;
                }
                rows.forEach(order -> open.put(order.id(), order));
                started = true;
                log.info("buy orders: {} open", rows.size());
            }
        }
        return true;
    }

    /** A player's open orders, oldest first. */
    public List<Need> of(int playerId) {
        return all().stream().filter(order -> order.posterId() == playerId).toList();
    }

    /** Every player's open orders, oldest first. */
    public List<Need> all() {
        return open.values().stream().sorted(Comparator.comparingInt(Need::id)).toList();
    }

    /** What a player may ask for: the materials of the crafters' recipes that stack. */
    List<Integer> materials() {
        return population.professions().book().materials().stream().filter(BuyOrders::orderable).toList();
    }

    private static boolean orderable(int itemId) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        return (type == InventoryType.ETC || type == InventoryType.USE) && !ItemConstants.isRechargeable(itemId);
    }

    // ---- posting and taking down -----------------------------------------------------------------

    /** The player posts an order; the whole price is held. Null when done, else why not. */
    public String post(Character player, int itemId, int quantity, long unit, long nowMs) {
        if (!on() || !start()) {
            return "The board takes no buy orders right now.";
        }
        if (!materials().contains(itemId) || quantity < 1 || quantity > QUANTITY_MAX) {
            return "The board can't take that order.";
        }
        if (unit < Math.max(1L, population.market().npcPays(itemId, 1)) || unit > Integer.MAX_VALUE / quantity) {
            return "Nobody would sell at that price.";
        }
        synchronized (desk) {
            if (of(player.getId()).size() >= config().BUY_ORDERS_PER_PLAYER) {
                return "You have " + config().BUY_ORDERS_PER_PLAYER + " orders up already.";
            }
            Need order = new Need(0, 0, NeedKind.BUY, itemId, quantity, 0, unit, "", player.getMapId(), Need.State.OPEN, 0,
                    0L, 0, true, nowMs, nowMs + config().BUY_ORDER_DAYS * DAY_MS, 0L, player.getId());
            int[] id = {0};
            String refused = hands.post(player, order, id);
            if (refused == null) {
                open.put(id[0], order.withId(id[0]));
                log.info("buy order {}: {} wants {} x{} at {}", id[0], player.getName(), itemId, quantity, unit);
            }
            return refused;
        }
    }

    /** The player takes it down and gets the meso of what has not come yet. Null when done, else why not. */
    public String takeDown(Character player, int orderId, long nowMs) {
        synchronized (desk) {
            Need order = open.get(orderId);
            if (order == null || order.posterId() != player.getId()) {
                return "That order is gone.";
            }
            String refused = hands.giveBack(player, order, order.closed(Need.State.GONE, nowMs));
            if (refused == null) {
                open.remove(orderId);
                log.info("buy order {} taken down by {}", orderId, player.getName());
            }
            return refused;
        }
    }

    // ---- the minute ----------------------------------------------------------------------------------

    /** Orders past their deadline go back; citizens in town or at the market sell into the rest, a lot a minute each. */
    public void minute(long nowMs) {
        if (!on() || !start() || open.isEmpty()) {
            return;
        }
        try {
            for (Need order : all()) {
                if (order.deadlineMs() <= nowMs) {
                    expire(order, nowMs);
                }
            }
            for (CitizenSession session : population.presence().sessions()) {
                Character agent = session.agent();
                if (!session.virtual && agent != null && mayDeal(session)) {
                    sell(session, agent, nowMs);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("buy orders minute failed", failure);
        }
    }

    /** Where a board or the market is at hand, and its goods are in its bag (not on its stall). */
    private static boolean mayDeal(CitizenSession session) {
        return !session.stallOpen && !session.citizen.hired() && (session.activity == CitizenSession.Activity.TOWN
                || session.activity == CitizenSession.Activity.MARKET);
    }

    private void expire(Need order, long nowMs) {
        synchronized (desk) {
            Need current = open.get(order.id());
            if (current != null && hands.mailBack(current, current.closed(Need.State.EXPIRED, nowMs),
                    "Your buy order for " + names.apply(current.itemId()) + " came down. Here is what was left.")) {
                open.remove(current.id());
                log.info("buy order {} of {} expired", current.id(), current.posterId());
                tell(current.posterId());
            }
        }
    }

    /** What the item is worth to a citizen that keeps it: what an NPC shop pays, or its least stall price. */
    long reservation(int itemId) {
        return Math.max(population.market().npcPays(itemId, 1),
                Math.round(population.market().price(itemId) * RESERVE_SHARE));
    }

    /** The citizen sells one lot into the best-paying order that beats its reservation; true when it did. */
    boolean sell(CitizenSession session, Character agent, long nowMs) {
        Need best = null;
        for (Need order : all()) {
            if ((best == null || order.offer() > best.offer()) && hands.lot(agent, order.itemId(), order.remaining()) != null
                    && order.offer() > reservation(order.itemId()) && !keeps(session, order.itemId())) {
                best = order;
            }
        }
        if (best == null) {
            return false;
        }
        synchronized (desk) {
            Need order = open.get(best.id());
            Item lot = order == null ? null : hands.lot(agent, order.itemId(), order.remaining());
            if (lot == null) {
                return false;
            }
            int count = lot.getQuantity();
            boolean done = count >= order.remaining();
            Need after = new Need(order.id(), 0, order.kind(), order.itemId(), order.quantity(), order.delivered() + count,
                    order.offer(), order.detail(), order.mapId(), done ? Need.State.MET : Need.State.OPEN, 0, 0L,
                    agent.getId(), true, order.createdAtMs(), order.deadlineMs(), done ? nowMs : 0L, order.posterId());
            if (!hands.sell(agent, order, after, count, sender(session.citizen), note(session, order, count))) {
                return false;
            }
            if (done) {
                open.remove(order.id());
            } else {
                open.put(order.id(), after);
            }
            log.info("buy order {}: citizen {} sells {} x{} at {}", order.id(), session.name(), order.itemId(), count,
                    order.offer());
            session.journal.add("market", "sold " + count + " " + names.apply(order.itemId()) + " to a buy order");
            if (population.memories() != null) {
                population.memories().record(session.characterId, order.posterId(), PersonMemory.Kind.SOLD, session.mapId,
                        names.apply(order.itemId()), 1);
            }
            tell(order.posterId());
            return true;
        }
    }

    /** A crafter keeps what its own trade takes. */
    private boolean keeps(CitizenSession session, int itemId) {
        Profession trade = population.professions().of(session.citizen);
        return trade != null && population.professions().book().of(trade).stream()
                .anyMatch(recipe -> recipe.inputs().stream().map(Stack::itemId).anyMatch(id -> id == itemId));
    }

    private String note(CitizenSession session, Need order, int count) {
        String line = population.chatter().compose(ChatterEngine.Event.of("need.buy.note", session.mapId, session.channel)
                .self(session).item(names.apply(order.itemId()).toLowerCase(Locale.ROOT))
                .slot("count", String.valueOf(count)));
        return line == null ? "For your order at the board." : line;
    }

    private static String sender(Citizen citizen) {
        return citizen.name().length() > SENDER_MAX ? citizen.name().substring(0, SENDER_MAX) : citizen.name();
    }

    private void tell(int playerId) {
        World world = population.gameWorld();
        Character player = world == null ? null : world.getPlayerStorage().getCharacterById(playerId);
        if (player != null) {
            hands.notifyDuey(player);
        }
    }

    public String report() {
        return "buy orders: on=" + on() + " players'=" + open.size();
    }
}
