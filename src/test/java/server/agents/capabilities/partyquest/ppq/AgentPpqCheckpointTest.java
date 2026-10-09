package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.auth.AgentControlService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentIdentityGateway;
import server.agents.integration.AgentIdentityGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.MapGateway;
import server.agents.registry.AgentResolvedCharacter;
import server.life.LifeFactory;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqAdmitFixture.BEN;

/**
 * PPQ Step 1 (plan 4.7): "!ppqtest checkpoint <medals|deck1|deck2|doors|boss>" takes a party prod run already inside
 * PPQ forward to a stage. The event is left as the stock stages before it would leave it (curStage one short, the
 * medal waves done after the medals, the cleared maps empty, the boss spawned as davy_next4.js spawns him), and every
 * member is moved inside the instance to the target map's first portal, so the stock timer restarts for the stage.
 * Only an adopted run inside PPQ gets one.
 */
class AgentPpqCheckpointTest {
    private static final String ADOPTED = AgentPpqTestServicePartyProdTest.ADOPTED;

    private PpqAdmitFixture fx;
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final Map<String, String> properties = new HashMap<>();
    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private MockedStatic<LifeFactory> life;
    private MapGateway moves;

    @BeforeEach
    void open() {
        fx = new PpqAdmitFixture();
        when(fx.party.onlineMembers(any())).thenAnswer(call -> fx.party());
        MockedStatic<AgentAuthorityService> authority = open(AgentAuthorityService.class);
        authority.when(() -> AgentAuthorityService.mayOperate(any())).thenReturn(true);
        open(AgentPartyQuestLifecycleRuntime.class);
        life = open(LifeFactory.class);
        moves = AgentMapGatewayRuntime.map();
        doAnswer(call -> properties.put(call.getArgument(0), String.valueOf((Object) call.getArgument(1))))
                .when(event).setIntProperty(anyString(), any());
        doAnswer(call -> properties.put(call.getArgument(0), call.getArgument(1)))
                .when(event).setProperty(anyString(), anyString());
        when(event.getProperty(anyString())).thenAnswer(call -> properties.get((String) call.getArgument(0)));
        when(event.getMapInstance(anyInt())).thenAnswer(call -> maps.computeIfAbsent(call.getArgument(0), this::map));
        properties.put("curStage", "1");
        properties.put("level", "70");
        properties.put("openedChests", "0");
    }

    @AfterEach
    void close() {
        AgentPpqTestService.execute(fx.ben, new String[] {"stop"}, 99_000L);
        for (AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(BEN);
             engagement != null; engagement = AgentPartyQuestEngagementRegistry.forOperator(BEN)) {
            AgentPartyQuestEngagementRegistry.remove(engagement);
        }
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
        fx.close();
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    private MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        Portal portal = mock(Portal.class);
        when(portal.getPosition()).thenReturn(new Point(id % 1_000, 50));
        when(map.getPortal(0)).thenReturn(portal);
        when(map.getId()).thenReturn(id);
        return map;
    }

    private List<String> ppqtest(long nowMs, String... params) {
        return AgentPpqTestService.execute(fx.ben, params, nowMs);
    }

    /** Ben's party adopted and inside PPQ: the session is bound to the event every member is in. */
    private AgentPpqSession inside() {
        assertEquals(List.of(ADOPTED), ppqtest(2_000L, "party", "prod", "7"));
        AgentPpqSession session = AgentPpqSessionRegistry.forOperator(BEN);
        session.bindEventInstance(event);
        for (Character member : fx.party()) {
            when(member.getEventInstance()).thenReturn(event);
        }
        return session;
    }

    @Test
    void eachTargetLeavesTheEventAsTheStagesBeforeItWould() {
        life.when(() -> LifeFactory.getMonster(anyInt())).thenAnswer(call -> mock(Monster.class));
        int[] curStage = {1, 2, 3, 4, 5};
        AgentPpqTestService.Checkpoint[] targets = AgentPpqTestService.Checkpoint.values();
        for (int i = 0; i < targets.length; i++) {
            AgentPpqTestService.Checkpoint target = targets[i];
            properties.clear();
            properties.put("level", "70");
            properties.put("openedChests", "0");
            maps.clear();

            AgentPpqTestService.prepare(event, target);

            assertEquals(String.valueOf(curStage[i]), properties.get("curStage"), target.name());
            assertEquals(target == AgentPpqTestService.Checkpoint.MEDALS ? null : "3", properties.get("stage2"),
                    target.name());
            assertEquals(target == AgentPpqTestService.Checkpoint.BOSS ? "true" : null,
                    properties.get("spawnedBoss"), target.name());
        }
    }

    @Test
    void theMapsBeforeTheTargetAreEmptiedAndNoOther() {
        AgentPpqTestService.prepare(event, AgentPpqTestService.Checkpoint.DOORS);

        for (int cleared : List.of(AgentPpqDefinition.ENTRY_MAP, AgentPpqDefinition.MEDAL_MAP,
                AgentPpqDefinition.DECK_ONE_MAP, AgentPpqDefinition.DECK_TWO_MAP)) {
            verify(maps.get(cleared)).killAllMonsters();
        }
        assertNull(maps.get(AgentPpqDefinition.DOOR_MAP), "the target keeps its monsters");
        assertNull(maps.get(AgentPpqDefinition.BOSS_MAP));
        life.verifyNoInteractions();
    }

    @Test
    void theBossIsTheOneTheOpenedChestsCallFor() {
        int[] bosses = {9_300_119, 9_300_105, 9_300_106};
        for (int chests = 0; chests < bosses.length; chests++) {
            properties.clear();
            properties.put("level", "70");
            properties.put("openedChests", String.valueOf(chests));
            maps.clear();
            Monster boss = mock(Monster.class);
            int id = bosses[chests];
            life.when(() -> LifeFactory.getMonster(id)).thenReturn(boss);

            AgentPpqTestService.prepare(event, AgentPpqTestService.Checkpoint.BOSS);

            verify(boss).changeDifficulty(70, true);
            verify(maps.get(AgentPpqDefinition.BOSS_MAP)).spawnMonsterOnGroundBelow(boss, new Point(777, 140));
            verify(maps.get(AgentPpqDefinition.DOOR_MAP)).killAllMonsters();
        }
    }

    @Test
    void aBossAlreadyThereIsNotSpawnedAgain() {
        properties.put("spawnedBoss", "true");

        AgentPpqTestService.prepare(event, AgentPpqTestService.Checkpoint.BOSS);

        life.verifyNoInteractions();
        verify(maps.get(AgentPpqDefinition.DOOR_MAP)).killAllMonsters();
    }

    @Test
    void everyMemberIsMovedToTheTargetsFirstPortal() {
        try (PpqLogLines lines = new PpqLogLines(AgentPpqTestService.class)) {
            AgentPpqSession session = inside();

            assertEquals(List.of("PPQ checkpoint: everyone is at the deck2. Earlier stages are skipped for this run"
                    + " only."), ppqtest(5_000L, "checkpoint", "deck2"));

            MapleMap deck = maps.get(AgentPpqDefinition.DECK_TWO_MAP);
            for (Character member : fx.party()) {
                verify(moves).changeMapNear(member, deck, new Point(300, 50));
            }
            assertEquals("3", properties.get("curStage"));
            assertEquals(5_000L, session.lastProgressAtMs());
            assertEquals(List.of("PPQ checkpoint: session=" + session.sessionId() + " stage=deck2 by=" + BEN),
                    lines.startingWith("PPQ checkpoint:"));
        }
    }

    @Test
    void theTargetNameIsCheckedFirst() {
        inside();
        for (String[] params : List.of(new String[] {"checkpoint"}, new String[] {"checkpoint", "deck3"})) {
            assertEquals(List.of("Syntax: !ppqtest checkpoint <medals|deck1|deck2|doors|boss>"),
                    ppqtest(5_000L, params));
        }
        verifyNothingMoved();
    }

    @Test
    void onlyAPartyProdRunGetsOne() {
        assertEquals(List.of("Checkpoints apply only to a party prod companion run."),
                ppqtest(5_000L, "checkpoint", "deck1"));

        // A fixture run (Ben joins five Agents being prepared) is not a companion run.
        when(fx.party.snapshot(fx.ben)).thenReturn(null);
        AgentControlService control = mock(AgentControlService.class);
        when(control.resolveCharacterByName(anyString()))
                .thenReturn(new AgentResolvedCharacter(900, "PPQer01", 1, null));
        open(AgentControlService.class).when(AgentControlService::getInstance).thenReturn(control);
        AgentIdentityGateway identities = mock(AgentIdentityGateway.class);
        open(AgentIdentityGatewayRuntime.class).when(AgentIdentityGatewayRuntime::identities).thenReturn(identities);
        try {
            when(identities.isActiveAgent(anyInt())).thenReturn(true);
        } catch (java.sql.SQLException impossible) {
            throw new AssertionError(impossible);
        }
        assertEquals("Use !ppqtest status.", ppqtest(3_000L, "humanmember").getLast());

        assertEquals(List.of("Checkpoints apply only to a party prod companion run."),
                ppqtest(5_000L, "checkpoint", "deck1"));
        verifyNothingMoved();
    }

    @Test
    void theRunMustBeInsidePpq() {
        AgentPpqSession session = inside();
        session.clearEventInstance();
        assertEquals(List.of("Enter PPQ through Guon before a checkpoint."), ppqtest(5_000L, "checkpoint", "deck1"));

        session.bindEventInstance(event);
        when(fx.ben.getEventInstance()).thenReturn(null);
        assertEquals(List.of("Enter PPQ through Guon before a checkpoint."), ppqtest(5_000L, "checkpoint", "deck1"));
        when(fx.ben.getEventInstance()).thenReturn(event);

        when(fx.tobi.getEventInstance()).thenReturn(null);
        assertEquals(List.of("Every member must be inside the PPQ instance first."),
                ppqtest(5_000L, "checkpoint", "deck1"));
        when(fx.tobi.getEventInstance()).thenReturn(event);

        session.beginTermination();
        assertEquals(List.of("Enter PPQ through Guon before a checkpoint."), ppqtest(5_000L, "checkpoint", "deck1"));
        verifyNothingMoved();
    }

    private void verifyNothingMoved() {
        verify(moves, never()).changeMapNear(any(), any(), any());
        verify(event, never()).setIntProperty(anyString(), any());
    }
}
