package server.agents.capabilities.build;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mutable runtime state for AP/SP build and job-advancement prompting.
 */
public final class AgentBuildState {
    private AgentBuildService.ApBuild apBuild = null;
    private boolean apPromptSent = false;
    private String spVariant = null;
    private boolean spVariantPromptSent = false;
    private int jobPromptSent = 0;
    private int lastKnownLevel = -1;
    private long nextBookCheckAt = 0L;
    private final Set<Integer> failedBookSkills = new LinkedHashSet<>();
    private final Set<Integer> bookSkillsNamed = new HashSet<>();

    public AgentBuildService.ApBuild apBuild() {
        return apBuild;
    }

    public boolean hasApBuild() {
        return apBuild != null;
    }

    public void setApBuild(AgentBuildService.ApBuild apBuild) {
        this.apBuild = apBuild;
        this.apPromptSent = false;
    }

    public void clearApBuildPromptState() {
        apBuild = null;
        apPromptSent = false;
    }

    public boolean apPromptSent() {
        return apPromptSent;
    }

    public void markApPromptSent() {
        this.apPromptSent = true;
    }

    public String spVariant() {
        return spVariant;
    }

    public boolean hasSpVariant() {
        return spVariant != null;
    }

    public void setSpVariant(String spVariant) {
        this.spVariant = spVariant;
    }

    public boolean spVariantPromptSent() {
        return spVariantPromptSent;
    }

    public void markSpVariantPromptSent() {
        this.spVariantPromptSent = true;
    }

    public int jobPromptSent() {
        return jobPromptSent;
    }

    public void setJobPromptSent(int jobPromptSent) {
        this.jobPromptSent = jobPromptSent;
    }

    public int lastKnownLevel() {
        return lastKnownLevel;
    }

    public void setLastKnownLevel(int lastKnownLevel) {
        this.lastKnownLevel = lastKnownLevel;
    }

    /** When the Agent next looks through its Use tab for a book it can read (AgentMasteryBooks.tick). */
    public long nextBookCheckAt() {
        return nextBookCheckAt;
    }

    public void setNextBookCheckAt(long nextBookCheckAt) {
        this.nextBookCheckAt = nextBookCheckAt;
    }

    /** Skills whose book failed its roll since the Agent last said so (AgentBookSpeech), in the order they failed. */
    public Set<Integer> failedBookSkills() {
        return failedBookSkills;
    }

    /** Skills it has named this session in a book line (AgentBookSpeech), so a waiting book is mentioned once. */
    public Set<Integer> bookSkillsNamed() {
        return bookSkillsNamed;
    }
}
