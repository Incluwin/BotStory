package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Contract;
import server.living.config.Content.LootShare;
import server.living.config.Content.Persona;
import server.living.config.Content.Terms;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contracts' loot terms (docs/COMPANION_LENDING_PLAN.md, section 4): the split math, which items stay the companion's,
 * when a pickup is tallied, and how a tally settles into the haul. A contract without lootShare, and the kill switch,
 * leave everything as before.
 */
class LootTermsTest {
    private static final int ALICE = 10;
    private static final int BOB = 20;
    private static final int OLWEN = 900;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingContent content = mock(LivingContent.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final BenchDutyDispatcher bench = mock(BenchDutyDispatcher.class);
    private final CompanionLoans loans = mock(CompanionLoans.class);
    private final Character olwen = mock(Character.class);
    private MockedStatic<AgentRuntimeRegistry> registry;
    private LootTerms terms;

    @BeforeEach
    void wireWorld() {
        when(world.config()).thenReturn(config);
        when(world.content()).thenReturn(content);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.directory()).thenReturn(directory);
        when(world.benchDuty()).thenReturn(bench);
        when(world.loans()).thenReturn(loans);
        when(directory.ownerOfCharacter(OLWEN)).thenReturn(ALICE);
        when(directory.personaIdOfCharacter(OLWEN)).thenReturn("olwen");
        when(residents.characterIdOf("olwen")).thenReturn(OLWEN);
        contract("olwen", LootShare.OWN_DROPS, 10);
        when(olwen.getId()).thenReturn(OLWEN);
        when(olwen.getLevel()).thenReturn(10); // its upkeep floor: 10,000 meso
        AgentPresence.install(chr -> chr.getId() == OLWEN);
        registry = mockStatic(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(OLWEN)).thenReturn(ALICE);
        terms = new LootTerms(world);
    }

    @AfterEach
    void restore() {
        registry.close();
        AgentPresence.install(null);
    }

    // ---- the math ---------------------------------------------------------------------------

    @Test
    void upkeepComesFirstUntilThePurseHoldsItsFloor() {
        long[] carry = {0L};
        assertEquals(0L, LootTerms.owedMeso(1_000, true, 10, 0L, 30_000L, carry)); // all of it to its supply money
        assertEquals(450L, LootTerms.owedMeso(1_000, true, 10, 29_500L, 30_000L, carry)); // 500 upkeep, 10% of the rest
        assertEquals(500L, LootTerms.owedMeso(1_000, false, 10, 29_500L, 30_000L, carry)); // not its kill: no cut
        assertEquals(1_000L, LootTerms.owedMeso(1_000, false, 10, 50_000L, 30_000L, carry));
    }

    @Test
    void ownDropsKeepsItsPercentOfItsOwnKillsOnly() {
        long[] carry = {0L};
        assertEquals(900L, LootTerms.owedMeso(1_000, true, 10, 50_000L, 30_000L, carry));
        assertEquals(1_000L, LootTerms.owedMeso(1_000, false, 10, 50_000L, 30_000L, carry));
        assertEquals(0L, LootTerms.owedMeso(1_000, true, 100, 50_000L, 0L, carry));
        assertEquals(1_000L, LootTerms.owedMeso(1_000, true, 0, 50_000L, 0L, carry)); // NONE and LEADER keep 0%
        assertEquals(0L, LootTerms.owedMeso(0, true, 10, 50_000L, 0L, carry));
        assertEquals(0L, carry[0]);
    }

    @Test
    void theCarryKeepsTheOwnersShareExactOverManyPickups() {
        long[] carry = {0L};
        long owed = 0L;
        for (int pickup = 0; pickup < 1_000; pickup++) {
            owed += LootTerms.owedMeso(7, true, 10, 50_000L, 0L, carry);
        }
        assertEquals(6_300L, owed); // 90% of 7,000 to the meso
        assertEquals(0L, carry[0]);

        long[] odd = {0L};
        long owedOdd = 0L;
        for (int pickup = 0; pickup < 999; pickup++) {
            owedOdd += LootTerms.owedMeso(13, true, 15, 50_000L, 0L, odd);
        }
        assertEquals(999L * 13 * 85 / 100, owedOdd); // never more than its share, at most a meso's fraction behind
        assertTrue(odd[0] >= 0 && odd[0] < 100);
    }

    @Test
    void suppliesQuestItemsAndEquipsStayTheCompanions() {
        assertFalse(LootTerms.owesItem(2000002, false)); // a potion
        assertFalse(LootTerms.owesItem(2010000, false)); // food
        assertFalse(LootTerms.owesItem(2020000, false)); // an elixir
        assertFalse(LootTerms.owesItem(2070000, false)); // throwing stars
        assertFalse(LootTerms.owesItem(2060000, false)); // arrows
        assertFalse(LootTerms.owesItem(2330000, false)); // bullets
        assertFalse(LootTerms.owesItem(1302000, false)); // an equip keeps its rolled stats in its bag
        assertFalse(LootTerms.owesItem(4031013, true)); // its errand's quest item
        assertTrue(LootTerms.owesItem(4000019, false)); // monster loot (ETC) is its owner's share
        assertTrue(LootTerms.owesItem(3010000, false)); // a chair
        assertFalse(LootTerms.owesItem(0, false));
    }

    // ---- the terms ----------------------------------------------------------------------------

    @Test
    void theCardSaysTheTermsAndNothingWithoutThem() {
        assertTrue(terms.describe("olwen").startsWith("Terms: keeps 10% of its own kills' meso"));
        contract("dorran", LootShare.NONE, -1);
        assertTrue(terms.describe("dorran").startsWith("Terms: keeps no cut"));
        contract("mira", null, -1);
        assertNull(terms.describe("mira"));
        assertNull(terms.describe("nobody"));
        config.COMPANION_LOOT_TERMS_ENABLED = false;
        assertNull(terms.describe("olwen"));
    }

    @Test
    void aHiredCitizenFollowsTheHiredHandContract() {
        Terms hired = new Terms(4, 10, LootShare.OWN_DROPS, List.of(), 0, 0L, 20);
        when(content.contract(LivingContent.HIRED_HAND_CONTRACT))
                .thenReturn(new Contract(LivingContent.HIRED_HAND_CONTRACT, null, null, hired, "test"));

        assertEquals(hired, terms.termsOf("hire:4242"));
    }

    // ---- the field ----------------------------------------------------------------------------

    @Test
    void anOwnKillsMesoIsSplitOutWithItsOwner() {
        when(olwen.getMeso()).thenReturn(51_000); // after the pickup: 50,000 before, over its floor

        terms.looted(olwen, 0, 0, 1_000, true);
        terms.looted(olwen, 0, 0, 1_000, false); // a party share of someone else's kill: all its owner's

        assertEquals(1_900L, terms.pendingMeso(OLWEN));
        assertEquals(100L, terms.takeKept(ALICE, "olwen"));
        assertEquals(0L, terms.takeKept(ALICE, "olwen")); // said once
    }

    @Test
    void itKeepsLootMesoUntilItsPurseHoldsItsUpkeep() {
        when(olwen.getMeso()).thenReturn(5_000); // 4,000 before the pickup: 6,000 short of its 10,000 floor

        terms.looted(olwen, 0, 0, 1_000, true);

        assertEquals(0L, terms.pendingMeso(OLWEN));
    }

    @Test
    void outOnLoanItStillOwesItsOwner() {
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(OLWEN)).thenReturn(BOB);
        when(olwen.getMeso()).thenReturn(51_000);

        terms.looted(olwen, 0, 0, 1_000, false);
        assertEquals(0L, terms.pendingMeso(OLWEN), "led by someone else and not on loan: not its owner's business");

        when(loans.onLoan(OLWEN)).thenReturn(true);
        terms.looted(olwen, 0, 0, 1_000, false);
        assertEquals(1_000L, terms.pendingMeso(OLWEN));
    }

    @Test
    void noTermsTheSwitchOffOrALiveBenchSliceSplitNothing() {
        when(olwen.getMeso()).thenReturn(51_000);
        contract("olwen", null, -1);
        terms.looted(olwen, 0, 0, 1_000, false);
        assertEquals(0L, terms.pendingMeso(OLWEN));

        contract("olwen", LootShare.OWN_DROPS, 10);
        config.COMPANION_LOOT_TERMS_ENABLED = false;
        terms.looted(olwen, 0, 0, 1_000, false);
        assertEquals(0L, terms.pendingMeso(OLWEN));

        config.COMPANION_LOOT_TERMS_ENABLED = true;
        when(bench.sliceOf(OLWEN)).thenReturn(mock(BenchDutyDispatcher.Slice.class));
        terms.looted(olwen, 0, 0, 1_000, false);
        assertEquals(0L, terms.pendingMeso(OLWEN), "a bench slice's harvest is split by benchShare");
    }

    // ---- settling -----------------------------------------------------------------------------

    @Test
    void settlingMovesTheOwedMesoFromItsPurseIntoTheHaul() {
        when(olwen.getMeso()).thenReturn(51_000);
        terms.looted(olwen, 0, 0, 1_000, false);
        RosterEntry row = row(250L);
        when(store.rosterEntry(ALICE, "olwen")).thenReturn(row);
        loaded();

        terms.settle(OLWEN);

        verify(olwen).gainMeso(-1_000, false);
        ArgumentCaptor<RosterEntry> saved = ArgumentCaptor.forClass(RosterEntry.class);
        verify(store).saveRosterProgress(saved.capture());
        assertEquals(1_250L, saved.getValue().haulMeso());
        assertEquals(0L, terms.pendingMeso(OLWEN));
        verify(store).event(ALICE, "olwen", "companion.loot_share", "+1000 meso, 0 stacks to the haul");
    }

    @Test
    void settlingNeverTakesMoreThanItsPurseHoldsAndForgivesWhatItSpent() {
        when(olwen.getMeso()).thenReturn(51_000);
        terms.looted(olwen, 0, 0, 1_000, false);
        when(olwen.getMeso()).thenReturn(400); // it bought potions meanwhile
        when(store.rosterEntry(ALICE, "olwen")).thenReturn(row(0L));
        loaded();

        terms.settle(OLWEN);

        verify(olwen).gainMeso(-400, false);
        assertEquals(0L, terms.pendingMeso(OLWEN)); // the rest is forgiven, never owed later
    }

    @Test
    void aCompanionNoLongerLoadedKeepsWhatItOwed() {
        when(olwen.getMeso()).thenReturn(51_000);
        terms.looted(olwen, 0, 0, 1_000, false);
        when(store.rosterEntry(ALICE, "olwen")).thenReturn(row(0L));

        terms.settle(OLWEN); // findByAgentCharacterId: null

        verify(olwen, never()).gainMeso(anyInt(), anyBoolean());
        verify(store, never()).saveRosterProgress(any());
    }

    // ---- the bench ----------------------------------------------------------------------------

    @Test
    void aBenchHarvestKeepsItsCutAndNoTermsMoveItAll() {
        assertEquals(900L, terms.benchShare(ALICE, "olwen", 1_000L));
        assertEquals(100L, terms.takeKept(ALICE, "olwen"));

        contract("dorran", LootShare.NONE, -1);
        assertEquals(1_000L, terms.benchShare(ALICE, "dorran", 1_000L));
        contract("mira", null, -1);
        assertEquals(1_000L, terms.benchShare(ALICE, "mira", 1_000L));
        config.COMPANION_LOOT_TERMS_ENABLED = false;
        assertEquals(1_000L, terms.benchShare(ALICE, "olwen", 1_000L));
        assertEquals(0L, terms.benchShare(ALICE, "olwen", -5L));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void contract(String personaId, LootShare share, int ownDropsPercent) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(personaId);
        when(persona.contract()).thenReturn(personaId + "-contract");
        when(content.persona(personaId)).thenReturn(persona);
        Terms authored = new Terms(4, 10, share, List.of(), 0, 0L, ownDropsPercent);
        when(content.contract(personaId + "-contract"))
                .thenReturn(new Contract(personaId + "-contract", null, null, authored, "test"));
    }

    /** Olwen is loaded: its runtime entry is found by its character id. */
    private void loaded() {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(olwen, mock(Character.class), null);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(OLWEN)).thenReturn(entry);
    }

    private static RosterEntry row(long haulMeso) {
        return new RosterEntry(ALICE, "olwen", RosterState.ACTIVE, Assignment.TRAIN, 0, 0, 104000000, 0L,
                haulMeso, 0L, 1_000L, 1_000L, 1_000L, 0, null);
    }
}
