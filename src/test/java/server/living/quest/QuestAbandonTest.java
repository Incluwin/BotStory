package server.living.quest;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.roster.CompanionExecutor;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Forfeiting a living quest in the client's quest window abandons it in the ledger. */
class QuestAbandonTest {
    private static final LivingContent CONTENT = ContentLoader.load(Path.of("scripts/config/living-world"));
    private static final int ME = 865;

    private LivingWorld world;
    private LivingStore store;
    private QuestLog questLog;
    private CompanionExecutor executor;
    private Character chr;

    @BeforeEach
    void setUp() {
        world = mock(LivingWorld.class);
        store = mock(LivingStore.class);
        questLog = mock(QuestLog.class);
        executor = mock(CompanionExecutor.class);
        chr = mock(Character.class);
        when(world.content()).thenReturn(CONTENT);
        when(world.store()).thenReturn(store);
        when(world.questLog()).thenReturn(questLog);
        when(world.markers()).thenReturn(mock(QuestMarkers.class));
        when(world.executor()).thenReturn(executor);
        when(world.roster()).thenReturn(mock(RosterService.class));
        when(chr.getId()).thenReturn(ME);
    }

    private void holds(String questId, int stepIndex, QuestStatus status, int repeats) {
        when(store.questState(ME, questId)).thenReturn(new QuestState(ME, questId, stepIndex, 3, status, 1_000L, 0L, repeats));
    }

    @Test
    void aFirstTakeIsDroppedSoTheGiverOffersItAgain() {
        holds("wren-shelf-of-spores", 1, QuestStatus.ACTIVE, 0);

        assertTrue(new QuestLedger(world).abandon(chr, "wren-shelf-of-spores"));

        verify(store).deleteQuestState(ME, "wren-shelf-of-spores");
        verify(store, never()).saveQuestState(any());
        verify(questLog, never()).completed(any(), any());
        verify(chr).dropMessage(eq(5), argThat(line -> line.contains("Ink from Spores") && line.contains("Wren")));
    }

    @Test
    void aRepeatGoesBackToCompletedAndKeepsItsCount() {
        holds("wren-shelf-of-spores", 1, QuestStatus.ACTIVE, 2);

        assertTrue(new QuestLedger(world).abandon(chr, "wren-shelf-of-spores"));

        verify(store).saveQuestState(argThat(state -> state.status() == QuestStatus.COMPLETED && state.repeats() == 2
                && state.completedAtMs() == 1_000L));
        verify(store, never()).deleteQuestState(anyInt(), anyString());
        verify(questLog).completed(eq(chr), argThat(quest -> quest.id().equals("wren-shelf-of-spores")));
    }

    @Test
    void onlyAnActiveQuestCanBeAbandoned() {
        holds("wren-shelf-of-spores", 2, QuestStatus.COMPLETED, 1);

        assertFalse(new QuestLedger(world).abandon(chr, "wren-shelf-of-spores"));
        assertFalse(new QuestLedger(world).abandon(chr, "no-such-quest"));

        verify(store, never()).deleteQuestState(anyInt(), anyString());
        verify(store, never()).saveQuestState(any());
    }

    @Test
    void anEscortTheQuestStartedHeadsHome() {
        holds("thane-walk-me-down", 2, QuestStatus.ACTIVE, 0); // past the escort step, Thane still along
        when(store.rosterEntry(ME, "thane")).thenReturn(new LivingStore.RosterEntry(ME, "thane", LivingStore.RosterState.AWAY,
                LivingStore.Assignment.REST, 0, 0, 105050000, 0L, 0L, 0L, 0L, 0L, 0L, 0, null));

        assertTrue(new QuestLedger(world).abandon(chr, "thane-walk-me-down"));

        verify(executor).submit(eq("roster.escort-abandoned"), any(Runnable.class));
    }

    @Test
    void aRecruitedPersonaIsNotSentHomeByAnAbandonedEscort() {
        holds("thane-walk-me-down", 2, QuestStatus.ACTIVE, 0);
        when(store.rosterEntry(ME, "thane")).thenReturn(new LivingStore.RosterEntry(ME, "thane", LivingStore.RosterState.ACTIVE,
                LivingStore.Assignment.REST, 0, 0, 105050000, 0L, 0L, 0L, 0L, 0L, 0L, 0, null));

        assertTrue(new QuestLedger(world).abandon(chr, "thane-walk-me-down"));

        verify(executor, never()).submit(anyString(), any(Runnable.class));
    }

    @Test
    void theForfeitHookFindsTheQuestByItsLogId() {
        QuestLedger ledger = mock(QuestLedger.class);
        when(world.quests()).thenReturn(ledger);
        int logId = CONTENT.questLogId("wren-shelf-of-spores");
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(world);

            LivingWorldHooks.questForfeited(chr, logId);
            LivingWorldHooks.questForfeited(chr, 2000); // a stock quest

            verify(ledger).abandon(chr, "wren-shelf-of-spores");
            assertEquals(1, org.mockito.Mockito.mockingDetails(ledger).getInvocations().size());
        }
    }
}
