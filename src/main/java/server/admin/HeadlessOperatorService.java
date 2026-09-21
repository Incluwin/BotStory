package server.admin;

import client.Character;
import client.command.CommandsExecutor;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.auth.AgentControlService;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.registry.AgentResolvedCharacter;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs GM commands as a headless operator character so automated harnesses can drive the
 * in-game command surface over the AdminControlPort. The operator is an ordinary GM character
 * from the database, brought online on a {@code BotClient} in the GM map when needed; its chat
 * output is captured through {@link OperatorMessageTap} and kept per run for polling, because
 * commands such as {@code !skillprobe} keep reporting after the command call returns.
 *
 * <p>Deliberately narrow: only {@link #ALLOWED_COMMANDS} may run, and only when the JVM was
 * started with {@code -Dagents.headless-operator=true}. The AdminControlPort is loopback-only and
 * token-gated, but a general "run any GM command" endpoint would still be a remote console.</p>
 */
public final class HeadlessOperatorService {
    private static final Logger log = LoggerFactory.getLogger(HeadlessOperatorService.class);
    private static final int WORLD = 0;
    private static final int CHANNEL = 1;
    private static final int OPERATOR_MAP = 180000000;
    private static final int RUN_HISTORY = 50;

    /** Only harness commands may run headlessly; this is not a general remote GM console. */
    static final Set<String> ALLOWED_COMMANDS = Set.of("skillprobe", "spawnbot");
    /** Off unless the server was started with {@code -Dagents.headless-operator=true}. */
    static final boolean ENABLED = Boolean.getBoolean("agents.headless-operator");

    private static final Map<Integer, Run> RUNS = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static volatile Run activeRun;

    private HeadlessOperatorService() {
    }

    public record Run(int id, String operator, String command, long startedAtMs, List<String> lines) {
        Run(int id, String operator, String command, long startedAtMs) {
            this(id, operator, command, startedAtMs, new CopyOnWriteArrayList<>());
        }
    }

    /** Executes {@code command} (without the leading {@code !}) and returns the run that captures its output. */
    public static synchronized Run run(String operatorName, String command) throws Exception {
        if (!ENABLED) {
            throw new IllegalStateException("Headless operator is disabled; start with -Dagents.headless-operator=true.");
        }
        String name = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED_COMMANDS.contains(name)) {
            throw new IllegalArgumentException("Command '" + name + "' is not allowed headlessly; allowed: " + ALLOWED_COMMANDS);
        }
        Character operator = ensureOnline(operatorName);
        Run run = new Run(SEQUENCE.incrementAndGet(), operator.getName(), command, System.currentTimeMillis());
        RUNS.put(run.id(), run);
        trimHistory();
        activeRun = run;
        OperatorMessageTap.install(operator.getId(), line -> {
            Run current = activeRun;
            if (current != null) {
                current.lines().add(line);
            }
        });
        CommandsExecutor.getInstance().handle(operator.getClient(), "!" + command);
        return run;
    }

    public static Run find(int id) {
        return RUNS.get(id);
    }

    public static Run latest() {
        return activeRun;
    }

    public static synchronized Character ensureOnline(String operatorName) throws Exception {
        World world = Server.getInstance().getWorld(WORLD);
        Character online = world.getPlayerStorage().getCharacterByName(operatorName);
        if (online != null) {
            return online;
        }
        AgentResolvedCharacter resolved = AgentControlService.getInstance().resolveCharacterByName(operatorName);
        if (resolved == null) {
            throw new IllegalArgumentException("Operator character '" + operatorName + "' does not exist.");
        }
        MapleMap map = Server.getInstance().getChannel(WORLD, CHANNEL).getMapFactory().getMap(OPERATOR_MAP);
        Portal spawn = map == null ? null : map.getPortal(0);
        Point position = spawn == null ? null : spawn.getPosition();
        Character operator = CosmicAgentOfflineLoader.loadOfflineAgent(resolved.id(), WORLD, CHANNEL, map, position);
        if (!operator.isGM()) {
            throw new IllegalStateException("Operator character '" + operatorName + "' is not a GM.");
        }
        log.warn("Headless operator {} ({}) brought online in map {}", operator.getName(), operator.getId(), operator.getMapId());
        return operator;
    }

    private static void trimHistory() {
        if (RUNS.size() <= RUN_HISTORY) {
            return;
        }
        List<Integer> ids = new ArrayList<>(RUNS.keySet());
        ids.sort(null);
        for (int i = 0; i < ids.size() - RUN_HISTORY; i++) {
            RUNS.remove(ids.get(i));
        }
    }
}
