package config;

/**
 * Living-world (resident bots, quest lines, contracts, roster) deployment settings.
 * Content lives under {@link #CONTENT_DIR}; see docs/LIVING_RPG_PLAN.md.
 */
public class LivingWorldConfig {
    /** Master switch. Off: no personas are provisioned, no hooks do work. */
    public boolean ENABLED = false;
    /** World and channel the Residents live on (one shard for now). */
    public int WORLD = 0;
    public int CHANNEL = 1;
    /** Directory with personas/, quests/, contracts/, arcs/, rumors/, loadouts.yaml, command-phrases.yaml. */
    public String CONTENT_DIR = "scripts/config/living-world";

    // Residents
    /** Residents of a town go to standby this long after the last player leaves it. */
    public int RESIDENT_SLEEP_AFTER_MS = 300000;
    /** How often the resident runtime reconciles presence, routines and leases. */
    public int RESIDENT_TICK_MS = 5000;
    /** Comma-separated TownLife profile ids whose Residents stay materialized even with no players (ops/testing). */
    public String ALWAYS_AWAKE_TOWNS = "";
    /** Reply-length threshold above which a Resident whispers instead of using a chat bubble. */
    public int RESIDENT_BUBBLE_MAX_CHARS = 70;

    // TypeSafe budget for Resident conversation
    /** Free-form judgments a player may trigger per day before Residents only understand exact phrases. */
    public int JEV_PER_PLAYER_PER_DAY = 60;
    /** Minimum gap between two judgments for the same Persona. */
    public int JEV_PERSONA_COOLDOWN_MS = 3000;
    /** Cache window for repeated (player, persona, phrase) ASK/ACT answers. */
    public int JEV_ANSWER_CACHE_MS = 600000;
    /** Confidence at or above which a chat-intent ACT is stored as a learned phrase. */
    public double LEARNED_PHRASE_MIN_CONFIDENCE = 0.90;
    /** Learned phrases with fewer hits than this expire after LEARNED_PHRASE_TTL_DAYS. */
    public int LEARNED_PHRASE_MIN_HITS = 3;
    public int LEARNED_PHRASE_TTL_DAYS = 30;

    // Bonds and contracts
    public int BOND_ACQUAINTED_POINTS = 25;
    public int BOND_TRUSTED_POINTS = 75;
    public int BOND_SWORN_POINTS = 150;
    /** Minutes a contract offer stays open once stated. */
    public int CONTRACT_OFFER_MINUTES = 10;
    /** Owner offline this long -> active companions are benched. */
    public int LEADER_OFFLINE_BENCH_MS = 1200000;

    // Roster and bench
    public int ROSTER_MAX = 10;
    /** Bench ledger accrual interval. */
    public int BENCH_LEDGER_INTERVAL_MS = 600000;
    /** Fraction of live yield credited to offline bench work. */
    public double BENCH_FARM_EFFICIENCY = 0.6;
    /** Bench farming (all assignments except TRAIN/REST) capped per account per day, hours. */
    public int BENCH_FARM_HOURS_PER_DAY = 16;
    /** Benched TRAIN companions materialized at their camps at once (0 disables live bench sessions). */
    public int BENCH_LIVE_MAX = 4;
    /** Length of one live training slice before the next benched companion gets a turn (unless a player is watching). */
    public int BENCH_LIVE_SLICE_MS = 900000;
    /** Distinct item stacks a companion's haul can hold. */
    public int HAUL_MAX_STACKS = 24;
    /** Pace: benched companions stop accruing at ownerLevel - PACE_GAP (shrinks with bond stage). */
    public int PACE_GAP = 3;
    /** Catch-up multiplier applies below ownerLevel - MAX_LAG. */
    public int MAX_LAG = 8;
    public double CATCH_UP_MAX_MULTIPLIER = 3.0;
    /** Apprentice bonus per level of gap (in party, co-present), capped at APPRENTICE_MAX_LEVELS. */
    public double APPRENTICE_BONUS_PER_LEVEL = 0.15;
    public int APPRENTICE_MAX_LEVELS = 6;
    /** Meso cost of an instant recall whistle (item id configured in loadouts.yaml -> whistleItemId). */
    public int RECALL_WHISTLE_ITEM_ID = 0;

    // Rumors
    public int RUMOR_TICK_MS = 60000;
    public int RUMOR_LINE_PACING_MS = 3500;

    public void validate() {
        if (WORLD < 0 || CHANNEL < 1) {
            throw new IllegalArgumentException("living_world.WORLD must be >= 0 and CHANNEL >= 1");
        }
        if (BENCH_FARM_EFFICIENCY < 0 || BENCH_FARM_EFFICIENCY > 1) {
            throw new IllegalArgumentException("living_world.BENCH_FARM_EFFICIENCY must be within 0..1");
        }
        if (ROSTER_MAX < 1 || ROSTER_MAX > 50) {
            throw new IllegalArgumentException("living_world.ROSTER_MAX must be within 1..50");
        }
        if (BOND_ACQUAINTED_POINTS >= BOND_TRUSTED_POINTS || BOND_TRUSTED_POINTS >= BOND_SWORN_POINTS) {
            throw new IllegalArgumentException("living_world bond thresholds must increase");
        }
    }
}
