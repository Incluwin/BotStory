package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.MapGateway;
import server.agents.integration.PartyGateway;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "!lpqtest checkpoint &lt;3-9&gt;": GM setup for re-running one LPQ stage of a companion run (a gm-1 leader cannot
 * !warp, and an in-instance !warp skips the stage flags). The whole party, the leader included, moves to the stage's
 * map inside its own instance, the earlier stages are marked clear and nothing else is touched, so the stage under
 * test plays from its start. It never applies to a fixture run, before entry, backwards, or with a member outside.
 */
class AgentLpqCheckpointTest {
    private static final int CREW = 743_300;
    private static final int[] COMPANIONS = {743_301, 743_302, 743_303, 743_304, 743_305};
    private static final Point PORTAL_0 = new Point(-180, 245);

    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final MapleMap stageEight = mock(MapleMap.class);
    private final MapGateway maps = mock(MapGateway.class);
    private final PartyGateway party = mock(PartyGateway.class);
    private final CharacterGateway characters = mock(CharacterGateway.class);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final List<Character> everyone = new ArrayList<>();
    private Character crew;
    private AgentLpqSession session;

    @BeforeEach
    void anAdoptedRunOnStageThree() {
        open(AgentAuthorityService.class).when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(true);
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        open(AgentMapGatewayRuntime.class).when(AgentMapGatewayRuntime::map).thenReturn(maps);
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        MockedStatic<AgentRuntimeIdentityRuntime> identity = open(AgentRuntimeIdentityRuntime.class);
        open(AgentSchedulerRuntime.class);
        open(AgentRuntimeCleanupService.class);
        MockedStatic<AgentLpqAdmissionService> admission = open(AgentLpqAdmissionService.class);

        crew = member(CREW, "Crewfriend");
        everyone.add(crew);
        when(characters.findOnlineCharacterById(CREW)).thenReturn(crew);
        String[] names = {"Lioren", "Kestrel", "Pell", "Halvard", "Casse"};
        for (int i = 0; i < COMPANIONS.length; i++) {
            Character companion = member(COMPANIONS[i], names[i]);
            AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
            int id = COMPANIONS[i];
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
            identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(companion);
            everyone.add(companion);
        }
        List<AgentPartyMemberSnapshot> snapshot = new ArrayList<>();
        for (Character member : everyone) {
            snapshot.add(new AgentPartyMemberSnapshot(member.getId(), member.getName(), member == crew,
                    AgentLpqDefinition.RECRUIT_MAP));
        }
        AgentPartySnapshot partySnapshot = new AgentPartySnapshot(41, snapshot);
        when(party.snapshot(crew)).thenReturn(partySnapshot);
        when(party.onlineMembers(crew)).thenReturn(List.copyOf(everyone));
        when(event.getMapInstance(AgentLpqDefinition.stage(8).mapId())).thenReturn(stageEight);
        Portal portal = mock(Portal.class);
        when(portal.getPosition()).thenReturn(new Point(PORTAL_0));
        when(stageEight.getPortal(0)).thenReturn(portal);

        session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 7L, CREW, 6, 1_000L);
        session.addMember(CREW, AgentLpqMemberState.MemberType.HUMAN);
        for (int id : COMPANIONS) session.addMember(id, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(CREW, COMPANIONS[0]);
        session.setCompanionAdopted(true);
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "lpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, CREW, 6, 1_000L);
        admission.when(() -> AgentLpqAdmissionService.admit(eq(crew), eq(crew), anyList(), anyLong(), anyLong()))
                .thenReturn(new AgentLpqAdmissionService.AdmissionResult(true, "LPQ party admitted", session,
                        engagement));
    }

    @AfterEach
    void tearDown() {
        AgentLpqTestService.execute(crew, new String[]{"stop"}, 9_000L);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    /** Adopted at the Red Sign, then the party is inside its instance on stage 3's map, the session in {@code phase}. */
    private void adoptAndEnter(AgentLpqSession.Phase phase) {
        assertEquals(List.of(AgentLpqTestServicePartyProdTest.ADOPTED),
                AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L));
        for (Character member : everyone) {
            when(member.getMapId()).thenReturn(AgentLpqDefinition.stage(3).mapId());
        }
        session.bindEventInstance(event);
        session.transition(phase, 3_000L);
    }

    private List<String> checkpoint(String... stage) {
        String[] params = new String[stage.length + 1];
        params[0] = "checkpoint";
        System.arraycopy(stage, 0, params, 1, stage.length);
        return AgentLpqTestService.execute(crew, params, 4_000L);
    }

    @Test
    void theWholePartyMovesToStageEightWithTheEarlierStagesClearAndNothingElse() {
        adoptAndEnter(AgentLpqSession.Phase.STAGE_3);
        try (LpqLogLines log = new LpqLogLines(AgentLpqTestService.class)) {
            assertEquals(List.of("LPQ checkpoint: everyone is on stage 8."), checkpoint("8"));

            for (int stage = 1; stage <= 7; stage++) verify(event).setProperty(stage + "stageclear", "true");
            verify(event, times(7)).setProperty(anyString(), anyString());
            verify(event, never()).setProperty(anyString(), any(Integer.class));
            for (Character member : everyone) verify(maps).changeMapNear(member, stageEight, PORTAL_0);
            verify(maps, times(6)).changeMapNear(any(), any(), any());
            assertEquals(AgentLpqSession.Phase.STAGE_8, session.phase());
            assertEquals(event, session.eventInstance());
            assertEquals(List.of("LPQ checkpoint: session=" + session.sessionId() + " from=STAGE_3 to=STAGE_8 "
                            + "members=[743300, 743301, 743302, 743303, 743304, 743305]"),
                    log.startingWith("LPQ checkpoint: "));
        }
    }

    /**
     * The checks run before the run's lock is taken, so a release can land in between: the tick's own failure, or
     * one begun by the watchdog, a force stop or a stop, which fail a run without its lock. Here it lands while the
     * stage map is looked up. Whatever it is, nothing is written and no one is moved into the instance. (Each change
     * is stubbed with doAnswer: re-stubbing through when() would call, and so run, the change stubbed before it.)
     */
    @Test
    void aRunReleasedBeforeTheCheckpointTakesHoldMovesNoOne() {
        adoptAndEnter(AgentLpqSession.Phase.STAGE_3);
        doAnswer(call -> {
            session.beginTermination();
            session.clearEventInstance();
            session.fail("The LPQ event timer expired", 3_900L);
            return stageEight;
        }).when(event).getMapInstance(AgentLpqDefinition.stage(8).mapId());
        assertEquals(List.of("The LPQ run ended before the checkpoint."), checkpoint("8"));
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        assertNothingMoved();
    }

    @Test
    void aReleaseUnderWayOrADisposedEventTakesNoCheckpoint() {
        adoptAndEnter(AgentLpqSession.Phase.STAGE_3);
        AtomicBoolean disposed = new AtomicBoolean();
        when(event.isEventDisposed()).thenAnswer(call -> disposed.get());
        doAnswer(call -> {
            disposed.set(true);
            return stageEight;
        }).when(event).getMapInstance(AgentLpqDefinition.stage(8).mapId());
        assertEquals(List.of("The LPQ run ended before the checkpoint."), checkpoint("8"));
        disposed.set(false);
        doAnswer(call -> {
            session.beginTermination(); // the watchdog's release has begun; the session is not FAILED yet
            return stageEight;
        }).when(event).getMapInstance(AgentLpqDefinition.stage(8).mapId());
        assertEquals(List.of("The LPQ run ended before the checkpoint."), checkpoint("8"));
        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase());
        assertNothingMoved();
    }

    @Test
    void aPartyThatMovedMeanwhileIsNotMoved() {
        adoptAndEnter(AgentLpqSession.Phase.STAGE_3);
        Character kestrel = everyone.get(2);
        doAnswer(call -> {
            session.transition(AgentLpqSession.Phase.STAGE_4, 3_900L);
            return stageEight;
        }).when(event).getMapInstance(AgentLpqDefinition.stage(8).mapId());
        assertEquals(List.of("The LPQ party moved on; try the checkpoint again."), checkpoint("8"));
        assertEquals(AgentLpqSession.Phase.STAGE_4, session.phase());
        AtomicBoolean kestrelLeft = new AtomicBoolean();
        when(kestrel.getMapId()).thenAnswer(call -> kestrelLeft.get()
                ? AgentLpqDefinition.EXIT_MAP : AgentLpqDefinition.stage(3).mapId());
        doAnswer(call -> {
            kestrelLeft.set(true);
            return stageEight;
        }).when(event).getMapInstance(AgentLpqDefinition.stage(8).mapId());
        assertEquals(List.of("Every member must be inside the LPQ instance first."), checkpoint("8"));
        assertEquals(AgentLpqSession.Phase.STAGE_4, session.phase());
        assertNothingMoved();
    }

    private void assertNothingMoved() {
        verify(event, never()).setProperty(anyString(), anyString());
        verify(maps, never()).changeMapNear(any(), any(), any());
    }

    @Test
    void onlyAnAdoptedRunTakesACheckpoint() {
        assertEquals(List.of("Checkpoints are for a companion run: !lpqtest party prod first."), checkpoint("5"));
        verify(maps, never()).changeMapNear(any(), any(), any());
    }

    @Test
    void aFixtureRunTakesNoCheckpoint() throws ReflectiveOperationException {
        // A fixture run (here a human-led observation run) with a live session inside its instance.
        Class<?> flow = Class.forName(AgentLpqTestService.class.getName() + "$Flow");
        Class<?> startStage = Class.forName(AgentLpqTestService.class.getName() + "$StartStage");
        Class<?> runType = Class.forName(AgentLpqTestService.class.getName() + "$Run");
        Constructor<?> constructor = runType.getDeclaredConstructor(Character.class, AgentPartyQuestEngagement.class,
                long.class, flow, startStage, AgentLpqSession.HumanRolePreference.class, Set.class);
        constructor.setAccessible(true);
        Object fixture = constructor.newInstance(crew, new AgentPartyQuestEngagement("lpq",
                        AgentPartyQuestEngagement.Mode.TEST_OBSERVATION, 7L, CREW, 6, 1_000L), 7L,
                enumConstant(flow, "HUMAN_LEADER"), enumConstant(startStage, "FULL_RUN"),
                AgentLpqSession.HumanRolePreference.DEFAULT, Set.of());
        Field sessionField = runType.getDeclaredField("session");
        sessionField.setAccessible(true);
        sessionField.set(fixture, session);
        Field runs = AgentLpqTestService.class.getDeclaredField("RUNS");
        runs.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> byOperator = (Map<Integer, Object>) runs.get(null);
        for (Character member : everyone) {
            when(member.getMapId()).thenReturn(AgentLpqDefinition.stage(3).mapId());
        }
        session.bindEventInstance(event);
        session.transition(AgentLpqSession.Phase.STAGE_3, 3_000L);
        byOperator.put(CREW, fixture);
        try {
            assertEquals(List.of("Checkpoints are for a companion run: !lpqtest party prod first."), checkpoint("8"));
            verify(maps, never()).changeMapNear(any(), any(), any());
            assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase());
        } finally {
            byOperator.remove(CREW, fixture);
        }
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) return constant;
        }
        throw new AssertionError(type + " has no " + name);
    }

    @Test
    void notBeforeThePartyIsInside() {
        assertEquals(List.of(AgentLpqTestServicePartyProdTest.ADOPTED),
                AgentLpqTestService.execute(crew, new String[]{"party", "prod", "7"}, 2_000L));
        assertEquals(List.of("The LPQ party has not entered yet."), checkpoint("5"));
        session.transition(AgentLpqSession.Phase.ENTERING, 3_000L);
        assertEquals(List.of("The LPQ party has not entered yet."), checkpoint("5"));
        verify(maps, never()).changeMapNear(any(), any(), any());
    }

    @Test
    void onlyStagesThreeToNine() {
        adoptAndEnter(AgentLpqSession.Phase.STAGE_1);
        for (String bad : List.of("2", "1", "10", "0", "x")) {
            assertEquals(List.of("Syntax: !lpqtest checkpoint <3-9>"), checkpoint(bad), bad);
        }
        assertEquals(List.of("Syntax: !lpqtest checkpoint <3-9>"), checkpoint());
        verify(maps, never()).changeMapNear(any(), any(), any());
        verify(event, never()).setProperty(anyString(), anyString());
    }

    @Test
    void neverBackwardsNorInPlace() {
        adoptAndEnter(AgentLpqSession.Phase.STAGE_5);
        assertEquals(List.of("The party is already at or past stage 4."), checkpoint("4"));
        assertEquals(List.of("The party is already at or past stage 5."), checkpoint("5"));
        session.transition(AgentLpqSession.Phase.BONUS, 3_500L);
        assertEquals(List.of("The party is already at or past stage 9."), checkpoint("9"));
        verify(maps, never()).changeMapNear(any(), any(), any());
    }

    @Test
    void everyMemberMustBeInsideTheInstance() {
        adoptAndEnter(AgentLpqSession.Phase.STAGE_3);
        Character kestrel = everyone.get(2);
        when(kestrel.getMapId()).thenReturn(AgentLpqDefinition.EXIT_MAP);
        assertEquals(List.of("Every member must be inside the LPQ instance first."), checkpoint("8"));
        when(kestrel.getMapId()).thenReturn(AgentLpqDefinition.stage(3).mapId());
        when(kestrel.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertEquals(List.of("Every member must be inside the LPQ instance first."), checkpoint("8"));
        when(kestrel.getEventInstance()).thenReturn(event);
        when(characters.findOnlineCharacterById(CREW)).thenReturn(null);
        assertEquals(List.of("Every member must be inside the LPQ instance first."), checkpoint("8"));
        verify(maps, never()).changeMapNear(any(), any(), any());
        verify(event, never()).setProperty(anyString(), anyString());
        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase());
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    private Character member(int id, String name) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(AgentLpqDefinition.RECRUIT_MAP);
        when(member.getEventInstance()).thenReturn(event);
        when(member.getLevel()).thenReturn(48);
        return member;
    }
}
