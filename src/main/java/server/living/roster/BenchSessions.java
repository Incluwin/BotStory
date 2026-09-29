package server.living.roster;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.agents.runtime.AgentInteractionRuntime;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.EnumSet;
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
        roster = harvest(slice, agent, roster);
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
            harvest(slice, agent, roster);
        }
    }

    /**
     * Moves what the slice picked up since the last look into the haul: the meso it picked up (never more than its purse
     * holds) and each item stack it picked up and still holds. Coin its owner gave it, potions it bought and gear it
     * wears were never picked up, so they stay its own. A trainee keeps everything: only EXP is the point.
     */
    public RosterEntry harvest(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry entry) {
        if (slice.duty() == Assignment.TRAIN) {
            return entry;
        }
        long haulMeso = entry.haulMeso();
        long gainedMeso = slice.loot.takeMeso(agent.getMeso());
        if (gainedMeso > 0) {
            agent.gainMeso((int) -gainedMeso, false);
            haulMeso += gainedMeso;
            world.bench().reportHaul(entry.characterId(), entry.personaId(), slice.mapId(), gainedMeso, 0, 0);
        }
        int gainedStacks = 0;
        for (Map.Entry<Integer, Integer> taken : slice.loot.takeItems(itemId -> held(agent, itemId)).entrySet()) {
            int itemId = taken.getKey();
            if (world.bench().haulStacks(entry) >= world.config().HAUL_MAX_STACKS && world.bench().haulCount(entry, itemId) == 0) {
                continue; // bag is full; the companion keeps it
            }
            InventoryManipulator.removeById(agent.getClient(), ItemConstants.getInventoryType(itemId), itemId, taken.getValue(),
                    false, false);
            world.store().addHaul(entry.characterId(), entry.personaId(), itemId, taken.getValue());
            world.bench().reportHaul(entry.characterId(), entry.personaId(), slice.mapId(), 0L, itemId, taken.getValue());
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
    private static int held(Character agent, int itemId) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        Inventory bag = HARVESTED.contains(type) ? agent.getInventory(type) : null;
        return bag == null ? 0 : bag.countById(itemId);
    }
}
