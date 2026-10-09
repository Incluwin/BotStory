package server.agents.capabilities.partyquest.rjpq;

import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyIntent;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyProfile;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** RJPQ's lobby vocabulary for the shared lobby a companion admit builds: one profile per side, four members. */
public final class AgentRjpqLobbyProfile {
    private static final Map<AgentRjpqSide, AgentPartyQuestLobbyProfile> PROFILES = profiles();

    private AgentRjpqLobbyProfile() {
    }

    public static AgentPartyQuestLobbyProfile profile(AgentRjpqSide side) {
        return PROFILES.get(side);
    }

    private static Map<AgentRjpqSide, AgentPartyQuestLobbyProfile> profiles() {
        Map<AgentRjpqSide, AgentPartyQuestLobbyProfile> profiles = new EnumMap<>(AgentRjpqSide.class);
        for (AgentRjpqSide side : AgentRjpqSide.values()) {
            profiles.put(side, new AgentPartyQuestLobbyProfile(
                    side.key(), side.recruitMapId(), side.entryNpcId(),
                    AgentRjpqSide.MIN_LEVEL, AgentRjpqSide.MAX_LEVEL, AgentRjpqSide.PARTY_SIZE, -100, 100,
                    phrases(), List.of(),
                    List.of("Looking for a Romeo and Juliet party.", "Magatia party forming."),
                    900L, 2_400L));
        }
        return Map.copyOf(profiles);
    }

    private static List<AgentPartyQuestLobbyProfile.Phrase> phrases() {
        List<AgentPartyQuestLobbyProfile.Phrase> result = new ArrayList<>();
        for (String name : List.of("rjpq", "romeo and juliet pq", "magatia pq")) {
            result.add(new AgentPartyQuestLobbyProfile.Phrase(
                    AgentPartyQuestLobbyIntent.RECRUITING_MEMBERS, "recruiting for " + name));
            result.add(new AgentPartyQuestLobbyProfile.Phrase(
                    AgentPartyQuestLobbyIntent.RECRUITING_MEMBERS, "lfm " + name));
            result.add(new AgentPartyQuestLobbyProfile.Phrase(
                    AgentPartyQuestLobbyIntent.REQUEST_TO_JOIN, "looking for " + name));
            result.add(new AgentPartyQuestLobbyProfile.Phrase(
                    AgentPartyQuestLobbyIntent.REQUEST_TO_JOIN, "invite me " + name));
        }
        return List.copyOf(result);
    }
}
