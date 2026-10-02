package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import scripting.npc.NPCConversationManager;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.kpq.AgentKpqRerollPolicy;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.living.config.Content.Persona;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * The KPQ re-roll as the living world hosts it (round 2b): inside the run the companion that asked shows its leader a
 * window of its own on his I/O thread, and his pick is only recorded where the run reads it; at the entrance, Lakelis
 * greets a leader back soon after a re-roll with one line above the lineup, and only him, only then, and only until
 * one of his runs ends any other way. The lineup's run carries the calibration it was priced with, so the run weighs
 * a re-roll at the same rate.
 */
class RerollAtLakelisTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private Persona mira;

    /** One question shown: who to, from whom, and what it said, with the pick it hands back. */
    private record Shown(Character leader, Persona asker, String question, Consumer<Boolean> answer) {
    }

    @BeforeEach
    void annAndThreeCompanionsAtLakelis() {
        mira = fx.companion("mira", "Mira", 900, 24, Job.HUNTER);
        fx.companion("tobi", "Tobi", 901, 27, Job.PAGE);
        fx.companion("pip", "Pip", 902, 26, Job.FP_WIZARD);
    }

    @AfterEach
    void close() {
        fx.close();
    }

    // ---- Lakelis ----------------------------------------------------------------------------------------

    @Test
    void aLeaderBackSoonAfterARerollHearsHerAboveTheLineup() {
        readyAtTheDesk();
        fx.yes();
        fx.sessionRerolled();

        assertTrue(fx.service.offerAtDesk(game.ann, "kpq"));

        assertEquals(List.of("proposal"), game.calls.stream().filter("proposal"::equals).toList());
        assertEquals("Back already? Cloto must have been in a mood.", fx.topLine);
    }

    @Test
    void withoutARerollSheSaysNothingAboveIt() {
        readyAtTheDesk();

        assertTrue(fx.service.offerAtDesk(game.ann, "kpq"));

        assertNull(fx.topLine);
    }

    @Test
    void aClearOrAFailureOfHisSinceForgetsTheReroll() {
        readyAtTheDesk();
        fx.yes();
        fx.sessionRerolled();
        assertTrue(fx.service.backAfterReroll(ANN, System.currentTimeMillis()));

        fx.yes();
        fx.service.cleared(List.of(game.ann));
        fx.sessionEnds(true);
        assertFalse(fx.service.backAfterReroll(ANN, System.currentTimeMillis()));

        fx.yes();
        fx.sessionRerolled();
        fx.yes();
        fx.sessionEnds(false);
        assertFalse(fx.service.backAfterReroll(ANN, System.currentTimeMillis()));
        assertTrue(fx.service.offerAtDesk(game.ann, "kpq"));
        assertNull(fx.topLine);
    }

    @Test
    void theGreetingLastsTheStreakResetAndIsHisAlone() {
        fx.ready();
        long before = System.currentTimeMillis();
        fx.yes();
        fx.sessionRerolled();
        long after = System.currentTimeMillis();
        long resetMs = AgentKpqRerollPolicy.configured().streakResetMs();

        assertTrue(fx.service.backAfterReroll(ANN, after));
        assertTrue(fx.service.backAfterReroll(ANN, before + resetMs - 1L));
        assertFalse(fx.service.backAfterReroll(ANN, after + resetMs));
        assertFalse(fx.service.backAfterReroll(ANN + 1, after), "another leader never left a run here");
    }

    @Test
    void aRunThatNeverGotInLeavesNoGreeting() {
        fx.ready();
        fx.doors.start = PqEntryPort.Start.NO_LOBBY;
        fx.yes(); // admitted, but the event did not start: its run ends unstarted

        assertFalse(fx.service.backAfterReroll(ANN, System.currentTimeMillis()));
    }

    @Test
    void theRunCarriesTheCalibrationItsLineupWasPricedWith() {
        AgentPqPaceProfile profile = new PqPaceTestRuns.Profile(1);
        AgentPqPaceCalibration learned = AgentPqPaceCalibration.prior(profile);
        fx.pace = new CompanionPartyQuestService.PaceSource() {
            @Override
            public AgentPqPaceProfile profile(String pqKey) {
                return profile;
            }

            @Override
            public AgentPqPaceCalibration calibration(AgentPqPaceProfile p, int leaderId) {
                return leaderId == ANN ? learned : null;
            }

            @Override
            public AgentPqMemberPower power(Character chr, AgentPqPaceProfile p, boolean human, boolean leader) {
                return PqPaceTestRuns.power(chr.getId(), human, leader, 300.0d);
            }

            @Override
            public double lastClearS(String pqKey, int leaderId, Set<Integer> companionIds) {
                return 0.0d;
            }
        };
        fx.ready();

        fx.yes();

        assertSame(learned, fx.doors.session.paceCalibration());
    }

    @Test
    void aColdLineupLeavesTheRunToThePriors() {
        fx.ready();

        fx.yes();

        assertNull(fx.doors.session.paceCalibration());
    }

    // ---- the companion's window -------------------------------------------------------------------------

    @Test
    void theCompanionAsksInAWindowOfItsOwnOnTheLeadersThreadAndThePickIsOnlyRecorded() {
        fx.ready();
        List<Shown> shown = new ArrayList<>();
        fx.lifecycle.installRerollWindow((leader, asker, question, answer) ->
                shown.add(new Shown(leader, asker, question, answer)));
        CompanionRun run = fx.yes();
        fx.io = new ArrayList<>();

        boolean asked = fx.lifecycle.askLeaderToReroll(new AgentPartyQuestHandback.RerollAsk("kpq", run.sessionId,
                ANN, 900, "thirty-fives", "ugh, two of us got 35s. that's a long grind, wanna bail and run it again?"));

        assertTrue(asked);
        assertEquals(List.of(), shown, "not on the run's tick: on Ann's I/O thread");
        assertEquals(1, fx.io.size());
        try (MockedStatic<AgentKpqSessionRegistry> registry = mockStatic(AgentKpqSessionRegistry.class,
                Mockito.CALLS_REAL_METHODS)) {
            fx.io.removeFirst().run();
            assertEquals(1, shown.size());
            assertSame(game.ann, shown.getFirst().leader());
            assertSame(mira, shown.getFirst().asker());
            assertEquals("ugh, two of us got 35s. that's a long grind, wanna bail and run it again?",
                    shown.getFirst().question());
            registry.verify(() -> AgentKpqSessionRegistry.answerReroll(run.sessionId, ANN, true), never());

            shown.getFirst().answer().accept(true);

            registry.verify(() -> AgentKpqSessionRegistry.answerReroll(run.sessionId, ANN, true));
        }
    }

    @Test
    void withoutAWindowARunOrItsLeaderOnlineTheQuestionStandsInChatAlone() {
        fx.ready();
        CompanionRun run = fx.yes();
        AgentPartyQuestHandback.RerollAsk ask = new AgentPartyQuestHandback.RerollAsk("kpq", run.sessionId, ANN, 900,
                "thirty-fives", "q");

        assertFalse(fx.lifecycle.askLeaderToReroll(ask), "no window installed");

        fx.lifecycle.installRerollWindow((leader, asker, question, answer) -> { });
        assertFalse(fx.lifecycle.askLeaderToReroll(new AgentPartyQuestHandback.RerollAsk("kpq", "kpq-gone", ANN, 900,
                "thirty-fives", "q")), "no such run");
        assertTrue(fx.lifecycle.askLeaderToReroll(ask));
    }

    /** Ann clicks Lakelis on her own thread, inside the stock script, with the companion PQ on. */
    private void readyAtTheDesk() {
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq";
        for (int id : List.of(900, 901, 902)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
        }
        Client client = game.ann.getClient();
        when(client.onIoThread()).thenReturn(true);
        when(client.getCM()).thenReturn(mock(NPCConversationManager.class));
        game.calls.clear();
    }
}
