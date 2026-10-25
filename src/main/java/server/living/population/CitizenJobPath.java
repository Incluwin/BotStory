package server.living.population;

import client.Job;
import client.JobProgressionPolicy;
import constants.game.GameConstants;
import server.agents.capabilities.build.AgentCharacterShaper;
import server.agents.capabilities.build.AgentJobPath;

/**
 * The jobs a citizen's life leads through (2026-10-06). careers.yaml names a second job (FIGHTER); its line goes on to
 * the third and fourth job of that branch (CRUSADER, HERO), with no choice left to make. The first and second job come
 * off-screen with the catch-up when the citizen loads (ProgressionLedger); the third and fourth it takes at its
 * instructor, as a player does (CitizenJobErrands: El Nath's chiefs, Leafre's elders). A citizen already past its Lv70
 * when this shipped is a backlog citizen: its overdue job comes off-screen once ({@link Citizen#FLAG_JOB_BACKLOG}).
 */
public final class CitizenJobPath {
    /** Job branch (GameConstants.getJobBranch) from which an advancement is an errand: the third job on. */
    static final int FIRST_ERRAND_BRANCH = 3;

    private CitizenJobPath() {
    }

    /** Its career's own line to the fourth job (FIGHTER: HERO); a career job that is not a second job is its own end. */
    public static Job line(Job careerJob) {
        if (careerJob == null || GameConstants.getJobBranch(careerJob) != 2) {
            return careerJob;
        }
        Job fourth = Job.getById(careerJob.getId() + 2);
        return fourth == null ? careerJob : fourth;
    }

    /**
     * What the catch-up shapes toward. {@code offScreen} (a backlog citizen, or errands switched off): its whole line, so
     * every job the game allows at its level comes now. Else its career job, or the later job it holds on that line
     * (the next one is an errand, never a reshape).
     */
    public static Job shapeTarget(Job careerJob, Job current, boolean offScreen) {
        Job line = line(careerJob);
        if (offScreen) {
            return line;
        }
        if (current != null && careerJob != null && GameConstants.getJobBranch(current) > GameConstants.getJobBranch(careerJob)
                && AgentCharacterShaper.advancementChain(line).contains(current)) {
            return current;
        }
        return careerJob;
    }

    /** The job it takes at its instructor now: the next third/fourth job step on its line the game allows at {@code level}. */
    public static Job errandStep(Job careerJob, Job current, int level) {
        Job step = AgentJobPath.next(current, line(careerJob), level);
        return step != null && GameConstants.getJobBranch(step) >= FIRST_ERRAND_BRANCH ? step : null;
    }

    /**
     * True when the citizen is already old enough for a third/fourth job on its line that it does not hold: at rollout
     * that makes it a backlog citizen. A citizen still at first job counts too (its catch-up brings the second first).
     */
    public static boolean overdue(Job careerJob, Job current, int level) {
        if (current == null) {
            return false;
        }
        int held = GameConstants.getJobBranch(current);
        for (Job step : AgentCharacterShaper.advancementChain(line(careerJob))) {
            int branch = GameConstants.getJobBranch(step);
            if (branch >= FIRST_ERRAND_BRANCH && branch > held && level >= JobProgressionPolicy.minimumLevel(step)) {
                return true;
            }
        }
        return false;
    }
}
