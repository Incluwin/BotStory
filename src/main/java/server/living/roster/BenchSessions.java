package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 2/3 of the bench (plan 11.4): a bounded, rotating set of benched TRAIN companions are
 * materialized at their camp as self-directed grinders. The Agent simulation policy already
 * runs them abstractly when no player shares the map and at full fidelity when someone walks
 * in (the daycare visit). While a companion is live the offline ledger skips it, so nothing
 * is credited twice.
 */
public final class BenchSessions {
    private static final Logger log = LoggerFactory.getLogger(BenchSessions.class);

    private static final class Session {
        final String personaId;
        final int ownerId;
        final int characterId;
        final long startedAtMs;

        Session(String personaId, int ownerId, int characterId, long startedAtMs) {
            this.personaId = personaId;
            this.ownerId = ownerId;
            this.characterId = characterId;
            this.startedAtMs = startedAtMs;
        }
    }

    private final LivingWorld world;
    private final Map<String, Session> live = new ConcurrentHashMap<>();
    private final Map<String, Long> lastLiveAtMs = new ConcurrentHashMap<>();

    public BenchSessions(LivingWorld world) {
        this.world = world;
    }

    public boolean live(String personaId) {
        return live.containsKey(personaId);
    }

    public int liveCount() {
        return live.size();
    }

    public void tick() {
        long now = System.currentTimeMillis();
        int max = world.config().BENCH_LIVE_MAX;
        for (Session session : new ArrayList<>(live.values())) {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(session.characterId);
            Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
            RosterEntry roster = world.store().rosterEntry(session.ownerId, session.personaId);
            if (agent == null || roster == null || roster.state() != RosterState.BENCHED || roster.assignment() != Assignment.TRAIN) {
                end(session, "no longer benched for training");
                continue;
            }
            boolean watched = agent.getMap() != null && ResidentRuntime.humansIn(agent.getMap()) > 0;
            if (!watched && now - session.startedAtMs > world.config().BENCH_LIVE_SLICE_MS) {
                end(session, "slice elapsed");
                continue;
            }
            int ownerLevel = world.store().characterLevel(session.ownerId);
            Persona persona = world.content().persona(session.personaId);
            int paceGap = persona != null && persona.paceGap() >= 0 ? persona.paceGap() : world.config().PACE_GAP;
            if (ownerLevel > 0 && agent.getLevel() >= ownerLevel - paceGap) {
                end(session, "reached pace target");
            }
        }
        if (max <= 0 || live.size() >= max) {
            return;
        }
        List<RosterEntry> candidates = new ArrayList<>();
        for (RosterEntry entry : world.store().allBenched()) {
            if (entry.state() == RosterState.BENCHED && entry.assignment() == Assignment.TRAIN
                    && !live.containsKey(entry.personaId()) && world.roster().companion(entry) == null
                    && world.residents().agentOf(entry.personaId()) == null) {
                candidates.add(entry);
            }
        }
        candidates.sort(Comparator.comparingLong(entry -> lastLiveAtMs.getOrDefault(entry.personaId(), 0L)));
        for (RosterEntry entry : candidates) {
            if (live.size() >= max) {
                break;
            }
            start(entry, now);
        }
    }

    private void start(RosterEntry entry, long now) {
        Persona persona = world.content().persona(entry.personaId());
        if (persona == null) {
            return;
        }
        int ownerLevel = world.store().characterLevel(entry.characterId());
        int characterId = world.residents().characterIdOf(persona.id());
        if (characterId == 0 || ownerLevel <= 0) {
            return;
        }
        int botLevel = world.store().characterLevel(characterId);
        int paceGap = persona.paceGap() >= 0 ? persona.paceGap() : world.config().PACE_GAP;
        if (botLevel >= ownerLevel - paceGap) {
            lastLiveAtMs.put(persona.id(), now);
            return;
        }
        int campMapId = entry.campMapId() == 0 ? ResidentSpots.townMapId(persona.homeTown()) : entry.campMapId();
        MapleMap map = world.residents().map(campMapId);
        if (map == null || map.isTown()) {
            return; // towns have nothing to fight; the ledger covers those camps
        }
        try {
            Character agent = CosmicAgentOfflineLoader.loadOfflineAgent(characterId, world.config().WORLD, world.config().CHANNEL,
                    map, ResidentSpots.fallback(map, 0));
            if (agent == null) {
                return;
            }
            world.bench().applyAccrued(entry, agent);
            AgentInteractionRuntime.registerSelfDirectedAgent(agent);
            live.put(persona.id(), new Session(persona.id(), entry.characterId(), characterId, now));
            lastLiveAtMs.put(persona.id(), now);
            world.store().event(entry.characterId(), persona.id(), "bench.live", "training live at map " + campMapId);
            log.info("bench session: {} training live at {} for {}", persona.name(), campMapId, entry.characterId());
        } catch (Exception failure) {
            log.warn("bench session for {} failed", persona.id(), failure);
        }
    }

    private void end(Session session, String reason) {
        live.remove(session.personaId);
        lastLiveAtMs.put(session.personaId, System.currentTimeMillis());
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(session.characterId);
        Character agent = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        AgentRuntimeCleanupService.removeAgentByCharacterId(session.characterId);
        if (agent != null && agent.getClient() != null) {
            agent.saveCharToDB();
            agent.getClient().forceDisconnect();
        }
        world.store().event(session.ownerId, session.personaId, "bench.live-end", reason);
    }

    /** Ends a live session before the character is needed elsewhere (call, release, shutdown). */
    public void endFor(String personaId, String reason) {
        Session session = live.get(personaId);
        if (session != null) {
            end(session, reason);
        }
    }

    public void endAll(String reason) {
        for (Session session : new ArrayList<>(live.values())) {
            end(session, reason);
        }
    }
}
