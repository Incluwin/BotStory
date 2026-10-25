package server.agents.capabilities.partyquest.hpq;

import client.Character;
import client.inventory.InventoryType;
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

/** Transactional handoff from the standardized lobby into an HPQ-owned session. */
public final class AgentHpqAdmissionService {
    private AgentHpqAdmissionService() {
    }

    /**
     * A host's entry of its own party: a human leader with his companions at Tory, admitted as a PRODUCTION companion
     * run (AgentHpqSession.companionAdopted). It builds the engagement and lobby the background director would, then
     * hands off at once; on any failure the engagement is recovered and nothing is left registered.
     *
     * <p>It runs the activity admission and every registry and lobby change on the calling thread. The GM command
     * '!hpqtest party prod' calls it there, as '!kpqtest party prod' calls KPQ's admit; a host on an IO thread (the
     * Tory desk and hook) must not call it directly but submit it to companion-ops or the Agent tick.
     */
    public static AdmissionResult admit(
            Character operator, Character eventLeader, List<Character> members, long seed, long nowMs) {
        return admit(operator, eventLeader, members, seed, nowMs, false);
    }

    /**
     * The population's entry of a citizen party that formed below Tory (CitizenPartyQuests): its citizen leader is
     * operator and event leader, the party stays its own (EXTERNAL), the leader picks the bonus as an Agent leader does,
     * and the session is a citizen run, not a companion run, from its first tick. Same threading as {@link #admit}.
     */
    public static AdmissionResult admitCitizens(Character leader, List<Character> members, long seed, long nowMs) {
        return admit(leader, leader, members, seed, nowMs, true);
    }

    private static AdmissionResult admit(Character operator, Character eventLeader, List<Character> members, long seed,
                                         long nowMs, boolean citizenRun) {
        Validation validation = validate(operator, eventLeader, members);
        if (!validation.success()) return AdmissionResult.failure(validation.message());
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "hpq", AgentPartyQuestEngagement.Mode.PRODUCTION, seed, operator.getId(),
                validation.members().size(), nowMs);
        AgentPartyQuestLobbySession lobby = null;
        try {
            AgentPartyQuestEngagementRegistry.register(engagement);
            for (Character agent : validation.agentMembers()) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry == null || !AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, agent,
                        "admitted to autonomous HPQ lobby", nowMs)) {
                    engagement.beginRecovery(agent.getName()
                            + " could not leave its current activity", nowMs);
                    AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
                    return new AdmissionResult(false, agent.getName()
                            + " could not leave its current activity", null, engagement);
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
                    engagement.engagementId(), AgentHpqLobbyProfile.profile(), seed,
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
                    seed, nowMs, AgentHpqSession.Mode.PRODUCTION, !citizenRun, citizenRun);
            if (!result.success()) {
                AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
                engagement.beginRecovery(result.message(), nowMs);
                AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
            }
            return result;
        } catch (RuntimeException failure) {
            if (lobby != null) AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            engagement.beginRecovery(failure.getMessage(), nowMs);
            AgentPartyQuestLifecycleRuntime.recover(engagement, nowMs);
            return new AdmissionResult(false, failure.getMessage(), null, engagement);
        }
    }

    /**
     * Admits a formed lobby. {@code companionRun} is true only from {@link #admit}: the session is then flagged a
     * companion run before it is published, so no tick or watchdog ever sees it as a stock run.
     */
    static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement,
            AgentPartyQuestLobbySession lobby,
            Character operator,
            Character eventLeader,
            List<Character> partyMembers,
            long seed,
            long nowMs,
            AgentHpqSession.Mode mode,
            boolean companionRun) {
        return admitFromLobby(engagement, lobby, operator, eventLeader, partyMembers, seed, nowMs, mode, companionRun,
                false);
    }

    /** As above; {@code citizenRun} (only from {@link #admitCitizens}) flags a citizen run before it is published. */
    private static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement,
            AgentPartyQuestLobbySession lobby,
            Character operator,
            Character eventLeader,
            List<Character> partyMembers,
            long seed,
            long nowMs,
            AgentHpqSession.Mode mode,
            boolean companionRun,
            boolean citizenRun) {
        Validation validation = validate(operator, eventLeader, partyMembers);
        if (!validation.success()) return restoreFailure(engagement, lobby, validation.message(), nowMs);
        if (engagement == null || lobby == null
                || !engagement.engagementId().equals(lobby.engagementId())
                || AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) {
            return AdmissionResult.failure("HPQ admission requires a registered matching lobby engagement");
        }
        Set<Integer> lobbyMembers = new LinkedHashSet<>(lobby.memberIds());
        Set<Integer> partyIds = validation.members().stream().map(Character::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!lobbyMembers.equals(partyIds)) {
            return restoreFailure(engagement, lobby,
                    "The authoritative party changed before HPQ entry", nowMs);
        }
        String registryBlocker = AgentHpqSessionRegistry.registrationBlocker(
                operator.getId(), partyIds);
        if (!registryBlocker.isEmpty()) {
            return restoreFailure(engagement, lobby,
                    "HPQ admission is already reserved: " + registryBlocker, nowMs);
        }

        AgentHpqSession session = new AgentHpqSession(
                mode, seed, operator.getId(), validation.members().size(), nowMs);
        session.setPartyOwnership(isAgent(eventLeader) && !citizenRun
                ? AgentHpqSession.PartyOwnership.HPQ_OWNED
                : AgentHpqSession.PartyOwnership.EXTERNAL);
        session.setBonusMode(isAgent(eventLeader)
                ? AgentHpqBonusPolicy.defaultMode()
                : AgentHpqSession.BonusMode.HUMAN_CHOICE);
        for (Character member : validation.members()) {
            session.addMember(member.getId(), isAgent(member)
                    ? AgentHpqMemberState.MemberType.AGENT : AgentHpqMemberState.MemberType.HUMAN);
        }
        session.setLeadership(eventLeader.getId(), validation.agentMembers().getFirst().getId());
        assignInitialRoles(session);
        if (companionRun) session.setCompanionAdopted(true);
        if (citizenRun) session.setCitizenRun(true);

        boolean published = false;
        try {
            lobby.reserve(nowMs);
            engagement.reserveEntry(nowMs);
            AgentHpqSessionRegistry.registerComplete(session);
            AgentHpqWatchdogRuntime.ensureStarted();
            published = true;
            lobby.beginHandoff(nowMs);
            engagement.activateSession(session.sessionId(), nowMs);
            for (Character agent : validation.agentMembers()) {
                var entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry != null) entry.simulationState()
                        .clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
            }
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            return new AdmissionResult(true, "HPQ party admitted", session, engagement);
        } catch (RuntimeException failure) {
            if (published) AgentHpqSessionRegistry.remove(session);
            return restoreFailure(engagement, lobby, failure.getMessage(), nowMs);
        }
    }

    private static void assignInitialRoles(AgentHpqSession session) {
        List<AgentHpqMemberState> agents = session.members().stream()
                .filter(member -> member.memberType() == AgentHpqMemberState.MemberType.AGENT)
                .toList();
        for (int index = 0; index < agents.size(); index++) {
            AgentHpqDefinition.SeedBed bed = AgentHpqDefinition.seedBeds()
                    .get(index % AgentHpqDefinition.seedBeds().size());
            agents.get(index).assign(AgentHpqMemberState.Role.SEED_COLLECTOR, bed.seedItemId());
        }
        AgentHpqMemberState leader = session.member(session.eventLeaderId());
        if (leader != null) leader.assign(AgentHpqMemberState.Role.EVENT_LEADER, 0);
    }

    static Validation validate(Character operator, Character eventLeader, List<Character> partyMembers) {
        if (operator == null || eventLeader == null || partyMembers == null) {
            return Validation.failure("Operator, leader, and party members are required");
        }
        List<Character> unique = new ArrayList<>();
        unique.add(eventLeader);
        for (Character member : partyMembers) {
            if (member != null && unique.stream().noneMatch(existing -> existing.getId() == member.getId())) {
                unique.add(member);
            }
        }
        if (unique.size() < 3 || unique.size() > 6) {
            return Validation.failure("The current Henesys event accepts three to six members");
        }
        List<Character> agents = unique.stream().filter(AgentHpqAdmissionService::isAgent).toList();
        if (agents.isEmpty()) {
            return Validation.failure("Agent-assisted HPQ requires at least one Agent participant");
        }
        AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(eventLeader);
        if (party == null) return Validation.failure("The event leader has no party");
        Set<Integer> requestedIds = unique.stream().map(Character::getId)
                .collect(java.util.stream.Collectors.toSet());
        Set<Integer> authoritativeIds = party.members().stream()
                .filter(java.util.Objects::nonNull)
                .map(server.agents.integration.AgentPartyMemberSnapshot::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!requestedIds.equals(authoritativeIds)) {
            return Validation.failure("Every authoritative party member must be online for HPQ");
        }
        boolean actualLeader = party.members().stream().anyMatch(member -> member != null
                && member.id() == eventLeader.getId() && member.leader());
        if (!actualLeader) return Validation.failure("The selected HPQ event leader is not the party leader");
        int world = AgentClientGatewayRuntime.clients().world(eventLeader);
        int channel = AgentClientGatewayRuntime.clients().channel(eventLeader);
        for (Character member : unique) {
            AgentPartySnapshot memberParty = AgentPartyGatewayRuntime.party().snapshot(member);
            if (memberParty == null || memberParty.id() != party.id()) {
                return Validation.failure("Every HPQ member must be in the event leader's party");
            }
            // Stock order: entrance and level, then world and channel, then the bag.
            String problem = placementProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
            if (AgentClientGatewayRuntime.clients().world(member) != world
                    || AgentClientGatewayRuntime.clients().channel(member) != channel) {
                return Validation.failure("Every HPQ member must be on the same world and channel");
            }
            problem = capacityProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
        }
        return new Validation(true, "", List.copyOf(unique), agents, party);
    }

    /**
     * Why this party member cannot enter HPQ from Tory, in admission's own words, or "" when it can. A host can check
     * its lineup with it before admitting it.
     */
    public static String memberProblem(Character member) {
        String problem = placementProblem(member);
        return problem.isEmpty() ? capacityProblem(member) : problem;
    }

    private static String placementProblem(Character member) {
        if (member.getMapId() != AgentHpqDefinition.RECRUIT_MAP) {
            return member.getName() + " is not at the Henesys PQ entrance";
        }
        if (member.getLevel() < 10 || member.getLevel() > 255) {
            return member.getName() + " must be level 10-255 for HPQ";
        }
        return "";
    }

    private static String capacityProblem(Character member) {
        if (member.getInventory(InventoryType.ETC).getNextFreeSlot() < 0) {
            return member.getName() + " needs free ETC capacity for HPQ items";
        }
        return "";
    }

    private static AdmissionResult restoreFailure(
            AgentPartyQuestEngagement engagement,
            AgentPartyQuestLobbySession lobby,
            String message,
            long nowMs) {
        String reason = message == null || message.isBlank() ? "unknown HPQ admission failure" : message;
        if (lobby != null && lobby.active()) lobby.restoreForming(reason, nowMs);
        if (engagement != null) {
            switch (engagement.state()) {
                case LOBBY_FORMING, LOBBY_READY, RESERVING_ENTRY, ACTIVE_EVENT ->
                        engagement.restoreLobby(reason, nowMs);
                default -> engagement.addDiagnostic(reason, nowMs);
            }
        }
        return new AdmissionResult(false, reason, null, engagement);
    }

    private static boolean isAgent(Character character) {
        return character != null
                && AgentRuntimeRegistry.findByAgentCharacterId(character.getId()) != null;
    }

    record Validation(boolean success, String message, List<Character> members,
                      List<Character> agentMembers, AgentPartySnapshot party) {
        private static Validation failure(String message) {
            return new Validation(false, message, List.of(), List.of(), null);
        }
    }

    public record AdmissionResult(boolean success, String message, AgentHpqSession session,
                                  AgentPartyQuestEngagement engagement) {
        static AdmissionResult failure(String message) {
            return new AdmissionResult(false, message, null, null);
        }
    }
}
