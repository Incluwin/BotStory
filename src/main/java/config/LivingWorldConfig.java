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
    /** Time zone for routine hours and rumor schedules (e.g. America/New_York); empty = the JVM default, which Cosmic sets to server.TIMEZONE. */
    public String ROUTINE_ZONE = "";
    /** Comma-separated TownLife profile ids whose Residents stay materialized even with no players (ops/testing). */
    public String ALWAYS_AWAKE_TOWNS = "";
    /** Reply-length threshold above which a Resident whispers instead of using a chat bubble. */
    public int RESIDENT_BUBBLE_MAX_CHARS = 70;
    /** Mirror quest state into the client quest log (needs the Quest.wz patch from tools\living-world-questlog.bat shipped). */
    public boolean QUEST_LOG_MIRROR = false;
    /** Effect path drawn over a Resident for a player who can take one of its quests; empty = none. */
    public String QUEST_MARKER_AVAILABLE = "Custom/UI/UIWindow.img/QuestMarker/available";
    /** Effect path drawn over a Resident for a player who can hand something in to it; empty = none. */
    public String QUEST_MARKER_READY = "Custom/UI/UIWindow.img/QuestMarker/ready";
    /** How often a marker is replayed; about one animation length so it reads as continuous. 0 = markers off. */
    public int QUEST_MARKER_PULSE_MS = 1100;
    /** How long a player's marker states are reused before being recomputed (quest events recompute sooner). */
    public int QUEST_MARKER_REFRESH_MS = 10000;
    /** Invisible NPC placed under each marker, for that player only, so clicking the icon opens the Resident's menu; 0 = icons are not clickable. */
    public int QUEST_MARKER_HITBOX_NPC = 9970999;
    /**
     * Px from the Resident's feet up to where the hitbox NPC stands. The client hit-tests an NPC as its
     * image standing on its position (a raised origin is ignored), so the NPC itself is lifted: 72 puts the
     * 46 px hitbox over the icon, whose centre living_world_markers.py draws 95 px up. 0 = on the foothold.
     */
    public int QUEST_MARKER_HITBOX_LIFT = 72;
    /** How often hitboxes catch up with walking Residents. */
    public int QUEST_MARKER_HITBOX_TRACK_MS = 250;

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
    /**
     * Decision 3, which authored end rules still end a rostered companion's contract on their own (ContractService.Mode):
     * AUTHORED (all of them: the travel hours, outlevelled, three leader deaths, bench tolerance), PERMANENT_ON_ROSTER
     * (none; arcs and a dismissal still end one, and wages keep working) or PERMANENT_AT_SWORN (authored terms until
     * the bond reaches SWORN). In every mode a walk-out waits while the companion is committed to a run.
     */
    public String ROSTER_CONTRACT_MODE = "PERMANENT_ON_ROSTER";

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
    /**
     * Decision 7: a companion called from its camp on the same continent arrives within this many ms (the road
     * estimate is capped here); off-island trips keep their 180 s.
     */
    public int RECALL_MAX_ETA_MS = 60000;
    /**
     * The Adventurer's Roster: double-clicking this USE item in the inventory opens the Crew window and does not use
     * it up (a hotkey sends nothing, P0 probe 2). A recruit or grant, and a login, hand one to a roster owner without
     * it. 0 = off. The client still names 2430011 "Agent Summon" until its String.wz / Custom.wz rename ships (P3).
     */
    public int ROSTER_BOOK_ITEM_ID = 2430011;

    // Companion budget v1 (docs/BOT_MANAGEMENT_P0_RESULTS.md, Measurement); CompanionBudget reads these
    /** Agents one online human may have following them (their loaded companions); a party holds 5 besides them. */
    public int COMPANION_PER_HUMAN = 5;
    /** Live bench bots charged to one owner at once. */
    public int BENCH_LIVE_PER_OWNER = 2;
    /** Live bench bots server-wide. BENCH_LIVE_MAX stays the bench rotation size; bench loads that ask the budget are also held under this. */
    public int BENCH_LIVE_GLOBAL = 8;
    /** Bots an expedition may load in one burst (split among owners by fair share later). */
    public int EXPEDITION_BURST_MAX = 12;
    /** Registered Agents server-wide at which no further companion, bench or expedition load is admitted. */
    public int COMPANION_BUDGET_CEILING = 300;
    /** Minimum gap between two companion or bench loads for the same owner. */
    public int COMPANION_SPAWN_INTERVAL_MS = 2000;
    /** A citizen answers a party invite after a random delay in MIN..MAX, like a player would. */
    public int CITIZEN_INVITE_REPLY_MIN_MS = 900;
    public int CITIZEN_INVITE_REPLY_MAX_MS = 1800;
    /**
     * Decision 17: roster companions in a party earn their own kill EXP, but the humans' split is computed as if they
     * were absent, so a full crew never slows its owner down. false = the stock split shares every kill with them.
     */
    public boolean COMPANION_EXP_SHARE = true;

    // Hired hands and Resident allocation (P4, decisions 2 b, 4 b, 18 b, 19 b)
    /**
     * Hired citizens ({@code hire:<characterId>}) one owner may keep; they count toward ROSTER_MAX, so at most that. The
     * default is ROSTER_MAX: a friend without Residents fills a party and a bench from the hire list (flow 4).
     */
    public int HIRE_MAX_PER_OWNER = 10;
    /** Minutes a citizen guest spends in a player's party before it offers to stay on as a hired hand. */
    public int HIRE_OFFER_PARTY_MINUTES = 10;
    /** Minutes a citizen whose offer was declined or went unanswered waits before it asks that player again. */
    public int HIRE_OFFER_COOLDOWN_MINUTES = 720;
    /** Decision 18: Residents one friend may keep on their roster at once (0 = none). */
    public int RESIDENT_CAP_PER_OWNER = 5;
    /** Decision 18: a friend away this many days releases their Residents to go home (0 = never). */
    public int RESIDENT_OWNER_INACTIVE_DAYS = 14;

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
        if (RECALL_MAX_ETA_MS < 0) {
            throw new IllegalArgumentException("living_world.RECALL_MAX_ETA_MS must be >= 0");
        }
        if (ROSTER_BOOK_ITEM_ID < 0) {
            throw new IllegalArgumentException("living_world.ROSTER_BOOK_ITEM_ID must be >= 0 (0 = off)");
        }
        if (!java.util.List.of("AUTHORED", "PERMANENT_ON_ROSTER", "PERMANENT_AT_SWORN").contains(ROSTER_CONTRACT_MODE)) {
            throw new IllegalArgumentException(
                    "living_world.ROSTER_CONTRACT_MODE must be AUTHORED, PERMANENT_ON_ROSTER or PERMANENT_AT_SWORN");
        }
        if (BOND_ACQUAINTED_POINTS >= BOND_TRUSTED_POINTS || BOND_TRUSTED_POINTS >= BOND_SWORN_POINTS) {
            throw new IllegalArgumentException("living_world bond thresholds must increase");
        }
        if (COMPANION_PER_HUMAN < 0 || COMPANION_PER_HUMAN > 5) {
            throw new IllegalArgumentException("living_world.COMPANION_PER_HUMAN must be within 0..5");
        }
        if (BENCH_LIVE_PER_OWNER < 0 || BENCH_LIVE_PER_OWNER > BENCH_LIVE_GLOBAL) {
            throw new IllegalArgumentException("living_world.BENCH_LIVE_PER_OWNER must be within 0..BENCH_LIVE_GLOBAL");
        }
        if (EXPEDITION_BURST_MAX < 0 || COMPANION_BUDGET_CEILING < 1 || COMPANION_SPAWN_INTERVAL_MS < 0) {
            throw new IllegalArgumentException(
                    "living_world.EXPEDITION_BURST_MAX and COMPANION_SPAWN_INTERVAL_MS must be >= 0, COMPANION_BUDGET_CEILING >= 1");
        }
        if (CITIZEN_INVITE_REPLY_MIN_MS < 0 || CITIZEN_INVITE_REPLY_MAX_MS < CITIZEN_INVITE_REPLY_MIN_MS) {
            throw new IllegalArgumentException(
                    "living_world.CITIZEN_INVITE_REPLY_MIN_MS must be >= 0 and <= CITIZEN_INVITE_REPLY_MAX_MS");
        }
        if (HIRE_MAX_PER_OWNER < 0 || HIRE_MAX_PER_OWNER > ROSTER_MAX) {
            throw new IllegalArgumentException("living_world.HIRE_MAX_PER_OWNER must be within 0..ROSTER_MAX");
        }
        if (HIRE_OFFER_PARTY_MINUTES < 1) {
            throw new IllegalArgumentException("living_world.HIRE_OFFER_PARTY_MINUTES must be >= 1");
        }
        if (HIRE_OFFER_COOLDOWN_MINUTES < 0) {
            throw new IllegalArgumentException("living_world.HIRE_OFFER_COOLDOWN_MINUTES must be >= 0");
        }
        if (RESIDENT_CAP_PER_OWNER < 0 || RESIDENT_CAP_PER_OWNER > ROSTER_MAX) {
            throw new IllegalArgumentException("living_world.RESIDENT_CAP_PER_OWNER must be within 0..ROSTER_MAX");
        }
        if (RESIDENT_OWNER_INACTIVE_DAYS < 0) {
            throw new IllegalArgumentException("living_world.RESIDENT_OWNER_INACTIVE_DAYS must be >= 0 (0 = never)");
        }
    }
}
