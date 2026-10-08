package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestTestQueueRuntime;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.commands.AgentSpawnCommandExecutor;
import server.agents.field.AgentOpqTestFixtureService;
import server.agents.perception.AgentMapPerception;
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
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GM-only OPQ harness: the six-Agent observation run ('start'; its provisioning ends at the lobby), and a companion
 * run of the operator's own party ('party prod', admitted through the public admit), with its checkpoints.
 */
public final class AgentOpqTestService {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqTestService.class);
    private static final AgentSpawnCommandExecutor PROVISIONING = new AgentSpawnCommandExecutor();
    private static final ConcurrentHashMap<Integer, Run> RUNS = new ConcurrentHashMap<>();
    private static final long SPAWN_STAGGER_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqTestService.SPAWN_STAGGER_MS");

    private AgentOpqTestService() { }

    public static List<String> execute(Character operator, String[] params, long nowMs) {
        if (operator == null || !AgentAuthorityService.mayOperate(operator)) return List.of("You are not configured as an Agent operator.");
        if (params == null || params.length == 0) return help();
        try {
            return switch (params[0].toLowerCase()) {
                case "start" -> start(operator, params.length > 1 ? Long.parseLong(params[1]) : nowMs, nowMs);
                case "party", "adopt" -> adoptCurrentParty(operator, adoptOptions(params, 1, nowMs), nowMs);
                case "checkpoint" -> checkpoint(operator, params, nowMs);
                case "status" -> status(operator);
                case "pause" -> pause(operator, true);
                case "resume" -> pause(operator, false);
                case "stop" -> stop(operator, "stopped by operator", nowMs);
                default -> help();
            };
        } catch (Exception failure) {
            log.warn("OPQ test command failed for operator {}", operator.getId(), failure);
            return List.of("OPQ test command failed: " + failure.getMessage());
        }
    }

    private static List<String> start(Character operator, long seed, long nowMs) throws Exception {
        // His own companion run is never replaced, or '!opqtest stop' would lose it: a live one needs that stop first,
        // a finished one makes way.
        Run own = RUNS.get(operator.getId());
        if (own != null && own.flow == Flow.ADOPTED) {
            if (!finished(own)) return List.of("Stop the current OPQ companion run first.");
            RUNS.remove(operator.getId(), own);
        }
        if (operator.getMapId() != AgentOpqDefinition.RECRUIT_MAP) return List.of("Stand at the OPQ entrance (200080101) first.");
        if (AgentPartyGatewayRuntime.party().snapshot(operator) != null) return List.of("Leave your current party before starting OPQ test.");
        if (RUNS.containsKey(operator.getId())) stop(operator, "replaced by a new OPQ test", nowMs);
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "opq", AgentPartyQuestEngagement.Mode.TEST_OBSERVATION,
                seed, operator.getId(), AgentOpqDefinition.PARTY_SIZE, nowMs);
        Run run = new Run(operator, engagement, seed, Flow.FIXTURE);
        RUNS.put(operator.getId(), run);
        AgentPartyQuestEngagementRegistry.register(engagement);
        engagement.beginLobby("opq-test-" + operator.getId(), nowMs);
        for (int index = 0; index < AgentOpqDefinition.PARTY_SIZE; index++) {
            int ordinal = index;
            String name = "OPQer%02d".formatted(index + 1);
            String failure = PROVISIONING.ensureBackingCharacter(operator, name);
            if (failure != null) throw new IllegalStateException(failure);
            AgentSchedulerRuntime.schedule(() -> launch(run, name, ordinal), SPAWN_STAGGER_MS * index);
        }
        return List.of("Six OPQ Agents are being prepared at level 65 with all-job coverage.",
                "Eye, face-accessory, and medal slots are enforced empty. Use !opqtest status.");
    }

    /**
     * "party prod [seed]": the operator's own party at Wonky (his companions, any humans) becomes a companion run
     * through the public admit, as a companion host would enter it. Nothing is spawned, loaded, invited or joined, so a
     * companion owner can run it with his own companions; the run is kept here only for status, checkpoint and stop.
     * It runs on the GM command thread.
     */
    private static synchronized List<String> adoptCurrentParty(Character operator, AdoptOptions options, long nowMs) {
        if (!options.production()) {
            return List.of("OPQ adopts a party only as a companion run: !opqtest party prod [seed].");
        }
        if (operator.getMapId() != AgentOpqDefinition.RECRUIT_MAP) {
            return List.of("Stand at the Unknown Tower entrance (200080101) before adopting a party.");
        }
        // A finished companion run keeps its entry here with nothing left to clean up; any other run needs a stop.
        Run existing = RUNS.get(operator.getId());
        if (existing != null && existing.flow == Flow.ADOPTED && finished(existing)) {
            RUNS.remove(operator.getId(), existing);
        }
        if (RUNS.containsKey(operator.getId())) {
            return List.of("Stop the current OPQ test before adopting another party.");
        }
        AgentPartySnapshot snapshot = AgentPartyGatewayRuntime.party().snapshot(operator);
        if (snapshot == null) return List.of("Create the desired party before using !opqtest party prod.");
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
        AgentOpqAdmissionService.AdmissionResult result =
                AgentOpqAdmissionService.admit(operator, operator, online, options.seed(), nowMs);
        if (!result.success()) return List.of("Could not adopt the party: " + result.message());
        result.session().setMemberCoordinationChatEnabled(true);
        Run run = new Run(operator, result.engagement(), options.seed(), Flow.ADOPTED);
        run.session = result.session();
        run.leaderId = operator.getId();
        RUNS.put(operator.getId(), run);
        return List.of("Adopted as a companion run. Talk to Wonky now; the run waits up to "
                + minutes(AgentOpqCoordinator.phaseTimeoutMs()) + " for you.");
    }

    private static String minutes(long ms) {
        long minutes = Math.max(1L, ms / 60_000L);
        return minutes + (minutes == 1L ? " minute" : " minutes");
    }

    /**
     * A party prod companion run with nothing left for a stop to do: its session has ended and its engagement is gone,
     * failed or closed. An engagement still held (a handback deferred while a companion is in the event or down) keeps
     * the run, so '!opqtest stop' can still recover it.
     */
    private static boolean finished(Run run) {
        if (run.session != null && !run.session.terminal()) return false;
        AgentPartyQuestEngagement engagement = run.engagement;
        if (AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) return true;
        AgentPartyQuestEngagement.State state = engagement.state();
        return state == AgentPartyQuestEngagement.State.FAILED || state == AgentPartyQuestEngagement.State.CLOSED;
    }

    /**
     * "checkpoint <center|garden>": a party prod companion run already inside OPQ goes forward for a live check of a
     * later part, by the event's own team warp after the stage flags Eak would have set: the centre (the entrance
     * cleared) or the garden (the entrance and the six rooms cleared, the scars still empty: the Root restores Minerva
     * without them). statusStg7 is left as it is (only the centre Eak reads it). No item, monster or reactor is
     * touched; the coordinator then moves the run on as in a real run. It runs on the GM command thread.
     */
    private static List<String> checkpoint(Character operator, String[] params, long nowMs) {
        Run run = RUNS.get(operator.getId());
        AgentOpqSession session = run == null ? null : run.session;
        if (run == null || run.flow != Flow.ADOPTED || session == null) {
            return List.of("Checkpoints are for a companion run: !opqtest party prod first.");
        }
        synchronized (session) {
            if (session.terminal() || session.terminationStarted()) {
                return List.of("Checkpoints are for a companion run: !opqtest party prod first.");
            }
            AgentOpqSession.Phase from = session.phase();
            EventInstanceManager event = session.eventInstance();
            if (from == AgentOpqSession.Phase.PREPARING || from == AgentOpqSession.Phase.ENTERING
                    || event == null || event.isEventDisposed()) {
                return List.of("The OPQ party has not entered yet.");
            }
            String target = params.length < 2 ? "" : params[1].toLowerCase();
            boolean garden = "garden".equals(target);
            if (!garden && !"center".equals(target)) return List.of("Syntax: !opqtest checkpoint <center|garden>");
            String place = garden ? "the garden" : "the center";
            boolean ahead = from == AgentOpqSession.Phase.ENTRANCE
                    || garden && from == AgentOpqSession.Phase.SPLIT_ROOMS;
            if (!ahead || event.isEventCleared()) return List.of("The party is already at or past " + place + ".");
            List<Character> members = membersInside(session, event);
            if (members == null) return List.of("Every member must be inside the OPQ instance first.");
            int stagesCleared = garden ? 6 : 0;
            for (int stage = 0; stage <= stagesCleared; stage++) event.setProperty("statusStg" + stage, "1");
            int targetMap = garden ? AgentOpqDefinition.GARDEN_MAP : AgentOpqDefinition.CENTER_MAP;
            event.warpEventTeam(targetMap);
            session.markProgress(nowMs);
            log.info("OPQ checkpoint: session={} from={} to={} members={}", session.sessionId(), from, targetMap,
                    members.stream().map(Character::getId).toList());
            return List.of("OPQ checkpoint: everyone is in " + place + ".");
        }
    }

    /** The run's members, each online and inside {@code event} on one of its maps; null when any one is not. */
    private static List<Character> membersInside(AgentOpqSession session, EventInstanceManager event) {
        List<Character> members = new ArrayList<>();
        for (AgentOpqMemberState state : session.members()) {
            Character member = online(state.characterId());
            if (member == null || member.getEventInstance() != event
                    || !AgentOpqDefinition.isEventMap(member.getMapId())) {
                return null;
            }
            members.add(member);
        }
        return members;
    }

    private static void launch(Run run, String name, int ordinal) {
        synchronized (run.lock) {
            if (RUNS.get(run.operator.getId()) != run) return;
            Character launched = null;
            try {
                int channel = AgentClientGatewayRuntime.clients().channel(run.operator);
                MapleMap recruit = AgentMapGatewayRuntime.map().resolveMap(run.operator.getWorld(), channel, AgentOpqDefinition.RECRUIT_MAP);
                if (recruit == null) throw new IllegalStateException("OPQ recruitment map unavailable");
                Point spawn = recruit.getRandomPlayerSpawnpoint() == null ? new Point(0, 0)
                        : recruit.getRandomPlayerSpawnpoint().getPosition();
                Point grounded = AgentPrimitiveCapabilityGatewayRuntime.gateway().groundPoint(recruit, spawn);
                AgentLifecycleService.AgentSpawnResult result = AgentInteractionRuntime
                        .spawnStationaryAgentForLeaderAt(run.operator, name, recruit, grounded == null ? spawn : grounded);
                if (!result.success()) throw new IllegalStateException(result.errorMessage());
                launched = result.agent();
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(launched.getId());
                if (entry == null) throw new IllegalStateException("spawned OPQ runtime unavailable");
                AgentOpqTestFixtureService.PreparationResult prepared = prepareFixture(
                        entry, name, ordinal, run.seed + ordinal * 10_007L);
                // The reusable career fixture resets through a Victoria checkpoint and therefore
                // changes maps while it builds the character. Return the fully prepared fixture to
                // the recruitment map before OPQ owns it; after registerComplete, every transition
                // is authored navigation/portal travel in AgentOpqCoordinator.
                if (launched.getMapId() != AgentOpqDefinition.RECRUIT_MAP) {
                    Point returnPoint = AgentPrimitiveCapabilityGatewayRuntime.gateway()
                            .groundPoint(recruit, spawn);
                    AgentMapGatewayRuntime.map().changeMapNear(
                            launched, recruit, returnPoint == null ? spawn : returnPoint);
                }
                if (launched.getMapId() != AgentOpqDefinition.RECRUIT_MAP) {
                    throw new IllegalStateException(name + " could not return to the OPQ recruit map after fixture preparation");
                }
                if (!AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, launched,
                        "entering OPQ observation", System.currentTimeMillis())) {
                    throw new IllegalStateException(name + " could not release its previous activity");
                }
                long now = System.currentTimeMillis();
                log.info("OPQ fixture launched: name={} job={} level={} weapon={} watk={}",
                        name, prepared.job(), prepared.level(), prepared.weaponItemId(), prepared.weaponAttack());
                var queued = AgentPartyQuestTestQueueRuntime.enqueue(
                        AgentOpqLobbyProfile.profile(), entry, launched,
                        AgentOpqDefinition.PARTY_SIZE, now,
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
                throw new IllegalStateException("OPQ test is no longer active");
            }
            AgentPartyQuestEngagementRegistry.addAndIndexMember(
                    run.engagement, agent.getId(), AgentPartyQuestEngagement.MemberType.AGENT, nowMs);
            joinParty(run, agent);
            run.agents.add(agent.getId());
            if (run.agents.size() == AgentOpqDefinition.PARTY_SIZE) activate(run, nowMs);
        }
    }

    private static void joinParty(Run run, Character agent) {
        Character leader = online(run.leaderId);
        if (leader == null) {
            if (!AgentPartyGatewayRuntime.party().createAgentParty(agent)) throw new IllegalStateException("could not create OPQ party");
            run.leaderId = agent.getId();
            return;
        }
        var party = AgentPartyGatewayRuntime.party().snapshot(leader);
        if (party == null || !AgentPartyGatewayRuntime.party().joinAgentParty(agent, party.id())) {
            throw new IllegalStateException(agent.getName() + " could not join OPQ party");
        }
        AgentPartyGatewayRuntime.party().publishAgentOnline(agent, party.id());
    }

    private static AgentOpqTestFixtureService.PreparationResult prepareFixture(
            AgentRuntimeEntry entry, String name, int ordinal, long seed) throws Exception {
        Exception firstFailure;
        try {
            return AgentOpqTestFixtureService.prepare(
                    entry, ordinal, seed, System.currentTimeMillis());
        } catch (Exception failure) {
            firstFailure = failure;
            log.warn("OPQ fixture {} needed a second preparation pass: {}",
                    name, failure.getMessage());
        }
        try {
            return AgentOpqTestFixtureService.prepare(
                    entry, ordinal, seed, System.currentTimeMillis());
        } catch (Exception secondFailure) {
            secondFailure.addSuppressed(firstFailure);
            throw secondFailure;
        }
    }

    private static void activate(Run run, long nowMs) {
        AgentOpqSession session = new AgentOpqSession(
                AgentOpqSession.Mode.TEST_OBSERVATION, run.seed, run.operator.getId(), nowMs);
        run.agents.forEach(id -> session.addMember(id, AgentOpqMemberState.MemberType.AGENT));
        session.setLeadership(run.leaderId, run.leaderId);
        run.engagement.reserveEntry(nowMs);
        AgentOpqSessionRegistry.registerComplete(session);
        run.engagement.activateSession(session.sessionId(), nowMs);
        run.session = session;
        run.operator.dropMessage(6, "Six-Agent OPQ activated. Every in-PQ movement now uses authored navigation and portals.");
    }

    private static List<String> status(Character operator) {
        Run run = RUNS.get(operator.getId());
        if (run == null) return List.of("No OPQ test is active.");
        if (run.session == null) return List.of("OPQ Agents prepared: " + run.agents.size() + "/6");
        ArrayList<String> lines = new ArrayList<>();
        lines.add("OPQ phase: " + run.session.phase());
        lines.add("rooms: " + run.session.rooms().snapshot());
        lines.add("loot: " + run.session.loot().snapshot());
        if (run.session.phase() == AgentOpqSession.Phase.ENTRANCE) {
            lines.add(entranceStatus(run.session));
        }
        if (run.flow == Flow.ADOPTED) {
            // A companion run: every member, the human leader included, by name.
            run.session.members().forEach(member -> lines.add(adoptedMemberStatus(member)));
            return List.copyOf(lines);
        }
        run.session.members().stream()
                .filter(member -> member.memberType() == AgentOpqMemberState.MemberType.AGENT)
                .sorted(java.util.Comparator.comparingInt(AgentOpqMemberState::characterId))
                .forEach(member -> lines.add(memberStatus(member)));
        return List.copyOf(lines);
    }

    private static String adoptedMemberStatus(AgentOpqMemberState member) {
        Character character = online(member.characterId());
        if (character == null) return "member " + member.characterId() + ": offline role=" + member.role();
        return "member " + character.getName() + ": map=" + character.getMapId() + " role=" + member.role()
                + " room=" + member.assignedRoom();
    }

    private static String entranceStatus(AgentOpqSession session) {
        Character leader = online(session.eventLeaderId());
        if (leader == null || leader.getMapId() != AgentOpqDefinition.ENTRANCE_MAP) {
            return "entrance: leader unavailable";
        }
        List<String> clouds = leader.getMap().getAllReactors().stream()
                .filter(reactor -> reactor.getId() == 2_002_001 && reactor.isAlive() && reactor.isActive())
                .map(Reactor::getPosition)
                .map(Point::toString)
                .sorted()
                .toList();
        List<String> drops = AgentMapPerception.items(leader.getMap()).stream()
                .filter(drop -> !drop.isPickedUp() && drop.getItemId() == AgentOpqDefinition.CLOUD_PIECE)
                .map(drop -> drop.getPosition() + "x" + (drop.getItem() == null
                        ? 1 : Math.max(1, drop.getItem().getQuantity())))
                .sorted()
                .toList();
        int held = session.members().stream()
                .map(member -> online(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .mapToInt(agent -> agent.getItemQuantity(AgentOpqDefinition.CLOUD_PIECE, false))
                .sum();
        return "entrance: activeClouds=" + clouds + " cloudDrops=" + drops + " partyHeld=" + held;
    }

    private static String memberStatus(AgentOpqMemberState member) {
        Character agent = online(member.characterId());
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.characterId());
        if (agent == null) return "agent " + member.characterId() + ": offline role=" + member.role();
        return "agent " + agent.getName() + ": map=" + agent.getMapId()
                + " pos=" + agent.getPosition() + " stance=" + agent.getStance()
                + " role=" + member.role() + " room=" + member.assignedRoom()
                + " inAir=" + (entry != null && AgentMovementStateRuntime.inAir(entry))
                + " climbing=" + (entry != null && AgentMovementStateRuntime.climbing(entry));
    }

    private static List<String> pause(Character operator, boolean paused) {
        Run run = RUNS.get(operator.getId());
        if (run == null || run.session == null) return List.of("No active OPQ session.");
        run.session.setPaused(paused);
        return List.of(paused ? "OPQ paused." : "OPQ resumed.");
    }

    private static List<String> stop(Character operator, String reason, long nowMs) {
        Run run = RUNS.remove(operator.getId());
        if (run == null) return List.of("No OPQ test is active.");
        if (run.flow == Flow.ADOPTED) {
            stopAdopted(run, reason, nowMs);
            return List.of("OPQ test stopped safely.");
        }
        if (run.session != null) AgentOpqTerminationService.release(run.session, reason, nowMs, true);
        if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
            AgentPartyQuestLifecycleRuntime.closeTest(run.engagement, nowMs);
        }
        run.agents.forEach(AgentOpqTestService::disconnect);
        return List.of("OPQ test stopped safely.");
    }

    private static void fail(Run run, String reason) {
        if (run == null || !RUNS.remove(run.operator.getId(), run)) return;
        long now = System.currentTimeMillis();
        if (run.flow == Flow.ADOPTED) {
            stopAdopted(run, reason, now);
            run.operator.dropMessage(6, "OPQ stopped safely: " + reason);
            return;
        }
        if (run.session != null) AgentOpqTerminationService.release(run.session, reason, now, true);
        if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
            AgentPartyQuestLifecycleRuntime.closeTest(run.engagement, now);
        }
        run.agents.forEach(AgentOpqTestService::disconnect);
        run.operator.dropMessage(6, "OPQ stopped safely: " + reason);
    }

    /**
     * Stops a party prod companion run. A live session is ended as the companion run it is (the party kept; after the
     * clear the event is left to the humans); without one, an engagement still registered is recovered, its
     * companions handed back to their owner (closing it would drop them with no handback). Nobody is logged out.
     */
    private static void stopAdopted(Run run, String reason, long nowMs) {
        AgentOpqSession session = run.session;
        if (session != null && AgentOpqSessionRegistry.forOperator(run.operator.getId()) == session) {
            AgentOpqTerminationService.fail(session, reason, nowMs);
        } else if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
            run.engagement.beginRecovery(reason, nowMs);
            AgentPartyQuestLifecycleRuntime.recover(run.engagement, nowMs);
        }
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
        return List.of("!opqtest start [seed]",
                "!opqtest party prod [seed] (adopt your own party as a companion run; you lead)",
                "!opqtest checkpoint <center|garden> (party prod runs only)",
                "!opqtest status | pause | resume | stop");
    }

    /** "party [prod] [seed]", read as the other harnesses read it: the prod token comes before the seed. */
    static AdoptOptions adoptOptions(String[] params, int firstOptionIndex, long fallbackSeed) {
        boolean production = params != null && params.length > firstOptionIndex
                && "prod".equalsIgnoreCase(params[firstOptionIndex]);
        int seedIndex = firstOptionIndex + (production ? 1 : 0);
        long seed = params != null && params.length > seedIndex ? Long.parseLong(params[seedIndex]) : fallbackSeed;
        return new AdoptOptions(production, seed);
    }

    /** FIXTURE: the six OPQers of 'start'. ADOPTED: the operator's own party as a companion run. */
    private enum Flow { FIXTURE, ADOPTED }

    record AdoptOptions(boolean production, long seed) { }

    private static final class Run {
        final Object lock = new Object();
        final Character operator;
        final AgentPartyQuestEngagement engagement;
        final long seed;
        final Flow flow;
        final Set<Integer> agents = new LinkedHashSet<>();
        volatile int leaderId;
        volatile AgentOpqSession session;
        Run(Character operator, AgentPartyQuestEngagement engagement, long seed, Flow flow) {
            this.operator = operator; this.engagement = engagement; this.seed = seed; this.flow = flow;
        }
    }
}
