package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback.RunEnded;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.living.store.LivingStore.PqRunPaceRow;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * A companion run's clear-time record (P3): the Yes keeps the estimate the leader was shown on the run; as the run's
 * session ends, the pace it reported goes on pq_run_pace once, beside the companions' own records; a run that never
 * got in, or whose session reported no pace, writes none. What the records hold then shapes the next lineup: the
 * estimate rests on them, and the leader's last clear with exactly these companions is there for Lakelis to say.
 */
class CompanionRunPaceTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private final AgentPqPaceProfile profile = new PqPaceTestRuns.Profile(1);
    private final PqPaceLedger ledger = new PqPaceLedger(game.world, key -> "kpq".equals(key) ? profile : null);

    @BeforeEach
    void annAndThreeCompanionsAtLakelis() {
        fx.companion("mira", "Mira", 900, 24, Job.HUNTER);
        fx.companion("tobi", "Tobi", 901, 27, Job.PAGE);
        fx.companion("pip", "Pip", 902, 26, Job.FP_WIZARD);
        CompanionPartyQuestService.PaceSource live = CompanionPartyQuestService.PaceSource.live(ledger);
        fx.pace = new CompanionPartyQuestService.PaceSource() {
            @Override
            public AgentPqPaceProfile profile(String pqKey) {
                return profile;
            }

            @Override
            public AgentPqPaceCalibration calibration(AgentPqPaceProfile warm, int leaderId) {
                return live.calibration(warm, leaderId);
            }

            @Override
            public AgentPqMemberPower power(Character chr, AgentPqPaceProfile warm, boolean human, boolean leader) {
                return PqPaceTestRuns.power(chr.getId(), human, leader, human ? 0.0d : 500.0d);
            }

            @Override
            public double lastClearS(String pqKey, int leaderId, Set<Integer> companionIds) {
                return live.lastClearS(pqKey, leaderId, companionIds);
            }
        };
        fx.ledger = ledger;
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void theYesKeepsTheEstimateTheLeaderWasShown() {
        LineupPlan shown = fx.ready().plan(game.ann, "kpq");

        CompanionRun run = fx.yes();

        assertNotNull(shown.estimate());
        assertEquals(shown.estimate(), run.estimateAtYes);
    }

    @Test
    void aLineupShownNoEstimateKeepsNone() {
        fx.pace = CompanionPartyQuestService.PaceSource.COLD;
        fx.ready();

        assertNull(fx.yes().estimateAtYes);
    }

    @Test
    void theSessionsPaceGoesOnTheRecordsOnceAsTheRunEnds() {
        fx.ready();
        CompanionRun run = fx.yes();
        fx.service.cleared(List.of(game.ann));
        AgentPqPace reported = PqPaceTestRuns.pace(run.sessionId, ANN, List.of(900, 901, 902), true);

        fx.sessionEnds(true, reported);
        fx.lifecycle.runEnded(new RunEnded("kpq", run.sessionId, ANN, true, "completed", 6_000L, reported));

        assertEquals(1, game.store.paces.size());
        PqRunPaceRow row = game.store.paces.getFirst();
        assertEquals(run.sessionId, row.sessionId());
        assertEquals("cleared", row.outcome());
        assertEquals(226, row.actualS());
        assertEquals((int) Math.round(run.estimateAtYes.p50S()), row.estP50S());
        assertEquals(reported, PqPaceJson.pace(row));
        assertEquals(3, game.store.performance.size(), "the companions' own records too");
    }

    @Test
    void aRunWithNoPaceReportedOrThatNeverGotInWritesNoRecord() {
        fx.ready();
        fx.yes();
        fx.sessionEnds(true); // a session that reports no pace

        fx.doors.start = PqEntryPort.Start.NO_LOBBY;
        fx.yes(); // admitted, but the event did not start: stopped again, and its run ended

        assertEquals(List.of(), game.store.paces);
    }

    @Test
    void theRecordsTeachTheNextLineupAndKeepItsLastClear() {
        CompanionPartyQuestService service = fx.ready();
        LineupPlan before = service.plan(game.ann, "kpq");
        assertEquals(0, before.estimate().recordsUsed());
        assertEquals(0.0d, before.clearTime().lastClearS());

        CompanionRun run = fx.yes();
        fx.service.cleared(List.of(game.ann));
        fx.sessionEnds(true, PqPaceTestRuns.pace(run.sessionId, ANN, List.of(900, 901, 902), true));
        LineupPlan after = service.plan(game.ann, "kpq");

        assertEquals(1, after.estimate().recordsUsed());
        assertEquals(226.0d, after.clearTime().lastClearS());
        assertTrue(LakelisLines.clearTime(after).getFirst()
                .startsWith("Last time you went in with them it took about 4 minutes."), LakelisLines.clearTime(after)
                .toString());
    }
}
