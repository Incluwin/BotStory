package server.agents.capabilities.supplies;

import client.Character;
import client.inventory.WeaponType;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentCombatAmmoCounter;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

/**
 * What a fight's server log shows of a member's supplies, as one text field: Easy Balrog's party vitals
 * (AgentEasyBalrogScenario) and a boss run's member-down line (AgentBossRunSession), which a dead member's bag still
 * answers.
 */
public final class AgentSupplyLogFields {
    private AgentSupplyLogFields() {
    }

    /**
     * " pots=H/M" (the HP and MP potions it carries, -1 when that cannot be told), then for a bow, crossbow, claw or
     * gun " ammo=N noAmmo=B" ({@link #ammo}).
     */
    public static String of(Character member) {
        PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        return " pots=" + gateway.hpPotionCount(member) + '/' + gateway.mpPotionCount(member) + ammo(member);
    }

    /**
     * " ammo=N noAmmo=B" for a bow, crossbow, claw or gun: the bag's count, whatever buff is up, " SA" after it while
     * Soul Arrow or Shadow Claw makes the count it fights with endless (AgentCombatAmmoCounter.countAmmo: an empty bag
     * still shoots), and its noAmmo flag ("?" without a runtime); else nothing. So "ammo=0 noAmmo=false" is a member
     * that ran dry and whose check has not flagged it yet.
     */
    public static String ammo(Character member) {
        WeaponType weapon = AgentAttackExecutionProvider.getEquippedWeaponType(member);
        if (!AgentCombatAmmoCounter.isRangedAmmoWeapon(weapon)) {
            return "";
        }
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(member.getId());
        return " ammo=" + AgentCombatAmmoCounter.countCarried(member, weapon)
                + (AgentCombatAmmoCounter.countAmmo(member, weapon) == Integer.MAX_VALUE ? " SA" : "")
                + " noAmmo=" + (entry == null ? "?" : Boolean.toString(AgentAmmoStateRuntime.noAmmo(entry)));
    }
}
