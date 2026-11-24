package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.combat.AgentSupportPoints;
import server.living.LivingWorld;
import server.living.store.LivingStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Learned support points (docs/WORLD_SYSTEMS_PLAN.md I1.4, population.SUPPORT_LEARNING_ENABLED): per job, a bandit over
 * the HP share to heal under (HEAL) and one over the share of a buff's duration to recast it at (BUFF), on the shared
 * Ucb arithmetic (PriceArms). One Agent plays one arm of each lever for SUPPORT_EPISODE_MS; the episode is then worth
 * its party's damage uptime (each member's time with no gap over {@link #GAP_MS} between attacks, over the members seen
 * attacking), and a death in the party ends it at once for SUPPORT_DEATH_PENALTY less. An episode with too few attacks,
 * or broken off (another map or party, not seen for a while), books nothing. Only free citizens explore; a companion
 * plays the best arm once it has SUPPORT_MIN_EPISODES, else today's point. No timer: episodes end on the next question
 * or attack. Off, nothing is asked, kept or stored (AgentSupportPoints answers today's numbers).
 */
public final class SupportLearning implements AgentSupportPoints.Rule {
    private static final Logger log = LoggerFactory.getLogger(SupportLearning.class);
    static final double[] HEAL_POINTS = {0.80d, 0.85d, 0.90d, 0.95d};
    static final double[] BUFF_SHARES = {0.86d, 0.88d, 0.90d, 0.95d};
    /** The arm both levers start from: what the rules do today (0.90 and 0.90). */
    static final int TODAY = 2;
    /** Two attacks of one member further apart than this count this much time, no more. */
    static final long GAP_MS = 3_000L;
    static final long STALE_MS = 60_000L;
    static final int MIN_ATTACKS = 20;
    private static final long RETRY_READ_MS = 60_000L;
    /** Today's arms start this much above the rest. */
    private static final double TODAY_EDGE = 0.02d;

    /** One Agent playing one arm of each lever. */
    private static final class Episode {
        final String job;
        final int healArm;
        final int buffArm;
        final int mapId;
        final int partyId;
        final long startedAtMs;
        long seenAtMs;
        boolean healed;
        boolean buffed;
        int attacks;
        long upMs;
        final Map<Integer, Long> lastAttackAt = new HashMap<>();

        Episode(String job, int healArm, int buffArm, int mapId, int partyId, long nowMs) {
            this.job = job;
            this.healArm = healArm;
            this.buffArm = buffArm;
            this.mapId = mapId;
            this.partyId = partyId;
            this.startedAtMs = nowMs;
            this.seenAtMs = nowMs;
        }

        void attack(int memberId, long nowMs) {
            Long last = lastAttackAt.put(memberId, nowMs);
            upMs += last == null ? 0 : Math.min(GAP_MS, Math.max(0, nowMs - last));
            attacks++;
            seenAtMs = nowMs;
        }

        double uptime(long nowMs) {
            long span = Math.max(1, nowMs - startedAtMs) * Math.max(1, lastAttackAt.size());
            return Math.min(1.0d, upMs / (double) span);
        }
    }

    private final Population population;
    private final PriceArms heals;
    private final PriceArms buffs;
    private final Random random;
    private final Map<Integer, Episode> episodes = new HashMap<>();
    private volatile boolean loaded;
    private volatile long lastReadFailMs;

    SupportLearning(Population population) {
        this.population = population;
        PopulationConfig config = population.config();
        double prior = config == null ? 0.5d : config.SUPPORT_PRIOR_UPTIME;
        this.heals = lever("HEAL", HEAL_POINTS, prior);
        this.buffs = lever("BUFF", BUFF_SHARES, prior);
        this.random = new Random((config == null ? 0L : config.SEED) ^ 0x5eed_0114L);
    }

    private static PriceArms lever(String name, double[] points, double prior) {
        return new PriceArms(name, points, m -> Math.abs(m - points[TODAY]) < 1e-9 ? prior + TODAY_EDGE : prior);
    }

    void install() {
        AgentSupportPoints.install(this);
    }

    static void uninstall() {
        AgentSupportPoints.install(null);
    }

    /** The switch, read live. */
    @Override
    public boolean on() {
        PopulationConfig config = population.config();
        return config != null && config.SUPPORT_LEARNING_ENABLED && load();
    }

    @Override
    public float healRatio(Character agent, float base) {
        if (!population.isCitizen(agent.getId()) || hired(agent)) {
            return (float) known(heals, job(agent), base);
        }
        synchronized (this) {
            Episode episode = episode(agent, System.currentTimeMillis());
            episode.healed = true;
            return (float) heals.multiplier(episode.healArm);
        }
    }

    @Override
    public double refreshShare(Character agent, double base) {
        if (!population.isCitizen(agent.getId()) || hired(agent)) {
            return known(buffs, job(agent), base);
        }
        synchronized (this) {
            Episode episode = episode(agent, System.currentTimeMillis());
            episode.buffed = true;
            return buffs.multiplier(episode.buffArm);
        }
    }

    private boolean hired(Character agent) {
        Citizen citizen = population.citizen(agent.getId());
        return citizen != null && citizen.hired();
    }

    /** What citizens found best for {@code job}, once its arm has the episodes; else {@code base}. */
    double known(PriceArms lever, String job, double base) {
        PopulationConfig config = population.config();
        int best = lever.best(job, config.SUPPORT_PRIOR_SAMPLES);
        for (PriceArms.Row row : lever.rows()) {
            if (row.key().equals(job) && row.arm() == best && row.n() >= config.SUPPORT_MIN_EPISODES) {
                return row.multiplier();
            }
        }
        return base;
    }

    /** The agent's running episode: a new one when it has none, the last is over, or it went elsewhere. */
    private Episode episode(Character agent, long nowMs) {
        Episode open = episodes.get(agent.getId());
        boolean here = open != null && open.mapId == agent.getMapId() && open.partyId == agent.getPartyId()
                && open.job.equals(job(agent)) && nowMs - open.seenAtMs <= STALE_MS;
        if (here && nowMs - open.startedAtMs < population.config().SUPPORT_EPISODE_MS) {
            open.seenAtMs = nowMs;
            return open;
        }
        if (here) {
            settle(open, 0, nowMs);
        }
        String job = job(agent);
        Episode next = new Episode(job, heals.pick(job, tuning(), random), buffs.pick(job, tuning(), random),
                agent.getMapId(), agent.getPartyId(), nowMs);
        episodes.put(agent.getId(), next);
        return next;
    }

    @Override
    public synchronized void attacked(Character agent, long nowMs) {
        Episode own = episodes.get(agent.getId());
        if (own != null && own.mapId == agent.getMapId() && own.partyId == agent.getPartyId()) {
            own.attack(agent.getId(), nowMs);
        }
        if (agent.getPartyId() <= 0) {
            return;
        }
        for (Map.Entry<Integer, Episode> entry : episodes.entrySet()) {
            Episode mate = entry.getValue();
            if (entry.getKey() != agent.getId() && mate.partyId == agent.getPartyId() && mate.mapId == agent.getMapId()) {
                mate.attack(agent.getId(), nowMs);
            }
        }
    }

    /** A character died, human or Agent: it ends its own episode and those of its party on its map. */
    synchronized void died(Character chr, long nowMs) {
        if (episodes.isEmpty() || !on()) {
            return;
        }
        episodes.entrySet().removeIf(entry -> {
            Episode episode = entry.getValue();
            boolean theirs = entry.getKey() == chr.getId()
                    || chr.getPartyId() > 0 && episode.partyId == chr.getPartyId();
            if (theirs && episode.mapId == chr.getMapId() && nowMs - episode.seenAtMs <= STALE_MS) {
                settle(episode, 1, nowMs);
                return true;
            }
            return false;
        });
    }

    private void settle(Episode episode, int deaths, long nowMs) {
        if (deaths == 0 && episode.attacks < MIN_ATTACKS) {
            return;
        }
        double reward = episode.uptime(nowMs) - deaths * population.config().SUPPORT_DEATH_PENALTY;
        if (episode.healed) {
            save(heals, heals.reward(episode.job, episode.healArm, reward, 1));
        }
        if (episode.buffed) {
            save(buffs, buffs.reward(episode.job, episode.buffArm, reward, 1));
        }
    }

    private static String job(Character agent) {
        return Integer.toString(agent.getJob() == null ? 0 : agent.getJob().getId());
    }

    private PriceArms.Tuning tuning() {
        PopulationConfig config = population.config();
        return new PriceArms.Tuning(config.SUPPORT_EXPLORE, config.SUPPORT_TEMPERATURE, config.SUPPORT_PRIOR_SAMPLES);
    }

    PriceArms heals() {
        return heals;
    }

    PriceArms buffs() {
        return buffs;
    }

    /** Reads the stored arms once, off the Agent tick: until they are read, today's points hold. */
    private boolean load() {
        if (loaded) {
            return true;
        }
        LivingWorld world = population.world();
        if (world == null || world.store() == null) {
            loaded = true; // nowhere to keep them: in memory since boot
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (world.executor() == null || now - lastReadFailMs < RETRY_READ_MS) {
                return loaded;
            }
            lastReadFailMs = now;
        }
        world.executor().submit("support.arms", () -> {
            try {
                List<LivingStore.YieldRow> rows = world.store().supportArms();
                for (LivingStore.YieldRow row : rows) {
                    PriceArms lever = row.jobBranch().startsWith("HEAL_") ? heals
                            : row.jobBranch().startsWith("BUFF_") ? buffs : null;
                    if (lever != null) {
                        lever.load(row.jobBranch().substring(5), row.levelBand(), row.samples(), row.expPerHour());
                    }
                }
                loaded = true;
                log.info("support learning read {} arm rows", rows.size());
            } catch (RuntimeException failure) {
                log.warn("support learning could not read its arms; today's points hold until it can", failure);
            }
        });
        return false;
    }

    private void save(PriceArms lever, PriceArms.Row row) {
        LivingWorld world = population.world();
        if (world == null || world.store() == null || world.executor() == null) {
            return;
        }
        LivingStore.YieldRow stored = new LivingStore.YieldRow(lever.storedKey(row.key()), row.arm(), 0, row.n(),
                row.mean(), row.multiplier(), 0.0d, 0.0d);
        world.executor().submit("support.arm " + stored.jobBranch(), () -> world.store().saveSupportArm(stored));
    }
}
