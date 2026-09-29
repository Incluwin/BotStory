package server.living.roster;

import client.Character;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeEntryRequest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The REST form of the bench (docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench", decision 12 a): cheap town presence. A
 * resting companion loads in its camp's town (the camp itself when it is a town, else the town the camp returns to),
 * or its home town, whichever has TownLife, under the BENCH_TOWN pool. It is registered director-idle (never a grinder)
 * and given a TownLife visit lease, so it idles, strolls and sits like the town's residents, at BACKGROUND_ABSTRACT
 * while nobody is there to see it; with a player in town it says one of its own idle or small-talk lines now and then.
 * It never fights: a town has nothing to fight, and a resting companion found outside its town ends its slice.
 */
public final class BenchRestForm implements BenchDutyDispatcher.Form {
    /** At most one line this often, and only with a player in town to hear it. */
    static final long CHAT_EVERY_MS = 5 * 60_000L;
    private static final long GRACEFUL_EXIT_MS = 30_000L;

    private final LivingWorld world;
    /** persona id -> when it last spoke (or arrived), and how many lines it has said this slice. */
    private final Map<String, long[]> chat = new ConcurrentHashMap<>();

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
        if (entry == null) {
            return false;
        }
        townLife(slice, entry, agent, nowMs);
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
            townLife(slice, entry, agent, nowMs); // its visit ran out while the slice runs on
        }
        speak(slice, agent, map, nowMs);
        return null;
    }

    @Override
    public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
        chat.remove(slice.personaId()); // the hand-back ends its TownLife visit
    }

    private static boolean hasTownLife(int mapId) {
        return mapId > 0 && ResidentSpots.profile(mapId).isPresent();
    }

    /** A TownLife visit lease in its town for one town slice (if it cannot start, it idles where it stands until the next look). */
    private void townLife(BenchDutyDispatcher.Slice slice, AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentTownLifeVisitRequest visit = new AgentTownLifeVisitRequest(slice.mapId(),
                AgentTownLifeVisitRequest.Purpose.LEISURE, "bench rest", 0L);
        AgentTownLifeVisitLeaseRequest lease = new AgentTownLifeVisitLeaseRequest(
                AgentTownLifeEntryRequest.external("bench-rest:" + slice.personaId() + ':' + nowMs, "living-bench", visit),
                AgentTownLifeAdmissionMode.AMBIENT, nowMs + world.config().BENCH_TOWN_SLICE_MS, GRACEFUL_EXIT_MS,
                "bench rest slice elapsed");
        AgentTownLifeVisitLeaseRuntime.start(entry, agent, lease, nowMs, slice.characterId());
    }

    /** One of its idle or small-talk lines, when a player is in town and it has been quiet for {@link #CHAT_EVERY_MS}. */
    private void speak(BenchDutyDispatcher.Slice slice, Character agent, MapleMap map, long nowMs) {
        long[] said = chat.computeIfAbsent(slice.personaId(), persona -> new long[] {nowMs, 0L});
        Persona persona = world.content().persona(slice.personaId());
        if (persona == null || nowMs - said[0] < CHAT_EVERY_MS || ResidentRuntime.humansIn(map) == 0) {
            return;
        }
        List<String> lines = new ArrayList<>(persona.lines("smallTalk"));
        lines.addAll(persona.lines("idle"));
        if (lines.isEmpty()) {
            return;
        }
        said[0] = nowMs;
        AgentDialogueTransportRuntime.sayMapNow(agent, lines.get((int) (said[1]++ % lines.size())));
    }
}
