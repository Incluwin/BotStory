package server.living.population.rivals;

import client.Character;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.expeditions.ExpeditionType;
import server.living.population.ChatterEngine;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.population.records.Clear;
import server.living.population.records.ClearLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rival crews (docs/BOT_CONCEPT_PLAN.md F7.2-F7.4a, decisions D12/D13): each player account gets a crew of 4-5
 * existing citizens within RIVALS_JOIN_BAND levels of its main character (the account's highest), under a name that is
 * also their own guild's, in one circle so they log in and party up together. Nothing steers anyone's EXP: while the
 * player is online the crew is checked every RIVALS_REFRESH_MINUTES, and a member now more than RIVALS_LEAVE_BAND levels
 * from the main leaves (a loaded one says why first: it fell behind or got ahead), and a citizen in band takes its seat.
 * <p>
 * Contact: banter when a loaded member shares a map with the main; a megaphone when the crew beats one of the main's
 * times (one slot of the shared cap, else a whisper); a whispered congratulation when the main beats the crew's; news in
 * the main's letters while they are away (living_event_log {@code rival.news}); and for Zakum, which the crew cannot do
 * alone, a help request on the needs board inviting the main as a guest. All behind population.RIVALS_ENABLED; the
 * clear records themselves are ClearLog's.
 */
public final class RivalCrews implements ClearLog.Listener {
    private static final Logger log = LoggerFactory.getLogger(RivalCrews.class);
    /** living_event_log kind of a crew clear while its player was away (read by CompanionLetters). */
    public static final String NEWS_EVENT = "rival.news";
    static final String JOURNAL = "crew";
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 3_600_000L;
    /** A member out of band says its line, then leaves this much later (its guild line goes out first). */
    private static final long LEAVE_AFTER_LINE_MS = 30_000L;
    /** A congratulation owed while no member was loaded waits this long at most. */
    private static final long CONGRATS_KEPT_MS = 24 * HOUR_MS;
    /** Below this many members a crew takes new ones (it formed with 4 or 5). */
    private static final int REFILL_TO = 4;
    /** Guild masters and jr. masters of other guilds are never picked. */
    private static final int LOWEST_RANK_PICKED = 3;
    /** Guilds need level 10 (SocialFabric founds and recruits from there). */
    private static final int MIN_MEMBER_LEVEL = 10;

    /** What the crews ask of the world; the server's is {@link CrewGuilds}, tests bring their own. */
    public interface Ports {
        /** Players online now (no Agents). */
        List<Character> onlineHumans();

        /** A free name for a new crew in the bot-guild style (3-12 letters, no guild has it), or null. */
        String freeName(Random random);

        /** {@code leader} founds the crew's guild {@code name} (leaving any guild first); its id, or 0. */
        int found(Citizen leader, String name);

        /** {@code member} joins guild {@code guildId} (leaving any other first). */
        void join(Citizen member, int guildId);

        /** {@code member} leaves guild {@code guildId}. */
        void leave(Citizen member, int guildId);

        /** The guild's master rank passes from {@code fromId} to {@code toId}. */
        void handOver(int guildId, int fromId, int toId);

        void disband(int guildId);

        boolean guildExists(int guildId);

        /** A new circle row; its id, or 0. */
        int circle(String name, String cohort);
    }

    /** A congratulation owed to a main who beat the crew's time. */
    private record Owed(String quest, long timeMs, long crewTimeMs, long atMs) { }

    /** What the crews keep of a player online. */
    private static final class Seen {
        int mainId;
        int mainLevel;
        long mainReadAtMs;
        long refreshedAtMs;
        long banterAtMs;
    }

    private final Population population;
    private final RivalStore store;
    private final Ports ports;
    private final Random random;
    private final Map<Integer, Crew> byAccount = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> crewOfCitizen = new ConcurrentHashMap<>();
    private final Map<Integer, Seen> seen = new ConcurrentHashMap<>();
    private final Map<Integer, Owed> owed = new ConcurrentHashMap<>();
    /** Citizen id -> when it said it is leaving (it leaves LEAVE_AFTER_LINE_MS later). */
    private final Map<Integer, Long> leaving = new ConcurrentHashMap<>();
    /** Account -> the member whose help request is up (its PARTY need's poster), for the player's answer. */
    private final Map<Integer, Integer> helpBy = new ConcurrentHashMap<>();
    private volatile boolean started;

    public RivalCrews(Population population) {
        this(population, new RivalStore(), null, new Random(population.config().SEED ^ 0x41a7c2eL));
    }

    RivalCrews(Population population, RivalStore store, Ports ports, Random random) {
        this.population = population;
        this.store = store;
        this.ports = ports == null ? new CrewGuilds(population) : ports;
        this.random = random;
    }

    public boolean on() {
        return population.config().RIVALS_ENABLED;
    }

    private PopulationConfig config() {
        return population.config();
    }

    /** The stored crews, once, the first time the switch is on (never at construction). */
    synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        for (Crew crew : store.all()) {
            put(crew);
        }
        log.info("rival crews: {} crews", byAccount.size());
    }

    private void put(Crew crew) {
        Crew before = byAccount.put(crew.accountId(), crew);
        if (before != null) {
            before.members().forEach(member -> crewOfCitizen.remove(member, before.id()));
        }
        crew.members().forEach(member -> crewOfCitizen.put(member, crew.id()));
    }

    private void save(Crew crew) {
        put(crew);
        store.update(crew);
    }

    // ---- views ---------------------------------------------------------------------------------

    public Crew crewOfAccount(int accountId) {
        return byAccount.get(accountId);
    }

    /** The crew a citizen is in, or null. */
    public Crew crewOf(int citizenId) {
        Integer crewId = crewOfCitizen.get(citizenId);
        return crewId == null ? null : byId(crewId);
    }

    public Crew byId(int crewId) {
        for (Crew crew : byAccount.values()) {
            if (crew.id() == crewId) {
                return crew;
            }
        }
        return null;
    }

    /** The crew racing this character (it is its account's main), or null. */
    public Crew crewOfMain(int characterId) {
        for (Crew crew : byAccount.values()) {
            if (crew.mainId() == characterId) {
                return crew;
            }
        }
        return null;
    }

    /** A guild id is a crew's (never a bot guild of SocialFabric's). */
    public boolean isCrewGuild(int guildId) {
        if (guildId <= 0) {
            return false;
        }
        for (Crew crew : byAccount.values()) {
            if (crew.guildId() == guildId) {
                return true;
            }
        }
        return false;
    }

    // ---- the minute -----------------------------------------------------------------------------

    /** The population's social minute: each player online gets their crew formed, kept in band, and in touch. */
    public void tick() {
        tick(System.currentTimeMillis());
    }

    void tick(long now) {
        if (!on()) {
            return;
        }
        try {
            start();
            Map<Integer, int[]> progress = null;
            Map<Integer, List<Character>> online = new LinkedHashMap<>();
            for (Character human : ports.onlineHumans()) {
                if (human != null) {
                    online.computeIfAbsent(human.getAccountID(), id -> new ArrayList<>()).add(human);
                }
            }
            for (Map.Entry<Integer, List<Character>> entry : online.entrySet()) {
                Seen one = see(entry.getKey(), entry.getValue(), now);
                if (one == null || one.mainLevel < config().RIVALS_MIN_LEVEL) {
                    continue;
                }
                Crew crew = byAccount.get(entry.getKey());
                boolean due = crew == null || now - one.refreshedAtMs >= config().RIVALS_REFRESH_MINUTES * MINUTE_MS
                        || crew.guildId() <= 0;
                if (due) {
                    if (progress == null) {
                        progress = store.citizenProgress();
                    }
                    one.refreshedAtMs = now;
                    crew = crew == null ? form(entry.getKey(), one, progress, now) : refresh(crew, one, progress, now);
                }
                if (crew == null) {
                    continue;
                }
                Character main = null;
                for (Character human : entry.getValue()) {
                    main = human.getId() == one.mainId ? human : main;
                }
                if (main != null) {
                    banter(crew, main, one, now);
                    congratulateOwed(crew, main, now);
                    helpRequest(crew, main, one, now);
                }
            }
            finishLeaving(now);
        } catch (RuntimeException failure) {
            log.warn("rival crews tick failed", failure);
        }
    }

    private int mainId(int accountId) {
        Seen one = seen.get(accountId);
        return one == null ? 0 : one.mainId;
    }

    /**
     * The account's main (its highest-level character, read again every refresh), with the live level of whichever of
     * its characters is online: one that overtook the stored main becomes the main at once.
     */
    private Seen see(int accountId, List<Character> online, long now) {
        Seen one = seen.computeIfAbsent(accountId, id -> new Seen());
        if (one.mainId == 0 || now - one.mainReadAtMs >= config().RIVALS_REFRESH_MINUTES * MINUTE_MS) {
            int[] main = store.mainOf(accountId);
            if (main == null) {
                return null;
            }
            one.mainId = main[0];
            one.mainLevel = main[1];
            one.mainReadAtMs = now;
        }
        for (Character character : online) {
            if (character.getId() == one.mainId) {
                one.mainLevel = character.getLevel();
            } else if (character.getLevel() > one.mainLevel) {
                one.mainId = character.getId();
                one.mainLevel = character.getLevel();
            }
        }
        return one;
    }

    private int levelOf(int citizenId, Map<Integer, int[]> progress) {
        CitizenSession session = population.presence().session(citizenId);
        Character agent = session == null || session.virtual ? null : session.agent();
        int[] stored = progress.get(citizenId);
        int level = stored == null ? 0 : stored[0];
        return agent != null ? Math.max(level, agent.getLevel()) : level;
    }

    private CitizenSession loaded(int citizenId) {
        CitizenSession session = population.presence().session(citizenId);
        return session == null || session.virtual || session.agent() == null ? null : session;
    }

    // ---- forming and keeping a crew ---------------------------------------------------------------

    /** Citizens who may join a crew racing a main of {@code mainLevel}, the closest in level first. */
    private List<Citizen> eligible(int mainLevel, Map<Integer, int[]> progress) {
        List<Citizen> out = new ArrayList<>();
        for (Citizen citizen : population.citizens()) {
            if (citizen.state() != Citizen.State.ACTIVE || citizen.hired() || crewOfCitizen.containsKey(citizen.characterId())
                    || leaving.containsKey(citizen.characterId()) || citizen.has(Citizen.FLAG_RECRUITER)) {
                continue;
            }
            int[] stored = progress.get(citizen.characterId());
            if (stored == null || stored[1] > 0 && stored[2] < LOWEST_RANK_PICKED) {
                continue; // a master or jr. master keeps its own guild
            }
            int level = levelOf(citizen.characterId(), progress);
            if (level >= MIN_MEMBER_LEVEL && Math.abs(level - mainLevel) <= config().RIVALS_JOIN_BAND) {
                out.add(citizen);
            }
        }
        Collections.shuffle(out, random);
        return out;
    }

    /** A new crew: an anchor, then its cohort first (shared hours), up to 4-5; at least RIVALS_CREW_MIN or none yet. */
    Crew form(int accountId, Seen one, Map<Integer, int[]> progress, long now) {
        List<Citizen> pool = eligible(one.mainLevel, progress);
        if (pool.size() < config().RIVALS_CREW_MIN) {
            log.debug("rival crew for account {}: {} citizens in band, not enough yet", accountId, pool.size());
            return null;
        }
        Citizen anchor = pool.getFirst();
        List<Citizen> rest = new ArrayList<>(pool.subList(1, pool.size()));
        rest.sort(Comparator.comparing((Citizen citizen) -> !citizen.cohort().equals(anchor.cohort())));
        int size = Math.min(pool.size(), Math.max(config().RIVALS_CREW_MIN,
                config().RIVALS_CREW_MAX - random.nextInt(2)));
        List<Citizen> picked = new ArrayList<>();
        picked.add(anchor);
        picked.addAll(rest.subList(0, size - 1));
        String name = ports.freeName(random);
        if (name == null) {
            return null;
        }
        int guildId = ports.found(anchor, name);
        if (guildId <= 0) {
            log.info("rival crew '{}' for account {}: its guild could not be founded", name, accountId);
            return null;
        }
        int circleId = ports.circle(name, anchor.cohort());
        List<Integer> ids = new ArrayList<>();
        for (Citizen member : picked) {
            if (member != anchor) {
                ports.join(member, guildId);
            }
            enterCircle(member, circleId);
            ids.add(member.characterId());
        }
        Crew crew = new Crew(0, accountId, one.mainId, name, guildId, circleId, anchor.characterId(), ids, now, 0L);
        int id = store.insert(crew);
        if (id <= 0) {
            return null;
        }
        crew = crew.withId(id);
        put(crew);
        for (Citizen member : picked) {
            journal(member.characterId(), "joined the " + name);
        }
        log.info("rival crew '{}' ({}) formed for account {} (main {} lv{}): {}", name, id, accountId, one.mainId,
                one.mainLevel, picked.stream().map(Citizen::name).toList());
        return crew;
    }

    /** Members gone or out of band leave (a loaded one says so first); the crew refills to 4; it keeps a guild. */
    Crew refresh(Crew crew, Seen one, Map<Integer, int[]> progress, long now) {
        if (crew.mainId() != one.mainId) {
            crew = crew.withMain(one.mainId);
            save(crew);
        }
        for (int memberId : new ArrayList<>(crew.members())) {
            Citizen citizen = population.citizen(memberId);
            if (citizen == null || citizen.state() != Citizen.State.ACTIVE || citizen.hired()) {
                crew = drop(crew, memberId, citizen);
                continue;
            }
            int level = levelOf(memberId, progress);
            if (Math.abs(level - one.mainLevel) <= config().RIVALS_LEAVE_BAND || leaving.containsKey(memberId)) {
                continue;
            }
            CitizenSession session = loaded(memberId);
            if (session == null) {
                crew = drop(crew, memberId, citizen); // nobody sees an away citizen go: the guild's notice tells it
            } else {
                sayLeaving(crew, session, level < one.mainLevel, now);
            }
        }
        crew = keepGuild(crew);
        if (crew.members().size() < REFILL_TO) {
            List<Citizen> pool = eligible(one.mainLevel, progress);
            Citizen leader = population.citizen(crew.leaderId());
            String cohort = leader == null ? "" : leader.cohort();
            pool.sort(Comparator.comparing((Citizen citizen) -> !citizen.cohort().equals(cohort)));
            for (Citizen recruit : pool) {
                if (crew.members().size() >= REFILL_TO) {
                    break;
                }
                crew = admit(crew, recruit);
            }
        }
        save(crew);
        return crew;
    }

    /** One more member: guild, circle, a line in guild chat when it is loaded. */
    private Crew admit(Crew crew, Citizen recruit) {
        if (crew.guildId() > 0) {
            ports.join(recruit, crew.guildId());
        }
        enterCircle(recruit, crew.circleId());
        crew = crew.with(recruit.characterId());
        crewOfCitizen.put(recruit.characterId(), crew.id());
        journal(recruit.characterId(), "joined the " + crew.name());
        CitizenSession session = loaded(recruit.characterId());
        if (session != null) {
            speak(session, "rival.join", null, crew, Map.of());
        }
        log.info("rival crew '{}': {} joined", crew.name(), recruit.name());
        return crew;
    }

    /** A loaded member out of band says why in guild chat and whispers the main; it leaves next tick or so. */
    private void sayLeaving(Crew crew, CitizenSession session, boolean behind, long now) {
        leaving.put(session.characterId, now);
        String kind = behind ? "behind" : "ahead";
        speak(session, "rival.leave." + kind, null, crew, Map.of());
        Character main = onlineCharacter(crew.mainId());
        if (main != null) {
            whisper(session, main, "rival.leave." + kind + ".whisper", crew, Map.of());
        }
        log.info("rival crew '{}': {} is leaving ({} the main)", crew.name(), session.name(), kind);
    }

    /** Those who said they are leaving leave once their line is out. */
    private void finishLeaving(long now) {
        for (Map.Entry<Integer, Long> entry : new ArrayList<>(leaving.entrySet())) {
            if (now - entry.getValue() < LEAVE_AFTER_LINE_MS) {
                continue;
            }
            leaving.remove(entry.getKey());
            Crew crew = crewOf(entry.getKey());
            if (crew != null) {
                crew = keepGuild(drop(crew, entry.getKey(), population.citizen(entry.getKey())));
                save(crew);
            }
        }
    }

    /** {@code memberId} is out of the crew, its guild and circle; a leaving master hands the guild on first. */
    private Crew drop(Crew crew, int memberId, Citizen citizen) {
        Crew after = crew.without(memberId);
        crewOfCitizen.remove(memberId, crew.id());
        if (crew.guildId() > 0 && memberId == crew.leaderId()) {
            int heir = heir(after);
            if (heir > 0) {
                ports.handOver(crew.guildId(), memberId, heir);
                after = after.withLeader(heir);
            } else {
                ports.disband(crew.guildId());
                after = after.withGuild(after.name(), 0, 0);
            }
        }
        if (citizen != null) {
            if (crew.guildId() > 0 && after.guildId() > 0 && !citizen.hired()) {
                ports.leave(citizen, crew.guildId());
            }
            if (!citizen.hired()) {
                population.update(memberId, current -> current.circleId() == crew.circleId() ? current.withCircle(0) : current);
            }
            journal(memberId, "left the " + crew.name());
        }
        log.info("rival crew '{}': {} left", crew.name(), citizen == null ? memberId : citizen.name());
        return after;
    }

    /** The member to take the guild over: the highest level, from memory or the stored row. */
    private int heir(Crew crew) {
        int best = 0;
        int bestLevel = -1;
        for (int memberId : crew.members()) {
            CitizenSession session = loaded(memberId);
            int level = session != null ? session.agent().getLevel() : 0;
            if (level > bestLevel) {
                best = memberId;
                bestLevel = level;
            }
        }
        return best;
    }

    /** A crew whose guild is gone (disbanded, or never founded) founds it again with its first member. */
    private Crew keepGuild(Crew crew) {
        if (crew.guildId() > 0 && ports.guildExists(crew.guildId())) {
            return crew;
        }
        if (crew.members().isEmpty()) {
            return crew.guildId() > 0 ? crew.withGuild(crew.name(), 0, 0) : crew;
        }
        Citizen leader = population.citizen(crew.members().getFirst());
        if (leader == null) {
            return crew;
        }
        String name = crew.name();
        int guildId = ports.found(leader, name);
        if (guildId <= 0) { // its name was taken meanwhile
            String other = ports.freeName(random);
            guildId = other == null ? 0 : ports.found(leader, other);
            name = guildId > 0 ? other : name;
        }
        if (guildId <= 0) {
            return crew.withGuild(crew.name(), 0, 0);
        }
        for (int memberId : crew.members()) {
            Citizen member = population.citizen(memberId);
            if (member != null && memberId != leader.characterId()) {
                ports.join(member, guildId);
            }
        }
        log.info("rival crew '{}' founded its guild again ({})", name, guildId);
        return crew.withGuild(name, guildId, leader.characterId());
    }

    private void enterCircle(Citizen member, int circleId) {
        if (circleId > 0) {
            population.update(member.characterId(), current -> current.hired() ? current : current.withCircle(circleId));
        }
    }

    // ---- clears --------------------------------------------------------------------------------------

    /** The crew a clear counts for: two or more of its members in it, and not its own player (a shared run is no race). */
    @Override
    public int creditFor(Set<Integer> memberIds) {
        if (!on()) {
            return 0;
        }
        start();
        for (Crew crew : byAccount.values()) {
            if (memberIds.contains(crew.mainId())) {
                continue;
            }
            int in = 0;
            for (int member : crew.members()) {
                if (memberIds.contains(member)) {
                    in++;
                }
            }
            if (in >= 2) {
                return crew.id();
            }
        }
        return 0;
    }

    /**
     * A clear was booked: the crew's beats its main's best (a megaphone, else a whisper; letters news while away), and a
     * main's beats its crew's best (a whispered congratulation, now or when a member is next loaded).
     */
    @Override
    public void cleared(Clear clear, long crewBestBefore, Map<Integer, Long> playerBestsBefore) {
        if (!on()) {
            return;
        }
        try {
            if (clear.crewId() > 0) {
                crewCleared(clear, crewBestBefore);
            }
            for (Map.Entry<Integer, Long> player : playerBestsBefore.entrySet()) {
                Crew crew = crewOfMain(player.getKey());
                if (crew != null && clear.timed()) {
                    mainCleared(crew, clear, player.getValue());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("rival reaction to a clear of {} failed", clear.quest(), failure);
        }
    }

    private void crewCleared(Clear clear, long crewBestBefore) {
        Crew crew = byId(clear.crewId());
        if (crew == null || !clear.timed()) {
            return;
        }
        long theirs = population.clears().playerBest(crew.mainId(), clear.quest());
        boolean newBest = crewBestBefore <= 0L || clear.durationMs() < crewBestBefore;
        boolean beat = newBest && theirs > 0L && clear.durationMs() < theirs;
        Character main = onlineCharacter(crew.mainId());
        if (main == null) { // away: their letters tell it, beaten or not
            news(crew, clear, beat ? theirs : 0L);
        }
        if (!beat) {
            return;
        }
        CitizenSession speaker = main == null ? null : speakerOf(crew, clear);
        Map<String, String> slots = slots(clear, theirs);
        String boast = speaker == null ? null
                : population.chatter().compose(event(speaker, "rival.beat", main.getName(), crew, slots));
        boolean loud = boast != null && population.chatter().megaphone(speaker, boast, false); // no megaphone stock (v1)
        if (!loud && speaker != null) {
            whisper(speaker, main, "rival.beat.whisper", crew, slots);
        }
        log.info("rival crew '{}' beat character {}'s {} time: {} < {} ({})", crew.name(), crew.mainId(), clear.quest(),
                ClearLog.time(clear.durationMs()), ClearLog.time(theirs),
                loud ? "megaphone" : speaker != null ? "whisper" : main == null ? "letters" : "nobody loaded to say it");
    }

    private void mainCleared(Crew crew, Clear clear, long bestBefore) {
        long crewBest = population.clears().crewBest(crew.id(), clear.quest());
        boolean newBest = bestBefore <= 0L || clear.durationMs() < bestBefore;
        if (!newBest || crewBest <= 0L || clear.durationMs() >= crewBest) {
            return;
        }
        Owed debt = new Owed(clear.quest(), clear.durationMs(), crewBest, clear.clearedAtMs());
        Character main = onlineCharacter(crew.mainId());
        if (main == null || !congratulate(crew, main, debt)) {
            owed.put(crew.accountId(), debt);
        }
        log.info("{} beat rival crew '{}' at {}: {} < {}", main == null ? crew.mainId() : main.getName(), crew.name(),
                clear.quest(), ClearLog.time(clear.durationMs()), ClearLog.time(crewBest));
    }

    private void congratulateOwed(Crew crew, Character main, long now) {
        Owed debt = owed.get(crew.accountId());
        if (debt == null) {
            return;
        }
        if (now - debt.atMs() > CONGRATS_KEPT_MS || congratulate(crew, main, debt)) {
            owed.remove(crew.accountId(), debt);
        }
    }

    private boolean congratulate(Crew crew, Character main, Owed debt) {
        for (int memberId : crew.members()) {
            CitizenSession session = loaded(memberId);
            if (session != null) {
                Map<String, String> slots = new LinkedHashMap<>();
                slots.put("quest", population.clears().labels().label(debt.quest()));
                slots.put("time", ClearLog.time(debt.timeMs()));
                slots.put("theirs", ClearLog.time(debt.crewTimeMs()));
                return whisper(session, main, "rival.congrats", crew, slots);
            }
        }
        return false;
    }

    /** The crew's news for the main's letters: "the Ironclads cleared Easy Balrog in 14:20 (yours: 16:05)". */
    private void news(Crew crew, Clear clear, long beaten) {
        String detail = crew.name() + "|" + clear.quest() + "|" + clear.durationMs() + "|" + beaten;
        population.world().store().event(crew.mainId(), null, NEWS_EVENT, detail);
    }

    /** A member of the crew who was in the clear and is loaded, to speak for it. */
    private CitizenSession speakerOf(Crew crew, Clear clear) {
        List<Integer> ids = new ArrayList<>(crew.members());
        Collections.shuffle(ids, random);
        for (int memberId : ids) {
            CitizenSession session = clear.has(memberId) ? loaded(memberId) : null;
            if (session != null) {
                return session;
            }
        }
        return null;
    }

    private Map<String, String> slots(Clear clear, long theirs) {
        Map<String, String> slots = new LinkedHashMap<>();
        slots.put("quest", population.clears().labels().label(clear.quest()));
        slots.put("time", ClearLog.time(clear.durationMs()));
        slots.put("theirs", ClearLog.time(theirs));
        return slots;
    }

    // ---- being in touch ---------------------------------------------------------------------------------

    /** A loaded member on the main's map says something to them, once every RIVALS_BANTER_GAP_MINUTES. */
    private void banter(Crew crew, Character main, Seen one, long now) {
        if (now - one.banterAtMs < config().RIVALS_BANTER_GAP_MINUTES * MINUTE_MS || main.getMap() == null
                || main.getMap().getEventInstance() != null) {
            return;
        }
        int channel = main.getClient() == null ? 0 : main.getClient().getChannel();
        for (int memberId : crew.members()) {
            CitizenSession session = loaded(memberId);
            if (session != null && session.mapId == main.getMapId() && session.channel == channel
                    && !leaving.containsKey(memberId)) {
                Map<String, String> slots = new LinkedHashMap<>();
                slots.put("quest", bestQuest(crew, main));
                if (population.chatter().speakTo(event(session, "rival.banter", main.getName(), crew, slots))) {
                    one.banterAtMs = now;
                }
                return;
            }
        }
    }

    /** A quest both have a time on, the crew's best there leading if it can ("kpq"), else the PQ word. */
    private String bestQuest(Crew crew, Character main) {
        for (Map.Entry<String, Long> best : population.clears().crewBests(crew.id()).entrySet()) {
            if (population.clears().playerBest(main.getId(), best.getKey()) > 0L) {
                return population.clears().labels().label(best.getKey());
            }
        }
        return "pq";
    }

    /**
     * F7.4a: a crew that reaches Zakum but cannot fight it without a player's client asks its main along: a PARTY
     * request on the needs board with the main as the guest, and a whisper, once every RIVALS_HELP_GAP_HOURS.
     */
    private void helpRequest(Crew crew, Character main, Seen one, long now) {
        if (!config().RIVAL_HELP_REQUESTS_ENABLED || population.needs() == null
                || now - crew.helpAskedAtMs() < config().RIVALS_HELP_GAP_HOURS * HOUR_MS) {
            return; // off until the citizen Zakum run (F7.4b) exists: an invitation that leads nowhere is a tell
        }
        ExpeditionType zakum = ExpeditionType.ZAKUM;
        if (one.mainLevel < zakum.getMinLevel() || one.mainLevel > zakum.getMaxLevel()) {
            return;
        }
        List<CitizenSession> online = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (int memberId : crew.members()) {
            CitizenSession session = loaded(memberId);
            if (session != null) {
                online.add(session);
                levels.add(session.agent().getLevel());
            }
        }
        if (online.size() < config().RIVALS_CREW_MIN) {
            return;
        }
        Collections.sort(levels);
        if (levels.get(levels.size() / 2) < zakum.getMinLevel()) {
            return;
        }
        CitizenSession leader = online.stream().filter(session -> session.characterId == crew.leaderId()).findFirst()
                .orElse(online.getFirst());
        population.needs().helpWanted(leader, "zakum", ZAKUM_DOOR_MAP, main.getId(),
                now + config().RIVALS_HELP_OPEN_MINUTES * MINUTE_MS, now);
        helpBy.put(crew.accountId(), leader.characterId);
        whisper(leader, main, "rival.help", crew, Map.of("quest", "zakum"));
        Crew asked = crew.withHelpAsked(now);
        save(asked);
        journal(leader.characterId, "asked " + main.getName() + " along for zakum");
        log.info("rival crew '{}' asked {} along for Zakum", crew.name(), main.getName());
    }

    /** El Nath's Door to Zakum, where the crew would meet its guest (the run itself is F7.4b, not built). */
    static final int ZAKUM_DOOR_MAP = 211042300;

    /** How a player's whispered answer to a help request reads. */
    enum Answer { YES, NO, OTHER }

    private static final Set<String> YES_WORDS = Set.of("y", "ye", "ya", "yes", "yeah", "yea", "yep", "yup", "sure", "ok",
            "okay", "k", "kk", "omw", "coming", "down", "lets", "sounds");
    private static final Set<String> NO_WORDS = Set.of("n", "no", "nah", "nope", "cant", "busy", "pass", "sorry");

    /** The first yes or no word of a reply ("ya omw" yes, "nah busy" no, "lol" neither). */
    static Answer answer(String text) {
        for (String word : text.toLowerCase(java.util.Locale.ROOT).replace("'", "").split("[^a-z]+")) {
            if (YES_WORDS.contains(word)) {
                return Answer.YES;
            }
            if (NO_WORDS.contains(word)) {
                return Answer.NO;
            }
        }
        return Answer.OTHER;
    }

    /**
     * A player whispered back to a member of their own rival crew (whisper intake, F5c: registered under topic "rival" at
     * integration). While the crew's help request is open, a yes gets "see u there" ({@code rival.reply.yes}) and a no
     * ends the request ({@code rival.reply.no}); anything else, a reply to a boast or a congratulation, gets a short
     * answer ({@code rival.reply}). True when it was handled: the citizen is in the player's crew.
     */
    public boolean replied(CitizenSession citizen, Character human, String text) {
        return replied(citizen, human, text, System.currentTimeMillis());
    }

    boolean replied(CitizenSession citizen, Character human, String text, long now) {
        if (!on() || citizen == null || human == null || text == null || text.isBlank()) {
            return false;
        }
        Crew crew = crewOf(citizen.characterId);
        if (crew == null || crew.accountId() != human.getAccountID()) {
            return false;
        }
        String trigger = "rival.reply";
        Integer asker = helpBy.get(crew.accountId());
        if (asker != null && config().RIVAL_HELP_REQUESTS_ENABLED
                && now - crew.helpAskedAtMs() < config().RIVALS_HELP_OPEN_MINUTES * MINUTE_MS) {
            switch (answer(text)) {
                case YES -> trigger = "rival.reply.yes";
                case NO -> {
                    trigger = "rival.reply.no";
                    helpBy.remove(crew.accountId());
                    if (population.needs() != null) {
                        population.needs().partyOver(asker, now); // the invitation is off the board
                    }
                }
                default -> { }
            }
        }
        whisper(citizen, human, trigger, crew, Map.of("quest", "zakum"));
        return true;
    }

    // ---- lines ------------------------------------------------------------------------------------------

    private ChatterEngine.Event event(CitizenSession self, String trigger, String mainName, Crew crew,
                                      Map<String, String> slots) {
        ChatterEngine.Event event = ChatterEngine.Event.of(trigger, self.mapId, self.channel).self(self)
                .slot("crew", crew.name());
        if (mainName != null) {
            event.subject(mainName, 0);
        }
        slots.forEach(event::slot);
        return event;
    }

    private void speak(CitizenSession self, String trigger, String mainName, Crew crew, Map<String, String> slots) {
        population.chatter().speakTo(event(self, trigger, mainName, crew, slots));
    }

    private boolean whisper(CitizenSession self, Character to, String trigger, Crew crew, Map<String, String> slots) {
        String line = population.chatter().compose(event(self, trigger, to.getName(), crew, slots));
        if (line == null) {
            return false;
        }
        population.chatter().whisper(self, to, line, "rival");
        return true;
    }

    private void journal(int citizenId, String line) {
        CitizenSession session = population.presence().session(citizenId);
        if (session != null) {
            session.journal.add(JOURNAL, line);
        }
    }

    private Character onlineCharacter(int characterId) {
        for (Character human : ports.onlineHumans()) {
            if (human != null && human.getId() == characterId) {
                return human;
            }
        }
        return null;
    }

    // ---- letters and reports ------------------------------------------------------------------------------

    /** A rival note for the main's letters (F3): who signs it and what it says; null with no news or the switch off. */
    public record Letter(String sender, String text) { }

    /**
     * The crew's news rows ({@code rival.news} details) since the main's last letters, as one short note signed by the
     * crew's leader: "hey Ann, Ironclads here. Easy Balrog 14:20 (beat your 16:05!), Kerning PQ 9:12. catch up lol".
     */
    public Letter letter(int mainId, String mainName, List<String> details) {
        if (!on() || details == null || details.isEmpty()) {
            return null;
        }
        start();
        Crew crew = crewOfMain(mainId);
        Citizen leader = crew == null ? null : population.citizen(crew.leaderId());
        if (crew == null || leader == null) {
            return null;
        }
        Map<String, long[]> best = new LinkedHashMap<>(); // quest -> {time, beaten}
        for (String detail : details) {
            String[] parts = detail.split("\\|");
            if (parts.length < 4 || !parts[0].equals(crew.name())) {
                continue;
            }
            try {
                long time = Long.parseLong(parts[2]);
                long beaten = Long.parseLong(parts[3]);
                long[] kept = best.get(parts[1]);
                if (kept == null || time < kept[0]) {
                    best.put(parts[1], new long[]{time, beaten});
                }
            } catch (NumberFormatException ignored) {
                // a hand-edited row
            }
        }
        if (best.isEmpty()) {
            return null;
        }
        List<String> runs = new ArrayList<>();
        for (Map.Entry<String, long[]> run : best.entrySet()) {
            String label = population.clears().labels().label(run.getKey()).toLowerCase(java.util.Locale.ROOT);
            runs.add(label + " in " + ClearLog.time(run.getValue()[0])
                    + (run.getValue()[1] > 0L ? " (beat ur " + ClearLog.time(run.getValue()[1]) + " :P)" : ""));
        }
        String text = "hey " + mainName + ", " + crew.name() + " here. while u were gone we did " + String.join(", ", runs)
                + ". catch up if u can lol";
        String sender = leader.name().length() <= 13 ? leader.name() : leader.name().substring(0, 13);
        return new Letter(sender, text.length() <= 200 ? text : text.substring(0, 197) + "...");
    }

    /** !rivals: a player's crew, its members and levels, its bests. */
    public List<String> report(int accountId) {
        List<String> out = new ArrayList<>();
        start();
        Crew crew = byAccount.get(accountId);
        if (crew == null) {
            out.add("no rival crew for account " + accountId + (on() ? "" : " (RIVALS_ENABLED is off)"));
            return out;
        }
        Map<Integer, int[]> progress = store.citizenProgress();
        out.add("crew '" + crew.name() + "' (" + crew.id() + ") guild " + crew.guildId() + " circle " + crew.circleId()
                + " racing character " + crew.mainId());
        for (int memberId : crew.members()) {
            Citizen citizen = population.citizen(memberId);
            out.add("  " + (citizen == null ? memberId : citizen.name()) + " lv" + levelOf(memberId, progress)
                    + (memberId == crew.leaderId() ? " (leader)" : "") + (loaded(memberId) != null ? " online" : "")
                    + (leaving.containsKey(memberId) ? " leaving" : ""));
        }
        Map<String, Long> bests = population.clears().crewBests(crew.id());
        List<String> times = new ArrayList<>();
        bests.forEach((quest, ms) -> times.add(population.clears().labels().label(quest) + " " + ClearLog.time(ms)
                + " (theirs " + ClearLog.time(population.clears().playerBest(crew.mainId(), quest)) + ")"));
        out.add("  bests: " + (times.isEmpty() ? "none yet" : String.join(", ", times)));
        return out;
    }

    /** Every crew, one line each, for !rivals with no name. */
    public List<String> summary() {
        start();
        List<String> out = new ArrayList<>();
        for (Crew crew : byAccount.values()) {
            out.add("'" + crew.name() + "' account " + crew.accountId() + " main " + crew.mainId() + ": "
                    + crew.members().size() + " members, guild " + crew.guildId());
        }
        if (out.isEmpty()) {
            out.add("no rival crews" + (on() ? "" : " (RIVALS_ENABLED is off)"));
        }
        return out;
    }

    /** For tests: the remembered main of an account. */
    int seenMain(int accountId) {
        return mainId(accountId);
    }

    Map<Integer, Long> leavingNow() {
        return new HashMap<>(leaving);
    }
}
