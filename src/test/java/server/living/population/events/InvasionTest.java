package server.living.population.events;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.manipulator.InventoryManipulator;
import config.PopulationConfig;
import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.integration.AgentPresence;
import server.life.Monster;
import server.life.SpawnPoint;
import server.living.population.ChatterEngine;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.CitizenSession.Activity;
import server.living.population.CitizenSessions;
import server.living.population.PersonMemories;
import server.living.population.PersonMemory;
import server.living.population.Population;
import server.living.population.PresenceScheduler;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The invasion (N4.3): who is sent and who leaves, waves only while a player is there, held and overrun, prizes to those
 * who dealt real damage and never meso, the unwatched roll with news only, and no monster outliving it.
 */
class InvasionTest {
    private static final int GATE = 106010000;
    private static final long MINUTE = 60_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private final PlayerStorage storage = mock(PlayerStorage.class);
    private final MapleMap map = mock(MapleMap.class);
    private final EventFakes.Store store = new EventFakes.Store();
    private final List<String> notices = new ArrayList<>();
    private final List<Character> onMap = new ArrayList<>();
    private final List<CitizenSession> sessions = new ArrayList<>();
    private final Map<Integer, Monster> standing = new HashMap<>();
    private final List<Monster> spawned = new ArrayList<>();
    private final Random dice = mock(Random.class);
    private MockedStatic<AgentPotionService> potions;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private MockedStatic<InventoryManipulator> bags;
    private MockedStatic<AgentPresence> agents;
    private WorldEvents events;
    private long now;

    @BeforeEach
    void wire() {
        now = System.currentTimeMillis();
        config.WORLD_EVENTS_ENABLED = true;
        config.WORLD_EVENT_INVASION_ENABLED = true;
        potions = mockStatic(AgentPotionService.class);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        bags = mockStatic(InventoryManipulator.class);
        agents = mockStatic(AgentPresence.class);
        agents.when(() -> AgentPresence.isAgent(any())).thenAnswer(call ->
                call.<Character>getArgument(0).getClient() instanceof BotClient);
        bags.when(() -> InventoryManipulator.checkSpace(any(), anyInt(), anyInt(), any())).thenReturn(true);
        when(population.config()).thenReturn(config);
        when(population.channels()).thenReturn(List.of(1));
        when(population.presence()).thenReturn(presence);
        when(population.memories()).thenReturn(memories);
        when(population.chatter()).thenReturn(chatter);
        when(chatter.compose(any())).thenReturn("here they come!");
        when(presence.sessions()).thenReturn(sessions);
        when(population.map(1, GATE)).thenReturn(map);
        World world = mock(World.class);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(population.gameWorld()).thenReturn(world);
        when(map.getAllPlayers()).thenReturn(onMap);
        SpawnPoint spot = mock(SpawnPoint.class);
        when(spot.getPosition()).thenReturn(new Point(0, 0));
        when(map.getMonsterSpawn()).thenReturn(List.of(spot));
        doAnswer(call -> {
            Monster monster = call.getArgument(0);
            standing.put(monster.getObjectId(), monster);
            return null;
        }).when(map).spawnMonsterOnGroundBelow(any(Monster.class), any(Point.class));
        when(map.getMonsterByOid(anyInt())).thenAnswer(call -> standing.get((int) call.getArgument(0)));
        when(map.removeMonsterWithoutKill(any())).thenAnswer(call -> {
            Monster monster = call.getArgument(0);
            return standing.remove(monster.getObjectId()) != null;
        });
        events = calendar();
    }

    @AfterEach
    void close() {
        potions.close();
        identity.close();
        bags.close();
        agents.close();
    }

    private WorldEvents calendar() {
        WorldEvents made = new WorldEvents(population, store, EventCatalog.load(Path.of("scripts/config/population")),
                ZoneOffset.UTC, dice);
        made.notice = notices::add;
        made.itemNames = id -> "cider";
        made.mobs = this::monster;
        return made;
    }

    private Monster monster(int id) {
        Monster monster = mock(Monster.class);
        int oid = 1000 + spawned.size();
        when(monster.getId()).thenReturn(id);
        when(monster.getObjectId()).thenReturn(oid);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getMaxHp()).thenReturn(500L);
        spawned.add(monster);
        return monster;
    }

    private void kill(Monster monster) {
        when(monster.isAlive()).thenReturn(false);
        standing.remove(monster.getObjectId());
    }

    private void killAll() {
        new ArrayList<>(standing.values()).forEach(this::kill);
    }

    private Character human(int id, String name) {
        Character chr = mock(Character.class);
        when(chr.getClient()).thenReturn(mock(Client.class));
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(storage.getCharacterById(id)).thenReturn(chr);
        return chr;
    }

    private CitizenSession citizen(int id, int level, int mapId, int hpPotions) {
        Citizen record = new Citizen(id, "C" + id, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        CitizenSession session = CitizenSessions.loaded(record, 0L, Long.MAX_VALUE);
        session.entry = mock(AgentRuntimeEntry.class);
        session.mapId = mapId;
        session.activity = Activity.TOWN;
        Character agent = human(id, "C" + id);
        when(agent.getClient()).thenReturn(mock(BotClient.class));
        when(agent.getLevel()).thenReturn(level);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getMap()).thenReturn(map);
        when(agent.isAlive()).thenReturn(true);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(session.entry)).thenReturn(agent);
        potions.when(() -> AgentPotionService.countPotions(agent)).thenReturn(new int[] {hpPotions, 0});
        when(population.citizen(id)).thenReturn(record);
        when(presence.session(id)).thenReturn(session);
        sessions.add(session);
        return session;
    }

    private void arrive(CitizenSession session) {
        Character agent = session.agent();
        when(agent.getMapId()).thenReturn(GATE);
        session.mapId = GATE;
        session.activity = Activity.EVENT;
        onMap.add(agent);
    }

    private void start() {
        String said = events.start("invasion", "henesys", now);
        assertTrue(said.contains("started"), said);
    }

    private void tick(double minutes) {
        events.tick(now + (long) (minutes * MINUTE));
    }

    private String result() {
        return store.rows.getFirst().result();
    }

    @Test
    void itSendsCitizensOfTheRegionInItsBandWithPotionsUpToTheMost() {
        for (int i = 0; i < 15; i++) {
            citizen(100 + i, 30, 100000000, 200);
        }
        citizen(200, 24, 100000000, 200); // under the band
        citizen(201, 51, 100000000, 200); // over it
        citizen(202, 30, 100000000, 49); // under the potion floor
        CitizenSession busy = citizen(203, 30, 100000000, 200);
        when(presence.held(busy)).thenReturn(true);
        start();
        verify(presence, times(12)).travel(any(), eq(Activity.EVENT), eq(GATE), anyLong());
        verify(presence, times(12)).hold(anyInt(), eq(now + 20 * MINUTE + 120_000L));
        for (int id = 200; id <= 203; id++) {
            verify(presence, never()).hold(eq(id), anyLong());
        }
        assertTrue(events.modifier().avoided(GATE, 24));
        assertFalse(events.modifier().avoided(GATE, 25));
    }

    @Test
    void nobodyWatchingNoMonsterStandsAndARollSettlesItWithNewsOnly() {
        CitizenSession kim = citizen(100, 30, 100000000, 200);
        start();
        arrive(kim);
        for (double minute = 1; minute < 20; minute += 1) {
            tick(minute);
        }
        assertTrue(spawned.isEmpty(), "they would not fight back");
        when(dice.nextDouble()).thenReturn(0.2d); // 30 + 5 + 5 %
        tick(20);
        assertTrue(result().startsWith("rolled held"), result());
        assertTrue(notices.getLast().contains("held"));
        verify(kim.agent(), never()).gainFame(anyInt());
        bags.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
        verify(memories, never()).record(anyInt(), anyInt(), any(), anyInt(), any(), anyInt());
        verify(presence).release(100);
        assertEquals(0L, kim.lastPlanAtMs, "it goes on with its day");
        assertFalse(events.modifier().avoided(GATE, 1));
    }

    @Test
    void aLostRollLeavesTheFieldToAvoidForAnHour() {
        start();
        when(dice.nextDouble()).thenReturn(0.99d);
        tick(20);
        assertTrue(result().startsWith("rolled overrun"));
        assertTrue(events.modifier().avoided(GATE, 200), "everyone keeps off it");
        assertTrue(notices.getLast().contains("overrun"));
    }

    @Test
    void aPlayerThereFightsWavesAndThoseWhoDealtRealDamageAreRewarded() {
        CitizenSession kim = citizen(100, 30, 100000000, 200);
        start();
        arrive(kim);
        Character ann = human(1, "Ann");
        Character idle = human(2, "Idle");
        onMap.add(ann);
        onMap.add(idle);
        double minute = 0.4;
        for (int wave = 0; wave < 4; wave++) {
            while (standing.isEmpty()) {
                tick(minute += 1.0 / 3);
            }
            for (Monster monster : standing.values()) {
                verify(monster).disableDrops();
                assertTrue(events.isInvader(monster));
                when(monster.damageBy(1)).thenReturn(400L);
                when(monster.damageBy(100)).thenReturn(100L);
                when(monster.damageBy(2)).thenReturn(wave == 0 ? 1L : 0L);
            }
            assertTrue(events.defends(100));
            killAll();
        }
        while (standing.isEmpty()) {
            tick(minute += 1.0 / 3);
        }
        verify(spawned.getLast()).setStartingHp(500L * 12);
        assertEquals(14 + 16 + 16 + 14 + 1, spawned.size());
        killAll();
        tick(minute + 1.0 / 3);
        assertTrue(result().startsWith("held"), result());
        assertTrue(minute < 19, "before its time");
        verify(ann).gainFame(1);
        verify(kim.agent()).gainFame(1);
        verify(idle, never()).gainFame(anyInt());
        bags.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), times(2));
        verify(ann, never()).gainMeso(anyInt(), anyBoolean());
        verify(memories).record(100, 1, PersonMemory.Kind.DEFENDED, GATE, "Henesys", 1);
        verify(memories, never()).record(eq(100), eq(100), any(), anyInt(), any(), anyInt());
        verify(memories, never()).record(eq(100), eq(2), any(), anyInt(), any(), anyInt());
        verify(ann, times(2)).sendPacket(any(Packet.class)); // the clock, once, and its end
        assertFalse(events.isInvader(spawned.getFirst()));
        assertFalse(events.defends(100));
    }

    @Test
    void whatStandsAtTheEndIsAnOverrunAndGoes() {
        start();
        Character ann = human(1, "Ann");
        onMap.add(ann);
        for (double minute = 0; minute < 20; minute += 1.0 / 3) {
            tick(minute);
        }
        assertFalse(standing.isEmpty());
        assertEquals(61, spawned.size(), "every wave came, beaten or not");
        tick(20);
        assertTrue(result().startsWith("overrun"), result());
        assertTrue(standing.isEmpty());
        verify(ann, never()).gainFame(anyInt());
    }

    @Test
    void theMonstersGoWhenThePlayerLeavesAndTheWaveComesAgainIfTheyComeBack() {
        start();
        Character ann = human(1, "Ann");
        onMap.add(ann);
        tick(1);
        tick(1.5);
        assertEquals(14, standing.size());
        onMap.clear();
        tick(1.8);
        assertEquals(14, standing.size(), "a moment away");
        tick(2.5);
        assertTrue(standing.isEmpty());
        onMap.add(ann);
        tick(5);
        tick(5.5);
        assertEquals(14, standing.size(), "the first wave was not beaten");
    }

    @Test
    void aPlayerComingAtTheVeryEndSeesNoWaves() {
        start();
        onMap.add(human(1, "Ann"));
        tick(18);
        tick(19);
        assertTrue(spawned.isEmpty());
        when(dice.nextDouble()).thenReturn(0.0d);
        tick(20);
        assertTrue(result().startsWith("rolled"));
    }

    @Test
    void aCitizenUnderItsPotionFloorDownOrAwayIsLetGo() {
        CitizenSession dry = citizen(100, 30, 100000000, 200);
        CitizenSession dead = citizen(101, 30, 100000000, 200);
        CitizenSession away = citizen(102, 30, 100000000, 200);
        CitizenSession onTheWay = citizen(103, 30, 100000000, 200);
        start();
        arrive(dry);
        arrive(dead);
        arrive(away);
        tick(1);
        verify(presence, never()).release(anyInt());
        Character dryAgent = dry.agent();
        potions.when(() -> AgentPotionService.countPotions(dryAgent)).thenReturn(new int[] {49, 0});
        when(dead.agent().isAlive()).thenReturn(false);
        when(away.agent().getMapId()).thenReturn(100000000);
        tick(1.5);
        verify(presence).release(100);
        verify(presence).release(101);
        verify(presence).release(102);
        verify(presence, never()).release(103);
        assertEquals(0L, dry.lastPlanAtMs);
        assertFalse(onTheWay.lastPlanAtMs == 0L);
    }

    @Test
    void stoppingShuttingDownAndFailingLeaveNoMonsterAndNobodyHeld() {
        for (String how : List.of("gm", "switch", "shutdown", "failure")) {
            standing.clear();
            onMap.clear();
            sessions.clear();
            store.rows.clear();
            citizen(100, 30, 100000000, 200);
            events = calendar();
            start();
            onMap.add(human(1, "Ann"));
            tick(1);
            tick(1.5);
            assertFalse(standing.isEmpty(), how);
            switch (how) {
                case "gm" -> events.stop("invasion");
                case "switch" -> {
                    config.WORLD_EVENT_INVASION_ENABLED = false;
                    tick(2);
                    config.WORLD_EVENT_INVASION_ENABLED = true;
                }
                case "shutdown" -> events.shutdown();
                default -> {
                    when(map.getMonsterSpawn()).thenThrow(new IllegalStateException("gone"));
                    killAll();
                    tick(2.5);
                }
            }
            assertTrue(standing.isEmpty(), how);
            assertTrue(events.runs().isEmpty(), how);
            assertFalse(events.modifier().avoided(GATE, 1), how);
            assertEquals(how.equals("shutdown") ? "RUNNING" : "CANCELLED", store.rows.getFirst().state(), how);
            org.mockito.Mockito.verify(presence, org.mockito.Mockito.atLeastOnce()).release(100);
            org.mockito.Mockito.clearInvocations(presence);
        }
    }

    @Test
    void aRestartInTheMiddleGoesOnAndOneOverTheEndIsRolled() {
        start();
        events.shutdown();
        WorldEvents again = calendar();
        again.minute(now + 5 * MINUTE);
        onMap.add(human(1, "Ann"));
        again.tick(now + 6 * MINUTE);
        again.tick(now + (long) (6.5 * MINUTE));
        assertEquals(14, standing.size(), "its waves come from the first");
        again.shutdown();
        assertTrue(standing.isEmpty());
        WorldEvents later = calendar();
        when(dice.nextDouble()).thenReturn(0.0d);
        later.minute(now + 21 * MINUTE);
        assertEquals("DONE", store.rows.getFirst().state());
        assertTrue(result().contains("server was down"));
    }

    @Test
    void everySitesMonstersAreOrdinaryWalkersOfItsBand() throws Exception {
        EventCatalog catalog = EventCatalog.load(Path.of("scripts/config/population"));
        assertEquals(5, catalog.sites().size());
        for (EventCatalog.Site site : catalog.sites()) {
            List<Integer> ids = new ArrayList<>(site.waves().stream().map(EventCatalog.Wave::mob).toList());
            ids.add(site.boss());
            for (int id : ids) {
                String img = java.nio.file.Files.readString(Path.of("wz/Mob.wz", String.format("%07d.img.xml", id)));
                assertFalse(img.contains("name=\"boss\" value=\"1\""), site.id() + " " + id);
                assertFalse(img.contains("<imgdir name=\"revive\">"), site.id() + " " + id);
                assertFalse(img.contains("<imgdir name=\"fly\">"), site.id() + " " + id);
                java.util.regex.Matcher level = java.util.regex.Pattern.compile("name=\"level\" value=\"(\\d+)\"").matcher(img);
                assertTrue(level.find());
                assertTrue(Integer.parseInt(level.group(1)) <= site.levelMin() + 3, site.id() + " " + id);
            }
        }
    }
}
