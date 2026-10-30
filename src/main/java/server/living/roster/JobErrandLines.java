package server.living.roster;

import client.Job;
import constants.game.GameConstants;
import server.agents.capabilities.dialogue.AgentDialogueReportFormatter;

import java.util.List;
import java.util.Locale;

/**
 * What a companion says about going for its next job when its persona has no lines of its own for it ("jobErrand",
 * "jobBack", "jobThanks"): in a player's words, never the system's. {tier}, {instructor}, {town} and {job} are filled in
 * (lower case, as typed in chat).
 */
public final class JobErrandLines {
    /** Leaving its owner for its instructor. */
    static final List<String> GO = List.of(
            "{tier} job time! off to see {instructor} in {town}. back soon",
            "gotta go see {instructor} about my {tier} job. brb",
            "{instructor} wants to see me. {tier} job! be right back");
    /** Back with its owner after it advanced. */
    static final List<String> BACK = List.of(
            "back! {job} now",
            "made it back. {job} now :)",
            "back. {instructor} signed off on it, {job} now");
    /** To its instructor, when a player is there to hear it. */
    static final List<String> THANKS = List.of("thank you!", "thanks. i won't let you down.");

    private JobErrandLines() {
    }

    public static String fill(String line, Job job, JobInstructors.Instructor instructor) {
        return line.replace("{tier}", JobInstructors.tier(job))
                .replace("{instructor}", instructor.name().toLowerCase(Locale.ROOT))
                .replace("{town}", instructor.town().toLowerCase(Locale.ROOT))
                .replace("{job}", jobName(job));
    }

    /** A Cygnus Knight or an Aran keeps its class name from job to job, so a player says which: "2nd job aran". */
    private static String jobName(Job job) {
        String name = AgentDialogueReportFormatter.jobDisplayName(job);
        boolean sameName = GameConstants.isCygnus(job.getId()) || GameConstants.isAran(job.getId());
        return sameName && GameConstants.getJobBranch(job) >= 2 ? JobInstructors.tier(job) + " job " + name : name;
    }
}
