package server.living.population;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.PersonMemory.Kind;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the citizens of a run remember of the humans who came along (docs/CITIZEN_PERSON_MEMORY_PLAN.md): still in the
 * run's party at its end, a clear or a fail together; online and out of the party when it failed, a let-down; out of
 * it when it cleared, or offline (a crash), nothing.
 */
class CitizenPartyQuestsMemoryTest {
    private final CitizenLobbyFixture lobby = new CitizenLobbyFixture();
    private final CitizenPartyQuests quests = lobby.quests(new CitizenLobbyFixture.Door());
    private final PersonMemories memories = mock(PersonMemories.class);
    private Character lia;
    private Character mira;
    private Character ann;

    @BeforeEach
    void liaMiraAndAnnInOneParty() {
        when(lobby.population.memories()).thenReturn(memories);
        lia = lobby.citizen(1, "Lia", 25);
        mira = lobby.citizen(2, "Mira", 24);
        ann = lobby.human(40, "Ann", 26);
        lobby.parties.lead(lia, mira, ann);
    }

    @AfterEach
    void close() {
        lobby.close();
    }

    private CitizenPartyQuests.Run run(int... humanIds) {
        Map<Integer, String> citizens = new LinkedHashMap<>(Map.of(1, "Lia"));
        citizens.put(2, "Mira");
        Map<Integer, String> humans = new LinkedHashMap<>();
        for (int id : humanIds) {
            humans.put(id, "human" + id);
        }
        return new CitizenPartyQuests.Run("kpq", "kpq-run-1", 1, 1, citizens, humans, 0L);
    }

    @Test
    void aHumanStillInThePartyAtTheClearIsRememberedForIt() {
        quests.rememberHumans(run(40), true);

        verify(memories).record(1, 40, Kind.PQ_CLEAR, 0, "kpq", 1);
        verify(memories).record(2, 40, Kind.PQ_CLEAR, 0, "kpq", 1);
    }

    @Test
    void aFailTogetherIsRememberedAsOne() {
        quests.rememberHumans(run(40), false);

        verify(memories).record(1, 40, Kind.PQ_FAIL, 0, "kpq", 1);
        verify(memories).record(2, 40, Kind.PQ_FAIL, 0, "kpq", 1);
    }

    @Test
    void aHumanWhoLeftTheRunBeforeItFailedLetItDown() {
        lobby.parties.leaveCurrentParty(ann);

        quests.rememberHumans(run(40), false);

        verify(memories).record(1, 40, Kind.PQ_LEFT, 0, "kpq", 1);
        verify(memories).record(2, 40, Kind.PQ_LEFT, 0, "kpq", 1);
    }

    @Test
    void leavingARunThatStillClearedOrBeingOfflineIsNothing() {
        lobby.parties.leaveCurrentParty(ann);
        quests.rememberHumans(run(40), true);
        quests.rememberHumans(run(77), false); // 77 is offline: a crash is no let-down

        verify(memories, never()).record(anyInt(), anyInt(), any(), anyInt(), anyString(), anyInt());
    }

    @Test
    void withNoCitizenOfTheRunInItsPartyNobodyIsJudged() {
        lobby.parties.leaveCurrentParty(lia); // the leader leaving disbands it

        quests.rememberHumans(run(40), false);

        verify(memories, never()).record(anyInt(), anyInt(), any(), anyInt(), anyString(), anyInt());
    }
}
