package server.living.roster;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
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
        boolean engaged;
        boolean inBossRun;

        @Override
        public AgentRuntimeEntry runtime(int characterId) {
            return mock(AgentRuntimeEntry.class);
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
            return CompletableFuture.completedFuture(new Outcome(kit.size(), kit.size()));
        }

        @Override
        public int give(Character gm, List<Piece> kit) {
            given.add(kit);
            return kit.size();
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
}
