package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.bond.BondService;
import server.living.bond.ContractService;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.ContractState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Roster deletion cleanup (docs/BOT_MANAGEMENT_PLAN.md, "Roster deletion cleanup", flow 10 step 6, decision 18): a
 * deleted owner character releases every roster row of theirs through the release logic on companion-ops. Loaded
 * companions are unloaded, a live bench slice ends, seats (their own, an errand's, a guest's charged to the owner),
 * leases and commitments go back, each Resident goes home (resident.released) with its bond's cooldown running, and a
 * hired citizen's row goes through the P4 seam.
 */
class DeletionCleanupTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private final BondService bonds = mock(BondService.class);
    private final List<Bond> savedBonds = new ArrayList<>();
    private final Persona hadrik = game.companion("hadrik", "Hadrik", 501, RosterState.ACTIVE, LITH);
    private final Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
    private final Persona vex = game.companion("vex", "Vex", 902, RosterState.AWAY, KERNING);
    private final Persona jinx = game.companion("jinx", "Jinx", 903, RosterState.ACTIVE, KERNING);

    DeletionCleanupTest() {
        when(game.world.bonds()).thenReturn(bonds);
        when(game.world.contracts()).thenReturn(new ContractService(game.world));
        when(bonds.peek(anyInt(), anyString())).thenAnswer(inv -> new Bond(ANN, inv.getArgument(1), BondStage.TRUSTED, 90,
                new LinkedHashSet<>(), ContractState.ACTIVE, 1_000L, 0L, 0, 0L, 600, 0L, 1_000L));
        doAnswer(inv -> savedBonds.add(inv.getArgument(0))).when(bonds).save(any());
        game.store.seed(new RosterEntry(ANN, "hire:777", RosterState.BENCHED, Assignment.TRAIN, 0, 0, LITH, 0L, 0L, 0L,
                1_000L, 1_000L, 1_000L, 0, null));
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void everyRowOfADeletedOwnerIsReleasedAndEverythingItHeldGoesBack() {
        RosterService roster = game.roster(); // after the rows, as at boot: the errand keeps its seat
        game.outWithAnn(hadrik);
        game.outWithAnn(jinx);
        game.commitments.commit("kpq-1", ANN, 903);
        String guest = CompanionSlots.characterKey(4242);
        game.slots.reserve(ANN, guest, CompanionSlots.Kind.GUEST, Long.MAX_VALUE);
        List<Commitments.Ended> ended = new ArrayList<>();
        game.commitments.addListener(ended::add);
        List<String> hires = new ArrayList<>();
        roster.installHiredHandRelease((ownerId, personaId, reason) -> hires.add(ownerId + " " + personaId + " " + reason));
        assertTrue(game.slots.reservation("vex") != null, "the errand held its seat");
        game.annOnline = false; // a character is deleted at character select: nobody sees its companions go

        roster.ownerDeleted(ANN);

        assertEquals(List.of(), game.store.rows.values().stream().toList(), "no row of theirs is left");
        assertTrue(game.calls.contains("remove Hadrik") && game.calls.contains("remove Jinx"), game.calls.toString());
        verify(game.world.benchDuty()).endFor("olwen", "released");
        verify(game.world.benchDuty()).endFor("hire:777", "released");
        assertNull(game.leases.holder(501));
        assertNull(game.leases.holder(903));
        assertNull(game.slots.reservation("vex"));
        assertNull(game.slots.reservation(guest), "a guest charged to them is let go");
        assertFalse(game.commitments.isCommitted(903));
        assertEquals(1, ended.size());
        assertEquals(903, ended.get(0).members().get(0).characterId());
        assertEquals(List.of(ANN + " hadrik resident.released owner deleted", ANN + " olwen resident.released owner deleted",
                ANN + " vex resident.released owner deleted", ANN + " jinx resident.released owner deleted"),
                game.store.events("resident.released"));
        assertEquals(5, game.store.events("roster.release").size());
        assertEquals(List.of(ANN + " hire:777 owner deleted"), hires);
        assertFalse(roster.hasCompanions(game.ann));
        for (String personaId : List.of("hadrik", "olwen", "vex", "jinx", "hire:777")) {
            assertFalse(roster.isOwned(personaId), personaId + " is owned by nobody: a Resident takes up its routine again");
            assertEquals(0, game.store.personaStates.get(personaId).employedBy(), personaId);
        }
    }

    @Test
    void eachResidentsBondEndsWithItsCooldownRunning() {
        RosterService roster = game.roster();
        long before = System.currentTimeMillis();

        roster.ownerDeleted(ANN);

        assertEquals(4, savedBonds.size());
        for (Bond bond : savedBonds) {
            assertEquals(ContractState.ENDED, bond.contractState());
            assertTrue(bond.leftAtMs() >= before, "left_at starts the contract's cooldownHoursAfterLeave");
        }
        assertEquals(4, game.store.events("contract.ended").size());
    }

    @Test
    void anOwnerWithNoRosterIsANoOp() {
        RosterService roster = game.roster();
        game.store.rows.clear();

        roster.ownerDeleted(ANN);

        assertEquals(List.of(), game.store.events("roster.release"));
    }

    @Test
    void theDeletionHookHandsTheCleanupToCompanionOps() {
        LivingWorld world = mock(LivingWorld.class);
        RosterService roster = mock(RosterService.class);
        List<Runnable> ops = DirectExecutor.queue();
        CompanionExecutor companionOps = DirectExecutor.queued(ops);
        when(world.executor()).thenReturn(companionOps);
        when(world.roster()).thenReturn(roster);
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(world);

            LivingWorldHooks.characterDeleted(ANN);
            LivingWorldHooks.characterDeleted(0);

            verify(roster, never()).ownerDeleted(anyInt()); // not on the login server's thread
            assertEquals(1, ops.size());
            ops.get(0).run();
            verify(roster).ownerDeleted(ANN);
        }
    }

    @Test
    void characterDeletionCallsTheHookOnceItsRowsAreGone() throws IOException {
        String source = Files.readString(Path.of("src/main/java/client/Character.java"));
        int delete = source.indexOf("public static boolean deleteCharFromDB(");
        int end = source.indexOf("\n    }\n", delete);
        String body = source.substring(delete, end);
        int committed = body.indexOf("completeCharacterDeletion(context, cid, senderAccId);");
        int hook = body.indexOf("LivingWorldHooks.characterDeleted(cid);");
        assertTrue(committed > 0 && hook > committed, "the hook runs after the deletion transaction committed");
    }
}
