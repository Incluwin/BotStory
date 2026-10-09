package client.command.commands.gm6;

import client.BotClient;
import client.Character;
import client.Client;
import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.boss.AgentBossAttackMirror;
import server.agents.capabilities.expedition.boss.AgentBossAttackMirror.Mode;
import server.agents.capabilities.mobcontrol.AgentBossScope;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.maps.MapleMap;
import testutil.LogLines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code !bossmirror} (P8 step 8): the GM's switch board for the boss attack mirror. {@code status} prints the mode,
 * the trial and the counters; {@code mode} sets the live mirror's runtime override; {@code trial on} binds the GM's
 * map and the companions the GM leads, and {@code trial off} clears it. Each use writes one "gm bossmirror" log line.
 * The command drives the live mirror (AgentBossAttackMirror.get()) and the live trial (AgentBossScope), so each test
 * puts both back.
 */
class BossMirrorCommandTest {
    private static final String MODE = "server.agents.capabilities.expedition.boss.AgentBossAttackMirror.MODE";
    private static final String ENABLED = "server.agents.capabilities.mobcontrol.AgentBossScope.ENABLED";
    private static final String BOSS_IDS = "server.agents.capabilities.mobcontrol.AgentBossScope.BOSS_IDS";
    private static final List<String> TUNING = List.of(MODE, ENABLED, BOSS_IDS);
    private static final int GM_ID = 42_000_081;
    private static final int OTHER_GM_ID = 42_000_082;
    private static final int THANE = 42_000_090;
    private static final int ISOLDE = 42_000_091;
    private static final int STRANGER = 42_000_099;
    /** Henesys, a field map with no event. */
    private static final int FIELD_ID = 100_000_000;
    private static final int ELSEWHERE_ID = 101_000_000;
    /** A Zakum arm spawned on the field for the trial. */
    private static final int ARM = 8_800_004;

    private final Map<String, String> shipped = new HashMap<>();
    private final List<String> messages = new ArrayList<>();
    private final AgentBossAttackMirror mirror = AgentBossAttackMirror.get();
    private final Client client = mock(Client.class);
    private final Character gm = mock(Character.class);
    private final MapleMap field = map(FIELD_ID);
    private final MapleMap elsewhere = map(ELSEWHERE_ID);

    @BeforeEach
    void setUp() {
        for (String key : TUNING) {
            shipped.put(key, AgentYamlConfig.config.tuning.get(key));
        }
        tune(MODE, "APPLY");
        tune(ENABLED, "true");
        tune(BOSS_IDS, "none");
        when(client.getPlayer()).thenReturn(gm);
        when(gm.getClient()).thenReturn(client);
        when(gm.getId()).thenReturn(GM_ID);
        when(gm.getName()).thenReturn("Bossfriend");
        when(gm.getMap()).thenReturn(field);
        when(gm.getMapId()).thenReturn(FIELD_ID);
        when(field.getCharacterById(GM_ID)).thenReturn(gm);
        doAnswer(invocation -> messages.add(invocation.getArgument(0))).when(gm).yellowMessage(anyString());
    }

    @AfterEach
    void tearDown() {
        mirror.overrideMode(null);
        AgentBossScope.endTrial();
        AgentRuntimeRegistry.clear();
        shipped.forEach((key, value) -> {
            if (value == null) {
                AgentYamlConfig.config.tuning.remove(key);
            } else {
                AgentYamlConfig.config.tuning.put(key, value);
            }
        });
    }

    @Test
    void statusPrintsTheModeTheTrialAndTheCounters() {
        companion(THANE, "Thane", field, GM_ID);
        run("mode", "observe");
        run("trial", "on");
        Monster arm = part(ARM, field);
        long before = reported(ARM, 0);
        mirror.reportedHit(gm, arm, 0, 1_234);   // the GM's client reports a hit from the arm's attack1
        messages.clear();

        run("status");

        assertTrue(messages.stream().anyMatch(line -> line.startsWith("Boss mirror mode=OBSERVE (runtime override")),
                messages.toString());
        assertTrue(messages.stream().anyMatch(line -> line.startsWith("Boss mirror trial on: map " + FIELD_ID)
                && line.contains("Bossfriend") && line.contains("Thane")), messages.toString());
        assertTrue(messages.stream().anyMatch(line -> line.startsWith(ARM + " attack1: reported=" + (before + 1) + " ")),
                messages.toString());

        run("trial", "off");
        messages.clear();
        run("status");
        assertTrue(messages.contains("Boss mirror trial off."), messages.toString());
    }

    @Test
    void modeSetsTheRuntimeOverrideThatBeatsTheYamlMode() {
        tune(MODE, "OFF");
        try (LogLines lines = new LogLines(BossMirrorCommand.class)) {
            run("mode", "observe");
            assertEquals(Mode.OBSERVE, mirror.mode());
            assertTrue(messages.stream().anyMatch(line -> line.contains("mode=OBSERVE")), messages.toString());

            run("mode", "APPLY");
            assertEquals(Mode.APPLY, mirror.mode());

            run("mode", "off");
            tune(MODE, "APPLY");
            assertEquals(Mode.OFF, mirror.mode(), "the runtime override beats agent-engine.yaml");

            List<String> logged = lines.startingWith("gm bossmirror mode:");
            assertEquals(3, logged.size(), logged.toString());
            assertTrue(logged.get(0).contains("mode=OBSERVE") && logged.get(0).contains("was=OFF"), logged.get(0));
        }
    }

    @Test
    void trialOnBindsTheGmsMapAndCompanionsAndTrialOffClearsIt() {
        companion(THANE, "Thane", field, GM_ID);
        companion(ISOLDE, "Isolde", elsewhere, GM_ID);
        companion(STRANGER, "Stranger", field, OTHER_GM_ID);
        Monster here = part(ARM, field);
        Monster away = part(ARM, elsewhere);
        assertFalse(AgentBossScope.guards(here), "no trial: a field part is not guarded");

        run("trial", "on");

        assertEquals(GM_ID, AgentBossScope.trialGm());
        assertSame(field, AgentBossScope.trialMap());
        assertTrue(AgentBossScope.guards(here), "the trial guards the parts on the GM's map");
        assertFalse(AgentBossScope.guards(away), "the trial never guards a part on another map");
        String reply = String.join("\n", messages);
        assertTrue(reply.contains("Thane"), reply);
        assertFalse(reply.contains("Isolde"), "a companion on another map is not in the trial's reply: " + reply);
        assertFalse(reply.contains("Stranger"), "another GM's companion is not the trial's: " + reply);

        messages.clear();
        run("trial", "off");

        assertEquals(0, AgentBossScope.trialGm());
        assertNull(AgentBossScope.trialMap());
        assertFalse(AgentBossScope.guards(here));
        assertTrue(messages.stream().anyMatch(line -> line.startsWith("Boss mirror trial off")), messages.toString());
    }

    @Test
    void trialOnReplacesAnotherGmsTrialAndSaysSo() {
        AgentBossScope.startTrial(OTHER_GM_ID, elsewhere);

        run("trial", "on");

        assertEquals(GM_ID, AgentBossScope.trialGm());
        assertSame(field, AgentBossScope.trialMap());
        assertTrue(messages.stream().anyMatch(line -> line.contains("replaces") && line.contains("" + ELSEWHERE_ID)),
                messages.toString());
    }

    @Test
    void eachUseIsLoggedWithTheGmAndTheirMap() {
        try (LogLines lines = new LogLines(BossMirrorCommand.class)) {
            run("status");
            run("mode", "observe");
            run("trial", "on");
            run("trial", "off");
            run("trial", "off");

            List<String> logged = lines.startingWith("gm bossmirror ");
            assertEquals(5, logged.size(), logged.toString());
            assertTrue(logged.stream().allMatch(line -> line.contains("gm=Bossfriend") && line.contains("map=" + FIELD_ID)),
                    logged.toString());
            assertEquals(1, lines.startingWith("gm bossmirror status:").size());
            assertEquals(3, lines.startingWith("gm bossmirror trial:").size());
            assertTrue(lines.startingWith("gm bossmirror trial:").get(0).contains("trial=on"));
            assertTrue(lines.startingWith("gm bossmirror trial:").get(1).contains("trial=off"));
        }
    }

    @Test
    void badArgumentsChangeNothingAndShowTheSyntax() {
        try (LogLines lines = new LogLines(BossMirrorCommand.class)) {
            run();
            run("mode");
            run("mode", "loud");
            run("trial", "maybe");
            run("trial");
            run("status", "now");
            run("nonsense");

            assertEquals(Mode.APPLY, mirror.mode());
            assertEquals(0, AgentBossScope.trialGm());
            assertEquals(List.of(BossMirrorCommand.USAGE, BossMirrorCommand.USAGE, BossMirrorCommand.USAGE,
                    BossMirrorCommand.USAGE, BossMirrorCommand.USAGE, BossMirrorCommand.USAGE,
                    BossMirrorCommand.USAGE), messages);
            assertTrue(lines.startingWith("gm bossmirror").isEmpty());
        }
    }

    private void run(String... params) {
        new BossMirrorCommand().execute(client, params);
    }

    private void tune(String key, String value) {
        AgentYamlConfig.config.tuning.put(key, value);
    }

    /** The live mirror's reported count for one attack of one mob; the live mirror keeps its counts for the JVM. */
    private long reported(int mobId, int attackIndex) {
        return mirror.calibration().stream()
                .filter(counts -> counts.mobId() == mobId && counts.attackIndex() == attackIndex)
                .mapToLong(AgentBossAttackMirror.CalibrationCounts::reported)
                .findFirst().orElse(0L);
    }

    private static MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        return map;
    }

    /** A live part with no controller yet, as !spawn leaves it. */
    private static Monster part(int mobId, MapleMap on) {
        Monster monster = mock(Monster.class);
        when(monster.getId()).thenReturn(mobId);
        when(monster.isAlive()).thenReturn(true);
        when(monster.getMap()).thenReturn(on);
        return monster;
    }

    private static Character companion(int id, String name, MapleMap on, int leaderId) {
        Character bot = mock(Character.class);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        when(bot.getId()).thenReturn(id);
        when(bot.getName()).thenReturn(name);
        when(bot.getMap()).thenReturn(on);
        AgentRuntimeRegistry.registerEntry(leaderId, new AgentRuntimeEntry(bot, null, null));
        return bot;
    }
}
