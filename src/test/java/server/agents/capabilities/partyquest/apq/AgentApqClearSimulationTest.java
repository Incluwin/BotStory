package server.agents.capabilities.partyquest.apq;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Amorian Challenge end to end on a simulated APQ (ApqWorld): Ben, a human leader who does only what his
 * companions tell him and what he sees, and five companions on the real engine clear every stage and the bonus vault,
 * and the run completes with its companions out of the event. Each seed draws other combinations, gates and
 * stage-1 exits.
 */
class AgentApqClearSimulationTest {
    /** A cap of two simulated hours: a clear takes well under the event's 75 minutes. */
    private static final int MAX_STEPS = 28_800;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L})
    void companionsAndAHumanLeaderClearTheAmorianChallenge(long seed) {
        try (ApqWorld world = new ApqWorld(seed)) {
            int steps = world.runUntilDone(MAX_STEPS);
            String transcript = String.join("\n", world.lines);
            assertEquals(AgentApqSession.Phase.COMPLETED, world.session.phase(),
                    "seed " + seed + " ended " + world.session.phase() + " (" + world.session.failure() + ") after "
                            + steps + " steps; stage " + world.session.leaderStage() + "\n" + transcript + "\n"
                            + world.describe() + "\nat dispose:\n" + world.atDispose);
            if (Boolean.getBoolean("apq.sim.print")) {
                System.out.println("seed " + seed + " stages " + world.benStageAt + " tries " + world.judgedTries
                        + " waves " + world.wavesSpawned + "\n" + transcript);
            }
            assertTrue(world.eventCleared, "Amos took the Geist Fang");
            assertTrue(world.reachedBonus, "the leader opened Amos' Vault");
            assertEquals(1, world.stageFiveTimerRestarts, "the pieces went in with more than five minutes left");
            assertTrue(world.judgedTries.get(2) <= AgentApqDefinition.ROPE_MISSES_PER_DRAW * 2,
                    "rope tries " + world.judgedTries.get(2));
            assertTrue(world.judgedTries.get(3) <= 9, "platform tries " + world.judgedTries.get(3));
            for (int id : ApqWorld.COMPANIONS) {
                assertEquals(AgentApqDefinition.GATE_MAP, world.body(id).mapId, world.body(id).name + " left");
            }
        }
    }
}
