package net.server.coordinator.partysearch;

import client.Job;

import java.util.Set;

/**
 * What a leader's Party Search asks for: a level range and the job groups ticked in the search window. A character
 * matches when its level is in the range and its job's group (as the search files it: a 4th-job Hero under Fighter) is
 * one of them. Equal wants are the same search.
 */
public record PartySearchWant(int minLevel, int maxLevel, Set<Job> jobGroups) {
    public PartySearchWant {
        jobGroups = Set.copyOf(jobGroups);
    }

    public boolean wants(int level, Job job) {
        return job != null && level >= minLevel && level <= maxLevel
                && jobGroups.contains(PartySearchCoordinator.getPartySearchJob(job));
    }
}
