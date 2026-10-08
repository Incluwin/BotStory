package server.agents.capabilities.expedition.boss;

import client.Character;
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
    void theCatalogListsEasyBalrogThenZakumAndFindsEachByItsKey() {
        assertEquals(List.of(AgentBossRunCatalog.EASY_BALROG, AgentBossRunCatalog.ZAKUM), AgentBossRunCatalog.all());
        assertSame(AgentBossRunCatalog.ZAKUM, AgentBossRunCatalog.of(" Zakum "));
        assertSame(AgentBossRunCatalog.EASY_BALROG, AgentBossRunCatalog.of("easy_balrog"));
        assertNull(AgentBossRunCatalog.of("balrog"));
        assertNull(AgentBossRunCatalog.of(null));
    }

    @Test
    void zakumsFightIsAFreshIdleScenarioPerSession() {
        AgentBossFight fight = AgentBossRunCatalog.ZAKUM.fight().get();
        assertInstanceOf(AgentZakumScenario.class, fight);
        assertNotSame(fight, AgentBossRunCatalog.ZAKUM.fight().get(), "each session gets its own fight");

        Character member = mock(Character.class);
        EventInstanceManager event = mock(EventInstanceManager.class);
        fight.adopt(7, 0);
        fight.tick(List.of(member), event, 1_000L);
        fight.end(event);

        verifyNoInteractions(member, event);
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
