package server.agents.capabilities.expedition.balrog;

import server.agents.field.AgentBalrogTestFixtureService;

import java.util.Set;

/** Authoritative IDs and live-test limits for the Easy Balrog expedition, and Normal Balrog's maps (P11). */
public final class AgentBalrogDefinition {
    public static final int RECRUIT_MAP = 105100100;
    public static final int BATTLE_MAP = 105100400;
    public static final int CLEAR_MAP = 105100401;
    public static final int ENTRY_NPC = 1061014;
    public static final int BATTLE_EXIT_NPC_ID = 1_061_018;
    public static final int REWARD_EXIT_PORTAL_ID = 2;
    public static final int REWARD_REACTOR_ID = 1_052_002;
    /** Normal Balrog's boss key: its kill switch, potion standard and boss rules catalog entry. */
    public static final String NORMAL_KEY = "balrog_normal";
    /** Normal Balrog's battle and reward maps (WZ twins of Easy's) and the reward room's chest. */
    public static final int NORMAL_BATTLE_MAP_ID = 105_100_300;
    public static final int NORMAL_CLEAR_MAP_ID = 105_100_301;
    public static final int NORMAL_REWARD_REACTOR_ID = 1_052_001;
    public static final int PARTY_CAPACITY = 6;
    public static final int ROSTER_SIZE = 12;
    public static final int LEVEL = AgentBalrogTestFixtureService.LEVEL;
    public static final int BODY_MOB = 8830007;
    public static final int RELEASED_CLAW_MOB = 8830008;
    public static final int INITIAL_CLAW_MOB = 8830009;
    public static final int RELEASE_SEAL_MOB = 8830013;
    public static final int JR_BALROG_ADD = 6400008;
    public static final int CRIMSON_BALROG_ADD = 6400009;
    public static final Set<Integer> CLAW_MOBS = Set.of(RELEASED_CLAW_MOB, INITIAL_CLAW_MOB);
    public static final Set<Integer> SUMMONED_ADDS = Set.of(JR_BALROG_ADD, CRIMSON_BALROG_ADD);
    public static final Set<Integer> COMBAT_MOBS = AgentBalrogTestFixtureService.BALROG_COMBAT_MOBS;

    private AgentBalrogDefinition() {
    }
}
