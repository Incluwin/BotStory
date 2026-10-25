package server.agents.capabilities.build;

import client.Character;
import client.Job;
import client.JobProgressionPolicy;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;

/**
 * The job an Agent is set on becoming, and the step along it the game allows now: the owner-free seam through which a
 * host (the living world, for its companions) names an Agent's path. An Agent with a path is never asked to pick a job
 * ({@link AgentBuildService#buildJobPrompt}); when its next step is legal the rule hears so, and the host decides how
 * and where the Agent takes it ({@link #advance} is the job change itself). With no rule installed, or no path for an
 * Agent, nothing changes: its owner picks each job in chat.
 *
 * <p>{@link Rule#path} answers from memory on any thread; {@link Rule#due} runs on the Agent tick and must only hand
 * work over.</p>
 */
public final class AgentJobPath {
    private static volatile Rule rule;

    private AgentJobPath() {
    }

    public interface Rule {
        /** The job the Agent is set on becoming from {@code current}, or null when its owner chooses. Memory only. */
        Job path(int agentCharacterId, Job current);

        /** The Agent may take {@code next} now. Runs on the Agent tick: hand the work over. */
        void due(int agentCharacterId, Job next);
    }

    /** Installs the host's rule; null takes it out again (every job is its owner's pick, as before). */
    public static void install(Rule value) {
        rule = value;
    }

    /**
     * The job after {@code current} on the legal chain to {@code path} (its line's beginner: Beginner, Noblesse or
     * Legend, then first job, ... {@code path}) when the game allows it at {@code level}; null when there is none now
     * or {@code current} is not on that chain.
     */
    public static Job next(Job current, Job path, int level) {
        if (current == null || path == null || current == path) {
            return null;
        }
        List<Job> chain = AgentCharacterShaper.advancementChain(path);
        boolean beginner = current == JobProgressionPolicy.beginnerOf(path);
        int at = beginner ? -1 : chain.indexOf(current);
        if ((at < 0 && !beginner) || at + 1 >= chain.size()) {
            return null;
        }
        Job step = chain.get(at + 1);
        return JobProgressionPolicy.isLegalAdvancement(current, step, level) ? step : null;
    }

    /**
     * Takes {@code next} now, as its instructor would: the stock job change, then the Agent's own build follow-up (SP,
     * AP, starter kit, gear). True when the Agent holds that job afterwards.
     */
    public static boolean advance(AgentRuntimeEntry entry, Job next) {
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        if (agent == null || !JobProgressionPolicy.isLegalAdvancement(agent.getJob(), next, agent.getLevel())) {
            return false;
        }
        AgentStarterKitService.advanceJob(entry, next);
        return agent.getJob() == next;
    }

    /**
     * True when the installed rule names a path for {@code agent}: its owner is not asked to pick, and the rule hears
     * when the next step along it is legal.
     */
    static boolean selfDirected(Character agent) {
        Rule current = rule;
        if (current == null || agent == null) {
            return false;
        }
        Job path = current.path(agent.getId(), agent.getJob());
        if (path == null) {
            return false;
        }
        Job step = next(agent.getJob(), path, agent.getLevel());
        if (step != null) {
            current.due(agent.getId(), step);
        }
        return true;
    }
}
