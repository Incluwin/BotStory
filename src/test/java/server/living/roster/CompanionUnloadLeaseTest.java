package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.quest.BossGearPlanner;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * A companion's unload (RosterService.unload) gives its COMPANION lease back only once it has logged off, so its logout
 * save (Client.disconnectInternal, synchronous) is in the store before anything else may load it. A released companion
 * that walks out is unowned while it walks (its row is gone), so a Resident tick or a citizen session could otherwise
 * take the character in the gap between the lease and the save, and load it from a store that still holds what it had
 * on, a !bossgear kit included. A logout that fails still gives the lease back, so the persona is never stuck.
 */
class CompanionUnloadLeaseTest {
    private static final int HADRIK = 501;
    private static final int BOSS_SWORD = 1302059;
    private static final int PLAIN_CAP = 1002004;
    private static final short CAP = -1;
    private static final short WEAPON = -11;

    private final LifecycleHarness game = new LifecycleHarness();
    private final Persona hadrik = game.companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, LITH);
    /** What the store would see at the logout: the lease holding Hadrik, and whether a Resident tick could take it. */
    private final List<String> atLogout = new ArrayList<>();
    private Inventory worn;

    CompanionUnloadLeaseTest() {
        when(game.world.contracts()).thenReturn(mock(ContractService.class));
    }

    @AfterEach
    void close() {
        game.close();
    }

    /** Hadrik out with Ann in a boss sword and a plain cap, on her channel, with a client its unload logs off. */
    private Client outWithAnnInBossGear() {
        Character bot = game.bot(hadrik);
        worn = new Inventory(bot, InventoryType.EQUIPPED, (byte) 100);
        Item sword = Equip.restored(BOSS_SWORD, WEAPON);
        sword.setOwner(BossGearPlanner.OWNER_TAG);
        worn.addItemFromDB(sword);
        worn.addItemFromDB(Equip.restored(PLAIN_CAP, CAP));
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(worn);
        when(bot.getInventory(InventoryType.EQUIP)).thenReturn(new Inventory(bot, InventoryType.EQUIP, (byte) 24));
        game.store.seed(game.row(hadrik).with(RosterState.ACTIVE, 1_000L));
        game.roster();
        game.outWithAnn(hadrik);
        game.companionOnChannel(hadrik, 1);
        return bot.getClient();
    }

    /** The logout: what holds the lease while it saves, and a Resident tick landing in it (ResidentRuntime). */
    private void loggingOffRecordsTheLease(Client client) {
        doAnswer(inv -> {
            game.calls.add("logout Hadrik");
            atLogout.add("lease " + game.leases.holder(HADRIK));
            boolean resident = game.leases.tryAcquire(HADRIK, CompanionLeases.Kind.RESIDENT, 0);
            atLogout.add("resident load " + (resident ? "taken" : "refused"));
            if (resident) {
                game.leases.release(HADRIK, CompanionLeases.Kind.RESIDENT);
            }
            atLogout.add("worn " + worn.list().stream().map(Item::getItemId).sorted().toList());
            return null;
        }).when(client).forceDisconnect();
    }

    @Test
    void aReleasedCompanionThatWalksOutKeepsItsLeaseUntilItsLogoutSaveIsDone() {
        loggingOffRecordsTheLease(outWithAnnInBossGear());

        game.roster().release(game.ann, hadrik, "dismissed");
        assertNull(game.row(hadrik), "its row is gone while it walks: nobody owns it any more");
        game.runTimers();

        assertTrue(game.calls.indexOf("remove Hadrik") < game.calls.indexOf("logout Hadrik"), game.calls.toString());
        assertEquals(List.of("lease " + new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, ANN),
                        "resident load refused", "worn [" + PLAIN_CAP + "]"), atLogout,
                "its logout saves it without the kit while its lease still keeps every other load out");
        assertNull(game.leases.holder(HADRIK), "logged off and saved: the lease is free for its Resident life");
    }

    @Test
    void aReleasedCompanionAwayFromItsOwnerLogsOffBeforeItsLeaseGoesToo() {
        loggingOffRecordsTheLease(outWithAnnInBossGear());
        game.moveAnn(KERNING, 1000, 200); // not on its map: it is unloaded where it stands, no walk-out

        game.roster().release(game.ann, hadrik, "dismissed");

        assertEquals(List.of("lease " + new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, ANN),
                "resident load refused", "worn [" + PLAIN_CAP + "]"), atLogout);
        assertNull(game.leases.holder(HADRIK));
    }

    @Test
    void aLogoutThatFailsStillGivesTheLeaseBack() {
        Client client = outWithAnnInBossGear();
        doAnswer(inv -> {
            throw new IllegalStateException("its session is already gone");
        }).when(client).forceDisconnect();

        game.roster().release(game.ann, hadrik, "dismissed");

        assertThrows(IllegalStateException.class, game::runTimers, "the failure is not hidden from companion-ops");
        assertNull(game.leases.holder(HADRIK), "never stuck under a lease nothing will give back");
    }
}
