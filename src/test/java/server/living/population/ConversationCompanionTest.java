package server.living.population;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.integration.AgentPresence;
import server.integration.AgentPresenceProvider;
import server.maps.MapleMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationCompanionTest {
    @AfterEach
    void restorePresence() {
        AgentPresence.install(null);
    }

    @Test
    void anAgentPartyMemberOnTheSameMapIsTheSpeakersCompanion() {
        Character player = character(1);
        Character companion = character(2);
        MapleMap map = mock(MapleMap.class);
        when(player.getMap()).thenReturn(map);
        when(map.getCharacterById(2)).thenReturn(companion);
        List<PartyCharacter> members = List.of(member(1), member(2));
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(members);
        when(player.getParty()).thenReturn(party);
        AgentPresenceProvider presence = mock(AgentPresenceProvider.class);
        when(presence.isAgent(any())).thenAnswer(call -> call.getArgument(0) == companion);
        AgentPresence.install(presence);

        assertTrue(Conversation.hasCompanionHere(player));

        when(map.getCharacterById(2)).thenReturn(null);  // the companion is on another map
        assertFalse(Conversation.hasCompanionHere(player));
    }

    @Test
    void aPlayerWithoutAPartyHasNoCompanion() {
        Character player = character(1);
        when(player.getMap()).thenReturn(mock(MapleMap.class));

        assertFalse(Conversation.hasCompanionHere(player));
    }

    private static Character character(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        return chr;
    }

    private static PartyCharacter member(int id) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        return member;
    }
}
