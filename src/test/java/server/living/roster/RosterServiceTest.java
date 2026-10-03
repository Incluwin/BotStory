package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * The roster around a companion's own restock: a companion gone to the store when its owner enters a run waits
 * outside it on HOLD without being stopped or moved (that would cut its trip short), at the spot beside Lakelis it
 * walks back to once the trip ends; rejoining while still on the trip, it follows once the trip is over instead of at
 * once (a follow now cancels its shop visit); and an owner coming into a party boss's gate hands the no-shop line to
 * companion-ops without touching the store.
 */
class RosterServiceTest {
    private static final int KERNING_SHOP = 103_000_002;
    private static final Point BY_LAKELIS = new Point(1440, 120);

    private final LifecycleHarness game = new LifecycleHarness();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private Persona sin;

    @BeforeEach
    void silentSinIsOutWithAnnInKerning() {
        game.moveAnn(KERNING, 1500, 120);
        sin = game.companion("silentsin", "SilentSin", 902, RosterState.ACTIVE, LITH);
        game.outWithAnn(sin);
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
        game.roster();
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aCompanionGoneToTheStoreIsHeldWhereItIsAndWalksBackBesideLakelisAfterItsTrip() {
        game.bot(sin).changeMap(game.map(KERNING_SHOP), new Point(-297, -79));
        onTrip();
        game.calls.clear();

        assertTrue(game.roster().hold(game.ann, sin, KERNING, BY_LAKELIS, HoldNote.whisper(null)).ok());

        assertEquals(List.of("leave SilentSin"), game.calls, "never stopped, warped or walked mid-trip");
        assertEquals(RosterState.HOLD, game.row(sin).state());
        assertEquals(KERNING, game.row(sin).holdMapId(), "it waits on the leader's map");
        assertEquals(new RosterService.HoldSpot(KERNING, BY_LAKELIS), game.roster().holdSpot("silentsin"));
        assertEquals(KERNING_SHOP, game.botMap(sin).getId());

        CompanionRestock restock = new CompanionRestock(game.world);
        game.roster().settings().bind(game.bot(sin), ANN, "silentsin");
        restock.afterRestock(902);
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("walk")), "still in the shop");

        game.bot(sin).changeMap(game.map(KERNING), new Point(-855, 154)); // walked back into town
        game.calls.clear();
        restock.afterRestock(902);
        assertEquals(List.of("walk SilentSin to 1440,120"), game.calls);
    }

    @Test
    void aHeldCompanionStillOnItsTripRejoinsByFollowingOnceTheTripIsOver() {
        game.bot(sin).changeMap(game.map(KERNING_SHOP), new Point(-297, -79));
        onTrip();
        assertTrue(game.roster().hold(game.ann, sin, KERNING, BY_LAKELIS, HoldNote.whisper(null)).ok());
        AgentSupplyProcurementState execution = entries.get(902).capabilityStates()
                .require(AgentSupplyProcurementState.STATE_KEY);
        execution.rememberForegroundMode(false, false); // held off follow when the trip began
        game.calls.clear();

        game.moveAnn(KERNING, 1200, 120); // out of the run
        game.roster().ownerEnteredMap(game.ann, game.map(KERNING));

        assertEquals(RosterState.ACTIVE, game.row(sin).state());
        assertTrue(game.calls.contains("join SilentSin"), game.calls.toString());
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("follow") || call.startsWith("walk")
                || call.startsWith("stop")), "no follow now: it would cancel the shop visit " + game.calls);
        assertTrue(execution.isActive());
        assertTrue(execution.resumeFollow(), "the trip ends by following Ann");
        assertFalse(game.roster().keepsOffFollow("silentsin"));
    }

    @Test
    void aHeldCompanionWhoseTripEndsAsItRejoinsFollowsAsAnyHeldCompanionDoes() {
        game.bot(sin).changeMap(game.map(KERNING_SHOP), new Point(-297, -79));
        onTrip();
        assertTrue(game.roster().hold(game.ann, sin, KERNING, BY_LAKELIS, HoldNote.whisper(null)).ok());
        game.bot(sin).changeMap(game.map(KERNING), new Point(1440, 120)); // back beside Lakelis
        game.calls.clear();

        game.moveAnn(KERNING, 1200, 120);
        try (org.mockito.MockedStatic<AgentSupplyReadiness> readiness = org.mockito.Mockito.mockStatic(
                AgentSupplyReadiness.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            // the Agent tick finished the trip between the rejoin's look and its follow-after-the-trip
            readiness.when(() -> AgentSupplyReadiness.resumeFollowAfterTrip(any())).thenReturn(false);
            game.roster().ownerEnteredMap(game.ann, game.map(KERNING));
        }

        assertEquals(RosterState.ACTIVE, game.row(sin).state());
        assertTrue(game.calls.contains("follow SilentSin"), "it follows Ann now, not never: " + game.calls);
        assertFalse(game.roster().keepsOffFollow("silentsin"));
    }

    @Test
    void withTheKillSwitchOffAHoldStopsAndMovesItAsBefore() {
        game.config.COMPANION_RESTOCK_ENABLED = false;
        onTrip();
        game.calls.clear();

        assertTrue(game.roster().hold(game.ann, sin, KERNING, BY_LAKELIS, HoldNote.whisper(null)).ok());

        assertEquals(List.of("stop SilentSin", "leave SilentSin", "walk SilentSin to 1440,120"), game.calls);
    }

    @Test
    void anOwnerComingIntoABossGateHandsTheNoShopLineToCompanionOpsWithoutTheStore() {
        List<String> submitted = new ArrayList<>();
        CompanionExecutor ops = mock(CompanionExecutor.class);
        when(ops.submit(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            submitted.add(inv.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        when(game.world.executor()).thenReturn(ops);
        MapleMap gate = game.map(220_080_000);
        clearInvocations(game.store.store);

        game.roster().ownerEnteredMap(game.ann, gate);

        assertEquals(List.of("companion.restock.boss_gate " + ANN), submitted);
        verifyNoInteractions(game.store.store);

        submitted.clear();
        game.roster().ownerEnteredMap(game.ann, game.map(KERNING));
        assertFalse(submitted.contains("companion.restock.boss_gate " + ANN), "not a boss gate");
    }

    /** SilentSin set off on a readiness trip to the Kerning shop. */
    private void onTrip() {
        Character bot = game.bot(sin);
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY).start(
                "readiness:HP_POTION:1000", "maintenance:resupply:readiness:HP_POTION:1000",
                AgentResourceCategory.HP_POTION, KERNING_SHOP, 1_051_002, KERNING,
                AgentSupplyProcurementState.Phase.SHOPPING);
    }
}
