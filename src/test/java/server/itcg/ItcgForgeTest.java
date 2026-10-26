package server.itcg;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.AbstractPlayerInteraction;
import server.itcg.ItcgForge.Made;
import server.itcg.ItcgRecipes.Recipe;
import server.itcg.ItcgRecipes.Stack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One make: every input and the room checked before anything is taken, the inputs taken, the result given. */
class ItcgForgeTest {
    private AbstractPlayerInteraction pi;
    private Character chr;
    private Client client;
    private final Map<Integer, Integer> bag = new HashMap<>();

    @BeforeEach
    void setUp() {
        pi = mock(AbstractPlayerInteraction.class);
        chr = mock(Character.class);
        client = mock(Client.class);
        when(pi.getPlayer()).thenReturn(chr);
        when(pi.getClient()).thenReturn(client);
        when(chr.getName()).thenReturn("Tester");
        when(chr.getItemQuantity(anyInt(), eq(false))).thenAnswer(call -> bag.getOrDefault(call.<Integer>getArgument(0), 0));
        when(pi.canHoldAllAfterRemoving(anyList(), anyList(), anyList(), anyList())).thenReturn(true);
        when(pi.gainItem(anyInt(), anyShort(), anyBoolean(), anyBoolean())).thenReturn(mock(Item.class));
    }

    private void holds(Recipe recipe) {
        for (Stack input : recipe.inputs()) {
            bag.merge(input.itemId(), input.count(), Integer::sum);
        }
    }

    @Test
    void aMakeTakesEveryInputFromTheInventoryAndGivesTheResult() {
        Recipe zeta = ItcgRecipes.FOXWIT_MANUALS.get(1);
        holds(zeta);
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            Made made = ItcgForge.make(pi, zeta);

            assertTrue(made.ok(), made.refusal());
            assertEquals(4_031_826, made.itemId());
            inventory.verify(() -> InventoryManipulator.removeById(client, InventoryType.ETC, ItcgRecipes.DARK_MATTER, 1, false, false));
            inventory.verify(() -> InventoryManipulator.removeById(client, InventoryType.ETC, ItcgRecipes.VORTICULAR_GYRO, 1, false, false));
            verify(pi).gainItem(4_031_826, (short) 1, true, true);
        }
    }

    @Test
    void aMissingInputTakesNothingAndSaysWhatIsShort() {
        Recipe zeta = ItcgRecipes.FOXWIT_MANUALS.get(1);
        bag.put(ItcgRecipes.DARK_MATTER, 1);
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            Made made = ItcgForge.make(pi, zeta);

            assertFalse(made.ok());
            assertTrue(made.refusal().contains("#t" + ItcgRecipes.VORTICULAR_GYRO + "#"), made.refusal());
            inventory.verify(() -> InventoryManipulator.removeById(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()), never());
            verify(pi, never()).gainItem(anyInt(), anyShort(), anyBoolean(), anyBoolean());
        }
    }

    @Test
    void noRoomForTheResultTakesNothing() {
        Recipe zeta = ItcgRecipes.FOXWIT_MANUALS.get(1);
        holds(zeta);
        when(pi.canHoldAllAfterRemoving(anyList(), anyList(), anyList(), anyList())).thenReturn(false);
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            Made made = ItcgForge.make(pi, zeta);

            assertFalse(made.ok());
            assertTrue(made.refusal().contains("make room"), made.refusal());
            inventory.verify(() -> InventoryManipulator.removeById(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()), never());
        }
    }

    @Test
    void corineWantsAPetOfTheRecipesLevelOut() {
        Recipe suma = ItcgRecipes.CORINE.get(0);
        holds(suma);
        Pet young = mock(Pet.class);
        when(young.getLevel()).thenReturn((byte) 14);
        when(chr.getPets()).thenReturn(new Pet[] {young, null, null});
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            assertFalse(ItcgForge.make(pi, suma).ok());
            when(young.getLevel()).thenReturn((byte) 15);

            Made made = ItcgForge.make(pi, suma);

            assertTrue(made.ok(), made.refusal());
            assertEquals(1_452_055, made.itemId());
            inventory.verify(() -> InventoryManipulator.removeById(client, InventoryType.EQUIP, 1_452_054, 1, false, false));
        }
    }

    @Test
    void aChoiceOutsideTheRecipesResultsIsRefused() {
        Recipe orb = ItcgForge.versal(1_302_079);
        bag.put(ItcgRecipes.MATERIA_ORB_ID, 1);
        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            assertFalse(ItcgForge.make(pi, orb, 1_302_088).ok());
            assertTrue(ItcgForge.make(pi, orb, 1_432_045).ok());
        }
    }

    @Test
    void theSwapSpendsThePiecesHeldMostAndNeverTheOneWanted() {
        bag.put(ItcgRecipes.DARK_MATTER, 2);
        bag.put(ItcgRecipes.ZETA_RESIDUE, 1);
        bag.put(ItcgRecipes.TARU_TOTEM, 5);
        bag.put(ItcgRecipes.SUBANI_ANKH, 4);

        Recipe swap = ItcgForge.swap(pi, ItcgRecipes.TARU_TOTEM);

        assertEquals(List.of(new Stack(ItcgRecipes.SUBANI_ANKH, 3)), swap.inputs());
        assertEquals(ItcgRecipes.TARU_TOTEM, swap.result());
        bag.clear();
        bag.put(ItcgRecipes.DARK_MATTER, 2);
        assertNull(ItcgForge.swap(pi, ItcgRecipes.TARU_TOTEM), "two pieces are not enough");
    }

    @Test
    void stirgemanGoesOneRungUpAndForksWhereTheLineDoes() {
        assertEquals(List.of(1_102_177), ItcgForge.stirgeNext(1_102_176));
        assertEquals(List.of(1_102_181, 1_102_182), ItcgForge.stirgeNext(1_102_180));
        assertEquals(List.of(1_102_183), ItcgForge.stirgeNext(1_102_182));
        assertTrue(ItcgForge.stirgeNext(1_102_183).isEmpty());
        Recipe step = ItcgForge.stirgeStep(1_302_091);
        assertEquals(List.of(1_302_092, 1_302_093), step.results());
        assertEquals(new Stack(1_302_091, 1), step.inputs().get(0));
    }
}
