package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyEvents;
import server.living.LivingWorld;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * The {@link CompanionView} cache (docs/BOT_MANAGEMENT_PLAN.md, "Menus ... render from the CompanionView cache, with no
 * DB read per render"). Per owner it keeps their roster rows, what each view needs from the database and their usual
 * lineup; a render adds only what lives in memory (the loaded character's HP, MP, EXP and map, the time left on the way,
 * a commitment). Every roster write (RosterService.write, the bench's progress and haul writes, a bond stage change, a
 * usual lineup saved) and every party change drops the owner's entry, and the next render reads it again. Guests are never cached: they are read from the seats
 * charged to the owner and their live characters.
 *
 * <p>Renders run on the owner's packet thread and invalidations on companion-ops (or a party thread): an entry read
 * before an invalidation is never stored after it.</p>
 */
public final class CompanionViews {
    private final LivingWorld world;
    private final IntFunction<String> placeNames;
    private final IntFunction<String> itemNames;
    private final Map<Integer, Crew> crews = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicLong> versions = new ConcurrentHashMap<>();
    /** Owner id -> persona id -> version, for the screens about one companion; made when such a screen is stamped. */
    private final Map<Integer, Map<String, AtomicLong>> rowVersions = new ConcurrentHashMap<>();

    /** One owner's rows, in roster order, with what their views read from the database, and their usual lineup. */
    private record Crew(List<RosterEntry> rows, Map<String, CompanionView.Stored> stored, Set<String> lineup) { }

    public CompanionViews(LivingWorld world) {
        this(world, CompanionView::placeName, CompanionView::itemName);
    }

    /** {@code placeNames} and {@code itemNames} name a camp's map and a farmed item (the game's data, by default). */
    public CompanionViews(LivingWorld world, IntFunction<String> placeNames, IntFunction<String> itemNames) {
        this.world = world;
        this.placeNames = placeNames;
        this.itemNames = itemNames;
    }

    /** {@link #invalidate} on the world's cache; a world without one (a test's) has nothing to drop. */
    public static void invalidate(LivingWorld world, int ownerId) {
        CompanionViews views = world == null ? null : world.views();
        if (views != null) {
            views.invalidate(ownerId);
        }
    }

    /** As {@link #invalidate(LivingWorld, int)} for a change to one companion's row: only its own screens go stale. */
    public static void invalidate(LivingWorld world, int ownerId, String personaId) {
        CompanionViews views = world == null ? null : world.views();
        if (views != null) {
            views.invalidate(ownerId, personaId);
        }
    }

    /** The owner's roster rows (never RELEASED), in roster order. */
    public List<RosterEntry> rows(int ownerId) {
        return crew(ownerId).rows();
    }

    /** The persona ids with a place in the owner's usual lineup (lineup_slot). */
    public Set<String> lineup(int ownerId) {
        return crew(ownerId).lineup();
    }

    /** Every row of the owner's as a view, in roster order. */
    public List<CompanionView> crew(Character owner) {
        Crew crew = crew(owner.getId());
        List<CompanionView> out = new ArrayList<>(crew.rows().size());
        for (RosterEntry entry : crew.rows()) {
            out.add(render(crew.stored().get(entry.personaId())));
        }
        return out;
    }

    /** One of the owner's companions as a view; null when it is not in their roster. */
    public CompanionView view(int ownerId, String personaId) {
        CompanionView.Stored stored = crew(ownerId).stored().get(personaId);
        return stored == null ? null : render(stored);
    }

    /** The owner's row of the persona, from the cache; null when it is not in their roster. */
    public RosterEntry row(int ownerId, String personaId) {
        CompanionView.Stored stored = crew(ownerId).stored().get(personaId);
        return stored == null ? null : stored.entry;
    }

    /** {@link #row} only while the owner's crew is cached (nothing is read); null otherwise or when it is not theirs. */
    public RosterEntry cachedRow(int ownerId, String personaId) {
        Crew cached = crews.get(ownerId);
        CompanionView.Stored stored = cached == null ? null : cached.stored().get(personaId);
        return stored == null ? null : stored.entry;
    }

    /**
     * The citizen guests in the owner's party: the GUEST seats charged to them (CompanionSlots) whose citizen is a member
     * online now, each marked {@code guest}. Nothing here is cached or read from the database.
     */
    public List<CompanionView> guests(Character owner) {
        List<CompanionView> out = new ArrayList<>();
        Party party = owner.getParty();
        if (party == null) {
            return out;
        }
        for (CompanionSlots.Reservation held : world.slots().reservations(owner.getId())) {
            if (held.kind() != CompanionSlots.Kind.GUEST) {
                continue;
            }
            for (PartyCharacter member : party.getMembers()) {
                Character citizen = member.getPlayer();
                if (citizen != null && held.key().equals(CompanionSlots.characterKey(member.getId()))) {
                    out.add(CompanionView.guest(citizen, held.key()));
                }
            }
        }
        return out;
    }

    /**
     * The owner's cache version, bumped by every invalidation: a screen that keeps the version it rendered at can tell
     * a click on data that has changed since ("things changed") and render again instead of acting.
     */
    public long version(int ownerId) {
        return versions.computeIfAbsent(ownerId, id -> new AtomicLong()).get();
    }

    /**
     * One companion's version, for a screen about that companion alone (its card and pages): bumped by a change to its
     * row, or by a change to the owner's rows that names no row, but not by another companion's arrival or duty.
     */
    public long version(int ownerId, String personaId) {
        return rowVersions.computeIfAbsent(ownerId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(personaId, id -> new AtomicLong()).get();
    }

    /** Drops the owner's rows: the next render reads them again. Every screen of theirs is stale. */
    public void invalidate(int ownerId) {
        dropCrew(ownerId);
        Map<String, AtomicLong> rows = rowVersions.get(ownerId);
        if (rows != null) {
            rows.values().forEach(AtomicLong::incrementAndGet);
        }
    }

    /** Drops the owner's rows after a change to {@code personaId}'s row: its screens and the crew-wide ones are stale. */
    public void invalidate(int ownerId, String personaId) {
        dropCrew(ownerId);
        Map<String, AtomicLong> rows = rowVersions.get(ownerId);
        AtomicLong row = rows == null || personaId == null ? null : rows.get(personaId);
        if (row != null) {
            row.incrementAndGet();
        }
    }

    /** The owner-wide version and the cached rows only (the crew-wide screens). */
    private void dropCrew(int ownerId) {
        AtomicLong version = versions.get(ownerId);
        if (version == null) {
            return; // nothing of theirs was ever read or stamped, so nothing can be stale (and no entry is made)
        }
        version.incrementAndGet();
        crews.remove(ownerId);
    }

    /**
     * A committed party change: every character in the party before or after it (a human is an owner) and the owner of
     * every companion among them.
     */
    public void partyChanged(PartyEvents.PartyChangeEvent event) {
        Set<Integer> ids = new HashSet<>(event.before().memberIds());
        ids.addAll(event.after().memberIds());
        ids.add(event.targetId());
        CompanionDirectory directory = world.directory();
        for (int id : ids) {
            dropCrew(id); // a human's party: their crew-wide screens; a companion's own screens only when it moved
            int owner = directory == null ? 0 : directory.ownerOfCharacter(id);
            if (owner != 0) {
                server.living.config.Content.Persona persona = world.residents() == null ? null
                        : world.residents().personaOfCharacterId(id);
                if (persona != null) {
                    invalidate(owner, persona.id());
                } else {
                    invalidate(owner);
                }
            }
        }
    }

    private CompanionView render(CompanionView.Stored stored) {
        RosterEntry entry = stored.entry;
        long travelMs = entry.state() != RosterState.TRAVELLING ? 0L : Math.max(0L, entry.etaAtMs() - System.currentTimeMillis());
        return CompanionView.live(world, stored, travelMs);
    }

    private Crew crew(int ownerId) {
        Crew cached = crews.get(ownerId);
        if (cached != null) {
            return cached;
        }
        AtomicLong version = versions.computeIfAbsent(ownerId, id -> new AtomicLong());
        long readAt = version.get();
        List<RosterEntry> rows = List.copyOf(world.store().roster(ownerId));
        Map<String, CompanionView.Stored> stored = new LinkedHashMap<>();
        for (RosterEntry entry : rows) {
            stored.put(entry.personaId(), CompanionView.Stored.read(world, entry, placeNames, itemNames));
        }
        Crew read = new Crew(rows, Map.copyOf(stored), Set.copyOf(world.store().lineupSlots(ownerId).keySet()));
        // kept only when nothing was invalidated while it was read; the check and the store are one step
        crews.compute(ownerId, (id, current) -> version.get() == readAt ? read : current);
        return read;
    }
}
