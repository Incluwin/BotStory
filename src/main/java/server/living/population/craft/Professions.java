package server.living.population.craft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.CareerBranch;
import server.living.population.Citizen;
import server.living.population.Population;

import java.nio.file.Path;

/**
 * Citizens' trades (docs/WORLD_SYSTEMS_PLAN.md N8.1): about one in ten takes one up ({@link Profession#drawn}: stable per
 * citizen, by career and personality) and keeps it as a flag; its stall, megaphones and talk mention it (chatter intents
 * {@code trade.<word>.*}, catalog lines) and it takes commissions ({@link Commissions}). Behind
 * population.PROFESSIONS_ENABLED; off, no citizen has a trade whatever its flags say.
 */
public final class Professions {
    private static final Logger log = LoggerFactory.getLogger(Professions.class);
    /** Chatter intents said only by a citizen of that trade: "trade.smith.town". */
    public static final String INTENT = "trade.";
    /** Flag rows written a minute at most: the first minutes after the switch goes on, not a hundred at once. */
    static final int WRITES_A_MINUTE = 20;

    private final Population population;
    private volatile RecipeBook book;

    public Professions(Population population) {
        this(population, null);
    }

    Professions(Population population, RecipeBook book) {
        this.population = population;
        this.book = book;
    }

    public boolean on() {
        return population.config().PROFESSIONS_ENABLED;
    }

    /** The trade the citizen works, or null (none, hired by a player, retired, or professions are off). */
    public Profession of(Citizen citizen) {
        return !on() || citizen == null || citizen.hired() || citizen.state() != Citizen.State.ACTIVE ? null
                : Profession.of(citizen);
    }

    public Profession of(int citizenId) {
        return of(population.citizen(citizenId));
    }

    /** recipes.yaml, read once. */
    public RecipeBook book() {
        RecipeBook known = book;
        if (known == null) {
            synchronized (this) {
                known = book;
                if (known == null) {
                    known = RecipeBook.load(Path.of(population.config().CONTENT_DIR));
                    book = known;
                }
            }
        }
        return known;
    }

    /** The population's minute: citizens who should have a trade and have none take it up. */
    public void minute() {
        if (!on()) {
            return;
        }
        int written = 0;
        for (Citizen citizen : population.citizens()) {
            if (written >= WRITES_A_MINUTE) {
                return;
            }
            if (citizen.state() != Citizen.State.ACTIVE || citizen.hired() || Profession.of(citizen) != null) {
                continue;
            }
            Profession drawn = Profession.drawn(citizen.characterId(), CareerBranch.of(population, citizen),
                    population.social().sociability(citizen));
            if (drawn != null) {
                population.update(citizen.characterId(),
                        current -> Profession.of(current) != null ? current : current.withFlag(drawn.flag()));
                log.info("citizen {} takes up a trade: {}", citizen.name(), drawn.word());
                written++;
            }
        }
    }

    /** For !profession: {@code profession} (null: none) instead of what it has. */
    public void set(int citizenId, Profession profession) {
        population.update(citizenId, current -> {
            Citizen next = current;
            for (Profession known : Profession.values()) {
                next = next.withoutFlag(known.flag());
            }
            return profession == null ? next : next.withFlag(profession.flag());
        });
    }

    /** A crafter's stall title, "smith here: steel plate and more", or {@code plain}. */
    public String stallTitle(Citizen citizen, String plain, String goods) {
        Profession profession = of(citizen);
        return profession == null ? plain : profession.word() + " here: " + goods;
    }

    /** Whether a chatter intent is the citizen's to say: a trade's lines only by someone of that trade, while on. */
    public boolean says(Citizen citizen, String intentId) {
        if (!intentId.startsWith(INTENT)) {
            return true;
        }
        Profession profession = of(citizen);
        return profession != null && intentId.startsWith(INTENT + profession.word() + ".");
    }
}
