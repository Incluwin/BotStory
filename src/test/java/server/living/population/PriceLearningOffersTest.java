package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.population.needs.Need;
import server.living.population.needs.NeedKind;
import server.living.population.needs.NeedOffers;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Learned board offers (docs/WORLD_SYSTEMS_PLAN.md I2.2): a SUPPLY request offers the premium of an OFFER arm (1.00 to
 * 1.30 over the shop price), keeps it while it is open, and is rewarded when it ends by the share helpers filled and
 * how fast, less what the premium cost; off, the configured premium and nothing booked.
 */
class PriceLearningOffersTest {
    private static final int CITIZEN = 7001;
    private static final int POTION = 2000002;
    private static final long HOUR = 3_600_000L;

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PopulationConfig config = new PopulationConfig();

    PriceLearningOffersTest() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(store.prices()).thenReturn(List.of());
    }

    @AfterEach
    void uninstall() {
        NeedOffers.install(null);
    }

    /** A SUPPLY request of {@code citizenId} posted now (after its arm was picked), ended {@code hours} later. */
    private static Need ended(int id, int citizenId, Need.State state, int quantity, int delivered, long hours) {
        long posted = System.currentTimeMillis();
        return new Need(id, citizenId, NeedKind.SUPPLY, POTION, quantity, delivered, 100L, "balrog", 100000000, state,
                0, 0L, 0, false, posted, posted + 24 * HOUR, posted + hours * HOUR);
    }

    private static Need open(Need need) {
        return new Need(need.id(), need.citizenId(), need.kind(), need.itemId(), need.quantity(), 0, need.offer(),
                need.detail(), need.mapId(), Need.State.OPEN, 0, 0L, 0, true, need.createdAtMs(), need.deadlineMs(), 0L);
    }

    @Test
    void offTheConfiguredPremiumHoldsAndNothingIsBooked() {
        PriceLearning prices = new PriceLearning(population);
        assertEquals(1.15d, prices.offerPremium(CITIZEN, POTION, 1.15d), 0.0d);
        prices.offerClosed(ended(1, CITIZEN, Need.State.MET, 10, 10, 1), 0L, true);
        assertTrue(prices.offers().rows().isEmpty());
    }

    /** A crafter's buy order (N8.3) has a lever of its own, by item class; a player's order teaches it nothing. */
    @Test
    void aBuyOrdersEndRewardsTheBuyLeverAlone() {
        PriceLearning prices = new PriceLearning(population);
        assertEquals(0.9d, prices.buyShare(CITIZEN, 4010000, 0.9d), 0.0d, "off: the fixed share");
        config.PRICE_LEARNING_ENABLED = true;
        double share = prices.buyShare(CITIZEN, 4010000, 0.9d);
        assertTrue(share >= 0.8d - 1e-9 && share <= 1.1d + 1e-9, "a BUY arm: " + share);
        long posted = System.currentTimeMillis();
        Need met = new Need(9, CITIZEN, NeedKind.BUY, 4010000, 50, 50, 90L, "r4011000", 100000000, Need.State.MET, 0, 0L, 0,
                true, posted, posted + 24 * HOUR, posted + HOUR);
        prices.offerClosed(new Need(8, 0, NeedKind.BUY, 4010000, 50, 50, 90L, "", 100000000, Need.State.MET, 0, 0L, 0, true,
                posted, posted + 24 * HOUR, posted + HOUR, 10), 0L, true);
        assertTrue(prices.buys().rows().isEmpty(), "no arm was picked for a player's order");
        prices.offerClosed(met, 0L, true);
        prices.offerClosed(met, 0L, true);
        assertEquals(1.0d, prices.buys().rows().get(0).n(), 0.0d, "once");
        assertTrue(prices.offers().rows().isEmpty());
    }

    @Test
    void anOpenRequestKeepsItsArmAndItsEndRewardsIt() {
        config.PRICE_LEARNING_ENABLED = true;
        PriceLearning prices = new PriceLearning(population);
        double premium = prices.offerPremium(CITIZEN, POTION, 1.15d);
        assertTrue(premium >= 1.0d && premium <= 1.3d, "an OFFER arm: " + premium);
        for (int i = 0; i < 20; i++) {
            assertEquals(premium, prices.offerPremium(CITIZEN, POTION, 1.15d), 0.0d, "derived again: the same offer");
        }
        Need met = ended(1, CITIZEN, Need.State.MET, 10, 10, 6);
        prices.offerClosed(met, 0L, true);
        PriceArms.Row row = prices.offers().rows().get(0);
        assertEquals(1.0d, row.n(), 0.0d);
        assertEquals(0.5d - (premium - 1.0d), row.mean(), 1e-9, "met in HALF_HOURS: half the speed reward, less the premium");

        prices.offerClosed(met, 0L, true); // its thanks: a second write of the same ended row
        double again = prices.offerPremium(CITIZEN, POTION, 1.15d); // a new request for the same potion
        prices.offerClosed(met, 0L, true); // the old row written once more (a retry) must not take the new one's arm
        assertEquals(1.0d, prices.offers().rows().stream().mapToDouble(PriceArms.Row::n).sum(), 0.0d,
                "an ended need counts exactly once");
        prices.offerClosed(ended(2, CITIZEN, Need.State.EXPIRED, 10, 0, 24), 0L, true);
        assertEquals(2.0d, prices.offers().rows().stream().mapToDouble(PriceArms.Row::n).sum(), 0.0d,
                "the new request (priced at " + again + ") books its own end");
    }

    @Test
    void aRequestCreatedBeforeItsArmWasPickedBooksNothing() {
        config.PRICE_LEARNING_ENABLED = true;
        PriceLearning prices = new PriceLearning(population);
        Need old = ended(5, CITIZEN, Need.State.MET, 10, 10, 1);
        Need older = new Need(5, CITIZEN, NeedKind.SUPPLY, POTION, 10, 10, 100L, "", 0, Need.State.MET, 0, 0L, 0, false,
                old.createdAtMs() - 2 * HOUR, 0L, old.createdAtMs()); // posted before a restart, priced by another rule
        prices.offerPremium(CITIZEN, POTION, 1.15d);
        prices.offerClosed(older, 0L, true);
        assertTrue(prices.offers().rows().isEmpty());
    }

    @Test
    void anEndWrittenInATransactionWaitsAndARollbackCountsNothing() {
        config.PRICE_LEARNING_ENABLED = true;
        PriceLearning prices = new PriceLearning(population);
        prices.offerPremium(CITIZEN, POTION, 1.15d);
        Need met = ended(7, CITIZEN, Need.State.MET, 10, 10, 2);
        prices.offerClosed(met, 0L, false);
        assertEquals(1, prices.pendingOffers());
        assertTrue(prices.offers().rows().isEmpty(), "nothing booked before the transaction is known to hold");

        prices.confirmPending(id -> open(met)); // the ledger still has it open: the hand-in rolled back
        assertEquals(0, prices.pendingOffers());
        assertTrue(prices.offers().rows().isEmpty(), "a rolled-back end never counts");

        prices.offerClosed(met, 0L, false); // tried again, and this time it holds
        prices.confirmPending(id -> null); // gone from the ledger's live requests
        assertEquals(1.0d, prices.offers().rows().get(0).n(), 0.0d);
        prices.offerClosed(met, 0L, false);
        prices.confirmPending(id -> null);
        assertEquals(1.0d, prices.offers().rows().get(0).n(), 0.0d, "and once only");
    }

    @Test
    void aMetRequestStillLiveOwingThanksIsAnEndThatHeld() {
        config.PRICE_LEARNING_ENABLED = true;
        PriceLearning prices = new PriceLearning(population);
        prices.offerPremium(CITIZEN, POTION, 1.15d);
        Need met = ended(8, CITIZEN, Need.State.MET, 10, 10, 2);
        prices.offerClosed(met, 0L, false);
        prices.confirmPending(id -> met);
        assertEquals(1.0d, prices.offers().rows().get(0).n(), 0.0d);
    }

    @Test
    void theRewardIsTheShareFilledTimesSpeedLessCost() {
        assertEquals(0.0d, PriceLearning.offerReward(0.0d, 1.0d, 1.1d, 6.0d, 1.0d), 0.0d, "nobody helped");
        assertEquals(1.0d - 0.1d, PriceLearning.offerReward(1.0d, 0.0d, 1.1d, 6.0d, 1.0d), 1e-12);
        assertEquals(0.5d * (0.5d - 0.2d), PriceLearning.offerReward(0.5d, 6.0d, 1.2d, 6.0d, 1.0d), 1e-12);
        assertEquals(1.0d, PriceLearning.fulfilled(ended(1, CITIZEN, Need.State.MET, 10, 0, 1)), 0.0d);
        assertEquals(0.3d, PriceLearning.fulfilled(ended(1, CITIZEN, Need.State.EXPIRED, 10, 3, 24)), 1e-12);
        assertEquals(0.0d, PriceLearning.fulfilled(ended(1, CITIZEN, Need.State.BOUGHT, 10, 0, 2)), 0.0d,
                "it bought its own: the board did not fill it");
    }

    /**
     * Helpers whose willingness rises with the premium: a request at premium p is filled with probability
     * min(1, 4 x (p - 1)), within two hours. Expected reward fill(p) x (1 / (1 + 2/6) - (p - 1)) is best at 1.25.
     */
    @Test
    void offersConvergeOnTheBestTradeOffForASyntheticHelperPool() {
        config.PRICE_LEARNING_ENABLED = true;
        PriceLearning prices = new PriceLearning(population);
        Random helpers = new Random(3L);
        for (int round = 0; round < 3_000; round++) {
            int citizen = 10_000 + round;
            double premium = prices.offerPremium(citizen, POTION, 1.15d);
            boolean filled = helpers.nextDouble() < Math.min(1.0d, 4.0d * (premium - 1.0d));
            prices.offerClosed(ended(round + 1, citizen, filled ? Need.State.MET : Need.State.EXPIRED, 10,
                    filled ? 10 : 0, filled ? 2 : 24), 0L, true);
        }
        PriceArms offers = prices.offers();
        assertEquals(1.25d, offers.multiplier(offers.best(PriceLearning.SUPPLY_KEY, config.PRICE_PRIOR_SAMPLES)), 1e-9);
    }

    @Test
    void theMarketInstallsItBehindTheBoard() {
        config.PRICE_LEARNING_ENABLED = true;
        Market market = new Market(population, itemId -> 0L);
        NeedOffers.Learner learner = captured();
        assertTrue(learner != null);
        market.prices().offerPremium(CITIZEN, POTION, 1.15d);
        assertEquals(market.prices().offerPremium(CITIZEN, POTION, 1.15d), learner.premium(CITIZEN, POTION, 1.15d), 0.0d,
                "the board asks the market's own learner");
    }

    private static NeedOffers.Learner captured() {
        try {
            var field = NeedOffers.class.getDeclaredField("learner");
            field.setAccessible(true);
            return (NeedOffers.Learner) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
