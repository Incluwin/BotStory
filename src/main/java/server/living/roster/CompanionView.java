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
import java.util.function.IntFunction;

/**
 * One companion as a Crew window row or a companion card shows it (docs/BOT_MANAGEMENT_PLAN.md, "CompanionView").
 * Built from the live character when the companion is loaded; otherwise from its roster row plus one read of
 * its characters row (level and job). {@code where} is the map it stands on when loaded, else its camp;
 * HP is known only when it is loaded.
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
                            int bankedExpPercent) {

    /** {@code placeNames} names a map id (the camp of a companion that is not loaded). */
    public static CompanionView of(LivingWorld world, RosterEntry entry, IntFunction<String> placeNames) {
        Persona persona = world.content().persona(entry.personaId());
        String name = persona == null ? entry.personaId() : persona.name();
        String duty = world.roster().describeAssignment(entry);
        Character bot = world.roster().companion(entry);
        if (bot != null) {
            return new CompanionView(entry.personaId(), name, entry.state(), true, bot.getLevel(), jobName(bot.getJob().getId()),
                    bot.getMap() == null ? "" : bot.getMap().getMapName(), bot.getHp(), bot.getCurrentMaxHp(), duty,
                    bankedPercent(entry.accruedExp(), bot.getLevel()));
        }
        int characterId = world.residents().characterIdOf(entry.personaId());
        int[] levelJob = characterId == 0 ? new int[] {0, -1} : world.store().characterLevelAndJob(characterId);
        int camp = entry.campMapId() != 0 || persona == null ? entry.campMapId() : ResidentSpots.townMapId(persona.homeTown());
        return new CompanionView(entry.personaId(), name, entry.state(), false, levelJob[0], jobName(levelJob[1]),
                camp == 0 ? "" : placeNames.apply(camp), 0, 0, duty, bankedPercent(entry.accruedExp(), levelJob[0]));
    }

    public int hpPercent() {
        return maxHp <= 0 ? 0 : (int) Math.round(100.0d * hp / maxHp);
    }

    /** Banked bench EXP as a share of the next level. */
    static int bankedPercent(long exp, int level) {
        int needed = level <= 0 ? 0 : ExpTable.getExpNeededForLevel(level);
        return needed <= 0 || exp <= 0 ? 0 : (int) Math.min(999L, exp * 100L / needed);
    }

    /** "Fighter", "F/P Wizard", "Dawnwarrior"; empty when unknown. */
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
            out.append(switch (word) {
                case "FP" -> "F/P";
                case "IL" -> "I/L";
                default -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT);
            });
        }
        return out.toString();
    }
}
