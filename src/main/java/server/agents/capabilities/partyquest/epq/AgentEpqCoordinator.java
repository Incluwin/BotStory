package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.Item;
import client.inventory.InventoryType;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.capabilities.combat.AgentAttackPlan;
import server.agents.capabilities.combat.AgentAttackTransactionResult;
import server.agents.capabilities.combat.AgentCombatAttackRuntime;
import server.agents.capabilities.combat.AgentCombatConfig;
import server.agents.capabilities.combat.AgentCombatPlanRuntime;
import server.agents.capabilities.combat.AgentCombatSkillConstraintState;
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
    private static final PrimitiveCapabilityGateway ACTIONS = AgentPrimitiveCapabilityGatewayRuntime.gateway();
    private static final PartyQuestGateway EPQ = AgentPartyQuestGatewayRuntime.partyQuest();
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

    private AgentEpqCoordinator() { }

    public static void tickSession(AgentEpqSession session, long nowMs) {
        if (nowMs - session.startedAtMs() >= EVENT_TIMEOUT_MS && !session.terminal()) {
            session.fail("EPQ event timer expired", nowMs);
            return;
        }
        Character leader = character(session.eventLeaderId());
        if (leader == null) {
            AgentEpqWatchdogRuntime.tick(session, nowMs);
            return;
        }
        if (session.eventInstance() == null && leader.getEventInstance() != null
                && AgentEpqDefinition.isEventMap(leader.getMapId())) {
            session.bindEventInstance(leader.getEventInstance());
            session.markProgress(nowMs);
        }
        AgentEpqSession.Phase observed = phaseForMap(leader.getMapId());
        if (observed != null) session.transition(observed, nowMs);
        session.observeProgressSignature(progressSignature(session, leader), nowMs);
        AgentEpqWatchdogRuntime.tick(session, nowMs);
        if (session.phase() == AgentEpqSession.Phase.EXITING
                && session.members().stream().map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .noneMatch(member -> AgentEpqDefinition.isEventMap(member.getMapId()))) {
            session.complete(nowMs);
        }
    }

    public static void tickMember(
            AgentEpqSession session, AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentEpqMemberState member = session.member(agent.getId());
        if (member == null || member.memberType() != AgentEpqMemberState.MemberType.AGENT
                || nowMs < member.nextActionAtMs()) return;
        entry.capabilityStates().require(AgentCombatSkillConstraintState.STATE_KEY)
                .requireAttackSkill();
        if (agent.getMapId() != AgentEpqDefinition.STAGE_FOUR_MAP) {
            ACTIONS.clearHorizontalBoundary(entry);
        }
        if (session.eventInstance() != null && AgentEpqDefinition.isEventMap(agent.getMapId())
                && agent.getEventInstance() != session.eventInstance()) {
            session.fail("EPQ member entered a different event instance", nowMs);
            return;
        }

        switch (agent.getMapId()) {
            case AgentEpqDefinition.RECRUIT_MAP -> enterEvent(session, entry, agent, member, nowMs);
            case AgentEpqDefinition.ENTRANCE_MAP -> enterPortal(entry, agent, 4, member, nowMs);
            case AgentEpqDefinition.STAGE_ONE_MAP -> stageOne(entry, agent, member, nowMs);
            case AgentEpqDefinition.STAGE_TWO_MAP -> stageTwo(session, entry, agent, member, nowMs);
            case AgentEpqDefinition.STAGE_THREE_MAP -> stageThree(entry, agent, member, nowMs);
            case AgentEpqDefinition.STAGE_FOUR_MAP -> stageFour(session, entry, agent, member, nowMs);
            case AgentEpqDefinition.STAGE_FIVE_MAP -> stageFive(session, entry, agent, member, nowMs);
            case AgentEpqDefinition.BOSS_MAP -> boss(session, entry, agent, member, nowMs);
            case AgentEpqDefinition.REWARD_MAP -> reward(session, entry, agent, member, nowMs);
            default -> {
                ACTIONS.stop(entry);
                if (session.phase() == AgentEpqSession.Phase.EXITING) session.markProgress(nowMs);
            }
        }
    }

    private static void enterEvent(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                   AgentEpqMemberState member, long nowMs) {
        long chatAt = lobbyChatAt(session, agent.getId());
        if (nowMs >= chatAt && member.claimAnnouncement("lobby-entry")) {
            boolean agentLeader = agent.getId() == session.eventLeaderId();
            sendVisiblePartyChat(agent, agentLeader
                    ? "Party ready. We're entering EPQ in 5 seconds."
                    : lobbyReadyMessage(session, agent.getId()));
            if (agentLeader) member.deferUntil(nowMs + LOBBY_ENTRY_DELAY_MS);
            ACTIONS.stop(entry);
            return;
        }
        if (agent.getId() != session.eventLeaderId()) { ACTIONS.stop(entry); return; }
        if (runNearbyNpc(entry, agent, AgentEpqDefinition.ENTRY_NPC, 0)) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            session.transition(AgentEpqSession.Phase.ENTERING, nowMs);
        }
    }

    private static void stageOne(AgentRuntimeEntry entry, Character agent,
                                 AgentEpqMemberState member, long nowMs) {
        if (ACTIONS.liveMonsterCount(agent, Set.of(AgentEpqDefinition.STAGE_ONE_MOB)) == 0) {
            enterPortal(entry, agent, 3, member, nowMs);
            return;
        }
        ACTIONS.grind(entry, Set.of(AgentEpqDefinition.STAGE_ONE_MOB));
    }

    private static void stageTwo(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                 AgentEpqMemberState member, long nowMs) {
        boolean coordinator = agent.getId() == workAgentId(session);
        Point position = agent.getPosition();
        Point landing = ACTIONS.groundPoint(agent.getMap(), position);
        boolean aboveLandingFoothold = landing != null && landing.y - position.y > 40;
        if (!ACTIONS.grounded(agent) || aboveLandingFoothold) {
            ACTIONS.prepareNavigation(entry, agent);
            ACTIONS.navigate(entry, landing == null ? new Point(position) : landing, true);
            return;
        }
        Reactor spine = agent.getMap().getReactorById(AgentEpqDefinition.SPINE_REACTOR);
        if (coordinator && spine != null && spine.getState() > 0
                && spine.getState() < STAGE_TWO_BOTTLES_REQUIRED
                && session.claimAnnouncement("stage2-progress-" + spine.getState())) {
            sendVisiblePartyChat(agent, "Filled bottles applied: " + spine.getState()
                    + "/" + STAGE_TWO_BOTTLES_REQUIRED + ".");
        }
        if (spine != null && spine.getState() >= STAGE_TWO_BOTTLES_REQUIRED) {
            if (coordinator && session.claimAnnouncement(
                    "stage2-progress-" + STAGE_TWO_BOTTLES_REQUIRED)) {
                sendVisiblePartyChat(agent, "Filled bottles applied: "
                        + STAGE_TWO_BOTTLES_REQUIRED + "/" + STAGE_TWO_BOTTLES_REQUIRED
                        + ". The thorns are open.");
            }
            enterPortal(entry, agent, 3, member, nowMs);
            return;
        }
        Reactor pond = agent.getMap().getReactorById(AgentEpqDefinition.POND_REACTOR);
        if (pond == null) { ACTIONS.stop(entry); return; }
        int securedBottles = stageTwoSecuredBottleCount(session, agent, spine);
        if (securedBottles >= STAGE_TWO_BOTTLES_REQUIRED) {
            session.secureStageTwoBottles(nowMs);
        }
        if (ACTIONS.itemCount(agent, AgentEpqDefinition.PURIFIED_POISON) > 0
                && spine != null) {
            if (dropAt(session, entry, agent, InventoryType.ETC,
                    AgentEpqDefinition.PURIFIED_POISON, spine.getPosition(), member, nowMs)) {
                if (session.stageTwoBottlesSecured()) {
                    member.finishStageTwoTreeReturn();
                    sendVisiblePartyChat(agent,
                            "Filled bottle dropped at the thorns. Heading to the exit.");
                } else {
                    member.beginStageTwoTreeReturn();
                    sendVisiblePartyChat(agent,
                            "Filled bottle dropped at the thorns. Returning to the tree.");
                }
            }
            return;
        }
        if (session.stageTwoBottlesSecured()) {
            member.finishStageTwoTreeReturn();
            if (coordinator && session.claimAnnouncement("stage2-bottles-secured")) {
                sendVisiblePartyChat(agent,
                        "Four filled bottles secured. Everyone head to the right-side exit.");
            }
            Point exit = ACTIONS.portalPosition(agent, 3);
            if (exit == null) ACTIONS.stop(entry);
            else if (!near(agent.getPosition(), exit, PORTAL_RADIUS)) ACTIONS.navigate(entry, exit, true);
            else ACTIONS.stop(entry);
            return;
        }
        if (member.stageTwoReturningToTree()) {
            Point rally = stageTwoRallyPoint(session, agent, pond.getPosition());
            if (!near(agent.getPosition(), rally, 45)) {
                ACTIONS.navigate(entry, rally, true);
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
            if (!near(agent.getPosition(), lure, 45)) ACTIONS.navigate(entry, lure, true);
            else ACTIONS.stop(entry);
            return;
        }
        if (lureSide >= 0 && tagged != null) {
            member.clearStageTwoTag();
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            ACTIONS.stop(entry);
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
        int bottlesBefore = ACTIONS.itemCount(agent, AgentEpqDefinition.PURIFIED_POISON);
        if (collectNearest(entry, agent, Set.of(AgentEpqDefinition.PURIFIED_POISON))) {
            if (ACTIONS.itemCount(agent, AgentEpqDefinition.PURIFIED_POISON) > bottlesBefore) {
                sendVisiblePartyChat(agent,
                        "Filled bottle collected. Taking it to the thorns.");
            }
            return;
        }
        if (lureSide < 0) {
            Point rally = stageTwoRallyPoint(session, agent, pond.getPosition());
            if (!near(agent.getPosition(), rally, 45)) ACTIONS.navigate(entry, rally, true);
            else ACTIONS.stop(entry);
            return;
        }
        if (bugs.isEmpty()) {
            member.clearStageTwoTag();
            ACTIONS.stop(entry);
            agent.getMap().instanceMapForceRespawn();
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }

        Monster target = stageTwoSideTarget(
                bugs, pond.getPosition(), pond.getArea(), lureSide);
        if (target == null) {
            Point rally = stageTwoRallyPoint(session, agent, pond.getPosition());
            if (!near(agent.getPosition(), rally, 45)) ACTIONS.navigate(entry, rally, true);
            else ACTIONS.stop(entry);
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
        if (next == null) { ACTIONS.stop(entry); return; }
        enterPortal(entry, agent, next.getId(), member, nowMs);
    }

    private static void stageFour(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                  AgentEpqMemberState member, long nowMs) {
        // The authored ground ends at x=-1800/1530. Keep combat momentum just
        // inside those edges so a flower knockback or jump cannot send an Agent
        // below the map and trigger the global emergency recovery teleport.
        ACTIONS.setHorizontalBoundary(entry, AgentEpqDefinition.STAGE_FOUR_MAP,
                STAGE_FOUR_MIN_X, STAGE_FOUR_MAX_X);
        int monsterMarbles = ACTIONS.itemCount(agent, AgentEpqDefinition.MONSTER_MARBLE);
        int total = stageItemTotal(session, agent, AgentEpqDefinition.MONSTER_MARBLE);
        if (agent.getId() == workAgentId(session)) {
            int milestone = Math.min(20, total / 5 * 5);
            if (milestone > 0 && session.claimAnnouncement("stage4-progress-" + milestone)) {
                AgentPartyGatewayRuntime.party().sendPartyChat(agent,
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
                        if (!near(agent.getPosition(), rally, NPC_RADIUS)) ACTIONS.navigate(entry, rally, true);
                        else ACTIONS.stop(entry);
                    }
                }
            } else if (monsterMarbles > 0) {
                dropStackNearNpc(entry, agent, AgentEpqDefinition.MONSTER_MARBLE,
                        AgentEpqDefinition.STAGE_NPC, member, nowMs);
                if (ACTIONS.itemCount(agent, AgentEpqDefinition.MONSTER_MARBLE) == 0) {
                    announce(member, agent, "stage4-handoff",
                            "I left my Monster Marbles beside Ellin for our leader.");
                }
            } else {
                Point rally = npcApproachPoint(agent, AgentEpqDefinition.STAGE_NPC);
                if (!near(agent.getPosition(), rally, NPC_RADIUS)) ACTIONS.navigate(entry, rally, true);
                else ACTIONS.stop(entry);
            }
            return;
        }
        if (ACTIONS.itemCount(agent, AgentEpqDefinition.PURIFICATION_MARBLE) == 0) {
            if (runNearbyNpc(entry, agent, AgentEpqDefinition.STAGE_NPC)) member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        Monster target = flowers(agent).stream()
                .filter(flower -> captureAgentId(session, flower) == agent.getId())
                .min(Comparator.comparingDouble(mob -> mob.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (target == null) {
            ACTIONS.stop(entry);
            agent.getMap().instanceMapForceRespawn();
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            return;
        }
        if (!near(agent.getPosition(), target.getPosition(), 220)) {
            ACTIONS.navigate(entry, target.getPosition(), true);
            return;
        }
        ACTIONS.stop(entry);
        if (AgentEpqCaptureRuntime.ready(target)) {
            if (AgentEpqCaptureRuntime.capture(agent, target)) {
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
        int magicStones = ACTIONS.itemCount(agent, AgentEpqDefinition.MAGIC_STONE);
        if (magicStones > 0) {
            if (session.claimAnnouncement("stage5-stone-found")) {
                AgentPartyGatewayRuntime.party().sendPartyChat(agent,
                        "Purple Stone of Magic found. Bringing it to Yuris.");
            }
            if (agent.getId() == session.eventLeaderId()) {
                if (runNearbyNpc(entry, agent, AgentEpqDefinition.STONE_NPC)) {
                    member.deferUntil(nowMs + ACTION_RETRY_MS);
                }
            } else {
                int stonesBefore = magicStones;
                dropStackNearNpc(entry, agent, AgentEpqDefinition.MAGIC_STONE,
                        AgentEpqDefinition.STONE_NPC, member, nowMs);
                if (stonesBefore > 0
                        && ACTIONS.itemCount(agent, AgentEpqDefinition.MAGIC_STONE) == 0) {
                    session.markStageFiveStoneHandedOff(nowMs);
                    announce(member, agent, "stage5-human-handoff",
                            "I dropped the Magic Stone beside Yuris. Leader, please pick it up and continue.");
                }
            }
            return;
        }
        Reactor target = assignedStageFiveReactor(session, agent);
        if (target == null) {
            Point rally = npcApproachPoint(agent, AgentEpqDefinition.STONE_NPC);
            if (!near(agent.getPosition(), rally, NPC_RADIUS)) ACTIONS.navigate(entry, rally, true);
            else ACTIONS.stop(entry);
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
            AgentPartyGatewayRuntime.party().sendPartyChat(agent,
                    "Poison Golem ahead. Everyone resume combat!");
        }
        if (ACTIONS.liveMonsterCount(agent, AgentEpqDefinition.BOSS_MOBS) > 0) {
            ACTIONS.grind(entry, AgentEpqDefinition.BOSS_COMBAT_TARGETS);
            return;
        }
        if (session.eventInstance() == null || !session.eventInstance().isEventCleared()) {
            if (agent.getId() == session.eventLeaderId()
                    && ACTIONS.itemCount(agent, AgentEpqDefinition.MAGIC_STONE) > 0) {
                Reactor altar = agent.getMap().getReactorById(AgentEpqDefinition.ALTAR_REACTOR);
                if (altar != null) dropAt(session, entry, agent, InventoryType.ETC,
                        AgentEpqDefinition.MAGIC_STONE, altar.getPosition(), member, nowMs);
            } else {
                ACTIONS.stop(entry);
            }
            return;
        }
        AgentMapPerception.monsters(agent.getMap()).stream()
                .filter(Monster::isAlive)
                .filter(monster -> monster.getId() == AgentEpqDefinition.POST_DEATH_DUMMY)
                .findFirst().ifPresent(dummy ->
                        agent.getMap().killMonster(dummy, null, false, (short) 0));
        if (agent.getId() == fragmentCollectorId(session)
                && collectNearest(entry, agent, Set.of(AgentEpqDefinition.ALTAIRE_FRAGMENT))) return;
        if (nowMs - session.observeBossCleared(nowMs) < BOSS_LOOT_SETTLE_MS) { ACTIONS.stop(entry); return; }
        if (agent.getId() == workAgentId(session)
                && session.members().stream().anyMatch(state ->
                state.memberType() == AgentEpqMemberState.MemberType.HUMAN)) {
            announce(member, agent, "boss-human-fragment",
                    "The Altair Fragment is reserved for our human party member; loot it before exiting.");
        }
        enterPortal(entry, agent, 1, member, nowMs);
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
        if (!session.rewardHit()) { ACTIONS.stop(entry); return; }
        if (enterPortal(entry, agent, 1, member, nowMs)) {
            session.transition(AgentEpqSession.Phase.EXITING, nowMs);
        }
    }

    private static AgentEpqSession.Phase phaseForMap(int mapId) {
        return switch (mapId) {
            case AgentEpqDefinition.RECRUIT_MAP -> AgentEpqSession.Phase.PREPARING;
            case AgentEpqDefinition.ENTRANCE_MAP -> AgentEpqSession.Phase.ENTERING;
            case AgentEpqDefinition.STAGE_ONE_MAP -> AgentEpqSession.Phase.STAGE_ONE;
            case AgentEpqDefinition.STAGE_TWO_MAP -> AgentEpqSession.Phase.STAGE_TWO;
            case AgentEpqDefinition.STAGE_THREE_MAP -> AgentEpqSession.Phase.STAGE_THREE;
            case AgentEpqDefinition.STAGE_FOUR_MAP -> AgentEpqSession.Phase.STAGE_FOUR;
            case AgentEpqDefinition.STAGE_FIVE_MAP -> AgentEpqSession.Phase.STAGE_FIVE;
            case AgentEpqDefinition.BOSS_MAP -> AgentEpqSession.Phase.BOSS;
            case AgentEpqDefinition.REWARD_MAP -> AgentEpqSession.Phase.REWARD;
            default -> null;
        };
    }

    private static List<Monster> flowers(Character agent) {
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
            ACTIONS.navigate(entry, target.getPosition(), true);
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
        Point ground = ACTIONS.groundPoint(agent.getMap(), candidate);
        return ground == null ? new Point(pond.x + offset, agent.getPosition().y) : ground;
    }

    private static List<Integer> stageTwoLurerIds(AgentEpqSession session) {
        int carrierId = workAgentId(session);
        return session.members().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                .map(AgentEpqMemberState::characterId)
                .filter(characterId -> characterId != carrierId)
                .map(AgentEpqCoordinator::character)
                .filter(java.util.Objects::nonNull)
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

    private static List<Integer> agentIds(AgentEpqSession session, MapleMap map) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                .map(AgentEpqMemberState::characterId)
                .filter(id -> {
                    Character candidate = character(id);
                    return candidate != null && candidate.getMap() == map;
                })
                .sorted()
                .toList();
    }

    private static int workAgentId(AgentEpqSession session) {
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

    private static void sendVisiblePartyChat(Character speaker, String message) {
        AgentPartyGatewayRuntime.party().sendPartyChat(speaker, message);
        AgentPacketGatewayRuntime.packets().broadcastChatText(speaker, message, false, 0);
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
                signature = signature * 31L + ACTIONS.itemCount(character, AgentEpqDefinition.POISON);
                signature = signature * 31L + ACTIONS.itemCount(character, AgentEpqDefinition.PURIFIED_POISON);
                signature = signature * 31L + ACTIONS.itemCount(character, AgentEpqDefinition.MONSTER_MARBLE);
                signature = signature * 31L + ACTIONS.itemCount(character, AgentEpqDefinition.MAGIC_STONE);
            }
        }
        if (leader.getMap() != null && AgentEpqDefinition.isEventMap(leader.getMapId())) {
            for (Monster monster : AgentMapPerception.monsters(leader.getMap()).stream()
                    .filter(Monster::isAlive).sorted(Comparator.comparingInt(Monster::getObjectId)).toList()) {
                signature = signature * 31L + monster.getObjectId();
                signature = signature * 31L + monster.getHp();
            }
            for (Reactor reactor : ACTIONS.reactors(leader).stream()
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
        return ACTIONS.reactors(agent).stream().filter(java.util.Objects::nonNull)
                .filter(Reactor::isAlive).filter(Reactor::isActive)
                .filter(reactor -> reactor.getId() == AgentEpqDefinition.STONE_BOX
                        || reactor.getId() == AgentEpqDefinition.EMPTY_BOX)
                .filter(reactor -> AgentEpqParticipationPolicy.assignedAgent(
                        session.seed(), reactor.getObjectId(), agents) == agent.getId())
                .min(Comparator.comparingDouble(reactor -> reactor.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
    }

    private static boolean collectNearest(AgentRuntimeEntry entry, Character agent,
                                          Set<Integer> itemIds) {
        MapItem item = AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> !drop.isPickedUp() && itemIds.contains(drop.getItemId()))
                .filter(drop -> drop.getPosition() != null)
                .min(Comparator.comparingDouble(drop ->
                        drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (item == null) return false;
        if (!near(agent.getPosition(), item.getPosition(), LOOT_RADIUS)) {
            Point ground = ACTIONS.groundPoint(agent.getMap(), item.getPosition());
            ACTIONS.navigate(entry, ground == null ? item.getPosition() : ground, true);
        } else {
            ACTIONS.stop(entry);
            ACTIONS.lootItem(agent, item.getObjectId(), LOOT_RADIUS);
        }
        return true;
    }

    private static int stageItemTotal(AgentEpqSession session, Character observer, int itemId) {
        int total = 0;
        for (AgentEpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character != null && character.getMap() == observer.getMap()) {
                total += ACTIONS.itemCount(character, itemId);
            }
        }
        for (MapItem drop : AgentMapPerception.items(observer.getMap())) {
            if (!drop.isPickedUp() && drop.getItemId() == itemId && drop.getItem() != null) {
                total += drop.getItem().getQuantity();
            }
        }
        return total;
    }

    private static int stageTwoSecuredBottleCount(
            AgentEpqSession session, Character observer, Reactor spine) {
        int total = spine == null ? 0 : spine.getState();
        for (AgentEpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character != null && character.getMap() == observer.getMap()) {
                total += ACTIONS.itemCount(character, AgentEpqDefinition.PURIFIED_POISON);
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

    private static boolean hitReactor(AgentRuntimeEntry entry, Character agent, Reactor reactor,
                                      AgentEpqMemberState member, long nowMs) {
        if (!near(agent.getPosition(), reactor.getPosition(), REACTOR_RADIUS) || !ACTIONS.grounded(agent)) {
            Point target = ACTIONS.groundPoint(agent.getMap(), reactor.getPosition());
            if (target != null) ACTIONS.navigate(entry, target, true);
            return false;
        }
        ACTIONS.stop(entry);
        if (!ACTIONS.hitReactor(agent, reactor.getObjectId())) return false;
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        return true;
    }

    private static boolean enterPortal(AgentRuntimeEntry entry, Character agent, int portalId,
                                       AgentEpqMemberState member, long nowMs) {
        Point portal = ACTIONS.portalPosition(agent, portalId);
        if (!near(agent.getPosition(), portal, PORTAL_RADIUS) || !ACTIONS.grounded(agent)) {
            if (portal != null) ACTIONS.navigate(entry, portal, true);
            return false;
        }
        ACTIONS.stop(entry);
        if (!EPQ.enterPortal(agent, portalId)) return false;
        member.deferUntil(nowMs + ACTION_RETRY_MS);
        return true;
    }

    private static boolean runNearbyNpc(AgentRuntimeEntry entry, Character agent, int npcId, int... selections) {
        Point npc = npcApproachPoint(agent, npcId);
        if (!near(agent.getPosition(), npc, NPC_RADIUS)) {
            if (npc != null) ACTIONS.navigate(entry, npc, true);
            return false;
        }
        ACTIONS.stop(entry);
        return EPQ.runNpc(agent, npcId, selections);
    }

    private static boolean dropAt(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                  InventoryType type, int itemId,
                                  Point target, AgentEpqMemberState member, long nowMs) {
        Point position = agent.getPosition();
        Point levelTarget = new Point(target.x, position == null ? target.y : position.y);
        Point ground = ACTIONS.groundPoint(agent.getMap(), levelTarget);
        Point approach = ground == null ? levelTarget : ground;
        if (!near(position, approach, ITEM_REACTOR_DROP_RADIUS) || !ACTIONS.grounded(agent)) {
            ACTIONS.navigate(entry, approach, true);
            return false;
        }
        ACTIONS.stop(entry);
        if (dropItem(entry, agent, type, itemId, (short) 1)) {
            // The map schedules item-reactor consumption itself. Only throttle the
            // carrier's transaction; lurers and tree defenders must keep working.
            member.deferUntil(nowMs + ACTION_RETRY_MS);
            return true;
        }
        return false;
    }

    private static void dropStackNearNpc(
            AgentRuntimeEntry entry, Character agent, int itemId, int npcId,
            AgentEpqMemberState member, long nowMs) {
        Point npc = npcApproachPoint(agent, npcId);
        if (!near(agent.getPosition(), npc, NPC_RADIUS) || !ACTIONS.grounded(agent)) {
            if (npc != null) ACTIONS.navigate(entry, npc, true);
            return;
        }
        ACTIONS.stop(entry);
        int count = ACTIONS.itemCount(agent, itemId);
        if (count > 0 && dropItem(entry, agent, InventoryType.ETC, itemId,
                (short) Math.min(count, Short.MAX_VALUE))) {
            member.deferUntil(nowMs + ACTION_RETRY_MS);
        }
    }

    private static Point npcApproachPoint(Character agent, int npcId) {
        Point npc = ACTIONS.npcPosition(agent, npcId);
        if (npc == null || agent == null || agent.getMap() == null) return npc;
        if (agent.getMapId() == AgentEpqDefinition.RECRUIT_MAP) {
            // Ellin stands on decorative footholds that are not connected to the
            // lobby floor. Players speak to her from below, so keep the Agent on
            // that reachable floor while enforcing a tight horizontal approach.
            Point position = agent.getPosition();
            Point floorLevel = new Point(npc.x - 48, position == null ? npc.y : position.y);
            Point lobbyGround = ACTIONS.groundPoint(agent.getMap(), floorLevel);
            return lobbyGround == null ? floorLevel : lobbyGround;
        }
        Point besideNpc = new Point(npc.x - 48, npc.y - 1);
        Point grounded = ACTIONS.groundPoint(agent.getMap(), besideNpc);
        return grounded == null ? besideNpc : grounded;
    }

    private static boolean dropItem(
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

    private static boolean near(Point first, Point second, int radius) {
        return first != null && second != null && first.distanceSq(second) <= (long) radius * radius;
    }

    private static void announce(AgentEpqMemberState member, Character agent,
                                 String key, String message) {
        if (member.claimAnnouncement(key)) {
            AgentPartyGatewayRuntime.party().sendPartyChat(agent, message);
        }
    }

    public static Character character(int id) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(id);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }
}
