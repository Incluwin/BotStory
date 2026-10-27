package server.agents.capabilities.looting;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.behavior.AgentImperfections;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F8.5 loot slip guards (docs/BOT_CONCEPT_PLAN.md F8): a slip only ever leaves a cheap monster drop. Never meso, an
 * equip, a cash or quest item, a player's drop, an item its host's quests need, never inside an event instance; and it
 * only narrows a policy (LIKE_OWNER's skips stay skipped).
 */
class AgentLootSlipTest {
    private static final int SNAIL_SHELL = 4000000;
    private static final int QUEST_ITEM = 4031013;
    private static final int CASH_ITEM = 5000000;
    private static final int SWORD = 1302000;
    private static final IntPredicate QUEST_OR_CASH = itemId -> itemId == QUEST_ITEM || itemId == CASH_ITEM;

    private final List<Integer> asked = new ArrayList<>();
    private final Character bot = mock(Character.class);

    @BeforeEach
    void wire() {
        MapleMap map = mock(MapleMap.class);
        when(map.getAllMonsters()).thenReturn(List.of());
        when(bot.getId()).thenReturn(7);
        when(bot.getMap()).thenReturn(map);
        AgentImperfections.install(new AgentImperfections.Rule() {
            @Override public boolean calibrated(Character agent) { return true; }
            @Override public boolean slips(Character agent, AgentImperfections.Slip slip) { return true; }
            @Override public boolean leavesDrop(Character agent, int itemId, int dropObjectId) {
                asked.add(itemId);
                return true;
            }
            @Override public float personalHpAlert(Character agent, float base) { return base; }
        });
    }

    @AfterEach
    void off() {
        AgentImperfections.install(null);
        AgentLootPolicy.install(null);
    }

    private static MapItem drop(int itemId, int meso, boolean fromMonster) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getMeso()).thenReturn(meso);
        when(drop.getObjectId()).thenReturn(1000 + itemId % 1000);
        when(drop.getDropper()).thenReturn(fromMonster ? mock(Monster.class) : mock(Character.class));
        return drop;
    }

    @Test
    void onlyACheapMonsterDropIsEverLeft() {
        assertTrue(AgentLootPolicy.slipsPast(bot, drop(SNAIL_SHELL, 0, true), null, QUEST_OR_CASH));
        assertFalse(AgentLootPolicy.slipsPast(bot, drop(QUEST_ITEM, 0, true), null, QUEST_OR_CASH), "a quest item");
        assertFalse(AgentLootPolicy.slipsPast(bot, drop(CASH_ITEM, 0, true), null, QUEST_OR_CASH), "a cash item");
        assertTrue(AgentLootPolicy.permits(bot, drop(0, 120, true)), "meso");
        assertTrue(AgentLootPolicy.permits(bot, drop(SWORD, 0, true)), "an equip");
        assertTrue(AgentLootPolicy.permits(bot, drop(SNAIL_SHELL, 0, false)), "a player's drop");
        assertEquals(List.of(SNAIL_SHELL), asked, "the host is only asked about the snail shell");
    }

    @Test
    void neverAnItemTheHostsQuestsNeedNorInsideAnEventInstance() {
        AgentLootPolicy.install(agentId -> new AgentLootPolicy.Policy(AgentLootPolicy.Mode.ALL, false,
                (agent, itemId) -> itemId == SNAIL_SHELL));
        assertTrue(AgentLootPolicy.permits(bot, drop(SNAIL_SHELL, 0, true)), "its owner's quest needs it");
        AgentLootPolicy.install(null);
        when(bot.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertTrue(AgentLootPolicy.permits(bot, drop(SNAIL_SHELL, 0, true)), "a party quest's or boss run's loot");
        assertTrue(asked.isEmpty());
    }

    @Test
    void aSlipOnlyNarrowsAPolicy() {
        AgentLootPolicy.install(agentId -> new AgentLootPolicy.Policy(AgentLootPolicy.Mode.LIKE_OWNER, false,
                (agent, itemId) -> false, (agent, itemId) -> itemId == SNAIL_SHELL));
        assertFalse(AgentLootPolicy.permits(bot, drop(SNAIL_SHELL, 0, true)), "its owner always leaves it");
        assertTrue(AgentLootPolicy.permits(bot, drop(SWORD, 0, true)), "a slip never touches an equip");
        AgentImperfections.install(null);
        assertTrue(AgentLootPolicy.permits(bot, drop(SWORD, 0, true)));
        assertTrue(asked.isEmpty());
    }
}
