package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.Item;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentAttackTransactionResult;
import server.agents.capabilities.combat.AgentCombatAttackRuntime;
import server.agents.capabilities.combat.AgentCombatConfig;
import server.agents.capabilities.combat.AgentCombatPlanRuntime;
import server.agents.capabilities.combat.AgentCombatSkillConstraintState;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.maps.Portal;
import server.maps.Reactor;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** EPQ stage coordinator. Every action follows the authored field interaction. */
public final class AgentEpqCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentEpqCoordinator.class);
    private static final long ACTION_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCoordinator.ACTION_RETRY_MS");
    private static final long EVENT_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCoordinator.EVENT_TIMEOUT_MS");
    private static final long BOSS_LOOT_SETTLE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCoordinator.BOSS_LOOT_SETTLE_MS");
    private static final long LOBBY_ENTRY_DELAY_MS = 5_000L;
    private static final long LOBBY_CHAT_STAGGER_MS = 650L;
    private static final int ITEM_REACTOR_DROP_RADIUS = 40;
    private static final int LOOT_RADIUS = 75;
    private static final int MAZE_EXIT_APPROACH_RADIUS = 360;
    private static final int STAGE_TWO_BOTTLES_REQUIRED = 4;
    private static final int STAGE_FOUR_MIN_X = -1_760;
    private static final int STAGE_FOUR_MAX_X = 1_490;
    private static final long STAGE_TWO_AGGRO_TIMEOUT_MS = 45_000L;
    private static final long STAGE_TWO_LURE_STALL_MS = 6_000L;
    private static final int STAGE_TWO_LURE_PROGRESS_PX = 16;
    private static final int PORTAL_RADIUS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCoordinator.PORTAL_RADIUS");
    private static final int NPC_RADIUS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCoordinator.NPC_RADIUS");
    private static final int REACTOR_RADIUS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqCoordinator.REACTOR_RADIUS");

    private static final String EVENT_ENDED_OR_EXPIRED = "The EPQ event instance ended or expired";

    private AgentEpqCoordinator() { }

    public static void tickSession(AgentEpqSession session, long nowMs) {
        if (eventTimerExpired(session, nowMs) && !session.terminal()) {
            fail(session, "EPQ event timer expired", nowMs);
            return;
        }
        Character leader = character(session.eventLeaderId());
        if (leader == null) {
            // A companion run whose leader is offline before the party went in waits out the entry wait.
            if (session.companionAdopted() && AgentEpqCompanionRun.leaderOffline(session, nowMs)) return;
            AgentEpqWatchdogRuntime.tick(session, nowMs);
            return;
        }
        if (session.companionAdopted() && AgentEpqCompanionRun.beforeEntry(session, leader, nowMs)) return;
        if (session.eventInstance() == null && leader.getEventInstance() != null
                && AgentEpqDefinition.isEventMap(leader.getMapId())) {
            session.bindEventInstance(leader.getEventInstance());
            session.markEnteredEvent(nowMs);
            session.markProgress(nowMs);
        }
        // EPQ base fix 2: before the clear, a bound event that was disposed, or that no longer holds the leader (the
        // stock event ends when its leader leaves it, or at its floor), ends the run at once; after the clear the
        // leader leaves through the exit and this check is inert. So is it while the run is being released: the
        // release exits every player and disposes the event itself, and fails the run with its own reason.
        EventInstanceManager bound = session.eventInstance();
        if (bound != null && !session.terminal() && !session.terminationStarted()
                && !AgentEpqTerminationService.pastTheClear(bound)
                && (bound.isEventDisposed() || leader.getEventInstance() != bound)) {
            log.warn("EPQ event end detail: session={} {}", session.sessionId(), eventEndDetail(session, leader));
            fail(session, EVENT_ENDED_OR_EXPIRED, nowMs);
            return;
        }
        for (AgentEpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character != null) observeDeparture(session, character, leader);
        }
        boolean rewardReady = AgentEpqTerminationService.pastTheClear(bound) && leader.getEventInstance() == bound;
        AgentEpqSession.Phase observed = phaseForMap(leader.getMapId(), rewardReady);
        if (observed != null) transition(session, observed, nowMs);
        session.observeProgressSignature(progressSignature(session, leader), nowMs);
        AgentEpqWatchdogRuntime.tick(session, nowMs);
        logMembersDown(session);
        logBossProgress(session, leader, nowMs);
        // A member is out once it is off the EPQ maps or no longer in the bound event (a leaver stands on the normal
        // 930000800, observed or not).
        if (session.phase() == AgentEpqSession.Phase.EXITING
                && session.members().stream().map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .noneMatch(member -> AgentEpqDefinition.isEventMap(member.getMapId())
                        && member.getEventInstance() == bound)) {
            session.complete(nowMs);
        }
    }

    /**
     * The coordinator's backstop for the event's 30-minute timer: a stock run counts from its admission, a companion
     * run from the party's entry (AgentEpqCompanionRun.timerExpired).
     */
    private static boolean eventTimerExpired(AgentEpqSession session, long nowMs) {
        if (session.companionAdopted()) return AgentEpqCompanionRun.timerExpired(session, nowMs, EVENT_TIMEOUT_MS);
        return nowMs - session.startedAtMs() >= EVENT_TIMEOUT_MS;
    }

    /** The bound event still runs and still holds the run's leader. */
    private static boolean eventLive(AgentEpqSession session, Character leader) {
        EventInstanceManager bound = session.eventInstance();
        return bound != null && !bound.isEventDisposed() && leader != null && leader.getEventInstance() == bound;
    }

    /**
     * The leaver's signature: the run is bound, and the member stands on an EPQ map with no event instance. The stock
     * exit sends every leaver to the normal 930000800 (every EPQ map's return map); a member the event never
     * registered still stands on the recruit map and is not a leaver.
     */
    private static boolean leftEvent(AgentEpqSession session, Character member) {
        return session.eventInstance() != null && member.getEventInstance() == null
                && AgentEpqDefinition.isEventMap(member.getMapId());
    }

    /**
     * EPQ base fix 3: marks a member the live bound event no longer holds as departed, once ('EPQ departed:'), and
     * reopens the stage-2 bottles it may have carried; a departed member seen back in the bound event is a member
     * again ('EPQ returned:'). Humans and Agents alike; never throws. Nothing is marked while the run is being
     * released: the release itself takes every player out of the event.
     */
    private static void observeDeparture(AgentEpqSession session, Character member, Character leader) {
        try {
            EventInstanceManager bound = session.eventInstance();
            if (bound == null || session.terminationStarted()) return;
            if (member.getEventInstance() == bound) {
                if (session.clearDeparted(member.getId())) {
                    log.info("EPQ returned: session={} member={}({}) map={} phase={}", session.sessionId(),
                            member.getName(), member.getId(), member.getMapId(), session.phase());
                }
                return;
            }
            if (!eventLive(session, leader) || !leftEvent(session, member) || !session.markDeparted(member.getId())) {
                return;
            }
            AgentEpqMemberState state = session.member(member.getId());
            log.info("EPQ departed: session={} member={}({}) type={} map={} phase={} registered={} companionRun={}",
                    session.sessionId(), member.getName(), member.getId(),
                    state == null ? "UNKNOWN" : state.memberType(), member.getMapId(), session.phase(),
                    bound.getPlayers().size(), session.companionAdopted());
            if (session.phase() == AgentEpqSession.Phase.STAGE_TWO) session.reopenStageTwoBottles();
        } catch (RuntimeException e) {
            log.warn("EPQ departure check failed: session={} member={}", session.sessionId(), member.getId(), e);
        }
    }

    /**
     * What the coordinator can still see when its event "ended or expired" (logged before the failure): the leader's
     * map and HP, whether the leader is in the bound event, in none or in another, whether the bound event was
     * disposed, how many players it still holds and how many dead members the departure gate holds. Never throws.
     */
    static String eventEndDetail(AgentEpqSession session, Character leader) {
        try {
            EventInstanceManager bound = session.eventInstance();
            EventInstanceManager leaderEvent = leader.getEventInstance();
            String where = leaderEvent == null ? "none" : leaderEvent == bound ? "bound" : "other";
            return "leaderMap=" + leader.getMapId() + " leaderHp=" + leader.getHp() + " leaderEvent=" + where
                    + " eventDisposed=" + (bound == null ? "unbound" : String.valueOf(bound.isEventDisposed()))
                    + " registered=" + (bound == null ? 0 : bound.getPlayers().size())
                    + " heldDead=" + session.heldDeadCount();
        } catch (RuntimeException e) {
            return "detail unavailable";
        }
    }

    /**
     * Fails the run as before, with one 'EPQ session failure:' line in every mode: the phase it was in, the reason, its
     * size and whether it is a companion run.
     */
    static void fail(AgentEpqSession session, String reason, long nowMs) {
        log.warn("EPQ session failure: session={} phase={} reason={} members={} companionRun={}",
                session.sessionId(), session.phase(), reason, session.memberCount(), session.companionAdopted());
        session.fail(reason, nowMs);
    }

    public static void tickMember(
            AgentEpqSession session, AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentEpqMemberState member = session.member(agent.getId());
        if (member == null || member.memberType() != AgentEpqMemberState.MemberType.AGENT
                || nowMs < member.nextActionAtMs()) return;
        entry.capabilityStates().require(AgentCombatSkillConstraintState.STATE_KEY)
                .requireAttackSkill();
        if (agent.getMapId() != AgentEpqDefinition.STAGE_FOUR_MAP) {
            actions().clearHorizontalBoundary(entry);
        }
        if (member.bossResting() && agent.getMapId() != AgentEpqDefinition.BOSS_MAP) {
            endBossRest(session, agent, member, null); // EPQ base fix 7: the rest belongs to the golem's map
        }
        if (leftEvent(session, agent)) {
            // EPQ base fix 3: the event no longer holds this Agent (its respawn walked it out, or it pressed OK). It
            // has left the run and idles until the release; it does not fail it.
            actions().stop(entry);
            observeDeparture(session, agent, character(session.eventLeaderId()));
            return;
        }
        if (session.eventInstance() != null && AgentEpqDefinition.isEventMap(agent.getMapId())
                && agent.getEventInstance() != null && agent.getEventInstance() != session.eventInstance()) {
            fail(session, "EPQ member entered a different event instance", nowMs);
            return;
        }

        switch (agent.getMapId()) {
            case AgentEpqDefinition.RECRUIT_MAP -> {
                // A companion run's leader clicks Ellin himself: his companions neither announce nor talk to her.
                if (session.companionAdopted()) actions().stop(entry);
                else enterEvent(session, entry, agent, member, nowMs);
            }
            case AgentEpqDefinition.ENTRANCE_MAP -> {
                if (!topUp(session, entry, agent, member, nowMs)) enterPortal(entry, agent, 4, member, nowMs);
            }
            case AgentEpqDefinition.STAGE_ONE_MAP -> stageOne(entry, agent, member, nowMs);
            case AgentEpqDefinition.STAGE_TWO_MAP -> stageTwo(session, entry, agent, member, nowMs);
            case AgentEpqDefinition.STAGE_THREE_MAP -> stageThree(entry, agent, member, nowMs);
            case AgentEpqDefinition.STAGE_FOUR_MAP -> stageFour(session, entry, agent, member, nowMs);
            case AgentEpqDefinition.STAGE_FIVE_MAP -> stageFive(session, entry, agent, member, nowMs);
            case AgentEpqDefinition.BOSS_MAP -> boss(session, entry, agent, member, nowMs);
            case AgentEpqDefinition.REWARD_MAP -> {
                // 930000800 is every stage's return map: only the reward phase hits its reactor (whose hit gives
                // everyone on the map its EXP) and takes its exit. An Agent the cleared event still holds here is at
                // the reward too (out00's warp), even when no coordinator pass saw the leader here before east00.
                if (session.phase().ordinal() >= AgentEpqSession.Phase.REWARD.ordinal()
                        || (AgentEpqTerminationService.pastTheClear(session.eventInstance())
                        && agent.getEventInstance() == session.eventInstance())) {
                    reward(session, entry, agent, member, nowMs);
                } else {
                    actions().stop(entry);
                }
            }
            default -> {
                actions().stop(entry);
                if (session.phase() == AgentEpqSession.Phase.EXITING) session.markProgress(nowMs);
            }
        }
    }

    private static void enterEvent(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                   AgentEpqMemberState member, long nowMs) {
        long chatAt = lobbyChatAt(session, agent.getId());
        if (nowMs >= chatAt && member.claimAnnouncement("lobby-entry")) {
            boolean agentLeader = agent.getId() == session.eventLeaderId();
            sendVisiblePartyChat(session, "lobby-entry", agent, agentLeader
                    ? "Party ready. We're entering EPQ in 5 seconds."
                    : lobbyReadyMessage(session, agent.getId()));
            if (agentLeader) member.deferUntil(nowMs + LOBBY_ENTRY_DELAY_MS);
            actions().stop(entry);
            return;
        }
        if (agent.getId() != session.eventLeaderId()) { actions().stop(entry); return; }
        if (runNearbyNpc(entry, agent, AgentEpqDefinition.ENTRY_NPC, 0)) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            transition(session, AgentEpqSession.Phase.ENTERING, nowMs);
        }
    }

    private static void stageOne(AgentRuntimeEntry entry, Character agent,
                                 AgentEpqMemberState member, long nowMs) {
        if (actions().liveMonsterCount(agent, Set.of(AgentEpqDefinition.STAGE_ONE_MOB)) == 0) {
            enterPortal(entry, agent, 3, member, nowMs);
            return;
        }
        actions().grind(entry, Set.of(AgentEpqDefinition.STAGE_ONE_MOB));
    }

    private static void stageTwo(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                 AgentEpqMemberState member, long nowMs) {
        boolean coordinator = agent.getId() == workAgentId(session);
        Point position = agent.getPosition();
        Point landing = actions().groundPoint(agent.getMap(), position);
        boolean aboveLandingFoothold = landing != null && landing.y - position.y > 40;
        if (!actions().grounded(agent) || aboveLandingFoothold) {
            actions().prepareNavigation(entry, agent);
            actions().navigate(entry, landing == null ? new Point(position) : landing, true);
            return;
        }
        Reactor spine = agent.getMap().getReactorById(AgentEpqDefinition.SPINE_REACTOR);
        if (coordinator && spine != null && spine.getState() > 0
                && spine.getState() < STAGE_TWO_BOTTLES_REQUIRED
                && session.claimAnnouncement("stage2-progress-" + spine.getState())) {
            sendVisiblePartyChat(session, "stage2-progress-" + spine.getState(), agent,
                    "Filled bottles applied: " + spine.getState()
                    + "/" + STAGE_TWO_BOTTLES_REQUIRED + ".");
        }
        if (spine != null && spine.getState() >= STAGE_TWO_BOTTLES_REQUIRED) {
            if (coordinator && session.claimAnnouncement(
                    "stage2-progress-" + STAGE_TWO_BOTTLES_REQUIRED)) {
                sendVisiblePartyChat(session, "stage2-progress-" + STAGE_TWO_BOTTLES_REQUIRED, agent,
                        "Filled bottles applied: " + STAGE_TWO_BOTTLES_REQUIRED + "/" + STAGE_TWO_BOTTLES_REQUIRED
                        + ". The thorns are open.");
            }
            enterPortal(entry, agent, 3, member, nowMs);
            return;
        }
        Reactor pond = agent.getMap().getReactorById(AgentEpqDefinition.POND_REACTOR);
        if (pond == null) { actions().stop(entry); return; }
        int securedBottles = stageTwoSecuredBottleCount(session, agent, spine);
        if (session.stageTwoBottlesSecured() && securedBottles < STAGE_TWO_BOTTLES_REQUIRED
                && corpseHoldsBottle(session, agent)) {
            // EPQ base fix 4: a bottle holder lying dead here keeps its bottle but cannot drop it; the bottles are
            // secured only while four are still reachable without it. Reopened only when they are short, so a
            // secured mark is never reopened and re-secured (each counted as progress) on every pass.
            session.reopenStageTwoBottles();
        }
        if (securedBottles >= STAGE_TWO_BOTTLES_REQUIRED) {
            session.secureStageTwoBottles(nowMs);
        }
        if (actions().itemCount(agent, AgentEpqDefinition.PURIFIED_POISON) > 0
                && spine != null) {
            if (dropAt(session, entry, agent, InventoryType.ETC,
                    AgentEpqDefinition.PURIFIED_POISON, spine.getPosition(), member, nowMs, "stage2-bottle-dropped")) {
                if (session.stageTwoBottlesSecured()) {
                    member.finishStageTwoTreeReturn();
                    sendVisiblePartyChat(session, "stage2-bottle-dropped", agent,
                            "Filled bottle dropped at the thorns. Heading to the exit.");
                } else {
                    member.beginStageTwoTreeReturn();
                    sendVisiblePartyChat(session, "stage2-bottle-dropped", agent,
                            "Filled bottle dropped at the thorns. Returning to the tree.");
                }
            }
            return;
        }
        if (session.stageTwoBottlesSecured()) {
            member.finishStageTwoTreeReturn();
            if (coordinator && session.claimAnnouncement("stage2-bottles-secured")) {
                sendVisiblePartyChat(session, "stage2-bottles-secured", agent,
                        "Four filled bottles secured. Everyone head to the right-side exit.");
            }
            Point exit = actions().portalPosition(agent, 3);
            if (exit == null) actions().stop(entry);
            else if (!near(agent.getPosition(), exit, PORTAL_RADIUS)) actions().navigate(entry, exit, true);
            else actions().stop(entry);
            return;
        }
        if (member.stageTwoReturningToTree()) {
            Point rally = stageTwoRallyPoint(session, agent, pond.getPosition());
            if (!near(agent.getPosition(), rally, 45)) {
                actions().navigate(entry, rally, true);
                return;
            }
            member.finishStageTwoTreeReturn();
        }
        List<Monster> bugs = AgentMapPerception.monsters(agent.getMap()).stream()
                .filter(Monster::isAlive)
                .filter(mob -> mob.getId() == AgentEpqDefinition.STAGE_TWO_MOB)
                .toList();
        List<Integer> lurers = stageTwoLurerIds(session);
        int lureSide = lurers.indexOf(agent.getId());
        Monster tagged = bugs.stream()
                .filter(mob -> mob.getObjectId() == member.stageTwoTaggedObjectId())
                .findFirst().orElse(null);
        if (lureSide >= 0 && tagged != null
                && (tagged.getPosition() == null || !pond.getArea().contains(tagged.getPosition()))) {
            if (tagged.getPosition() != null) {
                member.observeStageTwoLureProgress(
                        Math.abs(tagged.getPosition().x - pond.getPosition().x), nowMs,
                        STAGE_TWO_LURE_PROGRESS_PX);
            }
            if (member.stageTwoLureStalled(nowMs, STAGE_TWO_LURE_STALL_MS)) {
                attackTarget(entry, agent, tagged, member, nowMs, true, pond.getPosition());
                return;
            }
            Point lure = stageTwoRallyPoint(session, agent, pond.getPosition());
            if (!near(agent.getPosition(), lure, 45)) actions().navigate(entry, lure, true);
            else actions().stop(entry);
            return;
        }
        if (lureSide >= 0 && tagged != null) {
            member.clearStageTwoTag();
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            actions().stop(entry);
            return;
        }
        Monster atPond = bugs.stream()
                .filter(mob -> mob.getPosition() != null && pond.getArea().contains(mob.getPosition()))
                .min(Comparator.comparingLong(Monster::getHp))
                .orElse(null);
        if (atPond != null && lureSide < 0) {
            attackTarget(entry, agent, atPond, member, nowMs, false);
            return;
        }
        int bottlesBefore = actions().itemCount(agent, AgentEpqDefinition.PURIFIED_POISON);
        if (collectNearest(entry, agent, Set.of(AgentEpqDefinition.PURIFIED_POISON))) {
            if (actions().itemCount(agent, AgentEpqDefinition.PURIFIED_POISON) > bottlesBefore) {
                sendVisiblePartyChat(session, "stage2-collected", agent,
                        "Filled bottle collected. Taking it to the thorns.");
            }
            return;
        }
        if (lureSide < 0) {
            Point rally = stageTwoRallyPoint(session, agent, pond.getPosition());
            if (!near(agent.getPosition(), rally, 45)) actions().navigate(entry, rally, true);
            else actions().stop(entry);
            return;
        }
        if (bugs.isEmpty()) {
            member.clearStageTwoTag();
            actions().stop(entry);
            agent.getMap().instanceMapForceRespawn();
            rescue(session, "respawn-bugs", agent);
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }

        Monster target = stageTwoSideTarget(
                bugs, pond.getPosition(), pond.getArea(), lureSide);
        if (target == null) {
            Point rally = stageTwoRallyPoint(session, agent, pond.getPosition());
            if (!near(agent.getPosition(), rally, 45)) actions().navigate(entry, rally, true);
            else actions().stop(entry);
            return;
        }
        attackTarget(entry, agent, target, member, nowMs, true, pond.getPosition());
    }

    private static void stageThree(AgentRuntimeEntry entry, Character agent,
                                   AgentEpqMemberState member, long nowMs) {
        Point coordinator = npcApproachPoint(agent, AgentEpqDefinition.STAGE_NPC);
        if (near(agent.getPosition(), coordinator, MAZE_EXIT_APPROACH_RADIUS)) {
            if (runNearbyNpc(entry, agent, AgentEpqDefinition.STAGE_NPC)) member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        Portal next = agent.getMap().getPortals().stream()
                .filter(portal -> portal.getScriptName() != null
                        && portal.getScriptName().startsWith("party6_stage5"))
                .min(Comparator.comparingDouble(portal -> portal.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (next == null) { actions().stop(entry); return; }
        enterPortal(entry, agent, next.getId(), member, nowMs);
    }

    private static void stageFour(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                  AgentEpqMemberState member, long nowMs) {
        // The authored ground ends at x=-1800/1530. Keep combat momentum just
        // inside those edges so a flower knockback or jump cannot send an Agent
        // below the map and trigger the global emergency recovery teleport.
        actions().setHorizontalBoundary(entry, AgentEpqDefinition.STAGE_FOUR_MAP,
                STAGE_FOUR_MIN_X, STAGE_FOUR_MAX_X);
        int monsterMarbles = actions().itemCount(agent, AgentEpqDefinition.MONSTER_MARBLE);
        int total = stageItemTotal(session, agent, AgentEpqDefinition.MONSTER_MARBLE);
        if (agent.getId() == workAgentId(session)) {
            int milestone = Math.min(20, total / 5 * 5);
            if (milestone > 0 && session.claimAnnouncement("stage4-progress-" + milestone)) {
                partyChat(session, "stage4-progress-" + milestone, agent,
                        "Monster Marbles collected: " + total + "/20.");
            }
        }
        if (total >= 20) {
            if (agent.getId() == session.eventLeaderId()) {
                if (monsterMarbles >= 20) {
                    if (runNearbyNpc(entry, agent, AgentEpqDefinition.STAGE_NPC)) {
                        member.deferUntil(nowMs + ACTION_RETRY_MS);
                    }
                } else {
                    if (!collectNearest(entry, agent, Set.of(AgentEpqDefinition.MONSTER_MARBLE))) {
                        Point rally = npcApproachPoint(agent, AgentEpqDefinition.STAGE_NPC);
                        if (!near(agent.getPosition(), rally, NPC_RADIUS)) actions().navigate(entry, rally, true);
                        else actions().stop(entry);
                    }
                }
            } else if (monsterMarbles > 0) {
                dropStackNearNpc(session, entry, agent, AgentEpqDefinition.MONSTER_MARBLE,
                        AgentEpqDefinition.STAGE_NPC, member, nowMs, "stage4-handoff");
                if (actions().itemCount(agent, AgentEpqDefinition.MONSTER_MARBLE) == 0) {
                    announce(session, member, agent, "stage4-handoff",
                            "I left my Monster Marbles beside Ellin for our leader.");
                }
            } else {
                Point rally = npcApproachPoint(agent, AgentEpqDefinition.STAGE_NPC);
                if (!near(agent.getPosition(), rally, NPC_RADIUS)) actions().navigate(entry, rally, true);
                else actions().stop(entry);
            }
            return;
        }
        if (actions().itemCount(agent, AgentEpqDefinition.PURIFICATION_MARBLE) == 0) {
            if (runNearbyNpc(entry, agent, AgentEpqDefinition.STAGE_NPC)) member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        // EPQ base fix 8: an Agent whose own flowers are caught helps with another's; only with no live flower at all
        // does the assist below run (930000400's flowers are one-shot, so its respawn call spawns nothing).
        Monster target = AgentEpqParticipationPolicy.captureTarget(flowers(agent),
                flower -> captureAgentId(session, flower), agent.getId(),
                mob -> mob.getPosition().distanceSq(agent.getPosition()));
        if (target == null) {
            actions().stop(entry);
            agent.getMap().instanceMapForceRespawn();
            rescue(session, "respawn-flowers", agent);
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        if (!near(agent.getPosition(), target.getPosition(), 220)) {
            actions().navigate(entry, target.getPosition(), true);
            return;
        }
        actions().stop(entry);
        if (AgentEpqCaptureRuntime.ready(target)) {
            boolean own = captureAgentId(session, target) == agent.getId();
            if (AgentEpqCaptureRuntime.capture(agent, target)) {
                logCapture(session, agent, target, own);
                member.deferUntil(nowMs + ACTION_RETRY_MS);
            }
            return;
        }
        AgentAttackPlan skill = singleTargetSkillPlan(entry, agent, target);
        if (skill == null) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        AgentAttackTransactionResult attack = AgentCombatAttackRuntime.attackMonster(entry, agent, skill);
        if (attack.committed()) session.markProgress(nowMs);
    }

    private static void stageFive(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                  AgentEpqMemberState member, long nowMs) {
        boolean collector = mayCollectStageFiveStone(session, agent.getId());
        if (collector && !session.stageFiveStoneHandedOff()
                && collectNearest(entry, agent, Set.of(AgentEpqDefinition.MAGIC_STONE))) return;
        int magicStones = actions().itemCount(agent, AgentEpqDefinition.MAGIC_STONE);
        if (magicStones > 0) {
            if (session.claimAnnouncement("stage5-stone-found")) {
                partyChat(session, "stage5-stone-found", agent,
                        "Purple Stone of Magic found. Bringing it to Yuris.");
            }
            if (agent.getId() == session.eventLeaderId()) {
                if (runNearbyNpc(entry, agent, AgentEpqDefinition.STONE_NPC)) {
                    member.deferUntil(nowMs + ACTION_RETRY_MS);
                }
            } else {
                int stonesBefore = magicStones;
                dropStackNearNpc(session, entry, agent, AgentEpqDefinition.MAGIC_STONE,
                        AgentEpqDefinition.STONE_NPC, member, nowMs, "stage5-human-handoff");
                if (stonesBefore > 0
                        && actions().itemCount(agent, AgentEpqDefinition.MAGIC_STONE) == 0) {
                    session.markStageFiveStoneHandedOff(nowMs);
                    announce(session, member, agent, "stage5-human-handoff",
                            "I dropped the Magic Stone beside Yuris. Leader, please pick it up and continue.");
                }
            }
            return;
        }
        Reactor target = assignedStageFiveReactor(session, agent);
        if (target == null) {
            Point rally = npcApproachPoint(agent, AgentEpqDefinition.STONE_NPC);
            if (!near(agent.getPosition(), rally, NPC_RADIUS)) actions().navigate(entry, rally, true);
            else actions().stop(entry);
            return;
        }
        hitReactor(entry, agent, target, member, nowMs);
    }

    static boolean mayCollectStageFiveStone(AgentEpqSession session, int characterId) {
        return session != null && characterId == workAgentId(session);
    }

    private static void boss(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                             AgentEpqMemberState member, long nowMs) {
        if (agent.getId() == workAgentId(session)
                && session.claimAnnouncement("boss-combat")) {
            partyChat(session, "boss-combat", agent,
                    "Poison Golem ahead. Everyone resume combat!");
        }
        if (actions().liveMonsterCount(agent, AgentEpqDefinition.BOSS_MOBS) > 0) {
            if (member.topUpActive()) endTopUp(session, agent, member, "golem", false);
            if (bossRest(session, entry, agent, member)) return;
            actions().grind(entry, AgentEpqDefinition.BOSS_COMBAT_TARGETS);
            return;
        }
        // The golem chain revives (9300180 -> 9300181 -> 9300182) after each die animation, so for a moment no golem
        // lives: a rest lasts through that gap and ends with the clear (or once the member leaves this map).
        if (member.bossResting() && AgentEpqTerminationService.pastTheClear(session.eventInstance())) {
            endBossRest(session, agent, member, null);
        }
        if (session.eventInstance() == null || !session.eventInstance().isEventCleared()) {
            // Before the golem: a member under its top-up drinks first (an Agent leader before its altar drop).
            if (topUp(session, entry, agent, member, nowMs)) return;
            if (agent.getId() == session.eventLeaderId()
                    && actions().itemCount(agent, AgentEpqDefinition.MAGIC_STONE) > 0) {
                Reactor altar = agent.getMap().getReactorById(AgentEpqDefinition.ALTAR_REACTOR);
                if (altar != null) dropAt(session, entry, agent, InventoryType.ETC,
                        AgentEpqDefinition.MAGIC_STONE, altar.getPosition(), member, nowMs, "boss-altar");
            } else {
                actions().stop(entry);
            }
            return;
        }
        AgentMapPerception.monsters(agent.getMap()).stream()
                .filter(Monster::isAlive)
                .filter(monster -> monster.getId() == AgentEpqDefinition.POST_DEATH_DUMMY)
                .findFirst().ifPresent(dummy -> {
                    agent.getMap().killMonster(dummy, null, false, (short) 0);
                    rescue(session, "kill-dummy", agent);
                });
        if (agent.getId() == fragmentCollectorId(session)
                && collectNearest(entry, agent, Set.of(AgentEpqDefinition.GOLEMS_MARBLE))) return;
        if (nowMs - session.observeBossCleared(nowMs) < BOSS_LOOT_SETTLE_MS) { actions().stop(entry); return; }
        if (agent.getId() == workAgentId(session)
                && session.members().stream().anyMatch(state ->
                state.memberType() == AgentEpqMemberState.MemberType.HUMAN)) {
            announce(session, member, agent, "boss-human-fragment",
                    "The Altair Fragment is reserved for our human party member; loot it before exiting.");
        }
        enterPortal(entry, agent, 1, member, nowMs);
    }

    /**
     * EPQ base fix 7: while a golem lives, a living member with fewer than POT_STOP HP potions and HP under
     * REST_HP_FRACTION of its maximum rests out of the golem's reach (AgentEpqRecoveryPolicy), so a companion out of
     * potions is no longer sent back to its owner at the golem's feet. Away from its rest point (or airborne) it walks
     * there; there, out of the golem's reach, it fights a golem add that reaches it and otherwise stands still
     * (passive regen; the generic potion share still asks the party). It rejoins at RESUME_HP_FRACTION or once it
     * holds POT_STOP potions; the rest lasts through the golem's revive gap and otherwise ends only with the clear or
     * when it leaves the golem's map. 'EPQ boss rest:' START, END and every change of rest point (MOVE). True while
     * the rest owns this pass.
     */
    private static boolean bossRest(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                     AgentEpqMemberState member) {
        Point position = agent.getPosition();
        Monster golem = position == null ? null : AgentMapPerception.monsters(agent.getMap()).stream()
                .filter(Monster::isAlive)
                .filter(monster -> AgentEpqDefinition.BOSS_MOBS.contains(monster.getId()))
                .filter(monster -> monster.getPosition() != null)
                .min(Comparator.comparingDouble(monster -> monster.getPosition().distanceSq(position)))
                .orElse(null);
        if (golem == null) return false;
        int potions = actions().hpPotionCount(agent);
        boolean wasResting = member.bossResting();
        boolean rest = AgentEpqRecoveryPolicy.resting(wasResting, potions,
                server.agents.runtime.AgentRuntimeConfig.cfg.POT_STOP, agent.getHp(), agent.getCurrentMaxHp(),
                AgentEpqRecoveryPolicy.REST_HP_FRACTION, AgentEpqRecoveryPolicy.RESUME_HP_FRACTION);
        Point golemAt = golem.getPosition();
        if (!rest) {
            if (wasResting) endBossRest(session, agent, member, golemAt);
            return false;
        }
        Point stored = member.bossRestPoint();
        Point restPoint = AgentEpqRecoveryPolicy.restPoint(golemAt, position, stored, AgentEpqDefinition.BOSS_FLOOR,
                AgentEpqRecoveryPolicy.FLOOR_EDGE_MARGIN_PX, AgentEpqRecoveryPolicy.REACH_PX);
        member.restAt(restPoint);
        if (!wasResting) logBossRest(session, agent, "START", restPoint, golemAt);
        else if (!restPoint.equals(stored)) logBossRest(session, agent, "MOVE", restPoint, golemAt);
        if (!nearWithin(position, restPoint, AgentEpqRecoveryPolicy.REST_ARRIVAL_PX) || !actions().grounded(agent)) {
            actions().navigate(entry, restPoint, true);
        } else if (Math.abs(position.x - golemAt.x) > AgentEpqRecoveryPolicy.REACH_PX
                && AgentEpqRecoveryPolicy.addThreat(position, AgentMapPerception.monsters(agent.getMap()).stream()
                .filter(Monster::isAlive)
                .filter(monster -> AgentEpqDefinition.BOSS_ADDS.contains(monster.getId()))
                .map(Monster::getPosition)
                .toList(), AgentEpqRecoveryPolicy.ADD_THREAT_PX)) {
            actions().grindFromAnchor(entry, restPoint, AgentEpqDefinition.BOSS_ADDS, Set.of());
        } else {
            actions().stop(entry);
        }
        return true;
    }

    private static void endBossRest(AgentEpqSession session, Character agent, AgentEpqMemberState member,
                                    Point golem) {
        Point restPoint = member.bossRestPoint();
        member.clearBossRest();
        logBossRest(session, agent, "END", restPoint, golem);
    }

    /** 'EPQ boss rest:' in every mode; never throws. */
    private static void logBossRest(AgentEpqSession session, Character agent, String state, Point restPoint,
                                    Point golem) {
        try {
            log.info("EPQ boss rest: session={} member={} state={} hp={}/{} hpPots={} restPoint={} golem={}",
                    session.sessionId(), agent.getName(), state, agent.getHp(), agent.getCurrentMaxHp(),
                    actions().hpPotionCount(agent), xy(restPoint), xy(golem));
        } catch (RuntimeException e) {
            log.warn("EPQ boss rest line failed: session={}", session.sessionId(), e);
        }
    }

    /** Within {@code radius} along both axes. */
    private static boolean nearWithin(Point a, Point b, int radius) {
        return a != null && b != null && Math.abs(a.x - b.x) <= radius && Math.abs(a.y - b.y) <= radius;
    }

    /**
     * EPQ base fix 6: on the entrance, and on the golem's map while no golem lives and the event is not cleared, an
     * Agent under TOP_UP_HP_FRACTION of its maximum HP with HP potions stands and drinks one (the autopot's own choice
     * for its deficit) every TOP_UP_INTERVAL_MS until it reaches the fraction. One 'EPQ top-up:' START and END line per
     * episode; a drink that raised no HP, or no potion to choose, ends the episode and the top-ups on that map.
     * True while the top-up owns this pass.
     */
    private static boolean topUp(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                 AgentEpqMemberState member, long nowMs) {
        int mapId = agent.getMapId();
        if (member.topUpActive() && member.topUpMapId() != mapId) endTopUp(session, agent, member, "left-map", false);
        boolean golemAlive = mapId == AgentEpqDefinition.BOSS_MAP
                && actions().liveMonsterCount(agent, AgentEpqDefinition.BOSS_MOBS) > 0;
        if (!AgentEpqRecoveryPolicy.topUpMap(mapId, golemAlive,
                AgentEpqTerminationService.pastTheClear(session.eventInstance()))) {
            return false;
        }
        int potions = actions().hpPotionCount(agent);
        if (!AgentEpqRecoveryPolicy.needsTopUp(agent.getHp(), agent.getCurrentMaxHp(), potions,
                AgentEpqRecoveryPolicy.TOP_UP_HP_FRACTION)) {
            if (member.topUpActive()) endTopUp(session, agent, member, potions > 0 ? "target" : "no-potion", false);
            return false;
        }
        if (member.topUpSpentOn(mapId)) return false;
        if (!member.topUpActive()) {
            member.startTopUp(mapId);
            logTopUp(session, agent, member, "START", "");
        }
        if (nowMs < member.nextTopUpAtMs()) {
            actions().stop(entry);
            return true;
        }
        int hp = agent.getHp();
        if (member.lastTopUpHp() >= 0 && hp <= member.lastTopUpHp()) {
            endTopUp(session, agent, member, "no-gain", true);
            return false;
        }
        var choice = AgentPotionService.selectAutopotForCurrentDeficit(agent, true);
        if (choice == null) {
            endTopUp(session, agent, member, "no-potion", true);
            return false;
        }
        actions().stop(entry);
        boolean drank = actions().useItem(agent, choice.itemId());
        member.recordTopUpDrink(hp, nowMs + AgentEpqRecoveryPolicy.TOP_UP_INTERVAL_MS, drank);
        return true;
    }

    private static void endTopUp(AgentEpqSession session, Character agent, AgentEpqMemberState member,
                                 String reason, boolean spent) {
        member.endTopUp(spent);
        logTopUp(session, agent, member, "END", reason);
    }

    /** 'EPQ top-up:' for an episode's START or END, in every mode; never throws. */
    private static void logTopUp(AgentEpqSession session, Character agent, AgentEpqMemberState member,
                                 String state, String reason) {
        try {
            log.info("EPQ top-up: session={} member={} state={} reason={} hp={}/{} hpPots={} drinks={} map={}",
                    session.sessionId(), agent.getName(), state, reason, agent.getHp(), agent.getCurrentMaxHp(),
                    actions().hpPotionCount(agent), member.topUpDrinks(), member.topUpMapId());
        } catch (RuntimeException e) {
            log.warn("EPQ top-up line failed: session={}", session.sessionId(), e);
        }
    }

    private static void reward(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                               AgentEpqMemberState member, long nowMs) {
        if (!session.rewardHit() && agent.getId() == workAgentId(session)) {
            Reactor reward = agent.getMap().getReactorById(AgentEpqDefinition.REWARD_REACTOR);
            if (reward != null && reward.isActive()) {
                if (hitReactor(entry, agent, reward, member, nowMs)) session.markRewardHit(nowMs);
                return;
            }
            session.markRewardHit(nowMs);
        }
        if (!session.rewardHit()) { actions().stop(entry); return; }
        if (enterPortal(entry, agent, 1, member, nowMs)) {
            transition(session, AgentEpqSession.Phase.EXITING, nowMs);
        }
    }

    /**
     * Moves the run forward, with one 'EPQ phase:' line per change in every mode (the time spent in the phase it left
     * and in the run), so a run's pace reads straight from the log, followed by one 'EPQ vitals:' line (every online
     * member's HP and potions as the phase begins). A companion run leaving the entrance also logs its companions'
     * potions, which its release compares.
     */
    static void transition(AgentEpqSession session, AgentEpqSession.Phase next, long nowMs) {
        AgentEpqSession.Change change = session.advance(next, nowMs);
        if (change == null) return;
        log.info("EPQ phase: session={} {}->{} phaseMs={} runMs={} companionRun={}", session.sessionId(),
                change.from(), change.to(), change.phaseMs(), change.runMs(), session.companionAdopted());
        log.info("EPQ vitals: session={} phase={} members={}", session.sessionId(), change.to(),
                AgentEpqTerminationService.vitals(session));
        if (change.from() == AgentEpqSession.Phase.PREPARING && session.companionAdopted()) {
            String potions = AgentEpqTerminationService.potions(session);
            session.setPotionsAtEntry(potions);
            log.info("EPQ potions: session={} start={}", session.sessionId(), potions);
        }
    }

    /** 'EPQ member down:' once per death, for every member online; nothing decides by it. */
    private static void logMembersDown(AgentEpqSession session) {
        for (AgentEpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character == null) continue;
            if (character.getHp() > 0) {
                session.clearMemberDown(member.characterId());
            } else if (session.markMemberDown(member.characterId())) {
                log.info("EPQ member down: session={} member={} type={} map={} phase={}", session.sessionId(),
                        member.characterId(), member.memberType(), character.getMapId(), session.phase());
                log.info("EPQ down detail: session={} {}", session.sessionId(), downDetail(character));
            }
        }
    }

    /**
     * What a member that went down held and where it lay, for its 'EPQ down detail:' line: its maximum HP, potions,
     * the stage objective items it carried (filled bottles, the stone, monster marbles, purification marbles), map and
     * position. Never throws.
     */
    static String downDetail(Character member) {
        try {
            Point at = member.getPosition();
            return "member=" + member.getName() + "(" + member.getId() + ") maxHp=" + member.getCurrentMaxHp()
                    + " hpPots=" + actions().hpPotionCount(member) + " mpPots=" + actions().mpPotionCount(member)
                    + " items={bottles=" + actions().itemCount(member, AgentEpqDefinition.PURIFIED_POISON)
                    + ", stone=" + actions().itemCount(member, AgentEpqDefinition.MAGIC_STONE)
                    + ", marbles=" + actions().itemCount(member, AgentEpqDefinition.MONSTER_MARBLE)
                    + ", purifiers=" + actions().itemCount(member, AgentEpqDefinition.PURIFICATION_MARBLE)
                    + "} map=" + member.getMapId() + " at=" + xy(at);
        } catch (RuntimeException e) {
            return "detail unavailable";
        }
    }

    /** A point as the evidence lines write it: (x,y), or none. */
    static String xy(Point point) {
        return point == null ? "none" : "(" + point.x + "," + point.y + ")";
    }

    /**
     * 'EPQ drop:' for an objective drop that was made: where the dropper stood, where it meant the drop to go and where
     * the map would land it (none when that cannot be told). Nothing decides by it; never throws.
     */
    private static void logDrop(AgentEpqSession session, String key, Character agent, Point at, Point target) {
        try {
            log.info("EPQ drop: session={} key={} member={} at={} target={} landing={}", session.sessionId(), key,
                    agent.getName(), xy(at), xy(target), xy(actions().predictDropLanding(agent, at)));
        } catch (RuntimeException e) {
            log.warn("EPQ drop line failed: session={} key={}", session.sessionId(), key, e);
        }
    }

    /**
     * 'EPQ capture:' for a stage-4 flower caught: who caught it, whether it was its own by the stock assignment, and
     * the stage's marble total after it. Nothing decides by it; never throws.
     */
    private static void logCapture(AgentEpqSession session, Character agent, Monster flower, boolean own) {
        try {
            log.info("EPQ capture: session={} member={} flower={} own={} marbles={} map={}", session.sessionId(),
                    agent.getName(), flower.getObjectId(), own,
                    stageItemTotal(session, agent, AgentEpqDefinition.MONSTER_MARBLE), agent.getMapId());
        } catch (RuntimeException e) {
            log.warn("EPQ capture line failed: session={}", session.sessionId(), e);
        }
    }

    /**
     * 'EPQ boss:' the first time each golem is alive on the boss map while the leader is there, and the first time the
     * bound event reports its clear, wherever the leader is; nothing decides by it.
     */
    private static void logBossProgress(AgentEpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() == AgentEpqDefinition.BOSS_MAP && leader.getMap() != null) {
            List<Monster> alive = AgentMapPerception.monsters(leader.getMap()).stream()
                    .filter(Monster::isAlive).toList();
            for (int golem : AgentEpqDefinition.BOSS_MOBS.stream().sorted().toList()) {
                if (alive.stream().anyMatch(monster -> monster.getId() == golem)
                        && session.claimLogMark("golem-" + golem)) {
                    log.info("EPQ boss: session={} golem={} runMs={}", session.sessionId(), golem,
                            nowMs - session.startedAtMs());
                }
            }
        }
        if (AgentEpqTerminationService.pastTheClear(session.eventInstance()) && session.claimLogMark("cleared")) {
            log.info("EPQ boss: session={} cleared runMs={}", session.sessionId(), nowMs - session.startedAtMs());
        }
    }

    /**
     * Counts one of the coordinator's assists for the run's released line and logs the first of each kind as 'EPQ
     * rescue:' (a respawn can repeat every retry); nothing decides by it.
     */
    private static void rescue(AgentEpqSession session, String kind, Character agent) {
        if (session.countRescue(kind) == 1) {
            log.info("EPQ rescue: session={} kind={} agent={} map={} first=true", session.sessionId(), kind,
                    agent.getName(), agent.getMapId());
        }
    }

    /**
     * The phase the leader's map shows. 930000800 is also every stage's return map (a leader who died, quit or was
     * left behind stands there before any clear), so it reads as the reward only when {@code rewardReady}: the bound
     * event reports its clear and the leader is still in it.
     */
    private static AgentEpqSession.Phase phaseForMap(int mapId, boolean rewardReady) {
        return switch (mapId) {
            case AgentEpqDefinition.RECRUIT_MAP -> AgentEpqSession.Phase.PREPARING;
            case AgentEpqDefinition.ENTRANCE_MAP -> AgentEpqSession.Phase.ENTERING;
            case AgentEpqDefinition.STAGE_ONE_MAP -> AgentEpqSession.Phase.STAGE_ONE;
            case AgentEpqDefinition.STAGE_TWO_MAP -> AgentEpqSession.Phase.STAGE_TWO;
            case AgentEpqDefinition.STAGE_THREE_MAP -> AgentEpqSession.Phase.STAGE_THREE;
            case AgentEpqDefinition.STAGE_FOUR_MAP -> AgentEpqSession.Phase.STAGE_FOUR;
            case AgentEpqDefinition.STAGE_FIVE_MAP -> AgentEpqSession.Phase.STAGE_FIVE;
            case AgentEpqDefinition.BOSS_MAP -> AgentEpqSession.Phase.BOSS;
            case AgentEpqDefinition.REWARD_MAP -> rewardReady ? AgentEpqSession.Phase.REWARD : null;
            default -> null;
        };
    }

    static List<Monster> flowers(Character agent) {
        return AgentMapPerception.monsters(agent.getMap()).stream()
                .filter(Monster::isAlive).filter(mob -> mob.getId() == AgentEpqDefinition.POISON_FLOWER).toList();
    }

    private static int captureAgentId(AgentEpqSession session, Monster flower) {
        return AgentEpqParticipationPolicy.assignedAgent(
                session.seed(), flower.getObjectId(), agentIds(session, flower.getMap()));
    }

    private static void attackTarget(AgentRuntimeEntry entry, Character agent, Monster target,
                                     AgentEpqMemberState member, long nowMs,
                                     boolean preserveOutsidePond) {
        attackTarget(entry, agent, target, member, nowMs, preserveOutsidePond, null);
    }

    private static void attackTarget(AgentRuntimeEntry entry, Character agent, Monster target,
                                     AgentEpqMemberState member, long nowMs,
                                     boolean preserveOutsidePond, Point pond) {
        AgentAttackPlan skill = singleTargetSkillPlan(entry, agent, target);
        if (skill == null) {
            actions().navigate(entry, target.getPosition(), true);
            return;
        }
        AgentAttackTransactionResult attack = AgentCombatAttackRuntime.attackMonster(entry, agent, skill);
        if (!attack.committed()) return;
        if (preserveOutsidePond && attack.hitLines() > 0) {
            target.setAgentPhysicsAggroTimeoutOverrideMs(STAGE_TWO_AGGRO_TIMEOUT_MS);
            int treeDistance = pond == null || target.getPosition() == null
                    ? Integer.MAX_VALUE
                    : Math.abs(target.getPosition().x - pond.x);
            member.tagStageTwoObject(target.getObjectId(), treeDistance, nowMs);
        }
        member.deferUntil(nowMs + ACTION_RETRY_MS);
    }

    private static AgentAttackPlan singleTargetSkillPlan(
            AgentRuntimeEntry entry, Character agent, Monster target) {
        AgentAttackPlan planned = AgentCombatPlanRuntime.planSkillAttackOnly(
                entry, agent, target, AgentCombatConfig.cfg);
        if (planned == null || planned.skillId <= 0 || planned.primaryTarget() != target) return null;
        return new AgentAttackPlan(planned.skillId, planned.skillLevel, planned.numDamage,
                planned.hitBox, List.of(target), planned.route, planned.display, planned.direction,
                planned.rangedDirection, planned.stance, planned.speed, planned.hitDelayMs,
                planned.cooldownMs, planned.damageWeaponType);
    }

    private static Point stageTwoRallyPoint(AgentEpqSession session, Character agent, Point pond) {
        List<Integer> agents = agentIds(session, agent.getMap());
        int index = Math.max(0, agents.indexOf(agent.getId()));
        int offset = (index - agents.size() / 2) * 36;
        Point candidate = new Point(pond.x + offset, pond.y - 96);
        Point ground = actions().groundPoint(agent.getMap(), candidate);
        return ground == null ? new Point(pond.x + offset, agent.getPosition().y) : ground;
    }

    private static List<Integer> stageTwoLurerIds(AgentEpqSession session) {
        int carrierId = workAgentId(session);
        return session.members().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                .map(AgentEpqMemberState::characterId)
                .filter(characterId -> characterId != carrierId)
                .filter(characterId -> !session.departed(characterId))
                .map(AgentEpqCoordinator::character)
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> candidate.getHp() > 0)
                .sorted(Comparator.comparingInt(candidate ->
                        candidate.calculateMaxBaseDamage(candidate.getTotalWatk())))
                .limit(2)
                .map(Character::getId)
                .toList();
    }

    private static Monster stageTwoSideTarget(
            List<Monster> bugs, Point pond, java.awt.Rectangle pondArea, int lureSide) {
        Comparator<Monster> sideOrder = Comparator.comparingInt(mob -> mob.getPosition().x);
        if (lureSide == 1) sideOrder = sideOrder.reversed();
        Monster sideTarget = bugs.stream()
                .filter(mob -> mob.getPosition() != null
                        && (pondArea == null || !pondArea.contains(mob.getPosition())))
                .filter(mob -> lureSide == 0
                        ? mob.getPosition().x < pond.x
                        : mob.getPosition().x >= pond.x)
                .min(sideOrder)
                .orElse(null);
        return sideTarget;
    }

    /** The living Agents on this map, by id: a corpse owns no flower, rally slot or stage-5 box (EPQ base fix 4). */
    private static List<Integer> agentIds(AgentEpqSession session, MapleMap map) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                .map(AgentEpqMemberState::characterId)
                .filter(id -> {
                    Character candidate = character(id);
                    return candidate != null && candidate.getMap() == map && candidate.getHp() > 0;
                })
                .sorted()
                .toList();
    }

    static int workAgentId(AgentEpqSession session) {
        return session.workAgentId();
    }

    static long lobbyChatAt(AgentEpqSession session, int characterId) {
        if (characterId == session.eventLeaderId()) return session.startedAtMs();
        List<Integer> followers = session.members().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                .map(AgentEpqMemberState::characterId)
                .filter(id -> id != session.eventLeaderId())
                .sorted().toList();
        int index = Math.max(0, followers.indexOf(characterId));
        return session.startedAtMs() + (index + 1L) * LOBBY_CHAT_STAGGER_MS;
    }

    static String lobbyReadyMessage(AgentEpqSession session, int characterId) {
        List<Integer> followers = session.members().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                .map(AgentEpqMemberState::characterId)
                .filter(id -> id != session.eventLeaderId())
                .sorted().toList();
        int index = Math.max(0, followers.indexOf(characterId));
        return switch (Math.floorMod(index, 4)) {
            case 0 -> "Ready for EPQ!";
            case 1 -> "Ready. Let's go!";
            case 2 -> "I'll take the nearby mobs.";
            default -> "All set!";
        };
    }

    /** One of the coordinator's lines in party and map chat, logged first as 'EPQ line:' under its key. */
    private static void sendVisiblePartyChat(AgentEpqSession session, String key, Character speaker, String message) {
        AgentEpqDialogue.logLine(session.sessionId(), key, speaker, message);
        AgentPartyGatewayRuntime.party().sendPartyChat(speaker, message);
        AgentPacketGatewayRuntime.packets().broadcastChatText(speaker, message, false, 0);
    }

    /** One of the coordinator's lines in party chat, logged first as 'EPQ line:' under its key. */
    private static void partyChat(AgentEpqSession session, String key, Character speaker, String message) {
        AgentEpqDialogue.logLine(session.sessionId(), key, speaker, message);
        AgentPartyGatewayRuntime.party().sendPartyChat(speaker, message);
    }

    private static int fragmentCollectorId(AgentEpqSession session) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.HUMAN)
                .mapToInt(AgentEpqMemberState::characterId).findFirst()
                .orElseGet(() -> workAgentId(session));
    }

    private static long progressSignature(AgentEpqSession session, Character leader) {
        long signature = session.phase().ordinal();
        for (AgentEpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            signature = signature * 31L + (character == null ? -1 : character.getMapId());
            if (character != null) {
                signature = signature * 31L + actions().itemCount(character, AgentEpqDefinition.POISON);
                signature = signature * 31L + actions().itemCount(character, AgentEpqDefinition.PURIFIED_POISON);
                signature = signature * 31L + actions().itemCount(character, AgentEpqDefinition.MONSTER_MARBLE);
                signature = signature * 31L + actions().itemCount(character, AgentEpqDefinition.MAGIC_STONE);
            }
        }
        if (leader.getMap() != null && AgentEpqDefinition.isEventMap(leader.getMapId())) {
            for (Monster monster : AgentMapPerception.monsters(leader.getMap()).stream()
                    .filter(Monster::isAlive).sorted(Comparator.comparingInt(Monster::getObjectId)).toList()) {
                signature = signature * 31L + monster.getObjectId();
                signature = signature * 31L + monster.getHp();
            }
            for (Reactor reactor : actions().reactors(leader).stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparingInt(Reactor::getObjectId)).toList()) {
                signature = signature * 31L + reactor.getObjectId();
                signature = signature * 31L + reactor.getState();
            }
        }
        return signature;
    }

    private static Reactor assignedStageFiveReactor(AgentEpqSession session, Character agent) {
        List<Integer> agents = agentIds(session, agent.getMap());
        return actions().reactors(agent).stream().filter(java.util.Objects::nonNull)
                .filter(Reactor::isAlive).filter(Reactor::isActive)
                .filter(reactor -> reactor.getId() == AgentEpqDefinition.STONE_BOX
                        || reactor.getId() == AgentEpqDefinition.EMPTY_BOX)
                .filter(reactor -> AgentEpqParticipationPolicy.assignedAgent(
                        session.seed(), reactor.getObjectId(), agents) == agent.getId())
                .min(Comparator.comparingDouble(reactor -> reactor.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
    }

    static boolean collectNearest(AgentRuntimeEntry entry, Character agent,
                                          Set<Integer> itemIds) {
        MapItem item = AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> !drop.isPickedUp() && itemIds.contains(drop.getItemId()))
                .filter(drop -> drop.getPosition() != null)
                .min(Comparator.comparingDouble(drop ->
                        drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (item == null) return false;
        if (!near(agent.getPosition(), item.getPosition(), LOOT_RADIUS)) {
            Point ground = actions().groundPoint(agent.getMap(), item.getPosition());
            actions().navigate(entry, ground == null ? item.getPosition() : ground, true);
        } else {
            actions().stop(entry);
            actions().lootItem(agent, item.getObjectId(), LOOT_RADIUS);
        }
        return true;
    }

    static int stageItemTotal(AgentEpqSession session, Character observer, int itemId) {
        int total = 0;
        for (AgentEpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            // A corpse cannot drop what it holds (EPQ base fix 4).
            if (character != null && character.getMap() == observer.getMap() && character.getHp() > 0) {
                total += actions().itemCount(character, itemId);
            }
        }
        for (MapItem drop : AgentMapPerception.items(observer.getMap())) {
            if (!drop.isPickedUp() && drop.getItemId() == itemId && drop.getItem() != null) {
                total += drop.getItem().getQuantity();
            }
        }
        return total;
    }

    /** Whether a member lying dead on this observer's map holds a filled bottle. */
    private static boolean corpseHoldsBottle(AgentEpqSession session, Character observer) {
        for (AgentEpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character != null && character.getMap() == observer.getMap() && character.getHp() <= 0
                    && actions().itemCount(character, AgentEpqDefinition.PURIFIED_POISON) > 0) {
                return true;
            }
        }
        return false;
    }

    private static int stageTwoSecuredBottleCount(
            AgentEpqSession session, Character observer, Reactor spine) {
        int total = spine == null ? 0 : spine.getState();
        for (AgentEpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character != null && character.getMap() == observer.getMap() && character.getHp() > 0) {
                total += actions().itemCount(character, AgentEpqDefinition.PURIFIED_POISON);
            }
        }
        if (spine != null) {
            for (MapItem drop : AgentMapPerception.items(observer.getMap())) {
                if (!drop.isPickedUp()
                        && drop.getItemId() == AgentEpqDefinition.PURIFIED_POISON
                        && drop.getPosition() != null
                        && spine.getArea().contains(drop.getPosition())
                        && drop.getItem() != null) {
                    total += drop.getItem().getQuantity();
                }
            }
        }
        return total;
    }

    static boolean hitReactor(AgentRuntimeEntry entry, Character agent, Reactor reactor,
                                      AgentEpqMemberState member, long nowMs) {
        if (!near(agent.getPosition(), reactor.getPosition(), REACTOR_RADIUS) || !actions().grounded(agent)) {
            Point target = actions().groundPoint(agent.getMap(), reactor.getPosition());
            if (target != null) actions().navigate(entry, target, true);
            return false;
        }
        actions().stop(entry);
        if (!actions().hitReactor(agent, reactor.getObjectId())) return false;
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        return true;
    }

    static boolean enterPortal(AgentRuntimeEntry entry, Character agent, int portalId,
                                       AgentEpqMemberState member, long nowMs) {
        Point portal = actions().portalPosition(agent, portalId);
        if (!near(agent.getPosition(), portal, PORTAL_RADIUS) || !actions().grounded(agent)) {
            if (portal != null) actions().navigate(entry, portal, true);
            return false;
        }
        actions().stop(entry);
        if (!epq().enterPortal(agent, portalId)) return false;
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        return true;
    }

    static boolean runNearbyNpc(AgentRuntimeEntry entry, Character agent, int npcId, int... selections) {
        Point npc = npcApproachPoint(agent, npcId);
        if (!near(agent.getPosition(), npc, NPC_RADIUS)) {
            if (npc != null) actions().navigate(entry, npc, true);
            return false;
        }
        actions().stop(entry);
        return epq().runNpc(agent, npcId, selections);
    }

    static boolean dropAt(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                  InventoryType type, int itemId,
                                  Point target, AgentEpqMemberState member, long nowMs, String logKey) {
        Point position = agent.getPosition();
        Point levelTarget = new Point(target.x, position == null ? target.y : position.y);
        Point ground = actions().groundPoint(agent.getMap(), levelTarget);
        Point approach = ground == null ? levelTarget : ground;
        if (!near(position, approach, ITEM_REACTOR_DROP_RADIUS) || !actions().grounded(agent)) {
            actions().navigate(entry, approach, true);
            return false;
        }
        actions().stop(entry);
        Point at = position == null ? null : new Point(position);
        if (dropItem(entry, agent, type, itemId, (short) 1)) {
            // The map schedules item-reactor consumption itself. Only throttle the
            // carrier's transaction; lurers and tree defenders must keep working.
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            logDrop(session, logKey, agent, at, target);
            return true;
        }
        return false;
    }

    private static void dropStackNearNpc(
            AgentEpqSession session, AgentRuntimeEntry entry, Character agent, int itemId, int npcId,
            AgentEpqMemberState member, long nowMs, String logKey) {
        Point npc = npcApproachPoint(agent, npcId);
        if (!near(agent.getPosition(), npc, NPC_RADIUS) || !actions().grounded(agent)) {
            if (npc != null) actions().navigate(entry, npc, true);
            return;
        }
        actions().stop(entry);
        int count = actions().itemCount(agent, itemId);
        Point at = agent.getPosition() == null ? null : new Point(agent.getPosition());
        if (count > 0 && dropItem(entry, agent, InventoryType.ETC, itemId,
                (short) Math.min(count, Short.MAX_VALUE))) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            logDrop(session, logKey, agent, at, npc);
        }
    }

    static Point npcApproachPoint(Character agent, int npcId) {
        Point npc = actions().npcPosition(agent, npcId);
        if (npc == null || agent == null || agent.getMap() == null) return npc;
        if (agent.getMapId() == AgentEpqDefinition.RECRUIT_MAP) {
            // Ellin stands on decorative footholds that are not connected to the
            // lobby floor. Players speak to her from below, so keep the Agent on
            // that reachable floor while enforcing a tight horizontal approach.
            Point position = agent.getPosition();
            Point floorLevel = new Point(npc.x - 48, position == null ? npc.y : position.y);
            Point lobbyGround = actions().groundPoint(agent.getMap(), floorLevel);
            return lobbyGround == null ? floorLevel : lobbyGround;
        }
        Point besideNpc = new Point(npc.x - 48, npc.y - 1);
        Point grounded = actions().groundPoint(agent.getMap(), besideNpc);
        return grounded == null ? besideNpc : grounded;
    }

    static boolean dropItem(
            AgentRuntimeEntry entry, Character agent, InventoryType type, int itemId, short quantity) {
        var inventory = agent == null ? null : agent.getInventory(type);
        Item item = inventory == null ? null : inventory.findById(itemId);
        // This helper is private to the EPQ coordinator. Its objective items must
        // remain usable even if the generic loot capability reserved the stack.
        boolean epqOwned = AgentEpqDefinition.EXCLUSIVE_ITEMS.contains(itemId);
        if (item == null || item.getQuantity() <= 0
                || (!epqOwned && !AgentInventoryReservationRuntime.mayConsume(
                entry, item, System.currentTimeMillis()))) return false;
        short dropQuantity = (short) Math.min(item.getQuantity(), Math.max(1, quantity));
        if (itemId == AgentEpqDefinition.POISON
                && agent.getMapId() == AgentEpqDefinition.STAGE_TWO_MAP) {
            Item dropped = item.copy();
            dropped.setQuantity(dropQuantity);
            AgentInventoryGatewayRuntime.inventory().removeFromSlot(
                    agent, type, item.getPosition(), dropQuantity, true);
            agent.getMap().spawnItemDrop(
                    agent, agent, dropped, new Point(agent.getPosition()), true, true);
            return true;
        }
        AgentInventoryGatewayRuntime.inventory().dropItem(
                agent, type, item.getPosition(), dropQuantity);
        return true;
    }

    static boolean near(Point first, Point second, int radius) {
        return first != null && second != null && first.distanceSq(second) <= (long) radius * radius;
    }

    private static void announce(AgentEpqSession session, AgentEpqMemberState member, Character agent,
                                 String key, String message) {
        if (member.claimAnnouncement(key)) {
            partyChat(session, key, agent, message);
        }
    }

    /**
     * The gateways are resolved per call (each is one adapter singleton), so a test can stand in for them without a
     * class loaded under its mock keeping that mock for every later test.
     */
    static PrimitiveCapabilityGateway actions() {
        return AgentPrimitiveCapabilityGatewayRuntime.gateway();
    }

    static PartyQuestGateway epq() {
        return AgentPartyQuestGatewayRuntime.partyQuest();
    }

    public static Character character(int id) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(id);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }
}
