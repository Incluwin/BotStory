package server.agents.capabilities.partyquest.lmpq;

import client.Character;
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
 * A human-led LMPQ run's coupon hand-over (P6h Step 2d, the shape of OPQ's AgentOpqHandOver and PPQ's medal
 * hand-over): Pietri takes the coupons only from the event leader's bag, so each companion carrying some walks to him
 * in the rendezvous room once he stands there and drops its whole stack where the map lands it at his feet, or right
 * next to him; one that cannot reach him, or whose walk passes COUPON_HANDOVER_TIMEOUT_MS, drops it where it stands
 * and says so. A stack he leaves lying is pointed out once (after COUPON_PICKUP_NUDGE_MS) and, before it can expire,
 * picked back up and dropped for him again (after COUPON_RECOLLECT_MS, clamped below the server's item expiry), at
 * most COUPON_RECOLLECT_MAX times; only the companion that claimed it may take it
 * (AgentLmpqSessionRegistry.preservesPortalMarker's hand-over clause). Companion runs only.
 */
final class AgentLmpqHandOver {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqHandOver.class);
    static final int REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.COUPON_HANDOVER_REACH_PX");
    static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.COUPON_HANDOVER_TIMEOUT_MS");
    static final long PICKUP_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.COUPON_PICKUP_NUDGE_MS");
    static final long RECOLLECT_MS = recollectAfterMs(config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.COUPON_RECOLLECT_MS"));
    static final int RECOLLECT_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.COUPON_RECOLLECT_MAX");
    static final long LINE_WINDOW_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqCoordinator.COUPON_HANDOVER_LINE_WINDOW_MS");
    /** The shipped item expiry (config.yaml ITEM_EXPIRE_TIME 180000) less one ITEM_EXPIRE_CHECK sweep (10000). */
    static final long RECOLLECT_LATEST_MS = 170_000L;

    /** What a carrier does with its stack this pass. */
    enum HandOver { WALK, DROP_AT_FEET, DROP_BESIDE, DROP_HERE }

    /** Where a dropped stack lies, for its line. */
    enum Place { FEET, BESIDE, HERE }

    private AgentLmpqHandOver() {
    }

    /**
     * One pass for one companion in the rendezvous room: a stack it claimed back, its open hand-over, a hand-over that
     * is due now (the leader stands here), and the nudges and re-collect claims of stacks lying here, in that order.
     * True when it owns the pass; false leaves the companion to wait at its spot.
     */
    static boolean tick(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                        AgentLmpqMemberState member, long nowMs) {
        Character leader = AgentLmpqCoordinator.character(session.eventLeaderId());
        int held = Math.max(0, agent.getItemQuantity(AgentLmpqDefinition.COUPON, false));
        boolean here = agent.getMapId() == AgentLmpqDefinition.mapForRoom(session.rendezvousRoom());
        List<MapItem> stacks = here ? lyingStacks(session, agent.getMap()) : List.of();
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
                member.startHandOver(nowMs, lineage + 1);
                logRecollect(session, agent, claimed, lineage + 1);
            }
        }
        if (!here) return false;
        if (member.handOverStartedAtMs() > 0L) {
            if (held > 0 && walkOrDrop(session, entry, agent, member, held, leader, nowMs)) return true;
            member.endHandOver();
        }
        if (held > 0 && leaderHere(leader, agent)) {
            member.startHandOver(nowMs, 0);
            return walkOrDrop(session, entry, agent, member, held, leader, nowMs);
        }
        return nudgeAndClaim(session, entry, agent, member, held, leader, stacks, nowMs);
    }

    /** The coupon stacks the run's Agents dropped on {@code map}, still lying. */
    static List<MapItem> lyingStacks(AgentLmpqSession session, MapleMap map) {
        if (map == null) return List.of();
        return AgentMapPerception.items(map).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.isPlayerDrop()
                        && drop.getItemId() == AgentLmpqDefinition.COUPON && drop.getPosition() != null)
                .filter(drop -> session.agentOfRun(drop.getOwnerId()))
                .toList();
    }

    /** The coupons in those stacks. */
    static int lyingCoupons(AgentLmpqSession session, MapleMap map) {
        return lyingStacks(session, map).stream().mapToInt(AgentLmpqHandOver::stackSize).sum();
    }

    /**
     * The open hand-over: walk to the leader's spot, drop at his feet or beside him, or drop here when he cannot be
     * reached in time. False when he left the room before the drop (the stack stays in the bag).
     */
    private static boolean walkOrDrop(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                                      AgentLmpqMemberState member, int held, Character leader, long nowMs) {
        if (!leaderHere(leader, agent)) return false;
        PrimitiveCapabilityGateway actions = AgentLmpqCoordinator.actions();
        boolean onGround = actions.footing(agent) == PrimitiveCapabilityGateway.Footing.GROUND;
        boolean leaderOnGround = actions.footing(leader) == PrimitiveCapabilityGateway.Footing.GROUND;
        Point spot = groundSpot(leader);
        boolean reachable = spot != null && (!onGround || actions.canReach(entry, agent, spot));
        Point landing = atFeet(agent.getPosition(), spot, onGround, leaderOnGround, REACH_PX)
                ? actions.predictDropLanding(agent, agent.getPosition()) : null;
        HandOver branch = handOver(agent.getPosition(), spot, onGround, leaderOnGround, landing, reachable,
                nowMs - member.handOverStartedAtMs(), TIMEOUT_MS, REACH_PX);
        if (branch == HandOver.WALK) {
            actions.navigate(entry, spot, true);
            return true;
        }
        drop(session, entry, agent, member, held, leader, spot, branch, nowMs);
        return true;
    }

    /** Drops the whole stack in one drop, says where it lies (by where the map lands it), and logs it. */
    private static void drop(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                             AgentLmpqMemberState member, int held, Character leader, Point spot, HandOver branch,
                             long nowMs) {
        PrimitiveCapabilityGateway actions = AgentLmpqCoordinator.actions();
        Point at = agent.getPosition() == null ? null : new Point(agent.getPosition());
        Point landing = actions.predictDropLanding(agent, at);
        actions.stop(entry);
        if (!AgentLmpqCoordinator.dropCoupons(entry, held)) return;
        Place place = place(branch, landing, spot, REACH_PX);
        int lineage = member.handOverRecollectCount();
        member.endHandOver();
        member.deferUntil(nowMs + AgentLmpqCoordinator.actionRetryMs());
        int stack = newestOwnStack(agent);
        if (stack != 0 && lineage > 0) session.setRecollectCount(stack, lineage);
        session.markProgress(nowMs);
        logHandOver(session, agent, held, place, lineage > 0, leader, at, landing);
        if (leader == null || !session.claimLineWindow("handover", nowMs, LINE_WINDOW_MS)) return;
        AgentLmpqDialogue.say(session, "handover-" + place.name().toLowerCase(java.util.Locale.ROOT), agent,
                line(leader.getName(), place, lineage > 0));
    }

    /** The hand-over's line: H1 where it lies, or H3 for a stack picked back up. */
    static String line(String leader, Place place, boolean recollect) {
        if (recollect) return AgentLmpqCompanionPlay.pickedBackUp();
        return place == Place.HERE ? AgentLmpqCompanionPlay.droppedHere(leader)
                : AgentLmpqCompanionPlay.atYourFeet(leader, place == Place.FEET);
    }

    /**
     * The nudge on a stack this companion dropped for the leader, and the re-collect claims of stacks lying here: a
     * stack goes back to its dropper while it stands here, else to the lowest-id companion standing here. The first
     * claimer wins. True when this companion claimed one and set out for it.
     */
    private static boolean nudgeAndClaim(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent,
                                         AgentLmpqMemberState member, int held, Character leader,
                                         List<MapItem> stacks, long nowMs) {
        for (MapItem stack : stacks) {
            if (stack.getOwnerId() == agent.getId() && leader != null
                    && nudgeDue(stack.getDropTime(), nowMs, PICKUP_NUDGE_MS)
                    && session.claimNote("handover-nudge-" + stack.getObjectId())
                    && session.claimLineWindow("handover-nudge", nowMs, PICKUP_NUDGE_MS)) {
                AgentLmpqDialogue.say(session, "handover-nudge", agent, AgentLmpqCompanionPlay.stillOnTheFloor(
                        leader.getName()));
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
    private static int claimer(AgentLmpqSession session, Character agent, MapItem stack) {
        Character dropper = AgentLmpqCoordinator.character(stack.getOwnerId());
        if (dropper != null && dropper.getHp() > 0 && dropper.getMapId() == agent.getMapId()
                && session.agentOfRun(dropper.getId()) && !session.departed(dropper.getId())) {
            return dropper.getId();
        }
        return session.members().stream()
                .filter(other -> other.memberType() == AgentLmpqMemberState.MemberType.AGENT)
                .filter(other -> !session.departed(other.characterId()))
                .map(other -> AgentLmpqCoordinator.character(other.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(other -> other.getHp() > 0 && other.getMapId() == agent.getMapId())
                .mapToInt(Character::getId).min().orElse(0);
    }

    /** Walks to the stack and picks it up once within reach; the loot rule lets only its claimer take it. */
    static void pickUp(AgentLmpqSession session, AgentRuntimeEntry entry, Character agent, MapItem stack, long nowMs) {
        PrimitiveCapabilityGateway actions = AgentLmpqCoordinator.actions();
        if (AgentLmpqCoordinator.withinLootRadius(agent.getPosition(), stack.getPosition())) {
            actions.stop(entry);
            if (actions.lootItem(agent, stack.getObjectId(), AgentLmpqCoordinator.lootRadius())) {
                session.markProgress(nowMs);
            }
            return;
        }
        Point ground = actions.groundPoint(agent.getMap(), stack.getPosition());
        actions.navigate(entry, ground == null ? stack.getPosition() : ground, true);
    }

    private static boolean leaderHere(Character leader, Character agent) {
        return leader != null && leader.getHp() > 0 && leader.getMapId() == agent.getMapId();
    }

    private static int stackSize(MapItem drop) {
        return drop.getItem() == null ? 1 : Math.max(1, drop.getItem().getQuantity());
    }

    /** The stack this companion has just dropped: its own newest coupon drop on its map; 0 when none. */
    private static int newestOwnStack(Character agent) {
        if (agent.getMap() == null) return 0;
        return AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.isPlayerDrop()
                        && drop.getItemId() == AgentLmpqDefinition.COUPON && drop.getOwnerId() == agent.getId())
                .max(Comparator.comparingInt(MapItem::getObjectId))
                .map(MapItem::getObjectId).orElse(0);
    }

    /** 'LMPQ handover:' for every hand-over drop. Nothing decides by it; never throws. */
    private static void logHandOver(AgentLmpqSession session, Character agent, int count, Place place,
                                    boolean recollect, Character leader, Point at, Point landing) {
        try {
            log.info("LMPQ handover: session={} member={} count={} place={} recollect={} leader={} at={} landing={}",
                    session.sessionId(), agent.getName(), count, place, recollect,
                    leader == null ? "none" : leader.getName(), xy(at), xy(landing));
        } catch (RuntimeException e) {
            log.warn("LMPQ handover line failed: session={}", session.sessionId(), e);
        }
    }

    /** 'LMPQ recollect:' once a companion has picked a stack back up. Nothing decides by it; never throws. */
    private static void logRecollect(AgentLmpqSession session, Character agent, int stack, int count) {
        try {
            log.info("LMPQ recollect: session={} member={} stack={} count={}", session.sessionId(), agent.getName(),
                    stack, count);
        } catch (RuntimeException e) {
            log.warn("LMPQ recollect line failed: session={}", session.sessionId(), e);
        }
    }

    // ---- pure rules -----------------------------------------------------------------------------------------------

    /** What the carrier does now: walk, drop at his feet or right beside him, or drop where it stands. */
    static HandOver handOver(Point carrier, Point leaderSpot, boolean carrierOnGround, boolean leaderOnGround,
                             Point landing, boolean leaderReachable, long walkingMs, long timeoutMs, int reachPx) {
        if (!leaderReachable || walkingMs > timeoutMs) return HandOver.DROP_HERE;
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

    /** Where the stack lies for its line: at his feet, right beside him (a fallback drop landing by him), or here. */
    static Place place(HandOver branch, Point landing, Point leaderSpot, int reachPx) {
        if (branch == HandOver.DROP_AT_FEET) return Place.FEET;
        if (branch == HandOver.DROP_BESIDE) return Place.BESIDE;
        return landsAt(landing, leaderSpot, reachPx) ? Place.BESIDE : Place.HERE;
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

    private static Point groundSpot(Character character) {
        Point position = character.getPosition();
        if (position == null) return null;
        Point grounded = AgentLmpqCoordinator.actions().groundPoint(character.getMap(), position);
        return grounded == null ? new Point(position) : grounded;
    }

    private static String xy(Point point) {
        return point == null ? "-" : "(" + point.x + "," + point.y + ")";
    }

    /** COUPON_RECOLLECT_MS, clamped (with one WARN) below the items' expiry margin on this server. */
    private static long recollectAfterMs(long configuredMs) {
        config.ServerConfig server = config.YamlConfig.config == null ? null : config.YamlConfig.config.server;
        long latest = server == null || server.ITEM_EXPIRE_TIME <= 0 ? RECOLLECT_LATEST_MS
                : Math.max(0L, server.ITEM_EXPIRE_TIME - Math.max(0L, server.ITEM_EXPIRE_CHECK));
        long clamped = recollectAfterMs(configuredMs, latest);
        if (clamped != configuredMs) {
            log.warn("LMPQ COUPON_RECOLLECT_MS {} would let a coupon stack expire before it is picked back up; using {}",
                    configuredMs, clamped);
        }
        return clamped;
    }
}
