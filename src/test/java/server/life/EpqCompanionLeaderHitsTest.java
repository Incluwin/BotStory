package server.life;

import client.Character;
import org.junit.jupiter.api.AfterEach;
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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Step 2.6: a companion run plays EPQ stock, so its human leader hits Poison Flowers (930000400) and Stone Bugs outside
 * the fountain (930000200) as any player would; only its companions hold back. A stock or Agent-led run keeps today's
 * preserve for every member, and a bug in the fountain is never preserved.
 */
class EpqCompanionLeaderHitsTest {
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final MapleMap map = mock(MapleMap.class);
    private final List<AgentEpqSession> sessions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        sessions.forEach(AgentEpqSessionRegistry::remove);
    }

    /** Ben (1) human leader, Mira (2), Tobi (3), Una (4) companions. */
    private AgentEpqSession session(boolean companionRun) throws Exception {
        AgentEpqSession session = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, 7L, 1, 10L);
        session.addMember(1, AgentEpqMemberState.MemberType.HUMAN);
        for (int id = 2; id <= 4; id++) session.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        session.setLeadership(1, 2);
        Method adopt = AgentEpqSession.class.getDeclaredMethod("setCompanionAdopted", boolean.class);
        adopt.setAccessible(true);
        adopt.invoke(session, companionRun);
        session.bindEventInstance(event);
        AgentEpqSessionRegistry.registerComplete(session);
        sessions.add(session);
        return session;
    }

    private Character member(int id, int mapId) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getMapId()).thenReturn(mapId);
        when(member.getEventInstance()).thenReturn(event);
        return member;
    }

    private static Monster flower() {
        Monster flower = mock(Monster.class);
        when(flower.getId()).thenReturn(AgentEpqDefinition.POISON_FLOWER);
        return flower;
    }

    private Monster bug(int x) {
        Reactor pond = mock(Reactor.class);
        when(map.getReactorById(AgentEpqDefinition.POND_REACTOR)).thenReturn(pond);
        when(pond.getArea()).thenReturn(new Rectangle(-50, -50, 100, 100));
        Monster bug = mock(Monster.class);
        when(bug.getId()).thenReturn(AgentEpqDefinition.STAGE_TWO_MOB);
        when(bug.getMap()).thenReturn(map);
        when(bug.getPosition()).thenReturn(new Point(x, 0));
        return bug;
    }

    @Test
    void theHumanLeaderOfACompanionRunHitsFlowersAsInStock() throws Exception {
        session(true);
        Monster flower = flower();

        assertFalse(EpqPoisonFlowerCaptureService.preserveForAgentCapture(
                member(1, AgentEpqDefinition.STAGE_FOUR_MAP), flower));
        assertTrue(EpqPoisonFlowerCaptureService.preserveForAgentCapture(
                member(2, AgentEpqDefinition.STAGE_FOUR_MAP), flower), "pin: a companion holds back");
    }

    @Test
    void pinEveryMemberOfAStockRunHoldsBackOnFlowers() throws Exception {
        session(false);
        Monster flower = flower();

        assertTrue(EpqPoisonFlowerCaptureService.preserveForAgentCapture(
                member(1, AgentEpqDefinition.STAGE_FOUR_MAP), flower));
        assertTrue(EpqPoisonFlowerCaptureService.preserveForAgentCapture(
                member(2, AgentEpqDefinition.STAGE_FOUR_MAP), flower));
    }

    @Test
    void theHumanLeaderOfACompanionRunHitsStoneBugsAsInStock() throws Exception {
        session(true);
        Monster outside = bug(200);

        assertFalse(EpqPoisonFlowerCaptureService.preserveForAgentLure(
                member(1, AgentEpqDefinition.STAGE_TWO_MAP), outside));
        assertTrue(EpqPoisonFlowerCaptureService.preserveForAgentLure(
                member(2, AgentEpqDefinition.STAGE_TWO_MAP), outside), "pin: a companion holds back");
    }

    @Test
    void pinEveryMemberOfAStockRunHoldsBackOnStoneBugs() throws Exception {
        session(false);
        Monster outside = bug(200);

        assertTrue(EpqPoisonFlowerCaptureService.preserveForAgentLure(
                member(1, AgentEpqDefinition.STAGE_TWO_MAP), outside));
        assertTrue(EpqPoisonFlowerCaptureService.preserveForAgentLure(
                member(2, AgentEpqDefinition.STAGE_TWO_MAP), outside));
    }

    @Test
    void pinABugInTheFountainIsNeverPreserved() throws Exception {
        session(true);
        Monster inside = bug(0);

        for (int id = 1; id <= 2; id++) {
            assertFalse(EpqPoisonFlowerCaptureService.preserveForAgentLure(
                    member(id, AgentEpqDefinition.STAGE_TWO_MAP), inside));
        }
    }
}
