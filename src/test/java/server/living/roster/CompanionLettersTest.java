package server.living.roster;

import client.processor.npc.DueyProcessor;
import constants.game.ExpTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import server.living.bond.Friendship;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Letters (docs/BOT_CONCEPT_PLAN.md F3): after a night away each companion that did something writes its owner one note
 * in its voice, sent as itself, and one pink line sums it up; the bench's news is written as bench.report rows, so a
 * restart in between loses nothing; at most LETTERS_MAX_NOTES notes a login (the rest in one crew letter) within the
 * unread cap; finds are named by the rarity rule; a Resident friend sometimes mails a gift from its haul, capped weekly;
 * and a citizen asking after an absent friend is told at their login.
 */
class CompanionLettersTest {
    private static final int FIELD = 105040300;
    private static final int GLOVE_SCROLL = 2040804;
    private static final int STOLEN_FENCE = 4000021;
    private static final int SWORD = 1302000;
    private static final int HIGH_SWORD = 1302010;
    private static final int RARE_ORE = 4004004;
    private static final Map<Integer, String> NAMES = Map.of(GLOVE_SCROLL, "Scroll for Gloves for DEX 60%",
            STOLEN_FENCE, "Stolen Fence", SWORD, "Sword", HIGH_SWORD, "Fine Sword", RARE_ORE, "Dark Crystal Ore");
    /** One line per part: the letters read the same every time. */
    private static final String VOICE = """
            parts:
              open: { lines: ["{owner},"] }
              job: { lines: ["made {job}."] }
              level: { lines: ["ready for lv {level}."] }
              levelup: { lines: ["hit lv {level}."] }
              drops: { lines: ["found {drops} at {camp}."] }
              parcel: { lines: ["sent you something."] }
              camp: { lines: ["moved to {camp}."] }
              deaths: { lines: ["fell {times}."] }
              meso: { lines: ["+{meso} meso."] }
              close: { lines: ["- {self}"] }
              crew_open: { lines: ["crew:"] }
              crew_job: { lines: ["{name} made {job}."] }
              crew_level: { lines: ["{name} is ready for lv {level}."] }
              crew_drops: { lines: ["{name} found {drops}."] }
              crew_meso: { lines: ["{name} +{meso}."] }
              gift: { lines: ["found this at {camp}."] }
            """;

    @TempDir
    Path dir;

    /** Notes as the notes table would keep them: {sender, message}. */
    static final class FakeNotes implements CompanionLetters.Notes {
        int room = 100;
        int shown;
        final List<String[]> sent = new ArrayList<>();

        @Override
        public int room(String receiver) {
            return room;
        }

        @Override
        public boolean send(String message, String sender, String receiver) {
            sent.add(new String[] {sender, message});
            return true;
        }

        @Override
        public void show(client.Character receiver) {
            shown++;
        }

        List<String> messages() {
            return sent.stream().map(note -> note[0] + ": " + note[1]).toList();
        }
    }

    /** The item facts: names, required levels (swords 30 and 60) and the price index's top tenth (the ore). */
    static final CompanionLetters.ItemFacts ITEMS = new CompanionLetters.ItemFacts() {
        @Override
        public String name(int itemId) {
            return NAMES.getOrDefault(itemId, "item " + itemId);
        }

        @Override
        public int requiredLevel(int itemId) {
            return itemId == SWORD ? 30 : itemId == HIGH_SWORD ? 60 : 0;
        }

        @Override
        public boolean topDecile(int itemId) {
            return itemId == RARE_ORE;
        }
    };

    private FakeNotes letters(RosterService roster) throws IOException {
        Path file = dir.resolve(LetterVoice.FILE);
        Files.writeString(file, VOICE);
        CompanionLetters letters = roster.letters();
        letters.voice(LetterVoice.load(file));
        letters.items = ITEMS;
        FakeNotes notes = new FakeNotes();
        letters.notes = notes;
        return notes;
    }

    private static BenchLedger ledger(LifecycleHarness game) {
        BenchLedger ledger = new BenchLedger(game.world, id -> "Ant Tunnel I", NAMES::get);
        when(game.world.bench()).thenReturn(ledger);
        return ledger;
    }

    /** Its row with {@code banked} EXP. */
    private static void bank(LifecycleHarness game, Persona persona, long banked) {
        RosterEntry row = game.row(persona);
        game.store.seed(new RosterEntry(ANN, persona.id(), row.state(), Assignment.FARM_ITEM, 0, 0, FIELD, banked, 0L, 0L,
                row.recruitedAtMs(), row.benchedAtMs(), row.lastLedgerAtMs(), 0, null));
    }

    private static MockedStatic<MapFactory> places() {
        MockedStatic<MapFactory> places = mockStatic(MapFactory.class);
        places.when(() -> MapFactory.loadPlaceName(FIELD)).thenReturn("Ant Tunnel I");
        return places;
    }

    // ---- the digest ---------------------------------------------------------------------------

    @Test
    void aNightAwayBecomesOneNoteFromEachCompanionAndOnePinkLine() throws IOException {
        try (LifecycleHarness game = new LifecycleHarness(); MockedStatic<MapFactory> places = places()) {
            game.config.LETTERS_ENABLED = true;
            when(game.ann.getLevel()).thenReturn(35);
            Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
            Persona mira = game.companion("mira", "Mira", 950, RosterState.BENCHED, LITH);
            game.store.levels.put(900, 30);
            bank(game, olwen, ExpTable.getExpNeededForLevel(30) + 10L);
            BenchLedger ledger = ledger(game);
            FakeNotes notes = letters(game.roster());
            ledger.reportHaul(ANN, "olwen", FIELD, 999L, 0, 0); // farmed while Ann was still on: not news of her absence

            game.roster().onOwnerLogout(game.ann, null);
            ledger.reportHaul(ANN, "olwen", FIELD, 1_200L, GLOVE_SCROLL, 1);
            ledger.reportHaul(ANN, "olwen", FIELD, 0L, STOLEN_FENCE, 143); // common loot is no news
            ledger.reportSlice(ANN, "olwen", FIELD, 5_000L, 30, 2);
            game.store.store.event(ANN, "mira", CompanionLetters.JOB_ADVANCED_EVENT, "CRUSADER from Tylus at map 211000001");
            game.roster().onOwnerLogin(game.ann);
            assertTrue(notes.sent.isEmpty(), "the letters come a few seconds on, after the login's own note show");
            game.runTimers();

            assertEquals(List.of("Olwen: Ann, ready for lv 31. found a Scroll for Gloves for DEX 60% at Ant Tunnel I. fell twice."
                            + " +1.2k meso. - Olwen", "Mira: Ann, made crusader. - Mira"), notes.messages());
            assertEquals(1, notes.shown);
            verify(game.ann).dropMessage(5, "While you were away, Olwen and Mira wrote to you.");
            assertNull(ledger.takeReport(ANN, "olwen", 0), "told by the letters: no call-back tells it again");

            game.roster().onOwnerLogin(game.ann); // a channel change: nothing new, nothing again
            game.runTimers();
            assertEquals(2, notes.sent.size());
            assertEquals(1, game.store.events(CompanionLetters.TOLD_EVENT).size());
        }
    }

    @Test
    void aCompanionCalledBackAtLoginLeavesItsNewsToItsLetter() throws IOException {
        try (LifecycleHarness game = new LifecycleHarness(); MockedStatic<MapFactory> places = places()) {
            game.config.LETTERS_ENABLED = true;
            Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, HENESYS_FIELD); // camps where Ann is
            BenchLedger ledger = ledger(game);
            FakeNotes notes = letters(game.roster());
            game.roster().onOwnerLogout(game.ann, null);
            game.store.lineup.put(FakeLivingStore.key(ANN, "olwen"), 1); // in Ann's usual lineup
            ledger.reportHaul(ANN, "olwen", FIELD, 4_000L, GLOVE_SCROLL, 1);
            ledger.flushReports(0);

            game.roster().onOwnerLogin(game.ann); // the usual lineup is called back
            game.runTimers();

            verify(game.residents, never()).whisper(eq(olwen), eq(game.ann), startsWith("farmed"));
            assertEquals(List.of("Olwen: Ann, found a Scroll for Gloves for DEX 60% at Ant Tunnel I. +4.0k meso. - Olwen"),
                    notes.messages(), "told once, by its letter");
        }
    }

    @Test
    void theBenchNewsSurvivesARestart() throws IOException {
        try (LifecycleHarness game = new LifecycleHarness(); MockedStatic<MapFactory> places = places()) {
            game.config.LETTERS_ENABLED = true;
            when(game.ann.getLevel()).thenReturn(35);
            game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
            game.store.levels.put(900, 30);
            BenchLedger before = ledger(game);
            game.roster().onOwnerLogout(game.ann, null);
            before.reportHaul(ANN, "olwen", FIELD, 0L, GLOVE_SCROLL, 1);
            for (int kind = 0; kind < 40; kind++) {
                before.reportHaul(ANN, "olwen", FIELD, 0L, 4_000_100 + kind, 1); // more stacks than one row holds
            }
            before.reportHaul(ANN, "olwen", FIELD, 0L, RARE_ORE, 3);
            before.flushReports(0); // as the end of a ledger tick writes them
            assertTrue(game.store.events(BenchLedger.REPORT_EVENT).size() >= 3);
            assertTrue(game.store.events(BenchLedger.REPORT_EVENT).stream()
                    .allMatch(row -> row.length() - row.indexOf('{') <= BenchReport.DETAIL_MAX));

            // the restart: a new ledger and roster with nothing in memory, the same database
            BenchLedger after = ledger(game);
            RosterService restarted = new RosterService(game.world);
            when(game.world.roster()).thenReturn(restarted);
            FakeNotes notes = letters(restarted);
            restarted.onOwnerLogin(game.ann); // the first login since the boot
            game.runTimers();

            assertEquals(List.of("Olwen: Ann, found a Scroll for Gloves for DEX 60% and 3 Dark Crystal Ore at Ant Tunnel I."
                    + " - Olwen"), notes.messages());
            assertNull(after.takeReport(ANN, "olwen", 0), "the new ledger had nothing in memory");
        }
    }

    @Test
    void fiveNotesALoginAtMostTheRestInOneCrewLetterAndNeverPastTheUnreadCap() throws IOException {
        try (LifecycleHarness game = new LifecycleHarness(); MockedStatic<MapFactory> places = places()) {
            game.config.LETTERS_ENABLED = true;
            List<String> names = List.of("Ada", "Bo", "Cy", "Dee", "Eli", "Fay", "Gus");
            for (int i = 0; i < names.size(); i++) {
                game.companion(names.get(i).toLowerCase(), names.get(i), 900 + i, RosterState.BENCHED, LITH);
                game.store.store.event(ANN, names.get(i).toLowerCase(), CompanionLetters.JOB_ADVANCED_EVENT, "HUNTER from Athena");
            }
            FakeNotes notes = letters(game.roster());

            game.roster().letters().deliver(ANN);

            assertEquals(5, notes.sent.size());
            assertEquals(List.of("Ada", "Bo", "Cy", "Dee", "Eli"), notes.sent.stream().map(note -> note[0]).toList());
            assertEquals("Ann, made hunter. crew: Fay made hunter. Gus made hunter. - Eli", notes.sent.get(4)[1]);
            verify(game.ann).dropMessage(5, "While you were away, Ada, Bo, Cy and 4 others wrote to you.");

            for (int i = 0; i < names.size(); i++) {
                game.store.store.event(ANN, names.get(i).toLowerCase(), CompanionLetters.JOB_ADVANCED_EVENT, "SNIPER from Robeira");
            }
            notes.sent.clear();
            notes.room = 2; // 98 unread already
            game.roster().letters().deliver(ANN);
            assertEquals(List.of("Ada", "Bo"), notes.sent.stream().map(note -> note[0]).toList(), "one own, then the crew's");
            assertTrue(notes.sent.get(1)[1].startsWith("Ann, made sniper. crew: Cy made sniper."));
            assertTrue(notes.sent.stream().allMatch(note -> note[1].length() <= CompanionLetters.NOTE_MAX));

            game.store.store.event(ANN, "ada", CompanionLetters.JOB_ADVANCED_EVENT, "RANGER from Legor");
            notes.sent.clear();
            notes.room = 0; // a full inbox gets nothing
            game.roster().letters().deliver(ANN);
            assertTrue(notes.sent.isEmpty());
            verify(game.ann).dropMessage(5, "While you were away, your crew has news, but your notes are full.");
        }
    }

    @Test
    void aLongLetterLeavesOutItsLeastImportantNews() {
        String open = "Ann,";
        List<String> sentences = List.of("made crusader.", "ready for lv 71.", "x".repeat(170) + ".", "fell twice.");
        String text = CompanionLetters.fit(open, sentences, "- Olwen");
        assertEquals("Ann, made crusader. ready for lv 71. - Olwen", text);
        assertEquals(200, CompanionLetters.fit(open, List.of("y".repeat(300)), "- Olwen").length());
    }

    // ---- the rarity rule ---------------------------------------------------------------------

    @Test
    void theRarityRuleKeepsGearNearTheOwnersLevelScrollsAndTheDearestTenth() {
        assertTrue(CompanionLetters.notable(SWORD, 35, ITEMS, 10), "an equip 5 levels below its owner");
        assertTrue(CompanionLetters.notable(SWORD, 20, ITEMS, 10), "or 10 above");
        assertFalse(CompanionLetters.notable(SWORD, 41, ITEMS, 10), "11 below is no news");
        assertFalse(CompanionLetters.notable(HIGH_SWORD, 35, ITEMS, 10), "nor gear far past its owner");
        assertTrue(CompanionLetters.notable(GLOVE_SCROLL, 35, ITEMS, 10), "a scroll always is");
        assertFalse(CompanionLetters.notable(STOLEN_FENCE, 35, ITEMS, 10), "common loot is not");
        assertTrue(CompanionLetters.notable(RARE_ORE, 35, ITEMS, 10), "what the price index holds in its top tenth is");
        assertEquals("a few times", CompanionLetters.softCount(5));
        assertEquals("crusader", CompanionLetters.spokenJob("CRUSADER from Tylus at map 211000001"));
        assertEquals("ice lightning wizard", CompanionLetters.spokenJob("ICE_LIGHTNING_WIZARD from Grendel"));
        assertEquals(31, CompanionLetters.readyLevel(30, ExpTable.getExpNeededForLevel(30)));
        assertEquals(30, CompanionLetters.readyLevel(30, ExpTable.getExpNeededForLevel(30) - 1L));
    }

    // ---- gifts by Duey -----------------------------------------------------------------------

    @Test
    void aResidentFriendMailsOneFindFromItsHaulAWeekAtMost() throws IOException {
        try (LifecycleHarness game = new LifecycleHarness(); MockedStatic<MapFactory> places = places();
             MockedStatic<DueyProcessor> duey = mockStatic(DueyProcessor.class)) {
            game.config.LETTERS_ENABLED = true;
            duey.when(() -> DueyProcessor.dueyCreatePackage(any(), anyInt(), any(), anyString(), anyInt())).thenReturn(true);
            game.companion("olwen", "Olwen", 900, RosterState.BENCHED, FIELD);
            game.store.levels.put(ANN, 35);
            game.store.store.addHaul(ANN, "olwen", STOLEN_FENCE, 50);
            game.store.store.addHaul(ANN, "olwen", GLOVE_SCROLL, 2);
            Friendship friendship = mock(Friendship.class);
            when(game.world.friendship()).thenReturn(friendship);
            when(friendship.level(ANN, "olwen")).thenReturn(3);
            when(game.store.store.countEvents(eq(ANN), eq("olwen"), eq(CompanionLetters.GIFT_EVENT), anyLong()))
                    .thenAnswer(inv -> game.store.events(CompanionLetters.GIFT_EVENT).size());
            letters(game.roster());
            CompanionLetters letters = game.roster().letters();
            RosterEntry entry = game.store.row(ANN, "olwen");

            assertFalse(letters.gift(entry, System.currentTimeMillis()), "Lv 3 is not a friend yet");
            when(friendship.level(ANN, "olwen")).thenReturn(4);
            assertTrue(letters.gift(entry, System.currentTimeMillis()));
            assertFalse(letters.gift(entry, System.currentTimeMillis()), "one a week");

            duey.verify(() -> DueyProcessor.dueyCreatePackage(any(), eq(0), eq("found this at Ant Tunnel I."), eq("Olwen"),
                    eq(ANN)), times(1));
            duey.verify(() -> DueyProcessor.dueyNotify(game.ann));
            assertEquals(1, game.store.haul.get(FakeLivingStore.key(ANN, "olwen")).get(GLOVE_SCROLL), "taken from the haul");
            assertEquals(50, game.store.haul.get(FakeLivingStore.key(ANN, "olwen")).get(STOLEN_FENCE));

            game.config.LETTERS_GIFT_CHANCE = 1.0d;
            game.companion("hire:77", "Hiro", 977, RosterState.BENCHED, FIELD);
            game.store.store.addHaul(ANN, "hire:77", GLOVE_SCROLL, 1);
            letters.mailGifts(List.of(game.store.row(ANN, "hire:77")), System.currentTimeMillis());
            duey.verify(() -> DueyProcessor.dueyCreatePackage(any(), anyInt(), any(), eq("Hiro"), anyInt()), never());

            game.config.HIRE_FRIENDSHIP_ENABLED = true; // docs/BOT_CONCEPT_PLAN.md C1.2: hires keep friendship now
            when(friendship.level(ANN, "hire:77")).thenReturn(4);
            letters.mailGifts(List.of(game.store.row(ANN, "hire:77")), System.currentTimeMillis());
            duey.verify(() -> DueyProcessor.dueyCreatePackage(any(), anyInt(), any(), eq("Hiro"), eq(ANN)), times(1));
        }
    }

    // ---- citizens asking after an absent friend ---------------------------------------------------

    @Test
    void aCitizenAskingAfterYouIsToldAtYourLogin() throws IOException {
        try (LifecycleHarness game = new LifecycleHarness()) {
            game.config.LETTERS_ENABLED = true;
            FakeNotes notes = letters(game.roster());
            CompanionLetters letters = game.roster().letters();
            long now = System.currentTimeMillis();

            assertTrue(letters.mayAsk(ANN, now));
            letters.asked(ANN, "Ranna", now);
            assertFalse(letters.mayAsk(ANN, now + 3_600_000L), "nobody asks after Ann again for a while");
            assertTrue(letters.mayAsk(ANN, now + game.config.LETTERS_ASK_GAP_HOURS * 3_600_000L));
            letters.asked(ANN, "Bo", now);

            letters.deliver(ANN);
            assertTrue(notes.sent.isEmpty());
            verify(game.ann).dropMessage(5, "While you were away, Ranna and Bo asked after you.");
        }
    }

    @Test
    void withTheLettersOffTheLoginSaysWhatItSaidBefore() throws IOException {
        try (LifecycleHarness game = new LifecycleHarness()) {
            game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
            FakeNotes notes = letters(game.roster());
            BenchLedger ledger = ledger(game);
            ledger.reportHaul(ANN, "olwen", FIELD, 0L, GLOVE_SCROLL, 1);
            ledger.flushReports(0);
            game.roster().onOwnerLogout(game.ann, null);
            game.roster().onOwnerLogin(game.ann);
            game.runTimers();

            assertTrue(notes.sent.isEmpty());
            assertTrue(game.store.events(BenchLedger.REPORT_EVENT).isEmpty(), "no rows while the letters are off");
            assertTrue(game.store.events(CompanionLetters.AWAY_EVENT).isEmpty());
            assertNotNull(ledger.takeReport(ANN, "olwen", 0), "the call-back report is as it was");
        }
    }

    @Test
    void theShippedVoiceGivesEveryPartLinesInEveryStyle() {
        LetterVoice voice = LetterVoice.load(Path.of("scripts/config/living-world", LetterVoice.FILE));
        java.util.Random random = new java.util.Random(1);
        for (String part : List.of("open", "job", "level", "levelup", "drops", "parcel", "camp", "deaths", "meso", "close",
                "crew_open", "crew_job", "crew_level", "crew_levelup", "crew_drops", "crew_parcel", "crew_meso", "crew_camp",
                "crew_deaths", "gift")) {
            for (String style : Set.of("quiet-practical-v1", "friendly-casual-v1", "playful-social-v1", "curious-millennial-v1")) {
                String line = voice.line(part, style, random);
                assertNotNull(line, part + " in " + style);
                assertTrue(line.length() < 100, line);
            }
        }
    }
}
