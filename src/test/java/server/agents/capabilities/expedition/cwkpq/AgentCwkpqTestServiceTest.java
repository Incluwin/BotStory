package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqTestService.Checkpoint;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * '!cwkpqtest' (CWKPQ plan C4): a checkpoint leaves the event as the stock clears before that stage would, with the
 * stage counter one short so the move starts the stage's timer, moves everyone alive inside the instance, and leaves
 * the down where they fell. It never acts outside a CWKPQ instance, nor backwards.
 */
class AgentCwkpqTestServiceTest {
    private MockedStatic<AgentAuthorityService> authority;
    private final Map<String, Integer> flags = new HashMap<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final Character operator = mock(Character.class);

    @BeforeEach
    void operator() throws Exception {
        authority = mockStatic(AgentAuthorityService.class);
        authority.when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(true);
        for (String flag : List.of("current_instance", "glpq1", "glpq2", "glpq3", "glpq3_p", "glpq4", "glpq5",
                "glpq6")) {
            flags.put(flag, 0);
        }
        when(event.getName()).thenReturn("CWKPQ1");
        when(event.getIntProperty(anyString())).thenAnswer(call -> flags.getOrDefault(call.getArgument(0), 0));
        doAnswer(call -> flags.put(call.getArgument(0), call.getArgument(1))).when(event)
                .setIntProperty(anyString(), anyInt());
        when(event.invokeScriptFunction("clearPQ", event)).thenAnswer(call -> {
            when(event.isEventCleared()).thenReturn(true);
            return null;
        });
        when(operator.getEventInstance()).thenReturn(event);
        when(operator.getId()).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        authority.close();
    }

    @Test
    void eachCheckpointSetsTheFlagsTheStockClearsBeforeItLeave() {
        AgentCwkpqTestService.prepare(event, Checkpoint.STORAGE);
        assertEquals(Map.of("current_instance", 0, "glpq1", 1, "glpq2", 0, "glpq3", 0, "glpq3_p", 0, "glpq4", 0,
                "glpq5", 0, "glpq6", 0), flags);

        AgentCwkpqTestService.prepare(event, Checkpoint.WIT);
        assertEquals(Map.of("current_instance", 2, "glpq1", 1, "glpq2", 5, "glpq3", 5, "glpq3_p", 5, "glpq4", 0,
                "glpq5", 0, "glpq6", 0), flags);

        AgentCwkpqTestService.prepare(event, Checkpoint.COUNCIL);
        assertEquals(Map.of("current_instance", 4, "glpq1", 1, "glpq2", 5, "glpq3", 5, "glpq3_p", 5, "glpq4", 5,
                "glpq5", 5, "glpq6", 0), flags);
        assertEquals(false, event.isEventCleared());

        AgentCwkpqTestService.prepare(event, Checkpoint.ARMORY);
        assertEquals(5, flags.get("current_instance"));
        assertEquals(3, flags.get("glpq6"));
        assertEquals(true, event.isEventCleared());
    }

    @Test
    void theCountersMatchTheEventScriptsStageMaps() throws Exception {
        String script = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/event/CWKPQ.js"));
        for (Checkpoint checkpoint : Checkpoint.values()) {
            String stage = "case " + checkpoint.mapId + ":\n                if (eim.getIntProperty(\"current_instance\") == "
                    + (checkpoint.instance - 1) + ")";
            assertEquals(true, script.replace("\r\n", "\n").contains(stage), checkpoint.name());
        }
    }

    @Test
    void aCheckpointMovesEveryoneAliveAndLeavesTheDownWhereTheyFell() {
        MapleMap wit = mock(MapleMap.class);
        Portal start = mock(Portal.class);
        when(start.getPosition()).thenReturn(new Point(0, 0));
        when(wit.getPortal(0)).thenReturn(start);
        when(event.getMapInstance(610_030_400)).thenReturn(wit);
        Character down = mock(Character.class);
        when(operator.isAlive()).thenReturn(true);
        when(down.isAlive()).thenReturn(false);
        when(event.getPlayers()).thenReturn(List.of(operator, down));

        assertEquals(List.of("CWKPQ checkpoint: 1 moved to the wit, 1 down stay where they fell. Earlier stages are "
                        + "skipped for this run only."),
                AgentCwkpqTestService.execute(operator, new String[]{"checkpoint", "wit"}, 0L));

        verify(operator).changeMap(wit, start);
        verify(down, never()).changeMap(any(MapleMap.class), any(Portal.class));
        assertEquals(2, flags.get("current_instance"));
    }

    @Test
    void aRunAlreadyOnTheStageBringsOnlyThoseStillBehind() {
        MapleMap council = mock(MapleMap.class);
        Portal start = mock(Portal.class);
        when(start.getPosition()).thenReturn(new Point(0, 0));
        when(council.getPortal(0)).thenReturn(start);
        when(event.getMapInstance(610_030_600)).thenReturn(council);
        Character ahead = mock(Character.class);
        when(ahead.getMapId()).thenReturn(610_030_600);
        when(operator.getMapId()).thenReturn(610_030_500);
        when(operator.isAlive()).thenReturn(true);
        when(event.getPlayers()).thenReturn(List.of(operator, ahead));
        flags.put("current_instance", 5);
        flags.put("glpq5", 5);

        assertEquals(List.of("CWKPQ checkpoint: the run is on the council; 1 caught up."),
                AgentCwkpqTestService.execute(operator, new String[]{"checkpoint", "council"}, 0L));

        verify(operator).changeMap(council, start);
        verify(ahead, never()).changeMap(any(MapleMap.class), any(Portal.class));
        assertEquals(5, flags.get("current_instance"));
    }

    @Test
    void neverBackwardsNorOutsideARun() {
        flags.put("current_instance", 3);
        assertEquals(List.of("The run is already past that stage."),
                AgentCwkpqTestService.execute(operator, new String[]{"checkpoint", "agility"}, 0L));
        assertEquals(3, flags.get("current_instance"));

        when(event.getName()).thenReturn("Horntail1");
        assertNull(AgentCwkpqTestService.cwkpqEvent(operator));
        assertEquals(List.of("Enter CWKPQ through Jack before a checkpoint."),
                AgentCwkpqTestService.execute(operator, new String[]{"checkpoint", "council"}, 0L));
        assertEquals(List.of("Syntax: !cwkpqtest checkpoint <storage|agility|wit|unity|council|armory>"),
                AgentCwkpqTestService.execute(operator, new String[]{"checkpoint", "attic"}, 0L));
    }

    @Test
    void stopRunsTheEventsOwnEnd() throws Exception {
        assertSame(event, AgentCwkpqTestService.cwkpqEvent(operator));
        assertEquals(List.of("CWKPQ stopped: everyone in it goes back to the Hall to Inner Sanctum."),
                AgentCwkpqTestService.execute(operator, new String[]{"stop"}, 0L));
        verify(event).invokeScriptFunction("end", event);
    }

    @Test
    void anOperatorIsNeeded() {
        authority.when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(false);
        assertEquals(List.of("You are not configured as an Agent operator."),
                AgentCwkpqTestService.execute(operator, new String[]{"status"}, 0L));
    }
}
