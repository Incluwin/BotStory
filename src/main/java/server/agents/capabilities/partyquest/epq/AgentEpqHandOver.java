package server.agents.capabilities.partyquest.epq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;

import java.awt.Point;
import java.util.Comparator;
import java.util.List;

/**
 * A companion run's hand-overs at the leader's feet (the KPQ and HPQ pattern): a companion carrying the monster marbles
 * (930000400) or the purple stone (930000500) walks to its leader and drops the whole stack where the map lands it at
 * his feet, or right next to him; a leader it cannot reach, or a walk past its limit, gets the stack by the stage's
 * NPC. A stack he leaves lying is pointed out once and, before it can expire, picked back up and dropped for him
 * again, at most RECOLLECT_MAX times; a carrier about to go down leaves its stack where it stands. It runs for every
 * companion standing on the stage's map, carrier or not, on that companion's own tick, and reads its own member state,
 * the session and the characters only. Companion runs only.
 */
final class AgentEpqHandOver {
    private static final Logger log = LoggerFactory.getLogger(AgentEpqHandOver.class);
    static final int MARBLE_BATCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqHandOver.MARBLE_BATCH");
    static final int NEAR_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqHandOver.NEAR_PX");
    static final int REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqHandOver.REACH_PX");
    static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqHandOver.TIMEOUT_MS");
    static final long STONE_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqHandOver.STONE_TIMEOUT_MS");
    static final long PICKUP_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqHandOver.PICKUP_NUDGE_MS");
    static final long RECOLLECT_MS = recollectAfterMs(config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqHandOver.RECOLLECT_MS"));
    static final int RECOLLECT_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqHandOver.RECOLLECT_MAX");
    static final int DYING_HP_PERCENT = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqHandOver.DYING_HP_PERCENT");

    private AgentEpqHandOver() {
    }

    /**
     * One pass for one companion standing on the stage's map: a dying carrier's drop, a re-collect it claimed, its open
     * hand-over, a hand-over that is due now, and the nudges and re-collect claims of stacks lying on this map, in that
     * order. True when it owns the pass.
     */
    static boolean tick(AgentEpqSession session, AgentRuntimeEntry entry, Character agent, AgentEpqMemberState member,
                        int itemId, int npcId, long nowMs) {
        Character leader = AgentEpqCoordinator.character(session.eventLeaderId());
        int held = Math.max(0, actions().itemCount(agent, itemId));
        if (member.handOverItemId() != 0 && member.handOverItemId() != itemId) member.endHandOver();
        if (held > 0 && aboutToGoDown(agent)) {
            leaveHere(session, entry, agent, member, itemId, held, nowMs);
            return true;
        }
        List<MapItem> stacks = lyingStacks(session, agent, itemId);
        int claimed = session.recollectOf(agent.getId());
        if (claimed != 0) {
            MapItem stack = stacks.stream().filter(drop -> drop.getObjectId() == claimed).findFirst().orElse(null);
            if (stack != null) {
                pickUp(entry, agent, stack);
                return true;
            }
            int lineage = session.endRecollect(claimed);
            if (held > member.heldBefore()) {
                member.startHandOver(itemId, nowMs, true, lineage + 1);
                logRecollect(session, agent, itemId, claimed, lineage + 1, nowMs);
            }
        }
        if (member.handOverStartedAtMs() > 0L) {
            if (held > 0) {
                walkOrDrop(session, entry, agent, member, itemId, npcId, held, leader, nowMs);
                return true;
            }
            member.endHandOver();
        }
        if (held > 0 && due(session, agent, itemId, held, leader)) {
            member.startHandOver(itemId, nowMs, false, 0);
            walkOrDrop(session, entry, agent, member, itemId, npcId, held, leader, nowMs);
            return true;
        }
        return nudgeAndClaim(session, entry, agent, member, itemId, held, leader, stacks, nowMs);
    }

    /** Whether a carrier holding {@code held} of this item sets out now. */
    private static boolean due(AgentEpqSession session, Character agent, int itemId, int held, Character leader) {
        if (itemId != AgentEpqDefinition.MONSTER_MARBLE) return true;
        boolean leaderNear = leader != null && leader.getMap() == agent.getMap()
                && AgentEpqCoordinator.near(agent.getPosition(), leader.getPosition(), NEAR_PX);
        return AgentEpqCompanionPlay.marbleHandOverDue(held,
                AgentEpqCoordinator.stageItemTotal(session, agent, AgentEpqDefinition.MONSTER_MARBLE), leaderNear,
                AgentEpqCoordinator.flowers(agent).isEmpty(), MARBLE_BATCH,
                AgentEpqDefinition.REQUIRED_MONSTER_MARBLES);
    }

    /** The open hand-over: walk to the leader's spot, drop at his feet or beside him, or take it to the NPC. */
    private static void walkOrDrop(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                   AgentEpqMemberState member, int itemId, int npcId, int held, Character leader,
                                   long nowMs) {
        boolean onGround = actions().footing(agent) == PrimitiveCapabilityGateway.Footing.GROUND;
        Point spot = leader == null ? null : groundSpot(leader);
        if (!member.handOverToNpc()) {
            boolean leaderThere = leader != null && leader.getHp() > 0 && leader.getMap() == agent.getMap()
                    && AgentEpqCompanionRun.inBoundEvent(session, leader);
            boolean leaderOnGround = leaderThere
                    && actions().footing(leader) == PrimitiveCapabilityGateway.Footing.GROUND;
            boolean reachable = leaderThere && spot != null && (!onGround || actions().canReach(entry, agent, spot));
            Point landing = AgentEpqCompanionPlay.atFeet(agent.getPosition(), spot, onGround, leaderOnGround,
                    REACH_PX) ? actions().predictDropLanding(agent, agent.getPosition()) : null;
            AgentEpqCompanionPlay.HandOver branch = AgentEpqCompanionPlay.handOver(agent.getPosition(), spot,
                    onGround, leaderOnGround, landing, reachable, nowMs - member.handOverStartedAtMs(),
                    timeoutMs(itemId), REACH_PX);
            switch (branch) {
                case WALK -> {
                    actions().navigate(entry, spot, true);
                    return;
                }
                case DROP_AT_FEET, DROP_BESIDE -> {
                    drop(session, entry, agent, member, itemId, held, leader, spot, branch, npcId != 0, nowMs);
                    return;
                }
                default -> member.sendHandOverToNpc();
            }
        }
        Point npc = npcId == 0 ? null : AgentEpqCoordinator.npcApproachPoint(agent, npcId);
        if (npc == null || AgentEpqCompanionPlay.atFeet(agent.getPosition(), npc, onGround, true, REACH_PX)) {
            drop(session, entry, agent, member, itemId, held, leader, spot, AgentEpqCompanionPlay.HandOver.TO_NPC,
                    npcId != 0, nowMs);
            return;
        }
        actions().navigate(entry, npc, true);
    }

    /** Drops the whole stack in one drop, says where it lies (by where the map lands it), and logs it. */
    private static void drop(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                             AgentEpqMemberState member, int itemId, int held, Character leader, Point spot,
                             AgentEpqCompanionPlay.HandOver branch, boolean toNpc, long nowMs) {
        Point at = agent.getPosition() == null ? null : new Point(agent.getPosition());
        Point landing = actions().predictDropLanding(agent, at);
        actions().stop(entry);
        int lyingBefore = lyingTotal(session, agent, itemId);
        if (!AgentEpqCoordinator.dropItem(entry, agent, InventoryType.ETC, itemId, (short) held)) return;
        AgentEpqCompanionPlay.Place place = AgentEpqCompanionPlay.place(branch, landing, spot, REACH_PX, toNpc);
        boolean recollect = member.handOverRecollect();
        int lineage = member.handOverRecollectCount();
        member.endHandOver();
        int stack = newestOwnStack(agent, itemId);
        if (stack != 0 && lineage > 0) session.setRecollectCount(stack, lineage);
        int handOver = session.nextHandOver();
        session.markProgress(nowMs);
        // The stone is handed off: the collector no longer picks up a stone lying on this map (Step 2.8).
        if (itemId == AgentEpqDefinition.MAGIC_STONE) session.markStageFiveStoneHandedOff(nowMs);
        if (itemId == AgentEpqDefinition.GOLEMS_MARBLE) {
            session.noteGolemMarble(place == AgentEpqCompanionPlay.Place.FEET
                    || place == AgentEpqCompanionPlay.Place.BESIDE ? "handed" : "dropped");
        }
        logHandOver(session, agent, itemId, held, place, recollect, leader, at, landing);
        say(session, agent, itemId, held, place, recollect, leader, lyingBefore, handOver);
    }

    /**
     * Drops the whole stack where this companion stands, without a word ('EPQ handover: ... place=HERE'): the golem's
     * marble before the companions leave, when the leader cannot take it (Step 2.9).
     */
    static void dropHere(AgentEpqSession session, AgentRuntimeEntry entry, Character agent, AgentEpqMemberState member,
                         int itemId, int held, long nowMs) {
        Point at = agent.getPosition() == null ? null : new Point(agent.getPosition());
        Point landing = actions().predictDropLanding(agent, at);
        actions().stop(entry);
        if (!AgentEpqCoordinator.dropItem(entry, agent, InventoryType.ETC, itemId, (short) held)) return;
        member.endHandOver();
        session.markProgress(nowMs);
        if (itemId == AgentEpqDefinition.GOLEMS_MARBLE) session.noteGolemMarble("dropped");
        logHandOver(session, agent, itemId, held, AgentEpqCompanionPlay.Place.HERE, false,
                AgentEpqCoordinator.character(session.eventLeaderId()), at, landing);
    }

    /** A carrier about to go down drops its stack where it stands, says so, and the stack is due back at once. */
    private static void leaveHere(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                  AgentEpqMemberState member, int itemId, int held, long nowMs) {
        Point at = agent.getPosition() == null ? null : new Point(agent.getPosition());
        Point landing = actions().predictDropLanding(agent, at);
        actions().stop(entry);
        if (!AgentEpqCoordinator.dropItem(entry, agent, InventoryType.ETC, itemId, (short) held)) return;
        int lineage = member.handOverStartedAtMs() > 0L ? member.handOverRecollectCount() : 0;
        member.endHandOver();
        int stack = newestOwnStack(agent, itemId);
        if (stack != 0) {
            session.markDyingStack(stack);
            if (lineage > 0) session.setRecollectCount(stack, lineage);
        }
        session.markProgress(nowMs);
        logHandOver(session, agent, itemId, held, AgentEpqCompanionPlay.Place.DYING, false,
                AgentEpqCoordinator.character(session.eventLeaderId()), at, landing);
        String line = AgentEpqCompanionPlay.leavingLine(itemId);
        if (line != null) {
            AgentEpqCompanionRun.coordinate(session, agent,
                    AgentEpqCompanionPlay.itemKey(itemId) + "-dying-" + agent.getId(), line);
        }
    }

    /**
     * The nudge on a stack this companion dropped for the leader, and the re-collect claims of stacks lying on this map:
     * a hand-over stack goes back to its dropper while it stands here, else to the work Agent; a dying carrier's stack
     * to the work Agent, else to any companion here that is not about to go down itself. The first claimer wins. True
     * when this companion claimed one and set out for it.
     */
    private static boolean nudgeAndClaim(AgentEpqSession session, AgentRuntimeEntry entry, Character agent,
                                         AgentEpqMemberState member, int itemId, int held, Character leader,
                                         List<MapItem> stacks, long nowMs) {
        for (MapItem stack : stacks) {
            boolean dying = session.dyingStack(stack.getObjectId());
            if (!dying && stack.getOwnerId() == agent.getId() && leader != null
                    && AgentEpqCompanionPlay.nudgeDue(stack.getDropTime(), nowMs, PICKUP_NUDGE_MS)) {
                AgentEpqCompanionRun.coordinate(session, agent,
                        AgentEpqCompanionPlay.itemKey(itemId) + "-nudge-" + stack.getObjectId(),
                        AgentEpqCompanionPlay.nudgeLine(itemId, leader.getName()));
            }
        }
        if (aboutToGoDown(agent)) return false;
        for (MapItem stack : stacks.stream().sorted(Comparator.comparingLong(MapItem::getDropTime)).toList()) {
            int objectId = stack.getObjectId();
            boolean dying = session.dyingStack(objectId);
            if (session.recollectorOf(objectId) != 0 || !AgentEpqCompanionPlay.recollectDue(stack.getDropTime(),
                    nowMs, RECOLLECT_MS, dying, session.recollectCount(objectId), RECOLLECT_MAX)) {
                continue;
            }
            if (claimer(session, agent, stack, dying) != agent.getId()) continue;
            if (!session.beginRecollect(objectId, agent.getId())) continue;
            member.setHeldBefore(held);
            pickUp(entry, agent, stack);
            return true;
        }
        return false;
    }

    /** Who picks this stack back up; this companion's id when any companion here may (a dying stack, no work Agent). */
    private static int claimer(AgentEpqSession session, Character agent, MapItem stack, boolean dying) {
        int work = session.workAgentId();
        Character workAgent = AgentEpqCoordinator.character(work);
        boolean workHere = workAgent != null && workAgent.getHp() > 0 && workAgent.getMap() == agent.getMap()
                && !aboutToGoDown(workAgent);
        if (dying) return workHere ? work : agent.getId();
        Character dropper = AgentEpqCoordinator.character(stack.getOwnerId());
        boolean dropperHere = dropper != null && dropper.getHp() > 0 && dropper.getMap() == agent.getMap()
                && session.member(dropper.getId()) != null;
        return dropperHere ? dropper.getId() : workHere ? work : 0;
    }

    /** Walks to the stack and loots it once within reach; the loot rule lets only this companion take it back. */
    private static void pickUp(AgentRuntimeEntry entry, Character agent, MapItem stack) {
        AgentEpqCoordinator.pickUp(entry, agent, stack);
    }

    /**
     * The stacks of this item the run's companions dropped on this map (hand-overs and dying drops), still lying,
     * including those of a companion since taken out of the run.
     */
    static List<MapItem> lyingStacks(AgentEpqSession session, Character observer, int itemId) {
        return AgentMapPerception.items(observer.getMap()).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.isPlayerDrop()
                        && drop.getItemId() == itemId && drop.getPosition() != null)
                .filter(drop -> session.companionOfRun(drop.getOwnerId()))
                .toList();
    }

    private static int lyingTotal(AgentEpqSession session, Character observer, int itemId) {
        return lyingStacks(session, observer, itemId).stream().mapToInt(AgentEpqHandOver::stackSize).sum();
    }

    private static int stackSize(MapItem drop) {
        return drop.getItem() == null ? 1 : Math.max(1, drop.getItem().getQuantity());
    }

    /** The stack this companion has just dropped: its own newest player drop of the item on its map; 0 when none. */
    private static int newestOwnStack(Character agent, int itemId) {
        return AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.isPlayerDrop()
                        && drop.getItemId() == itemId && drop.getOwnerId() == agent.getId())
                .max(Comparator.comparingInt(MapItem::getObjectId))
                .map(MapItem::getObjectId).orElse(0);
    }

    private static void say(AgentEpqSession session, Character agent, int itemId, int held,
                            AgentEpqCompanionPlay.Place place, boolean recollect, Character leader, int lyingBefore,
                            int handOver) {
        if (leader == null) return;
        String item = AgentEpqCompanionPlay.itemKey(itemId);
        String name = leader.getName();
        if (place == AgentEpqCompanionPlay.Place.NPC) {
            AgentEpqCompanionRun.coordinate(session, agent,
                    item + "-by-" + AgentEpqCompanionPlay.npcKey(itemId) + "-" + handOver,
                    AgentEpqCompanionPlay.byNpcLine(itemId, name));
            return;
        }
        if (place == AgentEpqCompanionPlay.Place.HERE) return;
        boolean atHisFeet = place == AgentEpqCompanionPlay.Place.FEET;
        if (recollect) {
            AgentEpqCompanionRun.coordinate(session, agent, item + "-recollected-" + handOver,
                    AgentEpqCompanionPlay.recollectedLine(itemId));
            return;
        }
        if (itemId == AgentEpqDefinition.MONSTER_MARBLE
                && AgentEpqCompanionPlay.makesTwenty(held, actions().itemCount(leader, itemId), lyingBefore,
                AgentEpqDefinition.REQUIRED_MONSTER_MARBLES)
                && AgentEpqCompanionRun.coordinate(session, agent, "marbles-twenty",
                AgentEpqCompanionPlay.marblesTwentyLine(name, atHisFeet))) {
            return;
        }
        AgentEpqCompanionRun.coordinate(session, agent,
                itemId == AgentEpqDefinition.GOLEMS_MARBLE ? "golem-marble-handed" : item + "-at-feet-" + handOver,
                AgentEpqCompanionPlay.atFeetLine(itemId, name, atHisFeet));
    }

    /** 'EPQ handover:' for every hand-over drop. Nothing decides by it; never throws. */
    private static void logHandOver(AgentEpqSession session, Character agent, int itemId, int count,
                                    AgentEpqCompanionPlay.Place place, boolean recollect, Character leader, Point at,
                                    Point landing) {
        try {
            log.info("EPQ handover: session={} item={} member={} count={} place={} recollect={} leader={} at={} "
                            + "landing={}", session.sessionId(), AgentEpqCompanionPlay.itemKey(itemId),
                    agent.getName(), count, place, recollect, leader == null ? "none" : leader.getName(),
                    AgentEpqCoordinator.xy(at), AgentEpqCoordinator.xy(landing));
        } catch (RuntimeException e) {
            log.warn("EPQ handover line failed: session={}", session.sessionId(), e);
        }
    }

    /** 'EPQ recollect:' once a companion has picked a stack back up. Nothing decides by it; never throws. */
    private static void logRecollect(AgentEpqSession session, Character agent, int itemId, int stack, int count,
                                     long nowMs) {
        try {
            log.info("EPQ recollect: session={} item={} member={} stack={} count={} dying={}", session.sessionId(),
                    AgentEpqCompanionPlay.itemKey(itemId), agent.getName(), stack, count,
                    session.dyingStack(stack));
        } catch (RuntimeException e) {
            log.warn("EPQ recollect line failed: session={}", session.sessionId(), e);
        }
    }

    /** About to go down: low on HP with no HP potion left. The HP is read first, since the potion count reads its bag. */
    static boolean aboutToGoDown(Character agent) {
        if (agent == null) return false;
        int hp = agent.getHp();
        int maxHp = agent.getCurrentMaxHp();
        return AgentEpqCompanionPlay.lowOnHp(hp, maxHp, DYING_HP_PERCENT)
                && AgentEpqCompanionPlay.dying(hp, maxHp, actions().hpPotionCount(agent), DYING_HP_PERCENT);
    }

    /** The walk limit: the stone's long climb down (STONE_TIMEOUT_MS), else TIMEOUT_MS. */
    static long timeoutMs(int itemId) {
        return itemId == AgentEpqDefinition.MAGIC_STONE ? STONE_TIMEOUT_MS : TIMEOUT_MS;
    }

    /** Where a character stands, on its foothold. */
    private static Point groundSpot(Character character) {
        Point position = character.getPosition();
        if (position == null) return null;
        Point grounded = actions().groundPoint(character.getMap(), position);
        return grounded == null ? new Point(position) : grounded;
    }

    /** RECOLLECT_MS, clamped (with one WARN) below the items' expiry margin on this server. */
    private static long recollectAfterMs(long configuredMs) {
        config.ServerConfig server = config.YamlConfig.config == null ? null : config.YamlConfig.config.server;
        long latest = server == null || server.ITEM_EXPIRE_TIME <= 0
                ? AgentEpqDefinition.OBJECTIVE_RECOLLECT_LATEST_MS
                : AgentEpqDefinition.objectiveRecollectLatestMs(server.ITEM_EXPIRE_TIME, server.ITEM_EXPIRE_CHECK);
        long clamped = AgentEpqCompanionPlay.recollectAfterMs(configuredMs, latest);
        if (clamped != configuredMs) {
            log.warn("EPQ RECOLLECT_MS {} would let a stack expire before it is picked back up; using {}",
                    configuredMs, clamped);
        }
        return clamped;
    }

    private static PrimitiveCapabilityGateway actions() {
        return AgentEpqCoordinator.actions();
    }
}
