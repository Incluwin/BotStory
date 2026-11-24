package server.pvp;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import net.server.PlayerCoolDownValueHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.agents.capabilities.combat.learning.AgentCombatPolicyTable;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The duel learner (docs/WORLD_SYSTEMS_PLAN.md I1.2): it learns from bouts what the prior brain does not do, is used
 * only where it beat the prior brain over the measured pairs and only while DUEL_LEARNING_ENABLED, and a self-play bout
 * leaves no row, pairing, item or cooldown behind.
 */
class DuelLearnerTest {
    private static final int JOB = 111;
    private static final int SWING = 1001004;

    @TempDir
    Path folder;
    private DuelPolicies before;
    private boolean wasEnabled;
    private int wasDecisions;
    private int wasPairs;

    @BeforeEach
    void setUp() {
        before = DuelPolicies.use(new DuelPolicies(folder));
        wasEnabled = YamlConfig.config.pvp.DUEL_LEARNING_ENABLED;
        wasDecisions = YamlConfig.config.pvp.DUEL_LEARN_DECISIONS;
        wasPairs = YamlConfig.config.pvp.DUEL_EVAL_PAIRS;
        YamlConfig.config.pvp.DUEL_LEARN_DECISIONS = 100;
        YamlConfig.config.pvp.DUEL_EVAL_PAIRS = 40;
    }

    @AfterEach
    void tearDown() {
        DuelPolicies.use(before);
        YamlConfig.config.pvp.DUEL_LEARNING_ENABLED = wasEnabled;
        YamlConfig.config.pvp.DUEL_LEARN_DECISIONS = wasDecisions;
        YamlConfig.config.pvp.DUEL_EVAL_PAIRS = wasPairs;
    }

    /** {damage dealt, damage taken, won} of one round; the brain picks a swing or a step back each half second. */
    private static double[] round(BiFunction<DuelLearner.Situation, double[], Integer> brain, Random random,
                                  DuelLearner.Seat seat) {
        double own = 1d;
        double foe = 1d;
        long now = 1_000L;
        boolean swinging = false;
        while (own > 0 && foe > 0 && now < 90_000L) {
            // the foe winds up a heavy swing now and then; whoever stands in it is hit hard, whoever steps back is not
            DuelLearner.Situation situation = new DuelLearner.Situation(60, 120, 0, own, foe, 1d, false, swinging, false);
            int action = brain.apply(situation, new double[] {(1 - foe) - (1 - own), now});
            if (action == SWING) {
                foe -= 0.04d * (0.8d + 0.4d * random.nextDouble());
                own -= swinging ? 0.12d : 0.02d;
            }
            swinging = random.nextInt(3) == 0;
            now += 500L;
        }
        int won = foe <= 0 && own > 0 ? 1 : own <= 0 ? -1 : 0;
        if (seat != null) {
            seat.end((1 - foe) - (1 - own), won, now);
        }
        return new double[] {1 - foe, 1 - own, won};
    }

    private static final List<Integer> LEGAL = List.of(SWING, DuelLearner.RETREAT);

    private static BiFunction<DuelLearner.Situation, double[], Integer> seated(DuelLearner.Seat seat) {
        return (situation, at) -> seat.choose(situation, LEGAL, SWING, at[0], (long) at[1]);
    }

    /** Learn, measure, gate: the whole pipeline on a toy fight where stepping out of a wind-up pays. */
    @Test
    void itLearnsWhatThePriorDoesNotDoAndTheGateSeesIt() {
        Random random = new Random(11);
        DuelPolicies policies = DuelPolicies.get();
        assertEquals(DuelPolicies.Verdict.LEARNING, policies.verdict(JOB));
        for (int i = 0; i < 600; i++) {
            DuelLearner.Seat seat = policies.learningSeat(JOB);
            round(seated(seat), random, seat);
            policies.learned(JOB, seat.decisions());
        }
        assertEquals(DuelPolicies.Verdict.MEASURING, policies.verdict(JOB));
        YamlConfig.config.pvp.DUEL_LEARNING_ENABLED = true;
        assertNull(policies.liveSeat(JOB), "not measured yet: the prior brain fights");
        for (int i = 0; i < 40; i++) {
            double[] learned = round(seated(policies.frozenSeat(JOB)), random, null);
            double[] prior = round((situation, at) -> SWING, random, null);
            policies.measured(JOB, new DuelPolicies.Pair(1 + i % 5, i % 3, learned[0] - learned[1] + learned[2],
                    prior[0] - prior[1] + prior[2]));
        }
        assertEquals(DuelPolicies.Verdict.LEARNED_WON, policies.verdict(JOB), policies.status().toString());
        assertNotNull(policies.liveSeat(JOB));
        assertEquals(DuelPolicies.Verdict.LEARNED_WON, new DuelPolicies(folder).verdict(JOB), "kept over a restart");
        YamlConfig.config.pvp.DUEL_LEARNING_ENABLED = false;
        assertNull(policies.liveSeat(JOB), "off: every bot fights by the prior brain");
    }

    @Test
    void aJobThePriorBeatLearnsOn() {
        DuelPolicies policies = DuelPolicies.get();
        policies.learned(JOB, 100);
        Random random = new Random(2);
        for (int i = 0; i < 39; i++) {
            policies.measured(JOB, new DuelPolicies.Pair(1 + i % 5, i % 3, random.nextGaussian(), random.nextGaussian()));
            assertEquals(DuelPolicies.Verdict.MEASURING, policies.verdict(JOB));
        }
        policies.measured(JOB, new DuelPolicies.Pair(1, 0, 0, 0));
        assertEquals(DuelPolicies.Verdict.LEARNING, policies.verdict(JOB), "no better than the prior: its target grows");
        policies.learned(JOB, 50);
        assertEquals(DuelPolicies.Verdict.MEASURING, policies.verdict(JOB), "measured anew, from no pairs");
        assertEquals(0, policies.pairsAgainst(JOB, 1, 0));
    }

    @Test
    void pairsAgainstOneClassAloneProveNothing() {
        List<DuelPolicies.Pair> pairs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            pairs.add(new DuelPolicies.Pair(1, 0, 1d, 0d));
        }
        assertEquals(DuelPolicies.Verdict.MEASURING, DuelPolicies.verdict(10, 10, pairs, 40));
        pairs.add(new DuelPolicies.Pair(2, 0, 1d, 0d));
        pairs.add(new DuelPolicies.Pair(3, 0, 1d, 0d));
        assertEquals(DuelPolicies.Verdict.LEARNED_WON, DuelPolicies.verdict(10, 10, pairs, 40));
    }

    @Test
    void aLiveFighterFollowsItsTableOnlyWhereItHasTheEvidence() {
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        DuelLearner.Situation situation = new DuelLearner.Situation(60, 120, 0, 1, 1, 1, false, false, false);
        DuelLearner.Seat live = new DuelLearner.Seat(DuelLearner.Mode.LIVE, table, 1d);
        assertEquals(SWING, live.choose(situation, LEGAL, SWING, 0, 1), "an empty table: the prior");
        for (int i = 0; i < DuelLearner.LIVE_MIN_VISITS; i++) {
            assertEquals(SWING, live.choose(situation, LEGAL, SWING, 0, 1));
            table.update(situation.key(), DuelLearner.RETREAT, 1d, 0.02, 10, 0, 1);
        }
        assertEquals(SWING, live.choose(situation, LEGAL, SWING, 0, 1), "the prior itself was never tried there");
        for (int i = 0; i < DuelLearner.LIVE_MIN_VISITS; i++) {
            table.update(situation.key(), SWING, 0d, 0.02, 10, 0, 1);
        }
        assertEquals(DuelLearner.RETREAT, live.choose(situation, LEGAL, SWING, 0, 1));
        assertEquals(0, live.decisions(), "live play learns nothing");
    }

    @Test
    void situationsAreCoarse() {
        DuelLearner.Situation a = new DuelLearner.Situation(60, 120, 0, 0.9, 0.5, 1, false, false, true);
        assertEquals("close|level|hp2|foe1|melee|tonic", a.key());
        assertEquals("far|above|hp0|foe0|dry|ranged|swinging",
                new DuelLearner.Situation(500, 120, 90, 0.1, 0.2, 0.1, true, true, false).key());
        assertNotEquals(a.key(), new DuelLearner.Situation(300, 120, 0, 0.9, 0.5, 1, false, false, true).key());
        assertTrue(DuelLearnedBrain.ranged(410) && DuelLearnedBrain.ranged(312) && DuelLearnedBrain.ranged(231)
                && DuelLearnedBrain.ranged(522) && DuelLearnedBrain.ranged(1411));
        assertFalse(DuelLearnedBrain.ranged(112) || DuelLearnedBrain.ranged(421) || DuelLearnedBrain.ranged(512)
                || DuelLearnedBrain.ranged(2112));
        assertEquals(List.of(7, 5, 6), DuelLearnedBrain.chosenFirst(7, List.of(5, 6, 7)));
        assertEquals(new Point(260, 0), DuelLearnedBrain.retreatSpot(new Point(100, 0), new Point(50, 0), p -> p));
        assertNull(DuelLearnedBrain.retreatSpot(new Point(100, 0), new Point(50, 0), p -> new Point(p.x, 300)));
    }

    private static Character fighter(int id, MapleMap map) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn("F" + id);
        when(chr.getMap()).thenReturn(map);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getJob()).thenReturn(Job.CRUSADER);
        when(chr.getCurrentMaxHp()).thenReturn(1_000);
        when(map.getCharacterById(id)).thenReturn(chr);
        return chr;
    }

    /** A closed bout ends: the seats get their outcome, and no row, learned rate or pairing is left behind. */
    @Test
    void aSelfPlayRoundTeachesItsSeatsAndLeavesNothingElse() {
        MapleMap map = mock(MapleMap.class);
        Character a = fighter(41, map);
        Character b = fighter(42, map);
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        Map<Integer, DuelLearner.Seat> seats = new HashMap<>();
        seats.put(41, new DuelLearner.Seat(DuelLearner.Mode.LEARN, table, 0d));
        seats.put(42, new DuelLearner.Seat(DuelLearner.Mode.LEARN, table, 0d));
        DuelField field = new DuelField(5, map, List.of(a), List.of(b), new DuelField.Rules(90, 3, 25, 8000), seats);
        field.fighters().forEach(f -> f.mind.seat = seats.get(f.chr.getId())); // what begin() does for a bot
        DuelLearner.Situation situation = new DuelLearner.Situation(60, 120, 0, 1, 1, 1, false, false, false);
        seats.get(41).choose(situation, LEGAL, SWING, 0, System.currentTimeMillis() - 500);
        List<List<DuelLog.Row>> written = new ArrayList<>();
        var sink = DuelField.rowSink;
        DuelField.rowSink = written::add;
        int pairings = PvpPairings.played().size();
        try {
            field.knockOutByGm("F42");
            field.tick(System.currentTimeMillis());
        } finally {
            DuelField.rowSink = sink;
        }
        assertEquals(DuelField.State.ENDED, field.state());
        assertEquals(0, field.result().winnerSide());
        assertTrue(written.isEmpty(), "no duel row, so no learned rate, toughness or matchup");
        assertEquals(pairings, PvpPairings.played().size());
        assertEquals(1, table.visits(situation.key(), SWING));
        assertTrue(table.cell(situation.key(), SWING).value() > DuelLearner.PRIOR_EDGE, "the win came back to its last swing");
    }

    @Test
    void aRoundCutShortTeachesNothing() {
        MapleMap map = mock(MapleMap.class);
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        DuelLearner.Seat seat = new DuelLearner.Seat(DuelLearner.Mode.LEARN, table, 0d);
        DuelField field = new DuelField(6, map, List.of(fighter(41, map)), List.of(fighter(42, map)),
                new DuelField.Rules(90, 3, 25, 8000), Map.of(41, seat));
        field.fighters().getFirst().mind.seat = seat;
        DuelLearner.Situation situation = new DuelLearner.Situation(60, 120, 0, 1, 1, 1, false, false, false);
        seat.choose(situation, LEGAL, SWING, 0, System.currentTimeMillis() - 500);
        field.stop();
        assertEquals(0, table.visits(situation.key(), SWING));
    }

    /** A thrown star and a fresh cooldown come back as they were; nothing else is touched. */
    @Test
    void aBoutGivesBackWhatItsSwingsSpent() {
        MapleMap map = mock(MapleMap.class);
        Character a = fighter(41, map);
        Character b = fighter(42, map);
        for (Character chr : List.of(a, b)) {
            when(chr.getInventory(InventoryType.USE)).thenReturn(new Inventory(chr, InventoryType.USE, (byte) 24));
            when(chr.getInventory(InventoryType.ETC)).thenReturn(new Inventory(chr, InventoryType.ETC, (byte) 24));
            when(chr.getAllCooldowns()).thenReturn(List.of(new PlayerCoolDownValueHolder(7, 0, 1)));
        }
        Item stars = new Item(2070000, (short) 1, (short) 500);
        a.getInventory(InventoryType.USE).addItemFromDB(stars);
        DuelSelfPlay bout = new DuelSelfPlay(a, b, false, true);
        assertTrue(bout.more());
        stars.setQuantity((short) 431);
        when(a.getAllCooldowns()).thenReturn(List.of(new PlayerCoolDownValueHolder(7, 0, 1),
                new PlayerCoolDownValueHolder(1111008, 0, 1)));

        bout.finish();

        assertEquals(500, stars.getQuantity());
        verify(a).removeCooldown(1111008);
        verify(a, org.mockito.Mockito.never()).removeCooldown(7);
        assertFalse(bout.more());
    }
}
