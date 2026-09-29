package server.agents.capabilities.supplies;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentBerserkWindowPolicy;
import server.agents.capabilities.supplies.AgentAutopotPolicy.AutopotChoice;
import server.agents.capabilities.supplies.AgentAutopotPolicy.AutopotItemChoice;
import server.agents.runtime.AgentRuntimeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-Agent potion seam (docs/BOT_MANAGEMENT_PLAN.md P3b): every read of the autopot thresholds for an Agent goes
 * through AgentPotionThresholds, so an Agent its host answers for drinks at its own HP and MP percentages (the autopot
 * alerts and the deficits the potion choice is sized for), a Dark Knight's Berserk window still lowers the HP one, and
 * with no rule, or no answer, every Agent drinks at the configured AUTOPOT thresholds as before.
 */
class AgentPotionThresholdsTest {
    private static final int COMPANION = 7;
    private static final int OTHER = 8;
    private static final int MAX_HP = 1000;
    private static final int MAX_MP = 400;
    private static final Path AGENTS = Path.of("src", "main", "java", "server", "agents");

    private final Map<Integer, AgentPotionThresholds.Thresholds> own = new HashMap<>();

    @AfterEach
    void uninstall() {
        AgentPotionThresholds.install(null);
    }

    @Test
    void withNoRuleEveryAgentDrinksAtTheConfiguredThresholds() {
        Character bot = character(COMPANION);
        float hp = AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH;
        float mp = AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH;

        assertEquals(hp, AgentPotionService.hpThreshold(bot));
        assertEquals(mp, AgentPotionService.mpThreshold(bot));
        List<int[]> deficits = setUpAutopot(bot, 0);
        verify(bot).setAutopotHpAlert(hp);
        verify(bot).setAutopotMpAlert(mp);
        assertEquals(Math.round(MAX_HP * (1f - hp)), deficits.get(0)[0]);
        assertEquals(Math.round(MAX_MP * (1f - mp)), deficits.get(0)[1]);
    }

    @Test
    void anAgentTheHostAnswersForDrinksAtItsOwnThresholdsAndTheRestAsConfigured() {
        AgentPotionThresholds.install(own::get);
        own.put(COMPANION, new AgentPotionThresholds.Thresholds(0.4f, 0.3f));
        Character companion = character(COMPANION);
        Character other = character(OTHER);

        assertEquals(0.4f, AgentPotionService.hpThreshold(companion));
        assertEquals(0.3f, AgentPotionService.mpThreshold(companion));
        List<int[]> deficits = setUpAutopot(companion, 0);
        verify(companion).setAutopotHpAlert(0.4f);
        verify(companion).setAutopotMpAlert(0.3f);
        assertEquals(600, deficits.get(0)[0], "a potion sized for what it misses at 40%");
        assertEquals(280, deficits.get(0)[1]);

        assertEquals(AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH, AgentPotionService.hpThreshold(other));
        assertEquals(AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH, AgentPotionService.mpThreshold(other));
        setUpAutopot(other, 0);
        verify(other).setAutopotHpAlert(AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH);
    }

    @Test
    void theBerserkWindowStillAppliesOnTopOfTheAgentsOwnHpThreshold() {
        AgentPotionThresholds.install(own::get);
        own.put(COMPANION, new AgentPotionThresholds.Thresholds(0.9f, 0.3f));
        Character high = character(COMPANION);

        setUpAutopot(high, 80); // Berserk at 80%: the trigger never rises above its window (80 - 5)
        verify(high).setAutopotHpAlert(0.75f);

        own.put(COMPANION, new AgentPotionThresholds.Thresholds(0.5f, 0.3f));
        Character low = character(COMPANION);
        List<int[]> deficits = setUpAutopot(low, 80); // under the window: its own 50% stands
        verify(low).setAutopotHpAlert(0.5f);
        assertEquals(250, deficits.get(0)[0], "just enough to reach the top of the window, not a full heal");
    }

    @Test
    void noAgentSourceReadsTheGlobalAutopotThresholdsButThroughTheSeam() throws IOException {
        List<String> reads = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(AGENTS)) {
            for (Path file : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                for (String line : Files.readAllLines(file)) {
                    String code = line.strip();
                    if (code.startsWith("*") || code.startsWith("//") || name.equals("AgentRuntimeConfig.java")) {
                        continue;
                    }
                    if (code.contains(".AUTOPOT_HP_THRESH") || code.contains(".AUTOPOT_MP_THRESH")) {
                        reads.add(name + ": " + code);
                    }
                }
            }
        }
        assertEquals(List.of(
                "AgentPotionService.java: return AgentPotionThresholds.hp(bot.getId(), AgentRuntimeConfig.cfg.AUTOPOT_HP_THRESH);",
                "AgentPotionService.java: return AgentPotionThresholds.mp(bot.getId(), AgentRuntimeConfig.cfg.AUTOPOT_MP_THRESH);"),
                reads);
    }

    /**
     * Runs the autopot setup with a potion for each bar ({@code berserk}: the Berserk threshold, 0 for none) and returns
     * the HP and MP deficits the potion choice was asked for.
     */
    private static List<int[]> setUpAutopot(Character bot, int berserk) {
        List<int[]> deficits = new ArrayList<>();
        try (MockedStatic<AgentAutopotPolicy> policy = mockStatic(AgentAutopotPolicy.class);
             MockedStatic<AgentBerserkWindowPolicy> window = mockStatic(AgentBerserkWindowPolicy.class, CALLS_REAL_METHODS)) {
            window.when(() -> AgentBerserkWindowPolicy.thresholdPercent(bot)).thenReturn(berserk);
            policy.when(() -> AgentAutopotPolicy.computeChoice(any(), any(), eq(MAX_HP), eq(MAX_MP), anyInt(), anyInt()))
                    .thenAnswer(inv -> {
                        deficits.add(new int[]{inv.getArgument(4), inv.getArgument(5)});
                        return new AutopotChoice(new AutopotItemChoice(2000000, (short) 1, null),
                                new AutopotItemChoice(2000003, (short) 2, null));
                    });
            AgentPotionService.setupAutopotForBot(bot);
        }
        return deficits;
    }

    private static Character character(int id) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getCurrentMaxHp()).thenReturn(MAX_HP);
        when(bot.getCurrentMaxMp()).thenReturn(MAX_MP);
        Inventory use = mock(Inventory.class);
        when(use.list()).thenReturn(List.of());
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        return bot;
    }
}
