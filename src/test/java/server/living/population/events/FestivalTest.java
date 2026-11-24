package server.living.population.events;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.manipulator.InventoryManipulator;
import config.PopulationConfig;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.living.population.CitizenJumpQuests;
import server.living.population.JumpQuestCourses;
import server.living.population.Population;
import server.living.population.PresenceScheduler;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The festival (N4.4): its town, the table champion, the climb's winner, prizes that are never meso. */
class FestivalTest {
    private static final int HENESYS = 100000000;
    private static final long MINUTE = 60_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final EventFakes.Store store = new EventFakes.Store();
    private final List<String> notices = new ArrayList<>();
    private final PlayerStorage players = mock(PlayerStorage.class);
    private final MapleMap road = mock(MapleMap.class);
    private final List<Character> climbers = new ArrayList<>();
    private MockedStatic<InventoryManipulator> bags;
    private MockedStatic<ItemInformationProvider> items;
    private WorldEvents events;

    @BeforeEach
    void on() {
        config.WORLD_EVENTS_ENABLED = true;
        config.WORLD_EVENT_FESTIVAL_ENABLED = true;
        config.JQ_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.channels()).thenReturn(List.of(1));
        when(population.presence()).thenReturn(mock(PresenceScheduler.class));
        when(population.jumpQuests()).thenReturn(mock(CitizenJumpQuests.class));
        when(population.map(1, JumpQuestCourses.SHIPPED.getFirst().legs().getLast().mapId())).thenReturn(road);
        when(road.getAllPlayers()).thenReturn(climbers);
        World world = mock(World.class);
        when(world.getPlayerStorage()).thenReturn(players);
        when(population.gameWorld()).thenReturn(world);
        bags = mockStatic(InventoryManipulator.class);
        bags.when(() -> InventoryManipulator.checkSpace(any(), anyInt(), anyInt(), any())).thenReturn(true);
        items = mockStatic(ItemInformationProvider.class);
        items.when(ItemInformationProvider::getInstance).thenReturn(mock(ItemInformationProvider.class));
        events = new WorldEvents(population, store, EventCatalog.load(Path.of("scripts/config/population")), ZoneOffset.UTC,
                new Random(1));
        events.notice = notices::add;
    }

    @AfterEach
    void close() {
        bags.close();
        items.close();
    }

    private Character player(int id, String name, boolean bot) {
        Character chr = mock(Character.class);
        Client client = bot ? mock(BotClient.class) : mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        when(chr.getClient()).thenReturn(client);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(players.getCharacterById(id)).thenReturn(chr);
        return chr;
    }

    @Test
    void itsTownHasTheTablesForTheEveningOnly() {
        long now = System.currentTimeMillis();
        assertTrue(events.start("festival", "Henesys", now).contains("started"));
        assertEquals(HENESYS, events.modifier().festivalTown("victoria"));
        assertEquals(0, events.modifier().festivalTown("orbis"));
        events.tick(now + 120 * MINUTE);
        assertEquals(0, events.modifier().festivalTown("victoria"));
        assertEquals("no champion", store.rows.getFirst().result());
    }

    @Test
    void aFestivalNowhereDoesNotStart() {
        assertTrue(events.start("festival", "Nowhere", System.currentTimeMillis()).contains("could not start"));
        assertEquals("CANCELLED", store.rows.getFirst().state());
    }

    @Test
    void theChampionBeatTheMostDifferentPeopleAndGetsAnItemAndFameOnly() {
        long now = System.currentTimeMillis();
        events.start("festival", "Henesys", now);
        Character ann = player(1, "Ann", false);
        Character ben = player(2, "Ben", true);
        for (int i = 0; i < 5; i++) {
            events.played(ben, ann, HENESYS); // the same one over and over
        }
        for (int i = 0; i < 3; i++) {
            events.played(ann, player(10 + i, "C" + i, true), HENESYS);
        }
        events.played(ben, player(20, "D", true), HENESYS);
        events.played(ben, player(21, "E", true), 104000000);
        events.played(ben, player(22, "F", true), 104000000); // elsewhere: no festival's games
        events.tick(now + 120 * MINUTE);
        assertEquals("champion Ann", store.rows.getFirst().result());
        verify(ann).gainFame(2);
        bags.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), times(1));
        verify(ann, never()).gainMeso(anyInt(), anyBoolean());
        verify(ben, never()).gainFame(anyInt());
    }

    @Test
    void theFirstUpTheClimbWinsOnceAndNobodyWaitsAtTheTop() {
        long now = System.currentTimeMillis();
        events.start("festival", "Henesys", now);
        Point top = JumpQuestCourses.SHIPPED.getFirst().legs().getLast().top();
        Character waiting = player(1, "Wes", false);
        when(waiting.getPosition()).thenReturn(new Point(top));
        Character climber = player(2, "Cal", false);
        when(climber.getPosition()).thenReturn(new Point(top.x, top.y + 1500));
        climbers.add(waiting);
        climbers.add(climber);
        events.tick(now + 10 * MINUTE);
        assertEquals(1, notices.size(), "the meet opens a quarter into it");
        events.tick(now + 30 * MINUTE);
        assertTrue(notices.getLast().contains("climb begins"), notices.toString());
        events.tick(now + 31 * MINUTE);
        verify(waiting, never()).gainFame(anyInt());
        when(climber.getPosition()).thenReturn(new Point(top.x + 30, top.y));
        events.tick(now + 32 * MINUTE);
        verify(climber).gainFame(2);
        events.tick(now + 33 * MINUTE);
        verify(climber, times(1)).gainFame(eq(2));
        assertTrue(notices.getLast().contains("Cal wins"));
    }

    @Test
    void aRestartAfterTheMeetHoldsNoSecondOne() {
        long now = System.currentTimeMillis();
        events.start("festival", "Henesys", now);
        events.shutdown();
        WorldEvents again = new WorldEvents(population, store, EventCatalog.load(Path.of("scripts/config/population")),
                ZoneOffset.UTC, new Random(1));
        notices.clear();
        again.notice = notices::add;
        again.minute(now + 40 * MINUTE);
        assertEquals(HENESYS, again.modifier().festivalTown("victoria"), "it goes on");
        again.tick(now + 41 * MINUTE);
        assertFalse(notices.toString().contains("climb begins"));
    }

    @Test
    void everyFestivalTownHasACourseAndAllowsMinigames() {
        EventCatalog catalog = EventCatalog.load(Path.of("scripts/config/population"));
        for (EventCatalog.FestivalTown town : catalog.festivals().towns()) {
            assertFalse(town.courses().isEmpty(), town.name());
            for (String key : town.courses()) {
                assertTrue(JumpQuestCourses.SHIPPED.stream().anyMatch(course -> course.key().equals(key)), key);
            }
            assertTrue(town.name().length() <= 16);
        }
    }
}
