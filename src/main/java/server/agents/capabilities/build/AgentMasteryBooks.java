package server.agents.capabilities.build;

import client.Character;
import client.Skill;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentSkillGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.SkillGateway;
import server.agents.runtime.AgentRuntimeEntry;
import tools.PacketCreator;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Skill books (2280000-2289999) and mastery books (2290000-2299999) an Agent carries in its Use tab, from its owner or
 * its own loot (user decision 2026-10-13): it reads one as a player does (SkillBookHandler). A book for its job whose
 * required skill level it has, and whose master level is above its own, is used up; on the book's success roll the
 * skill's master level rises to the book's, and SP spending (AgentSpBuildProfileService caps a 4th-job skill at its
 * master level) then puts the SP it holds there. Everyone on the map sees the book's effect. A book for another job,
 * or one it cannot read yet, stays in its bag. One book at a time, at most every {@link #CHECK_EVERY_MS}.
 */
public final class AgentMasteryBooks {
    private static final Logger log = LoggerFactory.getLogger(AgentMasteryBooks.class);
    /** How often an Agent looks through its Use tab for a book it can read. */
    static final long CHECK_EVERY_MS = 10_000L;

    /** A book's data for a job (ItemInformationProvider.getSkillStats): skillid, masterLevel, reqSkillLevel, success. */
    interface BookStats {
        Map<String, Integer> forJob(int itemId, int jobId);
    }

    private AgentMasteryBooks() {
    }

    /**
     * On the build tick (AgentBuildService.checkLevelUp): at most every {@link #CHECK_EVERY_MS}, reads one book it can.
     * True when a master level rose, so the caller spends SP.
     */
    static boolean tick(AgentRuntimeEntry entry, Character bot, long now) {
        AgentBuildState state = entry == null ? null : entry.buildState();
        if (state == null || bot == null || now < state.nextBookCheckAt()) {
            return false;
        }
        state.setNextBookCheckAt(now + CHECK_EVERY_MS);
        return readOne(bot, AgentSkillGatewayRuntime.skills(), AgentInventoryGatewayRuntime.inventory(),
                (itemId, jobId) -> ItemInformationProvider.getInstance().getSkillStats(itemId, jobId),
                ItemInformationProvider::rollSuccessChance);
    }

    /** True for a skill book or mastery book id. */
    static boolean isBook(int itemId) {
        int kind = itemId / 10_000;
        return kind == 228 || kind == 229;
    }

    /**
     * Reads the first book in the Use tab (lowest slot) that the Agent can read, as SkillBookHandler does for a player:
     * uses it up, rolls its success and on success raises the skill's master level. True when a master level rose.
     */
    static boolean readOne(Character bot, SkillGateway skills, InventoryGateway inventory, BookStats stats,
                           IntPredicate roll) {
        if (bot == null || !bot.isAlive() || bot.getJob() == null) {
            return false;
        }
        Inventory use = bot.getInventory(InventoryType.USE);
        if (use == null) {
            return false;
        }
        List<Item> books = use.list().stream()
                .filter(item -> item != null && isBook(item.getItemId()) && item.getQuantity() > 0)
                .sorted(Comparator.comparingInt(Item::getPosition))
                .toList();
        for (Item book : books) {
            Map<String, Integer> data = stats.forJob(book.getItemId(), bot.getJob().getId());
            int skillId = data == null ? 0 : data.getOrDefault("skillid", 0);
            Skill skill = skillId == 0 ? null : skills.getSkill(skillId);
            if (skill == null) {
                continue; // another job's book: it keeps it
            }
            int required = data.getOrDefault("reqSkillLevel", 0);
            int master = data.getOrDefault("masterLevel", 0);
            int currentMaster = bot.getMasterLevel(skill);
            if ((required != 0 && bot.getSkillLevel(skill) < required) || currentMaster >= master) {
                continue; // not yet, or nothing to learn from it
            }
            inventory.removeFromSlot(bot, InventoryType.USE, book.getPosition(), (short) 1, false);
            boolean success = roll.test(data.getOrDefault("success", 0));
            if (success) {
                bot.changeSkillLevel(skill, bot.getSkillLevel(skill), master, -1);
            }
            if (bot.getMap() != null) {
                bot.getMap().broadcastMessage(PacketCreator.skillBookResult(bot, skillId, master, true, success));
            }
            log.info("Agent {} read book {}: skill {} master {} -> {} ({})", bot.getName(), book.getItemId(), skillId,
                    currentMaster, success ? master : currentMaster, success ? "learned" : "the book failed");
            return success;
        }
        return false;
    }
}
