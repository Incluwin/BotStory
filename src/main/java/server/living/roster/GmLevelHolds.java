package server.living.roster;

import client.Character;
import client.Job;
import client.LevelSetPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GM level holds ({@code !setlevel}): the level and job a character had before its first GM level set, kept until it
 * is restored, released with {@code keep}, or set back to exactly that level and job. One living_event_log row per
 * change ({@value #HOLD} with the original in its detail, {@value #RELEASE}), the character's latest of the two being
 * its record. The original is the level, job and EXP; a human's own record is kept under the persona id {@value #SELF}
 * and also holds its STR/DEX/INT/LUK/AP.
 *
 * <p>Read lazily (one query over the character's living_event_log rows, which are indexed by character only) and
 * cached; a read that fails counts as held and is not cached. A record changes in memory at once and its row is written
 * on companion-ops (inline when already there). From {@link #begin} until its {@link Pending} settles, a use is
 * {@linkplain #settling settling} and another use of the same record waits for it. While a record holds a companion,
 * its Resident quest gear is not applied (ResidentRuntime.kitAllowed) and it is not shaped again as a Resident; a
 * release refreshes its gear. No timer.</p>
 */
public final class GmLevelHolds {
    public static final String HOLD = "gm.level.hold";
    public static final String RELEASE = "gm.level.release";
    /** The persona id a human's own record is kept under. */
    public static final String SELF = "gm:self";
    private static final Logger log = LoggerFactory.getLogger(GmLevelHolds.class);
    /** How long a use may stay {@linkplain #settling settling} before the next use stops waiting for it. */
    static final long SETTLING_STALE_MS = 120_000L;
    private static final Pattern DETAIL = Pattern.compile("lv=(\\d+) job=(\\d+)(?: exp=(\\d+))?"
            + "(?: str=(\\d+) dex=(\\d+) int=(\\d+) luk=(\\d+) ap=(\\d+))?");

    /**
     * What a character was before its first GM set: its level, job and EXP toward the next level; {@code stats} only for
     * a human (null for a companion).
     */
    public record Original(int level, Job job, int exp, LevelSetPlan.Stats stats) {
        public Original(int level, Job job, LevelSetPlan.Stats stats) {
            this(level, job, 0, stats);
        }

        /** 'lv=37 job=0 exp=1234 str=161 dex=36 int=4 luk=4 ap=0' (a companion: 'lv=40 job=110 exp=0'). */
        public String detail() {
            String head = "lv=" + level + " job=" + job.getId() + " exp=" + exp;
            return stats == null ? head : head + " str=" + stats.str() + " dex=" + stats.dex() + " int=" + stats.intel()
                    + " luk=" + stats.luk() + " ap=" + stats.ap();
        }

        /** The original a {@link #detail} names (one written before EXP was kept: EXP 0), or null when it is not one. */
        public static Original parse(String detail) {
            Matcher m = detail == null ? null : DETAIL.matcher(detail.trim());
            if (m == null || !m.matches()) {
                return null;
            }
            try {
                Job job = Job.getById(Integer.parseInt(m.group(2)));
                if (job == null) {
                    return null;
                }
                int exp = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                LevelSetPlan.Stats stats = m.group(4) == null ? null : new LevelSetPlan.Stats(
                        Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)),
                        Integer.parseInt(m.group(7)), Integer.parseInt(m.group(8)));
                return new Original(Integer.parseInt(m.group(1)), job, exp, stats);
            } catch (NumberFormatException tooLarge) {
                return null;
            }
        }
    }

    /** What a use does to the record. */
    public enum Decision {
        /** No record and a change: the original is recorded before it is applied. */
        RECORD,
        /** A record and a restore, a keep, or a set to exactly the original: released once applied. */
        RELEASE,
        /** Nothing to record or release. */
        KEEP
    }

    private final LivingWorld world;
    /** "characterId personaId" -> its record (empty: none). */
    private final Map<String, Optional<Original>> records = new ConcurrentHashMap<>();
    /** Owners a party set is running for (CrewRotation leaves their crew alone meanwhile). */
    private final Set<Integer> busyOwners = ConcurrentHashMap.newKeySet();
    /** "characterId personaId" -> the use that is settling on it. */
    private final Map<String, Ticket> settling = new ConcurrentHashMap<>();
    private final AtomicLong tickets = new AtomicLong();
    private final LongSupplier clock;

    private record Ticket(long id, long sinceMs) {
    }

    public GmLevelHolds(LivingWorld world) {
        this(world, System::currentTimeMillis);
    }

    GmLevelHolds(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
    }

    /** The record: empty for none, null while it cannot be read (nothing is cached then). */
    public Optional<Original> record(int characterId, String personaId) {
        String key = key(characterId, personaId);
        Optional<Original> known = records.get(key);
        if (known != null) {
            return known;
        }
        Optional<LivingStore.EventRow> row = world.store().latestGmLevel(characterId, personaId);
        if (row == null) {
            return null;
        }
        Optional<Original> read = row.filter(r -> HOLD.equals(r.kind())).map(r -> Original.parse(r.detail()));
        Optional<Original> raced = records.putIfAbsent(key, read);
        return raced != null ? raced : read;
    }

    /** True while a record holds the character at its GM level; a record that cannot be read counts as held. */
    public boolean held(int characterId, String personaId) {
        Optional<Original> record = record(characterId, personaId);
        return record == null || record.isPresent();
    }

    /**
     * {@code existing}: the record (null for none); {@code releasing}: a restore or a keep; {@code changes}: the set
     * changes the level or the job.
     */
    public static Decision decide(Original existing, boolean releasing, boolean changes, int toLevel, Job toJob) {
        if (existing == null) {
            return changes ? Decision.RECORD : Decision.KEEP;
        }
        if (releasing || (existing.level() == toLevel && existing.job() == toJob)) {
            return Decision.RELEASE;
        }
        return Decision.KEEP;
    }

    /**
     * True while an earlier use of this record has begun and not settled (a companion's mailbox has not answered yet):
     * a use that began then would rely on a record that use may still take back, so callers refuse it. A use settling
     * for {@value #SETTLING_STALE_MS} ms or more is no longer waited for.
     */
    public boolean settling(int characterId, String personaId) {
        String key = key(characterId, personaId);
        Ticket ticket = settling.get(key);
        if (ticket == null) {
            return false;
        }
        if (clock.getAsLong() - ticket.sinceMs() < SETTLING_STALE_MS) {
            return true;
        }
        if (settling.remove(key, ticket)) {
            log.warn("GM level use on {} {} never settled; no longer waited for", characterId, personaId);
        }
        return false;
    }

    /**
     * Before a set is applied: decides against the record ({@code existing}, null when it could not be read: nothing is
     * recorded or released then; callers refuse a set then) and records {@code from} now when it is the first change.
     * The use is {@linkplain #settling settling} until the result is settled once the set is applied or refused.
     */
    public Pending begin(int characterId, String personaId, Optional<Original> existing, Original from,
                         boolean releasing, boolean changes, int toLevel, Job toJob) {
        Decision decision = existing == null ? Decision.KEEP
                : decide(existing.orElse(null), releasing, changes, toLevel, toJob);
        Ticket ticket = new Ticket(tickets.incrementAndGet(), clock.getAsLong());
        settling.put(key(characterId, personaId), ticket);
        CompletableFuture<Boolean> written = decision == Decision.RECORD
                ? hold(characterId, personaId, from) : CompletableFuture.completedFuture(true);
        return new Pending(characterId, personaId, decision, written, ticket);
    }

    /** A use's record work between {@link #begin} and the set's outcome. */
    public final class Pending {
        private final int characterId;
        private final String personaId;
        private final Decision decision;
        private final CompletableFuture<Boolean> written;
        private final Ticket ticket;

        private Pending(int characterId, String personaId, Decision decision, CompletableFuture<Boolean> written,
                        Ticket ticket) {
            this.characterId = characterId;
            this.personaId = personaId;
            this.decision = decision;
            this.written = written;
            this.ticket = ticket;
        }

        public Decision decision() {
            return decision;
        }

        /**
         * The set was applied (or, refused or failed, not): a release is written once applied; a record this use made
         * is released again when nothing was applied. The use stops settling. Completes true when every row it wrote
         * was saved.
         */
        public CompletableFuture<Boolean> settle(boolean applied) {
            CompletableFuture<Boolean> after = CompletableFuture.completedFuture(true);
            if (applied && decision == Decision.RELEASE) {
                after = release(characterId, personaId, "restored");
            } else if (!applied && decision == Decision.RECORD) {
                after = release(characterId, personaId, "not applied");
            }
            settling.remove(key(characterId, personaId), ticket);
            return written.thenCombine(after, Boolean::logicalAnd);
        }

        /** The set failed part way, so whether it applied is unknown: the record stays as it is; the use stops settling. */
        public void abandon() {
            settling.remove(key(characterId, personaId), ticket);
        }
    }

    /** Records {@code original} (in memory at once); completes false when its row could not be written. */
    public CompletableFuture<Boolean> hold(int characterId, String personaId, Original original) {
        records.put(key(characterId, personaId), Optional.of(original));
        return write(characterId, personaId, HOLD, original.detail());
    }

    /**
     * Releases the record (in memory at once; {@code why} is the row's detail) and refreshes a companion's quest gear;
     * completes false when its row could not be written.
     */
    public CompletableFuture<Boolean> release(int characterId, String personaId, String why) {
        records.put(key(characterId, personaId), Optional.empty());
        CompletableFuture<Boolean> written = write(characterId, personaId, RELEASE, why);
        if (!SELF.equals(personaId)) {
            refreshKit(personaId);
        }
        return written;
    }

    public void busy(int ownerId, boolean busy) {
        if (busy) {
            busyOwners.add(ownerId);
        } else {
            busyOwners.remove(ownerId);
        }
    }

    public boolean busy(int ownerId) {
        return busyOwners.contains(ownerId);
    }

    private CompletableFuture<Boolean> write(int characterId, String personaId, String kind, String detail) {
        CompanionExecutor ops = world.executor();
        if (ops == null || ops.onExecutorThread()) {
            return CompletableFuture.completedFuture(saved(characterId, personaId, kind, detail));
        }
        return ops.call("gm.level " + kind + " " + characterId, () -> saved(characterId, personaId, kind, detail))
                .exceptionally(failure -> false);
    }

    private boolean saved(int characterId, String personaId, String kind, String detail) {
        boolean saved = world.store().eventChecked(characterId, personaId, kind, detail);
        if (!saved) {
            log.warn("GM level record not saved: {} {} {} '{}' (kept in memory until the next boot)",
                    kind, characterId, personaId, detail);
        }
        return saved;
    }

    /** The companion's Resident quest gear for the level it is at now, once nothing holds it (companion-ops). */
    private void refreshKit(String personaId) {
        CompanionExecutor ops = world.executor();
        if (ops == null) {
            return;
        }
        ops.submit("gm.level kit " + personaId, () -> {
            Persona persona = world.content().persona(personaId);
            Character bot = world.roster().loaded(personaId);
            server.living.resident.ResidentRuntime residents = world.residents();
            if (persona != null && bot != null && residents != null) {
                residents.refreshKit(bot, persona);
            }
        });
    }

    private static String key(int characterId, String personaId) {
        return characterId + " " + personaId;
    }
}
