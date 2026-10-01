package server.agents.progression;

/**
 * A one-time AP reset owed to an Agent whose career bundle re-pick changed its AP profile: its stats
 * came from a bundle that never fit its job. {@code completedAtMs} is 0 until the reset has run.
 */
public record AgentApReset(
        int schemaVersion,
        int characterId,
        String characterName,
        String fromBundleId,
        String toBundleId,
        long requestedAtMs,
        long completedAtMs) {

    public AgentApReset {
        if (schemaVersion <= 0 || characterId <= 0 || characterName == null
                || fromBundleId == null || fromBundleId.isBlank() || toBundleId == null || toBundleId.isBlank()
                || requestedAtMs < 0 || completedAtMs < 0) {
            throw new IllegalArgumentException("valid AP reset fields are required");
        }
    }

    public boolean completed() {
        return completedAtMs > 0;
    }

    AgentApReset completedAt(long nowMs) {
        return new AgentApReset(schemaVersion, characterId, characterName, fromBundleId, toBundleId,
                requestedAtMs, Math.max(1L, nowMs));
    }
}
