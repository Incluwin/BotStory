package server.pvp.probe;

import client.BotClient;
import client.Character;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The V0 probes of docs/PVP_PLAN.md section 3. Measurement only: nothing here changes play. Each hook returns at once
 * unless a GM started that probe ({@code !pvp}).
 */
public final class PvpProbe {
    private static final Logger log = LoggerFactory.getLogger(PvpProbe.class);
    private static final Map<Integer, List<Long>> moveWatches = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> attackWatchesUntil = new ConcurrentHashMap<>();
    private static final DamageMeter METER = new DamageMeter();

    private PvpProbe() {
    }

    public static DamageMeter meter() {
        return METER;
    }

    /** V0.3: {@code MovePlayerHandler} asks before it calls {@link #onMove}. */
    public static boolean watchingMoves(int characterId) {
        return !moveWatches.isEmpty() && moveWatches.containsKey(characterId);
    }

    public static void onMove(Character chr) {
        List<Long> arrivals = moveWatches.get(chr.getId());
        if (arrivals != null) {
            synchronized (arrivals) {
                arrivals.add(System.currentTimeMillis());
            }
        }
    }

    public static void watchMoves(Character gm, int seconds) {
        moveWatches.put(gm.getId(), new ArrayList<>());
        TimerManager.getInstance().schedule(() -> finishMoves(gm), seconds * 1000L);
    }

    private static void finishMoves(Character gm) {
        List<Long> arrivals = moveWatches.remove(gm.getId());
        if (arrivals == null) {
            return;
        }
        GapStats stats;
        synchronized (arrivals) {
            stats = GapStats.of(new ArrayList<>(arrivals));
        }
        report(gm, "PvP probe move " + gm.getName() + ": packets=" + arrivals.size() + " gaps=" + stats.gaps()
                + " median=" + stats.medianMs() + "ms p90=" + stats.p90Ms() + "ms max=" + stats.maxMs() + "ms mean="
                + stats.meanMs() + "ms over1s=" + stats.overOneSecond());
    }

    /** V0.4: every attack packet {@code gm} sends for {@code seconds}, as parsed. */
    public static void watchAttacks(Character gm, int seconds) {
        attackWatchesUntil.put(gm.getId(), System.currentTimeMillis() + seconds * 1000L);
        TimerManager.getInstance().schedule(() -> {
            if (attackWatchesUntil.remove(gm.getId()) != null) {
                gm.yellowMessage("PvP probe attacks: done.");
            }
        }, seconds * 1000L);
    }

    /** Called by {@code AbstractDealDamageHandler.parseDamage}, so only for attacks a client sent. */
    public static void onAttackParsed(Character chr, AttackInfo attack) {
        if (attackWatchesUntil.isEmpty() || !attackWatchesUntil.containsKey(chr.getId())) {
            return;
        }
        Point at = chr.getPosition();
        report(chr, String.format("PvP probe attack %s %s skill=%d lv=%d mobs=%d lines=%d display=%d direction=%d "
                        + "stance=0x%02X facingLeft=%b speed=%d rangedir=%d pos=%d,%d",
                chr.getName(), attack.magic ? "MAGIC" : attack.ranged ? "RANGED" : "CLOSE", attack.skill,
                attack.skilllevel, attack.numAttacked, attack.numDamage, attack.display, attack.direction,
                attack.stance & 0xFF, (attack.stance & 0x80) != 0, attack.speed, attack.rangedirection,
                at == null ? 0 : at.x, at == null ? 0 : at.y));
    }

    /** Called by {@code AbstractDealDamageHandler.applyAttack}: humans and Agents alike feed the meter. */
    public static void onAttackApplied(Character chr, AttackInfo attack) {
        if (!METER.running()) {
            return;
        }
        METER.record(chr.getId(), chr.getJob().getId(), chr.getLevel(), !(chr.getClient() instanceof BotClient),
                bestTarget(attack), System.currentTimeMillis());
    }

    /** The damage of the attack's best-hit target: one target's lines summed. */
    static long bestTarget(AttackInfo attack) {
        long best = 0;
        if (attack.targets != null) {
            for (AttackTarget target : attack.targets.values()) {
                long sum = 0;
                for (int line : target.damageLines()) {
                    sum += Math.max(0, line);
                }
                best = Math.max(best, sum);
            }
        }
        return best;
    }

    private static void report(Character gm, String line) {
        log.info(line);
        gm.yellowMessage(line);
    }
}
