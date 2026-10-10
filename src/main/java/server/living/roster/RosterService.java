package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.processor.npc.DueyProcessor;
import config.AgentYamlConfig;
import constants.inventory.ItemConstants;
import net.server.Server;
import net.server.world.CauseScope;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyEvents;
import net.server.world.PartyOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.ItemInformationProvider;
import server.agents.auth.AgentClaimGuard;
import server.agents.capabilities.follow.AgentFollowGuard;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.knowledge.Decisions;
import server.knowledge.EntityKind;
import server.knowledge.FarmSites;
import server.knowledge.WorldKnowledge;
import server.living.LivingWorld;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.LivingContent;
import server.living.population.Population;
import server.living.population.PopulationContent.Field;
import server.living.quest.BossGearPlanner;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapFactory;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
    /** A bench, swap or errand refused while the companion is committed to a run (Commitments). */
    static final String AFTER_THE_RUN_LINE = "after the run.";
    private static final String CANT_GET_AWAY_LINE = "i can't get away right now. try again in a bit.";
    /** The goodbye one companion says in party chat when its owner logs off (decision 8a), unless it has its own. */
    static final String GOODBYE_LINE = "gn!";
    /** The login whisper names this many companions and counts the rest. */
    static final int SUMMARY_NAMES = 3;
    /** Event log kind: the owner was told who went home while they were away (the rows before it are told). */
    static final String AWAY_TOLD_EVENT = "roster.away_told";
    /** The persona id prefix of a hired citizen's roster row ({@code hire:<characterId>}, P4). */
    public static final String HIRE_PREFIX = LivingContent.HIRE_PREFIX;

    /** Where an owner last came into a map that is not an event instance's copy: the way into their next run. */
    private record Spot(int mapId, Point at) { }

    private final LivingWorld world;
    /** persona id -> owner character id, cached from the store. */
    private final Map<String, Integer> ownerByPersona = new ConcurrentHashMap<>();
    private final Map<String, Long> ownerLogoutAtMs = new ConcurrentHashMap<>();
    /** owner id -> the open map they last came into, and where (a companion arriving while they are in a run waits there). */
    private final Map<Integer, Spot> lastOpenMap = new ConcurrentHashMap<>();
    /** owner id -> when a companion last whispered them the leader cap. */
    private final Map<Integer, Long> leaderCapSaidAtMs = new ConcurrentHashMap<>();
    /** Character ids with at least one roster entry; consulted on every chat line, so cached. */
    private final java.util.Set<Integer> owners = ConcurrentHashMap.newKeySet();
    /** Persona ids being seated right now: one load per companion, whichever thread calls it. */
    private final java.util.Set<String> seating = ConcurrentHashMap.newKeySet();
    /** Persona id -> the walk-out of a companion benched a moment ago; any despawn or a new seat ends it. */
    private final Map<String, Object> leaving = new ConcurrentHashMap<>();
    /** Persona id -> what it says on its next arrival instead of its join line (back from its instructor: JobErrands). */
    private final Map<String, String> arrivalLines = new ConcurrentHashMap<>();
    /**
     * Persona ids whose rows are TRAVELLING, HOLD or AWAY: kept off follow (CompanionFollowGuard, any thread) until
     * the roster's own arrival, rejoin or call walks them over. Kept in step by {@link #write}; {@link #walkToOwner}
     * (and a call's spawn beside the owner) let one follow again.
     */
    private final java.util.Set<String> offFollow = ConcurrentHashMap.newKeySet();
    /** Where each owner came into the map they are on: their companions follow them in there. */
    private final PortalFollow portalFollow = new PortalFollow();
    /**
     * Persona id -> where its HOLD waits (the map, and the spot when it was given one), from its {@link #hold}: a held
     * companion whose potion trip ends walks back there (CompanionRestock.afterRestock). Dropped by any other write.
     */
    private final Map<String, HoldSpot> holdSpots = new ConcurrentHashMap<>();
    /** Owner id -> the own party their companions stayed in as offline members when they logged off (decision 8a). */
    private final Map<Integer, Integer> parkedParty = new ConcurrentHashMap<>();
    /** Owner id -> each row's banked EXP and haul meso when they logged off: the login whisper sums up the difference. */
    private final Map<Integer, Map<String, long[]>> benchAtLogout = new ConcurrentHashMap<>();
    /** Owners one of whose companions said goodbye as they logged off (decision 8a); cleared at their login. */
    private final Set<Integer> goodbyeSaid = ConcurrentHashMap.newKeySet();
    /** Deleted owners with a companion still finishing a run for a friend: it is released, not parked, after it. */
    private final Set<Integer> releasedAfterRun = ConcurrentHashMap.newKeySet();
    /**
     * Persona ids let go whose unload (at once, or at the end of their walk-out) takes their boss gear off and restores
     * any GM level hold on them: the mark of a real release.
     */
    private final Set<String> bossGearOff = ConcurrentHashMap.newKeySet();
    /** The game data a released companion's level restore reads (ReleasedLevelRestore.LIVE; a test installs its own). */
    volatile ReleasedLevelRestore.Facts levelRestoreFacts = ReleasedLevelRestore.LIVE;
    private final CompanionChannelFollow channelFollow;
    private final InstanceLogoutHandoff handoff;
    /** Each companion's own settings (P3b), applied on every path below that loads or seats it. */
    private final CompanionSettingsApplier settings;
    /** What a released hire does besides leaving the roster; nothing until HiredHands installs it (P4). */
    private volatile HiredHandRelease hiredHandRelease = (ownerId, personaId, reason) -> { };

    public RosterService(LivingWorld world) {
        this.world = world;
        this.channelFollow = new CompanionChannelFollow(world, this);
        this.handoff = new InstanceLogoutHandoff(world, this);
        this.settings = new CompanionSettingsApplier(world, this);
        Commitments commitments = world.commitments();
        if (commitments != null) {
            commitments.addListener(handoff::commitmentEnded); // a companion that finished a run for a friend parks
        }
        for (RosterEntry entry : world.store().allActive()) {
            ownerByPersona.put(entry.personaId(), entry.characterId());
            owners.add(entry.characterId());
        }
        for (RosterEntry entry : world.store().allBenched()) {
            ownerByPersona.put(entry.personaId(), entry.characterId());
            owners.add(entry.characterId());
            if (entry.state() == RosterState.AWAY) {
                keepAwaySeat(entry.characterId(), entry.personaId()); // reservations live in memory: rebuilt at boot
                offFollow.add(entry.personaId());
            }
        }
        for (RosterEntry entry : world.store().allInTransit()) {
            ownerByPersona.put(entry.personaId(), entry.characterId());
            owners.add(entry.characterId());
            if (offFollowState(entry.state())) {
                offFollow.add(entry.personaId());
            }
            if (entry.state() == RosterState.HOLD) {
                world.slots().reserve(entry.characterId(), entry.personaId(), CompanionSlots.Kind.HOLD, Long.MAX_VALUE);
            }
        }
    }

    /** The companions' own settings (P3b): the Agent seams' answers, and the Fighting style and Supplies screens'. */
    public CompanionSettingsApplier settings() {
        return settings;
    }

    /** True when the player has recruited anyone (grants companion-command authority). */
    public boolean hasCompanions(Character chr) {
        return chr != null && owners.contains(chr.getId());
    }

    /** {@link #hasCompanions} by character id, online or not, from memory. */
    public boolean isOwner(int characterId) {
        return owners.contains(characterId);
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

    /**
     * True when the companion already holds a seat (away, on its way, or listed in the owner's party, even as an offline
     * member) or the owner has one free for it.
     */
    public boolean canSeat(Character owner, RosterEntry entry) {
        CompanionSlots.Reservation held = world.slots().reservation(entry.personaId());
        return held != null && held.ownerId() == owner.getId() || inOwnersParty(owner, entry) || freeSlots(owner) > 0;
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

    /** Milliseconds until a TRAVELLING companion reaches the owner (its eta_at); 0 when it is not on the way. */
    public long travelMsLeft(int ownerId, String personaId) {
        RosterEntry entry = world.store().rosterEntry(ownerId, personaId);
        return entry == null || entry.state() != RosterState.TRAVELLING ? 0L
                : Math.max(0L, entry.etaAtMs() - System.currentTimeMillis());
    }

    /**
     * True while a verb is under way for the companion: it is being seated, walking out, on its way over, changing
     * channel with its owner, or finishing a run for a friend of its owner's (decision 8b).
     */
    public boolean inFlight(int ownerId, String personaId) {
        return seating.contains(personaId) || leaving.containsKey(personaId) || travelMsLeft(ownerId, personaId) > 0L
                || channelFollow.pending(personaId) || handoff.finishing(world.residents().characterIdOf(personaId));
    }

    /** True while the character finishes a run for a friend of its owner's, its owner gone (decision 8b). */
    public boolean finishingForFriend(int characterId) {
        return handoff.finishing(characterId);
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
        int rostered = roster(owner).size();
        if (rostered >= world.config().ROSTER_MAX) {
            return new RosterResult.RosterFull(world.config().ROSTER_MAX);
        }
        ResidentAllocation allocation = world.residentAllocation();
        if (allocation != null && !persona.id().startsWith(HIRE_PREFIX) && allocation.atCap(owner)) {
            return new RosterResult.Failed(ResidentAllocation.CAP_LINE); // decision 18 b, in the Resident's words
        }
        Point standing = standingNear(owner, persona.id()); // before enroll hands the Resident's character over
        RosterEntry entry = enroll(owner, persona, "roster.recruit");
        RosterResult result = freeSlots(owner) <= 0
                ? new RosterResult.Ok(persona.name() + " joins your roster but your party is full; call them when there is room.")
                : spawnCompanion(owner, persona, entry, standing);
        if (rostered == 0) {
            handOverRosterBook(owner, persona);
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
        int rostered = roster(owner).size();
        if (rostered >= world.config().ROSTER_MAX) {
            return owner.getName() + "'s roster is full (" + world.config().ROSTER_MAX + ").";
        }
        RosterEntry entry = enroll(owner, persona, "roster.grant");
        if (rostered == 0) {
            handOverRosterBook(owner, persona);
        }
        return persona.name() + " joins " + owner.getName() + "'s roster, benched at map " + entry.campMapId() + ".";
    }

    /**
     * A hired citizen joins the owner's roster (P4, HiredHands.hire), on companion-ops. Like {@link #recruit} it counts
     * toward ROSTER_MAX and hands over the Adventurer's Roster with the owner's first companion; its persona state names
     * its own character, and it camps at {@code campMapId}, where it is. {@code handOver} ends its population life and
     * says whether it had a loaded session to end. {@code inPlace}: its loaded character, taken where it stands
     * ({@link #adopt}): {@code seated}, it is in the owner's party (their guest) and becomes their companion there; else
     * it walks out to the nearest portal to wait at camp (no seat for it). With no {@code inPlace} it is called like a
     * benched companion (join-then-travel from its camp), or waits there while the party is full or it cannot come yet.
     * One leased or led by anyone else is refused before anything changes (Busy).
     */
    public RosterResult recruitHire(Character owner, Persona persona, int campMapId, Character inPlace, boolean seated,
                                    BooleanSupplier handOver) {
        if (isOwned(persona.id())) {
            int current = ownerOf(persona.id());
            return current == owner.getId() ? new RosterResult.Ok(persona.name() + " is already yours.")
                    : new RosterResult.Failed(persona.name() + " already travels with someone else.");
        }
        int rostered = roster(owner).size();
        if (rostered >= world.config().ROSTER_MAX) {
            return new RosterResult.RosterFull(world.config().ROSTER_MAX);
        }
        int characterId = world.residents().ensureCharacter(persona); // a hire's id names its character: none is made
        if (characterId == 0) {
            return new RosterResult.Failed(persona.name() + " cannot be reached right now.");
        }
        if (inPlace != null && !claimInPlace(owner, characterId)) {
            return new RosterResult.Busy();
        }
        world.store().savePersonaState(world.store().personaState(persona.id()).withCharacter(characterId));
        RosterEntry entry = enroll(owner, persona, "roster.recruit", campMapId);
        RosterResult result;
        if (inPlace != null) {
            result = adopt(owner, persona, entry, inPlace, seated, handOver);
        } else {
            handOver.getAsBoolean();
            result = callHire(owner, persona);
        }
        if (rostered == 0 && world.store().rosterEntry(owner.getId(), persona.id()) != null) {
            handOverRosterBook(owner, persona);
        }
        return result;
    }

    /**
     * A loaded hired citizen's COMPANION lease, taken for its owner before anything changes: refused while it is leased
     * for anything else, or led by anyone but itself (the population's own registration) or its owner.
     */
    private boolean claimInPlace(Character owner, int characterId) {
        int leader = AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId);
        return (leader == characterId || leader == owner.getId())
                && world.leases().tryAcquire(characterId, CompanionLeases.Kind.COMPANION, owner.getId());
    }

    /**
     * Adoption in place (P4): the citizen standing in the owner's party as their guest becomes their companion where it
     * stands, with no blink and no party churn. Under the claim guard (the owner may load it: their row, their lease),
     * its population life ends without an unload ({@code handOver}), its runtime moves to the owner by the leader-transfer
     * path (AgentTransferService: stop it, register it under the new leader, which replaces the population's entry and
     * its tick), its GUEST seat becomes its companion seat without it leaving the party (no stock "left the party"), and
     * it follows. Not {@code seated} (no seat for it on the owner's map), it walks out to the nearest portal instead and
     * waits at camp. Refused by the guard, nothing has changed: its lease goes back and the row is dropped. Its session
     * ended before the hand-over (logged off, or loaded afresh by anything else): the stale character is never
     * registered; its lease and guest seat go back and it is called like any benched hire.
     */
    private RosterResult adopt(Character owner, Persona persona, RosterEntry entry, Character bot, boolean seated,
                               BooleanSupplier handOver) {
        int characterId = bot.getId();
        if (!AgentClaimGuard.mayLoad(owner, characterId)) {
            world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
            dropRow(entry);
            return new RosterResult.Busy();
        }
        boolean handedOver = handOver.getAsBoolean();
        AgentRuntimeEntry before = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        if (!handedOver || before == null || AgentRuntimeIdentityRuntime.bot(before) != bot) {
            world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
            world.slots().release(CompanionSlots.characterKey(characterId), CompanionSlots.Kind.GUEST);
            log.info("hire {} of {}: its session ended before the hand-over; called instead", persona.id(), owner.getName());
            return callHire(owner, persona);
        }
        AgentMovementCommandRuntime.stop(before);
        AgentInteractionRuntime.registerAgent(owner.getId(), owner, bot);
        if (!seated) {
            walkOut(owner, current(entry)); // benched where it stood: no pop-out, it leaves by the nearest portal
            world.store().event(owner.getId(), persona.id(), "roster.bench", "walked out to camp where it stood");
            return new RosterResult.Ok(persona.name() + " joins your roster but your party is full; call them when there is room.");
        }
        world.slots().release(CompanionSlots.characterKey(characterId), CompanionSlots.Kind.GUEST);
        write(current(entry).with(RosterState.ACTIVE, System.currentTimeMillis()));
        walkToOwner(persona, bot);
        settings.apply(owner, persona, bot);
        world.store().event(owner.getId(), persona.id(), "roster.call", "adopted where it stands");
        rememberFirstLineup(owner);
        return new RosterResult.Ok(persona.name() + " joins your crew.");
    }

    /**
     * A hire not taken where it stands: it waits at camp while the party is full, else it is called. A call that cannot
     * come now (busy, the budget) still leaves it hired, waiting at camp.
     */
    private RosterResult callHire(Character owner, Persona persona) {
        if (freeSlots(owner) <= 0) {
            return new RosterResult.Ok(persona.name() + " joins your roster but your party is full; call them when there is room.");
        }
        RosterResult called = call(owner, persona);
        return called.ok() ? called
                : new RosterResult.Ok(persona.name() + " joins your crew and waits at camp; call them when there's room.");
    }

    /**
     * The owner's first companion, recruited or granted, hands over the Adventurer's Roster and says once how to use
     * it (plan flow 9 step 3). Later ones hand over nothing: a login gives a roster owner who has lost the book another.
     */
    private void handOverRosterBook(Character owner, Persona persona) {
        if (grantRosterBook(owner)) {
            world.residents().whisper(persona, owner, ROSTER_BOOK_LINE);
        }
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

    /**
     * Writes a BENCHED roster row and hands the persona over from its Resident life. It trains at a field fitting its
     * level near its home town (the FieldCatalog's top pick), or camps in the town when none fits.
     */
    private RosterEntry enroll(Character owner, Persona persona, String eventType) {
        return enroll(owner, persona, eventType, 0);
    }

    /** {@code campMapId}: where it camps instead (a hired citizen: where it is), or 0 for the field near its home town. */
    private RosterEntry enroll(Character owner, Persona persona, String eventType, int campMapId) {
        long now = System.currentTimeMillis();
        int campMap = campMapId;
        if (campMap == 0) {
            int town = server.living.resident.ResidentSpots.townMapId(persona.homeTown());
            int field = fieldCampFor(persona, town);
            campMap = field != 0 ? field : town;
        }
        RosterEntry entry = new RosterEntry(owner.getId(), persona.id(), RosterState.BENCHED, Assignment.TRAIN, 0, 0,
                campMap, 0L, 0L, 0L, now, now, now, 0, null);
        write(entry);
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
        return release(owner.getId(), persona, reason);
    }

    /** {@link #release(Character, Persona, String)} by owner id: the owner may be offline (a walk-out after a run) or gone. */
    public String release(int ownerId, Persona persona, String reason) {
        RosterEntry entry = world.store().rosterEntry(ownerId, persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return persona.name() + " is not in your roster.";
        }
        letGo(entry, reason);
        world.contracts().onReleased(ownerId, persona, reason);
        world.store().event(ownerId, persona.id(), "roster.release", reason);
        return persona.name() + " heads home to " + townName(persona.homeTown()) + ".";
    }

    /**
     * The FieldCatalog's top training field for the persona's level (its character's, else its authored band's
     * lowest), those in {@code nearMapId}'s region first: the camp picker's first choice. 0 when there is none.
     */
    public int fieldCampFor(Persona persona, int nearMapId) {
        Population population = Population.get();
        int[] read = world.store().characterLevelAndJob(world.residents().characterIdOf(persona.id()));
        int[] levelAndJob = read != null && read.length >= 2 ? read : new int[] {0, -1};
        int level = levelAndJob[0];
        if (level <= 0 && persona.level() != null) {
            level = persona.level().min();
        }
        if (population == null || level <= 0) {
            return 0;
        }
        // the best fitting field its kind has not been measured dying at: found live 2026-09-30, the first fit for a
        // Lv50 thief (Drake's Meal Table) measured 43 deaths an hour and killed Jinx 15 slices in 16. None safe: 0,
        // the ledger keeps it rather than send it to die.
        BenchYieldModel yields = world.benchYields();
        for (Field field : population.fields().fieldsFor(level, nearMapId, CAMP_CANDIDATES)) {
            if (yields == null || yields.deathsPerHour(BenchYieldModel.Key.of(levelAndJob[1], level, field.mapId()), 0.0d)
                    < DEADLY_CAMP_DEATHS_PER_HOUR) {
                return field.mapId();
            }
        }
        return 0;
    }

    /** Where FARM_ITEM farms (docs/SERVER_KNOWLEDGE_PLAN.md 8.3): a map plus what it says about it, or a refusal in its words. */
    private record FarmPick(int mapId, String said, RosterResult.Failed refusal) { }

    /**
     * FARM_ITEM's field for {@code itemId}: {@code preferred} when a monster there drops it for a companion of its level;
     * otherwise (or never, when {@code here}) the nearest such field from {@code anchor} (an open field by the knowledge
     * file, so no map is loaded for the choice) that roughCampFor does not rule out. An item any monster drops keeps
     * {@code preferred}. Null when knowledge is off (today's rules).
     */
    private FarmPick farmPick(Persona persona, int itemId, int preferred, int anchor, boolean here) {
        WorldKnowledge k = Decisions.active();
        if (k == null) {
            return null;
        }
        int characterId = world.residents().characterIdOf(persona.id());
        FarmSites.Plan plan = FarmSites.plan(k, itemId, farmLevel(persona, characterId), anchor, false);
        if (plan.verdict() == FarmSites.Verdict.ANY_MONSTER) {
            return new FarmPick(preferred, null, null);
        }
        if (plan.verdict() == FarmSites.Verdict.FARM) {
            for (FarmSites.Site site : plan.sites()) {
                if (site.mapId() == preferred) {
                    return new FarmPick(preferred, null, null);
                }
            }
            for (FarmSites.Site site : plan.sites().subList(0, here ? 0 : Math.min(FARM_SITES_TRIED, plan.sites().size()))) {
                if (!roughCampFor(characterId, site.mapId())) {
                    return new FarmPick(site.mapId(), site.mobs() + " drop those at " + site.place(), null);
                }
            }
        }
        return new FarmPick(preferred, null, new RosterResult.Failed(persona.name() + ": " + refusal(plan, here)));
    }

    /** The character's level, else the persona band's min (as fieldCampFor). */
    private int farmLevel(Persona persona, int characterId) {
        int[] read = world.store().characterLevelAndJob(characterId);
        int level = read != null && read.length >= 1 ? read[0] : 0;
        return level <= 0 && persona.level() != null ? persona.level().min() : level;
    }

    /** Why it cannot farm what {@code plan} is about, in its words (docs/SERVER_KNOWLEDGE_PLAN.md 8.3). */
    private static String refusal(FarmSites.Plan plan, boolean here) {
        String mobs = plan.mobs() == null ? "some monsters" : plan.mobs();
        return switch (plan.verdict()) {
            case ANY_MONSTER, FARM -> here && !plan.sites().isEmpty()
                    ? "nothing here drops those. " + plan.sites().get(0).mobs() + " at " + plan.sites().get(0).place() + " do."
                    : "those drop from " + mobs + ", but i can't get to them.";
            case CASH -> "that's cash shop stuff, nothing drops it.";
            case SHOP_ONLY -> "nothing drops those. " + plan.npc() + " sells them.";
            case NO_SOURCE -> "nothing drops those that i know of.";
            case SCRIPT_ONLY -> plan.mobs() == null || plan.npc() == null ? "only some event monsters drop those. can't farm that."
                    : "only the " + mobs + " drop those, " + plan.npc() + " calls em up. can't farm that.";
            case QUEST_ONLY -> "those only drop from " + mobs + " while you're on the quest.";
            case PQ_ONLY -> "those only drop inside " + plan.npc() + ". run it with me?";
            case TOO_STRONG -> "those only drop from " + mobs + ". way too strong for me.";
            case BOSS_ONLY -> "those only drop from " + mobs + ", and that's a boss hunt, not a farm.";
        };
    }

    /**
     * GATHER (8.3): the first item its owner's quests still need that nothing it could farm drops, as " {name}: {item}?
     * {why}" for the Ok line; "" when it can get them all or knowledge is off. The ledger credits nothing for those.
     */
    private String gatherWarning(Persona persona, RosterEntry entry, int camp) {
        WorldKnowledge k = Decisions.active();
        if (k == null || world.bench() == null) {
            return "";
        }
        int level = farmLevel(persona, world.residents().characterIdOf(persona.id()));
        for (int itemId : world.bench().gatherNeeds(entry, persona)) {
            if (FarmSites.creditSource(k, itemId, level, camp, true) == null) {
                return " " + persona.name() + ": " + k.names().say(EntityKind.ITEM, itemId) + "? "
                        + refusal(FarmSites.plan(k, itemId, level, camp, true), false);
            }
        }
        return "";
    }

    /** FARM_ITEM tries at most this many of the nearest fields (each one a roughCampFor check). */
    static final int FARM_SITES_TRIED = 3;
    /** How many of the best fitting fields a default camp is chosen among. */
    static final int CAMP_CANDIDATES = 5;
    /** A camp measured at this many deaths an hour or more (shrunk toward none) is passed over for a default camp. */
    static final double DEADLY_CAMP_DEATHS_PER_HOUR = 2.0d;
    /** A companion's camp was set (by its owner, or by itself leaving one too rough): its record there starts again. */
    public static final String CAMP_EVENT = "roster.camp";
    static final String CAMP_TOO_ROUGH = "too rough";
    static final String CAMP_MOVES_TOLD_EVENT = "bench.camp-moved.told";

    /**
     * Deaths an hour its kind (job branch, level band) was measured at {@code campMapId}, shrunk toward none (the
     * pickers' "rough for me"); 0 when nothing ran there or the yields are unknown.
     */
    public double campDeathsPerHour(int characterId, int campMapId) {
        BenchYieldModel yields = world.benchYields();
        int[] read = characterId == 0 ? null : world.store().characterLevelAndJob(characterId);
        if (yields == null || read == null || read.length < 2 || read[0] <= 0) {
            return 0.0d;
        }
        return yields.deathsPerHour(BenchYieldModel.Key.of(read[1], read[0], campMapId), 0.0d);
    }

    /** True when its kind was measured dying at the camp at DEADLY_CAMP_DEATHS_PER_HOUR or more. */
    public boolean roughCampFor(int characterId, int campMapId) {
        return campDeathsPerHour(characterId, campMapId) >= DEADLY_CAMP_DEATHS_PER_HOUR;
    }

    /** "Lith Harbor": the town's name as the game shows it, else its id in words. */
    private static String townName(String town) {
        int mapId = server.living.resident.ResidentSpots.townMapId(town);
        String name = mapId <= 0 ? null : MapFactory.loadPlaceName(mapId);
        if (name != null && !name.isEmpty()) {
            return name;
        }
        StringBuilder words = new StringBuilder();
        for (String word : town.split("-")) {
            if (!word.isEmpty()) {
                words.append(words.isEmpty() ? "" : " ").append(word.substring(0, 1).toUpperCase(Locale.ROOT))
                        .append(word.substring(1));
            }
        }
        return words.toString();
    }

    /**
     * The roster's part of a release: a live bench slice ends, a loaded companion leaves the party and walks out to the
     * nearest portal when it stands on its online owner's map (else it is unloaded), one parked with its owner leaves
     * their party, its seat and any wait for room go back, and the row is written RELEASED and deleted; nobody employs
     * the persona any more. A hired citizen then goes back to the population ({@link #installHiredHandRelease}),
     * whatever let it go: its owner, a contract end or its owner's deletion. Its unload takes any boss gear off
     * ({@link #takeBossGearOff}), so the kit never goes home or on to its next owner, and then gives it back its own
     * level ({@link #restoreOwnLevel}), so its next owner never gets a GM !setlevel level.
     */
    private void letGo(RosterEntry entry, String reason) {
        int ownerId = entry.characterId();
        String personaId = entry.personaId();
        world.benchDuty().endFor(personaId, "released");
        leaveOfflineParty(entry); // parked with its owner: it leaves their party too
        Character owner = onlineCharacter(ownerId);
        Character bot = companion(entry);
        bossGearOff.add(personaId); // its unload below takes any !bossgear kit off (takeBossGearOff)
        if (owner != null && bot != null && bot.getMap() != null && bot.getMap() == owner.getMap()) {
            walkOut(owner, entry); // no pop-out: it keeps its lease until it is gone at the portal
        } else {
            despawn(entry, "released");
        }
        world.slots().release(personaId);
        world.slots().stopWaiting(personaId);
        dropRow(entry);
        if (personaId.startsWith(HIRE_PREFIX)) {
            hiredHandRelease.released(ownerId, personaId, reason);
        }
    }

    /** The row is written RELEASED and deleted with its settings; nobody employs the persona any more. */
    private void dropRow(RosterEntry entry) {
        int ownerId = entry.characterId();
        String personaId = entry.personaId();
        write(entry.with(RosterState.RELEASED, System.currentTimeMillis()));
        world.store().deleteRoster(ownerId, personaId);
        settings.forget(ownerId, personaId); // its settings went with the row: a later recruit starts from the defaults
        ownerByPersona.remove(personaId);
        if (world.store().roster(ownerId).isEmpty()) {
            owners.remove(ownerId);
        }
        world.store().savePersonaState(world.store().personaState(personaId).withEmployer(0));
        world.store().clearWorldFlag("persona." + personaId + ".employed");
    }

    /**
     * Roster deletion cleanup (plan "Roster deletion cleanup", decision 18): the owner's character was deleted, so
     * every row of theirs is released through the release logic, on companion-ops. A companion's commitment to a run is
     * let go first; a live bench slice ends and a loaded companion is unloaded; its seat, its lease and the seats of
     * guests charged to the owner go back. A Resident goes home to its routine (resident.released), recruitable again
     * once its contract's cooldown allows; a hired citizen ({@code hire:<characterId>}) goes back through
     * {@link #installHiredHandRelease}, which HiredHands fills in P4. A companion finishing a run for a friend of the
     * owner's (decision 8b) is never pulled out of it: it is released once the run ends for it ({@link #parkAfterRun}).
     */
    public void ownerDeleted(int ownerId) {
        Commitments commitments = world.commitments();
        for (RosterEntry entry : world.store().roster(ownerId)) {
            int characterId = world.residents().characterIdOf(entry.personaId());
            if (handoff.finishing(characterId)) {
                releasedAfterRun.add(ownerId);
                continue;
            }
            if (commitments != null && characterId != 0) {
                commitments.releaseMember(characterId);
            }
            Persona persona = world.content().persona(entry.personaId());
            if (persona != null) {
                release(ownerId, persona, "owner deleted");
                if (!entry.personaId().startsWith(HIRE_PREFIX)) {
                    world.store().event(ownerId, persona.id(), "resident.released", "owner deleted");
                }
                continue;
            }
            releaseRow(entry, "owner deleted"); // a hire whose character is gone, or a persona the content no longer has
        }
        for (CompanionSlots.Reservation held : world.slots().reservations(ownerId)) {
            world.slots().release(held.key()); // guests charged to them
        }
        owners.remove(ownerId);
        lastOpenMap.remove(ownerId);
        leaderCapSaidAtMs.remove(ownerId);
        ownerLogoutAtMs.remove(String.valueOf(ownerId));
        parkedParty.remove(ownerId);
        benchAtLogout.remove(ownerId);
        goodbyeSaid.remove(ownerId);
        channelFollow.forget(ownerId);
        portalFollow.forget(ownerId);
    }

    /**
     * The release of a row whose persona resolves to nothing (a hire whose character is gone, or a persona the content no
     * longer has): the roster's part of it, and roster.release. Owner deletion and the inactivity sweep use it.
     */
    void releaseRow(RosterEntry entry, String reason) {
        letGo(entry, reason);
        world.store().event(entry.characterId(), entry.personaId(), "roster.release", reason);
    }

    /** P4 seam: what a released hired citizen does besides leaving the roster (clear employed_by and its hired flag). */
    @FunctionalInterface
    public interface HiredHandRelease {
        void released(int ownerId, String personaId, String reason);
    }

    /** HiredHands (P4) installs its release here; null restores the default, which leaves the citizen as it is. */
    public void installHiredHandRelease(HiredHandRelease release) {
        hiredHandRelease = release != null ? release : (ownerId, personaId, reason) -> { };
    }

    // ---- call / bench / swap -------------------------------------------------------------

    /**
     * Calls a companion: benched ones join the party at once and travel from their camp (plan "Join-then-travel
     * arrival") unless a whistle is used. Once it is seated or on its way it no longer waits for room. One waiting
     * outside a run (HOLD) rejoins, unless the run is still on ("after the run"); one whose trip its owner's logout cut
     * short sets off again for the time it had left (or walks in when that is up). Nobody is called while the owner is
     * inside a companion party-quest run's instance: that is "after the run" too, as is a call for a bench ally out
     * with a boss run (its row stays BENCHED for the whole trip).
     */
    public RosterResult call(Character owner, Persona persona) {
        RosterResult result = call(owner, persona, null);
        if (result.ok()) {
            world.slots().stopWaiting(persona.id());
            rememberFirstLineup(owner);
        }
        return result;
    }

    /**
     * The usual lineup saves itself the first time 2 or more companions are out (plan "Usual lineup"): with none
     * remembered yet, those out now, as {@link #saveUsualLineup} orders them. Later ones are the logout's and
     * "Remember this lineup"'s.
     */
    private void rememberFirstLineup(Character owner) {
        int out = 0;
        for (RosterEntry entry : roster(owner)) {
            out += CompanionSeatState.partyMember(entry.state()) ? 1 : 0;
        }
        if (out >= 2 && world.store().lineupSlots(owner.getId()).isEmpty()) {
            saveUsualLineup(owner);
        }
    }

    /** {@code standing}: where it stood on the owner's map before its Resident life let it go (an escort), else null. */
    private RosterResult call(Character owner, Persona persona, Point standing) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return new RosterResult.NotOwned();
        }
        boolean loaded = companion(entry) != null;
        if (entry.state() == RosterState.ACTIVE && loaded) {
            return new RosterResult.Ok(persona.name() + " is already with you.");
        }
        if (entry.state() == RosterState.TRAVELLING && loaded) {
            return new RosterResult.Ok(persona.name() + " is already on the way ("
                    + RecallTravel.describe(Math.max(0L, entry.etaAtMs() - System.currentTimeMillis())) + ").");
        }
        if (entry.state() == RosterState.HOLD) {
            return committed(persona.id()) || owner.getEventInstance() != null ? afterTheRun(owner, persona)
                    : rejoinHold(owner, persona, entry);
        }
        CompanionBossRuns bossRuns = world.bossRuns();
        if (entry.state() == RosterState.BENCHED && bossRuns != null && bossRuns.allyOnTrip(persona.id())) {
            return afterTheRun(owner, persona); // a bench ally out with a boss run (P7 Part B, CompanionBossAllies)
        }
        if (owner.getEventInstance() != null && inCompanionRun(owner.getId())) {
            // a load there lands on the run's map unregistered, and a join grows the party mid-run (P6)
            return afterTheRun(owner, persona);
        }
        if (!canSeat(owner, entry)) {
            return world.slots().leaderCapped(owner) ? leaderCapped(owner, persona) : new RosterResult.PartyFull();
        }
        if (entry.state() == RosterState.TRAVELLING) {
            long left = entry.etaAtMs() - System.currentTimeMillis();
            return left > 0L ? travel(owner, persona, entry, campOf(entry, persona), left)
                    : spawnCompanion(owner, persona, entry, null);
        }
        if ((entry.state() == RosterState.BENCHED || entry.state() == RosterState.AWAY) && owner.getMap() != null) {
            int campMap = departure(entry, persona); // a live bench slice sets off from where it stands
            long eta = recallEtaMs(campMap, owner.getMapId());
            if (eta > 0L && !consumeWhistle(owner)) {
                return travel(owner, persona, entry, campMap, eta);
            }
        }
        return spawnCompanion(owner, persona, entry, standing);
    }

    /** Where a benched companion camps: its row's camp, else its home town. */
    private static int campOf(RosterEntry entry, Persona persona) {
        return entry.campMapId() == 0 ? server.living.resident.ResidentSpots.townMapId(persona.homeTown()) : entry.campMapId();
    }

    /**
     * Where a benched companion called now sets off from: the map its live bench slice stands on (a field camp, a town
     * it rests in, a Free Market room; read before the call ends the slice), else its camp. A boss run's bench ally
     * sets off the same way (CompanionBossAllies).
     */
    int departure(RosterEntry entry, Persona persona) {
        BenchDutyDispatcher bench = world.benchDuty();
        BenchDutyDispatcher.Slice slice = bench == null ? null : bench.slice(persona.id());
        return slice != null && slice.mapId() > 0 ? slice.mapId() : campOf(entry, persona);
    }

    /** Decision 7: the road estimate, capped at RECALL_MAX_ETA_MS on the same continent. */
    private long recallEtaMs(int fromMapId, int toMapId) {
        return RecallTravel.etaMs(fromMapId, toMapId, world.config().RECALL_MAX_ETA_MS);
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

    /**
     * Join-then-travel (plan "Join-then-travel arrival"): the companion is loaded at once, without follow, at
     * {@code fromMapId} on the owner's channel (a live bench slice ends first and sets off from where it fought), joins
     * the owner's party at once (the stock "has joined" notice; the party window shows where it is), whispers its ETA
     * and is TRAVELLING with eta_at persisted until {@link #arrive} brings it in. A failed load or join leaves nothing
     * loaded, the row as it was, and gives back a seat taken for it (an AWAY or HOLD seat stays with its row). While the
     * owner's previous load still holds their load slot, it sets off once the slot frees.
     */
    private RosterResult travel(Character owner, Persona persona, RosterEntry entry, int fromMapId, long etaMs) {
        return travel(owner, persona, entry, fromMapId, etaMs, false);
    }

    /** {@code quiet}: the trip goes on after a channel change (its ETA was whispered already): no second ETA whisper. */
    private RosterResult travel(Character owner, Persona persona, RosterEntry entry, int fromMapId, long etaMs, boolean quiet) {
        long waitMs = world.budget().loadDelayMs(owner, System.currentTimeMillis());
        if (waitMs > 0L) {
            travelLater(owner.getId(), persona, entry.state(), fromMapId, waitMs, quiet);
            return new RosterResult.Travelling(etaMs + waitMs, fromMapId);
        }
        if (!seating.add(persona.id())) {
            return new RosterResult.Ok(persona.name() + " is on the way."); // another call is loading it right now
        }
        try {
            int characterId = world.residents().ensureCharacter(persona);
            if (characterId == 0) {
                return new RosterResult.Failed(persona.name() + " cannot be reached right now.");
            }
            RosterResult result;
            try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
                result = CompanionExecutor.transact(
                        () -> holdSeat(owner, persona),
                        held -> setOff(owner, persona, entry, characterId, fromMapId, etaMs, quiet),
                        RosterResult::ok,
                        held -> world.slots().release(persona.id()),
                        held -> world.slots().release(persona.id(), CompanionSlots.Kind.TRAVEL));
            }
            return result != null ? result : refuse(owner, persona, entry, BUSY_LINE, "seat held by another owner", new RosterResult.Busy());
        } finally {
            seating.remove(persona.id());
        }
    }

    /** The load and the join of {@link #travel}, on the seat held for it. */
    private RosterResult setOff(Character owner, Persona persona, RosterEntry entry, int characterId, int fromMapId, long etaMs,
                                boolean quiet) {
        CompanionBudget.Admission admission = world.budget().admit(CompanionBudget.Kind.COMPANION, owner);
        if (!admission.allowed()) {
            return refuse(owner, persona, entry, "give me a moment. too many of us are out right now.", admission.reason(),
                    admission.atCeiling() ? new RosterResult.BudgetExhausted() : new RosterResult.QuotaFull());
        }
        MapleMap from = channelMap(owner, fromMapId);
        if (from == null) {
            return refuse(owner, persona, entry, CANT_GET_AWAY_LINE, "no map " + fromMapId, couldNotJoin(persona));
        }
        Point stood = standingOn(from, persona.id()); // before it is let go below
        RosterResult busy = takeCharacter(owner, persona, entry, characterId);
        if (busy != null) {
            return busy;
        }
        if (owner.getParty() == null) {
            Party.createParty(owner, true, true); // decision 9 (a), as for any call
        }
        Point at = stood != null ? stood : ArrivalPlacement.portalNear(from, null);
        AgentLifecycleService.AgentSpawnResult result = at == null ? null : spawnAt(owner, persona, from, at);
        if (result == null || !result.success() || result.agent() == null) {
            world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
            String reason = result == null ? "no usable portal on map " + fromMapId : result.errorMessage();
            log.warn("call {} for {} failed: {}", persona.id(), owner.getName(), reason);
            return refuse(owner, persona, entry, CANT_GET_AWAY_LINE, reason, couldNotJoin(persona));
        }
        JoinOutcome joined = joinOwner(owner, result.agent());
        if (!joinedOk(joined)) {
            despawn(entry, "party join " + joined);
            return refuse(owner, persona, entry, joinRefusal(joined), joined.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    couldNotJoin(persona));
        }
        RosterEntry row = world.store().rosterEntry(entry.characterId(), entry.personaId());
        if (row == null || row.state() == RosterState.RELEASED) {
            despawn(entry, "released meanwhile");
            return new RosterResult.NotOwned();
        }
        world.bench().applyAccrued(current(entry), result.agent()); // as a call that seats it at once
        reclaimStall(owner, persona, result.agent());
        ownerByPersona.put(persona.id(), owner.getId());
        settings.apply(owner, persona, result.agent());
        return onItsWay(owner, persona, entry, fromMapId, etaMs, quiet);
    }

    private RosterResult onItsWay(Character owner, Persona persona, RosterEntry entry, int fromMapId, long etaMs) {
        return onItsWay(owner, persona, entry, fromMapId, etaMs, false);
    }

    /**
     * A companion in the owner's party now, loaded without follow on {@code fromMapId}: TRAVELLING with its eta_at, it
     * whispers the ETA (unless {@code quiet}), and its arrival is due then.
     */
    private RosterResult onItsWay(Character owner, Persona persona, RosterEntry entry, int fromMapId, long etaMs, boolean quiet) {
        long now = System.currentTimeMillis();
        // eta_at is a whole-second TIMESTAMP: arrive() matches the row by it, so the trip is due on a second the column holds
        long arrivesAt = LivingStore.toWholeSecond(now + etaMs);
        write(current(entry).withTravel(arrivesAt, now));
        if (!quiet) {
            List<String> lines = world.arcs().lines(persona, "onMyWay");
            world.residents().whisper(persona, owner, (lines.isEmpty() ? "on my way." : lines.get(Math.floorMod(owner.getId(), lines.size())))
                    + " " + RecallTravel.describe(etaMs) + ".");
        }
        world.store().event(owner.getId(), persona.id(), "roster.travel", "eta " + etaMs + " ms from map " + fromMapId);
        int ownerId = owner.getId();
        later("roster.arrive " + persona.id(), () -> arrive(ownerId, persona, arrivesAt), Math.max(0L, arrivesAt - now));
        return new RosterResult.Travelling(etaMs, fromMapId);
    }

    /** Sets off once the owner's load slot frees, if the row is still as it was and nobody loaded it meanwhile. */
    private void travelLater(int ownerId, Persona persona, RosterState state, int fromMapId, long delayMs, boolean quiet) {
        later("roster.travel " + persona.id(), () -> {
            Character owner = onlineCharacter(ownerId);
            RosterEntry entry = owner == null ? null : world.store().rosterEntry(ownerId, persona.id());
            if (entry == null || entry.state() != state || companion(entry) != null || seating.contains(persona.id())) {
                return;
            }
            long eta = recallEtaMs(fromMapId, owner.getMapId());
            RosterResult outcome = !canSeat(owner, entry)
                    ? refuse(owner, persona, entry, PARTY_FULL_LINE, "party full", couldNotJoin(persona))
                    : eta > 0L ? travel(owner, persona, entry, fromMapId, eta, quiet) : spawnCompanion(owner, persona, entry, null);
            if (!outcome.ok()) {
                owner.dropMessage(5, RosterVoice.line(persona, outcome));
            }
        }, delayMs);
    }

    /**
     * The trip due at {@code arrivesAt} ends. With its owner inside an event instance it waits outside instead, on the
     * map they went in from (HOLD). Else it comes into the owner's current map at the portal nearest them (from where
     * it stands, when it is on that map already), walks into formation, and only then follows; the row is ACTIVE. A
     * trip turned back, re-timed or replaced does nothing; so does one whose owner logged off (it parked with them).
     * One changing channel with its owner looks again once that move has had its turn (a Cash Shop exit moves nothing).
     * One its owner changed channel away from comes in on theirs, and one no longer in its owner's party (a merge or a
     * disband on the way) takes a seat there or turns back with returnWhenRoom.
     */
    private void arrive(int ownerId, Persona persona, long arrivesAt) {
        RosterEntry entry = world.store().rosterEntry(ownerId, persona.id());
        if (entry == null || entry.state() != RosterState.TRAVELLING || entry.etaAtMs() != arrivesAt) {
            return;
        }
        if (channelFollow.pending(persona.id())) {
            later("roster.arrive " + persona.id(), () -> arrive(ownerId, persona, arrivesAt), CompanionChannelFollow.STAGGER_MAX_MS);
            return;
        }
        Character owner = onlineCharacter(ownerId);
        Character bot = companion(entry);
        if (owner == null || bot == null) {
            return;
        }
        if (owner.getEventInstance() != null) {
            Spot entrance = lastOpenMap.get(ownerId);
            hold(owner, persona, entrance != null ? entrance.mapId() : bot.getMapId(), null);
            return;
        }
        if (!sameChannel(owner, bot)) {
            despawn(entry, "its owner changed channel on the way");
            spawnCompanion(owner, persona, entry, null);
            return;
        }
        if (!sameParty(owner, bot) && !(canSeat(owner, entry) && rejoin(owner, entry, bot))) {
            standDown(owner, persona, "your party's full. i'll head back to camp.", "companion.overflow");
            world.slots().waitForRoom(persona.id(), 0);
            return;
        }
        MapleMap map = owner.getMap();
        if (bot.getMap() != map) {
            Point portal = ArrivalPlacement.nearestPortal(map, owner.getPosition());
            bot.changeMap(map, portal != null ? portal : owner.getPosition()); // no usable portal: beside them, as P1.5
        }
        walkToOwner(persona, bot);
        write(current(entry).with(RosterState.ACTIVE, System.currentTimeMillis()));
        settings.apply(owner, persona, bot); // her settings are re-applied (plan flow 1, step 5)
        world.store().event(ownerId, persona.id(), "roster.arrive", "map " + owner.getMapId());
        CompanionPartyQuestService partyQuests = world.partyQuests();
        // sent for from an entry NPC's bench (P6): it tells the party waiting there, instead of its usual word to the
        // owner, in her desk's words ("here! ready for Lakelis when you are."), and the log keeps the line said
        CompanionPartyQuestService.ArrivalLine readyLine = partyQuests == null ? null
                : partyQuests.takeArrivalLine(ownerId, persona.id(), System.currentTimeMillis());
        if (readyLine != null && partyChat(bot, readyLine.text())) {
            readyLine.said(bot.getName());
        } else {
            world.residents().reply(persona, owner, joinLine(persona, ownerId));
        }
        BenchLedger ledger = world.bench();
        String report = ledger == null ? null : ledger.takeReport(ownerId, persona.id(), bot.getLevel());
        if (report != null) {
            world.residents().whisper(persona, owner, report); // what it did on the bench, once (P5, flow 1 step 5)
        }
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
        return spawnCompanion(owner, persona, entry, standing, false);
    }

    /** {@code quiet}: a restore (login, channel follow), which the stock party notice announces: no join line. */
    private RosterResult spawnCompanion(Character owner, Persona persona, RosterEntry entry, Point standing, boolean quiet) {
        long waitMs = world.budget().loadDelayMs(owner, System.currentTimeMillis());
        if (waitMs > 0L) {
            spawnLater(owner.getId(), persona, entry.state(), waitMs, quiet); // loads are spaced per owner: queue, not refuse
            return new RosterResult.Ok(persona.name() + " will be right there.");
        }
        if (!seating.add(persona.id())) {
            return new RosterResult.Ok(persona.name() + " is on the way."); // another call is loading this companion right now
        }
        try {
            return seat(owner, persona, entry, standing, quiet);
        } finally {
            seating.remove(persona.id());
        }
    }

    /**
     * Reserve, load, then commit or roll back (plan "CompanionExecutor"): the seat is held before anything is loaded,
     * so the party's seat gate admits the join. A seated companion is a member and needs no reservation; a failed load
     * gives back only a travel seat, since an AWAY or HOLD one stays with its row.
     */
    private RosterResult seat(Character owner, Persona persona, RosterEntry entry, Point standing, boolean quiet) {
        int characterId = world.residents().ensureCharacter(persona);
        if (characterId == 0) {
            return new RosterResult.Failed(persona.name() + " cannot be reached right now.");
        }
        RosterResult result;
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            result = CompanionExecutor.transact(
                    () -> holdSeat(owner, persona),
                    held -> load(owner, persona, entry, standing, characterId, quiet),
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

    private RosterResult load(Character owner, Persona persona, RosterEntry entry, Point standing, int characterId,
                              boolean quiet) {
        CompanionBudget.Admission admission = world.budget().admit(CompanionBudget.Kind.COMPANION, owner);
        if (!admission.allowed()) {
            return refuse(owner, persona, entry, "give me a moment. too many of us are out right now.", admission.reason(),
                    admission.atCeiling() ? new RosterResult.BudgetExhausted() : new RosterResult.QuotaFull());
        }
        Point from = standing != null ? standing : standingNear(owner, persona.id()); // before it is let go below
        RosterResult busy = takeCharacter(owner, persona, entry, characterId);
        if (busy != null) {
            return busy;
        }
        // before the spawn: its registration (or the join below) joins the owner's party, and would make one without the exemption
        if (owner.getParty() == null) {
            Party.createParty(owner, true, true); // decision 9 (a): below level 10 a starter party of their own companions
        }
        // a walk-in is loaded standing still where it comes from; a map without a usable portal keeps the beside-owner spawn
        MapleMap map = owner.getMap();
        Point arrival = from != null ? from : ArrivalPlacement.nearestPortal(map, owner.getPosition());
        if (arrival == null) {
            offFollow.remove(persona.id()); // it spawns beside its owner following already, so an AWAY row may too
        }
        AgentLifecycleService.AgentSpawnResult result = arrival == null
                ? AgentInteractionRuntime.spawnAgentForLeader(owner, persona.name())
                : spawnAt(owner, persona, map, arrival);
        if (!result.success() || result.agent() == null) {
            world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
            log.warn("call {} for {} failed: {}", persona.id(), owner.getName(), result.errorMessage());
            return refuse(owner, persona, entry, CANT_GET_AWAY_LINE, result.errorMessage(), couldNotJoin(persona));
        }
        JoinOutcome joined = joinOwner(owner, result.agent());
        if (!joinedOk(joined)) {
            despawn(entry, "party join " + joined);
            return refuse(owner, persona, entry, joinRefusal(joined), joined.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    couldNotJoin(persona));
        }
        if (arrival != null) {
            walkToOwner(persona, result.agent()); // only once it is in the party
        }
        RosterEntry row = world.store().rosterEntry(entry.characterId(), entry.personaId());
        if (row == null || row.state() == RosterState.RELEASED) {
            despawn(entry, "released meanwhile"); // never write a released row back from the snapshot
            return new RosterResult.NotOwned();
        }
        // bench slices and the ledger rewrite the row behind the caller's snapshot, and applyAccrued zeroes the
        // banked EXP: both saves start from the row as it is now, so the EXP can never be granted twice
        world.bench().applyAccrued(current(entry), result.agent());
        reclaimStall(owner, persona, result.agent());
        RosterEntry active = current(entry).with(RosterState.ACTIVE, System.currentTimeMillis());
        write(active);
        ownerByPersona.put(persona.id(), owner.getId());
        settings.apply(owner, persona, result.agent());
        world.store().event(owner.getId(), persona.id(), "roster.call", null);
        String report = world.bench().takeReport(owner.getId(), persona.id(), result.agent().getLevel()); // spent either way
        if (!quiet) {
            world.residents().reply(persona, owner, joinLine(persona, owner.getId()));
            if (report != null) {
                world.residents().whisper(persona, owner, report); // what it did on the bench, once (P5)
            }
        }
        return new RosterResult.Ok(persona.name() + " joins your party.");
    }

    /**
     * Lets go of whatever holds the persona's character before it is loaded as a companion: a walk-out in progress, a
     * live bench slice, its Resident life. A character loaded for something else is refused (the result), never pulled
     * out from under it; null when the COMPANION lease is taken.
     */
    private RosterResult takeCharacter(Character owner, Persona persona, RosterEntry entry, int characterId) {
        if (leaving.containsKey(persona.id())) {
            despawn(entry, "called back on its way out"); // it comes back from where it stood, so it frees its lease first
        }
        world.benchDuty().endFor(persona.id(), "called by owner"); // a live slice ends properly and frees its lease
        world.residents().releaseForRecruit(persona.id());
        if (!world.leases().tryAcquire(characterId, CompanionLeases.Kind.COMPANION, owner.getId())) {
            return refuse(owner, persona, entry, BUSY_LINE, "busy", new RosterResult.Busy());
        }
        if (AgentRuntimeRegistry.hasActiveAgentCharacterId(characterId)
                && AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId) != owner.getId()) {
            world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
            return refuse(owner, persona, entry, BUSY_LINE, "busy", new RosterResult.Busy());
        }
        return null;
    }

    /** A companion just loaded: what an interrupted bench stall of its left behind goes back to the haul first. */
    private void reclaimStall(Character owner, Persona persona, Character bot) {
        BenchDutyDispatcher bench = world.benchDuty();
        if (bench != null) {
            bench.reclaimStall(owner.getId(), persona.id(), bot);
        }
    }

    /** Loads (or places) the companion under its owner standing still at {@code point} on {@code map}: no follow yet. */
    private static AgentLifecycleService.AgentSpawnResult spawnAt(Character owner, Persona persona, MapleMap map, Point point) {
        return AgentInteractionRuntime.spawnStationaryAgentForLeaderAt(owner, persona.name(), map, point);
    }

    /**
     * Every roster join: the companion joins its owner's party (made when they have none) on the seat held for it. One
     * the party still lists as an offline member (it logged off with its owner, or to change channel) takes that row
     * back, as a player logging in does: an Agent load does not restore its party, and a fresh join would list it twice.
     */
    private static JoinOutcome joinOwner(Character owner, Character bot) {
        Party party = owner.getParty();
        if (bot.getParty() == null && party != null && party.getMemberById(bot.getId()) != null) {
            bot.setParty(party);
        }
        return AgentPartyLifecycleService.joinAgentToLeaderParty(owner, bot);
    }

    private static boolean joinedOk(JoinOutcome joined) {
        return joined == JoinOutcome.JOINED || joined == JoinOutcome.ALREADY_MEMBER;
    }

    /** {@code mapId} on the owner's channel (their own map when it is that one and not an instance's copy), or null. */
    MapleMap channelMap(Character owner, int mapId) {
        MapleMap here = owner.getMap();
        if (here != null && here.getId() == mapId && here.getEventInstance() == null) {
            return here;
        }
        Client client = owner.getClient();
        return client == null || client.getChannelServer() == null ? null : client.getChannelServer().getMapFactory().getMap(mapId);
    }

    /** Where the persona's character stands on {@code map} when it is loaded there (a live bench slice), else null. */
    private Point standingOn(MapleMap map, String personaId) {
        Character live = loaded(personaId);
        return live == null || map == null || live.getMap() != map || live.getPosition() == null ? null : new Point(live.getPosition());
    }

    /** False only when both are known to be on different channels. */
    static boolean sameChannel(Character owner, Character bot) {
        Client ours = owner.getClient();
        Client its = bot.getClient();
        return ours == null || its == null || ours.getChannel() == its.getChannel();
    }

    /**
     * A companion loaded at its arrival point stands still until it is seated; then it follows, so it walks to its owner.
     * The one way the roster lets a TRAVELLING, HOLD or AWAY companion follow again (its arrival, a rejoin, a call).
     */
    private void walkToOwner(Persona persona, Character bot) {
        offFollow.remove(persona.id());
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        if (runtime != null) {
            AgentMovementCommandRuntime.followConfiguredTarget(runtime);
        }
    }

    /** Retries the spawn once the owner's next load is admitted, if the row is still as it was and nobody is loaded. */
    private void spawnLater(int ownerId, Persona persona, RosterState state, long delayMs, boolean quiet) {
        later("roster.seat " + persona.id(), () -> seatQuietly(ownerId, persona, state, quiet), delayMs);
    }

    /** Runs {@code task} on companion-ops after {@code delayMs}: the timer only hands it over. */
    void later(String name, Runnable task, long delayMs) {
        server.TimerManager.getInstance().schedule(server.TimerManager.SchedulerLane.LOW_PRIORITY,
                () -> world.executor().submit(name, task), delayMs);
    }

    /**
     * Seats a queued or restored companion. While another load holds the owner's slot it waits for the next one
     * without a word; only a refusal is printed, since the stock "has joined the party" notice announces a seat. A
     * TRAVELLING row restored at login sets off again and a HOLD one rejoins, through {@link #call}. A PARKED one logs
     * back in: an offline member of its owner's party has its seat there already. {@code quiet}: a restore, which
     * says no join line (the login's one whisper sums it up).
     */
    private void seatQuietly(int ownerId, Persona persona, RosterState state, boolean quiet) {
        Character owner = onlineCharacter(ownerId);
        RosterEntry entry = owner == null ? null : world.store().rosterEntry(ownerId, persona.id());
        if (entry == null || entry.state() != state || companion(entry) != null || seating.contains(persona.id())) {
            return;
        }
        long waitMs = world.budget().loadDelayMs(owner, System.currentTimeMillis());
        if (waitMs > 0L) {
            spawnLater(ownerId, persona, state, waitMs, quiet);
            return;
        }
        // the party may have filled while this one waited; an AWAY or travelling companion kept its seat (so does an
        // offline member), and a restored ACTIVE (or PARKED) one with no seat left is benched with returnWhenRoom, so
        // its row says where it is and it comes back by itself once there is room
        boolean full = !canSeat(owner, entry);
        RosterResult outcome;
        if (full && (CompanionSeatState.partyMember(state) || state == RosterState.PARKED)) {
            outcome = standDown(owner, persona, overflowLine(persona, ownerId), "companion.overflow");
            world.slots().waitForRoom(persona.id(), 0);
        } else if (full) {
            outcome = refuse(owner, persona, entry, PARTY_FULL_LINE, "party full", couldNotJoin(persona));
        } else {
            outcome = state == RosterState.TRAVELLING || state == RosterState.HOLD ? call(owner, persona, null)
                    : spawnCompanion(owner, persona, entry, null, quiet);
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

    /** True when the companion is listed in its owner's party, online or (parked with them) as an offline member. */
    private boolean inOwnersParty(Character owner, RosterEntry entry) {
        Party party = owner.getParty();
        int characterId = world.residents().characterIdOf(entry.personaId());
        return party != null && characterId != 0 && party.getMemberById(characterId) != null;
    }

    /**
     * A call that could not seat the companion: nothing stays loaded for it, the row stays BENCHED (or AWAY, or HOLD)
     * so the ledger keeps crediting it, and the companion tells the owner why (the reason goes to the event log). An
     * ACTIVE, TRAVELLING or PARKED row nothing seated is benched (a PARKED one leaves the party it is still listed in
     * as an offline member); one that another call seated meanwhile stays as it is. {@code result} is what the caller
     * is told.
     */
    private RosterResult refuse(Character owner, Persona persona, RosterEntry entry, String line, String reason,
                                RosterResult result) {
        RosterEntry current = current(entry);
        if ((CompanionSeatState.partyMember(current.state()) || current.state() == RosterState.PARKED)
                && !seatedWith(owner, current)) {
            if (current.state() == RosterState.PARKED) {
                leaveOfflineParty(current);
            }
            write(current.with(RosterState.BENCHED, System.currentTimeMillis()));
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

    /**
     * Benches a companion that is out; one called and still on its way (TRAVELLING) turns back and never arrives: it
     * leaves the party, walks out (or goes at once, off its owner's map) and its seat goes back. Refused while it is
     * committed to a run ("after the run").
     */
    public RosterResult bench(Character owner, Persona persona) {
        if (committed(persona.id())) {
            return afterTheRun(owner, persona);
        }
        world.slots().stopWaiting(persona.id()); // benched on purpose: it does not come back by itself
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry != null && entry.state() == RosterState.TRAVELLING) {
            walkOut(owner, entry);
            world.slots().release(persona.id());
            write(entry.with(RosterState.BENCHED, System.currentTimeMillis()));
            world.store().event(owner.getId(), persona.id(), "roster.bench", "turned back on the way");
            String warning = benchWarning(owner, persona);
            if (warning != null) {
                world.residents().whisper(persona, owner, warning);
            }
            return new RosterResult.Ok(persona.name() + " turns back to camp.");
        }
        return standDown(owner, persona, null, "roster.bench");
    }

    /**
     * What a low-tolerance companion says as it is benched while ROSTER_CONTRACT_MODE lets the bench end its contract
     * (ContractService.benchWarning: chat and every other bench hear it in its words); null when there is nothing to warn of.
     */
    private String benchWarning(Character owner, Persona persona) {
        ContractService contracts = world.contracts();
        return contracts == null ? null : contracts.benchWarning(owner.getId(), persona);
    }

    /**
     * True while the owner is in a companion party-quest run: one of theirs is committed to it, or they went in with
     * its lineup. Any other event instance (a ride, a wedding, a job test) takes calls as it always did.
     */
    private boolean inCompanionRun(int ownerId) {
        Commitments commitments = world.commitments();
        CompanionPartyQuestService partyQuests = world.partyQuests();
        return commitments != null && commitments.ownerCommitted(ownerId)
                || partyQuests != null && partyQuests.runForCharacter(ownerId) != null;
    }

    /** True while the persona's companion is committed to an event instance or expedition (Commitments). */
    private boolean committed(String personaId) {
        Commitments commitments = world.commitments();
        int characterId = world.residents().characterIdOf(personaId);
        return commitments != null && characterId != 0 && commitments.isCommitted(characterId);
    }

    /** A bench, swap or errand asked for mid-run: the companion answers in its own words. */
    private RosterResult afterTheRun(Character owner, Persona persona) {
        world.residents().whisper(persona, owner, AFTER_THE_RUN_LINE);
        return new RosterResult.InEventInstance();
    }

    /** An order given to a companion committed to a run (CompanionManager): it answers "after the run." to its owner. */
    public RosterResult answerAfterTheRun(Character owner, Persona persona) {
        return afterTheRun(owner, persona);
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
        return standDown(owner, persona, line, eventKind, false);
    }

    /** {@link #standDown(Character, Persona, String, String)}; {@code down}: it went down and leaves at once, no walk. */
    private RosterResult standDown(Character owner, Persona persona, String line, String eventKind, boolean down) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return new RosterResult.NotOwned();
        }
        if (entry.state() == RosterState.BENCHED) {
            return new RosterResult.Ok(persona.name() + " is already benched.");
        }
        if (line == null) {
            String warning = benchWarning(owner, persona); // a bench that may end its contract: it says so as it goes
            List<String> lines = world.arcs().lines(persona, "bench");
            world.residents().reply(persona, owner, warning != null ? warning
                    : lines.isEmpty() ? "alright. i'll keep busy." : lines.get(Math.floorMod(owner.getId(), lines.size())));
        } else {
            world.residents().whisper(persona, owner, line);
        }
        if (entry.state() == RosterState.PARKED) {
            leaveOfflineParty(entry); // not loaded yet: it leaves the party it is listed in as an offline member
        }
        walkOut(owner, entry, down);
        world.slots().release(persona.id()); // an errand's seat goes back with it
        RosterEntry benched = entry.with(RosterState.BENCHED, System.currentTimeMillis());
        write(benched);
        world.store().event(owner.getId(), persona.id(), eventKind, benched.assignment().name());
        return new RosterResult.Ok(persona.name() + " heads back to camp to " + describeAssignment(benched) + ".");
    }

    /**
     * A benched companion on its owner's map leaves the party at once (the party window shows it), walks to the
     * nearest portal and goes when it gets there or after a few seconds, so it never vanishes on the spot (plan
     * "Rotate"). It keeps its COMPANION lease until then. Anywhere else, with no usable portal, or when it went down
     * ({@code down}, or no HP left of its pool), it goes at once.
     */
    private void walkOut(Character owner, RosterEntry entry) {
        walkOut(owner, entry, false);
    }

    private void walkOut(Character owner, RosterEntry entry, boolean down) {
        Character bot = companion(entry);
        MapleMap map = bot == null ? null : bot.getMap();
        boolean dead = down || bot != null && bot.getHp() <= 0 && bot.getCurrentMaxHp() > 0; // a corpse walks nowhere
        Point portal = map == null || map != owner.getMap() || dead ? null
                : ArrivalPlacement.nearestPortal(map, bot.getPosition());
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

    /**
     * Atomic swap (plan flow 1). The incoming companion is loaded first, without follow, at its camp on the owner's
     * channel (at the portal nearest the owner when it camps on their map). Then, under one cause token, the outgoing
     * one leaves the party and the incoming one joins on the seat it gave up, so the party never passes 6 and never
     * shows a hole. A join that fails rolls everything back: nothing incoming stays loaded and the outgoing one takes
     * its seat again. Otherwise the outgoing one says so and walks out to its duty, and the incoming one travels (or
     * walks in from the portal). Refused while either is committed to a run. With nobody out to trade places with, or
     * an incoming one that is not on the bench, it is a bench and a call. Ok when the incoming one is seated or on its way.
     */
    public RosterResult swap(Character owner, Persona out, Persona in) {
        return swap(owner, out, in, null);
    }

    /**
     * {@link #swap(Character, Persona, Persona)} for an outgoing one that went down (CrewRotation): it whispers
     * {@code outLine} to its owner instead of its bench words, and leaves at once while it is dead, since it cannot walk
     * to a portal (null: the usual swap).
     */
    public RosterResult swap(Character owner, Persona out, Persona in, String outLine) {
        if (committed(out.id()) || committed(in.id())) {
            return afterTheRun(owner, committed(out.id()) ? out : in);
        }
        RosterEntry leaving = world.store().rosterEntry(owner.getId(), out.id());
        RosterEntry coming = world.store().rosterEntry(owner.getId(), in.id());
        Character outBot = leaving == null ? null : companion(leaving);
        boolean seated = outBot != null && CompanionSeatState.partyMember(leaving.state()) && sameParty(owner, outBot);
        boolean benched = coming != null && (coming.state() == RosterState.BENCHED || coming.state() == RosterState.AWAY);
        if (!seated || !benched || owner.getMap() == null) {
            RosterResult benchedOut = bench(owner, out);
            RosterResult called = call(owner, in);
            String line = RosterVoice.line(out, benchedOut) + " " + RosterVoice.line(in, called);
            return called.ok() ? new RosterResult.Ok(line) : new RosterResult.Failed(line);
        }
        long waitMs = world.budget().loadDelayMs(owner, System.currentTimeMillis());
        if (waitMs > 0L) { // the owner's previous load still holds their load slot: the two trade places once it frees
            int ownerId = owner.getId();
            later("roster.swap " + in.id(), () -> {
                Character now = onlineCharacter(ownerId);
                RosterResult result = now == null ? null : swap(now, out, in, outLine);
                if (result != null && !result.ok()) {
                    now.dropMessage(5, RosterVoice.line(in, result));
                }
            }, waitMs);
            return new RosterResult.Ok(in.name() + " will take " + out.name() + "'s place in a moment.");
        }
        if (!seating.add(in.id())) {
            return new RosterResult.Ok(in.name() + " is on the way.");
        }
        try {
            return tradePlaces(owner, out, leaving, outBot, in, coming, outLine);
        } finally {
            seating.remove(in.id());
        }
    }

    private RosterResult tradePlaces(Character owner, Persona out, RosterEntry leaving, Character outBot, Persona in,
                                     RosterEntry coming, String outLine) {
        int characterId = world.residents().ensureCharacter(in);
        if (characterId == 0) {
            return new RosterResult.Failed(in.name() + " cannot be reached right now.");
        }
        CompanionBudget.Admission admission = world.budget().admitReplacing(CompanionBudget.Kind.COMPANION, owner);
        if (!admission.allowed()) {
            return refuse(owner, in, coming, "give me a moment. too many of us are out right now.", admission.reason(),
                    admission.atCeiling() ? new RosterResult.BudgetExhausted() : new RosterResult.QuotaFull());
        }
        int campMap = departure(coming, in); // a live bench slice sets off from where it stands
        long eta = recallEtaMs(campMap, owner.getMapId());
        if (eta > 0L && consumeWhistle(owner)) {
            eta = 0L;
        }
        MapleMap from = eta > 0L ? channelMap(owner, campMap) : owner.getMap();
        if (from == null) {
            return refuse(owner, in, coming, CANT_GET_AWAY_LINE, "no map " + campMap, couldNotJoin(in));
        }
        Point stood = eta > 0L ? standingOn(from, in.id()) : standingNear(owner, in.id()); // before it is let go below
        RosterResult busy = takeCharacter(owner, in, coming, characterId);
        if (busy != null) {
            return busy;
        }
        Point at = stood != null ? stood : eta > 0L ? ArrivalPlacement.portalNear(from, null)
                : ArrivalPlacement.nearestPortal(from, owner.getPosition());
        if (at == null && eta <= 0L) {
            at = owner.getPosition(); // no usable portal on the owner's map: it comes in beside them, as P1.5
        }
        AgentLifecycleService.AgentSpawnResult result = at == null ? null : spawnAt(owner, in, from, at);
        if (result == null || !result.success() || result.agent() == null) {
            world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
            String reason = result == null ? "no usable portal on map " + campMap : result.errorMessage();
            log.warn("swap in {} for {} failed: {}", in.id(), owner.getName(), reason);
            return refuse(owner, in, coming, CANT_GET_AWAY_LINE, reason, couldNotJoin(in));
        }
        Character inBot = result.agent();
        JoinOutcome joined;
        long now = System.currentTimeMillis();
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            leaveParty(outBot, leaving);
            boolean ownSeat = world.slots().reservation(in.id()) != null; // an errand keeps its AWAY seat for it
            if (!ownSeat) {
                world.slots().reserve(owner.getId(), in.id(), CompanionSlots.Kind.TRAVEL, now + CompanionSlots.SEAT_HOLD_MS);
            }
            joined = joinOwner(owner, inBot); // on the seat the outgoing one just gave up
            if (joinedOk(joined)) {
                world.slots().release(in.id()); // a member now
            } else {
                world.slots().release(in.id(), CompanionSlots.Kind.TRAVEL);
                despawn(coming, "swap join " + joined);
                world.slots().reserve(owner.getId(), out.id(), CompanionSlots.Kind.TRAVEL, now + CompanionSlots.SEAT_HOLD_MS);
                joinOwner(owner, outBot); // the outgoing one takes its seat back
                world.slots().release(out.id(), CompanionSlots.Kind.TRAVEL);
            }
        }
        if (!joinedOk(joined)) {
            return refuse(owner, in, coming, joinRefusal(joined), "swap: " + joined.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    couldNotJoin(in));
        }
        world.slots().stopWaiting(out.id());
        world.slots().stopWaiting(in.id());
        // it says so and walks out to its duty; one that went down whispers why, and goes at once while it is dead
        RosterResult benchedOut = standDown(owner, out, outLine, "roster.bench", outLine != null && outBot.getHp() <= 0);
        world.bench().applyAccrued(current(coming), inBot);
        reclaimStall(owner, in, inBot);
        ownerByPersona.put(in.id(), owner.getId());
        settings.apply(owner, in, inBot);
        RosterResult called;
        if (eta > 0L) {
            called = onItsWay(owner, in, coming, campMap, eta);
        } else {
            walkToOwner(in, inBot);
            write(current(coming).with(RosterState.ACTIVE, System.currentTimeMillis()));
            List<String> lines = world.arcs().lines(in, "join");
            world.residents().reply(in, owner, lines.isEmpty() ? "here." : lines.get(Math.floorMod(owner.getId(), lines.size())));
            String report = world.bench().takeReport(owner.getId(), in.id(), inBot.getLevel()); // flow 1 step 5, once
            if (report != null) {
                world.residents().whisper(in, owner, report);
            }
            called = new RosterResult.Ok(in.name() + " joins your party.");
        }
        world.store().event(owner.getId(), in.id(), "roster.swap", out.id() + " -> " + in.id());
        return new RosterResult.Ok(RosterVoice.line(out, benchedOut) + " " + RosterVoice.line(in, called));
    }

    public RosterResult setCamp(Character owner, Persona persona, int mapId) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return new RosterResult.NotOwned();
        }
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), mapId, entry.accruedExp(), entry.haulMeso(),
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(),
                entry.farmSecondsToday(), entry.farmDay(), entry.etaAtMs(), entry.holdMapId(), entry.parkedAtMs());
        write(updated);
        world.store().event(owner.getId(), persona.id(), CAMP_EVENT, "camp " + mapId); // its record there starts again
        endYourCall(owner.getId(), persona.id()); // its owner picked: no longer its own call
        return new RosterResult.Ok(persona.name() + " will camp at map " + mapId + ".");
    }

    /**
     * The companion leaves a camp too rough for it for {@code mapId} (BenchDutyDispatcher, before a slice; its owner may
     * be offline): the row's camp, its duty kept. The row now, or null when it is gone or not on the bench.
     */
    public RosterEntry moveCamp(int ownerId, String personaId, int mapId, String why) {
        RosterEntry entry = world.store().rosterEntry(ownerId, personaId);
        if (!BenchSessions.benchGoverned(entry)) {
            return null;
        }
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), mapId, entry.accruedExp(), entry.haulMeso(),
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(),
                entry.farmSecondsToday(), entry.farmDay(), entry.etaAtMs(), entry.holdMapId(), entry.parkedAtMs());
        write(updated);
        world.store().event(ownerId, personaId, CAMP_EVENT, "camp " + mapId + " (" + why + ")");
        return updated;
    }

    /**
     * Your call's own pick (BenchDutyDispatcher, before a slice of a row on Your call): the duty (no item, no count) and
     * the camp, in one write, Your call staying on. The row now, or null when it is gone or not on the bench.
     */
    public RosterEntry applyYourCall(int ownerId, String personaId, Assignment duty, int campMapId) {
        RosterEntry entry = world.store().rosterEntry(ownerId, personaId);
        if (!BenchSessions.benchGoverned(entry) || duty == null) {
            return null;
        }
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), duty, 0, 0, campMapId,
                entry.accruedExp(), entry.haulMeso(), entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(),
                entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay(), entry.etaAtMs(), entry.holdMapId(),
                entry.parkedAtMs());
        write(updated);
        world.store().event(ownerId, personaId, "roster.assign", describeAssignment(updated) + " at map " + campMapId
                + " (your call)");
        return updated;
    }

    /** A duty or camp its owner gave it: "Your call" is off for the row. */
    private void endYourCall(int ownerId, String personaId) {
        BenchDutyDispatcher bench = world.benchDuty();
        if (bench != null) {
            bench.setYourCall(ownerId, personaId, false);
        }
    }

    public RosterResult assign(Character owner, Persona persona, Assignment assignment, int itemId, int count) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return new RosterResult.NotOwned();
        }
        if (assignment == Assignment.FARM_ITEM && world.quests().isOwnQuestTarget(owner, persona, itemId)) {
            return refuseOwnErrand(persona);
        }
        int camp = entry.campMapId();
        if (assignment == Assignment.FARM_ITEM) { // it camps where the item drops, or says why it can't
            FarmPick pick = farmPick(persona, itemId, camp, camp != 0 ? camp
                    : server.living.resident.ResidentSpots.townMapId(persona.homeTown()), false);
            if (pick != null && pick.refusal() != null) {
                return pick.refusal();
            }
            camp = pick == null ? camp : pick.mapId();
        }
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), assignment,
                itemId, count, camp, entry.accruedExp(), entry.haulMeso(), entry.wagesPaidUntilMs(),
                entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay(),
                entry.etaAtMs(), entry.holdMapId(), entry.parkedAtMs());
        write(updated);
        if (camp != entry.campMapId()) { // as moveCamp: its record at the new camp starts again
            world.store().event(owner.getId(), persona.id(), CAMP_EVENT, "camp " + camp + " (farm " + itemId + ")");
        }
        endYourCall(owner.getId(), persona.id());
        world.store().event(owner.getId(), persona.id(), "roster.assign", describeAssignment(updated));
        return new RosterResult.Ok(persona.name() + " will " + describeAssignment(updated)
                + (camp != entry.campMapId() ? " at " + CompanionView.placeName(camp) : "")
                + (entry.state() == RosterState.ACTIVE ? " once benched." : ".")
                + (assignment == Assignment.GATHER ? gatherWarning(persona, updated, camp) : ""));
    }

    private RosterResult refuseOwnErrand(Persona persona) {
        List<String> lines = world.arcs().lines(persona, "refuseOwnErrand");
        return new RosterResult.Failed(lines.isEmpty() ? persona.name() + ": that one's your errand, not mine." : persona.name() + ": " + lines.get(0));
    }

    /**
     * Delegated remote work (plan 11.2): an active companion leaves for a work map and keeps its
     * party slot; from there the bench rules govern it (ledger, live sessions, haul, no own-quest
     * credit) until {@code come back} calls it. A benched companion is simply reassigned, and
     * {@code mapId} 0 means "where it already camps". Refused while it is committed to a run ("after the run").
     */
    public RosterResult delegate(Character owner, Persona persona, Assignment assignment, int itemId, int count, int mapId) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return new RosterResult.NotOwned();
        }
        if (committed(persona.id())) {
            return afterTheRun(owner, persona);
        }
        if (assignment == Assignment.FARM_ITEM && world.quests().isOwnQuestTarget(owner, persona, itemId)) {
            return refuseOwnErrand(persona);
        }
        int workMap = mapId != 0 ? mapId : entry.campMapId() != 0 ? entry.campMapId() : server.living.resident.ResidentSpots.townMapId(persona.homeTown());
        String movedLine = null;
        if (assignment == Assignment.FARM_ITEM) { // out with its owner it looks from where they stand, else from its work map
            FarmPick pick = farmPick(persona, itemId, workMap, mapId != 0 ? mapId
                    : entry.state() == RosterState.ACTIVE ? owner.getMapId() : workMap, mapId != 0);
            if (pick != null && pick.refusal() != null) {
                return pick.refusal();
            }
            if (pick != null && pick.mapId() != workMap) {
                workMap = pick.mapId();
                movedLine = pick.said() + ". heading there.";
            }
        }
        boolean fighting = assignment == Assignment.TRAIN || assignment == Assignment.FARM_MESO
                || assignment == Assignment.FARM_ITEM || assignment == Assignment.GATHER;
        RosterEntry updated = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), assignment,
                itemId, count, workMap, entry.accruedExp(), entry.haulMeso(), entry.wagesPaidUntilMs(),
                entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay(),
                entry.etaAtMs(), entry.holdMapId(), entry.parkedAtMs());
        server.maps.MapleMap map = world.residents().map(workMap);
        if (fighting && (map == null || map.isTown())) {
            return new RosterResult.Failed(persona.name() + ": where? take me to a field and say 'camp here', or send me from one with 'go "
                    + describeAssignment(updated) + " here'.");
        }
        endYourCall(owner.getId(), persona.id());
        String warning = assignment == Assignment.GATHER ? gatherWarning(persona, updated, workMap) : "";
        if (entry.state() != RosterState.ACTIVE) {
            write(updated);
            world.store().event(owner.getId(), persona.id(), "roster.assign", describeAssignment(updated) + " at map " + workMap);
            return new RosterResult.Ok(persona.name() + " will " + describeAssignment(updated) + " at map " + workMap + "." + warning);
        }
        List<String> lines = world.arcs().lines(persona, "delegate");
        world.residents().reply(persona, owner, movedLine != null ? movedLine
                : lines.isEmpty() ? "on it. call me when you want me back." : lines.get(Math.floorMod(owner.getId(), lines.size())));
        keepAwaySeat(owner.getId(), persona.id()); // before it leaves the party, so its seat is never up for grabs
        walkOut(owner, entry); // it walks out to the portal (off its owner's map it goes at once)
        RosterEntry away = updated.with(RosterState.AWAY, System.currentTimeMillis());
        write(away);
        world.store().event(owner.getId(), persona.id(), "roster.delegate", describeAssignment(away) + " at map " + workMap);
        return new RosterResult.Ok(persona.name() + " leaves to " + describeAssignment(away) + (mapId != 0 ? " here" : " at map " + workMap)
                + ", keeping the party slot. Say '" + persona.name().toLowerCase(Locale.ROOT) + " come back' to recall." + warning);
    }

    /**
     * A companion out with its owner leaves on an errand of its own (its next job: JobErrands): it says why in
     * {@code line}, keeps its party seat as an AWAY reservation and walks out like a delegated one. Its duty and camp
     * stay as they were, so the bench keeps it as before once the errand is over. Never while it is committed to a run.
     */
    public RosterResult leaveOnErrand(Character owner, Persona persona, String line) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() != RosterState.ACTIVE || companion(entry) == null) {
            return new RosterResult.Failed(persona.name() + " is not out with you.");
        }
        if (committed(persona.id())) {
            return new RosterResult.InEventInstance();
        }
        world.residents().reply(persona, owner, line);
        keepAwaySeat(owner.getId(), persona.id()); // before it leaves the party, so its seat is never up for grabs
        walkOut(owner, entry); // it walks out to the portal (off its owner's map it goes at once)
        write(entry.with(RosterState.AWAY, System.currentTimeMillis()));
        world.store().event(owner.getId(), persona.id(), "roster.errand", line);
        return new RosterResult.Ok(persona.name() + " leaves on an errand, keeping the party slot.");
    }

    /** Its next arrival (the end of a trip, or a seat at once) says {@code line} instead of its join line; null forgets it. */
    void sayOnArrival(String personaId, String line) {
        if (line == null) {
            arrivalLines.remove(personaId);
        } else {
            arrivalLines.put(personaId, line);
        }
    }

    /** What it says to its owner as it joins them: a line kept for this arrival (sayOnArrival), else its own "join" line. */
    private String joinLine(Persona persona, int ownerId) {
        String kept = arrivalLines.remove(persona.id());
        if (kept != null) {
            return kept;
        }
        List<String> lines = world.arcs().lines(persona, "join");
        return lines.isEmpty() ? "here." : lines.get(Math.floorMod(ownerId, lines.size()));
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
        // an hour of wages is an hour of its upkeep at its camp (decision 19 b), never less than a meso
        long hoursMs = Math.round(meso / Math.max(1.0d, world.bench().upkeepPerHour(entry)) * 3_600_000.0d);
        long from = Math.max(System.currentTimeMillis(), entry.wagesPaidUntilMs());
        owner.gainMeso((int) -meso, true);
        RosterEntry paid = new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), entry.accruedExp(), entry.haulMeso(),
                from + hoursMs, entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(), entry.farmSecondsToday(), entry.farmDay(),
                entry.etaAtMs(), entry.holdMapId(), entry.parkedAtMs());
        write(paid);
        world.store().event(owner.getId(), persona.id(), "roster.wages", meso + " meso -> " + (hoursMs / 3_600_000L) + " h");
        return persona.name() + " is paid up for " + CompanionView.paidFor(paid.wagesPaidUntilMs() - System.currentTimeMillis()) + ".";
    }

    public String wagesStatus(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null) {
            return persona.name() + " is not in your roster.";
        }
        long left = entry.wagesPaidUntilMs() - System.currentTimeMillis();
        return persona.name() + (left > 0 ? " is paid up for " + CompanionView.paidFor(left) : " is working off the haul")
                + " (upkeep " + Math.max(1L, Math.round(world.bench().upkeepPerHour(entry))) + " meso/hour).";
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
        CompanionViews.invalidate(world, owner.getId(), persona.id()); // the bag emptied
        world.store().event(owner.getId(), persona.id(), "haul.handover", moved + " stacks, " + meso + " meso, skipped " + skipped);
        List<String> given = new ArrayList<>();
        if (meso > 0) {
            given.add(String.format(Locale.ROOT, "%,d meso", meso));
        }
        if (moved > 0) {
            given.add(stacks(moved));
        }
        String line = !given.isEmpty() ? persona.name() + " hands you " + String.join(" and ", given)
                : skipped > 0 ? persona.name() + " has nothing that fits in your bag" : persona.name() + " has nothing to hand over";
        return line + (skipped > 0 ? "; " + stacks(skipped) + (skipped == 1 ? " does" : " do") + " not fit in your bag." : ".");
    }

    /** "1 stack", "4 stacks". */
    private static String stacks(int count) {
        return count + (count == 1 ? " stack" : " stacks");
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
        CompanionViews.invalidate(world, owner.getId(), persona.id()); // the bag emptied
        world.store().event(owner.getId(), persona.id(), "haul.duey", haul.size() + " packages, " + meso + " meso");
        int packages = Math.max(1, haul.size()); // the meso alone go in one
        return persona.name() + " sends you " + packages + (packages == 1 ? " package" : " packages")
                + (meso > 0 ? String.format(Locale.ROOT, " with %,d meso", meso) : "") + " by Duey.";
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
        return persona.name() + String.format(Locale.ROOT, " banks %,d meso into your storage.", meso);
    }

    void saveHaulMeso(RosterEntry entry, long meso) {
        write(new RosterEntry(entry.characterId(), entry.personaId(), entry.state(), entry.assignment(),
                entry.assignmentItem(), entry.assignmentCount(), entry.campMapId(), entry.accruedExp(), meso,
                entry.wagesPaidUntilMs(), entry.recruitedAtMs(), entry.benchedAtMs(), entry.lastLedgerAtMs(),
                entry.farmSecondsToday(), entry.farmDay(), entry.etaAtMs(), entry.holdMapId(), entry.parkedAtMs()));
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
        write(entry);
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

    // ---- login / logout (decision 8a), the grace, boot and channel follow -------------------

    /**
     * The owner logged in (decision 8a). Companions moving over from the owner's channel change or Cash Shop visit
     * follow on their own ({@link CompanionChannelFollow}). PARKED ones within their grace ({@code
     * LEADER_OFFLINE_BENCH_MS}) log back in, and ACTIVE, TRAVELLING and HOLD rows with nothing loaded are restored: 2-8 s
     * apart, once the field has loaded, each walks in from the portal nearest the owner into the party it stayed in as
     * an offline member, or the owner's party now; a trip sets off again and a hold rejoins unless the run is still on.
     * Restored ones say no join line. PARKED ones past their grace go to their duty from the grace deadline (the ledger
     * credits them up to now), and when nobody comes back otherwise the usual lineup is called with travel
     * (join-then-travel), in the party they stayed in as offline members; one not called back leaves it then. Only a
     * login that ends a logout (a real one, a Cash Shop visit past the grace, or the boot) calls the usual lineup; a
     * channel change or a Cash Shop exit never does. After a logout one whisper sums up what the bench earned
     * meanwhile. A roster owner without the Adventurer's Roster gets one.
     */
    public void onOwnerLogin(Character owner) {
        int ownerId = owner.getId();
        boolean afterLogout = ownerLogoutAtMs.remove(String.valueOf(ownerId)) != null;
        goodbyeSaid.remove(ownerId);
        MapleMap here = owner.getMap();
        if (here != null && here.getEventInstance() == null) {
            lastOpenMap.put(ownerId, new Spot(here.getId(), owner.getPosition() == null ? null : new Point(owner.getPosition())));
        }
        List<RosterEntry> entries = roster(owner);
        if (!entries.isEmpty()) {
            grantRosterBook(owner);
        }
        Set<String> following = channelFollow.ownerArrived(owner);
        boolean restoring = !following.isEmpty();
        long now = System.currentTimeMillis();
        long graceMs = world.config().LEADER_OFFLINE_BENCH_MS;
        long delayMs = ArrivalPlacement.LOGIN_ARRIVAL_MS;
        List<RosterEntry> toDuty = new ArrayList<>();
        for (RosterEntry entry : inLineupOrder(ownerId, entries)) {
            RosterState state = entry.state();
            if (following.contains(entry.personaId())) {
                continue;
            }
            if (state == RosterState.PARKED && now - entry.parkedAtMs() >= graceMs) {
                benchParked(entry, graceMs, false); // no expel in front of the owner: the lineup call reuses its seat
                world.bench().accrueNow(current(entry)); // its duty since the deadline, before the lineup is called
                toDuty.add(entry);
                continue;
            }
            if (state == RosterState.ACTIVE || state == RosterState.TRAVELLING || state == RosterState.HOLD
                    || state == RosterState.PARKED) {
                restoring = true;
                Persona persona = world.content().persona(entry.personaId());
                if (persona != null && companion(entry) == null) {
                    spawnLater(ownerId, persona, state, delayMs, true);
                    delayMs += ArrivalPlacement.staggerMs();
                }
            }
        }
        Map<String, long[]> before = benchAtLogout.remove(ownerId);
        if (before != null) {
            // one whisper sums it up (flow 7 step 6): the call-back reports of those it named are spent, not told again
            BenchLedger ledger = world.bench();
            for (String personaId : summarizeBench(owner, before)) {
                if (ledger != null) {
                    ledger.takeReport(ownerId, personaId, 0);
                }
            }
        }
        Set<String> called = new HashSet<>();
        if (!restoring && (afterLogout || before != null)) {
            callUsual(owner, called);
        }
        for (RosterEntry entry : toDuty) {
            if (!called.contains(entry.personaId())) {
                leaveOfflineParty(current(entry)); // not coming back now: it leaves the party it stayed in
            }
        }
        parkedParty.remove(ownerId);
        tellAwayReleases(owner);
        tellCampMoves(owner);
    }

    /**
     * A friend back from RESIDENT_OWNER_INACTIVE_DAYS away hears once, in pink, who went home meanwhile (the allocation
     * sweep's roster.release "owner inactive"): "While you were away, Hadrik went home to Lith Harbor."
     */
    /**
     * Companions that left a camp too rough for them while their owner was offline (BenchDutyDispatcher) say so once,
     * in pink, at the login: "While you were away, Casse moved camp to Line 1 <Area 2>: the old one was too rough."
     */
    private void tellCampMoves(Character owner) {
        if (AgentPresence.isAgent(owner)) {
            return;
        }
        List<String> moved = world.store().untoldEvents(owner.getId(), BenchDutyDispatcher.CAMP_MOVED_EVENT, CAMP_TOO_ROUGH,
                CAMP_MOVES_TOLD_EVENT);
        if (moved.isEmpty()) {
            return;
        }
        world.store().event(owner.getId(), null, CAMP_MOVES_TOLD_EVENT, moved.size() + " moved while away");
        Set<String> said = new HashSet<>();
        for (String personaId : moved) {
            Persona persona = personaId == null ? null : world.content().persona(personaId);
            RosterEntry entry = persona == null ? null : world.store().rosterEntry(owner.getId(), persona.id());
            if (entry != null && said.add(persona.id())) {
                owner.dropMessage(5, "While you were away, " + persona.name() + " moved camp to "
                        + CompanionView.placeName(entry.campMapId()) + ": the old one was too rough.");
            }
        }
    }

    private void tellAwayReleases(Character owner) {
        if (AgentPresence.isAgent(owner)) {
            return;
        }
        List<String> gone = world.store().untoldEvents(owner.getId(), "roster.release", ResidentAllocation.AWAY_REASON,
                AWAY_TOLD_EVENT);
        if (gone.isEmpty()) {
            return;
        }
        world.store().event(owner.getId(), null, AWAY_TOLD_EVENT, gone.size() + " let go while away");
        List<Persona> went = new ArrayList<>();
        for (String personaId : gone) {
            Persona persona = personaId == null ? null : world.content().persona(personaId);
            if (persona != null && went.stream().noneMatch(known -> known.id().equals(persona.id()))) {
                went.add(persona);
            }
        }
        if (went.size() == 1) {
            owner.dropMessage(5, "While you were away, " + went.get(0).name() + " went home to "
                    + townName(went.get(0).homeTown()) + ".");
        } else if (!went.isEmpty()) {
            List<String> names = went.stream().map(Persona::name).toList();
            owner.dropMessage(5, "While you were away, " + String.join(", ", names.subList(0, names.size() - 1)) + " and "
                    + names.get(names.size() - 1) + " went home.");
        }
    }

    /**
     * True from the owner's logout (a real one, a Cash Shop visit past the grace, or the boot) until their next login has
     * restored or called their companions back: a bench the parking made is not theirs to answer for yet.
     */
    public boolean awaitingLogin(int ownerId) {
        return ownerLogoutAtMs.containsKey(String.valueOf(ownerId));
    }

    /** {@link #onOwnerLogout(Character, EventInstanceManager)} in the event instance the owner is in now. */
    public void onOwnerLogout(Character owner) {
        onOwnerLogout(owner, owner.getEventInstance());
    }

    /**
     * The owner logged off (decision 8a), on companion-ops. The old session of a channel change (or of the Cash Shop's
     * way out) is no logout: its companions follow ({@link CompanionChannelFollow}). Otherwise the usual lineup is
     * saved from the party order and one companion says goodbye in party chat. In the owner's own party (its only
     * human, CompanionDirectory.isCompanionParty) the companions out log off with them and stay in it as offline
     * members; the owner keeps the lead, since Party.successor gives it to no bot while a human is a member. In a party
     * with another human, even one the owner leads, they leave it at once, so the friend gets the seats back. Either way
     * each one out (ACTIVE; TRAVELLING, whose trip ends; HOLD) is PARKED from now, and goes to its duty once {@code
     * LEADER_OFFLINE_BENCH_MS} has passed ({@link #benchParkedPastGrace}). {@code instance}: the event instance the
     * owner was in, read before the disconnect unregistered them. Its companions committed to the run finish it for a
     * human of the party still in it (decision 8b, {@link InstanceLogoutHandoff}); the others leave with the owner under
     * stock eim rules, and their commitments end.
     */
    public void onOwnerLogout(Character owner, EventInstanceManager instance) {
        int ownerId = owner.getId();
        if (channelFollow.logoutIsTransition(ownerId)) {
            return;
        }
        channelFollow.forget(ownerId);
        ownerLogoutAtMs.put(String.valueOf(ownerId), System.currentTimeMillis());
        List<RosterEntry> rows = roster(owner);
        if (rows.isEmpty()) {
            return;
        }
        benchAtLogout.put(ownerId, progress(rows));
        saveUsualLineup(owner);
        List<RosterEntry> out = new ArrayList<>();
        for (RosterEntry entry : inLineupOrder(ownerId, rows)) {
            if (CompanionSeatState.partyMember(entry.state()) || entry.state() == RosterState.HOLD) {
                out.add(entry);
            }
        }
        Party party = owner.getParty();
        boolean ownParty = party != null && world.directory().isCompanionParty(party);
        Set<String> finishing = instance == null ? Set.of() : handoff.handOff(owner, instance, out);
        long now = System.currentTimeMillis();
        boolean saidGoodbye = false;
        for (RosterEntry entry : out) {
            if (finishing.contains(entry.personaId())) {
                continue;
            }
            Persona persona = world.content().persona(entry.personaId());
            Character bot = companion(entry);
            if (!saidGoodbye && persona != null && bot != null && CompanionSeatState.partyMember(entry.state())) {
                saidGoodbye = partyChat(bot, goodbye(persona, ownerId));
            }
            park(entry, ownParty && CompanionSeatState.partyMember(entry.state()), now, "owner offline");
        }
        if (saidGoodbye) {
            goodbyeSaid.add(ownerId);
        }
        if (ownParty) {
            parkedParty.put(ownerId, party.getId());
        }
        CompanionBossRuns bossRuns = world.bossRuns();
        if (bossRuns != null) {
            bossRuns.ownerLoggedOut(owner); // P7 Part B: their bench allies (BENCHED rows, so none of them is out)
        }
    }

    /**
     * One companion logs off with its owner and its row is PARKED from {@code nowMs}: it stays in its owner's party as
     * an offline member ({@code keepParty}), else it leaves any party it is in first. A seat held for it goes back and
     * a commitment it had ends.
     */
    private void park(RosterEntry entry, boolean keepParty, long nowMs, String reason) {
        world.slots().release(entry.personaId());
        releaseCommitment(entry.personaId());
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) { // its leave or log-off is the sync's echo
            unload(entry, reason, !keepParty);
        }
        if (write(current(entry).withPark(nowMs))) {
            world.store().event(entry.characterId(), entry.personaId(), "companion.parked",
                    reason + (keepParty ? ": an offline member of its owner's party" : ": out of the party"));
        }
    }

    /** The companion's commitment to a run ends: it left the run. */
    private void releaseCommitment(String personaId) {
        Commitments commitments = world.commitments();
        int characterId = world.residents().characterIdOf(personaId);
        if (commitments != null && characterId != 0) {
            commitments.releaseMember(characterId);
        }
    }

    /** What a companion says in party chat as its owner logs off: its own "logout" line, else {@link #GOODBYE_LINE}. */
    private String goodbye(Persona persona, int ownerId) {
        List<String> lines = world.arcs().lines(persona, "logout");
        return lines.isEmpty() ? GOODBYE_LINE : lines.get(Math.floorMod(ownerId, lines.size()));
    }

    /** The companion says {@code line} in its party's chat; false when it could not. */
    private static boolean partyChat(Character bot, String line) {
        try {
            return AgentPartyGatewayRuntime.party().sendPartyChat(bot, line);
        } catch (RuntimeException failure) {
            log.debug("party chat failed for {}", bot.getName(), failure);
            return false;
        }
    }

    /** The companion says {@code line} in its party's chat (a party quest's lines); false when it could not. */
    public boolean sayInParty(Character bot, String line) {
        return partyChat(bot, line);
    }

    /** Each row's banked EXP and haul meso now, which the login whisper compares with. */
    private static Map<String, long[]> progress(List<RosterEntry> rows) {
        Map<String, long[]> out = new HashMap<>();
        for (RosterEntry entry : rows) {
            out.put(entry.personaId(), new long[] {entry.accruedExp(), entry.haulMeso()});
        }
        return out;
    }

    /**
     * The whisper to an owner back from a logout: what each companion on the bench earned meanwhile, its banked EXP (as
     * a share of its level, as the Crew window shows it) and haul meso gained since {@code before} (the rows at the
     * logout, or at the boot; a row missing there counts from nothing). The first one says it, of itself as "me"; the
     * first {@link #SUMMARY_NAMES} are named and the rest counted. Nothing earned, no whisper. The persona ids it told
     * of (named or counted).
     */
    private Set<String> summarizeBench(Character owner, Map<String, long[]> before) {
        Set<String> told = new HashSet<>();
        List<String> earned = new ArrayList<>();
        Persona speaker = null;
        int others = 0;
        for (RosterEntry entry : inLineupOrder(owner.getId(), roster(owner))) {
            Persona persona = BenchSessions.benchGoverned(entry) ? world.content().persona(entry.personaId()) : null;
            long[] was = before.getOrDefault(entry.personaId(), new long[2]);
            long exp = entry.accruedExp() - was[0];
            long meso = entry.haulMeso() - was[1];
            if (persona == null || exp <= 0L && meso <= 0L) {
                continue;
            }
            speaker = speaker == null ? persona : speaker;
            told.add(persona.id());
            if (earned.size() >= SUMMARY_NAMES) {
                others++;
                continue;
            }
            int percent = CompanionView.bankedPercent(exp, world.store().characterLevel(world.residents().characterIdOf(persona.id())));
            earned.add((persona == speaker ? "me" : persona.name())
                    + (exp <= 0L ? "" : percent > 0 ? " +" + percent + "% exp" : " +<1% exp")
                    + (meso > 0L ? String.format(Locale.ROOT, " +%,d meso", meso) : ""));
        }
        if (others > 0) {
            earned.add("and " + others + (others == 1 ? " other" : " others"));
        }
        if (speaker != null) {
            world.residents().whisper(speaker, owner, "while you were away: " + String.join(", ", earned) + ".");
        }
        return told;
    }

    /**
     * Remembers the usual lineup (plan "Usual lineup", lineup_slot): the owner's companions out, in their party window's
     * order, then those out of it (waiting outside a run, or parked: logged off with the owner and not back yet) in
     * their old order, 5 at most; the owner's other rows lose their place. Saved at logout (decision 8a); P3's
     * "Remember this lineup" calls it too.
     */
    public void saveUsualLineup(Character owner) {
        saveLineup(owner.getId(), lineupOrder(owner));
    }

    /**
     * Who is out with the owner as a lineup orders them (the usual one, a named one): those in the party window in its
     * order, then those waiting outside a run or parked, in their old usual-lineup order.
     */
    List<String> lineupOrder(Character owner) {
        List<RosterEntry> out = new ArrayList<>();
        Map<Integer, String> byCharacter = new HashMap<>();
        for (RosterEntry entry : inLineupOrder(owner.getId(), roster(owner))) {
            if (CompanionSeatState.partyMember(entry.state()) || entry.state() == RosterState.HOLD
                    || entry.state() == RosterState.PARKED) {
                out.add(entry);
                byCharacter.put(world.residents().characterIdOf(entry.personaId()), entry.personaId());
            }
        }
        List<String> order = new ArrayList<>();
        Party party = owner.getParty();
        if (party != null) {
            for (PartyCharacter member : party.getMembers()) {
                String personaId = byCharacter.get(member.getId());
                if (personaId != null && !order.contains(personaId)) {
                    order.add(personaId);
                }
            }
        }
        for (RosterEntry entry : out) {
            if (!order.contains(entry.personaId())) {
                order.add(entry.personaId());
            }
        }
        return order;
    }

    /** Writes the usual lineup: places 1.. in {@code order} (5 at most) and none for the owner's other rows. */
    private void saveLineup(int ownerId, List<String> order) {
        Map<String, Integer> was = world.store().lineupSlots(ownerId);
        Set<String> placed = new HashSet<>();
        for (String personaId : order) {
            if (placed.size() >= PARTY_CAP - 1) {
                break;
            }
            if (placed.add(personaId) && !Integer.valueOf(placed.size()).equals(was.get(personaId))) {
                world.store().saveLineupSlot(ownerId, personaId, placed.size());
            }
        }
        for (String personaId : was.keySet()) {
            if (!placed.contains(personaId)) {
                world.store().saveLineupSlot(ownerId, personaId, null);
            }
        }
        CompanionViews.invalidate(world, ownerId); // the Crew window reads the lineup from the view cache
    }

    /** The rows in usual-lineup order; those without a place come last, as they were. */
    private List<RosterEntry> inLineupOrder(int ownerId, List<RosterEntry> rows) {
        Map<String, Integer> lineup = world.store().lineupSlots(ownerId);
        List<RosterEntry> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(entry -> lineup.getOrDefault(entry.personaId(), Integer.MAX_VALUE)));
        return sorted;
    }

    /** Who of the usual lineup came ({@code called}: seated or on its way) and who did not, with why, in lineup order. */
    public record UsualCall(List<Persona> called, Map<Persona, RosterResult> refused) { }

    /**
     * Calls the usual lineup (plan "Usual lineup"): each benched companion with a place in it, in lineup order, joins at
     * once and travels over (join-then-travel) while the party has room. P3's "call the usual", which says what came of
     * it in one line; one refused for want of a seat comes back by itself once there is room (returnWhenRoom).
     */
    public UsualCall callUsual(Character owner) {
        UsualCall call = new UsualCall(new ArrayList<>(), new LinkedHashMap<>());
        Map<String, Integer> lineup = world.store().lineupSlots(owner.getId());
        for (RosterEntry entry : inLineupOrder(owner.getId(), roster(owner))) {
            Persona persona = entry.state() == RosterState.BENCHED && lineup.containsKey(entry.personaId())
                    ? world.content().persona(entry.personaId()) : null;
            if (persona == null) {
                continue;
            }
            RosterResult result = call(owner, persona);
            if (result.ok()) {
                call.called().add(persona);
                continue;
            }
            call.refused().put(persona, result);
            if (result instanceof RosterResult.PartyFull || result instanceof RosterResult.LeaderCapped) {
                world.slots().waitForRoom(persona.id(), 0);
            }
        }
        return call;
    }

    /**
     * {@link #callUsual(Character)} at login (decision 8a, after the grace): each refusal is printed; {@code called} gets
     * each one seated or on its way.
     */
    private void callUsual(Character owner, Set<String> called) {
        UsualCall call = callUsual(owner);
        call.called().forEach(persona -> called.add(persona.id()));
        call.refused().forEach((persona, result) -> owner.dropMessage(5, RosterVoice.line(persona, result)));
    }

    /**
     * Bench duty for companions whose owner stays away (decision 8a; on companion-ops every minute): each PARKED row of
     * an offline owner goes to its duty once {@code LEADER_OFFLINE_BENCH_MS} has passed since it parked. An owner who is
     * online again (or in the Cash Shop) is left to the login path.
     */
    public void benchParkedPastGrace() {
        long now = System.currentTimeMillis();
        long graceMs = world.config().LEADER_OFFLINE_BENCH_MS;
        Set<Integer> stillParked = new HashSet<>();
        for (RosterEntry entry : world.store().allInTransit()) {
            if (entry.state() != RosterState.PARKED) {
                continue;
            }
            if (now - entry.parkedAtMs() >= graceMs && onlineCharacter(entry.characterId()) == null) {
                benchParked(entry, graceMs, true);
            } else {
                stillParked.add(entry.characterId());
            }
        }
        parkedParty.keySet().retainAll(stillParked);
    }

    /**
     * A PARKED companion whose grace ran out goes to its duty (decision 8a): it leaves the owner's party it stayed in as
     * an offline member (expelled under the roster's cause, which the sync takes as an echo; not {@code leaveParty} at
     * its owner's login, whose lineup call takes the seat back), and its row is BENCHED from the grace deadline, so the
     * ledger credits its duty from then on and never the time it spent parked.
     */
    private void benchParked(RosterEntry entry, long graceMs, boolean leaveParty) {
        if (leaveParty) {
            leaveOfflineParty(entry);
        }
        world.slots().release(entry.personaId());
        if (write(entry.with(RosterState.BENCHED, entry.parkedAtMs() + graceMs))) {
            world.store().event(entry.characterId(), entry.personaId(), "companion.parked_bench", entry.assignment().name());
        }
    }

    /**
     * A companion still an offline member of its owner's party leaves it: the party the owner is in now while they are
     * online (the login no longer knows the parked one), else the own party they logged off in.
     */
    private void leaveOfflineParty(RosterEntry entry) {
        int characterId = world.residents().characterIdOf(entry.personaId());
        if (characterId == 0) {
            return;
        }
        Integer partyId = parkedParty.get(entry.characterId());
        try {
            net.server.world.World game = Server.getInstance().getWorld(world.config().WORLD);
            Character owner = onlineCharacter(entry.characterId());
            Party party = owner != null && owner.getParty() != null ? owner.getParty()
                    : partyId == null || game == null ? null : game.getParty(partyId);
            PartyCharacter member = party == null ? null : party.getMemberById(characterId);
            if (member != null && !member.isOnline()) {
                try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
                    game.updateParty(party.getId(), PartyOperation.EXPEL, member);
                }
            }
        } catch (RuntimeException failure) {
            log.debug("{} could not leave party {}", entry.personaId(), partyId, failure);
        }
    }

    /**
     * Boot reconciliation (decision 8a), on companion-ops before any login: nobody is online and no party survived the
     * restart, so every companion that was out (ACTIVE, TRAVELLING, HOLD) is PARKED from now, those become the usual
     * lineup in their old order, and a PARKED one whose grace had run out goes to its duty from its deadline. Every
     * owner's next login is then one after a logout, and its whisper sums up what the bench earned since the restart.
     */
    public void reconcileAtBoot() {
        long now = System.currentTimeMillis();
        long graceMs = world.config().LEADER_OFFLINE_BENCH_MS;
        List<RosterEntry> rows = new ArrayList<>(world.store().allActive());
        rows.addAll(world.store().allInTransit());
        Map<Integer, List<String>> parked = new LinkedHashMap<>();
        for (RosterEntry entry : rows) {
            switch (entry.state()) {
                case ACTIVE, TRAVELLING, HOLD -> {
                    world.slots().release(entry.personaId()); // the HOLD seat rebuilt at construction
                    if (write(entry.withPark(now))) {
                        parked.computeIfAbsent(entry.characterId(), id -> new ArrayList<>()).add(entry.personaId());
                        world.store().event(entry.characterId(), entry.personaId(), "companion.parked", "server restart");
                    }
                }
                case PARKED -> {
                    if (now - entry.parkedAtMs() >= graceMs) {
                        benchParked(entry, graceMs, true);
                    }
                }
                default -> { }
            }
        }
        for (int ownerId : owners) { // a restart logged everyone off
            ownerLogoutAtMs.putIfAbsent(String.valueOf(ownerId), now);
            benchAtLogout.computeIfAbsent(ownerId, id -> progress(world.store().roster(id)));
        }
        parked.forEach((ownerId, personaIds) -> {
            Map<String, Integer> was = world.store().lineupSlots(ownerId);
            List<String> order = new ArrayList<>(personaIds);
            order.sort(Comparator.comparingInt(id -> was.getOrDefault(id, Integer.MAX_VALUE)));
            saveLineup(ownerId, order);
        });
    }

    /**
     * A companion that finished a run for a friend of its offline owner (decision 8b; its commitment ended) leaves that
     * party and parks from now; the first of them says the goodbye nobody said at the logout. With its owner online
     * again meanwhile it logs back in to them at once; with its owner's character deleted meanwhile it goes home.
     */
    void parkAfterRun(int ownerId, int characterId) {
        Persona persona = world.residents().personaOfCharacterId(characterId);
        RosterEntry entry = persona == null ? null : world.store().rosterEntry(ownerId, persona.id());
        if (entry == null || !CompanionSeatState.partyMember(entry.state())) {
            return;
        }
        if (releasedAfterRun.contains(ownerId)) {
            release(ownerId, persona, "owner deleted");
            if (!persona.id().startsWith(HIRE_PREFIX)) {
                world.store().event(ownerId, persona.id(), "resident.released", "owner deleted");
            }
            if (world.store().roster(ownerId).isEmpty()) {
                releasedAfterRun.remove(ownerId);
            }
            return;
        }
        Character bot = companion(entry);
        boolean away = onlineCharacter(ownerId) == null;
        if (away && bot != null && !goodbyeSaid.contains(ownerId) && partyChat(bot, goodbye(persona, ownerId))) {
            goodbyeSaid.add(ownerId);
        }
        park(entry, false, System.currentTimeMillis(), "finished the run for its owner's party");
        if (!away) {
            spawnLater(ownerId, persona, RosterState.PARKED, ArrivalPlacement.LOGIN_ARRIVAL_MS, true);
        }
    }

    /** Installs who agrees to a companion finishing a run for a friend (decision 8b): P6's companion runs. */
    public void installInstanceDelegation(InstanceLogoutHandoff.Delegation delegation) {
        handoff.install(delegation);
    }

    /**
     * LivingWorldHooks.playerChangingChannel: the owner is about to change channel, or to leave the Cash Shop (a change
     * to the same channel). Memory only here ({@link CompanionChannelFollow}).
     */
    public void ownerChangingChannel(Character owner, int channel) {
        if (owners.contains(owner.getId())) {
            channelFollow.changing(owner, channel);
        }
    }

    /** LivingWorldHooks.playerEnteredCashShop: companions idle where they are; a visit past the grace logs them off. */
    public void ownerEnteredCashShop(Character owner) {
        if (owners.contains(owner.getId())) {
            channelFollow.enteredCashShop(owner);
        }
    }

    /**
     * Channel follow (plan flow 6), on companion-ops: the companion out with its owner (ACTIVE, or TRAVELLING toward
     * them) moves to the channel they changed to, as a player changing channel does. It logs off on the old channel,
     * staying in the party as an offline member meanwhile, then logs in on the owner's channel: an ACTIVE one at the
     * portal nearest the owner, walking over and only then following; a TRAVELLING one where it stood, still on its way
     * (an arrival already due comes in right after). It is a member already, so the party window shows its new channel.
     * Its load is budgeted as one that replaces itself. True when it is on its owner's channel now or nothing is left to
     * move (it was benched or parked meanwhile); false leaves it to {@link #followChannelFallback}.
     */
    boolean followChannel(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || !CompanionSeatState.partyMember(entry.state())) {
            return true;
        }
        Character bot = companion(entry);
        if (bot == null) {
            return false;
        }
        if (sameChannel(owner, bot)) { // a Cash Shop visit, or it made it already
            if (entry.state() == RosterState.TRAVELLING && entry.etaAtMs() <= System.currentTimeMillis()) {
                int ownerId = owner.getId();
                later("roster.arrive " + persona.id(), () -> arrive(ownerId, persona, entry.etaAtMs()), 0L);
            }
            return true;
        }
        boolean travelling = entry.state() == RosterState.TRAVELLING;
        MapleMap map = travelling ? channelMap(owner, bot.getMapId()) : owner.getMap();
        Point stood = bot.getPosition() == null ? null : new Point(bot.getPosition());
        Point at = !travelling ? ArrivalPlacement.nearestPortal(map, owner.getPosition())
                : stood != null ? stood : ArrivalPlacement.portalNear(map, null);
        if (at == null && !travelling) {
            at = owner.getPosition(); // no usable portal on the owner's map: beside them, as P1.5
        }
        if (map == null || at == null || !world.budget().admitReplacing(CompanionBudget.Kind.COMPANION, owner).allowed()) {
            return false;
        }
        int characterId = bot.getId();
        Character moved;
        // its log-off can take its row off the party list (seen live), so a seat is held for the join; none is free
        // at 6/6, where its row, still listed, is its seat
        boolean seatHeld = holdSeat(owner, persona) != null;
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            unload(entry, "follows its owner to another channel", false);
            world.leases().tryAcquire(characterId, CompanionLeases.Kind.COMPANION, owner.getId());
            AgentLifecycleService.AgentSpawnResult result = spawnAt(owner, persona, map, at);
            if (result == null || !result.success() || result.agent() == null) {
                world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
                log.warn("channel follow {} for {} failed: {}", persona.id(), owner.getName(),
                        result == null ? "no result" : result.errorMessage());
                return false;
            }
            moved = result.agent();
            JoinOutcome joined = joinOwner(owner, moved);
            if (!joinedOk(joined)) {
                despawn(entry, "channel follow join " + joined);
                return false;
            }
        } finally {
            if (seatHeld) {
                world.slots().release(persona.id(), CompanionSlots.Kind.TRAVEL); // a member again, or the fallback's to seat
            }
        }
        settings.apply(owner, persona, moved);
        if (!travelling) {
            walkToOwner(persona, moved);
        } else if (entry.etaAtMs() <= System.currentTimeMillis()) {
            int ownerId = owner.getId();
            later("roster.arrive " + persona.id(), () -> arrive(ownerId, persona, entry.etaAtMs()), 0L);
        }
        Client client = owner.getClient();
        world.store().event(owner.getId(), persona.id(), "companion.channel_follow",
                "channel " + (client == null ? "?" : String.valueOf(client.getChannel())));
        return true;
    }

    /**
     * Channel follow's fallback, {@link CompanionChannelFollow#FALLBACK_MS} after its owner logged in on the new channel:
     * a companion out with them and still not there is unloaded wherever it is and seated on their channel the way a
     * call does it, on the seat it had (no seat check: it was out with them), without a second word (an ACTIVE one
     * walks in from the portal nearest the owner; a TRAVELLING one sets off again for the time it had left, its ETA
     * whispered already, or arrives when that is up).
     */
    void followChannelFallback(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || !CompanionSeatState.partyMember(entry.state())) {
            return;
        }
        Character bot = companion(entry);
        if (bot != null && sameChannel(owner, bot)) {
            return;
        }
        despawn(entry, "channel follow fallback");
        long left = entry.etaAtMs() - System.currentTimeMillis();
        RosterResult result = entry.state() == RosterState.TRAVELLING && left > 0L
                ? travel(owner, persona, entry, campOf(entry, persona), left, true)
                : spawnCompanion(owner, persona, entry, null, entry.state() == RosterState.ACTIVE);
        if (!result.ok()) {
            owner.dropMessage(5, RosterVoice.line(persona, result));
        }
    }

    // ---- hold and rejoin (plan "Seat states": HOLD) ----------------------------------------

    /**
     * HOLD: the companion waits outside a run at {@code point} on {@code mapId} on the owner's channel (null: where
     * it stands when it is on that map already, else at the portal nearest the spot its owner came into that map by).
     * It stops following, leaves the party, keeps its seat as a HOLD reservation (taken first, so the seat is never up
     * for grabs), refuses every party invite, and comes back through {@link #rejoinHolds}. It must be out with its owner
     * (ACTIVE or TRAVELLING, loaded). One left behind on another channel is never moved across: it logs off there and
     * waits unloaded, and its rejoin sets off from {@code mapId} on its owner's channel.
     */
    public RosterResult hold(Character owner, Persona persona, int mapId, Point point) {
        return hold(owner, persona, mapId, point, null);
    }

    /**
     * {@link #hold(Character, Persona, int, Point)} saying {@code note} (null: the usual whisper to its owner). A party
     * line is said before it leaves the party, which it cannot party-chat outside of; a map line once it stands where
     * it waits. One it cannot say so (the companion left behind on another channel, or bots' chat turned off) is
     * whispered to the owner instead.
     */
    public RosterResult hold(Character owner, Persona persona, int mapId, Point point, HoldNote note) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return new RosterResult.NotOwned();
        }
        if (entry.state() == RosterState.HOLD) {
            return new RosterResult.Ok(persona.name() + " is waiting already.");
        }
        Character bot = companion(entry);
        MapleMap map = channelMap(owner, mapId);
        if (bot == null || !CompanionSeatState.partyMember(entry.state()) || map == null) {
            return new RosterResult.Failed(persona.name() + " is not out with you right now.");
        }
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        // gone to the store for potions: it finishes the trip and walks back to the spot afterwards
        // (CompanionRestock.afterRestock); stopping or moving it now would cut the trip short
        boolean restocking = runtime != null && world.config().COMPANION_RESTOCK_ENABLED
                && AgentSupplyReadiness.status(runtime, System.currentTimeMillis()).restocking();
        if (runtime != null && !restocking) {
            AgentMovementCommandRuntime.stop(runtime); // no follow warps it after its owner meanwhile
        }
        world.slots().reserve(owner.getId(), persona.id(), CompanionSlots.Kind.HOLD, Long.MAX_VALUE);
        String line = holdLine(persona, owner.getId(), note);
        boolean elsewhere = !sameChannel(owner, bot);
        boolean said = false;
        if (elsewhere) {
            despawn(entry, "waits outside the run from another channel");
            runtime = null;
        } else {
            said = note != null && note.voice() == HoldNote.Voice.PARTY && partyChat(bot, line);
            leaveParty(bot, entry);
        }
        if (!elsewhere && !restocking && bot.getMap() != map) {
            Spot spot = lastOpenMap.get(owner.getId());
            Point near = point != null ? point : spot != null && spot.mapId() == mapId ? spot.at() : null;
            Point portal = ArrivalPlacement.portalNear(map, near);
            Point to = portal != null ? portal : near;
            if (to != null) {
                bot.changeMap(map, to);
            } else {
                bot.changeMap(map, map.getPortal(0));
            }
        }
        if (runtime != null && point != null && !restocking) {
            AgentMovementCommandRuntime.moveTo(runtime, point, false);
        }
        write(current(entry).withHold(mapId, System.currentTimeMillis()));
        holdSpots.put(persona.id(), new HoldSpot(mapId, point == null ? null : new Point(point)));
        if (!elsewhere && note != null && note.voice() == HoldNote.Voice.MAP
                && AgentYamlConfig.config.agent.AGENT_DIALOGUE_TRANSPORT_ENABLED) { // off, sayMapNow says nothing
            AgentDialogueTransportRuntime.sayMapNow(bot, line); // a recruited companion has no Resident session to say it
            said = true;
        }
        if (!said) {
            world.residents().whisper(persona, owner, line);
        }
        world.store().event(owner.getId(), persona.id(), "companion.hold", "map " + mapId);
        return new RosterResult.Ok(persona.name() + " waits at map " + mapId + ".");
    }

    /** Where a held companion waits: {@code mapId}, and {@code point} on it (null: where it stood). */
    record HoldSpot(int mapId, Point point) { }

    /** Where the companion's HOLD waits, from memory; null when it is not held (or was held before this boot). */
    HoldSpot holdSpot(String personaId) {
        return personaId == null ? null : holdSpots.get(personaId);
    }

    /** What a companion says as it waits outside a run: the note's line, else its arc's "hold" line, else the usual one. */
    private String holdLine(Persona persona, int ownerId, HoldNote note) {
        if (note != null && note.line() != null) {
            return note.line();
        }
        List<String> lines = world.arcs().lines(persona, "hold");
        return lines.isEmpty() ? "i'll wait out here till you're done." : lines.get(Math.floorMod(ownerId, lines.size()));
    }

    /**
     * The companion steps out of the run it is in to wait outside it on {@code mapId} (a tap-out, on companion-ops).
     * With its owner online on its channel that is a {@link #hold}. Otherwise it only stops, says the note in party
     * chat and leaves the party, and its row is not written. With its owner gone (it finishes the run for a friend,
     * decision 8b) it parks when its commitment ends (InstanceLogoutHandoff). With its owner on another channel (they
     * walked out of the run and changed channel; a committed companion stays on the run's) a hold would despawn it,
     * and removing its runtime fails the run it is still a member of: the auditor seats it again once the run lets go.
     */
    public RosterResult waitOutside(int ownerId, Persona persona, int mapId, HoldNote note) {
        Character owner = onlineCharacter(ownerId);
        RosterEntry entry = world.store().rosterEntry(ownerId, persona.id());
        Character bot = entry == null ? null : companion(entry);
        if (owner != null && (bot == null || sameChannel(owner, bot))) {
            return hold(owner, persona, mapId, null, note);
        }
        if (bot == null) {
            return new RosterResult.Failed(persona.name() + " is not out right now.");
        }
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        if (runtime != null) {
            AgentMovementCommandRuntime.stop(runtime);
        }
        partyChat(bot, holdLine(persona, ownerId, note));
        leaveParty(bot, entry);
        return new RosterResult.Ok(persona.name() + " waits at map " + mapId + ".");
    }

    /**
     * The run is over (the owner is out of the event instance, or back on the map their companions wait on): every
     * HOLD companion no commitment holds rejoins the owner's party in usual-lineup order, and walks over (same map) or
     * travels to them (another map). On companion-ops.
     */
    public void rejoinHolds(Character owner) {
        if (owner.getEventInstance() != null) {
            return;
        }
        Map<String, Integer> lineup = world.store().lineupSlots(owner.getId());
        List<RosterEntry> rows = new ArrayList<>(roster(owner));
        rows.sort(Comparator.comparingInt(entry -> lineup.getOrDefault(entry.personaId(), Integer.MAX_VALUE)));
        for (RosterEntry entry : rows) {
            Persona persona = entry.state() == RosterState.HOLD ? world.content().persona(entry.personaId()) : null;
            if (persona != null && !committed(persona.id())) {
                RosterResult result = rejoinHold(owner, persona, entry);
                if (!result.ok()) {
                    owner.dropMessage(5, RosterVoice.line(persona, result));
                }
            }
        }
    }

    /**
     * One HOLD companion rejoins on its HOLD seat. Loaded on the owner's channel: it joins at once and walks over from
     * where it waits (same map) or travels (another map). Unloaded (its owner logged off meanwhile) or on another
     * channel: it sets off from the map it waited on, as a call does. A party with no room for it any more: it goes to
     * its duty and comes back by itself once there is room.
     */
    private RosterResult rejoinHold(Character owner, Persona persona, RosterEntry entry) {
        Character bot = companion(entry);
        if (bot == null || !sameChannel(owner, bot)) {
            if (bot != null) {
                despawn(entry, "rejoin from another channel");
            }
            long eta = recallEtaMs(entry.holdMapId(), owner.getMapId());
            return eta > 0L ? travel(owner, persona, entry, entry.holdMapId(), eta) : spawnCompanion(owner, persona, entry, null);
        }
        if (owner.getParty() == null) {
            Party.createParty(owner, true, true); // decision 9 (a), as for any call
        }
        JoinOutcome joined;
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            joined = joinOwner(owner, bot); // on its HOLD seat: the seat gate admits the roster's own rejoin only
        }
        if (!joinedOk(joined)) {
            RosterResult benched = standDown(owner, persona, PARTY_FULL_LINE, "companion.overflow");
            world.slots().waitForRoom(persona.id(), 0);
            return benched;
        }
        world.slots().release(persona.id()); // a member now
        settings.apply(owner, persona, bot);
        world.store().event(owner.getId(), persona.id(), "companion.rejoin", "from map " + bot.getMapId());
        AgentRuntimeEntry runtime = AgentRuntimeRegistry.findByAgentCharacterId(bot.getId());
        if (world.config().COMPANION_RESTOCK_ENABLED && runtime != null && runtime.capabilityStates()
                .find(AgentSupplyProcurementState.STATE_KEY).map(AgentSupplyProcurementState::isActive).orElse(false)) {
            // still on a supply trip (it restocked while its owner was inside): the trip ends by following them,
            // a follow warp, instead of a walk back here; following now would cancel its shop visit
            offFollow.remove(persona.id());
            if (AgentSupplyReadiness.resumeFollowAfterTrip(runtime)) {
                write(current(entry).with(RosterState.ACTIVE, System.currentTimeMillis()));
                return new RosterResult.Ok(persona.name() + " rejoins your party.");
            }
            // the trip ended in the meantime, before it could follow from it: it rejoins as any held companion does
        }
        if (bot.getMap() != owner.getMap()) {
            return onItsWay(owner, persona, entry, bot.getMapId(), recallEtaMs(bot.getMapId(), owner.getMapId()));
        }
        walkToOwner(persona, bot);
        write(current(entry).with(RosterState.ACTIVE, System.currentTimeMillis()));
        return new RosterResult.Ok(persona.name() + " rejoins your party.");
    }

    /** As {@link #ownerEnteredMap(Character, MapleMap, Point)}, for an owner already standing where they came in. */
    public void ownerEnteredMap(Character owner, MapleMap map) {
        ownerEnteredMap(owner, map, owner.getPosition());
    }

    /**
     * The owner came into {@code map} at {@code at} (LivingWorldHooks.mapEntered, on the thread that moves them, before
     * they stand there: memory only). Their following companions come in after them there (portal-true follow). An
     * open map, not an event instance's copy, is remembered as the way into their next run; coming into one, their
     * companions waiting outside a run rejoin them, on companion-ops, once the party changes that waited for the run
     * are reconciled (a friend who joined meanwhile may have shrunk their share: a hold past it goes to its duty).
     */
    public void ownerEnteredMap(Character owner, MapleMap map, Point at) {
        if (!owners.contains(owner.getId())) {
            return;
        }
        portalFollow.entered(owner.getId(), map.getId(), at);
        if (world.config().COMPANION_RESTOCK_ENABLED && world.config().companionRestockBossMap(map.getId())) {
            int ownerId = owner.getId();
            int mapId = map.getId();
            world.executor().submit("companion.restock.boss_gate " + ownerId, () -> {
                CompanionRestock restock = world.restock();
                if (restock != null) {
                    restock.bossGate(ownerId, mapId); // a companion short of potions says there is no shop here
                }
            });
        }
        CompanionBossRuns bossRuns = world.bossRuns();
        if (bossRuns != null && bossRuns.restockKeyAt(map.getId()) != null) {
            int ownerId = owner.getId();
            int mapId = map.getId();
            // a boss's entrance (Mu Young's room): companions short of its standard look at their potions now (P7)
            world.executor().submit("boss.owner_arrived " + ownerId, () -> bossRuns.ownerArrived(ownerId, mapId));
        }
        if (bossRuns != null && bossRuns.approachKeyAt(map.getId()) != null) {
            int ownerId = owner.getId();
            int mapId = map.getId();
            // a boss's approach with a store (Ant Tunnel Park on the way down to Easy Balrog): companions short of its
            // standard look at their potions now, and shop there before the party walks on (fix L7)
            world.executor().submit("boss.owner_approached " + ownerId,
                    () -> bossRuns.ownerApproached(ownerId, mapId));
        }
        if (map.getEventInstance() != null) {
            return;
        }
        lastOpenMap.put(owner.getId(), new Spot(map.getId(), at == null ? null : new Point(at)));
        boolean holding = false;
        for (CompanionSlots.Reservation held : world.slots().reservations(owner.getId())) {
            holding |= held.kind() == CompanionSlots.Kind.HOLD;
        }
        if (holding) {
            int ownerId = owner.getId();
            world.executor().submit("roster.rejoin_holds " + ownerId, () -> {
                CompanionPartySync sync = world.partySync();
                if (sync != null) {
                    sync.resumeDeferred();
                }
                Character now = onlineCharacter(ownerId);
                if (now != null) {
                    rejoinHolds(now);
                }
            });
        }
    }

    // ---- auditor repairs (CompanionAuditor, on companion-ops; they never write the row) ---------

    /**
     * An ACTIVE row whose companion is not loaded, not in the owner's party, not led by the owner or on another
     * channel. One that follows the owner on their map and channel and is only out of the party rejoins where it
     * stands (no pop-out). Anything else: whatever holds it lets go, and it is seated again the way login restores
     * it, in the owner's party now. One committed to a run is the run's until it ends.
     */
    public void reseat(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || entry.state() != RosterState.ACTIVE || inFlight(owner.getId(), persona.id())
                || committed(persona.id())) {
            return;
        }
        Character bot = companion(entry);
        if (bot != null && owner.getMap() != null && bot.getMap() == owner.getMap() && bot.getClient() != null
                && owner.getClient() != null && bot.getClient().getChannel() == owner.getClient().getChannel()
                && AgentRuntimeRegistry.leaderIdForAgentCharacter(bot.getId()) == owner.getId()
                && !sameParty(owner, bot) && canSeat(owner, entry)) {
            if (rejoin(owner, entry, bot)) {
                settings.apply(owner, persona, bot);
            }
            return;
        }
        despawn(entry, "audit: reseat");
        if (canSeat(owner, entry)) { // with no room the row waits: the sync recalls it once its owner has room
            spawnCompanion(owner, persona, entry, null);
        }
    }

    /**
     * The companion out with its owner joins the owner's party again on a seat held for the join (the auditor's repair
     * where it stands, or a traveller whose owner's party changed on the way). False when it could not.
     */
    private boolean rejoin(Character owner, RosterEntry entry, Character bot) {
        if (owner.getParty() == null && !Party.createParty(owner, true, true)) {
            return false;
        }
        if (!world.slots().reserve(owner.getId(), entry.personaId(), CompanionSlots.Kind.TRAVEL,
                System.currentTimeMillis() + CompanionSlots.SEAT_HOLD_MS)) {
            return false;
        }
        if (bot.getParty() != null) {
            leaveParty(bot, entry);
        }
        JoinOutcome joined = joinOwner(owner, bot);
        if (!joinedOk(joined)) {
            world.slots().release(entry.personaId(), CompanionSlots.Kind.TRAVEL);
            return false;
        }
        return true;
    }

    /**
     * A BENCHED or AWAY row whose companion is still in the owner's party or still led by the owner: it leaves the
     * party, and is unloaded unless something else (a bench slice) leads it. An offline member is expelled. One
     * committed to a run is the run's until it ends.
     */
    public void unseat(Character owner, Persona persona) {
        RosterEntry entry = world.store().rosterEntry(owner.getId(), persona.id());
        if (entry == null || !BenchSessions.benchGoverned(entry) || inFlight(owner.getId(), persona.id())
                || committed(persona.id())) {
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
        unload(entry, reason, true);
    }

    /**
     * Saves the companion and takes it off the server as a player logging off, with its COMPANION lease. {@code
     * leaveParty}: it leaves its party first; else it stays in it as an offline member (the stock log-off, under the
     * caller's cause token). The lease goes only once it has logged off, whose save is in the store then (as
     * ResidentRuntime.standby and BenchDutyDispatcher.unload): a released companion is nobody's while it walks out,
     * so a Resident tick or a citizen session could load it in between from a store that still holds its old kit. A
     * log-off that fails still gives the lease back.
     */
    private void unload(RosterEntry entry, String reason, boolean leaveParty) {
        leaving.remove(entry.personaId()); // a walk-out in progress ends here
        boolean released = bossGearOff.remove(entry.personaId());
        Character bot = companion(entry);
        int characterId = world.residents().characterIdOf(entry.personaId());
        if (bot != null) {
            if (leaveParty) {
                leaveParty(bot, entry);
            }
            bot.saveCharToDB();
        }
        if (characterId != 0) {
            AgentRuntimeCleanupService.removeAgentByCharacterId(characterId);
            if (released) { // nothing ticks it now, and it keeps its lease meanwhile
                takeBossGearOff(entry.characterId(), entry.personaId(), bot, characterId);
            }
        }
        try {
            if (bot != null && bot.getClient() != null) {
                bot.getClient().forceDisconnect(); // the unload saves it in full (Client.disconnectInternal)
            }
        } finally {
            if (characterId != 0) {
                if (released) {
                    restoreOwnLevel(entry.personaId(), characterId); // saved and off the server, its lease still held
                }
                // saved first, so the next load sees it
                world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
            }
        }
        log.info("companion {} despawned: {}", entry.personaId(), reason);
    }

    /** At most this many personas one start's release cleanup looks at; any more wait for the next start. */
    static final int RELEASE_CLEANUP_AT_START_MAX = 200;

    /**
     * At start, on companion-ops right after the boot reconciliation and before any persona is recruited or granted:
     * finishes each release whose cleanup a stop cut short. A release drops its row at once, but its unload (the
     * !bossgear strip, then the level restore) runs only once its walk-out ends, so a crash or a hard kill in between,
     * or a release in the last seconds of a shutdown countdown, left a persona nobody employs with its GM level hold or
     * its boss pieces, and no later release to take them off. Each persona nobody employs that still has either
     * (LivingStore.releaseCleanupCandidates: one bounded query) gets exactly an offline release's cleanup
     * ({@link #takeBossGearOff}, then {@link #restoreOwnLevel}) with their online checks, refusals and logs, under a
     * COMPANION lease charged to nobody, so no Resident tick or citizen session loads it meanwhile. Who let it go is not
     * known then, so its boss-gear record (what the kit displaced) is the persona's newest under any owner, and its
     * removal is logged under that owner. A persona on anyone's roster (a crew keeps its test levels and kit), a human's
     * own record, and a character leased or online now are never touched; one left as it is waits for the next start.
     * One INFO line when any was found.
     */
    public void finishReleaseCleanupAtStart() {
        Map<String, Integer> candidates = world.store().releaseCleanupCandidates(BossGearPlanner.OWNER_TAG,
                RELEASE_CLEANUP_AT_START_MAX);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        int found = 0;
        int stripped = 0;
        int restored = 0;
        int skipped = 0;
        for (Map.Entry<String, Integer> candidate : candidates.entrySet()) {
            String personaId = candidate.getKey();
            int characterId = candidate.getValue() == null ? 0 : candidate.getValue();
            if (personaId == null || characterId <= 0 || GmLevelHolds.SELF.equals(personaId) || isOwned(personaId)) {
                continue; // a human's own record, or a persona someone employs: its hold and kit stay its owner's
            }
            found++;
            if (!world.leases().tryAcquire(characterId, CompanionLeases.Kind.COMPANION, 0)) {
                skipped++;
                log.warn("Release cleanup left for the next start: id={} persona={} (its character is loaded as {})",
                        characterId, personaId, world.leases().holder(characterId));
                continue;
            }
            try {
                // who let it go is not known now: its boss-gear record is found by persona, and ends where it lies
                int taken = takeBossGearOff(0, personaId, null, characterId);
                ReleasedLevelRestore.Result level = restoreOwnLevel(personaId, characterId);
                stripped += taken > 0 ? 1 : 0;
                restored += level == ReleasedLevelRestore.Result.RESTORED ? 1 : 0;
                skipped += taken < 0 || level == ReleasedLevelRestore.Result.HOLD_STAYS ? 1 : 0;
            } finally {
                world.leases().release(characterId, CompanionLeases.Kind.COMPANION);
            }
        }
        if (found > 0) {
            log.info("Release cleanup at start: stripped={} restored={} skipped={}", stripped, restored, skipped);
        }
    }

    /** Whether the character is loaded anywhere now (online, or registered as an Agent): it saves its own rows then. */
    private boolean loadedAnywhere(int characterId) {
        return onlineCharacter(characterId) != null || AgentRuntimeRegistry.findByAgentCharacterId(characterId) != null;
    }

    /**
     * A companion let go goes back to its own level (ReleasedLevelRestore): a GM level hold on it is restored in its
     * stored rows, as '!setlevel <name> restore' restores it, and released. Only a release's unload (and the start's
     * cleanup of one a stop cut short) calls this; one whose character is loaded anywhere now is left as it is. Never
     * in the way of the release.
     */
    private ReleasedLevelRestore.Result restoreOwnLevel(String personaId, int characterId) {
        return ReleasedLevelRestore.restore(world, personaId, characterId, this::loadedAnywhere, levelRestoreFacts);
    }

    /**
     * A companion let go never takes a !bossgear kit home or on to its next owner (CompanionGearTool): as its release
     * unloads it, every piece tagged as boss gear leaves it, worn and carried, and nothing else does. Loaded, they come
     * off right here on companion-ops (out of the registry, nothing ticks it) and it is saved; its unload then saves it
     * in full. Not loaded, they leave its saved inventory, unless its character is online now (it saves its own over
     * the store). Either way each slot a boss piece left empty takes its own carried piece back (OwnGearRefit: first
     * the very one the kit displaced, by its BossGearRecord, such as its own shield), since no load path dresses a
     * carried shield again; the removal event ends that record. Never in the way of the release: a failure is logged.
     * {@code ownerId} is who let it go: its record is read under that owner and the removal logged there. At start
     * (0: nobody known) the record is the persona's newest under any owner (BossGearRecord.readByPersona), and the
     * removal is logged under the owner whose record it was, so it ends where it lies; with none found or a failed
     * read, it wears the best it carries and the removal goes under 0. How many pieces left it; -1 when they could not
     * be taken off.
     */
    private int takeBossGearOff(int ownerId, String personaId, Character bot, int characterId) {
        try {
            int taken;
            // read only when a boss piece comes off (a release's owner needs no read for its removal event)
            Supplier<BossGearRecord.Found> record = ownerId > 0
                    ? () -> new BossGearRecord.Found(ownerId, BossGearRecord.read(world.store(), ownerId, personaId))
                    : BossGearRecord.byPersonaOnce(world.store(), personaId);
            Supplier<Map<Short, Integer>> displaced = () -> record.get().slots();
            if (bot != null) {
                taken = CompanionGearTool.takeBossGearOnRelease(bot, displaced);
                if (taken > 0) {
                    bot.saveCharToDB();
                }
            } else {
                taken = CompanionGearTool.takeStoredBossGear(world.store(), characterId, this::loadedAnywhere,
                        displaced);
            }
            if (taken > 0) {
                int recordOwner = ownerId > 0 ? ownerId : record.get().ownerId();
                world.store().event(recordOwner, personaId, BossGearRecord.REMOVED, "released: " + taken
                        + (taken == 1 ? " piece" : " pieces"));
                log.info("companion {} let go: {} boss gear pieces taken off", personaId, taken);
            } else if (taken < 0) {
                log.warn("companion {} let go: its boss gear could not be taken off (its character {} is online, or the"
                        + " store failed)", personaId, characterId);
            }
            return taken;
        } catch (RuntimeException failure) {
            log.warn("companion {} let go: taking its boss gear off failed", personaId, failure);
            return -1;
        }
    }

    /**
     * Every roster row write goes through the one transition table (CompanionSeatState): a move it does not list is
     * logged and refused, never written. False when refused. A write drops the owner's CompanionViews.
     */
    boolean write(RosterEntry next) {
        RosterEntry stored = world.store().rosterEntry(next.characterId(), next.personaId());
        RosterState from = stored == null ? null : stored.state();
        if (!CompanionSeatState.legal(from, next.state())) {
            log.warn("roster {} of {}: illegal move {} -> {} refused", next.personaId(), next.characterId(), from, next.state());
            world.store().event(next.characterId(), next.personaId(), "roster.illegal_transition", from + " -> " + next.state());
            return false;
        }
        world.store().saveRoster(next);
        CompanionViews.invalidate(world, next.characterId(), next.personaId());
        if (offFollowState(next.state())) {
            offFollow.add(next.personaId());
        } else {
            offFollow.remove(next.personaId());
        }
        if (next.state() != RosterState.HOLD) {
            holdSpots.remove(next.personaId());
        }
        return true;
    }

    /** The seat states whose companion no follow path may start following or warp (plan "Follow guards"). */
    private static boolean offFollowState(RosterState state) {
        return state == RosterState.TRAVELLING || state == RosterState.HOLD || state == RosterState.AWAY;
    }

    /** True when some row keeps its companion off follow: the follow guard's fast path. */
    boolean keepsAnyOffFollow() {
        return !offFollow.isEmpty();
    }

    /** True while the persona's row keeps it off follow and no arrival or rejoin has walked it over yet. */
    boolean keepsOffFollow(String personaId) {
        return offFollow.contains(personaId);
    }

    /** How the owner's companion {@code companionId} follows them into their current map (null: beside them). */
    AgentFollowGuard.Crossing crossing(Character owner, int companionId, long nowMs) {
        return portalFollow.crossing(owner.getId(), owner.getMapId(), companionId, nowMs);
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
