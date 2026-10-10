package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.config.Content.Persona;
import server.living.population.Population;
import server.living.population.ShippedFields;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.population.ShippedFields.FOREST_BEFORE_THE_GATE;
import static server.living.population.ShippedFields.PAST_THE_CASTLE_GATES;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;

/**
 * A bench companion stops camping past the Mushroom Castle quest gates (user decision 2026-10-10, FieldCatalogQuestGateTest):
 * a camp stored there from before is no camp any more. Its field slices load at the default camp's field instead (the
 * row keeps its camp, as a town camp does), and on Your call it picks a camp again among fields that are none of the nine.
 */
class BenchDutyCastleCampTest {
    private static final int SKYSCRAPER_6 = 106021300;
    private static final int LEVEL = 33;

    private final BenchHarness game = new BenchHarness();

    @BeforeEach
    void setUp() {
        Population population = ShippedFields.population();
        game.statics(Population.class).when(Population::get).thenReturn(population);
        game.store.levels.put(ANN, 50); // the owner is ahead, so its Lv33 companion trains on the bench
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aStoredCampPastTheGatesLoadsAtTheDefaultCampsFieldInstead() {
        Persona olwen = companionAt(SKYSCRAPER_6, 901);

        game.duty.tick();

        assertTrue(game.calls.contains("load Olwen at " + FIELD + " ch1"), game.calls.toString());
        assertEquals(FIELD, BenchDutyDispatcher.fieldCamp(game.world, game.row(ANN, "olwen"), olwen));
        verify(game.roster, atLeastOnce()).fieldCampFor(olwen, SKYSCRAPER_6);
        assertEquals(SKYSCRAPER_6, game.row(ANN, "olwen").campMapId(), "the row keeps its camp, as a town camp does");
    }

    @Test
    void aCampInTheForestBeforeTheGateStaysItsCamp() {
        int forest = FOREST_BEFORE_THE_GATE.get(2);
        Persona olwen = companionAt(forest, 901);

        assertEquals(forest, BenchDutyDispatcher.fieldCamp(game.world, game.row(ANN, "olwen"), olwen));
        assertEquals(forest, BenchDutyChooser.camps(LEVEL, forest).get(0).mapId(), "its own camp is still a candidate");
    }

    @Test
    void yourCallsCandidatesAreNoneOfTheNine() {
        for (int level = 30; level <= 36; level++) {
            List<BenchDutyChooser.Camp> camps = BenchDutyChooser.camps(level, SKYSCRAPER_6);
            assertFalse(camps.isEmpty(), "level " + level);
            assertTrue(camps.stream().noneMatch(camp -> PAST_THE_CASTLE_GATES.contains(camp.mapId())),
                    "level " + level + ": " + camps);
        }
    }

    @Test
    void onYourCallAStoredCampPastTheGatesIsReplaced() {
        companionAt(SKYSCRAPER_6, 950);
        when(game.ledger.paceTarget(any(), any(), anyInt())).thenReturn(45);
        when(game.ledger.catchUp(any(), anyInt(), anyInt())).thenReturn(1.0d);
        when(game.ledger.expPrior(anyInt())).thenReturn(1_000.0d);
        when(game.ledger.mesoPrior(anyInt())).thenReturn(1_000.0d);
        when(game.roster.applyYourCall(anyInt(), anyString(), any(), anyInt())).thenAnswer(inv -> {
            RosterEntry row = game.row(inv.getArgument(0), inv.getArgument(1));
            RosterEntry now = new RosterEntry(row.characterId(), row.personaId(), row.state(), inv.getArgument(2), 0, 0,
                    inv.getArgument(3), row.accruedExp(), row.haulMeso(), row.wagesPaidUntilMs(), row.recruitedAtMs(),
                    row.benchedAtMs(), row.lastLedgerAtMs(), row.farmSecondsToday(), row.farmDay());
            game.store.seed(now);
            return now;
        });
        game.duty.setYourCall(ANN, "olwen", true);

        game.duty.tick();

        ArgumentCaptor<Integer> camp = ArgumentCaptor.forClass(Integer.class);
        verify(game.roster).applyYourCall(eq(ANN), eq("olwen"), any(), camp.capture());
        assertFalse(PAST_THE_CASTLE_GATES.contains(camp.getValue()), "picked " + camp.getValue());
        assertEquals(camp.getValue(), game.row(ANN, "olwen").campMapId(), "its own pick, set as its camp");
        assertTrue(game.duty.live("olwen"));
        assertFalse(PAST_THE_CASTLE_GATES.contains(game.duty.slice("olwen").mapId()), "loaded at " + game.duty.slice("olwen").mapId());
    }

    private Persona companionAt(int campMapId, int characterId) {
        Persona olwen = game.companion(ANN, "olwen", "Olwen", characterId, Assignment.TRAIN, campMapId);
        game.store.levels.put(characterId, LEVEL);
        game.botLevels.put(characterId, LEVEL);
        return olwen;
    }
}
