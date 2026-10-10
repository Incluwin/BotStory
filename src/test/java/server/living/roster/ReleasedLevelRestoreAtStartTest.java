package server.living.roster;

import client.Job;
import client.LevelSetPlan;
import client.inventory.Equip;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.knowledge.KnowledgeTestKit;
import server.living.bond.ContractService;
import server.living.quest.BossGearPlanner;
import server.living.store.LivingStore;
import server.living.store.LivingStore.LevelRewrite;
import server.living.store.LivingStore.PersonaState;
import server.living.store.LivingStore.RosterState;
import server.living.store.LivingStore.StoredLevel;
import server.living.store.LivingStore.StoredSkill;
import server.living.store.LivingStore.WearStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * A release whose cleanup a crash or a hard kill cut short is finished at the next start
 * (RosterService.finishReleaseCleanupAtStart, on companion-ops after the boot reconciliation). The release's row is
 * gone at once, but its unload (the !bossgear strip, its own-gear refit and the level restore) runs only when its
 * walk-out ends, so a stop in between left a persona nobody employs with its GM level hold or its boss pieces, and no
 * later release to take them off. At start such a persona gets exactly an offline release's cleanup: its boss pieces
 * leave its saved inventory and its own carried gear goes back on, then its hold is restored in its stored rows and
 * released. A crew member (a roster row), a human's own record and kit, a character online and one loaded for anything
 * else are never touched; with nothing found nothing is logged.
 */
class ReleasedLevelRestoreAtStartTest {
    private static final int HADRIK = 501;
    private static final int THANE = 502;
    private static final String HADRIK_HELD = "lv=40 job=110 exp=1234";
    private static final int GLADIUS = 1302008;
    private static final int STEEL_SHIELD = 1092000;
    private static final int BOSS_SHIELD = 1092017;
    private static final int QUEST_GLOVES = 1082149;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;
    private static final String LINE = "Release cleanup at start";
    /** A Lv70 Fighter, male, STR 250: its Gladius and Steel Shield fit it. */
    private static final WearStats FIGHTER = new WearStats(110, 70, 0, 250, 20, 4, 4, 0);

    private final LifecycleHarness game = new LifecycleHarness();
    private final GmLevelHolds holds = new GmLevelHolds(game.world);
    /** "characterId persona" -> its newest gm.level.hold or gm.level.release row. */
    private final Map<String, LivingStore.EventRow> gmRows = new HashMap<>();
    /** Every gm.level row written, as "characterId persona kind detail". */
    private final List<String> gmWrites = new ArrayList<>();
    /** Character id -> its stored characters row and skills; its items are in game.store.storedItems. */
    private final Map<Integer, StoredLevel> stored = new HashMap<>();
    /** Every stored level rewrite, as "id lvL jobJ expE". */
    private final List<String> rewrites = new ArrayList<>();
    /** The character's lease at each stored level rewrite. */
    private final List<String> atRewrite = new ArrayList<>();
    /** The limit each candidate read asked for. */
    private final List<Integer> limits = new ArrayList<>();

    ReleasedLevelRestoreAtStartTest() {
        when(game.world.contracts()).thenReturn(mock(ContractService.class));
        when(game.world.gmLevelHolds()).thenReturn(holds);
        LivingStore store = game.store.store;
        when(store.latestGmLevel(anyInt(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(gmRows.get(inv.getArgument(0) + " " + inv.getArgument(1))));
        when(store.eventChecked(anyInt(), any(), anyString(), any())).thenAnswer(inv -> gmWrites.add(
                inv.getArgument(0) + " " + inv.getArgument(1) + " " + inv.getArgument(2) + " " + inv.getArgument(3)));
        when(store.rewriteStoredLevel(anyInt(), any())).thenAnswer(inv -> rewrite(inv.getArgument(0),
                inv.getArgument(1)));
        when(store.releaseCleanupCandidates(anyString(), anyInt())).thenAnswer(inv -> candidates(inv.getArgument(0),
                inv.getArgument(1)));
    }

    @AfterEach
    void close() {
        game.close();
    }

    private static final ReleasedLevelRestore.Facts FACTS = new ReleasedLevelRestore.Facts() {
        @Override
        public LevelSetPlan.GearPiece piece(Item worn) {
            return new LevelSetPlan.GearPiece(worn.getPosition(), worn.getItemId(), 0, 0, false);
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

    /** The roster as at start (built once the rows are seeded), restoring with {@link #FACTS}. */
    private RosterService roster() {
        RosterService roster = game.roster();
        roster.levelRestoreFacts = FACTS;
        return roster;
    }

    /**
     * LivingStore.releaseCleanupCandidates as these tests see it: every character with a GM level hold (under the
     * persona its record names) or a boss piece in its saved inventory (under the persona its persona row names), by
     * persona id. That is more than the store's own query offers (persona rows of nobody's roster only:
     * ReleaseCleanupCandidatesStoreTest), so what keeps a crew or a human untouched here is the roster's own checks.
     */
    private Map<String, Integer> candidates(String gearTag, int limit) {
        limits.add(limit);
        Map<String, Integer> out = new TreeMap<>();
        gmRows.forEach((key, row) -> {
            if (GmLevelHolds.HOLD.equals(row.kind())) {
                String[] parts = key.split(" ", 2);
                out.put(parts[1], Integer.parseInt(parts[0]));
            }
        });
        for (PersonaState state : game.store.personaStates.values()) {
            List<Item> items = game.store.storedItems.get(state.characterId());
            if (items != null && items.stream().anyMatch(item -> gearTag.equals(item.getOwner()))) {
                out.put(state.personaId(), state.characterId());
            }
        }
        return out;
    }

    /** A persona whose release dropped its row (Ann let it go) before a stop cut its unload short. */
    private void releasedBeforeTheStop(String id, String name, int characterId) {
        game.companion(id, name, characterId, RosterState.BENCHED, LITH);
        game.store.rows.remove(FakeLivingStore.key(ANN, id));
        game.store.personaStates.put(id, new PersonaState(id, characterId, null, 0, 0));
    }

    /** One of Ann's crew: its row stays. */
    private void crew(String id, String name, int characterId) {
        game.companion(id, name, characterId, RosterState.BENCHED, LITH);
        game.store.personaStates.put(id, new PersonaState(id, characterId, null, 0, ANN));
    }

    private void held(int characterId, String personaId, String original) {
        gmRows.put(characterId + " " + personaId, new LivingStore.EventRow(GmLevelHolds.HOLD, original));
    }

    /** As a GM set it: a Lv70 Crusader with Warrior, Fighter and Crusader skills. */
    private void setToCrusaderSeventy(int characterId) {
        stored.put(characterId, new StoredLevel(70, Job.CRUSADER.getId(), 900, 250, 60, 4, 4, 0,
                new int[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 0, 0, 24, List.of(new StoredSkill(1001004, 20, 0, -1),
                new StoredSkill(1101004, 20, 0, -1), new StoredSkill(1111002, 20, 0, -1)), List.of(), List.of()));
    }

    private void offline(int characterId) {
        when(game.server.getWorld(0).getPlayerStorage().getCharacterById(characterId)).thenReturn(null);
    }

    /** A piece of a !bossgear kit, as BossGearPlanner.forge tags it. */
    private static Item boss(int itemId, short position) {
        Item item = Equip.restored(itemId, position);
        item.setOwner(BossGearPlanner.OWNER_TAG);
        item.setFlag((short) (item.getFlag() | ItemConstants.UNTRADEABLE));
        return item;
    }

    /** Its saved inventory: its Gladius and the boss shield worn, its own Steel Shield and gloves in its bag. */
    private List<Item> savedKit(int characterId) {
        List<Item> saved = new ArrayList<>(List.of(Equip.restored(GLADIUS, WEAPON), boss(BOSS_SHIELD, SHIELD),
                Equip.restored(STEEL_SHIELD, (short) 1), Equip.restored(QUEST_GLOVES, (short) 2)));
        game.store.storedItems.put(characterId, saved);
        when(game.store.store.wearStats(characterId)).thenReturn(FIGHTER);
        return saved;
    }

    /** "id@position" of each item, by item id. */
    private static List<String> placed(List<Item> items) {
        return items.stream().sorted(Comparator.comparingInt(Item::getItemId))
                .map(item -> item.getItemId() + "@" + item.getPosition()).toList();
    }

    /** LivingStore.rewriteStoredLevel over {@link #stored}. */
    private int rewrite(int characterId, Function<StoredLevel, LevelRewrite> plan) {
        StoredLevel row = stored.get(characterId);
        if (row == null) {
            return 0;
        }
        CompanionLeases.Lease lease = game.leases.holder(characterId);
        atRewrite.add(lease == null ? "no lease" : lease.kind() + " " + lease.ownerId());
        LevelRewrite rewrite = plan.apply(row);
        if (rewrite == null) {
            return 0;
        }
        stored.put(characterId, new StoredLevel(rewrite.level(), rewrite.jobId(), rewrite.exp(), rewrite.str(),
                rewrite.dex(), rewrite.intel(), rewrite.luk(), rewrite.ap(), rewrite.sp(), row.hpMpUsed(),
                row.gmLevel(), row.equipSlots(), rewrite.skills(), List.of(), List.of()));
        rewrites.add(characterId + " lv" + rewrite.level() + " job" + rewrite.jobId() + " exp" + rewrite.exp());
        return 1;
    }

    // ---- what a stop left undone is finished ------------------------------------------------------

    @Test
    void aPersonaNobodyEmploysThatStillHasItsGmLevelHoldGoesBackToItsOwnLevelAtStart() {
        releasedBeforeTheStop("hadrik", "Hadrik", HADRIK);
        held(HADRIK, "hadrik", HADRIK_HELD);
        setToCrusaderSeventy(HADRIK);
        offline(HADRIK);
        RosterService roster = roster();

        try (LivingLogLines lines = new LivingLogLines(RosterService.class)) {
            roster.finishReleaseCleanupAtStart();

            assertEquals(List.of(LINE + ": stripped=0 restored=1 skipped=0"), lines.startingWith(LINE));
        }
        assertEquals(List.of(HADRIK + " lv40 job110 exp1234"), rewrites);
        assertEquals(List.of("COMPANION 0"), atRewrite, "under a lease of its own, so nothing loads it meanwhile");
        assertNull(game.leases.holder(HADRIK), "the lease goes back once it is done");
        assertEquals(Optional.empty(), holds.record(HADRIK, "hadrik"), "its hold is released");
        assertEquals(List.of(HADRIK + " hadrik " + GmLevelHolds.RELEASE + " " + ReleasedLevelRestore.WHY), gmWrites);
        assertEquals(List.of(RosterService.RELEASE_CLEANUP_AT_START_MAX), limits, "one bounded read");
    }

    @Test
    void aPersonaNobodyEmploysWithBossGearInItsSavedInventoryLosesItAndWearsItsOwnShieldAgain() throws Exception {
        try (AutoCloseable knowledge = KnowledgeTestKit.publishSeed()) {
            releasedBeforeTheStop("hadrik", "Hadrik", HADRIK);
            List<Item> saved = savedKit(HADRIK);
            offline(HADRIK);
            RosterService roster = roster();

            try (LivingLogLines lines = new LivingLogLines(RosterService.class)) {
                roster.finishReleaseCleanupAtStart();

                assertEquals(List.of(LINE + ": stripped=1 restored=0 skipped=0"), lines.startingWith(LINE));
            }
            assertEquals(List.of(HADRIK), game.store.storedWrites, "one rewrite of its saved inventory");
            assertEquals(List.of(QUEST_GLOVES + "@2", STEEL_SHIELD + "@-10", GLADIUS + "@-11"), placed(saved),
                    "the boss shield is gone and its own Steel Shield is on again");
            assertEquals(List.of("0 hadrik bossgear.removed released: 1 piece"), game.store.events("bossgear.removed"));
            assertEquals(List.of(), rewrites, "no hold: its level stays");
            assertNull(game.leases.holder(HADRIK));
        }
    }

    @Test
    void aCharacterOnlineIsLeftAsItIsAndCountedAsSkipped() {
        releasedBeforeTheStop("hadrik", "Hadrik", HADRIK);
        held(HADRIK, "hadrik", HADRIK_HELD);
        setToCrusaderSeventy(HADRIK); // the world lists Hadrik's character online
        RosterService roster = roster();

        try (LivingLogLines lines = new LivingLogLines(RosterService.class)) {
            roster.finishReleaseCleanupAtStart();

            assertEquals(List.of(LINE + ": stripped=0 restored=0 skipped=1"), lines.startingWith(LINE));
        }
        assertEquals(List.of(), rewrites);
        assertTrue(holds.record(HADRIK, "hadrik").isPresent(), "its hold stays for the next start");
        assertEquals(List.of(), gmWrites);
        assertNull(game.leases.holder(HADRIK));
    }

    @Test
    void oneLoadedForAnythingElseIsLeftAsItIsAndKeepsThatLease() {
        releasedBeforeTheStop("hadrik", "Hadrik", HADRIK);
        held(HADRIK, "hadrik", HADRIK_HELD);
        setToCrusaderSeventy(HADRIK);
        offline(HADRIK);
        game.leases.tryAcquire(HADRIK, CompanionLeases.Kind.RESIDENT, 0); // the Resident runtime is loading it
        RosterService roster = roster();

        try (LivingLogLines lines = new LivingLogLines(RosterService.class)) {
            roster.finishReleaseCleanupAtStart();

            assertEquals(List.of(LINE + ": stripped=0 restored=0 skipped=1"), lines.startingWith(LINE));
            assertEquals(List.of("Release cleanup left for the next start: id=" + HADRIK + " persona=hadrik (its"
                    + " character is loaded as Lease[kind=RESIDENT, ownerId=0])"),
                    lines.startingWith("Release cleanup left"));
        }
        assertEquals(List.of(), rewrites);
        verify(game.store.store, never()).dropStoredItems(anyInt(), any(), any());
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.RESIDENT, 0), game.leases.holder(HADRIK));
        assertTrue(holds.record(HADRIK, "hadrik").isPresent());
    }

    // ---- nothing else is touched ------------------------------------------------------------------

    @Test
    void aCrewMemberWithAGmLevelHoldAndBossGearIsLeftAsItIs() {
        crew("thane", "Thane", THANE);
        held(THANE, "thane", "lv=40 job=110 exp=0"); // crew A's Lv160 test level
        setToCrusaderSeventy(THANE);
        List<Item> saved = savedKit(THANE);
        offline(THANE);
        RosterService roster = roster();

        try (LivingLogLines lines = new LivingLogLines(RosterService.class)) {
            roster.finishReleaseCleanupAtStart();

            assertEquals(List.of(), lines.startingWith(LINE), "nothing found");
        }
        assertEquals(List.of(), rewrites);
        assertEquals(List.of(), game.store.storedWrites);
        assertEquals(4, saved.size(), "its kit stays on it");
        assertTrue(holds.record(THANE, "thane").isPresent(), "its hold stays");
        assertEquals(List.of(), gmWrites);
        assertEquals(RosterState.BENCHED, game.store.rows.get(FakeLivingStore.key(ANN, "thane")).state());
    }

    @Test
    void aHumansOwnRecordAndKitAreNeverTouched() {
        gmRows.put(ANN + " " + GmLevelHolds.SELF, new LivingStore.EventRow(GmLevelHolds.HOLD,
                "lv=37 job=0 exp=0 str=161 dex=36 int=4 luk=4 ap=0"));
        List<Item> annsKit = new ArrayList<>(List.of(boss(BOSS_SHIELD, SHIELD), Equip.restored(GLADIUS, WEAPON)));
        game.store.storedItems.put(ANN, annsKit);
        game.annOnline = false;
        setToCrusaderSeventy(ANN);
        RosterService roster = roster();

        try (LivingLogLines lines = new LivingLogLines(RosterService.class)) {
            roster.finishReleaseCleanupAtStart();

            assertEquals(List.of(), lines.startingWith(LINE), "nothing found");
        }
        verify(game.store.store, never()).dropStoredItems(anyInt(), any(), any());
        verify(game.store.store, never()).rewriteStoredLevel(anyInt(), any());
        assertEquals(2, annsKit.size(), "her !bossgear me kit stays hers");
        assertTrue(holds.held(ANN, GmLevelHolds.SELF), "her own record holds as it did");
        assertEquals(List.of(), gmWrites);
    }

    @Test
    void withNothingFoundNothingIsDoneAndNothingIsLogged() {
        crew("thane", "Thane", THANE);
        offline(THANE);
        RosterService roster = roster();

        try (LivingLogLines lines = new LivingLogLines(RosterService.class)) {
            roster.finishReleaseCleanupAtStart();

            assertEquals(List.of(), lines.startingWith(LINE));
        }
        verify(game.store.store, never()).dropStoredItems(anyInt(), any(), any());
        verify(game.store.store, never()).latestGmLevel(anyInt(), anyString());
        assertEquals(List.of(RosterService.RELEASE_CLEANUP_AT_START_MAX), limits);
    }

    // ---- when it runs -------------------------------------------------------------------------

    @Test
    void itRunsOnCompanionOpsRightAfterTheBootReconciliationAndBeforeTheResidentSweep() throws Exception {
        String source = Files.readString(Path.of("src/main/java/server/living/LivingWorld.java"));
        int boot = source.indexOf("world.executor.submit(\"roster.boot\", world.roster::reconcileAtBoot);");
        int cleanup = source.indexOf(
                "world.executor.submit(\"roster.release_cleanup\", world.roster::finishReleaseCleanupAtStart);");
        int allocation = source.indexOf("world.executor.submit(\"residents.allocation\"");
        assertTrue(boot > 0 && cleanup > boot && allocation > cleanup,
                "boot " + boot + ", cleanup " + cleanup + ", allocation " + allocation);
    }
}
