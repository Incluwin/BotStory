package server.expeditions;

import client.BotClient;
import client.Character;
import config.YamlConfig;
import server.mods.ServerMods;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One boss run's shared death budget (the {@code death_count} mod; the client's death_count panel draws it beside the
 * boss clock). Each player death on the run's maps spends one; a player dying with none left ends the run the way its
 * clock would. Only players count: companions and other bots keep their own death handling and never spend it.
 * {@code mods.DEATH_COUNTS} in config.yaml sets the budget per expedition type; a missing or 0 line leaves that boss
 * stock. Each run has its own event instance, and so its own budget.
 */
public final class ExpeditionDeathCount {
    /** What {@link #charge} did with a death. */
    public enum Charge { OFF, SPENT, EXHAUSTED }

    private final AtomicInteger left = new AtomicInteger(-1);
    private final AtomicBoolean ended = new AtomicBoolean(false);

    /** The budget config.yaml gives this expedition type while the mod is on; 0 when it has none. */
    public static int configuredFor(ExpeditionType type) {
        if (type == null || !ServerMods.get().isOn(ServerMods.DEATH_COUNT)) {
            return 0;
        }
        Map<String, Integer> counts = YamlConfig.config.mods.DEATH_COUNTS;
        Integer count = counts == null ? null : counts.get(type.name().toLowerCase(Locale.ROOT));
        return count == null ? 0 : Math.max(0, count);
    }

    /** Whose death spends the budget: players, never companions or other bots. */
    public static boolean counts(Character chr) {
        return chr != null && !(chr.getClient() instanceof BotClient) && !BotHelpers.isBot(chr);
    }

    /** A run with a positive budget starts counting; 0 leaves it off. */
    public void arm(int deaths) {
        if (deaths > 0) {
            left.set(deaths);
        }
    }

    public boolean isOn() {
        return left.get() >= 0;
    }

    /** Deaths left, or -1 while the count is off (which hides the panel). */
    public int left() {
        return left.get();
    }

    /** GM override: a negative value turns the count off for the rest of the run. */
    public void set(int deaths) {
        left.set(Math.max(-1, deaths));
    }

    /**
     * One player death: spends one while any are left; with none left the first such death returns EXHAUSTED (the run
     * ends) and every later one OFF, so the run ends once.
     */
    public Charge charge() {
        int before = left.getAndUpdate(v -> v > 0 ? v - 1 : v);
        if (before > 0) {
            return Charge.SPENT;
        }
        if (before == 0 && ended.compareAndSet(false, true)) {
            return Charge.EXHAUSTED;
        }
        return Charge.OFF;
    }
}
