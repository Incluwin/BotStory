package server.living.population;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Births and retirements (plan 3.5). An empty registry is seeded toward the target in batches,
 * backdated so levels look like a mature server; afterwards a few citizens are born a day and
 * citizens past their retirement date stop logging in (with a goodbye if anyone can hear it).
 */
public final class Churn {
    private static final Logger log = LoggerFactory.getLogger(Churn.class);
    private static final int SEED_BATCH = 12;
    /** Fast-seed until the registry is within 1/10 of target; below that, births trickle in at BIRTHS_PER_DAY. */
    private static final int FAST_SEED_SHORTFALL_DIVISOR = 10;

    private final Population population;
    private final Random random;
    /** Citizens hired since the last sweep: their towns get a newcomer at once, not at BIRTHS_PER_DAY (P4). */
    private final AtomicInteger hiresToReplace = new AtomicInteger();
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
     * Called every presence sweep: seeds fast while the registry is well below target (a raised
     * TARGET_CITIZENS or a restart mid-seed), and lets BIRTHS_PER_DAY handle the last tenth.
     */
    void step(long now) {
        int active = population.activeCount();
        int target = population.config().TARGET_CITIZENS;
        if (active >= target) {
            seeding = false;
            hiresToReplace.set(0);
            return;
        }
        if (active < target - target / FAST_SEED_SHORTFALL_DIVISOR || seeding) {
            seeding = true;
            int batch = Math.min(SEED_BATCH, target - active);
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
