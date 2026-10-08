package server.life.autonomy.balrog;

import server.life.autonomy.BossAction;

/** Easy Balrog body (8830007). */
public final class EasyBalrogBodyBehavior extends EasyBalrogBehavior {
    public static final int MOB_ID = 8_830_007;

    /**
     * Whether the body's skill casts (145, 133) tell clients to play the skill1 action.
     * False by default: the cast packet carries no action, so clients play no skill1
     * animation and no Skill1 sound, and the status still applies at impact.
     * GM6 !balrogprobe telegraph switches it; it is never saved, so a restart restores false.
     */
    private static volatile boolean telegraphSkillActions = false;

    public static void setTelegraphSkillActions(boolean telegraph) {
        telegraphSkillActions = telegraph;
    }

    public static boolean telegraphSkillActions() {
        return telegraphSkillActions;
    }

    @Override
    public int mobId() {
        return MOB_ID;
    }

    @Override
    public boolean telegraphSkillAction(BossAction.Skill skill) {
        return telegraphSkillActions;
    }
}
