package server.living.population.events;

import server.living.population.CitizenSession.Activity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The one world-wide bias term of the citizens' day (docs/WORLD_SYSTEMS_PLAN.md N4.1): what the running world events
 * change, read by PresenceScheduler (plan weights, target maps, avoided fields), Market (stall cap, asks),
 * CitizenMiniGames and the drop game (the festival town). Immutable; WorldEvents swaps it whole as events set and
 * clear their {@link Effect}, and an effect past its time counts for nothing even if nobody cleared it.
 */
public final class WorldModifier {
    public static final WorldModifier NONE = new WorldModifier(List.of());
    /** {@link Effect#avoidBelow} for a field every citizen avoids. */
    public static final int EVERYONE = Integer.MAX_VALUE;

    /**
     * What one event changes until {@code untilMs}. {@code region}: the citizens it is for ("" = all).
     * {@code weights}: an activity's plan weight times this; {@code targets}: where a plan of the activity goes.
     * {@code stallTimes}: the stall cap times this. {@code askShare}: stall asks times this. {@code holdPrices}: sales
     * and unsold lines teach the price index and the learned asks nothing. {@code festivalTown}: the town minigames and
     * the drop game are at (0 none). {@code avoidMap}: a field citizens under level {@code avoidBelow} keep off (0 none).
     */
    public record Effect(String source, String region, Map<Activity, Double> weights, Map<Activity, Integer> targets,
                         int stallTimes, double askShare, boolean holdPrices, int festivalTown, int avoidMap,
                         int avoidBelow, long untilMs) {
        public Effect {
            weights = weights.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(weights));
            targets = targets.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(targets));
        }

        boolean forRegion(String citizenRegion) {
            return region.isEmpty() || region.equals(citizenRegion);
        }

        /** A market day: the stall cap times {@code stalls}, asks at {@code askShare}, MARKET plans {@code weight} up. */
        public static Effect marketDay(String source, int stalls, double askShare, double weight, long untilMs) {
            return new Effect(source, "", Map.of(Activity.MARKET, weight), Map.of(), stalls, askShare, true, 0, 0, 0,
                    untilMs);
        }

        /** The price index and learned asks stay as they are: stalls opened on a market day are still up after it. */
        public static Effect holdPrices(String source, long untilMs) {
            return new Effect(source, "", Map.of(), Map.of(), 1, 1.0d, true, 0, 0, 0, untilMs);
        }

        /** A festival at {@code town} for {@code region}: more PLAY and CASINO plans, PLAY plans go to the town. */
        public static Effect festival(String source, String region, int town, double weight, long untilMs) {
            return new Effect(source, region, Map.of(Activity.PLAY, weight, Activity.CASINO, weight),
                    Map.of(Activity.PLAY, town), 1, 1.0d, false, town, 0, 0, untilMs);
        }

        /** Citizens under {@code level} keep off {@code mapId}. */
        public static Effect avoid(String source, int mapId, int level, long untilMs) {
            return new Effect(source, "", Map.of(), Map.of(), 1, 1.0d, false, 0, mapId, level, untilMs);
        }
    }

    private final List<Effect> effects;

    private WorldModifier(List<Effect> effects) {
        this.effects = effects;
    }

    /** This with {@code effect} set in place of its source's last one. */
    public WorldModifier with(Effect effect) {
        List<Effect> next = new ArrayList<>(without(effect.source()).effects);
        next.add(effect);
        return new WorldModifier(List.copyOf(next));
    }

    /** This without {@code source}'s effect. */
    public WorldModifier without(String source) {
        List<Effect> next = new ArrayList<>(effects);
        next.removeIf(effect -> effect.source().equals(source));
        return next.isEmpty() ? NONE : new WorldModifier(List.copyOf(next));
    }

    public boolean has(String source) {
        return effects.stream().anyMatch(effect -> effect.source().equals(source));
    }

    private List<Effect> live() {
        if (effects.isEmpty()) {
            return effects;
        }
        long now = System.currentTimeMillis();
        return effects.stream().filter(effect -> now < effect.untilMs()).toList();
    }

    /** A plan's weight for {@code activity}: never more than 0 when it was 0 (an activity the cohort does not do). */
    public int weigh(Activity activity, String region, int weight) {
        if (weight <= 0) {
            return weight;
        }
        double out = weight;
        for (Effect effect : live()) {
            if (effect.forRegion(region)) {
                out *= effect.weights().getOrDefault(activity, 1.0d);
            }
        }
        return Math.max(1, (int) Math.round(out));
    }

    /** Where a plan of {@code activity} goes instead of the citizen's own choice; 0 for its own choice. */
    public int targetMap(Activity activity, String region) {
        for (Effect effect : live()) {
            if (effect.forRegion(region) && effect.targets().containsKey(activity)) {
                return effect.targets().get(activity);
            }
        }
        return 0;
    }

    public int stallCap(int cap) {
        int out = cap;
        for (Effect effect : live()) {
            out *= effect.stallTimes();
        }
        return out;
    }

    public double askShare() {
        double out = 1.0d;
        for (Effect effect : live()) {
            out *= effect.askShare();
        }
        return out;
    }

    public boolean holdsPrices() {
        return live().stream().anyMatch(Effect::holdPrices);
    }

    /** The town a festival is in for citizens of {@code region} (null: any festival); 0 for none. */
    public int festivalTown(String region) {
        for (Effect effect : live()) {
            if (effect.festivalTown() > 0 && (region == null || effect.forRegion(region))) {
                return effect.festivalTown();
            }
        }
        return 0;
    }

    /** Whether a citizen of {@code level} keeps off {@code mapId} now. */
    public boolean avoided(int mapId, int level) {
        for (Effect effect : live()) {
            if (effect.avoidMap() == mapId && mapId != 0 && level < effect.avoidBelow()) {
                return true;
            }
        }
        return false;
    }
}
