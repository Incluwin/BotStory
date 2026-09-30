package server.living.roster;

import client.BotClient;
import client.Character;
import client.Client;
import net.server.Server;
import net.server.world.CauseScope;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyEvents;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.roster.CompanionDriftReport.AgentRef;
import server.living.roster.CompanionDriftReport.Code;
import server.living.roster.CompanionDriftReport.Companion;
import server.living.roster.CompanionDriftReport.Drift;
import server.living.roster.CompanionDriftReport.Live;
import server.living.roster.CompanionDriftReport.Member;
import server.living.roster.CompanionDriftReport.Owner;
import server.living.roster.CompanionDriftReport.Row;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The zero-drift auditor (docs/BOT_MANAGEMENT_PLAN.md, "CompanionAuditor"): every {@link #INTERVAL_MS}, on
 * companion-ops, it compares every online owner's roster rows with their party and the Agent registry
 * ({@link CompanionDriftReport}) and repairs toward the roster, never toward a stale party. An ACTIVE row is seated
 * again in the owner's party; a BENCHED or AWAY row's companion is taken out of it. Nothing it does writes a row.
 *
 * <p>Drift must be seen on two runs in a row before anything happens, so a call, bench or channel change in flight is
 * never "repaired". Each drift episode gets one repair and one {@code companion.drift} event; if the drift is still
 * there on the next run the episode is marked stuck and logged once more, and nothing else is tried until the
 * companion is seen clean. That is what keeps the auditor out of a repair loop. Owners inside an event instance are
 * deferred, since their companions may be committed to the run, and so are owners with a companion committed to one
 * (an owner who walked out of a party quest their companions finish).</p>
 */
public final class CompanionAuditor {
    public static final long INTERVAL_MS = 60_000L;
    /** The cause token its party changes carry, so the party sync applies them to its cache only. */
    public static final String CAUSE = "companion.audit";
    private static final Logger log = LoggerFactory.getLogger(CompanionAuditor.class);

    /** What a repair does; it follows from the row's state alone, since the roster is the truth. */
    public enum Repair {
        /** ACTIVE: whatever holds the companion lets go, and it is seated in the owner's party the way login restores it. */
        RESEAT,
        /** BENCHED or AWAY: the companion leaves the owner's party and, unless a bench slice holds it, is unloaded. */
        UNSEAT,
        /** TRAVELLING, HOLD, PARKED (the P2b lifecycle): reported only. */
        NONE
    }

    /** A drift episode to act on this run: repair it ({@code repair} set) or report that the repair did not hold. */
    public record Finding(int ownerId, String personaId, List<Code> codes, String detail, Repair repair, boolean stuck) { }

    /** One open drift episode, as /runtime/companions/audit shows it. */
    public record Open(int ownerId, String personaId, List<Code> codes, long sinceMs, int sightings, boolean repaired,
                       boolean stuck) { }

    /** The auditor's counters; totals count since start. */
    public record Status(long intervalMs, long runs, long lastRunAtMs, long lastRunMs, int ownersChecked,
                         int ownersDeferred, int driftNow, Map<Code, Integer> driftByCode, long confirmed, long repairs,
                         long stuck, List<Open> open) { }

    private record LastRun(long atMs, long tookMs, int ownersChecked, int ownersDeferred, int driftNow,
                           Map<Code, Integer> driftByCode) { }

    private static final class Episode {
        final int ownerId;
        final long sinceMs;
        List<Code> codes = List.of();
        int sightings;
        boolean repaired;
        boolean stuck;

        Episode(int ownerId, long sinceMs) {
            this.ownerId = ownerId;
            this.sinceMs = sinceMs;
        }
    }

    private final LivingWorld world;
    private final LongSupplier clock;
    /** Persona id -> its open drift episode. Written on companion-ops only; the admin port reads it. */
    private final Map<String, Episode> episodes = new ConcurrentHashMap<>();
    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicLong repairs = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();
    private volatile LastRun last = new LastRun(0L, 0L, 0, 0, 0, Map.of());

    public CompanionAuditor(LivingWorld world) {
        this(world, System::currentTimeMillis);
    }

    CompanionAuditor(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
    }

    /** One audit of every online owner; runs on companion-ops (the timer only submits it). */
    public void run() {
        long started = clock.getAsLong();
        List<CompanionDriftReport> reports = new ArrayList<>();
        Set<Integer> deferred = new HashSet<>();
        Commitments commitments = world.commitments();
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (isBot(chr) || !world.roster().hasCompanions(chr)) {
                    continue;
                }
                // its companions may be committed to the run, also once it walked out of it and they carry on without it
                if (chr.getEventInstance() != null || commitments != null && commitments.ownerCommitted(chr.getId())) {
                    deferred.add(chr.getId());
                    continue;
                }
                reports.add(report(world, w, chr, world.store().roster(chr.getId()),
                        AgentRuntimeRegistry.entriesForLeader(chr.getId())));
            }
        }
        for (Finding finding : review(reports, deferred, started)) {
            apply(finding);
        }
        LastRun previous = last;
        last = new LastRun(started, Math.max(0L, clock.getAsLong() - started), reports.size(), deferred.size(),
                previous.driftNow(), previous.driftByCode());
    }

    /**
     * Updates the drift episodes from this run's reports and returns what to act on. {@code deferred} owners keep
     * their episodes as they are; an owner missing from both (logged off) loses them.
     */
    List<Finding> review(List<CompanionDriftReport> reports, Set<Integer> deferred, long nowMs) {
        runs.incrementAndGet();
        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<Code, Integer> byCode = new TreeMap<>();
        int driftNow = 0;
        for (CompanionDriftReport report : reports) {
            int ownerId = report.owner().id();
            for (Map.Entry<String, List<Drift>> drifting : drifting(report).entrySet()) {
                String personaId = drifting.getKey();
                seen.add(personaId);
                if (world.roster().inFlight(ownerId, personaId)) {
                    continue; // a call, walk-out or trip under way: neither drift nor clean yet
                }
                List<Code> codes = new ArrayList<>();
                for (Drift drift : drifting.getValue()) {
                    codes.add(drift.code());
                    byCode.merge(drift.code(), 1, Integer::sum);
                    driftNow++;
                }
                Finding finding = sighted(ownerId, personaId, codes, detail(drifting.getValue()),
                        repairFor(stateOf(report, personaId)), nowMs);
                if (finding != null) {
                    findings.add(finding);
                }
            }
        }
        // clean companions end their episode; so does an owner who is gone, unless deferred
        episodes.entrySet().removeIf(open -> !seen.contains(open.getKey()) && !deferred.contains(open.getValue().ownerId));
        last = new LastRun(nowMs, last.tookMs(), reports.size(), deferred.size(), driftNow, Map.copyOf(byCode));
        return findings;
    }

    private Finding sighted(int ownerId, String personaId, List<Code> codes, String detail, Repair repair, long nowMs) {
        Episode episode = episodes.get(personaId);
        if (episode == null || episode.ownerId != ownerId) {
            episode = new Episode(ownerId, nowMs);
            episodes.put(personaId, episode);
        }
        episode.codes = List.copyOf(codes);
        episode.sightings++;
        if (episode.sightings < 2) {
            return null; // seen once: it may be a verb in flight
        }
        if (!episode.repaired) {
            episode.repaired = true;
            confirmed.incrementAndGet();
            if (repair != Repair.NONE) {
                repairs.incrementAndGet();
            }
            return new Finding(ownerId, personaId, episode.codes, detail, repair, false);
        }
        if (!episode.stuck) {
            episode.stuck = true;
            stuck.incrementAndGet();
            return new Finding(ownerId, personaId, episode.codes, detail, Repair.NONE, true);
        }
        return null; // stuck: reported already, nothing more is tried until it is seen clean
    }

    /** Logs the finding to living_event_log and carries out its repair, under the audit's cause token. */
    void apply(Finding finding) {
        String detail = finding.codes() + " " + finding.detail() + (finding.stuck() ? "; the repair did not hold"
                : "; repair " + finding.repair());
        log.info("companion.drift {} of {}: {}", finding.personaId(), finding.ownerId(), detail);
        world.store().event(finding.ownerId(), finding.personaId(), "companion.drift", detail);
        if (finding.repair() == Repair.NONE) {
            return;
        }
        Character owner = world.roster().onlineCharacter(finding.ownerId());
        Persona persona = world.content().persona(finding.personaId());
        if (owner == null || persona == null) {
            return;
        }
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            if (finding.repair() == Repair.RESEAT) {
                world.roster().reseat(owner, persona);
            } else {
                world.roster().unseat(owner, persona);
            }
        } catch (RuntimeException failure) {
            log.warn("companion.drift repair {} of {} failed", finding.repair(), finding.personaId(), failure);
        }
    }

    /** Toward the roster: the row's state alone decides. */
    static Repair repairFor(RosterState state) {
        if (state == null) {
            return Repair.NONE;
        }
        return switch (state) {
            case ACTIVE -> Repair.RESEAT;
            case BENCHED, AWAY -> Repair.UNSEAT;
            default -> Repair.NONE;
        };
    }

    public Status status() {
        LastRun run = last;
        List<Open> open = new ArrayList<>();
        for (Map.Entry<String, Episode> entry : episodes.entrySet()) {
            Episode episode = entry.getValue();
            open.add(new Open(episode.ownerId, entry.getKey(), episode.codes, episode.sinceMs, episode.sightings,
                    episode.repaired, episode.stuck));
        }
        return new Status(INTERVAL_MS, runs.get(), run.atMs(), run.tookMs(), run.ownersChecked(), run.ownersDeferred(),
                run.driftNow(), run.driftByCode(), confirmed.get(), repairs.get(), stuck.get(), open);
    }

    /** Non-informational drift grouped by companion, in report order. */
    private static Map<String, List<Drift>> drifting(CompanionDriftReport report) {
        Map<String, List<Drift>> out = new LinkedHashMap<>();
        for (Drift drift : report.drift()) {
            if (!drift.code().informational() && drift.personaId() != null) {
                out.computeIfAbsent(drift.personaId(), id -> new ArrayList<>()).add(drift);
            }
        }
        return out;
    }

    private static RosterState stateOf(CompanionDriftReport report, String personaId) {
        for (Companion companion : report.companions()) {
            if (companion.row().personaId().equals(personaId)) {
                return companion.row().state();
            }
        }
        return null;
    }

    private static String detail(List<Drift> drifts) {
        List<String> details = new ArrayList<>();
        for (Drift drift : drifts) {
            details.add(drift.detail());
        }
        return String.join("; ", details);
    }

    // ---- snapshot ------------------------------------------------------------------------

    /**
     * The owner's rows, party and registered Agents as a {@link CompanionDriftReport}, read from live server state
     * (also the body of GET /runtime/companions). {@code living} may be null, with no rows.
     */
    public static CompanionDriftReport report(LivingWorld living, World w, Character chr, List<RosterEntry> entries,
                                              List<AgentRuntimeEntry> registered) {
        List<Row> rows = new ArrayList<>();
        Set<Integer> rosterIds = new HashSet<>();
        for (RosterEntry entry : entries) {
            Persona persona = living.content().persona(entry.personaId());
            int characterId = living.residents().characterIdOf(entry.personaId());
            rows.add(new Row(entry.personaId(), persona == null ? entry.personaId() : persona.name(), entry.state(),
                    entry.assignment(), entry.campMapId(), characterId));
            if (characterId > 0) {
                rosterIds.add(characterId);
            }
        }
        Party party = chr.getParty();
        List<Member> members = new ArrayList<>();
        if (party != null) {
            for (PartyCharacter member : party.getMembers()) {
                Character player = member.getPlayer();
                // An offline member has no Character to ask; a rostered companion is a bot either way.
                members.add(new Member(member.getId(), member.getName(), player != null ? isBot(player) : rosterIds.contains(member.getId())));
            }
        }
        Owner owner = new Owner(chr.getId(), chr.getName(), channelOf(chr), chr.getMapId(), party == null ? 0 : party.getId(), members);
        List<Companion> companions = new ArrayList<>();
        for (Row row : rows) {
            companions.add(new Companion(row, live(w, row.characterId())));
        }
        List<AgentRef> agents = new ArrayList<>();
        for (AgentRuntimeEntry entry : registered) {
            Character bot = AgentRuntimeIdentityRuntime.bot(entry);
            if (bot != null) {
                agents.add(new AgentRef(bot.getId(), bot.getName()));
            }
        }
        int freeSlots = living == null ? -1 : living.roster().freeSlots(chr);
        return CompanionDriftReport.of(owner, freeSlots, companions, agents);
    }

    private static Live live(World w, int characterId) {
        if (characterId <= 0) {
            return Live.unloaded(-1);
        }
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        // The cohort is the leader's id for leader-registered Agents; other cohorts may exceed int range.
        long leaderId = entry == null ? -1L : AgentRuntimeRegistry.cohortIdForAgentCharacter(characterId);
        Character bot = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        if (bot == null) {
            bot = w.getPlayerStorage().getCharacterById(characterId); // loaded without an Agent runtime
        }
        if (bot == null) {
            return Live.unloaded(leaderId);
        }
        Party botParty = bot.getParty(); // read once: a game thread may clear it between two calls
        return new Live(true, channelOf(bot), bot.getMapId(), bot.getHp(), bot.getMaxHp(),
                botParty == null ? 0 : botParty.getId(), leaderId);
    }

    private static boolean isBot(Character chr) {
        return chr.getClient() instanceof BotClient;
    }

    private static int channelOf(Character chr) {
        Client client = chr.getClient();
        return client == null ? -1 : client.getChannel();
    }
}
