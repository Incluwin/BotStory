package server.living.population;

import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The one-time correction to the scroll formula's new seeds (docs/ECONOMY_PLAN.md section 10.15, option b of 10.12, user
 * 2026-11-25). Until ScrollSeeds priced a scroll against its own kind, some seeds were up to 14 x too high, and the first
 * reseed and the drift guard held rows near them. Once, at start before any citizen loads, every row priced by the
 * formula that has sales and stands over PRICE_SEED_CORRECTION_OVER x its seed goes to its seed, never under what an NPC
 * pays. In the same pass, with their old prices kept too, the formula's rows without a sale take their seed as
 * {@link Market#reseedUnmoved} would a moment later. The table living_price_correction holds the old prices and the mark
 * that it ran; the first reseed's table and mark are its own. Only with the guard on, as the reseed. Samples stay.
 */
final class PriceSeedCorrection {
    private static final Logger log = LoggerFactory.getLogger(PriceSeedCorrection.class);

    /** The rows with sales it lowers, and the rows without one that take their seed at any start. */
    record Plan(List<PriceIndexReseed.Move> sold, List<PriceIndexReseed.Move> unsold) {
        List<PriceIndexReseed.Move> all() {
            List<PriceIndexReseed.Move> all = new ArrayList<>(sold);
            all.addAll(unsold);
            return all;
        }
    }

    private PriceSeedCorrection() {
    }

    /**
     * Before {@link Market#reseedUnmoved}: runs the correction if its switch and the drift guard are on and it never ran;
     * the number of rows it set. Not run, {@code market} keeps the unsold rows for the dry run, as the start is about to
     * set them.
     */
    static int runOnce(Market market, CitizenStore store, PopulationConfig config) {
        Plan plan = plan(market, config);
        market.unsoldAtStart = plan.unsold();
        if (!config.PRICE_SEED_CORRECTION_ENABLED) {
            return 0;
        }
        if (!config.PRICE_INDEX_DRIFT_GUARD_ENABLED) {
            log.warn("price seed correction refused: PRICE_INDEX_DRIFT_GUARD_ENABLED is off");
            return 0;
        }
        synchronized (PriceSeedCorrection.class) {
            if (store.priceCorrectionRan()) {
                return 0;
            }
            if (!store.correctPrices(plan.all())) {
                log.error("price seed correction failed; the rows with sales are unchanged");
                return 0;
            }
            plan.all().forEach(move -> market.rows().get(move.itemId())[0] = move.newPrice());
            market.unsoldAtStart = List.of();
        }
        log.info("price seed correction: {} scroll formula rows with sales over {} x their seed and {} without a sale set to"
                + " it; old prices in living_price_correction", plan.sold().size(), config.PRICE_SEED_CORRECTION_OVER,
                plan.unsold().size());
        return plan.all().size();
    }

    /** Reads only; the dearest rows first. */
    static Plan plan(Market market, PopulationConfig config) {
        List<PriceIndexReseed.Move> sold = new ArrayList<>();
        List<PriceIndexReseed.Move> unsold = new ArrayList<>();
        for (Map.Entry<Integer, long[]> entry : market.rows().entrySet()) {
            int itemId = entry.getKey();
            long[] row = entry.getValue();
            long seed = market.anchorPrice(itemId);
            long target = Math.max(market.npcUnit(itemId), seed);
            if (seed <= Market.MINIMUM_SEED || target == row[0]
                    || market.seedSource(itemId) != Market.SeedSource.SCROLL_FORMULA) {
                continue;
            }
            PriceIndexReseed.Move move = new PriceIndexReseed.Move(itemId, seed, row[0], target, (int) row[1],
                    Market.SeedSource.SCROLL_FORMULA, 1.0d);
            if (row[1] == 0L) {
                unsold.add(move);
            } else if (row[0] > seed * config.PRICE_SEED_CORRECTION_OVER && target < row[0]) {
                sold.add(move);
            }
        }
        Comparator<PriceIndexReseed.Move> dearest = Comparator.comparingLong(PriceIndexReseed.Move::oldPrice).reversed()
                .thenComparingInt(PriceIndexReseed.Move::itemId);
        sold.sort(dearest);
        unsold.sort(dearest);
        return new Plan(sold, unsold);
    }

    /**
     * The dry run for the admin port's /runtime/stats/price-correction; changes nothing. {@code unsold_at_start}: the rows
     * without a sale this start set to their seed with no backup (a start with the correction leaves none).
     */
    static String dryRunJson(Market market, CitizenStore store, PopulationConfig config) {
        Plan plan = plan(market, config);
        return "{\"ok\":true,\"switch_on\":" + config.PRICE_SEED_CORRECTION_ENABLED
                + ",\"guard_on\":" + config.PRICE_INDEX_DRIFT_GUARD_ENABLED
                + ",\"already_ran\":" + store.priceCorrectionRan() + ",\"over\":" + config.PRICE_SEED_CORRECTION_OVER
                + ",\"would_change\":" + plan.sold().size() + ",\"would_reset_unsold\":" + plan.unsold().size()
                + ",\"unsold_at_start_count\":" + market.unsoldAtStart.size()
                + ",\"moves\":" + PriceIndexReseed.listed(plan.sold())
                + ",\"unsold\":" + PriceIndexReseed.listed(plan.unsold())
                + ",\"unsold_at_start\":" + PriceIndexReseed.listed(market.unsoldAtStart) + "}";
    }
}
