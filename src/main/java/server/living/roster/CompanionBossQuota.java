package server.living.roster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Seat;
import server.agents.capabilities.expedition.boss.AgentBossRunService.Enlisted;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionBossLog;
import server.expeditions.ExpeditionType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Companions' daily boss entries (P8, plan decisions Q3 and Q4): a companion spends its own entry of a boss that keeps
 * an entry log (ExpeditionBossLog: Zakum), logged only when it enters the fight; players keep the stock count and log.
 * Before a party companion or a bench ally is enlisted, companion-ops asks the stock count for it ({@link #admit}): one
 * with no entry left is not enlisted (its caller says so in its words), one allowed is kept here for the run. The boss
 * log's hook (LivingWorldHooks.bossQuota) then answers that companion from memory ({@link #answer}): its sign-up inside
 * the entry NPC's script runs no JDBC (that script runs on the run's tick inside the NPC script lock), and the Start
 * logs nothing for it. When the run adopts it into the fight, companion-ops logs its entry once ({@link #entered}); a
 * companion that missed the start is never charged. Only allowed answers are kept, each enlist asks again, and the
 * run's end forgets them ({@link #runEnded}), so a GM's !resetboss never meets a stale refusal. A boss with no entry
 * log (Easy Balrog) is never counted and nothing of it is kept. Memory only, but for the two boss log calls on
 * companion-ops; the answers are read on any thread.
 */
final class CompanionBossQuota {
    private static final Logger log = LoggerFactory.getLogger(CompanionBossQuota.class);

    /** The boss log as the quota asks it (ExpeditionBossLog); tests use a fake. */
    interface Ports {
        /** True when entries of {@code type}'s boss are counted (memory). */
        boolean limits(ExpeditionType type);

        /** True while the character has an entry of {@code expedition}'s boss left today: the stock count (JDBC). */
        boolean entryLeft(int characterId, Expedition expedition);

        /** Logs one entry of {@code type}'s boss for the character: the stock insert (JDBC). */
        boolean recordEntry(int characterId, ExpeditionType type);

        Ports LIVE = new Ports() {
            @Override
            public boolean limits(ExpeditionType type) {
                return ExpeditionBossLog.limits(type);
            }

            @Override
            public boolean entryLeft(int characterId, Expedition expedition) {
                // the channel as Expedition.addMember reads it; the hook has no answer yet, so the stock count runs
                int channel = expedition.getRecruitingMap().getChannelServer().getId();
                return ExpeditionBossLog.attemptBoss(characterId, channel, expedition, false);
            }

            @Override
            public boolean recordEntry(int characterId, ExpeditionType type) {
                return ExpeditionBossLog.recordEntry(characterId, type);
            }
        };
    }

    /**
     * A companion allowed into {@code expedition}'s run: its boss, its run's session (0 until the enlist answered) and
     * whether its entry was logged.
     */
    private record Allowed(Expedition expedition, ExpeditionType type, long sessionId, boolean entered) {
    }

    private final CompanionBossRuns.OpenRuns open;
    private final Ports ports;
    /** Companion id -> what this run's pre-check allowed it; written on companion-ops, read on any thread. */
    private final Map<Integer, Allowed> allowed = new ConcurrentHashMap<>();

    CompanionBossQuota(CompanionBossRuns.OpenRuns open) {
        this(open, Ports.LIVE);
    }

    CompanionBossQuota(CompanionBossRuns.OpenRuns open, Ports ports) {
        this.open = open;
        this.ports = ports;
    }

    /**
     * On companion-ops, before the companion is enlisted for {@code definition}'s boss: true when it may be. For a boss
     * with an entry log, the stock count is asked each time; with an entry left, the answer is kept for {@code
     * expedition}, and with none, it is false and nothing is kept. A boss with no entry log: true, nothing asked.
     */
    boolean admit(AgentBossRunDefinition definition, Expedition expedition, int companionId) {
        if (!ports.limits(definition.type())) {
            return true;
        }
        if (!ports.entryLeft(companionId, expedition)) {
            log.info("Boss quota: companion {} has no {} entry left today; not enlisted", companionId,
                    definition.key());
            return false;
        }
        allowed.put(companionId, new Allowed(expedition, definition.type(), 0L, false));
        return true;
    }

    /**
     * On companion-ops, as the enlist of {@code asked} for {@code expedition} answered: those it took are kept under
     * their run's session, the rest are forgotten.
     */
    void enlisted(Expedition expedition, Enlisted enlisted, List<Integer> asked) {
        for (int id : asked) {
            Allowed kept = allowed.get(id);
            if (kept == null || kept.expedition() != expedition) {
                continue;
            }
            if (enlisted.sessionId() != 0L && enlisted.accepted().contains(id)) {
                allowed.replace(id, kept, new Allowed(expedition, kept.type(), enlisted.sessionId(), false));
            } else {
                allowed.remove(id, kept);
            }
        }
    }

    /**
     * The boss log's question for the character and {@code expedition} (memory, any thread): TRUE for a companion
     * allowed for it, so the count and the log are skipped; else null, the stock count and log. A companion of the open
     * run for it with no allowed answer gets the stock path too, with one warning.
     */
    Boolean answer(int characterId, Expedition expedition, boolean logEntry) {
        if (expedition == null) {
            return null;
        }
        Allowed kept = allowed.get(characterId);
        if (kept != null && kept.expedition() == expedition) {
            return Boolean.TRUE;
        }
        if (open.anchorIn(expedition, characterId) != 0) {
            log.warn("Boss quota: companion {} of the open {} run has no allowed answer; the stock count{} runs",
                    characterId, expedition.getType(), logEntry ? " and log" : "");
        }
        return null;
    }

    /**
     * True when the member of {@code seat} is a companion allowed into that run whose entry is not logged yet (memory,
     * any thread): the run's tick hands only these to companion-ops.
     */
    boolean counts(Seat seat) {
        Allowed kept = allowed.get(seat.agentCharacterId());
        return kept != null && kept.sessionId() == seat.sessionId() && !kept.entered();
    }

    /**
     * On companion-ops: the member of {@code seat} went into the fight, so its entry is logged, once per member and
     * run; nothing for a member this run never allowed (a boss with no entry log, or the stock path).
     */
    void entered(Seat seat) {
        int id = seat.agentCharacterId();
        Allowed kept = allowed.get(id);
        if (kept == null || kept.sessionId() != seat.sessionId() || kept.entered()
                || !allowed.replace(id, kept, new Allowed(kept.expedition(), kept.type(), kept.sessionId(), true))) {
            return;
        }
        boolean logged = ports.recordEntry(id, kept.type());
        log.info("Boss quota: entry logged={} session={} boss={} member={}", logged, seat.sessionId(),
                seat.bossKey(), id);
    }

    /** On companion-ops: run {@code sessionId} ended, and its allowed answers are forgotten. */
    void runEnded(long sessionId) {
        allowed.values().removeIf(kept -> kept.sessionId() == sessionId);
    }
}
