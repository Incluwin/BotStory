package server.living.population;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.knowledge.Decisions;
import server.knowledge.GearPicks;
import server.knowledge.ItemFact;
import server.knowledge.WorldKnowledge;
import server.living.config.Content.Loadout;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Level-fitting gear for a generated citizen (docs/SERVER_KNOWLEDGE_PLAN.md 8.4): careers.yaml's loadout bands end at
 * lv30-40 gear, so a citizen past them wears what a player of its level and job would, from this server's obtainable gear.
 * The authored band loadout stays its floor: a pick is never a downgrade, and the replaced piece goes unless the loadout
 * has it. Not for Residents, hires or companions.
 */
final class CitizenGear {
    private static final int MIN_LEVEL = 10;
    private static final int LAST_BRANCH = 5;
    /** This share of slots keeps a worn piece still within {@link #KEEP_LEVELS} of the band (players skip upgrades too). */
    private static final int KEEP_PERCENT = 20;
    private static final int KEEP_LEVELS = 20;
    /** This share of citizens without a cape stays without one. */
    private static final int NO_CAPE_PERCENT = 25;
    private static final int PERCENT = 100;
    private static final String CAPE = "cape";
    private static final String TOP = "top";
    private static final String PANTS = "pants";
    private static final String SHIELD = "shield";
    private static final String WEAPON = "weapon";
    /** Slot name -> equipped position; weapon and shield only change when one is worn. */
    private static final List<Slot> SLOTS = List.of(new Slot("cap", -1), new Slot(TOP, -5), new Slot(PANTS, -6),
            new Slot("shoes", -7), new Slot("glove", -8), new Slot(CAPE, -9), new Slot(SHIELD, -10), new Slot(WEAPON, -11));

    private record Slot(String name, int position) { }

    private CitizenGear() {
    }

    /**
     * Decision D4 (the needs board): whether the citizen holds a pick back as a want instead of being handed it now
     * ({@code NeedLedger.gearWant}). A pick it owns already is worn whatever this says.
     */
    interface Wants {
        Wants NONE = (agent, slot, pick) -> false;

        boolean holdBack(Character agent, String slot, int pick);
    }

    /** Upgrades each worn slot to a better-levelled pick; true when it changed anything (the character is then saved). */
    static boolean upgrade(Character agent, Job job, Loadout loadout) {
        return upgrade(agent, job, loadout, Wants.NONE);
    }

    /** As {@link #upgrade(Character, Job, Loadout)}, with picks {@code wants} holds back left unworn (a want on the board). */
    static boolean upgrade(Character agent, Job job, Loadout loadout, Wants wants) {
        if (!Decisions.citizenGear() || agent.getLevel() < MIN_LEVEL || job == Job.BEGINNER) {
            return false;
        }
        WorldKnowledge k = Decisions.active();
        int[] stats = {agent.getStr(), agent.getDex(), agent.getInt(), agent.getLuk()};
        Item top = agent.getInventory(InventoryType.EQUIPPED).getItem((short) -5);
        boolean overall = top != null && ItemConstants.isOverall(top.getItemId());
        boolean changed = false;
        for (Slot slot : SLOTS) {
            short position = (short) slot.position();
            Item worn = agent.getInventory(InventoryType.EQUIPPED).getItem(position);
            if (PANTS.equals(slot.name()) && overall
                    || worn == null && (WEAPON.equals(slot.name()) || SHIELD.equals(slot.name()))) {
                continue; // the overall covers the legs; a weapon keeps its type (mastery, SP build, ammo), no shield stays none
            }
            String category = TOP.equals(slot.name()) ? overall ? "longcoat" : "coat" : slot.name();
            int wornId = worn == null ? 0 : worn.getItemId();
            int pick = upgradeFor(k, category, wornId, agent.getLevel(), job, agent.getGender(), stats,
                    agent.getId() * 31L + slot.name().hashCode());
            if (pick == 0) {
                continue;
            }
            boolean added = agent.getInventory(InventoryType.EQUIP).countById(pick) == 0; // an owned copy is worn, not a new one
            if (added && wants.holdBack(agent, slot.name(), pick)) {
                continue; // it asks for this one on the board instead (until its deadline)
            }
            if (added && !InventoryManipulator.addById(agent.getClient(), pick, (short) 1)) {
                continue;
            }
            if (AgentEquipmentService.equipInSlot(agent, pick, position)) {
                changed = true;
                if (wornId != 0 && (loadout == null || !loadout.equipIds().contains(wornId)
                        || config.YamlConfig.config.economy.CITIZEN_DISPLACED_GEAR_REMOVED)) {
                    // shaping re-adds the authored pieces anyway; a loadout piece kept in the bag was free stock its
                    // stall could sell (economy.CITIZEN_DISPLACED_GEAR_REMOVED), and wearMissingEquips only puts one
                    // back on when it beats what is worn
                    removeOne(agent, wornId);
                }
            } else if (added) {
                removeOne(agent, pick); // it could not wear it: no junk piles up
            }
        }
        if (changed) {
            agent.saveCharToDB();
        }
        return changed;
    }

    /**
     * The equip to wear instead of {@code wornId} (0: nothing worn) as {@code category} (0: keep it): a pick at the
     * citizen's level band with a strictly higher reqLevel, never a downgrade; a weapon keeps the worn one's type. Stable
     * per seed, some slots keep a piece still near the band and some citizens wear no cape, so a crowd is not one kit.
     */
    static int upgradeFor(WorldKnowledge k, String category, int wornId, int level, Job job, int gender, int[] stats, long seed) {
        int branch = job.getId() / 100 % 10;
        if (k == null || level < MIN_LEVEL || job == Job.BEGINNER || branch < 1 || branch > LAST_BRANCH) {
            return 0;
        }
        int band = level / 10 * 10;
        int wornLevel = wornId == 0 ? -1 : k.item(wornId).map(ItemFact::reqLevel).orElse(0);
        int roll = new SplittableRandom(seed + 1).nextInt(PERCENT); // not GearPicks' own draw from the seed
        if (wornId != 0 && wornLevel > band - KEEP_LEVELS && roll < KEEP_PERCENT
                || wornId == 0 && CAPE.equals(category) && roll < NO_CAPE_PERCENT) {
            return 0;
        }
        int pick = GearPicks.pick(k, category, band, 1 << (branch - 1), gender,
                WEAPON.equals(category) ? wornId / 10_000 : 0, stats, seed);
        return pick != 0 && k.item(pick).map(ItemFact::reqLevel).orElse(0) > wornLevel ? pick : 0;
    }

    private static void removeOne(Character agent, int itemId) {
        for (Item item : agent.getInventory(InventoryType.EQUIP).list()) {
            if (item.getItemId() == itemId) {
                InventoryManipulator.removeFromSlot(agent.getClient(), InventoryType.EQUIP, item.getPosition(), (short) 1, false);
                return;
            }
        }
    }
}
