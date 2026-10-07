package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ C3.3: a companion run reads its live party every tick inside the tower. A member who left it is ejected (the
 * run goes on above five, and ends LEFT below); a lead passed to someone else ends the run; a player who drops out at
 * six is left behind, and a companion says so (T1).
 */
class AgentLpqCompanionRunPartyTest {
    private static final int S3 = AgentLpqDefinition.stage(3).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession onStageThree(Character... members) {
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_3, members);
        session.narrateOnce("s3-start");
        return session;
    }

    @Test
    void aCompanionWhoLeftThePartyAtSixIsEjectedAndTheRunGoesOn() {
        crew = fx.crew(S3, new Point(0, 0));
        AgentLpqSession session = onStageThree(crew.toArray(Character[]::new));
        fx.leaveParty(crew.get(2));

        fx.tick(session, 3_000L);

        assertNull(session.member(crew.get(2).getId()));
        assertEquals(5, session.memberCount());
        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase());
        fx.neverSaid("failed");
    }

    @Test
    void aMemberWhoLeftThePartyAtFiveEndsTheRunLeft() {
        crew = fx.crew(S3, new Point(0, 0)).subList(0, 5);
        AgentLpqSession session = onStageThree(crew.toArray(Character[]::new));
        fx.leaveParty(crew.get(2));

        fx.tick(session, 3_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        fx.said(session, "failed", crew.get(1), "Kes left, and we need five for this. let's head out.");
        verify(fx.event).dispose();
    }

    @Test
    void aLeadPassedToSomeoneElseEndsTheRun() {
        crew = fx.crew(S3, new Point(0, 0));
        AgentLpqSession session = onStageThree(crew.toArray(Character[]::new));
        fx.passLead(crew.get(1));

        fx.tick(session, 3_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        assertEquals("The live LPQ party is no longer led by the run's leader", session.failure());
        fx.said(session, "failed", crew.get(1), "we can't finish this one. let's head out.");
    }

    @Test
    void aPlayerWhoDropsOutAtSixIsLeftBehindAndACompanionSaysSo() {
        Character crewfriend = fx.human(1, "Crewfriend", S3, new Point(0, 0));
        Character ann = fx.human(2, "Ann", S3, new Point(5, 0));
        Character kes = fx.companion(3, "Kes", S3, new Point(10, 0));
        Character pel = fx.companion(4, "Pel", S3, new Point(15, 0));
        Character hal = fx.companion(5, "Hal", S3, new Point(20, 0));
        Character cas = fx.companion(6, "Cas", S3, new Point(25, 0));
        AgentLpqSession session = onStageThree(crewfriend, ann, kes, pel, hal, cas);
        fx.offline(ann);

        fx.tick(session, 3_000L);

        assertNull(session.member(ann.getId()));
        assertEquals(5, session.memberCount());
        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase());
        fx.said(session, "drop-2", kes, "Ann had to go. we'll keep going with five.");
        verify(fx.event, never()).dispose();
    }
}
