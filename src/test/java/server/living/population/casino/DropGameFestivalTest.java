package server.living.population.casino;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.population.events.WorldEvents;
import server.living.population.events.WorldModifier;

import java.awt.Point;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The drop game's host post at a festival (docs/WORLD_SYSTEMS_PLAN.md N4.4): there for its time, and back after. */
class DropGameFestivalTest {
    private static final int HOME = 103000000;
    private static final int ELLINIA = 101000000;

    private final CasinoHost host = mock(CasinoHost.class);
    private final CasinoContent content = mock(CasinoContent.class);
    private final Population population = mock(Population.class);
    private final WorldEvents events = mock(WorldEvents.class);
    private final PopulationConfig config = new PopulationConfig();
    private final WorldModifier festival = WorldModifier.NONE.with(
            WorldModifier.Effect.festival("f", "victoria", ELLINIA, 4.0d, Long.MAX_VALUE));
    private DropGame game;

    @BeforeEach
    void wire() {
        config.DROP_GAME_ENABLED = true;
        when(host.population()).thenReturn(population);
        when(host.random()).thenReturn(new Random(1));
        when(population.config()).thenReturn(config);
        when(population.channels()).thenReturn(java.util.List.of(1));
        when(population.worldEvents()).thenReturn(events);
        when(events.modifier()).thenReturn(WorldModifier.NONE);
        when(content.dropGameMap()).thenReturn(HOME);
        when(content.dropHost()).thenReturn(new Point(10, 20));
        game = new DropGame(host, content);
    }

    private CitizenSession session(int mapId) {
        CitizenSession session = mock(CitizenSession.class);
        Character agent = mock(Character.class);
        when(session.agent()).thenReturn(agent);
        when(agent.getMapId()).thenReturn(mapId);
        return session;
    }

    @Test
    void theVenueIsTheFestivalTownForItsTime() {
        assertEquals(HOME, game.mapId());
        when(events.modifier()).thenReturn(festival);
        assertEquals(ELLINIA, game.mapId());
        when(events.modifier()).thenReturn(festival.without("f"));
        assertEquals(HOME, game.mapId());
        assertEquals(new Point(10, 20), game.spot());
    }

    @Test
    void aHostWithNobodyPlayingLeavesForTheFestivalAndAGameUnderWayFinishesWhereItIs() {
        DropGame.DropPost idle = game.open(session(HOME), Long.MAX_VALUE);
        DropGame.DropPost busy = game.open(session(HOME), Long.MAX_VALUE);
        busy.stage = DropGame.Stage.GAME;
        game.posts.put(0, idle); // the mock session's channel
        game.posts.put(2, busy);
        when(events.modifier()).thenReturn(festival);
        game.step(idle, 0L);
        game.step(busy, 0L);
        assertFalse(game.posts.containsValue(idle));
        assertTrue(game.posts.containsValue(busy));
        assertEquals(HOME, game.mapId(busy), "the player and its host are still at home");
        assertEquals(ELLINIA, game.mapId(game.open(session(ELLINIA), Long.MAX_VALUE)));
    }
}
