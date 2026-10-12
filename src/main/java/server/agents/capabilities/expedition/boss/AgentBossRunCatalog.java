package server.agents.capabilities.expedition.boss;

import server.agents.capabilities.expedition.balrog.AgentBalrogDefinition;
import server.agents.capabilities.expedition.balrog.AgentEasyBalrogScenario;
import server.agents.capabilities.expedition.zakum.AgentZakumDefinition;
import server.agents.capabilities.expedition.zakum.AgentZakumScenario;
import server.expeditions.ExpeditionType;

import java.util.List;
import java.util.Locale;

/**
 * The bosses companions can sign up for. One content table: a new boss adds its row here. A row's marker items and its
 * never-target-fakes flag come from the boss rules catalog (boss-rules.json, AgentBossRules), by the row's key.
 */
public final class AgentBossRunCatalog {
    /** Mu Young's Easy Balrog: sign up with her Easy choice, fight on 105100400, collect in 105100401. */
    public static final AgentBossRunDefinition EASY_BALROG = new AgentBossRunDefinition(
            "easy_balrog", ExpeditionType.BALROG_EASY,
            AgentBalrogDefinition.RECRUIT_MAP, AgentBalrogDefinition.BATTLE_MAP, AgentBalrogDefinition.CLEAR_MAP,
            AgentBalrogDefinition.ENTRY_NPC, List.of(1),
            AgentBalrogDefinition.BATTLE_EXIT_NPC_ID, AgentBalrogDefinition.REWARD_EXIT_PORTAL_ID,
            AgentEasyBalrogScenario::forCompanions, false,
            AgentBossRules.markerItems("easy_balrog"), AgentBossRules.neverTargetFakes("easy_balrog"), false);
    // slot: zakum
    /**
     * Adobis's Zakum (P8): sign up in her room behind the Door to Zakum with her registering window (no choices), fight
     * on the altar and leave by Amon, before and after the clear (no reward room, so no clear map and no exit portal).
     * Members leave the Eye of Fire for the altar and never target the fake body (both from boss-rules.json), and leave
     * with an owner who goes.
     */
    public static final AgentBossRunDefinition ZAKUM = new AgentBossRunDefinition(
            "zakum", ExpeditionType.ZAKUM,
            AgentZakumDefinition.RECRUIT_MAP_ID, AgentZakumDefinition.BATTLE_MAP_ID, -1,
            AgentZakumDefinition.ENTRY_NPC_ID, List.of(),
            AgentZakumDefinition.EXIT_NPC_ID, -1,
            AgentZakumScenario::forCompanions, true,
            AgentBossRules.markerItems("zakum"), AgentBossRules.neverTargetFakes("zakum"), true);

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
            AgentEasyBalrogScenario::forNormalCompanions, false,
            AgentBossRules.markerItems(AgentBalrogDefinition.NORMAL_KEY),
            AgentBossRules.neverTargetFakes(AgentBalrogDefinition.NORMAL_KEY), true);

    // slot: latanica
    /**
     * Capt. Latanica (P14), a party boss: the owner leads the party through captinsg00 on the gate 541010060 (with
     * White Essence), and LatanicaBattle.js's afterSetup opens the run for the companions inside (party-instance run).
     * The fight is on 541010100. Bob (9270033) on that map is the only way out, before and after the clear, and warps
     * only the talker; an owner who leaves takes their companions out with them. A dead companion revives in Boat Quay
     * Town and waits there.
     */
    public static final AgentBossRunDefinition LATANICA = party("latanica", 541_010_060, 541_010_100, -1, 9_270_033,
            -1, true);

    // slot: neo city
    /**
     * The Neo City bosses (P14), party bosses for 2-6 players at Lv70 or more: the owner leads the party through
     * right00 (TD_Boss_enter) on each boss's gate, and TD_Battle1-5.js's afterSetup opens the run. Each battle map's
     * only way out is its portal 1 (left00, under00 for Oberon), so the battle map is also the map members leave from
     * (its clear map, with that portal as the way out). A dead companion revives at 240070000 and waits there.
     */
    public static final AgentBossRunDefinition NEO_BERGAMOT = party("neo_bergamot", 240_070_202, 240_070_203,
            240_070_203, 0, 1, false);
    public static final AgentBossRunDefinition NEO_DUNAS = party("neo_dunas", 240_070_302, 240_070_303,
            240_070_303, 0, 1, false);
    public static final AgentBossRunDefinition NEO_AUFHEBEN = party("neo_aufheben", 240_070_402, 240_070_403,
            240_070_403, 0, 1, false);
    public static final AgentBossRunDefinition NEO_OBERON = party("neo_oberon", 240_070_502, 240_070_503,
            240_070_503, 0, 1, false);
    public static final AgentBossRunDefinition NEO_NIBELUNG = party("neo_nibelung", 240_070_602, 240_070_603,
            240_070_603, 0, 1, false);

    // slot: papulatus
    /**
     * Papulatus (P15), a party boss with a daily quota (2 a day): the owner leads the party through Populatus00 on the
     * gate 220080000, and PapulatusBattle.js's afterSetup opens the run. The fight is on 220080001, where fake Low
     * Darkstars are never targets (boss-rules.json). 2041025 on that map is the way out before and after the clear, and
     * warps only the talker; an owner who leaves takes their companions out with them. A dead companion revives in
     * Ludibrium and waits there.
     */
    public static final AgentBossRunDefinition PAPULATUS = party("papulatus", 220_080_000, 220_080_001, -1, 2_041_025,
            -1, true);

    private static final List<AgentBossRunDefinition> ALL = List.of(EASY_BALROG, ZAKUM, BALROG_NORMAL, LATANICA,
            NEO_BERGAMOT, NEO_DUNAS, NEO_AUFHEBEN, NEO_OBERON, NEO_NIBELUNG, PAPULATUS);

    private AgentBossRunCatalog() {
    }

    public static List<AgentBossRunDefinition> all() {
        return ALL;
    }

    /**
     * A party boss (P14): no expedition, no entry NPC and no sign-up choices; its fight is the boss rules catalog's
     * priority fight; its dead members wait outside; an owner who leaves takes their party's members out with them.
     */
    private static AgentBossRunDefinition party(String key, int gateMapId, int battleMapId, int leaveMapId,
                                                int exitNpcId, int exitPortalId, boolean exitNpcAfterClear) {
        return new AgentBossRunDefinition(key, null, gateMapId, battleMapId, leaveMapId, 0, List.of(), exitNpcId,
                exitPortalId, AgentBossPriorityFight.forKey(key), exitNpcAfterClear, AgentBossRules.markerItems(key),
                AgentBossRules.neverTargetFakes(key), true, AgentBossRunDefinition.DeathMode.WAIT_OUTSIDE);
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
