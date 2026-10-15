package server.agents.capabilities.shop;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import server.Shop;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentCombatAmmoCounter;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.capabilities.supplies.AgentSupplyRecoveryPolicy;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * A readiness counter with no walk to it. An Agent loaded where no shop is in reach, after coming by recall past a
 * store on the way (a boss's bench ally loaded in Mu Young's room never walks through Ant Tunnel Park, P7 Part B),
 * buys at that store what it lacks of its activity's readiness standard as a readiness trip's counter does there:
 * the potions up to the standard (AgentShopService's readiness floor), the projectiles for its weapon up to the ammo
 * target (AgentShopService.buyReadinessAmmo), then with what is left the potions on toward their buy target
 * (BUY_TO_PERCENT of the standard) and the projectiles toward their headroom. The same picks, the store's prices, the
 * same per-visit and Use-slot limits (the slot a run keeps free stays free), from its own purse less its wallet
 * reserve, through the direct shop transaction gateway. It says nothing: its host says what it still lacks. It leaves
 * out what a walk-in visit also does (the reserve-bar passes, the old Victoria ammo top-up, skill and buff
 * consumables). Nothing when it lacks nothing.
 */
public final class AgentShopProvisioning {
    /** What one provisioning bought: the HP and MP potions and projectiles it gained, its purse before and after. */
    public record Provisioned(int hpPotions, int mpPotions, int projectiles, int mesosBefore, int mesosAfter) {
        /** Nothing bought, with {@code mesos} in the purse. */
        public static Provisioned nothing(int mesos) {
            return new Provisioned(0, 0, 0, mesos, mesos);
        }

        /** True when it bought any potion or projectile. */
        public boolean boughtAnything() {
            return hpPotions > 0 || mpPotions > 0 || projectiles > 0;
        }
    }

    private AgentShopProvisioning() {
    }

    /**
     * The loaded Agent buys at NPC {@code npcId}'s shop what it lacks of {@code activity}'s readiness standard (its
     * potions, and the projectiles for its weapon), with no visit. Nothing when it lacks nothing, or that NPC has no
     * shop. On the thread that holds the Agent (a host's companion-ops right after its load, say).
     */
    public static Provisioned provisionAt(Character agent, int npcId, AgentSupplyReadiness.Activity activity) {
        int mesos = agent == null ? 0 : agent.getMeso();
        if (agent == null || activity == null || npcId <= 0 || agent.getInventory(InventoryType.USE) == null
                || AgentSupplyReadiness.shortfall(agent, activity).none()) {
            return Provisioned.nothing(mesos);
        }
        Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npcId);
        if (shop == null) {
            return Provisioned.nothing(mesos);
        }
        return provision(agent, shop, activity, AgentSupplyRecoveryPolicy.minimumWalletReserve(agent),
                AgentInventoryGatewayRuntime.inventory(), AgentAttackExecutionProvider.getEquippedWeaponType(agent));
    }

    /**
     * 2026-10-15 (P18 run 6): the loaded Agent buys at NPC {@code npcId}'s shop the seal cures it lacks of
     * {@code activity}'s cure standard (AgentSupplyReadiness.cureStandard: "horntail_cure"), as a readiness trip's cure
     * step does there ({@link AgentShopService#readinessCureStep}), with no visit. Returns how many it bought; 0 when it
     * lacks none, the activity has no cure standard, or that NPC has no shop. On the thread that holds the Agent.
     */
    public static int provisionCuresAt(Character agent, int npcId, AgentSupplyReadiness.Activity activity) {
        int standard = AgentSupplyReadiness.cureStandard(activity);
        if (agent == null || npcId <= 0 || standard <= 0 || agent.getInventory(InventoryType.USE) == null
                || AgentSupplyReadiness.cureShortfall(agent, activity) <= 0) {
            return 0;
        }
        Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npcId);
        if (shop == null) {
            return 0;
        }
        int before = AgentSupplyReadiness.carriedCures(agent);
        AgentShopPurchaseSequence<AgentRuntimeEntry> counter = new AgentShopPurchaseSequence<>(null, agent,
                AgentInventoryGatewayRuntime.inventory(), null, List.of(), new ArrayList<>(), null);
        AgentShopService.readinessCureStep(counter, shop, agent, standard,
                AgentSupplyRecoveryPolicy.minimumWalletReserve(agent));
        return Math.max(0, AgentSupplyReadiness.carriedCures(agent) - before);
    }

    /**
     * The counter's steps in a readiness trip's order, for {@code activity} at {@code shop}, never spending into
     * {@code minimumMesoReserve}; a new autopot choice when it bought potions (as a visit's end does).
     */
    static Provisioned provision(Character agent, Shop shop, AgentSupplyReadiness.Activity activity,
                                 int minimumMesoReserve, InventoryGateway inventory, WeaponType weapon) {
        int[] potionsBefore = AgentPotionService.countPotions(agent);
        int ammoBefore = AgentCombatAmmoCounter.countCarried(agent, weapon);
        int mesosBefore = agent.getMeso();
        AgentShopPurchaseSequence<AgentRuntimeEntry> counter = new AgentShopPurchaseSequence<>(null, agent,
                inventory, null, List.of(), new ArrayList<>(), null);
        int[] bought = {0, 0};
        counter = AgentShopService.buyReadinessFloor(counter, shop, activity, minimumMesoReserve, bought, false);
        AgentShopService.buyReadinessAmmo(agent, shop, weapon, inventory, AgentSupplyReadiness.ammoTarget(activity),
                minimumMesoReserve);
        AgentShopService.buyReadinessFloor(counter, shop, activity, minimumMesoReserve, bought, true);
        AgentShopService.buyReadinessAmmo(agent, shop, weapon, inventory,
                AgentShopService.readinessAmmoBuyTarget(activity), minimumMesoReserve);
        int[] potionsAfter = AgentPotionService.countPotions(agent);
        int ammoAfter = AgentCombatAmmoCounter.countCarried(agent, weapon);
        Provisioned provisioned = new Provisioned(Math.max(0, potionsAfter[0] - potionsBefore[0]),
                Math.max(0, potionsAfter[1] - potionsBefore[1]), (int) Math.max(0L, (long) ammoAfter - ammoBefore),
                mesosBefore, agent.getMeso());
        if (provisioned.hpPotions() > 0 || provisioned.mpPotions() > 0) {
            AgentPotionService.setupAutopotForBot(agent);
        }
        return provisioned;
    }
}
