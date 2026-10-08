package server.agents.capabilities.expedition.balrog;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Before the seal releases the left claw, members stage on the lower-platform line right of the
 * future claw body. Live run 2026-10-07: ordinal 0's safe point (x=360) equals the staging exit
 * test (x < 360), and a precise move counts as arrived 8 px short, so she stopped at 352 and was
 * re-sent to staging every tick until the seal released (about 60 s with no attacks or heals).
 */
class AgentEasyBalrogInitialClawStagingTest {
    private static final int MEMBER_ID = 7101;
    private static final int LOWER_PLATFORM_Y = 258;

    @Test
    void companionOrdinalZeroArrivedShortOfItsSafePointGrindsInsteadOfBeingRestaged() {
        PrimitiveCapabilityGateway gateway = tickSealedClaw(
                AgentEasyBalrogScenario.forCompanions(), 0, new Point(352, LOWER_PLATFORM_Y), true);

        verify(gateway).grind(any(AgentRuntimeEntry.class), Mockito.eq(AgentBalrogDefinition.CLAW_MOBS));
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
    }

    @Test
    void companionOrdinalZeroAtTheLaterRenewedStopAlsoGrinds() {
        PrimitiveCapabilityGateway gateway = tickSealedClaw(
                AgentEasyBalrogScenario.forCompanions(), 0, new Point(357, LOWER_PLATFORM_Y), true);

        verify(gateway).grind(any(AgentRuntimeEntry.class), Mockito.eq(AgentBalrogDefinition.CLAW_MOBS));
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
    }

    @Test
    void fixtureOrdinalZeroArrivedShortOfItsSafePointAlsoGrinds() {
        PrimitiveCapabilityGateway gateway = tickSealedClaw(
                new AgentEasyBalrogScenario(1234L), 0, new Point(352, LOWER_PLATFORM_Y), true);

        verify(gateway).grind(any(AgentRuntimeEntry.class), Mockito.eq(AgentBalrogDefinition.CLAW_MOBS));
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
    }

    @Test
    void ordinalZeroStillStagesFromTheFutureClawBody() {
        PrimitiveCapabilityGateway gateway = tickSealedClaw(
                AgentEasyBalrogScenario.forCompanions(), 0, new Point(100, LOWER_PLATFORM_Y), true);

        verify(gateway).navigate(any(AgentRuntimeEntry.class),
                Mockito.eq(groundedSafePoint(0)), Mockito.eq(true));
        verify(gateway, never()).grind(any(), anySet());
    }

    @Test
    void laterOrdinalsLeftOfTheStagingLineStillStageAsBefore() {
        for (int ordinal = 1; ordinal < AgentBalrogDefinition.ROSTER_SIZE; ordinal++) {
            for (Point position : List.of(new Point(352, LOWER_PLATFORM_Y), new Point(100, LOWER_PLATFORM_Y))) {
                PrimitiveCapabilityGateway gateway = tickSealedClaw(
                        AgentEasyBalrogScenario.forCompanions(), ordinal, position, true);

                verify(gateway).navigate(any(AgentRuntimeEntry.class),
                        Mockito.eq(groundedSafePoint(ordinal)), Mockito.eq(true));
                verify(gateway, never()).grind(any(), anySet());
            }
        }
    }

    @Test
    void laterOrdinalsPastTheStagingLineGrindAsBefore() {
        for (int ordinal = 1; ordinal < AgentBalrogDefinition.ROSTER_SIZE; ordinal++) {
            Point arrived = new Point(groundedSafePoint(ordinal).x - 8, LOWER_PLATFORM_Y);
            PrimitiveCapabilityGateway gateway = tickSealedClaw(
                    AgentEasyBalrogScenario.forCompanions(), ordinal, arrived, true);

            verify(gateway).grind(any(AgentRuntimeEntry.class), Mockito.eq(AgentBalrogDefinition.CLAW_MOBS));
            verify(gateway, never()).navigate(any(), any(), anyBoolean());
        }
    }

    @Test
    void memberWithNoGroundedSafePointGrindsInsteadOfIdling() {
        PrimitiveCapabilityGateway gateway = tickSealedClaw(
                AgentEasyBalrogScenario.forCompanions(), 0, new Point(100, LOWER_PLATFORM_Y), false);

        verify(gateway).grind(any(AgentRuntimeEntry.class), Mockito.eq(AgentBalrogDefinition.CLAW_MOBS));
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
    }

    private static Point groundedSafePoint(int ordinal) {
        Point authored = AgentEasyBalrogCombatPolicy.initialClawSafePoint(ordinal);
        return new Point(authored.x, LOWER_PLATFORM_Y);
    }

    /**
     * One adopted member runs one fight tick while the initial claw and the seal are alive
     * (SEALED_CLAW). nowMs -1 skips the vitals log. The gateway resolves the member's authored safe
     * point to the lower platform, or to nothing when {@code safePointOnGround} is false.
     */
    private static PrimitiveCapabilityGateway tickSealedClaw(
            AgentEasyBalrogScenario fight, int ordinal, Point position, boolean safePointOnGround) {
        Character member = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
        PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
        Monster initialClaw = liveMob(AgentBalrogDefinition.INITIAL_CLAW_MOB);
        Monster seal = liveMob(AgentBalrogDefinition.RELEASE_SEAL_MOB);
        when(member.getId()).thenReturn(MEMBER_ID);
        when(member.getName()).thenReturn("Isolde");
        when(member.getMap()).thenReturn(map);
        when(member.getPosition()).thenReturn(position);
        when(member.getHp()).thenReturn(100);
        when(member.getCurrentMaxHp()).thenReturn(100);
        when(member.getMp()).thenReturn(100);
        when(member.getCurrentMaxMp()).thenReturn(100);
        when(gateway.groundPoint(map, AgentEasyBalrogCombatPolicy.initialClawSafePoint(ordinal)))
                .thenReturn(safePointOnGround ? groundedSafePoint(ordinal) : null);

        try (MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives =
                     Mockito.mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
             MockedStatic<AgentRuntimeRegistry> registry = Mockito.mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentCombatBuffRuntime> ignoredBuffs = Mockito.mockStatic(AgentCombatBuffRuntime.class);
             MockedStatic<AgentMapPerception> perception = Mockito.mockStatic(AgentMapPerception.class)) {
            primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(MEMBER_ID)).thenReturn(entry);
            perception.when(() -> AgentMapPerception.monsters(map)).thenReturn(List.of(initialClaw, seal));

            fight.adopt(MEMBER_ID, ordinal);
            fight.tick(List.of(member), null, -1L);
        }
        return gateway;
    }

    private static Monster liveMob(int mobId) {
        Monster mob = mock(Monster.class);
        when(mob.getId()).thenReturn(mobId);
        when(mob.isAlive()).thenReturn(true);
        return mob;
    }
}
