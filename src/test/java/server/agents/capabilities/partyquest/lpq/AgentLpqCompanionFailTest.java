package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C3.2 (section 10.6): the leader, the event's end and a stall end a companion run in the companions' words,
 * said by a companion ('LPQ parting:' in the log), then the stock release; before the party is inside the run ends
 * without a word. Amended B3: LudiPQ.js clearPQ stops the event timer before it marks the clear, so a tick in between
 * that reads no timer and no clear ends nothing. Amended B2: the after-clear limit says the wrap-up line first and
 * completes the run. Stock runs end as they always did, without a line.
 */
class AgentLpqCompanionFailTest {
    private static final Point AT = new Point(0, 0);
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private AgentLpqSession run(boolean companionRun, AgentLpqSession.Phase phase, int mapId) {
        crew = fx.crew(mapId, AT);
        AgentLpqSession session = fx.session(companionRun, phase, crew.toArray(Character[]::new));
        session.markEnteredEvent(1_000L);
        return session;
    }

    @Test
    void aLeaderWhoDropsOutIsNamedAndTheRunEndsAsAStockFailure() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_3, AgentLpqDefinition.stage(3).mapId());
        fx.offline(crew.getFirst());
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 5_000L);

            assertEquals(List.of("LPQ parting: session=" + session.sessionId() + " phase=STAGE_3 kind=GONE"
                    + " speaker=Mira pastTheClear=false"), log.startingWith("LPQ parting: "));
        }
        fx.said(session, "failed", crew.get(1), "Crewfriend dropped out, so we can't finish this one. let's head out.");
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        assertEquals("The LPQ event leader is unavailable", session.failure());
        verify(fx.event).dispose();
    }

    @Test
    void aLeaderWhoWentDownIsNamed() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_3, AgentLpqDefinition.stage(3).mapId());
        when(crew.getFirst().getHp()).thenReturn(0);

        fx.tick(session, 5_000L);

        fx.said(session, "failed", crew.get(1), "Crewfriend went down, so we can't finish this one. let's head out.");
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void theEventEndingAtItsTimerIsTheTimeRunningOut() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_8, AgentLpqDefinition.stage(8).mapId());
        fx.outside(crew.getFirst(), AgentLpqDefinition.EXIT_MAP);
        session.markProgress(2_690_000L);

        fx.tick(session, 1_000L + 2_698_500L);

        fx.said(session, "failed", crew.get(1), "we ran out of time. let's head out.");
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void theEventEndingEarlierIsGeneral() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_4, AgentLpqDefinition.stage(4).mapId());
        fx.outside(crew.getFirst(), AgentLpqDefinition.EXIT_MAP);
        session.markProgress(590_000L);

        fx.tick(session, 1_000L + 600_000L);

        fx.said(session, "failed", crew.get(1), "we can't finish this one. let's head out.");
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void theEntryWaitEndsTheRunWithoutAWord() {
        crew = fx.crew(AgentLpqDefinition.RECRUIT_MAP, AT);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.PREPARING, crew.toArray(Character[]::new));
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 1_000L + 30_000L);

            assertEquals(1, log.startingWith("LPQ parting: session=" + session.sessionId()
                    + " phase=PREPARING kind=SILENT").size());
        }
        fx.saidNothing();
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void aLeaderWhoLogsOffBeforeEntryEndsItWithoutAWord() {
        crew = fx.crew(AgentLpqDefinition.RECRUIT_MAP, AT);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.PREPARING, crew.toArray(Character[]::new));
        fx.offline(crew.getFirst());

        fx.tick(session, 5_000L);

        fx.saidNothing();
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
    }

    @Test
    void aStockRunEndsAsItAlwaysDidWithoutALine() {
        AgentLpqSession session = run(false, AgentLpqSession.Phase.STAGE_3, AgentLpqDefinition.stage(3).mapId());
        fx.offline(crew.getFirst());
        try (LpqLogLines log = new LpqLogLines(AgentLpqCoordinator.class)) {
            fx.tick(session, 5_000L);

            assertEquals(List.of(), log.startingWith("LPQ parting: "));
        }
        fx.saidNothing();
        assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
        assertEquals("The LPQ event leader is unavailable", session.failure());
    }

    /** Amended B3: the tick between clearPQ's stopEventTimer and its setEventCleared ends nothing. */
    @Test
    void theClearsOwnTimerStopEndsNothing() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.STAGE_9, AgentLpqDefinition.stage(9).mapId());
        session.markProgress(1_190_000L);
        when(fx.event.isTimerStarted()).thenReturn(false);
        when(fx.event.isEventCleared()).thenReturn(false);

        fx.tick(session, 1_000L + 1_200_000L);

        assertEquals(AgentLpqSession.Phase.STAGE_9, session.phase(), "no failure");
        fx.saidNothing();
        verify(fx.event, never()).dispose();

        when(fx.event.isEventCleared()).thenReturn(true);
        fx.tick(session, 1_000L + 1_201_000L);

        assertEquals(AgentLpqSession.Phase.BONUS, session.phase());
        assertEquals(1_000L + 1_201_000L, session.clearedAtMs());
        verify(fx.event, never()).dispose();
    }

    /** Amended B2: the after-clear limit says the wrap-up line before the wrap-up, and the run completes. */
    @Test
    void theAfterClearLimitSaysTheWrapUpFirst() {
        AgentLpqSession session = run(true, AgentLpqSession.Phase.CLAIMING_REWARD, AgentLpqDefinition.BONUS_MAP);
        session.freezeRewardEligibility();
        session.markCleared(100_000L);
        // Mira, Kes, Pel and Hal are done; Cas has not claimed (the human is never waited for).
        for (Character member : crew.subList(1, 5)) session.forfeitReward(member.getId());
        List<String> wrapUpsAtTheLine = new ArrayList<>();
        try (LpqLogLines log = new LpqLogLines(AgentLpqTerminationService.class)) {
            fx.dialogue.when(() -> AgentLpqDialogue.sayMapNow(anyString(), eq("failed"), any(), anyString()))
                    .thenAnswer(call -> {
                        wrapUpsAtTheLine.addAll(log.startingWith("LPQ after-clear wrap-up: "));
                        return null;
                    });

            fx.tick(session, 100_000L + 300_001L);

            assertEquals(1, log.startingWith("LPQ after-clear wrap-up: ").size());
        }
        fx.said(session, "failed", crew.get(1), "we've been at this a while. let's wrap it up and head back.");
        assertTrue(wrapUpsAtTheLine.isEmpty(), "the line came before the wrap-up");
        assertEquals(AgentLpqSession.Phase.COMPLETED, session.phase());
        verify(fx.event, never()).dispose();
    }
}
