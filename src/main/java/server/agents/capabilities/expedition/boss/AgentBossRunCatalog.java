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

    // slot: scarga
    /**
     * Scarlion and Targa (P17), an expedition of 6-30 at Lv100 or more, once a day: sign up with Aldol (9270047) on
     * 551030100 with his registering window (no choices, as Adobis's), fight on 551030200 and leave by 9201134 there,
     * before and after the clear (a yes, then the reward). The fight is the boss rules' priority fight: Targa's three
     * forms, then Scarlion's, then the adds. A member that dies revives in Kampung, walks back and enters again by
     * Aldol (REENTER); an owner who leaves takes their party's members out with them.
     */
    public static final AgentBossRunDefinition SCARGA = new AgentBossRunDefinition("scarga", ExpeditionType.SCARGA,
            551_030_100, 551_030_200, -1, 9_270_047, List.of(), 9_201_134, -1, AgentBossPriorityFight.forKey("scarga"),
            true, AgentBossRules.markerItems("scarga"), AgentBossRules.neverTargetFakes("scarga"), true,
            AgentBossRunDefinition.DeathMode.REENTER);

    // slot: horntail
    /**
     * Horntail (P18), an expedition of 6-30 at Lv100 or more, once a day: sign up with the Mark of the Squad (2083004)
     * on 240050400 with its registering window (no choices, as Adobis's). The Start puts everyone on 240060000; the
     * fight runs over three maps (boss-rules.json stageMaps): each pre-head map's head, then on through next00 to
     * 240060200, where the owner wakes Horntail at the reactor. Ranged and magic members take the heads, melee members
     * the legs and tail. The Crystal of Roots (2083002) on each map is the way out, before and after the clear, and
     * warps only the talker. A member that dies revives in Leafre, is moved to 240050400 (plan decision 11a) and enters
     * again by the Mark (REENTER); an owner who leaves takes their party's members out with them.
     */
    public static final AgentBossRunDefinition HORNTAIL = new AgentBossRunDefinition("horntail",
            ExpeditionType.HORNTAIL, 240_050_400, 240_060_200, -1, 2_083_004, List.of(), 2_083_002, -1,
            AgentBossPriorityFight.forKey("horntail"), true, AgentBossRules.markerItems("horntail"),
            AgentBossRules.neverTargetFakes("horntail"), true, AgentBossRunDefinition.DeathMode.REENTER);

    // slot: showa
    /**
     * The Boss (P19), Showa's expedition of 3-30 at Lv100 or more, no daily quota: sign up with Konpei (9120201) on
     * 801040004 with his registering window (no choices, as Adobis's), fight on 801040100: the gang first, then the
     * chain Bodyguard A, Bodyguard B and The Boss once the owner drops the comb on the reactor (boss-rules.json, where
     * the comb is a marker item members leave on the floor). Konpei (9120202) there is the way out before the clear;
     * after it his yes moves only the talker into 801040101, still inside the run, so members follow their owner there
     * and leave with them. A dead companion revives in Showa Town and waits there (decision 16); an owner who leaves
     * takes their party's members out with them.
     */
    public static final AgentBossRunDefinition SHOWA = new AgentBossRunDefinition("showa", ExpeditionType.SHOWA,
            801_040_004, 801_040_100, 801_040_101, 9_120_201, List.of(), 9_120_202, -1,
            AgentBossPriorityFight.forKey("showa"), false, AgentBossRules.markerItems("showa"),
            AgentBossRules.neverTargetFakes("showa"), true);

    // slot: pinkbean
    /**
     * Pink Bean (P20), an expedition of 6-30 at Lv120 or more, once a day: sign up with the Forgotten Temple Manager
     * (2141001) on 270050000 with his registering window (no choices, as Adobis's), fight on 270050100: the five waves
     * (healer Ariel first), then Pink Bean once the stage-5 drop wakes it. The statues and the 1-HP statue are never
     * targets, and the stage-5 drop stays on the floor (boss-rules.json); ranged and magic members keep out of
     * Solomon's and Rex's deadly boxes. 2141002 on the battle map is the way out, before and after the clear, and warps
     * only the talker. A member that dies revives inside, on 270050200 (REVIVE_INSIDE), and walks back through in00 (a
     * stage map); an owner who leaves takes their party's members out with them.
     */
    public static final AgentBossRunDefinition PINKBEAN = new AgentBossRunDefinition("pinkbean",
            ExpeditionType.PINKBEAN, 270_050_000, 270_050_100, -1, 2_141_001, List.of(), 2_141_002, -1,
            AgentBossPriorityFight.forKey("pinkbean"), true, AgentBossRules.markerItems("pinkbean"),
            AgentBossRules.neverTargetFakes("pinkbean"), true, AgentBossRunDefinition.DeathMode.REVIVE_INSIDE);

    private static final List<AgentBossRunDefinition> ALL = List.of(EASY_BALROG, ZAKUM, BALROG_NORMAL, LATANICA,
            NEO_BERGAMOT, NEO_DUNAS, NEO_AUFHEBEN, NEO_OBERON, NEO_NIBELUNG, PAPULATUS, SCARGA, HORNTAIL, SHOWA,
            PINKBEAN);

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
