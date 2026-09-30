package server.living.roster;

import client.Character;
import scripting.event.EventManager;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;

import java.util.List;

/**
 * The doors a companion lineup goes through into KPQ (P6, flow 3): the Agent layer's admission, KerningPQ's own start
 * (what Lakelis's stock click does), and the Agent layer's stop when the start fails after admission.
 */
interface PqEntryPort {
    /** How KerningPQ's start went, as 9020000.js tells them apart. */
    enum Start { STARTED, NOT_ELIGIBLE, NO_LOBBY, NO_EVENT }

    AgentKpqAdmissionService.AdmissionResult admit(Character leader, List<Character> members, long seed, long nowMs);

    Start startInstance(Character leader);

    void forceStop(int agentId, String reason, long nowMs);

    /** The live doors: PRODUCTION admission with the leader as operator, the stock event start, the Agent stop. */
    PqEntryPort STOCK = new PqEntryPort() {
        @Override
        public AgentKpqAdmissionService.AdmissionResult admit(Character leader, List<Character> members, long seed,
                                                              long nowMs) {
            return AgentKpqAdmissionService.admit(leader, leader, members, seed, nowMs);
        }

        /** Waits up to 7777 ms for a free lobby, exactly as the stock click does. */
        @Override
        public Start startInstance(Character leader) {
            EventManager em = leader.getClient().getEventManager("KerningPQ");
            if (em == null) {
                return Start.NO_EVENT;
            }
            if (em.getEligibleParty(leader.getParty()).isEmpty()) {
                return Start.NOT_ELIGIBLE;
            }
            return em.startInstance(leader.getParty(), leader.getMap(), 1) ? Start.STARTED : Start.NO_LOBBY;
        }

        @Override
        public void forceStop(int agentId, String reason, long nowMs) {
            AgentPartyQuestRuntime.forceStop(agentId, reason, nowMs);
        }
    };
}
