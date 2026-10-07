package server.living.probe;

import client.BotClient;
import client.BuddyList;
import client.BuddylistEntry;
import client.Character;
import client.Client;
import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteResultType;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.living.probe.CompanionProbeService.Buddy;
import server.living.probe.CompanionProbeService.GiveItem;
import server.living.probe.CompanionProbeService.Help;
import server.living.probe.CompanionProbeService.Imitate;
import server.living.probe.CompanionProbeService.Invite;
import server.living.probe.CompanionProbeService.Log;
import server.living.probe.CompanionProbeService.Refresh;
import server.living.probe.CompanionProbeService.Request;
import server.living.probe.CompanionProbeService.Status;
import server.living.probe.CompanionProbeService.Talk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CompanionProbeServiceTest {
    private static final int GM_ID = 4242;
    private static final int OTHER_GM_ID = 4343;
    private static final int NO_TEMPLATE = CompanionProbeService.NO_TEMPLATE;

    @AfterEach
    void forgetProbeState() {
        CompanionProbeService.forget(GM_ID);
        CompanionProbeService.forget(OTHER_GM_ID);
        InviteCoordinator.removeInvite(InviteType.PARTY, GM_ID);
    }

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
    void inviteNeedsANameAndOnlyTakesForce() {
        assertEquals(new Invite("Hadrik", false), parse("invite", "Hadrik"));
        assertEquals(new Invite("Ann", true), parse("invite", "Ann", "FORCE"));
        assertInstanceOf(Help.class, parse("invite"));
        assertInstanceOf(Help.class, parse("invite", "Ann", "now"));
    }

    @Test
    void talkDefaultsAndBounds() {
        assertEquals(new Talk(2, CompanionProbeService.DEFAULT_NPC_ID, 0, NO_TEMPLATE), parse("talk", "2"));
        assertEquals(new Talk(4, 1012100, 1, NO_TEMPLATE), parse("talk", "0x04", "1012100", "1"));
        assertEquals(new Talk(10, 1012100, 12, NO_TEMPLATE), parse("talk", "010", "1012100", "12")); // decimal, not octal
        assertInstanceOf(Help.class, parse("talk"));
        assertInstanceOf(Help.class, parse("talk", "256"));
        assertInstanceOf(Help.class, parse("talk", "-1"));
        assertInstanceOf(Help.class, parse("talk", "x"));
        assertInstanceOf(Help.class, parse("talk", "1", "0"));
        assertInstanceOf(Help.class, parse("talk", "1", "9010000", "3"));
    }

    @Test
    void talkTakesATrailingTemplateId() {
        assertEquals(new Talk(4, CompanionProbeService.DEFAULT_NPC_ID, 0, 9010000), parse("talk", "4", "+9010000"));
        assertEquals(new Talk(5, 1012100, 1, 0x10), parse("talk", "5", "1012100", "1", "+0x10"));
        assertInstanceOf(Help.class, parse("talk", "4", "+x"));
        assertInstanceOf(Help.class, parse("talk", "4", "+-1"));
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
    void buddyNeedsAName() {
        assertEquals(new Buddy("Hadrik"), parse("buddy", "Hadrik"));
        assertEquals(new Buddy("Hadrik"), parse("BUDDY", "Hadrik"));
        assertInstanceOf(Help.class, parse("buddy"));
        assertInstanceOf(Help.class, parse("buddy", "Hadrik", "now"));
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
        Character gm = gm();

        CompanionProbeService.execute(gm, new String[]{"log", "on"});
        assertTrue(CompanionProbeObserver.isObserving(GM_ID));
        CompanionProbeService.execute(gm, new String[]{"log", "off"});
        assertFalse(CompanionProbeObserver.isObserving(GM_ID));
        verify(gm, atLeastOnce()).dropMessage(anyInt(), anyString());
    }

    @Test
    void forgetDropsTheLogAndTheOpenWindow() {
        CompanionProbeObserver.setObserving(GM_ID, true);
        long ticket = CompanionProbeService.windowOpened(GM_ID);

        CompanionProbeService.forget(GM_ID);

        assertFalse(CompanionProbeObserver.isObserving(GM_ID));
        assertFalse(CompanionProbeService.windowOpen(GM_ID));
        assertFalse(CompanionProbeService.sameWindow(GM_ID, ticket));
        Client client = mock(Client.class);
        assertFalse(CompanionProbeService.answerOrphanTalk(client));
        verifyNoInteractions(client); // back on the empty-map path
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
        when(gm.getId()).thenReturn(OTHER_GM_ID);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(gm);
        CompanionProbeService.windowOpened(OTHER_GM_ID);

        assertTrue(CompanionProbeService.answerOrphanTalk(client));
        assertFalse(CompanionProbeService.answerOrphanTalk(client));

        verify(client, times(1)).sendPacket(any(Packet.class));
    }

    @Test
    void theRefreshOnlyWritesIntoTheSameStillOpenWindow() {
        Character gm = gm();
        when(gm.isLoggedinWorld()).thenReturn(true);
        long first = CompanionProbeService.windowOpened(GM_ID);

        CompanionProbeService.sendRefresh(gm, CompanionProbeService.DEFAULT_NPC_ID, first);
        verify(gm, times(1)).sendPacket(any(Packet.class));
        assertTrue(CompanionProbeService.sameWindow(GM_ID, first)); // the second text stays in the same window

        CompanionProbeService.windowOpened(GM_ID); // e.g. talk 3 within the 2 s
        CompanionProbeService.sendRefresh(gm, CompanionProbeService.DEFAULT_NPC_ID, first);

        verify(gm, times(1)).sendPacket(any(Packet.class));
        verify(gm).dropMessage(eq(6), contains("answered or replaced"));
    }

    @Test
    void aRefreshForALoggedOutGmSendsNothing() {
        Character gm = gm();
        long ticket = CompanionProbeService.windowOpened(GM_ID);

        CompanionProbeService.sendRefresh(gm, CompanionProbeService.DEFAULT_NPC_ID, ticket);

        verify(gm, never()).sendPacket(any(Packet.class));
        verify(gm, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void talkAndRefreshRefuseAnImitateTemplateWithNoLookSent() {
        Character gm = gm();

        CompanionProbeService.execute(gm, new String[]{"talk", "0", "9901000"});
        CompanionProbeService.execute(gm, new String[]{"refresh", "9977777"});

        verify(gm, never()).sendPacket(any(Packet.class));
        verify(gm, times(2)).dropMessage(eq(6), contains("imitate <name>"));
        assertFalse(CompanionProbeService.windowOpen(GM_ID));
    }

    @Test
    void imitateTemplatesAreTheImitateRangeAndCustomDev() {
        assertTrue(CompanionProbeService.isImitateTemplate(9901000));
        assertTrue(CompanionProbeService.isImitateTemplate(9906599));
        assertTrue(CompanionProbeService.isImitateTemplate(9977777));
        assertFalse(CompanionProbeService.isImitateTemplate(9900999));
        assertFalse(CompanionProbeService.isImitateTemplate(9906600));
        assertFalse(CompanionProbeService.isImitateTemplate(CompanionProbeService.DEFAULT_NPC_ID));
    }

    @Test
    void inviteFromABotCarriesThePartyIdItWasRegisteredUnder() {
        Character gm = gm();
        Character bot = character(9001, "Hadrik", mock(BotClient.class));
        Party party = party(77);
        when(bot.getParty()).thenReturn(party, (Party) null); // the bot leaves its party right after the check
        channelStorage(gm).put("Hadrik", bot);

        CompanionProbeService.execute(gm, new String[]{"invite", "Hadrik"});

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(gm).sendPacket(sent.capture());
        assertArrayEquals(CompanionProbePackets.partyInvite(77, "Hadrik").getBytes(), sent.getValue().getBytes());
        assertEquals(InviteResultType.ACCEPTED, InviteCoordinator.answerInvite(InviteType.PARTY, GM_ID, 77, true).result);
    }

    @Test
    void aHumanInviterNeedsForce() {
        Character gm = gm();
        Character human = character(9002, "Ann", mock(Client.class));
        Party party = party(55);
        when(human.getParty()).thenReturn(party);
        channelStorage(gm).put("Ann", human);

        CompanionProbeService.execute(gm, new String[]{"invite", "Ann"});

        verify(gm, never()).sendPacket(any(Packet.class));
        verify(gm).dropMessage(eq(6), contains("Add force"));
        assertFalse(InviteCoordinator.hasInvite(InviteType.PARTY, GM_ID));

        CompanionProbeService.execute(gm, new String[]{"invite", "Ann", "force"});

        verify(gm, times(1)).sendPacket(any(Packet.class));
        verify(gm).dropMessage(eq(6), contains("Declining will notify Ann"));
        assertTrue(InviteCoordinator.hasInvite(InviteType.PARTY, GM_ID));
    }

    @Test
    void anInviterOnAnotherChannelIsRefused() {
        Character gm = gm();
        channelStorage(gm);
        Client botClient = mock(BotClient.class);
        when(botClient.getChannel()).thenReturn(2);
        Character bot = character(9001, "Hadrik", botClient);
        World world = mock(World.class);
        PlayerStorage worldStorage = mock(PlayerStorage.class);
        when(gm.getWorldServer()).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(worldStorage);
        when(worldStorage.getCharacterByName("Hadrik")).thenReturn(bot);

        CompanionProbeService.execute(gm, new String[]{"invite", "Hadrik"});

        verify(gm, never()).sendPacket(any(Packet.class));
        verify(gm).dropMessage(eq(6), contains("is on channel 2"));
        assertFalse(InviteCoordinator.hasInvite(InviteType.PARTY, GM_ID));
    }

    @Test
    void buddyFromABotPutsTheGmOnItsListAndSendsTheStockRequest() {
        Character gm = gm();
        BuddyList gmList = new BuddyList(20);
        when(gm.getBuddylist()).thenReturn(gmList);
        Client botClient = mock(BotClient.class);
        when(botClient.getChannel()).thenReturn(1);
        Character bot = character(9001, "Hadrik", botClient);
        BuddyList botList = new BuddyList(20);
        when(bot.getBuddylist()).thenReturn(botList);
        channelStorage(gm).put("Hadrik", bot);
        World world = mock(World.class);
        when(gm.getWorldServer()).thenReturn(world);
        when(world.requestBuddyAdd("Gm", 1, 9001, "Hadrik")).thenReturn(BuddyList.BuddyAddResult.OK);

        CompanionProbeService.execute(gm, new String[]{"buddy", "Hadrik"});

        verify(world).requestBuddyAdd("Gm", 1, 9001, "Hadrik");
        BuddylistEntry entry = botList.get(GM_ID);
        assertTrue(entry != null && entry.isVisible() && entry.getName().equals("Gm"));
        verify(gm).dropMessage(eq(6), contains("Sent a buddy request from Hadrik"));
    }

    @Test
    void buddyRefusesAHumanAndABotAlreadyOnTheList() {
        Character gm = gm();
        BuddyList gmList = new BuddyList(20);
        when(gm.getBuddylist()).thenReturn(gmList);
        Character human = character(9002, "Ann", mock(Client.class));
        Client botClient = mock(BotClient.class);
        Character bot = character(9001, "Hadrik", botClient);
        ChannelStorage storage = channelStorage(gm);
        storage.put("Ann", human);
        storage.put("Hadrik", bot);
        World world = mock(World.class);
        when(gm.getWorldServer()).thenReturn(world);
        gmList.put(new BuddylistEntry("Hadrik", "Default Group", 9001, 1, true));

        CompanionProbeService.execute(gm, new String[]{"buddy", "Ann"});
        CompanionProbeService.execute(gm, new String[]{"buddy", "Hadrik"});

        verify(world, never()).requestBuddyAdd(any(), anyInt(), anyInt(), any());
        verify(gm).dropMessage(eq(6), contains("Ann is a human player"));
        verify(gm).dropMessage(eq(6), contains("Hadrik is already on your buddy list"));
    }

    private static Character gm() {
        Character gm = mock(Character.class);
        when(gm.getId()).thenReturn(GM_ID);
        when(gm.getName()).thenReturn("Gm");
        return gm;
    }

    private static Character character(int id, String name, Client client) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(client);
        return chr;
    }

    private static Party party(int id) {
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(id);
        return party;
    }

    /** The GM's channel storage; {@code put} makes a character findable by name on it. */
    private static ChannelStorage channelStorage(Character gm) {
        Client client = mock(Client.class);
        Channel channel = mock(Channel.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        when(gm.getClient()).thenReturn(client);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getPlayerStorage()).thenReturn(storage);
        return new ChannelStorage(storage);
    }

    private record ChannelStorage(PlayerStorage storage) {
        void put(String name, Character chr) {
            when(storage.getCharacterByName(name)).thenReturn(chr);
        }
    }
}
