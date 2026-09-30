package server.living.resident;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import scripting.npc.NPCConversationManager;
import server.living.resident.LivingTalk.Option;
import testutil.NpcTalks;
import testutil.Packets;
import tools.PacketCreator;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A stock NPC script handing the player's click to a living-world screen (P6, the Lakelis hook): the screen's window is
 * the answer to that click, though the click itself just stamped the 500 ms throttle; afterwards exactly one window is
 * on screen, or none and the client's actions are back with the script disposed. Nothing is sent while a window awaits
 * its reply (a second NPC_TALK drops the client, P0 probe 6) or off the player's own thread.
 */
class LivingTalkScriptClickTest {
    private static final int NPC = 9020000;

    private final Character chr = mock(Character.class);
    private final Client client = mock(Client.class);
    private final NPCConversationManager script = mock(NPCConversationManager.class);

    @BeforeEach
    void inTheLakelisScript() {
        when(chr.getId()).thenReturn(10);
        when(chr.getName()).thenReturn("Ann");
        when(chr.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(chr);
        when(client.onIoThread()).thenReturn(true);
        when(client.canClickNPC()).thenReturn(false); // the click that started the script stamped the throttle
        when(client.getCM()).thenReturn(script);
        doAnswer(inv -> {
            client.sendPacket(PacketCreator.enableActions()); // what the real dispose sends
            when(client.getCM()).thenReturn(null);
            return null;
        }).when(script).dispose();
    }

    @AfterEach
    void forget() {
        LivingTalk.forget(chr);
    }

    @Test
    void theScreensWindowIsTheAnswerToTheScriptsClick() {
        boolean answered = LivingTalk.answerScriptClick(chr,
                () -> LivingTalk.open(chr, NPC).menu("Who comes along?", List.of(new Option("Go", null))).show());

        assertTrue(answered);
        verify(script, times(1)).dispose();
        List<Packet> sent = sent();
        assertEquals(2, sent.size(), "the script's enableActions, then the window");
        assertEquals("Who comes along?", NpcTalks.read(sent.get(1)).text().substring(0, 16));
        assertTrue(LivingTalk.active(client));
    }

    @Test
    void aScreenThatShowsNothingLeavesTheScriptDisposedAndTheClientFree() {
        assertTrue(LivingTalk.answerScriptClick(chr, () -> { }));

        verify(script, times(1)).dispose();
        List<Packet> sent = sent();
        assertEquals(1, sent.size());
        assertTrue(NpcTalks.isEnableActions(sent.get(0)));
        assertFalse(LivingTalk.active(client));

        when(client.getCM()).thenReturn(null); // no script conversation left to dispose
        assertTrue(LivingTalk.answerScriptClick(chr, () -> {
            throw new IllegalStateException("the screen failed");
        }));
        assertEquals(2, sent().size());
        assertTrue(NpcTalks.isEnableActions(sent().get(1)));
    }

    @Test
    void whileAWindowAwaitsItsReplyNothingIsSentAndTheScriptGoesOn() {
        when(script.awaitingReply()).thenReturn(true);
        AtomicBoolean ran = new AtomicBoolean();

        assertFalse(LivingTalk.answerScriptClick(chr, () -> ran.set(true)));

        assertFalse(ran.get());
        verify(client, never()).sendPacket(any());
        verify(script, never()).dispose();
    }

    @Test
    void offThePlayersOwnThreadNothingIsSent() {
        when(client.onIoThread()).thenReturn(false);
        AtomicBoolean ran = new AtomicBoolean();

        assertFalse(LivingTalk.answerScriptClick(chr, () -> ran.set(true)));

        assertFalse(ran.get());
        verify(client, never()).sendPacket(any());
    }

    @Test
    void theAnswerMarkIsRestoredAfterwards() {
        LivingTalk.answerScriptClick(chr, () -> { });
        assertFalse(LivingTalk.mayShow(chr), "the throttle applies again after the script's click");

        // inside the answer to a living window's click, the mark stays that answer's
        when(client.canClickNPC()).thenReturn(true);
        LivingTalk.open(chr, NPC).menu("pick", List.of(new Option("next", () -> {
            LivingTalk.answerScriptClick(chr, () -> { });
            LivingTalk.open(chr, NPC).ok("still the answer").show();
        }))).show();
        when(client.canClickNPC()).thenReturn(false);
        NpcTalks.Talk menu = NpcTalks.talks(sent()).get(0);
        assertTrue(LivingTalk.respond(client, (byte) 4, (byte) 1,
                Packets.buildInPacket(out -> out.writeInt(menu.selection("next")))));

        List<NpcTalks.Talk> talks = NpcTalks.talks(sent());
        assertEquals("still the answer", talks.get(talks.size() - 1).text());
    }

    private List<Packet> sent() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues();
    }
}
