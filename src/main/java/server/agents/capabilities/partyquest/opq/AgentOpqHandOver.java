package server.agents.capabilities.partyquest.opq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Comparator;
import java.util.List;

/**
 * 2026-10-18: a human-led run's fragment hand-overs (PPQ's medal hand-over, AgentPpqMedalHandOver): Eak takes the
 * walkway's thirty and the lounge's forty only from the event leader's bag, so the companions bring them to him. Once
 * the party's fragments (his bag, the companions' bags and the stacks lying for him in the room) make the quota and he
 * stands in the room, each holder walks to him and drops its whole stack where the map lands it at his feet, or right
 * next to him; a leader it cannot reach, or a walk past TIMEOUT_MS, gets the stack by Eak. Until he comes the holders
 * wait by Eak (AgentOpqConductor calls him). A stack he leaves lying is pointed out once and, before it can expire,
 * picked back up and dropped for him again, at most RECOLLECT_MAX times; only the companion that claimed it may take it
 * (AgentOpqSessionRegistry.canLootExclusive with the drop). Human-led runs only. The garden's Root of Life goes to
 * him the same way (a quota of one, the garden's exit in place of Eak; AgentOpqConductedGarden), with its own lines.
 */
final class AgentOpqHandOver {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqHandOver.class);
    static final int REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqHandOver.REACH_PX");
    static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqHandOver.TIMEOUT_MS");
    static final long PICKUP_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqHandOver.PICKUP_NUDGE_MS");
    static final long RECOLLECT_MS = recollectAfterMs(config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqHandOver.RECOLLECT_MS"));
    static final int RECOLLECT_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqHandOver.RECOLLECT_MAX");
    static final long LINE_WINDOW_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqHandOver.LINE_WINDOW_MS");
    /** The shipped item expiry (config.yaml ITEM_EXPIRE_TIME 180000) less one ITEM_EXPIRE_CHECK sweep (10000). */
    static final long RECOLLECT_LATEST_MS = 170_000L;

    /** What a holder does with its stack this pass. */
    enum HandOver { WALK, DROP_AT_FEET, DROP_BESIDE, BY_EAK }

    /** Where a dropped stack lies, for its line. */
    enum Place { FEET, BESIDE, EAK }

    private AgentOpqHandOver() {
    }

    /**
     * One pass for one companion of the room whose fragments ({@code itemId}, {@code quota} of them) Eak takes on
     * {@code roomMapId}, at {@code eak}: a stack it claimed back, its open hand-over, a hand-over that is due now (or the
     * wait by Eak for the leader), and the nudges and re-collect claims of stacks lying here, in that order. True when
     * it owns the pass.
     */
    static boolean tick(AgentOpqSession session, AgentRuntimeEntry entry, Character agent, AgentOpqMemberState member,
                        int itemId, int quota, int roomMapId, Point eak, long nowMs) {
        Character leader = AgentOpqCoordinator.character(session.eventLeaderId());
        int held = Math.max(0, agent.getItemQuantity(itemId, false));
        if (member.handOverItemId() != 0 && member.handOverItemId() != itemId) member.endHandOver();
        boolean here = agent.getMapId() == roomMapId;
        List<MapItem> stacks = here ? lyingStacks(session, agent.getMap(), itemId) : List.of();
        int claimed = member.claimObjectId();
        if (claimed != 0) {
            MapItem stack = stacks.stream().filter(drop -> drop.getObjectId() == claimed).findFirst().orElse(null);
            if (stack != null) {
                pickUp(session, entry, agent, stack, nowMs);
                return true;
            }
            int lineage = session.endClaim(claimed);
            member.clearClaim();
            if (held > member.heldBefore()) {
                member.startHandOver(itemId, nowMs, lineage + 1);
                logRecollect(session, agent, itemId, claimed, lineage + 1);
            }
        }
        if (!here) return false;
        if (member.handOverStartedAtMs() > 0L) {
            if (held > 0) {
                walkOrDrop(session, entry, agent, member, itemId, held, leader, eak, nowMs);
                return true;
            }
            member.endHandOver();
        }
        if (held > 0 && partyTotal(session, agent.getMap(), leader, itemId) >= quota) {
            if (leaderHere(leader, agent)) {
                member.startHandOver(itemId, nowMs, 0);
                walkOrDrop(session, entry, agent, member, itemId, held, leader, eak, nowMs);
            } else {
                waitByEak(entry, agent, eak);
            }
            return true;
        }
        return nudgeAndClaim(session, entry, agent, member, itemId, held, leader, stacks, nowMs);
    }

    /**
     * The party's fragments of this kind for Eak: the leader's bag, every counted Agent's bag, and the stacks the
     * companions dropped for him still lying on {@code roomMap} (none when it is null).
     */
    static int partyTotal(AgentOpqSession session, MapleMap roomMap, Character leader, int itemId) {
        int total = leader == null ? 0 : Math.max(0, leader.getItemQuantity(itemId, false));
        for (AgentOpqMemberState member : AgentOpqCoordinator.counted(session)) {
            if (member.memberType() != AgentOpqMemberState.MemberType.AGENT) continue;
            if (leader != null && member.characterId() == leader.getId()) continue;
            Character character = AgentOpqCoordinator.character(member.characterId());
            if (character != null) total += Math.max(0, character.getItemQuantity(itemId, false));
        }
        return total + lyingStacks(session, roomMap, itemId).stream().mapToInt(AgentOpqHandOver::stackSize).sum();
    }

    /** The stacks of this kind the run's Agents dropped on {@code map}, still lying. */
    static List<MapItem> lyingStacks(AgentOpqSession session, MapleMap map, int itemId) {
        if (map == null) return List.of();
        return AgentMapPerception.items(map).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.isPlayerDrop()
                        && drop.getItemId() == itemId && drop.getPosition() != null)
                .filter(drop -> session.agentOfRun(drop.getOwnerId()))
                .toList();
    }

    /** The open hand-over: walk to the leader's spot, drop at his feet or beside him, or take it to Eak. */
    private static void walkOrDrop(AgentOpqSession session, AgentRuntimeEntry entry, Character agent,
                                   AgentOpqMemberState member, int itemId, int held, Character leader, Point eak,
                                   long nowMs) {
        PrimitiveCapabilityGateway actions = AgentOpqCoordinator.actions();
        boolean onGround = actions.footing(agent) == PrimitiveCapabilityGateway.Footing.GROUND;
        Point spot = leader == null ? null : groundSpot(leader);
        if (!member.handOverByEak()) {
            if (!leaderHere(leader, agent)) {
                // He left the room before the drop: the stack stays in the bag and the holder waits by Eak again.
                member.endHandOver();
                waitByEak(entry, agent, eak);
                return;
            }
            boolean leaderOnGround = actions.footing(leader) == PrimitiveCapabilityGateway.Footing.GROUND;
            boolean reachable = spot != null && (!onGround || actions.canReach(entry, agent, spot));
            Point landing = atFeet(agent.getPosition(), spot, onGround, leaderOnGround, REACH_PX)
                    ? actions.predictDropLanding(agent, agent.getPosition()) : null;
            HandOver branch = handOver(agent.getPosition(), spot, onGround, leaderOnGround, landing, reachable,
                    nowMs - member.handOverStartedAtMs(), TIMEOUT_MS, REACH_PX);
            switch (branch) {
                case WALK -> {
                    actions.navigate(entry, spot, true);
                    return;
                }
                case DROP_AT_FEET, DROP_BESIDE -> {
                    drop(session, entry, agent, member, itemId, held, leader, spot, branch, nowMs);
                    return;
                }
                default -> member.sendHandOverByEak();
            }
        }
        Point eakGround = eakGround(agent, eak);
        if (eakGround == null || atFeet(agent.getPosition(), eakGround, onGround, true, REACH_PX)) {
            drop(session, entry, agent, member, itemId, held, leader, spot, HandOver.BY_EAK, nowMs);
            return;
        }
        actions.navigate(entry, eakGround, true);
    }

    /** Drops the whole stack in one drop, says where it lies (by where the map lands it), and logs it. */
    private static void drop(AgentOpqSession session, AgentRuntimeEntry entry, Character agent,
                             AgentOpqMemberState member, int itemId, int held, Character leader, Point spot,
                             HandOver branch, long nowMs) {
        PrimitiveCapabilityGateway actions = AgentOpqCoordinator.actions();
        Point at = agent.getPosition() == null ? null : new Point(agent.getPosition());
        Point landing = actions.predictDropLanding(agent, at);
        actions.stop(entry);
        if (!AgentOpqCoordinator.dropItem(entry, InventoryType.ETC, itemId, (short) held)) return;
        Place place = place(branch, landing, spot, REACH_PX);
        int lineage = member.handOverRecollectCount();
        member.endHandOver();
        member.deferUntil(nowMs + AgentOpqCoordinator.actionRetryMs());
        int stack = newestOwnStack(agent, itemId);
        if (stack != 0 && lineage > 0) session.setRecollectCount(stack, lineage);
        session.markProgress(nowMs);
        logHandOver(session, agent, itemId, held, place, lineage > 0, leader, at, landing);
        if (leader == null) return;
        String name = leader.getName();
        if (!session.claimLineWindow("handover-" + itemId, nowMs, LINE_WINDOW_MS)) return;
        AgentOpqDialogue.say(session, "handover-" + place.name().toLowerCase(java.util.Locale.ROOT), agent, leader,
                line(itemId, name, place, lineage > 0));
    }

    /** The hand-over's line: the fragments' (pick them up and talk to Eak), or the Root's (only he takes the exit). */
    static String line(int itemId, String leader, Place place, boolean recollect) {
        if (itemId == AgentOpqDefinition.ROOT_OF_LIFE) {
            return recollect ? AgentOpqCompanionPlay.rootPickedBackUp()
                    : place == Place.EAK ? AgentOpqCompanionPlay.rootByTheExit(leader)
                    : AgentOpqCompanionPlay.rootAtYourFeet(leader, place == Place.FEET);
        }
        return recollect ? AgentOpqCompanionPlay.pickedBackUp()
                : place == Place.EAK ? AgentOpqCompanionPlay.byEak(leader)
                : AgentOpqCompanionPlay.atYourFeet(leader, place == Place.FEET);
    }

    /**
     * The nudge on a stack this companion dropped for the leader, and the re-collect claims of stacks lying here: a
     * stack goes back to its dropper while it stands here, else to the lowest-id companion standing here. The first
     * claimer wins. True when this companion claimed one and set out for it.
     */
    private static boolean nudgeAndClaim(AgentOpqSession session, AgentRuntimeEntry entry, Character agent,
                                         AgentOpqMemberState member, int itemId, int held, Character leader,
                                         List<MapItem> stacks, long nowMs) {
        for (MapItem stack : stacks) {
            if (stack.getOwnerId() == agent.getId() && leader != null
                    && nudgeDue(stack.getDropTime(), nowMs, PICKUP_NUDGE_MS)
                    && session.narrateOnce("handover-nudge-" + stack.getObjectId())
                    && session.claimLineWindow("handover-nudge-" + itemId, nowMs, PICKUP_NUDGE_MS)) {
                AgentOpqDialogue.say(session, "handover-nudge", agent, leader,
                        itemId == AgentOpqDefinition.ROOT_OF_LIFE
                                ? AgentOpqCompanionPlay.rootStillOnTheFloor(leader.getName())
                                : AgentOpqCompanionPlay.stillOnTheFloor(leader.getName()));
            }
        }
        for (MapItem stack : stacks.stream().sorted(Comparator.comparingLong(MapItem::getDropTime)).toList()) {
            int objectId = stack.getObjectId();
            if (session.claimerOf(objectId) != 0 || !recollectDue(stack.getDropTime(), nowMs, RECOLLECT_MS,
                    session.recollectCount(objectId), RECOLLECT_MAX)) {
                continue;
            }
            if (claimer(session, agent, stack) != agent.getId()) continue;
            if (!session.beginClaim(objectId, agent.getId())) continue;
            member.claimStack(objectId, held);
            pickUp(session, entry, agent, stack, nowMs);
            return true;
        }
        return false;
    }

    /** Who picks this stack back up: its dropper while it stands here, else the lowest-id companion standing here. */
    private static int claimer(AgentOpqSession session, Character agent, MapItem stack) {
        Character dropper = AgentOpqCoordinator.character(stack.getOwnerId());
        if (dropper != null && dropper.getHp() > 0 && dropper.getMapId() == agent.getMapId()
                && session.agentOfRun(dropper.getId()) && !session.departed(dropper.getId())) {
            return dropper.getId();
        }
        return AgentOpqCoordinator.counted(session).stream()
                .filter(other -> other.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .map(other -> AgentOpqCoordinator.character(other.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(other -> other.getHp() > 0 && other.getMapId() == agent.getMapId())
                .mapToInt(Character::getId).min().orElse(0);
    }

    /** Walks to the stack and picks it up once within reach; the loot rule lets only its claimer take it. */
    static void pickUp(AgentOpqSession session, AgentRuntimeEntry entry, Character agent, MapItem stack,
                       long nowMs) {
        PrimitiveCapabilityGateway actions = AgentOpqCoordinator.actions();
        if (AgentOpqCoordinator.withinLootRadius(agent.getPosition(), stack.getPosition())) {
            actions.stop(entry);
            agent.pickupItem(stack);
            session.markProgress(nowMs);
            return;
        }
        Point ground = actions.groundPoint(agent.getMap(), stack.getPosition());
        actions.navigate(entry, ground == null ? stack.getPosition() : ground, true);
    }

    private static void waitByEak(AgentRuntimeEntry entry, Character agent, Point eak) {
        Point ground = eakGround(agent, eak);
        if (ground == null || near(agent.getPosition(), ground, REACH_PX)) AgentOpqCoordinator.actions().stop(entry);
        else AgentOpqCoordinator.actions().navigate(entry, ground, true);
    }

    private static Point eakGround(Character agent, Point eak) {
        if (eak == null) return null;
        Point ground = AgentOpqCoordinator.actions().groundPoint(agent.getMap(), eak);
        return ground == null ? new Point(eak) : ground;
    }

    private static boolean leaderHere(Character leader, Character agent) {
        return leader != null && leader.getHp() > 0 && leader.getMapId() == agent.getMapId();
    }

    private static int stackSize(MapItem drop) {
        return drop.getItem() == null ? 1 : Math.max(1, drop.getItem().getQuantity());
    }

    /** The stack this companion has just dropped: its own newest player drop of the kind on its map; 0 when none. */
    private static int newestOwnStack(Character agent, int itemId) {
        if (agent.getMap() == null) return 0;
        return AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.isPlayerDrop()
                        && drop.getItemId() == itemId && drop.getOwnerId() == agent.getId())
                .max(Comparator.comparingInt(MapItem::getObjectId))
                .map(MapItem::getObjectId).orElse(0);
    }

    /** 'OPQ handover:' for every fragment hand-over drop. Nothing decides by it; never throws. */
    private static void logHandOver(AgentOpqSession session, Character agent, int itemId, int count, Place place,
                                    boolean recollect, Character leader, Point at, Point landing) {
        try {
            log.info("OPQ handover: session={} item={} member={} count={} place={} recollect={} leader={} at={} "
                            + "landing={}", session.sessionId(), itemId, agent.getName(), count, place, recollect,
                    leader == null ? "none" : leader.getName(), xy(at), xy(landing));
        } catch (RuntimeException e) {
            log.warn("OPQ handover line failed: session={}", session.sessionId(), e);
        }
    }

    /** 'OPQ recollect:' once a companion has picked a stack back up. Nothing decides by it; never throws. */
    private static void logRecollect(AgentOpqSession session, Character agent, int itemId, int stack, int count) {
        try {
            log.info("OPQ recollect: session={} item={} member={} stack={} count={}", session.sessionId(), itemId,
                    agent.getName(), stack, count);
        } catch (RuntimeException e) {
            log.warn("OPQ recollect line failed: session={}", session.sessionId(), e);
        }
    }

    // ---- pure rules -----------------------------------------------------------------------------------------------

    /** What the holder does now (PPQ's handOver): walk, drop at his feet or right beside him, or go to Eak. */
    static HandOver handOver(Point carrier, Point leaderSpot, boolean carrierOnGround, boolean leaderOnGround,
                             Point landing, boolean leaderReachable, long walkingMs, long timeoutMs, int reachPx) {
        if (!leaderReachable || walkingMs > timeoutMs) return HandOver.BY_EAK;
        if (!atFeet(carrier, leaderSpot, carrierOnGround, leaderOnGround, reachPx)) return HandOver.WALK;
        if (landing == null || landsAt(landing, leaderSpot, reachPx)) return HandOver.DROP_AT_FEET;
        if (Math.abs(landing.y - leaderSpot.y) <= reachPx) return HandOver.DROP_BESIDE;
        return HandOver.WALK;
    }

    /** Both on a foothold, within {@code px} of each other on both axes. */
    static boolean atFeet(Point dropper, Point spot, boolean dropperOnGround, boolean spotOnGround, int px) {
        return dropper != null && spot != null && dropperOnGround && spotOnGround
                && Math.abs(dropper.x - spot.x) <= px && Math.abs(dropper.y - spot.y) <= px;
    }

    /** A drop the map lands within {@code px} of this spot on both axes. */
    static boolean landsAt(Point landing, Point spot, int px) {
        return landing != null && spot != null
                && Math.abs(landing.x - spot.x) <= px && Math.abs(landing.y - spot.y) <= px;
    }

    /** Where the stack lies for its line: at his feet, right beside him (also an Eak drop landing by him), or by Eak. */
    static Place place(HandOver branch, Point landing, Point leaderSpot, int reachPx) {
        if (branch == HandOver.DROP_AT_FEET) return Place.FEET;
        if (branch == HandOver.DROP_BESIDE) return Place.BESIDE;
        return landsAt(landing, leaderSpot, reachPx) ? Place.BESIDE : Place.EAK;
    }

    /** A stack lying {@code recollectAfterMs} since its drop is picked back up, fewer than {@code max} times. */
    static boolean recollectDue(long droppedAtMs, long nowMs, long recollectAfterMs, int recollectsDone, int max) {
        return recollectsDone < max && nowMs - droppedAtMs >= recollectAfterMs;
    }

    /** The nudge is due: the stack has lain {@code nudgeMs} since its drop. */
    static boolean nudgeDue(long droppedAtMs, long nowMs, long nudgeMs) {
        return nowMs - droppedAtMs >= nudgeMs;
    }

    /** The configured re-collect delay, never past the latest a stack can be picked back up before it expires. */
    static long recollectAfterMs(long configuredMs, long latestMs) {
        return Math.min(configuredMs, latestMs);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static boolean near(Point first, Point second, int px) {
        return first != null && second != null && Math.abs(first.x - second.x) <= px
                && Math.abs(first.y - second.y) <= px;
    }

    private static Point groundSpot(Character character) {
        Point position = character.getPosition();
        if (position == null) return null;
        Point grounded = AgentOpqCoordinator.actions().groundPoint(character.getMap(), position);
        return grounded == null ? new Point(position) : grounded;
    }

    private static String xy(Point point) {
        return point == null ? "-" : "(" + point.x + "," + point.y + ")";
    }

    /** RECOLLECT_MS, clamped (with one WARN) below the items' expiry margin on this server. */
    private static long recollectAfterMs(long configuredMs) {
        config.ServerConfig server = config.YamlConfig.config == null ? null : config.YamlConfig.config.server;
        long latest = server == null || server.ITEM_EXPIRE_TIME <= 0 ? RECOLLECT_LATEST_MS
                : Math.max(0L, server.ITEM_EXPIRE_TIME - Math.max(0L, server.ITEM_EXPIRE_CHECK));
        long clamped = recollectAfterMs(configuredMs, latest);
        if (clamped != configuredMs) {
            log.warn("OPQ RECOLLECT_MS {} would let a fragment stack expire before it is picked back up; using {}",
                    configuredMs, clamped);
        }
        return clamped;
    }
}
