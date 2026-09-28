package server.life;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The party EXP split (GMS formula, see {@link Monster}) and who it is measured against. With
 * {@code COMPANION_EXP_SHARE} (docs/BOT_MANAGEMENT_PLAN.md decision 17) roster companions still earn their own cut of
 * a party kill, measured against the whole party as before, while every other member splits as if the companions were
 * absent: their levels leave the level total, they are not sharers for the party bonus, and the MVP is the top
 * damager among the others. Holy Symbol's party strength is left as it was. Without companions this is exactly the
 * stock split.
 */
public final class PartyExpSplit {
    private static final float BONUS_PER_SHARER = 0.05f;

    private PartyExpSplit() {
    }

    /** What one member's cut is measured against: the level total, the sharers and the MVP (may be null). */
    public record Basis<T>(int totalLevel, int sharers, T mvp) {
        /** The party bonus multiplier: 5% per sharer once there are two. */
        public float bonusMod() {
            return sharers > 1 ? BONUS_PER_SHARER * sharers : 0.0f;
        }
    }

    /**
     * The basis for each of {@code members} (those on the map in the level range). {@code damage}: every party member
     * that damaged the mob; the MVP is its top damager (the first one met on a tie, as the stock loop picks).
     */
    public static <T> Map<T, Basis<T>> bases(List<T> members, ToIntFunction<T> level, Predicate<T> isCompanion,
                                             Map<T, Long> damage) {
        Map<T, Boolean> asked = new HashMap<>();
        Predicate<T> companion = member -> asked.computeIfAbsent(member, isCompanion::test); // one lookup each
        Basis<T> all = basis(members, level, damage, member -> true);
        List<T> others = new ArrayList<>();
        for (T member : members) {
            if (!companion.test(member)) {
                others.add(member);
            }
        }
        Basis<T> withoutCompanions = others.size() == members.size() ? all
                : basis(others, level, damage, candidate -> !companion.test(candidate));
        Map<T, Basis<T>> out = new HashMap<>();
        for (T member : members) {
            out.put(member, companion.test(member) ? all : withoutCompanions);
        }
        return out;
    }

    /** One member's share of {@code exp} before the party bonus: its level's part of the total, plus the MVP part. */
    public static float share(int level, int totalLevel, boolean mvp, float exp, float commonMod, float mvpMod) {
        float playerExp = (commonMod * level) / totalLevel;
        if (mvp) {
            playerExp += mvpMod;
        }
        return playerExp * exp;
    }

    private static <T> Basis<T> basis(List<T> members, ToIntFunction<T> level, Map<T, Long> damage,
                                      Predicate<T> mayBeMvp) {
        int totalLevel = 0;
        for (T member : members) {
            totalLevel += level.applyAsInt(member);
        }
        long maxDamage = 0;
        T mvp = null;
        for (Map.Entry<T, Long> entry : damage.entrySet()) {
            if (mayBeMvp.test(entry.getKey()) && maxDamage < entry.getValue()) {
                maxDamage = entry.getValue();
                mvp = entry.getKey();
            }
        }
        return new Basis<>(totalLevel, members.size(), mvp);
    }
}
