package server.living.population.needs;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.processor.npc.DueyProcessor;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.economy.EconomyOperationKind;
import server.economy.EconomyTransactionCoordinator;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;

/**
 * Where a player's buy order moves items and meso, each step one NEED_FULFILLED economy transaction with the order's row:
 * the whole price out of the player's purse at the post; a lot out of the citizen's bag into a Duey package to the player
 * and its price, out of what the order holds, into the citizen's purse; or what is left back to the player.
 */
class BuyOrderHands {
    private static final Logger log = LoggerFactory.getLogger(BuyOrderHands.class);
    private static final short UNTRADEABLE = ItemConstants.LOCK | ItemConstants.UNTRADEABLE;
    private static final String AGAIN = "That didn't work. Try again in a moment.";
    /** What fits in one Duey package. */
    static final int LOT_MAX = 100;

    private final NeedStore store;
    private final IntPredicate tradeable;

    BuyOrderHands(NeedStore store) {
        this(store, itemId -> !ItemInformationProvider.getInstance().isUntradeableRestricted(itemId)
                && !ItemInformationProvider.getInstance().isQuestItem(itemId));
    }

    BuyOrderHands(NeedStore store, IntPredicate tradeable) {
        this.store = store;
        this.tradeable = tradeable;
    }

    /** The part of one stack in the citizen's bag it can sell now, {@code most} units at most, or null. */
    Item lot(Character agent, int itemId, int most) {
        for (Item item : agent.getInventory(ItemConstants.getInventoryType(itemId)).list()) {
            if (item.getItemId() == itemId && item.getQuantity() > 0 && (item.getFlag() & UNTRADEABLE) == 0
                    && item.getExpiration() == -1L && most > 0 && tradeable.test(itemId)) {
                Item lot = item.copy();
                lot.setPosition(item.getPosition());
                lot.setQuantity((short) Math.min(LOT_MAX, Math.min(most, item.getQuantity())));
                return lot;
            }
        }
        return null;
    }

    /** The whole price goes from the player into the order ({@code id[0]}: its row). Null when done, else why not. */
    String post(Character player, Need order, int[] id) {
        long total = order.pay(order.quantity());
        AtomicReference<String> refused = new AtomicReference<>();
        try {
            EconomyTransactionCoordinator.execute(player, null, EconomyOperationKind.NEED_FULFILLED, player.getName()
                    + " posts a buy order for " + order.quantity() + " x " + order.itemId() + " mesos=" + total, context -> {
                if (player.getMeso() < total) {
                    refused.set("You need " + total + " mesos for that. The board holds it.");
                    return;
                }
                player.gainMeso((int) -total, true);
                context.enlist(con -> id[0] = store.insertPosted(con, order), () -> { });
            });
        } catch (RuntimeException failure) {
            log.warn("buy order of {} rolled back", player.getName(), failure);
            return AGAIN;
        }
        return refused.get();
    }

    /** What the order still holds goes back into the player's purse. Null when done, else why not. */
    String giveBack(Character player, Need order, Need closed) {
        long left = order.pay(order.remaining());
        if (!player.canHoldMeso((int) left)) {
            return "You can't hold that many mesos.";
        }
        try {
            EconomyTransactionCoordinator.execute(player, null, EconomyOperationKind.NEED_FULFILLED, player.getName()
                    + " takes down buy order " + order.id() + " mesos=" + left, context -> {
                player.gainMeso((int) left, true);
                context.enlist(con -> store.updatePosted(con, order, closed), () -> { });
            });
        } catch (RuntimeException failure) {
            log.warn("buy order {} could not come down", order.id(), failure);
            return AGAIN;
        }
        return null;
    }

    /** An order past its deadline: what is left goes back by Duey. */
    boolean mailBack(Need order, Need closed, String note) {
        return store.inTransaction(con -> {
            store.updatePosted(con, order, closed);
            store.mail(con, null, (int) order.pay(order.remaining()), note, "Town Board", order.posterId());
        });
    }

    /** {@code count} units of one stack go from the citizen into a package to the player; true when done. */
    boolean sell(Character agent, Need order, Need after, int count, String sender, String note) {
        long pay = order.pay(count);
        if (!agent.canHoldMeso((int) pay)) {
            return false;
        }
        AtomicBoolean done = new AtomicBoolean();
        try {
            EconomyTransactionCoordinator.execute(agent, null, EconomyOperationKind.NEED_FULFILLED, "citizen "
                    + agent.getId() + " sells " + count + " x " + order.itemId() + " into buy order " + order.id()
                    + " of " + order.posterId() + " mesos=" + pay, context -> {
                Item lot = lot(agent, order.itemId(), count); // again under its lock
                if (lot == null || lot.getQuantity() != count) {
                    return;
                }
                InventoryType type = ItemConstants.getInventoryType(order.itemId());
                InventoryManipulator.removeFromSlot(agent.getClient(), type, lot.getPosition(), lot.getQuantity(), false);
                agent.gainMeso((int) pay, false);
                Item copy = lot.copy();
                copy.setPosition((short) 0);
                context.enlist(con -> {
                    store.updatePosted(con, order, after);
                    store.mail(con, copy, 0, note, sender, order.posterId());
                }, () -> { });
                done.set(true);
            });
        } catch (RuntimeException failure) {
            log.warn("sale into buy order {} rolled back", order.id(), failure);
            return false;
        }
        return done.get();
    }

    void notifyDuey(Character player) {
        DueyProcessor.dueyNotify(player);
    }
}
