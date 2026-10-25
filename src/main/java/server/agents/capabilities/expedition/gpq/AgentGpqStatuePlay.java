package server.agents.capabilities.expedition.gpq;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Reactor;

import java.awt.Point;
import java.util.List;

import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.bySlot;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.hit;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.noteRole;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.takePortal;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.waitAt;

/**
 * Stage 1, the Gatekeeper's statues (990000300, GPQ plan G3): the leader talks to the Gatekeeper (9040009, leader
 * only) and he plays a pattern on 4, then 5, then 6 of the 20 statues (each display hit adds the statue to the event's
 * stage1combo); once it is shown (stage1status "active") every hit on any statue adds to stage1guess, with no undo, and
 * the leader's next talk judges it. One companion, the first by slot here, repeats the pattern: it walks to each statue
 * in the order shown and hits it once, waiting for the hit to count before the next. Nobody touches a statue while the
 * pattern plays (a hit then can swallow a display hit and stall the statues for good). The others wait by the
 * Gatekeeper. Companions tell the leader when to talk: to start a pattern, once one is repeated, and after a miss
 * (which sends the test back to its first part). Once stage1clear is set, everyone takes next00 (statuegate_open).
 */
final class AgentGpqStatuePlay {
    static final int GATEKEEPER_MAP_ID = 990_000_300;
    /** The statue reactors: humanoid (bottom row), demon (middle), goblin (top); statuegate is 9208003. */
    static final List<Integer> STATUES = List.of(9_208_000, 9_208_001, 9_208_002);
    /** Where the others wait: on the floor between the statuegate and the Gatekeeper (645,163). */
    static final Point WAIT_SPOT = new Point(600, 165);
    /** How long the hitter waits for its hit to count (the statue's act appends it) before it hits that slot again. */
    static final long HIT_SETTLE_MS = 1_500L;
    static final String HITTER = "statues";

    private AgentGpqStatuePlay() {
    }

    static void gatekeeper(AgentGpqPorts ports, List<Character> members, EventInstanceManager event,
                           AgentGpqRun run, long nowMs) {
        if ("true".equals(event.getProperty("stage1clear"))) {
            run.release(HITTER);
            members.forEach(member -> takePortal(ports, run, member, "next00", nowMs));
            return;
        }
        List<Character> bySlot = bySlot(members, run);
        Character hitter = hitter(bySlot, run);
        String status = event.getProperty("stage1status");
        String phase = String.valueOf(event.getProperty("stage1phase"));
        int wrong = event.wrongEffectCount(GATEKEEPER_MAP_ID);
        String round = phase + "-" + wrong;
        if (wrong > run.mark("statues-wrong")) {
            run.mark("statues-wrong", wrong);
            ports.sayInParty(hitter, AgentGpqLines.GATEKEEPER_WRONG);
            run.once("statues-start-" + round); // the miss line already asks the leader to talk
        }
        String combo = String.valueOf(event.getProperty("stage1combo"));
        String guess = event.getProperty("stage1guess") == null ? "" : event.getProperty("stage1guess");
        for (Character member : bySlot) {
            if (member != hitter) {
                noteRole(run, member, "statues", "wait by the gatekeeper");
                waitAt(ports, member, WAIT_SPOT);
            }
        }
        if (status == null || "waiting".equals(status)) {
            if (run.once("statues-start-" + round)) {
                ports.sayInParty(hitter, AgentGpqLines.GATEKEEPER_START);
            }
            noteRole(run, hitter, "statues", "wait for the gatekeeper's pattern");
            waitAt(ports, hitter, WAIT_SPOT);
        } else if ("display".equals(status)) {
            noteRole(run, hitter, "statues", "watch the pattern");
            AgentRuntimeEntry entry = ports.entry(hitter.getId());
            if (entry != null) {
                ports.gateway().stop(entry); // touch no statue while it plays
            }
        } else if ("active".equals(status)) {
            repeat(ports, run, hitter, combo, guess, round, nowMs);
        }
    }

    /** The hitter repeats the pattern one statue at a time; with the pattern in, it tells the leader to talk. */
    static void repeat(AgentGpqPorts ports, AgentGpqRun run, Character hitter, String combo, String guess,
                       String round, long nowMs) {
        if (guess.length() >= combo.length()) {
            noteRole(run, hitter, "statues", "wait for the gatekeeper's verdict");
            if (run.once("statues-ready-" + round)) {
                ports.sayInParty(hitter, AgentGpqLines.GATEKEEPER_READY);
            }
            waitAt(ports, hitter, WAIT_SPOT);
            return;
        }
        int slot = guess.length() / 3;
        int objectLow = Integer.parseInt(combo.substring(slot * 3, slot * 3 + 3));
        Reactor statue = statue(ports, hitter, objectLow);
        if (statue == null) {
            return;
        }
        noteRole(run, hitter, "statues", "hit statue " + statue.getName() + " (" + (slot + 1) + " of "
                + combo.length() / 3 + ")");
        // one hit per slot: wait for the last one to count before hitting this slot again
        if (run.mark("statue-slot") == guess.length() + 1 && nowMs - run.mark("statue-hit-at") < HIT_SETTLE_MS) {
            return;
        }
        if (hit(ports, run, hitter, statue, nowMs)) {
            run.mark("statue-slot", guess.length() + 1);
            run.mark("statue-hit-at", nowMs);
        }
    }

    /** The statue on the hitter's map whose object id ends in {@code objectLow} (the pattern's own key), or null. */
    static Reactor statue(AgentGpqPorts ports, Character viewer, int objectLow) {
        for (Reactor reactor : ports.gateway().reactors(viewer)) {
            if (STATUES.contains(reactor.getId()) && reactor.getObjectId() % 1000 == objectLow) {
                return reactor;
            }
        }
        return null;
    }

    /** The member repeating the patterns: the one that took it on while it stands here, else the first by slot. */
    static Character hitter(List<Character> bySlot, AgentGpqRun run) {
        int holder = run.holder(HITTER);
        for (Character member : bySlot) {
            if (member.getId() == holder) {
                return member;
            }
        }
        run.release(HITTER);
        Character first = bySlot.getFirst();
        run.claim(HITTER, first.getId());
        return first;
    }
}
