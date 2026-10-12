package server.life.autonomy.balrog;

/** Normal Balrog claw active during the sealed phase (8830002, P11). */
public final class NormalBalrogInitialClawBehavior extends EasyBalrogBehavior {
    public static final int MOB_ID = EasyBalrogEncounterService.NORMAL_INITIAL_CLAW_ID;

    public NormalBalrogInitialClawBehavior() {
        super(false);
    }

    @Override
    public int mobId() {
        return MOB_ID;
    }
}
