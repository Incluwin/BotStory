package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.living.LivingWorld;
import server.living.population.PopulationContent.Field;
import server.living.roster.BenchDutyChooser;
import server.living.roster.BenchDutyDispatcher;
import server.living.roster.BenchYieldModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

/**
 * Citizens learn where to train (docs/BOT_CONCEPT_PLAN.md F2.1, population.CITIZEN_SPOT_LEARNING_ENABLED).
 *
 * <p>Booking: a loaded citizen's TRAIN stint on a field begins as it starts to grind there ({@link #begin}) and is booked
 * when it ends ({@link #end}): it moves on, goes AFK or logs off; a death books it at once with its death. Only what it
 * really fought counts, the best of what is observed: a stint shorter than CITIZEN_SPOT_MIN_MS is noise, one whose
 * citizen left the field another way (its own shop trip, so the shop walk would count as hunting) or whose Agent runs
 * abstract proves nothing, and an unloaded (virtual) citizen books nothing. A stint is booked per (job branch, level
 * band, field) as BenchYieldModel's citizen rows (source 'citizen'), on companion-ops.</p>
 *
 * <p>Picking: among the training fields FieldCatalog keeps (mob level, capacity, the healer rule, the human-map
 * preference come first), each field is scored as BenchDutyChooser scores a camp: the EXP an hour citizens of its class
 * line and band measured there (shrunk toward the ledger's prior, less for monsters far from its level, by
 * BENCH_YIELD_PRIOR_SLICES), against the ledger's rate at its level, less BENCH_CHOOSER_DEATH_COST per death an hour;
 * plus a UCB bonus, CITIZEN_SPOT_EXPLORE x sqrt(ln(1 + N) / (1 + n)), so a field few citizens measured gets its tries;
 * less a crowding term, CITIZEN_SPOT_CROWDING x the share of its capacity loaded citizens already fill, so fewer bots on
 * a map score higher. The pick is weighted, never the argmax: each field is drawn with weight
 * exp((ucb - best) / CITIZEN_SPOT_TEMPERATURE), so citizens planning at the same moment do not all walk to one map.</p>
 */
public final class CitizenSpots {
    private static final Logger log = LoggerFactory.getLogger(CitizenSpots.class);

    /** One field as scored for a citizen: the measured score, its exploration bonus and its crowding cost. */
    record Arm(Field field, double score, double bonus, double crowd) {
        double ucb() {
            return score + bonus - crowd;
        }
    }

    /** A loaded citizen's TRAIN stint on one field, from where it began: booked when it ends. */
    record Stint(int mapId, int jobId, int level, long exp, long startedAtMs, AtomicInteger deaths) { }

    private final Population population;

    CitizenSpots(Population population) {
        this.population = population;
    }

    private boolean on() {
        PopulationConfig config = population.config();
        return config != null && config.CITIZEN_SPOT_LEARNING_ENABLED && population.world() != null
                && population.world().benchYields() != null;
    }

    // ---- picking ---------------------------------------------------------------------------

    /** The learned pick among {@code candidates} for a citizen of {@code level} and {@code jobId}; null when off. */
    Field pick(List<Field> candidates, int level, int jobId, Random random) {
        if (!on() || jobId < 0 || candidates.isEmpty()) {
            return null;
        }
        LivingWorld world = population.world();
        PopulationConfig config = population.config();
        List<Arm> arms = arms(candidates, level, jobId, world.benchYields(), world.bench().expPrior(level),
                population.fields()::occupancy, config.CITIZEN_SPOT_EXPLORE, config.CITIZEN_SPOT_CROWDING,
                world.config().BENCH_YIELD_PRIOR_SLICES, world.config().BENCH_CHOOSER_DEATH_COST);
        return draw(arms, config.CITIZEN_SPOT_TEMPERATURE, random);
    }

    /** Each candidate scored for a citizen of {@code level} and {@code jobId} (see the class comment). */
    static List<Arm> arms(List<Field> candidates, int level, int jobId, BenchYieldModel yields, double baseExp,
                          IntUnaryOperator occupancy, double explore, double crowding, double priorSlices, double deathCost) {
        double base = Math.max(1e-9d, baseExp);
        double total = 0.0d;
        for (Field field : candidates) {
            total += samples(yields.citizenMeasured(BenchYieldModel.Key.of(jobId, level, field.mapId())));
        }
        List<Arm> arms = new ArrayList<>();
        for (Field field : candidates) {
            BenchYieldModel.Rates measured = yields.citizenMeasured(BenchYieldModel.Key.of(jobId, level, field.mapId()));
            double n = samples(measured);
            double fit = BenchDutyChooser.fit(field.mobLevel(), level);
            double deathPrior = BenchDutyChooser.deathPrior(field.mobLevel(), level);
            double exp = measured == null ? fit : BenchYieldModel.shrink(n, measured.expPerHour() / base, fit, priorSlices);
            double deaths = measured == null ? deathPrior
                    : BenchYieldModel.shrink(n, measured.deathsPerHour(), deathPrior, priorSlices);
            double bonus = explore * Math.sqrt(Math.log(1.0d + total) / (1.0d + n));
            double crowd = crowding * Math.min(1.0d, occupancy.applyAsInt(field.mapId()) / (double) Math.max(1, field.capacity()));
            arms.add(new Arm(field, exp - deathCost * deaths, bonus, crowd));
        }
        return arms;
    }

    /** A weighted draw: each arm with weight exp((ucb - best) / temperature); the best arm when temperature is 0. */
    static Field draw(List<Arm> arms, double temperature, Random random) {
        Arm best = null;
        for (Arm arm : arms) {
            if (best == null || arm.ucb() > best.ucb()) {
                best = arm;
            }
        }
        if (best == null || temperature <= 0.0d) {
            return best == null ? null : best.field();
        }
        double[] weights = weights(arms, temperature);
        double total = 0.0d;
        for (double weight : weights) {
            total += weight;
        }
        double roll = random.nextDouble() * total;
        for (int i = 0; i < arms.size(); i++) {
            roll -= weights[i];
            if (roll < 0.0d) {
                return arms.get(i).field();
            }
        }
        return best.field();
    }

    /** exp((ucb - best) / temperature) for each arm, in order: the best weighs 1, the rest less. */
    static double[] weights(List<Arm> arms, double temperature) {
        double best = Double.NEGATIVE_INFINITY;
        for (Arm arm : arms) {
            best = Math.max(best, arm.ucb());
        }
        double[] out = new double[arms.size()];
        for (int i = 0; i < arms.size(); i++) {
            out[i] = Math.exp((arms.get(i).ucb() - best) / temperature);
        }
        return out;
    }

    private static double samples(BenchYieldModel.Rates rates) {
        return rates == null ? 0.0d : Math.max(0.0d, rates.samples());
    }

    // ---- booking ---------------------------------------------------------------------------

    /** A loaded citizen starts to grind on its TRAIN field: its stint there begins now (one open at a time). */
    void begin(CitizenSession session, long now) {
        Character agent = session.agent();
        if (!on() || session.virtual || agent == null || agent.getJob() == null) {
            session.stint = null;
            return;
        }
        session.stint = new Stint(agent.getMapId(), agent.getJob().getId(), agent.getLevel(), agent.getExp(), now,
                new AtomicInteger());
    }

    /** The citizen died: its open stint is booked now with its death (it respawns in town, off its field). */
    void died(CitizenSession session, long now) {
        Stint stint = session.stint;
        if (stint != null) {
            stint.deaths().incrementAndGet();
            end(session, now);
        }
    }

    /** Its open stint ends (it moves on, goes AFK, logs off, died): booked when it was a clean one long enough. */
    void end(CitizenSession session, long now) {
        Stint stint = session.stint;
        session.stint = null;
        Character agent = session.agent();
        long ranMs = stint == null ? 0L : now - stint.startedAtMs();
        if (stint == null || agent == null || !on() || ranMs < population.config().CITIZEN_SPOT_MIN_MS) {
            return;
        }
        boolean died = stint.deaths().get() > 0;
        AgentRuntimeEntry entry = session.entry;
        if (!died && agent.getMapId() != stint.mapId()
                || entry != null && entry.simulationState().mode() == AgentSimulationMode.BACKGROUND_ABSTRACT) {
            return; // it left its field another way (its own shop trip), or nothing it did there was fought for real
        }
        BenchYieldModel.Key key = BenchYieldModel.Key.of(stint.jobId(), stint.level(), stint.mapId());
        BenchYieldModel.Sample sample = new BenchYieldModel.Sample(ranMs / 3_600_000.0d,
                BenchDutyDispatcher.expGained(stint.level(), stint.exp(), agent.getLevel(), agent.getExp()), 0L, 0L,
                stint.deaths().get());
        LivingWorld world = population.world();
        world.executor().submit("citizen.spot " + session.characterId, () -> world.benchYields().recordCitizen(key, sample));
        log.debug("citizen {} booked {} min at {}: {} exp, {} deaths", session.name(), ranMs / 60_000L, stint.mapId(),
                sample.exp(), sample.deaths());
    }
}
