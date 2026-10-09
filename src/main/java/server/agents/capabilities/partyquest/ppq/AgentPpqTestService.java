package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentAuthorityService;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestCandidateScope;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbySession;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestTestQueueRuntime;
import server.agents.commands.AgentSpawnCommandExecutor;
import server.agents.field.AgentPpqTestFixtureService;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.life.LifeFactory;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** GM-only six-Agent PPQ observation harness. */
public final class AgentPpqTestService {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqTestService.class);
    private static final AgentSpawnCommandExecutor PROVISIONING = new AgentSpawnCommandExecutor();
    private static final ConcurrentHashMap<Integer, Run> RUNS = new ConcurrentHashMap<>();
    private static final long SPAWN_STAGGER_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqTestService.SPAWN_STAGGER_MS");
    private AgentPpqTestService() { }

    public static List<String> execute(Character operator, String[] params, long nowMs) {
        if (operator == null || !AgentAuthorityService.mayOperate(operator)) return List.of("You are not configured as an Agent operator.");
        if (params == null || params.length == 0) return help();
        try {
            return switch (params[0].toLowerCase()) {
                case "start" -> start(operator, Flow.AGENTS_ONLY, parseSkip(params), parseSeed(params, nowMs), nowMs);
                case "humanleader", "withme" -> start(operator, Flow.HUMAN_LEADER,
                        parseSkip(params), parseSeed(params, nowMs), nowMs);
                case "humanmember", "agentleader" -> start(operator, Flow.HUMAN_MEMBER,
                        parseSkip(params), parseSeed(params, nowMs), nowMs);
                case "party", "adopt" -> adoptCurrentParty(operator, params, nowMs);
                case "checkpoint" -> checkpoint(operator, params, nowMs);
                case "status" -> status(operator);
                case "pause" -> pause(operator, true);
                case "resume" -> pause(operator, false);
                case "stop" -> stop(operator, "stopped by operator", nowMs);
                default -> help();
            };
        } catch (Exception failure) {
            log.warn("PPQ test command failed for operator {}", operator.getId(), failure);
            return List.of("PPQ test command failed: " + failure.getMessage());
        }
    }

    private static List<String> start(Character operator, Flow flow, boolean skipChests,
                                      long seed, long nowMs) throws Exception {
        if (operator.getMapId() != AgentPpqDefinition.RECRUIT_MAP) return List.of("Stand at the Pirate PQ entrance (251010404) first.");
        if (flow != Flow.AGENTS_ONLY && (operator.getLevel() < 55 || operator.getLevel() > 100)) {
            return List.of("Your participating character must be level 55-100.");
        }
        Run live = RUNS.get(operator.getId());
        if (live != null && live.flow == Flow.ADOPTED && !finished(live)) {
            return List.of("A PPQ companion run is live: stop it first (!ppqtest stop).");
        }
        if (AgentPartyGatewayRuntime.party().snapshot(operator) != null) return List.of("Leave your current party before starting PPQ test.");
        if (RUNS.containsKey(operator.getId())) stop(operator, "replaced by a new PPQ test", nowMs);
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "ppq", AgentPartyQuestEngagement.Mode.TEST_OBSERVATION,
                seed, operator.getId(), AgentPpqDefinition.PARTY_SIZE, nowMs);
        if (flow != Flow.AGENTS_ONLY) engagement.addMember(
                operator.getId(), AgentPartyQuestEngagement.MemberType.HUMAN, nowMs);
        Run run = new Run(operator, engagement, seed, skipChests, flow);
        if (flow == Flow.HUMAN_LEADER) {
            if (!AgentPartyGatewayRuntime.party().createAgentParty(operator)) {
                throw new IllegalStateException("could not create the human-led PPQ party");
            }
            run.leaderId = operator.getId();
        }
        RUNS.put(operator.getId(), run);
        AgentPartyQuestEngagementRegistry.register(engagement);
        openLobby(run, nowMs);
        int agentCount = flow == Flow.AGENTS_ONLY ? AgentPpqDefinition.PARTY_SIZE : 5;
        for (int index = 0; index < agentCount; index++) {
            int ordinal = index;
            String name = "PPQer%02d".formatted(index + 1);
            String failure = PROVISIONING.ensureBackingCharacter(operator, name);
            if (failure != null) throw new IllegalStateException(failure);
            AgentSchedulerRuntime.schedule(() -> launch(run, name, ordinal), SPAWN_STAGGER_MS * index);
        }
        return List.of((agentCount == 6 ? "Six" : "Five")
                        + " level-" + AgentPpqTestFixtureService.LEVEL + " PPQ Agents are being prepared with complete AP/SP builds and distinct class hats.",
                flow == Flow.HUMAN_LEADER ? "You are the event leader; the Agents will join your party."
                        : flow == Flow.HUMAN_MEMBER ? "The first Agent is event leader; you will join as the human member."
                        : "The run is fully Agent-controlled.",
                skipChests ? "Chest rooms will be skipped." : "Both chest rooms are enabled; two test chest keys will be provisioned.",
                "Use !ppqtest status.");
    }

    private static void launch(Run run, String name, int ordinal) {
        synchronized (run.lock) {
            if (RUNS.get(run.operator.getId()) != run) return;
            Character launched = null;
            try {
                int channel = AgentClientGatewayRuntime.clients().channel(run.operator);
                MapleMap recruit = AgentMapGatewayRuntime.map().resolveMap(
                        run.operator.getWorld(), channel, AgentPpqDefinition.RECRUIT_MAP);
                if (recruit == null) throw new IllegalStateException("PPQ recruitment map unavailable");
                Point spawn = recruit.getRandomPlayerSpawnpoint() == null ? new Point(0, 0)
                        : recruit.getRandomPlayerSpawnpoint().getPosition();
                Point grounded = AgentPrimitiveCapabilityGatewayRuntime.gateway().groundPoint(recruit, spawn);
                AgentLifecycleService.AgentSpawnResult result = AgentInteractionRuntime
                        .spawnStationaryAgentForLeaderAt(run.operator, name, recruit, grounded == null ? spawn : grounded);
                if (!result.success()) throw new IllegalStateException(result.errorMessage());
                launched = result.agent();
                AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(launched.getId());
                if (entry == null) throw new IllegalStateException("spawned PPQ runtime unavailable");
                AgentPpqTestFixtureService.PreparationResult prepared = prepareFixture(
                        entry, name, ordinal, run.seed + ordinal * 10_007L);
                if (launched.getMapId() != AgentPpqDefinition.RECRUIT_MAP) {
                    Point returnPoint = AgentPrimitiveCapabilityGatewayRuntime.gateway()
                            .groundPoint(recruit, spawn);
                    AgentMapGatewayRuntime.map().changeMapNear(
                            launched, recruit, returnPoint == null ? spawn : returnPoint);
                }
                if (launched.getMapId() != AgentPpqDefinition.RECRUIT_MAP) {
                    throw new IllegalStateException(name + " could not return to the PPQ recruit map");
                }
                if (!AgentActivityBootstrap.admission().prepare(
                        AgentActivityBootstrap.PARTY_QUEST_CONTROLLER_ID, entry, launched,
                        "entering PPQ observation", System.currentTimeMillis())) {
                    throw new IllegalStateException(name + " could not release its previous activity");
                }
                long now = System.currentTimeMillis();
                log.info("PPQ fixture launched: name={} job={} level={} weapon={} attack={}",
                        name, prepared.job(), prepared.level(), prepared.weaponItemId(), prepared.weaponAttack());
                int expectedAgents = run.flow == Flow.AGENTS_ONLY ? AgentPpqDefinition.PARTY_SIZE : 5;
                int queueSize = run.flow == Flow.HUMAN_LEADER
                        ? AgentPpqDefinition.PARTY_SIZE : expectedAgents;
                var queued = AgentPartyQuestTestQueueRuntime.enqueue(
                        AgentPpqLobbyProfile.profile(), entry, launched, queueSize, now,
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
                throw new IllegalStateException("PPQ test is no longer active");
            }
            AgentPartyQuestEngagementRegistry.addAndIndexMember(
                    run.engagement, agent.getId(), AgentPartyQuestEngagement.MemberType.AGENT, nowMs);
            var current = AgentPartyGatewayRuntime.party().snapshot(agent);
            if (current == null) joinParty(run, agent);
            AgentPartyQuestLobbyRegistry.addAndIndexMember(
                    run.lobby, agent.getId(), AgentPartyQuestLobbySession.MemberType.AGENT,
                    agent.getId() == run.leaderId
                            ? AgentPartyQuestLobbySession.MemberRole.RECRUITING_LEADER
                            : AgentPartyQuestLobbySession.MemberRole.JOINED_MEMBER, nowMs);
            run.agents.add(agent.getId());
            int expectedAgents = run.flow == Flow.AGENTS_ONLY
                    ? AgentPpqDefinition.PARTY_SIZE : 5;
            if (run.agents.size() == expectedAgents) activate(run, nowMs);
        }
    }

    private static void joinParty(Run run, Character agent) {
        Character leader = online(run.leaderId);
        if (leader == null) {
            if (!AgentPartyGatewayRuntime.party().createAgentParty(agent)) throw new IllegalStateException("could not create PPQ party");
            run.leaderId = agent.getId(); return;
        }
        var party = AgentPartyGatewayRuntime.party().snapshot(leader);
        if (party == null || !AgentPartyGatewayRuntime.party().joinAgentParty(agent, party.id())) {
            throw new IllegalStateException(agent.getName() + " could not join PPQ party");
        }
        AgentPartyGatewayRuntime.party().publishAgentOnline(agent, party.id());
    }

    private static AgentPpqTestFixtureService.PreparationResult prepareFixture(
            AgentRuntimeEntry entry, String name, int ordinal, long seed) throws Exception {
        Exception firstFailure;
        try {
            return AgentPpqTestFixtureService.prepare(
                    entry, ordinal, seed, System.currentTimeMillis());
        } catch (Exception failure) {
            firstFailure = failure;
            log.warn("PPQ fixture {} needed a second preparation pass: {}", name, failure.getMessage());
        }
        try {
            return AgentPpqTestFixtureService.prepare(
                    entry, ordinal, seed, System.currentTimeMillis());
        } catch (Exception secondFailure) {
            secondFailure.addSuppressed(firstFailure);
            throw secondFailure;
        }
    }

    private static void openLobby(Run run, long nowMs) {
        AgentPartyQuestLobbySession lobby = new AgentPartyQuestLobbySession(
                run.engagement.engagementId(), AgentPpqLobbyProfile.profile(), run.seed,
                run.operator.getId(), AgentPpqDefinition.PARTY_SIZE,
                AgentPartyQuestCandidateScope.OWNER_ONLY, nowMs);
        if (run.flow != Flow.AGENTS_ONLY) lobby.addMember(run.operator.getId(),
                AgentPartyQuestLobbySession.MemberType.HUMAN,
                run.flow == Flow.HUMAN_LEADER
                        ? AgentPartyQuestLobbySession.MemberRole.RECRUITING_LEADER
                        : AgentPartyQuestLobbySession.MemberRole.JOINED_MEMBER, nowMs);
        AgentPartyQuestLobbyRuntime.register(lobby, nowMs);
        run.engagement.beginLobby(lobby.lobbyId(), nowMs);
        run.lobby = lobby;
    }

    private static void activate(Run run, long nowMs) {
        if (run.flow == Flow.HUMAN_MEMBER) {
            Character leader = online(run.leaderId);
            var party = leader == null ? null : AgentPartyGatewayRuntime.party().snapshot(leader);
            if (party == null || !AgentPartyGatewayRuntime.party().joinAgentParty(run.operator, party.id())) {
                throw new IllegalStateException("human member could not join the Agent-led PPQ party");
            }
        }
        AgentPpqSession session = new AgentPpqSession(
                switch (run.flow) {
                    case AGENTS_ONLY -> AgentPpqSession.Mode.TEST_OBSERVATION;
                    case HUMAN_LEADER, ADOPTED -> AgentPpqSession.Mode.HUMAN_LEADER;
                    case HUMAN_MEMBER -> AgentPpqSession.Mode.HUMAN_MEMBER;
                }, run.seed, run.operator.getId(), run.skipChests, nowMs);
        if (run.flow != Flow.AGENTS_ONLY) {
            session.addMember(run.operator.getId(), AgentPpqMemberState.MemberType.HUMAN);
        }
        run.agents.forEach(id -> session.addMember(id, AgentPpqMemberState.MemberType.AGENT));
        int executorId = run.agents.iterator().next();
        session.setLeadership(run.leaderId, executorId);
        if (!run.skipChests) {
            Character leader = online(run.leaderId);
            if (leader == null || !AgentInventoryGatewayRuntime.inventory().addItem(
                    leader, AgentPpqDefinition.CHEST_KEY, (short) 2)) {
                throw new IllegalStateException("could not provision PPQ chest keys");
            }
        }
        run.lobby.markReady(nowMs);
        run.engagement.lobbyReady(nowMs);
        AgentPartyQuestLobbyRuntime.unregister(run.lobby.lobbyId(), nowMs);
        run.lobby = null;
        run.engagement.reserveEntry(nowMs);
        AgentPpqSessionRegistry.registerComplete(session);
        run.engagement.activateSession(session.sessionId(), nowMs);
        run.session = session;
        run.operator.dropMessage(6, "PPQ party activated; chest rooms "
                + (run.skipChests ? "will be skipped." : "are enabled."));
    }

    private static List<String> status(Character operator) {
        Run run = RUNS.get(operator.getId());
        if (run == null) return List.of("No PPQ test is active.");
        if (run.session == null) return List.of("PPQ Agents prepared: " + run.agents.size()
                + "/" + (run.flow == Flow.AGENTS_ONLY ? 6 : 5));
        Character leader = online(run.session.eventLeaderId());
        if (leader != null && leader.getMap() != null) {
            long floorMedals = leader.getMap().getDroppedItems().stream()
                    .filter(drop -> !drop.isPickedUp()
                            && AgentPpqDefinition.MEDALS.contains(drop.getItemId()))
                    .count();
            int liveMobs = AgentPrimitiveCapabilityGatewayRuntime.gateway()
                    .liveMonsterCount(leader, AgentPpqDefinition.COMBAT_MOBS);
            log.info("PPQ status phase={} map={} pos={} stage2={} medals={}/{}/{} floorMedals={} liveMobs={}",
                    run.session.phase(), leader.getMapId(), leader.getPosition(),
                    AgentPartyQuestGatewayRuntime.partyQuest().property(leader, "stage2"),
                    leader.getItemQuantity(AgentPpqDefinition.ROOKIE_MEDAL, false),
                    leader.getItemQuantity(AgentPpqDefinition.RISING_MEDAL, false),
                    leader.getItemQuantity(AgentPpqDefinition.VETERAN_MEDAL, false),
                    floorMedals, liveMobs);
        }
        return List.of("PPQ phase: " + run.session.phase(),
                "leader: " + run.session.eventLeaderId(),
                "chest rooms: " + (run.skipChests ? "skipped" : "enabled"));
    }
    private static List<String> pause(Character operator, boolean paused) {
        Run run = RUNS.get(operator.getId());
        if (run == null || run.session == null) return List.of("No active PPQ session.");
        run.session.setPaused(paused); return List.of(paused ? "PPQ paused." : "PPQ resumed.");
    }
    /**
     * "party prod [skipchests] [seed]": the operator's own fully online party of four to six beside Guon becomes a
     * companion run through the public admit. Nothing is spawned, invited, joined or granted.
     */
    private static synchronized List<String> adoptCurrentParty(Character operator, String[] params, long nowMs) {
        if (params.length < 2 || !"prod".equalsIgnoreCase(params[1])) {
            return List.of("PPQ adopts a party only as a companion run: !ppqtest party prod [skipchests] [seed].");
        }
        if (operator.getMapId() != AgentPpqDefinition.RECRUIT_MAP) {
            return List.of("Stand at the Pirate PQ entrance (251010404) first.");
        }
        Run existing = RUNS.get(operator.getId());
        if (existing != null && existing.flow == Flow.ADOPTED && finished(existing)) {
            RUNS.remove(operator.getId(), existing);
        }
        if (RUNS.containsKey(operator.getId())) {
            return List.of("Stop the current PPQ test before adopting another party.");
        }
        AgentPartySnapshot snapshot = AgentPartyGatewayRuntime.party().snapshot(operator);
        if (snapshot == null) {
            return List.of("Create the party first: you and three to five companions.");
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
        boolean skipChests = parseSkip(params);
        long seed = parseSeedAfter(params, 2, nowMs);
        AgentPpqAdmissionService.AdmissionResult result =
                AgentPpqAdmissionService.admit(operator, operator, online, skipChests, seed, nowMs);
        if (!result.success()) {
            return List.of("Could not adopt the party: " + result.message());
        }
        result.session().setMemberCoordinationChatEnabled(true);
        Run run = new Run(operator, result.engagement(), seed, skipChests, Flow.ADOPTED);
        run.leaderId = operator.getId();
        run.session = result.session();
        RUNS.put(operator.getId(), run);
        return List.of("Adopted as a companion run. Talk to Guon within 30 seconds.");
    }

    /** An adopted run with nothing left for a stop to do: its session ended and its engagement is gone or closed. */
    private static boolean finished(Run run) {
        if (run.session == null || !run.session.terminal()) return false;
        AgentPartyQuestEngagement engagement = run.engagement;
        if (AgentPartyQuestEngagementRegistry.byId(engagement.engagementId()) != engagement) return true;
        AgentPartyQuestEngagement.State state = engagement.state();
        return state == AgentPartyQuestEngagement.State.FAILED || state == AgentPartyQuestEngagement.State.CLOSED;
    }

    /**
     * Stops an adopted run as the companion run it is (EPQ's stopAdopted): a live session is released (the party is
     * kept); without one, an engagement still registered is recovered, so its companions go back to their owner.
     * Never a test close, a party leave or a logout.
     */
    private static List<String> stopAdopted(Run run, String reason, long nowMs) {
        if (AgentPpqSessionRegistry.forOperator(run.operator.getId()) == run.session) {
            AgentPpqTerminationService.release(run.session, reason, nowMs, true);
        } else if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
            run.engagement.beginRecovery(reason, nowMs);
            AgentPartyQuestLifecycleRuntime.recover(run.engagement, nowMs);
        }
        return List.of("PPQ test stopped safely.");
    }

    /**
     * "checkpoint <medals|deck1|deck2|doors|boss>": an adopted run already inside PPQ goes forward to that stage. The
     * run is read under its session's lock; every change to the event and the members' maps is made after it: the
     * stage counter one short of the target (curStage) and the medal waves done (stage2 = 3) for every target after
     * the medals, the cleared maps emptied, the boss spawned as davy_next4.js does for 'boss', then every member moved
     * inside the instance to the target map's first portal (never a !warp), so changedMapInside restarts the stock
     * timer for the stage.
     */
    private static List<String> checkpoint(Character operator, String[] params, long nowMs) {
        Checkpoint target = params.length < 2 ? null : Checkpoint.of(params[1]);
        if (target == null) return List.of("Syntax: !ppqtest checkpoint <medals|deck1|deck2|doors|boss>");
        Run run = RUNS.get(operator.getId());
        if (run == null || run.flow != Flow.ADOPTED) {
            return List.of("Checkpoints apply only to a party prod companion run.");
        }
        AgentPpqSession session = run.session;
        EventInstanceManager event;
        List<Character> members = new ArrayList<>();
        synchronized (session) {
            event = session.eventInstance();
            if (event == null || session.terminal() || session.terminationStarted()
                    || operator.getEventInstance() != event) {
                return List.of("Enter PPQ through Guon before a checkpoint.");
            }
            for (AgentPpqMemberState member : session.members()) {
                Character character = online(member.characterId());
                if (character == null || character.getEventInstance() != event) {
                    return List.of("Every member must be inside the PPQ instance first.");
                }
                members.add(character);
            }
        }
        prepare(event, target);
        MapleMap map = event.getMapInstance(target.mapId);
        var portal = map.getPortal(0);
        Point spawn = portal == null ? new Point(0, 0) : portal.getPosition();
        for (Character member : members) {
            AgentMapGatewayRuntime.map().changeMapNear(member, map, spawn);
        }
        session.markProgress(nowMs);
        log.info("PPQ checkpoint: session={} stage={} by={}", session.sessionId(),
                target.name().toLowerCase(java.util.Locale.ROOT), operator.getId());
        return List.of("PPQ checkpoint: everyone is at the " + target.name().toLowerCase(java.util.Locale.ROOT)
                + ". Earlier stages are skipped for this run only.");
    }

    /** The event state the stock stages before {@code target} would have left. */
    static void prepare(EventInstanceManager event, Checkpoint target) {
        event.setIntProperty("curStage", target.curStageBefore);
        if (target != Checkpoint.MEDALS) event.setProperty("stage2", "3");
        for (int cleared : target.clearedMaps) {
            MapleMap map = event.getMapInstance(cleared);
            if (map != null) map.killAllMonsters();
        }
        if (target == Checkpoint.BOSS && event.getProperty("spawnedBoss") == null) {
            int chests = parse(event.getProperty("openedChests"));
            Monster boss = LifeFactory.getMonster(chests == 0 ? 9_300_119 : chests == 1 ? 9_300_105 : 9_300_106);
            boss.changeDifficulty(parse(event.getProperty("level")), true);
            event.getMapInstance(AgentPpqDefinition.BOSS_MAP).spawnMonsterOnGroundBelow(boss, new Point(777, 140));
            event.setProperty("spawnedBoss", "true");
        }
    }

    private static int parse(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** The stages a checkpoint can skip to: the target map, curStage one short of it, the maps cleared before it. */
    enum Checkpoint {
        MEDALS(AgentPpqDefinition.MEDAL_MAP, 1, AgentPpqDefinition.ENTRY_MAP),
        DECK1(AgentPpqDefinition.DECK_ONE_MAP, 2, AgentPpqDefinition.ENTRY_MAP, AgentPpqDefinition.MEDAL_MAP),
        DECK2(AgentPpqDefinition.DECK_TWO_MAP, 3, AgentPpqDefinition.ENTRY_MAP, AgentPpqDefinition.MEDAL_MAP,
                AgentPpqDefinition.DECK_ONE_MAP),
        DOORS(AgentPpqDefinition.DOOR_MAP, 4, AgentPpqDefinition.ENTRY_MAP, AgentPpqDefinition.MEDAL_MAP,
                AgentPpqDefinition.DECK_ONE_MAP, AgentPpqDefinition.DECK_TWO_MAP),
        BOSS(AgentPpqDefinition.BOSS_MAP, 5, AgentPpqDefinition.ENTRY_MAP, AgentPpqDefinition.MEDAL_MAP,
                AgentPpqDefinition.DECK_ONE_MAP, AgentPpqDefinition.DECK_TWO_MAP, AgentPpqDefinition.DOOR_MAP);

        final int mapId;
        final int curStageBefore;
        final int[] clearedMaps;

        Checkpoint(int mapId, int curStageBefore, int... clearedMaps) {
            this.mapId = mapId;
            this.curStageBefore = curStageBefore;
            this.clearedMaps = clearedMaps;
        }

        static Checkpoint of(String name) {
            for (Checkpoint checkpoint : values()) {
                if (checkpoint.name().equalsIgnoreCase(name)) return checkpoint;
            }
            return null;
        }
    }

    private static List<String> stop(Character operator, String reason, long nowMs) {
        Run run = RUNS.remove(operator.getId());
        if (run == null) return List.of("No PPQ test is active.");
        if (run.flow == Flow.ADOPTED) return stopAdopted(run, reason, nowMs);
        if (run.session != null) AgentPpqTerminationService.release(run.session, reason, nowMs, true);
        if (run.lobby != null) AgentPartyQuestLobbyRuntime.unregister(run.lobby.lobbyId(), nowMs);
        if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
            AgentPartyQuestLifecycleRuntime.closeTest(run.engagement, nowMs);
        }
        run.agents.forEach(AgentPpqTestService::disconnect);
        if (AgentPartyGatewayRuntime.party().hasParty(run.operator)) {
            AgentPartyGatewayRuntime.party().leaveCurrentParty(run.operator);
        }
        return List.of("PPQ test stopped safely.");
    }
    private static void fail(Run run, String reason) {
        if (run == null || !RUNS.remove(run.operator.getId(), run)) return;
        log.warn("PPQ test run failed: {}", reason);
        long now = System.currentTimeMillis();
        if (run.session != null) AgentPpqTerminationService.release(run.session, reason, now, true);
        if (run.lobby != null) AgentPartyQuestLobbyRuntime.unregister(run.lobby.lobbyId(), now);
        if (AgentPartyQuestEngagementRegistry.byId(run.engagement.engagementId()) != null) {
            AgentPartyQuestLifecycleRuntime.closeTest(run.engagement, now);
        }
        run.agents.forEach(AgentPpqTestService::disconnect);
        if (AgentPartyGatewayRuntime.party().hasParty(run.operator)) {
            AgentPartyGatewayRuntime.party().leaveCurrentParty(run.operator);
        }
        run.operator.dropMessage(6, "PPQ stopped safely: " + reason);
    }
    private static boolean parseSkip(String[] params) {
        for (int i = 1; i < params.length; i++) if ("skipchests".equalsIgnoreCase(params[i])
                || "skip".equalsIgnoreCase(params[i])) return true;
        return false;
    }
    /** The first number from {@code from} on, skipping the chest flag; {@code fallback} without one. */
    private static long parseSeedAfter(String[] params, int from, long fallback) {
        for (int i = from; i < params.length; i++) {
            if ("skipchests".equalsIgnoreCase(params[i]) || "skip".equalsIgnoreCase(params[i])) continue;
            return Long.parseLong(params[i]);
        }
        return fallback;
    }

    private static long parseSeed(String[] params, long fallback) {
        return parseSeedAfter(params, 1, fallback);
    }
    private static void disconnect(int id) {
        Character agent = online(id); AgentRuntimeCleanupService.removeAgentByCharacterId(id);
        if (agent != null) AgentCharacterGatewayRuntime.characters().disconnect(agent, false, false);
    }
    private static Character online(int id) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(id);
        Character agent = entry == null ? null : server.agents.integration.AgentRuntimeIdentityRuntime.bot(entry);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }
    private static List<String> help() {
        return List.of("!ppqtest party prod [skipchests] [seed] (beside Guon: adopt your party of four to six as a"
                        + " companion run)",
                "!ppqtest checkpoint <medals|deck1|deck2|doors|boss> (party prod runs inside PPQ)",
                "!ppqtest start [skipchests] [seed]",
                "!ppqtest humanleader [skipchests] [seed]",
                "!ppqtest humanmember [skipchests] [seed]",
                "!ppqtest status | pause | resume | stop");
    }
    private enum Flow { AGENTS_ONLY, HUMAN_LEADER, HUMAN_MEMBER, ADOPTED }
    private static final class Run {
        final Object lock = new Object(); final Character operator;
        final AgentPartyQuestEngagement engagement; final long seed; final boolean skipChests; final Flow flow;
        final Set<Integer> agents = new LinkedHashSet<>();
        volatile int leaderId; volatile AgentPpqSession session;
        volatile AgentPartyQuestLobbySession lobby;
        Run(Character operator, AgentPartyQuestEngagement engagement, long seed, boolean skipChests, Flow flow) {
            this.operator = operator; this.engagement = engagement; this.seed = seed;
            this.skipChests = skipChests; this.flow = flow;
        }
    }
}
