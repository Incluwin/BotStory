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
        public Map<Integer, Integer> takeBet(int bettorId) {
            Character chr = bettors.get(bettorId);
            Character agent = agent();
            if (chr == null || agent == null || chr.getMap() == null) {
                return Map.of();
            }
            List<MapItem> chips = Floor.chipsBy(chr.getMap(), chr, chr.getPosition());
            if (chips.isEmpty()) {
                return Map.of();
            }
            if (Chips.value(Floor.count(chips)) > host.population().config().CASINO_MAX_BET) {
                host.say(session, "casino.dice.overmax", Map.of("name", chr.getName(),
                        "max", Chips.mesos(host.population().config().CASINO_MAX_BET)));
                return Map.of();
            }
            return Floor.take(agent, chips); // held by the host until the roll is settled
        }

        @Override
        public void pay(int bettorId, long mesos) {
            Character agent = agent();
            Character to = bettors.get(bettorId);
            if (agent == null || to == null || mesos <= 0) {
                return;
            }
            List<MapItem> paid = Floor.payOut(agent, mesos, to.getPosition(), to);
            long unpaid = mesos - Chips.value(Floor.count(paid));
            if (!Floor.purse(agent, to, unpaid)) {
                org.slf4j.LoggerFactory.getLogger(DiceTable.class).warn("dice host {} could not pay {} {} mesos",
                        agent.getName(), to.getName(), unpaid);
            }
        }

        @Override
        public void settled(int bettorId, boolean won, long value) {
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
        synchronized (post) {
            post.game.close(); // a bet still rolling is handed back
        }
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
