package server.living.resident;

import client.Character;
import client.Job;
import client.Skill;
import client.Character.SkillEntry;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.inventory.EquipSlot;
import constants.inventory.ItemConstants;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.build.AgentCharacterShaper;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileService;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.agents.capabilities.equipment.AgentWeaponCompatibilityPolicy;
import server.living.config.Content.ItemStack;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns a freshly provisioned level-1 beginner into the authored Persona: levels one at a time
 * so HP/MP and AP grow through the normal formulas, job advancements at the levels the game
 * allows them (a persona authored as a third job below level 70 stops at its second job),
 * AP by job archetype, then loadout equipment and consumables. Idempotent per shaped version.
 */
public final class ResidentShaper {
    private static final Logger log = LoggerFactory.getLogger(ResidentShaper.class);
    /** Bump when the shaping recipe changes so existing personas are reshaped once. */
    static final int RECIPE_VERSION = 5;

    private ResidentShaper() {
    }

    /** A hired citizen is never reshaped: its level, job and gear are its own. */
    public static void shape(Character agent, Persona persona, Loadout loadout, int levelSeed) {
        if (LivingContent.isHire(persona.id())) {
            return;
        }
        shapeTo(agent, job(persona.job()), persona.level().pick(levelSeed), loadout);
    }

    /**
     * As {@link #shape}, at the level the character has (a GM kept it there: {@code !setlevel <name> keep}): its job
     * advances as far as that level allows, then its AP, skills and loadout. A Beginner it advances gets back the AP
     * its Beginner levels spent, and below its loadout weapon's level it wears the best weapon that level allows
     * ({@code catalog}: every loadout, see {@link #fallbackWeapons}). A hired citizen is never reshaped.
     */
    public static void shapeKeepingLevel(Character agent, Persona persona, Loadout loadout,
                                         Collection<Loadout> catalog) {
        if (LivingContent.isHire(persona.id())) {
            return;
        }
        shapeTo(agent, job(persona.job()), agent.getLevel(), loadout, fallbackWeapons(loadout, catalog));
    }

    /**
     * Levels the character one step at a time to {@code level}, advancing along the legal job chain
     * toward {@code target}, repairs skipped growth, spends AP and applies the loadout. Used for
     * Residents and for generated citizens (population).
     */
    public static void shapeTo(Character agent, Job target, int level, Loadout loadout) {
        shapeTo(agent, target, level, loadout, null);
    }

    /** {@code keptWeapons}: at a level a GM kept, the weapons it may fall back to; null for a level chosen here. */
    private static void shapeTo(Character agent, Job target, int level, Loadout loadout, Set<Integer> keptWeapons) {
        SHAPING.set(Boolean.TRUE);
        try {
            shapeUnhooked(agent, target, level, loadout, keptWeapons);
        } finally {
            SHAPING.remove();
        }
    }

    private static final ThreadLocal<Boolean> SHAPING = new ThreadLocal<>();

    /** True on the thread that is currently shaping a character (level-up/job hooks stay quiet). */
    public static boolean shaping() {
        return Boolean.TRUE.equals(SHAPING.get());
    }

    private static void shapeUnhooked(Character agent, Job target, int level, Loadout loadout,
                                      Set<Integer> keptWeapons) {
        boolean keptLevel = keptWeapons != null;
        advanceAndSpendAp(agent, target, level, loadout, keptLevel);
        int learned = AgentSpBuildProfileService.assignOffline(agent);
        if (learned > 0) {
            log.debug("shaped {} learned {} skill levels", agent.getName(), learned);
        }
        stockSkillConsumables(agent);
        if (loadout != null) {
            for (int equipId : loadout.equipIds()) {
                if (agent.getInventory(InventoryType.EQUIP).countById(equipId) == 0
                        && agent.getInventory(InventoryType.EQUIPPED).countById(equipId) == 0) {
                    InventoryManipulator.addById(agent.getClient(), equipId, (short) 1);
                }
            }
            for (ItemStack stack : loadout.consumables()) {
                int type = stack.itemId() / 1_000_000;
                InventoryType inventory = type == 2 ? InventoryType.USE : type == 3 ? InventoryType.SETUP
                        : type == 4 ? InventoryType.ETC : InventoryType.EQUIP;
                if (agent.getInventory(inventory).countById(stack.itemId()) < stack.count()) {
                    InventoryManipulator.addById(agent.getClient(), stack.itemId(),
                            (short) Math.min(stack.count() - agent.getInventory(inventory).countById(stack.itemId()), Short.MAX_VALUE));
                }
            }
            try {
                AgentEquipmentService.autoEquip(agent, agent, null, true);
            } catch (RuntimeException failure) {
                log.debug("autoequip for {} failed", agent.getName(), failure);
            }
            if (keptLevel) {
                try {
                    wearAllowedWeapon(agent, loadout, keptWeapons, new WzItemRules());
                } catch (RuntimeException failure) {
                    log.warn("kept-level weapon for {} failed", agent.getName(), failure);
                }
            }
        }
        agent.healHpMp();
        agent.saveCharToDB();
    }

    /**
     * The level, job and AP step of shaping: levels the character one step at a time to {@code level} along the legal
     * job chain toward {@code target}, repairs skipped growth and spends its AP by the loadout's shares. At a level a
     * GM kept ({@code keptLevel}) a Beginner advanced here first gets back the AP its Beginner levels spent, as the
     * first-job instructors' stat reset does (USE_AUTOASSIGN_STARTERS_AP put them on the Beginner curve, STR first).
     */
    static void advanceAndSpendAp(Character agent, Job target, int level, Loadout loadout, boolean keptLevel) {
        int targetLevel = Math.max(1, Math.min(level, 200));
        List<Job> chain = AgentCharacterShaper.advancementChain(target);
        boolean beginner = agent.isBeginnerJob();
        int guard = 0;
        while (guard++ < 250) {
            AgentCharacterShaper.advanceIfLegal(agent, chain);
            if (agent.getLevel() >= targetLevel) {
                break;
            }
            agent.levelUp(false);
        }
        AgentCharacterShaper.advanceIfLegal(agent, chain);
        if (keptLevel && beginner && !agent.isBeginnerJob()) {
            AgentCharacterShaper.resetToStatFloors(agent);
        }
        AgentCharacterShaper.repairGrowth(agent);
        AgentCharacterShaper.assignAp(agent, agent.getJob(), loadout == null ? Map.of() : loadout.stats());
    }

    /**
     * The weapons a kept level may fall back to: what any loadout gives as gear (its equips), this loadout's own outfit
     * weapons and the first-job starter kits. Other loadouts' outfit weapons are looks only (the townsfolk's are work
     * tools).
     */
    static Set<Integer> fallbackWeapons(Loadout loadout, Collection<Loadout> catalog) {
        Set<Integer> ids = new HashSet<>(ResidentWardrobe.handedOut(loadout == null ? List.of() : List.of(loadout)));
        for (Loadout other : catalog) {
            for (int equipId : other.equipIds()) {
                if (ItemConstants.isWeapon(equipId)) {
                    ids.add(equipId);
                }
            }
        }
        return ids;
    }

    /**
     * At a level a GM kept below its loadout weapon's own: wears the best weapon of {@code weapons} it can use (the
     * highest level requirement), one of the loadout weapon's type or, failing that, any it can fight with that shoots
     * nothing (a bow, crossbow, claw or gun needs the loadout's own ammo), unless what it holds is as good. Found
     * 2026-10-08: Flint (GUNSLINGER, nautilus-gun-l33, a level-30 gun) kept at 25 held the creator's sword. True when
     * it wore one.
     */
    static boolean wearAllowedWeapon(Character agent, Loadout loadout, Collection<Integer> weapons, ItemRules items) {
        Set<WeaponType> types = new HashSet<>();
        for (int equipId : loadout.equipIds()) {
            if (items.slot(equipId) == WEAPON) {
                if (usable(agent, items, equipId, WEAPON)) {
                    return false; // shaping wore the loadout's own
                }
                types.add(items.weaponType(equipId));
            }
        }
        if (types.isEmpty()) {
            return false;
        }
        Item worn = agent.getInventory(InventoryType.EQUIPPED).getItem(WEAPON);
        int wornId = worn == null ? 0 : worn.getItemId();
        int best = best(agent, weapons, wornId, items, types::contains);
        if (best == 0) {
            best = best(agent, weapons, wornId, items, type -> !shoots(type));
        }
        if (best == 0 || best == wornId) {
            return false;
        }
        boolean carried = agent.getInventory(InventoryType.EQUIP).countById(best) > 0;
        if (!carried && (agent.getInventory(InventoryType.EQUIP).isFull()
                || !InventoryManipulator.addById(agent.getClient(), best, (short) 1))) {
            return false;
        }
        if (AgentEquipmentService.equipInSlot(agent, best, WEAPON)) {
            return true;
        }
        if (!carried) {
            removeOne(agent, best);
        }
        return false;
    }

    /**
     * The weapon it can use whose type {@code fits} with the highest level requirement: the worn one ({@code wornId}, 0
     * for none) on a tie, else the lowest id. 0 for none.
     */
    private static int best(Character agent, Collection<Integer> weapons, int wornId, ItemRules items,
                            Predicate<WeaponType> fits) {
        List<Integer> candidates = new ArrayList<>();
        if (wornId > 0) {
            candidates.add(wornId);
        }
        weapons.stream().sorted().forEach(candidates::add);
        int best = 0;
        for (int itemId : candidates) {
            if (items.slot(itemId) == WEAPON && fits.test(items.weaponType(itemId))
                    && usable(agent, items, itemId, WEAPON)
                    && (best == 0 || items.reqLevel(itemId) > items.reqLevel(best))) {
                best = itemId;
            }
        }
        return best;
    }

    /** It fires ammo (arrows, crossbow bolts, stars, bullets). */
    private static boolean shoots(WeaponType type) {
        return type == WeaponType.BOW || type == WeaponType.CROSSBOW || type == WeaponType.CLAW
                || type == WeaponType.GUN;
    }

    /**
     * Skills that burn an item on cast (Shadow Partner: Summoning Rock, Mystic Door: Magic Rock) are
     * refused by the server without it, and Agent supply procurement does not know these items yet;
     * keep a working stock on shaped characters so the buffs are usable.
     */
    private static void stockSkillConsumables(Character agent) {
        for (Map.Entry<Skill, SkillEntry> learned : agent.getSkills().entrySet()) {
            int level = learned.getValue().skillevel;
            if (level <= 0) {
                continue;
            }
            server.StatEffect effect = learned.getKey().getEffect(level);
            if (effect == null || effect.getItemConNo() <= 0) {
                continue;
            }
            int itemId = effect.getItemCon();
            InventoryType inventory = ItemConstants.getInventoryType(itemId);
            int have = agent.getInventory(inventory).countById(itemId);
            int want = effect.getItemConNo() * SKILL_CONSUMABLE_STOCK;
            if (have < want) {
                InventoryManipulator.addById(agent.getClient(), itemId, (short) (want - have));
            }
        }
    }

    private static final int SKILL_CONSUMABLE_STOCK = 50;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;

    /**
     * The loadout step of shaping for a character shaped before its loadout's equips were edited (the shaped version
     * follows the loadout id, so an edit never reshapes): each authored piece it does not wear goes on when it can wear
     * it and the slot is empty, holds something it cannot use (another class's weapon) or holds a lower-level piece. A
     * citizen wears the copy it carries before it is given one; a {@code dressed} Resident is only given what it lacks
     * and its weapon is left to the outfit when the outfit has one, since the wardrobe dresses those slots after this.
     * Never leaves a new carried copy (no junk, nothing new for a citizen's stall) and never touches level, job, AP or
     * skills. True when it wore anything (the character is then saved).
     */
    public static boolean wearMissingEquips(Character agent, Loadout loadout, boolean dressed) {
        return agent != null && loadout != null && wearMissingEquips(agent, loadout, dressed, new WzItemRules());
    }

    static boolean wearMissingEquips(Character agent, Loadout loadout, boolean dressed, ItemRules items) {
        boolean wardrobeWeapon = dressed && !loadout.outfit().getOrDefault("weapon", List.of()).isEmpty();
        boolean changed = false;
        for (int equipId : loadout.equipIds()) {
            boolean carried = agent.getInventory(InventoryType.EQUIP).countById(equipId) > 0;
            if (agent.getInventory(InventoryType.EQUIPPED).countById(equipId) > 0 || carried && dressed) {
                continue; // worn, or taken off by the wardrobe for the Resident's look
            }
            short slot = items.slot(equipId);
            if (slot >= 0 || slot == WEAPON && wardrobeWeapon || !beats(agent, items, equipId, slot)) {
                continue;
            }
            if (!carried && (agent.getInventory(InventoryType.EQUIP).isFull()
                    || !InventoryManipulator.addById(agent.getClient(), equipId, (short) 1))) {
                continue;
            }
            if (AgentEquipmentService.equipInSlot(agent, equipId, slot)) {
                changed = true;
                if (slot == WEAPON) {
                    stockAmmo(agent, loadout); // a crossbow worn without its bolts could not attack at all
                }
            } else if (!carried) {
                removeOne(agent, equipId);
            }
        }
        if (changed) {
            agent.saveCharToDB();
        }
        return changed;
    }

    /** It can use the piece in the slot, and the slot is empty, unusable to it or holds a lower-level piece. */
    private static boolean beats(Character agent, ItemRules items, int itemId, short slot) {
        if (!usable(agent, items, itemId, slot)) {
            return false;
        }
        Item weapon = agent.getInventory(InventoryType.EQUIPPED).getItem(WEAPON);
        if (slot == SHIELD && weapon != null && items.twoHanded(weapon.getItemId())) {
            return false; // the shield would push the two-handed weapon off
        }
        Item worn = agent.getInventory(InventoryType.EQUIPPED).getItem(slot);
        return worn == null || !usable(agent, items, worn.getItemId(), slot)
                || items.reqLevel(worn.getItemId()) < items.reqLevel(itemId);
    }

    private static boolean usable(Character agent, ItemRules items, int itemId, short slot) {
        return items.meetsRequirements(agent, itemId)
                && (slot != WEAPON || AgentWeaponCompatibilityPolicy.isWeaponCompatible(agent, items.weaponType(itemId)));
    }

    /** The loadout's arrows, stars or bullets it holds none of; only with a newly worn weapon, so once per edit. */
    private static void stockAmmo(Character agent, Loadout loadout) {
        for (ItemStack stack : loadout.consumables()) {
            int itemId = stack.itemId();
            if ((ItemConstants.isArrow(itemId) || ItemConstants.isThrowingStar(itemId) || ItemConstants.isBullet(itemId))
                    && agent.getInventory(InventoryType.USE).countById(itemId) == 0) {
                InventoryManipulator.addById(agent.getClient(), itemId, (short) Math.min(stack.count(), Short.MAX_VALUE));
            }
        }
    }

    /** The item facts {@link #wearMissingEquips} reads; an interface because ItemInformationProvider needs the DB to load. */
    interface ItemRules {
        /** The equipped slot it goes in (-1 hat ... -11 weapon); 0 for none or a cash item. */
        short slot(int itemId);

        int reqLevel(int itemId);

        /** The game's own read-only check: the job mask (bots), level and main stat. */
        boolean meetsRequirements(Character agent, int itemId);

        WeaponType weaponType(int itemId);

        boolean twoHanded(int itemId);
    }

    private static final class WzItemRules implements ItemRules {
        private final ItemInformationProvider ii = ItemInformationProvider.getInstance();

        @Override
        public short slot(int itemId) {
            return ii.isCash(itemId) ? 0 : (short) EquipSlot.getFromTextSlot(ii.getEquipmentSlot(itemId)).getPrimarySlot();
        }

        @Override
        public int reqLevel(int itemId) {
            return ii.getEquipLevelReq(itemId);
        }

        @Override
        public boolean meetsRequirements(Character agent, int itemId) {
            return ii.meetsEquipRequirements(agent, itemId);
        }

        @Override
        public WeaponType weaponType(int itemId) {
            return ii.getWeaponType(itemId);
        }

        @Override
        public boolean twoHanded(int itemId) {
            return ii.isTwoHanded(itemId);
        }
    }

    private static void removeOne(Character agent, int itemId) {
        for (Item item : agent.getInventory(InventoryType.EQUIP).list()) {
            if (item.getItemId() == itemId) {
                InventoryManipulator.removeFromSlot(agent.getClient(), InventoryType.EQUIP, item.getPosition(), (short) 1, false);
                return;
            }
        }
    }

    /** BEGINNER -> first job -> ... -> the authored job; see AgentCharacterShaper. */
    public static List<Job> advancementChain(Job target) {
        return AgentCharacterShaper.advancementChain(target);
    }

    public static Job job(String name) {
        if (name == null || name.isBlank()) {
            return Job.BEGINNER;
        }
        try {
            return Job.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            try {
                return Job.getById(Integer.parseInt(name));
            } catch (NumberFormatException notNumeric) {
                log.warn("unknown job '{}', using BEGINNER", name);
                return Job.BEGINNER;
            }
        }
    }

}
