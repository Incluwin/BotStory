package server.agents.capabilities.supplies;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.behavior.AgentImperfections;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F8.2 late potion: a drink waits only while it is safe, 0.3-1.5 s, and goes down at once the moment it is not; with no
 * imperfections rule (or the slip not coming up) every drink goes down at once, as before.
 */
class AgentLateDrinkRuntimeTest {
    private boolean slips = true;

    @AfterEach
    void off() {
        AgentImperfections.install(null);
    }

    private Character agent() {
        AgentImperfections.install(new AgentImperfections.Rule() {
            @Override public boolean calibrated(Character agent) { return true; }
            @Override public boolean slips(Character agent, AgentImperfections.Slip slip) { return slips; }
            @Override public boolean leavesDrop(Character agent, int itemId, int dropObjectId) { return false; }
            @Override public float personalHpAlert(Character agent, float base) { return base; }
        });
        Character agent = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getAllMonsters()).thenReturn(List.of());
        when(agent.getMap()).thenReturn(map);
        when(agent.getId()).thenReturn(311);
        return agent;
    }

    @Test
    void withNoRuleTheDrinkGoesDownAtOnce() {
        Character agent = mock(Character.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        assertFalse(AgentLateDrinkRuntime.holds(entry, agent, 1_000L, bot -> true, () -> 0.5d));
        assertEquals(0L, entry.potionSupplyState().lateHpDrinkAtMs());
    }

    @Test
    void anUnsafeMomentNeverWaits() {
        Character agent = agent();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        assertFalse(AgentLateDrinkRuntime.holds(entry, agent, 1_000L, bot -> false, () -> 0.5d));
        slips = false;
        assertFalse(AgentLateDrinkRuntime.holds(entry, agent, 1_000L, bot -> true, () -> 0.5d), "the slip did not come up");
    }

    @Test
    void aSafeDrinkWaitsWithinItsBandAndGoesDownWhenDueOrWhenItTurnsUnsafe() {
        Character agent = agent();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        assertTrue(AgentLateDrinkRuntime.holds(entry, agent, 1_000L, bot -> true, () -> 1d));
        long due = entry.potionSupplyState().lateHpDrinkAtMs();
        assertEquals(1_000L + AgentImperfections.LATE_DRINK_MAX_MS, due);
        assertTrue(AgentLateDrinkRuntime.holds(entry, agent, 1_400L, bot -> true, () -> 0d), "still waiting");
        assertEquals(due, entry.potionSupplyState().lateHpDrinkAtMs(), "a later hit does not restart the wait");
        assertFalse(AgentLateDrinkRuntime.dueNow(entry, due - 1));
        assertTrue(AgentLateDrinkRuntime.dueNow(entry, due));
        assertFalse(AgentLateDrinkRuntime.holds(entry, agent, due, bot -> true, () -> 0d), "due: drink now");
        assertEquals(0L, entry.potionSupplyState().lateHpDrinkAtMs());

        assertTrue(AgentLateDrinkRuntime.holds(entry, agent, 5_000L, bot -> true, () -> 0d));
        assertEquals(5_000L + AgentImperfections.LATE_DRINK_MIN_MS, entry.potionSupplyState().lateHpDrinkAtMs());
        assertFalse(AgentLateDrinkRuntime.holds(entry, agent, 5_100L, bot -> false, () -> 0d),
                "a hit that makes it unsafe drinks at once");
        assertEquals(0L, entry.potionSupplyState().lateHpDrinkAtMs());
    }
}
