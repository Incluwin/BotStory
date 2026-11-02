package server.maps;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A casino payout or a dealt card (docs/SOLOMAPLING_PORT.md) is picked up by its owner only, even by a party member and
 * after the usual ownership time runs out, so a monster card on the blackjack table never lands in a player's book.
 */
class MapItemOwnerOnlyTest {
    private static Character character(int id, int partyId) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getPartyId()).thenReturn(partyId);
        when(chr.isPartyMember(1)).thenReturn(partyId == 7);
        return chr;
    }

    private static MapItem drop(Character owner) {
        return new MapItem(new Item(2388043, (short) 0, (short) 1), new Point(0, 0), owner, owner, null, (byte) 0, false);
    }

    @Test
    void anOwnerOnlyDropIsTheOwnersAloneForever() {
        Character owner = character(1, 7);
        Character partyMember = character(2, 7);
        MapItem card = drop(owner);
        card.setPermanentOwner(true);
        card.dropTime = 0L; // long past any ownership time
        assertTrue(card.canBePickedBy(owner));
        assertFalse(card.canBePickedBy(partyMember));
        assertFalse(card.canBePickedBy(character(3, -1)));
    }

    @Test
    void anOrdinaryDropStillGoesToThePartyAndThenToAnyone() {
        Character owner = character(1, 7);
        MapItem loot = drop(owner);
        assertTrue(loot.canBePickedBy(character(2, 7)), "a party drop");
        loot.dropTime = 0L;
        assertTrue(loot.canBePickedBy(character(3, -1)), "free for all once its ownership time is over");
    }
}
