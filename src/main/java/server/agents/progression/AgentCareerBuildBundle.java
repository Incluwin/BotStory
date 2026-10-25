package server.agents.progression;

import java.util.List;

/**
 * Versioned career policy selected once for an Agent and restored on every session. An Explorer bundle names its
 * instructor's Power B. Fore training quests (Victoria's first-job journey); a Cygnus Knight or Aran bundle has none.
 */
public record AgentCareerBuildBundle(
        String bundleId,
        int bundleVersion,
        String career,
        int firstJobId,
        int advancementLevel,
        String apProfileId,
        String spProfileId,
        int instructorNpcId,
        int instructorMapId,
        List<Integer> instructorTrainingQuestIds,
        int milestoneLevel) {

    public AgentCareerBuildBundle {
        if (bundleId == null || bundleId.isBlank() || bundleVersion <= 0
                || career == null || career.isBlank() || firstJobId <= 0
                || advancementLevel < 8 || apProfileId == null || apProfileId.isBlank()
                || spProfileId == null || spProfileId.isBlank() || instructorNpcId <= 0
                || instructorMapId <= 0 || instructorTrainingQuestIds == null
                || (instructorTrainingQuestIds.isEmpty() && explorer(firstJobId)) || milestoneLevel < advancementLevel) {
            throw new IllegalArgumentException("valid career build bundle fields are required");
        }
        instructorTrainingQuestIds = List.copyOf(instructorTrainingQuestIds);
    }

    /** An Explorer first job (Victoria's first-job journey and catalog); a Cygnus Knight or Aran bundle is not. */
    public boolean explorer() {
        return explorer(firstJobId);
    }

    private static boolean explorer(int firstJobId) {
        return firstJobId < 1_000;
    }
}
