package server.agents.capabilities.shop;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import server.Shop;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.capabilities.combat.AgentCombatAmmoCounter;
import server.agents.capabilities.inventory.AgentInventoryItemPolicy;
import server.agents.capabilities.inventory.AgentInventoryReservationRuntime;
import server.agents.capabilities.inventory.AgentInventorySellTrashService;
import server.agents.capabilities.inventory.demand.AgentEtcSaleCandidate;
import server.agents.capabilities.inventory.demand.AgentQuestItemDispositionRuntime;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.capabilities.supplies.AgentSupplyRecoveryPolicy;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * reserve, through the direct shop transaction gateway. Before it buys, it sells the loot a readiness trip's counter
 * sells first ({@link #sellLoot}). It says nothing: its host says what it still lacks. It leaves out what a walk-in
 * visit also does (the reserve-bar passes, the old Victoria ammo top-up, skill and buff consumables). Nothing when it
 * lacks nothing.
 */
public final class AgentShopProvisioning {
    private static final Logger log = LoggerFactory.getLogger(AgentShopProvisioning.class);

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
        InventoryGateway inventory = AgentInventoryGatewayRuntime.inventory();
        try {
            sellLoot(AgentRuntimeRegistry.findByAgentCharacterId(agent.getId()), agent, shop, inventory);
        } catch (RuntimeException failure) {
            log.warn("Agent '{}' sold no loot at NPC {} before provisioning", agent.getName(), npcId, failure);
        }
        Provisioned bought = provision(agent, shop, activity, AgentSupplyRecoveryPolicy.minimumWalletReserve(agent),
                inventory, AgentAttackExecutionProvider.getEquippedWeaponType(agent));
        return new Provisioned(bought.hpPotions(), bought.mpPotions(), bought.projectiles(), mesos,
                bought.mesosAfter());
    }

    /**
     * 2026-10-19 (Zakum, everything on by default): the loot a readiness trip's counter sells before it buys
     * (AgentShopService's sell-trash sequence: the trash equips, then the ETC stacks no planned quest wants), sold at
     * {@code shop} at once, nothing reserved or held for an offer. Without it an ally with a thin purse and a full bag
     * bought nothing: Gunnar came to Zakum with 17,047 mesos, no HP potion and some 3 M mesos of loot, and died three
     * minutes into the arms; Orrin (34,936 mesos) ran dry fourteen minutes in. Returns the sales that went through; 0
     * with no runtime entry or no Equip or Etc tab. A failed sale never stops the purchase after it. On the thread that
     * holds the Agent.
     */
    static int sellLoot(AgentRuntimeEntry entry, Character agent, Shop shop, InventoryGateway inventory) {
        if (entry == null || agent == null || shop == null || agent.getInventory(InventoryType.EQUIP) == null
                || agent.getInventory(InventoryType.ETC) == null) {
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        int sold = 0;
        for (Item item : AgentInventorySellTrashService.collectSellTrashEquips(entry, agent, inventory)) {
            if (!AgentInventoryItemPolicy.hasItem(agent, item)
                    || !AgentInventoryReservationRuntime.mayConsume(entry, item, nowMs)) {
                continue;
            }
            AgentShopGatewayRuntime.shop().sell(agent, shop, InventoryType.EQUIP, item.getPosition(), (short) 1);
            if (!AgentInventoryItemPolicy.hasItem(agent, item)) {
                sold++;
            }
        }
        for (AgentEtcSaleCandidate candidate
                : AgentQuestItemDispositionRuntime.collectShopSaleCandidates(entry, agent, inventory, nowMs)) {
            Item item = candidate.item();
            int before = agent.getInventory(InventoryType.ETC).countById(item.getItemId());
            int reserved = AgentInventoryReservationRuntime.ledger(entry).reservedQuantity(item.getItemId(), nowMs);
            int quantity = Math.min(candidate.quantity(), Math.min(item.getQuantity(), Math.max(0, before - reserved)));
            if (quantity <= 0 || !AgentInventoryItemPolicy.hasItem(agent, item)) {
                continue;
            }
            AgentShopGatewayRuntime.shop().sell(agent, shop, InventoryType.ETC, item.getPosition(), (short) quantity);
            if (agent.getInventory(InventoryType.ETC).countById(item.getItemId()) < before) {
                sold++;
            }
        }
        return sold;
    }

    /**
     * 2026-10-15 (P18 run 6): the loaded Agent buys at NPC {@code npcId}'s shop the seal cures it lacks of
     * {@code activity}'s cure standard (AgentSupplyReadiness.cureStandard: "horntail_cure"), as a readiness trip's cure
     * step does there ({@link AgentShopService#readinessCureStep}), with no visit. Returns how many it bought; 0 when it
     * lacks none, the activity has no cure standard, or that NPC has no shop. On the thread that holds the Agent.
     * 2026-10-17: every kind of cure the activity has a standard for (AgentSupplyReadiness.CureKind).
     */
    public static int provisionCuresAt(Character agent, int npcId, AgentSupplyReadiness.Activity activity) {
        if (agent == null || npcId <= 0 || agent.getInventory(InventoryType.USE) == null
                || AgentSupplyReadiness.cureShortfall(agent, activity) <= 0) {
            return 0;
        }
        Shop shop = AgentShopGatewayRuntime.shop().findForNpc(npcId);
        if (shop == null) {
            return 0;
        }
        int before = carriedOfEveryKind(agent);
        AgentShopPurchaseSequence<AgentRuntimeEntry> counter = new AgentShopPurchaseSequence<>(null, agent,
                AgentInventoryGatewayRuntime.inventory(), null, List.of(), new ArrayList<>(), null);
        AgentShopService.readinessCureStep(counter, shop, agent, activity,
                AgentSupplyRecoveryPolicy.minimumWalletReserve(agent));
        return Math.max(0, carriedOfEveryKind(agent) - before);
    }

    /** The cures of every kind the Agent carries, an All Cure Potion (which counts toward each kind) once. */
    private static int carriedOfEveryKind(Character agent) {
        java.util.Set<Integer> cures = new java.util.HashSet<>();
        for (AgentSupplyReadiness.CureKind kind : AgentSupplyReadiness.CureKind.values()) {
            for (int itemId : kind.items()) {
                cures.add(itemId);
            }
        }
        int carried = 0;
        for (int itemId : cures) {
            carried += agent.getInventory(InventoryType.USE).countById(itemId);
        }
        return carried;
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
        // Room as a readiness trip makes it (2026-10-15, P19): the ally Gunnar came to every Showa run with 95 of 96 Use
        // slots full of looted arrows and bullets and bought nothing.
        counter = AgentShopService.makeUseRoomForReadiness(counter, shop, weapon, activity);
        int[] bought = {0, 0};
        counter = AgentShopService.buyReadinessFloor(counter, shop, activity, minimumMesoReserve, bought, false);
        AgentShopService.buyReadinessAmmo(agent, shop, weapon, inventory, AgentSupplyReadiness.ammoTarget(activity),
                minimumMesoReserve);
        AgentShopService.buyReadinessFloor(counter, shop, activity, minimumMesoReserve, bought, true);
        AgentShopService.buyReadinessAmmoHeadroom(agent, shop, weapon, inventory,
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
