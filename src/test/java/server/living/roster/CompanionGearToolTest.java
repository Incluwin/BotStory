package server.living.roster;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.agents.runtime.AgentRuntimeEntry;
import server.knowledge.KnowledgeTestKit;
import server.living.LivingWorld;
import server.living.quest.BossGearPlanner;
import server.living.quest.BossGearPlanner.Piece;
import server.living.quest.FriendForge.Catalog;
import server.living.quest.FriendForge.Scroll;
import server.living.quest.FriendForge.Use;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.CompanionGearTool.Outcome;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code !bossgear} on companion-ops (docs/COMPANION_BOSSING_PLAN.md G13): the GM's own ACTIVE companion, or the GM,
 * gets the planned kit (over the seed knowledge snapshot and a fixture forge catalog); a companion wears it on its own
 * mailbox. Everyone else, a companion that is busy, a bag too small and knowledge not loaded are refused.
 */
class CompanionGearToolTest {
    private static final int BOSSFRIEND = 870;
    private static final int FLINT = 910;
    private static final int BEGINNER_SWORD = 1302000;
    private static final int BLUE_JEAN_SHORTS = 1060002;
    private static final short TOP = -5;
    private static final short PANTS = -6;
    private static final short WEAPON = -11;
    private static final Scroll SIXTY = new Scroll(2049990, 60, Map.of("PAD", 2, "DEX", 1));
    private static final Scroll TEN = new Scroll(2049991, 10, Map.of("PAD", 5, "DEX", 3));
    private static final Catalog CATALOG = new Catalog() {
        @Override
        public Map<String, Integer> equipStats(int itemId) {
            return Map.of("tuc", 7);
        }

        @Override
        public List<Scroll> scrollsFor(int itemId) {
            return List.of(SIXTY, TEN);
        }
    };

    private final LivingWorld world = mock(LivingWorld.class);
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final RosterService roster = mock(RosterService.class);
    private final BenchDutyDispatcher bench = mock(BenchDutyDispatcher.class);
    private final CompanionPartyQuestService pq = mock(CompanionPartyQuestService.class);
    private final Character gm = mock(Character.class);
    private final Character flint = mock(Character.class);
    private final FakeGame game = new FakeGame();
    private Inventory flintWears;
    private AutoCloseable seed;
    private CompanionGearTool tool;

    /** The game: every mailbox wear and every gift to the GM is kept; the mailbox answers at once. */
    private static final class FakeGame implements CompanionGearTool.Game {
        final List<List<Piece>> worn = new ArrayList<>();
        final List<List<Piece>> given = new ArrayList<>();
        final List<Integer> needs = new ArrayList<>();
        /** The owner of every mailbox removal, in order. */
        final List<Character> removals = new ArrayList<>();
        /** The runtime every lookup finds: its bot is the companion under test. */
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        boolean engaged;
        boolean inBossRun;
        /** The mailbox finds every piece worn already (a use planned before the last kit went on). */
        boolean woreAlready;

        @Override
        public AgentRuntimeEntry runtime(int characterId) {
            return entry;
        }

        @Override
        public boolean engaged(int characterId) {
            return engaged;
        }

        @Override
        public boolean inBossRun(int characterId) {
            return inBossRun;
        }

        /** ItemInformationProvider.getWeaponType's WZ category table. */
        @Override
        public WeaponType weaponType(int itemId) {
            return switch (itemId / 10_000) {
                case 130 -> WeaponType.SWORD1H;
                case 131, 132 -> WeaponType.GENERAL1H_SWING;
                case 133 -> WeaponType.DAGGER_OTHER;
                case 137 -> WeaponType.WAND;
                case 138 -> WeaponType.STAFF;
                case 140 -> WeaponType.SWORD2H;
                case 141, 142 -> WeaponType.GENERAL2H_SWING;
                case 143 -> WeaponType.SPEAR_STAB;
                case 144 -> WeaponType.POLE_ARM_SWING;
                case 145 -> WeaponType.BOW;
                case 146 -> WeaponType.CROSSBOW;
                case 147 -> WeaponType.CLAW;
                case 148 -> WeaponType.KNUCKLE;
                case 149 -> WeaponType.GUN;
                default -> WeaponType.NOT_A_WEAPON;
            };
        }

        @Override
        public CompletableFuture<Outcome> wear(AgentRuntimeEntry runtime, List<Piece> kit, int need) {
            worn.add(kit);
            needs.add(need);
            return CompletableFuture.completedFuture(woreAlready ? new Outcome(0, 0, kit.size())
                    : new Outcome(kit.size(), kit.size()));
        }

        @Override
        public int give(Character gm, List<Piece> kit) {
            given.add(kit);
            return kit.size();
        }

        /** The mailbox runs the removal at once, as LIVE's does on the companion's tick. */
        @Override
        public CompletableFuture<Integer> remove(AgentRuntimeEntry runtime, Character owner) {
            removals.add(owner);
            return CompletableFuture.completedFuture(CompanionGearTool.removeKit(runtime, owner));
        }
    }

    @BeforeEach
    void wire() {
        seed = KnowledgeTestKit.publishSeed();
        when(world.directory()).thenReturn(directory);
        when(world.roster()).thenReturn(roster);
        when(world.benchDuty()).thenReturn(bench);
        when(world.partyQuests()).thenReturn(pq);
        CompanionExecutor inline = DirectExecutor.inline(); // stubs inside: not within another when()
        when(world.executor()).thenReturn(inline);
        when(gm.getId()).thenReturn(BOSSFRIEND);
        when(gm.getName()).thenReturn("Bossfriend");
        strong(gm, Job.HERO, 160);
        when(gm.getInventory(InventoryType.EQUIPPED)).thenReturn(new Inventory(gm, InventoryType.EQUIPPED, (byte) 100));
        when(gm.getInventory(InventoryType.EQUIP)).thenReturn(new Inventory(gm, InventoryType.EQUIP, (byte) 24));
        when(flint.getId()).thenReturn(FLINT);
        when(flint.getName()).thenReturn("Flint");
        strong(flint, Job.CORSAIR, 160);
        flintWears = new Inventory(flint, InventoryType.EQUIPPED, (byte) 100);
        flintWears.addItemFromDB(Equip.restored(BEGINNER_SWORD, WEAPON));
        when(flint.getInventory(InventoryType.EQUIPPED)).thenReturn(flintWears);
        bag(24);
        when(roster.loaded("flint")).thenReturn(flint);
        when(game.entry.bot()).thenReturn(flint);
        companion(RosterState.ACTIVE);
        tool = new CompanionGearTool(world, game, CATALOG, 50L);
    }

    @AfterEach
    void unpublish() throws Exception {
        seed.close();
    }

    private static void strong(Character chr, Job job, int level) {
        when(chr.getJob()).thenReturn(job);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getStr()).thenReturn(999);
        when(chr.getDex()).thenReturn(999);
        when(chr.getInt()).thenReturn(999);
        when(chr.getLuk()).thenReturn(999);
    }

    private void bag(int slots) {
        when(flint.getInventory(InventoryType.EQUIP)).thenReturn(new Inventory(flint, InventoryType.EQUIP, (byte) slots));
    }

    private void companion(RosterState state) {
        RosterEntry entry = new RosterEntry(BOSSFRIEND, "flint", state, LivingStore.Assignment.TRAIN, 0, 0, 0, 0L, 0L,
                0L, 0L, 0L, 0L, 0, null);
        when(directory.resolveName(gm, "Flint")).thenReturn(new Relation.OwnCompanion(entry));
    }

    private String use(String name, boolean preview) throws Exception {
        return tool.use(gm, name, preview).get(5, TimeUnit.SECONDS);
    }

    private static Piece at(List<Piece> kit, short position) {
        return kit.stream().filter(piece -> piece.position() == position).findFirst().orElseThrow();
    }

    @Test
    void aCorsairWearingABeginnerSwordGetsAGunOnItsMailbox() throws Exception {
        String reply = use("Flint", false);

        assertEquals(1, game.worn.size(), reply);
        List<Piece> kit = game.worn.get(0);
        assertEquals(149, at(kit, WEAPON).itemId() / 10_000, "the sword fails isWeaponCompatible for a Corsair: " + kit);
        for (Piece piece : kit) {
            assertEquals(List.of(new Use(SIXTY, 7, 7)), piece.plan().uses(), piece.toString());
        }
        assertTrue(reply.startsWith("Flint now wears " + kit.size() + " boss pieces"), reply);
        assertEquals(List.of(), game.given);
    }

    @Test
    void previewGivesNothingAndEveryUseWritesOneLogLine() throws Exception {
        try (LivingLogLines log = new LivingLogLines(CompanionGearTool.class)) {
            String reply = use("Flint", true);

            assertTrue(reply.contains("Nothing was given"), reply);
            assertEquals(List.of(), game.worn);
            assertEquals(List.of(), game.given);
            assertEquals(1, log.startingWith("gm bossgear:").size(), reply);
            assertTrue(log.startingWith("gm bossgear:").get(0).contains("preview=true"));

            use("Flint", false);
            assertEquals(2, log.startingWith("gm bossgear:").size());
        }
    }

    @Test
    void theGmGetsTheKitInTheirOwnBag() throws Exception {
        String reply = use("me", false);

        assertEquals(1, game.given.size(), reply);
        assertEquals(130, at(game.given.get(0), WEAPON).itemId() / 10_000,
                "a Hero wearing no weapon gets the first type it can use: a one-handed sword");
        assertEquals(List.of(), game.worn);
        assertTrue(reply.contains("in your Equip tab"), reply);
    }

    @Test
    void aSecondUseSkipsWhatItAlreadyWearsAsBossGear() throws Exception {
        use("Flint", false);
        int gun = at(game.worn.get(0), WEAPON).itemId();
        Item worn = Equip.restored(gun, WEAPON);
        worn.setOwner(BossGearPlanner.OWNER_TAG);
        flintWears.removeSlot(WEAPON);
        flintWears.addItemFromDB(worn);

        use("Flint", false);

        assertFalse(game.worn.get(1).stream().anyMatch(piece -> piece.position() == WEAPON), game.worn.get(1).toString());
    }

    @Test
    void aPlainWornCopyOfThePickedItemStillGetsTheBossPiece() throws Exception {
        use("Flint", false);
        int gun = at(game.worn.get(0), WEAPON).itemId();
        flintWears.removeSlot(WEAPON);
        flintWears.addItemFromDB(Equip.restored(gun, WEAPON)); // bought, looted or shaped: no tag, no scrolls

        use("Flint", false);

        assertEquals(gun, at(game.worn.get(1), WEAPON).itemId(), game.worn.get(1).toString());
    }

    @Test
    void onItsMailboxAKitPlannedBeforeTheLastOneWentOnSkipsEveryPieceItNowWears() throws Exception {
        use("Flint", false);
        List<Piece> kit = game.worn.get(0);
        for (Piece piece : kit) { // the first kit went on after this one was planned
            Item worn = Equip.restored(piece.itemId(), piece.position());
            worn.setOwner(BossGearPlanner.OWNER_TAG);
            flintWears.removeSlot(piece.position());
            flintWears.addItemFromDB(worn);
        }
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        when(entry.bot()).thenReturn(flint);

        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            Outcome outcome = CompanionGearTool.wearKit(entry, kit, game.needs.get(0));

            assertEquals(new Outcome(0, 0, kit.size()), outcome);
            inventory.verifyNoInteractions();
        }
    }

    @Test
    void aMailboxThatFoundEveryPieceWornAlreadySaysSo() throws Exception {
        game.woreAlready = true;

        String reply = use("Flint", false);

        assertEquals("Flint already wears boss gear in every slot it has a piece for.", reply);
    }

    @Test
    void theBagNeedsASlotPerPiecePlusOneForPantsAnOverallPushesOff() throws Exception {
        flintWears.addItemFromDB(Equip.restored(BLUE_JEAN_SHORTS, PANTS));
        use("Flint", false);
        List<Piece> kit = game.worn.get(0);
        assertEquals("longcoat", at(kit, TOP).category(), "a pirate's overall: " + kit);
        assertEquals(List.of(kit.size() + 1), game.needs, "the overall pushes the worn pants into the bag");

        bag(kit.size());
        String reply = use("Flint", false);
        assertTrue(reply.contains("needs " + (kit.size() + 1) + " free Equip slots"), reply);
        assertEquals(1, game.worn.size(), "nothing more went to the mailbox");
    }

    @Test
    void everyoneButTheGmAndTheirOwnActiveCompanionIsRefused() throws Exception {
        when(directory.resolveName(gm, "Ohboy")).thenReturn(new Relation.Human(1));
        when(directory.resolveName(gm, "Garrick")).thenReturn(new Relation.OthersCompanion(2, "garrick"));
        when(directory.resolveName(gm, "Lakelis")).thenReturn(new Relation.Resident("lakelis"));
        when(directory.resolveName(gm, "Tam")).thenReturn(new Relation.Citizen(3));
        when(directory.resolveName(gm, "Probe")).thenReturn(new Relation.PlainAgent(4));

        assertTrue(use("Ohboy", false).contains("is a player"));
        assertTrue(use("Garrick", false).contains("another player's companion"));
        assertTrue(use("Lakelis", false).contains("is a Resident"));
        assertTrue(use("Tam", false).contains("is a citizen"));
        assertTrue(use("Probe", false).contains("not your companion"));
        companion(RosterState.BENCHED);
        assertTrue(use("Flint", false).contains("not out with you"));
        assertNothingGiven();
    }

    @Test
    void aBusyCompanionIsRefused() throws Exception {
        when(bench.live("flint")).thenReturn(true);
        assertTrue(use("Flint", false).contains("bench slice"));
        when(bench.live("flint")).thenReturn(false);

        when(flint.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertTrue(use("Flint", false).contains("event instance"));
        when(flint.getEventInstance()).thenReturn(null);

        game.engaged = true;
        assertTrue(use("Flint", false).contains("in a party quest"));
        game.engaged = false;

        when(pq.runForCharacter(FLINT)).thenReturn(mock(CompanionRun.class));
        assertTrue(use("Flint", false).contains("companion party quest run"));
        when(pq.runForCharacter(FLINT)).thenReturn(null);
        when(pq.entering(BOSSFRIEND)).thenReturn(true);
        assertTrue(use("Flint", false).contains("companion party quest run"));
        when(pq.entering(BOSSFRIEND)).thenReturn(false);

        game.inBossRun = true;
        assertTrue(use("Flint", false).contains("companion boss run"));
        assertNothingGiven();
    }

    @Test
    void knowledgeNotLoadedIsRefused() throws Exception {
        seed.close();
        seed = () -> { };

        assertTrue(use("Flint", false).contains("knowledge is not loaded"));
        assertTrue(use("me", false).contains("knowledge is not loaded"));
        assertNothingGiven();
    }

    private void assertNothingGiven() {
        assertEquals(List.of(), game.worn);
        assertEquals(List.of(), game.given);
    }

    // ---- !bossgear <me|name> remove ----------------------------------------------------------

    private static final int BOSS_GUN = 1492013;
    private static final int BOSS_CAPE = 1102084;
    private static final int PLAIN_CAP = 1002004;
    private static final int QUEST_GLOVES = 1082149;
    private static final short CAP = -1;

    /** A piece of a !bossgear kit, as BossGearPlanner.forge tags it. */
    private static Item boss(int itemId, short position) {
        Item item = Equip.restored(itemId, position);
        item.setOwner(BossGearPlanner.OWNER_TAG);
        item.setFlag((short) (item.getFlag() | ItemConstants.UNTRADEABLE));
        return item;
    }

    /** Flint wears the boss gun and a plain cap, and carries the boss cape, plain shorts and its own quest gloves. */
    private Inventory flintCarriesAKit() {
        flintWears.removeSlot(WEAPON);
        flintWears.addItemFromDB(boss(BOSS_GUN, WEAPON));
        flintWears.addItemFromDB(Equip.restored(PLAIN_CAP, CAP));
        Inventory bag = new Inventory(flint, InventoryType.EQUIP, (byte) 24);
        bag.addItemFromDB(boss(BOSS_CAPE, (short) 1));
        bag.addItemFromDB(Equip.restored(BLUE_JEAN_SHORTS, (short) 2));
        Item gloves = Equip.restored(QUEST_GLOVES, (short) 3);
        gloves.setOwner("Flint"); // its own Resident quest piece: tagged, but not boss gear
        bag.addItemFromDB(gloves);
        when(flint.getInventory(InventoryType.EQUIP)).thenReturn(bag);
        return bag;
    }

    private static List<Integer> ids(Inventory inventory) {
        return inventory.list().stream().map(Item::getItemId).sorted().toList();
    }

    @Test
    void removeTakesEveryBossPieceOffItsMailboxKeepsTheRestAndItWearsItsOwnGearAgain() throws Exception {
        Inventory bag = flintCarriesAKit();

        try (MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class)) {
            String reply = tool.remove(gm, "Flint").get(5, TimeUnit.SECONDS);

            assertEquals("Flint gave back 2 boss pieces and wears its own gear again.", reply);
            assertEquals(List.of(gm), game.removals, "on its own mailbox");
            assertEquals(List.of(PLAIN_CAP), ids(flintWears), "the boss gun is gone, the plain cap stays on");
            assertEquals(List.of(BLUE_JEAN_SHORTS, QUEST_GLOVES).stream().sorted().toList(), ids(bag),
                    "the boss cape is gone; the shorts and its own tagged quest gloves stay");
            equipment.verify(() -> AgentEquipmentService.autoEquip(flint, gm, null, true));
            verify(flint).saveCharToDB();
        }
    }

    @Test
    void removeForTheGmTakesTheBossPiecesOutOfTheirOwnInventory() throws Exception {
        Inventory worn = gm.getInventory(InventoryType.EQUIPPED);
        Inventory bag = gm.getInventory(InventoryType.EQUIP);
        worn.addItemFromDB(boss(BOSS_GUN, WEAPON));
        worn.addItemFromDB(Equip.restored(PLAIN_CAP, CAP));
        bag.addItemFromDB(boss(BOSS_CAPE, (short) 1));

        String reply = tool.remove(gm, "me").get(5, TimeUnit.SECONDS);

        assertEquals("2 boss pieces were taken from you.", reply);
        assertEquals(List.of(PLAIN_CAP), ids(worn));
        assertEquals(List.of(), ids(bag));
        verify(gm).saveCharToDB();
        assertEquals(List.of(), game.removals, "the GM's own: no mailbox");
    }

    @Test
    void removeLeavesACompanionWithoutBossGearAsItIs() throws Exception {
        flintWears.addItemFromDB(Equip.restored(PLAIN_CAP, CAP));
        Inventory bag = new Inventory(flint, InventoryType.EQUIP, (byte) 24);
        bag.addItemFromDB(Equip.restored(BLUE_JEAN_SHORTS, (short) 1));
        when(flint.getInventory(InventoryType.EQUIP)).thenReturn(bag);

        try (MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class)) {
            String reply = tool.remove(gm, "Flint").get(5, TimeUnit.SECONDS);

            assertEquals("Flint has no boss gear.", reply);
            assertEquals(List.of(PLAIN_CAP, BEGINNER_SWORD), ids(flintWears));
            assertEquals(List.of(BLUE_JEAN_SHORTS), ids(bag));
            equipment.verifyNoInteractions();
            verify(flint, never()).saveCharToDB();
        }
        assertEquals("You have no boss gear.", tool.remove(gm, "me").get(5, TimeUnit.SECONDS));
        verify(gm, never()).saveCharToDB();
    }

    @Test
    void removeIsRefusedWhereAGiftIs() throws Exception {
        flintCarriesAKit();
        when(directory.resolveName(gm, "Lakelis")).thenReturn(new Relation.Resident("lakelis"));

        game.inBossRun = true;
        assertTrue(tool.remove(gm, "Flint").get(5, TimeUnit.SECONDS).contains("companion boss run"));
        game.inBossRun = false;
        assertTrue(tool.remove(gm, "Lakelis").get(5, TimeUnit.SECONDS).contains("is a Resident"));
        companion(RosterState.BENCHED);
        assertTrue(tool.remove(gm, "Flint").get(5, TimeUnit.SECONDS).contains("not out with you"));

        assertEquals(List.of(), game.removals);
        assertEquals(2, flintWears.list().size(), "nothing came off");
    }
}
