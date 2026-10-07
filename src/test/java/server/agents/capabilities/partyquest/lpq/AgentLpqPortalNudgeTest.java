package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ C2.7 (revision P2): after a stage's clear the stock gather stays (the companions walk to the next portal,
 * stop there, and nobody goes through until the leader is there too); a companion run adds one call: LEADER_NUDGE_MS
 * after the clear, while he is still away from the portal, a companion at the portal says N1 once.
 */
class AgentLpqPortalNudgeTest {
    private static final int S3 = AgentLpqDefinition.stage(3).mapId();
    private static final int S4 = AgentLpqDefinition.stage(4).mapId();
    private static final Point PORTAL = new Point(300, 0);
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession clearedStageThree(boolean companion) {
        crew = fx.crew(S3, new Point(290, 0));
        fx.moveTo(crew.getFirst(), new Point(-300, 0));
        fx.portal(S3, 2, PORTAL, S4);
        fx.property("3stageclear", "1");
        AgentLpqSession session = fx.session(companion, AgentLpqSession.Phase.STAGE_3, crew.toArray(Character[]::new));
        session.narrateOnce("s3-start");
        return session;
    }

    @Test
    void theCompanionsWaitAtThePortalAndCallHimOnce() {
        AgentLpqSession session = clearedStageThree(true);

        fx.tickEvery(session, 3_000L, 32_000L, 1_000L);
        fx.neverSaid("s3-portal");
        fx.tickEvery(session, 33_000L, 40_000L, 1_000L);

        fx.said(session, "s3-portal", crew.get(1), "Crewfriend, the portal's open. come on.");
        verify(fx.lpq, never()).enterPortal(any(), anyInt());
        verify(fx.actions, never()).stagePosition(any(), any(), any());

        fx.moveTo(crew.getFirst(), new Point(280, 0));
        fx.tick(session, 41_000L);
        for (Character companion : crew.subList(1, 6)) verify(fx.lpq).enterPortal(companion, 2);
    }

    @Test
    void aStockGatherHasNoCall() {
        AgentLpqSession session = clearedStageThree(false);

        fx.tickEvery(session, 3_000L, 14_000L, 1_000L);

        fx.saidNothing();
        verify(fx.lpq, never()).enterPortal(any(), anyInt());
    }
}
