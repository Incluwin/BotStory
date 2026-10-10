package server.agents.capabilities.inventory;

import client.inventory.Item;
import server.agents.capabilities.contracts.AgentInventoryReservation;
import server.agents.runtime.state.AgentCapabilityStateKey;

import java.util.Collections;
import java.util.Comparator;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One authoritative reservation ledger shared by loot, equipment, quest, shop, and trade policy. A reservation holds
 * every copy of its item id, unless its capability published item reservations
 * ({@link #replaceCapabilityItemReservations}): those hold only the exact items given.
 */
public final class AgentInventoryReservationLedger {
    public static final AgentCapabilityStateKey<AgentInventoryReservationLedger> STATE_KEY =
            new AgentCapabilityStateKey<>("inventory.reservations", AgentInventoryReservationLedger.class,
                    AgentInventoryReservationLedger::new);

    private final Map<String, AgentInventoryReservation> reservations = new LinkedHashMap<>();
    /** Per capability with item reservations: the exact items (by identity) its reservations hold. */
    private final Map<String, Set<Item>> heldItemsByCapability = new LinkedHashMap<>();

    public synchronized void reserve(AgentInventoryReservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("Inventory reservation is required");
        }
        reservations.put(reservation.reservationId(), reservation);
    }

    public synchronized boolean release(String reservationId) {
        return reservationId != null && reservations.remove(reservationId) != null;
    }

    public synchronized int releaseCapability(String capability) {
        if (capability == null || capability.isBlank()) {
            return 0;
        }
        int before = reservations.size();
        reservations.values().removeIf(reservation -> capability.equals(reservation.capability()));
        heldItemsByCapability.remove(capability);
        return before - reservations.size();
    }

    public synchronized void replaceCapabilityReservations(
            String capability,
            Collection<AgentInventoryReservation> replacements) {
        if (capability == null || capability.isBlank() || replacements == null
                || replacements.stream().anyMatch(reservation -> reservation == null
                || !capability.equals(reservation.capability()))) {
            throw new IllegalArgumentException("Replacement reservations must share one capability");
        }
        releaseCapability(capability);
        replacements.forEach(reservation -> reservations.put(reservation.reservationId(), reservation));
    }

    /**
     * Replaces one capability's reservations with item reservations: they hold only {@code heldItems} (each exact
     * item, by identity), not the other copies of their ids. The id view ({@link #reservedQuantity}) still counts
     * them.
     */
    public synchronized void replaceCapabilityItemReservations(
            String capability,
            Collection<AgentInventoryReservation> replacements,
            Collection<? extends Item> heldItems) {
        if (heldItems == null || heldItems.stream().anyMatch(item -> item == null)) {
            throw new IllegalArgumentException("Held items are required");
        }
        replaceCapabilityReservations(capability, replacements);
        Set<Item> held = Collections.newSetFromMap(new IdentityHashMap<>());
        held.addAll(heldItems);
        heldItemsByCapability.put(capability, held);
    }

    /**
     * True when a live reservation holds this exact item: an id reservation holds every copy of its id, an item
     * reservation only the items its capability holds.
     */
    public synchronized boolean reservesItem(Item item, long nowMs) {
        if (item == null) {
            return false;
        }
        expire(nowMs);
        for (AgentInventoryReservation reservation : reservations.values()) {
            if (reservation.itemId() != item.getItemId()) {
                continue;
            }
            Set<Item> held = heldItemsByCapability.get(reservation.capability());
            if (held == null || held.contains(item)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int reservedQuantity(int itemId, long nowMs) {
        expire(nowMs);
        return reservations.values().stream()
                .filter(reservation -> reservation.itemId() == itemId)
                .mapToInt(AgentInventoryReservation::quantity)
                .sum();
    }

    public synchronized List<AgentInventoryReservation> reservations(long nowMs) {
        expire(nowMs);
        return reservations.values().stream()
                .sorted(Comparator.comparingInt(AgentInventoryReservation::priority).reversed()
                        .thenComparing(AgentInventoryReservation::reservationId))
                .toList();
    }

    public synchronized int expire(long nowMs) {
        int before = reservations.size();
        reservations.values().removeIf(reservation -> reservation.expiresAtMs() > 0
                && nowMs >= reservation.expiresAtMs());
        return before - reservations.size();
    }

    public synchronized void clear() {
        reservations.clear();
        heldItemsByCapability.clear();
    }
}
