package server.agents.capabilities.combat;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P16: a mob's dispel takes an Agent's buffs while their timers still say they run. Its next buff tick makes every
 * buff skill due now, once, so it puts them back up at once as a player does; another Agent's timers stay.
 */
class AgentCombatBuffDispelTest {
    private static final int BLESS = 2301004;
    private static final int HOLY_SYMBOL = 2311003;

    @Test
    void aDispelledAgentsBuffsAreDueOnItsNextBuffTickOnce() {
        Character priest = agent(77_001);
        Character other = agent(77_002);
        AgentRuntimeEntry priestEntry = buffed(priest);
        AgentRuntimeEntry otherEntry = buffed(other);

        AgentCombatBuffRuntime.dispelled(priest);
        AgentCombatBuffRuntime.rebuffIfDispelled(otherEntry, other);
        AgentCombatBuffRuntime.rebuffIfDispelled(priestEntry, priest);

        assertEquals(0L, AgentCombatBuffStateRuntime.nextBuffAt(priestEntry, BLESS));
        assertEquals(0L, AgentCombatBuffStateRuntime.nextBuffAt(priestEntry, HOLY_SYMBOL));
        assertEquals(90_000L, AgentCombatBuffStateRuntime.nextBuffAt(otherEntry, BLESS), "not dispelled");

        AgentCombatBuffStateRuntime.setNextBuffAt(priestEntry, BLESS, 95_000L);
        AgentCombatBuffRuntime.rebuffIfDispelled(priestEntry, priest);
        assertEquals(95_000L, AgentCombatBuffStateRuntime.nextBuffAt(priestEntry, BLESS), "once per dispel");
    }

    private static Character agent(int id) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(id);
        return agent;
    }

    private static AgentRuntimeEntry buffed(Character agent) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, null, null);
        AgentCombatSkillCacheStateRuntime.addBuffSkillId(entry, BLESS);
        AgentCombatSkillCacheStateRuntime.addBuffSkillId(entry, HOLY_SYMBOL);
        AgentCombatBuffStateRuntime.setNextBuffAt(entry, BLESS, 90_000L);
        AgentCombatBuffStateRuntime.setNextBuffAt(entry, HOLY_SYMBOL, 120_000L);
        return entry;
    }
}
