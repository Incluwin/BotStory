package server.agents.social.conversation;

import client.BotClient;
import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.commands.AgentReplyChannel;
import server.agents.commands.AgentReplyChannelStateRuntime;
import server.agents.commands.AgentReplyRoute;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.social.conversation.AgentSocialDialogueRuntime.Styled;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deploy review 2026-10-10: the social reply to a whispered line goes out after a model call or a typing delay, and it
 * read the Agent's reply channel then, so a party line from the owner in between sent the private answer to the
 * party. It now goes on the route of the line it answers.
 */
class AgentSocialDialogueReplyRouteTest {
    private boolean transport;

    @BeforeEach
    void enableTransport() {
        transport = config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED;
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = true;
    }

    @AfterEach
    void restore() {
        config.AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED = transport;
        AgentSocialDialogueRuntime.installAnswerer(null);
    }

    @Test
    void anAnswerTypedAfterThePartyMovedTheChannelStillGoesByWhisper() {
        Character owner = character(1, "Kiwi", mock(Client.class));
        Character bot = character(2, "Tamsin", new BotClient(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(map.isObservedByPlayer()).thenReturn(true);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, owner, null);
        AgentReplyChannelStateRuntime.setReplyChannel(entry, AgentReplyChannel.WHISPER);
        AgentSocialDialogueRuntime.installAnswerer((agent, speaker, message, style) -> List.of(new Styled("ya got some", 1_200L)));
        List<Runnable> typed = new ArrayList<>();

        try (MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class)) {
            scheduler.when(() -> AgentSchedulerRuntime.scheduleLowPriority(any(Runnable.class), anyLong()))
                    .thenAnswer(call -> {
                        typed.add(call.getArgument(0));
                        return null;
                    });

            AgentSocialDialogueRuntime.maybeRespond(entry, owner, "u got pots",
                    new AgentReplyRoute(AgentReplyChannel.WHISPER, owner));
            for (int pass = 0; pass < 10 && AgentMailboxRuntime.drain(entry) > 0; pass++) {
                // the request is prepared on the Agent's mailbox
            }
            // the owner says something in party chat while the answer is being typed
            AgentReplyChannelStateRuntime.setReplyChannel(entry, AgentReplyChannel.PARTY);
            assertEquals(1, typed.size());
            typed.get(0).run();
        }

        verify(owner).sendPacket(any(Packet.class));
        verify(map, never()).broadcastMessage(any(Packet.class));
    }

    /**
     * Fourth deploy review 2026-10-10: the owner whispered "how do i get to perion" and took the portal while the
     * answer was being typed. The answer was dropped (they left before it was typed), but remembered as said, so the
     * next ask got "like i said, ...". A whisper reaches its asker on any map, so a whispered answer is sent anyway.
     */
    @Test
    void aWhisperedAnswerFollowsTheAskerToAnotherMap() {
        Character owner = character(1, "Kiwi", mock(Client.class));
        when(owner.getMapId()).thenReturn(100000000);
        AgentRuntimeEntry entry = typedAnswer(owner);
        List<Runnable> typed = new ArrayList<>();

        try (MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class)) {
            collect(scheduler, typed);
            AgentSocialDialogueRuntime.maybeRespond(entry, owner, "how do i get to perion",
                    new AgentReplyRoute(AgentReplyChannel.WHISPER, owner));
            drainAll(entry);
            when(owner.getMapId()).thenReturn(100000001); // through the portal before the answer is typed
            typed.forEach(Runnable::run);
        }

        verify(owner).sendPacket(any(Packet.class));
    }

    /** A map line's answer still waits for its asker on the map it was asked on, as live. */
    @Test
    void aMapLinesAnswerIsStillDroppedWhenTheAskerLeftTheMap() {
        Character owner = character(1, "Kiwi", mock(Client.class));
        when(owner.getMapId()).thenReturn(100000000);
        AgentRuntimeEntry entry = typedAnswer(owner);
        MapleMap map = entry.bot().getMap();
        List<Runnable> typed = new ArrayList<>();

        try (MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class)) {
            collect(scheduler, typed);
            AgentSocialDialogueRuntime.maybeRespond(entry, owner, "how do i get to perion",
                    new AgentReplyRoute(AgentReplyChannel.MAP, null));
            drainAll(entry);
            when(owner.getMapId()).thenReturn(100000001);
            typed.forEach(Runnable::run);
        }

        verify(map, never()).broadcastMessage(any(Packet.class));
        verify(owner, never()).sendPacket(any(Packet.class));
    }

    /** Kiwi's companion Tamsin, on an observed map, whose host answers in one typed line. */
    private static AgentRuntimeEntry typedAnswer(Character owner) {
        Character bot = character(2, "Tamsin", new BotClient(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(map.isObservedByPlayer()).thenReturn(true);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, owner, null);
        AgentSocialDialogueRuntime.installAnswerer((agent, speaker, message, style) ->
                List.of(new Styled("take the taxi from henesys", 6_000L)));
        return entry;
    }

    private static void collect(MockedStatic<AgentSchedulerRuntime> scheduler, List<Runnable> typed) {
        scheduler.when(() -> AgentSchedulerRuntime.scheduleLowPriority(any(Runnable.class), anyLong()))
                .thenAnswer(call -> typed.add(call.getArgument(0)));
    }

    private static void drainAll(AgentRuntimeEntry entry) {
        for (int pass = 0; pass < 10 && AgentMailboxRuntime.drain(entry) > 0; pass++) {
            // the request is prepared on the Agent's mailbox
        }
    }

    private static Character character(int id, String name, Client client) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.getClient()).thenReturn(client);
        return character;
    }
}
