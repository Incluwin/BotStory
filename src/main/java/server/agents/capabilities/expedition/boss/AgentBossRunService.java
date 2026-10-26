package server.agents.capabilities.expedition.boss;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.auth.AgentClaimGuard;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.expeditions.Expedition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opens boss runs for claimed Agents and keeps their monitors going. An enlister adopts only Agents it may control
 * and the host claims (the host's own companions, never an unclaimed or GM-spawned Agent), each free of every other
 * boss run and party quest. One run serves one expedition (or, P12 G7, one party-instance event); a second enlister
 * joins it.
 */
public final class AgentBossRunService {
    private static final Logger log = LoggerFactory.getLogger(AgentBossRunService.class);
    private static final Object ENLIST = new Object();
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();

    /** The run's id (0 when none holds the expedition) and the Agents it took and turned away, in the given order. */
    public record Enlisted(long sessionId, List<Integer> accepted, List<Integer> refused) {
        public Enlisted {
            accepted = List.copyOf(accepted);
            refused = List.copyOf(refused);
        }
    }

    private AgentBossRunService() {
    }

    /** Enlists {@code agentIds} as followers for {@code expedition} on behalf of {@code human}; any thread. */
    public static Enlisted enlist(Character human, AgentBossRunDefinition definition, Expedition expedition,
                                  List<Integer> agentIds, long nowMs) {
        return enlist(human, definition, expedition, agentIds, nowMs, AgentBossRunMember.Kind.FOLLOWER);
    }

    /**
     * Enlists {@code agentIds} as members of {@code kind} for {@code expedition} on behalf of {@code human}, who stays
     * their anchor; any thread.
     */
    public static Enlisted enlist(Character human, AgentBossRunDefinition definition, Expedition expedition,
                                  List<Integer> agentIds, long nowMs, AgentBossRunMember.Kind kind) {
        return enlist(AgentBossRunPorts.LIVE, human, definition, expedition, agentIds, nowMs, kind);
    }

    static Enlisted enlist(AgentBossRunPorts ports, Character human, AgentBossRunDefinition definition,
                           Expedition expedition, List<Integer> agentIds, long nowMs) {
        return enlist(ports, human, definition, expedition, agentIds, nowMs, AgentBossRunMember.Kind.FOLLOWER);
    }

    static Enlisted enlist(AgentBossRunPorts ports, Character human, AgentBossRunDefinition definition,
                           Expedition expedition, List<Integer> agentIds, long nowMs, AgentBossRunMember.Kind kind) {
        List<Integer> ids = agentIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(agentIds));
        if (human == null || definition == null || expedition == null || !expedition.isRegistering()) {
            return new Enlisted(0L, List.of(), ids);
        }
        List<Integer> accepted = new ArrayList<>();
        List<Integer> refused = new ArrayList<>();
        synchronized (ENLIST) {
            List<Integer> candidates = new ArrayList<>();
            for (int agentId : ids) {
                if (adoptable(human, agentId)) {
                    candidates.add(agentId);
                } else {
                    refused.add(agentId);
                }
            }
            AgentBossRunSession session = AgentBossRunRegistry.forExpedition(expedition);
            if (session != null && session.citizenRun()) {
                // a citizen expedition's run is the population's: a player may sign up on its list, their companions not
                refused.addAll(candidates);
                log.info("Boss run enlist refused: session={} boss={} enlister={} (a citizen expedition)",
                        session.sessionId(), definition.key(), human.getId());
                return new Enlisted(session.sessionId(), accepted, refused);
            }
            if (candidates.isEmpty()) {
                return new Enlisted(session == null ? 0L : session.sessionId(), accepted, refused);
            }
            if (session == null) {
                session = new AgentBossRunSession(NEXT_SESSION_ID.incrementAndGet(), definition, expedition,
                        definition.fight().get(), nowMs);
                AgentBossRunRegistry.put(session);
                ports.prepareScripts(definition.entryNpcId());
                AgentBossRunSession opened = session;
                ports.schedule(() -> monitor(ports, opened), monitorMs());
                log.info("Boss run opened: session={} boss={} enlister={}", session.sessionId(), definition.key(),
                        human.getId());
            }
            for (int agentId : candidates) {
                if (session.addMember(agentId, human.getId(), nowMs, kind) != null) {
                    accepted.add(agentId);
                } else {
                    refused.add(agentId);
                }
            }
            log.info("Boss run enlist: session={} boss={} enlister={} accepted={} refused={}", session.sessionId(),
                    definition.key(), human.getId(), accepted, refused);
            return new Enlisted(session.sessionId(), accepted, refused);
        }
    }

    /**
     * How a citizen expedition opened: its run and expedition, the citizens it took and turned away, in the given order;
     * on failure a run id of 0 and why, in a few words.
     */
    public record CitizenOpened(long sessionId, Expedition expedition, List<Integer> accepted, List<Integer> refused,
                                String problem) {
        public CitizenOpened {
            accepted = List.copyOf(accepted);
            refused = List.copyOf(refused);
            problem = problem == null ? "" : problem;
        }

        public boolean opened() {
            return sessionId > 0L;
        }

        static CitizenOpened failed(List<Integer> ids, String problem) {
            return new CitizenOpened(0L, null, List.of(), ids, problem);
        }
    }

    /**
     * A citizen expedition (docs/CITIZEN_EXPEDITIONS_PLAN.md): citizen {@code leader}, standing in the entry NPC's room,
     * makes {@code boss}'s expedition with her own window, and the run of it opens with {@code citizenIds} (the leader
     * first) as members that lead themselves, anchored to the leader. The run signs the others up through her window
     * as it does bench allies, and its leader starts it once the population asks ({@link #startCitizens}). The
     * population hosts it (AgentBossRunHost.citizens). A citizen is taken only while it is loaded, no companion, and in
     * no other boss run or party quest. On the population's thread.
     */
    public static CitizenOpened openCitizenExpedition(Character leader, AgentBossCitizenRuns.Boss boss,
                                                      List<Integer> citizenIds, boolean humanOptional, long nowMs) {
        return openCitizenExpedition(AgentBossRunPorts.LIVE, leader, boss, citizenIds, humanOptional, nowMs);
    }

    static CitizenOpened openCitizenExpedition(AgentBossRunPorts ports, Character leader, AgentBossCitizenRuns.Boss boss,
                                               List<Integer> citizenIds, boolean humanOptional, long nowMs) {
        List<Integer> ids = citizenIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(citizenIds));
        if (leader == null || boss == null || !ids.contains(leader.getId())) {
            return CitizenOpened.failed(ids, "no leader");
        }
        AgentBossRunDefinition definition = boss.definition();
        if (leader.getMapId() != definition.recruitMapId()) {
            return CitizenOpened.failed(ids, "the leader is not at the entry NPC");
        }
        synchronized (ENLIST) {
            if (!adoptableCitizen(ports, leader.getId())) {
                return CitizenOpened.failed(ids, "the leader is busy");
            }
            if (ports.expedition(leader, definition.type()) != null) {
                return CitizenOpened.failed(ids, "an expedition is open");
            }
            boolean talked = false;
            try {
                talked = ports.runNpc(leader, definition.entryNpcId(), boss.createChoices());
            } catch (RuntimeException failure) {
                log.warn("Citizen expedition create conversation failed: boss={} leader={}", definition.key(),
                        leader.getId(), failure);
            }
            Expedition expedition = ports.expedition(leader, definition.type());
            if (expedition == null || !expedition.isLeader(leader) || !expedition.isRegistering()) {
                log.info("Citizen expedition not made: boss={} leader={} talked={}", definition.key(), leader.getId(),
                        talked);
                return CitizenOpened.failed(ids, "the entry NPC made no expedition");
            }
            AgentBossRunSession session = new AgentBossRunSession(NEXT_SESSION_ID.incrementAndGet(), definition,
                    expedition, boss.fight().get(), nowMs, leader.getId(), boss.startChoices(), humanOptional);
            AgentBossRunRegistry.put(session);
            ports.prepareScripts(definition.entryNpcId());
            ports.schedule(() -> monitor(ports, session), monitorMs());
            List<Integer> accepted = new ArrayList<>();
            List<Integer> refused = new ArrayList<>();
            for (int agentId : ids) {
                if (adoptableCitizen(ports, agentId) && session.addMember(agentId, leader.getId(), nowMs,
                        AgentBossRunMember.Kind.SELF_LED) != null) {
                    accepted.add(agentId);
                } else {
                    refused.add(agentId);
                }
            }
            log.info("Boss run opened: session={} boss={} citizen leader={} humanOptional={} accepted={} refused={}",
                    session.sessionId(), definition.key(), leader.getId(), humanOptional, accepted, refused);
            return new CitizenOpened(session.sessionId(), expedition, accepted, refused, "");
        }
    }

    /**
     * The population asks citizen run {@code sessionId} to start: its leader talks the entry NPC's Start on the run's
     * next registering ticks. False when it is gone, no citizen run, or no longer registering. Memory only, any thread.
     */
    public static boolean startCitizens(long sessionId) {
        AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
        return session != null && session.requestStart();
    }

    /** Loaded, no companion, and in no boss run and no party quest. */
    private static boolean adoptableCitizen(AgentBossRunPorts ports, int agentId) {
        return ports.agent(agentId) != null
                && !AgentClaimGuard.isClaimed(agentId)
                && AgentBossRunRegistry.forMember(agentId) == null
                && !AgentPartyQuestRuntime.active(agentId);
    }

    /**
     * P12 G7: admits {@code agentIds}, companions the party's portal just took into {@code event}, as followers of
     * {@code human} in the party-instance run of {@code definition} for that event; the first call for an event opens
     * the run. Each one becomes INSIDE once it stands on the battle map in the event. Any thread.
     */
    public static Enlisted openForEvent(Character human, AgentBossRunDefinition definition, EventInstanceManager event,
                                        List<Integer> agentIds, long nowMs) {
        return openForEvent(AgentBossRunPorts.LIVE, human, definition, event, agentIds, nowMs);
    }

    static Enlisted openForEvent(AgentBossRunPorts ports, Character human, AgentBossRunDefinition definition,
                                 EventInstanceManager event, List<Integer> agentIds, long nowMs) {
        List<Integer> ids = agentIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(agentIds));
        if (human == null || definition == null || !definition.partyInstance() || event == null
                || event.isEventDisposed()) {
            return new Enlisted(0L, List.of(), ids);
        }
        List<Integer> accepted = new ArrayList<>();
        List<Integer> refused = new ArrayList<>();
        synchronized (ENLIST) {
            List<Integer> candidates = new ArrayList<>();
            for (int agentId : ids) {
                if (adoptable(human, agentId)) {
                    candidates.add(agentId);
                } else {
                    refused.add(agentId);
                }
            }
            AgentBossRunSession session = AgentBossRunRegistry.forEvent(event);
            if (candidates.isEmpty() || session != null && session.definition() != definition) {
                refused.addAll(candidates);
                return new Enlisted(session == null ? 0L : session.sessionId(), accepted, refused);
            }
            if (session == null) {
                session = new AgentBossRunSession(NEXT_SESSION_ID.incrementAndGet(), definition, event,
                        definition.fight().get(), nowMs);
                AgentBossRunRegistry.put(session);
                AgentBossRunSession opened = session;
                ports.schedule(() -> monitor(ports, opened), monitorMs());
                log.info("Boss run opened: session={} boss={} enlister={} (party event)", session.sessionId(),
                        definition.key(), human.getId());
            }
            for (int agentId : candidates) {
                if (session.admit(agentId, human.getId(), nowMs, AgentBossRunMember.Kind.FOLLOWER) != null) {
                    accepted.add(agentId);
                } else {
                    refused.add(agentId);
                }
            }
            log.info("Boss run admit: session={} boss={} enlister={} accepted={} refused={}", session.sessionId(),
                    definition.key(), human.getId(), accepted, refused);
            return new Enlisted(session.sessionId(), accepted, refused);
        }
    }

    /**
     * A logout handoff: the INSIDE member {@code agentId} of run {@code sessionId} stays with {@code humanId} (the heir)
     * from now on instead of its enlister, and after the clear leaves when the heir leaves. False when the run is gone
     * or the Agent is no INSIDE member of it. Memory only, any thread; it takes no lock.
     */
    public static boolean handTo(long sessionId, int agentId, int humanId) {
        AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
        return session != null && session.handTo(agentId, humanId);
    }

    /**
     * Takes the WAITING or REGISTERED member {@code agentId} of run {@code sessionId} back before Start: the run's next
     * tick takes it off the entry NPC's list and refuses it WITHDRAWN. False when the run is gone or no longer
     * registering, or the Agent is no WAITING or REGISTERED member of it. Memory only, any thread; it takes no lock.
     */
    public static boolean withdraw(long sessionId, int agentId) {
        AgentBossRunSession session = AgentBossRunRegistry.byId(sessionId);
        return session != null && session.withdraw(agentId);
    }

    /**
     * When a run of {@code expedition} stops waiting for members to sign up: the list's close (the expedition's start
     * time while it registers) less REGISTRATION_MARGIN_MS. Read from the expedition at each call.
     */
    public static long signUpDeadlineMs(Expedition expedition) {
        return expedition.getStartTime() - AgentBossRunSession.registrationMarginMs();
    }

    /** The enlister controls it, the host claims it, and it is in no boss run and no party quest. */
    private static boolean adoptable(Character human, int agentId) {
        return AgentClaimGuard.mayControl(human, agentId)
                && AgentClaimGuard.isClaimed(agentId)
                && AgentBossRunRegistry.forMember(agentId) == null
                && !AgentPartyQuestRuntime.active(agentId);
    }

    /**
     * One tick of the run, then the next one; a failing tick ends the run instead of leaving it open, and the end hands
     * back the members still in it.
     */
    static void monitor(AgentBossRunPorts ports, AgentBossRunSession session) {
        if (session.phase() == AgentBossRunSession.Phase.ENDED) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        // a citizen expedition's run tells the population, every other one the living world
        AgentBossRunHost.Rule host = session.citizenRun() ? AgentBossRunHost.citizens() : AgentBossRunHost.installed();
        try {
            session.tick(ports, host, nowMs);
        } catch (RuntimeException failure) {
            log.warn("Boss run tick failed: session={} boss={}", session.sessionId(), session.definition().key(),
                    failure);
            session.end(ports, host, "error", nowMs);
        }
        if (session.phase() != AgentBossRunSession.Phase.ENDED) {
            ports.schedule(() -> monitor(ports, session), monitorMs());
        }
    }

    static long monitorMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossRunService.MONITOR_MS");
    }
}
