package server.agents.capabilities.expedition.gpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.expedition.boss.AgentBossRunMember;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;
import server.agents.capabilities.expedition.gpq.AgentGpqRun.Stage;
import server.agents.capabilities.movement.AgentMoveTargetStateRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GM6 '!gpqtest' (GPQ plan G4, for the B6 live checks). A Sharenian companion run starts at Shuang (the boss track's
 * guild sign-up, so there is no adopt step). This harness reads it and moves it on:
 * <ul>
 * <li>{@code status}: the run's phase, every stage flag and gate state, and each companion's place, rock and job.</li>
 * <li>{@code why}: each companion's place, rock, speed, job and walk, also in the server log.</li>
 * <li>{@code checkpoint <gatekeeper|spears|vassals|waterway|corridor|throne>}: everyone alive in the event goes forward
 * to that stage inside the instance (never a !warp), the earlier stages' flags and gates set as their stock clears set
 * them. Only when everyone alive wears a Protector Rock: the castle's air kills a member without one.</li>
 * <li>{@code stop}: the event's own end, which sends everyone in it to the Returning Path.</li>
 * </ul>
 */
public final class AgentGpqTestService {
    private static final Logger log = LoggerFactory.getLogger(AgentGpqTestService.class);
    static final String EVENT_PREFIX = "Guild";
    /** The gates the stage clears open, by name and the map they stand on, in stage order. */
    private static final List<Gate> GATES = List.of(new Gate("statuegate", Stage.GATEKEEPER, 1),
            new Gate("speargate", Stage.HALL, 4), new Gate("watergate", Stage.FOUNTAIN, 1),
            new Gate("ghostgate", Stage.GRAVE, 1), new Gate("kinggate", Stage.CORRIDOR, 1));

    private record Gate(String name, Stage stage, int open) { }

    /** The stages a checkpoint can go forward to, each with the gates and flags opened before it. */
    enum Checkpoint {
        GATEKEEPER(Stage.GATEKEEPER, 0),
        SPEARS(Stage.HALL, 1),
        VASSALS(Stage.FOUNTAIN, 2),
        WATERWAY(Stage.WATERWAY, 3),
        CORRIDOR(Stage.CORRIDOR, 4),
        THRONE(Stage.THRONE, 5);

        final Stage stage;
        /** How many of GATES stand open on the way here. */
        final int gatesOpen;

        Checkpoint(Stage stage, int gatesOpen) {
            this.stage = stage;
            this.gatesOpen = gatesOpen;
        }

        static Checkpoint of(String name) {
            for (Checkpoint checkpoint : values()) {
                if (checkpoint.name().equalsIgnoreCase(name)) {
                    return checkpoint;
                }
            }
            return null;
        }
    }

    private AgentGpqTestService() {
    }

    public static List<String> execute(Character operator, String[] params, long nowMs) {
        if (operator == null || !AgentAuthorityService.mayOperate(operator)) {
            return List.of("You are not configured as an Agent operator.");
        }
        if (params == null || params.length == 0) {
            return help();
        }
        try {
            return switch (params[0].toLowerCase(Locale.ROOT)) {
                case "status" -> status(operator, nowMs);
                case "why" -> why(operator);
                case "checkpoint" -> checkpoint(operator, params);
                case "stop" -> stop(operator);
                default -> help();
            };
        } catch (RuntimeException failure) {
            log.warn("GPQ test command failed for operator {}", operator.getId(), failure);
            return List.of("GPQ test command failed: " + failure.getMessage());
        }
    }

    // ---- status ----

    private static List<String> status(Character operator, long nowMs) {
        EventInstanceManager event = gpqEvent(operator);
        if (event == null) {
            return List.of("No Sharenian run: register your guild at Shuang (101030104), then stand inside it.");
        }
        AgentBossRunSession session = AgentBossRunRegistry.forEvent(event);
        List<String> lines = new ArrayList<>();
        lines.add(session == null ? "GPQ " + event.getName() + ": no companion run in it."
                : "GPQ run " + session.sessionId() + ": " + session.phase().name().toLowerCase(Locale.ROOT) + ", "
                + session.members().size() + " companion(s).");
        lines.add(flags(event, nowMs));
        if (session != null) {
            AgentGpqRun run = session.fight() instanceof AgentGpqRun gpq ? gpq : null;
            for (AgentBossRunMember member : session.members()) {
                lines.add(memberLine(member, run));
            }
        }
        return lines;
    }

    /** Every stage flag and gate state GuildQuest.js and its NPCs keep, in one line (the fountain's answer hidden). */
    static String flags(EventInstanceManager event, long nowMs) {
        StringBuilder line = new StringBuilder(event.isEventCleared() ? "cleared:" : "flags:");
        long opensAt = AgentGpqStagePlays.longProperty(event, AgentGpqStagePlays.ENTRY_TIMESTAMP);
        line.append(" canJoin=").append(event.getProperty("canJoin")).append(" canRevive=")
                .append(event.getProperty("canRevive"));
        if (opensAt > nowMs) {
            line.append(" way in opens in ").append((opensAt - nowMs) / 1000).append(" s");
        }
        line.append("; statues ").append(event.getProperty("stage1status")).append(" part ")
                .append(event.getProperty("stage1phase")).append(" clear=").append(event.getProperty("stage1clear"))
                .append("; fountain ").append(event.getProperty(AgentGpqVassalPlays.COMBO) == null ? "not asked"
                        : "attempt " + event.getProperty("stage3attempt"))
                .append(" clear=").append(event.getProperty(AgentGpqVassalPlays.CLEAR))
                .append("; grave clear=").append(event.getProperty(AgentGpqWaterwayPlays.CLEAR)).append("; gates:");
        for (Gate gate : GATES) {
            MapleMap map = event.getMapInstance(gate.stage().mapId());
            Reactor reactor = map == null ? null : map.getReactorByName(gate.name());
            line.append(' ').append(gate.name()).append('=').append(reactor == null ? "-" : reactor.getState());
        }
        return line.toString();
    }

    private static String memberLine(AgentBossRunMember member, AgentGpqRun run) {
        Character agent = character(member.agentId());
        if (agent == null) {
            return "  " + member.agentId() + ": offline, " + member.phase().name().toLowerCase(Locale.ROOT);
        }
        return "  " + agent.getName() + " (" + agent.getJob().name().toLowerCase(Locale.ROOT) + " Lv" + agent.getLevel()
                + roles(agent) + "): " + member.phase().name().toLowerCase(Locale.ROOT) + " on " + agent.getMapId()
                + (agent.isAlive() ? "" : ", down") + (AgentGpqPorts.LIVE.wearsRock(agent) ? ", rock on" : ", no rock")
                + (member.deaths() > 0 ? ", " + member.deaths() + " death(s)" : "")
                + (run == null || run.roleOf(agent.getId()) == null ? "" : "; job: " + run.roleOf(agent.getId()));
    }

    /** The GPQ roles the member can fill (AgentGpqRoles), for the lineup check. */
    private static String roles(Character agent) {
        StringBuilder text = new StringBuilder();
        if (AgentGpqRoles.junior(agent)) {
            text.append(", junior");
        }
        if (AgentGpqRoles.shade(agent)) {
            text.append(", dark sight");
        }
        if (AgentGpqRoles.skilledClimber(agent)) {
            text.append(", climber");
        } else if (AgentGpqRoles.haste(agent) > 0) {
            text.append(", haste");
        }
        return text.toString();
    }

    // ---- why ----

    /** "why": each companion in the run you stand in, one line each in the chat and the server log. */
    private static List<String> why(Character operator) {
        EventInstanceManager event = gpqEvent(operator);
        AgentBossRunSession session = event == null ? null : AgentBossRunRegistry.forEvent(event);
        if (session == null) {
            return List.of("Stand inside a Sharenian run with companions in it.");
        }
        AgentGpqRun run = session.fight() instanceof AgentGpqRun gpq ? gpq : null;
        List<String> lines = new ArrayList<>();
        String flags = flags(event, System.currentTimeMillis());
        log.info("GPQ why: {}", flags);
        lines.add(flags);
        for (AgentBossRunMember member : session.members()) {
            Character agent = character(member.agentId());
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.agentId());
            if (agent == null || entry == null) {
                continue;
            }
            Point target = AgentMoveTargetStateRuntime.moveTarget(entry);
            String line = agent.getName() + " " + member.phase().name().toLowerCase(Locale.ROOT) + " on "
                    + agent.getMapId() + " (" + agent.getMap().countMonsters() + " monsters) at "
                    + AgentGpqMoves.pointText(agent.getPosition()) + (agent.isAlive() ? "" : " down")
                    + (AgentGpqPorts.LIVE.wearsRock(agent) ? ", rock on" : ", no rock") + ", speed "
                    + agent.getTotalMoveSpeedStat() + " jump " + agent.getTotalJumpStat()
                    + (AgentGpqRoles.inDarkSight(agent) ? " in dark sight" : "")
                    + "; job: " + (run == null ? "-" : String.valueOf(run.roleOf(agent.getId())))
                    + "; walking to " + (target == null ? "nothing" : AgentGpqMoves.pointText(target));
            log.info("GPQ why: {}", line);
            lines.add(line);
        }
        return lines;
    }

    // ---- checkpoint ----

    /**
     * "checkpoint <stage>": the run must be inside Sharenian with the way in open. The gates and flags of every stage
     * before it are set as their clears set them, then everyone alive in the event moves to the stage's first portal
     * inside the instance. The down stay where they fell.
     */
    private static List<String> checkpoint(Character operator, String[] params) {
        Checkpoint target = params.length < 2 ? null : Checkpoint.of(params[1]);
        if (target == null) {
            return List.of("Syntax: !gpqtest checkpoint <gatekeeper|spears|vassals|waterway|corridor|throne>");
        }
        EventInstanceManager event = gpqEvent(operator);
        if (event == null) {
            return List.of("Enter Sharenian through Shuang before a checkpoint.");
        }
        List<String> rockless = new ArrayList<>();
        for (Character player : event.getPlayers()) {
            if (player.isAlive() && !AgentGpqPorts.LIVE.wearsRock(player)) {
                rockless.add(player.getName());
            }
        }
        if (!rockless.isEmpty()) {
            return List.of("Not without rocks: " + String.join(", ", rockless) + " wear no Protector Rock and would die"
                    + " in the air. Get them rocks in the Valley of the Guard first.");
        }
        open(event, target);
        MapleMap map = event.getMapInstance(target.stage.mapId());
        Portal portal = map.getPortal(0);
        int moved = 0;
        int down = 0;
        for (Character player : new ArrayList<>(event.getPlayers())) {
            if (player.getMapId() == target.stage.mapId()) {
                continue;
            }
            if (!player.isAlive()) {
                down++;
                continue;
            }
            if (AgentPresence.isAgent(player)) {
                AgentMapGatewayRuntime.map().changeMapNear(player, map, portal.getPosition());
            } else {
                player.changeMap(map, portal);
            }
            moved++;
        }
        log.info("GPQ checkpoint: event={} stage={} moved={} down={} by={}", event.getName(),
                target.name().toLowerCase(Locale.ROOT), moved, down, operator.getId());
        return List.of("GPQ checkpoint: " + moved + " moved to the " + target.name().toLowerCase(Locale.ROOT)
                + (down > 0 ? ", " + down + " down stay where they fell" : "")
                + ". Earlier stages are skipped for this run only (no guild points for them).");
    }

    /** The flags and gates the stock clears before {@code target} would have left (none is closed again). */
    static void open(EventInstanceManager event, Checkpoint target) {
        event.setProperty(AgentGpqStagePlays.ENTRY_TIMESTAMP, "0");
        int order = target.ordinal();
        if (order >= Checkpoint.SPEARS.ordinal()) {
            event.setProperty("stage1clear", "true");
        }
        if (order >= Checkpoint.WATERWAY.ordinal()) {
            event.setProperty(AgentGpqVassalPlays.CLEAR, "true");
        }
        if (order >= Checkpoint.CORRIDOR.ordinal()) {
            event.setProperty(AgentGpqWaterwayPlays.CLEAR, "true");
        }
        if (order >= Checkpoint.THRONE.ordinal()) {
            event.setProperty("canRevive", "1");
        }
        for (Gate gate : GATES.subList(0, target.gatesOpen)) {
            MapleMap map = event.getMapInstance(gate.stage().mapId());
            Reactor reactor = map == null ? null : map.getReactorByName(gate.name());
            if (reactor != null && reactor.getState() < gate.open()) {
                reactor.forceHitReactor((byte) gate.open());
            }
        }
    }

    // ---- stop ----

    private static List<String> stop(Character operator) {
        EventInstanceManager event = gpqEvent(operator);
        if (event == null) {
            return List.of("Stand inside the Sharenian run to stop it.");
        }
        try {
            event.invokeScriptFunction("end", event);
        } catch (Exception failure) {
            throw new IllegalStateException("the event could not end", failure);
        }
        log.info("GPQ run stopped: event={} by={}", event.getName(), operator.getId());
        return List.of("GPQ stopped: everyone in it goes to the Returning Path.");
    }

    // ---- lookups ----

    /** The Guild Quest instance {@code operator} stands in, or null. */
    static EventInstanceManager gpqEvent(Character operator) {
        EventInstanceManager event = operator.getEventInstance();
        return event != null && event.getName() != null && event.getName().startsWith(EVENT_PREFIX) ? event : null;
    }

    private static Character character(int id) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(id);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }

    private static List<String> help() {
        return List.of(
                "!gpqtest status (a Sharenian run you stand in)",
                "!gpqtest why (inside a Sharenian run: each companion's place, rock, job and walk, also in the log)",
                "!gpqtest checkpoint <gatekeeper|spears|vassals|waterway|corridor|throne> (everyone wears a rock)",
                "!gpqtest stop (inside a Sharenian run: the event ends for everyone in it)");
    }
}
