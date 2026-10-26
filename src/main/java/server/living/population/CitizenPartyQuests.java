package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.monitoring.AgentSchedulerMetrics;
import server.agents.runtime.scheduler.AgentLoadSheddingLevel;
import server.agents.runtime.scheduler.AgentLoadSheddingState;
import server.integration.AgentPresence;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Lobby;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Citizens run party quests themselves (docs/CITIZEN_PARTY_QUESTS_PLAN.md). A citizen on a PQ session waits beside the
 * quest's NPC at its region's lobby. There the first opens a party and asks for people, the others whose level fits
 * join it, a party-less human whose level fits gets the invite before the last seat goes to a citizen, and once the
 * quest's minimum is in (its most, or no one more for a while) the party goes in by the quest's own admission, its
 * citizen leader leading. The quest's coordinator runs it; as it ends each citizen books it in its journal, says
 * something about it, and goes back to its day. Player first: never the last lobby a human could enter, never while
 * a human party waits there or an invite to a human is out, never a party a human leads. Bounded by
 * {@code PQ_RUNS_PER_CHANNEL} / {@code PQ_RUNS_MAX}, the Agent scheduler's load shedding and {@code PQ_RUNS_ENABLED}.
 * Ticked on the LOW_PRIORITY lane; the run's end, tap-outs and handbacks come from the Agent layer on any thread and
 * are answered from memory or queued for the tick.
 */
public final class CitizenPartyQuests {
    private static final Logger log = LoggerFactory.getLogger(CitizenPartyQuests.class);
    static final long TICK_MS = 10_000L;
    /** A party at its quest's minimum waits this long for one more before it goes in. */
    static final long FILL_WAIT_MS = 90_000L;
    /** The forming party asks for people at most this often. */
    static final long LF_EVERY_MS = 90_000L;
    /** A party still short this long starts calling citizens over, one each {@link #CALL_EVERY_MS}. */
    static final long CALL_AFTER_MS = 60_000L;
    static final long CALL_EVERY_MS = 60_000L;
    /** A citizen called over that has not reached the lobby this long is not counted on any more. */
    static final long COMING_MS = 5 * 60_000L;
    /** Citizens at a lobby invite one human at most this often. */
    static final long HUMAN_INVITE_EVERY_MS = 10 * 60_000L;
    /** A party the quest's admission refused this many times in a row gives up. */
    static final int ADMIT_ATTEMPTS = 3;
    /** A run that is gone from the quest's registry without its end being told is ended after this long. */
    static final long LOST_RUN_MS = 60_000L;
    /** After its run a citizen still down or in the instance this long logs off (its next load repairs it). */
    static final long RETURN_DEADLINE_MS = 5 * 60_000L;
    /** A party that gave up keeps out of party quests this long. */
    static final long GAVE_UP_COOLDOWN_MS = 10 * 60_000L;
    private static final int RECENT_RUNS = 10;

    /** A citizen party forming at one lobby, led by a citizen. */
    static final class Forming {
        final String questKey;
        final int channel;
        final int mapId;
        final int leaderId;
        final long formedAtMs;
        long lastLfAtMs;
        int calls;
        long lastCallAtMs;
        /** Citizen id -> when it was called over; dropped once it stands here or {@link #COMING_MS} passed. */
        final Map<Integer, Long> coming = new HashMap<>();
        /** When it first had its quest's minimum in; -1 while it has not. */
        long minReachedAtMs = -1L;
        int heldFor;
        long heldUntilMs;
        int refusals;
        final Set<Integer> toldProblem = new HashSet<>();

        Forming(String questKey, int channel, int mapId, int leaderId, long formedAtMs) {
            this.questKey = questKey;
            this.channel = channel;
            this.mapId = mapId;
            this.leaderId = leaderId;
            this.formedAtMs = formedAtMs;
        }
    }

    /** A citizen party's run: its quest session, its citizens and the humans who came along. */
    static final class Run {
        final String questKey;
        final String sessionId;
        final int channel;
        final int leaderId;
        final Map<Integer, String> citizens;
        final Map<Integer, String> humans;
        final long startedAtMs;
        volatile boolean over;
        long lostSinceMs;

        Run(String questKey, String sessionId, int channel, int leaderId, Map<Integer, String> citizens,
            Map<Integer, String> humans, long startedAtMs) {
            this.questKey = questKey;
            this.sessionId = sessionId;
            this.channel = channel;
            this.leaderId = leaderId;
            this.citizens = citizens;
            this.humans = humans;
            this.startedAtMs = startedAtMs;
        }
    }

    private final Population population;
    private final Map<String, CitizenPqDoor> doors;
    private final Random random;
    /** "channel:mapId" -> the party forming at that lobby; the tick's own. */
    private final Map<String, Forming> forming = new HashMap<>();
    /** Citizen id -> the run it is in now. */
    private final Map<Integer, Run> inRun = new ConcurrentHashMap<>();
    /** Citizen id -> when its run ended for it (the run's end, or its tap-out); it goes back to its day from here. */
    private final Map<Integer, Long> returning = new ConcurrentHashMap<>();
    /** Citizen id -> until when it plans no party quest. */
    private final Map<Integer, Long> coolUntil = new ConcurrentHashMap<>();
    /** Human id -> when citizens at a lobby last invited them. */
    private final Map<Integer, Long> humanInvitedAt = new HashMap<>();
    private final Queue<AgentPartyQuestHandback.RunEnded> ended = new ConcurrentLinkedQueue<>();
    private final Queue<Integer> tappingOut = new ConcurrentLinkedQueue<>();
    /** Quest key -> {runs, clears, failures} since boot. */
    private final Map<String, int[]> tally = new ConcurrentHashMap<>();
    private final Deque<String> recent = new ArrayDeque<>();

    CitizenPartyQuests(Population population) {
        this(population, CitizenPqDoor.shipped(), new Random(population.config().SEED ^ 0x9a27e5L));
    }

    /** Tests bring their own doors and draws. */
    CitizenPartyQuests(Population population, Map<String, CitizenPqDoor> doors, Random random) {
        this.population = population;
        this.doors = doors;
        this.random = random;
    }

    /** The quests {@code PQ_RUN_QUESTS} lets citizen parties go into, lower case; none while the switch is off. */
    static Set<String> quests(PopulationConfig config) {
        if (!config.PQ_RUNS_ENABLED || config.PQ_RUN_QUESTS == null) {
            return Set.of();
        }
        return Arrays.stream(config.PQ_RUN_QUESTS.split(",")).map(key -> key.trim().toLowerCase(Locale.ROOT))
                .filter(key -> !key.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    // ---- what the rest of the population asks ---------------------------------------------

    /** True while the citizen is in a run or on its way back from one: presence leaves its session alone. */
    public boolean holds(int characterId) {
        return inRun.containsKey(characterId) || returning.containsKey(characterId);
    }

    /** True while the citizen plans no party quest (its last run ended, or its party gave up, a short while ago). */
    boolean coolingDown(int characterId, long nowMs) {
        Long until = coolUntil.get(characterId);
        return until != null && nowMs < until;
    }

    /**
     * True when this session's party is the population's: in a run or back from one, or waiting at a lobby whose
     * quest citizens run (SocialFabric.partyUp leaves those alone).
     */
    boolean manages(CitizenSession session) {
        return holds(session.characterId) || session.activity == Activity.PQ && door(session.mapId) != null;
    }

    /** Where a citizen arriving at its lobby waits: beside the quest's NPC; null where citizens run no quest. */
    Point waitingSpot(CitizenSession session, MapleMap map) {
        CitizenPqDoor door = door(map.getId());
        return door == null ? null : door.beside(map, Math.floorMod(session.characterId, 6));
    }

    /** The door of the lobby on {@code mapId} when citizens may run its quest; else null. */
    private CitizenPqDoor door(int mapId) {
        Lobby lobby = population.fields().lobbyAt(mapId);
        return lobby == null || !quests(population.config()).contains(lobby.id()) ? null : doors.get(lobby.id());
    }

    // ---- the Agent layer's seams (any thread, from memory) ----------------------------------

    /** RELEASE for a citizen in a run or back from one: the population moves it, not the quest's town life. */
    public AgentPartyQuestHandback.Handback handback(int characterId) {
        return holds(characterId) ? AgentPartyQuestHandback.Handback.RELEASE : null;
    }

    /** A citizen that went down in its run and respawned outside it leaves the party at the next tick. */
    public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
        Run run = inRun.get(request.agentCharacterId());
        if (run == null || !run.sessionId.equals(request.sessionId()) || run.leaderId == request.agentCharacterId()) {
            return false;
        }
        tappingOut.add(request.agentCharacterId());
        return true;
    }

    /** A run ended; its citizens book it at the next tick. False when it was no citizen run. */
    public boolean runEnded(AgentPartyQuestHandback.RunEnded end) {
        for (Run run : inRun.values()) {
            if (run.sessionId.equals(end.sessionId())) {
                ended.add(end);
                return true;
            }
        }
        return false;
    }

    // ---- the tick ---------------------------------------------------------------------------

    public void tick() {
        tick(System.currentTimeMillis());
    }

    synchronized void tick(long nowMs) {
        try {
            finishEnded(nowMs);
            finishLost(nowMs);
            tapOuts(nowMs);
            goBackToDay(nowMs);
            coolUntil.values().removeIf(until -> nowMs >= until);
            humanInvitedAt.values().removeIf(at -> nowMs - at >= HUMAN_INVITE_EVERY_MS);
            if (!population.config().PQ_RUNS_ENABLED) {
                forming.clear();
                return;
            }
            Map<String, List<CitizenSession>> lobbies = waitingAtLobbies(nowMs);
            forming.keySet().retainAll(lobbies.keySet());
            for (Map.Entry<String, List<CitizenSession>> lobby : lobbies.entrySet()) {
                step(lobby.getKey(), lobby.getValue(), nowMs);
            }
        } catch (RuntimeException failure) {
            log.warn("citizen party-quest tick failed", failure);
        }
    }

    /**
     * Loaded citizens on a PQ session standing at a lobby whose quest citizens run, free for a party, by lobby. One
     * cooling down (back from a run, or its party gave up) is not, even while it still stands there on its old plan.
     */
    private Map<String, List<CitizenSession>> waitingAtLobbies(long nowMs) {
        Map<String, List<CitizenSession>> lobbies = new LinkedHashMap<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.virtual ? null : session.agent();
            if (agent == null || session.activity != Activity.PQ || session.afkUntilMs > nowMs || holds(session.characterId)
                    || coolingDown(session.characterId, nowMs) || agent.getHp() <= 0 || agent.getMapId() != session.mapId || door(session.mapId) == null
                    || PresenceScheduler.onSupplyTrip(session)) {
                continue;
            }
            lobbies.computeIfAbsent(session.channel + ":" + session.mapId, key -> new ArrayList<>()).add(session);
        }
        return lobbies;
    }

    private void step(String key, List<CitizenSession> waiting, long nowMs) {
        CitizenSession first = waiting.getFirst();
        Lobby lobby = population.fields().lobbyAt(first.mapId);
        CitizenPqDoor door = doors.get(lobby.id());
        Map<Integer, CitizenSession> here = new LinkedHashMap<>();
        waiting.forEach(session -> here.put(session.characterId, session));
        Forming party = forming.get(key);
        if (party != null && !leadsCitizenParty(here.get(party.leaderId), here)) {
            forming.remove(key);
            party = null;
        }
        if (party == null) {
            party = open(lobby, door, waiting, nowMs);
            if (party == null) {
                return;
            }
            forming.put(key, party);
        }
        CitizenSession leader = here.get(party.leaderId);
        Character leaderChr = leader.agent();
        MapleMap map = leaderChr.getMap();
        List<Character> humansHere = humansWithoutParty(map, lobby);
        boolean held = holdingSeat(party, nowMs);
        int size = members(leaderChr).size();
        if (!held && size < door.preferredSeats()) {
            held = inviteHuman(party, leader, lobby, humansHere, nowMs);
        }
        int open = door.preferredSeats() - size - (held ? 1 : 0);
        for (CitizenSession loner : waiting) {
            if (open <= 0) {
                break;
            }
            Character agent = loner.agent();
            if (loner == leader || party(agent) != null || !fits(agent, lobby)) {
                continue;
            }
            if (AgentPartyLifecycleService.joinAgentToLeaderParty(leaderChr, agent) == JoinOutcome.JOINED) {
                open--;
                loner.journal.add("party", "joined " + leader.name() + "'s " + lobby.name() + " party");
                say("pq.join", loner, null, 0);
            }
        }
        List<AgentPartyMemberSnapshot> members = members(leaderChr);
        size = members.size();
        if (size < door.minSeats()) {
            party.minReachedAtMs = -1L;
            if (nowMs - party.formedAtMs >= population.config().PQ_FORM_PATIENCE_MS) {
                giveUp(party, here, members, "nobody came", nowMs);
                return;
            }
            if (nowMs - party.lastLfAtMs >= LF_EVERY_MS) {
                party.lastLfAtMs = nowMs;
                say("pq.lf", leader, null, door.minSeats() - size);
            }
            callOver(party, leader, lobby, here, door.preferredSeats() - size, nowMs);
            return;
        }
        if (party.minReachedAtMs < 0L) {
            party.minReachedAtMs = nowMs;
        }
        if (held) {
            return; // never in while an invite to a human is out: the seat is theirs until they answer
        }
        if (size < door.preferredSeats() && nowMs - party.minReachedAtMs < FILL_WAIT_MS) {
            if (nowMs - party.lastLfAtMs >= LF_EVERY_MS) {
                party.lastLfAtMs = nowMs;
                say("pq.lf", leader, null, door.preferredSeats() - size);
            }
            callOver(party, leader, lobby, here, door.preferredSeats() - size, nowMs);
            return;
        }
        String blocked = blocker(party, door, lobby, leader, members, here, map);
        if (!blocked.isEmpty()) {
            log.debug("citizen {} party at {} waits: {}", leader.name(), lobby.name(), blocked);
            if (nowMs - party.formedAtMs >= population.config().PQ_FORM_PATIENCE_MS) {
                giveUp(party, here, members, blocked, nowMs);
            }
            return;
        }
        goIn(key, party, door, lobby, leader, members, here, nowMs);
    }

    /** True while {@code leader} stands here and leads a party (or none yet) with no human leading it. */
    private static boolean leadsCitizenParty(CitizenSession leader, Map<Integer, CitizenSession> here) {
        if (leader == null) {
            return false;
        }
        AgentPartySnapshot party = party(leader.agent());
        if (party == null) {
            return true;
        }
        return party.members().stream().anyMatch(member -> member != null && member.leader()
                && member.id() == leader.characterId && here.containsKey(member.id()));
    }

    /**
     * The party forming here: the biggest party a citizen standing here leads with no human leading it, else one the
     * first citizen with none opens. Citizens in a party a human leads are that human's business.
     */
    private Forming open(Lobby lobby, CitizenPqDoor door, List<CitizenSession> waiting, long nowMs) {
        CitizenSession best = null;
        int bestSize = 0;
        Map<Integer, CitizenSession> here = new LinkedHashMap<>();
        waiting.forEach(session -> here.put(session.characterId, session));
        for (CitizenSession session : waiting) {
            AgentPartySnapshot party = party(session.agent());
            if (party != null && leadsCitizenParty(session, here) && party.members().size() > bestSize
                    && party.members().size() <= door.maxSeats()) {
                best = session;
                bestSize = party.members().size();
            }
        }
        if (best == null) {
            for (CitizenSession session : waiting) {
                if (party(session.agent()) == null && fits(session.agent(), lobby)) {
                    best = session;
                    break;
                }
            }
        }
        if (best == null) {
            return null;
        }
        Forming party = new Forming(lobby.id(), best.channel, best.mapId, best.characterId, nowMs);
        party.lastLfAtMs = nowMs;
        say("pq.lf", best, null, door.minSeats() - Math.max(1, bestSize));
        return party;
    }

    /**
     * The human invited is still to answer, within the hold: their seat stays open. Once the hold is over the invite is
     * withdrawn, so a late yes never makes a fifth in a party that has gone in meanwhile.
     */
    private boolean holdingSeat(Forming party, long nowMs) {
        if (party.heldFor == 0) {
            return false;
        }
        boolean pending = AgentPartyGatewayRuntime.party().hasPendingPartyInvite(party.heldFor);
        if (pending && nowMs < party.heldUntilMs) {
            return true;
        }
        if (pending) {
            AgentPartyGatewayRuntime.party().cancelPartyInvite(party.heldFor);
        }
        party.heldFor = 0;
        return false;
    }

    /** One party-less human here whose level fits gets the leader's invite, once per {@link #HUMAN_INVITE_EVERY_MS}. */
    private boolean inviteHuman(Forming party, CitizenSession leader, Lobby lobby, List<Character> humans, long nowMs) {
        for (Character human : humans) {
            Long last = humanInvitedAt.get(human.getId());
            if (last != null && nowMs - last < HUMAN_INVITE_EVERY_MS) {
                continue;
            }
            humanInvitedAt.put(human.getId(), nowMs);
            Character leaderChr = leader.agent();
            if (party(leaderChr) == null && !AgentPartyGatewayRuntime.party().createAgentParty(leaderChr)) {
                return false;
            }
            if (!AgentPartyGatewayRuntime.party().invitePartyMember(leaderChr, human)) {
                return false;
            }
            party.heldFor = human.getId();
            party.heldUntilMs = nowMs + population.config().PQ_HUMAN_SEAT_HOLD_MS;
            population.chatter().event(ChatterEngine.Event.of("party.invite", leader.mapId, leader.channel)
                    .self(leader).subject(human));
            leader.journal.add("social", "invited " + human.getName() + " to " + lobby.name());
            return true;
        }
        return false;
    }

    /** Humans on the lobby with no party whose level fits it. */
    private static List<Character> humansWithoutParty(MapleMap map, Lobby lobby) {
        List<Character> humans = new ArrayList<>();
        for (Character chr : map.getAllPlayers()) {
            if (!AgentPresence.isAgent(chr) && party(chr) == null && fits(chr, lobby) && chr.getEventInstance() == null) {
                humans.add(chr);
            }
        }
        return humans;
    }

    /** Parties on the lobby with a human in them, not {@code ours}, waiting outside the quest. */
    private static int humanPartiesWaiting(MapleMap map, int ours) {
        Set<Integer> parties = new HashSet<>();
        for (Character chr : map.getAllPlayers()) {
            AgentPartySnapshot party = AgentPresence.isAgent(chr) ? null : party(chr);
            if (party != null && party.id() != ours && chr.getEventInstance() == null) {
                parties.add(party.id());
            }
        }
        return parties.size();
    }

    /**
     * Why the party may not go in now, or "" when it may: the caps, the scheduler, its members (citizens waiting here
     * and humans at the entry NPC, alive, the quest's own words; never another Agent, such as a human's companion),
     * the quest's registry, and the lobby kept for humans and any human party waiting there.
     */
    private String blocker(Forming party, CitizenPqDoor door, Lobby lobby, CitizenSession leader,
                           List<AgentPartyMemberSnapshot> members, Map<Integer, CitizenSession> here, MapleMap map) {
        PopulationConfig config = population.config();
        int onChannel = 0;
        Set<String> live = new HashSet<>();
        for (Run run : inRun.values()) {
            if (live.add(run.sessionId) && run.channel == leader.channel) {
                onChannel++;
            }
        }
        if (onChannel >= config.PQ_RUNS_PER_CHANNEL) {
            return "channel cap " + config.PQ_RUNS_PER_CHANNEL + " reached";
        }
        if (live.size() >= config.PQ_RUNS_MAX) {
            return "cap " + config.PQ_RUNS_MAX + " reached";
        }
        if (!schedulerCalm()) {
            return "the Agent scheduler is shedding load";
        }
        Character leaderChr = leader.agent();
        for (AgentPartyMemberSnapshot member : members) {
            Character chr = member == null ? null : memberCharacter(member.id(), map);
            if (chr == null || chr.getHp() <= 0 || chr.getMapId() != map.getId()) {
                return (member == null ? "a member" : member.name()) + " is not here";
            }
            boolean human = !AgentPresence.isAgent(chr);
            if (!human && !here.containsKey(chr.getId())) {
                return chr.getName() + " is an Agent that is not waiting here";
            }
            if (human && !door.atEntry(map, chr.getPosition())) {
                if (party.toldProblem.add(chr.getId())) {
                    tellHuman(leader, chr, "entrance", lobby);
                }
                return chr.getName() + " is not at the entry NPC";
            }
            String problem = door.memberProblem(chr, chr.getId() == leader.characterId);
            if (!problem.isEmpty()) {
                if (human && party.toldProblem.add(-chr.getId())) {
                    tellHuman(leader, chr, problem, lobby);
                }
                return problem;
            }
        }
        String registry = door.registrationBlocker(leader.characterId,
                members.stream().map(AgentPartyMemberSnapshot::id).toList());
        if (!registry.isEmpty()) {
            return registry;
        }
        AgentPartySnapshot ours = party(leaderChr);
        if (!door.lobbyFree(leaderChr, humanPartiesWaiting(map, ours == null ? 0 : ours.id()))) {
            return "no lobby free beside the humans'";
        }
        return "";
    }

    /** NORMAL load shedding on every scheduler shard. */
    static boolean schedulerCalm() {
        for (AgentLoadSheddingState state : AgentSchedulerMetrics.loadSheddingSnapshot().shardStates().values()) {
            if (state.level() != AgentLoadSheddingLevel.NORMAL) {
                return false;
            }
        }
        return true;
    }

    private void goIn(String key, Forming party, CitizenPqDoor door, Lobby lobby, CitizenSession leader,
                      List<AgentPartyMemberSnapshot> members, Map<Integer, CitizenSession> here, long nowMs) {
        Character leaderChr = leader.agent();
        MapleMap map = leaderChr.getMap();
        List<Character> characters = new ArrayList<>();
        Map<Integer, String> citizens = new LinkedHashMap<>();
        Map<Integer, String> humans = new LinkedHashMap<>();
        for (AgentPartyMemberSnapshot member : members) {
            Character chr = memberCharacter(member.id(), map);
            characters.add(chr);
            CitizenSession session = here.get(member.id());
            if (session != null) {
                citizens.put(member.id(), session.name());
                population.presence().holdForRun(session);
            } else {
                humans.put(member.id(), chr.getName()); // the blocker let no other Agent through
            }
        }
        CitizenPqDoor.Entered entered = door.admit(leaderChr, characters, random.nextLong(), nowMs);
        if (!entered.success()) {
            party.refusals++;
            log.info("citizen pq run refused: quest={} leader={} members={} attempt={} reason={}", lobby.id(),
                    leader.name(), names(citizens, humans), party.refusals, entered.message());
            if (party.refusals >= ADMIT_ATTEMPTS) {
                giveUp(party, here, members, entered.message(), nowMs);
            }
            return;
        }
        forming.remove(key);
        Run run = new Run(lobby.id(), entered.sessionId(), leader.channel, leader.characterId, citizens, humans, nowMs);
        for (int id : citizens.keySet()) {
            inRun.put(id, run);
            CitizenSession session = here.get(id);
            session.journal.add("pq", "went into " + lobby.name() + " with " + others(run, id));
        }
        tally.computeIfAbsent(lobby.id(), quest -> new int[3])[0]++;
        log.info("citizen pq run in: quest={} session={} channel={} leader={} members={}", lobby.id(), run.sessionId,
                run.channel, leader.name(), names(citizens, humans));
        say("pq.go", leader, null, 0);
    }

    /** The citizens leave the party, say so, keep out of party quests a while, and plan something else. */
    private void giveUp(Forming party, Map<Integer, CitizenSession> here, List<AgentPartyMemberSnapshot> members,
                        String why, long nowMs) {
        forming.remove(party.channel + ":" + party.mapId);
        CitizenSession leader = here.get(party.leaderId);
        if (leader != null) {
            say("pq.giveup", leader, null, 0);
        }
        log.info("citizen {} party at map {} gives up: {}", leader == null ? party.leaderId : leader.name(),
                party.mapId, why);
        Set<Integer> ids = new HashSet<>();
        members.forEach(member -> ids.add(member.id()));
        ids.add(party.leaderId);
        for (int id : ids) {
            CitizenSession session = here.get(id);
            if (session == null) {
                continue;
            }
            AgentPartyLifecycleService.leaveAgentParty(session.agent());
            coolUntil.put(id, nowMs + GAVE_UP_COOLDOWN_MS);
            population.presence().replan(session, nowMs);
        }
    }

    /**
     * A party short of its seats calls one citizen over, from {@link #CALL_AFTER_MS} after it formed, at most one each
     * {@link #CALL_EVERY_MS} and {@code PQ_CALLS_PER_PARTY} in all, and never while as many are on their way as it
     * needs: first a loaded circle or guild mate whose level fits, training or in town (the leader asks in guild chat
     * and it answers), else a citizen of the lobby's region idling in town whose PQ band this lobby is, who just walks
     * over as a player looking for a party does. Live, a party that only ever called one mate gave up "nobody came".
     */
    private void callOver(Forming party, CitizenSession leader, Lobby lobby, Map<Integer, CitizenSession> here, int need,
                          long nowMs) {
        party.coming.entrySet().removeIf(called -> here.containsKey(called.getKey())
                || nowMs - called.getValue() >= COMING_MS);
        if (nowMs - party.formedAtMs < CALL_AFTER_MS || nowMs - party.lastCallAtMs < CALL_EVERY_MS
                || party.calls >= population.config().PQ_CALLS_PER_PARTY || party.coming.size() >= need) {
            return;
        }
        Character leaderChr = leader.agent();
        List<CitizenSession> mates = new ArrayList<>();
        List<CitizenSession> townsfolk = new ArrayList<>();
        for (CitizenSession session : population.presence().sessions()) {
            Character agent = session.virtual ? null : session.agent();
            if (agent == null || session == leader || here.containsKey(session.characterId)
                    || party.coming.containsKey(session.characterId) || !free(session, agent, leader, nowMs)) {
                continue;
            }
            boolean mate = leader.citizen.circleId() != 0 && session.citizen.circleId() == leader.citizen.circleId()
                    || leaderChr.getGuildId() > 0 && agent.getGuildId() == leaderChr.getGuildId();
            if (mate && (session.activity == Activity.TRAIN || session.activity == Activity.TOWN) && fits(agent, lobby)) {
                mates.add(session);
            } else if (session.activity == Activity.TOWN && lobby.equals(population.fields().lobbyFor(session.citizen,
                    agent.getLevel()))) {
                townsfolk.add(session);
            }
        }
        boolean mate = !mates.isEmpty();
        List<CitizenSession> pool = mate ? mates : townsfolk;
        if (pool.isEmpty()) {
            return;
        }
        CitizenSession called = pool.get(random.nextInt(pool.size()));
        party.calls++;
        party.lastCallAtMs = nowMs;
        party.coming.put(called.characterId, nowMs);
        if (mate) {
            say("pq.call", leader, null, need);
            say("pq.call.answer", called, leaderChr, 0);
            called.journal.add("social", leader.name() + " called it to " + lobby.name());
        } else {
            called.journal.add("pq", "went to " + lobby.name() + " to find a party");
        }
        log.info("citizen {} party at {} calls {} ({})", leader.name(), lobby.name(), called.name(),
                mate ? "mate" : "townsfolk");
        population.presence().travel(called, Activity.PQ, lobby.mapId(), nowMs);
    }

    /** A loaded citizen on the leader's channel with nothing else on: no run, cooldown, AFK, party or supply trip. */
    private boolean free(CitizenSession session, Character agent, CitizenSession leader, long nowMs) {
        return session.channel == leader.channel && !holds(session.characterId) && !coolingDown(session.characterId, nowMs)
                && session.afkUntilMs <= nowMs && party(agent) == null && !PresenceScheduler.onSupplyTrip(session);
    }

    /** A human of the party who cannot go in hears why from the leader, once. */
    private void tellHuman(CitizenSession leader, Character human, String problem, Lobby lobby) {
        String line;
        if (problem.contains("level")) {
            line = "{name} " + lobby.name().toLowerCase(Locale.ROOT) + " is lv" + lobby.levelMin() + "-" + lobby.levelMax()
                    + " only, sorry";
        } else if (problem.contains("capacity")) {
            line = "{name} make some room in ur use and etc first";
        } else if (problem.contains("entrance")) {
            line = "{name} come over by the npc";
        } else {
            return;
        }
        population.chatter().event(ChatterEngine.Event.of("pq.wait", leader.mapId, leader.channel).self(leader)
                .subject(human).withText(line));
    }

    // ---- after the run ----------------------------------------------------------------------

    private void finishEnded(long nowMs) {
        AgentPartyQuestHandback.RunEnded end;
        while ((end = ended.poll()) != null) {
            Run run = runOf(end.sessionId());
            if (run != null) {
                finish(run, end.success(), end.rerolled() ? "rerolled" : end.reason(), nowMs);
            }
        }
    }

    /** A run gone from the quest's registry whose end was never told ends after {@link #LOST_RUN_MS}. */
    private void finishLost(long nowMs) {
        Set<Run> runs = new HashSet<>(inRun.values());
        for (Run run : runs) {
            CitizenPqDoor door = doors.get(run.questKey);
            boolean held = door != null && run.citizens.keySet().stream().anyMatch(id -> door.holds(id, run.sessionId));
            if (held) {
                run.lostSinceMs = 0L;
            } else if (run.lostSinceMs == 0L) {
                run.lostSinceMs = nowMs;
            } else if (nowMs - run.lostSinceMs >= LOST_RUN_MS) {
                finish(run, false, "the run was lost", nowMs);
            }
        }
    }

    private Run runOf(String sessionId) {
        for (Run run : inRun.values()) {
            if (run.sessionId.equals(sessionId)) {
                return run;
            }
        }
        return null;
    }

    /** The run is booked, talked about, and each citizen still in it goes back to its day. */
    private void finish(Run run, boolean cleared, String reason, long nowMs) {
        if (run.over) {
            return;
        }
        run.over = true;
        boolean rerolled = "rerolled".equals(reason);
        int[] counts = tally.computeIfAbsent(run.questKey, quest -> new int[3]);
        if (cleared) {
            counts[1]++;
        } else if (!rerolled) {
            counts[2]++;
        }
        String quest = run.questKey.toUpperCase(Locale.ROOT);
        String outcome = cleared ? "cleared" : rerolled ? "rerolled" : "failed";
        long minutes = Math.max(0L, (nowMs - run.startedAtMs) / 60_000L);
        remember(quest + " " + outcome + " in " + minutes + " min: " + names(run.citizens, run.humans)
                + (cleared ? "" : " (" + reason + ")"));
        log.info("citizen pq run: quest={} session={} outcome={} minutes={} members={} reason={}", run.questKey,
                run.sessionId, outcome, minutes, names(run.citizens, run.humans), cleared ? "-" : reason);
        if (!rerolled) {
            rememberHumans(run, cleared);
        }
        List<CitizenSession> stayed = new ArrayList<>();
        for (int id : run.citizens.keySet()) {
            if (inRun.remove(id, run)) {
                returning.put(id, nowMs);
                coolUntil.put(id, nowMs + population.config().PQ_RUN_COOLDOWN_MS);
                CitizenSession session = population.presence().session(id);
                if (session != null) {
                    stayed.add(session);
                    session.journal.add("pq", (cleared ? "cleared " : rerolled ? "left " : "failed ") + quest
                            + " with " + others(run, id));
                }
            }
        }
        if (stayed.isEmpty() || rerolled) {
            return;
        }
        Collections.shuffle(stayed, random);
        CitizenSession speaker = stayed.getFirst();
        say(cleared ? "pq.clear" : "pq.fail", speaker, null, 0);
        if (stayed.size() > 1 && random.nextInt(100) < 60) {
            say(cleared ? "pq.clear" : "pq.fail", stayed.get(1), null, 0);
        }
        if (cleared) {
            CitizenSession leader = population.presence().session(run.leaderId);
            for (int humanId : run.humans.keySet()) {
                Character human = memberCharacter(humanId, null);
                if (leader != null && human != null) {
                    say("pq.thanks", leader, human, 0);
                }
            }
        }
    }

    /**
     * Each citizen of the run remembers each human who came along (docs/CITIZEN_PERSON_MEMORY_PLAN.md): one still in the
     * run's party at its end cleared or failed it with them; one online and out of the party left it, a let-down when
     * the run then failed. A human offline at the end is neither (a crash is no let-down), nor is anyone when no citizen
     * of the run is in its party any more.
     */
    void rememberHumans(Run run, boolean cleared) {
        PersonMemories memories = population.memories();
        if (memories == null || run.humans.isEmpty()) {
            return;
        }
        int partyId = 0;
        for (int id : run.citizens.keySet()) {
            AgentPartySnapshot party = party(memberCharacter(id, null));
            if (party != null && (partyId == 0 || id == run.leaderId)) {
                partyId = party.id();
            }
        }
        if (partyId == 0) {
            return;
        }
        for (int humanId : run.humans.keySet()) {
            Character human = memberCharacter(humanId, null);
            if (human == null) {
                continue;
            }
            AgentPartySnapshot theirs = party(human);
            boolean stayed = theirs != null && theirs.id() == partyId;
            PersonMemory.Kind kind = stayed ? (cleared ? PersonMemory.Kind.PQ_CLEAR : PersonMemory.Kind.PQ_FAIL)
                    : cleared ? null : PersonMemory.Kind.PQ_LEFT;
            if (kind == null) {
                continue;
            }
            for (int citizenId : run.citizens.keySet()) {
                memories.record(citizenId, humanId, kind, 0, run.questKey, 1);
            }
        }
    }

    /** Citizens that went down in their run step out of its party, say so, and go back to their day. */
    private void tapOuts(long nowMs) {
        Integer id;
        while ((id = tappingOut.poll()) != null) {
            Run run = inRun.get(id);
            if (run == null || !inRun.remove(id, run)) {
                continue;
            }
            returning.put(id, nowMs);
            coolUntil.put(id, nowMs + population.config().PQ_RUN_COOLDOWN_MS);
            CitizenSession session = population.presence().session(id);
            if (session != null) {
                say("pq.down", session, null, 0);
                AgentPartyLifecycleService.leaveAgentParty(session.agent());
                session.journal.add("pq", "died in " + run.questKey.toUpperCase(Locale.ROOT) + " and left the party");
            }
            log.info("citizen pq run: {} taps out of {} ({})", run.citizens.get(id), run.sessionId, run.questKey);
        }
    }

    /**
     * Each citizen back from a run, once it stands alive outside the instance, plans its next activity, or logs off
     * if its session ran out meanwhile. One still down or inside after {@link #RETURN_DEADLINE_MS} logs off.
     */
    private void goBackToDay(long nowMs) {
        for (Map.Entry<Integer, Long> back : new ArrayList<>(returning.entrySet())) {
            int id = back.getKey();
            CitizenSession session = population.presence().session(id);
            Character agent = session == null ? null : session.agent();
            if (agent == null) {
                returning.remove(id);
                continue;
            }
            boolean out = agent.getHp() > 0 && agent.getEventInstance() == null;
            boolean late = nowMs - back.getValue() >= RETURN_DEADLINE_MS;
            if (!out && !late) {
                continue;
            }
            returning.remove(id);
            if (!out || nowMs >= session.endsAtMs) {
                population.presence().logout(session, out ? "session over" : "still in its party quest");
            } else {
                AgentPartyLifecycleService.leaveAgentParty(agent); // its party was for the quest; its circle re-forms
                session.mapId = agent.getMapId();
                population.presence().replan(session, nowMs);
            }
        }
    }

    // ---- ops ----------------------------------------------------------------------------

    /**
     * GM aid for live checks (!citizens pq send): a loaded citizen goes to the lobby its level fits, out for a party
     * quest, as its own PQ plan would send it. Says what happened.
     */
    public String send(CitizenSession session, long nowMs) {
        Character agent = session == null || session.virtual ? null : session.agent();
        if (agent == null) {
            return "not loaded";
        }
        if (holds(session.characterId)) {
            return session.name() + " is in a party quest";
        }
        Lobby lobby = population.fields().lobbyFor(session.citizen, agent.getLevel());
        if (lobby == null) {
            return "no lobby of " + session.citizen.homeRegion() + " fits lv" + agent.getLevel();
        }
        coolUntil.remove(session.characterId);
        population.presence().travel(session, Activity.PQ, lobby.mapId(), nowMs);
        return session.name() + " goes to " + lobby.name() + " at map " + lobby.mapId()
                + (door(lobby.mapId()) == null ? " (citizens do not run it: it only waits there)" : "");
    }

    /** One line for !citizens status and /runtime/citizens: runs now, and the tally since boot by quest. */
    public String status() {
        StringBuilder out = new StringBuilder("pq runs: ")
                .append(new HashSet<>(inRun.values()).size()).append(" in, ").append(forming.size()).append(" forming");
        tally.forEach((quest, counts) -> out.append("; ").append(quest).append(' ').append(counts[0]).append(" runs ")
                .append(counts[1]).append(" cleared ").append(counts[2]).append(" failed"));
        return out.toString();
    }

    /** The last few runs, newest last. */
    public List<String> recentRuns() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    private void remember(String line) {
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > RECENT_RUNS) {
                recent.removeFirst();
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private void say(String trigger, CitizenSession session, Character subject, int need) {
        ChatterEngine.Event event = ChatterEngine.Event.of(trigger, session.mapId, session.channel).self(session)
                .subject(subject);
        if (need > 0) {
            event.need(need);
        }
        population.chatter().event(event);
    }

    private static AgentPartySnapshot party(Character character) {
        return character == null ? null : AgentPartyGatewayRuntime.party().snapshot(character);
    }

    private static List<AgentPartyMemberSnapshot> members(Character character) {
        AgentPartySnapshot party = party(character);
        if (party == null) {
            return character == null ? List.of()
                    : List.of(new AgentPartyMemberSnapshot(character.getId(), character.getName(), true, character.getMapId()));
        }
        return party.members().stream().filter(java.util.Objects::nonNull).toList();
    }

    /** The member standing on {@code map} (null: anywhere online in the world). */
    private Character memberCharacter(int characterId, MapleMap map) {
        CitizenSession session = population.presence().session(characterId);
        Character citizen = session == null ? null : session.agent();
        if (citizen != null) {
            return citizen;
        }
        if (map != null) {
            for (Character chr : map.getAllPlayers()) {
                if (chr.getId() == characterId) {
                    return chr;
                }
            }
            return null;
        }
        return population.gameWorld() == null ? null
                : population.gameWorld().getPlayerStorage().getCharacterById(characterId);
    }

    private static boolean fits(Character chr, Lobby lobby) {
        return chr != null && chr.getLevel() >= lobby.levelMin() && chr.getLevel() <= lobby.levelMax();
    }

    private static String others(Run run, int self) {
        List<String> names = new ArrayList<>();
        run.citizens.forEach((id, name) -> {
            if (id != self) {
                names.add(name);
            }
        });
        names.addAll(run.humans.values());
        return names.isEmpty() ? "nobody" : String.join(", ", names);
    }

    private static String names(Map<Integer, String> citizens, Map<Integer, String> humans) {
        List<String> names = new ArrayList<>(citizens.values());
        humans.values().forEach(name -> names.add(name + "(human)"));
        return names.toString();
    }
}
