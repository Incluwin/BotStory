package server.agents.capabilities.expedition.gpq;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.gpq.AgentGpqRun.Stage;
import server.agents.capabilities.expedition.gpq.AgentGpqRun.StagePlay;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.bySlot;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.hit;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.noteRole;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.pickUpNearest;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.takePortal;
import static server.agents.capabilities.expedition.gpq.AgentGpqMoves.waitByPortal;

/**
 * Every GPQ stage play (GPQ plan B5 G3), and the plays of the maps between stages:
 * <ul>
 * <li>The Excavation Site (990000000): the three minutes of strategy time; once they are up (the event's
 * entryTimestamp) everyone takes join00 (guildwaitingenter lets nobody through before).</li>
 * <li>The Valley of the Guard (990000100) and the Entrance to the Remains (990000200): every member without a
 * Protector Rock breaks a glowing stone of its own (5 hits), picks up the rock it drops and wears it; no rock can be
 * held twice (only=1), so each breaks its own. Wearing one, it walks on (next00). The Entrance's two stones come back
 * a second after they break: there a member revived after the King's Corridor gate gets its rock again.</li>
 * <li>The Road to the Fortress (990000301) and the Middle Path (990000401): walk on through next01.</li>
 * </ul>
 * The Gatekeeper's statues are AgentGpqStatuePlay, the spears AgentGpqSpearPlays, the vassals AgentGpqVassalPlays, the
 * waterway and the grave AgentGpqWaterwayPlays, the corridor and the throne AgentGpqThronePlays.
 */
final class AgentGpqStagePlays {
    /** The glowing stones that drop Protector Rocks (9202000 "guardian stone 1", 9202009 "guardian stone 2"). */
    static final int ROCK_STONE = 9_202_000;
    static final int ROCK_STONE_TWO = 9_202_009;
    /** How far a member looks for a rock lying on the ground before it breaks a stone of its own. */
    static final int ROCK_SEEK_PX = 260;
    static final String ENTRY_TIMESTAMP = "entryTimestamp";

    private AgentGpqStagePlays() {
    }

    /** Every stage play, over {@code ports}. */
    static Map<Stage, StagePlay> all(AgentGpqPorts ports) {
        Map<Stage, StagePlay> plays = new EnumMap<>(Stage.class);
        plays.put(Stage.EXCAVATION, (members, event, run, nowMs) -> excavation(ports, members, event, run, nowMs));
        plays.put(Stage.VALLEY, (members, event, run, nowMs) -> rocks(ports, members, run, "next00", true, nowMs));
        plays.put(Stage.ENTRANCE, (members, event, run, nowMs) -> rocks(ports, members, run, "next00", false, nowMs));
        plays.put(Stage.GATEKEEPER, (members, event, run, nowMs) ->
                AgentGpqStatuePlay.gatekeeper(ports, members, event, run, nowMs));
        plays.put(Stage.ROAD, (members, event, run, nowMs) -> walkOn(ports, members, run, "next01", nowMs));
        plays.put(Stage.MIDDLE_PATH, (members, event, run, nowMs) -> walkOn(ports, members, run, "next01", nowMs));
        for (Stage stage : Stage.values()) {
            if (stage.spearStage()) {
                plays.put(stage, (members, event, run, nowMs) ->
                        AgentGpqSpearPlays.tick(ports, stage, members, event, run, nowMs));
            } else if (stage.waterwayStage() || stage == Stage.GRAVE) {
                plays.put(stage, (members, event, run, nowMs) ->
                        AgentGpqWaterwayPlays.tick(ports, stage, members, event, run, nowMs));
            }
        }
        for (Stage stage : List.of(Stage.FOUNTAIN, Stage.BANQUET, Stage.CELLAR)) {
            plays.put(stage, (members, event, run, nowMs) ->
                    AgentGpqVassalPlays.tick(ports, stage, members, event, run, nowMs));
        }
        plays.put(Stage.CORRIDOR, (members, event, run, nowMs) ->
                AgentGpqThronePlays.corridor(ports, members, event, run, nowMs));
        plays.put(Stage.THRONE, (members, event, run, nowMs) ->
                AgentGpqThronePlays.throne(ports, members, event, run, nowMs));
        return plays;
    }

    // ---- the Excavation Site ----

    static void excavation(AgentGpqPorts ports, List<Character> members, EventInstanceManager event, AgentGpqRun run,
                           long nowMs) {
        List<Character> bySlot = bySlot(members, run);
        if (run.once("strategy")) {
            ports.sayInParty(bySlot.getFirst(), AgentGpqLines.STRATEGY);
        }
        long opensAt = longProperty(event, ENTRY_TIMESTAMP);
        for (Character member : bySlot) {
            if (opensAt > 0 && nowMs >= opensAt) {
                noteRole(run, member, "excavation", "go in");
                takePortal(ports, run, member, "join00", nowMs);
            } else {
                noteRole(run, member, "excavation", "wait out the strategy time by join00");
                waitByPortal(ports, member, "join00");
            }
        }
    }

    // ---- the Valley of the Guard and the Entrance to the Remains ----

    /**
     * Each member wears a rock before it goes on: it wears one it carries, picks up one lying near, or breaks a stone
     * of its own (a stone another member works on is left to it); with a rock on, it takes {@code exitPortal}.
     */
    static void rocks(AgentGpqPorts ports, List<Character> members, AgentGpqRun run, String exitPortal,
                      boolean valley, long nowMs) {
        List<Character> bySlot = bySlot(members, run);
        if (valley && run.once("rocks")) {
            ports.sayInParty(bySlot.getFirst(), AgentGpqLines.ROCKS);
        }
        for (Character member : bySlot) {
            int id = member.getId();
            if (ports.wearsRock(member)) {
                run.releaseAll(id, "rock:");
                noteRole(run, member, "rocks", "walk on wearing a rock");
                takePortal(ports, run, member, exitPortal, nowMs);
                continue;
            }
            if (!valley && run.once("refill-" + id)) {
                ports.sayInParty(member, AgentGpqLines.REFILL);
            }
            if (ports.carried(member, AgentGpqPorts.PROTECTOR_ROCK) > 0) {
                if (run.ready(id, nowMs)) {
                    noteRole(run, member, "rocks", "wear its rock");
                    ports.wearRock(member);
                    run.busy(id, nowMs + AgentGpqMoves.ITEM_PAUSE_MS);
                }
                continue;
            }
            if (pickUpNearest(ports, run, member, AgentGpqPorts.PROTECTOR_ROCK, ROCK_SEEK_PX, nowMs)) {
                noteRole(run, member, "rocks", "pick up a rock");
                continue;
            }
            Reactor stone = stoneFor(ports, run, member);
            if (stone == null) {
                noteRole(run, member, "rocks", "wait for a stone to come back");
                continue;
            }
            noteRole(run, member, "rocks", "break the stone at " + AgentGpqMoves.pointText(stone.getPosition()));
            hit(ports, run, member, stone, nowMs);
        }
    }

    /**
     * The stone {@code member} breaks: the one it holds while it still stands, else the nearest standing stone no other
     * member holds, which it claims; null while none is free.
     */
    static Reactor stoneFor(AgentGpqPorts ports, AgentGpqRun run, Character member) {
        List<Reactor> stones = new ArrayList<>(AgentGpqMoves.live(ports, member, ROCK_STONE));
        stones.addAll(AgentGpqMoves.live(ports, member, ROCK_STONE_TWO));
        for (String held : run.claimsOf(member.getId(), "rock:")) {
            int oid = Integer.parseInt(held.substring("rock:".length()));
            Reactor stone = stones.stream().filter(candidate -> candidate.getObjectId() == oid).findFirst()
                    .orElse(null);
            if (stone != null) {
                return stone;
            }
            run.release(held); // broken: its rock lies by it, or another member took it
        }
        Point at = member.getPosition();
        Reactor nearest = null;
        double best = Double.MAX_VALUE;
        for (Reactor stone : stones) {
            if (run.holder("rock:" + stone.getObjectId()) != 0 || at == null) {
                continue;
            }
            double distance = stone.getPosition().distanceSq(at);
            if (distance < best) {
                best = distance;
                nearest = stone;
            }
        }
        if (nearest != null && !run.claim("rock:" + nearest.getObjectId(), member.getId())) {
            return null;
        }
        return nearest;
    }

    // ---- the roads between stages ----

    static void walkOn(AgentGpqPorts ports, List<Character> members, AgentGpqRun run, String portal, long nowMs) {
        for (Character member : bySlot(members, run)) {
            takePortal(ports, run, member, portal, nowMs);
        }
    }

    /** An event property read as a number; 0 when it is unset or not one. */
    static long longProperty(EventInstanceManager event, String name) {
        String value = event == null ? null : event.getProperty(name);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return (long) Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }
}
