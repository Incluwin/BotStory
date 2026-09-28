package server.living.resident;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scripting.npc.NPCConversationManager;
import scripting.quest.QuestActionManager;
import server.living.resident.LivingTalk.Option;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LivingTalk's client-safety rules (docs/BOT_MANAGEMENT_PLAN.md P1.5, docs/BOT_MANAGEMENT_P0_RESULTS.md probes 5
 * and 6): no speaker bit 0x04, "More..." paging, stamped options a stale click cannot pick, no second window while
 * one awaits a reply, the 500 ms click throttle, and every path ends in a window or enableActions. A talk whose
 * window cannot be on screen any more (a warp since, a stock window over it, a failed send) never blocks the next
 * window and never eats a stock window's reply.
 */
class LivingTalkTest {
    private static final byte MENU = 4;
    private static final byte SAY = 0;
    private static final byte YES_NO = 1;
    private static final int NPC = 9970001;

    private final Character chr = mock(Character.class);
    private final Client client = mock(Client.class);

    @BeforeEach
    void wire() {
        when(chr.getId()).thenReturn(10);
        when(chr.getName()).thenReturn("Ann");
        when(chr.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(chr);
        when(client.canClickNPC()).thenReturn(true);
    }

    @AfterEach
    void forget() {
        LivingTalk.forget(chr);
    }

    // ---- speaker -------------------------------------------------------------------------

    @Test
    void speakerBytesWithBit0x04AreRejectedBeforeAnythingIsSent() {
        for (int speaker : new int[] {4, 5, 6, 7, 0x0C, 0x14, 0xFF}) {
            assertThrows(IllegalArgumentException.class, () -> LivingTalk.open(chr, NPC).speaker(speaker));
        }
        assertThrows(IllegalArgumentException.class, () -> LivingTalk.open(chr, NPC).speaker(-1));
        assertThrows(IllegalArgumentException.class, () -> LivingTalk.open(chr, NPC).speaker(0x100));
        verify(client, never()).sendPacket(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theSpeakerByteIsSentOnEveryWindowAndDefaultsToZero() {
        LivingTalk.open(chr, NPC).ok("hi").show();
        assertEquals(0, lastTalk().speaker());
        answer(SAY, 1, null);

        LivingTalk.open(chr, 9040011).speaker(2).say("page").yesNo("sure?", null, null).show();
        Talk page = lastTalk();
        assertEquals(2, page.speaker());
        assertEquals(9040011, page.npcId());
        answer(SAY, 1, null);
        assertEquals(2, lastTalk().speaker());
        assertEquals(YES_NO, lastTalk().msgType());
    }

    // ---- paging --------------------------------------------------------------------------

    @Test
    void aLongMenuPagesWithMoreAndTheNextPageIsTheAnswerToTheClick() {
        AtomicInteger picked = new AtomicInteger(-1);
        List<Option> options = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            int chosen = index;
            options.add(new Option("row " + index, () -> picked.set(chosen)));
        }
        LivingTalk.open(chr, NPC).menu("pick one", options).show();

        Talk first = lastTalk();
        assertEquals(MENU, first.msgType());
        assertEquals(LivingTalk.MENU_PAGE_SIZE + 1, first.labels().size());
        assertEquals("row 0", first.labels().get(0));
        assertEquals("More...", first.labels().get(LivingTalk.MENU_PAGE_SIZE));

        answer(MENU, 1, first.selection("More..."));
        Talk second = lastTalk();
        assertEquals(List.of("row 10", "row 11", "row 12", "More..."), second.labels());
        assertEquals(-1, picked.get());

        answer(MENU, 1, second.selection("row 11"));
        assertEquals(11, picked.get());
        assertFalse(LivingTalk.active(client));
        assertTrue(NpcTalks.isEnableActions(lastPacket()));
    }

    @Test
    void headingsAreTextNotOptionsAndDoNotCountTowardAPage() {
        List<Option> options = new ArrayList<>();
        options.add(Option.heading("#eParty#n"));
        for (int index = 0; index < 10; index++) {
            options.add(new Option("row " + index, null));
        }
        LivingTalk.open(chr, NPC).menu("crew", options).show();

        Talk talk = lastTalk();
        assertEquals(10, talk.labels().size()); // no "More...": ten options fit on one page
        assertTrue(talk.text().startsWith("crew\r\n#eParty#n\r\n#L"));
    }

    // ---- stamps --------------------------------------------------------------------------

    @Test
    void aClickOnAnOlderWindowIsIgnoredAndTheWindowOnScreenStays() {
        AtomicInteger runs = new AtomicInteger();
        List<Option> options = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            options.add(new Option("row " + index, runs::incrementAndGet));
        }
        LivingTalk.open(chr, NPC).menu("pick", options).show();
        Talk first = lastTalk();
        answer(MENU, 1, first.selection("More..."));
        clearInvocations(client);

        answer(MENU, 1, first.selection("row 3")); // the first page again: a stale click

        assertEquals(0, runs.get());
        assertTrue(LivingTalk.active(client));
        verify(client, never()).sendPacket(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aDoubleClickRunsTheOptionOnceAndNeverPicksFromTheNextWindow() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        LivingTalk.open(chr, NPC).menu("pick", List.of(
                new Option("open the next", () -> {
                    first.incrementAndGet();
                    LivingTalk.open(chr, NPC).menu("next", List.of(new Option("a", second::incrementAndGet),
                            new Option("b", second::incrementAndGet))).show();
                }))).show();
        int click = lastTalk().selection("open the next");

        answer(MENU, 1, click);
        Talk next = lastTalk();
        assertEquals("next", next.text().substring(0, 4));
        clearInvocations(client);
        answer(MENU, 1, click); // the duplicate

        assertEquals(1, first.get());
        assertEquals(0, second.get());
        assertTrue(LivingTalk.active(client));
        verify(client, never()).sendPacket(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anAnswerOfAnotherWindowTypeIsIgnored() {
        AtomicInteger runs = new AtomicInteger();
        LivingTalk.open(chr, NPC).menu("pick", List.of(new Option("go", runs::incrementAndGet))).show();
        clearInvocations(client);

        answer(SAY, 1, null); // a "Next" from an older say window

        assertEquals(0, runs.get());
        assertTrue(LivingTalk.active(client));
        verify(client, never()).sendPacket(org.mockito.ArgumentMatchers.any());
    }

    // ---- one window at a time ------------------------------------------------------------

    @Test
    void noSecondWindowIsSentWhileOneAwaitsAReply() {
        LivingTalk.open(chr, NPC).ok("first").show();
        clearInvocations(client);

        LivingTalk.open(chr, NPC).ok("second").show();

        List<Packet> sent = sent();
        assertEquals(1, sent.size());
        assertTrue(NpcTalks.isEnableActions(sent.get(0)));
        answer(SAY, 1, null);
        assertFalse(LivingTalk.active(client)); // the reply closed the first window, not a phantom second one
    }

    @Test
    void showingTheSameTalkTwiceSendsOneWindow() {
        LivingTalk talk = LivingTalk.open(chr, NPC).ok("once");
        talk.show();
        talk.show();
        assertEquals(1, NpcTalks.talks(sent()).size());
    }

    @Test
    void aScriptWindowAwaitingItsReplyBlocksTheTalk() {
        NPCConversationManager script = mock(NPCConversationManager.class);
        when(script.awaitingReply()).thenReturn(true);
        when(client.getCM()).thenReturn(script);

        LivingTalk.open(chr, NPC).ok("hi").show();

        List<Packet> sent = sent();
        assertEquals(1, sent.size());
        assertTrue(NpcTalks.isEnableActions(sent.get(0)));
        verify(script, never()).dispose();
        assertFalse(LivingTalk.active(client));
    }

    @Test
    void aLeftoverScriptConversationWithNoWindowIsDisposedFirst() {
        NPCConversationManager script = mock(NPCConversationManager.class);
        when(client.getCM()).thenReturn(script);

        LivingTalk.open(chr, NPC).ok("hi").show();

        verify(script).dispose();
        assertEquals("hi", lastTalk().text());
    }

    @Test
    void aTalkLeftFromAnEarlierLoginDoesNotBlockTheNewOne() {
        LivingTalk.open(chr, NPC).ok("old session").show();
        Character relogged = mock(Character.class);
        Client newClient = mock(Client.class);
        when(relogged.getId()).thenReturn(10);
        when(relogged.getClient()).thenReturn(newClient);
        when(newClient.getPlayer()).thenReturn(relogged);
        when(newClient.canClickNPC()).thenReturn(true);

        LivingTalk.open(relogged, NPC).ok("new session").show();

        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(newClient).sendPacket(captor.capture());
        assertEquals("new session", NpcTalks.read(captor.getValue()).text());
        LivingTalk.forget(relogged);
    }

    @Test
    void theClickThrottleRefusesAFreshWindowButNotTheAnswerToAClick() {
        when(client.canClickNPC()).thenReturn(false);
        LivingTalk.open(chr, NPC).ok("too soon").show();
        assertEquals(0, NpcTalks.talks(sent()).size());
        assertTrue(NpcTalks.isEnableActions(lastPacket()));

        when(client.canClickNPC()).thenReturn(true);
        LivingTalk.open(chr, NPC).menu("pick", List.of(new Option("next",
                () -> LivingTalk.open(chr, NPC).ok("answer").show()))).show();
        when(client.canClickNPC()).thenReturn(false); // the player clicks within 500 ms
        answer(MENU, 1, lastTalk().selection("next"));

        assertEquals("answer", lastTalk().text());
        verify(client, atLeastOnce()).setClickedNPC();
    }

    // ---- always a window or enableActions ------------------------------------------------

    @Test
    void everyClosingPathReEnablesTheClient() {
        // End Chat on a menu
        LivingTalk.open(chr, NPC).menu("pick", List.of(new Option("go", null))).show();
        answer(MENU, 0, null);
        assertClosed();
        // an option with no action ("Never mind")
        LivingTalk.open(chr, NPC).menu("pick", List.of(new Option("never mind", null))).show();
        answer(MENU, 1, lastTalk().selection("never mind"));
        assertClosed();
        // an option whose action fails
        LivingTalk.open(chr, NPC).menu("pick", List.of(new Option("boom", () -> {
            throw new IllegalStateException("boom");
        }))).show();
        answer(MENU, 1, lastTalk().selection("boom"));
        assertClosed();
        // an action that opens nothing
        LivingTalk.open(chr, NPC).yesNo("sure?", () -> { }, null).show();
        answer(YES_NO, 0, null);
        assertClosed();
        // OK
        LivingTalk.open(chr, NPC).ok("done").show();
        answer(SAY, 1, null);
        assertClosed();
        // an unreadable selection
        LivingTalk.open(chr, NPC).menu("pick", List.of(new Option("go", null))).show();
        answer(MENU, 1, 7);
        assertClosed();
        // an empty talk
        LivingTalk.open(chr, NPC).show();
        assertTrue(NpcTalks.isEnableActions(lastPacket()));
    }

    @Test
    void anActionThatOpensTheNextWindowDoesNotReEnableFirst() {
        LivingTalk.open(chr, NPC).menu("pick", List.of(new Option("next",
                () -> LivingTalk.open(chr, NPC).ok("second").show()))).show();
        int click = lastTalk().selection("next");
        clearInvocations(client);

        answer(MENU, 1, click);

        List<Packet> sent = sent();
        assertEquals(1, sent.size());
        assertEquals("second", NpcTalks.read(sent.get(0)).text());
        assertTrue(LivingTalk.active(client));
    }

    @Test
    void aWarpForgetsTheWindowSoTheNextOneCanOpen() {
        LivingTalk.open(chr, NPC).ok("first").show();
        LivingTalk.forget(chr);
        LivingTalk.open(chr, NPC).ok("after the warp").show();
        assertEquals("after the warp", lastTalk().text());
    }

    // ---- stale talks ---------------------------------------------------------------------

    @Test
    void aTalkShownJustBeforeAWarpIsGoneOnTheNewMap() {
        // a quest step finishing on map entry opens its window before SET_FIELD, which closes it on the client
        MapleMap before = mock(MapleMap.class);
        when(chr.getMap()).thenReturn(before);
        LivingTalk.open(chr, NPC).ok("shown before the warp").show();
        when(chr.getMap()).thenReturn(mock(MapleMap.class));

        assertFalse(LivingTalk.respond(client, SAY, (byte) 1, Packets.buildInPacket(out -> { })));
        assertFalse(LivingTalk.active(client));
        LivingTalk.open(chr, NPC).ok("on the new map").show();
        assertEquals("on the new map", lastTalk().text());
    }

    @Test
    void aStockWindowOverALeftoverTalkGetsItsOwnReply() {
        LivingTalk.open(chr, NPC).ok("left over").show();
        NPCConversationManager script = mock(NPCConversationManager.class);
        when(script.awaitingReply()).thenReturn(true);
        when(client.getCM()).thenReturn(script);

        // the stock handler answers it; the talk never closes the stock prompt's reply
        assertFalse(LivingTalk.respond(client, SAY, (byte) 1, Packets.buildInPacket(out -> { })));
        assertFalse(LivingTalk.active(client));
    }

    @Test
    void theQuestAndTheNpcScriptAreBothChecked() {
        QuestActionManager quest = mock(QuestActionManager.class);
        NPCConversationManager npc = mock(NPCConversationManager.class);
        when(client.getQM()).thenReturn(quest);
        when(client.getCM()).thenReturn(npc);
        when(npc.awaitingReply()).thenReturn(true);

        LivingTalk.open(chr, NPC).ok("hi").show();
        assertEquals(0, NpcTalks.talks(sent()).size());
        verify(quest, never()).dispose();

        when(npc.awaitingReply()).thenReturn(false);
        LivingTalk.open(chr, NPC).ok("hi").show();
        verify(quest).dispose(); // neither had a window on screen: both are left-overs
        verify(npc).dispose();
        assertEquals("hi", lastTalk().text());
    }

    @Test
    void aWindowThatCouldNotBeSentLeavesNothingWaiting() {
        doThrow(new IllegalStateException("send failed")).doNothing().when(client).sendPacket(org.mockito.ArgumentMatchers.any());

        LivingTalk.open(chr, NPC).ok("hi").show();

        assertFalse(LivingTalk.active(client));
        assertTrue(NpcTalks.isEnableActions(lastPacket()));
        doNothing().when(client).sendPacket(org.mockito.ArgumentMatchers.any());
        LivingTalk.open(chr, NPC).ok("next one").show();
        assertEquals("next one", lastTalk().text());
    }

    @Test
    void mayShowAnswersWithoutSendingOrDisposingAnything() {
        NPCConversationManager leftover = mock(NPCConversationManager.class);
        when(client.getCM()).thenReturn(leftover);
        assertTrue(LivingTalk.mayShow(chr));
        verify(leftover, never()).dispose();

        LivingTalk.open(chr, NPC).ok("up").show();
        clearInvocations(client);
        assertFalse(LivingTalk.mayShow(chr));
        LivingTalk.forget(chr);
        when(client.canClickNPC()).thenReturn(false);
        assertFalse(LivingTalk.mayShow(chr));
        verify(client, never()).sendPacket(org.mockito.ArgumentMatchers.any());
    }

    // ---- helpers -------------------------------------------------------------------------

    private void answer(byte lastMsg, int action, Integer selection) {
        assertTrue(LivingTalk.respond(client, lastMsg, (byte) action, Packets.buildInPacket(out -> {
            if (selection != null) {
                out.writeInt(selection);
            }
        })));
    }

    private void assertClosed() {
        assertFalse(LivingTalk.active(client));
        assertTrue(NpcTalks.isEnableActions(lastPacket()));
    }

    private List<Packet> sent() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues();
    }

    private Packet lastPacket() {
        List<Packet> sent = sent();
        return sent.get(sent.size() - 1);
    }

    private Talk lastTalk() {
        List<Talk> talks = NpcTalks.talks(sent());
        return talks.get(talks.size() - 1);
    }
}
