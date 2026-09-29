package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.CitizenInviteDecision;
import server.living.population.Population;
import server.living.store.LivingStore.RosterEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Party-time hire offers (docs/BOT_MANAGEMENT_PLAN.md P4, flow 9 step 4, "Citizen guest"): a citizen guest that has
 * spent {@code HIRE_OFFER_PARTY_MINUTES} in its host's party, counted while both are online side by side in it, offers
 * to stay on when the host has room on their roster and under their hire cap, is not committed to a run, and the
 * citizen's temperament agrees ({@link CitizenInviteDecision#staysOn}). It whispers once in its own voice and the offer
 * stays open for 10 minutes: double-clicking the citizen (CompanionDirectory's HireOfferPending relation) or the hire
 * list opens the terms (HireScreens). One offer at a time per owner. A declined or expired offer keeps that citizen
 * from asking that owner again for {@code HIRE_OFFER_COOLDOWN_MINUTES}.
 *
 * <p>The party minutes are this guest stint's, in memory: living_acquaintance.party_minutes only counts on a shared
 * map, never resets and is written by the population's own throttled tick, so it stays the temperament's measure of
 * how well they know each other. The pending offer, the minutes and the cooldowns live in memory (the acquaintance
 * table has no column for a cooldown) and a restart forgets them. {@link #tick} runs on companion-ops every minute;
 * the reads ({@link #pendingOffers}, {@link #pending}) are safe from any thread. Owned by LivingWorld.</p>
 */
public final class HireOffers {
    private static final Logger log = LoggerFactory.getLogger(HireOffers.class);
    /** How often {@link #tick} runs: one party minute each. */
    public static final long TICK_MS = 60_000L;
    /** A pending offer stays open this long (docs "Citizen guest"). */
    static final long OPEN_MS = 10 * 60_000L;
    /** The offer, in the citizen's voice; the hint after it stays as written. */
    static final String OFFER_LINE = "i like running with you. want me to stick around?";
    static final String OFFER_HINT = " double-click me";
    /** A guest's no, said once a stint in its own voice, so its host is not left waiting for an offer. */
    static final String NOT_YET_LINE = "this is fun. not looking for steady work yet though.";

    /** An open offer: the citizen's character and name, and when it was made and closes. */
    public record Offer(int ownerId, int citizenCharacterId, String name, long offeredAtMs, long expiresAtMs) { }

    private final LivingWorld world;
    private final LongSupplier clock;
    /** Owner -> their one open offer. */
    private final Map<Integer, Offer> pending = new ConcurrentHashMap<>();
    /** (owner, citizen) -> minutes together in this guest stint. */
    private final Map<Long, Integer> minutes = new ConcurrentHashMap<>();
    /** (owner, citizen) -> until when the citizen does not ask that owner again. */
    private final Map<Long, Long> quietUntil = new ConcurrentHashMap<>();
    /** (owner, citizen) pairs whose guest said no this stint already. */
    private final Set<Long> saidNotYet = ConcurrentHashMap.newKeySet();

    public HireOffers(LivingWorld world) {
        this(world, System::currentTimeMillis);
    }

    HireOffers(LivingWorld world, LongSupplier clock) {
        this.world = world;
        this.clock = clock;
    }

    // ---- reads -------------------------------------------------------------------------------

    /** The owner's open offers (at most one), for the hire list to put on top. */
    public List<Offer> pendingOffers(Character owner) {
        Offer offer = owner == null ? null : open(owner.getId());
        return offer == null ? List.of() : List.of(offer);
    }

    /** The owner's open offer from this citizen, or null. */
    public Offer pending(int ownerId, int citizenCharacterId) {
        Offer offer = open(ownerId);
        return offer != null && offer.citizenCharacterId() == citizenCharacterId ? offer : null;
    }

    private Offer open(int ownerId) {
        Offer offer = pending.get(ownerId);
        return offer == null || clock.getAsLong() >= offer.expiresAtMs() || settled(offer) ? null : offer;
    }

    /** Hired (by anyone) or gone from the population: the offer is over without an answer to it. */
    private boolean settled(Offer offer) {
        if (world.roster().cachedOwnerOf(HiredHands.hireId(offer.citizenCharacterId())) != 0) {
            return true;
        }
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizen(offer.citizenCharacterId());
        return citizen == null || citizen.state() != Citizen.State.ACTIVE || citizen.hired();
    }

    // ---- answers -----------------------------------------------------------------------------

    /**
     * The owner declined this citizen's terms (HireScreens): an open offer from it closes, and it does not ask them again
     * for a while. Nothing happens when it had not offered. On companion-ops.
     */
    public void declined(int ownerId, int citizenCharacterId) {
        Offer offer = pending.get(ownerId);
        if (offer == null || offer.citizenCharacterId() != citizenCharacterId || !pending.remove(ownerId, offer)) {
            return;
        }
        close(offer, "declined");
    }

    // ---- the minute --------------------------------------------------------------------------

    /**
     * Every minute on companion-ops: open offers past their 10 minutes expire, then every citizen guest together with
     * its host (both online, one party) counts a minute, and one that has counted enough may offer.
     */
    public void tick() {
        long now = clock.getAsLong();
        for (Offer offer : pending.values()) {
            if (settled(offer)) {
                pending.remove(offer.ownerId(), offer); // hired (the accept is logged by HiredHands) or gone
            } else if (now >= offer.expiresAtMs() && pending.remove(offer.ownerId(), offer)) {
                close(offer, "expired");
            }
        }
        quietUntil.values().removeIf(until -> now >= until);
        Population population = Population.get();
        if (population == null) {
            minutes.clear();
            return;
        }
        Set<Long> guests = new HashSet<>();
        for (CompanionSlots.Reservation held : world.slots().guests()) {
            int citizenId = CompanionSlots.characterIdOf(held.key());
            if (citizenId <= 0) {
                continue;
            }
            long pair = pair(held.ownerId(), citizenId);
            guests.add(pair);
            Character owner = world.roster().onlineCharacter(held.ownerId());
            Character guest = loaded(citizenId);
            if (!together(owner, guest)) {
                continue; // counted only while both are online in one party, side by side
            }
            int together = minutes.merge(pair, 1, Integer::sum);
            if (together >= world.config().HIRE_OFFER_PARTY_MINUTES) {
                maybeOffer(population, owner, guest, pair, together, now);
            }
        }
        minutes.keySet().retainAll(guests); // a guest that left the party starts over the next time
        saidNotYet.retainAll(guests);
    }

    /**
     * The guest's time is up: it asks unless the owner has an offer open, it is keeping quiet with them, the owner is
     * committed to a run, or their roster or hire cap is full (then it asks once that changes); its temperament may say
     * no (once a stint, in its own words), and it thinks about it again after another stint's minutes, a little warmer
     * for the time together.
     */
    private void maybeOffer(Population population, Character owner, Character guest, long pair, int together, long now) {
        int ownerId = owner.getId();
        int citizenId = guest.getId();
        Citizen citizen = population.citizen(citizenId);
        if (citizen == null || citizen.state() != Citizen.State.ACTIVE || citizen.hired() || open(ownerId) != null
                || quiet(pair, now) || committed(owner) || !roomFor(owner)) {
            return;
        }
        minutes.remove(pair);
        HiredHands hands = world.hiredHands();
        String hireId = HiredHands.hireId(citizenId);
        Persona persona = hands == null ? null : hands.persona(hireId);
        if (persona == null) {
            return;
        }
        if (!CitizenInviteDecision.staysOn(owner, guest, citizen)) {
            if (saidNotYet.add(pair)) {
                world.residents().whisper(persona, owner, hands.voice(citizenId, NOT_YET_LINE));
            }
            return;
        }
        Offer offer = new Offer(ownerId, citizenId, citizen.name(), now, now + OPEN_MS);
        pending.put(ownerId, offer);
        world.residents().whisper(persona, owner, hands.voice(citizenId, OFFER_LINE) + OFFER_HINT);
        world.store().event(ownerId, hireId, HiredHands.EVENT_OFFER, "after " + together + " party minutes");
        log.info("{} offered to stay on with {}", citizen.name(), owner.getName());
    }

    private void close(Offer offer, String how) {
        long pair = pair(offer.ownerId(), offer.citizenCharacterId());
        quietUntil.put(pair, clock.getAsLong() + world.config().HIRE_OFFER_COOLDOWN_MINUTES * 60_000L);
        world.store().event(offer.ownerId(), HiredHands.hireId(offer.citizenCharacterId()), HiredHands.EVENT_OFFER, how);
    }

    private boolean quiet(long pair, long now) {
        Long until = quietUntil.get(pair);
        return until != null && now < until;
    }

    /** In an event instance, or a companion of theirs tied to a run. */
    private boolean committed(Character owner) {
        return owner.getEventInstance() != null || world.commitments().ownerCommitted(owner.getId());
    }

    /** Room for one more on the roster and under the hire cap (one roster read). */
    private boolean roomFor(Character owner) {
        List<RosterEntry> rows = world.roster().roster(owner);
        int hires = 0;
        for (RosterEntry row : rows) {
            hires += LivingContent.isHire(row.personaId()) ? 1 : 0;
        }
        return rows.size() < world.config().ROSTER_MAX && hires < world.config().HIRE_MAX_PER_OWNER;
    }

    /** One party, one channel and one map (as living_acquaintance's map minutes count): the time spent side by side. */
    private static boolean together(Character owner, Character guest) {
        return owner != null && guest != null && owner.getParty() != null && guest.getParty() != null
                && owner.getParty().getId() == guest.getParty().getId() && RosterService.sameChannel(owner, guest)
                && owner.getMapId() == guest.getMapId();
    }

    private static Character loaded(int characterId) {
        return AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(characterId));
    }

    private static long pair(int ownerId, int citizenId) {
        return ((long) ownerId << 32) | (citizenId & 0xffffffffL);
    }
}
