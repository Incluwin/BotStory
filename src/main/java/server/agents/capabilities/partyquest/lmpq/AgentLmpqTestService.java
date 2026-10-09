package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestTestQueueRuntime;
import server.agents.commands.AgentSpawnCommandExecutor;
import server.agents.field.AgentLmpqTestFixtureService;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.maps.MapleMap;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** GM-only variable-size LMPQ observation harness with persistent route evidence. */
public final class AgentLmpqTestService {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqTestService.class);
    private static final AgentSpawnCommandExecutor PROVISIONING = new AgentSpawnCommandExecutor();
    private static final ConcurrentHashMap<Integer, Run> RUNS = new ConcurrentHashMap<>();
    private static final long SPAWN_STAGGER_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lmpq.AgentLmpqTestService.SPAWN_STAGGER_MS");

    private AgentLmpqTestService() { }

    public static List<String> execute(Character operator, String[] params, long nowMs) {
        if (operator == null || !AgentAuthorityService.mayOperate(operator)) {
            return List.of("You are not configured as an Agent operator.");
        }
        if (params == null || params.length == 0) return help();
        try {
            return switch (params[0].toLowerCase()) {
                case "start" -> start(operator, startOptions(params, nowMs), nowMs);
                case "party", "adopt" -> adoptCurrentParty(operator, adoptOptions(params, nowMs), nowMs);
                case "status", "routes" -> status(operator);
                case "spectate" -> spectate(operator, params);
                case "pause" -> pause(operator, true);
                case "resume" -> pause(operator, false);
                case "stop" -> stop(operator, "stopped by operator", nowMs);
                default -> help();
            };
        } catch (Exception failure) {
            log.warn("LMPQ test command failed for operator {}", operator.getId(), failure);
            return List.of("LMPQ test command failed: " + failure.getMessage());
        }
    }

    static StartOptions startOptions(String[] params, long nowMs) {
        int partySize = params.length > 1 ? Integer.parseInt(params[1]) : 3;
        int rendezvousRoom = params.length > 2 ? Integer.parseInt(params[2])
                : AgentLmpqDefinition.RENDEZVOUS_ROOM;
        long seed = params.length > 3 ? Long.parseLong(params[3]) : nowMs;
        if (partySize < AgentLmpqDefinition.MIN_PARTY_SIZE
                || partySize > AgentLmpqDefinition.MAX_PARTY_SIZE) {
            throw new IllegalArgumentException("party size must be 3-6");
        }
        if (rendezvousRoom != AgentLmpqDefinition.RENDEZVOUS_ROOM
                && rendezvousRoom != AgentLmpqDefinition.CLEAR_ROOM) {
            throw new IllegalArgumentException("rendezvous room must be 9 or 16");
        }
        return new StartOptions(partySize, rendezvousRoom, seed);
    }

    /** "party prod [9|16] [seed]", in start's order: the prod token, then the rendezvous room, then the seed. */
    static AdoptOptions adoptOptions(String[] params, long nowMs) {
        boolean production = params.length > 1 && "prod".equalsIgnoreCase(params[1]);
        if (!production) return new AdoptOptions(false, 0, nowMs);
        int rendezvousRoom = params.length > 2 ? Integer.parseInt(params[2]) : 0;
        long seed = params.length > 3 ? Long.parseLong(params[3]) : nowMs;
        if (rendezvousRoom != 0 && rendezvousRoom != AgentLmpqDefinition.RENDEZVOUS_ROOM
                && rendezvousRoom != AgentLmpqDefinition.CLEAR_ROOM) {
            throw new IllegalArgumentException("rendezvous room must be 9 or 16");
        }
        return new AdoptOptions(true, rendezvousRoom, seed);
    }

    /**
     * "party prod [9|16] [seed]": the operator's own party at Rolly (his companions, any humans) becomes a companion
     * run through the public admit, as a companion host would enter it. Nothing is spawned, loaded, invited or
     * joined, so a companion owner can run it with his own companions; the run is kept here only for status and
     * stop. It runs on the GM command thread.
     */
    private static synchronized List<String> adoptCurrentParty(
            Character operator, AdoptOptions options, long nowMs) {
        if (!options.production()) {
            return List.of("LMPQ adopts a party only as a companion run: !lmpqtest party prod [9|16] [seed].");
        }
        if (operator.getMapId() != AgentLmpqDefinition.RECRUIT_MAP) {
            return List.of("Stand at the Ludibrium Maze entrance (220000000) before adopting a party.");
        }
        // A finished companion run keeps its entry here with nothing left to clean up; any other run needs a stop.
        Run existing = RUNS.get(operator.getId());
        if (existing != null && existing.flow == Flow.ADOPTED && finished(existing)) {
            RUNS.remove(operator.getId(), existing);
        }
        if (RUNS.containsKey(operator.getId())) {
            return List.of("Stop the current LMPQ test before adopting another party.");
        }
        AgentPartySnapshot snapshot = AgentPartyGatewayRuntime.party().snapshot(operator);
        if (snapshot == null) return List.of("Create the desired party before using !lmpqtest party prod.");
        List<Character> online = AgentPartyGatewayRuntime.party().onlineMembers(operator).stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (online.size() != snapshot.members().size()) {
            return List.of("Every party member must be online on this channel before the companion run starts.");
        }
        boolean leads = snapshot.members().stream().anyMatch(member -> member != null
                && member.id() == operator.getId() && member.leader());
        if (!leads) {
            return List.of("Lead the party yourself for a companion run: the public admit keys the run by its leader.");
        }
        AgentLmpqAdmissionService.AdmissionResult result =
                AgentLmpqAdmissionService.admit(operator, operator, online, options.seed(), nowMs);
        if (!result.success()) return List.of("Could not adopt the party: " + result.message());
        AgentLmpqSession session = result.session();
        session.setMemberCoordinationChatEnabled(true);
        if (options.rendezvousRoom() != 0) session.setRendezvousRoom(options.rendezvousRoom());
        Run run = new Run(operator, result.engagement(),
                new StartOptions(online.size(), session.rendezvousRoom(), options.seed()), Flow.ADOPTED);
        online.forEach(member -> run.names.put(member.getName(), member.getId()));
        run.session = session;
        run.leaderId = operator.getId();
        RUNS.put(operator.getId(), run);
        long minutes = Duration.ofMillis(AgentLmpqCoordinator.humanIdleLimitMs()).toMinutes();
        return List.of("Adopted as a companion run. Talk to Rolly now; the run waits up to " + minutes
                + (minutes == 1 ? " minute" : " minutes") + " for you.");
    }

    /**
     * A party prod companion run with nothing left for a stop to do: its session has ended and its engagement is
     * gone, failed or closed. An engagement still held (a handback deferred while a companion is in the event or
     * down) keeps the run, so '!lmpqtest stop' can still recover it.
     */
    private static boolean finished(Run run) {
        if (run.session != null && !run.session.terminal()) return false;
        AgentPartyQuestEngagement engagement = run.engagement;
        if (AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) return true;
        AgentPartyQuestEngagement.State state = engagement.state();
        return state == AgentPartyQuestEngagement.State.FAILED || state == AgentPartyQuestEngagement.State.CLOSED;
    }

    private static List<String> start(Character operator, StartOptions options, long nowMs) throws Exception {
        // His own companion run is never replaced, or '!lmpqtest stop' would lose it: a live one needs that stop
        // first, a finished one makes way.
        Run own = RUNS.get(operator.getId());
        if (own != null && own.flow == Flow.ADOPTED) {
            if (!finished(own)) return List.of("Stop the current LMPQ companion run first.");
            RUNS.remove(operator.getId(), own);
        }
        if (operator.getMapId() != AgentLmpqDefinition.RECRUIT_MAP) {
            return List.of("Stand at the LMPQ entrance (220000000) first.");
        }
        if (AgentPartyGatewayRuntime.party().snapshot(operator) != null) {
            return List.of("Leave your current party before starting LMPQ test.");
        }
        if (RUNS.containsKey(operator.getId())) stop(operator, "replaced by a new LMPQ test", nowMs);
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "lmpq", AgentPartyQuestEngagement.Mode.TEST_OBSERVATION,
                options.seed(), operator.getId(), options.partySize(), nowMs);
        Run run = new Run(operator, engagement, options, Flow.FIXTURE);
        RUNS.put(operator.getId(), run);
        AgentPartyQuestEngagementRegistry.register(engagement);
        engagement.beginLobby("lmpq-test-" + operator.getId(), nowMs);
        for (int index = 0; index < options.partySize(); index++) {
            int ordinal = index;
            String name = "LMPQ%dR%02d".formatted(options.partySize(), index + 1);
            String failure = PROVISIONING.ensureBackingCharacter(operator, name);
            if (failure != null) throw new IllegalStateException(failure);
            run.names.put(name, 0);
            AgentSchedulerRuntime.schedule(() -> launch(run, name, ordinal), SPAWN_STAGGER_MS * index);
        }
        return List.of(options.partySize() + " LMPQ Agents are being prepared at level 60.",
                "Rendezvous is Room " + options.rendezvousRoom()
                        + ". Use !lmpqtest status or !lmpqtest spectate.");
    }

    private static void launch(Run run, String name, int ordinal) {
        synchronized (run.lock) {
            if (RUNS.get(run.operator.getId()) != run) return;
            Character launched = null;
            try {
                int channel = AgentClientGatewayRuntime.clients().channel(run.operator);
                MapleMap recruit = AgentMapGatewayRuntime.map().resolveMap(
                        run.operator.getWorld(), channel, AgentLmpqDefinition.RECRUIT_MAP);
                if (recruit == null) throw new IllegalStateException("LMPQ recruitment map unavailable");
                Point spawn = recruit.getRandomPlayerSpawnpoint() == null ? new Point(0, 0)
                        : recruit.getRandomPlayerSpawnpoint().getPosition();
                Point grounded = AgentPrimitiveCapabilityGatewayRuntime.gateway().groundPoint(recruit, spawn);
                AgentLifecycleService.AgentSpawnResult result = AgentInteractionRuntime
                        .spawnStationaryAgentForLeaderAt(run.operator, name, recruit,
                                grounded == null ? spawn : grounded);
                if (!result.success()) throw new IllegalStateException(result.errorMessage());
                launched = result.agent();
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(launched.getId());
                if (entry == null) throw new IllegalStateException("spawned LMPQ runtime unavailable");
                AgentLmpqTestFixtureService.PreparationResult prepared = AgentLmpqTestFixtureService.prepare(
                        entry, ordinal, run.options.seed() + ordinal * 10_007L, System.currentTimeMillis());
                Point lobbySpawn = recruit.getRandomPlayerSpawnpoint() == null ? new Point(0, 0)
                        : recruit.getRandomPlayerSpawnpoint().getPosition();
                Point lobbyGround = AgentPrimitiveCapabilityGatewayRuntime.gateway()
                        .groundPoint(recruit, lobbySpawn);
                AgentMapGatewayRuntime.map().changeMapNear(
                        launched, recruit, lobbyGround == null ? lobbySpawn : lobbyGround);
                if (!AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, launched,
                        "entering LMPQ observation", System.currentTimeMillis())) {
                    throw new IllegalStateException(name + " could not release its previous activity");
                }
                long now = System.currentTimeMillis();
                run.names.put(name, launched.getId());
                log.info("LMPQ fixture launched: name={} job={} level={} weapon={} watk={}",
                        name, prepared.job(), prepared.level(), prepared.weaponItemId(), prepared.weaponAttack());
                var queued = AgentPartyQuestTestQueueRuntime.enqueue(
                        AgentLmpqLobbyProfile.profile(), entry, launched,
                        run.options.partySize(), now,
                        (candidate, admittedAtMs) -> admitQueued(run, candidate, admittedAtMs));
                if (queued.status() != server.agents.runtime.activity.session.AgentActivityAdmissionResult.Status.ACCEPTED) {
                    throw new IllegalStateException(queued.reason());
                }
            } catch (Exception failure) {
                if (launched != null) disconnect(launched.getId());
                fail(run, "Could not launch " + name + ": " + failure.getMessage());
            }
        }
    }

    private static void admitQueued(Run run, Character agent, long nowMs) {
        synchronized (run.lock) {
            if (RUNS.get(run.operator.getId()) != run) {
                throw new IllegalStateException("LMPQ test is no longer active");
            }
            AgentPartyQuestEngagementRegistry.addAndIndexMember(
                    run.engagement, agent.getId(), AgentPartyQuestEngagement.MemberType.AGENT, nowMs);
            joinParty(run, agent);
            run.agents.add(agent.getId());
            if (run.agents.size() == run.options.partySize()) activate(run, nowMs);
        }
    }

    private static void joinParty(Run run, Character agent) {
        Character leader = online(run.leaderId);
        if (leader == null) {
            if (!AgentPartyGatewayRuntime.party().createAgentParty(agent)) {
                throw new IllegalStateException("could not create LMPQ party");
            }
            run.leaderId = agent.getId();
            return;
        }
        var party = AgentPartyGatewayRuntime.party().snapshot(leader);
        if (party == null || !AgentPartyGatewayRuntime.party().joinAgentParty(agent, party.id())) {
            throw new IllegalStateException(agent.getName() + " could not join LMPQ party");
        }
        AgentPartyGatewayRuntime.party().publishAgentOnline(agent, party.id());
    }

    private static void activate(Run run, long nowMs) {
        // Started before the run is registered: a start that throws leaves nothing for a release to find.
        AgentLmpqWatchdogRuntime.ensureStarted();
        AgentLmpqSession session = new AgentLmpqSession(
                AgentLmpqSession.Mode.TEST_OBSERVATION, run.options.seed(), run.operator.getId(),
                run.options.partySize(), nowMs);
        run.agents.forEach(id -> session.addMember(id, AgentLmpqMemberState.MemberType.AGENT));
        session.setLeadership(run.leaderId, run.leaderId);
        session.setRendezvousRoom(run.options.rendezvousRoom());
        run.engagement.reserveEntry(nowMs);
        AgentLmpqSessionRegistry.registerComplete(session);
        run.engagement.activateSession(session.sessionId(), nowMs);
        run.session = session;
        run.operator.dropMessage(6, run.options.partySize()
                + "-Agent LMPQ activated. Movement is restricted to authored portals and navigation.");
    }

    private static List<String> status(Character operator) {
        Run run = RUNS.get(operator.getId());
        if (run == null) return List.of("No LMPQ test is active.");
        if (run.session == null) {
            return List.of("LMPQ Agents prepared: " + run.agents.size() + '/' + run.options.partySize());
        }
        List<String> lines = new ArrayList<>();
        String header = "LMPQ phase=" + run.session.phase() + ", rendezvous="
                + run.session.rendezvousRoom() + ", rooms=" + run.session.rooms().snapshot();
        lines.add(header);
        log.info("LMPQ status: session={} {}", run.session.sessionId(), header);
        for (Map.Entry<String, Integer> named : run.names.entrySet()) {
            AgentLmpqMemberState member = run.session.member(named.getValue());
            if (member == null) continue;
            String line = named.getKey() + ": initial=" + member.initialRoom()
                    + ", route=" + member.route() + ", assigned=" + member.assignments()
                    + ", target=" + member.targetRoom();
            lines.add(line);
            log.info("LMPQ route: session={} {}", run.session.sessionId(), line);
        }
        return lines;
    }

    private static List<String> spectate(Character operator, String[] params) {
        Run run = RUNS.get(operator.getId());
        if (run == null || run.session == null) return List.of("No active LMPQ session.");
        if (run.flow == Flow.ADOPTED) return List.of("A companion run's leader walks the maze himself.");
        String requested = params.length > 1 ? params[1] : "leader";
        int targetId = "leader".equalsIgnoreCase(requested)
                ? run.leaderId : run.names.getOrDefault(requested, 0);
        Character target = online(targetId);
        if (target == null || !AgentLmpqDefinition.isEventMap(target.getMapId())) {
            return List.of("The requested LMPQ Agent is not inside the maze yet.");
        }
        AgentMapGatewayRuntime.map().changeMapNear(operator, target.getMap(), target.getPosition());
        return List.of("Observing " + target.getName() + " in Maze Room "
                + AgentLmpqDefinition.roomForMap(target.getMapId()) + '.');
    }

    private static List<String> pause(Character operator, boolean paused) {
        Run run = RUNS.get(operator.getId());
        if (run == null || run.session == null) return List.of("No active LMPQ session.");
        run.session.setPaused(paused);
        return List.of(paused ? "LMPQ paused." : "LMPQ resumed.");
    }

    private static List<String> stop(Character operator, String reason, long nowMs) {
        Run run = RUNS.remove(operator.getId());
        if (run == null) return List.of("No LMPQ test is active.");
        if (run.flow == Flow.ADOPTED) {
            statusLinesToLog(run);
            stopAdopted(run, reason, nowMs);
            return List.of("LMPQ test stopped safely; final routes were written to the server log.");
        }
        if (run.session != null) {
            statusLinesToLog(run);
            // The line only: the release below claims the run on this thread, as before the line existed.
            if (!run.session.terminal()) AgentLmpqCoordinator.logFailure(run.session, reason);
            AgentLmpqTerminationService.release(run.session, reason, nowMs, true);
        }
        if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
            AgentPartyQuestLifecycleRuntime.closeTest(run.engagement, nowMs);
        }
        run.agents.forEach(AgentLmpqTestService::disconnect);
        return List.of("LMPQ test stopped safely; final routes were written to the server log.");
    }

    /**
     * Stops a party prod companion run. A live session fails with the stop's reason and is released as the companion
     * run it is (the party kept; after the clear the event is left to the humans); without one, an engagement still
     * registered is recovered, its companions handed back to their owner (closing it would drop them with no
     * handback). Nobody is logged out.
     */
    private static void stopAdopted(Run run, String reason, long nowMs) {
        AgentLmpqSession session = run.session;
        if (session != null && AgentLmpqSessionRegistry.forOperator(run.operator.getId()) == session) {
            if (!session.terminal()) AgentLmpqCoordinator.fail(session, reason, nowMs);
            AgentLmpqTerminationService.finish(session, reason, nowMs, true);
        } else if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
            run.engagement.beginRecovery(reason, nowMs);
            AgentPartyQuestLifecycleRuntime.recover(run.engagement, nowMs);
        }
    }

    private static void statusLinesToLog(Run run) {
        if (run.session == null) return;
        for (Map.Entry<String, Integer> named : run.names.entrySet()) {
            AgentLmpqMemberState member = run.session.member(named.getValue());
            if (member != null) log.info("LMPQ final route: session={} name={} initial={} route={} assigned={}",
                    run.session.sessionId(), named.getKey(), member.initialRoom(),
                    member.route(), member.assignments());
        }
    }

    private static void fail(Run run, String reason) {
        if (run == null || !RUNS.remove(run.operator.getId(), run)) return;
        long now = System.currentTimeMillis();
        statusLinesToLog(run);
        if (run.flow == Flow.ADOPTED) {
            stopAdopted(run, reason, now);
        } else {
            if (run.session != null) {
                if (!run.session.terminal()) AgentLmpqCoordinator.logFailure(run.session, reason);
                AgentLmpqTerminationService.release(run.session, reason, now, true);
            }
            if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
                AgentPartyQuestLifecycleRuntime.closeTest(run.engagement, now);
            }
            run.agents.forEach(AgentLmpqTestService::disconnect);
        }
        run.operator.dropMessage(6, "LMPQ stopped safely: " + reason);
    }

    private static void disconnect(int id) {
        Character agent = online(id);
        AgentRuntimeCleanupService.removeAgentByCharacterId(id);
        if (agent != null) AgentCharacterGatewayRuntime.characters().disconnect(agent, false, false);
    }

    private static Character online(int id) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(id);
        Character agent = entry == null ? null : server.agents.integration.AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }

    private static List<String> help() {
        return List.of("!lmpqtest start <3-6> [9|16] [seed]",
                "!lmpqtest party prod [9|16] [seed] (adopt your own party as a companion run; you lead)",
                "!lmpqtest status | routes | spectate [leader|name] | pause | resume | stop");
    }

    record StartOptions(int partySize, int rendezvousRoom, long seed) { }

    /** {@code rendezvousRoom} 0: the session's default. */
    record AdoptOptions(boolean production, int rendezvousRoom, long seed) { }

    /** FIXTURE: '!lmpqtest start' spawned the Agents; ADOPTED: '!lmpqtest party prod' adopted the operator's party. */
    private enum Flow { FIXTURE, ADOPTED }

    private static final class Run {
        final Object lock = new Object();
        final Character operator;
        final AgentPartyQuestEngagement engagement;
        final StartOptions options;
        final Flow flow;
        final Set<Integer> agents = new LinkedHashSet<>();
        final Map<String, Integer> names = new LinkedHashMap<>();
        volatile int leaderId;
        volatile AgentLmpqSession session;

        Run(Character operator, AgentPartyQuestEngagement engagement, StartOptions options, Flow flow) {
            this.operator = operator;
            this.engagement = engagement;
            this.options = options;
            this.flow = flow;
        }
    }
}
