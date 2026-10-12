package server.agents.capabilities.expedition.boss;

import client.Character;
import client.Client;
import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P12 G2 case 4: a listed boss id inside an event is guarded only while that event has an open companion run (a
 * party-instance boss such as Papulatus, keyed by its event). The same id in an all-human event, and an id the list
 * does not name, keep today's path. The party quest bosses stay unguarded whatever runs are open.
 */
class AgentBossScopeEventRunTest {
    private static final String BOSS_IDS = "server.agents.capabilities.mobcontrol.AgentBossScope.BOSS_IDS";
    private static final int CLOCK = 8_500_001;
    private static final List<Integer> PARTY_QUEST_BOSSES = List.of(9_300_003, 9_300_012, 9_300_039,
            9_300_180, 9_300_181, 9_300_182, 9_300_105, 9_300_106, 9_300_107, 9_300_119);

    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private final AgentBossRunDefinition papulatus = new AgentBossRunDefinition("papulatus_test", null, 220080000,
            220080001, -1, 0, List.of(), 0, -1, () -> fight, false, List.of(), true, true,
            AgentBossRunDefinition.DeathMode.WAIT_OUTSIDE);
    private String shippedIds;

    @BeforeEach
    void listPapulatus() {
        shippedIds = AgentYamlConfig.config.tuning.get(BOSS_IDS);
        AgentYamlConfig.config.tuning.put(BOSS_IDS, "8500000-8500004");
    }

    @AfterEach
    void tearDown() {
        AgentYamlConfig.config.tuning.put(BOSS_IDS, shippedIds);
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void aListedIdInAnEventWithAnOpenCompanionRunIsGuarded() {
        MapleMap clockTower = eventMap();
        Monster clock = boss(CLOCK, clockTower, human(clockTower));
        assertFalse(AgentBossScope.guards(clock), "fixture: an all-human event has no run");

        AgentBossRunRegistry.put(new AgentBossRunSession(1L, papulatus, clockTower.getEventInstance(), fight, 0L));

        assertTrue(AgentBossScope.guards(clock), "the party's companion run is open in its event");
        assertTrue(AgentBossScope.guards(boss(CLOCK, clockTower, null)), "before anyone controls it");
        MapleMap otherTower = eventMap();
        assertFalse(AgentBossScope.guards(boss(CLOCK, otherTower, human(otherTower))),
                "the same boss in another party's all-human event");
    }

    @Test
    void anIdTheListDoesNotNameIsNotGuardedInARunsEvent() {
        MapleMap clockTower = eventMap();
        AgentBossRunRegistry.put(new AgentBossRunSession(1L, papulatus, clockTower.getEventInstance(), fight, 0L));

        assertFalse(AgentBossScope.guards(boss(8_500_005, clockTower, human(clockTower))));
    }

    @Test
    void partyQuestBossesStayUnguardedWhileARunIsOpenInTheirEvent() {
        MapleMap pqStage = eventMap();
        AgentBossRunRegistry.put(new AgentBossRunSession(1L, papulatus, pqStage.getEventInstance(), fight, 0L));
        for (int id : PARTY_QUEST_BOSSES) {
            assertFalse(AgentBossScope.guards(boss(id, pqStage, human(pqStage))), id + " is never listed");
        }
    }

    private static MapleMap eventMap() {
        MapleMap map = mock(MapleMap.class);
        EventInstanceManager event = mock(EventInstanceManager.class);
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
        when(human.isLoggedinWorld()).thenReturn(true);
        when(human.isAlive()).thenReturn(true);
        when(human.getMap()).thenReturn(map);
        return human;
    }
}
