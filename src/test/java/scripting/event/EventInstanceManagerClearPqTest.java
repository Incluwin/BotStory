package scripting.event;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorldHooks;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * clearPQ hands the living world the event SCRIPT's name (its EventManager's, "ZakumBattle") beside the instance's
 * ("Zakum1"), so a boss expedition's clear can be told from a party quest's (user decision 2026-10-08).
 */
class EventInstanceManagerClearPqTest {

    @Test
    void theClearNamesTheScriptAndTheInstance() {
        EventManager manager = mock(EventManager.class, RETURNS_DEEP_STUBS);
        when(manager.getName()).thenReturn("ZakumBattle");
        EventInstanceManager instance = new EventInstanceManager(manager, "Zakum1");

        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            instance.clearPQ();

            hooks.verify(() -> LivingWorldHooks.partyQuestCleared(anyList(), eq("ZakumBattle"), eq("Zakum1")));
        }
    }

    @Test
    void aClearAfterTheInstanceLetItsManagerGoNamesNoScript() throws Exception {
        EventManager manager = mock(EventManager.class, RETURNS_DEEP_STUBS);
        EventInstanceManager instance = new EventInstanceManager(manager, "Kerning0");
        set(instance, "disposed", true); // dispose lets the manager go a minute later
        set(instance, "em", null);

        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            instance.clearPQ();

            hooks.verify(() -> LivingWorldHooks.partyQuestCleared(eq(List.of()), eq(null), eq("Kerning0")));
        }
    }

    private static void set(EventInstanceManager instance, String field, Object value) throws Exception {
        java.lang.reflect.Field declared = EventInstanceManager.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(instance, value);
    }
}
