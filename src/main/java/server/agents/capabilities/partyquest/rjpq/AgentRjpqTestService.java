package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartySnapshot;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GM6 '!rjpqtest': the operator's own party of four beside Romeo or Juliet becomes a companion run through the public
 * admit (nothing is spawned, invited or joined), then status, stop and stage checkpoints for live checks. RJPQ has no
 * fixture flows: every run is a companion run.
 */
public final class AgentRjpqTestService {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqTestService.class);
    private static final ConcurrentHashMap<Integer, Run> RUNS = new ConcurrentHashMap<>();

    /** The stages a checkpoint can skip to, in order. */
    enum Checkpoint {
        TUNNEL(AgentRjpqDefinition.TUNNEL_MAP),
        BEAKERS(AgentRjpqDefinition.BEAKER_MAP),
        KEYS(AgentRjpqDefinition.SPECIAL_LAB_MAP),
        OFFICE(AgentRjpqDefinition.OFFICE_MAP),
        UNITS(AgentRjpqDefinition.LAB_TUNNEL_MAP),
        CENTER(AgentRjpqDefinition.CENTER_ENTRANCE_MAP);

        final int zenumistMap;

        Checkpoint(int zenumistMap) {
            this.zenumistMap = zenumistMap;
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

    private AgentRjpqTestService() {
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
                case "party", "adopt" -> adoptCurrentParty(operator, params, nowMs);
                case "status" -> status(operator);
                case "stop" -> stop(operator, "stopped by operator", nowMs);
                case "checkpoint" -> checkpoint(operator, params, nowMs);
                default -> help();
            };
        } catch (RuntimeException failure) {
            log.warn("RJPQ test command failed for operator {}", operator.getId(), failure);
            return List.of("RJPQ test command failed: " + failure.getMessage());
        }
    }

    /** "party prod [seed]": the operator's own party becomes a companion run of the side he stands on. */
    private static synchronized List<String> adoptCurrentParty(Character operator, String[] params, long nowMs) {
        if (params.length < 2 || !"prod".equalsIgnoreCase(params[1])) {
            return List.of("RJPQ adopts a party only as a companion run: !rjpqtest party prod [seed].");
        }
        AgentRjpqSide side = AgentRjpqSide.fromRecruitMap(operator.getMapId());
        if (side == null) {
            return List.of("Stand beside Romeo (261000011) or Juliet (261000021) before adopting a party.");
        }
        Run existing = RUNS.get(operator.getId());
        if (existing != null && finished(existing)) {
            RUNS.remove(operator.getId(), existing);
        }
        if (RUNS.containsKey(operator.getId())) {
            return List.of("Stop the current RJPQ test before adopting another party.");
        }
        AgentPartySnapshot snapshot = AgentPartyGatewayRuntime.party().snapshot(operator);
        if (snapshot == null) {
            return List.of("Create the party first: you and three companions.");
        }
        List<Character> online = AgentPartyGatewayRuntime.party().onlineMembers(operator).stream()
                .filter(Objects::nonNull).distinct().toList();
        if (online.size() != snapshot.members().size()) {
            return List.of("Every party member must be online on this channel before the companion run starts.");
        }
        boolean leads = snapshot.members().stream().anyMatch(member -> member != null
                && member.id() == operator.getId() && member.leader());
        if (!leads) {
            return List.of("Lead the party yourself for a companion run: the public admit keys the run by its leader.");
        }
        long seed = seed(params, 2, nowMs);
        AgentRjpqAdmissionService.AdmissionResult result =
                AgentRjpqAdmissionService.admit(operator, operator, online, seed, nowMs);
        if (!result.success()) {
            return List.of("Could not adopt the party: " + result.message());
        }
        result.session().setMemberCoordinationChatEnabled(true);
        RUNS.put(operator.getId(), new Run(operator, result.engagement(), result.session()));
        return List.of("Adopted as a companion run. Talk to "
                + (side == AgentRjpqSide.ZENUMIST ? "Romeo" : "Juliet") + " within 30 seconds.");
    }

    /** A run with nothing left for a stop to do: its session ended and its engagement is gone, failed or closed. */
    private static boolean finished(Run run) {
        if (!run.session.terminal()) {
            return false;
        }
        AgentPartyQuestEngagement engagement = run.engagement;
        if (AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) {
            return true;
        }
        AgentPartyQuestEngagement.State state = engagement.state();
        return state == AgentPartyQuestEngagement.State.FAILED || state == AgentPartyQuestEngagement.State.CLOSED;
    }

    private static List<String> status(Character operator) {
        Run run = RUNS.get(operator.getId());
        if (run == null) {
            return List.of("No RJPQ test is active.");
        }
        AgentRjpqSession session = run.session;
        EventInstanceManager event = session.eventInstance();
        List<String> lines = new ArrayList<>();
        lines.add("RJPQ " + session.side().key() + " phase: " + session.phase()
                + (session.terminal() ? " (" + session.failure() + ")" : ""));
        lines.add("members: " + session.memberCount() + " held dead: " + session.heldDeadCount());
        if (event != null) {
            StringBuilder stages = new StringBuilder("stages:");
            for (int stage = 1; stage <= 7; stage++) {
                String value = event.getProperty("statusStg" + stage);
                stages.append(" ").append(stage).append('=').append(value == null ? "-" : value);
            }
            lines.add(stages.toString());
        }
        log.info("RJPQ status: session={} side={} phase={} members={} heldDead={} event={}", session.sessionId(),
                session.side().key(), session.phase(), session.memberCount(), session.heldDeadCount(),
                event == null ? "unbound" : event.getName());
        return List.copyOf(lines);
    }

    /**
     * Stops a party prod run as the companion run it is: a live session is released (the party is kept); without one,
     * an engagement still registered is recovered, so its companions are handed back to their owner. Nobody leaves the
     * party and nobody is logged out.
     */
    private static synchronized List<String> stop(Character operator, String reason, long nowMs) {
        Run run = RUNS.remove(operator.getId());
        if (run == null) {
            return List.of("No RJPQ test is active.");
        }
        if (AgentRjpqSessionRegistry.forOperator(operator.getId()) == run.session) {
            AgentRjpqTerminationService.release(run.session, reason, nowMs, true);
        } else if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
            run.engagement.beginRecovery(reason, nowMs);
            AgentPartyQuestLifecycleRuntime.recover(run.engagement, nowMs);
        }
        return List.of("RJPQ test stopped safely.");
    }

    /**
     * "checkpoint <tunnel|beakers|keys|office|units|center>": a party prod run already inside RJPQ goes forward to that
     * stage. The run is read under its lock; every change to the event, its reactors and the members' maps is made
     * after it, outside the lock: the earlier stages' flags as the stock clears set them (never statusStg5, the Lab Unit
     * rooms, or statusStg6), the gating doors opened, the cleared maps emptied, the Lab Unit answers generated by the
     * event's own function, then every member moved inside the instance to the stage's first portal (never a !warp).
     */
    private static List<String> checkpoint(Character operator, String[] params, long nowMs) {
        Checkpoint target = params.length < 2 ? null : Checkpoint.of(params[1]);
        if (target == null) {
            return List.of("Syntax: !rjpqtest checkpoint <tunnel|beakers|keys|office|units|center>");
        }
        Run run = RUNS.get(operator.getId());
        if (run == null) {
            return List.of("Checkpoints apply only to a party prod companion run.");
        }
        AgentRjpqSession session = run.session;
        EventInstanceManager event;
        List<Character> members;
        synchronized (session) {
            event = session.eventInstance();
            if (event == null || session.terminal() || session.terminationStarted()
                    || operator.getEventInstance() != event) {
                return List.of("Enter RJPQ through " + session.side().npcName() + " before a checkpoint.");
            }
            members = new ArrayList<>();
            for (AgentRjpqMemberState member : session.members()) {
                Character character = AgentRjpqCoordinator.character(member.characterId());
                if (character == null || character.getEventInstance() != event) {
                    return List.of("Every member must be inside the RJPQ instance first.");
                }
                members.add(character);
            }
        }
        AgentRjpqSide side = session.side();
        AgentRjpqSession.Phase targetPhase = AgentRjpqCoordinator.phaseFor(target.zenumistMap);
        if (targetPhase == null || session.phase().ordinal() >= targetPhase.ordinal()) {
            return List.of("The party is already at or past that stage.");
        }
        prepare(event, side, target);
        MapleMap map = event.getMapInstance(side.map(target.zenumistMap));
        var portal = map.getPortal(0);
        Point spawn = portal == null ? new Point(0, 0) : portal.getPosition();
        for (Character member : members) {
            AgentMapGatewayRuntime.map().changeMapNear(member, map, spawn);
        }
        session.markProgress(nowMs);
        log.info("RJPQ checkpoint: session={} side={} stage={} by={}", session.sessionId(), side.key(),
                target.name().toLowerCase(Locale.ROOT), operator.getId());
        return List.of("RJPQ checkpoint: everyone is at the " + target.name().toLowerCase(Locale.ROOT)
                + ". Earlier stages are skipped for this run only.");
    }

    /** The event state the stock clears before {@code target} would have left. */
    static void prepare(EventInstanceManager event, AgentRjpqSide side, Checkpoint target) {
        int order = target.ordinal();
        event.setIntProperty("statusStg1", 1);
        if (order >= Checkpoint.BEAKERS.ordinal()) {
            event.setIntProperty("statusStg2", 1);
            clear(event, side.map(AgentRjpqDefinition.TUNNEL_MAP));
        }
        if (order >= Checkpoint.KEYS.ordinal()) {
            event.setIntProperty("statusStg3", 3);
            clear(event, side.map(AgentRjpqDefinition.BEAKER_MAP));
            open(event, side.map(AgentRjpqDefinition.BEAKER_MAP), side.reactorName(AgentRjpqDefinition.BEAKER_DOOR));
        }
        if (order >= Checkpoint.OFFICE.ordinal()) {
            event.setIntProperty("statusStg4", 1);
            int lab = side.map(AgentRjpqDefinition.SPECIAL_LAB_MAP);
            clear(event, lab);
            open(event, lab, side.reactorName(AgentRjpqDefinition.LAB_ONE_DOOR));
            open(event, lab, side.reactorName(AgentRjpqDefinition.LAB_TWO_DOOR));
            open(event, lab, side.reactorName(AgentRjpqDefinition.OFFICE_DOOR));
            open(event, side.map(AgentRjpqDefinition.DARK_LAB_ONE_MAP),
                    side.reactorName(AgentRjpqDefinition.LAB_ONE_EXIT));
            open(event, side.map(AgentRjpqDefinition.DARK_LAB_TWO_MAP),
                    side.reactorName(AgentRjpqDefinition.LAB_TWO_EXIT));
        }
        if (order >= Checkpoint.UNITS.ordinal()) {
            int office = side.map(AgentRjpqDefinition.OFFICE_MAP);
            clear(event, office);
            open(event, office, side.reactorName(AgentRjpqDefinition.OFFICE_EXIT));
            try {
                event.invokeScriptFunction("generateStg6Combo", event);
            } catch (Exception failure) {
                throw new IllegalStateException("the event could not deal the Lab Unit answers", failure);
            }
        }
    }

    private static void clear(EventInstanceManager event, int mapId) {
        MapleMap map = event.getMapInstance(mapId);
        if (map != null) {
            map.killAllMonsters();
        }
    }

    private static void open(EventInstanceManager event, int mapId, String reactorName) {
        MapleMap map = event.getMapInstance(mapId);
        Reactor reactor = map == null ? null : map.getReactorByName(reactorName);
        if (reactor != null && reactor.getState() != 1) {
            reactor.forceHitReactor((byte) 1);
        }
    }

    private static long seed(String[] params, int index, long fallback) {
        if (params.length <= index) {
            return fallback;
        }
        try {
            return Long.parseLong(params[index]);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> help() {
        return List.of(
                "!rjpqtest party prod [seed] (beside Romeo or Juliet: adopt your party of four as a companion run)",
                "!rjpqtest checkpoint <tunnel|beakers|keys|office|units|center> (party prod runs inside RJPQ)",
                "!rjpqtest status | stop");
    }

    private record Run(Character operator, AgentPartyQuestEngagement engagement, AgentRjpqSession session) {
    }
}
