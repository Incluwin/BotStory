package config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
    /**
     * A citizen that goes AFK on a training field pauses 30 s to this long and only where it is safe (decision D6,
     * docs/BOT_CONCEPT_PLAN.md F8); in towns, Free Market rooms and lobbies the 5-15 min AFK stays. At most 120000.
     * 0 = fields AFK like towns, 5-15 min (the rule before 2026-10-27).
     */
    public int FIELD_AFK_MAX_MS = 110000;
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
    // Party megaphones and players' whispers to citizens (docs/BOT_CONCEPT_PLAN.md F5c)
    /** Kill switch: a short citizen PQ party's leader megaphones once (a real Super Megaphone); needs the whispers on. */
    public boolean LF_SMEGA_ENABLED = false;
    /** A forming party still short this long after it formed sends its megaphone. */
    public int LF_SMEGA_AFTER_MS = 240000;
    /** Megaphones one forming party sends at most. */
    public int LF_SMEGA_PER_PARTY = 1;
    /** Megaphones a citizen with none buys at a load (never above PartyMegaphones.STOCK_CAP), with this chance. */
    public int LF_SMEGA_STOCK = 2;
    public double LF_SMEGA_RESTOCK_CHANCE = 0.3d;
    /** Kill switch: players' whispers reach citizens (seat requests, replies, polite answers); false = ignored as before. */
    public boolean CITIZEN_WHISPERS_ENABLED = false;
    /** A citizen answers one player's out-of-the-blue whispers at most this often. */
    public int WHISPER_REPLY_GAP_MS = 120000;
    /** A player's whisper this soon after a citizen whispered them answers that whisper. */
    public int WHISPER_THREAD_MS = 900000;
    /** A party waits this long past its patience for a player who whispered in, took the invite and walks over. */
    public int WHISPER_JOIN_COMING_MS = 300000;
    // Jump quests (docs/BOT_CONCEPT_PLAN.md F5b)
    /** Kill switch: Victoria citizens climb Pet-Walking Road and the Forest of Patience (cohort weight JQ). */
    public boolean JQ_ENABLED = false;
    /** The courses citizens run (JumpQuestCourses keys). */
    public String JQ_COURSES = "pwr,fop_a";
    /** Mesos a citizen keeps on top of Shane's fee before it pays it. */
    public int JQ_FEE_RESERVE = 20000;
    /** A citizen's chance to clear one stage (about seven jumps), stable per citizen within this range. */
    public double JQ_STAGE_SUCCESS_MIN = 0.3d;
    public double JQ_STAGE_SUCCESS_MAX = 0.7d;
    /** Its chance rises this much for each stage it clears without a fall (this session). */
    public double JQ_PRACTICE_STEP = 0.03d;
    /** Falls before it gives up. */
    public int JQ_FALLS_MAX = 8;
    /** An attempt gives up after this long. */
    public int JQ_ATTEMPT_MAX_MS = 1200000;
    /** No JQ plan for this long after one. */
    public int JQ_COOLDOWN_MS = 21600000;

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
    /**
     * Bot guild alliances (docs/BOT_GUILD_ALLIANCES_PLAN.md): citizen guilds found, join and leave stock alliances by
     * how close they are, invite a player's guild they know, and answer a player's invite. Off: no new alliance, invite
     * or leaving; the alliances already formed stay as they are. Needs GUILDS_ENABLED.
     */
    public boolean ALLIANCES_ENABLED = true;
    /** Citizen-led alliances to keep standing; past this the closest pairs wait. */
    public int ALLIANCE_TARGET = 4;
    /** Minutes between alliance checks on the social tick. */
    public int ALLIANCE_CHECK_MINUTES = 30;
    /** The tie (0..1, GuildTies) at which two citizen guilds found an alliance, or a guild joins one. */
    public double ALLIANCE_FOUND_TIE = 0.5d;
    /** A citizen guild whose mean tie to the rest of its alliance stays under this for ALLIANCE_LEAVE_AFTER_DAYS leaves. */
    public double ALLIANCE_LEAVE_TIE = 0.2d;
    public int ALLIANCE_LEAVE_AFTER_DAYS = 3;
    /**
     * Shared minutes (map minutes plus party minutes twice, living_acquaintance) between a player guild's players and an
     * alliance's citizens before the alliance invites that guild, or a citizen guild takes that guild's invite.
     */
    public int ALLIANCE_PLAYER_MINUTES = 240;
    /** Days before a citizen alliance asks the same player guild again. */
    public int ALLIANCE_REASK_DAYS = 7;
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
     * List or vendor (docs/ECONOMY_PLAN.md E3): a citizen keeps the equips its stall would sell for well over the
     * shop's price instead of vendoring them. False: every citizen vendors as before.
     */
    public boolean MARKET_HOLD_FOR_STALL = true;
    /** An equip is worth listing when the price index values it at least this many times its shop price. */
    public double MARKET_HOLD_OVER_NPC = 1.5d;
    /** Equips a merchant citizen keeps for its stall at most (the best by index price over shop price). */
    public int MARKET_HOLD_MERCHANT = 8;
    /** The same for a citizen that never opens a stall: 0 vendors everything, as before. */
    public int MARKET_HOLD_OTHERS = 0;
    /**
     * Decision D6 (docs/ECONOMY_PLAN.md section 7): browsing citizens buy at other citizens' stalls too (players' first),
     * through the real shop purchase, so the sale is journaled, taxed and teaches the price index.
     */
    public boolean MARKET_BUY_FROM_CITIZENS = true;
    /**
     * Buy, don't receive, gear (E3): a citizen's level-band upgrade becomes an errand to buy a fitting piece at a stall;
     * only when none turns up within MARKET_GEAR_ERRAND_HOURS (or it cannot pay) is the piece handed to it as before.
     */
    public boolean MARKET_BUY_GEAR = true;
    public int MARKET_GEAR_ERRAND_HOURS = 24;
    /** The most a citizen on a gear errand pays: this many times the index price of what it buys. */
    public double MARKET_GEAR_OVER_INDEX = 1.3d;
    /**
     * Citizens scroll their own gear (E3, CitizenScrolling): out and about, one uses a scroll from its bag on a worn piece
     * with slots left, about one each eight minutes, up to SCROLLS_PER_DAY; browsing, one with meso to spare buys a fitting scroll
     * at a stall when its bag has none. Never a scroll that can destroy the piece.
     */
    public boolean MARKET_SCROLLING = true;
    public int SCROLLS_PER_DAY = 8;
    /** The least a scroll from its bag is worth to its job to be used (points of its attack; +2 main stat is 1.2). */
    public double SCROLL_MIN_VALUE = 0.25d;
    /** The least a scroll is worth to its job to be bought (+1 attack is 1). */
    public double SCROLL_BUY_MIN_VALUE = 1.0d;
    /** The most a citizen pays for a scroll: this many times its index price. */
    public double SCROLL_BUY_OVER_INDEX = 1.3d;
    /** What it keeps back when buying a scroll: this many times the meso its level should carry. */
    public double SCROLL_BUY_RESERVE = 1.5d;
    /**
     * Citizens go to sell when their equip bag fills (CitizenSellTrips): a loaded citizen with SELL_TRIP_FREE_SLOTS free
     * equip slots or fewer goes to the nearest potion shop it can walk to at its next plan and sells its trash equips and
     * spare drops there, at most once each SELL_TRIP_EVERY_HOURS. Its only shop visits were supply trips, so one
     * carrying a big potion stock never went and its bag filled (63 % of citizens on gear errands, 10-29).
     */
    public boolean SELL_TRIPS_ENABLED = true;
    public int SELL_TRIP_FREE_SLOTS = 8;
    public int SELL_TRIP_EVERY_HOURS = 6;

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

    // Minigames citizens play in towns (docs/BOT_CONCEPT_PLAN.md F5a)
    /** Kill switch: false = no PLAY plan; citizens never open, join or play an omok or match-card room. */
    public boolean MINIGAMES_ENABLED = false;
    /** Comma list of the town maps citizens play in; a map whose fieldLimit forbids minigames is skipped. */
    public String MINIGAME_TOWNS = "100000000,101000000,102000000,103000000,220000000";
    /** A citizen's usual think time per move, mostly near the short end. */
    public int MINIGAME_THINK_MIN_MS = 1000;
    public int MINIGAME_THINK_MAX_MS = 8000;
    /** Matches a citizen plays on a PLAY session before it goes on with its day. */
    public int MINIGAME_GAMES_MIN = 2;
    public int MINIGAME_GAMES_MAX = 5;
    /** A room nobody sits down in closes after this; a citizen's second such room ends its PLAY session. */
    public int MINIGAME_ROOM_WAIT_MS = 180000;
    /** Nothing happened in a room this long (a player who never readies or moves): the citizen gets up. */
    public int MINIGAME_IDLE_MS = 150000;
    /** Percent of citizens who play well; the rest play casually and improve slowly with their record. */
    public int MINIGAME_STRONG_PERCENT = 5;
    /** No PLAY plan for a citizen this long after its PLAY session. */
    public int MINIGAME_COOLDOWN_MS = 1800000;

    // Citizens remember the people they meet (docs/CITIZEN_PERSON_MEMORY_PLAN.md)
    /** Kill switch: false = no memory read or written; greetings, invites and grounding as before. */
    public boolean MEMORY_ENABLED = true;
    /** People one citizen remembers; for someone new past it, it forgets the least memorable. */
    public int MEMORY_PEOPLE_MAX = 30;
    /** Things it remembers about one person (the heaviest stay). */
    public int MEMORY_EPISODES_MAX = 6;
    /** Remembered (citizen, player) pairs server-wide; the hourly prune forgets the oldest past it. */
    public int MEMORY_TOTAL_MAX = 20000;
    /** A memory that has not changed this long is forgotten by the hourly prune. */
    public int MEMORY_FORGET_DAYS = 60;
    /** An episode's weight halves every this many days; it is forgotten under weight 1. */
    public double MEMORY_HALF_LIFE_DAYS = 10.0d;
    /** Warmth halves every this many days. */
    public double MEMORY_WARMTH_HALF_LIFE_DAYS = 14.0d;
    /** Apart at least this long, meeting again is a reunion worth a greeting. */
    public int MEMORY_RETURN_AFTER_MINUTES = 180;
    /** One reunion greeting per player from all citizens this often. */
    public int MEMORY_GREET_GAP_MS = 300000;

    // Citizens learn where to train (docs/BOT_CONCEPT_PLAN.md F2.1, CitizenSpots)
    /** Kill switch: false = no stint booked and the uniform random pick among the fitting training fields, as before. */
    public boolean CITIZEN_SPOT_LEARNING_ENABLED = false;
    /** The UCB weight: how much a field citizens measured less often is tried anyway (0 = never). */
    public double CITIZEN_SPOT_EXPLORE = 0.3;
    /** What a field full to its capacity with loaded citizens costs its score (0 = crowds do not matter). */
    public double CITIZEN_SPOT_CROWDING = 0.5;
    /** How sharp the weighted draw is: lower picks the best field more often (0 = always the best). */
    public double CITIZEN_SPOT_TEMPERATURE = 0.15;
    /** A TRAIN stint shorter than this is not booked: too short to say what the field pays. */
    public int CITIZEN_SPOT_MIN_MS = 300000;

    // The needs board: citizens ask for real things they lack (docs/BOT_CONCEPT_PLAN.md F1, server.living.population.needs)
    /** Kill switch: false = no need derived, posted or listed, no board spawned, "B>" chatter and gear as before. */
    public boolean NEEDS_ENABLED = false;
    /** Open item needs (SUPPLY, SCROLL, GEAR) a citizen has at once; a forming party's PARTY need is apart from them. */
    public int NEEDS_PER_CITIZEN = 2;
    /** A SUPPLY or SCROLL need ends unmet after a random time in this range (hours). */
    public int NEEDS_EXPIRE_MIN_HOURS = 24;
    public int NEEDS_EXPIRE_MAX_HOURS = 48;
    /** Decision D4: this share of a citizen's free gear upgrades becomes a want, granted as before after the deadline. */
    public double NEEDS_GEAR_WANT_SHARE = 0.3d;
    public int NEEDS_GEAR_DEADLINE_HOURS = 48;
    /** A citizen with a basket scroll that fits a worn piece and the purse for it asks for it at a refresh this often. */
    public double NEEDS_SCROLL_CHANCE = 0.15d;
    /** A SUPPLY need pays the potion's NPC shop price times this (the index has no market for shop goods). */
    public double NEEDS_SUPPLY_PREMIUM = 1.15d;
    /** Requests one player holds at once, and how long each hold lasts. */
    public int NEEDS_CLAIMS_PER_PLAYER = 3;
    public int NEEDS_CLAIM_MINUTES = 60;
    /** A citizen whispers its need to a player it likes on its map at most once per player this often. */
    public int NEEDS_WHISPER_GAP_MINUTES = 60;
    /** A loaded citizen's needs are derived again from its state this often (and at every load). */
    public int NEEDS_REFRESH_MINUTES = 15;
    /** The same request (kind and item) is not posted again this long after it ended. */
    public int NEEDS_REPOST_HOURS = 12;

    // Boss expeditions citizens run themselves (docs/CITIZEN_EXPEDITIONS_PLAN.md)
    /**
     * The bosses BOSS_RUN_BOSSES may name: only those the server moves itself once a bot signed up (Balrog,
     * AgentBossCitizenRuns), so a run with no human in it is a real fight.
     */
    public static final Set<String> CITIZEN_BOSSES = Set.of("easy_balrog", "balrog_normal");
    /** Kill switch: false = citizens at an expedition lobby only gather and invite. */
    public boolean BOSS_RUNS_ENABLED = true;
    /** Comma list of the bosses citizen parties sign up for (easy_balrog, balrog_normal). */
    public String BOSS_RUN_BOSSES = "easy_balrog,balrog_normal";
    /** Citizen expeditions at once in all. */
    public int BOSS_RUNS_MAX = 1;
    /** How many a citizen expedition's party fills to (citizens and the humans it invited; at most 6). */
    public int BOSS_RUN_SEATS = 6;
    /** A party of 6 whose citizens are all this level or more goes for Normal Balrog, else Easy. */
    public int BOSS_NORMAL_MIN_LEVEL = 80;
    /** No PQ or expedition plan for a citizen this long after its expedition ended. */
    public int BOSS_RUN_COOLDOWN_MS = 3600000;
    /** The leader waits this long for a human of its party to sign up with the entry NPC before it starts without. */
    public int BOSS_SIGNUP_WAIT_MS = 120000;

    // Clear records and the rival crew (docs/BOT_CONCEPT_PLAN.md F7)
    /** Every party quest or boss clear rivals.yaml lists is booked in living_clear (measuring only; on by default). */
    public boolean CLEAR_RECORDS_ENABLED = true;
    /** Kill switch (off until its live checks): rival crews, the records page, @records, their lines and requests. */
    public boolean RIVALS_ENABLED = false;
    /** A player's main this level or more gets a rival crew. */
    public int RIVALS_MIN_LEVEL = 20;
    /** A crew forms with at least this many citizens in band (and takes 4-5). */
    public int RIVALS_CREW_MIN = 3;
    /** At most this many in a crew. */
    public int RIVALS_CREW_MAX = 5;
    /** Citizens join a crew within this many levels of its main (decision D12: 5). */
    public int RIVALS_JOIN_BAND = 5;
    /** A member more than this many levels from the main leaves (wider than the join band: no churn at the edge). */
    public int RIVALS_LEAVE_BAND = 7;
    /** While its player is online a crew is checked this often (levels, members, its guild). */
    public int RIVALS_REFRESH_MINUTES = 30;
    /** A crew member on the main's map says something to them at most this often. */
    public int RIVALS_BANTER_GAP_MINUTES = 45;
    /** Help requests (F7.4a): off until the citizen Zakum run (F7.4b) exists, so no invitation leads nowhere. */
    public boolean RIVAL_HELP_REQUESTS_ENABLED = false;
    /** A crew asks its main along for Zakum at most this often. */
    public int RIVALS_HELP_GAP_HOURS = 12;
    /** Its help request stays on the needs board this long. */
    public int RIVALS_HELP_OPEN_MINUTES = 30;

    /** The bosses BOSS_RUN_BOSSES lets citizens run, lower case; none while BOSS_RUNS_ENABLED is off. */
    public Set<String> citizenBosses() {
        if (!BOSS_RUNS_ENABLED || BOSS_RUN_BOSSES == null) {
            return Set.of();
        }
        return Arrays.stream(BOSS_RUN_BOSSES.split(",")).map(key -> key.trim().toLowerCase(Locale.ROOT))
                .filter(key -> !key.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public void validate() {
        if (TARGET_CITIZENS < 0 || ONLINE_MAX < 0 || MAP_VISIBLE_MAX < 1) {
            throw new IllegalArgumentException("population.TARGET_CITIZENS/ONLINE_MAX must be >= 0 and MAP_VISIBLE_MAX >= 1");
        }
        if (MAP_VISIBLE_RETRY_MS < 1000 || MAP_VISIBLE_RETRY_LIMIT < 0 || MAP_VISIBLE_TRIM_PER_SWEEP < 0) {
            throw new IllegalArgumentException("population.MAP_VISIBLE_RETRY_MS must be >= 1000, MAP_VISIBLE_RETRY_LIMIT"
                    + " and MAP_VISIBLE_TRIM_PER_SWEEP >= 0");
        }
        if (FIELD_AFK_MAX_MS != 0 && (FIELD_AFK_MAX_MS < 30000 || FIELD_AFK_MAX_MS > 120000)) {
            throw new IllegalArgumentException("population.FIELD_AFK_MAX_MS must be within 30000..120000 (0 = the old"
                    + " 5-15 min field AFK)");
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
        if (MINIGAME_THINK_MIN_MS < 0 || MINIGAME_THINK_MAX_MS < MINIGAME_THINK_MIN_MS || MINIGAME_GAMES_MIN < 1
                || MINIGAME_GAMES_MAX < MINIGAME_GAMES_MIN || MINIGAME_ROOM_WAIT_MS < 10000 || MINIGAME_IDLE_MS < 10000
                || MINIGAME_STRONG_PERCENT < 0 || MINIGAME_STRONG_PERCENT > 100 || MINIGAME_COOLDOWN_MS < 0
                || MINIGAME_TOWNS == null) {
            throw new IllegalArgumentException("population.MINIGAME_THINK_MIN_MS..MAX_MS and MINIGAME_GAMES_MIN..MAX must be"
                    + " ranges (games >= 1), MINIGAME_ROOM_WAIT_MS and MINIGAME_IDLE_MS >= 10000, MINIGAME_STRONG_PERCENT"
                    + " 0-100, MINIGAME_COOLDOWN_MS >= 0 and MINIGAME_TOWNS set");
        }
        if (LF_SMEGA_AFTER_MS < 0 || LF_SMEGA_PER_PARTY < 0 || LF_SMEGA_STOCK < 0 || LF_SMEGA_STOCK > 2
                || LF_SMEGA_RESTOCK_CHANCE < 0 || LF_SMEGA_RESTOCK_CHANCE > 1 || WHISPER_REPLY_GAP_MS < 0
                || WHISPER_THREAD_MS < 0 || WHISPER_JOIN_COMING_MS < 0) {
            throw new IllegalArgumentException("population.LF_SMEGA_AFTER_MS, LF_SMEGA_PER_PARTY and the WHISPER_* times must"
                    + " be >= 0, LF_SMEGA_STOCK 0..2 and LF_SMEGA_RESTOCK_CHANCE 0..1");
        }
        if (JQ_STAGE_SUCCESS_MIN < 0.05 || JQ_STAGE_SUCCESS_MAX > 0.95 || JQ_STAGE_SUCCESS_MIN > JQ_STAGE_SUCCESS_MAX
                || JQ_PRACTICE_STEP < 0 || JQ_PRACTICE_STEP > 0.2 || JQ_FALLS_MAX < 1 || JQ_ATTEMPT_MAX_MS < 60000
                || JQ_COOLDOWN_MS < 0 || JQ_FEE_RESERVE < 0) {
            throw new IllegalArgumentException("population.JQ_STAGE_SUCCESS_MIN..MAX must lie in 0.05..0.95 (MIN <= MAX),"
                    + " JQ_PRACTICE_STEP 0..0.2, JQ_FALLS_MAX >= 1, JQ_ATTEMPT_MAX_MS >= 60000, JQ_COOLDOWN_MS and"
                    + " JQ_FEE_RESERVE >= 0");
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
        if (MEMORY_PEOPLE_MAX < 1 || MEMORY_EPISODES_MAX < 1 || MEMORY_EPISODES_MAX > 8 || MEMORY_TOTAL_MAX < 1
                || MEMORY_FORGET_DAYS < 1 || MEMORY_HALF_LIFE_DAYS <= 0 || MEMORY_WARMTH_HALF_LIFE_DAYS <= 0
                || MEMORY_RETURN_AFTER_MINUTES < 0 || MEMORY_GREET_GAP_MS < 0) {
            throw new IllegalArgumentException("population.MEMORY_PEOPLE_MAX, MEMORY_TOTAL_MAX and MEMORY_FORGET_DAYS must be"
                    + " >= 1, MEMORY_EPISODES_MAX 1..8 (the episodes column holds 8), the MEMORY_*_HALF_LIFE_DAYS > 0,"
                    + " MEMORY_RETURN_AFTER_MINUTES and MEMORY_GREET_GAP_MS >= 0");
        }
        if (BOSS_RUNS_MAX < 0 || BOSS_RUN_SEATS < 3 || BOSS_RUN_SEATS > 6 || BOSS_NORMAL_MIN_LEVEL < 1
                || BOSS_RUN_COOLDOWN_MS < 0 || BOSS_SIGNUP_WAIT_MS < 0) {
            throw new IllegalArgumentException("population.BOSS_RUNS_MAX and the BOSS_*_MS must be >= 0,"
                    + " BOSS_RUN_SEATS within 3..6 and BOSS_NORMAL_MIN_LEVEL >= 1");
        }
        for (String key : BOSS_RUN_BOSSES == null ? new String[0] : BOSS_RUN_BOSSES.split(",")) {
            String boss = key.trim().toLowerCase(Locale.ROOT);
            if (!boss.isEmpty() && !CITIZEN_BOSSES.contains(boss)) {
                throw new IllegalArgumentException("population.BOSS_RUN_BOSSES may name only " + CITIZEN_BOSSES
                        + " (bosses the server moves itself), not '" + boss + "'");
            }
        }
        if (CITIZEN_SPOT_EXPLORE < 0 || CITIZEN_SPOT_CROWDING < 0 || CITIZEN_SPOT_TEMPERATURE < 0 || CITIZEN_SPOT_MIN_MS < 0) {
            throw new IllegalArgumentException("population.CITIZEN_SPOT_EXPLORE, CITIZEN_SPOT_CROWDING,"
                    + " CITIZEN_SPOT_TEMPERATURE and CITIZEN_SPOT_MIN_MS must be >= 0");
        }
        if (NEEDS_PER_CITIZEN < 1 || NEEDS_EXPIRE_MIN_HOURS < 1 || NEEDS_EXPIRE_MAX_HOURS < NEEDS_EXPIRE_MIN_HOURS
                || NEEDS_GEAR_DEADLINE_HOURS < 1 || NEEDS_GEAR_WANT_SHARE < 0 || NEEDS_GEAR_WANT_SHARE > 1
                || NEEDS_SCROLL_CHANCE < 0 || NEEDS_SCROLL_CHANCE > 1 || NEEDS_SUPPLY_PREMIUM < 1
                || NEEDS_CLAIMS_PER_PLAYER < 1 || NEEDS_CLAIM_MINUTES < 1 || NEEDS_WHISPER_GAP_MINUTES < 0
                || NEEDS_REFRESH_MINUTES < 1 || NEEDS_REPOST_HOURS < 0) {
            throw new IllegalArgumentException("population.NEEDS_PER_CITIZEN, NEEDS_CLAIMS_PER_PLAYER, NEEDS_CLAIM_MINUTES,"
                    + " NEEDS_REFRESH_MINUTES and NEEDS_GEAR_DEADLINE_HOURS must be >= 1, NEEDS_EXPIRE_MIN_HOURS..MAX_HOURS a"
                    + " range from 1, NEEDS_GEAR_WANT_SHARE and NEEDS_SCROLL_CHANCE 0..1, NEEDS_SUPPLY_PREMIUM >= 1,"
                    + " NEEDS_WHISPER_GAP_MINUTES and NEEDS_REPOST_HOURS >= 0");
        }
        if (RIVALS_MIN_LEVEL < 1 || RIVALS_CREW_MIN < 2 || RIVALS_CREW_MAX < RIVALS_CREW_MIN || RIVALS_CREW_MAX > 6
                || RIVALS_JOIN_BAND < 0 || RIVALS_LEAVE_BAND < RIVALS_JOIN_BAND || RIVALS_REFRESH_MINUTES < 1
                || RIVALS_BANTER_GAP_MINUTES < 0 || RIVALS_HELP_GAP_HOURS < 0 || RIVALS_HELP_OPEN_MINUTES < 1) {
            throw new IllegalArgumentException("population.RIVALS_MIN_LEVEL, RIVALS_REFRESH_MINUTES and"
                    + " RIVALS_HELP_OPEN_MINUTES must be >= 1, RIVALS_CREW_MIN..MAX a range within 2..6, RIVALS_JOIN_BAND >= 0"
                    + " with RIVALS_LEAVE_BAND at least it, RIVALS_BANTER_GAP_MINUTES and RIVALS_HELP_GAP_HOURS >= 0");
        }
    }
}
