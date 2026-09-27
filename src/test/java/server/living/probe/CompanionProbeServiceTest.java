package server.living.probe;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import server.living.probe.CompanionProbeService.GiveItem;
import server.living.probe.CompanionProbeService.Help;
import server.living.probe.CompanionProbeService.Imitate;
import server.living.probe.CompanionProbeService.Invite;
import server.living.probe.CompanionProbeService.Log;
import server.living.probe.CompanionProbeService.Refresh;
import server.living.probe.CompanionProbeService.Request;
import server.living.probe.CompanionProbeService.Status;
import server.living.probe.CompanionProbeService.Talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CompanionProbeServiceTest {
    private static Request parse(String... params) {
        return CompanionProbeService.parse(params);
    }

    @Test
    void noArgumentsPrintsHelpWithoutAProblem() {
        assertNull(assertInstanceOf(Help.class, parse()).problem());
        assertNull(assertInstanceOf(Help.class, CompanionProbeService.parse(null)).problem());
    }

    @Test
    void logNeedsOnOrOff() {
        assertEquals(new Log(true), parse("log", "on"));
        assertEquals(new Log(false), parse("LOG", "Off"));
        assertInstanceOf(Help.class, parse("log"));
        assertInstanceOf(Help.class, parse("log", "maybe"));
    }

    @Test
    void inviteNeedsAName() {
        assertEquals(new Invite("Hadrik"), parse("invite", "Hadrik"));
        assertInstanceOf(Help.class, parse("invite"));
    }

    @Test
    void talkDefaultsAndBounds() {
        assertEquals(new Talk(2, CompanionProbeService.DEFAULT_NPC_ID, 0), parse("talk", "2"));
        assertEquals(new Talk(4, 1012100, 1), parse("talk", "0x04", "1012100", "1"));
        assertEquals(new Talk(10, 1012100, 12), parse("talk", "010", "1012100", "12")); // decimal, not octal
        assertInstanceOf(Help.class, parse("talk"));
        assertInstanceOf(Help.class, parse("talk", "256"));
        assertInstanceOf(Help.class, parse("talk", "-1"));
        assertInstanceOf(Help.class, parse("talk", "x"));
        assertInstanceOf(Help.class, parse("talk", "1", "0"));
        assertInstanceOf(Help.class, parse("talk", "1", "9010000", "3"));
    }

    @Test
    void refreshAndItemHaveDefaults() {
        assertEquals(new Refresh(CompanionProbeService.DEFAULT_NPC_ID), parse("refresh"));
        assertEquals(new Refresh(2100), parse("refresh", "2100"));
        assertEquals(new GiveItem(2430011), parse("item"));
        assertEquals(new GiveItem(2000000), parse("item", "2000000"));
        assertInstanceOf(Help.class, parse("item", "potion"));
        assertEquals(new Status(), parse("status"));
    }

    @Test
    void imitateOnlyAcceptsImitatedNpcIds() {
        assertEquals(new Imitate("Hadrik", 9906599), parse("imitate", "Hadrik", "9906599"));
        assertEquals(new Imitate("Hadrik", 9901000), parse("imitate", "Hadrik", "9901000"));
        assertTrue(assertInstanceOf(Help.class, parse("imitate", "Hadrik", "9010000")).problem().contains("9901000-9906599"));
        assertInstanceOf(Help.class, parse("imitate", "Hadrik", "9906600"));
        assertInstanceOf(Help.class, parse("imitate", "Hadrik", "9999999"));
        assertInstanceOf(Help.class, parse("imitate", "Hadrik"));
    }

    @Test
    void unknownSubcommandNamesIt() {
        assertTrue(assertInstanceOf(Help.class, parse("dance")).problem().contains("dance"));
    }

    @Test
    void helpPrintsTheProblemAndEveryUsageLine() {
        Character gm = mock(Character.class);

        CompanionProbeService.execute(gm, new String[]{"dance"});

        verify(gm).dropMessage(6, "Unknown subcommand: dance");
        verify(gm, times(CompanionProbeService.usage().size())).dropMessage(eq(6), contains("!companionprobe"));
    }

    @Test
    void logOnAndOffToggleTheObserver() {
        Character gm = mock(Character.class);
        when(gm.getId()).thenReturn(4242);

        CompanionProbeService.execute(gm, new String[]{"log", "on"});
        assertTrue(CompanionProbeObserver.isObserving(4242));
        CompanionProbeService.execute(gm, new String[]{"log", "off"});
        assertFalse(CompanionProbeObserver.isObserving(4242));
        verify(gm, atLeastOnce()).dropMessage(anyInt(), anyString());
    }

    @Test
    void anOrphanReplyIsIgnoredWhenNoProbeWindowIsOpen() {
        Client client = mock(Client.class);

        assertFalse(CompanionProbeService.answerOrphanTalk(client));

        verifyNoInteractions(client);
    }

    @Test
    void theReplyToAProbeWindowReEnablesActionsOnce() {
        Character gm = mock(Character.class);
        when(gm.getId()).thenReturn(4343);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(gm);
        CompanionProbeService.windowOpened(4343);

        assertTrue(CompanionProbeService.answerOrphanTalk(client));
        assertFalse(CompanionProbeService.answerOrphanTalk(client));

        verify(client, times(1)).sendPacket(any(Packet.class));
    }
}
