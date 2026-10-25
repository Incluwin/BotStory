package server.living.population;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.PopulationContent.Region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Births and retirements (plan 3.5). An empty registry is seeded toward the target in batches,
 * backdated so levels look like a mature server; afterwards a few citizens are born a day and
 * citizens past their retirement date stop logging in (with a goodbye if anyone can hear it). Operators can also queue
 * seeds homed in one region ({@link #requestSeeds}).
 */
public final class Churn {
    private static final Logger log = LoggerFactory.getLogger(Churn.class);
    public static final int SEED_BATCH = 12;
    /** Fast-seed until the registry is within 1/10 of target; below that, births trickle in at BIRTHS_PER_DAY. */
    private static final int FAST_SEED_SHORTFALL_DIVISOR = 10;
    /** Most seeds one operator request may queue. */
    public static final int SEED_REQUEST_MAX = 200;

    private final Population population;
    private final Random random;
    /** Citizens hired since the last sweep: their towns get a newcomer at once, not at BIRTHS_PER_DAY (P4). */
    private final AtomicInteger hiresToReplace = new AtomicInteger();
    /** Operator-requested seeds, one region id per citizen in request order; in memory only, so a restart drops them. */
    private final Queue<String> seedQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger seedsSkipped = new AtomicInteger();
    private long lastBirthMs;
    private long lastRetirementCheckMs;
    private volatile boolean seeding;

    Churn(Population population) {
        this.population = population;
        this.random = new Random(population.config().SEED ^ 0xc0ffeeL);
    }

    public boolean seeding() {
        return seeding;
    }

    /**
     * Called every presence sweep: first births up to SEED_BATCH queued seeds, then seeds fast with what is left of that
     * batch while the registry is well below target (a raised TARGET_CITIZENS or a restart mid-seed), and lets
     * BIRTHS_PER_DAY handle the last tenth.
     */
    void step(long now) {
        int queued = seedQueued();
        int active = population.activeCount();
        int target = population.config().TARGET_CITIZENS;
        if (active >= target) {
            seeding = false;
            hiresToReplace.set(0);
            return;
        }
        if (active < target - target / FAST_SEED_SHORTFALL_DIVISOR || seeding) {
            seeding = true;
            int batch = Math.min(SEED_BATCH - queued, target - active);
            for (int i = 0; i < batch; i++) {
                // squared so most seeded citizens are newcomers and only a few are veterans
                double skew = random.nextDouble();
                int backdate = (int) Math.round(skew * skew * population.config().SEED_BACKDATE_DAYS);
                if (population.generator().birth(null, backdate) == null) {
                    break;
                }
            }
            lastBirthMs = now;
            return;
        }
        int owed = Math.min(hiresToReplace.getAndSet(0), target - active);
        if (owed > 0) {
            for (int i = 0; i < owed; i++) {
                if (population.generator().birth(null, 0) == null) {
                    break;
                }
            }
            lastBirthMs = now;
            return;
        }
        long spacing = 86_400_000L / Math.max(1, population.config().BIRTHS_PER_DAY);
        if (now - lastBirthMs >= spacing) {
            population.generator().birth(null, 0);
            lastBirthMs = now;
        }
    }

    /**
     * Queues {@code count} citizens homed in {@code regionId} at levels inside its band. TARGET_CITIZENS does not limit
     * them (each sweep births up to SEED_BATCH even at or above target) but they count toward it: below target they take
     * the place of fast-seed births, and while the registry is at or above target ordinary births pause. Rejects an
     * unknown region, a count outside 1..SEED_REQUEST_MAX and a band no career lands in.
     */
    public Region requestSeeds(String regionId, int count) {
        Region region = population.content().region(regionId);
        if (region == null) {
            throw new IllegalArgumentException("unknown region " + regionId + "; regions: "
                    + String.join(", ", population.content().regions().keySet()));
        }
        if (count < 1 || count > SEED_REQUEST_MAX) {
            throw new IllegalArgumentException("count must be 1.." + SEED_REQUEST_MAX);
        }
        if (!reachable(region)) {
            throw new IllegalArgumentException("no career lands in " + region.id() + "'s lv" + region.levelMin() + "-"
                    + region.levelMax() + " within SEED_BACKDATE_DAYS " + population.config().SEED_BACKDATE_DAYS);
        }
        for (int i = 0; i < count; i++) {
            seedQueue.add(region.id());
        }
        return region;
    }

    /** True when some career lands in the region's band within SEED_BACKDATE_DAYS, so its seeds can be born. */
    public boolean reachable(Region region) {
        boolean lines = CitizenLines.enabled(population);
        return CitizenGenerator.reachable(region, CitizenLines.drawable(population.content().careers().values(), lines),
                career -> CitizenLines.band(region, career, lines), population.config().SEED_BACKDATE_DAYS,
                population.config().LIFE_PACE);
    }

    /** "leafre lv70-120 (seeds reach lv86)": the band, and how much of it the loaded careers can fill. */
    public String describe(Region region) {
        String band = region.id() + " lv" + region.levelMin() + "-" + region.levelMax();
        if (!reachable(region)) {
            return band + " (out of reach)";
        }
        int ceiling = CitizenGenerator.seedCeiling(CitizenLines.drawable(population.content().careers().values(),
                        CitizenLines.enabled(population)), population.config().SEED_BACKDATE_DAYS,
                population.config().LIFE_PACE);
        return ceiling < region.levelMax() ? band + " (seeds reach lv" + ceiling + ")" : band;
    }

    /** Pending targeted seeds per region, in queue order. */
    public Map<String, Integer> seedQueue() {
        Map<String, Integer> pending = new LinkedHashMap<>();
        for (String regionId : seedQueue) {
            pending.merge(regionId, 1, Integer::sum);
        }
        return pending;
    }

    /** Queued seeds dropped since boot: the band went out of reach (a reload) or creation failed. */
    public int seedsSkipped() {
        return seedsSkipped.get();
    }

    /** Births up to SEED_BATCH queued seeds; returns how many it took off the queue. */
    private int seedQueued() {
        int born = 0;
        int skipped = 0;
        String regionId;
        while (born + skipped < SEED_BATCH && (regionId = seedQueue.poll()) != null) {
            if (population.generator().seed(regionId) != null) {
                born++;
            } else {
                skipped++;
            }
        }
        if (born + skipped > 0) {
            seedsSkipped.addAndGet(skipped);
            log.info("seeded {} queued citizens ({} skipped), {} still queued", born, skipped, seedQueue.size());
        }
        return born + skipped;
    }

    /** A citizen went to work for a player: the next sweep replaces it in the population at once. */
    void hired() {
        hiresToReplace.incrementAndGet();
    }

    /** Hourly: retirements and weekly life summaries. */
    public void tick() {
        long now = System.currentTimeMillis();
        try {
            if (now - lastRetirementCheckMs < 3_000_000L) {
                return;
            }
            lastRetirementCheckMs = now;
            List<Citizen> retiring = new ArrayList<>();
            for (Citizen citizen : population.citizens()) {
                // a hired hand keeps its job past its date: it retires once it is back in the population
                if (citizen.state() == Citizen.State.ACTIVE && !citizen.hired() && citizen.retiresAtMs() > 0
                        && citizen.retiresAtMs() < now) {
                    retiring.add(citizen);
                }
            }
            for (Citizen citizen : retiring) {
                retire(citizen, "retired after " + citizen.ageDays(now) + " days");
            }
        } catch (RuntimeException failure) {
            log.warn("churn tick failed", failure);
        }
    }

    /**
     * The citizen stops logging in for good. Never a hired hand (P4): it works for a friend, and it retires once it is
     * back in the population. False when it was not retired (hired, or not a citizen).
     */
    public boolean retire(Citizen citizen, String reason) {
        Citizen current = population.citizen(citizen.characterId());
        if (current == null || current.hired()) {
            return false;
        }
        CitizenSession session = population.presence().session(citizen.characterId());
        if (session != null && !session.virtual) {
            population.chatter().event(ChatterEngine.Event.of("logout", session.mapId, session.channel).self(session)
                    .withText("quitting maple, was fun everyone"));
            population.presence().logout(session, reason);
        } else if (session != null) {
            population.presence().logout(session, reason);
        }
        population.social().onRetire(current);
        population.update(citizen.characterId(), latest -> latest.withState(Citizen.State.RETIRED));
        log.info("citizen {} retired: {}", citizen.name(), reason);
        return true;
    }
}
