package server.living.population.casino;

import client.Character;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blackjack in Henesys Game Park (SoloMapling's BlackjackDealerBot), dealt by citizens. A citizen on a CASINO session
 * takes a free table's dealer spot if it can afford a float (8 x CASINO_MAX_BET in chips, bought from Aramia), or sits
 * down at a dealt table with a buy-in; at most two citizens per table, so seats stay for players. A player standing at
 * a table says "deal me in" to sit down, drops chips in front of themself when the dealer calls for bets, says hit or
 * stand, and "im out" to get up. Rounds run only while a human stands in Game Park, one step every {@link #STEP_MS}.
 * Citizens learn hit or stand from their hands ({@link HitStandPolicy}, remembered across their visits until a
 * restart). Bets, payouts and pickups move real chips between bags; the cards are pictures nobody can loot.
 */
public final class BlackjackTables implements Venue {
    static final long STEP_MS = 1_500;
    /** How far from a table's dealer a player may stand and still be at that table. */
    static final int TABLE_REACH_PX = 520;
    private static final int CITIZEN_SEATS = 2;
    private static final int FLOAT_BETS = 8;
    private static final String[] JOIN = {"deal me in", "deal me", "join", "im in", "i'm in", "can i play", "sit in"};
    private static final String[] LEAVE = {"im out", "i'm out", "cash out", "leaving", "deal me out"};

    private final CasinoHost host;
    private final CasinoContent content;
    private final Map<String, Table> tables = new ConcurrentHashMap<>();
    /** Citizen id -> its role at a table. */
    private final Map<Integer, Role> roles = new ConcurrentHashMap<>();
    /** Citizen id -> how it plays, kept across its visits. */
    private final Map<Integer, HitStandPolicy> policies = new ConcurrentHashMap<>();

    private record Role(CitizenSession session, Table table, boolean dealer, long until) {
    }

    BlackjackTables(CasinoHost host, CasinoContent content) {
        this.host = host;
        this.content = content;
    }

    @Override
    public String name() {
        return "blackjack";
    }

    @Override
    public int mapId() {
        return content.gameParkMap();
    }

    @Override
    public boolean enabled() {
        return host.population().config().CASINO_BLACKJACK_ENABLED && host.population().config().CASINO_CHIPS_ENABLED;
    }

    private long maxBet() {
        return host.population().config().CASINO_MAX_BET;
    }

    @Override
    public boolean wants(Citizen citizen) {
        if (!enabled()) {
            return false;
        }
        long dealers = tables.values().stream().filter(t -> t.dealer != null).count();
        if (dealers < host.population().config().CASINO_BLACKJACK_TABLES) {
            return true;
        }
        return tables.values().stream().anyMatch(t -> t.dealer != null && t.citizenSeats() < CITIZEN_SEATS);
    }

    @Override
    public boolean take(CitizenSession session, long now) {
        Character agent = session.agent();
        if (!enabled() || agent == null || session.entry == null || roles.containsKey(session.characterId)) {
            return false;
        }
        long dealers = tables.values().stream().filter(t -> t.channel == session.channel && t.dealer != null).count();
        long floatMesos = FLOAT_BETS * maxBet();
        if (dealers < host.population().config().CASINO_BLACKJACK_TABLES && agent.getMeso() >= floatMesos + 100_000L) {
            for (int index = 0; index < content.tables().size(); index++) {
                CasinoContent.Table spots = content.tables().get(index);
                Table table = tables.computeIfAbsent(session.channel + ":" + index, key -> new Table(session.channel, spots));
                if (table.dealer == null) {
                    return seat(session, table, true, floatMesos, now);
                }
            }
        }
        for (Table table : tables.values()) {
            if (table.channel == session.channel && table.dealer != null && table.citizenSeats() < CITIZEN_SEATS
                    && table.game.seats().size() < BlackjackGame.MAX_SEATS && agent.getMeso() >= 300_000L) {
                long buyIn = Math.min(agent.getMeso() / 3, maxBet() * (4 + host.random().nextInt(6)));
                return seat(session, table, false, Math.max(100_000L, buyIn), now);
            }
        }
        return false;
    }

    /** It walks to the cashier, buys its chips, then walks to its spot at the table. */
    private boolean seat(CitizenSession session, Table table, boolean dealer, long chips, long now) {
        Character agent = session.agent();
        if (!ChipCashier.buy(agent, chips)) {
            return false;
        }
        Role role = new Role(session, table, dealer, now + host.shiftMs());
        roles.put(session.characterId, role);
        if (dealer) {
            table.dealer = session;
        }
        walk(session, content.cashier());
        host.after(6_000L + host.random().nextInt(4_000), () -> {
            if (roles.get(session.characterId) != role) {
                return;
            }
            Point spot = dealer ? table.spots.dealer() : table.freeSeatSpot();
            walk(session, spot);
            if (!dealer) {
                host.after(4_000L + host.random().nextInt(3_000), () -> {
                    synchronized (table) {
                        if (roles.get(session.characterId) == role && table.dealer != null) {
                            table.game.join(session.characterId, session.name(), false);
                            table.citizens.put(session.characterId, session);
                        }
                    }
                });
            }
        });
        session.journal.add("casino", dealer ? "dealt blackjack in Game Park" : "sat down at a blackjack table");
        return true;
    }

    private void walk(CitizenSession session, Point spot) {
        Character agent = session.agent();
        if (agent == null || agent.getMap() == null || session.entry == null) {
            return;
        }
        Point ground = AgentGroundingService.findGroundPoint(agent.getMap(), new Point(spot.x, spot.y - 1));
        AgentMovementCommandRuntime.moveTo(session.entry, ground != null ? ground : spot, false);
    }

    @Override
    public boolean holds(int characterId) {
        return roles.containsKey(characterId);
    }

    @Override
    public void tick(long now) {
        for (Role role : new ArrayList<>(roles.values())) {
            Character agent = role.session.agent();
            boolean gone = agent == null || agent.getMapId() != content.gameParkMap() || !enabled();
            boolean broke = !role.dealer && agent != null && ChipCashier.holding(agent) < Chips.value(Chips.SNAIL)
                    && role.table.game.phase() == BlackjackGame.Phase.WAITING;
            boolean over = now >= role.until && role.table.game.phase() == BlackjackGame.Phase.WAITING;
            if (gone || broke || over) {
                leave(role, gone ? "left" : broke ? "out of chips" : "shift over");
            }
        }
        for (Table table : tables.values()) {
            if (table.dealer != null && !table.stepping && host.humanOn(table.channel, content.gameParkMap())) {
                table.stepping = true;
                host.after(STEP_MS, table::step);
            }
        }
    }

    private void leave(Role role, String why) {
        if (!roles.remove(role.session.characterId, role)) {
            return;
        }
        Table table = role.table;
        Character agent = role.session.agent();
        synchronized (table) {
            if (role.dealer) {
                host.say(role.session, "casino.bj.closing", Map.of());
                for (BlackjackGame.Seat seat : new ArrayList<>(table.game.seats())) {
                    table.game.leave(seat.id);
                }
                table.sweepCards();
                table.dealer = null;
                for (Role other : new ArrayList<>(roles.values())) {
                    if (other.table == table) {
                        leave(other, "table closed");
                    }
                }
                table.humans.clear();
            } else {
                table.game.leave(role.session.characterId);
                table.citizens.remove(role.session.characterId);
            }
        }
        if (agent != null) {
            walk(role.session, content.cashier());
            host.after(5_000L, () -> ChipCashier.cashOut(agent));
        }
        host.after(8_000L, () -> host.done(role.session, "blackjack: " + why));
    }

    @Override
    public boolean heard(Character human, String text, long now) {
        if (!enabled() || human == null || human.getMapId() != content.gameParkMap()) {
            return false;
        }
        Table table = nearest(human);
        if (table == null) {
            return false;
        }
        String said = text.toLowerCase(Locale.ROOT).trim();
        synchronized (table) {
            boolean seated = table.game.seat(human.getId()) != null;
            if (!seated && matches(said, JOIN)) {
                if (table.game.seats().size() >= BlackjackGame.MAX_SEATS) {
                    host.say(table.dealer, "casino.bj.full", Map.of("name", human.getName()));
                } else if (table.game.join(human.getId(), human.getName(), true)) {
                    table.humans.put(human.getId(), human);
                    host.say(table.dealer, "casino.bj.welcome", Map.of("name", human.getName(),
                            "max", Chips.mesos(maxBet())));
                }
                return true;
            }
            if (seated && matches(said, LEAVE)) {
                table.game.leave(human.getId());
                table.humans.remove(human.getId());
                host.say(table.dealer, "casino.bj.bye", Map.of("name", human.getName()));
                return true;
            }
            if (seated && (said.contains("hit") || said.contains("stand") || said.contains("stay"))) {
                table.game.heard(human.getId(), said);
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String said, String[] phrases) {
        for (String phrase : phrases) {
            if (said.equals(phrase) || said.startsWith(phrase + " ") || said.endsWith(" " + phrase)) {
                return true;
            }
        }
        return false;
    }

    /** The dealt table the human stands at, null when none is near. */
    private Table nearest(Character human) {
        Table best = null;
        double bestDistance = TABLE_REACH_PX;
        for (Table table : tables.values()) {
            if (table.dealer == null || table.channel != human.getClient().getChannel()) {
                continue;
            }
            double distance = table.spots.dealer().distance(human.getPosition());
            if (distance <= bestDistance) {
                best = table;
                bestDistance = distance;
            }
        }
        return best;
    }

    @Override
    public void stop() {
        for (Role role : new ArrayList<>(roles.values())) {
            leave(role, "casino closed");
        }
    }

    @Override
    public String status() {
        StringBuilder out = new StringBuilder("blackjack " + (enabled() ? "on" : "off") + ":");
        for (Map.Entry<String, Table> entry : tables.entrySet()) {
            Table table = entry.getValue();
            if (table.dealer != null) {
                out.append(" [ch").append(entry.getKey()).append(' ').append(table.dealer.name()).append(' ')
                        .append(table.game.phase()).append(", ").append(table.game.seats().size()).append(" seated]");
            }
        }
        return out.toString();
    }

    /** One table on one channel: its game and what it put on the floor. */
    private final class Table implements BlackjackGame.Table {
        final int channel;
        final CasinoContent.Table spots;
        final BlackjackGame game;
        volatile CitizenSession dealer;
        volatile boolean stepping;
        final Map<Integer, Character> humans = new ConcurrentHashMap<>();
        final Map<Integer, CitizenSession> citizens = new ConcurrentHashMap<>();
        /** Seat id -> the chips it put down this round. */
        final Map<Integer, List<MapItem>> bets = new HashMap<>();
        final List<MapItem> cards = new ArrayList<>();
        MapItem hole;

        Table(int channel, CasinoContent.Table spots) {
            this.channel = channel;
            this.spots = spots;
            this.game = new BlackjackGame(this, host.random());
        }

        int citizenSeats() {
            int count = 0;
            for (Role role : roles.values()) {
                count += role.table == this && !role.dealer ? 1 : 0;
            }
            return count;
        }

        Point freeSeatSpot() {
            List<Point> seats = new ArrayList<>(spots.seats());
            for (BlackjackGame.Seat seat : game.seats()) {
                Character chr = character(seat);
                if (chr != null) {
                    seats.removeIf(spot -> spot.distance(chr.getPosition()) < 60);
                }
            }
            return seats.isEmpty() ? spots.seats().get(0) : seats.get(host.random().nextInt(seats.size()));
        }

        void step() {
            synchronized (this) {
                if (dealer == null || !host.humanOn(channel, content.gameParkMap())) {
                    stepping = false;
                    return;
                }
                try {
                    game.tick(System.currentTimeMillis());
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(BlackjackTables.class).debug("blackjack step failed", failure);
                }
            }
            host.after(STEP_MS, this::step);
        }

        Character dealerChr() {
            CitizenSession session = dealer;
            return session == null ? null : session.agent();
        }

        Character character(BlackjackGame.Seat seat) {
            if (seat.human) {
                return humans.get(seat.id);
            }
            CitizenSession session = citizens.get(seat.id);
            return session == null ? null : session.agent();
        }

        Point seatPoint(BlackjackGame.Seat seat) {
            Character chr = character(seat);
            return chr != null ? new Point(chr.getPosition()) : spots.seats().get(0);
        }

        void sweepCards() {
            Character by = dealerChr();
            if (by != null) {
                Floor.sweep(by, cards);
            }
            hole = null;
        }

        @Override
        public void say(String trigger, Map<String, Object> args) {
            CitizenSession session = dealer;
            if (session != null) {
                host.say(session, trigger, args);
            }
            if ("casino.bj.bets".equals(trigger)) {
                for (CitizenSession citizen : citizens.values()) {
                    host.after(1_500L + host.random().nextInt(5_000), () -> citizenBet(citizen));
                }
            }
        }

        private void citizenBet(CitizenSession citizen) {
            Character agent = citizen.agent();
            if (agent == null) {
                return;
            }
            long stack = ChipCashier.holding(agent);
            long want = Math.min(maxBet(), Math.max(Chips.value(Chips.SNAIL), stack * (5 + host.random().nextInt(11)) / 100));
            Map<Integer, Integer> chips = Chips.from(held(agent), want);
            if (!chips.isEmpty()) {
                host.runAs(citizen, () -> Floor.dropChips(agent, chips, agent.getPosition(), null));
            }
        }

        @Override
        public void deal(BlackjackGame.Seat seat, int index, String card, boolean faceDown) {
            Character by = dealerChr();
            if (by == null) {
                return;
            }
            Point at = seat == null ? new Point(spots.dealer().x - 20 + index * 35, spots.dealer().y)
                    : new Point(seatPoint(seat).x + 30 + index * 30, seatPoint(seat).y);
            MapItem shown = Floor.show(by, faceDown ? Cards.CARD_BACK : Cards.item(card), at);
            if (shown != null) {
                cards.add(shown);
                if (faceDown) {
                    hole = shown;
                }
            }
        }

        @Override
        public void reveal(String card) {
            Character by = dealerChr();
            if (by == null || hole == null) {
                return;
            }
            Point at = new Point(hole.getPosition());
            List<MapItem> back = new ArrayList<>(List.of(hole));
            cards.remove(hole);
            Floor.sweep(by, back);
            hole = null;
            MapItem shown = Floor.show(by, Cards.item(card), at);
            if (shown != null) {
                cards.add(shown);
            }
        }

        @Override
        public Map<Integer, Integer> chipsDown(BlackjackGame.Seat seat) {
            Character chr = character(seat);
            if (chr == null || chr.getMap() == null) {
                return Map.of();
            }
            List<MapItem> chips = Floor.chipsBy(chr.getMap(), chr, chr.getPosition());
            Map<Integer, Integer> counted = Floor.count(chips);
            if (Chips.value(counted) > maxBet()) {
                say("casino.bj.overmax", Map.of("name", seat.name, "max", Chips.mesos(maxBet())));
                return Map.of();
            }
            bets.put(seat.id, chips);
            return counted;
        }

        @Override
        public boolean betStillDown(BlackjackGame.Seat seat) {
            Character by = dealerChr();
            return by != null && Floor.allDown(by.getMap(), bets.getOrDefault(seat.id, List.of()));
        }

        @Override
        public void pay(BlackjackGame.Seat seat, Map<Integer, Integer> chips) {
            Character by = dealerChr();
            Character to = character(seat);
            if (by == null || to == null) {
                return;
            }
            Map<Integer, Integer> held = held(by);
            for (Map.Entry<Integer, Integer> chip : chips.entrySet()) {
                int missing = chip.getValue() - held.getOrDefault(chip.getKey(), 0);
                if (missing > 0) {
                    ChipCashier.buy(by, (long) missing * Chips.value(chip.getKey())); // a runner to the cashier
                }
            }
            List<MapItem> paid = Floor.dropChips(by, chips, seatPoint(seat), to);
            CitizenSession citizen = citizens.get(seat.id);
            if (citizen != null) {
                List<MapItem> mine = new ArrayList<>(bets.getOrDefault(seat.id, List.of()));
                mine.addAll(paid);
                host.after(2_000L + host.random().nextInt(1_500), () -> host.runAs(citizen, () -> Floor.pickUp(to, mine)));
            }
        }

        @Override
        public void collect(BlackjackGame.Seat seat) {
            Character by = dealerChr();
            List<MapItem> chips = bets.remove(seat.id);
            if (by != null && chips != null) {
                host.runAs(dealer, () -> Floor.pickUp(by, chips));
            }
        }

        @Override
        public void clearCards() {
            sweepCards();
            bets.clear();
        }

        @Override
        public void turn(BlackjackGame.Seat seat, int total, boolean soft, int dealerUp) {
            CitizenSession citizen = citizens.get(seat.id);
            if (citizen == null) {
                return; // a player: the dealer's line asks them
            }
            HitStandPolicy policy = policies.computeIfAbsent(seat.id, id -> new HitStandPolicy(
                    0.05 + Math.floorMod(id * 37, 20) / 100.0, new java.util.Random(id * 0x9E3779B97F4A7C15L)));
            host.after(1_200L + host.random().nextInt(2_500), () -> {
                synchronized (this) {
                    boolean hit = policy.hit(total, soft, dealerUp);
                    host.say(citizen, hit ? "casino.bj.hit" : "casino.bj.stand", Map.of());
                    game.decide(seat.id, hit);
                }
            });
        }

        @Override
        public boolean present(BlackjackGame.Seat seat) {
            Character chr = character(seat);
            if (chr == null || chr.getMapId() != content.gameParkMap() || (seat.human && !chr.isLoggedinWorld())) {
                return false;
            }
            return !seat.human || chr.getPosition().distance(spots.dealer()) <= TABLE_REACH_PX + 200;
        }

        @Override
        public void left(BlackjackGame.Seat seat, String reason) {
            if (reason != null) {
                say(reason, Map.of("name", seat.name));
            }
            humans.remove(seat.id);
            Role role = roles.get(seat.id);
            if (role != null && role.table == this && !role.dealer) {
                host.after(1_000L, () -> leave(role, reason == null ? "walked off" : reason));
            }
        }

        @Override
        public void settled(BlackjackGame.Seat seat, BlackjackGame.Result result, double reward) {
            CitizenSession citizen = citizens.get(seat.id);
            if (citizen == null) {
                return;
            }
            HitStandPolicy policy = policies.get(seat.id);
            if (policy != null) {
                policy.settle(reward);
            }
            Character agent = citizen.agent();
            if (result == BlackjackGame.Result.PUSH && agent != null) {
                List<MapItem> mine = new ArrayList<>(bets.getOrDefault(seat.id, List.of()));
                host.after(1_500L + host.random().nextInt(1_500), () -> host.runAs(citizen, () -> Floor.pickUp(agent, mine)));
            }
            if (host.random().nextInt(100) < 35) {
                String trigger = switch (result) {
                    case WIN -> "casino.bj.react.win";
                    case BLACKJACK -> "casino.bj.react.blackjack";
                    case PUSH -> "casino.bj.react.push";
                    case LOSE -> seat.busted ? "casino.bj.react.bust" : "casino.bj.react.lose";
                };
                host.after(1_000L + host.random().nextInt(2_000), () -> host.say(citizen, trigger, Map.of()));
            }
        }
    }

    /** The chips a character holds, item id to count. */
    static Map<Integer, Integer> held(Character chr) {
        Map<Integer, Integer> out = new HashMap<>();
        for (int chip : Chips.ids()) {
            int count = chr.getItemQuantity(chip, false);
            if (count > 0) {
                out.put(chip, count);
            }
        }
        return out;
    }
}
