package server.agents.capabilities.partyquest;

import server.agents.runtime.AgentSchedulerRuntime;

import java.util.function.Consumer;

/**
 * A run's session update that its event script's callback asks for (playerUnregistered's reward forfeit in
 * HenesysPQ.js, KerningPQ.js and LudiPQ.js). The callback runs under the script's lock, and a coordinator that holds its
 * session calls into that script (exitPlayer, dispose, a party leave), so a callback waiting for the session deadlocks
 * both. HPQ, live 2026-11-02: all four core timer threads for 77 min, every Agent frozen, and the shutdown task never
 * ran. The thread that already holds the session (a run ending exits its own players) updates it at once; any
 * other hands the update to the scheduler, so a script never waits on a session.
 */
public final class AgentPartyQuestScriptCallbacks {
    private static volatile Consumer<Runnable> handOff = action -> AgentSchedulerRuntime.schedule(action, 0L);

    private AgentPartyQuestScriptCallbacks() {
    }

    public static void update(Object session, Runnable action) {
        if (Thread.holdsLock(session)) {
            action.run();
        } else {
            handOff.accept(action);
        }
    }

    /** Swaps how an update leaves the script's thread (a test runs it in place); returns the one it replaced. */
    public static Consumer<Runnable> replaceHandOffForTest(Consumer<Runnable> next) {
        Consumer<Runnable> previous = handOff;
        handOff = next;
        return previous;
    }
}
