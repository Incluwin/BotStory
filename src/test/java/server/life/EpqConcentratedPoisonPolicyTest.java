package server.life;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EpqConcentratedPoisonPolicyTest {
    @Test
    void onlyBlocksConcentratedPoisonInsideStageTwo() {
        Character character = mock(Character.class);
        MapItem poison = mock(MapItem.class);
        when(character.getMapId()).thenReturn(930_000_200);
        when(poison.getItemId()).thenReturn(4_001_161);

        assertTrue(EpqConcentratedPoisonPolicy.blocksPickup(character, poison));
        when(poison.getItemId()).thenReturn(4_001_162);
        assertFalse(EpqConcentratedPoisonPolicy.blocksPickup(character, poison));
        when(character.getMapId()).thenReturn(930_000_100);
        when(poison.getItemId()).thenReturn(4_001_161);
        assertFalse(EpqConcentratedPoisonPolicy.blocksPickup(character, poison));
    }
}
