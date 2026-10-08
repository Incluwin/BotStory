package server.agents.capabilities.expedition.zakum;

/**
 * Authoritative IDs for the Zakum expedition (P8). Zakum's parts are {@code constants.id.MobId}'s ZAKUM_1..3 (the
 * body: the fake 8800000, then 8800001 and 8800002) and ZAKUM_ARM_1..8 (8800003-8800010).
 */
public final class AgentZakumDefinition {
    /** Adobis's room behind the Door to Zakum, where the expedition is formed (scripts/npc/2030013.js). */
    public static final int RECRUIT_MAP_ID = 211042400;
    /** Zakum's Altar (scripts/event/ZakumBattle.js). */
    public static final int BATTLE_MAP_ID = 280030000;
    /** Adobis, who forms the expedition and signs members up. */
    public static final int ENTRY_NPC_ID = 2030013;
    /** Amon, the altar's way out (scripts/npc/2030010.js). */
    public static final int EXIT_NPC_ID = 2030010;
    /** The Eye of Fire, which the altar takes to summon Zakum. */
    public static final int EYE_ITEM_ID = 4001017;
    /** The altar reactor that summons Zakum (scripts/reactor/2111001.js). */
    public static final int ALTAR_REACTOR_ID = 2111001;

    private AgentZakumDefinition() {
    }
}
