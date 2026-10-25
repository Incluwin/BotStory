package server.agents.capabilities.partyquest.apq;

import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyIntent;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyProfile;

import java.util.ArrayList;
import java.util.List;

/** APQ vocabulary and recruit-map geometry for the shared lobby a companion admission forms. */
public final class AgentApqLobbyProfile {
    private static final AgentPartyQuestLobbyProfile PROFILE = new AgentPartyQuestLobbyProfile(
            "apq", AgentApqDefinition.RECRUIT_MAP, AgentApqDefinition.ENTRY_NPC,
            AgentApqDefinition.MIN_LEVEL, AgentApqDefinition.MAX_LEVEL, AgentApqDefinition.PARTY_SIZE, -140, 140,
            phrases(), List.of(), List.of("Looking for an Amorian Challenge party.", "APQ party forming."), 900L,
            2_400L);

    private AgentApqLobbyProfile() {
    }

    public static AgentPartyQuestLobbyProfile profile() {
        return PROFILE;
    }

    private static List<AgentPartyQuestLobbyProfile.Phrase> phrases() {
        List<AgentPartyQuestLobbyProfile.Phrase> result = new ArrayList<>();
        for (String name : List.of("apq", "amoria pq", "amorian challenge")) {
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
