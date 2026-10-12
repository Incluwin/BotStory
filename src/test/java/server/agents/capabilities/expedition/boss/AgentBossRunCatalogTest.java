package server.agents.capabilities.expedition.boss;

import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.capabilities.expedition.balrog.AgentEasyBalrogScenario;
import server.agents.capabilities.expedition.zakum.AgentZakumDefinition;
import server.agents.capabilities.expedition.zakum.AgentZakumScenario;
import server.expeditions.ExpeditionType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The bosses companions can sign up for (P8 step 2): Easy Balrog with Mu Young, then Zakum with Adobis. Zakum's row
 * signs a member up in Adobis's room (211042400) with her registering window and no choices, fights on the altar
 * (280030000) and leaves by Amon (2030010) before and after the clear, since Zakum has no reward room. Its four P8
 * flags are on: Amon after the clear, the Eye of Fire (4001017) kept for the altar, the fake body never a target, and
 * an owner who leaves taking their party. Easy Balrog keeps every flag off, so its runs stay as they are.
 */
class AgentBossRunCatalogTest {

    @Test
    void zakumsRowIsAdobissRoomTheAltarAndAmon() {
        AgentBossRunDefinition zakum = AgentBossRunCatalog.ZAKUM;

        assertEquals("zakum", zakum.key());
        assertEquals(ExpeditionType.ZAKUM, zakum.type());
        assertEquals(List.of(211_042_400, 280_030_000, -1, 2_030_013, 2_030_010, -1),
                List.of(zakum.recruitMapId(), zakum.battleMapId(), zakum.clearMapId(), zakum.entryNpcId(),
                        zakum.exitNpcId(), zakum.rewardExitPortalId()), "no reward room: no clear map, no exit portal");
        assertEquals(List.of(), zakum.joinSelections(), "Adobis's registering branch adds a member with no choice");
        assertEquals(0, zakum.joinSelectionArray().length);
    }

    @Test
    void zakumsFourFlagsAreOn() {
        AgentBossRunDefinition zakum = AgentBossRunCatalog.ZAKUM;

        assertTrue(zakum.exitNpcAfterClear(), "Amon is the way out after the clear too");
        assertEquals(List.of(4_001_017), zakum.markerItemIds(), "the Eye of Fire stays on the altar");
        assertTrue(zakum.neverTargetFakes());
        assertTrue(zakum.ownerLeavingTakesParty());
    }

    /**
     * P11: Normal Balrog signs up with Mu Young's second choice, fights on 105100300 and collects in 105100301, the
     * twins of Easy's maps; her exit NPC is the way into the reward room after the clear, not out (it moves only the
     * talker there); the fake body is never a target; an owner who leaves before the clear takes the party out (rule
     * B); the fight is the Easy scenario with Normal's ids.
     */
    @Test
    void normalBalrogIsMuYoungsSecondChoiceWithRuleBAndNoFakeTargets() {
        AgentBossRunDefinition normal = AgentBossRunCatalog.BALROG_NORMAL;

        assertEquals("balrog_normal", normal.key());
        assertEquals(ExpeditionType.BALROG_NORMAL, normal.type());
        assertEquals(105100100, normal.recruitMapId());
        assertEquals(105100300, normal.battleMapId());
        assertEquals(105100301, normal.clearMapId());
        assertEquals(1061014, normal.entryNpcId());
        assertEquals(List.of(2), normal.joinSelections());
        assertEquals(50, normal.type().getMinLevel(), "her Lv50 gate; a companion under it is refused LEVEL "
                + "(AgentBossRunSessionRegistrationTest)");
        assertEquals(6, normal.type().getMinSize(), "the owner plus 5");
        assertEquals(1061018, normal.exitNpcId());
        assertEquals(2, normal.rewardExitPortalId(), "out99, balog_end");
        assertFalse(normal.exitNpcAfterClear());
        assertEquals(List.of(), normal.markerItemIds());
        assertTrue(normal.neverTargetFakes());
        assertTrue(normal.ownerLeavingTakesParty());
        AgentEasyBalrogScenario fight = assertInstanceOf(AgentEasyBalrogScenario.class, normal.fight().get());
        assertEquals("Normal Balrog", fight.spec().displayName());
        assertEquals(105100300, fight.spec().battleMapId());
        assertNotSame(normal.fight().get(), normal.fight().get(), "a fresh fight per session");
    }

    @Test
    void easyBalrogKeepsEveryP8FlagOff() {
        AgentBossRunDefinition easy = AgentBossRunCatalog.EASY_BALROG;

        assertFalse(easy.exitNpcAfterClear());
        assertEquals(List.of(), easy.markerItemIds());
        assertFalse(easy.neverTargetFakes());
        assertFalse(easy.ownerLeavingTakesParty());
        assertInstanceOf(AgentEasyBalrogScenario.class, easy.fight().get());
    }

    @Test
    void theCatalogListsEasyBalrogThenZakumThenNormalBalrogAndFindsEachByItsKey() {
        assertEquals(List.of(AgentBossRunCatalog.EASY_BALROG, AgentBossRunCatalog.ZAKUM,
                AgentBossRunCatalog.BALROG_NORMAL, AgentBossRunCatalog.LATANICA, AgentBossRunCatalog.NEO_BERGAMOT,
                AgentBossRunCatalog.NEO_DUNAS, AgentBossRunCatalog.NEO_AUFHEBEN, AgentBossRunCatalog.NEO_OBERON,
                AgentBossRunCatalog.NEO_NIBELUNG), AgentBossRunCatalog.all());
        assertSame(AgentBossRunCatalog.BALROG_NORMAL, AgentBossRunCatalog.of(" Balrog_Normal "));
        assertSame(AgentBossRunCatalog.ZAKUM, AgentBossRunCatalog.of(" Zakum "));
        assertSame(AgentBossRunCatalog.EASY_BALROG, AgentBossRunCatalog.of("easy_balrog"));
        assertNull(AgentBossRunCatalog.of("balrog"));
        assertNull(AgentBossRunCatalog.of(null));
    }

    @Test
    void zakumsFightIsAFreshZakumScenarioPerSession() {
        AgentBossFight fight = AgentBossRunCatalog.ZAKUM.fight().get();
        assertInstanceOf(AgentZakumScenario.class, fight);
        assertNotSame(fight, AgentBossRunCatalog.ZAKUM.fight().get(), "each session gets its own fight");

        EventInstanceManager event = mock(EventInstanceManager.class);
        fight.adopt(7, 0);
        fight.tick(List.of(), event, 1_000L);
        fight.end(event);

        verifyNoInteractions(event);
    }

    @Test
    void zakumsIdsAreTheStockOnes() {
        assertEquals(211_042_400, AgentZakumDefinition.RECRUIT_MAP_ID, "Adobis's room behind the Door to Zakum");
        assertEquals(280_030_000, AgentZakumDefinition.BATTLE_MAP_ID, "Zakum's Altar");
        assertEquals(2_030_013, AgentZakumDefinition.ENTRY_NPC_ID, "Adobis");
        assertEquals(2_030_010, AgentZakumDefinition.EXIT_NPC_ID, "Amon");
        assertEquals(4_001_017, AgentZakumDefinition.EYE_ITEM_ID, "Eye of Fire");
        assertEquals(2_111_001, AgentZakumDefinition.ALTAR_REACTOR_ID, "the altar that summons Zakum");
    }

    @Test
    void aDefinitionKeepsItsOwnCopyOfTheMarkerIds() {
        List<Integer> markers = new ArrayList<>(List.of(4_001_017));
        AgentBossRunDefinition definition = new AgentBossRunDefinition("test", ExpeditionType.ZAKUM, 1, 2, -1, 3,
                List.of(), 4, -1, AgentZakumScenario::forCompanions, true, markers, true, true);

        markers.add(1);

        assertEquals(List.of(4_001_017), definition.markerItemIds());
    }
}
