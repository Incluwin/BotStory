package server.living.population.needs;

import client.Character;
import client.Client;
import server.living.population.Market;
import server.living.population.Population;
import server.living.resident.LivingTalk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The board's buy-order windows (docs/WORLD_SYSTEMS_PLAN.md N8.3): the player's orders, posting one (a material by kind,
 * how many, what a piece) and taking one down. The moves run on companion-ops, answered as the board's hand-over is.
 */
public final class BuyOrderScreens {
    private static final long LATE_OUTCOME_MS = 1_000L;

    /** The kinds of material a board lists, by item id block. */
    private enum Kind {
        ORES("Ores"), PLATES("Plates and jewels"), CRYSTALS("Crystals and gems"), SPOILS("Monster spoils"),
        OTHER("Everything else");

        private final String title;

        Kind(String title) {
            this.title = title;
        }

        static Kind of(int itemId) {
            return switch (itemId / 1000) {
                case 4004, 4010, 4020 -> ORES;
                case 4011, 4021 -> PLATES;
                case 4005, 4250, 4251, 4260 -> CRYSTALS;
                case 4000 -> SPOILS;
                default -> OTHER;
            };
        }
    }

    private final Population population;
    private final BuyOrders orders;

    BuyOrderScreens(Population population, BuyOrders orders) {
        this.population = population;
        this.orders = orders;
    }

    public void board(Character player, Runnable back) {
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Need order : orders.of(player.getId())) {
            options.add(new LivingTalk.Option(line(order), () -> order(player, order.id(), back)));
        }
        options.add(new LivingTalk.Option("#bPost a buy order#k", () -> kinds(player, () -> board(player, back))));
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).menu("Name a material and a price.\r\n"
                + "The board holds the mesos.\r\nWhoever sells sends it by Duey.", options).show();
    }

    private static String line(Need order) {
        return order.remaining() + " #t" + order.itemId() + "# @ " + Market.formatPrice(order.offer())
                + (order.delivered() > 0 ? " (" + order.delivered() + " came)" : "");
    }

    private void order(Character player, int orderId, Runnable back) {
        Need order = orders.of(player.getId()).stream().filter(one -> one.id() == orderId).findFirst().orElse(null);
        Runnable list = () -> board(player, back);
        if (order == null) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(list).ok("That order is filled.").show();
            return;
        }
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(list).menu(line(order) + ".\r\nUp for "
                + NeedScreens.duration(order.deadlineMs() - System.currentTimeMillis()) + " more.", List.of(
                new LivingTalk.Option("Take it down (" + Market.formatPrice(order.pay(order.remaining())) + " comes back)",
                        () -> run(player, () -> {
                            String refused = orders.takeDown(player, orderId, System.currentTimeMillis());
                            return refused != null ? refused : "You take it down. The rest of the mesos is yours again.";
                        })))).show();
    }

    private void kinds(Character player, Runnable back) {
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            options.add(new LivingTalk.Option(kind.title, () -> items(player, kind, () -> kinds(player, back))));
        }
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).menu("What are you after?", options).show();
    }

    private void items(Character player, Kind kind, Runnable back) {
        List<LivingTalk.Option> options = new ArrayList<>();
        for (int itemId : orders.materials()) {
            if (Kind.of(itemId) == kind) {
                options.add(new LivingTalk.Option("#i" + itemId + "# #t" + itemId + "#",
                        () -> quantity(player, itemId, () -> items(player, kind, back))));
            }
        }
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).menu(kind.title + ":", options).show();
    }

    private void quantity(Character player, int itemId, Runnable back) {
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).number("How many #t" + itemId + "#?", 50, 1,
                BuyOrders.QUANTITY_MAX, quantity -> price(player, itemId, quantity, back)).show();
    }

    private void price(Character player, int itemId, int quantity, Runnable back) {
        long going = population.market().price(itemId);
        long floor = Math.max(1L, population.market().npcPays(itemId, 1));
        int most = Integer.MAX_VALUE / quantity;
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).number("What do you pay for one?\r\nThey go for about #b"
                        + Market.formatPrice(going) + "#k.", (int) Math.min(most, Math.max(floor, going)),
                (int) Math.min(most, floor), most, unit -> confirm(player, itemId, quantity, unit, back)).show();
    }

    private void confirm(Character player, int itemId, int quantity, int unit, Runnable back) {
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).acceptDecline(quantity + " #t" + itemId + "# @ "
                + Market.formatPrice(unit) + ".\r\nThe board takes #b" + Market.formatPrice((long) unit * quantity)
                + "#k now\r\nand gives back what nobody fills.", () -> run(player, () -> {
            String refused = orders.post(player, itemId, quantity, unit, System.currentTimeMillis());
            return refused != null ? refused : "It's up for " + population.config().BUY_ORDER_DAYS + " days.";
        }), back).show();
    }

    private void run(Character player, Supplier<String> work) {
        long clickedAtMs = System.currentTimeMillis();
        population.world().executor().submit("buyorder " + player.getId(), () -> {
            String text = work.get();
            Client client = player.getClient();
            if (client == null) {
                return; // logged off meanwhile
            }
            client.runOnIoThread(() -> {
                if (System.currentTimeMillis() - clickedAtMs <= LATE_OUTCOME_MS && LivingTalk.mayShow(player)) {
                    LivingTalk.answering(player, () -> LivingTalk.open(player, NeedBoards.BOARD_NPC).ok(text).show());
                } else {
                    player.dropMessage(5, text.replace("\r\n", " "));
                }
            });
        });
    }
}
