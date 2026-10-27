package server.living.standing;

import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.CitizenStore.Acquaintance;
import server.living.population.PersonMemory;
import server.living.population.Population;
import server.living.store.LivingStore.Bond;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * What {@code !standing <bot> [player]} prints (docs/BOT_CONCEPT_PLAN.md C1.1): the rung one player has with one bot,
 * and the raw numbers it comes from: a Resident's friendship (bond points, level, stage), a citizen's warmth (with
 * the party and map minutes together and the friendship a hire of it would start at), a hire's friendship with its
 * owner (and the warmth it keeps when let go) and its perks. Read-only; a store read or two, for a GM's command thread.
 */
public final class StandingReport {
    /** What kind of bot it is to the population and the roster. */
    public enum Kind { RESIDENT, CITIZEN, HIRE }

    /** The rung and the lines that explain it. */
    public record Report(Kind kind, Standing.Rung rung, List<String> lines) { }

    private StandingReport() {
    }

    /**
     * The report for the bot named {@code botName} and the player {@code playerId}; null when no bot has that name.
     * {@code names} names a character id (a hire's owner; null when unknown).
     */
    public static Report of(LivingWorld world, String botName, int playerId, String playerName,
                            IntFunction<String> names) {
        Persona resident = residentNamed(world, botName);
        if (resident != null) {
            return resident(world, resident, playerId, playerName);
        }
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizenByName(botName);
        if (citizen == null) {
            return null;
        }
        String hireId = LivingContent.HIRE_PREFIX + citizen.characterId();
        int owner = world.roster() == null ? 0 : world.roster().cachedOwnerOf(hireId);
        return owner != 0 ? hire(world, citizen, hireId, owner, playerId, playerName, names)
                : citizen(world, population, citizen, playerId, playerName);
    }

    private static Persona residentNamed(LivingWorld world, String name) {
        for (Persona persona : world.content().personas().values()) {
            if (!LivingContent.isHire(persona.id()) && persona.name().equalsIgnoreCase(name)) {
                return persona;
            }
        }
        return null;
    }

    private static Report resident(LivingWorld world, Persona persona, int playerId, String playerName) {
        Bond bond = world.bonds().peek(playerId, persona.id());
        int points = bond == null ? 0 : bond.points();
        int level = world.friendship().levelFor(points);
        Standing.Rung rung = Standing.ofResident(level);
        List<String> lines = new ArrayList<>();
        lines.add(persona.name() + " (Resident) and " + playerName + ": " + rung);
        lines.add(bond == null ? "no bond yet" : "friendship " + world.friendship().describe(points) + ", " + points
                + " points, stage " + bond.stage());
        return new Report(Kind.RESIDENT, rung, lines);
    }

    private static Report citizen(LivingWorld world, Population population, Citizen citizen, int playerId,
                                  String playerName) {
        int warmth = HireStanding.warmth(citizen.characterId(), playerId);
        Standing.Rung rung = Standing.ofCitizen(warmth);
        Acquaintance together = population.store() == null ? null
                : population.store().acquaintance(citizen.characterId(), playerId);
        int seed = Standing.seedPoints(warmth, world.config().friendshipLevelPoints());
        List<String> lines = new ArrayList<>();
        lines.add(citizen.name() + " (citizen) and " + playerName + ": " + rung
                + (Standing.grudge(warmth) ? ", a grudge" : ""));
        lines.add(String.format(Locale.ROOT, "warmth %d (REGULAR at %d, FRIEND at %d, a grudge at %d or under);"
                        + " together %d party min, %d map min", warmth, PersonMemory.WARM, PersonMemory.FOND,
                PersonMemory.COLD, together == null ? 0 : together.partyMinutes(),
                together == null ? 0 : together.mapMinutes()));
        lines.add("offers to stay on: " + (Standing.grudge(warmth) || !rung.atLeast(Standing.Rung.REGULAR) ? "no"
                : rung.atLeast(Standing.Rung.FRIEND) ? "yes" : "by its temperament") + "; a hire would start at Lv "
                + world.friendship().levelFor(seed) + " (" + seed + " points)" + switchNote(world));
        return new Report(Kind.CITIZEN, rung, lines);
    }

    private static Report hire(LivingWorld world, Citizen citizen, String hireId, int ownerId, int playerId,
                               String playerName, IntFunction<String> names) {
        Bond bond = world.bonds().peek(playerId, hireId);
        int points = bond == null ? 0 : bond.points();
        int level = world.friendship().levelFor(points);
        boolean owner = ownerId == playerId;
        int warmth = HireStanding.warmth(citizen.characterId(), playerId);
        Standing.Rung rung = owner ? Standing.ofHire(level) : Standing.ofCitizen(warmth);
        String employer = names.apply(ownerId);
        List<String> lines = new ArrayList<>();
        lines.add(citizen.name() + " (hired by " + (employer == null ? "#" + ownerId : employer) + ") and " + playerName
                + ": " + rung);
        lines.add((bond == null ? "no bond yet" : "friendship " + world.friendship().describe(points) + ", " + points
                + " points, stage " + bond.stage()) + "; warmth " + warmth + switchNote(world));
        if (owner) {
            lines.add("perks: buddy request " + yes(level >= world.config().COMPANION_BUDDY_FRIENDSHIP_LEVEL)
                    + ", wages -" + world.config().HIRE_CLOSE_WAGE_DISCOUNT_PERCENT + "% "
                    + yes(level >= Standing.WAGE_DISCOUNT_LEVEL)
                    + ", permanent " + yes(level >= Standing.PERMANENT_LEVEL) + "; let go it keeps warmth "
                    + Standing.releaseWarmth(level) + " or more");
        }
        return new Report(Kind.HIRE, rung, lines);
    }

    private static String yes(boolean reached) {
        return reached ? "(yes)" : "(not yet)";
    }

    private static String switchNote(LivingWorld world) {
        return HireStanding.on(world) ? "" : " [HIRE_FRIENDSHIP_ENABLED off]";
    }

}
