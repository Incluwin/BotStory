package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqAdmitFixture.BEN;

/**
 * '!rjpqtest': the operator's own party of four becomes a companion run of the side he stands beside, and a checkpoint
 * leaves the event as the stock clears before that stage would: the flags, the open doors, the Lab Unit answers from
 * the event's own function, and never the Lab Unit room claims.
 */
class AgentRjpqTestServiceTest {
    private final RjpqAdmitFixture fixture = new RjpqAdmitFixture();
    private MockedStatic<AgentAuthorityService> authority;

    @BeforeEach
    void operator() {
        authority = mockStatic(AgentAuthorityService.class);
        authority.when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(true);
        when(fixture.party.onlineMembers(any())).thenAnswer(call -> fixture.party());
    }

    @AfterEach
    void tearDown() {
        AgentRjpqTestService.execute(fixture.ben, new String[]{"stop"}, 99_000L);
        authority.close();
        fixture.close();
    }

    @Test
    void partyProdBesideRomeoAdoptsTheZenumistRun() {
        assertEquals(List.of("Adopted as a companion run. Talk to Romeo within 30 seconds."),
                AgentRjpqTestService.execute(fixture.ben, new String[]{"party", "prod", "7"}, 1_000L));

        AgentRjpqSession session = AgentRjpqSessionRegistry.forOperator(BEN);
        assertNotNull(session);
        assertEquals(AgentRjpqSide.ZENUMIST, session.side());
        assertEquals(Boolean.TRUE, session.memberCoordinationChatEnabled());
        assertEquals(List.of("Stop the current RJPQ test before adopting another party."),
                AgentRjpqTestService.execute(fixture.ben, new String[]{"party", "prod"}, 2_000L));
    }

    @Test
    void partyProdBesideJulietAdoptsTheAlcadnoRun() {
        fixture.onAlcadno();

        assertEquals(List.of("Adopted as a companion run. Talk to Juliet within 30 seconds."),
                AgentRjpqTestService.execute(fixture.ben, new String[]{"party", "prod"}, 1_000L));
        assertEquals(AgentRjpqSide.ALCADNO, AgentRjpqSessionRegistry.forOperator(BEN).side());
    }

    @Test
    void partyProdRefusesAnywhereElseAndWithoutProd() {
        when(fixture.ben.getMapId()).thenReturn(261_000_000);
        assertEquals(List.of("Stand beside Romeo (261000011) or Juliet (261000021) before adopting a party."),
                AgentRjpqTestService.execute(fixture.ben, new String[]{"party", "prod"}, 1_000L));
        assertEquals(List.of("RJPQ adopts a party only as a companion run: !rjpqtest party prod [seed]."),
                AgentRjpqTestService.execute(fixture.ben, new String[]{"party"}, 1_000L));
        assertNull(AgentRjpqSessionRegistry.forOperator(BEN));
    }

    @Test
    void aStopReleasesTheRunAndKeepsNothingRegistered() {
        AgentRjpqTestService.execute(fixture.ben, new String[]{"party", "prod"}, 1_000L);

        assertEquals(List.of("RJPQ test stopped safely."),
                AgentRjpqTestService.execute(fixture.ben, new String[]{"stop"}, 2_000L));
        assertNull(AgentRjpqSessionRegistry.forOperator(BEN));
        assertEquals(List.of("No RJPQ test is active."),
                AgentRjpqTestService.execute(fixture.ben, new String[]{"stop"}, 3_000L));
    }

    @Test
    void aCheckpointBeforeEnteringIsRefused() {
        AgentRjpqTestService.execute(fixture.ben, new String[]{"party", "prod"}, 1_000L);

        assertEquals(List.of("Enter RJPQ through romeo before a checkpoint."),
                AgentRjpqTestService.execute(fixture.ben, new String[]{"checkpoint", "office"}, 2_000L));
        assertEquals(List.of("Syntax: !rjpqtest checkpoint <tunnel|beakers|keys|office|units|center>"),
                AgentRjpqTestService.execute(fixture.ben, new String[]{"checkpoint", "boss"}, 2_000L));
    }

    @Test
    void theUnitsCheckpointLeavesTheEventAsTheStockClearsWould() throws Exception {
        EventInstanceManager event = mock(EventInstanceManager.class);
        Map<String, Reactor> reactors = new HashMap<>();
        Map<Integer, MapleMap> maps = new HashMap<>();
        when(event.getMapInstance(anyInt())).thenAnswer(call -> maps.computeIfAbsent(call.getArgument(0), id -> {
            MapleMap map = mock(MapleMap.class);
            when(map.getReactorByName(any())).thenAnswer(byName -> reactors.computeIfAbsent(byName.getArgument(0),
                    name -> mock(Reactor.class)));
            return map;
        }));

        AgentRjpqTestService.prepare(event, AgentRjpqSide.ALCADNO, AgentRjpqTestService.Checkpoint.UNITS);

        verify(event).setIntProperty("statusStg1", 1);
        verify(event).setIntProperty("statusStg2", 1);
        verify(event).setIntProperty("statusStg3", 3);
        verify(event).setIntProperty("statusStg4", 1);
        verify(event, never()).setIntProperty(eq("statusStg5"), any());
        verify(event, never()).setIntProperty(eq("statusStg6"), any());
        for (String door : List.of("jnr2_door", "jnr3_out1", "jnr3_out2", "jnr3_out3", "jnr31_out", "jnr32_out",
                "jnr6_out")) {
            verify(reactors.get(door)).forceHitReactor((byte) 1);
        }
        verify(event).invokeScriptFunction("generateStg6Combo", event);
        verify(maps.get(926_110_001)).killAllMonsters();
        verify(maps.get(926_110_203)).killAllMonsters();
    }

    @Test
    void theTunnelCheckpointOnlyOpensTheBooks() throws Exception {
        EventInstanceManager event = mock(EventInstanceManager.class);

        AgentRjpqTestService.prepare(event, AgentRjpqSide.ZENUMIST, AgentRjpqTestService.Checkpoint.TUNNEL);

        verify(event).setIntProperty("statusStg1", 1);
        verify(event, never()).setIntProperty(eq("statusStg2"), any());
        verify(event, never()).invokeScriptFunction(any(), any());
    }
}
