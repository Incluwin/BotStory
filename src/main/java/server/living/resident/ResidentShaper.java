package server.living.resident;

import client.Character;
import client.Job;
import client.Skill;
import client.Character.SkillEntry;
import client.inventory.InventoryType;
import constants.inventory.ItemConstants;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.build.AgentCharacterShaper;
import server.agents.capabilities.build.profiles.AgentSpBuildProfileService;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.living.config.Content.ItemStack;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
     * Levels the character one step at a time to {@code level}, advancing along the legal job chain
     * toward {@code target}, repairs skipped growth, spends AP and applies the loadout. Used for
     * Residents and for generated citizens (population).
     */
    public static void shapeTo(Character agent, Job target, int level, Loadout loadout) {
        SHAPING.set(Boolean.TRUE);
        try {
            shapeUnhooked(agent, target, level, loadout);
        } finally {
            SHAPING.remove();
        }
    }

    private static final ThreadLocal<Boolean> SHAPING = new ThreadLocal<>();

    /** True on the thread that is currently shaping a character (level-up/job hooks stay quiet). */
    public static boolean shaping() {
        return Boolean.TRUE.equals(SHAPING.get());
    }

    private static void shapeUnhooked(Character agent, Job target, int level, Loadout loadout) {
        int targetLevel = Math.max(1, Math.min(level, 200));
        List<Job> chain = AgentCharacterShaper.advancementChain(target);
        int guard = 0;
        while (guard++ < 250) {
            AgentCharacterShaper.advanceIfLegal(agent, chain);
            if (agent.getLevel() >= targetLevel) {
                break;
            }
            agent.levelUp(false);
        }
        AgentCharacterShaper.advanceIfLegal(agent, chain);
        AgentCharacterShaper.repairGrowth(agent);
        AgentCharacterShaper.assignAp(agent, agent.getJob(), loadout == null ? Map.of() : loadout.stats());
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
        }
        agent.healHpMp();
        agent.saveCharToDB();
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
