package server.agents.capabilities.expedition.gpq;

import client.BuffStat;
import client.Character;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.NightWalker;
import constants.skills.Rogue;
import server.agents.capabilities.navigation.AgentMovementSkillPolicy;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.runtime.AgentRuntimeEntry;

/**
 * The roles Sharenian asks of a party (Shuang's own list, GPQ plan B1), read off a member's level and skills: a member
 * at Lv30 or under fetches Sharen III's crown (under30gate) and gives the King's Corridor gate its rock; a thief with
 * Dark Sight carries the Rusty Key across the invincible Dark Muscle Stones; a magician with Teleport (or a hermit
 * with Flash Jump) climbs the two altar towers walking never reaches (AgentGpqMovementSkillPolicy); Haste carries a
 * spear up the second tower.
 */
final class AgentGpqRoles {
    /** The crown room's gate lets in Lv30 and under; the rock that keeps the air off asks Lv10. */
    static final int JUNIOR_MAX_LEVEL = 30;
    static final int ROCK_MIN_LEVEL = 10;
    /** The second altar tower's jump stair, hasted: the G0 probe reached it at speed 140 and jump 120. */
    static final int HASTED_SPEED = 140;
    static final int HASTED_JUMP = 120;
    private static final int[] DARK_SIGHT_SKILL_IDS = {Rogue.DARK_SIGHT, NightWalker.DARK_SIGHT};
    private static final int[] HASTE_SKILL_IDS = {Assassin.HASTE, Bandit.HASTE, NightWalker.HASTE};

    private AgentGpqRoles() {
    }

    /** A member at Lv30 or under (and Lv10 or more, to wear a rock): the crown and the sacrifice. */
    static boolean junior(Character member) {
        return member.getLevel() <= JUNIOR_MAX_LEVEL && member.getLevel() >= ROCK_MIN_LEVEL;
    }

    /** Its Dark Sight (a thief or a Night Walker), or 0. */
    static int darkSight(Character member) {
        return learned(member, DARK_SIGHT_SKILL_IDS);
    }

    /** Its Haste (a thief at 2nd job or above), or 0. */
    static int haste(Character member) {
        return learned(member, HASTE_SKILL_IDS);
    }

    /** A thief who can cross the Dark Muscle Stones under Dark Sight. */
    static boolean shade(Character member) {
        return darkSight(member) != 0;
    }

    /** A member with Teleport or Flash Jump learned: it climbs every altar tower in the Room of Justice. */
    static boolean skilledClimber(Character member) {
        return AgentMovementSkillPolicy.skillId(member, AgentNavigationGraph.EdgeType.TELEPORT) != 0
                || AgentMovementSkillPolicy.skillId(member, AgentNavigationGraph.EdgeType.FLASH_JUMP) != 0;
    }

    /** A member that climbs the second tower's jump stair: hasted (or able to be), or quick and springy enough. */
    static boolean hastedClimber(Character member) {
        return haste(member) != 0 || member.getTotalMoveSpeedStat() >= HASTED_SPEED
                && member.getTotalJumpStat() >= HASTED_JUMP;
    }

    static boolean inDarkSight(Character member) {
        return member.getBuffedValue(BuffStat.DARKSIGHT) != null;
    }

    /** A member with Haste puts it up whenever it has run out; true when it cast it now. */
    static boolean keepHaste(AgentGpqPorts ports, AgentGpqRun run, Character member, long nowMs) {
        int skill = haste(member);
        if (skill == 0 || member.getBuffedValue(BuffStat.SPEED) != null || !run.ready(member.getId(), nowMs)) {
            return false;
        }
        return cast(ports, run, member, skill, nowMs);
    }

    /** A shade puts Dark Sight up whenever it is off; true when it cast it now. */
    static boolean keepDarkSight(AgentGpqPorts ports, AgentGpqRun run, Character member, long nowMs) {
        int skill = darkSight(member);
        if (skill == 0 || inDarkSight(member) || !run.ready(member.getId(), nowMs)) {
            return false;
        }
        return cast(ports, run, member, skill, nowMs);
    }

    private static boolean cast(AgentGpqPorts ports, AgentGpqRun run, Character member, int skill, long nowMs) {
        AgentRuntimeEntry entry = ports.entry(member.getId());
        if (entry == null || !ports.buff(entry, member, skill)) {
            return false;
        }
        run.busy(member.getId(), nowMs + AgentGpqMoves.HIT_PAUSE_MS);
        return true;
    }

    private static int learned(Character member, int[] skillIds) {
        for (int skillId : skillIds) {
            if (member.getSkillLevel(skillId) > 0) {
                return skillId;
            }
        }
        return 0;
    }
}
