package scripting.event;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.living.LivingWorldHooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * A clear record (docs/BOT_CONCEPT_PLAN.md F7.1) is handed to the living world once an instance, however often its
 * script calls clearPQ, with the run's start: its first event timer, which a restarted timer (CWKPQ, PiratePQ) keeps,
 * else the instance's creation.
 */
class EventInstanceManagerClearRecordTest {

    @Test
    void aClearIsRecordedOnceAnInstance() {
        EventManager manager = mock(EventManager.class, RETURNS_DEEP_STUBS);
        when(manager.getName()).thenReturn("BalrogBattle_Easy");
        EventInstanceManager instance = new EventInstanceManager(manager, "Balrog1");

        try (MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            instance.clearPQ();
            instance.clearPQ();

            hooks.verify(() -> LivingWorldHooks.eventCleared(anyList(), eq("BalrogBattle_Easy"), eq("Balrog1"), anyLong()),
                    times(1));
            hooks.verify(() -> LivingWorldHooks.partyQuestCleared(anyList(), eq("BalrogBattle_Easy"), eq("Balrog1")),
                    times(2));
        }
    }

    @Test
    void theRunStartsAtItsFirstTimerAndARestartKeepsIt() throws Exception {
        long before = System.currentTimeMillis();
        EventInstanceManager instance = new EventInstanceManager(mock(EventManager.class, RETURNS_DEEP_STUBS), "CWKPQ1");
        assertTrue(instance.getClearStartedAt() >= before, "no timer yet: its creation");

        try (MockedStatic<TimerManager> timers = mockStatic(TimerManager.class)) {
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            Thread.sleep(5L);
            instance.startEventTimer(600_000L);
            long first = instance.getTimeStarted();
            Thread.sleep(5L);
            instance.restartEventTimer(300_000L);

            assertTrue(instance.getTimeStarted() > first);
            assertEquals(first, instance.getClearStartedAt());
        }
    }
}
