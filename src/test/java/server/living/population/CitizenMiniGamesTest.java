package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.PopulationContent.Region;
import server.maps.MapleMap;
import server.maps.MiniGame.MiniGameResult;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which citizens get a PLAY plan and where: only with the kill switch on, only in a MINIGAME_TOWNS town of their own
 * region, never on a map whose fieldLimit forbids minigames (the Free Market's CANNOTMINIGAME); and how well they play.
 */
class CitizenMiniGamesTest {
    private static final int HENESYS = 100_000_000;
    private static final int LITH_HARBOR = 104_000_000;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final FieldCatalog fields = mock(FieldCatalog.class);
    private final MapleMap henesys = mock(MapleMap.class);
    private Citizen citizen;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.fields()).thenReturn(fields);
        when(population.map(anyInt(), eq(HENESYS))).thenReturn(henesys);
        when(population.map(anyInt(), eq(LITH_HARBOR))).thenReturn(mock(MapleMap.class));
        citizen = new Citizen(981, "CardCam", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        when(fields.regionOf(any())).thenReturn(new Region("victoria", 1, 45, List.of(HENESYS, LITH_HARBOR), List.of(),
                List.of(), 910000000, 1));
    }

    @Test
    void playIsOffUntilTheKillSwitchIsOn() {
        CitizenMiniGames games = new CitizenMiniGames(population, (delay, step) -> { });
        assertFalse(games.plays(citizen, 0L));
        config.MINIGAMES_ENABLED = true;
        assertTrue(games.plays(citizen, 0L));
        assertEquals(HENESYS, games.townFor(citizen), "Lith Harbor is not a minigame town");
    }

    @Test
    void aTownWhoseMapForbidsMinigamesIsNeverChosen() {
        config.MINIGAMES_ENABLED = true;
        when(henesys.getFieldLimit()).thenReturn(0x80);
        CitizenMiniGames games = new CitizenMiniGames(population, (delay, step) -> { });
        assertFalse(games.plays(citizen, 0L));
        assertEquals(0, games.townFor(citizen));
    }

    @Test
    void aCitizenOfARegionWithNoMinigameTownNeverPlays() {
        config.MINIGAMES_ENABLED = true;
        when(fields.regionOf(any())).thenReturn(new Region("orbis", 40, 70, List.of(200_000_000), List.of(), List.of(),
                910000000, 1));
        assertFalse(new CitizenMiniGames(population, (delay, step) -> { }).plays(citizen, 0L));
    }

    @Test
    void fewCitizensPlayWellAndPracticeHelps() {
        CitizenMiniGames games = new CitizenMiniGames(population, (delay, step) -> { });
        int strong = 0;
        double total = 0;
        for (int id = 1; id <= 2_000; id++) {
            double strength = games.strength(agent(id, 0));
            assertTrue(strength >= 0.1d && strength <= 1.0d);
            total += strength;
            if (strength >= 0.8d) {
                strong++;
            }
        }
        assertTrue(strong > 40 && strong < 200, strong + " strong players of 2000");
        assertTrue(total / 2_000 < 0.4d, "most play casually");
        assertTrue(games.strength(agent(77, 200)) > games.strength(agent(77, 0)), "a citizen with a record plays better");
    }

    private static Character agent(int id, int played) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        when(agent.getMiniGamePoints(eq(MiniGameResult.WIN), anyBoolean())).thenReturn(played);
        return agent;
    }
}
