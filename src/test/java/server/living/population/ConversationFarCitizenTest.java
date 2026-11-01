package server.living.population;

import client.Character;
import client.Client;
import config.PopulationConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.integration.AgentPresence;
import server.integration.AgentPresenceProvider;
import server.knowledge.ContentChat;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A line that names a citizen on the map but out of earshot is that citizen's, not the speaker's companion's
 * (2026-10-31 live: "hi Blade520" got "heyy" from Crewfriend's companion Dorran while Blade520 stood across Henesys).
 */
class ConversationFarCitizenTest {
    private static final int MAP = 100_000_000;

    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final Population population = mock(Population.class);
    private Character speaker;
    private Conversation conversation;

    @BeforeEach
    void henesys() {
        when(population.config()).thenReturn(new PopulationConfig());
        PresenceScheduler presence = mock(PresenceScheduler.class, withSettings().stubOnly());
        when(population.presence()).thenReturn(presence);
        ContentChat content = mock(ContentChat.class, withSettings().stubOnly());
        when(content.on(any())).thenReturn(false);
        open(ContentChat.class).when(ContentChat::shared).thenReturn(content);

        MapleMap map = mock(MapleMap.class, withSettings().stubOnly());
        speaker = character(10, "Crewfriend", map, new Point(0, 0));
        Character dorran = character(20, "Dorran", map, new Point(30, 0));
        when(map.getCharacterById(20)).thenReturn(dorran);
        List<PartyCharacter> members = List.of(member(10), member(20));
        Party party = mock(Party.class, withSettings().stubOnly());
        when(party.getMembers()).thenReturn(members);
        when(speaker.getParty()).thenReturn(party);
        AgentPresenceProvider agents = mock(AgentPresenceProvider.class);
        when(agents.isAgent(any())).thenAnswer(call -> call.getArgument(0) == dorran);
        AgentPresence.install(agents);

        Character blade = character(30, "Blade520", map, new Point(2_000, 0)); // across town, far past earshot
        CitizenSession session = new CitizenSession(new Citizen(30, "Blade520", "evening", 0, 0, "victoria", "hunter",
                "relaxed-v1", "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, ""),
                0L, Long.MAX_VALUE, false);
        session.entry = mock(AgentRuntimeEntry.class, withSettings().stubOnly());
        open(AgentRuntimeIdentityRuntime.class).when(() -> AgentRuntimeIdentityRuntime.bot(session.entry)).thenReturn(blade);
        when(presence.sessions()).thenReturn(List.of(session));

        conversation = new Conversation(population);
    }

    @AfterEach
    void close() {
        statics.forEach(MockedStatic::close);
        AgentPresence.install(null);
    }

    @Test
    void aGreetingForACitizenAcrossTownIsNotTheCompanionsLine() {
        assertTrue(conversation.onChat(speaker, "hi Blade520"));
        assertTrue(conversation.onChat(speaker, "thanks blade520!"));
    }

    @Test
    void aLineNamingNoCitizenOrTheCompanionStaysTheCompanions() {
        assertFalse(conversation.onChat(speaker, "hi there"));
        assertFalse(conversation.onChat(speaker, "dorran say hi to blade520"));
        assertFalse(conversation.onChat(speaker, "blade5200 is a cool name")); // inside a longer word, not the name
    }

    @Test
    void theNameIsMatchedAsAWholeWord() {
        assertTrue(Conversation.names("hi blade520!", "Blade520"));
        assertTrue(Conversation.names("blade520", "Blade520"));
        assertFalse(Conversation.names("xblade520 hi", "Blade520"));
        assertFalse(Conversation.names("hi blade5200", "Blade520"));
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    private static Character character(int id, String name, MapleMap map, Point at) {
        Character chr = mock(Character.class, withSettings().stubOnly());
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(MAP);
        when(chr.getPosition()).thenReturn(at);
        Client client = mock(Client.class, withSettings().stubOnly());
        when(client.getChannel()).thenReturn(1);
        when(chr.getClient()).thenReturn(client);
        return chr;
    }

    private static PartyCharacter member(int id) {
        PartyCharacter member = mock(PartyCharacter.class, withSettings().stubOnly());
        when(member.getId()).thenReturn(id);
        return member;
    }
}
