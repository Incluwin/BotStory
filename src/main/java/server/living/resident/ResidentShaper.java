package server.living.resident;

import client.Character;
import client.Job;
import client.JobProgressionPolicy;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.living.config.Content.ItemStack;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;

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
    static final int RECIPE_VERSION = 2;

    private ResidentShaper() {
    }

    public static void shape(Character agent, Persona persona, Loadout loadout, int levelSeed) {
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
        List<Job> chain = advancementChain(target);
        int guard = 0;
        while (guard++ < 250) {
            advanceIfLegal(agent, chain);
            if (agent.getLevel() >= targetLevel) {
                break;
            }
            agent.levelUp(false);
        }
        advanceIfLegal(agent, chain);
        repairGrowth(agent);
        assignAp(agent, agent.getJob(), loadout == null ? Map.of() : loadout.stats());
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
     * Characters that were jumped to a level (GM tools, an older recipe) never received the AP
     * and HP/MP of the skipped levels; top them up to the job's formula for this level.
     */
    private static void repairGrowth(Character agent) {
        int level = agent.getLevel();
        int spentBeyondBase = Math.max(0, agent.getStr() + agent.getDex() + agent.getInt() + agent.getLuk() - 25);
        int expectedRemaining = Math.max(0, 5 * (level - 1) - spentBeyondBase);
        if (agent.getRemainingAp() < expectedRemaining) {
            agent.gainAp(expectedRemaining - agent.getRemainingAp(), true);
        }
        client.HpMpGrowthPolicy.Growth base = client.HpMpGrowthPolicy.baseForJobAtLevel(agent.getJob(), level);
        if (agent.getMaxHp() < base.hp()) {
            agent.addMaxHP(base.hp() - agent.getMaxHp());
        }
        if (agent.getMaxMp() < base.mp()) {
            agent.addMaxMP(base.mp() - agent.getMaxMp());
        }
    }

    /** BEGINNER -> first job -> second job -> ... up to the authored job. */
    static List<Job> advancementChain(Job target) {
        List<Job> chain = new ArrayList<>();
        if (target == null || target == Job.BEGINNER) {
            return chain;
        }
        int id = target.getId();
        int first = id / 100 * 100;
        int second = id / 10 * 10;
        Job firstJob = first > 0 ? Job.getById(first) : null;
        Job secondJob = second != first ? Job.getById(second) : null;
        if (firstJob != null) {
            chain.add(firstJob);
        }
        if (secondJob != null) {
            chain.add(secondJob);
        }
        if (id != second) {
            chain.add(target);
        }
        return chain;
    }

    private static void advanceIfLegal(Character agent, List<Job> chain) {
        for (Job next : chain) {
            if (agent.getJob() == next) {
                continue;
            }
            if (JobProgressionPolicy.isLegalAdvancement(agent.getJob(), next, agent.getLevel())) {
                agent.changeJob(next);
            }
        }
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

    /** Spends remaining AP by archetype unless the loadout pins explicit str/dex/int/luk shares. */
    private static void assignAp(Character agent, Job job, Map<String, Integer> stats) {
        int ap = agent.getRemainingAp();
        if (ap <= 0) {
            return;
        }
        int str;
        int dex;
        int int_;
        int luk;
        if (!stats.isEmpty()) {
            int total = Math.max(1, stats.values().stream().mapToInt(Integer::intValue).sum());
            str = ap * stats.getOrDefault("str", 0) / total;
            dex = ap * stats.getOrDefault("dex", 0) / total;
            int_ = ap * stats.getOrDefault("int", 0) / total;
            luk = ap * stats.getOrDefault("luk", 0) / total;
        } else {
            int family = job == null ? 0 : job.getId() / 100;
            switch (family) {
                case 1 -> { str = ap * 4 / 5; dex = ap - str; int_ = 0; luk = 0; }
                case 2 -> { int_ = ap * 4 / 5; luk = ap - int_; str = 0; dex = 0; }
                case 3 -> { dex = ap * 4 / 5; str = ap - dex; int_ = 0; luk = 0; }
                case 4 -> { luk = ap * 4 / 5; dex = ap - luk; str = 0; int_ = 0; }
                case 5 -> { str = ap / 2; dex = ap - str; int_ = 0; luk = 0; }
                default -> { str = ap / 2; dex = ap - str; int_ = 0; luk = 0; }
            }
        }
        int assigned = str + dex + int_ + luk;
        if (assigned < ap) {
            str += ap - assigned;
        }
        agent.assignStrDexIntLuk(str, dex, int_, luk);
    }
}
