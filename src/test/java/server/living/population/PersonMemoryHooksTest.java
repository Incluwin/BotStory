package server.living.population;

import client.Character;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.living.config.ContentLoader;
import server.living.population.PersonMemory.Kind;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where else a citizen's memories of people come from (docs/CITIZEN_PERSON_MEMORY_PLAN.md): a fame or a defame from a
 * player, a party quest a citizen guest cleared in a player's party (a run citizens lead is theirs to remember), the
 * quest names players use, and the shipped lines for every greeting.
 */
class PersonMemoryHooksTest {
    private static final int PIP = 963;
    private static final int ANN = 10;
    private static final int KERNING = 103_000_000;

    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final CitizenPartyQuests runs = mock(CitizenPartyQuests.class);
    private final Character pip = mock(Character.class);
    private final Character ann = mock(Character.class);
    private MockedStatic<Population> populations;
    private MockedStatic<AgentPresence> presences;

    @BeforeEach
    void wire() {
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        presences = mockStatic(AgentPresence.class);
        presences.when(() -> AgentPresence.isAgent(pip)).thenReturn(true);
        when(population.presence()).thenReturn(presence);
        when(population.memories()).thenReturn(memories);
        when(population.partyQuests()).thenReturn(runs);
        when(pip.getId()).thenReturn(PIP);
        when(pip.getMapId()).thenReturn(KERNING);
        when(ann.getId()).thenReturn(ANN);
    }

    @AfterEach
    void close() {
        presences.close();
        populations.close();
    }

    /** Pip is a citizen online (a real session: its id is a final field). */
    private void sessionIs(int id) {
        when(presence.sessionOf(pip)).thenReturn(new CitizenSession(new Citizen(id, "Pip", "evening", 0, 0, "victoria",
                "hunter", "relaxed-v1", "default", 1.0d, 0, 1, new java.util.LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L,
                0L, 0L, 0L, 0, ""), 0L, 1L, false));
    }

    @Test
    void aFameOrADefameFromAPlayerIsRememberedAndAnotherBotsIsNot() {
        sessionIs(PIP);

        PopulationHooks.famed(ann, pip, 1);
        PopulationHooks.famed(ann, pip, -1);
        PopulationHooks.famed(pip, pip, 1);

        verify(memories).record(PIP, ANN, Kind.FAMED, KERNING, "", 1);
        verify(memories).record(PIP, ANN, Kind.DEFAMED, KERNING, "", 1);
        verify(memories, never()).record(PIP, PIP, Kind.FAMED, KERNING, "", 1);
    }

    @Test
    void aGuestClearingAPlayersPartyQuestRemembersItAndARunCitizensLeadIsTheirs() {
        sessionIs(PIP);
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(5);
        when(pip.getParty()).thenReturn(party);
        when(ann.getParty()).thenReturn(party);

        PopulationHooks.partyQuestCleared(List.of(ann, pip), "KerningPQ");
        verify(memories).record(PIP, ANN, Kind.PQ_CLEAR, 0, "kpq", 1);

        when(runs.holds(PIP)).thenReturn(true);
        PopulationHooks.partyQuestCleared(List.of(ann, pip), "HenesysPQ");
        verify(memories, never()).record(PIP, ANN, Kind.PQ_CLEAR, 0, "hpq", 1);
        verify(memories, times(1)).record(anyInt(), anyInt(), any(), anyInt(), anyString(), anyInt());
    }

    @Test
    void questsGoByThePlayersNames() {
        assertEquals("kpq", PopulationHooks.questName("KerningPQ"));
        assertEquals("mpq", PopulationHooks.questName("MagatiaPQ_Z"));
        assertEquals("treasurepq", PopulationHooks.questName("TreasurePQ"));
        assertEquals("cafepq 1", PopulationHooks.questName("CafePQ_1"));
        assertEquals("", PopulationHooks.questName(null));
    }

    @Test
    void everyGreetingHasShippedLinesAndTheLintSaysSo() {
        PopulationContent content = PopulationLoader.load(Path.of("scripts/config/population"),
                ContentLoader.load(Path.of("scripts/config/living-world")));

        for (String trigger : PopulationLoader.MEMORY_TRIGGERS) {
            assertTrue(content.intents(trigger).stream().anyMatch(intent -> intent.channel().equals("map")), trigger);
        }
        assertTrue(content.warnings().stream().noneMatch(warning -> warning.contains("citizens never say it")),
                String.valueOf(content.warnings()));
    }
}
