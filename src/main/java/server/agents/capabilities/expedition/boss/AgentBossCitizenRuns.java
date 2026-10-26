package server.agents.capabilities.expedition.boss;

import server.agents.capabilities.expedition.AgentBossFight;
import server.agents.capabilities.expedition.balrog.AgentBalrogFight;
import server.agents.capabilities.expedition.balrog.AgentEasyBalrogScenario;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The bosses citizens run themselves (docs/CITIZEN_EXPEDITIONS_PLAN.md): only those the server moves itself once a
 * bot has signed up for the fight (Easy and Normal Balrog, living_world.COMPANION_BOSS_SERVER_AUTHORITY), so a run
 * with no human in it is still a real fight. Every other expedition boss is moved by a human's client and never
 * attacks without one. Per boss: its run definition, the entry NPC's choices that create and start the expedition (the
 * GM lobby's), and the fight that plays the reward room after the clear.
 */
public final class AgentBossCitizenRuns {
    /** One boss citizens may run. */
    public record Boss(AgentBossRunDefinition definition, List<Integer> createSelections,
                       List<Integer> startSelections, Supplier<AgentBossFight> fight) {
        public Boss {
            createSelections = List.copyOf(createSelections);
            startSelections = List.copyOf(startSelections);
        }

        public String key() {
            return definition.key();
        }

        int[] createChoices() {
            return createSelections.stream().mapToInt(Integer::intValue).toArray();
        }

        int[] startChoices() {
            return startSelections.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** Mu Young's Easy Balrog: create 1,1 (Easy, "Lets get this going!"), start 1,2 and her Ok. */
    public static final Boss EASY_BALROG = new Boss(AgentBossRunCatalog.EASY_BALROG,
            AgentBalrogFight.EASY.createSelections(), AgentBalrogFight.EASY.startSelections(),
            AgentEasyBalrogScenario::forCitizens);
    /** Mu Young's Normal Balrog: create 2,1, start 2,2 and her Ok. */
    public static final Boss BALROG_NORMAL = new Boss(AgentBossRunCatalog.BALROG_NORMAL,
            AgentBalrogFight.NORMAL.createSelections(), AgentBalrogFight.NORMAL.startSelections(),
            AgentEasyBalrogScenario::forNormalCitizens);

    private static final List<Boss> ALL = List.of(EASY_BALROG, BALROG_NORMAL);

    private AgentBossCitizenRuns() {
    }

    public static List<Boss> all() {
        return ALL;
    }

    /** The boss with this key (trimmed, any case); null for one citizens do not run. */
    public static Boss of(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(boss -> boss.key().equals(wanted)).findFirst().orElse(null);
    }
}
