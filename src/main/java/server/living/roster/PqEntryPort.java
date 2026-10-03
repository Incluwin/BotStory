package server.living.roster;

import client.Character;
import scripting.event.EventManager;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.agents.capabilities.partyquest.hpq.AgentHpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.agents.runtime.activity.session.AgentActivityKind;

import java.util.List;

/**
 * The doors a companion lineup goes through into its party quest (P6, flow 3; keyed by quest in P6b HPQ step 5): the
 * Agent layer's admission, the quest's own start (what the entry NPC's stock click does), the Agent layer's stop when
 * the start fails after admission, and whether a companion admission asked to stop what it was doing is at it still.
 */
interface PqEntryPort {
    /** How the quest's start went, as its entry NPC's script tells them apart. */
    enum Start { STARTED, NOT_ELIGIBLE, NO_LOBBY, NO_EVENT }

    /**
     * How admission went: on success the quest's session, as the host steers it ({@code session}); else why not, in
     * admission's own words ({@code message}).
     */
    record Admitted(boolean success, String message, AgentCompanionRunSession session) { }

    Admitted admit(String pqKey, Character leader, List<Character> members, long seed, long nowMs);

    /** True while {@code pqKey}'s registry still holds the companion in the session (it was not released meanwhile). */
    default boolean holds(String pqKey, int agentId, String sessionId) {
        return PqEntrance.of(pqKey).holds(agentId, sessionId);
    }

    Start startInstance(String pqKey, Character leader);

    void forceStop(int agentId, String reason, long nowMs);

    /** True while the companion is still on an activity other than a party quest (admission asked it to stop). */
    boolean finishing(int agentId);

    /** The live doors: PRODUCTION admission with the leader as operator, the stock event start, the Agent stop. */
    PqEntryPort STOCK = new PqEntryPort() {
        @Override
        public Admitted admit(String pqKey, Character leader, List<Character> members, long seed, long nowMs) {
            return switch (PqEntrance.of(pqKey).key()) {
                case "kpq" -> {
                    AgentKpqAdmissionService.AdmissionResult admitted =
                            AgentKpqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    yield new Admitted(admitted.success(), admitted.message(), admitted.session());
                }
                case "hpq" -> {
                    AgentHpqAdmissionService.AdmissionResult admitted =
                            AgentHpqAdmissionService.admit(leader, leader, members, seed, nowMs);
                    yield new Admitted(admitted.success(), admitted.message(), admitted.session());
                }
                default -> throw new IllegalArgumentException("no companion admission for " + pqKey);
            };
        }

        /** The quest's event (KerningPQ, HenesysPQ); waits up to 7777 ms for a free lobby, as the stock click does. */
        @Override
        public Start startInstance(String pqKey, Character leader) {
            EventManager em = leader.getClient().getEventManager(PqEntrance.of(pqKey).definition().eventManagerName());
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

        /** Its primary activities but a party quest: only those finish their step when admission asks them to stop. */
        @Override
        public boolean finishing(int agentId) {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agentId);
            Character agent = AgentRuntimeIdentityRuntime.bot(entry);
            if (agent == null) {
                return false;
            }
            for (AgentActivityKind kind : AgentActivityKind.values()) {
                if (kind != AgentActivityKind.PARTY_QUEST && AgentActivityBootstrap.primaryActive(entry, agent, kind)) {
                    return true;
                }
            }
            return false;
        }
    };
}
