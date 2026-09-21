package server.living.population;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Births and retirements (plan 3.5). An empty registry is seeded toward the target in batches,
 * backdated so levels look like a mature server; afterwards a few citizens are born a day and
 * citizens past their retirement date stop logging in (with a goodbye if anyone can hear it).
 */
public final class Churn {
    private static final Logger log = LoggerFactory.getLogger(Churn.class);
    private static final int SEED_BATCH = 12;

    private final Population population;
    private final Random random;
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

    /** Called every presence sweep: seeds fast while the registry is far below target. */
    void step(long now) {
        int active = population.activeCount();
        int target = population.config().TARGET_CITIZENS;
        if (active >= target) {
            seeding = false;
            return;
        }
        if (active < target / 2 || seeding) {
            seeding = true;
            int batch = Math.min(SEED_BATCH, target - active);
            for (int i = 0; i < batch; i++) {
                int backdate = random.nextInt(Math.max(1, population.config().SEED_BACKDATE_DAYS + 1));
                if (population.generator().birth(null, backdate) == null) {
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
                if (citizen.state() == Citizen.State.ACTIVE && citizen.retiresAtMs() > 0 && citizen.retiresAtMs() < now) {
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

    public void retire(Citizen citizen, String reason) {
        CitizenSession session = population.presence().session(citizen.characterId());
        if (session != null && !session.virtual) {
            population.chatter().event(ChatterEngine.Event.of("logout", session.mapId, session.channel).self(session)
                    .withText("quitting maple, was fun everyone"));
            population.presence().logout(session, reason);
        } else if (session != null) {
            population.presence().logout(session, reason);
        }
        population.social().onRetire(citizen);
        population.save(citizen.withState(Citizen.State.RETIRED));
        log.info("citizen {} retired: {}", citizen.name(), reason);
    }
}
