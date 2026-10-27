package server.living.standing;

import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.bond.Friendship;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.CitizenStore;
import server.living.population.CitizenStore.Acquaintance;
import server.living.population.PersonMemories;
import server.living.population.Population;
import server.living.roster.RosterService;
import server.living.standing.Standing.Rung;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.ContractState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** {@code !standing} (docs/BOT_CONCEPT_PLAN.md C1.1): the rung and the raw numbers for a Resident, a citizen and a hire. */
class StandingReportTest {
    private static final int ANN = 10;
    private static final int MIRA = 950;
    private static final String HIRE = "hire:950";

    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final BondService bonds = mock(BondService.class);
    private final RosterService roster = mock(RosterService.class);
    private final Population population = mock(Population.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final CitizenStore citizens = mock(CitizenStore.class);
    private MockedStatic<Population> populations;

    @BeforeEach
    void wire() {
        when(world.config()).thenReturn(config);
        Friendship friendship = new Friendship(world); // reads the config: not inside a stubbing
        when(world.friendship()).thenReturn(friendship);
        when(world.bonds()).thenReturn(bonds);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        Persona hadrik = mock(Persona.class);
        when(hadrik.id()).thenReturn("hadrik");
        when(hadrik.name()).thenReturn("Hadrik");
        when(content.personas()).thenReturn(Map.of("hadrik", hadrik));
        Citizen mira = mock(Citizen.class);
        when(mira.characterId()).thenReturn(MIRA);
        when(mira.name()).thenReturn("Mira");
        when(population.citizenByName("Mira")).thenReturn(mira);
        when(population.memories()).thenReturn(memories);
        when(population.store()).thenReturn(citizens);
        when(citizens.acquaintance(MIRA, ANN)).thenReturn(new Acquaintance(MIRA, ANN, 34, 80, false, false));
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
    }

    @AfterEach
    void close() {
        populations.close();
    }

    @Test
    void aResidentGoesByItsFriendship() {
        when(bonds.peek(ANN, "hadrik")).thenReturn(bond("hadrik", BondStage.TRUSTED, 80));

        StandingReport.Report report = StandingReport.of(world, "hadrik", ANN, "Ann", id -> null);

        assertEquals(StandingReport.Kind.RESIDENT, report.kind());
        assertEquals(Rung.FRIEND, report.rung());
        assertEquals(List.of("Hadrik (Resident) and Ann: FRIEND",
                "friendship Lv 4 Friend (30 points to Lv 5), 80 points, stage TRUSTED"), report.lines());
    }

    @Test
    void aCitizenGoesByItsWarmth() {
        when(memories.warmth(MIRA, ANN)).thenReturn(22);

        StandingReport.Report report = StandingReport.of(world, "Mira", ANN, "Ann", id -> null);

        assertEquals(StandingReport.Kind.CITIZEN, report.kind());
        assertEquals(Rung.REGULAR, report.rung());
        assertEquals("Mira (citizen) and Ann: REGULAR", report.lines().get(0));
        assertTrue(report.lines().get(1).startsWith("warmth 22 (REGULAR at 15, FRIEND at 40"), report.lines().get(1));
        assertTrue(report.lines().get(1).endsWith("together 34 party min, 80 map min"), report.lines().get(1));
        assertEquals("offers to stay on: by its temperament; a hire would start at Lv 2 (25 points)"
                + " [HIRE_FRIENDSHIP_ENABLED off]", report.lines().get(2));
    }

    @Test
    void aGrudgeIsSaidAndNeverOffers() {
        when(memories.warmth(MIRA, ANN)).thenReturn(-45);

        StandingReport.Report report = StandingReport.of(world, "Mira", ANN, "Ann", id -> null);

        assertEquals("Mira (citizen) and Ann: STRANGER, a grudge", report.lines().get(0));
        assertTrue(report.lines().get(2).startsWith("offers to stay on: no;"), report.lines().get(2));
    }

    @Test
    void aHireGoesByItsFriendshipWithItsOwnerAndShowsItsPerks() {
        config.HIRE_FRIENDSHIP_ENABLED = true;
        when(roster.cachedOwnerOf(HIRE)).thenReturn(ANN);
        when(bonds.peek(ANN, HIRE)).thenReturn(bond(HIRE, BondStage.TRUSTED, 75));
        when(memories.warmth(MIRA, ANN)).thenReturn(40);

        StandingReport.Report report = StandingReport.of(world, "Mira", ANN, "Ann", id -> id == ANN ? "Ann" : null);

        assertEquals(StandingReport.Kind.HIRE, report.kind());
        assertEquals(Rung.FRIEND, report.rung());
        assertEquals(List.of("Mira (hired by Ann) and Ann: FRIEND",
                "friendship Lv 4 Friend (35 points to Lv 5), 75 points, stage TRUSTED; warmth 40",
                "perks: buddy request (yes), wages -25% (not yet), permanent (not yet); let go it keeps warmth 40 or more"),
                report.lines());
    }

    @Test
    void nobodyByThatNameIsNull() {
        assertNull(StandingReport.of(world, "Nobody", ANN, "Ann", id -> null));
    }

    private static Bond bond(String personaId, BondStage stage, int points) {
        return new Bond(ANN, personaId, stage, points, new LinkedHashSet<>(), ContractState.NONE, 0L, 0L, 0, 0L, 0, 0L, 0L);
    }
}
