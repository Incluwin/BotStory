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
import constants.skills.Fighter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.knowledge.KnowledgeTestKit;
import server.living.LivingWorld;
import server.living.quest.BossGearPlanner;
import server.living.quest.BossGearPlanner.Piece;
import server.living.quest.FriendForge;
import server.living.quest.FriendForge.Catalog;
import server.living.quest.FriendForge.Scroll;
import server.living.quest.FriendForge.Use;
import server.living.roster.BossGearRecord.Displaced;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.CompanionGearTool.Outcome;
import server.living.store.LivingStore;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
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
    /** The living event log the record of what a kit displaced lives in (BossGearRecord). */
    private final FakeLivingStore store = new FakeLivingStore();
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
        /** The record of what the kit displaced each mailbox removal was handed (by item id), in order. */
        final List<Map<Short, Integer>> removedWith = new ArrayList<>();
        /** What the next mailbox wear says the kit displaced (each by its id alone), and if it went onto a kit. */
        Map<Short, Integer> displaced = Map.of();
        boolean ontoAKit;
        /** The runtime every lookup finds: its bot is the companion under test. */
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        boolean engaged;
        boolean inBossRun;
        /** The mailbox finds every piece worn already (a use planned before the last kit went on). */
        boolean woreAlready;
        /**
         * The companion's mailbox: null runs each action at once (a prompt tick); else each waits here, in order, until
         * the test runs it (a slow tick), and completes as it runs, as AgentActionMailbox does.
         */
        List<Runnable> mailbox;

        private <T> CompletableFuture<T> onMailbox(Supplier<T> action) {
            if (mailbox == null) {
                return CompletableFuture.completedFuture(action.get());
            }
            CompletableFuture<T> result = new CompletableFuture<>();
            mailbox.add(() -> result.complete(action.get()));
            return result;
        }

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
            Outcome outcome = woreAlready ? new Outcome(0, 0, kit.size())
                    : new Outcome(kit.size(), kit.size(), 0, byId(displaced), ontoAKit);
            return onMailbox(() -> outcome);
        }

        @Override
        public int give(Character gm, List<Piece> kit) {
            given.add(kit);
            return kit.size();
        }

        /** The mailbox runs the removal (at once, or as the test runs it), as LIVE's does on the companion's tick. */
        @Override
        public CompletableFuture<Integer> remove(AgentRuntimeEntry runtime, Character owner,
                                                 Map<Short, Displaced> displaced) {
            removals.add(owner);
            removedWith.add(ids(displaced));
            return onMailbox(() -> CompanionGearTool.removeKit(runtime, owner, displaced));
        }

        @Override
        public CompletableFuture<?> settle(AgentRuntimeEntry runtime) {
            return onMailbox(() -> Boolean.TRUE);
        }
    }

    @BeforeEach
    void wire() {
        seed = KnowledgeTestKit.publishSeed();
        when(world.directory()).thenReturn(directory);
        when(world.roster()).thenReturn(roster);
        when(world.benchDuty()).thenReturn(bench);
        when(world.partyQuests()).thenReturn(pq);
        when(world.store()).thenReturn(store.store);
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
            assertEquals(List.of(new Use(TEN, 7, 7)), piece.plan().uses(), piece.toString());
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
    void theBagNeedsASlotPerWornPiecePlusOneForPantsAnOverallPushesOff() throws Exception {
        flintWears.addItemFromDB(Equip.restored(BLUE_JEAN_SHORTS, PANTS));
        use("Flint", false);
        List<Piece> kit = game.worn.get(0);
        assertEquals("longcoat", at(kit, TOP).category(), "a pirate's overall: " + kit);
        assertEquals(List.of(2), game.needs, "the worn weapon swaps into the bag and the overall pushes the pants off");

        bag(1);
        String reply = use("Flint", false);
        assertTrue(reply.contains("needs 2 free Equip slots"), reply);
        assertEquals(1, game.worn.size(), "nothing more went to the mailbox");
    }

    @Test
    void piecesForEmptySlotsNeedOnlyTheOneSlotEachPassesThrough() throws Exception {
        flintWears.removeSlot(WEAPON);
        bag(1);

        use("Flint", false);

        assertEquals(1, game.worn.size(), "a full bag with one free slot still takes a kit for bare slots");
        assertEquals(List.of(1), game.needs);
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
            assertEquals(List.of(), store.events(BossGearRecord.REMOVED), "no kit came off: nothing ends");
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

    // ---- what the kit displaced (BossGearRecord) ------------------------------------------------------

    private static final int WHITE_BANDANA = 1002019;
    private static final int BONE_HELM = 1002006;
    private static final int UNDERSHIRT = 1040002;
    private static final int BOSS_CAP = 1002357;
    private static final int BOSS_OVERALL = 1052075;
    private static final short CAPE = -9;

    /** The record, by worn slot: each piece's item id. */
    private Map<Short, Integer> record() {
        return ids(BossGearRecord.read(store.store, BOSSFRIEND, "flint"));
    }

    private static Map<Short, Integer> ids(Map<Short, Displaced> displaced) {
        Map<Short, Integer> out = new HashMap<>();
        displaced.forEach((slot, piece) -> out.put(slot, piece.itemId()));
        return out;
    }

    /** A record that knows each piece by its id alone. */
    private static Map<Short, Displaced> byId(Map<Short, Integer> ids) {
        Map<Short, Displaced> out = new HashMap<>();
        ids.forEach((slot, itemId) -> out.put(slot, Displaced.of(itemId)));
        return out;
    }

    @Test
    void aKitThatGoesOnRecordsWhatItDisplacedWithTheCompanion() throws Exception {
        game.displaced = Map.of(CAP, WHITE_BANDANA, PANTS, BLUE_JEAN_SHORTS);

        use("Flint", false);

        assertEquals(List.of(BOSSFRIEND + " flint bossgear.worn displaced: -1:" + WHITE_BANDANA + " -6:"
                + BLUE_JEAN_SHORTS), store.events(BossGearRecord.WORN));
        assertEquals(Map.of(CAP, WHITE_BANDANA, PANTS, BLUE_JEAN_SHORTS), record());
    }

    @Test
    void aKitAddedToTheOneItWearsKeepsTheFirstRecordAndAFreshKitStartsANewOne() throws Exception {
        game.displaced = Map.of(CAP, WHITE_BANDANA);
        use("Flint", false);
        game.displaced = Map.of(PANTS, BLUE_JEAN_SHORTS);
        game.ontoAKit = true;
        use("Flint", false);

        assertEquals(Map.of(CAP, WHITE_BANDANA, PANTS, BLUE_JEAN_SHORTS), record(),
                "the second kit went onto the first");

        game.displaced = Map.of(TOP, UNDERSHIRT);
        game.ontoAKit = false;
        use("Flint", false);

        assertEquals(Map.of(TOP, UNDERSHIRT), record(), "the old kit came off some other way: a new record");
    }

    @Test
    void theGmsOwnKitAndAMailboxThatGaveNothingRecordNothing() throws Exception {
        use("me", false);
        game.woreAlready = true;
        use("Flint", false);

        assertEquals(List.of(), store.events(BossGearRecord.WORN));
    }

    /** A kit piece as BossGearPlanner plans it (the forge is stubbed: no WZ). */
    private static Piece piece(short position, String category, int itemId) {
        return new Piece(position, category, new FriendForge.Plan(itemId, 7, List.of()));
    }

    /**
     * InventoryManipulator as the game runs it for Flint (no client): a drop goes in the next free bag slot, and an
     * equip swaps what the slot wore into the piece's bag slot, an overall first pushing worn pants into the next free
     * slot (InventoryManipulator.equip).
     */
    private static void playsLikeTheGame(MockedStatic<InventoryManipulator> inventory, Inventory worn, Inventory bag) {
        inventory.when(() -> InventoryManipulator.addFromDrop(any(), any(Item.class), anyBoolean()))
                .thenAnswer(inv -> bag.addItem(inv.getArgument(1)) != -1);
        inventory.when(() -> InventoryManipulator.equip(any(), anyShort(), anyShort())).thenAnswer(inv -> {
            short src = inv.getArgument(1);
            short dst = inv.getArgument(2);
            Item piece = bag.getItem(src);
            Item pants = worn.getItem(PANTS);
            if (dst == TOP && pants != null && ItemConstants.isOverall(piece.getItemId())) {
                worn.removeSlot(PANTS);
                pants.setPosition(bag.getNextFreeSlot());
                bag.addItemFromDB(pants);
            }
            bag.removeSlot(src);
            Item old = worn.getItem(dst);
            if (old != null) {
                worn.removeSlot(dst);
                old.setPosition(src);
                bag.addItemFromDB(old);
            }
            piece.setPosition(dst);
            worn.addItemFromDB(piece);
            return null;
        });
    }

    @Test
    void onItsMailboxTheKitNamesEachOwnPieceItPutInTheBagThePantsTheOverallPushedOffAmongThem() throws Exception {
        flintWears.addItemFromDB(Equip.restored(WHITE_BANDANA, CAP));
        flintWears.addItemFromDB(Equip.restored(UNDERSHIRT, TOP));
        flintWears.addItemFromDB(Equip.restored(BLUE_JEAN_SHORTS, PANTS));
        Inventory bag = new Inventory(flint, InventoryType.EQUIP, (byte) 24);
        bag.addItemFromDB(Equip.restored(BONE_HELM, (short) 1));
        when(flint.getInventory(InventoryType.EQUIP)).thenReturn(bag);
        int gun = 1492013;
        List<Piece> kit = List.of(piece(CAP, "cap", BOSS_CAP), piece(TOP, "longcoat", BOSS_OVERALL),
                piece(WEAPON, "weapon", gun));
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        when(entry.bot()).thenReturn(flint);

        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class);
             MockedStatic<BossGearPlanner> forge = mockStatic(BossGearPlanner.class);
             MockedStatic<AgentInventoryReservationRuntime> reservations =
                     mockStatic(AgentInventoryReservationRuntime.class)) {
            playsLikeTheGame(inventory, flintWears, bag);
            forge.when(() -> BossGearPlanner.forge(any())).thenAnswer(inv -> boss(((Piece) inv.getArgument(0))
                    .itemId(), (short) 0));

            Outcome outcome = CompanionGearTool.wearKit(entry, kit, 4);

            assertEquals(3, outcome.worn(), outcome.toString());
            assertEquals(Map.of(CAP, WHITE_BANDANA, TOP, UNDERSHIRT, PANTS, BLUE_JEAN_SHORTS, WEAPON, BEGINNER_SWORD),
                    ids(outcome.displaced()));
            outcome.displaced().forEach((slot, piece) -> {
                assertTrue(piece.exact(), slot + ": " + piece);
                assertEquals(Displaced.of(bag.getItem(piece.bagSlot())), piece,
                        slot + ": the very piece in that bag slot");
            });
            assertFalse(outcome.ontoAKit());

            Outcome second = CompanionGearTool.wearKit(entry, List.of(piece(CAP, "cap", BOSS_CAP + 1),
                    piece(CAPE, "cape", BOSS_CAPE)), 2);

            assertEquals(2, second.worn(), second.toString());
            assertEquals(Map.of(), second.displaced(), "a boss cap swapped for another is no piece of its own");
            assertTrue(second.ontoAKit());
        }
    }

    @Test
    void removeWearsWhatTheKitDisplacedFirstAndEndsTheRecord() throws Exception {
        flintWears.addItemFromDB(boss(BOSS_CAP, CAP));
        Inventory bag = new Inventory(flint, InventoryType.EQUIP, (byte) 24);
        bag.addItemFromDB(Equip.restored(BONE_HELM, (short) 1));
        Item bandana = Equip.restored(WHITE_BANDANA, (short) 2);
        bag.addItemFromDB(bandana);
        when(flint.getInventory(InventoryType.EQUIP)).thenReturn(bag);
        BossGearRecord.write(store.store, BOSSFRIEND, "flint", byId(Map.of(CAP, WHITE_BANDANA)));

        try (MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class);
             MockedStatic<AgentInventoryReservationRuntime> reservations =
                     mockStatic(AgentInventoryReservationRuntime.class)) {
            String reply = tool.remove(gm, "Flint").get(5, TimeUnit.SECONDS);

            assertEquals("Flint gave back 1 boss piece and wears its own gear again.", reply);
            assertEquals(List.of(Map.of(CAP, WHITE_BANDANA)), game.removedWith, "the record went to its mailbox");
            assertEquals(bandana, flintWears.getItem(CAP), "its own bandana, not the Lv42 Bone Helm it also carries");
            assertEquals(List.of(BONE_HELM), ids(bag));
            equipment.verifyNoInteractions(); // it wears a weapon: the optimizer would trade the bandana away
            reservations.verify(() -> AgentInventoryReservationRuntime.refreshEquipmentReservations(any(), any(),
                    anyLong()));
            verify(flint).saveCharToDB();
        }
        assertEquals(List.of(BOSSFRIEND + " flint bossgear.removed remove: 1 piece"),
                store.events(BossGearRecord.REMOVED));
        assertEquals(Map.of(), record(), "the record ended");
    }

    // ---- a weapon its build fights with (AgentWeaponCompatibilityPolicy) ------------------------------

    private static final int BOSS_TWO_HANDED = 1402011;
    private static final int GLADIUS = 1302008;
    private static final int BLUE_COUNTER = 1312007;

    @Test
    void removeWearsTheSwordASwordFighterFightsWithNotAHigherAxeItsJobMaskAllows() throws Exception {
        when(flint.getJob()).thenReturn(Job.FIGHTER);
        when(flint.getSkillLevel(Fighter.SWORD_MASTERY)).thenReturn(1);
        flintWears.removeSlot(WEAPON);
        flintWears.addItemFromDB(boss(BOSS_TWO_HANDED, WEAPON)); // its own two-handed sword was sold meanwhile
        Inventory bag = new Inventory(flint, InventoryType.EQUIP, (byte) 24);
        bag.addItemFromDB(Equip.restored(GLADIUS, (short) 1));
        bag.addItemFromDB(Equip.restored(BLUE_COUNTER, (short) 2));
        when(flint.getInventory(InventoryType.EQUIP)).thenReturn(bag);

        try (MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class);
             MockedStatic<AgentInventoryReservationRuntime> reservations =
                     mockStatic(AgentInventoryReservationRuntime.class)) {
            String reply = tool.remove(gm, "Flint").get(5, TimeUnit.SECONDS);

            assertEquals("Flint gave back 1 boss piece and wears its own gear again.", reply);
            assertEquals(GLADIUS, flintWears.getItem(WEAPON).getItemId(),
                    "its one-handed sword, not the Lv40 axe the warrior mask also lets it wear");
            assertEquals(List.of(BLUE_COUNTER), ids(bag));
            equipment.verifyNoInteractions();
        }
    }

    // ---- remove typed while a gift is still on its way: the record is read after the gift wrote it ---------

    /**
     * Flint wears the boss cap a gift puts on (FakeGame.wear only answers) and carries its own bandana the kit
     * displaced and a higher Bone Helm; companion-ops keeps each command in {@code ops} until the test runs it.
     */
    private Item aGiftOnItsWay(List<Runnable> ops) {
        flintWears.addItemFromDB(boss(BOSS_CAP, CAP));
        Inventory bag = new Inventory(flint, InventoryType.EQUIP, (byte) 24);
        bag.addItemFromDB(Equip.restored(BONE_HELM, (short) 1));
        Item bandana = Equip.restored(WHITE_BANDANA, (short) 2);
        bag.addItemFromDB(bandana);
        when(flint.getInventory(InventoryType.EQUIP)).thenReturn(bag);
        CompanionExecutor queued = DirectExecutor.queued(ops); // stubs inside: not within another when()
        when(world.executor()).thenReturn(queued);
        game.displaced = Map.of(CAP, WHITE_BANDANA);
        tool = new CompanionGearTool(world, game, CATALOG, 60_000L);
        return bandana;
    }

    /** Runs the mailbox and companion-ops, each in its own order, until both are idle. */
    private void runUntilIdle(List<Runnable> ops) {
        List<Runnable> mailbox = game.mailbox == null ? List.of() : game.mailbox;
        while (!mailbox.isEmpty() || !ops.isEmpty()) {
            while (!mailbox.isEmpty()) {
                mailbox.remove(0).run();
            }
            while (!ops.isEmpty()) {
                ops.remove(0).run();
            }
        }
    }

    private void assertTheRemovalUsedTheGiftsRecord(Item bandana, CompletableFuture<String> removal) throws Exception {
        assertEquals(List.of(Map.of(CAP, WHITE_BANDANA)), game.removedWith,
                "the gift's record reached its mailbox, not the empty one from before the gift");
        assertEquals(bandana, flintWears.getItem(CAP), "its own bandana, not the higher Bone Helm");
        assertEquals("Flint gave back 1 boss piece and wears its own gear again.", removal.get(5, TimeUnit.SECONDS));
        assertEquals(List.of(BOSSFRIEND + " flint bossgear.worn displaced: -1:" + WHITE_BANDANA, BOSSFRIEND
                + " flint bossgear.removed remove: 1 piece"), store.events.stream()
                .filter(line -> line.contains(" bossgear.")).toList(), "the record, then its end");
        assertEquals(Map.of(), record());
    }

    @Test
    void removeTypedWhileCompanionOpsStillOwesTheGiftsAnswerUsesTheRecordThatAnswerWrites() throws Exception {
        List<Runnable> ops = DirectExecutor.queue();
        Item bandana = aGiftOnItsWay(ops);

        try (MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class);
             MockedStatic<AgentInventoryReservationRuntime> reservations =
                     mockStatic(AgentInventoryReservationRuntime.class)) {
            tool.use(gm, "Flint", false); // its mailbox answered at once: the answer waits on companion-ops
            CompletableFuture<String> removal = tool.remove(gm, "Flint");
            runUntilIdle(ops);

            assertTheRemovalUsedTheGiftsRecord(bandana, removal);
        }
    }

    @Test
    void removeTypedWhileItsMailboxStillOwesTheGiftUsesTheRecordTheGiftLeaves() throws Exception {
        List<Runnable> ops = DirectExecutor.queue();
        Item bandana = aGiftOnItsWay(ops);
        game.mailbox = new ArrayList<>(); // a slow tick: the kit is not on yet

        try (MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class);
             MockedStatic<AgentInventoryReservationRuntime> reservations =
                     mockStatic(AgentInventoryReservationRuntime.class)) {
            tool.use(gm, "Flint", false);
            CompletableFuture<String> removal = tool.remove(gm, "Flint");
            runUntilIdle(ops);

            assertTheRemovalUsedTheGiftsRecord(bandana, removal);
        }
    }

    // ---- the very copy the kit displaced, of two carried with its id (BossGearRecord.Displaced) ----------

    @Test
    void ofTwoBandanasItCarriesTheOneTheKitTookOffGoesBackOn() throws Exception {
        Equip own = Equip.restored(WHITE_BANDANA, CAP); // scrolled: three passed
        own.setWdef((short) 21);
        own.setLevel((byte) 3);
        flintWears.addItemFromDB(own);
        Inventory bag = new Inventory(flint, InventoryType.EQUIP, (byte) 24);
        Item looted = Equip.restored(WHITE_BANDANA, (short) 1);
        bag.addItemFromDB(looted);
        when(flint.getInventory(InventoryType.EQUIP)).thenReturn(bag);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        when(entry.bot()).thenReturn(flint);

        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class);
             MockedStatic<BossGearPlanner> forge = mockStatic(BossGearPlanner.class);
             MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class);
             MockedStatic<AgentInventoryReservationRuntime> reservations =
                     mockStatic(AgentInventoryReservationRuntime.class)) {
            playsLikeTheGame(inventory, flintWears, bag);
            forge.when(() -> BossGearPlanner.forge(any())).thenAnswer(inv -> boss(((Piece) inv.getArgument(0))
                    .itemId(), (short) 0));
            Outcome outcome = CompanionGearTool.wearKit(entry, List.of(piece(CAP, "cap", BOSS_CAP)), 1);
            assertEquals(own, bag.getItem((short) 2), "the boss cap went to slot 2, and its bandana swapped in there");

            CompanionGearTool.removeKit(entry, gm, outcome.displaced());

            assertEquals(own, flintWears.getItem(CAP), "its scrolled bandana, not the plain one it looted into slot 1");
            assertEquals(List.of(looted), bag.list().stream().toList());
        }
    }

    // ---- a record that cannot be read is never written over -------------------------------------------

    @Test
    void aKitOntoAKitWhoseRecordCannotBeReadLeavesTheEarlierRecordAsItIs() throws Exception {
        game.displaced = Map.of(CAP, WHITE_BANDANA);
        use("Flint", false);
        doReturn(null).when(store.store).latestEvent(anyInt(), anyString(), any()); // the database read fails
        game.displaced = Map.of(PANTS, BLUE_JEAN_SHORTS);
        game.ontoAKit = true;

        try (LivingLogLines log = new LivingLogLines(CompanionGearTool.class)) {
            String reply = use("Flint", false);

            assertTrue(reply.startsWith("Flint now wears"), "the gift itself still goes on: " + reply);
            assertEquals(List.of(BOSSFRIEND + " flint bossgear.worn displaced: -1:" + WHITE_BANDANA),
                    store.events(BossGearRecord.WORN), "the first kit's row stays the newest; no row with only pants");
            assertEquals(1, log.startingWith("gm bossgear: Flint's record could not be read").size());
        }
    }
}
