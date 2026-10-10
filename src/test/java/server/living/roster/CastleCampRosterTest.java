package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.config.Content.Persona;
import server.living.population.Population;
import server.living.population.ShippedFields;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.living.population.ShippedFields.FOREST_BEFORE_THE_GATE;
import static server.living.population.ShippedFields.PAST_THE_CASTLE_GATES;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * The roster side of no camps past the Mushroom Castle quest gates (user decision 2026-10-10, BenchDutyCastleCampTest):
 * a companion whose row still camps there (a row from before, kept by design) is called from the field its bench slices
 * load at instead, never loaded on the castle map; and its owner can no longer set a camp there, by 'camp here' or by
 * 'go <duty> here'.
 */
class CastleCampRosterTest {
    private static final int SKYSCRAPER_6 = 106021300;
    private static final int OLWEN = 900;

    private final LifecycleHarness game = new LifecycleHarness();
    private MockedStatic<Population> populations;

    @BeforeEach
    void setUp() {
        Population population = ShippedFields.population();
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        game.store.levels.put(OLWEN, 33);
    }

    @AfterEach
    void close() {
        populations.close();
        game.close();
    }

    @Test
    void aCallFromACampPastTheGatesSetsOffFromTheFieldItsSlicesLoadAt() {
        Persona olwen = game.companion("olwen", "Olwen", OLWEN, RosterState.BENCHED, SKYSCRAPER_6);
        int field = game.roster().fieldCampFor(olwen, SKYSCRAPER_6);
        assertNotEquals(0, field);
        assertFalse(PAST_THE_CASTLE_GATES.contains(field), "fell back to " + field);

        RosterResult result = game.roster().call(game.ann, olwen);

        RosterResult.Travelling trip = assertInstanceOf(RosterResult.Travelling.class, result);
        assertEquals(field, trip.fromMapId());
        assertTrue(game.calls.get(0).startsWith("spawn Olwen at " + field + " "), game.calls.toString());
        assertTrue(game.calls.stream().noneMatch(call -> call.contains(" " + SKYSCRAPER_6 + " ")), game.calls.toString());
        assertEquals(SKYSCRAPER_6, game.row(olwen).campMapId(), "the row keeps its camp, as the bench does");
    }

    @Test
    void aSwapOrCallSetsOffFromTheOpenFieldNotTheCastle() {
        Persona olwen = game.companion("olwen", "Olwen", OLWEN, RosterState.BENCHED, SKYSCRAPER_6);

        int from = game.roster().departure(game.row(olwen), olwen);

        assertEquals(game.roster().fieldCampFor(olwen, SKYSCRAPER_6), from);
    }

    @Test
    void aCampInTheForestBeforeTheGateIsStillWhereItSetsOffFrom() {
        int forest = FOREST_BEFORE_THE_GATE.get(2);
        Persona olwen = game.companion("olwen", "Olwen", OLWEN, RosterState.BENCHED, forest);

        assertEquals(forest, game.roster().departure(game.row(olwen), olwen));
    }

    @Test
    void campHerePastTheGatesIsRefusedAndTheCampStays() {
        Persona olwen = game.companion("olwen", "Olwen", OLWEN, RosterState.BENCHED, LifecycleHarness.HENESYS_FIELD);

        RosterResult result = game.roster().setCamp(game.ann, olwen, SKYSCRAPER_6);

        assertEquals(new RosterResult.Failed("Olwen: not here. i'd never get back in past the gate on my own."), result);
        assertEquals(LifecycleHarness.HENESYS_FIELD, game.row(olwen).campMapId());
        assertTrue(game.store.events(RosterService.CAMP_EVENT).isEmpty());
        assertTrue(game.roster().setCamp(game.ann, olwen, FOREST_BEFORE_THE_GATE.get(0)).ok(), "the forest before the gate");
        assertEquals(FOREST_BEFORE_THE_GATE.get(0), game.row(olwen).campMapId());
    }

    @Test
    void goTrainHerePastTheGatesIsRefusedAndTheRowStays() {
        Persona olwen = game.companion("olwen", "Olwen", OLWEN, RosterState.BENCHED, LifecycleHarness.HENESYS_FIELD);
        MapleMap castle = game.map(SKYSCRAPER_6); // a field map, not a town
        when(game.residents.map(SKYSCRAPER_6)).thenReturn(castle);

        RosterResult result = game.roster().delegate(game.ann, olwen, Assignment.FARM_MESO, 0, 0, SKYSCRAPER_6);

        assertEquals(new RosterResult.Failed("Olwen: not here. i'd never get back in past the gate on my own."), result);
        assertEquals(LifecycleHarness.HENESYS_FIELD, game.row(olwen).campMapId());
        assertEquals(Assignment.TRAIN, game.row(olwen).assignment());
        int forest = FOREST_BEFORE_THE_GATE.get(0);
        MapleMap forestMap = game.map(forest);
        when(game.residents.map(forest)).thenReturn(forestMap);
        assertTrue(game.roster().delegate(game.ann, olwen, Assignment.FARM_MESO, 0, 0, forest).ok(), "the forest before the gate");
        assertEquals(forest, game.row(olwen).campMapId());
    }
}
