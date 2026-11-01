package client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The EXP and drop rate buffs bought with Family reputation (FamilyUseHandler). They are kept by character id for
 * the server's lifetime, so a channel change or relog (a new Character object) keeps a buff already paid for.
 * A second buff of the same kind keeps the higher rate and the later end.
 */
public final class FamilyBuffs {
    private record Buff(float rate, long until) {
    }

    private static final Map<Integer, Buff> EXP = new ConcurrentHashMap<>();
    private static final Map<Integer, Buff> DROP = new ConcurrentHashMap<>();

    private FamilyBuffs() {
    }

    public static void grantExp(int chrId, float rate, long until) {
        grant(EXP, chrId, rate, until);
    }

    public static void grantDrop(int chrId, float rate, long until) {
        grant(DROP, chrId, rate, until);
    }

    /** The multiplier on EXP from hunting, 1 when no buff is running. */
    public static float expRate(int chrId, long now) {
        return rate(EXP, chrId, now);
    }

    /** The multiplier on monster drop chances, 1 when no buff is running. */
    public static float dropRate(int chrId, long now) {
        return rate(DROP, chrId, now);
    }

    /** "2" for 2.0, "1.5" for 1.5, as the Family window writes rates. */
    public static String formatRate(float rate) {
        return rate == (int) rate ? String.valueOf((int) rate) : String.valueOf(rate);
    }

    static void clear() {
        EXP.clear();
        DROP.clear();
    }

    private static void grant(Map<Integer, Buff> buffs, int chrId, float rate, long until) {
        buffs.merge(chrId, new Buff(rate, until), (old, add) -> old.until() <= System.currentTimeMillis()
                ? add
                : new Buff(Math.max(old.rate(), add.rate()), Math.max(old.until(), add.until())));
    }

    private static float rate(Map<Integer, Buff> buffs, int chrId, long now) {
        Buff buff = buffs.get(chrId);
        if (buff == null) {
            return 1.0f;
        }
        if (buff.until() <= now) {
            buffs.remove(chrId, buff);
            return 1.0f;
        }
        return buff.rate();
    }
}
