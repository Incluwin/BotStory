package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.PARTY_ID;

/**
 * Boot reconciliation (decision 8a, flow 7 step 7): a restart leaves nobody online and no party, so before any login
 * every companion that was out (ACTIVE, TRAVELLING, HOLD) is PARKED from now, in its old lineup order, and a PARKED one
 * whose grace had run out goes to its duty from its deadline; benched and away rows are untouched and nothing is
 * loaded. The auditor's drift classification is clean then, and again once the login path has walked the parked ones
 * back in.
 */
class BootReconcileTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private long graceMs;
    private long bootedAt;
    private Persona hadrik;
    private Persona jinx;
    private Persona vexa;
    private Persona olwen;
    private Persona brannock;
    private Persona sadie;
    private Persona dorran;

    private static RosterEntry row(String personaId, RosterState state, long etaAt, int holdMap, long parkedAt) {
        return new RosterEntry(ANN, personaId, state, Assignment.TRAIN, 0, 0, LITH, 0L, 0L, 0L, 1_000L, 1_000L, 1_000L, 0,
                null, etaAt, holdMap, parkedAt);
    }

    @BeforeEach
    void rowsAsTheLastRunLeftThem() {
        graceMs = game.config.LEADER_OFFLINE_BENCH_MS;
        long now = System.currentTimeMillis();
        hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        jinx = game.companion("jinx", "Jinx", 902, RosterState.ACTIVE, LITH);
        vexa = game.companion("vexa", "Vexa", 903, RosterState.ACTIVE, LITH);
        olwen = game.companion("olwen", "Olwen", 900, RosterState.ACTIVE, LITH);
        brannock = game.companion("brannock", "Brannock", 904, RosterState.ACTIVE, LITH);
        sadie = game.companion("sadie", "Sadie", 905, RosterState.BENCHED, LITH);
        dorran = game.companion("dorran", "Dorran", 906, RosterState.AWAY, LITH);
        game.store.seed(row("jinx", RosterState.TRAVELLING, now + 30_000L, 0, 0L));
        game.store.seed(row("vexa", RosterState.HOLD, 0L, KERNING, 0L));
        game.store.seed(row("olwen", RosterState.PARKED, 0L, 0, now - graceMs - 60_000L));
        game.store.seed(row("brannock", RosterState.PARKED, 0L, 0, now - 60_000L));
        game.store.lineup.put(FakeLivingStore.key(ANN, "jinx"), 1);
        game.store.lineup.put(FakeLivingStore.key(ANN, "hadrik"), 2);
        game.store.lineup.put(FakeLivingStore.key(ANN, "olwen"), 3);
        game.annOnline = false;
        game.roster(); // the boot rebuilds the HOLD and AWAY seats
        bootedAt = System.currentTimeMillis();
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void everyCompanionThatWasOutIsParkedFromNow() {
        assertNotNull(game.slots.reservation("vexa"));

        game.roster().reconcileAtBoot();

        for (Persona persona : List.of(hadrik, jinx, vexa)) {
            RosterEntry row = game.row(persona);
            assertEquals(RosterState.PARKED, row.state(), persona.name());
            assertTrue(row.parkedAtMs() >= bootedAt, "parked_at is the boot");
            assertEquals(0L, row.etaAtMs());
            assertEquals(0, row.holdMapId());
        }
        assertNull(game.slots.reservation("vexa"), "no run to wait outside any more");
        assertEquals(List.of(), game.calls, "nothing is loaded at boot");
    }

    @Test
    void aParkedOnePastItsGraceGoesToItsDutyFromItsDeadline() {
        long parkedAt = game.row(olwen).parkedAtMs();
        RosterEntry stillParked = game.row(brannock);

        game.roster().reconcileAtBoot();

        RosterEntry benched = game.row(olwen);
        assertEquals(RosterState.BENCHED, benched.state());
        assertEquals(parkedAt + graceMs, benched.lastLedgerAtMs(), "credited from the deadline");
        assertEquals(stillParked, game.row(brannock), "within its grace it stays parked");
    }

    @Test
    void benchedAndAwayRowsAreUntouched() {
        RosterEntry benched = game.row(sadie);
        RosterEntry away = game.row(dorran);

        game.roster().reconcileAtBoot();

        assertEquals(benched, game.row(sadie));
        assertEquals(away, game.row(dorran));
        assertNotNull(game.slots.reservation("dorran"), "an errand keeps its seat");
    }

    @Test
    void theUsualLineupBecomesThoseThatWereOutInTheirOldOrder() {
        game.roster().reconcileAtBoot();

        assertEquals(Map.of("jinx", 1, "hadrik", 2, "vexa", 3), game.store.store.lineupSlots(ANN));
    }

    @Test
    void theAuditorSeesNoDriftAfterTheBootNorOnceTheLoginRestoredThem() {
        game.roster().reconcileAtBoot();
        game.members.clear(); // no party survived the restart: Ann logs in alone and one is made for her
        game.members.add(ANN);
        assertTrue(report(0).clean(), String.valueOf(report(0).drift()));

        game.annOnline = true;
        game.roster().onOwnerLogin(game.ann);
        game.runDueTimers();

        for (Persona persona : List.of(hadrik, jinx, vexa, brannock)) {
            assertEquals(RosterState.ACTIVE, game.row(persona).state(), persona.name());
        }
        assertEquals(RosterState.BENCHED, game.row(olwen).state(), "the lineup is not called while others come back");
        assertTrue(report(PARTY_ID).clean(), String.valueOf(report(PARTY_ID).drift()));
    }

    /** Ann's drift report as the auditor builds it, from the harness's party and registry. */
    private CompanionDriftReport report(int partyId) {
        List<CompanionDriftReport.Member> party = new ArrayList<>();
        for (int id : game.members) {
            party.add(new CompanionDriftReport.Member(id, String.valueOf(id), id != ANN));
        }
        CompanionDriftReport.Owner owner = new CompanionDriftReport.Owner(ANN, "Ann", 1, HENESYS_FIELD, partyId,
                partyId == 0 ? List.of() : party);
        List<CompanionDriftReport.Companion> companions = new ArrayList<>();
        for (RosterEntry entry : game.store.store.roster(ANN)) {
            int id = game.residents.characterIdOf(entry.personaId());
            boolean loaded = game.loaded.contains(id);
            CompanionDriftReport.Row row = new CompanionDriftReport.Row(entry.personaId(), entry.personaId(), entry.state(),
                    entry.assignment(), entry.campMapId(), id);
            CompanionDriftReport.Live live = loaded ? new CompanionDriftReport.Live(true, 1, HENESYS_FIELD, 1, 1,
                    game.members.contains(id) ? PARTY_ID : 0, ANN) : CompanionDriftReport.Live.unloaded(-1);
            companions.add(new CompanionDriftReport.Companion(row, live));
        }
        return CompanionDriftReport.of(owner, 0, companions, List.of());
    }
}
