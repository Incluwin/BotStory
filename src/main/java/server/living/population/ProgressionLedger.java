package server.living.population;

import client.Character;
import client.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.config.Content.Loadout;
import server.living.population.PopulationContent.Career;
import server.living.resident.ResidentShaper;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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

    /** On load: shape up to the life-curve target (job chain, growth repair, AP, loadout for the level). */
    public void catchUp(Character agent, Citizen citizen) {
        Career career = citizen.hired() ? null : population.content().career(citizen.career());
        if (career == null) { // a hired hand's level, job and gear are its own doing now
            return;
        }
        int target = Math.max(agent.getLevel(), levelTarget(citizen, System.currentTimeMillis()));
        Job job = ResidentShaper.job(career.job());
        Loadout loadout = population.world().content().loadout(career.loadoutFor(target));
        try {
            if (agent.getLevel() < target || agent.getJob() == Job.BEGINNER && target >= 10 || !agent.getJob().equals(job) && target >= 30) {
                ResidentShaper.shapeTo(agent, job, target, loadout);
            }
            ResidentShaper.wearMissingEquips(agent, loadout, false); // a band edited after shaping (citizens are never dressed)
            CitizenGear.upgrade(agent, job, loadout); // every load: a level bumped in SQL skipped the shaping above
            long expectedMeso = Market.expectedMeso(agent.getLevel());
            if (agent.getMeso() < expectedMeso / 2) {
                agent.gainMeso((int) Math.min(Integer.MAX_VALUE / 2, expectedMeso - agent.getMeso()), false);
            }
        } catch (RuntimeException failure) {
            log.warn("catch-up shaping for {} failed", citizen.name(), failure);
        }
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
