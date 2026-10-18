package server.agents.capabilities.looting;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import constants.inventory.ItemConstants;
import server.agents.capabilities.partyquest.AgentPqRuntime;
import server.agents.capabilities.partyquest.epq.AgentEpqDefinition;
import server.agents.capabilities.partyquest.epq.AgentEpqSessionRegistry;
import server.agents.capabilities.partyquest.hpq.AgentHpqSessionRegistry;
import server.agents.capabilities.partyquest.kpq.AgentKpqDefinition;
import server.agents.capabilities.partyquest.kpq.AgentKpqMemberState;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.agents.capabilities.partyquest.lpq.AgentLpqDefinition;
import server.agents.capabilities.partyquest.lpq.AgentLpqSessionRegistry;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqDefinition;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqSessionRegistry;
import server.agents.capabilities.partyquest.opq.AgentOpqDefinition;
import server.agents.capabilities.partyquest.opq.AgentOpqSessionRegistry;
import server.agents.capabilities.partyquest.ppq.AgentPpqDefinition;
import server.agents.capabilities.partyquest.ppq.AgentPpqSessionRegistry;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqDefinition;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSessionRegistry;
import server.agents.capabilities.expedition.balrog.AgentEasyBalrogRewardGracePolicy;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.runtime.AgentSessionLifecycleRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.capabilities.partyquest.AgentPartyQuestHooks;
import server.maps.MapItem;
import server.maps.MapleMap;

public final class AgentLootEligibility {
    public static final int KPQ_COUPON = 4001007;
    public static final int KPQ_PASS = 4001008;
    public static final int HPQ_RICE_CAKE = 4001101;
    public static final long SERVER_PICKUP_MIN_AGE_MS = 400L;
    public static final long MIN_TARGET_LOOT_AGE_MS = 3_000L;
    public static final long BOT_INVENTORY_DROP_TARGET_LOOT_AGE_MS = 15_000L;

    private AgentLootEligibility() {
    }

    public static boolean isPresent(MapleMap map, MapItem drop) {
        return map != null
                && drop != null
                && !drop.isPickedUp()
                && map.getMapObject(drop.getObjectId()) == drop;
    }

    public static boolean canBotLoot(AgentRuntimeEntry entry, Character bot, MapItem drop) {
        if (!canBotReceiveAssignedLoot(entry, bot, drop)) {
            return false;
        }
        if (!AgentEasyBalrogRewardGracePolicy.permitsAgentLoot(
                bot, drop, System.currentTimeMillis())) {
            return false;
        }

        return true;
    }

    /** Base inventory/quest eligibility used before a scenario publishes reward claims. */
    public static boolean canBotReceiveAssignedLoot(
            AgentRuntimeEntry entry, Character bot, MapItem drop) {
        return rulesAllow(entry, bot, drop) && hasRoomFor(bot, drop);
    }

    /** Every rule of {@link #canBotReceiveAssignedLoot} but the bag's free slot, its host's loot policy last. */
    private static boolean rulesAllow(AgentRuntimeEntry entry, Character bot, MapItem drop) {
        if (entry == null || bot == null || drop == null || !drop.canBePickedBy(bot)) {
            return false;
        }

        int itemId = drop.getItemId();
        if (AgentLpqSessionRegistry.preservesRoomDoorMarker(bot, drop)) {
            return false;
        }
        if (AgentLmpqSessionRegistry.preservesPortalMarker(bot, drop)) return false;
        if (AgentOpqSessionRegistry.preservesMarker(bot, drop)) return false;
        if (AgentBossRunRegistry.preservesMarker(bot, drop)) return false;
        AgentKpqSession kpqSession = AgentKpqSessionRegistry.forMember(bot.getId());
        if (kpqSession != null) {
            AgentKpqMemberState member = kpqSession.member(bot.getId());
            if (itemId == KPQ_PASS && !AgentKpqSessionRegistry.canLootPass(bot.getId())) {
                return false;
            }
            if (itemId == KPQ_COUPON && (member == null
                    || !AgentKpqSessionRegistry.canLootCoupon(bot.getId())
                    || member.couponTarget() <= 0
                    || bot.getItemQuantity(KPQ_COUPON, false) >= member.couponTarget())) {
                return false;
            }
            if (itemId == AgentKpqDefinition.SQUISHY_SHOES
                    && !AgentKpqSessionRegistry.canLootSquishyShoes(bot.getId())) {
                return false;
            }
        }
        if (itemId == KPQ_PASS && kpqSession == null) {
            return false;
        }
        if (itemId == HPQ_RICE_CAKE
                && !AgentHpqSessionRegistry.canLootRiceCake(bot, drop)) {
            return false;
        }

        if ((itemId == AgentLpqDefinition.PASS || itemId == AgentLpqDefinition.BOSS_KEY)
                && !AgentLpqSessionRegistry.canLootExclusive(bot, drop)) {
            return false;
        }

        if (itemId == AgentLmpqDefinition.COUPON
                && !AgentLmpqSessionRegistry.canLootCoupon(bot)) return false;

        if (AgentOpqDefinition.EXCLUSIVE_ITEMS.contains(itemId)
                && !AgentOpqSessionRegistry.canLootExclusive(bot, itemId)) return false;

        if ((AgentEpqDefinition.EXCLUSIVE_ITEMS.contains(itemId) || itemId == AgentEpqDefinition.GOLEMS_MARBLE)
                && !AgentEpqSessionRegistry.canLootExclusive(bot, drop)) return false;

        if (AgentPpqDefinition.EXCLUSIVE_ITEMS.contains(itemId)
                && !AgentPpqSessionRegistry.canLootExclusive(bot, drop)) return false;

        // slot: rjpq
        if ((AgentRjpqDefinition.EXCLUSIVE_ITEM_IDS.contains(itemId)
                || AgentRjpqSessionRegistry.forMember(bot.getId()) != null)
                && !AgentRjpqSessionRegistry.canLoot(bot, drop)) return false;
        int kpqCouponTarget = AgentPqRuntime.kpqCouponTarget(entry);
        if (itemId == KPQ_COUPON && (AgentPartyQuestHooks.shouldSkipCouponLoot(entry)
                || (kpqCouponTarget > 0 && bot.getItemQuantity(KPQ_COUPON, false) >= kpqCouponTarget))) {
            return false;
        }
        if (itemId > 0 && !bot.needQuestItem(drop.getQuest(), itemId)) {
            return false;
        }
        return AgentLootPolicy.permits(bot, drop); // the host's per-Agent policy, after every rule above
    }

    /** A meso drop fills no bag; an item needs a free slot in the bag of its kind. */
    private static boolean hasRoomFor(Character bot, MapItem drop) {
        int itemId = drop.getItemId();
        if (drop.getMeso() <= 0 && itemId > 0) {
            InventoryType type = ItemConstants.getInventoryType(itemId);
            Inventory inv = bot.getInventory(type);
            return inv == null || !inv.isFull();
        }
        return true;
    }

    public static boolean canBotTargetLoot(AgentRuntimeEntry entry, Character bot, MapleMap map, MapItem drop, long now) {
        return canBotTargetLoot(entry, bot, map, drop, now, MIN_TARGET_LOOT_AGE_MS);
    }

    public static boolean canBotTargetLoot(AgentRuntimeEntry entry,
                                           Character bot,
                                           MapleMap map,
                                           MapItem drop,
                                           long now,
                                           long minimumTargetAgeMs) {
        return isPresent(map, drop)
                && canBotLoot(entry, bot, drop)
                && now - drop.getDropTime() >= requiredTargetLootAgeMs(bot, drop, minimumTargetAgeMs);
    }

    public static boolean isWaitingForTargetAge(AgentRuntimeEntry entry,
                                                Character bot,
                                                MapleMap map,
                                                MapItem drop,
                                                long now,
                                                long minimumTargetAgeMs) {
        return isPresent(map, drop)
                && canBotLoot(entry, bot, drop)
                && now - drop.getDropTime() < requiredTargetLootAgeMs(bot, drop, minimumTargetAgeMs);
    }

    static boolean isInventoryFull(Character bot, MapItem drop) {
        if (bot == null || drop == null || drop.getMeso() > 0 || drop.getItemId() <= 0) {
            return false;
        }
        Inventory inventory = bot.getInventory(ItemConstants.getInventoryType(drop.getItemId()));
        return inventory != null && inventory.isFull();
    }

    /**
     * True when the Agent leaves this drop only because its bag for the drop's kind has no free slot: every other
     * rule of {@link #canBotLoot}, its host's loot policy and Balrog's reward grace among them, would let it pick the
     * drop up (what AgentBagFullSpeech may mention; the loot rules themselves are unchanged).
     */
    static boolean leftForFullBag(AgentRuntimeEntry entry, Character bot, MapItem drop) {
        return isInventoryFull(bot, drop)
                && rulesAllow(entry, bot, drop)
                && AgentEasyBalrogRewardGracePolicy.permitsAgentLoot(bot, drop, System.currentTimeMillis());
    }

    static long requiredTargetLootAgeMs(Character bot, MapItem drop) {
        return requiredTargetLootAgeMs(bot, drop, MIN_TARGET_LOOT_AGE_MS);
    }

    static long requiredTargetLootAgeMs(Character bot, MapItem drop, long minimumTargetAgeMs) {
        if (bot == null || drop == null) {
            return MIN_TARGET_LOOT_AGE_MS;
        }
        if (isBotInventoryDrop(drop)) {
            return BOT_INVENTORY_DROP_TARGET_LOOT_AGE_MS;
        }
        long ordinaryMinimum = Math.max(SERVER_PICKUP_MIN_AGE_MS, minimumTargetAgeMs);
        return AgentYetiClassBoxPriorityPolicy.minimumTargetAgeMs(
                bot, drop, ordinaryMinimum);
    }

    private static boolean isBotInventoryDrop(MapItem drop) {
        int ownerId = drop.getOwnerId();
        return drop.isPlayerDrop()
                && ownerId > 0
                && AgentSessionLifecycleRuntime.activeLeaderByAgentCharacterId(ownerId) != null;
    }
}
