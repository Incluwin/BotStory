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
    public int PVP_LEVEL_PERCENT = 1;
    /** ... up to this percent either way. */
    public int PVP_LEVEL_CAP_PERCENT = 5;
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
     * skills (Achilles, High Defense, Combo Barrier, Power Guard, Magic Guard, Meso Guard) do not act in a duel. It
     * evens the hit chance too (PD17, 2026-11-09): each side's accuracy and avoid count against its own class's median
     * at its level band ({@link server.pvp.PvpHitBalance}), so DEX/LUK classes no longer dodge warriors by build; and
     * defense (2026-11-11): a median victim meets weapon hits and spells with one common defense.
     * Off: a warrior's double HP stands, raw accuracy, avoid and defense decide, and the two guard keys below apply.
     */
    public boolean PVP_EVEN_CLASSES = true;
    /** Magic Guard moves this share of its PvE share to MP (full strength makes the mage the tankiest class). */
    public double PVP_MAGIC_GUARD_FACTOR = 0.5d;
    /** Meso Guard leaves this share of a hit on HP (PvE: 0.5). */
    public double PVP_MESO_GUARD_HP_SHARE = 0.75d;
    /** Healing from any source restores at most this percent of max HP (and MP) per window ... */
    public int PVP_HEAL_PERCENT = 10;
    /** ... of this many ms (an allowance refilling at that rate); a potion is refused while it is under half full. */
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
    /**
     * Learned toughness (the user's call 2026-11-11), with PVP_EVEN_CLASSES: after every duel each victim's class and
     * job path and band learn how much of its HP hits take against a reference victim, with the learned rates' steps,
     * and hits on it are scaled by that (server.pvp.PvpToughness). Off: the class HP table, avoid and defense evening
     * stand alone.
     */
    public boolean PVP_LEARN_TOUGHNESS = false;
    /**
     * Learned matchups (the user's call 2026-11-13), with PVP_EVEN_CLASSES: after every one-against-one duel of two
     * classes, the pair learns how much faster one side wore the other down, gear and levels divided out, and hits
     * between the two split the difference (server.pvp.PvpMatchups). Off: no per-pair correction.
     */
    public boolean PVP_LEARN_MATCHUPS = false;
    /**
     * Live gear medians (the user's call 2026-11-13): a fighter's gear edge and the coefficient's median attacker are
     * measured against the median gear of the characters online per job path and band, refreshed every 10 minutes
     * (server.pvp.PvpGearMedians), not the 2026-11-08 medians of scripts/config/pvp/reference.yaml. Off: the file's.
     */
    public boolean PVP_LIVE_GEAR_MEDIANS = false;
    /**
     * Varied pairings (the user's call 2026-11-13): practice skips a partner a citizen fought this many minutes ago or
     * less, and the learned matchups count a pairing at most once in that time (server.pvp.PvpPairings). 0: no limit.
     */
    public int PVP_PAIRING_REPEAT_MINUTES = 60;
    /**
     * The coliseum ladder (docs/PVP_PLAN.md V4): the Arena Master and Quartermaster in Ariant, rated best-of-three
     * matches against citizens, and citizens' own background ladder matches. Needs PVP_ENABLED. Technical:
     * server.living.population.coliseum.Coliseum.
     */
    public boolean COLISEUM_ENABLED = false;
    /** A coliseum round's length; a round still open then goes to the side with more of its HP left. */
    public int COLISEUM_ROUND_SECONDS = 90;
    /** The ladder's entry fee, in mesos a level of the human (a sink). */
    public int COLISEUM_FEE_PER_LEVEL = 20;
    /** Arena tonics a fighter may drink a round, the share of max HP and MP each restores, and the gap between two. */
    public int COLISEUM_TONICS = 3;
    public int COLISEUM_TONIC_PERCENT = 25;
    public int COLISEUM_TONIC_GAP_MS = 8000;
    /** Background ladder matches between citizens, simulated each social minute (none while a human watches; V4.4). */
    public int COLISEUM_SIM_PER_MINUTE = 20;
    /** V6: background team matches (2v2 or 3v3) between citizen teams, simulated each social minute. */
    public int COLISEUM_TEAM_SIM_PER_MINUTE = 6;
    /**
     * The weekly tournament (docs/PVP_PLAN.md V5): an 8-seat knockout a ladder bracket, on TOURNAMENT_DAY at
     * TOURNAMENT_HOUR server time, fought on TOURNAMENT_CHANNEL. Needs COLISEUM_ENABLED. Technical:
     * server.living.population.coliseum.Tournament.
     */
    public boolean TOURNAMENT_ENABLED = false;
    /** A day of the week (MONDAY..SUNDAY) and an hour 0-23, in the server's own time zone (PD12). */
    public String TOURNAMENT_DAY = "SATURDAY";
    public int TOURNAMENT_HOUR = 20;
    /** The channel the tournament is fought on: seated citizens come from it, and a seated player fights on it. */
    public int TOURNAMENT_CHANNEL = 1;
    /** Seats a bracket (a power of two up to 8); players who signed up take theirs first. */
    public int TOURNAMENT_SEATS = 8;
    /** Minutes between the starts of two rounds (a round still running holds the next back). */
    public int TOURNAMENT_ROUND_MINUTES = 6;
    /** Every seat's fee, in mesos a level (a sink; the house keeps a quarter of the pool). */
    public int TOURNAMENT_FEE_PER_LEVEL = 200;
    /** Days the champion's medal lasts. */
    public int TOURNAMENT_MEDAL_DAYS = 7;

    // The duel learner (docs/WORLD_SYSTEMS_PLAN.md I1.2, docs/PVP_PLAN.md V2.3): DuelLearner, DuelPolicies, SelfPlay
    /** Kill switch: false = no self-play, and every bot fights by the prior brain. */
    public boolean DUEL_LEARNING_ENABLED = false;
    /** Self-play runs from this hour (UTC) up to the other, past midnight too; equal = never. */
    public int DUEL_SELFPLAY_FROM_HOUR = 8;
    public int DUEL_SELFPLAY_TO_HOUR = 12;
    /** Self-play bouts under way at once. */
    public int DUEL_SELFPLAY_BOUTS = 2;
    /** Decisions a job learns from before it is measured against the prior brain. */
    public int DUEL_LEARN_DECISIONS = 4000;
    /** How often a learning fighter tries something else. */
    public double DUEL_LEARN_EPSILON = 0.15d;
    /** Measuring pairs (a bout with the learned table, one with the prior brain) a job's verdict needs. */
    public int DUEL_EVAL_PAIRS = 40;

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
        if (COLISEUM_ROUND_SECONDS < 10 || COLISEUM_FEE_PER_LEVEL < 0 || COLISEUM_TONICS < 0
                || COLISEUM_TONIC_PERCENT < 0 || COLISEUM_TONIC_PERCENT > 100 || COLISEUM_TONIC_GAP_MS < 0
                || COLISEUM_SIM_PER_MINUTE < 0 || COLISEUM_TEAM_SIM_PER_MINUTE < 0) {
            throw new IllegalArgumentException("pvp: COLISEUM_ROUND_SECONDS >= 10, COLISEUM_TONIC_PERCENT 0..100,"
                    + " other COLISEUM_* values >= 0");
        }
        try {
            java.time.DayOfWeek.valueOf(TOURNAMENT_DAY.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException bad) {
            throw new IllegalArgumentException("pvp.TOURNAMENT_DAY must be MONDAY..SUNDAY, not " + TOURNAMENT_DAY);
        }
        if (TOURNAMENT_HOUR < 0 || TOURNAMENT_HOUR > 23 || TOURNAMENT_CHANNEL < 1 || TOURNAMENT_ROUND_MINUTES < 1
                || TOURNAMENT_FEE_PER_LEVEL < 0 || TOURNAMENT_MEDAL_DAYS < 1
                || TOURNAMENT_SEATS != 2 && TOURNAMENT_SEATS != 4 && TOURNAMENT_SEATS != 8) {
            throw new IllegalArgumentException("pvp: TOURNAMENT_HOUR 0..23, TOURNAMENT_SEATS 2, 4 or 8,"
                    + " TOURNAMENT_CHANNEL, TOURNAMENT_ROUND_MINUTES and TOURNAMENT_MEDAL_DAYS >= 1, fee >= 0");
        }
    }
}
