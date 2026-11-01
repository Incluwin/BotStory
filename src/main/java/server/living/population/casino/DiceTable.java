package server.living.population.casino;

import client.Character;
import server.living.population.CitizenSession;
import server.maps.MapItem;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cho-han dice in Henesys Game Park (SoloMapling's DiceBot), hosted by a citizen with a float of 3 x CASINO_MAX_BET in
 * chips. A player standing near drops chips and says "cho" (even) or "han" (odd); the host rolls two dice and pays
 * evens out of its own chips, or picks up the bet. Now and then, while a player is in Game Park and nobody's bet is
 * down, it calls for bets. SoloMapling's roll shout ("test 3 5") and the Cannon Master dice effect (not in v83) are
 * gone: the host says the roll.
 */
final class DiceTable extends HostPost<DiceTable.DicePost> {
    private static final int REACH_PX = 320;
    private static final int FLOAT_BETS = 3;

    final class DicePost extends HostPost.Post implements ChoHanGame.Table {
        final ChoHanGame game = new ChoHanGame(this, host.random());
        final Map<Integer, Character> bettors = new HashMap<>();
        final Map<Integer, List<MapItem>> bets = new HashMap<>();
        volatile boolean rolling;

        DicePost(CitizenSession session, long until) {
            super(session, until);
        }

        void roll() {
            synchronized (this) {
                game.tick(System.currentTimeMillis());
                if (game.phase() == ChoHanGame.Phase.OPEN) {
                    rolling = false;
                    return;
                }
            }
            host.after(800L, this::roll);
        }

        @Override
        public void say(String trigger, Map<String, Object> args) {
            host.say(session, trigger, args);
        }

        @Override
        public Map<Integer, Integer> chipsDown(int bettorId) {
            Character chr = bettors.get(bettorId);
            if (chr == null || chr.getMap() == null) {
                return Map.of();
            }
            List<MapItem> chips = Floor.chipsBy(chr.getMap(), chr, chr.getPosition());
            Map<Integer, Integer> counted = Floor.count(chips);
            if (Chips.value(counted) > host.population().config().CASINO_MAX_BET) {
                host.say(session, "casino.dice.overmax", Map.of("name", chr.getName(),
                        "max", Chips.mesos(host.population().config().CASINO_MAX_BET)));
                return Map.of();
            }
            bets.put(bettorId, chips);
            return counted;
        }

        @Override
        public boolean betStillDown(int bettorId) {
            Character agent = agent();
            return agent != null && Floor.allDown(agent.getMap(), bets.getOrDefault(bettorId, List.of()));
        }

        @Override
        public void pay(int bettorId, Map<Integer, Integer> chips) {
            Character agent = agent();
            Character to = bettors.get(bettorId);
            if (agent == null || to == null) {
                return;
            }
            Map<Integer, Integer> held = BlackjackTables.held(agent);
            for (Map.Entry<Integer, Integer> chip : chips.entrySet()) {
                int missing = chip.getValue() - held.getOrDefault(chip.getKey(), 0);
                if (missing > 0) {
                    ChipCashier.buy(agent, (long) missing * Chips.value(chip.getKey()));
                }
            }
            host.runAs(session, () -> Floor.dropChips(agent, chips, to.getPosition(), to));
        }

        @Override
        public void collect(int bettorId) {
            Character agent = agent();
            List<MapItem> chips = bets.remove(bettorId);
            if (agent != null && chips != null) {
                host.runAs(session, () -> Floor.pickUp(agent, chips));
            }
        }

        @Override
        public void settled(int bettorId, boolean won, long value) {
            bets.remove(bettorId);
            bettors.remove(bettorId);
        }
    }

    DiceTable(CasinoHost host, CasinoContent content) {
        super(host, content);
    }

    @Override
    public String name() {
        return "dice";
    }

    @Override
    public int mapId() {
        return content.gameParkMap();
    }

    @Override
    public boolean enabled() {
        return host.population().config().CASINO_DICE_ENABLED && host.population().config().CASINO_CHIPS_ENABLED;
    }

    @Override
    Point spot() {
        return content.dice();
    }

    @Override
    DicePost open(CitizenSession session, long until) {
        Character agent = session.agent();
        long floatMesos = FLOAT_BETS * (long) host.population().config().CASINO_MAX_BET;
        if (agent.getMeso() < floatMesos + 100_000L || !ChipCashier.buy(agent, floatMesos)) {
            return null;
        }
        return new DicePost(session, until);
    }

    @Override
    void step(DicePost post, long now) {
        if (post.game.phase() == ChoHanGame.Phase.OPEN && host.random().nextInt(100) < 12
                && host.humanOn(post.session.channel, mapId())) {
            host.say(post.session, "casino.dice.call", Map.of());
        }
    }

    @Override
    boolean busy(DicePost post) {
        return post.game.phase() != ChoHanGame.Phase.OPEN;
    }

    @Override
    void closing(DicePost post) {
        Character agent = post.agent();
        host.say(post.session, "casino.dice.closing", Map.of());
        if (agent != null) {
            walk(post.session, content.cashier());
            host.after(5_000L, () -> ChipCashier.cashOut(agent));
        }
    }

    @Override
    public boolean heard(Character human, String text, long now) {
        if (!enabled()) {
            return false;
        }
        DicePost post = near(human, REACH_PX);
        if (post == null) {
            return false;
        }
        String said = text.toLowerCase(Locale.ROOT).trim();
        boolean cho = says(said, "cho", "even", "bet cho");
        boolean han = says(said, "han", "odd", "bet han");
        if (cho == han) {
            return false;
        }
        synchronized (post) {
            if (post.game.phase() != ChoHanGame.Phase.OPEN) {
                if (post.game.bettor() != human.getId()) {
                    host.say(post.session, "casino.dice.wait", Map.of("name", human.getName()));
                }
                return true;
            }
            post.bettors.put(human.getId(), human);
            if (post.game.call(human.getId(), human.getName(), cho, now) && !post.rolling) {
                post.rolling = true;
                host.after(800L, post::roll);
            }
        }
        return true;
    }
}
