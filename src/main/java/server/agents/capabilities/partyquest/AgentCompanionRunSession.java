package server.agents.capabilities.partyquest;

import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;

/**
 * What a companion host holds of a party-quest session it admitted for a human leader and his companions, whichever
 * quest it is (KPQ, HPQ). Every quest keeps its own state machine; this is only the handle the host steers it by.
 */
public interface AgentCompanionRunSession {
    String sessionId();

    /** The run's event leader as the session has it now (the lead may pass inside the run). */
    int eventLeaderId();

    /** True for a human-led run of owner-bound companions; its quest's companion play applies only then. */
    boolean companionAdopted();

    /** Lets the companions coordinate the run aloud in map chat. */
    void setMemberCoordinationChatEnabled(boolean enabled);

    /** The leader's clear-time calibration, for a quest that paces its run by it; a quest that does not ignores it. */
    void setPaceCalibration(AgentPqPaceCalibration calibration);
}
