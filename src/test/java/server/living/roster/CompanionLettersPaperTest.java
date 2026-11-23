package server.living.roster;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import server.living.population.paper.PaperCatalog;
import server.living.population.paper.PaperCompose;
import server.living.population.paper.PaperDesk;
import server.living.population.paper.PaperIssue;
import server.maps.MapFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * The town paper in the letters (docs/WORLD_SYSTEMS_PLAN.md N1.2, live check P3): a player logging in while an issue
 * they have not seen is out gets one note that it is out, and the next login none; an issue read at a board first, or
 * the paper off, sends nothing.
 */
class CompanionLettersPaperTest {
    @TempDir
    Path dir;

    private final Map<Integer, Integer> seen = new HashMap<>();
    private final PopulationConfig config = new PopulationConfig();
    private PaperDesk.Stored issue;

    @AfterEach
    void uninstall() {
        PaperDesk.install(null);
    }

    private PaperDesk desk() {
        PaperDesk.Store store = new PaperDesk.Store() {
            @Override
            public boolean file(String detail) {
                return true;
            }

            @Override
            public Optional<PaperDesk.Stored> issue(int weekOf) {
                return Optional.ofNullable(issue);
            }

            @Override
            public boolean insert(int weekOf, long issuedAtMs, String body) {
                return false;
            }

            @Override
            public Optional<PaperDesk.Stored> latestAtOrBefore(int weekOf) {
                return Optional.ofNullable(issue);
            }

            @Override
            public Integer lastSeen(int characterId) {
                return seen.getOrDefault(characterId, -1);
            }

            @Override
            public void seen(int characterId, int weekOf) {
                seen.put(characterId, weekOf);
            }

            @Override
            public String name(int characterId) {
                return null;
            }
        };
        PaperDesk desk = new PaperDesk(store, mock(PaperCompose.Sources.class),
                () -> PaperCatalog.load(Path.of("scripts/config/population")), () -> config, Runnable::run,
                ZoneId.systemDefault());
        long now = System.currentTimeMillis();
        int week = desk.weekOf(now);
        PaperIssue printed = new PaperIssue(week, now - 3_600_000L, List.of(new PaperIssue.Section("town", "Town",
                List.of(new PaperIssue.Story("town.wedding", 70, "Wedding bells for Ann and Ben", "They wed.",
                        "ann and ben got married", List.of("Ann", "Ben"))))));
        issue = new PaperDesk.Stored(week, printed.issuedAtMs(), printed.toJson());
        PaperDesk.install(desk);
        return desk;
    }

    private CompanionLettersTest.FakeNotes letters(RosterService roster) throws IOException {
        Path file = dir.resolve(LetterVoice.FILE);
        Files.writeString(file, "parts:\n  open: { lines: [\"{owner},\"] }\n  close: { lines: [\"- {self}\"] }\n");
        CompanionLetters letters = roster.letters();
        letters.voice(LetterVoice.load(file));
        letters.items = CompanionLettersTest.ITEMS;
        CompanionLettersTest.FakeNotes notes = new CompanionLettersTest.FakeNotes();
        letters.notes = notes;
        return notes;
    }

    private void relog(LifecycleHarness game) {
        game.roster().onOwnerLogout(game.ann, null);
        game.roster().onOwnerLogin(game.ann);
        game.runTimers();
    }

    @Test
    void oneNotePerIssueNotTwo() throws IOException {
        config.PAPER_ENABLED = true;
        desk();
        try (LifecycleHarness game = new LifecycleHarness(); MockedStatic<MapFactory> places = mockStatic(MapFactory.class)) {
            game.config.LETTERS_ENABLED = true;
            CompanionLettersTest.FakeNotes notes = letters(game.roster());
            relog(game);
            assertEquals(1, notes.sent.size(), String.valueOf(notes.messages()));
            String note = notes.messages().get(0);
            assertTrue(note.startsWith("Paperboy: ") && note.contains("Wedding bells for Ann and Ben"), note);
            assertTrue(note.length() <= "Paperboy: ".length() + 200);
            assertEquals(issue.weekOf(), seen.get(ANN));
            relog(game);
            assertEquals(1, notes.sent.size(), "the next login: none");
        }
    }

    @Test
    void readAtABoardFirstOrThePaperOffSendsNothing() throws IOException {
        PaperDesk desk = desk();
        try (LifecycleHarness game = new LifecycleHarness(); MockedStatic<MapFactory> places = mockStatic(MapFactory.class)) {
            game.config.LETTERS_ENABLED = true;
            CompanionLettersTest.FakeNotes notes = letters(game.roster());
            relog(game);
            assertTrue(notes.sent.isEmpty(), "PAPER_ENABLED off");
            config.PAPER_ENABLED = true;
            desk.seen(ANN, issue.weekOf()); // read at a board
            relog(game);
            assertTrue(notes.sent.isEmpty());
        }
    }
}
