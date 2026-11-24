package server.living.population.craft;

import client.Character;
import client.Client;
import server.itcg.ItcgRecipes.Stack;
import server.living.population.CitizenSession;
import server.living.population.Market;
import server.living.population.Population;
import server.living.population.needs.NeedBoards;
import server.living.resident.LivingTalk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The commission windows (docs/WORLD_SYSTEMS_PLAN.md N8.2), at a board ("Commission a crafter") and by double-clicking a
 * crafter in town: the crafters about, what one of them can make from what the player carries, the terms, and the
 * player's orders. Orders run on companion-ops (economy and database work, never the packet thread), answered in a
 * window within a second of the click, else in pink text, as the board's hand-over is.
 */
public final class CommissionScreens {
    private static final long LATE_OUTCOME_MS = 1_000L;
    private static final int LISTED_MAX = 30;

    private final Population population;
    private final Professions professions;
    private final Commissions desk;

    CommissionScreens(Population population, Professions professions, Commissions desk) {
        this.population = population;
        this.professions = professions;
        this.desk = desk;
    }

    /** The board's page: the player's orders and the crafters about. */
    public void board(Character player, Runnable back) {
        List<LivingTalk.Option> options = new ArrayList<>();
        List<Commission> mine = desk.of(player.getId());
        if (!mine.isEmpty()) {
            options.add(new LivingTalk.Option("#bYour orders#k (" + mine.size() + ")", () -> orders(player, back)));
        }
        List<CitizenSession> crafters = desk.crafters(player.getId());
        for (CitizenSession crafter : crafters.subList(0, Math.min(LISTED_MAX, crafters.size()))) {
            int rung = Commissions.rung(desk.warmth(crafter.characterId, player.getId()));
            options.add(new LivingTalk.Option(crafter.name() + ", " + professions.of(crafter.characterId).word()
                    + (rung == 2 ? " #d(friend)#k" : rung == 1 ? " #d(knows you)#k" : ""),
                    () -> crafter(player, crafter.characterId, () -> board(player, back))));
        }
        if (options.isEmpty()) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).ok("No crafter is around right now.").show();
            return;
        }
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).menu("Leave materials and a fee with a crafter.\r\n"
                + "They work while they are around,\r\nand bring it to you or send it by Duey.", options).show();
    }

    /** Whether a double-click on this citizen may offer its work: a crafter in town, and the player carries materials. */
    public boolean mayTalk(Character player, Character citizen) {
        if (!desk.on()) {
            return false;
        }
        Profession trade = professions.of(citizen.getId());
        String where = trade == null ? "" : population.fields().kind(citizen.getMapId());
        return ("town".equals(where) || "fm".equals(where)) && !offers(player, trade).isEmpty();
    }

    /** One crafter: what it can make from what the player carries, else what it makes at all. */
    public void crafter(Character player, int crafterId, Runnable back) {
        Profession trade = professions.of(crafterId);
        if (trade == null || population.citizen(crafterId) == null) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).ok("They don't take orders any more.").show();
            return;
        }
        String name = population.citizen(crafterId).name();
        Runnable here = () -> crafter(player, crafterId, back);
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Offer offer : offers(player, trade)) {
            options.add(new LivingTalk.Option(offer.label(), () -> offer.open(player, crafterId, here)));
        }
        boolean can = !options.isEmpty();
        if (trade != Profession.SCROLLER) {
            options.add(new LivingTalk.Option("What do you make?", () -> catalogue(player, trade, here)));
        }
        String text = "#e" + name + "#n, " + trade.word() + ".\r\n" + (can ? "You carry what these take:"
                : trade == Profession.SCROLLER ? "Bring a piece with slots left and a scroll that fits it."
                : "You carry nothing they can work with.");
        LivingTalk talk = LivingTalk.open(player, NeedBoards.BOARD_NPC);
        if (back != null) {
            talk = talk.back(back);
        }
        if (options.isEmpty()) {
            talk.ok(text).show();
        } else {
            talk.menu(text, options).show();
        }
    }

    /** Something the player could order now: its line, and the window its click opens. */
    private record Offer(String label, Opens opens) {
        void open(Character player, int crafterId, Runnable back) {
            opens.open(player, crafterId, back);
        }
    }

    private interface Opens {
        void open(Character player, int crafterId, Runnable back);
    }

    private List<Offer> offers(Character player, Profession trade) {
        List<Offer> out = new ArrayList<>();
        if (trade == Profession.SCROLLER) {
            for (CommissionHands.ScrollPair pair : desk.hands().scrollPairs(player)) {
                short slot = pair.piece().getPosition();
                int pieceId = pair.piece().getItemId();
                int scrollId = pair.scroll().getItemId();
                out.add(new Offer("#t" + scrollId + "# on #t" + pieceId + "#",
                        (who, crafterId, back) -> scrollTerms(who, crafterId, slot, pieceId, scrollId, back)));
            }
            return out;
        }
        for (RecipeBook.Recipe recipe : professions.book().of(trade)) {
            int covers = desk.hands().covers(player, recipe.inputs());
            if (covers > 0) {
                int most = recipe.equip() ? 1 : Math.min(covers, Commissions.PACKAGE_MAX / recipe.resultCount());
                out.add(new Offer("#t" + recipe.result() + "#" + (recipe.equip() ? " (Lv " + recipe.level() + ")" : ""),
                        (who, crafterId, back) -> howMany(who, crafterId, recipe, most, back)));
            }
        }
        return out;
    }

    private void catalogue(Character player, Profession trade, Runnable back) {
        List<LivingTalk.Option> options = new ArrayList<>();
        professions.book().of(trade).stream()
                .sorted(Comparator.comparingInt(recipe -> Math.abs(recipe.level() - player.getLevel())))
                .limit(LISTED_MAX)
                .forEach(recipe -> options.add(new LivingTalk.Option("#t" + recipe.result() + "# (Lv " + recipe.level() + ")",
                        () -> LivingTalk.open(player, NeedBoards.BOARD_NPC).back(() -> catalogue(player, trade, back))
                                .ok("#t" + recipe.result() + "# takes:" + materials(recipe, 1)).show())));
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).menu("Nearest your level first:", options).show();
    }

    private static String materials(RecipeBook.Recipe recipe, int quantity) {
        StringBuilder out = new StringBuilder();
        for (Stack input : recipe.inputs()) {
            out.append("\r\n#i").append(input.itemId()).append("# ").append(input.count() * quantity).append(" #t")
                    .append(input.itemId()).append('#');
        }
        return out.toString();
    }

    private void howMany(Character player, int crafterId, RecipeBook.Recipe recipe, int most, Runnable back) {
        if (most <= 1) {
            terms(player, crafterId, recipe, 1, back);
            return;
        }
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).number("How many times #t" + recipe.result() + "#"
                + (recipe.resultCount() > 1 ? " (" + recipe.resultCount() + " each)" : "") + "?", 1, 1, most,
                quantity -> terms(player, crafterId, recipe, quantity, back)).show();
    }

    private void terms(Character player, int crafterId, RecipeBook.Recipe recipe, int quantity, Runnable back) {
        long fee = desk.fee(recipe, quantity, crafterId, player.getId());
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).acceptDecline("#e"
                + recipe.resultCount() * quantity + " #t" + recipe.result() + "##n" + materials(recipe, quantity)
                + (recipe.inputs().stream().anyMatch(input -> input.itemId() / 1_000_000 == 1)
                ? "\r\nOf several, they take your plainest." : "")
                + "\r\nFee: #b" + Market.formatPrice(fee) + "#k (" + fee + ")."
                + "\r\nDone in about " + due(desk.waitMs(crafterId, player.getId(), Commissions.workMs(recipe, quantity)))
                + ".\r\nYou can cancel until they start.",
                () -> run(player, () -> placed(desk.order(player, crafterId, recipe.id(), quantity,
                        System.currentTimeMillis()))), back).show();
    }

    private void scrollTerms(Character player, int crafterId, short slot, int pieceId, int scrollId, Runnable back) {
        int level = pieceLevel(pieceId);
        long fee = desk.scrollFee(level, crafterId, player.getId());
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).acceptDecline("#t" + scrollId + "# on #t" + pieceId
                + "#.\r\nIt can fail, as it can for you."
                + "\r\nFee: #b" + Market.formatPrice(fee) + "#k (" + fee + ")."
                + "\r\nDone in about " + due(desk.waitMs(crafterId, player.getId(), Commissions.SCROLL_MINUTES * 60_000L))
                + ".\r\nYou can cancel until they start.",
                () -> run(player, () -> placed(desk.orderScroll(player, crafterId, slot, pieceId, scrollId, level,
                        System.currentTimeMillis()))), back).show();
    }

    private static int pieceLevel(int itemId) {
        return server.ItemInformationProvider.getInstance().getEquipLevelReq(itemId);
    }

    private String placed(Commissions.Ordered ordered) {
        return ordered.refused() != null ? ordered.refused() : desk.crafterName(ordered.order()) + " takes it.\r\nDone in about "
                + due(Math.max(0L, ordered.order().dueAtMs() - System.currentTimeMillis()))
                + " if they stay around.\r\nThey bring it to you or send it by Duey.";
    }

    private void orders(Character player, Runnable back) {
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Commission order : desk.of(player.getId())) {
            options.add(new LivingTalk.Option(desk.crafterName(order) + ": " + desk.what(order)
                    + (order.state() == Commission.State.WORKING ? " #g(at work)#k" : " #d(waiting)#k"),
                    () -> order(player, order.id(), back)));
        }
        if (options.isEmpty()) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(back).ok("You have no orders out.").show();
            return;
        }
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(() -> board(player, back)).menu("Your orders:", options).show();
    }

    private void order(Character player, int id, Runnable back) {
        Commission order = desk.of(player.getId()).stream().filter(one -> one.id() == id).findFirst().orElse(null);
        Runnable list = () -> orders(player, back);
        if (order == null) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(list).ok("That order is done.").show();
            return;
        }
        boolean about = population.presence().session(order.crafterId()) != null;
        String text = "#e" + desk.what(order) + "#n\r\n" + desk.crafterName(order) + " is "
                + (order.state() == Commission.State.WORKING ? "at work on it, " + due(order.leftMs()) + " to go"
                : "not at it yet") + ".\r\n" + (about ? "They are around now." : "They are away: it waits until they're back.")
                + "\r\nFee: " + Market.formatPrice(order.fee()) + ".";
        if (order.state() != Commission.State.HELD) {
            LivingTalk.open(player, NeedBoards.BOARD_NPC).back(list).ok(text).show();
            return;
        }
        LivingTalk.open(player, NeedBoards.BOARD_NPC).back(list).menu(text, List.of(
                new LivingTalk.Option("Cancel it (everything comes back)", () -> run(player, () -> {
                    String refused = desk.cancel(player, id, System.currentTimeMillis());
                    return refused != null ? refused : "You take your materials and the fee back.";
                })))).show();
    }

    static String due(long ms) {
        long minutes = Math.max(1L, (ms + 59_999L) / 60_000L);
        if (minutes < 60) {
            return minutes + " min";
        }
        return minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " min");
    }

    private void run(Character player, Supplier<String> work) {
        long clickedAtMs = System.currentTimeMillis();
        population.world().executor().submit("commission " + player.getId(), () -> {
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
