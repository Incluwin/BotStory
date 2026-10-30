package server.life.autonomy.balrog;

import server.life.autonomy.BossAction;

/** Easy Balrog body (8830007). */
public final class EasyBalrogBodyBehavior extends EasyBalrogBehavior {
    public static final int MOB_ID = 8_830_007;

    /**
     * Whether the body's skill casts (145, 133) tell clients to play the skill1 action.
     * True by default: clients play the skill1 animation and its Skill1 sound, and the status
     * applies at impact. False (Fix B, P7A) sends the cast with no action. Fix B was a guess
     * during the 2026-10-08 client crash hunt; the crash was the 32-bit client running out of
     * address space (SOUND_DX8 got a NULL buffer), fixed by the large-address-aware client
     * (2026-10-09) and the WZ mapping fix (2026-10-14), so the telegraph is back on (2026-10-29).
     * GM6 !balrogprobe telegraph switches it; it is never saved, so a restart restores true.
     */
    private static volatile boolean telegraphSkillActions = true;

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
