package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * A companion run's Agent that departs before the clear taps out, as the other quests' companions do: the host is
 * asked before the hand-back, so it holds the companion outside with its party line instead of sending it after its
 * owner, who is still in the tower. A stock run and a departure after the clear are only handed back, as before.
 */
class AgentOpqTapOutTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final MockedStatic<AgentPartyQuestHandback> handback = mockStatic(AgentPartyQuestHandback.class);
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");

    @AfterEach
    void tearDown() {
        handback.close();
        run.close();
    }

    /** From now on the event holds exactly {@code players}. */
    private void holds(Character... players) {
        List<Character> held = List.of(players);
        when(run.event.getPlayers()).thenAnswer(call -> new ArrayList<>(held));
    }

    /** Kes went down and his respawn took him out of the tower. */
    private void kesRespawnsOutside() {
        when(kes.getMapId()).thenReturn(AgentOpqDefinition.EXIT_MAP);
        when(kes.getEventInstance()).thenReturn(null);
        holds(ben, mira, pel, hal);
    }

    @Test
    void aDownedCompanionTapsOutBeforeItIsHandedBack() {
        handback.when(() -> AgentPartyQuestHandback.tapOut(any())).thenReturn(true);
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                AgentOpqSession.Phase.SPLIT_ROOMS, ben, mira, kes, pel, hal);
        holds(ben, mira, kes, pel, hal);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        kesRespawnsOutside();

        try (OpqLogLines lines = new OpqLogLines(AgentOpqCoordinator.class)) {
            AgentOpqCoordinator.tickSession(session, 4_000L);
            AgentOpqCoordinator.tickSession(session, 5_000L);

            handback.verify(() -> AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut("opq",
                    session.sessionId(), kes.getId(), AgentOpqDefinition.EXIT_MAP, 4_000L)), times(1));
            handback.verify(() -> AgentPartyQuestHandback.tapOut(any()), times(1));
            run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(kes.getId(), "opq",
                    4_000L), times(1));
            assertEquals(List.of("OPQ tap-out: session=" + session.sessionId() + " member=3 map="
                    + AgentOpqDefinition.EXIT_MAP + " accepted=true"), lines.startingWith("OPQ tap-out:"));
        }
        assertTrue(session.departed(kes.getId()));
    }

    @Test
    void aRefusedTapOutStillHandsTheCompanionBack() {
        handback.when(() -> AgentPartyQuestHandback.tapOut(any())).thenReturn(false);
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                AgentOpqSession.Phase.SPLIT_ROOMS, ben, mira, kes, pel, hal);
        holds(ben, mira, kes, pel, hal);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        kesRespawnsOutside();

        AgentOpqCoordinator.tickSession(session, 4_000L);

        handback.verify(() -> AgentPartyQuestHandback.tapOut(any()), times(1));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(kes.getId(), "opq", 4_000L),
                times(1));
    }

    @Test
    void aStockRunNeverAsksForATapOut() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, false,
                AgentOpqSession.Phase.SPLIT_ROOMS, ben, mira, kes, pel, hal);
        holds(ben, mira, kes, pel, hal);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        kesRespawnsOutside();

        AgentOpqCoordinator.tickSession(session, 4_000L);

        handback.verify(() -> AgentPartyQuestHandback.tapOut(any()), never());
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(kes.getId(), "opq", 4_000L),
                times(1));
    }

    @Test
    void aDepartureAfterTheClearIsTheWayOutNotATapOut() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true,
                AgentOpqSession.Phase.SPLIT_ROOMS, ben, mira, kes, pel, hal);
        holds(ben, mira, kes, pel, hal);
        AgentOpqCoordinator.tickSession(session, 3_000L);
        session.markClearSeen(3_500L);
        kesRespawnsOutside();

        AgentOpqCoordinator.tickSession(session, 4_000L);

        handback.verify(() -> AgentPartyQuestHandback.tapOut(any()), never());
    }
}
