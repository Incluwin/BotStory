package server.living.population.casino;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.integration.AgentPresence;
import server.living.population.CitizenSession;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Game Park host (SoloMapling's GameZoneHostBot): a citizen at the bar who says hello to players walking in, and
 * when one speaks to it ("drink?", its name), offers a drink, names what it has, and drops the one they pick in front
 * of them, only theirs to pick up. The drinks are real items it bought (twice their WZ price, as a shop's), not
 * SoloMapling's placeholders (an event Coca_Cola and a duplicate Elixir id).
 */
final class GameParkBar extends HostPost<GameParkBar.BarPost> {
    private static final int REACH_PX = 300;
    private static final int STOCK_EACH = 3;
    private static final long GREET_AGAIN_MS = 30 * 60_000L;
    private static final long ANSWER_MS = 30_000L;

    enum Stage { OFFERED, MENU }

    record Talk(Stage stage, long until) {
    }

    final class BarPost extends HostPost.Post {
        /** Human id -> when it last greeted them. */
        final Map<Integer, Long> greeted = new ConcurrentHashMap<>();
        /** Human id -> where their talk with it stands. */
        final Map<Integer, Talk> talks = new ConcurrentHashMap<>();

        BarPost(CitizenSession session, long until) {
            super(session, until);
        }
    }

    GameParkBar(CasinoHost host, CasinoContent content) {
        super(host, content);
    }

    @Override
    public String name() {
        return "bar";
    }

    @Override
    public int mapId() {
        return content.gameParkMap();
    }

    @Override
    public boolean enabled() {
        return host.population().config().CASINO_BAR_ENABLED;
    }

    @Override
    Point spot() {
        return content.bar();
    }

    @Override
    BarPost open(CitizenSession session, long until) {
        return restock(session.agent()) ? new BarPost(session, until) : null;
    }

    /** It buys a few of each drink it has run out of; false when its purse cannot. */
    boolean restock(Character agent) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (int drink : content.drinks()) {
            int held = agent.getItemQuantity(drink, false);
            if (held > 0) {
                continue;
            }
            int cost = 2 * Math.max(1, ii.getPrice(drink, STOCK_EACH));
            if (agent.getMeso() < cost || !InventoryManipulator.checkSpace(agent.getClient(), drink, STOCK_EACH, "")) {
                return false;
            }
            try (var ignored = MesoFlows.as(MesoAccount.NPC_PURCHASE)) {
                agent.gainMeso(-cost, false);
            }
            InventoryManipulator.addById(agent.getClient(), drink, (short) STOCK_EACH);
        }
        return true;
    }

    @Override
    void step(BarPost post, long now) {
        Character agent = post.agent();
        MapleMap map = agent == null ? null : agent.getMap();
        if (map == null) {
            return;
        }
        post.talks.values().removeIf(talk -> talk.until() < now);
        List<Character> arrived = new ArrayList<>();
        for (Character chr : map.getAllPlayers()) {
            if (AgentPresence.isAgent(chr)) {
                continue;
            }
            Long last = post.greeted.get(chr.getId());
            if (last == null || now - last > GREET_AGAIN_MS) {
                post.greeted.put(chr.getId(), now);
                arrived.add(chr);
            }
        }
        if (arrived.size() == 1) {
            host.say(post.session, "casino.bar.greet", Map.of("name", arrived.get(0).getName()));
            agent.changeFaceExpression(2);
        } else if (arrived.size() > 1) {
            host.say(post.session, "casino.bar.greet.all", Map.of());
        } else if (!post.greeted.isEmpty() && host.random().nextInt(100) < 6 && host.humanOn(post.session.channel, mapId())) {
            host.say(post.session, "casino.bar.flavor", Map.of());
        }
    }

    @Override
    public boolean heard(Character human, String text, long now) {
        if (!enabled()) {
            return false;
        }
        BarPost post = near(human, REACH_PX);
        if (post == null) {
            return false;
        }
        String said = text.toLowerCase(Locale.ROOT).trim();
        Talk talk = post.talks.get(human.getId());
        Integer drink = drinkNamed(said);
        if (drink != null && (talk != null || says(said, "please", "pls", "plz", "one", "can i get", "can i have"))) {
            serve(post, human, drink);
            return true;
        }
        if (talk != null && talk.stage() == Stage.OFFERED) {
            if (says(said, "yes", "sure", "ok", "okay", "yeah", "ya", "yep", "pls", "please")) {
                host.say(post.session, "casino.bar.menu", Map.of("name", human.getName(), "drinks", menu()));
                post.talks.put(human.getId(), new Talk(Stage.MENU, now + ANSWER_MS));
                return true;
            }
            if (says(said, "no", "nah", "nope", "no thanks")) {
                host.say(post.session, "casino.bar.decline", Map.of("name", human.getName()));
                post.talks.remove(human.getId());
                return true;
            }
        }
        if (talk != null && talk.stage() == Stage.MENU) {
            host.say(post.session, "casino.bar.unknown", Map.of("name", human.getName(), "drinks", menu()));
            return true;
        }
        String name = post.session.name().toLowerCase(Locale.ROOT);
        if (said.contains(name) || says(said, "drink", "drinks", "thirsty", "bartender", "menu")) {
            host.say(post.session, "casino.bar.offer", Map.of("name", human.getName()));
            post.talks.put(human.getId(), new Talk(Stage.OFFERED, now + ANSWER_MS));
            return true;
        }
        return false;
    }

    private void serve(BarPost post, Character human, int drink) {
        Character agent = post.agent();
        post.talks.remove(human.getId());
        if (agent == null) {
            return;
        }
        String drinkName = ItemInformationProvider.getInstance().getName(drink);
        host.say(post.session, "casino.bar.serve", Map.of("name", human.getName(), "drink", drinkName.toLowerCase(Locale.ROOT)));
        host.after(2_500L + host.random().nextInt(1_500), () -> host.runAs(post.session, () -> {
            if (agent.getItemQuantity(drink, false) <= 0 && !restock(agent)) {
                return;
            }
            InventoryManipulator.removeById(agent.getClient(), InventoryType.USE, drink, 1, true, false);
            Item item = new Item(drink, (short) 0, (short) 1);
            MapleMap map = agent.getMap();
            if (map != null) {
                Floor.drop(map, agent, human, item, new Point(human.getPosition()), true);
            }
        }));
    }

    /** The drink the player named, or null. */
    private Integer drinkNamed(String said) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (int drink : content.drinks()) {
            String name = ii.getName(drink);
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            String last = lower.substring(lower.lastIndexOf(' ') + 1);
            String first = lower.split(" ")[0];
            if (said.contains(lower) || said.contains(first) && !first.equals("pure") || said.contains(last)
                    && !last.equals("juice")) {
                return drink;
            }
        }
        return null;
    }

    /** "orange juice, grape juice, maple cola or pure water". */
    private String menu() {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<String> names = new ArrayList<>();
        for (int drink : content.drinks()) {
            String name = ii.getName(drink);
            if (name != null) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        if (names.size() < 2) {
            return String.join("", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1);
    }
}
