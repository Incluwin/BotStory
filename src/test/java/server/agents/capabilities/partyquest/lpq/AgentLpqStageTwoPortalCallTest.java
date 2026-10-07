package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ review fixes (stage 1 to 2): after the scouts clear the trap ("trap's clear, let's go in.") the companions go
 * in; in a companion run one of them waits at the stage-2 portal while the leader is still on stage 1, calls him once
 * LEADER_NUDGE_MS after the clear (N1, key s1-portal) and follows him in. A stock run sends everyone in at once.
 */
class AgentLpqStageTwoPortalCallTest {
    private static final int S1 = AgentLpqDefinition.stage(1).mapId();
    private static final int S2 = AgentLpqDefinition.stage(2).mapId();
    private static final Point PORTAL = new Point(300, -192);
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;
    private Character pel;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** The trap is clear at 2 s: Mira (a scout) stands in the trap room, Kes in stage 2, the rest on stage 1. */
    private AgentLpqSession trapCleared(boolean companion) {
        fx.npcAt(AgentLpqDefinition.stage(1).npcId(), new Point(-157, -192));
        crew = fx.crew(S1, new Point(-157, -192));
        pel = crew.get(3);
        fx.onMap(crew.get(1), AgentLpqDefinition.STAGE_2_TRAP_MAP, new Point(0, 0));
        fx.onMap(crew.get(2), S2, new Point(0, 0));
        fx.moveTo(pel, new Point(100, -192));
        fx.portal(S1, 2, PORTAL, S2);
        AgentLpqSession session = fx.session(companion, AgentLpqSession.Phase.STAGE_2, crew.toArray(Character[]::new));
        session.markStage2ScoutPlanAnnounced(2_000L);
        session.markStage2TrapClearAnnounced(2_000L);
        return session;
    }

    @Test
    void oneCompanionWaitsAtThePortalAndCallsHimOnce() {
        AgentLpqSession session = trapCleared(true);
        fx.tick(session, 3_000L);
        verify(fx.actions).navigate(fx.entry(pel), PORTAL, true);

        fx.moveTo(pel, new Point(305, -192));
        fx.tickEvery(session, 4_000L, 31_000L, 1_000L);
        fx.neverSaid("s1-portal");

        fx.tickEvery(session, 32_000L, 60_000L, 1_000L);
        fx.said(session, "s1-portal", pel, "Crewfriend, the portal's open. come on.");
        verify(fx.lpq, never()).enterPortal(eq(pel), anyInt());
        for (Character other : List.of(crew.get(4), crew.get(5))) {
            verify(fx.actions, atLeastOnce()).navigate(fx.entry(other), PORTAL, true);
        }

        fx.onMap(crew.getFirst(), S2, new Point(0, 0));
        fx.tick(session, 61_000L);
        verify(fx.lpq, atLeastOnce()).enterPortal(eq(pel), anyInt());
    }

    @Test
    void aStockRunSendsEveryoneInAtOnce() {
        AgentLpqSession session = trapCleared(false);
        fx.moveTo(pel, new Point(305, -192));
        fx.tickEvery(session, 3_000L, 40_000L, 1_000L);

        verify(fx.lpq, atLeastOnce()).enterPortal(eq(pel), anyInt());
        fx.saidNothing();
    }
}
