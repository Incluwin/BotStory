package server.agents.capabilities.mobcontrol;

import client.BotClient;
import client.Character;
import client.Client;
import config.AgentYamlConfig;
import net.server.Server;
import net.server.services.task.channel.ServerMobAutonomyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.TimerManager;
import server.agents.capabilities.expedition.boss.AgentBossRunHost;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * G2, the control-steal block (P8 step 4). Every Zakum part (8800000-8800010) is a boss that a human client moves. A
 * bot's hit must never hand a part to Agent physics (MobPhysicsService acquire sends STOP to the human, and the arm
 * stops attacking), and a far bot hit must not raise DISTANCE_HACK (each alert resends the part's position). The scope
 * is a boss in an event of a listed expedition type (ZAKUM), a listed id on a map with no event (none by default), or
 * the GM trial map. Server-owned actors (Easy Balrog, the PQ bosses, the Balrog adds after acquire) and a part an Agent
 * already controls are never guarded. ENABLED false guards nothing.
 */
class AgentBossScopeTest {
    private static final String PREFIX = "server.agents.capabilities.mobcontrol.AgentBossScope.";
    private static final String ENABLED = PREFIX + "ENABLED";
    private static final String EXPEDITION_TYPES = PREFIX + "EXPEDITION_TYPES";
    private static final String BOSS_IDS = PREFIX + "BOSS_IDS";
    private static final int BOSSFRIEND_ID = 42_000_081;
    private static final List<Integer> ZAKUM_PARTS = IntStream.rangeClosed(8_800_000, 8_800_010).boxed().toList();
    /** KPQ King Slime, LPQ Alishar, OPQ Papa Pixie, EPQ Poison Golems, PPQ Lord Pirates. */
    private static final List<Integer> PARTY_QUEST_BOSSES = List.of(9_300_003, 9_300_012, 9_300_039,
            9_300_180, 9_300_181, 9_300_182, 9_300_105, 9_300_106, 9_300_107, 9_300_119);
    /** Every boss template with a server behaviour (ServerMobBehaviorRegistry) among the bosses named above. */
    private static final List<Integer> REGISTERED_ACTORS = List.of(9_300_012, 9_300_039,
            9_300_180, 9_300_181, 9_300_182, 9_300_105, 9_300_106, 9_300_107, 9_300_119,
            8_830_007, 8_830_008, 8_830_009);
    private static final List<Integer> BALROG_ADDS = List.of(6_400_008, 6_400_009);

    private final Map<String, String> shipped = new HashMap<>();
    private MockedStatic<TimerManager> timers;
    private MockedStatic<Server> servers;
    private ServerMobAutonomyService autonomy;

    @BeforeEach
    void setUp() {
        for (String key : List.of(ENABLED, EXPEDITION_TYPES, BOSS_IDS)) {
            shipped.put(key, AgentYamlConfig.config.tuning.get(key));
        }
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
        servers = mockStatic(Server.class);
        servers.when(Server::getInstance).thenReturn(mock(Server.class));
        autonomy = new ServerMobAutonomyService();
    }

    @AfterEach
    void tearDown() {
        AgentBossScope.endTrial();
        autonomy.dispose();
        servers.close();
        timers.close();
        shipped.forEach((key, value) -> {
            if (value == null) {
                AgentYamlConfig.config.tuning.remove(key);
            } else {
                AgentYamlConfig.config.tuning.put(key, value);
            }
        });
    }

    @Test
    void theYamlShipsTheScopeOnForZakumAndScargaWithNoListedIds() {
        assertEquals("true", shipped.get(ENABLED));
        assertEquals("ZAKUM,SCARGA", shipped.get(EXPEDITION_TYPES), "P17 adds SCARGA");
        assertEquals("none", shipped.get(BOSS_IDS));
    }

    @Test
    void everyZakumPartInAZakumEventIsGuarded() {
        MapleMap altar = eventMap(ExpeditionType.ZAKUM);
        Character bossfriend = human(altar);
        for (int id : ZAKUM_PARTS) {
            assertTrue(AgentBossScope.guards(boss(id, altar, bossfriend)), id + " with a human controller");
            assertTrue(AgentBossScope.guards(boss(id, altar, null)), id + " before anyone controls it");
        }
    }

    @Test
    void aZakumPartWithNoEventIsNotGuarded() {
        MapleMap field = mock(MapleMap.class);
        assertFalse(AgentBossScope.guards(boss(8_800_003, field, human(field))));
    }

    @Test
    void anExpeditionTypeThatIsNotListedIsNotGuarded() {
        MapleMap balrogBattle = eventMap(ExpeditionType.BALROG_EASY);
        Character bossfriend = human(balrogBattle);
        for (int id : List.of(8_830_007, 8_830_008, 8_830_009)) {
            // a fight with a native-capable client: the part is not a server-owned actor
            assertFalse(AgentBossScope.guards(boss(id, balrogBattle, bossfriend)), id + " in a BALROG_EASY event");
        }
        MapleMap horntailCave = eventMap(ExpeditionType.HORNTAIL);
        assertFalse(AgentBossScope.guards(boss(8_810_002, horntailCave, human(horntailCave))),
                "Horntail is not listed");
    }

    @Test
    void partyQuestBossesInTheirOwnEventsAreNeverGuarded() {
        MapleMap pqStage = eventMap(null);
        for (int id : PARTY_QUEST_BOSSES) {
            assertFalse(AgentBossScope.guards(boss(id, pqStage, human(pqStage))), id + " in a PQ event");
        }
    }

    @Test
    void registeredServerActorsAreNeverGuardedEvenInAListedExpedition() {
        AgentYamlConfig.config.tuning.put(EXPEDITION_TYPES, "BALROG_EASY,ZAKUM");
        MapleMap battle = eventMap(ExpeditionType.BALROG_EASY);
        for (int id : REGISTERED_ACTORS) {
            Monster actor = boss(id, battle, null);
            assertTrue(AgentBossScope.guards(actor), id + " fixture: in scope until the server owns it");

            autonomy.registerEncounterActor(actor, new Object(), true);

            assertTrue(ServerMobAutonomyService.isActiveInstance(actor), id + " fixture: a server-owned actor");
            assertFalse(AgentBossScope.guards(actor), id + " once registered");
        }
    }

    @Test
    void balrogAddsAreNeverGuardedAfterTheServerAcquiresThem() {
        AgentYamlConfig.config.tuning.put(EXPEDITION_TYPES, "BALROG_EASY,ZAKUM");
        MapleMap battle = eventMap(ExpeditionType.BALROG_EASY);
        Character thane = bot(battle);
        for (int id : BALROG_ADDS) {
            Monster add = boss(id, battle, null);
            assertTrue(AgentBossScope.guards(add), id + " fixture: in scope until the server owns it");

            assertTrue(autonomy.acquire(add, thane), id + " fixture: the server acquires the add");

            assertFalse(AgentBossScope.guards(add), id + " after acquire");
        }
    }

    @Test
    void aPartAnAgentAlreadyControlsIsNotGuarded() {
        MapleMap altar = eventMap(ExpeditionType.ZAKUM);
        assertFalse(AgentBossScope.guards(boss(8_800_003, altar, bot(altar))));
    }

    @Test
    void aMonsterThatIsNotABossIsNotGuardedInAZakumEvent() {
        MapleMap altar = eventMap(ExpeditionType.ZAKUM);
        Monster add = boss(8_800_117, altar, null);
        when(add.isBoss()).thenReturn(false);
        assertFalse(AgentBossScope.guards(add));
    }

    @Test
    void listedBossIdsAreGuardedOnlyOnAMapWithNoEvent() {
        AgentYamlConfig.config.tuning.put(BOSS_IDS, "8800003-8800010,8810002");
        MapleMap field = mock(MapleMap.class);
        Character bossfriend = human(field);
        assertTrue(AgentBossScope.guards(boss(8_800_003, field, bossfriend)), "the first id of the range");
        assertTrue(AgentBossScope.guards(boss(8_800_010, field, bossfriend)), "the last id of the range");
        assertTrue(AgentBossScope.guards(boss(8_810_002, field, bossfriend)), "a single id");
        assertFalse(AgentBossScope.guards(boss(8_800_011, field, bossfriend)), "past the range");
        assertFalse(AgentBossScope.guards(boss(8_800_002, field, bossfriend)), "before the range");

        MapleMap pqStage = eventMap(null);
        assertFalse(AgentBossScope.guards(boss(8_800_003, pqStage, human(pqStage))), "a listed id inside an event");
    }

    @Test
    void aFieldGroupsBossIsGuardedOnAFieldOnlyWhileItsKeyIsOn() {
        // P13: Jr. Balrog is in field_victoria (boss-rules.json); BOSS_IDS stays none
        boolean[] keyOn = {false};
        AgentBossRunHost.install(new AgentBossRunHost.Rule() {
            @Override
            public boolean seat(AgentBossRunHost.Seat seat) {
                return true;
            }

            @Override
            public boolean bossEnabled(String key) {
                return keyOn[0] && key.equals("field_victoria");
            }
        });
        try {
            MapleMap field = mock(MapleMap.class);
            Character bossfriend = human(field);
            Monster jrBalrog = boss(8_130_100, field, bossfriend);
            assertFalse(AgentBossScope.guards(jrBalrog), "field_victoria is off");

            keyOn[0] = true;
            assertTrue(AgentBossScope.guards(jrBalrog));
            assertFalse(AgentBossScope.guards(boss(7_220_000, field, bossfriend)), "Tae Roon: field_mulung is off");
            MapleMap pqStage = eventMap(null);
            assertFalse(AgentBossScope.guards(boss(8_130_100, pqStage, human(pqStage))), "inside an event");
        } finally {
            AgentBossRunHost.install(null);
        }
    }

    @Test
    void aPartyBossesPartIsGuardedInsideItsOpenRunOnlyNeverOnAField() {
        // P14: Latanica's add 9420511 roams Singapore's fields too; BOSS_IDS stays none
        MapleMap battle = eventMap(null);
        EventInstanceManager latanica = battle.getEventInstance();
        MapleMap field = mock(MapleMap.class);
        try (MockedStatic<AgentBossRunRegistry> runs = mockStatic(AgentBossRunRegistry.class)) {
            runs.when(() -> AgentBossRunRegistry.partyBossPart(latanica, 9_420_511)).thenReturn(true);

            assertTrue(AgentBossScope.guards(boss(9_420_511, battle, human(battle))), "in its run");
            assertFalse(AgentBossScope.guards(boss(9_420_511, field, human(field))), "on a field map");
            assertFalse(AgentBossScope.guards(boss(9_420_512, battle, human(battle))), "a part its rules do not name");
        }
    }

    @Test
    void theTrialGuardsOnlyTheTrialMap() {
        MapleMap trialField = mock(MapleMap.class);
        MapleMap otherField = mock(MapleMap.class);
        Character bossfriend = human(trialField);
        Monster arm = boss(8_800_010, trialField, bossfriend);
        Monster elsewhere = boss(8_800_010, otherField, human(otherField));
        assertFalse(AgentBossScope.guards(arm), "fixture: no trial yet");
        assertEquals(0, AgentBossScope.trialGm());
        assertNull(AgentBossScope.trialMap());

        AgentBossScope.startTrial(BOSSFRIEND_ID, trialField);

        assertTrue(AgentBossScope.guards(arm), "a part on the trial map");
        assertFalse(AgentBossScope.guards(elsewhere), "the same part on another map");
        assertFalse(AgentBossScope.guards(boss(8_800_010, trialField, bot(trialField))),
                "a part an Agent already controls, even on the trial map");
        assertEquals(BOSSFRIEND_ID, AgentBossScope.trialGm());
        assertSame(trialField, AgentBossScope.trialMap());

        AgentBossScope.endTrial();

        assertFalse(AgentBossScope.guards(arm), "the trial is over");
        assertEquals(0, AgentBossScope.trialGm());
        assertNull(AgentBossScope.trialMap());
    }

    @Test
    void aTrialNeedsAGmAndAMap() {
        assertThrows(IllegalArgumentException.class, () -> AgentBossScope.startTrial(0, mock(MapleMap.class)));
        assertThrows(IllegalArgumentException.class, () -> AgentBossScope.startTrial(BOSSFRIEND_ID, null));
        assertEquals(0, AgentBossScope.trialGm());
    }

    @Test
    void withEnabledFalseNothingIsGuarded() {
        AgentYamlConfig.config.tuning.put(BOSS_IDS, "8800003-8800010");
        MapleMap altar = eventMap(ExpeditionType.ZAKUM);
        MapleMap trialField = mock(MapleMap.class);
        AgentBossScope.startTrial(BOSSFRIEND_ID, trialField);
        Monster zakumArm = boss(8_800_003, altar, human(altar));
        Monster trialArm = boss(8_800_010, trialField, human(trialField));
        MapleMap field = mock(MapleMap.class);
        Monster listedArm = boss(8_800_004, field, human(field));
        assertTrue(AgentBossScope.guards(zakumArm) && AgentBossScope.guards(trialArm)
                && AgentBossScope.guards(listedArm), "fixture: all three are in scope while enabled");

        AgentYamlConfig.config.tuning.put(ENABLED, "false");

        assertFalse(AgentBossScope.guards(zakumArm), "a Zakum event part");
        assertFalse(AgentBossScope.guards(trialArm), "the trial map");
        assertFalse(AgentBossScope.guards(listedArm), "a listed id");
    }

    @Test
    void noMonsterIsNeverGuarded() {
        assertFalse(AgentBossScope.guards(null));
        assertFalse(AgentBossScope.guards(boss(8_800_003, null, null)), "a part with no map");
    }

    @Test
    void aLiveVisibleHumanOnThePartsMapAndInItsEventIsEngaged() {
        MapleMap altar = eventMap(ExpeditionType.ZAKUM);
        Character bossfriend = human(altar);
        assertTrue(AgentBossScope.engaged(boss(8_800_003, altar, bossfriend)));

        MapleMap trialField = mock(MapleMap.class);
        assertTrue(AgentBossScope.engaged(boss(8_800_010, trialField, human(trialField))),
                "no event on either side");
    }

    @Test
    void noControllerABotOrAnAbsentDeadOrHiddenHumanIsNotEngaged() {
        MapleMap altar = eventMap(ExpeditionType.ZAKUM);
        assertFalse(AgentBossScope.engaged(boss(8_800_003, altar, null)), "no controller");
        assertFalse(AgentBossScope.engaged(boss(8_800_003, altar, bot(altar))), "a bot controller");

        Character loggedOut = human(altar);
        when(loggedOut.isLoggedinWorld()).thenReturn(false);
        assertFalse(AgentBossScope.engaged(boss(8_800_003, altar, loggedOut)), "logged out");

        Character dead = human(altar);
        when(dead.isAlive()).thenReturn(false);
        assertFalse(AgentBossScope.engaged(boss(8_800_003, altar, dead)), "dead");

        Character hidden = human(altar);
        when(hidden.isHidden()).thenReturn(true);
        assertFalse(AgentBossScope.engaged(boss(8_800_003, altar, hidden)), "hidden");

        EventInstanceManager zakumEvent = altar.getEventInstance();
        Character elsewhere = human(mock(MapleMap.class));
        when(elsewhere.getEventInstance()).thenReturn(zakumEvent);
        assertFalse(AgentBossScope.engaged(boss(8_800_003, altar, elsewhere)), "on another map");

        Character otherEvent = human(altar);
        when(otherEvent.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertFalse(AgentBossScope.engaged(boss(8_800_003, altar, otherEvent)), "in another event");

        assertFalse(AgentBossScope.engaged(null));
        assertFalse(AgentBossScope.engaged(boss(8_800_003, null, human(altar))), "a part with no map");
    }

    /** A map whose event belongs to an expedition of {@code type}; a null type is an event with no expedition. */
    private static MapleMap eventMap(ExpeditionType type) {
        MapleMap map = mock(MapleMap.class);
        EventInstanceManager event = mock(EventInstanceManager.class);
        if (type != null) {
            Expedition expedition = mock(Expedition.class);
            when(expedition.getType()).thenReturn(type);
            when(event.getExpedition()).thenReturn(expedition);
        }
        when(map.getEventInstance()).thenReturn(event);
        return map;
    }

    private static Monster boss(int id, MapleMap map, Character controller) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(id);
        when(monster.isBoss()).thenReturn(true);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getMap()).thenReturn(map);
        when(monster.getController()).thenReturn(controller);
        return monster;
    }

    private static Character human(MapleMap map) {
        Character human = mock(Character.class);
        when(human.getClient()).thenReturn(mock(Client.class));
        when(human.getId()).thenReturn(BOSSFRIEND_ID);
        when(human.isLoggedinWorld()).thenReturn(true);
        when(human.isAlive()).thenReturn(true);
        when(human.getMap()).thenReturn(map);
        EventInstanceManager event = map == null ? null : map.getEventInstance();
        when(human.getEventInstance()).thenReturn(event);
        return human;
    }

    private static Character bot(MapleMap map) {
        Character bot = mock(Character.class);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        when(bot.getId()).thenReturn(42_000_082);
        when(bot.isLoggedinWorld()).thenReturn(true);
        when(bot.isAlive()).thenReturn(true);
        when(bot.getMap()).thenReturn(map);
        return bot;
    }
}
