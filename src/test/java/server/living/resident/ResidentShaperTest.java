package server.living.resident;

import client.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResidentShaperTest {
    @Test
    void advancementChainPassesThroughEveryJob() {
        assertEquals(List.of(), ResidentShaper.advancementChain(Job.BEGINNER));
        assertEquals(List.of(Job.WARRIOR), ResidentShaper.advancementChain(Job.WARRIOR));
        assertEquals(List.of(Job.WARRIOR, Job.FIGHTER), ResidentShaper.advancementChain(Job.FIGHTER));
        assertEquals(List.of(Job.WARRIOR, Job.FIGHTER, Job.CRUSADER), ResidentShaper.advancementChain(Job.CRUSADER));
        // A fourth-job target must not skip the third job, or shaping stalls at second job forever.
        assertEquals(List.of(Job.WARRIOR, Job.FIGHTER, Job.CRUSADER, Job.HERO), ResidentShaper.advancementChain(Job.HERO));
        assertEquals(List.of(Job.MAGICIAN, Job.CLERIC, Job.PRIEST, Job.BISHOP), ResidentShaper.advancementChain(Job.BISHOP));
        assertEquals(List.of(Job.PIRATE, Job.GUNSLINGER, Job.OUTLAW, Job.CORSAIR), ResidentShaper.advancementChain(Job.CORSAIR));
    }
}
