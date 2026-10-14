package server.agents.capabilities.supplies;

import client.inventory.Item;
import server.StatEffect;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;

import java.util.Collection;
import java.util.function.Function;

public final class AgentPotionInventoryPolicy {
    private AgentPotionInventoryPolicy() {
    }

    public static int[] countRecoveryPotions(
            Collection<Item> items, Function<Integer, StatEffect> effectLookup) {
        int hp = 0;
        int mp = 0;
        for (Item item : items) {
            if (item.getQuantity() <= 0) {
                continue;
            }
            StatEffect effect = effectLookup.apply(item.getItemId());
            if (!AgentUseItemClassificationPolicy.isRecoveryPotion(effect)) {
                continue;
            }
            int quantity = item.getQuantity();
            if (effect.getHp() > 0 || effect.getHpRate() > 0) {
                hp += quantity;
            }
            if (effect.getMp() > 0 || effect.getMpRate() > 0) {
                mp += quantity;
            }
        }
        return new int[]{hp, mp};
    }

    /**
     * As {@link #countRecoveryPotions(Collection, Function)}, counting a potion toward HP (or MP) only when one of it
     * restores at least {@code hpFloorBps} of {@code maxHp} ({@code mpFloorBps} of {@code maxMp}): a boss standard's
     * potion floor (AgentSupplyReadiness.potionFloor). A floor of 0 counts every potion of the kind.
     */
    public static int[] countRecoveryPotions(
            Collection<Item> items, Function<Integer, StatEffect> effectLookup,
            int maxHp, int maxMp, int hpFloorBps, int mpFloorBps) {
        int hp = 0;
        int mp = 0;
        for (Item item : items) {
            if (item.getQuantity() <= 0) {
                continue;
            }
            StatEffect effect = effectLookup.apply(item.getItemId());
            if (!AgentUseItemClassificationPolicy.isRecoveryPotion(effect)) {
                continue;
            }
            int quantity = item.getQuantity();
            AgentPotionRecoveryPolicy.Recovery forHp = AgentPotionRecoveryPolicy.recovery(effect, maxHp, maxMp, true);
            if (forHp != null && AgentPotionRecoveryPolicy.meetsFloor(forHp, maxHp, hpFloorBps)) {
                hp += quantity;
            }
            AgentPotionRecoveryPolicy.Recovery forMp = AgentPotionRecoveryPolicy.recovery(effect, maxHp, maxMp, false);
            if (forMp != null && AgentPotionRecoveryPolicy.meetsFloor(forMp, maxMp, mpFloorBps)) {
                mp += quantity;
            }
        }
        return new int[]{hp, mp};
    }

    public static long recoveryCapacity(
            Collection<Item> items,
            Function<Integer, StatEffect> effectLookup,
            int maxHp,
            int maxMp,
            boolean forHp) {
        long capacity = 0L;
        for (Item item : items) {
            if (item.getQuantity() <= 0) {
                continue;
            }
            StatEffect effect = effectLookup.apply(item.getItemId());
            if (!AgentUseItemClassificationPolicy.isRecoveryPotion(effect)) {
                continue;
            }
            AgentPotionRecoveryPolicy.Recovery recovery =
                    AgentPotionRecoveryPolicy.recovery(effect, maxHp, maxMp, forHp);
            if (recovery == null) {
                continue;
            }
            long contribution = (long) recovery.primary() * item.getQuantity();
            capacity = contribution >= Long.MAX_VALUE - capacity
                    ? Long.MAX_VALUE
                    : capacity + contribution;
        }
        return capacity;
    }
}
