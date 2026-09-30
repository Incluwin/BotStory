package server.living.roster;

import client.Character;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeEntryRequest;
import server.agents.capabilities.townlife.AgentTownLifeSessionResult;
import server.agents.capabilities.townlife.AgentTownLifeVisitRequest;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRequest;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.RosterEntry;
import server.maps.MapleMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The REST form of the bench (docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench", decision 12 a): cheap town presence. A
 * resting companion loads in its camp's town (the camp itself when it is a town, else the town the camp returns to),
 * or its home town, whichever has TownLife, under the BENCH_TOWN pool, at a portal it walks in from. It is registered
 * director-idle (never a grinder) and given a TownLife visit lease on its own mailbox, so it idles, strolls and sits
 * like the town's residents, at BACKGROUND_ABSTRACT while nobody is there to see it; a lease that does not start keeps
 * the duty in the ledger, and one that cannot be started again ends the slice, so it never stands frozen in town.
 * While a player watches, its lease is renewed before it runs out. With a player in town it says one of its own "rest"
 * lines now and then (its "idle" ones only in its home town), twice a slice at most. It never fights: a town has
 * nothing to fight, and a resting companion found outside its town ends its slice.
 */
public final class BenchRestForm implements BenchDutyDispatcher.Form {
    /** At most one line this often, and only with a player in town to hear it. */
    static final long CHAT_EVERY_MS = 5 * 60_000L;
    /** Lines one slice says at most. */
    static final int LINES_PER_SLICE = 2;
    private static final long GRACEFUL_EXIT_MS = 30_000L;

    private final LivingWorld world;
    /** persona id -> when it last spoke (or arrived), and how many lines it has said this slice. */
    private final Map<String, long[]> chat = new ConcurrentHashMap<>();
    /** persona id -> the TownLife visit lease its slice runs on now (renewed as the same request). */
    private final Map<String, AgentTownLifeVisitLeaseRequest> visits = new ConcurrentHashMap<>();

    public BenchRestForm(LivingWorld world) {
        this.world = world;
    }

    @Override
    public CompanionBudget.Kind pool() {
        return CompanionBudget.Kind.BENCH_TOWN;
    }

    /** The camp's town, else its home town: the first of them with TownLife (0: none, the ledger keeps it). */
    @Override
    public int mapFor(RosterEntry entry, Persona persona, int characterId) {
        int camp = entry.campMapId();
        MapleMap map = camp == 0 ? null : world.residents().map(camp);
        int campTown = map == null ? 0 : map.isTown() ? camp : map.getReturnMapId();
        if (hasTownLife(campTown)) {
            return campTown;
        }
        int home = ResidentSpots.townMapId(persona.homeTown());
        return hasTownLife(home) ? home : 0;
    }

    @Override
    public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
        AgentRuntimeEntry entry = AgentInteractionRuntime.registerDirectorIdleAgent(agent); // no grind, no follow
        if (entry == null || !townLife(slice, entry, agent, nowMs)) {
            return false; // no TownLife visit: the ledger keeps its rest
        }
        chat.put(slice.personaId(), new long[] {nowMs, 0L});
        return true;
    }

    @Override
    public String check(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry roster, long nowMs) {
        MapleMap map = agent.getMap();
        if (map == null || map.getId() != slice.mapId()) {
            return "left town"; // resting is in town; it never goes out to fight
        }
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(slice.characterId());
        if (entry != null && !AgentTownLifeVisitLeaseRuntime.active(entry)) {
            if (!townLife(slice, entry, agent, nowMs)) { // its visit ran out while the slice runs on
                return "no town visit";
            }
        } else if (entry != null && ResidentRuntime.humansIn(map) > 0) {
            renew(slice, entry, agent, nowMs); // watched: its visit goes on past the next look
        }
        speak(slice, agent, map, nowMs);
        return null;
    }

    @Override
    public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
        chat.remove(slice.personaId()); // the hand-back ends its TownLife visit
        visits.remove(slice.personaId());
    }

    private static boolean hasTownLife(int mapId) {
        return mapId > 0 && ResidentSpots.profile(mapId).isPresent();
    }

    /** A TownLife visit lease in its town for one town slice, on its own mailbox; true when it started. */
    private boolean townLife(BenchDutyDispatcher.Slice slice, AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentTownLifeVisitRequest visit = new AgentTownLifeVisitRequest(slice.mapId(),
                AgentTownLifeVisitRequest.Purpose.LEISURE, "bench rest", 0L);
        AgentTownLifeVisitLeaseRequest lease = new AgentTownLifeVisitLeaseRequest(
                AgentTownLifeEntryRequest.external("bench-rest:" + slice.personaId() + ':' + nowMs, "living-bench", visit),
                AgentTownLifeAdmissionMode.AMBIENT, nowMs + world.config().BENCH_TOWN_SLICE_MS, GRACEFUL_EXIT_MS,
                "bench rest slice elapsed");
        if (!started(BenchDutyDispatcher.onAgent(entry,
                runtime -> AgentTownLifeVisitLeaseRuntime.start(runtime, agent, lease, nowMs, slice.characterId())))) {
            return false;
        }
        visits.put(slice.personaId(), lease);
        return true;
    }

    /**
     * Its visit lease, renewed as the same TownLife request when it would run out before the next look: the lease's
     * watcher is cleared and started again with a later exit, so the visit itself goes on without a break.
     */
    private void renew(BenchDutyDispatcher.Slice slice, AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentTownLifeVisitLeaseRequest lease = visits.get(slice.personaId());
        if (lease == null || lease.exitAtMs() > nowMs + BenchDutyDispatcher.TICK_MS) {
            return;
        }
        AgentTownLifeVisitLeaseRequest later = new AgentTownLifeVisitLeaseRequest(lease.entryRequest(), lease.admissionMode(),
                nowMs + world.config().BENCH_TOWN_SLICE_MS, lease.gracefulTimeoutMs(), lease.exitReason());
        if (started(BenchDutyDispatcher.onAgent(entry, runtime -> {
            AgentTownLifeVisitLeaseRuntime.clear(runtime, agent);
            return AgentTownLifeVisitLeaseRuntime.start(runtime, agent, later, nowMs, slice.characterId());
        }))) {
            visits.put(slice.personaId(), later);
        }
    }

    private static boolean started(AgentTownLifeSessionResult result) {
        return result != null && (result.status() == AgentTownLifeSessionResult.Status.STARTED
                || result.status() == AgentTownLifeSessionResult.Status.ALREADY_ACTIVE_SAME_REQUEST);
    }

    /**
     * One of its "rest" lines (its "idle" ones only in its home town, where they were written for), when a player is in
     * town, it has been quiet for {@link #CHAT_EVERY_MS} and it has said fewer than {@link #LINES_PER_SLICE} this slice.
     */
    private void speak(BenchDutyDispatcher.Slice slice, Character agent, MapleMap map, long nowMs) {
        long[] said = chat.computeIfAbsent(slice.personaId(), persona -> new long[] {nowMs, 0L});
        Persona persona = world.content().persona(slice.personaId());
        if (persona == null || said[1] >= LINES_PER_SLICE || nowMs - said[0] < CHAT_EVERY_MS || ResidentRuntime.humansIn(map) == 0) {
            return;
        }
        List<String> lines = persona.lines("rest");
        if (lines.isEmpty() && slice.mapId() == ResidentSpots.townMapId(persona.homeTown())) {
            lines = persona.lines("idle");
        }
        if (lines.isEmpty()) {
            return;
        }
        said[0] = nowMs;
        said[1]++;
        AgentDialogueTransportRuntime.sayMapNow(agent, lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
    }
}
