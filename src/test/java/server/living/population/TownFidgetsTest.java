package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.living.population.CitizenSession.Activity;
import server.maps.MapleMap;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Citizens' idle town fidgets (SoloMapling's SocialHotPotatoManager, docs/SOLOMAPLING_PORT.md): nothing while
 * TOWN_FIDGETS_ENABLED is off, only idle townies, and an avatar megaphone fits its four short lines.
 */
class TownFidgetsTest {
    @Test
    void anAvatarMegaphoneLineIsSplitIntoFourShortLinesOnWords() {
        List<String> lines = TownFidgets.lines("happy birthday to my best friend in the whole wide world and everyone else here");
        assertTrue(lines.size() <= 4);
        lines.forEach(line -> assertTrue(line.length() <= 20, line));
        assertEquals("happy birthday to my", lines.get(0));
        assertEquals(List.of("brb"), TownFidgets.lines("brb"));
        assertEquals(List.of("aaaaaaaaaaaaaaaaaaaa"), TownFidgets.lines("aaaaaaaaaaaaaaaaaaaaaaaaa"), "one long word is cut");
    }

    @Test
    void onlyAnIdleTownieFidgets() {
        CitizenSession session = new CitizenSession(new Citizen(5, "Ilo", "evening", 0, 0, "victoria", "hunter",
                "relaxed-v1", "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, ""),
                0L, 24 * 3_600_000L, false);
        Character agent = mock(Character.class);
        when(agent.getMap()).thenReturn(mock(MapleMap.class));
        session.activity = Activity.TOWN;
        assertTrue(TownFidgets.idleInTown(session, agent));
        session.afkUntilMs = Long.MAX_VALUE;
        assertFalse(TownFidgets.idleInTown(session, agent), "away from the keyboard");
        session.afkUntilMs = 0;
        session.activity = Activity.CASINO;
        assertFalse(TownFidgets.idleInTown(session, agent), "dealing at a table is not idling");
        assertFalse(TownFidgets.idleInTown(session, null));
    }

    @Test
    void nothingHappensWhileTheSwitchIsOff() {
        PopulationConfig config = new PopulationConfig();
        Population population = mock(Population.class);
        PresenceScheduler presence = mock(PresenceScheduler.class);
        when(population.config()).thenReturn(config);
        when(population.presence()).thenReturn(presence);
        CitizenSession session = new CitizenSession(new Citizen(5, "Ilo", "evening", 0, 0, "victoria", "hunter",
                "relaxed-v1", "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, ""),
                0L, 24 * 3_600_000L, false);
        session.activity = Activity.TOWN;
        session.entry = mock(AgentRuntimeEntry.class);
        Character agent = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(agent.getMap()).thenReturn(map);
        when(map.getAllPlayers()).thenReturn(List.of(agent, mock(Character.class)));
        when(presence.sessions()).thenReturn(List.of(session));
        try (MockedStatic<AgentRuntimeIdentityRuntime> identity = mockStatic(AgentRuntimeIdentityRuntime.class)) {
            identity.when(() -> AgentRuntimeIdentityRuntime.bot(session.entry)).thenReturn(agent);
            TownFidgets fidgets = new TownFidgets(population);
            for (int i = 0; i < 500; i++) {
                fidgets.tick();
            }
            verify(agent, never()).setChalkboard(any());
            verify(agent, never()).changeFaceExpression(anyInt());
            assertTrue(fidgets.status().contains("off"));
        }
    }
}
