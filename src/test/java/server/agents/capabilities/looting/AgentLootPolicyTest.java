package server.agents.capabilities.looting;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-Agent loot seam (docs/BOT_MANAGEMENT_PLAN.md P3b): a host's policy narrows what the loot rules allow at all
 * three AgentLootEligibility entry points, one rule at a time (NONE, OWN_KILLS, QUEST_ONLY and its meso, the boss-drop
 * flag), and stands aside inside an event instance, where the PQ, Balrog and Yeti rules decide alone. With no rule, or
 * no policy for an Agent, nothing changes.
 */
class AgentLootPolicyTest {
    private static final int BOT = 7;
    private static final int OWNER = 5;
    private static final int SNAIL_SHELL = 4000000;
    private static final int QUEST_ITEM = 4000019;

    private final Map<Integer, AgentLootPolicy.Policy> policies = new HashMap<>();
    private final Character bot = bot();
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, mock(Character.class), null);
    private final MapleMap map = mock(MapleMap.class);

    @AfterEach
    void uninstall() {
        AgentLootPolicy.install(null);
    }

    // ---- no rule: nothing changes ------------------------------------------------------------------

    @Test
    void withNoRuleInstalledEveryDropTheLootRulesAllowIsStillAllowed() {
        MapItem item = drop(SNAIL_SHELL, 0, monster(false, false), OWNER);
        MapItem meso = drop(0, 120, monster(true, false), OWNER);
        MapItem bossItem = drop(SNAIL_SHELL, 0, monster(true, false), OWNER);

        for (MapItem drop : new MapItem[]{item, meso, bossItem}) {
            assertTrue(AgentLootPolicy.permits(bot, drop));
            assertAllThreeEntryPoints(true, drop);
        }
    }

    @Test
    void anAgentTheHostHasNoPolicyForLootsAsBefore() {
        installRule();
        policies.put(BOT + 1, policy(AgentLootPolicy.Mode.NONE, true)); // another Agent's
        MapItem item = drop(SNAIL_SHELL, 0, monster(true, false), OWNER);

        assertTrue(AgentLootPolicy.permits(bot, item));
        assertAllThreeEntryPoints(true, item);
    }

    // ---- one rule at a time ---------------------------------------------------------------------------

    @Test
    void nonePicksUpNothingAtAnyEntryPoint() {
        installRule();
        policies.put(BOT, policy(AgentLootPolicy.Mode.NONE, false));

        for (MapItem drop : new MapItem[]{drop(SNAIL_SHELL, 0, monster(false, true), BOT), drop(0, 50, monster(false, true), BOT)}) {
            assertFalse(AgentLootPolicy.permits(bot, drop));
            assertAllThreeEntryPoints(false, drop);
        }
    }

    @Test
    void allPicksUpWhatTheLootRulesAllow() {
        installRule();
        policies.put(BOT, policy(AgentLootPolicy.Mode.ALL, false));
        MapItem drop = drop(SNAIL_SHELL, 0, monster(false, false), OWNER);

        assertTrue(AgentLootPolicy.permits(bot, drop));
        assertAllThreeEntryPoints(true, drop);
    }

    @Test
    void ownKillsTakesOnlyDropsOfMonstersItFoughtOrWasCreditedWith() {
        installRule();
        policies.put(BOT, policy(AgentLootPolicy.Mode.OWN_KILLS, false));

        MapItem fought = drop(SNAIL_SHELL, 0, monster(false, true), OWNER);    // it hit the mob; the owner got the credit
        MapItem credited = drop(SNAIL_SHELL, 0, monster(false, false), BOT);   // the kill was credited to it
        MapItem itsMeso = drop(0, 40, monster(false, true), OWNER);
        MapItem ownersKill = drop(SNAIL_SHELL, 0, monster(false, false), OWNER);
        MapItem ownersMeso = drop(0, 40, monster(false, false), OWNER);
        MapItem playerDrop = drop(SNAIL_SHELL, 0, mock(Character.class), OWNER); // not a monster's

        assertTrue(AgentLootPolicy.permits(bot, fought));
        assertTrue(AgentLootPolicy.permits(bot, credited));
        assertTrue(AgentLootPolicy.permits(bot, itsMeso));
        assertAllThreeEntryPoints(true, fought);
        assertFalse(AgentLootPolicy.permits(bot, ownersKill));
        assertFalse(AgentLootPolicy.permits(bot, ownersMeso));
        assertFalse(AgentLootPolicy.permits(bot, playerDrop));
        assertAllThreeEntryPoints(false, ownersKill);
    }

    @Test
    void ownKillsNeverReadsTheDamageRecordOfAMonsterStillAlive() {
        installRule();
        policies.put(BOT, policy(AgentLootPolicy.Mode.OWN_KILLS, false));
        Monster alive = monster(false, true);
        when(alive.isAlive()).thenReturn(true); // a pickpocket's meso: dropped by a monster still fighting
        MapItem pickpocketed = drop(0, 40, alive, OWNER);

        assertFalse(AgentLootPolicy.permits(bot, pickpocketed), "no kill yet");
        verify(alive, never()).isAttackedBy(any()); // channel threads still write that record

        MapItem credited = drop(0, 40, alive, BOT);
        assertTrue(AgentLootPolicy.permits(bot, credited), "credited to it: its own");
    }

    @Test
    void questOnlyTakesWhatTheHostsQuestsNeedAndMeso() {
        installRule();
        List<Character> asked = new ArrayList<>();
        AgentLootPolicy.QuestNeeds needs = (agent, itemId) -> asked.add(agent) && itemId == QUEST_ITEM;
        policies.put(BOT, new AgentLootPolicy.Policy(AgentLootPolicy.Mode.QUEST_ONLY, false, needs));

        MapItem needed = drop(QUEST_ITEM, 0, monster(false, false), OWNER);
        MapItem meso = drop(0, 75, monster(false, false), OWNER);
        MapItem other = drop(SNAIL_SHELL, 0, monster(false, true), BOT);

        assertTrue(AgentLootPolicy.permits(bot, needed));
        assertTrue(AgentLootPolicy.permits(bot, meso), "meso is split with the party and pays for its potions");
        assertFalse(AgentLootPolicy.permits(bot, other));
        assertAllThreeEntryPoints(true, needed);
        assertAllThreeEntryPoints(false, other);
        assertTrue(asked.stream().allMatch(agent -> agent == bot), "asked with the Agent, whose own bag counts too");

        policies.put(BOT, new AgentLootPolicy.Policy(AgentLootPolicy.Mode.QUEST_ONLY, false, null));
        assertFalse(AgentLootPolicy.permits(bot, needed), "no quest answer: no quest item");
        assertTrue(AgentLootPolicy.permits(bot, meso));
    }

    @Test
    void likeOwnerLeavesWhatItsOwnerLeavesButNeverMesoOrWhatTheirQuestsNeed() {
        // F2.4 (docs/BOT_CONCEPT_PLAN.md): the owner always leaves this category, quest item included
        installRule();
        AgentLootPolicy.QuestNeeds skips = (agent, itemId) -> itemId == SNAIL_SHELL || itemId == QUEST_ITEM;
        AgentLootPolicy.QuestNeeds needs = (agent, itemId) -> itemId == QUEST_ITEM;
        policies.put(BOT, new AgentLootPolicy.Policy(AgentLootPolicy.Mode.LIKE_OWNER, false, needs, skips));

        MapItem skipped = drop(SNAIL_SHELL, 0, monster(false, false), OWNER);
        MapItem needed = drop(QUEST_ITEM, 0, monster(false, false), OWNER);
        MapItem meso = drop(0, 75, monster(false, false), OWNER);
        MapItem other = drop(4010000, 0, monster(false, false), OWNER);

        assertFalse(AgentLootPolicy.permits(bot, skipped), "left like its owner leaves it");
        assertTrue(AgentLootPolicy.permits(bot, needed), "what the owner's quests need is never left");
        assertTrue(AgentLootPolicy.permits(bot, meso), "meso is never left: it never comes to pick up nothing");
        assertTrue(AgentLootPolicy.permits(bot, other), "everything else, as ALL");
        assertAllThreeEntryPoints(false, skipped);
        assertAllThreeEntryPoints(true, needed);

        policies.put(BOT, new AgentLootPolicy.Policy(AgentLootPolicy.Mode.LIKE_OWNER, false, needs));
        assertTrue(AgentLootPolicy.permits(bot, skipped), "nothing learned: LIKE_OWNER is ALL");
    }

    @Test
    void leaveBossDropsSkipsABossMonstersDropsWhateverTheMode() {
        installRule();
        MapItem bossItem = drop(SNAIL_SHELL, 0, monster(true, true), BOT);
        MapItem bossMeso = drop(0, 900, monster(true, true), BOT);
        MapItem ordinary = drop(SNAIL_SHELL, 0, monster(false, true), BOT);
        for (AgentLootPolicy.Mode mode : new AgentLootPolicy.Mode[]{AgentLootPolicy.Mode.ALL,
                AgentLootPolicy.Mode.OWN_KILLS, AgentLootPolicy.Mode.QUEST_ONLY}) {
            policies.put(BOT, new AgentLootPolicy.Policy(mode, true, (agent, itemId) -> true));

            assertFalse(AgentLootPolicy.permits(bot, bossItem), mode.name());
            assertFalse(AgentLootPolicy.permits(bot, bossMeso), mode.name());
            assertTrue(AgentLootPolicy.permits(bot, ordinary), mode.name());
        }
        assertAllThreeEntryPoints(false, bossItem);

        policies.put(BOT, policy(AgentLootPolicy.Mode.ALL, false));
        assertTrue(AgentLootPolicy.permits(bot, bossItem), "boss drops picked up when the flag is off");
        assertAllThreeEntryPoints(true, bossItem);
    }

    // ---- the existing rules keep priority -------------------------------------------------------------

    @Test
    void insideAnEventInstanceThePolicyStandsAsideForTheRunsOwnLootRules() {
        installRule();
        policies.put(BOT, policy(AgentLootPolicy.Mode.NONE, true));
        when(bot.getEventInstance()).thenReturn(mock(EventInstanceManager.class)); // a PQ, Balrog, the Yetis
        MapItem bossItem = drop(SNAIL_SHELL, 0, monster(true, false), OWNER);

        assertTrue(AgentLootPolicy.permits(bot, bossItem));
        assertAllThreeEntryPoints(true, bossItem);
    }

    @Test
    void aPolicyNeverWidensWhatTheLootRulesRefuse() {
        installRule();
        policies.put(BOT, policy(AgentLootPolicy.Mode.ALL, false));
        MapItem pass = drop(AgentLootEligibility.KPQ_PASS, 0, monster(false, true), BOT); // no KPQ session: refused
        MapItem notTheirs = drop(SNAIL_SHELL, 0, monster(false, true), OWNER);
        when(notTheirs.canBePickedBy(bot)).thenReturn(false);

        assertAllThreeEntryPoints(false, pass);
        assertAllThreeEntryPoints(false, notTheirs);
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private void installRule() {
        AgentLootPolicy.install(policies::get);
    }

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

    private Monster monster(boolean boss, boolean foughtByBot) {
        Monster mob = mock(Monster.class);
        when(mob.isBoss()).thenReturn(boss);
        when(mob.isAttackedBy(bot)).thenReturn(foughtByBot);
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
