package server.living.population;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.integration.AgentPresence;
import server.living.config.ContentLoader;
import server.living.config.Node;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Conversations among citizens standing together in town (docs/SOLOMAPLING_PORT.md; SoloMapling's ConversationManager,
 * which nothing here replaced: RumorScheduler is Residents passing on news, Conversation answers a player). Two to four
 * idle TOWN citizens within {@link #CLUSTER_X} x {@link #CLUSTER_Y} px of each other, on a map where a player can see
 * them, play out one of conversations.yaml's scripts: they face each other's middle and each line comes in its
 * speaker's voice, a few seconds after the last. One conversation per map per CONVERSATION_GAP_MS (a third of it on a
 * map with more than twenty citizens), the last twenty scripts not repeated, a citizen in one conversation at a time.
 * A speaker who walks off or goes on to something else ends it. Rides the 20 s chatter tick; CONVERSATIONS_ENABLED:
 * false is the kill switch.
 */
public final class TownConversations {
    private static final Logger log = LoggerFactory.getLogger(TownConversations.class);
    static final String FILE = "conversations.yaml";
    static final int CLUSTER_X = 250;
    static final int CLUSTER_Y = 30;
    private static final int MAX_SPEAKERS = 4;
    private static final int BUSY_MAP = 20;
    private static final int RECENT = 20;
    private static final long LINE_GAP_MIN_MS = 6_000L;
    private static final long LINE_GAP_SPAN_MS = 3_000L;
    private static final String[] ROLES = {"A", "B", "C", "D"};

    /** One line of a script: who of A-D says it, and the face it pulls (0 none). */
    record Line(String speaker, String text, int emote) {
    }

    record Script(String id, int participants, List<Line> lines) {
    }

    /** Runs a step later: a timer live, a queue in tests. */
    interface Clock {
        void after(long delayMs, Runnable step);
    }

    private final Population population;
    private final Random random;
    private final Clock clock;
    private volatile List<Script> scripts;
    private final Set<Integer> talking = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastOnMap = new ConcurrentHashMap<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private final AtomicInteger started = new AtomicInteger();

    TownConversations(Population population) {
        this(population, (delayMs, step) -> TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                step, Math.max(1L, delayMs)), null);
    }

    TownConversations(Population population, Clock clock, List<Script> scripts) {
        this.population = population;
        this.clock = clock;
        this.scripts = scripts;
        this.random = new Random(population.config().SEED ^ 0xc0a7e75L);
    }

    boolean enabled() {
        return population.config().CONVERSATIONS_ENABLED;
    }

    /** True while the citizen is in a conversation (its other chatter waits). */
    public boolean talking(int characterId) {
        return talking.contains(characterId);
    }

    /** Every 20 s (the chatter tick): at most one new conversation per map whose gap is up. */
    void tick() {
        if (!enabled()) {
            return;
        }
        List<Script> loaded = scripts();
        if (loaded.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, List<CitizenSession>> byMap = new HashMap<>();
        for (CitizenSession session : population.presence().sessions()) {
            if (TownFidgets.idleInTown(session, session.agent()) && !talking.contains(session.characterId)) {
                byMap.computeIfAbsent(session.channel + ":" + session.mapId, key -> new ArrayList<>()).add(session);
            }
        }
        for (Map.Entry<String, List<CitizenSession>> map : byMap.entrySet()) {
            List<CitizenSession> idle = map.getValue();
            if (idle.size() < 2 || !gapUp(map.getKey(), idle.size(), now) || !humanOn(idle.get(0).agent())) {
                continue;
            }
            List<CitizenSession> cluster = cluster(idle, random);
            Script script = cluster.size() < 2 ? null : pick(loaded, cluster.size());
            if (script != null) {
                lastOnMap.put(map.getKey(), now);
                start(script, cluster.subList(0, script.participants()));
            }
        }
    }

    private boolean gapUp(String key, int citizens, long now) {
        Long last = lastOnMap.get(key);
        long gap = population.config().CONVERSATION_GAP_MS;
        return last == null || now - last >= (citizens > BUSY_MAP ? gap / 3 : gap);
    }

    private static boolean humanOn(Character agent) {
        if (agent == null || agent.getMap() == null) {
            return false;
        }
        for (Character chr : agent.getMap().getAllPlayers()) {
            if (!AgentPresence.isAgent(chr)) {
                return true;
            }
        }
        return false;
    }

    /** Up to four citizens standing near one of them, that one first; empty when nobody stands near anybody. */
    static List<CitizenSession> cluster(List<CitizenSession> idle, Random random) {
        List<CitizenSession> shuffled = new ArrayList<>(idle);
        Collections.shuffle(shuffled, random);
        for (CitizenSession anchor : shuffled) {
            Character a = anchor.agent();
            if (a == null) {
                continue;
            }
            List<CitizenSession> out = new ArrayList<>(List.of(anchor));
            for (CitizenSession other : shuffled) {
                Character o = other.agent();
                if (other != anchor && o != null && Math.abs(a.getPosition().x - o.getPosition().x) <= CLUSTER_X
                        && Math.abs(a.getPosition().y - o.getPosition().y) <= CLUSTER_Y) {
                    out.add(other);
                    if (out.size() == MAX_SPEAKERS) {
                        break;
                    }
                }
            }
            if (out.size() >= 2) {
                return out;
            }
        }
        return List.of();
    }

    /** A script for at most {@code speakers} citizens, not one of the last twenty when another fits. */
    Script pick(List<Script> from, int speakers) {
        List<Script> fresh = new ArrayList<>();
        List<Script> any = new ArrayList<>();
        synchronized (recent) {
            for (Script script : from) {
                if (script.participants() <= speakers && script.participants() >= 2) {
                    any.add(script);
                    if (!recent.contains(script.id())) {
                        fresh.add(script);
                    }
                }
            }
            List<Script> pool = fresh.isEmpty() ? any : fresh;
            if (pool.isEmpty()) {
                return null;
            }
            Script chosen = pool.get(random.nextInt(pool.size()));
            recent.addLast(chosen.id());
            while (recent.size() > RECENT) {
                recent.removeFirst();
            }
            return chosen;
        }
    }

    private void start(Script script, List<CitizenSession> speakers) {
        Map<String, CitizenSession> roles = new HashMap<>();
        for (int i = 0; i < speakers.size(); i++) {
            roles.put(ROLES[i], speakers.get(i));
            talking.add(speakers.get(i).characterId);
        }
        started.incrementAndGet();
        log.debug("citizens {} play conversation {}", speakers.stream().map(CitizenSession::name).toList(), script.id());
        faceEachOther(speakers);
        clock.after(800L, () -> say(script, roles, 0));
    }

    private void say(Script script, Map<String, CitizenSession> roles, int index) {
        if (index >= script.lines().size() || !stillHere(roles)) {
            for (CitizenSession session : roles.values()) {
                talking.remove(session.characterId);
            }
            return;
        }
        Line line = script.lines().get(index);
        CitizenSession speaker = roles.get(line.speaker());
        if (speaker != null) {
            String rendered = population.voices().render(speaker.citizen, line.text(), System.currentTimeMillis());
            population.chatter().answer(speaker, rendered, population.voices().delayMs(speaker.citizen, rendered), null);
            Character agent = speaker.agent();
            if (line.emote() > 0 && agent != null) {
                agent.changeFaceExpression(Math.min(7, line.emote()));
            }
        }
        clock.after(LINE_GAP_MIN_MS + random.nextInt((int) LINE_GAP_SPAN_MS), () -> say(script, roles, index + 1));
    }

    /** Every speaker is still an idle townie on the map it started on. */
    private boolean stillHere(Map<String, CitizenSession> roles) {
        if (!enabled()) {
            return false;
        }
        int mapId = -1;
        for (CitizenSession session : roles.values()) {
            Character agent = session.agent();
            if (!TownFidgets.idleInTown(session, agent) || population.presence().session(session.characterId) != session
                    || mapId != -1 && agent.getMapId() != mapId) {
                return false;
            }
            mapId = agent.getMapId();
        }
        return true;
    }

    /** They turn toward the middle of the group: a step of a few pixels, as a player turning around. */
    private void faceEachOther(List<CitizenSession> speakers) {
        int sum = 0;
        for (CitizenSession session : speakers) {
            sum += session.agent().getPosition().x;
        }
        int middle = sum / speakers.size();
        for (CitizenSession session : speakers) {
            Character agent = session.agent();
            if (session.entry != null && Math.abs(agent.getPosition().x - middle) > 10) {
                int toward = agent.getPosition().x < middle ? 3 : -3;
                server.agents.capabilities.movement.AgentMovementCommandRuntime.moveTo(session.entry,
                        new Point(agent.getPosition().x + toward, agent.getPosition().y), false);
            }
        }
    }

    /** One line for {@code !citizens talk}. */
    public String status() {
        List<Script> loaded = scripts;
        String recentIds;
        synchronized (recent) {
            recentIds = String.join(", ", recent);
        }
        return "town conversations " + (enabled() ? "on" : "off (CONVERSATIONS_ENABLED)") + ": "
                + (loaded == null ? "scripts not loaded yet" : loaded.size() + " scripts") + "; " + started.get()
                + " started since boot; " + talking.size() + " citizens talking now" + (recentIds.isEmpty() ? "" : "; last: " + recentIds);
    }

    private List<Script> scripts() {
        List<Script> current = scripts;
        if (current == null) {
            current = load(Path.of(population.config().CONTENT_DIR).resolve(FILE));
            scripts = current;
        }
        return current;
    }

    /** Reads conversations.yaml; empty (and a warning) when it is missing or broken. */
    static List<Script> load(Path file) {
        List<Script> out = new ArrayList<>();
        try {
            if (!Files.exists(file)) {
                log.warn("{} not found; citizens hold no town conversations", file.toAbsolutePath());
                return out;
            }
            Node root = ContentLoader.read(file).get("conversations");
            for (Map.Entry<String, Node> entry : root.entries().entrySet()) {
                List<Line> lines = new ArrayList<>();
                for (Node line : entry.getValue().get("lines").items()) {
                    lines.add(new Line(line.get("speaker").string("A"), line.get("text").string(""), line.get("emote").integer(0)));
                }
                out.add(new Script(entry.getKey(), entry.getValue().get("participants").integer(2), lines));
            }
        } catch (RuntimeException failure) {
            log.warn("conversations not loaded: {}", failure.getMessage());
        }
        return out;
    }
}
