package server.agents.capabilities.partyquest.lpq;

import client.BuffStat;
import client.Character;
import client.Disease;
import client.Job;
import client.Skill;
import client.inventory.InventoryType;
import constants.skills.Rogue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentAttackRoute;
import server.agents.capabilities.combat.AgentAttackTransactionResult;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.combat.AgentCombatAttackRuntime;
import server.agents.capabilities.combat.AgentCombatConfig;
import server.agents.capabilities.combat.AgentCombatHitCounter;
import server.agents.capabilities.combat.AgentCombatPlanRuntime;
import server.agents.capabilities.combat.AgentCombatSkillCacheStateRuntime;
import server.agents.capabilities.combat.AgentCombatSkillClassifier;
import server.agents.capabilities.combat.AgentCombatTargetRuntime;
import server.agents.capabilities.combat.AgentCombatWeaponPolicy;
import server.agents.capabilities.combat.AgentGrindTargetStateRuntime;
import server.agents.capabilities.combat.AgentSkillAttackPlanRuntime;
import server.agents.capabilities.inventory.AgentInventoryStateRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.dialogue.AgentDialogueReportFormatter;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.perception.AgentMapPerception;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import scripting.reactor.ReactorActionManager;
import server.StatEffect;
import server.life.Monster;
import server.maps.Foothold;
import server.maps.Portal;
import server.maps.Reactor;
import server.maps.MapleMap;
import server.maps.MapItem;
import scripting.event.EventInstanceManager;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/** LPQ-only coordinator that advances the authored NPC, portal, reactor, combat, and loot flow. */
final class AgentLpqCoordinator {
    enum PassHandoffExitAction { NAVIGATE, PLACE_AT_PORTAL, MOVE_TO_STAGE }
    enum DarkSightRoomAction { NONE, CAST_FOR_TRAVERSAL, CANCEL_FOR_ATTACK }
    private static final Logger log = LoggerFactory.getLogger(AgentLpqCoordinator.class);
    // Resolved per call (AgentLpqGateways): each is one adapter singleton, and a test can stand in for it.
    private static final PrimitiveCapabilityGateway ACTIONS = AgentLpqGateways.perCall(
            PrimitiveCapabilityGateway.class, AgentPrimitiveCapabilityGatewayRuntime::gateway);
    private static final PartyQuestGateway LPQ = AgentLpqGateways.perCall(
            PartyQuestGateway.class, AgentPartyQuestGatewayRuntime::partyQuest);
    private static final PartyGateway PARTY = AgentLpqGateways.perCall(
            PartyGateway.class, AgentPartyGatewayRuntime::party);
    private static final String EVENT_ENDED_OR_EXPIRED = "The LPQ event instance ended or expired";
    private static final long PHASE_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PHASE_TIMEOUT_MS");
    private static final long PREPARATION_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PREPARATION_DELAY_MS");
    private static final long INTERACTION_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.INTERACTION_RETRY_MS");
    private static final long ROOM_LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.ROOM_LEASE_MS");
    private static final long STAGE_1_MAX_NATURAL_COMBAT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.STAGE_1_MAX_NATURAL_COMBAT_MS");
    private static final int INTERACTION_RADIUS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.INTERACTION_RADIUS_PX");
    /** Ordinary LPQ boxes must be struck from their own platform, not an adjacent floor. */
    private static final int REACTOR_PLATFORM_VERTICAL_TOLERANCE_PX = 40;
    private static final long STAGE_2_PASS_LOOT_SETTLE_MS = 2_000L;
    private static final long STAGE_5_PASS_LOOT_SETTLE_MS = 2_000L;
    /** Grace period for a final Stage 5 box drop to materialize before replacing it. */
    private static final long STAGE_5_MISSING_ROOM_PASS_SETTLE_MS = 3_000L;
    private static final long STAGE_5_UNOBSERVED_ASSIST_MS = 30_000L;
    private static final long STAGE_5_ROOM_CEILING_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.STAGE_5_ROOM_CEILING_MS");
    private static final int NPC_APPROACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.NPC_APPROACH_PX");
    private static final int GATHER_RADIUS_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.GATHER_RADIUS_PX");
    private static final long BONUS_DRAIN_SETTLE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.BONUS_DRAIN_SETTLE_MS");
    private static final long DARK_SIGHT_REFRESH_WINDOW_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.DARK_SIGHT_REFRESH_WINDOW_MS");
    private static final long STAGE_4_COMBAT_STALL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.STAGE_4_COMBAT_STALL_MS");
    private static final long STAGE_4_EYE_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.STAGE_4_EYE_RETRY_MS");
    private static final long STAGE_4_ROOM_ASSIST_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.STAGE_4_ROOM_ASSIST_MS");
    private static final long STAGE_4_ROOM_HARD_RECOVERY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.STAGE_4_ROOM_HARD_RECOVERY_MS");
    private static final long ROOM_PROGRESS_LOG_INTERVAL_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.ROOM_PROGRESS_LOG_INTERVAL_MS");
    private static final long COMPANION_ENTRY_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.COMPANION_ENTRY_WAIT_MS");
    private static final int PASS_HANDOVER_BATCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PASS_HANDOVER_BATCH");
    private static final int PASS_HANDOVER_NEAR_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PASS_HANDOVER_NEAR_PX");
    private static final int PASS_HANDOVER_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PASS_HANDOVER_REACH_PX");
    private static final long PASS_HANDOVER_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PASS_HANDOVER_TIMEOUT_MS");
    private static final long PASS_HANDOVER_LOOT_INHIBIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PASS_HANDOVER_LOOT_INHIBIT_MS");
    private static final long PASS_PICKUP_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PASS_PICKUP_NUDGE_MS");
    private static final long PASS_RECOLLECT_MS = passRecollectAfterMs(config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PASS_RECOLLECT_MS"));
    private static final int PASS_RECOLLECT_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PASS_RECOLLECT_MAX");
    private static final int PASS_DYING_HP_PERCENT = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PASS_DYING_HP_PERCENT");
    private static final long STAGE_5_ROOM_GIVE_UP_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.STAGE_5_ROOM_GIVE_UP_MS");
    private static final long LEADER_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.LEADER_NUDGE_MS");
    private static final long COMPANION_CLAIM_GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.COMPANION_CLAIM_GRACE_MS");
    private static final int COMPANION_CLAIM_FAILURE_LIMIT = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.COMPANION_CLAIM_FAILURE_LIMIT");
    private static final long COMPANION_AFTER_CLEAR_LIMIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.COMPANION_AFTER_CLEAR_LIMIT_MS");
    private static final long TIMEOUT_SLACK_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.TIMEOUT_SLACK_MS");
    private static final long MEMBER_RECOVERY_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.MEMBER_RECOVERY_TIMEOUT_MS");
    private static final int DARK_SIGHT_SAFE_PORTAL_ID = 0;
    private static final Point STAGE_7_BOTTOM = new Point(1, -211);
    private static final int STAGE_7_FIRING_ANCHOR_RADIUS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.STAGE_7_FIRING_ANCHOR_RADIUS");
    /**
     * The Stage 9 balloon NPC stands on authored foothold 89 at y=74. Ranged party
     * members must finish navigating onto that platform before the LPQ-only Black
     * Ratz shot is allowed. The dropped pass activates Alishar's reactor naturally.
     */
    private static final Point STAGE_9_BALLOON_PLATFORM_ANCHOR = new Point(222, 74);
    private static final int STAGE_9_RATZ_FIRING_ANCHOR_RADIUS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.STAGE_9_RATZ_FIRING_ANCHOR_RADIUS");
    private static final int STAGE_9_FLOOR_LEFT_X = -310;
    private static final int STAGE_9_FLOOR_RIGHT_X = 1_210;
    private static final int STAGE_9_FLOOR_Y = 184;
    private static final int STAGE_9_PRE_BOSS_RALLY_X = 430;
    private static final int STAGE_9_PRE_BOSS_RALLY_SPACING_PX = 28;
    private static final int STAGE_9_BOSS_EDGE_MARGIN_PX = 180;
    private static final int STAGE_9_FORMATION_RADIUS_PX = 70;
    private static final long STAGE_7_FORCE_LOOT_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.STAGE_7_FORCE_LOOT_DELAY_MS");
    private static final List<Point> STAGE_7_LOOT_SWEEP = List.of(
            new Point(1, -211), new Point(-171, -707), new Point(224, -1_044),
            new Point(219, -1_276), new Point(228, -1_543), new Point(179, -774));
    /** Authored Stage 6 portal-ID route, from the bottom row upward. */
    private static final List<Integer> STAGE_6_PORTAL_IDS = List.of(
            4, 7, 8, 12, 15, 17, 20, 23, 27, 29, 34, 36, 38, 41, 46);
    private static final List<String> STAGE_6_SEQUENCE_CHAT = List.of(
            "Stage 6 sequence: 133 221 333 123 111");
    private static final String STARTING_CHAT =
            "Party ready. Everyone is here. Starting LPQ in 5 seconds.";
    private static final String STAGE_2_SCOUT_CHAT =
            "Stage 2: two scouts are going first. Everyone else wait for trap clear.";
    private static final String STAGE_2_TRAP_CLEAR_CHAT =
            "Stage 2 trap clear. Everyone enter now.";
    private static final Map<Integer, List<FlavorLine>> MIXED_PARTY_FLAVOR = Map.ofEntries(
            Map.entry(1, List.of(
                    new FlavorLine(3_000L, 0, "{human}, coupons first. Try not to race the ladders."),
                    new FlavorLine(10_000L, 1, "Who put this many Ratz in a clock tower?"))),
            Map.entry(2, List.of(
                    new FlavorLine(3_000L, 0, "Scouts first. If the floor bites, we learned something."),
                    new FlavorLine(10_000L, 1, "Very scientific. You step on it."))),
            Map.entry(3, List.of(
                    new FlavorLine(3_000L, 0, "{human}, clear your lane and head upward when it is quiet."),
                    new FlavorLine(10_000L, 1, "The boxes are full of Blocktopuses. Naturally."))),
            Map.entry(4, List.of(
                    new FlavorLine(3_000L, 0, "Split rooms now. Call out if your monster ignores your damage type."),
                    new FlavorLine(10_000L, 1, "Mine is weak to confidence. I brought too much."))),
            Map.entry(5, List.of(
                    new FlavorLine(3_000L, 0, "Teleport and Dark Sight runners, your doors are reserved."),
                    new FlavorLine(10_000L, 1, "Four passes each, and please bring yourselves back too."))),
            Map.entry(6, List.of(
                    new FlavorLine(3_000L, 0, "The sequence is in party chat. No heroic guessing."),
                    new FlavorLine(10_000L, 1, "I guessed once. It was a very educational fall."))),
            Map.entry(7, List.of(
                    new FlavorLine(3_000L, 0, "Ranged team takes the upper Ratz; everyone else waits for Rombards."),
                    new FlavorLine(10_000L, 1, "Rat first, box second, giant robot somehow."))),
            Map.entry(8, List.of(
                    new FlavorLine(3_000L, 0, "{human}, stay on your assigned barrel until the balloon checks us."),
                    new FlavorLine(10_000L, 1, "Nine barrels and somehow none labeled correct."))),
            Map.entry(9, List.of(
                    new FlavorLine(3_000L, 0, "Ranged team, balloon platform first; then Black Ratz."),
                    new FlavorLine(10_000L, 1, "After Alishar, sweep summons and drops before we leave."))));

    private AgentLpqCoordinator() { }

    private static AgentLpqStageRecoveryPolicy recovery(int stage) {
        return AgentLpqStageRecoveryPolicy.forStage(stage);
    }

    static void tick(AgentLpqSession session, long nowMs) {
        synchronized (session) {
            if (session.terminal()) return;
            // A companion run whose release has begun (its companions on their way out) gets no coordinator pass.
            if (session.companionAdopted() && session.terminationStarted()) return;
            if (session.companionAdopted()) { companionRunTick(session, nowMs); return; }
            if (nowMs - session.lastProgressAtMs() > PHASE_TIMEOUT_MS) {
                AgentLpqTerminationService.fail(session, "No LPQ progress before phase timeout", nowMs);
                return;
            }
            Character leader = character(session.eventLeaderId());
            if (leader == null || leader.getHp() <= 0) {
                AgentLpqTerminationService.fail(session, "The LPQ event leader is unavailable", nowMs);
                return;
            }
            if (session.eventInstance() == null && LPQ.event(leader) != null) session.bindEventInstance(LPQ.event(leader));
            synchronizePhase(session, leader, nowMs);
            String eventFailure = liveEventFailure(session, leader);
            if (!eventFailure.isEmpty()) {
                if (EVENT_ENDED_OR_EXPIRED.equals(eventFailure)) {
                    log.warn("LPQ event end detail: session={} {}", session.sessionId(),
                            eventEndDetail(session, leader));
                }
                AgentLpqTerminationService.fail(session, eventFailure, nowMs);
                return;
            }
            recalculateStageAssignments(session, nowMs);
            tickMixedPartyFlavor(session, nowMs);
            switch (session.phase()) {
                case PREPARING -> prepare(session, leader, nowMs);
                case ENTERING -> enter(session, leader, nowMs);
                case STAGE_1, STAGE_2, STAGE_3, STAGE_4, STAGE_5,
                        STAGE_7 -> collectionStage(session, leader, nowMs);
                case STAGE_6 -> portalMaze(session, leader, nowMs);
                case STAGE_8 -> platformPuzzle(session, leader, nowMs);
                case STAGE_9 -> boss(session, leader, nowMs);
                case BONUS -> bonus(session, nowMs);
                case CLAIMING_REWARD -> claim(session, nowMs);
                case EXITING -> exitAfterRewardWarpSettles(session, nowMs);
                default -> { }
            }
        }
    }

    /** Rebuilds authoritative stage ownership once, after transition cleared the old stage. */
    private static void recalculateStageAssignments(AgentLpqSession session, long nowMs) {
        if (!session.stageAssignmentsNeedRecalculation()) return;
        if (session.members().stream().anyMatch(member -> character(member.characterId()) == null)) return;
        int stage = stage(session.phase());
        switch (stage) {
            case 4, 5 -> assignRooms(session, stage, AgentLpqDefinition.roomMaps(stage), nowMs);
            case 7 -> assignStageSevenRoles(session);
            case 8 -> {
                if (!assignStageEightRoles(session)) return;
            }
            case 9 -> assignStageNineRoles(session);
            default -> {
                // The transition baseline (leader + general members) is the full plan.
            }
        }
        session.markStageAssignmentsRecalculated(nowMs);
    }

    private static void assignStageSevenRoles(AgentLpqSession session) {
        List<Integer> topIds = stageSevenTopMemberIds(
                session.members(), id -> AgentLpqRosterRequirementPolicy.stageSevenBoxWacker(character(id)),
                session.eventLeaderId(), preferredStageSevenHuman(session));
        for (AgentLpqMemberState member : session.members()) {
            int topIndex = topIds.indexOf(member.characterId());
            member.assign(topIndex >= 0 ? AgentLpqMemberState.Role.RANGED_TRIGGER
                    : AgentLpqMemberState.Role.BOSS_ATTACKER, AgentLpqDefinition.stage(7).mapId());
            member.assignPlatform(topIndex >= 0 ? topIndex + 1 : 0);
        }
    }

    private static boolean assignStageEightRoles(AgentLpqSession session) {
        List<AgentLpqMemberState> participants = session.members().stream()
                .filter(member -> member.characterId() != session.eventLeaderId())
                .sorted(Comparator.comparingInt(AgentLpqMemberState::characterId)).toList();
        participants = withTheLeaderOnABox(session, participants);
        if (participants.size() < 5) return false;
        Map<Integer, Integer> assignments = session.stage8Assignments(
                participants.stream().map(AgentLpqMemberState::characterId).toList());
        for (int index = 0; index < 5; index++) {
            AgentLpqMemberState member = participants.get(index);
            member.assign(index == 4 ? AgentLpqMemberState.Role.PLATFORM_MOVER
                    : AgentLpqMemberState.Role.PLATFORM_HOLDER, AgentLpqDefinition.stage(8).mapId());
            member.assignPlatform(assignments.get(member.characterId()));
        }
        return true;
    }

    /**
     * What the coordinator can still see when its event "ended or expired" (logged before the failure): the leader's
     * map and HP, whether the bound event was disposed, and how many dead members the departure gate held. Never
     * throws.
     */
    static String eventEndDetail(AgentLpqSession session, Character leader) {
        try {
            EventInstanceManager event = session.eventInstance();
            return "leaderMap=" + leader.getMapId() + " leaderHp=" + leader.getHp()
                    + " eventDisposed=" + (event == null ? "unbound" : String.valueOf(event.isEventDisposed()))
                    + " heldDead=" + session.heldDeadCount();
        } catch (RuntimeException e) {
            return "detail unavailable";
        }
    }

    static String liveEventFailure(AgentLpqSession session, Character leader) {
        if (session == null || !requiresLiveEvent(session.phase())) return "";
        EventInstanceManager event = session.eventInstance();
        if (session.phase() == AgentLpqSession.Phase.CLAIMING_REWARD) {
            return event == null || event.isEventDisposed()
                    ? "The LPQ reward instance ended before all claims were resolved" : "";
        }
        EventInstanceManager leaderEvent = LPQ.event(leader);
        if (event == null || leaderEvent == null) {
            return EVENT_ENDED_OR_EXPIRED;
        }
        if (event != leaderEvent || event.isEventDisposed()) {
            return "The LPQ event instance no longer matches the leader";
        }
        if (leader == null || event.getLeaderId() != leader.getId()
                || event.getPlayerById(leader.getId()) != leader) {
            return "The LPQ event leader is no longer registered correctly";
        }
        if (!leader.isPartyLeader()) {
            return "The LPQ event leader is no longer the party leader";
        }
        MapleMap leaderMap = leader.getMap();
        if (leaderMap == null || leaderMap.getEventInstance() != event
                || !AgentLpqDefinition.isEventMap(leader.getMapId())) {
            return "The LPQ event leader left the bound instance map";
        }
        if (session.phase() != AgentLpqSession.Phase.BONUS
                && session.phase() != AgentLpqSession.Phase.CLAIMING_REWARD
                && (!event.isTimerStarted() || event.getTimeLeft() <= 0L)) {
            return "The LPQ event timer expired";
        }
        Set<Integer> sessionMemberIds = session.members().stream()
                .map(AgentLpqMemberState::characterId)
                .collect(java.util.stream.Collectors.toSet());
        long registeredMembers = event.getPlayers().stream()
                .filter(java.util.Objects::nonNull)
                .filter(member -> sessionMemberIds.contains(member.getId()))
                .filter(member -> member.getEventInstance() == event)
                .filter(member -> member.getMap() != null
                        && member.getMap().getEventInstance() == event)
                .count();
        if (requiresRegisteredRoster(session.phase())
                && registeredMembers < AgentLpqDefinition.MIN_PARTY_SIZE) {
            return "LPQ no longer has five registered session members";
        }
        return "";
    }

    static boolean requiresRegisteredRoster(AgentLpqSession.Phase phase) {
        return phase != null && phase != AgentLpqSession.Phase.ENTERING;
    }

    static boolean requiresLiveEvent(AgentLpqSession.Phase phase) {
        return phase != null && switch (phase) {
            case ENTERING,
                    STAGE_1, STAGE_2, STAGE_3, STAGE_4, STAGE_5,
                    STAGE_6, STAGE_7, STAGE_8, STAGE_9,
                    BONUS, CLAIMING_REWARD -> true;
            default -> false;
        };
    }

    private static void synchronizePhase(AgentLpqSession session, Character leader, long nowMs) {
        int mapId = leader.getMapId();
        if (mapId == AgentLpqDefinition.CLEAR_MAP) {
            session.freezeRewardEligibility();
            session.transition(AgentLpqSession.Phase.BONUS, nowMs);
            return;
        }
        if (mapId == AgentLpqDefinition.BONUS_MAP) {
            boolean bonusMemberStillInside = session.members().stream()
                    .map(member -> character(member.characterId()))
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(member -> member.getMapId() == AgentLpqDefinition.CLEAR_MAP);
            if (session.phase() == AgentLpqSession.Phase.BONUS && bonusMemberStillInside) return;
            session.transition(AgentLpqSession.Phase.CLAIMING_REWARD, nowMs);
            return;
        }
        int stage = AgentLpqDefinition.stageNumber(mapId);
        if (stage >= 1 && stage <= 9) {
            AgentLpqSession.Phase expected = synchronizedStagePhase(session.phase(), stage);
            if (session.phase() != expected) session.transition(expected, nowMs);
        }
    }

    static AgentLpqSession.Phase synchronizedStagePhase(
            AgentLpqSession.Phase current, int stage) {
        AgentLpqSession.Phase expected = AgentLpqSession.Phase.valueOf("STAGE_" + stage);
        if ((current == AgentLpqSession.Phase.PREPARING
                || current == AgentLpqSession.Phase.ENTERING)
                && expected == AgentLpqSession.Phase.STAGE_1) {
            return AgentLpqSession.Phase.ENTERING;
        }
        if (current == AgentLpqSession.Phase.STAGE_2
                && expected == AgentLpqSession.Phase.STAGE_1) {
            return current;
        }
        return expected;
    }

    private static void prepare(AgentLpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() == AgentLpqDefinition.stage(1).mapId() && LPQ.event(leader) != null) {
            session.transition(AgentLpqSession.Phase.ENTERING, nowMs);
            return;
        }
        if (leader.getMapId() != AgentLpqDefinition.RECRUIT_MAP) return;
        Point npc = ACTIONS.npcPosition(leader, AgentLpqDefinition.ENTRY_NPC);
        if (npc == null) return;
        boolean allGathered = true;
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant == null || participant.getMapId() != AgentLpqDefinition.RECRUIT_MAP) {
                allGathered = false;
                continue;
            }
            AgentRuntimeEntry participantEntry = entry(member.characterId());
            if (member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                if (participantEntry == null) {
                    allGathered = false;
                    continue;
                }
                Point target = lobbyApproachPoint(session, member, participant, npc);
                if (!near(participant.getPosition(), target, NPC_APPROACH_PX)) {
                    ACTIONS.navigate(participantEntry, target, true);
                    allGathered = false;
                }
            } else if (!near(participant.getPosition(), npc, GATHER_RADIUS_PX)) {
                allGathered = false;
            }
        }
        if (!allGathered) {
            session.setReadyAtMs(0L);
            return;
        }
        if (session.readyAtMs() == 0L) {
            session.setReadyAtMs(nowMs + PREPARATION_DELAY_MS);
            Character speaker = isAgent(leader) ? leader : session.members().stream()
                    .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                    .map(member -> character(member.characterId()))
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            String startingLine = voice(session, null, STARTING_CHAT);
            if (speaker != null && startingLine != null) {
                AgentPacketGatewayRuntime.packets().broadcastChatText(
                        speaker, startingLine, false, 0);
            }
            return;
        }
        if (nowMs < session.readyAtMs() || !isAgent(leader)) return;
        AgentRuntimeEntry entry = entry(leader.getId());
        if (entry == null) return;
        if (LPQ.runNpc(leader, AgentLpqDefinition.ENTRY_NPC, 0)) session.markProgress(nowMs);
    }

    private static Point lobbyApproachPoint(
            AgentLpqSession session, AgentLpqMemberState member, Character character, Point npc) {
        long mixed = session.seed() + member.characterId() * 131L;
        int offset = 24 + Math.floorMod(mixed, Math.max(1, NPC_APPROACH_PX));
        Point target = new Point(npc.x - offset, npc.y);
        Point grounded = ACTIONS.groundPoint(character.getMap(), target);
        return grounded == null ? target : grounded;
    }

    private static void enter(AgentLpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() != AgentLpqDefinition.stage(1).mapId() || LPQ.event(leader) == null) return;
        session.bindEventInstance(LPQ.event(leader));
        boolean allEntered = true;
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant != null && LPQ.sameEvent(leader, participant)) continue;
            allEntered = false;
            if (participant != null && member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                restoreEventMember(session, "entering-not-in-event", participant, AgentLpqDefinition.stage(1).mapId(), nowMs);
            } else if (participant != null && nowMs >= member.nextActionAtMs()) {
                String enterLine = voice(session, null, "Enter LPQ through the Red Sign to join your party instance.");
                if (enterLine != null) participant.dropMessage(6, enterLine);
                member.deferUntil(nowMs + 5_000L);
            }
        }
        if (allEntered) beginStageOne(session, nowMs);
    }

    /**
     * The party is inside: stage 1 begins. A companion run first logs its companions' potions
     * ('LPQ potions: session= start=') and keeps them for the release's end line; a failure to read them is logged
     * and never keeps stage 1 from beginning.
     */
    static void beginStageOne(AgentLpqSession session, long nowMs) {
        if (session.companionAdopted()) {
            try {
                String potions = AgentLpqTerminationService.potions(session);
                session.setPotionsAtEntry(potions);
                log.info("LPQ potions: session={} start={}", session.sessionId(), potions);
            } catch (RuntimeException failure) {
                log.warn("LPQ potions failed: session={}", session.sessionId(), failure);
            }
            beginPace(session, nowMs);
        }
        session.transition(AgentLpqSession.Phase.STAGE_1, nowMs);
    }

    private static void collectionStage(AgentLpqSession session, Character leader, long nowMs) {
        int stage = stage(session.phase());
        AgentLpqDefinition.Stage contract = AgentLpqDefinition.stage(stage);
        if (LPQ.property(leader, stage + "stageclear") != null) {
            movePartyToNextStage(session, stage, nowMs);
            return;
        }
        if (stage == 2 && (!session.stage2TrapClearAnnounced()
                || leader.getMapId() == AgentLpqDefinition.stage(1).mapId())) {
            advanceStageTwoScoutProtocol(session, nowMs);
            return;
        }
        if (session.companionAdopted()) {
            companionCollection(session, leader, contract, nowMs);
            return;
        }
        if (session.passHandoffRecoveryActive()) {
            if (recoverPassHandoffAtNpc(session, leader, contract, nowMs)) {
                submit(session, leader, contract, nowMs);
                if (LPQ.property(leader, stage + "stageclear") != null) {
                    movePartyToNextStage(session, stage, nowMs);
                }
            }
            return;
        }
        catchUpLaggingMembers(session, stage, contract.mapId(), nowMs);
        cooperativeCouponCollection(session, leader, contract, nowMs);
    }

    private static void cooperativeCouponCollection(AgentLpqSession session, Character leader,
                                                    AgentLpqDefinition.Stage contract, long nowMs) {
        int stage = contract.number();
        if (LPQ.property(leader, stage + "stageclear") != null) {
            movePartyToNextStage(session, stage, nowMs);
            return;
        }
        if (!session.couponRegrouping(stage)) {
            int partyPasses = partyPassCount(session);
            if (partyPasses >= contract.submissionCount()) {
                if (passHandoffRecoveryApplicable(
                        stage, partyPasses, contract.submissionCount())
                        && memberOutsideStageMap(session, contract.mapId())) {
                    runCollectionObjectives(session, stage, nowMs);
                    boolean allOutsideRoomsComplete = outsideMembersHaveCompletedRooms(
                            session, stage, contract.mapId());
                    long stalledForMs = splitRoomCompletionInactivity(
                            session, stage, partyPasses, nowMs);
                    if ((allOutsideRoomsComplete
                            || stalledForMs >= recovery(stage).missingPassMs())
                            && session.beginPassHandoffRecovery(nowMs)) {
                        session.members().forEach(AgentLpqMemberState::clearTraversalProgress);
                        log.info("LPQ Stage {} coordinated pass-handoff exit started: "
                                        + "session={} partyPasses={}/{} stalledForMs={} "
                                        + "allOutsideRoomsComplete={}",
                                stage, session.sessionId(), partyPasses,
                                contract.submissionCount(), stalledForMs,
                                allOutsideRoomsComplete);
                    }
                    return;
                }
                if (!allLoosePassesCollected(session, stage, nowMs)) return;
                session.beginCouponRegroup(stage, nowMs);
            } else {
                session.observeSubmissionReady(false, nowMs);
                runCollectionObjectives(session, stage, nowMs);
                recoverMissingPasses(session, leader, stage, contract.submissionCount(), nowMs);
                if (partyPassCount(session) < contract.submissionCount()) return;
                if (!allLoosePassesCollected(session, stage, nowMs)) return;
                session.beginCouponRegroup(stage, nowMs);
            }
        }

        regroupCouponsAtNpc(session, leader, contract, nowMs);
        if (leader.getItemQuantity(AgentLpqDefinition.PASS, false) < contract.submissionCount()) return;
        submit(session, leader, contract, nowMs);
        if (LPQ.property(leader, stage + "stageclear") != null) {
            movePartyToNextStage(session, stage, nowMs);
        }
    }

    private static void runCollectionObjectives(
            AgentLpqSession session, int stage, long nowMs) {
        if (stage == 4 || stage == 5) splitRooms(session, stage, nowMs);
        else if (stage == 7) stageSevenObjectives(session, nowMs);
        else ordinaryObjectives(session, stage, nowMs);
    }

    /** Collection stages end as a visible handoff: regroup at the balloon, then drop coupons. */
    private static void regroupCouponsAtNpc(AgentLpqSession session, Character leader,
                                            AgentLpqDefinition.Stage contract, long nowMs) {
        if (contract.number() == 2) {
            for (AgentLpqMemberState member : session.members()) {
                Character participant = character(member.characterId());
                AgentRuntimeEntry participantEntry = entry(member.characterId());
                if (participant == null || participant.getMapId() != 922_010_201) continue;
                if (member.memberType() == AgentLpqMemberState.MemberType.AGENT
                        && participantEntry != null) {
                    useStageTwoTrapExit(session, participantEntry, participant, nowMs);
                }
            }
        }
        Point npc = ACTIONS.npcPosition(leader, contract.npcId());
        if (npc == null) return;
        boolean everyoneOnMainMap = session.members().stream()
                .map(member -> character(member.characterId()))
                .allMatch(participant -> participant != null
                        && participant.getMapId() == contract.mapId());
        long regroupElapsedMs = session.observeMainMapRally(
                contract.mapId(), everyoneOnMainMap, nowMs);

        boolean everyoneAtNpc = true;
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant == null || participant.getMapId() != contract.mapId()) {
                everyoneAtNpc = false;
                continue;
            }
            AgentRuntimeEntry participantEntry = entry(participant.getId());
            if (contract.number() == 5
                    && member.memberType() == AgentLpqMemberState.MemberType.AGENT
                    && participantEntry != null
                    && prepareStageFiveMainMapDarkSight(
                    member, participantEntry, participant, nowMs)) {
                everyoneAtNpc = false;
                continue;
            }
            if (near(participant.getPosition(), npc, INTERACTION_RADIUS)) {
                if (participantEntry != null && participant.getId() != leader.getId()) {
                    ACTIONS.stop(participantEntry);
                }
                if (member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                    member.deferUntil(nowMs);
                }
                continue;
            }
            if (member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                if (participantEntry != null) {
                    if (nowMs < member.nextActionAtMs()) {
                        everyoneAtNpc = false;
                        continue;
                    }
                    everyoneAtNpc = false;
                    rallyMemberAtNpc(member, participant, participantEntry, npc);
                } else {
                    everyoneAtNpc = false;
                }
            } else {
                everyoneAtNpc = false;
            }
        }
        if (!everyoneAtNpc) {
            recoverBalloonRallyPositions(
                    session, contract.number(), contract.mapId(), npc,
                    regroupElapsedMs, isAgent(leader), nowMs);
            if (balloonRallyForceTransferDue(contract.number(), regroupElapsedMs)) {
                forcePassTransferToLeader(session, "final-rally", leader, contract, regroupElapsedMs, nowMs);
            }
            return;
        }

        int agentDrops = dropAgentPassesAtNpc(session, leader, contract, npc, nowMs);
        if (agentDrops > 0) {
            log.info("LPQ Stage {} coordinated balloon handoff dropped passes from {} agents "
                            + "in one batch: session={} leader={}({})",
                    contract.number(), agentDrops, session.sessionId(),
                    leader.getName(), leader.getId());
        }

        if (isAgent(leader)) {
            AgentRuntimeEntry leaderEntry = entry(leader.getId());
            if (leaderEntry != null) {
                if (collectNearbyDrops(
                        leaderEntry, leader, Set.of(AgentLpqDefinition.PASS)) == 0) {
                    collectNearestDrop(leaderEntry, leader,
                            Set.of(AgentLpqDefinition.PASS), new LinkedHashSet<>());
                }
            }
        }
        if (regroupElapsedMs >= recovery(contract.number()).missingPassMs()
                && leader.getItemQuantity(AgentLpqDefinition.PASS, false)
                < contract.submissionCount()) {
            int vacuumed = vacuumPassDrops(session, "coupon-regroup", leader, Set.of(leader.getMap()));
            if (vacuumed > 0) {
                session.markProgress(nowMs);
                log.warn("LPQ Stage {} coupon-regroup recovery vacuumed {} real pass drops: "
                                + "session={} leader={}({}) elapsedMs={}",
                        contract.number(), vacuumed, session.sessionId(), leader.getName(),
                        leader.getId(), regroupElapsedMs);
            }
        }
    }

    /** Drops every ready Agent's held passes in one coordinator tick after the party has gathered. */
    private static int dropAgentPassesAtNpc(
            AgentLpqSession session, Character leader, AgentLpqDefinition.Stage contract,
            Point npc, long nowMs) {
        int droppers = 0;
        for (AgentLpqMemberState member : session.members()) {
            if (member.characterId() == leader.getId()) continue;
            Character participant = character(member.characterId());
            int coupons = participant == null ? 0
                    : participant.getItemQuantity(AgentLpqDefinition.PASS, false);
            if (coupons <= 0) continue;
            if (member.memberType() == AgentLpqMemberState.MemberType.HUMAN) {
                continue;
            }
            if (participant.getMapId() != contract.mapId()
                    || !near(participant.getPosition(), npc, INTERACTION_RADIUS)) continue;
            AgentRuntimeEntry participantEntry = entry(participant.getId());
            if (participantEntry != null && AgentScriptItemActionService.dropItem(
                    participantEntry, InventoryType.ETC, AgentLpqDefinition.PASS,
                    (short) Math.min(Short.MAX_VALUE, coupons))) {
                if (contract.number() == 5) member.handOffStageFivePasses(coupons);
                member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                session.markProgress(nowMs);
                droppers++;
            }
        }
        return droppers;
    }

    /**
     * A leader can reach the next portal before the rest of the party. Phase synchronization follows the
     * leader, so keep advancing Agents that are still standing in an earlier authored stage instead of
     * abandoning them when the session phase changes.
     */
    static void catchUpLaggingMembers(AgentLpqSession session, int stage,
                                              int destination, long nowMs) {
        long phaseElapsedMs = Math.max(0L, nowMs - session.phaseEnteredAtMs());
        // A companion run's laggards walk the authored traversal (no absolute move; P6g LPQ C2.4).
        boolean absoluteRecoveryDue = !session.companionAdopted() && stageTransitionCatchUpRecoveryDue(
                stage, phaseElapsedMs);
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null) continue;
            if (agent.getMapId() == AgentLpqDefinition.EXIT_MAP
                    && restoreEventMember(session, "dead-agent-reentry", agent, destination, nowMs)) {
                if (stage == 5) {
                    restoreStageFiveEarnedPasses(session, member, agent, nowMs);
                    prepareStageFiveMainMapDarkSight(member, entry, agent, nowMs);
                }
                continue;
            }
            int currentStage = AgentLpqDefinition.stageNumber(agent.getMapId());
            if (currentStage <= 0 || currentStage >= stage) continue;
            if (absoluteRecoveryDue) {
                int sourceMapId = agent.getMapId();
                if (moveWithinEvent(session, "catch-up-absolute", agent, destination, nowMs)) {
                    member.clearTraversalProgress();
                    log.warn("LPQ lagging-member absolute transition recovery: session={} "
                                    + "member={}({}) source={} stage={} destination={} "
                                    + "phaseElapsedMs={}",
                            session.sessionId(), agent.getName(), agent.getId(), sourceMapId,
                            stage, destination, phaseElapsedMs);
                }
                continue;
            }
            if (currentStage == 2 && agent.getMapId() == 922_010_201 && session.companionAdopted()) {
                useStageTwoTrapExit(session, entry, agent, nowMs);
                continue;
            }
            if (currentStage == 2 && agent.getMapId() == 922_010_201) {
                recoverStageTwoTrap(session, "catch-up-trap", agent, nowMs);
                continue;
            }
            if (currentStage == 6 && agent.getMapId() == AgentLpqDefinition.stage(6).mapId()) {
                advancePortalMazeMember(session, member, agent, entry, nowMs);
                continue;
            }
            int nextMapId = AgentLpqDefinition.nextTraversalMap(agent.getMapId());
            long inactiveForMs = nextMapId == 0 ? 0L
                    : traversalInactivity(member, agent, nextMapId, nowMs);
            if (nextMapId != 0) enterPortalTo(entry, agent, nextMapId);
            if (session.companionAdopted()) {
                // A stalled walk is re-planned once per traversal window, never moved.
                if (agent.getMapId() != destination && inactiveForMs >= recovery(stage).traversalMs()) {
                    ACTIONS.refreshNavigation(entry, agent);
                    member.clearTraversalProgress();
                }
                continue;
            }
            if (agent.getMapId() != destination
                    && inactiveForMs >= recovery(stage).traversalMs()) {
                log.warn("LPQ lagging-member traversal recovery: session={} member={}({}) "
                                + "source={} stage={} destination={} inactiveForMs={}",
                        session.sessionId(), agent.getName(), agent.getId(), agent.getMapId(),
                        stage, destination, inactiveForMs);
                moveWithinEvent(session, "catch-up-traversal", agent, destination, nowMs);
                member.clearTraversalProgress();
            }
        }
    }

    private static void ordinaryObjectives(AgentLpqSession session, int stage, long nowMs) {
        int mapId = AgentLpqDefinition.stage(stage).mapId();
        Point npc = stageNpcPosition(session, stage);
        Set<Integer> reactorClaims = stage == 2 || stage == 3
                ? activeReactorClaims(session, mapId) : new LinkedHashSet<>();
        Set<Integer> trapReactorClaims = stage == 2
                ? activeReactorClaims(session, 922_010_201) : Set.of();
        Map<Integer, Set<Integer>> lootClaimsByMap = new LinkedHashMap<>();
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null) continue;
            if (stage == 2 && agent.getMapId() == 922_010_201) {
                Set<Integer> mapLootClaims = lootClaimsByMap.computeIfAbsent(
                        agent.getMapId(), ignored -> new LinkedHashSet<>());
                if (collectStageTwoPassDrop(
                        session, entry, agent, mapLootClaims)) {
                    continue;
                }
                announcePassProgress(session, member, agent, stage, agent.getMapId(),
                        agent.getItemQuantity(AgentLpqDefinition.PASS, false),
                        AgentLpqDefinition.stage(stage).submissionCount(),
                        AgentLpqDefinition.stage(stage).submissionCount());
                boolean hitTrapReactor = hitNearestReactor(
                        entry, agent, nowMs, member, false, trapReactorClaims);
                if (member.reactorTargetObjectId() != 0) {
                    trapReactorClaims.add(member.reactorTargetObjectId());
                }
                boolean looseTrapPass = AgentMapPerception.items(agent.getMap()).stream()
                        .anyMatch(item -> !item.isPickedUp()
                                && item.getItemId() == AgentLpqDefinition.PASS);
                if (!hitTrapReactor && activeReactors(agent).isEmpty() && !looseTrapPass) {
                    member.clearReactorWork();
                    useStageTwoTrapExit(session, entry, agent, nowMs);
                }
                continue;
            }
            if (agent.getMapId() != mapId) continue;
            Set<Integer> mapLootClaims = lootClaimsByMap.computeIfAbsent(
                    agent.getMapId(), ignored -> new LinkedHashSet<>());
            if ((stage == 2
                    ? collectStageTwoPassDrop(session, entry, agent, mapLootClaims)
                    : collectNearestDrop(entry, agent,
                    Set.of(AgentLpqDefinition.PASS), mapLootClaims))) {
                continue;
            }
            announcePassProgress(session, member, agent, stage, mapId,
                    agent.getItemQuantity(AgentLpqDefinition.PASS, false),
                    AgentLpqDefinition.stage(stage).submissionCount(),
                    AgentLpqDefinition.stage(stage).submissionCount());
            boolean hitReactor = (stage == 2 || stage == 3)
                    && hitNearestReactor(entry, agent, nowMs, member, false, reactorClaims);
            if ((stage == 2 || stage == 3) && member.reactorTargetObjectId() != 0) {
                reactorClaims.add(member.reactorTargetObjectId());
            }
            if (!hitReactor) {
                if (stageMapObjectivesExhausted(agent) && npc != null) {
                    rallyMemberAtNpc(member, agent, entry, npc);
                    continue;
                }
                if (stage == 1) {
                    announceStageOneMeleeIntent(session, member, agent);
                    grindReachableStageOneMobs(entry, agent);
                } else {
                    Set<Integer> liveTargets = agent.getMap().getAllMonsters().stream()
                            .map(Monster::getId).collect(java.util.stream.Collectors.toSet());
                    Set<Integer> combatTargets = collectionCombatTargets(
                            stage, ACTIONS.configuredMonsterSpawnIds(agent), liveTargets);
                    if (ordinaryStageShouldRallyAtNpc(
                            stage, ACTIONS.liveMonsterCount(agent, combatTargets)) && npc != null) {
                        rallyMemberAtNpc(member, agent, entry, npc);
                    } else {
                        ACTIONS.grind(entry, combatTargets);
                    }
                }
            }
        }
    }

    static boolean ordinaryStageShouldRallyAtNpc(int stage, int liveCombatTargets) {
        return (stage == 2 || stage == 3) && liveCombatTargets <= 0;
    }

    static Set<Integer> collectionCombatTargets(
            int stage, Set<Integer> configuredTargets, Set<Integer> liveTargets) {
        Set<Integer> targets = new LinkedHashSet<>(stageCombatTargets(stage, configuredTargets));
        if (stage == 3 && liveTargets != null) targets.addAll(liveTargets);
        return Set.copyOf(targets);
    }

    private static Set<Integer> activeReactorClaims(AgentLpqSession session, int mapId) {
        Set<Integer> claims = new LinkedHashSet<>();
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant != null && participant.getMapId() == mapId
                    && member.reactorTargetMapId() == mapId
                    && member.reactorTargetObjectId() != 0) {
                claims.add(member.reactorTargetObjectId());
            }
        }
        return claims;
    }

    private static boolean allLoosePassesCollected(AgentLpqSession session, int stage, long nowMs) {
        Set<MapleMap> maps = collectionMaps(session, stage);
        int before = maps.stream().mapToInt(map -> unpickedPassDropCount(map.getDroppedItems())).sum();
        if (before == 0) {
            session.observeLoosePasses(0, nowMs);
            return true;
        }
        long inactiveForMs = session.observeLoosePasses(before, nowMs);
        Map<Integer, Set<Integer>> claimsByMap = new LinkedHashMap<>();
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent != null && entry != null && maps.contains(agent.getMap())) {
                Set<Integer> mapClaims = claimsByMap.computeIfAbsent(
                        agent.getMapId(), ignored -> new LinkedHashSet<>());
                collectNearestDrop(entry, agent, Set.of(AgentLpqDefinition.PASS), mapClaims);
            }
        }
        int after = maps.stream().mapToInt(map -> unpickedPassDropCount(map.getDroppedItems())).sum();
        if (after < before) {
            session.markProgress(nowMs);
            session.observeLoosePasses(after, nowMs);
        }
        if (after > 0 && inactiveForMs >= recovery(stage).missingPassMs()) {
            Character leader = character(session.eventLeaderId());
            int vacuumed = leader == null ? 0 : vacuumPassDrops(session, "loose-passes", leader, maps);
            int remaining = maps.stream()
                    .mapToInt(map -> unpickedPassDropCount(map.getDroppedItems())).sum();
            if (vacuumed > 0) {
                session.markProgress(nowMs);
                session.observeLoosePasses(remaining, nowMs);
                log.warn("LPQ Stage {} loose-pass recovery vacuumed {} stranded drops "
                                + "after {}ms of natural collection: session={} remaining={}",
                        stage, vacuumed, inactiveForMs, session.sessionId(), remaining);
            }
            return remaining == 0;
        }
        return after == 0;
    }

    private static long splitRoomCompletionInactivity(
            AgentLpqSession session, int stage, int partyPasses, long nowMs) {
        Set<MapleMap> maps = collectionMaps(session, stage);
        int liveMobs = maps.stream().mapToInt(map -> map.getAllMonsters().size()).sum();
        long liveMobHp = maps.stream().flatMap(map -> map.getAllMonsters().stream())
                .mapToLong(Monster::getHp).sum();
        return session.observePassRecoveryProgress(
                partyPasses, liveMobs, activeReactorCount(session, maps), liveMobHp, nowMs);
    }

    static int unpickedPassDropCount(Collection<MapItem> drops) {
        if (drops == null) return 0;
        return (int) drops.stream()
                .filter(java.util.Objects::nonNull)
                .filter(drop -> !drop.isPickedUp() && drop.getItemId() == AgentLpqDefinition.PASS)
                .count();
    }

    static boolean collectNearestDrop(
            AgentRuntimeEntry entry, Character agent, Set<Integer> itemIds,
            Set<Integer> claimedObjectIds) {
        return collectNearestDrop(entry, agent, itemIds, claimedObjectIds, ignored -> true);
    }

    private static boolean collectNearestDrop(
            AgentRuntimeEntry entry, Character agent, Set<Integer> itemIds,
            Set<Integer> claimedObjectIds, IntPredicate allowedItem) {
        if (entry == null || agent == null || agent.getMap() == null) return false;
        Set<Integer> claimed = claimedObjectIds == null ? Set.of() : claimedObjectIds;
        MapItem target = agent.getMap().getDroppedItems().stream()
                .filter(java.util.Objects::nonNull)
                .filter(drop -> !drop.isPickedUp() && !claimed.contains(drop.getObjectId()))
                .filter(drop -> itemIds == null || itemIds.contains(drop.getItemId()))
                .filter(drop -> allowedItem == null || allowedItem.test(drop.getItemId()))
                .filter(drop -> mayLootInRun(agent, drop))
                .min(Comparator.comparingDouble(drop ->
                        drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (target == null) return false;
        return collectDrop(entry, agent, target, claimedObjectIds);
    }

    private static boolean collectStageTwoPassDrop(
            AgentLpqSession session, AgentRuntimeEntry entry, Character agent,
            Set<Integer> claimedObjectIds) {
        if (session == null || entry == null || agent == null || agent.getMap() == null) {
            return false;
        }
        Set<Integer> claimed = claimedObjectIds == null ? Set.of() : claimedObjectIds;
        Map<Integer, Point> activeAgentPositions = session.members().stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(participant -> participant.getMap() == agent.getMap())
                .filter(participant -> participant.getPosition() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Character::getId, Character::getPosition, (first, ignored) -> first));
        MapItem target = agent.getMap().getDroppedItems().stream()
                .filter(java.util.Objects::nonNull)
                .filter(drop -> !drop.isPickedUp()
                        && drop.getItemId() == AgentLpqDefinition.PASS
                        && !claimed.contains(drop.getObjectId()))
                .filter(drop -> mayLootInRun(agent, drop))
                .filter(drop -> nearestAgentIdToDrop(
                        activeAgentPositions, drop.getPosition()) == agent.getId())
                .min(Comparator.comparingDouble(drop ->
                        drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (target == null) return false;
        return collectDrop(entry, agent, target, claimedObjectIds);
    }

    static int nearestAgentIdToDrop(Map<Integer, Point> agentPositions, Point dropPosition) {
        if (agentPositions == null || agentPositions.isEmpty() || dropPosition == null) return 0;
        return agentPositions.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .min(Comparator
                        .comparingDouble((Map.Entry<Integer, Point> entry) ->
                                entry.getValue().distanceSq(dropPosition))
                        .thenComparingInt(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(0);
    }

    private static boolean collectDrop(
            AgentRuntimeEntry entry, Character agent, MapItem target,
            Set<Integer> claimedObjectIds) {
        if (claimedObjectIds != null) claimedObjectIds.add(target.getObjectId());
        int radius = server.agents.runtime.AgentRuntimeConfig.cfg.LOOT_RADIUS;
        Point position = target.getPosition();
        if (Math.abs(position.x - agent.getPosition().x) <= radius
                && Math.abs(position.y - agent.getPosition().y) <= radius) {
            ACTIONS.stop(entry);
            agent.pickupItem(target);
        } else {
            ACTIONS.navigate(entry, position, true);
        }
        return true;
    }

    /** Collects only drops already lying at the character's feet; this is not a vacuum. */
    private static int collectNearbyDrops(
            AgentRuntimeEntry entry, Character agent, Set<Integer> itemIds) {
        if (entry == null || agent == null || agent.getMap() == null
                || agent.getPosition() == null) return 0;
        int radius = server.agents.runtime.AgentRuntimeConfig.cfg.LOOT_RADIUS;
        List<MapItem> nearby = agent.getMap().getDroppedItems().stream()
                .filter(java.util.Objects::nonNull)
                .filter(drop -> !drop.isPickedUp())
                .filter(drop -> itemIds == null || itemIds.contains(drop.getItemId()))
                .filter(drop -> Math.abs(drop.getPosition().x - agent.getPosition().x) <= radius
                        && Math.abs(drop.getPosition().y - agent.getPosition().y) <= radius)
                .toList();
        if (nearby.isEmpty()) return 0;
        ACTIONS.stop(entry);
        int collected = 0;
        for (MapItem drop : nearby) {
            if (drop.isPickedUp()) continue;
            agent.pickupItem(drop);
            collected++;
        }
        return collected;
    }

    private static boolean stageMapObjectivesExhausted(Character agent) {
        return agent != null && activeReactors(agent).isEmpty()
                && agent.getMap().getAllMonsters().isEmpty()
                && unpickedPassDropCount(agent.getMap().getDroppedItems()) == 0;
    }

    private static Point stageNpcPosition(AgentLpqSession session, int stage) {
        int mapId = AgentLpqDefinition.stage(stage).mapId();
        Character observer = session.members().stream()
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(member -> member.getMapId() == mapId)
                .findFirst().orElse(null);
        return observer == null ? null
                : ACTIONS.npcPosition(observer, AgentLpqDefinition.stage(stage).npcId());
    }

    private static void rallyMemberAtNpc(
            AgentLpqMemberState member, Character agent, AgentRuntimeEntry entry, Point npc) {
        if (agent == null || entry == null || npc == null) return;
        if (member != null) {
            member.assign(AgentLpqMemberState.Role.NPC_RALLY, 0);
        }
        Point finalTarget = balloonRallyTarget(agent, npc);
        Point target = finalTarget;
        Point stageTwoWaypoint = AgentLpqStageTwoRallyRoutePolicy.nextWaypoint(
                agent.getMap(), agent.getPosition(), member);
        int nextMapId = AgentLpqDefinition.nextTraversalMap(agent.getMapId());
        Point authoredWaypoint = nextMapId == 0 ? null
                : AgentLpqExitRoutePolicy.nextWaypoint(
                agent.getMapId(), nextMapId, agent.getPosition(), finalTarget);
        if (stageTwoWaypoint != null) target = stageTwoWaypoint;
        else if (authoredWaypoint != null) target = authoredWaypoint;
        if (near(agent.getPosition(), finalTarget, 30)) {
            ACTIONS.stop(entry);
            return;
        }
        ACTIONS.navigate(entry, target, true);
    }

    /**
     * Keeps every rally slot on the same authored foothold as the balloon. Grounding an
     * offset just outside that foothold can otherwise fall through to a lower platform;
     * Stage 1 exposed this deterministically for LPQer06 at x=-181 beside foothold 320,
     * whose left edge is x=-178.
     */
    private static Point balloonRallyTarget(Character agent, Point npc) {
        int offset = Math.floorMod(agent.getId() * 37, 81) - 40;
        Point desired = new Point(npc.x + offset, npc.y);
        Foothold balloonFoothold = agent == null || agent.getMap() == null
                ? null : agent.getMap().getFootholds().findBelow(npc);
        if (balloonFoothold != null) {
            int minX = Math.min(balloonFoothold.getX1(), balloonFoothold.getX2());
            int maxX = Math.max(balloonFoothold.getX1(), balloonFoothold.getX2());
            if (maxX - minX > 16 && npc.x >= minX && npc.x <= maxX) {
                int targetX = Math.clamp(desired.x, minX + 8, maxX - 8);
                int width = balloonFoothold.getX2() - balloonFoothold.getX1();
                int targetY = width == 0 ? balloonFoothold.getY1()
                        : balloonFoothold.getY1()
                        + (int) Math.round((double) (targetX - balloonFoothold.getX1())
                        * (balloonFoothold.getY2() - balloonFoothold.getY1()) / width);
                return new Point(targetX, targetY);
            }
        }
        Point grounded = ACTIONS.groundPoint(agent == null ? null : agent.getMap(), desired);
        return grounded == null ? desired : grounded;
    }

    /**
     * The balloon grace belongs to the whole stage, not to each member's movement history.
     * Stages 1, 3 and 7 are already entirely on their main map when the pass total is ready;
     * split stages start this clock only after every room/scout has returned. Until the cap,
     * every Agent keeps using ordinary navigation. At the cap only remaining laggards are
     * placed at their visible balloon slot so coupon transfer cannot stall indefinitely.
     */
    private static void recoverBalloonRallyPositions(
            AgentLpqSession session, int stage, int mapId, Point npc,
            long rallyElapsedMs, boolean agentLeader, long nowMs) {
        if (!balloonRallyPlacementRecoveryDue(
                stage, rallyElapsedMs, agentLeader)) return;
        List<String> recoveredMembers = new ArrayList<>();
        Set<Integer> stageTwoScouts = stage == 2
                ? Set.copyOf(stageTwoScoutIds(session.members(), session.eventLeaderId(),
                AgentLpqDefinition.STAGE_2_SCOUT_COUNT)) : Set.of();
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT
                    || member.balloonRallyRecoveredFor(stage)) continue;
            Character participant = character(member.characterId());
            AgentRuntimeEntry participantEntry = entry(member.characterId());
            if (participant == null || participantEntry == null
                    || participant.getMapId() != mapId
                    || near(participant.getPosition(), npc, INTERACTION_RADIUS)) continue;
            Point target = balloonRallyTarget(participant, npc);
            Point source = participant.getPosition() == null
                    ? null : new Point(participant.getPosition());
            ACTIONS.stop(participantEntry);
            rescueTeleport(session, "balloon-rally", participantEntry, participant, target);
            member.markBalloonRallyRecovered(stage);
            recoveredMembers.add(participant.getName() + "(" + participant.getId() + ")"
                    + " role=" + member.role()
                    + " scout=" + stageTwoScouts.contains(participant.getId())
                    + " observed=" + ACTIONS.observedByPlayer(participant)
                    + " from=" + source + " to=" + target);
        }
        if (recoveredMembers.isEmpty()) return;
        session.markProgress(nowMs);
        log.warn("LPQ Stage {} party rally recovery moved {} lagging members to the "
                        + "balloon after {}ms: session={} members={}",
                stage, recoveredMembers.size(), rallyElapsedMs, session.sessionId(),
                recoveredMembers);
    }

    /**
     * Final collection-stage backstop. The party gets one full natural-navigation window,
     * then one full window after visible balloon placement recovery. If it is still unable
     * to perform the ordinary drop-and-loot handoff, consolidate only real passes already
     * owned by this session (including its ground drops) onto the leader. The caller then
     * performs the normal authored NPC submission and stage transition.
     */
    static void forcePassTransferToLeader(
            AgentLpqSession session, String site, Character leader, AgentLpqDefinition.Stage contract,
            long rallyElapsedMs, long nowMs) {
        if (AgentLpqRescue.withhold(session, AgentLpqRescue.Kind.FORCE_TRANSFER, site, leader,
                "stage=" + contract.number())) return;
        int required = contract.submissionCount();
        int leaderPasses = leader.getItemQuantity(AgentLpqDefinition.PASS, false);
        if (leaderPasses < required) {
            vacuumPassDrops(session, site, leader, collectionMaps(session, contract.number()));
            leaderPasses = leader.getItemQuantity(AgentLpqDefinition.PASS, false);
        }

        int transferred = 0;
        for (AgentLpqMemberState member : session.members()) {
            if (leaderPasses >= required || member.characterId() == leader.getId()) break;
            Character participant = character(member.characterId());
            if (participant == null) continue;
            int held = participant.getItemQuantity(AgentLpqDefinition.PASS, false);
            int quantity = Math.min(held, required - leaderPasses);
            if (quantity <= 0) continue;
            if (!AgentInventoryGatewayRuntime.inventory().addItem(
                    leader, AgentLpqDefinition.PASS, (short) quantity)) {
                if (transferred > 0) {
                    AgentLpqRescue.note(session, AgentLpqRescue.Kind.FORCE_TRANSFER, site, leader,
                            "passes=" + transferred + " stage=" + contract.number());
                }
                AgentLpqTerminationService.fail(session,
                        "LPQ leader has no ETC space for forced pass transfer", nowMs);
                return;
            }
            AgentInventoryGatewayRuntime.inventory().removeById(
                    participant, InventoryType.ETC, AgentLpqDefinition.PASS,
                    quantity, false, false);
            if (contract.number() == 5) member.handOffStageFivePasses(quantity);
            transferred += quantity;
            leaderPasses += quantity;
        }
        if (transferred > 0) {
            AgentLpqRescue.note(session, AgentLpqRescue.Kind.FORCE_TRANSFER, site, leader,
                    "passes=" + transferred + " stage=" + contract.number());
        }
        if (transferred <= 0 && leaderPasses < required) return;
        session.markProgress(nowMs);
        log.warn("LPQ Stage {} final rally recovery force-transferred {} real passes "
                        + "to leader after {}ms: session={} leader={}({}) leaderPasses={}/{}",
                contract.number(), transferred, rallyElapsedMs, session.sessionId(),
                leader.getName(), leader.getId(), leaderPasses, required);
    }

    private static boolean rallyPartyAtNpc(
            AgentLpqSession session, Character leader, int stage, long nowMs) {
        Point npc = ACTIONS.npcPosition(leader, AgentLpqDefinition.stage(stage).npcId());
        if (npc == null) return false;
        boolean gathered = true;
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant == null || participant.getMapId() != AgentLpqDefinition.stage(stage).mapId()) {
                gathered = false;
                continue;
            }
            if (near(participant.getPosition(), npc, INTERACTION_RADIUS)) continue;
            gathered = false;
            if (member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                rallyMemberAtNpc(member, participant, entry(member.characterId()), npc);
            }
        }
        return gathered;
    }

    private static void grindReachableStageOneMobs(AgentRuntimeEntry entry, Character agent) {
        var current = AgentGrindTargetStateRuntime.target(entry);
        if (current != null && !AgentCombatTargetRuntime.isReachableGrindTarget(entry, agent, current)) {
            AgentGrindTargetStateRuntime.clear(entry);
        }
        Set<Integer> stageTargets = stageCombatTargets(1, ACTIONS.configuredMonsterSpawnIds(agent));
        ACTIONS.grind(entry, stageTargets, Set.of());
    }

    private static void announceStageOneMeleeIntent(
            AgentLpqSession session, AgentLpqMemberState member, Character agent) {
        if (member.memberType() != AgentLpqMemberState.MemberType.AGENT
                || !stageOneMelee(agent)
                || !member.claimIntentAnnouncement("stage1:upper-melee")) return;
        sendPartyAndAllChat(agent, voice(session, null, "Stage 1: I'm climbing to clear every reachable upper mob as a "
                + jobLabel(agent) + "."));
    }

    static boolean stageOneMelee(Character agent) {
        if (agent == null || agent.getJob() == null) return false;
        Job job = agent.getJob();
        return job.isA(Job.WARRIOR) || job.isA(Job.BANDIT);
    }

    private static void announceRoomIntent(
            AgentLpqSession session, AgentLpqMemberState member, Character agent,
            int stage, int roomMapId, AgentLpqMemberState.Role role, boolean helper) {
        if (agent == null || member.memberType() != AgentLpqMemberState.MemberType.AGENT
                || !member.claimIntentAnnouncement("stage" + stage + ":room:" + roomMapId
                + (helper ? ":helper" : ""))) return;
        String room = String.valueOf(roomMapId % 1_000);
        String purpose;
        if (stage == 4) {
            purpose = AgentLpqDefinition.STAGE_4_MAGIC_ROOMS.contains(roomMapId)
                    ? "magic-weak mob room " : "physical-weak mob room ";
            if (helper) purpose = "help finish " + purpose;
        } else if (role == AgentLpqMemberState.Role.TELEPORT_RUNNER) {
            purpose = "Teleport-required room ";
        } else if (role == AgentLpqMemberState.Role.DARK_SIGHT_RUNNER) {
            purpose = "Dark Sight-required room ";
        } else {
            purpose = "regular room ";
        }
        sendPartyAndAllChat(agent, voice(session, null, "Stage " + stage + ": I'm going to " + purpose + room
                + " as a " + jobLabel(agent) + "."));
    }

    private static void announceStageSevenIntent(
            AgentLpqSession session, AgentLpqMemberState member,
            Character participant, int topIndex) {
        if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) return;
        String key = topIndex >= 0 ? "stage7:top:" + topIndex : "stage7:bottom";
        if (!member.claimIntentAnnouncement(key)) return;
        sendPartyAndAllChat(participant, voice(session, null, topIndex >= 0
                ? "Stage 7: I'm going up to kill trigger Ratz " + (topIndex + 1)
                + " as a " + jobLabel(participant) + "."
                : "Stage 7: I'm holding the bottom to kill Rombards as a "
                + jobLabel(participant) + "."));
    }

    private static void announcePassProgress(
            AgentLpqSession session, AgentLpqMemberState member, Character agent,
            int stage, int mapId, int localCount, int localQuota, int partyQuota) {
        if (session.hasHumanMember() || agent == null
                || member.memberType() != AgentLpqMemberState.MemberType.AGENT
                || !member.shouldReportPassProgress(
                stage, mapId, localCount, localQuota)) return;
        int partyCount = partyPassCount(session);
        String location = AgentLpqDefinition.roomMaps(stage).contains(mapId)
                ? "room " + (mapId % 1_000) + " " : "";
        sendPartyAndAllChat(agent, "Stage " + stage + ": " + location + "passes "
                + localCount + "/" + localQuota + "; party "
                + partyCount + "/" + partyQuota + ".");
    }

    private static String jobLabel(Character character) {
        return character == null || character.getJob() == null ? "member"
                : AgentDialogueReportFormatter.jobDisplayName(character.getJob());
    }

    private static void stageSevenObjectives(AgentLpqSession session, long nowMs) {
        int mapId = AgentLpqDefinition.stage(7).mapId();
        List<Integer> topIds = stageSevenTopMemberIds(
                session.members(), id -> AgentLpqRosterRequirementPolicy.stageSevenBoxWacker(character(id)),
                session.eventLeaderId(), preferredStageSevenHuman(session));
        Character observer = session.members().stream().map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull).filter(member -> member.getMapId() == mapId)
                .findFirst().orElse(null);
        List<Monster> liveTriggerRatz = observer == null ? List.of() : observer.getMap().getAllMonsters().stream()
                .filter(monster -> AgentLpqDefinition.STAGE_7_TRIGGER_MOBS.contains(monster.getId()))
                .filter(monster -> monster.getHp() > 0)
                .sorted(Comparator.comparingInt(
                        (Monster monster) -> monster.getPosition().y).reversed())
                .toList();
        List<Reactor> liveTriggerReactors = observer == null ? List.of() : activeReactors(observer).stream()
                .filter(reactor -> reactor.getId() == AgentLpqDefinition.STAGE_7_TRIGGER_REACTOR)
                .toList();
        boolean rombardAlive = observer != null && ACTIONS.liveMonsterCount(
                observer, Set.of(AgentLpqDefinition.ROMBARD)) > 0;
        boolean combatCleared = observer != null && stageSevenCombatCleared(
                liveTriggerRatz.size(), liveTriggerReactors.size(), rombardAlive ? 1 : 0);
        long combatClearedForMs = session.observeStage7CombatCleared(combatCleared, nowMs);
        Point npc = observer == null ? null : ACTIONS.npcPosition(
                observer, AgentLpqDefinition.stage(7).npcId());
        if (session.companionAdopted()) sayStageSevenRoles(session, topIds);
        Set<Integer> stageSevenLootClaims = new LinkedHashSet<>();
        for (AgentLpqMemberState member : session.members()) {
            int topIndex = topIds.indexOf(member.characterId());
            if (topIndex >= 0) {
                member.assign(AgentLpqMemberState.Role.RANGED_TRIGGER, mapId);
                member.assignPlatform(topIndex + 1);
            } else {
                member.assign(AgentLpqMemberState.Role.BOSS_ATTACKER, mapId);
                member.assignPlatform(0);
            }

            Character participant = character(member.characterId());
            if (participant == null || participant.getMapId() != mapId) continue;
            announceStageSevenIntent(session, member, participant, topIndex);
            if (member.memberType() == AgentLpqMemberState.MemberType.HUMAN) {
                if (nowMs >= member.nextActionAtMs()) {
                    String humanLine = voice(session, null, topIndex >= 0
                            ? "LPQ Stage 7: take the upper platforms and kill trigger " + (topIndex + 1) + "."
                            : "LPQ Stage 7: hold the bottom and kill the summoned Rombards.");
                    if (humanLine != null) participant.dropMessage(6, humanLine);
                    member.deferUntil(nowMs + 5_000L);
                }
                continue;
            }

            AgentRuntimeEntry entry = entry(member.characterId());
            if (entry == null) continue;
            if (combatCleared && session.companionAdopted()
                    && collectStageTwoPassDrop(session, entry, participant, stageSevenLootClaims)) {
                // A companion run: the nearest companion walks to each natural pass and picks it up.
                continue;
            }
            if (combatCleared) {
                if (member.characterId() != session.eventLeaderId() && npc != null) {
                    rallyMemberAtNpc(member, participant, entry, npc);
                }
                continue;
            }
            if (topIndex >= 0) {
                Monster triggerRatz = stageSevenTriggerForWorker(
                        liveTriggerRatz, topIndex, topIds.size());
                if (triggerRatz == null) {
                    // A killed Ratz drops the authored trigger item onto its box. The
                    // server activates that item reactor after its normal five-second
                    // delay, so ranged workers wait instead of striking the box itself.
                    if (npc != null) rallyMemberAtNpc(member, participant, entry, npc);
                    continue;
                }
                attackStageSevenTriggerRatz(
                        session, entry, participant, nowMs, member, triggerRatz);
                continue;
            }

            if (ACTIONS.liveMonsterCount(participant, Set.of(AgentLpqDefinition.ROMBARD)) > 0) {
                ACTIONS.grind(entry, Set.of(AgentLpqDefinition.ROMBARD));
            } else if (npc != null) {
                rallyMemberAtNpc(member, participant, entry, npc);
            } else {
                ACTIONS.stop(entry);
            }
        }
        if (combatCleared) stageSevenLeaderLootSweep(session, combatClearedForMs, nowMs);
    }

    private static boolean attackStageSevenTriggerRatz(
            AgentLpqSession session, AgentRuntimeEntry entry, Character agent, long nowMs,
            AgentLpqMemberState member, Monster triggerRatz) {
        if (nowMs < member.nextActionAtMs()) return true;
        Point firingAnchor = stageSevenFiringAnchor(triggerRatz.getPosition());
        if (firingAnchor == null) return false;
        if (!stageSevenFiringReady(
                agent.getPosition(), ACTIONS.grounded(agent), firingAnchor)) {
            ACTIONS.navigate(entry, firingAnchor, true);
            return true;
        }
        ACTIONS.stop(entry);
        ACTIONS.facePosition(agent, triggerRatz.getPosition());
        int hpBefore = triggerRatz.getHpAsInt();
        AgentAttackPlan attackPlan = AgentCombatPlanRuntime.planAttack(
                entry, agent, triggerRatz, AgentCombatConfig.cfg);
        if (attackPlan == null) {
            attackPlan = authoredStageSevenTriggerAttackPlan(agent, triggerRatz);
        }
        if (attackPlan == null) {
            ACTIONS.stop(entry);
            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
            if (member.claimIntentAnnouncement("stage7:ratz-no-ranged-plan")) {
                log.warn("LPQ Stage 7 trigger has no legal ranged attack plan from "
                                + "its authored firing ledge: session={} member={}({}) mob={}",
                        session.sessionId(), agent.getName(), agent.getId(), triggerRatz.getId());
            }
            return true;
        }
        AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackMonster(
                entry, agent, attackPlan);
        if (result.committed()) {
            log.info("LPQ Stage 7 ranged Ratz attack: session={} member={}({}) mob={} oid={} "
                            + "agentPosition={} firingAnchor={} ratzPosition={} hpBefore={} hpAfter={} "
                            + "route={} skill={}",
                    session.sessionId(), agent.getName(), agent.getId(), triggerRatz.getId(),
                    triggerRatz.getObjectId(), agent.getPosition(), firingAnchor,
                    triggerRatz.getPosition(), hpBefore, triggerRatz.getHp(),
                    attackPlan.route, attackPlan.skillId);
        }
        if (result.status() == AgentAttackTransactionResult.Status.REJECTED) {
            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
            if (member.claimIntentAnnouncement("stage7:ratz-rejected:" + result.reason())) {
                log.warn("LPQ Stage 7 Ratz attack rejected: session={} member={}({}) mob={} reason={}",
                        session.sessionId(), agent.getName(), agent.getId(),
                        triggerRatz.getId(), result.reason());
            }
        }
        if (triggerRatz.getHp() < hpBefore) session.markProgress(nowMs);
        member.observeRoomCombatTarget(
                AgentLpqDefinition.stage(7).mapId(), triggerRatz.getObjectId(),
                triggerRatz.getHpAsInt(), nowMs);
        return true;
    }

    /**
     * Stage 7's trigger Blocktopus sit just outside the conservative vertical band
     * used for ordinary grinding. Keep the agent grounded on the authored opposite
     * ledge and extend only this LPQ objective's normal ranged basic attack to the
     * selected trigger. This avoids the former stationary jump-shot loop.
     */
    private static AgentAttackPlan authoredStageSevenTriggerAttackPlan(
            Character agent, Monster trigger) {
        if (agent == null || trigger == null || agent.getPosition() == null
                || trigger.getPosition() == null) return null;
        AgentAttackExecutionProvider.BasicAttackData attack =
                AgentAttackExecutionProvider.buildBasicAttackData(agent, trigger.getPosition());
        if (attack == null || attack.route() != AgentAttackRoute.RANGED) return null;
        Rectangle hitBox = authoredStageSevenTriggerHitBox(
                attack.hitBox(), trigger.getPosition());
        return new AgentAttackPlan(0, 0,
                AgentCombatHitCounter.packetSafeHitCount(agent, attack.route(), 1),
                hitBox, List.of(trigger), attack.route(), attack.display(), attack.direction(),
                attack.rangedDirection(), attack.stance(), attack.speed(), attack.hitDelayMs(),
                attack.cooldownMs(), AgentCombatWeaponPolicy.damageWeaponTypeForAction(
                0, AgentAttackExecutionProvider.getEquippedWeaponType(agent), attack.action()));
    }

    static Rectangle authoredStageSevenTriggerHitBox(
            Rectangle ordinaryHitBox, Point triggerPosition) {
        if (triggerPosition == null) {
            throw new IllegalArgumentException("trigger position is required");
        }
        Rectangle triggerPoint = new Rectangle(triggerPosition.x, triggerPosition.y, 1, 1);
        return ordinaryHitBox == null ? triggerPoint : ordinaryHitBox.union(triggerPoint);
    }

    private static Monster stageSevenTriggerForWorker(
            List<Monster> liveTriggerRatz, int topIndex, int workerCount) {
        if (liveTriggerRatz == null || liveTriggerRatz.isEmpty()) return null;
        for (int priorityY : stageSevenRatzPriorityYs(topIndex, workerCount)) {
            Monster target = liveTriggerRatz.stream()
                    .filter(monster -> monster != null && monster.getHp() > 0
                            && monster.getPosition() != null)
                    .min(Comparator.comparingInt(monster ->
                            Math.abs(monster.getPosition().y - priorityY)))
                    .orElse(null);
            if (target != null && Math.abs(target.getPosition().y - priorityY) <= 64) {
                return target;
            }
        }
        return null;
    }

    static Point stageSevenFiringAnchor(Point ratzPosition) {
        if (ratzPosition == null) return null;
        int authoredY = List.of(-1_044, -1_276, -1_543).stream()
                .min(Comparator.comparingInt(y -> Math.abs(ratzPosition.y - y)))
                .orElse(0);
        if (Math.abs(ratzPosition.y - authoredY) > 64) return null;
        return switch (authoredY) {
            case -1_044 -> new Point(-240, -990);
            case -1_276 -> new Point(-240, -1_263);
            case -1_543 -> new Point(-240, -1_469);
            default -> null;
        };
    }

    static boolean stageSevenFiringReady(
            Point position, boolean grounded, Point firingAnchor) {
        return grounded && near(position, firingAnchor, STAGE_7_FIRING_ANCHOR_RADIUS);
    }

    static boolean stageSevenCombatCleared(
            int liveTriggerRatz, int liveTriggerReactors, int liveRombards) {
        return liveTriggerRatz == 0 && liveTriggerReactors == 0 && liveRombards == 0;
    }

    static List<Integer> stageSevenRatzPriorityYs(int topIndex) {
        return stageSevenRatzPriorityYs(topIndex, 2);
    }

    static List<Integer> stageSevenRatzPriorityYs(int topIndex, int workerCount) {
        if (workerCount <= 1) {
            return topIndex == 0 ? List.of(-1_044, -1_276, -1_543) : List.of();
        }
        return switch (topIndex) {
            case 0 -> List.of(-1_044, -1_276);
            case 1 -> List.of(-1_543);
            default -> List.of();
        };
    }

    static List<Integer> stageSevenTopMemberIds(Collection<AgentLpqMemberState> members,
                                                IntPredicate rangedAttack,
                                                int eventLeaderId) {
        return stageSevenTopMemberIds(members, rangedAttack, eventLeaderId, 0);
    }

    static List<Integer> stageSevenTopMemberIds(Collection<AgentLpqMemberState> members,
                                                IntPredicate rangedAttack,
                                                int eventLeaderId,
                                                int preferredHumanId) {
        if (members == null || rangedAttack == null) return List.of();
        List<Integer> selected = new ArrayList<>();
        if (preferredHumanId > 0) {
            members.stream().filter(member -> member.characterId() == preferredHumanId)
                    .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.HUMAN)
                    .filter(member -> rangedAttack.test(member.characterId()))
                    .findFirst().ifPresent(member -> selected.add(member.characterId()));
        }
        members.stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .filter(member -> rangedAttack.test(member.characterId()))
                .sorted(Comparator
                        .comparing((AgentLpqMemberState member) ->
                                member.characterId() == eventLeaderId)
                        .thenComparingInt(AgentLpqMemberState::characterId))
                .limit(Math.max(0, 2 - selected.size()))
                .map(AgentLpqMemberState::characterId)
                .forEach(selected::add);
        return List.copyOf(selected);
    }

    private static int preferredStageSevenHuman(AgentLpqSession session) {
        return session.humanRolePreference() == AgentLpqSession.HumanRolePreference.RANGED
                ? session.preferredHumanId() : 0;
    }

    private static void stageSevenLeaderLootSweep(AgentLpqSession session,
                                                   long combatClearedForMs, long nowMs) {
        Character leader = character(session.eventLeaderId());
        if (leader == null || leader.getMapId() != AgentLpqDefinition.stage(7).mapId()) return;
        AgentLpqMemberState leaderState = session.member(leader.getId());
        if (leaderState != null) {
            announcePassProgress(session, leaderState, leader, 7, leader.getMapId(),
                    leader.getItemQuantity(AgentLpqDefinition.PASS, false), 3, 3);
        }
        AgentRuntimeEntry leaderEntry = entry(leader.getId());
        if (leaderEntry != null && collectNearestDrop(leaderEntry, leader,
                Set.of(AgentLpqDefinition.PASS), new LinkedHashSet<>())) return;
        int sweepIndex = session.stage7LootSweepIndex();
        if (leaderEntry != null && sweepIndex < STAGE_7_LOOT_SWEEP.size()) {
            Point target = STAGE_7_LOOT_SWEEP.get(sweepIndex);
            if (near(leader.getPosition(), target, INTERACTION_RADIUS)) {
                session.advanceStage7LootSweep(nowMs);
            } else {
                ACTIONS.navigate(leaderEntry, target, true);
            }
        }
        boolean sweepComplete = session.stage7LootSweepIndex() >= STAGE_7_LOOT_SWEEP.size();
        if (sweepComplete && leaderEntry != null) {
            Point npc = ACTIONS.npcPosition(leader, AgentLpqDefinition.stage(7).npcId());
            if (npc != null) rallyMemberAtNpc(leaderState, leader, leaderEntry, npc);
        }
        if (session.stage7ForceLootAttempted() || !sweepComplete
                || combatClearedForMs < STAGE_7_FORCE_LOOT_DELAY_MS) return;
        int vacuumed = vacuumPassDrops(session, "stage7-leader-sweep", leader, Set.of(leader.getMap()));
        session.markStage7ForceLootAttempted(nowMs);
        log.info("LPQ Stage 7 verified leader sweep: session={} leader={}({}) "
                        + "sweepComplete={} clearForMs={} vacuumedPasses={}",
                session.sessionId(), leader.getName(), leader.getId(),
                sweepComplete, combatClearedForMs, vacuumed);
    }

    private static void splitRooms(AgentLpqSession session, int stage, long nowMs) {
        int mainMap = AgentLpqDefinition.stage(stage).mapId();
        List<Integer> rooms = AgentLpqDefinition.roomMaps(stage);
        // An occupant is authoritative evidence of progress and must not lose its room lease.
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant == null) continue;
            if (member.memberType() == AgentLpqMemberState.MemberType.HUMAN
                    && participant.getMapId() == mainMap && member.assignedMapId() != 0
                    && member.observeRoomApproachProgress(
                    member.assignedMapId(), participant.getPosition())) {
                session.rooms().markProgress(member.assignedMapId(), nowMs);
            }
            if (!rooms.contains(participant.getMapId())) continue;
            if (stage == 5 && member.memberType() == AgentLpqMemberState.MemberType.HUMAN
                    && member.assignedMapId() == participant.getMapId()
                    && suitable(member.characterId(), stage, participant.getMapId())) {
                Integer owner = session.rooms().owner(participant.getMapId());
                if (owner != null && owner != participant.getId()) {
                    session.rooms().release(participant.getMapId());
                    AgentLpqMemberState displaced = session.member(owner);
                    if (displaced != null) displaced.assign(AgentLpqMemberState.Role.GENERAL, 0);
                }
                session.rooms().reserve(participant.getMapId(), participant.getId(), nowMs);
            }
            session.rooms().markEntered(participant.getMapId());
            if (session.rooms().completed(participant.getMapId())) continue;
            if (java.util.Objects.equals(session.rooms().owner(participant.getMapId()), participant.getId())) {
                session.rooms().markProgress(participant.getMapId(), nowMs);
            }
            if (stage == 5 && member.memberType() == AgentLpqMemberState.MemberType.HUMAN
                    && java.util.Objects.equals(
                    session.rooms().owner(participant.getMapId()), participant.getId())) {
                observeHumanStageFiveRoomCompletion(session, member, participant, nowMs);
            }
        }
        for (AgentLpqRoomAssignment.ExpiredReservation expired
                : session.rooms().releaseExpired(nowMs, ROOM_LEASE_MS)) {
            AgentLpqMemberState displaced = session.member(expired.characterId());
            if (displaced != null && displaced.assignedMapId() == expired.roomMapId()) {
                displaced.assign(AgentLpqMemberState.Role.GENERAL, 0);
            }
            log.warn("LPQ room lease expired atomically: session={} room={} formerOwner={}",
                    session.sessionId(), expired.roomMapId(), expired.characterId());
        }
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character participant = character(member.characterId());
            if (participant != null && rooms.contains(participant.getMapId())) {
                if (session.rooms().completed(participant.getMapId())) continue;
                Integer owner = session.rooms().owner(participant.getMapId());
                if (suitable(member.characterId(), stage, participant.getMapId())
                        && owner != null && owner != participant.getId()) {
                    session.rooms().release(participant.getMapId());
                    AgentLpqMemberState displaced = session.member(owner);
                    if (displaced != null) displaced.assign(AgentLpqMemberState.Role.GENERAL, 0);
                }
                if (session.rooms().reserve(participant.getMapId(), participant.getId(), nowMs)
                        && member.assignedMapId() != participant.getMapId()) {
                    assignRoom(session, member, roomRole(stage, participant.getMapId()),
                            participant.getMapId(), stage, false);
                }
            }
        }
        assignRooms(session, stage, rooms, nowMs);
        Point npc = stageNpcPosition(session, stage);
        Map<Integer, Set<Integer>> lootClaimsByMap = new LinkedHashMap<>();
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null) continue;
            if (!rooms.contains(agent.getMapId())) member.clearRoomExitProgress();
            if (stage == 5 && tickStageFiveRoomContinuation(
                    session, entry, agent, nowMs)) {
                continue;
            }
            if (stage == 5 && session.companionAdopted() && rooms.contains(agent.getMapId())
                    && companionRoomCeiling(session, member, entry, agent, mainMap, false, nowMs)) {
                continue;
            }
            if (stage == 5 && !session.companionAdopted() && rooms.contains(agent.getMapId())
                    && stageFiveRoomCeilingRecoveryDue(
                    member.roomTimingElapsed(agent.getMapId(), nowMs))) {
                recoverStageFiveRoomAtCeiling(
                        session, member, entry, agent, mainMap, nowMs);
                continue;
            }
            if (rooms.contains(agent.getMapId())) {
                // Map object ids are scoped to one MapleMap. Sharing one bare OID set
                // across all five/six split rooms makes, for example, room 503's pass
                // look claimed merely because room 502 happened to allocate the same
                // object id. Keep the cooperative claim set map-scoped.
                Set<Integer> roomLootClaims = lootClaimsByMap.computeIfAbsent(
                        agent.getMapId(), ignored -> new LinkedHashSet<>());
                if (agent.getMapId() == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM) {
                    if (requiresDarkSightCancellationForRoomLoot(
                            agent.getBuffedValue(BuffStat.DARKSIGHT) != null,
                            passDropWithinPickupRange(agent))) {
                        ACTIONS.stop(entry);
                        cancelDarkSight(agent);
                        member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                        continue;
                    }
                    if (darkSightRefreshDue(agent, nowMs)
                            && roomPassRequiresTraversal(agent)
                            && ACTIONS.grounded(agent)
                            && !AgentMovementStateRuntime.inAir(entry)
                            && !AgentMovementStateRuntime.climbing(entry)) {
                        ACTIONS.stop(entry);
                        if (refreshDarkSight(entry, agent)) {
                            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                        }
                        continue;
                    }
                }
                if (collectNearestDrop(entry, agent,
                        Set.of(AgentLpqDefinition.PASS), roomLootClaims)) continue;
            }
            if (stage == 5 && agent.getMapId() == mainMap
                    && prepareStageFiveMainMapDarkSight(member, entry, agent, nowMs)) {
                continue;
            }
            int assigned = member.assignedMapId();
            if (agent.getMapId() == mainMap && assigned != 0) {
                boolean completed = session.rooms().completed(assigned);
                switch (mainMapRoomStep(completed, session.rooms().owner(assigned), agent.getId(),
                        !completed && isStageFourMagicHelper(session, stage, member, agent, assigned))) {
                    case RELEASE_COMPLETED -> {
                        member.assign(AgentLpqMemberState.Role.GENERAL, 0);
                        member.clearTraversalProgress();
                        if (npc != null) rallyMemberAtNpc(member, agent, entry, npc);
                        continue;
                    }
                    case RELEASE_NOT_OWNER -> {
                        member.assign(AgentLpqMemberState.Role.GENERAL, 0);
                        if (npc != null) rallyMemberAtNpc(member, agent, entry, npc);
                        continue;
                    }
                    case ENTER -> { }
                }
                if (member.observeRoomApproachProgress(assigned, agent.getPosition())) {
                    session.rooms().markProgress(assigned, nowMs);
                }
                markRoomAndEnter(session, member, entry, agent, assigned, nowMs);
                continue;
            }
            if (agent.getMapId() == mainMap && assigned == 0) {
                if (npc != null) rallyMemberAtNpc(member, agent, entry, npc);
                continue;
            }
            if (!rooms.contains(agent.getMapId())) continue;
            int roomMapId = agent.getMapId();
            session.rooms().markProgress(roomMapId, nowMs);
            if (session.rooms().completed(roomMapId)) {
                if (stage == 4) {
                    tickStageFourCompletedRoomExit(session, entry, agent, nowMs);
                    continue;
                }
                member.beginRoomExit(roomMapId, nowMs);
                if (prepareDarkSightRoomExit(
                        stage, roomMapId, entry, agent, member, nowMs)) {
                    continue;
                }
                long exitInactiveMs = traversalInactivity(member, agent, mainMap, nowMs);
                long exitElapsedMs = member.roomExitElapsed(roomMapId, nowMs);
                boolean placementRecoveryDue = exitInactiveMs
                        >= recovery(stage).roomExitPlacementMs()
                        && exitElapsedMs >= recovery(stage).roomExitPlacementMs();
                if (stage == 5 && stageFiveOrdinaryRoom(roomMapId)) {
                    if (!session.companionAdopted()) applyStageFiveUnobservedExitAssist(
                            session, member, entry, agent, mainMap, nowMs);
                    int naturalExitSourceMapId = agent.getMapId();
                    Point naturalExitSourcePosition = agent.getPosition() == null
                            ? null : new Point(agent.getPosition());
                    enterPortalTo(entry, agent, mainMap);
                    if (agent.getMapId() == mainMap) {
                        log.info("LPQ natural room exit completed: session={} stage={} "
                                        + "member={}({}) source={} sourcePosition={} destination={} elapsedMs={}",
                                session.sessionId(), stage, agent.getName(), agent.getId(),
                                naturalExitSourceMapId, naturalExitSourcePosition,
                                mainMap, exitElapsedMs);
                        member.assign(AgentLpqMemberState.Role.GENERAL, 0);
                        member.clearTraversalProgress();
                        member.clearStageFiveAssist();
                        if (npc != null) rallyMemberAtNpc(member, agent, entry, npc);
                    }
                } else if (roomExitPortalPlacementDue(stage, exitInactiveMs, exitElapsedMs)) {
                    forceRoomExitThroughPortal(session, member, entry, agent, mainMap, nowMs);
                } else {
                    if (stage == 4 && placementRecoveryDue
                            && member.claimIntentAnnouncement(
                            "stage4:exit-route-refresh:" + roomMapId)) {
                        ACTIONS.refreshNavigation(entry, agent);
                        log.info("LPQ Stage 4 natural room-exit route refreshed: session={} "
                                        + "member={}({}) room={} inactiveForMs={} elapsedMs={}",
                                session.sessionId(), agent.getName(), agent.getId(),
                                roomMapId, exitInactiveMs, exitElapsedMs);
                    }
                    reportStageFourExitRoute(
                            session, member, entry, agent, mainMap, exitElapsedMs);
                    int naturalExitSourceMapId = agent.getMapId();
                    Point naturalExitSourcePosition = agent.getPosition() == null
                            ? null : new Point(agent.getPosition());
                    enterPortalTo(entry, agent, mainMap);
                    if (agent.getMapId() == mainMap) {
                        log.info("LPQ natural room exit completed: session={} stage={} "
                                        + "member={}({}) source={} sourcePosition={} destination={} elapsedMs={}",
                                session.sessionId(), stage, agent.getName(), agent.getId(),
                                naturalExitSourceMapId, naturalExitSourcePosition,
                                mainMap, exitElapsedMs);
                        member.assign(AgentLpqMemberState.Role.GENERAL, 0);
                        member.clearTraversalProgress();
                        if (npc != null) rallyMemberAtNpc(member, agent, entry, npc);
                    }
                }
                continue;
            }
            if (member.beginRoomTiming(roomMapId, nowMs)) {
                log.info("LPQ room timing started: session={} stage={} map={} member={}({}) role={}",
                        session.sessionId(), stage, roomMapId, agent.getName(),
                        agent.getId(), member.role());
            }
            Integer roomOwner = session.rooms().owner(roomMapId);
            if (stage == 4 && java.util.Objects.equals(roomOwner, agent.getId())) {
                int roomPasses = member.roomPassesCollectedFor(roomMapId,
                        agent.getItemQuantity(AgentLpqDefinition.PASS, false));
                if (applyStageFourRoomRecovery(
                        session, member, entry, agent, roomPasses, nowMs)) {
                    continue;
                }
            }
            boolean stageFourMagicHelper = stage == 4
                    && AgentLpqDefinition.STAGE_4_MAGIC_ROOMS.contains(roomMapId)
                    && member.assignedMapId() == roomMapId
                    && AgentLpqRosterRequirementPolicy.magicAttack(agent);
            if (roomOwner != null && roomOwner != agent.getId() && !stageFourMagicHelper) {
                member.assign(AgentLpqMemberState.Role.GENERAL, 0);
                enterPortalTo(entry, agent, mainMap);
                continue;
            }
            Set<Integer> mobs = stage == 4
                    ? stageFourCombatTargets(roomMapId)
                    : ACTIONS.configuredMonsterSpawnIds(agent);
            boolean darkSightRoom = stage == 5
                    && roomMapId == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM;
            boolean reactorSpawnsMustBeCleared = stage == 4;
            boolean roomMobsMustBeCleared = reactorSpawnsMustBeCleared
                    || (stage == 5 && !darkSightRoom);
            boolean acted;
            if (member.reactorSpawnCleanupPending()) {
                if (reactorSpawnsMustBeCleared && ACTIONS.liveMonsterCount(agent, mobs) > 0) {
                    ACTIONS.grind(entry, mobs);
                    acted = true;
                } else {
                    member.finishReactorSpawnCleanup();
                    acted = hitNearestReactor(
                            entry, agent, nowMs, member, reactorSpawnsMustBeCleared);
                }
            } else {
                acted = hitNearestReactor(
                        entry, agent, nowMs, member, reactorSpawnsMustBeCleared);
            }
            if (!acted && roomMobsMustBeCleared && !mobs.isEmpty()) {
                ACTIONS.grind(entry, mobs);
            }
            // A Stage 5 hazard can eject the Dark Sight runner while this tick is executing.
            // Never complete or release a room using the character's new exit-map id.
            if (agent.getMapId() != roomMapId) continue;
            int activeRoomReactors = activeReactors(agent).size();
            boolean looseRoomPass = AgentMapPerception.items(agent.getMap()).stream()
                    .anyMatch(item -> !item.isPickedUp()
                            && item.getItemId() == AgentLpqDefinition.PASS);
            boolean exhausted = activeRoomReactors == 0
                    && (!roomMobsMustBeCleared || ACTIONS.liveMonsterCount(agent, mobs) == 0)
                    && !looseRoomPass;
            int passQuota = AgentLpqDefinition.roomPassQuota(roomMapId);
            int roomPasses = member.roomPassesCollectedFor(roomMapId,
                    agent.getItemQuantity(AgentLpqDefinition.PASS, false));
            boolean passQuotaMet = roomPasses >= passQuota;
            reportRoomProgress(session, member, agent, roomMapId, activeReactors(agent).size(),
                    ACTIONS.liveMonsterCount(agent, mobs), roomPasses, passQuota, nowMs);
            announcePassProgress(session, member, agent, stage, roomMapId,
                    roomPasses, passQuota, AgentLpqDefinition.stage(stage).submissionCount());
            if (exhausted && passQuotaMet) {
                long roomDurationMs = member.finishRoomTiming(roomMapId, nowMs);
                log.info("LPQ room timing completed: session={} stage={} map={} member={}({}) "
                                + "role={} durationMs={} passes={}/{}",
                        session.sessionId(), stage, roomMapId, agent.getName(), agent.getId(),
                        member.role(), roomDurationMs, roomPasses, passQuota);
                if (stage == 5) member.recordStageFiveEarnedPasses(passQuota);
                session.rooms().complete(roomMapId);
                member.beginRoomExit(roomMapId, nowMs);
                if (prepareDarkSightRoomExit(
                        stage, roomMapId, entry, agent, member, nowMs)) {
                    session.markProgress(nowMs);
                    continue;
                }
                reportStageFourExitRoute(session, member, entry, agent, mainMap,
                        member.roomExitElapsed(roomMapId, nowMs));
                int naturalExitSourceMapId = agent.getMapId();
                Point naturalExitSourcePosition = agent.getPosition() == null
                        ? null : new Point(agent.getPosition());
                enterPortalTo(entry, agent, mainMap);
                if (agent.getMapId() == mainMap) {
                    log.info("LPQ natural room exit completed: session={} stage={} "
                                    + "member={}({}) source={} sourcePosition={} destination={} elapsedMs={}",
                            session.sessionId(), stage, agent.getName(), agent.getId(),
                            naturalExitSourceMapId, naturalExitSourcePosition, mainMap,
                            member.roomExitElapsed(naturalExitSourceMapId, nowMs));
                    member.assign(AgentLpqMemberState.Role.GENERAL, 0);
                    member.clearTraversalProgress();
                    if (npc != null) rallyMemberAtNpc(member, agent, entry, npc);
                }
                session.markProgress(nowMs);
            }
        }
    }

    private static void observeHumanStageFiveRoomCompletion(
            AgentLpqSession session, AgentLpqMemberState member,
            Character human, long nowMs) {
        int roomMapId = human.getMapId();
        if (roomMapId != AgentLpqDefinition.STAGE_5_TELEPORT_ROOM
                && roomMapId != AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM) return;
        int quota = AgentLpqDefinition.roomPassQuota(roomMapId);
        int passes = member.roomPassesCollectedFor(
                roomMapId, human.getItemQuantity(AgentLpqDefinition.PASS, false));
        boolean loosePass = AgentMapPerception.items(human.getMap()).stream()
                .anyMatch(item -> !item.isPickedUp()
                && item.getItemId() == AgentLpqDefinition.PASS);
        if (activeReactors(human).isEmpty() && !loosePass && passes >= quota) {
            member.finishRoomTiming(roomMapId, nowMs);
            member.recordStageFiveEarnedPasses(quota);
            session.rooms().complete(roomMapId);
            member.beginRoomExit(roomMapId, nowMs);
            session.markProgress(nowMs);
        }
    }

    private static boolean markRoomAndEnter(AgentLpqSession session, AgentLpqMemberState member,
                                            AgentRuntimeEntry entry, Character agent,
                                            int roomMapId, long nowMs) {
        boolean magicHelper = isStageFourMagicHelper(session, stage(session.phase()), member, agent, roomMapId);
        if (!mayEnterRoom(session.rooms().owner(roomMapId), agent.getId(), magicHelper)) return false;
        Integer portalId = AgentLpqExitRoutePolicy.portalId(agent.getMapId(), roomMapId);
        if (portalId == null) portalId = ACTIONS.directPortalIdTo(agent, roomMapId);
        Point portal = portalId == null ? null : ACTIONS.portalPosition(agent, portalId);
        if (portalId == null || portal == null) return false;
        if (requiresDarkSightBeforeHazardTraversal(roomMapId, darkSightRefreshDue(agent, nowMs))) {
            Point safeSpot = ACTIONS.portalPosition(agent, DARK_SIGHT_SAFE_PORTAL_ID);
            if (safeSpot != null && !near(agent.getPosition(), safeSpot, INTERACTION_RADIUS)) {
                ACTIONS.navigate(entry, safeSpot, true);
                return true;
            }
            ACTIONS.stop(entry);
            if (refreshDarkSight(entry, agent)) {
                member.deferUntil(nowMs + INTERACTION_RETRY_MS);
            }
            return true;
        }
        if (!atDoorMarkerPosition(agent.getPosition(), portal)) {
            ACTIONS.navigate(entry, portal, true);
            return true;
        }
        ACTIONS.stop(entry);
        // The owner marked the door already; a magic helper never drops a second marker.
        if (!magicHelper && !member.roomMarkerDroppedFor(roomMapId)) {
            if (nowMs < member.nextActionAtMs()) return true;
            boolean markerDropped = AgentScriptItemActionService.dropMesos(
                    entry, AgentLpqDefinition.ROOM_MARKER_MESOS);
            if (!markerDropped) {
                int markerItem = AgentLpqDefinition.ROOM_MARKER_ITEMS.stream()
                        .filter(itemId -> ACTIONS.itemCount(agent, itemId) > 0)
                        .findFirst().orElse(0);
                if (markerItem == 0 && grantItem(session, AgentLpqRescue.Kind.MARKER_GRANT, "room-marker-potion",
                        agent, AgentLpqDefinition.RED_POTION, (short) 1)) {
                    markerItem = AgentLpqDefinition.RED_POTION;
                }
                markerDropped = markerItem != 0 && AgentScriptItemActionService.dropItem(
                        entry, InventoryType.USE, markerItem, (short) 1);
            }
            if (!markerDropped && session.companionAdopted()) {
                member.markRoomMarkerDropped(roomMapId);
                log.info("LPQ room marker skipped: session={} member={}({}) room={}",
                        session.sessionId(), agent.getName(), agent.getId(), roomMapId);
                boolean enteredUnmarked = LPQ.enterPortal(agent, portalId);
                if (enteredUnmarked) session.rooms().markProgress(roomMapId, nowMs);
                return enteredUnmarked;
            }
            if (!markerDropped) {
                log.warn("LPQ room marker unavailable; retrying before room entry: "
                                + "session={} member={}({}) stage={} room={}",
                        session.sessionId(), agent.getName(), agent.getId(),
                        stage(session.phase()), roomMapId);
                member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                return true;
            }
            member.markRoomMarkerDropped(roomMapId);
            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
            session.rooms().markProgress(roomMapId, nowMs);
            session.markProgress(nowMs);
            return true;
        }
        if (nowMs < member.nextActionAtMs()) return true;
        boolean entered = LPQ.enterPortal(agent, portalId);
        if (entered) session.rooms().markProgress(roomMapId, nowMs);
        return entered;
    }

    /** What a member assigned a split room does on the main map: walk in, or let the room go. */
    enum MainMapRoomStep { ENTER, RELEASE_COMPLETED, RELEASE_NOT_OWNER }

    /**
     * Exactly the case assignStageFourMagicHelper creates (LPQ base fix 7): a magic attacker sent to a stage-4 magic
     * room that a non-magic member owns. It may walk in; on entry the in-room takeover gives the room to it.
     */
    static boolean stageFourMagicHelper(int stage, int roomMapId, int assignedMapId, boolean agentMagic,
                                        Integer ownerId, int agentId, boolean ownerMagic) {
        return stage == 4 && AgentLpqDefinition.STAGE_4_MAGIC_ROOMS.contains(roomMapId)
                && assignedMapId == roomMapId && agentMagic && ownerId != null && ownerId != agentId && !ownerMagic;
    }

    /** A completed room is let go first; the owner, or a stage-4 magic helper, walks in; anyone else lets go. */
    static MainMapRoomStep mainMapRoomStep(boolean completed, Integer ownerId, int agentId, boolean magicHelper) {
        if (completed) return MainMapRoomStep.RELEASE_COMPLETED;
        return mayEnterRoom(ownerId, agentId, magicHelper)
                ? MainMapRoomStep.ENTER : MainMapRoomStep.RELEASE_NOT_OWNER;
    }

    static boolean mayEnterRoom(Integer ownerId, int agentId, boolean magicHelper) {
        return java.util.Objects.equals(ownerId, agentId) || magicHelper;
    }

    private static boolean isStageFourMagicHelper(AgentLpqSession session, int stage, AgentLpqMemberState member,
                                                  Character agent, int roomMapId) {
        if (stage != 4 || !AgentLpqDefinition.STAGE_4_MAGIC_ROOMS.contains(roomMapId)) return false;
        Integer ownerId = session.rooms().owner(roomMapId);
        if (ownerId == null || ownerId == agent.getId()) return false;
        Character owner = character(ownerId);
        return stageFourMagicHelper(stage, roomMapId, member.assignedMapId(),
                AgentLpqRosterRequirementPolicy.magicAttack(agent), ownerId, agent.getId(),
                owner != null && AgentLpqRosterRequirementPolicy.magicAttack(owner));
    }

    static boolean atDoorMarkerPosition(Point characterPosition, Point portalPosition) {
        if (characterPosition == null || portalPosition == null) return false;
        return Math.abs(characterPosition.x - portalPosition.x) <= 28
                && Math.abs(characterPosition.y - portalPosition.y) <= 24;
    }

    private static void assignRooms(AgentLpqSession session, int stage, List<Integer> rooms, long nowMs) {
        int humanRoom = preferredStageFiveRoom(session);
        if (stage == 5 && humanRoom != 0 && rooms.contains(humanRoom)
                && !session.rooms().completed(humanRoom)) {
            AgentLpqMemberState human = session.member(session.preferredHumanId());
            if (human != null && suitable(human.characterId(), stage, humanRoom)
                    && session.rooms().reserve(humanRoom, human.characterId(), nowMs)
                    && human.assignedMapId() != humanRoom) {
                assignRoom(session, human, roomRole(stage, humanRoom), humanRoom, stage, false);
            }
        }
        List<AgentLpqMemberState> agents = session.members().stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .sorted(Comparator.comparingInt(member -> member.characterId() == session.eventLeaderId() ? 1 : 0))
                .toList();
        List<Integer> assignmentOrder = stage == 5
                ? List.of(AgentLpqDefinition.STAGE_5_TELEPORT_ROOM,
                AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM, 922_010_502,
                922_010_503, 922_010_504, 922_010_505)
                : stage == 4
                ? List.of(922_010_401, 922_010_402, 922_010_404,
                922_010_405, 922_010_403)
                : rooms;
        for (int room : assignmentOrder) {
            if (session.rooms().completed(room) || session.rooms().owner(room) != null) continue;
            var eligible = agents.stream().filter(member -> member.assignedMapId() == 0)
                    .filter(member -> suitable(member.characterId(), stage, room));
            AgentLpqMemberState chosen = stage == 4 && room == 922_010_404
                    ? eligible.min(Comparator.comparingInt(member ->
                    AgentLpqRosterRequirementPolicy.stageFourTwoMonsterRoomPriority(
                            character(member.characterId())))).orElse(null)
                    : eligible.findFirst().orElse(null);
            boolean specialized = stage == 4
                    || room == AgentLpqDefinition.STAGE_5_TELEPORT_ROOM
                    || room == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM;
            if (chosen == null && stage == 4
                    && room == AgentLpqDefinition.STAGE_4_WEAK_MAGIC_ROOM) {
                chosen = agents.stream().filter(member -> member.assignedMapId() == 0)
                        .findFirst().orElse(null);
            }
            if (chosen == null && !specialized) {
                chosen = agents.stream().filter(member -> member.assignedMapId() == 0).findFirst().orElse(null);
            }
            if (chosen == null) continue;
            if (!session.rooms().reserve(room, chosen.characterId(), nowMs)) continue;
            assignRoom(session, chosen, roomRole(stage, room), room, stage, false);
        }
        if (stage == 4) assignStageFourMagicHelper(session, agents);
    }

    static int preferredStageFiveRoom(AgentLpqSession session) {
        return switch (session.humanRolePreference()) {
            case TELEPORT -> AgentLpqDefinition.STAGE_5_TELEPORT_ROOM;
            case DARK_SIGHT -> AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM;
            default -> 0;
        };
    }

    private static void assignStageFourMagicHelper(AgentLpqSession session,
                                                    List<AgentLpqMemberState> agents) {
        if (session.rooms().completed(AgentLpqDefinition.STAGE_4_WEAK_MAGIC_ROOM)) return;
        Integer ownerId = session.rooms().owner(AgentLpqDefinition.STAGE_4_WEAK_MAGIC_ROOM);
        Character owner = ownerId == null ? null : character(ownerId);
        if (owner == null || AgentLpqRosterRequirementPolicy.magicAttack(owner)) return;
        AgentLpqMemberState helper = agents.stream()
                .filter(member -> member.assignedMapId() == 0)
                .filter(member -> {
                    Character candidate = character(member.characterId());
                    return AgentLpqRosterRequirementPolicy.magicAttack(candidate);
                })
                .findFirst().orElse(null);
        if (helper != null) {
            assignRoom(session, helper, AgentLpqMemberState.Role.MAGIC_ATTACKER,
                    AgentLpqDefinition.STAGE_4_WEAK_MAGIC_ROOM, 4, true);
        }
    }

    private static void assignRoom(AgentLpqSession session, AgentLpqMemberState member,
                                   AgentLpqMemberState.Role role, int roomMapId,
                                   int stage, boolean helper) {
        member.assign(role, roomMapId);
        Character character = character(member.characterId());
        int passCount = character == null ? 0
                : character.getItemQuantity(AgentLpqDefinition.PASS, false);
        member.beginRoomPassCollection(roomMapId, passCount);
        if (stage == 5 && character != null && session.eventInstance() != null) {
            MapleMap room = session.eventInstance().getMapInstance(roomMapId);
            AgentRuntimeEntry entry = entry(member.characterId());
            AgentNavigationGraphService.warmGraphAsync(
                    room, AgentMovementStateRuntime.movementProfileOrCharacter(entry, character));
        }
        if (member.memberType() == AgentLpqMemberState.MemberType.HUMAN) {
            announceHumanRoomIntent(session, character, roomMapId, role);
        } else {
            announceRoomIntent(session, member, character, stage, roomMapId, role, helper);
        }
    }

    private static void announceHumanRoomIntent(
            AgentLpqSession session, Character human, int roomMapId,
            AgentLpqMemberState.Role role) {
        // A companion run says no system text to its human (voice: no companion line here).
        if (human == null || voice(session, null, "room intent") == null) return;
        String skill = role == AgentLpqMemberState.Role.TELEPORT_RUNNER
                ? "Teleport" : "Dark Sight";
        human.dropMessage(6, "LPQ Stage 5: you own room " + (roomMapId % 1_000)
                + " for " + skill + ". Collect four passes and exit through the far portal.");
        Character speaker = session.members().stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (speaker != null) PARTY.sendPartyChat(speaker, human.getName()
                + " has room " + (roomMapId % 1_000) + " for " + skill + ".");
    }

    private static AgentLpqMemberState.Role roomRole(int stage, int room) {
        if (stage == 5 && room == AgentLpqDefinition.STAGE_5_TELEPORT_ROOM) {
            return AgentLpqMemberState.Role.TELEPORT_RUNNER;
        }
        if (stage == 5 && room == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM) {
            return AgentLpqMemberState.Role.DARK_SIGHT_RUNNER;
        }
        if (AgentLpqDefinition.STAGE_4_MAGIC_ROOMS.contains(room)) {
            return AgentLpqMemberState.Role.MAGIC_ATTACKER;
        }
        if (AgentLpqDefinition.STAGE_4_PHYSICAL_ROOMS.contains(room)) {
            return AgentLpqMemberState.Role.PHYSICAL_ATTACKER;
        }
        return AgentLpqMemberState.Role.GENERAL;
    }

    private static boolean suitable(int id, int stage, int room) {
        Character member = character(id);
        if (stage == 4 && AgentLpqDefinition.STAGE_4_MAGIC_ROOMS.contains(room)) {
            return AgentLpqRosterRequirementPolicy.magicAttack(member);
        }
        if (stage == 4 && AgentLpqDefinition.STAGE_4_PHYSICAL_ROOMS.contains(room)) {
            return AgentLpqRosterRequirementPolicy.physicalAttack(member);
        }
        if (stage == 5 && room == AgentLpqDefinition.STAGE_5_TELEPORT_ROOM) {
            return AgentLpqRosterRequirementPolicy.teleportMagic(member);
        }
        if (stage == 5 && room == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM) {
            return AgentLpqRosterRequirementPolicy.darkSight(member);
        }
        return true;
    }

    static Set<Integer> stageFourCombatTargets(int roomMapId) {
        if (AgentLpqDefinition.STAGE_4_MAGIC_ROOMS.contains(roomMapId)) {
            return Set.of(AgentLpqDefinition.STAGE_4_MAGIC_MOB);
        }
        if (AgentLpqDefinition.STAGE_4_PHYSICAL_ROOMS.contains(roomMapId)) {
            return Set.of(AgentLpqDefinition.STAGE_4_PHYSICAL_MOB);
        }
        return Set.of();
    }

    static boolean stageFourRoomAssistRecoveryDue(
            long roomElapsedMs, long inactiveForMs, boolean alreadyApplied) {
        return !alreadyApplied
                && roomElapsedMs >= STAGE_4_ROOM_ASSIST_MS
                && inactiveForMs >= STAGE_4_COMBAT_STALL_MS;
    }

    static boolean stageFourRoomHardRecoveryDue(
            long roomElapsedMs, boolean alreadyApplied) {
        return !alreadyApplied
                && roomElapsedMs >= STAGE_4_ROOM_HARD_RECOVERY_MS;
    }

    static boolean stageFourRoomReadyToExit(
            boolean mobsCleared, int collectedPasses, int passQuota) {
        return mobsCleared && passQuota > 0 && collectedPasses >= passQuota;
    }

    /**
     * Runs from the LPQ watchdog so room deadlines do not depend on an
     * individual Agent remaining runnable in the activity scheduler.
     */
    static boolean tickStageFourRoomRecoveryWatchdog(AgentLpqSession session, long nowMs) {
        synchronized (session) {
            if (session.terminal() || session.paused()
                    || session.phase() != AgentLpqSession.Phase.STAGE_4) {
                return false;
            }
            boolean coordinatorTickRequired = false;
            for (AgentLpqMemberState member : session.members()) {
                if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
                Character agent = character(member.characterId());
                AgentRuntimeEntry entry = entry(member.characterId());
                if (agent == null || entry == null
                        || !AgentLpqDefinition.roomMaps(4).contains(agent.getMapId())) {
                    continue;
                }
                int roomMapId = agent.getMapId();
                // Room completion releases ownership immediately, but crossing the
                // authored exit route still requires scheduler ticks. Keep the
                // coordinator alive until the character is confirmed on the Stage 4
                // main map; otherwise a fast final-pass pickup can strand the former
                // owner inside the completed room.
                if (session.rooms().completed(roomMapId)) {
                    coordinatorTickRequired |= tickStageFourCompletedRoomExit(
                            session, entry, agent, nowMs);
                    continue;
                }
                if (!java.util.Objects.equals(
                        session.rooms().owner(roomMapId), agent.getId())) {
                    continue;
                }
                member.beginRoomTiming(roomMapId, nowMs);
                int roomPasses = member.roomPassesCollectedFor(roomMapId,
                        agent.getItemQuantity(AgentLpqDefinition.PASS, false));
                coordinatorTickRequired |= applyStageFourRoomRecovery(
                        session, member, entry, agent, roomPasses, nowMs);
            }
            return coordinatorTickRequired;
        }
    }

    /**
     * Advances one completed Stage 4 occupant independently of the party
     * coordinator lease. Completion is not an exit: the character remains a
     * room occupant until the portal changes its map.
     */
    static boolean tickStageFourCompletedRoomExit(
            AgentLpqSession session, AgentRuntimeEntry entry,
            Character agent, long nowMs) {
        synchronized (session) {
            if (session.terminal() || session.paused()
                    || session.phase() != AgentLpqSession.Phase.STAGE_4
                    || entry == null || agent == null
                    || !AgentLpqDefinition.roomMaps(4).contains(agent.getMapId())
                    || !session.rooms().completed(agent.getMapId())) {
                return false;
            }
            int roomMapId = agent.getMapId();
            int mainMapId = AgentLpqDefinition.stage(4).mapId();
            AgentLpqMemberState member = session.member(agent.getId());
            if (member == null) return false;
            member.beginRoomExit(roomMapId, nowMs);
            long exitInactiveMs = traversalInactivity(
                    member, agent, mainMapId, nowMs);
            long exitElapsedMs = member.roomExitElapsed(roomMapId, nowMs);
            if (roomExitPortalPlacementDue(4, exitInactiveMs, exitElapsedMs)) {
                return forceRoomExitThroughPortal(
                        session, member, entry, agent, mainMapId, nowMs);
            }
            reportStageFourExitRoute(
                    session, member, entry, agent, mainMapId, exitElapsedMs);
            Point sourcePosition = agent.getPosition() == null
                    ? null : new Point(agent.getPosition());
            enterPortalTo(entry, agent, mainMapId);
            if (agent.getMapId() != mainMapId) return true;
            log.info("LPQ natural room exit completed: session={} stage=4 "
                            + "member={}({}) source={} sourcePosition={} destination={} elapsedMs={}",
                    session.sessionId(), agent.getName(), agent.getId(), roomMapId,
                    sourcePosition, mainMapId, exitElapsedMs);
            member.assign(AgentLpqMemberState.Role.GENERAL, 0);
            member.clearTraversalProgress();
            session.markProgress(nowMs);
            return true;
        }
    }

    /**
     * Keeps Stage 5 terminal room work independent of the party-wide collection
     * lease. A final loose pass and a completed room exit must continue even when
     * every other member has already become quiescent on the main map.
     */
    static boolean tickStageFiveRoomContinuationWatchdog(
            AgentLpqSession session, long nowMs) {
        synchronized (session) {
            if (session.terminal() || session.paused()
                    || session.phase() != AgentLpqSession.Phase.STAGE_5) {
                return false;
            }
            boolean handled = false;
            for (AgentLpqMemberState member : session.members()) {
                if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
                Character agent = character(member.characterId());
                AgentRuntimeEntry entry = entry(member.characterId());
                handled |= tickStageFiveRoomContinuation(
                        session, entry, agent, nowMs);
            }
            return handled;
        }
    }

    static boolean tickStageFiveRoomContinuation(
            AgentLpqSession session, AgentRuntimeEntry entry,
            Character agent, long nowMs) {
        synchronized (session) {
            if (session.terminal() || session.paused()
                    || session.phase() != AgentLpqSession.Phase.STAGE_5
                    || entry == null || agent == null
                    || !AgentLpqDefinition.roomMaps(5).contains(agent.getMapId())) {
                return false;
            }
            int roomMapId = agent.getMapId();
            AgentLpqMemberState member = session.member(agent.getId());
            if (member == null) return false;
            if (session.rooms().completed(roomMapId)) {
                return tickStageFiveCompletedRoomExit(
                        session, member, entry, agent, nowMs);
            }

            int activeRoomReactors = activeReactors(agent).size();
            if (activeRoomReactors > 0) return false;
            boolean darkSightRoom = roomMapId == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM;
            Set<Integer> mobs = ACTIONS.configuredMonsterSpawnIds(agent);
            if (!darkSightRoom && ACTIONS.liveMonsterCount(agent, mobs) > 0) return false;

            member.beginRoomTiming(roomMapId, nowMs);
            int passQuota = AgentLpqDefinition.roomPassQuota(roomMapId);
            int roomPasses = member.roomPassesCollectedFor(
                    roomMapId, agent.getItemQuantity(AgentLpqDefinition.PASS, false));
            boolean looseRoomPass = AgentMapPerception.items(agent.getMap()).stream()
                    .anyMatch(item -> !item.isPickedUp()
                            && item.getItemId() == AgentLpqDefinition.PASS);
            long settledForMs = member.observeRoomRecoveryProgress(
                    roomMapId, 0, 0L, roomPasses, nowMs);

            if (looseRoomPass) {
                if (darkSightRoom && requiresDarkSightCancellationForRoomLoot(
                        agent.getBuffedValue(BuffStat.DARKSIGHT) != null,
                        passDropWithinPickupRange(agent))) {
                    ACTIONS.stop(entry);
                    cancelDarkSight(agent);
                    member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                    return true;
                }
                if (stageFiveFinalLoosePassRecoveryDue(
                        roomPasses, passQuota, settledForMs)) {
                    int vacuumed = vacuumPassDrops(session, "stage5-terminal-room", agent, Set.of(agent.getMap()));
                    if (vacuumed > 0) {
                        session.markProgress(nowMs);
                        roomPasses = member.roomPassesCollectedFor(
                                roomMapId, agent.getItemQuantity(
                                        AgentLpqDefinition.PASS, false));
                        looseRoomPass = false;
                        log.warn("LPQ Stage 5 terminal-room watchdog recovered {} loose "
                                        + "pass for member={}({}) room={} passes={}/{} "
                                        + "settledForMs={}",
                                vacuumed, agent.getName(), agent.getId(), roomMapId,
                                roomPasses, passQuota, settledForMs);
                    }
                }
                if (looseRoomPass && session.companionAdopted() && companionRoomCeiling(session, member, entry,
                        agent, AgentLpqDefinition.stage(5).mapId(), true, nowMs)) {
                    return true;
                }
                if (looseRoomPass) {
                    collectNearestDrop(entry, agent,
                            Set.of(AgentLpqDefinition.PASS), new LinkedHashSet<>());
                    return true;
                }
            }

            if (stageFiveMissingRoomPassRecoveryDue(
                    5, 0, false, roomPasses, passQuota, settledForMs)
                    && grantItem(session, AgentLpqRescue.Kind.PASS_AWARD, "stage5-missing-room-pass",
                    agent, AgentLpqDefinition.PASS, (short) 1)) {
                roomPasses++;
                member.clearReactorWork();
                session.markProgress(nowMs);
                log.warn("LPQ Stage 5 terminal-room watchdog replaced one missing "
                                + "pass: session={} member={}({}) room={} passes={}/{} "
                                + "settledForMs={}",
                        session.sessionId(), agent.getName(), agent.getId(), roomMapId,
                        roomPasses, passQuota, settledForMs);
            }
            if (session.companionAdopted() && roomPasses < passQuota) {
                // Nothing is left in it (no box, no mob, no loose pass) and its last drops have settled: a player
                // walks out with what it holds (no missing pass is granted in a companion run).
                if (settledForMs < STAGE_5_MISSING_ROOM_PASS_SETTLE_MS) return true;
                member.finishRoomTiming(roomMapId, nowMs);
                member.recordStageFiveEarnedPasses(roomPasses);
                session.rooms().complete(roomMapId);
                member.beginRoomExit(roomMapId, nowMs);
                session.markProgress(nowMs);
                log.info("LPQ stage5 room short: session={} room={} member={}({}) passes={}/{}",
                        session.sessionId(), roomMapId, agent.getName(), agent.getId(), roomPasses, passQuota);
                return tickStageFiveCompletedRoomExit(session, member, entry, agent, nowMs);
            }
            if (roomPasses < passQuota) return true;

            long roomDurationMs = member.finishRoomTiming(roomMapId, nowMs);
            member.recordStageFiveEarnedPasses(passQuota);
            session.rooms().complete(roomMapId);
            member.beginRoomExit(roomMapId, nowMs);
            session.markProgress(nowMs);
            log.info("LPQ Stage 5 terminal-room watchdog completed room: session={} "
                            + "map={} member={}({}) durationMs={} passes={}/{}",
                    session.sessionId(), roomMapId, agent.getName(), agent.getId(),
                    roomDurationMs, roomPasses, passQuota);
            return tickStageFiveCompletedRoomExit(
                    session, member, entry, agent, nowMs);
        }
    }

    static boolean stageFiveFinalLoosePassRecoveryDue(
            int roomPasses, int passQuota, long settledForMs) {
        return passQuota > 0
                && roomPasses == passQuota - 1
                && settledForMs >= STAGE_5_PASS_LOOT_SETTLE_MS;
    }

    private static boolean tickStageFiveCompletedRoomExit(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, long nowMs) {
        int roomMapId = agent.getMapId();
        int mainMapId = AgentLpqDefinition.stage(5).mapId();
        member.beginRoomExit(roomMapId, nowMs);
        if (prepareDarkSightRoomExit(
                5, roomMapId, entry, agent, member, nowMs)) {
            return true;
        }
        long exitInactiveMs = traversalInactivity(
                member, agent, mainMapId, nowMs);
        long exitElapsedMs = member.roomExitElapsed(roomMapId, nowMs);
        if (roomExitPortalPlacementDue(5, exitInactiveMs, exitElapsedMs)) {
            return forceRoomExitThroughPortal(
                    session, member, entry, agent, mainMapId, nowMs);
        }
        Point sourcePosition = agent.getPosition() == null
                ? null : new Point(agent.getPosition());
        enterPortalTo(entry, agent, mainMapId);
        if (agent.getMapId() != mainMapId) return true;
        log.info("LPQ natural room exit completed: session={} stage=5 member={}({}) "
                        + "source={} sourcePosition={} destination={} elapsedMs={}",
                session.sessionId(), agent.getName(), agent.getId(), roomMapId,
                sourcePosition, mainMapId, exitElapsedMs);
        member.assign(AgentLpqMemberState.Role.GENERAL, 0);
        member.clearTraversalProgress();
        member.clearStageFiveAssist();
        session.markProgress(nowMs);
        Point npc = stageNpcPosition(session, 5);
        if (npc != null) rallyMemberAtNpc(member, agent, entry, npc);
        return true;
    }

    static boolean applyStageFourRoomRecovery(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, int roomPasses, long nowMs) {
        int roomMapId = agent.getMapId();
        Set<Integer> targetIds = stageFourCombatTargets(roomMapId);
        List<Monster> liveTargets = agent.getMap().getAllMonsters().stream()
                .filter(monster -> targetIds.contains(monster.getId()))
                .toList();
        long liveHp = liveTargets.stream().mapToLong(Monster::getHp).sum();
        long inactiveForMs = member.observeRoomRecoveryProgress(
                roomMapId, liveTargets.size(), liveHp, roomPasses, nowMs);
        long roomElapsedMs = member.roomTimingElapsed(roomMapId, nowMs);
        int passQuota = AgentLpqDefinition.roomPassQuota(roomMapId);

        // Inventory completion is authoritative. Do not wait for the assigned Agent's
        // activity tick to translate 1/1 or 2/2 into the room-exit state; that tick can
        // become idle immediately after collecting the final pass. The independent LPQ
        // watchdog performs the same natural portal attempt for every Stage 4 room.
        if (stageFourRoomReadyToExit(liveTargets.isEmpty(), roomPasses, passQuota)) {
            return completeStageFourRoomAndBeginExit(
                    session, member, entry, agent, roomMapId, roomElapsedMs,
                    roomPasses, passQuota, 0, nowMs, "objective-complete");
        }
        // A companion run's owner fights its room to the end: no hard recovery, no pass vacuum, no assist.
        if (session.companionAdopted()) return false;

        if (stageFourRoomHardRecoveryDue(
                roomElapsedMs, member.roomRecoveryHardAppliedFor(roomMapId))) {
            ACTIONS.stop(entry);
            AgentGrindTargetStateRuntime.clear(entry);
            List<Integer> mobIds = liveTargets.stream().map(Monster::getId).distinct().toList();
            killWithDrops(session, "stage4-hard-recovery", agent.getMap(), mobIds);
            int vacuumed = vacuumPassDrops(session, "stage4-hard-recovery", agent, Set.of(agent.getMap()));
            member.markRoomRecoveryHardApplied(roomMapId);
            member.clearRoomCombatProgress();
            session.markProgress(nowMs);
            int passesAfter = member.roomPassesCollectedFor(roomMapId,
                    agent.getItemQuantity(AgentLpqDefinition.PASS, false));
            boolean objectivesComplete = agent.getMap().getAllMonsters().stream()
                    .noneMatch(monster -> targetIds.contains(monster.getId()))
                    && passesAfter >= passQuota;
            boolean exited = false;
            if (objectivesComplete) {
                session.rooms().complete(roomMapId);
                member.beginRoomExit(roomMapId, nowMs);
                member.finishRoomTiming(roomMapId, nowMs);
                exited = forceRoomExitThroughPortal(session, member, entry, agent,
                        AgentLpqDefinition.stage(4).mapId(), nowMs);
            }
            log.warn("LPQ Stage 4 90-second hard room recovery: session={} member={}({}) "
                            + "room={} elapsedMs={} inactiveForMs={} killedMobs={} "
                            + "vacuumedPasses={} passesBefore={}/{} passesAfter={}/{} "
                            + "exitedThroughPortal={}",
                    session.sessionId(), agent.getName(), agent.getId(), roomMapId,
                    roomElapsedMs, inactiveForMs, liveTargets.size(), vacuumed, roomPasses,
                    passQuota, passesAfter, passQuota, exited);
            return true;
        }

        if (liveTargets.isEmpty() && roomElapsedMs >= STAGE_4_ROOM_ASSIST_MS) {
            int vacuumed = vacuumPassDrops(session, "stage4-pass-loot", agent, Set.of(agent.getMap()));
            int passesAfter = member.roomPassesCollectedFor(roomMapId,
                    agent.getItemQuantity(AgentLpqDefinition.PASS, false));
            if (stageFourRoomReadyToExit(true, passesAfter, passQuota)) {
                return completeStageFourRoomAndBeginExit(
                        session, member, entry, agent, roomMapId, roomElapsedMs,
                        passesAfter, passQuota, vacuumed, nowMs, "60-second-pass-loot");
            }
        }
        if (!stageFourRoomAssistRecoveryDue(roomElapsedMs, inactiveForMs,
                member.roomRecoveryAssistAppliedFor(roomMapId)) || liveTargets.isEmpty()) {
            return false;
        }

        Monster target = liveTargets.stream()
                .min(java.util.Comparator.comparingDouble(monster ->
                        monster.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        Point grounded = target == null ? null
                : ACTIONS.groundPoint(agent.getMap(), target.getPosition());
        if (grounded == null) return false;
        ACTIONS.stop(entry);
        AgentGrindTargetStateRuntime.clear(entry);
        rescueTeleport(session, "stage4-attack-assist", entry, agent, grounded);
        member.markRoomRecoveryAssistApplied(roomMapId);
        member.clearRoomCombatProgress();
        member.clearTraversalProgress();
        member.deferUntil(nowMs + STAGE_4_EYE_RETRY_MS);
        session.markProgress(nowMs);
        log.warn("LPQ Stage 4 60-second attack-position assist: session={} member={}({}) "
                        + "room={} elapsedMs={} inactiveForMs={} targetMob={} targetOid={} "
                        + "targetHp={} target={}",
                session.sessionId(), agent.getName(), agent.getId(), roomMapId,
                roomElapsedMs, inactiveForMs, target.getId(), target.getObjectId(),
                target.getHp(), grounded);
        return true;
    }

    private static boolean completeStageFourRoomAndBeginExit(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, int roomMapId,
            long roomElapsedMs, int roomPasses, int passQuota,
            int vacuumedPasses, long nowMs, String reason) {
        ACTIONS.stop(entry);
        AgentGrindTargetStateRuntime.clear(entry);
        session.rooms().complete(roomMapId);
        member.beginRoomExit(roomMapId, nowMs);
        member.finishRoomTiming(roomMapId, nowMs);
        member.clearRoomCombatProgress();
        boolean exited = enterPortalTo(entry, agent, AgentLpqDefinition.stage(4).mapId());
        session.markProgress(nowMs);
        log.info("LPQ Stage 4 room-complete natural exit started: session={} "
                        + "member={}({}) room={} reason={} elapsedMs={} vacuumedPasses={} "
                        + "passes={}/{} exitedImmediately={}",
                session.sessionId(), agent.getName(), agent.getId(), roomMapId,
                reason, roomElapsedMs, vacuumedPasses, roomPasses, passQuota, exited);
        return true;
    }

    private static void reportRoomProgress(AgentLpqSession session, AgentLpqMemberState member,
                                           Character agent, int roomMapId, int reactors,
                                           int mobs, int passes, int quota, long nowMs) {
        int loosePasses = unpickedPassDropCount(agent.getMap().getDroppedItems());
        String signature = reactors + ":" + mobs + ":" + passes + ":" + loosePasses;
        if (!member.shouldReportRoomProgress(
                roomMapId, signature, nowMs, ROOM_PROGRESS_LOG_INTERVAL_MS)) return;
        Monster target = AgentGrindTargetStateRuntime.activeTargetInMap(entry(agent.getId()), agent.getMap());
        Integer ownerId = session.rooms().owner(roomMapId);
        log.info("LPQ room progress: stage={} map={} owner={} member={}({}) role={} "
                        + "position={} reactors={} mobs={} passes={}/{} loosePasses={} "
                        + "reactorApproachOid={} reactorTargetOid={} targetOid={} targetMob={} targetHp={}",
                AgentLpqDefinition.stageNumber(roomMapId), roomMapId, ownerId,
                agent.getName(), agent.getId(), member.role(), agent.getPosition(),
                reactors, mobs, passes, quota, loosePasses,
                member.reactorApproachObjectId(), member.reactorTargetObjectId(),
                target == null ? 0 : target.getObjectId(),
                target == null ? 0 : target.getId(), target == null ? 0 : target.getHp());
    }

    /** Records each distinct authored Stage 4 exit leg with the actual server pose. */
    private static void reportStageFourExitRoute(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, int destinationMapId,
            long exitElapsedMs) {
        if (agent == null || AgentLpqDefinition.stageNumber(agent.getMapId()) != 4) return;
        Integer portalId = portalIdTo(agent, destinationMapId);
        Point portalPosition = portalId == null ? null : ACTIONS.portalPosition(agent, portalId);
        Point currentPosition = agent.getPosition();
        Point waypoint = AgentLpqExitRoutePolicy.nextWaypoint(
                agent.getMapId(), destinationMapId, currentPosition, portalPosition);
        String leg = "stage4:exit-leg:" + agent.getMapId() + ":"
                + (waypoint == null ? "portal" : waypoint.x + ":" + waypoint.y);
        if (!member.claimIntentAnnouncement(leg)) return;
        log.info("LPQ Stage 4 authored exit leg: session={} member={}({}) room={} "
                        + "position={} waypoint={} portalId={} portalPosition={} elapsedMs={} "
                        + "inAir={} climbing={}",
                session.sessionId(), agent.getName(), agent.getId(), agent.getMapId(),
                currentPosition, waypoint, portalId, portalPosition, exitElapsedMs,
                AgentMovementStateRuntime.inAir(entry),
                AgentMovementStateRuntime.climbing(entry));
    }

    static boolean requiresDarkSightBeforeHazardTraversal(int roomMapId, boolean refreshDue) {
        return roomMapId == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM && refreshDue;
    }

    static boolean requiresDarkSightBeforeRoomExit(
            int stage, int roomMapId, boolean refreshDue) {
        return stage == 5 && roomMapId == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM
                && refreshDue;
    }

    static boolean requiresStageFiveMainMapDarkSight(
            boolean stageFiveRunner, int mapId, boolean refreshDue) {
        return stageFiveRunner
                && mapId == AgentLpqDefinition.stage(5).mapId()
                && refreshDue;
    }

    static boolean shouldCancelStageFiveDarkSightAtBalloon(
            boolean stageFiveRunner, int mapId,
            boolean atBalloon, boolean darkSightActive) {
        return stageFiveRunner
                && mapId == AgentLpqDefinition.stage(5).mapId()
                && atBalloon && darkSightActive;
    }

    static boolean requiresDarkSightCancellationForRoomLoot(
            boolean darkSightActive, boolean passWithinPickupRange) {
        return darkSightActive && passWithinPickupRange;
    }

    private static boolean prepareStageFiveMainMapDarkSight(
            AgentLpqMemberState member, AgentRuntimeEntry entry,
            Character agent, long nowMs) {
        Point balloon = ACTIONS.npcPosition(
                agent, AgentLpqDefinition.stage(5).npcId());
        boolean atBalloon = balloon != null
                && near(agent.getPosition(), balloon, INTERACTION_RADIUS);
        boolean darkSightActive = agent.getBuffedValue(BuffStat.DARKSIGHT) != null;
        if (shouldCancelStageFiveDarkSightAtBalloon(
                member.stageFiveDarkSightRunner(), agent.getMapId(),
                atBalloon, darkSightActive)) {
            ACTIONS.stop(entry);
            cancelDarkSight(agent);
            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
            return true;
        }
        // Reaching the balloon is the protection boundary. Once Dark Sight has
        // been cancelled there, do not immediately cast it again merely because
        // the missing buff is considered refresh-due.
        if (member.stageFiveDarkSightRunner()
                && agent.getMapId() == AgentLpqDefinition.stage(5).mapId()
                && atBalloon) {
            return false;
        }
        if (!requiresStageFiveMainMapDarkSight(
                member.stageFiveDarkSightRunner(), agent.getMapId(),
                darkSightRefreshDue(agent, nowMs))) {
            return false;
        }
        ACTIONS.stop(entry);
        if (AgentMovementStateRuntime.inAir(entry)
                || AgentMovementStateRuntime.climbing(entry)
                || !ACTIONS.grounded(agent)) {
            return true;
        }
        if (refreshDarkSight(entry, agent)) {
            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
        }
        return true;
    }

    private static boolean prepareDarkSightRoomExit(
            int stage, int roomMapId, AgentRuntimeEntry entry, Character agent,
            AgentLpqMemberState member, long nowMs) {
        if (!requiresDarkSightBeforeRoomExit(
                stage, roomMapId, darkSightRefreshDue(agent, nowMs))) {
            return false;
        }
        if (AgentMovementStateRuntime.inAir(entry)
                || AgentMovementStateRuntime.climbing(entry)
                || !ACTIONS.grounded(agent)) {
            ACTIONS.stop(entry);
            return true;
        }
        ACTIONS.stop(entry);
        if (refreshDarkSight(entry, agent)) {
            member.markRoomExitProtectionPrepared(roomMapId);
            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
        }
        return true;
    }

    private static void recoverMissingPasses(AgentLpqSession session, Character leader,
                                             int stage, int required, long nowMs) {
        if (stage == 5 && !allSplitRoomsEntered(session, stage)) {
            session.markPassRecoveryConsolidation(nowMs);
            return;
        }
        if (session.passHandoffRecoveryActive()) {
            recoverPassHandoffAtNpc(
                    session, leader, AgentLpqDefinition.stage(stage), nowMs);
            return;
        }
        int partyPasses = partyPassCount(session);
        int leaderPasses = leader.getItemQuantity(AgentLpqDefinition.PASS, false);
        // Stage 5 owns a room-entry ceiling for every worker. Do not let the
        // generic missing-pass watchdog relocate workers or synthesize passes first.
        if (stage == 5 && partyPasses < required) return;
        Set<MapleMap> maps = collectionMaps(session, stage);
        int liveMobs = maps.stream().mapToInt(map -> map.getAllMonsters().size()).sum();
        int activeReactorCount = activeReactorCount(session, maps);
        long liveMobHp = maps.stream().flatMap(map -> map.getAllMonsters().stream())
                .mapToLong(Monster::getHp).sum();
        long inactiveForMs = session.observePassRecoveryProgress(
                partyPasses, liveMobs, activeReactorCount, liveMobHp, nowMs);
        boolean stageOneCombatCeilingReached = stageOneCombatCeilingReached(
                stage, session.phaseEnteredAtMs(), nowMs, STAGE_1_MAX_NATURAL_COMBAT_MS);
        if (leaderPasses >= required
                || (!stageOneCombatCeilingReached
                && inactiveForMs < recovery(stage).missingPassMs())) {
            return;
        }
        int vacuumed = recoverPassDrops(session, leader, stage, maps);
        partyPasses = partyPassCount(session);
        if (passHandoffRecoveryApplicable(stage, partyPasses, required)) {
            if (session.beginPassHandoffRecovery(nowMs)) {
                log.warn("LPQ Stage {} pass handoff recovery started: session={} leader={}({}) "
                                + "partyPasses={}/{} vacuumedDrops={} currentStageMap={}",
                        stage, session.sessionId(), leader.getName(), leader.getId(), partyPasses,
                        required, vacuumed, AgentLpqDefinition.stage(stage).mapId());
            }
            recoverPassHandoffAtNpc(
                    session, leader, AgentLpqDefinition.stage(stage), nowMs);
            return;
        }
        if (vacuumed > 0) {
            session.markPassRecoveryConsolidation(nowMs);
            log.warn("LPQ Stage {} pass recovery vacuumed {} unreachable pass drops while "
                            + "preserving split-room ownership: session={} leader={}({}) "
                            + "partyPasses={}/{} maps={}",
                    stage, vacuumed, session.sessionId(), leader.getName(), leader.getId(),
                    partyPasses, required, maps.stream().map(MapleMap::getId).toList());
            return;
        }
        if (partyPasses >= required) return;
        if (missingPassMobSweepAllowed(stage)
                && liveMobs > 0 && !session.passRecoveryMobSweepAttempted()) {
            for (MapleMap map : maps) {
                List<Integer> mobIds = map.getAllMonsters().stream()
                        .map(mob -> mob.getId()).distinct().toList();
                killWithDrops(session, "missing-pass-mob-sweep", map, mobIds);
            }
            session.markPassRecoveryMobSweep(nowMs);
            log.warn("LPQ Stage {} missing-pass recovery killed {} stalled mobs with drops: "
                            + "session={} partyPasses={}/{} maps={} phaseElapsedMs={} inactiveForMs={}",
                    stage, liveMobs, session.sessionId(), partyPasses, required,
                    maps.stream().map(MapleMap::getId).toList(),
                    Math.max(0L, nowMs - session.phaseEnteredAtMs()), inactiveForMs);
            return;
        }
        int missing = Math.max(0, required - partyPasses);
        if (missing == 0 || session.passRecoveryPassesAwarded()) return;
        if (!grantItem(session, AgentLpqRescue.Kind.PASS_AWARD, "missing-pass-award",
                leader, AgentLpqDefinition.PASS, (short) missing)) {
            AgentLpqTerminationService.fail(
                    session, "LPQ passes disappeared and the leader has no ETC space for recovery", nowMs);
            return;
        }
        session.markPassRecoveryPassesAwarded(nowMs);
        log.warn("LPQ Stage {} missing-pass recovery awarded {} passes to leader: "
                        + "session={} leader={}({}) partyPasses={}/{} mobs={} maps={}",
                stage, missing, session.sessionId(), leader.getName(), leader.getId(),
                partyPasses, required, liveMobs, maps.stream().map(MapleMap::getId).toList());
    }

    private static boolean memberOutsideStageMap(AgentLpqSession session, int stageMapId) {
        return session.members().stream()
                .map(member -> character(member.characterId()))
                .anyMatch(participant -> participant == null || participant.getMapId() != stageMapId);
    }

    private static boolean outsideMembersHaveCompletedRooms(
            AgentLpqSession session, int stage, int stageMapId) {
        List<Integer> rooms = AgentLpqDefinition.roomMaps(stage);
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant == null) return false;
            if (participant.getMapId() == stageMapId) continue;
            if (!rooms.contains(participant.getMapId())
                    || !session.rooms().completed(participant.getMapId())) return false;
        }
        return true;
    }

    private static void purgeResidualPasses(AgentLpqSession session, int clearedStage) {
        int removed = 0;
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            int quantity = participant == null ? 0
                    : participant.getItemQuantity(AgentLpqDefinition.PASS, false);
            if (quantity <= 0) continue;
            AgentInventoryGatewayRuntime.inventory().removeById(
                    participant, InventoryType.ETC, AgentLpqDefinition.PASS,
                    quantity, false, false);
            removed += quantity;
        }
        if (removed > 0) {
            log.info("LPQ cleared Stage {} residual pass cleanup removed {} coupons: session={}",
                    clearedStage, removed, session.sessionId());
        }
    }

    static boolean stageFiveRoomCeilingRecoveryDue(long roomElapsedMs) {
        return roomElapsedMs >= STAGE_5_ROOM_CEILING_MS;
    }

    static boolean stageFiveMissingRoomPassRecoveryDue(
            int stage, int activeReactors, boolean loosePass,
            int roomPasses, int passQuota, long settledForMs) {
        return stage == 5
                && activeReactors == 0
                && !loosePass
                && passQuota > 0
                && roomPasses == passQuota - 1
                && settledForMs >= STAGE_5_MISSING_ROOM_PASS_SETTLE_MS;
    }

    private static void recoverStageFiveRoomAtCeiling(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, int mainMapId, long nowMs) {
        int roomMapId = agent.getMapId();
        boolean firstAttempt = !member.stageFiveCeilingRecoveryAppliedFor(roomMapId);
        if (firstAttempt) member.markStageFiveCeilingRecoveryApplied(roomMapId);

        List<Reactor> unresolved = activeReactors(agent);
        int reactorHits = remoteBoxHits(session, "stage5-room-ceiling", agent, unresolved);
        int vacuumed = vacuumPassDrops(session, "stage5-room-ceiling", agent, Set.of(agent.getMap()));
        member.clearReactorWork();

        int passQuota = AgentLpqDefinition.roomPassQuota(roomMapId);
        int roomPasses = member.roomPassesCollectedFor(roomMapId,
                agent.getItemQuantity(AgentLpqDefinition.PASS, false));
        int activeAfter = activeReactors(agent).size();
        if (firstAttempt) {
            log.warn("LPQ Stage 5 four-minute room recovery activated: session={} "
                            + "member={}({}) room={} unresolvedBoxes={} reactorHits={} "
                            + "vacuumedPasses={} passes={}/{}",
                    session.sessionId(), agent.getName(), agent.getId(), roomMapId,
                    unresolved.size(), reactorHits, vacuumed, roomPasses, passQuota);
        }
        if (activeAfter > 0 || roomPasses < passQuota) return;

        // A failed box approach commonly leaves the Dark Sight runner attached to
        // the centre rope. Ground it at the authored exit before asking the normal
        // utility-buff runtime to cast; that runtime correctly rejects rope casts.
        if (roomMapId == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM) {
            Integer exitPortalId = portalIdTo(agent, mainMapId);
            Point exitPortal = exitPortalId == null
                    ? null : ACTIONS.portalPosition(agent, exitPortalId);
            Point groundedExit = exitPortal == null
                    ? null : ACTIONS.groundPoint(agent.getMap(), exitPortal);
            Point recoveryPosition = groundedExit == null ? exitPortal : groundedExit;
            if (recoveryPosition != null) {
                ACTIONS.stop(entry);
                rescueTeleport(session, "stage5-room-ceiling", entry, agent, recoveryPosition);
            }
        }

        long durationMs = member.finishRoomTiming(roomMapId, nowMs);
        member.recordStageFiveEarnedPasses(passQuota);
        session.rooms().complete(roomMapId);
        member.beginRoomExit(roomMapId, nowMs);
        if (prepareDarkSightRoomExit(
                5, roomMapId, entry, agent, member, nowMs)) {
            log.info("LPQ Stage 5 room recovery prepared Dark Sight before exit: "
                            + "session={} member={}({}) room={} passes={}/{}",
                    session.sessionId(), agent.getName(), agent.getId(), roomMapId,
                    roomPasses, passQuota);
            // The ceiling recovery has already committed the room and raised the
            // party pass total. Do not yield here: coupon regroup can take over on
            // the next coordinator pass and stop ticking split-room traversal.
        }
        boolean exited = forceRoomExitThroughPortal(
                session, member, entry, agent, mainMapId, nowMs);
        log.warn("LPQ Stage 5 four-minute room recovery completed: session={} "
                        + "member={}({}) room={} durationMs={} reactorHits={} "
                        + "vacuumedPasses={} passes={}/{} exitedThroughPortal={}",
                session.sessionId(), agent.getName(), agent.getId(), roomMapId,
                durationMs, reactorHits, vacuumed, roomPasses, passQuota, exited);
    }

    static boolean missingPassMobSweepAllowed(int stage) {
        return stage != 5;
    }

    static boolean balloonRallyRecoveryDue(int stage, long partyRallyElapsedMs) {
        return partyRallyElapsedMs >= recovery(stage).rallyRecoveryMs();
    }

    static boolean balloonRallyPlacementRecoveryDue(
            int stage, long partyRallyElapsedMs, boolean agentLeader) {
        return agentLeader && balloonRallyRecoveryDue(stage, partyRallyElapsedMs);
    }

    static boolean balloonRallyForceTransferDue(int stage, long partyRallyElapsedMs) {
        long recoveryMs = recovery(stage).rallyRecoveryMs();
        return recoveryMs > 0L && partyRallyElapsedMs >= recoveryMs * 2L;
    }

    static boolean postClearTransitionRecoveryDue(int stage, long transitionElapsedMs) {
        return transitionElapsedMs >= recovery(stage).rallyRecoveryMs();
    }

    static boolean stageTransitionCatchUpRecoveryDue(int destinationStage, long phaseElapsedMs) {
        return destinationStage != 2
                && phaseElapsedMs >= recovery(destinationStage).rallyRecoveryMs();
    }

    static boolean stageOneCombatCeilingReached(int stage, long phaseEnteredAtMs,
                                                long nowMs, long ceilingMs) {
        return stage == 1
                && ceilingMs > 0L
                && nowMs - phaseEnteredAtMs >= ceilingMs;
    }

    static boolean allSplitRoomsEntered(AgentLpqSession session, int stage) {
        if (session == null) return false;
        List<Integer> rooms = AgentLpqDefinition.roomMaps(stage);
        return rooms.isEmpty() || session.rooms().enteredAll(rooms);
    }

    private static int activeReactorCount(AgentLpqSession session, Set<MapleMap> maps) {
        EventInstanceManager event = session.eventInstance();
        int count = 0;
        for (MapleMap map : maps) {
            for (Reactor reactor : map.getAllReactors()) {
                if (reactor == null || !reactor.isAlive() || !reactor.isActive()) continue;
                if (event != null && event.getProperty(ReactorActionManager.eventReactorActionKey(
                        "lpq", map.getId(), reactor.getObjectId())) != null) continue;
                count++;
            }
        }
        return count;
    }

    static boolean passHandoffRecoveryApplicable(int stage, int partyPasses, int required) {
        if (partyPasses < required) return false;
        return stage == 4 || stage == 5 || stage == 7;
    }

    static PassHandoffExitAction passHandoffExitAction(int stage, long exitInactiveMs) {
        AgentLpqStageRecoveryPolicy recovery = recovery(stage);
        if (exitInactiveMs >= recovery.portalMs()) {
            return PassHandoffExitAction.MOVE_TO_STAGE;
        }
        // Stage 4's dark rooms have a fully authored grounded return overlay.
        // Preserve it for the complete natural-navigation window instead of
        // visibly placing magicians across the room after only 15 seconds.
        if (stage == 4) return PassHandoffExitAction.NAVIGATE;
        if (exitInactiveMs >= recovery.roomExitPlacementMs()) {
            return PassHandoffExitAction.PLACE_AT_PORTAL;
        }
        return PassHandoffExitAction.NAVIGATE;
    }

    static boolean roomExitPortalPlacementDue(
            int stage, long exitInactiveMs, long exitElapsedMs) {
        AgentLpqStageRecoveryPolicy policy = recovery(stage);
        long threshold = stage == 4
                ? policy.portalMs() : policy.roomExitPlacementMs();
        return exitInactiveMs >= threshold && exitElapsedMs >= threshold;
    }

    private static boolean recoverPassHandoffAtNpc(
            AgentLpqSession session, Character leader,
            AgentLpqDefinition.Stage contract, long nowMs) {
        int mainMapId = contract.mapId();
        List<Integer> roomMaps = AgentLpqDefinition.roomMaps(contract.number());
        long recoveryElapsedMs = session.passHandoffRecoveryElapsed(nowMs);
        Point npc = stageNpcPosition(session, contract.number());
        boolean everyoneOnMainMap = true;
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant == null) {
                everyoneOnMainMap = false;
                continue;
            }
            if (participant.getMapId() == mainMapId) {
                if (npc != null && member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                    AgentRuntimeEntry participantEntry = entry(participant.getId());
                    if (participantEntry != null && prepareStageFiveMainMapDarkSight(
                            member, participantEntry, participant, nowMs)) {
                        continue;
                    }
                    rallyMemberAtNpc(member, participant, participantEntry, npc);
                }
                continue;
            }
            everyoneOnMainMap = false;
            PassHandoffExitAction exitAction = passHandoffExitAction(
                    contract.number(), recoveryElapsedMs);
            if (roomMaps.contains(participant.getMapId())
                    && member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                AgentRuntimeEntry participantEntry = entry(participant.getId());
                if (participantEntry == null) continue;
                int roomMapId = participant.getMapId();
                member.beginRoomExit(roomMapId, nowMs);
                if (exitAction != PassHandoffExitAction.MOVE_TO_STAGE
                        && prepareDarkSightRoomExit(contract.number(), roomMapId,
                        participantEntry, participant, member, nowMs)) {
                    continue;
                }
                if (exitAction == PassHandoffExitAction.NAVIGATE) {
                    enterPortalTo(participantEntry, participant, mainMapId);
                    continue;
                }
                if (exitAction == PassHandoffExitAction.PLACE_AT_PORTAL) {
                    forceRoomExitThroughPortal(
                            session, member, participantEntry, participant, mainMapId, nowMs);
                    continue;
                }
            } else if (roomMaps.contains(participant.getMapId())
                    && member.memberType() == AgentLpqMemberState.MemberType.HUMAN
                    && exitAction != PassHandoffExitAction.MOVE_TO_STAGE) {
                continue;
            }
            if (exitAction == PassHandoffExitAction.MOVE_TO_STAGE) {
                int sourceMapId = participant.getMapId();
                if (moveWithinEvent(session, "pass-handoff-stage-map", participant, mainMapId, nowMs)) {
                    member.assign(AgentLpqMemberState.Role.GENERAL, 0);
                    member.clearTraversalProgress();
                    log.warn("LPQ hard current-stage handoff recovery: session={} stage={} "
                                    + "member={}({}) sourceMap={} destination={} elapsedMs={}",
                            session.sessionId(), contract.number(), participant.getName(),
                            participant.getId(), sourceMapId, mainMapId, recoveryElapsedMs);
                }
            }
        }
        if (!everyoneOnMainMap) {
            session.observeMainMapRally(mainMapId, false, nowMs);
            return false;
        }

        session.beginCouponRegroup(contract.number(), nowMs);
        regroupCouponsAtNpc(session, leader, contract, nowMs);
        npc = ACTIONS.npcPosition(leader, contract.npcId());
        if (npc == null) return false;
        boolean everyoneAtNpc = true;
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant == null || participant.getMapId() != mainMapId) {
                everyoneAtNpc = false;
                continue;
            }
            if (near(participant.getPosition(), npc, INTERACTION_RADIUS)) continue;
            AgentRuntimeEntry participantEntry = entry(participant.getId());
            if (member.memberType() == AgentLpqMemberState.MemberType.AGENT
                    && participantEntry != null) {
                rallyMemberAtNpc(member, participant, participantEntry, npc);
                everyoneAtNpc = false;
                continue;
            } else {
                everyoneAtNpc = false;
            }
        }
        return everyoneAtNpc && leader.getItemQuantity(
                AgentLpqDefinition.PASS, false) >= contract.submissionCount();
    }

    static boolean forceRoomExitThroughPortal(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, int destinationMapId, long nowMs) {
        int sourceMapId = agent.getMapId();
        Integer portalId = portalIdTo(agent, destinationMapId);
        Point portal = portalId == null ? null : ACTIONS.portalPosition(agent, portalId);
        if (portalId == null || portal == null) return false;
        if (session.companionAdopted()) {
            return walkRoomExit(session, member, entry, agent, portalId, portal, destinationMapId, nowMs);
        }
        ACTIONS.stop(entry);
        rescueTeleport(session, "room-exit-portal", entry, agent, portal);
        boolean exited = LPQ.enterPortal(agent, portalId);
        if (!exited) return false;
        session.rooms().release(sourceMapId);
        member.assign(AgentLpqMemberState.Role.GENERAL, 0);
        member.clearTraversalProgress();
        session.markProgress(nowMs);
        log.warn("LPQ authored room-exit recovery: session={} member={}({}) sourceMap={} "
                        + "portal={} destination={}", session.sessionId(), agent.getName(),
                agent.getId(), sourceMapId, portalId, destinationMapId);
        return true;
    }

    private static int vacuumPassDrops(AgentLpqSession session, String site,
                                       Character recipient, Set<MapleMap> maps) {
        return vacuumItemDrops(session, site, recipient, maps, AgentLpqDefinition.PASS);
    }

    private static int recoverPassDrops(AgentLpqSession session, Character leader,
                                        int stage, Set<MapleMap> maps) {
        if (stage != 4 && stage != 5) return vacuumPassDrops(session, "missing-pass-drops", leader, maps);
        int vacuumed = 0;
        int mainMapId = AgentLpqDefinition.stage(stage).mapId();
        for (MapleMap map : maps) {
            Character recipient = map.getId() == mainMapId
                    ? leader : splitRoomPassRecipient(session, map.getId());
            if (recipient == null) continue;
            vacuumed += vacuumPassDrops(session, "missing-pass-room-drops", recipient, Set.of(map));
        }
        return vacuumed;
    }

    private static Character splitRoomPassRecipient(AgentLpqSession session, int roomMapId) {
        Integer ownerId = session.rooms().owner(roomMapId);
        Character owner = ownerId == null ? null : character(ownerId);
        if (owner != null && owner.getMapId() == roomMapId) return owner;
        for (AgentLpqMemberState member : session.members()) {
            if (member.assignedMapId() != roomMapId) continue;
            Character participant = character(member.characterId());
            if (participant != null && participant.getMapId() == roomMapId) return participant;
        }
        return null;
    }

    /**
     * A rescue: takes every live drop of {@code itemId} lying in {@code maps} into the recipient's bag, wherever he
     * stands (no walk, no loot range). One 'LPQ rescue:' line with the total, when it took any.
     */
    static int vacuumItemDrops(AgentLpqSession session, String site,
                               Character recipient, Set<MapleMap> maps, int itemId) {
        if (AgentLpqRescue.withhold(session, AgentLpqRescue.Kind.VACUUM, site, recipient, "item=" + itemId)) return 0;
        int vacuumed = 0;
        for (MapleMap map : maps) {
            for (MapItem drop : map.getDroppedItems()) {
                if (drop == null || drop.isPickedUp() || drop.getItemId() != itemId
                        || drop.getItem() == null) continue;
                drop.lockItem();
                try {
                    if (drop.isPickedUp()) continue;
                    short quantity = drop.getItem().getQuantity();
                    if (quantity <= 0 || !AgentInventoryGatewayRuntime.inventory().addItem(
                            recipient, itemId, quantity)) continue;
                    AgentMapGatewayRuntime.map().removeItemDrop(map, drop, 2, recipient.getId());
                    vacuumed += quantity;
                } finally {
                    drop.unlockItem();
                }
            }
        }
        if (vacuumed > 0) {
            AgentLpqRescue.note(session, AgentLpqRescue.Kind.VACUUM, site, recipient,
                    "item=" + itemId + " quantity=" + vacuumed);
        }
        return vacuumed;
    }

    /**
     * A rescue: places the member at {@code target} at once (a pose teleport), however far it is or whatever lies
     * between. A site without its session passes null; the note finds it by the member.
     */
    static boolean rescueTeleport(AgentLpqSession session, String site, AgentRuntimeEntry entry,
                                  Character agent, Point target) {
        if (AgentLpqRescue.withhold(session, AgentLpqRescue.Kind.TELEPORT, site, agent,
                "map=" + agent.getMapId() + " to=" + (target == null ? "-" : target.x + "," + target.y))) return false;
        ACTIONS.stagePosition(entry, agent, target);
        AgentLpqRescue.note(session, AgentLpqRescue.Kind.TELEPORT, site, agent,
                "map=" + agent.getMapId() + " to=" + (target == null ? "-" : target.x + "," + target.y));
        return true;
    }

    /**
     * A rescue: hits each live box of the member's room from wherever it stands, up to 16 times a box, until its
     * script destroys it. Returns the hits; one 'LPQ rescue:' line when there were any.
     */
    static int remoteBoxHits(AgentLpqSession session, String site, Character agent, List<Reactor> reactors) {
        if (AgentLpqRescue.withhold(session, AgentLpqRescue.Kind.REMOTE_REACTOR, site, agent,
                "map=" + agent.getMapId() + " boxes=" + reactors.size())) return 0;
        int reactorHits = 0;
        for (Reactor reactor : reactors) {
            // LPQ boxes use a small authored hit-state chain. Keep advancing the
            // same live object until its normal script destroys it and emits its pass.
            for (int attempt = 0; attempt < 16
                    && reactor.isAlive() && reactor.isActive()
                    && activeReactors(agent).stream().anyMatch(candidate ->
                    candidate.getObjectId() == reactor.getObjectId()); attempt++) {
                if (!ACTIONS.hitReactor(agent, reactor.getObjectId())) break;
                reactorHits++;
            }
        }
        if (reactorHits > 0) {
            AgentLpqRescue.note(session, AgentLpqRescue.Kind.REMOTE_REACTOR, site, agent,
                    "map=" + agent.getMapId() + " boxes=" + reactors.size() + " hits=" + reactorHits);
        }
        return reactorHits;
    }

    /**
     * A rescue: puts items in a member's bag that no drop or player gave him (a pass, the boss key, a door-marker
     * potion). Returns whether the bag took them; one 'LPQ rescue:' line only when it did.
     */
    static boolean grantItem(AgentLpqSession session, AgentLpqRescue.Kind kind, String site,
                             Character character, int itemId, short quantity) {
        if (AgentLpqRescue.withhold(session, kind, site, character, "item=" + itemId + " quantity=" + quantity)) {
            return false;
        }
        boolean granted = AgentInventoryGatewayRuntime.inventory().addItem(character, itemId, quantity);
        if (granted) {
            AgentLpqRescue.note(session, kind, site, character, "item=" + itemId + " quantity=" + quantity);
        }
        return granted;
    }

    /** A rescue: kills these mobs of the map outright, with their drops. One 'LPQ rescue:' line when there were any. */
    static void killWithDrops(AgentLpqSession session, String site, MapleMap map, List<Integer> mobIds) {
        if (AgentLpqRescue.withhold(session, AgentLpqRescue.Kind.KILL_WITH_DROPS, site, null,
                "map=" + map.getId() + " mobs=" + mobIds.size())) return;
        for (int mobId : mobIds) map.killMonsterWithDrops(mobId);
        if (!mobIds.isEmpty()) {
            AgentLpqRescue.note(session, AgentLpqRescue.Kind.KILL_WITH_DROPS, site, null,
                    "map=" + map.getId() + " mobs=" + mobIds.size());
        }
    }

    private static int partyPassCount(AgentLpqSession session) {
        return partyItemCount(session, AgentLpqDefinition.PASS);
    }

    private static int partyItemCount(AgentLpqSession session, int itemId) {
        int total = 0;
        for (AgentLpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character != null) total += character.getItemQuantity(itemId, false);
        }
        return total;
    }

    private static Set<MapleMap> collectionMaps(AgentLpqSession session, int stage) {
        Set<Integer> allowed = new LinkedHashSet<>(AgentLpqDefinition.roomMaps(stage));
        allowed.add(AgentLpqDefinition.stage(stage).mapId());
        if (stage == 2) allowed.add(922_010_201);
        Set<MapleMap> maps = new LinkedHashSet<>();
        var event = session.eventInstance();
        if (event == null) return maps;
        for (int mapId : allowed) {
            MapleMap map = event.getMapInstance(mapId);
            if (map != null) maps.add(map);
        }
        return maps;
    }

    static boolean recoverStageTwoTrap(AgentLpqSession session, String site, Character agent,
                                       long nowMs) {
        if (AgentLpqRescue.withhold(session, AgentLpqRescue.Kind.TRAP_RETURN, site, agent,
                "from=" + agent.getMapId())) return false;
        var event = session.eventInstance();
        MapleMap destination = event == null ? null
                : event.getMapInstance(AgentLpqDefinition.stage(2).mapId());
        Portal spawn = destination == null ? null : destination.getRandomPlayerSpawnpoint();
        if (destination == null || spawn == null) return false;
        log.warn("LPQ Stage 2 trap portal recovery: session={} member={}({}) sourceMap={} destination={}",
                session.sessionId(), agent.getName(), agent.getId(), agent.getMapId(), destination.getId());
        int sourceMapId = agent.getMapId();
        AgentMapGatewayRuntime.map().changeMapNear(agent, destination, spawn.getPosition());
        session.markProgress(nowMs);
        AgentLpqRescue.note(session, AgentLpqRescue.Kind.TRAP_RETURN, site, agent,
                "from=" + sourceMapId + " to=" + destination.getId());
        return true;
    }

    static boolean useStageTwoTrapExit(AgentLpqSession session, AgentRuntimeEntry entry,
                                               Character agent, long nowMs) {
        int destinationMapId = AgentLpqDefinition.stage(2).mapId();
        AgentLpqMemberState member = session.member(agent.getId());
        long inactiveForMs = member == null ? 0L
                : traversalInactivity(member, agent, destinationMapId, nowMs);
        Integer portalId = portalIdTo(agent, destinationMapId);
        boolean stalled = inactiveForMs >= recovery(2).portalMs();
        if (portalId != null && !stalled) return enterPortalTo(entry, agent, destinationMapId);
        if (session.companionAdopted()) {
            // A player in the trap room walks back to its exit portal; a stalled route is re-planned.
            ACTIONS.refreshNavigation(entry, agent);
            if (member != null) member.clearTraversalProgress();
            return portalId != null && enterPortalTo(entry, agent, destinationMapId);
        }
        ACTIONS.stop(entry);
        boolean recovered = recoverStageTwoTrap(session, "trap-exit-stalled", agent, nowMs);
        if (recovered && member != null) member.clearTraversalProgress();
        return recovered;
    }

    static boolean restoreEventMember(AgentLpqSession session, String site, Character agent,
                                      int destinationMapId, long nowMs) {
        if (AgentLpqRescue.withhold(session, AgentLpqRescue.Kind.EVENT_REREGISTER, site, agent,
                "destination=" + destinationMapId)) return false;
        var event = session.eventInstance();
        MapleMap destination = event == null ? null : event.getMapInstance(destinationMapId);
        Portal spawn = destination == null ? null : destination.getRandomPlayerSpawnpoint();
        if (event == null || destination == null || spawn == null) return false;
        event.registerPlayer(agent, false);
        AgentMapGatewayRuntime.map().changeMapNear(agent, destination, spawn.getPosition());
        session.markProgress(nowMs);
        log.warn("LPQ event-member recovery: session={} member={}({}) destination={}",
                session.sessionId(), agent.getName(), agent.getId(), destinationMapId);
        AgentLpqRescue.note(session, AgentLpqRescue.Kind.EVENT_REREGISTER, site, agent,
                "destination=" + destinationMapId);
        return true;
    }

    private static void restoreStageFiveEarnedPasses(
            AgentLpqSession session, AgentLpqMemberState member,
            Character agent, long nowMs) {
        int earned = member.stageFiveUnhandedPasses();
        int held = agent.getItemQuantity(AgentLpqDefinition.PASS, false);
        int missing = stageFivePassRestoreCount(earned, held);
        if (missing == 0) return;
        if (!grantItem(session, AgentLpqRescue.Kind.PASS_RESTORE, "stage5-earned-passes",
                agent, AgentLpqDefinition.PASS, (short) missing)) {
            AgentLpqTerminationService.fail(session,
                    "LPQ Stage 5 earned passes could not be restored after member revival", nowMs);
            return;
        }
        session.markProgress(nowMs);
        log.warn("LPQ Stage 5 restored {} earned passes after event-exclusive death cleanup: "
                        + "session={} member={}({}) heldBefore={} earned={}",
                missing, session.sessionId(), agent.getName(), agent.getId(), held, earned);
    }

    static int stageFivePassRestoreCount(int earned, int held) {
        return Math.max(0, earned - held);
    }

    static boolean moveWithinEvent(AgentLpqSession session, String site, Character agent,
                                   int destinationMapId, long nowMs) {
        if (AgentLpqRescue.withhold(session, AgentLpqRescue.Kind.MAP_MOVE, site, agent,
                "from=" + agent.getMapId() + " to=" + destinationMapId)) return false;
        var event = session.eventInstance();
        MapleMap destination = event == null ? null : event.getMapInstance(destinationMapId);
        Portal spawn = destination == null ? null : destination.getRandomPlayerSpawnpoint();
        if (destination == null || spawn == null) return false;
        int sourceMapId = agent.getMapId();
        AgentMapGatewayRuntime.map().changeMapNear(agent, destination, spawn.getPosition());
        session.markProgress(nowMs);
        AgentLpqRescue.note(session, AgentLpqRescue.Kind.MAP_MOVE, site, agent,
                "from=" + sourceMapId + " to=" + destinationMapId);
        return true;
    }

    private static void submit(AgentLpqSession session, Character leader,
                               AgentLpqDefinition.Stage stage, long nowMs) {
        long readyForMs = session.observeSubmissionReady(true, nowMs);
        if (!isAgent(leader)) {
            return;
        }
        if (LPQ.event(leader) != session.eventInstance()) {
            restoreEventMember(session, "agent-leader-reentry", leader, stage.mapId(), nowMs);
            return;
        }
        AgentRuntimeEntry entry = entry(leader.getId());
        AgentLpqMemberState leaderState = session.member(leader.getId());
        if (leaderState != null && nowMs < leaderState.nextActionAtMs()) return;
        Point npc = ACTIONS.npcPosition(leader, stage.npcId());
        if (entry == null) return;
        boolean nearNpc = npc != null && near(leader.getPosition(), npc, INTERACTION_RADIUS);
        if (!nearNpc && readyForMs < recovery(stage.number()).submissionMs()) {
            if (npc != null) ACTIONS.navigate(entry, npc, true);
            return;
        }
        ACTIONS.stop(entry);
        String clearBefore = LPQ.property(leader, stage.number() + "stageclear");
        if (LPQ.runNpc(leader, stage.npcId(), 1)) {
            // Noted only once the run started: one that did not start is retried next tick, with no line.
            if (!nearNpc) AgentLpqRescue.note(session, AgentLpqRescue.Kind.REMOTE_NPC, "remote-submit", leader,
                    "readyForMs=" + readyForMs);
            if (leaderState != null) leaderState.deferUntil(nowMs + INTERACTION_RETRY_MS);
            String clearAfter = LPQ.property(leader, stage.number() + "stageclear");
            if (clearBefore == null && clearAfter != null) {
                purgeResidualPasses(session, stage.number());
                session.markProgress(nowMs);
            }
            if (!nearNpc && clearAfter != null) {
                log.warn("LPQ Stage {} submission navigation recovery: session={} leader={}({}) readyForMs={}",
                        stage.number(), session.sessionId(), leader.getName(), leader.getId(), readyForMs);
            }
        }
    }

    private static void movePartyToNextStage(AgentLpqSession session, int stage, long nowMs) {
        if (stage == 1) {
            session.transition(AgentLpqSession.Phase.STAGE_2, nowMs);
            advanceStageTwoScoutProtocol(session, nowMs);
            return;
        }
        movePartyToNextStageTogether(session, stage, nowMs);
    }

    static void movePartyToNextStageTogether(
            AgentLpqSession session, int stage, long nowMs) {
        int source = AgentLpqDefinition.stage(stage).mapId();
        int destination = stage == 9
                ? AgentLpqDefinition.CLEAR_MAP : AgentLpqDefinition.stage(stage + 1).mapId();
        long transitionElapsedMs = session.beginOrObservePostClearTransition(nowMs);
        boolean recoveryDue = !session.companionAdopted() && postClearTransitionRecoveryDue(stage, transitionElapsedMs);
        if (session.companionAdopted()) nudgeLeaderToPortal(session, stage, source, destination, transitionElapsedMs);
        boolean allReady = true;
        int recovered = 0;
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant == null) {
                allReady = false;
                continue;
            }
            if (participant.getMapId() == destination) continue;
            if (participant.getMapId() != source) {
                allReady = false;
                if (recoveryDue
                        && member.memberType() == AgentLpqMemberState.MemberType.AGENT
                        && moveWithinEvent(session, "next-stage-lagging", participant, destination, nowMs)) {
                    cancelStageFiveDarkSightAtTransition(stage, member, participant);
                    member.clearTraversalProgress();
                    recovered++;
                }
                continue;
            }
            Integer portalId = portalIdTo(participant, destination);
            Point portal = portalId == null ? null : ACTIONS.portalPosition(participant, portalId);
            if (portal == null || !near(participant.getPosition(), portal, INTERACTION_RADIUS)) {
                allReady = false;
                if (member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                    AgentRuntimeEntry entry = entry(member.characterId());
                    if (entry != null && recoveryDue) {
                        if (portal == null) {
                            if (moveWithinEvent(session, "next-stage-no-portal", participant, destination, nowMs)) {
                                cancelStageFiveDarkSightAtTransition(stage, member, participant);
                                member.clearTraversalProgress();
                                recovered++;
                            }
                        } else {
                            ACTIONS.stop(entry);
                            rescueTeleport(session, "next-stage-portal", entry, participant, portal);
                            member.clearTraversalProgress();
                            recovered++;
                        }
                    } else if (entry != null && portal != null) {
                        // Keep the ordinary post-clear transition on the same authored
                        // waypoint/rope overlay used by every other LPQ portal approach.
                        // A direct request for the final portal can otherwise select a
                        // visually invalid long graph edge and look like a cross-map zip.
                        enterPortalTo(entry, participant, destination);
                    }
                }
            }
        }
        if (recovered > 0) {
            session.markProgress(nowMs);
            log.warn("LPQ Stage {} shared post-clear transition recovery moved {} lagging "
                            + "members after {}ms: session={} destination={}",
                    stage, recovered, transitionElapsedMs, session.sessionId(), destination);
            return;
        }
        if (!allReady) return;
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent != null && entry != null && agent.getMapId() == source) {
                cancelStageFiveDarkSightAtTransition(stage, member, agent);
                enterPortalTo(entry, agent, destination);
            }
        }
    }

    private static void cancelStageFiveDarkSightAtTransition(
            int stage, AgentLpqMemberState member, Character participant) {
        if (stage == 5 && member.stageFiveDarkSightRunner()) {
            cancelDarkSight(participant);
        }
    }

    private static void advanceStageTwoScoutProtocol(AgentLpqSession session, long nowMs) {
        List<Integer> scoutIds = stageTwoScoutIds(
                session.members(), session.eventLeaderId(), AgentLpqDefinition.STAGE_2_SCOUT_COUNT);
        if (scoutIds.isEmpty()) return;

        Character speaker = scoutIds.stream().map(AgentLpqCoordinator::character)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (!session.stage2ScoutPlanAnnounced() && session.companionAdopted()) {
            // A companion run: one player-like line on the leader's map, never party plus map chat.
            Character leader = character(session.eventLeaderId());
            if (leader == null || coordinate(session, narrator(session, leader), "s2-scouts",
                    AgentLpqCompanionPlay.SCOUTS_GO_FIRST) || !session.memberCoordinationChatEnabled()) {
                session.markStage2ScoutPlanAnnounced(nowMs);
            }
        }
        if (!session.stage2ScoutPlanAnnounced() && !session.companionAdopted()
                && sendPartyAndAllChat(speaker, STAGE_2_SCOUT_CHAT)) {
            session.markStage2ScoutPlanAnnounced(nowMs);
        }

        int stageOneMap = AgentLpqDefinition.stage(1).mapId();
        int stageTwoMap = AgentLpqDefinition.stage(2).mapId();
        boolean trapReached = scoutIds.stream().map(AgentLpqCoordinator::character)
                .filter(java.util.Objects::nonNull)
                .anyMatch(scout -> scout.getMapId() == AgentLpqDefinition.STAGE_2_TRAP_MAP);
        if (!trapReached) {
            boolean allScoutsReady = true;
            for (int scoutId : scoutIds) {
                Character scout = character(scoutId);
                AgentRuntimeEntry scoutEntry = entry(scoutId);
                if (scout == null || scoutEntry == null) {
                    allScoutsReady = false;
                    continue;
                }
                if (scout.getMapId() == stageOneMap) {
                    enterStagePortalWithRecovery(session, session.member(scoutId),
                            scoutEntry, scout, stageTwoMap, nowMs, "Stage 2 scout entry");
                    allScoutsReady = false;
                } else if (scout.getMapId() != stageTwoMap) {
                    allScoutsReady = false;
                }
            }
            holdStageTwoWaitingAgents(session, scoutIds, stageOneMap);
            if (!allScoutsReady) return;

            for (int scoutId : scoutIds) {
                Character scout = character(scoutId);
                AgentRuntimeEntry scoutEntry = entry(scoutId);
                AgentLpqMemberState scoutState = session.member(scoutId);
                if (scout != null && scoutEntry != null && scoutState != null
                        && scout.getMapId() == stageTwoMap) {
                    hitStageTwoTrapReactor(scoutEntry, scout, nowMs, scoutState);
                }
            }
            return;
        }

        if (!session.stage2TrapClearAnnounced() && session.companionAdopted()) {
            // The party goes in on the clear whether or not anyone could say it.
            Character leader = character(session.eventLeaderId());
            coordinate(session, narrator(session, leader), "s2-clear", AgentLpqCompanionPlay.TRAP_CLEAR);
            session.markStage2TrapClearAnnounced(nowMs);
        }
        if (!session.stage2TrapClearAnnounced()
                && sendPartyAndAllChat(speaker, STAGE_2_TRAP_CLEAR_CHAT)) {
            session.markStage2TrapClearAnnounced(nowMs);
        }
        if (!session.stage2TrapClearAnnounced()) return;

        Character porter = stageTwoPorter(session, scoutIds, stageOneMap);
        for (AgentLpqMemberState member : session.members()) {
            if (scoutIds.contains(member.characterId())) continue;
            Character participant = character(member.characterId());
            if (participant == null || participant.getMapId() != stageOneMap) continue;
            if (member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                AgentRuntimeEntry participantEntry = entry(member.characterId());
                if (participantEntry != null && participant == porter) {
                    waitAtStageTwoPortal(session, participantEntry, participant, stageTwoMap, nowMs);
                } else if (participantEntry != null) enterStagePortalWithRecovery(
                        session, member, participantEntry, participant, stageTwoMap,
                        nowMs, "Stage 2 party entry");
            } else if (nowMs >= member.nextActionAtMs()) {
                String clearLine = voice(session, null, STAGE_2_TRAP_CLEAR_CHAT);
                if (clearLine != null) participant.dropMessage(6, clearLine);
                member.deferUntil(nowMs + 5_000L);
            }
        }
    }

    /**
     * A companion run whose leader is still on stage 1 after the trap clear keeps one companion with him: the first
     * companion standing on his map (never a scout). Null for a stock run, or once he has gone in.
     */
    private static Character stageTwoPorter(AgentLpqSession session, List<Integer> scoutIds, int stageOneMap) {
        if (!session.companionAdopted()) return null;
        Character leader = character(session.eventLeaderId());
        if (leader == null || leader.getMapId() != stageOneMap) return null;
        Character porter = narrator(session, leader);
        return porter == null || scoutIds.contains(porter.getId()) ? null : porter;
    }

    /**
     * The companion that stays with the leader walks to the stage-2 portal and waits there (it follows him in once he
     * has gone, as a laggard); LEADER_NUDGE_MS after the trap clear, with him still away from the portal, it calls him
     * once (N1).
     */
    private static void waitAtStageTwoPortal(AgentLpqSession session, AgentRuntimeEntry entry, Character porter,
                                             int stageTwoMap, long nowMs) {
        Integer portalId = portalIdTo(porter, stageTwoMap);
        Point portal = portalId == null ? null : ACTIONS.portalPosition(porter, portalId);
        if (portal == null) return;
        if (!near(porter.getPosition(), portal, INTERACTION_RADIUS)) {
            // The authored approach enterPortalTo walks, one leg at a time, without going through.
            Point waypoint = AgentLpqExitRoutePolicy.nextWaypoint(porter.getMapId(), stageTwoMap,
                    porter.getPosition(), portal);
            if (waypoint != null && near(porter.getPosition(), waypoint, INTERACTION_RADIUS)) {
                waypoint = AgentLpqExitRoutePolicy.nextWaypoint(porter.getMapId(), stageTwoMap, waypoint, portal);
            }
            ACTIONS.navigate(entry, waypoint != null ? waypoint : portal, true);
            return;
        }
        Character leader = character(session.eventLeaderId());
        if (leader != null && nowMs - session.stage2TrapClearAtMs() >= LEADER_NUDGE_MS
                && !near(leader.getPosition(), portal, INTERACTION_RADIUS)) {
            coordinate(session, porter, "s1-portal", AgentLpqCompanionPlay.portalOpenLine(leader.getName()));
        }
    }

    static void enterStagePortalWithRecovery(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, int destinationMapId,
            long nowMs, String context) {
        long inactiveForMs = member == null ? 0L
                : traversalInactivity(member, agent, destinationMapId, nowMs);
        enterPortalTo(entry, agent, destinationMapId);
        int sourceStage = AgentLpqDefinition.stageNumber(agent.getMapId());
        if (agent.getMapId() == destinationMapId || sourceStage < 1 || sourceStage > 9
                || !postClearTransitionRecoveryDue(sourceStage, inactiveForMs)) return;
        if (session.companionAdopted()) {
            ACTIONS.refreshNavigation(entry, agent);
            if (member != null) member.clearTraversalProgress();
            return;
        }
        int sourceMapId = agent.getMapId();
        if (!moveWithinEvent(session, "stage-portal-traversal", agent, destinationMapId, nowMs)) return;
        if (member != null) member.clearTraversalProgress();
        log.warn("LPQ {} traversal recovery: session={} member={}({}) source={} "
                        + "destination={} inactiveForMs={}", context, session.sessionId(),
                agent.getName(), agent.getId(), sourceMapId, destinationMapId, inactiveForMs);
    }

    private static void holdStageTwoWaitingAgents(AgentLpqSession session,
                                                  List<Integer> scoutIds,
                                                  int stageOneMap) {
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT
                    || scoutIds.contains(member.characterId())) continue;
            Character participant = character(member.characterId());
            AgentRuntimeEntry participantEntry = entry(member.characterId());
            if (participant != null && participant.getMapId() == stageOneMap
                    && participantEntry != null) {
                ACTIONS.stop(participantEntry);
            }
        }
    }

    static List<Integer> stageTwoScoutIds(Collection<AgentLpqMemberState> members,
                                          int eventLeaderId,
                                          int requestedScoutCount) {
        if (members == null || requestedScoutCount <= 0) return List.of();
        return members.stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .sorted(Comparator
                        .comparing((AgentLpqMemberState member) ->
                                member.characterId() == eventLeaderId)
                        .thenComparingInt(AgentLpqMemberState::characterId))
                .limit(requestedScoutCount)
                .map(AgentLpqMemberState::characterId)
                .toList();
    }

    private static boolean sendPartyAndAllChat(Character speaker, String message) {
        if (speaker == null || message == null || message.isBlank()
                || !PARTY.sendPartyChat(speaker, message)) {
            return false;
        }
        AgentPacketGatewayRuntime.packets().broadcastChatText(speaker, message, false, 0);
        return true;
    }

    private static void tickMixedPartyFlavor(AgentLpqSession session, long nowMs) {
        if (!session.hasHumanMember()) return;
        int stage = stageNumberOrZero(session.phase());
        if (stage == 0) return;
        List<FlavorLine> lines = MIXED_PARTY_FLAVOR.get(stage);
        if (lines == null || nowMs - session.phaseEnteredAtMs() < lines.getFirst().delayMs()) return;
        Character human = session.members().stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.HUMAN)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        List<Character> speakers = session.members().stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .sorted(Comparator.comparingInt(AgentLpqMemberState::characterId))
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull).toList();
        if (human == null || speakers.isEmpty()) return;
        for (int index = 0; index < lines.size(); index++) {
            FlavorLine line = lines.get(index);
            if (nowMs - session.phaseEnteredAtMs() < line.delayMs()) continue;
            String key = "stage:" + stage + ":flavor:" + index;
            if (!session.claimFlavorChat(key)) continue;
            Character speaker = speakers.get(Math.floorMod(line.speakerOffset(), speakers.size()));
            String template = stage == 8 && human.getId() == session.eventLeaderId() && index == 0
                    ? "{human}, call the balloon after all five assigned spots are held."
                    : line.message();
            String message = template.replace("{human}", human.getName());
            if (speaker.getMapId() == human.getMapId()) {
                AgentPacketGatewayRuntime.packets().broadcastChatText(speaker, message, false, 0);
            } else {
                PARTY.sendPartyChat(speaker, message);
            }
        }
    }

    private static void portalMaze(AgentLpqSession session, Character leader, long nowMs) {
        catchUpLaggingMembers(session, 6, AgentLpqDefinition.stage(6).mapId(), nowMs);
        announceStageSixSequence(session, leader, nowMs);
        if (session.companionAdopted()) companionMazeClimb(session, leader, nowMs);
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null || agent.getMapId() != 922_010_600) continue;
            advancePortalMazeMember(session, member, agent, entry, nowMs);
        }
        if (stageSixPartyFinishedMaze(session)) {
            movePartyToNextStageTogether(session, 6, nowMs);
        }
    }

    private record FlavorLine(long delayMs, int speakerOffset, String message) { }

    private static boolean stageSixPartyFinishedMaze(AgentLpqSession session) {
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant == null) return false;
            if (participant.getMapId() == AgentLpqDefinition.stage(7).mapId()) continue;
            if (participant.getMapId() != AgentLpqDefinition.stage(6).mapId()
                    || participant.getPosition().y >= -3_050) return false;
        }
        return true;
    }

    private static void announceStageSixSequence(AgentLpqSession session, Character leader, long nowMs) {
        if (!session.stage6SequenceChatReady(nowMs)) return;
        if (session.companionAdopted()) {
            // A companion run: the code as a player says it, at the bottom where everyone starts.
            if (coordinate(session, narrator(session, leader), "s6-start", AgentLpqCompanionPlay.MAZE_CODE)
                    || !session.memberCoordinationChatEnabled()) {
                session.markStage6SequenceAnnounced(nowMs);
            }
            return;
        }
        int speakerId = stageSixAnnouncementSpeakerId(session.members(), session.eventLeaderId());
        Character speaker = speakerId == leader.getId() ? leader : character(speakerId);
        if (speaker != null && sendPartyAndAllChat(speaker, STAGE_6_SEQUENCE_CHAT.getFirst())) {
            session.markStage6SequenceAnnounced(nowMs);
        }
    }

    static int stageSixAnnouncementSpeakerId(Collection<AgentLpqMemberState> members, int eventLeaderId) {
        AgentLpqMemberState leader = members == null ? null : members.stream()
                .filter(member -> member.characterId() == eventLeaderId)
                .findFirst().orElse(null);
        if (leader != null && leader.memberType() == AgentLpqMemberState.MemberType.AGENT) {
            return eventLeaderId;
        }
        return members == null ? 0 : members.stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .mapToInt(AgentLpqMemberState::characterId)
                .min().orElse(0);
    }

    private static void advancePortalMazeMember(
            AgentLpqSession session, AgentLpqMemberState member,
            Character agent, AgentRuntimeEntry entry, long nowMs) {
        if (agent.getPosition().y < -3_050) {
            Integer portalId = portalIdTo(agent, 922_010_700);
            Portal portal = portalId == null ? null : agent.getMap().getPortal(portalId);
            if (portal != null && approachStageSixPortal(
                    session, member, entry, agent, portal,
                    STAGE_6_PORTAL_IDS.size(), nowMs)) {
                // A companion run's companions wait at the top portal and go through with him (or after him).
                if (session.companionAdopted() && !leaderOnMap(session, AgentLpqDefinition.stage(7).mapId())) return;
                LPQ.enterPortal(agent, portalId);
            }
            return;
        }
        int row = mazeRow(agent);
        if (row < 0) return;
        int portalId = stageSixPortalId(row);
        Portal portal = agent.getMap().getPortal(portalId);
        if (portal == null) return;
        if (!approachStageSixPortal(
                session, member, entry, agent, portal, row, nowMs)) return;
        enterMazePortal(session, member, agent, portalId, nowMs, row);
    }

    static boolean approachStageSixPortal(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, Portal portal,
            int row, long nowMs) {
        Point target = portal.getPosition();
        if (stageSixPortalApproachReady(
                agent.getPosition(), ACTIONS.grounded(agent), target)) {
            ACTIONS.stop(entry);
            member.clearTraversalProgress();
            member.clearDestinationApproachProgress();
            return true;
        }
        long distanceSq = agent.getPosition() == null
                ? Long.MAX_VALUE : (long) agent.getPosition().distanceSq(target);
        long inactiveForMs = member.observeTraversalProgress(
                agent.getMapId(), portal.getId(), agent.getPosition(), distanceSq, nowMs);
        if (inactiveForMs >= recovery(6).traversalMs()) {
            ACTIONS.stop(entry);
            if (!rescueTeleport(session, "stage6-portal-approach", entry, agent, target)) {
                // Withheld (a companion run): the row's portal is never entered from afar; re-plan and walk.
                ACTIONS.refreshNavigation(entry, agent);
                member.clearTraversalProgress();
                ACTIONS.navigate(entry, target, true);
                return false;
            }
            member.clearTraversalProgress();
            session.markProgress(nowMs);
            log.warn("LPQ Stage 6 authored portal approach recovery: session={} "
                            + "member={}({}) row={} portal={} target={} inactiveForMs={}",
                    session.sessionId(), agent.getName(), agent.getId(), row + 1,
                    portal.getId(), target, inactiveForMs);
            return true;
        }
        if (member.claimDestinationApproachRetry(inactiveForMs, nowMs, 3_000L)) {
            ACTIONS.refreshNavigation(entry, agent);
        }
        // The row's canonical portal is the complete overlay target. Do not let
        // generic navigation retarget a neighbouring portal while approaching it.
        ACTIONS.navigate(entry, target, true);
        return false;
    }

    static boolean stageSixPortalApproachReady(
            Point position, boolean grounded, Point portalPosition) {
        return grounded && near(position, portalPosition, INTERACTION_RADIUS);
    }

    private static void enterMazePortal(
            AgentLpqSession session, AgentLpqMemberState member, Character agent,
            int portalId, long nowMs, int row) {
        int beforeY = agent.getPosition().y;
        if (!LPQ.enterPortal(agent, portalId)) return;
        member.clearTraversalProgress();
        if (agent.getMapId() == 922_010_700) {
            session.markProgress(nowMs);
        } else if (agent.getPosition().y < beforeY - 80) {
            if (row >= 0) session.maze().recordSuccess(row, portalId);
            session.markProgress(nowMs);
        }
    }

    static int stageSixPortalId(int row) {
        if (row < 0 || row >= STAGE_6_PORTAL_IDS.size()) {
            throw new IllegalArgumentException("LPQ Stage 6 row must be between 0 and 14");
        }
        return STAGE_6_PORTAL_IDS.get(row);
    }

    static List<String> stageSixSequenceChats() {
        return STAGE_6_SEQUENCE_CHAT;
    }

    private static int mazeRow(Character agent) {
        Portal first = agent.getMap().getPortal(2);
        if (first != null && agent.getPosition().y > first.getPosition().y + 130) return 0;
        int bestRow = -1;
        double best = Double.MAX_VALUE;
        for (int row = 0; row < 15; row++) {
            Portal portal = agent.getMap().getPortal(2 + row * 3);
            if (portal == null) continue;
            double distance = Math.abs(agent.getPosition().y - portal.getPosition().y);
            if (distance < best) { best = distance; bestRow = row; }
        }
        // A knockback or an interrupted climb can leave a member between the
        // authored row bands. The nearest row is still the only meaningful
        // continuation, and approachWithRecovery provides the bounded escape.
        return bestRow;
    }

    private static void platformPuzzle(AgentLpqSession session, Character leader, long nowMs) {
        catchUpLaggingMembers(session, 8, AgentLpqDefinition.stage(8).mapId(), nowMs);
        if (LPQ.property(leader, "8stageclear") != null) {
            movePartyToNextStageTogether(session, 8, nowMs);
            return;
        }
        if ("-1".equals(LPQ.property(leader, "statusStg8"))) {
            coordinate(session, narrator(session, leader), "s8-start",
                    AgentLpqCompanionPlay.stageEightStartLine(leader.getName()));
            if (!rallyPartyAtNpc(session, leader, 8, nowMs)) return;
            if (!isAgent(leader)) {
                return;
            }
            AgentRuntimeEntry leaderEntry = entry(leader.getId());
            AgentLpqMemberState leaderState = session.member(leader.getId());
            if (leaderState != null && nowMs < leaderState.nextActionAtMs()) return;
            Point balloon = ACTIONS.npcPosition(leader, AgentLpqDefinition.stage(8).npcId());
            if (leaderEntry == null || balloon == null) return;
            if (leaderState == null || !approachWithRecovery(
                    session, leaderState, leaderEntry, leader, 922_010_800,
                    balloon, "Stage 8 initialization", nowMs)) {
                return;
            }
            if (LPQ.runNpc(leader, AgentLpqDefinition.stage(8).npcId())) {
                if (leaderState != null) leaderState.deferUntil(nowMs + INTERACTION_RETRY_MS);
                session.markProgress(nowMs);
            }
            return;
        }
        List<AgentLpqMemberState> participants = session.members().stream()
                .filter(member -> member.characterId() != session.eventLeaderId())
                .sorted(Comparator.comparingInt(AgentLpqMemberState::characterId)).toList();
        participants = withTheLeaderOnABox(session, participants);
        if (participants.size() < 5) {
            AgentLpqTerminationService.fail(session, "LPQ Stage 8 needs five nonleader participants", nowMs);
            return;
        }
        java.util.Map<Integer, Integer> assignments = session.stage8Assignments(
                participants.stream().map(AgentLpqMemberState::characterId).toList());
        if (session.stage8AssignmentChatEnabled() && !session.stage8AssignmentAnnounced()) {
            Character speaker = isAgent(leader) ? leader : participants.stream()
                    .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                    .map(member -> character(member.characterId()))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            String assignmentChat = voice(session, null, stageEightAssignmentChat(assignments));
            if (sendPartyAndAllChat(speaker, assignmentChat)) {
                session.markStage8AssignmentAnnounced(nowMs);
            }
        }
        boolean positioned = true;
        for (int index = 0; index < 5; index++) {
            AgentLpqMemberState member = participants.get(index);
            int platform = assignments.get(member.characterId());
            member.assign(index == 4 ? AgentLpqMemberState.Role.PLATFORM_MOVER
                    : AgentLpqMemberState.Role.PLATFORM_HOLDER, 922_010_800);
            member.assignPlatform(platform);
            Character participant = character(member.characterId());
            if (participant == null || participant.getMapId() != 922_010_800) { positioned = false; continue; }
            Rectangle area = participant.getMap().getAreas().get(platform - 1);
            if (area.contains(participant.getPosition())) {
                member.clearTraversalProgress();
                continue;
            }
            positioned = false;
            if (member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                AgentRuntimeEntry entry = entry(member.characterId());
                if (entry != null) occupyStageEightPlatformWithRecovery(
                        session, member, entry, participant, area, platform, nowMs);
            }
        }
        if (session.companionAdopted()) companionFormationLines(session, leader, participants, positioned);
        if (!positioned) return;
        if (!isAgent(leader)) {
            return;
        }
        AgentRuntimeEntry entry = entry(leader.getId());
        AgentLpqMemberState leaderState = session.member(leader.getId());
        if (leaderState != null && nowMs < leaderState.nextActionAtMs()) return;
        Point npc = ACTIONS.npcPosition(leader, AgentLpqDefinition.stage(8).npcId());
        if (entry == null || npc == null) return;
        if (leaderState == null || !approachWithRecovery(
                session, leaderState, entry, leader, 922_010_800,
                npc, "Stage 8 submission", nowMs)) return;
        if (LPQ.runNpc(leader, AgentLpqDefinition.stage(8).npcId(), 1)) {
            if (leaderState != null) leaderState.deferUntil(nowMs + INTERACTION_RETRY_MS);
            session.advanceStage8(nowMs);
        }
        if (LPQ.property(leader, "8stageclear") != null) {
            movePartyToNextStageTogether(session, 8, nowMs);
        }
    }

    static Point stageEightPlatformTarget(Rectangle area) {
        if (area == null) throw new IllegalArgumentException("LPQ Stage 8 platform area is required");
        // WZ areas straddle the standing foothold. Their center is slightly below that
        // foothold, which makes grounding select the next platform down. Approach from
        // the authored area's top edge so navigation resolves the intended box surface.
        return new Point((int) area.getCenterX(), area.y);
    }

    static String stageEightAssignmentChat(java.util.Map<Integer, Integer> assignments) {
        if (assignments == null || assignments.isEmpty()) return "";
        String mapping = assignments.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> {
                    Character member = character(entry.getKey());
                    return (member == null ? String.valueOf(entry.getKey()) : member.getName())
                            + "->" + entry.getValue();
                })
                .collect(java.util.stream.Collectors.joining(", "));
        return "Stage 8: " + mapping;
    }

    static boolean occupyStageEightPlatformWithRecovery(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, Rectangle area,
            int platform, long nowMs) {
        if (area.contains(agent.getPosition())) {
            member.clearTraversalProgress();
            return true;
        }
        Point target = stageEightPlatformTarget(area);
        long distanceSq = agent.getPosition() == null
                ? Long.MAX_VALUE : (long) agent.getPosition().distanceSq(target);
        long inactiveForMs = member.observeTraversalProgress(
                agent.getMapId(), 922_010_800, agent.getPosition(), distanceSq, nowMs);
        if (inactiveForMs < recovery(8).traversalMs()) {
            ACTIONS.navigate(entry, target, true);
            return false;
        }
        ACTIONS.stop(entry);
        if (!rescueTeleport(session, "stage8-platform", entry, agent, target)) {
            ACTIONS.refreshNavigation(entry, agent);
            member.clearTraversalProgress();
            ACTIONS.navigate(entry, target, true);
            return false;
        }
        member.clearTraversalProgress();
        session.markProgress(nowMs);
        log.warn("LPQ Stage 8 platform containment recovery: session={} member={}({}) "
                        + "platform={} target={} inactiveForMs={}", session.sessionId(),
                agent.getName(), agent.getId(), platform, target, inactiveForMs);
        return area.contains(agent.getPosition());
    }

    private static void boss(AgentLpqSession session, Character leader, long nowMs) {
        if (LPQ.property(leader, "9stageclear") != null) {
            movePartyToNextStageTogether(session, 9, nowMs);
            return;
        }
        catchUpLaggingMembers(session, 9, AgentLpqDefinition.stage(9).mapId(), nowMs);
        Set<Integer> lootClaims = new LinkedHashSet<>();
        boolean triggerRatzAlive = ACTIONS.liveMonsterCount(
                leader, Set.of(AgentLpqDefinition.BOSS_TRIGGER_RATZ)) > 0;
        Monster alishar = leader.getMap().getAllMonsters().stream()
                .filter(monster -> monster.getId() == AgentLpqDefinition.ALISHAR)
                .findFirst().orElse(null);
        boolean alisharAlive = alishar != null;
        observeStageNineBossDamage(session, alishar, nowMs);
        Reactor alisharReactor = activeReactors(leader).stream().findFirst().orElse(null);
        boolean rangedTriggerEngaged = triggerRatzAlive
                && session.members().stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(agent -> agent.getMapId() == AgentLpqDefinition.stage(9).mapId())
                .filter(agent -> agent.getHp() > 0)
                .filter(AgentLpqRosterRequirementPolicy::rangedAttack)
                .anyMatch(agent -> stageNineRatzFiringReady(
                        agent.getPosition(), ACTIONS.grounded(agent),
                        stageNineRatzFiringAnchor(agent.getId())));
        boolean cleanupPhase = !alisharAlive && !triggerRatzAlive && alisharReactor == null;
        // Once Alishar appears, shooters fight him from stations outside his reach and melee members
        // take his summons first (fightAlishar, LPQ base fix 5); the former formation gate stays unused.
        if (!alisharAlive) session.observeBossEdgeRegroup(false, nowMs);
        List<Integer> stageNineShooters = alisharAlive ? stageNineShooterIds(session) : List.of();
        boolean stageNineGroupsUnset = alisharAlive && session.members().stream()
                .allMatch(member -> member.stageNineGroup() == null);
        List<Point> stageNineSummons = alisharAlive
                ? ACTIONS.liveMonsterPositions(leader, AgentLpqDefinition.ALISHAR_SUMMONS) : List.of();
        if (session.companionAdopted()) selectKeyCarrier(session, leader);
        if (session.companionAdopted()) markAlishar(session, alishar, nowMs);
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null
                    || agent.getMapId() != AgentLpqDefinition.stage(9).mapId()) continue;
            // A dead member held on the map (LPQ base fix 1) gets no order, group, station or loot claim in any
            // phase: a corpse must not reserve a cleanup drop the living then skip, nor count as firing at the Ratz.
            if (agent.getHp() <= 0) continue;
            // A companion run's key carrier is handled by companionBossKey until it has handed the key over.
            if (session.companionAdopted() && member.characterId() == session.keyCarrierId()
                    && keyCarrierBusy(session, agent)) continue;
            if (member.characterId() == leader.getId()
                    && collectNearestDrop(entry, agent,
                            Set.of(AgentLpqDefinition.BOSS_KEY), lootClaims)) continue;
            if (cleanupPhase && collectNearestDrop(
                    entry, agent, null, lootClaims,
                    itemId -> stageNineAgentMayCollectItem(
                            member.characterId(), leader.getId(), session.keyCarrierId(), itemId))) continue;
            if (alisharAlive) {
                boolean joiningBossCombat = member.claimIntentAnnouncement(
                        "stage9:alishar-combat");
                member.assign(AgentLpqMemberState.Role.BOSS_ATTACKER,
                        AgentLpqDefinition.stage(9).mapId());
                if (joiningBossCombat) {
                    // Clear the firing-platform navigation or trigger wait exactly once,
                    // then schedule every Agent into the ordinary boss combat pipeline.
                    ACTIONS.stop(entry);
                    log.info("LPQ Stage 9 Alishar combat scheduled: session={} member={}({}) "
                                    + "position={} bossPosition={}",
                            session.sessionId(), agent.getName(), agent.getId(),
                            agent.getPosition(), alishar.getPosition());
                }
                fightAlishar(session, member, entry, agent, alishar.getPosition(), stageNineShooters,
                        stageNineGroupsUnset, stageNineSummons);
                continue;
            }
            if (triggerRatzAlive && AgentLpqRosterRequirementPolicy.rangedAttack(agent)) {
                member.assign(AgentLpqMemberState.Role.RANGED_TRIGGER,
                        AgentLpqDefinition.stage(9).mapId());
                engageBossTriggerRatz(session, member, entry, agent, nowMs);
                continue;
            }
            if (triggerRatzAlive || alisharReactor != null) {
                member.assign(AgentLpqMemberState.Role.BOSS_ATTACKER,
                        AgentLpqDefinition.stage(9).mapId());
                // Once the first ranged member is in position and firing, melee
                // members can use that trigger window to cross the floor naturally.
                // After the Ratz dies, this same route brings the ranged members
                // down from the balloon platform before Alishar appears.
                prepareStageNineWaitingAgent(
                        entry, agent, !triggerRatzAlive || rangedTriggerEngaged);
                continue;
            }
            if (!agent.getMap().getAllMonsters().isEmpty()) {
                member.assign(AgentLpqMemberState.Role.BOSS_ATTACKER,
                        AgentLpqDefinition.stage(9).mapId());
                Set<Integer> remainingMobIds = agent.getMap().getAllMonsters().stream()
                        .filter(Monster::isAlive)
                        .map(Monster::getId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                ACTIONS.grind(entry, remainingMobIds);
                continue;
            }
            ACTIONS.stop(entry);
        }
        if (session.companionAdopted()) {
            // The carrier brings the key to his feet; he talks to the violet balloon himself (no consolidation, no
            // key recovery, no submit).
            companionBossKey(session, leader, nowMs);
            return;
        }
        if (cleanupPhase && (!leader.getMap().getAllMonsters().isEmpty()
                || leader.getMap().getDroppedItems().stream()
                .anyMatch(drop -> drop != null && !drop.isPickedUp()))) {
            return;
        }
        consolidateBossKey(session, leader, nowMs);
        recoverBossKey(session, leader, nowMs);
        if (leader.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false) > 0) {
            if (isAgent(leader)) submit(session, leader, AgentLpqDefinition.stage(9), nowMs);
        }
    }

    /**
     * Damage to Alishar is stage-9 progress (LPQ base fix 3): once the Black Ratz is dead nothing else marks progress
     * while he lives, so a fight that is still dealing damage must not reach the phase timeout.
     */
    static void observeStageNineBossDamage(AgentLpqSession session, Monster alishar, long nowMs) {
        if (alishar != null && session.observeStageNineBossHp(alishar.getHp())) {
            session.markProgress(nowMs);
        }
    }

    /**
     * Stage-9 orders while Alishar lives (LPQ base fix 5 and 6), one living member per call, as
     * AgentLpqStageNineBossPolicy.order decides them. A member out of potions backs off out of his reach and rests
     * there, so a companion is no longer sent back to its owner at his feet; resting heals by regen, asks no one for
     * potions and keeps the member registered, and out of his reach it fights a summon that reaches it. A shooter
     * fights him from a station outside his reach: it walks without attacking to cross him or to get out of his
     * reach, and otherwise farms from its station, attacking what is in range and walking back to the station between
     * attacks; a summon that reaches it is fought first. Melee members take his summons first and Alishar when none is
     * up. Shooters are split into two groups at the first pass so that hits from both sides keep him between them; a
     * shooter that joins later is group A.
     */
    private static void fightAlishar(AgentLpqSession session, AgentLpqMemberState member, AgentRuntimeEntry entry,
                                     Character agent, Point boss, List<Integer> shooterIds, boolean groupsUnset,
                                     List<Point> summons) {
        AgentLpqStageNineBossPolicy.MemberState before = member.stageNineState();
        int potions = ACTIONS.hpPotionCount(agent);
        AgentLpqStageNineBossPolicy.Order order = AgentLpqStageNineBossPolicy.order(before,
                new AgentLpqStageNineBossPolicy.Pass(stageNineShooter(agent), shooterIds.indexOf(agent.getId()),
                        groupsUnset, agent.getPosition(), boss, summons, potions,
                        server.agents.runtime.AgentRuntimeConfig.cfg.POT_STOP, agent.getHp(), agent.getMaxHp()),
                stageNineFloor());
        member.applyStageNine(order.state());
        logStageNineOrder(session, member, agent, boss, potions, before, order);
        switch (order.move()) {
            case MELEE -> ACTIONS.grind(entry, order.targets().preferred(), order.targets().fallback());
            case WALK_TO_STATION, WALK_TO_REST -> ACTIONS.navigate(entry, order.point(), true);
            case FIGHT_FROM_STATION, FIGHT_AT_REST -> ACTIONS.grindFromAnchor(entry, order.point(),
                    order.targets().preferred(), order.targets().fallback());
            case REST -> ACTIONS.stop(entry);
        }
    }

    /** The 'LPQ stage9 rest:', 'LPQ stage9 group:' and 'LPQ stage9 side:' lines for one member's stage-9 order. */
    private static void logStageNineOrder(AgentLpqSession session, AgentLpqMemberState member, Character agent,
                                          Point boss, int potions, AgentLpqStageNineBossPolicy.MemberState before,
                                          AgentLpqStageNineBossPolicy.Order order) {
        AgentLpqStageNineBossPolicy.MemberState after = order.state();
        if (after.resting() != before.resting()) {
            log.info("LPQ stage9 rest: session={} member={}({}) state={} hp={} hpPotions={} restPoint={}",
                    session.sessionId(), agent.getName(), agent.getId(), after.resting() ? "START" : "END",
                    agent.getHp(), potions, after.resting() ? after.restPoint() : before.restPoint());
            if (after.resting()) {
                coordinate(session, agent, "rest-" + agent.getId(), AgentLpqCompanionPlay.BACKING_OFF);
            }
        }
        if (order.move() == AgentLpqStageNineBossPolicy.Move.MELEE) {
            if (member.claimStageNineGroupLine()) {
                log.info("LPQ stage9 group: session={} member={}({}) group=MELEE home=NONE",
                        session.sessionId(), agent.getName(), agent.getId());
            }
            return;
        }
        if (after.resting()) return;
        if (before.group() == null && member.claimStageNineGroupLine()) {
            log.info("LPQ stage9 group: session={} member={}({}) group={} home={}", session.sessionId(),
                    agent.getName(), agent.getId(), after.group(), after.homeSide());
        }
        if (after.side() != before.side()) {
            log.info("LPQ stage9 side: session={} member={}({}) from={} to={} boss={} station={}",
                    session.sessionId(), agent.getName(), agent.getId(),
                    before.side() == null ? "NONE" : before.side(), after.side(), boss, after.station());
        }
    }

    /** A magic attacker or a bow, crossbow, claw or gun user; a Lucky Seven rogue holding a dagger fights in melee. */
    private static boolean stageNineShooter(Character agent) {
        return AgentLpqStageNineBossPolicy.shooter(
                AgentLpqRosterRequirementPolicy.magicAttack(agent), ACTIONS.rangedWeapon(agent));
    }

    /** The living shooters on the boss map, by character id (their rank sets their group at the first pass). */
    private static List<Integer> stageNineShooterIds(AgentLpqSession session) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(agent -> agent.getMapId() == AgentLpqDefinition.stage(9).mapId() && agent.getHp() > 0)
                .filter(AgentLpqCoordinator::stageNineShooter)
                .map(Character::getId).sorted().toList();
    }

    /** The 922010900 boss floor (footholds 95-100) with the stage-9 edge margin. */
    static AgentLpqStageNineBossPolicy.Floor stageNineFloor() {
        return new AgentLpqStageNineBossPolicy.Floor(STAGE_9_FLOOR_LEFT_X, STAGE_9_FLOOR_RIGHT_X, STAGE_9_FLOOR_Y,
                AgentLpqStageNineBossPolicy.floorEdgeMarginPx());
    }

    private static void assignStageNineRoles(AgentLpqSession session) {
        for (AgentLpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (member.memberType() == AgentLpqMemberState.MemberType.HUMAN) {
                if (member.characterId() == session.preferredHumanId()
                        && session.humanRolePreference()
                        == AgentLpqSession.HumanRolePreference.RANGED
                        && AgentLpqRosterRequirementPolicy.rangedAttack(participant)) {
                    member.assign(AgentLpqMemberState.Role.RANGED_TRIGGER,
                            AgentLpqDefinition.stage(9).mapId());
                    String bossLine = voice(session, null,
                            "LPQ boss: stand on the balloon platform and attack Black Ratz from range.");
                    if (bossLine != null) participant.dropMessage(6, bossLine);
                }
                continue;
            }
            member.assign(AgentLpqRosterRequirementPolicy.rangedAttack(participant)
                    ? AgentLpqMemberState.Role.RANGED_TRIGGER
                    : AgentLpqMemberState.Role.BOSS_ATTACKER, AgentLpqDefinition.stage(9).mapId());
        }
    }

    static int stageNineTriggerPriority(Character character) {
        if (character == null || character.getJob() == null) return 4;
        if (character.getJob().isA(client.Job.BOWMAN)) return 0;
        if (character.getJob().isA(client.Job.ASSASSIN)) return 1;
        if (character.getJob().isA(client.Job.GUNSLINGER)) return 2;
        return AgentLpqRosterRequirementPolicy.rangedAttack(character) ? 3 : 4;
    }

    private static void engageBossTriggerRatz(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (!reachStageNineBalloonPlatform(session, member, entry, agent, nowMs)) return;
        ACTIONS.facePosition(agent, new Point(588, -3));
        Monster trigger = agent.getMap().getAllMonsters().stream()
                .filter(monster -> monster.getId() == AgentLpqDefinition.BOSS_TRIGGER_RATZ)
                .findFirst().orElse(null);
        if (trigger == null) return;
        int hpBefore = trigger.getHpAsInt();
        AgentAttackPlan attackPlan = AgentCombatPlanRuntime.planAttack(
                entry, agent, trigger, AgentCombatConfig.cfg);
        if (attackPlan == null) {
            attackPlan = authoredBossTriggerAttackPlan(entry, agent, trigger);
        }
        if (attackPlan == null) {
            ACTIONS.stop(entry);
            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
            if (member.claimIntentAnnouncement("stage9:ratz-no-ranged-plan")) {
                log.warn("LPQ Stage 9 Black Ratz has no legal ranged attack plan from "
                                + "the balloon platform: session={} member={}({})",
                        session.sessionId(), agent.getName(), agent.getId());
            }
            return;
        }
        AgentAttackTransactionResult result = AgentCombatAttackRuntime.attackMonster(
                entry, agent, attackPlan);
        if (result.committed()) {
            log.info("LPQ Stage 9 ranged Black Ratz attack: session={} member={}({}) "
                            + "agentPosition={} firingAnchor={} ratzPosition={} hpBefore={} hpAfter={} "
                            + "route={} skill={}",
                    session.sessionId(), agent.getName(), agent.getId(), agent.getPosition(),
                    stageNineRatzFiringAnchor(agent.getId()), trigger.getPosition(), hpBefore,
                    trigger.getHp(), attackPlan.route, attackPlan.skillId);
        }
        if (result.status() == AgentAttackTransactionResult.Status.REJECTED
                && member.claimIntentAnnouncement("stage9:ratz-rejected:" + result.reason())) {
            log.warn("LPQ Stage 9 Black Ratz attack rejected: session={} member={}({}) reason={}",
                    session.sessionId(), agent.getName(), agent.getId(), result.reason());
        }
        if (result.status() == AgentAttackTransactionResult.Status.REJECTED) {
            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
        }
        if (trigger.getHp() < hpBefore) session.markProgress(nowMs);
        member.observeRoomCombatTarget(
                AgentLpqDefinition.stage(9).mapId(),
                trigger.getObjectId(), trigger.getHpAsInt(), nowMs);
    }

    /**
     * LPQ's Black Ratz is an authored trigger target 187 px above the continuous firing
     * floor. The v83 client permits the shot from that platform, but the general combat
     * planner's conservative +/-50 px projectile band correctly rejects it for ordinary
     * grinding. Preserve the normal weapon animation, ammo, cooldown, damage formula and
     * authoritative attack transaction while extending only this LPQ objective hit box.
     */
    private static AgentAttackPlan authoredBossTriggerAttackPlan(
            AgentRuntimeEntry entry, Character agent, Monster trigger) {
        if (agent == null || trigger == null || agent.getPosition() == null
                || trigger.getPosition() == null) return null;
        if (!agent.hasDisease(Disease.SEAL)) {
            Rectangle objectivePoint = new Rectangle(
                    trigger.getPosition().x, trigger.getPosition().y, 1, 1);
            List<Integer> skillIds = bossTriggerMagicSkillIds(entry, agent);
            for (int skillId : skillIds) {
                AgentAttackPlan skillPlan = AgentSkillAttackPlanRuntime
                        .planSkillAttackForAuthoredObjective(
                                agent, trigger, skillId, AgentCombatConfig.cfg,
                                objectivePoint);
                if (skillPlan != null && skillPlan.route == AgentAttackRoute.MAGIC) {
                    return new AgentAttackPlan(
                            skillPlan.skillId, skillPlan.skillLevel, skillPlan.numDamage,
                            skillPlan.hitBox, List.of(trigger), skillPlan.route,
                            skillPlan.display, skillPlan.direction,
                            skillPlan.rangedDirection, skillPlan.stance, skillPlan.speed,
                            skillPlan.hitDelayMs, skillPlan.cooldownMs,
                            skillPlan.damageWeaponType);
                }
            }
        }
        AgentAttackExecutionProvider.BasicAttackData attack =
                AgentAttackExecutionProvider.buildBasicAttackData(agent, trigger.getPosition());
        if (attack == null || attack.route() != AgentAttackRoute.RANGED) return null;
        Rectangle hitBox = authoredBossTriggerHitBox(attack.hitBox(), trigger.getPosition());
        return new AgentAttackPlan(0, 0,
                AgentCombatHitCounter.packetSafeHitCount(agent, attack.route(), 1),
                hitBox, List.of(trigger), attack.route(), attack.display(), attack.direction(),
                attack.rangedDirection(), attack.stance(), attack.speed(), attack.hitDelayMs(),
                attack.cooldownMs(), AgentCombatWeaponPolicy.damageWeaponTypeForAction(
                0, AgentAttackExecutionProvider.getEquippedWeaponType(agent), attack.action()));
    }

    static List<Integer> bossTriggerMagicSkillIds(
            AgentRuntimeEntry entry, Character agent) {
        if (entry == null || agent == null) return List.of();
        LinkedHashSet<Integer> skillIds = new LinkedHashSet<>(
                AgentCombatSkillClassifier.cachedAttackSkillIds(
                    AgentCombatSkillCacheStateRuntime.attackSkillIds(entry),
                    AgentCombatSkillCacheStateRuntime.attackSkillId(entry),
                    AgentCombatSkillCacheStateRuntime.aoeSkillId(entry)));
        for (Map.Entry<Skill, Character.SkillEntry> learned : agent.getSkills().entrySet()) {
            Skill skill = learned.getKey();
            Character.SkillEntry skillEntry = learned.getValue();
            int level = skillEntry == null ? 0 : Byte.toUnsignedInt(skillEntry.skillevel);
            if (skill == null || level <= 0 || level > skill.getMaxLevel()) continue;
            if (AgentCombatSkillClassifier.isActiveAttackSkill(skill, skill.getEffect(level))
                    && AgentAttackExecutionProvider.determineSkillRoute(agent, skill.getId())
                    == AgentAttackRoute.MAGIC) {
                skillIds.add(skill.getId());
            }
        }
        return List.copyOf(skillIds);
    }

    static Rectangle authoredBossTriggerHitBox(Rectangle ordinaryHitBox, Point triggerPosition) {
        if (triggerPosition == null) throw new IllegalArgumentException("trigger position is required");
        Rectangle triggerPoint = new Rectangle(triggerPosition.x, triggerPosition.y, 1, 1);
        return ordinaryHitBox == null ? triggerPoint : ordinaryHitBox.union(triggerPoint);
    }

    private static boolean prepareStageNineBossFormation(
            AgentLpqSession session, Monster boss, long nowMs) {
        Point bossPosition = boss == null ? null : boss.getPosition();
        boolean edge = stageNineBossAtEdge(bossPosition);
        long elapsedMs = session.observeBossEdgeRegroup(edge, nowMs);
        if (!edge) return true;
        boolean recoveryDue = stageNineBossRegroupRecoveryDue(elapsedMs);
        boolean ready = true;
        int recovered = 0;
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null
                    || agent.getMapId() != AgentLpqDefinition.stage(9).mapId()) {
                ready = false;
                continue;
            }
            Point desired = stageNineOpenSpaceAnchor(bossPosition, agent.getId());
            Point grounded = ACTIONS.groundPoint(agent.getMap(), desired);
            Point target = grounded == null ? desired : grounded;
            if (near(agent.getPosition(), target, STAGE_9_FORMATION_RADIUS_PX)) continue;
            ready = false;
            if (recoveryDue) {
                ACTIONS.stop(entry);
                rescueTeleport(session, "stage9-formation", entry, agent, target);
                recovered++;
            } else {
                ACTIONS.navigate(entry, target, true);
            }
        }
        if (recovered > 0) {
            session.markProgress(nowMs);
            log.warn("LPQ Stage 9 edge formation recovery moved {} members into open "
                            + "space after {}ms: session={} bossPosition={}",
                    recovered, elapsedMs, session.sessionId(), bossPosition);
            return false;
        }
        return ready;
    }

    static boolean stageNineBossAtEdge(Point bossPosition) {
        return bossPosition != null
                && (bossPosition.x <= STAGE_9_FLOOR_LEFT_X + STAGE_9_BOSS_EDGE_MARGIN_PX
                || bossPosition.x >= STAGE_9_FLOOR_RIGHT_X - STAGE_9_BOSS_EDGE_MARGIN_PX);
    }

    static Point stageNineOpenSpaceAnchor(Point bossPosition, int characterId) {
        if (bossPosition == null) throw new IllegalArgumentException("boss position is required");
        int baseX = bossPosition.x <= (STAGE_9_FLOOR_LEFT_X + STAGE_9_FLOOR_RIGHT_X) / 2
                ? 520 : 380;
        int offset = Math.floorMod(characterId * 37, 121) - 60;
        return new Point(baseX + offset, STAGE_9_FLOOR_Y);
    }

    static boolean stageNineBossRegroupRecoveryDue(long elapsedMs) {
        return elapsedMs >= recovery(9).rallyRecoveryMs();
    }

    private static boolean reachStageNineBalloonPlatform(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, long nowMs) {
        Point firingAnchor = stageNineRatzFiringAnchor(agent.getId());
        if (stageNineRatzFiringReady(
                agent.getPosition(), ACTIONS.grounded(agent), firingAnchor)) {
            member.clearTraversalProgress();
            return true;
        }
        approachWithRecovery(session, member, entry, agent,
                AgentLpqDefinition.stage(9).mapId(),
                firingAnchor, STAGE_9_RATZ_FIRING_ANCHOR_RADIUS,
                "Stage 9 balloon firing platform", nowMs);
        return false;
    }

    static Point stageNineRatzFiringAnchor(int characterId) {
        return new Point(STAGE_9_BALLOON_PLATFORM_ANCHOR);
    }

    /**
     * Melee members do not participate in the ranged Black Ratz trigger. Let them
     * settle below their current entry position instead of regrouping at a fixed
     * left-side slot before Alishar appears.
     */
    private static void prepareStageNineWaitingAgent(
            AgentRuntimeEntry entry, Character agent, boolean advance) {
        Point current = agent.getPosition();
        if (current == null) {
            ACTIONS.stop(entry);
            return;
        }
        if (advance) {
            Point desired = stageNinePreBossRallyAnchor(agent.getId());
            Point grounded = ACTIONS.groundPoint(agent.getMap(), desired);
            Point target = grounded == null ? desired : grounded;
            if (near(current, target, STAGE_9_FORMATION_RADIUS_PX)
                    && ACTIONS.grounded(agent)) {
                ACTIONS.stop(entry);
            } else {
                ACTIONS.navigate(entry, target, true);
            }
            return;
        }
        if (ACTIONS.grounded(agent)) {
            ACTIONS.stop(entry);
            return;
        }
        Point desired = new Point(
                Math.clamp(current.x, STAGE_9_FLOOR_LEFT_X, STAGE_9_FLOOR_RIGHT_X),
                STAGE_9_FLOOR_Y);
        Point grounded = ACTIONS.groundPoint(agent.getMap(), desired);
        Point target = grounded == null ? desired : grounded;
        ACTIONS.navigate(entry, target, true);
    }

    static Point stageNinePreBossRallyAnchor(int characterId) {
        int slot = Math.floorMod(characterId, 6);
        return new Point(
                STAGE_9_PRE_BOSS_RALLY_X + slot * STAGE_9_PRE_BOSS_RALLY_SPACING_PX,
                STAGE_9_FLOOR_Y);
    }

    static boolean stageNineRatzFiringReady(
            Point position, boolean grounded, Point firingAnchor) {
        return grounded && near(position, firingAnchor, STAGE_9_RATZ_FIRING_ANCHOR_RADIUS);
    }

    private static void recoverBossKey(AgentLpqSession session, Character leader, long nowMs) {
        int partyKeys = partyItemCount(session, AgentLpqDefinition.BOSS_KEY);
        boolean bossMapCleared = activeReactors(leader).isEmpty()
                && leader.getMap().getAllMonsters().isEmpty();
        // Distinguish "no key while fighting" from "no key after clear" so
        // the missing-key grace starts at clear rather than at stage entry.
        int recoverySignature = partyKeys + (bossMapCleared ? 1_000_000 : 0);
        if (leader.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false) > 0
                || session.observePassRecovery(recoverySignature, nowMs)
                < recovery(9).missingPassMs()) return;

        int vacuumed = vacuumItemDrops(
                session, "boss-key", leader, Set.of(leader.getMap()), AgentLpqDefinition.BOSS_KEY);
        if (vacuumed > 0) {
            session.markPassRecoveryConsolidation(nowMs);
            log.warn("LPQ boss-key recovery vacuumed {} unreachable key drops: session={} leader={}({})",
                    vacuumed, session.sessionId(), leader.getName(), leader.getId());
            return;
        }
        boolean repositionedHolder = false;
        for (AgentLpqMemberState member : session.members()) {
            if (member.characterId() == leader.getId()) continue;
            Character holder = character(member.characterId());
            if (holder == null || holder.getMap() != leader.getMap()
                    || holder.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false) <= 0) continue;
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) {
                if (nowMs >= member.nextActionAtMs()) {
                    String keyLine = voice(session, null, "Drop Alishar's key beside the party leader.");
                    if (keyLine != null) holder.dropMessage(6, keyLine);
                    member.deferUntil(nowMs + 5_000L);
                }
                continue;
            }
            AgentRuntimeEntry holderEntry = entry(member.characterId());
            if (holderEntry == null) continue;
            ACTIONS.stop(holderEntry);
            rescueTeleport(session, "boss-key-holder", holderEntry, holder, leader.getPosition());
            repositionedHolder = true;
        }
        if (repositionedHolder) {
            consolidateBossKey(session, leader, nowMs);
            session.markPassRecoveryConsolidation(nowMs);
            log.warn("LPQ boss-key holder regroup recovery: session={} leader={}({})",
                    session.sessionId(), leader.getName(), leader.getId());
            return;
        }
        if (partyKeys > 0 || !bossMapCleared
                || session.passRecoveryPassesAwarded()) return;
        if (!grantItem(session, AgentLpqRescue.Kind.KEY_AWARD, "boss-key-award",
                leader, AgentLpqDefinition.BOSS_KEY, (short) 1)) {
            AgentLpqTerminationService.fail(
                    session, "Alishar's key disappeared and the leader has no ETC space", nowMs);
            return;
        }
        session.markPassRecoveryPassesAwarded(nowMs);
        log.warn("LPQ boss-key recovery awarded one missing key after the cleared boss map: "
                        + "session={} leader={}({})", session.sessionId(),
                leader.getName(), leader.getId());
    }

    private static void consolidateBossKey(AgentLpqSession session, Character leader, long nowMs) {
        if (isAgent(leader)) {
            AgentRuntimeEntry leaderEntry = entry(leader.getId());
            if (leaderEntry != null) collectNearestDrop(leaderEntry, leader,
                    Set.of(AgentLpqDefinition.BOSS_KEY), new LinkedHashSet<>());
        }
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT || member.characterId() == leader.getId()) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null || agent.getMapId() != leader.getMapId()
                    || agent.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false) <= 0) continue;
            if (!near(agent.getPosition(), leader.getPosition(), INTERACTION_RADIUS)) {
                ACTIONS.navigate(entry, leader.getPosition(), true); continue;
            }
            AgentScriptItemActionService.dropItem(entry, InventoryType.ETC, AgentLpqDefinition.BOSS_KEY, (short) 1);
            session.markProgress(nowMs);
        }
    }

    static boolean stageNineAgentMayCollectItem(
            int agentId, int eventLeaderId, int itemId) {
        return stageNineAgentMayCollectItem(agentId, eventLeaderId, 0, itemId);
    }

    /** The key is the event leader's, and a companion run's key carrier's ({@code carrierId}, 0 for none). */
    static boolean stageNineAgentMayCollectItem(int agentId, int eventLeaderId, int carrierId, int itemId) {
        return itemId != AgentLpqDefinition.BOSS_KEY || agentId == eventLeaderId
                || (carrierId != 0 && agentId == carrierId);
    }

    private static void bonus(AgentLpqSession session, long nowMs) {
        List<Character> bonusAgents = new ArrayList<>();
        boolean participantRemaining = false;
        Set<Integer> reactorClaims = activeReactorClaims(session, AgentLpqDefinition.CLEAR_MAP);
        Set<Integer> lootClaims = new LinkedHashSet<>();
        for (AgentLpqMemberState member : session.members()) {
            Character agent = character(member.characterId());
            if (agent == null || agent.getMapId() != AgentLpqDefinition.CLEAR_MAP) continue;
            participantRemaining = true;
            if (member.memberType() == AgentLpqMemberState.MemberType.HUMAN) {
                continue;
            }
            AgentRuntimeEntry entry = entry(member.characterId());
            if (entry == null) continue;
            bonusAgents.add(agent);
            if (session.bonusMode() == AgentLpqSession.BonusMode.SKIP) continue;

            if (collectNearestDrop(entry, agent, null, lootClaims)) continue;
            if (hitNearestReactorWithoutRecovery(
                    entry, agent, nowMs, member, reactorClaims)) {
                session.markProgress(nowMs);
            }
            if (member.reactorTargetObjectId() != 0) reactorClaims.add(member.reactorTargetObjectId());
        }
        if (!participantRemaining) {
            session.transition(AgentLpqSession.Phase.CLAIMING_REWARD, nowMs);
            return;
        }

        if (bonusAgents.isEmpty()) return;

        boolean drained = session.bonusMode() == AgentLpqSession.BonusMode.SKIP
                || bonusMapDrained(bonusAgents.getFirst());
        if (session.bonusMode() != AgentLpqSession.BonusMode.SKIP
                && session.observeBonusDrained(drained, nowMs) < BONUS_DRAIN_SETTLE_MS) return;
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null
                    || agent.getMapId() != AgentLpqDefinition.CLEAR_MAP) continue;
            if (approachRewardNpc(session, member, entry, agent,
                    AgentLpqDefinition.CLEAR_NPC, "bonus exit balloon", nowMs)
                    && LPQ.runNpc(agent, AgentLpqDefinition.CLEAR_NPC, 1)) {
                session.markProgress(nowMs);
            }
        }
    }

    private static void claim(AgentLpqSession session, long nowMs) {
        session.freezeRewardEligibility();
        boolean nonLeaderRewardsResolved = session.members().stream()
                .filter(member -> member.characterId() != session.eventLeaderId())
                .allMatch(AgentLpqMemberState::rewardResolved);
        for (AgentLpqMemberState member : session.members()) {
            if (member.rewardResolved()) continue;
            Character participant = character(member.characterId());
            if (participant == null) {
                session.forfeitReward(member.characterId());
                continue;
            }
            if (participant.getMapId() == AgentLpqDefinition.CLEAR_MAP) {
                if (member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
                    AgentRuntimeEntry participantEntry = entry(member.characterId());
                    if (participantEntry != null && approachRewardNpc(
                            session, member, participantEntry, participant,
                            AgentLpqDefinition.CLEAR_NPC, "bonus exit balloon", nowMs)
                            && LPQ.runNpc(participant, AgentLpqDefinition.CLEAR_NPC, 1)) {
                        session.markProgress(nowMs);
                    }
                }
                continue;
            }
            if (participant.getMapId() != AgentLpqDefinition.BONUS_MAP) continue;
            if (member.memberType() == AgentLpqMemberState.MemberType.HUMAN) {
                continue;
            }
            if (member.characterId() == session.eventLeaderId() && !nonLeaderRewardsResolved) continue;
            AgentRuntimeEntry participantEntry = entry(member.characterId());
            if (participantEntry != null && approachRewardNpc(
                    session, member, participantEntry, participant,
                    AgentLpqDefinition.REWARD_NPC, "Arturo reward", nowMs)
                    && LPQ.runNpc(participant, AgentLpqDefinition.REWARD_NPC, 1)) {
                session.markProgress(nowMs);
            }
        }
        if (session.allRewardsResolved()) session.transition(AgentLpqSession.Phase.EXITING, nowMs);
    }

    static boolean approachRewardNpc(
            AgentLpqSession session, AgentLpqMemberState member, AgentRuntimeEntry entry,
            Character agent, int npcId, String context, long nowMs) {
        Point npc = ACTIONS.npcPosition(agent, npcId);
        if (npc == null) return false;
        Point grounded = ACTIONS.groundPoint(agent.getMap(), npc);
        Point target = grounded == null ? npc : grounded;
        if (near(agent.getPosition(), target, INTERACTION_RADIUS) && ACTIONS.grounded(agent)) {
            ACTIONS.stop(entry);
            member.clearDestinationApproachProgress();
            return true;
        }
        long distanceSq = agent.getPosition() == null
                ? Long.MAX_VALUE : (long) agent.getPosition().distanceSq(target);
        long inactiveForMs = member.observeDestinationApproachProgress(
                9, agent.getMapId(), agent.getPosition(), distanceSq, nowMs);
        if (inactiveForMs >= recovery(9).rallyRecoveryMs()) {
            ACTIONS.stop(entry);
            if (!rescueTeleport(session, "reward-npc-approach", entry, agent, target)) {
                ACTIONS.refreshNavigation(entry, agent);
                member.clearDestinationApproachProgress();
                ACTIONS.navigate(entry, target, true);
                return false;
            }
            member.clearDestinationApproachProgress();
            session.markProgress(nowMs);
            log.warn("LPQ {} approach recovery: session={} member={}({}) map={} "
                            + "target={} inactiveForMs={}", context, session.sessionId(),
                    agent.getName(), agent.getId(), agent.getMapId(), target, inactiveForMs);
            return false;
        }
        if (member.claimDestinationApproachRetry(
                inactiveForMs, nowMs, recovery(9).rallyRetryMs())) {
            ACTIONS.refreshNavigation(entry, agent);
        }
        ACTIONS.navigate(entry, target, true);
        return false;
    }

    private static void exitAfterRewardWarpSettles(AgentLpqSession session, long nowMs) {
        boolean agentStillInEventMap = session.members().stream()
                .filter(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .anyMatch(agent -> AgentLpqDefinition.isEventMap(agent.getMapId()));
        if (agentStillInEventMap || nowMs - session.phaseEnteredAtMs() < 2_000L) return;
        AgentLpqTerminationService.complete(session, nowMs);
    }

    static Set<Integer> bonusDropIds(Collection<MapItem> drops) {
        if (drops == null || drops.isEmpty()) return Set.of();
        return drops.stream().filter(java.util.Objects::nonNull)
                .filter(drop -> !drop.isPickedUp())
                .map(MapItem::getItemId).filter(id -> id > 0)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean bonusMapDrained(Character agent) {
        return activeReactors(agent).isEmpty()
                && agent.getMap().getDroppedItems().stream().noneMatch(drop -> !drop.isPickedUp());
    }

    private static boolean hitNearestReactor(AgentRuntimeEntry entry, Character agent,
                                             long nowMs, AgentLpqMemberState member) {
        return hitNearestReactor(entry, agent, nowMs, member, false);
    }

    private static boolean hitNearestReactor(AgentRuntimeEntry entry, Character agent,
                                             long nowMs, AgentLpqMemberState member,
                                             boolean waitForSpawnCleanup) {
        return hitNearestReactor(entry, agent, nowMs, member, waitForSpawnCleanup, Set.of());
    }

    private static boolean hitNearestReactor(AgentRuntimeEntry entry, Character agent,
                                             long nowMs, AgentLpqMemberState member,
                                             boolean waitForSpawnCleanup,
                                             Set<Integer> claimedByParty) {
        return hitReactorFromCandidates(entry, agent, nowMs, member, waitForSpawnCleanup,
                claimedByParty, activeReactors(agent), true);
    }

    private static boolean hitNearestReactorWithoutRecovery(
            AgentRuntimeEntry entry, Character agent, long nowMs,
            AgentLpqMemberState member, Set<Integer> claimedByParty) {
        return hitReactorFromCandidates(entry, agent, nowMs, member, false,
                claimedByParty, activeReactors(agent), false);
    }

    private static boolean hitStageTwoTrapReactor(AgentRuntimeEntry entry, Character agent,
                                                  long nowMs, AgentLpqMemberState member) {
        List<Reactor> trapReactors = stageTwoTrapReactors(activeReactors(agent));
        return hitReactorFromCandidates(
                entry, agent, nowMs, member, false, Set.of(), trapReactors, true);
    }

    static boolean hitReactorFromCandidates(AgentRuntimeEntry entry, Character agent,
                                                     long nowMs, AgentLpqMemberState member,
                                                     boolean waitForSpawnCleanup,
                                                     Set<Integer> claimedByParty,
                                                     List<Reactor> candidates,
                                                     boolean allowRemoteRecovery) {
        boolean stageFiveRoom = AgentLpqDefinition.roomMaps(5).contains(agent.getMapId());
        // A reactor hit deliberately defers the next strike. Report the objective as
        // active throughout that delay so the ordinary stage scheduler cannot issue
        // grind/rally movement and pull the member away from the committed box.
        if (nowMs < member.nextActionAtMs()) return true;
        int committedBeforeSelection = member.reactorTargetObjectId();
        Reactor reactor = stageFiveRoom
                ? selectStageFiveReactor(entry, agent, nowMs, member, candidates, claimedByParty)
                : selectCommittedReactor(member, agent.getMapId(), agent.getPosition(),
                candidates, waitForSpawnCleanup, claimedByParty);
        if (reactor == null) {
            int stage = AgentLpqDefinition.stageNumber(agent.getMapId());
            if ((stage == 2 || stage == 3) && committedBeforeSelection != 0) {
                // The reactor disappeared after the final hit. Stay at its platform
                // long enough for the authored pass drop to materialize; the normal
                // loot pass at the start of the next tick remains higher priority.
                ACTIONS.stop(entry);
                member.deferUntil(nowMs + STAGE_2_PASS_LOOT_SETTLE_MS);
                return true;
            }
            return false;
        }
        if (stageFiveOrdinaryRoom(agent.getMapId()) && !inCompanionRun(agent)
                && applyStageFiveUnobservedBoxAssist(
                member, entry, agent, reactor, nowMs)) {
            return true;
        }
        if (member.reactorTargetHitOnce()) {
            // Once a box has been reached and struck, hold that authored interaction
            // instead of letting the movement planner climb away and rediscover it
            // between every reactor state hit.
            if (!reactorInteractionReady(agent, reactor)) {
                ACTIONS.navigate(entry,
                        reactorApproachTarget(agent, reactor), true);
                return true;
            }
            ACTIONS.stop(entry);
            if (ACTIONS.hitReactor(agent, reactor.getObjectId())) {
                member.deferUntil(nowMs + INTERACTION_RETRY_MS);
            }
            return true;
        }
        if (!reactorInteractionReady(agent, reactor)) {
            boolean darkSightFinalBox = AgentLpqStageFiveReactorOrder.isDarkSightFinalBox(
                    agent.getMapId(), reactor.getPosition());
            boolean darkSightActive = agent.getBuffedValue(BuffStat.DARKSIGHT) != null;
            boolean refreshBeforeFinalAscent = darkSightFinalBox
                    && ACTIONS.grounded(agent) && darkSightRefreshDue(agent, nowMs);
            if (agent.getMapId() == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM
                    && (!darkSightActive || refreshBeforeFinalAscent)
                    && ACTIONS.grounded(agent)
                    && !AgentMovementStateRuntime.inAir(entry)
                    && !AgentMovementStateRuntime.climbing(entry)) {
                ACTIONS.stop(entry);
                if (refreshDarkSight(entry, agent)) {
                    member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                }
                return true;
            }
            int stage = AgentLpqDefinition.stageNumber(agent.getMapId());
            Point authoredApproach = AgentLpqStageFiveReactorOrder.authoredApproachWaypoint(
                    agent.getMapId(), agent.getPosition(), reactor.getPosition());
            Point approachTarget = authoredApproach == null
                    ? reactorApproachTarget(agent, reactor) : authoredApproach;
            if (stageFiveRoom && member.reactorTargetObjectId() == 0) {
                long distanceSq = agent.getPosition() == null || approachTarget == null
                        ? Long.MAX_VALUE : (long) agent.getPosition().distanceSq(approachTarget);
                long stalledForMs = member.observeTraversalProgress(
                        agent.getMapId(), reactor.getObjectId(), agent.getPosition(),
                        distanceSq, nowMs);
                if (allowRemoteRecovery && stalledForMs >= recovery(5).reactorMs()) {
                    ACTIONS.refreshNavigation(entry, agent);
                    member.clearTraversalProgress();
                    member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                    log.warn("LPQ Stage 5 provisional box route was refreshed after no "
                                    + "navigation progress: member={}({}) map={} reactor={} stalledForMs={}",
                            agent.getName(), agent.getId(), agent.getMapId(),
                            reactor.getObjectId(), stalledForMs);
                    return true;
                }
            }
            if (allowRemoteRecovery && !stageFiveRoom && stage >= 1 && stage <= 9
                    && member.reactorTargetCommittedAtMs() > 0L
                    && nowMs - member.reactorTargetCommittedAtMs()
                    >= recovery(stage).reactorMs()) {
                ACTIONS.stop(entry);
                Point grounded = ACTIONS.groundPoint(agent.getMap(), reactor.getPosition());
                Point recoveryTarget = grounded == null ? reactor.getPosition() : grounded;
                if (!rescueTeleport(null, "reactor-approach", entry, agent, recoveryTarget)) {
                    // Withheld (a companion run): re-plan, re-commit the box and walk on.
                    ACTIONS.refreshNavigation(entry, agent);
                    member.clearReactorWork();
                    ACTIONS.navigate(entry, approachTarget, true);
                    return true;
                }
                member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                log.warn("LPQ reactor approach recovery placed member beside committed reactor: "
                                + "member={}({}) map={} reactor={} committedForMs={}",
                        agent.getName(), agent.getId(), agent.getMapId(), reactor.getObjectId(),
                        nowMs - member.reactorTargetCommittedAtMs());
                return true;
            }
            ACTIONS.navigate(entry, approachTarget, true);
            return true;
        }
        if (agent.getMapId() == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM
                && darkSightRoomAction(
                agent.getBuffedValue(BuffStat.DARKSIGHT) != null,
                true, false) == DarkSightRoomAction.CANCEL_FOR_ATTACK) {
            ACTIONS.stop(entry);
            cancelDarkSight(agent);
            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
            return true;
        }
        ACTIONS.stop(entry);
        if (ACTIONS.hitReactor(agent, reactor.getObjectId())) {
            if (stageFiveRoom && member.reactorTargetObjectId() == 0) {
                member.commitReactorTarget(agent.getMapId(), reactor.getObjectId(), nowMs);
                member.clearTraversalProgress();
            }
            member.markReactorTargetHit();
            member.deferUntil(nowMs + INTERACTION_RETRY_MS);
            return true;
        }
        return false;
    }

    private static Point reactorApproachTarget(Character agent, Reactor reactor) {
        Point grounded = ACTIONS.groundPoint(agent.getMap(), reactor.getPosition());
        return grounded == null ? reactor.getPosition() : grounded;
    }

    private static boolean stageFiveOrdinaryRoom(int mapId) {
        return mapId >= 922_010_502 && mapId <= 922_010_505;
    }

    private static boolean applyStageFiveUnobservedBoxAssist(
            AgentLpqMemberState member, AgentRuntimeEntry entry,
            Character agent, Reactor reactor, long nowMs) {
        int roomPasses = member.roomPassesCollectedFor(agent.getMapId(),
                agent.getItemQuantity(AgentLpqDefinition.PASS, false));
        long unobservedMs = member.observeStageFiveAssist(
                agent.getMapId(), roomPasses, ACTIONS.observedByPlayer(agent), nowMs);
        if (member.stageFiveAssistApplied()
                || unobservedMs < STAGE_5_UNOBSERVED_ASSIST_MS) return false;
        Point target = reactorApproachTarget(agent, reactor);
        if (target == null) return false;
        ACTIONS.stop(entry);
        rescueTeleport(null, "stage5-unobserved-box", entry, agent, target);
        member.markStageFiveAssistApplied();
        member.clearTraversalProgress();
        log.info("LPQ Stage 5 unobserved ordinary-room box assist: member={}({}) "
                        + "room={} reactor={} target={} unobservedMs={} passes={}/{}",
                agent.getName(), agent.getId(), agent.getMapId(), reactor.getObjectId(),
                target, unobservedMs, roomPasses,
                AgentLpqDefinition.roomPassQuota(agent.getMapId()));
        return true;
    }

    private static boolean applyStageFiveUnobservedExitAssist(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, int destinationMapId, long nowMs) {
        int roomMapId = agent.getMapId();
        int roomPasses = member.roomPassesCollectedFor(roomMapId,
                agent.getItemQuantity(AgentLpqDefinition.PASS, false));
        long unobservedMs = member.observeStageFiveAssist(
                roomMapId, roomPasses, ACTIONS.observedByPlayer(agent), nowMs);
        if (member.stageFiveAssistApplied()
                || unobservedMs < STAGE_5_UNOBSERVED_ASSIST_MS) return false;
        Integer portalId = portalIdTo(agent, destinationMapId);
        Point portal = portalId == null ? null : ACTIONS.portalPosition(agent, portalId);
        if (portal == null) return false;
        ACTIONS.stop(entry);
        rescueTeleport(session, "stage5-unobserved-exit", entry, agent, portal);
        member.markStageFiveAssistApplied();
        member.clearTraversalProgress();
        session.markProgress(nowMs);
        log.info("LPQ Stage 5 unobserved ordinary-room exit assist: session={} "
                        + "member={}({}) room={} portal={} target={} unobservedMs={}",
                session.sessionId(), agent.getName(), agent.getId(), roomMapId,
                portalId, portal, unobservedMs);
        return true;
    }

    private static Reactor selectStageFiveReactor(
            AgentRuntimeEntry entry, Character agent, long nowMs,
            AgentLpqMemberState member, List<Reactor> active,
            Set<Integer> claimedByParty) {
        int mapId = agent.getMapId();
        int currentPassCount = agent.getItemQuantity(AgentLpqDefinition.PASS, false);
        if (member.reactorTargetObjectId() != 0) {
            if (member.reactorTargetMapId() == mapId) {
                Reactor committed = active.stream()
                        .filter(candidate -> candidate.getObjectId()
                                == member.reactorTargetObjectId())
                        .findFirst().orElse(null);
                if (committed != null) return committed;
            }
            int destroyedReactorObjectId = member.reactorTargetObjectId();
            member.markReactorTargetBroken(false);
            member.clearTraversalProgress();
            member.deferUntil(nowMs + STAGE_5_PASS_LOOT_SETTLE_MS);
            log.info("LPQ Stage 5 box destroyed; waiting for its pass before selecting another: "
                            + "member={}({}) map={} reactor={} passes={}",
                    agent.getName(), agent.getId(), mapId, destroyedReactorObjectId,
                    currentPassCount);
            return null;
        }
        if (member.reactorSpawnCleanupPending()) return null;
        if (member.reactorApproachObjectId() != 0) {
            if (member.reactorApproachMapId() == mapId) {
                Reactor provisional = active.stream()
                        .filter(candidate -> candidate.getObjectId()
                                == member.reactorApproachObjectId())
                        .findFirst().orElse(null);
                if (provisional != null) return provisional;
            }
            member.clearReactorApproach();
            member.clearTraversalProgress();
        }
        Set<Integer> excluded = claimedByParty == null ? Set.of() : claimedByParty;
        List<Reactor> available = active.stream()
                .filter(candidate -> !excluded.contains(candidate.getObjectId()))
                .toList();
        Reactor selected = AgentLpqStageFiveReactorOrder.select(mapId, available);
        if (selected == null) return null;
        member.beginReactorApproach(mapId, selected.getObjectId());
        log.info("LPQ Stage 5 box route selected: member={}({}) map={} reactor={} "
                        + "agentPosition={} reactorPosition={} platformTarget={}",
                agent.getName(), agent.getId(), mapId, selected.getObjectId(),
                agent.getPosition(), selected.getPosition(),
                reactorApproachTarget(agent, selected));
        return selected;
    }

    private static boolean reactorInteractionReady(Character agent, Reactor reactor) {
        return agent != null && reactor != null
                && reactorInteractionReady(agent.getPosition(), ACTIONS.grounded(agent),
                reactor.getPosition());
    }

    static boolean reactorInteractionReady(
            Point agentPosition, boolean grounded, Point reactorPosition) {
        return grounded && agentPosition != null && reactorPosition != null
                && Math.abs(agentPosition.x - reactorPosition.x) <= INTERACTION_RADIUS
                && Math.abs(agentPosition.y - reactorPosition.y)
                <= REACTOR_PLATFORM_VERTICAL_TOLERANCE_PX;
    }

    static List<Reactor> stageTwoTrapReactors(List<Reactor> active) {
        if (active == null || active.isEmpty()) return List.of();
        return active.stream()
                .filter(java.util.Objects::nonNull)
                .filter(reactor -> reactor.getId() == AgentLpqDefinition.STAGE_2_TRAP_REACTOR)
                .toList();
    }

    static Reactor selectCommittedReactor(AgentLpqMemberState member, int mapId,
                                           Point position, List<Reactor> active,
                                           boolean waitForSpawnCleanup) {
        return selectCommittedReactor(
                member, mapId, position, active, waitForSpawnCleanup, Set.of());
    }

    static Reactor selectCommittedReactor(AgentLpqMemberState member, int mapId,
                                           Point position, List<Reactor> active,
                                           boolean waitForSpawnCleanup,
                                           Set<Integer> claimedByParty) {
        if (member == null || mapId <= 0 || position == null || active == null) return null;
        if (member.reactorTargetObjectId() != 0) {
            if (member.reactorTargetMapId() == mapId) {
                Reactor committed = active.stream()
                        .filter(candidate -> candidate.getObjectId() == member.reactorTargetObjectId())
                        .findFirst().orElse(null);
                if (committed != null) return committed;
            }
            member.markReactorTargetBroken(waitForSpawnCleanup);
            return null;
        }
        if (member.reactorSpawnCleanupPending()) return null;
        Set<Integer> excluded = claimedByParty == null ? Set.of() : claimedByParty;
        List<Reactor> available = active.stream()
                .filter(candidate -> !excluded.contains(candidate.getObjectId()))
                .toList();
        Reactor selected = AgentLpqStageFiveReactorOrder.select(mapId, available);
        if (selected == null) {
            selected = available.stream()
                    .min(Comparator.comparingDouble(candidate -> candidate.getPosition().distance(position)))
                    .orElse(null);
        }
        if (selected != null) member.commitReactorTarget(mapId, selected.getObjectId());
        return selected;
    }

    static Set<Integer> stageCombatTargets(int stage, Set<Integer> configuredTargets) {
        Set<Integer> targets = new LinkedHashSet<>(
                configuredTargets == null ? Set.of() : configuredTargets);
        if (stage == 7) targets.add(AgentLpqDefinition.ROMBARD);
        if (stage == 9) {
            targets.add(AgentLpqDefinition.BOSS_TRIGGER_RATZ);
            targets.add(AgentLpqDefinition.ALISHAR);
        }
        return Set.copyOf(targets);
    }

    private static List<Reactor> activeReactors(Character agent) {
        List<Reactor> reactors = new ArrayList<>();
        EventInstanceManager event = agent.getEventInstance();
        for (Reactor reactor : ACTIONS.reactors(agent)) {
            if (reactor == null || !reactor.isAlive() || !reactor.isActive()) continue;
            if (event != null && event.getProperty(ReactorActionManager.eventReactorActionKey(
                    "lpq", agent.getMapId(), reactor.getObjectId())) != null) {
                continue;
            }
            reactors.add(reactor);
        }
        return reactors;
    }

    static DarkSightRoomAction darkSightRoomAction(
            boolean darkSightActive, boolean reactorInteractionReady,
            boolean reactorAlreadyHit) {
        if (reactorAlreadyHit) return DarkSightRoomAction.NONE;
        if (reactorInteractionReady && darkSightActive) {
            return DarkSightRoomAction.CANCEL_FOR_ATTACK;
        }
        if (!reactorInteractionReady && !darkSightActive) {
            return DarkSightRoomAction.CAST_FOR_TRAVERSAL;
        }
        return DarkSightRoomAction.NONE;
    }

    private static boolean roomPassRequiresTraversal(Character agent) {
        if (agent == null || agent.getMap() == null || agent.getPosition() == null) return false;
        int radius = server.agents.runtime.AgentRuntimeConfig.cfg.LOOT_RADIUS;
        return agent.getMap().getDroppedItems().stream()
                .filter(java.util.Objects::nonNull)
                .filter(drop -> !drop.isPickedUp()
                        && drop.getItemId() == AgentLpqDefinition.PASS)
                .anyMatch(drop -> Math.abs(drop.getPosition().x - agent.getPosition().x) > radius
                        || Math.abs(drop.getPosition().y - agent.getPosition().y) > radius);
    }

    private static boolean passDropWithinPickupRange(Character agent) {
        if (agent == null || agent.getMap() == null || agent.getPosition() == null) return false;
        int radius = server.agents.runtime.AgentRuntimeConfig.cfg.LOOT_RADIUS;
        return agent.getMap().getDroppedItems().stream()
                .filter(java.util.Objects::nonNull)
                .filter(drop -> !drop.isPickedUp()
                        && drop.getItemId() == AgentLpqDefinition.PASS)
                .anyMatch(drop -> Math.abs(drop.getPosition().x - agent.getPosition().x) <= radius
                        && Math.abs(drop.getPosition().y - agent.getPosition().y) <= radius);
    }

    private static void cancelDarkSight(Character agent) {
        if (agent == null || agent.getBuffedValue(BuffStat.DARKSIGHT) == null) return;
        agent.cancelEffectFromBuffStat(BuffStat.DARKSIGHT);
        agent.cancelBuffStats(BuffStat.DARKSIGHT);
    }

    private static boolean refreshDarkSight(AgentRuntimeEntry entry, Character agent) {
        // A normal client may refresh an active buff. Do not cancel the live protection first:
        // readiness can still reject a cast while airborne, climbing, cooling down, or out of MP.
        return AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(entry, agent, Rogue.DARK_SIGHT);
    }

    private static boolean darkSightRefreshDue(Character agent, long nowMs) {
        StatEffect effect = agent.getBuffEffect(BuffStat.DARKSIGHT);
        return darkSightRefreshDue(agent.getBuffedStarttime(BuffStat.DARKSIGHT),
                effect == null ? 0 : effect.getDuration(), nowMs);
    }

    static boolean darkSightRefreshDue(Long startTimeMs, int durationMs, long nowMs) {
        if (startTimeMs == null || durationMs <= 0) return true;
        long remainingMs = startTimeMs + durationMs - nowMs;
        return remainingMs <= DARK_SIGHT_REFRESH_WINDOW_MS;
    }

    private static long traversalInactivity(AgentLpqMemberState member, Character agent,
                                            int destinationMapId, long nowMs) {
        Integer portalId = portalIdTo(agent, destinationMapId);
        Point portal = portalId == null ? null : ACTIONS.portalPosition(agent, portalId);
        long distanceSq = portal == null || agent.getPosition() == null
                ? Long.MAX_VALUE : (long) agent.getPosition().distanceSq(portal);
        return member.observeTraversalProgress(
                agent.getMapId(), destinationMapId, agent.getPosition(), distanceSq, nowMs);
    }

    private static boolean approachWithRecovery(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, int destinationMapId,
            Point target, String context, long nowMs) {
        return approachWithRecovery(session, member, entry, agent, destinationMapId,
                target, INTERACTION_RADIUS, context, nowMs);
    }

    static boolean approachWithRecovery(
            AgentLpqSession session, AgentLpqMemberState member,
            AgentRuntimeEntry entry, Character agent, int destinationMapId,
            Point target, int arrivalRadius, String context, long nowMs) {
        if (target == null) return false;
        if (near(agent.getPosition(), target, arrivalRadius)) {
            member.clearTraversalProgress();
            return true;
        }
        long distanceSq = agent.getPosition() == null
                ? Long.MAX_VALUE : (long) agent.getPosition().distanceSq(target);
        long inactiveForMs = member.observeTraversalProgress(
                agent.getMapId(), destinationMapId, agent.getPosition(), distanceSq, nowMs);
        int stage = AgentLpqDefinition.stageNumber(agent.getMapId());
        if (stage < 1 || stage > 9 || inactiveForMs < recovery(stage).traversalMs()) {
            ACTIONS.navigate(entry, target, true);
            return false;
        }
        ACTIONS.stop(entry);
        if (!rescueTeleport(session, "approach", entry, agent, target)) {
            ACTIONS.refreshNavigation(entry, agent);
            member.clearTraversalProgress();
            ACTIONS.navigate(entry, target, true);
            return false;
        }
        member.clearTraversalProgress();
        session.markProgress(nowMs);
        log.warn("LPQ {} position recovery: session={} member={}({}) map={} "
                        + "target={} inactiveForMs={}", context, session.sessionId(),
                agent.getName(), agent.getId(), agent.getMapId(), target, inactiveForMs);
        return true;
    }

    private static Integer portalIdTo(Character agent, int destinationMapId) {
        List<Integer> portalIds = AgentLpqExitRoutePolicy.portalIds(
                agent.getMapId(), destinationMapId);
        Point currentPosition = agent.getPosition();
        Integer nearest = portalIds.stream()
                .filter(portalId -> ACTIONS.portalPosition(agent, portalId) != null)
                .min(Comparator.comparingDouble(portalId ->
                        currentPosition == null ? 0.0
                                : ACTIONS.portalPosition(agent, portalId).distanceSq(currentPosition)))
                .orElse(null);
        return nearest != null ? nearest : ACTIONS.directPortalIdTo(agent, destinationMapId);
    }

    private static boolean enterPortalTo(AgentRuntimeEntry entry, Character agent, int destinationMapId) {
        int sourceMapId = agent.getMapId();
        Integer portalId = portalIdTo(agent, destinationMapId);
        if (portalId == null) return false;
        Point position = ACTIONS.portalPosition(agent, portalId);
        if (position == null) return false;
        Point waypoint = AgentLpqExitRoutePolicy.nextWaypoint(
                sourceMapId, destinationMapId, agent.getPosition(), position);
        // A character can finish a movement tick just inside the arrival radius of an
        // authored anchor. Do not treat that as permission to target the distant portal
        // directly; advance to the following authored leg first. This keeps Stage 4's
        // long dark-room return visibly grounded instead of crossing several platforms
        // in one navigation request.
        if (waypoint != null && near(agent.getPosition(), waypoint, INTERACTION_RADIUS)) {
            Point following = AgentLpqExitRoutePolicy.nextWaypoint(
                    sourceMapId, destinationMapId, waypoint, position);
            if (following != null) waypoint = following;
        }
        if (waypoint != null && !near(agent.getPosition(), waypoint, INTERACTION_RADIUS)) {
            ACTIONS.navigate(entry, waypoint, true);
            return true;
        }
        if (!near(agent.getPosition(), position, INTERACTION_RADIUS)) {
            ACTIONS.navigate(entry, position, true);
            return true;
        }
        ACTIONS.stop(entry);
        return LPQ.enterPortal(agent, portalId);
    }

    // ---- companion runs (P6g LPQ) ----

    /**
     * A companion run (a player leading his own companions, adopted through AgentLpqAdmissionService.admit): he plays,
     * they play beside him and talk like players. Stock, background and '!lpqtest' fixture runs never come here.
     * Called under the session monitor, from tick.
     */
    private static void companionRunTick(AgentLpqSession session, long nowMs) {
        boolean cleared = AgentLpqTerminationService.pastTheClear(session);
        if (!cleared && nowMs - session.lastProgressAtMs() > PHASE_TIMEOUT_MS) {
            fail(session, "No LPQ progress before phase timeout", AgentLpqParting.of(AgentLpqParting.Kind.TOO_LONG),
                    nowMs);
            return;
        }
        if (cleared && session.clearedAtMs() > 0L && nowMs - session.clearedAtMs() > COMPANION_AFTER_CLEAR_LIMIT_MS) {
            // The wrap-up line first; past the clear the termination service completes the run (nothing disposed).
            fail(session, "The companions did not all claim within the after-clear limit",
                    AgentLpqParting.of(AgentLpqParting.Kind.TOO_LONG), nowMs);
            return;
        }
        holdFullRateSimulation(session);
        Character leader = character(session.eventLeaderId());
        if (cleared) {
            // After the clear nothing about the leader ends the run: once he is let go they claim and go.
            if (session.clearedAtMs() == 0L) companionCleared(session, nowMs);
            releaseLeaderAfterClear(session, leader);
            companionAfterClear(session, session.leaderReleased() ? null : leader, nowMs);
            return;
        }
        if (leader == null) {
            fail(session, "The LPQ event leader is unavailable", new AgentLpqParting(AgentLpqParting.Kind.GONE,
                    session.eventLeaderId(), memberName(session, session.eventLeaderId())), nowMs);
            return;
        }
        if (leader.getHp() <= 0) {
            fail(session, "The LPQ event leader is unavailable",
                    AgentLpqParting.of(AgentLpqParting.Kind.DEFEATED, leader), nowMs);
            return;
        }
        if (session.phase() == AgentLpqSession.Phase.PREPARING) {
            companionPrepare(session, leader, nowMs);
            return;
        }
        if (session.eventInstance() == null && LPQ.event(leader) != null) session.bindEventInstance(LPQ.event(leader));
        synchronizePhase(session, leader, nowMs);
        if (AgentLpqTerminationService.pastTheClear(session)) return;
        String eventFailure = liveEventFailure(session, leader);
        if (!eventFailure.isEmpty()) {
            AgentLpqParting parting = eventParting(session, eventFailure, nowMs);
            // LudiPQ's clearPQ stops the event timer before it marks the clear: no failure, the next tick sees it.
            if (parting == null) return;
            if (EVENT_ENDED_OR_EXPIRED.equals(eventFailure)) {
                log.warn("LPQ event end detail: session={} {}", session.sessionId(),
                        eventEndDetail(session, leader));
            }
            fail(session, eventFailure, parting, nowMs);
            return;
        }
        // Departures and deaths come before the stage logic; a downed companion pauses nothing (the others play on).
        if (AgentLpqMemberVerdicts.insideEvent(session.phase())
                && (!reconcileLiveParty(session, nowMs) || !membersAlive(session, leader, nowMs))) {
            return;
        }
        recalculateStageAssignments(session, nowMs);
        companionStageLine(session, leader);
        switch (session.phase()) {
            case ENTERING -> companionEnter(session, leader, nowMs);
            case STAGE_1, STAGE_2, STAGE_3, STAGE_4, STAGE_5,
                    STAGE_7 -> collectionStage(session, leader, nowMs);
            case STAGE_6 -> portalMaze(session, leader, nowMs);
            case STAGE_8 -> platformPuzzle(session, leader, nowMs);
            case STAGE_9 -> boss(session, leader, nowMs);
            default -> { }
        }
    }

    /** The run is cleared: rewards freeze, any hand-over ends, the companions stop where they stand, the bonus begins. */
    private static void companionCleared(AgentLpqSession session, long nowMs) {
        session.freezeRewardEligibility();
        session.markCleared(nowMs);
        session.pace().markCleared(nowMs);
        session.endRecollect(false);
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            member.endHandOver();
            AgentRuntimeEntry entry = entry(member.characterId());
            if (entry == null) continue;
            AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
            ACTIONS.stop(entry);
        }
        if (stageNumberOrZero(session.phase()) > 0) session.transition(AgentLpqSession.Phase.BONUS, nowMs);
    }

    /** After the clear: the bonus, the claims, the way out; a null leader has been let go. */
    private static void companionAfterClear(AgentLpqSession session, Character leader, long nowMs) {
        switch (session.phase()) {
            case BONUS -> companionBonus(session, leader, nowMs);
            case CLAIMING_REWARD -> companionClaim(session, nowMs);
            case EXITING -> exitAfterRewardWarpSettles(session, nowMs);
            default -> { }
        }
    }

    /**
     * The bonus map: while the leader stays on it the companions loot and break the boxes beside him (C1, said there
     * by one of them); without him they leave through the Pink Balloon. A companion still on a stage map waits,
     * stopped, for clearPQ's warp and holds the bonus. With no companion on the bonus map or a stage map the claims
     * begin (the bonus timer warps everyone anyway; COMPANION_AFTER_CLEAR_LIMIT_MS bounds a warp that never comes).
     */
    private static void companionBonus(AgentLpqSession session, Character leader, long nowMs) {
        boolean leaderStays = leader != null && leader.getHp() > 0
                && leader.getMapId() == AgentLpqDefinition.CLEAR_MAP && inRun(session, leader);
        if (leaderStays) coordinate(session, narrator(session, leader), "cleared", AgentLpqCompanionPlay.CLEARED);
        boolean holdsBonus = false;
        Set<Integer> reactorClaims = activeReactorClaims(session, AgentLpqDefinition.CLEAR_MAP);
        Set<Integer> lootClaims = new LinkedHashSet<>();
        for (AgentLpqMemberState member : session.members()) {
            Character agent = standingCompanion(member);
            if (agent != null && onStageMap(agent)) {
                holdsBonus = true;
                continue;
            }
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null || agent.getMapId() != AgentLpqDefinition.CLEAR_MAP) continue;
            holdsBonus = true;
            if (leaderStays) {
                if (collectNearestDrop(entry, agent, null, lootClaims)) continue;
                if (hitNearestReactorWithoutRecovery(entry, agent, nowMs, member, reactorClaims)) {
                    session.markProgress(nowMs);
                }
                if (member.reactorTargetObjectId() != 0) reactorClaims.add(member.reactorTargetObjectId());
            } else {
                leaveThroughThePinkBalloon(session, member, entry, agent, nowMs);
            }
        }
        if (!holdsBonus) session.transition(AgentLpqSession.Phase.CLAIMING_REWARD, nowMs);
    }

    private static void leaveThroughThePinkBalloon(AgentLpqSession session, AgentLpqMemberState member,
                                                   AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (!approachRewardNpc(session, member, entry, agent, AgentLpqDefinition.CLEAR_NPC, "bonus exit balloon",
                nowMs) || nowMs < member.nextActionAtMs()) {
            return;
        }
        member.deferUntil(nowMs + INTERACTION_RETRY_MS);
        if (LPQ.runNpc(agent, AgentLpqDefinition.CLEAR_NPC, 1)) session.markProgress(nowMs);
    }

    /**
     * The claims: each companion still in the event leaves the bonus map, and on the reward map waits
     * COMPANION_CLAIM_GRACE_MS (C2 from the first one there) and claims at Arturo, which takes it out. One Arturo turned
     * away COMPANION_CLAIM_FAILURE_LIMIT times gives its reward up and leaves through the event's exit. Humans are
     * never waited for: with no companion pending the run exits.
     */
    private static void companionClaim(AgentLpqSession session, long nowMs) {
        session.freezeRewardEligibility();
        boolean pending = false;
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT || member.rewardResolved()) continue;
            Character agent = character(member.characterId());
            if (agent == null || !inRun(session, agent)) continue;
            if (member.claimFailures() >= COMPANION_CLAIM_FAILURE_LIMIT) {
                giveUpClaim(session, member, agent);
                continue;
            }
            pending = true;
            AgentRuntimeEntry entry = entry(member.characterId());
            if (entry == null || agent.getHp() <= 0) continue;
            if (agent.getMapId() == AgentLpqDefinition.CLEAR_MAP) {
                leaveThroughThePinkBalloon(session, member, entry, agent, nowMs);
                continue;
            }
            if (agent.getMapId() != AgentLpqDefinition.BONUS_MAP) continue;
            long arrivedAtMs = member.observeArrivedOnRewardMap(nowMs);
            coordinate(session, agent, "arturo", AgentLpqCompanionPlay.ARTURO);
            if (nowMs - arrivedAtMs < COMPANION_CLAIM_GRACE_MS) continue;
            if (approachRewardNpc(session, member, entry, agent, AgentLpqDefinition.REWARD_NPC, "Arturo reward", nowMs)
                    && nowMs >= member.nextActionAtMs()) {
                member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                if (LPQ.runNpc(agent, AgentLpqDefinition.REWARD_NPC, 1)) session.markProgress(nowMs);
            }
        }
        if (!pending) session.transition(AgentLpqSession.Phase.EXITING, nowMs);
    }

    /** Its reward forfeited, a companion Arturo keeps turning away leaves through the event's exit. */
    private static void giveUpClaim(AgentLpqSession session, AgentLpqMemberState member, Character agent) {
        session.forfeitReward(member.characterId());
        log.warn("LPQ claim failed: session={} member={} reason=inventory failures={}", session.sessionId(),
                member.characterId(), member.claimFailures());
        EventInstanceManager event = session.eventInstance();
        if (event != null && LPQ.event(agent) == event) event.exitPlayer(agent);
    }

    /** After the clear the leader is let go once he logs off, goes down, or no longer leads the live party. */
    private static void releaseLeaderAfterClear(AgentLpqSession session, Character leader) {
        if (session.leaderReleased()) return;
        String why = leader == null ? "offline"
                : leader.getHp() <= 0 ? "down"
                : leadPassed(session, leader) ? "lead passed" : null;
        if (why == null) return;
        session.markLeaderReleased();
        log.info("LPQ leader let go after the clear: session={} leader={} why={} phase={}",
                session.sessionId(), session.eventLeaderId(), why, session.phase());
    }

    /** His live party is gone, or someone else leads it. */
    private static boolean leadPassed(AgentLpqSession session, Character leader) {
        AgentPartySnapshot party = PARTY.snapshot(leader);
        return party == null || party.members().stream().noneMatch(member -> member != null && member.leader()
                && member.id() == session.eventLeaderId());
    }

    /**
     * Stage 8 needs five on the boxes: a five-member companion run has four companions, so its leader stands on the
     * fifth, after them (decision D-c: stock party sizes). Every other run keeps its non-leaders.
     */
    private static List<AgentLpqMemberState> withTheLeaderOnABox(AgentLpqSession session,
                                                                 List<AgentLpqMemberState> nonLeaders) {
        if (!session.companionAdopted() || nonLeaders.size() >= 5) return nonLeaders;
        AgentLpqMemberState leader = session.member(session.eventLeaderId());
        if (leader == null) return nonLeaders;
        List<AgentLpqMemberState> participants = new ArrayList<>(nonLeaders);
        participants.add(leader);
        return List.copyOf(participants);
    }

    /**
     * A five-member run's leader is given a box like the others but never moved: a companion tells him which (L8a), and
     * which to move to when his box changes for the next formation (L8m).
     */
    private static void tellTheLeaderHisBox(AgentLpqSession session, Character leader) {
        AgentLpqMemberState leaderState = session.member(leader.getId());
        int platform = leaderState == null ? 0 : leaderState.assignedPlatform();
        if (platform <= 0 || platform == session.stage8LeaderBoxTold()) return;
        boolean first = session.stage8LeaderBoxTold() == 0;
        Character speaker = narrator(session, leader);
        boolean said = first
                ? coordinate(session, speaker, "s8-yours", AgentLpqCompanionPlay.yourBoxLine(leader.getName(), platform))
                : coordinate(session, speaker, "s8-move-" + session.stage8Attempt(),
                AgentLpqCompanionPlay.moveToBoxLine(leader.getName(), platform));
        if (said) session.tellStage8LeaderBox(platform);
    }

    /** This character stands in the run's own event instance. */
    private static boolean inRun(AgentLpqSession session, Character character) {
        EventInstanceManager event = session.eventInstance();
        return character != null && event != null && LPQ.event(character) == event;
    }

    /**
     * The party is inside: a companion run's pace record begins with who entered and each one's power against LPQ's
     * mobs, read on this tick from the warmed pace profile (none while it is cold, or when any member cannot be read).
     */
    private static void beginPace(AgentLpqSession session, long nowMs) {
        List<Integer> entrants = new ArrayList<>();
        List<AgentPqMemberPower> power = new ArrayList<>();
        AgentPqPaceProfile profile = AgentPqPaceProfiles.cached(AgentLpqPaceProfile.QUEST_KEY);
        boolean readable = profile != null;
        for (AgentLpqMemberState member : session.members()) {
            entrants.add(member.characterId());
            if (!readable) continue;
            Character character = character(member.characterId());
            if (character == null) {
                readable = false;
                continue;
            }
            try {
                power.add(AgentPqMemberPower.of(character, profile,
                        member.memberType() == AgentLpqMemberState.MemberType.HUMAN,
                        member.characterId() == session.eventLeaderId()));
            } catch (RuntimeException | LinkageError failure) {
                readable = false;
                log.debug("LPQ pace power unreadable: session={} member={}", session.sessionId(),
                        member.characterId(), failure);
            }
        }
        session.pace().markEntered(readable ? power : List.of(), entrants, nowMs);
    }

    /** Stage 9's pace: Alishar first seen (with his HP), then gone. */
    private static void markAlishar(AgentLpqSession session, Monster alishar, long nowMs) {
        if (alishar != null) session.pace().markAlisharSeen(alishar.getMaxHp(), nowMs);
        else session.pace().markAlisharDown(nowMs);
    }

    /** On one of the run's stage maps (stages 1-9 and their rooms), not on the bonus or the reward map. */
    private static boolean onStageMap(Character character) {
        int mapId = character.getMapId();
        return AgentLpqDefinition.isEventMap(mapId) && mapId != AgentLpqDefinition.CLEAR_MAP
                && mapId != AgentLpqDefinition.BONUS_MAP;
    }

    /**
     * A companion run's end. A companion puts it in its own words (partingNarrator), SILENT before the party was
     * inside (the desk already answered the leader); the raw reason goes only to the log. Then the termination service
     * ends it: before the clear as a stock failure does (everyone out, the event disposed), past the clear as a
     * completed run (only the companions leave). A stock run fails as it always did, without a line.
     */
    private static void fail(AgentLpqSession session, String reason, AgentLpqParting parting, long nowMs) {
        if (!session.companionAdopted()) {
            AgentLpqTerminationService.fail(session, reason, nowMs);
            return;
        }
        if (session.terminal() || session.terminationStarted()) return;
        boolean pastTheClear = AgentLpqTerminationService.pastTheClear(session);
        AgentLpqParting said = beforeEntry(session.phase()) ? AgentLpqParting.SILENT : parting;
        Character speaker = partingNarrator(session);
        log.info("LPQ parting: session={} phase={} kind={} speaker={} pastTheClear={}", session.sessionId(),
                session.phase(), said.kind(), speaker == null ? "none" : speaker.getName(), pastTheClear);
        if (speaker != null) narrate(session, speaker, "failed", said.line(speaker.getId(), session.eventLeaderId(), pastTheClear));
        AgentLpqTerminationService.fail(session, reason, nowMs);
    }

    /** Before the party was inside: the desk already answered the leader, so the run ends without a word. */
    private static boolean beforeEntry(AgentLpqSession.Phase phase) {
        return phase == AgentLpqSession.Phase.PREPARING || phase == AgentLpqSession.Phase.ENTERING;
    }

    /**
     * How a companion run puts a live-event failure: the event's end by its cause, too few registered members as
     * someone having left, the timer as the time running out, anything else in general words. Null when the timer
     * reads stopped long before its end: LudiPQ.js clearPQ stops it before it marks the clear, so a tick in between
     * sees neither; the next tick sees the clear, or the event's real end.
     */
    private static AgentLpqParting eventParting(AgentLpqSession session, String eventFailure, long nowMs) {
        if (EVENT_ENDED_OR_EXPIRED.equals(eventFailure)) return AgentLpqParting.of(endedCause(session, nowMs));
        if ("LPQ no longer has five registered session members".equals(eventFailure)) {
            return AgentLpqParting.of(AgentLpqParting.Kind.LEFT);
        }
        if ("The LPQ event timer expired".equals(eventFailure)) {
            return endedCause(session, nowMs) == AgentLpqParting.Kind.TIMEOUT
                    ? AgentLpqParting.of(AgentLpqParting.Kind.TIMEOUT) : null;
        }
        return AgentLpqParting.GENERIC;
    }

    /** Why the event ended under the run: LudiPQ's timer, when the run has stood in it that long; else neither. */
    private static AgentLpqParting.Kind endedCause(AgentLpqSession session, long nowMs) {
        long enteredAtMs = session.enteredEventAtMs() > 0L ? session.enteredEventAtMs() : session.startedAtMs();
        return AgentLpqParting.eventEnded(nowMs - enteredAtMs,
                AgentPartyQuestCatalog.require("lpq").eventTimerMs(), TIMEOUT_SLACK_MS);
    }

    /**
     * Who says a parting line: the narrator on the leader's map, else the first standing companion in the run's
     * event, else the first companion online even when it is down; null when none is.
     */
    private static Character partingNarrator(AgentLpqSession session) {
        Character onHisMap = narrator(session, character(session.eventLeaderId()));
        if (onHisMap != null) return onHisMap;
        EventInstanceManager event = session.eventInstance();
        Character online = null;
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            if (agent == null) continue;
            if (standingCompanion(member) != null && (event == null || LPQ.event(agent) == event)) return agent;
            if (online == null) online = agent;
        }
        return online;
    }

    /** The live party, as the first online member of the run sees it; null when none has one. */
    private static AgentPartySnapshot liveParty(AgentLpqSession session) {
        for (AgentLpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            AgentPartySnapshot party = character == null ? null : PARTY.snapshot(character);
            if (party != null) return party;
        }
        return null;
    }

    /** A member's name: from the live party, which keeps offline members, else from the online character; or null. */
    private static String memberName(AgentLpqSession session, int characterId) {
        AgentPartySnapshot party = liveParty(session);
        String name = party == null ? null : party.members().stream()
                .filter(member -> member != null && member.id() == characterId)
                .map(member -> member.name())
                .findFirst().orElse(null);
        if (name != null) return name;
        Character character = character(characterId);
        return character == null ? null : character.getName();
    }

    /**
     * Takes one member out of the run and both indexes (out of the event first, while still in it) and hands an Agent
     * back to its owner. The split rooms it had not finished go back at once (a free companion takes them on the next
     * tick, not after the room lease), and the key carrier and re-collector roles end if they were its.
     */
    private static void departMember(AgentLpqSession session, AgentLpqMemberState member, String reason, long nowMs) {
        int id = member.characterId();
        Character character = character(id);
        EventInstanceManager event = session.eventInstance();
        if (character != null && event != null && LPQ.event(character) == event) event.exitPlayer(character);
        AgentLpqFullRateSimulation.apply(member, AgentRuntimeRegistry.findByAgentCharacterId(id), false);
        // A companion killed outright still held its passes as it went down; the respawn's unregister took them.
        int held = character == null ? -1 : character.getItemQuantity(AgentLpqDefinition.PASS, false);
        log.info("LPQ passes lost with member: session={} member={} passes={}", session.sessionId(), id,
                member.passesWhenDown() > 0 ? Math.max(held, member.passesWhenDown()) : held);
        log.warn("LPQ member left the run and was ejected: session={} member={} type={} phase={} reason={} "
                        + "remainingBefore={}", session.sessionId(), id, member.memberType(), session.phase(), reason,
                session.memberCount());
        session.rooms().assignments().forEach((room, owner) -> {
            if (owner == id && !session.rooms().completed(room)) session.rooms().release(room);
        });
        session.removeMember(id);
        AgentLpqSessionRegistry.unindexMember(session, id);
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(session.operatorId());
        if (engagement != null) AgentPartyQuestEngagementRegistry.removeAndUnindexMember(engagement, id, nowMs);
        if (member.memberType() == AgentLpqMemberState.MemberType.AGENT) {
            AgentPartyQuestLifecycleRuntime.recoverDetachedMember(id, "lpq", nowMs);
        }
        if (session.keyCarrierId() == id) session.setKeyCarrierId(0);
        if (session.recollectorId() == id) session.endRecollect(false);
    }

    /** False when the departures ended the run: fewer than five left, or no companion (the humans carry on). */
    private static boolean carriesOn(AgentLpqSession session, AgentLpqParting belowFive, long nowMs) {
        if (session.memberCount() < AgentLpqDefinition.MIN_PARTY_SIZE) {
            fail(session, "The companion run fell below five members", belowFive, nowMs);
            return false;
        }
        if (session.members().stream()
                .noneMatch(member -> member.memberType() == AgentLpqMemberState.MemberType.AGENT)) {
            AgentLpqTerminationService.detach(session, "every companion left; the humans carry on", nowMs);
            return false;
        }
        return true;
    }

    /**
     * The departed-member pass, read from the live party as HPQ's: a member no longer in it (a tapped-out companion its
     * host took out, a player who left) is ejected; fewer than five end the run LEFT, and with no companion left the
     * humans carry on. A lead passed inside the event ends the run. False when the run ended.
     */
    private static boolean reconcileLiveParty(AgentLpqSession session, long nowMs) {
        if (session.eventInstance() == null) return true;
        AgentPartySnapshot party = liveParty(session);
        if (party == null) {
            fail(session, "The LPQ party was disbanded", AgentLpqParting.GENERIC, nowMs);
            return false;
        }
        Set<Integer> live = party.members().stream().filter(java.util.Objects::nonNull)
                .map(member -> member.id()).collect(java.util.stream.Collectors.toSet());
        List<AgentLpqMemberState> departed = session.members().stream()
                .filter(member -> member.characterId() != session.eventLeaderId())
                .filter(member -> !live.contains(member.characterId()))
                .toList();
        AgentLpqParting left = AgentLpqParting.of(AgentLpqParting.Kind.LEFT);
        for (AgentLpqMemberState member : departed) {
            Character character = character(member.characterId());
            if (left.subject() == null && character != null) {
                left = AgentLpqParting.of(AgentLpqParting.Kind.LEFT, character);
            }
            departMember(session, member, "left the party", nowMs);
        }
        if (!departed.isEmpty() && !carriesOn(session, left, nowMs)) return false;
        int liveLeaderId = party.members().stream().filter(java.util.Objects::nonNull)
                .filter(member -> member.leader())
                .mapToInt(member -> member.id()).findFirst().orElse(0);
        if (liveLeaderId != session.eventLeaderId()) {
            fail(session, "The live LPQ party is no longer led by the run's leader", AgentLpqParting.GENERIC, nowMs);
            return false;
        }
        return true;
    }

    /**
     * Every non-leader member by AgentLpqMemberVerdicts (the leader was handled before). A downed one is marked down
     * and left out of the stage loops, which keep running; a companion up outside the event (the departure gate let
     * its respawn through at six) is tapped out by its host, which voices it (a refusal ends the run DEFEATED); a human
     * who dropped is ejected. With fewer than five others standing the run ends WENT_DOWN. False when the run ended.
     */
    private static boolean membersAlive(AgentLpqSession session, Character leader, long nowMs) {
        EventInstanceManager event = session.eventInstance();
        boolean leaderInBoundEvent = event != null && LPQ.event(leader) == event;
        boolean cleared = event != null && event.isEventCleared();
        // Counted as the loop marks members, so two companions downed in one tick cannot both wait.
        int down = (int) session.members().stream().filter(AgentLpqMemberState::down).count();
        for (AgentLpqMemberState member : session.members()) {
            int id = member.characterId();
            if (id == session.eventLeaderId()) continue;
            Character character = character(id);
            AgentLpqMemberVerdicts.Verdict verdict = AgentLpqMemberVerdicts.verdict(new AgentLpqMemberVerdicts.Facts(
                    true, session.phase(), member.memberType(), false, character != null,
                    character == null ? 0 : character.getHp(), character != null && LPQ.event(character) == event,
                    leaderInBoundEvent, cleared, member.downSinceMs(), member.tapOutRequested(),
                    session.memberCount() - 1 - (down - (member.down() ? 1 : 0)), nowMs, MEMBER_RECOVERY_TIMEOUT_MS));
            switch (verdict) {
                case MISSING -> {
                    if (nowMs - member.observeMissing(nowMs) >= MEMBER_RECOVERY_TIMEOUT_MS) {
                        fail(session, "Party member " + id + " disconnected or left the channel",
                                new AgentLpqParting(AgentLpqParting.Kind.GONE, id, memberName(session, id)), nowMs);
                        return false;
                    }
                }
                case OK -> member.clearMissing();
                case DEFEATED -> {
                    fail(session, character.getName() + " was defeated during LPQ",
                            AgentLpqParting.of(AgentLpqParting.Kind.DEFEATED, character), nowMs);
                    return false;
                }
                case SHORT_HANDED -> {
                    fail(session, character.getName() + " went down, and LPQ needs five",
                            AgentLpqParting.of(AgentLpqParting.Kind.WENT_DOWN, character), nowMs);
                    return false;
                }
                case DROP_HUMAN -> {
                    if (!dropHuman(session, member, character, nowMs)) return false;
                }
                case DOWN_WAIT -> {
                    if (!member.down()) down++;
                    markDown(session, member, character, nowMs);
                }
                case REQUEST_TAP_OUT -> {
                    // The stock respawn carried it out of the event; its host takes it out of the party (and has it
                    // say its own tap-out line there), and the departed-member pass ejects it on a later tick.
                    if (AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut(
                            "lpq", session.sessionId(), id, character.getMapId(), nowMs))) {
                        member.markTapOutRequested();
                        session.pace().markTappedOut(id);
                        log.info("LPQ companion tapped out: session={} member={} map={}",
                                session.sessionId(), id, character.getMapId());
                    } else if (!cleared) {
                        fail(session, character.getName() + " was defeated during LPQ and its host kept it",
                                AgentLpqParting.of(AgentLpqParting.Kind.DEFEATED, character), nowMs);
                        return false;
                    }
                    // Else the Violet Balloon cleared the run on its own thread since this tick read the clear: after
                    // the clear nothing is asked of a downed member.
                }
                case REVIVED -> {
                    if (member.confirmRevived(nowMs)) {
                        member.clearDown();
                        down--;
                    }
                }
                case IGNORE -> {
                }
            }
        }
        return true;
    }

    /** Marks a member down at its first sighting, with the passes it holds (logged once). */
    private static void markDown(AgentLpqSession session, AgentLpqMemberState member, Character character,
                                 long nowMs) {
        boolean first = !member.down();
        int passes = character == null ? 0
                : Math.max(0, character.getItemQuantity(AgentLpqDefinition.PASS, false));
        member.markDown(nowMs, passes);
        if (first) {
            session.pace().markDown(member.characterId());
            log.info("LPQ member down: session={} member={} type={} phase={} passes={}",
                    session.sessionId(), member.characterId(), member.memberType(), session.phase(), passes);
        }
    }

    /**
     * A non-leader human who went offline or is up outside the event is left behind: the run carries on with T1, or
     * ends LEFT below five. False when the run ended.
     */
    private static boolean dropHuman(AgentLpqSession session, AgentLpqMemberState member, Character character,
                                     long nowMs) {
        int id = member.characterId();
        String name = memberName(session, id);
        departMember(session, member, character == null ? "offline" : "up outside the event", nowMs);
        if (session.memberCount() < AgentLpqDefinition.MIN_PARTY_SIZE) {
            fail(session, "A human member dropped, and LPQ needs five",
                    new AgentLpqParting(AgentLpqParting.Kind.LEFT, id, name), nowMs);
            return false;
        }
        coordinate(session, partingNarrator(session), "drop-" + id,
                AgentLpqCompanionPlay.carryOnLine(name == null ? "someone" : name, session.memberCount()));
        return true;
    }

    /**
     * True when the watchdog, finding no standing companion to run a companion run, should wait: one has been down less
     * than MEMBER_RECOVERY_TIMEOUT_MS and the stock respawn will carry it out, where its host taps it out. Short-handed
     * the run ends WENT_DOWN now (LudiPQ.js would end the event at the respawn anyway).
     */
    static boolean awaitingRespawn(AgentLpqSession session, long nowMs) {
        synchronized (session) {
            if (session.terminal() || session.terminationStarted()) return true;
            // Before entry a respawn stays in Ludibrium, so there is nothing to wait for.
            if (beforeEntry(session.phase())) return false;
            Character downed = null;
            for (AgentLpqMemberState member : session.members()) {
                if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
                Character agent = character(member.characterId());
                if (agent == null) continue;
                if (agent.getHp() <= 0) markDown(session, member, agent, nowMs);
                if (downed == null && member.down() && nowMs - member.downSinceMs() < MEMBER_RECOVERY_TIMEOUT_MS) {
                    downed = agent;
                }
            }
            if (downed == null) return false;
            if (shortHanded(session)) {
                fail(session, downed.getName() + " went down, and LPQ needs five",
                        AgentLpqParting.of(AgentLpqParting.Kind.WENT_DOWN, downed), nowMs);
            }
            return true;
        }
    }

    /** The watchdog found no live Agent to run the session: a companion run hears it end, a stock run ends as ever. */
    static void failWithoutLiveAgent(AgentLpqSession session, long nowMs) {
        String reason = "No live Agent remains";
        if (!session.companionAdopted()) {
            AgentLpqTerminationService.fail(session, reason, nowMs);
            return;
        }
        synchronized (session) {
            fail(session, reason, AgentLpqParting.GENERIC, nowMs);
        }
    }

    /** Inside an uncleared stage, fewer than five members still stand in the event (up, not marked down, inside). */
    private static boolean shortHanded(AgentLpqSession session) {
        EventInstanceManager event = session.eventInstance();
        if (!AgentLpqMemberVerdicts.insideEvent(session.phase()) || event == null || event.isEventCleared()) {
            return false;
        }
        return session.members().stream()
                .filter(member -> !member.down())
                .map(member -> character(member.characterId()))
                .filter(character -> character != null && character.getHp() > 0 && LPQ.event(character) == event)
                .count() < AgentLpqDefinition.MIN_PARTY_SIZE;
    }

    /**
     * He clicks the Red Sign himself: nobody walks anywhere and nobody announces anything. The run goes on once he
     * stands in the event on stage 1, and ends without a word if he has not after COMPANION_ENTRY_WAIT_MS.
     */
    private static void companionPrepare(AgentLpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() == AgentLpqDefinition.stage(1).mapId() && LPQ.event(leader) != null) {
            session.markEnteredEvent(nowMs);
            session.transition(AgentLpqSession.Phase.ENTERING, nowMs);
            return;
        }
        if (nowMs - session.phaseEnteredAtMs() >= COMPANION_ENTRY_WAIT_MS) {
            fail(session, "The companion run's leader did not take the party into LPQ within the entry wait",
                    AgentLpqParting.SILENT, nowMs);
        }
    }

    /**
     * Waits for every member on stage 1 in his event; nobody is re-registered or moved. After COMPANION_ENTRY_WAIT_MS
     * the run ends while fewer than all are inside.
     */
    private static void companionEnter(AgentLpqSession session, Character leader, long nowMs) {
        long waitedMs = nowMs - session.phaseEnteredAtMs();
        int stageOne = AgentLpqDefinition.stage(1).mapId();
        EventInstanceManager event = LPQ.event(leader);
        if (leader.getMapId() != stageOne || event == null) {
            if (waitedMs >= COMPANION_ENTRY_WAIT_MS) {
                fail(session, "The companion run's leader left before the party was inside LPQ",
                        AgentLpqParting.SILENT, nowMs);
            }
            return;
        }
        session.bindEventInstance(event);
        List<AgentLpqMemberState> absent = new ArrayList<>();
        for (AgentLpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character == null || character.getMapId() != stageOne || !LPQ.sameEvent(leader, character)) {
                absent.add(member);
            }
        }
        int members = session.memberCount();
        switch (AgentLpqCompanionPlay.entering(members - absent.size(), members, waitedMs,
                COMPANION_ENTRY_WAIT_MS, AgentLpqDefinition.MIN_PARTY_SIZE)) {
            case ENTERED -> {
                beginStageOne(session, nowMs);
                companionStageLine(session, leader);
            }
            case EJECT_ABSENT -> {
                // Five or more are inside: the run goes on without the rest, as a party of players would.
                for (AgentLpqMemberState member : absent) {
                    if (member.characterId() == session.eventLeaderId()) continue;
                    departMember(session, member, "not inside at the end of the entry wait", nowMs);
                }
                if (!carriesOn(session, AgentLpqParting.SILENT, nowMs)) return;
                beginStageOne(session, nowMs);
                companionStageLine(session, leader);
            }
            case GIVE_UP -> fail(session,
                    "Fewer than five of the companion run entered LPQ within the entry wait", AgentLpqParting.SILENT,
                    nowMs);
            default -> { }
        }
    }

    /**
     * A companion run's companions standing in its instance (stages 1-9, the bonus, and the reward map until their
     * reward is claimed) run at full rate, so a room nobody watches plays as a watched one (KPQ's rule); nobody else's
     * are held, and the hold lets go as a member leaves the instance or the run.
     */
    private static void holdFullRateSimulation(AgentLpqSession session) {
        AgentLpqSession.Phase phase = session.phase();
        boolean ending = session.terminationStarted();
        boolean inside = stageNumberOrZero(phase) > 0 || phase == AgentLpqSession.Phase.BONUS;
        boolean claiming = phase == AgentLpqSession.Phase.CLAIMING_REWARD;
        EventInstanceManager event = session.eventInstance();
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            boolean wanted = !ending && session.companionAdopted() && agent != null && event != null
                    && LPQ.event(agent) == event && (inside || claiming && !member.rewardResolved());
            AgentLpqFullRateSimulation.apply(member, entry(member.characterId()), wanted);
        }
    }

    /**
     * A companion run's collection stage (1-5, 7): the companions collect as today (no rescue, no regroup at the
     * balloon, no vacuum), and each carrier brings what it holds to the leader's feet on his map (the same-map rule),
     * by the KPQ/HPQ hand-over pattern: the map's own drop placement, the balloon when he cannot be reached, a nudge,
     * and a re-collect before a stack he leaves lying would vanish. He talks to the balloon himself.
     */
    private static void companionCollection(AgentLpqSession session, Character leader,
                                            AgentLpqDefinition.Stage contract, long nowMs) {
        int stage = contract.number();
        int required = contract.submissionCount();
        catchUpLaggingMembers(session, stage, contract.mapId(), nowMs);
        Set<MapleMap> maps = collectionMaps(session, stage);
        List<MapItem> lying = lyingRunStacks(session, maps, AgentLpqDefinition.PASS);
        if (passesAtHisFeet(session, leader) >= required) session.pace().markPassesAtFeet(stage, nowMs);
        int accounted = partyPassCount(session) + stackQuantity(lying);
        Point npc = stageNpcPosition(session, stage);
        if (accounted < required || stage == 4 || stage == 5) {
            runCollectionObjectives(session, stage, nowMs);
        } else {
            for (AgentLpqMemberState member : session.members()) {
                Character agent = standingCompanion(member);
                AgentRuntimeEntry entry = entry(member.characterId());
                if (agent != null && entry != null && agent.getMapId() == AgentLpqDefinition.STAGE_2_TRAP_MAP) {
                    // Every pass is found (the trap room's last one is often the 15th): a scout still in there walks
                    // out through its exit and brings its passes to his feet, as a player does.
                    member.clearReactorWork();
                    useStageTwoTrapExit(session, entry, agent, nowMs);
                    continue;
                }
                if (agent != null && entry != null && agent.getMapId() == contract.mapId() && npc != null
                        && agent.getItemQuantity(AgentLpqDefinition.PASS, false) <= 0) {
                    rallyMemberAtNpc(member, agent, entry, npc);
                }
            }
        }
        followRecollect(session, lying, nowMs);
        for (AgentLpqMemberState member : session.members()) {
            Character agent = standingCompanion(member);
            AgentRuntimeEntry entry = entry(member.characterId());
            if (agent == null || entry == null) continue;
            if (session.recollectStackObjectId() != 0 && session.recollectorId() == agent.getId()) continue;
            tellGivenUpRoom(session, member, agent, leader, contract.mapId());
            int held = agent.getItemQuantity(AgentLpqDefinition.PASS, false);
            if (held <= 0) {
                if (member.handOverStartedAtMs() != 0L) member.endHandOver();
                continue;
            }
            boolean onMainMap = agent.getMapId() == contract.mapId();
            // On the main map its passes stay in the event when it goes down; in a room (stages 4-5) it keeps them,
            // since a stack dropped there is nobody's to loot and its room is not done.
            if (onMainMap && aboutToGoDown(agent)) {
                dropHandOver(session, member, agent, entry, leader, AgentLpqDefinition.PASS, held, stage,
                        AgentLpqCompanionPlay.PassPlace.DYING, lying, nowMs);
                continue;
            }
            boolean inUnfinishedRoom = member.assignedMapId() != 0
                    && !session.rooms().completed(member.assignedMapId());
            boolean due = member.handOverStartedAtMs() != 0L || member.handOverRecollectStack() != 0
                    || AgentLpqCompanionPlay.handOverDue(held, PASS_HANDOVER_BATCH,
                    onMainMap && agent.getMap() == leader.getMap()
                            && near(agent.getPosition(), leader.getPosition(), PASS_HANDOVER_NEAR_PX),
                    accounted >= required, (stage == 4 || stage == 5) && onMainMap && member.assignedMapId() == 0,
                    false);
            if (!due || !onMainMap || inUnfinishedRoom) continue;
            if (agent.getMap() != leader.getMap()) {
                // The same-map rule: he is not on this stage's map yet; wait for him by the balloon.
                if (member.handOverStartedAtMs() != 0L) member.endHandOver();
                if (npc != null) rallyMemberAtNpc(member, agent, entry, npc);
                continue;
            }
            if (member.handOverStartedAtMs() == 0L) member.startHandOver(nowMs);
            handOverItem(session, member, agent, entry, leader, AgentLpqDefinition.PASS, held, stage, lying, nowMs);
        }
        nudgeLyingStacks(session, stage, leader, lying, nowMs);
        beginRecollect(session, lying, nowMs);
        if (leader.getItemQuantity(AgentLpqDefinition.PASS, false) >= required
                && !session.narrated("s" + stage + "-passes") && !session.narrated("s" + stage + "-balloon")) {
            coordinate(session, narrator(session, leader), "s" + stage + "-all",
                    AgentLpqCompanionPlay.allPassesLine(leader.getName(), stage));
        }
    }

    /** The run's leader stands on this map. */
    private static boolean leaderOnMap(AgentLpqSession session, int mapId) {
        Character leader = character(session.eventLeaderId());
        return leader != null && leader.getMapId() == mapId;
    }

    /**
     * A companion run's maze: the leader's climb to a new highest row is progress, and once every standing companion
     * waits at the top while he is still below it, one of them tells him the code again (S6n) after LEADER_NUDGE_MS.
     */
    private static void companionMazeClimb(AgentLpqSession session, Character leader, long nowMs) {
        int mazeMap = AgentLpqDefinition.stage(6).mapId();
        if (leader.getMapId() != mazeMap || leader.getPosition() == null) return;
        int row = leader.getPosition().y < -3_050 ? STAGE_6_PORTAL_IDS.size() : mazeRow(leader);
        if (row >= 0 && session.observeLeaderClimb(row)) session.markProgress(nowMs);
        Character atTheTop = null;
        boolean allAtTop = true;
        for (AgentLpqMemberState member : session.members()) {
            Character agent = standingCompanion(member);
            if (agent == null) continue;
            if (agent.getMapId() != mazeMap || agent.getPosition() == null || agent.getPosition().y >= -3_050) {
                allAtTop = false;
            } else if (atTheTop == null) {
                atTheTop = agent;
            }
        }
        boolean leaderBelow = leader.getPosition().y >= -3_050;
        long waitedMs = session.observeStageSixAllAtTop(allAtTop && atTheTop != null && leaderBelow, nowMs);
        if (waitedMs >= LEADER_NUDGE_MS) {
            coordinate(session, atTheTop, "s6-climb", AgentLpqCompanionPlay.mazeClimbLine(leader.getName()));
        }
    }

    /**
     * N1: LEADER_NUDGE_MS after a stage's clear the leader is still on its map away from the next portal, while the
     * companions wait at it: one of them, at the portal on his map, calls him once.
     */
    private static void nudgeLeaderToPortal(AgentLpqSession session, int stage, int source, int destination,
                                            long sinceClearMs) {
        if (stage == 6 || stage >= 9 || sinceClearMs < LEADER_NUDGE_MS) return;
        Character leader = character(session.eventLeaderId());
        if (leader == null || leader.getMapId() != source) return;
        Integer portalId = portalIdTo(leader, destination);
        Point portal = portalId == null ? null : ACTIONS.portalPosition(leader, portalId);
        if (portal == null || near(leader.getPosition(), portal, INTERACTION_RADIUS)) return;
        for (AgentLpqMemberState member : session.members()) {
            Character agent = standingCompanion(member);
            if (agent != null && agent.getMap() == leader.getMap() && near(agent.getPosition(), portal, INTERACTION_RADIUS)) {
                coordinate(session, agent, "s" + stage + "-portal", AgentLpqCompanionPlay.portalOpenLine(leader.getName()));
                return;
            }
        }
    }

    /** S7: who takes the trigger ratz on the ledges, and that he stays at the bottom. */
    private static void sayStageSevenRoles(AgentLpqSession session, List<Integer> topIds) {
        Character leader = character(session.eventLeaderId());
        if (leader == null || leader.getMapId() != AgentLpqDefinition.stage(7).mapId()) return;
        List<String> shooters = topIds.stream().map(AgentLpqCoordinator::character)
                .filter(java.util.Objects::nonNull).map(Character::getName).toList();
        coordinate(session, narrator(session, leader), "s7-start",
                AgentLpqCompanionPlay.stageSevenLine(shooters, leader.getName()));
    }

    /**
     * Stage 8's lines in a companion run (the stock order of boxes is kept): S8x once when the leader of a six-member
     * run stands on a box, then S8b for the first formation standing and S8n for each later one, keyed by attempt so
     * each formation is told once. The judgement of a check is never read from here (stageEightCheckMissed).
     */
    private static void companionFormationLines(AgentLpqSession session, Character leader,
                                                List<AgentLpqMemberState> participants, boolean positioned) {
        boolean leaderParticipates = participants.stream()
                .anyMatch(member -> member.characterId() == leader.getId());
        if (leaderParticipates) tellTheLeaderHisBox(session, leader);
        boolean leaderOnBox = !leaderParticipates && boxOf(leader, leader.getMap()) != 0;
        if (leaderOnBox) {
            coordinate(session, narrator(session, leader), "s8-off",
                    AgentLpqCompanionPlay.offTheBoxesLine(leader.getName()));
        }
        if (!positioned || leaderOnBox) return;
        int attempt = session.stage8Attempt();
        coordinate(session, narrator(session, leader), "s8-next-" + (attempt + 1),
                AgentLpqCompanionPlay.formationLine(attempt, leaderParticipates));
    }

    /**
     * The box (1-9) a character stands on, as 2040043.js reads it: the first of {@code map}'s areas (the stage-8 map
     * the leader checks from) containing its position, or 0.
     */
    private static int boxOf(Character character, MapleMap map) {
        if (map == null || character == null || character.getPosition() == null) return 0;
        List<Rectangle> areas = map.getAreas();
        for (int index = 0; index < areas.size(); index++) {
            if (areas.get(index).contains(character.getPosition())) return index + 1;
        }
        return 0;
    }

    /**
     * A companion run's wrong Blue Balloon check (2040043.js told the leader so). The boxes the event's players stand on
     * now are read as the script read them; when they are this attempt's formation the attempt was judged and the next
     * one comes (stock). Otherwise someone was still moving: the attempt is not skipped (advancing would lose a
     * combination for good), it is logged, and a companion says S8w once per attempt. Called from the NPC script
     * thread, holding no event or map lock; the session monitor nests the reads as the coordinator does.
     */
    static void stageEightCheckMissed(AgentLpqSession session, Character leader, long nowMs) {
        synchronized (session) {
            List<Integer> expected = session.stage8Combination().stream().sorted().toList();
            EventInstanceManager event = session.eventInstance();
            List<Integer> occupied = new ArrayList<>();
            if (event != null) {
                for (Character player : event.getPlayers()) {
                    int box = boxOf(player, leader.getMap());
                    if (box != 0 && !occupied.contains(box)) occupied.add(box);
                }
            }
            occupied.sort(Comparator.naturalOrder());
            if (occupied.equals(expected)) {
                session.pace().markJudgedCheck(nowMs);
                session.advanceStage8(nowMs);
                return;
            }
            int attempt = session.stage8Attempt() + 1;
            log.info("LPQ stage8 check ignored: session={} attempt={} reason=formation-mismatch occupied={} expected={}",
                    session.sessionId(), attempt, occupied, expected);
            session.markProgress(nowMs);
            coordinate(session, narrator(session, leader), "s8-wait-" + attempt, AgentLpqCompanionPlay.STILL_MOVING);
        }
    }

    /** The key carrier still has the key's hand-over in hand (it holds the key, or the key lies to be fetched). */
    private static boolean keyCarrierBusy(AgentLpqSession session, Character carrier) {
        if (carrier.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false) > 0) return true;
        MapleMap map = carrier.getMap();
        return map != null && map.getDroppedItems().stream().anyMatch(drop -> drop != null && !drop.isPickedUp()
                && drop.getItemId() == AgentLpqDefinition.BOSS_KEY && !drop.isPlayerDrop());
    }

    /**
     * Alishar's key in a companion run: the standing companion nearest to the dropped key becomes its carrier, loots
     * it (the loot rule lets only it) and brings it to the leader's feet with the pass hand-over (K1, K1p, K1b), with a
     * nudge (K2), a re-collect before it vanishes (K3), and a reminder once he has held it LEADER_NUDGE_MS (K4).
     */
    private static void companionBossKey(AgentLpqSession session, Character leader, long nowMs) {
        MapleMap map = stageNineMap(session, leader);
        if (map == null) return;
        List<MapItem> keys = naturalKeys(map);
        List<MapItem> lying = lyingRunStacks(session, Set.of(map), AgentLpqDefinition.BOSS_KEY);
        followRecollect(session, lying, nowMs);
        AgentLpqMemberState member = session.member(session.keyCarrierId());
        Character carrier = member == null ? null : standingCompanion(member);
        AgentRuntimeEntry entry = carrier == null ? null : entry(carrier.getId());
        boolean recollecting = session.recollectStackObjectId() != 0 && session.recollectorId() == session.keyCarrierId();
        if (carrier != null && entry != null && !recollecting) {
            int held = carrier.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false);
            if (held > 0 && aboutToGoDown(carrier)) {
                // Its death would take the key out of the event with it (LudiPQ's event exclusives).
                dropHandOver(session, member, carrier, entry, leader, AgentLpqDefinition.BOSS_KEY, held, 9,
                        AgentLpqCompanionPlay.PassPlace.DYING, lying, nowMs);
            } else if (held > 0 && carrier.getMap() == leader.getMap()) {
                if (member.handOverStartedAtMs() == 0L) member.startHandOver(nowMs);
                handOverItem(session, member, carrier, entry, leader, AgentLpqDefinition.BOSS_KEY, held, 9, lying,
                        nowMs);
            } else if (held <= 0 && !keys.isEmpty()) {
                collectNearestDrop(entry, carrier, Set.of(AgentLpqDefinition.BOSS_KEY), new LinkedHashSet<>());
            }
        }
        for (MapItem stack : lying) {
            if (AgentLpqCompanionPlay.nudgeDue(stack.getDropTime(), nowMs, PASS_PICKUP_NUDGE_MS)) {
                coordinate(session, narrator(session, leader), "s9-key-nudge",
                        AgentLpqCompanionPlay.keyStillLyingLine(leader.getName()));
            }
        }
        beginRecollect(session, lying, nowMs);
        boolean holds = leader.getItemQuantity(AgentLpqDefinition.BOSS_KEY, false) > 0;
        if (holds || lying.stream().anyMatch(stack -> leader.getMap() != null
                && leader.getMap().getDroppedItems().contains(stack)
                && near(stack.getPosition(), leader.getPosition(), PASS_HANDOVER_NEAR_PX))) {
            session.pace().markKeyAtFeet(nowMs);
        }
        if (session.observeLeaderKey(holds, nowMs) >= LEADER_NUDGE_MS) {
            coordinate(session, narrator(session, leader), "s9-key-held",
                    AgentLpqCompanionPlay.keyHeldLine(leader.getName()));
        }
    }

    private static MapleMap stageNineMap(AgentLpqSession session, Character leader) {
        return session.eventInstance() == null ? leader.getMap()
                : session.eventInstance().getMapInstance(AgentLpqDefinition.stage(9).mapId());
    }

    /** Alishar's key as he dropped it (never a hand-over stack). */
    private static List<MapItem> naturalKeys(MapleMap map) {
        return map.getDroppedItems().stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && !drop.isPlayerDrop()
                        && drop.getItemId() == AgentLpqDefinition.BOSS_KEY).toList();
    }

    /** Once Alishar's key lies on stage 9, the standing companion nearest to it becomes its carrier (before any loot). */
    private static void selectKeyCarrier(AgentLpqSession session, Character leader) {
        int stageNine = AgentLpqDefinition.stage(9).mapId();
        MapleMap map = stageNineMap(session, leader);
        List<MapItem> keys = map == null ? List.of() : naturalKeys(map);
        if (session.keyCarrierId() == 0 && !keys.isEmpty()) {
            MapItem key = keys.getFirst();
            Character nearest = null;
            for (AgentLpqMemberState member : session.members()) {
                Character agent = standingCompanion(member);
                if (agent == null || agent.getMapId() != stageNine || agent.getPosition() == null) continue;
                if (nearest == null || agent.getPosition().distanceSq(key.getPosition())
                        < nearest.getPosition().distanceSq(key.getPosition())) {
                    nearest = agent;
                }
            }
            if (nearest != null) {
                session.setKeyCarrierId(nearest.getId());
                log.info("LPQ key carrier: session={} member={}({}) key={}", session.sessionId(), nearest.getName(),
                        nearest.getId(), key.getPosition());
            }
        }
    }

    /**
     * A companion run's stage-5 room that runs long: its owner re-plans its route (a fresh route to the next box or
     * pass) every STAGE_5_ROOM_CEILING_MS of the room's time, and gives an ordinary room (502-505) up at
     * STAGE_5_ROOM_GIVE_UP_MS while a box or a pass is still in it: the room is done as it is, the owner walks out with
     * what it holds and, back on the main map, tells the leader what is left there (H6b/H6p). Rooms 501 (Teleport) and
     * 506 (Dark Sight) never give up, since a leader without the skill could not fetch what is left. True once it gave up.
     */
    private static boolean companionRoomCeiling(AgentLpqSession session, AgentLpqMemberState member,
                                                AgentRuntimeEntry entry, Character agent, int mainMapId,
                                                boolean passLeft, long nowMs) {
        int room = agent.getMapId();
        long elapsedMs = member.roomTimingElapsed(room, nowMs);
        int quota = AgentLpqDefinition.roomPassQuota(room);
        int passes = member.roomPassesCollectedFor(room, agent.getItemQuantity(AgentLpqDefinition.PASS, false));
        if (stageFiveOrdinaryRoom(room) && elapsedMs >= STAGE_5_ROOM_GIVE_UP_MS) {
            log.info("LPQ stage5 room retry: session={} room={} member={}({}) step=GIVE_UP elapsedMs={} passes={}/{}",
                    session.sessionId(), room, agent.getName(), agent.getId(), elapsedMs, passes, quota);
            member.finishRoomTiming(room, nowMs);
            member.recordStageFiveEarnedPasses(passes);
            session.rooms().complete(room);
            member.beginRoomExit(room, nowMs);
            member.markRoomGivenUp(room, passLeft);
            member.clearReactorWork();
            session.markProgress(nowMs);
            tickStageFiveCompletedRoomExit(session, member, entry, agent, nowMs);
            return true;
        }
        int due = (int) Math.min(Integer.MAX_VALUE, elapsedMs / Math.max(1L, STAGE_5_ROOM_CEILING_MS));
        if (due > member.roomRetries(room)) {
            member.markRoomRetries(room, due);
            ACTIONS.refreshNavigation(entry, agent);
            member.clearReactorWork();
            log.info("LPQ stage5 room retry: session={} room={} member={}({}) step=RETRY elapsedMs={} passes={}/{}",
                    session.sessionId(), room, agent.getName(), agent.getId(), elapsedMs, passes, quota);
        }
        return false;
    }

    /** H6b/H6p: a member that gave its room up tells the leader once it stands on the stage's main map. */
    private static void tellGivenUpRoom(AgentLpqSession session, AgentLpqMemberState member, Character agent,
                                        Character leader, int mainMapId) {
        int room = member.givenUpRoom();
        if (room == 0 || agent.getMapId() != mainMapId) return;
        coordinate(session, agent, "s5-room-" + room,
                AgentLpqCompanionPlay.roomGivenUpLine(leader.getName(), room, member.givenUpPassLeft()));
        member.clearRoomGivenUp();
    }

    /**
     * Walks a carrier's stack (passes or Alishar's key) to the leader's grounded spot and drops it there in one drop,
     * where the map's own placement puts it at his feet or right beside him; a leader it has no route to, or a walk
     * longer than PASS_HANDOVER_TIMEOUT_MS, has it take the stack to the stage's balloon instead.
     */
    private static void handOverItem(AgentLpqSession session, AgentLpqMemberState member, Character agent,
                                     AgentRuntimeEntry entry, Character leader, int itemId, int held, int stage,
                                     List<MapItem> lying, long nowMs) {
        boolean onGround = ACTIONS.footing(agent) == PrimitiveCapabilityGateway.Footing.GROUND;
        if (!member.handOverToBalloon()) {
            Point spot = groundSpot(leader);
            boolean leaderOnGround = ACTIONS.footing(leader) == PrimitiveCapabilityGateway.Footing.GROUND;
            boolean reachable = spot != null && (!onGround || ACTIONS.canReach(entry, agent, spot));
            Point landing = AgentLpqCompanionPlay.atFeet(agent.getPosition(), spot, onGround, leaderOnGround,
                    PASS_HANDOVER_REACH_PX) ? ACTIONS.predictDropLanding(agent, agent.getPosition()) : null;
            switch (AgentLpqCompanionPlay.handOver(agent.getPosition(), spot, onGround, leaderOnGround, landing,
                    reachable, nowMs - member.handOverStartedAtMs(), PASS_HANDOVER_TIMEOUT_MS,
                    PASS_HANDOVER_REACH_PX)) {
                case WALK -> {
                    AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
                    ACTIONS.navigate(entry, spot, true);
                    return;
                }
                case DROP_AT_FEET, DROP_BESIDE -> {
                    dropHandOver(session, member, agent, entry, leader, itemId, held, stage,
                            AgentLpqCompanionPlay.PassPlace.of(landing == null ? agent.getPosition() : landing, spot,
                                    PASS_HANDOVER_REACH_PX), lying, nowMs);
                    return;
                }
                default -> member.sendHandOverToBalloon();
            }
        }
        Point balloon = balloonSpot(agent, stage);
        if (balloon == null) return;
        if (AgentLpqCompanionPlay.atFeet(agent.getPosition(), balloon, onGround, true, PASS_HANDOVER_REACH_PX)) {
            dropHandOver(session, member, agent, entry, leader, itemId, held, stage,
                    AgentLpqCompanionPlay.PassPlace.BALLOON, lying, nowMs);
            return;
        }
        AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
        ACTIONS.navigate(entry, balloon, true);
    }

    /**
     * Drops the whole stack in one drop and says where it lies: by the balloon (H1b), about to go down (H5), picked
     * back up and dropped again (H3), or at his feet, where only the drop that makes the stage's count there tells him
     * to talk to the balloon (H1; H1p while it still has to explain itself), and the run's first batch that does not
     * explains the hand-overs once (H0).
     */
    private static void dropHandOver(AgentLpqSession session, AgentLpqMemberState member, Character agent,
                                     AgentRuntimeEntry entry, Character leader, int itemId, int held, int stage,
                                     AgentLpqCompanionPlay.PassPlace place, List<MapItem> lying, long nowMs) {
        ACTIONS.stop(entry);
        // What already lies at his feet, read before this drop lands there.
        int atHisFeetBefore = itemId == AgentLpqDefinition.PASS ? passesAtHisFeet(session, leader) : 0;
        if (!AgentScriptItemActionService.dropItem(entry, InventoryType.ETC, itemId,
                (short) Math.min(Short.MAX_VALUE, held))) {
            return;
        }
        if (stage == 5 && itemId == AgentLpqDefinition.PASS) member.handOffStageFivePasses(held);
        int recollected = member.handOverRecollectStack();
        member.endHandOver();
        session.markProgress(nowMs);
        inhibitLoot(session);
        log.info("LPQ hand-over: session={} member={}({}) stage={} item={} quantity={} place={} recollect={} "
                        + "leader={} at={}", session.sessionId(), agent.getName(), agent.getId(), stage, itemId, held,
                place, recollected != 0, leader.getId(), agent.getPosition());
        String name = leader.getName();
        if (itemId == AgentLpqDefinition.BOSS_KEY) {
            if (place == AgentLpqCompanionPlay.PassPlace.DYING) {
                coordinate(session, agent, "s9-key-dying", AgentLpqCompanionPlay.LEAVING_THE_KEY);
            } else if (place == AgentLpqCompanionPlay.PassPlace.BALLOON) {
                coordinate(session, agent, "s9-key-balloon", AgentLpqCompanionPlay.keyByTheBalloonLine(name));
            } else if (recollected != 0) {
                coordinate(session, agent, "s9-key-recollect", AgentLpqCompanionPlay.keyRecollectedLine(name));
            } else {
                coordinate(session, agent, "s9-key", AgentLpqCompanionPlay.keyLine(name, place,
                        "-1".equals(LPQ.property(leader, "statusStg9"))));
            }
            return;
        }
        if (itemId != AgentLpqDefinition.PASS) return;
        switch (place) {
            case DYING -> coordinate(session, agent, "dying-" + agent.getId(), AgentLpqCompanionPlay.LEAVING_PASSES);
            case BALLOON -> coordinate(session, agent, "s" + stage + "-balloon",
                    AgentLpqCompanionPlay.passesByTheBalloonLine(name, stage));
            default -> {
                if (recollected != 0) {
                    coordinate(session, agent, "recollect-" + recollected,
                            AgentLpqCompanionPlay.passesRecollectedLine(name));
                    return;
                }
                if (atHisFeetBefore + held >= AgentLpqDefinition.stage(stage).submissionCount()) {
                    coordinate(session, agent, "s" + stage + "-passes", AgentLpqCompanionPlay.passesLine(name, stage,
                            place, "-1".equals(LPQ.property(leader, "statusStg" + stage))));
                } else {
                    coordinate(session, agent, "batch-explained", AgentLpqCompanionPlay.BATCH_EXPLAINED);
                }
            }
        }
    }

    /**
     * The passes the leader holds plus the run's hand-over stacks lying within PASS_HANDOVER_NEAR_PX of him on his map,
     * read live (a stack dropped earlier this tick already lies there).
     */
    private static int passesAtHisFeet(AgentLpqSession session, Character leader) {
        int lying = leader.getMap() == null ? 0
                : lyingRunStacks(session, Set.of(leader.getMap()), AgentLpqDefinition.PASS).stream()
                .filter(stack -> near(stack.getPosition(), leader.getPosition(), PASS_HANDOVER_NEAR_PX))
                .mapToInt(AgentLpqCoordinator::stackSize).sum();
        return leader.getItemQuantity(AgentLpqDefinition.PASS, false) + lying;
    }

    /**
     * H2: a reminder, by a companion on his map, once a hand-over stack he left lying has lain PASS_PICKUP_NUDGE_MS:
     * once a stage for stacks by his feet and once for stacks back where he was, however many stacks lie there.
     */
    private static void nudgeLyingStacks(AgentLpqSession session, int stage, Character leader, List<MapItem> lying,
                                         long nowMs) {
        for (MapItem stack : lying) {
            if (stack.getItemId() != AgentLpqDefinition.PASS
                    || !AgentLpqCompanionPlay.nudgeDue(stack.getDropTime(), nowMs, PASS_PICKUP_NUDGE_MS)) continue;
            boolean onHisMap = leader.getMap() != null && leader.getMap().getDroppedItems().contains(stack);
            if (!onHisMap) continue;
            boolean byHisFeet = near(stack.getPosition(), leader.getPosition(), PASS_HANDOVER_NEAR_PX);
            coordinate(session, narrator(session, leader), "s" + stage + (byHisFeet ? "-nudge" : "-nudge-back"),
                    AgentLpqCompanionPlay.passesStillLyingLine(leader.getName(), byHisFeet));
        }
    }

    /**
     * Picks the oldest hand-over stack lying past the re-collect time back up (at most PASS_RECOLLECT_MAX a stage):
     * its dropper if it stands on that map, else the nearest standing companion there.
     */
    private static void beginRecollect(AgentLpqSession session, List<MapItem> lying, long nowMs) {
        if (session.recollectStackObjectId() != 0) return;
        MapItem stack = lying.stream()
                .filter(drop -> AgentLpqCompanionPlay.recollectDue(drop.getDropTime(), nowMs, PASS_RECOLLECT_MS,
                        session.recollects(), PASS_RECOLLECT_MAX))
                .min(Comparator.comparingLong(MapItem::getDropTime)).orElse(null);
        if (stack == null) return;
        Character recollector = null;
        AgentLpqMemberState dropper = session.member(stack.getOwnerId());
        Character owner = dropper == null ? null : standingCompanion(dropper);
        if (owner != null && owner.getMap() != null && owner.getMap().getDroppedItems().contains(stack)) {
            recollector = owner;
        } else {
            double best = Double.MAX_VALUE;
            for (AgentLpqMemberState member : session.members()) {
                Character agent = standingCompanion(member);
                if (agent == null || agent.getMap() == null || !agent.getMap().getDroppedItems().contains(stack)) {
                    continue;
                }
                double distance = agent.getPosition().distanceSq(stack.getPosition());
                if (distance < best) {
                    best = distance;
                    recollector = agent;
                }
            }
        }
        if (recollector == null) return;
        session.beginRecollect(recollector.getId(), stack.getObjectId(), stack.getItemId(),
                recollector.getItemQuantity(stack.getItemId(), false));
        log.info("LPQ recollect: session={} member={}({}) stage={} stack={} quantity={} lyingMs={}",
                session.sessionId(), recollector.getName(), recollector.getId(), stageNumberOrZero(session.phase()),
                stack.getObjectId(), stackSize(stack), nowMs - stack.getDropTime());
    }

    /**
     * The recollector walks to its stack and picks it up; once the stack is gone, a recollector holding more of the
     * stack's item than before picked it up and hands it over at once (its drop says H3, or K3 for the key). A
     * companion that picked Alishar's key back up carries it from then on, whoever carried it before.
     */
    private static void followRecollect(AgentLpqSession session, List<MapItem> lying, long nowMs) {
        int stackId = session.recollectStackObjectId();
        if (stackId == 0) return;
        Character recollector = character(session.recollectorId());
        AgentLpqMemberState member = session.member(session.recollectorId());
        MapItem stack = lying.stream().filter(drop -> drop.getObjectId() == stackId).findFirst().orElse(null);
        if (stack == null || recollector == null || member == null || recollector.getHp() <= 0) {
            int itemId = session.recollectItemId();
            boolean pickedUp = recollector != null
                    && recollector.getItemQuantity(itemId, false) > session.recollectHeldBefore();
            session.endRecollect(pickedUp);
            if (pickedUp && member != null) {
                member.markHandOverRecollect(stackId);
                if (itemId == AgentLpqDefinition.BOSS_KEY && session.keyCarrierId() != recollector.getId()) {
                    session.setKeyCarrierId(recollector.getId());
                    log.info("LPQ key carrier: session={} member={}({}) key=recollected", session.sessionId(),
                            recollector.getName(), recollector.getId());
                }
            }
            return;
        }
        AgentRuntimeEntry entry = entry(recollector.getId());
        if (entry != null) collectDrop(entry, recollector, stack, null);
    }

    /** Stacks the run's companions dropped (hand-overs, or left before going down) still lying on these maps. */
    private static List<MapItem> lyingRunStacks(AgentLpqSession session, Set<MapleMap> maps, int itemId) {
        List<MapItem> lying = new ArrayList<>();
        for (MapleMap map : maps) {
            for (MapItem drop : map.getDroppedItems()) {
                if (drop != null && drop.isPlayerDrop() && !drop.isPickedUp() && drop.getItemId() == itemId
                        && session.companionOfRun(drop.getOwnerId())) {
                    lying.add(drop);
                }
            }
        }
        return lying;
    }

    private static int stackQuantity(List<MapItem> stacks) {
        return stacks.stream().mapToInt(AgentLpqCoordinator::stackSize).sum();
    }

    /** The items in one stack on the floor (an unreadable stack is one). */
    private static int stackSize(MapItem drop) {
        return drop.getItem() == null ? 1 : Math.max(1, drop.getItem().getQuantity());
    }

    /** A loaded companion of the run, online and standing (up, and not marked down); null otherwise. */
    private static Character standingCompanion(AgentLpqMemberState member) {
        if (member.memberType() != AgentLpqMemberState.MemberType.AGENT || member.down()) return null;
        Character agent = character(member.characterId());
        return agent != null && agent.getHp() > 0 ? agent : null;
    }

    /** About to go down: low on HP with no HP potion left. The HP is read first, since the count reads its bag. */
    private static boolean aboutToGoDown(Character agent) {
        int hp = agent.getHp();
        int maxHp = agent.getCurrentMaxHp();
        return AgentLpqCompanionPlay.lowOnHp(hp, maxHp, PASS_DYING_HP_PERCENT)
                && AgentLpqCompanionPlay.dying(hp, maxHp, ACTIONS.hpPotionCount(agent), PASS_DYING_HP_PERCENT);
    }

    /** Nobody of the run picks up for PASS_HANDOVER_LOOT_INHIBIT_MS after a hand-over: the stack is the leader's. */
    private static void inhibitLoot(AgentLpqSession session) {
        int inhibitMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, PASS_HANDOVER_LOOT_INHIBIT_MS));
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT) continue;
            AgentRuntimeEntry entry = entry(member.characterId());
            if (entry != null) {
                AgentInventoryStateRuntime.setLootInhibitMs(entry,
                        Math.max(AgentInventoryStateRuntime.lootInhibitMs(entry), inhibitMs));
            }
        }
    }

    /** Where a character stands, on its foothold. */
    private static Point groundSpot(Character character) {
        Point position = character.getPosition();
        if (position == null) return null;
        Point grounded = ACTIONS.groundPoint(character.getMap(), position);
        return grounded == null ? new Point(position) : grounded;
    }

    /** The stage's balloon, grounded, where a stack goes when the leader cannot be reached. */
    private static Point balloonSpot(Character agent, int stage) {
        Point balloon = ACTIONS.npcPosition(agent, AgentLpqDefinition.stage(stage).npcId());
        if (balloon == null) return null;
        Point grounded = ACTIONS.groundPoint(agent.getMap(), balloon);
        return grounded == null ? new Point(balloon) : grounded;
    }

    /**
     * A companion run's loot rule for its exclusive drops (AgentLpqSessionRegistry.canLootExclusive with the drop):
     * a hand-over stack lying for the leader is never picked up again except on its re-collect, and the key only by its
     * carrier. Every other drop, and every drop of a stock run, passes.
     */
    private static boolean mayLootInRun(Character agent, MapItem drop) {
        int itemId = drop.getItemId();
        if (itemId != AgentLpqDefinition.PASS && itemId != AgentLpqDefinition.BOSS_KEY) return true;
        AgentLpqSession run = AgentLpqSessionRegistry.forMember(agent.getId());
        if (run == null || !run.companionAdopted()) return true;
        return AgentLpqSessionRegistry.canLootExclusive(agent, drop);
    }

    /** PASS_RECOLLECT_MS, clamped (with one WARN) below the drops' expiry margin on this server. */
    private static long passRecollectAfterMs(long configuredMs) {
        config.ServerConfig server = config.YamlConfig.config == null ? null : config.YamlConfig.config.server;
        long latest = server == null || server.ITEM_EXPIRE_TIME <= 0 ? AgentLpqDefinition.DROP_RECOLLECT_LATEST_MS
                : AgentLpqDefinition.dropRecollectLatestMs(server.ITEM_EXPIRE_TIME, server.ITEM_EXPIRE_CHECK);
        long clamped = AgentLpqCompanionPlay.recollectAfterMs(configuredMs, latest);
        if (clamped != configuredMs) {
            log.warn("LPQ PASS_RECOLLECT_MS {} would let a stack expire before it is picked back up; using {}",
                    configuredMs, clamped);
        }
        return clamped;
    }

    /**
     * A companion run's room exit, as a player leaves a room: within reach of the exit portal it presses up there
     * (with the stock success bookkeeping); farther it walks the authored route, re-planned once per traversal
     * window when it stalls. Never a teleport. True once it stands on the destination.
     */
    private static boolean walkRoomExit(AgentLpqSession session, AgentLpqMemberState member,
                                        AgentRuntimeEntry entry, Character agent, int portalId, Point portal,
                                        int destinationMapId, long nowMs) {
        int sourceMapId = agent.getMapId();
        if (near(agent.getPosition(), portal, INTERACTION_RADIUS)) {
            ACTIONS.stop(entry);
            if (!LPQ.enterPortal(agent, portalId)) return false;
            session.rooms().release(sourceMapId);
            member.assign(AgentLpqMemberState.Role.GENERAL, 0);
            member.clearTraversalProgress();
            session.markProgress(nowMs);
            log.info("LPQ room exit walked: session={} member={}({}) source={} destination={}",
                    session.sessionId(), agent.getName(), agent.getId(), sourceMapId, destinationMapId);
            return true;
        }
        long inactiveForMs = traversalInactivity(member, agent, destinationMapId, nowMs);
        enterPortalTo(entry, agent, destinationMapId);
        int stage = AgentLpqDefinition.stageNumber(sourceMapId);
        if (stage >= 1 && stage <= 9 && inactiveForMs >= recovery(stage).traversalMs()) {
            ACTIONS.refreshNavigation(entry, agent);
            member.clearTraversalProgress();
        }
        return agent.getMapId() == destinationMapId;
    }

    /** The member stands in a companion run (sites that have no session in hand). */
    private static boolean inCompanionRun(Character agent) {
        AgentLpqSession run = agent == null ? null : AgentLpqSessionRegistry.forMember(agent.getId());
        return run != null && run.companionAdopted();
    }

    /** A stage's own start line (S1, S3, S4, S5, S9), once, said on the leader's map. */
    private static void companionStageLine(AgentLpqSession session, Character leader) {
        int stage = stageNumberOrZero(session.phase());
        if (stage == 0 || leader.getMapId() != AgentLpqDefinition.stage(stage).mapId()) return;
        coordinate(session, narrator(session, leader), "s" + stage + "-start", stage == 9
                ? AgentLpqCompanionPlay.stageNineLine(leader.getName())
                : AgentLpqCompanionPlay.stageStartLine(stage, leader.getName()));
    }

    /**
     * A companion run hears its companions talk like players (a null companion line says nothing), never a status
     * line; a stock or test run keeps the plain lines it always had.
     */
    static String voice(AgentLpqSession session, String companionLine, String stockLine) {
        return session != null && session.companionAdopted() ? companionLine : stockLine;
    }

    /**
     * True when the line was said now; each key is said once a run, and a line nobody can say keeps its key. A line
     * too long for one chat line is said as its parts, by the same speaker, one after the other.
     */
    private static boolean narrate(AgentLpqSession session, Character speaker, String key, String line) {
        if (speaker == null || line == null || !session.narrateOnce(key)) return false;
        for (String part : AgentLpqCompanionPlay.chatLines(line)) {
            AgentLpqDialogue.sayMapNow(session.sessionId(), key, speaker, part);
        }
        return true;
    }

    /** A companion run's coordination line, while its host has the members' coordination chat on. */
    private static boolean coordinate(AgentLpqSession session, Character speaker, String key, String line) {
        if (!session.companionAdopted() || !session.memberCoordinationChatEnabled()) return false;
        return narrate(session, speaker, key, line);
    }

    /**
     * Who says a line the leader must hear: the first companion of the run standing on his own map instance; null
     * when none is (the line then waits, so he always hears it).
     */
    private static Character narrator(AgentLpqSession session, Character leader) {
        if (leader == null || leader.getMap() == null) return null;
        for (AgentLpqMemberState member : session.members()) {
            if (member.memberType() != AgentLpqMemberState.MemberType.AGENT || member.down()) continue;
            Character agent = character(member.characterId());
            if (agent != null && agent.getHp() > 0 && agent.getMap() == leader.getMap()) return agent;
        }
        return null;
    }

    private static int stage(AgentLpqSession.Phase phase) {
        int stage = stageNumberOrZero(phase);
        if (stage == 0) throw new IllegalArgumentException("LPQ phase is not a numbered stage: " + phase);
        return stage;
    }

    static int stageNumberOrZero(AgentLpqSession.Phase phase) {
        return switch (phase) {
            case STAGE_1 -> 1;
            case STAGE_2 -> 2;
            case STAGE_3 -> 3;
            case STAGE_4 -> 4;
            case STAGE_5 -> 5;
            case STAGE_6 -> 6;
            case STAGE_7 -> 7;
            case STAGE_8 -> 8;
            case STAGE_9 -> 9;
            default -> 0;
        };
    }
    private static boolean near(Point a, Point b, int radius) {
        return a != null && b != null && Math.abs(a.x - b.x) <= radius && Math.abs(a.y - b.y) <= radius;
    }
    private static boolean isAgent(Character character) { return character != null && entry(character.getId()) != null; }
    private static AgentRuntimeEntry entry(int id) { return AgentRuntimeRegistry.findByAgentCharacterId(id); }
    private static Character character(int id) {
        AgentRuntimeEntry entry = entry(id);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }
}
