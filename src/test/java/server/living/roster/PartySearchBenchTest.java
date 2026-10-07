package server.living.roster;

import client.Job;
import net.server.coordinator.partysearch.PartySearchWant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Party Search fills from the bench (docs/BOT_MANAGEMENT_PLAN.md flow 11, P9; decisions 2026-10-06): while the owner's
 * stock Party Search runs, one rested bench companion whose level and job it asks for answers each round no human
 * answered. It whispers that it saw the search and comes like a call (join-then-travel). The pick is prepared a round
 * ahead on companion-ops, so the first answer comes in the second round.
 */
class PartySearchBenchTest {
    private static final long SECOND = 1_000L;
    /** What a Fighter-only search for levels 25-40 asks for. */
    private static final PartySearchWant FIGHTERS = new PartySearchWant(25, 40, Set.of(Job.FIGHTER));

    private final LifecycleHarness game = new LifecycleHarness();
    /** Character ids of the bench companions that carry no HP potion. */
    private final Set<Integer> noPotions = new HashSet<>();
    private BenchDutyDispatcher bench;
    private PartySearchBench search;
    private Persona mira;
    private long now = 600 * SECOND;

    @BeforeEach
    void bench() {
        when(game.ann.getLevel()).thenReturn(30);
        mira = benched("mira", "Mira", 950, 31, Job.FIGHTER);
        game.roster();
        bench = game.world.benchDuty();
        when(bench.note(anyString())).thenReturn("");
        CrewRotation rested = new CrewRotation(game.world, () -> now, bot -> noPotions.contains(bot.getId()), noPotions::contains);
        search = new PartySearchBench(game.world, () -> now, rested::restedLevel);
    }

    @AfterEach
    void close() {
        game.close();
    }

    private Persona benched(String id, String name, int characterId, int level, Job job) {
        Persona persona = game.companion(id, name, characterId, RosterState.BENCHED, LITH);
        game.store.levels.put(characterId, level);
        game.store.jobs.put(characterId, job.getId());
        return persona;
    }

    /** One search round 10 s after the last; true when a bench companion answered it. */
    private boolean round(PartySearchWant want) {
        now += 10 * SECOND;
        return search.round(game.ann, want);
    }

    @Test
    void aRestedMatchingBenchCompanionAnswersTheSecondRoundAndComesLikeACall() {
        assertFalse(round(FIGHTERS), "the first round only prepares the pick");
        assertEquals(RosterState.BENCHED, game.row(mira).state());

        assertTrue(round(FIGHTERS));

        assertEquals(RosterState.TRAVELLING, game.row(mira).state());
        verify(game.residents).whisper(eq(mira), eq(game.ann), argThat(line -> line.contains("party search")));
    }

    @Test
    void aFourthJobCompanionAnswersForItsSecondJobGroup() {
        game.store.jobs.put(950, Job.HERO.getId());

        round(FIGHTERS);

        assertTrue(round(FIGHTERS));
    }

    @Test
    void outsideTheLevelRangeOrAJobNotSearchedNobodyAnswers() {
        PartySearchWant pages = new PartySearchWant(25, 40, Set.of(Job.PAGE));
        PartySearchWant low = new PartySearchWant(10, 30, Set.of(Job.FIGHTER));

        for (int i = 0; i < 3; i++) {
            assertFalse(round(pages));
        }
        for (int i = 0; i < 3; i++) {
            assertFalse(round(low));
        }

        assertEquals(RosterState.BENCHED, game.row(mira).state());
    }

    @Test
    void aBenchCompanionWithoutPotionsOrRestingAfterARoughHuntNeverAnswers() {
        noPotions.add(950);
        round(FIGHTERS);
        assertFalse(round(FIGHTERS));

        noPotions.clear();
        when(bench.note("mira")).thenReturn("resting after a rough hunt");
        round(FIGHTERS);
        assertFalse(round(FIGHTERS));

        assertEquals(RosterState.BENCHED, game.row(mira).state());
    }

    @Test
    void withNoSeatLeftInTheOwnersShareNobodyAnswers() {
        String[] names = {"Tobi", "Hadrik", "Jinx", "Sadie", "Vex"};
        for (int i = 0; i < names.length; i++) {
            game.outWithAnn(game.companion(names[i].toLowerCase(), names[i], 901 + i, RosterState.ACTIVE, LITH));
        }

        round(FIGHTERS);
        assertFalse(round(FIGHTERS));

        assertEquals(RosterState.BENCHED, game.row(mira).state());
    }

    @Test
    void aPickPreparedForAnotherSearchOrTooLongAgoDoesNotAnswer() {
        round(FIGHTERS);
        assertFalse(round(new PartySearchWant(25, 41, Set.of(Job.FIGHTER))), "the search changed: prepared again");

        now += PartySearchBench.PICK_TTL_MS;
        assertFalse(round(new PartySearchWant(25, 41, Set.of(Job.FIGHTER))), "the pick went stale");

        assertTrue(round(new PartySearchWant(25, 41, Set.of(Job.FIGHTER))));
    }

    @Test
    void theKillSwitchAnswersNothing() {
        game.config.COMPANION_PARTY_SEARCH_FILL = false;

        for (int i = 0; i < 3; i++) {
            assertFalse(round(FIGHTERS));
        }

        assertEquals(RosterState.BENCHED, game.row(mira).state());
    }

    @Test
    void theUsualLineupAnswersFirstThenTheClosestLevel() {
        Persona rurik = benched("rurik", "Rurik", 951, 30, Job.FIGHTER);
        Persona yara = benched("yara", "Yara", 952, 36, Job.FIGHTER);

        game.store.lineup.put(ANN + ":yara", 1);

        round(FIGHTERS);
        assertTrue(round(FIGHTERS));
        assertEquals(RosterState.TRAVELLING, game.row(yara).state(), "Yara is in the usual lineup");
        assertEquals(RosterState.BENCHED, game.row(rurik).state());

        assertTrue(round(FIGHTERS));
        assertEquals(RosterState.TRAVELLING, game.row(rurik).state(), "Rurik is level 30 like Ann");
        assertEquals(RosterState.BENCHED, game.row(mira).state());
    }

    @Test
    void aCompanionWhoseAnswerWasRefusedIsPassedOverForAnother() {
        Persona rurik = benched("rurik", "Rurik", 951, 33, Job.FIGHTER);
        round(FIGHTERS);
        when(game.residents.ensureCharacter(mira)).thenReturn(0); // her call fails: she cannot be reached

        round(FIGHTERS);
        round(FIGHTERS);

        assertEquals(RosterState.BENCHED, game.row(mira).state());
        assertEquals(RosterState.TRAVELLING, game.row(rurik).state());
        verify(game.residents, never()).whisper(eq(mira), any(), argThat(line -> line.contains("party search")));
    }

    @Test
    void aLeaderInsideAnEventInstanceGetsNoAnswer() {
        round(FIGHTERS);
        game.annEntersInstance(103000800);

        round(FIGHTERS);

        assertEquals(RosterState.BENCHED, game.row(mira).state());
        verify(game.residents, never()).whisper(eq(mira), any(), argThat(line -> line.contains("party search")));
    }

    @Test
    void aCompanionOnItsWayToItsInstructorIsNotAsked() {
        JobErrands errands = mock(JobErrands.class);
        when(errands.underway("mira")).thenReturn(true);
        when(game.world.jobErrands()).thenReturn(errands);

        round(FIGHTERS);
        assertFalse(round(FIGHTERS));

        assertEquals(RosterState.BENCHED, game.row(mira).state());
    }

    @Test
    void itsLinesAreAPlayersWords() {
        for (String line : PartySearchBench.SAW_SEARCH) {
            assertTrue(line.equals(line.toLowerCase()) && line.contains("party search") && !line.contains("Party Search"),
                    line);
        }
    }
}
