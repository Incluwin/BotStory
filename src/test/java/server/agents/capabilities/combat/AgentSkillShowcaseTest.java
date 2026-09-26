package server.agents.capabilities.combat;

import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillShowcaseTest {
    private static final int SLASH_BLAST = 1001005;
    private static final int BRANDISH = 1111008;
    private static final int THREE_SNAILS = 1000;

    @Test
    void pickIsOffUntilTheShowcaseStarts() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertNull(AgentSkillShowcase.pick(entry, List.of(plan(SLASH_BLAST), plan(BRANDISH))));
    }

    @Test
    void pickRotatesToTheSkillShownLeastRecently() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        AgentSkillShowcase.start(entry);
        AgentSkillShowcaseState state = entry.capabilityStates().require(AgentSkillShowcaseState.STATE_KEY);
        List<AgentAttackPlan> eligible = List.of(plan(0), plan(THREE_SNAILS), plan(SLASH_BLAST), plan(BRANDISH));

        state.recordUse(SLASH_BLAST, 1_000L);
        assertEquals(BRANDISH, AgentSkillShowcase.pick(entry, eligible).skillId, "never-shown skill first");

        state.recordUse(BRANDISH, 2_000L);
        assertEquals(SLASH_BLAST, AgentSkillShowcase.pick(entry, eligible).skillId, "then the oldest");
    }

    @Test
    void aSkillIsNotNamedTwiceInARow() {
        AgentSkillShowcaseState state = new AgentSkillShowcaseState();
        state.start();

        assertTrue(state.recordUse(SLASH_BLAST, 1L));
        assertFalse(state.recordUse(SLASH_BLAST, 2L));
        assertTrue(state.recordUse(BRANDISH, 3L));
        assertTrue(state.recordUse(SLASH_BLAST, 4L));
    }

    private static AgentAttackPlan plan(int skillId) {
        return new AgentAttackPlan(skillId, 0, 1, null, List.of(), AgentAttackRoute.CLOSE,
                0, 0, 0, 0, 0, 0, 0, WeaponType.SWORD1H);
    }
}
