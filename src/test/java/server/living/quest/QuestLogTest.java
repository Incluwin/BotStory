package server.living.quest;

import client.Character;
import client.QuestStatus;
import config.LivingWorldConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.LivingWorld;
import server.living.config.Content.Quest;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.store.LivingStore.QuestState;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The quest-log mirror sends the ledger's kill counts as the client's quest progress. */
class QuestLogTest {
    private static final LivingContent CONTENT = ContentLoader.load(Path.of("scripts/config/living-world"));
    private static final Quest NIGHT_LOG = CONTENT.quest("corvin-the-night-log");
    private static final int ZOMBIE = NIGHT_LOG.step(1).mobIds().get(0);

    private QuestLog questLog;
    private Character chr;
    private QuestStatus shown;

    @BeforeEach
    void setUp() {
        LivingWorld world = mock(LivingWorld.class);
        LivingWorldConfig config = new LivingWorldConfig();
        config.QUEST_LOG_MIRROR = true;
        when(world.config()).thenReturn(config);
        when(world.content()).thenReturn(CONTENT);
        questLog = new QuestLog(world);
        chr = mock(Character.class);
        int logId = CONTENT.questLogId(NIGHT_LOG.id());
        shown = new QuestStatus(server.quest.Quest.getInstance(logId), QuestStatus.Status.STARTED);
        shown.setProgress(ZOMBIE, "003");
        when(chr.getQuest(any(server.quest.Quest.class))).thenAnswer(inv -> shown);
    }

    private static QuestState state(int stepIndex, int progress) {
        return new QuestState(865, NIGHT_LOG.id(), stepIndex, progress,
                server.living.store.LivingStore.QuestStatus.ACTIVE, 0L, 0L, 0);
    }

    @Test
    void aKillSendsTheLedgerCountAsThreeDigits() {
        questLog.progressed(chr, NIGHT_LOG, state(1, 12));

        ArgumentCaptor<QuestStatus> sent = ArgumentCaptor.forClass(QuestStatus.class);
        verify(chr).updateQuestStatus(sent.capture());
        assertEquals(QuestStatus.Status.STARTED, sent.getValue().getStatus());
        assertEquals("012", sent.getValue().getProgress(ZOMBIE));
    }

    @Test
    void nothingIsSentWhenTheClientAlreadyShowsIt() {
        questLog.progressed(chr, NIGHT_LOG, state(1, 3));

        verify(chr, never()).updateQuestStatus(any());
    }

    @Test
    void aQuestTheLogDoesNotShowAsStartedIsLeftAlone() {
        shown = new QuestStatus(shown.getQuest(), QuestStatus.Status.NOT_STARTED);

        questLog.progressed(chr, NIGHT_LOG, state(1, 12));

        verify(chr, never()).updateQuestStatus(any());
    }

    @Test
    void theLivingRangeIsTheMirrors() {
        assertTrue(QuestLog.isMirrored(30000));
        assertTrue(QuestLog.isMirrored(32767));
        assertFalse(QuestLog.isMirrored(29933), "the highest stock v83 quest");
        assertFalse(QuestLog.isMirrored(2000));
    }
}
