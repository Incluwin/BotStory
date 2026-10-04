package server.agents.capabilities.supplies;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.Shop;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.capabilities.shop.AgentShopStateRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveStatus;
import server.agents.progression.AgentCareerBuildBundle;
import server.agents.progression.AgentCareerProgressionState;
import server.agents.progression.AgentCareerShopCatalog;
import server.agents.progression.AgentPotionSupplierCatalog;
import server.agents.progression.AgentTravelLegCatalog;
import server.agents.progression.AgentVictoriaRouteRuntime;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeConfig;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.AgentActivityHostState;
import server.maps.MapleMap;

import java.util.List;

/**
 * The standing readiness duty on the Agent tick (AgentSupplyReadiness). An Agent the host gave a duty looks at its
 * potions every CHECK_INTERVAL_MS; short of the standard on a town map, or a map without monsters that is no travel
 * station (a party quest's entry, a lobby), whose nearest potion supplier is at most STANDING_MAX_HOPS away
 * (DESK_MAX_HOPS on a party quest's recruit map), from a map the host lets it shop from (never a boss's gate), and free
 * (alive, outside an event, no objective, shop visit, trade, queued script task or Activity Host controller of its own,
 * and, while it follows, its anchor still on its map), it offers procurement a readiness request, which one visit to
 * the supplier fills for both HP and MP. It never goes when the trip cannot be paid for (the ammo top-up at a Victoria
 * supplier plus the potions to the standard at the supplier's cheapest potion, against its purse less its wallet
 * reserve) or when its Use tab has no room for a new kind of potion; it tells the host once and waits until its purse
 * grows by what it lacked, a slot frees, or it lacks fewer potions. Trips from one town cut short twice in a row (its
 * owner called it back, the shop failed) end its tries there until it has been elsewhere.
 *
 * <p>A duty holder's critical resupply ({@link #criticalGate}) passes a lighter check: it needs only the critical
 * deficit (back to POT_STOP) at the supplier's cheapest potion, without the ammo top-up. When even that cannot be
 * paid for it never walks to a shop; in a town or on a monster-free map it does not ask its party either (the readiness
 * duty says it cannot afford them), out in the field it asks as before, since a share is all that helps out there. On
 * a map the host keeps it on (a boss's gate) it only asks its party. Readiness never reads the critical path's
 * suppression.</p>
 */
public final class AgentSupplyReadinessRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentSupplyReadinessRuntime.class);
    /** Every readiness request id starts with this; the critical path's never do. */
    static final String REQUEST_PREFIX = "readiness:";

    private AgentSupplyReadinessRuntime() {
    }

    /** What an assessment of a trip found. */
    enum Assessed { GO, NO_SUPPLIER, CANNOT_AFFORD, NO_SPACE }

    /** What a duty holder's critical potion request may do: go as before, only ask its party, or neither. */
    enum CriticalGate { ALLOW, ASK_ONLY, REFUSE }

    /**
     * {@code stop}: the supplier ({@code hops} away); {@code spendable}: the purse less its wallet reserve;
     * {@code missingMesos}: what the trip lacks (CANNOT_AFFORD).
     */
    record Assessment(Assessed kind, AgentCareerShopCatalog.ShopStop stop, int hops, int spendable,
                      int missingMesos) {
        static Assessment of(Assessed kind) {
            return new Assessment(kind, null, -1, 0, 0);
        }
    }

    /** True for a readiness request id. */
    static boolean readiness(String requestId) {
        return requestId != null && requestId.startsWith(REQUEST_PREFIX);
    }

    /**
     * The readiness request to begin now, or null. Called by procurement before it looks at critical requests, while
     * no supply trip is under way; at most once per CHECK_INTERVAL_MS (a host's checkSoon brings it forward).
     */
    static AgentProcurementRequest select(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentSupplyReadinessState state = entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY);
        if (nowMs < state.nextCheckAtMs()) {
            return null;
        }
        state.scheduleNextCheck(nowMs + AgentSupplyReadiness.checkIntervalMs());
        state.noteMap(agent.getMapId());
        settleOrphan(agent, state, nowMs);
        AgentSupplyReadiness.Activity activity = AgentSupplyReadiness.standing(agent.getId());
        if (activity == null) {
            state.clearVerdict();
            return null;
        }
        AgentSupplyReadiness.Shortfall shortfall = AgentSupplyReadiness.shortfall(agent, activity);
        if (shortfall.none()) {
            state.clearVerdict();
            state.clearSpendCooldown();
            return null;
        }
        if (state.cooldownInForce(nowMs, mesos(agent), freeUseSlots(agent), shortfall, agent.getMapId()) != null
                || !free(entry, agent)) {
            return null;
        }
        if (!AgentSupplyReadiness.restocksFrom(agent.getId(), agent.getMapId())) {
            state.clearVerdict(); // a boss's gate: the host says its own no-shop line
            return null;
        }
        int maxHops = partyQuestDesk(agent.getMapId())
                ? AgentSupplyReadiness.deskMaxHops() : AgentSupplyReadiness.standingMaxHops();
        Assessment assessment = assess(entry, agent, activity, shortfall, maxHops, nowMs);
        if (assessment.kind() != Assessed.GO) {
            record(agent, state, activity, shortfall, assessment, nowMs);
            return null;
        }
        state.clearVerdict();
        AgentResourceCategory category = shortfall.hp() > 0
                ? AgentResourceCategory.HP_POTION : AgentResourceCategory.MP_POTION;
        AgentProcurementRequest request = new AgentProcurementRequest(
                REQUEST_PREFIX + category + ':' + nowMs, category,
                shortfall.hp() > 0 ? shortfall.hp() : shortfall.mp(), Math.max(0, assessment.spendable()),
                List.of(AgentProcurementMethod.NPC_SHOP), AgentSupplyUrgency.CRITICAL, "",
                nowMs + AgentSupplyReadiness.requestTtlMs(assessment.hops()));
        state.offer(request, activity, assessment.hops(), nowMs + AgentSupplyReadiness.tripEtaBaseMs()
                + (long) assessment.hops() * AgentSupplyReadiness.tripEtaPerHopMs(), agent.getMapId());
        return request;
    }

    /**
     * A critical potion request of a duty holder ({@code activity} non-null): ALLOW when the purse less its wallet
     * reserve covers the critical deficit (back to POT_STOP) at the nearest supplier's cheapest potion, or no supplier
     * is reachable (then it asks its party as before); otherwise no trip, and in a town no party ask either (REFUSE: the
     * host hears it cannot afford them), out in the field only the party ask (ASK_ONLY). A refusal holds until its
     * purse grows by what it lacked or it carries more of the kind; an allowance is looked at again after a check
     * interval.
     */
    static CriticalGate criticalGate(AgentRuntimeEntry entry, Character agent, AgentSupplyReadiness.Activity activity,
                                     AgentProcurementRequest request, long nowMs) {
        if (!AgentSupplyReadiness.restocksFrom(agent.getId(), agent.getMapId())) {
            return CriticalGate.ASK_ONLY; // a boss's gate: never a shop trip from here, only the party ask
        }
        AgentSupplyReadinessState state = entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY);
        AgentResourceCategory category = request.category();
        int count = AgentSupplyInventorySnapshot.quantity(agent, category);
        int mesos = mesos(agent);
        AgentSupplyReadinessState.CriticalCheck last = state.criticalCheck();
        boolean allowed;
        if (last != null && last.category() == category && last.allowed() && nowMs < last.untilMs()) {
            allowed = true;
        } else if (last != null && last.category() == category && !last.allowed()
                && (long) mesos < (long) last.mesosAt() + last.missingMesos() && count <= last.countAt()) {
            allowed = false;
        } else {
            int deficit = Math.max(1, AgentRuntimeConfig.cfg.POT_STOP - count);
            int missing = criticalMissingMesos(entry, agent, category == AgentResourceCategory.HP_POTION, deficit,
                    nowMs);
            allowed = missing <= 0;
            state.criticalCheck(new AgentSupplyReadinessState.CriticalCheck(category, allowed,
                    allowed ? nowMs + AgentSupplyReadiness.checkIntervalMs() : 0L, mesos, Math.max(0, missing), count));
            if (!allowed) {
                log.info("Agent '{}' cannot pay for a critical {} trip (deficit {}, mesos {}, missing {}): no shop"
                        + " trip", agent.getName(), category, deficit, mesos, missing);
                // in a town the desk shows it; a readiness verdict found here already (what the standard lacks) stays
                if (restockGround(agent) && state.verdictOn(agent.getMapId()) == null && state.verdict(
                        AgentSupplyReadinessState.Verdict.CANNOT_AFFORD, agent.getMapId(), missing, nowMs)) {
                    AgentSupplyReadiness.refused(agent.getId(), activity, AgentSupplyReadiness.Refusal.CANNOT_AFFORD,
                            AgentSupplyReadiness.shortfall(agent, activity), missing);
                }
            }
        }
        if (allowed) {
            return CriticalGate.ALLOW;
        }
        return restockGround(agent) ? CriticalGate.REFUSE : CriticalGate.ASK_ONLY;
    }

    /**
     * What the purse less its wallet reserve lacks for {@code deficit} potions of the kind at the nearest supplier's
     * cheapest one, wherever that supplier is; 0 when it is covered or no supplier is known.
     */
    private static int criticalMissingMesos(AgentRuntimeEntry entry, Character agent, boolean forHp, int deficit,
                                            long nowMs) {
        AgentCareerBuildBundle bundle = entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY)
                .bundle();
        if (bundle == null) {
            return 0;
        }
        AgentCareerShopCatalog.ShopStop stop = AgentCareerShopCatalog.forSupply(bundle,
                forHp ? AgentResourceCategory.HP_POTION : AgentResourceCategory.MP_POTION, agent.getMapId(),
                (source, destination) -> AgentVictoriaRouteRuntime.routeHops(entry, source, destination, nowMs));
        Shop shop = stop == null ? null : AgentShopGatewayRuntime.shop().findForNpc(stop.npcId());
        int price = shop == null ? -1 : AgentShopService.cheapestRecoveryPrice(agent, shop, forHp);
        if (price < 0) {
            return 0;
        }
        long spendable = (long) mesos(agent) - AgentSupplyRecoveryPolicy.minimumWalletReserve(agent);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) deficit * price - spendable));
    }

    /**
     * Whether a readiness trip to the nearest potion supplier within {@code maxHops} can go: the supplier, then the
     * spend (the ammo top-up there, then each short category at that shop's cheapest potion) against the purse less
     * the wallet reserve, then the Use slots the new kinds of potion take ({@link #useSlotsNeeded}). The shop is looked
     * up on the Agent tick only.
     */
    static Assessment assess(AgentRuntimeEntry entry, Character agent, AgentSupplyReadiness.Activity activity,
                             AgentSupplyReadiness.Shortfall shortfall, int maxHops, long nowMs) {
        AgentCareerBuildBundle bundle = entry.capabilityStates().require(AgentCareerProgressionState.STATE_KEY)
                .bundle();
        if (bundle == null) {
            return Assessment.of(Assessed.NO_SUPPLIER);
        }
        int mapId = agent.getMapId();
        AgentCareerShopCatalog.ShopStop stop = AgentCareerShopCatalog.forSupply(bundle,
                AgentResourceCategory.HP_POTION, mapId, (source, destination) ->
                        AgentVictoriaRouteRuntime.routeHops(entry, source, destination, nowMs));
        int hops = stop == null ? -1 : AgentVictoriaRouteRuntime.routeHops(entry, mapId, stop.mapId(), nowMs);
        if (stop == null || hops < 0 || hops > maxHops) {
            return Assessment.of(Assessed.NO_SUPPLIER);
        }
        Shop shop = AgentShopGatewayRuntime.shop().findForNpc(stop.npcId());
        int hpPrice = shortfall.hp() > 0 && shop != null ? AgentShopService.cheapestRecoveryPrice(agent, shop, true) : 0;
        int mpPrice = shortfall.mp() > 0 && shop != null ? AgentShopService.cheapestRecoveryPrice(agent, shop, false) : 0;
        if (shop == null || hpPrice < 0 || mpPrice < 0) {
            return Assessment.of(Assessed.NO_SUPPLIER);
        }
        long ammo = AgentPotionSupplierCatalog.ammoTopUpAllowedAt(stop.mapId())
                ? AgentShopService.plannedAmmoSpend(agent, shop) : 0L;
        long spend = ammo + (long) shortfall.hp() * hpPrice + (long) shortfall.mp() * mpPrice;
        long spendable = (long) mesos(agent) - AgentSupplyRecoveryPolicy.minimumWalletReserve(agent);
        if (spend > spendable) {
            return new Assessment(Assessed.CANNOT_AFFORD, stop, hops, (int) Math.max(0L, spendable),
                    (int) Math.min(Integer.MAX_VALUE, spend - spendable));
        }
        if (freeUseSlots(agent) < useSlotsNeeded(agent, shop, activity, shortfall)) {
            return new Assessment(Assessed.NO_SPACE, stop, hops, (int) spendable, 0);
        }
        return new Assessment(Assessed.GO, stop, hops, (int) Math.min(Integer.MAX_VALUE, spendable), 0);
    }

    /**
     * The free Use slots a readiness trip needs: one for each short kind it carries no stack of that this shop
     * sells and that still has room (bought potions stack onto it), plus, when it needs any, the one a party quest
     * keeps free for its items and rewards (KPQ admission refuses a member without it).
     */
    static int useSlotsNeeded(Character agent, Shop shop, AgentSupplyReadiness.Activity activity,
                              AgentSupplyReadiness.Shortfall shortfall) {
        int fresh = (shortfall.hp() > 0 && !AgentShopService.carriesStackWithRoom(agent, shop, true) ? 1 : 0)
                + (shortfall.mp() > 0 && !AgentShopService.carriesStackWithRoom(agent, shop, false) ? 1 : 0);
        return fresh == 0 ? 0 : fresh + (activity != null ? 1 : 0); // every activity is a party quest
    }

    /**
     * A readiness trip that cannot go: the verdict on this map, and a cooldown for CANNOT_AFFORD and NO_SPACE (until
     * the purse grows by what it lacked, a slot frees, or it lacks fewer potions); the host hears it when the verdict
     * changed.
     */
    private static void record(Character agent, AgentSupplyReadinessState state,
                               AgentSupplyReadiness.Activity activity, AgentSupplyReadiness.Shortfall shortfall,
                               Assessment assessment, long nowMs) {
        AgentSupplyReadinessState.Verdict verdict = switch (assessment.kind()) {
            case CANNOT_AFFORD -> AgentSupplyReadinessState.Verdict.CANNOT_AFFORD;
            case NO_SPACE -> AgentSupplyReadinessState.Verdict.NO_SPACE;
            default -> AgentSupplyReadinessState.Verdict.NO_SUPPLIER;
        };
        int mesos = mesos(agent);
        int freeSlots = freeUseSlots(agent);
        if (verdict == AgentSupplyReadinessState.Verdict.CANNOT_AFFORD) {
            state.cooldown(AgentSupplyReadinessState.Cooldown.CANNOT_AFFORD, Long.MAX_VALUE, mesos,
                    assessment.missingMesos(), freeSlots, shortfall, agent.getMapId());
        } else if (verdict == AgentSupplyReadinessState.Verdict.NO_SPACE) {
            state.cooldown(AgentSupplyReadinessState.Cooldown.NO_SPACE, Long.MAX_VALUE, mesos, 0, freeSlots,
                    shortfall, agent.getMapId());
        }
        if (!state.verdict(verdict, agent.getMapId(), assessment.missingMesos(), nowMs)) {
            return;
        }
        if (verdict != AgentSupplyReadinessState.Verdict.NO_SUPPLIER) {
            log.info("Agent '{}' stays put instead of restocking for a {}: {} on map {} (short hp {} mp {}, mesos {},"
                            + " missing {}, free use slots {})", agent.getName(), activity, verdict, agent.getMapId(),
                    shortfall.hp(), shortfall.mp(), mesos, assessment.missingMesos(), freeSlots);
        }
        AgentSupplyReadiness.refused(agent.getId(), activity, switch (verdict) {
            case CANNOT_AFFORD -> AgentSupplyReadiness.Refusal.CANNOT_AFFORD;
            case NO_SPACE -> AgentSupplyReadiness.Refusal.NO_SPACE;
            case NO_SUPPLIER -> AgentSupplyReadiness.Refusal.NO_SUPPLIER;
        }, shortfall, assessment.missingMesos());
    }

    /** The readiness trip began: the host hears it set off, about how long until it is back. */
    static void began(AgentRuntimeEntry entry, Character agent, AgentProcurementRequest request,
                      AgentSupplyProcurementState execution, long nowMs) {
        AgentSupplyReadinessState state = entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY);
        AgentSupplyReadiness.Activity activity = state.activity() == null
                ? AgentSupplyReadiness.Activity.PARTY_QUEST : state.activity();
        long etaMs = Math.max(0L, state.etaAtMs(request.requestId()) - nowMs);
        log.info("Agent '{}' set off to restock for a {}: request {} to supplier map {} ({} hops), back in about {} s",
                agent.getName(), activity, request.requestId(), execution.supplierMapId(), state.hops(),
                etaMs / 1000L);
        AgentSupplyReadiness.started(agent.getId(), activity, etaMs);
    }

    /** The readiness trip could not begin: no category is suppressed, it only waits a short trip's cooldown. */
    static void beginFailed(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentSupplyReadinessState state = entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY);
        int origin = state.originMapId() > 0 ? state.originMapId() : agent.getMapId();
        state.clearRequest();
        cutShort(agent, state, origin, nowMs);
        log.info("Agent '{}' could not set off to restock; trying again in {} ms", agent.getName(),
                AgentSupplyReadiness.shortTripCooldownMs());
    }

    /** The readiness request under way while its time is not up; null once it is (the trip then stalls). */
    static AgentProcurementRequest activeRequest(AgentRuntimeEntry entry, String requestId, long nowMs) {
        AgentProcurementRequest request = entry.capabilityStates()
                .require(AgentSupplyReadinessState.STATE_KEY).request();
        return request != null && request.requestId().equals(requestId) && nowMs <= request.expiresAtMs()
                ? request : null;
    }

    /**
     * The shop's outcome as a duty holder's trip counts it: a readiness trip is restored exactly when the standard is
     * met; a critical trip of a duty holder is restored too when it bought some and the standard is met.
     */
    static AgentSupplyProcurementOutcome judged(AgentRuntimeEntry entry, Character agent,
                                                AgentSupplyProcurementState execution,
                                                AgentSupplyProcurementOutcome outcome) {
        boolean readinessTrip = readiness(execution.requestId());
        AgentSupplyReadiness.Activity activity = readinessTrip
                ? activityOf(entry) : AgentSupplyReadiness.standing(agent.getId());
        if (activity == null) {
            return outcome;
        }
        boolean ready = AgentSupplyReadiness.shortfall(agent, activity).none();
        if (readinessTrip && !ready && outcome.restored()) {
            return withStatus(outcome, outcome.quantityAfter() > outcome.quantityBefore()
                            ? AgentSupplyProcurementOutcome.Status.PARTIALLY_RESTORED
                            : AgentSupplyProcurementOutcome.Status.NO_PROGRESS,
                    "shop visit did not reach the readiness standard");
        }
        if (ready && !outcome.restored()
                && (readinessTrip || outcome.quantityAfter() > outcome.quantityBefore())) {
            return withStatus(outcome, AgentSupplyProcurementOutcome.Status.RESTORED,
                    "readiness standard met after the shop visit");
        }
        return outcome;
    }

    /**
     * A readiness trip given up: INTERRUPTED when its owner called it back; else SHORT_MESOS when what is left of the
     * purse cannot buy what it still lacks at the supplier's cheapest potions, else SHORT_SPACE with no Use slot free,
     * else INTERRUPTED; each sets its cooldown.
     */
    static AgentSupplyReadiness.Outcome abandonedOutcome(AgentRuntimeEntry entry, Character agent,
                                                         AgentSupplyProcurementState execution, long nowMs) {
        AgentSupplyReadinessState state = entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY);
        int origin = state.originMapId() > 0 ? state.originMapId() : execution.returnMapId();
        if (execution.recalled()) {
            cutShort(agent, state, origin, nowMs);
            return AgentSupplyReadiness.Outcome.INTERRUPTED;
        }
        AgentSupplyReadiness.Shortfall shortfall = AgentSupplyReadiness.shortfall(agent, activityOf(entry));
        int mesos = mesos(agent);
        int freeSlots = freeUseSlots(agent);
        long missing = 0L;
        if (!shortfall.none() && execution.supplierNpcId() > 0) {
            Shop shop = AgentShopGatewayRuntime.shop().findForNpc(execution.supplierNpcId());
            int hpPrice = shop == null || shortfall.hp() <= 0 ? 0 : AgentShopService.cheapestRecoveryPrice(agent, shop, true);
            int mpPrice = shop == null || shortfall.mp() <= 0 ? 0 : AgentShopService.cheapestRecoveryPrice(agent, shop, false);
            long needed = (long) shortfall.hp() * Math.max(0, hpPrice) + (long) shortfall.mp() * Math.max(0, mpPrice);
            long spendable = (long) mesos - AgentSupplyRecoveryPolicy.minimumWalletReserve(agent);
            missing = Math.max(0L, needed - spendable);
        }
        long until = nowMs + AgentSupplyReadiness.shortTripCooldownMs();
        if (missing > 0L) {
            state.cooldown(AgentSupplyReadinessState.Cooldown.SHORT_MESOS, until, mesos,
                    (int) Math.min(Integer.MAX_VALUE, missing), freeSlots, shortfall, agent.getMapId());
            return AgentSupplyReadiness.Outcome.SHORT_MESOS;
        }
        if (!shortfall.none() && freeSlots == 0) {
            state.cooldown(AgentSupplyReadinessState.Cooldown.SHORT_SPACE, until, mesos, 0, freeSlots, shortfall,
                    agent.getMapId());
            return AgentSupplyReadiness.Outcome.SHORT_SPACE;
        }
        cutShort(agent, state, origin, nowMs);
        return AgentSupplyReadiness.Outcome.INTERRUPTED;
    }

    /**
     * A readiness trip from {@code originMapId} was cut short (or could not begin): it waits a short trip's cooldown;
     * the second in a row from that town, it gives the town up until it has been on another map.
     */
    private static void cutShort(Character agent, AgentSupplyReadinessState state, int originMapId, long nowMs) {
        if (state.interrupted(originMapId) >= 2) {
            state.cooldown(AgentSupplyReadinessState.Cooldown.GAVE_UP, Long.MAX_VALUE, mesos(agent), 0,
                    freeUseSlots(agent), null, originMapId);
            log.info("Agent '{}' gives up restocking from map {} after trips cut short in a row, until it has been"
                    + " elsewhere", agent.getName(), originMapId);
            return;
        }
        state.cooldown(AgentSupplyReadinessState.Cooldown.INTERRUPTED,
                nowMs + AgentSupplyReadiness.shortTripCooldownMs(), mesos(agent), 0, freeUseSlots(agent), null,
                originMapId);
    }

    /**
     * The readiness trip is over (procurement's finish, its only call site): the request is let go and the host hears
     * how it ended: READY when it succeeded with the standard met, else {@code given} (an abandoned trip's), else
     * INTERRUPTED.
     */
    static void finished(AgentRuntimeEntry entry, Character agent, String requestId, AgentObjectiveStatus status,
                         AgentSupplyReadiness.Outcome given) {
        AgentSupplyReadinessState state = entry.capabilityStates().require(AgentSupplyReadinessState.STATE_KEY);
        AgentSupplyReadiness.Activity activity = activityOf(entry);
        state.clearRequest();
        AgentSupplyReadiness.Outcome outcome = given;
        if (outcome == null) {
            outcome = status == AgentObjectiveStatus.SUCCEEDED
                    && AgentSupplyReadiness.shortfall(agent, activity).none()
                    ? AgentSupplyReadiness.Outcome.READY : AgentSupplyReadiness.Outcome.INTERRUPTED;
        }
        if (outcome == AgentSupplyReadiness.Outcome.READY) {
            state.resetInterrupted();
        }
        log.info("Agent '{}' readiness restock {} ended {} ({}): potions now hp {} mp {}", agent.getName(), requestId,
                outcome, status, AgentSupplyInventorySnapshot.quantity(agent, AgentResourceCategory.HP_POTION),
                AgentSupplyInventorySnapshot.quantity(agent, AgentResourceCategory.MP_POTION));
        AgentSupplyReadiness.finished(agent.getId(), activity, outcome);
    }

    /**
     * A readiness request that outlived its trip (the trip state was cleared by something else): it is let go as
     * INTERRUPTED, so the host still hears that it ended.
     */
    private static void settleOrphan(Character agent, AgentSupplyReadinessState state, long nowMs) {
        AgentProcurementRequest orphan = state.request();
        if (orphan == null) {
            return;
        }
        AgentSupplyReadiness.Activity activity = state.activity() == null
                ? AgentSupplyReadiness.Activity.PARTY_QUEST : state.activity();
        int origin = state.originMapId() > 0 ? state.originMapId() : agent.getMapId();
        state.clearRequest();
        cutShort(agent, state, origin, nowMs);
        log.info("Agent '{}' readiness request {} lost its trip; counted as interrupted", agent.getName(),
                orphan.requestId());
        AgentSupplyReadiness.finished(agent.getId(), activity, AgentSupplyReadiness.Outcome.INTERRUPTED);
    }

    /**
     * Free for a trip of its own: alive, outside an event instance, on a town or monster-free map, with no objective, pending shop
     * visit, open trade, queued script task, or Activity Host controller owning its foreground (an idle following
     * Agent has none); and while it follows, its anchor stands on its map and is not changing maps (an owner heading
     * out of town is followed, not left for the shop). Memory only.
     */
    static boolean free(AgentRuntimeEntry entry, Character agent) {
        if (agent.getHp() <= 0 || agent.getEventInstance() != null || !restockGround(agent)) {
            return false;
        }
        if (AgentObjectiveKernel.active(entry) != null || AgentShopStateRuntime.shopVisitPending(entry)
                || agent.getTrade() != null || entry.scriptTaskQueueState().hasTasks()) {
            return false;
        }
        if (AgentModeStateRuntime.following(entry)) {
            Character anchor = followAnchor(entry);
            if (anchor == null || anchor.getMapId() != agent.getMapId() || anchor.isChangingMaps()) {
                return false;
            }
        }
        return entry.capabilityStates().find(AgentActivityHostState.STATE_KEY)
                .map(host -> host.controllerId() == null).orElse(true);
    }

    /** Who a following Agent follows: its follow target, or with none its interaction target (its owner). */
    static Character followAnchor(AgentRuntimeEntry entry) {
        Character target = AgentRelationshipRuntime.followTarget(entry);
        return target != null ? target : AgentRelationshipRuntime.interactionTarget(entry);
    }

    /**
     * Where a duty holder sets off from and the host's desk speaks for it: a town map, or a map with no monster spawns
     * and no monsters on it (a party quest's entry map such as LPQ's Eos Tower 101st floor, a lobby) unless it is a
     * station of a travel leg (an Orbis platform): its owner may board any moment.
     */
    private static boolean restockGround(Character agent) {
        MapleMap map = agent.getMap();
        return map != null && (map.isTown() || !AgentTravelLegCatalog.station(map.getId())
                && map.getMonsterSpawn().isEmpty() && map.countMonsters() == 0);
    }

    /** A party quest's recruit map (AgentPartyQuestCatalog): its desk may send a companion DESK_MAX_HOPS away. */
    private static boolean partyQuestDesk(int mapId) {
        for (AgentPartyQuestDefinition definition : AgentPartyQuestCatalog.definitions()) {
            if (definition.recruitMapId() == mapId) {
                return true;
            }
        }
        return false;
    }

    private static AgentSupplyReadiness.Activity activityOf(AgentRuntimeEntry entry) {
        AgentSupplyReadiness.Activity activity = entry.capabilityStates()
                .require(AgentSupplyReadinessState.STATE_KEY).activity();
        return activity == null ? AgentSupplyReadiness.Activity.PARTY_QUEST : activity;
    }

    private static AgentSupplyProcurementOutcome withStatus(AgentSupplyProcurementOutcome outcome,
                                                            AgentSupplyProcurementOutcome.Status status,
                                                            String reason) {
        return new AgentSupplyProcurementOutcome(status, outcome.category(), outcome.quantityBefore(),
                outcome.quantityAfter(), outcome.mesosBefore(), outcome.mesosAfter(), outcome.occurredAtMs(), reason);
    }

    static int mesos(Character agent) {
        return Math.max(0, agent.getMeso());
    }

    static int freeUseSlots(Character agent) {
        Inventory use = agent.getInventory(InventoryType.USE);
        return use == null ? 0 : Math.max(0, use.getNumFreeSlot());
    }
}
