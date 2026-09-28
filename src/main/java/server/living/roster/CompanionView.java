package server.living.roster;

import client.Character;
import client.Job;
import constants.game.ExpTable;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * One companion as a Crew window row or a companion card shows it (docs/BOT_MANAGEMENT_PLAN.md, "CompanionView").
 * Built from the live character when the companion is loaded; otherwise from its roster row plus one read of
 * its characters row (level and job). {@code where} is the map it stands on when loaded, else its camp;
 * HP is known only when it is loaded. {@code travelMs} is how long a companion called from its camp still needs
 * (0 when it is not on the way).
 */
public record CompanionView(String personaId,
                            String name,
                            RosterState state,
                            boolean loaded,
                            int level,
                            String job,
                            String where,
                            int hp,
                            int maxHp,
                            String duty,
                            int bankedExpPercent,
                            long travelMs) {
    /** Enum words that are two words in the game's job names; the rest are title-cased as they are. */
    private static final Map<String, String> JOB_WORDS = Map.ofEntries(
            Map.entry("FP", "F/P"), Map.entry("IL", "I/L"),
            Map.entry("WHITEKNIGHT", "White Knight"), Map.entry("DRAGONKNIGHT", "Dragon Knight"),
            Map.entry("DARKKNIGHT", "Dark Knight"), Map.entry("NIGHTLORD", "Night Lord"),
            Map.entry("CHIEFBANDIT", "Chief Bandit"), Map.entry("MAPLELEAF", "Maple Leaf"),
            Map.entry("GM", "GM"), Map.entry("SUPERGM", "GM"),
            Map.entry("DAWNWARRIOR", "Dawn Warrior"), Map.entry("BLAZEWIZARD", "Blaze Wizard"),
            Map.entry("WINDARCHER", "Wind Archer"), Map.entry("NIGHTWALKER", "Night Walker"),
            Map.entry("THUNDERBREAKER", "Thunder Breaker"));

    /** {@code placeNames} names a map id (the camp of a companion that is not loaded). */
    public static CompanionView of(LivingWorld world, RosterEntry entry, IntFunction<String> placeNames) {
        Persona persona = world.content().persona(entry.personaId());
        String name = persona == null ? entry.personaId() : persona.name();
        String duty = world.roster().describeAssignment(entry);
        long travelMs = world.roster().travelMsLeft(entry.characterId(), entry.personaId());
        Character bot = world.roster().companion(entry);
        if (bot != null) {
            return new CompanionView(entry.personaId(), name, entry.state(), true, bot.getLevel(), jobName(bot.getJob().getId()),
                    bot.getMap() == null ? "" : bot.getMap().getMapName(), bot.getHp(), bot.getCurrentMaxHp(), duty,
                    bankedPercent(entry.accruedExp(), bot.getLevel()), travelMs);
        }
        int characterId = world.residents().characterIdOf(entry.personaId());
        int[] levelJob = characterId == 0 ? new int[] {0, -1} : world.store().characterLevelAndJob(characterId);
        int camp = entry.campMapId() != 0 || persona == null ? entry.campMapId() : ResidentSpots.townMapId(persona.homeTown());
        return new CompanionView(entry.personaId(), name, entry.state(), false, levelJob[0], jobName(levelJob[1]),
                camp == 0 ? "" : placeNames.apply(camp), 0, 0, duty, bankedPercent(entry.accruedExp(), levelJob[0]), travelMs);
    }

    public int hpPercent() {
        return maxHp <= 0 ? 0 : (int) Math.round(100.0d * hp / maxHp);
    }

    /** Banked bench EXP as a share of the next level; 100 once it would level the companion (it is never more). */
    static int bankedPercent(long exp, int level) {
        int needed = level <= 0 ? 0 : ExpTable.getExpNeededForLevel(level);
        return needed <= 0 || exp <= 0 ? 0 : (int) Math.min(100L, exp * 100L / needed);
    }

    /** "Fighter", "F/P Wizard", "Dawn Warrior", "Maple Leaf Brigadier"; empty when unknown. */
    static String jobName(int jobId) {
        Job job = jobId < 0 ? null : Job.getById(jobId);
        if (job == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String word : job.name().replaceAll("[0-9]", "").split("_")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(JOB_WORDS.getOrDefault(word, word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT)));
        }
        return out.toString();
    }
}
