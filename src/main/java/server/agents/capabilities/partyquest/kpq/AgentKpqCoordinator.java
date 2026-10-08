package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.agents.capabilities.combat.AgentCombatSkillUsageStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootTargetService;
import server.agents.capabilities.looting.AgentLootCollectionPolicyConfig;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.capabilities.supplies.AgentAmmoService;
import server.agents.catalog.AgentMapRegionAssignment;
import server.agents.field.AgentFieldObservationState;
import server.agents.operations.events.AgentOperationalEventProjectionState;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.agents.runtime.simulation.AgentSimulationState;
import scripting.event.EventInstanceManager;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Party-level KPQ state machine. It uses ordinary NPC scripts, portals, combat, drops, and loot. */
final class AgentKpqCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentKpqCoordinator.class);
    private static final PrimitiveCapabilityGateway ACTIONS = AgentPrimitiveCapabilityGatewayRuntime.gateway();
    private static final PartyQuestGateway KPQ = AgentPartyQuestGatewayRuntime.partyQuest();
    private static final Set<Integer> STAGE_1_MOBS = Set.of(9_300_001);
    static final Set<Integer> STAGE_5_NORMAL_MOBS = Set.of(9_300_000, 9_300_002, 210_100);
    static final Set<Integer> STAGE_5_BOSS_MOBS = Set.of(9_300_003);
    /** The Curse Eyes on the floor a leader drops to from the arrival ledge on his way to Cloto. */
    static final Set<Integer> STAGE_5_CURSE_EYES = Set.of(9_300_002);
    private static final long KING_SLIME_REVIVE_GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.KING_SLIME_REVIVE_GRACE_MS");
    private static final long FORMATION_STABLE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.FORMATION_STABLE_MS");
    private static final long HUMAN_LED_FORMATION_STABLE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.HUMAN_LED_FORMATION_STABLE_MS");
    private static final long FORMATION_MOVER_DELAY_PER_SLOT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.FORMATION_MOVER_DELAY_PER_SLOT_MS");
    private static final long FORMATION_MOVER_DELAY_SPREAD_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.FORMATION_MOVER_DELAY_SPREAD_MS");
    private static final long STAGE_MOVEMENT_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE_MOVEMENT_DELAY_MS");
    private static final long STAGE_MOVEMENT_DELAY_PER_SLOT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE_MOVEMENT_DELAY_PER_SLOT_MS");
    private static final long STAGE_MOVEMENT_DELAY_SPREAD_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE_MOVEMENT_DELAY_SPREAD_MS");
    private static final long KPQ_PHASE_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.KPQ_PHASE_TIMEOUT_MS");
    private static final long COUPON_SWEEP_INTERVAL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.COUPON_SWEEP_INTERVAL_MS");
    private static final long COUPON_SWEEP_MAXIMUM_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.COUPON_SWEEP_MAXIMUM_MS");
    private static final long MISSING_PASS_GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.MISSING_PASS_GRACE_MS");
    private static final long STAGE1_SUBMIT_RECOVERY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_SUBMIT_RECOVERY_MS");
    private static final long LOCAL_RECOVERY_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.LOCAL_RECOVERY_TIMEOUT_MS");
    private static final long CLOTO_ANCHOR_REPLAN_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.CLOTO_ANCHOR_REPLAN_MS");
    private static final long INTERACTION_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.INTERACTION_RETRY_MS");
    private static final long STAGE1_QUESTION_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_QUESTION_DELAY_MS");
    private static final long STAGE1_QUESTION_VARIANCE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_QUESTION_VARIANCE_MS");
    private static final long STAGE1_SUBMIT_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_SUBMIT_DELAY_MS");
    private static final long STAGE1_SUBMIT_VARIANCE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_SUBMIT_VARIANCE_MS");
    private static final long PUZZLE_CHECK_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.PUZZLE_CHECK_DELAY_MS");
    private static final long PUZZLE_CHECK_VARIANCE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.PUZZLE_CHECK_VARIANCE_MS");
    private static final long STAGE5_MISSING_PASS_GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_MISSING_PASS_GRACE_MS");
    private static final long STAGE5_PASS_HANDOVER_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_PASS_HANDOVER_MS");
    private static final long STAGE5_SWEEP_LIMIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_SWEEP_LIMIT_MS");
    private static final long STAGE5_FLOOR_PASS_PATIENCE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_FLOOR_PASS_PATIENCE_MS");
    private static final long STAGE5_PASS_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_PASS_NUDGE_MS");
    private static final long STAGE5_INREACH_PASS_PATIENCE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_INREACH_PASS_PATIENCE_MS");
    private static final long STAGE5_ROUTE_REPEAT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_ROUTE_REPEAT_MS");
    private static final double STAGE5_CARRIER_LOW_HP_FRACTION = config.AgentTuning.doubleValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_CARRIER_LOW_HP_FRACTION");
    private static final int PASS_DROP_AT_FEET_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.PASS_DROP_AT_FEET_PX");
    private static final int PASS_DROP_SPOT_SEARCH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.PASS_DROP_SPOT_SEARCH_PX");
    private static final int PASS_DROP_SPOT_STEP_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.PASS_DROP_SPOT_STEP_PX");
    private static final long PASS_DROP_LANDED_WINDOW_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.PASS_DROP_LANDED_WINDOW_MS");
    private static final long SQUISHY_SHOES_HUMAN_PRIORITY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.SQUISHY_SHOES_HUMAN_PRIORITY_MS");
    private static final long KING_SLIME_LOOT_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.KING_SLIME_LOOT_DELAY_MS");
    private static final int SQUISHY_SHOES_HUMAN_NEAR_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.SQUISHY_SHOES_HUMAN_NEAR_PX");
    private static final long STAGE5_CLEANUP_MIN_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_CLEANUP_MIN_MS");
    private static final long STAGE5_CLEANUP_MAX_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE5_CLEANUP_MAX_MS");
    private static final long BONUS_MAP_DWELL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.BONUS_MAP_DWELL_MS");
    private static final long PREPARATION_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.PREPARATION_DELAY_MS");
    private static final int NEAR_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.NEAR_PX");
    /**
     * How far along the leader's foothold a deliverer may step aside to drop: PASS_DROP_SPOT_SEARCH_PX, but never so
     * far that a drop landing up to PASS_DROP_AT_FEET_PX past the spot lies outside his reach (NEAR_PX), where the
     * nudges would call it left back there and the stage-5 re-collect would carry it back to the same spot.
     */
    private static final int PASS_DROP_SPOT_REACH_PX = AgentKpqPassHandOver.spotReach(
            PASS_DROP_SPOT_SEARCH_PX, NEAR_PX, PASS_DROP_AT_FEET_PX);
    private static final int NPC_APPROACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.NPC_APPROACH_PX");
    private static final int GATHER_RADIUS_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.GATHER_RADIUS_PX");
    private static final int HELPER_ANCHOR_MOVE_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.HELPER_ANCHOR_MOVE_PX");
    private static final int STAGE1_TOWER_GAP_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_TOWER_GAP_PX");
    private static final long STAGE1_HELPER_TERRITORY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_HELPER_TERRITORY_MS");
    private static final long STAGE1_HELPER_TERRITORY_EMPTY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_HELPER_TERRITORY_EMPTY_MS");
    private static final int STAGE4_LEADER_BELOW_CLOTO_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE4_LEADER_BELOW_CLOTO_PX");
    private static final int STAGE1_LEADER_FAR_FROM_CLOTO_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_LEADER_FAR_FROM_CLOTO_PX");
    private static final long STAGE1_PASS_AT_FEET_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_PASS_AT_FEET_MS");
    private static final long STAGE1_PASS_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_PASS_NUDGE_MS");
    private static final long STAGE1_FLOOR_PASS_PATIENCE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_FLOOR_PASS_PATIENCE_MS");
    private static final long STAGE1_LEADER_COUPON_REPEAT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_LEADER_COUPON_REPEAT_MS");
    private static final int STAGE1_LOOT_NEAR_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_LOOT_NEAR_PX");
    private static final long STAGE1_LOOT_MAX_AGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_LOOT_MAX_AGE_MS");
    private static final int STAGE1_RANGED_SPAWN_STANDOFF_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_RANGED_SPAWN_STANDOFF_PX");
    private static final int STAGE1_RANGED_SPOT_MIN_SPAWNS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_RANGED_SPOT_MIN_SPAWNS");
    private static final int STAGE1_CLUSTER_LINK_X_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_CLUSTER_LINK_X_PX");
    private static final int STAGE1_CLUSTER_LINK_Y_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_CLUSTER_LINK_Y_PX");
    private static final int STAGE1_CLUSTER_MIN_SPAWNS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_CLUSTER_MIN_SPAWNS");
    private static final long STAGE1_CLUSTER_RESPAWN_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_CLUSTER_RESPAWN_WAIT_MS");
    private static final int STAGE1_CLUSTER_RETURN_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.STAGE1_CLUSTER_RETURN_PX");

    private AgentKpqCoordinator() {
    }

    static void tick(AgentKpqSession session, long nowMs) {
        synchronized (session) {
            tickSession(session, nowMs);
            AgentKpqPaceReport.logClosedSpans(session, nowMs);
        }
    }

    private static void tickSession(AgentKpqSession session, long nowMs) {
        if (terminal(session.phase())) return;
        Character leader = eventLeader(session);
        Character narrator = narrator(session);
        if (nowMs - session.lastProgressAtMs() > KPQ_PHASE_TIMEOUT_MS) {
            if (leader == null) {
                fail(session, "No KPQ progress for " + (KPQ_PHASE_TIMEOUT_MS / 60_000L) + " minutes",
                        Parting.of(Parting.Kind.TOO_LONG), nowMs);
                return;
            }
            if (session.phase() == AgentKpqSession.Phase.STAGE_1) {
                endStageOneForTimeout(session, leader, nowMs);
            } else {
                fail(session, "No KPQ progress for " + (KPQ_PHASE_TIMEOUT_MS / 60_000L) + " minutes",
                        Parting.of(Parting.Kind.TOO_LONG), nowMs);
            }
            return;
        }
        if (leader == null) {
            fail(session, "The event leader is no longer active", new Parting(Parting.Kind.GONE,
                    session.eventLeaderId(), memberName(firstCharacter(session), session.eventLeaderId())), nowMs);
            return;
        }
        if (leader.getHp() <= 0) {
            fail(session, "The event leader was defeated or left the KPQ",
                    Parting.of(Parting.Kind.DEFEATED, leader), nowMs);
            return;
        }
        bindKnownEvent(session, leader);
        holdFullRateSimulation(session);
        observePaceSimulation(session, nowMs);
        if (!reconcileLiveParty(session, leader, nowMs)) return;
        if (!membersAlive(session, leader, nowMs)) return;
        if (insideEventPhase(session.phase()) && session.eventInstance() != null
                && KPQ.event(leader) != session.eventInstance()) {
            fail(session, "The KPQ event instance disappeared or the leader left it",
                    Parting.of(Parting.Kind.OVER), nowMs);
            return;
        }
        switch (session.phase()) {
            case PREPARING -> prepare(session, leader, narrator, nowMs);
            case ENTERING -> enter(session, leader, narrator, nowMs);
            case STAGE_1 -> stage1(session, leader, narrator, nowMs);
            case STAGE_2 -> combinationStage(session, leader, narrator, 2, nowMs);
            case STAGE_3 -> combinationStage(session, leader, narrator, 3, nowMs);
            case STAGE_4 -> combinationStage(session, leader, narrator, 4, nowMs);
            case STAGE_5 -> stage5(session, leader, narrator, nowMs);
            case CLAIMING_REWARDS -> claimRewards(session, nowMs);
            case EXITING -> exit(session, nowMs);
            default -> { }
        }
    }

    /**
     * A companion run's companions standing in its instance run at full rate until their reward; a stock run's, and
     * every background KPQ's, keep the cadence their map's observers give them. A run whose release has begun (a
     * force stop or removal from another thread, which lets go of the holds before the phase turns FAILED) takes
     * none back.
     */
    private static void holdFullRateSimulation(AgentKpqSession session) {
        AgentKpqSession.Phase phase = session.phase();
        boolean ending = session.terminationStarted();
        for (AgentKpqMemberState member : session.members()) {
            if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            boolean wanted = !ending && session.companionAdopted() && agent != null && standsInEvent(session, agent)
                    && (insideEventPhase(phase)
                    || phase == AgentKpqSession.Phase.CLAIMING_REWARDS && !member.rewardResolved());
            AgentKpqFullRateSimulation.apply(member, entry(member.characterId()), wanted);
        }
    }

    /**
     * How the Agents standing in the run are simulated while a kill segment runs, for its pace span: one measured
     * below the presentation rate (no player on the map) is not one the clear-time calibration may learn from.
     */
    private static void observePaceSimulation(AgentKpqSession session, long nowMs) {
        AgentKpqPaceMarks pace = session.pace();
        String killSegment = pace.openKillSegment(session.phase());
        int agents = 0;
        int throttled = 0;
        if (killSegment != null) {
            for (AgentKpqMemberState member : session.members()) {
                if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
                AgentRuntimeEntry entry = entry(member.characterId());
                Character agent = character(member.characterId());
                if (entry == null || agent == null || !standsInEvent(session, agent)) continue;
                agents++;
                AgentSimulationState simulation = entry.simulationState();
                if (simulation != null && simulation.mode() != AgentSimulationMode.PRESENTATION) throttled++;
            }
        }
        pace.sampleSimulation(killSegment, agents, throttled, nowMs);
    }

    /**
     * Stage 1 starts the run's pace record: each member's power against KPQ's mobs, read on this tick from the warmed
     * pace profile (none while it is cold, or when any member cannot be read), and the members Cloto gives a coupon
     * question, every one but the leader.
     */
    private static void beginPace(AgentKpqSession session, Character leader) {
        List<Integer> collectors = new ArrayList<>();
        List<AgentPqMemberPower> power = new ArrayList<>();
        AgentPqPaceProfile profile = AgentPqPaceProfiles.cached(AgentKpqPaceProfile.QUEST_KEY);
        boolean readable = profile != null;
        for (AgentKpqMemberState member : session.members()) {
            boolean leads = member.characterId() == session.eventLeaderId();
            if (!leads) collectors.add(member.characterId());
            if (!readable) continue;
            Character character = memberCharacter(member.characterId(), leader);
            if (character == null) {
                readable = false;
                continue;
            }
            try {
                power.add(AgentPqMemberPower.of(character, profile,
                        member.memberType() == AgentKpqMemberState.MemberType.HUMAN, leads));
            } catch (RuntimeException | LinkageError failure) {
                readable = false;
                log.debug("KPQ pace power unreadable: session={} member={}", session.sessionId(),
                        member.characterId(), failure);
            }
        }
        session.pace().beginStageOne(readable ? power : List.of(), collectors);
    }

    /** The stage-5 race's own starts: the first companion on stage 5, and the leader. */
    private static void observeStageFiveArrivals(AgentKpqSession session, Character anchor, long nowMs) {
        AgentKpqPaceMarks pace = session.pace();
        if (!pace.companionsOnStageFive()) {
            for (AgentKpqMemberState member : session.members()) {
                if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
                Character agent = memberCharacter(member.characterId(), anchor);
                if (agent != null && agent.getMapId() == AgentKpqDefinition.STAGE_5_MAP) {
                    pace.markCompanionsOnStageFive(nowMs);
                    break;
                }
            }
        }
        Character leader = eventLeader(session);
        if (!pace.leaderOnStageFive() && leader != null && leader.getMapId() == AgentKpqDefinition.STAGE_5_MAP) {
            pace.markLeaderOnStageFive(nowMs);
        }
    }

    private static void endStageOneForTimeout(AgentKpqSession session, Character leader, long nowMs) {
        // A companion run's parting line says it in the players' own words.
        if (!session.companionAdopted()) {
            narrate(session, narrator(session), "stage1-timeout",
                    "No KPQ progress in stage 1 for " + (KPQ_PHASE_TIMEOUT_MS / 60_000L)
                            + " minutes. Ending KPQ and returning to Kerning.");
        }
        fail(session, "No KPQ progress for " + (KPQ_PHASE_TIMEOUT_MS / 60_000L)
                + " minutes in stage 1", Parting.of(Parting.Kind.TOO_LONG), nowMs);
    }

    private static void prepare(
            AgentKpqSession session, Character leader, Character narrator, long nowMs) {
        if (session.memberCount() != session.requestedPartySize()) {
            if (blockedTooLong(session, "prepare-party-size", nowMs)) {
                fail(session, "KPQ party membership changed during preparation", nowMs);
            }
            return;
        }
        EventInstanceManager enteredEvent = KPQ.event(leader);
        boolean enteredTogether = leader.getMapId() == AgentKpqDefinition.STAGE_1_MAP
                && enteredEvent != null && allMembersSameEvent(session, leader);
        if (shouldAcceptEnteredStageOneDuringPreparation(
                leader.getMapId(), enteredEvent != null, enteredTogether)) {
            session.bindEventInstance(enteredEvent);
            session.clearBlocker();
            transition(session, AgentKpqSession.Phase.ENTERING, nowMs);
            return;
        }
        if (leader.getMapId() == AgentKpqDefinition.STAGE_1_MAP && enteredEvent != null) {
            if (blockedTooLong(session, "prepare-enter-partial-party", nowMs)) {
                fail(session, "Only part of the party entered the KPQ instance", nowMs);
            }
            return;
        }
        if (!allMembersOnMap(session, AgentKpqDefinition.RECRUIT_MAP, leader)) {
            if (blockedTooLong(session, "prepare-member-map", nowMs)) {
                fail(session, "A party member did not reach the Kerning entrance", nowMs);
            }
            return;
        }
        Point entryNpc = ACTIONS.npcPosition(leader, AgentKpqDefinition.ENTRY_NPC);
        if (entryNpc == null) {
            if (blockedTooLong(session, "prepare-entry-npc", nowMs)) {
                fail(session, "The Kerning entry NPC could not be located", nowMs);
            }
            return;
        }
        boolean allGathered = true;
        for (AgentKpqMemberState member : session.members()) {
            Character memberCharacter = memberCharacter(member.characterId(), leader);
            if (memberCharacter == null || memberCharacter.getMap() == null) {
                allGathered = false;
                continue;
            }
            if (member.memberType() == AgentKpqMemberState.MemberType.AGENT) {
                Point target = npcApproachPoint(session, member, memberCharacter, entryNpc, 11);
                if (!walkToPoint(memberCharacter, target, entry(member.characterId()), NPC_APPROACH_PX)) {
                    allGathered = false;
                }
            } else if (!near(memberCharacter.getPosition(), entryNpc, GATHER_RADIUS_PX)) {
                allGathered = false;
            }
        }
        if (!allGathered) {
            if (session.readyAtMs() != 0L) session.setReadyAtMs(0L);
            if (blockedTooLong(session, "prepare-gather", nowMs)) {
                fail(session, "The party could not gather at Lakelis", nowMs);
            }
            return;
        }
        session.clearBlocker();
        if (session.readyAtMs() == 0L) {
            // Before it says it is starting: with the lobby gone that would be a start it cannot make.
            if (backgroundLobbyTaken(session, leader)) {
                giveUpTakenLobby(session, nowMs);
                return;
            }
            session.setReadyAtMs(nowMs + PREPARATION_DELAY_MS);
            KPQ.prepareNpcScripts(AgentKpqDefinition.ENTRY_NPC, AgentKpqDefinition.CLOTO_NPC,
                    AgentKpqDefinition.EXIT_NPC);
            AgentKpqDialogue.sayMapNow(narrator, voice(session, "everyone's here. starting in 5.",
                    "Party ready. Everyone is here. Starting KPQ in 5 seconds."));
        }
        if (nowMs >= session.readyAtMs()) transition(session, AgentKpqSession.Phase.ENTERING, nowMs);
    }

    /**
     * A background party formed while a lobby was free may no longer take the last one a human could enter. An
     * Agent-led !kpqtest run is KPQ_OWNED too, but it is not background and keeps the stock entry.
     */
    private static boolean backgroundLobbyTaken(AgentKpqSession session, Character leader) {
        return session.partyOwnership() == AgentKpqSession.PartyOwnership.KPQ_OWNED
                && session.mode() != AgentKpqSession.Mode.TEST_OBSERVATION
                && !AgentKpqLobbyPolicy.backgroundEntryAllowed(KPQ.openInstances(leader, "KerningPQ"),
                        AgentKpqLobbyPolicy.maxLobbies(), AgentKpqLobbyPolicy.humanReservedLobbies());
    }

    /**
     * Another party took the lobby, and it keeps it for its whole run, so waiting cannot help: the party stood at
     * Lakelis for the 45 s deadline and then told the whole entrance the engine's reason. It goes at once, casually.
     */
    private static void giveUpTakenLobby(AgentKpqSession session, long nowMs) {
        fail(session, "The last free KPQ lobby is kept for human parties",
                Parting.of(Parting.Kind.LOBBY_TAKEN), nowMs);
    }

    static boolean shouldAcceptEnteredStageOneDuringPreparation(
            int leaderMapId, boolean leaderHasEvent, boolean allMembersSameEvent) {
        return leaderMapId == AgentKpqDefinition.STAGE_1_MAP
                && leaderHasEvent && allMembersSameEvent;
    }

    private static void enter(
            AgentKpqSession session, Character leader, Character narrator, long nowMs) {
        if (leader.getMapId() == AgentKpqDefinition.STAGE_1_MAP && KPQ.event(leader) != null) {
            session.bindEventInstance(KPQ.event(leader));
            if (allMembersSameEvent(session, leader)) {
                session.clearBlocker();
                if (session.mode() == AgentKpqSession.Mode.TEST_OBSERVATION
                        && session.requestedCheckpointStage() > 1) {
                    AgentKpqCheckpointService.apply(session, leader, session.requestedCheckpointStage(), nowMs);
                    return;
                }
                coordinate(session, narrator, "entered", voice(session,
                        "we're in. everyone ask cloto for a coupon number.",
                        "We're in. Ask Cloto for your coupon number."));
                initializeStageMovementDelays(session, 1, nowMs);
                for (AgentKpqMemberState member : session.members()) {
                    AgentRuntimeEntry entry = entry(member.characterId());
                    if (entry != null) {
                        member.beginStage1Tally(combatCounts(entry, nowMs), nowMs);
                        member.beginStage1Profile(postureMs(entry, nowMs, Map.of()), skillAttacks(entry, Map.of()),
                                AgentCombatSkillUsageStateRuntime.degenerateAttacks(entry));
                    }
                }
                beginPace(session, leader);
                transition(session, AgentKpqSession.Phase.STAGE_1, nowMs);
            } else if (blockedTooLong(session, "enter-partial-party", nowMs)) {
                fail(session, "Only part of the party entered the KPQ instance", nowMs);
            }
            return;
        }
        if (leader.getMapId() != AgentKpqDefinition.RECRUIT_MAP) {
            if (blockedTooLong(session, "enter-leader-map", nowMs)) {
                fail(session, "The event leader left the KPQ entrance during admission", nowMs);
            }
            return;
        }
        if (isAgent(session, leader.getId())) {
            AgentKpqMemberState leaderState = session.member(leader.getId());
            if (nowMs < leaderState.nextRetryAtMs()) return;
            leaderState.setNextRetryAtMs(nowMs + INTERACTION_RETRY_MS);
            if (backgroundLobbyTaken(session, leader)) {
                giveUpTakenLobby(session, nowMs);
                return;
            }
            boolean invoked = KPQ.runNpc(leader, AgentKpqDefinition.ENTRY_NPC, 0);
            if (leader.getMapId() == AgentKpqDefinition.RECRUIT_MAP
                    && blockedTooLong(session, invoked ? "enter-lobby-wait" : "enter-npc", nowMs)) {
                fail(session, invoked
                        ? "No KPQ lobby became available before the admission deadline"
                        : "Lakelis repeatedly rejected or failed the KPQ entry request", nowMs);
            }
        } else {
            coordinate(session, narrator, "human-enter", voice(session,
                    leader.getName() + ", talk to lakelis when you're ready.",
                    leader.getName() + ", please talk to Lakelis to start KPQ."));
        }
    }

    private static void stage1(
            AgentKpqSession session, Character leader, Character narrator, long nowMs) {
        session.members().forEach(member -> tallyStageOne(member, nowMs));
        if ("true".equals(KPQ.property(leader, "1stageclear"))) {
            closeRerollAtStageClear(session, nowMs);
            if (session.claimStageOneSummary()) {
                reportStageOne(session, nowMs);
                if (session.companionAdopted() && !isAgent(session, leader.getId())) {
                    reportStageOneLeader(session, leader);
                }
            }
            clearStageOneLandingSafety(session, leader);
            clearCouponLootObjectives(session);
            // A territory now outlives the grind call, and every run's stage 1 is this map: it ends with the stage.
            session.members().forEach(member -> ACTIONS.leaveTerritory(entry(member.characterId())));
            enterNextPortal(session, 2, nowMs);
            return;
        }
        if (stageOneReroll(session, leader, nowMs)) return;
        boolean allDelivered = true;
        AgentRuntimeEntry leaderEntry = entry(leader.getId());
        Point cloto = ACTIONS.npcPosition(leader, AgentKpqDefinition.CLOTO_NPC);
        int needed = stageOnePassesNeeded(session.companionAdopted(), session.memberCount(), livePartySize(leader));
        boolean agentLeader = isAgent(session, leader.getId());
        // The stage-1 levers are a companion run's, for its human leader; stock and all-Agent runs use none of them.
        AgentKpqStageOneLevers levers = session.companionAdopted() && !agentLeader
                ? session.stageOneLevers() : AgentKpqStageOneLevers.NONE;
        StageOneTick tick = new StageOneTick(session, levers, levers.watchesLigators()
                ? ACTIONS.liveMonsterPositions(leader, stage1GrindTargets(leader)) : List.of());
        List<MapItem> floorCoupons = liveCouponDrops(leader.getMap());
        int couponsOnGround = couponQuantityOnGround(floorCoupons);
        int remainingPartyNeed = remainingPartyCouponNeed(session, leader);
        boolean knownCouponNeedsCovered = allKnownCouponNeedsCovered(session, leader);
        AgentKpqCouponCollectionPolicy.Decision collection =
                AgentKpqCouponCollectionPolicy.decide(
                        nowMs,
                        session.phaseEnteredAtMs(),
                        session.couponSweepStartedAtMs(),
                        session.nextCouponSweepAtMs(),
                        couponsOnGround,
                        remainingPartyNeed,
                        COUPON_SWEEP_INTERVAL_MS,
                        COUPON_SWEEP_MAXIMUM_MS);
        session.setCouponSweepStartedAtMs(collection.sweepStartedAtMs());
        session.setNextCouponSweepAtMs(collection.nextSweepAtMs());
        syncCouponLootObjectives(session, leader,
                collection.sweepActive() ? floorCoupons : List.of(), collection.repick(),
                levers.fightBeforeLoot(), tick.liveLigators(), nowMs);
        if (session.companionAdopted() && !agentLeader) {
            observeLeaderKills(session, leader, floorCoupons);
            // While the re-roll question is out it is the only thing asked of him, so his "ok" can only answer it.
            if (levers.leaderCoupons() && !session.rerollPending()) askLeaderForCoupons(session, leader, nowMs);
        }
        if (levers.clusters()) assignStageOneClusters(session, leader, cloto);
        // A companion run's helpers hold the collector's tower (or cluster); a stock run's fight from a spot under it.
        AgentKpqSession.HelperTerritory helperTerritory = session.companionAdopted()
                ? stageOneHelperTerritory(session, leader, levers.clusters(), nowMs) : null;
        Point helperAnchor = session.companionAdopted() ? null : stageOneHelperAnchor(session, leader);
        // A human leader picks the passes up by hand, and a dropped pass expires after 180 s: live, two of three
        // passes dropped one by one expired before the leader got to them. So a companion keeps its pass, and helps
        // the collectors, until every pass exists; then they all go down together. In a companion run they set out
        // once the last collector has its coupons: its walk to Cloto and trade take about as long as theirs to the
        // leader, who is asked down to Cloto then, and not at entry, where it would stop killing for the coupons.
        boolean lastCollectorDone = knownCouponNeedsCovered && !everyNonLeaderPassCreated(session);
        boolean holdPasses = !agentLeader && !everyNonLeaderPassCreated(session)
                && !(session.companionAdopted() && lastCollectorDone);
        if (session.companionAdopted() && !agentLeader && lastCollectorDone && cloto != null
                && !session.rerollPending()
                && leader.getPosition().distance(cloto) > STAGE1_LEADER_FAR_FROM_CLOTO_PX) {
            if (narrate(session, narrator != null ? narrator : firstStandingAgent(session), "stage1-come-down",
                    leader.getName() + ", come on down by cloto, we're almost done.")) {
                session.markStageOneLeaderLine(nowMs);
            }
        }
        // A companion run says it even with coordination chat off: the passes expire where they lie.
        boolean passesAtFeetLine = !agentLeader
                && (session.companionAdopted() || memberCoordinationVisible(session));
        // A companion run's holders drop on the human leader's own spot, as stage 5's deliverers do: a player picks up
        // only what lies almost under him. A stock run's still drop within NEAR_PX of him.
        boolean dropAtFeet = session.companionAdopted() && !agentLeader;
        Point leaderSpot = dropAtFeet ? groundSpot(leader) : null;
        for (AgentKpqMemberState member : session.members()) {
            Character agent = memberCharacter(member.characterId(), leader);
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || agent.getMap() == null
                    || agent.getMapId() != AgentKpqDefinition.STAGE_1_MAP) {
                if (member.characterId() != session.eventLeaderId()) allDelivered = false;
                continue;
            }
            if (member.memberType() == AgentKpqMemberState.MemberType.HUMAN) {
                if (member.characterId() != session.eventLeaderId()) {
                    if (KPQ.playerGrid(agent) == 0) session.markPassCreated(member, nowMs);
                    if (member.passCreated()
                            && agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false) == 0) {
                        member.markPassDelivered();
                    }
                    if (!member.passDelivered()) allDelivered = false;
                    if (!member.passDelivered()) {
                        AgentKpqHumanDialogueRuntime.remindStageOne(session, agent);
                        AgentKpqHumanDialogueRuntime.promptDelayed(session, agent,
                                "finish your coupons and drop the pass beside " + leader.getName(), nowMs);
                    }
                }
                continue;
            }
            if (entry == null) {
                if (member.characterId() != session.eventLeaderId()) allDelivered = false;
                continue;
            }
            if (stageMovementDelayed(session, member, entry, 1, nowMs)) {
                if (member.characterId() != session.eventLeaderId()) allDelivered = false;
                continue;
            }
            if (member.characterId() == session.eventLeaderId()) {
                member.setRole(AgentKpqMemberState.Role.EVENT_LEADER);
                if (!livePassDrops(agent.getMap()).isEmpty()) {
                    cancelStageOneLandingSafety(member, entry, agent);
                    ACTIONS.stop(entry);
                    KPQ.lootNearby(agent, Set.of(AgentKpqDefinition.PASS_ITEM));
                } else if (knownCouponNeedsCovered) {
                    cancelStageOneLandingSafety(member, entry, agent);
                    moveTowardStageOneExit(session, member, agent, entry);
                } else {
                    grindStageOne(member, entry, agent, null, null, tick, nowMs);
                }
                continue;
            }
            if (!member.questionRequested()) {
                cancelStageOneLandingSafety(member, entry, agent);
                Point approach = npcApproachPoint(session, member, agent,
                        ACTIONS.npcPosition(agent, AgentKpqDefinition.CLOTO_NPC), 101);
                if (!walkToPoint(agent, approach, entry, NPC_APPROACH_PX)) {
                    allDelivered = false;
                    continue;
                }
                if (!stageOneNpcDelayElapsed(session, member, entry, nowMs, 101,
                        STAGE1_QUESTION_DELAY_MS, STAGE1_QUESTION_VARIANCE_MS)) {
                    allDelivered = false;
                    continue;
                }
                if (nowMs < member.nextRetryAtMs()) {
                    allDelivered = false;
                    continue;
                }
                member.setNextRetryAtMs(nowMs + INTERACTION_RETRY_MS);
                boolean invoked = KPQ.runNpc(agent, AgentKpqDefinition.CLOTO_NPC);
                int target = AgentKpqDefinition.couponTarget(KPQ.playerGrid(agent));
                if (target > 0) {
                    member.clearBlocker();
                    session.assignCouponTarget(member, target, nowMs);
                    member.setActionNotBeforeMs(0L);
                    member.setRole(AgentKpqMemberState.Role.COUPON_COLLECTOR);
                    narrate(session, agent, "coupon-target-" + agent.getId(), voice(session,
                            "i need " + target + " coupons.", "I need " + target + " coupons."));
                } else {
                    member.observeBlocker(invoked ? "stage1-question-state" : "stage1-question-npc", nowMs);
                    if (nowMs - member.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS) {
                        fail(session, "Cloto could not assign a coupon question to " + agent.getName(), nowMs);
                        return;
                    }
                }
            }
            if (!member.passCreated()) {
                member.setRole(AgentKpqMemberState.Role.COUPON_COLLECTOR);
                allDelivered = false;
                int have = agent.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false);
                member.tallyStage1Coupons(have);
                reportCouponProgress(session, agent, member, have);
                if (member.couponTarget() > 0 && have >= member.couponTarget()) {
                    cancelStageOneLandingSafety(member, entry, agent);
                    ACTIONS.stop(entry);
                    if (have > member.couponTarget()) {
                        AgentScriptItemActionService.dropItem(entry, InventoryType.ETC,
                                AgentKpqDefinition.COUPON_ITEM, (short) (have - member.couponTarget()));
                        continue;
                    }
                    Point approach = npcApproachPoint(session, member, agent,
                            ACTIONS.npcPosition(agent, AgentKpqDefinition.CLOTO_NPC), 151);
                    if (!walkToPoint(agent, approach, entry, NPC_APPROACH_PX)) {
                        continue;
                    }
                    if (!stageOneNpcDelayElapsed(session, member, entry, nowMs, 151,
                            STAGE1_SUBMIT_DELAY_MS, STAGE1_SUBMIT_VARIANCE_MS)) {
                        continue;
                    }
                    if (nowMs < member.nextRetryAtMs()) continue;
                    member.setNextRetryAtMs(nowMs + INTERACTION_RETRY_MS);
                    KPQ.runNpc(agent, AgentKpqDefinition.CLOTO_NPC);
                    if (agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false) > 0) {
                        member.clearBlocker();
                        session.markPassCreated(member, nowMs);
                        member.setActionNotBeforeMs(0L);
                        member.setRole(AgentKpqMemberState.Role.PASS_DELIVERER);
                        session.markProgress(nowMs);
                    } else {
                        member.observeBlocker("stage1-pass-create", nowMs);
                        if (nowMs - member.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS) {
                            fail(session, agent.getName()
                                    + " could not create a Stage 1 pass; check ETC inventory capacity", nowMs);
                            return;
                        }
                    }
                } else {
                    // With the cluster lever it camps its own cluster; otherwise it grinds map-wide.
                    grindStageOne(member, entry, agent, null,
                            levers.clusters() ? clusterTerritory(session, member, nowMs) : null, tick, nowMs);
                }
            }
            if (member.passCreated() && !member.passDelivered()) {
                allDelivered = false;
                if (holdPasses) {
                    grindStageOne(member, entry, agent, helperAnchor, helperTerritory, tick, nowMs);
                    continue;
                }
                cancelStageOneLandingSafety(member, entry, agent);
                // A holder that cannot get onto the leader's own spot within STAGE1_PASS_AT_FEET_MS (he keeps moving,
                // or stays up a rope or ladder) drops beside him as a stock run's do, and tells him.
                boolean onHisSpot = dropAtFeet && !member.stage1PassChaseOverdue(nowMs, STAGE1_PASS_AT_FEET_MS);
                PassDropPlan plan = onHisSpot ? passDropPlan(agent, leader, leaderSpot) : null;
                if (onHisSpot ? plan.dropNow() : near(agent, leader, NEAR_PX)) {
                    ACTIONS.stop(entry);
                    int passesBeforeDrop = agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
                    boolean dropRequested = AgentScriptItemActionService.dropItem(
                            entry, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 1);
                    int passesAfterDrop = agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
                    if (dropRequested && passDropConfirmed(passesBeforeDrop, passesAfterDrop)) {
                        member.markPassDelivered();
                        member.setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
                        session.markProgress(nowMs);
                        if (dropAtFeet) {
                            logPassDrop(session, 1, agent, leader, onHisSpot
                                    ? AgentKpqPassHandOver.DropReason.BESIDE
                                    : AgentKpqPassHandOver.DropReason.OVERDUE, onHisSpot ? plan.spot() : null, 1,
                                    nowMs);
                            if (onHisSpot) session.notePassPlace(1, plan.place());
                        }
                        // The leader gets the one pickup line below instead of a line per companion, unless this
                        // one could not get onto his spot.
                        if (!agentLeader && !passesAtFeetLine) {
                            narrate(session, agent, "pass-dropped-" + agent.getId(),
                                    stageOnePassDropMessage(leader.getName()));
                        } else if (dropAtFeet && !onHisSpot) {
                            narrate(session, agent, "pass-dropped-" + agent.getId(),
                                    AgentKpqPassHandOver.passBesideLine(leader.getName()));
                        }
                    }
                } else if (onHisSpot) {
                    if (plan.walkTo() != null) ACTIONS.navigate(entry, plan.walkTo(), true);
                } else {
                    ACTIONS.navigate(entry, leader.getPosition(), false);
                }
            } else if (member.passDelivered()) {
                if (knownCouponNeedsCovered) {
                    cancelStageOneLandingSafety(member, entry, agent);
                    moveTowardStageOneExit(session, member, agent, entry);
                }
                else grindStageOne(member, entry, agent, helperAnchor, helperTerritory, tick, nowMs);
            }
        }
        List<MapItem> floorPasses = livePassDrops(leader.getMap());
        if (agentLeader && !floorPasses.isEmpty()) {
            ACTIONS.stop(leaderEntry);
            KPQ.lootNearby(leader, Set.of(AgentKpqDefinition.PASS_ITEM));
            floorPasses = livePassDrops(leader.getMap());
        }
        AgentKpqMemberState leaderState = session.member(session.eventLeaderId());
        int leaderPasses = leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        if (dropAtFeet) observeLeaderPassPickup(session, 1, leader, leaderPasses, nowMs);
        reportLeaderPassProgress(session, narrator, leader.getName(), leaderState, leaderPasses, needed);
        int floorPassQuantity = passQuantityOnGround(floorPasses);
        // Only the companions' drops: a second human may keep its pass for minutes while theirs lie there expiring.
        // Where a holder stepped aside to drop, or nowhere by him landed on his foothold, it says where they lie.
        if (passesAtFeetLine && companionPassesDelivered(session) && floorPassQuantity > 0) {
            narrate(session, narrator != null ? narrator : firstStandingAgent(session), "stage1-passes-at-feet",
                    AgentKpqPassHandOver.stageOnePassesLine(leader.getName(), session.passPlace(1)));
        }
        if (leaderPasses >= needed) {
            session.setMissingPassSinceMs(0L);
            if (!agentLeader) {
                coordinate(session, narrator, "human-stage1-submit", voice(session,
                        leader.getName() + ", you've got all the passes, go talk to cloto.",
                        leader.getName() + ", you have " + leaderPasses + "/" + needed
                                + " passes. Please talk to Cloto."));
                return;
            }
            Point approach = npcApproachPoint(session, leaderState, leader, cloto, 191);
            if (walkToPoint(leader, approach, leaderEntry, NPC_APPROACH_PX)) {
                ACTIONS.stop(leaderEntry);
                if (!stageOneNpcDelayElapsed(session, leaderState, leaderEntry, nowMs, 191,
                        STAGE1_SUBMIT_DELAY_MS, STAGE1_SUBMIT_VARIANCE_MS)) {
                    return;
                }
                if (nowMs >= leaderState.nextRetryAtMs()) {
                    leaderState.setNextRetryAtMs(nowMs + INTERACTION_RETRY_MS);
                    if (submitReadyStageOne(session, leader, nowMs, "npc-approach")) {
                        leaderState.clearBlocker();
                    } else {
                        leaderState.observeBlocker("stage1-submit-rejected", nowMs);
                        if (nowMs - leaderState.blockerSinceMs() >= STAGE1_SUBMIT_RECOVERY_MS) {
                            forceReadyStageOne(session, leader, nowMs, "npc-rejection-timeout");
                        }
                    }
                }
            } else {
                leaderState.observeBlocker("stage1-submit-walk", nowMs);
                if (nowMs - leaderState.blockerSinceMs() >= STAGE1_SUBMIT_RECOVERY_MS
                        && nowMs >= leaderState.nextRetryAtMs()) {
                    leaderState.setNextRetryAtMs(nowMs + INTERACTION_RETRY_MS);
                    submitReadyStageOne(session, leader, nowMs, "navigation-timeout");
                }
            }
            return;
        }
        // A companion run's human leader picks the passes up by hand, and one he leaves lying past the patience is not
        // on its way: live, one of three dropped at his feet held the recovery back until it expired 180 s later.
        // While the re-roll question is out it is the only thing asked of him, so he is not reminded then, and the
        // pass he has not heard about still holds the recovery back, as any floor pass does in a stock run.
        boolean patient = dropAtFeet && !session.rerollPending();
        if (patient) nudgeLeaderAboutFloorPasses(session, leader, narrator, floorPasses, nowMs);
        int waitingFloorPasses = patient
                ? stageOneWaitingFloorPasses(floorPasses, nowMs, STAGE1_FLOOR_PASS_PATIENCE_MS) : floorPassQuantity;
        if (!allDelivered || waitingFloorPasses > 0) {
            session.setMissingPassSinceMs(0L);
            return;
        }
        if (session.missingPassSinceMs() == 0L) {
            session.setMissingPassSinceMs(nowMs);
            return;
        }
        if (shouldBypassMissingPasses(allDelivered, leaderPasses, needed,
                waitingFloorPasses, session.missingPassSinceMs(), nowMs,
                MISSING_PASS_GRACE_MS)
                && bypassMissingStageOnePasses(
                        session, leader, leaderPasses, needed, floorPassQuantity, "grace-timeout", nowMs)
                && dropAtFeet) {
            takeBackStageOnePasses(session, leader, floorPasses);
        }
    }

    /**
     * After a companion run's recovery clears stage 1, takes its passes back as Cloto's own clear takes the leader's
     * (9020001.js gainItem(4001008, -held)): one left lying could be picked up after the clear, and Cloto counts any
     * pass in stage 5, so it would lower stage 5's ten. Stock runs keep theirs, as before.
     */
    private static void takeBackStageOnePasses(AgentKpqSession session, Character leader, List<MapItem> floorPasses) {
        MapleMap map = leader.getMap();
        int fromFloor = 0;
        for (MapItem drop : floorPasses) {
            if (AgentMapGatewayRuntime.map().expireItemDrop(map, drop)) fromFloor++;
        }
        // Read after the floor is clear, so a pass he picked up meanwhile goes too.
        int fromLeader = leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        if (fromLeader > 0) {
            AgentInventoryGatewayRuntime.inventory().removeById(
                    leader, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, fromLeader, false, false);
        }
        log.info("KPQ Stage 1 passes taken back at the recovery: session={} leader={}({}) fromLeader={} "
                        + "floorDrops={}/{}",
                session.sessionId(), leader.getName(), leader.getId(), fromLeader, fromFloor, floorPasses.size());
    }

    /**
     * The floor passes still on their way to a companion run's human leader: one he has left lying longer than the
     * patience, by its own drop time, is not, however near him it lies.
     */
    static int stageOneWaitingFloorPasses(List<MapItem> floorPasses, long nowMs, long patienceMs) {
        List<MapItem> waiting = new ArrayList<>();
        for (MapItem drop : floorPasses) {
            if (nowMs - drop.getDropTime() < patienceMs) waiting.add(drop);
        }
        return passQuantityOnGround(waiting);
    }

    /**
     * A companion's pass that a companion run's human leader has left lying STAGE1_PASS_NUDGE_MS gets him one reminder,
     * from the companion that dropped the oldest, or the narrator once that companion's Agent is no longer loaded.
     */
    private static void nudgeLeaderAboutFloorPasses(
            AgentKpqSession session, Character leader, Character narrator, List<MapItem> floorPasses, long nowMs) {
        List<MapItem> lying = new ArrayList<>();
        MapItem oldest = null;
        boolean byHisFeet = true;
        for (MapItem drop : floorPasses) {
            // Only a companion's: a second human's lying pass must not have the run speak, least of all as him.
            AgentKpqMemberState dropper = session.member(drop.getOwnerId());
            if (dropper == null || dropper.memberType() != AgentKpqMemberState.MemberType.AGENT
                    || dropper.characterId() == session.eventLeaderId()
                    || nowMs - drop.getDropTime() < STAGE1_PASS_NUDGE_MS) {
                continue;
            }
            lying.add(drop);
            byHisFeet &= inLeadersReach(leader, drop);
            if (oldest == null || drop.getDropTime() < oldest.getDropTime()) oldest = drop;
        }
        if (oldest == null) return;
        // The stage logic runs only while every companion of the run stands in it (membersAlive pauses it for a
        // downed one or one respawned outside), so the dropper says it unless its Agent is no longer loaded.
        Character dropper = character(oldest.getOwnerId());
        Character speaker = dropper != null ? dropper : narrator != null ? narrator : firstStandingAgent(session);
        narrate(session, speaker, "stage1-pass-nudge",
                AgentKpqPassHandOver.stageOneNudgeLine(leader.getName(), passQuantityOnGround(lying), byHisFeet));
    }

    /**
     * Cloto wants a pass from every party member but the leader, and still counts a member dropped from a companion
     * run (offline or walked out). Asking for more passes than can arrive lets the missing-pass grace clear stage 1
     * instead of telling the human leader to hand in too few.
     */
    static int stageOnePassesNeeded(boolean adopted, int sessionMembers, int partySize) {
        return adopted ? Math.max(sessionMembers, partySize) - 1 : sessionMembers - 1;
    }

    private static int livePartySize(Character leader) {
        server.agents.integration.AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(leader);
        return party == null ? 0 : (int) party.members().stream().filter(java.util.Objects::nonNull).count();
    }

    static String stageOnePassDropMessage(String leaderName) {
        String name = leaderName == null || leaderName.isBlank() ? "Leader" : leaderName;
        return name + ", I dropped my pass beside you.";
    }

    private static void combinationStage(AgentKpqSession session,
                                         Character leader,
                                         Character narrator,
                                         int stage,
                                         long nowMs) {
        AgentKpqDefinition.CombinationStage definition = AgentKpqDefinition.combinationStage(stage);
        if ("true".equals(KPQ.property(leader, definition.clearProperty()))) {
            session.pace().markAccepted(stage, session.attemptIndex() + 1, nowMs);
            enterNextPortal(session, stage + 1, nowMs);
            return;
        }
        List<AgentKpqMemberState> participants = participants(session);
        if (participants.size() < 3) {
            fail(session, "Stage " + stage + " needs three controllable puzzle participants", nowMs);
            return;
        }
        boolean leaderParticipates = !leaderHasDedicatedClotoRole(participants, leader.getId());
        List<List<Integer>> order = AgentKpqCombinationOrder.forPositionCount(definition.positions().size());
        if (KPQ.property(leader, definition.answerProperty()) == null) {
            if (!leaderParticipates) {
                prePositionInitialFormation(session, leader, narrator, definition,
                        participants, order.getFirst(), nowMs);
            }
            if (isAgent(session, leader.getId())) {
                if (!stationLeaderAtCloto(session, leader, stage, nowMs)) return;
                KPQ.runNpc(leader, AgentKpqDefinition.CLOTO_NPC);
            } else {
                coordinate(session, narrator, "human-stage" + stage + "-initialize", voice(session,
                        leader.getName() + ", talk to cloto so we can start.",
                        leader.getName() + ", please talk to Cloto to begin Stage " + stage + '.'));
                AgentKpqHumanDialogueRuntime.promptDelayed(session, leader, voice(session,
                        "talk to cloto so we can start", "talk to Cloto to begin Stage " + stage), nowMs);
                if (stage == 4 && !leaderParticipates) inviteLeaderUpToCloto(session, leader, narrator);
            }
            return;
        }
        // A three-member party needs all members on the puzzle. Its Agent leader therefore
        // uses the authoritative Cloto script from the assigned puzzle position. With four
        // members, the leader has no puzzle assignment and stays physically beside Cloto.
        if (isAgent(session, leader.getId()) && !leaderParticipates
                && !stationLeaderAtCloto(session, leader, stage, nowMs)) {
            return;
        }
        if (session.attemptIndex() >= order.size()) {
            failExhaustedPuzzle(session, leader, definition, nowMs);
            return;
        }
        int attempt = session.attemptIndex();
        List<Integer> combination = order.get(attempt);
        if (!combination.equals(session.combination())) {
            int attemptId = session.nextAttemptId();
            List<AgentKpqMemberState> movers = assignFormation(
                    participants, combination, session.seed(), attemptId, nowMs);
            session.setCombination(combination);
            session.setPuzzleCheckAtMs(0L);
            if (!movers.isEmpty()) {
                // The attempt id changes when a reset formation is assigned again, so it is announced again.
                coordinate(session, narrator, "s" + stage + "-a" + attempt + "-" + attemptId,
                        formationInstruction(movers, leader, stage, session.companionAdopted()));
            }
        }
        boolean humanLeader = !isAgent(session, leader.getId());
        // A rejection Cloto gave the formation as it stood counts now, even if a mover has stepped off since.
        if (humanLeader && session.consumeHumanPuzzleRejection(stage)) {
            session.markPuzzleVerdict(nowMs);
            advancePuzzleAttempt(session, leader, definition, order.size(), attempt, nowMs);
            return;
        }
        long formationStableMs = humanLeader && session.companionAdopted()
                ? HUMAN_LED_FORMATION_STABLE_MS : FORMATION_STABLE_MS;
        boolean stable = true;
        for (AgentKpqMemberState participant : participants.subList(0, 3)) {
            Character agent = memberCharacter(participant.characterId(), leader);
            AgentRuntimeEntry entry = entry(participant.characterId());
            if (agent == null || agent.getMapId() != definition.mapId()) {
                stable = false;
                continue;
            }
            if (nowMs < participant.stageMovementNotBeforeMs()) {
                if (entry != null) ACTIONS.stop(entry);
                stable = false;
                continue;
            }
            if (nowMs < participant.actionNotBeforeMs()) {
                stable = false;
                continue;
            }
            boolean grounded = definition.holdMode() != AgentKpqDefinition.HoldMode.GROUNDED
                    || ACTIONS.grounded(agent);
            if (!puzzlePositionReady(definition, participant.assignedPosition(),
                    agent.getPosition(), grounded)) {
                participant.clearFidget();
                participant.observeBlocker("puzzle-position", nowMs);
                participant.setStableSinceMs(0L);
                if (entry != null) {
                    ACTIONS.navigate(entry, formationTarget(
                            definition, participant,
                            session.seed() + participant.blockerAttempts() * 17L), true);
                    if (nowMs - participant.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS * 2L) {
                        fail(session, "Stage " + stage + " formation movement exhausted for member "
                                + participant.characterId(), nowMs);
                        return;
                    }
                } else if (nowMs - participant.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS) {
                    String spot = puzzleSpot(stage) + ' ' + participant.assignedPosition();
                    coordinate(session, narrator,
                            "human-stage" + stage + "-position-" + participant.characterId(), voice(session,
                                    agent.getName() + ", can you get on " + spot + '?',
                                    agent.getName() + ", please move to " + participant.assignedPosition() + '.'));
                    AgentKpqHumanDialogueRuntime.promptDelayed(session, agent, voice(session,
                            "get on " + spot, "move to position " + participant.assignedPosition()), nowMs);
                }
                stable = false;
            } else if (participant.stableSinceMs() == 0L) {
                participant.clearBlocker();
                if (entry != null) ACTIONS.stop(entry);
                participant.setStableSinceMs(nowMs);
                stable = false;
            } else if (nowMs - participant.stableSinceMs() < formationStableMs) {
                stable = false;
            }
            if (puzzlePositionReady(definition, participant.assignedPosition(),
                    agent.getPosition(), grounded)) {
                AgentKpqPuzzleFidgetBehavior.tick(
                        participant, agent, entry, definition, nowMs);
            }
        }
        // A human leader's Cloto rejection counts only while this mark stands (recordHumanPuzzleValidation).
        if (!stable) {
            session.clearFormationReady();
            return;
        }
        if (session.markFormationReady() && session.mode() != AgentKpqSession.Mode.BACKGROUND_POPULATION) {
            // Paired with the verdict lines, it splits each check into the companions re-forming and the check.
            long verdictAtMs = session.lastPuzzleVerdictAtMs();
            log.info("KPQ puzzle formation ready: session={} stage={} attempt={} attemptId={} sinceVerdictMs={} "
                            + "phaseMs={} humanLeader={}",
                    session.sessionId(), stage, attempt, session.attemptId(),
                    verdictAtMs == 0L ? -1L : nowMs - verdictAtMs, nowMs - session.phaseEnteredAtMs(),
                    humanLeader);
        }
        AgentKpqMemberState fidgeter = AgentKpqPuzzleFidgetBehavior.select(
                session, participants.subList(0, 3));
        if (fidgeter != null && fidgeter.fidgetedAttemptId() != session.attemptId()) {
            Character agent = memberCharacter(fidgeter.characterId(), leader);
            AgentRuntimeEntry entry = entry(fidgeter.characterId());
            if (agent != null && entry != null) {
                AgentKpqPuzzleFidgetBehavior.begin(fidgeter, definition, agent.getPosition(),
                        session.seed(), session.attemptId(), nowMs, LOCAL_RECOVERY_TIMEOUT_MS);
            }
        }
        if (isAgent(session, leader.getId())) {
            if (session.puzzleCheckAtMs() == 0L) {
                long delayMs = puzzleCheckDelayMs(session.seed(), stage, attempt);
                session.setPuzzleCheckAtMs(nowMs + delayMs);
                if (session.mode() == AgentKpqSession.Mode.TEST_OBSERVATION) {
                    log.info("KPQ puzzle check scheduled: session={} stage={} attempt={} attemptId={} "
                                    + "delayMs={} fidgeters={}",
                            session.sessionId(), stage, attempt, session.attemptId(), delayMs,
                            participants.subList(0, 3).stream()
                                    .filter(member -> member.fidgetTarget() != null).count());
                }
                return;
            }
            if (nowMs < session.puzzleCheckAtMs()) return;
            if (session.mode() == AgentKpqSession.Mode.TEST_OBSERVATION
                    && session.markPuzzleCheckLogged(session.attemptId())) {
                log.info("KPQ puzzle check executing: session={} stage={} attempt={} attemptId={} "
                                + "latenessMs={} fidgeters={} positions={}",
                        session.sessionId(), stage, attempt, session.attemptId(),
                        Math.max(0L, nowMs - session.puzzleCheckAtMs()),
                        participants.subList(0, 3).stream()
                                .filter(member -> member.fidgetTarget() != null).count(),
                        participants.subList(0, 3).stream()
                                .map(member -> member.characterId() + "->" + member.assignedPosition())
                                .toList());
            }
        }
        if (humanLeader) {
            AgentKpqSession.PuzzleValidation validation = session.consumeHumanPuzzleValidation(stage);
            if (validation == null) {
                // Asked again when a reset forms the attempt anew, and after each rejection dropped as early.
                coordinate(session, narrator, "human-stage" + stage + "-check-" + attempt + "-"
                                + session.attemptId() + "-" + session.earlyPuzzleRejections(),
                        voice(session, leader.getName() + ", we're set, check with cloto.",
                                leader.getName() + ", please check this formation with Cloto."));
                AgentKpqHumanDialogueRuntime.promptDelayed(session, leader, voice(session,
                        "check with cloto", "check this formation with Cloto"), nowMs, true);
                return;
            }
            session.markPuzzleVerdict(nowMs);
            if (validation.accepted()) {
                session.pace().markAccepted(stage, attempt + 1, nowMs);
                return;
            }
            advancePuzzleAttempt(session, leader, definition, order.size(), attempt, nowMs);
            return;
        }
        if (!leaderParticipates && !stationLeaderAtCloto(session, leader, stage, nowMs)) {
            return;
        }
        if (!KPQ.runNpc(leader, AgentKpqDefinition.CLOTO_NPC)) {
            if (blockedTooLong(session, "stage" + stage + "-npc-check", nowMs)) {
                fail(session, "Stage " + stage + " Cloto check repeatedly failed", nowMs);
            }
            return;
        }
        session.clearBlocker();
        session.markPuzzleVerdict(nowMs);
        if (!"true".equals(KPQ.property(leader, definition.clearProperty()))) {
            advancePuzzleAttempt(session, leader, definition, order.size(), attempt, nowMs);
        } else {
            session.pace().markAccepted(stage, attempt + 1, nowMs);
        }
    }

    /**
     * Stage 4 starts at the bottom of a long staircase, and its way out is at the top beside Cloto. Live (run 6) the
     * human leader stayed below for the whole puzzle and then took seven minutes to climb out while the passes waited
     * on stage 5. A companion run asks the leader up once, in passing, and warns off the barrels, since Cloto counts
     * anyone standing on one. A leader who must stand on a barrel (three members) is not asked: the formation call
     * gives it its barrel.
     */
    private static void inviteLeaderUpToCloto(AgentKpqSession session, Character leader, Character narrator) {
        Point cloto = ACTIONS.npcPosition(leader, AgentKpqDefinition.CLOTO_NPC);
        if (!session.companionAdopted() || cloto == null || leader.getPosition() == null
                || leader.getMapId() != AgentKpqDefinition.STAGE_4_MAP
                || leader.getPosition().y - cloto.y <= STAGE4_LEADER_BELOW_CLOTO_PX) {
            return;
        }
        narrate(session, narrator != null ? narrator : firstStandingAgent(session), "stage4-come-up",
                leader.getName() + ", come on up by cloto, the way out's at the top. stay off the barrels.");
    }

    private static void stage5(
            AgentKpqSession session, Character leader, Character narrator, long nowMs) {
        if ("true".equals(KPQ.property(leader, "5stageclear"))) {
            dropCarriedStageFivePasses(session, leader, nowMs);
            clearStageFiveLootTargets(session);
            stopAll(session);
            session.freezeRewardEligibility();
            transition(session, AgentKpqSession.Phase.CLAIMING_REWARDS, nowMs);
            return;
        }
        coordinate(session, narrator, "stage5-enter", voice(session,
                "let's clear these out and get the 10 passes.",
                "Clearing the monsters and collecting 10 passes."));
        boolean agentLeader = isAgent(session, leader.getId());
        boolean carriers = stageFivePassCarriers(session);
        int normalAlive = ACTIONS.liveMonsterCount(leader, STAGE_5_NORMAL_MOBS);
        int bossAlive = ACTIONS.liveMonsterCount(leader, STAGE_5_BOSS_MOBS);
        observeStageFiveArrivals(session, leader, nowMs);
        observeStageFiveBossCombat(session, normalAlive, bossAlive, nowMs);
        session.beginStage5LootDelayIfBossDefeated(bossAlive, nowMs, KING_SLIME_LOOT_DELAY_MS);
        boolean lootDelayActive = session.stage5LootDelayActive(nowMs);
        if (agentLeader && !lootDelayActive) {
            KPQ.lootNearby(leader, Set.of(AgentKpqDefinition.PASS_ITEM));
        } else if (!agentLeader) {
            // A companion run's companions bring the passes up, so its leader goes to Cloto instead of 2,300 px down for
            // them and back. Everyone arrives on the left top island, which nothing below can reach, and Cloto stands
            // across a gap no jump clears: live (pace1b) a leader told to wait by Cloto stood on that ledge, out of
            // everyone's reach, until the event timer ran out. So he is told the way, even with coordination chat
            // off: nothing else moves him, and his passes wait by Cloto for him.
            if (carriers) {
                Character speaker = stageFiveSpeaker(session, narrator);
                if (narrate(session, speaker, "human-stage5-collect",
                        AgentKpqPassHandOver.routeLine(leader.getName()))) {
                    session.markStage5RouteTold(nowMs);
                    warnOfCurseEyes(session, leader, speaker, "human-stage5-curse-eyes");
                }
            } else {
                coordinate(session, narrator, "human-stage5-collect", voice(session,
                        AgentKpqPassHandOver.routeLine(leader.getName()),
                        leader.getName() + ", please collect the 10 passes while we clear the monsters."));
                AgentKpqHumanDialogueRuntime.promptDelayed(session, leader,
                        "collect the 10 passes while we clear the monsters", nowMs);
            }
        }
        int leaderPasses = leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        if (carriers && observeLeaderPassPickup(session, 5, leader, leaderPasses, nowMs) > 0) {
            // A leader bending down for his passes gets the missing-pass grace afresh: run out, the recovery would make
            // the rest vanish from under him while he picks them up.
            session.setMissingPassSinceMs(0L);
        }
        int stage5MobCount = normalAlive + bossAlive;
        boolean reviveGraceActive = session.stage5ReviveGraceActive(
                bossAlive, nowMs, KING_SLIME_REVIVE_GRACE_MS);
        List<MapItem> passDrops = livePassDrops(leader.getMap());
        int floorPasses = passQuantityOnGround(passDrops);
        // In a companion run a pass left lying out of the leader's reach stops counting as on its way after a while:
        // live, two passes dropped 2,300 px under the leader held the recovery back until they expired.
        List<MapItem> stalePasses = carriers ? stalePasses(session, leader, passDrops, nowMs) : List.of();
        // So does one he leaves lying at his feet, after longer: live (kpq-a6f696f0) ten passes dropped beside him were
        // never picked up and held the recovery back until they expired 180 s later.
        List<MapItem> leftAtFeet = carriers ? passesLeftAtFeet(session, leader, passDrops, nowMs) : List.of();
        if (session.observeStage5Progress(stage5MobCount, leaderPasses)) session.markProgress(nowMs);
        boolean returningToCloto = stageFiveReadyToReturn(
                leaderPasses, normalAlive, bossAlive, reviveGraceActive);
        if (returningToCloto) session.pace().markPassesAtLeader(nowMs);
        boolean mobsDown = stage5MobCount == 0 && !reviveGraceActive;
        long mobsDownMs = session.observeStage5MobsDown(mobsDown, nowMs);
        if (returningToCloto && !agentLeader) {
            // Said before the shoes window and the drop cleanup below: after them a leader holding all ten waited
            // another 6-17 s to hear it.
            coordinate(session, narrator, "human-stage5-submit", voice(session,
                    leader.getName() + ", that's all 10 passes. go talk to cloto.",
                    leader.getName() + ", you have 10/10 passes. Please talk to Cloto."));
            AgentKpqHumanDialogueRuntime.promptDelayed(session, leader, voice(session,
                    "talk to cloto with the passes", "talk to Cloto with the 10 passes"), nowMs);
        }
        // Carriers send the shoes' winner as soon as King Slime is down, while they are still below: by the time the
        // leader holds all ten, up by Cloto, the shoes lie 2,300 px down.
        boolean shoesPending = (returningToCloto || (carriers && bossAlive == 0 && !reviveGraceActive))
                && handleSquishyShoes(session, leader, nowMs);
        if (returningToCloto && shoesPending) {
            int winnerId = session.squishyShoesWinnerId();
            AgentRuntimeEntry winnerEntry = entry(winnerId);
            int activeCollectorId = AgentGrindLootStateRuntime.hasObjectiveLootTarget(winnerEntry)
                    ? winnerId : 0;
            stopStageFiveMembersExcept(session, activeCollectorId);
            return;
        }
        if (returningToCloto && drainStageFiveLoot(session, leader, nowMs)) {
            return;
        }
        boolean sweeping = false;
        for (AgentKpqMemberState member : session.members()) {
            AgentRuntimeEntry entry = entry(member.characterId());
            Character agent = memberCharacter(member.characterId(), leader);
            if (agent == null || agent.getMapId() != AgentKpqDefinition.STAGE_5_MAP) continue;
            member.setRole(stageFiveRole(session, member, agent, carriers, nowMs));
            if (member.memberType() == AgentKpqMemberState.MemberType.AGENT && entry != null) {
                if (carriers) member.observeStage5Passes(agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false));
                // Its sweep over, the re-collector lets go of a pass it never reached: as its loot target the pass
                // would count as fetched, and so as on its way, for good.
                if (member.characterId() == session.stage5RecollectorId()
                        && member.role() != AgentKpqMemberState.Role.STAGE5_PASS_CARRIER
                        && session.claimStage5RecollectTargetRelease()) {
                    AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
                }
                if (nowMs < member.stageMovementNotBeforeMs()) {
                    ACTIONS.stop(entry);
                } else if (returningToCloto) {
                    Point cloto = ACTIONS.npcPosition(agent, AgentKpqDefinition.CLOTO_NPC);
                    Point approach = npcApproachPoint(session, member, agent, cloto, 501);
                    walkToPoint(agent, approach, entry, NPC_APPROACH_PX);
                } else if (carriers && leavePassesBeforeGoingDown(
                        session, member, agent, entry, leader, stage5MobCount > 0, nowMs)) {
                    continue;
                } else if (shoesPending && member.characterId() == session.squishyShoesWinnerId()) {
                    continue;
                } else if (mobsDown && member.role() == AgentKpqMemberState.Role.STAGE5_PASS_CARRIER
                        && member.characterId() == session.stage5RecollectorId()) {
                    recollectPasses(session, member, agent, entry, leader, passDrops, nowMs);
                } else if (mobsDown && member.role() == AgentKpqMemberState.Role.STAGE5_PASS_CARRIER) {
                    sweeping |= sweepStageFivePasses(session, entry, agent, nowMs);
                } else if (mobsDown && member.role() == AgentKpqMemberState.Role.PASS_DELIVERER) {
                    handOverStageFivePasses(session, member, agent, entry, leader, nowMs);
                } else {
                    grindStageFive(session, entry, agent, nowMs);
                }
            }
        }
        // A sweep that has not ended STAGE5_SWEEP_LIMIT_MS after the last kill is one that cannot: a pass it cannot
        // fetch then falls to the floor-pass patience instead of holding the hand-over for its 180 s expiry.
        boolean sweepCapped = mobsDownMs >= STAGE5_SWEEP_LIMIT_MS;
        if (carriers && mobsDown && !lootDelayActive && (!sweeping || sweepCapped)
                && session.startStage5HandOver(nowMs)) {
            logStageFiveHandOverStart(session, leader, sweeping, nowMs);
        }
        if (carriers && !returningToCloto) repeatRouteToCloto(session, leader, narrator, nowMs);
        if (carriers && leaderPasses < 10) nudgeLeaderAboutStageFivePasses(session, leader, narrator, passDrops, nowMs);
        if (reviveGraceActive && stage5MobCount == 0) {
            session.setMissingPassSinceMs(0L);
            return;
        }
        // Passes on their way to the leader are not missing: the recovery would make up all ten 7 s after the last
        // kill while the carriers still held them. A companion run's stale floor passes are fetched once more first;
        // the ones he left at his feet already lie by him.
        int waitingFloorPasses = floorPasses - passQuantityOnGround(stalePasses) - passQuantityOnGround(leftAtFeet);
        if (!returningToCloto && stage5MobCount == 0 && waitingFloorPasses == 0
                && carriedStageFivePasses(session) == 0) {
            if (carriers && session.stage5RecollectSweeping(nowMs, STAGE5_SWEEP_LIMIT_MS)) {
                session.setMissingPassSinceMs(0L);
                return;
            }
            if (session.missingPassSinceMs() == 0L) session.setMissingPassSinceMs(nowMs);
            if (nowMs - session.missingPassSinceMs() < STAGE5_MISSING_PASS_GRACE_MS) return;
            // The one re-collect waits for the carriers to set out and for the grace, as the recovery does: started
            // in the middle of their sweep it would use up the run's only one on a pass they were about to fetch.
            if (carriers && !stalePasses.isEmpty() && !session.stage5RecollectStarted()) {
                if (!session.stage5HandOverStarted()) return;
                if (beginStageFiveRecollect(session, leader, stalePasses, nowMs)) {
                    session.setMissingPassSinceMs(0L);
                    return;
                }
            }
            if (recoverMissingStageFivePasses(session, leader, leaderPasses, floorPasses,
                    passQuantityOnGround(stalePasses), passQuantityOnGround(leftAtFeet), nowMs) && carriers) {
                // What the recovery made up is no pickup of his: the pickup line is how a live check tells them apart.
                session.observeLeaderPasses(leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false));
                takeBackStageFivePasses(session, leader, passDrops);
            }
            return;
        }
        session.setMissingPassSinceMs(0L);
        if (returningToCloto) {
            if (!agentLeader) return;
            AgentKpqMemberState leaderState = session.member(session.eventLeaderId());
            Point approach = npcApproachPoint(session, leaderState, leader,
                    ACTIONS.npcPosition(leader, AgentKpqDefinition.CLOTO_NPC), 501);
            if (walkToPoint(leader, approach, entry(leader.getId()), NPC_APPROACH_PX)) {
                KPQ.runNpc(leader, AgentKpqDefinition.CLOTO_NPC);
            }
        }
    }

    /**
     * Who tells a companion run's leader the way to Cloto: the narrator, or with coordination chat off (no narrator)
     * the first companion standing in the run.
     */
    private static Character stageFiveSpeaker(AgentKpqSession session, Character narrator) {
        return narrator != null ? narrator : firstStandingAgent(session);
    }

    /** With the way to Cloto, while a Curse Eye still lives on the floor the leader drops to. */
    private static void warnOfCurseEyes(AgentKpqSession session, Character leader, Character speaker, String key) {
        if (ACTIONS.liveMonsterCount(leader, STAGE_5_CURSE_EYES) > 0) {
            narrate(session, speaker, key, AgentKpqPassHandOver.CURSE_EYES_CAREFUL);
        }
    }

    /**
     * Says the way to Cloto once more, STAGE5_ROUTE_REPEAT_MS after the first time, if the leader still stands where
     * no companion can reach him, with the Curse Eye caution again while one lives. Like the first time it is said
     * with coordination chat off too. Nothing else can move a leader who stays on the arrival ledge; his passes wait
     * by Cloto, where they cannot expire, until he comes.
     */
    private static void repeatRouteToCloto(
            AgentKpqSession session, Character leader, Character narrator, long nowMs) {
        long toldAtMs = session.stage5RouteToldAtMs();
        if (toldAtMs == 0L || session.stage5RouteRepeated() || nowMs - toldAtMs < STAGE5_ROUTE_REPEAT_MS
                || !leaderOutOfReach(session, leader) || !session.claimStage5RouteRepeat()) {
            return;
        }
        Character speaker = stageFiveSpeaker(session, narrator);
        narrate(session, speaker, "human-stage5-route-again", AgentKpqPassHandOver.routeAgainLine(leader.getName()));
        warnOfCurseEyes(session, leader, speaker, "human-stage5-curse-eyes-again");
    }

    /** True when a companion standing on stage 5's ground has no route at all to where the leader stands. */
    private static boolean leaderOutOfReach(AgentKpqSession session, Character leader) {
        for (AgentKpqMemberState member : session.members()) {
            if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null || agent.getHp() <= 0
                    || agent.getMapId() != AgentKpqDefinition.STAGE_5_MAP
                    || ACTIONS.footing(agent) != PrimitiveCapabilityGateway.Footing.GROUND) {
                continue;
            }
            if (!ACTIONS.canReach(entry, agent, leader.getPosition())) return true;
        }
        return false;
    }

    /**
     * A companion run's companions carry the stage-5 passes to its human leader. Live (runs 6 and D) only the leader
     * could pick them up: the passes lay 2,300 px below Cloto, or expired there, for 106-134 s until the missing-pass
     * recovery made them up.
     */
    private static boolean stageFivePassCarriers(AgentKpqSession session) {
        return session.companionAdopted() && !isAgent(session, session.eventLeaderId());
    }

    /**
     * Carriers pick up every pass they can until none is left to fetch, then all set out together and stop looting
     * passes, so none takes back what another dropped at the leader's feet; one with none to bring, or that has
     * dropped them, just fights. The one companion sent back for passes left lying picks them up again until it has
     * them all, and one that left its passes before going down picks none up again.
     */
    private static AgentKpqMemberState.Role stageFiveRole(AgentKpqSession session, AgentKpqMemberState member,
                                                          Character agent, boolean carriers, long nowMs) {
        if (member.characterId() == session.eventLeaderId()) return AgentKpqMemberState.Role.STAGE5_PASS_COLLECTOR;
        if (!carriers || member.memberType() != AgentKpqMemberState.MemberType.AGENT
                || member.stage5PassesLeftBehind()) {
            return AgentKpqMemberState.Role.COMBAT_HELPER;
        }
        if (!session.stage5HandOverStarted()) return AgentKpqMemberState.Role.STAGE5_PASS_CARRIER;
        if (member.characterId() == session.stage5RecollectorId()
                && session.stage5RecollectSweeping(nowMs, STAGE5_SWEEP_LIMIT_MS)) {
            return AgentKpqMemberState.Role.STAGE5_PASS_CARRIER;
        }
        return switch (member.role()) {
            case STAGE5_PASS_CARRIER -> agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false) > 0
                    ? AgentKpqMemberState.Role.PASS_DELIVERER : AgentKpqMemberState.Role.COMBAT_HELPER;
            case PASS_DELIVERER -> AgentKpqMemberState.Role.PASS_DELIVERER;
            default -> AgentKpqMemberState.Role.COMBAT_HELPER;
        };
    }

    /**
     * A carrier fetches the nearest pass left on the floor once nothing is left to fight. True while it has one to
     * fetch, or one too fresh to target yet, so the hand-over waits for it.
     */
    private static boolean sweepStageFivePasses(
            AgentKpqSession session, AgentRuntimeEntry entry, Character agent, long nowMs) {
        // Entering grind mode clears a loot target, so the mode comes first.
        grindStageFive(session, entry, agent, nowMs);
        return ACTIONS.prepareObjectiveLoot(entry, agent, Set.of(AgentKpqDefinition.PASS_ITEM))
                || livePassDrops(agent.getMap()).stream().anyMatch(drop -> AgentLootEligibility.isWaitingForTargetAge(
                        entry, agent, agent.getMap(), drop, nowMs, AgentLootEligibility.MIN_TARGET_LOOT_AGE_MS));
    }

    /**
     * A deliverer drops what it carries on the leader's own spot, as players hand items over: a player picks up only
     * what lies almost under him. One that cannot reach the leader at all (he stands on the arrival ledge, which has
     * no way in) waits with its passes by Cloto, where he is told to go and where held passes cannot expire, and
     * goes to him once he is up on Cloto's island, or has been in reach for STAGE5_PASS_HANDOVER_MS without coming.
     * One whose route fails for STAGE5_PASS_HANDOVER_MS though he can be
     * reached leaves the passes by Cloto, on his way there, or where it stands when it cannot get there either.
     */
    private static void handOverStageFivePasses(AgentKpqSession session, AgentKpqMemberState member,
                                                Character agent, AgentRuntimeEntry entry, Character leader,
                                                long nowMs) {
        int held = agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        if (held == 0) {
            member.setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
            return;
        }
        Point clotoApproach = npcApproachPoint(session, member, agent,
                ACTIONS.npcPosition(agent, AgentKpqDefinition.CLOTO_NPC), 501);
        // Only where it stands on a foothold does its region, and so the answer, mean anything; in the air or on a
        // ladder on its way it keeps the last one.
        if (ACTIONS.footing(agent) == PrimitiveCapabilityGateway.Footing.GROUND) {
            member.observeStage5LeaderReach(ACTIONS.canReach(entry, agent, leader.getPosition()), nowMs);
        }
        if (member.stage5MeetsLeaderAtCloto()) {
            // Having told him it brings the passes there, it waits by Cloto until he is up on Cloto's level; chasing
            // him once he drops off the ledge, it would pass him on his way up the ladders. Only a leader who could be
            // reached for STAGE5_PASS_HANDOVER_MS and still has not come is gone to.
            if (!leaderUpByCloto(member, leader, clotoApproach)
                    && member.stage5LeaderReachableForMs(nowMs) < STAGE5_PASS_HANDOVER_MS) {
                member.restartStage5Chase(nowMs);
                if (clotoApproach == null || walkToPoint(agent, clotoApproach, entry, NPC_APPROACH_PX)) {
                    ACTIONS.stop(entry);
                }
                return;
            }
            member.endStage5MeetAtCloto();
            member.restartStage5Chase(nowMs);
        }
        Point leaderSpot = groundSpot(leader);
        PassDropPlan plan = passDropPlan(agent, leader, leaderSpot);
        if (plan.dropNow()) {
            dropStageFivePasses(session, member, agent, entry, leader, held,
                    member.characterId() == session.stage5RecollectorId()
                            ? AgentKpqPassHandOver.DropReason.RECOLLECT : AgentKpqPassHandOver.DropReason.BESIDE,
                    plan, nowMs);
            return;
        }
        long chasingMs = nowMs - Math.max(session.stage5HandOverStartedAtMs(), member.stage5ChaseFromMs());
        if (chasingMs < STAGE5_PASS_HANDOVER_MS) {
            ACTIONS.navigate(entry, plan.walkTo(), true);
            return;
        }
        // An overdue deliverer gets one more bound to get the passes to Cloto's approach.
        if (clotoApproach != null && chasingMs < STAGE5_PASS_HANDOVER_MS * 2L
                && ACTIONS.canReach(entry, agent, clotoApproach)) {
            if (walkToPoint(agent, clotoApproach, entry, NPC_APPROACH_PX)) {
                dropStageFivePasses(session, member, agent, entry, leader, held,
                        AgentKpqPassHandOver.DropReason.AT_CLOTO, null, nowMs);
            }
            return;
        }
        dropStageFivePasses(session, member, agent, entry, leader, held,
                AgentKpqPassHandOver.DropReason.OVERDUE, null, nowMs);
    }

    /**
     * The leader stands on a foothold at the height of Cloto's approach and this deliverer has a route to him: he is
     * up on Cloto's island. The arrival ledge is at that height too, but has no way in.
     */
    private static boolean leaderUpByCloto(AgentKpqMemberState member, Character leader, Point clotoApproach) {
        Point position = leader.getPosition();
        return !member.stage5LeaderUnreachable() && clotoApproach != null && position != null
                && ACTIONS.footing(leader) == PrimitiveCapabilityGateway.Footing.GROUND
                && Math.abs(position.y - clotoApproach.y) <= NEAR_PX;
    }

    /** Where a character stands, on its foothold: the spot a deliverer walks onto. */
    private static Point groundSpot(Character character) {
        Point position = character.getPosition();
        if (position == null) return null;
        Point grounded = ACTIONS.groundPoint(character.getMap(), position);
        return grounded == null ? new Point(position) : grounded;
    }

    /** Both on a foothold, within PASS_DROP_AT_FEET_PX of the leader's spot on both axes, on his map. */
    private static boolean passesAtLeadersFeet(Character dropper, Character leader, Point leaderSpot) {
        return dropper.getMap() == leader.getMap() && AgentKpqPassHandOver.atFeet(dropper.getPosition(), leaderSpot,
                ACTIONS.footing(dropper) == PrimitiveCapabilityGateway.Footing.GROUND,
                ACTIONS.footing(leader) == PrimitiveCapabilityGateway.Footing.GROUND, PASS_DROP_AT_FEET_PX);
    }

    /**
     * What a deliverer does about a companion run's leader this tick: drop now, or walk (precise) to {@code walkTo}.
     * {@code spot} names, for the drop log line, where it drops: at his feet, from a spot it moved to, or at his feet
     * though nowhere near him lands on his foothold; {@code place}, for the line that tells him, where the drop lies
     * against him by the map's own placement.
     */
    private record PassDropPlan(boolean dropNow, Point walkTo, String spot, AgentKpqPassHandOver.PassPlace place) {
        static final String AT_FEET = "feet";
        static final String MOVED = "moved";
        static final String NO_SPOT = "none";

        static PassDropPlan walk(Point to) {
            return new PassDropPlan(false, to, null, null);
        }

        static PassDropPlan drop(Point at, String spot, Point landing, Point leaderSpot) {
            return new PassDropPlan(true, at, spot,
                    AgentKpqPassHandOver.PassPlace.of(landing, leaderSpot, PASS_DROP_AT_FEET_PX));
        }
    }

    /**
     * Where a deliverer lets its passes go for a companion run's leader (the at-feet drops: a stage-1 holder's, a
     * stage-5 deliverer's and the re-collect's). The map lands a player's drop on the first foothold under a point
     * 85 px over the dropper: live (2026-10-02, kpq-fea2a83b) a stage-1 pass dropped 6 px from the leader landed 83 px
     * up, on the ramp over him, and only the patience recovery cleared the stage. So the drop at his feet is previewed
     * with the map's own placement and goes down only where it lands at his feet; otherwise the deliverer walks to the
     * nearest spot on his foothold, within PASS_DROP_SPOT_REACH_PX, where it lands on that foothold, and drops
     * there. With no such spot it drops at his feet as before; a map that cannot tell where a drop lands is dropped on
     * as before.
     */
    private static PassDropPlan passDropPlan(Character dropper, Character leader, Point leaderSpot) {
        boolean atHisFeet = passesAtLeadersFeet(dropper, leader, leaderSpot);
        Point at = dropper.getPosition();
        Point landing = ACTIONS.predictDropLanding(dropper, at);
        if (atHisFeet && (landing == null || AgentKpqPassHandOver.landsAt(landing, leaderSpot, PASS_DROP_AT_FEET_PX))) {
            return PassDropPlan.drop(leaderSpot, PassDropPlan.AT_FEET, landing, leaderSpot);
        }
        // A spot is looked for only once it is about at his feet, on his map, with him on a foothold.
        boolean leaderOnGround = ACTIONS.footing(leader) == PrimitiveCapabilityGateway.Footing.GROUND;
        if (leaderSpot == null || at == null || dropper.getMap() != leader.getMap() || !leaderOnGround
                || Math.abs(at.x - leaderSpot.x) > PASS_DROP_SPOT_REACH_PX + PASS_DROP_AT_FEET_PX
                || Math.abs(at.y - leaderSpot.y) > NEAR_PX) {
            return PassDropPlan.walk(leaderSpot);
        }
        // On its way, with nothing over his spot (the usual case), it walks onto it without a search.
        if (!atHisFeet && dropsLandAroundHisSpot(dropper, leaderSpot)) return PassDropPlan.walk(leaderSpot);
        boolean dropperOnGround = ACTIONS.footing(dropper) == PrimitiveCapabilityGateway.Footing.GROUND;
        MapleMap map = leader.getMap();
        Point spot = AgentKpqPassHandOver.dropSpot(leaderSpot, at, dropperOnGround ? landing : null,
                point -> ACTIONS.groundPoint(map, point), point -> ACTIONS.predictDropLanding(dropper, point),
                PASS_DROP_AT_FEET_PX, PASS_DROP_SPOT_REACH_PX, PASS_DROP_SPOT_STEP_PX);
        if (spot == null) {
            // Nowhere by him lands on his foothold: at his feet as before, which the patience recovery covers.
            return atHisFeet ? PassDropPlan.drop(leaderSpot, PassDropPlan.NO_SPOT, landing, leaderSpot)
                    : PassDropPlan.walk(leaderSpot);
        }
        // Where it stands for the spot, its own drop lands there: the search does not pick a spot it stands by
        // otherwise.
        if (AgentKpqPassHandOver.atFeet(at, spot, dropperOnGround, leaderOnGround, PASS_DROP_AT_FEET_PX)
                && AgentKpqPassHandOver.landsAt(landing, spot, PASS_DROP_AT_FEET_PX)) {
            return PassDropPlan.drop(spot, PassDropPlan.MOVED, landing, leaderSpot);
        }
        return PassDropPlan.walk(spot);
    }

    /**
     * Drops from the leader's spot and from either edge of his feet (PASS_DROP_AT_FEET_PX either way) land at his
     * feet, or the map cannot tell where a drop lands at all: either way a deliverer on its way walks onto his spot
     * as before, after three previews and no ground reads.
     */
    private static boolean dropsLandAroundHisSpot(Character dropper, Point leaderSpot) {
        Point fromHisSpot = ACTIONS.predictDropLanding(dropper, leaderSpot);
        if (fromHisSpot == null) return true;
        if (!AgentKpqPassHandOver.landsAt(fromHisSpot, leaderSpot, PASS_DROP_AT_FEET_PX)) return false;
        for (int side = -1; side <= 1; side += 2) {
            Point edge = new Point(leaderSpot.x + side * PASS_DROP_AT_FEET_PX, leaderSpot.y);
            if (!AgentKpqPassHandOver.landsAt(ACTIONS.predictDropLanding(dropper, edge), leaderSpot,
                    PASS_DROP_AT_FEET_PX)) {
                return false;
            }
        }
        return true;
    }

    private static void dropStageFivePasses(AgentKpqSession session, AgentKpqMemberState member, Character agent,
                                            AgentRuntimeEntry entry, Character leader, int held,
                                            AgentKpqPassHandOver.DropReason reason, PassDropPlan plan,
                                            long nowMs) {
        ACTIONS.stop(entry);
        if (!AgentScriptItemActionService.dropItem(entry, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) held)
                || !passDropConfirmed(held, agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false))) {
            return;
        }
        member.setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
        session.markProgress(nowMs);
        recordStageFivePassDrops(session, leader,
                logPassDrop(session, 5, agent, leader, reason, plan == null ? null : plan.spot(), held, nowMs),
                reason, nowMs);
        AgentKpqPassHandOver.PassPlace place = plan == null ? null : plan.place();
        if (reason == AgentKpqPassHandOver.DropReason.BESIDE) session.notePassPlace(5, place);
        switch (reason) {
            case OVERDUE -> narrate(session, agent, "stage5-passes-dropped-" + agent.getId(),
                    leader.getName() + ", can't get to you, i dropped the passes right here by me.");
            case AT_CLOTO -> narrate(session, agent, "stage5-passes-by-cloto-" + agent.getId(),
                    AgentKpqPassHandOver.leftByClotoLine(leader.getName()));
            case RECOLLECT -> narrate(session, agent, "stage5-recollected-at-feet",
                    AgentKpqPassHandOver.recollectedLine(leader.getName(), place));
            default -> {
                if (session.members().stream().noneMatch(
                        other -> other.role() == AgentKpqMemberState.Role.PASS_DELIVERER)) {
                    // The last one there says it, once: the passes expire where they lie. Where one stepped aside to
                    // drop, or nowhere by him landed on his foothold, it says where they lie.
                    narrate(session, agent, "stage5-passes-at-feet",
                            AgentKpqPassHandOver.stageFivePassesLine(leader.getName(), session.passPlace(5)));
                }
            }
        }
    }

    /**
     * A carrier about to go down (low on HP with no HP potion left while a stage-5 mob lives; live, Faro723 went down
     * so and its passes went with it) drops its passes while it still stands, says so, and carries none from then
     * on; the others pick them up as they sweep, or the re-collect does. One low on HP with nothing left to fight,
     * as by Cloto, is in no danger and keeps them. True when it dropped them this tick.
     */
    private static boolean leavePassesBeforeGoingDown(AgentKpqSession session, AgentKpqMemberState member,
                                                      Character agent, AgentRuntimeEntry entry, Character leader,
                                                      boolean mobsAlive, long nowMs) {
        if (member.stage5PassesLeftBehind() || !mobsAlive) return false;
        int held = agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        // The potion count reads its bag, so only a carrier already low on HP is looked at.
        boolean lowOnHp = AgentKpqPassHandOver.lowOnHp(
                agent.getHp(), agent.getCurrentMaxHp(), STAGE5_CARRIER_LOW_HP_FRACTION);
        if (held <= 0 || !lowOnHp
                || !AgentKpqPassHandOver.aboutToGoDown(lowOnHp, ACTIONS.hpPotionCount(agent), mobsAlive)) {
            return false;
        }
        ACTIONS.stop(entry);
        if (!AgentScriptItemActionService.dropItem(entry, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) held)
                || !passDropConfirmed(held, agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false))) {
            return false;
        }
        member.markStage5PassesLeftBehind();
        member.setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
        session.markProgress(nowMs);
        recordStageFivePassDrops(session, leader,
                logPassDrop(session, 5, agent, leader, AgentKpqPassHandOver.DropReason.DYING, null, held, nowMs),
                AgentKpqPassHandOver.DropReason.DYING, nowMs);
        narrate(session, agent, "stage5-leaving-passes-" + agent.getId(), AgentKpqPassHandOver.LEAVING_PASSES);
        return true;
    }

    /**
     * The floor passes no longer on their way to a companion run's leader: older than the patience, out of his
     * reach (NEAR_PX on each axis, a step for him), and nobody going for them. Passes left by Cloto (within NEAR_PX
     * of her) lie where he has to go to clear the stage, and where he was told they are: they wait for him there
     * until he is by Cloto himself, bounded by their expiry.
     */
    private static List<MapItem> stalePasses(
            AgentKpqSession session, Character leader, List<MapItem> passDrops, long nowMs) {
        if (passDrops.isEmpty()) return List.of();
        Point cloto = ACTIONS.npcPosition(leader, AgentKpqDefinition.CLOTO_NPC);
        boolean leaderByCloto = near(leader.getPosition(), cloto, NEAR_PX);
        List<MapItem> stale = new ArrayList<>();
        for (MapItem drop : passDrops) {
            boolean waitingByCloto = !leaderByCloto && near(drop.getPosition(), cloto, NEAR_PX);
            if (AgentKpqPassHandOver.stale(nowMs - drop.getDropTime(), inLeadersReach(leader, drop), waitingByCloto,
                    fetchedByCompanion(session, drop), STAGE5_FLOOR_PASS_PATIENCE_MS)) {
                stale.add(drop);
            }
        }
        return stale;
    }

    private static boolean inLeadersReach(Character leader, MapItem drop) {
        return leader.getMap() != null && near(leader.getPosition(), drop.getPosition(), NEAR_PX);
    }

    /**
     * The floor passes a companion run's leader has left lying in his reach longer than
     * STAGE5_INREACH_PASS_PATIENCE_MS with nobody going for them: they already lie by him, so nobody fetches them
     * again, and they no longer hold the recovery back. The patience runs from the pass's own drop time, or from when
     * it first lay in his reach if that was later: passes left by Cloto long before he comes, or a kill's old pass he
     * walks up to, are not made up while he walks up to them. Once he has left passes so, the ones the re-collect then
     * brings him get only STAGE5_PASS_NUDGE_MS: the whole patience again would put the clear back near their expiry.
     */
    private static List<MapItem> passesLeftAtFeet(
            AgentKpqSession session, Character leader, List<MapItem> passDrops, long nowMs) {
        if (passDrops.isEmpty()) return List.of();
        boolean leftBefore = session.stage5PassesLeftAtFeet();
        List<MapItem> left = new ArrayList<>();
        for (MapItem drop : passDrops) {
            if (!inLeadersReach(leader, drop)) continue;
            long inReachFromMs = Math.max(drop.getDropTime(),
                    session.observeStage5PassInReach(drop.getObjectId(), nowMs));
            boolean recollected = session.stage5PassDropReason(drop.getObjectId())
                    == AgentKpqPassHandOver.DropReason.RECOLLECT;
            long patienceMs = leftBefore && recollected ? STAGE5_PASS_NUDGE_MS : STAGE5_INREACH_PASS_PATIENCE_MS;
            if (AgentKpqPassHandOver.leftAtFeet(nowMs - inReachFromMs, true, fetchedByCompanion(session, drop),
                    patienceMs)) {
                left.add(drop);
            }
        }
        if (!left.isEmpty()) session.markStage5PassesLeftAtFeet();
        return left;
    }

    /**
     * Remembers the passes a companion just dropped on stage 5 by their map objects: why it dropped them, and, for
     * those that landed in the leader's reach, that they lie there from now.
     */
    private static void recordStageFivePassDrops(AgentKpqSession session, Character leader, List<MapItem> landed,
                                                 AgentKpqPassHandOver.DropReason reason, long nowMs) {
        for (MapItem drop : landed) {
            session.markStage5PassDrop(drop.getObjectId(), reason);
            if (leader != null && inLeadersReach(leader, drop)) {
                session.observeStage5PassInReach(drop.getObjectId(), nowMs);
            }
        }
    }

    /**
     * Passes a companion dropped on a companion run's human leader's spot that he has left lying STAGE5_PASS_NUDGE_MS
     * get him one reminder, from the companion that dropped the oldest, or the narrator once its Agent is no longer
     * loaded. Passes left by Cloto, where a companion stood, or before it went down were explained as they were
     * dropped.
     */
    private static void nudgeLeaderAboutStageFivePasses(
            AgentKpqSession session, Character leader, Character narrator, List<MapItem> passDrops, long nowMs) {
        List<MapItem> lying = new ArrayList<>();
        MapItem oldest = null;
        boolean byHisFeet = true;
        for (MapItem drop : passDrops) {
            // Only what a companion dropped on his spot, known by its map object: a pass a companion's kill left lying
            // is owned by it too, and a second human's lying pass must not have the run speak, least of all as him.
            if (!AgentKpqPassHandOver.droppedOnLeadersSpot(session.stage5PassDropReason(drop.getObjectId()))
                    || nowMs - drop.getDropTime() < STAGE5_PASS_NUDGE_MS) {
                continue;
            }
            lying.add(drop);
            byHisFeet &= inLeadersReach(leader, drop);
            if (oldest == null || drop.getDropTime() < oldest.getDropTime()) oldest = drop;
        }
        if (oldest == null) return;
        // As in stage 1, the stage logic runs only while every companion of the run stands in it.
        Character dropper = character(oldest.getOwnerId());
        Character speaker = dropper != null ? dropper : narrator != null ? narrator : firstStandingAgent(session);
        narrate(session, speaker, "stage5-pass-nudge",
                AgentKpqPassHandOver.stageFiveNudgeLine(leader.getName(), passQuantityOnGround(lying), byHisFeet));
    }

    private static boolean fetchedByCompanion(AgentKpqSession session, MapItem drop) {
        for (AgentKpqMemberState member : session.members()) {
            if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
            AgentRuntimeEntry memberEntry = entry(member.characterId());
            if (memberEntry != null && AgentGrindLootStateRuntime.grindLootTarget(memberEntry) == drop) return true;
        }
        return false;
    }

    /**
     * Before the inventory recovery makes up passes left lying, the companion nearest them goes back for them once,
     * as a player would, and brings them to the leader. True when this started that one re-collect.
     */
    private static boolean beginStageFiveRecollect(
            AgentKpqSession session, Character leader, List<MapItem> stalePasses, long nowMs) {
        AgentKpqMemberState recollector = null;
        Character recollectorCharacter = null;
        double nearest = Double.MAX_VALUE;
        for (AgentKpqMemberState member : session.members()) {
            Character agent = character(member.characterId());
            if (member.memberType() != AgentKpqMemberState.MemberType.AGENT || member.stage5PassesLeftBehind()
                    || member.characterId() == session.eventLeaderId() || agent == null || agent.getHp() <= 0
                    || agent.getMapId() != AgentKpqDefinition.STAGE_5_MAP || agent.getPosition() == null) {
                continue;
            }
            for (MapItem drop : stalePasses) {
                double distance = drop.getPosition() == null ? Double.MAX_VALUE / 2
                        : drop.getPosition().distanceSq(agent.getPosition());
                if (distance < nearest) {
                    nearest = distance;
                    recollector = member;
                    recollectorCharacter = agent;
                }
            }
        }
        if (recollector == null || !session.beginStage5Recollect(recollector.characterId(), nowMs)) return false;
        recollector.setRole(AgentKpqMemberState.Role.STAGE5_PASS_CARRIER);
        recollector.restartStage5Chase(nowMs);
        log.info("KPQ Stage 5 pass re-collect: session={} agent={} stalePasses={} positions={} leaderPos={}",
                session.sessionId(), recollectorCharacter.getName(), passQuantityOnGround(stalePasses),
                stalePasses.stream().map(MapItem::getPosition).toList(), leader.getPosition());
        narrate(session, recollectorCharacter, "stage5-recollect", AgentKpqPassHandOver.RECOLLECT_START);
        return true;
    }

    /** The re-collector goes for the nearest pass still out of the leader's reach; with none left it delivers. */
    private static void recollectPasses(AgentKpqSession session, AgentKpqMemberState member, Character agent,
                                        AgentRuntimeEntry entry, Character leader, List<MapItem> passDrops,
                                        long nowMs) {
        member.restartStage5Chase(nowMs);
        Point position = agent.getPosition();
        MapItem nearest = passDrops.stream()
                .filter(drop -> !inLeadersReach(leader, drop) && drop.getPosition() != null)
                .min(Comparator.comparingDouble(drop -> position == null ? 0d
                        : drop.getPosition().distanceSq(position)))
                .orElse(null);
        if (nearest == null) {
            session.finishStage5RecollectSweep();
            return;
        }
        // Entering grind mode clears a loot target, so the mode comes first.
        grindStageFive(session, entry, agent, nowMs);
        AgentGrindLootStateRuntime.setObjectiveLootTarget(entry, nearest);
    }

    /**
     * One line when the carriers set out: where the leader stands, whether each carrier can reach him, what it holds
     * and where it is. Live the hand-over that failed left nothing in the log but its 90 s timing.
     */
    private static void logStageFiveHandOverStart(
            AgentKpqSession session, Character leader, boolean sweepCapped, long nowMs) {
        List<String> carriers = new ArrayList<>();
        Set<Integer> leaderRegions = Set.of();
        for (AgentKpqMemberState member : session.members()) {
            if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null || agent.getMapId() != AgentKpqDefinition.STAGE_5_MAP) continue;
            if (leaderRegions.isEmpty() && leader.getPosition() != null) {
                leaderRegions = ACTIONS.groundRegionIds(entry, agent, List.of(leader.getPosition()));
            }
            carriers.add(agent.getName() + ":passes=" + agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)
                    + ":reach=" + ACTIONS.canReach(entry, agent, leader.getPosition())
                    + ":pos=" + pointText(agent.getPosition()));
        }
        log.info("KPQ Stage 5 hand-over start: session={} leaderMap={} leaderRegion={} leaderPos={} sweepCapped={} "
                        + "sinceReachedMs={} carriers={}",
                session.sessionId(), leader.getMapId(), leaderRegions, pointText(leader.getPosition()), sweepCapped,
                session.stage5ReachedAtMs() == 0L ? -1L : nowMs - session.stage5ReachedAtMs(), carriers);
    }

    /**
     * One line per pass drop in a companion run: why, how many, where it was dropped from (at the leader's feet, a
     * spot it moved to, or at his feet with no spot found; "-" for the other reasons), where the map's placement said
     * it would land, and where it landed against the leader. Returns the dropped passes as they lie on the map (see
     * droppedPasses).
     */
    private static List<MapItem> logPassDrop(AgentKpqSession session, int stage, Character agent, Character leader,
                                             AgentKpqPassHandOver.DropReason reason, String spot, int passes,
                                             long nowMs) {
        session.markPassDrop(nowMs);
        Point at = agent.getPosition();
        Point leaderAt = leader == null ? null : leader.getPosition();
        boolean measured = at != null && leaderAt != null;
        PrimitiveCapabilityGateway.Footing leaderFooting = leader == null ? null : ACTIONS.footing(leader);
        List<MapItem> landed = droppedPasses(agent, nowMs);
        log.info("KPQ pass drop: session={} stage={} agent={} reason={} passes={} dx={} dy={} sameFoothold={} "
                        + "leaderFooting={} spot={} predicted={} landed={}",
                session.sessionId(), stage, agent.getName(), reason.label(), passes,
                measured ? at.x - leaderAt.x : "?", measured ? at.y - leaderAt.y : "?",
                measured && leader.getMap() == agent.getMap()
                        && ACTIONS.footing(agent) == PrimitiveCapabilityGateway.Footing.GROUND
                        && leaderFooting == PrimitiveCapabilityGateway.Footing.GROUND
                        && Math.abs(at.y - leaderAt.y) <= PASS_DROP_AT_FEET_PX,
                leaderFooting, spot == null ? "-" : spot, pointText(ACTIONS.predictDropLanding(agent, at)),
                landedText(landed, leaderAt));
        return landed;
    }

    /**
     * The passes a companion just dropped, as they lie on its map: the map's player-dropped passes it owns, stamped
     * within PASS_DROP_LANDED_WINDOW_MS of now (the map stamps them with the server's slightly delayed clock). A pass a
     * kill of its own left lying is owned by it too, but is no player drop. One read of the map's drop list.
     */
    private static List<MapItem> droppedPasses(Character dropper, long nowMs) {
        List<MapItem> landed = new ArrayList<>();
        for (MapItem drop : livePassDrops(dropper.getMap())) {
            if (drop.isPlayerDrop() && drop.getOwnerId() == dropper.getId()
                    && Math.abs(nowMs - drop.getDropTime()) <= PASS_DROP_LANDED_WINDOW_MS) {
                landed.add(drop);
            }
        }
        return landed;
    }

    /**
     * Where the dropped passes lie against the leader, with their map objects and whether anyone may pick them up
     * yet, to set against what his client showed. Live (2026-10-02, kpq-a6f696f0) ten passes dropped beside the
     * leader were never seen near him, and nothing said where they had landed. Diagnostics only.
     */
    private static String landedText(List<MapItem> landed, Point leaderAt) {
        if (landed.isEmpty()) return "none";
        List<String> lines = new ArrayList<>();
        for (MapItem drop : landed) {
            Point lies = drop.getPosition();
            lines.add(pointText(lies) + (lies != null && leaderAt != null
                    ? ":dx=" + (lies.x - leaderAt.x) + ":dy=" + (lies.y - leaderAt.y) : "")
                    + ":passes=" + passQuantityOnGround(List.of(drop)) + ":oid=" + drop.getObjectId()
                    + ":ffa=" + drop.isFFADrop());
        }
        return lines.toString();
    }

    /**
     * When the leader's passes go up in a companion run: how many came, and how long after the last drop. Returns how
     * many came.
     */
    private static int observeLeaderPassPickup(
            AgentKpqSession session, int stage, Character leader, int held, long nowMs) {
        int gained = session.observeLeaderPasses(held);
        if (gained <= 0) return 0;
        long lastDropAtMs = session.lastPassDropAtMs();
        log.info("KPQ pass pickup: session={} stage={} leader={} delta={} held={} sinceDropMs={}",
                session.sessionId(), stage, leader.getName(), gained, held,
                lastDropAtMs == 0L ? -1L : nowMs - lastDropAtMs);
        return gained;
    }

    private static String pointText(Point point) {
        return point == null ? "?" : "(" + point.x + "," + point.y + ")";
    }

    /** What the companions alive on stage 5 still carry; a downed one's passes are as good as missing. */
    private static int carriedStageFivePasses(AgentKpqSession session) {
        int carried = 0;
        for (AgentKpqMemberState member : session.members()) {
            Character agent = character(member.characterId());
            if (agent == null || member.characterId() == session.eventLeaderId() || agent.getHp() <= 0
                    || agent.getMapId() != AgentKpqDefinition.STAGE_5_MAP) {
                continue;
            }
            carried += agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        }
        return carried;
    }

    /** Carriers never take a pass out of stage 5: what they still hold when Cloto clears it, they drop there. */
    private static void dropCarriedStageFivePasses(AgentKpqSession session, Character leader, long nowMs) {
        for (AgentKpqMemberState member : session.members()) {
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null || member.characterId() == session.eventLeaderId()
                    || agent.getMapId() != AgentKpqDefinition.STAGE_5_MAP) {
                continue;
            }
            int held = agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
            if (held > 0) {
                AgentScriptItemActionService.dropItem(entry, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM,
                        (short) held);
                if (stageFivePassCarriers(session)) {
                    logPassDrop(session, 5, agent, leader, AgentKpqPassHandOver.DropReason.CLEAR, null, held, nowMs);
                }
            }
        }
    }

    static boolean stageFiveReadyToReturn(int leaderPasses,
                                          int normalAlive,
                                          int bossAlive,
                                          boolean reviveGraceActive) {
        return leaderPasses >= 10 && normalAlive == 0 && bossAlive == 0 && !reviveGraceActive;
    }

    static long stageFiveCleanupDurationMs(long seed, long minimumMs, long maximumMs) {
        long minimum = Math.max(0L, Math.min(minimumMs, maximumMs));
        long maximum = Math.max(minimum, Math.max(minimumMs, maximumMs));
        if (minimum == maximum) return minimum;
        return minimum + Math.floorMod(seed * 31L + 17L, maximum - minimum + 1L);
    }

    static int stageFiveCleanupSeekRadius(MapleMap map) {
        Rectangle area = map == null ? null : map.getMapArea();
        if (area == null) return AgentLootCollectionPolicyConfig.preExitLootRadius();
        double diagonal = Math.hypot(Math.max(0, area.width), Math.max(0, area.height));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(
                AgentLootCollectionPolicyConfig.preExitLootRadius(), Math.ceil(diagonal)));
    }

    static boolean leaderHasDedicatedClotoRole(
            List<AgentKpqMemberState> participants, int leaderId) {
        return participants != null && participants.size() >= 3
                && participants.subList(0, 3).stream()
                .noneMatch(member -> member.characterId() == leaderId);
    }

    /**
     * Times each stage-5 death and King Slime's fight from whichever member is there first. Live (run 6) the
     * companions killed all ten while the leader still climbed out of stage 4; the boss had been dead a minute when
     * the STAGE_5 phase began, so its fight was never reported and the kill time read as the leader's.
     */
    private static void observeStageFiveBossCombat(
            AgentKpqSession session, int normalAlive, int bossAlive, long nowMs) {
        session.pace().observeStageFiveMobs(normalAlive, bossAlive, nowMs);
        boolean firstSighting = session.stage5ReachedAtMs() == 0L;
        int died = session.observeStage5Mobs(normalAlive + bossAlive, nowMs);
        if (firstSighting) {
            log.info("KPQ Stage 5 reached: session={} alive={} phase={} runMs={}",
                    session.sessionId(), normalAlive + bossAlive, session.phase(), nowMs - session.startedAtMs());
        } else if (died > 0) {
            log.info("KPQ Stage 5 mobs died: session={} died={} alive={} boss={} sinceReachedMs={} phase={}",
                    session.sessionId(), died, normalAlive + bossAlive, bossAlive,
                    nowMs - session.stage5ReachedAtMs(), session.phase());
        }
        if (normalAlive == 0 && bossAlive > 0 && session.beginStage5BossCombat(nowMs)) {
            for (AgentKpqMemberState member : session.members()) {
                if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
                AgentRuntimeEntry entry = entry(member.characterId());
                if (entry == null) continue;
                AgentFieldObservationState.Snapshot snapshot = entry.capabilityStates()
                        .require(AgentFieldObservationState.STATE_KEY).snapshot(nowMs);
                member.beginStage5BossCombat(snapshot.attacks(), snapshot.hitLines(),
                        snapshot.missLines(), snapshot.damage());
            }
            log.info("KPQ King Slime combat started: session={} agents={}",
                    session.sessionId(), session.members().stream()
                            .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT)
                            .map(AgentKpqMemberState::characterId).toList());
            return;
        }
        if (bossAlive > 0 || !session.claimStage5BossCombatReport()) return;
        reportStageFiveBossCombat(session, nowMs);
    }

    private static void reportStageFiveBossCombat(AgentKpqSession session, long nowMs) {
        long durationMs = Math.max(1L, nowMs - session.stage5BossCombatStartedAtMs());
        record Result(String name, AgentKpqMemberState.BossCombatDelta delta,
                      AgentFieldObservationState.Snapshot snapshot) { }
        List<Result> results = session.members().stream()
                .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT)
                .map(member -> {
                    Character agent = memberCharacter(member.characterId(), firstCharacter(session));
                    AgentRuntimeEntry entry = entry(member.characterId());
                    if (entry == null) return null;
                    AgentFieldObservationState.Snapshot snapshot = entry.capabilityStates()
                            .require(AgentFieldObservationState.STATE_KEY).snapshot(nowMs);
                    return new Result(agent == null ? "#" + member.characterId() : agent.getName(),
                            member.stage5BossCombatDelta(snapshot.attacks(), snapshot.hitLines(),
                                    snapshot.missLines(), snapshot.damage()), snapshot);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        long maximumDamage = results.stream().map(Result::delta)
                .mapToLong(AgentKpqMemberState.BossCombatDelta::damage).max().orElse(0L);
        for (Result result : results) {
            AgentKpqMemberState.BossCombatDelta delta = result.delta();
            String concern = bossCombatConcern(delta, maximumDamage);
            String message = "KPQ King Slime combat: session=" + session.sessionId()
                    + " agent=" + result.name() + " durationMs=" + durationMs
                    + " attacks=" + delta.attacks() + " hits=" + delta.hitLines()
                    + " misses=" + delta.missLines() + " damage=" + delta.damage()
                    + " dps=" + (delta.damage() * 1000L / durationMs)
                    + " posture=" + result.snapshot().posture()
                    + " target=" + result.snapshot().targetMobId()
                    + " assessment=" + concern;
            if ("ok".equals(concern)) log.info(message);
            else log.warn(message);
        }
        AgentPartyQuestEngagement engagement =
                AgentPartyQuestEngagementRegistry.forOperator(session.operatorId());
        if (engagement != null) {
            String summary = results.stream().map(result -> result.name() + ':'
                            + result.delta().damage() + '/' + result.delta().attacks())
                    .collect(java.util.stream.Collectors.joining(","));
            engagement.addDiagnostic("King Slime " + durationMs + "ms damage/attacks " + summary, nowMs);
        }
    }

    static String bossCombatConcern(
            AgentKpqMemberState.BossCombatDelta delta, long maximumPartyDamage) {
        if (delta == null || delta.attacks() == 0L) return "no-attacks";
        if (delta.damage() == 0L && delta.missLines() > 0L) return "all-misses";
        if (delta.damage() == 0L) return "no-damage";
        long attemptedLines = delta.hitLines() + delta.missLines();
        if (attemptedLines >= 5L && delta.missLines() * 5L >= attemptedLines * 3L) {
            return "accuracy-limited";
        }
        if (maximumPartyDamage > 0L && delta.damage() * 10L < maximumPartyDamage) {
            return "below-10%-of-party-maximum";
        }
        return "ok";
    }

    private static void claimRewards(AgentKpqSession session, long nowMs) {
        session.freezeRewardEligibility();
        Character anchor = firstCharacter(session);
        boolean shoesPending = anchor != null && handleSquishyShoes(session, anchor, nowMs);
        boolean allInBonus = true;
        for (AgentKpqMemberState member : session.members()) {
            Character agent = memberCharacter(member.characterId(), firstCharacter(session));
            if (agent == null || agent.getMap() == null) {
                session.forfeitReward(member.characterId());
                if (!member.rewardResolved()) allInBonus = false;
                continue;
            }
            if (session.companionAdopted() && doneOutsideEvent(agent.getMapId(), KPQ.event(agent) != null)) {
                // A member who died on the bonus map respawned outside the event and cannot come back for it.
                session.forfeitReward(member.characterId());
                continue;
            }
            if (agent.getMapId() == AgentKpqDefinition.STAGE_5_MAP) {
                allInBonus = false;
                if (member.memberType() == AgentKpqMemberState.MemberType.AGENT) {
                    if (agent.getHp() <= 0) continue;
                    if (shoesPending && member.characterId() == session.squishyShoesWinnerId()) {
                        AgentRuntimeEntry winnerEntry = entry(member.characterId());
                        if (winnerEntry != null) ACTIONS.stop(winnerEntry);
                        continue;
                    }
                    boolean rewardCapacity = agent.getInventory(InventoryType.USE).getNextFreeSlot() > -1
                            && agent.getInventory(InventoryType.ETC).getNextFreeSlot() > -1;
                    if (!rewardCapacity) {
                        member.observeBlocker("reward-inventory-full", nowMs);
                        if (nowMs - member.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS) {
                            log.warn("KPQ reward abandoned after inventory deadline: session={} member={} map={}",
                                    session.sessionId(), member.characterId(), agent.getMapId());
                            EventInstanceManager event = session.eventInstance();
                            if (event != null) event.exitPlayer(agent);
                            session.forfeitReward(member.characterId());
                        }
                        continue;
                    }
                    Point cloto = ACTIONS.npcPosition(agent, AgentKpqDefinition.CLOTO_NPC);
                    Point approach = npcApproachPoint(session, member, agent, cloto, 551);
                    if (walkToPoint(agent, approach, entry(member.characterId()), NPC_APPROACH_PX)) {
                        if (nowMs >= member.nextRetryAtMs()) {
                            member.setNextRetryAtMs(nowMs + INTERACTION_RETRY_MS);
                            KPQ.runNpc(agent, AgentKpqDefinition.CLOTO_NPC);
                        }
                    }
                } else {
                    coordinate(session, narrator(session), "human-claim-" + member.characterId(), voice(session,
                            agent.getName() + ", talk to cloto for your reward.",
                            agent.getName() + ", please talk to Cloto to claim your reward."));
                }
            } else if (agent.getMapId() == AgentKpqDefinition.BONUS_MAP) {
                member.clearBlocker();
                if (!member.rewardClaimed()) {
                    if (session.beginRewardClaim(member.characterId())) {
                        session.completeRewardClaim(member.characterId());
                    }
                    log.info("KPQ reward confirmed by bonus-map entry: session={} member={} name={}",
                            session.sessionId(), member.characterId(), agent.getName());
                }
            } else if (member.rewardResolved()
                    && agent.getMapId() == AgentKpqDefinition.RECRUIT_MAP) {
                // Bounded Agent recovery may deliberately leave without a reward.
            } else {
                allInBonus = false;
            }
        }
        if (allInBonus && session.allRewardsResolved()) {
            transition(session, AgentKpqSession.Phase.EXITING, nowMs);
        }
    }

    private static void exit(AgentKpqSession session, long nowMs) {
        boolean allOutside = true;
        Character leader = leader(session);
        Point entryNpc = leader == null || leader.getMap() == null
                || leader.getMapId() != AgentKpqDefinition.RECRUIT_MAP
                ? null : ACTIONS.npcPosition(leader, AgentKpqDefinition.ENTRY_NPC);
        boolean allGathered = true;
        for (AgentKpqMemberState member : session.members()) {
            Character agent = memberCharacter(member.characterId(), firstCharacter(session));
            if (agent == null || agent.getMap() == null) {
                log.warn("KPQ exit treats missing member as outside: session={} member={}",
                        session.sessionId(), member.characterId());
                continue;
            }
            if (session.companionAdopted() && doneOutsideEvent(agent.getMapId(), KPQ.event(agent) != null)) {
                continue;
            }
            if (agent.getMapId() == AgentKpqDefinition.BONUS_MAP
                    || agent.getMapId() == AgentKpqDefinition.EXIT_MAP) {
                allOutside = false;
                if (member.memberType() == AgentKpqMemberState.MemberType.AGENT) {
                    if (session.companionAdopted() && agent.getHp() <= 0) {
                        // Its respawn carries it outside, and then it is done.
                        allGathered = false;
                        continue;
                    }
                    if (agent.getMapId() == AgentKpqDefinition.BONUS_MAP
                            && bonusMapDwellActive(session.phaseEnteredAtMs(), nowMs,
                                    BONUS_MAP_DWELL_MS)) {
                        AgentRuntimeEntry agentEntry = entry(member.characterId());
                        if (agentEntry != null) ACTIONS.stop(agentEntry);
                        allGathered = false;
                        continue;
                    }
                    member.observeBlocker("exit-npc", nowMs);
                    if (nowMs >= member.nextRetryAtMs()) {
                        member.setNextRetryAtMs(nowMs + INTERACTION_RETRY_MS);
                        KPQ.runNpc(agent, AgentKpqDefinition.EXIT_NPC);
                    }
                    if (nowMs - member.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS) {
                        EventInstanceManager event = session.eventInstance();
                        if (event != null) event.exitPlayer(agent);
                    }
                } else {
                    coordinate(session, narrator(session), "human-exit-" + member.characterId(), voice(session,
                            agent.getName() + ", talk to nella when you're ready to head out.",
                            agent.getName() + ", please use the exit NPC when you're ready."));
                }
                allGathered = false;
            } else if (agent.getMapId() != AgentKpqDefinition.RECRUIT_MAP) {
                allOutside = false;
            } else if (member.memberType() == AgentKpqMemberState.MemberType.AGENT) {
                AgentRuntimeEntry agentEntry = entry(member.characterId());
                Point target = npcApproachPoint(session, member, agent, entryNpc, 601);
                if (!walkToPoint(agent, target, agentEntry, NPC_APPROACH_PX)) {
                    allGathered = false;
                }
            }
        }
        if (!allOutside || !allGathered) return;
        stopAll(session);
        if (!session.companionAdopted()) {
            coordinate(session, narrator(session), "wait-outside",
                    "Party is back in Kerning and waiting for the next command.");
        }
        AgentKpqTerminationService.complete(session, nowMs);
    }

    /**
     * In a companion run, a member outside the event and off the bonus map is done: it cannot come back in, and a
     * human standing on the exit map held the run for the whole phase timeout.
     */
    static boolean doneOutsideEvent(int mapId, boolean inEvent) {
        return !inEvent && mapId != AgentKpqDefinition.BONUS_MAP;
    }

    private static Character leader(AgentKpqSession session) {
        return eventLeader(session);
    }

    private static void enterNextPortal(AgentKpqSession session, int nextStage, long nowMs) {
        int expectedMap = AgentKpqDefinition.STAGE_1_MAP + nextStage - 1;
        if (nextStage == 5) {
            observeStageFiveArrivals(session, leader(session), nowMs);
            session.members().stream()
                    .map(member -> memberCharacter(member.characterId(), leader(session)))
                    .filter(character -> character != null && character.getMapId() == expectedMap)
                    .findFirst()
                    .ifPresent(there -> {
                        int bossAlive = ACTIONS.liveMonsterCount(there, STAGE_5_BOSS_MOBS);
                        observeStageFiveBossCombat(session,
                                ACTIONS.liveMonsterCount(there, STAGE_5_NORMAL_MOBS), bossAlive, nowMs);
                        // Carriers loot here already, behind the same King Slime delay as in the STAGE_5 phase.
                        if (stageFivePassCarriers(session)) {
                            session.beginStage5LootDelayIfBossDefeated(bossAlive, nowMs, KING_SLIME_LOOT_DELAY_MS);
                        }
                    });
        }
        boolean allThere = true;
        for (AgentKpqMemberState member : session.members()) {
            Character agent = memberCharacter(member.characterId(), leader(session));
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || agent.getMap() == null) {
                allThere = false;
                member.observeBlocker("portal-member-missing", nowMs);
                if (nowMs - member.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS) {
                    fail(session, "Party member " + member.characterId()
                            + " disappeared during the Stage " + nextStage + " transition",
                            new Parting(Parting.Kind.GONE, member.characterId(),
                                    memberName(leader(session), member.characterId())), nowMs);
                    return;
                }
                continue;
            }
            if (agent.getMapId() != expectedMap) {
                allThere = false;
                member.observeBlocker("portal-stage-" + nextStage, nowMs);
                if (member.memberType() == AgentKpqMemberState.MemberType.AGENT && entry != null) {
                    Point portal = ACTIONS.portalPosition(agent, AgentKpqDefinition.NEXT_PORTAL_ID);
                    if (portal != null && near(agent.getPosition(), portal, 50)) {
                        if (nowMs >= member.nextRetryAtMs()) {
                            member.setNextRetryAtMs(nowMs + INTERACTION_RETRY_MS);
                            KPQ.enterPortal(agent, AgentKpqDefinition.NEXT_PORTAL_ID);
                        }
                    } else if (portal != null) {
                        ACTIONS.navigate(entry, portal, true);
                    }
                    if (nowMs - member.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS
                            && recoverAgentPortal(session, member, agent, nextStage, nowMs)) {
                        continue;
                    }
                    if (nowMs - member.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS * 2L) {
                        fail(session, "Portal recovery exhausted for " + agent.getName()
                                + " entering Stage " + nextStage, nowMs);
                        return;
                    }
                } else if (nowMs - member.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS) {
                    coordinate(session, narrator(session),
                            "human-portal-" + nextStage + '-' + member.characterId(), voice(session,
                                    agent.getName() + ", come through the portal.",
                                    agent.getName() + ", please enter the next portal."));
                }
            } else if (member.memberType() == AgentKpqMemberState.MemberType.AGENT && entry != null) {
                member.clearBlocker();
                prePositionAfterEntry(session, member, agent, entry, nextStage, nowMs);
            }
        }
        if (allThere) transition(session, phaseForStage(nextStage), nowMs);
    }

    private static boolean recoverAgentPortal(AgentKpqSession session,
                                              AgentKpqMemberState member,
                                              Character agent,
                                              int nextStage,
                                              long nowMs) {
        EventInstanceManager event = session.eventInstance();
        int clearedStage = nextStage - 1;
        if (event == null || !"true".equals(event.getProperty(clearedStage + "stageclear"))) return false;
        MapleMap destination = event.getMapInstance(AgentKpqDefinition.STAGE_1_MAP + nextStage - 1);
        var spawnPortal = destination == null ? null : destination.getRandomPlayerSpawnpoint();
        if (destination == null || spawnPortal == null) return false;
        log.warn("KPQ portal recovery: session={} member={} stage={} attempts={} sourceMap={}",
                session.sessionId(), member.characterId(), nextStage,
                member.blockerAttempts(), agent.getMapId());
        AgentMapGatewayRuntime.map().changeMapNear(agent, destination, spawnPortal.getPosition());
        member.clearBlocker();
        session.markProgress(nowMs);
        return true;
    }

    private static List<AgentKpqMemberState> participants(AgentKpqSession session) {
        List<AgentKpqMemberState> participants = session.members().stream()
                .sorted(Comparator.comparingInt(AgentKpqMemberState::partyNumber))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (participants.size() >= 4) {
            participants.removeIf(member -> member.characterId() == session.eventLeaderId());
        }
        return AgentKpqPuzzleParticipantOrder.order(participants, session.seed());
    }

    static List<AgentKpqMemberState> assignFormation(List<AgentKpqMemberState> participants,
                                                     List<Integer> combination,
                                                     long seed,
                                                     int attemptId,
                                                     long nowMs) {
        List<AgentKpqMemberState> firstThree = participants.subList(0, 3);
        Set<Integer> retained = new LinkedHashSet<>();
        List<AgentKpqMemberState> movers = new ArrayList<>();
        for (AgentKpqMemberState member : firstThree) {
            if (combination.contains(member.assignedPosition())) retained.add(member.assignedPosition());
        }
        List<Integer> open = combination.stream().filter(position -> !retained.contains(position)).toList();
        int openIndex = 0;
        for (AgentKpqMemberState member : firstThree) {
            member.setRole(AgentKpqMemberState.Role.PUZZLE_PARTICIPANT);
            member.setStableSinceMs(0L);
            member.clearFidget();
            if (!retained.contains(member.assignedPosition())) {
                member.setAssignedPosition(open.get(openIndex++));
                movers.add(member);
                member.setActionNotBeforeMs(nowMs + FORMATION_MOVER_DELAY_PER_SLOT_MS * member.partyNumber()
                        + Math.floorMod(seed + attemptId * 31L + member.characterId(),
                        Math.max(1L, FORMATION_MOVER_DELAY_SPREAD_MS)));
            } else {
                member.setActionNotBeforeMs(nowMs);
            }
        }
        return List.copyOf(movers);
    }

    private static void prePositionInitialFormation(
            AgentKpqSession session,
            Character leader,
            Character narrator,
            AgentKpqDefinition.CombinationStage definition,
            List<AgentKpqMemberState> participants,
            List<Integer> initialCombination,
            long nowMs) {
        if (!initialCombination.equals(session.combination())) {
            int attemptId = session.nextAttemptId();
            List<AgentKpqMemberState> movers = assignFormation(
                    participants, initialCombination, session.seed(), attemptId, nowMs);
            session.setCombination(initialCombination);
            if (!movers.isEmpty()) {
                coordinate(session, narrator, "s" + definition.stageNumber() + "-initial-position-" + attemptId,
                        formationInstruction(movers, leader, definition.stageNumber(), session.companionAdopted()));
            }
        }
        for (AgentKpqMemberState participant : participants.subList(0, 3)) {
            if (participant.memberType() != AgentKpqMemberState.MemberType.AGENT
                    || nowMs < participant.actionNotBeforeMs()) {
                continue;
            }
            Character agent = memberCharacter(participant.characterId(), leader);
            AgentRuntimeEntry entry = entry(participant.characterId());
            if (agent != null && entry != null && agent.getMapId() == definition.mapId()
                    && !puzzlePositionReady(definition, participant.assignedPosition(),
                    agent.getPosition(), ACTIONS.grounded(agent))) {
                ACTIONS.navigate(entry, formationTarget(
                        definition, participant, session.seed()), true);
            }
        }
    }

    /**
     * Who goes where. A companion run says it as a player calls it ("Rurik, grab rope 3. Yara, take 1."): live, the
     * "Rurik -> 3; Yara -> 1." it used to hear on every attempt, some eighteen times a run, read like a status line.
     */
    static String formationInstruction(
            List<AgentKpqMemberState> movers, Character eventAnchor, int stage, boolean companionRun) {
        List<AgentKpqMemberState> speakingOrder = movers.stream()
                .sorted(Comparator
                        .comparing((AgentKpqMemberState member) ->
                                member.memberType() != AgentKpqMemberState.MemberType.HUMAN)
                        .thenComparingInt(AgentKpqMemberState::partyNumber))
                .toList();
        List<String> assignments = new ArrayList<>();
        for (AgentKpqMemberState member : speakingOrder) {
            Character character = memberCharacter(member.characterId(), eventAnchor);
            String name = character == null ? "Party member " + member.partyNumber() : character.getName();
            if (!companionRun) {
                assignments.add(name + " -> " + member.assignedPosition());
            } else {
                assignments.add(name + (assignments.isEmpty()
                        ? (stage == 2 ? ", grab " : ", get on ") + puzzleSpot(stage) + ' ' : ", take ")
                        + member.assignedPosition() + '.');
            }
        }
        return companionRun ? String.join(" ", assignments) : String.join("; ", assignments) + '.';
    }

    /** What a stage's puzzle positions are to the players standing on them. */
    static String puzzleSpot(int stage) {
        return switch (stage) {
            case 2 -> "rope";
            case 3 -> "platform";
            default -> "barrel";
        };
    }

    /**
     * A companion run hears its companions talk like players, never a status line; a stock or test run keeps the
     * plain lines it always had.
     */
    private static String voice(AgentKpqSession session, String companionLine, String stockLine) {
        return session.companionAdopted() ? companionLine : stockLine;
    }

    /** One line per phase with its time: a run's pace reads straight from the log instead of a 1 Hz sampler. */
    private static void transition(AgentKpqSession session, AgentKpqSession.Phase phase, long nowMs) {
        log.info("KPQ phase: session={} {}->{} phaseMs={} runMs={} earlyRejections={} companionRun={}",
                session.sessionId(), session.phase(), phase, nowMs - session.phaseEnteredAtMs(),
                nowMs - session.startedAtMs(), session.earlyPuzzleRejections(), session.companionAdopted());
        session.transition(phase, nowMs);
    }

    private static void advancePuzzleAttempt(AgentKpqSession session,
                                             Character leader,
                                             AgentKpqDefinition.CombinationStage definition,
                                             int attemptCount,
                                             int attempt,
                                             long nowMs) {
        int next = attempt + 1;
        session.setPuzzleCheckAtMs(0L);
        // The attempt id moves on only when the next formation is assigned; a click before then must not count.
        session.clearFormationReady();
        if (next >= attemptCount) {
            failExhaustedPuzzle(session, leader, definition, nowMs);
            return;
        }
        session.setAttemptIndex(next);
        session.markProgress(nowMs);
    }

    private static void failExhaustedPuzzle(AgentKpqSession session,
                                            Character leader,
                                            AgentKpqDefinition.CombinationStage definition,
                                            long nowMs) {
        List<String> positions = participants(session).stream().limit(3).map(member -> {
            Character character = memberCharacter(member.characterId(), leader);
            return member.characterId() + "->" + member.assignedPosition()
                    + "@" + (character == null ? "offline" : character.getPosition())
                    + ":grounded=" + (character != null && ACTIONS.grounded(character));
        }).toList();
        log.error("KPQ puzzle combinations exhausted: session={} stage={} answer={} attempt={} positions={}",
                session.sessionId(), definition.stageNumber(),
                KPQ.property(leader, definition.answerProperty()), session.attemptIndex(), positions);
        fail(session, "Stage " + definition.stageNumber()
                + " rejected every authored puzzle formation", nowMs);
    }

    static long puzzleCheckDelayMs(long seed, int stage, int attempt) {
        long variance = Math.max(0L, PUZZLE_CHECK_VARIANCE_MS);
        long offset = variance == 0L ? 0L
                : Math.floorMod(seed + stage * 997L + attempt * 131L, variance * 2L + 1L) - variance;
        return Math.max(0L, PUZZLE_CHECK_DELAY_MS + offset);
    }

    private static boolean blockedTooLong(AgentKpqSession session, String key, long nowMs) {
        session.observeBlocker(key, nowMs);
        return nowMs - session.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS;
    }

    private static void fail(AgentKpqSession session, String reason, long nowMs) {
        fail(session, reason, Parting.GENERIC, nowMs);
    }

    private static void fail(AgentKpqSession session, String reason, Parting parting, long nowMs) {
        int failureSpeakerId = failureSpeakerId(session);
        Character failureSpeaker = failureSpeakerId <= 0 ? null : character(failureSpeakerId);
        if (failureSpeaker != null) {
            narrate(session, failureSpeaker, "failed", failureLine(
                    session.companionAdopted(), reason, parting, failureSpeakerId, pastTheClear(session)));
        }
        log.error("KPQ session failure: session={} phase={} reason={} blocker={} blockerMs={} diagnostics={}",
                session.sessionId(), session.phase(), reason, session.blockerKey(),
                session.blockerSinceMs(), sessionDiagnostics(session));
        AgentKpqTerminationService.fail(session, reason, nowMs);
    }

    /**
     * A stock run says the engine's reason; a companion run hears its players' own words, and the log the reason. A
     * lost lobby is said casually by every run: that end is new with the background entry gate, and nothing stock
     * ever said it.
     */
    static String failureLine(
            boolean companionRun, String reason, Parting parting, int speakerId, boolean pastTheClear) {
        return companionRun || parting.kind() == Parting.Kind.LOBBY_TAKEN
                ? parting.line(speakerId, pastTheClear) : "KPQ stopped: " + reason + '.';
    }

    /** Stage 5 is cleared: the players have finished the run, whatever ends it on the bonus map or the way out. */
    private static boolean pastTheClear(AgentKpqSession session) {
        if (session.phase() == AgentKpqSession.Phase.CLAIMING_REWARDS
                || session.phase() == AgentKpqSession.Phase.EXITING) {
            return true;
        }
        EventInstanceManager event = session.eventInstance();
        return event != null && "true".equals(event.getProperty("5stageclear"));
    }

    /** How an Agent puts a run's early end to the players around it: casual, and never an id or a reason. */
    record Parting(Kind kind, int subjectId, String subject) {
        enum Kind { GENERIC, WENT_DOWN, LEFT, DEFEATED, GONE, OVER, TOO_LONG, LOBBY_TAKEN, REROLL }

        static final Parting GENERIC = new Parting(Kind.GENERIC, 0, null);

        static Parting of(Kind kind) {
            return new Parting(kind, 0, null);
        }

        static Parting of(Kind kind, Character subject) {
            return subject == null ? of(kind) : new Parting(kind, subject.getId(), subject.getName());
        }

        /**
         * The line as this speaker says it: about itself when it is the one who went down, and as a wrap-up once the
         * run is cleared.
         */
        String line(int speakerId, boolean pastTheClear) {
            boolean self = subjectId > 0 && subjectId == speakerId;
            boolean named = subject != null && !subject.isBlank();
            return pastTheClear ? wrapUp(self, named) : beforeTheClear(self, named);
        }

        private String beforeTheClear(boolean self, boolean named) {
            String generic = "we can't finish this one. let's head out.";
            return switch (kind) {
                case GENERIC -> generic;
                case WENT_DOWN -> self ? "sorry, i went down. we need three for this, so let's head out."
                        : named ? subject + " went down, and we need three for this. let's head out."
                        : "we're short now, and we need three for this. let's head out.";
                case LEFT -> named ? subject + " left, and we need three for this. let's head out."
                        : "we're short now, and we need three for this. let's head out.";
                case DEFEATED -> self ? "sorry, i went down. we can't finish this one, let's head out."
                        : named ? subject + " went down, so we can't finish this one. let's head out." : generic;
                case GONE -> named ? subject + " dropped out, so we can't finish this one. let's head out." : generic;
                case OVER -> "looks like this run's over. let's head back.";
                case TOO_LONG -> "we've been stuck too long. let's call it and head out.";
                case LOBBY_TAKEN -> "someone beat us to it. let's try again later.";
                case REROLL -> AgentKpqRerollPolicy.GO_AGAIN_LINE;
            };
        }

        /** After the clear nobody is told the run can't be finished, or that it needs three: it is done. */
        private String wrapUp(boolean self, boolean named) {
            String wrapUp = "let's wrap it up and head back.";
            return switch (kind) {
                case GENERIC -> wrapUp;
                case WENT_DOWN, DEFEATED -> self ? "sorry, i went down. " + wrapUp
                        : named ? subject + " went down. " + wrapUp : wrapUp;
                case LEFT -> named ? subject + " left. " + wrapUp : wrapUp;
                case GONE -> named ? subject + " dropped out. " + wrapUp : wrapUp;
                case TOO_LONG -> "we've been at this a while. " + wrapUp;
                case OVER, LOBBY_TAKEN, REROLL -> beforeTheClear(self, named);
            };
        }
    }

    private static String sessionDiagnostics(AgentKpqSession session) {
        Character anchor = firstCharacter(session);
        List<String> members = session.members().stream().map(member -> {
            Character character = memberCharacter(member.characterId(), anchor);
            if (character == null) return member.characterId() + ":offline:" + member.blocker();
            int channel = AgentClientGatewayRuntime.clients().hasClient(character)
                    ? AgentClientGatewayRuntime.clients().channel(character) : -1;
            return character.getName() + '(' + member.characterId() + ")"
                    + ":type=" + member.memberType()
                    + ":channel=" + channel
                    + ":map=" + character.getMapId()
                    + ":pos=" + character.getPosition()
                    + ":hp=" + character.getHp()
                    + ":coupons=" + character.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)
                    + ":passes=" + character.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)
                    + ":shoes=" + character.getItemQuantity(AgentKpqDefinition.SQUISHY_SHOES, false)
                    + ":blocker=" + member.blocker();
        }).toList();
        EventInstanceManager event = session.eventInstance();
        return "event=" + (event == null ? "none" : event.getName()) + ",members=" + members;
    }

    private static void stopAll(AgentKpqSession session) {
        session.members().forEach(member -> {
            AgentRuntimeEntry entry = entry(member.characterId());
            if (entry != null) ACTIONS.stop(entry);
        });
    }

    private static boolean stationLeaderAtCloto(
            AgentKpqSession session, Character leader, int stage, long nowMs) {
        AgentRuntimeEntry leaderEntry = entry(leader.getId());
        AgentKpqMemberState leaderState = session.member(leader.getId());
        Point cloto = ACTIONS.npcPosition(leader, AgentKpqDefinition.CLOTO_NPC);
        if (leaderEntry == null || leaderState == null || cloto == null) {
            if (leaderState != null) {
                leaderState.observeBlocker("stage" + stage + "-cloto-anchor", nowMs);
            }
            return false;
        }
        Point approach = npcApproachPoint(session, leaderState, leader, cloto, 200 + stage);
        if (!walkToPoint(leader, approach, leaderEntry, NPC_APPROACH_PX)) {
            leaderState.observeBlocker("stage" + stage + "-cloto-anchor", nowMs);
            long blockedMs = nowMs - leaderState.blockerSinceMs();
            if (!leaderState.blockerRecoveryAttempted()
                    && blockedMs >= CLOTO_ANCHOR_REPLAN_MS) {
                leaderState.markBlockerRecoveryAttempted();
                ACTIONS.stop(leaderEntry);
                ACTIONS.refreshNavigation(leaderEntry, leader);
                log.info("KPQ Cloto route replanned: session={} stage={} leader={} blockedMs={}",
                        session.sessionId(), stage, leader.getId(), blockedMs);
            }
            if (blockedMs >= LOCAL_RECOVERY_TIMEOUT_MS) {
                fail(session, "Stage " + stage + " leader could not reach Cloto after route recovery", nowMs);
            }
            return false;
        }
        leaderState.clearBlocker();
        ACTIONS.stop(leaderEntry);
        return true;
    }

    private static boolean drainStageFiveLoot(
            AgentKpqSession session, Character eventAnchor, long nowMs) {
        long durationMs = stageFiveCleanupDurationMs(
                session.seed(), STAGE5_CLEANUP_MIN_MS, STAGE5_CLEANUP_MAX_MS);
        if (session.beginStage5Cleanup(nowMs, durationMs)) {
            clearStageFiveLootTargets(session);
            log.info("KPQ Stage 5 cleanup started: session={} deadlineMs={} durationMs={}",
                    session.sessionId(), session.stage5CleanupDeadlineMs(), durationMs);
        }
        if (!session.stage5CleanupActive(nowMs)) {
            clearStageFiveLootTargets(session);
            return false;
        }

        boolean eligibleTargetPending = false;
        Set<Integer> assignedDropIds = new LinkedHashSet<>();
        MapleMap stageFiveMap = eventAnchor == null ? null : eventAnchor.getMap();
        int maximumSeekRadius = stageFiveCleanupSeekRadius(stageFiveMap);
        for (AgentKpqMemberState member : session.members()) {
            if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
            AgentRuntimeEntry memberEntry = entry(member.characterId());
            Character agent = memberCharacter(member.characterId(), eventAnchor);
            if (memberEntry == null || agent == null
                    || agent.getMapId() != AgentKpqDefinition.STAGE_5_MAP) {
                continue;
            }
            // Entering grind mode clears an old loot target. Establish the no-combat
            // movement mode first, then assign this member a distinct cleanup drop.
            grindStageFive(session, memberEntry, agent, nowMs);
            AgentGrindLootTargetService.refreshPreExitLootTarget(
                    memberEntry,
                    agent,
                    true,
                    server.agents.runtime.AgentRuntimeConfig.cfg.LOOT_RADIUS,
                    maximumSeekRadius,
                    assignedDropIds);
            if (AgentGrindLootStateRuntime.hasGrindLootTarget(memberEntry)) {
                eligibleTargetPending = true;
                assignedDropIds.add(AgentGrindLootStateRuntime
                        .grindLootTarget(memberEntry).getObjectId());
            } else {
                ACTIONS.stop(memberEntry);
            }
        }
        boolean settlingPartyDrop = hasPendingStageFiveSettlingLoot(
                session, eventAnchor, nowMs, maximumSeekRadius);
        if (!eligibleTargetPending && !settlingPartyDrop) {
            clearStageFiveLootTargets(session);
        }
        return eligibleTargetPending || settlingPartyDrop;
    }

    private static boolean hasPendingStageFiveSettlingLoot(
            AgentKpqSession session,
            Character eventAnchor,
            long nowMs,
            int maximumSeekRadius) {
        MapleMap map = eventAnchor == null ? null : eventAnchor.getMap();
        if (map == null) return false;
        double seekRangeSq = (double) maximumSeekRadius * maximumSeekRadius;
        for (MapItem drop : map.getDroppedItems()) {
            if (!ordinaryStageFivePartyDrop(session, map, drop)) continue;
            for (AgentKpqMemberState member : session.members()) {
                if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
                AgentRuntimeEntry memberEntry = entry(member.characterId());
                Character agent = memberCharacter(member.characterId(), eventAnchor);
                if (memberEntry == null || agent == null || agent.getMap() != map
                        || drop.getPosition().distanceSq(agent.getPosition()) > seekRangeSq) {
                    continue;
                }
                if (AgentLootEligibility.isWaitingForTargetAge(
                        memberEntry, agent, map, drop, nowMs,
                        AgentLootEligibility.MIN_TARGET_LOOT_AGE_MS)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean ordinaryStageFivePartyDrop(
            AgentKpqSession session, MapleMap map, MapItem drop) {
        if (!AgentLootEligibility.isPresent(map, drop)
                || session.member(drop.getOwnerId()) == null) {
            return false;
        }
        int itemId = drop.getMeso() > 0 ? 0 : drop.getItemId();
        return itemId != AgentKpqDefinition.PASS_ITEM
                && itemId != AgentKpqDefinition.SQUISHY_SHOES;
    }

    private static void clearStageFiveLootTargets(AgentKpqSession session) {
        for (AgentKpqMemberState member : session.members()) {
            AgentRuntimeEntry memberEntry = entry(member.characterId());
            if (memberEntry != null) {
                AgentGrindLootStateRuntime.clearGrindLootTarget(memberEntry);
            }
        }
    }

    private static void stopStageFiveMembersExcept(AgentKpqSession session, int activeCharacterId) {
        for (AgentKpqMemberState member : session.members()) {
            if (member.characterId() == activeCharacterId) continue;
            AgentRuntimeEntry memberEntry = entry(member.characterId());
            if (memberEntry != null) ACTIONS.stop(memberEntry);
        }
    }

    /** True when the line was said now; each key is said once a session. */
    private static boolean narrate(AgentKpqSession session, Character speaker, String key, String message) {
        if (speaker == null || !session.narrateOnce(key)) return false;
        // Required KPQ narration is deliberately map-local. Cosmic's party-chat
        // broadcast excludes the sender and anyone observing outside the party,
        // which made successful Stage 1 target/progress reports look silent.
        AgentKpqDialogue.sayMapNow(speaker, message);
        return true;
    }

    private static boolean coordinate(
            AgentKpqSession session, Character speaker, String key, String message) {
        if (!memberCoordinationVisible(session)) return false;
        return narrate(session, speaker, key, message);
    }

    static boolean memberCoordinationVisible(AgentKpqSession session) {
        if (session == null) return false;
        AgentKpqMemberState leader = session.member(session.eventLeaderId());
        return leader == null
                || leader.memberType() == AgentKpqMemberState.MemberType.AGENT
                || session.memberCoordinationChatEnabled();
    }

    private static boolean allMembersOnMap(AgentKpqSession session, int mapId, Character eventAnchor) {
        return session.members().stream().map(member -> memberCharacter(member.characterId(), eventAnchor))
                .allMatch(character -> character != null && character.getMap() != null
                        && character.getMapId() == mapId);
    }

    private static boolean allMembersSameEvent(AgentKpqSession session, Character leader) {
        return session.members().stream()
                .map(member -> memberCharacter(member.characterId(), leader))
                .allMatch(character -> character != null && KPQ.sameEvent(leader, character));
    }

    private static Character character(int id) {
        AgentRuntimeEntry entry = entry(id);
        return entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
    }

    private static Character memberCharacter(int id, Character eventAnchor) {
        Character agent = character(id);
        if (agent != null) return agent;
        Character online = AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
        if (online != null) return online;
        if (eventAnchor == null) return null;
        return KPQ.eventMembers(eventAnchor).stream()
                .filter(member -> member.getId() == id).findFirst().orElse(null);
    }

    private static Character firstCharacter(AgentKpqSession session) {
        for (AgentKpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character != null) return character;
        }
        return null;
    }

    private static Character eventLeader(AgentKpqSession session) {
        return memberCharacter(session.eventLeaderId(), firstCharacter(session));
    }

    private static void bindKnownEvent(AgentKpqSession session, Character eventAnchor) {
        EventInstanceManager event = session.eventInstance();
        if (event == null) {
            for (AgentKpqMemberState member : session.members()) {
                Character character = memberCharacter(member.characterId(), eventAnchor);
                event = character == null ? null : KPQ.event(character);
                if (event != null) {
                    session.bindEventInstance(event);
                    break;
                }
            }
        }
    }

    private static boolean reconcileLiveParty(
            AgentKpqSession session, Character eventAnchor, long nowMs) {
        if (!insideEventPhase(session.phase()) || session.eventInstance() == null) return true;
        Character partyAnchor = null;
        server.agents.integration.AgentPartySnapshot party = null;
        for (AgentKpqMemberState member : session.members()) {
            Character character = memberCharacter(member.characterId(), eventAnchor);
            var candidate = character == null ? null : AgentPartyGatewayRuntime.party().snapshot(character);
            if (candidate != null) {
                partyAnchor = character;
                party = candidate;
                break;
            }
        }
        if (partyAnchor == null || party == null) {
            fail(session, "The KPQ party was disbanded; no rewards were awarded", Parting.of(Parting.Kind.OVER), nowMs);
            return false;
        }
        Set<Integer> livePartyIds = party.members().stream()
                .filter(java.util.Objects::nonNull)
                .map(server.agents.integration.AgentPartyMemberSnapshot::id)
                .collect(java.util.stream.Collectors.toSet());
        List<AgentKpqMemberState> departed = session.members().stream()
                .filter(member -> !livePartyIds.contains(member.characterId()))
                .toList();
        EventInstanceManager event = session.eventInstance();
        AgentPartyQuestEngagement engagement =
                AgentPartyQuestEngagementRegistry.forOperator(session.operatorId());
        Parting left = Parting.of(Parting.Kind.LEFT);
        for (AgentKpqMemberState member : departed) {
            Character character = memberCharacter(member.characterId(), eventAnchor);
            // Event-exclusive passes leave with a member the stock respawn took out: say how many, and what the
            // others still carry. Live the line said neither, and the passes lost could only be guessed.
            log.warn("KPQ member left the live party and was ejected: session={} member={} type={} "
                            + "phase={} remainingBefore={} passesNow={} passesLastSeen={} carriedPasses={}",
                    session.sessionId(), member.characterId(), member.memberType(),
                    session.phase(), session.memberCount(),
                    character == null ? -1 : character.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false),
                    member.stage5PassesSeen(), carriedPassesByMember(session, eventAnchor, member.characterId()));
            if (session.phase() == AgentKpqSession.Phase.STAGE_5) {
                session.addStage5PassesLostWithMember(member.stage5PassesSeen());
            }
            if (left.subject() == null && character != null) left = Parting.of(Parting.Kind.LEFT, character);
            ejectMember(session, member, character, event, engagement, nowMs);
        }
        if (session.memberCount() < 3) {
            fail(session, "The KPQ party fell below three members; no rewards were awarded", left, nowMs);
            return false;
        }
        // Checked before the leadership reconcile, which would fail the humans for having no Agent to execute.
        if (session.companionAdopted() && session.members().stream()
                .noneMatch(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT)) {
            AgentKpqTerminationService.detach(session, "every companion tapped out; the humans carry on", nowMs);
            return false;
        }
        int liveLeaderId = party.members().stream()
                .filter(java.util.Objects::nonNull)
                .filter(server.agents.integration.AgentPartyMemberSnapshot::leader)
                .mapToInt(server.agents.integration.AgentPartyMemberSnapshot::id)
                .findFirst().orElse(0);
        if (liveLeaderId <= 0 || session.member(liveLeaderId) == null) {
            fail(session, "The live KPQ party leader is not an event member; no rewards were awarded", nowMs);
            return false;
        }
        int executionAgentId = session.member(session.executionAgentId()) != null
                ? session.executionAgentId()
                : session.members().stream()
                .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT)
                .mapToInt(AgentKpqMemberState::characterId).findFirst().orElse(0);
        if (liveLeaderId != session.eventLeaderId()
                || executionAgentId != session.executionAgentId()) {
            if (executionAgentId <= 0) {
                fail(session, "No Agent execution owner remained after the KPQ leader changed", nowMs);
                return false;
            }
            Character newLeader = memberCharacter(liveLeaderId, partyAnchor);
            session.setLeadership(liveLeaderId, executionAgentId);
            if (newLeader != null) event.setLeader(newLeader);
            session.markProgress(nowMs);
            log.info("KPQ leadership reconciled to live party: session={} leader={} executionAgent={} members={}",
                    session.sessionId(), liveLeaderId, executionAgentId, session.memberCount());
            return false;
        }
        return true;
    }

    /** The passes each other member but the leader holds now, by name. */
    private static Map<String, Integer> carriedPassesByMember(
            AgentKpqSession session, Character eventAnchor, int departedId) {
        Map<String, Integer> carried = new TreeMap<>();
        for (AgentKpqMemberState other : session.members()) {
            if (other.characterId() == departedId || other.characterId() == session.eventLeaderId()) continue;
            Character character = memberCharacter(other.characterId(), eventAnchor);
            if (character != null) {
                carried.put(character.getName(), character.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false));
            }
        }
        return carried;
    }

    /**
     * Takes one member out of the session and both indexes. The formation is reset for every session: a four-member
     * leader re-entered participants() with no assigned position, and the stage kept waiting on the departed slot.
     * Only a companion run also restarts the blocker clocks, which kept running while its downed member paused it.
     */
    private static void ejectMember(AgentKpqSession session,
                                    AgentKpqMemberState member,
                                    Character character,
                                    EventInstanceManager event,
                                    AgentPartyQuestEngagement engagement,
                                    long nowMs) {
        if (character != null && KPQ.event(character) == event) event.exitPlayer(character);
        AgentKpqFullRateSimulation.apply(member, entry(member.characterId()), false);
        session.removeMember(member.characterId());
        session.pace().markDeparture(nowMs);
        AgentKpqSessionRegistry.unindexMember(session, member.characterId());
        if (engagement != null && session.companionAdopted()) {
            // Still listed, a tapped-out companion unloaded later (its owner logged out) answered TownLife at the
            // run's end, and that alone kept a cleared run's companions idle in the 90 s post-run hold.
            AgentPartyQuestEngagementRegistry.removeAndUnindexMember(engagement, member.characterId(), nowMs);
        } else if (engagement != null) {
            AgentPartyQuestEngagementRegistry.unindexMember(engagement, member.characterId());
        }
        if (member.memberType() == AgentKpqMemberState.MemberType.AGENT) {
            AgentPartyQuestLifecycleRuntime.recoverDetachedMember(member.characterId(), nowMs);
        }
        session.resetFormation();
        if (session.companionAdopted()) session.restartBlockerClocks();
    }

    /** False pauses the stage logic for this tick, or means the run just ended. */
    private static boolean membersAlive(AgentKpqSession session, Character eventAnchor, long nowMs) {
        EventInstanceManager event = session.eventInstance();
        boolean leaderInBoundEvent = event != null && KPQ.event(eventAnchor) == event;
        boolean cleared = "true".equals(KPQ.property(eventAnchor, "5stageclear"));
        // Counted as the loop marks members, so two companions downed in one tick cannot both wait.
        int down = (int) session.members().stream().filter(AgentKpqMemberState::down).count();
        boolean waiting = false;
        for (AgentKpqMemberState member : session.members()) {
            Character character = memberCharacter(member.characterId(), eventAnchor);
            MemberVerdict verdict = memberVerdict(new MemberFacts(
                    session.companionAdopted(), session.phase(), member.memberType(),
                    member.characterId() == session.eventLeaderId(), character != null,
                    character == null ? 0 : character.getHp(),
                    character != null && KPQ.event(character) == event, leaderInBoundEvent, cleared,
                    member.downSinceMs(), member.tapOutRequested(),
                    session.memberCount() - 1 - (down - (member.down() ? 1 : 0)),
                    nowMs, LOCAL_RECOVERY_TIMEOUT_MS));
            switch (verdict) {
                case MISSING -> {
                    member.observeBlocker("member-missing", nowMs);
                    if (nowMs - member.blockerSinceMs() >= LOCAL_RECOVERY_TIMEOUT_MS) {
                        fail(session, "Party member " + member.characterId()
                                + " disconnected or left the channel", new Parting(Parting.Kind.GONE,
                                member.characterId(), memberName(eventAnchor, member.characterId())), nowMs);
                        return false;
                    }
                }
                case OK -> {
                    if ("member-missing".equals(member.blocker())) member.clearBlocker();
                }
                case DEFEATED -> {
                    fail(session, character.getName() + " was defeated during KPQ",
                            Parting.of(Parting.Kind.DEFEATED, character), nowMs);
                    return false;
                }
                case SHORT_HANDED -> {
                    fail(session, wentDown(character.getName()), Parting.of(Parting.Kind.WENT_DOWN, character), nowMs);
                    return false;
                }
                case DROP_HUMAN -> {
                    dropHuman(session, member, character, eventAnchor, nowMs);
                    return false;
                }
                case DOWN_WAIT -> {
                    if (!member.down()) {
                        down++;
                        session.pace().markDown(member.characterId(), nowMs);
                    }
                    member.markDown(nowMs);
                    waiting = true;
                }
                case REQUEST_TAP_OUT -> {
                    // The stock respawn carried it out of the event; the host takes it out of the party, and the
                    // departed-member path drops it on a later tick.
                    if (AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut(
                            "kpq", session.sessionId(), member.characterId(), character.getMapId(), nowMs))) {
                        member.markTapOutRequested();
                        session.pace().markTappedOut(member.characterId());
                        waiting = true;
                    } else if (!"true".equals(KPQ.property(eventAnchor, "5stageclear"))) {
                        fail(session, character.getName() + " was defeated during KPQ",
                                Parting.of(Parting.Kind.DEFEATED, character), nowMs);
                        return false;
                    }
                    // Else the leader cleared stage 5 on its own thread since this tick read the clear, and the host
                    // refuses a cleared run: after the clear nothing is asked of a downed member.
                }
                case REVIVED -> {
                    if (member.confirmRevived(nowMs)) {
                        member.clearDown();
                        down--;
                        session.resetFormation();
                        session.restartBlockerClocks();
                    } else {
                        waiting = true;
                    }
                }
                case IGNORE -> {
                }
            }
        }
        return !waiting;
    }

    enum MemberVerdict {
        OK, MISSING, DROP_HUMAN, DOWN_WAIT, REQUEST_TAP_OUT, REVIVED, IGNORE, DEFEATED, SHORT_HANDED
    }

    record MemberFacts(boolean adopted,
                       AgentKpqSession.Phase phase,
                       AgentKpqMemberState.MemberType type,
                       boolean eventLeader,
                       boolean present,
                       int hp,
                       boolean inBoundEvent,
                       boolean leaderInBoundEvent,
                       boolean cleared,
                       long downSinceMs,
                       boolean tapOutRequested,
                       int standingOthers,
                       long nowMs,
                       long timeoutMs) {
    }

    /**
     * How one member's state bears on the run. Outside a companion run, and for the event leader, any death fails
     * it as before. In a companion run a downed non-leader pauses the stage until the stock respawn carries it out
     * of the event: a companion is then tapped out by its host, and a human is dropped once it revives outside.
     * Fewer than three still standing ends the run, since KerningPQ would end the event at the respawn anyway.
     */
    static MemberVerdict memberVerdict(MemberFacts facts) {
        boolean human = facts.type() == AgentKpqMemberState.MemberType.HUMAN;
        if (!facts.present()) {
            if (!facts.adopted() || !human || facts.eventLeader()) return MemberVerdict.MISSING;
            // Only an uncleared stage needs the three. After the clear the rewards forfeit an absent member's share
            // and the exit counts it as outside, so its 45 s clock only failed the run under a leader on the bonus
            // map, which has no timer. Before entry a changed party fails preparation anyway.
            if (facts.cleared() || facts.phase() == AgentKpqSession.Phase.CLAIMING_REWARDS
                    || facts.phase() == AgentKpqSession.Phase.EXITING) {
                return MemberVerdict.IGNORE;
            }
            return insideEventPhase(facts.phase()) ? MemberVerdict.DROP_HUMAN : MemberVerdict.MISSING;
        }
        if (!facts.adopted() || facts.eventLeader()) {
            return facts.hp() <= 0 ? MemberVerdict.DEFEATED : MemberVerdict.OK;
        }
        // After the stage-5 clear nothing is asked of a downed member; it only must not hold the rewards up.
        if (facts.cleared() || facts.phase() == AgentKpqSession.Phase.CLAIMING_REWARDS
                || facts.phase() == AgentKpqSession.Phase.EXITING) {
            return facts.hp() <= 0 || facts.downSinceMs() > 0L ? MemberVerdict.IGNORE : MemberVerdict.OK;
        }
        if (!insideEventPhase(facts.phase())) {
            return facts.hp() <= 0 ? MemberVerdict.DEFEATED : MemberVerdict.OK;
        }
        // With the leader out of the bound event the event check fails the run with the real reason.
        if (!facts.leaderInBoundEvent()) return MemberVerdict.OK;
        boolean standing = facts.hp() > 0 && facts.inBoundEvent();
        if (facts.downSinceMs() == 0L) {
            if (standing) return MemberVerdict.OK;
            if (human && facts.hp() > 0) return MemberVerdict.DROP_HUMAN;
            // Alive outside the event is a death no tick saw: the respawn already carried it out.
            return facts.standingOthers() < 3 ? MemberVerdict.SHORT_HANDED : MemberVerdict.DOWN_WAIT;
        }
        if (facts.nowMs() - facts.downSinceMs() >= facts.timeoutMs()) return MemberVerdict.DEFEATED;
        if (standing) return MemberVerdict.REVIVED;
        if (facts.standingOthers() < 3) return MemberVerdict.SHORT_HANDED;
        if (facts.hp() > 0 && human) return MemberVerdict.DROP_HUMAN;
        if (facts.hp() > 0 && !facts.tapOutRequested()) return MemberVerdict.REQUEST_TAP_OUT;
        return MemberVerdict.DOWN_WAIT;
    }

    /** Mid-stage, a companion run carries on without a non-leader human who went offline or left the instance. */
    private static void dropHuman(AgentKpqSession session,
                                  AgentKpqMemberState member,
                                  Character character,
                                  Character eventAnchor,
                                  long nowMs) {
        String name = partyMemberName(eventAnchor, member.characterId());
        log.warn("KPQ human member dropped from a companion run: session={} member={} online={} phase={} "
                        + "remainingBefore={}",
                session.sessionId(), member.characterId(), character != null, session.phase(),
                session.memberCount());
        ejectMember(session, member, character, session.eventInstance(),
                AgentPartyQuestEngagementRegistry.forOperator(session.operatorId()), nowMs);
        if (session.memberCount() < 3) {
            fail(session, name + " left, and KPQ needs three of us", new Parting(Parting.Kind.LEFT,
                    member.characterId(), memberName(eventAnchor, member.characterId())), nowMs);
            return;
        }
        coordinate(session, narrator(session), "drop-" + member.characterId(),
                name + " had to go. we'll keep going with three.");
    }

    private static String partyMemberName(Character eventAnchor, int characterId) {
        String name = memberName(eventAnchor, characterId);
        return name == null ? "A party member" : name;
    }

    /** A member's name from the live party, which keeps offline members; null when it is not there. */
    private static String memberName(Character eventAnchor, int characterId) {
        server.agents.integration.AgentPartySnapshot party =
                eventAnchor == null ? null : AgentPartyGatewayRuntime.party().snapshot(eventAnchor);
        return party == null ? null : party.members().stream()
                .filter(java.util.Objects::nonNull)
                .filter(member -> member.id() == characterId)
                .map(server.agents.integration.AgentPartyMemberSnapshot::name)
                .findFirst().orElse(null);
    }

    static String wentDown(String name) {
        return name + " went down, and KPQ needs three of us";
    }

    /**
     * The watchdog's end of a companion run that its downed Agents leave short-handed. With no Agent standing no
     * tick of theirs could say it, so the humans were warped out in silence; the downed companion says it now.
     */
    static void failShortHanded(AgentKpqSession session, Character downed, long nowMs) {
        synchronized (session) {
            fail(session, wentDown(downed.getName()), Parting.of(Parting.Kind.WENT_DOWN, downed), nowMs);
        }
    }

    /** The watchdog found no live Agent to run the session: a companion run hears it end, a stock run ends as ever. */
    static void failWithoutLiveAgent(AgentKpqSession session, long nowMs) {
        String reason = "No live Agent execution participant remains";
        if (!session.companionAdopted()) {
            AgentKpqTerminationService.fail(session, reason, nowMs);
            return;
        }
        synchronized (session) {
            // The watchdog read the phase outside this monitor, and an Agent's tick may have ended the run since (a
            // cleared run must not hear it failed), or a release without the monitor may be under way.
            if (terminal(session.phase()) || session.terminationStarted()) return;
            fail(session, reason, nowMs);
        }
    }

    /**
     * True inside an uncleared stage when fewer than three members still stand in the bound event (not down, alive,
     * inside it). A downed companion's respawn would then end the event, since KerningPQ ends it at three.
     */
    static boolean shortHanded(AgentKpqSession session) {
        EventInstanceManager event = session.eventInstance();
        if (!insideEventPhase(session.phase()) || event == null
                || "true".equals(event.getProperty("5stageclear"))) {
            return false;
        }
        Character anchor = firstCharacter(session);
        return session.members().stream()
                .filter(member -> !member.down())
                .map(member -> memberCharacter(member.characterId(), anchor))
                .filter(character -> character != null && standsInEvent(session, character))
                .count() < 3;
    }

    private static Character narrator(AgentKpqSession session) {
        int speakerId = narrationSpeakerId(session);
        Character speaker = speakerId <= 0 ? null : character(speakerId);
        if (speaker == null || standsInEvent(session, speaker)) return speaker;
        Character standing = firstStandingAgent(session);
        return standing == null ? speaker : standing;
    }

    /** Narration is map chat: a downed speaker, or one already respawned outside, is heard by nobody in the run. */
    private static boolean standsInEvent(AgentKpqSession session, Character character) {
        return character.getHp() > 0 && KPQ.event(character) == session.eventInstance();
    }

    private static Character firstStandingAgent(AgentKpqSession session) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT)
                .sorted(Comparator.comparingInt(AgentKpqMemberState::partyNumber))
                .map(member -> character(member.characterId()))
                .filter(agent -> agent != null && standsInEvent(session, agent))
                .findFirst().orElse(null);
    }

    static int narrationSpeakerId(AgentKpqSession session) {
        if (session == null) return 0;
        if (session.formationCallerId() > 0) return session.formationCallerId();
        return session.memberCoordinationChatEnabled() ? session.executionAgentId() : 0;
    }

    static int failureSpeakerId(AgentKpqSession session) {
        if (session == null) return 0;
        Character standing = firstStandingAgent(session);
        if (standing != null) return standing.getId();
        return session.members().stream()
                .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT)
                .min(Comparator.comparingInt(AgentKpqMemberState::partyNumber))
                .map(AgentKpqMemberState::characterId).orElse(0);
    }

    private static boolean isAgent(AgentKpqSession session, int characterId) {
        AgentKpqMemberState member = session.member(characterId);
        return member != null && member.memberType() == AgentKpqMemberState.MemberType.AGENT;
    }

    private static AgentRuntimeEntry entry(int id) {
        return AgentRuntimeRegistry.findByAgentCharacterId(id);
    }

    private static boolean near(Character first, Character second, int px) {
        return first != null && second != null && first.getMap() == second.getMap()
                && near(first.getPosition(), second.getPosition(), px);
    }

    private static boolean near(Point first, Point second, int px) {
        return first != null && second != null
                && Math.abs(first.x - second.x) <= px && Math.abs(first.y - second.y) <= px;
    }

    private static AgentKpqSession.Phase phaseForStage(int stage) {
        return switch (stage) {
            case 2 -> AgentKpqSession.Phase.STAGE_2;
            case 3 -> AgentKpqSession.Phase.STAGE_3;
            case 4 -> AgentKpqSession.Phase.STAGE_4;
            case 5 -> AgentKpqSession.Phase.STAGE_5;
            default -> throw new IllegalArgumentException("Invalid KPQ stage " + stage);
        };
    }

    private static boolean terminal(AgentKpqSession.Phase phase) {
        return phase == AgentKpqSession.Phase.COMPLETED
                || phase == AgentKpqSession.Phase.FAILED;
    }

    private static boolean insideEventPhase(AgentKpqSession.Phase phase) {
        return switch (phase) {
            case STAGE_1, STAGE_2, STAGE_3, STAGE_4, STAGE_5 -> true;
            default -> false;
        };
    }

    private static boolean walkToPoint(Character character, Point target, AgentRuntimeEntry entry, int radiusPx) {
        if (character == null || entry == null || target == null) {
            return false;
        }
        if (near(character.getPosition(), target, radiusPx)) {
            return true;
        }
        ACTIONS.navigate(entry, target, true);
        return false;
    }

    static Point npcApproachPoint(AgentKpqSession session,
                                  AgentKpqMemberState member,
                                  Character character,
                                  Point npcPoint,
                                  int purposeSalt) {
        if (session == null || member == null || character == null || npcPoint == null) return npcPoint;
        long mixed = session.seed() + member.characterId() * 131L
                + member.partyNumber() * 37L + purposeSalt * 977L;
        int leftOffset = 24 + Math.floorMod(mixed, 58);
        int x = npcPoint.x - leftOffset;
        // A minority of leader approaches may be just right of the NPC; members remain mostly left.
        if (member.characterId() == session.eventLeaderId() && Math.floorMod(mixed, 5L) == 0L) {
            x = npcPoint.x + 14 + Math.floorMod(mixed >>> 3, 20);
        }
        MapleMap map = character.getMap();
        Point grounded = ACTIONS.groundPoint(map, new Point(x, npcPoint.y));
        return grounded == null ? new Point(x, npcPoint.y) : grounded;
    }

    private static Set<Integer> stage1GrindTargets(Character agent) {
        Set<Integer> configured = ACTIONS.configuredMonsterSpawnIds(agent);
        return configured.isEmpty() ? STAGE_1_MOBS : configured;
    }

    /** What one stage-1 tick works out once for every member: the levers in force and where the live Ligators are. */
    private record StageOneTick(AgentKpqSession session, AgentKpqStageOneLevers levers, List<Point> liveLigators) {
    }

    /**
     * With an anchor, the member fights from that spot, and in a territory inside it, instead of map-wide. In a
     * companion run a ranged member shoots from its territory's firing spot (L4), and a member out of its cluster
     * walks back while a Ligator lives there (L1); a coupon the sweep gave it comes first.
     */
    private static void grindStageOne(AgentKpqMemberState member,
                                      AgentRuntimeEntry entry,
                                      Character agent,
                                      Point anchor,
                                      AgentKpqSession.HelperTerritory territory,
                                      StageOneTick tick,
                                      long nowMs) {
        if (AgentKpqStageOneThiefLandingRuntime.tick(member, entry, agent, nowMs)) return;
        Set<Integer> mobs = stage1GrindTargets(agent);
        // The regions of its own graph, where its combat looks up each Ligator's region.
        Set<Integer> regions = territory == null
                ? Set.of() : ACTIONS.groundRegionIds(entry, agent, territory.spawns());
        if (!regions.isEmpty()) {
            MapItem objective = AgentGrindLootStateRuntime.hasObjectiveLootTarget(entry)
                    ? AgentGrindLootStateRuntime.grindLootTarget(entry) : null;
            if (objective == null) {
                Point spot = tick.levers().rangedStandoff()
                        ? stageOneFiringSpot(tick.session(), member, entry, agent, territory) : null;
                if (spot != null) {
                    ACTIONS.grindFromAnchor(entry, spot, mobs, mobs);
                    return;
                }
                if (territory.cluster() && walkBackToCluster(entry, agent, territory, regions, tick.liveLigators())) {
                    return;
                }
            }
            ACTIONS.grindInTerritory(entry, mobs, new AgentMapRegionAssignment(
                    territory.assignmentId(), AgentKpqDefinition.STAGE_1_MAP,
                    regions.stream().sorted().map(String::valueOf).toList(), member.partyNumber(),
                    territory.spawns().size(), territory.expiresAtMs(),
                    territory.cluster() ? STAGE1_CLUSTER_RESPAWN_WAIT_MS : STAGE1_HELPER_TERRITORY_EMPTY_MS,
                    territory.minX(), territory.maxX(), true));
            // Leaving the firing spot restarts the grind, which lets go of the coupon the sweep just gave it.
            if (objective != null && !AgentGrindLootStateRuntime.hasObjectiveLootTarget(entry)) {
                AgentGrindLootStateRuntime.setObjectiveLootTarget(entry, objective);
            }
        } else if (anchor == null) {
            ACTIONS.grind(entry, mobs);
        } else {
            ACTIONS.grindFromAnchor(entry, anchor, mobs, mobs);
        }
    }

    /**
     * L4: where a bow, crossbow, claw or gun member fights from in its territory, worked out once per territory: a
     * spot STAGE1_RANGED_SPAWN_STANDOFF_PX beside a spawn on that spawn's platform, one holding at least
     * STAGE1_RANGED_SPOT_MIN_SPAWNS of the territory's spawns (anchored, it attacks only what comes into range). By
     * their damage per line, the 2026-10-01 ranged pair spent about 40-45% of their stage-1 attacks on point-blank
     * whacks, each followed by a retreat. None for anyone else, or while its graph cannot tell the platforms apart.
     */
    private static Point stageOneFiringSpot(AgentKpqSession session, AgentKpqMemberState member,
                                            AgentRuntimeEntry entry, Character agent,
                                            AgentKpqSession.HelperTerritory territory) {
        if (!stageOneFiresFromASpot(session, agent, ACTIONS.rangedWeapon(agent))) return null;
        List<Point> area = territory.spawns();
        if (!member.stage1FiringSpotKnownFor(area)) {
            Point spot = AgentKpqStageOneClusterPolicy.firingSpot(area, STAGE1_RANGED_SPAWN_STANDOFF_PX,
                    STAGE1_RANGED_SPOT_MIN_SPAWNS, candidate -> ACTIONS.groundPoint(agent.getMap(), candidate),
                    point -> platformOf(entry, agent, point));
            member.setStage1FiringSpot(area, spot);
            log.info("KPQ Stage 1 firing spot: session={} agent={} spot={} area={}", session.sessionId(),
                    agent.getName(), spot == null ? "none" : spot.x + "," + spot.y,
                    area.stream().map(point -> point.x + "," + point.y).toList());
        }
        return member.stage1FiringSpot();
    }

    /**
     * Whether a stage-1 member holds a standoff firing spot: a ranged weapon, and in a companion run something to shoot
     * (AgentAmmoService.dryShooter); a dry one grinds its territory like a melee member, and its cached spot waits for
     * its next projectiles. A stock run keeps the spot.
     */
    static boolean stageOneFiresFromASpot(AgentKpqSession session, Character agent, boolean rangedWeapon) {
        return stageOneFiresFromASpot(agent, rangedWeapon, session.companionAdopted());
    }

    /** {@link #stageOneFiresFromASpot(AgentKpqSession, Character, boolean)} with the run's adoption given. */
    static boolean stageOneFiresFromASpot(Character agent, boolean rangedWeapon, boolean companionRun) {
        return rangedWeapon && (!companionRun || !AgentAmmoService.dryShooter(agent));
    }

    /** The navigation region of this Agent's own graph under a ground point, or -1 while it cannot tell. */
    private static int platformOf(AgentRuntimeEntry entry, Character agent, Point point) {
        Set<Integer> regions = ACTIONS.groundRegionIds(entry, agent, List.of(point));
        return regions.isEmpty() ? -1 : regions.iterator().next();
    }

    /**
     * L1: a member knocked or dropped farther than STAGE1_CLUSTER_RETURN_PX from its cluster while a Ligator lives
     * there walks back to the cluster's nearest spawn; its combat, bound to the cluster, sees nothing that far off.
     * Nearer, as on its way between two of the cluster's platforms, its combat walks there itself and is not turned
     * around. With nothing alive at home it waits for the respawn where it is, and borrows once its territory lets
     * it; it is not called back from a borrow before something lives at home again.
     */
    private static boolean walkBackToCluster(AgentRuntimeEntry entry, Character agent,
                                             AgentKpqSession.HelperTerritory territory, Set<Integer> regions,
                                             List<Point> liveLigators) {
        Point position = agent.getPosition();
        if (liveLigators.isEmpty() || position == null || !ACTIONS.grounded(agent)) return false;
        Point home = territory.spawns().stream()
                .min(Comparator.comparingDouble((Point spawn) -> spawn.distanceSq(position))
                        .thenComparingInt(spawn -> spawn.x))
                .orElse(null);
        if (home == null || home.distance(position) <= STAGE1_CLUSTER_RETURN_PX) return false;
        if (Collections.disjoint(ACTIONS.groundRegionIds(entry, agent, liveLigators), regions)) return false;
        ACTIONS.navigate(entry, home, false);
        return true;
    }

    /**
     * L1: the cluster a collector camps, made once and again only when it expires, so the territory stays equal from
     * tick to tick; null before the clusters are shared out, or with none for it.
     */
    private static AgentKpqSession.HelperTerritory clusterTerritory(
            AgentKpqSession session, AgentKpqMemberState member, long nowMs) {
        if (member == null) return null;
        int index = session.stageOneClusterIndex(member.characterId());
        List<Point> spawns = session.stageOneClusterSpawns(index);
        if (spawns == null) return null;
        AgentKpqSession.HelperTerritory territory = member.stage1ClusterTerritory();
        if (territory == null || nowMs >= territory.expiresAtMs() || !territory.spawns().equals(spawns)) {
            territory = new AgentKpqSession.HelperTerritory(
                    "kpq-stage1-cluster:" + session.sessionId() + ':' + index + ':' + nowMs,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, spawns, nowMs + STAGE1_HELPER_TERRITORY_MS, true);
            member.setStage1ClusterTerritory(territory);
        }
        return territory;
    }

    /**
     * L1: once every companion has its coupon target, the map's Ligator spawns are split into platform clusters and
     * shared out by target and by what each companion's line does to a Ligator. Sampled in run C, about 45-50% of a
     * collector's time went on ropes and in the air, hopping between platforms of 1-3 spawns map-wide.
     */
    private static void assignStageOneClusters(AgentKpqSession session, Character leader, Point cloto) {
        if (session.stageOneClustersAssigned()) return;
        List<AgentKpqMemberState> companions = session.members().stream()
                .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT)
                .filter(member -> member.characterId() != session.eventLeaderId())
                .toList();
        if (companions.stream().anyMatch(member -> !member.questionRequested() || member.couponTarget() <= 0)) return;
        List<AgentKpqStageOneClusterPolicy.Collector> collectors = new ArrayList<>();
        Map<Integer, String> names = new TreeMap<>();
        for (AgentKpqMemberState member : companions) {
            Character agent = memberCharacter(member.characterId(), leader);
            AgentRuntimeEntry entry = entry(member.characterId());
            double lineDamage = agent == null || entry == null ? 0.0
                    : ACTIONS.expectedLineDamage(entry, agent, stage1GrindTargets(agent)).values().stream()
                    .mapToDouble(Double::doubleValue).max().orElse(0.0);
            collectors.add(new AgentKpqStageOneClusterPolicy.Collector(
                    member.characterId(), member.couponTarget(), lineDamage));
            names.put(member.characterId(), agent == null ? String.valueOf(member.characterId()) : agent.getName());
        }
        List<List<Point>> clusters = AgentKpqStageOneClusterPolicy.clusters(
                ACTIONS.monsterSpawnPositions(leader, stage1GrindTargets(leader)),
                STAGE1_CLUSTER_LINK_X_PX, STAGE1_CLUSTER_LINK_Y_PX, STAGE1_CLUSTER_MIN_SPAWNS);
        Map<Integer, Integer> clusterOf = AgentKpqStageOneClusterPolicy.assign(clusters, collectors, cloto);
        session.assignStageOneClusters(clusters, clusterOf);
        Map<String, Integer> assigned = new TreeMap<>();
        clusterOf.forEach((characterId, index) -> assigned.put(names.get(characterId), index));
        log.info("KPQ Stage 1 clusters: session={} clusters={} assigned={}", session.sessionId(),
                clusters.stream().map(cluster -> cluster.stream().map(spawn -> spawn.x + "," + spawn.y).toList())
                        .toList(), assigned);
    }

    /** L6: whether a live Ligator stands on the platform (navigation region) this collector fights on. */
    private static boolean platformBusy(AgentRuntimeEntry entry, Character collector, List<Point> liveLigators) {
        Point position = collector.getPosition();
        if (entry == null || position == null || liveLigators.isEmpty()) return false;
        Set<Integer> standing = ACTIONS.groundRegionIds(entry, collector, List.of(position));
        return !standing.isEmpty()
                && !Collections.disjoint(standing, ACTIONS.groundRegionIds(entry, collector, liveLigators));
    }

    /**
     * A human leader's stage-1 kills, as the coupons they drop: every Ligator drops one, owned by its killer. Counted
     * once per coupon while it lies on the floor; a coupon he picks up before a tick sees it is missed.
     */
    private static void observeLeaderKills(AgentKpqSession session, Character leader, List<MapItem> floorCoupons) {
        for (MapItem drop : floorCoupons) {
            if (!drop.isPlayerDrop() && drop.getOwnerId() == leader.getId()) {
                session.observeStageOneLeaderKill(drop.getObjectId());
            }
        }
    }

    /**
     * L2: Cloto gives a human leader no coupon question, so every coupon he picks up counts for nobody. While he holds
     * some and a collector is still short, the neediest collector asks him to drop them by it, once and at most once
     * more STAGE1_LEADER_COUPON_REPEAT_MS later; its next sweep takes coupons lying that close even mid-fight. He is
     * never asked to stop fighting.
     */
    private static void askLeaderForCoupons(AgentKpqSession session, Character leader, long nowMs) {
        if (leader.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false) <= 0) return;
        Character neediest = neediestStageOneCollector(session, leader);
        if (neediest == null) return;
        int asks = session.stageOneLeaderCouponAsks();
        if (asks == 0) {
            if (narrate(session, neediest, "stage1-leader-coupons", stageOneLeaderCouponAsk(leader.getName()))) {
                session.markStageOneLeaderCouponAsk(nowMs);
                session.markStageOneLeaderLine(nowMs);
            }
        } else if (asks == 1 && nowMs - session.stageOneLeaderCouponAskAtMs() >= STAGE1_LEADER_COUPON_REPEAT_MS) {
            if (narrate(session, neediest, "stage1-leader-coupons-again",
                    stageOneLeaderCouponAskAgain(leader.getName()))) {
                session.markStageOneLeaderCouponAsk(nowMs);
                session.markStageOneLeaderLine(nowMs);
            }
        }
    }

    static String stageOneLeaderCouponAsk(String leaderName) {
        return leaderName + ", if you're picking up coupons, drop them by me, i still need a bunch.";
    }

    static String stageOneLeaderCouponAskAgain(String leaderName) {
        return leaderName + ", still short on coupons over here, toss yours by me when you get a sec.";
    }

    /**
     * Round 2b: a heavy stage-1 draw in a companion run with a human leader. Once every collector has its coupon
     * number, and before any pass exists, the run decides once whether to offer a re-roll (AgentKpqRerollPolicy, one
     * "KPQ stage 1 draw" line): the companion with the highest draw says so in its own words, and the collectors carry
     * on meanwhile. The host shows the leader its window at the first lull (never mid-fight: the window holds his
     * input), while the question is out. His answer is only recorded where it came (AgentKpqSessionRegistry, his I/O
     * thread) and acted on here: "go again" ends the run through the stock exit path; "push through", or no answer
     * within REROLL_ANSWER_MS of the question (of the window, once it is on screen), carries on. Stock and all-Agent
     * runs never get here, nor any run while the policy is off.
     *
     * @return true when the run was let go
     */
    private static boolean stageOneReroll(AgentKpqSession session, Character leader, long nowMs) {
        if (!session.companionAdopted() || isAgent(session, leader.getId())) return false;
        AgentKpqRerollPolicy policy = session.rerollPolicy();
        if (!policy.enabled()) return false;
        if (session.rerollPending()) return awaitRerollAnswer(session, leader, policy, nowMs);
        if (session.rerollDecided()) return false;
        List<Integer> targets = new ArrayList<>();
        boolean everyTarget = true;
        boolean passExists = leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false) > 0
                || !livePassDrops(leader.getMap()).isEmpty();
        long stillNeeded = 0L;
        for (AgentKpqMemberState member : session.members()) {
            if (member.characterId() == session.eventLeaderId()) continue;
            if (member.passCreated()) passExists = true;
            Character character = memberCharacter(member.characterId(), leader);
            int target = member.couponTarget();
            if (target <= 0 && member.memberType() == AgentKpqMemberState.MemberType.HUMAN) {
                // A player's number is Cloto's alone: read where she keeps it (0 once his pass is made).
                int grid = character == null ? -1 : KPQ.playerGrid(character);
                if (grid == 0) passExists = true;
                target = AgentKpqDefinition.couponTarget(grid);
            }
            if (target > 0) {
                targets.add(target);
                int held = character == null ? 0
                        : Math.max(0, character.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false));
                stillNeeded += Math.max(0, target - held);
            } else {
                everyTarget = false;
            }
        }
        if (!everyTarget && !passExists) return false;
        session.markRerollDecided();
        logRerollRestart(session, leader, policy, nowMs);
        // The coupons the leader picked up are the party's too (L2 has him drop them by a collector).
        int remaining = (int) Math.max(0L,
                stillNeeded - Math.max(0, leader.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)));
        double[] grind = stageOneGrind(session);
        AgentKpqRerollPolicy.Decision decision = policy.decide(new AgentKpqRerollPolicy.Draw(true,
                session.rerollDeclined(), AgentKpqRerollStreaks.current(leader.getId(), nowMs, policy.streakResetMs()),
                passExists, targets, remaining, grind[0], grind[1]));
        Character asker = decision.proposed() ? rerollAsker(session, leader) : null;
        boolean proposed = asker != null;
        log.info("KPQ stage 1 draw: session={} targets={} total={} remaining={} rule={} savingMs={} proposed={}"
                        + " reason={}", session.sessionId(), targets, targets.stream().mapToInt(Integer::intValue).sum(),
                remaining, decision.rule().label(),
                Double.isFinite(decision.savingMs()) ? Math.round(decision.savingMs()) : "-",
                proposed, decision.proposed() && !proposed ? "nobody-to-ask" : decision.reason());
        if (proposed) askLeaderToReroll(session, leader, asker, decision, targets, policy, nowMs);
        return false;
    }

    /**
     * The question is out: the leader's recorded answer is acted on, or, with none yet, the companions carry on once it
     * has waited REROLL_ANSWER_MS (from the window, once that went on screen); meanwhile the window is offered at a lull.
     *
     * @return true when the run was let go
     */
    private static boolean awaitRerollAnswer(AgentKpqSession session, Character leader, AgentKpqRerollPolicy policy,
                                             long nowMs) {
        noteRerollWindowShown(session, nowMs);
        Boolean goAgain = AgentKpqSessionRegistry.takeRerollAnswer(session.sessionId(), session.rerollLeaderId());
        long waitedMs = nowMs - session.rerollAskedAtMs();
        if (Boolean.TRUE.equals(goAgain)) {
            log.info("KPQ stage 1 re-roll answer: session={} answer=go-again waitedMs={}", session.sessionId(),
                    waitedMs);
            reroll(session, nowMs);
            return true;
        }
        long answerFromMs = Math.max(session.rerollAskedAtMs(), session.rerollWindowShownAtMs());
        if (Boolean.FALSE.equals(goAgain) || nowMs - answerFromMs >= policy.answerMs()) {
            session.settleReroll();
            AgentKpqSessionRegistry.closeReroll(session.sessionId());
            log.info("KPQ stage 1 re-roll answer: session={} answer={} waitedMs={}", session.sessionId(),
                    goAgain == null ? "none" : "push-through", waitedMs);
            narrate(session, rerollSpeaker(session), "stage1-reroll-carry-on", AgentKpqRerollPolicy.GRIND_IT_OUT_LINE);
            return false;
        }
        offerRerollWindow(session, leader, policy, nowMs);
        return false;
    }

    /**
     * The party cleared stage 1 with the question still out (a human collector handed in meanwhile, say): it is over,
     * unanswered and unsaid, so a later "ok" or a late pick on the window is no answer.
     */
    private static void closeRerollAtStageClear(AgentKpqSession session, long nowMs) {
        if (!session.rerollPending()) return;
        session.settleReroll();
        AgentKpqSessionRegistry.closeReroll(session.sessionId());
        log.info("KPQ stage 1 re-roll answer: session={} answer=stage-cleared waitedMs={}", session.sessionId(),
                nowMs - session.rerollAskedAtMs());
    }

    /**
     * Offers the leader the companion's window, only at a lull (AgentKpqRerollPolicy.lull) and while the question is
     * out (so within its first REROLL_ANSWER_MS: without the window it settles then): once a tick until it is on
     * screen; never again once the host says it cannot show one (the question then stands in chat alone).
     */
    private static void offerRerollWindow(AgentKpqSession session, Character leader, AgentKpqRerollPolicy policy,
                                          long nowMs) {
        if (session.rerollWindowSettled()) return;
        boolean grounded = ACTIONS.footing(leader) == PrimitiveCapabilityGateway.Footing.GROUND;
        boolean fighting = ACTIONS.inCombatStance(leader);
        List<Point> ligators = grounded && !fighting
                ? ACTIONS.liveMonsterPositions(leader, stage1GrindTargets(leader)) : List.of();
        if (!policy.lull(leader.getPosition(), grounded, fighting, ligators)) return;
        boolean offered = AgentPartyQuestHandback.askLeaderToReroll(new AgentPartyQuestHandback.RerollAsk(
                AgentKpqPaceProfile.QUEST_KEY, session.sessionId(), session.rerollLeaderId(), session.rerollAskerId(),
                session.rerollRule(), session.rerollQuestion()));
        if (!offered) {
            session.markRerollWindowUnavailable();
            log.info("KPQ stage 1 re-roll window not shown: session={} leader={}; the question stands in chat",
                    session.sessionId(), leader.getName());
            return;
        }
        noteRerollWindowShown(session, nowMs); // a host may show it at once
    }

    /** The leader's I/O thread put the window on his screen: his answer is timed from this tick. */
    private static void noteRerollWindowShown(AgentKpqSession session, long nowMs) {
        if (session.rerollWindowShownAtMs() > 0L
                || !AgentKpqSessionRegistry.rerollWindowShown(session.sessionId(), session.rerollLeaderId())) {
            return;
        }
        session.markRerollWindowShown(nowMs);
        log.info("KPQ stage 1 re-roll window shown: session={} afterMs={}", session.sessionId(),
                nowMs - session.rerollAskedAtMs());
    }

    /**
     * What the leader's last re-roll cost, measured as his next run decides: from that run's release to this run's
     * stage-1 entry and to this draw (REROLL_RESTART_MS is assumed, never measured; these lines are its data).
     */
    private static void logRerollRestart(AgentKpqSession session, Character leader, AgentKpqRerollPolicy policy,
                                         long nowMs) {
        long rerolledAtMs = AgentKpqRerollStreaks.lastRerollAtMs(leader.getId(), nowMs, policy.streakResetMs());
        if (rerolledAtMs <= 0L) return;
        log.info("KPQ re-roll restart: session={} leader={} toStageOneMs={} toDrawMs={} assumedMs={}",
                session.sessionId(), leader.getName(), session.phaseEnteredAtMs() - rerolledAtMs, nowMs - rerolledAtMs,
                policy.restartMs());
    }

    /**
     * Stage 1's theory per kill for this party and a fresh draw's mean kills, from the warm pace profile, the members'
     * power read at stage-1 entry and the calibration the leader's lineup was priced with (its priors without one);
     * NaN for both while the profile is cold or no power was read, and the players' rule of thumb decides.
     */
    private static double[] stageOneGrind(AgentKpqSession session) {
        double[] cold = {Double.NaN, Double.NaN};
        AgentPqPaceProfile profile = AgentPqPaceProfiles.cached(AgentKpqPaceProfile.QUEST_KEY);
        List<AgentPqMemberPower> members = session.pace().snapshot().members();
        if (profile == null || members.isEmpty()) return cold;
        try {
            AgentPqPaceCalibration calibration = session.paceCalibration();
            if (calibration == null || calibration.epoch() != profile.epoch()) {
                calibration = AgentPqPaceCalibration.prior(profile);
            }
            for (AgentPqPaceSegment segment : profile.segments(members.size())) {
                if (segment instanceof AgentPqPaceSegment.Quota quota
                        && AgentKpqPaceProfile.S1_GRIND.equals(quota.key())) {
                    return new double[]{AgentPqPaceEstimator.theorySeconds(profile, members, calibration,
                            AgentKpqPaceProfile.S1_GRIND, 1.0d), quota.kills().mean()};
                }
            }
        } catch (RuntimeException failure) {
            log.debug("KPQ stage 1 grind unreadable: session={}", session.sessionId(), failure);
        }
        return cold;
    }

    /** The companion with the highest draw that stands in the run (the first of the party on a tie), or null. */
    private static Character rerollAsker(AgentKpqSession session, Character leader) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT
                        && member.characterId() != session.eventLeaderId() && member.couponTarget() > 0)
                .sorted(Comparator.comparingInt(AgentKpqMemberState::couponTarget).reversed()
                        .thenComparingInt(AgentKpqMemberState::partyNumber))
                .map(member -> memberCharacter(member.characterId(), leader))
                .filter(agent -> agent != null && standsInEvent(session, agent))
                .findFirst().orElse(null);
    }

    /** Who answers for the re-roll: the companion that asked while it stands in the run, else any that does. */
    private static Character rerollSpeaker(AgentKpqSession session) {
        Character asker = session.rerollAskerId() > 0 ? character(session.rerollAskerId()) : null;
        return asker != null && standsInEvent(session, asker) ? asker : firstStandingAgent(session);
    }

    /**
     * The question: recorded as out first (so no answer can come before it), then said once in the companion's own
     * words, then offered to the leader as its window (now if he is at a lull, else at the first lull). A bare yes or
     * no he types counts as his answer only when no other companion line asked him something within REROLL_ANSWER_MS
     * before; while the question is out, none does.
     */
    private static void askLeaderToReroll(AgentKpqSession session, Character leader, Character asker,
                                          AgentKpqRerollPolicy.Decision decision, List<Integer> targets,
                                          AgentKpqRerollPolicy policy, long nowMs) {
        String question = AgentKpqRerollPolicy.askLine(targets);
        long leaderLineAtMs = session.stageOneLeaderLineAtMs();
        boolean oneWordCounts = leaderLineAtMs <= 0L || nowMs - leaderLineAtMs >= policy.answerMs();
        AgentKpqSessionRegistry.askReroll(session.sessionId(), leader.getId(), nowMs, oneWordCounts);
        session.markRerollAsked(asker.getId(), leader.getId(), decision.rule().label(), question, nowMs);
        narrate(session, asker, "stage1-reroll-ask", question);
        offerRerollWindow(session, leader, policy, nowMs);
    }

    /**
     * The leader said go again: the asking companion says so, and the run ends through the stock exit path, as a
     * re-roll (AgentKpqTerminationService.reroll), never as a failure.
     */
    private static void reroll(AgentKpqSession session, long nowMs) {
        Character speaker = rerollSpeaker(session);
        if (speaker != null) {
            narrate(session, speaker, "rerolled", Parting.of(Parting.Kind.REROLL).line(speaker.getId(), false));
        }
        AgentKpqTerminationService.reroll(session, nowMs);
    }

    /** The human leader's stage 1, beside the companions' summaries: his kills, the coupons he kept, the asks. */
    private static void reportStageOneLeader(AgentKpqSession session, Character leader) {
        log.info("KPQ Stage 1 leader: session={} leader={} kills={} coupons={} asked={}", session.sessionId(),
                leader.getName(), session.stageOneLeaderKills(),
                leader.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false), session.stageOneLeaderCouponAsks());
    }

    private static void cancelStageOneLandingSafety(
            AgentKpqMemberState member, AgentRuntimeEntry entry, Character agent) {
        AgentKpqStageOneThiefLandingRuntime.cancel(member, entry, agent);
    }

    private static void clearStageOneLandingSafety(
            AgentKpqSession session, Character eventAnchor) {
        for (AgentKpqMemberState member : session.members()) {
            cancelStageOneLandingSafety(
                    member, entry(member.characterId()),
                    memberCharacter(member.characterId(), eventAnchor));
        }
    }

    private static List<MapItem> liveCouponDrops(MapleMap map) {
        if (map == null) return List.of();
        return map.getDroppedItems().stream()
                .filter(drop -> drop != null && !drop.isPickedUp()
                        && drop.getMeso() <= 0
                        && drop.getItemId() == AgentKpqDefinition.COUPON_ITEM)
                .toList();
    }

    private static List<MapItem> livePassDrops(MapleMap map) {
        if (map == null) return List.of();
        return map.getDroppedItems().stream()
                .filter(drop -> drop != null && !drop.isPickedUp()
                        && drop.getMeso() <= 0
                        && drop.getItemId() == AgentKpqDefinition.PASS_ITEM)
                .toList();
    }

    private static List<MapItem> liveSquishyShoesDrops(MapleMap map) {
        if (map == null) return List.of();
        return map.getDroppedItems().stream()
                .filter(drop -> drop != null && !drop.isPickedUp()
                        && drop.getMeso() <= 0
                        && drop.getItemId() == AgentKpqDefinition.SQUISHY_SHOES)
                .toList();
    }

    private static boolean handleSquishyShoes(
            AgentKpqSession session, Character eventAnchor, long nowMs) {
        if (session.squishyShoesResolved()) return false;
        Character stageFiveMember = session.members().stream()
                .map(member -> memberCharacter(member.characterId(), eventAnchor))
                .filter(java.util.Objects::nonNull)
                .filter(character -> character.getMapId() == AgentKpqDefinition.STAGE_5_MAP)
                .findFirst().orElse(null);
        MapleMap stageFiveMap = stageFiveMember == null ? null : stageFiveMember.getMap();
        List<MapItem> shoes = liveSquishyShoesDrops(stageFiveMap);
        if (shoes.isEmpty()) {
            if (session.squishyShoesSeenAtMs() > 0L) session.markSquishyShoesResolved();
            return false;
        }
        if (session.squishyShoesSeenAtMs() == 0L) session.setSquishyShoesSeenAtMs(nowMs);
        Character winner = memberCharacter(session.squishyShoesWinnerId(), eventAnchor);
        if (winner == null || winner.getMapId() != AgentKpqDefinition.STAGE_5_MAP) {
            int winnerId = chooseSquishyShoesWinner(session, eventAnchor);
            session.setSquishyShoesWinnerId(winnerId);
            winner = memberCharacter(winnerId, eventAnchor);
        }
        if (winner == null) return true;
        if (winner.getItemQuantity(AgentKpqDefinition.SQUISHY_SHOES, false) > 0) {
            session.markSquishyShoesResolved();
            return false;
        }
        if (lootDelayActive(session.squishyShoesSeenAtMs(), nowMs, KING_SLIME_LOOT_DELAY_MS)) {
            return true;
        }
        // A carrier run's leader waits up by Cloto, 2,300 px above where King Slime dropped the shoes: a window for a
        // human nowhere near them only holds the winner back.
        boolean humanPriority = hasHumanPartyMember(session)
                && (!stageFivePassCarriers(session) || humanNearSquishyShoes(session, eventAnchor, shoes));
        if (squishyShoesHumanWindowActive(humanPriority,
                session.squishyShoesSeenAtMs(), nowMs, SQUISHY_SHOES_HUMAN_PRIORITY_MS)) {
            return true;
        }
        narrate(session, winner, "squishy-shoes-pickup-" + winner.getId(), voice(session,
                "i'll grab the squishy shoes.", "I'll pick up the Squishy Shoes."));
        KPQ.lootNearby(winner, Set.of(AgentKpqDefinition.SQUISHY_SHOES));
        if (winner.getItemQuantity(AgentKpqDefinition.SQUISHY_SHOES, false) > 0
                || liveSquishyShoesDrops(stageFiveMap).isEmpty()) {
            AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry(winner.getId()));
            session.markSquishyShoesResolved();
            return false;
        }
        AgentRuntimeEntry winnerEntry = entry(winner.getId());
        if (winnerEntry != null) {
            Point winnerPosition = winner.getPosition();
            MapItem closest = liveSquishyShoesDrops(stageFiveMap).stream()
                    .min(Comparator.comparingDouble(drop ->
                            drop.getPosition().distanceSq(winnerPosition)))
                    .orElse(null);
            grindStageFive(session, winnerEntry, winner, nowMs);
            AgentGrindLootStateRuntime.setObjectiveLootTarget(winnerEntry, closest);
        }
        long exclusiveWindowMs = humanPriority
                ? Math.max(KING_SLIME_LOOT_DELAY_MS, SQUISHY_SHOES_HUMAN_PRIORITY_MS)
                : KING_SLIME_LOOT_DELAY_MS;
        if (nowMs - session.squishyShoesSeenAtMs()
                >= exclusiveWindowMs + LOCAL_RECOVERY_TIMEOUT_MS) {
            log.warn("KPQ Squishy Shoes collection abandoned after bounded priority window: "
                            + "session={} winner={} map={} floorDrops={}",
                    session.sessionId(), winner.getId(), winner.getMapId(),
                    liveSquishyShoesDrops(stageFiveMap).size());
            AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry(winner.getId()));
            session.markSquishyShoesResolved();
            return false;
        }
        return true;
    }

    private static boolean humanNearSquishyShoes(
            AgentKpqSession session, Character eventAnchor, List<MapItem> shoes) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.HUMAN)
                .map(member -> memberCharacter(member.characterId(), eventAnchor))
                .filter(human -> human != null && human.getPosition() != null
                        && human.getMapId() == AgentKpqDefinition.STAGE_5_MAP)
                .anyMatch(human -> shoes.stream().anyMatch(drop ->
                        drop.getPosition().distance(human.getPosition()) <= SQUISHY_SHOES_HUMAN_NEAR_PX));
    }

    static int chooseSquishyShoesWinner(AgentKpqSession session, Character eventAnchor) {
        List<AgentKpqMemberState> candidates = session.members().stream()
                .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT)
                .filter(member -> {
                    Character character = memberCharacter(member.characterId(), eventAnchor);
                    return character != null && character.getMapId() == AgentKpqDefinition.STAGE_5_MAP;
                })
                .sorted(Comparator.comparingInt(AgentKpqMemberState::partyNumber))
                .toList();
        if (candidates.size() > 1) {
            List<AgentKpqMemberState> nonLeaders = candidates.stream()
                    .filter(member -> member.characterId() != session.eventLeaderId()).toList();
            if (!nonLeaders.isEmpty()) candidates = nonLeaders;
        }
        List<AgentKpqMemberState> withoutShoes = candidates.stream().filter(member -> {
            Character character = memberCharacter(member.characterId(), eventAnchor);
            return character != null
                    && character.getItemQuantity(AgentKpqDefinition.SQUISHY_SHOES, false) == 0
                    && character.hasEmptySlot(AgentKpqDefinition.SQUISHY_SHOES);
        }).toList();
        if (!withoutShoes.isEmpty()) candidates = withoutShoes;
        if (candidates.isEmpty()) return 0;
        int choice = Math.floorMod(session.seed() + session.attemptId() * 31L, candidates.size());
        return candidates.get(choice).characterId();
    }

    static boolean squishyShoesHumanWindowActive(
            boolean humanPresent, long dropSeenAtMs, long nowMs, long graceMs) {
        return humanPresent && dropSeenAtMs > 0L
                && nowMs - dropSeenAtMs < Math.max(0L, graceMs);
    }

    static boolean lootDelayActive(
            long startedAtMs, long nowMs, long delayMs) {
        return startedAtMs > 0L && nowMs - startedAtMs < Math.max(0L, delayMs);
    }

    static boolean bonusMapDwellActive(
            long phaseEnteredAtMs, long nowMs, long dwellMs) {
        return nowMs - phaseEnteredAtMs < Math.max(0L, dwellMs);
    }

    static boolean hasHumanPartyMember(AgentKpqSession session) {
        return session != null && session.members().stream()
                .anyMatch(member -> member.memberType() == AgentKpqMemberState.MemberType.HUMAN);
    }

    static int couponQuantityOnGround(List<MapItem> drops) {
        long total = 0L;
        if (drops == null) return 0;
        for (MapItem drop : drops) {
            if (drop == null || drop.isPickedUp() || drop.getMeso() > 0
                    || drop.getItemId() != AgentKpqDefinition.COUPON_ITEM) {
                continue;
            }
            total += drop.getItem() == null ? 1 : Math.max(1, drop.getItem().getQuantity());
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static int passQuantityOnGround(List<MapItem> drops) {
        long total = 0L;
        if (drops == null) return 0;
        for (MapItem drop : drops) {
            if (drop == null || drop.isPickedUp() || drop.getMeso() > 0
                    || drop.getItemId() != AgentKpqDefinition.PASS_ITEM) {
                continue;
            }
            total += drop.getItem() == null ? 1 : Math.max(1, drop.getItem().getQuantity());
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    static boolean passDropConfirmed(int passesBeforeDrop, int passesAfterDrop) {
        return passesBeforeDrop > 0 && passesAfterDrop < passesBeforeDrop;
    }

    private static boolean stageOneNpcDelayElapsed(
            AgentKpqSession session,
            AgentKpqMemberState member,
            AgentRuntimeEntry entry,
            long nowMs,
            int interactionSalt,
            long baseDelayMs,
            long varianceMs) {
        if (member.actionNotBeforeMs() == 0L) {
            member.setActionNotBeforeMs(nowMs + stageOneNpcDelayMs(
                    session.seed(), member.characterId(), member.partyNumber(), interactionSalt,
                    baseDelayMs, varianceMs));
        }
        if (nowMs < member.actionNotBeforeMs()) {
            ACTIONS.stop(entry);
            return false;
        }
        return true;
    }

    static long stageOneNpcDelayMs(
            long seed,
            int characterId,
            int partyNumber,
            int interactionSalt,
            long baseDelayMs,
            long varianceMs) {
        long boundedBase = Math.max(0L, baseDelayMs);
        long boundedVariance = Math.max(0L, varianceMs);
        if (boundedVariance == 0L) return boundedBase;
        long mixed = seed * 31L + characterId * 17L + partyNumber * 13L + interactionSalt * 37L;
        return boundedBase + Math.floorMod(mixed, boundedVariance + 1L);
    }

    static boolean shouldBypassMissingPasses(
            boolean allDelivered,
            int leaderPasses,
            int needed,
            int floorPasses,
            long missingPassSinceMs,
            long nowMs,
            long graceMs) {
        return allDelivered
                && leaderPasses < needed
                && floorPasses == 0
                && missingPassSinceMs > 0L
                && nowMs - missingPassSinceMs >= graceMs;
    }

    static boolean recoverMissingStageOnePassesForTestCommand(
            AgentKpqSession session, Character leader, long nowMs) {
        if (session == null || leader == null) return false;
        int needed = session.memberCount() - 1;
        int leaderPasses = leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        boolean allDelivered = session.members().stream()
                .filter(member -> member.characterId() != session.eventLeaderId())
                .allMatch(AgentKpqMemberState::passDelivered);
        if (!allDelivered || leaderPasses >= needed || !livePassDrops(leader.getMap()).isEmpty()) {
            return false;
        }
        bypassMissingStageOnePasses(
                session, leader, leaderPasses, needed, 0, "test-complete-command", nowMs);
        return true;
    }

    static boolean submitReadyStageOne(
            AgentKpqSession session, Character leader, long nowMs, String trigger) {
        if (session == null || leader == null) return false;
        int needed = session.memberCount() - 1;
        int leaderPasses = leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        if (leaderPasses < needed) return false;
        EventInstanceManager event = KPQ.event(leader);
        if (event != null && event.getLeaderId() != leader.getId()) {
            log.warn("KPQ event leader mismatch repaired before Stage 1 submit: session={} "
                            + "sessionLeader={} eventLeader={} partySize={} eventPlayers={}",
                    session.sessionId(), leader.getId(), event.getLeaderId(),
                    session.memberCount(), event.getPlayerCount());
            event.setLeader(leader);
        }
        boolean invoked = KPQ.runNpc(leader, AgentKpqDefinition.CLOTO_NPC);
        boolean cleared = "true".equals(KPQ.property(leader, "1stageclear"));
        if (cleared) {
            clearCouponLootObjectives(session);
            session.markProgress(nowMs);
        } else {
            log.warn("KPQ Stage 1 submit did not clear: session={} leader={}({}) passes={}/{} "
                            + "invoked={} trigger={} map={} eventLeader={} eventPlayers={} sessionMembers={}",
                    session.sessionId(), leader.getName(), leader.getId(), leaderPasses, needed,
                    invoked, trigger, leader.getMapId(), event == null ? -1 : event.getLeaderId(),
                    event == null ? -1 : event.getPlayerCount(), session.memberCount());
        }
        return cleared;
    }

    static boolean forceReadyStageOne(
            AgentKpqSession session, Character leader, long nowMs, String trigger) {
        if (session == null || leader == null) return false;
        EventInstanceManager event = KPQ.event(leader);
        int needed = session.memberCount() - 1;
        int leaderPasses = leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        if (event == null || leaderPasses < needed) return false;
        log.warn("KPQ Stage 1 forced clear after authoritative NPC rejection: session={} "
                        + "leader={}({}) passes={}/{} trigger={} eventLeader={} eventPlayers={} sessionMembers={}",
                session.sessionId(), leader.getName(), leader.getId(), leaderPasses, needed, trigger,
                event.getLeaderId(), event.getPlayerCount(), session.memberCount());
        AgentInventoryGatewayRuntime.inventory().removeById(
                leader, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, leaderPasses, false, false);
        event.setProperty("1stageclear", "true");
        event.showClearEffect(true);
        event.linkToNextStage(1, "kpq", AgentKpqDefinition.STAGE_1_MAP);
        event.gridClear();
        clearCouponLootObjectives(session);
        session.setMissingPassSinceMs(0L);
        session.markProgress(nowMs);
        session.pace().markStageOnePassesRestored();
        return true;
    }

    /**
     * Clears stage 1 without the passes Cloto counts. The floor passes are the ones a companion run's leader left
     * lying past the patience (none otherwise); every restore names its cause, and is a defect unless the party still
     * counts a member the run no longer has or a test command asked for it.
     *
     * @return true when the stage was cleared
     */
    private static boolean bypassMissingStageOnePasses(
            AgentKpqSession session,
            Character leader,
            int leaderPasses,
            int needed,
            int floorPasses,
            String trigger,
            long nowMs) {
        EventInstanceManager event = KPQ.event(leader);
        if (event == null) {
            log.error("KPQ Stage 1 missing-pass recovery failed: session={} leader={}({}) map={} "
                            + "leaderPasses={}/{} reason=no-event members={}",
                    session.sessionId(), leader.getName(), leader.getId(), leader.getMapId(),
                    leaderPasses, needed, stageOnePassDiagnostics(session, leader));
            return false;
        }
        boolean testCommand = "test-complete-command".equals(trigger);
        boolean memberGone = needed > session.memberCount() - 1;
        String cause = testCommand ? "test-command" : floorPasses > 0 ? "stale-floor-passes"
                : memberGone ? "member-gone" : "passes-gone";
        log.warn("KPQ Stage 1 missing-pass recovery: session={} leader={}({}) map={} "
                        + "leaderPasses={}/{} floorPasses={} defect={} cause={} trigger={} graceMs={} members={}",
                session.sessionId(), leader.getName(), leader.getId(), leader.getMapId(),
                leaderPasses, needed, floorPasses, !testCommand && !memberGone, cause, trigger,
                MISSING_PASS_GRACE_MS, stageOnePassDiagnostics(session, leader));
        event.setProperty("1stageclear", "true");
        event.showClearEffect(true);
        event.linkToNextStage(1, "kpq", AgentKpqDefinition.STAGE_1_MAP);
        event.gridClear();
        session.setMissingPassSinceMs(0L);
        session.markProgress(nowMs);
        session.pace().markStageOnePassesRestored();
        return true;
    }

    /**
     * Passes appear in the leader's bag with nothing in the game making them: the last resort, and a defect each time
     * it runs in a run whose passes were all made, so every restore names its cause.
     */
    private static boolean recoverMissingStageFivePasses(AgentKpqSession session, Character leader, int leaderPasses,
                                                         int floorPasses, int stalePasses, int leftAtFeet,
                                                         long nowMs) {
        int missing = Math.max(0, 10 - leaderPasses);
        if (missing == 0) {
            session.setMissingPassSinceMs(0L);
            return false;
        }
        if (!AgentInventoryGatewayRuntime.inventory().addItem(
                leader, AgentKpqDefinition.PASS_ITEM, (short) missing)) {
            fail(session, "Stage 5 passes disappeared and the leader has no ETC space for recovery", nowMs);
            return false;
        }
        session.pace().markStageFivePassesRestored();
        String cause = stalePasses > 0 ? "stale-floor-passes" : leftAtFeet > 0 ? "left-at-feet"
                : session.stage5PassesLostWithMembers() > 0 ? "lost-with-member" : "passes-gone";
        log.warn("KPQ Stage 5 missing-pass recovery restored inventory only: session={} "
                        + "leader={}({}) map={} leaderPasses={}/10 restored={} floorPasses={} stalePasses={} "
                        + "leftAtFeet={} defect=true cause={} recollect={} lostWithMembers={} mobs=0 graceMs={} "
                        + "members={}",
                session.sessionId(), leader.getName(), leader.getId(), leader.getMapId(),
                leaderPasses, missing, floorPasses, stalePasses, leftAtFeet, cause, session.stage5RecollectStarted(),
                session.stage5PassesLostWithMembers(), STAGE5_MISSING_PASS_GRACE_MS,
                stageOnePassDiagnostics(session, leader));
        session.setMissingPassSinceMs(0L);
        session.markProgress(nowMs);
        return true;
    }

    /**
     * After a companion run's recovery made up stage 5's passes, the ones still lying vanish as expired drops do, as
     * the stage-1 take-back does: left there, the leader could pick them up on top of the ten, or a companion sweep
     * them up again. What he holds beyond the ten Cloto takes is no matter.
     */
    private static void takeBackStageFivePasses(AgentKpqSession session, Character leader, List<MapItem> floorPasses) {
        if (floorPasses.isEmpty()) return;
        int lying = passQuantityOnGround(floorPasses);
        MapleMap map = leader.getMap();
        int fromFloor = 0;
        for (MapItem drop : floorPasses) {
            if (AgentMapGatewayRuntime.map().expireItemDrop(map, drop)) fromFloor++;
        }
        log.info("KPQ Stage 5 passes taken back at the recovery: session={} leader={}({}) floorDrops={}/{} "
                        + "floorPasses={}",
                session.sessionId(), leader.getName(), leader.getId(), fromFloor, floorPasses.size(), lying);
    }

    private static String stageOnePassDiagnostics(AgentKpqSession session, Character eventAnchor) {
        List<String> members = new ArrayList<>();
        for (AgentKpqMemberState member : session.members()) {
            Character character = memberCharacter(member.characterId(), eventAnchor);
            int inventoryPasses = character == null
                    ? -1 : character.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
            int grid = character == null ? -2 : KPQ.playerGrid(character);
            members.add(member.characterId() + ":" + member.memberType()
                    + ":created=" + member.passCreated()
                    + ":delivered=" + member.passDelivered()
                    + ":inventory=" + inventoryPasses
                    + ":grid=" + grid);
        }
        return members.toString();
    }

    private static int remainingPartyCouponNeed(AgentKpqSession session, Character eventAnchor) {
        long required = 0L;
        long held = 0L;
        for (AgentKpqMemberState member : session.members()) {
            if (member.passCreated() || member.couponTarget() <= 0) continue;
            Character character = memberCharacter(member.characterId(), eventAnchor);
            if (character == null || character.getMapId() != AgentKpqDefinition.STAGE_1_MAP) continue;
            required += member.couponTarget();
            held += Math.max(0,
                    character.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false));
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, required - held));
    }

    private static boolean allKnownCouponNeedsCovered(AgentKpqSession session, Character eventAnchor) {
        for (AgentKpqMemberState member : session.members()) {
            if (member.characterId() == session.eventLeaderId()) continue;
            Character character = memberCharacter(member.characterId(), eventAnchor);
            if (member.memberType() == AgentKpqMemberState.MemberType.HUMAN) {
                if (!member.passCreated()) return false;
                continue;
            }
            if (!member.questionRequested() || member.couponTarget() <= 0 || character == null) return false;
            if (!member.passCreated()
                    && character.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)
                    < member.couponTarget()) return false;
        }
        return true;
    }

    private static boolean couponSweepCollectorEligible(
            AgentKpqSession session, Character eventAnchor, int characterId) {
        AgentKpqMemberState member = session.member(characterId);
        if (member == null || member.memberType() != AgentKpqMemberState.MemberType.AGENT
                || member.characterId() == session.eventLeaderId()
                || member.passCreated() || member.couponTarget() <= 0) {
            return false;
        }
        Character collector = memberCharacter(characterId, eventAnchor);
        return collector != null
                && collector.getMapId() == AgentKpqDefinition.STAGE_1_MAP
                && collector.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)
                < member.couponTarget();
    }

    /**
     * Every collector still short of its target goes for a floor coupon no other collector is already after, so a
     * coupon from anyone's kill is picked up within seconds. Live, one rotating sweeper every 90 s left the helpers'
     * and the human's coupons on the floor until they expired. The closest collector and coupon pairs are matched
     * first, so with fewer coupons than collectors the one standing by a coupon gets it rather than the earlier party
     * member across the map. A collector keeps its coupon until it is picked up, or until {@code repick} matches the
     * pairs again. With {@code fightFirst} (L6) a collector with a Ligator alive on its platform finishes the fight
     * first: it is given only a coupon lying close by or getting old, unless the floor's coupons would finish its
     * count.
     */
    private static void syncCouponLootObjectives(
            AgentKpqSession session,
            Character eventAnchor,
            List<MapItem> floorCoupons,
            boolean repick,
            boolean fightFirst,
            List<Point> liveLigators,
            long nowMs) {
        Set<Integer> claimedDropIds = new LinkedHashSet<>();
        List<AgentKpqMemberState> unassigned = new ArrayList<>();
        for (AgentKpqMemberState member : session.members()) {
            if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) continue;
            AgentRuntimeEntry memberEntry = entry(member.characterId());
            if (memberEntry == null) continue;
            if (floorCoupons.isEmpty()
                    || !couponSweepCollectorEligible(session, eventAnchor, member.characterId())) {
                AgentGrindLootStateRuntime.clearObjectiveLootTarget(memberEntry);
                continue;
            }
            MapItem current = AgentGrindLootStateRuntime.grindLootTarget(memberEntry);
            if (!repick && AgentGrindLootStateRuntime.hasObjectiveLootTarget(memberEntry)
                    && floorCoupons.contains(current)) {
                claimedDropIds.add(current.getObjectId());
            } else {
                unassigned.add(member);
            }
        }
        record Pair(AgentKpqMemberState member, MapItem drop, double distanceSq) { }
        List<Pair> pairs = new ArrayList<>();
        int floorQuantity = fightFirst ? couponQuantityOnGround(floorCoupons) : 0;
        for (AgentKpqMemberState member : unassigned) {
            Character collector = memberCharacter(member.characterId(), eventAnchor);
            if (collector == null) continue;
            boolean busy = fightFirst && platformBusy(entry(member.characterId()), collector, liveLigators);
            boolean completesNeed = busy && collector.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)
                    + floorQuantity >= member.couponTarget();
            for (MapItem drop : floorCoupons) {
                if (claimedDropIds.contains(drop.getObjectId())) continue;
                if (busy && !AgentKpqStageOneClusterPolicy.lootNow(true,
                        drop.getPosition().distance(collector.getPosition()), nowMs - drop.getDropTime(),
                        completesNeed, STAGE1_LOOT_NEAR_PX, STAGE1_LOOT_MAX_AGE_MS)) {
                    continue;
                }
                pairs.add(new Pair(member, drop, drop.getPosition().distanceSq(collector.getPosition())));
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::distanceSq)
                .thenComparingInt(pair -> pair.drop().getObjectId())
                .thenComparingInt(pair -> pair.member().partyNumber()));
        Set<AgentKpqMemberState> matched = new LinkedHashSet<>();
        for (Pair pair : pairs) {
            if (matched.contains(pair.member()) || claimedDropIds.contains(pair.drop().getObjectId())) continue;
            matched.add(pair.member());
            claimedDropIds.add(pair.drop().getObjectId());
            AgentGrindLootStateRuntime.setObjectiveLootTarget(entry(pair.member().characterId()), pair.drop());
        }
        for (AgentKpqMemberState member : unassigned) {
            if (!matched.contains(member)) {
                AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry(member.characterId()));
            }
        }
    }

    /**
     * Stage 1 helpers fight where the collector that still needs the most coupons stands, so their kills drop near it
     * and its next sweep walk is short. Live, helpers grinding map-wide made the collectors slower, not faster. Every
     * new anchor restarts the helpers' farming, so they stay with that collector until it has its coupons (two
     * collectors a coupon apart would otherwise trade the anchor on every pickup), and the spot follows it only once
     * it walked past HELPER_ANCHOR_MOVE_PX.
     */
    private static Point stageOneHelperAnchor(AgentKpqSession session, Character eventAnchor) {
        Character followed = followedStageOneCollector(session, eventAnchor);
        if (followed == null) {
            session.setStageOneHelperAnchor(0, null);
            return null;
        }
        Point anchor = session.stageOneHelperAnchor();
        if (anchor == null || anchor.distance(followed.getPosition()) > HELPER_ANCHOR_MOVE_PX) {
            Point grounded = ACTIONS.groundPoint(followed.getMap(), followed.getPosition());
            anchor = grounded == null ? new Point(followed.getPosition()) : grounded;
        }
        session.setStageOneHelperAnchor(followed.getId(), anchor);
        return anchor;
    }

    /**
     * A companion run's helpers fight in that collector's tower instead. From the spot under it (run C) they lost it
     * whenever it climbed the x=-437 rope, the spot falling to the Ligator-less floor, and every move of the spot
     * restarted their farming: over the 200 s it worked alone they stood a median 370-410 px away and took 2 and 9 hits
     * to its 24. The tower stays while that collector climbs or jumps, and moves once it lands in another tower or
     * the territory runs out.
     */
    private static AgentKpqSession.HelperTerritory stageOneHelperTerritory(
            AgentKpqSession session, Character eventAnchor, boolean clusters, long nowMs) {
        Character followed = followedStageOneCollector(session, eventAnchor);
        if (followed == null) {
            session.setStageOneHelperTerritory(0, null);
            return null;
        }
        // With the cluster lever they camp that collector's cluster instead, wherever it walks for its coupons.
        AgentKpqSession.HelperTerritory cluster =
                clusters ? clusterTerritory(session, session.member(followed.getId()), nowMs) : null;
        if (cluster != null) {
            session.setStageOneHelperTerritory(followed.getId(), cluster);
            return cluster;
        }
        AgentKpqSession.HelperTerritory territory = session.stageOneHelperTerritory();
        if (territory == null || territory.cluster() || nowMs >= territory.expiresAtMs()
                || ACTIONS.grounded(followed) && !territory.covers(followed.getPosition().x)) {
            territory = towerTerritory(session, followed, nowMs);
        }
        session.setStageOneHelperTerritory(followed.getId(), territory);
        return territory;
    }

    /** The collector the helpers follow: the one they follow already while it still collects, else the neediest. */
    private static Character followedStageOneCollector(AgentKpqSession session, Character eventAnchor) {
        Character followed = stageOneHelperCollector(session, eventAnchor, session.stageOneHelperCollectorId());
        return followed != null ? followed : neediestStageOneCollector(session, eventAnchor);
    }

    /** The companion collector still short the most coupons, the earlier member on a tie. */
    private static Character neediestStageOneCollector(AgentKpqSession session, Character eventAnchor) {
        Character neediest = null;
        int mostNeeded = 0;
        for (AgentKpqMemberState member : session.members()) {
            Character collector = stageOneHelperCollector(session, eventAnchor, member.characterId());
            if (collector == null) continue;
            int needed = member.couponTarget()
                    - collector.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false);
            if (needed > mostNeeded) {
                mostNeeded = needed;
                neediest = collector;
            }
        }
        return neediest;
    }

    /**
     * The tower {@code collector} is in: the map's Ligator spawns split where neighbours lie more than
     * STAGE1_TOWER_GAP_PX apart (103000800's west, middle and east towers), each tower's x-band reaching to the middle
     * of the gap. Its expiry is set once here, so the territory stays equal from tick to tick.
     */
    private static AgentKpqSession.HelperTerritory towerTerritory(
            AgentKpqSession session, Character collector, long nowMs) {
        List<Point> spawns = new ArrayList<>(
                ACTIONS.monsterSpawnPositions(collector, stage1GrindTargets(collector)));
        spawns.sort(Comparator.comparingInt(spawn -> spawn.x));
        int x = collector.getPosition().x;
        int minX = Integer.MIN_VALUE;
        List<Point> tower = new ArrayList<>();
        for (int index = 0; index < spawns.size(); index++) {
            tower.add(spawns.get(index));
            boolean last = index == spawns.size() - 1;
            int gap = last ? 0 : spawns.get(index + 1).x - spawns.get(index).x;
            if (!last && gap <= STAGE1_TOWER_GAP_PX) continue;
            int maxX = last ? Integer.MAX_VALUE : spawns.get(index).x + gap / 2;
            if (x <= maxX) {
                return new AgentKpqSession.HelperTerritory(
                        "kpq-stage1:" + session.sessionId() + ':' + minX + ':' + nowMs,
                        minX, maxX, tower, nowMs + STAGE1_HELPER_TERRITORY_MS);
            }
            minX = maxX + 1;
            tower = new ArrayList<>();
        }
        return null;
    }

    private static Character stageOneHelperCollector(
            AgentKpqSession session, Character eventAnchor, int characterId) {
        if (!couponSweepCollectorEligible(session, eventAnchor, characterId)) return null;
        Character collector = memberCharacter(characterId, eventAnchor);
        return collector == null || collector.getPosition() == null ? null : collector;
    }

    /** Every Agent but the leader has dropped its pass; a human's own pass is its own to hand over. */
    private static boolean companionPassesDelivered(AgentKpqSession session) {
        List<AgentKpqMemberState> companions = session.members().stream()
                .filter(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT)
                .filter(member -> member.characterId() != session.eventLeaderId())
                .toList();
        return !companions.isEmpty() && companions.stream().allMatch(AgentKpqMemberState::passDelivered);
    }

    private static boolean everyNonLeaderPassCreated(AgentKpqSession session) {
        return session.members().stream()
                .filter(member -> member.characterId() != session.eventLeaderId())
                .allMatch(AgentKpqMemberState::passCreated);
    }

    private static void clearCouponLootObjectives(AgentKpqSession session) {
        if (session == null) return;
        for (AgentKpqMemberState member : session.members()) {
            AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry(member.characterId()));
        }
        session.setCouponSweepStartedAtMs(0L);
    }

    private static void grindStageFive(
            AgentKpqSession session, AgentRuntimeEntry entry, Character agent, long nowMs) {
        Set<Integer> configured = ACTIONS.configuredMonsterSpawnIds(agent);
        if (configured.isEmpty()) {
            configured = new LinkedHashSet<>(STAGE_5_NORMAL_MOBS);
            configured.addAll(STAGE_5_BOSS_MOBS);
        }
        int normalAlive = ACTIONS.liveMonsterCount(agent, STAGE_5_NORMAL_MOBS);
        int bossAlive = ACTIONS.liveMonsterCount(agent, STAGE_5_BOSS_MOBS);
        if (normalAlive > 0) {
            Set<Integer> anyMob = new LinkedHashSet<>(STAGE_5_NORMAL_MOBS);
            anyMob.addAll(configured);
            ACTIONS.grind(entry, stageFiveTargets(session, agent, nowMs), anyMob);
        } else if (bossAlive > 0) {
            ACTIONS.grind(entry, STAGE_5_BOSS_MOBS, configured);
        } else {
            ACTIONS.grind(entry, configured);
        }
    }

    /**
     * The live mob type the target policy gives this Agent; any of them while nobody could be judged. Worked out once
     * a tick, not once per fighter: each estimate reads every fighter's gear and skills against every live type. The
     * Squishy Shoes' winner is left out while it fetches them, so a type only it covered goes to someone else.
     */
    private static Set<Integer> stageFiveTargets(AgentKpqSession session, Character agent, long nowMs) {
        Map<Integer, Integer> targets = session.stage5Targets(nowMs);
        if (targets == null) {
            Map<Integer, Map<Integer, Double>> damage = new TreeMap<>();
            for (AgentKpqMemberState member : session.members()) {
                Character fighter = character(member.characterId());
                AgentRuntimeEntry fighterEntry = entry(member.characterId());
                if (fighter == null || fighterEntry == null || fighter.getHp() <= 0
                        || fighter.getMapId() != AgentKpqDefinition.STAGE_5_MAP
                        || (member.characterId() == session.squishyShoesWinnerId()
                        && !session.squishyShoesResolved())) {
                    continue;
                }
                damage.put(member.characterId(),
                        ACTIONS.expectedLineDamage(fighterEntry, fighter, STAGE_5_NORMAL_MOBS));
            }
            targets = AgentKpqStageFiveTargetPolicy.assign(damage);
            if (session.observeStage5Targets(targets, nowMs)) {
                log.info("KPQ Stage 5 targets: session={} targets={}", session.sessionId(),
                        targets.entrySet().stream().map(target -> character(target.getKey()).getName() + ':'
                                + target.getValue()).toList());
            }
        }
        Integer mobId = targets.get(agent.getId());
        return mobId == null ? STAGE_5_NORMAL_MOBS : Set.of(mobId);
    }

    private static Point formationTarget(AgentKpqDefinition.CombinationStage definition,
                                         AgentKpqMemberState member,
                                         long seed) {
        Rectangle area = definition.positions().get(member.assignedPosition() - 1);
        long mixed = seed + definition.stageNumber() * 997L
                + member.partyNumber() * 131L + member.assignedPosition() * 43L;
        if (definition.holdMode() == AgentKpqDefinition.HoldMode.ROPE) {
            int margin = Math.min(28, Math.max(8, area.height / 5));
            int range = Math.max(1, area.height - margin * 2);
            return new Point((int) area.getCenterX(), area.y + margin + Math.floorMod(mixed, range));
        }
        int margin = definition.stageNumber() == 4 ? 6 : 24;
        int range = Math.max(1, area.width - margin * 2);
        return new Point(area.x + margin + Math.floorMod(mixed, range), (int) area.getCenterY());
    }

    static boolean puzzlePositionReady(AgentKpqDefinition.CombinationStage definition,
                                       int assignedPosition,
                                       Point current,
                                       boolean grounded) {
        return definition.contains(assignedPosition, current)
                && (definition.holdMode() != AgentKpqDefinition.HoldMode.GROUNDED || grounded);
    }

    /**
     * Whether Cloto, judging now, sees this attempt's formation as assigned: every participant on its own spot and no
     * other live party member on any. Each event member of the leader's live party counts on the first spot that
     * contains it, as rectangleStages in 9020001.js counts. Read while she judges, under the session's monitor: by the
     * coordinator's next tick a mover may have stepped off.
     */
    static boolean formationStandsAsAssigned(AgentKpqSession session, int stage) {
        AgentKpqDefinition.CombinationStage definition = AgentKpqDefinition.combinationStage(stage);
        List<List<Integer>> order = AgentKpqCombinationOrder.forPositionCount(definition.positions().size());
        List<AgentKpqMemberState> participants = participants(session);
        Character leader = eventLeader(session);
        server.agents.integration.AgentPartySnapshot party =
                leader == null ? null : AgentPartyGatewayRuntime.party().snapshot(leader);
        if (party == null || participants.size() < 3 || session.attemptIndex() >= order.size()
                || !order.get(session.attemptIndex()).equals(session.combination())) {
            return false;
        }
        Map<Integer, Integer> assigned = new HashMap<>();
        participants.subList(0, 3).forEach(member -> assigned.put(member.characterId(), member.assignedPosition()));
        Set<Integer> livePartyIds = party.members().stream().filter(java.util.Objects::nonNull)
                .map(server.agents.integration.AgentPartyMemberSnapshot::id)
                .collect(java.util.stream.Collectors.toSet());
        int placed = 0;
        for (Character member : KPQ.eventMembers(leader)) {
            if (!livePartyIds.contains(member.getId())) continue;
            int spot = definition.firstContaining(member.getPosition());
            if (spot != assigned.getOrDefault(member.getId(), 0)) return false;
            if (spot > 0) placed++;
        }
        return placed == assigned.size();
    }

    private static void prePositionAfterEntry(AgentKpqSession session,
                                              AgentKpqMemberState member,
                                              Character agent,
                                              AgentRuntimeEntry entry,
                                              int nextStage,
                                              long nowMs) {
        member.beginStageMovement(nextStage,
                nowMs + stageMovementDelayMs(session.seed(), member, nextStage));
        if (nowMs < member.stageMovementNotBeforeMs()) {
            ACTIONS.stop(entry);
            return;
        }
        if (nextStage == 5) {
            // Carriers start before the leader is up: live (run 6) the companions killed all ten while the leader
            // still climbed out of stage 4, and the passes they left expired on the floor.
            if (stageFivePassCarriers(session)) {
                member.setRole(AgentKpqMemberState.Role.STAGE5_PASS_CARRIER);
                if (ACTIONS.liveMonsterCount(agent, STAGE_5_NORMAL_MOBS)
                        + ACTIONS.liveMonsterCount(agent, STAGE_5_BOSS_MOBS) == 0) {
                    sweepStageFivePasses(session, entry, agent, nowMs);
                    return;
                }
            }
            grindStageFive(session, entry, agent, nowMs);
            return;
        }
        AgentKpqDefinition.CombinationStage definition = AgentKpqDefinition.combinationStage(nextStage);
        List<AgentKpqMemberState> participants = participants(session);
        int participantIndex = participants.subList(0, Math.min(3, participants.size())).indexOf(member);
        if (participantIndex < 0) {
            Point cloto = ACTIONS.npcPosition(agent, AgentKpqDefinition.CLOTO_NPC);
            Point approach = npcApproachPoint(session, member, agent, cloto, 700 + nextStage);
            walkToPoint(agent, approach, entry, NPC_APPROACH_PX);
            return;
        }
        List<Integer> initial = AgentKpqCombinationOrder.forPositionCount(definition.positions().size()).getFirst();
        member.setAssignedPosition(initial.get(participantIndex));
        ACTIONS.navigate(entry, formationTarget(definition, member, session.seed()), true);
    }

    private static void initializeStageMovementDelays(
            AgentKpqSession session, int stage, long nowMs) {
        for (AgentKpqMemberState member : session.members()) {
            if (member.memberType() == AgentKpqMemberState.MemberType.AGENT) {
                member.beginStageMovement(stage,
                        nowMs + stageMovementDelayMs(session.seed(), member, stage));
            }
        }
    }

    private static boolean stageMovementDelayed(
            AgentKpqSession session,
            AgentKpqMemberState member,
            AgentRuntimeEntry entry,
            int stage,
            long nowMs) {
        member.beginStageMovement(stage,
                nowMs + stageMovementDelayMs(session.seed(), member, stage));
        if (nowMs >= member.stageMovementNotBeforeMs()) return false;
        ACTIONS.stop(entry);
        return true;
    }

    static long stageMovementDelayMs(long seed, AgentKpqMemberState member, int stage) {
        return STAGE_MOVEMENT_DELAY_MS + STAGE_MOVEMENT_DELAY_PER_SLOT_MS * Math.max(1, member.partyNumber())
                + Math.floorMod(seed + member.characterId() * 43L + stage * 101L,
                Math.max(1L, STAGE_MOVEMENT_DELAY_SPREAD_MS));
    }

    private static void moveTowardStageOneExit(
            AgentKpqSession session,
            AgentKpqMemberState member,
            Character agent,
            AgentRuntimeEntry entry) {
        Point portal = ACTIONS.portalPosition(agent, AgentKpqDefinition.NEXT_PORTAL_ID);
        if (portal == null) {
            ACTIONS.stop(entry);
            return;
        }
        long mixed = session.seed() + member.characterId() * 71L;
        Point target = ACTIONS.groundPoint(agent.getMap(),
                new Point(portal.x - 24 - (int) Math.floorMod(mixed, 54L), portal.y));
        walkToPoint(agent, target == null ? portal : target, entry, NPC_APPROACH_PX);
    }

    private static void reportCouponProgress(AgentKpqSession session,
                                            Character speaker,
                                            AgentKpqMemberState member,
                                            int have) {
        int target = member.couponTarget();
        if (target <= 0 || speaker == null) return;
        List<Integer> crossed = crossedCouponMilestones(member.couponMilestone(), have, target);
        if (crossed.isEmpty()) return;
        int milestone = crossed.getLast();
        narrate(session, speaker, "coupon-" + speaker.getId() + '-' + milestone, voice(session,
                have < target ? have + " coupons down, " + (target - have) + " to go."
                        : "got all " + target + ", heading to cloto.",
                "Coupons: " + have + "/" + target + '.'));
        member.setCouponMilestone(milestone);
    }

    static List<Integer> crossedCouponMilestones(int previous, int have, int target) {
        if (target <= 0 || have <= 0) return List.of();
        int percent = Math.min(100, (int) ((have * 100L) / target));
        List<Integer> crossed = new ArrayList<>();
        for (int milestone : List.of(20, 50, 90, 100)) {
            if (milestone > previous && percent >= milestone) crossed.add(milestone);
        }
        if (have >= target && previous < 100 && !crossed.contains(100)) crossed.add(100);
        return List.copyOf(crossed);
    }

    /**
     * One line per Agent at the stage-1 clear: what it killed, looted and missed, how long it spent in each role, and
     * when its pass came (the gap between the last two is the tail one collector works alone).
     */
    private static void reportStageOne(AgentKpqSession session, long nowMs) {
        for (AgentKpqMemberState member : session.members()) {
            AgentRuntimeEntry entry = entry(member.characterId());
            Character agent = character(member.characterId());
            if (member.memberType() != AgentKpqMemberState.MemberType.AGENT || entry == null || agent == null) {
                continue;
            }
            AgentKpqMemberState.CombatCounts now = combatCounts(entry, nowMs);
            AgentKpqMemberState.CombatCounts combat = member.stage1Combat(now);
            session.pace().recordStageOneCombat(member.characterId(), combat.attacks(), combat.kills());
            member.closeStage1RoleCombat(now);
            Map<AgentKpqMemberState.Role, Long> roleKills = new TreeMap<>();
            Map<AgentKpqMemberState.Role, Long> roleAttacks = new TreeMap<>();
            Map<AgentKpqMemberState.Role, Long> roleMs = member.stage1RoleMs();
            member.stage1RoleCombat().forEach((role, counts) -> {
                // A role held for no time and nothing done in it (WAITING at entry) is left out.
                if (counts.kills() > 0L || counts.attacks() > 0L || roleMs.containsKey(role)) {
                    roleKills.put(role, counts.kills());
                    roleAttacks.put(role, counts.attacks());
                }
            });
            Map<String, Long> footingMs = member.stage1FootingMs();
            log.info("KPQ Stage 1 summary: session={} agent={} stageMs={} kills={} coupons={} attacks={} hits={} "
                            + "misses={} passAtMs={} roleMs={} roleKills={} roleAttacks={} postureMs={} climbMs={} "
                            + "airMs={} simulationMs={} skills={} degenerate={}",
                    session.sessionId(), agent.getName(), nowMs - session.phaseEnteredAtMs(), combat.kills(),
                    member.stage1CouponsLooted(), combat.attacks(), combat.hitLines(), combat.missLines(),
                    member.stage1PassAtMs() == 0L ? -1L : member.stage1PassAtMs() - session.phaseEnteredAtMs(),
                    new TreeMap<>(member.stage1RoleMs()), roleKills, roleAttacks,
                    postureMs(entry, nowMs, member.stage1PostureBaseline()),
                    footingMs.getOrDefault(PrimitiveCapabilityGateway.Footing.ROPE.name(), 0L),
                    footingMs.getOrDefault(PrimitiveCapabilityGateway.Footing.AIR.name(), 0L),
                    new TreeMap<>(member.stage1SimulationMs()),
                    skillAttacks(entry, member.stage1SkillBaseline()),
                    Math.max(0L, AgentCombatSkillUsageStateRuntime.degenerateAttacks(entry)
                            - member.stage1DegenerateBaseline()));
        }
        session.pace().markStageOneCombatRecorded();
    }

    /**
     * Each stage-1 tick: the role's time, the kills and attacks of a role just left, and where the Agent stood and
     * how it was simulated. Only a role change reads the kill and attack counters.
     */
    private static void tallyStageOne(AgentKpqMemberState member, long nowMs) {
        member.tallyStage1Role(nowMs);
        if (member.memberType() != AgentKpqMemberState.MemberType.AGENT) return;
        AgentRuntimeEntry entry = entry(member.characterId());
        Character agent = entry == null ? null : character(member.characterId());
        if (agent == null) return;
        if (member.stage1CombatRoleChanged()) member.closeStage1RoleCombat(combatCounts(entry, nowMs));
        var simulation = entry.simulationState();
        member.sampleStage1(ACTIONS.footing(agent).name(),
                simulation == null ? null : simulation.mode().name(), nowMs);
    }

    /** Milliseconds per combat posture since {@code baseline}, from the field observation's own accounting. */
    private static Map<String, Long> postureMs(AgentRuntimeEntry entry, long nowMs, Map<String, Long> baseline) {
        Map<String, Long> spent = new TreeMap<>();
        entry.capabilityStates().require(AgentFieldObservationState.STATE_KEY).snapshot(nowMs).postureTimeMs()
                .forEach((posture, ms) -> {
                    long since = ms - baseline.getOrDefault(posture.name(), 0L);
                    if (since > 0L) spent.put(posture.name(), since);
                });
        return spent;
    }

    /** Attacks per skill id (0 is the basic attack) since {@code baseline}. */
    private static Map<Integer, Long> skillAttacks(AgentRuntimeEntry entry, Map<Integer, Long> baseline) {
        Map<Integer, Long> swung = new TreeMap<>();
        AgentCombatSkillUsageStateRuntime.snapshot(entry).forEach((skillId, usage) -> {
            long since = usage[0] - baseline.getOrDefault(skillId, 0L);
            if (since > 0L) swung.put(skillId, since);
        });
        return swung;
    }

    /** The kills and attack lines an Agent has had since it logged in; a stage's figures are differences. */
    private static AgentKpqMemberState.CombatCounts combatCounts(AgentRuntimeEntry entry, long nowMs) {
        AgentFieldObservationState.Snapshot field = entry.capabilityStates()
                .require(AgentFieldObservationState.STATE_KEY).snapshot(nowMs);
        long kills = entry.capabilityStates()
                .require(AgentOperationalEventProjectionState.STATE_KEY).snapshot().kills();
        return new AgentKpqMemberState.CombatCounts(kills, field.attacks(), field.hitLines(), field.missLines());
    }

    private static void reportLeaderPassProgress(AgentKpqSession session,
                                                 Character speaker,
                                                 String leaderName,
                                                 AgentKpqMemberState leaderState,
                                                 int have,
                                                 int needed) {
        if (speaker == null || leaderState == null || needed <= 0) return;
        int capped = Math.min(have, needed);
        if (session.companionAdopted()) {
            // One line for what the leader holds now; the call to go to Cloto says the last.
            if (capped > leaderState.reportedPassCount() && capped < needed) {
                coordinate(session, speaker, "leader-pass-" + capped, leaderName + " has " + capped
                        + (capped == 1 ? " pass, " : " passes, ") + (needed - capped) + " more to go.");
            }
        } else {
            for (int count = leaderState.reportedPassCount() + 1; count <= capped; count++) {
                coordinate(session, speaker, "leader-pass-" + count,
                        "Passes with leader: " + count + "/" + needed
                                + (count == needed ? " done" : ""));
            }
        }
        leaderState.setReportedPassCount(capped);
    }
}
