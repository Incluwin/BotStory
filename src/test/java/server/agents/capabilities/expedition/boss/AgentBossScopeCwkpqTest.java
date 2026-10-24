package server.agents.capabilities.expedition.boss;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.life.Monster;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C9: CWKPQ joins the guarded expedition types, in its Council Hall only. There the Grandmasters and the hall's guards
 * (the boss rules' adds 9400594 and 9400582) stay with the leader's client, so the boss mirror plays their attacks on
 * the companions. The same ids in the class rooms (9400582 in the warrior room, 9400594 in the archer room), where no
 * human ever stands, and every mob of the earlier stages keep the unguarded path they cleared on live.
 */
class AgentBossScopeCwkpqTest {
    private static final int COUNCIL_HALL = 610_030_600;
    private static final int WARRIOR_ROOM = 610_030_510;
    private static final int ARCHER_ROOM = 610_030_540;
    private static final int MARGANA = 9_400_590;
    private static final int MASTER_GUARDIAN = 9_400_582;
    private static final int GUARDIAN = 9_400_594;

    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();

    @AfterEach
    void tearDown() {
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void theCouncilHallsMastersAndGuardsAreGuarded() {
        Expedition cwkpq = expedition();
        EventInstanceManager event = event(cwkpq);
        MapleMap hall = map(event, COUNCIL_HALL);
        assertTrue(AgentBossScope.guards(mob(MARGANA, true, hall)), "a Grandmaster, as Zakum's parts");
        assertFalse(AgentBossScope.guards(mob(GUARDIAN, false, hall)), "no run: a guard keeps today's path");

        AgentBossRunRegistry.put(new AgentBossRunSession(1L, AgentBossRunCatalog.CWKPQ, cwkpq, fight, 0L));

        assertTrue(AgentBossScope.guards(mob(GUARDIAN, false, hall)), "the run's rules name the hall's guards");
        assertTrue(AgentBossScope.guards(mob(MASTER_GUARDIAN, false, hall)));
    }

    @Test
    void theStageMapsAndClassRoomsStayUnguarded() {
        Expedition cwkpq = expedition();
        EventInstanceManager event = event(cwkpq);
        AgentBossRunRegistry.put(new AgentBossRunSession(1L, AgentBossRunCatalog.CWKPQ, cwkpq, fight, 0L));

        assertFalse(AgentBossScope.guards(mob(MASTER_GUARDIAN, false, map(event, WARRIOR_ROOM))),
                "no human ever drives the warrior room");
        assertFalse(AgentBossScope.guards(mob(GUARDIAN, false, map(event, ARCHER_ROOM))));
        assertFalse(AgentBossScope.guards(mob(9_400_580, true, map(event, 610_030_521))),
                "not even a boss-flagged room monster");
        assertFalse(AgentBossScope.guards(mob(9_400_576, false, map(event, 610_030_100))), "the hallway");
    }

    private static Expedition expedition() {
        Expedition expedition = mock(Expedition.class);
        when(expedition.getType()).thenReturn(ExpeditionType.CWKPQ);
        return expedition;
    }

    private static EventInstanceManager event(Expedition expedition) {
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getExpedition()).thenReturn(expedition);
        return event;
    }

    private static MapleMap map(EventInstanceManager event, int mapId) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(map.getEventInstance()).thenReturn(event);
        return map;
    }

    private static Monster mob(int id, boolean boss, MapleMap map) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(id);
        when(monster.isBoss()).thenReturn(boss);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getMap()).thenReturn(map);
        Character human = mock(Character.class);
        when(human.getClient()).thenReturn(mock(Client.class));
        when(monster.getController()).thenReturn(human);
        return monster;
    }
}
