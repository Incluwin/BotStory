package scripting.npc;

import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.invocation.Invocation;
import server.living.LivingWorldHooks;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stock Moony (9201000.js) crafts engagement ring boxes for men only; a woman marrying her companion brings him
 * (docs/BOT_LORE_COUPLES_PLAN.md, user 2026-10-25): she picks him, he agrees by friendship, she pays as a man would,
 * and the box goes to him (LivingWorldHooks.ringBoxMade), never to her; he then proposes through the stock proposal.
 * One who already carries a box is asked to propose with it. A man's talk is the stock one. Run in Graal as
 * NPCScriptManager runs it, the living world's answers stubbed.
 */
class MoonyBrideScriptTest {
    private static final int MOONSTONE_BOX = 2240000;

    private final Character bride = mock(Character.class);
    private final Character groom = mock(Character.class);
    private final List<String> windows = new ArrayList<>();
    private NPCConversationManager cm;

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @BeforeEach
    void cast() {
        when(bride.getGender()).thenReturn(1);
        when(bride.getLevel()).thenReturn(60);
        when(groom.getName()).thenReturn("Dorran");
        when(groom.canHold(anyInt(), anyInt())).thenReturn(true);
    }

    @Test
    void aBridesRingBoxIsMadeForHerGroomAndSheNeverHoldsIt() throws Exception {
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            hooks.when(() -> LivingWorldHooks.ringBoxGrooms(bride)).thenReturn(List.of(groom));
            hooks.when(() -> LivingWorldHooks.groomAgrees(bride, groom)).thenReturn(true);

            talk(bride, 0, 0, 0, 1); // make a ring, him, a Moonstone, yes

            hooks.verify(() -> LivingWorldHooks.ringBoxMade(bride, groom, MOONSTONE_BOX));
            verify(cm).gainMeso(-30000);
            assertFalse(gaveItem(MOONSTONE_BOX), "the box is his to give");
            assertTrue(windows.get(1).startsWith("simple The ring box is the groom's to give. Who is it for?"), windows.toString());
            assertTrue(windows.get(1).contains("#L0#Dorran#l"), windows.toString());
            assertTrue(windows.get(2).contains("craft for #bDorran#k"), windows.toString());
            assertTrue(windows.get(4).contains("I've given it to #bDorran#k"), windows.toString());
            assertEquals("dispose", windows.get(windows.size() - 1));
        }
    }

    @Test
    void aGroomWhoIsNotReadyGetsNoBoxAndSheIsNotCharged() throws Exception {
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            hooks.when(() -> LivingWorldHooks.ringBoxGrooms(bride)).thenReturn(List.of(groom));

            talk(bride, 0, 0);

            assertTrue(windows.get(2).contains("isn't ready for that yet"), windows.toString());
            hooks.verify(() -> LivingWorldHooks.ringBoxMade(bride, groom, MOONSTONE_BOX), never());
            verify(cm, never()).gainMeso(anyInt());
        }
    }

    @Test
    void aGroomCarryingABoxAlreadyProposesWithIt() throws Exception {
        when(groom.haveItem(MOONSTONE_BOX)).thenReturn(true);
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            hooks.when(() -> LivingWorldHooks.ringBoxGrooms(bride)).thenReturn(List.of(groom));
            hooks.when(() -> LivingWorldHooks.groomAgrees(bride, groom)).thenReturn(true);

            talk(bride, 0, 0, 1);

            assertTrue(windows.get(2).startsWith("yesno #bDorran#k already carries a ring box"), windows.toString());
            hooks.verify(() -> LivingWorldHooks.groomProposes(bride, groom));
            verify(cm, never()).gainMeso(anyInt());
        }
    }

    @Test
    void withNoGroomBesideHerTheStockAnswerSaysWhoToBring() throws Exception {
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            talk(bride, 0);

            assertTrue(windows.get(1).startsWith("ok Sorry, but the ring box is currently available only for males. "
                    + "Bring the man who will give it to you"), windows.toString());
        }
    }

    @Test
    void underLevelFiftySheIsToldBeforeAnyGroomIsAsked() throws Exception {
        when(bride.getLevel()).thenReturn(49);
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            talk(bride, 0);

            assertTrue(windows.get(1).contains("level 50 or higher"), windows.toString());
            hooks.verifyNoInteractions();
        }
    }

    @Test
    void aMansTalkIsTheStockOne() throws Exception {
        Character man = mock(Character.class);
        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            talk(man, 0, 0, 1);

            assertTrue(gaveItem(MOONSTONE_BOX), "he holds the box he will give");
            assertTrue(windows.get(windows.size() - 2).startsWith("ok All done, the engagement ring came out just right. "
                    + "I wish you a happy engagement."), windows.toString());
            hooks.verifyNoInteractions();
        }
    }

    /** start, then action(1, 0, selection) per window until the talk ends; every step answers with a window. */
    private void talk(Character player, int... selections) throws Exception {
        cm = mock(NPCConversationManager.class);
        AtomicBoolean disposed = new AtomicBoolean();
        when(cm.getPlayer()).thenReturn(player);
        when(cm.isQuestCompleted(100400)).thenReturn(true);
        when(cm.getMeso()).thenReturn(1_000_000);
        when(cm.haveItem(anyInt(), anyInt())).thenReturn(true);
        when(cm.canHold(anyInt(), anyInt())).thenReturn(true);
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
        engine.eval(Files.readString(Path.of("scripts/npc/9201000.js"), StandardCharsets.UTF_8));
        engine.put("cm", cm);
        Invocable script = (Invocable) engine;
        script.invokeFunction("start");
        for (int selection : selections) {
            if (disposed.get()) {
                break;
            }
            int before = windows.size();
            script.invokeFunction("action", 1, 0, selection);
            assertTrue(windows.size() > before, "choice " + selection + " answers with a window: " + windows);
        }
    }

    /** True when the talk put {@code itemId} in the player's bag (any gainItem overload). */
    private boolean gaveItem(int itemId) {
        for (Invocation call : mockingDetails(cm).getInvocations()) {
            if (call.getMethod().getName().equals("gainItem") && ((Number) call.getArgument(0)).intValue() == itemId
                    && ((Number) call.getArgument(1)).intValue() > 0) {
                return true;
            }
        }
        return false;
    }
}
