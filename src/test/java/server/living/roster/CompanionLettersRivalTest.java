package server.living.roster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import server.living.population.Population;
import server.living.population.rivals.RivalCrews;
import server.maps.MapFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * The rival crew in the letters (docs/BOT_CONCEPT_PLAN.md F7.3): its clears while its player was away come as one note
 * signed by its leader, inside LETTERS_MAX_NOTES, and the pink line names the writer; with nothing from the crew there
 * is no note.
 */
class CompanionLettersRivalTest {
    private static final String NEWS = "Ironclads|BalrogBattle_Easy|840000|960000";

    @TempDir
    Path dir;

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

    @Test
    void theCrewsClearsWhileAwayAreOneNoteFromItsLeader() throws IOException {
        Population population = mock(Population.class);
        RivalCrews rivals = mock(RivalCrews.class);
        when(population.rivals()).thenReturn(rivals);
        when(rivals.letter(ANN, "Ann", List.of(NEWS))).thenReturn(new RivalCrews.Letter("Kim",
                "hey Ann, Ironclads here. while u were gone we did easy balrog in 14:00 (beat ur 16:00 :P). catch up if u can lol"));
        try (LifecycleHarness game = new LifecycleHarness(); MockedStatic<MapFactory> places = mockStatic(MapFactory.class);
             MockedStatic<Population> populations = mockStatic(Population.class)) {
            populations.when(Population::get).thenReturn(population);
            game.config.LETTERS_ENABLED = true;
            CompanionLettersTest.FakeNotes notes = letters(game.roster());

            game.roster().onOwnerLogout(game.ann, null);
            game.store.store.event(ANN, null, CompanionLetters.RIVAL_NEWS_EVENT, NEWS);
            game.roster().onOwnerLogin(game.ann);
            game.runTimers();

            assertEquals(List.of("Kim: hey Ann, Ironclads here. while u were gone we did easy balrog in 14:00 (beat ur 16:00"
                    + " :P). catch up if u can lol"), notes.messages());
            assertEquals(1, notes.shown);
            verify(game.ann).dropMessage(5, "While you were away, Kim wrote to you.");
        }
    }

    @Test
    void noCrewNewsNoNote() throws IOException {
        Population population = mock(Population.class);
        RivalCrews rivals = mock(RivalCrews.class);
        when(population.rivals()).thenReturn(rivals);
        try (LifecycleHarness game = new LifecycleHarness(); MockedStatic<MapFactory> places = mockStatic(MapFactory.class);
             MockedStatic<Population> populations = mockStatic(Population.class)) {
            populations.when(Population::get).thenReturn(population);
            game.config.LETTERS_ENABLED = true;
            CompanionLettersTest.FakeNotes notes = letters(game.roster());

            game.roster().onOwnerLogout(game.ann, null);
            game.roster().onOwnerLogin(game.ann);
            game.runTimers();

            assertTrue(notes.sent.isEmpty());
            verify(rivals, never()).letter(anyInt(), anyString(), anyList());
        }
    }

    @Test
    void theLettersReadTheRowsTheCrewsWrite() {
        assertEquals(RivalCrews.NEWS_EVENT, CompanionLetters.RIVAL_NEWS_EVENT);
    }
}
