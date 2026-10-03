package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeConfig;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one readiness standard (AgentSupplyReadiness): 20 HP potions and, for every job past a beginner's, 25 mana
 * potions before a party quest, read from agent-engine.yaml; the shortfall both the entry desk and the Agent read;
 * the shop's buy target under a standing duty (twice the standard); and a misconfigured mana standard at or under
 * POT_STOP is refused by validate and clamped, never thrown, at runtime.
 */
class AgentSupplyReadinessTest {
    private static final String MP_KEY = "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_MP_POTIONS";
    private static final AgentSupplyReadiness.Activity PQ = AgentSupplyReadiness.Activity.PARTY_QUEST;

    @AfterEach
    void noRule() {
        AgentSupplyReadiness.install(null);
    }

    @Test
    void thePartyQuestStandardIsTwentyHpAndTwentyFiveMpPotionsFromTheYaml() {
        assertEquals(new AgentSupplyReadiness.Standard(20, 25), AgentSupplyReadiness.standard(PQ));
    }

    @Test
    void theShortfallCountsWhatIsMissingAndOnlySkillUsersNeedMana() {
        assertEquals(new AgentSupplyReadiness.Shortfall(1, 0), AgentSupplyReadiness.shortfall(19, 30, true, PQ));
        assertEquals(new AgentSupplyReadiness.Shortfall(0, 1), AgentSupplyReadiness.shortfall(20, 24, true, PQ));
        assertTrue(AgentSupplyReadiness.shortfall(20, 25, true, PQ).none());
        assertTrue(AgentSupplyReadiness.shortfall(20, 0, false, PQ).none(), "no skills, no mana potions");

        assertFalse(AgentSupplyReadiness.needsManaPotions(withJob(Job.BEGINNER)));
        assertFalse(AgentSupplyReadiness.needsManaPotions(withJob(Job.NOBLESSE)));
        assertFalse(AgentSupplyReadiness.needsManaPotions(withJob(Job.LEGEND)), "Aran's beginner");
        assertTrue(AgentSupplyReadiness.needsManaPotions(withJob(Job.HUNTER)));
        assertTrue(AgentSupplyReadiness.needsManaPotions(withJob(Job.FIGHTER)));
    }

    @Test
    void theBuyTargetIsTwiceTheStandardUnderAStandingDutyAndZeroWithout() {
        Character hunter = withJob(Job.HUNTER);
        Character beginner = withJob(Job.BEGINNER);
        when(hunter.getId()).thenReturn(7);
        when(beginner.getId()).thenReturn(8);

        assertEquals(0, AgentSupplyReadiness.buyTarget(hunter, true), "no rule installed");
        assertEquals(0, AgentSupplyReadiness.buyTarget(hunter, false));

        AgentSupplyReadiness.install(id -> PQ);
        assertEquals(40, AgentSupplyReadiness.buyTarget(hunter, true));
        assertEquals(50, AgentSupplyReadiness.buyTarget(hunter, false));
        assertEquals(40, AgentSupplyReadiness.buyTarget(beginner, true));
        assertEquals(0, AgentSupplyReadiness.buyTarget(beginner, false), "a beginner buys no mana");

        AgentSupplyReadiness.install(id -> id == 7 ? PQ : null);
        assertEquals(0, AgentSupplyReadiness.buyTarget(beginner, true), "no duty for this one");
    }

    @Test
    void aManaStandardAtOrUnderPotStopIsRefusedAndClampedAtRuntime() {
        int potStop = AgentRuntimeConfig.cfg.POT_STOP;
        assertThrows(IllegalArgumentException.class, () -> AgentSupplyReadiness.validate(20, potStop, potStop));
        assertThrows(IllegalArgumentException.class, () -> AgentSupplyReadiness.validate(20, potStop - 1, potStop));
        assertDoesNotThrow(() -> AgentSupplyReadiness.validate(20, potStop + 1, potStop));

        String configured = AgentYamlConfig.config.tuning.put(MP_KEY, Integer.toString(potStop));
        try {
            AgentSupplyReadiness.Standard clamped = assertDoesNotThrow(() -> AgentSupplyReadiness.standard(PQ));
            assertEquals(new AgentSupplyReadiness.Standard(20, potStop + 1), clamped);
        } finally {
            AgentYamlConfig.config.tuning.put(MP_KEY, configured);
        }
    }

    private static Character withJob(Job job) {
        Character chr = mock(Character.class);
        when(chr.getJob()).thenReturn(job);
        return chr;
    }
}
