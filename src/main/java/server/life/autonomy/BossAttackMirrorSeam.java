package server.life.autonomy;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The core decode seam of the boss attack mirror (P8 G1). A human client that controls a monster
 * reports each attack it plays in MOVE_LIFE, and each hit it takes in TAKE_DAMAGE. This seam
 * decodes the two reports and hands them to the installed {@link Listener}. The living world
 * installs the Agent mirror, which holds every rule and setting. With no listener both calls do
 * nothing.
 *
 * <p>MoveLifeHandler calls {@link #acceptedMove} only for the monster's own controller, after the
 * server checked and replied to the move and before the move is relayed. Server-owned monsters
 * return earlier. TakeDamageHandler calls {@link #reportedHit} for a hit from a monster the server
 * does not own. Both run on the reporting client's I/O thread, so a listener must not block. A
 * listener failure never reaches the handler: it is logged at most once a minute.</p>
 */
public final class BossAttackMirrorSeam {
    private static final Logger log = LoggerFactory.getLogger(BossAttackMirrorSeam.class);
    /** MOVE_LIFE action values for attack1 to attack9: 24 + attack index x 2 + facing (1 = left). */
    private static final int FIRST_ATTACK_ACTION = 24;
    private static final int LAST_ATTACK_ACTION = 41;
    private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;
    private static final long NEVER = Long.MIN_VALUE;

    private static volatile Listener listener;
    private static final AtomicLong lastFailureLogMs = new AtomicLong(NEVER);
    private static final AtomicLong skippedFailures = new AtomicLong();

    private BossAttackMirrorSeam() {
    }

    /** Receives the decoded reports on the client's I/O thread; it must hand the work off, not do it. */
    public interface Listener {
        /**
         * The controller played attack{attackIndex + 1} of {@code part}. {@code pOption} is the
         * unsigned option field, the region mask of a region attack.
         */
        void controllerAttack(Character controller, Monster part, int attackIndex, boolean facingLeft,
                              int pOption, int moveId);

        /**
         * A human client reported a hit from {@code attacker}'s attack or skill
         * ({@code damageFrom} 0 or more, as TAKE_DAMAGE sends it).
         */
        void reportedHit(Character human, Monster attacker, int damageFrom, int damage);

        /**
         * P12 G1: {@code bomb} blows itself up, just before the map kills it (its self-destruct HP or its timer). Each
         * human client hits its own player with the blast; the listener decides whom else it hits. On the thread that
         * kills it.
         */
        default void selfDestructed(Monster bomb) {
        }
    }

    /** Installs the listener (null removes it) and starts the failure log afresh. */
    public static void install(Listener installed) {
        listener = installed;
        lastFailureLogMs.set(NEVER);
        skippedFailures.set(0);
    }

    /**
     * A move the server accepted from {@code part}'s controller, with the action value as the
     * handler relays it (a refused attack is -1). Only attack actions 24 to 41 reach the listener.
     */
    public static void acceptedMove(Character controller, Monster part, int rawActivity, int pOption, int moveId) {
        Listener current = listener;
        if (current == null || rawActivity < FIRST_ATTACK_ACTION || rawActivity > LAST_ATTACK_ACTION) {
            return;
        }
        try {
            current.controllerAttack(controller, part, (rawActivity - FIRST_ATTACK_ACTION) >> 1,
                    (rawActivity & 1) == 1, pOption & 0xFFFF, moveId);
        } catch (RuntimeException failure) {
            failed("controllerAttack", part, failure, System.currentTimeMillis());
        }
    }

    /**
     * A hit a human client reported from {@code attacker}. Only a hit from an attack or a skill
     * ({@code damageFrom} 0 or more) reaches the listener, not touch damage.
     */
    public static void reportedHit(Character human, Monster attacker, int damageFrom, int damage) {
        Listener current = listener;
        if (current == null || damageFrom < 0) {
            return;
        }
        try {
            current.reportedHit(human, attacker, damageFrom, damage);
        } catch (RuntimeException failure) {
            failed("reportedHit", attacker, failure, System.currentTimeMillis());
        }
    }

    /**
     * P12 G1: a self-destructing monster blows up (MapleMap, just before it kills the bomb, while it is still alive).
     * Called for every self-destruct kill; the listener filters.
     */
    public static void selfDestructed(Monster bomb) {
        Listener current = listener;
        if (current == null || bomb == null) {
            return;
        }
        try {
            current.selfDestructed(bomb);
        } catch (RuntimeException failure) {
            failed("selfDestructed", bomb, failure, System.currentTimeMillis());
        }
    }

    /** Logs a listener failure unless one was logged in the last minute; the next line counts the skipped ones. */
    static void failed(String call, Monster part, RuntimeException failure, long nowMs) {
        long last = lastFailureLogMs.get();
        if (last != NEVER && nowMs - last < FAILURE_LOG_INTERVAL_MS
                || !lastFailureLogMs.compareAndSet(last, nowMs)) {
            skippedFailures.incrementAndGet();
            return;
        }
        log.warn("Boss mirror listener failed in {} mob={} oid={} skipped={}", call, part.getId(),
                part.getObjectId(), skippedFailures.getAndSet(0), failure);
    }
}
