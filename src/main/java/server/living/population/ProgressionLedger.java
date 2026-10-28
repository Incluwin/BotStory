package server.living.population;

import client.Character;
import client.Job;
import constants.game.GameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.build.AgentJobPath;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.living.config.Content.Loadout;
import server.living.population.PopulationContent.Career;
import server.living.resident.ResidentShaper;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real levels over weeks (plan 3.4). Loaded citizens level by fighting; everyone else follows the
 * life curve: the ledger raises the stored level of offline citizens toward their age target so
 * the rank board moves, and {@link #catchUp} repairs growth, advances jobs and upgrades gear the
 * next time the character is loaded. Never lowers a level a citizen earned for real.
 */
public final class ProgressionLedger {
    private static final Logger log = LoggerFactory.getLogger(ProgressionLedger.class);
    private static final int SLICE = 60;
    private static final int SUMMARY_MAX_CHARS = 480;

    private final Population population;
    private int cursor;

    ProgressionLedger(Population population) {
        this.population = population;
    }

    public int levelTarget(Citizen citizen, long nowMs) {
        Career career = population.content().career(citizen.career());
        int cap = career == null ? 70 : career.levelCap();
        return LifeCurve.levelTarget(citizen.ageDays(nowMs), citizen.pace(), cap, population.config().LIFE_PACE);
    }

    /**
     * The pocket money a citizen gets on this load: its purse up to {@link Market#expectedMeso} when it holds under half
     * of that. With {@code once} (economy.CITIZEN_POCKET_MONEY_ONCE) only at its first load: a top-up on every load
     * refilled whatever it had spent, which printed the meso it bought from players with.
     */
    static long pocketMoney(Citizen citizen, int meso, int level, boolean once) {
        if (once && citizen.lastOnlineAtMs() > 0L) {
            return 0L;
        }
        long expected = Market.expectedMeso(level);
        return meso < expected / 2 ? Math.min(Integer.MAX_VALUE / 2, expected - meso) : 0L;
    }

    /**
     * On load: shape up to the life-curve target (job chain, growth repair, AP, loadout for the level), else repair the
     * growth and skills of levels this ledger wrote alone. The first and second job come here; a third or fourth job
     * only for a backlog citizen or with job errands off (CitizenJobPath), and then that job is returned (else null).
     */
    public Job catchUp(Character agent, Citizen citizen) {
        Career career = citizen.hired() ? null : population.content().career(citizen.career());
        if (career == null) { // a hired hand's level, job and gear are its own doing now
            return null;
        }
        int target = Math.max(agent.getLevel(), levelTarget(citizen, System.currentTimeMillis()));
        Job job = ResidentShaper.job(career.job());
        boolean backlog = citizen.has(Citizen.FLAG_JOB_BACKLOG);
        Job before = agent.getJob();
        Job shapeTarget = CitizenJobPath.shapeTarget(job, before, backlog || !population.config().JOB_ERRANDS_ENABLED);
        Loadout loadout = population.world().content().loadout(career.loadoutFor(target));
        try {
            if (agent.getLevel() < target || AgentJobPath.next(before, shapeTarget, target) != null) {
                ResidentShaper.shapeTo(agent, shapeTarget, target, loadout);
            } else {
                ResidentShaper.repair(agent, loadout); // a level bumped in SQL, a first job left unspent
            }
            ResidentShaper.wearMissingEquips(agent, loadout, false); // a band edited after shaping (citizens are never dressed)
            // every load: a level bumped in SQL skipped the shaping above; a want on the needs board holds its pick back
            CitizenGear.upgrade(agent, job, loadout, population.needs() == null ? CitizenGear.Wants.NONE
                    : (who, slot, pick) -> population.needs().gearWant(who, slot, pick, System.currentTimeMillis()));
            long pocketMoney = pocketMoney(citizen, agent.getMeso(), agent.getLevel(),
                    config.YamlConfig.config.economy.CITIZEN_POCKET_MONEY_ONCE);
            if (pocketMoney > 0L) {
                try (var ledgerScope = MesoFlows.as(MesoAccount.TOP_UP)) { agent.gainMeso((int) pocketMoney, false); }
            }
            PartyMegaphones.stock(population, agent, citizen); // a few megaphones for its PQ parties (F5c)
        } catch (RuntimeException failure) {
            log.warn("catch-up shaping for {} failed", citizen.name(), failure);
        }
        if (backlog) { // one off-screen catch-up for the job it was overdue at rollout; every later one is an errand
            population.update(citizen.characterId(), latest -> latest.withoutFlag(Citizen.FLAG_JOB_BACKLOG));
        }
        Job after = agent.getJob();
        return after != before && GameConstants.getJobBranch(after) >= CitizenJobPath.FIRST_ERRAND_BRANCH ? after : null;
    }

    /**
     * Once, when citizens started taking their third and fourth job (2026-10-06): every active citizen already old
     * enough for one it does not hold is flagged {@link Citizen#FLAG_JOB_BACKLOG}, so that job comes off-screen with its
     * next catch-up instead of a crowd of errands. Citizens who reach the level later take the errand. Returns how many.
     */
    public int markJobBacklog() {
        int marked = 0;
        Map<Integer, int[]> progress = population.store().levelsAndJobs();
        for (Citizen citizen : population.citizens()) {
            Career career = citizen.state() != Citizen.State.ACTIVE || citizen.hired() ? null : population.content().career(citizen.career());
            int[] levelAndJob = progress.get(citizen.characterId());
            if (career == null || levelAndJob == null || citizen.has(Citizen.FLAG_JOB_BACKLOG)) {
                continue;
            }
            Job current = Job.getById(levelAndJob[1]);
            if (CitizenJobPath.overdue(ResidentShaper.job(career.job()), current, levelAndJob[0])) {
                population.update(citizen.characterId(), latest -> latest.hired() ? latest : latest.withFlag(Citizen.FLAG_JOB_BACKLOG));
                marked++;
            }
        }
        return marked;
    }

    /** Raises stored levels of a slice of offline citizens toward their target (the rank board keeps moving). */
    public void tick() {
        List<Citizen> active = new ArrayList<>();
        for (Citizen citizen : population.citizens()) {
            if (citizen.state() == Citizen.State.ACTIVE && !citizen.hired()) { // a hired hand levels for real
                active.add(citizen);
            }
        }
        if (active.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int touched = 0;
        for (int i = 0; i < Math.min(SLICE, active.size()); i++) {
            Citizen citizen = active.get(Math.floorMod(cursor++, active.size()));
            if (population.presence().session(citizen.characterId()) != null && !population.presence().session(citizen.characterId()).virtual) {
                continue; // fighting for real
            }
            int target = levelTarget(citizen, now);
            int[] progress = population.store().characterProgress(citizen.characterId());
            Citizen current = population.citizen(citizen.characterId()); // hired since the list was made: hands off
            if (current == null || current.hired()) {
                continue;
            }
            if (progress[0] > 0 && progress[0] < target) {
                bumpLevel(citizen.characterId(), target);
                touched++;
            }
            if (current.lastLedgerAtMs() < now - 6 * 3_600_000L) {
                population.update(citizen.characterId(), latest -> latest.hired() ? latest
                        : latest.withLedger(now, latest.accruedExp(), latest.scheduledMinutes()));
            }
        }
        if (touched > 0) {
            log.debug("ledger raised {} offline citizens toward their life-curve level", touched);
        }
    }

    /** Ops/test knob (!citizens level): raises an offline citizen's stored level as this ledger does; never lowers it. */
    public void raiseStoredLevel(int characterId, int level) {
        bumpLevel(characterId, Math.max(1, Math.min(level, 200)));
    }

    private void bumpLevel(int characterId, int level) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE characters SET level = ?, exp = 0 WHERE id = ? AND level < ?")) {
            ps.setInt(1, level);
            ps.setInt(2, characterId);
            ps.setInt(3, level);
            ps.executeUpdate();
        } catch (SQLException failure) {
            log.warn("ledger level bump failed for {}", characterId, failure);
        }
    }

    /** Folds the journal into the citizen's persisted one-line life summary (plan 6.4). */
    public void refreshSummary(Citizen citizen, Journal journal) {
        if (citizen == null || journal == null || journal.isEmpty()) {
            return;
        }
        int[] progress = population.store().characterProgress(citizen.characterId());
        String head = "lv" + progress[0] + (citizen.guildId() > 0 ? ", in a guild" : "") + ", plays " + citizen.cohort().replace('-', ' ');
        String recent = journal.summary(SUMMARY_MAX_CHARS - head.length() - 3);
        population.save(citizen.withSummary(recent.isEmpty() ? head : head + " | " + recent));
    }
}
