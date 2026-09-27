package client;

import net.packet.InPacket;
import net.packet.Packet;
import net.server.channel.handlers.PartyOperationHandler;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.maps.MapleMap;
import testutil.Packets;
import tools.PacketCreator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Leadership never passes to a bot while a human is in the party (docs/BOT_MANAGEMENT_PLAN.md P1): the successor
 * when the leader logs out ({@code Client.removePartyPlayer}) and party operation 6 (change leader).
 */
class LeaderSuccessionTest {
    private static final int LEAVER = 1;

    private final MapleMap map = mock(MapleMap.class);

    @Test
    void aHumanOnTheMapLeadsEvenWhenABotOutlevelsThem() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter ben = member(3, 30, false, true);

        assertSame(ben, Client.successor(party(leaver(), bot, ben), LEAVER, false, map));
    }

    @Test
    void aHumanElsewhereLeadsBeforeABotOnTheMap() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter ben = member(3, 30, false, false);

        assertSame(ben, Client.successor(party(leaver(), bot, ben), LEAVER, false, map));
    }

    @Test
    void theHighestLevelHumanOnTheMapLeads() {
        PartyCharacter ben = member(3, 30, false, true);
        PartyCharacter cleo = member(4, 45, false, true);
        PartyCharacter dan = member(5, 90, false, false);

        assertSame(cleo, Client.successor(party(leaver(), ben, cleo, dan), LEAVER, false, map));
    }

    @Test
    void aHumanLeaderWithOnlyBotsOnlineStaysLeader() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter offlineHuman = offline(3);

        assertNull(Client.successor(party(leaver(), bot, offlineHuman), LEAVER, false, map));
    }

    @Test
    void aBotLeaderHandsOverToAHumanFirst() {
        PartyCharacter bot = member(2, 120, true, true);
        PartyCharacter ben = member(3, 30, false, true);

        assertSame(ben, Client.successor(party(leaver(), bot, ben), LEAVER, true, map));
    }

    @Test
    void aBotOnlyPartyKeepsTheStockRule() {
        PartyCharacter low = member(2, 20, true, true);
        PartyCharacter high = member(3, 60, true, true);
        PartyCharacter away = member(4, 90, true, false);

        assertSame(high, Client.successor(party(leaver(), low, high, away), LEAVER, true, map));
    }

    // ---- op 6, change leader -------------------------------------------------------------

    @Test
    void changeLeaderWithoutAPartyIsIgnored() {
        Client client = leaderClient(null);

        changeLeader(client, 2);

        verify(client.getWorldServer(), never()).updateParty(anyInt(), any(), any());
    }

    @Test
    void onlyTheLeaderMayHandOverLeadership() {
        Party party = party(leaver(), member(3, 30, false, true));
        when(party.getLeaderId()).thenReturn(3);
        Client client = leaderClient(party);

        changeLeader(client, 3);

        verify(client.getWorldServer(), never()).updateParty(anyInt(), any(), any());
    }

    @Test
    void aBotIsNeverMadeLeaderAndSaysSo() {
        PartyCharacter bot = member(2, 120, true, true);
        when(bot.getPlayer().getName()).thenReturn("Olwen");
        when(bot.getPlayer().getClient().getChannel()).thenReturn(1);
        Party party = party(leaver(), bot);
        when(party.getLeaderId()).thenReturn(LEAVER);
        when(party.getMemberById(2)).thenReturn(bot);
        Client client = leaderClient(party);

        changeLeader(client, 2);

        verify(client.getWorldServer(), never()).updateParty(anyInt(), any(), any());
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.getWhisperReceive("Olwen", 0, false, "nah, you lead").getBytes(),
                sent.getValue().getBytes());
    }

    @Test
    void aHumanMayBeMadeLeader() {
        PartyCharacter ben = member(3, 30, false, true);
        Party party = party(leaver(), ben);
        when(party.getId()).thenReturn(77);
        when(party.getLeaderId()).thenReturn(LEAVER);
        when(party.getMemberById(3)).thenReturn(ben);
        Client client = leaderClient(party);

        changeLeader(client, 3);

        verify(client.getWorldServer()).updateParty(77, PartyOperation.CHANGE_LEADER, ben);
    }

    private static Client leaderClient(Party party) {
        Character leader = mock(Character.class);
        when(leader.getId()).thenReturn(LEAVER);
        when(leader.getParty()).thenReturn(party);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(leader);
        when(client.getWorldServer()).thenReturn(mock(World.class));
        return client;
    }

    private static void changeLeader(Client client, int newLeaderId) {
        InPacket packet = Packets.buildInPacket(out -> {
            out.writeByte(6);
            out.writeInt(newLeaderId);
        });
        new PartyOperationHandler().handlePacket(packet, client);
    }

    // ---- helpers -------------------------------------------------------------------------

    private PartyCharacter member(int id, int level, boolean bot, boolean onMap) {
        Character player = mock(Character.class);
        when(player.getClient()).thenReturn(bot ? mock(BotClient.class) : mock(Client.class));
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        when(member.getLevel()).thenReturn(level);
        when(member.isOnline()).thenReturn(true);
        when(member.getPlayer()).thenReturn(player);
        when(map.getCharacterById(id)).thenReturn(onMap ? player : null);
        return member;
    }

    private static PartyCharacter offline(int id) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        return member;
    }

    /** The leaver's own entry is already offline (LOG_ONOFF runs first). */
    private static PartyCharacter leaver() {
        return offline(LEAVER);
    }

    private static Party party(PartyCharacter... members) {
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(List.of(members));
        return party;
    }
}
