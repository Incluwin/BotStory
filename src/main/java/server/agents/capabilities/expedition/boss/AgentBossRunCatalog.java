package server.agents.capabilities.expedition.boss;

import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.agents.capabilities.expedition.balrog.AgentEasyBalrogScenario;
import server.expeditions.ExpeditionType;

import java.util.List;
import java.util.Locale;

/** The bosses companions can sign up for. One content table: a new boss adds its row here. */
public final class AgentBossRunCatalog {
    /** Mu Young's Easy Balrog: sign up with her Easy choice, fight on 105100400, collect in 105100401. */
    public static final AgentBossRunDefinition EASY_BALROG = new AgentBossRunDefinition(
            "easy_balrog", ExpeditionType.BALROG_EASY,
            AgentBalrogDefinition.RECRUIT_MAP, AgentBalrogDefinition.BATTLE_MAP, AgentBalrogDefinition.CLEAR_MAP,
            AgentBalrogDefinition.ENTRY_NPC, List.of(1),
            AgentBalrogDefinition.BATTLE_EXIT_NPC_ID, AgentBalrogDefinition.REWARD_EXIT_PORTAL_ID,
            AgentEasyBalrogScenario::forCompanions);
    // slot: zakum

    private static final List<AgentBossRunDefinition> ALL = List.of(EASY_BALROG);

    private AgentBossRunCatalog() {
    }

    public static List<AgentBossRunDefinition> all() {
        return ALL;
    }

    /** The boss with this key (trimmed, any case); null for an unknown or blank key. */
    public static AgentBossRunDefinition of(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(definition -> definition.key().equals(wanted)).findFirst().orElse(null);
    }
}
