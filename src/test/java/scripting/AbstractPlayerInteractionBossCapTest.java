package scripting;

import client.Character;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P12 G8, the Papulatus party path: the portal's daily-cap check and its entry log skip a companion that does not stand
 * on the leader's map (it does not go in), and treat every player as before, wherever they stand (decision 7).
 */
class AbstractPlayerInteractionBossCapTest {
    private final MapleMap gate = mock(MapleMap.class);
    private final MapleMap elsewhere = mock(MapleMap.class);

    @Test
    void aCompanionOffTheLeadersMapOrOfflineIsAway() {
        assertTrue(AbstractPlayerInteraction.companionAway(member(true, elsewhere), gate));
        assertTrue(AbstractPlayerInteraction.companionAway(member(true, null), gate), "offline");
    }

    @Test
    void aCompanionBesideTheLeaderIsCheckedAndLogged() {
        assertFalse(AbstractPlayerInteraction.companionAway(member(true, gate), gate));
    }

    @Test
    void aPlayerIsNeverAwayWhereverTheyStand() {
        assertFalse(AbstractPlayerInteraction.companionAway(member(false, elsewhere), gate));
        assertFalse(AbstractPlayerInteraction.companionAway(member(false, null), gate));
    }

    /** A party row: a bot or a player, online on {@code on}, or offline when {@code on} is null. */
    private static PartyCharacter member(boolean bot, MapleMap on) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.isBot()).thenReturn(bot);
        if (on != null) {
            Character character = mock(Character.class);
            when(character.getMap()).thenReturn(on);
            when(member.getPlayer()).thenReturn(character);
        }
        return member;
    }
}
