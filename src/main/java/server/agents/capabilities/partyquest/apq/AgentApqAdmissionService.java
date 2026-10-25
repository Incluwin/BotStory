package server.agents.capabilities.partyquest.apq;

import client.Character;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The public companion admit into the Amorian Challenge (APQ plan A3): a human leader's own party of six on Amos's map
 * becomes a companion run (Amos's desk, and '!apqtest party prod'). It checks what AmoriaPQ.js getEligibleParty checks,
 * in admission's own words, so the desk can say it before the stock start would refuse it silently: exactly six, all
 * on Amos's map, Lv40 and up, at least one of each gender, and every member married (decision 1: the stock rule
 * stands). Then the engagement, each companion leaving its activity, a formed lobby, and the session published.
 */
public final class AgentApqAdmissionService {
    private static final Logger log = LoggerFactory.getLogger(AgentApqAdmissionService.class);

    private AgentApqAdmissionService() {
    }

    public static AdmissionResult admit(Character operator, Character eventLeader, List<Character> members, long seed,
                                        long nowMs) {
        Validation validation = validate(operator, eventLeader, members);
        if (!validation.success()) return AdmissionResult.failure(validation.message());
        List<Character> agents = validation.members().stream().filter(AgentApqAdmissionService::isAgent).toList();
        String blocker = AgentApqSessionRegistry.registrationBlocker(
                operator.getId(), validation.members().stream().map(Character::getId).toList());
        if (!blocker.isEmpty()) return AdmissionResult.failure("APQ admission is already reserved: " + blocker);
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "apq", AgentPartyQuestEngagement.Mode.PRODUCTION, seed, operator.getId(),
                validation.members().size(), nowMs);
        AgentPartyQuestLobbySession lobby = null;
        try {
            AgentPartyQuestEngagementRegistry.register(engagement);
            for (Character agent : agents) {
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(agent.getId());
                if (entry == null || !AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, agent,
                        "admitted to a companion APQ run", nowMs)) {
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
                    engagement.engagementId(), AgentApqLobbyProfile.profile(), seed, operator.getId(),
                    validation.members().size(), AgentPartyQuestCandidateScope.ANY_ELIGIBLE_HUMAN, nowMs);
            for (Character member : validation.members()) {
                lobby.addMember(member.getId(), isAgent(member)
                                ? AgentPartyQuestLobbySession.MemberType.AGENT
                                : AgentPartyQuestLobbySession.MemberType.HUMAN,
                        AgentPartyQuestLobbySession.MemberRole.JOINED_MEMBER, nowMs);
            }
            lobby.setCoordinatorAgentId(agents.getFirst().getId());
            AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(eventLeader);
            Set<Integer> roster = validation.members().stream().map(Character::getId).collect(Collectors.toSet());
            lobby.reconcileParty(party.id(), eventLeader.getId(), roster, nowMs);
            lobby.markReady(nowMs);
            engagement.beginLobby(lobby.lobbyId(), nowMs);
            engagement.lobbyReady(nowMs);
            AgentPartyQuestLobbyRuntime.register(lobby, nowMs);
            AdmissionResult result = publish(engagement, lobby, operator, eventLeader, validation.members(), seed,
                    nowMs);
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

    /**
     * Why this party member cannot enter APQ from Amos's map, in admission's own words ("<name> is not at APQ",
     * "<name> must be level 40-255", "<name> is not married"), or "" when it can (AmoriaPQ.js getEligibleParty).
     */
    public static String memberProblem(Character member) {
        if (member == null) return "";
        if (member.getMapId() != AgentApqDefinition.RECRUIT_MAP) return member.getName() + " is not at APQ";
        if (member.getLevel() < AgentApqDefinition.MIN_LEVEL || member.getLevel() > AgentApqDefinition.MAX_LEVEL) {
            return member.getName() + " must be level " + AgentApqDefinition.MIN_LEVEL + '-'
                    + AgentApqDefinition.MAX_LEVEL;
        }
        if (!member.isMarried()) return member.getName() + " is not married";
        return "";
    }

    /** What the party lacks between its members, in admission's words; "" when it has both genders. */
    public static String genderProblem(List<Integer> genders) {
        boolean boy = genders.contains(0);
        boolean girl = genders.contains(1);
        if (boy && girl) return "";
        return boy ? "The APQ party needs a woman" : "The APQ party needs a man";
    }

    private static AdmissionResult publish(AgentPartyQuestEngagement engagement, AgentPartyQuestLobbySession lobby,
                                           Character operator, Character eventLeader, List<Character> members,
                                           long seed, long nowMs) {
        AgentApqSession session = new AgentApqSession(seed, operator.getId(), nowMs);
        members.forEach(member -> session.addMember(member.getId(), isAgent(member)
                ? AgentApqMemberState.MemberType.AGENT : AgentApqMemberState.MemberType.HUMAN, member.getGender()));
        int executor = members.stream().filter(AgentApqAdmissionService::isAgent).mapToInt(Character::getId)
                .findFirst().orElseThrow();
        session.setLeadership(eventLeader.getId(), executor);
        boolean published = false;
        try {
            lobby.reserve(nowMs);
            engagement.reserveEntry(nowMs);
            AgentApqSessionRegistry.registerComplete(session);
            published = true;
            lobby.beginHandoff(nowMs);
            engagement.activateSession(session.sessionId(), nowMs);
            AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), nowMs);
            log.info("APQ session admitted: session={} operator={} leader={} members={} companionRun=true",
                    session.sessionId(), operator.getId(), eventLeader.getId(), session.memberCount());
            return new AdmissionResult(true, "APQ party admitted", session, engagement);
        } catch (RuntimeException failure) {
            if (published) AgentApqSessionRegistry.remove(session);
            String reason = failure.getMessage() == null ? "unknown APQ admission failure" : failure.getMessage();
            if (lobby.active()) lobby.restoreForming(reason, nowMs);
            return AdmissionResult.failure(reason);
        }
    }

    /** AmoriaPQ.js's rules for a human-led party with at least one companion. */
    static Validation validate(Character operator, Character eventLeader, List<Character> partyMembers) {
        if (operator == null || eventLeader == null || partyMembers == null) {
            return Validation.failure("Operator, leader, and party members are required");
        }
        List<Character> unique = new ArrayList<>();
        unique.add(eventLeader);
        partyMembers.stream().filter(Objects::nonNull).forEach(member -> {
            if (unique.stream().noneMatch(existing -> existing.getId() == member.getId())) unique.add(member);
        });
        if (unique.size() != AgentApqDefinition.PARTY_SIZE) {
            return Validation.failure("The Amorian Challenge takes exactly six members");
        }
        if (isAgent(eventLeader)) return Validation.failure("An APQ companion run is led by a player");
        if (unique.stream().noneMatch(AgentApqAdmissionService::isAgent)) {
            return Validation.failure("An APQ companion run needs a companion");
        }
        AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(eventLeader);
        if (party == null) return Validation.failure("The APQ leader has no party");
        Set<Integer> requested = unique.stream().map(Character::getId).collect(Collectors.toSet());
        Set<Integer> actual = party.members().stream().filter(Objects::nonNull).map(AgentPartyMemberSnapshot::id)
                .collect(Collectors.toSet());
        if (!requested.equals(actual)) return Validation.failure("Every APQ member must be online");
        if (party.members().stream().noneMatch(member -> member != null
                && member.id() == eventLeader.getId() && member.leader())) {
            return Validation.failure("The selected APQ event leader is not party leader");
        }
        int world = AgentClientGatewayRuntime.clients().world(eventLeader);
        int channel = AgentClientGatewayRuntime.clients().channel(eventLeader);
        for (Character member : unique) {
            AgentPartySnapshot memberParty = AgentPartyGatewayRuntime.party().snapshot(member);
            if (memberParty == null || memberParty.id() != party.id()) {
                return Validation.failure("Every APQ member must share the party");
            }
            String problem = memberProblem(member);
            if (!problem.isEmpty()) return Validation.failure(problem);
            if (AgentClientGatewayRuntime.clients().world(member) != world
                    || AgentClientGatewayRuntime.clients().channel(member) != channel) {
                return Validation.failure("Every APQ member must share world and channel");
            }
        }
        String genders = genderProblem(unique.stream().map(Character::getGender).toList());
        if (!genders.isEmpty()) return Validation.failure(genders);
        return new Validation(true, "", List.copyOf(unique));
    }

    private static boolean isAgent(Character character) {
        return character != null && AgentRuntimeRegistry.findByAgentCharacterId(character.getId()) != null;
    }

    public record Validation(boolean success, String message, List<Character> members) {
        private static Validation failure(String message) {
            return new Validation(false, message, List.of());
        }
    }

    public record AdmissionResult(boolean success, String message, AgentApqSession session,
                                  AgentPartyQuestEngagement engagement) {
        private static AdmissionResult failure(String message) {
            return new AdmissionResult(false, message, null, null);
        }
    }
}
