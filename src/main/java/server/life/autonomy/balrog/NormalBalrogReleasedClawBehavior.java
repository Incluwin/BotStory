package server.life.autonomy.balrog;

/** Normal Balrog claw released by the seal (8830001, P11); it summons the Jr. and Crimson Balrogs. */
public final class NormalBalrogReleasedClawBehavior extends EasyBalrogBehavior {
    public static final int MOB_ID = EasyBalrogEncounterService.NORMAL_RELEASED_CLAW_ID;

    public NormalBalrogReleasedClawBehavior() {
        super(false);
    }

    @Override
    public int mobId() {
        return MOB_ID;
    }
}
