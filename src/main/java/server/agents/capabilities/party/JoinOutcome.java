package server.agents.capabilities.party;

/**
 * What joining an Agent to its leader's party did ({@link AgentPartyLifecycleService}): only JOINED and
 * ALREADY_MEMBER leave the Agent in that party.
 */
public enum JoinOutcome {
    JOINED,
    ALREADY_MEMBER,
    /** The leader's party already has six members. */
    PARTY_FULL,
    /** The leader has no party and one could not be created (a leader below level 10, for example). */
    NO_PARTY,
    FAILED
}
