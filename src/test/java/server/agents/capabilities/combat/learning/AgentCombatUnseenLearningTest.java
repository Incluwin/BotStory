package server.agents.capabilities.combat.learning;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.runtime.AgentRuntimeEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Decision 13 a (docs/BOT_MANAGEMENT_PLAN.md, "Bench combat learning"): an Agent the host's AgentCombatLearnGuard rule
 * says nobody can see learns (LEARN) into its job's candidate table, back to live play the moment a human can see it
 * and learning again once they leave; the arena's own modes are never touched; and live play never reads a candidate.
 */
class AgentCombatUnseenLearningTest {
    /** A job with no live policy file under scripts/config/agents/combat-policy. */
    private static final Job JOB = Job.BEGINNER;

    private final Character bot = mock(Character.class);
    private final AgentCombatLearningState state = new AgentCombatLearningState();
    /** What the host's rule answers: true while no human is on the Agent's map. */
    private boolean unseen;

    AgentCombatUnseenLearningTest() {
        when(bot.getId()).thenReturn(4242);
        when(bot.getJob()).thenReturn(JOB);
    }

    @AfterEach
    void uninstall() {
        AgentCombatLearnGuard.install(null);
    }

    private void installRule(double epsilon) {
        AgentCombatLearnGuard.install(new AgentCombatLearnGuard.Rule() {
            @Override
            public boolean learnUnseen(Character agent) {
                return unseen;
            }

            @Override
            public double epsilon() {
                return epsilon;
            }
        });
    }

    @Test
    void itLearnsOnlyWhileNobodyCanSeeItAndSwitchesAtOnceWhenAHumanEntersAndLeaves() {
        installRule(0.2);
        AgentCombatPolicyTable candidate = AgentCombatCandidates.defaultCandidates().table(JOB.getId());

        unseen = true;
        assertEquals(AgentCombatLearner.Mode.LEARN, AgentCombatLearner.resolve(state, bot));
        assertSame(candidate, state.table(), "it learns into its job's candidate table");
        assertEquals(0.2, state.epsilon(), 1e-9);
        state.open(new AgentCombatLearningState.Open("1 target", 0, 1_000L, 50));

        unseen = false; // a human steps onto its map
        assertEquals(AgentCombatLearner.Mode.LIVE, AgentCombatLearner.resolve(state, bot));
        assertNull(state.table(), "live play: no table of its own");
        assertNull(state.open(), "the exploring decision is dropped, not learned from");

        unseen = true; // they leave
        assertEquals(AgentCombatLearner.Mode.LEARN, AgentCombatLearner.resolve(state, bot));
        assertSame(candidate, state.table(), "the same table, with what it learned before");
    }

    @Test
    void withNothingInstalledEveryAgentPlaysLive() {
        unseen = true;
        assertEquals(AgentCombatLearner.Mode.LIVE, AgentCombatLearner.resolve(state, bot));
        assertNull(state.table());
    }

    @Test
    void theArenasOwnModeStandsUntilItEnds() {
        installRule(0.1);
        unseen = true;
        AgentCombatPolicyTable arena = new AgentCombatPolicyTable();
        state.begin(AgentCombatLearner.Mode.FROZEN, arena, 0);

        assertEquals(AgentCombatLearner.Mode.FROZEN, AgentCombatLearner.resolve(state, bot));
        assertSame(arena, state.table());

        state.end();
        assertEquals(AgentCombatLearner.Mode.LEARN, AgentCombatLearner.resolve(state, bot));
    }

    @Test
    void livePlayNeverReadsACandidateTable() {
        installRule(0.1);
        // a candidate that knows exactly what to do in this situation
        AgentCombatPolicyTable candidate = AgentCombatCandidates.defaultCandidates().table(JOB.getId());
        for (int visit = 0; visit < 20; visit++) {
            candidate.update("1 target", 0, 1_000_000, 0.05, 0, 1_000_000, 1);
        }
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentAttackPlan basic = new AgentAttackPlan(0, 0, 1, null, List.of(), null, 0, 0, 0, 0, 0, 0, 600, null);

        unseen = false; // seen: live play, and this job has no adopted policy
        assertNull(AgentCombatLearner.choose(entry, bot, null, List.of(basic)),
                "the scorer decides: the candidate is not consulted");
        assertEquals(AgentCombatLearner.Mode.LIVE, entry.capabilityStates().require(AgentCombatLearningState.STATE_KEY).mode());
    }

    @Test
    void candidatesAreWrittenWhereTheLivePolicyNeverLooksAndNeverAsAdopted(@TempDir Path root) throws Exception {
        Path live = root.resolve("combat-policy");
        AgentCombatCandidates candidates = new AgentCombatCandidates(live.resolve("candidates"));
        AgentCombatPolicyTable table = candidates.table(JOB.getId());
        table.update("1 target", 0, 500, 0.05, 0, 500, 1);
        table.update("4+ targets", 0, 900, 0.05, 0, 900, 1);
        table.update("1 target|boss", 0, 300, 0.05, 0, 300, 1);

        assertEquals(1, candidates.flush());
        assertEquals(0, candidates.flush(), "unchanged since: not written again");

        assertTrue(Files.isRegularFile(live.resolve("candidates").resolve(JOB.getId() + ".json")));
        AgentCombatPolicyRepository livePolicy = new AgentCombatPolicyRepository(live);
        assertTrue(livePolicy.find(JOB.getId()).isEmpty(), "the live policy reads combat-policy/<jobId>.json only");
        AgentCombatPolicy written = new AgentCombatPolicyRepository(live.resolve("candidates")).find(JOB.getId())
                .orElseThrow().policy();
        assertEquals(List.of("boss", "multi", "single"), List.copyOf(written.scenarios().keySet()));
        assertTrue(written.scenarios().values().stream().noneMatch(AgentCombatPolicy.Scenario::adopted));
        assertFalse(written.priorsRelaxed());

        AgentCombatCandidates afterRestart = new AgentCombatCandidates(live.resolve("candidates"));
        assertEquals(1, afterRestart.loadAll());
        assertEquals(1, afterRestart.table(JOB.getId()).visits("4+ targets", 0), "one table again, all situations");
        assertEquals(List.of("1 target"), List.copyOf(afterRestart.copy(JOB.getId(), "single").orElseThrow()
                .snapshot().keySet()), "the arena checks one scenario's situations");
        assertTrue(afterRestart.copy(Job.WARRIOR.getId(), "single").isEmpty(), "nothing learned: nothing to check");
    }
}
