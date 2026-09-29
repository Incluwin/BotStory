package server.living.roster;

import client.Character;
import client.Job;
import constants.game.ExpTable;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyEvents;
import net.server.world.PartyOperation;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The job a Crew window row or card header shows reads as the game names it, for every job there is. P3: the view's
 * new fields (MP%, EXP%, the ETA clock, duty @ camp, FARM_ITEM progress, banked EXP, the haul, wages, bond, guests,
 * commitments), and the per-owner cache: a render after the first reads no database, and every roster write and party
 * change drops the owner's rows (a read racing a drop is never kept).
 */
class CompanionViewTest {
    private static final int ANN = LifecycleHarness.ANN;
    private static final int STOLEN_FENCE = 4000021;

    private final LivingWorld world = mock(LivingWorld.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final LivingContent content = mock(LivingContent.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Character ann = mock(Character.class);

    @Test
    void everyJobReadsAsTheGameNamesIt() {
        Map<Job, String> expected = new EnumMap<>(Job.class);
        put(expected, "Beginner", Job.BEGINNER);
        put(expected, "Warrior", Job.WARRIOR);
        put(expected, "Fighter", Job.FIGHTER);
        put(expected, "Crusader", Job.CRUSADER);
        put(expected, "Hero", Job.HERO);
        put(expected, "Page", Job.PAGE);
        put(expected, "White Knight", Job.WHITEKNIGHT);
        put(expected, "Paladin", Job.PALADIN);
        put(expected, "Spearman", Job.SPEARMAN);
        put(expected, "Dragon Knight", Job.DRAGONKNIGHT);
        put(expected, "Dark Knight", Job.DARKKNIGHT);
        put(expected, "Magician", Job.MAGICIAN);
        put(expected, "F/P Wizard", Job.FP_WIZARD);
        put(expected, "F/P Mage", Job.FP_MAGE);
        put(expected, "F/P Archmage", Job.FP_ARCHMAGE);
        put(expected, "I/L Wizard", Job.IL_WIZARD);
        put(expected, "I/L Mage", Job.IL_MAGE);
        put(expected, "I/L Archmage", Job.IL_ARCHMAGE);
        put(expected, "Cleric", Job.CLERIC);
        put(expected, "Priest", Job.PRIEST);
        put(expected, "Bishop", Job.BISHOP);
        put(expected, "Bowman", Job.BOWMAN);
        put(expected, "Hunter", Job.HUNTER);
        put(expected, "Ranger", Job.RANGER);
        put(expected, "Bowmaster", Job.BOWMASTER);
        put(expected, "Crossbowman", Job.CROSSBOWMAN);
        put(expected, "Sniper", Job.SNIPER);
        put(expected, "Marksman", Job.MARKSMAN);
        put(expected, "Thief", Job.THIEF);
        put(expected, "Assassin", Job.ASSASSIN);
        put(expected, "Hermit", Job.HERMIT);
        put(expected, "Night Lord", Job.NIGHTLORD);
        put(expected, "Bandit", Job.BANDIT);
        put(expected, "Chief Bandit", Job.CHIEFBANDIT);
        put(expected, "Shadower", Job.SHADOWER);
        put(expected, "Pirate", Job.PIRATE);
        put(expected, "Brawler", Job.BRAWLER);
        put(expected, "Marauder", Job.MARAUDER);
        put(expected, "Buccaneer", Job.BUCCANEER);
        put(expected, "Gunslinger", Job.GUNSLINGER);
        put(expected, "Outlaw", Job.OUTLAW);
        put(expected, "Corsair", Job.CORSAIR);
        put(expected, "Maple Leaf Brigadier", Job.MAPLELEAF_BRIGADIER);
        put(expected, "GM", Job.GM, Job.SUPERGM);
        put(expected, "Noblesse", Job.NOBLESSE);
        put(expected, "Dawn Warrior", Job.DAWNWARRIOR1, Job.DAWNWARRIOR2, Job.DAWNWARRIOR3, Job.DAWNWARRIOR4);
        put(expected, "Blaze Wizard", Job.BLAZEWIZARD1, Job.BLAZEWIZARD2, Job.BLAZEWIZARD3, Job.BLAZEWIZARD4);
        put(expected, "Wind Archer", Job.WINDARCHER1, Job.WINDARCHER2, Job.WINDARCHER3, Job.WINDARCHER4);
        put(expected, "Night Walker", Job.NIGHTWALKER1, Job.NIGHTWALKER2, Job.NIGHTWALKER3, Job.NIGHTWALKER4);
        put(expected, "Thunder Breaker", Job.THUNDERBREAKER1, Job.THUNDERBREAKER2, Job.THUNDERBREAKER3, Job.THUNDERBREAKER4);
        put(expected, "Legend", Job.LEGEND);
        put(expected, "Evan", Job.EVAN, Job.EVAN1, Job.EVAN2, Job.EVAN3, Job.EVAN4, Job.EVAN5, Job.EVAN6, Job.EVAN7,
                Job.EVAN8, Job.EVAN9, Job.EVAN10);
        put(expected, "Aran", Job.ARAN1, Job.ARAN2, Job.ARAN3, Job.ARAN4);

        assertEquals(Job.values().length, expected.size(), "a job constant without an expected name");
        for (Job job : Job.values()) {
            assertEquals(expected.get(job), CompanionView.jobName(job.getId()), job.name());
        }
    }

    @Test
    void bankedExpIsAShareOfTheNextLevelNeverOverAHundredPercent() {
        int needed = ExpTable.getExpNeededForLevel(28);

        assertEquals(10, CompanionView.bankedPercent(needed / 10, 28));
        assertEquals(99, CompanionView.bankedPercent(needed - 1, 28));
        assertEquals(100, CompanionView.bankedPercent(needed, 28));
        assertEquals(100, CompanionView.bankedPercent(needed * 50L, 28));
        assertEquals(0, CompanionView.bankedPercent(0L, 28));
        assertEquals(0, CompanionView.bankedPercent(1_000L, 0)); // never loaded: no level to measure against
    }

    @Test
    void anUnknownJobHasNoName() {
        assertEquals("", CompanionView.jobName(-1));
        assertEquals("", CompanionView.jobName(12345));
    }

    // ---- P3 fields ---------------------------------------------------------------------------

    @Test
    void aLoadedCompanionShowsItsHpMpAndExpFromTheLiveCharacter() {
        wireWorld();
        RosterEntry row = row("hadrik", RosterState.ACTIVE, Assignment.TRAIN, 0, 0, 0L, 0L, 0L);
        when(store.roster(ANN)).thenReturn(List.of(row));
        Character bot = bot(31, Job.FIGHTER, "Perion");
        when(bot.getMp()).thenReturn(150);
        when(bot.getCurrentMaxMp()).thenReturn(300);
        when(bot.getExp()).thenReturn((int) Math.ceil(ExpTable.getExpNeededForLevel(31) * 0.43d));
        when(roster.companion(row)).thenReturn(bot);

        CompanionView view = views().view(ANN, "hadrik");

        assertTrue(view.loaded());
        assertEquals("Perion", view.where());
        assertEquals("Ant Tunnel", view.camp());
        assertEquals(90, view.hpPercent());
        assertEquals(50, view.mpPercent());
        assertEquals(43, view.expPercent());
        assertTrue(view.inParty());
        assertEquals("", view.eta());
        assertEquals("", view.progress());
    }

    @Test
    void aTravellingCompanionShowsItsEtaAsAClockAndIsInTheParty() {
        wireWorld();
        RosterEntry row = new RosterEntry(ANN, "mira", RosterState.TRAVELLING, Assignment.TRAIN, 0, 0, 105040300, 0L, 0L,
                0L, 0L, 0L, 0L, 0, null, System.currentTimeMillis() + 40_000L, 0, 0L);
        when(store.roster(ANN)).thenReturn(List.of(row));

        CompanionView view = views().view(ANN, "mira");

        assertEquals("0:40", view.eta());
        assertTrue(view.inParty());
        assertFalse(view.loaded());
        assertEquals(-1, view.expPercent(), "not loaded: its EXP is unknown");
    }

    @Test
    void aBenchRowShowsFarmProgressTheHaulBankedExpWagesAndBond() {
        wireWorld();
        long banked = (long) Math.ceil(ExpTable.getExpNeededForLevel(28) * 0.12d);
        RosterEntry row = row("mira", RosterState.BENCHED, Assignment.FARM_ITEM, STOLEN_FENCE, 200, banked, 3_120L,
                System.currentTimeMillis() + 7_200_000L);
        when(store.roster(ANN)).thenReturn(List.of(row));
        when(store.haul(ANN, "mira")).thenReturn(List.of(new HaulItem(STOLEN_FENCE, 143), new HaulItem(4000000, 5),
                new HaulItem(2000000, 1), new HaulItem(4000001, 2)));
        when(roster.describeAssignment(row)).thenReturn("farm Stolen Fence x200");
        BondService bonds = mock(BondService.class);
        when(world.bonds()).thenReturn(bonds);
        Bond met = Bond.fresh(ANN, "mira", 0L);
        when(bonds.peek(ANN, "mira")).thenReturn(new Bond(ANN, "mira", BondStage.TRUSTED, 500, met.flags(), met.contractState(),
                0L, 0L, 0, 0L, 0, 0L, 0L));

        CompanionView view = views().view(ANN, "mira");

        assertEquals(28, view.level());
        assertEquals("Hunter", view.job());
        assertEquals("farm Stolen Fence x200", view.duty());
        assertEquals("Ant Tunnel", view.camp());
        assertEquals("143/200 Stolen Fence", view.progress());
        assertEquals(12, view.bankedExpPercent());
        assertEquals("3,120 meso, 4 stacks", view.haul());
        assertTrue(view.wages().startsWith("paid up for"), view.wages());
        assertEquals(BondStage.TRUSTED, view.bond());
        assertFalse(view.inParty());
        assertFalse(view.guest());
    }

    @Test
    void anUnpaidCompanionWorksOffTheHaulAndOneWithNothingCarriesNothing() {
        wireWorld();
        when(store.roster(ANN)).thenReturn(List.of(row("mira", RosterState.BENCHED, Assignment.TRAIN, 0, 0, 0L, 0L, 0L)));

        CompanionView view = views().view(ANN, "mira");

        assertEquals("working off the haul", view.wages());
        assertEquals("", view.haul());
        assertEquals("", view.progress());
        assertNull(view.bond());
    }

    @Test
    void aCompanionTiedToARunIsMarkedCommitted() {
        wireWorld();
        Commitments commitments = new Commitments();
        when(world.commitments()).thenReturn(commitments);
        when(store.roster(ANN)).thenReturn(List.of(row("hadrik", RosterState.ACTIVE, Assignment.TRAIN, 0, 0, 0L, 0L, 0L)));
        when(residents.characterIdOf("hadrik")).thenReturn(901);
        commitments.commit("kpq-1", ANN, 901);

        assertTrue(views().view(ANN, "hadrik").committed());
    }

    @Test
    void aCitizenGuestInThePartyIsListedAsAGuestAndNeverCached() {
        wireWorld();
        CompanionSlots slots = new CompanionSlots(world);
        when(world.slots()).thenReturn(slots);
        slots.reserve(ANN, CompanionSlots.characterKey(555), CompanionSlots.Kind.GUEST, Long.MAX_VALUE);
        Character cookie = bot(20, Job.BOWMAN, "Henesys");
        when(cookie.getName()).thenReturn("RedCookie");
        Party party = mock(Party.class);
        PartyCharacter annRow = mock(PartyCharacter.class);
        when(annRow.getId()).thenReturn(ANN);
        PartyCharacter guestRow = mock(PartyCharacter.class);
        when(guestRow.getId()).thenReturn(555);
        when(guestRow.getPlayer()).thenReturn(cookie);
        when(party.getMembers()).thenReturn(List.of(annRow, guestRow));
        when(ann.getParty()).thenReturn(party);

        List<CompanionView> guests = views().guests(ann);

        assertEquals(1, guests.size());
        assertEquals("RedCookie", guests.get(0).name());
        assertTrue(guests.get(0).guest());
        assertTrue(guests.get(0).inParty());
        assertEquals("Bowman", guests.get(0).job());
        verify(store, never()).roster(anyInt());
    }

    @Test
    void theStaticInvalidateIsSafeOnAWorldWithoutACache() {
        CompanionViews.invalidate(world, ANN); // world.views() is null: nothing to drop
        CompanionViews.invalidate(null, ANN);
    }

    // ---- the cache -----------------------------------------------------------------------------

    @Test
    void aRenderAfterTheFirstReadsNoDatabase() {
        try (LifecycleHarness h = new LifecycleHarness()) {
            Persona hadrik = h.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LifecycleHarness.LITH);
            h.companion("mira", "Mira", 902, RosterState.BENCHED, LifecycleHarness.HENESYS_FIELD);
            h.roster();
            h.outWithAnn(hadrik);
            when(h.bot(hadrik).getJob()).thenReturn(Job.FIGHTER);
            when(h.store.store.characterLevelAndJob(902)).thenReturn(new int[] {28, Job.HUNTER.getId()});
            CompanionViews views = harnessViews(h);
            List<CompanionView> first = views.crew(h.ann);
            clearInvocations(h.store.store);

            List<CompanionView> again = views.crew(h.ann);
            views.view(ANN, "mira");
            views.rows(ANN);

            verifyNoInteractions(h.store.store);
            assertEquals(List.of("Hadrik", "Mira"), again.stream().map(CompanionView::name).toList());
            assertEquals(28, again.get(1).level());
            assertEquals(first.get(1), again.get(1));
        }
    }

    @Test
    void aRosterWriteDropsTheOwnersViews() {
        try (LifecycleHarness h = new LifecycleHarness()) {
            Persona hadrik = h.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LifecycleHarness.LITH);
            h.roster();
            h.outWithAnn(hadrik);
            when(h.bot(hadrik).getJob()).thenReturn(Job.FIGHTER);
            CompanionViews views = harnessViews(h);
            long before = views.version(ANN);
            assertEquals(RosterState.ACTIVE, views.view(ANN, "hadrik").state());

            h.roster().bench(h.ann, hadrik); // RosterService.write
            clearInvocations(h.store.store);

            assertTrue(views.version(ANN) > before);
            assertEquals(RosterState.BENCHED, views.view(ANN, "hadrik").state());
            verify(h.store.store).roster(ANN); // read again: the write dropped the cached rows
        }
    }

    @Test
    void aPartyChangeDropsTheViewsOfEveryoneInIt() {
        try (LifecycleHarness h = new LifecycleHarness()) {
            h.companion("mira", "Mira", 902, RosterState.BENCHED, LifecycleHarness.HENESYS_FIELD);
            h.roster();
            CompanionViews views = harnessViews(h);
            views.rows(ANN);
            views.rows(20);
            when(h.world.directory().ownerOfCharacter(902)).thenReturn(ANN);
            clearInvocations(h.store.store);

            // Ann's Mira joins Ben's (20) party: Ann is in neither snapshot, her companion is
            views.partyChanged(new PartyEvents.PartyChangeEvent(77, PartyOperation.JOIN, 902, 20,
                    new PartyEvents.Snapshot(List.of(20), 20), new PartyEvents.Snapshot(List.of(20, 902), 20), null, true));
            views.rows(ANN);
            views.rows(20);

            verify(h.store.store).roster(ANN);
            verify(h.store.store).roster(20);
        }
    }

    @Test
    void aReadRacingAnInvalidationIsNotKept() {
        try (LifecycleHarness h = new LifecycleHarness()) {
            h.companion("mira", "Mira", 902, RosterState.BENCHED, LifecycleHarness.HENESYS_FIELD);
            h.roster();
            CompanionViews views = harnessViews(h);
            AtomicBoolean racing = new AtomicBoolean(true);
            doAnswer(inv -> {
                if (racing.getAndSet(false)) {
                    views.invalidate(ANN); // a roster write on companion-ops while the packet thread reads
                }
                return h.store.rows.values().stream().filter(e -> e.characterId() == ANN).toList();
            }).when(h.store.store).roster(ANN);

            views.rows(ANN);
            views.rows(ANN);
            views.rows(ANN);

            verify(h.store.store, times(2)).roster(ANN); // the raced read was not kept; the next one is
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void wireWorld() {
        when(ann.getId()).thenReturn(ANN);
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(store);
        when(world.content()).thenReturn(content);
        when(world.residents()).thenReturn(residents);
        when(roster.describeAssignment(org.mockito.ArgumentMatchers.any())).thenReturn("train");
        when(residents.characterIdOf("mira")).thenReturn(902);
        when(store.characterLevelAndJob(902)).thenReturn(new int[] {28, Job.HUNTER.getId()});
        for (String id : List.of("hadrik", "mira")) {
            Persona persona = mock(Persona.class);
            when(persona.id()).thenReturn(id);
            when(persona.name()).thenReturn(id.substring(0, 1).toUpperCase() + id.substring(1));
            when(content.persona(id)).thenReturn(persona);
        }
    }

    private CompanionViews views() {
        CompanionViews views = new CompanionViews(world, mapId -> "Ant Tunnel", itemId -> itemId == STOLEN_FENCE ? "Stolen Fence" : "item");
        when(world.views()).thenReturn(views);
        return views;
    }

    private static CompanionViews harnessViews(LifecycleHarness h) {
        CompanionViews views = new CompanionViews(h.world, mapId -> "camp " + mapId, itemId -> "item " + itemId);
        when(h.world.views()).thenReturn(views);
        return views;
    }

    private static Character bot(int level, Job job, String mapName) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getMapName()).thenReturn(mapName);
        when(bot.getMap()).thenReturn(map);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getJob()).thenReturn(job);
        when(bot.getHp()).thenReturn(812);
        when(bot.getCurrentMaxHp()).thenReturn(900);
        return bot;
    }

    private static RosterEntry row(String personaId, RosterState state, Assignment duty, int item, int count, long banked,
                                   long haulMeso, long paidUntilMs) {
        return new RosterEntry(ANN, personaId, state, duty, item, count, 105040300, banked, haulMeso, paidUntilMs, 0L, 0L,
                0L, 0, null);
    }

    private static void put(Map<Job, String> expected, String name, Job... jobs) {
        for (Job job : jobs) {
            expected.put(job, name);
        }
    }
}
