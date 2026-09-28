package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.follow.AgentFollowGuard;
import server.agents.capabilities.follow.AgentFollowMapSyncService;
import server.agents.runtime.AgentModeService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.HENESYS;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.PERION;
import static server.living.roster.LifecycleHarness.portalX;

/**
 * The follow travel guard (docs/BOT_MANAGEMENT_PLAN.md P2b, "Follow guards"): a TRAVELLING, HOLD or AWAY companion its
 * owner leads is never warped by follow and never starts following, whatever its owner does ("follow me", "gather
 * up", a job advance, a resupply, a map change), until the roster's own arrival or rejoin walks it over. Plain
 * Agents and a live bench slice (which leads itself) are not held.
 */
class FollowTravelSuppressionTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private final Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
    private final Persona mira = game.companion("mira", "Mira", 901, RosterState.ACTIVE, LITH);
    private final Persona rae = game.companion("rae", "Rae", 902, RosterState.AWAY, HENESYS);
    private final CompanionFollowGuard guard = new CompanionFollowGuard(game.world);
    private final List<String> sync = new ArrayList<>();

    @BeforeEach
    void install() {
        game.roster();
        AgentFollowGuard.install(guard);
    }

    @AfterEach
    void close() {
        AgentFollowGuard.install(null);
        game.close();
    }

    @Test
    void theOwnerChangingMapTwiceDuringTheEtaNeverWarpsItAndItStillArrivesByPortal() {
        game.roster().call(game.ann, olwen);
        assertTrue(guard.holdsOffFollow(900), "on its way");
        AgentRuntimeEntry runtime = following(olwen); // were anything to put it on follow anyway

        game.moveAnn(PERION, 400, 150);
        game.roster().ownerEnteredMap(game.ann, game.map(PERION), new Point(400, 150));
        assertTrue(syncFollow(runtime));
        game.moveAnn(KERNING, 1300, 90);
        game.roster().ownerEnteredMap(game.ann, game.map(KERNING), new Point(1300, 90));
        assertTrue(syncFollow(runtime));

        assertEquals(List.of("hold", "hold"), sync, "never warped after its owner");
        assertEquals(game.map(LITH), game.botMap(olwen), "still at its camp");

        game.runTimers(); // the ETA ends

        assertEquals(List.of("warp Olwen to " + KERNING + " at " + portalX(KERNING) + ",200", "follow Olwen"),
                game.calls.subList(game.calls.size() - 2, game.calls.size()));
        assertEquals(RosterState.ACTIVE, game.row(olwen).state());
        assertFalse(guard.holdsOffFollow(900), "its arrival lets it follow");
    }

    @Test
    void noFollowCommandStartsATravellerFollowingBeforeItArrives() {
        game.roster().call(game.ann, olwen);
        AgentRuntimeEntry runtime = runtime(olwen);

        assertFalse(AgentModeService.mayStartFollow(runtime), "'follow me', 'gather up', a job advance, a resupply");

        game.runTimers();

        assertTrue(AgentModeService.mayStartFollow(runtime));
    }

    @Test
    void aHoldIsNeverWarpedAfterItsOwnerAndFollowsOnlyOnceItRejoins() {
        game.moveAnn(KERNING, 1500, 120);
        game.outWithAnn(mira);
        game.roster().hold(game.ann, mira, KERNING, null);
        AgentRuntimeEntry runtime = following(mira);

        assertTrue(guard.holdsOffFollow(901));
        assertFalse(AgentModeService.mayStartFollow(runtime));
        game.moveAnn(PERION, 400, 150); // an open map: only the travel guard keeps it where it waits
        assertTrue(syncFollow(runtime));
        assertEquals(List.of("hold"), sync);

        game.moveAnn(KERNING, 1500, 120);
        game.roster().ownerEnteredMap(game.ann, game.map(KERNING)); // back where it waits: it rejoins

        assertEquals(RosterState.ACTIVE, game.row(mira).state());
        assertEquals("follow Mira", game.calls.get(game.calls.size() - 1));
        assertFalse(guard.holdsOffFollow(901));
    }

    @Test
    void anAwayCompanionItsOwnerLeadsIsHeldButABenchSliceLeadingItselfIsNot() {
        game.loaded.add(902); // loaded under Ann: on its way back in from its errand

        assertTrue(guard.holdsOffFollow(902));

        game.selfLed.add(902); // a live bench slice doing the errand leads itself: its own follow is untouched

        assertFalse(guard.holdsOffFollow(902));
    }

    @Test
    void plainAgentsAndActiveCompanionsAreNeverHeld() {
        game.outWithAnn(mira);

        assertFalse(guard.holdsOffFollow(950), "a plain Agent");
        assertFalse(guard.holdsOffFollow(901), "an ACTIVE companion");
    }

    @Test
    void anAwayCompanionCalledOntoAMapWithNoPortalSpawnsBesideItsOwnerFollowing() {
        MapleMap bare = mock(MapleMap.class);
        when(bare.getId()).thenReturn(HENESYS);
        when(bare.getPortals()).thenReturn(List.of());
        game.maps.put(HENESYS, bare);
        game.moveAnn(HENESYS, 500, 200); // where its errand is: no trip
        List<Boolean> heldAtSpawn = new ArrayList<>();
        game.onSpawn = bot -> heldAtSpawn.add(guard.holdsOffFollow(bot.getId()));

        RosterResult result = game.roster().call(game.ann, rae);

        assertTrue(result.ok(), result.toString());
        assertEquals(List.of(false), heldAtSpawn, "it follows from its spawn beside its owner (P1.5)");
        assertEquals(RosterState.ACTIVE, game.row(rae).state());
    }

    private boolean syncFollow(AgentRuntimeEntry runtime) {
        return AgentFollowMapSyncService.syncFollowMap(runtime, runtime.bot(), game.ann,
                new AgentFollowMapSyncService.FollowMapSyncHooks(
                        (map, point) -> point,
                        (entry, agent) -> { },
                        (agent, map, point) -> sync.add("warp " + map.getId()),
                        entry -> { },
                        (entry, agent) -> sync.add("hold")));
    }

    private AgentRuntimeEntry following(Persona persona) {
        AgentRuntimeEntry runtime = runtime(persona);
        AgentModeStateRuntime.setFollowing(runtime, true);
        return runtime;
    }

    private AgentRuntimeEntry runtime(Persona persona) {
        Character bot = game.bot(persona);
        return new AgentRuntimeEntry(bot, game.ann, null);
    }
}
