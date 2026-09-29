package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.combat.learning.AgentCombatCandidates;
import server.agents.capabilities.combat.learning.AgentCombatLearnGuard;
import server.living.LivingWorld;
import server.living.resident.ResidentRuntime;
import server.maps.MapleMap;

/**
 * Bench combat learning (docs/BOT_MANAGEMENT_PLAN.md decision 13 a, "Bench combat learning"): the living world's
 * answer to the combat learner's "is this Agent one nobody can see?" (AgentCombatLearnGuard). It is a companion on a
 * live field slice (the BENCH_LIVE pool: TRAIN, FARM_MESO, FARM_ITEM, GATHER), hunting (not restocking), on a map with
 * no human on it. Such a grinder explores in LEARN mode into its job's candidate table (AgentCombatCandidates), which
 * no companion near a player ever plays: the arena's {@code !combatarena <agent> policy=candidate} adopts it only
 * where it is not worse than live play. The moment a human steps onto its map, its next decision is live play again,
 * and learning resumes when they leave. BENCH_COMBAT_LEARN off learns nothing.
 *
 * <p>The rule is asked on the Agent tick at each combat decision, so it only reads memory: the dispatcher's slices and
 * the map's players. What was learned is read at boot and written every BENCH_COMBAT_LEARN_SAVE_MS and at shutdown on
 * companion-ops ({@link #load}, {@link #save}).</p>
 */
public final class BenchCombatLearning implements AgentCombatLearnGuard.Rule {
    private static final Logger log = LoggerFactory.getLogger(BenchCombatLearning.class);

    private final LivingWorld world;

    public BenchCombatLearning(LivingWorld world) {
        this.world = world;
    }

    public void install() {
        AgentCombatLearnGuard.install(this);
    }

    public static void uninstall() {
        AgentCombatLearnGuard.install(null);
    }

    @Override
    public boolean learnUnseen(Character bot) {
        LivingWorldConfig config = world.config();
        BenchDutyDispatcher dispatcher = world.benchDuty();
        if (bot == null || config == null || !config.BENCH_COMBAT_LEARN || dispatcher == null) {
            return false;
        }
        BenchDutyDispatcher.Slice slice = dispatcher.sliceOf(bot.getId());
        if (slice == null || slice.pool() != CompanionBudget.Kind.BENCH_LIVE || slice.resupplying()) {
            return false;
        }
        MapleMap map = bot.getMap();
        return map != null && ResidentRuntime.humansIn(map) == 0;
    }

    @Override
    public double epsilon() {
        return world.config().BENCH_COMBAT_LEARN_EPSILON;
    }

    /** What bench grinders learned before, at boot (companion-ops). */
    public void load() {
        int jobs = AgentCombatCandidates.defaultCandidates().loadAll();
        if (jobs > 0) {
            log.info("bench combat learning: candidates of {} jobs read", jobs);
        }
    }

    /** Writes what bench grinders learned since the last write (companion-ops). */
    public void save() {
        int jobs = AgentCombatCandidates.defaultCandidates().flush();
        if (jobs > 0) {
            log.debug("bench combat learning: candidates of {} jobs written", jobs);
        }
    }
}
