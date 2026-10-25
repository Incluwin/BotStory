package server.agents.capabilities.expedition.gpq;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sharenian end to end on a simulated Guild Quest (GpqWorld): Ben, a human leader who talks to the Gatekeeper, the
 * fountain and the ghost when his companions tell him and walks on when the way opens, and seven companions on every
 * stage play clear the castle, break the warehouse's boxes and walk out by Nuris. Each seed draws other statue
 * patterns and vassal offerings.
 */
class AgentGpqClearSimulationTest {
    private static final long QUIET_MS = 20 * 60_000L;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1L, 2L, 3L, 4L, 5L, 6L})
    void companionsAndAHumanLeaderClearSharenian(long seed) {
        GpqWorld world = new GpqWorld(seed);
        int steps = 0;
        String seen = "";
        long quietSinceMs = world.now;
        while (world.step()) {
            steps++;
            String progress = world.progress();
            if (!progress.equals(seen)) {
                seen = progress;
                quietSinceMs = world.now;
            } else if (world.now - quietSinceMs > QUIET_MS) {
                break; // stalled: no flag, gate or member's map moved on for 20 simulated minutes
            }
        }
        String transcript = String.join("\n", world.lines);
        assertTrue(world.cleared, "seed " + seed + " not cleared after " + steps + " steps (" + world.at() + "); Ben's"
                + " maps " + world.benAt + "\n" + transcript + world.where());
        if (Boolean.getBoolean("gpq.sim.print")) {
            System.out.println("seed " + seed + " cleared at " + (world.clearedAtMs - world.startedAtMs) / 1000
                    + " s; Ben's maps " + world.benAt + "; gatekeeper misses " + world.gatekeeperMisses
                    + "; fountain judged " + world.fountainJudged + " draws " + world.fountainDraws + "\n"
                    + transcript);
        }
        assertTrue(world.rocklessAt300.isEmpty(), "walked into the bad air without a rock: " + world.rocklessAt300);
        int deaths = GpqWorld.COMPANIONS.stream().mapToInt(id -> world.body(id).deaths).sum();
        assertEquals(1, deaths, "only the King's Corridor's rock giver dies\n" + world.log);
        assertTrue(world.fountainJudged <= 7 * world.fountainDraws, "the fountain judged " + world.fountainJudged);
        for (int id : GpqWorld.COMPANIONS) {
            GpqWorld.Body body = world.body(id);
            assertEquals(GpqWorld.CAMP, body.mapId, body.name + " did not walk out: " + world.where());
        }
    }
}
