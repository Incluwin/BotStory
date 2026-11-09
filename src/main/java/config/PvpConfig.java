package config;

/**
 * Fights between characters (docs/PVP_PLAN.md). The balance keys are the plan's priors (section 2); the self-play
 * matrix tunes them later. Nothing here acts while {@link #PVP_ENABLED} is off, except the GM's {@code !duel}.
 */
public class PvpConfig {
    /** Master switch for player-facing duels. {@code !duel} (GM 5) works either way, for live checks. */
    public boolean PVP_ENABLED = false;

    /** Seconds of uninterrupted hitting for a median attacker to knock out a median-HP victim of its level. */
    public double PVP_TTK_SECONDS = 12d;
    /** Gear ratio exponent: damage follows (gear / job median)^this, so twice the median gear deals 1.62x. */
    public double PVP_GEAR_EXPONENT = 0.7d;
    /** One attack never takes more than this percent of the victim's max HP; its lines shrink together to fit. */
    public int PVP_MAX_HIT_PERCENT = 25;
    /** Each target past the first takes this much less ... */
    public double PVP_AOE_FALLOFF = 0.15d;
    /** ... but never less than this share. */
    public double PVP_AOE_FLOOR = 0.6d;
    /** The most characters one attack hits, whatever the skill's mob count. */
    public int PVP_MAX_TARGETS = 3;
    /** Damage changes this percent per level of difference ... */
    public int PVP_LEVEL_PERCENT = 2;
    /** ... up to this percent either way. */
    public int PVP_LEVEL_CAP_PERCENT = 10;
    /** Defense cut = def / (def + this x attacker level), using WDEF against physical and MDEF against magic ... */
    public int PVP_DEF_K = 40;
    /** ... capped at this percent. */
    public int PVP_DEF_CAP_PERCENT = 25;
    /** Physical attacks miss at most this percent of the time, however much avoid the victim has. */
    public int PVP_PHYSICAL_MISS_CAP_PERCENT = 25;
    /** Magic attacks miss at most this percent of the time. */
    public int PVP_MAGIC_MISS_CAP_PERCENT = 10;
    /**
     * Even classes (PD15, the user's call 2026-11-08): a hit is scaled by the victim class's median HP over the
     * class-balanced median, so a median fighter of every class lasts {@link #PVP_TTK_SECONDS}, and the class defense
     * skills (Achilles, High Defense, Combo Barrier, Power Guard, Magic Guard, Meso Guard) do not act in a duel. Off:
     * a warrior's double HP stands, and the two guard keys below apply.
     */
    public boolean PVP_EVEN_CLASSES = true;
    /** Magic Guard moves this share of its PvE share to MP (full strength makes the mage the tankiest class). */
    public double PVP_MAGIC_GUARD_FACTOR = 0.5d;
    /** Meso Guard leaves this share of a hit on HP (PvE: 0.5). */
    public double PVP_MESO_GUARD_HP_SHARE = 0.75d;
    /** Healing from any source restores at most this percent of max HP (and MP) per window ... */
    public int PVP_HEAL_PERCENT = 10;
    /** ... of this many ms; a potion is refused until the window since the last drink has passed. */
    public int PVP_HEAL_WINDOW_MS = 5000;
    /** How old the server's copy of a moving human may be (V0.3: a movement packet every 510 ms). */
    public int PVP_LAG_WINDOW_MS = 600;
    /** A human's hurtbox is shrunk by this many px on each side, so moving out of a swing works. */
    public int PVP_HUMAN_HURTBOX_INSET_PX = 6;
    /** A PvP hit counts at most once per this many ms per attacker (rapid-fire skills: a fifth of it). */
    public int PVP_MIN_ATTACK_GAP_MS = 250;
    /** Seconds of a round; when it runs out the higher remaining HP share wins. */
    public int PVP_ROUND_SECONDS = 180;
    /** Seconds of countdown before a round, with the fighters frozen out of PvP. */
    public int PVP_COUNTDOWN_SECONDS = 3;
    /**
     * Learned rates (PD16, the user's call 2026-11-09): after every duel each fighter's raw damage moves its class's
     * and its job path and band's rate a step toward what it measured (server.pvp.PvpRateLearner), so the rates follow
     * whoever fights instead of one practice run's sample. Off: the reference table's rates stand.
     */
    public boolean PVP_LEARN_RATES = false;
    /** The step a class's learned rate takes toward one fighter's measurement (a running mean of about 1/step). */
    public double PVP_LEARN_CLASS_STEP = 0.05d;
    /** The step a job path and band's learned rate takes; it is used once it has this many fighters ... */
    public double PVP_LEARN_JOB_STEP = 0.1d;
    /** ... and until then the class's learned rate stands in for it. */
    public int PVP_LEARN_JOB_MIN_FIGHTERS = 8;

    public void validate() {
        if (PVP_TTK_SECONDS <= 0d || PVP_GEAR_EXPONENT <= 0d || PVP_GEAR_EXPONENT > 1d) {
            throw new IllegalArgumentException("pvp.PVP_TTK_SECONDS must be > 0 and PVP_GEAR_EXPONENT in (0, 1]");
        }
        if (PVP_MAX_HIT_PERCENT < 1 || PVP_MAX_HIT_PERCENT > 100 || PVP_MAX_TARGETS < 1
                || PVP_AOE_FALLOFF < 0d || PVP_AOE_FLOOR <= 0d || PVP_AOE_FLOOR > 1d) {
            throw new IllegalArgumentException("pvp: PVP_MAX_HIT_PERCENT 1..100, PVP_MAX_TARGETS >= 1,"
                    + " PVP_AOE_FALLOFF >= 0 and PVP_AOE_FLOOR in (0, 1]");
        }
        if (PVP_LEVEL_PERCENT < 0 || PVP_LEVEL_CAP_PERCENT < 0 || PVP_DEF_K <= 0
                || PVP_DEF_CAP_PERCENT < 0 || PVP_DEF_CAP_PERCENT > 90) {
            throw new IllegalArgumentException("pvp: level percents >= 0, PVP_DEF_K > 0, PVP_DEF_CAP_PERCENT 0..90");
        }
        if (PVP_PHYSICAL_MISS_CAP_PERCENT < 0 || PVP_PHYSICAL_MISS_CAP_PERCENT > 90
                || PVP_MAGIC_MISS_CAP_PERCENT < 0 || PVP_MAGIC_MISS_CAP_PERCENT > 90) {
            throw new IllegalArgumentException("pvp: miss caps must be 0..90");
        }
        if (PVP_MAGIC_GUARD_FACTOR < 0d || PVP_MAGIC_GUARD_FACTOR > 1d
                || PVP_MESO_GUARD_HP_SHARE <= 0d || PVP_MESO_GUARD_HP_SHARE > 1d) {
            throw new IllegalArgumentException("pvp: PVP_MAGIC_GUARD_FACTOR 0..1, PVP_MESO_GUARD_HP_SHARE (0, 1]");
        }
        if (PVP_HEAL_PERCENT < 0 || PVP_HEAL_WINDOW_MS < 0 || PVP_LAG_WINDOW_MS < 0
                || PVP_HUMAN_HURTBOX_INSET_PX < 0 || PVP_MIN_ATTACK_GAP_MS < 0) {
            throw new IllegalArgumentException("pvp: heal, lag, inset and attack-gap values must be >= 0");
        }
        if (PVP_LEARN_CLASS_STEP <= 0d || PVP_LEARN_CLASS_STEP > 1d || PVP_LEARN_JOB_STEP <= 0d
                || PVP_LEARN_JOB_STEP > 1d || PVP_LEARN_JOB_MIN_FIGHTERS < 1) {
            throw new IllegalArgumentException("pvp: PVP_LEARN_CLASS_STEP and PVP_LEARN_JOB_STEP in (0, 1],"
                    + " PVP_LEARN_JOB_MIN_FIGHTERS >= 1");
        }
        if (PVP_ROUND_SECONDS < 10 || PVP_COUNTDOWN_SECONDS < 0) {
            throw new IllegalArgumentException("pvp: PVP_ROUND_SECONDS >= 10 and PVP_COUNTDOWN_SECONDS >= 0");
        }
    }
}
