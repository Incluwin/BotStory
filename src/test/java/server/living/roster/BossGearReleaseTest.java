package server.living.roster;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * A !bossgear kit never goes on with a released companion (docs/COMPANION_BOSSING_PLAN.md G13): whatever lets it go
 * (its owner, a contract end, its owner's deletion, the inactivity sweep: all RosterService.letGo), every piece tagged
 * {@value BossGearPlanner#OWNER_TAG} leaves it, worn and carried, and nothing else does. One out with its owner loses
 * them when its walk-out unloads it (nothing ticks it then), one not loaded loses them from its saved inventory, and one
 * loaded anywhere else is never rewritten under it.
 */
class BossGearReleaseTest {
    private static final int HADRIK = 501;
    private static final int BOSS_SWORD = 1302059;
    private static final int BOSS_CAPE = 1102084;
    private static final int PLAIN_CAP = 1002004;
    private static final int PLAIN_SWORD = 1302000;
    private static final int QUEST_GLOVES = 1082149;
    private static final short CAP = -1;
    private static final short WEAPON = -11;

    private final LifecycleHarness game = new LifecycleHarness();
    private final Persona hadrik = game.companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, LITH);

    BossGearReleaseTest() {
        when(game.world.contracts()).thenReturn(mock(ContractService.class));
    }

    @AfterEach
    void close() {
        game.close();
    }

    /** A piece of a !bossgear kit, as BossGearPlanner.forge tags it. */
    private static Item boss(int itemId, short position) {
        Item item = Equip.restored(itemId, position);
        item.setOwner(BossGearPlanner.OWNER_TAG);
        item.setFlag((short) (item.getFlag() | ItemConstants.UNTRADEABLE));
        return item;
    }

    /** Its own Resident quest piece: tagged with its name, not boss gear. */
    private static Item questGloves(short position) {
        Item item = Equip.restored(QUEST_GLOVES, position);
        item.setOwner("Hadrik");
        return item;
    }

    private static List<Integer> ids(Iterable<Item> items) {
        List<Integer> out = new ArrayList<>();
        items.forEach(item -> out.add(item.getItemId()));
        return out.stream().sorted().toList();
    }

    /** Its saved inventory: the boss sword worn and the boss cape carried, beside a plain cap, sword and its gloves. */
    private List<Item> savedKit() {
        List<Item> saved = new ArrayList<>(List.of(boss(BOSS_SWORD, WEAPON), Equip.restored(PLAIN_CAP, CAP),
                boss(BOSS_CAPE, (short) 1), Equip.restored(PLAIN_SWORD, (short) 2), questGloves((short) 3)));
        game.store.storedItems.put(HADRIK, saved);
        return saved;
    }

    private void offline(int characterId) {
        when(game.server.getWorld(0).getPlayerStorage().getCharacterById(characterId)).thenReturn(null);
    }

    @Test
    void aCompanionOutWithItsOwnerLosesItsBossGearWhenItsWalkOutUnloadsItAndKeepsTheRest() {
        Character bot = game.bot(hadrik);
        Inventory worn = new Inventory(bot, InventoryType.EQUIPPED, (byte) 100);
        worn.addItemFromDB(boss(BOSS_SWORD, WEAPON));
        worn.addItemFromDB(Equip.restored(PLAIN_CAP, CAP));
        Inventory bag = new Inventory(bot, InventoryType.EQUIP, (byte) 24);
        bag.addItemFromDB(boss(BOSS_CAPE, (short) 1));
        bag.addItemFromDB(Equip.restored(PLAIN_SWORD, (short) 2));
        bag.addItemFromDB(questGloves((short) 3));
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(worn);
        when(bot.getInventory(InventoryType.EQUIP)).thenReturn(bag);
        game.store.seed(game.row(hadrik).with(RosterState.ACTIVE, 1_000L));
        game.roster();
        game.outWithAnn(hadrik);

        game.roster().release(game.ann, hadrik, "dismissed");
        assertEquals(2, worn.list().size(), "it walks out to the portal in what it wears");
        game.runTimers();

        assertTrue(game.calls.contains("remove Hadrik"), game.calls.toString());
        assertEquals(List.of(PLAIN_CAP), ids(worn.list()), "the boss sword is gone, the plain cap stays on");
        assertEquals(List.of(QUEST_GLOVES, PLAIN_SWORD), ids(bag.list()),
                "the boss cape is gone; the plain sword and its own quest gloves stay");
        assertEquals(List.of(ANN + " hadrik bossgear.removed released: 2 pieces"), game.store.events("bossgear.removed"));
        assertEquals(List.of(), game.store.storedWrites, "loaded: never rewritten in the store under it");
    }

    @Test
    void aBenchedCompanionLosesTheBossGearInItsSavedInventoryAndKeepsTheRest() {
        savedKit();
        offline(HADRIK);
        game.roster();

        game.roster().release(game.ann, hadrik, "dismissed");

        assertNull(game.row(hadrik));
        assertEquals(List.of(HADRIK), game.store.storedWrites);
        assertEquals(List.of(PLAIN_CAP, QUEST_GLOVES, PLAIN_SWORD), ids(game.store.storedItems.get(HADRIK)));
        assertEquals(List.of(ANN + " hadrik bossgear.removed released: 2 pieces"), game.store.events("bossgear.removed"));
    }

    @Test
    void aReleasedCompanionWithoutBossGearIsLeftAsItIs() {
        List<Item> saved = new ArrayList<>(List.of(Equip.restored(PLAIN_CAP, CAP), questGloves((short) 1)));
        game.store.storedItems.put(HADRIK, saved);
        offline(HADRIK);
        game.roster();

        game.roster().release(game.ann, hadrik, "dismissed");

        assertEquals(List.of(), game.store.storedWrites, "nothing to take: nothing written");
        assertEquals(List.of(PLAIN_CAP, QUEST_GLOVES), ids(game.store.storedItems.get(HADRIK)));
        assertEquals(List.of(), game.store.events("bossgear.removed"));
    }

    @Test
    void aCharacterLoadedElsewhereIsNeverRewrittenUnderIt() {
        savedKit(); // the world lists Hadrik's character online (loaded, not registered as an Agent yet)
        game.roster();

        game.roster().release(game.ann, hadrik, "dismissed");

        assertNull(game.row(hadrik), "the release itself goes on");
        assertEquals(List.of(), game.store.storedWrites);
        assertEquals(5, game.store.storedItems.get(HADRIK).size());
    }
}
