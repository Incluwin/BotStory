package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.maps.MapFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;
import static server.living.roster.BenchHarness.OTHER_FIELD;

/**
 * A companion leaves a camp that keeps killing it (found live 2026-09-30: Casse, a Lv24 magician, died 3 slices in 4 at
 * Excavation Site III, a camp set when she was granted). Before a field slice: its kind measured dying there
 * (RosterService.roughCampFor) and its own last three slices there since the camp was last set holding two deaths, it
 * moves itself to the best safe field for its level and tells its owner once; with none safe it waits in the ledger and
 * its card says so. The pickers mark rough camps, and a camp its owner picks anew is judged on what happens after.
 */
class RoughCampTest {
    private static final int CASSE = 333;
    private final BenchHarness game = new BenchHarness();
    private final List<String[]> history = new ArrayList<>();

    @AfterEach
    void close() {
        game.close();
    }

    private static String[] live(int mapId) {
        return new String[] {"bench.live", "farm_meso live at map " + mapId};
    }

    private static String[] end(String reason) {
        return new String[] {"bench.live-end", reason};
    }

    // ---- its own record at a camp ------------------------------------------------------------

    @Test
    void theRecordCountsItsLastSlicesAtTheCampAndTheirDeaths() {
        List<String[]> events = List.of(
                live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT),
                live(OTHER_FIELD), end(BenchDutyDispatcher.ROUGH_HUNT), // another camp: not counted
                live(FIELD), end("resupplied"),
                live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT));

        assertArrayEquals(new int[] {3, 2}, BenchDutyDispatcher.campRecord(events, FIELD, 3));
        assertArrayEquals(new int[] {1, 1}, BenchDutyDispatcher.campRecord(events, OTHER_FIELD, 3));
    }

    @Test
    void onlyTheLastWindowOfSlicesCounts() {
        List<String[]> events = List.of(
                live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT),
                live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT),
                live(FIELD), end("time up"),
                live(FIELD), end("time up"),
                live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT));

        assertArrayEquals(new int[] {3, 1}, BenchDutyDispatcher.campRecord(events, FIELD, 3), "two old deaths are out");
    }

    @Test
    void aCampSetAnewStartsItsRecordAgain() {
        List<String[]> events = List.of(
                live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT),
                live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT),
                new String[] {RosterService.CAMP_EVENT, "camp " + FIELD}, // its owner picked it again, knowing
                live(FIELD), end("time up"));

        assertArrayEquals(new int[] {1, 0}, BenchDutyDispatcher.campRecord(events, FIELD, 3));
        assertArrayEquals(new int[] {0, 0}, BenchDutyDispatcher.campRecord(null, FIELD, 3));
    }

    // ---- before a slice ----------------------------------------------------------------------

    /** Casse farming meso at FIELD, her kind measured {@code rough} there, her record there {@code events}. */
    private Persona casse(boolean rough, List<String[]> events) {
        Persona persona = game.companion(ANN, "casse", "Casse", CASSE, Assignment.FARM_MESO);
        when(game.roster.roughCampFor(CASSE, FIELD)).thenReturn(rough);
        history.addAll(events);
        game.duty.campHistory = (ownerId, personaId) -> "casse".equals(personaId) ? history : List.of();
        return persona;
    }

    private void safeField(Persona persona, int mapId) {
        when(game.roster.fieldCampFor(persona, FIELD)).thenReturn(mapId);
        when(game.roster.moveCamp(eq(ANN), eq("casse"), eq(mapId), anyString())).thenAnswer(inv -> {
            RosterEntry moved = row(mapId);
            game.store.seed(moved);
            return moved;
        });
    }

    private RosterEntry row(int campMapId) {
        RosterEntry now = game.row(ANN, "casse");
        return new RosterEntry(now.characterId(), now.personaId(), now.state(), now.assignment(), now.assignmentItem(),
                now.assignmentCount(), campMapId, now.accruedExp(), now.haulMeso(), now.wagesPaidUntilMs(),
                now.recruitedAtMs(), now.benchedAtMs(), now.lastLedgerAtMs(), now.farmSecondsToday(), now.farmDay(),
                now.etaAtMs(), now.holdMapId(), now.parkedAtMs());
    }

    private MockedStatic<MapFactory> placeNames() {
        MockedStatic<MapFactory> names = mockStatic(MapFactory.class);
        names.when(() -> MapFactory.loadPlaceName(FIELD)).thenReturn("Excavation Site III");
        names.when(() -> MapFactory.loadPlaceName(OTHER_FIELD)).thenReturn("Line 1 <Area 2>");
        return names;
    }

    @Test
    void aCompanionThatKeepsDyingAtARoughCampMovesToASafeOneAndSaysSo() {
        Persona persona = casse(true, List.of(live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT),
                live(FIELD), end("resupplied"), live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT)));
        safeField(persona, OTHER_FIELD);
        game.online.add(ANN);

        try (MockedStatic<MapFactory> names = placeNames()) {
            game.duty.tick();
        }

        verify(game.roster).moveCamp(eq(ANN), eq("casse"), eq(OTHER_FIELD), anyString());
        assertTrue(game.duty.live("casse"));
        assertEquals(OTHER_FIELD, game.duty.slice("casse").mapId(), "the slice starts at its new camp");
        verify(game.residents).whisper(eq(persona), any(Character.class),
                eq("Excavation Site III is too rough for me. i'll hunt at Line 1 <Area 2> till you say otherwise."));
        assertEquals(1, game.store.events(BenchDutyDispatcher.CAMP_MOVED_EVENT).size());
        assertEquals(1, game.store.events(RosterService.CAMP_MOVES_TOLD_EVENT).size(), "told online: not again at login");
    }

    @Test
    void offlineItMovesWithoutAWhisperAndTheLoginLineTellsIt() {
        Persona persona = casse(true, List.of(live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT),
                live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT)));
        safeField(persona, OTHER_FIELD);

        try (MockedStatic<MapFactory> names = placeNames()) {
            game.duty.tick();
        }

        assertEquals(OTHER_FIELD, game.row(ANN, "casse").campMapId());
        verify(game.residents, never()).whisper(any(), any(), anyString());
        assertEquals(1, game.store.events(BenchDutyDispatcher.CAMP_MOVED_EVENT).size());
        assertTrue(game.store.events(RosterService.CAMP_MOVES_TOLD_EVENT).isEmpty(), "the login line tells it");
    }

    @Test
    void oneDeathInItsLastThreeIsNotEnoughItTries() {
        casse(true, List.of(live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT), live(FIELD), end("time up"),
                live(FIELD), end("time up")));

        game.duty.tick();

        verify(game.roster, never()).moveCamp(anyInt(), anyString(), anyInt(), anyString());
        assertEquals(FIELD, game.duty.slice("casse").mapId());
    }

    @Test
    void deathsWhereItsKindIsNotMeasuredDeadlyDoNotMoveIt() {
        casse(false, List.of(live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT), live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT)));

        game.duty.tick();

        verify(game.roster, never()).moveCamp(anyInt(), anyString(), anyInt(), anyString());
        assertEquals(FIELD, game.duty.slice("casse").mapId(), "bad luck at a camp its kind does fine at");
    }

    @Test
    void withNoSafeCampItWaitsInTheLedgerAndItsCardSaysSoOnce() {
        Persona persona = casse(true, List.of(live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT),
                live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT)));
        when(game.roster.fieldCampFor(persona, FIELD)).thenReturn(0);
        game.online.add(ANN);

        try (MockedStatic<MapFactory> names = placeNames()) {
            game.duty.tick();
            game.now += 120_000L;
            game.duty.tick();
        }

        assertFalse(game.duty.live("casse"), "not sent back to die");
        assertEquals(BenchDutyDispatcher.NO_SAFE_CAMP, game.duty.note("casse"));
        verify(game.residents, times(1)).whisper(eq(persona), any(Character.class), contains("too rough for me"));
        assertEquals(1, game.store.events("bench.no-safe-camp").size(), "said once, not every tick");

        when(game.roster.roughCampFor(CASSE, FIELD)).thenReturn(false); // its owner moved it, or it levelled up
        game.now += 120_000L;
        game.duty.tick();
        assertTrue(game.duty.live("casse"));
        assertEquals("", game.duty.note("casse"));
    }

    @Test
    void aRowOnYourCallIsLeftToItsOwnPick() {
        casse(true, List.of(live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT), live(FIELD), end(BenchDutyDispatcher.ROUGH_HUNT)));
        game.duty.setYourCall(ANN, "casse", true);
        game.duty.yourCallCamps = (level, near) -> List.of(); // no camps to pick among: it keeps what it has

        game.duty.tick();

        verify(game.roster, never()).moveCamp(anyInt(), anyString(), anyInt(), anyString());
    }
}
