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
 * P17: SCARGA joins the guarded expedition types. Its six boss forms are guarded in a Scarga event as Zakum's parts
 * are; its non-boss adds (Ratatula, Scaredy Scarlion) only inside an event with an open companion run, whose boss rules
 * name them. 9420531 and 9420532 also live on the field map 551000200: there nothing changes. Zakum's rules name no
 * part, so a non-boss mob in a Zakum run's event stays unguarded.
 */
class AgentBossScopeScargaTest {
    private static final int TARGA = 9_420_542;
    private static final int RATATULA = 9_420_545;
    private static final int FIELD_RATATULA = 9_420_532;

    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();

    @AfterEach
    void tearDown() {
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void scargasBossFormsAreGuardedInItsEventAndItsAddsOnlyWithAnOpenRun() {
        Expedition scarga = expedition(ExpeditionType.SCARGA);
        MapleMap spookyWorld = eventMap(scarga);
        assertTrue(AgentBossScope.guards(mob(TARGA, true, spookyWorld)), "a boss form, as Zakum's parts");
        assertFalse(AgentBossScope.guards(mob(RATATULA, false, spookyWorld)), "no run: an add keeps today's path");

        AgentBossRunRegistry.put(new AgentBossRunSession(1L, AgentBossRunCatalog.SCARGA, scarga, fight, 0L));

        assertTrue(AgentBossScope.guards(mob(RATATULA, false, spookyWorld)), "the run's rules name it");
        assertTrue(AgentBossScope.guards(mob(FIELD_RATATULA, false, spookyWorld)));
        assertFalse(AgentBossScope.guards(mob(9_420_540, false, spookyWorld)), "Gallopera: not Scarga's");
    }

    @Test
    void theAddsOnTheirFieldMapAndAZakumRunsMobsAreUnchanged() {
        MapleMap field = mock(MapleMap.class);
        assertFalse(AgentBossScope.guards(mob(FIELD_RATATULA, false, field)), "551000200 keeps today's path");

        Expedition zakum = expedition(ExpeditionType.ZAKUM);
        MapleMap altar = eventMap(zakum);
        AgentBossRunRegistry.put(new AgentBossRunSession(2L, AgentBossRunCatalog.ZAKUM, zakum, fight, 0L));
        assertFalse(AgentBossScope.guards(mob(RATATULA, false, altar)), "Zakum's rules name no part");
    }

    private static Expedition expedition(ExpeditionType type) {
        Expedition expedition = mock(Expedition.class);
        when(expedition.getType()).thenReturn(type);
        return expedition;
    }

    private static MapleMap eventMap(Expedition expedition) {
        MapleMap map = mock(MapleMap.class);
        EventInstanceManager event = mock(EventInstanceManager.class);
        when(event.getExpedition()).thenReturn(expedition);
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
