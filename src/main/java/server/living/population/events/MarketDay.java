package server.living.population.events;

import client.Character;
import server.knowledge.ContentAnswers;
import server.living.population.CitizenSession;
import server.living.population.paper.PaperDesk;
import server.maps.PlayerShop;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Market day (docs/WORLD_SYSTEMS_PLAN.md N4.2): the stall cap goes up, stall asks come down and more citizens' plans go
 * to the Free Market, all through {@link WorldModifier}. A discount only moves meso between holders. The price index and
 * the learned asks hold still for the day and the hour after it (the longest a stall opened on it stays up), so a sale
 * price never becomes the price the paper and the economy controller read. The day's best bargains go to the paper.
 */
final class MarketDay implements WorldEvents.Runner {
    /** A merchant plans its next activity within 45 minutes. */
    static final long TAIL_MS = 3_600_000L;
    static final int BARGAINS = 3;

    private final WorldEvents events;
    private final WorldEvents.Run run;
    /** Item id -> the lowest share of its index price a citizen's stall asked for it. */
    private final Map<Integer, Double> cheapest = new HashMap<>();
    private int mostStalls;

    MarketDay(WorldEvents events, WorldEvents.Run run) {
        this.events = events;
        this.run = run;
    }

    @Override
    public void begin(long now, boolean resumed) {
        EventCatalog.Market market = events.catalog().market();
        events.set(WorldModifier.Effect.marketDay(run.source(), market.stalls(), market.ask(), market.weight(),
                run.endsAtMs));
        if (!resumed) {
            events.notice.accept("[World Event] It's Market Day! The Free Market is full of stalls and prices are down for"
                    + " the next " + run.entry.minutes() / 60 + " hours.");
        }
    }

    @Override
    public boolean step(long now) {
        int stalls = 0;
        for (CitizenSession session : events.population().presence().sessions()) {
            Character agent = session.stallOpen ? session.agent() : null;
            PlayerShop shop = agent == null ? null : agent.getPlayerShop();
            if (shop == null) {
                continue;
            }
            stalls++;
            for (PlayerShop.ListingView view : shop.listingSnapshot()) {
                ContentAnswers.PriceQuote quote = events.population().market().known(view.itemId());
                if (view.bundles() > 0 && view.perBundle() > 0 && quote != null && quote.price() > 0) {
                    cheapest.merge(view.itemId(), view.bundlePrice() / (double) view.perBundle() / quote.price(), Math::min);
                }
            }
        }
        mostStalls = Math.max(mostStalls, stalls);
        return false;
    }

    @Override
    public String end(long now) {
        List<Map.Entry<Integer, Double>> best = cheapest.entrySet().stream().filter(one -> one.getValue() < 1.0d)
                .sorted(Comparator.comparingDouble(Map.Entry::getValue)).limit(BARGAINS).toList();
        if (!best.isEmpty()) {
            PaperDesk.file("market.bargains", 150, Map.of(
                    "items", String.join(", ", best.stream().map(one -> events.itemName(one.getKey())).toList()),
                    "percent", String.valueOf(Math.round((1.0d - best.getFirst().getValue()) * 100.0d))));
        }
        return "at most " + mostStalls + " stalls";
    }

    @Override
    public void cleanUp() {
        events.clear(run.source());
        events.set(WorldModifier.Effect.holdPrices(run.source(), System.currentTimeMillis() + TAIL_MS));
    }
}
