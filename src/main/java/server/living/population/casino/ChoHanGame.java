package server.living.population.casino;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Cho-han dice at a host's table, ported from SoloMapling's DiceBot: a player drops chips in front of the host and calls
 * cho (even) or han (odd); the host rolls two dice, and an even match pays the bet at evens, a miss goes to the host.
 * One bet at a time. {@link #tick} moves it one step; the world goes through {@link Table}.
 */
final class ChoHanGame {
    static final long ROLL_MS = 2_500;
    static final long SETTLE_MS = 1_500;

    enum Phase { OPEN, ROLLING, SETTLE }

    /** What the host does in the world. */
    interface Table {
        /** The host says a line ({@code trigger} casino.dice.*). */
        void say(String trigger, Map<String, Object> args);

        /** The chips the bettor has dropped in front of the host, item id to count. */
        Map<Integer, Integer> chipsDown(int bettorId);

        /** Whether the bettor's chips are still on the floor. */
        boolean betStillDown(int bettorId);

        void pay(int bettorId, Map<Integer, Integer> chips);

        void collect(int bettorId);

        /** The bet is settled: {@code won} or not, for its value in mesos. */
        void settled(int bettorId, boolean won, long value);
    }

    private final Table table;
    private final Random random;
    private Phase phase = Phase.OPEN;
    private long until;
    private int bettor;
    private String bettorName;
    private boolean cho;
    private final Map<Integer, Integer> bet = new LinkedHashMap<>();
    private int[] dice;

    ChoHanGame(Table table, Random random) {
        this.table = table;
        this.random = random;
    }

    Phase phase() {
        return phase;
    }

    /** The id of the player whose bet is down; 0 when the table is open. */
    int bettor() {
        return phase == Phase.OPEN ? 0 : bettor;
    }

    /** A player calls cho or han: true when the bet is taken (chips were down and the table was open). */
    boolean call(int id, String name, boolean even, long now) {
        if (phase != Phase.OPEN) {
            return false;
        }
        Map<Integer, Integer> chips = table.chipsDown(id);
        if (chips.isEmpty()) {
            table.say("casino.dice.nobet", Map.of("name", name));
            return false;
        }
        bettor = id;
        bettorName = name;
        cho = even;
        bet.clear();
        bet.putAll(chips);
        table.say("casino.dice.taken", Map.of("name", name, "bet", Chips.describe(chips), "side", even ? "cho" : "han"));
        phase = Phase.ROLLING;
        until = now + ROLL_MS;
        return true;
    }

    /** The bettor walked off before the roll: its chips stay for the host. */
    void abandon(int id) {
        if (phase != Phase.OPEN && bettor == id) {
            table.collect(id);
            table.settled(id, false, Chips.value(bet));
            phase = Phase.OPEN;
        }
    }

    void tick(long now) {
        if (phase == Phase.OPEN || now < until) {
            return;
        }
        if (phase == Phase.ROLLING) {
            dice = new int[]{1 + random.nextInt(6), 1 + random.nextInt(6)};
            boolean even = (dice[0] + dice[1]) % 2 == 0;
            table.say("casino.dice.result", Map.of("a", dice[0], "b", dice[1], "side", even ? "cho" : "han"));
            phase = Phase.SETTLE;
            until = now + SETTLE_MS;
            return;
        }
        boolean even = (dice[0] + dice[1]) % 2 == 0;
        long value = Chips.value(bet);
        if (!table.betStillDown(bettor)) {
            table.say("casino.dice.tookback", Map.of("name", bettorName));
            table.settled(bettor, false, value);
        } else if (even == cho) {
            table.pay(bettor, bet);
            table.say("casino.dice.win", Map.of("name", bettorName, "bet", Chips.describe(bet)));
            table.settled(bettor, true, value);
        } else {
            table.collect(bettor);
            table.say("casino.dice.lose", Map.of("name", bettorName));
            table.settled(bettor, false, value);
        }
        phase = Phase.OPEN;
    }

    /** The dice of the last roll, for tests. */
    int[] dice() {
        return dice;
    }
}
