package server.living.roster;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.agents.runtime.AgentInteractionRuntime;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.habits.OwnerHabitBook;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The field form of the bench (plan 11.4, docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench"): a benched or away companion on
 * a fighting duty (TRAIN, FARM_MESO, FARM_ITEM) is materialized by the {@link BenchDutyDispatcher} at its camp field (a
 * FieldCatalog field fitting its level when its camp is a town) as a self-directed grinder. The Agent simulation policy
 * runs it at background priority when no player shares the map and at full fidelity when someone walks in (the daycare
 * visit). What a farming slice picks up (kill loot and meso, never its own supplies: HarvestDiff) is harvested into
 * the haul each tick; while a companion is live the offline ledger only moves its clock, so nothing is credited twice.
 */
public final class BenchSessions implements BenchDutyDispatcher.Form {
    private static final Set<InventoryType> HARVESTED = EnumSet.of(InventoryType.EQUIP, InventoryType.USE,
            InventoryType.SETUP, InventoryType.ETC);

    private final LivingWorld world;
    /** What an NPC shop pays for {@code quantity} of an item (the stock price; a test gives its own). */
    java.util.function.IntBinaryOperator vendorPrice = (itemId, quantity) ->
            ItemInformationProvider.getInstance().getPrice(itemId, quantity);

    public BenchSessions(LivingWorld world) {
        this.world = world;
    }

    /** True for roster rows the bench model governs: benched, or away on delegated work (plan 11.2). */
    static boolean benchGoverned(RosterEntry entry) {
        return entry != null && (entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY);
    }

    @Override
    public CompanionBudget.Kind pool() {
        return CompanionBudget.Kind.BENCH_LIVE;
    }

    /**
     * Its field camp, while it has something to fight for: a trainee below its pace target, a farmer below its owner's
     * level, within the daily farming cap and short of what it was asked for. 0 otherwise, or when no field fits.
     */
    @Override
    public int mapFor(RosterEntry entry, Persona persona, int characterId) {
        int ownerLevel = world.store().characterLevel(entry.characterId());
        if (ownerLevel <= 0) {
            return 0;
        }
        int botLevel = world.store().characterLevel(characterId);
        int paceGap = persona.paceGap() >= 0 ? persona.paceGap() : world.config().PACE_GAP;
        boolean farming = entry.assignment() != Assignment.TRAIN;
        if (farming ? botLevel >= ownerLevel : botLevel >= ownerLevel - paceGap) {
            return 0;
        }
        if (farming && (!world.bench().withinDailyCap(entry, 0.0d)
                || entry.assignment() == Assignment.FARM_ITEM && entry.assignmentCount() > 0
                && world.bench().haulCount(entry, entry.assignmentItem()) >= entry.assignmentCount())) {
            return 0;
        }
        return BenchDutyDispatcher.fieldCamp(world, entry, persona); // never a town: towns have nothing to fight
    }

    @Override
    public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
        AgentInteractionRuntime.registerSelfDirectedAgent(agent);
        return true;
    }

    @Override
    public String check(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry roster, long nowMs) {
        if (agent.getMap() == null || agent.getMap().isTown()) {
            return "back in town"; // died and respawned, or wandered off the field
        }
        roster = harvest(slice, agent, roster, Sold.HOLD);
        int ownerLevel = world.store().characterLevel(slice.ownerId());
        Persona persona = world.content().persona(slice.personaId());
        int paceGap = persona != null && persona.paceGap() >= 0 ? persona.paceGap() : world.config().PACE_GAP;
        if (slice.duty() == Assignment.TRAIN) {
            if (ownerLevel > 0 && agent.getLevel() >= ownerLevel - paceGap) {
                return "reached pace target";
            }
        } else if (ownerLevel > 0 && agent.getLevel() >= ownerLevel) {
            return "would outlevel owner; back to the ledger";
        } else if (!world.bench().withinDailyCap(roster, 0.0d)) {
            return "daily farming cap reached";
        } else if (slice.duty() == Assignment.FARM_ITEM && roster.assignmentCount() > 0
                && world.bench().haulCount(roster, roster.assignmentItem()) >= roster.assignmentCount()) {
            return "collected what was asked";
        }
        return null;
    }

    @Override
    public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
        RosterEntry roster = agent == null ? null : world.store().rosterEntry(slice.ownerId(), slice.personaId());
        if (roster != null) {
            harvest(slice, agent, roster, BenchDutyDispatcher.RESUPPLIED.equals(reason) ? Sold.VENDOR : Sold.HAUL);
        }
    }

    /**
     * What a harvest does with the items its owner sells at shops (OwnerHabitBook.sells, F2.4): HOLD them in its bag
     * while it hunts, VENDOR them at its resupply (the slice ends at the shop: their NPC price goes into the haul), HAUL
     * them like the rest when the slice ends anywhere else.
     */
    enum Sold { HOLD, VENDOR, HAUL }

    /** {@link #harvest(BenchDutyDispatcher.Slice, Character, RosterEntry, Sold)} hauling everything (a GATHER visit's). */
    public RosterEntry harvest(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry entry) {
        return harvest(slice, agent, entry, Sold.HAUL);
    }

    /**
     * Moves what the slice picked up since the last look into the haul: the meso it picked up (never more than its purse
     * holds) and each item stack it picked up and still holds. Coin its owner gave it, potions it bought and gear it
     * wears were never picked up, so they stay its own. A trainee keeps everything: only EXP is the point. What its
     * owner sells at shops goes as {@code sold} says.
     */
    RosterEntry harvest(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry entry, Sold sold) {
        if (slice.duty() == Assignment.TRAIN) {
            return entry;
        }
        long haulMeso = entry.haulMeso();
        long pickedMeso = slice.loot.takeMeso(agent.getMeso());
        // its contract's cut of that meso stays in its purse (LootTerms; all of it moves without authored terms)
        LootTerms terms = world.lootTerms();
        long gainedMeso = terms == null ? pickedMeso : terms.benchShare(entry.characterId(), entry.personaId(), pickedMeso);
        if (gainedMeso > 0) {
            try (var ledgerScope = MesoFlows.as(MesoAccount.HAUL)) { agent.gainMeso((int) -gainedMeso, false); }
            haulMeso += gainedMeso;
            world.bench().reportHaul(entry.characterId(), entry.personaId(), slice.mapId(), gainedMeso, 0, 0);
        }
        int gainedStacks = 0;
        OwnerHabitBook habits = world.ownerHabits();
        for (Map.Entry<Integer, Integer> taken : slice.loot.takeItems(itemId -> held(agent, itemId)).entrySet()) {
            int itemId = taken.getKey();
            if (sold != Sold.HAUL && habits != null && habits.sells(entry.characterId(), itemId)) {
                if (sold == Sold.HOLD) {
                    slice.loot.keep(itemId, taken.getValue()); // sold at its resupply, like its owner does
                    continue;
                }
                int vended = takeFromBag(agent, itemId, taken.getValue());
                long meso = vended <= 0 ? 0L : Math.max(0, vendorPrice.applyAsInt(itemId, vended));
                if (meso > 0) {
                    haulMeso += meso;
                    gainedMeso += meso;
                    world.bench().reportHaul(entry.characterId(), entry.personaId(), slice.mapId(), meso, 0, 0);
                    world.store().event(entry.characterId(), entry.personaId(), "bench.vendor", vended + " x " + itemId
                            + " for " + meso + " meso");
                }
                continue;
            }
            if (world.bench().haulStacks(entry) >= world.config().HAUL_MAX_STACKS && world.bench().haulCount(entry, itemId) == 0) {
                continue; // bag is full; the companion keeps it
            }
            int moved = takeFromBag(agent, itemId, taken.getValue());
            if (moved <= 0) {
                continue;
            }
            world.store().addHaul(entry.characterId(), entry.personaId(), itemId, moved);
            world.bench().reportHaul(entry.characterId(), entry.personaId(), slice.mapId(), 0L, itemId, moved);
            gainedStacks++;
        }
        if (gainedMeso <= 0 && gainedStacks == 0) {
            return entry;
        }
        // the ledger owns the clock and the daily-cap seconds, even while the companion is live
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), entry.accruedExp(), haulMeso,
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(),
                entry.farmSecondsToday(), entry.farmDay());
        world.store().saveRosterProgress(updated);
        CompanionViews.invalidate(world, entry.characterId(), entry.personaId());
        world.store().event(entry.characterId(), entry.personaId(), "bench.harvest", "+" + Math.max(0, gainedMeso) + " meso, " + gainedStacks + " stacks");
        return updated;
    }

    /** How many of the item the companion holds in its bags (not what it wears, and nothing from the cash tab). */
    /**
     * Takes up to {@code count} of the item out of its bag, and how many it took. An equip goes copy by copy, never one
     * with its own name as the owner tag (a Resident's own quest gear, ResidentKit), even when loot shares its id.
     */
    static int takeFromBag(Character agent, int itemId, int count) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        List<Item> copies = type == InventoryType.EQUIP ? agent.getInventory(type).listById(itemId) : List.of();
        if (copies.stream().noneMatch(item -> ownedBy(agent, item))) {
            InventoryManipulator.removeById(agent.getClient(), type, itemId, count, false, false);
            return count;
        }
        int taken = 0;
        for (Item item : copies) {
            if (taken < count && !ownedBy(agent, item)) {
                InventoryManipulator.removeFromSlot(agent.getClient(), type, item.getPosition(), item.getQuantity(), false);
                taken++;
            }
        }
        return taken;
    }

    private static boolean ownedBy(Character agent, Item item) {
        return agent.getName() != null && agent.getName().equals(item.getOwner());
    }

    private static int held(Character agent, int itemId) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        Inventory bag = HARVESTED.contains(type) ? agent.getInventory(type) : null;
        return bag == null ? 0 : bag.countById(itemId);
    }
}
