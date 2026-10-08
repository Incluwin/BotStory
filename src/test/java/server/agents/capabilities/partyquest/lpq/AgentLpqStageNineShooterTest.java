package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * LPQ stage 9 in a companion run: a claw with no stars takes no shooter station out of Alishar's reach (where it
 * could not hit him); it gets the melee orders instead. A stock run keeps every ranged weapon a shooter.
 */
class AgentLpqStageNineShooterTest {
    @Test
    void aDryClawIsNoStageNineShooterInACompanionRun() {
        Character dry = AgentLpqStageSevenAssignmentTest.assassin(0);
        Character loaded = AgentLpqStageSevenAssignmentTest.assassin(800);
        try (MockedStatic<AgentAttackExecutionProvider> weapons = AgentLpqStageSevenAssignmentTest.claws();
             MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> gateways = rangedWeapons()) {
            assertFalse(AgentLpqCoordinator.stageNineShooter(dry, true));
            assertTrue(AgentLpqCoordinator.stageNineShooter(loaded, true));
        }
    }

    @Test
    void aStockRunKeepsEveryRangedWeapon() {
        Character dry = AgentLpqStageSevenAssignmentTest.assassin(0);
        try (MockedStatic<AgentAttackExecutionProvider> weapons = AgentLpqStageSevenAssignmentTest.claws();
             MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> gateways = rangedWeapons()) {
            assertTrue(AgentLpqCoordinator.stageNineShooter(dry, false));
        }
    }

    @Test
    void aDryClawIsSentToNoBlackRatzInACompanionRunButIsInAStockRun() {
        // review: removing this gate left every AgentLpq test green; a dry companion would go to the balloon platform
        Character dry = luckySeven(AgentLpqStageSevenAssignmentTest.assassin(0));
        Character loaded = luckySeven(AgentLpqStageSevenAssignmentTest.assassin(800));
        try (MockedStatic<AgentAttackExecutionProvider> weapons = AgentLpqStageSevenAssignmentTest.claws()) {
            assertFalse(AgentLpqCoordinator.stageNineRatzShooter(dry, true));
            assertTrue(AgentLpqCoordinator.stageNineRatzShooter(loaded, true));
            assertTrue(AgentLpqCoordinator.stageNineRatzShooter(dry, false), "a stock run keeps the skill-only pick");
            assertFalse(AgentLpqCoordinator.stageNineRatzShooter(AgentLpqStageSevenAssignmentTest.assassin(800), true),
                    "no ranged attack skill");
        }
    }

    /** {@code rogue} with Lucky Seven learned. */
    private static Character luckySeven(Character rogue) {
        when(rogue.getSkillLevel(constants.skills.Rogue.LUCKY_SEVEN)).thenReturn(1);
        return rogue;
    }

    /** The gateway the coordinator's actions reach says every character's weapon shoots. */
    private static MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> rangedWeapons() {
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        when(gateway.rangedWeapon(any())).thenReturn(true);
        MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> gateways =
                mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        gateways.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
        return gateways;
    }
}
