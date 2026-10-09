package server.agents.capabilities.partyquest.rjpq;

import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;

/**
 * After the boss: each companion claims for itself from beside each NPC (the marble, the traces, the reward), never
 * before the clear, and a full tab costs it its cheapest item and one line, a few tries at most.
 */
class AgentRjpqClaimTest {
    private RjpqStageFixture fixture;

    private void on(int zenumistMap, boolean cleared) {
        fixture = new RjpqStageFixture(AgentRjpqSide.ZENUMIST, zenumistMap);
        when(fixture.event.isEventCleared()).thenReturn(cleared);
    }

    @AfterEach
    void close() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void afterTheBossItWalksToRomeoAndClaimsTheMarbleFromBesideHim() {
        on(AgentRjpqDefinition.CENTER_LAB_MAP, true);
        when(fixture.actions.npcPosition(any(), eq(2_112_006))).thenReturn(new Point(-370, -150));
        fixture.at(MIRA, 0, 100);

        fixture.tick(MIRA, 2_000L);
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(-370, -150), true);

        fixture.at(MIRA, -360, -150);
        fixture.tick(MIRA, 2_100L);
        verify(fixture.partyQuest).runNpc(fixture.character(MIRA), 2_112_006);
        assertEquals(List.of("we did it! juliet is safe. romeo has our reward."), fixture.said);
    }

    @Test
    void beforeTheClearNoCompanionEverTalksToRomeo() {
        on(AgentRjpqDefinition.CENTER_LAB_MAP, false);
        when(fixture.actions.npcPosition(any(), eq(2_112_006))).thenReturn(new Point(-370, -150));
        fixture.at(MIRA, -360, -150);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.partyQuest, never()).runNpc(any(), eq(2_112_006));
    }

    @Test
    void theTracesAndTheRewardAreClaimedFromTheirOwnNpcs() {
        on(AgentRjpqDefinition.TRACES_MAP, true);
        when(fixture.actions.npcPosition(any(), anyInt())).thenReturn(new Point(200, 100));
        fixture.at(MIRA, 200, 100);
        fixture.tick(MIRA, 2_000L);
        verify(fixture.partyQuest).runNpc(fixture.character(MIRA), 2_112_001);

        fixture.close();
        on(AgentRjpqDefinition.REWARD_MAP, true);
        when(fixture.actions.npcPosition(any(), anyInt())).thenReturn(new Point(200, 100));
        fixture.at(TOBI, 200, 100);
        fixture.tick(TOBI, 2_000L);
        verify(fixture.partyQuest).runNpc(fixture.character(TOBI), AgentRjpqDefinition.REWARD_NPC_ID);
    }

    @Test
    void aRefusedMarbleCostsTheCheapestEtcItemThenGivesUpAfterAFewTries() {
        on(AgentRjpqDefinition.CENTER_LAB_MAP, true);
        when(fixture.actions.npcPosition(any(), eq(2_112_006))).thenReturn(new Point(-370, -150));
        fixture.at(MIRA, -360, -150);
        Inventory etc = mock(Inventory.class);
        when(etc.isFull()).thenReturn(true);
        List<Item> bag = List.of(item(4_001_132, 11), item(4_000_000, 12), item(4_000_021, 13));
        when(etc.list()).thenReturn(bag);
        when(fixture.character(MIRA).getInventory(InventoryType.ETC)).thenReturn(etc);

        for (long now = 2_000L; now <= 8_000L; now += 1_000L) {
            fixture.tick(MIRA, now);
        }

        verify(fixture.partyQuest, times(3)).runNpc(fixture.character(MIRA), 2_112_006);
        verify(fixture.inventory, times(2)).dropItem(fixture.character(MIRA), InventoryType.ETC, (short) 12, (short) 5);
        verify(fixture.inventory, never()).dropItem(any(), any(), eq((short) 11), anyShort());
        assertTrue(fixture.said.contains("my bag's full, one sec."));
        assertEquals(1, fixture.said.stream().filter("my bag's full, one sec."::equals).count());
    }

    @Test
    void theItemDroppedIsTheCheapestTheCompanionMayPartWithNeverTheRunsOwn() {
        Item liquid = item(4_001_132, 1);
        Item marble = item(4_001_159, 2);
        Item snail = item(4_000_000, 3);
        Item gem = item(4_004_000, 4);
        Item quest = item(4_031_000, 5);
        List<Item> bag = List.of(liquid, marble, gem, snail, quest);

        assertEquals(snail, AgentRjpqCompanionPlay.cheapest(bag, id -> id == 4_000_000 ? 3 : id == 4_031_000 ? 0 : 900,
                id -> id != 4_031_000));
        assertEquals(null, AgentRjpqCompanionPlay.cheapest(List.of(liquid, marble), id -> 1, id -> true));
    }

    private static Item item(int itemId, int slot) {
        Item item = mock(Item.class);
        when(item.getItemId()).thenReturn(itemId);
        when(item.getPosition()).thenReturn((short) slot);
        when(item.getQuantity()).thenReturn((short) 5);
        return item;
    }
}
