package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.bond.BondService;
import server.living.bond.ContractService;
import server.living.config.Content;
import server.living.config.Content.BondStage;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.ContractState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Resident allocation (docs/BOT_MANAGEMENT_PLAN.md "Resident allocation", decision 18 b, flows 9 and 10): a friend keeps
 * at most RESIDENT_CAP_PER_OWNER Residents (hired hands do not count), refused in the Resident's words by both
 * ContractService.askToJoin and RosterService.recruit; and the daily sweep releases every row of an owner away
 * RESIDENT_OWNER_INACTIVE_DAYS or more (flow 10 step 6): Residents go home with their bonds remembered
 * (resident.released), hired hands go back to the population; it leaves owners seen lately or online and one still in a
 * run alone, and is idempotent; the friend hears once, when back, who went home.
 */
class ResidentAllocationTest {
    private static final int BOB = 20;
    private static final String CONTRACT = "resident-standard";

    private final LifecycleHarness game = new LifecycleHarness();
    private final BondService bonds = mock(BondService.class);
    private final List<Bond> savedBonds = new ArrayList<>();
    private final ResidentAllocation allocation = new ResidentAllocation(game.world);
    private final Persona hadrik = game.companion("hadrik", "Hadrik", 501, RosterState.BENCHED, LITH);
    private final Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.BENCHED, LITH);
    private final Persona vex = game.companion("vex", "Vex", 902, RosterState.AWAY, KERNING);

    ResidentAllocationTest() {
        when(game.world.bonds()).thenReturn(bonds);
        when(game.world.contracts()).thenReturn(new ContractService(game.world));
        when(game.world.residentAllocation()).thenReturn(allocation);
        when(bonds.peek(anyInt(), anyString())).thenAnswer(inv -> new Bond(inv.getArgument(0), inv.getArgument(1),
                BondStage.TRUSTED, 90, new LinkedHashSet<>(), ContractState.ACTIVE, 1_000L, 0L, 0, 0L, 600, 0L, 1_000L));
        doAnswer(inv -> savedBonds.add(inv.getArgument(0))).when(bonds).save(any());
        hire("hire:777");
    }

    @AfterEach
    void close() {
        game.close();
    }

    // ---- the cap -----------------------------------------------------------------------------------

    @Test
    void aFriendAtTheCapIsRefusedInTheResidentsWordsByTheRoster() {
        game.config.RESIDENT_CAP_PER_OWNER = 3; // Hadrik, Olwen and Vex
        Persona dorran = newcomer("dorran", "Dorran", 903);

        RosterResult result = game.roster().recruit(game.ann, dorran);

        assertEquals(new RosterResult.Failed(ResidentAllocation.CAP_LINE), result);
        assertNull(game.row(dorran), "nothing enrolled");
        assertTrue(game.calls.isEmpty(), game.calls.toString());
    }

    @Test
    void hiredHandsDoNotCountTowardTheCap() {
        game.config.RESIDENT_CAP_PER_OWNER = 4;
        hire("hire:778");
        hire("hire:779"); // three hires beside three Residents: still room for a fourth Resident
        Persona dorran = newcomer("dorran", "Dorran", 903);

        RosterResult result = game.roster().recruit(game.ann, dorran);

        assertTrue(result.ok(), result.toString());
        assertNotNull(game.row(dorran));
        assertTrue(allocation.atCap(game.ann), "and now she is at it");
    }

    @Test
    void askingAResidentToJoinAtTheCapIsRefusedBeforeAnyTerms() {
        game.roster();
        when(game.world.arcs().recruitable(any())).thenReturn(true);
        when(game.world.arcs().contractUnlocked(any())).thenReturn(true);
        when(game.content.contract(CONTRACT)).thenReturn(new Content.Contract(CONTRACT,
                new Content.OfferWhen(BondStage.NONE, List.of(), null, null), new Content.Price(500L, List.of(), null),
                new Content.Terms(0, 0, Content.LootShare.OWN_DROPS, List.of(Content.LeaveRule.DISMISS), 0, 0L, 10), "test"));
        Persona dorran = newcomer("dorran", "Dorran", 903);
        ContractService contracts = game.world.contracts();

        game.config.RESIDENT_CAP_PER_OWNER = 3;
        assertEquals(ResidentAllocation.CAP_LINE, contracts.askToJoin(game.ann, dorran));
        verify(bonds, never()).save(any()); // no offer made

        game.config.RESIDENT_CAP_PER_OWNER = 4; // the hire beside them never counted
        assertNull(contracts.blocker(game.ann, dorran));
    }

    @Test
    void aCapOfZeroMeansNoResidents() {
        game.roster();
        game.store.rows.clear();
        game.config.RESIDENT_CAP_PER_OWNER = 0;

        assertTrue(allocation.atCap(game.ann));
    }

    // ---- the inactivity sweep ------------------------------------------------------------------------

    @Test
    void anOwnerAwayPastTheLimitHasTheirResidentsGoHomeWithTheirBondsKept() {
        Persona brannock = game.companionOf(BOB, "brannock", "Brannock", 904, RosterState.BENCHED, LITH);
        RosterService roster = game.roster();
        game.annOnline = false;
        when(game.store.store.lastSeenAtMs(ANN)).thenReturn(daysAgo(15));
        when(game.store.store.lastSeenAtMs(BOB)).thenReturn(daysAgo(1));

        assertEquals(4, allocation.sweep());

        assertEquals(List.of(ANN + " hadrik resident.released owner inactive", ANN + " olwen resident.released owner inactive",
                ANN + " vex resident.released owner inactive"), game.store.events("resident.released"));
        for (Persona resident : List.of(hadrik, olwen, vex)) {
            assertNull(game.row(resident), resident.id());
            assertFalse(roster.isOwned(resident.id()), resident.id() + " is recruitable by any friend again");
        }
        assertNull(game.store.row(ANN, "hire:777"), "her hired hand (its character gone) is let go too, as no Resident");
        assertNotNull(game.store.row(BOB, brannock.id()), "Bob was here yesterday");
        assertEquals(3, savedBonds.size());
        for (Bond bond : savedBonds) {
            assertEquals(ContractState.ENDED, bond.contractState());
            assertEquals(90, bond.points(), "the bond is remembered");
            assertEquals(BondStage.TRUSTED, bond.stage());
        }
    }

    @Test
    void anOwnerSeenLatelyOrOnlineNowKeepsTheirResidents() {
        game.roster();
        game.annOnline = false;
        when(game.store.store.lastSeenAtMs(ANN)).thenReturn(daysAgo(13));
        assertEquals(0, allocation.sweep());

        game.annOnline = true; // online for weeks without logging out
        when(game.store.store.lastSeenAtMs(ANN)).thenReturn(daysAgo(30));
        assertEquals(0, allocation.sweep());

        assertEquals(List.of(), game.store.events("resident.released"));
        assertNotNull(game.row(hadrik));
    }

    @Test
    void theSweepIsIdempotent() {
        game.roster();
        game.annOnline = false;
        when(game.store.store.lastSeenAtMs(ANN)).thenReturn(daysAgo(20));

        assertEquals(4, allocation.sweep());
        assertEquals(0, allocation.sweep());

        assertEquals(3, game.store.events("resident.released").size());
        assertEquals(4, game.store.events("roster.release").size());
    }

    @Test
    void aResidentStillInARunWaitsForTheNextSweep() {
        game.roster();
        game.annOnline = false;
        when(game.store.store.lastSeenAtMs(ANN)).thenReturn(daysAgo(20));
        game.commitments.commit("kpq-1", BOB, 900); // Olwen finishes a run for a friend of Ann's

        assertEquals(3, allocation.sweep()); // Hadrik, Vex and the hire
        assertNotNull(game.row(olwen));

        game.commitments.releaseMember(900);
        assertEquals(1, allocation.sweep());
        assertNull(game.row(olwen));
    }

    @Test
    void anOwnerAwayPastTheLimitGivesTheirHiredHandsBackToThePopulation() {
        Persona mira = hirePersona("hire:777", "Mira");
        List<String> givenBack = new ArrayList<>();
        RosterService roster = game.roster();
        roster.installHiredHandRelease((ownerId, personaId, reason) -> givenBack.add(ownerId + " " + personaId + " " + reason));
        game.annOnline = false;
        when(game.store.store.lastSeenAtMs(ANN)).thenReturn(daysAgo(15));

        assertEquals(4, allocation.sweep());

        assertEquals(List.of(ANN + " hire:777 owner inactive"), givenBack, "HiredHands.onReleased clears its hired flag");
        assertNull(game.store.row(ANN, mira.id()));
        assertFalse(roster.isOwned(mira.id()), "hireable by anyone again");
        assertTrue(game.store.events("roster.release").contains(ANN + " hire:777 roster.release owner inactive"));
        assertTrue(game.store.events("resident.released").stream().noneMatch(line -> line.contains("hire:")),
                "a hired hand is no Resident");
    }

    @Test
    void aHiredHandStillInARunWaitsForTheNextSweep() {
        hirePersona("hire:777", "Mira");
        game.roster();
        game.annOnline = false;
        when(game.store.store.lastSeenAtMs(ANN)).thenReturn(daysAgo(20));
        game.commitments.commit("kpq-1", BOB, 777);

        assertEquals(3, allocation.sweep());

        assertNotNull(game.store.row(ANN, "hire:777"));
    }

    @Test
    void aFriendBackFromAwayHearsOnceWhoWentHome() {
        RosterService roster = game.roster();
        game.store.rows.clear(); // the sweep let them go while she was away
        when(game.store.store.untoldEvents(ANN, "roster.release", ResidentAllocation.AWAY_REASON,
                RosterService.AWAY_TOLD_EVENT)).thenReturn(List.of("hadrik"), List.of());

        roster.onOwnerLogin(game.ann);
        roster.onOwnerLogin(game.ann); // a channel change: told already

        verify(game.ann, times(1)).dropMessage(5, "While you were away, Hadrik went home to Lith Harbor.");
        assertEquals(1, game.store.events(RosterService.AWAY_TOLD_EVENT).size());
    }

    @Test
    void zeroDaysNeverSendsAnyoneHome() {
        game.roster();
        game.annOnline = false;
        game.config.RESIDENT_OWNER_INACTIVE_DAYS = 0;

        assertEquals(0, allocation.sweep());

        verify(game.store.store, never()).lastSeenAtMs(anyInt());
        assertNotEquals(0, game.store.rows.size());
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** A Resident with a contract who is on nobody's roster yet (the harness seeds a row for every companion). */
    private Persona newcomer(String id, String name, int characterId) {
        game.companion(id, name, characterId, RosterState.BENCHED, LITH); // its character, as the harness moves it
        game.store.rows.remove(FakeLivingStore.key(ANN, id));
        Persona persona = new Persona(id, name, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, CONTRACT, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, Look.NONE, "test");
        when(game.content.persona(id)).thenReturn(persona);
        when(game.residents.ensureCharacter(persona)).thenReturn(characterId);
        when(game.residents.personaOfCharacterId(characterId)).thenReturn(persona);
        game.personas.put(id, persona);
        return persona;
    }

    /** The hire's synthetic persona, as HiredHands resolves it from its characters row. */
    private Persona hirePersona(String hireId, String name) {
        Persona persona = new Persona(hireId, name, "", "HUNTER", new Content.LevelBand(30, 30), Content.Strength.SEASONED,
                null, "relaxed-v1", null, "henesys", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, Look.NONE, "living_citizen");
        when(game.content.persona(hireId)).thenReturn(persona);
        return persona;
    }

    private void hire(String hireId) {
        game.store.seed(new RosterEntry(ANN, hireId, RosterState.BENCHED, Assignment.TRAIN, 0, 0, LITH, 0L, 0L, 0L, 1_000L,
                1_000L, 1_000L, 0, null));
    }

    private static long daysAgo(int days) {
        return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
    }
}
