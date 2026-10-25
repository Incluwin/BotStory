package server.agents.capabilities.partyquest.apq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;
import server.maps.MapObject;

import java.awt.Point;
import java.util.Set;

/**
 * Bringing an objective item to the leader (APQ plan A4, the shape of AgentPpqMedalHandOver): only he may hand Amos or
 * the Glimmer Man the Mirror's shard, the Cupid Code Pieces and the Geist Fang, so a companion holding them walks to
 * him and drops them at his feet, as a player would. A stack dropped for him is his alone
 * (AgentApqSessionRegistry.canLootExclusive); one he leaves lying is pointed out, and before it can expire its
 * dropper picks it back up and brings it again.
 */
final class AgentApqHandOver {
    private static final Logger log = LoggerFactory.getLogger(AgentApqHandOver.class);
    static final int REACH_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.apq.AgentApqHandOver.REACH_PX");
    static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqHandOver.TIMEOUT_MS");
    static final long PICKUP_NUDGE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqHandOver.PICKUP_NUDGE_MS");
    static final long RECOLLECT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqHandOver.RECOLLECT_MS");
    static final long LINE_WINDOW_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqHandOver.LINE_WINDOW_MS");

    private AgentApqHandOver() {
    }

    /**
     * One step of bringing {@code itemId} to the leader: walk to him, then drop every one held at his feet. True while
     * it works on it (the companion does nothing else this tick); false when it holds none, or the leader is not on
     * its map or is down (it keeps them and goes on with its stage).
     */
    static boolean tick(AgentApqSession session, AgentRuntimeEntry entry, Character agent, AgentApqMemberState member,
                        int itemId, Character leader, String line, long nowMs) {
        int held = Math.max(0, AgentApqCoordinator.actions().itemCount(agent, itemId));
        if (held <= 0) {
            if (member.handOverItemId() == itemId) member.endHandOver();
            return false;
        }
        if (leader == null || leader.getHp() <= 0 || leader.getMapId() != agent.getMapId()
                || leader.getPosition() == null) {
            return false;
        }
        if (member.handOverItemId() != itemId) member.startHandOver(itemId, nowMs, 0);
        Point at = leader.getPosition();
        if (!AgentApqCoordinator.within(agent.getPosition(), at, REACH_PX)) {
            if (nowMs - member.handOverStartedAtMs() >= TIMEOUT_MS) {
                // he keeps moving away: try again a little later, between other work
                member.endHandOver();
                member.deferUntil(nowMs + AgentApqCoordinator.RETRY_MS);
                return false;
            }
            Point ground = AgentApqCoordinator.actions().groundPoint(agent.getMap(), at);
            AgentApqCoordinator.actions().navigate(entry, ground == null ? at : ground, true);
            return true;
        }
        AgentApqCoordinator.actions().stop(entry);
        if (!AgentApqCoordinator.dropEtc(entry, itemId, (short) held)) return true;
        member.endHandOver();
        member.deferUntil(nowMs + AgentApqCoordinator.RETRY_MS);
        session.markProgress(nowMs);
        log.info("APQ hand-over: session={} member={} item={} count={}", session.sessionId(), agent.getName(),
                itemId, held);
        if (line != null && session.claimLineWindow("hand-" + itemId, nowMs, LINE_WINDOW_MS)) {
            AgentApqDialogue.say(session, "hand-" + itemId, agent, leader, line);
        }
        return true;
    }

    /**
     * A stack of {@code itemId} this companion dropped for the leader that he has left lying for RECOLLECT_MS: it
     * claims it back (only then may it take it) and picks it up, to bring it again. True while it works on one.
     */
    static boolean recollect(AgentApqSession session, AgentRuntimeEntry entry, Character agent, int itemId,
                             long nowMs) {
        if (agent.getMap() == null) return false;
        for (MapItem drop : AgentMapPerception.items(agent.getMap())) {
            if (drop == null || drop.isPickedUp() || drop.getItemId() != itemId || !drop.isPlayerDrop()
                    || !droppedBy(drop, agent) || nowMs - drop.getDropTime() < RECOLLECT_MS) {
                continue;
            }
            if (session.recollectorOf(drop.getObjectId()) == agent.getId()
                    || session.beginRecollect(drop.getObjectId(), agent.getId())) {
                log.info("APQ re-collect: session={} member={} item={}", session.sessionId(), agent.getName(), itemId);
                return AgentApqCoordinator.collectObjectiveDrop(session, entry, agent, Set.of(itemId), nowMs);
            }
        }
        return false;
    }

    /**
     * The nudge, once per window: a stack of these items has lain at the leader's feet for PICKUP_NUDGE_MS. Each nudge
     * of an item doubles the wait before the next (up to eight times), so a leader busy fighting is not nagged.
     */
    static void nudge(AgentApqSession session, Character speaker, Character leader, int itemId, String line,
                      long nowMs) {
        if (speaker == null || leader == null || leader.getMap() == null || line == null) return;
        long window = PICKUP_NUDGE_MS << Math.min(3, session.nudgesSaid(itemId));
        for (MapItem drop : AgentMapPerception.items(leader.getMap())) {
            if (drop != null && !drop.isPickedUp() && drop.getItemId() == itemId && drop.isPlayerDrop()
                    && nowMs - drop.getDropTime() >= PICKUP_NUDGE_MS
                    && session.claimLineWindow("nudge-" + itemId, nowMs, window)) {
                session.noteNudge(itemId);
                AgentApqDialogue.say(session, "nudge-" + itemId, speaker, leader, line);
                return;
            }
        }
    }

    private static boolean droppedBy(MapItem drop, Character agent) {
        MapObject dropper = drop.getDropper();
        return dropper instanceof Character character ? character.getId() == agent.getId()
                : drop.getOwnerId() == agent.getId();
    }
}
