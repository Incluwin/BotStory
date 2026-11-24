package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.CitizenSession.Activity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Scroll errands (docs/ECONOMY_PLAN.md 10.14, population.SCROLL_ERRANDS_ENABLED, user 2026-11-24): a loaded citizen with
 * an open slot, no scroll of its own it would use and meso to spare goes to a stall that has a fitting one now, as a
 * gear errand does, and uses it at once (CitizenScrolling says what it wants, pays and does with it). An errand is only
 * opened for a scroll on sale, at most SCROLL_ERRANDS_MAX are out at once, and one ends when it bought, when nothing on
 * sale is for it any more, when it gave up on the stall or after SCROLL_ERRAND_HOURS; the citizen then waits
 * SCROLL_ERRAND_RETRY_HOURS for its next. Kept in memory: it holds no goods and no meso, so a restart only forgets it.
 */
final class ScrollErrands {
    private static final Logger log = LoggerFactory.getLogger(ScrollErrands.class);
    private static final long HOUR_MS = 3_600_000L;

    private final Population population;
    private final CitizenScrolling scrolling;
    private final IntUnaryOperator random;
    /** Citizen -> when its errand runs out. */
    private final Map<Integer, Long> open = new ConcurrentHashMap<>();
    /** Citizen -> when it may open its next errand. */
    private final Map<Integer, Long> resting = new ConcurrentHashMap<>();
    /** What the loaded citizens that could buy want, as of the last market tick: what a stall's scroll lines are ranked by. */
    private final Map<Integer, CitizenScrolling.Seeker> seekers = new ConcurrentHashMap<>();
    private final AtomicLong opened = new AtomicLong();
    private final AtomicLong bought = new AtomicLong();
    private final AtomicLong ended = new AtomicLong();

    /** {@code random}: a number in 0..bound-1, where in the sessions a tick starts to look. */
    ScrollErrands(Population population, CitizenScrolling scrolling, IntUnaryOperator random) {
        this.population = population;
        this.scrolling = scrolling;
        this.random = random;
    }

    boolean on() {
        PopulationConfig config = population.config();
        return config.MARKET_ENABLED && config.MARKET_SCROLLING && config.SCROLL_ERRANDS_ENABLED;
    }

    boolean any(int citizenId) {
        return open.containsKey(citizenId) && on();
    }

    /** The most it pays for one {@code itemId} on its errand: CitizenScrolling's offer; 0 with none open. */
    long offer(Character agent, int itemId) {
        return agent != null && any(agent.getId()) ? scrolling.offer(agent, itemId) : 0L;
    }

    /** How many of the other citizens that could buy want {@code itemId}. */
    int demand(int sellerId, int itemId) {
        if (!on()) {
            return 0;
        }
        return (int) seekers.values().stream().filter(seeker -> seeker.id() != sellerId
                && scrolling.wants(seeker, itemId)).count();
    }

    /** At a market tick: {@code onSale} reads the scrolls on sale by channel, item id -> the cheapest single's price. */
    void tick(Collection<CitizenSession> sessions, IntFunction<Map<Integer, Long>> onSale, long now) {
        if (!on()) {
            if (!open.isEmpty() || !seekers.isEmpty()) {
                open.clear();
                seekers.clear();
            }
            return;
        }
        Map<Integer, Map<Integer, Long>> sale = new HashMap<>();
        Map<Integer, CitizenScrolling.Seeker> found = new HashMap<>();
        List<CitizenSession> order = new ArrayList<>(sessions);
        if (!order.isEmpty()) {
            Collections.rotate(order, random.applyAsInt(order.size())); // not always the same citizens first
        }
        Set<Integer> seen = new HashSet<>();
        for (CitizenSession session : order) {
            Character agent = session.agent();
            if (session.virtual || agent == null) {
                continue;
            }
            seen.add(session.characterId);
            CitizenScrolling.Seeker seeker = scrolling.seeker(agent);
            if (seeker != null) {
                found.put(session.characterId, seeker);
            }
            Long until = open.get(session.characterId);
            boolean goods = seeker != null && (until != null || mayOpen(session, now))
                    && sale.computeIfAbsent(session.channel, onSale::apply).entrySet().stream()
                    .anyMatch(line -> line.getValue() <= scrolling.offer(agent, line.getKey()));
            if (until != null) {
                if (!goods || now >= until) {
                    end(session, now, seeker == null ? "no longer wants one" : goods ? "ran out of time" : "nothing for it on sale");
                }
            } else if (goods) {
                open.put(session.characterId, now + population.config().SCROLL_ERRAND_HOURS * HOUR_MS);
                opened.incrementAndGet();
                log.info("citizen {} goes shopping for a scroll ({} out)", session.name(), open.size());
            }
        }
        seekers.keySet().retainAll(found.keySet());
        seekers.putAll(found);
        open.keySet().retainAll(seen); // logged off, or no longer loaded
        resting.values().removeIf(at -> now >= at);
    }

    /** Never a merchant (it minds its stall), nor one in a run, on a job errand or at a game. */
    private boolean mayOpen(CitizenSession session, long now) {
        Activity activity = session.activity;
        return open.size() < population.config().SCROLL_ERRANDS_MAX && now >= resting.getOrDefault(session.characterId, 0L)
                && !session.citizen.has(Citizen.FLAG_MERCHANT) && !session.stallOpen
                && (activity == Activity.TRAIN || activity == Activity.TOWN || activity == Activity.MARKET
                || activity == Activity.AFK);
    }

    /** It bought a scroll it wanted: its errand, if it was on one, is done. */
    void bought(CitizenSession session, int itemId, long now) {
        if (itemId / 10_000 == 204 && open.containsKey(session.characterId)) {
            bought.incrementAndGet();
            end(session, now, "bought one");
        }
    }

    /** It gave up on the stall it walked to. */
    void gaveUp(CitizenSession session, long now) {
        if (open.containsKey(session.characterId)) {
            end(session, now, "could not reach the stall");
        }
    }

    private void end(CitizenSession session, long now, String why) {
        if (open.remove(session.characterId) == null) {
            return;
        }
        ended.incrementAndGet();
        resting.put(session.characterId, now + population.config().SCROLL_ERRAND_RETRY_HOURS * HOUR_MS);
        log.info("citizen {} ends its scroll errand: {}", session.name(), why);
        if (session.activity == Activity.MARKET) {
            session.lastPlanAtMs = 0L; // it came for that: it plans anew instead of standing about in the Free Market
        }
    }

    String json() {
        return "{\"on\":" + on() + ",\"open\":" + open.size() + ",\"max\":" + population.config().SCROLL_ERRANDS_MAX
                + ",\"seekers\":" + seekers.size() + ",\"opened\":" + opened.get() + ",\"bought\":" + bought.get()
                + ",\"ended\":" + ended.get() + "}";
    }
}
