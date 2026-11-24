package server.agents.diagnostics;

import client.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every job has weapons of its own family to fall back on in the arena. */
class ArenaWeaponLadderTest {
    @Test
    void theBestFirstThenItsFamilysLesserOnes() {
        for (Job job : List.of(Job.WARRIOR, Job.DRAGONKNIGHT, Job.PRIEST, Job.RANGER, Job.SNIPER, Job.HERMIT,
                Job.CHIEFBANDIT, Job.MARAUDER, Job.OUTLAW, Job.ARAN3, Job.BEGINNER)) {
            List<Integer> weapons = AgentHarnessProvisioning.arenaWeaponsFor(job);
            assertEquals(AgentHarnessProvisioning.arenaWeaponFor(job), weapons.getFirst());
            assertEquals(4, weapons.size(), job.name());
            assertTrue(weapons.stream().allMatch(id -> id / 10_000 == weapons.getFirst() / 10_000), job.name());
        }
    }
}
