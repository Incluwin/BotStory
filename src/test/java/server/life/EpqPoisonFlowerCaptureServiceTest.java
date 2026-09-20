package server.life;

import client.Character;
import constants.id.MobId;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.epq.AgentEpqDefinition;
import server.agents.capabilities.partyquest.epq.AgentEpqMemberState;
import server.agents.capabilities.partyquest.epq.AgentEpqSession;
import server.agents.capabilities.partyquest.epq.AgentEpqSessionRegistry;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EpqPoisonFlowerCaptureServiceTest {
    @Test
    void usesTheAuthoredStrictlyBelowFortyPercentThreshold() {
        Monster flower = mock(Monster.class);
        when(flower.getId()).thenReturn(MobId.POISON_FLOWER);
        when(flower.isAlive()).thenReturn(true);
        when(flower.getMaxHp()).thenReturn(7_500);
        when(flower.getHp()).thenReturn(3_000);
        assertFalse(EpqPoisonFlowerCaptureService.ready(flower));

        when(flower.getHp()).thenReturn(2_999);
        assertTrue(EpqPoisonFlowerCaptureService.ready(flower));
    }

    @Test
    void agentLureTargetIsPreservedOnlyUntilItReachesTheTree() {
        EventInstanceManager event = mock(EventInstanceManager.class);
        AgentEpqSession session = new AgentEpqSession(
                AgentEpqSession.Mode.TEST_OBSERVATION, 7L, 7, 10L);
        for (int id = 7; id <= 10; id++) {
            session.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        }
        session.bindEventInstance(event);
        AgentEpqSessionRegistry.registerComplete(session);
        try {
            Character attacker = mock(Character.class);
            when(attacker.getId()).thenReturn(7);
            when(attacker.getMapId()).thenReturn(AgentEpqDefinition.STAGE_TWO_MAP);
            when(attacker.getEventInstance()).thenReturn(event);
            MapleMap map = mock(MapleMap.class);
            Reactor pond = mock(Reactor.class);
            when(map.getReactorById(AgentEpqDefinition.POND_REACTOR)).thenReturn(pond);
            when(pond.getArea()).thenReturn(new Rectangle(-50, -50, 100, 100));
            Monster bug = mock(Monster.class);
            when(bug.getId()).thenReturn(AgentEpqDefinition.STAGE_TWO_MOB);
            when(bug.getMap()).thenReturn(map);
            when(bug.getPosition()).thenReturn(new Point(200, 0));

            assertTrue(EpqPoisonFlowerCaptureService.preserveForAgentLure(attacker, bug));
            when(bug.getPosition()).thenReturn(new Point(0, 0));
            assertFalse(EpqPoisonFlowerCaptureService.preserveForAgentLure(attacker, bug));
        } finally {
            AgentEpqSessionRegistry.remove(session);
        }
    }
}
