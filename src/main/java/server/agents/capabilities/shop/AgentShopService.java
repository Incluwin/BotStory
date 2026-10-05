package server.agents.capabilities.shop;

import server.agents.capabilities.movement.AgentMovementTimers;

import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentBuffStateRuntime;
import server.agents.capabilities.combat.AgentCombatAmmoCounter;

import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.shop.AgentShopAmmoPolicy;
import server.agents.capabilities.shop.AgentShopApproachPolicy;
import server.agents.capabilities.shop.AgentShopPotionPolicy;
import server.agents.capabilities.supplies.AgentBuffConsumableSupplyPolicy;
import server.agents.capabilities.supplies.AgentPotionInventoryPolicy;
import server.agents.capabilities.supplies.AgentPotionRecoveryPolicy;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSkillConsumablePolicy;
import server.agents.capabilities.supplies.AgentSupplyConfig;
import server.agents.capabilities.supplies.AgentSupplyReadiness;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.dialogue.AgentDialogueSelector;
import server.agents.capabilities.inventory.AgentInventoryItemPolicy;
import server.agents.capabilities.inventory.AgentInventorySellTrashService;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.inventory.demand.AgentEtcSaleCandidate;
import server.agents.capabilities.inventory.demand.AgentQuestItemDispositionRuntime;
import server.agents.capabilities.movement.AgentMovementStateResetService;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.progression.AgentPotionSupplierCatalog;
import server.agents.capabilities.shop.AgentShopRuntime;
import server.agents.capabilities.supplies.AgentCombatAmmoCheckRuntime;
import server.agents.runtime.AgentRandom;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.ItemInformationProvider;
import server.Shop;
import server.ShopItem;
import server.StatEffect;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationPathService;
import server.agents.capabilities.navigation.AgentNavigationRegionService;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapObjectType;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntToDoubleFunction;

public final class AgentShopService {
    private static final Logger log = LoggerFactory.getLogger(AgentShopService.class);

    private static final int SHOP_MANHATTAN_RADIUS = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.SHOP_MANHATTAN_RADIUS");
    private static final int SHOP_ARRIVE_DIST = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.SHOP_ARRIVE_DIST");
    private static final int SHOP_TRANSACTION_RADIUS = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.SHOP_TRANSACTION_RADIUS");
    private static final int SHOP_NPC_SEARCH_DIST = config.AgentTuning.intValue(
            "server.agents.capabilities.shop.AgentShopService.SHOP_NPC_SEARCH_DIST");
    private static final int SHOP_APPROACH_DELAY_MAX_MS = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.SHOP_APPROACH_DELAY_MAX_MS");
    private static final int BUFF_POTION_SPEND_PERCENT = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.BUFF_POTION_SPEND_PERCENT");
    private static final int SHOP_STEP_DELAY_MIN_MS = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.SHOP_STEP_DELAY_MIN_MS");
    private static final int SHOP_STEP_DELAY_MAX_MS = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.SHOP_STEP_DELAY_MAX_MS");
    private static final int SELL_TRASH_STEP_DELAY_MS = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.SELL_TRASH_STEP_DELAY_MS");
    private static final long SHOP_VISIT_TIMEOUT_MS = config.AgentTuning.longValue("server.agents.capabilities.shop.AgentShopService.SHOP_VISIT_TIMEOUT_MS");
    private static final long SHOP_SEQUENCE_TIMEOUT_MS = config.AgentTuning.longValue("server.agents.capabilities.shop.AgentShopService.SHOP_SEQUENCE_TIMEOUT_MS");
    private static final long SHOP_SEQUENCE_SALE_ALLOWANCE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.shop.AgentShopService.SHOP_SEQUENCE_SALE_ALLOWANCE_MS");
    private static final long SHOP_STUCK_FALLBACK_MS = config.AgentTuning.longValue("server.agents.capabilities.shop.AgentShopService.SHOP_STUCK_FALLBACK_MS");
    private static final int SHOP_STUCK_MOVE_TOLERANCE_PX = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.SHOP_STUCK_MOVE_TOLERANCE_PX");
    private static final int AMMO_TRIGGER_THRESHOLD = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.AMMO_TRIGGER_THRESHOLD");
    private static final int AMMO_TARGET_THRESHOLD = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.AMMO_TARGET_THRESHOLD"); // full target when buying at shop
    private static final int RECHARGE_MAX_SETS = config.AgentTuning.intValue("server.agents.capabilities.shop.AgentShopService.RECHARGE_MAX_SETS"); // cap recharge to the best N own-type stacks

    private AgentShopService() {}

    private record NpcShopMatch(NPC npc, Shop shop, Point npcPos) {}

    private record ShopSlotItem(
            short slot,
            ShopItem shopItem,
            AgentPotionRecoveryPolicy.Recovery recovery) {}

    /**
     * What a sell-trash pass got rid of: trash equips one at a time, surplus ETC a stack (sale line) at a time.
     * Kept apart so the Agent never reports ETC pieces as equips.
     */
    private record SaleTally(int equipsSold, int equipsFailed, int etcStacksSold, int etcStacksFailed) {
        SaleTally withEtcSale(boolean sold) {
            return sold
                    ? new SaleTally(equipsSold, equipsFailed, etcStacksSold + 1, etcStacksFailed)
                    : new SaleTally(equipsSold, equipsFailed, etcStacksSold, etcStacksFailed + 1);
        }

        boolean anySold() {
            return equipsSold > 0 || etcStacksSold > 0;
        }

        boolean anyFailed() {
            return equipsFailed > 0 || etcStacksFailed > 0;
        }
    }

    public static void onMapChange(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory) {
        clearShopState(entry);
        if (bot == null || bot.getMap() == null) {
            return;
        }

        NpcShopMatch match = findBestShop(bot, false, inventory);
        if (match == null) {
            return;
        }

        WeaponType wt = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        boolean needsRecharge = needsRechargeForShop(bot, wt, ammoTriggerThreshold(), inventory);
        boolean needsSeedRechargeAmmo = needsSeedRechargeAmmoForShop(bot, match.shop, wt);
        boolean needsAmmoForShop = needsFixedAmmoForShop(bot, match.shop, wt, ammoTriggerThreshold());
        boolean needsHpPots = needsPotionStock(bot, match.shop, true);
        boolean needsMpPots = needsPotionStock(bot, match.shop, false);
        if (!needsRecharge && !needsSeedRechargeAmmo && !needsAmmoForShop
                && !needsHpPots && !needsMpPots) {
            return;
        }

        long distSq = (long) bot.getPosition().distanceSq(match.npcPos);
        if (distSq > 1000L * 1000L) {
            AgentShopRuntime.sayMapNow(bot, AgentDialogueSelector.randomReply(AgentDialogueCatalog.shopResupplyReplies()));
        }

        startShopVisit(entry, bot, match);
    }

    /** Starts the planned MVP shop visit even when current supplies already satisfy restock thresholds. */
    public static boolean requestVisitAtNpc(AgentRuntimeEntry entry, Character bot, int npcId) {
        return requestVisitAtNpc(entry, bot, npcId, 0);
    }

    /**
     * Starts a planned shop visit while preserving the supplied meso floor for later journey costs.
     */
    public static boolean requestVisitAtNpc(
            AgentRuntimeEntry entry, Character bot, int npcId, int minimumMesoReserve) {
        return requestVisitAtNpc(entry, bot, npcId, minimumMesoReserve, 0, 0);
    }

    public static boolean requestVisitAtNpc(
            AgentRuntimeEntry entry,
            Character bot,
            int npcId,
            int minimumMesoReserve,
            int requiredItemId,
            int requiredItemCount) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return false;
        }
        NpcShopMatch match = findShopForNpc(bot, npcId);
        if (match == null) {
            return false;
        }
        startShopVisit(entry, bot, match, minimumMesoReserve, requiredItemId, requiredItemCount);
        return true;
    }

    public static void requestSellTrashVisit(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return;
        }
        if (AgentInventorySellTrashService.collectSellTrashEquips(entry, bot, inventory).isEmpty()) {
            AgentShopRuntime.replyNow(entry, AgentDialogueCatalog.shopNoTrashEquipsReply());
            return;
        }

        AgentShopStateRuntime.setShopSellTrashPending(entry, true);
        if (AgentShopStateRuntime.shopVisitPending(entry)) {
            return;
        }

        NpcShopMatch match = findBestShop(bot, true, inventory);
        if (match == null) {
            AgentShopStateRuntime.setShopSellTrashPending(entry, false);
            AgentShopRuntime.replyNow(entry, AgentDialogueCatalog.shopNotFoundReply());
            return;
        }

        AgentShopRuntime.replyNow(entry, AgentDialogueCatalog.shopSellTrashStartReply());
        startShopVisit(entry, bot, match);
    }

    private static void startShopVisit(AgentRuntimeEntry entry, Character bot, NpcShopMatch match) {
        startShopVisit(entry, bot, match, 0);
    }

    private static void startShopVisit(
            AgentRuntimeEntry entry, Character bot, NpcShopMatch match, int minimumMesoReserve) {
        startShopVisit(entry, bot, match, minimumMesoReserve, 0, 0);
    }

    private static void startShopVisit(
            AgentRuntimeEntry entry,
            Character bot,
            NpcShopMatch match,
            int minimumMesoReserve,
            int requiredItemId,
            int requiredItemCount) {
        AgentShopStateRuntime.startShopVisit(
                entry,
                match.npcPos,
                pickShopApproachPoint(match.npcPos, entry, bot),
                (int) AgentRandom.randMs(0, SHOP_APPROACH_DELAY_MAX_MS),
                minimumMesoReserve,
                requiredItemId,
                requiredItemCount,
                System.currentTimeMillis());
    }

    public static boolean tickShopVisit(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory) {
        return tickShopVisit(entry, bot, inventory, System.currentTimeMillis());
    }

    static boolean tickShopVisit(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory, long now) {
        if (!AgentShopStateRuntime.shopVisitPending(entry)) {
            return false;
        }
        if (!AgentShopStateRuntime.hasShopNpcPosition(entry)) {
            abortShop(entry, bot, AgentDialogueCatalog.shopLostReply());
            return false;
        }
        if (AgentShopStateRuntime.visitTimedOut(entry, now, SHOP_VISIT_TIMEOUT_MS)) {
            AgentShopRuntime.sayMapNow(bot, AgentDialogueCatalog.shopReachTimeoutReply());
            logShopFailure(entry, bot, "visit timeout (never reached the shop NPC)");
            clearShopState(entry);
            return false;
        }
        if (AgentShopStateRuntime.sequenceTimedOut(entry, now, SHOP_SEQUENCE_TIMEOUT_MS)) {
            abortShop(entry, bot, AgentDialogueCatalog.shopSequenceTimeoutReply());
            return false;
        }
        if (AgentShopStateRuntime.shopApproachDelayMs(entry) > 0) {
            AgentShopStateRuntime.setShopApproachDelayMs(
                    entry,
                    AgentMovementTimers.tickDown(AgentShopStateRuntime.shopApproachDelayMs(entry)));
            return false;
        }

        Point botPos = bot.getPosition();
        Point target = AgentShopStateRuntime.shopTargetOrNpcPosition(entry);
        boolean reachedApproach = manhattan(botPos, target) <= SHOP_ARRIVE_DIST;
        boolean stuckAtNpc = !AgentShopStateRuntime.shopSequenceActive(entry)
                && !reachedApproach
                && isStuckNearNpc(entry, botPos, now);
        if (reachedApproach || stuckAtNpc) {
            if (!AgentShopStateRuntime.shopSequenceActive(entry)) {
                AgentShopStateRuntime.markShopSequenceActive(entry, now);
                // Close enough to trade: drop any committed navigation step so the Agent
                // stands still for the transaction instead of chasing the exact approach point.
                AgentMovementStateResetService.clearNavigationState(entry);
                AgentShopRuntime.sayMapNow(bot, AgentDialogueSelector.randomReply(AgentDialogueCatalog.shoppingReplies()));
                Point npcPos = AgentShopStateRuntime.shopNpcPosition(entry);
                scheduleShopStep(entry, () -> executePurchases(entry, bot, inventory, npcPos));
            }
            return true;
        }

        return true;
    }

    private static boolean isStuckNearNpc(AgentRuntimeEntry entry, Point botPos, long now) {
        return AgentShopStateRuntime.stuckNearNpc(
                entry, botPos, now, SHOP_STUCK_FALLBACK_MS, SHOP_STUCK_MOVE_TOLERANCE_PX, SHOP_ARRIVE_DIST);
    }

    private static int manhattan(Point a, Point b) {
        return AgentShopApproachPolicy.manhattan(a, b);
    }

    private static NpcShopMatch findBestShop(Character bot, boolean allowAnyShop, InventoryGateway inventory) {
        List<MapObject> objects = bot.getMap().getMapObjectsInRange(
                new Point(0, 0), Double.POSITIVE_INFINITY,
                Arrays.asList(MapObjectType.NPC));

        for (MapObject obj : objects) {
            NPC npc = (NPC) obj;
            if (!npc.hasShop()) {
                continue;
            }
            Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npc.getId());
            if (shop == null) {
                continue;
            }
            if (allowAnyShop || shopHasAnythingNeeded(bot, shop, inventory)) {
                return new NpcShopMatch(npc, shop, npc.getPosition());
            }
        }
        return null;
    }

    private static NpcShopMatch findShopForNpc(Character bot, int npcId) {
        for (MapObject obj : bot.getMap().getMapObjectsInRange(
                new Point(0, 0), Double.POSITIVE_INFINITY, List.of(MapObjectType.NPC))) {
            NPC npc = (NPC) obj;
            if (npc.getId() != npcId || !npc.hasShop()) {
                continue;
            }
            Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npc.getId());
            if (shop != null) {
                return new NpcShopMatch(npc, shop, npc.getPosition());
            }
        }
        return null;
    }

    private static boolean shopHasAnythingNeeded(Character bot, Shop shop, InventoryGateway inventory) {
        WeaponType wt = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        boolean ammoHere = AgentPotionSupplierCatalog.ammoTopUpAllowedAt(bot.getMapId());
        if (ammoHere && needsFixedAmmoForShop(bot, shop, wt, ammoTriggerThreshold())) {
            return true;
        }
        if (ammoHere && needsRechargeForShop(bot, wt, ammoTriggerThreshold(), inventory)) {
            return true;
        }
        if (ammoHere && needsSeedRechargeAmmoForShop(bot, shop, wt)) {
            return true;
        }
        if (needsPotionStock(bot, shop, true)) {
            return true;
        }
        if (needsPotionStock(bot, shop, false)) {
            return true;
        }
        return needsSkillConsumables(bot, shop);
    }

    /** Summoning/Magic Rocks for Shadow Partner, Mystic Door and friends, when this shop stocks them. */
    private static boolean needsSkillConsumables(Character bot, Shop shop) {
        for (Map.Entry<Integer, Integer> target : AgentSkillConsumablePolicy.targets(bot).entrySet()) {
            if (AgentSkillConsumablePolicy.needsRestock(bot, target.getKey(), target.getValue())
                    && findShopItem(shop, target.getKey()) != null) {
                return true;
            }
        }
        return false;
    }

    private static AgentShopPurchaseSequence<AgentRuntimeEntry> buySkillConsumables(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop, int minimumMesoReserve) {
        Character bot = sequence.bot();
        for (Map.Entry<Integer, Integer> target : AgentSkillConsumablePolicy.targets(bot).entrySet()) {
            int missing = AgentSkillConsumablePolicy.shortfall(bot, target.getKey(), target.getValue());
            ShopSlotItem item = findShopItem(shop, target.getKey());
            if (missing <= 0 || item == null) {
                continue;
            }
            sequence = appendBuyReport(sequence,
                    buyFixedCostItem(bot, shop, item, missing, Math.max(1, missing), minimumMesoReserve),
                    "skill consumable");
        }
        return sequence;
    }

    /**
     * Accuracy and attack potions for the Agent's job, topped up only on a visit that happens anyway.
     * They are optional stock: each is capped at a share of the spare mesos, and a capped or failed
     * purchase never counts as the visit's shortfall.
     */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> buyBuffConsumables(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop, int minimumMesoReserve) {
        Character bot = sequence.bot();
        Map<Integer, Integer> targets = AgentBuffConsumableSupplyPolicy.targets(
                bot, AgentBuffStateRuntime.enabled(sequence.entry()));
        for (Map.Entry<Integer, Integer> target : targets.entrySet()) {
            int missing = AgentSkillConsumablePolicy.shortfall(bot, target.getKey(), target.getValue());
            ShopSlotItem item = findShopItem(shop, target.getKey());
            if (missing <= 0 || item == null) {
                continue;
            }
            AgentShopBuyReport report = buyFixedCostItem(bot, shop, item, missing, missing, minimumMesoReserve,
                    buffPotionBudget(bot.getMeso(), minimumMesoReserve));
            if (report.quantity() > 0) {
                sequence.bought().add(report.quantity() + " "
                        + resolveItemName(report.itemId(), "buff potion", sequence.inventory()));
            }
        }
        return sequence;
    }

    static int buffPotionBudget(int mesos, int minimumMesoReserve) {
        long spare = Math.max(0, mesos - Math.max(0, minimumMesoReserve));
        return (int) (spare * BUFF_POTION_SPEND_PERCENT / 100);
    }

    private static void executePurchases(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory, Point npcPos) {
        if (!isShopSequenceValid(entry, bot, npcPos)) {
            abortShop(entry, bot, AgentDialogueCatalog.shopKeeperUnreachableReply());
            return;
        }

        if (AgentShopStateRuntime.shopSellTrashPending(entry)) {
            startSellTrashSequence(new AgentShopPurchaseSequence<>(
                    entry, bot, inventory, npcPos, List.of(), new ArrayList<>(), null),
                    () -> executePurchases(entry, bot, inventory, npcPos));
            return;
        }

        WeaponType wt = AgentAttackExecutionProvider.getEquippedWeaponType(bot);
        int minimumMesoReserve = AgentShopStateRuntime.minimumMesoReserve(entry);
        List<AgentShopPurchaseAction<AgentRuntimeEntry>> actions = new ArrayList<>();

        int requiredItemId = AgentShopStateRuntime.requiredItemId(entry);
        int requiredItemCount = AgentShopStateRuntime.requiredItemCount(entry);
        boolean itemOnlyVisit = requiredItemId > 0 && requiredItemCount > 0;
        if (itemOnlyVisit) {
            actions.add((sequence, shop) -> {
                int missing = Math.max(0, requiredItemCount
                        - bot.getInventory(ItemConstants.getInventoryType(requiredItemId))
                        .countById(requiredItemId));
                if (missing > 0) {
                    AgentShopRuntime.sayMapNow(
                            bot,
                            AgentDialogueCatalog.shopPurchaseIntent(
                                    missing,
                                    resolveItemName(requiredItemId, "travel item", inventory),
                                    null));
                }
                return appendBuyReport(sequence,
                        buyFixedCostItem(bot, shop, findShopItem(shop, requiredItemId),
                                missing, Math.max(1, missing), 0),
                        "required travel item");
            });
        }

        // Projectiles only at Victoria shops, as before town shops elsewhere were known (AgentPotionSupplierCatalog).
        boolean ammoHere = AgentPotionSupplierCatalog.ammoTopUpAllowedAt(bot.getMapId());
        if (!itemOnlyVisit && ammoHere
                && shouldBuySeedRechargeAmmoWhileShopping(bot, wt)) {
            actions.add((sequence, shop) -> appendBuyReport(
                    sequence, buySeedRechargeAmmo(bot, shop, wt, minimumMesoReserve),
                    wt == WeaponType.GUN ? "bullet set" : "throwing-star set"));
        }
        if (!itemOnlyVisit && ammoHere
                && shouldRechargeWhileShopping(bot, wt, inventory)) {
            actions.add((sequence, shop) -> {
                AgentShopBuyReport recharge = doRecharge(
                        bot, shop, wt, sequence.inventory(), minimumMesoReserve);
                if (recharge.quantity() > 0) {
                    int recharged = recharge.quantity();
                    String ammoName = wt == WeaponType.GUN ? "bullets" : "throwing stars";
                    sequence.bought().add("refilled " + recharged + " set"
                            + (recharged > 1 ? "s" : "") + " of my " + ammoName);
                }
                return sequence.withFirstShortfall(recharge);
            });
        }
        if (!itemOnlyVisit && ammoHere && shouldBuyFixedAmmoWhileShopping(bot, wt)) {
            actions.add((sequence, shop) -> appendBuyReport(
                    sequence, buyAmmo(bot, shop, wt, minimumMesoReserve), "ammo"));
        }
        if (!itemOnlyVisit) {
            actions.add((sequence, shop) ->
                    buyPotionStock(sequence, shop, minimumMesoReserve));
            actions.add((sequence, shop) ->
                    buySkillConsumables(sequence, shop, minimumMesoReserve));
            actions.add((sequence, shop) ->
                    buyBuffConsumables(sequence, shop, minimumMesoReserve));
        }

        runPurchaseStep(new AgentShopPurchaseSequence<>(entry, bot, inventory, npcPos, actions, new ArrayList<>(), null), 0);
    }

    private static void runPurchaseStep(AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, int index) {
        if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
            abortShop(sequence.entry(), sequence.bot(), AgentDialogueCatalog.shopBuyInterruptedReply());
            return;
        }
        if (index >= sequence.actions().size()) {
            finishPurchaseSequence(sequence, true);
            return;
        }

        NPC npc = findNpcNear(sequence.bot(), sequence.npcPos());
        if (npc == null) {
            abortShop(sequence.entry(), sequence.bot(), AgentDialogueCatalog.shopKeeperGoneBuyReply());
            return;
        }
        Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npc.getId());
        if (shop == null) {
            abortShop(sequence.entry(), sequence.bot(), AgentDialogueCatalog.shopClosedBuyReply());
            return;
        }

        AgentShopPurchaseSequence<AgentRuntimeEntry> next = sequence.actions().get(index).run(sequence, shop);
        scheduleShopStep(sequence.entry(), () -> runPurchaseStep(next, index + 1));
    }

    private static void finishPurchaseSequence(AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, boolean announceIfEmpty) {
        if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
            abortShop(sequence.entry(), sequence.bot(), AgentDialogueCatalog.shopFinishFailedReply());
            return;
        }

        Runnable finish = () -> {
            if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
                abortShop(sequence.entry(), sequence.bot(), AgentDialogueCatalog.shopFinishFailedReply());
                return;
            }
            if (sequence.firstShortfall() != null) {
                AgentShopRuntime.sayMapNow(sequence.bot(), buildShortfallMessage(sequence.firstShortfall(), sequence.inventory()));
            } else if (announceIfEmpty && sequence.bought().isEmpty()) {
                // Never end a resupply visit silently: nothing was bought and nothing fell short.
                AgentShopRuntime.sayMapNow(sequence.bot(), AgentDialogueCatalog.shopEmptyResupplyReply());
            }
            AgentShopStateRuntime.completeWorkflow(sequence.entry(), "shop transaction reconciled",
                    System.currentTimeMillis());
            clearShopState(sequence.entry());
        };

        if (!sequence.bought().isEmpty()) {
            AgentShopRuntime.sayMapNow(sequence.bot(), AgentDialogueCatalog.shopBoughtReply(sequence.bought()));
            AgentPotionService.setupAutopotForBot(sequence.bot());
            AgentCombatAmmoCheckRuntime.tickAmmoCheck(sequence.entry(), sequence.bot(),
                    AgentSupplyConfig.cfg.AMMO_LOW_WARN, AgentRuntimeConfig.cfg.POT_LOW_WARN);
            scheduleShopStep(sequence.entry(), finish);
            return;
        }

        finish.run();
    }

    private static void startSellTrashSequence(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Runnable afterSale) {
        List<Item> items = AgentInventorySellTrashService.collectSellTrashEquips(
                sequence.entry(), sequence.bot(), sequence.inventory());
        List<AgentEtcSaleCandidate> etcPlan =
                AgentQuestItemDispositionRuntime.collectShopSaleCandidates(
                        sequence.entry(), sequence.bot(), sequence.inventory(),
                        System.currentTimeMillis());
        if (items.isEmpty() && etcPlan.isEmpty()) {
            AgentShopStateRuntime.setShopSellTrashPending(sequence.entry(), false);
            AgentShopRuntime.sayMapNow(sequence.bot(), AgentDialogueCatalog.shopNoTrashEquipsReply());
            afterSale.run();
            return;
        }
        // The sequence timeout budgets the purchases; each planned sale is one more step ahead of them,
        // so a full bag of trash must not eat the time the restock needs.
        AgentShopStateRuntime.extendShopSequence(sequence.entry(),
                (long) (items.size() + etcPlan.size()) * SHOP_SEQUENCE_SALE_ALLOWANCE_MS);

        if (items.isEmpty()) {
            scheduleShopStep(sequence.entry(), SELL_TRASH_STEP_DELAY_MS,
                    () -> runEtcSaleStep(
                             sequence.entry(), sequence.bot(), sequence.npcPos(),
                             new SaleTally(0, 0, 0, 0), 0, etcPlan, sequence.bought(), sequence.firstShortfall(),
                             sequence.inventory(), afterSale));
            return;
        }

        List<Item> plan = List.copyOf(items);
        scheduleShopStep(sequence.entry(), SELL_TRASH_STEP_DELAY_MS,
                () -> runSellTrashStep(
                        sequence.entry(),
                        sequence.bot(),
                        sequence.npcPos(),
                        0,
                        Collections.newSetFromMap(new IdentityHashMap<>()),
                        plan,
                        etcPlan,
                         sequence.bought(),
                         sequence.firstShortfall(),
                         sequence.inventory(),
                         afterSale));
    }

    private static void runSellTrashStep(
            AgentRuntimeEntry entry,
            Character bot,
            Point npcPos,
            int equipsSold,
            Set<Item> failedItems,
            List<Item> plan,
            List<AgentEtcSaleCandidate> etcPlan,
            List<String> bought,
            AgentShopBuyReport firstShortfall,
            InventoryGateway inventory,
            Runnable afterSale) {
        if (!isShopSequenceValid(entry, bot, npcPos)) {
            abortShop(entry, bot, AgentDialogueCatalog.shopSellInterruptedReply());
            return;
        }

        List<Item> items = plan.stream()
                .filter(item -> AgentInventoryItemPolicy.hasItem(bot, item))
                .filter(item -> !failedItems.contains(item))
                .filter(item -> AgentInventoryReservationRuntime.mayConsume(
                        entry, item, System.currentTimeMillis()))
                .toList();
        if (items.isEmpty()) {
            SaleTally tally = new SaleTally(equipsSold, failedItems.size(), 0, 0);
            if (!etcPlan.isEmpty()) {
                scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                        () -> runEtcSaleStep(entry, bot, npcPos, tally, 0, etcPlan, bought,
                                firstShortfall, inventory, afterSale));
                return;
            }
            finishSellTrashSequence(entry, bot, inventory, npcPos, tally, bought, firstShortfall, afterSale);
            return;
        }

        Item item = items.get(0);
        if (!AgentInventoryReservationRuntime.mayConsume(entry, item, System.currentTimeMillis())) {
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runSellTrashStep(entry, bot, npcPos, equipsSold, failedItems,
                            plan, etcPlan, bought, firstShortfall, inventory, afterSale));
            return;
        }
        if (!AgentInventoryItemPolicy.hasItem(bot, item)) {
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runSellTrashStep(entry, bot, npcPos, equipsSold, failedItems,
                            plan, etcPlan, bought, firstShortfall, inventory, afterSale));
            return;
        }

        NPC npc = findNpcNear(bot, npcPos);
        if (npc == null) {
            abortShop(entry, bot, AgentDialogueCatalog.shopKeeperGoneSellReply());
            return;
        }
        Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npc.getId());
        if (shop == null) {
            abortShop(entry, bot, AgentDialogueCatalog.shopClosedSellReply());
            return;
        }

        AgentShopGatewayRuntime.shop().sell(bot, shop, InventoryType.EQUIP, item.getPosition(), (short) 1);
        if (AgentInventoryItemPolicy.hasItem(bot, item)) {
            failedItems.add(item);
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runSellTrashStep(entry, bot, npcPos, equipsSold, failedItems,
                            plan, etcPlan, bought, firstShortfall, inventory, afterSale));
            return;
        }

        int nextEquipsSold = equipsSold + 1;
        scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                () -> runSellTrashStep(entry, bot, npcPos, nextEquipsSold, failedItems,
                        plan, etcPlan, bought, firstShortfall, inventory, afterSale));
    }

    private static void runEtcSaleStep(
            AgentRuntimeEntry entry,
            Character bot,
            Point npcPos,
            SaleTally tally,
            int index,
            List<AgentEtcSaleCandidate> plan,
            List<String> bought,
            AgentShopBuyReport firstShortfall,
            InventoryGateway inventory,
            Runnable afterSale) {
        if (!isShopSequenceValid(entry, bot, npcPos)) {
            abortShop(entry, bot, AgentDialogueCatalog.shopSellInterruptedReply());
            return;
        }
        if (index >= plan.size()) {
            finishSellTrashSequence(entry, bot, inventory, npcPos, tally, bought, firstShortfall, afterSale);
            return;
        }

        AgentEtcSaleCandidate candidate = plan.get(index);
        Item item = candidate.item();
        int before = bot.getInventory(InventoryType.ETC).countById(item.getItemId());
        int reserved = AgentInventoryReservationRuntime.ledger(entry)
                .reservedQuantity(item.getItemId(), System.currentTimeMillis());
        int available = Math.max(0, before - reserved);
        int quantity = Math.min(candidate.quantity(),
                Math.min(item.getQuantity(), available));
        if (!AgentInventoryItemPolicy.hasItem(bot, item) || quantity <= 0) {
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runEtcSaleStep(entry, bot, npcPos, tally,
                            index + 1, plan, bought, firstShortfall, inventory, afterSale));
            return;
        }

        NPC npc = findNpcNear(bot, npcPos);
        if (npc == null) {
            abortShop(entry, bot, AgentDialogueCatalog.shopKeeperGoneSellReply());
            return;
        }
        Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npc.getId());
        if (shop == null) {
            abortShop(entry, bot, AgentDialogueCatalog.shopClosedSellReply());
            return;
        }

        AgentShopGatewayRuntime.shop().sell(
                bot, shop, InventoryType.ETC, item.getPosition(), (short) quantity);
        int after = bot.getInventory(InventoryType.ETC).countById(item.getItemId());
        // One sale line is one stack in the Agent's telling, however many pieces it moved.
        SaleTally next = tally.withEtcSale(after < before);
        scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                 () -> runEtcSaleStep(entry, bot, npcPos, next,
                         index + 1, plan, bought, firstShortfall, inventory, afterSale));
    }

    private static void finishSellTrashSequence(
            AgentRuntimeEntry entry,
            Character bot,
            InventoryGateway inventory,
            Point npcPos,
            SaleTally tally,
            List<String> bought,
            AgentShopBuyReport firstShortfall,
            Runnable afterSale) {
        AgentShopStateRuntime.setShopSellTrashPending(entry, false);
        if (tally.anySold()) {
            AgentShopRuntime.sayMapNow(bot,
                    AgentDialogueCatalog.shopSoldTrashReply(tally.equipsSold(), tally.etcStacksSold()));
        }
        if (tally.anyFailed()) {
            AgentShopRuntime.sayMapNow(bot,
                    AgentDialogueCatalog.shopSellTrashFailureReply(tally.equipsFailed(), tally.etcStacksFailed()));
        } else if (!tally.anySold()) {
            AgentShopRuntime.sayMapNow(bot, AgentDialogueCatalog.shopNoTrashEquipsReply());
        }
        afterSale.run();
    }

    private static AgentShopPurchaseSequence<AgentRuntimeEntry> appendBuyReport(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            AgentShopBuyReport report,
            String fallbackName) {
        if (report.quantity() > 0) {
            sequence.bought().add(report.quantity() + " " + resolveItemName(report.itemId(), fallbackName, sequence.inventory()));
        }
        return sequence.withFirstShortfall(report);
    }

    private static boolean needsAmmo(Character bot, WeaponType wt) {
        return AgentShopAmmoPolicy.needsFixedAmmoWeapon(wt);
    }

    private static int ammoTriggerThreshold() {
        return AgentShopAmmoPolicy.triggerThreshold(AgentSupplyConfig.cfg.AMMO_LOW_WARN, AMMO_TRIGGER_THRESHOLD);
    }

    private static int ammoTargetThreshold() {
        return AgentShopAmmoPolicy.targetThreshold(AgentSupplyConfig.cfg.AMMO_LOW_WARN, AMMO_TARGET_THRESHOLD);
    }

    private static boolean needsFixedAmmoForShop(Character bot, Shop shop, WeaponType wt, int threshold) {
        if (!needsAmmo(bot, wt) || AgentCombatAmmoCounter.countAmmo(bot, wt) >= threshold) {
            return false;
        }
        return shop == null || findAmmoItem(shop, wt) != null;
    }

    // True when the bot's BEST rechargeable ammo (highest-attack star/bullet matching the
    // weapon) is below the threshold AND has a partial stack that can actually be refilled.
    // Keying on the best item means a pile of weaker stars can't mask a depleted good stack,
    // and the partial-stack check stops pointless trips/silent no-ops when nothing is refillable.
    private static boolean needsRechargeForShop(Character bot, WeaponType wt, int threshold, InventoryGateway inventory) {
        return AgentShopAmmoPolicy.needsRecharge(
                bot.getInventory(InventoryType.USE).list(),
                wt,
                threshold,
                inventory::getProjectileWeaponAttack,
                itemId -> inventory.getSlotMax(bot, itemId));
    }

    static boolean shouldRechargeWhileShopping(Character bot, WeaponType wt, InventoryGateway inventory) {
        return needsRechargeForShop(bot, wt, ammoTriggerThreshold(), inventory);
    }

    static boolean shouldBuySeedRechargeAmmoWhileShopping(Character bot, WeaponType wt) {
        return AgentShopAmmoPolicy.needsSeedRechargeAmmo(
                wt, AgentCombatAmmoCounter.countAmmo(bot, wt));
    }

    static boolean shouldBuyFixedAmmoWhileShopping(Character bot, WeaponType wt) {
        return AgentShopAmmoPolicy.shouldBuyFixedAmmo(wt, AgentCombatAmmoCounter.countAmmo(bot, wt),
                ammoTargetThreshold());
    }

    private static boolean isRechargeWeaponType(WeaponType wt) {
        return AgentShopAmmoPolicy.isRechargeWeaponType(wt);
    }

    private static ShopSlotItem findAmmoItem(Shop shop, WeaponType wt) {
        List<ShopItem> items = shop.getItems();
        ShopSlotItem best = null;
        for (int i = 0; i < items.size(); i++) {
            ShopItem si = items.get(i);
            if (si.getPrice() <= 0) {
                continue;
            }
            int id = si.getItemId();
            boolean matches = switch (wt) {
                case BOW -> ItemConstants.isArrowForBow(id);
                case CROSSBOW -> ItemConstants.isArrowForCrossBow(id);
                default -> false;
            };
            if (matches && (best == null || si.getPrice() < best.shopItem.getPrice())) {
                best = new ShopSlotItem((short) i, si, null);
            }
        }
        return best;
    }

    private static ShopSlotItem findSeedRechargeAmmoItem(Shop shop, WeaponType wt) {
        List<ShopItem> items = shop.getItems();
        ShopSlotItem best = null;
        for (int i = 0; i < items.size(); i++) {
            ShopItem item = items.get(i);
            if (item.getPrice() <= 0
                    || !AgentShopAmmoPolicy.matchesRechargeWeapon(item.getItemId(), wt)) {
                continue;
            }
            if (best == null || item.getPrice() < best.shopItem.getPrice()) {
                best = new ShopSlotItem((short) i, item, null);
            }
        }
        return best;
    }

    private static boolean needsSeedRechargeAmmoForShop(
            Character bot, Shop shop, WeaponType wt) {
        return shouldBuySeedRechargeAmmoWhileShopping(bot, wt)
                && (shop == null || findSeedRechargeAmmoItem(shop, wt) != null);
    }

    static AgentShopBuyReport buySeedRechargeAmmo(
            Character bot, Shop shop, WeaponType wt, int minimumMesoReserve) {
        return buyFixedCostItem(bot, shop, findSeedRechargeAmmoItem(shop, wt),
                1, 1, minimumMesoReserve);
    }

    static AgentShopBuyReport buyAmmo(
            Character bot, Shop shop, WeaponType wt, int minimumMesoReserve) {
        ShopSlotItem ammo = findAmmoItem(shop, wt);
        if (ammo == null) {
            return new AgentShopBuyReport(0, 0, 0, AgentShopShortfallReason.NONE);
        }

        int target = ammoTargetThreshold();
        int current = AgentCombatAmmoCounter.countAmmo(bot, wt);
        return buyFixedCostItem(
                bot, shop, ammo, Math.max(0, target - current), 1000, minimumMesoReserve);
    }

    private static boolean matchesRechargeWeapon(int itemId, WeaponType wt) {
        return AgentShopAmmoPolicy.matchesRechargeWeapon(itemId, wt);
    }

    static AgentShopBuyReport doRecharge(
            Character bot,
            Shop shop,
            WeaponType wt,
            InventoryGateway inventory,
            int minimumMesoReserve) {
        // Only recharge ammo matching the equipped weapon (claw->stars, gun->bullets) and only
        // the best stacks by attack: recharging off-weapon or low-tier leftovers just wastes meso,
        // and an off-weapon failure must never short-circuit the real ammo refill.
        List<Item> refillable = refillableStacks(bot, wt, inventory);

        int recharged = 0;
        int attempted = 0;
        int shortfallItemId = 0;
        AgentShopShortfallReason reason = AgentShopShortfallReason.NONE;
        for (Item item : refillable) {
            if (recharged >= RECHARGE_MAX_SETS) {
                break;
            }
            Shop.TransactionResult result = AgentShopGatewayRuntime.shop().recharge(
                    bot, shop, item.getPosition(), minimumMesoReserve);
            if (result == Shop.TransactionResult.SUCCESS) {
                recharged++;
                attempted++;
                continue;
            }
            attempted++;
            shortfallItemId = item.getItemId();
            reason = switch (result) {
                case NOT_ENOUGH_MESO -> AgentShopShortfallReason.NO_MESO;
                case NO_SPACE -> AgentShopShortfallReason.NO_SPACE;
                default -> AgentShopShortfallReason.OTHER;
            };
            break;
        }
        return new AgentShopBuyReport(shortfallItemId, recharged, attempted, reason);
    }

    /** The weapon's own partial stacks a recharge refills, best attack first. */
    private static List<Item> refillableStacks(Character bot, WeaponType wt, InventoryGateway inventory) {
        List<Item> refillable = new ArrayList<>();
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            int id = item.getItemId();
            if (!ItemConstants.isRechargeable(id) || !matchesRechargeWeapon(id, wt)) {
                continue;
            }
            if (item.getQuantity() >= inventory.getSlotMax(bot, id)) {
                continue;
            }
            refillable.add(item);
        }
        refillable.sort((a, b) -> Integer.compare(
                inventory.getProjectileWeaponAttack(b.getItemId()), inventory.getProjectileWeaponAttack(a.getItemId())));
        return refillable;
    }

    private static boolean needsPotionStock(Character bot, Shop shop, boolean forHp) {
        ShopSlotItem potion = findPotionItem(shop, bot, forHp);
        if (potion == null) {
            return false;
        }
        return AgentPotionPurchasePolicy.belowReserve(
                potionRecoveryCapacity(bot, forHp),
                forHp ? bot.getCurrentMaxHp() : bot.getCurrentMaxMp(),
                AgentPotionPurchasePolicy.triggerReserveBars(forHp));
    }

    static AgentShopPurchaseSequence<AgentRuntimeEntry> buyPotionStock(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Shop shop,
            int minimumMesoReserve) {
        Character bot = sequence.bot();
        ShopSlotItem hpPotion = findPotionItem(shop, bot, true);
        ShopSlotItem mpPotion = findPotionItem(shop, bot, false);
        // A standing readiness duty first: potions by count up to the next activity's standard,
        // even when the reserve bars below are already met.
        int[] floorBought = {0, 0};
        AgentShopPurchaseSequence<AgentRuntimeEntry> current = buyReadinessFloor(
                sequence, shop, hpPotion, mpPotion, minimumMesoReserve, floorBought, false);
        boolean needsHp = needsPotionTarget(bot, hpPotion, true);
        boolean needsMp = needsPotionTarget(bot, mpPotion, false);

        if (needsHp) {
            String intent = potionPurchaseIntent(bot, hpPotion, true, sequence.inventory());
            if (intent != null) {
                AgentShopRuntime.sayMapNow(bot, intent);
            }
        }
        if (needsMp) {
            String intent = potionPurchaseIntent(bot, mpPotion, false, sequence.inventory());
            if (intent != null) {
                AgentShopRuntime.sayMapNow(bot, intent);
            }
        }

        int hpBought = floorBought[0];
        int mpBought = floorBought[1];

        AgentShopBuyReport report = buyPotionToReserve(
                bot,
                shop,
                hpPotion,
                true,
                AgentPotionPurchasePolicy.criticalReserveBars(true),
                hpBought,
                false,
                minimumMesoReserve,
                Integer.MAX_VALUE);
        hpBought += report.quantity();
        current = appendBuyReport(current, report, "critical HP pots");

        report = buyPotionToReserve(
                bot,
                shop,
                mpPotion,
                false,
                AgentPotionPurchasePolicy.criticalReserveBars(false),
                mpBought,
                false,
                minimumMesoReserve,
                Integer.MAX_VALUE);
        mpBought += report.quantity();
        current = appendBuyReport(current, report, "critical MP pots");

        boolean mpNeedsNormalStock = needsPotionTarget(bot, mpPotion, false);
        int spendable = Math.max(0, bot.getMeso() - Math.max(0, minimumMesoReserve));
        int hpBudget = AgentPotionPurchasePolicy.normalSpendBudget(
                spendable, mpNeedsNormalStock);
        report = buyPotionToReserve(
                bot,
                shop,
                hpPotion,
                true,
                AgentPotionPurchasePolicy.targetReserveBars(true),
                hpBought,
                true,
                minimumMesoReserve,
                hpBudget);
        hpBought += report.quantity();
        current = appendBuyReport(current, report, "HP pots");

        report = buyPotionToReserve(
                bot,
                shop,
                mpPotion,
                false,
                AgentPotionPurchasePolicy.targetReserveBars(false),
                mpBought,
                true,
                minimumMesoReserve,
                Integer.MAX_VALUE);
        mpBought += report.quantity();
        current = appendBuyReport(current, report, "MP pots");
        // Last, with what is left, a duty holder tops up toward its buy target (BUY_TO_PERCENT of the standard).
        return buyReadinessFloor(current, shop, hpPotion, mpPotion, minimumMesoReserve,
                new int[]{hpBought, mpBought}, true);
    }

    /**
     * The readiness floor of a standing duty (AgentSupplyReadiness), in two passes around the reserve-bar passes.
     * First ({@code topUp} false) each kind up to the activity's standard, by count: HP first, within the purse less
     * what the mana potions it lacks of the standard cost at their cheapest, then MP, so a purse that covers the
     * standard always reaches it. Last ({@code topUp} true), with what is left, toward the buy target
     * (AgentSupplyReadiness.buyTarget), again holding back what the other kind still wants, buying only what the
     * budget pays for. {@link #floorItem} picks the potion. Nothing without a duty; {@code bought} counts this visit's
     * purchases per kind (HP, MP) against the per-visit limit.
     */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> buyReadinessFloor(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Shop shop,
            ShopSlotItem hpPick,
            ShopSlotItem mpPick,
            int minimumMesoReserve,
            int[] bought,
            boolean topUp) {
        Character bot = sequence.bot();
        // the trip's own activity when the duty cannot be read at the counter (travel cleared its follow target)
        AgentSupplyReadiness.Activity activity = AgentSupplyReadiness.shoppingActivity(sequence.entry(), bot);
        if (activity == null) {
            return sequence;
        }
        AgentSupplyReadiness.Standard standard = AgentSupplyReadiness.standard(activity);
        int hpGoal = topUp ? AgentSupplyReadiness.buyTarget(activity, bot, true) : standard.hp();
        int mpGoal = !AgentSupplyReadiness.needsManaPotions(bot) ? 0
                : topUp ? AgentSupplyReadiness.buyTarget(activity, bot, false) : standard.mp();
        int[] carried = AgentPotionService.countPotions(bot);
        int hpWant = AgentPotionPurchasePolicy.floorQuantity(hpGoal, carried[0], bought[0]);
        int mpWant = AgentPotionPurchasePolicy.floorQuantity(mpGoal, carried[1], bought[1]);
        AgentShopPurchaseSequence<AgentRuntimeEntry> current = sequence;
        if (hpWant > 0) {
            long heldForMana = mpWant <= 0 ? 0L
                    : (long) mpWant * Math.max(0, cheapestRecoveryPrice(bot, shop, false));
            current = buyFloor(current, shop, hpPick, true, hpWant,
                    Math.max(0L, spendable(bot, minimumMesoReserve) - heldForMana), minimumMesoReserve, bought, topUp);
        }
        if (mpWant > 0) {
            current = buyFloor(current, shop, mpPick, false, mpWant, spendable(bot, minimumMesoReserve),
                    minimumMesoReserve, bought, topUp);
        }
        return current;
    }

    /**
     * One kind of the floor: {@code want} of the potion {@link #floorItem} picks within {@code budget}; a top-up buys
     * only what the budget pays for, so a short purse is no shortfall to report.
     */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> buyFloor(
            AgentShopPurchaseSequence<AgentRuntimeEntry> current,
            Shop shop,
            ShopSlotItem pick,
            boolean forHp,
            int want,
            long budget,
            int minimumMesoReserve,
            int[] bought,
            boolean topUp) {
        Character bot = current.bot();
        ShopSlotItem item = floorItem(shop, bot, pick, forHp, want, budget, current.inventory());
        if (item == null) {
            return current;
        }
        int quantity = want;
        if (topUp) {
            int price = item.shopItem.getPrice();
            quantity = price <= 0 ? 0 : (int) Math.min(want, budget / price);
            if (quantity <= 0) {
                return current;
            }
        }
        AgentShopBuyReport report = buyFixedCostItem(bot, shop, item, quantity, 100, minimumMesoReserve,
                (int) Math.min(Integer.MAX_VALUE, budget));
        bought[forHp ? 0 : 1] += report.quantity();
        return appendBuyReport(current, report, forHp ? "HP pots" : "MP pots");
    }

    /**
     * The potion a floor pass buys {@code want} of within {@code budget} (AgentShopPotionPolicy.selectForCount). With
     * at most one Use slot free, a new kind of potion would take the last slot (a party quest keeps one free for its
     * items and rewards), so it buys onto a stack of this kind it already carries, when this shop sells one.
     */
    private static ShopSlotItem floorItem(Shop shop, Character bot, ShopSlotItem pick, boolean forHp, int want,
                                          long budget, InventoryGateway inventory) {
        ShopSlotItem selected = selectForCount(shop, bot, pick, forHp, want, budget);
        if (selected == null || freeUseSlots(bot) > 1
                || stackRoom(bot, selected.shopItem.getItemId(), inventory) > 0) {
            return selected;
        }
        ShopSlotItem carried = carriedStack(shop, bot, forHp, inventory);
        return carried != null ? carried : selected;
    }

    /**
     * True when the bot carries a stack of an HP (or MP) recovery potion this shop sells that has room left, so a
     * purchase of it needs no new Use slot.
     */
    public static boolean carriesStackWithRoom(Character bot, Shop shop, boolean forHp) {
        return bot != null && shop != null
                && carriedStack(shop, bot, forHp, AgentInventoryGatewayRuntime.inventory()) != null;
    }

    /** The strongest HP (or MP) recovery potion this shop sells of which the bot carries a stack with room; or null. */
    private static ShopSlotItem carriedStack(Shop shop, Character bot, boolean forHp, InventoryGateway inventory) {
        if (bot.getInventory(InventoryType.USE) == null) {
            return null;
        }
        List<ShopItem> items = shop.getItems();
        ShopSlotItem best = null;
        for (int i = 0; i < items.size(); i++) {
            ShopItem item = items.get(i);
            if (item.getPrice() <= 0 || !AgentUseItemClassificationPolicy.isRecoveryPotion(item.getItemId())
                    || stackRoom(bot, item.getItemId(), inventory) <= 0) {
                continue;
            }
            StatEffect effect = AgentUseItemClassificationPolicy.itemEffect(item.getItemId());
            AgentPotionRecoveryPolicy.Recovery recovery = effect == null ? null : AgentPotionRecoveryPolicy.recovery(
                    effect, bot.getCurrentMaxHp(), bot.getCurrentMaxMp(), forHp);
            if (recovery != null && (best == null || recovery.primary() > best.recovery.primary())) {
                best = new ShopSlotItem((short) i, item, recovery);
            }
        }
        return best;
    }

    /** How many more of {@code itemId} its carried stacks of it take before it needs a new Use slot. */
    private static int stackRoom(Character bot, int itemId, InventoryGateway inventory) {
        if (bot.getInventory(InventoryType.USE) == null) {
            return 0;
        }
        int room = 0;
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (item.getItemId() == itemId) {
                room += Math.max(0, inventory.getSlotMax(bot, itemId) - item.getQuantity());
            }
        }
        return room;
    }

    private static int freeUseSlots(Character bot) {
        return bot.getInventory(InventoryType.USE) == null ? 0
                : Math.max(0, bot.getInventory(InventoryType.USE).getNumFreeSlot());
    }

    private static long spendable(Character bot, int minimumMesoReserve) {
        return Math.max(0L, (long) bot.getMeso() - Math.max(0, minimumMesoReserve));
    }

    private static ShopSlotItem selectForCount(
            Shop shop, Character bot, ShopSlotItem coveragePick, boolean forHp, int want, long budget) {
        AgentShopPotionPolicy.PotionShopSlot selected = AgentShopPotionPolicy.selectForCount(
                shop.getItems(),
                coveragePick == null ? null : new AgentShopPotionPolicy.PotionShopSlot(
                        coveragePick.slot, coveragePick.shopItem, coveragePick.recovery),
                want,
                budget,
                bot.getCurrentMaxHp(),
                bot.getCurrentMaxMp(),
                forHp,
                AgentUseItemClassificationPolicy::isRecoveryPotion,
                AgentUseItemClassificationPolicy::itemEffect);
        return selected == null ? null : new ShopSlotItem(selected.slot(), selected.shopItem(), selected.recovery());
    }

    /** The cheapest HP (or MP) recovery potion this shop sells, in mesos; -1 when it sells none. */
    public static int cheapestRecoveryPrice(Character bot, Shop shop, boolean forHp) {
        if (bot == null || shop == null) {
            return -1;
        }
        return AgentShopPotionPolicy.cheapestPrice(shop.getItems(), bot.getCurrentMaxHp(), bot.getCurrentMaxMp(),
                forHp, AgentUseItemClassificationPolicy::isRecoveryPotion, AgentUseItemClassificationPolicy::itemEffect);
    }

    /**
     * What a visit to {@code shop} would spend on ammo before any potion, as {@link #executePurchases} sizes it: a
     * seed set of stars or bullets when it has none, the refills of its best partial stacks, and arrows up to the
     * ammo target. 0 for a weapon without ammo.
     */
    public static int plannedAmmoSpend(Character bot, Shop shop) {
        if (bot == null || shop == null) {
            return 0;
        }
        long spend = plannedAmmoSpend(bot, shop, AgentAttackExecutionProvider.getEquippedWeaponType(bot),
                AgentInventoryGatewayRuntime.inventory(),
                itemId -> ItemInformationProvider.getInstance().getUnitPrice(itemId));
        return (int) Math.min(Integer.MAX_VALUE, spend);
    }

    static long plannedAmmoSpend(Character bot, Shop shop, WeaponType wt, InventoryGateway inventory,
                                 IntToDoubleFunction unitPrice) {
        if (wt == null || bot.getInventory(InventoryType.USE) == null) {
            return 0L;
        }
        long spend = 0L;
        if (shouldBuySeedRechargeAmmoWhileShopping(bot, wt)) {
            ShopSlotItem seed = findSeedRechargeAmmoItem(shop, wt);
            spend += seed == null ? 0L : seed.shopItem.getPrice();
        }
        if (shouldRechargeWhileShopping(bot, wt, inventory)) {
            int sets = 0;
            for (Item item : refillableStacks(bot, wt, inventory)) {
                if (sets++ >= RECHARGE_MAX_SETS) {
                    break;
                }
                int missing = inventory.getSlotMax(bot, item.getItemId()) - item.getQuantity();
                spend += (long) Math.ceil(unitPrice.applyAsDouble(item.getItemId()) * missing);
            }
        }
        if (shouldBuyFixedAmmoWhileShopping(bot, wt)) {
            ShopSlotItem ammo = findAmmoItem(shop, wt);
            int missing = Math.max(0, ammoTargetThreshold() - AgentCombatAmmoCounter.countAmmo(bot, wt));
            spend += ammo == null ? 0L : (long) ammo.shopItem.getPrice() * missing;
        }
        return spend;
    }

    private static AgentShopBuyReport buyPotionToReserve(
            Character bot,
            Shop shop,
            ShopSlotItem potion,
            boolean forHp,
            double reserveBars,
            int alreadyBoughtThisVisit,
            boolean applyMinimumPurchase,
            int minimumMesoReserve,
            int maximumSpend) {
        if (potion == null || potion.recovery == null) {
            return new AgentShopBuyReport(0, 0, 0, AgentShopShortfallReason.NONE);
        }
        int[] counts = AgentPotionService.countPotions(bot);
        int carriedQuantity = forHp ? counts[0] : counts[1];
        int quantity = AgentPotionPurchasePolicy.quantityToTarget(
                potionRecoveryCapacity(bot, forHp),
                carriedQuantity,
                potion.recovery.primary(),
                forHp ? bot.getCurrentMaxHp() : bot.getCurrentMaxMp(),
                reserveBars,
                alreadyBoughtThisVisit,
                applyMinimumPurchase);
        return buyFixedCostItem(
                bot,
                shop,
                potion,
                quantity,
                100,
                minimumMesoReserve,
                maximumSpend);
    }

    private static boolean needsPotionTarget(
            Character bot, ShopSlotItem potion, boolean forHp) {
        return potion != null
                && AgentPotionPurchasePolicy.belowReserve(
                        potionRecoveryCapacity(bot, forHp),
                        forHp ? bot.getCurrentMaxHp() : bot.getCurrentMaxMp(),
                        AgentPotionPurchasePolicy.targetReserveBars(forHp));
    }

    private static long potionRecoveryCapacity(Character bot, boolean forHp) {
        return AgentPotionInventoryPolicy.recoveryCapacity(
                bot.getInventory(InventoryType.USE).list(),
                AgentUseItemClassificationPolicy::itemEffect,
                bot.getCurrentMaxHp(),
                bot.getCurrentMaxMp(),
                forHp);
    }

    private static String potionPurchaseIntent(
            Character bot,
            ShopSlotItem potion,
            boolean forHp,
            InventoryGateway inventory) {
        int[] counts = AgentPotionService.countPotions(bot);
        int plannedQuantity = AgentPotionPurchasePolicy.quantityToTarget(
                potionRecoveryCapacity(bot, forHp),
                forHp ? counts[0] : counts[1],
                potion.recovery.primary(),
                forHp ? bot.getCurrentMaxHp() : bot.getCurrentMaxMp(),
                AgentPotionPurchasePolicy.targetReserveBars(forHp),
                0,
                true);
        return plannedQuantity <= 0
                ? null
                : AgentDialogueCatalog.shopPurchaseIntent(
                plannedQuantity,
                resolveItemName(potion.shopItem.getItemId(), "potions", inventory),
                forHp ? "HP" : "MP");
    }

    private static ShopSlotItem findPotionItem(Shop shop, Character bot, boolean forHp) {
        int maxHp = bot.getCurrentMaxHp();
        int maxMp = bot.getCurrentMaxMp();
        float threshold = forHp
                ? AgentPotionService.hpThreshold(bot)
                : AgentPotionService.mpThreshold(bot);
        int targetDeficit = Math.max(
                1,
                Math.round((forHp ? maxHp : maxMp) * (1.0f - threshold)));
        AgentShopPotionPolicy.PotionShopSlot selected = AgentShopPotionPolicy.selectPotionItem(
                shop.getItems(),
                maxHp,
                maxMp,
                targetDeficit,
                forHp,
                AgentUseItemClassificationPolicy::isRecoveryPotion,
                AgentUseItemClassificationPolicy::itemEffect);
        return selected == null
                ? null
                : new ShopSlotItem(selected.slot(), selected.shopItem(), selected.recovery());
    }

    private static ShopSlotItem findShopItem(Shop shop, int itemId) {
        List<ShopItem> items = shop.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getItemId() == itemId && items.get(i).getPrice() > 0) {
                return new ShopSlotItem((short) i, items.get(i), null);
            }
        }
        return null;
    }

    private static AgentShopBuyReport buyFixedCostItem(
            Character bot,
            Shop shop,
            ShopSlotItem item,
            int desiredQuantity,
            int batchSize,
            int minimumMesoReserve) {
        return buyFixedCostItem(
                bot,
                shop,
                item,
                desiredQuantity,
                batchSize,
                minimumMesoReserve,
                Integer.MAX_VALUE);
    }

    private static AgentShopBuyReport buyFixedCostItem(
            Character bot,
            Shop shop,
            ShopSlotItem item,
            int desiredQuantity,
            int batchSize,
            int minimumMesoReserve,
            int maximumSpend) {
        if (item == null || desiredQuantity <= 0) {
            return new AgentShopBuyReport(0, 0, 0, AgentShopShortfallReason.NONE);
        }

        int totalBought = 0;
        AgentShopShortfallReason reason = AgentShopShortfallReason.NONE;
        int price = item.shopItem.getPrice();

        while (totalBought < desiredQuantity) {
            int remaining = desiredQuantity - totalBought;
            long spent = (long) totalBought * price;
            int remainingBudget = maximumSpend == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) maximumSpend - spent));
            int affordable = affordableQuantity(
                    bot.getMeso(),
                    minimumMesoReserve,
                    price,
                    remaining,
                    batchSize,
                    remainingBudget);
            if (affordable <= 0) {
                reason = AgentShopShortfallReason.NO_MESO;
                break;
            }
            short qty = (short) affordable;
            Shop.TransactionResult result = AgentShopGatewayRuntime.shop()
                    .buy(bot, shop, item.slot, item.shopItem.getItemId(), qty);
            if (result == Shop.TransactionResult.SUCCESS) {
                totalBought += qty;
                continue;
            }
            if (result == Shop.TransactionResult.NOT_ENOUGH_MESO) {
                reason = AgentShopShortfallReason.NO_MESO;
            } else if (result == Shop.TransactionResult.NO_SPACE) {
                reason = AgentShopShortfallReason.NO_SPACE;
            } else {
                reason = AgentShopShortfallReason.OTHER;
            }
            break;
        }

        return new AgentShopBuyReport(item.shopItem.getItemId(), totalBought, desiredQuantity, reason);
    }

    static int affordableQuantity(
            int mesos, int minimumMesoReserve, int price, int remaining, int batchSize) {
        return affordableQuantity(
                mesos,
                minimumMesoReserve,
                price,
                remaining,
                batchSize,
                Integer.MAX_VALUE);
    }

    static int affordableQuantity(
            int mesos,
            int minimumMesoReserve,
            int price,
            int remaining,
            int batchSize,
            int maximumSpend) {
        if (price <= 0 || remaining <= 0 || batchSize <= 0) {
            return 0;
        }
        int spendableMesos = Math.max(0, mesos - Math.max(0, minimumMesoReserve));
        int boundedSpend = Math.min(spendableMesos, Math.max(0, maximumSpend));
        return Math.min(Math.min(remaining, batchSize), boundedSpend / price);
    }

    private static String buildShortfallMessage(AgentShopBuyReport report, InventoryGateway inventory) {
        String itemName = resolveItemName(report.itemId(), "item", inventory);
        String got = GameConstants.numberWithCommas(report.quantity());
        String want = GameConstants.numberWithCommas(report.requestedQuantity());
        return switch (report.reason()) {
            case NO_SPACE -> AgentDialogueCatalog.shopNoSpaceReply(itemName, got, want, report.quantity());
            case OTHER -> AgentDialogueCatalog.shopRefusedReply(itemName);
            case NO_MESO, NONE -> AgentDialogueCatalog.shopNoMesoReply(itemName, got, want, report.quantity());
        };
    }

    private static String resolveItemName(int itemId, String fallbackName, InventoryGateway inventory) {
        String name = inventory.getItemName(itemId);
        return name != null ? name : fallbackName;
    }

    private static boolean isShopSequenceValid(AgentRuntimeEntry entry, Character bot, Point npcPos) {
        if (bot.getMap() == null) {
            return false;
        }
        // Accept proximity to the approach point OR the NPC itself: the sequence can start
        // via the stuck-at-NPC fallback, where the bot is near the NPC but not the approach point.
        // The transaction radius is wider than the arrival radius: a shop stays usable from
        // anywhere on the shopkeeper's screen, and landing from a jump must not void it.
        return AgentShopStateRuntime.sequenceValid(entry, bot.getPosition(), npcPos, SHOP_TRANSACTION_RADIUS)
                && findNpcNear(bot, npcPos) != null;
    }

    public static void cancelShopVisit(AgentRuntimeEntry entry) {
        clearShopState(entry);
    }

    // Abort the shop visit and tell the owner why. Player commands cancel via
    // cancelShopVisit (which clears shopVisitPending) and scheduleShopStep guards on
    // that flag, so a cleared flag here means a concurrent player cancel — stay silent.
    private static void abortShop(AgentRuntimeEntry entry, Character bot, String reason) {
        if (AgentShopStateRuntime.shopVisitPending(entry)) {
            AgentShopRuntime.sayMapNow(bot, reason);
        }
        logShopFailure(entry, bot, reason);
        AgentShopStateRuntime.blockWorkflow(entry, reason, System.currentTimeMillis());
        clearShopState(entry);
    }

    // The spoken reason is only heard on the map; log it with the geometry so a failed
    // resupply is diagnosable from the server log alone.
    private static void logShopFailure(AgentRuntimeEntry entry, Character bot, String reason) {
        AgentShopState state = entry.shopState();
        Point botPos = bot == null ? null : bot.getPosition();
        Point npcPos = state.npcPosition();
        Point target = state.targetPosition();
        NPC npc = bot != null && bot.getMap() != null && npcPos != null ? findNpcNear(bot, npcPos) : null;
        long now = System.currentTimeMillis();
        log.warn("Agent '{}' shop visit failed: reason=\"{}\" phase={} map={} npc={} npcPos={} approachPos={} botPos={}"
                        + " distToNpc={} distToApproach={} visitPending={} sequenceActive={} visitAgeMs={} sequenceAgeMs={}"
                        + " sequenceAllowanceMs={} sellTrashPending={} mesos={}",
                bot == null ? "?" : bot.getName(),
                reason,
                state.workflow().phase(),
                bot == null ? -1 : bot.getMapId(),
                npc == null ? "none" : npc.getId(),
                fmt(npcPos),
                fmt(target),
                fmt(botPos),
                botPos == null || npcPos == null ? -1 : manhattan(botPos, npcPos),
                botPos == null || target == null ? -1 : manhattan(botPos, target),
                state.visitPending(),
                state.sequenceActive(),
                state.visitStartedAtMs() > 0 ? now - state.visitStartedAtMs() : -1,
                state.sequenceStartedAtMs() > 0 ? now - state.sequenceStartedAtMs() : -1,
                state.sequenceAllowanceMs(),
                state.sellTrashPending(),
                bot == null ? -1 : bot.getMeso());
    }

    private static String fmt(Point point) {
        return point == null ? "null" : "(" + point.x + "," + point.y + ")";
    }

    private static void clearShopState(AgentRuntimeEntry entry) {
        AgentShopStateRuntime.clearShopState(entry);
    }

    private static long stepDelayMs() {
        return AgentShopRuntime.randomDelayMs(SHOP_STEP_DELAY_MIN_MS, SHOP_STEP_DELAY_MAX_MS);
    }

    private static void scheduleShopStep(AgentRuntimeEntry entry, Runnable step) {
        scheduleShopStep(entry, stepDelayMs(), step);
    }

    private static void scheduleShopStep(AgentRuntimeEntry entry, long delayMs, Runnable step) {
        AgentShopRuntime.afterDelay(entry, delayMs, () -> {
            if (!AgentShopStateRuntime.shouldRunScheduledShopStep(entry)) {
                return;
            }
            try {
                step.run();
            } catch (RuntimeException exception) {
                // The scheduler only reports delayed-action failures at debug level, so the
                // stack trace would otherwise vanish along with the shop visit.
                Character bot = AgentRuntimeIdentityRuntime.bot(entry);
                log.warn("Agent '{}' shop step threw; aborting the shop visit",
                        bot == null ? "?" : bot.getName(), exception);
                abortShop(entry, bot, AgentDialogueCatalog.shopScheduleErrorReply());
                throw exception;
            }
        });
    }

    private static Point pickShopApproachPoint(Point npcPos, AgentRuntimeEntry entry, Character bot) {
        var footholds = bot.getMap().getFootholds();
        if (footholds == null) {
            return npcPos;
        }
        List<Point> candidates = AgentShopApproachPolicy.footholdCandidatesNear(
                npcPos, footholds.getAllFootholds(), SHOP_MANHATTAN_RADIUS);
        if (candidates.isEmpty()) {
            return npcPos;
        }
        AgentMovementProfile profile = AgentMovementStateRuntime.movementProfileOrCharacter(entry, bot);
        AgentNavigationGraph graph = AgentNavigationGraphService.peekBestGraph(bot.getMap(), profile);
        if (graph != null) {
            Point botPos = bot.getPosition();
            int startRegionId = AgentNavigationRegionService.resolveCurrentRegionId(graph, entry, bot.getMap(), botPos);
            if (startRegionId >= 0) {
                List<Point> reachable = new ArrayList<>();
                for (Point candidate : candidates) {
                    int targetRegionId = AgentNavigationRegionService.resolveTargetRegionId(
                            graph, entry, bot.getMap(), candidate);
                    if (targetRegionId < 0) continue;
                    if (startRegionId == targetRegionId
                            || !AgentNavigationPathService.findPathForApproachProbe(graph, bot.getMap(), botPos,
                                    startRegionId, targetRegionId, candidate).isEmpty()) {
                        reachable.add(candidate);
                    }
                }
                if (!reachable.isEmpty()) {
                    candidates = reachable;
                }
            }
        }
        Point botPosition = bot.getPosition();
        candidates.sort(java.util.Comparator
                .comparingInt((Point candidate) -> Math.abs(candidate.y - npcPos.y))
                .thenComparingInt(candidate -> manhattan(candidate, npcPos))
                .thenComparingInt(candidate -> manhattan(candidate, botPosition))
                .thenComparingInt(candidate -> candidate.x));
        return candidates.get(0);
    }


    /**
     * The visit's shopkeeper: the shop NPC standing nearest {@code pos} within SHOP_NPC_SEARCH_DIST, not merely the
     * first one in range (Herb Town's armor shop stands 538 px from Lan Ming); null when there is none.
     */
    static NPC findNpcNear(Character bot, Point pos) {
        NPC nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (MapObject obj : bot.getMap().getMapObjectsInRange(
                pos, SHOP_NPC_SEARCH_DIST * SHOP_NPC_SEARCH_DIST,
                Arrays.asList(MapObjectType.NPC))) {
            NPC npc = (NPC) obj;
            if (!npc.hasShop()) {
                continue;
            }
            double distance = npc.getPosition().distanceSq(pos);
            if (distance < nearestDistance) {
                nearest = npc;
                nearestDistance = distance;
            }
        }
        return nearest;
    }
}
