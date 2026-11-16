package server.pvp;

import client.Character;
import client.Client;
import client.Job;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AranComboHandler;
import net.server.channel.handlers.CloseRangeDamageHandler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * A landed duel hit earns what a monster hit does (practice 2026-11-09: no Crusader could build an orb, so Lv 100+
 * Crusaders refused every finisher and dealt 4-6% of the model; no Aran could build combo).
 */
class PvpHooksTest {

    private static Character fighter(Job job) {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        when(chr.getJob()).thenReturn(job);
        when(chr.getClient()).thenReturn(client);
        return chr;
    }

    private static AttackInfo attack(int skill, boolean ranged, boolean magic) {
        AttackInfo attack = new AttackInfo();
        attack.skill = skill;
        attack.ranged = ranged;
        attack.magic = magic;
        return attack;
    }

    @Test
    void aCloseRangeHitEarnsOrbsAndEnergyOncePerVictim() {
        Character crusader = fighter(Job.CRUSADER);
        try (MockedStatic<CloseRangeDamageHandler> close = mockStatic(CloseRangeDamageHandler.class);
             MockedStatic<AranComboHandler> aran = mockStatic(AranComboHandler.class)) {
            PvpHooks.earnOnHit(crusader, attack(1001004, false, false), 2);

            close.verify(() -> CloseRangeDamageHandler.gainOnHit(crusader, crusader.getClient(), 1001004, 2));
            aran.verify(() -> AranComboHandler.countHit(any()), never());
        }
    }

    @Test
    void rangedAndMagicHitsEarnNoOrbs() {
        Character bowman = fighter(Job.HUNTER);
        try (MockedStatic<CloseRangeDamageHandler> close = mockStatic(CloseRangeDamageHandler.class)) {
            PvpHooks.earnOnHit(bowman, attack(3001004, true, false), 1);
            PvpHooks.earnOnHit(bowman, attack(2001005, false, true), 1);

            close.verify(() -> CloseRangeDamageHandler.gainOnHit(any(), any(), anyInt(), anyInt()), never());
        }
    }

    @Test
    void anAranCountsOneComboHitPerVictim() {
        Character aran = fighter(Job.ARAN3);
        try (MockedStatic<CloseRangeDamageHandler> close = mockStatic(CloseRangeDamageHandler.class);
             MockedStatic<AranComboHandler> combo = mockStatic(AranComboHandler.class)) {
            PvpHooks.earnOnHit(aran, attack(21100001, false, false), 3);

            combo.verify(() -> AranComboHandler.countHit(aran), times(3));
        }
    }
}
