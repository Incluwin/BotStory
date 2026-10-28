package server.living.population.needs;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ScrollTransactionService;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.agents.capabilities.supplies.AgentPotionService;
import server.economy.EconomyOperationKind;
import server.economy.EconomyTransactionCoordinator;
import server.living.population.Population;
import tools.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The needs board's hands in the live game: what a player carries that a request takes (tradeable, an equip base or
 * better), and the move itself as one NEED_FULFILLED economy transaction - the item out of the player's inventory and
 * into the citizen's (or its escrow, inventoryitems type 14, when it is away), the offer out of its purse (its stored
 * row when away) and into the player's, and the request's row, all in one commit. Then the citizen puts it to use.
 */
final class LiveNeedHands implements NeedLedger.Hands {
    private static final Logger log = LoggerFactory.getLogger(LiveNeedHands.class);
    private static final short UNTRADEABLE = ItemConstants.LOCK | ItemConstants.UNTRADEABLE;

    private final Population population;
    private final NeedStore store;
    private final NeedLedger.Items items;

    LiveNeedHands(Population population, NeedStore store, NeedLedger.Items items) {
        this.population = population;
        this.store = store;
        this.items = items;
    }

    @Override
    public int carried(Character player, Need need) {
        return takeable(player, need).stream().mapToInt(Item::getQuantity).sum();
    }

    /** The player's items the request takes: its item, tradeable, and for an equip base or better. */
    private List<Item> takeable(Character player, Need need) {
        List<Item> out = new ArrayList<>();
        if (!items.tradeable(need.itemId())) {
            return out;
        }
        InventoryType type = ItemConstants.getInventoryType(need.itemId());
        for (Item item : player.getInventory(type).list()) {
            if (item.getItemId() != need.itemId() || (item.getFlag() & UNTRADEABLE) != 0 || item.getQuantity() <= 0) {
                continue;
            }
            if (item instanceof Equip equip && !items.baseOrBetter(need.itemId(), NeedQuality.stats(equip))) {
                continue;
            }
            out.add(item);
        }
        return out;
    }

    @Override
    public long purse(int citizenId, Character citizen) {
        return citizen != null ? citizen.getMeso() : population.world().store().characterMeso(citizenId);
    }

    @Override
    public String transfer(Character player, Character citizen, Need need, Need after, int count, long pay) {
        String name = population.citizen(need.citizenId()) == null ? "They" : population.citizen(need.citizenId()).name();
        if (pay > Integer.MAX_VALUE || !player.canHoldMeso((int) pay)) {
            return "You can't hold that many mesos.";
        }
        List<Pair<Item, InventoryType>> escrow = citizen != null ? List.of() : store.escrow(need.citizenId());
        if (escrow == null) {
            return name + " cannot be reached right now.";
        }
        AtomicReference<String> refused = new AtomicReference<>();
        String summary = player.getName() + " hands citizen " + need.citizenId() + " " + count + " x " + need.itemId()
                + " for need " + need.id() + (citizen == null ? " (escrow)" : "");
        try {
            EconomyTransactionCoordinator.execute(player, citizen, EconomyOperationKind.NEED_FULFILLED, summary, context -> {
                // again under both purses' locks: a trade, a shop or a drop meanwhile came first
                List<Item> moving = moving(player, need, count);
                if (moving == null) {
                    refused.set("You don't have that any more.");
                    return;
                }
                if (citizen != null && (citizen.getMeso() < pay || !room(citizen, need, count))) {
                    refused.set(citizen.getMeso() < pay ? name + " can't pay that right now." : name + " has no room for that.");
                    return;
                }
                InventoryType type = ItemConstants.getInventoryType(need.itemId());
                List<Pair<Item, InventoryType>> kept = new ArrayList<>(escrow);
                for (Item item : moving) {
                    Item copy = item.copy();
                    copy.setPosition((short) 0);
                    InventoryManipulator.removeFromSlot(player.getClient(), type, item.getPosition(), copy.getQuantity(), false);
                    if (citizen != null) {
                        InventoryManipulator.addFromDrop(citizen.getClient(), copy, false);
                    } else {
                        kept.add(new Pair<>(copy, type));
                    }
                }
                player.gainMeso((int) pay, true);
                if (citizen != null) {
                    citizen.gainMeso((int) -pay, false);
                    context.enlist(con -> store.update(con, after), () -> { });
                } else {
                    context.enlist(con -> {
                        store.debit(con, need.citizenId(), pay);
                        store.saveEscrow(con, need.citizenId(), kept);
                        store.update(con, after);
                    }, () -> { });
                }
            });
        } catch (RuntimeException failure) {
            log.warn("hand-over of need {} from {} rolled back", need.id(), player.getName(), failure);
            return name + " cannot be reached right now.";
        }
        return refused.get();
    }

    /** The items (split from a stack) making up {@code count} units, or null when the player carries fewer now. */
    private List<Item> moving(Character player, Need need, int count) {
        List<Item> out = new ArrayList<>();
        int left = count;
        for (Item item : takeable(player, need)) {
            if (left <= 0) {
                break;
            }
            short take = (short) Math.min(left, item.getQuantity());
            Item part = item.copy();
            part.setPosition(item.getPosition());
            part.setQuantity(take);
            out.add(part);
            left -= take;
        }
        return left > 0 ? null : out;
    }

    private static boolean room(Character citizen, Need need, int count) {
        return InventoryManipulator.checkSpace(citizen.getClient(), need.itemId(), count, "");
    }

    @Override
    public List<Integer> takeEscrow(Character citizen, List<Need> escrowed) {
        List<Pair<Item, InventoryType>> waiting = store.escrow(citizen.getId());
        if (waiting == null) {
            return null;
        }
        for (Pair<Item, InventoryType> entry : waiting) {
            Item item = entry.getLeft();
            if (!InventoryManipulator.checkSpace(citizen.getClient(), item.getItemId(), item.getQuantity(), "")) {
                log.info("citizen {} has no room for its escrowed {} this load", citizen.getName(), item.getItemId());
                return null; // all or nothing: it all waits for the next load
            }
        }
        List<Integer> taken = new ArrayList<>();
        try {
            EconomyTransactionCoordinator.execute(citizen, null, EconomyOperationKind.NEED_FULFILLED,
                    "citizen " + citizen.getId() + " takes " + waiting.size() + " escrowed items", context -> {
                        for (Pair<Item, InventoryType> entry : waiting) {
                            InventoryManipulator.addFromDrop(citizen.getClient(), entry.getLeft().copy(), false);
                            taken.add(entry.getLeft().getItemId());
                        }
                        long now = System.currentTimeMillis();
                        context.enlist(con -> {
                            store.saveEscrow(con, citizen.getId(), List.of());
                            for (Need need : escrowed) {
                                store.update(con, need.closed(Need.State.MET, now));
                            }
                        }, () -> { });
                    });
        } catch (RuntimeException failure) {
            log.warn("citizen {} could not take its escrow this load", citizen.getName(), failure);
            return null;
        }
        return taken;
    }

    @Override
    public Boolean use(Character citizen, Need need) {
        try {
            return switch (need.kind()) {
                case SCROLL -> scroll(citizen, need);
                case GEAR -> {
                    short position = NeedLedger.position(need.detail());
                    if (position != 0 && AgentEquipmentService.equipInSlot(citizen, need.itemId(), position)) {
                        citizen.saveCharToDB();
                    }
                    yield null;
                }
                case SUPPLY -> {
                    AgentPotionService.setupAutopotForBot(citizen);
                    yield null;
                }
                case PARTY, MENTOR -> null;
            };
        } catch (RuntimeException failure) {
            log.warn("citizen {} could not use its {}", citizen.getName(), need.itemId(), failure);
            return null;
        }
    }

    /** Scrolls the piece the request was for (its own SCROLL_APPLY transaction); true when it passed, null when it could not. */
    private static Boolean scroll(Character citizen, Need need) {
        Item scroll = citizen.getInventory(InventoryType.USE).findById(need.itemId());
        int pieceId = NeedLedger.parseInt(need.detail());
        Equip piece = null;
        for (Equip equip : NeedDerivation.worn(citizen)) {
            if (equip.getItemId() == pieceId && equip.getUpgradeSlots() > 0) {
                piece = equip;
            }
        }
        if (scroll == null || piece == null || !citizen.getClient().tryacquireClient()) {
            return null;
        }
        Equip target = piece;
        AtomicReference<ScrollTransactionService.Result> applied = new AtomicReference<>();
        try {
            EconomyTransactionCoordinator.execute(citizen, null, EconomyOperationKind.SCROLL_APPLY,
                    "scroll=" + need.itemId() + " equipment=" + pieceId + " need=" + need.id(), context -> {
                        // a refused scroll changes nothing (no throw: a rollback would disconnect the citizen)
                        applied.set(ScrollTransactionService.apply(citizen.getClient(), scroll.getPosition(),
                                target.getPosition(), (byte) 0));
                    });
        } catch (RuntimeException failure) {
            log.warn("citizen {} could not scroll its {}", citizen.getName(), pieceId, failure);
            return null;
        } finally {
            citizen.getClient().releaseClient();
        }
        ScrollTransactionService.Result result = applied.get();
        return result == null || !result.applied() ? null : "SUCCESS".equals(result.outcome());
    }
}
