package config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    /**
     * Mixed into each Resident's look pick (skin, face, hair, and gender where the persona sets none). 0 keeps the
     * looks Residents always had; any other number gives this server its own. A change re-dresses every Resident on
     * the next boot, so re-run the portrait tool (tools/living-world-client.bat) afterwards.
     */
    public long RESIDENT_LOOK_SEED = 0;
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

    // Friendship: a 1-10 meter over the same bond points (server.living.bond.Friendship)
    /** Bond points at which friendship levels 1..10 begin (7 = Close friend, 10 = Best friend). */
    public String FRIENDSHIP_LEVEL_POINTS = "1,25,50,75,110,150,200,260,330,400";
    /** Points for famed (and minus for defamed) by a player; the game allows it once a month per target. */
    public int FRIENDSHIP_FAME_POINTS = 8;
    /** Points for a gift from the Resident's menu, and for one of its favourites ({@code giftItemIds}). */
    public int FRIENDSHIP_GIFT_POINTS = 2;
    public int FRIENDSHIP_GIFT_FAVORITE_POINTS = 6;
    /** Gifts a Resident takes from one player per day (in ROUTINE_ZONE); later ones it turns down. */
    public int FRIENDSHIP_GIFTS_PER_DAY = 1;
    /** Points for a party quest cleared with the Resident in the party, counted this many times a day. */
    public int FRIENDSHIP_PQ_POINTS = 10;
    public int FRIENDSHIP_PQ_PER_DAY = 3;
    /**
     * Points for a boss killed with the Resident in the party on its map, counted this many times a day. Inside a
     * companion boss run the kills give none: the cleared run gives them once per companion (P12 G12).
     */
    public int FRIENDSHIP_BOSS_POINTS = 15;
    public int FRIENDSHIP_BOSS_PER_DAY = 3;
    /**
     * Per friendship level 1..10, the percent of a reward armour piece's slots a friend's 60% and 10% scrolls take
     * (0 = the friend does not use that grade yet). 100% scrolls always take. The friend scrolls with whichever grade
     * gives the better item for the player's job, so the result never gets worse as the level rises. A giver's own
     * weapon follows its authored recipe instead.
     */
    public String FRIENDSHIP_SCROLL_TAKES_60 = "0,0,0,70,80,95,100,100,100,100";
    public String FRIENDSHIP_SCROLL_TAKES_10 = "0,0,0,0,0,0,45,60,75,90";
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
    /**
     * Who may open the native Crew window (P10, docs/BOT_MANAGEMENT_P10_PLAN.md; server.living.roster.ui.NativeCrewWindow)
     * that a client with the P10 DLL offers on its hotkey: "off" (nobody: a HELLO is answered "not enabled"), "gm" (GM
     * characters only) or "all". The NPC-dialog Crew window, the cards and the Roster item work as before either way.
     * {@code !world crewwindow} changes it until the next restart; a window it no longer allows is closed.
     */
    public String CREW_WINDOW_NATIVE = "off";

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

    // Living bench (P5, decisions 12 a and 13 a): BenchDutyDispatcher and BenchYieldModel read these
    /**
     * Benched companions resting live in town (REST: cheap town presence) server-wide at once; 0 = none. Held at 4 (and
     * COMMERCE_LIVE_MAX at 2) until the P5 soak (2 humans x 5 active, the pools full) measures queue-lag p95 under 100 ms.
     */
    public int BENCH_TOWN_MAX = 4;
    /** Of those, resting in town charged to one owner at once. */
    public int BENCH_TOWN_PER_OWNER = 1;
    /** Length of one town slice (REST) before another benched companion gets a turn, unless a player is watching. */
    public int BENCH_TOWN_SLICE_MS = 3600000;
    /** Benched companions running a real Free Market stall (STALL, SELL: the COMMERCE pool) server-wide; 0 = none. */
    public int COMMERCE_LIVE_MAX = 2;
    /** Of those, stalls charged to one owner at once. */
    public int COMMERCE_PER_OWNER = 1;
    /** Length of one stall slice before another benched companion gets a turn, unless a player is watching. */
    public int COMMERCE_SLICE_MS = 7200000;
    /**
     * A STALL's markdown (BenchStallForm, user 2026-10-25): each of its own last stall slices that listed an item for its
     * whole length and sold none of it takes this share of the index off that item's price, down to SELL's 0.85 (where
     * browsing citizens buy); a sale puts it back at the index. 0 = always the index (only players buy).
     */
    public double BENCH_STALL_MARKDOWN_STEP = 0.05;
    /**
     * A live GATHER slice (BenchGatherForm, user 2026-10-25) goes to the best field for what its owner's quests need
     * when no monster at its camp drops it (the field where a round of kills brings the most); false = always its camp.
     */
    public boolean BENCH_GATHER_AT_DROPPERS = true;
    /** A live field companion out of supplies ends its hunt and restocks at a shop; after this long it unloads anyway. */
    public int BENCH_RESUPPLY_MAX_MS = 300000;
    /**
     * It walks to its shop when the shop is at most this many portal hops from its camp; farther, its slice ends once it
     * has set off and its next slice loads it at that shop. 0: every shop off its camp's map.
     */
    public int BENCH_RESUPPLY_MAX_HOPS = 10;
    /**
     * How many full live slices the ledger's prior (the BENCH_FARM_EFFICIENCY rates) counts for against the rates
     * measured at a camp: the offline credit is (slices x measured + this x prior) / (slices + this).
     */
    public double BENCH_YIELD_PRIOR_SLICES = 1.0;
    /**
     * Decision 19 (b): a companion's bench upkeep (30 meso per level an hour, which is also a hired hand's hourly wage)
     * is never more than this share of the meso an hour it is predicted to pick up at its camp (BenchYieldModel).
     */
    public double BENCH_UPKEEP_MAX_SHARE = 0.15;
    /** "Your call" (BenchDutyChooser): how much a camp measured less often is tried anyway (the UCB weight; 0 = never). */
    public double BENCH_CHOOSER_EXPLORE = 0.3;
    /** "Your call": the share of an hour's work one death an hour costs a camp's score (1 death/h at 0.5 halves it). */
    public double BENCH_CHOOSER_DEATH_COST = 0.5;
    /** A citizen answers a party invite after a random delay in MIN..MAX, like a player would. */
    public int CITIZEN_INVITE_REPLY_MIN_MS = 900;
    public int CITIZEN_INVITE_REPLY_MAX_MS = 1800;
    /**
     * Decision 17: roster companions in a party earn their own kill EXP, but the humans' split is computed as if they
     * were absent, so a full crew never slows its owner down. false = the stock split shares every kill with them.
     */
    public boolean COMPANION_EXP_SHARE = true;

    // Lending a companion to a friend, and the contracts' loot terms (docs/COMPANION_LENDING_PLAN.md)
    /**
     * Kill switch for lending: an owner lends a companion out with them to a mutual buddy or guild mate on their map
     * (CompanionLoans). false = nobody is offered "Lend to a friend...", and every loan out comes home on the next sweep.
     */
    public boolean COMPANION_LENDING_ENABLED = true;
    /** Companions one friend may have on loan at once. */
    public int COMPANION_LOANS_PER_BORROWER = 1;
    /** Companions one owner may have out on loan at once. */
    public int COMPANION_LOANS_PER_OWNER = 2;
    /** The longest loan offered; the card offers 30, 60 and 120 minutes up to it. */
    public int COMPANION_LOAN_MAX_MINUTES = 120;
    /** How long the friend's Yes/No to a loan stays open before it lapses. */
    public int COMPANION_LOAN_OFFER_MS = 60000;
    /**
     * Kill switch for the contracts' loot terms (lootShare, ownDropsPercent; LootTerms): what a contracted companion
     * loots is split between its own purse and bag and its owner's share in its haul. false = no split, as before.
     */
    public boolean COMPANION_LOOT_TERMS_ENABLED = true;
    /** Upkeep first: a companion keeps its loot meso until its purse holds this much per level, before any split. */
    public int COMPANION_LOOT_UPKEEP_MESO_PER_LEVEL = 1000;
    /** How often a companion's owed share of its loot is moved from its bag and purse into its haul. */
    public int COMPANION_LOOT_SETTLE_MS = 60000;

    // Letters: what happened while you were away (docs/BOT_CONCEPT_PLAN.md F3, CompanionLetters)
    /**
     * Kill switch for letters: the bench's news kept across a restart (bench.report rows), citizens asking after an
     * absent friend, the login digest by note with its one pink line, and gifts by Duey. false = none of it, and the
     * login says what it said before.
     */
    public boolean LETTERS_ENABLED = false;
    /** How long after a login the letters arrive: after the login's own note show, so they are shown once. */
    public int LETTERS_DELAY_MS = 6000;
    /** Notes one login may bring; the companions past the first ones share one crew letter. */
    public int LETTERS_MAX_NOTES = 5;
    /** The rarity rule: a found equip is news when its required level is within this many levels of its owner's. */
    public int LETTERS_EQUIP_LEVEL_SPAN = 10;
    /** The friendship level a Resident needs before it mails its owner a gift from what it found. */
    public int LETTERS_GIFT_FRIENDSHIP_LEVEL = 4;
    /** The chance, each bench ledger tick, that a Resident with something worth giving mails it. */
    public double LETTERS_GIFT_CHANCE = 0.03;
    /** Gifts one companion mails its owner in seven days. */
    public int LETTERS_GIFTS_PER_WEEK = 1;
    /** The warmth (PersonMemory, 0-100) a citizen needs before it asks after someone who is not around. */
    public int LETTERS_ASK_WARMTH = 40;
    /** The chance that an idle line of a citizen with such a friend offline is asking after them instead. */
    public double LETTERS_ASK_CHANCE = 0.1;
    /** Hours before any citizen asks after the same person again. */
    public int LETTERS_ASK_GAP_HOURS = 6;

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

    // Bench combat learning and "Keep my crew fresh" (P5, decisions 13 a and 15 b)
    /**
     * Decision 13 a: a companion grinding on a live bench slice with no human on its map runs the combat learner in
     * LEARN mode into its job's candidate table, which live play uses only once the arena's policy=candidate check
     * adopts it. false = bench grinders fight as everyone else does.
     */
    public boolean BENCH_COMBAT_LEARN = true;
    /** How often such a grinder still explores once every choice has had its tries (the arena uses 0.1). */
    public double BENCH_COMBAT_LEARN_EPSILON = 0.1;
    /** How often what bench grinders learned is written to the candidate files (and at shutdown). */
    public int BENCH_COMBAT_LEARN_SAVE_MS = 300000;
    /**
     * Decision 15 b, "Keep my crew fresh": OPT_IN offers each owner the toggle in the Crew window (off until they turn it
     * on); OFF hides it and swaps nobody.
     */
    public String AUTO_ROTATE_MODE = "OPT_IN";
    /** How often an owner's crew is looked at for a companion to swap out. */
    public int AUTO_ROTATE_TICK_MS = 30000;
    /** A companion swapped in or out is not swapped again for this long. */
    public int AUTO_ROTATE_COOLDOWN_MS = 600000;
    /** A companion this many levels or more off its owner (either way) is swapped for one closer. */
    public int AUTO_ROTATE_LEVEL_GAP = 10;

    // Party quests with companions (P6, docs/BOT_MANAGEMENT_PLAN.md flow 3)
    /**
     * Party quests whose entry NPC offers its party leader a lineup of the party and their companions, a comma list of
     * the keys {@link CompanionPqKeys#KNOWN} names (such as "kpq", Lakelis's, and "hpq", Tory's, whose desk also has
     * the owner's companions wait off her ledge). Empty = off: the NPC is stock.
     */
    public String COMPANION_PQ_ENABLED_KEYS = "";
    /** A companion KPQ run narrates the roles in map chat and nudges a stalled human, as a !kpqtest run does. */
    public boolean COMPANION_PQ_GUIDANCE = true;
    /**
     * The entry NPC says how long the lineup should take (a range, and who would struggle where) and picks the quickest
     * lineup within each owner's seats (the PQ clear-time estimate, server.agents...partyquest.pace). false = the kill
     * switch: her window and her lineup exactly as before the estimate.
     */
    public boolean COMPANION_PQ_CLEAR_TIME = true;
    /**
     * Character ids, a comma list, whose runs as party leader teach the clear-time records no leader term (how fast
     * the leader talks to the NPCs and works the puzzles): a scripted test driver leads far slower than a person, so
     * its runs would make every human leader's estimate too long. Their companions' times still count. Empty = every
     * leader is learned from. The live config.yaml lists the characters the test driver leads with.
     */
    public String COMPANION_PQ_PACE_EXCLUDED_LEADERS = "";

    // Companions restock their own potions before a party quest or a boss (AgentSupplyReadiness, CompanionRestock)
    /**
     * A companion out with its owner (or waiting outside a run for them) keeps its potions at the party-quest
     * standard by itself: short in a town near a shop, it walks there, buys, and comes back; the entry NPC shows it
     * as gone to the store. false = the kill switch: no standing duty, and the desk and holds exactly as before.
     */
    public boolean COMPANION_RESTOCK_ENABLED = true;
    /** A companion that cannot afford its potions says so on its own at most this often (the desk's ask is apart). */
    public int COMPANION_RESTOCK_ASK_EVERY_MS = 1800000;
    /**
     * Party-boss gate maps, a comma list: an owner coming into one with a companion short of potions hears it say
     * there is no shop out here (no supplier is reachable from them). Entry is never refused.
     */
    public String COMPANION_RESTOCK_BOSS_MAPS = "220080000,106021402";
    /**
     * P12 G9: boss gates of COMPANION_RESTOCK_BOSS_MAPS that a short companion may still walk to a shop from (the
     * Papulatus desk trip, decision 10; agent-engine.yaml AgentSupplyReadiness.BOSS_DESK_MAPS sets its radius). Empty:
     * no gate sends a trip, and each keeps its no-shop line.
     */
    public String COMPANION_RESTOCK_TRIP_MAPS = "";
    /**
     * Field bosses' towns (user decision 2026-10-13: companions keep a field boss's potion standard as they keep a run
     * boss's): "key=mapId|mapId,...". A companion out with its owner in a town listed for a field group whose key
     * COMPANION_BOSS_ENABLED_TYPES lists, at or above the group's level (boss-rules.json minLevel), keeps that group's
     * AgentSupplyReadiness.BOSS_STANDARDS standard there (CompanionBossRuns.fieldRestockKeyAt). Empty: none.
     */
    public String COMPANION_FIELD_BOSS_TOWNS = "";
    /**
     * 2026-10-14 (P18): towns where a companion buys a boss's seal cures: "key=mapId|mapId,...". A companion out with
     * its owner on a listed map, for a boss COMPANION_BOSS_ENABLED_TYPES lists and at or above its level, keeps the
     * cure standard AgentSupplyReadiness.CURE_STANDARDS gives "key_cure" there (CompanionBossRuns.cureRestockKeyAt):
     * Orbis for Horntail, whose Right Hand seals the altar and whose way passes Orbis. Empty: none.
     */
    public String COMPANION_BOSS_CURE_TOWNS = "";

    // Companions go for their next job by themselves (AgentJobPath, JobErrands)
    /**
     * A companion whose persona names its job, and every companion from its second job on (its own line to fourth
     * job), takes each advancement by itself once the game allows it: it tells its owner, walks out, travels to its
     * instructor, walks up to them, advances there and comes back (a benched one goes between its duties). A hire at
     * first job or a Beginner persona is asked to pick in chat as before. false = the kill switch: every companion is
     * asked, as before.
     */
    public boolean COMPANION_JOB_ERRANDS_ENABLED = true;
    /** How long it stands with its instructor before it advances. */
    public int COMPANION_JOB_ERRAND_TALK_MS = 5000;
    /** How long it walks up to its instructor at most; then it advances where it stands. */
    public int COMPANION_JOB_ERRAND_WALK_MAX_MS = 45000;
    /** Called back by its owner before it advanced, it sets off again no sooner than this. */
    public int COMPANION_JOB_ERRAND_RETRY_MS = 1800000;
    /** How often the errands out are looked at (a return its owner was offline for, one called back meanwhile). */
    public int COMPANION_JOB_ERRAND_SWEEP_MS = 30000;

    // Bots play the Cygnus Knights and Aran (docs/CYGNUS_ARAN_BOTS_PLAN.md)
    /**
     * Citizens, Residents and hires can be Cygnus Knights (born Noblesse in Ereve) and Arans (born Legends in Rien), as
     * players of those lines are: about a quarter of new citizens (careers.yaml), the Ereve and Rien Residents, and the
     * citizens one hires. false = the switch off: no new citizen of these lines and those Residents stay away; citizens
     * and companions already on these lines keep playing them.
     */
    public boolean CYGNUS_ARAN_CAREERS_ENABLED = true;
    /**
     * A companion its owner asks ("casse get your keystone") goes for its own Crimsonwood Keystone (KeystoneErrands,
     * docs/CWKPQ_COMPANION_PLAN.md A3): Lukan's and Jack's quests and the hunts for them, live when a seat is free,
     * else on the ledger at its own measured rates. It walks and talks as a job errand does (COMPANION_JOB_ERRAND_TALK_MS,
     * _WALK_MAX_MS) and is looked at on the job errands' sweep. false = the kill switch: no companion goes, and one out
     * is called back.
     */
    public boolean COMPANION_KEYSTONE_ERRANDS_ENABLED = true;
    /** The level a companion must have to go: the hunts are Lv80-100 mobs (Lukan's own gate is 70). */
    public int COMPANION_KEYSTONE_ERRAND_MIN_LEVEL = 85;
    /**
     * A player founds a guild with their companions (CompanionGuildFounding, GPQ plan decision 9): at the Guild
     * Headquarters a guildless companion in the party its owner leads counts as a cofounder, answers the founding
     * prompt yes as a player would, and joins as a Jr. Master; the founder's companion party is kept. false = the kill
     * switch: the stock founding, where only characters in no party and no guild count.
     */
    public boolean COMPANION_GUILD_COFOUNDERS_ENABLED = true;
    /**
     * A companion its owner asks ("casse get your ticket") goes for its own Entrance Ticket to the Amorian Challenge
     * (TicketErrands, docs/APQ_GPQ_COMPANION_PLAN.md A2): ten Lip Lock Keys hunted in Purplewood Forest 2, traded with
     * Amos the Strong; live when a seat is free, else on the ledger at its own measured key rate. false = the kill
     * switch: no companion goes, and one out is called back.
     */
    public boolean COMPANION_TICKET_ERRANDS_ENABLED = true;
    /** The level a companion must have to go: the challenge's own (AmoriaPQ.js minLevel 40). */
    public int COMPANION_TICKET_ERRAND_MIN_LEVEL = 40;

    // Party Search fills from the bench (P9 flow 11, PartySearchBench)
    /**
     * While an owner's stock Party Search runs, a rested bench companion of theirs whose level and job it asks for
     * answers each round no human answered: it whispers that it saw the search and comes like a call. false = the
     * kill switch: only humans answer, as before (bots stay out of the search either way).
     */
    public boolean COMPANION_PARTY_SEARCH_FILL = true;

    // Companions ask you to be buddies (P9, CompanionBuddies)
    /**
     * A Resident whose friendship with you reached COMPANION_BUDDY_FRIENDSHIP_LEVEL, and a hired hand after
     * COMPANION_BUDDY_HIRE_PARTY_MINUTES in your party, sends you the stock buddy request once while you are both online;
     * your own companions land in your "Crew" group. false = the kill switch: nobody new asks (citizens still do).
     */
    public boolean COMPANION_BUDDY_REQUESTS = false;
    /** The friendship level (1-10; 4 is "Friend") at which a Resident asks. */
    public int COMPANION_BUDDY_FRIENDSHIP_LEVEL = 4;
    /** The minutes a hired hand spends in your party before it asks (a citizen asks after 30, PopulationConfig). */
    public int COMPANION_BUDDY_HIRE_PARTY_MINUTES = 30;

    // Boss expeditions with companions (P7, docs/BOT_MANAGEMENT_PLAN.md flow 4)
    /**
     * Boss expeditions whose entry NPC offers an owner who creates or joins the expedition "My party will register
     * too.", a comma list of the keys {@link CompanionBossKeys#KNOWN} names (such as "easy_balrog", Mu Young's): the
     * owner's party companions on her map sign up through her own window and fight beside the owner. Empty = off: the
     * NPC, the boss and companion loot are stock.
     */
    public String COMPANION_BOSS_ENABLED_TYPES = "";
    /**
     * While a signed-up companion is in the fight, the server moves the boss for all of it and its attacks reach the
     * companions (the Poison Golem mechanism). false = the stock choice of controller (touch damage only for bots).
     */
    public boolean COMPANION_BOSS_SERVER_AUTHORITY = true;
    /**
     * Boss expeditions whose entry NPC also offers an owner on her list "Call allies from my bench...", a comma list of
     * {@link CompanionBossKeys#KNOWN} keys that COMPANION_BOSS_ENABLED_TYPES lists too: up to ROSTER_MAX - 5 benched
     * companions travel down, sign up, fight in their own bot party and go back to their bench duty after the run.
     * Empty = off: her window and every boss run are Part A's.
     */
    public String COMPANION_BOSS_ALLY_TYPES = "";
    /** Owners whose companions may sign up for one expedition (1-6; 1 = Part A's one-owner rule). */
    public int COMPANION_BOSS_OWNERS_MAX = 1;
    /** Seats of the expedition list that companions always leave free for players (0-25). */
    public int COMPANION_BOSS_HUMAN_SEATS = 2;
    /** How long an ally walks to the room's way out after the run before it goes where it stands. */
    public int COMPANION_BOSS_ALLY_WALK_OUT_MS = 20000;
    /**
     * P13 (decision 18): a companion that died beside a field boss rests in town until its HP and MP are back to this
     * percent of their maximum (1-100) before it follows its owner again, or until COMPANION_FIELD_BOSS_REST_MAX_MS.
     */
    public int COMPANION_FIELD_BOSS_REST_PERCENT = 100;
    /** The longest a companion rests after a death beside a field boss, from its death (0-600000 ms). */
    public int COMPANION_FIELD_BOSS_REST_MAX_MS = 120000;
    /**
     * A companion that went down this many times beside a field boss on one map sits that fight out in town while the
     * boss stands there and its owner stays on that map (0-10; 0 = never: it rests and follows again every time).
     */
    public int COMPANION_FIELD_BOSS_SIT_OUT_DEATHS = 2;
    /** The longest a companion sits a field boss's fight out, from its first death beside that boss (0-7200000 ms). */
    public int COMPANION_FIELD_BOSS_SIT_OUT_MAX_MS = 1800000;

    // Boss clears are not party-quest clears (2026-10-08)
    /**
     * Event scripts (scripts/event file names without ".js", a comma list) whose clear is a boss kill, not a party
     * quest clear: EventInstanceManager.clearPQ runs for both, and for a listed script the living world marks no
     * companion PQ run as cleared, advances no "clear a party quest" step and gives no friendship PQ points; it logs
     * "Boss event cleared" instead. Friendship boss points and companion boss-run records do not depend on it.
     * Empty = every clear counts as a party-quest clear, as before.
     */
    public String BOSS_EVENT_SCRIPTS = "BalrogBattle,BalrogBattle_Easy,ZakumBattle,HorntailBattle,PinkBeanBattle,"
            + "ScargaBattle,ShowaBattle,PapulatusBattle,LatanicaBattle,TD_Battle1,TD_Battle2,TD_Battle3,TD_Battle4,"
            + "TD_Battle5";

    // Rumors
    public int RUMOR_TICK_MS = 60000;
    public int RUMOR_LINE_PACING_MS = 3500;

    // The tell audit (2026-10-26, docs/BOT_CONCEPT_PLAN.md C2)
    /**
     * Measures how bot-like the bots look (reaction times, chat cadence, stacking, presence, walked routes, potion and
     * buff points, deaths) for GET /runtime/tells and an hourly audit.tells row in living_event_log. false = nothing is
     * measured and every report is one volatile read.
     */
    public boolean TELL_AUDIT_ENABLED = false;

    // A companion says why its owner's whisper reached no Agent (2026-10-10)
    /**
     * Least time between two of one companion's whispered "can't right now" lines (a live bench slice keeps it, or its
     * Agent is not running yet), so a spammed whisper gets one answer. 0 = every refused whisper is answered.
     */
    public int COMPANION_WHISPER_REFUSAL_GAP_MS = 5000;

    /** True when CREW_WINDOW_NATIVE lets a character use the native Crew window: "all", or "gm" for a GM (any case). */
    public boolean crewWindowNative(boolean gm) {
        String mode = CREW_WINDOW_NATIVE == null ? "" : CREW_WINDOW_NATIVE.trim();
        return mode.equalsIgnoreCase("all") || gm && mode.equalsIgnoreCase("gm");
    }

    /** True when COMPANION_PQ_ENABLED_KEYS lists {@code pqKey} (any case, spaces around it ignored). */
    public boolean companionPqEnabled(String pqKey) {
        if (pqKey == null || COMPANION_PQ_ENABLED_KEYS == null) {
            return false;
        }
        for (String entry : COMPANION_PQ_ENABLED_KEYS.split(",")) {
            if (!entry.isBlank() && entry.trim().equalsIgnoreCase(pqKey.trim())) {
                return true;
            }
        }
        return false;
    }

    /** P12 G9: true when COMPANION_RESTOCK_TRIP_MAPS lists {@code mapId} (blank entries and spaces ignored). */
    public boolean companionRestockTripMap(int mapId) {
        return listsMap(COMPANION_RESTOCK_TRIP_MAPS, mapId);
    }

    /** The field group keys COMPANION_FIELD_BOSS_TOWNS lists {@code mapId} as a town for, in listed order. */
    public List<String> companionFieldBossKeysAt(int mapId) {
        List<String> keys = new ArrayList<>();
        for (String pair : COMPANION_FIELD_BOSS_TOWNS == null ? new String[0] : COMPANION_FIELD_BOSS_TOWNS.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && listsMap(pair.substring(eq + 1).replace('|', ','), mapId)) {
                keys.add(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }

    /** The boss keys COMPANION_BOSS_CURE_TOWNS lists {@code mapId} as a cure town for, in listed order. */
    public List<String> companionCureBossKeysAt(int mapId) {
        List<String> keys = new ArrayList<>();
        for (String pair : COMPANION_BOSS_CURE_TOWNS == null ? new String[0] : COMPANION_BOSS_CURE_TOWNS.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && listsMap(pair.substring(eq + 1).replace('|', ','), mapId)) {
                keys.add(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }

    /**
     * The towns COMPANION_BOSS_CURE_TOWNS lists for boss {@code key}, in listed order; empty for none. The value is
     * validated at load, so every entry parses.
     */
    public List<Integer> companionCureTownsFor(String key) {
        List<Integer> towns = new ArrayList<>();
        for (String pair : COMPANION_BOSS_CURE_TOWNS == null ? new String[0] : COMPANION_BOSS_CURE_TOWNS.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key != null && pair.substring(0, eq).trim().equalsIgnoreCase(key)) {
                for (String entry : pair.substring(eq + 1).split("\\|")) {
                    if (!entry.isBlank()) {
                        towns.add(Integer.parseInt(entry.trim()));
                    }
                }
            }
        }
        return towns;
    }

    private static boolean listsMap(String list, int mapId) {
        for (String entry : list == null ? new String[0] : list.split(",")) {
            if (!entry.isBlank() && entry.trim().equals(Integer.toString(mapId))) {
                return true;
            }
        }
        return false;
    }

    /** True when COMPANION_RESTOCK_BOSS_MAPS lists {@code mapId} (blank entries and spaces ignored). */
    public boolean companionRestockBossMap(int mapId) {
        for (String entry : COMPANION_RESTOCK_BOSS_MAPS == null ? new String[0] : COMPANION_RESTOCK_BOSS_MAPS.split(",")) {
            if (!entry.isBlank() && entry.trim().equals(Integer.toString(mapId))) {
                return true;
            }
        }
        return false;
    }

    /** True when COMPANION_BOSS_ENABLED_TYPES lists {@code bossKey} (any case, spaces around it ignored). */
    public boolean companionBossEnabled(String bossKey) {
        if (bossKey == null || COMPANION_BOSS_ENABLED_TYPES == null) {
            return false;
        }
        for (String entry : COMPANION_BOSS_ENABLED_TYPES.split(",")) {
            if (!entry.isBlank() && entry.trim().equalsIgnoreCase(bossKey.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when COMPANION_BOSS_ALLY_TYPES lists {@code bossKey} (any case, spaces around it ignored) and
     * {@link #companionBossEnabled} is true for it too: allies need both switches.
     */
    public boolean companionBossAlliesEnabled(String bossKey) {
        if (!companionBossEnabled(bossKey) || COMPANION_BOSS_ALLY_TYPES == null) {
            return false;
        }
        for (String entry : COMPANION_BOSS_ALLY_TYPES.split(",")) {
            if (!entry.isBlank() && entry.trim().equalsIgnoreCase(bossKey.trim())) {
                return true;
            }
        }
        return false;
    }

    /** True when BOSS_EVENT_SCRIPTS lists event script {@code scriptName} (any case, spaces around it ignored). */
    public boolean bossEventScript(String scriptName) {
        if (scriptName == null || BOSS_EVENT_SCRIPTS == null) {
            return false;
        }
        for (String entry : BOSS_EVENT_SCRIPTS.split(",")) {
            if (!entry.isBlank() && entry.trim().equalsIgnoreCase(scriptName.trim())) {
                return true;
            }
        }
        return false;
    }

    /** COMPANION_PQ_PACE_EXCLUDED_LEADERS as character ids, blank entries and spaces ignored. */
    public java.util.Set<Integer> companionPqPaceExcludedLeaders() {
        java.util.Set<Integer> ids = new java.util.TreeSet<>();
        for (String entry : COMPANION_PQ_PACE_EXCLUDED_LEADERS == null ? new String[0]
                : COMPANION_PQ_PACE_EXCLUDED_LEADERS.split(",")) {
            if (!entry.isBlank()) {
                ids.add(Integer.parseInt(entry.trim()));
            }
        }
        return java.util.Collections.unmodifiableSet(ids);
    }

    /** {@link #FRIENDSHIP_LEVEL_POINTS}: index 0 is level 1. */
    public int[] friendshipLevelPoints() {
        return tenInts("FRIENDSHIP_LEVEL_POINTS", FRIENDSHIP_LEVEL_POINTS);
    }

    public int[] friendshipScrollTakes60() {
        return tenInts("FRIENDSHIP_SCROLL_TAKES_60", FRIENDSHIP_SCROLL_TAKES_60);
    }

    public int[] friendshipScrollTakes10() {
        return tenInts("FRIENDSHIP_SCROLL_TAKES_10", FRIENDSHIP_SCROLL_TAKES_10);
    }

    private static int[] tenInts(String key, String value) {
        String[] parts = value == null ? new String[0] : value.split(",");
        if (parts.length != 10) {
            throw new IllegalArgumentException("living_world." + key + " needs 10 comma-separated numbers (levels 1-10)");
        }
        int[] out = new int[10];
        for (int i = 0; i < 10; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("living_world." + key + ": '" + parts[i].trim() + "' is not a number");
            }
        }
        return out;
    }

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
        if (COMPANION_LOANS_PER_BORROWER < 1 || COMPANION_LOANS_PER_OWNER < 1 || COMPANION_LOAN_MAX_MINUTES < 30
                || COMPANION_LOAN_OFFER_MS < 5000) {
            throw new IllegalArgumentException("living_world.COMPANION_LOANS_PER_BORROWER and COMPANION_LOANS_PER_OWNER"
                    + " must be >= 1, COMPANION_LOAN_MAX_MINUTES >= 30, COMPANION_LOAN_OFFER_MS >= 5000");
        }
        if (COMPANION_LOOT_UPKEEP_MESO_PER_LEVEL < 0 || COMPANION_LOOT_SETTLE_MS < 1000) {
            throw new IllegalArgumentException("living_world.COMPANION_LOOT_UPKEEP_MESO_PER_LEVEL must be >= 0 and"
                    + " COMPANION_LOOT_SETTLE_MS >= 1000");
        }
        if (LETTERS_MAX_NOTES < 1 || LETTERS_MAX_NOTES > 100 || LETTERS_DELAY_MS < 0 || LETTERS_EQUIP_LEVEL_SPAN < 0
                || LETTERS_GIFT_FRIENDSHIP_LEVEL < 1 || LETTERS_GIFT_FRIENDSHIP_LEVEL > 10 || LETTERS_GIFTS_PER_WEEK < 0
                || LETTERS_GIFT_CHANCE < 0 || LETTERS_GIFT_CHANCE > 1 || LETTERS_ASK_CHANCE < 0 || LETTERS_ASK_CHANCE > 1
                || LETTERS_ASK_WARMTH < 0 || LETTERS_ASK_WARMTH > 100 || LETTERS_ASK_GAP_HOURS < 0) {
            throw new IllegalArgumentException("living_world.LETTERS_MAX_NOTES must be within 1..100,"
                    + " LETTERS_GIFT_FRIENDSHIP_LEVEL 1..10, LETTERS_ASK_WARMTH 0..100, the chances 0..1, the rest >= 0");
        }
        if (RECALL_MAX_ETA_MS < 0) {
            throw new IllegalArgumentException("living_world.RECALL_MAX_ETA_MS must be >= 0");
        }
        if (ROSTER_BOOK_ITEM_ID < 0) {
            throw new IllegalArgumentException("living_world.ROSTER_BOOK_ITEM_ID must be >= 0 (0 = off)");
        }
        if (CREW_WINDOW_NATIVE == null
                || !java.util.List.of("off", "gm", "all").contains(CREW_WINDOW_NATIVE.trim().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("living_world.CREW_WINDOW_NATIVE must be off, gm or all");
        }
        if (!java.util.List.of("AUTHORED", "PERMANENT_ON_ROSTER", "PERMANENT_AT_SWORN").contains(ROSTER_CONTRACT_MODE)) {
            throw new IllegalArgumentException(
                    "living_world.ROSTER_CONTRACT_MODE must be AUTHORED, PERMANENT_ON_ROSTER or PERMANENT_AT_SWORN");
        }
        if (BOND_ACQUAINTED_POINTS >= BOND_TRUSTED_POINTS || BOND_TRUSTED_POINTS >= BOND_SWORN_POINTS) {
            throw new IllegalArgumentException("living_world bond thresholds must increase");
        }
        int[] levelPoints = friendshipLevelPoints();
        for (int level = 1; level < levelPoints.length; level++) {
            if (levelPoints[level] <= levelPoints[level - 1]) {
                throw new IllegalArgumentException("living_world.FRIENDSHIP_LEVEL_POINTS must increase");
            }
        }
        for (int[] takes : new int[][] {friendshipScrollTakes60(), friendshipScrollTakes10()}) {
            for (int percent : takes) {
                if (percent < 0 || percent > 100) {
                    throw new IllegalArgumentException("living_world.FRIENDSHIP_SCROLL_TAKES_* must be percents 0..100");
                }
            }
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
        if (BENCH_TOWN_MAX < 0 || COMMERCE_LIVE_MAX < 0) {
            throw new IllegalArgumentException("living_world.BENCH_TOWN_MAX and COMMERCE_LIVE_MAX must be >= 0 (0 = none)");
        }
        if (BENCH_TOWN_PER_OWNER < 0 || BENCH_TOWN_PER_OWNER > BENCH_TOWN_MAX
                || COMMERCE_PER_OWNER < 0 || COMMERCE_PER_OWNER > COMMERCE_LIVE_MAX) {
            throw new IllegalArgumentException("living_world.BENCH_TOWN_PER_OWNER must be within 0..BENCH_TOWN_MAX and"
                    + " COMMERCE_PER_OWNER within 0..COMMERCE_LIVE_MAX");
        }
        if (BENCH_TOWN_SLICE_MS < 0 || COMMERCE_SLICE_MS < 0 || BENCH_RESUPPLY_MAX_MS < 0 || BENCH_RESUPPLY_MAX_HOPS < 0) {
            throw new IllegalArgumentException("living_world.BENCH_TOWN_SLICE_MS, COMMERCE_SLICE_MS, BENCH_RESUPPLY_MAX_MS"
                    + " and BENCH_RESUPPLY_MAX_HOPS must be >= 0");
        }
        if (BENCH_STALL_MARKDOWN_STEP < 0 || BENCH_STALL_MARKDOWN_STEP > 1) {
            throw new IllegalArgumentException("living_world.BENCH_STALL_MARKDOWN_STEP must be within 0..1 (0 = no markdown)");
        }
        if (BENCH_YIELD_PRIOR_SLICES < 0) {
            throw new IllegalArgumentException("living_world.BENCH_YIELD_PRIOR_SLICES must be >= 0");
        }
        if (BENCH_UPKEEP_MAX_SHARE < 0 || BENCH_UPKEEP_MAX_SHARE > 1) {
            throw new IllegalArgumentException("living_world.BENCH_UPKEEP_MAX_SHARE must be within 0..1");
        }
        if (BENCH_CHOOSER_EXPLORE < 0 || BENCH_CHOOSER_DEATH_COST < 0) {
            throw new IllegalArgumentException("living_world.BENCH_CHOOSER_EXPLORE and BENCH_CHOOSER_DEATH_COST must be >= 0");
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
        if (BENCH_COMBAT_LEARN_EPSILON < 0 || BENCH_COMBAT_LEARN_EPSILON > 1 || BENCH_COMBAT_LEARN_SAVE_MS < 1000) {
            throw new IllegalArgumentException(
                    "living_world.BENCH_COMBAT_LEARN_EPSILON must be within 0..1 and BENCH_COMBAT_LEARN_SAVE_MS >= 1000");
        }
        if (!java.util.List.of("OPT_IN", "OFF").contains(AUTO_ROTATE_MODE)) {
            throw new IllegalArgumentException("living_world.AUTO_ROTATE_MODE must be OPT_IN or OFF");
        }
        if (AUTO_ROTATE_TICK_MS < 1000 || AUTO_ROTATE_COOLDOWN_MS < 0 || AUTO_ROTATE_LEVEL_GAP < 1) {
            throw new IllegalArgumentException("living_world.AUTO_ROTATE_TICK_MS must be >= 1000, AUTO_ROTATE_COOLDOWN_MS >= 0"
                    + " and AUTO_ROTATE_LEVEL_GAP >= 1");
        }
        for (String key : COMPANION_PQ_ENABLED_KEYS == null ? new String[0] : COMPANION_PQ_ENABLED_KEYS.split(",")) {
            if (!key.isBlank() && !CompanionPqKeys.known(key)) {
                throw new IllegalArgumentException(CompanionPqKeys.message());
            }
        }
        for (String id : COMPANION_PQ_PACE_EXCLUDED_LEADERS == null ? new String[0]
                : COMPANION_PQ_PACE_EXCLUDED_LEADERS.split(",")) {
            if (!id.isBlank() && !id.trim().matches("[1-9][0-9]{0,8}")) {
                throw new IllegalArgumentException(
                        "living_world.COMPANION_PQ_PACE_EXCLUDED_LEADERS must list character ids, not \"" + id.trim() + "\"");
            }
        }
        if (COMPANION_RESTOCK_ASK_EVERY_MS < 0) {
            throw new IllegalArgumentException("living_world.COMPANION_RESTOCK_ASK_EVERY_MS must be >= 0");
        }
        if (COMPANION_JOB_ERRAND_TALK_MS < 0 || COMPANION_JOB_ERRAND_WALK_MAX_MS < 0 || COMPANION_JOB_ERRAND_RETRY_MS < 0) {
            throw new IllegalArgumentException(
                    "living_world.COMPANION_JOB_ERRAND_TALK_MS, _WALK_MAX_MS and _RETRY_MS must be >= 0");
        }
        if (COMPANION_JOB_ERRAND_SWEEP_MS <= 0) {
            throw new IllegalArgumentException("living_world.COMPANION_JOB_ERRAND_SWEEP_MS must be > 0");
        }
        if (COMPANION_KEYSTONE_ERRAND_MIN_LEVEL < 70 || COMPANION_KEYSTONE_ERRAND_MIN_LEVEL > 200) {
            throw new IllegalArgumentException("living_world.COMPANION_KEYSTONE_ERRAND_MIN_LEVEL must be 70-200 (Lukan asks 70)");
        }
        if (COMPANION_TICKET_ERRAND_MIN_LEVEL < 40 || COMPANION_TICKET_ERRAND_MIN_LEVEL > 200) {
            throw new IllegalArgumentException("living_world.COMPANION_TICKET_ERRAND_MIN_LEVEL must be 40-200 (Amos asks 40)");
        }
        if (COMPANION_BUDDY_FRIENDSHIP_LEVEL < 1 || COMPANION_BUDDY_FRIENDSHIP_LEVEL > 10) {
            throw new IllegalArgumentException("living_world.COMPANION_BUDDY_FRIENDSHIP_LEVEL must be 1-10");
        }
        if (COMPANION_BUDDY_HIRE_PARTY_MINUTES < 1) {
            throw new IllegalArgumentException("living_world.COMPANION_BUDDY_HIRE_PARTY_MINUTES must be >= 1");
        }
        for (String id : COMPANION_RESTOCK_BOSS_MAPS == null ? new String[0] : COMPANION_RESTOCK_BOSS_MAPS.split(",")) {
            if (!id.isBlank() && !id.trim().matches("[1-9][0-9]{0,8}")) {
                throw new IllegalArgumentException(
                        "living_world.COMPANION_RESTOCK_BOSS_MAPS must list map ids, not \"" + id.trim() + "\"");
            }
        }
        for (String id : COMPANION_RESTOCK_TRIP_MAPS == null ? new String[0] : COMPANION_RESTOCK_TRIP_MAPS.split(",")) {
            if (!id.isBlank() && !id.trim().matches("[1-9][0-9]{0,8}")) {
                throw new IllegalArgumentException(
                        "living_world.COMPANION_RESTOCK_TRIP_MAPS must list map ids, not \"" + id.trim() + "\"");
            }
        }
        for (String pair : COMPANION_FIELD_BOSS_TOWNS == null ? new String[0] : COMPANION_FIELD_BOSS_TOWNS.split(",")) {
            if (!pair.isBlank() && (!pair.trim().matches("[a-z0-9_]+=[1-9][0-9]{0,8}(\\|[1-9][0-9]{0,8})*")
                    || !CompanionBossKeys.known(pair.trim().substring(0, pair.trim().indexOf('='))))) {
                throw new IllegalArgumentException("living_world.COMPANION_FIELD_BOSS_TOWNS must list"
                        + " field_key=mapId|mapId pairs of known keys, not \"" + pair.trim() + "\"");
            }
        }
        for (String pair : COMPANION_BOSS_CURE_TOWNS == null ? new String[0] : COMPANION_BOSS_CURE_TOWNS.split(",")) {
            if (!pair.isBlank() && (!pair.trim().matches("[a-z0-9_]+=[1-9][0-9]{0,8}(\\|[1-9][0-9]{0,8})*")
                    || !CompanionBossKeys.known(pair.trim().substring(0, pair.trim().indexOf('='))))) {
                throw new IllegalArgumentException("living_world.COMPANION_BOSS_CURE_TOWNS must list"
                        + " key=mapId|mapId pairs of known keys, not \"" + pair.trim() + "\"");
            }
        }
        for (String key : COMPANION_BOSS_ENABLED_TYPES == null ? new String[0]
                : COMPANION_BOSS_ENABLED_TYPES.split(",")) {
            if (!key.isBlank() && !CompanionBossKeys.known(key)) {
                throw new IllegalArgumentException(CompanionBossKeys.message());
            }
        }
        for (String key : COMPANION_BOSS_ALLY_TYPES == null ? new String[0] : COMPANION_BOSS_ALLY_TYPES.split(",")) {
            if (key.isBlank()) {
                continue;
            }
            if (!CompanionBossKeys.known(key)) {
                throw new IllegalArgumentException("living_world.COMPANION_BOSS_ALLY_TYPES must list boss keys ("
                        + String.join(", ", CompanionBossKeys.KNOWN) + "), not \"" + key.trim() + "\"");
            }
            if (!companionBossEnabled(key)) {
                throw new IllegalArgumentException("living_world.COMPANION_BOSS_ALLY_TYPES must list only bosses"
                        + " COMPANION_BOSS_ENABLED_TYPES also lists, not \"" + key.trim() + "\"");
            }
        }
        if (COMPANION_BOSS_OWNERS_MAX < 1 || COMPANION_BOSS_OWNERS_MAX > 6) {
            throw new IllegalArgumentException("living_world.COMPANION_BOSS_OWNERS_MAX must be within 1..6");
        }
        if (COMPANION_BOSS_HUMAN_SEATS < 0 || COMPANION_BOSS_HUMAN_SEATS > 25) {
            throw new IllegalArgumentException("living_world.COMPANION_BOSS_HUMAN_SEATS must be within 0..25");
        }
        if (COMPANION_BOSS_ALLY_WALK_OUT_MS < 1000 || COMPANION_BOSS_ALLY_WALK_OUT_MS > 120000) {
            throw new IllegalArgumentException("living_world.COMPANION_BOSS_ALLY_WALK_OUT_MS must be within 1000..120000");
        }
        if (COMPANION_FIELD_BOSS_REST_PERCENT < 1 || COMPANION_FIELD_BOSS_REST_PERCENT > 100) {
            throw new IllegalArgumentException("living_world.COMPANION_FIELD_BOSS_REST_PERCENT must be within 1..100");
        }
        if (COMPANION_FIELD_BOSS_REST_MAX_MS < 0 || COMPANION_FIELD_BOSS_REST_MAX_MS > 600000) {
            throw new IllegalArgumentException("living_world.COMPANION_FIELD_BOSS_REST_MAX_MS must be within 0..600000");
        }
        if (COMPANION_FIELD_BOSS_SIT_OUT_DEATHS < 0 || COMPANION_FIELD_BOSS_SIT_OUT_DEATHS > 10) {
            throw new IllegalArgumentException("living_world.COMPANION_FIELD_BOSS_SIT_OUT_DEATHS must be within 0..10");
        }
        if (COMPANION_FIELD_BOSS_SIT_OUT_MAX_MS < 0 || COMPANION_FIELD_BOSS_SIT_OUT_MAX_MS > 7200000) {
            throw new IllegalArgumentException("living_world.COMPANION_FIELD_BOSS_SIT_OUT_MAX_MS must be within 0..7200000");
        }
        for (String script : BOSS_EVENT_SCRIPTS == null ? new String[0] : BOSS_EVENT_SCRIPTS.split(",")) {
            if (!script.isBlank() && !script.trim().matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("living_world.BOSS_EVENT_SCRIPTS must list event script names"
                        + " (scripts/event, without .js), not \"" + script.trim() + "\"");
            }
        }
        if (COMPANION_WHISPER_REFUSAL_GAP_MS < 0) {
            throw new IllegalArgumentException("living_world.COMPANION_WHISPER_REFUSAL_GAP_MS must be >= 0");
        }
    }
}
