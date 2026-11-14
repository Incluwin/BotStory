package server.living.population.rivals;

import client.Character;
import config.PopulationConfig;
import config.YamlConfig;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.agents.capabilities.expedition.boss.AgentBossField;
import server.living.LivingWorld;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.resident.LivingTalk;
import server.living.story.DuelGround;
import server.living.store.LivingStore;
import server.maps.MapleMap;
import server.pvp.DuelField;
import server.pvp.DuelRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.function.ToDoubleFunction;

/**
 * Rival challenges (docs/PVP_PLAN.md V3.1-V3.2): a player's rival crew walks in on the field its main hunts and asks for a
 * duel. A yes fights it on a private copy of the field ({@link DuelGround}), then everyone stands where they stood; a no,
 * or no answer within RIVAL_CHALLENGE_ANSWER_SECONDS, costs nothing but a jab before they leave.
 *
 * <ul>
 *   <li><b>When</b>, checked on the crews' social minute ({@link #minute}): the main has hunted one field for
 *       {@link #FIELD_MS}, has {@link #MIN_HP_SHARE} of its HP, has no window or dialog open, two members are loaded on
 *       its channel, the last challenge is RIVAL_CHALLENGE_GAP_MINUTES back and fewer than RIVAL_CHALLENGE_DAILY_MAX
 *       came today (UTC); then one minute in {@link #MINUTES_PER_CHALLENGE} rolls one.</li>
 *   <li><b>Arrival</b>: up to {@link #MAX_SIDE} loaded members walk in through the presence scheduler's travel (a
 *       portal, while the field is watched), held there ({@link server.living.population.PresenceScheduler#hold}) until
 *       the challenge lets them go and they walk back to where they were.</li>
 *   <li><b>The answer</b>: yes or no in map chat ({@link #heard}), whispered to a member ({@link #whispered}), or
 *       {@code @duel yes|no} ({@link #command}).</li>
 *   <li><b>The sides</b>: the main, its active companions and its human party members on the field, up to
 *       {@link #MAX_SIDE}; the crew sends as many, those whose gear edge is nearest the player side's. The others watch
 *       and heckle on the copy.</li>
 * </ul>
 *
 * The hooks only queue what they heard; every change of a challenge happens on its own one-second step, off the client
 * and command threads that called them, and never under a character's lock.
 */
public final class RivalChallenges {
    private static final Logger log = LoggerFactory.getLogger(RivalChallenges.class);
    static final long MINUTE_MS = 60_000L;
    /** A main this long on one hunting field qualifies (the plan's 5 minutes). */
    static final long FIELD_MS = 5 * MINUTE_MS;
    /** And has at least this share of its max HP. */
    static final double MIN_HP_SHARE = 0.6d;
    /** About one challenge per this many qualifying minutes (the plan's one per two qualifying hours). */
    static final int MINUTES_PER_CHALLENGE = 120;
    /** At most this many fighters a side, and members walking in. */
    static final int MAX_SIDE = 3;
    /** The crew has this long to walk in. */
    static final long WALK_IN_MS = 3 * MINUTE_MS;
    /** After a no or the result, the crew lingers this long before it walks back. */
    static final long LINGER_MS = 20_000L;
    /** A duel that outlives its countdown and round by this much is stopped. */
    static final long FIGHT_SLACK_MS = MINUTE_MS;
    /** A watcher heckles at most this often. */
    static final long HECKLE_GAP_MS = 15_000L;
    static final long STEP_MS = 1_000L;
    /** The longest a member is held for one challenge; it is let go sooner, when the challenge is over. */
    static final long HOLD_MS = 15 * MINUTE_MS;
    static final String TOPIC = "rival";

    enum Stage { WALKING, ASKING, FIGHTING, LEAVING }

    /** Where a member was before it walked in, so it goes back there. */
    record Origin(int mapId, CitizenSession.Activity activity) { }

    /** What the minute reads of a main before it rolls. */
    record Facts(long onFieldMs, double hpShare, boolean busy, int loadedOnChannel, long sinceLastMs, int today) { }

    static final class Challenge {
        final int accountId;
        final int mainId;
        final String mainName;
        final Crew crew;
        final int mapId;
        final int channel;
        final boolean forced;
        final Map<Integer, Origin> members = new LinkedHashMap<>();
        volatile Stage stage = Stage.WALKING;
        volatile int leaderId;
        long deadlineMs;
        long heckleAtMs;
        DuelGround ground;
        DuelField field;
        final Set<Integer> followers = new HashSet<>();
        final Set<Integer> watchers = new HashSet<>();
        volatile DuelField.Result result;

        Challenge(int accountId, int mainId, String mainName, Crew crew, int mapId, int channel, boolean forced) {
            this.accountId = accountId;
            this.mainId = mainId;
            this.mainName = mainName;
            this.crew = crew;
            this.mapId = mapId;
            this.channel = channel;
            this.forced = forced;
        }
    }

    private record Heard(int accountId, int characterId, boolean yes) { }

    private final Population population;
    private final RivalCrews crews;
    private final Random random;
    private final Map<Integer, Challenge> byAccount = new ConcurrentHashMap<>();
    /** Account -> {field map id, since when} of its main's stint on one hunting field. */
    private final Map<Integer, long[]> stints = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastAt = new ConcurrentHashMap<>();
    /** Account -> {UTC day, challenges that day}. */
    private final Map<Integer, long[]> daily = new ConcurrentHashMap<>();
    private final Queue<Heard> heard = new ConcurrentLinkedQueue<>();
    private final Queue<Character> forced = new ConcurrentLinkedQueue<>();
    private final Object tickerLock = new Object();
    private ScheduledFuture<?> ticker;

    RivalChallenges(Population population, RivalCrews crews, Random random) {
        this.population = population;
        this.crews = crews;
        this.random = random;
    }

    private PopulationConfig config() {
        return population.config();
    }

    /** The switches a challenge the minute rolls needs (a GM's {@code !pvp challenge} needs only the crews). */
    public boolean on() {
        return config().RIVALS_ENABLED && config().RIVAL_CHALLENGES_ENABLED && YamlConfig.config.pvp.PVP_ENABLED;
    }

    // ---- the minute ---------------------------------------------------------------------------------------------

    /** The crews' social minute for one account whose main is online: may roll a challenge. */
    synchronized void minute(Crew crew, Character main, long now) {
        long onField = onField(crew.accountId(), main, now);
        if (!on() || byAccount.containsKey(crew.accountId())) {
            return;
        }
        Facts facts = new Facts(onField, hpShare(main), busy(main), loadedHere(crew, main).size(),
                now - lastAt.getOrDefault(crew.accountId(), Long.MIN_VALUE / 2), today(crew.accountId(), now));
        if (blocked(facts, config()) != null || random.nextInt(MINUTES_PER_CHALLENGE) != 0) {
            return;
        }
        begin(crew, main, false, now);
    }

    /** Why a main may not be challenged now, or null. */
    static String blocked(Facts facts, PopulationConfig config) {
        if (facts.onFieldMs() < FIELD_MS) {
            return "not five minutes on one hunting field";
        }
        if (facts.hpShare() < MIN_HP_SHARE) {
            return "under 60% HP";
        }
        if (facts.busy()) {
            return "a window or dialog is open";
        }
        if (facts.loadedOnChannel() < 2) {
            return "fewer than two crew members on the channel";
        }
        if (facts.sinceLastMs() < config.RIVAL_CHALLENGE_GAP_MINUTES * MINUTE_MS) {
            return "challenged within RIVAL_CHALLENGE_GAP_MINUTES";
        }
        if (facts.today() >= config.RIVAL_CHALLENGE_DAILY_MAX) {
            return "RIVAL_CHALLENGE_DAILY_MAX reached today";
        }
        return null;
    }

    /** How long the main has stood on this hunting field (0 off one); keeps the account's stint. */
    private long onField(int accountId, Character main, long now) {
        MapleMap map = main.getMap();
        if (!huntingField(map)) {
            stints.remove(accountId);
            return 0L;
        }
        long[] stint = stints.compute(accountId, (id, old) -> old != null && old[0] == map.getId()
                ? old : new long[] {map.getId(), now});
        return now - stint[1];
    }

    private boolean huntingField(MapleMap map) {
        return map != null && map.getEventInstance() == null && !map.isTown()
                && "field".equals(population.fields().kind(map.getId())) && !AgentBossField.near(map);
    }

    private static double hpShare(Character chr) {
        return chr.getHp() / (double) Math.max(1, chr.getCurrentMaxHp());
    }

    /** A dialog, trade, shop, mini-game or storage open: nobody walks up to ask then. */
    private static boolean busy(Character chr) {
        return !LivingTalk.mayShow(chr) || chr.getTrade() != null || chr.getShop() != null
                || chr.getPlayerShop() != null || chr.getMiniGame() != null || chr.getHiredMerchant() != null
                || chr.getStorage() != null && chr.getStorage().isOpen();
    }

    private int today(int accountId, long now) {
        long[] day = daily.get(accountId);
        return day == null || day[0] != now / (24 * 60 * MINUTE_MS) ? 0 : (int) day[1];
    }

    private void count(int accountId, long now) {
        long dayIndex = now / (24 * 60 * MINUTE_MS);
        daily.compute(accountId, (id, old) -> old == null || old[0] != dayIndex
                ? new long[] {dayIndex, 1} : new long[] {dayIndex, old[1] + 1});
        lastAt.put(accountId, now);
    }

    /** Members loaded on the main's channel and free to come: those on the main's map first. */
    private List<CitizenSession> loadedHere(Crew crew, Character main) {
        int channel = main.getClient() == null ? 0 : main.getClient().getChannel();
        List<CitizenSession> here = new ArrayList<>();
        List<CitizenSession> away = new ArrayList<>();
        for (int memberId : crew.members()) {
            CitizenSession session = crews.loaded(memberId);
            Character agent = session == null ? null : session.agent();
            if (session == null || session.channel != channel || crews.isLeaving(memberId) || agent == null
                    || !agent.isAlive() || DuelRegistry.inDuel(memberId) || population.presence().held(session)) {
                continue;
            }
            (session.mapId == main.getMapId() ? here : away).add(session);
        }
        here.addAll(away);
        return here;
    }

    // ---- a challenge's life --------------------------------------------------------------------------------------

    private void begin(Crew crew, Character main, boolean forcedByGm, long now) {
        List<CitizenSession> coming = loadedHere(crew, main);
        if (coming.isEmpty()) {
            return;
        }
        coming = coming.subList(0, Math.min(MAX_SIDE, coming.size()));
        int channel = main.getClient() == null ? 0 : main.getClient().getChannel();
        Challenge challenge = new Challenge(crew.accountId(), main.getId(), main.getName(), crew, main.getMapId(), channel,
                forcedByGm);
        for (CitizenSession session : coming) {
            challenge.members.put(session.characterId, new Origin(session.mapId, session.activity));
            population.presence().hold(session.characterId, now + HOLD_MS);
            if (session.mapId != main.getMapId()) {
                population.presence().travel(session, CitizenSession.Activity.TRAIN, main.getMapId(), now);
            }
        }
        challenge.leaderId = challenge.members.containsKey(crew.leaderId()) ? crew.leaderId()
                : coming.get(0).characterId;
        challenge.deadlineMs = now + WALK_IN_MS;
        byAccount.put(crew.accountId(), challenge);
        count(crew.accountId(), now);
        startTicker();
        log.info("Rival challenge: {} walks in on {} at map {} with {}{}", crew.name(), main.getName(), main.getMapId(),
                challenge.members.keySet(), forcedByGm ? " (GM)" : "");
    }

    /** Its own lock: a GM command or the minute starting the ticker never waits on a step that is warping people. */
    private void startTicker() {
        synchronized (tickerLock) {
            if (ticker == null) {
                ticker = TimerManager.getInstance().register(this::step, STEP_MS, STEP_MS);
            }
        }
    }

    private void stopTickerIfIdle() {
        synchronized (tickerLock) {
            if (byAccount.isEmpty() && forced.isEmpty() && ticker != null) {
                ticker.cancel(false);
                ticker = null;
            }
        }
    }

    /** One second of every challenge running; the ticker stops when none is left. */
    synchronized void step() {
        long now = System.currentTimeMillis();
        try {
            for (Character gm; (gm = forced.poll()) != null; ) {
                beginForced(gm, now);
            }
            for (Heard answer; (answer = heard.poll()) != null; ) {
                Challenge challenge = byAccount.get(answer.accountId());
                Character main = challenge == null ? null : online(challenge.mainId);
                if (challenge != null && challenge.stage == Stage.ASKING && main != null) {
                    if (answer.yes()) {
                        accept(challenge, main, now);
                    } else {
                        decline(challenge, main, now);
                    }
                }
            }
            for (Challenge challenge : new ArrayList<>(byAccount.values())) {
                try {
                    stepOne(challenge, now);
                } catch (RuntimeException failure) {
                    log.warn("rival challenge for {} failed; ending it", challenge.mainName, failure);
                    abort(challenge, now);
                }
            }
        } finally {
            stopTickerIfIdle();
        }
    }

    private void stepOne(Challenge challenge, long now) {
        Character main = online(challenge.mainId);
        switch (challenge.stage) {
            case WALKING -> {
                if (main == null || main.getMapId() != challenge.mapId) {
                    abort(challenge, now); // the player moved on before the crew got there: nobody saw anything
                    return;
                }
                List<Integer> here = arrived(challenge);
                if (here.size() == challenge.members.size() || now >= challenge.deadlineMs && !here.isEmpty()) {
                    if (!here.contains(challenge.leaderId)) {
                        challenge.leaderId = here.get(0);
                    }
                    ask(challenge, main, now);
                } else if (now >= challenge.deadlineMs) {
                    abort(challenge, now);
                }
            }
            case ASKING -> {
                if (main == null || main.getMapId() != challenge.mapId) {
                    linger(challenge, now); // walked away: as good as a no, and nobody is there to hear a jab
                } else if (now >= challenge.deadlineMs) {
                    decline(challenge, main, now);
                }
            }
            case FIGHTING -> {
                DuelField.Result result = challenge.result;
                if (result != null) {
                    afterFight(challenge, result, now);
                } else if (now >= challenge.deadlineMs) {
                    challenge.field.stop();
                } else {
                    heckle(challenge, now);
                }
            }
            case LEAVING -> {
                if (now >= challenge.deadlineMs) {
                    abort(challenge, now);
                }
            }
        }
    }

    private void ask(Challenge challenge, Character main, long now) {
        challenge.stage = Stage.ASKING;
        challenge.deadlineMs = now + config().RIVAL_CHALLENGE_ANSWER_SECONDS * 1000L;
        for (int memberId : arrived(challenge)) {
            population.whispers().expect(memberId, main.getId(), TOPIC, now); // a whispered yes to any of them counts
        }
        say(challenge, challenge.leaderId, "rival.challenge", Map.of("quest", crews.bestQuest(challenge.crew, main)));
        log.info("Rival challenge: {} asks {} (answer within {} s)", challenge.crew.name(), main.getName(),
                config().RIVAL_CHALLENGE_ANSWER_SECONDS);
    }

    private void decline(Challenge challenge, Character main, long now) {
        say(challenge, challenge.leaderId, "rival.decline", Map.of());
        log.info("Rival challenge: {} said no to {} (or let it lapse)", main.getName(), challenge.crew.name());
        linger(challenge, now);
    }

    private void linger(Challenge challenge, long now) {
        challenge.stage = Stage.LEAVING;
        challenge.deadlineMs = now + LINGER_MS;
    }

    private void accept(Challenge challenge, Character main, long now) {
        List<Character> crewHere = new ArrayList<>();
        for (int memberId : arrived(challenge)) {
            CitizenSession session = crews.loaded(memberId);
            Character agent = session == null ? null : session.agent();
            if (agent != null && agent.isAlive() && !DuelRegistry.inDuel(memberId)) {
                crewHere.add(agent);
            }
        }
        List<Character> players = playerSide(main);
        int count = Math.min(players.size(), crewHere.size());
        if (count == 0) {
            linger(challenge, now);
            return;
        }
        players = new ArrayList<>(players.subList(0, count));
        double edge = players.stream().mapToDouble(DuelField::gearEdgeOf).average().orElse(1d);
        List<Character> fighters = nearest(crewHere, count, DuelField::gearEdgeOf, edge);
        say(challenge, challenge.leaderId, "rival.accept", Map.of());
        DuelGround ground = DuelGround.open(main);
        if (ground == null) {
            log.warn("Rival challenge: no private copy of map {} for {}", challenge.mapId, main.getName());
            linger(challenge, now);
            return;
        }
        challenge.ground = ground;
        for (Character chr : players) {
            ground.enter(chr);
            if (chr != main && server.integration.AgentPresence.isAgent(chr)) {
                challenge.followers.add(chr.getId());
            }
        }
        for (Character chr : crewHere) {
            ground.enter(chr);
            if (!fighters.contains(chr)) {
                challenge.watchers.add(chr.getId());
            }
        }
        try {
            DuelField field = DuelRegistry.start(ground.map(), players, fighters);
            challenge.field = field;
            field.onEnd(result -> challenge.result = result);
        } catch (IllegalStateException refused) {
            log.warn("Rival challenge: the duel for {} would not start: {}", main.getName(), refused.getMessage());
            ground.close(challenge.followers);
            linger(challenge, now);
            return;
        }
        challenge.stage = Stage.FIGHTING;
        var pvp = YamlConfig.config.pvp;
        challenge.deadlineMs = now + (pvp.PVP_COUNTDOWN_SECONDS + pvp.PVP_ROUND_SECONDS) * 1000L + FIGHT_SLACK_MS;
        challenge.heckleAtMs = now + HECKLE_GAP_MS;
        log.info("Rival challenge: {} {} against {} {} on a copy of map {} (watching {})", main.getName(), names(players),
                challenge.crew.name(), names(fighters), challenge.mapId, challenge.watchers);
    }

    private void afterFight(Challenge challenge, DuelField.Result result, long now) {
        challenge.ground.close(challenge.followers);
        // side 1 is the crew's (DuelRegistry.start(map, players, crew))
        String trigger = result.winnerSide() == 1 ? "rival.win" : result.winnerSide() == 0 ? "rival.lose" : "rival.draw";
        int speaker = arrived(challenge).contains(challenge.leaderId) || arrived(challenge).isEmpty()
                ? challenge.leaderId : arrived(challenge).get(0);
        say(challenge, speaker, trigger, Map.of());
        log.info("Rival challenge: {} against {} ended: winner side {} ({})", challenge.mainName, challenge.crew.name(),
                result.winnerSide(), result.why());
        linger(challenge, now);
    }

    private void heckle(Challenge challenge, long now) {
        if (challenge.watchers.isEmpty() || now < challenge.heckleAtMs) {
            return;
        }
        challenge.heckleAtMs = now + HECKLE_GAP_MS + random.nextInt((int) HECKLE_GAP_MS);
        List<Integer> watching = new ArrayList<>(challenge.watchers);
        say(challenge, watching.get(random.nextInt(watching.size())), "rival.heckle", Map.of());
    }

    /** Lets every member go and walks it back where it was; the challenge is over. */
    private void abort(Challenge challenge, long now) {
        byAccount.remove(challenge.accountId, challenge);
        if (challenge.field != null && challenge.result == null) {
            challenge.field.stop();
        }
        if (challenge.ground != null) {
            challenge.ground.close(challenge.followers);
        }
        for (Map.Entry<Integer, Origin> member : challenge.members.entrySet()) {
            population.presence().release(member.getKey());
            CitizenSession session = crews.loaded(member.getKey());
            Origin origin = member.getValue();
            if (session != null && session.mapId == challenge.mapId && origin.mapId() != challenge.mapId) {
                population.presence().travel(session, origin.activity(), origin.mapId(), now);
            }
        }
    }

    // ---- what the player says -----------------------------------------------------------------------------------

    /** The main's map chat; true when it answered an open challenge (nobody else then answers it). */
    public boolean heard(Character human, String text) {
        Challenge challenge = human == null || text == null ? null : byAccount.get(human.getAccountID());
        if (challenge == null || challenge.stage != Stage.ASKING || human.getId() != challenge.mainId
                || human.getMapId() != challenge.mapId) {
            return false;
        }
        return queue(challenge, human, RivalCrews.answer(text));
    }

    /** A whisper to a crew member; true when it answered that crew's open challenge. */
    public boolean whispered(CitizenSession citizen, Character human, String text) {
        Challenge challenge = human == null || text == null || citizen == null ? null : byAccount.get(human.getAccountID());
        if (challenge == null || challenge.stage != Stage.ASKING || human.getId() != challenge.mainId
                || !challenge.members.containsKey(citizen.characterId)) {
            return false;
        }
        return queue(challenge, human, RivalCrews.answer(text));
    }

    private boolean queue(Challenge challenge, Character human, RivalCrews.Answer answer) {
        if (answer == RivalCrews.Answer.OTHER) {
            return false;
        }
        heard.add(new Heard(challenge.accountId, human.getId(), answer == RivalCrews.Answer.YES));
        return true;
    }

    /** {@code @duel yes|no}: what to tell the player. */
    public String command(Character human, boolean yes) {
        Challenge challenge = human == null ? null : byAccount.get(human.getAccountID());
        if (challenge == null || challenge.stage != Stage.ASKING || human.getId() != challenge.mainId) {
            return "Nobody has challenged you.";
        }
        heard.add(new Heard(challenge.accountId, human.getId(), yes));
        return null;
    }

    /** A GM's {@code !pvp challenge}: its crew walks in now, whatever the minute would say; what to tell the GM. */
    public String force(Character gm) {
        if (gm == null || !config().RIVALS_ENABLED) {
            return "Rival crews are off (population.RIVALS_ENABLED).";
        }
        Crew crew = crews.crewOfAccount(gm.getAccountID());
        if (crew == null) {
            return "This account has no rival crew yet.";
        }
        if (byAccount.containsKey(gm.getAccountID())) {
            return crew.name() + " is already on its way or asking.";
        }
        forced.add(gm);
        startTicker();
        return crew.name() + " will walk in if a member is loaded on this channel.";
    }

    private void beginForced(Character gm, long now) {
        Crew crew = crews.crewOfAccount(gm.getAccountID());
        if (crew == null || byAccount.containsKey(gm.getAccountID())) {
            return;
        }
        if (loadedHere(crew, gm).isEmpty()) {
            gm.yellowMessage("PvP: no member of " + crew.name() + " is loaded and free on this channel.");
            return;
        }
        begin(crew, gm, true, now);
    }

    /** What !pvp challenge status says. */
    public String report() {
        if (byAccount.isEmpty()) {
            return "No rival challenge running.";
        }
        StringBuilder out = new StringBuilder();
        for (Challenge challenge : byAccount.values()) {
            out.append(challenge.crew.name()).append(" on ").append(challenge.mainName).append(": ")
                    .append(challenge.stage).append(' ').append(challenge.members.keySet()).append("; ");
        }
        return out.toString();
    }

    // ---- pieces -------------------------------------------------------------------------------------------------

    /** Members standing on the challenge's field now (arrived, not still walking). */
    private List<Integer> arrived(Challenge challenge) {
        List<Integer> here = new ArrayList<>();
        for (int memberId : challenge.members.keySet()) {
            CitizenSession session = crews.loaded(memberId);
            Character agent = session == null ? null : session.agent();
            if (agent != null && agent.getMapId() == challenge.mapId && !session.onTheWay()) {
                here.add(memberId);
            }
        }
        return here;
    }

    /** The main, its active companions and its human party members on its map, up to MAX_SIDE, the main first. */
    private List<Character> playerSide(Character main) {
        List<Character> side = new ArrayList<>();
        side.add(main);
        LivingWorld world = LivingWorld.get();
        if (world != null && world.roster() != null) {
            for (LivingStore.RosterEntry entry : world.roster().roster(main)) {
                Character bot = entry.state() == LivingStore.RosterState.ACTIVE ? world.roster().companion(entry) : null;
                if (side.size() < MAX_SIDE && fit(bot, main)
                        && (world.commitments() == null || !world.commitments().isCommitted(bot.getId()))) {
                    side.add(bot);
                }
            }
        }
        for (Character member : main.getPartyMembersOnSameMap()) {
            if (side.size() < MAX_SIDE && member != main && !side.contains(member)
                    && !server.integration.AgentPresence.isAgent(member) && fit(member, main)) {
                side.add(member);
            }
        }
        return side;
    }

    private static boolean fit(Character chr, Character main) {
        return chr != null && chr.isAlive() && chr.getMap() == main.getMap() && chr.getEventInstance() == null
                && !DuelRegistry.inDuel(chr.getId());
    }

    /** The {@code count} of {@code candidates} whose {@code edge} is nearest {@code target}, nearest first. */
    static <T> List<T> nearest(List<T> candidates, int count, ToDoubleFunction<T> edge, double target) {
        List<T> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(candidate -> Math.abs(edge.applyAsDouble(candidate) - target)));
        return new ArrayList<>(sorted.subList(0, Math.min(count, sorted.size())));
    }

    private void say(Challenge challenge, int memberId, String trigger, Map<String, String> slots) {
        CitizenSession session = crews.loaded(memberId);
        if (session == null) {
            return;
        }
        String line = population.chatter().compose(crews.event(session, trigger, challenge.mainName, challenge.crew,
                slots));
        if (line != null) {
            population.chatter().say(session, line);
        }
    }

    private static String names(List<Character> side) {
        List<String> names = new ArrayList<>();
        side.forEach(chr -> names.add(chr.getName()));
        return names.toString();
    }

    private static Character online(int characterId) {
        for (World world : Server.getInstance().getWorlds()) {
            Character chr = world.getPlayerStorage().getCharacterById(characterId);
            if (chr != null) {
                return chr;
            }
        }
        return null;
    }
}
