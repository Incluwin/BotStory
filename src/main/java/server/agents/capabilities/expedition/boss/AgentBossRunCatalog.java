package server.agents.capabilities.expedition.boss;

import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.agents.capabilities.expedition.balrog.AgentEasyBalrogScenario;
import server.agents.capabilities.expedition.zakum.AgentZakumDefinition;
import server.agents.capabilities.expedition.zakum.AgentZakumScenario;
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
            AgentEasyBalrogScenario::forCompanions, false, List.of(), false, false);
    // slot: zakum
    /**
     * Adobis's Zakum (P8): sign up in her room behind the Door to Zakum with her registering window (no choices), fight
     * on the altar and leave by Amon, before and after the clear (no reward room, so no clear map and no exit portal).
     * Members leave the Eye of Fire for the altar, never target the fake body, and leave with an owner who goes.
     */
    public static final AgentBossRunDefinition ZAKUM = new AgentBossRunDefinition(
            "zakum", ExpeditionType.ZAKUM,
            AgentZakumDefinition.RECRUIT_MAP_ID, AgentZakumDefinition.BATTLE_MAP_ID, -1,
            AgentZakumDefinition.ENTRY_NPC_ID, List.of(),
            AgentZakumDefinition.EXIT_NPC_ID, -1,
            AgentZakumScenario::forCompanions, true, List.of(AgentZakumDefinition.EYE_ITEM_ID), true, true);

    // slot: balrog_normal
    /**
     * Mu Young's Normal Balrog (P11): sign up with her Normal choice, fight on 105100300, collect in 105100301. The
     * exit NPC's yes after the clear moves only the talker into the reward room, so members follow their owner there
     * and leave with them; an owner who leaves before the clear takes their party's members out with them (rule B).
     * The fake body is never a target, nor are the parts the boss rules catalog lists (AgentBossRules).
     */
    public static final AgentBossRunDefinition BALROG_NORMAL = new AgentBossRunDefinition(
            AgentBalrogDefinition.NORMAL_KEY, ExpeditionType.BALROG_NORMAL,
            AgentBalrogDefinition.RECRUIT_MAP, AgentBalrogDefinition.NORMAL_BATTLE_MAP_ID,
            AgentBalrogDefinition.NORMAL_CLEAR_MAP_ID, AgentBalrogDefinition.ENTRY_NPC, List.of(2),
            AgentBalrogDefinition.BATTLE_EXIT_NPC_ID, AgentBalrogDefinition.REWARD_EXIT_PORTAL_ID,
            AgentEasyBalrogScenario::forNormalCompanions, false, List.of(), true, true);

    private static final List<AgentBossRunDefinition> ALL = List.of(EASY_BALROG, ZAKUM, BALROG_NORMAL);

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
