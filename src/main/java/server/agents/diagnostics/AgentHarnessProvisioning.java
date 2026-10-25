package server.agents.diagnostics;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.StatEffect;
import server.agents.capabilities.build.AgentCharacterShaper;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.agents.capabilities.combat.AgentAttackExecutionProvider;
import server.agents.integration.AgentInventoryGatewayRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provisioning shared by the GM harnesses (skill probe, combat arena): a plain shop weapon per
 * branch with its ammo, the consumables that item-cost skills burn, a potion stock so supply
 * procurement does not pull the Agent out of the foreground, and the full skill tree.
 */
public final class AgentHarnessProvisioning {
    /** Elixir: restores HP and MP, so one stack covers both potion categories. */
    public static final int ELIXIR_ITEM_ID = 2000004;
    private static final int[] STANDARD_BEGINNER_SKILLS = {1000, 1001, 1002};

    private AgentHarnessProvisioning() {
    }

    /** A plain shop weapon per branch so the tree's skills are weapon-compatible. */
    public static int defaultWeaponFor(Job job) {
        int id = job == null ? 0 : job.getId();
        int family = id / 100;
        int branch = (id / 10) % 10;
        return switch (family) {
            case 1 -> branch == 3 ? 1432000 : 1302000;
            case 2 -> 1372005;
            case 3 -> branch == 2 ? 1462000 : 1452002;
            case 4 -> branch == 2 ? 1332007 : 1472000;
            case 5 -> branch == 2 ? 1492000 : 1482000;
            case 12 -> 1372005;                          // Blaze Wizard: wand
            case 13 -> 1452002;                          // Wind Archer: bow
            case 14 -> 1472000;                          // Night Walker: claw
            case 15 -> 1482000;                          // Thunder Breaker: knuckle
            case 20, 21 -> 1442000;                      // Legend and Aran: polearm (a Dawn Warrior keeps the sword)
            default -> 1302000;
        };
    }

    /**
     * A level-appropriate weapon for arena measurement. The ordinary default is a starter weapon,
     * which leaves a level 120 Agent swinging 19 watk: absolute damage then says more about the
     * weapon than the rotation, and two jobs cannot be compared at all. These are the strongest
     * level 90-120 weapons of each family in the shipped WZ data, so every job is measured on
     * comparable gear. Every id here exists in both the equip data and the item strings, or the
     * server refuses to hand it over. Warriors that can hold a sword get one, so their
     * sword-family charges and boosters apply.
     */
    public static int arenaWeaponFor(Job job) {
        int id = job == null ? 0 : job.getId();
        int family = id / 100;
        int branch = (id / 10) % 10;
        return switch (family) {
            case 1 -> branch == 3 ? 1432047 : 1402046;   // spear for the Dragon Knight line, else two-handed sword
            case 2 -> 1382057;                            // staff
            case 3 -> branch == 2 ? 1462050 : 1452057;   // crossbow for Marksman, else bow
            case 4 -> branch == 2 ? 1332073 : 1472068;   // dagger for the Bandit line, else claw
            case 5 -> branch == 2 ? 1492023 : 1482024;   // gun for the Gunslinger line, else knuckle
            case 12 -> 1382057;                           // Blaze Wizard: staff
            case 13 -> 1452057;                           // Wind Archer: bow
            case 14 -> 1472068;                           // Night Walker: claw
            case 15 -> 1482024;                           // Thunder Breaker: knuckle
            case 20, 21 -> 1442045;                       // Legend and Aran: Dragon Chelbird (Lv110 polearm)
            default -> 1402046;
        };
    }

    public static int defaultAmmoFor(WeaponType weaponType) {
        if (weaponType == null) {
            return 0;
        }
        return switch (weaponType) {
            case BOW -> 2060000;
            case CROSSBOW -> 2061000;
            case CLAW -> 2070000;
            case GUN -> 2330000;
            default -> 0;
        };
    }

    /** Equips {@code itemId} (provisioned if absent) and tops up its ammo; returns a note or null. */
    public static String provisionWeapon(Character bot, int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ii.getName(itemId) == null) {
            return "weapon item " + itemId + " does not exist";
        }
        if (bot.getInventory(InventoryType.EQUIP).countById(itemId) == 0
                && bot.getInventory(InventoryType.EQUIPPED).countById(itemId) == 0) {
            AgentInventoryGatewayRuntime.inventory().addItem(bot, itemId, (short) 1);
        }
        if (!AgentEquipmentService.equipPreferredWeapon(bot, itemId)) {
            return "could not equip weapon " + itemId + " (" + ii.getName(itemId) + ")";
        }
        topUpAmmo(bot);
        return null;
    }

    public static void topUpAmmo(Character bot) {
        int ammoId = defaultAmmoFor(AgentAttackExecutionProvider.getEquippedWeaponType(bot));
        if (ammoId > 0 && bot.getInventory(InventoryType.USE).countById(ammoId) < 200) {
            AgentInventoryGatewayRuntime.inventory().addItem(bot, ammoId, (short) 500);
        }
    }

    public static void topUpPotions(Character bot) {
        if (bot.getInventory(InventoryType.USE).countById(ELIXIR_ITEM_ID) < 50) {
            AgentInventoryGatewayRuntime.inventory().addItem(bot, ELIXIR_ITEM_ID, (short) 100);
        }
    }

    /** Stocks every item a learned skill burns on cast (Summoning/Magic Rocks) for {@code casts} casts. */
    public static void topUpSkillConsumables(Character bot, int casts) {
        for (Map.Entry<Skill, Character.SkillEntry> learned : bot.getSkills().entrySet()) {
            int level = learned.getValue().skillevel;
            if (level <= 0) {
                continue;
            }
            StatEffect effect = learned.getKey().getEffect(level);
            if (effect == null || effect.getItemConNo() <= 0) {
                continue;
            }
            int want = effect.getItemConNo() * casts;
            int have = bot.getInventory(ItemConstants.getInventoryType(effect.getItemCon())).countById(effect.getItemCon());
            if (have < want) {
                AgentInventoryGatewayRuntime.inventory().addItem(bot, effect.getItemCon(), (short) (want - have));
            }
        }
    }

    /** Sets every skill on the Agent's advancement chain to its maximum level (three standard beginner skills only). */
    public static int learnFullTree(Character bot) {
        List<Integer> jobIds = new ArrayList<>();
        for (Job job : AgentCharacterShaper.advancementChain(bot.getJob())) {
            jobIds.add(job.getId());
        }
        int changed = 0;
        for (int skillId : STANDARD_BEGINNER_SKILLS) {
            changed += maxSkill(bot, SkillFactory.getSkill(skillId)) ? 1 : 0;
        }
        for (int jobId : jobIds) {
            for (int index = 0; index <= 1999; index++) {  // v83 skill indexes stop near 1010; misses are cheap
                changed += maxSkill(bot, SkillFactory.getSkill(jobId * 10000 + index)) ? 1 : 0;
            }
        }
        return changed;
    }

    private static boolean maxSkill(Character bot, Skill skill) {
        if (skill == null) {
            return false;
        }
        int max = skill.getMaxLevel();
        if (max <= 0 || bot.getSkillLevel(skill) >= max) {
            return false;
        }
        int master = skill.isFourthJob() ? max : bot.getMasterLevel(skill);
        return bot.changeSkillLevel(skill, (byte) max, master, -1);
    }
}
