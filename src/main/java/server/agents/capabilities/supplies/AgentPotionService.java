package server.agents.capabilities.supplies;

import server.agents.capabilities.trade.AgentPendingTradeStateRuntime;

import server.agents.capabilities.movement.AgentMovementTimers;

import server.agents.capabilities.dialogue.AgentEmote;

import server.agents.capabilities.behavior.AgentImperfections;

import server.agents.monitoring.AgentPerformanceMonitor;

import client.Character;
import client.Disease;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.keybind.KeyBinding;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.dialogue.AgentDialogueSelector;
import server.agents.capabilities.dialogue.AgentSupplyDialogueReporter;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.agents.capabilities.movement.AgentMovementPoseService;
import server.agents.capabilities.trade.AgentSupplyShareTradeService;
import server.agents.capabilities.supplies.AgentAutopotPolicy.AutopotChoice;
import server.agents.capabilities.supplies.AgentAutopotPolicy.AutopotItemChoice;
import server.agents.capabilities.supplies.AgentAutopotPolicy.PotionRanking;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.supplies.AgentPotionRuntime;
import server.agents.capabilities.supplies.AgentCombatAmmoCheckRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentSessionLifecycleRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.StatEffect;
import server.agents.coordination.AgentCoordinationRuntime;
import server.agents.coordination.AgentSupplyNeedMessage;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyNeed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

public final class AgentPotionService {
    // ownerCharId -> shared HP/MP 30 s request cooldown
    private static final Map<Long, Long> potShareCooldownUntil = new ConcurrentHashMap<>();
    // ownerCharId -> category-specific 10 min failed-request backoff
    private static final Map<Long, Long> potShareHpBackoffUntil = new ConcurrentHashMap<>();
    private static final Map<Long, Long> potShareMpBackoffUntil = new ConcurrentHashMap<>();

    private AgentPotionService() {
    }

    /**
     * Takes the cohort's share window when it is open, in one step: true for the one member that took it. Members of a
     * cohort ask on their own ticks, at once in central-sharded mode, and a get-then-put let two ask the same donor
     * (docs/SCALE_PLATFORM_PLAN.md section 6, race M6).
     */
    static boolean claimShareWindow(Map<Long, Long> windows, long cohortId, long now, long windowMs) {
        boolean[] claimed = {false};
        windows.compute(cohortId, (id, until) -> {
            if (until != null && now < until) {
                return until;
            }
            claimed[0] = true;
            return now + windowMs;
        });
        return claimed[0];
    }

    public static void clearLeaderRuntimeState(int leaderId) {
        potShareCooldownUntil.remove((long) leaderId);
        potShareHpBackoffUntil.remove((long) leaderId);
        potShareMpBackoffUntil.remove((long) leaderId);
    }

    /** Single source of truth: items the bot has that count as recovery pots. */
    public static List<Item> recoveryPotions(Character bot) {
        long startedAt = AgentPerformanceMonitor.start();
        List<Item> result = new java.util.ArrayList<>();
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (item.getQuantity() <= 0) {
                continue;
            }
            if (AgentUseItemClassificationPolicy.isRecoveryPotion(item.getItemId())) {
                result.add(item);
            }
        }
        AgentPerformanceMonitor.recordSince("potion-recovery-scan", startedAt);
        return result;
    }

    public static int[] countPotions(Character bot) {
        long startedAt = AgentPerformanceMonitor.start();
        int[] counts = AgentPotionInventoryPolicy.countRecoveryPotions(
                bot.getInventory(InventoryType.USE).list(),
                AgentUseItemClassificationPolicy::itemEffect);
        AgentPerformanceMonitor.recordSince("potion-recovery-scan", startedAt);
        return counts;
    }

    public static int[] countPotions(List<Item> items, Function<Integer, StatEffect> effectLookup) {
        long startedAt = AgentPerformanceMonitor.start();
        int[] counts = AgentPotionInventoryPolicy.countRecoveryPotions(items, effectLookup);
        AgentPerformanceMonitor.recordSince("potion-recovery-count", startedAt);
        return counts;
    }

    /**
     * The HP fraction this Agent drinks below, before any Berserk adjustment: its host's own value
     * (AgentPotionThresholds), else the configured AUTOPOT_HP_THRESH.
     */
    public static float hpThreshold(Character bot) {
        // F8.2: with no owner's or learned point, a citizen or companion drinks at its own point near the configured one
        float configured = AgentImperfections.hpAlert(bot, AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH);
        return AgentPotionThresholds.hp(bot.getId(), configured);
    }

    /** The MP fraction this Agent drinks below: its host's own value, else the configured AUTOPOT_MP_THRESH. */
    public static float mpThreshold(Character bot) {
        return AgentPotionThresholds.mp(bot.getId(), AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH);
    }

    /** Shared deficit-aware selection used by keybind setup and the debug report. */
    public static AutopotChoice computeAutopotChoice(Character bot) {
        long startedAt = AgentPerformanceMonitor.start();
        int maxHp = bot.getCurrentMaxHp();
        int maxMp = bot.getCurrentMaxMp();
        // A Dark Knight that tops up at the ordinary threshold never enters Berserk's window and
        // fights at half damage; with Berserk learned the trigger sits just under the threshold.
        int hpDeficit = Math.max(
                1,
                Math.round(maxHp * server.agents.capabilities.combat.AgentBerserkWindowPolicy
                        .autopotHpDeficitFraction(bot, hpThreshold(bot))));
        int mpDeficit = Math.max(
                1,
                Math.round(maxMp * (1.0f - mpThreshold(bot))));
        AutopotChoice choice = AgentAutopotPolicy.computeChoice(
                bot.getInventory(InventoryType.USE).list(),
                AgentUseItemClassificationPolicy::itemEffect,
                maxHp,
                maxMp,
                hpDeficit,
                mpDeficit);
        AgentPerformanceMonitor.recordSince("potion-recovery-scan", startedAt);
        return choice;
    }

    /** Selects against the live missing HP/MP so consumption matches purchasing. */
    public static AutopotItemChoice selectAutopotForCurrentDeficit(
            Character bot, boolean forHp) {
        int maxHp = bot.getCurrentMaxHp();
        int maxMp = bot.getCurrentMaxMp();
        int deficit = forHp
                ? Math.max(1, maxHp - bot.getHp())
                : Math.max(1, maxMp - bot.getMp());
        return AgentAutopotPolicy.select(
                bot.getInventory(InventoryType.USE).list(),
                AgentUseItemClassificationPolicy::itemEffect,
                maxHp,
                maxMp,
                deficit,
                forHp);
    }

    public static void setupAutopotForBot(Character bot) {
        AutopotChoice choice = computeAutopotChoice(bot);

        if (choice.hpItemId() > 0) {
            bot.changeKeybinding(91, new KeyBinding(7, choice.hpItemId()));
            bot.setAutopotHpAlert(server.agents.capabilities.combat.AgentBerserkWindowPolicy
                    .autopotHpThreshold(bot, hpThreshold(bot)));
        } else {
            bot.getKeymap().remove(91);
            bot.setAutopotHpAlert(0f);
        }

        if (choice.mpItemId() > 0) {
            bot.changeKeybinding(92, new KeyBinding(7, choice.mpItemId()));
            bot.setAutopotMpAlert(mpThreshold(bot));
        } else {
            bot.getKeymap().remove(92);
            bot.setAutopotMpAlert(0f);
        }
    }

    /** Owner-facing diagnostic: counts vs. selected items for each slot. */
    public static String autopotDebugReport(Character bot, InventoryGateway inventory) {
        int[] cnt = countPotions(bot);
        AutopotChoice choice = computeAutopotChoice(bot);
        return AgentSupplyDialogueReporter.autopotDebugReport(
                cnt[0],
                cnt[1],
                describeChoice(choice.hpItemId(), choice.hpRank(), inventory),
                describeChoice(choice.mpItemId(), choice.mpRank(), inventory));
    }

    private static String describeChoice(int itemId, PotionRanking rank, InventoryGateway inventory) {
        if (itemId <= 0 || rank == null) {
            return "none";
        }
        String name = inventory.getItemName(itemId);
        if (name == null) name = String.valueOf(itemId);
        return AgentSupplyDialogueReporter.autopotChoice(
                name,
                itemId,
                rank.primaryRecovery(),
                rank.coverageBasisPoints(),
                rank.mixed(),
                rank.percentageBased());
    }

    public static String grindStartMessage(Character bot) {
        int[] pots = countPotions(bot);
        return AgentSupplyDialogueReporter.grindStartMessage(
                AgentDialogueSelector.randomReply(AgentDialogueCatalog.grindReplies()),
                pots[0],
                pots[1],
                AgentRuntimeConfig.cfg.POT_LOW_WARN);
    }

    public static void tickPotionCheck(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory) {
        long now = System.currentTimeMillis();
        tickDiseaseCure(entry, bot, inventory, now,
                () -> ThreadLocalRandom.current().nextLong(500L, 2_501L));
        tickLowTopUp(entry, bot, now);
        if (AgentPotionStateRuntime.hasPotCheckDelay(entry)) {
            AgentPotionStateRuntime.tickPotCheckDelay(entry, AgentMovementTimers::tickDown);
            return;
        }
        AgentPotionStateRuntime.setPotCheckTimerMs(
                entry,
                AgentMovementTimers.delayAfterCurrentTick(AgentRuntimeConfig.cfg.POT_CHECK_INTERVAL_MS));

        long startedAt = AgentPerformanceMonitor.start();
        setupAutopotForBot(bot);
        AgentPerformanceMonitor.recordSince("potion-autopot", startedAt);

        startedAt = AgentPerformanceMonitor.start();
        AgentCombatAmmoCheckRuntime.tickAmmoCheck(entry, bot,
                AgentSupplyConfig.cfg.AMMO_LOW_WARN, AgentRuntimeConfig.cfg.POT_LOW_WARN);
        AgentPerformanceMonitor.recordSince("potion-ammo-check", startedAt);

        if (!AgentModeStateRuntime.grinding(entry) && !AgentModeStateRuntime.following(entry)) {
            return;
        }
        startedAt = AgentPerformanceMonitor.start();
        AgentAmmoService.tickAmmoShareCheck(entry, bot, inventory);
        AgentPerformanceMonitor.recordSince("potion-ammo-share", startedAt);

        startedAt = AgentPerformanceMonitor.start();
        int[] pots = countPotions(bot);
        AgentPerformanceMonitor.recordSince("potion-count", startedAt);

        startedAt = AgentPerformanceMonitor.start();
        requestLowPotShare(entry, bot, pots[0], true, false);
        AgentPerformanceMonitor.recordSince("potion-share-hp", startedAt);

        startedAt = AgentPerformanceMonitor.start();
        requestLowPotShare(entry, bot, pots[1], false, false);
        AgentPerformanceMonitor.recordSince("potion-share-mp", startedAt);

        if (!AgentModeStateRuntime.grinding(entry)) {
            return;
        }
        startedAt = AgentPerformanceMonitor.start();
        // Low on HP potions by the standard an ordinary trip uses: a full reserve in a few strong potions fights on.
        // A party quest whose own rest holds the member for its potions (the EPQ golem rest, whose fight from its rest
        // point is grind mode) owns its moves and words: no stop, no "walking to you" line.
        if (pots[0] < AgentRuntimeConfig.cfg.POT_STOP && bot.getHp() < bot.getMaxHp() * 0.4f
                && !AgentPartyQuestRuntime.ownsPotionRest(bot.getId(), bot.getMapId())
                && AgentPotionReserve.tripNeeded(bot, AgentResourceCategory.HP_POTION,
                AgentSupplyReadiness.standing(bot.getId()) != null)) {
            if (AgentRelationshipRuntime.followTarget(entry) != null) {
                AgentMovementCommandRuntime.followConfiguredTarget(entry);
            } else {
                AgentMovementCommandRuntime.stop(entry);
            }
            AgentPotionRuntime.sayMapNow(bot, AgentDialogueCatalog.potLowReturnReply());
            bot.changeFaceExpression(AgentEmote.GLARE.getValue());
        }
        AgentPerformanceMonitor.recordSince("potion-grind-stop", startedAt);
    }

    static void tickDiseaseCure(AgentRuntimeEntry entry,
                                Character bot,
                                InventoryGateway inventory,
                                long nowMs,
                                LongSupplier delayMs) {
        if (entry == null || bot == null || inventory == null || delayMs == null) return;
        Set<Disease> activeDiseases = bot.getAllDiseases().keySet().stream()
                .filter(AgentDiseaseCurePolicy.CURABLE_DISEASES::contains)
                .collect(Collectors.toUnmodifiableSet());
        long signature = AgentDiseaseCurePolicy.signature(activeDiseases);
        if (signature == 0L) {
            AgentPotionStateRuntime.clearDiseaseCure(entry);
            return;
        }

        if (AgentPotionStateRuntime.diseaseSignature(entry) != signature
                || AgentPotionStateRuntime.diseaseCureDueAtMs(entry) <= 0L) {
            AgentPotionStateRuntime.scheduleDiseaseCure(
                    entry, signature, nowMs + cureDelay(delayMs.getAsLong()));
            return;
        }
        if (nowMs < AgentPotionStateRuntime.diseaseCureDueAtMs(entry)) return;

        AgentDiseaseCurePolicy.CureChoice choice = AgentDiseaseCurePolicy.select(
                bot.getInventory(InventoryType.USE).list(), inventory::getItemEffect,
                activeDiseases);
        if (choice == null) {
            AgentPotionStateRuntime.scheduleDiseaseCure(
                    entry, signature, nowMs + cureDelay(delayMs.getAsLong()));
            return;
        }
        if (inventory.consumeUseItem(bot, choice.slot(), choice.itemId())) {
            AgentPotionStateRuntime.clearDiseaseCure(entry);
        } else {
            AgentPotionStateRuntime.scheduleDiseaseCure(
                    entry, signature, nowMs + cureDelay(delayMs.getAsLong()));
        }
    }

    private static long cureDelay(long delayMs) {
        return Math.max(500L, Math.min(2_500L, delayMs));
    }

    /**
     * The autopot drinks only on a stat loss, so an Agent left low without being hit (revived at 50 HP, walking back
     * into a boss fight) never drank and fell to the first hit. At most every LOW_TOP_UP_INTERVAL_MS, an Agent with
     * an autopot key whose HP (or MP) is at or below its alert drinks as a loss would make it.
     */
    static void tickLowTopUp(AgentRuntimeEntry entry, Character bot, long nowMs) {
        if (entry == null || bot == null || !bot.isAlive() || bot.isChangingMaps()) {
            return;
        }
        boolean lowHp = bot.getKeymap().get(91) != null
                && bot.getHp() <= bot.getAutopotHpAlert() * bot.getCurrentMaxHp();
        boolean lowMp = bot.getKeymap().get(92) != null
                && bot.getMp() <= bot.getAutopotMpAlert() * bot.getCurrentMaxMp();
        if (AgentLateDrinkRuntime.dueNow(entry, nowMs)) { // F8.2: a drink that waited a beat goes down on this tick
            if (lowHp) {
                bot.triggerAutopotWhileLow(true, false);
                return;
            }
            AgentLateDrinkRuntime.cancel(entry);
        }
        if (!lowHp && !lowMp || nowMs < AgentPotionStateRuntime.lowTopUpDueAtMs(entry)) {
            return;
        }
        AgentPotionStateRuntime.setLowTopUpDueAtMs(entry, nowMs + Math.max(0, config.AgentTuning.intValue(
                "server.agents.capabilities.supplies.AgentPotionService.LOW_TOP_UP_INTERVAL_MS")));
        bot.triggerAutopotWhileLow(lowHp, lowMp);
    }

    public static void checkPotShareOnModeStart(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory) {
        AgentPotionStateRuntime.clearAllPotShareRequests(entry);
        AgentAmmoService.checkAmmoShareOnModeStart(entry, bot, inventory);
        requestLowPotShares(entry, bot, false);
    }

    public static boolean requestLowSuppliesFromOwnerAsk(AgentRuntimeEntry entry, Character bot, InventoryGateway inventory) {
        boolean requestedPots = requestLowPotShares(entry, bot, true);
        boolean requestedAmmo = AgentAmmoService.requestLowAmmoShare(entry, bot, true, inventory);
        return requestedPots || requestedAmmo;
    }

    private static boolean requestLowPotShares(AgentRuntimeEntry entry, Character bot, boolean bypassShareLimits) {
        return requestLowPotShares(entry, bot, countPotions(bot), bypassShareLimits);
    }

    private static boolean requestLowPotShares(AgentRuntimeEntry entry, Character bot, int[] pots, boolean bypassShareLimits) {
        boolean requestedHp = requestLowPotShare(entry, bot, pots[0], true, bypassShareLimits);
        boolean requestedMp = requestLowPotShare(entry, bot, pots[1], false, bypassShareLimits);
        return requestedHp || requestedMp;
    }

    private static boolean requestLowPotShare(AgentRuntimeEntry entry,
                                              Character bot,
                                              int count,
                                              boolean forHp,
                                              boolean bypassShareLimits) {
        long observedAtMs = System.currentTimeMillis();
        AgentResourceCategory category = forHp
                ? AgentResourceCategory.HP_POTION : AgentResourceCategory.MP_POTION;
        AgentSupplyNeed previous = entry.capabilityStates()
                .require(AgentResourcePlanningState.STATE_KEY).need(category);
        AgentSupplyNeed observed = AgentResourcePlanningRuntime.observe(
                entry,
                category,
                count,
                AgentRuntimeConfig.cfg.POT_LOW_WARN,
                AgentRuntimeConfig.cfg.POT_STOP,
                Math.max(AgentRuntimeConfig.cfg.POT_LOW_WARN * 2, Math.max(0, count)),
                observedAtMs);
        boolean thresholdEventPublished = previous == null
                || previous.urgency() != observed.urgency();
        if (count >= AgentRuntimeConfig.cfg.POT_LOW_WARN) {
            AgentPotionStateRuntime.clearPotShareRequested(entry, forHp);
            return false;
        }

        boolean alreadyRequested = AgentPotionStateRuntime.potShareRequested(entry, forHp);
        if ((alreadyRequested && !bypassShareLimits)
                || !requestPotShare(entry, bot, forHp, bypassShareLimits, thresholdEventPublished,
                System.currentTimeMillis())) {
            return false;
        }
        AgentPotionStateRuntime.setPotShareRequested(entry, forHp, true);
        return true;
    }

    public static void tickPassiveRecovery(AgentRuntimeEntry entry, Character bot) {
        boolean hpFull = bot.getHp() >= bot.getCurrentMaxHp();
        boolean mpFull = bot.getMp() >= bot.getCurrentMaxMp();
        if (hpFull && mpFull) {
            AgentPotionStateRuntime.clearMpRecoveryTimer(entry);
            return;
        }
        if (AgentPotionStateRuntime.hasMpRecoveryDelay(entry)) {
            AgentPotionStateRuntime.tickMpRecoveryDelay(entry, AgentMovementTimers::tickDown);
            return;
        }

        AgentPotionStateRuntime.setMpRecoveryTimerMs(
                entry,
                AgentMovementTimers.delayAfterCurrentTick(AgentRuntimeConfig.cfg.MP_RECOVERY_INTERVAL_MS));

        int hpRecovery = hpFull ? 0 : calculatePassiveHpRecovery(entry, bot);
        int mpRecovery = mpFull ? 0 : calculatePassiveMpRecovery(entry, bot);
        if (hpRecovery <= 0 && mpRecovery <= 0) {
            return;
        }

        bot.addMPHP(hpRecovery, mpRecovery);
    }

    public static boolean requestPotShare(AgentRuntimeEntry entry, Character bot, boolean forHp) {
        return requestPotShare(entry, bot, forHp, false);
    }

    public static boolean requestPotShare(AgentRuntimeEntry entry, Character bot, boolean forHp, boolean bypassShareLimits) {
        return requestPotShare(entry, bot, forHp, bypassShareLimits, false, System.currentTimeMillis());
    }

    /**
     * As {@link #requestPotShare(AgentRuntimeEntry, Character, boolean)}, with the share's cohort cooldown and no-donor
     * backoff read and set on the caller's clock {@code nowMs} (wall-clock milliseconds). A caller that spaces its own
     * asks by that clock (the EPQ dry golem rester) then meets the cooldown's end exactly instead of a few
     * milliseconds short of it.
     */
    public static boolean requestPotShareAt(AgentRuntimeEntry entry, Character bot, boolean forHp, long nowMs) {
        return requestPotShare(entry, bot, forHp, false, false, nowMs);
    }

    /**
     * 2026-10-15 (P20 run 3): a bench ally still short of its boss's potions after its arrival purchase has asked its
     * crew for some in its own words (CompanionBossAllies): its share is asked as its own critical ask is, the donor
     * picked from its cohort and its crew ({@link #donorCandidates}), past the share's cooldown and no-donor backoff,
     * with no ask line of the Agent's own. False when no Agent runtime holds it or it cannot trade now.
     */
    public static boolean requestCrewPotShare(Character bot, boolean forHp) {
        AgentRuntimeEntry entry = bot == null ? null : AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        return entry != null && requestPotShare(entry, bot, forHp, true, true, System.currentTimeMillis());
    }

    private static boolean requestPotShare(AgentRuntimeEntry entry,
                                           Character bot,
                                           boolean forHp,
                                           boolean bypassShareLimits,
                                           boolean thresholdEventPublished,
                                           long now) {
        long startedAt = AgentPerformanceMonitor.start();
        if (selfSustaining(entry)) {
            AgentPerformanceMonitor.recordSince("potion-request", startedAt);
            return false;
        }
        if (bot.getTrade() != null || AgentPendingTradeStateRuntime.hasActiveSequence(entry)) {
            AgentPerformanceMonitor.recordSince("potion-request", startedAt);
            return false;
        }

        long cohortId = AgentRelationshipRuntime.cohortId(entry);
        Map<Long, Long> categoryBackoff = forHp ? potShareHpBackoffUntil : potShareMpBackoffUntil;
        if (!bypassShareLimits) {
            if (now < categoryBackoff.getOrDefault(cohortId, 0L)) {
                AgentPerformanceMonitor.recordSince("potion-request", startedAt);
                return false;
            }
            if (!claimShareWindow(potShareCooldownUntil, cohortId, now, 30_000L)) {
                AgentPerformanceMonitor.recordSince("potion-request", startedAt);
                return false;
            }
        }

        int[] currentPots = bot.getInventory(InventoryType.USE) == null
                ? new int[]{-1, -1}
                : countPotions(bot);
        int currentCount = forHp ? currentPots[0] : currentPots[1];
        if (bypassShareLimits && !thresholdEventPublished) {
            AgentCoordinationRuntime.publish(new AgentSupplyNeedMessage(
                    bot.getId(),
                    cohortId,
                    bot.getMapId(),
                    forHp ? AgentSupplyNeedMessage.SupplyKind.HP_POTION
                            : AgentSupplyNeedMessage.SupplyKind.MP_POTION,
                    currentCount,
                    "",
                    now));
            AgentPotionRuntime.sayMapNow(bot, AgentDialogueSelector.randomReply(
                    forHp ? AgentDialogueCatalog.potRequestHpReplies()
                            : AgentDialogueCatalog.potRequestMpReplies()));
        }

        AgentPotionDonorPlan<AgentRuntimeEntry> plan = selectPotDonor(entry, bot, entry, forHp);
        if (plan == null) {
            if (!bypassShareLimits) {
                categoryBackoff.put(cohortId, now + 10 * 60_000L);
            }
            AgentPerformanceMonitor.recordSince("potion-request", startedAt);
            return true;
        }

        if (!plan.qualifies()) {
            if (!bypassShareLimits) {
                categoryBackoff.put(cohortId, now + 10 * 60_000L);
            }
            String ownerName = bot.getName();
            AgentPotionRuntime.afterRandomDelay(entry, 4000, 6000, () ->
                    AgentPotionRuntime.sayMapNow(
                            AgentRuntimeIdentityRuntime.bot(plan.entry()),
                            AgentDialogueCatalog.formatPotDonorLowReply(
                                    AgentDialogueSelector.randomReply(AgentDialogueCatalog.potDonorLowTemplates()),
                                    ownerName)));
        } else {
            schedulePotShare(plan, bot, forHp, AgentPotionRuntime.randomDelayMs(2000, 3000));
        }
        AgentPerformanceMonitor.recordSince("potion-request", startedAt);
        return true;
    }

    public enum OwnerPotShareResult {
        OFFERED,
        NO_DONOR,
        BLOCKED
    }

    public static OwnerPotShareResult offerPotShareToOwner(AgentRuntimeEntry entry, boolean forHp) {
        if (selfSustaining(entry)) {
            return OwnerPotShareResult.BLOCKED;
        }
        Character owner = AgentRelationshipRuntime.requester(entry);
        if (owner == null || owner.getTrade() != null) {
            return OwnerPotShareResult.BLOCKED;
        }

        AgentPotionDonorPlan<AgentRuntimeEntry> plan = selectPotDonor(entry, owner, null, forHp);
        if (plan == null || !plan.qualifies()) {
            return OwnerPotShareResult.NO_DONOR;
        }

        schedulePotShare(plan, owner, forHp, AgentPotionRuntime.randomDelayMs(900, 1400));
        return OwnerPotShareResult.OFFERED;
    }

    private static AgentPotionDonorPlan<AgentRuntimeEntry> selectPotDonor(AgentRuntimeEntry requesterEntry, Character recipient, AgentRuntimeEntry excludedEntry, boolean forHp) {
        long startedAt = AgentPerformanceMonitor.start();
        AgentRuntimeEntry bestEntry = null;
        int bestCount = 0;
        for (AgentRuntimeEntry sibling : donorCandidates(requesterEntry)) {
            Character siblingBot = AgentRuntimeIdentityRuntime.bot(sibling);
            if (sibling == excludedEntry || selfSustaining(sibling)
                    || siblingBot == null || siblingBot.getMapId() != recipient.getMapId()) {
                continue;
            }
            int[] pots = countPotions(siblingBot);
            int count = forHp ? pots[0] : pots[1];
            if (count > bestCount) {
                bestCount = count;
                bestEntry = sibling;
            }
        }
        AgentPerformanceMonitor.recordSince("potion-donor-select", startedAt);
        return bestEntry != null ? new AgentPotionDonorPlan<>(bestEntry, bestCount) : null;
    }

    /**
     * Whom a share asks: the requester's cohort, and 2026-10-15 (P20 run 3) for a bench ally also its crew, the members
     * of its boss run who stay with the same human (AgentBossRunRegistry.crewOf). An ally leads itself in a party of
     * bots, so its cohort is itself alone: Gunnar asked his party at 14:23:26, dry, and nobody answered, while his
     * owner's ten companions in the same fight still held 328 to 1,976 HP potions each when the run failed.
     */
    private static List<AgentRuntimeEntry> donorCandidates(AgentRuntimeEntry requesterEntry) {
        List<AgentRuntimeEntry> cohort = AgentSessionLifecycleRuntime.getCohortEntries(requesterEntry);
        Character requester = AgentRuntimeIdentityRuntime.bot(requesterEntry);
        List<Integer> crew = requester == null ? List.of() : AgentBossRunRegistry.crewOf(requester.getId());
        if (crew.isEmpty()) {
            return cohort;
        }
        List<AgentRuntimeEntry> candidates = new ArrayList<>(cohort);
        for (int memberId : crew) {
            AgentRuntimeEntry member = AgentRuntimeRegistry.findByAgentCharacterId(memberId);
            if (member != null && !candidates.contains(member)) {
                candidates.add(member);
            }
        }
        return candidates;
    }

    private static boolean selfSustaining(AgentRuntimeEntry entry) {
        return entry != null && entry.capabilityStates()
                .find(AgentResourceAutonomyState.STATE_KEY)
                .map(AgentResourceAutonomyState::selfSustaining).orElse(false);
    }

    private static void schedulePotShare(AgentPotionDonorPlan<AgentRuntimeEntry> plan, Character recipient, boolean forHp, long initialDelayMs) {
        AgentRuntimeEntry donorEntry = plan.entry();
        Character donorBot = AgentRuntimeIdentityRuntime.bot(donorEntry);
        int maxQty = plan.donationQty();
        AgentPotionRuntime.afterDelay(donorEntry, initialDelayMs, () -> {
            if (donorBot.getTrade() != null || AgentPendingTradeStateRuntime.hasActiveSequence(donorEntry) || recipient.getTrade() != null) {
                return;
            }
            List<Item> items = collectPotShareItems(donorBot, forHp, maxQty);
            if (items.isEmpty()) {
                return;
            }
            AgentPotionRuntime.sayMapNow(donorBot, AgentDialogueSelector.randomReply(
                    forHp ? AgentDialogueCatalog.potOfferHpReplies() : AgentDialogueCatalog.potOfferMpReplies()));
            AgentPotionRuntime.afterRandomDelay(donorEntry, 900, 1100, () ->
                    AgentSupplyShareTradeService.startPotShareTransfer(items, recipient, donorEntry, donorBot, maxQty));
        });
    }

    public static List<Item> collectPotShareItems(Character donorBot, boolean forHp, int maxQty) {
        return AgentPotionSharePolicy.collectShareItems(donorBot, forHp, maxQty,
                AgentUseItemClassificationPolicy::isRecoveryPotion,
                AgentUseItemClassificationPolicy::itemEffect);
    }

    private static int calculatePassiveHpRecovery(AgentRuntimeEntry entry, Character bot) {
        return AgentPassiveRecoveryPolicy.hpRecovery(
                bot,
                AgentRuntimeConfig.cfg.BASE_HP_RECOVERY,
                isStandingStillForRecovery(entry));
    }

    private static int calculatePassiveMpRecovery(AgentRuntimeEntry entry, Character bot) {
        return AgentPassiveRecoveryPolicy.mpRecovery(
                bot,
                AgentRuntimeConfig.cfg.BASE_MP_RECOVERY,
                isStandingStillForRecovery(entry));
    }

    private static boolean isStandingStillForRecovery(AgentRuntimeEntry entry) {
        if (AgentMovementStateRuntime.inAir(entry) || AgentMovementStateRuntime.climbing(entry)) {
            return false;
        }
        return !AgentMovementStateRuntime.hasMoveDirection(entry)
                && AgentMovementPoseService.isStandingResolvedStance(entry);
    }

}
