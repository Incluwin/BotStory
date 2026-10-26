package scripting.npc;

import client.Character;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.invocation.Invocation;
import server.itcg.ItcgRecipes;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The iTCG NPC scripts (docs/ITCG_MASTERIA_PLAN.md section 3) run in Graal as NPCScriptManager runs them, each through
 * its main flow, with the player's bag stubbed: every menu answers with a window, the make gives the right item, and
 * the talk ends.
 */
class ItcgNpcScriptsTest {
    private final Map<Integer, Integer> bag = new HashMap<>();
    private final List<String> windows = new ArrayList<>();
    private final Character player = mock(Character.class);
    private NPCConversationManager cm;
    private MockedStatic<InventoryManipulator> inventory;

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @BeforeEach
    void setUp() {
        when(player.getName()).thenReturn("Tester");
        when(player.getItemQuantity(anyInt(), eq(false))).thenAnswer(call -> bag.getOrDefault(call.<Integer>getArgument(0), 0));
        inventory = mockStatic(InventoryManipulator.class);
    }

    @AfterEach
    void tearDown() {
        inventory.close();
    }

    private void holds(int itemId, int count) {
        bag.merge(itemId, count, Integer::sum);
    }

    private void holds(ItcgRecipes.Recipe recipe) {
        recipe.inputs().forEach(input -> holds(input.itemId(), input.count()));
    }

    @Test
    void foxwitDrawsAManualAndKeepsTheQuestLogTrue() throws Exception {
        holds(ItcgRecipes.DARK_MATTER, 1);
        holds(ItcgRecipes.VORTICULAR_GYRO, 1);

        talk("9201052", 0, 1, 1); // draw, the Zeta Cape manual, yes

        assertTrue(gave(4_031_826), windows.toString());
        verify(cm).forceStartQuest(ItcgRecipes.FOXWIT_QUEST);
        verify(cm).forceCompleteQuest(ItcgRecipes.FOXWIT_QUEST);
        assertTrue(windows.get(2).contains("#t1102146#"), "names what Spindle makes of it: " + windows.get(2));
        assertTrue(windows.get(3).contains("Omega Sector"), windows.toString());
    }

    @Test
    void theGlimmerManMakesAnOrbAndClosesQuest4924() throws Exception {
        holds(ItcgRecipes.MATERIA_ORB);

        talk("9201083", 0, 1);

        assertTrue(gave(ItcgRecipes.MATERIA_ORB_ID), windows.toString());
        verify(cm).forceCompleteQuest(ItcgRecipes.GLIMMER_QUEST);
    }

    @Test
    void spindleForgesFromAManualAndFromAnOrb() throws Exception {
        holds(ItcgRecipes.SPINDLE_FORGES.get(1)); // the Zeta Cape and its ores

        talk("9201082", 0, 0, 1);

        assertTrue(gave(1_102_146), windows.toString());
        bag.clear();
        windows.clear();
        holds(ItcgRecipes.MATERIA_ORB_ID, 1);

        talk("9201082", 1, 3, 1); // the fourth Versal weapon, Sunspear

        assertTrue(gave(1_432_045), windows.toString());
    }

    @Test
    void barricadeDrawsSetTwoManualsAndForgesBosshunter() throws Exception {
        holds(ItcgRecipes.NARICAIN_JEWEL, 1);
        holds(ItcgRecipes.SUBANI_ANKH, 1);

        talk("9201051", 0, 2, 1); // the Infinity Circlet manual

        assertTrue(gave(4_031_822), windows.toString());
        verify(cm).forceCompleteQuest(ItcgRecipes.BARRICADE_QUEST);
        windows.clear();
        holds(ItcgRecipes.BOSSHUNTER.get(0));

        talk("9201051", 1, 0, 1);

        assertTrue(gave(1_002_739), windows.toString());
    }

    @Test
    void corineWakesASpiritWeaponWithAGrownPetOut() throws Exception {
        holds(ItcgRecipes.CORINE.get(0));
        Pet pet = mock(Pet.class);
        when(pet.getLevel()).thenReturn((byte) 20);
        when(player.getPets()).thenReturn(new Pet[] {pet, null, null});

        talk("9201094", 0, 1);

        assertTrue(gave(1_452_055), windows.toString());
    }

    @Test
    void stirgemanClimbsOneRungAndLetsThePlayerPickAFork() throws Exception {
        holds(1_102_176, 1);
        ItcgRecipes.STIRGEMAN.get(0).cost().forEach(s -> holds(s.itemId(), s.count()));

        talk("9201102", 0, 1);

        assertTrue(gave(1_102_177), windows.toString());
        bag.clear();
        windows.clear();
        holds(1_102_180, 1);
        ItcgRecipes.STIRGEMAN.get(0).cost().forEach(s -> holds(s.itemId(), s.count()));

        talk("9201102", 0, 1, 1); // Mk IV, the Cloak of Darkness branch, yes

        assertTrue(gave(1_102_182), windows.toString());
    }

    @Test
    void litaPaysABountyOutOfTheCrate() throws Exception {
        holds(ItcgRecipes.LITA_BOUNTIES.get(0));

        talk("9201054", 0, 1);

        assertTrue(ItcgRecipes.SET1.stream().anyMatch(this::gave), windows.toString());
    }

    @Test
    void t1337BrewsTenJuicesOfOneKind() throws Exception {
        holds(ItcgRecipes.T1337.get(0));

        talk("9201101", 0, 1);

        assertTrue(ItcgRecipes.JUICES.stream().anyMatch(juice -> gave(juice, 10)), windows.toString());
    }

    @Test
    void ridleyKeepsHisKeepDirectionsAndReadsAStone() throws Exception {
        talk("9201103", 0);

        assertTrue(windows.get(1).contains("Expeditions are frequently being held"), windows.toString());
        windows.clear();
        holds(ItcgRecipes.RIDLEYS_STONE, 1);
        holds(ItcgRecipes.TENGU_NOSE, 1);

        talk("9201103", 1, 0, 1);

        assertTrue(ItcgRecipes.RIDLEY_PRIZES.stream().anyMatch(this::gave), windows.toString());
    }

    @Test
    void adonisTradesADenariAndUpgradesTheMinersHat() throws Exception {
        holds(ItcgRecipes.STONE_DENARI, 1);

        talk("9201106", 1, 1);

        assertTrue(ItcgRecipes.DENARI_PRIZES.stream().anyMatch(this::gave), windows.toString());
        windows.clear();
        holds(ItcgRecipes.ADONIS_UPGRADES.get(3));

        talk("9201106", 2, 3, 1);

        assertTrue(gave(1_002_857), windows.toString());
    }

    @Test
    void crysseaSwapsThreeDoublesForTheMissingPiece() throws Exception {
        holds(ItcgRecipes.SUBANI_ANKH, 3);

        talk("9010018", 0, 5, 1); // Set 1, the Taru Totem (sixth), yes

        assertTrue(gave(ItcgRecipes.TARU_TOTEM), windows.toString());
        inventory.verify(() -> InventoryManipulator.removeById(cm.getClient(), client.inventory.InventoryType.ETC,
                ItcgRecipes.SUBANI_ANKH, 3, false, false));
    }

    /** start, then action(1, 0, selection) per window until the talk ends; every step answers with a window. */
    private void talk(String npc, int... selections) throws Exception {
        cm = mock(NPCConversationManager.class);
        AtomicBoolean disposed = new AtomicBoolean();
        when(cm.getPlayer()).thenReturn(player);
        when(cm.getLevel()).thenReturn(120);
        when(cm.canHoldAllAfterRemoving(anyList(), anyList(), anyList(), anyList())).thenReturn(true);
        when(cm.gainItem(anyInt(), anyShort(), anyBoolean(), anyBoolean())).thenReturn(mock(Item.class));
        doAnswer(inv -> windows.add("simple " + inv.getArgument(0))).when(cm).sendSimple(anyString());
        doAnswer(inv -> windows.add("ok " + inv.getArgument(0))).when(cm).sendOk(anyString());
        doAnswer(inv -> windows.add("yesno " + inv.getArgument(0))).when(cm).sendYesNo(anyString());
        doAnswer(inv -> windows.add("next " + inv.getArgument(0))).when(cm).sendNext(anyString());
        doAnswer(inv -> {
            disposed.set(true);
            return windows.add("dispose");
        }).when(cm).dispose();

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        bindings.put("polyglot.js.allowHostAccess", true);
        bindings.put("polyglot.js.allowHostClassLookup", true);
        engine.eval(Files.readString(Path.of("scripts/npc/" + npc + ".js"), StandardCharsets.UTF_8));
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;
        script.invokeFunction("start");
        for (int selection : selections) {
            assertTrue(!disposed.get(), npc + " ended before choice " + selection + ": " + windows);
            int before = windows.size();
            script.invokeFunction("action", 1, 0, selection);
            assertTrue(windows.size() > before, "choice " + selection + " answers with a window: " + windows);
        }
        assertEquals("dispose", windows.get(windows.size() - 1), npc + " ends the talk: " + windows);
    }

    private boolean gave(int itemId) {
        return gave(itemId, -1);
    }

    /** True when the talk gave {@code itemId} (any count, or exactly {@code count}). */
    private boolean gave(int itemId, int count) {
        for (Invocation call : mockingDetails(cm).getInvocations()) {
            if (call.getMethod().getName().equals("gainItem") && ((Number) call.getArgument(0)).intValue() == itemId) {
                int given = ((Number) call.getArgument(1)).intValue();
                if (given > 0 && (count < 0 || given == count)) {
                    return true;
                }
            }
        }
        return false;
    }
}
