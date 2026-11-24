package server.living.population.craft;

import client.Character;
import client.inventory.Item;
import config.PopulationConfig;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.ChatterEngine;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.PersonMemory;
import server.living.population.Population;
import server.living.population.paper.PaperDesk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Commissions (docs/WORLD_SYSTEMS_PLAN.md N8.2): a player leaves materials and a fee with a citizen crafter, who works on
 * one order at a time while it is online, people it knows first, and brings what it made: in person when the customer is
 * on its map, else by Duey with a note. An order still waiting may be cancelled for everything back; one whose crafter
 * left, or never got to it, goes back by Duey. The fee is under the stock NPC's, less again for a friend, and a transfer
 * (EconomyOperationKind.COMMISSION). Rides the population's minute; every change of one order is under its desk lock.
 * Behind population.COMMISSIONS_ENABLED (and PROFESSIONS_ENABLED).
 */
public final class Commissions {
    private static final Logger log = LoggerFactory.getLogger(Commissions.class);
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 3_600_000L;
    /** A minute lane that came late counts for this much work at most (the server slept, the crafter did not work). */
    static final long STEP_MAX_MS = 2 * MINUTE_MS;
    /** What fits in one Duey package. */
    static final int PACKAGE_MAX = 100;
    static final int SCROLL_MINUTES = 15;
    /** An order is news from this level or fee on. */
    static final int NEWS_LEVEL = 50;
    static final long NEWS_FEE = 50_000L;
    private static final int SENDER_MAX = 13;

    private final Population population;
    private final Professions professions;
    private final CommissionStore store;
    private final CommissionHands hands;
    private final IntFunction<String> names;
    private final Map<Integer, Commission> open = new ConcurrentHashMap<>();
    private final Object desk = new Object();
    private final CommissionScreens screens;
    private volatile boolean started;
    private long lastTickMs;

    public Commissions(Population population, Professions professions) {
        this(population, professions, new CommissionStore(), null, itemId -> {
            String name = server.ItemInformationProvider.getInstance().getName(itemId);
            return name == null ? "item " + itemId : name;
        });
    }

    Commissions(Population population, Professions professions, CommissionStore store, CommissionHands hands,
                IntFunction<String> names) {
        this.population = population;
        this.professions = professions;
        this.store = store;
        this.hands = hands == null ? new CommissionHands(store) : hands;
        this.names = names;
        this.screens = new CommissionScreens(population, professions, this);
    }

    public boolean on() {
        return config().COMMISSIONS_ENABLED && professions.on();
    }

    private PopulationConfig config() {
        return population.config();
    }

    public CommissionScreens screens() {
        return screens;
    }

    public CommissionHands hands() {
        return hands;
    }

    /** Reads the open orders once; false when they could not be read (nothing is ordered or worked on then). */
    private boolean start() {
        if (started) {
            return true;
        }
        synchronized (desk) {
            if (!started) {
                List<Commission> rows = store.open();
                if (rows == null) {
                    return false;
                }
                rows.forEach(order -> open.put(order.id(), order));
                started = true;
                log.info("commissions: {} open", rows.size());
            }
        }
        return true;
    }

    // ---- views -----------------------------------------------------------------------------------

    /** A player's open orders, oldest first. */
    public List<Commission> of(int customerId) {
        return open.values().stream().filter(order -> order.customerId() == customerId)
                .sorted(Comparator.comparingInt(Commission::id)).toList();
    }

    /** A crafter's open orders, oldest first. */
    public List<Commission> with(int crafterId) {
        return open.values().stream().filter(order -> order.crafterId() == crafterId)
                .sorted(Comparator.comparingInt(Commission::id)).toList();
    }

    /** The crafters about now who take orders, those who know the player best first. */
    public List<CitizenSession> crafters(int playerId) {
        List<CitizenSession> out = new ArrayList<>();
        for (CitizenSession session : population.presence().sessions()) {
            if (!session.virtual && session.agent() != null && professions.of(session.characterId) != null) {
                out.add(session);
            }
        }
        Map<Integer, Integer> warmth = new java.util.HashMap<>();
        out.forEach(session -> warmth.put(session.characterId, warmth(session.characterId, playerId)));
        out.sort(Comparator.comparingInt((CitizenSession session) -> warmth.get(session.characterId)).reversed()
                .thenComparingInt(session -> session.characterId));
        return out;
    }

    int warmth(int crafterId, int playerId) {
        return population.memories() == null ? 0 : population.memories().warmth(crafterId, playerId);
    }

    /** What the crafter asks this player for {@code quantity}: the recipe's fee, less a friend's discount; at least 1. */
    long fee(RecipeBook.Recipe recipe, int quantity, int crafterId, int playerId) {
        return discounted((long) recipe.fee() * quantity, crafterId, playerId);
    }

    long scrollFee(int pieceLevel, int crafterId, int playerId) {
        return discounted((long) config().COMMISSION_SCROLL_FEE_PER_LEVEL * Math.max(10, pieceLevel), crafterId, playerId);
    }

    private long discounted(long fee, int crafterId, int playerId) {
        double off = warmth(crafterId, playerId) >= PersonMemory.FOND ? config().COMMISSION_FRIEND_DISCOUNT : 0.0d;
        return Math.max(1L, (long) Math.ceil(fee * (1.0d - off)));
    }

    static long workMs(RecipeBook.Recipe recipe, int quantity) {
        return recipe.equip() ? recipe.minutes() * MINUTE_MS
                : recipe.minutes() * MINUTE_MS + (quantity - 1) * MINUTE_MS; // the fire is hot: one more is a minute
    }

    /** How long until the crafter would be done with one more order for this player, if it stays online. */
    long waitMs(int crafterId, int playerId, long workMs) {
        int rung = rung(warmth(crafterId, playerId));
        long wait = workMs;
        for (Commission order : with(crafterId)) {
            if (order.state() == Commission.State.WORKING || rung(warmth(crafterId, order.customerId())) >= rung) {
                wait += order.leftMs();
            }
        }
        return wait;
    }

    /** 0 stranger, 1 it knows them, 2 friend (the citizen standing ladder). */
    static int rung(int warmth) {
        return warmth >= PersonMemory.FOND ? 2 : warmth >= PersonMemory.WARM ? 1 : 0;
    }

    String itemName(int itemId) {
        return names.apply(itemId);
    }

    /** What an order is, as people say it: "3 Steel Plate", "a scroll". */
    String what(Commission order) {
        RecipeBook.Recipe recipe = order.scroll() ? null : professions.book().recipe(order.recipe());
        if (recipe == null) {
            return order.scroll() ? "scroll work" : order.recipe();
        }
        int count = recipe.resultCount() * order.quantity();
        return (count > 1 ? count + " " : "") + itemName(recipe.result());
    }

    String crafterName(Commission order) {
        Citizen crafter = population.citizen(order.crafterId());
        return crafter == null ? "Your crafter" : crafter.name();
    }

    // ---- ordering ----------------------------------------------------------------------------------

    /** What came of an order: it as stored, or what to tell the customer. */
    public record Ordered(Commission order, String refused) {
        static Ordered no(String why) {
            return new Ordered(null, why);
        }
    }

    /** The player orders {@code quantity} of {@code recipeId} from the crafter. */
    public Ordered order(Character customer, int crafterId, String recipeId, int quantity, long nowMs) {
        RecipeBook.Recipe recipe = professions.book().recipe(recipeId);
        Profession trade = professions.of(crafterId);
        if (!on() || recipe == null || trade == null || recipe.profession() != trade) {
            return Ordered.no("They don't make that any more.");
        }
        int most = recipe.equip() ? 1 : PACKAGE_MAX / recipe.resultCount();
        if (quantity < 1 || quantity > most) {
            return Ordered.no("That's too many for one order.");
        }
        long fee = fee(recipe, quantity, crafterId, customer.getId());
        return place(customer, crafterId, recipeId, quantity, fee, workMs(recipe, quantity),
                () -> hands.materials(customer, recipe.inputs(), quantity), nowMs);
    }

    /** The player has the scroller use {@code scrollId} on the piece in {@code pieceSlot}. */
    public Ordered orderScroll(Character customer, int crafterId, short pieceSlot, int pieceId, int scrollId, int pieceLevel,
                               long nowMs) {
        if (!on() || professions.of(crafterId) != Profession.SCROLLER) {
            return Ordered.no("They don't do that any more.");
        }
        return place(customer, crafterId, Commission.SCROLL, 1, scrollFee(pieceLevel, crafterId, customer.getId()),
                SCROLL_MINUTES * MINUTE_MS, () -> hands.scrollMaterials(customer, pieceSlot, pieceId, scrollId), nowMs);
    }

    private Ordered place(Character customer, int crafterId, String recipe, int quantity, long fee, long workMs,
                          CommissionHands.Finder materials, long nowMs) {
        if (!start() || fee > Integer.MAX_VALUE) {
            return Ordered.no("That didn't work. Try again in a moment.");
        }
        synchronized (desk) {
            if (of(customer.getId()).size() >= config().COMMISSIONS_PER_PLAYER) {
                return Ordered.no("You have " + config().COMMISSIONS_PER_PLAYER + " orders out already.");
            }
            if (with(crafterId).size() >= config().COMMISSION_QUEUE_MAX) {
                return Ordered.no("They have all the work they can take right now.");
            }
            long dueAt = nowMs + waitMs(crafterId, customer.getId(), workMs);
            Commission order = new Commission(0, customer.getId(), crafterId, recipe, quantity, fee, Commission.State.HELD,
                    customer.getMapId(), workMs, 0L, nowMs, dueAt, 0L);
            CommissionHands.Placed placed = hands.place(customer, order, materials);
            if (placed.refused() != null) {
                return Ordered.no(placed.refused());
            }
            open.put(placed.order().id(), placed.order());
            log.info("commission {}: {} orders {} x{} from citizen {} for {}", placed.order().id(), customer.getName(),
                    recipe, quantity, crafterId, fee);
            return new Ordered(placed.order(), null);
        }
    }

    /** The customer takes back an order its crafter has not started. Null when done, else why not. */
    public String cancel(Character customer, int commissionId, long nowMs) {
        synchronized (desk) {
            Commission order = open.get(commissionId);
            if (order == null || order.customerId() != customer.getId()) {
                return "That order is gone.";
            }
            if (order.state() != Commission.State.HELD) {
                return crafterName(order) + " has started on it already.";
            }
            String refused = hands.giveBack(customer, order.closed(Commission.State.CANCELLED, nowMs));
            if (refused != null) {
                return refused;
            }
            open.remove(commissionId);
            log.info("commission {} cancelled by {}", commissionId, customer.getName());
            return null;
        }
    }

    // ---- the minute ----------------------------------------------------------------------------------

    /** Orders move on: refunds first, then each crafter online works, or starts on its next order, or hands one over. */
    public void minute(long nowMs) {
        long step = lastTickMs == 0L ? MINUTE_MS : Math.max(0L, Math.min(STEP_MAX_MS, nowMs - lastTickMs));
        lastTickMs = nowMs;
        if (!on() || population.needs() == null || !start()) {
            return; // off: every order keeps what it holds and goes on when the switch is back
        }
        for (Commission order : List.copyOf(open.values())) {
            safely("refund of " + order.id(), () -> refundIfStuck(order, nowMs));
        }
        List<Integer> crafters = open.values().stream().map(Commission::crafterId).distinct().toList();
        for (int crafterId : crafters) {
            CitizenSession session = population.presence().session(crafterId);
            if (session != null) {
                safely("work of " + crafterId, () -> work(session, step, nowMs));
            }
        }
        for (CitizenSession session : population.presence().sessions()) {
            if (!session.virtual && session.agent() != null && !session.stallOpen
                    && professions.of(session.citizen) != null && with(session.characterId).isEmpty() && atWork(session)) {
                // a piece a citizen asks for on the board
                safely("board work of " + session.characterId,
                        () -> population.needs().madeBy(session, session.agent(), nowMs));
            }
        }
    }

    /** One order that fails does not stop the others. */
    private static void safely(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            log.warn("commissions: {} failed", what, failure);
        }
    }

    /** In a town or at the market, where a board is. */
    private boolean atWork(CitizenSession session) {
        String where = population.fields().kind(session.mapId);
        return "town".equals(where) || "fm".equals(where);
    }

    private void work(CitizenSession session, long step, long nowMs) {
        synchronized (desk) {
            Commission current = null;
            for (Commission order : with(session.characterId)) {
                if (order.state() == Commission.State.WORKING) {
                    current = order;
                }
            }
            if (current == null) {
                Commission next = next(session.characterId);
                if (next != null) {
                    keep(next.started(nowMs + next.leftMs()));
                    log.info("commission {}: citizen {} starts on {}", next.id(), session.name(), next.recipe());
                }
                return;
            }
            Commission worked = current.leftMs() <= 0L ? current : current.worked(step);
            if (worked != current) {
                keep(worked);
            }
            if (worked.leftMs() <= 0L && !session.virtual && session.agent() != null) {
                deliver(session, session.agent(), worked, nowMs);
            }
        }
    }

    /** The waiting order it takes next: people it knows better first, then the oldest. */
    Commission next(int crafterId) {
        Commission best = null;
        int bestRung = -1;
        for (Commission order : with(crafterId)) {
            int rung = rung(warmth(crafterId, order.customerId()));
            if (order.state() == Commission.State.HELD && rung > bestRung) {
                best = order;
                bestRung = rung;
            }
        }
        return best;
    }

    private void keep(Commission order) {
        if (store.update(order)) {
            open.put(order.id(), order);
        }
    }

    private void deliver(CitizenSession session, Character crafter, Commission order, long nowMs) {
        Character customer = online(order.customerId());
        boolean near = customer != null && customer.getMapId() == crafter.getMapId()
                && customer.getMap() == crafter.getMap() && !customer.isHidden() && customer.getTrade() == null
                && !customer.isAwayFromWorld();
        RecipeBook.Recipe recipe = order.scroll() ? null : professions.book().recipe(order.recipe());
        String note = line("trade.note", session, order, customer);
        CommissionHands.Delivered delivered = hands.deliver(crafter, near ? customer : null,
                order.closed(Commission.State.DELIVERED, nowMs), recipe, sender(session.citizen), note);
        if (delivered == null) {
            return; // tried again next minute
        }
        open.remove(order.id());
        Item made = delivered.made();
        log.info("commission {}: citizen {} delivers {} x{} to {} {} for {}", order.id(), session.name(), made.getItemId(),
                made.getQuantity(), order.customerId(), delivered.byHand() ? "in person" : "by duey", order.fee());
        session.journal.add("trade", "finished " + what(order) + " for a customer");
        if (population.memories() != null) {
            population.memories().record(session.characterId, order.customerId(), PersonMemory.Kind.SOLD,
                    crafter.getMapId(), itemName(made.getItemId()), 1);
        }
        if (delivered.byHand()) {
            population.chatter().speakTo(event("trade.deliver", session, order, customer));
            customer.dropMessage(5, session.name() + " hands you " + what(order) + ".");
        } else if (customer != null) {
            hands.notifyDuey(customer);
        }
        if (recipe != null && (recipe.level() >= NEWS_LEVEL && recipe.equip() || order.fee() >= NEWS_FEE)) {
            Citizen known = population.citizen(order.customerId());
            String name = customer != null ? customer.getName() : known != null ? known.name()
                    : Character.getNameById(order.customerId());
            if (name != null) {
                PaperDesk.file("market.commission", 20 + recipe.level() / 4, Map.of("name", name, "crafter", session.name(),
                        "item", itemName(made.getItemId())));
            }
        }
    }

    private ChatterEngine.Event event(String trigger, CitizenSession session, Commission order, Character customer) {
        return ChatterEngine.Event.of(trigger, session.mapId, session.channel).self(session).subject(customer)
                .item(what(order).toLowerCase(java.util.Locale.ROOT));
    }

    private String line(String trigger, CitizenSession session, Commission order, Character customer) {
        String line = population.chatter().compose(event(trigger, session, order, customer));
        return line == null ? "Your order is done." : line;
    }

    /** An order nobody will finish goes back by Duey: its crafter is gone, or it waited past COMMISSION_REFUND_HOURS. */
    private void refundIfStuck(Commission order, long nowMs) {
        Citizen crafter = population.citizen(order.crafterId());
        boolean gone = professions.of(crafter) == null;
        long patience = config().COMMISSION_REFUND_HOURS * HOUR_MS;
        boolean late = order.state() == Commission.State.HELD ? nowMs - order.orderedAtMs() >= patience
                : nowMs - order.orderedAtMs() >= 3 * patience + order.workMs();
        if (!gone && !late) {
            return;
        }
        synchronized (desk) {
            Commission current = open.get(order.id());
            if (current == null) {
                return;
            }
            String name = crafter == null ? "Town Crafter" : sender(crafter);
            if (hands.refund(current.closed(Commission.State.REFUNDED, nowMs), name,
                    "Sorry, I can't get to your order. Here is everything back.")) {
                open.remove(current.id());
                log.info("commission {} of {} refunded by duey ({})", current.id(), current.customerId(),
                        gone ? "crafter gone" : "waited too long");
                Character customer = online(current.customerId());
                if (customer != null) {
                    hands.notifyDuey(customer);
                }
            }
        }
    }

    private static String sender(Citizen citizen) {
        return citizen.name().length() > SENDER_MAX ? citizen.name().substring(0, SENDER_MAX) : citizen.name();
    }

    private Character online(int playerId) {
        World world = population.gameWorld();
        return world == null ? null : world.getPlayerStorage().getCharacterById(playerId);
    }

    /** For !profession: the open orders of a crafter, a line each. */
    public List<String> describe(int crafterId, long nowMs) {
        List<String> out = new ArrayList<>();
        for (Commission order : with(crafterId)) {
            out.add("#" + order.id() + " " + order.state() + " " + what(order) + " for " + order.customerId() + ", fee "
                    + order.fee() + ", worked " + order.workedMs() / MINUTE_MS + "/" + order.workMs() / MINUTE_MS + " min");
        }
        return out;
    }

    public String report() {
        return "commissions: on=" + on() + " open=" + open.size() + " working="
                + open.values().stream().filter(order -> order.state() == Commission.State.WORKING).count();
    }

    /** For !profession finish: the order it works on counts as worked through; its next minute delivers it. */
    public boolean finish(int crafterId) {
        synchronized (desk) {
            for (Commission order : with(crafterId)) {
                if (order.state() == Commission.State.WORKING && order.leftMs() > 0L) {
                    keep(order.worked(order.leftMs()));
                    return true;
                }
            }
        }
        return false;
    }
}
