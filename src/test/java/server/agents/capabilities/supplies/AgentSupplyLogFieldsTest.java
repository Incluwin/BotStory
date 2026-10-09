package server.agents.capabilities.supplies;

import client.BuffStat;
import client.Character;
import client.inventory.WeaponType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentCombatAmmoCounter;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The supplies field of a fight's log lines (Easy Balrog's party vitals, a boss run's member-down line). Review fix
 * (2026-10-09): with Soul Arrow or Shadow Claw up the count a shooter fights with is endless, so an empty bag still
 * shoots; " SA" after the bag's count tells that apart from a member that is dry and not yet flagged.
 */
class AgentSupplyLogFieldsTest {
    private final Character sorrel = mock(Character.class);
    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final AgentRuntimeEntry entry = new AgentRuntimeEntry(sorrel, null, null);
    private MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> primitives;
    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentAttackExecutionProvider> execution;
    private MockedStatic<AgentCombatAmmoCounter> ammo;

    @BeforeEach
    void sorrelShootsACrossbow() {
        when(sorrel.getId()).thenReturn(903);
        when(sorrel.getBuffedValue(any(BuffStat.class))).thenReturn(null); // no buff up
        when(gateway.hpPotionCount(sorrel)).thenReturn(120);
        when(gateway.mpPotionCount(sorrel)).thenReturn(40);
        primitives = Mockito.mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        primitives.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
        registry = Mockito.mockStatic(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(903)).thenReturn(entry);
        execution = Mockito.mockStatic(AgentAttackExecutionProvider.class, Mockito.CALLS_REAL_METHODS);
        execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(sorrel))
                .thenReturn(WeaponType.CROSSBOW);
        ammo = Mockito.mockStatic(AgentCombatAmmoCounter.class, Mockito.CALLS_REAL_METHODS);
        ammo.when(() -> AgentCombatAmmoCounter.countCarried(sorrel, WeaponType.CROSSBOW)).thenReturn(0);
    }

    @AfterEach
    void close() {
        ammo.close();
        execution.close();
        registry.close();
        primitives.close();
    }

    @Test
    void anEmptyBagUnderSoulArrowIsMarkedSoItReadsApartFromADryMemberNotYetFlagged() {
        assertEquals(" pots=120/40 ammo=0 noAmmo=false", AgentSupplyLogFields.of(sorrel), "dry, flag not yet set");

        when(sorrel.getBuffedValue(BuffStat.SOULARROW)).thenReturn(1);
        assertEquals(" pots=120/40 ammo=0 SA noAmmo=false", AgentSupplyLogFields.of(sorrel), "still shooting");

        AgentAmmoStateRuntime.setNoAmmo(entry, true);
        when(sorrel.getBuffedValue(BuffStat.SOULARROW)).thenReturn(null);
        assertEquals(" pots=120/40 ammo=0 noAmmo=true", AgentSupplyLogFields.of(sorrel));
    }

    @Test
    void aMemberWithNoProjectileWeaponShowsItsPotionsOnly() {
        execution.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(sorrel))
                .thenReturn(WeaponType.SPEAR_STAB);

        assertEquals(" pots=120/40", AgentSupplyLogFields.of(sorrel));
    }
}
