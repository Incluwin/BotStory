package server.agents.capabilities.supplies;

import client.Character;
import client.inventory.InventoryType;
import constants.id.ItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.contracts.AgentProcurementMethod;
import server.agents.capabilities.contracts.AgentProcurementRequest;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.contracts.AgentSupplyUrgency;
import server.agents.capabilities.shop.AgentShopService;
import server.agents.capabilities.shop.AgentShopStateRuntime;
import server.agents.capabilities.shop.AgentShopWorkflowPhase;
import server.agents.capabilities.movement.AgentChairService;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.objectives.AgentObjectiveDefinition;
import server.agents.objectives.AgentObjectiveKernel;
import server.agents.objectives.AgentObjectiveSource;
import server.agents.objectives.AgentObjectiveStatus;
import server.agents.progression.AgentCareerBuildBundle;
import server.agents.progression.AgentCareerProgressionState;
import server.agents.progression.AgentCareerShopCatalog;
import server.agents.progression.AgentVictoriaRouteRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSessionEventRuntime;
import server.agents.events.AgentDomainEvent;
import server.agents.runtime.maintenance.AgentRemediationCoordinator;
import server.agents.runtime.maintenance.AgentRemediationFrame;
import server.agents.runtime.maintenance.AgentRemediationKind;
import server.agents.runtime.maintenance.AgentRemediationState;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/** Executes urgent supply requests as route-aware maintenance without destroying foreground intent. */
public final class AgentSupplyProcurementRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentSupplyProcurementRuntime.class);
    private static final String OBJECTIVE_PREFIX = "maintenance:resupply:";

    private AgentSupplyProcurementRuntime() {
    }

    /** Runs automatic maintenance only for explicitly self-sustaining sessions. */
    public static boolean tickIfSelfSustaining(
            AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (entry == null || agent == null) {
            return false;
        }
        if (!selfSustaining(entry)) {
            releaseInapplicableRestoredMaintenance(entry, agent, nowMs);
            return false;
        }
        return tick(entry, agent, nowMs);
    }

    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentResourcePlanningState planning = entry.capabilityStates().require(
                AgentResourcePlanningState.STATE_KEY);
        AgentSupplyProcurementState execution = entry.capabilityStates().require(
                AgentSupplyProcurementState.STATE_KEY);
        AgentSupplyMaintenanceEvaluationState evaluations = entry.capabilityStates().require(
                AgentSupplyMaintenanceEvaluationState.STATE_KEY);
        execution.retainPartyAsks(category -> planning.procurement(category) != null);
        if (!execution.isActive()
                && releaseInapplicableRestoredMaintenance(
                entry, agent, planning, evaluations, nowMs)) {
            return false;
        }
        AgentSupplyThresholdChangedEvent signal = null;
        AgentProcurementRequest request;
        // A duty holder's (AgentSupplyReadiness) critical potion requests pass its affordability check first.
        AgentSupplyReadiness.Activity dutyHolder = null;
        if (execution.isActive()) {
            if (!AgentSupplyReadinessRuntime.readiness(execution.requestId())
                    && !AgentSupplyRecoveryPolicy.requiresAutomaticReserve(
                    agent, execution.category())) {
                finish(entry, agent, planning, execution, AgentObjectiveStatus.CANCELLED,
                        "beginner progression does not require a potion reserve", nowMs);
                return false;
            }
            request = activeRequest(entry, planning, execution, nowMs);
        } else {
            // The standing readiness duty goes first: one visit fills HP and MP to the standard.
            request = AgentSupplyReadinessRuntime.select(entry, agent, nowMs);
            if (request == null) {
                if (hasPotionRequest(planning)) {
                    dutyHolder = AgentSupplyReadiness.standing(agent.getId());
                    dropRestoredPotionRequests(planning, evaluations, agent, dutyHolder != null);
                }
                signal = evaluations.next();
                request = signal == null
                        ? null : selectRequest(planning, execution, agent, nowMs, signal.category());
                if (signal != null && request == null) {
                    evaluations.resolve(signal.category());
                }
                if (request == null) {
                    request = selectRequest(planning, execution, agent, nowMs, null);
                }
            }
        }

        if (request == null) {
            if (!execution.isActive()) {
                return false;
            }
            if (execution.phase() != AgentSupplyProcurementState.Phase.RETURNING
                    && execution.phase() != AgentSupplyProcurementState.Phase.STALLED) {
                stall(entry, agent, planning, execution,
                        "supply request expired before targets were restored", nowMs);
            }
            // The shop leg is over or given up: a request that resolved meanwhile (the
            // purchase already restored the reserve) must not strand the trip home.
            return execution.phase() != AgentSupplyProcurementState.Phase.RETURNING
                    || returnToPlan(entry, agent, planning, execution, nowMs);
        }

        if (!execution.isActive()) {
            boolean readinessTrip = AgentSupplyReadinessRuntime.readiness(request.requestId());
            AgentSupplyReadinessRuntime.CriticalGate gate = dutyHolder != null && potion(request.category())
                    ? AgentSupplyReadinessRuntime.criticalGate(entry, agent, dutyHolder, request, nowMs)
                    : AgentSupplyReadinessRuntime.CriticalGate.ALLOW;
            if (gate != AgentSupplyReadinessRuntime.CriticalGate.ALLOW) {
                // It cannot pay even for the potions back to POT_STOP: it never walks to a shop. Out in
                // the field it still asks its party, once, as before; in a town it does not (it says it
                // cannot afford them) and waits until its purse grows.
                if (gate == AgentSupplyReadinessRuntime.CriticalGate.ASK_ONLY) {
                    awaitingPartySupply(entry, agent, request, execution, nowMs);
                }
                return false;
            }
            if (awaitingPartySupply(entry, agent, request, execution, nowMs)) {
                return false;
            }
            if (!begin(entry, agent, request, execution, nowMs)) {
                if (readinessTrip) {
                    AgentSupplyReadinessRuntime.beginFailed(entry, agent, nowMs);
                    return false;
                }
                // begin() scans the local shops first; retrying a refused start every tick
                // repeats that scan for as long as the shortage lasts.
                execution.suppress(request.category(),
                        nowMs + AgentSupplyRecoveryPolicy.failedProcurementCooldownMs());
                log.warn("Agent '{}' could not start {} resupply; suppressing the category for {} ms",
                        agent.getName(), request.category(),
                        AgentSupplyRecoveryPolicy.failedProcurementCooldownMs());
                return false;
            }
            evaluations.resolve(request.category());
            AgentSupplyReadinessRuntime.ownTrip(entry);
            if (readinessTrip) {
                AgentSupplyReadinessRuntime.began(entry, agent, request, execution, nowMs);
            }
        }

        return switch (execution.phase()) {
            case TRAVEL_TO_SUPPLIER -> travelToSupplier(entry, agent, planning, execution, nowMs);
            case SHOPPING -> shop(entry, agent, planning, execution, nowMs);
            case RETURNING -> returnToPlan(entry, agent, planning, execution, nowMs);
            case RESTING -> restForRecovery(entry, agent, planning, execution, request, nowMs);
            case INCOME_RECOVERY -> recoverIncome(entry, agent, planning, execution, request, nowMs);
            case STALLED -> true;
            case IDLE -> false;
        };
    }

    private static boolean releaseInapplicableRestoredMaintenance(
            AgentRuntimeEntry entry, Character agent, long nowMs) {
        return releaseInapplicableRestoredMaintenance(
                entry,
                agent,
                entry.capabilityStates().require(AgentResourcePlanningState.STATE_KEY),
                entry.capabilityStates().require(AgentSupplyMaintenanceEvaluationState.STATE_KEY),
                nowMs);
    }

    private static boolean releaseInapplicableRestoredMaintenance(
            AgentRuntimeEntry entry,
            Character agent,
            AgentResourcePlanningState planning,
            AgentSupplyMaintenanceEvaluationState evaluations,
            long nowMs) {
        AgentObjectiveDefinition active = AgentObjectiveKernel.active(entry);
        if (active == null || !"maintenance.resupply".equals(active.type())
                || active.objectiveId().contains(AgentSupplyReadinessRuntime.REQUEST_PREFIX)) {
            // A readiness trip is the host's duty, not a beginner's reserve: it is never cancelled here.
            return false;
        }
        AgentResourceCategory category = java.util.Arrays.stream(
                        new AgentResourceCategory[]{
                                AgentResourceCategory.HP_POTION,
                                AgentResourceCategory.MP_POTION})
                .filter(candidate -> active.objectiveId().contains(':' + candidate.name() + ':'))
                .findFirst().orElse(null);
        if (category == null
                || AgentSupplyRecoveryPolicy.requiresAutomaticReserve(agent, category)) {
            return false;
        }
        planning.resolve(category);
        evaluations.resolve(category);
        return AgentObjectiveKernel.finishAndResume(
                entry, active.objectiveId(), AgentObjectiveStatus.CANCELLED,
                "beginner progression does not require a potion reserve", nowMs);
    }

    private static AgentProcurementRequest selectRequest(AgentResourcePlanningState planning,
                                                         AgentSupplyProcurementState execution,
                                                         Character agent,
                                                         long nowMs,
                                                         AgentResourceCategory category) {
        return planning.procurementSnapshot().values().stream()
                .filter(candidate -> candidate.expiresAtMs() >= nowMs)
                .filter(candidate -> !execution.suppressed(candidate.category(), nowMs))
                .filter(candidate -> candidate.urgency().ordinal() >= AgentSupplyUrgency.CRITICAL.ordinal())
                .filter(candidate -> candidate.permittedMethods().contains(AgentProcurementMethod.NPC_SHOP))
                .filter(candidate -> AgentSupplyRecoveryPolicy.requiresAutomaticReserve(
                        agent, candidate.category()))
                .filter(candidate -> category == null || candidate.category() == category)
                .max(Comparator.comparingInt(candidate -> candidate.urgency().ordinal()))
                .orElse(null);
    }

    /**
     * The request the trip under way serves: a readiness trip's own while its time is not up (null after: the trip
     * stalls as expired), else the planning request of its category.
     */
    private static AgentProcurementRequest activeRequest(AgentRuntimeEntry entry,
                                                         AgentResourcePlanningState planning,
                                                         AgentSupplyProcurementState execution,
                                                         long nowMs) {
        if (AgentSupplyReadinessRuntime.readiness(execution.requestId())) {
            return AgentSupplyReadinessRuntime.activeRequest(entry, execution.requestId(), nowMs);
        }
        return planning.procurement(execution.category());
    }

    /**
     * Its owner gave it a movement order (follow, stop, move here, hunt here, patrol) while it was on a readiness trip:
     * the order wins. The trip is given up where it stands, ending in the mode the order asks for ({@code follow}), and
     * counts as interrupted (a short trip's cooldown, no party ask, no broke line). A critical trip is left alone.
     * False when no readiness trip was under way. Thread-safe: it only marks the trip, the Agent tick ends it.
     */
    public static boolean ownerCalledBack(AgentRuntimeEntry entry, boolean follow) {
        AgentSupplyProcurementState execution = entry == null ? null
                : entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY).orElse(null);
        if (execution == null || !execution.isActive() || execution.abandoned()
                || !AgentSupplyReadinessRuntime.readiness(execution.requestId())) {
            return false;
        }
        execution.markRecalled("its owner called it back");
        execution.rememberForegroundMode(follow, false);
        log.info("Agent '{}' was called back from its readiness trip {} by its owner", entry.bot() == null ? "?"
                : entry.bot().getName(), execution.requestId());
        return true;
    }

    private static boolean potion(AgentResourceCategory category) {
        return category == AgentResourceCategory.HP_POTION || category == AgentResourceCategory.MP_POTION;
    }

    private static boolean hasPotionRequest(AgentResourcePlanningState planning) {
        return critical(planning.procurement(AgentResourceCategory.HP_POTION))
                || critical(planning.procurement(AgentResourceCategory.MP_POTION));
    }

    private static boolean critical(AgentProcurementRequest request) {
        return request != null && request.urgency().ordinal() >= AgentSupplyUrgency.CRITICAL.ordinal();
    }

    /**
     * A critical potion request that its potions no longer bear out (AgentPotionReserve.tripNeeded) is resolved, so it
     * never starts a trip or a party ask of its own: once they number POT_STOP again (a readiness visit, a share, or the
     * other kind's shop visit, which buys HP and MP both, filled them since it was planned), and for an Agent with no
     * standing duty also once the reserve the shop buys to is met. Found live 2026-10-05: an MP trip went out on a
     * request planned before the HP visit had already bought the MP (40 -> 49, 25 -> 25), and one with twelve bars of
     * MP in nine potions went out every five minutes to buy nothing (9 -> 9).
     */
    private static void dropRestoredPotionRequests(AgentResourcePlanningState planning,
                                                   AgentSupplyMaintenanceEvaluationState evaluations,
                                                   Character agent,
                                                   boolean dutyHolder) {
        for (AgentResourceCategory category : new AgentResourceCategory[]{
                AgentResourceCategory.HP_POTION, AgentResourceCategory.MP_POTION}) {
            if (critical(planning.procurement(category))
                    && !AgentPotionReserve.tripNeeded(agent, category, dutyHolder)) {
                planning.resolve(category);
                evaluations.resolve(category);
            }
        }
    }

    /**
     * A companion following its owner asks the owner and party for the supply first and stays
     * with them; it leaves for a shop only when nobody shared within the wait.
     */
    private static boolean awaitingPartySupply(AgentRuntimeEntry entry,
                                               Character agent,
                                               AgentProcurementRequest request,
                                               AgentSupplyProcurementState execution,
                                               long nowMs) {
        if (selfSustaining(entry)
                || AgentSupplyReadinessRuntime.readiness(request.requestId())
                || !AgentModeStateRuntime.following(entry)
                || AgentRelationshipRuntime.followTarget(entry) == null) {
            return false;
        }
        long askedAtMs = execution.partyAskedAtMs(request.category());
        if (askedAtMs < 0L) {
            execution.markPartyAsked(request.category(), nowMs);
            askPartyForSupply(entry, agent, request.category());
            log.info("Agent '{}' is low on {} and asked its party before leaving to shop",
                    agent.getName(), request.category());
            return true;
        }
        return nowMs < askedAtMs + AgentSupplyRecoveryPolicy.partySupplyWaitMs();
    }

    static boolean begin(AgentRuntimeEntry entry,
                         Character agent,
                         AgentProcurementRequest request,
                         AgentSupplyProcurementState execution,
                         long nowMs) {
        AgentShopService.onMapChange(entry, agent, AgentInventoryGatewayRuntime.inventory());
        int supplierMapId = agent.getMapId();
        int supplierNpcId = 0;
        AgentSupplyProcurementState.Phase phase = AgentSupplyProcurementState.Phase.SHOPPING;
        if (!AgentShopStateRuntime.shopVisitPending(entry)) {
            AgentCareerBuildBundle bundle = entry.capabilityStates().require(
                    AgentCareerProgressionState.STATE_KEY).bundle();
            if (bundle == null) {
                return false;
            }
            AgentCareerShopCatalog.ShopStop stop = AgentCareerShopCatalog.forSupply(
                    bundle, request.category(), agent.getMapId(), routeHops(entry, nowMs));
            if (stop == null) {
                // Off every portal graph (a party quest's stages, Ereve), behind a one-way portal
                // outside Victoria, or cut off by blocked portals: a trip toward a shop the router
                // cannot reach (or cannot walk back from) only strands it.
                log.info("Agent '{}' has no {} supplier the portal router can reach from map {}; asking the party instead",
                        agent.getName(), request.category(), agent.getMapId());
                if (execution.partyAskedAtMs(request.category()) < 0L
                        && !AgentSupplyReadinessRuntime.readiness(request.requestId())) {
                    askPartyForSupply(entry, agent, request.category());
                }
                return false;
            }
            supplierMapId = stop.mapId();
            supplierNpcId = stop.npcId();
            phase = supplierMapId == agent.getMapId()
                    ? AgentSupplyProcurementState.Phase.SHOPPING
                    : AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER;
        } else {
            // The trip adopts the visit onMapChange opened, and its shopkeeper: a retry after that visit ends
            // BLOCKED or CANCELLED asks the same shopkeeper (live, NPC 0 gave the trip up as "unavailable").
            supplierNpcId = AgentShopService.pendingVisitNpcId(entry, agent);
        }

        String maintenanceId = OBJECTIVE_PREFIX + request.requestId();
        AgentObjectiveDefinition maintenance = new AgentObjectiveDefinition(
                maintenanceId, "maintenance.resupply", 1_000, request.expiresAtMs(), 2,
                AgentObjectiveSource.RECOVERY_POLICY, "supply-procurement-v2",
                request.objectiveId().isBlank() ? maintenanceId : request.objectiveId());
        AgentObjectiveDefinition foreground = AgentObjectiveKernel.active(entry);
        String parentCorrelationId = foreground == null ? request.objectiveId()
                : foreground.correlationId();
        AgentRemediationFrame remediation = new AgentRemediationFrame(
                "resupply:" + request.requestId(),
                AgentRemediationKind.LOW_SUPPLIES,
                maintenanceId,
                parentCorrelationId == null ? "" : parentCorrelationId,
                1,
                nowMs,
                request.expiresAtMs(),
                Map.of("resourceCategory", request.category().name(),
                        "targetQuantity", Integer.toString(request.quantity())));
        if (!AgentRemediationCoordinator.begin(entry, remediation, maintenance,
                request.category() + " is " + request.urgency(), nowMs)) {
            return false;
        }
        execution.start(request.requestId(), maintenanceId, request.category(), supplierMapId,
                supplierNpcId, agent.getMapId(), phase,
                AgentSupplyInventorySnapshot.quantity(agent, request.category()),
                Math.max(0, agent.getMeso()));
        execution.rememberForegroundMode(
                AgentModeStateRuntime.following(entry), AgentModeStateRuntime.grinding(entry));
        journal(entry, agent, request, "started", "supply shortage interrupted foreground work",
                Map.of("phase", phase.name(),
                        "sourceMapId", Integer.toString(agent.getMapId()),
                        "supplierMapId", Integer.toString(supplierMapId),
                        "quantityBefore", Integer.toString(execution.quantityBefore()),
                        "mesosBefore", Integer.toString(execution.mesosBefore())), nowMs);
        log.info("Agent '{}' suspended foreground work for {} resupply: phase={} sourceMap={} supplierMap={} supplierNpc={} existingShopVisit={}",
                agent.getName(), request.category(), phase, agent.getMapId(), supplierMapId,
                supplierNpcId, AgentShopStateRuntime.shopVisitPending(entry));
        if (AgentShopStateRuntime.shopVisitPending(entry)) {
            execution.markShopRequested();
        }
        return true;
    }

    private static boolean travelToSupplier(AgentRuntimeEntry entry,
                                            Character agent,
                                            AgentResourcePlanningState planning,
                                            AgentSupplyProcurementState execution,
                                            long nowMs) {
        if (!keepSupplierWithinReach(entry, agent, planning, execution, nowMs)) {
            return true;
        }
        AgentRouteOutcome outcome = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                entry, agent, execution.supplierMapId(), nowMs);
        if (outcome.status() == AgentRouteStatus.NO_ROUTE) {
            recordFailure(entry, agent, execution, AgentSupplyProcurementOutcome.Status.ROUTE_FAILED,
                    "no portal route reaches the selected supplier", nowMs);
            stall(entry, agent, planning, execution,
                    "no portal route reaches the selected supplier", nowMs);
            return true;
        }
        if (outcome.status() != AgentRouteStatus.ARRIVED) {
            String stuck = noProgress(agent, execution, outcome,
                    AgentSupplyProcurementOutcome.NO_PROGRESS_TOWARD_SUPPLIER, nowMs);
            if (stuck != null) {
                avoidStalledHop(entry, agent, outcome, nowMs);
                recordFailure(entry, agent, execution, AgentSupplyProcurementOutcome.Status.ROUTE_FAILED,
                        stuck, nowMs);
                stall(entry, agent, planning, execution, stuck, nowMs);
            }
            return true;
        }
        AgentShopStateRuntime.setShopSellTrashPending(entry, true);
        if (!AgentShopService.requestVisitAtNpc(entry, agent, execution.supplierNpcId(),
                AgentSupplyRecoveryPolicy.minimumWalletReserve(agent))) {
            recordFailure(entry, agent, execution, AgentSupplyProcurementOutcome.Status.SHOP_FAILED,
                    "selected supplier NPC is unavailable", nowMs);
            stall(entry, agent, planning, execution,
                    "selected supplier NPC is unavailable", nowMs);
            return true;
        }
        execution.markShopRequested();
        return true;
    }

    /**
     * Keeps the trip on a supplier the router reaches without a detour. A portal that keeps
     * refusing entry is blocked for a while and the router walks around it, which from
     * Kerning's outskirts means twenty maps through Lith Harbor and Henesys. The trip may
     * never become longer than it was when it started (a stray step through a portal on the
     * way stays inside that); otherwise switch to a supplier within that length or give the
     * trip up. Only a self-sustaining session accepts the detour. Returns false when the trip
     * was given up.
     */
    private static boolean keepSupplierWithinReach(AgentRuntimeEntry entry,
                                                   Character agent,
                                                   AgentResourcePlanningState planning,
                                                   AgentSupplyProcurementState execution,
                                                   long nowMs) {
        int budget = execution.supplierHopBudget();
        int hops = AgentVictoriaRouteRuntime.routeHops(
                entry, agent.getMapId(), execution.supplierMapId(), nowMs);
        if (hops >= 0 && (budget < 0 || hops <= budget)) {
            if (budget < 0) {
                execution.setSupplierHopBudget(hops);
            }
            return true;
        }
        boolean selfSustaining = selfSustaining(entry);
        AgentCareerBuildBundle bundle = entry.capabilityStates().require(
                AgentCareerProgressionState.STATE_KEY).bundle();
        AgentCareerShopCatalog.ShopStop nearer = bundle == null ? null
                : AgentCareerShopCatalog.forSupply(
                bundle, execution.category(), agent.getMapId(), routeHops(entry, nowMs));
        int nearerHops = nearer == null ? -1
                : AgentVictoriaRouteRuntime.routeHops(entry, agent.getMapId(), nearer.mapId(), nowMs);
        String change = hops < 0 ? "no longer has a route"
                : "needs " + hops + " portal hops, more than the " + budget + " the trip started with";
        if (nearer != null && nearer.mapId() != execution.supplierMapId()
                && (selfSustaining || budget < 0 || nearerHops <= budget)) {
            log.info("Agent '{}' switched {} resupply from supplier map {} to map {} ({} portal hops): the first supplier {}",
                    agent.getName(), execution.category(), execution.supplierMapId(),
                    nearer.mapId(), nearerHops, change);
            execution.redirectSupplier(nearer.mapId(), nearer.npcId(),
                    selfSustaining || budget < 0 ? nearerHops : budget);
            return true;
        }
        if (hops >= 0 && selfSustaining) {
            execution.setSupplierHopBudget(hops);
            return true;
        }
        String reason = hops < 0 ? "no portal route reaches the selected supplier"
                : "the route to the selected supplier " + change;
        recordFailure(entry, agent, execution, AgentSupplyProcurementOutcome.Status.ROUTE_FAILED,
                reason, nowMs);
        stall(entry, agent, planning, execution, reason, nowMs);
        return false;
    }

    /**
     * Why the walk counts as stuck: it has stood on one map for TRAVEL_NO_PROGRESS_MS without crossing to another
     * (live 2026-10-09: four companions held at Another Entrance's arrival portal for 8+ minutes, the router walking
     * them toward a portal their navigation could not reach, with no line logged). Null while it still has time; else
     * {@code stalled} ("no progress toward ...") and where it stood.
     */
    private static String noProgress(Character agent,
                                     AgentSupplyProcurementState execution,
                                     AgentRouteOutcome outcome,
                                     String stalled,
                                     long nowMs) {
        long stalledMs = execution.travelStalledMs(agent.getMapId(), nowMs);
        if (stalledMs < AgentSupplyRecoveryPolicy.travelNoProgressMs()) {
            return null;
        }
        return stalled + ": " + stalledMs + " ms on map " + agent.getMapId()
                + " without reaching " + (stalledHop(agent, outcome)
                ? "its portal to map " + outcome.nextMapId() : "its next portal");
    }

    /** True when the route named the next map it was walking this map's portal to. */
    private static boolean stalledHop(Character agent, AgentRouteOutcome outcome) {
        return outcome.sourceMapId() == agent.getMapId() && outcome.nextMapId() >= 0
                && outcome.nextMapId() != outcome.sourceMapId();
    }

    /**
     * Keeps this Agent's route off the hop it stood before for STALLED_HOP_BLOCK_MS, so its next trip after the
     * cooldown takes another supplier or way, or asks its party when none is left, instead of walking into the same
     * hold again (a walk out, TRAVEL_NO_PROGRESS_MS standing, a warp back and a party ask, over and over).
     */
    private static void avoidStalledHop(AgentRuntimeEntry entry,
                                        Character agent,
                                        AgentRouteOutcome outcome,
                                        long nowMs) {
        if (!stalledHop(agent, outcome)) {
            return;
        }
        AgentVictoriaRouteRuntime.avoidHop(entry, outcome.sourceMapId(), outcome.nextMapId(),
                nowMs + AgentSupplyRecoveryPolicy.stalledHopBlockMs());
    }

    /**
     * The walk back stood on one map for TRAVEL_NO_PROGRESS_MS, or a restocked one found no route back: the plan
     * resumes where the Agent stands. After a purchase the trip still succeeded, so it neither waits out a cooldown nor
     * asks the party for potions it carries; a trip already given up ends as one.
     */
    private static boolean resumeWhereItStands(AgentRuntimeEntry entry,
                                               Character agent,
                                               AgentResourcePlanningState planning,
                                               AgentSupplyProcurementState execution,
                                               String stuck,
                                               long nowMs) {
        log.warn("Agent '{}' stopped walking back to map {} after its {} resupply ({}): {}",
                agent.getName(), execution.returnMapId(), execution.category(),
                execution.abandoned() ? "not restocked" : "restocked", stuck);
        if (execution.abandoned()) {
            return finishAbandoned(entry, agent, planning, execution, nowMs);
        }
        finish(entry, agent, planning, execution, AgentObjectiveStatus.SUCCEEDED,
                "shop transaction completed; plan resumed where it stands: " + stuck, nowMs);
        return false;
    }

    private static IntBinaryOperator routeHops(AgentRuntimeEntry entry, long nowMs) {
        return (sourceMapId, destinationMapId) -> AgentVictoriaRouteRuntime.routeHops(
                entry, sourceMapId, destinationMapId, nowMs);
    }

    private static boolean shop(AgentRuntimeEntry entry,
                                Character agent,
                                AgentResourcePlanningState planning,
                                AgentSupplyProcurementState execution,
                                long nowMs) {
        if (!execution.shopRequested()) {
            if (nowMs < execution.shopRetryAtMs()) {
                return true;
            }
            AgentShopStateRuntime.setShopSellTrashPending(entry, true);
            if (!AgentShopService.requestVisitAtNpc(entry, agent, execution.supplierNpcId(),
                    AgentSupplyRecoveryPolicy.minimumWalletReserve(agent))) {
                recordFailure(entry, agent, execution, AgentSupplyProcurementOutcome.Status.SHOP_FAILED,
                        "selected supplier NPC is unavailable", nowMs);
                stall(entry, agent, planning, execution,
                        "selected supplier NPC is unavailable", nowMs);
                return true;
            }
            execution.markShopRequested();
        }
        if (AgentShopStateRuntime.shopVisitPending(entry)) {
            AgentShopService.tickShopVisit(entry, agent, AgentInventoryGatewayRuntime.inventory());
            return true;
        }
        AgentShopWorkflowPhase phase = AgentShopStateRuntime.workflow(entry).phase();
        if (phase == AgentShopWorkflowPhase.COMPLETED) {
            AgentProcurementRequest request = activeRequest(entry, planning, execution, nowMs);
            int after = AgentSupplyInventorySnapshot.quantity(agent, execution.category());
            // An ordinary potion trip is judged by the reserve the shop buys to; a readiness trip and an ammo trip by count.
            AgentPotionReserve reserve = potion(execution.category())
                    && !AgentSupplyReadinessRuntime.readiness(execution.requestId())
                    ? AgentPotionReserve.of(agent, execution.category()) : null;
            int target = request == null ? after : request.quantity() + execution.quantityBefore();
            log.info("Agent '{}' finished shopping at NPC {} on map {}: {} {} -> {} ({}), potions hp {} mp {},"
                            + " mesos {} -> {}", agent.getName(), execution.supplierNpcId(), agent.getMapId(),
                    execution.category(), execution.quantityBefore(), after,
                    reserve == null ? "target " + target
                            : "reserve " + reserve.capacity() + "/" + reserve.target(),
                    AgentSupplyInventorySnapshot.quantity(agent, AgentResourceCategory.HP_POTION),
                    AgentSupplyInventorySnapshot.quantity(agent, AgentResourceCategory.MP_POTION),
                    execution.mesosBefore(), Math.max(0, agent.getMeso()));
            boolean restored = reserve == null ? after >= target : reserve.restoredSince(execution.quantityBefore());
            AgentSupplyProcurementOutcome outcome = AgentSupplyReadinessRuntime.judged(entry, agent, execution,
                    reconcile(execution, agent, after, restored, reserve != null, nowMs));
            execution.complete(outcome);
            journalOutcome(entry, agent, execution.requestId(), outcome);
            if (!outcome.restored()) {
                // Resting and grinding for mesos keeps a companion away from its owner for many
                // minutes; only a self-sustaining session earns its own supplies that way.
                // Everyone else gives the trip up below, rejoins and asks the party.
                if (outcome.requiresRecoveryIncome() && selfSustaining(entry)) {
                    return beginRecovery(entry, agent, planning, execution, request, outcome, nowMs);
                }
                stall(entry, agent, planning, execution, outcome.reason(), nowMs);
                return true;
            }
            execution.markReturning();
            return true;
        }
        if (phase == AgentShopWorkflowPhase.BLOCKED || phase == AgentShopWorkflowPhase.CANCELLED) {
            String workflowReason = AgentShopStateRuntime.workflow(entry).reason();
            String reason = "shop transaction ended in " + phase
                    + (workflowReason == null || workflowReason.isBlank() ? "" : ": " + workflowReason);
            recordFailure(entry, agent, execution, AgentSupplyProcurementOutcome.Status.SHOP_FAILED,
                    reason, nowMs);
            if (execution.shopFailures() < AgentSupplyRecoveryPolicy.shopRetryLimit()) {
                execution.retryShopVisit(agent.getMapId() == execution.supplierMapId(),
                        nowMs + AgentSupplyRecoveryPolicy.shopRetryDelayMs());
                AgentProcurementRequest request = activeRequest(entry, planning, execution, nowMs);
                if (request != null) {
                    journal(entry, agent, request, "shop-retry", reason,
                            Map.of("shopFailures", Integer.toString(execution.shopFailures()),
                                    "retryLimit", Integer.toString(
                                            AgentSupplyRecoveryPolicy.shopRetryLimit())), nowMs);
                }
                log.info("Agent '{}' retrying supplier visit {}/{} after: {}",
                        agent.getName(), execution.shopFailures(),
                        AgentSupplyRecoveryPolicy.shopRetryLimit(), reason);
                return true;
            }
            stall(entry, agent, planning, execution, reason, nowMs);
            return true;
        }
        return true;
    }

    private static boolean returnToPlan(AgentRuntimeEntry entry,
                                        Character agent,
                                        AgentResourcePlanningState planning,
                                        AgentSupplyProcurementState execution,
                                        long nowMs) {
        if (execution.abandoned() && execution.recalled()) {
            // Its owner's order took over where it stands: no walk back to the map it left.
            return finishAbandoned(entry, agent, planning, execution, nowMs);
        }
        if (execution.resumeFollow() && followAnchor(entry) != null
                && AgentModeService.mayStartFollow(entry)) {
            // A companion's plan location is its owner, and follow warps it back beside the
            // owner; walking to the map it left can detour for minutes away from the party.
            // Only while follow may start, though: live, an owner entered a party quest
            // mid-trip and the host held the companion off follow to wait outside, so follow
            // never came and it stood on the shop map for the whole run. A held companion
            // walks back to the map it left, like an Agent with no owner.
            if (execution.abandoned()) {
                return finishAbandoned(entry, agent, planning, execution, nowMs);
            }
            log.info("Agent '{}' is rejoining its owner after its {} resupply", agent.getName(), execution.category());
            finish(entry, agent, planning, execution, AgentObjectiveStatus.SUCCEEDED,
                    "shop transaction completed; rejoining the owner", nowMs);
            return false;
        }
        AgentRouteOutcome outcome = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                entry, agent, execution.returnMapId(), nowMs);
        if (outcome.status() == AgentRouteStatus.NO_ROUTE) {
            if (execution.abandoned()) {
                // Nothing left to try: hand the foreground back where the Agent stands.
                return finishAbandoned(entry, agent, planning, execution, nowMs);
            }
            // It holds what it bought, so no way back is no failure: no cooldown, no party ask for potions it
            // carries. Seen with a hop its own stalled walk back blocked (105090200 -> 105090300 for a Drake-side
            // citizen) and from the Balrog stairway (105100000), which no route edge enters.
            return resumeWhereItStands(entry, agent, planning, execution,
                    "no return route from map " + agent.getMapId(), nowMs);
        }
        if (outcome.status() != AgentRouteStatus.ARRIVED) {
            String stuck = noProgress(agent, execution, outcome, "no progress toward the suspended plan", nowMs);
            if (stuck != null) {
                avoidStalledHop(entry, agent, outcome, nowMs);
                return resumeWhereItStands(entry, agent, planning, execution, stuck, nowMs);
            }
            return true;
        }
        if (execution.abandoned()) {
            return finishAbandoned(entry, agent, planning, execution, nowMs);
        }
        // One line when the walk back arrives: before it a restocked trip ended in silence (live, the citizen
        // AngryWarrior shopped in Kerning at 07:11:18 and nothing more was logged), so a live check could not tell
        // a walk back that arrived from one still under way. A shop on the map it left had no walk back to make
        // (review 2026-10-10: a TOWN citizen in Ariant 260000000 at NPC 2100004 "walked back" to the map it stood on).
        if (execution.supplierMapId() == execution.returnMapId()) {
            log.info("Agent '{}' finished its {} resupply on map {}", agent.getName(), execution.category(),
                    execution.returnMapId());
        } else {
            log.info("Agent '{}' walked back to map {} after its {} resupply", agent.getName(), execution.returnMapId(),
                    execution.category());
        }
        finish(entry, agent, planning, execution, AgentObjectiveStatus.SUCCEEDED,
                "shop transaction completed and plan location restored", nowMs);
        return false;
    }

    /**
     * Resumes the suspended plan after a failed resupply, then asks the party for the
     * supply the shop could not provide. Returns false so the resumed plan ticks normally.
     */
    private static boolean finishAbandoned(AgentRuntimeEntry entry,
                                           Character agent,
                                           AgentResourcePlanningState planning,
                                           AgentSupplyProcurementState execution,
                                           long nowMs) {
        AgentResourceCategory category = execution.category();
        String reason = "resupply abandoned: " + execution.abandonedReason();
        if (AgentSupplyReadinessRuntime.readiness(execution.requestId())) {
            // A readiness trip never asks the party: what it lacks waits for its own next trip.
            AgentSupplyReadiness.Outcome outcome =
                    AgentSupplyReadinessRuntime.abandonedOutcome(entry, agent, execution, nowMs);
            finish(entry, agent, planning, execution, AgentObjectiveStatus.FAILED, reason, nowMs, outcome);
            return false;
        }
        finish(entry, agent, planning, execution, AgentObjectiveStatus.FAILED, reason, nowMs);
        log.info("Agent '{}' resumed its plan without restocking {} and is asking the party instead",
                agent.getName(), category);
        askPartyForSupply(entry, agent, category);
        return false;
    }

    private static void askPartyForSupply(AgentRuntimeEntry entry,
                                          Character agent,
                                          AgentResourceCategory category) {
        if (category == null) {
            return;
        }
        switch (category) {
            case HP_POTION -> AgentPotionService.requestPotShare(entry, agent, true, true);
            case MP_POTION -> AgentPotionService.requestPotShare(entry, agent, false, true);
            case ARROW, CROSSBOW_BOLT, THROWING_STAR, BULLET -> AgentAmmoService.requestLowAmmoShare(
                    entry, agent, true, AgentInventoryGatewayRuntime.inventory());
            default -> { }
        }
    }

    private static boolean beginRecovery(
            AgentRuntimeEntry entry,
            Character agent,
            AgentResourcePlanningState planning,
            AgentSupplyProcurementState execution,
            AgentProcurementRequest request,
            AgentSupplyProcurementOutcome outcome,
            long nowMs) {
        if (request == null) {
            stall(entry, agent, planning, execution,
                    "supply request disappeared before recovery could begin", nowMs);
            return true;
        }
        if (execution.recoveryAttempts()
                >= AgentSupplyRecoveryPolicy.maximumRecoveryAttempts()) {
            stall(entry, agent, planning, execution,
                    "resource recovery exhausted after " + execution.recoveryAttempts()
                            + " attempts: " + outcome.reason(), nowMs);
            return true;
        }
        AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
        AgentSupplyRecoveryPolicy.RecoveryMap map =
                AgentSupplyRecoveryPolicy.selectRecoveryMap(agent).orElse(null);
        execution.beginRecovery(map == null ? 0 : map.mapId(),
                Math.max(0, agent.getMeso()),
                AgentSupplyRecoveryPolicy.recoveryMesoTarget(agent, request),
                nowMs, nowMs + AgentSupplyRecoveryPolicy.restTimeoutMs());
        journal(entry, agent, request, "recovery-started",
                "shopping did not restore supplies; foreground remains suspended",
                Map.of("attempt", Integer.toString(execution.recoveryAttempts()),
                        "recoveryMapId", Integer.toString(execution.recoveryMapId()),
                        "mesoTarget", Integer.toString(execution.recoveryTargetMeso())), nowMs);
        return true;
    }

    private static boolean restForRecovery(
            AgentRuntimeEntry entry,
            Character agent,
            AgentResourcePlanningState planning,
            AgentSupplyProcurementState execution,
            AgentProcurementRequest request,
            long nowMs) {
        int chairId = ownedRecoveryChair(agent);
        boolean recovered = AgentSupplyRecoveryPolicy.recoveredForCombat(agent);
        if (!recovered && chairId > 0 && nowMs < execution.recoveryDeadlineAtMs()) {
            if (agent.getChair() != chairId) {
                AgentChairService.sit(entry, agent, chairId);
            }
            return true;
        }
        if (agent.getChair() >= 0) {
            AgentChairService.stand(entry, agent);
        }
        if (!recovered && AgentSupplyRecoveryPolicy.criticallyLowHp(agent)) {
            stall(entry, agent, planning, execution,
                    chairId <= 0
                            ? "no owned recovery chair and HP is unsafe for income recovery"
                            : "chair recovery timed out below the safe combat threshold",
                    nowMs);
            return true;
        }
        if (execution.recoveryMapId() <= 0) {
            stall(entry, agent, planning, execution,
                    "no bounded low-risk income map is available for this level and job",
                    nowMs);
            return true;
        }
        execution.markIncomeRecovery(
                nowMs, nowMs + AgentSupplyRecoveryPolicy.incomeTimeoutMs());
        if (request != null) {
            journal(entry, agent, request, "income-recovery",
                    "rest completed; starting bounded low-risk income recovery",
                    Map.of("attempt", Integer.toString(execution.recoveryAttempts()),
                            "mapId", Integer.toString(execution.recoveryMapId()),
                            "mesos", Integer.toString(Math.max(0, agent.getMeso()))), nowMs);
        }
        return true;
    }

    private static boolean recoverIncome(
            AgentRuntimeEntry entry,
            Character agent,
            AgentResourcePlanningState planning,
            AgentSupplyProcurementState execution,
            AgentProcurementRequest request,
            long nowMs) {
        if (AgentSupplyRecoveryPolicy.criticallyLowHp(agent)) {
            AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
            execution.markResting(nowMs, nowMs + AgentSupplyRecoveryPolicy.restTimeoutMs());
            return true;
        }
        boolean targetReached = agent.getMeso() >= execution.recoveryTargetMeso();
        boolean timedOut = nowMs >= execution.recoveryDeadlineAtMs();
        if (targetReached || timedOut) {
            AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
            if (timedOut
                    && agent.getMeso() <= execution.recoveryBaselineMeso()
                    && execution.recoveryAttempts()
                    >= AgentSupplyRecoveryPolicy.maximumRecoveryAttempts()) {
                stall(entry, agent, planning, execution,
                        "bounded income recovery produced no mesos after "
                                + execution.recoveryAttempts() + " attempts", nowMs);
                return true;
            }
            execution.retrySupplier(agent.getMapId() == execution.supplierMapId());
            if (request != null) {
                journal(entry, agent, request, "shop-retry",
                        targetReached
                                ? "income target reached; retrying suspended resupply"
                                : "income window ended; retrying with recovered mesos",
                        Map.of("attempt", Integer.toString(execution.recoveryAttempts()),
                                "mesos", Integer.toString(Math.max(0, agent.getMeso())),
                                "targetReached", Boolean.toString(targetReached)), nowMs);
            }
            return true;
        }
        AgentSupplyRecoveryPolicy.RecoveryMap map = AgentSupplyRecoveryPolicy
                .recoveryMap(execution.recoveryMapId(), agent.getLevel()).orElse(null);
        if (map == null) {
            stall(entry, agent, planning, execution,
                    "selected income recovery map is no longer eligible", nowMs);
            return true;
        }
        AgentRouteOutcome travel = AgentPrimitiveCapabilityGatewayRuntime.gateway().travelTo(
                entry, agent, map.mapId(), nowMs);
        if (travel.status() == AgentRouteStatus.NO_ROUTE) {
            stall(entry, agent, planning, execution,
                    "no route reaches the bounded income recovery map " + map.mapId(), nowMs);
            return true;
        }
        if (travel.status() == AgentRouteStatus.ARRIVED) {
            AgentPrimitiveCapabilityGatewayRuntime.gateway().grind(entry, map.mobIds());
        }
        return true;
    }

    private static int ownedRecoveryChair(Character agent) {
        if (agent == null || agent.getInventory(InventoryType.SETUP) == null) {
            return 0;
        }
        if (agent.getInventory(InventoryType.SETUP).countById(ItemId.RELAXER) > 0) {
            return ItemId.RELAXER;
        }
        return agent.getInventory(InventoryType.SETUP)
                .countById(ItemId.SKY_BLUE_WOODEN_CHAIR) > 0
                ? ItemId.SKY_BLUE_WOODEN_CHAIR : 0;
    }

    private static void stall(
            AgentRuntimeEntry entry,
            Character agent,
            AgentResourcePlanningState planning,
            AgentSupplyProcurementState execution,
            String reason,
            long nowMs) {
        // stop() clears the follow target; keep it so the Agent can rejoin its leader later.
        Character followTarget = AgentRelationshipRuntime.followTarget(entry);
        AgentPrimitiveCapabilityGatewayRuntime.gateway().stop(entry);
        AgentRelationshipRuntime.setFollowTarget(entry, followTarget);
        if (agent.getChair() >= 0) {
            AgentChairService.stand(entry, agent);
        }
        String failedPhase = execution.phase().name();
        boolean selfSustaining = selfSustaining(entry);
        if (selfSustaining) {
            // Journey participants must prove they can sustain themselves: keep the
            // suspended foreground exactly as it was so the run can be inspected.
            execution.markStalled(reason);
        } else {
            // Companions and citizens give up, walk back to the plan and ask the party
            // instead of standing at the shop with their foreground suspended forever.
            execution.markAbandoned(reason);
            if (!AgentSupplyReadinessRuntime.readiness(execution.requestId())) {
                // a readiness trip keeps its own cooldowns, and an ammo-only one carries HP_POTION: suppressing it
                // would hold back the critical HP path
                execution.suppress(execution.category(),
                        nowMs + AgentSupplyRecoveryPolicy.failedProcurementCooldownMs());
            }
        }
        publish(entry, agent, "resource.recovery-stalled",
                "resource-stalled:" + execution.requestId() + ':' + nowMs,
                reason, Map.of(
                        "requestId", execution.requestId(),
                        "attempts", Integer.toString(execution.recoveryAttempts()),
                        "shopFailures", Integer.toString(execution.shopFailures()),
                        "phase", failedPhase,
                        "disposition", selfSustaining ? "stalled" : "abandoned"), nowMs);
        if (selfSustaining) {
            log.warn("Agent '{}' left foreground objective suspended after supply recovery stalled: {}",
                    agent.getName(), reason);
        } else {
            log.warn("Agent '{}' abandoned {} resupply after {} failed shop visit(s), returning to map {}"
                            + " and suppressing the category for {} ms: {}",
                    agent.getName(), execution.category(), execution.shopFailures(),
                    execution.returnMapId(),
                    AgentSupplyRecoveryPolicy.failedProcurementCooldownMs(), reason);
        }
    }

    private static boolean selfSustaining(AgentRuntimeEntry entry) {
        return entry.capabilityStates()
                .find(AgentResourceAutonomyState.STATE_KEY)
                .map(AgentResourceAutonomyState::selfSustaining)
                .orElse(false);
    }

    private static void finish(AgentRuntimeEntry entry,
                               Character agent,
                               AgentResourcePlanningState planning,
                               AgentSupplyProcurementState execution,
                               AgentObjectiveStatus status,
                               String reason,
                               long nowMs) {
        finish(entry, agent, planning, execution, status, reason, nowMs, null);
    }

    /** {@code readinessOutcome}: how an abandoned readiness trip ended (null: judged from {@code status}). */
    private static void finish(AgentRuntimeEntry entry,
                               Character agent,
                               AgentResourcePlanningState planning,
                               AgentSupplyProcurementState execution,
                               AgentObjectiveStatus status,
                               String reason,
                               long nowMs,
                               AgentSupplyReadiness.Outcome readinessOutcome) {
        String requestId = execution.requestId();
        String maintenanceObjectiveId = execution.objectiveId();
        var category = execution.category();
        boolean readinessTrip = AgentSupplyReadinessRuntime.readiness(requestId);
        // the mode it ends in, read in the same lock that clears the trip (a host's resumeFollowAfterTrip races it)
        AgentSupplyProcurementState.ForegroundMode mode = execution.clearTakingForegroundMode();
        boolean resumeFollow = mode.follow();
        boolean resumeGrind = mode.grind();
        execution.forgetPartyAsk(category);
        AgentSupplyMaintenanceEvaluationState evaluations =
                entry.capabilityStates().require(AgentSupplyMaintenanceEvaluationState.STATE_KEY);
        if (category != null) {
            planning.resolve(category);
            evaluations.resolve(category);
        }
        if (readinessTrip) {
            // One visit filled both: a critical request of the other category planned before it is spent too.
            for (AgentResourceCategory potion : new AgentResourceCategory[]{
                    AgentResourceCategory.HP_POTION, AgentResourceCategory.MP_POTION}) {
                planning.resolve(potion);
                evaluations.resolve(potion);
            }
        }
        String frameId = "resupply:" + requestId;
        if (entry.capabilityStates().require(AgentRemediationState.STATE_KEY).active() == null) {
            AgentObjectiveDefinition active = AgentObjectiveKernel.active(entry);
            String parentCorrelation = active == null ? "" : active.correlationId();
            AgentRemediationCoordinator.reattach(entry, new AgentRemediationFrame(
                    frameId, AgentRemediationKind.LOW_SUPPLIES, maintenanceObjectiveId,
                    parentCorrelation, 1, nowMs, Long.MAX_VALUE,
                    category == null ? Map.of() : Map.of("resourceCategory", category.name())));
        }
        AgentRemediationCoordinator.finish(entry, frameId, status, reason, nowMs);
        restoreForegroundMode(entry, resumeFollow, resumeGrind);
        AgentSupplyReadinessRuntime.ownTrip(entry);
        if (readinessTrip) {
            AgentSupplyReadinessRuntime.finished(entry, agent, requestId, status, readinessOutcome);
        }
    }

    /**
     * Travelling to the supplier and stopping for recovery clear the movement mode, and no
     * plan handler re-issues it for a plain companion or citizen. Put the Agent back into the
     * mode it was in unless a resumed plan objective now owns the foreground.
     */
    private static void restoreForegroundMode(AgentRuntimeEntry entry,
                                              boolean resumeFollow,
                                              boolean resumeGrind) {
        if (AgentObjectiveKernel.active(entry) != null) {
            return;
        }
        if (resumeFollow && followAnchor(entry) != null) {
            AgentMovementCommandRuntime.followConfiguredTarget(entry);
        } else if (resumeGrind) {
            AgentMovementCommandRuntime.grind(entry);
        }
    }

    /**
     * Who a companion follows again: its follow target, or its owner once travel stopped and
     * cleared the target (portal settling stops the Agent), as followConfiguredTarget does.
     */
    private static Character followAnchor(AgentRuntimeEntry entry) {
        Character target = AgentRelationshipRuntime.followTarget(entry);
        return target != null ? target : AgentRelationshipRuntime.interactionTarget(entry);
    }

    /** {@code potionReserve}: {@code restored} was judged by the potion reserve the shop buys to. */
    private static AgentSupplyProcurementOutcome reconcile(
            AgentSupplyProcurementState execution,
            Character agent,
            int quantityAfter,
            boolean restored,
            boolean potionReserve,
            long nowMs) {
        int mesosAfter = Math.max(0, agent.getMeso());
        AgentSupplyProcurementOutcome.Status status;
        String reason;
        if (restored) {
            status = AgentSupplyProcurementOutcome.Status.RESTORED;
            reason = potionReserve ? "potion reserve restored to the restock target"
                    : "supply target restored after liquidation and purchase";
        } else if (quantityAfter > execution.quantityBefore()) {
            status = AgentSupplyProcurementOutcome.Status.PARTIALLY_RESTORED;
            reason = potionReserve ? "shop improved potions but the reserve is still short of the restock target"
                    : "shop improved supplies but did not restore the resume target";
        } else if (mesosAfter <= 0) {
            status = AgentSupplyProcurementOutcome.Status.INSUFFICIENT_MESO;
            reason = "safe liquidation produced insufficient mesos for the required supply";
        } else {
            status = AgentSupplyProcurementOutcome.Status.NO_PROGRESS;
            reason = "shop completed without increasing the required supply";
        }
        return new AgentSupplyProcurementOutcome(status, execution.category(),
                execution.quantityBefore(), quantityAfter, execution.mesosBefore(),
                mesosAfter, nowMs, reason);
    }

    private static void recordFailure(
            AgentRuntimeEntry entry,
            Character agent,
            AgentSupplyProcurementState execution,
            AgentSupplyProcurementOutcome.Status status,
            String reason,
            long nowMs) {
        AgentSupplyProcurementOutcome outcome = new AgentSupplyProcurementOutcome(
                status, execution.category(), execution.quantityBefore(),
                AgentSupplyInventorySnapshot.quantity(agent, execution.category()),
                execution.mesosBefore(), Math.max(0, agent.getMeso()), nowMs, reason);
        execution.complete(outcome);
        journalOutcome(entry, agent, execution.requestId(), outcome);
    }

    private static void journalOutcome(
            AgentRuntimeEntry entry,
            Character agent, String requestId, AgentSupplyProcurementOutcome outcome) {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("status", outcome.status().name());
        evidence.put("category", outcome.category().name());
        evidence.put("quantityBefore", Integer.toString(outcome.quantityBefore()));
        evidence.put("quantityAfter", Integer.toString(outcome.quantityAfter()));
        evidence.put("mesosBefore", Integer.toString(outcome.mesosBefore()));
        evidence.put("mesosAfter", Integer.toString(outcome.mesosAfter()));
        publish(entry, agent, "resource.maintenance-outcome",
                "resource:" + requestId + ':' + outcome.occurredAtMs(),
                outcome.reason(), evidence, outcome.occurredAtMs());
    }

    private static void journal(
            AgentRuntimeEntry entry,
            Character agent,
            AgentProcurementRequest request,
            String phase,
            String reason,
            Map<String, String> facts,
            long nowMs) {
        Map<String, String> evidence = new LinkedHashMap<>(facts);
        evidence.put("phase", phase);
        evidence.put("category", request.category().name());
        evidence.put("targetShortfall", Integer.toString(request.quantity()));
        publish(entry, agent, "resource.maintenance-decision",
                "resource:" + request.requestId() + ':' + phase + ':' + nowMs,
                reason, evidence, nowMs);
    }

    private static void publish(
            AgentRuntimeEntry entry,
            Character agent,
            String type,
            String dedupeKey,
            String reason,
            Map<String, String> evidence,
            long nowMs) {
        Map<String, String> attributes = new LinkedHashMap<>(evidence);
        attributes.put("reason", reason == null ? "" : reason);
        AgentSessionEventRuntime.bus(entry).publish(new AgentDomainEvent(
                agent.getId(), nowMs, type, dedupeKey, attributes));
    }
}
