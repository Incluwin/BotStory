package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.integration.AgentPresence;
import server.living.population.PopulationContent.Lobby;
import server.living.standing.Standing;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;

/**
 * Whispered invitations to a citizen run (docs/WORLD_SYSTEMS_PLAN.md N3.1, decision D6; {@code
 * CITIZEN_RUN_INVITES_ENABLED}). A citizen party forming at a lobby (CitizenPartyQuests) and short of its seats has one
 * of its citizens whisper a human it knows to come along: "doing kpq in a few, wanna come? need 2". The human must be
 * known (the citizen's warmth toward them REGULAR or better, no grudge), on the party's channel, of the lobby's band,
 * not in a party (one of only their own companions is fine: the line says the seat is theirs alone, and the run takes
 * them once they bench their crew; nobody's companions are benched or moved for it, N3.2), a PQ, a boss run or the
 * Cash Shop, not standing at the lobby (its own invite covers that), and logged
 * in for {@code CITIZEN_RUN_INVITE_LOGIN_QUIET_MS}. One invitation per human per {@code CITIZEN_RUN_INVITE_GAP_MS}
 * across all citizens; each "no" in a row doubles that gap (to 8x), a yes resets it. A yes whispered back gets the
 * leader's party invite by the seat rules of a whispered seat request (CitizenPartyQuests.acceptRunInvite): the seat
 * waits while they walk over and the leader says so at the lobby. A no gets a friendly line. Whether to ask at all is
 * the {@link InviteBandit}'s call (N3.6). Rides the party-quest tick; answers come through CitizenWhispers topic
 * {@link #TOPIC}. No model call: lines are chatter/runs.yaml.
 */
final class CitizenRunInvites {
    private static final Logger log = LoggerFactory.getLogger(CitizenRunInvites.class);
    /** The whisper thread an invitation opens (CitizenWhispers.onReply). */
    static final String TOPIC = "run";
    /** A forming party whispers at most one invitation this often. */
    static final long PARTY_INVITE_EVERY_MS = 60_000L;
    /** "no" answers in a row past which the gap stops doubling (8x). */
    static final int MAX_BACKOFF_STEPS = 3;

    private static final Set<String> YES_WORDS = Set.of("y", "ya", "ye", "yes", "yea", "yeah", "yep", "yup", "sure", "ok",
            "okay", "k", "kk", "omw", "coming", "otw", "down", "bet", "sounds", "lets", "go", "im", "inv", "invite");
    private static final Set<String> NO_WORDS = Set.of("n", "no", "nah", "nope", "cant", "busy", "pass", "sorry", "sry",
            "later", "nty", "maybe");

    /** How a whispered answer reads. */
    enum Answer { YES, NO, OTHER }

    /** An invitation out, waiting for its answer. */
    record Pending(int askerId, int leaderId, String questKey, InviteBandit.Context context, long atMs) { }

    /** A player with their crew out who said yes: the seat waits until they bench it, or until {@code untilMs}. */
    record Benching(int askerId, int leaderId, long untilMs) { }

    private final Population population;
    private final CitizenPartyQuests partyQuests;
    private final Supplier<Collection<Character>> humans;
    private final ToIntBiFunction<Integer, Integer> warmth;
    /** Character id -> the player whose companion it is (0: nobody's). */
    private final IntUnaryOperator ownerOf;
    /** Human id -> their yes while their crew is still out. */
    private final Map<Integer, Benching> benching = new HashMap<>();
    private final InviteBandit bandit = new InviteBandit();
    /** Human id -> when a citizen last whispered them an invitation. */
    private final Map<Integer, Long> askedAt = new HashMap<>();
    /** Human id -> "no" answers in a row. */
    private final Map<Integer, Integer> noStreak = new HashMap<>();
    /** Human id -> the invitation they are to answer. */
    private final Map<Integer, Pending> pending = new HashMap<>();
    /** "humanId:quest" -> when they last went into that quest with citizens. */
    private final Map<String, Long> lastRan = new HashMap<>();

    CitizenRunInvites(Population population, CitizenPartyQuests partyQuests) {
        this(population, partyQuests, () -> liveHumans(population), (citizen, human) -> liveWarmth(population, citizen, human),
                CitizenRunInvites::liveOwnerOf);
    }

    /** Tests bring the humans online, the warmth and the companions' owners. */
    CitizenRunInvites(Population population, CitizenPartyQuests partyQuests, Supplier<Collection<Character>> humans,
                      ToIntBiFunction<Integer, Integer> warmth, IntUnaryOperator ownerOf) {
        this.population = population;
        this.partyQuests = partyQuests;
        this.humans = humans;
        this.warmth = warmth;
        this.ownerOf = ownerOf;
    }

    private static int liveOwnerOf(int characterId) {
        server.living.LivingWorld world = server.living.LivingWorld.get();
        return world == null || world.directory() == null ? 0 : world.directory().ownerOfCharacter(characterId);
    }

    /** The player whose companion {@code characterId} is; 0 when it is nobody's. */
    int ownerOf(int characterId) {
        return ownerOf.applyAsInt(characterId);
    }

    /** True when {@code human} is in a party of only themselves and their own companions. */
    boolean crewOnly(Character human) {
        server.agents.integration.AgentPartySnapshot party = AgentPartyGatewayRuntime.party().snapshot(human);
        if (party == null) {
            return false;
        }
        for (server.agents.integration.AgentPartyMemberSnapshot member : party.members()) {
            if (member != null && member.id() != human.getId() && ownerOf(member.id()) != human.getId()) {
                return false;
            }
        }
        return true;
    }

    private static Collection<Character> liveHumans(Population population) {
        net.server.world.World world = population.gameWorld();
        return world == null ? List.of() : world.getPlayerStorage().getRealPlayers();
    }

    private static int liveWarmth(Population population, int citizenId, int humanId) {
        PersonMemories memories = population.memories();
        return memories == null ? 0 : memories.warmth(citizenId, humanId);
    }

    /** Invitations go out only while their answers reach citizens. */
    boolean on() {
        PopulationConfig config = population.config();
        return config.CITIZEN_RUN_INVITES_ENABLED && config.CITIZEN_WHISPERS_ENABLED;
    }

    InviteBandit bandit() {
        return bandit;
    }

    // ---- asking -------------------------------------------------------------------------------------

    /**
     * The forming party is short {@code open} seats: at most one invitation a minute and CITIZEN_RUN_INVITES_PER_PARTY
     * in all, one of {@code askers} (its citizens at the lobby, the leader first) whispers the known human it is
     * warmest toward, when the bandit finds that worth asking. True when one went out.
     */
    synchronized boolean ask(CitizenPartyQuests.Forming party, CitizenSession leader, List<CitizenSession> askers,
                             Lobby lobby, int open, long nowMs) {
        PopulationConfig config = population.config();
        if (!on() || open <= 0 || askers.isEmpty() || party.invitations >= config.CITIZEN_RUN_INVITES_PER_PARTY
                || party.lastInvitationAtMs > 0L && nowMs - party.lastInvitationAtMs < PARTY_INVITE_EVERY_MS) {
            return false;
        }
        record Candidate(Character human, CitizenSession asker, int warmth) { }
        List<Candidate> candidates = new ArrayList<>();
        for (Character human : humans.get()) {
            if (human == null || !gate(human, lobby, party.channel, party.mapId, nowMs).isEmpty()) {
                continue;
            }
            CitizenSession best = null;
            int bestWarmth = Integer.MIN_VALUE;
            boolean grudge = false;
            for (CitizenSession asker : askers) {
                int felt = warmth.applyAsInt(asker.characterId, human.getId());
                grudge |= Standing.grudge(felt);
                if (felt > bestWarmth) {
                    best = asker;
                    bestWarmth = felt;
                }
            }
            if (best != null && !grudge && Standing.ofCitizen(bestWarmth).atLeast(Standing.Rung.REGULAR)) {
                candidates.add(new Candidate(human, best, bestWarmth));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::warmth).reversed());
        for (Candidate candidate : candidates) {
            Character human = candidate.human();
            InviteBandit.Context context = InviteBandit.Context.of(nowMs, inTown(human),
                    Standing.ofCitizen(candidate.warmth()), lastRan.get(human.getId() + ":" + party.questKey));
            if (!bandit.worthAsking(context, config.CITIZEN_RUN_INVITE_EXPLORE, config.CITIZEN_RUN_INVITE_MIN_ACCEPT)) {
                continue;
            }
            CitizenSession asker = candidate.asker();
            ChatterEngine chatter = population.chatter();
            String trigger = crewOnly(human) ? "run.invite.crew" : asker == leader ? "run.invite" : "run.invite.member";
            String line = chatter.compose(ChatterEngine.Event.of(trigger, asker.mapId, asker.channel).self(asker)
                    .subject(human).need(open).slot("ch", String.valueOf(asker.channel)).slot("leader", leader.name()));
            if (line == null) {
                return false;
            }
            chatter.whisper(asker, human, line, TOPIC);
            askedAt.put(human.getId(), nowMs);
            pending.put(human.getId(), new Pending(asker.characterId, leader.characterId, party.questKey, context, nowMs));
            party.invitations++;
            party.lastInvitationAtMs = nowMs;
            asker.journal.add("social", "asked " + human.getName() + " along to " + lobby.name());
            log.info("citizen {} whispered {} an invitation to {} (ch{}, {} open, {})", asker.name(), human.getName(),
                    lobby.name(), party.channel, open, context.key());
            return true;
        }
        return false;
    }

    /**
     * Why {@code human} may not be asked to the party forming on {@code lobbyMapId} now; "" when they may (the warmth
     * and the bandit come after).
     */
    String gate(Character human, Lobby lobby, int channel, int lobbyMapId, long nowMs) {
        PopulationConfig config = population.config();
        if (AgentPresence.isAgent(human) || human.getClient() == null) {
            return "not a player online";
        }
        if (human.getClient().getChannel() != channel) {
            return "another channel";
        }
        if (human.getLevel() < lobby.levelMin() || human.getLevel() > lobby.levelMax()) {
            return "level";
        }
        if (human.getMapId() == lobbyMapId) {
            return "at the lobby"; // the lobby's own invite asks them
        }
        if (AgentPartyGatewayRuntime.party().snapshot(human) != null && !crewOnly(human)) {
            return "in a party"; // with other players or other owners' bots
        }
        if (human.getEventInstance() != null || AgentBossRunRegistry.forMember(human.getId()) != null) {
            return "in a party quest or boss run";
        }
        if (human.getCashShop() != null && human.getCashShop().isOpened()) {
            return "in the Cash Shop";
        }
        if (nowMs - human.getLoginTime() < config.CITIZEN_RUN_INVITE_LOGIN_QUIET_MS) {
            return "just logged in";
        }
        if (pending.containsKey(human.getId())) {
            return "an invitation is out";
        }
        if (benching.containsKey(human.getId())) {
            return "said yes, benching their crew";
        }
        Long last = askedAt.get(human.getId());
        if (last != null && nowMs - last < gapMs(human.getId())) {
            return "asked lately";
        }
        return "";
    }

    /** CITIZEN_RUN_INVITE_GAP_MS doubled per "no" in a row, to 8x. */
    long gapMs(int humanId) {
        int steps = Math.min(MAX_BACKOFF_STEPS, noStreak.getOrDefault(humanId, 0));
        return (long) population.config().CITIZEN_RUN_INVITE_GAP_MS << steps;
    }

    private static boolean inTown(Character human) {
        MapleMap map = human.getMap();
        int mapId = human.getMapId();
        return mapId / 1_000_000 == 910 || map != null && map.isTown(); // the Free Market rooms count as town
    }

    // ---- answers ------------------------------------------------------------------------------------

    /**
     * A whisper back to the citizen that asked (CitizenWhispers topic {@link #TOPIC}): a yes gets the leader's invite
     * and a line that says so, a no a friendly line; both teach the bandit. False for anything else (the citizen's
     * acknowledgement answers) or when no invitation from this citizen is out to them.
     */
    boolean replied(CitizenSession citizen, Character human, String text) {
        return replied(citizen, human, text, System.currentTimeMillis());
    }

    boolean replied(CitizenSession citizen, Character human, String text, long nowMs) {
        Pending ask;
        Answer answer = answer(text);
        synchronized (this) {
            ask = pending.get(human.getId());
            if (ask == null || ask.askerId() != citizen.characterId || answer == Answer.OTHER) {
                return false;
            }
            pending.remove(human.getId());
            bandit.answered(ask.context(), answer == Answer.YES);
            if (answer == Answer.YES) {
                noStreak.remove(human.getId());
            } else {
                noStreak.merge(human.getId(), 1, Integer::sum);
            }
        }
        if (answer == Answer.NO) {
            citizen.journal.add("social", human.getName() + " could not come along this time");
            reply(citizen, human, "whisper.run.no", null);
            log.info("citizen {}'s invitation: {} said no", citizen.name(), human.getName());
            return true;
        }
        if (crewOnly(human)) { // their crew is out: the seat waits while they bench it (nobody moves their companions)
            synchronized (this) {
                benching.put(human.getId(), new Benching(citizen.characterId, ask.leaderId(),
                        nowMs + population.config().CITIZEN_RUN_INVITE_ANSWER_MS));
            }
            reply(citizen, human, "whisper.run.yes.crew", null);
            log.info("citizen {}'s invitation: {} said yes, crew still out", citizen.name(), human.getName());
            return true;
        }
        CitizenPartyQuests.JoinAnswer joined = partyQuests.acceptRunInvite(human, ask.leaderId(), nowMs);
        CitizenSession leader = population.presence().session(ask.leaderId());
        String leaderName = leader == null ? null : leader.name();
        String trigger = switch (joined) {
            case INVITED -> citizen.characterId == ask.leaderId() ? "whisper.run.yes" : "whisper.run.yes.leader";
            case LEVEL -> "whisper.join.level";
            case IN_PARTY -> "whisper.join.party";
            case INVITE_OUT -> "whisper.join.wait";
            default -> "whisper.run.gone"; // filled up, went in, or gave up meanwhile
        };
        reply(citizen, human, trigger, leaderName);
        log.info("citizen {}'s invitation: {} said yes ({})", citizen.name(), human.getName(), joined);
        return true;
    }

    /**
     * Players who said yes with their crew out: one with no party any more gets the leader's invite now; one still in
     * their crew's party past the hold keeps no seat. On the party-quest tick.
     */
    void followUp(long nowMs) {
        List<Map.Entry<Integer, Benching>> due;
        synchronized (this) {
            due = new ArrayList<>(benching.entrySet());
        }
        for (Map.Entry<Integer, Benching> entry : due) {
            Character human = null;
            for (Character online : humans.get()) {
                if (online != null && online.getId() == entry.getKey()) {
                    human = online;
                }
            }
            Benching bench = entry.getValue();
            boolean free = human != null && AgentPartyGatewayRuntime.party().snapshot(human) == null;
            if (!free && human != null && nowMs < bench.untilMs()) {
                continue;
            }
            synchronized (this) {
                benching.remove(entry.getKey());
            }
            if (!free) {
                continue;
            }
            CitizenPartyQuests.JoinAnswer joined = partyQuests.acceptRunInvite(human, bench.leaderId(), nowMs);
            CitizenSession asker = population.presence().session(bench.askerId());
            if (asker != null) {
                reply(asker, human, joined == CitizenPartyQuests.JoinAnswer.INVITED ? "whisper.join.invited"
                        : "whisper.run.gone", null);
            }
            log.info("citizen run invitation: {} benched their crew ({})", human.getName(), joined);
        }
    }

    /** True while a player who said yes to the party {@code leaderId} leads is benching their crew: the seat is theirs. */
    synchronized boolean holdsSeat(int leaderId, long nowMs) {
        return benching.values().stream().anyMatch(bench -> bench.leaderId() == leaderId && nowMs < bench.untilMs());
    }

    /** Invitations not answered within CITIZEN_RUN_INVITE_ANSWER_MS are booked as not taken. */
    synchronized void expire(long nowMs) {
        long window = population.config().CITIZEN_RUN_INVITE_ANSWER_MS;
        pending.values().removeIf(ask -> {
            if (nowMs - ask.atMs() < window) {
                return false;
            }
            bandit.answered(ask.context(), false);
            return true;
        });
        long horizon = (long) population.config().CITIZEN_RUN_INVITE_GAP_MS << MAX_BACKOFF_STEPS;
        askedAt.values().removeIf(at -> nowMs - at >= horizon);
        lastRan.values().removeIf(at -> nowMs - at >= 24 * InviteBandit.RECENT_MS);
    }

    /** The human went into {@code questKey} with citizens (the bandit's "time since they last ran it"). */
    synchronized void ran(int humanId, String questKey, long nowMs) {
        lastRan.put(humanId + ":" + questKey, nowMs);
    }

    synchronized boolean pending(int humanId) {
        return pending.containsKey(humanId);
    }

    /** The first yes or no word of a reply ("ya omw" yes, "nah busy" no, "lol" neither). */
    static Answer answer(String text) {
        if (text == null) {
            return Answer.OTHER;
        }
        for (String word : text.toLowerCase(Locale.ROOT).replace("'", "").split("[^a-z]+")) {
            if (YES_WORDS.contains(word)) {
                return Answer.YES;
            }
            if (NO_WORDS.contains(word)) {
                return Answer.NO;
            }
        }
        return Answer.OTHER;
    }

    private void reply(CitizenSession citizen, Character human, String trigger, String leader) {
        ChatterEngine chatter = population.chatter();
        String line = chatter.compose(ChatterEngine.Event.of(trigger, citizen.mapId, citizen.channel).self(citizen)
                .subject(human).slot("ch", String.valueOf(citizen.channel)).slot("leader", leader));
        if (line != null) {
            chatter.whisperReply(citizen, human, line);
        }
    }

    /** One line for the party-quest status. */
    String status() {
        synchronized (this) {
            return bandit.status() + ", " + pending.size() + " waiting";
        }
    }
}
