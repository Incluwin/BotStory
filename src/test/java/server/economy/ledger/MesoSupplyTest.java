package server.economy.ledger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Who holds a DB row's meso in the supply split: a persona character or a hired citizen is a bot before anything
 * else, then a retired or active citizen, then a GM, then a player. Sums add up by pool and by holder.
 */
class MesoSupplyTest {
    @Test
    void aBotBeforeACitizenBeforeAGm() {
        assertEquals(MesoHolder.BOT, MesoSupply.holder(true, "ACTIVE", true));
        assertEquals(MesoHolder.RETIRED, MesoSupply.holder(false, "RETIRED", false));
        assertEquals(MesoHolder.CITIZEN, MesoSupply.holder(false, "ACTIVE", true));
        assertEquals(MesoHolder.GM, MesoSupply.holder(false, null, true));
        assertEquals(MesoHolder.HUMAN, MesoSupply.holder(false, null, false));
    }

    @Test
    void aSnapshotSumsByPoolAndHolder() {
        MesoSupply.Snapshot snapshot = new MesoSupply.Snapshot();
        snapshot.add(MesoSupply.Pool.PURSE, MesoHolder.CITIZEN, 1_000);
        snapshot.add(MesoSupply.Pool.PURSE, MesoHolder.HUMAN, 200);
        snapshot.add(MesoSupply.Pool.STORAGE, MesoHolder.HUMAN, 50);
        snapshot.add(MesoSupply.Pool.HAUL, MesoHolder.BOT, -30); // a haul that owes upkeep
        assertEquals(1_220, snapshot.total());
        assertEquals(1_200, snapshot.pool(MesoSupply.Pool.PURSE));
        assertEquals(250, snapshot.holder(MesoHolder.HUMAN));
        assertEquals(-30, snapshot.holder(MesoHolder.BOT));
    }
}
