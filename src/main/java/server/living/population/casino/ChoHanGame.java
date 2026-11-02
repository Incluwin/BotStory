package server.living.population.casino;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Cho-han dice at a host's table, ported from SoloMapling's DiceBot: a player drops chips in front of the host and calls
 * cho (even) or han (odd); the host takes the chips in, rolls two dice, and pays a match the stake and as much again; a
 * miss stays with the host. One bet at a time; a host that closes mid-roll hands the bet back. {@link #tick} moves it one step; the world goes through {@link Table}.
 */
final class ChoHanGame {
    static final long ROLL_MS = 2_500;
    static final long SETTLE_MS = 1_500;

    enum Phase { OPEN, ROLLING, SETTLE }

    /** What the host does in the world. */
    interface Table {
        /** The host says a line ({@code trigger} casino.dice.*). */
        void say(String trigger, Map<String, Object> args);

        /** The host takes in the chips the bettor dropped by it: what it took, item id to count; empty when none. */
        Map<Integer, Integer> takeBet(int bettorId);

        /** The host pays the bettor {@code mesos} in chips. */
        void pay(int bettorId, long mesos);

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
        Map<Integer, Integer> chips = table.takeBet(id);
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

    /** The bettor walked off before the roll: the host keeps its bet. */
    void abandon(int id) {
        if (phase != Phase.OPEN && bettor == id) {
            table.settled(id, false, Chips.value(bet));
            phase = Phase.OPEN;
        }
    }

    /** The host closes mid-roll: the bet is handed back. */
    void close() {
        if (phase != Phase.OPEN) {
            table.pay(bettor, Chips.value(bet));
            table.settled(bettor, false, 0);
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
        if (even == cho) {
            table.pay(bettor, value * 2);
            table.say("casino.dice.win", Map.of("name", bettorName, "bet", Chips.describe(bet)));
            table.settled(bettor, true, value);
        } else {
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
