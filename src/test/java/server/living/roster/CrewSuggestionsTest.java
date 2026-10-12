package server.living.roster;

import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.config.Content.Persona;
import server.living.roster.CrewSuggestions.Suggestion;
import server.living.store.LivingStore.CompanionRunRow;
import server.living.store.LivingStore.RosterState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * "Who should come along?" (P9 learned lineup suggestions; decisions 2026-10-06): a crew for each party quest switched
 * on whose levels take the owner, picked by the desks' own rule (LineupPlan) from the owner, their party and their
 * rested bench companions in the quest's levels; the bench companions picked are the ones to call. A companion's clear
 * record counts once it has three runs, so a suggestion changes after recorded runs.
 */
class CrewSuggestionsTest {
    private final LifecycleHarness game = new LifecycleHarness();
    /** Character ids of the bench companions that carry no HP potion. */
    private final Set<Integer> noPotions = new HashSet<>();
    private CompanionRunRecorder records;
    private CrewSuggestions suggestions;

    @BeforeEach
    void ann() {
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        when(game.ann.getLevel()).thenReturn(25);
        when(game.ann.getJob()).thenReturn(Job.HUNTER);
        game.roster();
        when(game.world.benchDuty().note(anyString())).thenReturn("");
        records = new CompanionRunRecorder(game.world);
        CompanionPartyQuestService quests = mock(CompanionPartyQuestService.class);
        when(quests.records()).thenReturn(records);
        when(game.world.partyQuests()).thenReturn(quests);
        CrewRotation rested = new CrewRotation(game.world, () -> 0L, bot -> noPotions.contains(bot.getId()), noPotions::contains);
        suggestions = new CrewSuggestions(game.world, rested::restedLevel);
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

    private Suggestion of(String pqKey) {
        return suggestions.suggest(game.ann).stream().filter(s -> s.pqKey().equals(pqKey)).findFirst().orElse(null);
    }

    private void clears(int characterId, String pqKey, int runs) {
        for (int i = 0; i < runs; i++) {
            game.store.performance.add(new CompanionRunRow(ANN, characterId, "p" + characterId, pqKey, "cleared", 600, 0, 4));
        }
        records.load();
    }

    @Test
    void eachQuestThatTakesTheOwnerGetsACrewFromTheBenchInItsLevels() {
        Persona mira = benched("mira", "Mira", 950, 26, Job.FIGHTER);
        Persona jinx = benched("jinx", "Jinx", 951, 27, Job.CLERIC);
        Persona tobi = benched("tobi", "Tobi", 953, 24, Job.HUNTER);
        benched("rurik", "Rurik", 952, 40, Job.FIGHTER); // past KPQ's levels, and far off Ann's for HPQ

        Suggestion kpq = of("kpq");
        Suggestion hpq = of("hpq");

        assertEquals(List.of("you", "Mira", "Jinx", "Tobi"), kpq.crew(), "a new job branch first, then the closest level");
        assertEquals(List.of(mira, jinx, tobi), kpq.fromBench());
        assertEquals(List.of(mira, jinx, tobi), kpq.own());
        assertEquals(List.of("you", "Mira", "Jinx", "Tobi"), hpq.crew(), "Rurik is 15 levels off: he stays at camp");
        assertEquals(List.of("kpq", "hpq"), suggestions.suggest(game.ann).stream().map(Suggestion::pqKey).toList());
    }

    @Test
    void aCompanionOutWithTheOwnerGoesInWithoutACallAndOneNotRestedIsLeftAtCamp() {
        Persona tobi = game.companion("tobi", "Tobi", 901, RosterState.ACTIVE, LITH);
        game.outWithAnn(tobi);
        when(game.bot(tobi).getLevel()).thenReturn(24);
        when(game.bot(tobi).getJob()).thenReturn(Job.HUNTER);
        Persona mira = benched("mira", "Mira", 950, 26, Job.FIGHTER);
        benched("jinx", "Jinx", 951, 27, Job.CLERIC);
        noPotions.add(951);

        Suggestion kpq = of("kpq");

        assertTrue(kpq.crew().contains("Tobi"), kpq.toString());
        assertTrue(kpq.crew().contains("Mira"), kpq.toString());
        assertEquals(List.of(mira), kpq.fromBench(), "Jinx carries no potions");
    }

    @Test
    void theSuggestionChangesOnceACompanionsRecordShows() {
        for (String name : List.of("Jinx", "Pip", "Sadie", "Vex", "Yara")) {
            benched(name.toLowerCase(), name, 950 + name.charAt(0), 25, Job.CLERIC);
        }
        assertEquals(List.of("you", "Jinx", "Pip", "Sadie"), of("hpq").crew(), "alike: by name");

        clears(950 + 'Y', "hpq", 2);
        assertEquals(List.of("you", "Jinx", "Pip", "Sadie"), of("hpq").crew(), "two runs are too few to count");

        clears(950 + 'Y', "hpq", 1);
        assertEquals(List.of("you", "Yara (cleared 3 of 3)", "Jinx", "Pip"), of("hpq").crew());
    }

    @Test
    void oneQuestsCrewIsItsEntryInTheListAndNoneWhenSwitchedOffOrNotForTheOwnersLevel() {
        benched("mira", "Mira", 950, 26, Job.FIGHTER);
        assertEquals(of("kpq"), suggestions.suggest(game.ann, "kpq"));
        assertEquals(of("hpq"), suggestions.suggest(game.ann, "hpq"));

        game.config.COMPANION_PQ_ENABLED_KEYS = "hpq";
        assertNull(suggestions.suggest(game.ann, "kpq"));

        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        when(game.ann.getLevel()).thenReturn(35);
        assertNull(suggestions.suggest(game.ann, "kpq"));
    }

    @Test
    void aQuestSwitchedOffOrNotForTheOwnersLevelIsNotSuggested() {
        benched("mira", "Mira", 950, 26, Job.FIGHTER);
        game.config.COMPANION_PQ_ENABLED_KEYS = "hpq";
        assertEquals(List.of("hpq"), suggestions.suggest(game.ann).stream().map(Suggestion::pqKey).toList());

        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        when(game.ann.getLevel()).thenReturn(35);
        assertEquals(List.of("hpq"), suggestions.suggest(game.ann).stream().map(Suggestion::pqKey).toList());
    }
}
