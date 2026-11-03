package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestScriptCallbacks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HPQ live 2026-11-02: a run failing under its session lock exited its players through HenesysPQ.js, while on another
 * thread the script's playerUnregistered (under the script's lock) waited for that session in forfeitUnclaimedReward.
 * All four TimerManager-Core threads stopped for 77 min. The callback now never waits on the session.
 */
class AgentHpqScriptCallbackDeadlockTest {
    private final List<Runnable> handedOff = new CopyOnWriteArrayList<>();
    private Consumer<Runnable> previous;
    private AgentHpqSession session;

    @BeforeEach
    void handOffCollects() {
        previous = AgentPartyQuestScriptCallbacks.replaceHandOffForTest(handedOff::add);
    }

    @AfterEach
    void restore() {
        AgentPartyQuestScriptCallbacks.replaceHandOffForTest(previous);
        if (session != null) AgentHpqSessionRegistry.remove(session);
    }

    @Test
    void theScriptsCallbackDoesNotWaitWhileAnotherThreadHoldsTheSession() throws InterruptedException {
        EventInstanceManager event = mock(EventInstanceManager.class);
        session = registered(event);
        Character member = member(910_102, event);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch letGo = new CountDownLatch(1);
        Thread coordinator = new Thread(() -> {
            synchronized (session) {
                held.countDown();
                awaitQuietly(letGo);
            }
        });
        coordinator.start();
        assertTrue(held.await(5, SECONDS));

        Thread script = new Thread(() -> AgentHpqSessionRegistry.forfeitUnclaimedReward(member));
        script.start();
        script.join(5_000L);
        try {
            assertFalse(script.isAlive(), "the script's thread waited for the session");
        } finally {
            letGo.countDown();
            coordinator.join(5_000L);
            script.join(5_000L);
        }

        assertEquals(AgentHpqMemberState.RewardState.PENDING, session.member(910_102).rewardState());
        assertEquals(1, handedOff.size());
        handedOff.forEach(Runnable::run);
        assertEquals(AgentHpqMemberState.RewardState.FORFEITED, session.member(910_102).rewardState());
    }

    @Test
    void theThreadThatHoldsTheSessionForfeitsAtOnce() {
        EventInstanceManager event = mock(EventInstanceManager.class);
        session = registered(event);

        synchronized (session) { // a run ending exits its own players through the script
            AgentHpqSessionRegistry.forfeitUnclaimedReward(member(910_102, event));
        }

        assertEquals(AgentHpqMemberState.RewardState.FORFEITED, session.member(910_102).rewardState());
        assertTrue(handedOff.isEmpty());
    }

    @Test
    void anotherEventsUnregisterForfeitsNothing() {
        session = registered(mock(EventInstanceManager.class));

        AgentHpqSessionRegistry.forfeitUnclaimedReward(member(910_102, mock(EventInstanceManager.class)));

        assertTrue(handedOff.isEmpty());
        assertEquals(AgentHpqMemberState.RewardState.PENDING, session.member(910_102).rewardState());
    }

    private static AgentHpqSession registered(EventInstanceManager event) {
        AgentHpqSession session = new AgentHpqSession(
                AgentHpqSession.Mode.TEST_OBSERVATION, 910_007L, 910_101, 3, 1_000L);
        session.addMember(910_101, AgentHpqMemberState.MemberType.AGENT);
        session.addMember(910_102, AgentHpqMemberState.MemberType.AGENT);
        session.addMember(910_103, AgentHpqMemberState.MemberType.HUMAN);
        session.setLeadership(910_101, 910_101);
        session.bindEventInstance(event);
        AgentHpqSessionRegistry.registerComplete(session);
        return session;
    }

    private static Character member(int characterId, EventInstanceManager event) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(characterId);
        when(character.getEventInstance()).thenReturn(event);
        return character;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
