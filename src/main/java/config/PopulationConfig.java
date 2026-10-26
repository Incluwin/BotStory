package config;

/**
 * Populated-server settings: generated citizens with schedules, progression, chatter, guilds,
 * grounded conversation and market participation. Requires {@code living_world.ENABLED}; the
 * population module runs inside the living world. See docs/POPULATED_SERVER_PLAN.md.
 */
public class PopulationConfig {
    /** Master switch for citizens. */
    public boolean ENABLED = false;
    /** Directory with cohorts.yaml, careers.yaml, names.yaml, voices.yaml, guild-names.yaml, regions.yaml, fields.yaml, chatter/. */
    public String CONTENT_DIR = "scripts/config/population";
    /** Seed for deterministic generation (names, looks, schedules). */
    public long SEED = 20260921L;

    // Presence (plan 3)
    /** How many citizens the registry keeps alive; births refill toward this after retirements. */
    public int TARGET_CITIZENS = 300;
    /** Hard cap on citizens loaded as characters at once (CPU bound); the rest progress on the ledger. */
    public int ONLINE_MAX = 60;
    /** Comma-separated channels citizens are spread across. */
    public String CHANNELS = "1";
    /** Presence sweep interval. */
    public int SWEEP_MS = 60000;
    /** Ledger interval for citizens that are scheduled online but not loaded. */
    public int LEDGER_MS = 600000;
    /** Global multiplier on cohort online probability (1.0 = as authored; 0.5 = half as many online). */
    public double PRESENCE_MULTIPLIER = 1.0;
    /** How many maps away from a human a loaded citizen may be before it is unloaded to the ledger (0 = only human maps). */
    public int PERCEPTION_HOPS = 1;
    /**
     * Visible citizens cap on any single map instance (docs/SCALE_PLATFORM_PLAN.md section 1): a login or move to a map
     * holding this many goes elsewhere, waits, or logs in virtual; a map over it sends its excess on.
     */
    public int MAP_VISIBLE_MAX = 40;
    /** Kill switch for MAP_VISIBLE_MAX: false = no per-map cap (the behaviour before 2026-10-25). */
    public boolean MAP_VISIBLE_CAP_ENABLED = true;
    /** A move with a purpose (PQ lobby, instructor) to a full map asks again this often... */
    public int MAP_VISIBLE_RETRY_MS = 5000;
    /** ...this many times at most, then it is dropped. */
    public int MAP_VISIBLE_RETRY_LIMIT = 36;
    /** Citizens a sweep sends on from maps over the cap, at most. */
    public int MAP_VISIBLE_TRIM_PER_SWEEP = 8;
    /** Fraction of a real casual player's leveling speed a citizen follows (1.0 = same). */
    public double LIFE_PACE = 1.0;
    /** Citizens born per daily churn tick when below target. */
    public int BIRTHS_PER_DAY = 6;
    /** How long a citizen keeps logging in, in days (min..max), before it retires. */
    public int LIFE_DAYS_MIN = 60;
    public int LIFE_DAYS_MAX = 400;
    /** When seeding an empty registry, citizens are backdated up to this many days so levels look like a mature server. */
    public int SEED_BACKDATE_DAYS = 240;
    /**
     * Server shutdown: loaded citizens unload (a full character save each) on this many threads at once. Each holds one
     * database connection (the pool has 10). Measured on the live MySQL settings, 240 citizens: 1 = 21.8 s, 4 = 10.2 s,
     * 8 = 8.7 s.
     */
    public int SHUTDOWN_LOGOUT_THREADS = 4;
    /** Server shutdown: the longest the citizens' unloads may take; any still waiting then unload with their channel. */
    public int SHUTDOWN_LOGOUT_BUDGET_MS = 45000;
    /**
     * A citizen whose life reaches its third or fourth job takes it at its instructor (El Nath's chiefs, Leafre's elders):
     * its next session starts on their map, it walks up, advances and goes about its day (CitizenJobErrands). false = the
     * kill switch: every job comes off-screen with the next catch-up instead.
     */
    public boolean JOB_ERRANDS_ENABLED = true;
    /** How long a citizen stands with its instructor before it advances. */
    public int JOB_ERRAND_TALK_MS = 6000;
    /** How long a citizen walks up to its instructor at most; then it advances where it stands. */
    public int JOB_ERRAND_WALK_MAX_MS = 45000;

    // Travel on foot while someone can see it (docs/CITIZEN_PORTAL_TRAVEL_PLAN.md)
    /**
     * A citizen on a map a player is on goes to another map on foot: to the right portal and through it, map to map, up
     * to the boarding NPC at a dock; once nobody can see it, the cheap way. false = the kill switch: every move is the
     * delayed map change, as before.
     */
    public boolean PORTAL_TRAVEL_ENABLED = true;
    /** How often a move on foot is checked: walk or cheap, arrived, stuck. */
    public int PORTAL_TRAVEL_CHECK_MS = 1000;
    /** A walk that stands on one map this long without crossing gives up its way (out by the nearest portal, then waits). */
    public int PORTAL_TRAVEL_HOP_TIMEOUT_MS = 120000;
    /** A move still on foot after this long waits for nobody to watch, then goes the cheap way. */
    public int PORTAL_TRAVEL_MAX_MS = 600000;
    /** How long its TownLife may finish what it is doing before it sets off anyway. */
    public int PORTAL_TRAVEL_TOWNLIFE_GRACE_MS = 30000;
    /** How long it stands at a dock's boarding NPC, as if talking, before it is gone. */
    public int PORTAL_TRAVEL_BOARD_MS = 2000;

    // Chatter (plan 5)
    public boolean CHATTER_ENABLED = true;
    /** Map chat lines per minute allowed on a crowded town map; fields get a third of this. */
    public int CHATTER_TOWN_LINES_PER_MINUTE = 12;
    /** Minimum time between two lines from the same citizen on the same channel. */
    public int CHATTER_CITIZEN_COOLDOWN_MS = 45000;
    /** Server-wide megaphones per hour from citizens. */
    public int CHATTER_SMEGA_PER_HOUR = 2;
    /** Recent template ids a citizen will not repeat. */
    public int CHATTER_RECENT_MEMORY = 12;

    // Conversation (plan 6)
    public boolean CONVERSATION_ENABLED = true;
    /** Generated replies a human may draw per day across all citizens before they fall back to templates. */
    public int CONVERSATION_PER_PLAYER_PER_DAY = 40;
    /** Minimum gap between generated replies from one citizen. */
    public int CONVERSATION_CITIZEN_COOLDOWN_MS = 8000;
    /** Generated replies in flight at once (sized to the model host). */
    public int CONVERSATION_MAX_CONCURRENT = 2;
    /** Jev judgments per player per day spent deciding who a line was addressed to. */
    public int CONVERSATION_JEV_PER_PLAYER_PER_DAY = 60;

    // Social (plan 4)
    public boolean GUILDS_ENABLED = true;
    /** Bot guilds to keep alive. */
    public int GUILD_TARGET = 8;
    /**
     * GPQ plan decision 8: a citizen guild master promotes a human member of its guild to Jr. Master once they have
     * shared this many minutes (map minutes plus party minutes twice, as the guild invite counts them), so the human can
     * register the guild for the Guild Quest at Shuang. 0 = never.
     */
    public int GUILD_PROMOTE_AFTER_MINUTES = 180;
    public boolean BUDDIES_ENABLED = true;
    /** Shared party minutes with a human before a citizen sends one buddy request. */
    public int BUDDY_REQUEST_AFTER_MINUTES = 30;

    // Market (plan 7)
    public boolean MARKET_ENABLED = true;
    /** Citizen stalls open at once in the Free Market. */
    public int MARKET_STALLS_MAX = 6;
    /** Meso a citizen may spend per day buying from human shops. */
    public long MARKET_BUY_BUDGET_PER_DAY = 200000L;

    /**
     * A citizen that died beside a field boss whose group is on trains elsewhere while that boss stands there, at most
     * this long from its death (0-10800000 ms; 0 = it goes back as before).
     */
    public int FIELD_BOSS_AVOID_MS = 3600000;

    // Party quests citizens run themselves (docs/CITIZEN_PARTY_QUESTS_PLAN.md)
    /** Kill switch: false = citizens at a PQ lobby only gather and invite, as before. */
    public boolean PQ_RUNS_ENABLED = true;
    /** Comma list of the quests citizen parties go into (kpq, hpq). */
    public String PQ_RUN_QUESTS = "kpq,hpq";
    /** Citizen runs at once on one channel. */
    public int PQ_RUNS_PER_CHANNEL = 1;
    /** Citizen runs at once in all. */
    public int PQ_RUNS_MAX = 2;
    /** A lobby party short of its quest's minimum gives up after this long. */
    public int PQ_FORM_PATIENCE_MS = 600000;
    /** A seat stays open this long for a human a lobby party invited, and the party waits for the answer. */
    public int PQ_HUMAN_SEAT_HOLD_MS = 60000;
    /** No PQ plan for a citizen this long after its run ended. */
    public int PQ_RUN_COOLDOWN_MS = 1800000;
    /** A lobby party short of its seats calls at most this many citizens over, one a minute, never more than it needs. */
    public int PQ_CALLS_PER_PARTY = 4;

    public void validate() {
        if (TARGET_CITIZENS < 0 || ONLINE_MAX < 0 || MAP_VISIBLE_MAX < 1) {
            throw new IllegalArgumentException("population.TARGET_CITIZENS/ONLINE_MAX must be >= 0 and MAP_VISIBLE_MAX >= 1");
        }
        if (MAP_VISIBLE_RETRY_MS < 1000 || MAP_VISIBLE_RETRY_LIMIT < 0 || MAP_VISIBLE_TRIM_PER_SWEEP < 0) {
            throw new IllegalArgumentException("population.MAP_VISIBLE_RETRY_MS must be >= 1000, MAP_VISIBLE_RETRY_LIMIT"
                    + " and MAP_VISIBLE_TRIM_PER_SWEEP >= 0");
        }
        if (PRESENCE_MULTIPLIER < 0 || LIFE_PACE <= 0) {
            throw new IllegalArgumentException("population.PRESENCE_MULTIPLIER must be >= 0 and LIFE_PACE > 0");
        }
        if (LIFE_DAYS_MIN < 1 || LIFE_DAYS_MAX < LIFE_DAYS_MIN) {
            throw new IllegalArgumentException("population.LIFE_DAYS_MIN..MAX must be a positive range");
        }
        if (JOB_ERRAND_TALK_MS < 0 || JOB_ERRAND_WALK_MAX_MS < 0) {
            throw new IllegalArgumentException("population.JOB_ERRAND_TALK_MS and JOB_ERRAND_WALK_MAX_MS must be >= 0");
        }
        if (PORTAL_TRAVEL_CHECK_MS < 100 || PORTAL_TRAVEL_HOP_TIMEOUT_MS < 1000 || PORTAL_TRAVEL_MAX_MS < 1000
                || PORTAL_TRAVEL_TOWNLIFE_GRACE_MS < 0 || PORTAL_TRAVEL_BOARD_MS < 0) {
            throw new IllegalArgumentException("population.PORTAL_TRAVEL_CHECK_MS must be >= 100, PORTAL_TRAVEL_HOP_TIMEOUT_MS"
                    + " and PORTAL_TRAVEL_MAX_MS >= 1000, PORTAL_TRAVEL_TOWNLIFE_GRACE_MS and PORTAL_TRAVEL_BOARD_MS >= 0");
        }
        if (CHANNELS == null || CHANNELS.isBlank()) {
            throw new IllegalArgumentException("population.CHANNELS must name at least one channel");
        }
        if (SHUTDOWN_LOGOUT_THREADS < 1 || SHUTDOWN_LOGOUT_BUDGET_MS < 0) {
            throw new IllegalArgumentException("population.SHUTDOWN_LOGOUT_THREADS must be >= 1 and SHUTDOWN_LOGOUT_BUDGET_MS >= 0");
        }
        if (FIELD_BOSS_AVOID_MS < 0 || FIELD_BOSS_AVOID_MS > 10800000) {
            throw new IllegalArgumentException("population.FIELD_BOSS_AVOID_MS must be within 0..10800000");
        }
        if (PQ_RUNS_PER_CHANNEL < 0 || PQ_RUNS_MAX < 0 || PQ_FORM_PATIENCE_MS < 0 || PQ_HUMAN_SEAT_HOLD_MS < 0
                || PQ_RUN_COOLDOWN_MS < 0 || PQ_CALLS_PER_PARTY < 0) {
            throw new IllegalArgumentException(
                    "population.PQ_RUNS_PER_CHANNEL, PQ_RUNS_MAX, PQ_CALLS_PER_PARTY and the PQ_*_MS must be >= 0");
        }
    }
}
