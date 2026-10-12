package server.agents.capabilities.expedition.boss;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
