package server.life.autonomy.balrog;

import server.life.autonomy.BossAction;

/**
 * Normal Balrog body (8830000, P11), in a fight EasyBalrogEncounterService runs for companions. Its skill casts follow
 * the Easy body's switch (EasyBalrogBodyBehavior.telegraphSkillActions, GM6 !balrogprobe telegraph), so Fix B holds
 * for both bodies.
 */
public final class NormalBalrogBodyBehavior extends EasyBalrogBehavior {
    public static final int MOB_ID = EasyBalrogEncounterService.NORMAL_BODY_ID;

    public NormalBalrogBodyBehavior() {
        super(false);
    }

    @Override
    public int mobId() {
        return MOB_ID;
    }

    @Override
    public boolean telegraphSkillAction(BossAction.Skill skill) {
        return EasyBalrogBodyBehavior.telegraphSkillActions();
    }
}
