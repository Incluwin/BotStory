package server.agents.capabilities.partyquest.hpq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.inventory.AgentInventoryStateRuntime;
import server.agents.capabilities.looting.AgentGrindLootStateRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.perception.AgentMapPerception;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** HPQ-only stage coordinator using ordinary NPC, combat, loot, and reactor paths. */
final class AgentHpqCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentHpqCoordinator.class);
    private static final String QUEST_KEY = "hpq";
    /** Said by the first Agent as a stock run gathers at Tory; a companion run says nothing (voice). */
    static final String STOCK_READY_LINE = "Party ready. Everyone is here. Starting HPQ in 5 seconds.";
    /** The stock run's reminder to a human still to claim; a companion run sends no system text. */
    static final String STOCK_CLAIM_LINE = "Talk to Tory to claim your HPQ reward and return to Henesys.";
    /** Said as a stock run ends at Tory; a companion run's companions just follow their owner. */
    static final String STOCK_COMPLETE_LINE = "HPQ complete. Waiting at Tory for the next run.";
    private static final long PHASE_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.PHASE_TIMEOUT_MS");
    private static final long INTERACTION_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.INTERACTION_RETRY_MS");
    private static final int REACTOR_DROP_RADIUS_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.REACTOR_DROP_RADIUS_PX");
    private static final long FLOWER_ACTIVATION_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.FLOWER_ACTIVATION_WAIT_MS");
    private static final long PREPARATION_DELAY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.PREPARATION_DELAY_MS");
    private static final int NPC_APPROACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.NPC_APPROACH_PX");
    private static final int GATHER_RADIUS_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.GATHER_RADIUS_PX");
    private static final long DEFENSE_REACTION_MAX_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.DEFENSE_REACTION_MAX_MS");
    private static final int DEFENSE_WAKE_RADIUS_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.DEFENSE_WAKE_RADIUS_PX");
    private static final int DEFENSE_GUARD_ARRIVAL_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.DEFENSE_GUARD_ARRIVAL_PX");
    private static final long COMPANION_ENTRY_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.COMPANION_ENTRY_WAIT_MS");
    private static final int COMPANION_GUARD_SPREAD_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.COMPANION_GUARD_SPREAD_PX");
    private static final int GUARD_POST_MAX_DY_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.GUARD_POST_MAX_DY_PX");
    private static final int COMPANION_BUNNY_THREAT_RADIUS_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.COMPANION_BUNNY_THREAT_RADIUS_PX");
    private static final long HUMAN_SEED_GRACE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.HUMAN_SEED_GRACE_MS");
    private static final long SEED_HOLD_HINT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.SEED_HOLD_HINT_MS");
    private static final int CAKE_HANDOVER_BATCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.CAKE_HANDOVER_BATCH");
    private static final int CAKE_HANDOVER_NEAR_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.CAKE_HANDOVER_NEAR_PX");
    private static final int CAKE_HANDOVER_REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.CAKE_HANDOVER_REACH_PX");
    private static final long CAKE_HANDOVER_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.CAKE_HANDOVER_TIMEOUT_MS");
    private static final long CAKE_HANDOVER_LOOT_INHIBIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.CAKE_HANDOVER_LOOT_INHIBIT_MS");
    private static final long CAKE_PICKUP_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.CAKE_PICKUP_NUDGE_MS");
    private static final long CAKE_RECOLLECT_MS = recollectAfterMs(config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.CAKE_RECOLLECT_MS"));
    private static final int CAKE_RECOLLECT_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.CAKE_RECOLLECT_MAX");
    private static final int CAKE_DYING_HP_PERCENT = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.CAKE_DYING_HP_PERCENT");
    private static final long BUNNY_HURT_LINE_EVERY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.BUNNY_HURT_LINE_EVERY_MS");
    private static final int BUNNY_HURT_LINE_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.BUNNY_HURT_LINE_MAX");
    private static final long COMPANION_CLEAR_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.COMPANION_CLEAR_WAIT_MS");
    private static final long MEMBER_RECOVERY_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.MEMBER_RECOVERY_TIMEOUT_MS");
    private static final long TIMEOUT_SLACK_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.TIMEOUT_SLACK_MS");

    private AgentHpqCoordinator() {
    }

    static void tick(AgentHpqSession session, long nowMs) {
        synchronized (session) {
            if (session.terminal()) return;
            if (session.companionAdopted()) {
                companionRunTick(session, nowMs);
                return;
            }
            if (nowMs - session.lastProgressAtMs() > PHASE_TIMEOUT_MS) {
                AgentHpqTerminationService.fail(session, "No HPQ progress before the phase timeout", nowMs);
                return;
            }
            holdFullRateSimulation(session);
            Character leader = character(session.eventLeaderId());
            if (leader == null || leader.getHp() <= 0) {
                AgentHpqTerminationService.fail(session, "The HPQ event leader is unavailable", nowMs);
                return;
            }
            if (session.eventInstance() == null && hpq().event(leader) != null) {
                session.bindEventInstance(hpq().event(leader));
            }
            if (insideEvent(session.phase()) && session.eventInstance() != null
                    && hpq().event(leader) != session.eventInstance()) {
                AgentHpqTerminationService.fail(
                        session, "The HPQ event ended or the leader left its instance", nowMs);
                return;
            }
            switch (session.phase()) {
                case PREPARING -> prepare(session, leader, nowMs);
                case ENTERING -> enter(session, leader, nowMs);
                case COLLECTING_SEEDS, PLANTING_SEEDS -> seeds(session, leader, nowMs);
                case DEFENDING_BUNNY -> defend(session, leader, nowMs);
                case DELIVERING_CAKES -> deliver(session, leader, nowMs);
                case BONUS_DECISION -> bonusDecision(session, leader, nowMs);
                case BONUS_FARMING -> bonus(session, leader, nowMs);
                case CLAIMING_REWARD -> claim(session, nowMs);
                case EXITING -> exit(session, nowMs);
                default -> { }
            }
        }
    }

    private static void prepare(AgentHpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() == AgentHpqDefinition.STAGE_MAP && hpq().event(leader) != null) {
            transition(session, AgentHpqSession.Phase.ENTERING, nowMs);
            return;
        }
        if (leader.getMapId() != AgentHpqDefinition.RECRUIT_MAP) return;
        Point npc = actions().npcPosition(leader, AgentHpqDefinition.ENTRY_NPC);
        if (npc == null) return;
        boolean allGathered = true;
        for (AgentHpqMemberState member : session.members()) {
            Character participant = character(member.characterId());
            if (participant == null || participant.getMapId() != AgentHpqDefinition.RECRUIT_MAP) {
                allGathered = false;
                continue;
            }
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry == null) {
                if (!near(participant.getPosition(), npc, GATHER_RADIUS_PX)) allGathered = false;
                continue;
            }
            Point target = lobbyApproachPoint(session, member, participant, npc);
            if (!near(participant.getPosition(), target, NPC_APPROACH_PX)) {
                actions().navigate(entry, target, true);
                allGathered = false;
            }
        }
        long previousReadyAtMs = session.readyAtMs();
        long readyAtMs = preparationReadyAtMs(
                allGathered, previousReadyAtMs, nowMs, PREPARATION_DELAY_MS);
        session.setReadyAtMs(readyAtMs);
        if (!allGathered) return;
        if (previousReadyAtMs == 0L) announceReady(session);
        if (nowMs >= readyAtMs) transition(session, AgentHpqSession.Phase.ENTERING, nowMs);
    }

    private static void enter(AgentHpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() == AgentHpqDefinition.RECRUIT_MAP) {
            AgentRuntimeEntry leaderEntry = AgentRuntimeRegistry.findByAgentCharacterId(leader.getId());
            if (leaderEntry == null) return;
            Point npc = actions().npcPosition(leader, AgentHpqDefinition.ENTRY_NPC);
            if (npc != null && !near(leader.getPosition(), npc, NPC_APPROACH_PX)) {
                actions().navigate(leaderEntry, npc, true);
                return;
            }
            if (hpq().runNpc(leader, AgentHpqDefinition.ENTRY_NPC, 0)) {
                session.markProgress(nowMs);
            }
            return;
        }
        if (leader.getMapId() != AgentHpqDefinition.STAGE_MAP || hpq().event(leader) == null) return;
        boolean together = session.members().stream().allMatch(member -> {
            Character character = character(member.characterId());
            return character != null && character.getMapId() == AgentHpqDefinition.STAGE_MAP
                    && hpq().sameEvent(leader, character);
        });
        if (!together) return;
        session.bindEventInstance(hpq().event(leader));
        transition(session, AgentHpqSession.Phase.COLLECTING_SEEDS, nowMs);
    }

    static long preparationReadyAtMs(
            boolean allGathered, long currentReadyAtMs, long nowMs, long delayMs) {
        if (!allGathered) return 0L;
        return currentReadyAtMs == 0L ? nowMs + Math.max(0L, delayMs) : currentReadyAtMs;
    }

    private static Point lobbyApproachPoint(
            AgentHpqSession session, AgentHpqMemberState member, Character character, Point npc) {
        long mixed = session.seed() + member.characterId() * 131L;
        int offset = 24 + Math.floorMod(mixed, Math.max(1, NPC_APPROACH_PX));
        Point target = new Point(npc.x - offset, npc.y);
        Point grounded = actions().groundPoint(character.getMap(), target);
        return grounded == null ? target : grounded;
    }

    private static void announceReady(AgentHpqSession session) {
        Character narrator = character(session.executionAgentId());
        if (narrator != null) {
            String line = voice(session, null, STOCK_READY_LINE);
            if (line != null) AgentPacketGatewayRuntime.packets().broadcastChatText(narrator, line, false, 0);
        }
    }

    private static void seeds(AgentHpqSession session, Character leader, long nowMs) {
        if (eventStage(leader) >= AgentHpqDefinition.seedBeds().size()) {
            assignDefenseRoles(session);
            transition(session, AgentHpqSession.Phase.DEFENDING_BUNNY, nowMs);
            return;
        }
        Set<Integer> seedIds = AgentHpqDefinition.seedBeds().stream()
                .map(AgentHpqDefinition.SeedBed::seedItemId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<MapItem> floorSeeds = naturalSeedDrops(leader.getMap().getDroppedItems());
        Set<Integer> assignedDropIds = new HashSet<>();
        boolean hasSeed = false;
        int collectorIndex = 0;
        int seedlessAgents = (int) session.members().stream()
                .filter(member -> member.memberType() == AgentHpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(agent -> agent.getMapId() == AgentHpqDefinition.STAGE_MAP)
                .filter(agent -> seedIds.stream().noneMatch(
                        seedId -> agent.getItemQuantity(seedId, false) > 0))
                .count();
        int sourceCollectorCount = Math.max(1,
                seedlessAgents - Math.min(seedlessAgents, floorSeeds.size()));
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (agent == null || entry == null || agent.getMapId() != AgentHpqDefinition.STAGE_MAP) continue;
            int carriedSeed = seedIds.stream()
                    .filter(seedId -> agent.getItemQuantity(seedId, false) > 0)
                    .findFirst().orElse(0);
            if (carriedSeed > 0) {
                hasSeed = true;
                AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
                member.assign(AgentHpqMemberState.Role.SEED_PLANTER, carriedSeed);
                plant(session, entry, agent, member, carriedSeed, nowMs);
            } else {
                member.assign(AgentHpqMemberState.Role.SEED_COLLECTOR, member.assignedSeedItemId());
                MapItem floorSeed = nearestUnassignedDrop(
                        agent, floorSeeds, assignedDropIds);
                if (floorSeed != null) {
                    assignedDropIds.add(floorSeed.getObjectId());
                    pursueLoot(entry, floorSeed);
                } else {
                    AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
                    collectSeed(entry, agent, member, collectorIndex++, sourceCollectorCount,
                            AgentHpqDefinition.SEED_SOURCE_REACTORS, nowMs);
                }
            }
        }
        if (hasSeed && session.phase() == AgentHpqSession.Phase.COLLECTING_SEEDS) {
            transition(session, AgentHpqSession.Phase.PLANTING_SEEDS, nowMs);
        }
    }

    private static void collectSeed(AgentRuntimeEntry entry, Character agent,
                                    AgentHpqMemberState member, int collectorIndex,
                                    int collectorCount, Set<Integer> sourceReactorIds, long nowMs) {
        if (nowMs < member.nextActionAtMs()) return;
        List<Reactor> sources = new ArrayList<>();
        for (Reactor reactor : actions().reactors(agent)) {
            if (reactor != null && reactor.isAlive() && reactor.isActive()
                    && sourceReactorIds.contains(reactor.getId())) {
                sources.add(reactor);
            }
        }
        if (sources.isEmpty()) return;
        sources.sort(Comparator.comparingInt((Reactor reactor) -> reactor.getPosition().x)
                .thenComparingInt(Reactor::getObjectId));
        Reactor target = sources.get(distributedSourceIndex(
                collectorIndex, collectorCount, sources.size()));
        if (!near(agent.getPosition(), target.getPosition(), REACTOR_DROP_RADIUS_PX)) {
            actions().navigate(entry, target.getPosition(), true);
            return;
        }
        actions().stop(entry);
        if (actions().hitReactor(agent, target.getObjectId())) {
            member.deferUntil(nowMs + Math.max(500L, INTERACTION_RETRY_MS));
        }
    }

    static int distributedSourceIndex(int collectorIndex, int collectorCount, int sourceCount) {
        if (collectorCount <= 0 || sourceCount <= 0) {
            throw new IllegalArgumentException("positive HPQ collector and source counts are required");
        }
        int ordinal = Math.floorMod(collectorIndex, collectorCount);
        long numerator = (2L * ordinal + 1L) * sourceCount;
        return Math.min(sourceCount - 1, (int) (numerator / (2L * collectorCount)));
    }

    /** True when it dropped a seed on its bed this tick. */
    private static boolean plant(AgentHpqSession session, AgentRuntimeEntry entry, Character agent,
                                 AgentHpqMemberState member, int seedItemId, long nowMs) {
        if (nowMs < member.nextActionAtMs()) return false;
        AgentHpqDefinition.SeedBed bed = AgentHpqDefinition.seedBed(seedItemId);
        Point reactor = actions().reactors(agent).stream()
                .filter(Reactor::isAlive)
                .filter(candidate -> candidate.getId() == bed.reactorId())
                .filter(candidate -> bed.reactorName().equalsIgnoreCase(candidate.getName()))
                .min(Comparator.comparingDouble(candidate ->
                        candidate.getPosition().distance(agent.getPosition())))
                .map(candidate -> new Point(candidate.getPosition()))
                .orElse(null);
        if (reactor == null) return false;
        // Reactor.wz item bounds are x [-19,20], y [-42,16]; stay just inside them.
        if (!near(agent.getPosition(), reactor,
                AgentHpqDefinition.FLOWER_DROP_X_PX, AgentHpqDefinition.FLOWER_DROP_Y_PX)) {
            actions().navigate(entry, reactor, true);
            return false;
        }
        actions().stop(entry);
        if (AgentScriptItemActionService.dropItem(
                entry, InventoryType.ETC, seedItemId, (short) 1)) {
            inhibitSeedPickupDuringFlowerActivation(session);
            member.deferUntil(nowMs + Math.max(500L, INTERACTION_RETRY_MS));
            return true;
        }
        return false;
    }

    static List<MapItem> naturalSeedDrops(Collection<MapItem> drops) {
        if (drops == null || drops.isEmpty()) return List.of();
        return drops.stream()
                .filter(java.util.Objects::nonNull)
                .filter(drop -> !drop.isPickedUp() && !drop.isPlayerDrop())
                .filter(drop -> drop.getMeso() <= 0 && AgentHpqDefinition.isSeed(drop.getItemId()))
                .sorted(Comparator.comparingInt(MapItem::getObjectId))
                .toList();
    }

    private static MapItem nearestUnassignedDrop(
            Character agent, List<MapItem> drops, Set<Integer> assignedDropIds) {
        if (agent == null || agent.getPosition() == null) return null;
        return drops.stream()
                .filter(drop -> !assignedDropIds.contains(drop.getObjectId()))
                .min(Comparator
                        .comparingDouble((MapItem drop) ->
                                drop.getPosition().distanceSq(agent.getPosition()))
                        .thenComparingInt(MapItem::getObjectId))
                .orElse(null);
    }

    private static void pursueLoot(AgentRuntimeEntry entry, MapItem drop) {
        actions().grind(entry, Set.of());
        AgentGrindLootStateRuntime.setObjectiveLootTarget(entry, drop);
    }

    private static void inhibitSeedPickupDuringFlowerActivation(AgentHpqSession session) {
        int inhibitMs = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, FLOWER_ACTIVATION_WAIT_MS));
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) {
                AgentInventoryStateRuntime.setLootInhibitMs(entry,
                        Math.max(AgentInventoryStateRuntime.lootInhibitMs(entry), inhibitMs));
            }
        }
    }

    private static void assignDefenseRoles(AgentHpqSession session) {
        for (AgentHpqMemberState member : session.members()) {
            AgentGrindLootStateRuntime.clearObjectiveLootTarget(
                    AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()));
            if (member.characterId() == session.eventLeaderId()) {
                member.assign(AgentHpqMemberState.Role.EVENT_LEADER, 0);
            } else {
                member.assign(AgentHpqMemberState.Role.BUNNY_GUARD, 0);
            }
            if (member.memberType() == AgentHpqMemberState.MemberType.AGENT) {
                member.deferUntil(0L);
            }
        }
    }

    private static void defend(AgentHpqSession session, Character leader, long nowMs) {
        guardBunny(session, leader, nowMs, false);
        if (leader.getItemQuantity(AgentHpqDefinition.RICE_CAKE, false)
                >= AgentHpqDefinition.REQUIRED_RICE_CAKES) {
            transition(session, AgentHpqSession.Phase.DELIVERING_CAKES, nowMs);
        }
    }

    /**
     * Every Agent guards the Moon Bunny from its post. While the cakes are delivered the loop keeps running for every
     * Agent but the event leader, who takes them to Growlie (an Agent leader is the only one that loots cakes): live
     * (2026-10-03) the guards stopped once the leader held ten, wandered to the upper floors, and bats killed the Moon
     * Bunny while he stood at Growlie with all ten, which exiled the whole party.
     */
    private static void guardBunny(AgentHpqSession session, Character leader, long nowMs, boolean delivering) {
        Set<Integer> monsters = stageMonsters(leader);
        List<Monster> liveHostiles = AgentMapPerception.monsters(leader.getMap()).stream()
                .filter(java.util.Objects::nonNull)
                .filter(Monster::isAlive)
                .filter(monster -> monsters.contains(monster.getId()))
                .toList();
        boolean hostilesPresent = !liveHostiles.isEmpty();
        if (session.observeDefenseHostiles(hostilesPresent)) {
            assignDefenseReactionDelays(session, nowMs);
        }
        List<MapItem> cakes = leader.getMap().getDroppedItems().stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.getMeso() <= 0
                        && drop.getItemId() == AgentHpqDefinition.RICE_CAKE)
                .toList();
        List<AgentHpqMemberState> agents = session.members().stream()
                .filter(member -> member.memberType() == AgentHpqMemberState.MemberType.AGENT)
                .toList();
        boolean centerReserved = agents.stream().anyMatch(
                member -> member.characterId() == session.eventLeaderId());
        int ordinaryCount = agents.size() - (centerReserved ? 1 : 0);
        int ordinaryOrdinal = 0;
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (agent == null || entry == null) continue;

            boolean eventLeader = centerReserved
                    && member.characterId() == session.eventLeaderId();
            Point authoredPost = eventLeader
                    ? AgentHpqDefinition.defenseGuardPoints().get(2)
                    : defenseGuardPoint(ordinaryOrdinal++, ordinaryCount, centerReserved);
            if (delivering && eventLeader) continue;
            Point guardPost = actions().groundPoint(agent.getMap(), authoredPost);
            if (guardPost == null) guardPost = authoredPost;
            boolean hostileClose = hostileNear(agent, liveHostiles, DEFENSE_WAKE_RADIUS_PX);
            if (!near(agent.getPosition(), guardPost, DEFENSE_GUARD_ARRIVAL_PX)
                    && !hostileClose) {
                AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
                actions().navigate(entry, guardPost, true);
                continue;
            }
            if (hostilesPresent && nowMs < member.nextActionAtMs() && !hostileClose) {
                actions().stop(entry);
                continue;
            }

            MapItem cake = member.characterId() == session.eventLeaderId()
                    ? nearestUnassignedDrop(agent, cakes, Set.of()) : null;
            if (hostilesPresent || cake != null) {
                actions().grind(entry, hostilesPresent ? monsters : Set.of());
                if (cake != null) AgentGrindLootStateRuntime.setObjectiveLootTarget(entry, cake);
                else AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
            } else {
                AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
                actions().stop(entry);
            }
        }
    }

    private static void assignDefenseReactionDelays(AgentHpqSession session, long nowMs) {
        int agentOrdinal = 0;
        long waveSeed = session.seed()
                ^ (session.defenseWaveOrdinal() * 0x94D049BB133111EBL);
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            member.deferUntil(nowMs + defenseReactionDelayMs(
                    waveSeed, member.characterId(), agentOrdinal++, DEFENSE_REACTION_MAX_MS));
        }
    }

    static Point defenseGuardPoint(
            int ordinal, int participantCount, boolean centerReserved) {
        List<Point> points = new ArrayList<>(AgentHpqDefinition.defenseGuardPoints());
        if (centerReserved) points.remove(2);
        int index = distributedSourceIndex(ordinal, Math.max(1, participantCount), points.size());
        return new Point(points.get(index));
    }

    static long defenseReactionDelayMs(
            long seed, int characterId, int ordinal, long maximumDelayMs) {
        if (maximumDelayMs <= 0L || Math.floorMod(ordinal, 3) == 0) return 0L;
        long minimumDelayMs = Math.min(450L, maximumDelayMs);
        long span = maximumDelayMs - minimumDelayMs + 1L;
        long mixed = seed ^ (characterId * 0x9E3779B97F4A7C15L)
                ^ (ordinal * 0xBF58476D1CE4E5B9L);
        return minimumDelayMs + Math.floorMod(mixed, span);
    }

    private static boolean hostileNear(
            Character agent, List<Monster> hostiles, int radiusPx) {
        if (agent == null || agent.getPosition() == null || radiusPx < 0) return false;
        long radiusSquared = (long) radiusPx * radiusPx;
        for (Monster monster : hostiles) {
            if (monster != null && monster.getPosition() != null
                    && monster.getPosition().distanceSq(agent.getPosition()) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private static void deliver(AgentHpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() == AgentHpqDefinition.CLEAR_MAP) {
            session.freezeRewardEligibility();
            transition(session, AgentHpqSession.Phase.BONUS_DECISION, nowMs);
            return;
        }
        guardBunny(session, leader, nowMs, true);
        AgentRuntimeEntry leaderEntry = AgentRuntimeRegistry.findByAgentCharacterId(leader.getId());
        if (leaderEntry == null) return;
        Point growlie = actions().npcPosition(leader, AgentHpqDefinition.GROWLIE_NPC);
        if (growlie != null && !near(leader.getPosition(), growlie, 80)) {
            actions().navigate(leaderEntry, growlie, true);
            return;
        }
        hpq().runNpc(leader, AgentHpqDefinition.GROWLIE_NPC, 1);
    }

    private static void bonusDecision(AgentHpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() == AgentHpqDefinition.BONUS_MAP) {
            transition(session, AgentHpqSession.Phase.BONUS_FARMING, nowMs);
            return;
        }
        boolean agentLeader = AgentRuntimeRegistry.findByAgentCharacterId(leader.getId()) != null;
        if (!agentLeader) {
            boolean waiting = leader.getMapId() == AgentHpqDefinition.CLEAR_MAP
                    && nowMs - session.phaseEnteredAtMs() < AgentHpqBonusPolicy.humanDecisionMs();
            if (!waiting) transition(session, AgentHpqSession.Phase.CLAIMING_REWARD, nowMs);
            return;
        }
        if (session.bonusMode() == AgentHpqSession.BonusMode.ENTER) {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(leader.getId());
            Point tommy = actions().npcPosition(leader, AgentHpqDefinition.TOMMY_NPC);
            if (entry != null && tommy != null && !near(leader.getPosition(), tommy, 80)) {
                actions().navigate(entry, tommy, true);
                return;
            }
            hpq().runNpc(leader, AgentHpqDefinition.TOMMY_NPC);
            return;
        }
        transition(session, AgentHpqSession.Phase.CLAIMING_REWARD, nowMs);
    }

    private static void bonus(AgentHpqSession session, Character leader, long nowMs) {
        boolean exit = nowMs - session.phaseEnteredAtMs() >= AgentHpqBonusPolicy.dwellMs()
                || leader.getMapId() != AgentHpqDefinition.BONUS_MAP;
        boolean agentRemaining = false;
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (agent == null || entry == null || agent.getMapId() != AgentHpqDefinition.BONUS_MAP) continue;
            agentRemaining = true;
            if (exit) hpq().runNpc(agent, AgentHpqDefinition.TOMMY_NPC);
            else actions().grind(entry, actions().configuredMonsterSpawnIds(agent));
        }
        if (!agentRemaining) transition(session, AgentHpqSession.Phase.CLAIMING_REWARD, nowMs);
    }

    private static void claim(AgentHpqSession session, long nowMs) {
        session.freezeRewardEligibility();
        boolean nonLeaderRewardsResolved = session.members().stream()
                .filter(member -> member.characterId() != session.eventLeaderId())
                .allMatch(AgentHpqMemberState::rewardResolved);
        for (AgentHpqMemberState member : session.members()) {
            if (member.rewardResolved()) continue;
            Character participant = character(member.characterId());
            if (participant == null) continue;
            if (participant.getMapId() == AgentHpqDefinition.CLEAR_MAP
                    || participant.getMapId() == AgentHpqDefinition.REWARD_EXIT_MAP) {
                if (member.memberType() == AgentHpqMemberState.MemberType.HUMAN) {
                    String line = voice(session, null, STOCK_CLAIM_LINE);
                    if (line != null && nowMs >= member.nextActionAtMs()) {
                        participant.dropMessage(6, line);
                        member.deferUntil(nowMs + 5_000L);
                    }
                    continue;
                }
                if (member.characterId() == session.eventLeaderId() && !nonLeaderRewardsResolved) {
                    continue;
                }
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(
                        member.characterId());
                Point tory = actions().npcPosition(participant, AgentHpqDefinition.ENTRY_NPC);
                if (entry != null && tory != null
                        && !near(participant.getPosition(), tory, NPC_APPROACH_PX)) {
                    actions().navigate(entry, tory, true);
                } else if (tory != null) {
                    hpq().runNpc(participant, AgentHpqDefinition.ENTRY_NPC);
                }
            }
        }
        if (session.allRewardsResolved()) transition(session, AgentHpqSession.Phase.EXITING, nowMs);
    }

    private static void exit(AgentHpqSession session, long nowMs) {
        boolean allGathered = true;
        Character narrator = null;
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (agent == null || entry == null || agent.getMapId() != AgentHpqDefinition.RECRUIT_MAP) {
                allGathered = false;
                continue;
            }
            if (narrator == null) narrator = agent;
            Point tory = actions().npcPosition(agent, AgentHpqDefinition.ENTRY_NPC);
            if (tory == null) {
                allGathered = false;
                continue;
            }
            Point target = lobbyApproachPoint(session, member, agent, tory);
            if (!near(agent.getPosition(), target, NPC_APPROACH_PX)) {
                actions().navigate(entry, target, true);
                allGathered = false;
            }
        }
        if (!allGathered) return;
        for (AgentHpqMemberState member : session.members()) {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry != null) actions().stop(entry);
        }
        String line = voice(session, null, STOCK_COMPLETE_LINE);
        if (narrator != null && line != null) {
            AgentPacketGatewayRuntime.packets().broadcastChatText(narrator, line, false, 0);
        }
        AgentHpqTerminationService.complete(session, nowMs);
    }

    // ---- companion runs ----

    /**
     * A companion run (a player leading his own companions, adopted through AgentHpqAdmissionService.admit): he plays,
     * they play beside him and talk like players, and nothing they say is a status line. Stock, background and
     * GM-observed runs never come here.
     */
    private static void companionRunTick(AgentHpqSession session, long nowMs) {
        if (nowMs - session.lastProgressAtMs() > PHASE_TIMEOUT_MS) {
            fail(session, "No HPQ progress before the phase timeout",
                    AgentHpqParting.of(AgentHpqParting.Kind.TOO_LONG), nowMs);
            return;
        }
        holdFullRateSimulation(session);
        Character leader = character(session.eventLeaderId());
        if (pastTheClear(session)) {
            // After Growlie's clear nothing about the leader ends the run: once he is let go they claim and go.
            releaseLeaderAfterClear(session, leader);
            Character playing = session.leaderReleased() ? null : leader;
            if (playing == null && insideEvent(session.phase())) {
                companionCleared(session, nowMs);
            } else {
                companionTick(session, playing, nowMs);
            }
            return;
        }
        if (leader == null) {
            fail(session, "The HPQ event leader went offline", new AgentHpqParting(AgentHpqParting.Kind.GONE,
                    session.eventLeaderId(), memberName(session, session.eventLeaderId())), nowMs);
            return;
        }
        if (leader.getHp() <= 0) {
            fail(session, "The HPQ event leader was defeated",
                    AgentHpqParting.of(AgentHpqParting.Kind.DEFEATED, leader), nowMs);
            return;
        }
        if (session.eventInstance() == null && hpq().event(leader) != null) {
            session.bindEventInstance(hpq().event(leader));
        }
        if (insideEvent(session.phase()) && session.eventInstance() != null
                && hpq().event(leader) != session.eventInstance()) {
            fail(session, "The HPQ event ended or the leader left its instance",
                    AgentHpqParting.of(endedCause(session, nowMs)), nowMs);
            return;
        }
        // Departures and deaths come before the stage logic; a downed member pauses nothing (the Bunny's clock runs).
        if (insideEvent(session.phase())
                && (!reconcileLiveParty(session, nowMs) || !membersAlive(session, leader, nowMs))) {
            return;
        }
        companionTick(session, leader, nowMs);
    }

    /** A companion run's phase switch, once the run-level checks let it go on; a null leader has been let go. */
    private static void companionTick(AgentHpqSession session, Character leader, long nowMs) {
        switch (session.phase()) {
            case PREPARING -> companionPrepare(session, leader, nowMs);
            case ENTERING -> companionEnter(session, leader, nowMs);
            case COLLECTING_SEEDS, PLANTING_SEEDS -> companionSeeds(session, leader, nowMs);
            case DEFENDING_BUNNY -> companionDefend(session, leader, nowMs, false);
            case DELIVERING_CAKES -> companionDefend(session, leader, nowMs, true);
            case BONUS_DECISION -> companionAfterClear(session, leader, nowMs);
            case BONUS_FARMING -> companionBonus(session, leader, nowMs);
            case CLAIMING_REWARD -> companionClaim(session, leader, nowMs);
            case EXITING -> companionExit(session, nowMs);
            default -> { }
        }
    }

    /**
     * He clicks Tory himself: nobody walks anywhere and nobody announces anything. The run goes on once he stands in
     * the event on Primrose Hill, and ends without a word if he has not after COMPANION_ENTRY_WAIT_MS (the 30 s the
     * adoption promises).
     */
    private static void companionPrepare(AgentHpqSession session, Character leader, long nowMs) {
        if (leader.getMapId() == AgentHpqDefinition.STAGE_MAP && hpq().event(leader) != null) {
            session.markEnteredEvent(nowMs);
            transition(session, AgentHpqSession.Phase.ENTERING, nowMs);
            return;
        }
        if (nowMs - session.phaseEnteredAtMs() >= COMPANION_ENTRY_WAIT_MS) {
            fail(session, "The companion run's leader did not take the party into HPQ within the entry wait",
                    AgentHpqParting.SILENT, nowMs);
        }
    }

    /**
     * Waits for every member on Primrose Hill in his event. After COMPANION_ENTRY_WAIT_MS the run goes on without
     * the absent ones (taken out of the run and handed back) while at least three are inside, and ends without a word
     * otherwise.
     */
    private static void companionEnter(AgentHpqSession session, Character leader, long nowMs) {
        long waitedMs = nowMs - session.phaseEnteredAtMs();
        EventInstanceManager event = hpq().event(leader);
        if (leader.getMapId() != AgentHpqDefinition.STAGE_MAP || event == null) {
            if (waitedMs >= COMPANION_ENTRY_WAIT_MS) {
                fail(session, "The companion run's leader left before the party was inside HPQ",
                        AgentHpqParting.SILENT, nowMs);
            }
            return;
        }
        if (session.eventInstance() == null) session.bindEventInstance(event);
        List<AgentHpqMemberState> absent = new ArrayList<>();
        for (AgentHpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character == null || character.getMapId() != AgentHpqDefinition.STAGE_MAP
                    || !hpq().sameEvent(leader, character)) {
                absent.add(member);
            }
        }
        int members = session.memberCount();
        switch (AgentHpqCompanionPlay.entering(members - absent.size(), members, waitedMs,
                COMPANION_ENTRY_WAIT_MS, AgentHpqDefinition.MINIMUM_PARTY_SIZE)) {
            case ENTERED -> companionEnterSeeds(session, nowMs);
            case EJECT_ABSENT -> {
                for (AgentHpqMemberState member : absent) departMember(session, member, "did not enter", nowMs);
                if (carriesOn(session, AgentHpqParting.SILENT, nowMs)) companionEnterSeeds(session, nowMs);
            }
            case GIVE_UP -> fail(session, "Fewer than three of the companion run entered HPQ within the entry wait",
                    AgentHpqParting.SILENT, nowMs);
            default -> { }
        }
    }

    private static void companionEnterSeeds(AgentHpqSession session, long nowMs) {
        transition(session, AgentHpqSession.Phase.COLLECTING_SEEDS, nowMs);
        String potions = AgentHpqTerminationService.potions(session);
        session.setPotionsAtEntry(potions);
        log.info("HPQ potions: session={} start={}", session.sessionId(), potions);
        coordinate(session, narrator(session), "seeds-start", AgentHpqCompanionPlay.SEEDS_START);
    }

    /**
     * Takes one member out of the run and both indexes (out of the event first, while still in it) and hands an Agent
     * back to its owner; the collector role moves on if it was this member's.
     */
    private static void departMember(AgentHpqSession session, AgentHpqMemberState member, String reason, long nowMs) {
        int id = member.characterId();
        Character character = character(id);
        EventInstanceManager event = session.eventInstance();
        if (character != null && event != null && hpq().event(character) == event) event.exitPlayer(character);
        AgentHpqFullRateSimulation.apply(member, AgentRuntimeRegistry.findByAgentCharacterId(id), false);
        // A companion killed outright still held its cakes as it went down; the respawn's unregister took them.
        int held = character == null ? -1 : character.getItemQuantity(AgentHpqDefinition.RICE_CAKE, false);
        log.info("HPQ cakes lost with member: session={} member={} cakes={}", session.sessionId(), id,
                member.cakesWhenDown() > 0 ? Math.max(held, member.cakesWhenDown()) : held);
        log.warn("HPQ member left the run and was ejected: session={} member={} type={} phase={} reason={} "
                        + "remainingBefore={}", session.sessionId(), id, member.memberType(), session.phase(), reason,
                session.memberCount());
        session.removeMember(id);
        AgentHpqSessionRegistry.unindexMember(session, id);
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(session.operatorId());
        if (engagement != null) AgentPartyQuestEngagementRegistry.removeAndUnindexMember(engagement, id, nowMs);
        if (member.memberType() == AgentHpqMemberState.MemberType.AGENT) {
            AgentPartyQuestLifecycleRuntime.recoverDetachedMember(id, QUEST_KEY, nowMs);
        }
        if (session.cakeCollectorId() == id) session.setCakeCollectorId(0);
    }

    /** False when the departures ended the run: fewer than three left, or no companion (the humans carry on). */
    private static boolean carriesOn(AgentHpqSession session, AgentHpqParting belowThree, long nowMs) {
        if (session.memberCount() < AgentHpqDefinition.MINIMUM_PARTY_SIZE) {
            fail(session, "The companion run fell below three members", belowThree, nowMs);
            return false;
        }
        if (session.members().stream()
                .noneMatch(member -> member.memberType() == AgentHpqMemberState.MemberType.AGENT)) {
            AgentHpqTerminationService.detach(session, "every companion left; the humans carry on", nowMs);
            return false;
        }
        return true;
    }

    /**
     * Seeds as a player party plays them: only the colours a pad still needs are fetched (a pad with a seed of its
     * colour dropped in its box within FLOWER_ACTIVATION_WAIT_MS is on its way), a colour a companion carries is
     * planted by it, and one a human carries is his until SEED_HOLD_HINT_MS plus HUMAN_SEED_GRACE_MS have passed
     * (C3 tells the leader which pad). Natural seeds of colours no pad needs are left on the floor.
     */
    private static void companionSeeds(AgentHpqSession session, Character leader, long nowMs) {
        int beds = AgentHpqDefinition.seedBeds().size();
        int stage = eventStage(leader);
        if (stage >= beds) {
            assignDefenseRoles(session);
            transition(session, AgentHpqSession.Phase.DEFENDING_BUNNY, nowMs);
            return;
        }
        if (stage * 2 >= beds) {
            coordinate(session, narrator(session), "seeds-half", AgentHpqCompanionPlay.HALF_THE_FLOWERS);
        }
        List<MapItem> drops = droppedItems(leader);
        Map<Integer, Reactor> pads = moonflowers(leader);
        List<AgentHpqCompanionPlay.Pad> padStates = new ArrayList<>();
        for (AgentHpqDefinition.SeedBed bed : AgentHpqDefinition.seedBeds()) {
            Reactor pad = pads.get(bed.seedItemId());
            if (pad == null) continue;
            long latestDropAtMs = drops.stream()
                    .filter(drop -> drop.isPlayerDrop() && !drop.isPickedUp()
                            && drop.getItemId() == bed.seedItemId()
                            && near(drop.getPosition(), pad.getPosition(),
                                    AgentHpqDefinition.FLOWER_DROP_X_PX, AgentHpqDefinition.FLOWER_DROP_Y_PX))
                    .mapToLong(MapItem::getDropTime).max().orElse(0L);
            padStates.add(new AgentHpqCompanionPlay.Pad(bed.seedItemId(), pad.isActive(),
                    AgentHpqCompanionPlay.padPending(session.padPlantedAtMs(bed.reactorId()), latestDropAtMs,
                            nowMs, FLOWER_ACTIVATION_WAIT_MS)));
        }
        Set<Integer> needed = AgentHpqCompanionPlay.neededSeeds(padStates);
        Set<Integer> carriedByAgents = new HashSet<>();
        Set<Integer> coveredByHumans = new HashSet<>();
        for (AgentHpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            boolean onStage = character != null && character.getMapId() == AgentHpqDefinition.STAGE_MAP;
            if (member.memberType() == AgentHpqMemberState.MemberType.AGENT) {
                // A downed companion plants nothing: its colour is fetched again.
                int carried = onStage && character.getHp() > 0 && !member.down() ? AgentHpqCompanionPlay.carriedSeed(
                        seed -> character.getItemQuantity(seed, false) > 0, needed) : 0;
                if (carried > 0) carriedByAgents.add(carried);
                continue;
            }
            List<Integer> carries = !onStage ? List.of() : needed.stream()
                    .filter(seed -> character.getItemQuantity(seed, false) > 0).toList();
            session.keepHumanSeeds(member.characterId(), carries);
            for (int seed : carries) {
                long sinceMs = session.humanSeedHeldSinceMs(member.characterId(), seed, nowMs);
                if (AgentHpqCompanionPlay.humanSeedCovers(sinceMs, nowMs, SEED_HOLD_HINT_MS, HUMAN_SEED_GRACE_MS)) {
                    coveredByHumans.add(seed);
                }
                Reactor pad = pads.get(seed);
                String position = pad == null ? null : AgentHpqDefinition.padPositionName(pad.getPosition());
                if (member.characterId() == session.eventLeaderId() && position != null
                        && AgentHpqCompanionPlay.seedHintDue(sinceMs, nowMs, SEED_HOLD_HINT_MS)) {
                    coordinate(session, narrator(session), "seed-hint",
                            AgentHpqCompanionPlay.seedPadLine(leader.getName(), position));
                }
            }
        }
        List<Integer> toFetch = AgentHpqCompanionPlay.seedsToFetch(needed, carriedByAgents, coveredByHumans);
        List<MapItem> floorSeeds = naturalSeedDrops(drops).stream()
                .filter(drop -> toFetch.contains(drop.getItemId())).toList();
        // Who fetches which colour first, so the collectors of one colour spread over its primroses.
        Map<Integer, Integer> fetchColour = new HashMap<>();
        Map<Integer, Integer> collectorsByColour = new HashMap<>();
        int fetchOrdinal = 0;
        boolean hasSeed = false;
        for (AgentHpqMemberState member : session.members()) {
            Character agent = standingAgent(member);
            if (agent == null || agent.getMapId() != AgentHpqDefinition.STAGE_MAP) continue;
            if (AgentHpqCompanionPlay.carriedSeed(seed -> agent.getItemQuantity(seed, false) > 0, needed) > 0) {
                continue;
            }
            int colour = AgentHpqCompanionPlay.fetchSeed(toFetch, fetchOrdinal++);
            fetchColour.put(member.characterId(), colour);
            collectorsByColour.merge(colour, 1, Integer::sum);
        }
        Map<Integer, Integer> colourOrdinal = new HashMap<>();
        Set<Integer> assignedDropIds = new HashSet<>();
        for (AgentHpqMemberState member : session.members()) {
            Character agent = standingAgent(member);
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (agent == null || entry == null || agent.getMapId() != AgentHpqDefinition.STAGE_MAP) continue;
            int carried = AgentHpqCompanionPlay.carriedSeed(seed -> agent.getItemQuantity(seed, false) > 0, needed);
            if (carried > 0) {
                hasSeed = true;
                AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
                member.assign(AgentHpqMemberState.Role.SEED_PLANTER, carried);
                if (plant(session, entry, agent, member, carried, nowMs)) {
                    session.markPadPlanted(AgentHpqDefinition.seedBed(carried).reactorId(), nowMs);
                }
                continue;
            }
            int colour = fetchColour.getOrDefault(member.characterId(), 0);
            member.assign(AgentHpqMemberState.Role.SEED_COLLECTOR, colour);
            MapItem floorSeed = nearestUnassignedDrop(agent, floorSeeds, assignedDropIds);
            if (floorSeed != null) {
                assignedDropIds.add(floorSeed.getObjectId());
                pursueLoot(entry, floorSeed);
            } else if (colour == 0) {
                AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
                actions().stop(entry);
            } else {
                AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
                int ordinal = colourOrdinal.merge(colour, 1, Integer::sum) - 1;
                collectSeed(entry, agent, member, ordinal, collectorsByColour.getOrDefault(colour, 1),
                        Set.of(AgentHpqDefinition.sourceReactorFor(colour)), nowMs);
            }
        }
        if (hasSeed && session.phase() == AgentHpqSession.Phase.COLLECTING_SEEDS) {
            transition(session, AgentHpqSession.Phase.PLANTING_SEEDS, nowMs);
        }
    }

    /** The moonflower pads standing on the leader's map, by the seed each takes. */
    private static Map<Integer, Reactor> moonflowers(Character leader) {
        Map<Integer, Reactor> pads = new HashMap<>();
        Collection<Reactor> reactors = actions().reactors(leader);
        if (reactors == null) return pads;
        for (AgentHpqDefinition.SeedBed bed : AgentHpqDefinition.seedBeds()) {
            reactors.stream()
                    .filter(reactor -> reactor != null && reactor.isAlive() && reactor.getId() == bed.reactorId()
                            && bed.reactorName().equalsIgnoreCase(reactor.getName()))
                    .findFirst().ifPresent(reactor -> pads.put(bed.seedItemId(), reactor));
        }
        return pads;
    }

    /** What the guards see this tick. */
    private record Defense(Character leader, List<Point> posts, Point bunny, List<Point> hostiles,
                           Set<Integer> threats, Set<Integer> mobs, boolean delivering) {
    }

    /**
     * Defense, from the Moon Bunny's appearance to Growlie's clear (it keeps running while the leader takes the cakes
     * to Growlie). The clear is checked first, then the Bunny lost, then the leader holding every cake. The guards stand
     * on posts around the live Bunny; the companion on the centre post collects its cakes and hands them over in
     * batches at the leader's feet.
     */
    private static void companionDefend(AgentHpqSession session, Character leader, long nowMs, boolean delivering) {
        EventInstanceManager event = session.eventInstance();
        boolean cleared = leader.getMapId() == AgentHpqDefinition.CLEAR_MAP
                || (event != null && event.isEventCleared());
        List<Monster> live = AgentMapPerception.monsters(leader.getMap()).stream()
                .filter(java.util.Objects::nonNull).filter(Monster::isAlive).toList();
        Monster bunny = live.stream().filter(monster -> monster.getId() == AgentHpqDefinition.MOON_BUNNY)
                .findFirst().orElse(null);
        boolean deliveringNow = delivering;
        switch (AgentHpqCompanionPlay.defenseStep(cleared, session.bunnySeen(), bunny != null,
                leader.getMapId() == AgentHpqDefinition.STAGE_MAP,
                leader.getItemQuantity(AgentHpqDefinition.RICE_CAKE, false), AgentHpqDefinition.REQUIRED_RICE_CAKES)) {
            case CLEARED -> {
                companionCleared(session, nowMs);
                return;
            }
            case BUNNY_LOST -> {
                session.markBunnyLost();
                fail(session, "The Moon Bunny was defeated", AgentHpqParting.of(AgentHpqParting.Kind.BUNNY), nowMs);
                return;
            }
            case DELIVER -> {
                if (!delivering) transition(session, AgentHpqSession.Phase.DELIVERING_CAKES, nowMs);
                deliveringNow = true;
            }
            default -> { }
        }
        observeBunny(session, bunny, nowMs);
        MapleMap map = leader.getMap();
        Point spawn = actions().groundPoint(map, AgentHpqDefinition.MOON_BUNNY_POSITION);
        Point anchor = AgentHpqCompanionPlay.guardAnchor(bunny == null ? null : bunny.getPosition(),
                session.bunnyAnchor(), spawn == null ? AgentHpqDefinition.MOON_BUNNY_POSITION : spawn,
                DEFENSE_GUARD_ARRIVAL_PX);
        if (bunny != null) session.setBunnyAnchor(anchor);
        List<Point> posts = AgentHpqCompanionPlay.guardPosts(anchor, COMPANION_GUARD_SPREAD_PX, GUARD_POST_MAX_DY_PX,
                point -> actions().groundPoint(map, point));
        Point bunnyAt = bunny != null && bunny.getPosition() != null ? bunny.getPosition() : anchor;
        Set<Integer> mobs = stageMonsters(leader);
        List<Monster> hostiles = live.stream().filter(monster -> mobs.contains(monster.getId())).toList();
        Set<Integer> threats = hostiles.stream()
                .filter(monster -> AgentHpqCompanionPlay.threatens(
                        monster.getPosition(), bunnyAt, COMPANION_BUNNY_THREAT_RADIUS_PX))
                .map(Monster::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        Defense defense = new Defense(leader, posts, bunnyAt,
                hostiles.stream().map(Monster::getPosition).toList(), threats, mobs, deliveringNow);
        List<AgentHpqMemberState> standing = session.members().stream()
                .filter(member -> standingAgent(member) != null
                        && standingAgent(member).getMapId() == AgentHpqDefinition.STAGE_MAP
                        && AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()) != null)
                .toList();
        int collectorId = assignCollector(session, standing);
        int ordinal = 1;
        for (AgentHpqMemberState member : standing) {
            Character agent = standingAgent(member);
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (member.characterId() == collectorId) {
                collectCakes(session, member, agent, entry, defense, nowMs);
            } else {
                guard(entry, agent, posts.get(ordinal++ % posts.size()), defense);
            }
        }
        if (deliveringNow && !session.leaderToldGrowlie()) {
            coordinate(session, narrator(session), "all-ten", AgentHpqCompanionPlay.ALL_TEN);
        }
    }

    /** C4 when the Bunny first appears, C5 when it loses HP (spaced and capped). */
    private static void observeBunny(AgentHpqSession session, Monster bunny, long nowMs) {
        if (bunny == null) return;
        if (!session.bunnySeen()) {
            session.markBunnySeen();
            coordinate(session, narrator(session), "bunny-out", AgentHpqCompanionPlay.BUNNY_OUT);
        }
        long hp = bunny.getHp();
        if (AgentHpqCompanionPlay.bunnyHurtLineDue(session.bunnyLastHp(), hp, session.bunnyHurtLines(),
                session.bunnyHurtLineAtMs(), nowMs, BUNNY_HURT_LINE_EVERY_MS, BUNNY_HURT_LINE_MAX)
                && coordinate(session, narrator(session), "bunny-hit-" + (session.bunnyHurtLines() + 1),
                        AgentHpqCompanionPlay.BUNNY_HIT)) {
            session.noteBunnyHurtLine(nowMs);
        }
        session.setBunnyLastHp(hp);
    }

    /** Keeps or moves the collector role (CAKE_COLLECTOR, which alone may loot cakes) to the next standing companion. */
    private static int assignCollector(AgentHpqSession session, List<AgentHpqMemberState> standing) {
        int current = session.cakeCollectorId();
        Set<Integer> leftBehind = session.members().stream().filter(AgentHpqMemberState::cakesLeftBehind)
                .map(AgentHpqMemberState::characterId).collect(Collectors.toSet());
        int next = AgentHpqCompanionPlay.collector(
                standing.stream().map(AgentHpqMemberState::characterId).toList(), current, leftBehind);
        if (next == current) return current;
        AgentHpqMemberState previous = session.member(current);
        if (previous != null && previous.role() == AgentHpqMemberState.Role.CAKE_COLLECTOR) {
            previous.assign(AgentHpqMemberState.Role.BUNNY_GUARD, 0);
        }
        AgentHpqMemberState chosen = session.member(next);
        if (chosen != null) chosen.assign(AgentHpqMemberState.Role.CAKE_COLLECTOR, 0);
        session.setCakeCollectorId(next);
        session.endHandOver(false);
        session.endRecollect(false);
        log.info("HPQ cake collector: session={} from={} to={}", session.sessionId(), current, next);
        return next;
    }

    /**
     * A guard wakes for a hostile near itself or one threatening the Bunny and fights from its post (farm-here, which
     * leashes it to the post), preferring the Bunny's attackers; otherwise it walks back to its post and waits there.
     */
    private static void guard(AgentRuntimeEntry entry, Character agent, Point post, Defense defense) {
        AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
        if (AgentHpqCompanionPlay.guardWakes(agent.getPosition(), defense.bunny(), defense.hostiles(),
                DEFENSE_WAKE_RADIUS_PX, COMPANION_BUNNY_THREAT_RADIUS_PX)) {
            actions().grindFromAnchor(entry, post,
                    defense.threats().isEmpty() ? defense.mobs() : defense.threats(), defense.mobs());
        } else if (!near(agent.getPosition(), post, DEFENSE_GUARD_ARRIVAL_PX)) {
            actions().navigate(entry, post, true);
        } else {
            actions().stop(entry);
        }
    }

    /**
     * The collector: drops its stack where it stands if it is about to go down; picks a stack the leader left lying
     * back up once its re-collect is due; walks a due batch to the leader's feet (or to Growlie); loots the Bunny's
     * natural cakes only while nothing threatens the Bunny; and otherwise guards the centre post. While the leader
     * delivers it only guards.
     */
    private static void collectCakes(AgentHpqSession session, AgentHpqMemberState member, Character agent,
                                     AgentRuntimeEntry entry, Defense defense, long nowMs) {
        Character leader = defense.leader();
        int held = agent.getItemQuantity(AgentHpqDefinition.RICE_CAKE, false);
        if (held > 0 && AgentHpqCompanionPlay.dying(agent.getHp(), agent.getCurrentMaxHp(),
                actions().hpPotionCount(agent), CAKE_DYING_HP_PERCENT)) {
            leaveCakes(session, member, agent, entry, held, nowMs);
            return;
        }
        List<MapItem> drops = droppedItems(leader);
        int recollectStack = session.recollectStackObjectId();
        if (recollectStack != 0 && drops.stream().noneMatch(
                drop -> drop.getObjectId() == recollectStack && !drop.isPickedUp())) {
            boolean pickedUp = held > session.recollectHeldBefore();
            session.endRecollect(pickedUp);
            if (pickedUp && !defense.delivering()) session.startHandOver(nowMs, true);
        }
        if (defense.delivering()) {
            if (session.handOverStartedAtMs() != 0L) session.endHandOver(false);
            guard(entry, agent, defense.posts().getFirst(), defense);
            return;
        }
        if (session.handOverStartedAtMs() != 0L) {
            if (held > 0) {
                handOverCakes(session, agent, entry, leader, held, nowMs);
                return;
            }
            session.endHandOver(false);
        }
        List<MapItem> stacks = lyingStacks(session, drops);
        for (MapItem stack : stacks) {
            if (AgentHpqCompanionPlay.nudgeDue(stack.getDropTime(), nowMs, CAKE_PICKUP_NUDGE_MS)) {
                coordinate(session, agent, "cakes-nudge-" + stack.getObjectId(),
                        AgentHpqCompanionPlay.cakesStillLyingLine(leader.getName()));
            }
        }
        if (session.recollectStackObjectId() == 0) {
            stacks.stream()
                    .filter(stack -> AgentHpqCompanionPlay.recollectDue(stack.getDropTime(), nowMs,
                            CAKE_RECOLLECT_MS, session.recollects(), CAKE_RECOLLECT_MAX))
                    .min(Comparator.comparingLong(MapItem::getDropTime))
                    .ifPresent(stack -> session.beginRecollect(agent.getId(), stack.getObjectId(), held));
        }
        MapItem recollecting = stacks.stream()
                .filter(stack -> stack.getObjectId() == session.recollectStackObjectId()).findFirst().orElse(null);
        if (recollecting != null) {
            pursueLoot(entry, recollecting);
            return;
        }
        List<MapItem> natural = naturalCakes(drops);
        boolean leaderNear = agent.getMap() == leader.getMap()
                && near(agent.getPosition(), leader.getPosition(), CAKE_HANDOVER_NEAR_PX);
        if (AgentHpqCompanionPlay.handOverDue(held, leader.getItemQuantity(AgentHpqDefinition.RICE_CAKE, false),
                leaderNear, intProperty(leader, "bunnyCake"), !natural.isEmpty(), CAKE_HANDOVER_BATCH,
                AgentHpqDefinition.REQUIRED_RICE_CAKES)) {
            session.startHandOver(nowMs, false);
            handOverCakes(session, agent, entry, leader, held, nowMs);
            return;
        }
        MapItem cake = defense.threats().isEmpty() ? nearestUnassignedDrop(agent, natural, Set.of()) : null;
        if (cake != null) {
            pursueLoot(entry, cake);
            return;
        }
        guard(entry, agent, defense.posts().getFirst(), defense);
    }

    /** The Moon Bunny's own cakes on the floor: never a stack a player (a companion's hand-over) dropped. */
    static List<MapItem> naturalCakes(Collection<MapItem> drops) {
        if (drops == null || drops.isEmpty()) return List.of();
        return drops.stream()
                .filter(java.util.Objects::nonNull)
                .filter(drop -> !drop.isPickedUp() && !drop.isPlayerDrop() && drop.getMeso() <= 0
                        && drop.getItemId() == AgentHpqDefinition.RICE_CAKE)
                .sorted(Comparator.comparingInt(MapItem::getObjectId))
                .toList();
    }

    /** Stacks the run's companions dropped (hand-overs, or one left before going down) still lying on the floor. */
    private static List<MapItem> lyingStacks(AgentHpqSession session, Collection<MapItem> drops) {
        return drops.stream()
                .filter(drop -> drop != null && drop.isPlayerDrop() && !drop.isPickedUp()
                        && drop.getItemId() == AgentHpqDefinition.RICE_CAKE)
                .filter(drop -> {
                    AgentHpqMemberState owner = session.member(drop.getOwnerId());
                    return owner != null && owner.memberType() == AgentHpqMemberState.MemberType.AGENT;
                })
                .toList();
    }

    /**
     * Walks the stack to the leader's spot and drops it there in one drop, where the map's own placement puts it at
     * his feet or right next to him; a leader it has no route to, or a walk longer than CAKE_HANDOVER_TIMEOUT_MS, has
     * it take the stack to Growlie instead.
     */
    private static void handOverCakes(AgentHpqSession session, Character agent, AgentRuntimeEntry entry,
                                      Character leader, int held, long nowMs) {
        boolean onGround = actions().footing(agent) == PrimitiveCapabilityGateway.Footing.GROUND;
        if (!session.handOverToGrowlie()) {
            Point spot = groundSpot(leader);
            boolean leaderOnGround = actions().footing(leader) == PrimitiveCapabilityGateway.Footing.GROUND;
            boolean reachable = agent.getMap() == leader.getMap()
                    && leader.getMapId() == AgentHpqDefinition.STAGE_MAP
                    && (!onGround || actions().canReach(entry, agent, spot));
            Point landing = AgentHpqCompanionPlay.atFeet(agent.getPosition(), spot, onGround, leaderOnGround,
                    CAKE_HANDOVER_REACH_PX) ? actions().predictDropLanding(agent, agent.getPosition()) : null;
            switch (AgentHpqCompanionPlay.handOver(agent.getPosition(), spot, onGround, leaderOnGround, landing,
                    reachable, nowMs - session.handOverStartedAtMs(), CAKE_HANDOVER_TIMEOUT_MS,
                    CAKE_HANDOVER_REACH_PX)) {
                case WALK -> {
                    AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
                    actions().navigate(entry, spot, true);
                    return;
                }
                case DROP_AT_FEET -> {
                    dropCakes(session, agent, entry, leader, held, CakePlace.FEET, nowMs);
                    return;
                }
                case DROP_BESIDE -> {
                    dropCakes(session, agent, entry, leader, held, CakePlace.BESIDE, nowMs);
                    return;
                }
                default -> session.sendHandOverToGrowlie();
            }
        }
        Point growlie = growlieSpot(agent);
        if (AgentHpqCompanionPlay.atFeet(agent.getPosition(), growlie, onGround, true, CAKE_HANDOVER_REACH_PX)) {
            dropCakes(session, agent, entry, leader, held, CakePlace.GROWLIE, nowMs);
            return;
        }
        AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
        actions().navigate(entry, growlie, true);
    }

    private enum CakePlace { FEET, BESIDE, GROWLIE }

    private static void dropCakes(AgentHpqSession session, Character agent, AgentRuntimeEntry entry,
                                  Character leader, int held, CakePlace place, long nowMs) {
        actions().stop(entry);
        if (!AgentScriptItemActionService.dropItem(
                entry, InventoryType.ETC, AgentHpqDefinition.RICE_CAKE, (short) held)) {
            return;
        }
        boolean recollect = session.handOverRecollect();
        int handOver = session.handOvers() + 1;
        session.endHandOver(true);
        session.markProgress(nowMs);
        inhibitCakePickup(session);
        log.info("HPQ cakes dropped: session={} member={} cakes={} place={} recollect={} leader={} at={}",
                session.sessionId(), agent.getId(), held, place, recollect, leader.getId(), agent.getPosition());
        if (recollect) {
            coordinate(session, agent, "cakes-recollected-" + handOver, AgentHpqCompanionPlay.CAKES_RECOLLECTED);
        } else if (place == CakePlace.GROWLIE) {
            coordinate(session, agent, "cakes-by-growlie-" + handOver,
                    AgentHpqCompanionPlay.cakesByGrowlieLine(leader.getName()));
        } else if (coordinate(session, agent, "cakes-dropped-" + handOver,
                AgentHpqCompanionPlay.cakesDroppedLine(leader.getName(), place == CakePlace.FEET))) {
            session.markLeaderToldGrowlie();
        }
    }

    /** A collector about to go down drops its stack where it stands, says so, and collects no more. */
    private static void leaveCakes(AgentHpqSession session, AgentHpqMemberState member, Character agent,
                                   AgentRuntimeEntry entry, int held, long nowMs) {
        actions().stop(entry);
        if (!AgentScriptItemActionService.dropItem(
                entry, InventoryType.ETC, AgentHpqDefinition.RICE_CAKE, (short) held)) {
            return;
        }
        member.markCakesLeftBehind();
        session.endHandOver(false);
        session.markProgress(nowMs);
        log.info("HPQ cakes dropped: session={} member={} cakes={} place=dying at={}",
                session.sessionId(), agent.getId(), held, agent.getPosition());
        coordinate(session, agent, "cakes-left-" + agent.getId(), AgentHpqCompanionPlay.LEAVING_CAKES);
    }

    /** Nobody picks up for CAKE_HANDOVER_LOOT_INHIBIT_MS after a hand-over: the stack is the leader's. */
    private static void inhibitCakePickup(AgentHpqSession session) {
        int inhibitMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, CAKE_HANDOVER_LOOT_INHIBIT_MS));
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
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
        Point grounded = actions().groundPoint(character.getMap(), position);
        return grounded == null ? new Point(position) : grounded;
    }

    /** Growlie's spot on the spawn floor, where a stack goes when the leader cannot be reached. */
    private static Point growlieSpot(Character agent) {
        Point growlie = actions().npcPosition(agent, AgentHpqDefinition.GROWLIE_NPC);
        Point at = growlie == null ? AgentHpqDefinition.GROWLIE_POSITION : growlie;
        Point grounded = actions().groundPoint(agent.getMap(), at);
        return grounded == null ? new Point(at) : grounded;
    }

    /** Growlie has his cakes: rewards freeze, the companions stop where they stand, and the clear map's choice comes. */
    private static void companionCleared(AgentHpqSession session, long nowMs) {
        session.freezeRewardEligibility();
        session.markCleared(nowMs);
        session.endHandOver(false);
        session.endRecollect(false);
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (entry == null) continue;
            AgentGrindLootStateRuntime.clearObjectiveLootTarget(entry);
            actions().stop(entry);
        }
        transition(session, AgentHpqSession.Phase.BONUS_DECISION, nowMs);
    }

    /**
     * On the clear map the leader chooses: Tommy's Pig Town (they follow, as his party), Tory's reward, or he goes. The
     * companions wait for him, up to COMPANION_CLEAR_WAIT_MS.
     */
    private static void companionAfterClear(AgentHpqSession session, Character leader, long nowMs) {
        coordinate(session, narrator(session), "cleared", AgentHpqCompanionPlay.CLEARED);
        boolean waitPassed = nowMs - session.clearedAtMs() >= COMPANION_CLEAR_WAIT_MS;
        switch (AgentHpqCompanionPlay.afterClear(leader != null, leader == null ? 0 : leader.getHp(),
                inRun(session, leader), leader == null ? 0 : leader.getMapId(), waitPassed)) {
            case BONUS -> transition(session, AgentHpqSession.Phase.BONUS_FARMING, nowMs);
            case CLAIM -> transition(session, AgentHpqSession.Phase.CLAIMING_REWARD, nowMs);
            case CLAIM_AFTER_WAIT -> {
                coordinate(session, narrator(session), "wait-outside", AgentHpqCompanionPlay.WAIT_OUTSIDE);
                transition(session, AgentHpqSession.Phase.CLAIMING_REWARD, nowMs);
            }
            default -> { }
        }
    }

    /** Pig Town: they fight beside him until he leaves it, then leave through Tommy themselves. */
    private static void companionBonus(AgentHpqSession session, Character leader, long nowMs) {
        coordinate(session, narrator(session), "pig-town", AgentHpqCompanionPlay.PIG_TOWN);
        boolean leaderStays = leader != null && leader.getHp() > 0
                && leader.getMapId() == AgentHpqDefinition.BONUS_MAP && inRun(session, leader);
        boolean agentRemaining = false;
        for (AgentHpqMemberState member : session.members()) {
            Character agent = standingAgent(member);
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (agent == null || entry == null || agent.getMapId() != AgentHpqDefinition.BONUS_MAP) continue;
            agentRemaining = true;
            if (leaderStays) {
                actions().grind(entry, actions().configuredMonsterSpawnIds(agent));
            } else if (nowMs >= member.nextActionAtMs()) {
                member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                hpq().runNpc(agent, AgentHpqDefinition.TOMMY_NPC);
            }
        }
        if (!agentRemaining) transition(session, AgentHpqSession.Phase.CLAIMING_REWARD, nowMs);
    }

    /**
     * The companions claim at Tory (which takes them out to Henesys) once they may (see companionsMayClaim); one in
     * Pig Town after he left it goes out through Tommy first. Humans are never waited for and get no system text.
     */
    private static void companionClaim(AgentHpqSession session, Character leader, long nowMs) {
        session.freezeRewardEligibility();
        boolean mayClaim = AgentHpqCompanionPlay.companionsMayClaim(inRun(session, leader),
                leader == null ? 0 : leader.getMapId(), nowMs - session.clearedAtMs() >= COMPANION_CLEAR_WAIT_MS);
        boolean pending = false;
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT || member.rewardResolved()) continue;
            Character agent = character(member.characterId());
            if (agent == null || !inRun(session, agent)) continue;
            pending = true;
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
            if (!mayClaim || entry == null || agent.getHp() <= 0) continue;
            if (agent.getMapId() == AgentHpqDefinition.BONUS_MAP) {
                if (nowMs >= member.nextActionAtMs()) {
                    member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                    hpq().runNpc(agent, AgentHpqDefinition.TOMMY_NPC);
                }
                continue;
            }
            if (agent.getMapId() != AgentHpqDefinition.CLEAR_MAP
                    && agent.getMapId() != AgentHpqDefinition.REWARD_EXIT_MAP) {
                continue;
            }
            Point tory = actions().npcPosition(agent, AgentHpqDefinition.ENTRY_NPC);
            if (tory != null && !near(agent.getPosition(), tory, NPC_APPROACH_PX)) {
                actions().navigate(entry, tory, true);
            } else if (tory != null && nowMs >= member.nextActionAtMs()) {
                member.deferUntil(nowMs + INTERACTION_RETRY_MS);
                hpq().runNpc(agent, AgentHpqDefinition.ENTRY_NPC);
            }
        }
        if (!pending) transition(session, AgentHpqSession.Phase.EXITING, nowMs);
    }

    /** Done once every companion is up and out of the event; no line and no walk, they follow their owner. */
    private static void companionExit(AgentHpqSession session, long nowMs) {
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            if (agent != null && (agent.getHp() <= 0 || inRun(session, agent))) return;
        }
        AgentHpqTerminationService.complete(session, nowMs);
    }

    // ---- companion runs: departures, deaths and the run's end ----

    /** Growlie took the cakes: the phase is past the clear, or the event's own clear is in. */
    private static boolean pastTheClear(AgentHpqSession session) {
        EventInstanceManager event = session.eventInstance();
        return AgentHpqMemberVerdicts.clearedPhase(session.phase()) || (event != null && event.isEventCleared());
    }

    /** After the clear the leader is let go once he logs off, goes down, or is no longer the party's leader. */
    private static void releaseLeaderAfterClear(AgentHpqSession session, Character leader) {
        if (session.leaderReleased()) return;
        String why = leader == null ? "offline"
                : leader.getHp() <= 0 ? "down"
                : leadPassed(session) ? "lead passed" : null;
        if (why == null) return;
        session.markLeaderReleased();
        log.info("HPQ leader let go after the clear: session={} leader={} why={} phase={}",
                session.sessionId(), session.eventLeaderId(), why, session.phase());
    }

    /** The live party is gone, or someone else leads it. */
    private static boolean leadPassed(AgentHpqSession session) {
        AgentPartySnapshot party = liveParty(session);
        return party == null || party.members().stream().noneMatch(member -> member != null && member.leader()
                && member.id() == session.eventLeaderId());
    }

    /** The live party, as the first online member of the run sees it; null when none has one. */
    private static AgentPartySnapshot liveParty(AgentHpqSession session) {
        for (AgentHpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            AgentPartySnapshot party = character == null ? null : AgentPartyGatewayRuntime.party().snapshot(character);
            if (party != null) return party;
        }
        return null;
    }

    /** A member's name: from the live party, which keeps offline members, else from the online character; or null. */
    private static String memberName(AgentHpqSession session, int characterId) {
        AgentPartySnapshot party = liveParty(session);
        String name = party == null ? null : party.members().stream()
                .filter(member -> member != null && member.id() == characterId)
                .map(AgentPartyMemberSnapshot::name)
                .findFirst().orElse(null);
        if (name != null) return name;
        Character character = character(characterId);
        return character == null ? null : character.getName();
    }

    /** Why the event ended under the run: the Bunny lost, its timer (counted from the leader's entry), or neither. */
    private static AgentHpqParting.Kind endedCause(AgentHpqSession session, long nowMs) {
        long enteredAtMs = session.enteredEventAtMs() > 0L ? session.enteredEventAtMs() : session.startedAtMs();
        return AgentHpqParting.eventEnded(session.bunnyLost(), nowMs - enteredAtMs,
                AgentPartyQuestCatalog.require(QUEST_KEY).eventTimerMs(), TIMEOUT_SLACK_MS);
    }

    /**
     * The departed-member pass, read from the live party as KPQ's reconcileLiveParty: a member no longer in it (a
     * tapped-out companion its host took out, a player who left) is ejected; fewer than three end the run LEFT, and
     * with no companion left the humans carry on. A lead passed inside the event ends the run (HPQ does not pass the lead).
     * False when the run ended.
     */
    private static boolean reconcileLiveParty(AgentHpqSession session, long nowMs) {
        if (session.eventInstance() == null) return true;
        AgentPartySnapshot party = liveParty(session);
        if (party == null) {
            fail(session, "The HPQ party was disbanded", AgentHpqParting.GENERIC, nowMs);
            return false;
        }
        Set<Integer> live = party.members().stream().filter(java.util.Objects::nonNull)
                .map(AgentPartyMemberSnapshot::id).collect(Collectors.toSet());
        List<AgentHpqMemberState> departed = session.members().stream()
                .filter(member -> member.characterId() != session.eventLeaderId())
                .filter(member -> !live.contains(member.characterId()))
                .toList();
        AgentHpqParting left = AgentHpqParting.of(AgentHpqParting.Kind.LEFT);
        for (AgentHpqMemberState member : departed) {
            Character character = character(member.characterId());
            if (left.subject() == null && character != null) {
                left = AgentHpqParting.of(AgentHpqParting.Kind.LEFT, character);
            }
            departMember(session, member, "left the party", nowMs);
        }
        if (!departed.isEmpty() && !carriesOn(session, left, nowMs)) return false;
        int liveLeaderId = party.members().stream().filter(java.util.Objects::nonNull)
                .filter(AgentPartyMemberSnapshot::leader)
                .mapToInt(AgentPartyMemberSnapshot::id).findFirst().orElse(0);
        if (liveLeaderId != session.eventLeaderId()) {
            fail(session, "The live HPQ party is no longer led by the run's leader", AgentHpqParting.GENERIC, nowMs);
            return false;
        }
        return true;
    }

    /**
     * Every non-leader member by AgentHpqMemberVerdicts (the leader was handled before). A downed one is marked down
     * and left out of the stage loops, which keep running; a companion up outside the event is tapped out by its host
     * (a refusal ends the run DEFEATED); a human who dropped is ejected. False when the run ended.
     */
    private static boolean membersAlive(AgentHpqSession session, Character leader, long nowMs) {
        EventInstanceManager event = session.eventInstance();
        boolean leaderInBoundEvent = event != null && hpq().event(leader) == event;
        boolean cleared = event != null && event.isEventCleared();
        // Counted as the loop marks members, so two companions downed in one tick cannot both wait.
        int down = (int) session.members().stream().filter(AgentHpqMemberState::down).count();
        for (AgentHpqMemberState member : session.members()) {
            int id = member.characterId();
            if (id == session.eventLeaderId()) continue;
            Character character = character(id);
            AgentHpqMemberVerdicts.Verdict verdict = AgentHpqMemberVerdicts.verdict(new AgentHpqMemberVerdicts.Facts(
                    true, session.phase(), member.memberType(), false, character != null,
                    character == null ? 0 : character.getHp(), character != null && hpq().event(character) == event,
                    leaderInBoundEvent, cleared, member.downSinceMs(), member.tapOutRequested(),
                    session.memberCount() - 1 - (down - (member.down() ? 1 : 0)), nowMs, MEMBER_RECOVERY_TIMEOUT_MS));
            switch (verdict) {
                case MISSING -> {
                    if (nowMs - member.observeMissing(nowMs) >= MEMBER_RECOVERY_TIMEOUT_MS) {
                        fail(session, "Party member " + id + " disconnected or left the channel",
                                new AgentHpqParting(AgentHpqParting.Kind.GONE, id, memberName(session, id)), nowMs);
                        return false;
                    }
                }
                case OK -> member.clearMissing();
                case DEFEATED -> {
                    fail(session, character.getName() + " was defeated during HPQ",
                            AgentHpqParting.of(AgentHpqParting.Kind.DEFEATED, character), nowMs);
                    return false;
                }
                case SHORT_HANDED -> {
                    fail(session, character.getName() + " went down, and HPQ needs three",
                            AgentHpqParting.of(AgentHpqParting.Kind.WENT_DOWN, character), nowMs);
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
                    // The stock respawn carried it out of the event; its host takes it out of the party, and the
                    // departed-member pass ejects it on a later tick.
                    if (AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut(
                            QUEST_KEY, session.sessionId(), id, character.getMapId(), nowMs))) {
                        member.markTapOutRequested();
                        log.info("HPQ companion tapped out: session={} member={} map={}",
                                session.sessionId(), id, character.getMapId());
                    } else if (!cleared) {
                        fail(session, character.getName() + " was defeated during HPQ and its host kept it",
                                AgentHpqParting.of(AgentHpqParting.Kind.DEFEATED, character), nowMs);
                        return false;
                    }
                    // Else Growlie cleared the run on his own thread since this tick read the clear: after the clear
                    // nothing is asked of a downed member.
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

    /** Marks a member down at its first sighting, with the rice cakes it holds (logged once). */
    private static void markDown(AgentHpqSession session, AgentHpqMemberState member, Character character,
                                 long nowMs) {
        boolean first = !member.down();
        int cakes = character == null ? 0
                : Math.max(0, character.getItemQuantity(AgentHpqDefinition.RICE_CAKE, false));
        member.markDown(nowMs, cakes);
        if (first) {
            log.info("HPQ member down: session={} member={} type={} phase={} cakes={}",
                    session.sessionId(), member.characterId(), member.memberType(), session.phase(), cakes);
        }
    }

    /**
     * A non-leader human who went offline or is up outside the event is left behind: the run carries on with C13, or
     * ends LEFT below three. False when the run ended.
     */
    private static boolean dropHuman(AgentHpqSession session, AgentHpqMemberState member, Character character,
                                     long nowMs) {
        int id = member.characterId();
        String name = memberName(session, id);
        departMember(session, member, character == null ? "offline" : "up outside the event", nowMs);
        if (session.memberCount() < AgentHpqDefinition.MINIMUM_PARTY_SIZE) {
            fail(session, "A human member dropped, and HPQ needs three",
                    new AgentHpqParting(AgentHpqParting.Kind.LEFT, id, name), nowMs);
            return false;
        }
        coordinate(session, narrator(session), "drop-" + id,
                AgentHpqCompanionPlay.carryOnLine(name == null ? "someone" : name, session.memberCount()));
        return true;
    }

    /**
     * A companion run's end. The first standing companion (else the first companion, even down) puts it in its own
     * words, SILENT before the run got going; the raw reason goes only to the log. Before the clear the event is
     * disposed, as a stock failure does; after it only the companions leave, through the event's exit, and the event
     * and its players stay. A stock run fails as it always did.
     */
    private static void fail(AgentHpqSession session, String reason, AgentHpqParting parting, long nowMs) {
        if (!session.companionAdopted()) {
            AgentHpqTerminationService.fail(session, reason, nowMs);
            return;
        }
        if (session.terminal() || session.terminationStarted()) return;
        boolean pastTheClear = pastTheClear(session);
        AgentHpqParting said = beforeEntry(session.phase()) ? AgentHpqParting.SILENT : parting;
        Character speaker = narrator(session);
        log.warn("HPQ session failure: session={} phase={} parting={} pastTheClear={} reason={} members={}",
                session.sessionId(), session.phase(), said.kind(), pastTheClear, reason, session.memberCount());
        if (speaker != null) narrate(session, speaker, "failed", said.line(speaker.getId(), pastTheClear));
        if (pastTheClear) {
            AgentHpqTerminationService.failAfterClear(session, reason, nowMs);
        } else {
            AgentHpqTerminationService.fail(session, reason, nowMs);
        }
    }

    /** Before the party was inside: the desk already answered the leader, so the run ends without a word. */
    private static boolean beforeEntry(AgentHpqSession.Phase phase) {
        return phase == AgentHpqSession.Phase.PREPARING || phase == AgentHpqSession.Phase.ENTERING;
    }

    /**
     * True when the watchdog, finding no standing companion to run a companion run, should wait: one has been down less
     * than MEMBER_RECOVERY_TIMEOUT_MS and the stock respawn will carry it out, where its host taps it out. Short-handed
     * the run ends WENT_DOWN now (the respawn would end the event anyway), said by the downed companion.
     */
    static boolean awaitingRespawn(AgentHpqSession session, long nowMs) {
        synchronized (session) {
            if (session.terminal() || session.terminationStarted()) return true;
            // Before entry a respawn stays in Henesys, so there is nothing to wait for.
            if (beforeEntry(session.phase())) return false;
            Character downed = null;
            for (AgentHpqMemberState member : session.members()) {
                if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
                Character agent = character(member.characterId());
                if (agent == null) continue;
                if (agent.getHp() <= 0) markDown(session, member, agent, nowMs);
                if (downed == null && member.down() && nowMs - member.downSinceMs() < MEMBER_RECOVERY_TIMEOUT_MS) {
                    downed = agent;
                }
            }
            if (downed == null) return false;
            if (shortHanded(session)) {
                fail(session, downed.getName() + " went down, and HPQ needs three",
                        AgentHpqParting.of(AgentHpqParting.Kind.WENT_DOWN, downed), nowMs);
            }
            return true;
        }
    }

    /** The watchdog found no live Agent to run the session: a companion run hears it end, a stock run ends as ever. */
    static void failWithoutLiveAgent(AgentHpqSession session, long nowMs) {
        String reason = "No live Agent execution participant remains";
        if (!session.companionAdopted()) {
            AgentHpqTerminationService.fail(session, reason, nowMs);
            return;
        }
        synchronized (session) {
            fail(session, reason, AgentHpqParting.GENERIC, nowMs);
        }
    }

    /** Inside an uncleared stage, fewer than three members still stand in the event (up, not marked down, inside). */
    private static boolean shortHanded(AgentHpqSession session) {
        EventInstanceManager event = session.eventInstance();
        if (!insideEvent(session.phase()) || event == null || event.isEventCleared()) return false;
        return session.members().stream()
                .filter(member -> !member.down())
                .map(member -> character(member.characterId()))
                .filter(character -> character != null && character.getHp() > 0 && hpq().event(character) == event)
                .count() < AgentHpqDefinition.MINIMUM_PARTY_SIZE;
    }

    /**
     * A companion run's companions standing in its instance after the clear, their reward still to claim, run at full
     * rate; nobody else's are held.
     */
    private static void holdFullRateSimulation(AgentHpqSession session) {
        AgentHpqSession.Phase phase = session.phase();
        boolean ending = session.terminationStarted();
        boolean claimPhase = phase == AgentHpqSession.Phase.BONUS_DECISION
                || phase == AgentHpqSession.Phase.BONUS_FARMING || phase == AgentHpqSession.Phase.CLAIMING_REWARD;
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            boolean wanted = !ending && session.companionAdopted() && claimPhase && agent != null
                    && inRun(session, agent) && !member.rewardResolved();
            AgentHpqFullRateSimulation.apply(
                    member, AgentRuntimeRegistry.findByAgentCharacterId(member.characterId()), wanted);
        }
    }

    /** This character stands in the run's own event instance. */
    private static boolean inRun(AgentHpqSession session, Character character) {
        EventInstanceManager event = session.eventInstance();
        return character != null && event != null && hpq().event(character) == event;
    }

    /** A loaded companion of the run that is online, standing and not marked down; null otherwise. */
    private static Character standingAgent(AgentHpqMemberState member) {
        if (member.memberType() != AgentHpqMemberState.MemberType.AGENT || member.down()) return null;
        Character agent = character(member.characterId());
        return agent != null && agent.getHp() > 0 ? agent : null;
    }

    /**
     * Who says the run's lines: the first companion standing in the run (up, not marked down, and in its event once
     * it has one), else the first companion online even when it is down; null when none is.
     */
    private static Character narrator(AgentHpqSession session) {
        EventInstanceManager event = session.eventInstance();
        Character online = null;
        for (AgentHpqMemberState member : session.members()) {
            if (member.memberType() != AgentHpqMemberState.MemberType.AGENT) continue;
            Character agent = character(member.characterId());
            if (agent == null) continue;
            if (standingAgent(member) != null && (event == null || hpq().event(agent) == event)) return agent;
            if (online == null) online = agent;
        }
        return online;
    }

    private static List<MapItem> droppedItems(Character character) {
        MapleMap map = character.getMap();
        Collection<MapItem> drops = map == null ? null : map.getDroppedItems();
        return drops == null ? List.of() : List.copyOf(drops);
    }

    /**
     * A companion run hears its companions talk like players (a null companion line says nothing), never a status
     * line; a stock or test run keeps the plain lines it always had.
     */
    static String voice(AgentHpqSession session, String companionLine, String stockLine) {
        return session.companionAdopted() ? companionLine : stockLine;
    }

    /** True when the line was said now; each key is said once a run. */
    private static boolean narrate(AgentHpqSession session, Character speaker, String key, String line) {
        if (speaker == null || line == null || !session.narrateOnce(key)) return false;
        AgentHpqDialogue.sayMapNow(session.sessionId(), key, speaker, line);
        return true;
    }

    /** A companion run's coordination line, while its host has the members' coordination chat on. */
    private static boolean coordinate(AgentHpqSession session, Character speaker, String key, String line) {
        if (!session.companionAdopted() || !session.memberCoordinationChatEnabled()) return false;
        return narrate(session, speaker, key, line);
    }

    /** One line per phase with its time, for every run: its pace reads straight from the log. */
    private static void transition(AgentHpqSession session, AgentHpqSession.Phase phase, long nowMs) {
        log.info("HPQ phase: session={} {}->{} phaseMs={} runMs={} companionRun={}",
                session.sessionId(), session.phase(), phase, nowMs - session.phaseEnteredAtMs(),
                nowMs - session.startedAtMs(), session.companionAdopted());
        session.transition(phase, nowMs);
    }

    /** CAKE_RECOLLECT_MS, clamped (with one WARN) below the rice cakes' expiry margin. */
    private static long recollectAfterMs(long configuredMs) {
        long clamped = AgentHpqCompanionPlay.recollectAfterMs(
                configuredMs, AgentHpqDefinition.RICE_CAKE_RECOLLECT_LATEST_MS);
        if (clamped != configuredMs) {
            log.warn("HPQ CAKE_RECOLLECT_MS {} would let a stack expire before it is picked back up; using {}",
                    configuredMs, clamped);
        }
        return clamped;
    }

    private static int eventStage(Character character) {
        return intProperty(character, "stage");
    }

    private static int intProperty(Character character, String key) {
        String value = hpq().property(character, key);
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Set<Integer> stageMonsters(Character character) {
        Set<Integer> monsters = new LinkedHashSet<>(actions().configuredMonsterSpawnIds(character));
        monsters.remove(AgentHpqDefinition.MOON_BUNNY);
        return Set.copyOf(monsters);
    }

    private static boolean near(Point first, Point second, int radius) {
        return near(first, second, radius, radius);
    }

    private static boolean near(Point first, Point second, int horizontalRadius, int verticalRadius) {
        return first != null && second != null
                && Math.abs(first.x - second.x) <= horizontalRadius
                && Math.abs(first.y - second.y) <= verticalRadius;
    }

    private static boolean insideEvent(AgentHpqSession.Phase phase) {
        return AgentHpqMemberVerdicts.insideEvent(phase);
    }

    /**
     * The gateways are resolved per call (each is one adapter singleton), so a test can stand in for them without a
     * class loaded under its mock keeping that mock for every later test.
     */
    private static PrimitiveCapabilityGateway actions() {
        return AgentPrimitiveCapabilityGatewayRuntime.gateway();
    }

    private static PartyQuestGateway hpq() {
        return AgentPartyQuestGatewayRuntime.partyQuest();
    }

    private static Character character(int characterId) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent
                : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(characterId);
    }
}
