package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.processor.npc.DueyProcessor;
import constants.inventory.ItemConstants;
import net.server.Server;
import net.server.world.CauseScope;
import net.server.world.Party;
import net.server.world.PartyEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's recruited companions: who is active in the party, who is benched at a camp, slot
 * accounting under the 6-member cap, recall/bench/swap, the haul hand-over, and the apprentice
 * EXP bonus. Companion spawning reuses the {@code !spawnbot} path so the bot behaves exactly
 * like a hand-spawned companion once it is in the party.
 */
public final class RosterService {
    private static final Logger log = LoggerFactory.getLogger(RosterService.class);
    public static final int PARTY_CAP = 6;
    /** The cause token of the roster's own party changes: the sync takes their events as echoes. */
    public static final String CAUSE = "companion.roster";
    private static final String BUSY_LINE = "give me a moment. i'm in the middle of something.";
    private static final String PARTY_FULL_LINE = "your party's full. i'll wait at camp.";
    /** A companion refused a seat its owner's leader cap holds empty (CompanionSlots, the leader cap). */
    public static final String LEADER_CAP_LINE = "the leader sent one of us out. i'll sit this party out at camp.";
    /** Leader-cap refusals to one owner this close together are one burst: only the first whispers. */
    static final long LEADER_CAP_BURST_MS = 3_000L;
    private static final String ROSTER_BOOK_LINE = "double-click me or the book to manage the crew.";

    private final LivingWorld world;
    /** persona id -> owner character id, cached from the store. */
    private final Map<String, Integer> ownerByPersona = new ConcurrentHashMap<>();
    private final Map<String, Long> ownerLogoutAtMs = new ConcurrentHashMap<>();
    /** owner:persona -> arrival time of a companion travelling from its camp. */
    private final Map<String, Long> travelling = new ConcurrentHashMap<>();
    /** owner id -> when a companion last whispered them the leader cap. */
    private final Map<Integer, Long> leaderCapSaidAtMs = new ConcurrentHashMap<>();
    /** Character ids with at least one roster entry; consulted on every chat line, so cached. */
    private final java.util.Set<Integer> owners = ConcurrentHashMap.newKeySet();
    /** Persona ids being seated right now: one load per companion, whichever thread calls it. */
    private final java.util.Set<String> seating = ConcurrentHashMap.newKeySet();
    /** Persona id -> the walk-out of a companion benched a moment ago; any despawn or a new seat ends it. */
    private final Map<String, Object> leaving = new ConcurrentHashMap<>();

    public RosterService(LivingWorld world) {
        this.world = world;
        for (RosterEntry entry : world.store().allActive()) {
            ownerByPersona.put(entry.personaId(), entry.characterId());
            owners.add(entry.characterId());
        }
        for (RosterEntry entry : world.store().allBenched()) {
            ownerByPersona.put(entry.personaId(), entry.characterId());
            owners.add(entry.characterId());
            if (entry.state() == RosterState.AWAY) {
                keepAwaySeat(entry.characterId(), entry.personaId()); // reservations live in memory: rebuilt at boot
            }
        }
    }

    /** True when the player has recruited anyone (grants companion-command authority). */
    public boolean hasCompanions(Character chr) {
        return chr != null && owners.contains(chr.getId());
    }

    // ---- queries -------------------------------------------------------------------------

    public boolean isOwned(String personaId) {
        return ownerOf(personaId) != 0;
    }

    public int ownerOf(String personaId) {
        Integer cached = ownerByPersona.get(personaId);
        if (cached != null) {
            return cached;
        }
        RosterEntry entry = world.store().rosterOfPersona(personaId);
        if (entry == null) {
            return 0;
        }
        ownerByPersona.put(personaId, entry.characterId());
        return entry.characterId();
    }

    /**
     * The owner from memory only (0 = unowned), for hot paths: the cache is loaded with every
     * non-released row at boot and kept in step by recruit, escort and release.
     */
    public int cachedOwnerOf(String personaId) {
        Integer cached = personaId == null ? null : ownerByPersona.get(personaId);
        return cached == null ? 0 : cached;
    }

    public List<RosterEntry> roster(Character chr) {
        return world.store().roster(chr.getId());
    }

    public RosterEntry entry(int characterId, String personaId) {
        return world.store().rosterEntry(characterId, personaId);
    }

    public Character onlineCharacter(int characterId) {
        try {
            return Server.getInstance().getWorld(world.config().WORLD).getPlayerStorage().getCharacterById(characterId);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Free party seats for this owner's companions and guests, from the slot authority: members and the reserved
     * seats (an errand's AWAY, a traveller's) under the fair share of the humans in the party (plan P2a).
     */
    public int freeSlots(Character owner) {
        return world.slots().free(owner);
    }

    /** True when the companion already holds a seat (away, on its way) or the owner has one free for it. */
    public boolean canSeat(Character owner, RosterEntry entry) {
        CompanionSlots.Reservation held = world.slots().reservation(entry.personaId());
        return held != null && held.ownerId() == owner.getId() || freeSlots(owner) > 0;
    }

    /** An AWAY row keeps its party seat until it is called back, benched or released (plan 11.2). */
    private void keepAwaySeat(int ownerId, String personaId) {
        world.slots().reserve(ownerId, personaId, CompanionSlots.Kind.AWAY, Long.MAX_VALUE);
    }

    /** The companion character of a roster entry when it is online, else null. */
    public Character companion(RosterEntry entry) {
        return loaded(entry.personaId());
    }

    /** The persona's character when it is loaded for anything (a Resident, a live bench slice, a companion), else null. */
    Character loaded(String personaId) {
        int characterId = world.residents().characterIdOf(personaId);
        if (characterId == 0) {
            return null;
        }
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        return runtime == null ? null : AgentRuntimeIdentityRuntime.bot(runtime);
    }

    /**
     * Where the persona's character stands when it is loaded on the owner's map right now (a Resident, a live bench
     * slice, a companion still walking out), else null: a call brings it back in from there, not from a portal.
     */
    private Point standingNear(Character owner, String personaId) {
        Character live = loaded(personaId);
        MapleMap map = owner.getMap();
        return live == null || map == null || live.getMap() != map || live.getPosition() == null ? null : new Point(live.getPosition());
    }

    /** Milliseconds until a companion called from its camp reaches the owner; 0 when it is not on the way. */
    public long travelMsLeft(int ownerId, String personaId) {
        Long arriving = travelling.get(ownerId + ":" + personaId);
        return arriving == null ? 0L : Math.max(0L, arriving - System.currentTimeMillis());
    }

    /** True while a verb is under way for the companion: it is being seated, walking out, or on its way over. */
    public boolean inFlight(int ownerId, String personaId) {
        return seating.contains(personaId) || leaving.containsKey(personaId) || travelMsLeft(ownerId, personaId) > 0L;
    }

    /** True when the persona is active in the owner's party and on the owner's map (plan 5.3). */
    public boolean coPresent(Character owner, String personaId) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), personaId);
        if (entry == null || entry.state() != RosterState.ACTIVE) {
            return false;
        }
        Character bot = companion(entry);
        if (bot == null || bot.getMap() == null || owner.getMap() == null) {
            return false;
        }
        return bot.getMap().getId() == owner.getMap().getId() && sameParty(owner, bot);
    }

    private static boolean sameParty(Character owner, Character bot) {
        Party party = owner.getParty();
        return party != null && bot.getParty() != null && party.getId() == bot.getParty().getId();
    }

    // ---- recruit / release ---------------------------------------------------------------

    /** Adds the persona to the owner's roster and spawns it as an active companion. */
    public RosterResult recruit(Character owner, Persona persona) {
        if (isOwned(persona.id())) {
            int current = ownerOf(persona.id());
            return current == owner.getId() ? new RosterResult.Ok(persona.name() + " is already yours.")
                    : new RosterResult.Failed(persona.name() + " already travels with someone else.");
        }
        if (roster(owner).size() >= world.config().ROSTER_MAX) {
            return new RosterResult.RosterFull(world.config().ROSTER_MAX);
        }
        Point standing = standingNear(owner, persona.id()); // before enroll hands the Resident's character over
        RosterEntry entry = enroll(owner, persona, "roster.recruit");
        RosterResult result = freeSlots(owner) <= 0
                ? new RosterResult.Ok(persona.name() + " joins your roster but your party is full; call them when there is room.")
                : spawnCompanion(owner, persona, entry, standing);
        if (grantRosterBook(owner)) {
            world.residents().whisper(persona, owner, ROSTER_BOOK_LINE);
        }
        return result;
    }

    /**
     * GM grant ({@code !grantcompanion}, for tests and demos): the persona joins the owner's roster
     * benched at its home-town camp, with no contract and no change to its character. It counts
     * toward {@code ROSTER_MAX} and keeps one owner per persona like a recruit; the owner calls it
     * in the normal way.
     */
    public String grant(Character owner, Persona persona) {
        if (isOwned(persona.id())) {
            return persona.name() + (ownerOf(persona.id()) == owner.getId()
                    ? " is already on " + owner.getName() + "'s roster." : " already travels with someone else.");
        }
        if (roster(owner).size() >= world.config().ROSTER_MAX) {
            return owner.getName() + "'s roster is full (" + world.config().ROSTER_MAX + ").";
        }
        RosterEntry entry = enroll(owner, persona, "roster.grant");
        grantRosterBook(owner);
        return persona.name() + " joins " + owner.getName() + "'s roster, benched at map " + entry.campMapId() + ".";
    }

    /**
     * The Adventurer's Roster (plan flow 9 step 3) with the stock item-gain line, for an owner who carries none:
     * the Crew window from anywhere, even with every companion benched. It is a convenience, so a full inventory
     * or a failure never stops a recruit or a login; the next login tries again.
     */
    private boolean grantRosterBook(Character owner) {
        int book = world.config().ROSTER_BOOK_ITEM_ID;
        Client client = owner.getClient();
        if (book <= 0 || client == null) {
            return false;
        }
        try {
            if (owner.haveItem(book) || !InventoryManipulator.checkSpace(client, book, 1, "")
                    || !InventoryManipulator.addById(client, book, (short) 1)) {
                return false;
            }
            owner.sendPacket(PacketCreator.getShowItemGain(book, (short) 1, true));
            return true;
        } catch (RuntimeException failure) {
            log.warn("roster book for {} failed", owner.getName(), failure);
            return false;
        }
    }

    /** Writes a BENCHED roster row and hands the persona over from its Resident life. */
    private RosterEntry enroll(Character owner, Persona persona, String eventType) {
        long now = System.currentTimeMillis();
        int campMap = server.living.resident.ResidentSpots.townMapId(persona.homeTown());
        RosterEntry entry = new RosterEntry(owner.getId(), persona.id(), RosterState.BENCHED, Assignment.TRAIN, 0, 0,
                campMap, 0L, 0L, 0L, now, now, now, 0, null);
        world.store().saveRoster(entry);
        ownerByPersona.put(persona.id(), owner.getId());
        owners.add(owner.getId());
        world.store().savePersonaState(world.store().personaState(persona.id()).withEmployer(owner.getId()));
        world.store().setWorldFlag("persona." + persona.id() + ".employed", owner.getId());
        world.residents().releaseForRecruit(persona.id());
        world.store().event(owner.getId(), persona.id(), eventType, null);
        return entry;
    }

    /** Drops the persona from the roster; it goes home as a Resident. */
    public String release(Character owner, Persona persona, String reason) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return persona.name() + " is not in your roster.";
        }
        world.benchSessions().endFor(persona.id(), "released");
        despawn(entry, "released");
        world.slots().release(persona.id());
        world.slots().stopWaiting(persona.id());
        world.store().saveRoster(entry.with(RosterState.RELEASED, System.currentTimeMillis()));
        world.store().deleteRoster(owner.getId(), persona.id());
        ownerByPersona.remove(persona.id());
        if (roster(owner).isEmpty()) {
            owners.remove(owner.getId());
        }
        world.store().savePersonaState(world.store().personaState(persona.id()).withEmployer(0));
        world.store().clearWorldFlag("persona." + persona.id() + ".employed");
        world.contracts().onReleased(owner, persona, reason);
        world.store().event(owner.getId(), persona.id(), "roster.release", reason);
        return persona.name() + " heads home to " + persona.homeTown().replace('-', ' ') + ".";
    }

    // ---- call / bench / swap -------------------------------------------------------------

    /**
     * Calls a companion: benched ones travel from their camp first (plan 11.3) unless a whistle is used. Once it is
     * seated or on its way it no longer waits for room.
     */
    public RosterResult call(Character owner, Persona persona) {
        RosterResult result = call(owner, persona, null);
        if (result.ok()) {
            world.slots().stopWaiting(persona.id());
        }
        return result;
    }

    /** {@code standing}: where it stood on the owner's map before its Resident life let it go (an escort), else null. */
    private RosterResult call(Character owner, Persona persona, Point standing) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return new RosterResult.NotOwned();
        }
        if (entry.state() == RosterState.ACTIVE && companion(entry) != null) {
            return new RosterResult.Ok(persona.name() + " is already with you.");
        }
        if (!canSeat(owner, entry)) {
            return world.slots().leaderCapped(owner) ? leaderCapped(owner, persona) : new RosterResult.PartyFull();
        }
        boolean away = entry.state() == RosterState.AWAY;
        String key = owner.getId() + ":" + persona.id();
        Long arriving = travelling.get(key);
        if (arriving != null && arriving > System.currentTimeMillis()) {
            return new RosterResult.Ok(persona.name() + " is already on the way ("
                    + RecallTravel.describe(arriving - System.currentTimeMillis()) + ").");
        }
        if ((entry.state() == RosterState.BENCHED || away) && owner.getMap() != null) {
            int campMap = entry.campMapId() == 0 ? server.living.resident.ResidentSpots.townMapId(persona.homeTown()) : entry.campMapId();
            long eta = RecallTravel.etaMs(campMap, owner.getMapId());
            if (eta > 0L && !consumeWhistle(owner)) {
                long now = System.currentTimeMillis();
                long arrivesAt = now + eta;
                travelling.put(key, arrivesAt);
                if (world.slots().reservation(persona.id()) == null) { // an AWAY companion keeps its own seat meanwhile
                    world.slots().reserve(owner.getId(), persona.id(), CompanionSlots.Kind.TRAVEL,
                            now + eta + CompanionSlots.TRAVEL_GRACE_MS);
                }
                List<String> lines = world.arcs().lines(persona, "onMyWay");
                world.residents().whisper(persona, owner, (lines.isEmpty() ? "on my way." : lines.get(Math.floorMod(owner.getId(), lines.size())))
                        + " " + RecallTravel.describe(eta) + ".");
                world.store().event(owner.getId(), persona.id(), "roster.travel", "eta " + eta + " ms from map " + campMap);
                later("roster.arrive " + persona.id(), () -> arrive(owner.getId(), persona, arrivesAt), eta);
                return new RosterResult.Travelling(eta, campMap);
            }
        }
        travelling.remove(key);
        return spawnCompanion(owner, persona, entry, standing);
    }

    /**
     * The leader's expel holds the seat empty: the companion says so in its words, once for a burst of refusals to
     * the same owner ("call the usual", the Crew window and chat in one breath).
     */
    private RosterResult leaderCapped(Character owner, Persona persona) {
        long now = System.currentTimeMillis();
        Long last = leaderCapSaidAtMs.put(owner.getId(), now);
        if (last == null || now - last >= LEADER_CAP_BURST_MS) {
            world.residents().whisper(persona, owner, LEADER_CAP_LINE);
        }
        return new RosterResult.LeaderCapped();
    }

    /** Ends the trip due {@code arrivesAt}; a trip called off (benched on the way) or replaced does nothing. */
    private void arrive(int ownerId, Persona persona, long arrivesAt) {
        if (!travelling.remove(ownerId + ":" + persona.id(), arrivesAt)) {
            return; // bench() gave its seat back when it called the trip off; a newer trip holds its own
        }
        Character owner = onlineCharacter(ownerId);
        RosterEntry entry = owner == null ? null : world.store().rosterEntry(ownerId, persona.id());
        if (entry == null || !(entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY)) {
            world.slots().release(persona.id(), CompanionSlots.Kind.TRAVEL); // the trip is over
            return;
        }
        if (entry.state() == RosterState.BENCHED && !canSeat(owner, entry)) {
            world.residents().whisper(persona, owner, "your party's full. i'll head back to camp.");
            return;
        }
        seatQuietly(ownerId, persona, entry.state());
    }

    /** True when the owner carried a recall whistle and one was consumed. */
    private boolean consumeWhistle(Character owner) {
        int whistle = world.config().RECALL_WHISTLE_ITEM_ID;
        if (whistle <= 0) {
            return false;
        }
        client.inventory.InventoryType type = constants.inventory.ItemConstants.getInventoryType(whistle);
        if (owner.getInventory(type).countById(whistle) <= 0) {
            return false;
        }
        InventoryManipulator.removeById(owner.getClient(), type, whistle, 1, false, false);
        owner.dropMessage(5, "You blow the whistle.");
        return true;
    }

    /**
     * Loads the companion where it walks over from (plan P1.5, no pop-in): the spot it stood on when it is on the
     * owner's map already ({@code standing}, captured by a caller that let it go first, or found in {@link #seat}),
     * else the portal nearest the owner; a map without a usable one keeps the beside-owner spawn.
     */
    private RosterResult spawnCompanion(Character owner, Persona persona, RosterEntry entry, Point standing) {
        long waitMs = world.budget().loadDelayMs(owner, System.currentTimeMillis());
        if (waitMs > 0L) {
            spawnLater(owner.getId(), persona, entry.state(), waitMs); // loads are spaced per owner: queue, not refuse
            return new RosterResult.Ok(persona.name() + " will be right there.");
        }
        if (!seating.add(persona.id())) {
            return new RosterResult.Ok(persona.name() + " is on the way."); // another call is loading this companion right now
        }
        try {
            return seat(owner, persona, entry, standing);
        } finally {
            seating.remove(persona.id());
        }
    }

    /**
     * Reserve, load, then commit or roll back (plan "CompanionExecutor"): the seat is held before anything is loaded,
     * so the party's seat gate admits the join. A seated companion is a member and needs no reservation; a failed load
     * gives back only a travel seat, since an AWAY or HOLD one stays with its row.
     */
    private RosterResult seat(Character owner, Persona persona, RosterEntry entry, Point standing) {
        int characterId = world.residents().ensureCharacter(persona);
        if (characterId == 0) {
            return new RosterResult.Failed(persona.name() + " cannot be reached right now.");
        }
        RosterResult result;
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            result = CompanionExecutor.transact(
                    () -> holdSeat(owner, persona),
                    held -> load(owner, persona, entry, standing, characterId),
                    RosterResult::ok,
                    held -> world.slots().release(persona.id()),
                    held -> world.slots().release(persona.id(), CompanionSlots.Kind.TRAVEL));
        }
        return result != null ? result : refuse(owner, persona, entry, BUSY_LINE, "seat held by another owner", new RosterResult.Busy());
    }

    /** The seat this load uses: one it holds already (away, travelling), else a seat taken now for the load. */
    private CompanionSlots.Reservation holdSeat(Character owner, Persona persona) {
        CompanionSlots.Reservation held = world.slots().reservation(persona.id());
        if (held == null || held.ownerId() != owner.getId()) {
            world.slots().reserve(owner.getId(), persona.id(), CompanionSlots.Kind.TRAVEL,
                    System.currentTimeMillis() + CompanionSlots.SEAT_HOLD_MS);
            held = world.slots().reservation(persona.id());
        }
        return held != null && held.ownerId() == owner.getId() ? held : null;
    }

    private RosterResult load(Character owner, Persona persona, RosterEntry entry, Point standing, int characterId) {
        CompanionBudget.Admission admission = world.budget().admit(CompanionBudget.Kind.COMPANION, owner);
        if (!admission.allowed()) {
            return refuse(owner, persona, entry, "give me a moment. too many of us are out right now.", admission.reason(),
                    admission.atCeiling() ? new RosterResult.BudgetExhausted() : new RosterResult.QuotaFull());
        }
        Point from = standing != null ? standing : standingNear(owner, persona.id()); // before it is let go below
        if (leaving.containsKey(persona.id())) {
            despawn(entry, "called back on its way out"); // it comes back from where it stood, so it frees its lease first
        }
        world.benchSessions().endFor(persona.id(), "called by owner"); // a live slice ends properly and frees its lease
        world.residents().releaseForRecruit(persona.id());
        // a character loaded for something else is refused, never pulled out from under it
        if (!world.leases().tryAcquire(characterId, CompanionLeases.Kind.COMPANION, owner.getId())) {
            return refuse(owner, persona, entry, BUSY_LINE, "busy", new RosterResult.Busy());
        }
        if (AgentRuntimeRegistry.hasActiveAgentCharacterId(characterId)
                && AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId) != owner.getId()) {
            world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
            return refuse(owner, persona, entry, BUSY_LINE, "busy", new RosterResult.Busy());
        }
        // before the spawn: its registration (or the join below) joins the owner's party, and would make one without the exemption
        if (owner.getParty() == null) {
            Party.createParty(owner, true, true); // decision 9 (a): below level 10 a starter party of their own companions
        }
        // a walk-in is loaded standing still where it comes from; a map without a usable portal keeps the beside-owner spawn
        MapleMap map = owner.getMap();
        Point arrival = from != null ? from : ArrivalPlacement.nearestPortal(map, owner.getPosition());
        AgentLifecycleService.AgentSpawnResult result = arrival == null
                ? AgentInteractionRuntime.spawnAgentForLeader(owner, persona.name())
                : AgentInteractionRuntime.spawnStationaryAgentForLeaderAt(owner, persona.name(), map, arrival);
        if (!result.success() || result.agent() == null) {
            world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
            log.warn("call {} for {} failed: {}", persona.id(), owner.getName(), result.errorMessage());
            return refuse(owner, persona, entry, "i can't get away right now. try again in a bit.", result.errorMessage(),
                    couldNotJoin(persona));
        }
        JoinOutcome joined = AgentPartyLifecycleService.joinAgentToLeaderParty(owner, result.agent());
        if (joined != JoinOutcome.JOINED && joined != JoinOutcome.ALREADY_MEMBER) {
            despawn(entry, "party join " + joined);
            return refuse(owner, persona, entry, joinRefusal(joined), joined.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    couldNotJoin(persona));
        }
        if (arrival != null) {
            walkToOwner(result.agent()); // only once it is in the party
        }
        RosterEntry row = world.store().rosterEntry(entry.characterId(), entry.personaId());
        if (row == null || row.state() == RosterState.RELEASED) {
            despawn(entry, "released meanwhile"); // never write a released row back from the snapshot
            return new RosterResult.NotOwned();
        }
        // bench slices and the ledger rewrite the row behind the caller's snapshot, and applyAccrued zeroes the
        // banked EXP: both saves start from the row as it is now, so the EXP can never be granted twice
        world.bench().applyAccrued(current(entry), result.agent());
        RosterEntry active = current(entry).with(RosterState.ACTIVE, System.currentTimeMillis());
        world.store().saveRoster(active);
        ownerByPersona.put(persona.id(), owner.getId());
        world.store().event(owner.getId(), persona.id(), "roster.call", null);
        List<String> lines = world.arcs().lines(persona, "join");
        world.residents().reply(persona, owner, lines.isEmpty() ? "here." : lines.get(Math.floorMod(owner.getId(), lines.size())));
        return new RosterResult.Ok(persona.name() + " joins your party.");
    }

    /** A companion loaded at its arrival point stands still until it is seated; then it follows, so it walks to its owner. */
    private static void walkToOwner(Character bot) {
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        if (runtime != null) {
            AgentMovementCommandRuntime.followConfiguredTarget(runtime);
        }
    }

    /** Retries the spawn once the owner's next load is admitted, if the row is still as it was and nobody is loaded. */
    private void spawnLater(int ownerId, Persona persona, RosterState state, long delayMs) {
        later("roster.seat " + persona.id(), () -> seatQuietly(ownerId, persona, state), delayMs);
    }

    /** Runs {@code task} on companion-ops after {@code delayMs}: the timer only hands it over. */
    private void later(String name, Runnable task, long delayMs) {
        server.TimerManager.getInstance().schedule(server.TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor().submit(name, task), delayMs);
    }

    /**
     * Seats a queued or arriving companion. While another load holds the owner's slot it waits for the next one
     * without a word; only a refusal is printed, since the stock "has joined the party" notice announces a seat.
     */
    private void seatQuietly(int ownerId, Persona persona, RosterState state) {
        Character owner = onlineCharacter(ownerId);
        RosterEntry entry = owner == null ? null : world.store().rosterEntry(ownerId, persona.id());
        if (entry == null || entry.state() != state || companion(entry) != null || seating.contains(persona.id())) {
            return;
        }
        long waitMs = world.budget().loadDelayMs(owner, System.currentTimeMillis());
        if (waitMs > 0L) {
            spawnLater(ownerId, persona, state, waitMs);
            return;
        }
        // the party may have filled while this one waited; an AWAY or travelling companion kept its seat, and a
        // restored ACTIVE one with no seat left is benched with returnWhenRoom, so its row says where it is and it
        // comes back by itself once there is room
        boolean full = !canSeat(owner, entry);
        RosterResult outcome;
        if (full && state == RosterState.ACTIVE) {
            outcome = standDown(owner, persona, overflowLine(persona, ownerId), "companion.overflow");
            world.slots().waitForRoom(persona.id(), 0);
        } else {
            outcome = full ? refuse(owner, persona, entry, PARTY_FULL_LINE, "party full", couldNotJoin(persona))
                    : spawnCompanion(owner, persona, entry, null);
        }
        if (!seatedWith(owner, current(entry))) {
            owner.dropMessage(5, RosterVoice.line(persona, outcome));
        }
    }

    /** True when the companion is loaded and follows this owner, whichever call seated it. */
    private boolean seatedWith(Character owner, RosterEntry entry) {
        Character bot = companion(entry);
        return bot != null && AgentRuntimeRegistry.leaderIdForAgentCharacter(bot.getId()) == owner.getId();
    }

    /**
     * A call that could not seat the companion: nothing stays loaded for it, the row stays BENCHED (or AWAY)
     * so the ledger keeps crediting it, and the companion tells the owner why (the reason goes to the event log).
     * A row that another call seated meanwhile stays ACTIVE. {@code result} is what the caller is told.
     */
    private RosterResult refuse(Character owner, Persona persona, RosterEntry entry, String line, String reason,
                                RosterResult result) {
        RosterEntry current = current(entry);
        if (current.state() == RosterState.ACTIVE && !seatedWith(owner, current)) {
            world.store().saveRoster(current.with(RosterState.BENCHED, System.currentTimeMillis()));
        }
        world.residents().whisper(persona, owner, line);
        world.store().event(owner.getId(), persona.id(), "roster.call_refused", reason);
        return result;
    }

    /** A load that failed past the seat check (the companion's whisper and the event log say why). */
    private static RosterResult couldNotJoin(Persona persona) {
        return new RosterResult.Failed(persona.name() + " could not join right now.");
    }

    private static String joinRefusal(JoinOutcome joined) {
        return joined == JoinOutcome.PARTY_FULL ? PARTY_FULL_LINE : "i couldn't join your party. try again in a bit.";
    }

    /** The row as stored now (the snapshot when it is gone). */
    private RosterEntry current(RosterEntry entry) {
        RosterEntry current = world.store().rosterEntry(entry.characterId(), entry.personaId());
        return current == null ? entry : current;
    }

    /** Benches a companion that is out; one called and still on its way turns back and never arrives. */
    public RosterResult bench(Character owner, Persona persona) {
        world.slots().stopWaiting(persona.id()); // benched on purpose: it does not come back by itself
        if (travelling.remove(owner.getId() + ":" + persona.id()) != null) {
            world.slots().release(persona.id(), CompanionSlots.Kind.TRAVEL); // an AWAY one's own seat goes below
            RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
            if (entry != null && entry.state() == RosterState.BENCHED) {
                world.store().event(owner.getId(), persona.id(), "roster.bench", "turned back on the way");
                return new RosterResult.Ok(persona.name() + " turns back to camp.");
            }
        }
        return standDown(owner, persona, null, "roster.bench");
    }

    /** What a companion with no seat left whispers: its own words for it, else where its duty takes it. */
    public String overflowLine(Persona persona, int ownerId) {
        List<String> lines = world.arcs().lines(persona, "overflow");
        if (!lines.isEmpty()) {
            return lines.get(Math.floorMod(ownerId, lines.size()));
        }
        RosterEntry entry = world.store().rosterEntry(ownerId, persona.id());
        return "party's full. i'll " + (entry == null ? "train" : describeAssignment(entry)) + " at camp till there's room.";
    }

    /**
     * Benched because the party put it out (another leader's expel, fair share, make-room; CompanionPartySync) or
     * by its owner ({@link #bench}): it walks out and goes back to its duty. It whispers {@code line} to its owner
     * instead of its usual words (null keeps them); {@code eventKind} is logged.
     */
    public RosterResult standDown(Character owner, Persona persona, String line, String eventKind) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return new RosterResult.NotOwned();
        }
        if (entry.state() == RosterState.BENCHED) {
            return new RosterResult.Ok(persona.name() + " is already benched.");
        }
        if (line == null) {
            List<String> lines = world.arcs().lines(persona, "bench");
            world.residents().reply(persona, owner, lines.isEmpty() ? "alright. i'll keep busy." : lines.get(Math.floorMod(owner.getId(), lines.size())));
        } else {
            world.residents().whisper(persona, owner, line);
        }
        walkOut(owner, entry);
        world.slots().release(persona.id()); // an errand's seat goes back with it
        RosterEntry benched = entry.with(RosterState.BENCHED, System.currentTimeMillis());
        world.store().saveRoster(benched);
        world.store().event(owner.getId(), persona.id(), eventKind, benched.assignment().name());
        return new RosterResult.Ok(persona.name() + " heads back to camp to " + describeAssignment(benched) + ".");
    }

    /**
     * A benched companion on its owner's map leaves the party at once (the party window shows it), walks to the
     * nearest portal and goes when it gets there or after a few seconds, so it never vanishes on the spot (plan
     * "Rotate"). It keeps its COMPANION lease until then. Anywhere else, or with no usable portal, it goes at once.
     */
    private void walkOut(Character owner, RosterEntry entry) {
        Character bot = companion(entry);
        MapleMap map = bot == null ? null : bot.getMap();
        Point portal = map == null || map != owner.getMap() ? null : ArrivalPlacement.nearestPortal(map, bot.getPosition());
        AgentRuntimeEntry runtime = portal == null ? null : AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        if (runtime == null) {
            despawn(entry, "benched");
            return;
        }
        leaveParty(bot, entry);
        AgentMovementCommandRuntime.moveTo(runtime, portal, false);
        Object walk = new Object();
        leaving.put(entry.personaId(), walk);
        watchWalkOut(entry, bot, portal, walk, System.currentTimeMillis() + ArrivalPlacement.WALK_OUT_MAX_MS);
    }

    private void watchWalkOut(RosterEntry entry, Character bot, Point portal, Object walk, long deadlineMs) {
        later("roster.walk_out " + entry.personaId(), () -> {
            if (leaving.get(entry.personaId()) != walk) {
                return; // called back, released or despawned meanwhile
            }
            Point at = bot.getPosition();
            if (at != null && at.distance(portal) > ArrivalPlacement.WALK_OUT_REACH_PX && System.currentTimeMillis() < deadlineMs) {
                watchWalkOut(entry, bot, portal, walk, deadlineMs);
            } else if (leaving.remove(entry.personaId(), walk)) {
                despawn(entry, "benched, walked out");
            }
        }, ArrivalPlacement.WALK_OUT_CHECK_MS);
    }

    /** Bench-then-call (the atomic swap is P2b); Ok when the incoming companion is seated or on its way. */
    public RosterResult swap(Character owner, Persona out, Persona in) {
        RosterResult benched = bench(owner, out);
        RosterResult called = call(owner, in);
        String line = RosterVoice.line(out, benched) + " " + RosterVoice.line(in, called);
        return called.ok() ? new RosterResult.Ok(line) : new RosterResult.Failed(line);
    }

    public RosterResult setCamp(Character owner, Persona persona, int mapId) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return new RosterResult.NotOwned();
        }
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), mapId, entry.accruedExp(), entry.haulMeso(),
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(),
                entry.farmSecondsToday(), entry.farmDay());
        world.store().saveRoster(updated);
        return new RosterResult.Ok(persona.name() + " will camp at map " + mapId + ".");
    }

    public RosterResult assign(Character owner, Persona persona, Assignment assignment, int itemId, int count) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return new RosterResult.NotOwned();
        }
        if (assignment == Assignment.FARM_ITEM && world.quests().isOwnQuestTarget(owner, persona, itemId)) {
            return refuseOwnErrand(persona);
        }
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), assignment,
                itemId, count, entry.campMapId(), entry.accruedExp(), entry.haulMeso(), entry.wagesPaidUntilMs(),
                entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay());
        world.store().saveRoster(updated);
        world.store().event(owner.getId(), persona.id(), "roster.assign", describeAssignment(updated));
        return new RosterResult.Ok(persona.name() + " will " + describeAssignment(updated)
                + (entry.state() == RosterState.ACTIVE ? " once benched." : "."));
    }

    private RosterResult refuseOwnErrand(Persona persona) {
        List<String> lines = world.arcs().lines(persona, "refuseOwnErrand");
        return new RosterResult.Failed(lines.isEmpty() ? persona.name() + ": that one's your errand, not mine." : persona.name() + ": " + lines.get(0));
    }

    /**
     * Delegated remote work (plan 11.2): an active companion leaves for a work map and keeps its
     * party slot; from there the bench rules govern it (ledger, live sessions, haul, no own-quest
     * credit) until {@code come back} calls it. A benched companion is simply reassigned, and
     * {@code mapId} 0 means "where it already camps".
     */
    public RosterResult delegate(Character owner, Persona persona, Assignment assignment, int itemId, int count, int mapId) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return new RosterResult.NotOwned();
        }
        if (assignment == Assignment.FARM_ITEM && world.quests().isOwnQuestTarget(owner, persona, itemId)) {
            return refuseOwnErrand(persona);
        }
        int workMap = mapId != 0 ? mapId : entry.campMapId() != 0 ? entry.campMapId() : server.living.resident.ResidentSpots.townMapId(persona.homeTown());
        boolean fighting = assignment == Assignment.TRAIN || assignment == Assignment.FARM_MESO
                || assignment == Assignment.FARM_ITEM || assignment == Assignment.GATHER;
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), assignment,
                itemId, count, workMap, entry.accruedExp(), entry.haulMeso(), entry.wagesPaidUntilMs(),
                entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay());
        server.maps.MapleMap map = world.residents().map(workMap);
        if (fighting && (map == null || map.isTown())) {
            return new RosterResult.Failed(persona.name() + ": where? take me to a field and say 'camp here', or send me from one with 'go "
                    + describeAssignment(updated) + " here'.");
        }
        if (entry.state() != RosterState.ACTIVE) {
            world.store().saveRoster(updated);
            world.store().event(owner.getId(), persona.id(), "roster.assign", describeAssignment(updated) + " at map " + workMap);
            return new RosterResult.Ok(persona.name() + " will " + describeAssignment(updated) + " at map " + workMap + ".");
        }
        List<String> lines = world.arcs().lines(persona, "delegate");
        world.residents().reply(persona, owner, lines.isEmpty() ? "on it. call me when you want me back." : lines.get(Math.floorMod(owner.getId(), lines.size())));
        keepAwaySeat(owner.getId(), persona.id()); // before it leaves the party, so its seat is never up for grabs
        despawn(entry, "delegated");
        RosterEntry away = updated.with(RosterState.AWAY, System.currentTimeMillis());
        world.store().saveRoster(away);
        world.store().event(owner.getId(), persona.id(), "roster.delegate", describeAssignment(away) + " at map " + workMap);
        return new RosterResult.Ok(persona.name() + " leaves to " + describeAssignment(away) + (mapId != 0 ? " here" : " at map " + workMap)
                + ", keeping the party slot. Say '" + persona.name().toLowerCase(Locale.ROOT) + " come back' to recall.");
    }

    public String describeAssignment(RosterEntry entry) {
        return switch (entry.assignment()) {
            case TRAIN -> "train";
            case FARM_MESO -> "farm meso";
            case FARM_ITEM -> "farm " + itemName(entry.assignmentItem())
                    + (entry.assignmentCount() > 0 ? " x" + entry.assignmentCount() : "");
            case GATHER -> "gather for your quests";
            case SELL -> "sell the haul";
            case STALL -> "run a stall";
            case REST -> "rest";
        };
    }

    // ---- wages ---------------------------------------------------------------------------

    /** Prepays upkeep so bench work is not charged to the haul; returns the new paid-until description. */
    public String payWages(Character owner, Persona persona, long meso) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        if (meso <= 0 || owner.getMeso() < meso) {
            return "you don't have " + meso + " meso.";
        }
        int level = Math.max(1, world.store().characterLevel(world.residents().characterIdOf(persona.id())));
        long hoursMs = Math.round(meso / BenchLedger.upkeepPerHour(level) * 3_600_000.0d);
        long from = Math.max(System.currentTimeMillis(), entry.wagesPaidUntilMs());
        owner.gainMeso((int) -meso, true);
        RosterEntry paid = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), entry.accruedExp(), entry.haulMeso(),
                from + hoursMs, entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay());
        world.store().saveRoster(paid);
        world.store().event(owner.getId(), persona.id(), "roster.wages", meso + " meso -> " + (hoursMs / 3_600_000L) + " h");
        return persona.name() + " is paid up for " + RecallTravel.describe(paid.wagesPaidUntilMs() - System.currentTimeMillis()) + ".";
    }

    public String wagesStatus(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        long left = entry.wagesPaidUntilMs() - System.currentTimeMillis();
        int level = Math.max(1, world.store().characterLevel(world.residents().characterIdOf(persona.id())));
        return persona.name() + (left > 0 ? " is paid up for " + RecallTravel.describe(left) : " is working off the haul")
                + " (upkeep " + Math.round(BenchLedger.upkeepPerHour(level)) + " meso/hour).";
    }

    // ---- haul ----------------------------------------------------------------------------

    public List<String> haulSummary(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        List<String> lines = new ArrayList<>();
        if (entry == null) {
            lines.add(persona.name() + " is not in your roster.");
            return lines;
        }
        lines.add(persona.name() + ": " + entry.haulMeso() + " meso" + (entry.accruedExp() > 0 ? ", " + entry.accruedExp() + " exp banked" : ""));
        for (HaulItem item : world.store().haul(owner.getId(), persona.id())) {
            lines.add("  " + itemName(item.itemId()) + " x" + item.quantity());
        }
        return lines;
    }

    /** Moves the haul into the owner's inventory and purse; provenance is recorded per stack. */
    public String handOver(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        int moved = 0;
        int skipped = 0;
        for (HaulItem item : world.store().haul(owner.getId(), persona.id())) {
            if (InventoryManipulator.checkSpace(owner.getClient(), item.itemId(), item.quantity(), "")) {
                InventoryManipulator.addById(owner.getClient(), item.itemId(), (short) Math.min(item.quantity(), Short.MAX_VALUE));
                world.store().removeHaul(owner.getId(), persona.id(), item.itemId(), item.quantity());
                world.store().recordTransfer(owner.getId(), persona.id(), item.itemId(), item.quantity());
                moved++;
            } else {
                skipped++;
            }
        }
        long meso = entry.haulMeso();
        if (meso > 0) {
            owner.gainMeso((int) Math.min(meso, Integer.MAX_VALUE), true);
            saveHaulMeso(entry, 0L);
        }
        world.store().event(owner.getId(), persona.id(), "haul.handover", moved + " stacks, " + meso + " meso, skipped " + skipped);
        return persona.name() + " hands over " + moved + " item stack(s) and " + meso + " meso"
                + (skipped > 0 ? " (" + skipped + " stack(s) did not fit)" : "") + ".";
    }

    /** Mails the haul through Duey, one package per stack, the meso in the first. */
    public String sendIt(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        List<HaulItem> haul = world.store().haul(owner.getId(), persona.id());
        long meso = entry.haulMeso();
        if (haul.isEmpty() && meso <= 0) {
            return persona.name() + " has nothing to send.";
        }
        boolean first = true;
        for (HaulItem stack : haul) {
            Item item = ItemConstants.isEquipment(stack.itemId())
                    ? ItemInformationProvider.getInstance().getEquipById(stack.itemId())
                    : new Item(stack.itemId(), (short) 0, (short) Math.min(stack.quantity(), Short.MAX_VALUE));
            DueyProcessor.dueyCreatePackage(item, first ? (int) Math.min(meso, Integer.MAX_VALUE) : 0, persona.name(), owner.getId());
            world.store().removeHaul(owner.getId(), persona.id(), stack.itemId(), stack.quantity());
            world.store().recordTransfer(owner.getId(), persona.id(), stack.itemId(), stack.quantity());
            first = false;
        }
        if (first && meso > 0) {
            DueyProcessor.dueyCreatePackage(new Item(4000000, (short) 0, (short) 1), (int) Math.min(meso, Integer.MAX_VALUE),
                    persona.name(), owner.getId());
        }
        saveHaulMeso(entry, 0L);
        world.store().event(owner.getId(), persona.id(), "haul.duey", haul.size() + " packages, " + meso + " meso");
        return persona.name() + " sends " + haul.size() + " package(s) through Duey.";
    }

    public String bankIt(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        long meso = entry.haulMeso();
        if (meso <= 0) {
            return persona.name() + " has no meso to bank.";
        }
        server.Storage storage = owner.getStorage();
        storage.setMeso((int) Math.min((long) storage.getMeso() + meso, Integer.MAX_VALUE));
        owner.saveCharToDB();
        saveHaulMeso(entry, 0L);
        world.store().event(owner.getId(), persona.id(), "haul.bank", meso + " meso");
        return persona.name() + " banks " + meso + " meso into your storage.";
    }

    void saveHaulMeso(RosterEntry entry, long meso) {
        world.store().saveRoster(new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), entry.accruedExp(), meso,
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(),
                entry.farmSecondsToday(), entry.farmDay()));
    }

    // ---- escort (quest step) -------------------------------------------------------------

    /**
     * A quest ESCORT step: the persona follows the player as a temporary companion until the step ends. Its row counts
     * toward {@code ROSTER_MAX} and it takes a seat before anything is written; with no room it says so in character
     * and nothing changes (null when there is nobody to escort).
     */
    public RosterResult beginEscort(Character owner, Persona persona, Quest quest, Step step) {
        if (persona == null || isOwned(persona.id())) {
            return null;
        }
        if (roster(owner).size() >= world.config().ROSTER_MAX) {
            return refuseEscort(owner, persona, quest, "you've got a full crew already. i'll find my own way.",
                    new RosterResult.RosterFull(world.config().ROSTER_MAX));
        }
        // an AWAY row always holds its seat: taken first, so the escort never sits past the owner's share
        if (freeSlots(owner) <= 0 || !world.slots().reserve(owner.getId(), persona.id(), CompanionSlots.Kind.AWAY, Long.MAX_VALUE)) {
            return refuseEscort(owner, persona, quest, "your party's full. i'll find my own way.", new RosterResult.PartyFull());
        }
        long now = System.currentTimeMillis();
        RosterEntry entry = new RosterEntry(owner.getId(), persona.id(), RosterState.AWAY, Assignment.REST, 0, 0,
                owner.getMapId(), 0L, 0L, 0L, now, 0L, now, 0, null); // camp = here, so the call below is instant
        Point standing = standingNear(owner, persona.id()); // the Resident the player talked to walks over from there
        world.store().saveRoster(entry);
        ownerByPersona.put(persona.id(), owner.getId());
        owners.add(owner.getId());
        world.residents().releaseForRecruit(persona.id());
        RosterResult result = call(owner, persona, standing);
        owner.dropMessage(5, RosterVoice.line(persona, result) + " (escort for " + quest.title() + ")");
        return result;
    }

    private RosterResult refuseEscort(Character owner, Persona persona, Quest quest, String line, RosterResult result) {
        world.residents().whisper(persona, owner, line);
        world.store().event(owner.getId(), persona.id(), "roster.escort_refused", quest.id() + ": " + result);
        owner.dropMessage(5, RosterVoice.line(persona, result) + " (escort for " + quest.title() + ")");
        return result;
    }

    // ---- login / logout ------------------------------------------------------------------

    /**
     * Restores the owner's ACTIVE companions (decision 8a): each walks in from a portal once the field has loaded
     * on the client, a few seconds apart; the load spacing still applies when each one fires. A roster owner
     * without the Adventurer's Roster gets one.
     */
    public void onOwnerLogin(Character owner) {
        ownerLogoutAtMs.remove(String.valueOf(owner.getId()));
        List<RosterEntry> entries = roster(owner);
        if (!entries.isEmpty()) {
            grantRosterBook(owner);
        }
        long delayMs = ArrivalPlacement.LOGIN_ARRIVAL_MS;
        for (RosterEntry entry : entries) {
            if (entry.state() == RosterState.ACTIVE) {
                Persona persona = world.content().persona(entry.personaId());
                if (persona != null && companion(entry) == null) {
                    spawnLater(owner.getId(), persona, RosterState.ACTIVE, delayMs);
                    delayMs += ArrivalPlacement.staggerMs();
                }
            }
        }
    }

    public void onOwnerLogout(Character owner) {
        for (RosterEntry entry : roster(owner)) {
            if (entry.state() == RosterState.ACTIVE) {
                despawn(entry, "owner offline");
            }
        }
        ownerLogoutAtMs.put(String.valueOf(owner.getId()), System.currentTimeMillis());
    }

    // ---- auditor repairs (CompanionAuditor, on companion-ops; they never write the row) ---------

    /**
     * An ACTIVE row whose companion is not loaded, not in the owner's party, not led by the owner or on another
     * channel. One that follows the owner on their map and channel and is only out of the party rejoins where it
     * stands (no pop-out). Anything else: whatever holds it lets go, and it is seated again the way login restores
     * it, in the owner's party now.
     */
    public void reseat(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() != RosterState.ACTIVE || inFlight(owner.getId(), persona.id())) {
            return;
        }
        Character bot = companion(entry);
        if (bot != null && owner.getMap() != null && bot.getMap() == owner.getMap() && bot.getClient() != null
                && owner.getClient() != null && bot.getClient().getChannel() == owner.getClient().getChannel()
                && AgentRuntimeRegistry.leaderIdForAgentCharacter(bot.getId()) == owner.getId()
                && !sameParty(owner, bot) && canSeat(owner, entry)) {
            rejoin(owner, entry, bot);
            return;
        }
        despawn(entry, "audit: reseat");
        if (canSeat(owner, entry)) { // with no room the row waits: the sync recalls it once its owner has room
            spawnCompanion(owner, persona, entry, null);
        }
    }

    /** The companion standing with its owner joins the owner's party again on a seat held for the join. */
    private void rejoin(Character owner, RosterEntry entry, Character bot) {
        if (owner.getParty() == null && !Party.createParty(owner, true, true)) {
            return;
        }
        if (!world.slots().reserve(owner.getId(), entry.personaId(), CompanionSlots.Kind.TRAVEL,
                System.currentTimeMillis() + CompanionSlots.SEAT_HOLD_MS)) {
            return;
        }
        if (bot.getParty() != null) {
            leaveParty(bot, entry);
        }
        JoinOutcome joined = AgentPartyLifecycleService.joinAgentToLeaderParty(owner, bot);
        if (joined != JoinOutcome.JOINED && joined != JoinOutcome.ALREADY_MEMBER) {
            world.slots().release(entry.personaId(), CompanionSlots.Kind.TRAVEL);
        }
    }

    /**
     * A BENCHED or AWAY row whose companion is still in the owner's party or still led by the owner: it leaves the
     * party, and is unloaded unless something else (a bench slice) leads it. An offline member is expelled.
     */
    public void unseat(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || !BenchSessions.benchGoverned(entry) || inFlight(owner.getId(), persona.id())) {
            return;
        }
        Character bot = companion(entry);
        if (bot != null && AgentRuntimeRegistry.leaderIdForAgentCharacter(bot.getId()) == owner.getId()) {
            despawn(entry, "audit: unseat");
            return;
        }
        Party party = owner.getParty();
        if (bot != null) {
            if (party != null && sameParty(owner, bot)) {
                AgentPartyLifecycleService.leaveAgentParty(bot);
            }
            return;
        }
        net.server.world.PartyCharacter member = party == null ? null
                : party.getMemberById(world.residents().characterIdOf(persona.id()));
        if (member != null && !member.isOnline()) {
            owner.getWorldServer().updateParty(party.getId(), net.server.world.PartyOperation.EXPEL, member);
        }
    }

    private void despawn(RosterEntry entry, String reason) {
        leaving.remove(entry.personaId()); // a walk-out in progress ends here
        Character bot = companion(entry);
        int characterId = world.residents().characterIdOf(entry.personaId());
        if (bot != null) {
            leaveParty(bot, entry);
            bot.saveCharToDB();
        }
        if (characterId != 0) {
            AgentRuntimeCleanupService.removeAgentByCharacterId(characterId);
            world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
        }
        if (bot != null && bot.getClient() != null) {
            bot.getClient().forceDisconnect();
        }
        log.info("companion {} despawned: {}", entry.personaId(), reason);
    }

    /** The companion leaves its party under the roster's cause token (the sync takes the event as an echo). */
    private static void leaveParty(Character bot, RosterEntry entry) {
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            AgentPartyLifecycleService.leaveAgentParty(bot);
        } catch (RuntimeException failure) {
            log.debug("leave party failed for {}", entry.personaId(), failure);
        }
    }

    // ---- exp -----------------------------------------------------------------------------

    /** Apprentice bonus for an active companion at least two levels below a co-present owner. */
    public double apprenticeMultiplier(Character attacker) {
        if (!AgentPresence.isAgent(attacker)) {
            return 1.0d;
        }
        Persona persona = world.residents().personaOf(attacker);
        if (persona == null) {
            return 1.0d;
        }
        int ownerId = ownerOf(persona.id());
        if (ownerId == 0) {
            return 1.0d;
        }
        Character owner = attacker.getMap() == null ? null : attacker.getMap().getCharacterById(ownerId);
        if (owner == null || !sameParty(owner, attacker)) {
            return 1.0d;
        }
        int gap = owner.getLevel() - attacker.getLevel();
        if (gap < 2) {
            return 1.0d;
        }
        return 1.0d + world.config().APPRENTICE_BONUS_PER_LEVEL * Math.min(world.config().APPRENTICE_MAX_LEVELS, gap);
    }

    // ---- listing -------------------------------------------------------------------------

    public List<String> summary(Character owner) {
        List<String> lines = new ArrayList<>();
        List<RosterEntry> entries = roster(owner);
        if (entries.isEmpty()) {
            lines.add("Nobody travels with you yet. Earn a townsperson's trust and ask them to join.");
            return lines;
        }
        lines.add("Roster " + entries.size() + "/" + world.config().ROSTER_MAX + ", free party slots " + freeSlots(owner) + ":");
        for (RosterEntry entry : entries) {
            Persona persona = world.content().persona(entry.personaId());
            String name = persona == null ? entry.personaId() : persona.name();
            int level = world.store().characterLevel(world.residents().characterIdOf(entry.personaId()));
            lines.add("  " + name + " lv" + level + " " + entry.state().name().toLowerCase(Locale.ROOT)
                    + (entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY
                    ? " - " + describeAssignment(entry) + " at map " + entry.campMapId() : "")
                    + (entry.haulMeso() > 0 ? ", haul " + entry.haulMeso() + " meso" : ""));
        }
        return lines;
    }

    private static String itemName(int itemId) {
        String name = itemId == 0 ? null : ItemInformationProvider.getInstance().getName(itemId);
        return name == null ? "item " + itemId : name;
    }

    public LivingStore store() {
        return world.store();
    }
}
