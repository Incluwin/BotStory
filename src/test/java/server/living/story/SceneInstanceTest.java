package server.living.story;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventManager;
import server.TimerManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A scene's private instance answers its few callbacks in Java and never reaches a script (F6.2): a warp away
 * unregisters at once (so it lands on the channel's map) and leaves the scene, a revive goes to the exit, a disconnect
 * ends the scene, and monsterValue is a number.
 */
class SceneInstanceTest {
    private static final int STAGE = 101040001;
    private static final int EXIT = 104000000;

    private final List<String> heard = new ArrayList<>();
    private final SceneInstance instance = new SceneInstance(mock(EventManager.class, RETURNS_DEEP_STUBS), "Scene-test",
            STAGE, EXIT, new SceneInstance.Exits() {
                @Override
                public void left(Character chr) {
                    heard.add("left " + chr.getId());
                }

                @Override
                public void disconnected(Character chr) {
                    heard.add("disconnected " + chr.getId());
                }
            });

    private static Character character(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        return chr;
    }

    @Test
    void aWarpAwayUnregistersAtOnceAndLeavesTheScene() {
        Character ann = character(10);
        instance.changedMap(ann, STAGE);
        assertEquals(List.of(), heard, "into the stage: nothing");

        instance.changedMap(ann, EXIT);
        verify(ann).setEventInstance(null);
        assertEquals(List.of("left 10"), heard);
    }

    @Test
    void aReviveInTheSceneGoesToItsExitInsteadOfTheStockReturn() {
        Character ann = character(10);
        assertFalse(instance.revivePlayer(ann), "the stock respawn path does not run");
        verify(ann).respawn(instance, EXIT);
        assertEquals(List.of("left 10"), heard);
    }

    @Test
    void aDisconnectIsHeardAndMonsterValueIsANumber() throws Exception {
        instance.playerDisconnected(character(10));
        assertEquals(List.of("disconnected 10"), heard);
        assertEquals(0, instance.invokeScriptFunction("monsterValue", instance, 2230102));
        assertEquals(null, instance.invokeScriptFunction("playerEntry", instance, character(10)), "every other: nothing");
    }

    @Test
    void endingIsOnce() {
        assertFalse(instance.ended());
        TimerManager timer = mock(TimerManager.class);
        try (MockedStatic<TimerManager> timers = mockStatic(TimerManager.class)) {
            timers.when(TimerManager::getInstance).thenReturn(timer);
            instance.end();
            int once = mockingDetails(timer).getInvocations().size();
            instance.end();
            assertEquals(once, mockingDetails(timer).getInvocations().size(), "the second end does nothing");
        }
        assertTrue(instance.ended());
    }
}
