package server.living.roster;

import net.server.Server;
import net.server.world.CauseScope;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.bond.BondService;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.PARTY_ID;

/**
 * The grace after a logout (decision 8a, flow 7 step 5): a PARKED companion whose owner stays away past
 * {@code LEADER_OFFLINE_BENCH_MS} goes to its duty, where it "kept training". Its row is BENCHED from the grace
 * deadline, so the ledger credits its duty from then on and never the time it spent parked (nor any active time),
 * and it leaves its owner's offline party then: an offline member is expelled once, under the roster's cause, so the
 * party sync takes it as an echo. A companion still within its grace, or an owner online again, is left alone.
 */
class ParkGraceTest {
    private static final long HOUR_MS = 3_600_000L;

    private final LifecycleHarness game = new LifecycleHarness();
    private Persona hadrik;
    private long graceMs;

    /** The PARKED row of {@code persona} as its logout wrote it, but parked {@code agoMs} before now. */
    static void parkedAgo(LifecycleHarness game, Persona persona, long agoMs) {
        RosterEntry row = game.row(persona);
        game.store.seed(new RosterEntry(row.characterId(), row.personaId(), RosterState.PARKED, row.assignment(),
                row.assignmentItem(), row.assignmentCount(), row.campMapId(), row.accruedExp(), row.haulMeso(),
                row.wagesPaidUntilMs(), row.recruitedAtMs(), row.benchedAtMs(), row.lastLedgerAtMs(),
                row.farmSecondsToday(), row.farmDay(), 0L, 0, System.currentTimeMillis() - agoMs));
    }

    @BeforeEach
    void parkedWithAnnOffline() {
        hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.roster().onOwnerLogout(game.ann, null); // her own party: Hadrik stays in it, offline
        game.offline.add(901);
        game.annOnline = false;
        graceMs = game.config.LEADER_OFFLINE_BENCH_MS;
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void pastItsGraceItGoesToItsDutyFromTheDeadline() {
        parkedAgo(game, hadrik, graceMs + 5 * 60_000L);
        long parkedAt = game.row(hadrik).parkedAtMs();

        game.roster().benchParkedPastGrace();

        RosterEntry row = game.row(hadrik);
        assertEquals(RosterState.BENCHED, row.state());
        assertEquals(parkedAt + graceMs, row.benchedAtMs(), "benched from the grace deadline");
        assertEquals(parkedAt + graceMs, row.lastLedgerAtMs(), "the ledger clock starts at the deadline");
        assertEquals(0L, row.parkedAtMs());
        assertEquals(1, game.store.events("companion.parked_bench").size());
    }

    @Test
    void withinItsGraceItStaysParked() {
        parkedAgo(game, hadrik, graceMs - 60_000L);
        RosterEntry parked = game.row(hadrik);

        game.roster().benchParkedPastGrace();

        assertEquals(parked, game.row(hadrik));
    }

    @Test
    void anOwnerOnlineAgainIsLeftToTheLogin() {
        parkedAgo(game, hadrik, graceMs + 60_000L);
        game.annOnline = true;

        game.roster().benchParkedPastGrace();

        assertEquals(RosterState.PARKED, game.row(hadrik).state());
    }

    @Test
    void itLeavesTheOfflinePartyOnceUnderTheRostersCause() {
        World world = Server.getInstance().getWorld(0);
        when(world.getParty(PARTY_ID)).thenReturn(game.party);
        List<String> causes = new ArrayList<>();
        List<Integer> expelled = new ArrayList<>();
        doAnswer(inv -> {
            causes.add(CauseScope.current());
            PartyCharacter member = inv.getArgument(2);
            expelled.add(member.getId());
            game.members.remove((Integer) member.getId());
            return null;
        }).when(world).updateParty(eq(PARTY_ID), eq(PartyOperation.EXPEL), any());
        parkedAgo(game, hadrik, graceMs + 60_000L);

        game.roster().benchParkedPastGrace();
        game.roster().benchParkedPastGrace(); // the next sweep has nothing left to do

        assertEquals(List.of(901), expelled);
        assertEquals(List.of(RosterService.CAUSE), causes, "the sync takes the expel as the roster's echo");
        assertEquals(List.of(ANN), game.members, "Ann's party keeps only her");
    }

    @Test
    void anOnlineMemberIsNeverExpelled() {
        World world = Server.getInstance().getWorld(0);
        when(world.getParty(PARTY_ID)).thenReturn(game.party);
        game.offline.remove(901); // someone loaded it meanwhile: not the roster's to pull out
        parkedAgo(game, hadrik, graceMs + 60_000L);

        game.roster().benchParkedPastGrace();

        verify(world, never()).updateParty(anyInt(), any(), any());
        assertEquals(RosterState.BENCHED, game.row(hadrik).state());
    }

    @Test
    void theLedgerCreditsTheDutyFromTheDeadlineAndNeverTheTimeParked() {
        Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
        long now = System.currentTimeMillis();
        RosterEntry bench = game.row(olwen); // on duty for the last hour, for comparison
        game.store.seed(new RosterEntry(ANN, "olwen", RosterState.BENCHED, bench.assignment(), 0, 0, LITH, 0L, 0L, 0L,
                1_000L, now - HOUR_MS, now - HOUR_MS, 0, null));
        parkedAgo(game, hadrik, graceMs + HOUR_MS); // parked 1 h 20 min ago: 1 h past its deadline
        game.store.levels.put(ANN, 30);
        game.store.levels.put(900, 10);
        game.store.levels.put(901, 10);
        when(game.world.bonds()).thenReturn(mock(BondService.class));
        when(game.world.routineDate()).thenReturn(LocalDate.of(2026, 9, 28));
        BenchLedger ledger = new BenchLedger(game.world);

        game.roster().benchParkedPastGrace();
        ledger.tick();

        long credited = game.row(hadrik).accruedExp();
        assertTrue(credited > 0L, "it kept training");
        long hourOfDuty = game.row(olwen).accruedExp();
        assertTrue(Math.abs(credited - hourOfDuty) <= hourOfDuty / 1000 + 1,
                "one hour of duty, not the 80 minutes since it parked: " + credited + " vs " + hourOfDuty);
    }

    @Test
    void justAtItsDeadlineNothingIsCreditedForTheTimeParked() {
        game.store.levels.put(ANN, 30);
        game.store.levels.put(901, 10);
        when(game.world.bonds()).thenReturn(mock(BondService.class));
        when(game.world.routineDate()).thenReturn(LocalDate.of(2026, 9, 28));
        parkedAgo(game, hadrik, graceMs); // parked for exactly the grace

        game.roster().benchParkedPastGrace();
        new BenchLedger(game.world).tick();

        assertEquals(RosterState.BENCHED, game.row(hadrik).state());
        assertEquals(0L, game.row(hadrik).accruedExp(), "the twenty minutes parked are not bench time");
    }
}
