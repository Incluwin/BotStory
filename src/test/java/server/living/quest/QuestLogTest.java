package server.living.quest;

import client.Character;
import client.QuestStatus;
import config.LivingWorldConfig;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.LivingWorld;
import server.living.config.Content.Quest;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.store.LivingStore;
import server.living.store.LivingStore.QuestState;
import tools.PacketCreator;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The quest-log mirror sends the ledger's kill counts as the client's quest record (three digits per monster, in
 * Check.img/1 order), which the Quest Helper and the detail text's {@code #a} codes read; a kill sends only the record,
 * as the stock kill counter does.
 */
class QuestLogTest {
    private static final LivingContent CONTENT = ContentLoader.load(Path.of("scripts/config/living-world"));
    private static final Quest NIGHT_LOG = CONTENT.quest("corvin-the-night-log");
    private static final int ZOMBIE = NIGHT_LOG.step(1).mobIds().get(0);
    /** Fenna's "The Pig Problem": 20 Pigs, then 10 Ribbon Pigs. */
    private static final Quest PIG_PROBLEM = CONTENT.quest("fenna-pig-problem");
    private static final int PIG = 1210100;
    private static final int RIBBON_PIG = 1210101;

    private QuestLog questLog;
    private LivingStore store;
    private Character chr;
    private QuestStatus shown;

    @BeforeEach
    void setUp() {
        LivingWorld world = mock(LivingWorld.class);
        LivingWorldConfig config = new LivingWorldConfig();
        config.QUEST_LOG_MIRROR = true;
        when(world.config()).thenReturn(config);
        when(world.content()).thenReturn(CONTENT);
        store = mock(LivingStore.class);
        when(world.store()).thenReturn(store);
        questLog = new QuestLog(world);
        chr = mock(Character.class);
        when(chr.getId()).thenReturn(865);
        shown = started(NIGHT_LOG);
        shown.setProgress(ZOMBIE, "003");
        when(chr.getQuest(any(server.quest.Quest.class))).thenAnswer(inv -> shown);
    }

    private static QuestStatus started(Quest quest) {
        return new QuestStatus(server.quest.Quest.getInstance(CONTENT.questLogId(quest.id())), QuestStatus.Status.STARTED);
    }

    private static QuestState state(Quest quest, int stepIndex, int progress) {
        return new QuestState(865, quest.id(), stepIndex, progress, LivingStore.QuestStatus.ACTIVE, 0L, 0L, 0);
    }

    private static InPacket read(Packet packet) {
        return new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
    }

    private void verifyNothingSent() {
        verify(chr, never()).updateQuestStatus(any());
        verify(chr, never()).announceUpdateQuest(any(), any(), any());
    }

    @Test
    void aKillSendsOnlyTheRecordAsThreeDigits() {
        questLog.progressed(chr, NIGHT_LOG, state(NIGHT_LOG, 1, 12));

        verify(chr).announceUpdateQuest(Character.DelayedQuestUpdate.UPDATE, shown, false);
        verify(chr, never()).announceUpdateQuest(eq(Character.DelayedQuestUpdate.INFO), any());
        verify(chr, never()).updateQuestStatus(any());
        assertEquals("012", shown.getProgress(ZOMBIE));

        InPacket packet = read(PacketCreator.updateQuest(chr, shown, false));
        assertEquals(SendOpcode.SHOW_STATUS_INFO.getValue(), packet.readShort());
        assertEquals(1, packet.readByte(), "quest record");
        assertEquals(CONTENT.questLogId(NIGHT_LOG.id()), packet.readShort());
        assertEquals(1, packet.readByte(), "started");
        assertEquals("012", packet.readString());
    }

    @Test
    void nothingIsSentWhenTheClientAlreadyShowsIt() {
        questLog.progressed(chr, NIGHT_LOG, state(NIGHT_LOG, 1, 3));

        verifyNothingSent();
    }

    @Test
    void aQuestTheLogDoesNotShowAsStartedIsLeftAlone() {
        shown = new QuestStatus(shown.getQuest(), QuestStatus.Status.NOT_STARTED);

        questLog.progressed(chr, NIGHT_LOG, state(NIGHT_LOG, 1, 12));

        verifyNothingSent();
    }

    @Test
    void acceptStartsTheQuestWithEveryMonsterAtZeroInCheckOrder() {
        shown = new QuestStatus(started(PIG_PROBLEM).getQuest(), QuestStatus.Status.NOT_STARTED);

        questLog.started(chr, PIG_PROBLEM);

        ArgumentCaptor<QuestStatus> sent = ArgumentCaptor.forClass(QuestStatus.class);
        verify(chr).updateQuestStatus(sent.capture());
        assertEquals(QuestStatus.Status.STARTED, sent.getValue().getStatus());
        assertEquals(List.of(PIG, RIBBON_PIG), List.copyOf(sent.getValue().getProgress().keySet()));
        assertEquals("000000", sent.getValue().getProgressData());
    }

    @Test
    void theNextStepShowsTheEarlierInFullAndCountsItsOwnSlot() {
        shown = started(PIG_PROBLEM);
        shown.setProgress(PIG, "019");
        shown.setProgress(RIBBON_PIG, "000");

        questLog.progressed(chr, PIG_PROBLEM, state(PIG_PROBLEM, 2, 4));

        verify(chr).announceUpdateQuest(Character.DelayedQuestUpdate.UPDATE, shown, false);
        assertEquals("020004", shown.getProgressData(), "#a300141# reads 20, #a300142# reads 4");
        InPacket packet = read(PacketCreator.updateQuest(chr, shown, false));
        packet.readShort();
        packet.readByte();
        assertEquals(30014, packet.readShort());
        packet.readByte();
        assertEquals("020004", packet.readString());
    }

    @Test
    void aRecordInAnotherOrderIsReplacedWhole() {
        shown = started(PIG_PROBLEM);
        shown.setProgress(RIBBON_PIG, "000");
        shown.setProgress(PIG, "012");

        questLog.progressed(chr, PIG_PROBLEM, state(PIG_PROBLEM, 1, 13));

        ArgumentCaptor<QuestStatus> sent = ArgumentCaptor.forClass(QuestStatus.class);
        verify(chr).updateQuestStatus(sent.capture());
        assertEquals(List.of(PIG, RIBBON_PIG), List.copyOf(sent.getValue().getProgress().keySet()));
        assertEquals("013000", sent.getValue().getProgressData());
    }

    @Test
    void loginDropsAMonsterTheQuestNoLongerTracks() {
        shown = started(NIGHT_LOG);
        shown.setProgress(100100, "005");
        shown.setProgress(ZOMBIE, "003");
        when(store.questStates(865, null)).thenReturn(List.of(state(NIGHT_LOG, 1, 3)));

        questLog.reconcile(chr);

        ArgumentCaptor<QuestStatus> sent = ArgumentCaptor.forClass(QuestStatus.class);
        verify(chr).updateQuestStatus(sent.capture());
        assertEquals(List.of(ZOMBIE), List.copyOf(sent.getValue().getProgress().keySet()));
        assertEquals("003", sent.getValue().getProgressData());
    }

    @Test
    void loginCatchesTheRecordUpWithTheLedger() {
        when(store.questStates(865, null)).thenReturn(List.of(state(NIGHT_LOG, 1, 7)));

        questLog.reconcile(chr);

        verify(chr).announceUpdateQuest(Character.DelayedQuestUpdate.UPDATE, shown, false);
        verify(chr, never()).updateQuestStatus(any());
        assertEquals("007", shown.getProgressData());
    }

    @Test
    void theLivingRangeIsTheMirrors() {
        assertTrue(QuestLog.isMirrored(30000));
        assertTrue(QuestLog.isMirrored(32767));
        assertFalse(QuestLog.isMirrored(29933), "the highest stock v83 quest");
        assertFalse(QuestLog.isMirrored(2000));
    }
}
