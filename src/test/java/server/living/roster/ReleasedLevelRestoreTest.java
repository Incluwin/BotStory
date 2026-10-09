package server.living.roster;

import client.Job;
import client.LevelSetPlan;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.knowledge.KnowledgeTestKit;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.quest.BossGearPlanner;
import server.living.store.LivingStore;
import server.living.store.LivingStore.LevelRewrite;
import server.living.store.LivingStore.RosterState;
import server.living.store.LivingStore.StoredLevel;
import server.living.store.LivingStore.StoredSkill;
import server.living.store.LivingStore.WearStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * A released companion goes back to its own level (user decision 2026-10-09: the next friend who recruits or hires it
 * never gets a GM !setlevel level). Whatever lets it go (its owner, the inactivity sweep, its owner's deletion, a
 * persona the content lost, a hired hand: all RosterService.letGo), loaded or not, its GM level hold is restored in its
 * stored rows once its unload has it off the server and before its lease goes back, and the hold is released. A
 * companion no hold holds is left alone; bench and owner logout unload a held companion and keep its hold; a human's
 * own record is never touched; one online elsewhere is never rewritten under it; and the restore takes off what its
 * own level does not allow after the !bossgear strip put its own carried gear back on.
 */
class ReleasedLevelRestoreTest {
    private static final int HADRIK = 501;
    private static final int WREN = 601;
    private static final int GONE = 701;
    private static final String HADRIK_HELD = "lv=40 job=110 exp=1234";
    private static final int GLADIUS = 1302008;
    private static final int STEEL_SHIELD = 1092000;
    private static final int BOSS_SHIELD = 1092017;
    private static final int PLAIN_CAP = 1002004;
    private static final int QUEST_GLOVES = 1082149;
    private static final short CAP = -1;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;
    /** Level and reqJob mask of each piece (Character.wz). */
    private static final Map<Integer, int[]> REQUIREMENTS = Map.of(GLADIUS, new int[] {30, 1}, STEEL_SHIELD,
            new int[] {15, 1}, BOSS_SHIELD, new int[] {70, 1}, PLAIN_CAP, new int[] {35, 1}, QUEST_GLOVES,
            new int[] {10, 0});

    private final LifecycleHarness game = new LifecycleHarness();
    private final Persona hadrik = game.companion("hadrik", "Hadrik", HADRIK, RosterState.BENCHED, LITH);
    private final GmLevelHolds holds = new GmLevelHolds(game.world);
    /** "characterId persona" -> its newest gm.level.hold or gm.level.release row. */
    private final Map<String, LivingStore.EventRow> gmRows = new HashMap<>();
    /** Every gm.level row written, as "characterId persona kind detail". */
    private final List<String> gmWrites = new ArrayList<>();
    /** Character id -> its stored characters row and skills; its items are in game.store.storedItems. */
    private final Map<Integer, StoredLevel> stored = new HashMap<>();
    /** Every stored level rewrite, as "id lvL jobJ expE". */
    private final List<String> rewrites = new ArrayList<>();
    private final Map<Integer, LevelRewrite> written = new HashMap<>();
    /** The character's lease and whether its Agent was still registered, at each rewrite. */
    private final List<String> atRewrite = new ArrayList<>();

    ReleasedLevelRestoreTest() {
        when(game.world.contracts()).thenReturn(mock(ContractService.class));
        when(game.world.gmLevelHolds()).thenReturn(holds);
        LivingStore store = game.store.store;
        when(store.latestGmLevel(anyInt(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(gmRows.get(inv.getArgument(0) + " " + inv.getArgument(1))));
        when(store.eventChecked(anyInt(), any(), anyString(), any())).thenAnswer(inv -> gmWrites.add(
                inv.getArgument(0) + " " + inv.getArgument(1) + " " + inv.getArgument(2) + " " + inv.getArgument(3)));
        when(store.rewriteStoredLevel(anyInt(), any())).thenAnswer(inv -> rewrite(inv.getArgument(0),
                inv.getArgument(1)));
    }

    @AfterEach
    void close() {
        game.close();
    }

    /** Each piece's level and job requirement (ItemInformationProvider reads WZ and the database at load). */
    private static final ReleasedLevelRestore.Facts FACTS = new ReleasedLevelRestore.Facts() {
        @Override
        public LevelSetPlan.GearPiece piece(Item worn) {
            int[] req = REQUIREMENTS.get(worn.getItemId());
            return new LevelSetPlan.GearPiece(worn.getPosition(), worn.getItemId(), req[0], req[1], false);
        }

        @Override
        public int passiveX(int skillId, int skillLevel) {
            return 0;
        }

        @Override
        public LevelSetPlan.ApRules apRules() {
            return ReleasedLevelRestore.LIVE.apRules();
        }
    };

    /** The roster (built once the rows are seeded, as at boot), restoring with {@link #FACTS}. */
    private RosterService roster() {
        RosterService roster = game.roster();
        roster.levelRestoreFacts = FACTS;
        return roster;
    }

    /** The GM level hold on {@code characterId}'s persona: what it was before its first GM set. */
    private void held(int characterId, String personaId, String original) {
        gmRows.put(characterId + " " + personaId, new LivingStore.EventRow(GmLevelHolds.HOLD, original));
    }

    /** As a GM set it: a Lv70 Crusader with Warrior, Fighter and Crusader skills, its saved items as seeded. */
    private void setToCrusaderSeventy(int characterId) {
        stored.put(characterId, new StoredLevel(70, Job.CRUSADER.getId(), 900, 250, 60, 4, 4, 0,
                new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 0, 0, 24, List.of(new StoredSkill(1001004, 20, 0, -1),
                new StoredSkill(1101004, 20, 0, -1), new StoredSkill(1111002, 20, 0, -1)), List.of(), List.of()));
    }

    private void offline(int characterId) {
        when(game.server.getWorld(0).getPlayerStorage().getCharacterById(characterId)).thenReturn(null);
    }

    /** The companion out with Ann, wearing and carrying nothing (its unload's !bossgear strip finds nothing). */
    private void outWithAnn(Persona persona) {
        client.Character bot = game.bot(persona);
        when(bot.getInventory(InventoryType.EQUIPPED)).thenReturn(new Inventory(bot, InventoryType.EQUIPPED, (byte) 100));
        when(bot.getInventory(InventoryType.EQUIP)).thenReturn(new Inventory(bot, InventoryType.EQUIP, (byte) 24));
        game.store.seed(game.row(persona).with(RosterState.ACTIVE, 1_000L));
        roster();
        game.outWithAnn(persona);
    }

    /** LivingStore.rewriteStoredLevel over {@link #stored} and game.store.storedItems. */
    private int rewrite(int characterId, Function<StoredLevel, LevelRewrite> plan) {
        StoredLevel row = stored.get(characterId);
        if (row == null) {
            return 0;
        }
        List<Item> saved = game.store.storedItems.getOrDefault(characterId, new ArrayList<>());
        StoredLevel now = new StoredLevel(row.level(), row.jobId(), row.exp(), row.str(), row.dex(), row.intel(),
                row.luk(), row.ap(), row.sp(), row.hpMpUsed(), row.gmLevel(), row.equipSlots(), row.skills(),
                saved.stream().filter(item -> item.getPosition() < 0).toList(),
                saved.stream().filter(item -> item.getPosition() > 0).toList());
        LevelRewrite rewrite = plan.apply(now);
        CompanionLeases.Lease lease = game.leases.holder(characterId);
        atRewrite.add((lease == null ? "no lease" : lease.kind()) + ", "
                + (game.loaded.contains(characterId) ? "still an Agent" : "no Agent"));
        if (rewrite == null) {
            return 0;
        }
        for (Item item : saved) {
            Short to = item.getPosition() < 0 ? rewrite.unequip().get(item.getPosition()) : null;
            if (to != null) {
                item.setPosition(to);
            }
        }
        stored.put(characterId, new StoredLevel(rewrite.level(), rewrite.jobId(), rewrite.exp(), rewrite.str(),
                rewrite.dex(), rewrite.intel(), rewrite.luk(), rewrite.ap(), rewrite.sp(), row.hpMpUsed(),
                row.gmLevel(), row.equipSlots(), rewrite.skills(), List.of(), List.of()));
        written.put(characterId, rewrite);
        rewrites.add(characterId + " lv" + rewrite.level() + " job" + rewrite.jobId() + " exp" + rewrite.exp());
        return 1;
    }

    // ---- every real release restores ----------------------------------------------------------

    @Test
    void aLoadedCompanionItsOwnerLetsGoGoesBackToItsOwnLevelOnceItsWalkOutUnloadsIt() {
        held(HADRIK, "hadrik", HADRIK_HELD);
        setToCrusaderSeventy(HADRIK);
        offline(HADRIK); // once it is unloaded, no channel lists it
        outWithAnn(hadrik);

        roster().release(game.ann, hadrik, "dismissed");
        assertEquals(List.of(), rewrites, "it walks out to the portal as it is");
        game.runTimers();

        assertTrue(game.calls.contains("remove Hadrik"), game.calls.toString());
        assertEquals(List.of(HADRIK + " lv40 job110 exp1234"), rewrites);
        assertEquals(List.of("COMPANION, no Agent"), atRewrite, "off the Agent registry, its lease still held");
        assertNull(game.leases.holder(HADRIK), "its lease goes back once it is restored");
        LevelRewrite rewrite = written.get(HADRIK);
        assertEquals(List.of(1001004, 1101004), rewrite.skills().stream().map(StoredSkill::skillId).toList(),
                "its Crusader skill is off its Fighter chain");
        assertEquals(Optional.empty(), holds.record(HADRIK, "hadrik"), "its hold is released");
        assertEquals(List.of(HADRIK + " hadrik " + GmLevelHolds.RELEASE + " " + ReleasedLevelRestore.WHY), gmWrites);
    }

    @Test
    void aBenchedCompanionTheInactivitySweepLetsGoIsRestoredInItsStoredRows() {
        held(HADRIK, "hadrik", HADRIK_HELD);
        setToCrusaderSeventy(HADRIK);
        offline(HADRIK);
        game.annOnline = false; // away 14 days and more (her last seen reads 0)
        roster();

        assertEquals(1, new ResidentAllocation(game.world).sweep());

        assertNull(game.row(hadrik));
        assertEquals(List.of(HADRIK + " lv40 job110 exp1234"), rewrites);
        assertEquals(List.of("no lease, no Agent"), atRewrite);
        assertEquals(Optional.empty(), holds.record(HADRIK, "hadrik"));
    }

    @Test
    void anOwnersDeletionRestoresEveryHeldCompanionItLetsGoAHiredHandAndALostPersonaToo() {
        Persona wren = game.companion("hire:" + WREN, "Wren", WREN, RosterState.BENCHED, LITH);
        Persona gone = game.companion("gone", "Gone", GONE, RosterState.BENCHED, LITH);
        when(game.content.persona(gone.id())).thenReturn(null); // the content no longer has it: releaseRow
        for (int id : new int[] {HADRIK, WREN, GONE}) {
            setToCrusaderSeventy(id);
            offline(id);
        }
        held(HADRIK, "hadrik", HADRIK_HELD);
        held(WREN, wren.id(), "lv=35 job=100 exp=0");
        held(GONE, gone.id(), "lv=30 job=110 exp=7");
        roster();

        roster().ownerDeleted(ANN);

        assertEquals(List.of(HADRIK + " lv40 job110 exp1234", WREN + " lv35 job100 exp0", GONE + " lv30 job110 exp7"),
                rewrites);
        assertEquals(Optional.empty(), holds.record(WREN, wren.id()));
        assertEquals(Optional.empty(), holds.record(GONE, gone.id()));
    }

    @Test
    void aCompanionAtItsRecordedLevelAlreadyHasItsHoldReleasedAndNothingWritten() {
        held(HADRIK, "hadrik", "lv=70 job=111 exp=0");
        setToCrusaderSeventy(HADRIK);
        offline(HADRIK);
        roster();

        roster().release(game.ann, hadrik, "dismissed");

        assertEquals(List.of(), rewrites);
        assertEquals(Optional.empty(), holds.record(HADRIK, "hadrik"));
        assertEquals(List.of(HADRIK + " hadrik " + GmLevelHolds.RELEASE + " " + ReleasedLevelRestore.WHY), gmWrites);
    }

    // ---- nothing else is touched ------------------------------------------------------------------

    @Test
    void aReleaseOfACompanionNoHoldHoldsChangesNothing() {
        setToCrusaderSeventy(HADRIK);
        offline(HADRIK);
        roster();

        roster().release(game.ann, hadrik, "dismissed");

        assertNull(game.row(hadrik), "released");
        verify(game.store.store, never()).rewriteStoredLevel(anyInt(), any());
        assertEquals(List.of(), gmWrites);
        assertEquals(70, stored.get(HADRIK).level());
    }

    @Test
    void benchingAHeldCompanionUnloadsItWithItsHoldAndItsLevel() {
        held(HADRIK, "hadrik", HADRIK_HELD);
        setToCrusaderSeventy(HADRIK);
        offline(HADRIK);
        outWithAnn(hadrik);

        roster().bench(game.ann, hadrik);
        game.runTimers();

        assertTrue(game.calls.contains("remove Hadrik"), "benched: unloaded " + game.calls);
        verify(game.store.store, never()).rewriteStoredLevel(anyInt(), any());
        assertTrue(holds.record(HADRIK, "hadrik").isPresent(), "its hold stays");
        assertEquals(List.of(), gmWrites);
    }

    @Test
    void itsOwnerLoggingOffParksAHeldCompanionWithItsHoldAndItsLevel() {
        held(HADRIK, "hadrik", HADRIK_HELD);
        setToCrusaderSeventy(HADRIK);
        offline(HADRIK);
        outWithAnn(hadrik);

        roster().onOwnerLogout(game.ann);
        game.runTimers();

        assertTrue(game.calls.contains("remove Hadrik"), "parked: unloaded " + game.calls);
        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        verify(game.store.store, never()).rewriteStoredLevel(anyInt(), any());
        assertTrue(holds.record(HADRIK, "hadrik").isPresent(), "its hold stays");
    }

    @Test
    void aHumansOwnRecordIsNeverTouched() {
        gmRows.put(ANN + " " + GmLevelHolds.SELF, new LivingStore.EventRow(GmLevelHolds.HOLD,
                "lv=37 job=0 exp=0 str=161 dex=36 int=4 luk=4 ap=0"));
        held(HADRIK, "hadrik", HADRIK_HELD);
        setToCrusaderSeventy(HADRIK);
        offline(HADRIK);
        roster();

        roster().release(game.ann, hadrik, "dismissed");

        assertEquals(List.of(HADRIK + " lv40 job110 exp1234"), rewrites, "its companion only");
        verify(game.store.store, never()).rewriteStoredLevel(eq(ANN), any());
        assertTrue(holds.held(ANN, GmLevelHolds.SELF), "Ann's own record holds as it did");
    }

    @Test
    void aCharacterOnlineElsewhereIsNeverRewrittenUnderItAndKeepsItsHold() {
        held(HADRIK, "hadrik", HADRIK_HELD);
        setToCrusaderSeventy(HADRIK); // the world lists Hadrik's character online (loaded, not an Agent of ours)
        roster();

        roster().release(game.ann, hadrik, "dismissed");

        assertNull(game.row(hadrik), "the release itself goes on");
        verify(game.store.store, never()).rewriteStoredLevel(anyInt(), any());
        assertTrue(holds.record(HADRIK, "hadrik").isPresent());
        assertEquals(List.of(), gmWrites);
    }

    // ---- with the !bossgear strip and its refit ---------------------------------------------------

    /** A piece of a !bossgear kit, as BossGearPlanner.forge tags it. */
    private static Item boss(int itemId, short position) {
        Item item = Equip.restored(itemId, position);
        item.setOwner(BossGearPlanner.OWNER_TAG);
        item.setFlag((short) (item.getFlag() | ItemConstants.UNTRADEABLE));
        return item;
    }

    /** "id@position" of each item, by item id. */
    private static List<String> placed(List<Item> items) {
        return items.stream().sorted(Comparator.comparingInt(Item::getItemId))
                .map(item -> item.getItemId() + "@" + item.getPosition()).toList();
    }

    @Test
    void theRestoreTakesOffWhatItsOwnLevelDoesNotAllowAfterTheStripPutItsOwnShieldBack() throws Exception {
        try (AutoCloseable knowledge = KnowledgeTestKit.publishSeed()) {
            held(HADRIK, "hadrik", "lv=25 job=100 exp=500"); // a Lv25 Warrior before the GM set it to 70
            setToCrusaderSeventy(HADRIK);
            List<Item> saved = new ArrayList<>(List.of(Equip.restored(GLADIUS, WEAPON), boss(BOSS_SHIELD, SHIELD),
                    Equip.restored(PLAIN_CAP, CAP), Equip.restored(STEEL_SHIELD, (short) 1),
                    Equip.restored(QUEST_GLOVES, (short) 2)));
            game.store.storedItems.put(HADRIK, saved);
            offline(HADRIK);
            when(game.store.store.wearStats(HADRIK)).thenReturn(new WearStats(111, 70, 0, 250, 60, 4, 4, 0));
            roster();

            roster().release(game.ann, hadrik, "dismissed");

            assertEquals(List.of(HADRIK), game.store.storedWrites, "the strip's rewrite: the boss shield is gone");
            assertEquals(List.of(HADRIK + " lv25 job100 exp500"), rewrites, "then the restore's");
            assertEquals(List.of(PLAIN_CAP + "@3", QUEST_GLOVES + "@2", STEEL_SHIELD + "@-10", GLADIUS + "@1"),
                    placed(saved), "its own shield back on; its Lv30 sword and Lv35 cap in its bag");
            assertEquals(4, saved.size(), "only the boss piece left it: nothing lost");
            for (Item item : saved) {
                assertTrue(item.getPosition() > 0 || REQUIREMENTS.get(item.getItemId())[0] <= 25,
                        "worn above its level: " + item.getItemId());
            }
        }
    }

    @Test
    void withNoRoomForTheGearThatWouldComeOffNothingChangesAndTheHoldStays() {
        held(HADRIK, "hadrik", "lv=25 job=100 exp=500");
        stored.put(HADRIK, new StoredLevel(70, Job.CRUSADER.getId(), 0, 250, 60, 4, 4, 0, new int[10], 0, 0, 1,
                List.of(), List.of(), List.of()));
        game.store.storedItems.put(HADRIK, new ArrayList<>(List.of(Equip.restored(GLADIUS, WEAPON),
                Equip.restored(QUEST_GLOVES, (short) 1))));
        offline(HADRIK);
        roster();

        roster().release(game.ann, hadrik, "dismissed");

        assertEquals(List.of("no lease, no Agent"), atRewrite, "its rows were read and the plan refused");
        assertEquals(List.of(), rewrites);
        assertEquals(70, stored.get(HADRIK).level());
        assertTrue(holds.record(HADRIK, "hadrik").isPresent(), "the hold stays");
        assertEquals(List.of(), gmWrites);
    }
}
