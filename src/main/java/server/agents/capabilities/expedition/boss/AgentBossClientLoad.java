package server.agents.capabilities.expedition.boss;

import client.Character;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The client-load lever for boss runs (P8). Each attack a companion commits on a boss's battle map sends its packet,
 * effect and sound to every game client on that map, and a long fight with many companions may push a player's 32-bit
 * client out of memory. ATTACK_INTERVAL_FLOOR_MS spaces the attacks of each INSIDE member on its run's battle map by
 * at least that long; 0, the shipped value, leaves every attack's own pace. P12 widens the floor to the field scope: any
 * Agent on a map with no event where a field boss whose key is on stands (AgentBossField.near). The attacks INSIDE
 * members commit are also counted per run, for the boss mirror's summary line (botAttacks=).
 *
 * <p>AgentCombatAttackRuntime calls {@link #attackCommitted} and {@link #attackFloorMs} once per committed attack, on
 * the Agent's mailbox. Memory only; the floor is read from agent-engine.yaml at call time.</p>
 */
public final class AgentBossClientLoad {
    /** The attacks committed in each open run, by its session (identity); closed runs are pruned on a new run. */
    private static final Map<AgentBossRunSession, AtomicLong> ATTACKS = new ConcurrentHashMap<>();

    private AgentBossClientLoad() {
    }

    /**
     * The least time before the Agent's next attack: ATTACK_INTERVAL_FLOOR_MS while it is an INSIDE member standing on
     * its run's battle map, or (P12) while it stands beside a field boss whose key is on, else 0 (no floor).
     */
    public static int attackFloorMs(Character bot) {
        int floorMs = attackIntervalFloorMs();
        return floorMs > 0 && (battleRun(bot) != null || AgentBossField.near(bot)) ? floorMs : 0;
    }

    /** Counts one committed attack under the Agent's run while it is an INSIDE member on the run's battle map. */
    public static void attackCommitted(Character bot) {
        AgentBossRunSession session = battleRun(bot);
        if (session == null) {
            return;
        }
        AtomicLong attacks = ATTACKS.get(session);
        if (attacks == null) {
            ATTACKS.keySet().removeIf(run -> AgentBossRunRegistry.byId(run.sessionId()) != run);
            attacks = ATTACKS.computeIfAbsent(session, ignored -> new AtomicLong());
        }
        attacks.incrementAndGet();
    }

    /** The attacks the run's INSIDE members committed on its battle map since the run began. */
    static long attacks(AgentBossRunSession session) {
        AtomicLong attacks = ATTACKS.get(session);
        return attacks == null ? 0L : attacks.get();
    }

    /** The open run the Agent is an INSIDE member of while it stands on a map of that run's fight (P18), else null. */
    private static AgentBossRunSession battleRun(Character bot) {
        if (bot == null) {
            return null;
        }
        AgentBossRunSession session = AgentBossRunRegistry.forMember(bot.getId());
        AgentBossRunMember member = session == null ? null : session.member(bot.getId());
        return member != null && member.phase() == AgentBossRunMember.Phase.INSIDE
                && session.definition().fightMap(bot.getMapId()) ? session : null;
    }

    static int attackIntervalFloorMs() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossClientLoad.ATTACK_INTERVAL_FLOOR_MS");
    }

    /** Forgets every count; tests only. */
    static void resetForTest() {
        ATTACKS.clear();
    }
}
