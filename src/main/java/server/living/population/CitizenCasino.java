package server.living.population;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.runtime.AgentForeignWriteRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.integration.AgentPresence;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Region;
import server.living.population.casino.CasinoContent;
import server.living.population.casino.CasinoHost;
import server.living.population.casino.CasinoVenues;
import server.living.population.casino.Venue;
import server.maps.MapleMap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CASINO (docs/SOLOMAPLING_PORT.md): the SoloMapling games and posts, run by citizens as players would run them. A
 * citizen whose plan rolls CASINO (cohorts.yaml weights, only while a venue's switch is on and a role there would suit
 * it) goes to a venue's map: Henesys Game Park for blackjack, cho-han dice and the bar, the Department Store for the
 * drop game, the Market for a gachapon box, the Free Market entrance to sell NX codes. On arrival the venue gives it a
 * role or it strolls the town instead; the role holds its re-plans until its shift is over, then it goes on with its
 * day and no CASINO plan comes for CASINO_COOLDOWN_MS. Seating and shifts ride the 20 s chatter tick; a game's steps are
 * one-shot timers while a human is there to see them. Henesys venues are for citizens of the Victoria region.
 */
public final class CitizenCasino implements CasinoHost {
    private static final Logger log = LoggerFactory.getLogger(CitizenCasino.class);
    private static final int HENESYS = 100000000;

    /** Runs a step later: a timer live, a queue in tests. */
    interface Clock {
        void after(long delayMs, Runnable step);
    }

    private final Population population;
    private final Random random;
    private final Clock clock;
    private final CasinoVenues venues;
    private final Map<Integer, Long> restUntil = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong deferred = new java.util.concurrent.atomic.AtomicLong();

    CitizenCasino(Population population) {
        this(population, (delayMs, step) -> TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                step, Math.max(1L, delayMs)), load(population));
    }

    CitizenCasino(Population population, Clock clock, CasinoContent content) {
        this.population = population;
        this.clock = clock;
        // not the population's public SEED: decks, dice and timings a player could otherwise predict are worth real chips
        this.random = new java.security.SecureRandom();
        this.venues = content == null ? null : new CasinoVenues(this, content);
    }

    private static CasinoContent load(Population population) {
        try {
            return CasinoContent.load(Path.of(population.config().CONTENT_DIR));
        } catch (RuntimeException failure) {
            log.warn("casino content not loaded; the casino stays closed: {}", failure.getMessage());
            return null;
        }
    }

    /** The venues; null when casino.yaml did not load. */
    public CasinoVenues venues() {
        return venues;
    }

    /** True when a CASINO plan fits {@code citizen} now: a venue it may go to is on and has a role for it. */
    boolean plays(Citizen citizen, long now) {
        Long rest = restUntil.get(citizen.characterId());
        return venues != null && (rest == null || rest <= now) && !open(citizen).isEmpty();
    }

    /** The map of a venue with a role for the citizen; 0 when there is none. */
    int mapFor(Citizen citizen) {
        List<Venue> open = open(citizen);
        return open.isEmpty() ? 0 : open.get(random.nextInt(open.size())).mapId();
    }

    private List<Venue> open(Citizen citizen) {
        List<Venue> out = new ArrayList<>();
        if (venues == null) {
            return out;
        }
        Region region = population.fields().regionOf(citizen);
        boolean victoria = region != null && region.towns().contains(HENESYS);
        for (Venue venue : venues.all()) {
            boolean reachable = victoria || venue.mapId() == venues.content().nxSellerMap();
            if (reachable && venue.enabled() && venue.wants(citizen)) {
                out.add(venue);
            }
        }
        return out;
    }

    /** Its CASINO session starts on {@code map}: a venue there gives it a role, or false (it strolls the town). */
    boolean arrive(CitizenSession session, MapleMap map, long now) {
        if (venues == null || session.agent() == null || session.entry == null) {
            return false;
        }
        for (Venue venue : venues.all()) {
            if (venue.mapId() == map.getId() && venue.enabled() && venue.wants(session.citizen) && venue.take(session, now)) {
                log.info("citizen {} takes a {} role at {}", session.name(), venue.name(), map.getId());
                return true;
            }
        }
        return false;
    }

    /** True while the citizen holds a role at a venue: its re-plans wait for its shift to end. */
    boolean holds(CitizenSession session) {
        if (venues == null) {
            return false;
        }
        for (Venue venue : venues.all()) {
            if (venue.holds(session.characterId)) {
                return true;
            }
        }
        return false;
    }

    /** Every 20 s (the chatter tick): the cashier, seating, shifts. */
    void tick() {
        if (venues == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            venues.tick(now);
        } catch (RuntimeException failure) {
            log.debug("casino tick failed", failure);
        }
    }

    /** A human's map chat: a table or host near them may take it ("deal me in", "hit", "cho"). */
    boolean heard(Character human, String text) {
        return venues != null && venues.heard(human, text, System.currentTimeMillis());
    }

    /** A player clicked an NPC object: the casino's cashier opens its shop (true). */
    public boolean clicked(Character player, int objectId) {
        return venues != null && venues.clicked(player, objectId);
    }

    /** Everything stops (the population stops): roles end, the cashier goes. */
    void stopAll() {
        if (venues != null) {
            venues.stop();
        }
    }

    /**
     * {@code !citizens casino send <name> [venue]}: the citizen goes to a venue now (the named one, or any whose switch
     * is on and has a role for it), past its cooldown; it takes the role on arrival as on a rolled CASINO plan.
     */
    public String send(CitizenSession session, String venueName, long now) {
        if (session == null || session.virtual || session.agent() == null) {
            return "not loaded";
        }
        if (venues == null) {
            return "casino closed: casino.yaml did not load";
        }
        if (holds(session)) {
            return session.name() + " already has a casino role";
        }
        restUntil.remove(session.characterId);
        Venue chosen = null;
        for (Venue venue : venues.all()) {
            boolean named = venueName == null || venueName.isBlank() || venue.name().equalsIgnoreCase(venueName);
            if (named && venue.enabled() && venue.wants(session.citizen)) {
                chosen = venue;
                break;
            }
        }
        if (chosen == null) {
            return "no venue " + (venueName == null ? "" : venueName + " ") + "has a role for " + session.name()
                    + " (each needs its switch on; blackjack and dice need CASINO_CHIPS_ENABLED too)";
        }
        population.presence().travel(session, Activity.CASINO, chosen.mapId(), now);
        return session.name() + " goes to the " + chosen.name() + " at map " + chosen.mapId();
    }

    /** One line per venue, for {@code !citizens casino}. */
    public List<String> status() {
        return venues == null ? List.of("casino closed: casino.yaml did not load") : venues.status();
    }

    // ---- CasinoHost ------------------------------------------------------------------------

    @Override
    public Population population() {
        return population;
    }

    @Override
    public boolean say(CitizenSession session, String trigger, Map<String, ?> slots) {
        if (session == null || session.virtual || session.agent() == null) {
            return false;
        }
        ChatterEngine.Event event = ChatterEngine.Event.of(trigger, session.mapId, session.channel).self(session);
        for (Map.Entry<String, ?> slot : slots.entrySet()) {
            event.slot(slot.getKey(), String.valueOf(slot.getValue()));
        }
        String line = population.chatter().compose(event);
        if (line == null || line.isBlank()) {
            return false;
        }
        // the voice's case and abbreviations, no typos: a garbled total or name would be a wrong call at the table
        String rendered = population.voices().renderAnswer(session.citizen, line, System.currentTimeMillis() ^ trigger.hashCode(),
                false);
        population.chatter().answer(session, rendered, population.voices().delayMs(session.citizen, rendered), null);
        return true;
    }

    @Override
    public void done(CitizenSession session, String why) {
        long now = System.currentTimeMillis();
        restUntil.put(session.characterId, now + population.config().CASINO_COOLDOWN_MS);
        log.debug("citizen {} leaves the casino: {}", session.name(), why);
        if (population.presence().session(session.characterId) == session && session.activity == Activity.CASINO) {
            population.presence().replan(session, now);
        }
    }

    @Override
    public void after(long delayMs, Runnable step) {
        clock.after(delayMs, () -> {
            try {
                step.run();
            } catch (RuntimeException failure) {
                log.debug("casino step failed", failure);
            }
        });
    }

    @Override
    public void runAs(CitizenSession session, Runnable step) {
        AgentRuntimeEntry entry = session.entry;
        try {
            if (entry != null && AgentForeignWriteRuntime.foreign(entry)) {
                // a key of its own per step: the mailbox keeps only the newest write per key, and two pickups in one
                // settle must both run
                AgentForeignWriteRuntime.defer(entry, "casino-" + deferred.incrementAndGet(), owner -> step.run());
            } else {
                step.run();
            }
        } catch (RuntimeException failure) {
            log.debug("casino step for citizen {} failed", session.name(), failure);
        }
    }

    @Override
    public Random random() {
        return random;
    }

    @Override
    public long shiftMs() {
        int min = population.config().CASINO_SHIFT_MIN_MS;
        int max = population.config().CASINO_SHIFT_MAX_MS;
        return min + (long) random.nextInt(Math.max(1, max - min + 1));
    }

    @Override
    public boolean humanOn(int channel, int mapId) {
        MapleMap map = population.map(channel, mapId);
        if (map == null) {
            return false;
        }
        for (Character chr : map.getAllPlayers()) {
            if (!AgentPresence.isAgent(chr)) {
                return true;
            }
        }
        return false;
    }
}
