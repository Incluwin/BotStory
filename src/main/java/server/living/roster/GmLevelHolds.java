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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GM level holds ({@code !setlevel}): the level and job a character had before its first GM level set, kept until it
 * is restored, released with {@code keep}, or set back to exactly that level and job. One living_event_log row per
 * change ({@value #HOLD} with the original in its detail, {@value #RELEASE}), the character's latest of the two being
 * its record; a human's own record is kept under the persona id {@value #SELF} and also holds its STR/DEX/INT/LUK/AP.
 *
 * <p>Read lazily, one indexed row per character, and cached; a read that fails counts as held and is not cached. A
 * record changes in memory at once and its row is written on companion-ops (inline when already there). While a record
 * holds a companion, its Resident quest gear is not applied (ResidentRuntime.kitAllowed); a release refreshes it. No
 * timer.</p>
 */
public final class GmLevelHolds {
    public static final String HOLD = "gm.level.hold";
    public static final String RELEASE = "gm.level.release";
    /** The persona id a human's own record is kept under. */
    public static final String SELF = "gm:self";
    private static final Logger log = LoggerFactory.getLogger(GmLevelHolds.class);
    private static final Pattern DETAIL = Pattern.compile(
            "lv=(\\d+) job=(\\d+)(?: str=(\\d+) dex=(\\d+) int=(\\d+) luk=(\\d+) ap=(\\d+))?");

    /** What a character was before its first GM set; {@code stats} only for a human (null for a companion). */
    public record Original(int level, Job job, LevelSetPlan.Stats stats) {
        /** 'lv=37 job=0 str=161 dex=36 int=4 luk=4 ap=0' (a companion: 'lv=40 job=110'). */
        public String detail() {
            String head = "lv=" + level + " job=" + job.getId();
            return stats == null ? head : head + " str=" + stats.str() + " dex=" + stats.dex() + " int=" + stats.intel()
                    + " luk=" + stats.luk() + " ap=" + stats.ap();
        }

        /** The original a {@link #detail} names, or null when it is not one. */
        public static Original parse(String detail) {
            Matcher m = detail == null ? null : DETAIL.matcher(detail.trim());
            if (m == null || !m.matches()) {
                return null;
            }
            Job job = Job.getById(Integer.parseInt(m.group(2)));
            if (job == null) {
                return null;
            }
            LevelSetPlan.Stats stats = m.group(3) == null ? null : new LevelSetPlan.Stats(Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)),
                    Integer.parseInt(m.group(7)));
            return new Original(Integer.parseInt(m.group(1)), job, stats);
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

    public GmLevelHolds(LivingWorld world) {
        this.world = world;
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
     * Before a set is applied: decides against the record ({@code existing}, null when it could not be read: nothing is
     * recorded or released then) and records {@code from} now when it is the first change. Settle the result once the
     * set is applied or refused.
     */
    public Pending begin(int characterId, String personaId, Optional<Original> existing, Original from,
                         boolean releasing, boolean changes, int toLevel, Job toJob) {
        Decision decision = existing == null ? Decision.KEEP
                : decide(existing.orElse(null), releasing, changes, toLevel, toJob);
        CompletableFuture<Boolean> written = decision == Decision.RECORD
                ? hold(characterId, personaId, from) : CompletableFuture.completedFuture(true);
        return new Pending(characterId, personaId, decision, written);
    }

    /** A use's record work between {@link #begin} and the set's outcome. */
    public final class Pending {
        private final int characterId;
        private final String personaId;
        private final Decision decision;
        private final CompletableFuture<Boolean> written;

        private Pending(int characterId, String personaId, Decision decision, CompletableFuture<Boolean> written) {
            this.characterId = characterId;
            this.personaId = personaId;
            this.decision = decision;
            this.written = written;
        }

        public Decision decision() {
            return decision;
        }

        /**
         * The set was applied (or, refused or failed, not): a release is written once applied; a record this use made
         * is released again when nothing was applied. Completes true when every row it wrote was saved.
         */
        public CompletableFuture<Boolean> settle(boolean applied) {
            CompletableFuture<Boolean> after = CompletableFuture.completedFuture(true);
            if (applied && decision == Decision.RELEASE) {
                after = release(characterId, personaId, "restored");
            } else if (!applied && decision == Decision.RECORD) {
                after = release(characterId, personaId, "not applied");
            }
            return written.thenCombine(after, Boolean::logicalAnd);
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
