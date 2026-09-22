package server.agents.diagnostics.arena;

import client.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaOptionsTest {
    @Test
    void defaultsRunEveryScenarioWithTheProfileBuild() {
        ArenaOptions options = ArenaOptions.parse(new String[]{"ProbeHero"});
        assertEquals("ProbeHero", options.agentName());
        assertEquals(ArenaOptions.ALL_SCENARIOS, options.scenarios());
        assertTrue(options.profileBuild());
        assertFalse(options.wantsShaping());
        assertTrue(options.confirmSeconds() >= options.roundOneSeconds());
    }

    @Test
    void parsesJobScenarioAndBuild() {
        ArenaOptions options = ArenaOptions.parse(new String[]{"a", "job=hero", "scenario=boss,single", "build=max", "seconds=8", "long=20", "pack=4"});
        assertEquals(Job.HERO, options.job());
        assertEquals(120, options.level());
        assertEquals(List.of("boss", "single"), options.scenarios());
        assertFalse(options.profileBuild());
        assertEquals(8, options.roundOneSeconds());
        assertEquals(20, options.confirmSeconds());
        assertEquals(4, options.packSize());
        assertTrue(options.wantsShaping());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> ArenaOptions.parse(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> ArenaOptions.parse(new String[]{"a", "scenario=raid"}));
        assertThrows(IllegalArgumentException.class, () -> ArenaOptions.parse(new String[]{"a", "seconds=30", "long=10"}));
        assertThrows(IllegalArgumentException.class, () -> ArenaOptions.parse(new String[]{"a", "pack=1"}));
        assertThrows(IllegalArgumentException.class, () -> ArenaOptions.parse(new String[]{"a", "bogus"}));
    }
}
