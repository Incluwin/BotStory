package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

/**
 * P6g LPQ C2.7 (decision D-b, stock stage 8): the companions take the boxes in the coordinator's order and coach the
 * leader like players: S8a while the blue balloon has not explained itself, S8b when the first formation stands,
 * S8n ("next one, try the balloon.") for each later one, and S8x when he stands on a box in a six-member run.
 */
class AgentLpqStageEightCoachTest {
    static final int S8 = AgentLpqDefinition.stage(8).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** Nine boxes in a row: box n (1-9) is the area x 100(n-1)..100(n-1)+60, y -250..-210. */
    static List<Rectangle> boxes() {
        List<Rectangle> boxes = new ArrayList<>();
        for (int box = 1; box <= 9; box++) boxes.add(new Rectangle(100 * (box - 1), -250, 60, 40));
        return boxes;
    }

    static Point onBox(int box) {
        return new Point(100 * (box - 1) + 30, -230);
    }

    private AgentLpqSession stageEight() {
        fx.areas(S8, boxes());
        fx.npcAt(AgentLpqDefinition.stage(8).npcId(), new Point(-300, -100));
        crew = fx.crew(S8, new Point(-300, -100));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_8, crew.toArray(Character[]::new));
        fx.property("statusStg8", "-1");
        return session;
    }

    private void everyoneToTheirBoxes(AgentLpqSession session) {
        for (Character member : crew.subList(1, 6)) {
            fx.moveTo(member, onBox(session.member(member.getId()).assignedPlatform()));
        }
    }

    @Test
    void theLeaderIsCoachedThroughEachFormation() {
        AgentLpqSession session = stageEight();
        fx.tickEvery(session, 3_000L, 5_000L, 1_000L);
        fx.said(session, "s8-start", crew.get(1), "Crewfriend, talk to the blue balloon first.\nthen we stand"
                + " on the boxes and you ask it if we got it right.");

        fx.property("statusStg8", "0");
        fx.tick(session, 6_000L);
        fx.neverSaid("s8-next-1");
        everyoneToTheirBoxes(session);
        fx.tick(session, 7_000L);
        fx.tick(session, 8_000L);
        fx.said(session, "s8-next-1", crew.get(1), "we're on the boxes. try the balloon.");

        session.advanceStage8(9_000L);
        fx.tick(session, 9_000L);
        fx.neverSaid("s8-next-2");
        everyoneToTheirBoxes(session);
        fx.tick(session, 10_000L);
        fx.said(session, "s8-next-2", crew.get(1), "next one, try the balloon.");
    }

    @Test
    void theLeaderOnABoxIsToldToStepOff() {
        AgentLpqSession session = stageEight();
        fx.property("statusStg8", "0");
        fx.tick(session, 3_000L);
        everyoneToTheirBoxes(session);
        int free = 1;
        while (true) {
            int candidate = free;
            if (crew.subList(1, 6).stream().noneMatch(member ->
                    session.member(member.getId()).assignedPlatform() == candidate)) break;
            free++;
        }
        fx.moveTo(crew.getFirst(), onBox(free));

        fx.tick(session, 4_000L);

        fx.said(session, "s8-off", crew.get(1), "Crewfriend, stay off the boxes, only five can stand on them.");
        fx.neverSaid("s8-next-1");
    }

    @Test
    void aStockRunHasNoCoaching() {
        fx.areas(S8, boxes());
        crew = fx.crew(S8, new Point(-300, -100));
        AgentLpqSession session = fx.session(false, AgentLpqSession.Phase.STAGE_8, crew.toArray(Character[]::new));
        fx.property("statusStg8", "0");
        fx.tick(session, 3_000L);
        everyoneToTheirBoxes(session);
        fx.tick(session, 4_000L);
        fx.saidNothing();
        assertEquals(0, session.stage8Attempt());
        fx.dialogue.verify(() -> AgentLpqDialogue.sayMapNow(anyString(), eq("s8-next-1"), any(), anyString()), never());
    }
}
