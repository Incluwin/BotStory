package server.agents.capabilities.combat;

import client.status.MonsterStatus;
import config.YamlConfig;
import constants.skills.Crusader;
import constants.skills.DragonKnight;
import constants.skills.WhiteKnight;
import server.life.Monster;

import java.util.EnumSet;
import java.util.Set;

/**
 * Armor/Power/Magic Crash only remove a monster buff; against an unbuffed mob they are a wasted
 * cast (and an immersion break: no player opens on a Kentaurus with Armor Crash). Mirrors what
 * {@link Monster#debuffMob(int)} would actually cancel, including the anti-immunity extension when
 * the server enables it.
 */
public final class AgentCrashSkillPolicy {
    private AgentCrashSkillPolicy() {
    }

    public static boolean isCrashSkill(int skillId) {
        return skillId == Crusader.ARMOR_CRASH || skillId == DragonKnight.POWER_CRASH || skillId == WhiteKnight.MAGIC_CRASH;
    }

    /** True when the target carries at least one buff this crash would cancel. */
    public static boolean worthCasting(int skillId, Monster target) {
        if (target == null) {
            return false;
        }
        for (MonsterStatus status : cancelledBy(skillId, YamlConfig.config.server.USE_ANTI_IMMUNITY_CRASH)) {
            if (target.isBuffed(status)) {
                return true;
            }
        }
        return false;
    }

    static Set<MonsterStatus> cancelledBy(int skillId, boolean antiImmunityCrash) {
        EnumSet<MonsterStatus> cancelled = EnumSet.noneOf(MonsterStatus.class);
        if (skillId == Crusader.ARMOR_CRASH) {
            cancelled.add(MonsterStatus.WEAPON_DEFENSE_UP);
            if (antiImmunityCrash) {
                cancelled.add(MonsterStatus.WEAPON_IMMUNITY);
                cancelled.add(MonsterStatus.MAGIC_IMMUNITY);
            }
        } else if (skillId == WhiteKnight.MAGIC_CRASH) {
            cancelled.add(MonsterStatus.MAGIC_ATTACK_UP);
            if (antiImmunityCrash) {
                cancelled.add(MonsterStatus.MAGIC_IMMUNITY);
            }
        } else if (skillId == DragonKnight.POWER_CRASH) {
            cancelled.add(MonsterStatus.WEAPON_ATTACK_UP);
            if (antiImmunityCrash) {
                cancelled.add(MonsterStatus.WEAPON_IMMUNITY);
            }
        }
        return cancelled;
    }
}
