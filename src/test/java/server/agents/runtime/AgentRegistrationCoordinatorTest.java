package server.agents.runtime;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.agents.plans.AgentPlanReattachmentRuntime;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

class AgentRegistrationCoordinatorTest {
    @Test
    void manualRegistrationPreservesNonSpawnNormalizationFlag() {
        Character leader = mock(Character.class);
        Character agent = mock(Character.class);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        AgentLifecycleService.AgentTickCallback tickCallback = (activeEntry, leaderCharId, agentCharId) -> {
        };

        try (MockedStatic<AgentLifecycleService> lifecycle = mockStatic(AgentLifecycleService.class);
             MockedStatic<AgentPersonalityRuntime> personality = mockStatic(AgentPersonalityRuntime.class);
             MockedStatic<AgentPlanReattachmentRuntime> reattachment =
                     mockStatic(AgentPlanReattachmentRuntime.class)) {
            lifecycle.when(() -> AgentLifecycleService.registerAgent(
                            eq(100),
                            eq(leader),
                            eq(agent),
                            eq(false),
                            any(AgentLifecycleService.RegisterHooks.class)))
                    .thenReturn(entry);

            assertSame(entry, AgentRegistrationCoordinator.registerManualAgent(100, leader, agent, tickCallback));
            personality.verify(() -> AgentPersonalityRuntime.restoreOrAssign(
                    eq(entry), eq(false), anyLong()));
        }
    }

    @Test
    void anAgentWearingAWeaponItsBuildCannotUseReGearsSoonAfterLogin() {
        assertLoginReGear(false, 1);
    }

    @Test
    void anAgentWearingAUsableWeaponIsNotReGearedAtLogin() {
        assertLoginReGear(true, 0);
    }

    private static void assertLoginReGear(boolean wearsUsableWeapon, int scheduled) {
        Character leader = mock(Character.class);
        Character agent = mock(Character.class);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        AgentLifecycleService.AgentTickCallback tickCallback = (activeEntry, leaderCharId, agentCharId) -> {
        };

        try (MockedStatic<AgentLifecycleService> lifecycle = mockStatic(AgentLifecycleService.class);
             MockedStatic<AgentPlanReattachmentRuntime> reattachment =
                     mockStatic(AgentPlanReattachmentRuntime.class);
             MockedStatic<AgentEquipmentService> equipment = mockStatic(AgentEquipmentService.class);
             MockedStatic<AgentSchedulerRuntime> scheduler = mockStatic(AgentSchedulerRuntime.class)) {
            lifecycle.when(() -> AgentLifecycleService.registerAgent(
                            eq(100), eq(leader), eq(agent), eq(false),
                            any(AgentLifecycleService.RegisterHooks.class)))
                    .thenReturn(entry);
            equipment.when(() -> AgentEquipmentService.wearsUsableWeapon(agent)).thenReturn(wearsUsableWeapon);

            AgentRegistrationCoordinator.registerManualAgent(100, leader, agent, tickCallback);

            scheduler.verify(() -> AgentSchedulerRuntime.afterRandomDelay(
                    eq(entry), anyInt(), anyInt(), any(Runnable.class)), times(scheduled));
        }
    }

    @Test
    void spawnedRegistrationPreservesSpawnNormalizationFlag() {
        Character leader = mock(Character.class);
        Character agent = mock(Character.class);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        AgentLifecycleService.AgentTickCallback tickCallback = (activeEntry, leaderCharId, agentCharId) -> {
        };

        try (MockedStatic<AgentLifecycleService> lifecycle = mockStatic(AgentLifecycleService.class);
             MockedStatic<AgentPlanReattachmentRuntime> reattachment =
                     mockStatic(AgentPlanReattachmentRuntime.class)) {
            lifecycle.when(() -> AgentLifecycleService.registerAgent(
                            eq(100),
                            eq(leader),
                            eq(agent),
                            eq(true),
                            any(AgentLifecycleService.RegisterHooks.class)))
                    .thenReturn(entry);

            assertSame(entry, AgentRegistrationCoordinator.registerSpawnedAgent(100, leader, agent, tickCallback));
        }
    }
}
