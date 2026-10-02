package server.agents.capabilities.partyquest.pace;

/**
 * One recorded run as the calibration sees it: {@code ageRank} 0 is the newest run of the quest, and {@code epoch} the
 * pace epoch it was recorded under. The theory a run is compared with is recomputed from {@code pace.members()}, so a
 * model change re-reads old runs without rewriting them.
 */
public record AgentPqPaceObservation(int ageRank, int epoch, AgentPqPace pace) {
    public AgentPqPaceObservation {
        if (ageRank < 0) throw new IllegalArgumentException("age rank must be >= 0");
        if (pace == null) throw new IllegalArgumentException("a recorded run is required");
    }
}
