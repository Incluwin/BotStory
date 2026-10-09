package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static server.agents.capabilities.partyquest.lmpq.LmpqGmFixture.CASSE;
import static server.agents.capabilities.partyquest.lmpq.LmpqGmFixture.CREW;
import static server.agents.capabilities.partyquest.lmpq.LmpqGmFixture.LIOREN;
import static server.agents.capabilities.partyquest.lmpq.LmpqGmFixture.PELL;

/**
 * Step 1 fixture neutrality: '!lmpqtest stop' (and the harness's own fail) logs the 'LMPQ session failure:' line
 * and then releases a fixture run itself, with the run still in its live phase, as before the line existed. A run
 * already marked FAILED would be released by the next member tick instead, while the GM thread closes the test and
 * logs its Agents out under that release.
 */
class AgentLmpqHarnessStopTest {
    private final LmpqGmFixture gm = new LmpqGmFixture();
    private AgentLmpqSession session;

    @AfterEach
    void tearDown() {
        AgentLmpqSessionRegistry.remove(session);
        gm.close();
    }

    @Test
    void theStopReleasesTheRunInItsLivePhase() throws ReflectiveOperationException {
        observationRunInRuns();
        List<AgentLmpqSession.Phase> atRelease = new CopyOnWriteArrayList<>();
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class);
             MockedStatic<AgentLmpqTerminationService> termination = releasesSeenAt(atRelease)) {
            assertEquals(List.of("LMPQ test stopped safely; final routes were written to the server log."),
                    gm.lmpqtest(gm.crew, 5_000L, "stop"));

            assertEquals(List.of(AgentLmpqSession.Phase.FARMING), atRelease, "the GM thread released a live run");
            assertEquals(List.of("LMPQ session failure: session=" + session.sessionId()
                            + " phase=FARMING reason=stopped by operator members=3 companionRun=false"),
                    lines.startingWith("LMPQ session failure:"));
        }
        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        assertEquals("stopped by operator", session.failure());
        assertNull(AgentLmpqSessionRegistry.forOperator(CREW));
    }

    @Test
    void theHarnessFailReleasesTheRunInItsLivePhase() throws ReflectiveOperationException {
        Object run = observationRunInRuns();
        Method fail = AgentLmpqTestService.class.getDeclaredMethod("fail", run.getClass(), String.class);
        fail.setAccessible(true);
        List<AgentLmpqSession.Phase> atRelease = new CopyOnWriteArrayList<>();
        try (MockedStatic<AgentLmpqTerminationService> termination = releasesSeenAt(atRelease)) {
            fail.invoke(null, run, "Could not launch LMPQ3R03: no map");

            assertEquals(List.of(AgentLmpqSession.Phase.FARMING), atRelease);
        }
        assertEquals("Could not launch LMPQ3R03: no map", session.failure());
    }

    /** Records the run's phase at each release the harness asks for; the release itself runs as usual. */
    private static MockedStatic<AgentLmpqTerminationService> releasesSeenAt(List<AgentLmpqSession.Phase> phases) {
        MockedStatic<AgentLmpqTerminationService> termination =
                mockStatic(AgentLmpqTerminationService.class, CALLS_REAL_METHODS);
        termination.when(() -> AgentLmpqTerminationService.release(any(), anyString(), anyLong(), anyBoolean()))
                .thenAnswer(call -> {
                    phases.add(call.<AgentLmpqSession>getArgument(0).phase());
                    return call.callRealMethod();
                });
        return termination;
    }

    /** Crewfriend's '!lmpqtest start' run of three fixture Agents, farming: registered here and in the harness. */
    private Object observationRunInRuns() throws ReflectiveOperationException {
        session = new AgentLmpqSession(AgentLmpqSession.Mode.TEST_OBSERVATION, 7L, CREW, 3, 1_000L);
        for (int id : List.of(LIOREN, CASSE, PELL)) session.addMember(id, AgentLmpqMemberState.MemberType.AGENT);
        session.setLeadership(LIOREN, LIOREN);
        session.bindEventInstance(gm.event);
        session.transition(AgentLmpqSession.Phase.FARMING, 2_000L);
        AgentLmpqSessionRegistry.registerComplete(session);

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
        Object run = constructor.newInstance(gm.crew, new AgentPartyQuestEngagement("lmpq",
                        AgentPartyQuestEngagement.Mode.TEST_OBSERVATION, 7L, CREW, 3, 1_000L),
                new AgentLmpqTestService.StartOptions(3, AgentLmpqDefinition.RENDEZVOUS_ROOM, 7L), fixture);
        Field sessionField = runType.getDeclaredField("session");
        sessionField.setAccessible(true);
        sessionField.set(run, session);
        Field runs = AgentLmpqTestService.class.getDeclaredField("RUNS");
        runs.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> registered = (Map<Integer, Object>) runs.get(null);
        registered.put(CREW, run);
        return run;
    }
}
