package server.agents.capabilities.shop;

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
import server.agents.capabilities.supplies.AgentSupplyProcurementRuntime;
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
import server.agents.capabilities.inventory.demand.AgentEtcRoomPlan;
import server.agents.capabilities.inventory.demand.AgentEtcSaleCandidate;
import server.agents.capabilities.inventory.demand.AgentQuestItemDispositionRuntime;
import server.agents.capabilities.movement.AgentMapStateRuntime;
import server.agents.capabilities.movement.AgentMovementStateResetService;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.activity.AgentActivityHostState;
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
import server.maps.MapleMap;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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

    /** The clock a shop step's lateness is read on ({@link #scheduleShopStep}); a test's own virtual one. */
    static java.util.function.LongSupplier stepClock = System::currentTimeMillis;

    private AgentShopService() {}

    private record NpcShopMatch(NPC npc, Shop shop, Point npcPos) {}

    private record ShopSlotItem(
            short slot,
            ShopItem shopItem,
            AgentPotionRecoveryPolicy.Recovery recovery) {}

    /**
     * What a sell-trash pass got rid of: trash equips one at a time, surplus ETC a stack (sale line) at a time.
     * Kept apart so the Agent never reports ETC pieces as equips. {@code routine}: the pass is the quiet sale of every
     * visit (AgentJunkSale), fixed as the pass starts (the request it takes is cleared then, so the shop state no
     * longer tells it); an asked pass that follows one at the same counter carries its tally on, with routine false.
     */
    private record SaleTally(int equipsSold, int equipsFailed, int etcStacksSold, int etcStacksFailed,
                             boolean routine) {
        static SaleTally start(boolean routine) {
            return new SaleTally(0, 0, 0, 0, routine);
        }

        SaleTally withEtcSale(boolean sold) {
            return sold
                    ? new SaleTally(equipsSold, equipsFailed, etcStacksSold + 1, etcStacksFailed, routine)
                    : new SaleTally(equipsSold, equipsFailed, etcStacksSold, etcStacksFailed + 1, routine);
        }

        SaleTally withEquipSold() {
            return new SaleTally(equipsSold + 1, equipsFailed, etcStacksSold, etcStacksFailed, routine);
        }

        SaleTally withEquipsFailed(int failed) {
            return new SaleTally(equipsSold, failed, etcStacksSold, etcStacksFailed, routine);
        }

        /** This tally carried into the asked pass that follows it at the same counter. */
        SaleTally asked() {
            return new SaleTally(equipsSold, equipsFailed, etcStacksSold, etcStacksFailed, false);
        }

        boolean anySold() {
            return equipsSold > 0 || etcStacksSold > 0;
        }

        boolean anyFailed() {
            return equipsFailed > 0 || etcStacksFailed > 0;
        }
    }

    /**
     * The Agent arrived on a map (the map-change hooks): as {@link #onMapChange}, except that one its readiness duty
     * holds back for mesos (AgentSupplyReadiness.heldForMesos: a broke companion that followed its owner into a store)
     * starts no visit of its own. What it cannot pay for its readiness says, and what its owner hands it there stays
     * a gift it can answer. A supply trip that begins here still opens its visit ({@link #onMapChange}). A new map
     * visit gets one more look at a shop its owner opens there ({@link #tickOwnerShopping}). A sale asked before this
     * arrival ({@link #requestSaleHere}) opens now, unless the arrival opened a visit of its own (whose counter sells
     * the junk anyway).
     */
    public static void onArrival(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory) {
        AgentShopStateRuntime.setOwnerShopSaleMapId(entry, 0);
        boolean saleAsked = AgentShopStateRuntime.takeShopSaleOnArrival(entry);
        if (AgentSupplyReadiness.heldForMesos(entry, System.currentTimeMillis())) {
            clearShopState(entry);
            return;
        }
        onMapChange(entry, bot, inventory);
        if (saleAsked && !AgentShopStateRuntime.shopVisitPending(entry)) {
            requestSaleVisit(entry, bot, 0, true);
        }
    }

    public static void onMapChange(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory) {
        clearShopState(entry);
        if (bot == null || bot.getMap() == null) {
            return;
        }
        if (AgentSupplyProcurementRuntime.travellingPast(entry, bot.getMapId())) {
            // on its way to its supplier: a shop it passes is not its stop. Live 10-25..29, supply trips to Ludibrium's
            // potion shop 221000200 stopped at the Gumball Machine on 221021600, whose counter no walk reaches, and
            // timed out there ("never reached the shop NPC") before walking on
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

    /** The shopkeeper the pending visit trades with (the one its purchase steps find), or 0 when there is none. */
    public static int pendingVisitNpcId(AgentRuntimeEntry entry, Character bot) {
        Point npcPos = AgentShopStateRuntime.shopNpcPosition(entry);
        if (npcPos == null || bot == null || bot.getMap() == null) {
            return 0;
        }
        NPC npc = findNpcNear(bot, npcPos);
        return npc == null ? 0 : npc.getId();
    }

    public static void requestSellTrashVisit(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return;
        }
        if (AgentInventorySellTrashService.collectSellTrashEquips(entry, bot, inventory).isEmpty()) {
            AgentShopRuntime.replyNow(entry, AgentDialogueCatalog.shopNoTrashEquipsReply());
            return;
        }

        // a quiet sale on its way (every visit sells, AgentJunkSale) is no sale the owner asked for: it joins it, and
        // the sale is announced from now on
        boolean alreadySelling = AgentShopStateRuntime.shopSaleAsked(entry);
        AgentShopStateRuntime.setShopSellTrashPending(entry, true);
        if (AgentShopStateRuntime.shopVisitPending(entry)) {
            // the visit on its way (a restock, an arrival at a shop) sells first: it joins that one, and says so. One
            // already at the counter may be past its sell step: it sells once its purchases are done (runPurchaseStep)
            AgentShopRuntime.replyNow(entry, AgentShopStateRuntime.shopSequenceActive(entry)
                    ? AgentDialogueCatalog.shopSellTrashAfterReply()
                    : alreadySelling
                    ? AgentDialogueCatalog.shopSellTrashAgainReply() : AgentDialogueCatalog.shopSellTrashJoinReply());
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

    /**
     * The sell-trash visit, asked by the Agent's host rather than its owner and so without its lines: to shop NPC
     * {@code npcId} on its map, to sell its trash equips and spare drops (a citizen whose equip bag filled). False when
     * none can start here: that shopkeeper is not on its map, or a visit is under way.
     */
    public static boolean requestSaleAt(AgentRuntimeEntry entry, Character bot, int npcId) {
        if (entry == null || bot == null || bot.getMap() == null || AgentShopStateRuntime.shopVisitPending(entry)) {
            return false;
        }
        NpcShopMatch match = findShopForNpc(bot, npcId);
        if (match == null) {
            return false;
        }
        AgentShopStateRuntime.setShopSellTrashPending(entry, true);
        startShopVisit(entry, bot, match);
        return true;
    }

    /**
     * Opens a visit only to sell the Agent's junk gear (decision 2026-10-10): at shopkeeper {@code npcId} on its map,
     * or with {@code anyShop} at any shopkeeper there when that one is not (a bench sale slice). It buys nothing and
     * says nothing; it walks after the usual 0 to SHOP_APPROACH_DELAY_MAX_MS. False, with nothing opened, while a
     * visit is pending, when AgentJunkSale names no routine sale for it, when it carries no junk, or when no
     * shopkeeper is found. On the Agent's own thread (the junk look refreshes its reservations).
     */
    public static boolean requestSaleVisit(AgentRuntimeEntry entry, Character bot, int npcId, boolean anyShop) {
        if (entry == null || bot == null || bot.getMap() == null || AgentShopStateRuntime.shopVisitPending(entry)) {
            return false;
        }
        if (!AgentJunkSale.routineSale(bot) || !AgentJunkSale.hasJunk(entry, bot)) {
            return false;
        }
        NpcShopMatch match = findShopForNpc(bot, npcId);
        if (match == null && anyShop) {
            match = findBestShop(bot, true, AgentInventoryGatewayRuntime.inventory());
        }
        if (match == null) {
            return false;
        }
        startShopVisit(entry, bot, match);
        AgentShopStateRuntime.markShopSaleOnly(entry);
        AgentShopStateRuntime.requestShopRoutineSale(entry);
        log.info("Agent '{}' walks to NPC {} on map {} to sell its junk", bot.getName(), match.npc().getId(),
                bot.getMapId());
        return true;
    }

    /**
     * {@link #requestSaleVisit} at any shopkeeper on the map the Agent was just loaded onto (a bench sale slice,
     * BenchDutyDispatcher). Its arrival there clears every visit (onArrival), so before that has run the sale waits for
     * it. True when the visit opened or waits for the arrival. On the Agent's own thread.
     */
    public static boolean requestSaleHere(AgentRuntimeEntry entry, Character bot) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return false;
        }
        if (!AgentMapStateRuntime.isTrackingMap(entry, bot.getMapId())) {
            AgentShopStateRuntime.setShopSaleOnArrival(entry, true);
            return true;
        }
        return requestSaleVisit(entry, bot, 0, true);
    }

    /**
     * Where the Agent would stand to trade with the shopkeeper at {@code npcPos} on its map: the visit's own pick, the
     * foothold point near the shopkeeper its route reaches soonest. A shopkeeper can stand inside its counter's
     * footholds, where nobody walks to; {@code npcPos} when the map has no footholds.
     */
    public static Point approachPoint(AgentRuntimeEntry entry, Character bot, Point npcPos) {
        if (entry == null || bot == null || bot.getMap() == null || npcPos == null) {
            return npcPos;
        }
        return pickShopApproach(npcPos, entry, bot).point();
    }

    /**
     * Its owner opened an NPC shop window on its map (Character.getShop, set by Shop.sendShop): a following companion
     * with junk gear walks to that shopkeeper and sells there ({@link #requestSaleVisit}, that shopkeeper only). One
     * look per map visit (onArrival starts the next), and none while its owner is changing maps, before the arrival
     * hook ran on this map, while a visit is pending, while it is dead, busy with an objective, a trade, a script task
     * or an Activity Host controller, or held back for mesos (its readiness flow decides what it does in a store).
     * Every check before the look is a field read; the preflight runs it on every Agent tick.
     */
    public static void tickOwnerShopping(AgentRuntimeEntry entry, Character bot, long nowMs) {
        if (entry == null || bot == null || !AgentModeStateRuntime.following(entry)) {
            return;
        }
        Character anchor = AgentJunkSale.followAnchor(entry);
        Shop open = anchor == null ? null : anchor.getShop();
        if (open == null || anchor.getMapId() != bot.getMapId() || anchor.isChangingMaps()) {
            return;
        }
        int mapId = bot.getMapId();
        if (!AgentMapStateRuntime.isTrackingMap(entry, mapId)
                || AgentShopStateRuntime.ownerShopSaleMapId(entry) == mapId) {
            return;
        }
        if (AgentShopStateRuntime.shopVisitPending(entry) || bot.getHp() <= 0
                || AgentObjectiveKernel.active(entry) != null || bot.getTrade() != null
                || entry.scriptTaskQueueState().hasTasks() || activityHostOwnsForeground(entry)) {
            return;
        }
        if (AgentSupplyReadiness.heldForMesos(entry, nowMs)) {
            return;
        }
        AgentShopStateRuntime.setOwnerShopSaleMapId(entry, mapId); // a refused or empty look is not repeated here
        requestSaleVisit(entry, bot, open.getNpcId(), false);
    }

    private static boolean activityHostOwnsForeground(AgentRuntimeEntry entry) {
        return entry.capabilityStates().find(AgentActivityHostState.STATE_KEY)
                .map(host -> host.controllerId() != null).orElse(false);
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
        startShopVisit(entry, bot, match.npcPos, (int) AgentRandom.randMs(0, SHOP_APPROACH_DELAY_MAX_MS),
                minimumMesoReserve, requiredItemId, requiredItemCount, System.currentTimeMillis());
    }

    static void startShopVisit(
            AgentRuntimeEntry entry,
            Character bot,
            Point npcPos,
            int approachDelayMs,
            int minimumMesoReserve,
            int requiredItemId,
            int requiredItemCount,
            long now) {
        ShopApproach approach = pickShopApproach(npcPos, entry, bot);
        AgentShopStateRuntime.startShopVisit(
                entry,
                npcPos,
                approach.point(),
                approachDelayMs,
                minimumMesoReserve,
                requiredItemId,
                requiredItemCount,
                now);
        // SHOP_VISIT_TIMEOUT_MS counts on top of the walk: one flat 30 s from the visit start was shorter than the
        // route to some shops (Mushroom Shrine 800000000 about 30 s, Ant Tunnel Park 105070001 about 38 s).
        AgentShopStateRuntime.extendShopVisit(entry, approach.routeMs());
    }

    public static boolean tickShopVisit(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory) {
        return tickShopVisit(entry, bot, inventory, System.currentTimeMillis());
    }

    static boolean tickShopVisit(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory, long now) {
        if (!AgentShopStateRuntime.shopVisitPending(entry)) {
            return false;
        }
        if (AgentShopStateRuntime.shopSaleOnly(entry) && !AgentShopStateRuntime.shopSequenceActive(entry)
                && AgentJunkSale.ownerWalkedOn(entry, bot)) {
            // a visit only to sell, not reached yet: it follows its owner out instead (the follow sync catches up)
            log.info("Agent '{}' gave up selling on map {}: its owner left", bot.getName(), bot.getMapId());
            clearShopState(entry);
            return false;
        }
        if (!AgentShopStateRuntime.hasShopNpcPosition(entry)) {
            abortShop(entry, bot, AgentDialogueCatalog.shopLostReply());
            return false;
        }
        if (AgentShopStateRuntime.visitTimedOut(entry, now, SHOP_VISIT_TIMEOUT_MS)) {
            if (!AgentShopStateRuntime.shopSaleOnly(entry)) {
                AgentShopRuntime.sayMapNow(bot, AgentDialogueCatalog.shopReachTimeoutReply());
            }
            logShopFailure(entry, bot, "visit timeout (never reached the shop NPC)");
            clearShopState(entry);
            return false;
        }
        if (AgentShopStateRuntime.sequenceTimedOut(entry, now, SHOP_SEQUENCE_TIMEOUT_MS)) {
            abortShop(entry, bot, AgentDialogueCatalog.shopSequenceTimeoutReply());
            return false;
        }
        if (AgentShopStateRuntime.shopApproachDelayMs(entry) > 0) {
            // Wall-clock, like the visit timeout. Counted one movement tick per call, it lasted five times
            // longer for an Agent no player watches (it ticks every background period), and the Agent
            // stood still until the visit timed out (Glibber, El Nath Ice Valley II 211040200).
            if (AgentShopStateRuntime.shopApproachDelayPending(entry, now)) {
                return false;
            }
            AgentShopStateRuntime.setShopApproachDelayMs(entry, 0);
        }

        Point botPos = bot.getPosition();
        Point target = AgentShopStateRuntime.shopTargetOrNpcPosition(entry);
        boolean reachedApproach = manhattan(botPos, target) <= SHOP_ARRIVE_DIST;
        if (!reachedApproach && !AgentShopStateRuntime.shopSequenceActive(entry)) {
            // A walk that stopped gets none of its route's time: stuck where it landed, an Agent at Mushroom Shrine
            // waited out the route and the delay and the timeout, 64 s, before it gave up. One still walking keeps
            // the whole allowance (visitTimedOut).
            AgentShopStateRuntime.noteShopApproach(entry, manhattan(botPos, target), SHOP_STUCK_MOVE_TOLERANCE_PX, now);
            if (AgentShopStateRuntime.shopApproachStalled(entry, now, SHOP_VISIT_TIMEOUT_MS)) {
                if (!AgentShopStateRuntime.shopSaleOnly(entry)) { // a visit nobody asked for gives up quietly
                    AgentShopRuntime.sayMapNow(bot, AgentDialogueCatalog.shopReachTimeoutReply());
                }
                logShopFailure(entry, bot, "visit timeout (never reached the shop NPC: no closer in "
                        + SHOP_VISIT_TIMEOUT_MS + " ms)");
                clearShopState(entry);
                return false;
            }
        }
        boolean stuckAtNpc = !AgentShopStateRuntime.shopSequenceActive(entry)
                && !reachedApproach
                && isStuckNearNpc(entry, botPos, now);
        if (reachedApproach || stuckAtNpc) {
            if (!AgentShopStateRuntime.shopSequenceActive(entry)) {
                AgentShopStateRuntime.markShopSequenceActive(entry, now);
                // Close enough to trade: drop any committed navigation step so the Agent
                // stands still for the transaction instead of chasing the exact approach point.
                AgentMovementStateResetService.clearNavigationState(entry);
                // Every visit sells a companion's junk gear first, quietly (decision 2026-10-10): claimed here, where
                // every visit opens its transaction (an arrival restock, a trip's visit, an item-only visit, a bench
                // shop slice). A sale already asked for (a trip, "sell junk") keeps its kind and its lines.
                if (!AgentShopStateRuntime.shopSellTrashPending(entry) && AgentJunkSale.routineSale(bot)) {
                    AgentShopStateRuntime.requestShopRoutineSale(entry);
                }
                if (!AgentShopStateRuntime.shopSaleOnly(entry)) {
                    AgentShopRuntime.sayMapNow(bot,
                            AgentDialogueSelector.randomReply(AgentDialogueCatalog.shoppingReplies()));
                }
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
     * purchase never counts as the visit's shortfall. They never take the Use slot a readiness trip of more than one
     * stack keeps free for its run ({@link #buffRoomBesideTheRunsSlot}).
     */
    static AgentShopPurchaseSequence<AgentRuntimeEntry> buyBuffConsumables(
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
            int quantity = Math.min(missing, buffRoomBesideTheRunsSlot(sequence, shop, item));
            if (quantity <= 0) {
                continue;
            }
            AgentShopBuyReport report = buyFixedCostItem(bot, shop, item, quantity, quantity, minimumMesoReserve,
                    buffPotionBudget(bot.getMeso(), minimumMesoReserve));
            if (report.quantity() > 0) {
                sequence.bought().add(report.quantity() + " "
                        + resolveItemName(report.itemId(), "buff potion", sequence.inventory()));
            }
        }
        return sequence;
    }

    /**
     * How many of a buff potion a visit may buy: no limit, except at the last free Use slot of a readiness duty whose
     * standard is more than one stack of the potions this shop sells (its trip was let go for one free slot per stack
     * and the one its run keeps, AgentSupplyReadinessRuntime.useSlotsNeeded): then the room of the stacks of it
     * carried. A standard of one stack (KPQ, HPQ) and an ordinary trip keep today's purchase.
     */
    private static int buffRoomBesideTheRunsSlot(AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop,
                                                 ShopSlotItem item) {
        Character bot = sequence.bot();
        if (freeUseSlots(bot) > 1) {
            return Integer.MAX_VALUE;
        }
        AgentSupplyReadiness.Activity activity = AgentSupplyReadiness.shoppingActivity(sequence.entry(), bot);
        if (activity == null || !aboveOneStack(bot, shop, readinessStandard(bot, shop, activity),
                sequence.inventory())) {
            return Integer.MAX_VALUE;
        }
        return stackRoom(bot, item.shopItem.getItemId(), sequence.inventory());
    }

    /** True when {@code standard} is more than one stack of the HP, or for a skill user MP, potions this shop sells. */
    private static boolean aboveOneStack(Character bot, Shop shop, AgentSupplyReadiness.Standard standard,
                                         InventoryGateway inventory) {
        int hpStack = potionStackSize(bot, shop, true, inventory);
        int mpStack = AgentSupplyReadiness.needsManaPotions(bot) ? potionStackSize(bot, shop, false, inventory) : 0;
        return hpStack > 0 && standard.hp() > hpStack || mpStack > 0 && standard.mp() > mpStack;
    }

    static int buffPotionBudget(int mesos, int minimumMesoReserve) {
        long spare = Math.max(0, mesos - Math.max(0, minimumMesoReserve));
        return (int) (spare * BUFF_POTION_SPEND_PERCENT / 100);
    }

    private static void executePurchases(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory, Point npcPos) {
        executePurchases(entry, bot, inventory, npcPos, false);
    }

    private static void executePurchases(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory,
                                         Point npcPos, boolean roomSold) {
        if (!isShopSequenceValid(entry, bot, npcPos)) {
            abortShop(entry, bot, AgentDialogueCatalog.shopKeeperUnreachableReply());
            return;
        }

        if (AgentShopStateRuntime.shopSellTrashPending(entry)) {
            startSellTrashSequence(new AgentShopPurchaseSequence<>(
                    entry, bot, inventory, npcPos, List.of(), new ArrayList<>(), null),
                    () -> executePurchases(entry, bot, inventory, npcPos, roomSold));
            return;
        }

        // 2026-10-22 (CWKPQ C9): a readiness trip for an activity with an ETC room standard sells monster drops for
        // that room first (AgentEtcRoomPlan), once a visit
        List<AgentEtcSaleCandidate> room = roomSold ? List.of() : etcRoomSales(entry, bot);
        if (!room.isEmpty()) {
            AgentShopStateRuntime.extendShopSequence(entry, (long) room.size() * SHOP_SEQUENCE_SALE_ALLOWANCE_MS);
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runEtcSaleStep(entry, bot, npcPos, SaleTally.start(false), 0, room, new ArrayList<>(),
                            null, inventory, () -> executePurchases(entry, bot, inventory, npcPos, true)));
            return;
        }

        // a visit opened only to sell buys nothing
        List<AgentShopPurchaseAction<AgentRuntimeEntry>> actions = AgentShopStateRuntime.shopSaleOnly(entry)
                ? List.of()
                : purchaseActions(entry, bot, inventory, AgentAttackExecutionProvider.getEquippedWeaponType(bot));
        runPurchaseStep(new AgentShopPurchaseSequence<>(entry, bot, inventory, npcPos,
                actions, new ArrayList<>(), null), 0);
    }

    /** The stacks a readiness trip sells for its activity's ETC room standard; empty off a trip or with room. */
    static List<AgentEtcSaleCandidate> etcRoomSales(AgentRuntimeEntry entry, Character bot) {
        AgentSupplyReadiness.Activity trip = AgentSupplyReadiness.tripActivity(entry);
        int wanted = trip == null ? 0 : AgentSupplyReadiness.etcRoomStandard(trip);
        return wanted <= 0 ? List.of() : AgentEtcRoomPlan.plan(bot, wanted);
    }

    /**
     * The purchase steps of a visit, in order: a required travel item alone; else on a Victoria shop the old ammo
     * top-up (a seed set, the best stacks' refills, arrows to the ammo target), the potions, then skill and buff
     * consumables. On a readiness trip the potions come in two parts around its ammo step ({@link #buyReadinessAmmo},
     * at any supplier): first the potion standard and the critical reserve ({@link #buyPotionStandard}), then the
     * trip's ammo standard, then the ordinary reserve and the top-up toward the buy target ({@link #buyPotionExtras}),
     * so nothing past the potion standard spends the money the trip's checks priced for its projectiles.
     */
    static List<AgentShopPurchaseAction<AgentRuntimeEntry>> purchaseActions(
            AgentRuntimeEntry entry, Character bot, InventoryGateway inventory, WeaponType wt) {
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
        AgentSupplyReadiness.Activity trip = AgentSupplyReadiness.tripActivity(entry);
        if (!itemOnlyVisit) {
            if (trip == null) {
                actions.add((sequence, shop) ->
                        buyPotionStock(makeUseRoomForPotions(sequence, shop, wt), shop, minimumMesoReserve));
            } else {
                // a readiness trip buys its ammo standard after the potion standard, before any potion past it
                int[] potionsBought = {0, 0};
                actions.add((sequence, shop) -> buyPotionStandard(makeUseRoomForReadiness(sequence, shop, wt, trip),
                        shop, minimumMesoReserve, potionsBought));
                actions.add((sequence, shop) -> readinessAmmoStep(sequence, shop, bot, wt,
                        AgentSupplyReadiness.ammoTarget(trip), minimumMesoReserve));
                actions.add((sequence, shop) -> readinessCureStep(sequence, shop, bot, trip, minimumMesoReserve));
                actions.add((sequence, shop) ->
                        buyPotionExtras(sequence, shop, minimumMesoReserve, potionsBought));
            }
            actions.add((sequence, shop) ->
                    buySkillConsumables(sequence, shop, minimumMesoReserve));
            actions.add((sequence, shop) ->
                    buyBuffConsumables(sequence, shop, minimumMesoReserve));
            if (trip != null) {
                // last, with what is left: headroom past the ammo standard
                actions.add((sequence, shop) -> readinessAmmoHeadroomStep(sequence, shop, bot, wt,
                        readinessAmmoBuyTarget(trip), minimumMesoReserve));
            }
        }
        return actions;
    }

    /**
     * What a readiness trip's last step buys its projectiles toward: AgentSupplyReadiness.ammoBuyTarget, never past
     * what one Victoria top-up buys to (AMMO_LOW_WARN x AMMO_TARGET_THRESHOLD) unless the standard itself is.
     */
    static int readinessAmmoBuyTarget(AgentSupplyReadiness.Activity trip) {
        return Math.max(AgentSupplyReadiness.ammoTarget(trip),
                Math.min(ammoTargetThreshold(), AgentSupplyReadiness.ammoBuyTarget(trip)));
    }

    private static void runPurchaseStep(AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, int index) {
        if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
            abortShop(sequence.entry(), sequence.bot(), AgentDialogueCatalog.shopBuyInterruptedReply());
            return;
        }
        if (index >= sequence.actions().size()) {
            // a visit opened only to sell never says it needed nothing here
            boolean announceIfEmpty = !AgentShopStateRuntime.shopSaleOnly(sequence.entry());
            if (AgentShopStateRuntime.shopSellTrashPending(sequence.entry())) {
                // "sell junk" came after the sell step at the transaction's start: it sells here before it goes
                startSellTrashSequence(sequence, () -> finishPurchaseSequence(sequence, announceIfEmpty));
                return;
            }
            finishPurchaseSequence(sequence, announceIfEmpty);
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

    /**
     * What a visit says as it ends at the counter: its first spoken shortfall; else, with {@code announceIfEmpty},
     * that it needed nothing when it bought nothing and fell short of nothing (a quiet shortfall included: the trip's
     * own lines tell that); else nothing.
     */
    static String closingLine(AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, boolean announceIfEmpty) {
        if (sequence.firstShortfall() != null) {
            return buildShortfallMessage(sequence.firstShortfall(), sequence.inventory());
        }
        if (announceIfEmpty && !sequence.boughtAnything() && !sequence.quietShortfall()) {
            // Never end a resupply visit silently: nothing was bought and nothing fell short.
            return AgentDialogueCatalog.shopEmptyResupplyReply();
        }
        return null;
    }

    /**
     * After the purchase steps: says what it bought and closes one shop step later ({@link #closeVisit}), else at
     * once.
     */
    static void finishPurchaseSequence(AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, boolean announceIfEmpty) {
        if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
            abortShop(sequence.entry(), sequence.bot(), AgentDialogueCatalog.shopFinishFailedReply());
            return;
        }

        Runnable finish = () -> closeVisit(sequence, announceIfEmpty);

        if (sequence.boughtAnything()) {
            if (!sequence.bought().isEmpty()) {
                AgentShopRuntime.sayMapNow(sequence.bot(), AgentDialogueCatalog.shopBoughtReply(sequence.bought()));
            }
            AgentPotionService.setupAutopotForBot(sequence.bot());
            AgentCombatAmmoCheckRuntime.tickAmmoCheck(sequence.entry(), sequence.bot(),
                    AgentSupplyConfig.cfg.AMMO_LOW_WARN, AgentRuntimeConfig.cfg.POT_LOW_WARN);
            scheduleShopStep(sequence.entry(), finish);
            return;
        }

        finish.run();
    }

    /**
     * The visit's last step at the counter: its closing line, then the visit ends. A visit that bought something
     * closes one shop step after its "bought ..." line, and "sell junk" in that step ("i'll sell the junk after
     * this") comes after the purchase steps' own check (runPurchaseStep): it sells here first, at the same counter,
     * then closes (second deploy review 2026-10-10: the close cleared the flag and nothing was sold).
     */
    private static void closeVisit(AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, boolean announceIfEmpty) {
        if (!isShopSequenceValid(sequence.entry(), sequence.bot(), sequence.npcPos())) {
            abortShop(sequence.entry(), sequence.bot(), AgentDialogueCatalog.shopFinishFailedReply());
            return;
        }
        if (AgentShopStateRuntime.shopSellTrashPending(sequence.entry())) {
            startSellTrashSequence(sequence, () -> closeVisit(sequence, announceIfEmpty));
            return;
        }
        String closing = closingLine(sequence, announceIfEmpty);
        if (closing != null) {
            AgentShopRuntime.sayMapNow(sequence.bot(), closing);
        }
        AgentShopStateRuntime.completeWorkflow(sequence.entry(), "shop transaction reconciled",
                System.currentTimeMillis());
        clearShopState(sequence.entry());
    }

    private static void startSellTrashSequence(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Runnable afterSale) {
        startSellTrashSequence(sequence, afterSale, null, Set.of());
    }

    /**
     * One sell-trash pass at the counter. Its kind is fixed here, before the request it takes is cleared: a routine
     * pass (the quiet sale every visit makes for a companion, AgentJunkSale) sells gear only, says nothing and stops
     * when a following Agent's owner leaves the map; an asked pass (its owner's "sell junk", its own supply trip) sells
     * gear and ETC surplus and says how it went. {@code carried}: the tally of the routine pass this asked pass follows
     * at the same counter ("sell junk" came during it), told in this pass's one line; {@code carriedFailed}: what that
     * pass could not sell, not tried again.
     */
    private static void startSellTrashSequence(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Runnable afterSale,
            SaleTally carried,
            Set<Item> carriedFailed) {
        AgentRuntimeEntry entry = sequence.entry();
        boolean routine = AgentShopStateRuntime.shopRoutineSale(entry);
        List<Item> items = AgentInventorySellTrashService.collectSellTrashEquips(
                entry, sequence.bot(), sequence.inventory());
        // a routine sale sells gear only: the ETC forecast knows the Agent's own quests, not its owner's or party's
        List<AgentEtcSaleCandidate> etcPlan = routine
                ? List.of()
                : AgentQuestItemDispositionRuntime.collectShopSaleCandidates(
                        entry, sequence.bot(), sequence.inventory(),
                        System.currentTimeMillis());
        // the request this sale takes ends with its plan: one made during the sale ("i'll sell the junk after this")
        // stays, and the visit's next step sells what came meanwhile at the same counter (third deploy review
        // 2026-10-10: the sale cleared it as it finished, and that junk was never sold)
        AgentShopStateRuntime.setShopSellTrashPending(entry, false);
        SaleTally tally = carried != null ? carried.asked() : SaleTally.start(routine);
        Set<Item> failedItems = Collections.newSetFromMap(new IdentityHashMap<>());
        failedItems.addAll(carriedFailed);
        List<Item> plan = items.stream().filter(item -> !failedItems.contains(item)).toList();
        if (plan.isEmpty() && etcPlan.isEmpty()) {
            if (carried != null) {
                // the quiet pass before it sold or failed something: one line tells both passes
                finishSellTrashSequence(entry, sequence.bot(), sequence.inventory(), sequence.npcPos(),
                        tally.withEquipsFailed(failedItems.size()), failedItems, sequence.bought(),
                        sequence.firstShortfall(), afterSale);
                return;
            }
            if (!routine) {
                AgentShopRuntime.sayMapNow(sequence.bot(), AgentDialogueCatalog.shopNoTrashEquipsReply());
            }
            afterSale.run();
            return;
        }
        // The sequence timeout budgets the purchases; each planned sale is one more step ahead of them,
        // so a full bag of trash must not eat the time the restock needs.
        AgentShopStateRuntime.extendShopSequence(entry,
                (long) (plan.size() + etcPlan.size()) * SHOP_SEQUENCE_SALE_ALLOWANCE_MS);

        if (plan.isEmpty()) {
            SaleTally equipsDone = tally.withEquipsFailed(failedItems.size());
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runEtcSaleStep(
                             entry, sequence.bot(), sequence.npcPos(),
                             equipsDone, 0, etcPlan, sequence.bought(), sequence.firstShortfall(),
                             sequence.inventory(), afterSale));
            return;
        }

        scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                () -> runSellTrashStep(
                        entry,
                        sequence.bot(),
                        sequence.npcPos(),
                        tally,
                        failedItems,
                        plan,
                        etcPlan,
                         sequence.bought(),
                         sequence.firstShortfall(),
                         sequence.inventory(),
                         afterSale));
    }

    /**
     * One trash equip sold. {@code tally} counts what sold so far (with a carried routine pass's); what failed is in
     * {@code failedItems} until the gear is done. A routine pass stops here when a following Agent's owner left the
     * map (unless "sell junk" came during it: that sale is kept and never cut); the rest waits for the next shop.
     */
    private static void runSellTrashStep(
            AgentRuntimeEntry entry,
            Character bot,
            Point npcPos,
            SaleTally tally,
            Set<Item> failedItems,
            List<Item> plan,
            List<AgentEtcSaleCandidate> etcPlan,
            List<String> bought,
            AgentShopBuyReport firstShortfall,
            InventoryGateway inventory,
            Runnable afterSale) {
        if (!isShopSequenceValid(entry, bot, npcPos)) {
            // a sale nobody asked for never says it could not sell (a sale-only visit says nothing, abortShop)
            abortShop(entry, bot, tally.routine()
                    ? AgentDialogueCatalog.shopBuyInterruptedReply() : AgentDialogueCatalog.shopSellInterruptedReply());
            return;
        }
        if (tally.routine() && !AgentShopStateRuntime.shopSaleAsked(entry) && AgentJunkSale.ownerWalkedOn(entry, bot)) {
            log.info("Agent '{}' stopped selling on map {}: its owner left ({} sold)",
                    bot.getName(), bot.getMapId(), tally.equipsSold());
            finishSellTrashSequence(entry, bot, inventory, npcPos, tally.withEquipsFailed(failedItems.size()),
                    failedItems, bought, firstShortfall, afterSale);
            return;
        }

        List<Item> items = plan.stream()
                .filter(item -> AgentInventoryItemPolicy.hasItem(bot, item))
                .filter(item -> !failedItems.contains(item))
                .filter(item -> AgentInventoryReservationRuntime.mayConsume(
                        entry, item, System.currentTimeMillis()))
                .toList();
        if (items.isEmpty()) {
            SaleTally equipsDone = tally.withEquipsFailed(failedItems.size());
            if (!etcPlan.isEmpty()) {
                scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                        () -> runEtcSaleStep(entry, bot, npcPos, equipsDone, 0, etcPlan, bought,
                                firstShortfall, inventory, afterSale));
                return;
            }
            finishSellTrashSequence(entry, bot, inventory, npcPos, equipsDone, failedItems, bought, firstShortfall,
                    afterSale);
            return;
        }

        Item item = items.get(0);
        if (!AgentInventoryReservationRuntime.mayConsume(entry, item, System.currentTimeMillis())) {
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runSellTrashStep(entry, bot, npcPos, tally, failedItems,
                            plan, etcPlan, bought, firstShortfall, inventory, afterSale));
            return;
        }
        if (!AgentInventoryItemPolicy.hasItem(bot, item)) {
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runSellTrashStep(entry, bot, npcPos, tally, failedItems,
                            plan, etcPlan, bought, firstShortfall, inventory, afterSale));
            return;
        }

        NPC npc = findNpcNear(bot, npcPos);
        if (npc == null) {
            abortShop(entry, bot, tally.routine()
                    ? AgentDialogueCatalog.shopKeeperGoneBuyReply() : AgentDialogueCatalog.shopKeeperGoneSellReply());
            return;
        }
        Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npc.getId());
        if (shop == null) {
            abortShop(entry, bot, tally.routine()
                    ? AgentDialogueCatalog.shopClosedBuyReply() : AgentDialogueCatalog.shopClosedSellReply());
            return;
        }

        AgentShopGatewayRuntime.shop().sell(bot, shop, InventoryType.EQUIP, item.getPosition(), (short) 1);
        if (AgentInventoryItemPolicy.hasItem(bot, item)) {
            failedItems.add(item);
            scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                    () -> runSellTrashStep(entry, bot, npcPos, tally, failedItems,
                            plan, etcPlan, bought, firstShortfall, inventory, afterSale));
            return;
        }

        SaleTally next = tally.withEquipSold();
        scheduleShopStep(entry, SELL_TRASH_STEP_DELAY_MS,
                () -> runSellTrashStep(entry, bot, npcPos, next, failedItems,
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
            abortShop(entry, bot, tally.routine()
                    ? AgentDialogueCatalog.shopBuyInterruptedReply() : AgentDialogueCatalog.shopSellInterruptedReply());
            return;
        }
        if (index >= plan.size()) {
            finishSellTrashSequence(entry, bot, inventory, npcPos, tally, Set.of(), bought, firstShortfall,
                    afterSale);
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
            abortShop(entry, bot, tally.routine()
                    ? AgentDialogueCatalog.shopKeeperGoneBuyReply() : AgentDialogueCatalog.shopKeeperGoneSellReply());
            return;
        }
        Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npc.getId());
        if (shop == null) {
            abortShop(entry, bot, tally.routine()
                    ? AgentDialogueCatalog.shopClosedBuyReply() : AgentDialogueCatalog.shopClosedSellReply());
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

    /**
     * The end of a sell-trash pass. An asked pass says how it went, as before. A routine pass says nothing (logged at
     * INFO) and the visit goes on; when "sell junk" came during it, the asked pass starts at once at the same counter
     * with this pass's tally, and its one line tells both. The request flag is never written here: one made during
     * this pass stays for the visit's next step.
     */
    private static void finishSellTrashSequence(
            AgentRuntimeEntry entry,
            Character bot,
            InventoryGateway inventory,
            Point npcPos,
            SaleTally tally,
            Set<Item> failedItems,
            List<String> bought,
            AgentShopBuyReport firstShortfall,
            Runnable afterSale) {
        if (tally.routine()) {
            if (AgentShopStateRuntime.shopSaleAsked(entry)) {
                startSellTrashSequence(new AgentShopPurchaseSequence<>(
                        entry, bot, inventory, npcPos, List.of(), bought, firstShortfall), afterSale, tally,
                        failedItems);
                return;
            }
            log.info("Agent '{}' sold its junk on map {}: {} equips sold, {} failed, mesos {}",
                    bot.getName(), bot.getMapId(), tally.equipsSold(), tally.equipsFailed(), bot.getMeso());
            afterSale.run();
            return;
        }
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
        return appendBuyReport(sequence, report, fallbackName, false);
    }

    /** As above; with {@code quiet} a shortfall is a quiet one (logged by its trip, never said at the counter). */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> appendBuyReport(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            AgentShopBuyReport report,
            String fallbackName,
            boolean quiet) {
        if (report.quantity() > 0) {
            sequence.bought().add(report.quantity() + " " + resolveItemName(report.itemId(), fallbackName, sequence.inventory()));
        }
        return quiet ? sequence.withQuietShortfall(report) : sequence.withFirstShortfall(report);
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

    /**
     * A readiness trip's ammo step (after the potion standard): {@link #buyReadinessAmmo} toward {@code target},
     * logged and counted as bought, but not added to the spoken "bought ..." line, and a shortfall is logged, not said
     * (a quiet shortfall): the trip's own go, back and broke lines cover it in the companion's words, and the run's Use
     * slot it keeps free on purpose is no full bag.
     */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> readinessAmmoStep(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop, Character bot, WeaponType wt, int target,
            int minimumMesoReserve) {
        AgentShopBuyReport report = buyReadinessAmmo(bot, shop, wt, sequence.inventory(), target, minimumMesoReserve);
        if (report.quantity() > 0 || report.hasShortfall()) {
            log.info("Agent '{}' readiness ammo: {} x{} ({}), now {}", bot.getName(), report.itemId(),
                    report.quantity(), report.reason(), AgentCombatAmmoCounter.countCarried(bot, wt));
        }
        return sequence.withUnspoken(report.quantity()).withQuietShortfall(report);
    }

    /**
     * 2026-10-14 (P18): a readiness trip toward a cure standard ({@link AgentSupplyReadiness#cureStandard}) buys the
     * shop's cheapest seal cure (Holy Water, All Cure) until the Agent carries the standard, after the potion standard
     * and before any potion past it, never into the last free Use slot (the one a run keeps free). A shop that sells
     * none buys nothing; the trip's checks ({@link #readinessCurePlan}) keep a companion from walking to one.
     * 2026-10-17: every kind of cure {@code activity} has a standard for ({@link AgentSupplyReadiness.CureKind}: seal,
     * then darkness), each the same way.
     */
    static AgentShopPurchaseSequence<AgentRuntimeEntry> readinessCureStep(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop, Character bot,
            AgentSupplyReadiness.Activity activity, int minimumMesoReserve) {
        AgentShopPurchaseSequence<AgentRuntimeEntry> result = sequence;
        for (AgentSupplyReadiness.CureKind kind : AgentSupplyReadiness.CureKind.values()) {
            result = readinessCureStep(result, shop, bot, kind,
                    AgentSupplyReadiness.cureStandard(kind, activity), minimumMesoReserve);
        }
        return result;
    }

    private static AgentShopPurchaseSequence<AgentRuntimeEntry> readinessCureStep(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop, Character bot,
            AgentSupplyReadiness.CureKind kind, int standard, int minimumMesoReserve) {
        int missing = standard - AgentSupplyReadiness.carriedCures(bot, kind);
        ShopSlotItem cure = standard <= 0 || missing <= 0 ? null : cheapestCure(shop, kind);
        if (cure == null) {
            return sequence;
        }
        int quantity = Math.min(missing, cureRoom(bot, cure.shopItem.getItemId(), sequence.inventory()));
        if (quantity <= 0) {
            log.info("Agent '{}' readiness cure: no Use room for {} (lacks {})", bot.getName(),
                    cure.shopItem.getItemId(), missing);
            return sequence;
        }
        AgentShopBuyReport report = buyFixedCostItem(bot, shop, cure, quantity, 100, minimumMesoReserve);
        log.info("Agent '{}' readiness cure: {} x{} ({}), now {}", bot.getName(), report.itemId(),
                report.quantity(), report.reason(), AgentSupplyReadiness.carriedCures(bot, kind));
        return sequence.withUnspoken(report.quantity()).withQuietShortfall(report);
    }

    /**
     * What a readiness trip's cure step would buy at {@code shop} for {@code activity}'s cure standards: the first cure
     * it buys, how many cures in all, what they cost and the Use slots they open, over every kind the shop sells
     * (2026-10-17). {@code quantity} 0 when nothing is lacking; a lacking Agent at a shop that sells none of the cures
     * it lacks gets {@link ReadinessCurePlan#NONE_SOLD}.
     */
    public record ReadinessCurePlan(int itemId, int quantity, long spend, int newStacks) {
        public static final ReadinessCurePlan NOTHING = new ReadinessCurePlan(0, 0, 0L, 0);
        public static final ReadinessCurePlan NONE_SOLD = new ReadinessCurePlan(-1, 0, 0L, 0);

        /** The Agent lacks cures and the shop sells none. */
        public boolean unsold() {
            return itemId < 0;
        }
    }

    /** {@link ReadinessCurePlan} for {@code bot} at {@code shop}. */
    public static ReadinessCurePlan readinessCurePlan(Character bot, Shop shop,
                                                      AgentSupplyReadiness.Activity activity) {
        if (bot == null || shop == null || AgentSupplyReadiness.cureShortfall(bot, activity) <= 0) {
            return ReadinessCurePlan.NOTHING;
        }
        InventoryGateway inventory = AgentInventoryGatewayRuntime.inventory();
        int firstItemId = 0;
        int quantity = 0;
        long spend = 0L;
        int newStacks = 0;
        for (AgentSupplyReadiness.CureKind kind : AgentSupplyReadiness.CureKind.values()) {
            int missing = AgentSupplyReadiness.cureShortfall(bot, activity, kind);
            ShopSlotItem cure = missing <= 0 ? null : cheapestCure(shop, kind);
            if (cure == null) {
                continue;
            }
            int itemId = cure.shopItem.getItemId();
            int perSlot = Math.max(1, inventory.getSlotMax(bot, itemId));
            int beyondStacks = Math.max(0, missing - stackRoom(bot, itemId, inventory));
            firstItemId = firstItemId == 0 ? itemId : firstItemId;
            quantity += missing;
            spend += (long) missing * cure.shopItem.getPrice();
            newStacks += (beyondStacks + perSlot - 1) / perSlot;
        }
        return firstItemId == 0 ? ReadinessCurePlan.NONE_SOLD
                : new ReadinessCurePlan(firstItemId, quantity, spend, newStacks);
    }

    /** The shop's cheapest cure of {@code kind} ({@link AgentSupplyReadiness.CureKind#items}), or null for none. */
    private static ShopSlotItem cheapestCure(Shop shop, AgentSupplyReadiness.CureKind kind) {
        ShopSlotItem best = null;
        for (int itemId : kind.items()) {
            ShopSlotItem item = shop == null ? null : findShopItem(shop, itemId);
            if (item != null && (best == null || item.shopItem.getPrice() < best.shopItem.getPrice())) {
                best = item;
            }
        }
        return best;
    }

    /** How many of {@code itemId} fit on the Agent's stacks of it and in its free Use slots but the last one. */
    private static int cureRoom(Character bot, int itemId, InventoryGateway inventory) {
        long room = stackRoom(bot, itemId, inventory)
                + (long) Math.max(0, freeUseSlots(bot) - 1) * Math.max(1, inventory.getSlotMax(bot, itemId));
        return (int) Math.min(Integer.MAX_VALUE, room);
    }

    /**
     * A readiness trip's last step: {@link #buyReadinessAmmo} on toward {@code buyTarget} with what is left, counted
     * as bought but never said, and no shortfall of any kind when it stops short (headroom is a bonus).
     */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> readinessAmmoHeadroomStep(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop, Character bot, WeaponType wt,
            int buyTarget, int minimumMesoReserve) {
        AgentShopBuyReport report = buyReadinessAmmoHeadroom(bot, shop, wt, sequence.inventory(), buyTarget,
                minimumMesoReserve);
        if (report.quantity() > 0) {
            log.info("Agent '{}' readiness ammo headroom: {} x{} ({}), now {}", bot.getName(), report.itemId(),
                    report.quantity(), report.reason(), AgentCombatAmmoCounter.countCarried(bot, wt));
        }
        return sequence.withUnspoken(report.quantity());
    }

    /**
     * Tops the projectiles for {@code wt} in the bag up to {@code target} at any shop. A claw or gun first refills
     * every partial star or bullet stack it owns ({@link #refillableStacks}: any id, best first; every shop recharges),
     * then buys sets of this shop's cheapest star or bullet one at a time; a bow or crossbow buys this shop's cheapest
     * arrows or bolts. It never fills the last free Use slot (a party quest keeps it for its items and rewards), and
     * reports a NO_SPACE shortfall when that stops it short. The report: the last item touched, the projectiles
     * gained, the ones wanted, the first shortfall; empty when nothing is wanted or this shop sells none.
     */
    static AgentShopBuyReport buyReadinessAmmo(Character bot, Shop shop, WeaponType wt, InventoryGateway inventory,
                                               int target, int minimumMesoReserve) {
        return buyReadinessAmmo(bot, shop, wt, inventory, target, minimumMesoReserve, false);
    }

    /**
     * {@link #buyReadinessAmmo} toward a readiness trip's headroom ({@link #readinessAmmoBuyTarget}), which it never
     * passes: a refill or a set only while the whole of it still fits under {@code target}. A set is a whole stack, so
     * buying sets until the count reached the target took claws and guns from the standard's 2,100 to 4,000 for a
     * 3,200 buy target (live 2026-10-08/09: Kestrel, Sorrel, Flint); arrows were bought to the count already.
     */
    static AgentShopBuyReport buyReadinessAmmoHeadroom(Character bot, Shop shop, WeaponType wt,
                                                       InventoryGateway inventory, int target, int minimumMesoReserve) {
        return buyReadinessAmmo(bot, shop, wt, inventory, target, minimumMesoReserve, true);
    }

    private static AgentShopBuyReport buyReadinessAmmo(Character bot, Shop shop, WeaponType wt,
                                                       InventoryGateway inventory, int target, int minimumMesoReserve,
                                                       boolean withinTarget) {
        AgentShopBuyReport none = new AgentShopBuyReport(0, 0, 0, AgentShopShortfallReason.NONE);
        if (bot == null || shop == null || wt == null || !AgentCombatAmmoCounter.isRangedAmmoWeapon(wt)
                || bot.getInventory(InventoryType.USE) == null) {
            return none;
        }
        int start = AgentCombatAmmoCounter.countCarried(bot, wt);
        if (target <= 0 || start >= target) {
            return none;
        }
        int itemId = 0;
        AgentShopShortfallReason reason = AgentShopShortfallReason.NONE;
        if (isRechargeWeaponType(wt)) {
            for (Item stack : refillableStacks(bot, wt, inventory)) {
                int carried = AgentCombatAmmoCounter.countCarried(bot, wt);
                if (carried >= target) {
                    break;
                }
                if (withinTarget && carried + inventory.getSlotMax(bot, stack.getItemId()) - stack.getQuantity() > target) {
                    continue;
                }
                itemId = stack.getItemId();
                Shop.TransactionResult result = AgentShopGatewayRuntime.shop().recharge(
                        bot, shop, stack.getPosition(), minimumMesoReserve);
                if (result != Shop.TransactionResult.SUCCESS) {
                    reason = shortfallOf(result);
                    break;
                }
            }
            ShopSlotItem set = reason == AgentShopShortfallReason.NONE ? findSeedRechargeAmmoItem(shop, wt) : null;
            if (set != null) {
                itemId = set.shopItem.getItemId();
                int perSet = inventory.getSlotMax(bot, itemId);
                int count = AgentCombatAmmoCounter.countCarried(bot, wt);
                while (count < target && !(withinTarget && count + perSet > target)) {
                    if (freeUseSlots(bot) <= 1) {
                        reason = AgentShopShortfallReason.NO_SPACE;
                        break;
                    }
                    AgentShopBuyReport bought = buyFixedCostItem(bot, shop, set, 1, 1, minimumMesoReserve);
                    if (bought.reason() != AgentShopShortfallReason.NONE || bought.quantity() <= 0) {
                        reason = bought.reason() == AgentShopShortfallReason.NONE
                                ? AgentShopShortfallReason.OTHER : bought.reason();
                        break;
                    }
                    int after = AgentCombatAmmoCounter.countCarried(bot, wt);
                    if (after <= count) {
                        break; // nothing arrived: never loop on a set that does not land
                    }
                    count = after;
                }
            }
        } else {
            ShopSlotItem arrows = findAmmoItem(shop, wt);
            if (arrows != null) {
                itemId = arrows.shopItem.getItemId();
                int want = target - start;
                int perSlot = inventory.getSlotMax(bot, itemId);
                long room = stackRoom(bot, itemId, inventory)
                        + (long) Math.max(0, freeUseSlots(bot) - 1) * Math.max(0, perSlot);
                int quantity = (int) Math.min(want, room);
                AgentShopBuyReport bought = buyFixedCostItem(bot, shop, arrows, quantity, 1000, minimumMesoReserve);
                reason = bought.reason() == AgentShopShortfallReason.NONE && quantity < want
                        ? AgentShopShortfallReason.NO_SPACE : bought.reason();
            }
        }
        int gained = Math.max(0, AgentCombatAmmoCounter.countCarried(bot, wt) - start);
        return new AgentShopBuyReport(itemId, gained, target - start, reason);
    }

    private static AgentShopShortfallReason shortfallOf(Shop.TransactionResult result) {
        return switch (result) {
            case NOT_ENOUGH_MESO -> AgentShopShortfallReason.NO_MESO;
            case NO_SPACE -> AgentShopShortfallReason.NO_SPACE;
            default -> AgentShopShortfallReason.OTHER;
        };
    }

    /**
     * What a readiness trip's visit to a shop does with ammo, from the bag as it is now: {@code projected}, the
     * projectiles for the weapon after the visit (Integer.MAX_VALUE for a weapon that shoots none); {@code spend},
     * what its readiness ammo step pays (the Victoria top-up before the potions is {@link #plannedAmmoSpend});
     * {@code newStacks}, the Use slots the visit's ammo opens, the Victoria top-up's included. No purse or slot limit:
     * the trip's checks compare these with the purse and the free slots.
     */
    public record ReadinessAmmoPlan(int projected, long spend, int newStacks) {
        static final ReadinessAmmoPlan NONE = new ReadinessAmmoPlan(Integer.MAX_VALUE, 0L, 0);
    }

    /**
     * {@link ReadinessAmmoPlan} for a readiness trip toward {@code activity}'s ammo target at the supplier on
     * {@code supplierMapId} (a Victoria one runs the old top-up first).
     */
    public static ReadinessAmmoPlan readinessAmmoPlan(Character bot, Shop shop, int supplierMapId,
                                                      AgentSupplyReadiness.Activity activity) {
        if (bot == null || shop == null) {
            return ReadinessAmmoPlan.NONE;
        }
        return readinessAmmoPlan(bot, shop, AgentAttackExecutionProvider.getEquippedWeaponType(bot),
                AgentInventoryGatewayRuntime.inventory(),
                itemId -> ItemInformationProvider.getInstance().getUnitPrice(itemId),
                AgentPotionSupplierCatalog.ammoTopUpAllowedAt(supplierMapId), AgentSupplyReadiness.ammoTarget(activity));
    }

    /**
     * The projection itself, step for step as the counter runs them: on Victoria the seed set, the refills of the
     * best stacks and the arrows (with the predicates {@link #purchaseActions} builds them by), then
     * {@link #buyReadinessAmmo}'s refills, sets or arrows toward {@code target}.
     */
    static ReadinessAmmoPlan readinessAmmoPlan(Character bot, Shop shop, WeaponType wt, InventoryGateway inventory,
                                               IntToDoubleFunction unitPrice, boolean victoria, int target) {
        if (wt == null || !AgentCombatAmmoCounter.isRangedAmmoWeapon(wt)
                || bot.getInventory(InventoryType.USE) == null) {
            return ReadinessAmmoPlan.NONE;
        }
        long count = AgentCombatAmmoCounter.countCarried(bot, wt);
        long spend = 0L;
        int newStacks = 0;
        List<Item> refillable = new ArrayList<>(refillableStacks(bot, wt, inventory));
        Map<Integer, Long> roomOf = new HashMap<>();
        if (victoria) {
            if (shouldBuySeedRechargeAmmoWhileShopping(bot, wt)) {
                ShopSlotItem seed = findSeedRechargeAmmoItem(shop, wt);
                if (seed != null) {
                    count += Math.max(0, inventory.getSlotMax(bot, seed.shopItem.getItemId()));
                    newStacks++; // a full stack of its own: nothing to refill
                }
            }
            if (shouldRechargeWhileShopping(bot, wt, inventory)) {
                for (int sets = 0; sets < RECHARGE_MAX_SETS && !refillable.isEmpty(); sets++) {
                    Item stack = refillable.remove(0);
                    count += Math.max(0, inventory.getSlotMax(bot, stack.getItemId()) - stack.getQuantity());
                }
            }
            if (shouldBuyFixedAmmoWhileShopping(bot, wt)) {
                ShopSlotItem ammo = findAmmoItem(shop, wt);
                int missing = Math.max(0, ammoTargetThreshold() - AgentCombatAmmoCounter.countAmmo(bot, wt));
                if (ammo != null && missing > 0) {
                    newStacks += stacksBeyondRoom(bot, ammo, missing, inventory, roomOf);
                    count += missing;
                }
            }
        }
        if (target > 0 && count < target) {
            if (isRechargeWeaponType(wt)) {
                for (Item stack : refillable) {
                    if (count >= target) {
                        break;
                    }
                    int missing = Math.max(0, inventory.getSlotMax(bot, stack.getItemId()) - stack.getQuantity());
                    count += missing;
                    spend += (long) Math.ceil(unitPrice.applyAsDouble(stack.getItemId()) * missing);
                }
                ShopSlotItem set = findSeedRechargeAmmoItem(shop, wt);
                int perSet = set == null ? 0 : inventory.getSlotMax(bot, set.shopItem.getItemId());
                while (perSet > 0 && count < target) {
                    count += perSet;
                    spend += set.shopItem.getPrice();
                    newStacks++;
                }
            } else {
                ShopSlotItem arrows = findAmmoItem(shop, wt);
                if (arrows != null) {
                    int want = (int) (target - count);
                    newStacks += stacksBeyondRoom(bot, arrows, want, inventory, roomOf);
                    spend += (long) arrows.shopItem.getPrice() * want;
                    count += want;
                }
            }
        }
        return new ReadinessAmmoPlan((int) Math.min(Integer.MAX_VALUE, count), spend, newStacks);
    }

    /**
     * The new Use slots {@code quantity} of {@code item} take beyond the room its carried stacks have left;
     * {@code roomOf} keeps that room across the visit's steps.
     */
    private static int stacksBeyondRoom(Character bot, ShopSlotItem item, int quantity, InventoryGateway inventory,
                                        Map<Integer, Long> roomOf) {
        int itemId = item.shopItem.getItemId();
        int perSlot = inventory.getSlotMax(bot, itemId);
        long room = roomOf.computeIfAbsent(itemId, id -> (long) stackRoom(bot, id, inventory));
        long beyond = Math.max(0L, quantity - room);
        int stacks = perSlot <= 0 ? 0 : (int) ((beyond + perSlot - 1) / perSlot);
        roomOf.put(itemId, Math.max(0L, room - quantity) + (long) stacks * Math.max(0, perSlot) - beyond);
        return stacks;
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

    /**
     * Use room for the potions a visit buys to its reserve bars: with fewer free slots than they take
     * ({@link #potionSlotsNeeded}), it sells one stack of ammo for a weapon it does not wield
     * (AgentInventorySellTrashService.collectUnusableAmmo) per slot missing, and nothing else. Found live 2026-10-13:
     * the Spearman Demon744 kept 8 Unagi in the last slot of a Use tab of 96 holding some 20 stacks of looted arrows,
     * so Perion's shop refused its White Potions (NO_SPACE) on every visit, and the trip was given up and taken again
     * every ten minutes while its potions ran out.
     */
    static AgentShopPurchaseSequence<AgentRuntimeEntry> makeUseRoomForPotions(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop, WeaponType wt) {
        Character bot = sequence.bot();
        return makeUseRoom(sequence, shop, wt, potionSlotsNeeded(bot, shop, sequence.inventory()), "potions");
    }

    /**
     * {@link #makeUseRoomForPotions} for a readiness trip: room for all it buys, as the trip's checks counted it
     * (AgentSupplyReadinessRuntime.assess counts these stacks as room): the potion standard's shortfall a stack of
     * {@link #READINESS_POTIONS_PER_STACK} at a time, the trip's ammo and seal cure stacks, and the run's slot, or the
     * reserve bars' slots when those take more. Found live 2026-10-14 (P18): the Crusader Gunnar carried 51 stacks of
     * looted arrows and 24 of bullets in a full Use tab, so every Horntail restock stayed put on NO_SPACE.
     */
    static AgentShopPurchaseSequence<AgentRuntimeEntry> makeUseRoomForReadiness(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop, WeaponType wt,
            AgentSupplyReadiness.Activity trip) {
        Character bot = sequence.bot();
        AgentSupplyReadiness.Shortfall shortfall = AgentSupplyReadiness.shortfall(bot, trip);
        // a points standard's shortfall is HP and MP points, in potions only as the visit's pick buys them (as the
        // trip's checks count it): read as potions, live 10-29 Balrog readiness trips "needed" 49-1,192 free Use slots
        boolean points = AgentSupplyReadiness.recoveryStandard(trip) != null;
        int hpPotions = points ? AgentSupplyReadiness.potionsFor(shortfall.hp(), coveragePickRecovery(bot, shop, true))
                : shortfall.hp();
        int mpPotions = points ? AgentSupplyReadiness.potionsFor(shortfall.mp(), coveragePickRecovery(bot, shop, false))
                : shortfall.mp();
        int wanted = stacks(hpPotions) + stacks(mpPotions)
                + readinessAmmoPlan(bot, shop, bot.getMapId(), trip).newStacks()
                + readinessCurePlan(bot, shop, trip).newStacks() + 1;
        return makeUseRoom(sequence, shop, wt,
                Math.max(wanted, potionSlotsNeeded(bot, shop, sequence.inventory())), "a readiness trip");
    }

    /** What a readiness trip's room counts one potion stack as: the smallest stack a counter potion fills. */
    static final int READINESS_POTIONS_PER_STACK = 100;

    private static int stacks(int potions) {
        return potions <= 0 ? 0 : (potions + READINESS_POTIONS_PER_STACK - 1) / READINESS_POTIONS_PER_STACK;
    }

    /**
     * Stacks of ammo for a weapon {@code bot} does not wield: the Use room a visit can make
     * ({@link #makeUseRoom}), which the readiness checks count beside the free slots.
     */
    public static int sellableAmmoStacks(AgentRuntimeEntry entry, Character bot) {
        return AgentInventorySellTrashService.collectUnusableAmmo(entry, bot,
                AgentAttackExecutionProvider.getEquippedWeaponType(bot), AgentInventoryGatewayRuntime.inventory()).size();
    }

    /** Sells one stack of ammo for a weapon it does not wield per Use slot it lacks of {@code slotsWanted}. */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> makeUseRoom(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop, WeaponType wt, int slotsWanted,
            String forWhat) {
        Character bot = sequence.bot();
        int missing = slotsWanted - freeUseSlots(bot);
        if (missing <= 0) {
            return sequence;
        }
        List<Integer> sold = new ArrayList<>();
        for (Item item : AgentInventorySellTrashService.collectUnusableAmmo(
                sequence.entry(), bot, wt, sequence.inventory())) {
            if (sold.size() >= missing) {
                break;
            }
            AgentShopGatewayRuntime.shop().sell(bot, shop, InventoryType.USE, item.getPosition(), item.getQuantity());
            if (!AgentInventoryItemPolicy.hasItem(bot, item)) {
                sold.add(item.getItemId());
            }
        }
        log.info("Agent '{}' needs {} more free Use slot(s) for {} on map {}: sold ammo it does not use {}",
                bot.getName(), missing, forWhat, bot.getMapId(), sold);
        return sequence;
    }

    /**
     * The new Use slots a visit's reserve-bar potions take: per kind, what {@link #plannedPotionQuantity} plans of the
     * coverage pick ({@link #findPotionItem}) past the room of the stacks of it carried, in whole stacks.
     */
    private static int potionSlotsNeeded(Character bot, Shop shop, InventoryGateway inventory) {
        int slots = 0;
        for (boolean forHp : new boolean[]{true, false}) {
            ShopSlotItem potion = findPotionItem(shop, bot, forHp);
            if (!needsPotionTarget(bot, potion, forHp)) {
                continue;
            }
            int itemId = potion.shopItem.getItemId();
            int beyond = plannedPotionQuantity(bot, potion, forHp) - stackRoom(bot, itemId, inventory);
            if (beyond > 0) {
                int perSlot = Math.max(1, inventory.getSlotMax(bot, itemId));
                slots += (beyond + perSlot - 1) / perSlot;
            }
        }
        return slots;
    }

    /** A visit's potions in one step: {@link #buyPotionStandard}, then {@link #buyPotionExtras}. */
    static AgentShopPurchaseSequence<AgentRuntimeEntry> buyPotionStock(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Shop shop,
            int minimumMesoReserve) {
        int[] bought = {0, 0};
        return buyPotionExtras(buyPotionStandard(sequence, shop, minimumMesoReserve, bought), shop,
                minimumMesoReserve, bought);
    }

    /**
     * The first part of a visit's potions: a standing readiness duty's standard ({@link #buyReadinessFloor}), the
     * purchase intent lines, then the critical reserve-bar passes. {@code bought} counts what it buys per kind (HP,
     * MP) for the per-visit limit {@link #buyPotionExtras} goes on with.
     */
    static AgentShopPurchaseSequence<AgentRuntimeEntry> buyPotionStandard(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Shop shop,
            int minimumMesoReserve,
            int[] bought) {
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
            String intent = potionPurchaseIntent(bot, hpPotion, true, sequence.inventory(),
                    reserveRoomBesideTheRunsSlot(sequence, shop, hpPotion, true));
            if (intent != null) {
                AgentShopRuntime.sayMapNow(bot, intent);
            }
        }
        if (needsMp) {
            String intent = potionPurchaseIntent(bot, mpPotion, false, sequence.inventory(),
                    reserveRoomBesideTheRunsSlot(sequence, shop, mpPotion, false));
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
                Integer.MAX_VALUE,
                reserveRoomBesideTheRunsSlot(sequence, shop, hpPotion, true));
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
                Integer.MAX_VALUE,
                reserveRoomBesideTheRunsSlot(sequence, shop, mpPotion, false));
        mpBought += report.quantity();
        bought[0] += hpBought;
        bought[1] += mpBought;
        return appendBuyReport(current, report, "critical MP pots");
    }

    /**
     * The rest of a visit's potions, after {@link #buyPotionStandard} (and on a readiness trip after its ammo step):
     * the ordinary reserve-bar passes, then with what is left a duty holder's top-up toward its buy target
     * (BUY_TO_PERCENT of the standard). {@code bought}: this visit's purchases per kind (HP, MP) so far, kept up. On a
     * readiness trip what these passes cannot buy is a quiet shortfall: the trip already met what it went for.
     */
    static AgentShopPurchaseSequence<AgentRuntimeEntry> buyPotionExtras(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Shop shop,
            int minimumMesoReserve,
            int[] bought) {
        Character bot = sequence.bot();
        ShopSlotItem hpPotion = findPotionItem(shop, bot, true);
        ShopSlotItem mpPotion = findPotionItem(shop, bot, false);
        int hpBought = bought[0];
        int mpBought = bought[1];
        AgentShopPurchaseSequence<AgentRuntimeEntry> current = sequence;
        boolean quiet = AgentSupplyReadiness.tripActivity(sequence.entry()) != null;
        boolean mpNeedsNormalStock = needsPotionTarget(bot, mpPotion, false);
        int spendable = Math.max(0, bot.getMeso() - Math.max(0, minimumMesoReserve));
        int hpBudget = AgentPotionPurchasePolicy.normalSpendBudget(
                spendable, mpNeedsNormalStock);
        AgentShopBuyReport report = buyPotionToReserve(
                bot,
                shop,
                hpPotion,
                true,
                AgentPotionPurchasePolicy.targetReserveBars(true),
                hpBought,
                true,
                minimumMesoReserve,
                hpBudget,
                reserveRoomBesideTheRunsSlot(sequence, shop, hpPotion, true));
        hpBought += report.quantity();
        current = appendBuyReport(current, report, "HP pots", quiet);

        report = buyPotionToReserve(
                bot,
                shop,
                mpPotion,
                false,
                AgentPotionPurchasePolicy.targetReserveBars(false),
                mpBought,
                true,
                minimumMesoReserve,
                Integer.MAX_VALUE,
                reserveRoomBesideTheRunsSlot(sequence, shop, mpPotion, false));
        mpBought += report.quantity();
        current = appendBuyReport(current, report, "MP pots", quiet);
        bought[0] = hpBought;
        bought[1] = mpBought;
        // Last, with what is left, a duty holder tops up toward its buy target (BUY_TO_PERCENT of the standard).
        return buyReadinessFloor(current, shop, hpPotion, mpPotion, minimumMesoReserve, bought, true);
    }

    /**
     * The readiness floor of a standing duty (AgentSupplyReadiness), in two passes around the reserve-bar passes.
     * First ({@code topUp} false) each kind up to the activity's standard, by count: HP first, within the purse less
     * what the mana potions it lacks of the standard cost at their cheapest, then MP, so a purse that covers the
     * standard always reaches it. Last ({@code topUp} true), with what is left, toward the buy target
     * (AgentSupplyReadiness.buyTarget), again holding back what the other kind still wants, buying only what the
     * budget pays for. {@link #floorItem} picks the potion. Nothing without a duty; {@code bought} counts this visit's
     * purchases per kind (HP, MP) against the per-visit limit. A standard above the carry or per-visit limit raises
     * that limit to itself (AgentPotionPurchasePolicy.floorQuantity), so one visit reaches it, and nothing buys
     * past the larger of it and MAX_CARRIED_QUANTITY. A standard of HP and MP points (a boss's,
     * AgentSupplyReadiness.recoveryStandard) buys in its own passes ({@link #buyRecoveryFloor}).
     */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> buyReadinessFloor(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Shop shop,
            ShopSlotItem hpPick,
            ShopSlotItem mpPick,
            int minimumMesoReserve,
            int[] bought,
            boolean topUp) {
        // the trip's own activity when the duty cannot be read at the counter (travel cleared its follow target)
        return buyReadinessFloor(sequence, shop, hpPick, mpPick, minimumMesoReserve, bought, topUp,
                AgentSupplyReadiness.shoppingActivity(sequence.entry(), sequence.bot()));
    }

    /**
     * {@link #buyReadinessFloor} for {@code activity} with the coverage picks a visit makes at {@code shop}
     * ({@link #findPotionItem}): a counter with no visit under way (AgentShopProvisioning).
     */
    static AgentShopPurchaseSequence<AgentRuntimeEntry> buyReadinessFloor(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Shop shop,
            AgentSupplyReadiness.Activity activity,
            int minimumMesoReserve,
            int[] bought,
            boolean topUp) {
        Character bot = sequence.bot();
        return buyReadinessFloor(sequence, shop, findPotionItem(shop, bot, true), findPotionItem(shop, bot, false),
                minimumMesoReserve, bought, topUp, activity);
    }

    /** {@link #buyReadinessFloor} for {@code activity}; nothing when it is null. */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> buyReadinessFloor(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Shop shop,
            ShopSlotItem hpPick,
            ShopSlotItem mpPick,
            int minimumMesoReserve,
            int[] bought,
            boolean topUp,
            AgentSupplyReadiness.Activity activity) {
        Character bot = sequence.bot();
        if (activity == null) {
            return sequence;
        }
        AgentSupplyReadiness.RecoveryStandard points = AgentSupplyReadiness.recoveryStandard(activity);
        if (points != null) {
            return buyRecoveryFloor(sequence, shop, hpPick, mpPick, minimumMesoReserve, bought, topUp, activity,
                    points);
        }
        AgentSupplyReadiness.Standard standard = AgentSupplyReadiness.standard(activity);
        int hpGoal = topUp ? AgentSupplyReadiness.buyTarget(activity, bot, true) : standard.hp();
        int mpGoal = !AgentSupplyReadiness.needsManaPotions(bot) ? 0
                : topUp ? AgentSupplyReadiness.buyTarget(activity, bot, false) : standard.mp();
        int[] carried = AgentSupplyReadiness.countFor(bot, activity);
        AgentSupplyReadiness.PotionFloor floor = AgentSupplyReadiness.potionFloor(activity);
        int hpFloorBps = floor == null ? 0 : floor.hpBps();
        int mpFloorBps = floor == null ? 0 : floor.mpBps();
        int hpWant = AgentPotionPurchasePolicy.floorQuantity(hpGoal, carried[0], bought[0], standard.hp());
        int mpWant = AgentPotionPurchasePolicy.floorQuantity(mpGoal, carried[1], bought[1], standard.mp());
        AgentShopPurchaseSequence<AgentRuntimeEntry> current = sequence;
        if (hpWant > 0) {
            long heldForMana = mpWant <= 0 ? 0L
                    : (long) mpWant * Math.max(0, cheapestRecoveryPrice(bot, shop, false, mpFloorBps));
            current = buyFloor(current, shop, hpPick, true, hpWant,
                    Math.max(0L, spendable(bot, minimumMesoReserve) - heldForMana), minimumMesoReserve, bought, topUp,
                    standard.hp(), hpFloorBps);
        }
        if (mpWant > 0) {
            current = buyFloor(current, shop, mpPick, false, mpWant, spendable(bot, minimumMesoReserve),
                    minimumMesoReserve, bought, topUp, standard.mp(), mpFloorBps);
        }
        return current;
    }

    /**
     * One kind of the floor: {@code want} of the potion {@link #floorItem} picks within {@code budget}; a top-up buys
     * only what the budget pays for, so a short purse is no shortfall to report. Never past the room that leaves the
     * Use slot a party quest keeps free ({@link #roomBesideTheRunsSlot}); a standard pass held back by it says its
     * bag is full. {@code floorBps}: the standard's potion floor for the kind (0 for none).
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
            boolean topUp,
            int standard,
            int floorBps) {
        Character bot = current.bot();
        ShopSlotItem item = floorItem(shop, bot, pick, forHp, want, budget, floorBps, current.inventory());
        if (item == null) {
            return current;
        }
        return buyFloorItem(current, shop, item, forHp, want, budget, minimumMesoReserve, bought, topUp, standard);
    }

    /**
     * {@link #buyFloor} once its potion is picked: {@code want} of {@code item} within {@code budget}, a top-up only
     * what the budget pays for, never past the room beside the run's slot ({@code standard}: the kind's standard in
     * potions of it).
     */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> buyFloorItem(
            AgentShopPurchaseSequence<AgentRuntimeEntry> current,
            Shop shop,
            ShopSlotItem item,
            boolean forHp,
            int want,
            long budget,
            int minimumMesoReserve,
            int[] bought,
            boolean topUp,
            int standard) {
        Character bot = current.bot();
        int quantity = want;
        if (topUp) {
            int price = item.shopItem.getPrice();
            quantity = price <= 0 ? 0 : (int) Math.min(want, budget / price);
            if (quantity <= 0) {
                return current;
            }
        }
        int room = roomBesideTheRunsSlot(bot, item, standard, current.inventory());
        AgentShopBuyReport report = buyFixedCostItem(bot, shop, item, Math.min(quantity, room), 100,
                minimumMesoReserve, (int) Math.min(Integer.MAX_VALUE, budget));
        if (!topUp && room < quantity && report.reason() == AgentShopShortfallReason.NONE) {
            report = new AgentShopBuyReport(item.shopItem.getItemId(), report.quantity(), quantity,
                    AgentShopShortfallReason.NO_SPACE);
        }
        bought[forHp ? 0 : 1] += report.quantity();
        return appendBuyReport(current, report, forHp ? "HP pots" : "MP pots");
    }

    /**
     * The readiness floor of a standard of HP and MP points (AgentSupplyReadiness.recoveryStandard, a boss's), in the
     * same two passes: each kind's points gap, to the standard or topping up toward its buy target
     * (AgentSupplyReadiness.recoveryBuyTarget), turned into a count of the potion the visit picks for that kind
     * ({@link #recoveryFloorItem}), rounded up ({@link #recoveryWant}). HP first, within the purse less what the mana
     * gap costs in its pick, then MP. Within the floor's limits: a limit or a purse that stops it short leaves it
     * short, and the trip's checks and its host's lines say so.
     */
    private static AgentShopPurchaseSequence<AgentRuntimeEntry> buyRecoveryFloor(
            AgentShopPurchaseSequence<AgentRuntimeEntry> sequence,
            Shop shop,
            ShopSlotItem hpPick,
            ShopSlotItem mpPick,
            int minimumMesoReserve,
            int[] bought,
            boolean topUp,
            AgentSupplyReadiness.Activity activity,
            AgentSupplyReadiness.RecoveryStandard points) {
        Character bot = sequence.bot();
        int[] carried = AgentPotionService.countPotions(bot);
        int mpWant = !AgentSupplyReadiness.needsManaPotions(bot) ? 0
                : recoveryWant(bot, activity, points, mpPick, false, carried[1], bought[1], topUp);
        AgentShopPurchaseSequence<AgentRuntimeEntry> current = sequence;
        ShopSlotItem hpItem = recoveryFloorItem(shop, bot, hpPick, true, current.inventory());
        int hpWant = recoveryWant(bot, activity, points, hpItem, true, carried[0], bought[0], topUp);
        if (hpWant > 0) {
            long heldForMana = mpWant <= 0 ? 0L : (long) mpWant * mpPick.shopItem.getPrice();
            current = buyFloorItem(current, shop, hpItem, true, hpWant,
                    Math.max(0L, spendable(bot, minimumMesoReserve) - heldForMana), minimumMesoReserve, bought, topUp,
                    AgentSupplyReadiness.potionsFor(points.hp(), hpItem.recovery.primary()));
        }
        if (mpWant > 0) {
            ShopSlotItem mpItem = recoveryFloorItem(shop, bot, mpPick, false, current.inventory());
            int want = recoveryWant(bot, activity, points, mpItem, false, carried[1], bought[1], topUp);
            if (want > 0) {
                current = buyFloorItem(current, shop, mpItem, false, want, spendable(bot, minimumMesoReserve),
                        minimumMesoReserve, bought, topUp,
                        AgentSupplyReadiness.potionsFor(points.mp(), mpItem.recovery.primary()));
            }
        }
        return current;
    }

    /**
     * How many of {@code item} a points floor pass buys: what the kind's recovery
     * (AgentSupplyReadiness.recoveryCarried) lacks of the standard, or topping up of its buy target, in that potion,
     * rounded up; within AgentPotionPurchasePolicy.floorQuantity, with the bag's count at the standard
     * ({@code carried} and what the standard still lacks in that potion) as the standard, so one visit reaches it past
     * the carry and per-visit limits while a top-up stays within them. 0 without a potion.
     */
    private static int recoveryWant(Character bot, AgentSupplyReadiness.Activity activity,
                                    AgentSupplyReadiness.RecoveryStandard points, ShopSlotItem item, boolean forHp,
                                    int carried, int boughtThisVisit, boolean topUp) {
        if (item == null || item.recovery == null) {
            return 0;
        }
        int perPotion = item.recovery.primary();
        long recovery = AgentSupplyReadiness.recoveryCarried(bot, forHp);
        long standard = forHp ? points.hp() : points.mp();
        long goal = topUp ? AgentSupplyReadiness.recoveryBuyTarget(activity, bot, forHp) : standard;
        long atStandard = (long) Math.max(0, carried) + AgentSupplyReadiness.potionsFor(standard - recovery, perPotion);
        long atGoal = (long) Math.max(0, carried) + AgentSupplyReadiness.potionsFor(goal - recovery, perPotion);
        return AgentPotionPurchasePolicy.floorQuantity((int) Math.min(Integer.MAX_VALUE, atGoal), carried,
                boughtThisVisit, (int) Math.min(Integer.MAX_VALUE, atStandard));
    }

    /**
     * The potion a points floor pass buys: the visit's pick for the kind ({@link #findPotionItem}), the one its gap is
     * counted in; with at most one Use slot free and no room on its stacks, a stack of the kind it carries that this
     * shop sells ({@link #floorItem}'s rule), so the slot a run keeps stays free. Null when the shop sells none.
     */
    private static ShopSlotItem recoveryFloorItem(Shop shop, Character bot, ShopSlotItem pick, boolean forHp,
                                                  InventoryGateway inventory) {
        if (pick == null || freeUseSlots(bot) > 1 || stackRoom(bot, pick.shopItem.getItemId(), inventory) > 0) {
            return pick;
        }
        ShopSlotItem carried = carriedStack(shop, bot, forHp, inventory);
        return carried != null ? carried : pick;
    }

    /**
     * The potion a points floor pass buys at the last free Use slot ({@link #recoveryFloorItem}): {@code recovery},
     * what one of it restores for the bot, the potion the pass counts the gap in; {@code room}, the places left on the
     * stacks of it carried, all the pass may buy of it beside the run's slot (AgentSupplyReadinessRuntime.useSlotsNeeded
     * counts the gap in the same potion).
     */
    public record RecoveryFloorStack(int recovery, int room) {
    }

    /**
     * {@link RecoveryFloorStack} of the HP (or MP) potion a points floor pass at {@code shop} buys with at most one Use
     * slot free: the coverage pick, or with no room on its stacks the strongest carried stack of the kind this shop
     * sells that has room. Null when the shop sells no potion of the kind.
     */
    public static RecoveryFloorStack recoveryFloorStack(Character bot, Shop shop, boolean forHp) {
        if (bot == null || shop == null) {
            return null;
        }
        InventoryGateway inventory = AgentInventoryGatewayRuntime.inventory();
        ShopSlotItem item = recoveryFloorItem(shop, bot, findPotionItem(shop, bot, forHp), forHp, inventory);
        if (item == null || item.recovery == null || item.recovery.primary() <= 0) {
            return null;
        }
        return new RecoveryFloorStack(item.recovery.primary(), stackRoom(bot, item.shopItem.getItemId(), inventory));
    }

    /**
     * How many of {@code item} a floor pass may buy and still leave free the Use slot a party quest keeps for its
     * items and rewards. A {@code standard} of at most one stack: no limit ({@link #floorItem} keeps that slot). A
     * larger one: the room of the stacks of it carried, and a full stack in every free slot but one (the slots its
     * readiness trip was let go for, AgentSupplyReadinessRuntime.useSlotsNeeded). Unknown stack size: no limit.
     */
    private static int roomBesideTheRunsSlot(Character bot, ShopSlotItem item, int standard,
                                             InventoryGateway inventory) {
        int perSlot = inventory.getSlotMax(bot, item.shopItem.getItemId());
        if (perSlot <= 0 || standard <= perSlot) {
            return Integer.MAX_VALUE;
        }
        long room = stackRoom(bot, item.shopItem.getItemId(), inventory)
                + (long) Math.max(0, freeUseSlots(bot) - 1) * perSlot;
        return (int) Math.min(Integer.MAX_VALUE, room);
    }

    /**
     * How many of {@code potion} a reserve-bar pass of buyPotionStock may buy and still leave free the Use slot a
     * party quest keeps (the floor's rule, {@link #roomBesideTheRunsSlot}): limited only under a duty whose standard
     * of the kind is above one stack, so a floor pass held back by that slot is not undone by the passes after it.
     * No duty, or a standard of at most one stack: no limit. A points standard counts in the potions of this shop
     * ({@link #readinessStandard}).
     */
    private static int reserveRoomBesideTheRunsSlot(AgentShopPurchaseSequence<AgentRuntimeEntry> sequence, Shop shop,
                                                    ShopSlotItem potion, boolean forHp) {
        if (potion == null) {
            return Integer.MAX_VALUE;
        }
        Character bot = sequence.bot();
        AgentSupplyReadiness.Activity activity = AgentSupplyReadiness.shoppingActivity(sequence.entry(), bot);
        if (activity == null) {
            return Integer.MAX_VALUE;
        }
        AgentSupplyReadiness.Standard standard = readinessStandard(bot, shop, activity);
        return roomBesideTheRunsSlot(bot, potion, forHp ? standard.hp() : standard.mp(), sequence.inventory());
    }

    /**
     * The potion a floor pass buys {@code want} of within {@code budget} (AgentShopPotionPolicy.selectForCount; with a
     * potion floor, AgentShopPotionPolicy.selectAtFloor). With at most one Use slot free, a new kind of potion would
     * take the last slot (a party quest keeps one free for its items and rewards), so it buys onto a stack of this kind
     * it already carries, when this shop sells one that meets the floor.
     */
    private static ShopSlotItem floorItem(Shop shop, Character bot, ShopSlotItem pick, boolean forHp, int want,
                                          long budget, int floorBps, InventoryGateway inventory) {
        ShopSlotItem selected = floorBps > 0 ? selectAtFloor(shop, bot, forHp, want, budget, floorBps)
                : selectForCount(shop, bot, pick, forHp, want, budget);
        if (selected == null || freeUseSlots(bot) > 1
                || stackRoom(bot, selected.shopItem.getItemId(), inventory) > 0) {
            return selected;
        }
        ShopSlotItem carried = carriedStack(shop, bot, forHp, inventory);
        return carried != null && AgentPotionRecoveryPolicy.meetsFloor(carried.recovery,
                forHp ? bot.getCurrentMaxHp() : bot.getCurrentMaxMp(), floorBps) ? carried : selected;
    }

    /**
     * True when the bot carries a stack of an HP (or MP) recovery potion this shop sells that has room left, so a
     * purchase of it needs no new Use slot.
     */
    public static boolean carriesStackWithRoom(Character bot, Shop shop, boolean forHp) {
        return bot != null && shop != null
                && carriedStack(shop, bot, forHp, AgentInventoryGatewayRuntime.inventory()) != null;
    }

    /**
     * How many HP (or MP) potions one Use slot holds at this shop: the smallest stack of the recovery potions of the
     * kind it sells, since a floor pass may buy another one than the coverage pick; 0 when it sells none.
     */
    public static int potionStackSize(Character bot, Shop shop, boolean forHp) {
        return bot == null || shop == null ? 0
                : potionStackSize(bot, shop, forHp, AgentInventoryGatewayRuntime.inventory());
    }

    private static int potionStackSize(Character bot, Shop shop, boolean forHp, InventoryGateway inventory) {
        int smallest = 0;
        for (ShopItem item : shop.getItems()) {
            if (item.getPrice() <= 0 || !AgentUseItemClassificationPolicy.isRecoveryPotion(item.getItemId())) {
                continue;
            }
            StatEffect effect = AgentUseItemClassificationPolicy.itemEffect(item.getItemId());
            if (effect == null || AgentPotionRecoveryPolicy.recovery(
                    effect, bot.getCurrentMaxHp(), bot.getCurrentMaxMp(), forHp) == null) {
                continue;
            }
            int stack = inventory.getSlotMax(bot, item.getItemId());
            if (stack > 0 && (smallest == 0 || stack < smallest)) {
                smallest = stack;
            }
        }
        return smallest;
    }

    /**
     * How many HP (or MP) potions a purchase at the last free Use slot surely fits on a carried stack: the least room
     * among the recovery potions of the kind this shop sells of which the bot carries a stack with room, since
     * {@link #floorItem} may buy onto any of them; 0 when it carries none.
     */
    public static int carriedStackRoom(Character bot, Shop shop, boolean forHp) {
        if (bot == null || shop == null || bot.getInventory(InventoryType.USE) == null) {
            return 0;
        }
        InventoryGateway inventory = AgentInventoryGatewayRuntime.inventory();
        int least = 0;
        for (ShopItem item : shop.getItems()) {
            if (item.getPrice() <= 0 || !AgentUseItemClassificationPolicy.isRecoveryPotion(item.getItemId())) {
                continue;
            }
            int room = stackRoom(bot, item.getItemId(), inventory);
            StatEffect effect = room <= 0 ? null : AgentUseItemClassificationPolicy.itemEffect(item.getItemId());
            if (effect != null && AgentPotionRecoveryPolicy.recovery(
                    effect, bot.getCurrentMaxHp(), bot.getCurrentMaxMp(), forHp) != null
                    && (least == 0 || room < least)) {
                least = room;
            }
        }
        return least;
    }

    /**
     * How many of {@code want} HP (or MP) potions a readiness floor pass surely puts on a carried stack: the room of
     * the stacks of the coverage pick ({@link #findPotionItem}) carried, when {@code want} of it fit {@code budget}
     * (AgentShopPotionPolicy.selectForCount then always buys it, and the shop fills a carried stack before it takes a
     * new Use slot); 0 when they do not fit (the potion it buys then depends on the budget), or the shop sells none.
     */
    public static int readinessPickRoom(Character bot, Shop shop, boolean forHp, int want, long budget) {
        if (bot == null || shop == null || want <= 0) {
            return 0;
        }
        ShopSlotItem pick = findPotionItem(shop, bot, forHp);
        if (pick == null || (long) pick.shopItem.getPrice() * want > budget) {
            return 0;
        }
        return stackRoom(bot, pick.shopItem.getItemId(), AgentInventoryGatewayRuntime.inventory());
    }

    /** The price of the HP (or MP) potion a visit picks for coverage ({@link #findPotionItem}); -1 when it sells none. */
    public static int coveragePickPrice(Character bot, Shop shop, boolean forHp) {
        if (bot == null || shop == null) {
            return -1;
        }
        ShopSlotItem pick = findPotionItem(shop, bot, forHp);
        return pick == null ? -1 : pick.shopItem.getPrice();
    }

    /**
     * What one of the HP (or MP) potion a visit picks for coverage ({@link #findPotionItem}) restores for the bot: the
     * potion a points standard's gap is counted in (AgentSupplyReadiness.potionsFor); 0 when the shop sells none.
     */
    public static int coveragePickRecovery(Character bot, Shop shop, boolean forHp) {
        if (bot == null || shop == null) {
            return 0;
        }
        ShopSlotItem pick = findPotionItem(shop, bot, forHp);
        return pick == null || pick.recovery == null ? 0 : pick.recovery.primary();
    }

    /**
     * {@code activity}'s potion standard in potions at {@code shop}: AgentSupplyReadiness.standard for a count
     * standard; for a points standard (AgentSupplyReadiness.recoveryStandard) each kind's points in the potion a visit
     * picks for it ({@link #coveragePickRecovery}), rounded up.
     */
    private static AgentSupplyReadiness.Standard readinessStandard(Character bot, Shop shop,
                                                                   AgentSupplyReadiness.Activity activity) {
        AgentSupplyReadiness.RecoveryStandard points = AgentSupplyReadiness.recoveryStandard(activity);
        if (points == null) {
            return AgentSupplyReadiness.standard(activity);
        }
        return new AgentSupplyReadiness.Standard(
                AgentSupplyReadiness.potionsFor(points.hp(), coveragePickRecovery(bot, shop, true)),
                AgentSupplyReadiness.potionsFor(points.mp(), coveragePickRecovery(bot, shop, false)));
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

    private static ShopSlotItem selectAtFloor(Shop shop, Character bot, boolean forHp, int want, long budget,
                                              int floorBps) {
        AgentShopPotionPolicy.PotionShopSlot selected = AgentShopPotionPolicy.selectAtFloor(
                shop.getItems(), want, budget, bot.getCurrentMaxHp(), bot.getCurrentMaxMp(), forHp, floorBps,
                AgentUseItemClassificationPolicy::isRecoveryPotion, AgentUseItemClassificationPolicy::itemEffect);
        return selected == null ? null : new ShopSlotItem(selected.slot(), selected.shopItem(), selected.recovery());
    }

    /** The cheapest HP (or MP) recovery potion this shop sells, in mesos; -1 when it sells none. */
    public static int cheapestRecoveryPrice(Character bot, Shop shop, boolean forHp) {
        return cheapestRecoveryPrice(bot, shop, forHp, 0);
    }

    /**
     * As {@link #cheapestRecoveryPrice(Character, Shop, boolean)}, among the potions one of which restores at least
     * {@code floorBps} of the bot's maximum (a boss standard's potion floor; 0 for none).
     */
    public static int cheapestRecoveryPrice(Character bot, Shop shop, boolean forHp, int floorBps) {
        if (bot == null || shop == null) {
            return -1;
        }
        return AgentShopPotionPolicy.cheapestPrice(shop.getItems(), bot.getCurrentMaxHp(), bot.getCurrentMaxMp(),
                forHp, floorBps, AgentUseItemClassificationPolicy::isRecoveryPotion,
                AgentUseItemClassificationPolicy::itemEffect);
    }

    /**
     * What one HP (or MP) potion costs at this shop as an ordinary visit's reserve-bar passes buy them (the pick of
     * {@link #findPotionItem}, which a purse short of it never trades down from); -1 when it sells none.
     */
    public static int ordinaryPotionPrice(Character bot, Shop shop, boolean forHp) {
        if (bot == null || shop == null) {
            return -1;
        }
        ShopSlotItem potion = findPotionItem(shop, bot, forHp);
        return potion == null ? -1 : potion.shopItem.getPrice();
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
            int maximumSpend,
            int room) {
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
                Math.min(quantity, room),
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
            InventoryGateway inventory,
            int room) {
        int plannedQuantity = Math.min(plannedPotionQuantity(bot, potion, forHp), room);
        return plannedQuantity <= 0
                ? null
                : AgentDialogueCatalog.shopPurchaseIntent(
                plannedQuantity,
                resolveItemName(potion.shopItem.getItemId(), "potions", inventory),
                forHp ? "HP" : "MP");
    }

    /** What a visit plans to buy of {@code potion} toward the target reserve bars, before its budget and room. */
    private static int plannedPotionQuantity(Character bot, ShopSlotItem potion, boolean forHp) {
        int[] counts = AgentPotionService.countPotions(bot);
        return AgentPotionPurchasePolicy.quantityToTarget(
                potionRecoveryCapacity(bot, forHp),
                forHp ? counts[0] : counts[1],
                potion.recovery.primary(),
                forHp ? bot.getCurrentMaxHp() : bot.getCurrentMaxMp(),
                AgentPotionPurchasePolicy.targetReserveBars(forHp),
                0,
                true);
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
    // that flag, so a cleared flag here means a concurrent player cancel — stay silent. A visit opened only to sell
    // (nobody asked for it) ends quietly too; the log keeps the reason.
    private static void abortShop(AgentRuntimeEntry entry, Character bot, String reason) {
        if (AgentShopStateRuntime.shopVisitPending(entry) && !AgentShopStateRuntime.shopSaleOnly(entry)) {
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
                        + " distToNpc={} distToApproach={} visitPending={} sequenceActive={} visitAgeMs={}"
                        + " visitAllowanceMs={} sequenceAgeMs={}"
                        + " sequenceAllowanceMs={} sellTrashPending={} sale={} saleOnly={} mesos={}",
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
                state.visitAllowanceMs(),
                state.sequenceStartedAtMs() > 0 ? now - state.sequenceStartedAtMs() : -1,
                state.sequenceAllowanceMs(),
                state.sellTrashPending(),
                state.sale(),
                state.saleOnly(),
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
        // The step belongs to the visit open now. If that visit ends and another opens before the step comes
        // due (a supply trip or a map change re-runs onMapChange), the step is dropped: live, it aborted the
        // new visit with "couldn't get to the shopkeeper" because the new visit had no transaction yet.
        long visitGeneration = AgentShopStateRuntime.shopVisitGeneration(entry);
        long dueAtMs = stepClock.getAsLong() + delayMs;
        AgentShopRuntime.afterDelay(entry, delayMs, () -> {
            if (!AgentShopStateRuntime.shouldRunScheduledShopStep(entry, visitGeneration)) {
                if (AgentShopStateRuntime.shopVisitPending(entry)) {
                    Character bot = AgentRuntimeIdentityRuntime.bot(entry);
                    log.info("Agent '{}' dropped a shop step left from an earlier visit; the current visit continues",
                            bot == null ? "?" : bot.getName());
                }
                return;
            }
            // The time a step runs past its due time spends none of the transaction's budget, which prices the visit's
            // own pace: an unwatched Agent's steps run on its mailbox at its tick rate, about 1.5 s a sale against the
            // 0.5 s step with 250 Agents (10-29), and 23 sell trips of 70-80 items ran out of time after selling
            // ("took too long at the shop"). A step that never runs adds nothing: a stalled visit still times out.
            AgentShopStateRuntime.extendShopSequence(entry, stepClock.getAsLong() - dueAtMs);
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

    /** Where a shop visit walks to, and the nav route's travel time there in ms (0 when no route was found). */
    private record ShopApproach(Point point, long routeMs) {}

    private static ShopApproach pickShopApproach(Point npcPos, AgentRuntimeEntry entry, Character bot) {
        var footholds = bot.getMap().getFootholds();
        if (footholds == null) {
            return new ShopApproach(npcPos, 0L);
        }
        List<Point> candidates = AgentShopApproachPolicy.footholdCandidatesNear(
                npcPos, footholds.getAllFootholds(), SHOP_MANHATTAN_RADIUS);
        if (candidates.isEmpty()) {
            return new ShopApproach(npcPos, 0L);
        }
        Map<Point, Long> routeMs = new HashMap<>();
        AgentMovementProfile profile = AgentMovementStateRuntime.movementProfileOrCharacter(entry, bot);
        AgentNavigationGraph graph = AgentNavigationGraphService.peekBestGraph(bot.getMap(), profile);
        if (graph != null) {
            Point botPos = bot.getPosition();
            int startRegionId = approachStartRegionId(graph, entry, bot.getMap(), botPos);
            if (startRegionId >= 0) {
                List<Point> reachable = new ArrayList<>();
                for (Point candidate : candidates) {
                    int targetRegionId = AgentNavigationRegionService.resolveTargetRegionId(
                            graph, entry, bot.getMap(), candidate);
                    if (targetRegionId < 0) continue;
                    long candidateRouteMs = startRegionId == targetRegionId
                            ? AgentNavigationPathService.intraRegionTravelCost(graph, startRegionId, botPos, candidate)
                            : AgentNavigationPathService.completeRouteCost(
                                    AgentNavigationPathService.findRouteForApproachProbe(graph, bot.getMap(), botPos,
                                            startRegionId, targetRegionId, candidate), -1L);
                    if (candidateRouteMs >= 0L) {
                        reachable.add(candidate);
                        routeMs.put(candidate, candidateRouteMs);
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
        Point approach = candidates.get(0);
        return new ShopApproach(approach, routeMs.getOrDefault(approach, 0L));
    }

    // A visit opened on arrival starts while the spawn fall is still under way, and an Agent in the air has no
    // current region. Skipping the reachability check then sent Agents at Mushroom Shrine (800000000) to a platform
    // beside the potion seller that no route reaches. The fall lands on the ground below.
    private static int approachStartRegionId(AgentNavigationGraph graph, AgentRuntimeEntry entry, MapleMap map,
                                             Point botPos) {
        int regionId = AgentNavigationRegionService.resolveCurrentRegionId(graph, entry, map, botPos);
        return regionId >= 0 || !AgentMovementStateRuntime.inAir(entry) ? regionId : graph.findRegionId(map, botPos);
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
