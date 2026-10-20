package server.agents.capabilities.partyquest.lmpq;

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

/** Transactional shared-lobby handoff into an independent LMPQ session. */
public final class AgentLmpqAdmissionService {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqAdmissionService.class);

    private AgentLmpqAdmissionService() { }

    /**
     * A host's entry of its own party: a human leader with his companions at Rolly, admitted as a companion run
     * (AgentLmpqSession.companionAdopted) under a PRODUCTION engagement, by the stock event's party rule (three to six
     * members, at least one Agent). It builds the engagement and lobby the background director would, then hands off
     * at once; on any failure the engagement is recovered and nothing is left registered. A party that is already
     * reserved is refused before any engagement exists.
     *
     * <p>It runs the activity admission and every registry and lobby change on the calling thread. The GM command
     * '!lmpqtest party prod' calls it there; a host on an IO thread (a Rolly desk and hook) must not call it directly
     * but submit it to companion-ops or the Agent tick.
     */
    public static AdmissionResult admit(
            Character operator, Character eventLeader, List<Character> members, long seed, long nowMs) {
        Validation validation = validate(operator, eventLeader, members);
        if (!validation.success()) return AdmissionResult.failure(validation.message());
        String blocker = AgentLmpqSessionRegistry.registrationBlocker(
                operator.getId(), validation.members().stream().map(Character::getId).toList());
        if (!blocker.isEmpty()) return AdmissionResult.failure("LMPQ is already reserved: " + blocker);
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "lmpq", AgentPartyQuestEngagement.Mode.PRODUCTION, seed, operator.getId(),
                validation.members().size(), nowMs);
        AgentPartyQuestLobbySession lobby = null;
        try {
            AgentPartyQuestEngagementRegistry.register(engagement);
            for (Character agent : validation.agentMembers()) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry == null || !AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, agent,
                        "admitted to autonomous LMPQ lobby", nowMs)) {
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
                    engagement.engagementId(), AgentLmpqLobbyProfile.profile(), seed,
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
                    engagement, lobby, operator, eventLeader, validation.members(), seed, nowMs, true);
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

    /** Admits a formed lobby as a stock run: a director's lobby (AgentLmpqLobbyAdmissionRuntime). */
    public static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            Character operator, Character eventLeader, List<Character> partyMembers,
            long seed, long nowMs) {
        return admitFromLobby(engagement, lobby, operator, eventLeader, partyMembers, seed, nowMs, false);
    }

    /**
     * Admits a formed lobby. {@code companionRun} is true only from {@link #admit}: the session is then flagged a
     * companion run before it is published, so no tick ever sees it as a stock run. Package-private, as HPQ's, LPQ's
     * and EPQ's, so a companion run is published only through admit, with its preparation and rollback. The mode is
     * derived from the party in either case (a human leader's run is HUMAN_LEADER).
     */
    static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            Character operator, Character eventLeader, List<Character> partyMembers,
            long seed, long nowMs, boolean companionRun) {
        Validation validation = validate(operator, eventLeader, partyMembers);
        if (!validation.success()) return restoreFailure(
                engagement, lobby, validation.message(), nowMs);
        if (engagement == null || lobby == null || !"lmpq".equals(lobby.profile().questKey())
                || !engagement.engagementId().equals(lobby.engagementId())
                || AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) {
            return restoreFailure(engagement, lobby,
                    "LMPQ admission requires a registered matching lobby", nowMs);
        }
        Set<Integer> expected = new LinkedHashSet<>(lobby.memberIds());
        Set<Integer> actual = validation.members().stream().map(Character::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!expected.equals(actual)) return restoreFailure(
                engagement, lobby, "The party changed before LMPQ entry", nowMs);
        String blocker = AgentLmpqSessionRegistry.registrationBlocker(operator.getId(), actual);
        if (!blocker.isEmpty()) return restoreFailure(
                engagement, lobby, "LMPQ is already reserved: " + blocker, nowMs);

        long humans = validation.members().stream().filter(member -> !isAgent(member)).count();
        AgentLmpqSession.Mode mode = humans == 0 ? AgentLmpqSession.Mode.AUTONOMOUS
                : isAgent(eventLeader) ? AgentLmpqSession.Mode.HUMAN_MEMBER
                : AgentLmpqSession.Mode.HUMAN_LEADER;
        AgentLmpqSession session = new AgentLmpqSession(
                mode, seed, operator.getId(), validation.members().size(), nowMs);
        validation.members().forEach(member -> session.addMember(member.getId(), isAgent(member)
                ? AgentLmpqMemberState.MemberType.AGENT : AgentLmpqMemberState.MemberType.HUMAN));
        int executor = validation.agentMembers().getFirst().getId();
        session.setLeadership(eventLeader.getId(), executor);
        if (companionRun) {
            session.setCompanionAdopted(true);
            // P6h Step 2c: a companion run meets in Room 16, Pietri's room: one destination for its leader.
            session.setRendezvousRoom(AgentLmpqDefinition.CLEAR_ROOM);
        }
        boolean published = false;
        try {
            lobby.reserve(nowMs);
            engagement.reserveEntry(nowMs);
            AgentLmpqSessionRegistry.registerComplete(session);
            published = true; // from here a failure removes the run it registered
            AgentLmpqWatchdogRuntime.ensureStarted();
            lobby.beginHandoff(nowMs);
            engagement.activateSession(session.sessionId(), nowMs);
            validation.agentMembers().forEach(agent -> {
                var entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry != null) entry.simulationState().clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
            });
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            log.info("LMPQ session admitted: session={} mode={} operator={} leader={} members={} agents={} "
                            + "companionRun={}", session.sessionId(), mode, operator.getId(), eventLeader.getId(),
                    validation.members().size(), validation.agentMembers().size(), companionRun);
            return new AdmissionResult(true, "LMPQ party admitted", session, engagement);
        } catch (RuntimeException failure) {
            if (published) AgentLmpqSessionRegistry.remove(session);
            return restoreFailure(engagement, lobby, failure.getMessage(), nowMs);
        }
    }

    public static Validation validate(Character operator, Character eventLeader, List<Character> partyMembers) {
        if (operator == null || eventLeader == null || partyMembers == null) {
            return Validation.failure("Operator, leader, and party are required");
        }
        List<Character> unique = new ArrayList<>();
        unique.add(eventLeader);
        partyMembers.stream().filter(java.util.Objects::nonNull).forEach(member -> {
            if (unique.stream().noneMatch(existing -> existing.getId() == member.getId())) unique.add(member);
        });
        if (unique.size() < AgentLmpqDefinition.MIN_PARTY_SIZE
                || unique.size() > AgentLmpqDefinition.MAX_PARTY_SIZE) {
            return Validation.failure("LMPQ requires three to six members");
        }
        List<Character> agents = unique.stream().filter(AgentLmpqAdmissionService::isAgent).toList();
        if (agents.isEmpty()) return Validation.failure("Agent-assisted LMPQ requires at least one Agent");
        AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(eventLeader);
        if (party == null) return Validation.failure("The LMPQ leader has no party");
        Set<Integer> requested = unique.stream().map(Character::getId).collect(java.util.stream.Collectors.toSet());
        Set<Integer> online = party.members().stream().filter(java.util.Objects::nonNull)
                .map(server.agents.integration.AgentPartyMemberSnapshot::id).collect(java.util.stream.Collectors.toSet());
        if (!requested.equals(online)) return Validation.failure("Every LMPQ member must be online");
        if (party.members().stream().noneMatch(member -> member != null
                && member.id() == eventLeader.getId() && member.leader())) {
            return Validation.failure("The selected LMPQ event leader is not party leader");
        }
        int world = AgentClientGatewayRuntime.clients().world(eventLeader);
        int channel = AgentClientGatewayRuntime.clients().channel(eventLeader);
        for (Character member : unique) {
            AgentPartySnapshot memberParty = AgentPartyGatewayRuntime.party().snapshot(member);
            if (memberParty == null || memberParty.id() != party.id()) {
                return Validation.failure("Every LMPQ member must share the party");
            }
            // Stock order: the entrance and the level band, then world and channel, then the bag.
            String problem = placementProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
            if (AgentClientGatewayRuntime.clients().world(member) != world
                    || AgentClientGatewayRuntime.clients().channel(member) != channel) {
                return Validation.failure("Every LMPQ member must share world and channel");
            }
            problem = capacityProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
        }
        return new Validation(true, "", List.copyOf(unique), agents, party);
    }

    /**
     * Why this party member cannot enter LMPQ from Rolly, in admission's own words, or "" when it can. A host can
     * check its lineup with it before admitting it.
     */
    public static String memberProblem(Character member) {
        String problem = placementProblem(member);
        return problem.isEmpty() ? capacityProblem(member) : problem;
    }

    private static String placementProblem(Character member) {
        if (member.getMapId() != AgentLmpqDefinition.RECRUIT_MAP) {
            return member.getName() + " is not at the LMPQ entrance";
        }
        if (member.getLevel() < AgentLmpqDefinition.MIN_LEVEL
                || member.getLevel() > AgentLmpqDefinition.MAX_LEVEL) {
            return member.getName() + " must be level 51-70";
        }
        return "";
    }

    private static String capacityProblem(Character member) {
        if (member.getInventory(InventoryType.ETC).getNextFreeSlot() < 0) {
            return member.getName() + " needs a free ETC slot";
        }
        return "";
    }

    private static boolean isAgent(Character character) {
        return character != null && AgentRuntimeRegistry.findByAgentCharacterId(character.getId()) != null;
    }

    private static AdmissionResult restoreFailure(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            String message, long nowMs) {
        String reason = message == null || message.isBlank()
                ? "unknown LMPQ admission failure" : message;
        if (lobby != null && lobby.active()) lobby.restoreForming(reason, nowMs);
        if (engagement != null) {
            switch (engagement.state()) {
                case LOBBY_FORMING, LOBBY_READY, RESERVING_ENTRY, ACTIVE_EVENT ->
                        engagement.restoreLobby(reason, nowMs);
                default -> engagement.addDiagnostic(reason, nowMs);
            }
        }
        return AdmissionResult.failure(reason, engagement);
    }

    public record Validation(boolean success, String message, List<Character> members,
                             List<Character> agentMembers, AgentPartySnapshot party) {
        static Validation failure(String message) {
            return new Validation(false, message, List.of(), List.of(), null);
        }
    }

    /** {@code engagement}: the engagement the admission ran under (a failed public admit returns it recovered). */
    public record AdmissionResult(boolean success, String message, AgentLmpqSession session,
                                  AgentPartyQuestEngagement engagement) {
        static AdmissionResult failure(String message) { return new AdmissionResult(false, message, null, null); }

        static AdmissionResult failure(String message, AgentPartyQuestEngagement engagement) {
            return new AdmissionResult(false, message, null, engagement);
        }
    }
}
