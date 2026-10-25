package server.living.roster;

import client.Character;
import client.Job;
import net.server.coordinator.partysearch.PartySearchWant;
import net.server.world.Party;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Party Search fills from the bench (docs/BOT_MANAGEMENT_PLAN.md flow 11, P9; decisions 2026-10-06). While an owner's
 * stock Party Search runs, each round no human answered (PartySearchCoordinator, about every 10 s) one rested bench
 * companion of theirs whose level and job the search asks for answers it: it comes like a call (join-then-travel, with
 * its ETA whisper) and whispers that it saw the search. The usual lineup answers first, then the level closest to the
 * owner's. Humans still come first: a round a human answered never asks the bench, and a friend who joins later is
 * seated by make-room as before.
 *
 * <p>The round runs on the search's timer, so it only takes a pick prepared a round earlier on companion-ops (the
 * rested check reads the store) and hands the call to companion-ops: the first answer comes in the second round. A pick
 * is for one search (an equal {@link PartySearchWant}) and younger than {@link #PICK_TTL_MS}. The call looks again: the
 * owner still leads their party, outside any event instance, with a seat of their share free, and the companion is
 * still benched, rested and asked for. A companion whose call was refused is passed over for {@link #REFUSED_FOR_MS},
 * so a search never goes on for good on a bench that cannot come.</p>
 */
public final class PartySearchBench {
    /** A pick older than this is prepared again (three search rounds). */
    static final long PICK_TTL_MS = 30_000L;
    /** A companion whose answer was refused is passed over this long. */
    static final long REFUSED_FOR_MS = 10 * 60_000L;
    /** What it whispers after its ETA when its persona has no "partySearch" lines: a player's words. */
    static final List<String> SAW_SEARCH = List.of(
            "saw your party search :)",
            "saw ur party search, count me in",
            "oh nice, a party search. i'm free");

    /** A bench companion's level when it is rested, else 0 (CrewRotation's rule). */
    @FunctionalInterface
    interface RestedLevel {
        int of(Character owner, RosterEntry entry, Persona persona);
    }

    private record Pick(PartySearchWant want, String personaId, long atMs) {
    }

    private final LivingWorld world;
    private final LongSupplier clock;
    private final RestedLevel rested;
    /** owner id -> the bench companion that answers their next unanswered round. */
    private final Map<Integer, Pick> picks = new ConcurrentHashMap<>();
    /** owner id -> persona id -> until when it is passed over after a refused answer. */
    private final Map<Integer, Map<String, Long>> refused = new ConcurrentHashMap<>();

    public PartySearchBench(LivingWorld world) {
        this(world, System::currentTimeMillis,
                (owner, entry, persona) -> world.crewRotation().restedLevel(owner, entry, persona));
    }

    PartySearchBench(LivingWorld world, LongSupplier clock, RestedLevel rested) {
        this.world = world;
        this.clock = clock;
        this.rested = rested;
    }

    /**
     * A round of {@code owner}'s search that no human answered (any thread). True when a bench companion answers it:
     * the pick prepared for this search is called on companion-ops, and the next one is prepared there.
     */
    public boolean round(Character owner, PartySearchWant want) {
        if (owner == null || want == null || !world.config().COMPANION_PARTY_SEARCH_FILL) {
            return false;
        }
        Pick pick = picks.remove(owner.getId());
        boolean answers = pick != null && pick.want().equals(want) && clock.getAsLong() - pick.atMs() < PICK_TTL_MS;
        world.executor().submit("party-search.bench " + owner.getName(), () -> {
            if (answers) {
                answer(owner, want, pick.personaId());
            }
            prepare(owner, want);
        });
        return answers;
    }

    /** Companion-ops: the pick comes like a call, if it still may. */
    private void answer(Character owner, PartySearchWant want, String personaId) {
        Persona persona = world.content().persona(personaId);
        RosterEntry entry = persona == null ? null : world.store().rosterEntry(owner.getId(), personaId);
        if (entry == null || !searching(owner) || level(owner, entry, persona, want) == 0) {
            return;
        }
        RosterResult result = world.roster().call(owner, persona);
        if (result.ok()) {
            world.residents().whisper(persona, owner, line(owner, persona));
            world.store().event(owner.getId(), personaId, "roster.party-search", "answered levels " + want.minLevel()
                    + "-" + want.maxLevel());
        } else {
            refused.computeIfAbsent(owner.getId(), id -> new ConcurrentHashMap<>())
                    .put(personaId, clock.getAsLong() + REFUSED_FOR_MS);
            world.store().event(owner.getId(), personaId, "roster.party-search", "refused: " + result);
        }
    }

    /** Companion-ops: the bench companion that answers the next unanswered round, if any. */
    private void prepare(Character owner, PartySearchWant want) {
        if (!searching(owner)) {
            return;
        }
        Map<String, Integer> lineup = world.store().lineupSlots(owner.getId());
        String best = null;
        int bestSlot = Integer.MAX_VALUE;
        int bestOff = Integer.MAX_VALUE;
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            int level = persona == null ? 0 : level(owner, entry, persona, want);
            if (level == 0) {
                continue;
            }
            int slot = lineup.getOrDefault(persona.id(), Integer.MAX_VALUE);
            int off = Math.abs(level - owner.getLevel());
            if (slot < bestSlot || slot == bestSlot && (off < bestOff || off == bestOff && persona.id().compareTo(best) < 0)) {
                best = persona.id();
                bestSlot = slot;
                bestOff = off;
            }
        }
        if (best != null) {
            picks.put(owner.getId(), new Pick(want, best, clock.getAsLong()));
        }
    }

    /** The owner still leads their party, outside any event instance, with a seat of their share free. */
    private boolean searching(Character owner) {
        Party party = owner.getParty();
        return party != null && party.getLeaderId() == owner.getId() && owner.getEventInstance() == null
                && world.slots().free(owner) > 0;
    }

    /** The level of a benched companion that may answer this search now, else 0 (companion-ops: store reads). */
    private int level(Character owner, RosterEntry entry, Persona persona, PartySearchWant want) {
        if (entry.state() != RosterState.BENCHED || passedOver(owner.getId(), persona.id())) {
            return 0;
        }
        JobErrands errands = world.jobErrands();
        if (errands != null && errands.underway(persona.id())) {
            return 0; // on its way to its instructor: it answers nothing until it is back
        }
        KeystoneErrands keystones = world.keystoneErrands();
        if (keystones != null && keystones.underway(persona.id())) {
            return 0; // out for its Crimsonwood Keystone: the same
        }
        TicketErrands tickets = world.ticketErrands();
        if (tickets != null && tickets.underway(persona.id())) {
            return 0; // out for its Entrance Ticket to the Amorian Challenge: the same
        }
        int level = rested.of(owner, entry, persona);
        if (level <= 0) {
            return 0;
        }
        Character live = world.roster().companion(entry);
        Job job = live != null ? live.getJob()
                : Job.getById(world.store().characterLevelAndJob(world.residents().characterIdOf(persona.id()))[1]);
        return want.wants(level, job) ? level : 0;
    }

    private boolean passedOver(int ownerId, String personaId) {
        Map<String, Long> passed = refused.get(ownerId);
        Long until = passed == null ? null : passed.get(personaId);
        if (until == null) {
            return false;
        }
        if (until > clock.getAsLong()) {
            return true;
        }
        passed.remove(personaId);
        return false;
    }

    private String line(Character owner, Persona persona) {
        List<String> own = world.arcs().lines(persona, "partySearch");
        List<String> lines = own == null || own.isEmpty() ? SAW_SEARCH : own;
        return lines.get(Math.floorMod(owner.getId() + persona.id().hashCode(), lines.size()));
    }
}
