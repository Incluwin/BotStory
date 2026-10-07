package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /** The last companion left: the run lets go and the five players carry on in the event by themselves. */
    @Test
    void whenTheLastCompanionLeavesTheHumansCarryOnInTheEvent() {
        String[] names = {"Crewfriend", "Ann", "Bo", "Cy", "Di"};
        List<Character> members = new ArrayList<>();
        for (int i = 0; i < names.length; i++) members.add(fx.human(i + 1, names[i], S3, new Point(5 * i, 0)));
        Character cas = fx.companion(6, "Cas", S3, new Point(30, 0));
        members.add(cas);
        AgentLpqSession session = onStageThree(members.toArray(Character[]::new));
        fx.leaveParty(cas);

        fx.tick(session, 3_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase(), "the run is released");
        assertEquals("every companion left; the humans carry on", session.failure());
        verify(fx.event, never()).dispose();
        for (Character human : members.subList(0, 5)) verify(fx.event, never()).exitPlayer(human);
        fx.neverSaid("failed");
    }

    /** A companion that stays unloaded past MEMBER_RECOVERY_TIMEOUT_MS ends the run GONE, named. */
    @Test
    void aCompanionMissingPastTheRecoveryTimeoutEndsTheRun() {
        crew = fx.crew(S3, new Point(0, 0));
        AgentLpqSession session = onStageThree(crew.toArray(Character[]::new));
        fx.offline(crew.get(2));

        fx.tick(session, 3_000L);
        fx.tick(session, 47_999L);
        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase(), "it may come back");
        fx.tick(session, 48_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        fx.said(session, "failed", crew.get(1), "Kes dropped out, so we can't finish this one. let's head out.");
    }

    /** A downed companion standing in the event again is back only from the second tick that sees it so. */
    @Test
    void aCompanionUpAgainInTheEventIsBackOnceItsRevivalIsConfirmed() {
        crew = fx.crew(S3, new Point(0, 0));
        AgentLpqSession session = onStageThree(crew.toArray(Character[]::new));
        Character pel = crew.get(3);
        session.member(pel.getId()).markDown(1_500L, 0);

        fx.tick(session, 2_000L);
        assertTrue(session.member(pel.getId()).down(), "one sighting is not enough");
        fx.tick(session, 3_000L);

        assertFalse(session.member(pel.getId()).down());
        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase());
    }
}
