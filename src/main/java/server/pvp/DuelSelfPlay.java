package server.pvp;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.PvpConfig;
import config.YamlConfig;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One self-play bout between two bots on a closed floor (docs/WORLD_SYSTEMS_PLAN.md I1.2): rounds to learn from, or a
 * pair of rounds to measure by, under the coliseum's rules (tonics, no own potions). It leaves no ladder result, duel
 * row, learned rate, pairing, say or memory behind, and gives each fighter back the items and cooldowns its swings
 * spent; HP and MP the duel gives back itself. Who opens the floor, holds the fighters and steps this: the coliseum's
 * SelfPlay.
 */
public final class DuelSelfPlay {
    /** A learning bout's rounds. */
    static final int LEARN_ROUNDS = 3;
    /** A measuring bout's: one with the learned table, one with the prior brain. */
    static final int MEASURE_ROUNDS = 2;

    private record Stack(InventoryType type, short slot, int itemId, short quantity) {
    }

    /** What a fighter carried and had cooling before the bout. */
    private record Kept(List<Stack> stacks, List<Integer> cooling) {
    }

    private final Character a;
    private final Character b;
    /** Measuring: {@code a} plays its job's table in one round and the prior brain in the other; {@code b} the prior. */
    private final boolean measuring;
    private final boolean learnedFirst;
    private final Map<Integer, Kept> kept = new HashMap<>();
    private final double[] margins = new double[2];
    private Map<Integer, DuelLearner.Seat> seats = Map.of();
    private int round;
    private DuelField field;
    private volatile DuelField.Result result;
    private boolean broken;

    public DuelSelfPlay(Character a, Character b, boolean measuring, boolean learnedFirst) {
        this.a = a;
        this.b = b;
        this.measuring = measuring;
        this.learnedFirst = learnedFirst;
        kept.put(a.getId(), keep(a));
        kept.put(b.getId(), keep(b));
    }

    public Character a() {
        return a;
    }

    public Character b() {
        return b;
    }

    /** True while rounds are left to fight (none once a round would not start, was stopped or a fighter left). */
    public boolean more() {
        return !broken && round < (measuring ? MEASURE_ROUNDS : LEARN_ROUNDS);
    }

    public boolean fighting() {
        return field != null;
    }

    /** Starts the next round on {@code map}; false when it would not start. */
    public boolean start(MapleMap map) {
        PvpConfig pvp = YamlConfig.config.pvp;
        Map<Integer, DuelLearner.Seat> next = new HashMap<>();
        if (!measuring) {
            next.put(a.getId(), DuelPolicies.get().learningSeat(a.getJob().getId()));
            next.put(b.getId(), DuelPolicies.get().learningSeat(b.getJob().getId()));
        } else if (learnedRound()) {
            next.put(a.getId(), DuelPolicies.get().frozenSeat(a.getJob().getId()));
        }
        seats = next;
        result = null;
        try {
            field = DuelRegistry.start(map, List.of(a), List.of(b), new DuelField.Rules(pvp.COLISEUM_ROUND_SECONDS,
                    pvp.COLISEUM_TONICS, pvp.COLISEUM_TONIC_PERCENT, pvp.COLISEUM_TONIC_GAP_MS), next);
        } catch (IllegalStateException refused) {
            broken = true;
            return false;
        }
        field.onEnd(done -> result = done);
        return true;
    }

    private boolean learnedRound() {
        return (round == 0) == learnedFirst;
    }

    /** Books the round when it has ended; true then. A round that was cut short books nothing and ends the bout. */
    public boolean roundOver() {
        DuelField.Result done = result;
        if (field == null || done == null) {
            return false;
        }
        if (done.rows().stream().anyMatch(DuelLog.Row::left) || "it was stopped".equals(done.why())) {
            broken = true;
        } else if (!measuring) {
            DuelPolicies.get().learned(a.getJob().getId(), seats.get(a.getId()).decisions());
            DuelPolicies.get().learned(b.getJob().getId(), seats.get(b.getId()).decisions());
        } else {
            margins[learnedRound() ? 0 : 1] = DuelLearnedBrain.score(fighter(a), fighter(b))
                    + (done.winnerSide() < 0 ? 0 : done.winnerSide() == 0 ? DuelLearner.WIN : -DuelLearner.WIN);
            if (round == MEASURE_ROUNDS - 1) {
                DuelPolicies.get().measured(a.getJob().getId(), new DuelPolicies.Pair(classOf(b), band(b), margins[0], margins[1]));
            }
        }
        field = null;
        round++;
        return true;
    }

    private DuelField.Fighter fighter(Character chr) {
        return field.fighters().stream().filter(f -> f.character() == chr).findFirst().orElseThrow();
    }

    /** The five classes as the hundreds digit has them (Cygnus and Aran with their Explorer class). */
    public static int classOf(Character chr) {
        return (chr.getJob() == null ? 0 : chr.getJob().getId()) / 100 % 10;
    }

    /** The plan's three level bands. */
    public static int band(Character chr) {
        return chr.getLevel() < 70 ? 0 : chr.getLevel() < 110 ? 1 : 2;
    }

    /** The bout is over or called off: a running round stops, and each fighter has back what it spent. */
    public void finish() {
        broken = true;
        if (field != null) {
            field.stop();
            field = null;
        }
        giveBack(a, kept.remove(a.getId()));
        giveBack(b, kept.remove(b.getId()));
    }

    private static Kept keep(Character chr) {
        List<Stack> stacks = new ArrayList<>();
        for (InventoryType type : new InventoryType[] {InventoryType.USE, InventoryType.ETC}) {
            for (Item item : chr.getInventory(type).list()) {
                stacks.add(new Stack(type, item.getPosition(), item.getItemId(), item.getQuantity()));
            }
        }
        List<Integer> cooling = new ArrayList<>();
        chr.getAllCooldowns().forEach(holder -> cooling.add(holder.skillId));
        return new Kept(stacks, cooling);
    }

    private static void giveBack(Character chr, Kept before) {
        if (before == null) {
            return;
        }
        for (Stack was : before.stacks()) {
            Item now = chr.getInventory(was.type()).getItem(was.slot());
            if (now == null) {
                AgentInventoryGatewayRuntime.inventory().addItem(chr, was.itemId(), was.quantity()); // used up
            } else if (now.getItemId() == was.itemId() && now.getQuantity() < was.quantity()) {
                now.setQuantity(was.quantity());
            }
        }
        chr.getAllCooldowns().forEach(holder -> {
            if (!before.cooling().contains(holder.skillId)) {
                chr.removeCooldown(holder.skillId);
            }
        });
    }
}
