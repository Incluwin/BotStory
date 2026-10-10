package server.living.roster;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Contract;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.ContentLint;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.Population;
import server.living.population.PopulationContent.Field;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.CharacterRow;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hired hands (docs/BOT_MANAGEMENT_PLAN.md P4, decisions 2 b, 3 b and 19 b): a citizen a player hires joins their
 * roster as {@code hire:<characterId>}, a synthetic Persona that is never content. Its name and look are its own
 * characters row's (read once, then kept), its level and job are live while it is loaded, it has no rendered portrait
 * (the Bulletin Board speaks for it), no quests and no arc, and it works under contracts/hired-hand.yaml: price.meso per
 * level as its fee, then the bench upkeep every companion has as its wages (BenchLedger), paid ahead or out of its haul.
 * It never walks out: it stays while its wages are paid (decision 3 b) until its owner parts ways with it.
 *
 * <p>{@link #hire} runs on companion-ops: it checks, charges the fee and hands the citizen to
 * {@link RosterService#recruitHire}, which adopts a guest standing in its owner's party where it stands and calls any
 * other one from where it is. While a citizen is flagged {@link Citizen#FLAG_HIRED} the population leaves it alone;
 * {@link #onReleased} (installed as the roster's hired-hand release) gives it back. Owned by LivingWorld, which
 * installs the persona resolver ({@link LivingContent#persona} falls back to it for {@code hire:} ids).</p>
 */
public final class HiredHands {
    private static final Logger log = LoggerFactory.getLogger(HiredHands.class);
    /** Event log kind of a citizen's offer to stay on (the party-time offers). */
    public static final String EVENT_OFFER = "hire.offer";
    /** Event log kind of a hire accepted and paid. */
    public static final String EVENT_ACCEPT = "hire.accept";
    /** The fee per citizen level when the content has no hired-hand contract (it ships one: 1,000). */
    static final long DEFAULT_FEE_PER_LEVEL = 1_000L;
    private static final String DEFAULT_HOME_TOWN = "henesys";
    private static final String DEFAULT_PERSONALITY = "relaxed-v1";

    /**
     * A citizen's terms as its window shows them: its level and job (the job's name, "Hunter"), the fee up front and its
     * wages for an hour at work on the bench (the card's Wages page shows the same upkeep).
     */
    public record Terms(int level, String job, long fee, long hourlyWage) { }

    public enum Result { HIRED, ALREADY_YOURS, CANNOT_PAY, ROSTER_FULL, HIRE_CAP, TAKEN, BUSY, CHANGED, GONE }

    /**
     * What {@link #hire} did: {@code line} is the citizen's own words to the owner (lowercase, one short line; empty when
     * it has nothing to say), {@code notice} the roster's line for pink text (empty for none).
     */
    public record Outcome(Result result, String line, String notice) {
        public boolean hired() {
            return result == Result.HIRED;
        }
    }

    private final LivingWorld world;
    /** hire id -> its synthetic persona: the name and look read once, the level and job refreshed while it is loaded. */
    private final Map<String, Persona> personas = new ConcurrentHashMap<>();
    /** Citizen character id -> the owner whose hire of it is under way: two owners at once cannot both win. */
    private final Map<Integer, Integer> hiring = new ConcurrentHashMap<>();

    public HiredHands(LivingWorld world) {
        this.world = world;
    }

    /** LivingWorld.start: {@code hire:} ids resolve here, and a released hire goes back to the population. */
    public void install() {
        LivingContent.installHireResolver(this::persona);
        world.roster().installHiredHandRelease(this::onReleased);
    }

    /** LivingWorld.stop: {@code hire:} ids resolve to nothing again. */
    public static void uninstall() {
        LivingContent.installHireResolver(null);
    }

    // ---- personas ------------------------------------------------------------------------

    /** The {@code hire:} id of a citizen's character. */
    public static String hireId(int characterId) {
        return LivingContent.HIRE_PREFIX + characterId;
    }

    /**
     * The synthetic persona of a {@code hire:<characterId>} id; null for any other id or a character that has no row. Its
     * name, gender and look come from its characters row, its level and job from the loaded character when it is loaded.
     */
    public Persona persona(String personaId) {
        int characterId = LivingContent.hireCharacterId(personaId);
        if (characterId <= 0) {
            return null;
        }
        Persona known = personas.get(personaId);
        if (known == null) {
            CharacterRow row = world.store().characterRow(characterId);
            if (row == null) {
                return null;
            }
            Look look = new Look(row.gender() == 1 ? "female" : "male", row.skin(), row.face(), row.hair());
            known = persona(personaId, row.name(), row.level(), row.job(), look, homeTown(characterId), personality(characterId));
            personas.put(personaId, known);
        }
        Character live = loaded(characterId);
        if (live != null && live.getJob() != null && (live.getLevel() != known.level().min()
                || !live.getJob().name().equals(known.job()))) {
            known = persona(personaId, known.name(), live.getLevel(), live.getJob().getId(), known.look(), known.homeTown(),
                    known.personality());
            personas.put(personaId, known);
        }
        return known;
    }

    private static Persona persona(String id, String name, int level, int jobId, Look look, String homeTown, String personality) {
        client.Job job = client.Job.getById(jobId);
        return new Persona(id, name, "", job == null ? client.Job.BEGINNER.name() : job.name(),
                new Content.LevelBand(level, level), Content.Strength.SEASONED, null, personality, null, homeTown, List.of(),
                false, LivingContent.HIRED_HAND_CONTRACT, List.of(), null, List.of(), List.of(), Map.of(),
                -1 /* never walks out of the bench */, -1, -1, List.of(), 0 /* no rendered portrait */, look, "living_citizen");
    }

    /** The known town of the citizen's home region, for "heads home to ..."; Henesys when it has none. */
    private static String homeTown(int characterId) {
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizen(characterId);
        int town = citizen == null ? 0 : population.fields().homeTown(citizen, null);
        for (String key : ContentLint.KNOWN_TOWNS) {
            if (town != 0 && ResidentSpots.townMapId(key) == town) {
                return key;
            }
        }
        return DEFAULT_HOME_TOWN;
    }

    private static String personality(int characterId) {
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizen(characterId);
        return citizen == null || citizen.personality() == null ? DEFAULT_PERSONALITY : citizen.personality();
    }

    /** The character when it is loaded for anything, else null. */
    private static Character loaded(int characterId) {
        return AgentRuntimeIdentityRuntime.bot(AgentRuntimeRegistry.findByAgentCharacterId(characterId));
    }

    // ---- terms ---------------------------------------------------------------------------

    /**
     * The citizen's terms now: the hired-hand contract's price.meso per level as the fee, and the bench upkeep of its
     * level for an hour as its wages: 30 meso per level, capped by what the camp it would keep pays (decision 19 b,
     * BenchLedger), the same number its Wages page shows once hired. Null when its level is unknown.
     */
    public Terms terms(int citizenCharacterId) {
        Character live = loaded(citizenCharacterId);
        int level;
        int jobId;
        if (live != null && live.getJob() != null) {
            level = live.getLevel();
            jobId = live.getJob().getId();
        } else {
            int[] known = world.store().characterLevelAndJob(citizenCharacterId);
            level = known[0];
            jobId = known[1];
        }
        if (level <= 0) {
            return null;
        }
        Contract contract = world.content().contract(LivingContent.HIRED_HAND_CONTRACT);
        long perLevel = contract == null ? DEFAULT_FEE_PER_LEVEL : contract.price().meso();
        BenchLedger ledger = world.bench();
        // the camp it would keep once hired, as the hire saves it: the Wages page charges the same hour
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizen(citizenCharacterId);
        int stood = live != null ? live.getMapId() : rowMap(citizenCharacterId);
        int camp = citizen == null ? stood : camp(population, citizen, stood);
        double wage = ledger == null ? BenchLedger.upkeepPerHour(level) : ledger.upkeepPerHour(level, jobId, camp);
        return new Terms(level, CompanionView.jobName(jobId), perLevel * level, Math.round(wage));
    }

    /** Hired hands on the owner's roster. */
    public int hiresOf(Character owner) {
        int hires = 0;
        for (RosterEntry entry : world.roster().roster(owner)) {
            hires += LivingContent.isHire(entry.personaId()) ? 1 : 0;
        }
        return hires;
    }

    // ---- hiring --------------------------------------------------------------------------

    /**
     * The owner hires the citizen on the {@code terms} they were shown, on companion-ops. Refused, with the citizen's own
     * words, when it is gone, hired or being hired by anyone else, busy (partied up elsewhere, leased or led by anything
     * but the population, over the companion budget), in a run or its owner is ("after the run."), or when the owner's
     * roster or hire cap is full or they cannot pay; CHANGED when its terms are no longer the ones shown; GONE, with
     * nothing to say, when {@code owner} is not their session any more (a channel change or a logout since the click).
     * Otherwise the fee is taken in one step with the check, the citizen says "deal" at once, and
     * RosterService.recruitHire enrolls it (its persona state names its character and its employer; the population flags
     * it hired): a guest in the owner's party is adopted where it stands; one standing partyless on their channel takes a
     * guest seat first and is adopted too; one on their map with no seat for it walks out to the nearest portal to wait
     * at camp; any other is called from where it is. Should nothing be enrolled after all, even on a failure, exactly
     * the fee goes back.
     */
    public Outcome hire(Character owner, int citizenCharacterId, Terms terms) {
        Integer racing = hiring.putIfAbsent(citizenCharacterId, owner.getId());
        if (racing != null) {
            return racing == owner.getId() ? refused(Result.BUSY, citizenCharacterId, "one sec, still sorting that out.")
                    : refused(Result.TAKEN, citizenCharacterId, "sorry, someone else is hiring me right now.");
        }
        try {
            return hireNow(owner, citizenCharacterId, terms);
        } finally {
            hiring.remove(citizenCharacterId, owner.getId());
        }
    }

    private Outcome hireNow(Character owner, int characterId, Terms shown) {
        String hireId = hireId(characterId);
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizen(characterId);
        if (citizen == null || citizen.state() != Citizen.State.ACTIVE) {
            return new Outcome(Result.GONE, "", "They are not around any more.");
        }
        int employer = world.roster().ownerOf(hireId);
        if (employer == owner.getId()) {
            return refused(Result.ALREADY_YOURS, characterId, "i'm already with you.");
        }
        if (employer != 0 || citizen.hired()) {
            return refused(Result.TAKEN, characterId, "sorry, i already work for someone.");
        }
        Character loaded = loaded(characterId);
        Commitments commitments = world.commitments();
        if (owner.getEventInstance() != null || commitments != null && commitments.ownerCommitted(owner.getId())
                || loaded != null && loaded.getEventInstance() != null) {
            return refused(Result.BUSY, characterId, RosterService.AFTER_THE_RUN_LINE);
        }
        if (world.roster().roster(owner).size() >= world.config().ROSTER_MAX) {
            return refused(Result.ROSTER_FULL, characterId, "you've got enough people following you around.");
        }
        if (hiresOf(owner) >= world.config().HIRE_MAX_PER_OWNER) {
            return refused(Result.HIRE_CAP, characterId, "you've got plenty of hired hands already.");
        }
        Terms terms = terms(characterId);
        if (terms == null) {
            return new Outcome(Result.GONE, "", citizen.name() + " is not around any more.");
        }
        if (shown == null || shown.fee() != terms.fee()) {
            return new Outcome(Result.CHANGED, "", citizen.name() + "'s terms changed. Ask again.");
        }
        int fee = (int) Math.min(terms.fee(), Integer.MAX_VALUE);
        if (owner.getMeso() < fee) {
            return cannotPay(characterId, terms);
        }
        boolean inOwnersParty = loaded != null && owner.getParty() != null && loaded.getParty() != null
                && loaded.getParty().getId() == owner.getParty().getId() && RosterService.sameChannel(owner, loaded);
        if (busy(owner, characterId, loaded, inOwnersParty)) {
            return refused(Result.BUSY, characterId, "i'm in the middle of something. ask me later.");
        }
        boolean onOwnersChannel = loaded != null && RosterService.sameChannel(owner, loaded);
        // partyless on its new owner's channel with a seat for it: a guest seat first, then adopted where it stands
        boolean joinable = !inOwnersParty && onOwnersChannel && loaded.getParty() == null && world.roster().freeSlots(owner) > 0;
        if ((inOwnersParty || joinable) && !world.budget().admitInPlace(CompanionBudget.Kind.COMPANION, owner).allowed()) {
            return refused(Result.BUSY, characterId, "give me a moment. too many of us are out right now.");
        }
        personas.remove(hireId); // its name and look as its row holds them now
        Persona persona = persona(hireId);
        if (persona == null) {
            return new Outcome(Result.GONE, "", citizen.name() + " is not around any more.");
        }
        if (world.roster().onlineCharacter(owner.getId()) != owner) {
            return new Outcome(Result.GONE, "", ""); // the session that clicked is gone: its purse is not theirs any more
        }
        if (!owner.tryPayMeso(fee, true)) {
            return cannotPay(characterId, terms); // spent meanwhile
        }
        boolean hired = false;
        try {
            world.residents().whisper(persona, owner, voice(characterId, "deal. let's go.")); // before the roster's lines
            boolean adopt = inOwnersParty || joinable && new CompanionPartyRouter(world).seatGuest(owner, loaded);
            boolean walkOut = !adopt && onOwnersChannel && loaded.getMap() != null && loaded.getMap() == owner.getMap();
            Character inPlace = adopt || walkOut ? loaded : null;
            int camp = camp(population, citizen, loaded != null ? loaded.getMapId() : rowMap(characterId));
            RosterResult result = world.roster().recruitHire(owner, persona, camp, inPlace, adopt,
                    () -> population.presence().handOver(characterId, inPlace != null, "hired by " + owner.getName()));
            if (world.roster().entry(owner.getId(), hireId) == null) {
                return refused(Result.BUSY, characterId, "i can't right now. ask me again in a bit.");
            }
            hired = true;
            world.store().event(owner.getId(), hireId, EVENT_ACCEPT, "fee " + terms.fee() + ", lv" + terms.level()
                    + (adopt ? ", adopted where it stood" : walkOut ? ", walked out to camp" : ", called from map " + camp));
            log.info("{} hired {} ({}) for {} meso", owner.getName(), citizen.name(), hireId, terms.fee());
            return new Outcome(Result.HIRED, "", adopt ? "" : RosterVoice.line(persona, result));
        } finally {
            if (!hired && world.roster().entry(owner.getId(), hireId) == null) {
                owner.gainMeso(fee, true); // nothing was hired, even on a failure: exactly the fee goes back
                giveBack(characterId);
            }
        }
    }

    private Outcome cannotPay(int characterId, Terms terms) {
        return refused(Result.CANNOT_PAY, characterId,
                "you're a bit short. it's " + String.format(Locale.ROOT, "%,d", terms.fee()) + " meso up front.");
    }

    /**
     * Not the population's to hand over now: leased for anything (a party quest run, a bench slice), loaded under any
     * leader but itself (a PQ lobby), partied up with others, or a guest in the owner's party charged to another human.
     */
    private boolean busy(Character owner, int characterId, Character loaded, boolean inOwnersParty) {
        if (world.leases().holder(characterId) != null) {
            return true;
        }
        if (loaded == null) {
            return false;
        }
        if (AgentRuntimeRegistry.leaderIdForAgentCharacter(characterId) != characterId) {
            return true;
        }
        if (!inOwnersParty) {
            return loaded.getParty() != null;
        }
        CompanionSlots.Reservation held = world.slots().reservation(CompanionSlots.characterKey(characterId));
        return held != null && held.ownerId() != owner.getId();
    }

    /**
     * Where it camps once hired: the map it is on (or was saved on) when that is a field or town, else (or on a field
     * closed to camps, FieldCatalog.closed) its home town.
     */
    private static int camp(Population population, Citizen citizen, int mapId) {
        Field field = mapId <= 0 ? null : population.content().field(mapId);
        return field != null && !population.fields().closed(mapId) ? mapId : population.fields().homeTown(citizen, null);
    }

    private int rowMap(int characterId) {
        CharacterRow row = world.store().characterRow(characterId);
        return row == null ? 0 : row.mapId();
    }

    private Outcome refused(Result result, int characterId, String words) {
        return new Outcome(result, voice(characterId, words), "");
    }

    /** A line in the citizen's own typing (VoicePacks), or as written without a population. */
    public String voice(int characterId, String line) {
        Population population = Population.get();
        Citizen citizen = population == null ? null : population.citizen(characterId);
        return citizen == null ? line : population.voices().render(citizen, line, line.hashCode());
    }

    // ---- release -------------------------------------------------------------------------

    /**
     * The roster let the hired citizen go (installed as RosterService's hired-hand release): by its owner, a contract end
     * or its owner's deletion. The roster's release already cleared employed_by and unloads it (a walk to the nearest
     * portal on its owner's map); here its hired flag goes, so the population logs it in again in time and Churn counts it
     * again, and its persona is forgotten.
     */
    public void onReleased(int ownerId, String personaId, String reason) {
        personas.remove(personaId);
        giveBack(LivingContent.hireCharacterId(personaId));
        log.info("hired hand {} of {} let go: {}", personaId, ownerId, reason);
    }

    /**
     * The citizen is the population's again (unless another hire of it is on the roster). With no population now (it is
     * off, or stopped before this release ran) the flag stays, and {@link #reconcileFlags} clears it once it is up.
     */
    private void giveBack(int characterId) {
        Population population = Population.get();
        if (population != null && characterId > 0 && world.roster().cachedOwnerOf(hireId(characterId)) == 0) {
            population.setHired(characterId, false);
        }
    }

    /**
     * The roster's hire rows are the truth for the population's hired flag (plan "Hired hand"): on companion-ops once
     * the population is up (LivingWorld.start, after the roster's boot reconciliation) and daily. A flagged citizen no
     * hire row employs goes back to the population (a lost release, a crash between a row's removal and the flag); one
     * a row employs with no flag is handed over, which flags it and logs off any population session (a save the table
     * never took, a crash between the row and the flag). Each repair is a companion.drift event. Returns the repairs.
     */
    public int reconcileFlags() {
        Population population = Population.get();
        if (population == null) {
            return 0;
        }
        Map<Integer, Integer> employed = new HashMap<>();
        List<RosterEntry> rows = new ArrayList<>(world.store().allActive());
        rows.addAll(world.store().allBenched());
        rows.addAll(world.store().allInTransit());
        for (RosterEntry row : rows) {
            int characterId = LivingContent.hireCharacterId(row.personaId());
            if (characterId > 0 && row.state() != RosterState.RELEASED) {
                employed.put(characterId, row.characterId());
            }
        }
        int repaired = 0;
        for (Citizen citizen : List.copyOf(population.citizens())) {
            String hireId = hireId(citizen.characterId());
            // a scan that failed reads as no rows: the roster's cache and the citizen's own row must agree first
            if (citizen.hired() && !employed.containsKey(citizen.characterId()) && world.roster().cachedOwnerOf(hireId) == 0
                    && world.store().rosterOfPersona(hireId) == null) {
                population.setHired(citizen.characterId(), false);
                drift(0, citizen.characterId(), "hired flag with no roster row: back to the population");
                repaired++;
            }
        }
        for (Map.Entry<Integer, Integer> row : employed.entrySet()) {
            Citizen citizen = population.citizen(row.getKey());
            if (citizen != null && !citizen.hired()) {
                population.presence().handOver(row.getKey(), false, "reconcile");
                drift(row.getValue(), row.getKey(), "roster row with no hired flag: flagged, population session ended");
                repaired++;
            }
        }
        return repaired;
    }

    private void drift(int ownerId, int characterId, String detail) {
        log.info("companion.drift {} of {}: {}", hireId(characterId), ownerId, detail);
        world.store().event(ownerId, hireId(characterId), "companion.drift", detail);
    }
}
