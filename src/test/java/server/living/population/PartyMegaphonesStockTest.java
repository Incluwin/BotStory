package server.living.population;

import client.Character;
import client.Client;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Megaphones are cash items no NPC sells (F5c): at a load, a citizen a citizen-run lobby fits and carrying none buys a
 * few, never more than two; one who still has one, one too low or too high for a lobby, or a load while the switch is
 * off gets none.
 */
class PartyMegaphonesStockTest {
    private final CitizenLobbyFixture lobby = new CitizenLobbyFixture();
    private final CitizenPartyQuests quests = lobby.quests(new CitizenLobbyFixture.Door());
    private final List<String> added = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lobby.config.LF_SMEGA_ENABLED = true;
        lobby.config.LF_SMEGA_RESTOCK_CHANCE = 1.0d;
        when(lobby.population.partyQuests()).thenReturn(quests);
        lobby.open(InventoryManipulator.class)
                .when(() -> InventoryManipulator.addById(any(Client.class), anyInt(), anyShort()))
                .thenAnswer(call -> added.add(call.getArgument(1) + " x" + call.getArgument(2)));
    }

    @AfterEach
    void tearDown() {
        lobby.close();
    }

    private Character carrying(int id, String name, int level, int megaphones) {
        Character chr = lobby.citizen(id, name, level);
        when(chr.getClient()).thenReturn(mock(Client.class, withSettings().stubOnly()));
        when(chr.getItemQuantity(ChatterEngine.SUPER_MEGAPHONE, false)).thenReturn(megaphones);
        return chr;
    }

    @Test
    void aCitizenAKpqFitsAndCarryingNoneBuysTwo() {
        Character lia = carrying(1, "Lia", 25, 0);

        assertEquals(2, PartyMegaphones.stock(lobby.population, lia, lobby.session(lia).citizen));
        assertEquals(List.of("5072000 x2"), added);
    }

    @Test
    void neverAboveTwoAndNeverOnTopOfOnesItHas() {
        lobby.config.LF_SMEGA_STOCK = 2;
        Character lia = carrying(1, "Lia", 25, 1);
        assertEquals(0, PartyMegaphones.stock(lobby.population, lia, lobby.session(lia).citizen));

        lobby.config.LF_SMEGA_STOCK = 5; // validate() refuses this; the cap holds anyway
        Character mira = carrying(2, "Mira", 22, 0);
        assertEquals(PartyMegaphones.STOCK_CAP, PartyMegaphones.stock(lobby.population, mira, lobby.session(mira).citizen));
    }

    @Test
    void noneOutsideTheBandWithTheSwitchOffOrOnAnUnluckyLoad() {
        Character low = carrying(1, "Low", 12, 0);
        assertEquals(0, PartyMegaphones.stock(lobby.population, low, lobby.session(low).citizen), "no lobby fits lv12");

        Character lia = carrying(2, "Lia", 25, 0);
        lobby.config.LF_SMEGA_RESTOCK_CHANCE = 0.0d;
        assertEquals(0, PartyMegaphones.stock(lobby.population, lia, lobby.session(lia).citizen));

        lobby.config.LF_SMEGA_RESTOCK_CHANCE = 1.0d;
        lobby.config.LF_SMEGA_ENABLED = false;
        assertEquals(0, PartyMegaphones.stock(lobby.population, lia, lobby.session(lia).citizen));
        assertTrue(added.isEmpty());
    }
}
