package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;
import static server.living.roster.BenchHarness.OTHER_FIELD;

/**
 * "Your call" as a standing intent (docs/BOT_MANAGEMENT_PLAN.md P5, "BenchYieldModel / BenchDutyChooser", decision 13 a):
 * the row keeps living_roster.your_call, and before each of its bench slices the dispatcher lets the companion pick its
 * duty and camp again on what the last slices measured. The real loop (choose, hunt a live slice, record its yield,
 * choose again) tries each camp and settles on the better one; a duty or camp its owner gives it ends its call.
 */
class BenchYourCallTest {
    private static final double PRIOR = 1_000.0d;
    /** EXP an hour at each camp, as a share of the prior. */
    private static final Map<Integer, Double> TRUTH = Map.of(FIELD, 0.5d, OTHER_FIELD, 1.5d);

    private final BenchHarness game = new BenchHarness();
    private final Map<Integer, Integer> exp = new HashMap<>();
    private Persona mira;

    @BeforeEach
    void setUp() {
        mira = game.companion(ANN, "mira", "Mira", 950, Assignment.TRAIN);
        Character bot = game.bots.get(950);
        exp.put(950, 0);
        when(bot.getExp()).thenAnswer(inv -> exp.get(950));
        // the ledger's priors: Mira (Lv10) trains behind Ann (Lv30)
        when(game.ledger.paceTarget(any(), any(), anyInt())).thenReturn(27);
        when(game.ledger.catchUp(any(), anyInt(), anyInt())).thenReturn(1.0d);
        when(game.ledger.expPrior(anyInt())).thenReturn(PRIOR);
        when(game.ledger.mesoPrior(anyInt())).thenReturn(PRIOR);
        game.duty.yourCallCamps = (level, near) -> List.of(new BenchDutyChooser.Camp(FIELD, 0),
                new BenchDutyChooser.Camp(OTHER_FIELD, 0));
        when(game.roster.applyYourCall(anyInt(), anyString(), any(), anyInt())).thenAnswer(inv -> {
            RosterEntry row = game.row(inv.getArgument(0), inv.getArgument(1));
            RosterEntry now = new RosterEntry(row.characterId(), row.personaId(), row.state(), inv.getArgument(2), 0, 0,
                    inv.getArgument(3), row.accruedExp(), row.haulMeso(), row.wagesPaidUntilMs(), row.recruitedAtMs(),
                    row.benchedAtMs(), row.lastLedgerAtMs(), row.farmSecondsToday(), row.farmDay());
            game.store.seed(now);
            return now;
        });
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void itIsKeptAndReadBackAtBoot() {
        assertTrue(game.duty.setYourCall(ANN, "mira", true));
        assertEquals(Set.of(ANN + ":mira"), game.store.yourCall);

        BenchDutyDispatcher rebooted = new BenchDutyDispatcher(game.world, game.field, () -> game.now);
        rebooted.loadYourCalls();

        assertTrue(rebooted.yourCall(ANN, "mira"));
        assertTrue(game.duty.setYourCall(ANN, "mira", false));
        assertEquals(Set.of(), game.store.yourCall);
    }

    @Test
    void beforeEachSliceItPicksAgainAndSettlesOnTheBetterCamp() {
        game.duty.setYourCall(ANN, "mira", true);
        Set<Integer> tried = new HashSet<>();
        int betterLate = 0;
        int rounds = 30;
        game.duty.tick(); // its first slice, at the camp it picks now
        for (int round = 0; round < rounds; round++) {
            assertTrue(game.duty.live("mira"), "round " + round);
            int camp = game.duty.slice("mira").mapId();
            tried.add(camp);
            if (round >= rounds - 10 && camp == OTHER_FIELD) {
                betterLate++;
            }
            exp.merge(950, (int) Math.round(PRIOR * TRUTH.get(camp) * game.config.BENCH_LIVE_SLICE_MS / 3_600_000.0d), Integer::sum);
            game.now += game.config.BENCH_LIVE_SLICE_MS + 1L;
            game.duty.tick(); // the slice ends and records its hour, then Mira picks again for the next one
        }

        assertEquals(Set.of(FIELD, OTHER_FIELD), tried, "each camp got its try");
        assertTrue(betterLate >= 8, "the better camp in " + betterLate + " of the last 10 slices");
        assertEquals(OTHER_FIELD, game.row(ANN, "mira").campMapId(), "its own pick, set as its camp");
        assertFalse(game.store.events("bench.your_call").isEmpty());
        assertTrue(game.duty.yourCall(ANN, "mira"), "still its call");
    }

    @Test
    void aRowNotOnYourCallKeepsTheDutyAndCampItsOwnerGaveIt() {
        game.duty.tick();
        game.now += game.config.BENCH_LIVE_SLICE_MS + 1L;
        game.duty.tick();

        assertEquals(FIELD, game.row(ANN, "mira").campMapId());
        assertTrue(game.store.events("bench.your_call").isEmpty());
        org.mockito.Mockito.verify(game.roster, org.mockito.Mockito.never()).applyYourCall(anyInt(), anyString(), any(), anyInt());
    }
}
