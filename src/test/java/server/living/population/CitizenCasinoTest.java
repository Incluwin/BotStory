package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Region;
import server.living.population.casino.CasinoContent;
import server.living.population.casino.Chips;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which citizens get a CASINO plan and where (docs/SOLOMAPLING_PORT.md): every venue is closed until its own switch is
 * on (blackjack and dice also need the chips), a switch opens only its venue, Henesys venues are for Victoria citizens,
 * no plan comes during the cooldown after a shift, and {@code !citizens casino send} names what is missing.
 */
class CitizenCasinoTest {
    private static final int HENESYS = 100_000_000;
    private static final int GAME_PARK = 100_000_203;
    private static final int DEPARTMENT_STORE = 100_000_102;
    private static final int FREE_MARKET = 910_000_000;
    private static final CasinoContent CONTENT = CasinoContent.load(Path.of("scripts/config/population"));

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final FieldCatalog fields = mock(FieldCatalog.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private Citizen citizen;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.fields()).thenReturn(fields);
        when(population.presence()).thenReturn(presence);
        citizen = new Citizen(981, "CardCam", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        victoria();
    }

    @AfterEach
    void close() {
        if (identity != null) {
            identity.close();
        }
    }

    private void victoria() {
        when(fields.regionOf(any())).thenReturn(new Region("victoria", 1, 45, List.of(HENESYS, 104_000_000), List.of(),
                List.of(), FREE_MARKET, 1));
    }

    private CitizenCasino casino() {
        return new CitizenCasino(population, (delay, step) -> { }, CONTENT);
    }

    @Test
    void theContentIsSoloMaplingsGameParkWithRealDrinks() {
        assertEquals(GAME_PARK, CONTENT.gameParkMap());
        assertEquals(5, CONTENT.tables().size());
        CONTENT.tables().forEach(table -> assertEquals(5, table.seats().size()));
        assertEquals(List.of(2020009, 2020010, 2022035, 2022000), CONTENT.drinks(),
                "Orange Juice, Grape Juice, Maple Cola, Pure Water: not the old Coca-Cola event item or a second Elixir");
        assertEquals(DEPARTMENT_STORE, CONTENT.dropGameMap());
        assertEquals(List.of(10_000_000, 50_000_000), CONTENT.dropTiers().stream().map(CasinoContent.DropTier::fee).toList());
        CONTENT.dropTiers().forEach(tier -> assertFalse(CONTENT.dropPools().get(tier.id()).items().isEmpty(), tier.id()));
        assertEquals(FREE_MARKET, CONTENT.nxSellerMap());
        assertFalse(CONTENT.gacha().prizes().isEmpty());
    }

    @Test
    void everyVenueIsClosedWhileItsSwitchIsOff() {
        CitizenCasino casino = casino();
        assertFalse(casino.plays(citizen, 0L));
        assertEquals(0, casino.mapFor(citizen));
        config.CASINO_CHIPS_ENABLED = true; // the cashier alone is no venue
        assertFalse(casino.plays(citizen, 0L));
    }

    private void opensOnly(Consumer<PopulationConfig> on, int map) {
        PopulationConfig fresh = new PopulationConfig();
        on.accept(fresh);
        when(population.config()).thenReturn(fresh);
        CitizenCasino casino = casino();
        assertTrue(casino.plays(citizen, 0L));
        for (int i = 0; i < 20; i++) {
            assertEquals(map, casino.mapFor(citizen));
        }
    }

    @Test
    void eachSwitchOpensOnlyItsOwnVenue() {
        opensOnly(c -> c.CASINO_BAR_ENABLED = true, GAME_PARK);
        opensOnly(c -> c.DROP_GAME_ENABLED = true, DEPARTMENT_STORE);
        opensOnly(c -> c.GACHA_SHOW_ENABLED = true, CONTENT.gacha().mapId());
        opensOnly(c -> c.NX_SELLER_ENABLED = true, FREE_MARKET);
        opensOnly(c -> {
            c.CASINO_BLACKJACK_ENABLED = true;
            c.CASINO_CHIPS_ENABLED = true;
        }, GAME_PARK);
        opensOnly(c -> {
            c.CASINO_DICE_ENABLED = true;
            c.CASINO_CHIPS_ENABLED = true;
        }, GAME_PARK);
    }

    @Test
    void blackjackAndDiceNeedTheChips() {
        config.CASINO_BLACKJACK_ENABLED = true;
        config.CASINO_DICE_ENABLED = true;
        assertFalse(casino().plays(citizen, 0L), "no table without the cashier's chips");
    }

    @Test
    void aCitizenOfAnotherRegionOnlySellsNxCodesInTheFreeMarket() {
        when(fields.regionOf(any())).thenReturn(new Region("ossyria", 30, 70, List.of(200_000_000), List.of(), List.of(),
                FREE_MARKET, 1));
        config.CASINO_BAR_ENABLED = true;
        config.DROP_GAME_ENABLED = true;
        config.GACHA_SHOW_ENABLED = true;
        CitizenCasino casino = casino();
        assertFalse(casino.plays(citizen, 0L), "Henesys venues are for Victoria citizens");
        config.NX_SELLER_ENABLED = true;
        assertTrue(casino.plays(citizen, 0L));
        for (int i = 0; i < 20; i++) {
            assertEquals(FREE_MARKET, casino.mapFor(citizen));
        }
    }

    @Test
    void noCasinoPlanDuringTheCooldownAfterAShift() {
        config.NX_SELLER_ENABLED = true;
        CitizenCasino casino = casino();
        CitizenSession session = new CitizenSession(citizen, 0L, 24 * 3_600_000L, false);
        long before = System.currentTimeMillis();
        casino.done(session, "shift over");
        assertFalse(casino.plays(citizen, before));
        assertTrue(casino.plays(citizen, System.currentTimeMillis() + config.CASINO_COOLDOWN_MS + 1));
    }

    private CitizenSession loaded() {
        CitizenSession session = new CitizenSession(citizen, 0L, 24 * 3_600_000L, false);
        session.entry = mock(AgentRuntimeEntry.class);
        Character agent = mock(Character.class);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(session.entry)).thenReturn(agent);
        return session;
    }

    @Test
    void sendNamesTheMissingSwitchAndOtherwiseSendsTheCitizen() {
        CitizenSession session = loaded();
        CitizenCasino casino = casino();
        String refused = casino.send(session, "dice", 0L);
        assertTrue(refused.contains("CASINO_CHIPS_ENABLED"), refused);
        verify(presence, never()).travel(any(), any(), anyInt(), anyLong());
        config.NX_SELLER_ENABLED = true;
        String sent = casino.send(session, null, 5L);
        assertTrue(sent.contains("nxseller"), sent);
        verify(presence).travel(eq(session), eq(Activity.CASINO), eq(FREE_MARKET), eq(5L));
    }

    @Test
    void chipValuesMatchTheStampsTheCashierSells() {
        assertEquals(List.of(4002000, 4002001, 4002002, 4002003), Chips.ids());
        assertEquals(1_310_000L, Chips.ids().stream().mapToLong(Chips::value).sum());
    }
}
