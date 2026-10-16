package server.agents.capabilities.expedition.boss;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P12 G3: the boss rules catalog (boss-rules.json) holds every boss's never-target list, its marker items and its
 * never-target-fakes flag, and the run catalog reads the last two from it. {@link AgentBossRules#preferences} turns a
 * boss's target priority into what a member grinds first and what it falls back to.
 */
class AgentBossRulesCatalogTest {
    private static final AgentBossRules.Boss CHAIN = new AgentBossRules.Boss("test_chain", "Test chain", null,
            List.of(9_400_112, 9_400_113, 9_400_300), List.of(9_400_120), List.of(9_400_999), null, false, null);

    @Test
    void zakumsRowTakesTheEyeAndTheFakesFlagFromTheRulesCatalog() {
        assertEquals(List.of(4_001_017), AgentBossRules.markerItems("zakum"), "the Eye of Fire");
        assertTrue(AgentBossRules.neverTargetFakes("zakum"));
        assertEquals(AgentBossRules.markerItems("zakum"), AgentBossRunCatalog.ZAKUM.markerItemIds());
        assertTrue(AgentBossRunCatalog.ZAKUM.neverTargetFakes());

        assertTrue(AgentBossRunCatalog.BALROG_NORMAL.neverTargetFakes(), "Normal Balrog's fake body");
        assertEquals(List.of(), AgentBossRunCatalog.BALROG_NORMAL.markerItemIds());
        assertFalse(AgentBossRunCatalog.EASY_BALROG.neverTargetFakes(), "Easy keeps P7's own fake-body guard");
        assertEquals(List.of(), AgentBossRunCatalog.EASY_BALROG.markerItemIds());
    }

    @Test
    void horntailsDisplayPartsAndPinkBeansStatuesAreNeverTargets() {
        IntStream.rangeClosed(8_810_010, 8_810_018).forEach(id ->
                assertTrue(AgentBossRules.neverTarget("horntail", id), "Horntail display part " + id));
        assertFalse(AgentBossRules.neverTarget("horntail", 8_810_002), "a live Horntail head is a target");

        for (int id : List.of(8_820_000, 8_820_019, 8_820_020, 8_820_021, 8_820_022, 8_820_023)) {
            assertTrue(AgentBossRules.neverTarget("pinkbean", id), "Pink Bean statue " + id);
        }
        assertFalse(AgentBossRules.neverTarget("pinkbean", 8_820_001), "the real Pink Bean is a target");
        assertEquals(List.of(4_001_193), AgentBossRules.markerItems("pinkbean"), "PinkBeanBattle.js takes 4001193");
        assertEquals(List.of(), AgentBossRules.markerItems("no_such_boss"));
        assertFalse(AgentBossRules.neverTargetFakes("no_such_boss"));
    }

    /**
     * Deploy review 2026-10-12: fieldKeyOf runs for every hostile monster an Agent looks at, so it reads a sorted index
     * built with the catalog. The first row that lists an id keeps it, as the old row scan did, and a replaced catalog
     * replaces the index too.
     */
    @Test
    void theFieldIndexKeepsTheFirstRowsKeyAndFollowsAReplacedCatalog() {
        try {
            AgentBossRules.replaceForTest(List.of(
                    new AgentBossRules.Boss("field_b", "B", null, null, null, null, List.of(300, 100), false, null),
                    new AgentBossRules.Boss("field_a", "A", null, null, null, null, List.of(100, 200), false, null)));
            assertEquals("field_b", AgentBossRules.fieldKeyOf(100), "the first row listing it");
            assertEquals("field_a", AgentBossRules.fieldKeyOf(200));
            assertEquals("field_b", AgentBossRules.fieldKeyOf(300));
            assertEquals(null, AgentBossRules.fieldKeyOf(150));
            assertEquals(null, AgentBossRules.fieldKeyOf(9_300_003), "King Slime is in no field group");
        } finally {
            AgentBossRules.replaceForTest(null);
        }
        assertEquals("field_victoria", AgentBossRules.fieldKeyOf(2_220_000), "the shipped catalog again: Mano");
        assertEquals(null, AgentBossRules.fieldKeyOf(100));
    }

    @Test
    void eachP13FieldBossBelongsToItsRegionsGroup() {
        for (int id : List.of(2_220_000, 3_220_000, 5_220_002, 6_220_000, 5_220_001, 5_220_000, 8_220_008, 8_220_009,
                6_130_101, 6_300_005, 8_130_100)) {
            assertEquals("field_victoria", AgentBossRules.fieldKeyOf(id), "Victoria " + id);
        }
        assertEquals("field_ossyria", AgentBossRules.fieldKeyOf(8_220_000), "Eliza");
        assertEquals("field_ossyria", AgentBossRules.fieldKeyOf(8_220_001), "Snowman");
        assertEquals("field_ludus", AgentBossRules.fieldKeyOf(5_220_003), "Timer");
        assertEquals("field_ludus", AgentBossRules.fieldKeyOf(6_220_001), "Zeno");
        assertEquals("field_ludus", AgentBossRules.fieldKeyOf(7_220_001), "Nine-Tailed Fox");
        assertEquals("field_mulung", AgentBossRules.fieldKeyOf(7_220_000), "Tae Roon");
        assertEquals("field_mulung", AgentBossRules.fieldKeyOf(7_220_002), "King Sage Cat");
        assertEquals("field_mulung", AgentBossRules.fieldKeyOf(5_220_004), "Giant Centipede");
        assertEquals("field_nihal", AgentBossRules.fieldKeyOf(3_220_001), "Deo");
        assertEquals("field_nihal", AgentBossRules.fieldKeyOf(8_220_002), "Kimera");
        assertEquals(null, AgentBossRules.fieldKeyOf(8_500_001), "Papulatus is a party boss (P15)");
        for (String key : List.of("field_victoria", "field_ossyria", "field_ludus", "field_mulung", "field_nihal")) {
            assertTrue(config.CompanionBossKeys.known(key), key);
        }
    }

    /** P16: the high-level field bosses, by region; Bloody Boom is Pianus's add. */
    @Test
    void eachP16FieldBossBelongsToItsRegionsGroup() {
        for (int id : List.of(8_510_000, 8_520_000, 4_220_000, 4_220_001, 8_510_100)) {
            assertEquals("field_pianus", AgentBossRules.fieldKeyOf(id), "Pianus " + id);
        }
        assertEquals(List.of(8_510_100), AgentBossRules.of("field_pianus").adds(), "Bloody Boom");
        for (int id : List.of(8_220_003, 8_180_000, 8_180_001)) {
            assertEquals("field_leafre", AgentBossRules.fieldKeyOf(id), "Leafre " + id);
        }
        for (int id : List.of(8_220_004, 8_220_005, 8_220_006)) {
            assertEquals("field_temple", AgentBossRules.fieldKeyOf(id), "Temple of Time " + id);
        }
        for (int id : List.of(9_400_014, 9_400_205, 6_090_002, 9_400_120, 9_400_122, 9_400_121)) {
            assertEquals("field_zipangu", AgentBossRules.fieldKeyOf(id), "Zipangu " + id);
        }
        for (int id : List.of(9_400_549, 9_400_575)) {
            assertEquals("field_masteria", AgentBossRules.fieldKeyOf(id), "Masteria " + id);
        }
    }

    @Test
    void theFirstLivePriorityPartIsPreferredAndEveryOtherLiveMonsterIsTheFallback() {
        AgentBossRules.Preferences first = AgentBossRules.preferences(CHAIN,
                List.of(9_400_120, 9_400_113, 9_400_112, 8_140_500));
        assertEquals(Set.of(9_400_112), first.preferred());
        assertEquals(Set.of(9_400_113, 9_400_120, 8_140_500), first.fallback(),
                "a later chain part, an add and a monster the list does not name");

        AgentBossRules.Preferences next = AgentBossRules.preferences(CHAIN, List.of(9_400_300, 9_400_120));
        assertEquals(Set.of(9_400_300), next.preferred(), "the chain moves on as its parts fall");
        assertEquals(Set.of(9_400_120), next.fallback());
    }

    @Test
    void withNoPriorityPartAliveEveryLiveMonsterIsPreferredAndNeverTargetsAreLeftOut() {
        AgentBossRules.Preferences adds = AgentBossRules.preferences(CHAIN, List.of(9_400_120, 9_400_999));
        assertEquals(Set.of(9_400_120), adds.preferred());
        assertEquals(Set.of(), adds.fallback());

        assertTrue(AgentBossRules.preferences(CHAIN, List.of(9_400_999)).isEmpty(), "only a never-target is left");
        assertTrue(AgentBossRules.preferences(CHAIN, List.of()).isEmpty());
        assertEquals(Set.of(100_100), AgentBossRules.preferences(null, List.of(100_100)).preferred(),
                "a boss with no rules grinds whatever lives");
    }

    @Test
    void normalBalrogsClawOrderReadsAsAPriority() {
        AgentBossRules.Boss normal = AgentBossRules.of("balrog_normal");
        AgentBossRules.Preferences claws = AgentBossRules.preferences(normal,
                List.of(8_830_000, 8_830_001, 8_830_002, 6_400_005, 8_830_004));
        assertEquals(Set.of(8_830_002), claws.preferred(), "the initial claw first");
        assertEquals(Set.of(8_830_000, 8_830_001, 6_400_005), claws.fallback(), "the helper 8830004 is never hit");
    }

    @Test
    void pinkBeansCompanionsHitItsPartsTwentyTimesHarderAndEveryOtherBossKeepsTheirOwnStrength() {
        AgentBossRules.Boss pinkBean = AgentBossRules.of("pinkbean");
        assertEquals(2000, pinkBean.damagePercentOn(8_820_003), "Solomon, a guardian");
        assertEquals(2000, pinkBean.damagePercentOn(8_820_001), "Pink Bean himself");
        assertEquals(100, pinkBean.damagePercentOn(8_820_007), "an add keeps the member's own strength");
        assertEquals(100, pinkBean.damagePercentOn(8_820_019), "a statue is no part");
        assertEquals(List.of(8_820_003, 8_820_004, 8_820_005, 8_820_006, 8_820_002, 8_820_001),
                pinkBean.attackKeepOut(), "every guardian's hit keeps out a member that one would nearly kill"
                        + " (Hugin's killed three in run 8), and so does Pink Bean's barrage (Fenna, run 14)");
        assertEquals(0, pinkBean.keepOutHpPercent(), "the global TOUCH_KEEP_OUT_HP_PERCENT (25) for touches");
        assertEquals(65, pinkBean.attackThresholdPercent(),
                "a Hyper Body Bowmaster (~14,400) keeps out of the barrage (~11,500), an unbuffed warrior does not");
        assertEquals(90, AgentBossRules.of("showa").attackThresholdPercent(), "Showa's one threshold for both");
        assertEquals(0, AgentBossRules.of("horntail").attackThresholdPercent(), "the global 80");
        assertEquals(new AgentBossRules.LastStand("fallenPlayers", 4), pinkBean.lastStand(),
                "PinkBeanBattle.js calls its last stand at the fourth fallen player and ends at the fifth");
        assertNull(AgentBossRules.of("horntail").lastStand(), "Horntail's dead re-enter");
        assertTrue(pinkBean.targetPriority().indexOf(8_820_004) < pinkBean.targetPriority().indexOf(8_820_003),
                "Rex, whose attack2 covers the whole floor, falls before Solomon: the two never hit one spot at once");

        for (String key : List.of("zakum", "horntail", "showa", "scarga", "papulatus", "easy_balrog")) {
            AgentBossRules.Boss boss = AgentBossRules.of(key);
            for (int part : boss.targetPriority()) {
                assertEquals(100, boss.damagePercentOn(part), key + " " + part);
            }
        }
    }

    @Test
    void aCompanionDamagePercentReachesTheFocusAndMeleeListsAndStaysInItsBounds() {
        AgentBossRules.Boss boss = new AgentBossRules.Boss("test_strong", "Test strong", null, List.of(100), null,
                null, null, false, null, 0, 0, false, null, null, List.of(200), 0, null, null,
                List.of(new AgentBossRules.Focus(List.of(300), null, List.of())), null, 0, 250);
        assertEquals(250, boss.damagePercentOn(100));
        assertEquals(250, boss.damagePercentOn(200));
        assertEquals(250, boss.damagePercentOn(300));
        assertEquals(100, boss.damagePercentOn(400));

        assertThrows(IllegalArgumentException.class, () -> new AgentBossRules.Boss("test_bad", "Test bad", null,
                List.of(100), null, null, null, false, null, 0, 0, false, null, null, null, 0, null, null, null,
                null, 0, 5001));
    }
}
