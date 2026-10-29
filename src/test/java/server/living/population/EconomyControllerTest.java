package server.living.population;

import client.Character;
import config.EconomyConfig;
import org.junit.jupiter.api.Test;
import server.economy.ledger.EconomyLedger.Close;
import server.living.population.EconomyController.Levers;
import server.maps.MapItem;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The E4 controller (docs/ECONOMY_PLAN.md E4): from a day's close it moves pickup selectivity toward the share that
 * would have held organic growth to the target, raises luxury spending only when pickup cannot do it alone and eases it
 * off first, moves buy budgets against the basket index, all by bounded steps; and a citizen's pickup answer is fixed
 * per drop.
 */
class EconomyControllerTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);
    private static final double EPS = 1e-9;
    private final EconomyConfig config = new EconomyConfig();

    private static Close close(long supply, long organicNet, long citizenNpcSale, Double cpi, Double previousCpi) {
        return new Close(DAY, supply, organicNet, citizenNpcSale, cpi, previousCpi);
    }

    @Test
    void farTooMuchGrowthLowersPickupByAStepAndStartsLuxurySpending() {
        // today's numbers: 5.8b supply, ~1b organic net (17 %), 0.85b of it citizens vendoring
        Levers next = EconomyController.decide(close(5_800_000_000L, 1_000_000_000L, 850_000_000L, null, null),
                Levers.NEUTRAL, config).levers();
        assertEquals(0.85d, next.pickupShare(), EPS, "one step, though the share it wants is under nothing");
        assertEquals(0.1d, next.luxuryRate(), EPS, "pickup alone cannot hold it");
        assertEquals(1.0d, next.budgetScale(), EPS, "no basket index: budgets stay");
    }

    @Test
    void aSmallExcessPickupCanCoverLeavesLuxuryAlone() {
        // 2 % on 10b, 1.5 of the 2 points vendoring: the share that would have held 1 % is a third, over the least
        EconomyController.Decision decision = EconomyController.decide(
                close(10_000_000_000L, 200_000_000L, 150_000_000L, null, null), Levers.NEUTRAL, config);
        assertEquals(0.02d, decision.growth(), EPS);
        assertEquals(0.85d, decision.levers().pickupShare(), EPS);
        assertEquals(0.0d, decision.levers().luxuryRate(), EPS);
    }

    @Test
    void underTargetLuxuryEasesOffBeforePickupRises() {
        Levers spending = new Levers(0.3d, 0.2d, 1.0d);
        Levers eased = EconomyController.decide(close(10_000_000_000L, 50_000_000L, 40_000_000L, null, null),
                spending, config).levers();
        assertEquals(0.3d, eased.pickupShare(), EPS);
        assertEquals(0.1d, eased.luxuryRate(), EPS);

        Levers picking = EconomyController.decide(close(10_000_000_000L, 50_000_000L, 40_000_000L, null, null),
                new Levers(0.5d, 0.0d, 1.0d), config).levers();
        assertEquals(0.65d, picking.pickupShare(), EPS, "toward 0.5 x (100m - 10m) / 40m, a step at most");
        assertEquals(1.0d, EconomyController.decide(close(10_000_000_000L, 0L, 0L, null, null),
                new Levers(0.95d, 0.0d, 1.0d), config).levers().pickupShare(), EPS, "never over everything");
    }

    @Test
    void pickupNeverGoesUnderItsLeastAndLuxuryStopsAtItsMost() {
        Levers next = EconomyController.decide(close(5_800_000_000L, 1_000_000_000L, 850_000_000L, null, null),
                new Levers(0.35d, 0.45d, 1.0d), config).levers();
        assertEquals(0.3d, next.pickupShare(), EPS);
        assertEquals(0.5d, next.luxuryRate(), EPS);
    }

    @Test
    void buyBudgetsMoveAgainstTheBasketIndexOutsideItsBand() {
        assertEquals(0.9d, budget(110.0d, 100.0d, 1.0d), EPS, "prices up 10 %: budgets down 10 %");
        assertEquals(0.75d, budget(150.0d, 100.0d, 1.0d), EPS, "a step at most");
        assertEquals(1.0d, budget(101.0d, 100.0d, 1.0d), EPS, "inside the band");
        assertEquals(1.2d, budget(80.0d, 100.0d, 1.0d), EPS, "prices down 20 %: budgets up, a step at most");
        assertEquals(2.0d, budget(50.0d, 100.0d, 1.9d), EPS, "never over the most");
        assertEquals(0.5d, budget(200.0d, 100.0d, 0.6d), EPS, "never under the least");
        assertNull(EconomyController.decide(close(1L, 0L, 0L, 100.0d, null), Levers.NEUTRAL, config).cpiChange());
    }

    private double budget(double cpi, double previousCpi, double scale) {
        return EconomyController.decide(close(10_000_000_000L, 100_000_000L, 0L, cpi, previousCpi),
                new Levers(1.0d, 0.0d, scale), config).levers().budgetScale();
    }

    @Test
    void aCitizenLeavesTheShareOfEquipDropsTheLeverLeavesTheSameEachAsk() {
        Population population = mock(Population.class);
        when(population.market()).thenReturn(mock(Market.class));
        when(population.citizen(1)).thenReturn(citizen(1, Set.of()));
        when(population.citizen(2)).thenReturn(citizen(2, Set.of(Citizen.FLAG_HIRED)));
        EconomyController controller = new EconomyController(population, config);
        Character walker = character(1);
        Character hired = character(2);
        Character player = character(3);

        assertFalse(controller.leaves(walker, drop(7)), "neutral: it picks up everything");
        controller.apply(new Levers(0.3d, 0.0d, 1.0d));
        int left = 0;
        for (int objectId = 0; objectId < 2_000; objectId++) {
            boolean leaves = controller.leaves(walker, drop(objectId));
            assertEquals(leaves, controller.leaves(walker, drop(objectId)), "the same drop, the same answer");
            left += leaves ? 1 : 0;
            assertFalse(controller.leaves(hired, drop(objectId)), "a player's hired hand loots for its player");
            assertFalse(controller.leaves(player, drop(objectId)), "no citizen");
        }
        assertTrue(left > 1_300 && left < 1_500, "about 70 % of 2,000 left: " + left);
    }

    @Test
    void atStartItSetsFromARecentCloseItNeverHeard() {
        LocalDate today = LocalDate.of(2026, 10, 30);
        assertTrue(EconomyController.unheard(DAY, null, today), "no setting yet");
        assertTrue(EconomyController.unheard(DAY, DAY.minusDays(1), today), "stopped between the close and its setting");
        assertTrue(EconomyController.unheard(DAY.minusDays(1), null, today), "the day before yesterday is still recent");
        assertFalse(EconomyController.unheard(DAY, DAY, today), "heard");
        assertFalse(EconomyController.unheard(DAY.minusDays(2), null, today), "too old to set tomorrow from");
        assertFalse(EconomyController.unheard(null, null, today), "no close at all");
    }

    @Test
    void unitIsSpreadEvenlyOverZeroToOne() {
        double sum = 0.0d;
        for (int objectId = 0; objectId < 10_000; objectId++) {
            double unit = EconomyController.unit(objectId, 42);
            assertTrue(unit >= 0.0d && unit < 1.0d);
            sum += unit;
        }
        assertEquals(0.5d, sum / 10_000, 0.02d);
    }

    private static Character character(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        return character;
    }

    private static MapItem drop(int objectId) {
        MapItem drop = mock(MapItem.class);
        when(drop.getObjectId()).thenReturn(objectId);
        return drop;
    }

    private static Citizen citizen(int characterId, Set<String> flags) {
        return new Citizen(characterId, "Mira", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
