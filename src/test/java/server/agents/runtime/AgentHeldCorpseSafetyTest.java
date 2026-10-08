package server.agents.runtime;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentDeathStateRuntime;
import server.agents.capabilities.combat.AgentDeathTickCoordinator;
import server.agents.capabilities.recovery.AgentLeaderSafetyService;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * A dead companion a party-quest departure gate holds in its event (LPQ, EPQ, ...) is never moved by the owner safety.
 * A party-quest coordinator's stop or grind clears the follow target, so the tick resolves the Agent as its own leader;
 * dead, it is an "inactive owner", and after the owner-inactive delay on a map with a live monster the safety scrolls it
 * to its return map, which ends the event before the clear. The tick now leaves a held corpse to its gate; every other
 * Agent runs the safety as before.
 */
class AgentHeldCorpseSafetyTest {
    private static final long DIED_AT_MS = 1_000L;
    private static final long RESPAWN_DELAY_MS = 5_000L;
    private static final long PAST_THE_OWNER_DELAY_MS =
            DIED_AT_MS + RESPAWN_DELAY_MS + AgentRuntimeConfig.cfg.OWNER_INACTIVE_TOWN_RETURN_MS + 1_000L;

    private static AgentRuntimeEntry deadEntry() {
        Character agent = mock(Character.class);
        when(agent.getHp()).thenReturn(0);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        AgentDeathStateRuntime.enterDeadState(entry, DIED_AT_MS, RESPAWN_DELAY_MS);
        return entry;
    }

    @Test
    void pinADeadAgentThatLeadsItselfIsMovedToTownOnceTheOwnerDelayPasses() {
        AgentRuntimeEntry entry = deadEntry();
        Character itself = entry.bot();
        AtomicReference<Boolean> townMove = new AtomicReference<>();
        AgentLeaderSafetyService.InactiveLeaderTickHooks hooks = new AgentLeaderSafetyService.InactiveLeaderTickHooks(
                returned -> { },
                inactive -> true,
                (inactive, town) -> {
                    townMove.set(town);
                    return town;
                },
                AgentRuntimeConfig.cfg.OWNER_INACTIVE_TOWN_RETURN_MS);

        assertFalse(AgentLeaderSafetyService.handleInactiveLeaderTick(entry, itself, DIED_AT_MS + 100L, hooks),
                "the first dead tick only starts the owner timer");
        assertTrue(AgentLeaderSafetyService.handleInactiveLeaderTick(entry, itself, PAST_THE_OWNER_DELAY_MS, hooks));
        assertEquals(Boolean.TRUE, townMove.get(), "the hazard: a live monster on the map sends the corpse to town");
    }

    @Test
    void aCorpseItsGateHoldsNeverReachesTheOwnerSafety() {
        AgentRuntimeEntry entry = deadEntry();
        try (MockedStatic<AgentLeaderSafetyCoordinator> safety = mockStatic(AgentLeaderSafetyCoordinator.class)) {
            safety.when(() -> AgentLeaderSafetyCoordinator.handleInactiveLeaderTick(
                    any(), any(), any(), anyLong(), anyInt())).thenReturn(true);

            assertFalse(AgentTickCoreRuntime.inactiveLeaderTick(
                    entry, entry.bot(), entry.bot(), DIED_AT_MS + RESPAWN_DELAY_MS, 77));
            assertFalse(AgentTickCoreRuntime.inactiveLeaderTick(
                    entry, entry.bot(), entry.bot(), PAST_THE_OWNER_DELAY_MS, 77));

            safety.verify(() -> AgentLeaderSafetyCoordinator.handleInactiveLeaderTick(
                    any(), any(), any(), anyLong(), anyInt()), never());
        }
    }

    @Test
    void pinEveryOtherAgentRunsTheOwnerSafetyAsBefore() {
        AgentRuntimeEntry dyingNow = deadEntry();
        AgentRuntimeEntry alive = new AgentRuntimeEntry(mock(Character.class), mock(Character.class), null);
        AgentRuntimeEntry respawned = deadEntry();
        AgentDeathStateRuntime.clear(respawned);
        Character owner = mock(Character.class);
        try (MockedStatic<AgentLeaderSafetyCoordinator> safety = mockStatic(AgentLeaderSafetyCoordinator.class)) {
            safety.when(() -> AgentLeaderSafetyCoordinator.handleInactiveLeaderTick(
                    any(), any(), any(), anyLong(), anyInt())).thenReturn(true);

            assertTrue(AgentTickCoreRuntime.inactiveLeaderTick(
                    dyingNow, dyingNow.bot(), dyingNow.bot(), DIED_AT_MS + 100L, 77),
                    "dead, before its respawn is due: the owner timer starts as today");
            assertTrue(AgentTickCoreRuntime.inactiveLeaderTick(alive, alive.bot(), owner, PAST_THE_OWNER_DELAY_MS, 9));
            assertTrue(AgentTickCoreRuntime.inactiveLeaderTick(
                    respawned, respawned.bot(), owner, PAST_THE_OWNER_DELAY_MS, 9));

            safety.verify(() -> AgentLeaderSafetyCoordinator.handleInactiveLeaderTick(
                    dyingNow, dyingNow.bot(), dyingNow.bot(), DIED_AT_MS + 100L, 77), times(1));
            safety.verify(() -> AgentLeaderSafetyCoordinator.handleInactiveLeaderTick(
                    alive, alive.bot(), owner, PAST_THE_OWNER_DELAY_MS, 9), times(1));
            safety.verify(() -> AgentLeaderSafetyCoordinator.handleInactiveLeaderTick(
                    respawned, respawned.bot(), owner, PAST_THE_OWNER_DELAY_MS, 9), times(1));
        }
    }

    @Test
    void aRespawnIsHeldOnlyWhileTheAgentIsDeadPastItsRespawnTime() {
        AgentRuntimeEntry entry = deadEntry();

        assertFalse(AgentDeathTickCoordinator.respawnHeld(entry, DIED_AT_MS + RESPAWN_DELAY_MS - 1L));
        assertTrue(AgentDeathTickCoordinator.respawnHeld(entry, DIED_AT_MS + RESPAWN_DELAY_MS));
        AgentDeathStateRuntime.clear(entry);
        assertFalse(AgentDeathTickCoordinator.respawnHeld(entry, PAST_THE_OWNER_DELAY_MS));
        assertFalse(AgentDeathTickCoordinator.respawnHeld(null, PAST_THE_OWNER_DELAY_MS));
    }
}
