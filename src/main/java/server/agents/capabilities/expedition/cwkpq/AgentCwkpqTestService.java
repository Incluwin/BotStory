package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.expedition.boss.AgentBossRunMember;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;
import server.agents.capabilities.expedition.cwkpq.AgentCwkpqSigils.Archetype;
import server.agents.capabilities.movement.AgentMoveTargetStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationDebugStateRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.integration.AgentPresence;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GM6 '!cwkpqtest' (CWKPQ plan C4, for the C9 live checks). A CWKPQ companion run starts at the real desk, Jack in
 * the Hall to Inner Sanctum (the run is the boss track's, so there is no adopt step). This harness reads it and moves
 * it on:
 * <ul>
 * <li>{@code status}: the run's phase, the stage and every stage flag, each companion's place, and the stage-4 spots
 * as the map holds them.</li>
 * <li>{@code checkpoint <storage|agility|wit|unity|council|armory>}: everyone alive in the run goes forward to that
 * stage, the earlier stages' flags set as their stock clears set them. The stage counter is set to the one before, so
 * the move's own changedMap starts that stage's timer as the stock run would. The armory is after the clear: the
 * event's own clearPQ runs first.</li>
 * <li>{@code stop}: the event's own end, which sends everyone in it to the Hall to Inner Sanctum.</li>
 * </ul>
 */
public final class AgentCwkpqTestService {
    private static final Logger log = LoggerFactory.getLogger(AgentCwkpqTestService.class);
    static final String EVENT_PREFIX = "CWKPQ";

    /** The stages a checkpoint can go forward to, each with the stage counter CWKPQ.js keeps while it is played. */
    enum Checkpoint {
        STORAGE(610_030_200, 1),
        AGILITY(610_030_300, 2),
        WIT(610_030_400, 3),
        UNITY(610_030_500, 4),
        COUNCIL(610_030_600, 5),
        ARMORY(610_030_800, 6);

        final int mapId;
        final int instance;

        Checkpoint(int mapId, int instance) {
            this.mapId = mapId;
            this.instance = instance;
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

    private AgentCwkpqTestService() {
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
                case "status" -> status(operator);
                case "why" -> why(operator);
                case "checkpoint" -> checkpoint(operator, params);
                case "stop" -> stop(operator);
                default -> help();
            };
        } catch (RuntimeException failure) {
            log.warn("CWKPQ test command failed for operator {}", operator.getId(), failure);
            return List.of("CWKPQ test command failed: " + failure.getMessage());
        }
    }

    // ---- status ----

    private static List<String> status(Character operator) {
        EventInstanceManager event = cwkpqEvent(operator);
        AgentBossRunSession session = event != null ? AgentBossRunRegistry.forEvent(event) : registering(operator);
        List<String> lines = new ArrayList<>();
        if (event == null && session == null) {
            return List.of("No CWKPQ run: sign up at Jack in the Hall to Inner Sanctum (610030020), or stand inside one.");
        }
        if (session == null) {
            lines.add("CWKPQ " + event.getName() + ": no companion run in it.");
        } else {
            lines.add("CWKPQ run " + session.sessionId() + ": " + session.phase().name().toLowerCase(Locale.ROOT)
                    + ", " + session.members().size() + " companion(s).");
        }
        if (event != null) {
            lines.add(flags(event));
        }
        if (session != null) {
            for (AgentBossRunMember member : session.members()) {
                lines.add(memberLine(member));
            }
        }
        if (event != null) {
            lines.addAll(witSpots(event));
        }
        return lines;
    }

    /** The stage counter and every stage flag CWKPQ.js keeps, in one line. */
    static String flags(EventInstanceManager event) {
        StringBuilder line = new StringBuilder("stage ").append(event.getIntProperty("current_instance") + 1)
                .append(event.isEventCleared() ? " (cleared)" : "").append(":");
        for (String flag : List.of("glpq1", "glpq2", "glpq3", "glpq3_p", "glpq4", "glpq5", "glpq6")) {
            line.append(' ').append(flag).append('=').append(event.getIntProperty(flag));
        }
        return line.toString();
    }

    private static String memberLine(AgentBossRunMember member) {
        Character agent = character(member.agentId());
        if (agent == null) {
            return "  " + member.agentId() + ": offline, " + member.phase().name().toLowerCase(Locale.ROOT);
        }
        Archetype turns = AgentCwkpqSigils.turns(agent);
        return "  " + agent.getName() + " (" + agent.getJob().name().toLowerCase(Locale.ROOT) + " Lv"
                + agent.getLevel() + (turns == null ? ", no sigil skill" : ", turns the " + turns.name()
                .toLowerCase(Locale.ROOT) + " sigil") + "): " + member.phase().name().toLowerCase(Locale.ROOT)
                + " on " + agent.getMapId() + (agent.isAlive() ? "" : ", down") + (member.deaths() > 0
                ? ", " + member.deaths() + " death(s)" : "");
    }

    /** The stage-4 spots as the instance holds them: each live spot's reactor, what it is, and its state. */
    private static List<String> witSpots(EventInstanceManager event) {
        MapleMap wit = event.getMapInstance(AgentCwkpqRun.Stage.WIT.mapId());
        if (wit == null || wit.getAllPlayers().isEmpty() && event.getIntProperty("current_instance") != 3) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Reactor reactor : wit.getAllReactors()) {
            if (!reactor.isAlive()) {
                continue;
            }
            int id = reactor.getId();
            Archetype picture = AgentCwkpqStagePlays.picture(id);
            if (picture == null) {
                continue;
            }
            boolean fake = AgentCwkpqStagePlays.FAKE_PICTURES.containsKey(id);
            lines.add("  spot (" + reactor.getPosition().x + "," + reactor.getPosition().y + "): " + id
                    + (fake ? " fake (shows the " : " real (") + picture.name().toLowerCase(Locale.ROOT) + ")"
                    + ", state " + reactor.getState());
        }
        return lines;
    }

    // ---- checkpoint ----

    /**
     * "checkpoint <stage>": the run must be inside CWKPQ and short of that stage. The flags of every stage before it
     * are set as their clears set them, the stage counter to the one before, then everyone alive in the event moves to
     * the stage's first portal inside the instance (never a !warp). The down stay where they fell.
     */
    private static List<String> checkpoint(Character operator, String[] params) {
        Checkpoint target = params.length < 2 ? null : Checkpoint.of(params[1]);
        if (target == null) {
            return List.of("Syntax: !cwkpqtest checkpoint <storage|agility|wit|unity|council|armory>");
        }
        EventInstanceManager event = cwkpqEvent(operator);
        if (event == null) {
            return List.of("Enter CWKPQ through Jack before a checkpoint.");
        }
        if (event.getIntProperty("current_instance") >= target.instance) {
            return List.of("The run is already at or past that stage.");
        }
        prepare(event, target);
        MapleMap map = event.getMapInstance(target.mapId);
        Portal portal = map.getPortal(0);
        int moved = 0;
        int down = 0;
        for (Character player : new ArrayList<>(event.getPlayers())) {
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
        log.info("CWKPQ checkpoint: event={} stage={} moved={} down={} by={}", event.getName(),
                target.name().toLowerCase(Locale.ROOT), moved, down, operator.getId());
        return List.of("CWKPQ checkpoint: " + moved + " moved to the " + target.name().toLowerCase(Locale.ROOT)
                + (down > 0 ? ", " + down + " down stay where they fell" : "")
                + ". Earlier stages are skipped for this run only.");
    }

    /** The event state the stock clears before {@code target} would have left, and the counter one stage short. */
    static void prepare(EventInstanceManager event, Checkpoint target) {
        int order = target.ordinal();
        event.setIntProperty("glpq1", 1);
        if (order >= Checkpoint.AGILITY.ordinal()) {
            event.setIntProperty("glpq2", 5);
        }
        if (order >= Checkpoint.WIT.ordinal()) {
            event.setIntProperty("glpq3", 5);
            event.setIntProperty("glpq3_p", 5);
        }
        if (order >= Checkpoint.UNITY.ordinal()) {
            event.setIntProperty("glpq4", 5);
        }
        if (order >= Checkpoint.COUNCIL.ordinal()) {
            event.setIntProperty("glpq5", 5);
        }
        if (target == Checkpoint.ARMORY) {
            event.setIntProperty("glpq6", 3);
            try {
                event.invokeScriptFunction("clearPQ", event);
            } catch (Exception failure) {
                throw new IllegalStateException("the event could not clear", failure);
            }
        }
        event.setIntProperty("current_instance", target.instance - 1);
    }

    // ---- stop ----

    private static List<String> stop(Character operator) {
        EventInstanceManager event = cwkpqEvent(operator);
        if (event == null) {
            return List.of("Stand inside the CWKPQ run to stop it.");
        }
        try {
            event.invokeScriptFunction("end", event);
        } catch (Exception failure) {
            throw new IllegalStateException("the event could not end", failure);
        }
        log.info("CWKPQ run stopped: event={} by={}", event.getName(), operator.getId());
        return List.of("CWKPQ stopped: everyone in it goes back to the Hall to Inner Sanctum.");
    }

    // ---- lookups ----

    /** The CWKPQ instance {@code operator} stands in, or null. */
    static EventInstanceManager cwkpqEvent(Character operator) {
        EventInstanceManager event = operator.getEventInstance();
        return event != null && event.getName() != null && event.getName().startsWith(EVENT_PREFIX) ? event : null;
    }

    /** The companion run on the CWKPQ list registering on {@code operator}'s channel, or null. */
    private static AgentBossRunSession registering(Character operator) {
        if (operator.getMap() == null || operator.getMap().getChannelServer() == null) {
            return null;
        }
        Expedition expedition = operator.getMap().getChannelServer().getExpedition(ExpeditionType.CWKPQ);
        return expedition == null ? null : AgentBossRunRegistry.forExpedition(expedition);
    }

    // ---- why ----

    /**
     * "why": each companion in the run you stand in, one line each in the chat and the server log: where it stands
     * and on what (ground, rope, air), its speed and jump, the job the stage play last gave it, where it is walking,
     * and the navigation's last decision.
     */
    private static List<String> why(Character operator) {
        EventInstanceManager event = cwkpqEvent(operator);
        AgentBossRunSession session = event == null ? null : AgentBossRunRegistry.forEvent(event);
        if (session == null) {
            return List.of("Stand inside a CWKPQ run with companions in it.");
        }
        AgentCwkpqRun run = session.fight() instanceof AgentCwkpqRun cwkpq ? cwkpq : null;
        PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        List<String> lines = new ArrayList<>();
        lines.add(flags(event));
        for (AgentBossRunMember member : session.members()) {
            Character agent = character(member.agentId());
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.agentId());
            if (agent == null || entry == null) {
                continue;
            }
            Point target = AgentMoveTargetStateRuntime.moveTarget(entry);
            String line = agent.getName() + " " + member.phase().name().toLowerCase(Locale.ROOT) + " on "
                    + agent.getMapId() + " at " + AgentCwkpqStagePlays.pointText(agent.getPosition()) + " "
                    + gateway.footing(agent).name().toLowerCase(Locale.ROOT) + ", speed "
                    + agent.getTotalMoveSpeedStat() + " jump " + agent.getTotalJumpStat()
                    + (agent.getBuffedValue(client.BuffStat.SPEED) != null ? " hasted" : "")
                    + "; job: " + (run == null ? "-" : String.valueOf(run.roleOf(agent.getId())))
                    + "; walking to " + (target == null ? "nothing" : AgentCwkpqStagePlays.pointText(target))
                    + "; nav: " + AgentNavigationDebugStateRuntime.decisionWithBlockReason(entry)
                    + "; physics: " + (entry.movementPhysicsState().inAir() ? "in the air" : "grounded")
                    + " at (" + Math.round(entry.movementPhysicsState().physicsX()) + ","
                    + Math.round(entry.movementPhysicsState().physicsY()) + ") vy "
                    + entry.movementPhysicsState().verticalVelocity() + " vx "
                    + Math.round(entry.movementPhysicsState().horizontalSpeed())
                    + "; sim " + entry.simulationState().mode().name().toLowerCase(Locale.ROOT)
                    + (entry.simulationState().fullRateSimulationRequired() ? " full-rate" : "")
                    + "; ticked " + (System.currentTimeMillis() - entry.tickState().lastTickAtMs()) + " ms ago"
                    + (entry.tickState().lastTickWasAi() ? " (ai)" : "");
            log.info("CWKPQ why: {}", line);
            lines.add(line);
        }
        return lines;
    }

    private static Character character(int id) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(id);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }

    private static List<String> help() {
        return List.of(
                "!cwkpqtest status (a CWKPQ run you stand in, or the list registering on your channel)",
                "!cwkpqtest why (inside a CWKPQ run: each companion's footing, job and walk, also in the server log)",
                "!cwkpqtest checkpoint <storage|agility|wit|unity|council|armory> (inside a CWKPQ run)",
                "!cwkpqtest stop (inside a CWKPQ run: the event ends for everyone in it)");
    }
}
