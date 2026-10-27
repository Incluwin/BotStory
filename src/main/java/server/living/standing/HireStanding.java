package server.living.standing;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.bond.Friendship;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.PersonMemories;
import server.living.population.PersonMemory;
import server.living.population.Population;
import server.living.store.LivingStore.Bond;

import java.util.Locale;
import java.util.Set;

/**
 * Hired hands on the friendship meter (docs/BOT_CONCEPT_PLAN.md C1.2-C1.4), while {@code HIRE_FRIENDSHIP_ENABLED}: a
 * hire's bond with its owner is a {@code hire:<characterId>} living_bond row, seeded once at the hire from the
 * citizen's warmth ({@link Standing#seedPoints}) and fed after that by the same party quests, bosses, fame and gifts as a
 * Resident's (Friendship). The citizen keeps remembering its owner while hired, a hire let go keeps the warmth of its
 * friendship, and the rung perks read the level here. Every method is a no-op or the old answer with the switch off.
 */
public final class HireStanding {
    private static final Logger log = LoggerFactory.getLogger(HireStanding.class);
    private static final Set<String> DIFFICULTY_WORDS = Set.of("easy", "normal", "hard", "chaos");

    private HireStanding() {
    }

    /** HIRE_FRIENDSHIP_ENABLED in this world. */
    public static boolean on(LivingWorld world) {
        return world != null && world.config() != null && world.config().HIRE_FRIENDSHIP_ENABLED;
    }

    /** The switch, for the population's hooks (which see no LivingWorld of their own). */
    public static boolean on() {
        return on(LivingWorld.get());
    }

    /** How warm the citizen feels toward the player now (PersonMemory); 0 without a population or its memory. */
    public static int warmth(int citizenId, int playerId) {
        Population population = Population.get();
        PersonMemories memories = population == null ? null : population.memories();
        return memories == null ? 0 : memories.warmth(citizenId, playerId);
    }

    /** The owner's friendship level with this hire (0: none kept). */
    public static int level(LivingWorld world, int ownerId, String hireId) {
        Friendship friendship = world.friendship();
        return friendship == null ? 0 : friendship.level(ownerId, hireId);
    }

    /**
     * The new hire's bond with its owner, on companion-ops once the roster row is written (HiredHands.hire): the points
     * {@code warmth} earns ({@link Standing#seedPoints}), or what an earlier hire, a fame or a boss run left when that is
     * more; the level shows as pink text like any friendship change.
     */
    public static void seed(LivingWorld world, Character owner, Persona hire, int warmth) {
        if (!on(world) || world.bonds() == null || !LivingContent.isHire(hire.id())) {
            return;
        }
        int target = Standing.seedPoints(warmth, world.config().friendshipLevelPoints());
        Bond bond = world.bonds().bond(owner, hire);
        if (target > bond.points()) {
            world.bonds().addPoints(owner, hire, target - bond.points(), "hired, warmth " + warmth);
        }
    }

    /**
     * The hire let go (HiredHands.onReleased): the citizen feels at least {@link Standing#releaseWarmth} of its
     * friendship toward its old owner, so it greets them as a friend when they meet again.
     */
    public static void released(LivingWorld world, int ownerId, String hireId) {
        int citizenId = LivingContent.hireCharacterId(hireId);
        Population population = Population.get();
        PersonMemories memories = population == null ? null : population.memories();
        if (!on(world) || citizenId <= 0 || memories == null) {
            return;
        }
        int level = level(world, ownerId, hireId);
        int warmth = Standing.releaseWarmth(level);
        memories.warmAtLeast(citizenId, ownerId, warmth);
        log.info("hired hand {} let go by {} at friendship Lv {}: warmth at least {}", hireId, ownerId, level, warmth);
    }

    /**
     * A hired citizen ({@code characterId}) remembers what it did with {@code playerId} while hired (a party quest or a
     * boss cleared, a fame): the population's own hooks see no session for it. False when it is no hire or the switch
     * is off.
     */
    public static boolean remember(int characterId, int playerId, PersonMemory.Kind kind, int mapId, String detail) {
        Population population = Population.get();
        if (population == null || population.memories() == null || !on()) {
            return false;
        }
        Citizen citizen = population.citizen(characterId);
        if (citizen == null || !citizen.hired()) {
            return false;
        }
        population.memories().record(characterId, playerId, kind, mapId, detail, 1);
        return true;
    }

    /** What players call a boss run: easy_balrog and balrog_normal are "balrog" (its difficulty words dropped). */
    public static String runName(String bossKey) {
        if (bossKey == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String word : bossKey.toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty() && !DIFFICULTY_WORDS.contains(word)) {
                out.append(out.length() == 0 ? "" : " ").append(word);
            }
        }
        return out.toString();
    }

    /** The fee a FRIEND (warmth 40+) asks: {@code fee} less HIRE_FRIEND_FEE_DISCOUNT_PERCENT; anyone else the fee. */
    public static long fee(LivingWorld world, long fee, int warmth) {
        if (!on(world) || !Standing.ofCitizen(warmth).atLeast(Standing.Rung.FRIEND)) {
            return fee;
        }
        return fee * (100 - world.config().HIRE_FRIEND_FEE_DISCOUNT_PERCENT) / 100;
    }

    /** What a companion's upkeep is multiplied by: less HIRE_CLOSE_WAGE_DISCOUNT_PERCENT for a hire at Lv 7+, else 1. */
    public static double wageFactor(LivingWorld world, int ownerId, String personaId) {
        if (!on(world) || !LivingContent.isHire(personaId)
                || level(world, ownerId, personaId) < Standing.WAGE_DISCOUNT_LEVEL) {
            return 1.0d;
        }
        return (100 - world.config().HIRE_CLOSE_WAGE_DISCOUNT_PERCENT) / 100.0d;
    }

    /** A hire at Lv 10 with its owner: it stays on their roster through any absence (ResidentAllocation.sweep). */
    public static boolean permanent(LivingWorld world, int ownerId, String personaId) {
        return on(world) && LivingContent.isHire(personaId)
                && level(world, ownerId, personaId) >= Standing.PERMANENT_LEVEL;
    }
}
