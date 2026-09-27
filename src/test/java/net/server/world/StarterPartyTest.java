package net.server.world;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.maps.MapleMap;
import tools.PacketCreator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Decision 9 (a) of docs/BOT_MANAGEMENT_PLAN.md: a player below level 10 may lead a party of their own companions. */
class StarterPartyTest {
    private static final int PARTY_ID = 77;

    private final World world = mock(World.class);
    private final MapleMap map = mock(MapleMap.class);
    private Party created;

    @BeforeEach
    void worldMakesParties() {
        when(world.createParty(any())).thenAnswer(inv -> {
            PartyCharacter leader = inv.getArgument(0);
            created = new Party(PARTY_ID, leader);
            created.addMember(leader);
            return created;
        });
        when(world.getParty(PARTY_ID)).thenAnswer(inv -> created);
    }

    @Test
    void belowLevelTenTheStockRuleStillRefusesAnOrdinaryParty() {
        Character starter = player(1, 8, false);

        assertFalse(Party.createParty(starter, false));

        assertSent(starter, PacketCreator.partyStatusMessage(10));
    }

    @Test
    void belowLevelTenAPartyForYourOwnCompanionsIsAStarterParty() {
        Character starter = player(1, 8, false);

        assertTrue(Party.createParty(starter, false, true));

        assertNotNull(created);
        assertTrue(created.isStarterParty());
        assertSent(starter, PacketCreator.partyCreated(created, 1));
    }

    @Test
    void fromLevelTenThePartyIsAnOrdinaryOne() {
        Character veteran = player(1, 10, false);

        assertTrue(Party.createParty(veteran, false, true));

        assertFalse(created.isStarterParty());
    }

    @Test
    void aStarterPartyRefusesAnotherHumanWith10AndSeatsABot() {
        assertTrue(Party.createParty(player(1, 8, false), false, true));
        Character ben = player(2, 30, false);
        Character companion = player(3, 12, true);

        assertFalse(Party.joinParty(ben, PARTY_ID, false));
        assertTrue(Party.joinParty(companion, PARTY_ID, false));

        assertSent(ben, PacketCreator.partyStatusMessage(10));
        verify(world, never()).updateParty(eq(PARTY_ID), eq(PartyOperation.JOIN), argThat(joining -> joining.getId() == 2));
        verify(world).updateParty(eq(PARTY_ID), eq(PartyOperation.JOIN), argThat(joining -> joining.getId() == 3));
    }

    @Test
    void onceTheLeaderReachesLevelTenAHumanMayJoin() {
        Character starter = player(1, 8, false);
        assertTrue(Party.createParty(starter, false, true));
        when(starter.getLevel()).thenReturn(10);
        created.updateMember(new PartyCharacter(starter)); // what a level-up's silent party update does

        assertFalse(created.isStarterParty());
        assertTrue(Party.joinParty(player(2, 30, false), PARTY_ID, false));
    }

    private void assertSent(Character player, Packet expected) {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(player).sendPacket(sent.capture());
        assertArrayEquals(expected.getBytes(), sent.getValue().getBytes());
    }

    private Character player(int id, int level, boolean bot) {
        Character player = mock(Character.class);
        Client client = bot ? mock(BotClient.class) : mock(Client.class);
        when(client.getChannel()).thenReturn(1);
        when(player.getClient()).thenReturn(client);
        when(player.getId()).thenReturn(id);
        when(player.getName()).thenReturn("P" + id);
        when(player.getLevel()).thenReturn(level);
        when(player.getJob()).thenReturn(Job.BEGINNER);
        when(player.getWorldServer()).thenReturn(world);
        when(player.getMap()).thenReturn(map);
        return player;
    }
}
