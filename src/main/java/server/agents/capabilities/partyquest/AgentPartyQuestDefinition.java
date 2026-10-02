package server.agents.capabilities.partyquest;

/**
 * Immutable server-content contract used by shared PQ routing and recovery.
 *
 * @param eventTimerMs the event script's timer (its {@code eventTime} in minutes x 60000), 0 when not recorded here;
 *                     the clear-time estimate weighs a lineup against it
 */
public record AgentPartyQuestDefinition(
        String questKey,
        String eventManagerName,
        int recruitMapId,
        int entryMapId,
        int clearMapId,
        int exitMapId,
        int recoveryMapId,
        int minimumLevel,
        int maximumLevel,
        int minimumPartySize,
        int maximumPartySize,
        long eventTimerMs) {

    public AgentPartyQuestDefinition {
        questKey = normalize(questKey);
        if (eventManagerName == null || eventManagerName.isBlank()
                || recruitMapId <= 0 || entryMapId <= 0 || clearMapId <= 0
                || exitMapId <= 0 || recoveryMapId <= 0
                || minimumLevel < 1 || maximumLevel < minimumLevel
                || minimumPartySize < 2 || maximumPartySize < minimumPartySize || eventTimerMs < 0L) {
            throw new IllegalArgumentException("valid party-quest definition values are required");
        }
        eventManagerName = eventManagerName.trim();
    }

    /** A definition whose event timer is not recorded here (0). */
    public AgentPartyQuestDefinition(String questKey, String eventManagerName, int recruitMapId, int entryMapId,
                                     int clearMapId, int exitMapId, int recoveryMapId, int minimumLevel,
                                     int maximumLevel, int minimumPartySize, int maximumPartySize) {
        this(questKey, eventManagerName, recruitMapId, entryMapId, clearMapId, exitMapId, recoveryMapId, minimumLevel,
                maximumLevel, minimumPartySize, maximumPartySize, 0L);
    }

    public boolean acceptsPartySize(int partySize) {
        return partySize >= minimumPartySize && partySize <= maximumPartySize;
    }

    public boolean acceptsLevel(int level) {
        return level >= minimumLevel && level <= maximumLevel;
    }

    public static String normalize(String questKey) {
        String normalized = questKey == null ? "" : questKey.trim().toLowerCase();
        if (normalized.isEmpty()) throw new IllegalArgumentException("party-quest key is required");
        return normalized;
    }
}
