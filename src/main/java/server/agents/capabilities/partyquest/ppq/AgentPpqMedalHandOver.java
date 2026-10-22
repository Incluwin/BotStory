package server.agents.capabilities.partyquest.ppq;

import client.Character;
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
 * A companion run's medal hand-overs on 925100100 (the EPQ marble pattern): only the event leader may hand Guon the
 * twenty marks of a wave, so the companions gather the current wave's marks and bring them to him. A carrier drops its
 * whole stack where the map lands it at his feet, or right next to him; a leader it cannot reach, or a walk past
 * TIMEOUT_MS, gets the stack by Guon. A stack he leaves lying is pointed out once and, before it can expire, picked
 * back up and dropped for him again, at most RECOLLECT_MAX times. A carrier sets out with BATCH marks while he is near,
 * and every carrier sets out once the party's marks (his bag, theirs and the stacks lying for him) make twenty, when
 * the companions also stop gathering. Companion runs only; it reads its own member state, the session and the
 * characters.
 */
final class AgentPpqMedalHandOver {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqMedalHandOver.class);
    static final int BATCH = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqMedalHandOver.BATCH");
    static final int NEAR_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqMedalHandOver.NEAR_PX");
    static final int REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqMedalHandOver.REACH_PX");
    static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqMedalHandOver.TIMEOUT_MS");
    static final long PICKUP_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqMedalHandOver.PICKUP_NUDGE_MS");
    static final long RECOLLECT_MS = recollectAfterMs(config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqMedalHandOver.RECOLLECT_MS"));
    static final int RECOLLECT_MAX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqMedalHandOver.RECOLLECT_MAX");
    static final long LINE_WINDOW_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqMedalHandOver.LINE_WINDOW_MS");
    /** The shipped item expiry (config.yaml ITEM_EXPIRE_TIME 180000) less one ITEM_EXPIRE_CHECK sweep (10000). */
    static final long RECOLLECT_LATEST_MS = 170_000L;

    /** What a carrier does with its stack this pass. */
    enum HandOver { WALK, DROP_AT_FEET, DROP_BESIDE, TO_NPC }

    /** Where a dropped stack lies, for its line. */
    enum Place { FEET, BESIDE, NPC }

    private AgentPpqMedalHandOver() {
    }

    /**
     * One pass for one companion on the medal map: a re-collect it claimed, its open hand-over, a hand-over that is due
     * now, and the nudges and re-collect claims of stacks lying here, in that order. True when it owns the pass.
     */
    static boolean tick(AgentPpqSession session, AgentRuntimeEntry entry, Character agent, AgentPpqMemberState member,
                        int itemId, Character leader, long nowMs) {
        int held = Math.max(0, actions().itemCount(agent, itemId));
        if (member.handOverItemId() != 0 && member.handOverItemId() != itemId) member.endHandOver();
        List<MapItem> stacks = lyingStacks(session, agent, itemId);
        int claimed = member.recollectObjectId();
        if (claimed != 0) {
            MapItem stack = stacks.stream().filter(drop -> drop.getObjectId() == claimed).findFirst().orElse(null);
            if (stack != null) {
                pickUp(session, entry, agent, stack, nowMs);
                return true;
            }
            int lineage = session.endRecollect(claimed);
            member.clearRecollect();
            if (held > member.heldBefore()) {
                member.startHandOver(itemId, nowMs, lineage + 1);
                logRecollect(session, agent, itemId, claimed, lineage + 1);
            }
        }
        if (member.handOverStartedAtMs() > 0L) {
            if (held > 0) {
                walkOrDrop(session, entry, agent, member, itemId, held, leader, nowMs);
                return true;
            }
            member.endHandOver();
        }
        // Nothing is handed to a leader lying dead: the companions keep fighting and carry on once he is up.
        if (held > 0 && leader != null && leader.getHp() > 0 && due(session, agent, itemId, held, leader)) {
            member.startHandOver(itemId, nowMs, 0);
            walkOrDrop(session, entry, agent, member, itemId, held, leader, nowMs);
            return true;
        }
        return nudgeAndClaim(session, entry, agent, member, itemId, held, leader, stacks, nowMs);
    }

    /** Whether the companions still gather this wave's marks: the party's are short of twenty. */
    static boolean gatherWanted(AgentPpqSession session, Character observer, Character leader, int itemId) {
        return partyTotal(session, observer, leader, itemId) < AgentPpqDefinition.MEDALS_PER_WAVE;
    }

    private static boolean due(AgentPpqSession session, Character agent, int itemId, int held, Character leader) {
        boolean leaderNear = leader != null && leader.getMapId() == agent.getMapId()
                && near(agent.getPosition(), leader.getPosition(), NEAR_PX);
        return handOverDue(held, partyTotal(session, agent, leader, itemId), leaderNear, BATCH,
                AgentPpqDefinition.MEDALS_PER_WAVE);
    }

    /** The open hand-over: walk to the leader's spot, drop at his feet or beside him, or take it to Guon. */
    private static void walkOrDrop(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                                   AgentPpqMemberState member, int itemId, int held, Character leader, long nowMs) {
        boolean onGround = actions().footing(agent) == PrimitiveCapabilityGateway.Footing.GROUND;
        Point spot = leader == null ? null : groundSpot(leader);
        if (!member.handOverToNpc()) {
            boolean leaderThere = leader != null && leader.getHp() > 0 && leader.getMapId() == agent.getMapId();
            boolean leaderOnGround = leaderThere
                    && actions().footing(leader) == PrimitiveCapabilityGateway.Footing.GROUND;
            boolean reachable = leaderThere && spot != null && (!onGround || actions().canReach(entry, agent, spot));
            Point landing = atFeet(agent.getPosition(), spot, onGround, leaderOnGround, REACH_PX)
                    ? actions().predictDropLanding(agent, agent.getPosition()) : null;
            HandOver branch = handOver(agent.getPosition(), spot, onGround, leaderOnGround, landing, reachable,
                    nowMs - member.handOverStartedAtMs(), TIMEOUT_MS, REACH_PX);
            switch (branch) {
                case WALK -> {
                    actions().navigate(entry, spot, true);
                    return;
                }
                case DROP_AT_FEET, DROP_BESIDE -> {
                    drop(session, entry, agent, member, itemId, held, leader, spot, branch, nowMs);
                    return;
                }
                default -> member.sendHandOverToNpc();
            }
        }
        Point npc = actions().npcPosition(agent, AgentPpqDefinition.GUIDE_NPC);
        Point npcGround = npc == null ? null : actions().groundPoint(agent.getMap(), npc);
        Point target = npcGround == null ? npc : npcGround;
        if (target == null || atFeet(agent.getPosition(), target, onGround, true, REACH_PX)) {
            drop(session, entry, agent, member, itemId, held, leader, spot, HandOver.TO_NPC, nowMs);
            return;
        }
        actions().navigate(entry, target, true);
    }

    /** Drops the whole stack in one drop, says where it lies (by where the map lands it), and logs it. */
    private static void drop(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                             AgentPpqMemberState member, int itemId, int held, Character leader, Point spot,
                             HandOver branch, long nowMs) {
        Point at = agent.getPosition() == null ? null : new Point(agent.getPosition());
        Point landing = actions().predictDropLanding(agent, at);
        actions().stop(entry);
        int lyingBefore = lyingTotal(session, agent, itemId);
        int leaderHeld = leader == null ? 0 : Math.max(0, actions().itemCount(leader, itemId));
        if (!AgentPpqCoordinator.dropEtc(entry, itemId, (short) held)) return;
        Place place = place(branch, landing, spot, REACH_PX);
        int lineage = member.handOverRecollectCount();
        member.endHandOver();
        int stack = newestOwnStack(agent, itemId);
        if (stack != 0 && lineage > 0) session.setRecollectCount(stack, lineage);
        session.markProgress(nowMs);
        logHandOver(session, agent, itemId, held, place, lineage > 0, leader, at, landing);
        if (leader == null) return;
        String name = leader.getName();
        if (lineage > 0) {
            if (session.claimLineWindow("medal-feet-" + itemId, nowMs, LINE_WINDOW_MS)) {
                AgentPpqDialogue.say(session, "M4", agent, leader, AgentPpqCompanionPlay.medalsPickedBackUp());
            }
            return;
        }
        if (place != Place.NPC && makesTwenty(held, leaderHeld, lyingBefore, AgentPpqDefinition.MEDALS_PER_WAVE)
                && AgentPpqDialogue.narrate(session, twentyKey(itemId), agent, leader,
                AgentPpqCompanionPlay.twentyWithThese(name))) {
            session.claimLineWindow("medal-feet-" + itemId, nowMs, LINE_WINDOW_MS);
            return;
        }
        String kind = place == Place.NPC ? "medal-npc-" : "medal-feet-";
        if (!session.claimLineWindow(kind + itemId, nowMs, LINE_WINDOW_MS)) return;
        AgentPpqDialogue.say(session, place == Place.NPC ? "M2b" : "M2", agent, leader, place == Place.NPC
                ? AgentPpqCompanionPlay.medalsByGuon(name) : AgentPpqCompanionPlay.medalsAtFeet(name, place == Place.FEET));
    }

    /** The once-a-wave key of the twenty line, shared by the hand-over that makes twenty and M5 (he holds twenty). */
    static String twentyKey(int itemId) {
        return "medal-twenty-" + itemId;
    }

    /**
     * The nudge on a stack this companion dropped for the leader, and the re-collect claims of stacks lying here: a
     * stack goes back to its dropper while it stands here, else to the lowest-id companion standing here. The first
     * claimer wins. True when this companion claimed one and set out for it.
     */
    private static boolean nudgeAndClaim(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                                         AgentPpqMemberState member, int itemId, int held, Character leader,
                                         List<MapItem> stacks, long nowMs) {
        for (MapItem stack : stacks) {
            if (stack.getOwnerId() == agent.getId() && leader != null
                    && nudgeDue(stack.getDropTime(), nowMs, PICKUP_NUDGE_MS)) {
                nudge(session, agent, itemId, stack, leader, nowMs);
            }
        }
        for (MapItem stack : stacks.stream().sorted(Comparator.comparingLong(MapItem::getDropTime)).toList()) {
            int objectId = stack.getObjectId();
            if (session.recollectorOf(objectId) != 0 || !recollectDue(stack.getDropTime(), nowMs, RECOLLECT_MS,
                    session.recollectCount(objectId), RECOLLECT_MAX)) {
                continue;
            }
            if (claimer(session, agent, stack) != agent.getId()) continue;
            if (!session.beginRecollect(objectId, agent.getId())) continue;
            member.claimRecollect(objectId, held);
            pickUp(session, entry, agent, stack, nowMs);
            return true;
        }
        return false;
    }

    /** One nudge for this stack, and one for stacks lying together: a nudge said within the window speaks for them. */
    private static void nudge(AgentPpqSession session, Character agent, int itemId, MapItem stack, Character leader,
                              long nowMs) {
        String key = "medal-nudge-" + stack.getObjectId();
        if (!session.narrateOnce(key)) return;
        if (!session.claimLineWindow("medal-nudge-" + itemId, nowMs, PICKUP_NUDGE_MS)) return;
        AgentPpqDialogue.say(session, "M3", agent, leader, AgentPpqCompanionPlay.medalsStillLying(leader.getName()));
    }

    /** Who picks this stack back up: its dropper while it stands here, else the lowest-id companion standing here. */
    private static int claimer(AgentPpqSession session, Character agent, MapItem stack) {
        Character dropper = AgentPpqCoordinator.character(stack.getOwnerId());
        if (dropper != null && dropper.getHp() > 0 && dropper.getMapId() == agent.getMapId()
                && session.agentOfRun(dropper.getId())) {
            return dropper.getId();
        }
        return session.members().stream()
                .filter(other -> other.memberType() == AgentPpqMemberState.MemberType.AGENT)
                .map(other -> AgentPpqCoordinator.character(other.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(other -> other.getHp() > 0 && other.getMapId() == agent.getMapId())
                .mapToInt(Character::getId).min().orElse(0);
    }

    /** Walks to the stack and picks it up once within reach; the loot rule lets only this companion take it back. */
    private static void pickUp(AgentPpqSession session, AgentRuntimeEntry entry, Character agent, MapItem stack,
                               long nowMs) {
        if (AgentPpqCoordinator.near(agent.getPosition(), stack.getPosition())) {
            actions().stop(entry);
            agent.pickupItem(stack);
            session.markProgress(nowMs);
            return;
        }
        Point ground = actions().groundPoint(agent.getMap(), stack.getPosition());
        actions().navigate(entry, ground == null ? stack.getPosition() : ground, true);
    }

    /**
     * The party's marks of this wave: the leader's bag, every Agent's bag, and the stacks the companions dropped for him
     * still lying on the observer's map.
     */
    static int partyTotal(AgentPpqSession session, Character observer, Character leader, int itemId) {
        int total = leader == null ? 0 : Math.max(0, actions().itemCount(leader, itemId));
        for (AgentPpqMemberState member : session.members()) {
            if (member.memberType() != AgentPpqMemberState.MemberType.AGENT) continue;
            if (leader != null && member.characterId() == leader.getId()) continue;
            Character character = AgentPpqCoordinator.character(member.characterId());
            if (character != null) total += Math.max(0, actions().itemCount(character, itemId));
        }
        return total + lyingTotal(session, observer, itemId);
    }

    /** The stacks of this mark the run's Agents dropped on the observer's map, still lying. */
    static List<MapItem> lyingStacks(AgentPpqSession session, Character observer, int itemId) {
        if (observer == null || observer.getMap() == null) return List.of();
        return AgentMapPerception.items(observer.getMap()).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.isPlayerDrop()
                        && drop.getItemId() == itemId && drop.getPosition() != null)
                .filter(drop -> session.agentOfRun(drop.getOwnerId()))
                .toList();
    }

    private static int lyingTotal(AgentPpqSession session, Character observer, int itemId) {
        return lyingStacks(session, observer, itemId).stream().mapToInt(AgentPpqMedalHandOver::stackSize).sum();
    }

    private static int stackSize(MapItem drop) {
        return drop.getItem() == null ? 1 : Math.max(1, drop.getItem().getQuantity());
    }

    /** The stack this companion has just dropped: its own newest player drop of the mark on its map; 0 when none. */
    private static int newestOwnStack(Character agent, int itemId) {
        if (agent.getMap() == null) return 0;
        return AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> drop != null && !drop.isPickedUp() && drop.isPlayerDrop()
                        && drop.getItemId() == itemId && drop.getOwnerId() == agent.getId())
                .max(Comparator.comparingInt(MapItem::getObjectId))
                .map(MapItem::getObjectId).orElse(0);
    }

    /** 'PPQ handover:' for every medal hand-over drop. Nothing decides by it; never throws. */
    private static void logHandOver(AgentPpqSession session, Character agent, int itemId, int count, Place place,
                                    boolean recollect, Character leader, Point at, Point landing) {
        try {
            log.info("PPQ handover: session={} item={} member={} count={} place={} recollect={} leader={} at={} "
                            + "landing={}", session.sessionId(), itemId, agent.getName(), count, place, recollect,
                    leader == null ? "none" : leader.getName(), xy(at), xy(landing));
        } catch (RuntimeException e) {
            log.warn("PPQ handover line failed: session={}", session.sessionId(), e);
        }
    }

    /** 'PPQ recollect:' once a companion has picked a stack back up. Nothing decides by it; never throws. */
    private static void logRecollect(AgentPpqSession session, Character agent, int itemId, int stack, int count) {
        try {
            log.info("PPQ recollect: session={} item={} member={} stack={} count={}", session.sessionId(), itemId,
                    agent.getName(), stack, count);
        } catch (RuntimeException e) {
            log.warn("PPQ recollect line failed: session={}", session.sessionId(), e);
        }
    }

    // ---- pure rules -----------------------------------------------------------------------------------------------

    /**
     * A carrier sets out with {@code batch} marks while the leader is near, and every carrier sets out once the party's
     * marks make the wave's twenty.
     */
    static boolean handOverDue(int held, int partyTotal, boolean leaderNear, int batch, int required) {
        return held > 0 && ((held >= batch && leaderNear) || partyTotal >= required);
    }

    /** What the carrier does now (EPQ's handOver): walk, drop at his feet or right beside him, or go to Guon. */
    static HandOver handOver(Point carrier, Point leaderSpot, boolean carrierOnGround, boolean leaderOnGround,
                             Point landing, boolean leaderReachable, long walkingMs, long timeoutMs, int reachPx) {
        if (!leaderReachable || walkingMs > timeoutMs) return HandOver.TO_NPC;
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

    /** Where the stack lies for its line: at his feet, right beside him (also a Guon drop landing by him), or by Guon. */
    static Place place(HandOver branch, Point landing, Point leaderSpot, int reachPx) {
        if (branch == HandOver.DROP_AT_FEET) return Place.FEET;
        if (branch == HandOver.DROP_BESIDE) return Place.BESIDE;
        return landsAt(landing, leaderSpot, reachPx) ? Place.BESIDE : Place.NPC;
    }

    /** This hand-over makes the twenty: the stack dropped, what he holds and the companions' stacks lying for him. */
    static boolean makesTwenty(int dropped, int leaderHeld, int lying, int required) {
        return dropped + Math.max(0, leaderHeld) + Math.max(0, lying) >= required;
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

    /** The mark's name in the lines. */
    static String markName(int itemId) {
        return switch (itemId) {
            case AgentPpqDefinition.ROOKIE_MEDAL -> "Rookie Pirate";
            case AgentPpqDefinition.RISING_MEDAL -> "Rising Pirate";
            case AgentPpqDefinition.VETERAN_MEDAL -> "Veteran Pirate";
            default -> "pirate";
        };
    }

    /**
     * The wave's opening guide line to a human leader of a stock (not companion) run, as the coordinator's guide says
     * it; a companion run's companions say AgentPpqCompanionPlay's lines instead.
     */
    static String waveLine(int itemId, String leader) {
        return leader + ", we'll gather the " + markName(itemId) + " marks for you. Talk to Guon at twenty.";
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static boolean near(Point first, Point second, int px) {
        return first != null && second != null && first.distanceSq(second) <= (long) px * px;
    }

    private static Point groundSpot(Character character) {
        Point position = character.getPosition();
        if (position == null) return null;
        Point grounded = actions().groundPoint(character.getMap(), position);
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
            log.warn("PPQ RECOLLECT_MS {} would let a medal stack expire before it is picked back up; using {}",
                    configuredMs, clamped);
        }
        return clamped;
    }

    private static PrimitiveCapabilityGateway actions() {
        return AgentPpqCoordinator.actions();
    }
}
