package server.agents.integration;

import server.agents.runtime.AgentRuntimeEntry;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import config.YamlConfig;
import org.mockito.MockedStatic;
import server.agents.commands.AgentMessageQueueStateRuntime;
import server.agents.commands.AgentReplyChannel;
import server.agents.commands.AgentReplyChannelStateRuntime;
import server.agents.commands.AgentReplyRoute;
import server.agents.runtime.AgentSchedulerRuntime;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDialogueTransportRuntimeTest {
    private boolean previousTransportEnabled;

    @BeforeEach
    void enableDialogueTransportForParityTests() {
        previousTransportEnabled = config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED;
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = true;
    }

    @AfterEach
    void restoreDialogueTransportFlag() {
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = previousTransportEnabled;
    }

    @Test
    void visibleSayBroadcastsToMap() {
        Character bot = mock(Character.class);
        Character owner = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, owner, null);

        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(7);
        when(map.isObservedByPlayer()).thenReturn(true);

        AgentDialogueTransportRuntime.visibleSayNow(entry, "hello");

        verify(map).broadcastMessage(any(Packet.class));
    }

    @Test
    void whisperReplySendsPacketToOwner() {
        Character bot = mock(Character.class);
        Character owner = mock(Character.class);
        Client botClient = mock(Client.class);
        Client ownerClient = mock(Client.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, owner, null);
        AgentReplyChannelStateRuntime.setWhisper(entry);

        when(bot.getName()).thenReturn("agent");
        when(bot.getClient()).thenReturn(botClient);
        when(botClient.getChannel()).thenReturn(1);
        when(owner.getClient()).thenReturn(ownerClient);

        AgentDialogueTransportRuntime.replyNow(entry, "ok");

        verify(owner).sendPacket(any(Packet.class));
    }

    @Test
    void partyDeliveryFallsBackToMapWhenBotHasNoParty() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);

        when(bot.getParty()).thenReturn(null);
        when(bot.getMap()).thenReturn(map);
        when(bot.getId()).thenReturn(7);
        when(map.isObservedByPlayer()).thenReturn(true);

        AgentDialogueTransportRuntime.sayPartyNow(bot, "party");

        verify(map).broadcastMessage(any(Packet.class));
    }

    @Test
    void visibleSaySkipsBroadcastWhenMapHasNoPlayerObserver() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);

        when(bot.getMap()).thenReturn(map);

        AgentDialogueTransportRuntime.visibleSayNow(entry, "hello");

        verify(map, never()).broadcastMessage(any(Packet.class));
    }

    @Test
    void disabledTransportSuppressesDirectReplies() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        when(bot.getMap()).thenReturn(map);
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = false;

        AgentDialogueTransportRuntime.queueSay(entry, "ambient");
        AgentDialogueTransportRuntime.visibleSayNow(entry, "ambient");

        verify(map, never()).broadcastMessage(any(Packet.class));
        org.junit.jupiter.api.Assertions.assertEquals(
                0, server.agents.commands.AgentMessageQueueStateRuntime.size(entry));
    }

    /**
     * Deploy review 2026-10-10: a reply on the route its line fixed (a whisper from the owner) goes there, though a
     * party line moved the Agent's channel since and an administrator is its interaction target now; the reply queue
     * keeps the route too. A reply with no route still reads the channel when it is sent.
     */
    @Test
    void aRoutedReplyKeepsItsWhisperWhateverTheChannelIsWhenItIsSent() {
        Character bot = mock(Character.class);
        Character owner = mock(Character.class);
        Character admin = mock(Character.class);
        Client botClient = mock(Client.class);
        MapleMap map = mock(MapleMap.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, admin, null);
        AgentReplyChannelStateRuntime.setReplyChannel(entry, AgentReplyChannel.PARTY);
        when(bot.getName()).thenReturn("agent");
        when(bot.getId()).thenReturn(7);
        when(bot.getClient()).thenReturn(botClient);
        when(botClient.getChannel()).thenReturn(1);
        when(bot.getMap()).thenReturn(map);
        when(map.isObservedByPlayer()).thenReturn(true);
        when(owner.getClient()).thenReturn(mock(Client.class));
        when(admin.getClient()).thenReturn(mock(Client.class));
        AgentReplyRoute whisper = new AgentReplyRoute(AgentReplyChannel.WHISPER, owner);

        try (MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class)) {
            AgentDialogueTransportRuntime.replyNow(entry, "ok", whisper);
            AgentDialogueTransportRuntime.queueReply(entry, "queued too", whisper);
            verify(owner, times(2)).sendPacket(any(Packet.class));
            verify(admin, never()).sendPacket(any(Packet.class));
            verify(map, never()).broadcastMessage(any(Packet.class));

            AgentDialogueTransportRuntime.replyNow(entry, "no route", null);
            verify(map).broadcastMessage(any(Packet.class)); // the party channel now, and no party: the map
        }
    }

    /**
     * Second deploy review 2026-10-10: a reply said or queued while its line is handled, with no route of its own (the
     * command flows' plain replyNow and queueReply), keeps the route of that line: a reply that waits in a busy queue
     * goes by whisper to the one who whispered, though the channel and the interaction target have moved since.
     */
    @Test
    void aPlainReplyQueuedWhileItsLineIsHandledKeepsThatLinesRouteInABusyQueue() {
        Character bot = mock(Character.class);
        Character owner = mock(Character.class);
        Character admin = mock(Character.class);
        Client botClient = mock(Client.class);
        MapleMap map = mock(MapleMap.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, owner, null);
        AgentReplyChannelStateRuntime.setReplyChannel(entry, AgentReplyChannel.WHISPER);
        when(bot.getName()).thenReturn("agent");
        when(bot.getId()).thenReturn(7);
        when(bot.getClient()).thenReturn(botClient);
        when(botClient.getChannel()).thenReturn(1);
        when(bot.getMap()).thenReturn(map);
        when(map.isObservedByPlayer()).thenReturn(true);
        when(owner.getClient()).thenReturn(mock(Client.class));
        when(admin.getClient()).thenReturn(mock(Client.class));
        AgentReplyRoute whisper = new AgentReplyRoute(AgentReplyChannel.WHISPER, admin);
        AgentMessageQueueStateRuntime.setSending(entry, true); // a reply is being typed

        AgentReplyRoute.within(entry, whisper, () -> {
            AgentDialogueTransportRuntime.queueReply(entry, "on it");
            return null;
        });
        AgentReplyChannelStateRuntime.setReplyChannel(entry, AgentReplyChannel.PARTY); // the owner's party line

        AgentReplyRoute queued = AgentMessageQueueStateRuntime.poll(entry).route();
        assertEquals(whisper, queued);
        AgentDialogueTransportRuntime.replyNow(entry, "on it", queued);
        verify(admin).sendPacket(any(Packet.class));
        verify(owner, never()).sendPacket(any(Packet.class));
        verify(map, never()).broadcastMessage(any(Packet.class));
    }
}
