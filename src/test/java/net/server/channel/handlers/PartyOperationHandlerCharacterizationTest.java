// Pins behaviour as of 2026-09-27 for docs/BOT_MANAGEMENT_PLAN.md P1/P2. When the fix lands, flip the assertion and rename the test.
package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService.AgentSpawnResult;
import testutil.HandlerTest;
import testutil.Packets;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Characterizes party operation 4 (invite) in {@link PartyOperationHandler} for Agent invitees. */
@ExtendWith(MockitoExtension.class)
class PartyOperationHandlerCharacterizationTest extends HandlerTest {
    private static final int PARTY_ID = 77;
    private final PartyOperationHandler handler = new PartyOperationHandler();

    @Mock
    private World world;
    @Mock
    private PlayerStorage storage;
    @Mock
    private Party party;

    private static InPacket invite(String name) {
        return Packets.buildInPacket(out -> {
            out.writeByte(4);
            out.writeString(name);
        });
    }

    @Test
    void offlineNameIsSpawnedBeforeTheFullPartyIsRefused() {
        List<String> calls = new ArrayList<>();
        Character spawned = mock(Character.class); // its own join failed, so getParty() is null
        List<PartyCharacter> six = List.of(mock(PartyCharacter.class), mock(PartyCharacter.class),
                mock(PartyCharacter.class), mock(PartyCharacter.class), mock(PartyCharacter.class),
                mock(PartyCharacter.class));
        when(client.getWorldServer()).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterByName("Olwen")).thenReturn(null);
        when(chr.getParty()).thenReturn(party);
        when(party.getMembers()).thenAnswer(inv -> {
            calls.add("size check " + six.size());
            return six;
        });

        try (MockedStatic<AgentInteractionRuntime> interaction = mockStatic(AgentInteractionRuntime.class);
             MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class)) {
            interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(chr, "Olwen")).thenAnswer(inv -> {
                calls.add("spawn Olwen");
                return AgentSpawnResult.ok(spawned, true);
            });

            handler.handlePacket(invite("Olwen"), client);

            invites.verifyNoInteractions();
        }

        // PartyOperationHandler:83-86 spawns before the size check at :96, and nothing in op 4 removes the
        // spawned Agent again when the invite is then refused with status 17 (:121)
        assertEquals(List.of("spawn Olwen", "size check 6"), calls);
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.partyStatusMessage(17).getBytes(), sent.getValue().getBytes());
    }

    @Test
    void strangersInviteToAnotherPlayersPartylessCompanionIsAccepted() {
        // Alice's companion after Alice left or disbanded her party: online, a BotClient, in no party,
        // not a lobby waiter and holding no queue ticket.
        Character alicesCompanion = mock(Character.class);
        when(alicesCompanion.getId()).thenReturn(900);
        when(alicesCompanion.getClient()).thenReturn(mock(BotClient.class));
        when(chr.getName()).thenReturn("Bob");
        when(client.getWorldServer()).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterByName("Olwen")).thenReturn(alicesCompanion);
        when(chr.getParty()).thenReturn(party);
        when(party.getId()).thenReturn(PARTY_ID);
        when(party.getMembers()).thenReturn(List.of(mock(PartyCharacter.class)));

        try (MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class);
             MockedStatic<Party> parties = mockStatic(Party.class);
             MockedStatic<AgentInteractionRuntime> interaction = mockStatic(AgentInteractionRuntime.class)) {
            invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, 900)).thenReturn(true);

            handler.handlePacket(invite("Olwen"), client);

            // decidePartyInvite answers NOT_LOBBY_WAITER and :107 treats every answer but REJECT as accept
            invites.verify(() -> InviteCoordinator.answerInvite(InviteType.PARTY, 900, PARTY_ID, true));
            parties.verify(() -> Party.joinParty(alicesCompanion, PARTY_ID, false));
            interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(any(), any()), never());
        }
        verify(chr, never()).dropMessage(anyInt(), any());
    }
}
