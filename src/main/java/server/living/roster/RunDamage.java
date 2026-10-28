package server.living.roster;

import server.life.Monster;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Who did how much of a companion run's damage (companion_performance.damage_share, docs/BOT_CONCEPT_PLAN.md F6.4):
 * measuring only, for the crew's rival banter and the records. A party quest run counts every monster its members
 * kill while it runs ({@link #killed}: each one's damage on it from the monster's own record); a boss run counts its
 * bosses ({@link #add}, from CompanionBossRuns as each boss part dies). At the run's end each member's share of what
 * the run dealt is read and the tally dropped ({@link #end}). A kill by no member of a running party quest costs one
 * empty-map check; there are no locks on the kill path.
 */
public final class RunDamage {
    /** One run's tally: member id -> damage dealt. */
    private static final class Tally {
        final String key;
        final Map<Integer, AtomicLong> dealt = new ConcurrentHashMap<>();

        Tally(String key) {
            this.key = key;
        }
    }

    /** A running party quest's members -> its tally (a kill looks its attackers up here). */
    private final Map<Integer, Tally> byMember = new ConcurrentHashMap<>();
    /** Every open tally by its run key ("pq:kpq:<session>", "boss:<session>"). */
    private final Map<String, Tally> byKey = new ConcurrentHashMap<>();

    /** A party quest run got in: its members' kills count toward it from now (a member's older run is let go). */
    void start(String runKey, Collection<Integer> memberIds) {
        Tally tally = new Tally(runKey);
        for (int id : memberIds) {
            tally.dealt.put(id, new AtomicLong());
            Tally before = byMember.put(id, tally);
            if (before != null && before != tally) {
                before.dealt.remove(id);
            }
        }
        byKey.put(runKey, tally);
    }

    /**
     * A monster died (LivingWorldHooks.mobKilled, inside its monitor): each attacker in a running party quest adds its
     * damage on it to that run. Nothing at all to do while no party quest run is going.
     */
    public void killed(Monster monster, Set<Integer> attackerIds) {
        if (byMember.isEmpty() || monster == null || attackerIds == null) {
            return;
        }
        for (int id : attackerIds) {
            Tally tally = byMember.get(id);
            AtomicLong own = tally == null ? null : tally.dealt.get(id);
            if (own != null) {
                own.addAndGet(monster.damageFrom(id));
            }
        }
    }

    /** {@code damage} dealt by {@code attackerId} toward run {@code runKey} (a boss run's boss parts). */
    void add(String runKey, int attackerId, long damage) {
        if (damage <= 0L) {
            return;
        }
        byKey.computeIfAbsent(runKey, Tally::new).dealt.computeIfAbsent(attackerId, id -> new AtomicLong())
                .addAndGet(damage);
    }

    /**
     * The run is over: each member's share (0..1) of the damage its members dealt, and the tally is dropped. Empty when
     * nothing was dealt (or no tally was kept), so no share is made up.
     */
    Map<Integer, Double> end(String runKey) {
        Tally tally = byKey.remove(runKey);
        if (tally == null) {
            return Map.of();
        }
        byMember.entrySet().removeIf(entry -> entry.getValue() == tally);
        long total = 0L;
        for (AtomicLong dealt : tally.dealt.values()) {
            total += dealt.get();
        }
        Map<Integer, Double> shares = new HashMap<>();
        if (total <= 0L) {
            return shares;
        }
        for (Map.Entry<Integer, AtomicLong> member : tally.dealt.entrySet()) {
            shares.put(member.getKey(), member.getValue().get() / (double) total);
        }
        return shares;
    }

    /** True while any party quest run is tallied (tests). */
    boolean tracking() {
        return !byMember.isEmpty();
    }
}
