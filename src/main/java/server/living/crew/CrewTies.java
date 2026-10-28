package server.living.crew;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.config.Content.Persona;
import server.living.config.Content.Relationship;
import server.living.config.Content.Spouse;
import server.living.population.records.Clear;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crew ties (docs/BOT_CONCEPT_PLAN.md F6.4): what two companions on one owner's roster are to each other, and how
 * close. The kinds come from content (each persona's {@code relationships} through {@link TieKind#of}, and a lore
 * couple's {@code spouse} while the two are still married); a pair with nothing authored is CREWMATE, and FRIEND once
 * its affinity reaches the friend mark. Affinity is the best seed of its kinds plus a few points per shared clear: a
 * living_clear the owner and both companions were in. Nothing is stored here: an owner's shared clears are read back
 * from living_clear_member once at login (on companion-ops) and kept current by the booked clears (ClearLog), so this
 * is a view, as Standing is. It loads whether or not banter is on, so the story arcs can read it. Reads: any thread.
 */
public final class CrewTies {
    private static final Logger log = LoggerFactory.getLogger(CrewTies.class);
    static final int MAX_AFFINITY = 100;

    /**
     * The tie between personas {@code a} and {@code b} of one owner, seen from {@code a}: its kinds (never empty for two
     * personas; CREWMATE when nothing is authored), its affinity 0-100, and the clears the three of them shared.
     */
    public record Tie(String a, String b, Set<TieKind> kinds, int affinity, int sharedClears) {
        public Tie {
            kinds = kinds.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(kinds));
        }

        public boolean has(TieKind kind) {
            return kinds.contains(kind);
        }
    }

    /** A clear of one owner with its companions in it: their character ids, the quest's script and its time. */
    public record SharedClear(int clearId, int ownerId, String quest, long durationMs, List<Integer> companionIds) {
        public SharedClear {
            companionIds = List.copyOf(companionIds);
        }
    }

    /** What the ties read of the world; tests bring their own. */
    public interface Ports {
        Persona persona(String personaId);

        Collection<Persona> personas();

        /** The persona's character id, 0 when it has none yet. */
        int characterIdOf(String personaId);

        /** The persona id of a character (a hire's {@code hire:<id>}), null for none. */
        String personaIdOfCharacter(int characterId);

        /** The roster owner of the persona, from memory; 0 for none. */
        int ownerOf(String personaId);

        /** The stock partner of a loaded character (0 unmarried), -1 when the character is not loaded. */
        int partnerIdOf(int characterId);

        /**
         * Every living_clear the owner (as a player) was in, with the companions in it (any owner's, as they were), oldest
         * first. Throws when the read fails.
         */
        List<SharedClear> clearsWith(int ownerId);

        Map<TieKind, Integer> seeds();

        int clearPoints();

        int friendAffinity();
    }

    /** One owner's crew as read at login and kept by the booked clears. */
    private static final class Crew {
        /** "a|b" (sorted persona ids) -> clears shared with the owner. */
        final Map<String, Integer> shared = new ConcurrentHashMap<>();
        /** companion character id -> quest script -> its fastest timed clear with this owner, ms. */
        final Map<Integer, Map<String, Long>> bests = new ConcurrentHashMap<>();
        /** Clear ids already counted, so the login read and a booked clear never count one twice. */
        final Set<Integer> counted = ConcurrentHashMap.newKeySet();
    }

    private final Ports ports;
    private final Map<Integer, Crew> crews = new ConcurrentHashMap<>();

    public CrewTies(Ports ports) {
        this.ports = ports;
    }

    // ---- loading (companion-ops) ---------------------------------------------------------------

    /**
     * At an owner's login, on companion-ops: its shared clears read back. A failed read logs and leaves the crew with
     * its seeds only (the login itself never sees it).
     */
    public void load(int ownerId) {
        Crew crew = new Crew();
        try {
            for (SharedClear clear : ports.clearsWith(ownerId)) {
                count(crew, ownerId, clear);
            }
        } catch (RuntimeException failure) {
            log.warn("crew ties of {} could not be read; seeds only until the next login", ownerId, failure);
        }
        crews.put(ownerId, crew);
    }

    /** The owner logged out: its crew is read again at the next login. */
    public void drop(int ownerId) {
        crews.remove(ownerId);
    }

    public boolean loaded(int ownerId) {
        return crews.containsKey(ownerId);
    }

    /**
     * A clear was booked (ClearLog, on companion-ops): each player in it whose crew is loaded counts it for its own
     * companions in it. Returns one SharedClear per such owner with two or more of its companions there (the banter's
     * after-clear moments).
     */
    public List<SharedClear> booked(Clear clear) {
        List<SharedClear> out = new ArrayList<>();
        if (clear == null) {
            return out;
        }
        for (Clear.Member owner : clear.members()) {
            Crew crew = owner.human() ? crews.get(owner.id()) : null;
            if (crew == null) {
                continue;
            }
            List<Integer> own = new ArrayList<>();
            for (Clear.Member member : clear.members()) {
                String personaId = member.kind() == Clear.AS_COMPANION ? ports.personaIdOfCharacter(member.id()) : null;
                if (personaId != null && ports.ownerOf(personaId) == owner.id()) {
                    own.add(member.id());
                }
            }
            SharedClear shared = new SharedClear(clear.id(), owner.id(), clear.quest(), clear.durationMs(), own);
            count(crew, owner.id(), shared);
            if (own.size() >= 2) {
                out.add(shared);
            }
        }
        return out;
    }

    /** Adds one clear to the crew: its pairs among the owner's own companions, and their best times. */
    private void count(Crew crew, int ownerId, SharedClear clear) {
        if (clear.clearId() > 0 && !crew.counted.add(clear.clearId())) {
            return;
        }
        List<String> personas = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (int characterId : clear.companionIds()) {
            String personaId = ports.personaIdOfCharacter(characterId);
            if (personaId != null && ports.ownerOf(personaId) == ownerId && !personas.contains(personaId)) {
                personas.add(personaId);
                ids.add(characterId);
            }
        }
        if (clear.durationMs() > 0L) {
            for (int characterId : ids) {
                crew.bests.computeIfAbsent(characterId, key -> new ConcurrentHashMap<>())
                        .merge(clear.quest(), clear.durationMs(), Math::min);
            }
        }
        for (int i = 0; i < personas.size(); i++) {
            for (int j = i + 1; j < personas.size(); j++) {
                crew.shared.merge(pairKey(personas.get(i), personas.get(j)), 1, Integer::sum);
            }
        }
    }

    // ---- reads (any thread) ---------------------------------------------------------------------

    /**
     * The tie between personas {@code a} and {@code b} on owner {@code ownerId}'s roster, from {@code a}'s side; never
     * null. Before the owner's crew is read (or for an owner offline) its shared clears count 0.
     */
    public Tie tie(int ownerId, String a, String b) {
        Set<TieKind> kinds = authored(a, b);
        Crew crew = crews.get(ownerId);
        Integer counted = crew == null || a == null || b == null ? null : crew.shared.get(pairKey(a, b));
        int shared = counted == null ? 0 : counted;
        int seed = 0;
        for (TieKind kind : kinds) {
            seed = Math.max(seed, ports.seeds().getOrDefault(kind, 0));
        }
        int affinity = Math.min(MAX_AFFINITY, seed + Math.max(0, ports.clearPoints()) * shared);
        if (kinds.isEmpty()) {
            kinds = EnumSet.of(affinity >= ports.friendAffinity() ? TieKind.FRIEND : TieKind.CREWMATE);
        }
        return new Tie(a, b, kinds, affinity, shared);
    }

    /**
     * Every pair of the owner's recruitable personas that are rivals (either side's relationship reads RIVAL), each
     * once, from memory: the roster's owner cache. Hires are never rivals.
     */
    public List<Tie> rivals(int ownerId) {
        List<String> own = new ArrayList<>();
        for (Persona persona : ports.personas()) {
            if (persona.recruitable() && ports.ownerOf(persona.id()) == ownerId) {
                own.add(persona.id());
            }
        }
        own.sort(String::compareTo);
        List<Tie> out = new ArrayList<>();
        for (int i = 0; i < own.size(); i++) {
            for (int j = i + 1; j < own.size(); j++) {
                Tie tie = tie(ownerId, own.get(i), own.get(j));
                if (tie.has(TieKind.RIVAL)) {
                    out.add(tie);
                }
            }
        }
        return out;
    }

    /** The companion's fastest clear of {@code quest} with this owner, ms; 0 for none (or the crew is not read). */
    public long best(int ownerId, int companionCharacterId, String quest) {
        Crew crew = crews.get(ownerId);
        Map<String, Long> bests = crew == null ? null : crew.bests.get(companionCharacterId);
        Long ms = bests == null ? null : bests.get(quest);
        return ms == null ? 0L : ms;
    }

    /** Quest script -> the companion's fastest clear with this owner (ms); empty for none. */
    public Map<String, Long> bests(int ownerId, int companionCharacterId) {
        Crew crew = crews.get(ownerId);
        Map<String, Long> bests = crew == null ? null : crew.bests.get(companionCharacterId);
        return bests == null ? Map.of() : Map.copyOf(bests);
    }

    /** What content says {@code a} is to {@code b}: both sides' relationships (b's mirrored) and a still-married spouse. */
    private Set<TieKind> authored(String a, String b) {
        Set<TieKind> kinds = EnumSet.noneOf(TieKind.class);
        if (a == null || b == null || a.equals(b)) {
            return kinds;
        }
        Persona first = ports.persona(a);
        Persona second = ports.persona(b);
        addRelationships(kinds, first, b, false);
        addRelationships(kinds, second, a, true);
        if (spouses(first, second) && stillMarried(a, b)) {
            kinds.add(TieKind.SPOUSE);
        }
        return kinds;
    }

    private static void addRelationships(Set<TieKind> kinds, Persona persona, String other, boolean mirror) {
        if (persona == null || persona.relationships() == null) {
            return;
        }
        for (Relationship relationship : persona.relationships()) {
            TieKind kind = other.equals(relationship.persona()) ? TieKind.of(relationship.kind()) : null;
            if (kind != null) {
                kinds.add(mirror ? kind.mirror() : kind);
            }
        }
    }

    private static boolean spouses(Persona first, Persona second) {
        return first != null && second != null && (names(first.spouse(), second.id()) || names(second.spouse(), first.id()));
    }

    private static boolean names(Spouse spouse, String personaId) {
        return spouse != null && !spouse.citizen() && personaId.equals(spouse.persona());
    }

    /**
     * True unless a loaded partner's stock marriage names someone else (LoreCouples gives each loaded partner its half, so
     * a loaded lore spouse married to anyone but the other, or to no one, has parted: one wed its owner at the chapel).
     */
    private boolean stillMarried(String a, String b) {
        int first = ports.characterIdOf(a);
        int second = ports.characterIdOf(b);
        return partnerFits(first, second) && partnerFits(second, first);
    }

    private boolean partnerFits(int characterId, int otherId) {
        if (characterId <= 0) {
            return true;
        }
        int partner = ports.partnerIdOf(characterId);
        return partner < 0 || otherId > 0 && partner == otherId;
    }

    static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
