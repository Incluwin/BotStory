package server.agents.capabilities.partyquest.lpq;

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

/** Transactional standardized-lobby handoff into an LPQ-owned session. */
public final class AgentLpqAdmissionService {
    private AgentLpqAdmissionService() { }

    /**
     * A host's entry of its own party: a human leader with his companions at the Red Sign, admitted as a PRODUCTION
     * companion run (AgentLpqSession.companionAdopted). It builds the engagement and lobby the background director
     * would, then hands off at once; on any failure the engagement is recovered and nothing is left registered.
     *
     * <p>It runs the activity admission and every registry and lobby change on the calling thread. The GM command
     * '!lpqtest party prod' calls it there, as '!hpqtest party prod' calls HPQ's admit; a host on an IO thread (a
     * Red Sign desk and hook) must not call it directly but submit it to companion-ops or the Agent tick.
     */
    public static AdmissionResult admit(
            Character operator, Character eventLeader, List<Character> members, long seed, long nowMs) {
        Validation validation = validate(operator, eventLeader, members);
        if (!validation.success()) return AdmissionResult.failure(validation.message());
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "lpq", AgentPartyQuestEngagement.Mode.PRODUCTION, seed, operator.getId(),
                validation.members().size(), nowMs);
        AgentPartyQuestLobbySession lobby = null;
        try {
            AgentPartyQuestEngagementRegistry.register(engagement);
            for (Character agent : validation.agentMembers()) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry == null || !AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, agent,
                        "admitted to autonomous LPQ lobby", nowMs)) {
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
                    engagement.engagementId(), AgentLpqLobbyProfile.profile(), seed,
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
                    seed, nowMs, AgentLpqSession.Mode.PRODUCTION, 0,
                    AgentLpqSession.HumanRolePreference.DEFAULT, true);
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

    /** Admits a formed lobby as a stock run: a director's lobby, or an '!lpqtest' fixture's. */
    public static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            Character operator, Character eventLeader, List<Character> partyMembers,
            long seed, long nowMs, AgentLpqSession.Mode mode, int preferredHumanId,
            AgentLpqSession.HumanRolePreference humanRolePreference) {
        return admitFromLobby(engagement, lobby, operator, eventLeader, partyMembers, seed, nowMs, mode,
                preferredHumanId, humanRolePreference, false);
    }

    /**
     * Admits a formed lobby. {@code companionRun} is true only from {@link #admit}: the session is then flagged a
     * companion run before it is published, so no tick or watchdog ever sees it as a stock run. Package-private, as
     * HPQ's and EPQ's, so a companion run is published only through admit, with its preparation and rollback.
     */
    static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            Character operator, Character eventLeader, List<Character> partyMembers,
            long seed, long nowMs, AgentLpqSession.Mode mode, int preferredHumanId,
            AgentLpqSession.HumanRolePreference humanRolePreference, boolean companionRun) {
        Validation validation = validate(operator, eventLeader, partyMembers,
                preferredHumanId, humanRolePreference);
        if (!validation.success()) return restoreFailure(engagement, lobby, validation.message(), nowMs);
        if (engagement == null || lobby == null
                || !engagement.engagementId().equals(lobby.engagementId())
                || AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) {
            return AdmissionResult.failure("LPQ admission requires a registered matching lobby engagement");
        }
        Set<Integer> lobbyMembers = new LinkedHashSet<>(lobby.memberIds());
        Set<Integer> partyIds = validation.members().stream().map(Character::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!lobbyMembers.equals(partyIds)) {
            return restoreFailure(engagement, lobby, "The party changed before LPQ entry", nowMs);
        }
        String blocker = AgentLpqSessionRegistry.registrationBlocker(operator.getId(), partyIds);
        if (!blocker.isEmpty()) return restoreFailure(engagement, lobby, "LPQ is reserved: " + blocker, nowMs);

        AgentLpqSession session = new AgentLpqSession(mode, seed, operator.getId(), partyIds.size(), nowMs);
        session.setPartyOwnership(isAgent(eventLeader)
                ? AgentLpqSession.PartyOwnership.LPQ_OWNED : AgentLpqSession.PartyOwnership.EXTERNAL);
        session.setBonusMode(isAgent(eventLeader)
                ? AgentLpqSession.BonusMode.ENTER : AgentLpqSession.BonusMode.HUMAN_CHOICE);
        validation.members().forEach(member -> session.addMember(member.getId(), isAgent(member)
                ? AgentLpqMemberState.MemberType.AGENT : AgentLpqMemberState.MemberType.HUMAN));
        if (preferredHumanId > 0 && validation.members().stream().anyMatch(member ->
                member.getId() == preferredHumanId && !isAgent(member))) {
            session.setHumanRolePreference(preferredHumanId,
                    humanRolePreference == null
                            ? AgentLpqSession.HumanRolePreference.DEFAULT : humanRolePreference);
        }
        session.setLeadership(eventLeader.getId(), validation.agentMembers().getFirst().getId());
        if (companionRun) session.setCompanionAdopted(true);
        if (companionRun) validation.agentMembers().forEach(agent -> session.addCompanionOfRun(agent.getId()));
        boolean published = false;
        try {
            lobby.reserve(nowMs);
            engagement.reserveEntry(nowMs);
            AgentLpqSessionRegistry.registerComplete(session);
            AgentLpqWatchdogRuntime.ensureStarted();
            published = true;
            lobby.beginHandoff(nowMs);
            engagement.activateSession(session.sessionId(), nowMs);
            validation.agentMembers().forEach(agent -> {
                var entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry != null) entry.simulationState().clearAbstractExecution(AgentAbstractExecutionScope.TOWN_LIFE);
            });
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            return new AdmissionResult(true, "LPQ party admitted", session, engagement);
        } catch (RuntimeException failure) {
            if (published) AgentLpqSessionRegistry.remove(session);
            return restoreFailure(engagement, lobby, failure.getMessage(), nowMs);
        }
    }

    public static Validation validate(Character operator, Character eventLeader, List<Character> partyMembers) {
        return validate(operator, eventLeader, partyMembers, 0,
                AgentLpqSession.HumanRolePreference.DEFAULT);
    }

    static Validation validate(Character operator, Character eventLeader, List<Character> partyMembers,
                               int preferredHumanId,
                               AgentLpqSession.HumanRolePreference humanRolePreference) {
        if (operator == null || eventLeader == null || partyMembers == null) {
            return Validation.failure("Operator, leader, and party members are required");
        }
        List<Character> unique = new ArrayList<>();
        unique.add(eventLeader);
        partyMembers.stream().filter(java.util.Objects::nonNull).forEach(member -> {
            if (unique.stream().noneMatch(existing -> existing.getId() == member.getId())) unique.add(member);
        });
        if (unique.size() != AgentLpqDefinition.RECOMMENDED_PARTY_SIZE) {
            return Validation.failure("Agent-assisted LPQ currently requires six members; the ordinary event still accepts five to six");
        }
        List<Character> agents = unique.stream().filter(AgentLpqAdmissionService::isAgent).toList();
        if (agents.isEmpty()) return Validation.failure("Agent-assisted LPQ requires an Agent participant");
        AgentLpqRosterRequirementPolicy.Coverage coverage =
                capabilityCoverage(unique, agents, preferredHumanId, humanRolePreference);
        if (!coverage.complete()) return Validation.failure("Missing LPQ party capability: "
                + String.join(", ", coverage.missingRequirements()));
        AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(eventLeader);
        if (party == null) return Validation.failure("The LPQ leader has no party");
        Set<Integer> requested = unique.stream().map(Character::getId).collect(java.util.stream.Collectors.toSet());
        Set<Integer> actual = party.members().stream().filter(java.util.Objects::nonNull)
                .map(server.agents.integration.AgentPartyMemberSnapshot::id).collect(java.util.stream.Collectors.toSet());
        if (!requested.equals(actual)) return Validation.failure("Every party member must be online for LPQ");
        if (party.members().stream().noneMatch(member -> member != null
                && member.id() == eventLeader.getId() && member.leader())) {
            return Validation.failure("The selected LPQ event leader is not the party leader");
        }
        int world = AgentClientGatewayRuntime.clients().world(eventLeader);
        int channel = AgentClientGatewayRuntime.clients().channel(eventLeader);
        for (Character member : unique) {
            AgentPartySnapshot memberParty = AgentPartyGatewayRuntime.party().snapshot(member);
            if (memberParty == null || memberParty.id() != party.id()) return Validation.failure("Every LPQ member must share the party");
            // Stock order: entrance and level, then world and channel, then the bag.
            String problem = placementProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
            if (AgentClientGatewayRuntime.clients().world(member) != world
                    || AgentClientGatewayRuntime.clients().channel(member) != channel) {
                return Validation.failure("Every LPQ member must share world and channel");
            }
            problem = capacityProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
        }
        return new Validation(true, "", List.copyOf(unique), agents, party);
    }

    /**
     * Why this party member cannot enter LPQ from the Red Sign, in admission's own words, or "" when he can. A host
     * can check its lineup with it before admitting it.
     */
    public static String memberProblem(Character member) {
        String problem = placementProblem(member);
        return problem.isEmpty() ? capacityProblem(member) : problem;
    }

    private static String placementProblem(Character member) {
        if (member.getMapId() != AgentLpqDefinition.RECRUIT_MAP) return member.getName() + " is not at LPQ";
        if (member.getLevel() < AgentLpqDefinition.MIN_LEVEL || member.getLevel() > AgentLpqDefinition.MAX_LEVEL) {
            return member.getName() + " must be level 35-50";
        }
        return "";
    }

    private static String capacityProblem(Character member) {
        if (member.getInventory(InventoryType.ETC).getNextFreeSlot() < 0) {
            return member.getName() + " needs a free ETC slot";
        }
        return "";
    }

    static AgentLpqRosterRequirementPolicy.Coverage capabilityCoverage(
            List<Character> partyMembers, List<Character> agentMembers, int preferredHumanId,
            AgentLpqSession.HumanRolePreference humanRolePreference) {
        List<Character> roster = new ArrayList<>(agentMembers);
        AgentLpqSession.HumanRolePreference preference = humanRolePreference == null
                ? AgentLpqSession.HumanRolePreference.DEFAULT : humanRolePreference;
        if (preference != AgentLpqSession.HumanRolePreference.DEFAULT) {
            partyMembers.stream().filter(member -> member != null
                            && member.getId() == preferredHumanId && !isAgent(member)
                            && supports(member, preference))
                    .findFirst().ifPresent(roster::add);
        }
        return AgentLpqRosterRequirementPolicy.evaluate(roster);
    }

    private static boolean supports(Character character,
                                    AgentLpqSession.HumanRolePreference preference) {
        return switch (preference) {
            case TELEPORT -> AgentLpqRosterRequirementPolicy.teleportMagic(character);
            case DARK_SIGHT -> AgentLpqRosterRequirementPolicy.darkSight(character);
            case RANGED -> AgentLpqRosterRequirementPolicy.rangedAttack(character);
            case DEFAULT -> false;
        };
    }

    private static AdmissionResult restoreFailure(AgentPartyQuestEngagement engagement,
                                                   AgentPartyQuestLobbySession lobby,
                                                   String message, long nowMs) {
        String reason = message == null || message.isBlank() ? "unknown LPQ admission failure" : message;
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
        return character != null && AgentRuntimeRegistry.findByAgentCharacterId(character.getId()) != null;
    }

    public record Validation(boolean success, String message, List<Character> members,
                             List<Character> agentMembers, AgentPartySnapshot party) {
        private static Validation failure(String message) {
            return new Validation(false, message, List.of(), List.of(), null);
        }
    }
    public record AdmissionResult(boolean success, String message, AgentLpqSession session,
                                  AgentPartyQuestEngagement engagement) {
        private static AdmissionResult failure(String message) { return new AdmissionResult(false, message, null, null); }
    }
}
