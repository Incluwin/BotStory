package server.agents.capabilities.combat;

import client.Character;
import server.integration.AgentPresence;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Manners in the grind loop (docs/WORLD_SYSTEMS_PLAN.md N2.4, decision D4): a polite bot leaves alone a mob a human
 * outside its party has hit and it has not, the way players do. It is the first half of kill-stealing: before a
 * citizen remembers a human who keeps finishing its mobs, citizens stop doing it to humans. Who is polite is installed
 * by the population (citizens, while population.KS_MEMORY_ENABLED is on); nobody until then, so the target pick, the
 * skill hitboxes and every other bot are as they were. A map with no human costs one scan of its players.
 */
public final class AgentGrindManners {
    private static volatile IntPredicate polite = id -> false;

    private AgentGrindManners() {
    }

    /** {@code politeAgents} tells which bot characters keep these manners (Population.start). */
    public static void install(IntPredicate politeAgents) {
        polite = politeAgents == null ? id -> false : politeAgents;
    }

    /** The humans on the bot's map outside its party, when the bot is polite; empty otherwise (nothing to leave alone). */
    static List<Character> othersHumans(Character bot) {
        if (bot == null || !polite.test(bot.getId())) {
            return List.of();
        }
        MapleMap map = bot.getMap();
        if (map == null) {
            return List.of();
        }
        List<Character> out = new ArrayList<>(2);
        for (Character chr : map.getAllPlayers()) {
            if (chr == null || chr.getId() == bot.getId() || AgentPresence.isAgent(chr)) {
                continue;
            }
            if (bot.getParty() != null && chr.getParty() != null && bot.getParty().getId() == chr.getParty().getId()) {
                continue;
            }
            out.add(chr);
        }
        return out;
    }

    /** True when one of {@code humans} hit {@code monster} and {@code bot} has not: theirs. */
    static boolean theirs(Character bot, Monster monster, List<Character> humans) {
        if (humans.isEmpty() || monster.isAttackedBy(bot)) {
            return false;
        }
        for (Character human : humans) {
            if (monster.isAttackedBy(human)) {
                return true;
            }
        }
        return false;
    }

    /** {@code candidates} without the mobs humans are fighting, for a polite bot (the list itself, changed in place). */
    static <T extends Monster> List<T> leaveTheirs(Character bot, List<T> candidates) {
        List<Character> humans = othersHumans(bot);
        if (!humans.isEmpty()) {
            candidates.removeIf(monster -> theirs(bot, monster, humans));
        }
        return candidates;
    }

    /** As {@link #leaveTheirs}, over any monsters, as a new list (an AoE's secondary candidates). */
    static Iterable<Monster> mannerly(Character bot, Iterable<Monster> monsters) {
        List<Character> humans = othersHumans(bot);
        if (humans.isEmpty()) {
            return monsters;
        }
        List<Monster> out = new ArrayList<>();
        for (Monster monster : monsters) {
            if (!theirs(bot, monster, humans)) {
                out.add(monster);
            }
        }
        return out;
    }
}
