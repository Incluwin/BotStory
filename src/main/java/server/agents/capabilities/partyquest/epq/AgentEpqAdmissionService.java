package server.agents.capabilities.partyquest.epq;

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
import server.agents.integration.AgentPartySnapshot;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.agents.runtime.simulation.AgentAbstractExecutionScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Transactional EPQ lobby-to-event handoff. */
public final class AgentEpqAdmissionService {
    private static final Logger log = LoggerFactory.getLogger(AgentEpqAdmissionService.class);

    private AgentEpqAdmissionService() { }

    /**
     * A host's entry of its own party: a human leader with his companions at Ellin, admitted as a HUMAN_ASSISTED
     * companion run (AgentEpqSession.companionAdopted) under a PRODUCTION engagement, by the stock event's party rule
     * (four to six adventurers) instead of the Agent rule. It builds the engagement and lobby the background director
     * would, then hands off at once; on any failure the engagement is recovered and nothing is left registered.
     *
     * <p>It runs the activity admission and every registry and lobby change on the calling thread. The GM command
     * '!epqtest party prod' calls it there, as '!hpqtest party prod' calls HPQ's admit; a host on an IO thread (the
     * Ellin desk and hook) must not call it directly but submit it to companion-ops or the Agent tick.
     */
    public static AdmissionResult admit(
            Character operator, Character eventLeader, List<Character> members, long seed, long nowMs) {
        Validation validation = validate(operator, eventLeader, members, true);
        if (!validation.success()) return AdmissionResult.failure(validation.message());
        String blocker = AgentEpqSessionRegistry.registrationBlocker(
                operator.getId(), validation.members().stream().map(Character::getId).toList());
        if (!blocker.isEmpty()) return AdmissionResult.failure("EPQ admission is already reserved: " + blocker);
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "epq", AgentPartyQuestEngagement.Mode.PRODUCTION, seed, operator.getId(),
                validation.members().size(), nowMs);
        AgentPartyQuestLobbySession lobby = null;
        try {
            AgentPartyQuestEngagementRegistry.register(engagement);
            for (Character agent : validation.agentMembers()) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry == null || !AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, agent,
                        "admitted to autonomous EPQ lobby", nowMs)) {
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
                    engagement.engagementId(), AgentEpqLobbyProfile.companionProfile(), seed,
                    operator.getId(), validation.members().size(),
                    AgentPartyQuestCandidateScope.ANY_ELIGIBLE_HUMAN, nowMs);
            for (Character member : validation.members()) {
                lobby.addMember(member.getId(), isAgent(member)
                                ? AgentPartyQuestLobbySession.MemberType.AGENT
                                : AgentPartyQuestLobbySession.MemberType.HUMAN,
                        member.getId() == eventLeader.getId() && isAgent(member)
                                ? AgentPartyQuestLobbySession.MemberRole.RECRUITING_LEADER
                                : AgentPartyQuestLobbySession.MemberRole.JOINED_MEMBER, nowMs);
            }
            lobby.setCoordinatorAgentId(validation.agentMembers().getFirst().getId());
            Set<Integer> roster = validation.members().stream()
                    .map(Character::getId).collect(java.util.stream.Collectors.toSet());
            lobby.reconcileParty(validation.party().id(), eventLeader.getId(), roster, nowMs);
            lobby.markReady(nowMs);
            engagement.beginLobby(lobby.lobbyId(), nowMs);
            engagement.lobbyReady(nowMs);
            AgentPartyQuestLobbyRuntime.register(lobby, nowMs);
            AdmissionResult result = admitFromLobby(
                    engagement, lobby, operator, eventLeader, validation.members(),
                    seed, nowMs, AgentEpqSession.Mode.HUMAN_ASSISTED, true);
            if (result.success()) return result;
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            engagement.beginRecovery(result.message(), nowMs);
            AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
            return new AdmissionResult(false, result.message(), null, engagement);
        } catch (RuntimeException failure) {
            if (lobby != null) AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            engagement.beginRecovery(failure.getMessage(), nowMs);
            AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
            return new AdmissionResult(false, failure.getMessage(), null, engagement);
        }
    }

    public static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            Character operator, Character eventLeader, List<Character> partyMembers,
            long seed, long nowMs, AgentEpqSession.Mode mode) {
        return admitFromLobby(engagement, lobby, operator, eventLeader, partyMembers, seed, nowMs, mode, false);
    }

    /**
     * Admits a formed lobby. {@code companionRun} is true only from {@link #admit}: the party is then checked by the
     * stock event's rule, and the session is flagged a companion run before it is published, so no tick or watchdog
     * ever sees it as a stock run.
     */
    static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            Character operator, Character eventLeader, List<Character> partyMembers,
            long seed, long nowMs, AgentEpqSession.Mode mode, boolean companionRun) {
        Validation validation = validate(operator, eventLeader, partyMembers, companionRun);
        if (!validation.success()) return AdmissionResult.failure(validation.message());
        if (engagement == null || lobby == null
                || !"epq".equals(lobby.profile().questKey())
                || !engagement.engagementId().equals(lobby.engagementId())
                || AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) {
            return AdmissionResult.failure("EPQ admission requires a registered matching EPQ lobby");
        }
        Set<Integer> expected = new LinkedHashSet<>(lobby.memberIds());
        Set<Integer> actual = validation.members().stream().map(Character::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!expected.equals(actual)) return AdmissionResult.failure("The party changed before EPQ entry");

        AgentEpqSession session = new AgentEpqSession(mode, seed, operator.getId(), nowMs);
        validation.members().forEach(member -> session.addMember(member.getId(), isAgent(member)
                ? AgentEpqMemberState.MemberType.AGENT : AgentEpqMemberState.MemberType.HUMAN));
        int execution = validation.members().stream().filter(AgentEpqAdmissionService::isAgent)
                .mapToInt(Character::getId).findFirst().orElseThrow();
        session.setLeadership(eventLeader.getId(), execution);
        if (companionRun) session.setCompanionAdopted(true);
        try {
            lobby.reserve(nowMs);
            engagement.reserveEntry(nowMs);
            AgentEpqSessionRegistry.registerComplete(session);
            lobby.beginHandoff(nowMs);
            engagement.activateSession(session.sessionId(), nowMs);
            validation.members().stream().filter(AgentEpqAdmissionService::isAgent).forEach(agent -> {
                var entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry != null) entry.simulationState()
                        .clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
            });
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            log.info("EPQ session admitted: session={} mode={} operator={} leader={} members={} agents={} "
                            + "companionRun={}", session.sessionId(), mode, operator.getId(), eventLeader.getId(),
                    validation.members().size(), validation.agentMembers().size(), companionRun);
            return new AdmissionResult(true, "EPQ party admitted", session, engagement);
        } catch (RuntimeException failure) {
            AgentEpqSessionRegistry.remove(session);
            return AdmissionResult.failure(failure.getMessage());
        }
    }

    public static Validation validate(Character operator, Character eventLeader, List<Character> partyMembers) {
        return validate(operator, eventLeader, partyMembers, false);
    }

    /**
     * {@code companionRun} replaces the Agent rule (exactly five, one per Explorer branch) with the stock event's own:
     * four to six members (EllinPQ.js). Every other check is the same, in the same order.
     */
    static Validation validate(Character operator, Character eventLeader, List<Character> partyMembers,
                               boolean companionRun) {
        if (operator == null || eventLeader == null || partyMembers == null) {
            return Validation.failure("Operator, leader, and party are required");
        }
        List<Character> unique = new ArrayList<>();
        unique.add(eventLeader);
        partyMembers.stream().filter(java.util.Objects::nonNull).forEach(member -> {
            if (unique.stream().noneMatch(existing -> existing.getId() == member.getId())) unique.add(member);
        });
        if (companionRun) {
            if (unique.size() < AgentEpqDefinition.MIN_PARTY_SIZE
                    || unique.size() > AgentEpqDefinition.MAX_PARTY_SIZE) {
                return Validation.failure("The Ellin Forest event accepts four to six members");
            }
        } else {
            AgentEpqRosterRequirementPolicy.Coverage coverage =
                    AgentEpqRosterRequirementPolicy.evaluate(unique);
            if (!coverage.complete()) return Validation.failure(
                    "Missing EPQ coverage: " + String.join(", ", coverage.missingRequirements()));
        }
        List<Character> agents = unique.stream().filter(AgentEpqAdmissionService::isAgent).toList();
        if (agents.isEmpty()) {
            return Validation.failure("EPQ Agent control requires at least one Agent");
        }
        AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(eventLeader);
        if (party == null) return Validation.failure("The EPQ leader has no party");
        Set<Integer> requested = unique.stream().map(Character::getId).collect(java.util.stream.Collectors.toSet());
        Set<Integer> online = party.members().stream().filter(java.util.Objects::nonNull)
                .map(server.agents.integration.AgentPartyMemberSnapshot::id).collect(java.util.stream.Collectors.toSet());
        if (!requested.equals(online)) return Validation.failure("Every EPQ member must be online");
        if (party.members().stream().noneMatch(member -> member != null
                && member.id() == eventLeader.getId() && member.leader())) {
            return Validation.failure("The selected EPQ event leader is not party leader");
        }
        int world = AgentClientGatewayRuntime.clients().world(eventLeader);
        int channel = AgentClientGatewayRuntime.clients().channel(eventLeader);
        for (Character member : unique) {
            // Stock order: the entrance, the level band and the adventurer rule, then world and channel, then the bag.
            String problem = placementProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
            if (AgentClientGatewayRuntime.clients().world(member) != world
                    || AgentClientGatewayRuntime.clients().channel(member) != channel) {
                return Validation.failure("Every EPQ member must share world/channel");
            }
            problem = capacityProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
        }
        return new Validation(true, "", List.copyOf(unique), agents, party);
    }

    /**
     * Why this party member cannot enter EPQ from Ellin, in admission's own words, or "" when it can. A host can check
     * its lineup with it before admitting it.
     */
    public static String memberProblem(Character member) {
        String problem = placementProblem(member);
        return problem.isEmpty() ? capacityProblem(member) : problem;
    }

    private static String placementProblem(Character member) {
        if (member.getMapId() != AgentEpqDefinition.RECRUIT_MAP) {
            return member.getName() + " is not at EPQ";
        }
        if (member.getLevel() < AgentEpqDefinition.MIN_LEVEL || member.getLevel() > AgentEpqDefinition.MAX_LEVEL) {
            return member.getName() + " must be level 44-55";
        }
        if (member.getJob() == null || member.getJob().getId() >= 1_000) {
            return member.getName() + " must be an Adventurer";
        }
        return "";
    }

    private static String capacityProblem(Character member) {
        if (member.getInventory(InventoryType.ETC).getNumFreeSlot() < 4
                || member.getInventory(InventoryType.USE).getNumFreeSlot() < 1) {
            return member.getName() + " needs four ETC slots and one USE slot";
        }
        return "";
    }

    private static boolean isAgent(Character character) {
        return AgentRuntimeRegistry.findByAgentCharacterId(character.getId()) != null;
    }
    public record Validation(boolean success, String message, List<Character> members,
                             List<Character> agentMembers, AgentPartySnapshot party) {
        static Validation failure(String message) { return new Validation(false, message, List.of(), List.of(), null); }
    }
    public record AdmissionResult(boolean success, String message, AgentEpqSession session,
                                  AgentPartyQuestEngagement engagement) {
        static AdmissionResult failure(String message) { return new AdmissionResult(false, message, null, null); }
    }
}
