package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestCandidateScope;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbySession;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.agents.runtime.simulation.AgentAbstractExecutionScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one way an RJPQ run is made: a human leader's own party of four on Romeo's or Juliet's hidden room, admitted as a
 * companion run under a PRODUCTION engagement, its lobby built and handed off at once. On any failure the engagement is
 * recovered and nothing is left registered.
 *
 * <p>It runs on the calling thread: '!rjpqtest party prod' calls it there; a host on an IO thread (the desk) submits it
 * to companion-ops or the Agent tick.
 */
public final class AgentRjpqAdmissionService {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqAdmissionService.class);
    /** The claims at the end need room: Romeo's marble (ETC) and 2112018's reward (EQP or USE). */
    public static final int REQUIRED_FREE_ETC_SLOTS = 2;
    public static final int REQUIRED_FREE_EQUIP_SLOTS = 1;
    public static final int REQUIRED_FREE_USE_SLOTS = 1;

    private AgentRjpqAdmissionService() {
    }

    public static AdmissionResult admit(
            Character operator, Character eventLeader, List<Character> members, long seed, long nowMs) {
        AgentRjpqSide side = eventLeader == null ? null : AgentRjpqSide.fromRecruitMap(eventLeader.getMapId());
        if (side == null) {
            return AdmissionResult.failure("Stand beside Romeo or Juliet first.");
        }
        Validation validation = validate(side, operator, eventLeader, members);
        if (!validation.success()) {
            return AdmissionResult.failure(validation.message());
        }
        String blocker = AgentRjpqSessionRegistry.registrationBlocker(
                operator.getId(), validation.members().stream().map(Character::getId).toList());
        if (!blocker.isEmpty()) {
            return AdmissionResult.failure("RJPQ admission is already reserved: " + blocker);
        }
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                side.key(), AgentPartyQuestEngagement.Mode.PRODUCTION, seed, operator.getId(),
                validation.members().size(), nowMs);
        AgentPartyQuestLobbySession lobby = null;
        try {
            AgentPartyQuestEngagementRegistry.register(engagement);
            for (Character agent : validation.agentMembers()) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry == null || !AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, agent,
                        "admitted to a companion RJPQ run", nowMs)) {
                    String reason = agent.getName() + " could not leave its current activity";
                    engagement.beginRecovery(reason, nowMs);
                    AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
                    return new AdmissionResult(false, reason, null, engagement);
                }
                AgentPartyQuestEngagementRegistry.addAndIndexMember(
                        engagement, agent.getId(), AgentPartyQuestEngagement.MemberType.AGENT, nowMs);
            }
            for (Character member : validation.members()) {
                if (!isAgent(member)) {
                    AgentPartyQuestEngagementRegistry.addAndIndexMember(
                            engagement, member.getId(), AgentPartyQuestEngagement.MemberType.HUMAN, nowMs);
                }
            }
            lobby = new AgentPartyQuestLobbySession(
                    engagement.engagementId(), AgentRjpqLobbyProfile.profile(side), seed,
                    operator.getId(), validation.members().size(),
                    AgentPartyQuestCandidateScope.ANY_ELIGIBLE_HUMAN, nowMs);
            for (Character member : validation.members()) {
                lobby.addMember(member.getId(), isAgent(member)
                                ? AgentPartyQuestLobbySession.MemberType.AGENT
                                : AgentPartyQuestLobbySession.MemberType.HUMAN,
                        AgentPartyQuestLobbySession.MemberRole.JOINED_MEMBER, nowMs);
            }
            lobby.setCoordinatorAgentId(validation.agentMembers().getFirst().getId());
            Set<Integer> roster = validation.members().stream().map(Character::getId).collect(Collectors.toSet());
            lobby.reconcileParty(validation.party().id(), eventLeader.getId(), roster, nowMs);
            lobby.markReady(nowMs);
            engagement.beginLobby(lobby.lobbyId(), nowMs);
            engagement.lobbyReady(nowMs);
            AgentPartyQuestLobbyRuntime.register(lobby, nowMs);
            AdmissionResult result = admitFromLobby(
                    side, engagement, lobby, operator, eventLeader, validation, seed, nowMs);
            if (result.success()) {
                return result;
            }
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            engagement.beginRecovery(result.message(), nowMs);
            AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
            return new AdmissionResult(false, result.message(), null, engagement);
        } catch (RuntimeException failure) {
            if (lobby != null) {
                AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            }
            engagement.beginRecovery(failure.getMessage(), nowMs);
            AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
            return new AdmissionResult(false, failure.getMessage(), null, engagement);
        }
    }

    /**
     * Publishes the session behind the formed lobby. It is a companion run from the start: the flag is set before the
     * session is registered, so no tick ever sees it otherwise.
     */
    private static AdmissionResult admitFromLobby(AgentRjpqSide side, AgentPartyQuestEngagement engagement,
                                                  AgentPartyQuestLobbySession lobby, Character operator,
                                                  Character eventLeader, Validation validation, long seed,
                                                  long nowMs) {
        Set<Integer> expected = new LinkedHashSet<>(lobby.memberIds());
        Set<Integer> actual = validation.members().stream().map(Character::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!expected.equals(actual)) {
            return AdmissionResult.failure("The party changed before RJPQ entry");
        }
        AgentRjpqSession session = new AgentRjpqSession(side, seed, operator.getId(), nowMs);
        validation.members().forEach(member -> session.addMember(member.getId(), isAgent(member)
                ? AgentRjpqMemberState.MemberType.AGENT : AgentRjpqMemberState.MemberType.HUMAN));
        session.setLeadership(eventLeader.getId(), validation.agentMembers().getFirst().getId());
        session.setCompanionAdopted(true);
        try {
            lobby.reserve(nowMs);
            engagement.reserveEntry(nowMs);
            AgentRjpqSessionRegistry.registerComplete(session);
            AgentRjpqWatchdogRuntime.ensureStarted();
            lobby.beginHandoff(nowMs);
            engagement.activateSession(session.sessionId(), nowMs);
            for (Character agent : validation.agentMembers()) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry != null) {
                    entry.simulationState().clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
                }
            }
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            log.info("RJPQ session admitted: session={} side={} operator={} leader={} members={} agents={} "
                            + "companionRun=true", session.sessionId(), side.key(), operator.getId(),
                    eventLeader.getId(), validation.members().size(), validation.agentMembers().size());
            return new AdmissionResult(true, "RJPQ party admitted", session, engagement);
        } catch (RuntimeException failure) {
            AgentRjpqSessionRegistry.remove(session);
            return AdmissionResult.failure(failure.getMessage());
        }
    }

    /** The stock event's rule (exactly four, all in band beside the side's NPC), then the companions' bag room. */
    static Validation validate(AgentRjpqSide side, Character operator, Character eventLeader,
                               List<Character> partyMembers) {
        if (operator == null || eventLeader == null || partyMembers == null) {
            return Validation.failure("Operator, leader, and party are required");
        }
        List<Character> unique = new ArrayList<>();
        unique.add(eventLeader);
        partyMembers.stream().filter(Objects::nonNull).forEach(member -> {
            if (unique.stream().noneMatch(existing -> existing.getId() == member.getId())) {
                unique.add(member);
            }
        });
        if (unique.size() != AgentRjpqSide.PARTY_SIZE) {
            return Validation.failure("Romeo and Juliet takes exactly four");
        }
        List<Character> agents = unique.stream().filter(AgentRjpqAdmissionService::isAgent).toList();
        if (agents.isEmpty()) {
            return Validation.failure("An RJPQ companion run needs at least one companion");
        }
        AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(eventLeader);
        if (party == null) {
            return Validation.failure("The RJPQ leader has no party");
        }
        Set<Integer> requested = unique.stream().map(Character::getId).collect(Collectors.toSet());
        Set<Integer> online = party.members().stream().filter(Objects::nonNull)
                .map(AgentPartyMemberSnapshot::id).collect(Collectors.toSet());
        if (!requested.equals(online)) {
            return Validation.failure("Every RJPQ member must be online in the party");
        }
        if (party.members().stream().noneMatch(member -> member != null
                && member.id() == eventLeader.getId() && member.leader())) {
            return Validation.failure("The selected RJPQ event leader is not party leader");
        }
        int world = AgentClientGatewayRuntime.clients().world(eventLeader);
        int channel = AgentClientGatewayRuntime.clients().channel(eventLeader);
        for (Character member : unique) {
            String problem = placementProblem(side, member);
            if (!problem.isEmpty()) {
                return Validation.failure(problem);
            }
            if (AgentClientGatewayRuntime.clients().world(member) != world
                    || AgentClientGatewayRuntime.clients().channel(member) != channel) {
                return Validation.failure("Every RJPQ member must share world/channel");
            }
        }
        for (Character agent : agents) {
            String problem = roomProblem(agent);
            if (!problem.isEmpty()) {
                return Validation.failure(problem);
            }
        }
        return new Validation(true, "", List.copyOf(unique), agents, party);
    }

    /**
     * Why this party member cannot enter RJPQ from its side's hidden room, in admission's own words, or "" when it can.
     * A host checks its lineup with it before admitting.
     */
    public static String memberProblem(Character member) {
        if (member == null) {
            return "";
        }
        AgentRjpqSide side = AgentRjpqSide.fromRecruitMap(member.getMapId());
        return placementProblem(side == null ? AgentRjpqSide.ZENUMIST : side, member);
    }

    /** What keeps {@code member} out of a run on {@code side}'s desk, in that NPC's words; "" when nothing does. */
    public static String memberProblem(AgentRjpqSide side, Character member) {
        if (member == null || side == null) {
            return "";
        }
        return placementProblem(side, member);
    }

    private static String placementProblem(AgentRjpqSide side, Character member) {
        if (member.getMapId() != side.recruitMapId()) {
            return member.getName() + " is not beside " + (side == AgentRjpqSide.ZENUMIST ? "Romeo." : "Juliet.");
        }
        if (member.getLevel() < AgentRjpqSide.MIN_LEVEL || member.getLevel() > AgentRjpqSide.MAX_LEVEL) {
            return member.getName() + " must be level 71 to 85.";
        }
        return "";
    }

    /** A companion keeps room for the claims: the marble and 2112018's reward. */
    private static String roomProblem(Character agent) {
        if (agent.getInventory(InventoryType.ETC).getNumFreeSlot() < REQUIRED_FREE_ETC_SLOTS
                || agent.getInventory(InventoryType.EQUIP).getNumFreeSlot() < REQUIRED_FREE_EQUIP_SLOTS
                || agent.getInventory(InventoryType.USE).getNumFreeSlot() < REQUIRED_FREE_USE_SLOTS) {
            return agent.getName() + " needs room in the bag first.";
        }
        return "";
    }

    private static boolean isAgent(Character character) {
        return AgentRuntimeRegistry.findByAgentCharacterId(character.getId()) != null;
    }

    public record Validation(boolean success, String message, List<Character> members,
                             List<Character> agentMembers, AgentPartySnapshot party) {
        static Validation failure(String message) {
            return new Validation(false, message, List.of(), List.of(), null);
        }
    }

    public record AdmissionResult(boolean success, String message, AgentRjpqSession session,
                                  AgentPartyQuestEngagement engagement) {
        static AdmissionResult failure(String message) {
            return new AdmissionResult(false, message, null, null);
        }
    }
}
