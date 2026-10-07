package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C3.3: the LPQ watchdog finds no live Agent to run a session. A companion run whose only companion is down
 * waits for its respawn (its host taps it out) up to MEMBER_RECOVERY_TIMEOUT_MS while five others stand; at five it
 * ends WENT_DOWN at once, in the downed companion's words. A stock run ends 'No live Agent remains' at once, as today.
 */
class AgentLpqWatchdogAwaitRespawnTest {
    private static final int S3 = AgentLpqDefinition.stage(3).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private Character cas;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    /** Crewfriend and {@code humans - 1} other players, and one companion (Cas), on stage 3; Cas is down. */
    private AgentLpqSession withOneCompanionDown(boolean companionRun, int humans) {
        List<Character> members = new ArrayList<>();
        String[] names = {"Crewfriend", "Ann", "Bo", "Cy", "Di"};
        for (int i = 0; i < humans; i++) members.add(fx.human(i + 1, names[i], S3, new Point(10 * i, 0)));
        cas = fx.companion(9, "Cas", S3, new Point(100, 0));
        members.add(cas);
        AgentLpqSession session = fx.session(companionRun, AgentLpqSession.Phase.STAGE_3,
                members.toArray(Character[]::new));
        when(cas.getHp()).thenReturn(0);
        return session;
    }

    @Test
    void aCompanionRunWaitsForItsDownedCompanionWhileFiveStand() {
        AgentLpqSession session = withOneCompanionDown(true, 5);

        AgentLpqWatchdogRuntime.tick(session, 3_000L);
        AgentLpqWatchdogRuntime.tick(session, 47_999L);

        assertEquals(AgentLpqSession.Phase.STAGE_3, session.phase(), "it waits");
        assertTrue(session.member(cas.getId()).down());
        verify(fx.event, never()).dispose();

        AgentLpqWatchdogRuntime.tick(session, 48_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase(), "not past MEMBER_RECOVERY_TIMEOUT_MS");
        assertEquals("No live Agent remains", session.failure());
    }

    @Test
    void atFiveTheRunEndsWentDownAtOnce() {
        AgentLpqSession session = withOneCompanionDown(true, 4);

        AgentLpqWatchdogRuntime.tick(session, 3_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        fx.said(session, "failed", cas, "sorry, i went down. we need five for this, so let's head out.");
        verify(fx.event).dispose();
    }

    @Test
    void aStockRunEndsAtOnceAsToday() {
        AgentLpqSession session = withOneCompanionDown(false, 5);

        AgentLpqWatchdogRuntime.tick(session, 3_000L);

        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        assertEquals("No live Agent remains", session.failure());
        fx.saidNothing();
    }
}
