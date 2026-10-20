package server.living.population;

import client.Character;
import client.Client;
import config.YamlConfig;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.capabilities.townlife.AgentTownLifeAdmissionMode;
import server.agents.capabilities.townlife.AgentTownLifeEntryRequest;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.capabilities.townlife.AgentTownLifeSessionResult;
import server.agents.capabilities.townlife.AgentTownLifeVisitRequest;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRequest;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.knowledge.IntList;
import server.knowledge.KnownWorld;
import server.knowledge.PersonalKnowledge;
import server.living.LivingWorld;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    /** An old hand this close below knowledge.VETERAN_LEVEL talks like a veteran (exact chances, rate numbers). */
    private static final int VETERAN_EARLY_LEVELS = 20;
    private static final int VETERAN_DAYS = 120;

    private final Population population;
    private final Map<Integer, CitizenSession> sessions = new ConcurrentHashMap<>();
    private final Random random;
    private volatile long lastSweepMs;
    private volatile long lastSweepDurationMs;
    private volatile int forcedOnline;

    PresenceScheduler(Population population) {
        this(population, new Random(population.config().SEED ^ 0x5eedL));
    }

    /** Tests choose the draws (whether a due re-plan goes AFK, which activity it picks). */
    PresenceScheduler(Population population, Random random) {
        this.population = population;
        this.random = random;
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
                    logout(session, "session over");
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
                if (citizen.state() == Citizen.State.ACTIVE && !citizen.hired() && !sessions.containsKey(citizen.characterId())
                        && !rostered(citizen.characterId())) {
                    offline.add(citizen); // a hired hand is the roster's: never logged in here, at boot or after
                }
            }
            Collections.shuffle(offline, random);
            for (Citizen citizen : offline) {
                boolean forced = loaded < forcedOnline;
                if (!forced && random.nextDouble() >= loginChance(citizen, moment)) {
                    continue;
                }
                boolean virtual = loaded >= population.config().ONLINE_MAX;
                CitizenSession session = login(citizen, now, virtual);
                if (session != null && !virtual) {
                    loaded++;
                }
            }
        } catch (RuntimeException failure) {
            log.warn("presence sweep failed", failure);
        } finally {
            lastSweepMs = started;
            lastSweepDurationMs = System.currentTimeMillis() - started;
        }
    }

    /** Per-sweep login probability so the steady-state online fraction tracks the cohort's hour weight. */
    double loginChance(Citizen citizen, ZonedDateTime now) {
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
        double chance = fraction * sweepMinutes / Math.max(1.0d, meanSessionMinutes * (1.0d - Math.min(0.9d, fraction)));
        if (citizen.circleId() > 0 && circleOnline(citizen.circleId())) {
            chance *= CIRCLE_BOOST;
        }
        return Math.min(MAX_LOGIN_CHANCE, chance);
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
        Cohort cohort = population.content().cohort(citizen.cohort());
        int minutes = cohort == null ? 90 : cohort.sessionMin() + random.nextInt(Math.max(1, cohort.sessionMax() - cohort.sessionMin() + 1));
        CitizenSession session = new CitizenSession(citizen, now, now + minutes * 60_000L, virtual);
        if (!virtual && !materialize(session, now)) {
            return null;
        }
        sessions.put(citizen.characterId(), session);
        population.save(citizen.withOnline(now));
        session.citizen = population.citizen(citizen.characterId());
        if (!virtual) {
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
        try {
            Point arrival = ResidentSpots.fallback(map, citizen.characterId());
            Character agent = CosmicAgentOfflineLoader.loadOfflineAgent(existing, population.world().config().WORLD,
                    citizen.channel(), map, arrival);
            if (agent == null) {
                return false;
            }
            population.ledger().catchUp(agent, citizen);
            AgentRuntimeEntry entry = activity == Activity.TRAIN
                    ? AgentInteractionRuntime.registerSelfDirectedAgent(agent)
                    : AgentInteractionRuntime.registerDirectorIdleAgent(agent);
            session.entry = entry;
            session.mapId = map.getId();
            session.channel = citizen.channel();
            session.activity = activity;
            session.lastPlanAtMs = now;
            openKnowledge(session, agent, map.getId(), now);
            startActivity(session, map, now);
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
        Character agent = session.agent();
        if (session.virtual || agent == null) {
            return null;
        }
        try {
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
        Cohort cohort = population.content().cohort(citizen.cohort());
        Map<String, Integer> weights = cohort == null ? Map.of("TRAIN", 60, "TOWN", 40) : cohort.activities();
        int level = population.store().characterProgress(citizen.characterId())[0];
        int total = 0;
        List<Activity> options = new ArrayList<>();
        List<Integer> option = new ArrayList<>();
        for (Activity activity : Activity.values()) {
            int weight = weights.getOrDefault(activity.name(), 0);
            if (activity == Activity.PQ && population.fields().lobbyFor(citizen, level) == null) {
                weight = 0;
            }
            if (activity == Activity.MARKET && (!population.config().MARKET_ENABLED || !citizen.has(Citizen.FLAG_MERCHANT))) {
                weight = weight / 3;
            }
            if (activity == Activity.AFK && citizen.has(Citizen.FLAG_AFK_TOWNIE)) {
                weight *= 3;
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
                Field field = population.fields().trainingField(citizen, level, random, true,
                        mapId -> avoiding(session, mapId, now));
                return field == null ? population.fields().homeTown(citizen, random) : field.mapId();
            }
            case PQ -> {
                Lobby lobby = population.fields().lobbyFor(citizen, level);
                return lobby == null ? population.fields().homeTown(citizen, random) : lobby.mapId();
            }
            case MARKET -> {
                return FieldCatalog.FM_ENTRANCE;
            }
            default -> {
                return population.fields().homeTown(citizen, random);
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
            case TRAIN -> AgentMovementCommandRuntime.grind(entry);
            case MARKET -> population.market().onArrive(session);
            default -> {
                if (!townLife(session, map, now)) {
                    AgentMovementCommandRuntime.moveTo(entry, ResidentSpots.fallback(map, session.characterId + (int) (now / 60_000L)), true);
                }
            }
        }
    }

    /**
     * Towns and Free Market rooms without an authored town-life profile used to leave citizens
     * standing on the arrival portal for the whole 25-45 minute session. Between re-plans, a
     * TOWN or MARKET citizen (not one minding a stall) strolls to a random spot on its own
     * platform about every other sweep, so crowded maps read as people milling about.
     */
    private void idleWander(CitizenSession session) {
        if (session.entry == null || session.stallOpen
                || (session.activity != Activity.TOWN && session.activity != Activity.MARKET)) {
            return;
        }
        Character agent = session.agent();
        MapleMap map = agent == null ? null : agent.getMap();
        if (map == null || ResidentSpots.profile(map.getId()).isPresent() || random.nextInt(100) >= WANDER_CHANCE_PERCENT) {
            return;
        }
        Point here = agent.getPosition();
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
        if (onSupplyTrip(session)) {
            // no AFK, stroll or re-plan while its own shop trip runs: a re-plan that is due stays due for the next sweep
            log.debug("citizen {} is on a supply trip; its re-plan waits", session.name());
            return;
        }
        if (moveAfterSupplyTrip(session, now)) {
            return;
        }
        if (leaveFieldBoss(session, now)) {
            return;
        }
        if (session.afkUntilMs > now) {
            return;
        }
        if (session.afkUntilMs != 0L) {
            session.afkUntilMs = 0L;
            population.chatter().event(ChatterEngine.Event.of("back", session.mapId, session.channel).self(session));
            resume(session, now);
            return;
        }
        long since = now - session.lastPlanAtMs;
        if (since < REPLAN_MIN_MS + random.nextInt((int) (REPLAN_MAX_MS - REPLAN_MIN_MS))) {
            idleWander(session);
            return;
        }
        session.lastPlanAtMs = now;
        if (random.nextInt(100) < 15) {
            session.afkUntilMs = now + (5 + random.nextInt(11)) * 60_000L;
            population.chatter().event(ChatterEngine.Event.of("afk", session.mapId, session.channel).self(session));
            if (session.entry != null) {
                AgentMovementCommandRuntime.stop(session.entry);
            }
            return;
        }
        Activity next = plan(session, now);
        int mapId = mapFor(session, next);
        travel(session, next, mapId, now);
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
     * Moves a loaded citizen to another map: instantly when nobody can see either side, else after a short pause. Its
     * activity changes as it arrives, never before: a move that waits (a supply trip) or is still on its pause leaves
     * the map it stands on with that map's activity, so an AFK resume there never grinds a town with no mobs.
     */
    public void travel(CitizenSession session, Activity next, int mapId, long now) {
        Character agent = session.agent();
        MapleMap target = population.map(session.channel, mapId);
        if (agent == null || target == null) {
            return;
        }
        CitizenSession.Move asked = new CitizenSession.Move(next, mapId);
        session.latestMove = asked;
        if (onSupplyTrip(session)) {
            waitForSupplyTrip(session, agent, asked);
            return;
        }
        AgentRuntimeEntry entry = session.entry;
        if (entry != null && AgentTownLifeRuntime.active(entry)) {
            AgentTownLifeRuntime.stop(entry, agent);
            AgentTownLifeVisitLeaseRuntime.clear(entry, agent);
        }
        if (entry != null) {
            AgentMovementCommandRuntime.stop(entry);
        }
        population.market().onLeave(session);
        Runnable move = () -> {
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
                if (current.getMapId() != target.getId()) {
                    current.changeMap(target, target.getPortal(0));
                }
                session.mapId = target.getId();
                session.activity = next;
                startActivity(session, target, System.currentTimeMillis());
                population.chatter().event(ChatterEngine.Event.of("enter", target.getId(), session.channel).self(session));
            } catch (RuntimeException failure) {
                log.debug("citizen {} travel failed", session.name(), failure);
            }
        };
        boolean observed = Population.humansIn(agent.getMap()) > 0 || Population.humansIn(target) > 0;
        long delay = observed ? Math.min(90_000L, Math.max(4_000L, server.living.roster.RecallTravel.etaMs(agent.getMapId(), target.getId()) / 4)) : 1_000L;
        TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY, move, delay);
    }

    /**
     * True while the citizen's running Agent is on its own supply trip (a resupply or a readiness restock, any phase):
     * a re-plan or a move then would pull it off the shop map or its walk back (live, iNeedFox shopped on Aqua Road at
     * 22:52:28 and stood in Leafre at 22:52:53, so its walk back found no route). The wait is bounded by the trip: the
     * supply watchdog ends a walk that stands on one map for TRAVEL_NO_PROGRESS_MS (300 s), a shop visit times out
     * (SHOP_VISIT_TIMEOUT_MS, one retry), and the session's end logs the citizen off whatever it is doing. A citizen
     * with no running Agent entry is never on one.
     */
    private static boolean onSupplyTrip(CitizenSession session) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(session.characterId);
        return entry != null && entry.capabilityStates().find(AgentSupplyProcurementState.STATE_KEY)
                .map(AgentSupplyProcurementState::isActive).orElse(false);
    }

    /**
     * The move waits for the supply trip and is made at the first sweep after it ({@link #moveAfterSupplyTrip}). A move
     * dropped here was lost for good: SocialFabric.partyUp sends a circle party to train once, and skips a citizen that
     * has a party. An older move whose delay ran out during the trip gives way to the newer one.
     */
    private void waitForSupplyTrip(CitizenSession session, Character agent, CitizenSession.Move move) {
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
}
