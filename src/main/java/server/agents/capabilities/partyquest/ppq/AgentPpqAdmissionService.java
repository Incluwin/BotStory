package server.agents.capabilities.partyquest.ppq;

import client.Character;
import client.inventory.Inventory;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Transactional shared-lobby handoff into Pirate PQ, and the public companion-run admit. */
public final class AgentPpqAdmissionService {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqAdmissionService.class);

    private AgentPpqAdmissionService() { }

    /**
     * A human leader's own party of four to six on Guon's map becomes a PPQ companion run (the host's desk and the
     * '!ppqtest party prod' adoption): validation, then the engagement, each companion leaving its activity, a formed
     * lobby, and the session published with its companion flag already set. Chest rooms are played.
     */
    public static AdmissionResult admit(
            Character operator, Character eventLeader, List<Character> members, long seed, long nowMs) {
        return admit(operator, eventLeader, members, false, seed, nowMs);
    }

    /** {@link #admit(Character, Character, List, long, long)}, with the run skipping the chest rooms when asked. */
    public static AdmissionResult admit(Character operator, Character eventLeader, List<Character> members,
                                        boolean skipChestRooms, long seed, long nowMs) {
        Validation validation = validate(operator, eventLeader, members, true);
        if (!validation.success()) return AdmissionResult.failure(validation.message());
        List<Character> agents = validation.members().stream().filter(AgentPpqAdmissionService::isAgent).toList();
        for (Character agent : agents) {
            Inventory etc = agent.getInventory(InventoryType.ETC);
            if (etc == null || etc.getNumFreeSlot() < AgentPpqDefinition.CLEAR_ETC_SLOTS) {
                return AdmissionResult.failure(agent.getName() + " needs two free etc slots.");
            }
        }
        String blocker = AgentPpqSessionRegistry.registrationBlocker(
                operator.getId(), validation.members().stream().map(Character::getId).toList());
        if (!blocker.isEmpty()) return AdmissionResult.failure("PPQ admission is already reserved: " + blocker);
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "ppq", AgentPartyQuestEngagement.Mode.PRODUCTION, seed, operator.getId(),
                validation.members().size(), nowMs);
        AgentPartyQuestLobbySession lobby = null;
        try {
            AgentPartyQuestEngagementRegistry.register(engagement);
            for (Character agent : agents) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry == null || !AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, agent,
                        "admitted to a companion PPQ run", nowMs)) {
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
                    engagement.engagementId(), AgentPpqLobbyProfile.profile(), seed, operator.getId(),
                    validation.members().size(), AgentPartyQuestCandidateScope.ANY_ELIGIBLE_HUMAN, nowMs);
            for (Character member : validation.members()) {
                lobby.addMember(member.getId(), isAgent(member)
                                ? AgentPartyQuestLobbySession.MemberType.AGENT
                                : AgentPartyQuestLobbySession.MemberType.HUMAN,
                        AgentPartyQuestLobbySession.MemberRole.JOINED_MEMBER, nowMs);
            }
            lobby.setCoordinatorAgentId(agents.getFirst().getId());
            AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(eventLeader);
            Set<Integer> roster = validation.members().stream().map(Character::getId)
                    .collect(java.util.stream.Collectors.toSet());
            lobby.reconcileParty(party.id(), eventLeader.getId(), roster, nowMs);
            lobby.markReady(nowMs);
            engagement.beginLobby(lobby.lobbyId(), nowMs);
            engagement.lobbyReady(nowMs);
            AgentPartyQuestLobbyRuntime.register(lobby, nowMs);
            AdmissionResult result = admitFromLobby(engagement, lobby, operator, eventLeader, validation.members(),
                    seed, nowMs, true, skipChestRooms);
            if (result.success()) return new AdmissionResult(true, result.message(), result.session(), engagement);
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

    /**
     * Why this party member cannot enter PPQ from Guon's map, in admission's own words ("<name> is not at PPQ",
     * "<name> must be level 55-100"), or "" when it can. The bag room is the companion admit's own rule.
     */
    public static String memberProblem(Character member) {
        if (member == null) return "";
        if (member.getMapId() != AgentPpqDefinition.RECRUIT_MAP) return member.getName() + " is not at PPQ";
        if (member.getLevel() < AgentPpqLobbyProfile.profile().minimumLevel()
                || member.getLevel() > AgentPpqLobbyProfile.profile().maximumLevel()) {
            return member.getName() + " must be level " + AgentPpqLobbyProfile.profile().minimumLevel() + '-'
                    + AgentPpqLobbyProfile.profile().maximumLevel();
        }
        return "";
    }

    public static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            Character operator, Character eventLeader, List<Character> partyMembers,
            long seed, long nowMs) {
        return admitFromLobby(engagement, lobby, operator, eventLeader, partyMembers, seed, nowMs, false, false);
    }

    /**
     * Publishes the session behind the formed lobby. A companion run is a HUMAN_LEADER session flagged before it is
     * registered, so no tick ever sees it otherwise; every other caller keeps today's rules.
     */
    static AdmissionResult admitFromLobby(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            Character operator, Character eventLeader, List<Character> partyMembers,
            long seed, long nowMs, boolean companionRun, boolean skipChestRooms) {
        Validation validation = validate(operator, eventLeader, partyMembers, companionRun);
        if (!validation.success()) {
            return restoreFailure(engagement, lobby, validation.message(), nowMs);
        }
        if (engagement == null || lobby == null
                || !engagement.engagementId().equals(lobby.engagementId())
                || AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) {
            return AdmissionResult.failure(
                    "PPQ admission requires a registered matching lobby engagement");
        }
        Set<Integer> lobbyIds = new LinkedHashSet<>(lobby.memberIds());
        Set<Integer> memberIds = validation.members().stream().map(Character::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!lobbyIds.equals(memberIds)) {
            return restoreFailure(engagement, lobby,
                    "The party changed before PPQ entry", nowMs);
        }
        boolean agentLeader = isAgent(eventLeader);
        boolean humanMember = validation.members().stream().anyMatch(member -> !isAgent(member));
        AgentPpqSession.Mode mode = companionRun ? AgentPpqSession.Mode.HUMAN_LEADER : agentLeader
                ? (humanMember ? AgentPpqSession.Mode.HUMAN_MEMBER
                        : AgentPpqSession.Mode.AUTONOMOUS)
                : AgentPpqSession.Mode.HUMAN_LEADER;
        AgentPpqSession session = new AgentPpqSession(mode, seed, operator.getId(), companionRun && skipChestRooms,
                nowMs);
        validation.members().forEach(member -> session.addMember(
                member.getId(), isAgent(member)
                        ? AgentPpqMemberState.MemberType.AGENT
                        : AgentPpqMemberState.MemberType.HUMAN));
        int executor = validation.members().stream().filter(AgentPpqAdmissionService::isAgent)
                .mapToInt(Character::getId).findFirst().orElseThrow();
        session.setLeadership(eventLeader.getId(), executor);
        session.setCompanionAdopted(companionRun);
        boolean published = false;
        try {
            lobby.reserve(nowMs);
            engagement.reserveEntry(nowMs);
            AgentPpqSessionRegistry.registerComplete(session);
            published = true;
            lobby.beginHandoff(nowMs);
            engagement.activateSession(session.sessionId(), nowMs);
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            log.info("PPQ session admitted: session={} operator={} leader={} members={} companionRun={}",
                    session.sessionId(), operator.getId(), eventLeader.getId(), session.memberCount(), companionRun);
            return new AdmissionResult(true, "PPQ party admitted", session, engagement);
        } catch (RuntimeException failure) {
            if (published) AgentPpqSessionRegistry.remove(session);
            return restoreFailure(engagement, lobby, failure.getMessage(), nowMs);
        }
    }

    public static Validation validate(
            Character operator, Character eventLeader, List<Character> partyMembers) {
        return validate(operator, eventLeader, partyMembers, false);
    }

    /** Today's rules; a companion run takes four to six members instead of exactly six. */
    static Validation validate(
            Character operator, Character eventLeader, List<Character> partyMembers, boolean companionRun) {
        if (operator == null || eventLeader == null || partyMembers == null) {
            return Validation.failure("Operator, leader, and party members are required");
        }
        List<Character> unique = new ArrayList<>();
        unique.add(eventLeader);
        partyMembers.stream().filter(java.util.Objects::nonNull).forEach(member -> {
            if (unique.stream().noneMatch(existing -> existing.getId() == member.getId())) {
                unique.add(member);
            }
        });
        if (companionRun && (unique.size() < AgentPpqDefinition.COMPANION_MIN_PARTY_SIZE
                || unique.size() > AgentPpqDefinition.COMPANION_MAX_PARTY_SIZE)) {
            return Validation.failure("A PPQ companion run takes four to six members");
        }
        if (!companionRun && unique.size() != AgentPpqDefinition.PARTY_SIZE) {
            return Validation.failure("Agent-assisted PPQ requires six members");
        }
        if (unique.stream().noneMatch(AgentPpqAdmissionService::isAgent)) {
            return Validation.failure("Agent-assisted PPQ requires an Agent participant");
        }
        AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(eventLeader);
        if (party == null) return Validation.failure("The PPQ leader has no party");
        Set<Integer> requested = unique.stream().map(Character::getId)
                .collect(java.util.stream.Collectors.toSet());
        Set<Integer> actual = party.members().stream().filter(java.util.Objects::nonNull)
                .map(server.agents.integration.AgentPartyMemberSnapshot::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!requested.equals(actual)) return Validation.failure("Every PPQ member must be online");
        if (party.members().stream().noneMatch(member -> member != null
                && member.id() == eventLeader.getId() && member.leader())) {
            return Validation.failure("The selected PPQ event leader is not party leader");
        }
        int world = AgentClientGatewayRuntime.clients().world(eventLeader);
        int channel = AgentClientGatewayRuntime.clients().channel(eventLeader);
        for (Character member : unique) {
            AgentPartySnapshot memberParty = AgentPartyGatewayRuntime.party().snapshot(member);
            if (memberParty == null || memberParty.id() != party.id()) {
                return Validation.failure("Every PPQ member must share the party");
            }
            String problem = memberProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
            if (AgentClientGatewayRuntime.clients().world(member) != world
                    || AgentClientGatewayRuntime.clients().channel(member) != channel) {
                return Validation.failure("Every PPQ member must share world and channel");
            }
        }
        return new Validation(true, "", List.copyOf(unique));
    }

    private static AdmissionResult restoreFailure(
            AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
            String message, long nowMs) {
        String reason = message == null || message.isBlank()
                ? "unknown PPQ admission failure" : message;
        if (lobby != null && lobby.active()) lobby.restoreForming(reason, nowMs);
        if (engagement != null) {
            switch (engagement.state()) {
                case LOBBY_FORMING, LOBBY_READY, RESERVING_ENTRY, ACTIVE_EVENT ->
                        engagement.restoreLobby(reason, nowMs);
                default -> engagement.addDiagnostic(reason, nowMs);
            }
        }
        return AdmissionResult.failure(reason);
    }

    private static boolean isAgent(Character character) {
        return character != null
                && AgentRuntimeRegistry.findByAgentCharacterId(character.getId()) != null;
    }

    public record Validation(boolean success, String message, List<Character> members) {
        private static Validation failure(String message) {
            return new Validation(false, message, List.of());
        }
    }

    public record AdmissionResult(boolean success, String message, AgentPpqSession session,
                                  AgentPartyQuestEngagement engagement) {
        public AdmissionResult(boolean success, String message, AgentPpqSession session) {
            this(success, message, session, null);
        }

        private static AdmissionResult failure(String message) {
            return new AdmissionResult(false, message, null, null);
        }
    }
}
