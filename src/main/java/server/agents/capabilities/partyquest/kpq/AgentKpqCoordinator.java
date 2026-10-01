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
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootTargetService;
import server.agents.capabilities.looting.AgentLootCollectionPolicyConfig;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.catalog.AgentMapRegionAssignment;
import server.agents.field.AgentFieldObservationState;
import server.agents.operations.events.AgentOperationalEventProjectionState;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import scripting.event.EventInstanceManager;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
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

    private AgentKpqCoordinator() {
    }

    static void tick(AgentKpqSession session, long nowMs) {
        synchronized (session) {
            tickSession(session, nowMs);
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
                    if (entry != null) member.beginStage1Tally(combatCounts(entry, nowMs), nowMs);
                }
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
        session.members().forEach(member -> member.tallyStage1Role(nowMs));
        if ("true".equals(KPQ.property(leader, "1stageclear"))) {
            if (session.claimStageOneSummary()) reportStageOne(session, nowMs);
            clearStageOneLandingSafety(session, leader);
            clearCouponLootObjectives(session);
            // A territory now outlives the grind call, and every run's stage 1 is this map: it ends with the stage.
            session.members().forEach(member -> ACTIONS.leaveTerritory(entry(member.characterId())));
            enterNextPortal(session, 2, nowMs);
            return;
        }
        boolean allDelivered = true;
        AgentRuntimeEntry leaderEntry = entry(leader.getId());
        Point cloto = ACTIONS.npcPosition(leader, AgentKpqDefinition.CLOTO_NPC);
        int needed = stageOnePassesNeeded(session.companionAdopted(), session.memberCount(), livePartySize(leader));
        boolean agentLeader = isAgent(session, leader.getId());
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
                collection.sweepActive() ? floorCoupons : List.of(), collection.repick());
        // A companion run's helpers hold the collector's tower; a stock run's fight from a spot under it.
        AgentKpqSession.HelperTerritory helperTerritory =
                session.companionAdopted() ? stageOneHelperTerritory(session, leader, nowMs) : null;
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
                && leader.getPosition().distance(cloto) > STAGE1_LEADER_FAR_FROM_CLOTO_PX) {
            narrate(session, narrator != null ? narrator : firstStandingAgent(session), "stage1-come-down",
                    leader.getName() + ", come on down by cloto, we're almost done.");
        }
        // A companion run says it even with coordination chat off: the passes expire where they lie.
        boolean passesAtFeetLine = !agentLeader
                && (session.companionAdopted() || memberCoordinationVisible(session));
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
                    if (KPQ.playerGrid(agent) == 0) member.markPassCreated(nowMs);
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
                    grindStageOne(member, entry, agent, null, null, nowMs);
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
                    member.markQuestionRequested();
                    member.setCouponTarget(target);
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
                        member.markPassCreated(nowMs);
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
                    grindStageOne(member, entry, agent, null, null, nowMs);
                }
            }
            if (member.passCreated() && !member.passDelivered()) {
                allDelivered = false;
                if (holdPasses) {
                    grindStageOne(member, entry, agent, helperAnchor, helperTerritory, nowMs);
                    continue;
                }
                cancelStageOneLandingSafety(member, entry, agent);
                if (near(agent, leader, NEAR_PX)) {
                    ACTIONS.stop(entry);
                    int passesBeforeDrop = agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
                    boolean dropRequested = AgentScriptItemActionService.dropItem(
                            entry, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) 1);
                    int passesAfterDrop = agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
                    if (dropRequested && passDropConfirmed(passesBeforeDrop, passesAfterDrop)) {
                        member.markPassDelivered();
                        member.setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
                        session.markProgress(nowMs);
                        // The leader gets the one pickup line below instead of a line per companion.
                        if (!agentLeader && !passesAtFeetLine) {
                            narrate(session, agent, "pass-dropped-" + agent.getId(),
                                    stageOnePassDropMessage(leader.getName()));
                        }
                    }
                } else {
                    ACTIONS.navigate(entry, leader.getPosition(), false);
                }
            } else if (member.passDelivered()) {
                if (knownCouponNeedsCovered) {
                    cancelStageOneLandingSafety(member, entry, agent);
                    moveTowardStageOneExit(session, member, agent, entry);
                }
                else grindStageOne(member, entry, agent, helperAnchor, helperTerritory, nowMs);
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
        reportLeaderPassProgress(session, narrator, leader.getName(), leaderState, leaderPasses, needed);
        int floorPassQuantity = passQuantityOnGround(floorPasses);
        // Only the companions' drops: a second human may keep its pass for minutes while theirs lie there expiring.
        if (passesAtFeetLine && companionPassesDelivered(session) && floorPassQuantity > 0) {
            narrate(session, narrator != null ? narrator : firstStandingAgent(session), "stage1-passes-at-feet",
                    leader.getName() + ", the passes are at your feet. pick them up and talk to Cloto.");
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
        if (!allDelivered || floorPassQuantity > 0) {
            session.setMissingPassSinceMs(0L);
            return;
        }
        if (session.missingPassSinceMs() == 0L) {
            session.setMissingPassSinceMs(nowMs);
            return;
        }
        if (shouldBypassMissingPasses(allDelivered, leaderPasses, needed,
                floorPassQuantity, session.missingPassSinceMs(), nowMs,
                MISSING_PASS_GRACE_MS)) {
            bypassMissingStageOnePasses(
                    session, leader, leaderPasses, needed, "grace-timeout", nowMs);
        }
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
        session.markFormationReady();
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
            if (validation.accepted()) return;
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
        if (!"true".equals(KPQ.property(leader, definition.clearProperty()))) {
            advancePuzzleAttempt(session, leader, definition, order.size(), attempt, nowMs);
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
                leader.getName() + ", come on up by Cloto, the way out's at the top. stay off the barrels.");
    }

    private static void stage5(
            AgentKpqSession session, Character leader, Character narrator, long nowMs) {
        if ("true".equals(KPQ.property(leader, "5stageclear"))) {
            dropCarriedStageFivePasses(session);
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
        observeStageFiveBossCombat(session, normalAlive, bossAlive, nowMs);
        session.beginStage5LootDelayIfBossDefeated(bossAlive, nowMs, KING_SLIME_LOOT_DELAY_MS);
        boolean lootDelayActive = session.stage5LootDelayActive(nowMs);
        if (agentLeader && !lootDelayActive) {
            KPQ.lootNearby(leader, Set.of(AgentKpqDefinition.PASS_ITEM));
        } else if (!agentLeader) {
            // A companion run's companions bring the passes up, so its leader waits by Cloto, at the top, instead of
            // going 2,300 px down for them and back; there is nothing to nudge it about.
            coordinate(session, narrator, "human-stage5-collect", voice(session,
                    leader.getName() + ", wait by cloto, we'll bring the passes.",
                    leader.getName() + ", please collect the 10 passes while we clear the monsters."));
            if (!carriers) {
                AgentKpqHumanDialogueRuntime.promptDelayed(session, leader,
                        "collect the 10 passes while we clear the monsters", nowMs);
            }
        }
        int leaderPasses = leader.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        int stage5MobCount = normalAlive + bossAlive;
        boolean reviveGraceActive = session.stage5ReviveGraceActive(
                bossAlive, nowMs, KING_SLIME_REVIVE_GRACE_MS);
        int floorPasses = passQuantityOnGround(livePassDrops(leader.getMap()));
        if (session.observeStage5Progress(stage5MobCount, leaderPasses)) session.markProgress(nowMs);
        boolean returningToCloto = stageFiveReadyToReturn(
                leaderPasses, normalAlive, bossAlive, reviveGraceActive);
        boolean mobsDown = stage5MobCount == 0 && !reviveGraceActive;
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
            member.setRole(stageFiveRole(session, member, agent, carriers));
            if (member.memberType() == AgentKpqMemberState.MemberType.AGENT && entry != null) {
                if (nowMs < member.stageMovementNotBeforeMs()) {
                    ACTIONS.stop(entry);
                } else if (returningToCloto) {
                    Point cloto = ACTIONS.npcPosition(agent, AgentKpqDefinition.CLOTO_NPC);
                    Point approach = npcApproachPoint(session, member, agent, cloto, 501);
                    walkToPoint(agent, approach, entry, NPC_APPROACH_PX);
                } else if (shoesPending && member.characterId() == session.squishyShoesWinnerId()) {
                    continue;
                } else if (mobsDown && member.role() == AgentKpqMemberState.Role.STAGE5_PASS_CARRIER) {
                    sweeping |= sweepStageFivePasses(session, entry, agent, nowMs);
                } else if (mobsDown && member.role() == AgentKpqMemberState.Role.PASS_DELIVERER) {
                    handOverStageFivePasses(session, member, agent, entry, leader, nowMs);
                } else {
                    grindStageFive(session, entry, agent, nowMs);
                }
            }
        }
        if (carriers && mobsDown && !lootDelayActive && !sweeping) session.startStage5HandOver(nowMs);
        if (reviveGraceActive && stage5MobCount == 0) {
            session.setMissingPassSinceMs(0L);
            return;
        }
        // Passes on their way to the leader are not missing: the recovery would make up all ten 7 s after the last
        // kill while the carriers still held them.
        if (!returningToCloto && stage5MobCount == 0 && floorPasses == 0 && carriedStageFivePasses(session) == 0) {
            if (session.missingPassSinceMs() == 0L) session.setMissingPassSinceMs(nowMs);
            if (nowMs - session.missingPassSinceMs() >= STAGE5_MISSING_PASS_GRACE_MS) {
                recoverMissingStageFivePasses(session, leader, leaderPasses, nowMs);
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
     * dropped them, just fights.
     */
    private static AgentKpqMemberState.Role stageFiveRole(
            AgentKpqSession session, AgentKpqMemberState member, Character agent, boolean carriers) {
        if (member.characterId() == session.eventLeaderId()) return AgentKpqMemberState.Role.STAGE5_PASS_COLLECTOR;
        if (!carriers || member.memberType() != AgentKpqMemberState.MemberType.AGENT) {
            return AgentKpqMemberState.Role.COMBAT_HELPER;
        }
        if (!session.stage5HandOverStarted()) return AgentKpqMemberState.Role.STAGE5_PASS_CARRIER;
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
     * A deliverer walks to the leader and drops what it carries at the leader's feet, as stage 1's holders do. One
     * that cannot get there (a failed route, a leader on a ledge or rope it cannot path to) would hold its passes, and
     * so the missing-pass recovery, back until the phase timeout failed the run; after STAGE5_PASS_HANDOVER_MS it
     * drops them where it stands and says where, so the leader can fetch them or the recovery makes them up.
     */
    private static void handOverStageFivePasses(AgentKpqSession session, AgentKpqMemberState member,
                                                Character agent, AgentRuntimeEntry entry, Character leader,
                                                long nowMs) {
        int held = agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false);
        if (held == 0) {
            member.setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
            return;
        }
        boolean beside = near(agent, leader, NEAR_PX);
        boolean stuck = !beside && session.stage5HandOverOverdue(nowMs, STAGE5_PASS_HANDOVER_MS);
        if (!beside && !stuck) {
            ACTIONS.navigate(entry, leader.getPosition(), false);
            return;
        }
        ACTIONS.stop(entry);
        if (AgentScriptItemActionService.dropItem(entry, InventoryType.ETC, AgentKpqDefinition.PASS_ITEM, (short) held)
                && passDropConfirmed(held, agent.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false))) {
            member.setRole(AgentKpqMemberState.Role.COMBAT_HELPER);
            session.markProgress(nowMs);
            if (stuck) {
                narrate(session, agent, "stage5-passes-dropped-" + agent.getId(),
                        leader.getName() + ", can't get to you, i dropped the passes right here by me.");
            } else if (session.members().stream().noneMatch(
                    other -> other.role() == AgentKpqMemberState.Role.PASS_DELIVERER)) {
                // The last one there says it, once: the passes expire where they lie.
                narrate(session, agent, "stage5-passes-at-feet",
                        leader.getName() + ", the passes are at your feet. grab them and talk to cloto.");
            }
        }
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
    private static void dropCarriedStageFivePasses(AgentKpqSession session) {
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
        enum Kind { GENERIC, WENT_DOWN, LEFT, DEFEATED, GONE, OVER, TOO_LONG, LOBBY_TAKEN }

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
                case OVER, LOBBY_TAKEN -> beforeTheClear(self, named);
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

    private static void narrate(AgentKpqSession session, Character speaker, String key, String message) {
        if (speaker == null || !session.narrateOnce(key)) return;
        // Required KPQ narration is deliberately map-local. Cosmic's party-chat
        // broadcast excludes the sender and anyone observing outside the party,
        // which made successful Stage 1 target/progress reports look silent.
        AgentKpqDialogue.sayMapNow(speaker, message);
    }

    private static void coordinate(
            AgentKpqSession session, Character speaker, String key, String message) {
        if (!memberCoordinationVisible(session)) return;
        narrate(session, speaker, key, message);
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
            log.warn("KPQ member left the live party and was ejected: session={} member={} type={} "
                            + "phase={} remainingBefore={}",
                    session.sessionId(), member.characterId(), member.memberType(),
                    session.phase(), session.memberCount());
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
        session.removeMember(member.characterId());
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
                    if (!member.down()) down++;
                    member.markDown(nowMs);
                    waiting = true;
                }
                case REQUEST_TAP_OUT -> {
                    // The stock respawn carried it out of the event; the host takes it out of the party, and the
                    // departed-member path drops it on a later tick.
                    if (AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut(
                            "kpq", session.sessionId(), member.characterId(), character.getMapId(), nowMs))) {
                        member.markTapOutRequested();
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

    /** With an anchor, the member fights from that spot, and in a territory inside it, instead of map-wide. */
    private static void grindStageOne(AgentKpqMemberState member,
                                      AgentRuntimeEntry entry,
                                      Character agent,
                                      Point anchor,
                                      AgentKpqSession.HelperTerritory territory,
                                      long nowMs) {
        if (AgentKpqStageOneThiefLandingRuntime.tick(member, entry, agent, nowMs)) return;
        Set<Integer> mobs = stage1GrindTargets(agent);
        // The regions of its own graph, where its combat looks up each Ligator's region.
        Set<Integer> regions = territory == null
                ? Set.of() : ACTIONS.groundRegionIds(entry, agent, territory.spawns());
        if (!regions.isEmpty()) {
            ACTIONS.grindInTerritory(entry, mobs, new AgentMapRegionAssignment(
                    territory.assignmentId(), AgentKpqDefinition.STAGE_1_MAP,
                    regions.stream().sorted().map(String::valueOf).toList(), member.partyNumber(),
                    territory.spawns().size(), territory.expiresAtMs(), STAGE1_HELPER_TERRITORY_EMPTY_MS,
                    territory.minX(), territory.maxX(), true));
        } else if (anchor == null) {
            ACTIONS.grind(entry, mobs);
        } else {
            ACTIONS.grindFromAnchor(entry, anchor, mobs, mobs);
        }
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
                session, leader, leaderPasses, needed, "test-complete-command", nowMs);
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
        return true;
    }

    private static void bypassMissingStageOnePasses(
            AgentKpqSession session,
            Character leader,
            int leaderPasses,
            int needed,
            String trigger,
            long nowMs) {
        EventInstanceManager event = KPQ.event(leader);
        if (event == null) {
            log.error("KPQ Stage 1 missing-pass recovery failed: session={} leader={}({}) map={} "
                            + "leaderPasses={}/{} reason=no-event members={}",
                    session.sessionId(), leader.getName(), leader.getId(), leader.getMapId(),
                    leaderPasses, needed, stageOnePassDiagnostics(session, leader));
            return;
        }
        log.warn("KPQ Stage 1 missing-pass recovery: session={} leader={}({}) map={} "
                        + "leaderPasses={}/{} floorPasses=0 trigger={} graceMs={} members={}",
                session.sessionId(), leader.getName(), leader.getId(), leader.getMapId(),
                leaderPasses, needed, trigger, MISSING_PASS_GRACE_MS,
                stageOnePassDiagnostics(session, leader));
        event.setProperty("1stageclear", "true");
        event.showClearEffect(true);
        event.linkToNextStage(1, "kpq", AgentKpqDefinition.STAGE_1_MAP);
        event.gridClear();
        session.setMissingPassSinceMs(0L);
        session.markProgress(nowMs);
    }

    private static void recoverMissingStageFivePasses(
            AgentKpqSession session, Character leader, int leaderPasses, long nowMs) {
        int missing = Math.max(0, 10 - leaderPasses);
        if (missing == 0) {
            session.setMissingPassSinceMs(0L);
            return;
        }
        if (!AgentInventoryGatewayRuntime.inventory().addItem(
                leader, AgentKpqDefinition.PASS_ITEM, (short) missing)) {
            fail(session, "Stage 5 passes disappeared and the leader has no ETC space for recovery", nowMs);
            return;
        }
        log.warn("KPQ Stage 5 missing-pass recovery restored inventory only: session={} "
                        + "leader={}({}) map={} leaderPasses={}/10 restored={} floorPasses=0 "
                        + "mobs=0 graceMs={} members={}",
                session.sessionId(), leader.getName(), leader.getId(), leader.getMapId(),
                leaderPasses, missing, STAGE5_MISSING_PASS_GRACE_MS,
                stageOnePassDiagnostics(session, leader));
        session.setMissingPassSinceMs(0L);
        session.markProgress(nowMs);
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
     * pairs again.
     */
    private static void syncCouponLootObjectives(
            AgentKpqSession session,
            Character eventAnchor,
            List<MapItem> floorCoupons,
            boolean repick) {
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
        for (AgentKpqMemberState member : unassigned) {
            Character collector = memberCharacter(member.characterId(), eventAnchor);
            if (collector == null) continue;
            for (MapItem drop : floorCoupons) {
                if (claimedDropIds.contains(drop.getObjectId())) continue;
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
            AgentKpqSession session, Character eventAnchor, long nowMs) {
        Character followed = followedStageOneCollector(session, eventAnchor);
        if (followed == null) {
            session.setStageOneHelperTerritory(0, null);
            return null;
        }
        AgentKpqSession.HelperTerritory territory = session.stageOneHelperTerritory();
        if (territory == null || nowMs >= territory.expiresAtMs()
                || ACTIONS.grounded(followed) && !territory.covers(followed.getPosition().x)) {
            territory = towerTerritory(session, followed, nowMs);
        }
        session.setStageOneHelperTerritory(followed.getId(), territory);
        return territory;
    }

    /** The collector the helpers follow: the one they follow already while it still collects, else the neediest. */
    private static Character followedStageOneCollector(AgentKpqSession session, Character eventAnchor) {
        Character followed = stageOneHelperCollector(session, eventAnchor, session.stageOneHelperCollectorId());
        if (followed != null) return followed;
        int mostNeeded = 0;
        for (AgentKpqMemberState member : session.members()) {
            Character collector = stageOneHelperCollector(session, eventAnchor, member.characterId());
            if (collector == null) continue;
            int needed = member.couponTarget()
                    - collector.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false);
            if (needed > mostNeeded) {
                mostNeeded = needed;
                followed = collector;
            }
        }
        return followed;
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
            AgentKpqMemberState.CombatCounts combat = member.stage1Combat(combatCounts(entry, nowMs));
            log.info("KPQ Stage 1 summary: session={} agent={} stageMs={} kills={} coupons={} attacks={} hits={} "
                            + "misses={} passAtMs={} roleMs={}",
                    session.sessionId(), agent.getName(), nowMs - session.phaseEnteredAtMs(), combat.kills(),
                    member.stage1CouponsLooted(), combat.attacks(), combat.hitLines(), combat.missLines(),
                    member.stage1PassAtMs() == 0L ? -1L : member.stage1PassAtMs() - session.phaseEnteredAtMs(),
                    new TreeMap<>(member.stage1RoleMs()));
        }
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
