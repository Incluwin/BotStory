// Pinned behaviour as of 2026-09-27 for docs/BOT_MANAGEMENT_PLAN.md P1/P2; flipped by P1 (op 4 checks before it spawns, and routes companions).
package net.server.channel.handlers;

import client.Character;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.coordinator.world.InviteCoordinator;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionDirectory.Relation;
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
    private static final int ALICE = 10;
    private static final int COMPANION = 900;
    private final PartyOperationHandler handler = new PartyOperationHandler();

    @Mock
    private World world;
    @Mock
    private PlayerStorage storage;
    @Mock
    private Party party;

    @AfterEach
    void clearRegistry() {
        AgentRuntimeRegistry.clear();
    }

    private static InPacket invite(String name) {
        return Packets.buildInPacket(out -> {
            out.writeByte(4);
            out.writeString(name);
        });
    }

    @Test
    void offlineNameIsRefusedAsFullBeforeAnythingIsSpawned() {
        List<String> calls = new ArrayList<>();
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
            handler.handlePacket(invite("Olwen"), client);

            invites.verifyNoInteractions();
            // the full party (status 17) is refused before op 4 would spawn anyone
            interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(any(), any()), never());
        }

        assertEquals(List.of("size check 6"), calls);
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.partyStatusMessage(17).getBytes(), sent.getValue().getBytes());
    }

    @Test
    void strangersInviteToAnotherPlayersPartylessCompanionIsDeniedWithNothingSeated() {
        // Alice's companion after Alice left or disbanded her party: online, in no party, not a lobby
        // waiter and holding no queue ticket, but still registered under Alice at runtime.
        Character alice = mock(Character.class);
        when(alice.getId()).thenReturn(ALICE);
        Character alicesCompanion = mock(Character.class);
        when(alicesCompanion.getId()).thenReturn(COMPANION);
        when(alicesCompanion.getName()).thenReturn("Olwen");
        AgentRuntimeRegistry.clear();
        AgentRuntimeRegistry.registerEntry(ALICE, new AgentRuntimeEntry(alicesCompanion, alice, null));
        assertEquals(ALICE, AgentRuntimeRegistry.leaderIdForAgentCharacter(COMPANION));
        when(client.getWorldServer()).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterByName("Olwen")).thenReturn(alicesCompanion);
        // the living world knows Olwen is Alice's companion
        LivingWorld living = mock(LivingWorld.class);
        CompanionDirectory directory = mock(CompanionDirectory.class);
        when(living.directory()).thenReturn(directory);
        when(directory.resolve(chr, alicesCompanion)).thenReturn(new Relation.OthersCompanion(ALICE, "olwen"));

        try (MockedStatic<LivingWorld> livingWorld = mockStatic(LivingWorld.class);
             MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class);
             MockedStatic<Party> parties = mockStatic(Party.class);
             MockedStatic<AgentInteractionRuntime> interaction = mockStatic(AgentInteractionRuntime.class)) {
            livingWorld.when(LivingWorld::get).thenReturn(living);

            handler.handlePacket(invite("Olwen"), client);

            // op 4 asks the living world first: Bob gets the stock "has denied" and Olwen stays Alice's
            invites.verifyNoInteractions();
            parties.verifyNoInteractions();
            interaction.verifyNoInteractions();
        }
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(chr).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.partyStatusMessage(23, "Olwen").getBytes(), sent.getValue().getBytes());
        verify(chr, never()).dropMessage(anyInt(), any());
    }
}
