package server.living.population;

import client.Character;
import client.Client;
import client.Job;
import config.PopulationConfig;
import config.YamlConfig;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.navigation.AgentMapWalk;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeEntryRequest;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.capabilities.townlife.AgentTownLifeSessionResult;
import server.agents.capabilities.townlife.AgentTownLifeVisitRequest;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentForeignWriteRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.scheduler.AgentScheduler;
import server.agents.runtime.simulation.AgentSimulationMode;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRequest;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.knowledge.IntList;
import server.knowledge.KnownWorld;
import server.knowledge.PersonalKnowledge;
import server.living.LivingWorld;
import server.living.audit.TellAudit;
import server.living.config.LivingContent;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Cohort;
import server.living.population.PopulationContent.Field;
import server.living.population.PopulationContent.Lobby;
import server.living.population.PopulationContent.Region;
import server.living.resident.ResidentSpots;
import server.living.roster.CompanionLeases;
import server.living.roster.RosterService;
import server.maps.MapleMap;

import java.awt.Point;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

/**
 * Who is online when (plan 3.2/3.3): every sweep, offline citizens log in with a probability
 * from their cohort's hour weight so the steady-state online fraction follows the cohort curve;
 * sessions last a cohort-typical time and re-plan their activity along the way. Loaded citizens
 * are capped by {@code ONLINE_MAX}; the rest are online only on the ledger (virtual).
 */
public final class PresenceScheduler {
    private static final Logger log = LoggerFactory.getLogger(PresenceScheduler.class);
    private static final long LEASE_MS = 20 * 60_000L;
    private static final long REPLAN_MIN_MS = 25 * 60_000L;
    private static final long REPLAN_MAX_MS = 45 * 60_000L;
    private static final int WANDER_CHANCE_PERCENT = 50;
    private static final int WANDER_MIN_PX = 120;
    private static final int WANDER_MAX_PX = 520;
    private static final int WANDER_MAX_DROP_PX = 40;
    private static final int CIRCLE_BOOST = 3;
    private static final double MAX_LOGIN_CHANCE = 0.5d;
    /**
     * How often the sweep runs. Population.start registers it at this period after a first delay of SWEEP_MS
     * (TimerManager.register takes the period before the delay), and every live online count so far came from sweeps
     * this far apart, while loginChance counts SWEEP_MS a sweep: so citizens log in four times as often as it reckons.
     */
    static final long SWEEP_PERIOD_MS = 15_000L;
    /** Boot warm start: how far back its replay of sweeps begins; three times the longest session (afk-townie, 8 h). */
    private static final int WARM_START_REPLAY_HOURS = 24;
    /** Boot warm start: characters it loads a sweep (about 0.45 s each live, 2026-10-20), a third of the sweep period. */
    static final int WARM_START_LOADS_PER_SWEEP = 10;
    /** An old hand this close below knowledge.VETERAN_LEVEL talks like a veteran (exact chances, rate numbers). */
    private static final int VETERAN_EARLY_LEVELS = 20;
    private static final int VETERAN_DAYS = 120;
    /** A move re-checks every half second whether its abstract citizen has left BACKGROUND_ABSTRACT. */
    private static final long ABSTRACT_EXIT_CHECK_MS = 500L;
    /** 12 checks (6 s) outlast one abstract heartbeat (5 s), so even a scheduler that cannot be woken decides once. */
    static final int ABSTRACT_EXIT_CHECKS = 12;

    private final Population population;
    private final Map<Integer, CitizenSession> sessions = new ConcurrentHashMap<>();
    private final Random random;
    private final CitizenJobErrands jobs;
    private final CitizenSalons salons;
    private final CitizenTravel onFoot;
    private final MapVisibleCap cap;
    private volatile long lastSweepMs;
    private volatile long lastSweepDurationMs;
    private volatile int forcedOnline;
    /** Boot warm start: who logs in next, each with the session end its replay drew; null until the first sweep. */
    private Map<Integer, Long> warmStart;

    PresenceScheduler(Population population) {
        this(population, new Random(population.config().SEED ^ 0x5eedL));
    }

    /** Tests choose the draws (whether a due re-plan goes AFK, which activity it picks). */
    PresenceScheduler(Population population, Random random) {
        this.population = population;
        this.random = random;
        this.jobs = new CitizenJobErrands(population, this);
        this.salons = new CitizenSalons(population, this);
        this.onFoot = new CitizenTravel(population, this);
        this.cap = new MapVisibleCap(population::config, sessions::values, MapVisibleCap::standingMap);
    }

    /** population.MAP_VISIBLE_MAX: the per-map cap on visible citizens (docs/SCALE_PLATFORM_PLAN.md section 1). */
    MapVisibleCap visibleCap() {
        return cap;
    }

    /** Third and fourth jobs taken at the instructor (activity JOB). */
    CitizenJobErrands jobs() {
        return jobs;
    }

    public Collection<CitizenSession> sessions() {
        return sessions.values();
    }

    public CitizenSession session(int characterId) {
        return sessions.get(characterId);
    }

    public CitizenSession sessionOf(Character chr) {
        return chr == null ? null : sessions.get(chr.getId());
    }

    public int loadedCount() {
        int count = 0;
        for (CitizenSession session : sessions.values()) {
            if (!session.virtual) {
                count++;
            }
        }
        return count;
    }

    public int virtualCount() {
        return sessions.size() - loadedCount();
    }

    public long lastSweepDurationMs() {
        return lastSweepDurationMs;
    }

    public long lastSweepMs() {
        return lastSweepMs;
    }

    /** Test/ops knob: keep at least this many citizens loaded regardless of the cohort curve. */
    public void forceOnline(int count) {
        forcedOnline = Math.max(0, count);
    }

    public int forcedOnline() {
        return forcedOnline;
    }

    /** Citizens the boot warm start has still to log in. */
    synchronized int warmStartWaiting() {
        return warmStart == null ? 0 : warmStart.size();
    }

    // ---- sweep ---------------------------------------------------------------------------

    public synchronized void sweep() {
        long started = System.currentTimeMillis();
        try {
            long now = started;
            ZonedDateTime moment = ZonedDateTime.now();
            population.churn().step(now);
            for (CitizenSession session : new ArrayList<>(sessions.values())) {
                Citizen current = population.citizen(session.characterId);
                if (current != null && current.hired()) {
                    sessions.remove(session.characterId); // the roster owns it now: never logged out, moved or reclaimed
                    continue;
                }
                if (session.citizen.state() != Citizen.State.ACTIVE || now >= session.endsAtMs) {
                    if (!inPartyQuest(session)) { // one in a party quest logs off after it (CitizenPartyQuests)
                        logout(session, "session over");
                    }
                    continue;
                }
                if (!session.virtual && session.agent() == null) {
                    sessions.remove(session.characterId);
                    log.debug("citizen {} vanished from the runtime; dropping session", session.name());
                    continue;
                }
                if (!session.virtual) {
                    maybeReplan(session, now);
                }
            }
            int loaded = loadedCount();
            List<Citizen> offline = new ArrayList<>();
            for (Citizen citizen : population.citizens()) {
                if (free(citizen)) {
                    offline.add(citizen); // a hired hand is the roster's: never logged in here, at boot or after
                }
            }
            if (warmStart == null) {
                long replayStarted = System.currentTimeMillis();
                warmStart = replay(offline, now, moment);
                log.info("presence warm start: {} citizens online as if the server had been up ({} h of sweeps replayed in {} ms)",
                        warmStart.size(), WARM_START_REPLAY_HOURS, System.currentTimeMillis() - replayStarted);
            }
            if (!warmStart.isEmpty()) {
                loaded = warmUp(loaded, now);
                offline.removeIf(citizen -> sessions.containsKey(citizen.characterId()) || warmStart.containsKey(citizen.characterId()));
            }
            Collections.shuffle(offline, random);
            for (Citizen citizen : offline) {
                boolean forced = loaded < forcedOnline;
                if (!forced && random.nextDouble() >= loginChance(citizen, moment)) {
                    continue;
                }
                boolean virtual = loaded >= population.config().ONLINE_MAX;
                CitizenSession session = login(citizen, now, virtual);
                if (session != null && !session.virtual) { // a login every map of its was too full for is virtual
                    loaded++;
                }
            }
            trimCrowdedMaps(now);
        } catch (RuntimeException failure) {
            log.warn("presence sweep failed", failure);
        } finally {
            lastSweepMs = started;
            lastSweepDurationMs = System.currentTimeMillis() - started;
        }
    }

    /** True when the citizen may log in here: active, not hired or on a roster, and not online already. */
    private boolean free(Citizen citizen) {
        return citizen.state() == Citizen.State.ACTIVE && !citizen.hired() && !sessions.containsKey(citizen.characterId())
                && !rostered(citizen.characterId());
    }

    /**
     * Boot warm start (2026-10-21: a restart at 06:44 came back with 1 citizen online where 76 had been, and took three
     * hours to fill again): who would be online now had the server been up all along. Replays the last
     * {@code WARM_START_REPLAY_HOURS} of sweeps ({@link #SWEEP_PERIOD_MS} apart) in memory, nothing loaded: each sweep
     * ends the replayed sessions that are over, then logs in the others with the sweep's own chance and session length,
     * a circle's boost read from the replayed sessions. The sessions still running now, in random order, each with the
     * end it was drawn: they log in with the time they have left, so they never all log off together.
     */
    Map<Integer, Long> replay(List<Citizen> candidates, long nowMs, ZonedDateTime moment) {
        return replay(candidates, nowMs, moment, WARM_START_REPLAY_HOURS * 3_600_000L);
    }

    /**
     * The replay over the last {@code replayMs}; from boot, a replay over the time since is the sweeps without a warm
     * start. A citizen's chance before its circle's boost holds until the next hour on its cohort's clock, so it is worked
     * out once an hour, not each of the 5,760 sweeps of a day.
     */
    Map<Integer, Long> replay(List<Citizen> candidates, long nowMs, ZonedDateTime moment, long replayMs) {
        List<Citizen> order = new ArrayList<>(candidates);
        Collections.shuffle(order, random);
        int size = order.size();
        long[] endsAt = new long[size]; // 0: offline in the replay
        double[] chance = new double[size];
        long[] chanceUntil = new long[size];
        Map<Integer, Integer> circles = new HashMap<>();
        IntPredicate circleOnline = circle -> circles.getOrDefault(circle, 0) > 0;
        for (long step = replayMs / SWEEP_PERIOD_MS; step >= 1; step--) { // the sweep running now draws for "now" itself
            long at = nowMs - step * SWEEP_PERIOD_MS;
            for (int i = 0; i < size; i++) {
                Citizen citizen = order.get(i);
                if (endsAt[i] != 0L) {
                    if (at < endsAt[i]) {
                        continue;
                    }
                    endsAt[i] = 0L;
                    circles.merge(citizen.circleId(), -1, Integer::sum);
                }
                if (at >= chanceUntil[i]) {
                    ZonedDateTime when = moment.minusNanos((nowMs - at) * 1_000_000L);
                    chance[i] = chanceAlone(citizen, when);
                    chanceUntil[i] = nextHourMs(citizen, when);
                }
                if (chance[i] > 0.0d && random.nextDouble() < boosted(citizen, chance[i], circleOnline)) {
                    endsAt[i] = at + sessionMinutes(citizen) * 60_000L;
                    circles.merge(citizen.circleId(), 1, Integer::sum);
                }
            }
        }
        Map<Integer, Long> online = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            if (endsAt[i] > nowMs) {
                online.put(order.get(i).characterId(), endsAt[i]);
            }
        }
        return online;
    }

    /** When the citizen's hour weight next changes: the next hour on its cohort's clock, its offset counted. */
    private long nextHourMs(Citizen citizen, ZonedDateTime when) {
        Cohort cohort = population.content().cohort(citizen.cohort());
        if (cohort == null) {
            return Long.MAX_VALUE;
        }
        ZonedDateTime local = when.plusMinutes(citizen.offsetMinutes()).withZoneSameInstant(cohort.zone());
        return local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli() - citizen.offsetMinutes() * 60_000L;
    }

    /**
     * Logs in the warm start's next citizens, each until the end its replay drew: loaded while fewer than
     * {@code ONLINE_MAX} are, else virtual. At most {@link #WARM_START_LOADS_PER_SWEEP} loads a sweep; a virtual login
     * loads nothing, so those never wait. One that is no longer free, or whose session would be over, is dropped.
     * Returns the loaded count.
     */
    private int warmUp(int loaded, long now) {
        int loads = 0;
        int max = population.config().ONLINE_MAX;
        Iterator<Map.Entry<Integer, Long>> waiting = warmStart.entrySet().iterator();
        while (waiting.hasNext() && (loads < WARM_START_LOADS_PER_SWEEP || loaded >= max)) {
            Map.Entry<Integer, Long> next = waiting.next();
            waiting.remove();
            Citizen citizen = population.citizen(next.getKey());
            if (citizen == null || !free(citizen) || next.getValue() <= now) {
                continue;
            }
            boolean virtual = loaded >= max;
            CitizenSession session = login(citizen, now, next.getValue(), virtual);
            if (!virtual) {
                loads++;
                if (session != null && !session.virtual) {
                    loaded++;
                }
            }
        }
        if (warmStart.isEmpty()) {
            log.info("presence warm start done: {} loaded, {} virtual", loaded, virtualCount());
        }
        return loaded;
    }

    /** Per-sweep login probability so the steady-state online fraction tracks the cohort's hour weight. */
    double loginChance(Citizen citizen, ZonedDateTime now) {
        return boosted(citizen, chanceAlone(citizen, now), this::circleOnline);
    }

    /** Three times the chance while one of its circle is online (live, or in the warm start's replay); capped. */
    private static double boosted(Citizen citizen, double chance, IntPredicate circleOnline) {
        if (chance > 0.0d && citizen.circleId() > 0 && circleOnline.test(citizen.circleId())) {
            chance *= CIRCLE_BOOST;
        }
        return Math.min(MAX_LOGIN_CHANCE, chance);
    }

    /** Its login chance before a circle's boost; 0 off its cohort's hours. */
    private double chanceAlone(Citizen citizen, ZonedDateTime now) {
        Cohort cohort = population.content().cohort(citizen.cohort());
        if (cohort == null) {
            return 0.0d;
        }
        int weight = cohort.weight(now.plusMinutes(citizen.offsetMinutes()));
        double fraction = weight / 10.0d * cohort.peakOnlinePercent() / 100.0d * population.config().PRESENCE_MULTIPLIER;
        if (fraction <= 0.0d) {
            return 0.0d;
        }
        double meanSessionMinutes = (cohort.sessionMin() + cohort.sessionMax()) / 2.0d;
        double sweepMinutes = population.config().SWEEP_MS / 60_000.0d;
        return fraction * sweepMinutes / Math.max(1.0d, meanSessionMinutes * (1.0d - Math.min(0.9d, fraction)));
    }

    private boolean circleOnline(int circleId) {
        for (CitizenSession session : sessions.values()) {
            if (session.citizen.circleId() == circleId) {
                return true;
            }
        }
        return false;
    }

    // ---- login / logout ------------------------------------------------------------------

    public CitizenSession login(Citizen citizen, long now, boolean virtual) {
        return login(citizen, now, now + sessionMinutes(citizen) * 60_000L, virtual);
    }

    /** A session length drawn from the citizen's cohort. */
    private int sessionMinutes(Citizen citizen) {
        Cohort cohort = population.content().cohort(citizen.cohort());
        return cohort == null ? 90 : cohort.sessionMin() + random.nextInt(Math.max(1, cohort.sessionMax() - cohort.sessionMin() + 1));
    }

    private CitizenSession login(Citizen citizen, long now, long endsAtMs, boolean virtual) {
        CitizenSession session = new CitizenSession(citizen, now, endsAtMs, virtual);
        if (!virtual && !materialize(session, now)) {
            return null;
        }
        sessions.put(citizen.characterId(), session);
        population.update(citizen.characterId(), latest -> latest.withOnline(now)); // the catch-up may have cleared a flag
        session.citizen = population.citizen(citizen.characterId());
        if (!session.virtual) { // materialize turns a login every map of its was too full for into a virtual one
            population.social().onLogin(session);
            population.chatter().onLogin(session);
        }
        return session;
    }

    private boolean materialize(CitizenSession session, long now) {
        Citizen citizen = session.citizen;
        Activity activity = plan(session, now);
        int mapId = mapFor(session, activity);
        MapleMap map = population.map(citizen.channel(), mapId);
        if (map == null) {
            map = population.map(citizen.channel(), population.fields().homeTown(citizen, random));
            activity = Activity.TOWN;
        }
        if (map == null) {
            return false;
        }
        int existing = citizen.characterId();
        if (AgentRuntimeRegistry.hasActiveAgentCharacterId(existing) || rostered(existing) || leased(existing)) {
            return false; // a Resident, companion or bench session already owns this character, or a roster employs it
        }
        Placement placed = loginPlacement(session, activity, map, now);
        if (placed == null) { // every map it could go to is full: it is online on the ledger, never seen
            log.info("citizen {} logs in virtual: map {} and every other it could go to hold {} citizens",
                    citizen.name(), map.getId(), cap.cap());
            session.virtual = true;
            return true;
        }
        if (placed.map() != map) {
            log.debug("citizen {} logs in at {} instead of full map {}", citizen.name(), placed.map().getId(), map.getId());
            activity = placed.activity();
            map = placed.map();
            session.errandJob = null; // its instructor's map was full: an ordinary visit, its errand another session
        }
        try {
            Point arrival = ResidentSpots.fallback(map, citizen.characterId());
            Character agent = CosmicAgentOfflineLoader.loadOfflineAgent(existing, population.world().config().WORLD,
                    citizen.channel(), map, arrival);
            cap.release(session); // loaded, it counts where it stands; not loaded, it holds no slot
            if (agent == null) {
                return false;
            }
            Job offScreen = population.ledger().catchUp(agent, citizen);
            if (population.needs() != null) {
                population.needs().onLoad(session, agent, now); // the needs board: escrow picked up, requests derived
            }
            boolean replanSoon = false;
            if (activity == Activity.JOB && jobs.due(citizen, agent.getJob(), agent.getLevel()) != session.errandJob) {
                session.errandJob = null; // the catch-up settled it after all: an ordinary visit, re-planned soon
                activity = Activity.TOWN;
                replanSoon = true;
            }
            AgentRuntimeEntry entry = activity == Activity.TRAIN
                    ? AgentInteractionRuntime.registerSelfDirectedAgent(agent)
                    : AgentInteractionRuntime.registerDirectorIdleAgent(agent);
            session.entry = entry;
            session.mapId = map.getId();
            session.channel = citizen.channel();
            session.activity = activity;
            session.lastPlanAtMs = replanSoon ? 0L : now;
            openKnowledge(session, agent, map.getId(), now);
            startActivity(session, map, now);
            if (offScreen != null) {
                jobs.mentionOffScreen(session, offScreen);
            }
            World world = population.gameWorld();
            if (world != null) { // its buddies were told it came online as it loaded (CosmicMapGateway.addWorldPlayer)
                if (agent.getGuildId() > 0) {
                    Server.getInstance().setGuildMemberOnline(agent, true, citizen.channel());
                }
            }
            log.info("citizen {} online: {} at {} ({}) lv{} ch{}", citizen.name(), activity, map.getId(),
                    population.fields().name(map.getId()), agent.getLevel(), citizen.channel());
            return true;
        } catch (Exception failure) {
            cap.release(session);
            log.warn("citizen {} could not be loaded", citizen.name(), failure);
            return false;
        }
    }

    public void logout(CitizenSession session, String reason) {
        Character agent = unhook(session);
        if (agent != null) {
            agent.getClient().forceDisconnect(); // the unload saves it in full (Client.disconnectInternal)
        }
        settle(session, reason);
    }

    /** Ends the session and runs the logout hooks; the loaded agent that is still to unload, else null. */
    private Character unhook(CitizenSession session) {
        sessions.remove(session.characterId);
        session.capRetry = null;
        cap.release(session);
        onFoot.cancel(session);
        AgentMapWalk.stopAll(session.characterId);
        Character agent = session.agent();
        if (session.virtual || agent == null) {
            return null;
        }
        try {
            endStint(session, System.currentTimeMillis()); // before it unloads (F2.1)
            population.chatter().onLogout(session);
            population.social().onLogout(session);
            population.market().onLogout(session);
            AgentRuntimeEntry entry = session.entry;
            if (entry != null && AgentTownLifeRuntime.active(entry)) {
                AgentTownLifeRuntime.stop(entry, agent);
                AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
            }
            World world = population.gameWorld();
            if (world != null) { // its buddies are told it went offline as it unloads (Client.disconnectInternal)
                if (agent.getGuildId() > 0) {
                    Server.getInstance().setGuildMemberOnline(agent, false, session.channel);
                }
            }
        } catch (RuntimeException failure) {
            log.debug("citizen {} logout hooks failed", session.name(), failure);
        }
        AgentRuntimeCleanupService.removeAgentByCharacterId(session.characterId);
        return agent.getClient() == null ? null : agent;
    }

    /** After the unload: its known world is written and its time online booked. */
    private void settle(CitizenSession session, String reason) {
        PersonalKnowledge.shared().close(session.characterId);
        book(session);
        log.info("citizen {} offline: {}", session.name(), reason);
    }

    /**
     * Its known world (docs/SERVER_KNOWLEDGE_PLAN.md 7.2): a past of training in Maple Island below 10, Victoria until its
     * home region's levels, then at home; the home region's towns; a veteran at knowledge.VETERAN_LEVEL, or an old hand
     * close below it; a merchant knows prices. Then the map it stands on (the offline load placed it without a map
     * change). Never fails the login: a failure here costs only what it knows.
     */
    private void openKnowledge(CitizenSession session, Character agent, int mapId, long now) {
        PersonalKnowledge personal = PersonalKnowledge.shared();
        if (!personal.on()) {
            return;
        }
        try {
            Citizen citizen = session.citizen;
            Region home = population.content().regions().get(citizen.homeRegion());
            int level = agent.getLevel();
            int veteranLevel = YamlConfig.config.knowledge.VETERAN_LEVEL;
            boolean veteran = level >= veteranLevel || level >= veteranLevel - VETERAN_EARLY_LEVELS
                    && citizen.ageDays(now) >= VETERAN_DAYS && !"afk-townie".equals(citizen.cohort());
            IntList towns = home == null ? IntList.EMPTY : IntList.of(home.towns().stream().mapToInt(Integer::intValue).toArray());
            personal.open(citizen.characterId(), new PersonalKnowledge.Spec(KnownWorld.Past.SYNTHESIZED, citizen.characterId(), level,
                    band -> band < 10 ? "maple-island" : home == null || band < home.levelMin() ? "victoria" : home.id(), towns,
                    List.of(), veteran, citizen.has(Citizen.FLAG_MERCHANT)));
            personal.visited(citizen.characterId(), mapId);
        } catch (RuntimeException failure) {
            log.warn("citizen {}: opening its known world failed", session.name(), failure);
        }
    }

    /** Books a session's minutes online and folds its journal into the life summary. */
    private void book(CitizenSession session) {
        Citizen citizen = population.citizen(session.characterId);
        if (citizen != null) {
            TellAudit.session(session.characterId, session.startedAtMs, System.currentTimeMillis());
            int minutes = (int) Math.max(0L, (Math.min(System.currentTimeMillis(), session.endsAtMs) - session.startedAtMs) / 60_000L);
            population.save(citizen.withLedger(System.currentTimeMillis(), citizen.accruedExp(), citizen.scheduledMinutes() + minutes));
            if (!session.journal.isEmpty()) {
                population.ledger().refreshSummary(population.citizen(session.characterId), session.journal);
            }
        }
    }

    /**
     * A player hired the citizen (P4, HiredHands): it is flagged {@link Citizen#FLAG_HIRED}, so no sweep logs it in, out
     * or anywhere else again, and its session ends. {@code keepLoaded}: a loaded one stays loaded where it stands, in
     * whatever party it is in, for the roster to adopt it in place (its town-life lease and any stall close, and its
     * time online is booked, but it never logs off); else a loaded one logs off as citizens do. One lock with the sweep,
     * so a sweep never sees it half handed over. True when it had a loaded session.
     */
    public synchronized boolean handOver(int characterId, boolean keepLoaded, String reason) {
        population.setHired(characterId, true);
        CitizenSession session = sessions.get(characterId);
        if (session == null) {
            return false;
        }
        Character agent = session.agent();
        boolean loaded = !session.virtual && agent != null;
        if (!keepLoaded) {
            logout(session, reason);
            return loaded;
        }
        sessions.remove(characterId);
        onFoot.cancel(session); // the roster adopts it where it stands, not halfway to a portal
        if (loaded) {
            try {
                population.market().onLeave(session);
                AgentRuntimeEntry entry = session.entry;
                if (entry != null && AgentTownLifeRuntime.active(entry)) {
                    AgentTownLifeRuntime.stop(entry, agent);
                    AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
                }
            } catch (RuntimeException failure) {
                log.debug("citizen {} hand-over hooks failed", session.name(), failure);
            }
        }
        book(session);
        log.info("citizen {} handed over, still online: {}", session.name(), reason);
        return loaded;
    }

    /**
     * True while some owner's roster has a {@code hire:<characterId>} row (P4): the roster is the truth, so even a
     * citizen whose hired flag was lost is never logged in or loaded here.
     */
    private boolean rostered(int characterId) {
        LivingWorld world = population.world();
        RosterService roster = world == null ? null : world.roster();
        return roster != null && roster.cachedOwnerOf(LivingContent.HIRE_PREFIX + characterId) != 0;
    }

    /** True while the living world holds the character's materialization lease (a companion, a live bench slice...). */
    private boolean leased(int characterId) {
        LivingWorld world = population.world();
        CompanionLeases leases = world == null ? null : world.leases();
        return leases != null && leases.holder(characterId) != null;
    }

    /**
     * Server shutdown: every citizen logs off. The unloads are the slow part (a full character save each, ~0.2 s live), so
     * they run on {@code SHUTDOWN_LOGOUT_THREADS} workers for at most {@code SHUTDOWN_LOGOUT_BUDGET_MS}: a full town never
     * holds the channels' shutdown, and the players' saves in it, past the restart's budget. A citizen still waiting then
     * stays loaded, and its channel's shutdown unloads and saves it. The hooks before and the bookkeeping after stay on
     * this thread, in session order, as a single logout runs them.
     */
    public void logoutAll(String reason) {
        long startedMs = System.currentTimeMillis();
        List<CitizenSession> ending = new ArrayList<>(sessions.values());
        List<Character> loaded = new ArrayList<>();
        for (CitizenSession session : ending) {
            Character agent = unhook(session);
            if (agent != null) {
                loaded.add(agent);
            }
        }
        int left = unloadAll(loaded, population.config().SHUTDOWN_LOGOUT_THREADS, population.config().SHUTDOWN_LOGOUT_BUDGET_MS);
        for (CitizenSession session : ending) {
            settle(session, reason);
        }
        log.info("citizens logged off ({}): {} sessions, {} unloaded, {} left to their channel's shutdown, {} ms", reason,
                ending.size(), loaded.size() - left, left, System.currentTimeMillis() - startedMs);
    }

    /** Unloads the agents on {@code threads} workers for at most {@code budgetMs}; how many were never started. */
    static int unloadAll(List<Character> agents, int threads, long budgetMs) {
        if (agents.isEmpty()) {
            return 0;
        }
        int workers = Math.max(1, Math.min(threads, agents.size()));
        ThreadPoolExecutor pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), Thread.ofPlatform().daemon().name("citizen-logout-", 1).factory());
        for (Character agent : agents) {
            pool.execute(() -> {
                try {
                    Client client = agent.getClient();
                    if (client != null) {
                        client.forceDisconnect(); // saves it in full (Client.disconnectInternal)
                    }
                } catch (RuntimeException failure) {
                    log.warn("citizen {} could not be unloaded", agent.getName(), failure);
                }
            });
        }
        pool.shutdown();
        try {
            if (pool.awaitTermination(Math.max(0L, budgetMs), TimeUnit.MILLISECONDS)) {
                return 0;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // the workers are never interrupted, so no save is cut off half-way: the running ones finish on their own
        List<Runnable> waiting = new ArrayList<>();
        pool.getQueue().drainTo(waiting);
        log.warn("{} of {} citizens were not unloaded within {} ms; their channel's shutdown unloads and saves them",
                waiting.size(), agents.size(), budgetMs);
        return waiting.size();
    }

    // ---- planning ------------------------------------------------------------------------

    Activity plan(CitizenSession session, long now) {
        Citizen citizen = session.citizen;
        session.salonNpc = 0;
        Job errand = jobs.planned(session);
        session.errandJob = errand;
        if (errand != null) {
            return Activity.JOB; // its life reached its next job: it goes to its instructor first
        }
        int salon = salons.planned(session, now);
        session.salonNpc = salon;
        if (salon != 0) {
            return Activity.TOWN; // a luxury the economy controller allows (E4b): a visit to its region's hair salon
        }
        Cohort cohort = population.content().cohort(citizen.cohort());
        Map<String, Integer> weights = cohort == null ? Map.of("TRAIN", 60, "TOWN", 40) : cohort.activities();
        int level = population.store().characterProgress(citizen.characterId())[0];
        int total = 0;
        List<Activity> options = new ArrayList<>();
        List<Integer> option = new ArrayList<>();
        for (Activity activity : Activity.values()) {
            int weight = weights.getOrDefault(activity.name(), 0);
            if (activity == Activity.PQ && (population.fields().lobbyFor(citizen, level) == null
                    || partyQuests() != null && partyQuests().coolingDown(citizen.characterId(), now))) {
                weight = 0;
            }
            if (activity == Activity.MARKET && (!population.config().MARKET_ENABLED || !citizen.has(Citizen.FLAG_MERCHANT))) {
                weight = weight / 3;
            }
            if (activity == Activity.MARKET && population.config().MARKET_ENABLED
                    && population.market().wantsFromMarket(citizen.characterId())) {
                weight = Math.max(weight * 3, 10); // it wants gear or a scroll (the needs board, a gear errand: E3)
            }
            if (activity == Activity.AFK && citizen.has(Citizen.FLAG_AFK_TOWNIE)) {
                weight *= 3;
            }
            if (activity == Activity.PLAY && (miniGames() == null || !miniGames().plays(citizen, now))) {
                weight = 0;
            }
            if (activity == Activity.JQ && (session.virtual || jumpQuests() == null || !jumpQuests().plays(session, level, now))) {
                weight = 0;
            }
            if (weight > 0) {
                options.add(activity);
                option.add(weight);
                total += weight;
            }
        }
        if (total == 0) {
            return Activity.TOWN;
        }
        int roll = random.nextInt(total);
        for (int i = 0; i < options.size(); i++) {
            roll -= option.get(i);
            if (roll < 0) {
                return options.get(i);
            }
        }
        return Activity.TOWN;
    }

    int mapFor(CitizenSession session, Activity activity) {
        Citizen citizen = session.citizen;
        int level = Math.max(1, population.store().characterProgress(citizen.characterId())[0]);
        switch (activity) {
            case TRAIN -> {
                long now = System.currentTimeMillis();
                Field field = population.fields().trainingField(citizen, level, jobOf(session), random, true,
                        mapId -> avoiding(session, mapId, now));
                return field == null ? population.fields().homeTown(citizen, random) : field.mapId();
            }
            case PQ -> {
                Lobby lobby = population.fields().lobbyFor(citizen, level, random);
                return lobby == null ? population.fields().homeTown(citizen, random) : lobby.mapId();
            }
            case MARKET -> {
                return FieldCatalog.FM_ENTRANCE;
            }
            case JOB -> {
                int instructorMap = jobs.mapFor(session);
                return instructorMap > 0 ? instructorMap : population.fields().homeTown(citizen, random);
            }
            case PLAY -> {
                int town = miniGames() == null ? 0 : miniGames().townFor(citizen);
                return town > 0 ? town : population.fields().homeTown(citizen, random);
            }
            case JQ -> {
                int start = jumpQuests() == null ? 0 : jumpQuests().mapFor(session, level);
                return start > 0 ? start : population.fields().homeTown(citizen, random);
            }
            default -> {
                CitizenSalons.Salon salon = activity == Activity.TOWN ? CitizenSalons.salon(session.salonNpc) : null;
                return salon != null ? salon.mapId() : population.fields().homeTown(citizen, random);
            }
        }
    }

    private void startActivity(CitizenSession session, MapleMap map, long now) {
        AgentRuntimeEntry entry = session.entry;
        Character agent = session.agent();
        if (entry == null || agent == null) {
            return;
        }
        switch (session.activity) {
            case TRAIN -> {
                AgentMovementCommandRuntime.grind(entry);
                beginStint(session, now); // its stint here is booked when it ends (F2.1)
            }
            case MARKET -> population.market().onArrive(session);
            case PQ -> waitAtLobby(session, map, now);
            case PLAY -> {
                if (miniGames() == null || !miniGames().arrive(session, map, now)) { // no minigames here: a town visit
                    session.activity = Activity.TOWN;
                    stroll(session, map, now);
                }
            }
            case JQ -> {
                if (jumpQuests() == null || !jumpQuests().arrive(session, map, now)) { // nothing to climb here: a visit
                    session.activity = Activity.TOWN;
                    stroll(session, map, now);
                }
            }
            case JOB -> {
                if (!jobs.begin(session, map)) { // nothing to go for here after all: an ordinary visit, re-planned soon
                    session.errandJob = null;
                    session.activity = Activity.TOWN;
                    session.lastPlanAtMs = 0L;
                    stroll(session, map, now);
                }
            }
            case TOWN -> {
                if (session.salonNpc == 0 || !salons.begin(session, map)) { // no salon visit here after all: a stroll
                    stroll(session, map, now);
                }
            }
            default -> stroll(session, map, now);
        }
    }

    /**
     * A citizen out for a party quest waits beside the quest's NPC, as players waiting for a party do, where citizens
     * run that quest (CitizenPartyQuests forms the party there); anywhere else it strolls as before. Live, citizens
     * strolling Kerning City's town life were up on its roofs when the old background director took them in, and
     * never made it down to Lakelis in time.
     */
    private void waitAtLobby(CitizenSession session, MapleMap map, long now) {
        Point spot = partyQuests() == null ? null : partyQuests().waitingSpot(session, map);
        if (spot == null) {
            stroll(session, map, now);
            return;
        }
        AgentMovementCommandRuntime.moveTo(session.entry, spot, true);
    }

    /**
     * Before its party goes in (CitizenPartyQuests): whatever it was doing on the lobby stops, its town life, its
     * stall and its walk, so the quest's admission finds it free and its coordinator moves it.
     */
    void holdForRun(CitizenSession session) {
        Character agent = session.agent();
        AgentRuntimeEntry entry = session.entry;
        if (agent == null || entry == null) {
            return;
        }
        leaveAbstract(entry, agent);
        if (AgentTownLifeRuntime.active(entry)) {
            AgentTownLifeRuntime.stop(entry, agent);
            AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
        }
        AgentMovementCommandRuntime.stop(entry);
        population.market().onLeave(session);
    }

    /** The population's party quests; null before they exist (and in tests that leave them out). */
    private CitizenPartyQuests partyQuests() {
        return population.partyQuests();
    }

    /** The population's minigames; null in tests that leave them out. */
    private CitizenMiniGames miniGames() {
        return population.miniGames();
    }

    /** The population's jump quests; null in tests that leave them out. */
    private CitizenJumpQuests jumpQuests() {
        return population.jumpQuests();
    }

    /** True while the citizen is in a party quest of its party, or on its way back from one. */
    private boolean inPartyQuest(CitizenSession session) {
        CitizenPartyQuests partyQuests = partyQuests();
        return partyQuests != null && partyQuests.holds(session.characterId);
    }

    private void stroll(CitizenSession session, MapleMap map, long now) {
        if (!townLife(session, map, now)) {
            AgentMovementCommandRuntime.moveTo(session.entry, ResidentSpots.fallback(map, session.characterId + (int) (now / 60_000L)), true);
        }
    }

    /**
     * Towns and Free Market rooms without an authored town-life profile used to leave citizens
     * standing on the arrival portal for the whole 25-45 minute session. Between re-plans, a
     * TOWN or MARKET citizen (not one minding a stall) strolls to a random spot on its own
     * platform about every other sweep, so crowded maps read as people milling about. In a Free
     * Market room it browses from stall to stall instead, on any platform (Market.browseSpot).
     */
    private void idleWander(CitizenSession session) {
        if (session.entry == null || session.stallOpen || session.stallSpot != null || session.salonNpc != 0
                || (session.activity != Activity.TOWN && session.activity != Activity.MARKET)) {
            return;
        }
        Character agent = session.agent();
        MapleMap map = agent == null ? null : agent.getMap();
        if (map == null || ResidentSpots.profile(map.getId()).isPresent() || random.nextInt(100) >= WANDER_CHANCE_PERCENT) {
            return;
        }
        Point here = agent.getPosition();
        Point browse = Market.browseSpot(map.getId(), here, random);
        if (browse != null) {
            Point ground = AgentGroundingService.findGroundPoint(map, new Point(browse.x, browse.y - 1));
            if (ground != null && Math.abs(ground.y - browse.y) <= WANDER_MAX_DROP_PX) {
                AgentMovementCommandRuntime.moveTo(session.entry, ground, false);
            }
            return;
        }
        int dx = (WANDER_MIN_PX + random.nextInt(WANDER_MAX_PX - WANDER_MIN_PX)) * (random.nextBoolean() ? 1 : -1);
        Point ground = AgentGroundingService.findGroundPoint(map, new Point(here.x + dx, here.y - 1));
        if (ground == null || Math.abs(ground.y - here.y) > WANDER_MAX_DROP_PX) {
            return;
        }
        AgentMovementCommandRuntime.moveTo(session.entry, ground, false);
    }

    private boolean townLife(CitizenSession session, MapleMap map, long now) {
        if (ResidentSpots.profile(map.getId()).isEmpty() || session.entry == null || session.agent() == null) {
            return false;
        }
        String requestId = "citizen:" + session.characterId + ':' + now;
        AgentTownLifeVisitRequest visit = new AgentTownLifeVisitRequest(map.getId(),
                AgentTownLifeVisitRequest.Purpose.LEISURE, "citizen " + session.activity.name().toLowerCase(java.util.Locale.ROOT), 0L);
        AgentTownLifeVisitLeaseRequest lease = new AgentTownLifeVisitLeaseRequest(
                AgentTownLifeEntryRequest.external(requestId, "population", visit),
                AgentTownLifeAdmissionMode.AMBIENT, now + LEASE_MS, 30_000L, "citizen lease elapsed");
        AgentTownLifeSessionResult result = AgentTownLifeVisitLeaseRuntime.start(session.entry, session.agent(), lease, now, session.characterId);
        return result.started() || result.status() == AgentTownLifeSessionResult.Status.ALREADY_ACTIVE
                || result.status() == AgentTownLifeSessionResult.Status.ALREADY_ACTIVE_SAME_REQUEST;
    }

    private void maybeReplan(CitizenSession session, long now) {
        if (inPartyQuest(session)) {
            return; // its party quest moves it, and sends it back to its day after (CitizenPartyQuests)
        }
        if (miniGames() != null && miniGames().holds(session)) {
            return; // its PLAY session plans its next activity when its games are done (CitizenMiniGames)
        }
        if (jumpQuests() != null && jumpQuests().holds(session)) {
            return; // its jump quest plans its next activity when it clears or gives up (CitizenJumpQuests)
        }
        if (onSupplyTrip(session)) {
            // no AFK, stroll or re-plan while its own shop trip runs: a re-plan that is due stays due for the next sweep
            log.debug("citizen {} is on a supply trip; its re-plan waits", session.name());
            return;
        }
        if (moveAfterSupplyTrip(session, now)) {
            return;
        }
        if (session.trip != null) {
            return; // on its way on foot (CitizenTravel): no wander, AFK or re-plan until it gets there
        }
        if (leaveFieldBoss(session, now)) {
            return;
        }
        if (session.afkUntilMs > now && !(session.fieldPause && FieldPause.endsEarly(session.agent()))) {
            return;
        }
        if (session.afkUntilMs != 0L) {
            session.afkUntilMs = 0L;
            session.fieldPause = false;
            population.chatter().event(ChatterEngine.Event.of("back", session.mapId, session.channel).self(session));
            resume(session, now);
            return;
        }
        long since = now - session.lastPlanAtMs;
        if (session.activity == Activity.JOB && since < (long) population.config().JOB_ERRAND_WALK_MAX_MS
                + population.config().JOB_ERRAND_TALK_MS + CitizenJobErrands.OVERDUE_SLACK_MS) {
            return; // on its way to its instructor: the errand re-plans it when it is done
        }
        if (since < REPLAN_MIN_MS + random.nextInt((int) (REPLAN_MAX_MS - REPLAN_MIN_MS))) {
            idleWander(session);
            return;
        }
        session.lastPlanAtMs = now;
        boolean afk = random.nextInt(100) < 15;
        String afkWhere = afk ? population.fields().kind(session.mapId) : null;
        boolean field = "field".equals(afkWhere) && population.config().FIELD_AFK_MAX_MS > 0;
        if (afk && (!field || FieldPause.safeToStart(session.agent()))) {
            endStint(session, now); // time spent AFK is never booked as hunting
            // D6 (docs/BOT_CONCEPT_PLAN.md F8): on a field a short pause where it is safe, else the long town AFK
            session.afkUntilMs = now + FieldPause.durationMs(afkWhere, population.config().FIELD_AFK_MAX_MS, random);
            session.fieldPause = field;
            if (TellAudit.active() != null) {
                TellAudit.afk(afkWhere, session.afkUntilMs - now);
            }
            population.chatter().event(ChatterEngine.Event.of(field ? "brb" : "afk", session.mapId, session.channel)
                    .self(session));
            if (session.entry != null) {
                AgentMovementCommandRuntime.stop(session.entry);
            }
            return;
        }
        replan(session, now);
    }

    /** Plans its next activity now and goes there (the usual re-plan; also after a job errand). */
    void replan(CitizenSession session, long now) {
        session.lastPlanAtMs = now;
        Activity next = plan(session, now);
        travel(session, next, mapFor(session, next), now);
    }

    /**
     * Ops/test knob (!citizens login): loads this citizen now, replacing a ledger-only session; null when it cannot be
     * (hired, rostered, leased or already owned by a runtime). An already loaded session is returned as it is.
     */
    public synchronized CitizenSession bringOnline(Citizen citizen, long now) {
        CitizenSession existing = sessions.get(citizen.characterId());
        if (existing != null && !existing.virtual) {
            return existing;
        }
        if (citizen.hired() || citizen.state() != Citizen.State.ACTIVE || rostered(citizen.characterId())) {
            return null;
        }
        if (existing != null) {
            logout(existing, "loaded by a GM");
        }
        return login(population.citizen(citizen.characterId()), now, false);
    }

    /**
     * Ops/test knob (!citizens salon): this loaded citizen goes to its hair salon now, whatever the luxury lever and its
     * daily roll say (docs/ECONOMY_PLAN.md E4b). Null when it went, else why not.
     */
    public synchronized String sendToSalon(int characterId, long now) {
        CitizenSession session = sessions.get(characterId);
        if (session == null || !session.loaded() || !movable(session)) {
            return "not loaded, or busy (a party quest, stall, errand or move)";
        }
        CitizenSalons.Salon salon = salons.salonFor(session);
        if (salon == null) {
            return "may not go: a hired hand or persona, no salon for its region, or short of the price over its level's meso";
        }
        session.salonNpc = salon.npcId();
        session.lastPlanAtMs = now;
        travel(session, Activity.TOWN, salon.mapId(), now);
        return null;
    }

    /**
     * A citizen training on the field it died on beside a field boss that still stands (PopulationHooks.died) plans
     * again at once, alive, so it trains elsewhere instead of walking back to die again. True when it moved on.
     */
    private boolean leaveFieldBoss(CitizenSession session, long now) {
        Character agent = session.agent();
        if (session.activity != Activity.TRAIN || agent == null || !agent.isAlive()
                || !avoiding(session, session.mapId, now)) {
            return false;
        }
        session.afkUntilMs = 0L;
        session.lastPlanAtMs = now;
        Activity next = plan(session, now);
        int mapId = mapFor(session, next);
        log.debug("citizen {} leaves field {}: a field boss it died beside still stands there", session.name(), session.mapId);
        travel(session, next, mapId, now);
        return true;
    }

    /** Its TRAIN stint on the field it grinds now begins (CitizenSpots, F2.1); none without a stint book. */
    private void beginStint(CitizenSession session, long now) {
        CitizenSpots spots = spots();
        if (spots != null) {
            spots.begin(session, now);
        }
    }

    /** Its open TRAIN stint ends and is booked when it was a clean one (CitizenSpots, F2.1). */
    private void endStint(CitizenSession session, long now) {
        CitizenSpots spots = spots();
        if (spots != null) {
            spots.end(session, now);
        }
    }

    private CitizenSpots spots() {
        FieldCatalog fields = population.fields();
        return fields == null ? null : fields.spots();
    }

    /** Its loaded character's job id, for the learned training pick (CitizenSpots); -1 when it has none loaded. */
    private static int jobOf(CitizenSession session) {
        Character agent = session.agent();
        return agent == null || agent.getJob() == null ? -1 : agent.getJob().getId();
    }

    /** True while {@code session} avoids {@code mapId}: it died there beside a field boss that still stands. */
    boolean avoiding(CitizenSession session, int mapId, long now) {
        if (mapId == 0 || mapId != session.avoidMapId) {
            return false;
        }
        if (now >= session.avoidUntilMs || !AgentBossField.near(population.map(session.channel, mapId))) {
            session.avoidMapId = 0;
            return false;
        }
        return true;
    }

    private void resume(CitizenSession session, long now) {
        MapleMap map = population.map(session.channel, session.mapId);
        if (map != null) {
            startActivity(session, map, now);
        }
    }

    /**
     * Moves a loaded citizen to another map: on foot while someone can see the map it stands on (CitizenTravel,
     * population.PORTAL_TRAVEL_ENABLED), else instantly when nobody can see either side, else after a short pause. Its
     * activity changes as it arrives, never before: a move that waits (a supply trip) or is still on its pause leaves
     * the map it stands on with that map's activity, so an AFK resume there never grinds a town with no mobs. A citizen
     * whose Agent runs abstract leaves BACKGROUND_ABSTRACT before it is moved. A destination already holding
     * population.MAP_VISIBLE_MAX citizens is never entered: the citizen goes somewhere else of the same kind, or (a party
     * quest lobby, an instructor, no other place free) stays where it is and asks again ({@link #refused}).
     */
    public void travel(CitizenSession session, Activity next, int mapId, long now) {
        session.capRetry = null; // a newer move replaces one that waits for room
        travel(session, next, mapId, now, 0);
    }

    private void travel(CitizenSession session, Activity next, int mapId, long now, int waited) {
        Character agent = session.agent();
        MapleMap target = population.map(session.channel, mapId);
        if (agent == null || target == null) {
            return;
        }
        if (!onSupplyTrip(session) && !cap.admit(session, target, now)) {
            refused(session, next, target, now, waited); // it has not moved, so nobody sees anything happen
            return;
        }
        endStint(session, now); // its stint on the field it leaves is booked (F2.1)
        CitizenSession.Move asked = new CitizenSession.Move(next, mapId);
        session.latestMove = asked;
        onFoot.cancel(session); // a newer move replaces the walk of an older one
        if (onSupplyTrip(session)) {
            waitForSupplyTrip(session, agent, asked);
            return;
        }
        AgentRuntimeEntry entry = session.entry;
        leaveAbstract(entry, agent);
        if (entry != null && AgentTownLifeRuntime.active(entry)) { // someone can see it: it finishes what it is doing
            AgentTownLifeRuntime.stop(entry, agent);
            AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
        }
        if (entry != null) {
            AgentMovementCommandRuntime.stop(entry);
        }
        population.market().onLeave(session);
        if (onFoot.walks(agent, target)) {
            onFoot.begin(session, asked, target, now);
            return;
        }
        cheapMove(session, asked, target);
    }

    /**
     * The cheap way: the citizen stands where it is for the pause, then changes map (arrive). 1 s when nobody can see
     * either map, else the road-hop ETA's quarter, 4-90 s.
     */
    void cheapMove(CitizenSession session, CitizenSession.Move asked, MapleMap target) {
        Character agent = session.agent();
        if (agent == null) {
            return;
        }
        boolean observed = Population.humansIn(agent.getMap()) > 0 || Population.humansIn(target) > 0;
        long delay = observed ? Math.min(90_000L, Math.max(4_000L, server.living.roster.RecallTravel.etaMs(agent.getMapId(), target.getId()) / 4)) : 1_000L;
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> arrive(session, asked, target, ABSTRACT_EXIT_CHECKS), delay);
    }

    /**
     * Makes a move after its pause. A citizen whose Agent is still in BACKGROUND_ABSTRACT is not moved yet: the abstract
     * window reconciles only while the citizen stands where the window began, so the move waits for the mode decision
     * that {@link #leaveAbstract} woke. After ABSTRACT_EXIT_CHECKS checks it moves anyway, and the Agent leaves the
     * window through the scheduler's bounded abandon (a WARN). With portal travel on, a player who came onto its map
     * during the pause sees it walk from where it stands instead, and a player on the target map sees it come in at
     * the portal its route enters by (CitizenTravel.entryPortal), not on the map's start point.
     */
    private void arrive(CitizenSession session, CitizenSession.Move asked, MapleMap target, int abstractChecksLeft) {
        try {
            Character current = session.agent();
            if (current == null || !sessions.containsKey(session.characterId)) {
                return;
            }
            if (asked != session.latestMove) { // a newer move was asked during this one's pause: that one stands
                log.debug("citizen {}: its move to {} gives way to a newer one", session.name(), asked.mapId());
                return;
            }
            if (onSupplyTrip(session)) { // it ran low while the move waited
                waitForSupplyTrip(session, current, asked);
                return;
            }
            if (abstractChecksLeft > 0 && leaveAbstract(session.entry, current)) {
                TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                        () -> arrive(session, asked, target, abstractChecksLeft - 1), ABSTRACT_EXIT_CHECK_MS);
                return;
            }
            long now = System.currentTimeMillis();
            if (current.getMapId() != target.getId()) {
                if (!cap.holds(session, target, now) && !cap.admit(session, target, now)) {
                    cap.countRefusedArrival(); // its slot lapsed and the map filled meanwhile: it has not moved yet
                    refused(session, asked.activity(), target, now, 0);
                    return;
                }
                if (onFoot.walks(current, target)) {
                    log.info("citizen {}: someone came onto map {} during its move to {}; it walks", session.name(),
                            current.getMapId(), target.getId());
                    onFoot.begin(session, asked, target, now);
                    return;
                }
                if (onFoot.enabled()) {
                    stopTownLife(session.entry, current); // nobody on its map: a stroll it lingered on stops at once
                }
                placeAndLand(session, current, asked, target, arrivalPortal(session, current.getMapId(), target, now), now);
                return;
            }
            landed(session, asked, target, now);
        } catch (RuntimeException failure) {
            log.debug("citizen {} travel failed", session.name(), failure);
        }
    }

    /** The move is made: its activity starts on the map it came to. */
    void landed(CitizenSession session, CitizenSession.Move move, MapleMap target, long now) {
        cap.release(session); // it counts where it stands now
        session.mapId = target.getId();
        session.activity = move.activity();
        startActivity(session, target, now);
        population.chatter().event(ChatterEngine.Event.of("enter", target.getId(), session.channel).self(session));
    }

    /** Changes the citizen's map, at {@code portal} (portal 0 when null). */
    void place(Character agent, MapleMap target, server.maps.Portal portal) {
        agent.changeMap(target, portal != null ? portal : target.getPortal(0));
    }

    /**
     * Changes the citizen's map and starts what it does there ({@link #place}, {@link #landed}). This runs on the
     * population timer; with agent-engine.yaml ROUTE_EXTERNAL_WRITES on, both go through the citizen's mailbox and run
     * on its own tick (docs/SCALE_PLATFORM_PLAN.md section 3, mailbox bypass 2), unless a newer move replaced this
     * one or the session ended by then. Its slot on the destination (population.MAP_VISIBLE_MAX) stays reserved until
     * it lands.
     */
    void placeAndLand(CitizenSession session, Character agent, CitizenSession.Move move, MapleMap target,
                      server.maps.Portal portal, long now) {
        AgentRuntimeEntry entry = session.entry;
        if (entry != null && AgentForeignWriteRuntime.foreign(entry)) {
            AgentForeignWriteRuntime.defer(entry, "citizen-move", owner -> {
                if (holds(session) && move == session.latestMove) {
                    place(agent, target, portal);
                    landed(session, move, target, System.currentTimeMillis());
                }
            });
            return;
        }
        place(agent, target, portal);
        landed(session, move, target, now);
    }

    /** A map change with no landing (boarding at a dock): on the citizen's own tick as {@link #placeAndLand} does. */
    void placeOnTick(CitizenSession session, Character agent, MapleMap target, server.maps.Portal portal) {
        AgentRuntimeEntry entry = session.entry;
        if (entry != null && AgentForeignWriteRuntime.foreign(entry)) {
            AgentForeignWriteRuntime.defer(entry, "citizen-move", owner -> {
                if (holds(session)) {
                    place(agent, target, portal);
                }
            });
            return;
        }
        place(agent, target, portal);
    }

    /**
     * Where a cheap move comes into {@code target}: portal 0 as always while nobody is there (or portal travel is off);
     * with a player there, the portal its route enters by.
     */
    private server.maps.Portal arrivalPortal(CitizenSession session, int fromMapId, MapleMap target, long now) {
        server.maps.Portal entry = onFoot.enabled() && CitizenTravel.watched(target)
                ? onFoot.entryPortal(session, fromMapId, target, now) : null;
        return entry != null ? entry : target.getPortal(0);
    }

    /**
     * A move on foot with no way on a player could watch (CitizenTravel): it goes back to what it was doing on that map
     * until nobody watches, a grind on a field it trains on, else a stroll.
     */
    void linger(CitizenSession session, MapleMap map, long now) {
        if (session.entry == null) {
            return;
        }
        if (session.activity == Activity.TRAIN) {
            AgentMovementCommandRuntime.grind(session.entry);
        } else {
            stroll(session, map, now);
        }
    }

    /** True while {@code session} is this scheduler's live session for its citizen. */
    boolean holds(CitizenSession session) {
        return sessions.get(session.characterId) == session;
    }

    private static void stopTownLife(AgentRuntimeEntry entry, Character agent) {
        if (entry != null && AgentTownLifeRuntime.active(entry)) {
            AgentTownLifeRuntime.forceStop(entry, agent, "citizen travel");
            AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
        }
    }

    /**
     * Starts an abstract citizen's way back to the exact world before it is moved. A graceful TownLife exit waits out a
     * committed DWELL and holds the abstract scope through the move, so the window could not reconcile (one WARN per
     * travel). Nobody on its map can see an abrupt stop, so TownLife stops at once, and the woken scheduler's next mode
     * decision reconciles the window. True while the Agent is still abstract.
     */
    private static boolean leaveAbstract(AgentRuntimeEntry entry, Character agent) {
        if (entry == null || entry.simulationState().mode() != AgentSimulationMode.BACKGROUND_ABSTRACT) {
            return false;
        }
        if (AgentTownLifeRuntime.active(entry) && Population.humansIn(agent.getMap()) == 0) {
            AgentTownLifeRuntime.forceStop(entry, agent, "citizen travel");
            AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
        }
        AgentScheduler.wake(entry);
        return true;
    }

    /**
     * True while the citizen's running Agent is on its own supply trip (a resupply or a readiness restock, any phase):
     * a re-plan or a move then would pull it off the shop map or its walk back (live, iNeedFox shopped on Aqua Road at
     * 22:52:28 and stood in Leafre at 22:52:53, so its walk back found no route). The wait is bounded by the trip: the
     * supply watchdog ends a walk that stands on one map for TRAVEL_NO_PROGRESS_MS (300 s), a shop visit times out
     * (SHOP_VISIT_TIMEOUT_MS, one retry), and the session's end logs the citizen off whatever it is doing. A citizen
     * with no running Agent entry is never on one.
     */
    static boolean onSupplyTrip(CitizenSession session) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(session.characterId);
        return entry != null && entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY)
                .map(AgentSupplyProcurementState::isActive).orElse(false);
    }

    /**
     * The move waits for the supply trip and is made at the first sweep after it ({@link #moveAfterSupplyTrip}). A move
     * dropped here was lost for good: SocialFabric.partyUp sends a circle party to train once, and skips a citizen that
     * has a party. An older move whose delay ran out during the trip gives way to the newer one.
     */
    void waitForSupplyTrip(CitizenSession session, Character agent, CitizenSession.Move move) {
        if (move != session.latestMove) {
            log.debug("citizen {}: its move to {} gives way to a newer one", session.name(), move.mapId());
            return;
        }
        session.waitingMove.set(move);
        log.info("citizen {} stays on map {} for its supply trip; its move to {} waits until the trip ends",
                session.name(), agent.getMapId(), move.mapId());
    }

    /**
     * Makes the move that waited for the citizen's supply trip, unless a newer move was asked since (that one stands);
     * the move's activity starts on arrival as after any move (Market.onArrive for a shopper, a grind for TRAIN). A
     * session that ended is never swept again. True when the move was made, so this sweep does nothing else. The move
     * counts as its plan, as a re-plan's move does: a re-plan the trip held back would otherwise run at the next sweep,
     * while this move is still on its pause (audit 2026-10-10: two moves in flight).
     */
    private boolean moveAfterSupplyTrip(CitizenSession session, long now) {
        // taken and cleared in one step: a delayed move on the timer thread may set a newer one meanwhile
        CitizenSession.Move waiting = session.waitingMove.getAndSet(null);
        if (waiting == null) {
            return false;
        }
        if (waiting != session.latestMove) {
            return false;
        }
        log.info("citizen {} moves on to map {} after its supply trip", session.name(), waiting.mapId());
        session.lastPlanAtMs = now;
        travel(session, waiting.activity(), waiting.mapId(), now);
        return true;
    }

    // ---- population.MAP_VISIBLE_MAX ------------------------------------------------------------

    /** Where a citizen goes instead of a full map, and what it does there. */
    record Placement(Activity activity, MapleMap map) {
    }

    /**
     * Where a login lands: {@code map} when it has room, else another place for {@code activity} (a party quest lobby
     * or an instructor that is full gives a town visit), its slot reserved; null when every one is full, and the login
     * is virtual.
     */
    Placement loginPlacement(CitizenSession session, Activity activity, MapleMap map, long now) {
        if (cap.admit(session, map, now)) {
            return new Placement(activity, map);
        }
        Placement other = elsewhere(session, activity, map.getId(), now);
        if (other == null || !cap.admit(session, other.map(), now)) {
            cap.countVirtualLogin();
            return null;
        }
        cap.countRedirect();
        return other;
    }

    /**
     * A move to a full map. An everyday move goes somewhere else of the same kind (another field, town or Free Market
     * room). A move with a purpose (its party quest's lobby, its instructor), or one with nowhere else free, stays
     * where it is doing what it was doing, asks one everyday citizen on the full map to move on so a slot opens, and
     * asks again every MAP_VISIBLE_RETRY_MS, MAP_VISIBLE_RETRY_LIMIT times at most; then the move is dropped and it goes
     * on with its day where it stands. {@code waited} 0 is the first refusal; a redirected move that also finds its
     * destination full waits instead of redirecting again.
     */
    private void refused(CitizenSession session, Activity next, MapleMap target, long now, int waited) {
        PopulationConfig config = population.config();
        if (waited == 0) {
            Placement other = next == Activity.PQ || next == Activity.JOB
                    ? null : elsewhere(session, next, target.getId(), now);
            if (other != null) {
                cap.countRedirect();
                log.debug("citizen {} goes to {} instead of full map {}", session.name(), other.map().getId(), target.getId());
                travel(session, other.activity(), other.map().getId(), now, 1);
                return;
            }
            boolean opened = makeRoom(target, session, now);
            log.info("citizen {} waits on map {} for room on map {} ({} citizens, the cap){}", session.name(),
                    session.mapId, target.getId(), cap.cap(), opened ? "; one there moves on" : "");
        }
        if (waited >= config.MAP_VISIBLE_RETRY_LIMIT) {
            log.info("citizen {} drops its move to full map {} after {} tries", session.name(), target.getId(), waited);
            return;
        }
        cap.countDeferral();
        Object token = new Object();
        session.capRetry = token;
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, () -> {
            if (session.capRetry != token || !holds(session)) {
                return; // a newer move, or the session ended
            }
            session.capRetry = null;
            try {
                travel(session, next, target.getId(), System.currentTimeMillis(), waited + 1);
            } catch (RuntimeException failure) {
                log.debug("citizen {}: its wait for room on map {} failed", session.name(), target.getId(), failure);
            }
        }, config.MAP_VISIBLE_RETRY_MS);
    }

    /**
     * Another place for {@code activity} than {@code excludedMapId} with room for one more citizen: another training
     * field for TRAIN, another Free Market room for MARKET, else a town of its region (AFK stays AFK there, anything else
     * is a town visit). Null when every one is full.
     */
    Placement elsewhere(CitizenSession session, Activity activity, int excludedMapId, long now) {
        Citizen citizen = session.citizen;
        int channel = session.channel;
        IntPredicate unusable = mapId -> mapId == excludedMapId
                || population.map(channel, mapId) == null
                || cap.full(population.map(channel, mapId), channel, session.characterId, now);
        if (activity == Activity.TRAIN) {
            int level = Math.max(1, population.store().characterProgress(citizen.characterId())[0]);
            Field field = population.fields().trainingField(citizen, level, jobOf(session), random, true,
                    mapId -> avoiding(session, mapId, now) || unusable.test(mapId));
            if (field != null) {
                return new Placement(Activity.TRAIN, population.map(channel, field.mapId()));
            }
        }
        if (activity == Activity.MARKET) {
            List<Integer> rooms = new ArrayList<>();
            for (int room = FieldCatalog.FM_ENTRANCE + 1; FieldCatalog.isFreeMarket(room); room++) {
                rooms.add(room);
            }
            Collections.shuffle(rooms, random);
            for (int room : rooms) {
                if (!unusable.test(room)) {
                    return new Placement(Activity.MARKET, population.map(channel, room));
                }
            }
        }
        Region region = population.fields().regionOf(citizen);
        List<Integer> towns = region == null ? new ArrayList<>() : new ArrayList<>(region.towns());
        Collections.shuffle(towns, random);
        for (int town : towns) {
            if (!unusable.test(town)) {
                return new Placement(activity == Activity.AFK ? Activity.AFK : Activity.TOWN, population.map(channel, town));
            }
        }
        return null;
    }

    /** Sends one everyday citizen standing on the full map {@code map} somewhere else, so a slot opens there. */
    private boolean makeRoom(MapleMap map, CitizenSession requester, long now) {
        CitizenSession leaving = null;
        for (CitizenSession candidate : sessions.values()) {
            if (candidate != requester && MapVisibleCap.standingMap(candidate) == map && movable(candidate)
                    && (leaving == null || candidate.lastPlanAtMs < leaving.lastPlanAtMs)) {
                leaving = candidate;
            }
        }
        return leaving != null && moveOn(leaving, map, now);
    }

    /**
     * A citizen nothing else is moving or holding: no party quest, party, job errand, stall, supply trip, walk, move
     * on its way or wait for room.
     */
    private boolean movable(CitizenSession session) {
        Activity activity = session.activity;
        return session.loaded() && activity != Activity.PQ && activity != Activity.JOB && !session.stallOpen
                && session.partyId == 0 && session.trip == null && session.capRetry == null
                && session.waitingMove.get() == null && !cap.reserved(session) && !inPartyQuest(session)
                && !onSupplyTrip(session);
    }

    /** It goes on to another place of the kind it is at (another field, town or market room), the way any move goes. */
    private boolean moveOn(CitizenSession session, MapleMap crowded, long now) {
        Activity next = session.activity == Activity.AFK ? Activity.TOWN : session.activity;
        Placement other = elsewhere(session, next, crowded.getId(), now);
        if (other == null) {
            return false;
        }
        cap.countNudge();
        session.afkUntilMs = 0L;
        session.lastPlanAtMs = now;
        log.info("citizen {} moves on from crowded map {} to {}", session.name(), crowded.getId(), other.map().getId());
        travel(session, other.activity(), other.map().getId(), now);
        return true;
    }

    /**
     * A map over the cap (a cap lowered by a reload, or citizens the engine moved on its own, as a supply trip does)
     * sends its excess everyday citizens elsewhere through {@link #travel}, so they walk out while someone watches and
     * go the cheap way while nobody does; at most MAP_VISIBLE_TRIM_PER_SWEEP a sweep, longest-planned first.
     */
    void trimCrowdedMaps(long now) {
        if (!cap.enabled()) {
            return;
        }
        int budget = population.config().MAP_VISIBLE_TRIM_PER_SWEEP;
        for (Map.Entry<MapleMap, List<CitizenSession>> crowd : cap.standingByMap().entrySet()) {
            int excess = crowd.getValue().size() - cap.cap();
            if (excess <= 0) {
                continue;
            }
            List<CitizenSession> standing = new ArrayList<>(crowd.getValue());
            standing.sort(java.util.Comparator.comparingLong(session -> session.lastPlanAtMs));
            for (CitizenSession session : standing) {
                if (excess <= 0 || budget <= 0) {
                    break;
                }
                if (movable(session) && moveOn(session, crowd.getKey(), now)) {
                    excess--;
                    budget--;
                }
            }
            if (budget <= 0) {
                return;
            }
        }
    }

    /** One line for !citizens and the admin port: the cap, the busiest maps, and what it has done since boot. */
    public String visibleCapReport() {
        MapVisibleCap.Stats stats = cap.stats();
        return "Visible cap " + (cap.enabled() ? cap.cap() + " a map" : "off") + "; busiest " + cap.busiest(3)
                + "; redirects " + stats.redirects() + ", virtual logins " + stats.virtualLogins() + ", waits "
                + stats.deferrals() + ", moved on " + stats.nudges() + ", lapsed arrivals " + stats.refusedArrivals();
    }
}
