package server.agents.capabilities.looting;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.balrog.AgentEasyBalrogRewardClaimRegistry;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NO_BOSS inside a boss run (docs/BOT_MANAGEMENT_PLAN.md P7, A9): inside an event instance the per-Agent policy still
 * stands aside, except that an Agent whose host leaves boss drops in its run
 * ({@link AgentLootPolicy.Rule#leavesBossDropsInRun}) and whose policy leaves a boss's drops skips a boss monster's
 * drops. A reactor's drops (the reward room's spray) are no boss drops, a drop a reward claim gives the Agent is still
 * its own, and a party quest's run (the rule false) or a policy that picks up boss drops loot exactly as before.
 */
class AgentLootPolicyBossRunTest {
    private static final int BOT = 7;
    private static final int OWNER = 5;
    private static final int SNAIL_SHELL = 4000000;

    private final Map<Integer, AgentLootPolicy.Policy> policies = new HashMap<>();
    /** The Agents whose host leaves boss drops in their run: those in a boss run. */
    private final Set<Integer> inBossRun = new HashSet<>();
    private final Character bot = bot();
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, mock(Character.class), null);
    private final MapleMap map = mock(MapleMap.class);

    @BeforeEach
    void insideAnEventInstance() {
        when(bot.getEventInstance()).thenReturn(mock(EventInstanceManager.class)); // the expedition's instance
        when(bot.getMap()).thenReturn(map);
        AgentLootPolicy.install(new AgentLootPolicy.Rule() {
            @Override
            public AgentLootPolicy.Policy policy(int agentCharacterId) {
                return policies.get(agentCharacterId);
            }

            @Override
            public boolean leavesBossDropsInRun(int agentCharacterId) {
                return inBossRun.contains(agentCharacterId);
            }
        });
    }

    @AfterEach
    void uninstall() {
        AgentLootPolicy.install(null);
        AgentEasyBalrogRewardClaimRegistry.clear(map);
    }

    @Test
    void aBossRunLeavesABossMonstersDropsToTheHumans() {
        policies.put(BOT, policy(AgentLootPolicy.Mode.ALL, true));
        inBossRun.add(BOT);
        MapItem bossItem = drop(SNAIL_SHELL, 0, monster(true), BOT);
        MapItem bossMeso = drop(0, 900, monster(true), BOT);

        assertFalse(AgentLootPolicy.permits(bot, bossItem));
        assertFalse(AgentLootPolicy.permits(bot, bossMeso));
        assertAllThreeEntryPoints(false, bossItem);
        assertAllThreeEntryPoints(false, bossMeso);
    }

    @Test
    void everythingElseInTheRunIsStillLeftToTheRunsOwnRules() {
        policies.put(BOT, policy(AgentLootPolicy.Mode.NONE, true)); // the mode still stands aside in an instance
        inBossRun.add(BOT);
        MapItem ordinary = drop(SNAIL_SHELL, 0, monster(false), OWNER);
        MapItem playerDrop = drop(SNAIL_SHELL, 0, mock(Character.class), OWNER);

        assertTrue(AgentLootPolicy.permits(bot, ordinary));
        assertTrue(AgentLootPolicy.permits(bot, playerDrop));
        assertAllThreeEntryPoints(true, ordinary);
    }

    @Test
    void aReactorsDropIsNoBossDrop() {
        policies.put(BOT, policy(AgentLootPolicy.Mode.ALL, true));
        inBossRun.add(BOT);
        MapItem chestItem = drop(SNAIL_SHELL, 0, mock(Reactor.class), OWNER); // the reward room's spray

        assertTrue(AgentLootPolicy.permits(bot, chestItem));
        assertAllThreeEntryPoints(true, chestItem);
    }

    @Test
    void aBossDropARewardClaimAssignsToTheAgentIsStillItsOwn() {
        policies.put(BOT, policy(AgentLootPolicy.Mode.ALL, true));
        inBossRun.add(BOT);
        MapItem claimed = drop(SNAIL_SHELL, 0, monster(true), BOT);
        MapItem someoneElses = drop(SNAIL_SHELL, 0, monster(true), BOT);
        AgentEasyBalrogRewardClaimRegistry.replace(map, Map.of(
                claimed.getObjectId(), BOT,
                someoneElses.getObjectId(), BOT + 1));

        assertTrue(AgentLootPolicy.permits(bot, claimed));
        assertAllThreeEntryPoints(true, claimed);
        assertFalse(AgentLootPolicy.permits(bot, someoneElses), "a claim for another character gives it nothing");
    }

    @Test
    void aPartyQuestRunOrAPolicyThatPicksUpBossDropsLootsAsBefore() {
        MapItem bossItem = drop(SNAIL_SHELL, 0, monster(true), OWNER);

        policies.put(BOT, policy(AgentLootPolicy.Mode.ALL, true)); // not in a boss run: a PQ, the Yetis
        assertTrue(AgentLootPolicy.permits(bot, bossItem), "the rule false: as before");
        assertAllThreeEntryPoints(true, bossItem);

        inBossRun.add(BOT);
        policies.put(BOT, policy(AgentLootPolicy.Mode.ALL, false)); // picks up boss drops too
        assertTrue(AgentLootPolicy.permits(bot, bossItem), "leaveBossDrops false: as before");
        assertAllThreeEntryPoints(true, bossItem);

        policies.remove(BOT); // no policy for it
        assertTrue(AgentLootPolicy.permits(bot, bossItem), "no policy: as before");

        AgentLootPolicy.install(policies::get); // a host that never says (the rule's default)
        policies.put(BOT, policy(AgentLootPolicy.Mode.ALL, true));
        assertTrue(AgentLootPolicy.permits(bot, bossItem), "the default rule: as before");
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private static AgentLootPolicy.Policy policy(AgentLootPolicy.Mode mode, boolean leaveBossDrops) {
        return new AgentLootPolicy.Policy(mode, leaveBossDrops, (agent, itemId) -> false);
    }

    private void assertAllThreeEntryPoints(boolean expected, MapItem drop) {
        long now = drop.getDropTime() + 60_000L;
        String why = "item " + drop.getItemId() + " meso " + drop.getMeso();
        if (expected) {
            assertTrue(AgentLootEligibility.canBotReceiveAssignedLoot(entry, bot, drop), why);
            assertTrue(AgentLootEligibility.canBotLoot(entry, bot, drop), why);
            assertTrue(AgentLootEligibility.canBotTargetLoot(entry, bot, map, drop, now), why);
        } else {
            assertFalse(AgentLootEligibility.canBotReceiveAssignedLoot(entry, bot, drop), why);
            assertFalse(AgentLootEligibility.canBotLoot(entry, bot, drop), why);
            assertFalse(AgentLootEligibility.canBotTargetLoot(entry, bot, map, drop, now), why);
        }
    }

    private static Character bot() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(BOT);
        when(bot.needQuestItem(anyInt(), anyInt())).thenReturn(true);
        Inventory room = mock(Inventory.class);
        for (InventoryType type : InventoryType.values()) {
            when(bot.getInventory(type)).thenReturn(room);
        }
        return bot;
    }

    private static Monster monster(boolean boss) {
        Monster mob = mock(Monster.class);
        when(mob.isBoss()).thenReturn(boss);
        return mob;
    }

    private int nextObjectId = 100;

    /** A drop the loot rules allow the bot: {@code itemId} (0 with {@code meso} for a meso drop), owned by {@code ownerId}. */
    private MapItem drop(int itemId, int meso, MapObject dropper, int ownerId) {
        MapItem drop = mock(MapItem.class);
        int objectId = nextObjectId++;
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getItemId()).thenReturn(meso > 0 ? meso : itemId);
        when(drop.getMeso()).thenReturn(meso);
        when(drop.getDropper()).thenReturn(dropper);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.getQuest()).thenReturn(-1);
        when(drop.getDropTime()).thenReturn(1_000L);
        when(drop.canBePickedBy(any(Character.class))).thenReturn(true);
        when(map.getMapObject(objectId)).thenReturn(drop);
        return drop;
    }
}
