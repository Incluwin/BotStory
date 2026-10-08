package server.agents.capabilities.supplies;

import client.Character;
import client.Job;
import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeConfig;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The one readiness standard (AgentSupplyReadiness): 20 HP potions and, for every job past a beginner's, 25 mana
 * potions before a party quest, read from agent-engine.yaml; the shortfall both the entry desk and the Agent read;
 * the shop's buy target under a standing duty (twice the standard); and a misconfigured mana standard at or under
 * POT_STOP is refused by validate and clamped, never thrown, at runtime.
 */
class AgentSupplyReadinessTest {
    private static final String MP_KEY = "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_MP_POTIONS";
    private static final String STANDARDS_KEY =
            "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_STANDARDS";
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

    @Test
    void eachPartyQuestKeyHasItsOwnStandardAndTheRestKeepTheDefault() {
        assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("kpq"));
        assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("hpq"));
        assertEquals(new AgentSupplyReadiness.Standard(30, 40), standardFor("lmpq"));
        assertEquals(new AgentSupplyReadiness.Standard(30, 40), standardFor("ppq"));
        assertEquals(new AgentSupplyReadiness.Standard(40, 50), standardFor("lpq"));
        assertEquals(new AgentSupplyReadiness.Standard(40, 50), standardFor("opq"));
        assertEquals(new AgentSupplyReadiness.Standard(40, 50), standardFor("rjpq"));
        assertEquals(new AgentSupplyReadiness.Standard(350, 350), standardFor("EPQ "), "keys are trimmed, any case");
        assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("cwkpq"), "an unlisted key: the default");
    }

    @Test
    void theCountOverloadAndTheCompanionsOwnCountAgreeOnTheEpqStandard() {
        // live 2026-10-06: one golem fight used 157-299 HP and 177-420 MP potions per Lv50 companion.
        // Ellin's desk passes partyQuest("epq") (CompanionPartyQuestService.waitingReason), as the duty does.
        AgentSupplyReadiness.Activity epq = AgentSupplyReadiness.Activity.partyQuest("epq");
        Character mage = withJob(Job.FP_WIZARD);
        when(mage.getInventory(client.inventory.InventoryType.USE)).thenReturn(mock(client.inventory.Inventory.class));
        int[] carried = {349, 350};
        try (MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class)) {
            potions.when(() -> AgentPotionService.countPotions(mage)).thenAnswer(inv -> carried.clone());

            assertEquals(new AgentSupplyReadiness.Shortfall(1, 0), AgentSupplyReadiness.shortfall(349, 350, true, epq),
                    "the count overload, which a desk calls with its own key");
            assertEquals(new AgentSupplyReadiness.Shortfall(1, 0), AgentSupplyReadiness.shortfall(mage, epq),
                    "what the companion's own restock reads");

            carried[0] = 350;
            assertTrue(AgentSupplyReadiness.shortfall(350, 350, true, epq).none());
            assertTrue(AgentSupplyReadiness.shortfall(mage, epq).none());
        }
    }

    @Test
    void aBlankKeyIsTheDefaultActivityAndKeysCompareByValue() {
        assertEquals(PQ, AgentSupplyReadiness.Activity.partyQuest(" "));
        assertEquals(PQ, AgentSupplyReadiness.Activity.partyQuest(null));
        assertEquals(AgentSupplyReadiness.Activity.partyQuest("lpq"), AgentSupplyReadiness.Activity.partyQuest("LPQ"));
        assertEquals("lpq", AgentSupplyReadiness.Activity.partyQuest(" Lpq").partyQuestKey());
    }

    @Test
    void theShortfallAndTheBuyTargetFollowThePartyQuestsStandard() {
        Character mage = withJob(Job.FP_WIZARD);
        AgentSupplyReadiness.Activity lpq = AgentSupplyReadiness.Activity.partyQuest("lpq");
        assertEquals(new AgentSupplyReadiness.Shortfall(20, 25), AgentSupplyReadiness.shortfall(20, 25, true, lpq));

        when(mage.getId()).thenReturn(41);
        AgentSupplyReadiness.install(id -> id == 41 ? lpq : null);
        assertEquals(80, AgentSupplyReadiness.buyTarget(mage, true));
        assertEquals(100, AgentSupplyReadiness.buyTarget(mage, false));
    }

    @Test
    void aMalformedOverrideIsSkippedAndAManaOverrideAtPotStopIsClamped() {
        int potStop = AgentRuntimeConfig.cfg.POT_STOP;
        String configured = AgentYamlConfig.config.tuning.put(STANDARDS_KEY,
                "lpq=40/" + potStop + ", junk, opq=x/9, epq=35/45");
        try {
            assertEquals(new AgentSupplyReadiness.Standard(40, potStop + 1), standardFor("lpq"));
            assertEquals(new AgentSupplyReadiness.Standard(20, 25), standardFor("opq"));
            assertEquals(new AgentSupplyReadiness.Standard(35, 45), standardFor("epq"));
        } finally {
            AgentYamlConfig.config.tuning.put(STANDARDS_KEY, configured);
        }
    }

    @Test
    void atTheCounterAReadinessTripBuysForTheActivityItSetOffForEvenIfTheDutyCannotBeReadThere() {
        Character warrior = withJob(Job.WARRIOR);
        when(warrior.getId()).thenReturn(436);
        server.agents.runtime.AgentRuntimeEntry entry = new server.agents.runtime.AgentRuntimeEntry(warrior, null, null);
        AgentSupplyReadiness.Activity ppq = AgentSupplyReadiness.Activity.partyQuest("ppq");
        AgentSupplyReadiness.install(id -> null); // travel cleared the follow target its duty is read from

        assertEquals(null, AgentSupplyReadiness.shoppingActivity(entry, warrior), "no duty, no trip: nothing");

        entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY).offer(
                new server.agents.capabilities.contracts.AgentProcurementRequest("readiness:HP_POTION:1",
                        server.agents.capabilities.contracts.AgentResourceCategory.HP_POTION, 5, 0L,
                        java.util.List.of(server.agents.capabilities.contracts.AgentProcurementMethod.NPC_SHOP),
                        server.agents.capabilities.contracts.AgentSupplyUrgency.CRITICAL, "",
                        Long.MAX_VALUE), ppq, 7, 200_000L, 251010404);
        assertEquals(ppq, AgentSupplyReadiness.shoppingActivity(entry, warrior), "the trip's own activity");
        assertEquals(60, AgentSupplyReadiness.buyTarget(ppq, warrior, true), "PPQ's 30 HP, bought to 200%");

        AgentSupplyReadiness.install(id -> PQ);
        assertEquals(ppq, AgentSupplyReadiness.shoppingActivity(entry, warrior),
                "the trip it is judged by wins over a duty it can read");
    }

    @Test
    void atTheCounterAReadinessTripBuysTowardTheActivityItIsJudgedByWhateverTheDutyThereAnswers() {
        // at Mayoren the duty answers the default (no desk map) while the trip set off for the EPQ standard
        Character mage = withJob(Job.FP_WIZARD);
        when(mage.getId()).thenReturn(437);
        server.agents.runtime.AgentRuntimeEntry entry = new server.agents.runtime.AgentRuntimeEntry(mage, null, null);
        AgentSupplyReadiness.Activity lpq = AgentSupplyReadiness.Activity.partyQuest("lpq");
        AgentSupplyReadiness.install(id -> null);
        assertEquals(null, AgentSupplyReadiness.shoppingActivity(entry, mage), "no duty, no trip: nothing");

        AgentSupplyReadiness.install(id -> PQ);
        AgentSupplyReadinessState state = entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY);
        state.offer(new server.agents.capabilities.contracts.AgentProcurementRequest("readiness:HP_POTION:2",
                server.agents.capabilities.contracts.AgentResourceCategory.HP_POTION, 40, 0L,
                java.util.List.of(server.agents.capabilities.contracts.AgentProcurementMethod.NPC_SHOP),
                server.agents.capabilities.contracts.AgentSupplyUrgency.CRITICAL, "",
                Long.MAX_VALUE), lpq, 5, 150_000L, 221024500);
        assertEquals(lpq, AgentSupplyReadiness.shoppingActivity(entry, mage), "the trip's activity, not the duty's");
        assertEquals(80, AgentSupplyReadiness.buyTarget(AgentSupplyReadiness.shoppingActivity(entry, mage), mage, true));

        state.clearRequest();
        assertEquals(PQ, AgentSupplyReadiness.shoppingActivity(entry, mage), "no trip: the standing duty");
    }

    @Test
    void aRangedFighterBelowThePartyQuestAmmoStandardIsShortAndNoOneElseEverIs() {
        int standard = config.AgentTuning.intValue(
                "server.agents.capabilities.supplies.AgentSupplyReadiness.PARTY_QUEST_AMMO");
        assertEquals(standard, AgentSupplyReadiness.ammoShortfall(0, PQ), "a claw with no stars");
        assertEquals(standard - 120, AgentSupplyReadiness.ammoShortfall(120, PQ));
        assertEquals(0, AgentSupplyReadiness.ammoShortfall(standard, PQ));
        assertEquals(0, AgentSupplyReadiness.ammoShortfall(Integer.MAX_VALUE, PQ),
                "no ammo weapon, or Soul Arrow / Shadow Claw up");
        assertEquals(0, AgentSupplyReadiness.ammoShortfall(0, null), "no activity");
        assertEquals(0, AgentSupplyReadiness.ammoShortfall(withJob(Job.WARRIOR), PQ), "nothing equipped to shoot");
    }

    @Test
    void ammoNeverMakesThePotionShortfall() {
        Character assassin = withJob(Job.ASSASSIN);
        assertTrue(AgentSupplyReadiness.shortfall(20, 25, true, PQ).none(), "the restock duty only ever sees potions");
        assertEquals(0, AgentSupplyReadiness.ammoShortfall(assassin, PQ), "no claw equipped on this mock");
    }

    private static AgentSupplyReadiness.Standard standardFor(String key) {
        return AgentSupplyReadiness.standard(AgentSupplyReadiness.Activity.partyQuest(key));
    }

    private static Character withJob(Job job) {
        Character chr = mock(Character.class);
        when(chr.getJob()).thenReturn(job);
        return chr;
    }
}
