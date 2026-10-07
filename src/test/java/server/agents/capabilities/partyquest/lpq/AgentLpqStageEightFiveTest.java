package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ C3.4 (decision D-c): a five-member companion run has four companions for stage 8's five boxes, so its leader
 * stands on the fifth, after them. He is given a box like the others but never moved: a companion tells him which
 * (L8a "you take the ... box"), and which to move to when the next formation changes his (L8m). With him on his box the
 * formation stands and S8b is said.
 */
class AgentLpqStageEightFiveTest {
    private static final int S8 = AgentLpqDefinition.stage(8).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private void everyoneToTheirBoxes(AgentLpqSession session) {
        for (Character member : crew) {
            fx.moveTo(member, AgentLpqStageEightCoachTest.onBox(session.member(member.getId()).assignedPlatform()));
        }
    }

    @Test
    void theLeaderTakesTheFifthBoxAndIsToldWhichAndWhereToMove() {
        fx.areas(S8, AgentLpqStageEightCoachTest.boxes());
        fx.npcAt(AgentLpqDefinition.stage(8).npcId(), new Point(-300, -100));
        crew = fx.crew(S8, new Point(-300, -100)).subList(0, 5);
        Character leader = crew.getFirst();
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_8, crew.toArray(Character[]::new));
        fx.property("statusStg8", "0");

        fx.tick(session, 3_000L);

        assertEquals(AgentLpqSession.Phase.STAGE_8, session.phase(), "no 'needs five nonleader participants' end");
        int box = session.member(leader.getId()).assignedPlatform();
        assertTrue(box >= 1 && box <= 9, "the leader holds a box: " + box);
        for (Character companion : crew.subList(1, 5)) {
            assertNotEquals(box, session.member(companion.getId()).assignedPlatform());
        }
        fx.said(session, "s8-yours", crew.get(1),
                "Crewfriend, you take the " + AgentLpqCompanionPlay.boxName(box) + " box.");
        fx.neverSaid("s8-next-1");

        everyoneToTheirBoxes(session);
        fx.tick(session, 4_000L);
        fx.said(session, "s8-next-1", crew.get(1), "we're on the boxes. try the balloon.");

        long now = 5_000L;
        int moved = box;
        while (moved == box && session.stage8Attempt() < 60) {
            session.advanceStage8(now);
            fx.tick(session, now);
            moved = session.member(leader.getId()).assignedPlatform();
            now += 1_000L;
        }
        assertNotEquals(box, moved, "some later formation moves him");
        fx.said(session, "s8-move-" + session.stage8Attempt(), crew.get(1),
                "Crewfriend, move to the " + AgentLpqCompanionPlay.boxName(moved) + " box.");
        verify(fx.mapGateway, never()).changeMapNear(eq(leader), any(), any());
        verify(fx.actions, never()).navigate(eq(fx.entry(leader)), any(), anyBoolean());
    }

    @Test
    void theBoxNamesReadLikeAPlayersAndStayInRange() {
        assertEquals(List.of("top left", "top right", "middle left", "middle", "middle right", "bottom left",
                        "second from the left at the bottom", "third from the left at the bottom", "bottom right"),
                java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(AgentLpqCompanionPlay::boxName).toList());
        assertEquals("Crewfriend, you take the middle box.", AgentLpqCompanionPlay.yourBoxLine("Crewfriend", 4));
        assertEquals("Crewfriend, move to the bottom right box.", AgentLpqCompanionPlay.moveToBoxLine("Crewfriend", 9));
    }
}
