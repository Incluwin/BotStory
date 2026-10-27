package server.living.population;

import config.PopulationConfig;
import config.YamlConfig;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.expedition.boss.AgentBossRunHost;
import server.knowledge.ContentAnswers;
import server.knowledge.ContentChat;
import server.living.LivingWorld;
import server.living.config.ContentException;
import server.living.population.needs.NeedLedger;
import server.maps.MapleMap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.UnaryOperator;

/**
 * Composition root of the populated server (docs/POPULATED_SERVER_PLAN.md): citizens with lives.
 * Runs inside the living world and reuses its store, residents and hooks. Started by
 * {@link LivingWorld#start()} when {@code population.ENABLED}.
 */
public final class Population {
    private static final Logger log = LoggerFactory.getLogger(Population.class);
    /** World flag: the citizens already overdue a third/fourth job when they started taking them are marked (once). */
    static final String JOB_BACKLOG_MARKED_FLAG = "population.job-backlog-marked";
    private static volatile Population instance;

    private final LivingWorld world;
    private final PopulationConfig config;
    private volatile PopulationContent content;
    private final CitizenStore store;
    private final Map<Integer, Citizen> registry = new ConcurrentHashMap<>();
    private volatile NameGenerator names;
    private final CitizenGenerator generator;
    private final FieldCatalog fields;
    private final ProgressionLedger ledger;
    private final PresenceScheduler presence;
    private final Churn churn;
    private final VoicePacks voices;
    private final ChatterEngine chatter;
    private final SocialFabric social;
    private final Conversation conversation;
    private final Market market;
    private final CitizenPartyQuests partyQuests;
    private final CitizenMiniGames miniGames;
    private final PersonMemories memories;
    private final NeedLedger needs;
    private final CitizenWhispers whispers;
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();
    private final List<Integer> channels = new ArrayList<>();

    private Population(LivingWorld world, PopulationConfig config, PopulationContent content) {
        this.world = world;
        this.config = config;
        this.content = content;
        this.store = new CitizenStore();
        for (String part : config.CHANNELS.split(",")) {
            try {
                channels.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // lint reports it
            }
        }
        if (channels.isEmpty()) {
            channels.add(world.config().CHANNEL);
        }
        this.names = new NameGenerator(content.names().patterns(), content.names().lists(), content.names().start(),
                content.names().middle(), content.names().end());
        this.generator = new CitizenGenerator(this);
        this.fields = new FieldCatalog(this);
        this.ledger = new ProgressionLedger(this);
        this.voices = new VoicePacks(this);
        this.chatter = new ChatterEngine(this);
        this.social = new SocialFabric(this);
        this.conversation = new Conversation(this);
        this.market = new Market(this);
        this.partyQuests = new CitizenPartyQuests(this);
        this.miniGames = new CitizenMiniGames(this);
        this.memories = new PersonMemories(this);
        this.needs = new NeedLedger(this);
        this.whispers = new CitizenWhispers(this);
        this.presence = new PresenceScheduler(this);
        this.churn = new Churn(this);
    }

    public static Population get() {
        return instance;
    }

    public static boolean enabled() {
        return instance != null;
    }

    public static synchronized void start(LivingWorld world) {
        PopulationConfig config = YamlConfig.config.population;
        if (config == null || !config.ENABLED) {
            log.info("Population disabled (population.ENABLED=false).");
            return;
        }
        if (instance != null) {
            return;
        }
        PopulationContent content;
        try {
            content = PopulationLoader.load(Path.of(config.CONTENT_DIR), world.content());
        } catch (ContentException failure) {
            log.error("Population content failed to load; citizens stay off: {}", failure.getMessage());
            return;
        }
        for (String warning : content.warnings()) {
            if (warning.startsWith("ERROR")) {
                log.warn("Population content: {}", warning);
            } else {
                log.debug("Population content: {}", warning);
            }
        }
        Population population = new Population(world, config, content);
        for (Citizen citizen : population.store.all(null)) {
            population.registry.put(citizen.characterId(), citizen);
        }
        population.markJobBacklogOnce();
        population.social.installSeams();
        population.conversation.installSeams();
        // the boss runs of the expeditions citizens lead report to the population (docs/CITIZEN_EXPEDITIONS_PLAN.md)
        AgentBossRunHost.installCitizens(population.partyQuests.bossHost());
        ContentAnswers.installPrices(itemId -> {
            Population current = instance;
            return current == null ? null : current.market.known(itemId); // read-only: never seeds or saves
        });
        ContentChat.installCitizens(characterId -> {
            Population current = instance;
            return current != null && current.isCitizen(characterId);
        });
        TimerManager timers = TimerManager.getInstance();
        population.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, population.presence::sweep,
                PresenceScheduler.SWEEP_PERIOD_MS, config.SWEEP_MS));
        population.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, population.ledger::tick,
                config.LEDGER_MS, config.LEDGER_MS));
        population.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, population.churn::tick,
                90_000L, 3_600_000L));
        population.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, () -> {
            population.chatter.tick();
            population.miniGames.tick(); // minigame rooms ride the chatter tick (docs/BOT_CONCEPT_PLAN.md F5a)
        }, 20_000L, 20_000L));
        // and on the same minute the tell audit samples where idle bots stand, folding hourly (BOT_CONCEPT_PLAN.md C2)
        population.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, () -> {
            population.social.tick();
            server.living.audit.TellAudit.tick(System.currentTimeMillis());
            population.needs.tick(); // the needs board's minute (docs/BOT_CONCEPT_PLAN.md F1)
        }, 45_000L, 60_000L));
        population.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, population.market::tick,
                120_000L, 120_000L));
        population.timers.add(timers.register(TimerManager.SchedulerLane.LOW_PRIORITY, population.partyQuests::tick,
                CitizenPartyQuests.TICK_MS, CitizenPartyQuests.TICK_MS));
        instance = population;
        log.info("Population started: {} citizens ({}), {} target, {} online max, channels {}", population.registry.size(),
                content.census(), config.TARGET_CITIZENS, config.ONLINE_MAX, population.channels);
    }

    public static synchronized void stop() {
        Population population = instance;
        if (population == null) {
            return;
        }
        instance = null;
        AgentBossRunHost.installCitizens(null);
        for (ScheduledFuture<?> timer : population.timers) {
            timer.cancel(false);
        }
        population.timers.clear();
        population.market.closeAll("server shutdown");
        population.needs.boards().removeAll(); // a later start spawns them again
        population.memories.flushAll(); // before the citizens unload: what happened up to the last event is kept
        population.presence.logoutAll("server shutdown");
        log.info("Population stopped.");
    }

    /** Re-reads the content directory; returns lint findings. */
    public List<String> reload() {
        PopulationContent fresh = PopulationLoader.load(Path.of(config.CONTENT_DIR), world.content());
        content = fresh;
        names = new NameGenerator(fresh.names().patterns(), fresh.names().lists(), fresh.names().start(),
                fresh.names().middle(), fresh.names().end());
        voices.reload();
        chatter.reload();
        return fresh.warnings();
    }

    // ---- accessors -----------------------------------------------------------------------

    /** ProgressionLedger.markJobBacklog, the first time this world starts with citizen job errands. */
    private void markJobBacklogOnce() {
        try {
            if (world.store().worldFlags().contains(JOB_BACKLOG_MARKED_FLAG)) {
                return;
            }
            int marked = ledger.markJobBacklog();
            world.store().setWorldFlag(JOB_BACKLOG_MARKED_FLAG, 0);
            log.info("{} citizens were already old enough for their next job: it comes off-screen at their next load", marked);
        } catch (RuntimeException failure) {
            log.warn("marking the citizens' job backlog failed; it is tried again next start", failure);
        }
    }

    public LivingWorld world() {
        return world;
    }

    public PopulationConfig config() {
        return config;
    }

    public PopulationContent content() {
        return content;
    }

    public CitizenStore store() {
        return store;
    }

    public NameGenerator names() {
        return names;
    }

    public CitizenGenerator generator() {
        return generator;
    }

    public FieldCatalog fields() {
        return fields;
    }

    public ProgressionLedger ledger() {
        return ledger;
    }

    public PresenceScheduler presence() {
        return presence;
    }

    public Churn churn() {
        return churn;
    }

    public VoicePacks voices() {
        return voices;
    }

    public ChatterEngine chatter() {
        return chatter;
    }

    public SocialFabric social() {
        return social;
    }

    public Conversation conversation() {
        return conversation;
    }

    public Market market() {
        return market;
    }

    /** Party quests citizen parties run themselves (docs/CITIZEN_PARTY_QUESTS_PLAN.md). */
    public CitizenPartyQuests partyQuests() {
        return partyQuests;
    }

    /** Omok and match cards citizens play in towns (docs/BOT_CONCEPT_PLAN.md F5a). */
    public CitizenMiniGames miniGames() {
        return miniGames;
    }

    /** What citizens ask for on the needs board (docs/BOT_CONCEPT_PLAN.md F1). */
    public NeedLedger needs() {
        return needs;
    }

    /** Players' whispers to citizens (docs/BOT_CONCEPT_PLAN.md F5c). */
    public CitizenWhispers whispers() {
        return whispers;
    }

    /** What citizens remember of the people they meet (docs/CITIZEN_PERSON_MEMORY_PLAN.md). */
    public PersonMemories memories() {
        return memories;
    }

    public List<Integer> channels() {
        return channels;
    }

    // ---- registry ------------------------------------------------------------------------

    public Collection<Citizen> citizens() {
        return registry.values();
    }

    public Citizen citizen(int characterId) {
        return registry.get(characterId);
    }

    public Citizen citizenByName(String name) {
        if (name == null) {
            return null;
        }
        for (Citizen citizen : registry.values()) {
            if (citizen.name().equalsIgnoreCase(name)) {
                return citizen;
            }
        }
        return null;
    }

    public boolean isCitizen(int characterId) {
        return registry.containsKey(characterId);
    }

    /**
     * Writes through to the store and the in-memory registry. The hired bit is {@link #setHired}'s alone: a copy taken
     * before a hire (or a release) keeps the registry's current bit, so no ordinary save flips it.
     */
    public void save(Citizen citizen) {
        registry.compute(citizen.characterId(), (id, current) -> persist(keepHired(current, citizen)));
    }

    /**
     * Applies {@code change} to the citizen's current record (never to a copy taken earlier) and writes the result
     * through; returning the record unchanged writes nothing. The hired bit stays as it is. The current record after
     * the change, or null when the citizen is not in the registry.
     */
    public Citizen update(int characterId, UnaryOperator<Citizen> change) {
        return registry.computeIfPresent(characterId, (id, current) -> {
            Citizen next = change.apply(current);
            return next == null || next == current ? current : persist(keepHired(current, next));
        });
    }

    /**
     * The one writer of {@link Citizen#FLAG_HIRED} (P4): a hand-over sets it, a release clears it, on the current
     * record. The current record, or null when the citizen is not in the registry.
     */
    public Citizen setHired(int characterId, boolean hired) {
        boolean[] newlyHired = {false};
        Citizen result = registry.computeIfPresent(characterId, (id, current) -> {
            if (current.hired() == hired) {
                return current;
            }
            newlyHired[0] = hired;
            return persist(hired ? current.withFlag(Citizen.FLAG_HIRED) : current.withoutFlag(Citizen.FLAG_HIRED));
        });
        if (newlyHired[0]) {
            churn.hired(); // its town gets a newcomer on the next sweep, not at BIRTHS_PER_DAY
        }
        return result;
    }

    private static Citizen keepHired(Citizen current, Citizen next) {
        if (current == null || current.hired() == next.hired()) {
            return next;
        }
        return current.hired() ? next.withFlag(Citizen.FLAG_HIRED) : next.withoutFlag(Citizen.FLAG_HIRED);
    }

    /** The store write, inside the registry's compute so memory and the table get one citizen's writes in one order. */
    private Citizen persist(Citizen citizen) {
        store.save(citizen);
        return citizen;
    }

    public void forget(int characterId) {
        registry.remove(characterId);
    }

    /** Citizens the population lives with: ACTIVE and not hired (a hired one is a companion; Churn backfills its town). */
    public int activeCount() {
        int count = 0;
        for (Citizen citizen : citizens()) {
            if (citizen.state() == Citizen.State.ACTIVE && !citizen.hired()) {
                count++;
            }
        }
        return count;
    }

    // ---- world helpers -------------------------------------------------------------------

    public MapleMap map(int channel, int mapId) {
        try {
            World w = Server.getInstance().getWorld(world.config().WORLD);
            Channel ch = w == null ? null : w.getChannel(channel);
            return ch == null || mapId <= 0 ? null : ch.getMapFactory().getMap(mapId);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    public World gameWorld() {
        return Server.getInstance().getWorld(world.config().WORLD);
    }

    /** Humans on this map across the given channel instance. */
    public static int humansIn(MapleMap map) {
        return server.living.resident.ResidentRuntime.humansIn(map);
    }

    /** Maps (any channel) that currently hold a human, for perception-biased planning. */
    public java.util.Set<Integer> humanMaps() {
        java.util.Set<Integer> maps = new java.util.HashSet<>();
        World w = gameWorld();
        if (w == null) {
            return maps;
        }
        for (client.Character chr : w.getPlayerStorage().getAllCharacters()) {
            if (!server.integration.AgentPresence.isAgent(chr) && chr.getMap() != null) {
                maps.add(chr.getMapId());
            }
        }
        return maps;
    }
}
