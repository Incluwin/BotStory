package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.Client;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import net.server.coordinator.world.InviteCoordinator;
import net.server.world.Messenger;
import net.server.world.MessengerCharacter;
import net.server.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.runtime.AgentInteractionRuntime;
import server.living.LivingWorldHooks;
import testutil.HandlerTest;
import testutil.Packets;
import tools.PacketCreator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

/**
 * Maple Messenger invites (mode 3) never spawn or party anyone; a messenger opens with a bot only when it is the
 * inviter's own loaded companion (docs/BOT_MANAGEMENT_PLAN.md P1, "MessengerHandler").
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessengerInviteTest extends HandlerTest {
    private static final int MESSENGER_ID = 5;

    private final MessengerHandler handler = new MessengerHandler();

    @Mock
    private World world;
    @Mock
    private Channel channel;
    @Mock
    private PlayerStorage storage;
    private Messenger messenger;

    @BeforeEach
    void wire() {
        when(client.tryacquireClient()).thenReturn(true);
        when(client.getWorldServer()).thenReturn(world);
        when(client.getChannelServer()).thenReturn(channel);
        when(client.getChannel()).thenReturn(1);
        when(channel.getPlayerStorage()).thenReturn(storage);
        when(chr.getId()).thenReturn(10);
        when(chr.getName()).thenReturn("Ann");
        when(chr.getClient()).thenReturn(client);
        messenger = new Messenger(MESSENGER_ID, new MessengerCharacter(chr, 0));
        when(chr.getMessenger()).thenReturn(messenger);
    }

    @Test
    void anUnknownNameIsNotFoundAndNothingIsSpawnedOrPartied() {
        when(storage.getCharacterByName("Olwen")).thenReturn(null);
        when(world.find("Olwen")).thenReturn(-1);

        try (MockedStatic<AgentInteractionRuntime> interaction = mockStatic(AgentInteractionRuntime.class);
             MockedStatic<AgentPartyLifecycleService> parties = mockStatic(AgentPartyLifecycleService.class)) {
            invite("Olwen");

            interaction.verifyNoInteractions();
            parties.verifyNoInteractions();
        }
        assertSent(PacketCreator.messengerNote("Olwen", 4, 0));
    }

    @Test
    void yourLoadedCompanionJoinsTheMessengerAtOnce() {
        Character olwen = character(900, "Olwen", true);
        when(storage.getCharacterByName("Olwen")).thenReturn(olwen);

        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class);
             MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class)) {
            hooks.when(() -> LivingWorldHooks.ownsCompanion(chr, olwen)).thenReturn(true);

            invite("Olwen");

            invites.verifyNoInteractions();
        }
        verify(olwen).setMessenger(messenger);
        verify(world).joinMessenger(eq(MESSENGER_ID), argThat(member -> member.getId() == 900), eq("Olwen"), eq(1));
        verify(client, never()).sendPacket(any());
    }

    @Test
    void anotherPlayersCompanionIsNotFound() {
        Character brin = character(901, "Brin", true);
        when(storage.getCharacterByName("Brin")).thenReturn(brin);

        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class);
             MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class)) {
            hooks.when(() -> LivingWorldHooks.ownsCompanion(chr, brin)).thenReturn(false);

            invite("Brin");

            invites.verifyNoInteractions();
        }
        verify(brin, never()).setMessenger(any());
        verify(world, never()).joinMessenger(anyInt(), any(), anyString(), anyInt());
        assertSent(PacketCreator.messengerNote("Brin", 4, 0));
    }

    @Test
    void aHumanStillGetsTheStockInvite() {
        Character ben = character(20, "Ben", false);
        when(storage.getCharacterByName("Ben")).thenReturn(ben);

        try (MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class)) {
            invites.when(() -> InviteCoordinator.createInvite(InviteCoordinator.InviteType.MESSENGER, chr, MESSENGER_ID, 20))
                    .thenReturn(true);

            invite("Ben");
        }
        ArgumentCaptor<Packet> toBen = ArgumentCaptor.forClass(Packet.class);
        verify(ben).sendPacket(toBen.capture());
        assertArrayEquals(PacketCreator.messengerInvite("Ann", MESSENGER_ID).getBytes(), toBen.getValue().getBytes());
        assertSent(PacketCreator.messengerNote("Ben", 4, 1));
    }

    private void invite(String name) {
        InPacket packet = Packets.buildInPacket(out -> {
            out.writeByte(0x03);
            out.writeString(name);
        });
        handler.handlePacket(packet, client);
    }

    private void assertSent(Packet expected) {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        assertArrayEquals(expected.getBytes(), sent.getValue().getBytes());
    }

    private static Character character(int id, String name, boolean bot) {
        Character character = mock(Character.class);
        Client client = bot ? mock(BotClient.class) : mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.getClient()).thenReturn(client);
        return character;
    }
}
