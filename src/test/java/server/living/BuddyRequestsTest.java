package server.living;

import client.BuddyList;
import client.BuddylistEntry;
import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A bot asks a player to be buddies the way a player does (P9 buddy friends; probe 8): it lists the player on its own
 * buddy list first, so the player's accept makes them buddies both ways and the player sees it come and go, and the
 * player gets the stock request ("Buddy request from 'Casse'"). Nothing is asked of a player who already has it on their
 * list (as a buddy or a request still open) or whose list is full.
 */
class BuddyRequestsTest {
    private static final int ANN = 10;
    private static final int CASSE = 333;

    private final BuddyList annList = new BuddyList(20);
    private final BuddyList casseList = new BuddyList(20);
    private final Client annClient = mock(Client.class);
    private final Character ann = character(ANN, "Ann", annList, annClient, 1);
    private final Character casse = character(CASSE, "Casse", casseList, mock(Client.class), 2);

    private static Character character(int id, String name, BuddyList list, Client client, int channel) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getBuddylist()).thenReturn(list);
        when(chr.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(chr);
        when(client.getChannel()).thenReturn(channel);
        return chr;
    }

    @Test
    void theBotListsThePlayerAndThePlayerGetsTheStockRequest() {
        assertTrue(BuddyRequests.ask(casse, ann));

        BuddylistEntry mine = casseList.get(ANN);
        assertTrue(mine != null && mine.isVisible() && mine.getChannel() == 1, "Ann is on Casse's list, on her channel");
        assertTrue(annList.contains(CASSE) && !annList.containsVisible(CASSE), "a request Ann has yet to answer");
        assertTrue(annList.get(CASSE).getChannel() == 2, "it says where Casse is");
        verify(annClient, times(1)).sendPacket(any(Packet.class));
    }

    @Test
    void nothingIsAskedOfAPlayerWhoHasItAlreadyOrWhoseListIsFull() {
        BuddyRequests.ask(casse, ann);
        assertFalse(BuddyRequests.ask(casse, ann), "a request still open");
        verify(annClient, times(1)).sendPacket(any(Packet.class));

        BuddyList full = new BuddyList(1);
        full.put(new BuddylistEntry("Tobi", "Default Group", 901, -1, true));
        Client client = mock(Client.class);
        Character bob = character(11, "Bob", full, client, 1);
        assertFalse(BuddyRequests.ask(casse, bob));
        assertFalse(casseList.contains(11), "Casse does not list someone who was never asked");
        verify(client, never()).sendPacket(any(Packet.class));
    }

    @Test
    void aPlayerWithNoClientIsNotAsked() {
        when(ann.getClient()).thenReturn(null);

        assertFalse(BuddyRequests.ask(casse, ann));
        assertFalse(casseList.contains(ANN));
    }
}
