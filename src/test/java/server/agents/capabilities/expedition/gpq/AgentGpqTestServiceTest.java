package server.agents.capabilities.expedition.gpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.expedition.gpq.AgentGpqRun.Stage;
import server.agents.capabilities.expedition.gpq.AgentGpqTestService.Checkpoint;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * '!gpqtest' (GPQ plan G4): a checkpoint opens the gates and sets the flags the stock clears before that stage would
 * have, never closes one again, and refuses while anyone alive in the event wears no Protector Rock (the air would kill
 * them on the move). It never acts outside a Guild Quest instance.
 */
class AgentGpqTestServiceTest {
    private MockedStatic<AgentAuthorityService> authority;
    private final Map<String, String> properties = new HashMap<>();
    private final Map<String, Reactor> gates = new HashMap<>();
    private final Map<String, Integer> states = new HashMap<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final Character operator = mock(Character.class);

    @BeforeEach
    void instance() {
        authority = mockStatic(AgentAuthorityService.class);
        authority.when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(true);
        when(event.getName()).thenReturn("Guild12");
        when(event.getProperty(anyString())).thenAnswer(call -> properties.get((String) call.getArgument(0)));
        doAnswer(call -> properties.put(call.getArgument(0), call.getArgument(1))).when(event)
                .setProperty(anyString(), anyString());
        Map<Stage, String> gateOn = Map.of(Stage.GATEKEEPER, "statuegate", Stage.HALL, "speargate",
                Stage.FOUNTAIN, "watergate", Stage.GRAVE, "ghostgate", Stage.CORRIDOR, "kinggate");
        Map<Integer, MapleMap> maps = new HashMap<>();
        gateOn.forEach((stage, name) -> {
            Reactor gate = mock(Reactor.class);
            states.put(name, 0);
            when(gate.getState()).thenAnswer(call -> (byte) (int) states.get(name));
            doAnswer(call -> states.put(name, (int) (byte) call.getArgument(0))).when(gate).forceHitReactor(anyByte());
            gates.put(name, gate);
            MapleMap map = mock(MapleMap.class);
            when(map.getReactorByName(name)).thenReturn(gate);
            maps.put(stage.mapId(), map);
        });
        when(event.getMapInstance(anyInt())).thenAnswer(call -> maps.get((Integer) call.getArgument(0)));
        when(operator.getEventInstance()).thenReturn(event);
        when(operator.getId()).thenReturn(1);
        when(operator.getName()).thenReturn("Ben");
        when(operator.isAlive()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        authority.close();
    }

    @Test
    void eachCheckpointOpensTheGatesAndFlagsTheStockClearsBeforeItLeave() {
        AgentGpqTestService.open(event, Checkpoint.GATEKEEPER);
        assertEquals(Map.of("statuegate", 0, "speargate", 0, "watergate", 0, "ghostgate", 0, "kinggate", 0), states);
        assertEquals("0", properties.get(AgentGpqStagePlays.ENTRY_TIMESTAMP), "the way in stands open");

        AgentGpqTestService.open(event, Checkpoint.VASSALS);
        assertEquals(Map.of("statuegate", 1, "speargate", 4, "watergate", 0, "ghostgate", 0, "kinggate", 0), states);
        assertEquals("true", properties.get("stage1clear"));

        AgentGpqTestService.open(event, Checkpoint.CORRIDOR);
        assertEquals(Map.of("statuegate", 1, "speargate", 4, "watergate", 1, "ghostgate", 1, "kinggate", 0), states);
        assertEquals("true", properties.get(AgentGpqVassalPlays.CLEAR));
        assertEquals("true", properties.get(AgentGpqWaterwayPlays.CLEAR));
        assertEquals(null, properties.get("canRevive"), "the gate's sacrifice is still to come");

        AgentGpqTestService.open(event, Checkpoint.THRONE);
        assertEquals(1, states.get("kinggate"));
        assertEquals("1", properties.get("canRevive"));
    }

    @Test
    void aCheckpointNeverClosesAGateAgain() {
        states.put("speargate", 4);
        AgentGpqTestService.open(event, Checkpoint.SPEARS);
        verify(gates.get("speargate"), never()).forceHitReactor(anyByte());
        assertEquals(1, states.get("statuegate"));
    }

    @Test
    void aCheckpointWaitsUntilEveryoneAliveWearsARock() {
        Inventory equipped = mock(Inventory.class);
        when(operator.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.getItem(anyShort())).thenReturn(null);
        when(event.getPlayers()).thenReturn(List.of(operator));

        List<String> lines = AgentGpqTestService.execute(operator, new String[] {"checkpoint", "vassals"}, 0L);

        assertTrue(lines.getFirst().startsWith("Not without rocks: Ben"), lines.toString());
        assertEquals(0, states.get("statuegate"), "nothing moved on");
    }

    @Test
    void itActsOnlyInsideAGuildQuest() {
        when(event.getName()).thenReturn("CWKPQ1");
        List<String> lines = AgentGpqTestService.execute(operator, new String[] {"checkpoint", "throne"}, 0L);
        assertEquals(List.of("Enter Sharenian through Shuang before a checkpoint."), lines);
        assertEquals(0, states.get("kinggate"));
    }
}
