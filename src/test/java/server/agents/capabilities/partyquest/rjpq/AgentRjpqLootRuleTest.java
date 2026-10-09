package server.agents.capabilities.partyquest.rjpq;

import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MapItem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;

/**
 * The RJPQ loot clause (plan 7.7): refuse-only, for a companion standing in its run's event. A liquid only while it
 * holds none and never from a beaker, a card key never from a closed door, never a letter or the files, and nothing
 * new once the bag is down to the room the claims need.
 */
class AgentRjpqLootRuleTest {
    private RjpqStageFixture fixture;

    @AfterEach
    void close() {
        if (fixture != null) {
            AgentRjpqSessionRegistry.remove(fixture.session);
            fixture.close();
        }
    }

    private static boolean allows(int itemId, int held, int free, boolean beaker, boolean door) {
        return AgentRjpqLootRule.allows(new AgentRjpqLootRule.Facts(itemId, false, held, free, beaker, door));
    }

    @Test
    void theRunsOwnItemsFollowTheStage() {
        assertTrue(allows(AgentRjpqDefinition.LIQUID_ITEM_ID, 0, 10, false, false));
        assertFalse(allows(AgentRjpqDefinition.LIQUID_ITEM_ID, 1, 10, false, false));
        assertFalse(allows(AgentRjpqDefinition.LIQUID_ITEM_ID, 0, 10, true, false));
        assertTrue(allows(AgentRjpqDefinition.CARD_KEY_ITEM_ID, 3, 10, false, false));
        assertFalse(allows(AgentRjpqDefinition.CARD_KEY_ITEM_ID, 0, 10, false, true));
        for (int never : new int[]{4_001_130, 4_001_131, AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID,
                AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID}) {
            assertFalse(allows(never, 0, 10, false, false), "item " + never);
        }
    }

    @Test
    void anythingElseLeavesTheRoomTheClaimsNeed() {
        assertTrue(allows(4_000_000, 0, 3, false, false));
        assertFalse(allows(4_000_000, 0, 2, false, false));
        assertTrue(allows(4_000_000, 5, 0, false, false), "it stacks on what it holds");
        assertFalse(allows(2_000_000, 0, 1, false, false));
        assertTrue(allows(2_000_000, 0, 2, false, false));
        assertFalse(allows(1_302_000, 3, 1, false, false), "an equip always takes a new slot");
        assertTrue(allows(1_302_000, 0, 2, false, false));
        assertTrue(AgentRjpqLootRule.allows(new AgentRjpqLootRule.Facts(0, true, 0, 0, false, false)), "mesos");
    }

    @Test
    void theClauseReadsTheBeakersAndOnlyBindsACompanionInsideTheRunsEvent() {
        fixture = new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.BEAKER_MAP);
        AgentRjpqSessionRegistry.registerComplete(fixture.session);
        fixture.reactors(fixture.beaker("beaker1", 2, -440, 23), fixture.beaker("beaker2", 0, 36, 25),
                fixture.beaker("beaker3", 0, 544, 21));
        fixture.holds(MIRA, AgentRjpqDefinition.LIQUID_ITEM_ID, 0, 7);
        Inventory etc = mock(Inventory.class);
        when(etc.getNumFreeSlot()).thenReturn((short) 10);
        when(fixture.character(MIRA).getInventory(InventoryType.ETC)).thenReturn(etc);
        MapItem poured = fixture.drop(701, AgentRjpqDefinition.LIQUID_ITEM_ID, -430, 23);
        MapItem loose = fixture.drop(702, AgentRjpqDefinition.LIQUID_ITEM_ID, 200, 23);

        assertFalse(AgentRjpqSessionRegistry.canLoot(fixture.character(MIRA), poured));
        assertTrue(AgentRjpqSessionRegistry.canLoot(fixture.character(MIRA), loose));
        assertTrue(AgentRjpqSessionRegistry.canLoot(fixture.character(BEN), poured), "the human leader");
        when(fixture.character(TOBI).getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertTrue(AgentRjpqSessionRegistry.canLoot(fixture.character(TOBI), poured), "outside the run's event");
    }
}
