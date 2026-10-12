package server.agents.capabilities.expedition.boss;

import client.BotClient;
import client.Character;
import net.server.services.task.channel.ServerMobAutonomyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.combat.AgentCombatDamageRuntime;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.life.Monster;
import server.life.MonsterStats;
import server.life.autonomy.BossAction;
import server.life.autonomy.BossAttackMirrorSeam;
import server.life.autonomy.BossMirrorGeometry;
import server.life.autonomy.ServerMobActionCatalog;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * The Agent half of the boss attack mirror (P8 G1). A human client that controls a boss part, such as a Zakum arm,
 * plays the part's attacks itself, so without the mirror the server never hits the companions beside that player.
 * The core seam (BossAttackMirrorSeam) hands each attack the controller reports to {@link #controllerAttack}, and
 * this class decides whether, when and whom it hits.
 *
 * <p>An attack counts only while MODE is not OFF, for a real part (not fake) that AgentBossScope guards (so not
 * server-owned and not controlled by an Agent), reported by the part's own engaged controller, and only when the part's
 * event has an open companion run (AgentBossRunRegistry.forEvent: its expedition's, or a party-instance run's), or for
 * the GM trial (the trial GM's part on the trial map). The client
 * repeats an attack's action while it plays, so one report per attack animation counts. At the attack's attackAfter,
 * on the shared timer, the part must still be alive, on its map and client-driven. Then every INSIDE or LEAVING member
 * of the run that stands in the attack's shape gets the hit on its own mailbox (in the trial: every companion the
 * trial GM leads). Humans are never hit by the mirror: their clients report their own hits, which only feed the
 * calibration counts. OBSERVE logs what APPLY would do and changes no HP.</p>
 *
 * <p>{@link #controllerAttack} and {@link #reportedHit} run on the reporting client's I/O thread and touch memory
 * only. Tunables are read from agent-engine.yaml at call time; {@link #overrideMode} beats the yaml MODE.</p>
 */
public final class AgentBossAttackMirror implements BossAttackMirrorSeam.Listener {
    private static final Logger log = LoggerFactory.getLogger(AgentBossAttackMirror.class);
    private static final AgentBossAttackMirror LIVE = new AgentBossAttackMirror(Ports.LIVE);
    /** The key of the GM trial's counters, beside the open runs' sessions. */
    private static final Object TRIAL = new Object();

    /** What the mirror does with the attacks it accepts. */
    public enum Mode {
        /** Nothing: no hits and no calibration. */
        OFF,
        /** Logs each accepted attack with the members and humans in its shape; no HP changes. */
        OBSERVE,
        /** Hits the members in each accepted attack's shape. */
        APPLY
    }

    /**
     * One attack's calibration counts: hits human clients reported, reports that matched a prediction of the mirror,
     * reports it did not predict, and predictions no report confirmed. {@code attackIndex} is 0 for attack1.
     */
    public record CalibrationCounts(int mobId, int attackIndex, long reported, long matched,
                                    long reportedNotPredicted, long predictedNotReported) {
    }

    /** What the mirror needs from the server; tests pass fakes. */
    interface Ports {
        long nowMs();

        /** Runs {@code impact} on the shared timer after {@code delayMs} real milliseconds. */
        void schedule(Runnable impact, long delayMs);

        /** Runs {@code hit} on the Agent's own mailbox. */
        void dispatch(AgentRuntimeEntry entry, Runnable hit);

        RandomGenerator random();

        Ports LIVE = new Ports() {
            @Override
            public long nowMs() {
                return System.currentTimeMillis();
            }

            @Override
            public void schedule(Runnable impact, long delayMs) {
                AgentSchedulerRuntime.schedule(impact, delayMs);
            }

            @Override
            public void dispatch(AgentRuntimeEntry entry, Runnable hit) {
                AgentSchedulerRuntime.schedule(entry, hit, 0L);
            }

            @Override
            public RandomGenerator random() {
                return ThreadLocalRandom.current();
            }
        };
    }

    private final Ports ports;
    private volatile Mode override;
    /** When each attack of each part was last accepted: the start of its dedup window. */
    private final Map<AttackKey, Long> lastAccepted = new ConcurrentHashMap<>();
    /** The counters of each open run (by its session) and of the trial ({@link #TRIAL}). */
    private final Map<Object, RunStats> runStats = new ConcurrentHashMap<>();
    private final Object calibrationLock = new Object();
    /** Under {@link #calibrationLock}: hits the mirror predicted on humans, by when. */
    private final Map<HitKey, Long> predictions = new HashMap<>();
    /** Under {@link #calibrationLock}: hits human clients reported that no prediction matched yet. */
    private final Map<HitKey, Report> reports = new HashMap<>();
    /** Under {@link #calibrationLock}. */
    private final Map<MobAttack, Counters> counters = new HashMap<>();

    AgentBossAttackMirror(Ports ports) {
        this.ports = ports;
    }

    /** The mirror the living world installs into the core seam. */
    public static AgentBossAttackMirror get() {
        return LIVE;
    }

    /** Sets the runtime mode (the GM's !bossmirror), which beats MODE; null goes back to MODE. */
    public void overrideMode(Mode mode) {
        override = mode;
    }

    /** The mode in force: the runtime override, else MODE. */
    public Mode mode() {
        Mode forced = override;
        return forced != null ? forced : configuredMode();
    }

    @Override
    public void controllerAttack(Character controller, Monster part, int attackIndex, boolean facingLeft,
                                 int pOption, int moveId) {
        // every client-driven monster's attack comes here: the scope check first, it fails fast off boss maps
        if (controller == null || part == null || part.isFake() || !AgentBossScope.guards(part)
                || mode() == Mode.OFF) {
            return;
        }
        MapleMap map = part.getMap();
        EventInstanceManager event = map.getEventInstance();
        long now = ports.nowMs();
        Scope scope = scope(map, event, controller, now);
        if (scope == null) {
            return;
        }
        RunStats stats = scope.stats();
        stats.attacks.incrementAndGet();
        if (!AgentBossScope.engaged(part) || part.getController() != controller) {
            stats.unengaged.incrementAndGet();
        } else {
            accept(scope, part, attackIndex, facingLeft, pOption, moveId, event, now);
        }
        summarize(stats, now);
    }

    @Override
    public void reportedHit(Character human, Monster attacker, int damageFrom, int damage) {
        if (human == null || attacker == null || human.getClient() instanceof BotClient || attacker.isFake()
                || !AgentBossScope.guards(attacker) || mode() == Mode.OFF) {
            return;
        }
        MapleMap map = attacker.getMap();
        EventInstanceManager event = map.getEventInstance();
        boolean inRun = event != null && AgentBossRunRegistry.forEvent(event) != null;
        if (!inRun && AgentBossScope.trialMap() != map) {
            return;
        }
        long now = ports.nowMs();
        Point at = human.getPosition();
        Point from = attacker.getPosition();
        int dx = at == null || from == null ? 0 : at.x - from.x;
        int dy = at == null || from == null ? 0 : at.y - from.y;
        Long acceptedAt = lastAccepted.get(new AttackKey(attacker, damageFrom));
        long delayMs = acceptedAt == null ? -1L : now - acceptedAt;
        synchronized (calibrationLock) {
            expire(now);
            HitKey key = new HitKey(attacker, damageFrom, human.getId());
            Counters counted = counters(attacker.getId(), damageFrom);
            counted.reported++;
            if (predictions.remove(key) != null) {
                counted.matched++;
                return;
            }
            Report earlier = reports.put(key, new Report(now, dx, dy, delayMs));
            if (earlier != null) {
                unpredicted(key, earlier);
            }
        }
    }

    /** Each attack's calibration counts, by mob id and attack; reports and predictions past the window count. */
    public List<CalibrationCounts> calibration() {
        synchronized (calibrationLock) {
            expire(ports.nowMs());
            return counters.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(MobAttack.ORDER))
                    .map(entry -> new CalibrationCounts(entry.getKey().mobId(), entry.getKey().attackIndex(),
                            entry.getValue().reported, entry.getValue().matched,
                            entry.getValue().reportedNotPredicted, entry.getValue().predictedNotReported))
                    .toList();
        }
    }

    /** The GM status text: the mode, each open run's and the trial's counters, and the calibration counts. */
    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        Mode forced = override;
        lines.add("Boss mirror mode=" + mode() + (forced == null ? " (agent-engine.yaml)"
                : " (runtime override; agent-engine.yaml says " + configuredMode() + ")"));
        pruneClosedRuns();
        runStats.values().stream().map(RunStats::counts).sorted().forEach(counts -> lines.add("Run " + counts));
        for (CalibrationCounts counts : calibration()) {
            lines.add(counts.mobId() + " attack" + (counts.attackIndex() + 1) + ": reported=" + counts.reported()
                    + " matched=" + counts.matched() + " reportedNotPredicted=" + counts.reportedNotPredicted()
                    + " predictedNotReported=" + counts.predictedNotReported());
        }
        return lines;
    }

    // ---- an accepted attack ---------------------------------------------------------------------------------

    /** The open run of the part's event (forEvent), else the trial when the trial GM reports on the trial map. */
    private Scope scope(MapleMap map, EventInstanceManager event, Character controller, long now) {
        AgentBossRunSession session = event == null ? null : AgentBossRunRegistry.forEvent(event);
        if (session != null) {
            return new Scope(session, 0, stats(session, now));
        }
        int trialGm = AgentBossScope.trialGm();
        if (trialGm > 0 && AgentBossScope.trialMap() == map && controller.getId() == trialGm) {
            return new Scope(null, trialGm, stats(TRIAL, now));
        }
        return null;
    }

    private void accept(Scope scope, Monster part, int attackIndex, boolean facingLeft, int pOption, int moveId,
                        EventInstanceManager event, long now) {
        List<BossAction.OrdinaryAttack> attacks = ServerMobActionCatalog.forMob(part.getId()).attacks();
        if (attackIndex < 0 || attackIndex >= attacks.size()) {
            return;
        }
        BossAction.OrdinaryAttack attack = attacks.get(attackIndex);
        if (!firstReport(part, attackIndex, attack, now)) {
            return;
        }
        RunStats stats = scope.stats();
        stats.mirrored.incrementAndGet();
        List<Integer> regions = BossMirrorGeometry.regions(attack, pOption, ports.random());
        if (attack.hasDistributedRegions()) {
            (mask(regions) == (pOption & 0xFFFF) ? stats.regionsMask : stats.regionsSampled).incrementAndGet();
        }
        Impact impact = new Impact(scope, part, part.getObjectId(), attackIndex, attack, facingLeft, regions,
                ServerMobActionCatalog.jumpAttack(part.getId(), attackIndex), event, pOption, moveId);
        ports.schedule(() -> impact(impact), attack.impactDelayMs());
    }

    /**
     * True for the first report of an attack animation: none of this attack of this part was accepted for
     * min(DEDUP_MAX_MS, max(attackAfter, the full animation)). A new key prunes dead parts and old keys.
     */
    private boolean firstReport(Monster part, int attackIndex, BossAction.OrdinaryAttack attack, long now) {
        long maxWindow = dedupMaxMs();
        long window = Math.min(maxWindow, Math.max(attack.impactDelayMs(),
                ServerMobActionCatalog.resolvedAnimationMs(part.getId(), attackIndex)));
        AttackKey key = new AttackKey(part, attackIndex);
        boolean fresh = !lastAccepted.containsKey(key);
        AtomicBoolean accepted = new AtomicBoolean();
        lastAccepted.compute(key, (ignored, previous) -> {
            boolean due = previous == null || now - previous >= window;
            accepted.set(due);
            return due ? now : previous;
        });
        if (fresh) {
            lastAccepted.entrySet().removeIf(entry -> !entry.getKey().part().isAlive()
                    || now - entry.getValue() > maxWindow);
        }
        return accepted.get();
    }

    /** At attackAfter, on the shared timer: hits the members in the attack's shape, or logs them in OBSERVE. */
    private void impact(Impact hit) {
        Mode mode = mode();
        Monster part = hit.part();
        MapleMap map = part.getMap();
        if (mode == Mode.OFF || !part.isAlive() || map == null || map.getMonsterByOid(hit.oid()) != part
                || ServerMobAutonomyService.isActiveInstance(part)) {
            return;
        }
        long now = ports.nowMs();
        Point origin = part.getPosition();
        MonsterStats partStats = part.getStats();
        boolean noFlip = partStats != null && partStats.getFixedStance() > 0;
        List<Character> targets = new ArrayList<>();
        List<Integer> humans = new ArrayList<>();
        for (Character character : map.getAllPlayers()) {
            if (character == null || !character.isAlive() || character.isHidden()
                    || character.getEventInstance() != hit.event()) {
                continue;
            }
            boolean agent = character.getClient() instanceof BotClient;
            if ((agent && !hit.scope().covers(character.getId()))
                    || !BossMirrorGeometry.hits(hit.attack(), origin, character.getPosition(), noFlip,
                    hit.facingLeft(), hit.regions())) {
                continue;
            }
            if (agent) {
                targets.add(character);
            } else {
                humans.add(character.getId());
                predicted(part, hit.attackIndex(), character.getId(), now);
            }
        }
        RunStats stats = hit.scope().stats();
        if (mode == Mode.OBSERVE) {
            log.info("Boss mirror observe {} mob={} oid={} attack={} facingLeft={} noFlip={} pOption={} moveId={}"
                            + " regions={} origin={},{} agents={} humans={}", stats.label(), part.getId(), hit.oid(),
                    hit.attackIndex() + 1, hit.facingLeft(), noFlip, hit.pOption(), hit.moveId(), hit.regions(),
                    origin == null ? 0 : origin.x, origin == null ? 0 : origin.y,
                    targets.stream().map(Character::getId).toList(), humans);
            summarize(stats, now);
            return;
        }
        AtomicInteger shown = new AtomicInteger();
        int maxShown = maxShownHits();
        int percent = damagePercent();
        boolean shared = sharedHitWindow();
        for (Character bot : targets) {
            AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
            if (entry == null) {
                continue;
            }
            stats.dispatched.incrementAndGet();
            ports.dispatch(entry, () -> count(stats, AgentCombatDamageRuntime.applyMirroredHit(entry, bot, part,
                    hit.attack(), percent, () -> shownHit(shown, maxShown, stats), hit.jumpAttack(), shared)));
        }
        summarize(stats, now);
    }

    /** True for the first MAX_SHOWN_HITS applied hits of one attack: those send a damage packet. */
    private static boolean shownHit(AtomicInteger shown, int maxShown, RunStats stats) {
        if (shown.getAndIncrement() >= maxShown) {
            return false;
        }
        stats.shown.incrementAndGet();
        return true;
    }

    private static void count(RunStats stats, AgentCombatDamageRuntime.MirroredHit result) {
        switch (result) {
            case APPLIED -> stats.applied.incrementAndGet();
            case WINDOW -> stats.window.incrementAndGet();
            case AIRBORNE -> stats.airborne.incrementAndGet();
            case NONE -> {
            }
        }
    }

    private static int mask(List<Integer> regions) {
        int mask = 0;
        for (int region : regions) {
            mask |= 1 << region;
        }
        return mask;
    }

    // ---- counters and logs ----------------------------------------------------------------------------------

    private RunStats stats(Object key, long now) {
        RunStats stats = runStats.get(key);
        if (stats != null) {
            return stats;
        }
        pruneClosedRuns();
        return runStats.computeIfAbsent(key, ignored -> key instanceof AgentBossRunSession session
                ? new RunStats("session=" + session.sessionId(), session, now)
                : new RunStats("session=trial", null, now));
    }

    private void pruneClosedRuns() {
        runStats.keySet().removeIf(key -> key instanceof AgentBossRunSession session
                && AgentBossRunRegistry.byId(session.sessionId()) != session);
    }

    /** One INFO line per run once SUMMARY_LOG_MS has passed since its last one; the counts are since the run began. */
    private void summarize(RunStats stats, long now) {
        long last = stats.lastSummaryMs.get();
        if (now - last < summaryLogMs() || !stats.lastSummaryMs.compareAndSet(last, now)) {
            return;
        }
        log.info("Boss mirror summary {}", stats.counts());
    }

    /** A human in an attack's shape at its impact: the hit its client should report. */
    private void predicted(Monster part, int attackIndex, int characterId, long now) {
        synchronized (calibrationLock) {
            expire(now);
            HitKey key = new HitKey(part, attackIndex, characterId);
            if (reports.remove(key) != null) {
                counters(part.getId(), attackIndex).matched++;
                return;
            }
            if (predictions.put(key, now) != null) {
                counters(part.getId(), attackIndex).predictedNotReported++;
            }
        }
    }

    /** Under {@link #calibrationLock}: counts and drops the reports and predictions older than the window. */
    private void expire(long now) {
        long window = calibrationWindowMs();
        predictions.entrySet().removeIf(entry -> {
            if (now - entry.getValue() <= window) {
                return false;
            }
            counters(entry.getKey().part().getId(), entry.getKey().attackIndex()).predictedNotReported++;
            return true;
        });
        reports.entrySet().removeIf(entry -> {
            if (now - entry.getValue().atMs() <= window) {
                return false;
            }
            unpredicted(entry.getKey(), entry.getValue());
            return true;
        });
    }

    private void unpredicted(HitKey key, Report report) {
        counters(key.part().getId(), key.attackIndex()).reportedNotPredicted++;
        log.info("Boss mirror calibration miss mob={} attack={} oid={} chr={} dx={} dy={} delayMs={}",
                key.part().getId(), key.attackIndex() + 1, key.part().getObjectId(), key.characterId(),
                report.dx(), report.dy(), report.delayMs());
    }

    private Counters counters(int mobId, int attackIndex) {
        return counters.computeIfAbsent(new MobAttack(mobId, attackIndex), ignored -> new Counters());
    }

    // ---- tuning (agent-engine.yaml), read at call time ------------------------------------------------------

    static Mode configuredMode() {
        return Mode.valueOf(config.AgentTuning.stringValue(
                "server.agents.capabilities.expedition.boss.AgentBossAttackMirror.MODE").trim().toUpperCase(Locale.ROOT));
    }

    static long dedupMaxMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossAttackMirror.DEDUP_MAX_MS");
    }

    static int maxShownHits() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossAttackMirror.MAX_SHOWN_HITS");
    }

    static int damagePercent() {
        return config.AgentTuning.intValue(
                "server.agents.capabilities.expedition.boss.AgentBossAttackMirror.DAMAGE_PERCENT");
    }

    static boolean sharedHitWindow() {
        return config.AgentTuning.booleanValue(
                "server.agents.capabilities.expedition.boss.AgentBossAttackMirror.SHARED_HIT_WINDOW");
    }

    static long calibrationWindowMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossAttackMirror.CALIBRATION_WINDOW_MS");
    }

    static long summaryLogMs() {
        return config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossAttackMirror.SUMMARY_LOG_MS");
    }

    // ---- records --------------------------------------------------------------------------------------------

    /** An open run (session set) or the trial (session null, led by {@code trialGmId}) and its counters. */
    private record Scope(AgentBossRunSession session, int trialGmId, RunStats stats) {
        /** True for an Agent the attack may hit: an INSIDE or LEAVING member, or a companion the trial GM leads. */
        boolean covers(int agentId) {
            if (session == null) {
                return AgentRuntimeRegistry.leaderIdForAgentCharacter(agentId) == trialGmId;
            }
            AgentBossRunMember member = session.member(agentId);
            AgentBossRunMember.Phase phase = member == null ? null : member.phase();
            return phase == AgentBossRunMember.Phase.INSIDE || phase == AgentBossRunMember.Phase.LEAVING;
        }
    }

    /** One accepted attack, waiting for its attackAfter; the regions and the event are fixed at the report. */
    private record Impact(Scope scope, Monster part, int oid, int attackIndex, BossAction.OrdinaryAttack attack,
                          boolean facingLeft, List<Integer> regions, boolean jumpAttack, EventInstanceManager event,
                          int pOption, int moveId) {
    }

    /** The dedup key: one attack of one part (Monster identity). */
    private record AttackKey(Monster part, int attackIndex) {
    }

    /** A hit on one human from one attack of one part (Monster identity). */
    private record HitKey(Monster part, int attackIndex, int characterId) {
    }

    private record Report(long atMs, int dx, int dy, long delayMs) {
    }

    private record MobAttack(int mobId, int attackIndex) {
        static final Comparator<MobAttack> ORDER =
                Comparator.comparingInt(MobAttack::mobId).thenComparingInt(MobAttack::attackIndex);
    }

    private static final class Counters {
        private long reported;
        private long matched;
        private long reportedNotPredicted;
        private long predictedNotReported;
    }

    /**
     * One run's (or the trial's) counters since it began; any thread. A run's line ends with botAttacks=, the attacks
     * its members committed on the battle map (AgentBossClientLoad); the trial is no run and has no such count.
     */
    private static final class RunStats {
        private final String label;
        /** The run, or null for the trial. */
        private final AgentBossRunSession session;
        private final AtomicLong lastSummaryMs;
        private final AtomicLong attacks = new AtomicLong();
        private final AtomicLong mirrored = new AtomicLong();
        private final AtomicLong dispatched = new AtomicLong();
        private final AtomicLong applied = new AtomicLong();
        private final AtomicLong shown = new AtomicLong();
        private final AtomicLong window = new AtomicLong();
        private final AtomicLong airborne = new AtomicLong();
        private final AtomicLong unengaged = new AtomicLong();
        private final AtomicLong regionsMask = new AtomicLong();
        private final AtomicLong regionsSampled = new AtomicLong();

        private RunStats(String label, AgentBossRunSession session, long createdAtMs) {
            this.label = label;
            this.session = session;
            this.lastSummaryMs = new AtomicLong(createdAtMs);
        }

        String label() {
            return label;
        }

        String counts() {
            return label + " attacks=" + attacks.get() + " mirrored=" + mirrored.get()
                    + " dispatched=" + dispatched.get() + " applied=" + applied.get() + " shown=" + shown.get()
                    + " window=" + window.get() + " airborne=" + airborne.get() + " unengaged=" + unengaged.get()
                    + " regionsMask=" + regionsMask.get() + " regionsSampled=" + regionsSampled.get()
                    + (session == null ? "" : " botAttacks=" + AgentBossClientLoad.attacks(session));
        }
    }
}
