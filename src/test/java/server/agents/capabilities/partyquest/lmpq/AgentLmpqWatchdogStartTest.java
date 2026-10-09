package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.lmpq.LmpqAdmitFixture.IDS;

/**
 * LMPQ base fix 8, starting the watchdog: each registration of a run starts it (the public admit and the GM
 * fixture harness), and a start that throws never leaves a run registered with nobody to release it: the admission
 * counts the run as published as soon as it is registered, so its rollback removes it, and the harness starts the
 * watchdog before it registers anything.
 */
class AgentLmpqWatchdogStartTest {

    @Test
    void theAdmissionStartsIt() {
        try (LmpqAdmitFixture fixture = new LmpqAdmitFixture();
             MockedStatic<AgentLmpqWatchdogRuntime> watchdog = mockStatic(AgentLmpqWatchdogRuntime.class)) {
            AgentLmpqAdmissionService.AdmissionResult admitted =
                    AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

            assertTrue(admitted.success(), admitted.message());
            watchdog.verify(AgentLmpqWatchdogRuntime::ensureStarted, times(1));
        }
    }

    @Test
    void aWatchdogThatCannotStartLeavesNoAdmittedRunBehind() {
        try (LmpqAdmitFixture fixture = new LmpqAdmitFixture();
             MockedStatic<AgentLmpqWatchdogRuntime> watchdog = mockStatic(AgentLmpqWatchdogRuntime.class)) {
            watchdog.when(AgentLmpqWatchdogRuntime::ensureStarted)
                    .thenThrow(new IllegalStateException("scheduler unavailable"));

            AgentLmpqAdmissionService.AdmissionResult admitted =
                    AgentLmpqAdmissionService.admit(fixture.ben, fixture.ben, fixture.party(), 7L, 1_000L);

            assertFalse(admitted.success());
            assertEquals("scheduler unavailable", admitted.message());
            assertNull(AgentLmpqSessionRegistry.forOperator(BEN), "no orphan run");
            for (int id : IDS) assertNull(AgentLmpqSessionRegistry.forMember(id), "session of " + id);
            assertEquals(AgentPartyQuestEngagement.State.CLOSED, admitted.engagement().state(), "recovered");
        }
    }

    @Test
    void theHarnessStartsItAndAStartThatThrowsRegistersNothing() throws ReflectiveOperationException {
        int operatorId = 781_960;
        List<Integer> agents = List.of(781_961, 781_962, 781_963);
        try (MockedStatic<AgentLmpqWatchdogRuntime> watchdog = mockStatic(AgentLmpqWatchdogRuntime.class)) {
            watchdog.when(AgentLmpqWatchdogRuntime::ensureStarted)
                    .thenThrow(new IllegalStateException("scheduler unavailable"));
            Object refused = fixtureRun(operatorId, agents);

            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> activate().invoke(null, refused, 2_000L));

            assertEquals("scheduler unavailable", thrown.getCause().getMessage());
            for (int id : agents) assertNull(AgentLmpqSessionRegistry.forMember(id), "session of " + id);
            assertNull(AgentLmpqSessionRegistry.forOperator(operatorId));

            watchdog.reset();
            watchdog.clearInvocations();
            Object started = fixtureRun(operatorId, agents);
            activate().invoke(null, started, 3_000L);
            watchdog.verify(AgentLmpqWatchdogRuntime::ensureStarted, times(1));
            AgentLmpqSession session = AgentLmpqSessionRegistry.forOperator(operatorId);
            assertNotNull(session, "the fixture run is registered");
            AgentLmpqSessionRegistry.remove(session);
        } finally {
            for (int id : agents) AgentLmpqSessionRegistry.remove(AgentLmpqSessionRegistry.forMember(id));
        }
    }

    private static Method activate() throws ReflectiveOperationException {
        Class<?> runType = Class.forName(AgentLmpqTestService.class.getName() + "$Run");
        Method activate = AgentLmpqTestService.class.getDeclaredMethod("activate", runType, long.class);
        activate.setAccessible(true);
        return activate;
    }

    /** A fixture run whose three Agents have joined, ready to activate: its engagement holds a formed lobby. */
    private static Object fixtureRun(int operatorId, List<Integer> agents) throws ReflectiveOperationException {
        Character operator = mock(Character.class);
        when(operator.getId()).thenReturn(operatorId);
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "lmpq", AgentPartyQuestEngagement.Mode.TEST_OBSERVATION, 7L, operatorId, agents.size(), 1_000L);
        engagement.beginLobby("lmpq-test-" + operatorId, 1_000L);
        assertNull(AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()));
        String service = AgentLmpqTestService.class.getName();
        Class<?> runType = Class.forName(service + "$Run");
        Class<?> flow = Class.forName(service + "$Flow");
        Object fixture = null;
        for (Object constant : flow.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals("FIXTURE")) fixture = constant;
        }
        Constructor<?> constructor = runType.getDeclaredConstructor(Character.class, AgentPartyQuestEngagement.class,
                AgentLmpqTestService.StartOptions.class, flow);
        constructor.setAccessible(true);
        Object run = constructor.newInstance(operator, engagement,
                new AgentLmpqTestService.StartOptions(agents.size(), AgentLmpqDefinition.RENDEZVOUS_ROOM, 7L), fixture);
        Field agentIds = runType.getDeclaredField("agents");
        agentIds.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Integer> joined = (Set<Integer>) agentIds.get(run);
        joined.addAll(agents);
        Field leader = runType.getDeclaredField("leaderId");
        leader.setAccessible(true);
        leader.setInt(run, agents.getFirst());
        return run;
    }
}
