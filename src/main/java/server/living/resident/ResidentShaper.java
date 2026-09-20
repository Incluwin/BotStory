package server.living.resident;

import client.Character;
import client.Job;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.equipment.AgentEquipmentService;
import server.living.config.Content.ItemStack;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;

import java.util.Locale;
import java.util.Map;

/**
 * Turns a freshly provisioned level-1 beginner into the authored Persona: level band, job,
 * AP by job archetype, loadout equipment and consumables. Idempotent per shaped version.
 */
final class ResidentShaper {
    private static final Logger log = LoggerFactory.getLogger(ResidentShaper.class);
    /** Bump when the shaping recipe changes so existing personas are reshaped once. */
    static final int RECIPE_VERSION = 1;

    private ResidentShaper() {
    }

    static void shape(Character agent, Persona persona, Loadout loadout, int levelSeed) {
        int targetLevel = Math.max(1, Math.min(persona.level().pick(levelSeed), agent.getMaxClassLevel()));
        Job job = job(persona.job());
        if (agent.getLevel() < targetLevel) {
            agent.loseExp(agent.getExp(), false, false);
            agent.setLevel(targetLevel - 1);
            agent.resetPlayerRates();
            agent.levelUp(false);
        }
        if (job != null && agent.getJob() != job) {
            agent.forceChangeJobForAdmin(job);
            agent.equipChanged();
        }
        assignAp(agent, job, loadout == null ? Map.of() : loadout.stats());
        if (loadout != null) {
            for (int equipId : loadout.equipIds()) {
                if (agent.getInventory(client.inventory.InventoryType.EQUIP).countById(equipId) == 0
                        && agent.getInventory(client.inventory.InventoryType.EQUIPPED).countById(equipId) == 0) {
                    InventoryManipulator.addById(agent.getClient(), equipId, (short) 1);
                }
            }
            for (ItemStack stack : loadout.consumables()) {
                InventoryManipulator.addById(agent.getClient(), stack.itemId(), (short) Math.min(stack.count(), Short.MAX_VALUE));
            }
            try {
                AgentEquipmentService.autoEquip(agent, agent, null, true);
            } catch (RuntimeException failure) {
                log.debug("autoequip for persona {} failed", persona.id(), failure);
            }
        }
        agent.healHpMp();
        agent.saveCharToDB();
    }

    static Job job(String name) {
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
