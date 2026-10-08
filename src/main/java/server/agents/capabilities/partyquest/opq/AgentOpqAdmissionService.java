package server.agents.capabilities.partyquest.opq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
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

/** Transactional shared-lobby handoff into an OPQ session: six Agents, or a human's companion run of five or six. */
public final class AgentOpqAdmissionService {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqAdmissionService.class);

    private AgentOpqAdmissionService() { }

    /**
     * A host's entry of its own party: a human leader with his companions at Wonky, admitted as a companion run
     * (AgentOpqSession.companionAdopted) under a PRODUCTION engagement, by the stock event's party rule (five or six
     * members, OrbisPQ.js) instead of the Agent rule. It builds the engagement and lobby the background director
     * would, then hands off at once; on any failure the engagement is recovered and nothing is left registered.
     *
     * <p>It runs the activity admission and every registry and lobby change on the calling thread. The GM command
     * '!opqtest party prod' calls it there; a host on an IO thread must not call it directly but submit it to
     * companion-ops or the Agent tick.
     */
    public static AdmissionResult admit(
            Character operator, Character eventLeader, List<Character> members, long seed, long nowMs) {
        Validation validation = validateCompanion(operator, eventLeader, members);
        if (!validation.success()) return AdmissionResult.failure(validation.message());
        String blocker = AgentOpqSessionRegistry.registrationBlocker(
                operator.getId(), validation.members().stream().map(Character::getId).toList());
        if (!blocker.isEmpty()) return AdmissionResult.failure("OPQ is already reserved: " + blocker);
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "opq", AgentPartyQuestEngagement.Mode.PRODUCTION, seed, operator.getId(),
                validation.members().size(), nowMs);
        AgentPartyQuestLobbySession lobby = null;
        try {
            AgentPartyQuestEngagementRegistry.register(engagement);
            for (Character agent : validation.agentMembers()) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry == null || !AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, agent,
                        "admitted to autonomous OPQ lobby", nowMs)) {
                    String reason = agent.getName() + " could not leave its current activity";
                    engagement.beginRecovery(reason, nowMs);
                    AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
                    return AdmissionResult.failure(reason, engagement);
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
                    engagement.engagementId(), AgentOpqLobbyProfile.companionProfile(), seed,
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
                    engagement, lobby, operator, eventLeader, validation.members(), seed, nowMs,
                    isAgent(eventLeader) ? AgentOpqSession.Mode.AUTONOMOUS : AgentOpqSession.Mode.HUMAN_ASSISTED,
                    true);
            if (result.success()) return result;
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            engagement.beginRecovery(result.message(), nowMs);
            AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
            return AdmissionResult.failure(result.message(), engagement);
        } catch (RuntimeException failure) {
            if (lobby != null) AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            engagement.beginRecovery(failure.getMessage(), nowMs);
            AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
            return AdmissionResult.failure(failure.getMessage(), engagement);
        }
    }

    public static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            Character operator, Character eventLeader, List<Character> partyMembers,
            long seed, long nowMs, AgentOpqSession.Mode mode) {
        return admitFromLobby(engagement, lobby, operator, eventLeader, partyMembers, seed, nowMs, mode, false);
    }

    /**
     * Admits a formed lobby. {@code companionRun} is true only from {@link #admit}: the party is then checked by the
     * stock event's rule, the session is opened for the party's own size, and it is flagged a companion run before it
     * is published, so no tick ever sees it as a stock run.
     */
    static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            Character operator, Character eventLeader, List<Character> partyMembers,
            long seed, long nowMs, AgentOpqSession.Mode mode, boolean companionRun) {
        Validation validation = companionRun
                ? validateCompanion(operator, eventLeader, partyMembers)
                : validate(operator, eventLeader, partyMembers);
        if (!validation.success()) return AdmissionResult.failure(validation.message());
        if (engagement == null || lobby == null
                || !engagement.engagementId().equals(lobby.engagementId())
                || AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) {
            return AdmissionResult.failure("OPQ admission requires a registered matching lobby engagement");
        }
        Set<Integer> expected = new LinkedHashSet<>(lobby.memberIds());
        Set<Integer> actual = validation.members().stream().map(Character::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!expected.equals(actual)) return AdmissionResult.failure("The party changed before OPQ entry");
        AgentOpqSession session = companionRun
                ? new AgentOpqSession(mode, seed, operator.getId(), validation.members().size(), nowMs)
                : new AgentOpqSession(mode, seed, operator.getId(), nowMs);
        validation.members().forEach(member -> session.addMember(member.getId(), isAgent(member)
                ? AgentOpqMemberState.MemberType.AGENT : AgentOpqMemberState.MemberType.HUMAN));
        int execution = validation.members().stream().filter(AgentOpqAdmissionService::isAgent)
                .mapToInt(Character::getId).findFirst().orElseThrow();
        session.setLeadership(eventLeader.getId(), execution);
        if (companionRun) session.setCompanionAdopted(true);
        try {
            lobby.reserve(nowMs);
            engagement.reserveEntry(nowMs);
            AgentOpqSessionRegistry.registerComplete(session);
            lobby.beginHandoff(nowMs);
            engagement.activateSession(session.sessionId(), nowMs);
            if (companionRun) {
                // A companion leaves its town life for the run (as LMPQ and EPQ admit theirs).
                validation.agentMembers().forEach(agent -> {
                    AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                    if (entry != null) entry.simulationState()
                            .clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
                });
            }
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            log.info("OPQ session admitted: session={} mode={} operator={} leader={} members={} agents={} "
                            + "companionRun={}", session.sessionId(), mode, operator.getId(), eventLeader.getId(),
                    validation.members().size(), validation.agentMembers().size(), companionRun);
            return new AdmissionResult(true, "OPQ party admitted", session, engagement);
        } catch (RuntimeException failure) {
            AgentOpqSessionRegistry.remove(session);
            return AdmissionResult.failure(failure.getMessage());
        }
    }

    public static Validation validate(Character operator, Character eventLeader, List<Character> partyMembers) {
        if (operator == null || eventLeader == null || partyMembers == null) return Validation.failure("Operator, leader, and party are required");
        List<Character> unique = leaderFirst(eventLeader, partyMembers);
        AgentOpqRosterRequirementPolicy.Coverage coverage = AgentOpqRosterRequirementPolicy.evaluate(unique);
        if (!coverage.complete()) return Validation.failure("Missing OPQ coverage: " + String.join(", ", coverage.missingRequirements()));
        return partyChecks(eventLeader, unique, true);
    }

    /**
     * A companion run's party, by the stock event's rule (OrbisPQ.js): five or six members at Wonky, level 51-70, on
     * the leader's world and channel, with at least one Agent, and no branch rule. The Agents need eight free ETC
     * slots for the run's pieces; a human brings his own bag, as stock asks nothing of it. Every other check is the
     * stock one, in its order.
     */
    static Validation validateCompanion(Character operator, Character eventLeader, List<Character> partyMembers) {
        if (operator == null || eventLeader == null || partyMembers == null) {
            return Validation.failure("Operator, leader, and party are required");
        }
        List<Character> unique = leaderFirst(eventLeader, partyMembers);
        if (!AgentPartyQuestCatalog.require("opq").acceptsPartySize(unique.size())) {
            return Validation.failure("OPQ needs five or six members");
        }
        if (unique.stream().noneMatch(AgentOpqAdmissionService::isAgent)) {
            return Validation.failure("Agent-assisted OPQ requires at least one Agent");
        }
        return partyChecks(eventLeader, unique, false);
    }

    /**
     * Why this party member cannot enter OPQ from Wonky, in admission's own words, or "" when it can: the map and the
     * level band, then, for an Agent, the ETC bag. A host can check its lineup with it before admitting it.
     */
    public static String memberProblem(Character member) {
        String problem = placementProblem(member);
        if (!problem.isEmpty() || !isAgent(member)) return problem;
        return capacityProblem(member);
    }

    /** The party, online, leader and per-member checks both rules share; {@code bagForAll} is the stock rule. */
    private static Validation partyChecks(Character eventLeader, List<Character> unique, boolean bagForAll) {
        AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(eventLeader);
        if (party == null) return Validation.failure("The OPQ leader has no party");
        Set<Integer> requested = unique.stream().map(Character::getId).collect(java.util.stream.Collectors.toSet());
        Set<Integer> online = party.members().stream().filter(java.util.Objects::nonNull)
                .map(server.agents.integration.AgentPartyMemberSnapshot::id).collect(java.util.stream.Collectors.toSet());
        if (!requested.equals(online)) return Validation.failure("Every OPQ member must be online");
        if (party.members().stream().noneMatch(member -> member != null && member.id() == eventLeader.getId() && member.leader())) {
            return Validation.failure("The selected OPQ event leader is not party leader");
        }
        int world = AgentClientGatewayRuntime.clients().world(eventLeader);
        int channel = AgentClientGatewayRuntime.clients().channel(eventLeader);
        for (Character member : unique) {
            // Stock order: the map, the level band, then world and channel, then the bag.
            String problem = placementProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
            if (AgentClientGatewayRuntime.clients().world(member) != world
                    || AgentClientGatewayRuntime.clients().channel(member) != channel) return Validation.failure("Every OPQ member must share world/channel");
            if (bagForAll || isAgent(member)) {
                problem = capacityProblem(member);
                if (!problem.isEmpty()) return Validation.failure(problem);
            }
        }
        List<Character> agents = unique.stream().filter(AgentOpqAdmissionService::isAgent).toList();
        return new Validation(true, "", List.copyOf(unique), agents, party);
    }

    private static List<Character> leaderFirst(Character eventLeader, List<Character> partyMembers) {
        List<Character> unique = new ArrayList<>();
        unique.add(eventLeader);
        partyMembers.stream().filter(java.util.Objects::nonNull).forEach(member -> {
            if (unique.stream().noneMatch(existing -> existing.getId() == member.getId())) unique.add(member);
        });
        return unique;
    }

    private static String placementProblem(Character member) {
        if (member.getMapId() != AgentOpqDefinition.RECRUIT_MAP) return member.getName() + " is not at OPQ";
        if (member.getLevel() < AgentOpqDefinition.MIN_LEVEL || member.getLevel() > AgentOpqDefinition.MAX_LEVEL) {
            return member.getName() + " must be level 51-70";
        }
        return "";
    }

    private static String capacityProblem(Character member) {
        if (member.getInventory(InventoryType.ETC).getNumFreeSlot() < 8) return member.getName() + " needs eight free ETC slots";
        return "";
    }

    private static boolean isAgent(Character character) {
        return AgentRuntimeRegistry.findByAgentCharacterId(character.getId()) != null;
    }
    public record Validation(boolean success, String message, List<Character> members,
                             List<Character> agentMembers, AgentPartySnapshot party) {
        static Validation failure(String message) { return new Validation(false, message, List.of(), List.of(), null); }
    }
    public record AdmissionResult(boolean success, String message, AgentOpqSession session,
                                  AgentPartyQuestEngagement engagement) {
        static AdmissionResult failure(String message) { return failure(message, null); }
        static AdmissionResult failure(String message, AgentPartyQuestEngagement engagement) {
            return new AdmissionResult(false, message, null, engagement);
        }
    }
}
