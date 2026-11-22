package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.behavior.AgentImperfections;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.CompanionRunRow;
import server.living.store.LivingStore.CompanionTraitRun;
import server.living.store.LivingStore.RosterEntry;
import server.living.roster.Traits.History;
import server.living.roster.Traits.Kind;
import server.living.roster.Traits.Run;
import server.living.roster.Traits.Trait;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Companion traits in the living world (docs/WORLD_SYSTEMS_PLAN.md N9), behind living_world.TRAITS_ENABLED: how low
 * each companion's HP went in a party-quest or boss run (the engine's potion tick through
 * {@link AgentImperfections.Wariness}, written on its companion_performance row), its traits read again from its runs
 * with its owner as each run is recorded and once a day while it sits on the bench ({@link Traits#of}), kept in
 * living_bond.flags so they outlast a restart, shown on its card, and said once in its own words as one is earned or
 * lost. The one change to how it fights (decision D8): a companion WARY of a quest or boss drinks 5-10 points earlier
 * there, never later. Off, nothing is measured, nothing changes and the card shows no trait.
 */
public final class CompanionTraits implements AgentImperfections.Wariness {
    private static final Logger log = LoggerFactory.getLogger(CompanionTraits.class);
    /** How far back a companion's runs are read for its traits, and at most how many. */
    static final long HISTORY_MS = 365L * Traits.DAY_MS;
    static final int HISTORY_RUNS = 400;
    /** A benched companion's traits are read again at most this often (its bench ledger tick asks every interval). */
    static final long BENCH_EVAL_GAP_MS = Traits.DAY_MS;
    /** After a close call, HP must climb back over this share before another one counts. */
    static final double CLOSE_CALL_REARM = 0.40d;
    /** The banter.yaml traits keys: "{kind}.earned", "{kind}.lost", and WARY's line on the way in. */
    static final String EARNED = "earned";
    static final String LOST = "lost";
    static final String WARY_ENTERING = "wary.entering";

    /** What the traits ask of the world; tests bring their own. */
    interface Ports {
        boolean on();

        long now();

        /** living_world.TRAITS_TRADER_MESO: the bench sales over thirty days a TRADER takes. */
        long traderMeso();

        /** The companion's runs with this owner since {@code sinceMs}, newest first; throws ReadFailed on a failed read. */
        List<CompanionTraitRun> runs(int ownerId, int companionId, long sinceMs, int limit);

        /** What its bench sales took since {@code sinceMs} (bench.report rows); -1 when the read failed. */
        long soldSince(int ownerId, String personaId, long sinceMs);

        /** The bond's flags, empty for no bond; null when it could not be read. */
        Set<String> flags(int ownerId, String personaId);

        void saveFlags(int ownerId, String personaId, Set<String> flags);

        /** The companion's character id (0: not known). */
        int companionId(String personaId);

        /** The companion says {@code line} (its party, else its map, else to its owner); false when it could not. */
        boolean say(int companionId, String line);

        /** The banter.yaml traits lines of {@code key} ("wary.earned"), empty for none. */
        List<String> lines(String key);

        /** What players call a quest or boss: "KPQ", "Zakum". */
        String label(String activity);

        /** The companion's autopot reads its HP drink point again (a raise came or went). */
        void refreshPotions(int companionId);
    }

    /** One run's HP so far: its lowest share and its close calls. Written by the engine's potion tick. */
    static final class Low {
        private double min = 1d;
        private int closeCalls;
        private boolean under;

        synchronized void seen(double fraction) {
            min = Math.min(min, fraction);
            if (fraction < Traits.CLOSE_CALL && !under) {
                closeCalls++;
                under = true;
            } else if (fraction >= CLOSE_CALL_REARM) {
                under = false;
            }
        }

        synchronized int minPercent() {
            return (int) Math.round(min * 100d);
        }

        synchronized int closeCalls() {
            return closeCalls;
        }
    }

    private final Ports ports;
    /** Companion id -> the HP of the run it is in now (from its way in to its record). */
    private final Map<Integer, Low> lows = new ConcurrentHashMap<>();
    /** Companion id -> how far its drink point is raised in the run it is in now (WARY of it). */
    private final Map<Integer, Float> armed = new ConcurrentHashMap<>();
    /** "owner:persona" -> when its traits were last read from the bench. */
    private final Map<String, Long> benchReadAt = new ConcurrentHashMap<>();
    /** "owner:persona" -> the earned or lost line nobody heard (it was on the bench): said on its next way in. */
    private final Map<String, String> unsaid = new ConcurrentHashMap<>();

    public CompanionTraits(LivingWorld world) {
        this(new LivePorts(world));
    }

    CompanionTraits(Ports ports) {
        this.ports = ports;
    }

    public boolean on() {
        return ports.on();
    }

    /** LivingWorld.start: the engine's seam, only while the switch is on. */
    public void install() {
        AgentImperfections.installWariness(on() ? this : null);
    }

    public static void uninstall() {
        AgentImperfections.installWariness(null);
    }

    // ---- a run (companion-ops) ------------------------------------------------------------------

    /**
     * The companion went into a run of {@code activity} (a party quest's start, a boss run's way in): its HP is watched
     * until the run is recorded, and when it is WARY of the place its drink point goes up and it says so.
     */
    public void entering(int companionId, int ownerId, String personaId, String activity) {
        if (!on() || companionId == 0 || personaId == null || activity == null) {
            return;
        }
        lows.put(companionId, new Low());
        String key = ownerId + ":" + personaId;
        Trait wary = held(ownerId, personaId).stream()
                .filter(trait -> trait.kind() == Kind.WARY && activity.equals(trait.activity())).findFirst().orElse(null);
        String waiting = unsaid.remove(key);
        if (wary == null) {
            if (waiting != null) {
                ports.say(companionId, waiting);
            }
            return;
        }
        armed.put(companionId, raise(companionId));
        ports.refreshPotions(companionId);
        String line = line(WARY_ENTERING, companionId, wary);
        if (line != null) {
            ports.say(companionId, line);
        }
    }

    /** How many points (5 to 10, by its id) a WARY companion drinks earlier: its own habit, the same every run. */
    static float raise(int companionId) {
        return (5 + Math.floorMod(companionId * 31, 6)) / 100f;
    }

    /**
     * The row of a run as it is written (CompanionRunRecorder.save): its HP figures added from what was seen (lowest
     * HP 0 when it died in it), and its raise and watch end. A row no watch began for is returned as it was.
     */
    CompanionRunRow measured(CompanionRunRow row) {
        int id = row.companionCharacterId();
        if (armed.remove(id) != null) {
            ports.refreshPotions(id);
        }
        Low low = lows.remove(id);
        if (low == null || !on()) {
            return row;
        }
        int minPercent = row.deaths() > 0 ? 0 : low.minPercent();
        return new CompanionRunRow(row.ownerCharacterId(), id, row.personaId(), row.activity(), row.outcome(),
                row.durationS(), row.deaths(), row.partySize(), row.damageShare(), minPercent, low.closeCalls());
    }

    /** The row was written: the companion's traits are read again from its runs, and a change is said. */
    void recorded(CompanionRunRow row) {
        if (on()) {
            evaluate(row.ownerCharacterId(), row.companionCharacterId(), row.personaId(), 0L, true);
        }
    }

    /** The bench ledger's tick (companion-ops): each benched companion's traits are read again once a day. */
    public void benched(Collection<RosterEntry> benched, long nowMs) {
        if (!on()) {
            return;
        }
        for (RosterEntry entry : benched) {
            String key = entry.characterId() + ":" + entry.personaId();
            Long last = benchReadAt.get(key);
            if (last != null && nowMs - last < BENCH_EVAL_GAP_MS) {
                continue;
            }
            benchReadAt.put(key, nowMs);
            int companionId = ports.companionId(entry.personaId());
            if (companionId != 0) {
                evaluate(entry.characterId(), companionId, entry.personaId(), Math.max(1L, entry.benchedAtMs()), true);
            }
        }
    }

    // ---- the engine's seam (any thread) ---------------------------------------------------------

    /** The raise while it is inside the run it went into WARY (a run lost without a record raises nothing outside). */
    @Override
    public float hpAlertRaise(Character agent) {
        Float raise = armed.get(agent.getId());
        return raise == null || agent.getEventInstance() == null ? 0f : raise;
    }

    @Override
    public void hpSeen(Character agent, float fraction) {
        Low low = lows.get(agent.getId());
        if (low != null) {
            low.seen(fraction);
        }
    }

    // ---- reading and saying ---------------------------------------------------------------------

    /** The traits the companion holds with this owner (living_bond.flags); empty while off. */
    public List<Trait> held(int ownerId, String personaId) {
        if (!on()) {
            return List.of();
        }
        Set<String> flags = ports.flags(ownerId, personaId);
        return Traits.held(flags == null ? Set.of() : flags);
    }

    /** The kinds of the traits it holds ("wary", "veteran"): crew banter's traits filter. */
    public Set<String> kinds(int ownerId, String personaId) {
        Set<String> out = new LinkedHashSet<>();
        for (Trait trait : held(ownerId, personaId)) {
            out.add(trait.kind().key());
        }
        return out;
    }

    /** Its traits as the card says them, a line each ("Wary of Zakum since going down there"); empty while off. */
    public List<String> cardLines(int ownerId, String personaId) {
        List<String> out = new ArrayList<>();
        for (Trait trait : held(ownerId, personaId)) {
            out.add(cardLine(trait, trait.activity() == null ? null : ports.label(trait.activity())));
        }
        return out;
    }

    static String cardLine(Trait trait, String label) {
        return switch (trait.kind()) {
            case WARY -> "Wary of " + label + (Traits.CLOSE.equals(trait.cause())
                    ? " after close calls there" : " since going down there");
            case VETERAN -> "A veteran of " + label;
            case STEADY -> "Steady: has not gone down in a long while";
            case SHAKEN -> "Shaken: went down too often this week";
            case HOMEBODY -> "A homebody after a long spell on the bench";
            case TRADER -> "Has a trader's eye from minding the stall";
        };
    }

    /**
     * Reads its traits again and, when {@code save}, keeps the change and says one line of it: the first trait earned,
     * else the first lost. Returns the traits now (the held ones when the history could not be read).
     */
    List<Trait> evaluate(int ownerId, int companionId, String personaId, long benchedSinceMs, boolean save) {
        Set<String> flags = ports.flags(ownerId, personaId);
        if (flags == null) {
            return List.of();
        }
        List<Trait> before = Traits.held(flags);
        History history = history(ownerId, companionId, personaId, benchedSinceMs);
        if (history == null) {
            return before;
        }
        List<Trait> now = Traits.of(history, before);
        if (!save || now.equals(before)) {
            return now;
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String flag : flags) {
            if (Trait.parse(flag) == null) {
                kept.add(flag);
            }
        }
        now.forEach(trait -> kept.add(trait.flag()));
        ports.saveFlags(ownerId, personaId, kept);
        log.info("companion {} ({}) traits with {}: {} -> {}", personaId, companionId, ownerId, flagsOf(before),
                flagsOf(now));
        String line = changeLine(companionId, before, now);
        if (line != null && !ports.say(companionId, line)) {
            unsaid.put(ownerId + ":" + personaId, line);
        }
        return now;
    }

    /** What {@link Traits#of} reads for the companion now; null when its runs could not be read. */
    History history(int ownerId, int companionId, String personaId, long benchedSinceMs) {
        long now = ports.now();
        List<Run> runs = new ArrayList<>();
        try {
            for (CompanionTraitRun row : ports.runs(ownerId, companionId, now - HISTORY_MS, HISTORY_RUNS)) {
                runs.add(new Run(row.activity(), "cleared".equals(row.outcome()), row.deaths(),
                        row.closeCalls() == null ? 0 : row.closeCalls(), row.recordedAtMs()));
            }
        } catch (LivingStore.ReadFailed failure) {
            return null;
        }
        long sold = ports.soldSince(ownerId, personaId, now - Traits.TRADER_DAYS * Traits.DAY_MS);
        return new History(runs, benchedSinceMs, Math.max(0L, sold), ports.traderMeso(), now);
    }

    /** One line for the change: the first trait gained in its earned words, else the first lost; null for none. */
    private String changeLine(int companionId, List<Trait> before, List<Trait> now) {
        for (Trait trait : now) {
            if (before.stream().noneMatch(was -> was.identity().equals(trait.identity()))) {
                String line = line(trait.kind().key() + "." + EARNED, companionId, trait);
                if (line != null) {
                    return line;
                }
            }
        }
        for (Trait trait : before) {
            if (now.stream().noneMatch(is -> is.identity().equals(trait.identity()))) {
                String line = line(trait.kind().key() + "." + LOST, companionId, trait);
                if (line != null) {
                    return line;
                }
            }
        }
        return null;
    }

    /** The companion's line of {@code key} (the same voice every time: picked by its id), "{act}" filled; null for none. */
    private String line(String key, int companionId, Trait trait) {
        List<String> lines = ports.lines(key);
        if (lines.isEmpty()) {
            return null;
        }
        String line = lines.get(Math.floorMod(companionId, lines.size()));
        return trait.activity() == null ? line
                : line.replace("{act}", ports.label(trait.activity()).toLowerCase(Locale.ROOT));
    }

    private static List<String> flagsOf(List<Trait> traits) {
        return traits.stream().map(Trait::flag).toList();
    }

    /** {@code !traits <companion>} (GM): what it holds, what a fresh read says, and what that read is made of. */
    public List<String> status(int ownerId, String personaId) {
        List<String> out = new ArrayList<>();
        out.add("switch: " + (on() ? "on" : "off"));
        int companionId = ports.companionId(personaId);
        Set<String> flags = ports.flags(ownerId, personaId);
        List<Trait> held = Traits.held(flags == null ? Set.of() : flags);
        out.add("held: " + (held.isEmpty() ? "none" : String.join(", ", flagsOf(held))));
        History history = companionId == 0 ? null : history(ownerId, companionId, personaId, 0L);
        if (history == null) {
            out.add("its runs could not be read");
            return out;
        }
        List<Trait> fresh = Traits.of(history, held);
        out.add("a read now (not on the bench): " + (fresh.isEmpty() ? "none" : String.join(", ", flagsOf(fresh))));
        long deaths = history.runs().stream().mapToInt(Run::deaths).sum();
        long closeCalls = history.runs().stream().mapToInt(Run::closeCalls).sum();
        out.add("runs in a year: " + history.runs().size() + ", deaths " + deaths + ", close calls " + closeCalls
                + ", bench sales in thirty days " + history.soldMeso() + " (TRADER at " + history.traderMeso() + ")");
        for (String line : cardLines(ownerId, personaId)) {
            out.add("card: " + line);
        }
        return out;
    }

    // ---- the live ports ------------------------------------------------------------------------

    private static final class LivePorts implements Ports {
        private final LivingWorld world;

        LivePorts(LivingWorld world) {
            this.world = world;
        }

        @Override
        public boolean on() {
            return world.config().TRAITS_ENABLED;
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public long traderMeso() {
            return world.config().TRAITS_TRADER_MESO;
        }

        @Override
        public List<CompanionTraitRun> runs(int ownerId, int companionId, long sinceMs, int limit) {
            return world.store().companionTraitRuns(ownerId, companionId, sinceMs, limit);
        }

        @Override
        public long soldSince(int ownerId, String personaId, long sinceMs) {
            List<String> details = world.store().eventDetailsSince(ownerId, personaId, BenchLedger.REPORT_EVENT, sinceMs);
            if (details == null) {
                return -1L;
            }
            long sold = 0L;
            for (String detail : details) {
                BenchReport.Row row = BenchReport.decode(detail);
                sold += row == null ? 0L : Math.max(0L, row.sold());
            }
            return sold;
        }

        @Override
        public Set<String> flags(int ownerId, String personaId) {
            BondService bonds = world.bonds();
            Bond bond = bonds == null ? null : bonds.peek(ownerId, personaId);
            return bond == null ? Set.of() : bond.flags();
        }

        @Override
        public void saveFlags(int ownerId, String personaId, Set<String> flags) {
            BondService bonds = world.bonds();
            Bond bond = bonds == null ? null : bonds.bond(ownerId, personaId, true);
            if (bond == null) {
                return;
            }
            bonds.save(new Bond(bond.characterId(), bond.personaId(), bond.stage(), bond.points(),
                    new LinkedHashSet<>(flags), bond.contractState(), bond.contractStartedAtMs(), bond.offerExpiresAtMs(),
                    bond.proofProgress(), bond.paidMeso(), bond.partySeconds(), bond.leftAtMs(),
                    bond.lastInteractionAtMs()));
            CompanionViews.invalidate(world, ownerId, personaId); // the card shows them
        }

        @Override
        public int companionId(String personaId) {
            return world.residents().characterIdOf(personaId);
        }

        @Override
        public boolean say(int companionId, String line) {
            return CompanionRestockLines.sayLine(world, bot(companionId), "traits", line);
        }

        @Override
        public List<String> lines(String key) {
            return world.crew() == null ? List.of() : world.crew().catalog().traitLines(key);
        }

        @Override
        public String label(String activity) {
            return CompanionRunRecorder.label(activity);
        }

        @Override
        public void refreshPotions(int companionId) {
            Character bot = bot(companionId);
            if (bot != null && bot.getKeymap().get(91) != null) {
                AgentPotionService.setupAutopotForBot(bot);
            }
        }

        private static Character bot(int characterId) {
            return AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(characterId));
        }
    }
}
