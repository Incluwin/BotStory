package server.living;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import server.living.bond.ContractService;
import server.living.quest.QuestLog;
import server.living.quest.QuestMarkers;
import server.living.roster.BenchDutyDispatcher;
import server.living.roster.CompanionExecutor;
import server.living.roster.DirectExecutor;
import server.living.roster.RosterService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The login, logout and death hooks hand every roster write to companion-ops (docs/BOT_MANAGEMENT_PLAN.md,
 * "CompanionExecutor"): nothing is restored ahead of a logout still queued there, and a logout that runs after its
 * owner logged in again (a channel change) takes the old session's companions off and restores them for the new one.
 */
class LivingWorldHooksTest {
    private static final int ANN = 10;

    private final LivingWorld world = mock(LivingWorld.class);
    private final RosterService roster = mock(RosterService.class);
    private final ContractService contracts = mock(ContractService.class);
    private final List<Runnable> queue = DirectExecutor.queue();
    private final CompanionExecutor ops = DirectExecutor.queued(queue);
    private final Character ann = character();
    private MockedStatic<LivingWorld> living;

    @BeforeEach
    void wire() {
        when(world.roster()).thenReturn(roster);
        when(world.contracts()).thenReturn(contracts);
        when(world.executor()).thenReturn(ops);
        when(world.questLog()).thenReturn(mock(QuestLog.class));
        when(world.markers()).thenReturn(mock(QuestMarkers.class));
        when(world.benchDuty()).thenReturn(mock(BenchDutyDispatcher.class));
        when(world.crewRotation()).thenReturn(mock(server.living.roster.CrewRotation.class));
        living = mockStatic(LivingWorld.class);
        living.when(LivingWorld::get).thenReturn(world);
    }

    @AfterEach
    void close() {
        living.close();
    }

    @Test
    void theLoginRestoreRunsOnCompanionOpsBehindAnyQueuedLogout() {
        LivingWorldHooks.playerLoggedOut(ann);
        Character back = character();
        LivingWorldHooks.playerLoggedIn(back);

        verify(roster, never()).onOwnerLogin(any());
        assertEquals(2, queue.size());
        when(roster.onlineCharacter(ANN)).thenReturn(null); // the logout runs before the new session is in storage
        queue.get(0).run();
        queue.get(1).run();

        InOrder order = inOrder(roster);
        order.verify(roster).onOwnerLogout(ann, null);
        order.verify(roster).onOwnerLogin(back);
    }

    @Test
    void aLogoutRunningAfterItsOwnerIsBackTakesTheOldCompanionsOffAndRestoresThemForTheNewSession() {
        LivingWorldHooks.playerLoggedOut(ann);
        Character back = character(); // the new channel's character, in storage by the time companion-ops runs
        when(roster.onlineCharacter(ANN)).thenReturn(back);

        queue.get(0).run();

        InOrder order = inOrder(roster);
        order.verify(roster).onOwnerLogout(ann, null);
        order.verify(roster).onOwnerLogin(back);
    }

    @Test
    void anOrdinaryLogoutRestoresNothing() {
        LivingWorldHooks.playerLoggedOut(ann);
        when(roster.onlineCharacter(ANN)).thenReturn(ann); // still in storage while its logout finishes

        queue.get(0).run();

        verify(roster).onOwnerLogout(ann, null);
        verify(roster, never()).onOwnerLogin(any());
    }

    @Test
    void aLogoutInsideAnEventInstanceHandsTheInstanceReadBeforeTheDisconnectToTheRoster() {
        scripting.event.EventInstanceManager instance = mock(scripting.event.EventInstanceManager.class);
        when(ann.getEventInstance()).thenReturn(instance);
        LivingWorldHooks.playerLoggedOut(ann);
        when(ann.getEventInstance()).thenReturn(null); // the disconnect unregisters them before companion-ops runs

        queue.get(0).run();

        verify(roster).onOwnerLogout(ann, instance);
    }

    @Test
    void aChannelChangeAndACashShopVisitReachTheRosterBeforeTheTransition() {
        LivingWorldHooks.playerChangingChannel(ann, 2);
        LivingWorldHooks.playerEnteredCashShop(ann);

        verify(roster).ownerChangingChannel(ann, 2); // on the packet thread, ahead of the old session's logout
        verify(roster).ownerEnteredCashShop(ann);
        assertEquals(0, queue.size());
    }

    @Test
    void anOwnersDeathIsCountedOnCompanionOps() {
        LivingWorldHooks.playerDied(ann);

        verify(contracts, never()).onOwnerDied(any()); // a third death releases a companion: a roster write
        queue.get(0).run();
        verify(contracts).onOwnerDied(ann);
    }

    private static Character character() {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(ANN);
        return character;
    }
}
