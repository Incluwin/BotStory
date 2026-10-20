package server.knowledge;

import config.KnowledgeConfig;
import config.YamlConfig;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.knowledge.ContentQuestionDetector.Kind;
import server.knowledge.ContentQuestionDetector.Shape;
import server.knowledge.ReplyBank.Voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers a content question truthfully for this server (docs/SERVER_KNOWLEDGE_PLAN.md 5.4): the ordered
 * {@code content.*} facts for the model and a short deterministic reply in the bot's voice.
 *
 * <p>What a reply says: one source, or two when the runner-up scores within 15% (level fit x chance bucket x
 * portal distance from the asker); spoken names only (aliases.yaml {@code say}, never a raw WZ name, id, %
 * or coordinate); drop chances as buckets at the live drop rate; script-only mobs name the NPC that spawns
 * them; prices only when the population price index has seen sales (never a guess); the bot's known world (or,
 * without one, {@link KnowledgeHorizon}) bounds what it admits to knowing.
 *
 * <p>What a conversation remembers, per (asker, bot), in memory only: a pending ask-back ("blue or green
 * mushroom cap?", "what job u going?") for 45 s, the last subject for follow-ups ("how do i get there",
 * "and manas?") for 60 s, answers already given (10 min: "like i said, ..."), and a budget of four answers per
 * ten minutes. Every lookup is in memory; nothing here touches the DB or WZ, so it is safe on the Netty thread.
 */
public final class ContentAnswers {
    private static final Logger log = LoggerFactory.getLogger(ContentAnswers.class);

    private static final long PENDING_MS = 45_000;
    private static final long FOLLOW_MS = 60_000;
    private static final long REPEAT_MS = 600_000;
    private static final long BUDGET_WINDOW_MS = 600_000;
    private static final int BUDGET_ANSWERS = 4;
    private static final int MEMORY_MAX = 4096;
    private static final int FACT_CHARS = 256;
    private static final int FACT_SOURCES = 3;
    private static final double RUNNER_UP = 0.85;
    private static final double COMMON = 0.10;
    private static final double SOMETIMES = 0.01;
    private static final double RARE = 0.001;
    /** Below this a veteran does not say a number ("<0.01%" reads like a stat sheet): the bucket words instead. */
    private static final double EXACT_MIN = 0.0001;
    private static final int FIT_BELOW = 10;
    private static final int FIT_ABOVE = 15;
    private static final int FAR_TRIP_LEVELS = 25;
    private static final double ROCK_CHANCE = 0.2;
    private static final double KPQ_CHANCE = 0.3;
    private static final int KPQ_MIN = 21;
    private static final int KPQ_MAX = 30;
    private static final int NEAR_LEVELS = 5;
    private static final int EXP_CAP = 5;
    private static final int TRAINING_CANDIDATES = 12;
    /** Personal training answers: the fields of the level band it may know, from this many candidates. */
    private static final int PERSONAL_CANDIDATES = 60;
    private static final int TRAIN_BELOW = 8;
    private static final int TRAIN_ABOVE = 5;
    private static final int FM_FIRST = 910_000_000;
    private static final int FM_LAST = 910_000_022;
    private static final int TELE_ROCK = 5_041_000;
    private static final int PERFECT_PITCH = 4_310_000;
    /** CWKPQ plan C2: the Crimsonwood Keystone, a quest reward only (8223), which the drop tables never show. */
    private static final int CRIMSONWOOD_KEYSTONE = 3_992_041;
    /** The keep's PQ lobby, the Hall to Inner Sanctum (aliases.yaml cwkpq). */
    private static final int CWKPQ_LOBBY = 610_030_020;
    /** Lukan's keystone quests start at level 70: a bot below it has not done them. */
    private static final int KEYSTONE_QUEST_LEVEL = 70;
    private static final String KEYSTONE_FACT = "Crimsonwood Keystone: Lukan on Phantom Road in the Phantom Forest"
            + " gives it for his quest Storming the Castle (level 70), after The Brewing Storm (10 Stormbreaker Badges)"
            + " and starting Stemming the Tide with Jack in the Cavern of Pain; nothing drops it and no shop sells it;"
            + " the gate to Crimsonwood Keep's party quest takes it";
    private static final int[] JOB_LEVELS = {0, 10, 30, 70, 120};
    private static final int MAGICIAN_FIRST_JOB = 8;
    private static final List<EntityKind> ITEMS = List.of(EntityKind.ITEM);
    private static final List<EntityKind> MOBS = List.of(EntityKind.MOB);
    private static final List<EntityKind> ITEM_OR_MOB = List.of(EntityKind.ITEM, EntityKind.MOB);
    private static final List<EntityKind> GETTABLE = List.of(EntityKind.ITEM, EntityKind.ROLE, EntityKind.NPC, EntityKind.MAP,
            EntityKind.REGION, EntityKind.PQ, EntityKind.MOB);
    private static final List<EntityKind> WHERE = List.of(EntityKind.NPC, EntityKind.MAP, EntityKind.REGION, EntityKind.PQ,
            EntityKind.ROLE, EntityKind.MOB, EntityKind.ITEM);
    private static final List<EntityKind> PLACES = List.of(EntityKind.MAP, EntityKind.REGION, EntityKind.PQ, EntityKind.NPC,
            EntityKind.ROLE, EntityKind.MOB);
    private static final List<EntityKind> LEVELLED = List.of(EntityKind.MOB, EntityKind.ITEM, EntityKind.PQ, EntityKind.MAP,
            EntityKind.REGION);
    private static final List<EntityKind> ANY = List.of(EntityKind.ITEM, EntityKind.MOB, EntityKind.MAP, EntityKind.REGION,
            EntityKind.NPC, EntityKind.PQ);
    private static final Set<String> RECHARGEABLE_PREFIX = Set.of("207", "233");
    private static final int EVENT_MOB_ID = 9_000_000;
    /** A walk through at most this many maps is "close"; longer ones are "a walk from {town}". */
    private static final int CLOSE_WALK_MAPS = 4;
    private static final int CONTINENT = 100_000_000;
    private static final String HIDDEN_STREET = "Hidden Street";
    /** Words no game name has: an unresolved slot with one of them is chat ("the fun in that"), never "no clue". */
    private static final Set<String> POINTING = Set.of("that", "it", "this", "them", "those", "these", "there", "here", "you", "me");
    /** bestSpawn: a Hidden Street spawn ranks like one this many maps further away. */
    private static final int HIDDEN_PENALTY = 30;
    /** "can a warrior wear X": the words that make it a gear question without naming an item. */
    private static final Set<String> GEAR_WORDS = Set.of("anything", "any gear", "gear", "equip", "equipment", "armor", "armour",
            "robe", "weapon", "claw", "bow", "crossbow", "xbow", "sword", "dagger", "wand", "staff", "spear", "polearm", "pole arm",
            "axe", "mace", "gun", "knuckle", "overall", "hat", "helmet", "helm", "shield", "shoe", "glove", "cape", "top", "bottom",
            "pant", "earring", "other class gear", "other job gear", "other classes gear", "any class gear", "everything");
    private static final Pattern RIDE_TO = Pattern.compile("^(?:the )?(?:boat|ship|train|ticket|taxi|cab|crane|subway|plane) (?:to|for) (.+)$");
    private static final Pattern IN_TOWN = Pattern.compile("^(.+?) (?:in|at|near) (.+)$");
    /** Drop rows that get a full (spawn map + distance) score; the rest are already out-ranked. */
    private static final int SOURCE_SCAN = 10;
    /** Etc items 4030000-4030999 are minigame pieces ("Monster Card" for Match Cards), not loot to brag about. */
    private static final int MINIGAME_FIRST = 4_030_000;
    private static final int MINIGAME_LAST = 4_030_999;
    private static final double EVENT_PLACE = 0.3;
    private static final int CARD_FIRST = 2_380_000;
    private static final int CARD_LAST = 2_389_999;
    private static final Pattern LEVEL_REPLY = Pattern.compile("\\b(\\d{1,3})\\b");
    /** Towns every player knows by name wherever they are from (the plan review's hub list); the FM is matched by range. */
    private static final Set<Integer> HUB_TOWNS = Set.of(200_000_000, 211_000_000, 220_000_000);
    /** {@link #answerable}: a neutral asker in Henesys and a bot that knows everything. */
    private static final int NEUTRAL_MAP = 100_000_000;
    private static final int NEUTRAL_LEVEL = 30;
    private static final int ALL_KNOWING = 200;

    /** Who asks: their map decides what is near, their level what fits, their world the live rates. */
    public record Asker(int id, String name, int mapId, int level, int jobId, int worldId) {
    }

    /**
     * Who answers. {@code style} is the dialogue style id (voices.yaml); {@code homeRegion} null for none;
     * {@code ownRefs} entity keys ({@link Entity#key()}) from the bot's own quests and arcs, always known;
     * {@code stranger} false for a companion answering its own owner or party, who is never told "ask someone else";
     * {@code known} its known world (what it lived and heard, plan 7), null: the level horizon alone.
     */
    public record Responder(int id, int level, String homeRegion, String style, Set<Long> ownRefs, boolean stranger,
                            KnownWorld known) {
        public Responder(int id, int level, String homeRegion, String style, Set<Long> ownRefs, boolean stranger) {
            this(id, level, homeRegion, style, ownRefs, stranger, null);
        }

        public Responder(int id, int level, String homeRegion, String style, Set<Long> ownRefs) {
            this(id, level, homeRegion, style, ownRefs, true, null);
        }
    }

    /**
     * One chat line to answer. {@code characterName} tells whether a word is an online character's name on the
     * asker's channel ("where is mia" about a player is not the NPC Mia).
     */
    public record Ask(String line, Asker asker, Responder bot, Predicate<String> characterName, long nowMs) {
    }

    public enum Outcome { ANSWERED, CLARIFY, DEFLECTED, BOUNDARY, REPEAT, BUDGET, FACTS_ONLY, SILENT }

    /**
     * @param kind    the kind answered (a follow-up's resolved kind; GET_WHERE for "where is" an item)
     * @param subject what the answer is about, null when none
     * @param reply   the deterministic reply, null when the model should answer from {@code facts} (OPINION) or silence
     * @param facts   ordered {@code content.*} facts, each value at most 256 chars
     */
    public record ContentAnswer(Outcome outcome, Kind kind, Shape shape, NameIndex.Resolution resolution, Entity subject,
                                String reply, Map<String, String> facts) {
        static ContentAnswer silent(Shape shape) {
            return new ContentAnswer(Outcome.SILENT, shape == null ? null : shape.kind(), shape, null, null, null, Map.of());
        }
    }

    /**
     * What the population price index knows about an item: its index price, how many sales nudged it, and
     * whether it is a market basket good (an authored price). Absent when the index has never priced it.
     */
    public record PriceQuote(long price, int observations, boolean basket) {
        boolean known() {
            return observations > 0 || basket;
        }
    }

    /** The live world rates and spawn settings (GM commands and MapleEdit change them at runtime). */
    public record Rates(int exp, int meso, int drop, int bossDrop, float mobRate, int perSpawnPoint) {
        static final Rates DEFAULT = new Rates(1, 1, 1, 1, 1f, 1);

        static Rates live(int worldId) {
            World world = Server.getInstance().getWorld(worldId);
            return world == null ? DEFAULT : new Rates(world.getExpRate(), world.getMesoRate(), world.getDropRate(),
                    world.getBossDropRate(), world.getMobrate(), world.getMobperspawnpoint());
        }
    }

    private static volatile IntFunction<PriceQuote> installedPrices = itemId -> null;
    private static volatile ContentAnswers shared;
    private static final AtomicLong seen = new AtomicLong();
    private static final AtomicLong answered = new AtomicLong();
    private static final AtomicLong deflected = new AtomicLong();
    private static final AtomicLong unresolved = new AtomicLong();
    private static final AtomicLong clarified = new AtomicLong();

    private final Supplier<WorldKnowledge> knowledge;
    private final IntFunction<Rates> rates;
    private final IntFunction<PriceQuote> prices;
    private final KnowledgeHorizon horizon;
    private final int maxChars;
    private final boolean logUnresolved;
    private final Map<Long, Memory> memory = new ConcurrentHashMap<>();
    /** asker id -> the memory key of their newest pending ask-back, so a host's per-line check is one lookup. */
    private final Map<Integer, Long> pendingByAsker = new ConcurrentHashMap<>();

    /**
     * @param prices null: the price seam the population installs ({@link #installPrices})
     */
    ContentAnswers(Supplier<WorldKnowledge> knowledge, IntFunction<Rates> rates, IntFunction<PriceQuote> prices,
                   int horizonSlack, int maxChars, boolean logUnresolved) {
        this.knowledge = knowledge;
        this.rates = rates;
        this.prices = prices;
        this.horizon = new KnowledgeHorizon(horizonSlack);
        this.maxChars = maxChars;
        this.logUnresolved = logUnresolved;
    }

    /** The server's instance: the published {@link WorldKnowledge}, live World rates, config.yaml {@code knowledge:}. */
    public static ContentAnswers shared() {
        ContentAnswers instance = shared;
        if (instance == null) {
            synchronized (ContentAnswers.class) {
                if (shared == null) {
                    KnowledgeConfig config = YamlConfig.config.knowledge;
                    shared = new ContentAnswers(WorldKnowledge::current, Rates::live, null, config.HORIZON_LEVEL_SLACK,
                            config.MAX_REPLY_CHARS, config.LOG_UNRESOLVED);
                }
                instance = shared;
            }
        }
        return instance;
    }

    /**
     * The population market's read-only price lookup (no seeding, no DB write). server.knowledge never imports
     * the population, so the population hands it in at start; until then every price is unknown.
     */
    public static void installPrices(IntFunction<PriceQuote> lookup) {
        installedPrices = lookup == null ? itemId -> null : lookup;
    }

    /**
     * Server-rule facts for grounding a reply (section 10 of the plan), read live from the asker's world:
     * {@code prompted} false gives only the low-risk facts a bot may bring up unasked; true adds rates, quest EXP
     * and the gear rule (when the asker asked about rules).
     */
    public Map<String, String> ruleFacts(int worldId, boolean prompted) {
        return ServerRules.facts(rates.apply(worldId), prompted);
    }

    /** Counters for {@code !knowledge status}. */
    public static String counters() {
        return "questions seen " + seen.get() + ", answered " + answered.get() + ", deflected " + deflected.get()
                + ", unresolved " + unresolved.get() + ", asked back " + clarified.get();
    }

    /** The answer to a chat line, empty when it is not a content question this bot should answer. */
    public Optional<ContentAnswer> answer(Ask ask) {
        ContentAnswer result = respond(ask, true);
        return result.reply() == null && result.facts().isEmpty() ? Optional.empty() : Optional.of(result);
    }

    /** The same as {@link #answer} without touching the conversation memory or counters (for {@code !knowledge}). */
    public ContentAnswer explain(Ask ask) {
        return respond(ask, false);
    }

    /**
     * Like {@link #answer} (remembered), but always returns the outcome, so a host can tell a deliberate silence
     * (REPEAT or BUDGET without a reply: "like i said" or "ask someone else" was already said) from a line that is not
     * a content question for it (SILENT).
     */
    public ContentAnswer converse(Ask ask) {
        return respond(ask, true);
    }

    /**
     * Whether a line is a content question with something to answer (a subject that resolves, or a shape that needs
     * none), asked by anyone of any level: the companion chat-intent gate. Follow-ups and thanks are not (they only
     * mean something right after an answer). Touches no memory.
     */
    public boolean answerable(String line) {
        ContentAnswer probe = explain(new Ask(line, new Asker(0, "", NEUTRAL_MAP, NEUTRAL_LEVEL, 0, 0),
                new Responder(0, ALL_KNOWING, null, null, Set.of()), null, 0L));
        return probe.outcome() != Outcome.SILENT && probe.shape() != null && probe.shape().kind() != Kind.THANKS
                && probe.shape().kind() != Kind.FOLLOW_UP;
    }

    /** The bot an asker owes a reply to their ask-back, when one is pending (the host routes that reply there first). */
    public OptionalInt pendingResponder(int askerId, long nowMs) {
        Long key = pendingByAsker.get(askerId);
        Memory m = key == null ? null : memory.get(key);
        if (m == null) {
            return OptionalInt.empty();
        }
        synchronized (m) {
            return m.pending != null && nowMs - m.pending.askedAt <= PENDING_MS ? OptionalInt.of((int) (long) key) : OptionalInt.empty();
        }
    }

    // ---- flow ---------------------------------------------------------------------------------

    private ContentAnswer respond(Ask ask, boolean remember) {
        WorldKnowledge k = knowledge.get();
        if (!k.ready() || ask.line() == null) {
            return ContentAnswer.silent(null);
        }
        Memory m = remember ? memory(ask) : peek(ask);
        synchronized (m) {
            Context c = new Context(ask, k, m, rates.apply(ask.asker().worldId()), Voice.of(ask.bot().style()), remember);
            ContentAnswer result = respond(c);
            if (remember) {
                record(c, result);
            }
            return result;
        }
    }

    private ContentAnswer respond(Context c) {
        Pending pending = c.m.pending;
        if (pending != null && c.ask.nowMs() - pending.askedAt <= PENDING_MS) {
            ContentAnswer resolved = resolvePending(c, pending);
            if (resolved != null) {
                return resolved;
            }
        }
        Shape shape = ContentQuestionDetector.shape(c.ask.line());
        if (shape == null) {
            return ContentAnswer.silent(null);
        }
        if (shape.kind() == Kind.THANKS) {
            boolean fresh = c.m.lastAt > 0 && c.ask.nowMs() - c.m.lastAt <= FOLLOW_MS && c.m.lastOutcome == Outcome.ANSWERED;
            return fresh ? reply(c, Outcome.ANSWERED, shape, Kind.THANKS, null, null, line(c, "thanks", Map.of()), facts(c))
                    : ContentAnswer.silent(shape);
        }
        if (remembering(c)) {
            seen.incrementAndGet();
        }
        if (shape.kind() == Kind.BUY_WHERE && shape.whoSells() && inFreeMarket(c.ask.asker().mapId())) {
            return ContentAnswer.silent(shape); // "who sells X" in the FM is a trade call
        }
        return switch (shape.kind()) {
            case FOLLOW_UP -> followUp(c, shape);
            case TRAIN_WHERE -> train(c, shape, shape.level());
            case JOB_ADVANCE -> jobAdvance(c, shape, null);
            case WHERE_AM_I -> whereAmI(c, shape);
            case MAP_DROPS -> mapDrops(c, shape);
            case MAP_GOOD -> mapGood(c, shape);
            case RULES -> rules(c, shape);
            case OPINION -> opinion(c, shape);
            default -> slotted(c, shape, shape.kind(), shape.slot());
        };
    }

    /** A question with a subject slot: resolve it (by kind), then answer. */
    private ContentAnswer slotted(Context c, Shape shape, Kind kind, String slot) {
        if (isCharacterName(c, slot)) {
            return ContentAnswer.silent(shape); // "where is mia", "what level is bob": a player, not the NPC or the mob
        }
        Matcher ride = RIDE_TO.matcher(slot);
        if (ride.matches() && kind != Kind.ROUTE) {
            return slotted(c, shape, Kind.ROUTE, ride.group(1));
        }
        NameIndex.Resolution resolution = c.k.names().resolveSlot(slot, kindsFor(kind));
        Matcher inTown = IN_TOWN.matcher(slot);
        if (!resolution.resolved() && !resolution.tie() && inTown.matches()) {
            resolution = c.k.names().resolveSlot(inTown.group(1), kindsFor(kind)); // "the fm in ellinia", "athena in henesys"
        }
        if ((kind == Kind.LEVEL_OF || kind == Kind.MOB_DROPS) && namesAnNpc(c, slot, resolution)) {
            return ContentAnswer.silent(shape); // "what level is maya": a person, not her seal of love
        }
        if (resolution.tie()) {
            String asked = NameIndex.normalize(slot);
            if (!c.m.askedBack.contains(asked)) {
                return clarify(c, shape, kind, resolution, asked);
            }
            ContentAnswer guess = subject(c, shape, kind, resolution, resolution.best().orElseThrow().entity());
            String hedged = guess.reply() == null ? null
                    : ReplyBank.spell(clip(ReplyBank.line("guess", c.voice, c.rng, maxChars, Map.of("x", resolution.best().get().entity().say()))
                    + " " + ReplyBank.uncapitalize(guess.reply())), c.voice, maxChars);
            return new ContentAnswer(guess.outcome(), guess.kind(), shape, resolution, guess.subject(), hedged, guess.facts());
        }
        if (!resolution.resolved()) {
            return unresolved(c, shape, kind, slot, resolution);
        }
        return subject(c, shape, kind, resolution, resolution.best().orElseThrow().entity());
    }

    /** The slot exactly names an NPC (alias or full name), at least as well as anything of the question's kinds. */
    private static boolean namesAnNpc(Context c, String slot, NameIndex.Resolution asked) {
        Optional<NameIndex.Candidate> npc = c.k.names().resolveSlot(slot, List.of(EntityKind.NPC)).best()
                .filter(candidate -> candidate.how().equals("alias") || candidate.how().equals("exact"));
        return npc.isPresent() && asked.best().map(best -> best.score() < npc.get().score()).orElse(true);
    }

    /** Re-kinds by what the slot turned out to be, then applies the horizon, repeat memory and budget. */
    private ContentAnswer subject(Context c, Shape shape, Kind kind, NameIndex.Resolution resolution, Entity subject) {
        Kind answerKind = rekind(kind, subject);
        if (answerKind == null) {
            return ContentAnswer.silent(shape);
        }
        c.subject = subject;
        c.rng = c.seeded(subject.key());
        c.echo = echo(shape == null ? "" : shape.slot(), resolution);
        c.hedged = resolution != null && resolution.best().map(b -> b.score() < NameIndex.EXACT - 0.01).orElse(false);
        c.heard = heardOnly(c, subject);
        KnowledgeHorizon.Grade grade = grade(c, subject, answerKind);
        // levels and prices are talk everyone hears; only places and the ways there get vague
        boolean rough = grade == KnowledgeHorizon.Grade.ROUGH && answerKind != Kind.LEVEL_OF && answerKind != Kind.PRICE;
        if (grade == KnowledgeHorizon.Grade.NONE || rough && !roughly(answerKind, subject)) {
            return reply(c, Outcome.DEFLECTED, shape, answerKind, resolution, subject, line(c, deflectKey(c, subject, answerKind), Map.of()),
                    facts(c, "content.boundary", "you have never been near " + subject.say() + "; say you don't know"));
        }
        ContentAnswer limited = limits(c, shape, answerKind, subject);
        if (limited != null) {
            return limited;
        }
        Composed composed = rough ? rough(c, subject) : compose(c, answerKind, subject, shape);
        if (composed.reply == null && composed.facts.isEmpty()) {
            return ContentAnswer.silent(shape);
        }
        return reply(c, composed.outcome, shape, answerKind, resolution, subject, composed.reply, composed.facts);
    }

    private static Kind rekind(Kind kind, Entity subject) {
        EntityKind is = subject.kind();
        return switch (kind) {
            case GET_WHERE, BUY_WHERE -> is == EntityKind.ITEM ? kind : Kind.WHERE_IS;
            case DROP_SOURCE -> is == EntityKind.ITEM ? kind : is == EntityKind.MOB ? Kind.WHERE_IS : null;
            case WHERE_IS -> is == EntityKind.ITEM ? Kind.GET_WHERE : kind;
            default -> kind;
        };
    }

    private ContentAnswer followUp(Context c, Shape shape) {
        boolean fresh = c.m.lastSubject != null && c.ask.nowMs() - c.m.lastAt <= FOLLOW_MS;
        if (!fresh) {
            if (shape.follow() == Kind.MOB_DROPS) {
                return mapDrops(c, shape); // "what do they drop" with nothing said before: the mobs here
            }
            return ContentAnswer.silent(shape);
        }
        if (shape.follow() == null) {
            Kind kind = c.m.lastKind == null ? Kind.WHERE_IS : c.m.lastKind;
            return slotted(c, shape, kind, shape.slot());
        }
        Kind kind = shape.follow();
        Entity last = c.m.lastSubject;
        if (kind == Kind.WHERE_IS && last.kind() == EntityKind.ITEM) {
            kind = Kind.GET_WHERE;
        }
        if (kind == Kind.MOB_DROPS && last.kind() != EntityKind.MOB || kind == Kind.DROP_SOURCE && last.kind() != EntityKind.ITEM
                || kind == Kind.PRICE && last.kind() != EntityKind.ITEM) {
            return ContentAnswer.silent(shape);
        }
        return subject(c, shape, kind, null, last);
    }

    private ContentAnswer clarify(Context c, Shape shape, Kind kind, NameIndex.Resolution resolution, String asked) {
        List<Entity> options = new ArrayList<>();
        for (NameIndex.Candidate candidate : resolution.candidates()) {
            if (resolution.best().get().score() - candidate.score() < NameIndex.TIE && options.size() < 2) {
                options.add(candidate.entity());
            }
        }
        String text = line(c, "clarify", Map.of("x", alternatives(options.get(0).say(), options.get(1).say())));
        if (remembering(c)) {
            setPending(c, new Pending(kind, shape, resolution.candidates().stream().map(NameIndex.Candidate::entity).toList(),
                    c.ask.nowMs(), false, false));
            c.m.askedBack.add(asked);
            clarified.incrementAndGet();
        }
        return reply(c, Outcome.CLARIFY, shape, kind, resolution, null, text,
                facts(c, "content.ambiguous", "\"" + shape.slot() + "\" could be " + options.get(0).say() + " or "
                        + options.get(1).say() + "; ask which"));
    }

    /** The asker's answer to an ask-back: a candidate's distinguishing word ("red", "the 60 one"), a class word or a level. */
    private ContentAnswer resolvePending(Context c, Pending pending) {
        String said = NameIndex.normalize(c.ask.line());
        if (pending.job) {
            Optional<AliasCatalog.JobBranch> branch = jobIn(c, said);
            if (branch.isPresent()) {
                clearPending(c);
                return jobAdvance(c, pending.shape, branch.get());
            }
        } else if (pending.level) {
            Matcher number = LEVEL_REPLY.matcher(said);
            if (number.find()) {
                clearPending(c);
                return train(c, pending.shape, Integer.parseInt(number.group(1)));
            }
        } else {
            Entity chosen = null;
            for (Entity candidate : pending.candidates) {
                for (String token : distinguishing(candidate, pending.candidates)) {
                    if ((" " + said + " ").contains(" " + token + " ")) {
                        chosen = chosen == null || chosen == candidate ? candidate : null;
                    }
                }
            }
            if (chosen == null) {
                chosen = byTown(c, said, pending.candidates); // "the henesys one"
            }
            if (chosen != null) {
                clearPending(c);
                return subject(c, pending.shape, pending.kind, null, chosen);
            }
        }
        clearPending(c);
        return null;
    }

    private void setPending(Context c, Pending pending) {
        c.m.pending = pending;
        pendingByAsker.put(c.ask.asker().id(), memoryKey(c.ask));
    }

    private void clearPending(Context c) {
        if (remembering(c)) {
            c.m.pending = null;
            pendingByAsker.remove(c.ask.asker().id(), memoryKey(c.ask));
        }
    }

    /** The one candidate whose town the reply names; null when none or several are there. */
    private static Entity byTown(Context c, String said, List<Entity> candidates) {
        Entity chosen = null;
        for (Entity candidate : candidates) {
            int map = targetMap(c, candidate);
            String town = map == 0 ? "" : NameIndex.normalize(townSay(c, map));
            if (!town.isEmpty() && (" " + said + " ").contains(" " + town + " ")) {
                if (chosen != null) {
                    return null;
                }
                chosen = candidate;
            }
        }
        return chosen;
    }

    private static List<String> distinguishing(Entity candidate, List<Entity> all) {
        List<String> out = new ArrayList<>();
        for (String token : NameIndex.normalize(candidate.say()).split(" ")) {
            boolean shared = false;
            for (Entity other : all) {
                if (other != candidate && (" " + NameIndex.normalize(other.say()) + " ").contains(" " + token + " ")) {
                    shared = true;
                }
            }
            if (!shared) {
                out.add(token);
            }
        }
        return out;
    }

    private ContentAnswer unresolved(Context c, Shape shape, Kind kind, String slot, NameIndex.Resolution resolution) {
        String[] words = NameIndex.normalize(slot).split(" ");
        boolean category = Arrays.stream(words).allMatch(NameIndex.GRAMMAR::contains); // "event items", "the boss"
        boolean pointing = Arrays.stream(words).anyMatch(POINTING::contains); // "wheres the fun in that"
        if (shape.weak() || words.length > 4 || words.length == 1 || category || pointing || !c.k.names().nameLike(slot)) {
            return ContentAnswer.silent(shape); // a one-word slot may be a player or a word in a name; "how do i get a gf" is not content
        }
        if (remembering(c)) {
            unresolved.incrementAndGet();
            if (logUnresolved) {
                log.info("[knowledge] unresolved {} slot '{}' (line '{}')", kind, slot, c.ask.line());
            }
        }
        return reply(c, Outcome.BOUNDARY, shape, kind, resolution, null, line(c, "boundary", Map.of("askin", askIn(c))),
                facts(c, "content.boundary", "nothing known about \"" + slot + "\"; say you don't know, in character"));
    }

    /** "ask in henesys" only to someone who is not already in Henesys or the FM; "" drops that template. */
    private static String askIn(Context c) {
        int map = c.ask.asker().mapId();
        return inFreeMarket(map) || townOrSelf(c, map) == NEUTRAL_MAP ? "" : mapSay(c, NEUTRAL_MAP);
    }

    /** Repeat memory and the per-asker budget. Null when the bot may answer normally. */
    private ContentAnswer limits(Context c, Shape shape, Kind kind, Entity subject) {
        long key = subject.key() * 31 + kind.ordinal();
        Long at = c.m.answeredAt.get(key);
        long now = c.ask.nowMs();
        if (at != null && now - at <= REPEAT_MS) {
            String short_ = c.m.answeredReply.get(key);
            if (c.m.repeated.contains(key) || short_ == null) {
                return new ContentAnswer(Outcome.REPEAT, kind, shape, null, subject, null, Map.of()); // already said "like i said"
            }
            c.repeatKey = key;
            String text = line(c, "repeat", Map.of("short", short_));
            return reply(c, Outcome.REPEAT, shape, kind, null, subject, text, facts(c));
        }
        if (now - c.m.windowStart > BUDGET_WINDOW_MS) {
            c.m.windowStart = now;
            c.m.answersInWindow = 0;
            c.m.budgetSaid = false;
        }
        if (c.m.answersInWindow >= BUDGET_ANSWERS && c.ask.bot().stranger()) {
            if (c.m.budgetSaid) {
                return new ContentAnswer(Outcome.BUDGET, kind, shape, null, subject, null, Map.of()); // already said "ask someone else"
            }
            c.budget = true;
            return reply(c, Outcome.BUDGET, shape, kind, null, subject, line(c, "budget", Map.of()), facts(c));
        }
        return null;
    }

    private void record(Context c, ContentAnswer result) {
        Memory m = c.m;
        if (result.outcome() == Outcome.SILENT || result.reply() == null && result.facts().isEmpty()) {
            return; // nothing said: a deliberate silence leaves the conversation as it was
        }
        long now = c.ask.nowMs();
        m.lastAt = now;
        m.lastOutcome = result.outcome();
        if (result.subject() != null && result.outcome() != Outcome.BUDGET) {
            m.lastSubject = result.subject();
            m.lastKind = result.kind();
        }
        if (c.repeatKey != 0) {
            m.repeated.add(c.repeatKey);
        }
        if (c.budget) {
            m.budgetSaid = true;
        }
        if (result.outcome() == Outcome.ANSWERED && result.subject() != null && result.reply() != null) {
            long key = result.subject().key() * 31 + result.kind().ordinal();
            m.answeredAt.put(key, now);
            m.answeredReply.put(key, result.reply());
            m.answersInWindow++;
            m.turn++;
            trim(m.answeredAt);
            trim(m.answeredReply);
        }
        switch (result.outcome()) {
            case ANSWERED, REPEAT -> answered.incrementAndGet();
            case DEFLECTED -> deflected.incrementAndGet();
            default -> {
            }
        }
    }

    private Memory memory(Ask ask) {
        long key = memoryKey(ask);
        if (memory.size() > MEMORY_MAX) {
            long now = ask.nowMs();
            memory.values().removeIf(m -> now - m.lastAt > REPEAT_MS && m.pending == null);
            if (memory.size() > MEMORY_MAX) {
                evictOldest(); // a busy server: the oldest conversations go, a pending ask-back from seconds ago stays
            }
        }
        return memory.computeIfAbsent(key, k -> new Memory());
    }

    private void evictOldest() {
        List<long[]> byAge = new ArrayList<>(); // {key, lastAt}, read once: other threads keep writing lastAt
        for (Map.Entry<Long, Memory> entry : memory.entrySet()) {
            byAge.add(new long[]{entry.getKey(), entry.getValue().lastAt});
        }
        byAge.sort(Comparator.comparingLong(entry -> entry[1]));
        for (int i = 0; i < byAge.size() - MEMORY_MAX * 3 / 4; i++) {
            memory.remove(byAge.get(i)[0]);
        }
        pendingByAsker.values().removeIf(key -> !memory.containsKey(key));
    }

    private static long memoryKey(Ask ask) {
        return (long) ask.asker().id() << 32 | (ask.bot().id() & 0xffffffffL);
    }

    private Memory peek(Ask ask) {
        Memory existing = memory.get(memoryKey(ask));
        return existing == null ? new Memory() : existing;
    }

    private static boolean remembering(Context c) {
        return c.remember;
    }

    /** One-line price: two significant figures, k / m ("25m", "1.5m", "90k", "850"). */
    static String formatPrice(long price) {
        long rounded = roundPrice(price);
        if (rounded >= 1_000_000L) {
            return trimZero(rounded / 1_000_000d) + "m";
        }
        if (rounded >= 1_000L) {
            return trimZero(rounded / 1_000d) + "k";
        }
        return String.valueOf(rounded);
    }

    static long roundPrice(long price) {
        if (price < 100) {
            return price;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(price)) - 1);
        return Math.round(Math.round(price / magnitude) * magnitude);
    }

    private static String trimZero(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    // ---- composition by kind -------------------------------------------------------------------

    private record Composed(Outcome outcome, String reply, Map<String, String> facts) {
    }

    /**
     * A place or mob a little past the bot's horizon (or a famous boss or PQ beyond it): known by name and rough
     * place only, never a route, map or drop table ("ludi somewhere, high lvl tho").
     */
    private Composed rough(Context c, Entity subject) {
        int map;
        if (subject.kind() == EntityKind.MOB) { // known by name only: its place need not be a map the bot saw
            List<Integer> spawns = c.k.spawnMaps(subject.id());
            map = !spawns.isEmpty() ? bestSpawn(c, subject.id(), spawns) // a boss's room, never an event map named after it
                    : c.k.names().resolve(subject.say(), List.of(EntityKind.MAP)).best().map(b -> b.entity().id())
                    .filter(room -> c.k.map(room).map(MapFact::region).isPresent()).orElse(0);
        } else {
            map = targetMap(c, subject);
        }
        if (map == 0) {
            return new Composed(Outcome.DEFLECTED, line(c, deflectKey(c, subject, Kind.WHERE_IS), Map.of()),
                    facts(c, "content.boundary", "you have never been near " + subject.say() + "; say you don't know"));
        }
        String town = townSay(c, map);
        boolean itself = town.equals(subject.say()); // "how do i get to leafre": not "somewhere around leafre"
        // "high lvl tho" only of what is past the bot's level; a place it just never went to is "never been"
        boolean far = !synthesized(c) || deflectLevel(c, subject) > c.ask.bot().level() + horizon.slack();
        return new Composed(Outcome.ANSWERED, line(c, (itself ? "rough.town" : "rough") + (far ? "" : ".heard"),
                Map.of("x", subject.say(), "town", town)), facts(c, "content.where", subject.say() + " is known only by name"
                + (itself ? "" : ", somewhere around " + town) + (far ? "; far above the speaker's level, so no route or details"
                : "; never been there, so no route or details")));
    }

    /** What a rough answer can cover: where a place or mob is and the way there; never a drop table or an item. */
    private static boolean roughly(Kind kind, Entity subject) {
        return subject.kind() != EntityKind.ITEM && (kind == Kind.WHERE_IS || kind == Kind.ROUTE);
    }

    private Composed compose(Context c, Kind kind, Entity subject, Shape shape) {
        return switch (kind) {
            case DROP_SOURCE -> dropSource(c, subject);
            case MOB_DROPS -> mobDrops(c, subject);
            case BUY_WHERE, GET_WHERE -> buyOrGet(c, subject, kind == Kind.GET_WHERE);
            case WHERE_IS -> whereIs(c, subject);
            case ROUTE -> route(c, subject, shape);
            case PRICE -> price(c, subject);
            case LEVEL_OF -> levelAnswer(c, subject);
            default -> new Composed(Outcome.SILENT, null, Map.of());
        };
    }

    /** One mob source: its spoken name, nearest map, effective chance, and the score that ranks it for this asker. */
    private record Source(DropSource drop, MobFact mob, int map, double chance, double score) {
    }

    private Composed dropSource(Context c, Entity item) {
        Optional<ItemFact> fact = c.k.item(item.id());
        if (item.id() == TELE_ROCK) {
            return answered(c, "rules.rock", Map.of(), "content.rules", ServerRules.rock());
        }
        if (item.id() == CRIMSONWOOD_KEYSTONE) {
            return keystone(c, item);
        }
        if (fact.map(ItemFact::cash).orElse(false)) {
            return answered(c, "cash", Map.of(), "content.item_source", item.say() + ": a Cash Shop item, nothing drops it");
        }
        List<Source> sources = sources(c, item);
        List<Source> known = sources.stream().filter(s -> sourceKnown(c, s)).toList();
        int continent = c.ask.asker().mapId() / CONTINENT;
        List<DropSource> globals = c.k.dropSources(item.id()).stream()
                .filter(drop -> drop.global() && (drop.continent() < 0 || drop.continent() == continent)).toList();
        double globalChance = globals.isEmpty() ? 0 : globals.get(0).effectiveChance(c.rates.drop(), c.rates.bossDrop());
        Map<String, String> facts = facts(c);
        if (known(c) == null) {
            put(facts, "content.item_source", item.say() + " drops from: " + describe(c, sources));
        } else if (!known.isEmpty() || sources.isEmpty()) {
            put(facts, "content.item_source", item.say() + " drops from: " + describe(c, known)); // only what it can vouch for
        }
        if (sources.isEmpty() && !globals.isEmpty()) {
            put(facts, "content.item_source", item.say() + ": any monster can drop it (" + bucket(globalChance) + ")");
            if (item.id() == PERFECT_PITCH) {
                put(facts, "content.rules", ServerRules.pitch());
                return new Composed(Outcome.ANSWERED, line(c, "drop.pitch", Map.of()), facts);
            }
            return new Composed(Outcome.ANSWERED, line(c, "drop.global", Map.of("bucket", bucketPhrase(c, globalChance, false))), facts);
        }
        if (!globals.isEmpty()) {
            put(facts, "content.item_source_any", item.say() + ": any monster can also drop it (" + bucket(globalChance) + ")");
        }
        if (sources.isEmpty()) {
            Optional<ShopSource> shop = nearestShop(c, item);
            if (shop.isPresent()) {
                put(facts, "content.shop", shopFact(c, item, shop.get()));
                return new Composed(Outcome.ANSWERED, line(c, "drop.none.shop", Map.of("npc", npcSay(c, shop.get().npcId()),
                        "town", townSay(c, nearestMap(c, shop.get().npcMaps())))), facts);
            }
            if (!c.k.shopsSelling(item.id()).isEmpty()) {
                put(facts, "content.item_source", item.say() + (synthesized(c) && nearestShop(c, item, null).isPresent()
                        ? ": nothing drops it; you don't know which shop sells it" : ": nothing drops it; only a shop the asker cannot reach sells it"));
                return new Composed(Outcome.ANSWERED, line(c, "drop.none.fm", Map.of()), facts);
            }
            if (remembering(c) && logUnresolved) {
                log.info("[knowledge] no source for item {} ({})", item.id(), item.name());
            }
            put(facts, "content.item_source", item.say() + ": nothing drops it and no shop sells it");
            return new Composed(Outcome.ANSWERED, line(c, "drop.none", Map.of()), facts);
        }
        if (known.isEmpty() && synthesized(c)) {
            Optional<ShopSource> shop = nearestShop(c, item); // never "nothing drops it": it just never saw what does
            if (shop.isPresent()) {
                put(facts, "content.shop", shopFact(c, item, shop.get()));
                return new Composed(Outcome.ANSWERED, shopReply(c, shop.get()), facts);
            }
            return new Composed(Outcome.DEFLECTED, line(c, "drop.unknown", Map.of()),
                    facts(c, "content.boundary", "you have never seen what drops " + item.say() + "; say you don't know"));
        }
        if (known.isEmpty()) {
            return new Composed(Outcome.DEFLECTED, line(c, deflectKey(c, item, Kind.DROP_SOURCE), Map.of()),
                    facts(c, "content.boundary", "only monsters far above you drop " + item.say() + "; say you don't know"));
        }
        Source top = known.get(0);
        String mobs = mobSay(c, top.mob());
        if (top.drop().scriptOnly()) {
            for (ScriptSpawn spawn : c.k.scriptSpawns(top.mob().id())) {
                if (spawn.revivedFrom() != 0) {
                    mobs = c.k.names().say(EntityKind.MOB, spawn.revivedFrom());
                    break;
                }
            }
        }
        String map = top.map() == 0 ? "" : placeSay(c, top.map());
        Map<String, String> vars = mobVars(top.mob(), mobs);
        if (top.drop().scriptOnly() && top.drop().viaNpc() != 0 && top.map() != 0) {
            int town = c.k.townOf(top.map());
            return new Composed(Outcome.ANSWERED, line(c, "drop.script", Map.of("mobs", mobs, "map",
                    town != 0 ? mapSay(c, town) : map, "npc", npcSay(c, top.drop().viaNpc()))), facts);
        }
        if (top.drop().questId() > 0) {
            return new Composed(Outcome.ANSWERED, line(c, "drop.quest", Map.of("mobs", mobs)), facts);
        }
        vars.put("map", map);
        vars.put("bucket", bucketPhrase(c, top.chance(), top.mob().boss()));
        boolean heard = c.heard || known(c) != null && known(c).heardOnly(EntityKind.MOB, top.mob().id());
        vars.put("hedge", heard ? ReplyBank.phrase("hedge.heard", c.voice, c.rng) : c.hedged ? ReplyBank.phrase("hedge", c.voice, c.rng) : "");
        Source runner = null;
        for (int i = 1; i < known.size() && runner == null; i++) {
            Source next = known.get(i);
            if (next.score() < top.score() * RUNNER_UP) {
                break;
            }
            if (!mobSay(c, next.mob()).equals(mobs) && !sameFamily(top.mob(), next.mob())) {
                runner = next; // "zombie mushies or annoyed zombie mushrooms" is one mob twice
            }
        }
        String key;
        if (runner != null) {
            vars.put("mobs2", mobSay(c, runner.mob()));
            double best = Math.max(top.chance(), runner.chance());
            vars.put("bucket", bucketPhrase(c, best, top.mob().boss() && runner.mob().boss()));
            key = best < SOMETIMES ? "drop.two.rare" : "drop.two"; // a rare drop always says so
        } else {
            key = top.chance() >= COMMON && !map.isEmpty() && c.rng.nextBoolean() ? "drop.one.common" : "drop.one";
        }
        String text = raw(c, key, vars);
        if (text != null && !globals.isEmpty() && top.chance() < SOMETIMES) {
            String also = ReplyBank.phrase("drop.also.global", c.voice, c.rng); // chaos scrolls: the mobs, and anything
            text = text.length() + also.length() <= maxChars ? text + also : text;
        }
        return new Composed(Outcome.ANSWERED, done(c, key, text), facts);
    }

    /** One mob under two names: every word of one's name is in the other's ("Zombie Mushroom", "Annoyed Zombie Mushroom"). */
    private static boolean sameFamily(MobFact a, MobFact b) {
        Set<String> x = Set.of(NameIndex.normalize(a.name()).split(" "));
        Set<String> y = Set.of(NameIndex.normalize(b.name()).split(" "));
        return x.containsAll(y) || y.containsAll(x);
    }

    /**
     * Mob sources of an item, one per spoken mob name, best first for this asker. Every row gets the cheap part
     * of the score (level fit x chance bucket); only the best few pay for spawn maps and route distances.
     */
    private List<Source> sources(Context c, Entity item) {
        List<Source> rough = new ArrayList<>();
        for (DropSource drop : c.k.dropSources(item.id())) {
            if (drop.global()) {
                continue;
            }
            MobFact mob = c.k.mob(drop.mobId()).orElse(null);
            if (!meetable(c, drop, mob)) {
                continue; // a copy nobody can meet (event or PQ instances without a placement)
            }
            double chance = drop.effectiveChance(c.rates.drop(), c.rates.bossDrop());
            rough.add(new Source(drop, mob, 0, chance, fit(c.ask.asker().level(), mob.level()) * bucketWeight(chance)
                    * (drop.questId() > 0 ? 0.5 : 1d)));
        }
        rough.sort(Comparator.comparingDouble(Source::score).reversed());
        Map<String, Source> bySay = new LinkedHashMap<>();
        for (int i = 0; i < rough.size() && i < SOURCE_SCAN; i++) {
            Source source = rough.get(i);
            DropSource drop = source.drop();
            List<Integer> spawns = knownSpawns(c, drop.maps());
            int map = spawns.isEmpty() ? 0 : bestSpawn(c, drop.mobId(), spawns);
            boolean field = map != 0 && c.k.map(map).map(m -> m.region() != null && m.forcedReturn() == 0).orElse(false);
            double score = source.score() * distanceFactor(c, map) * (field ? 1d : EVENT_PLACE);
            String say = mobSay(c, source.mob());
            Source previous = bySay.get(say);
            if (previous == null || previous.score() < score) {
                bySay.put(say, new Source(drop, source.mob(), map, source.chance(), score));
            }
        }
        List<Source> out = new ArrayList<>(bySay.values());
        out.sort(Comparator.comparingDouble(Source::score).reversed());
        return out;
    }

    private String describe(Context c, List<Source> sources) {
        if (sources.isEmpty()) {
            return "no monster";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sources.size() && i < FACT_SOURCES; i++) {
            Source s = sources.get(i);
            out.append(i == 0 ? "" : "; ").append(mobSay(c, s.mob())).append(" (lv ").append(s.mob().level()).append(", ")
                    .append(bucket(s.chance())).append(veteran(c) ? ", " + percent(s.chance()) : "");
            if (s.map() != 0) {
                out.append(", ").append(mapSay(c, s.map()));
            }
            if (s.drop().questId() > 0) {
                out.append(", only during its quest");
            }
            if (s.drop().scriptOnly() && s.drop().viaNpc() != 0) {
                out.append(", spawned by ").append(npcSay(c, s.drop().viaNpc()));
            }
            out.append(')');
        }
        return out.toString();
    }

    private Composed mobDrops(Context c, Entity mob) {
        List<DropSource> drops = new ArrayList<>();
        for (int id : mob.ids()) {
            drops.addAll(c.k.mobDrops(id)); // an alias may cover several bodies (zakum = Zakum1, Zakum2, Zakum3)
        }
        Map<String, String> facts = facts(c);
        ItemFact etc = null;
        ItemFact notable = null;
        double etcChance = 0;
        int notablePrice = -1;
        StringBuilder list = new StringBuilder();
        boolean synthesized = synthesized(c);
        boolean unseen = false;
        for (DropSource drop : drops) {
            ItemFact item = c.k.item(drop.itemId()).orElse(null);
            if (item == null || item.unnamed() || drop.questId() > 0 || item.id() >= CARD_FIRST && item.id() <= CARD_LAST) {
                continue;
            }
            double chance = drop.effectiveChance(c.rates.drop(), c.rates.bossDrop());
            if (synthesized && !PersonalHorizon.sourceKnown(c.k, known(c), drop, chance) && !ownRef(c, drop)) {
                unseen = true; // a drop it never saw: not listed, so the model cannot say it either
                continue;
            }
            if (list.length() < FACT_CHARS) {
                list.append(list.length() == 0 ? "" : ", ").append(itemSay(c, item.id())).append(" (").append(bucket(chance)).append(')');
            }
            if (item.id() / 1_000_000 == 4 && (item.id() < MINIGAME_FIRST || item.id() > MINIGAME_LAST) && chance > etcChance) {
                etc = item;
                etcChance = chance;
            }
            boolean wearOrScroll = item.equip() || item.id() / 10_000 == 204;
            boolean famous = c.k.names().aliased(EntityKind.ITEM, item.id()) && (wearOrScroll || item.id() / 1_000_000 == 4);
            int worth = famous ? Integer.MAX_VALUE : wearOrScroll ? item.price() : -1;
            if (worth > notablePrice && (synthesized || horizon.withinReach(knower(c), item.reqLevel()))) {
                notable = item;
                notablePrice = worth;
            }
        }
        if (unseen && list.length() == 0) {
            return new Composed(Outcome.DEFLECTED, line(c, "mob.drops.unknown", Map.of()),
                    facts(c, "content.boundary", "you never saw what " + mob.say() + " drop; say you don't know"));
        }
        put(facts, "content.mob_drops", mob.say() + " drop: " + (list.length() == 0 ? "nothing listed" : list));
        if (notable == etc) {
            notable = null;
        }
        if (etc == null && notable == null) {
            return new Composed(Outcome.ANSWERED, line(c, "mob.drops.none", Map.of()), facts);
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("mobs", mob.say());
        vars.put("etc", itemSay(c, (etc != null ? etc : notable).id()));
        if (etc != null && notable != null) {
            vars.put("notable", itemSay(c, notable.id()));
            return new Composed(Outcome.ANSWERED, line(c, "mob.drops", vars), facts);
        }
        return new Composed(Outcome.ANSWERED, line(c, "mob.drops.etc", vars), facts);
    }

    private Composed buyOrGet(Context c, Entity item, boolean get) {
        if (item.id() == TELE_ROCK) {
            return answered(c, "rules.rock", Map.of(), "content.rules", ServerRules.rock());
        }
        if (item.id() == CRIMSONWOOD_KEYSTONE) {
            return keystone(c, item);
        }
        if (c.k.item(item.id()).map(ItemFact::cash).orElse(false)) {
            return answered(c, "cash", Map.of(), "content.shop", item.say() + ": a Cash Shop item");
        }
        Optional<ShopSource> shop = nearestShop(c, item);
        Map<String, String> facts = facts(c);
        if (shop.isPresent()) {
            put(facts, "content.shop", shopFact(c, item, shop.get()));
            return new Composed(Outcome.ANSWERED, shopReply(c, shop.get()), facts);
        }
        List<Source> sources = sources(c, item);
        List<Source> told = known(c) == null ? sources : sources.stream().filter(s -> sourceKnown(c, s)).toList();
        boolean unknownShop = synthesized(c) && nearestShop(c, item, null).isPresent(); // a shop sells it; it never saw which
        put(facts, unknownShop ? "content.boundary" : "content.shop", unknownShop
                ? "you don't know who sells " + item.say() + "; say you don't know" : item.say() + ": no NPC shop sells it");
        if (!told.isEmpty()) {
            put(facts, "content.item_source", item.say() + " drops from: " + describe(c, told));
        }
        if (get && !sources.isEmpty()) {
            Composed drop = dropSource(c, item);
            Map<String, String> merged = new LinkedHashMap<>(facts);
            drop.facts().forEach(merged::putIfAbsent);
            return new Composed(drop.outcome(), drop.reply(), merged);
        }
        if (unknownShop) {
            return new Composed(Outcome.DEFLECTED, line(c, "buy.unknown", Map.of()), facts);
        }
        Source drop = synthesized(c) ? told.isEmpty() ? null : told.get(0)
                : !sources.isEmpty() && sourceKnown(c, sources.get(0)) ? sources.get(0) : null;
        if (drop != null) {
            return new Composed(Outcome.ANSWERED, line(c, "buy.none.drop", mobVars(drop.mob(), mobSay(c, drop.mob()))), facts);
        }
        return new Composed(Outcome.ANSWERED, line(c, get ? "get.fm" : "buy.none", Map.of()), facts);
    }

    private String shopReply(Context c, ShopSource shop) {
        int map = nearestMap(c, shop.npcMaps());
        Map<String, String> vars = new HashMap<>();
        vars.put("npc", npcSay(c, shop.npcId()));
        vars.put("town", townSay(c, map));
        vars.put("spot", spot(c, map));
        vars.put("role", c.k.names().roleOf(shop.npcId()).orElse(""));
        if (shop.pitch() > 0) {
            return line(c, "buy.pitch", vars);
        }
        boolean here = c.k.townOf(map) != 0 && c.k.townOf(map) == c.k.townOf(c.ask.asker().mapId());
        return line(c, here ? "buy.here" : "buy.there", vars);
    }

    private String shopFact(Context c, Entity item, ShopSource shop) {
        int map = nearestMap(c, shop.npcMaps());
        return item.say() + ": sold by " + npcSay(c, shop.npcId()) + " in " + townSay(c, map)
                + (spot(c, map).isEmpty() ? "" : " (" + spot(c, map) + ")")
                + (shop.pitch() > 0 ? " for " + shop.pitch() + " perfect pitch" : " for " + formatPrice(shop.price()) + " mesos");
    }

    /** The nearest shop selling the item; a synthesized past only knows the shops of places it knows. */
    private Optional<ShopSource> nearestShop(Context c, Entity item) {
        return nearestShop(c, item, synthesized(c) ? known(c) : null);
    }

    /** {@code known} null: any shop. */
    private Optional<ShopSource> nearestShop(Context c, Entity item, KnownWorld known) {
        ShopSource best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ShopSource shop : c.k.shopsSelling(item.id())) {
            int map = nearestMap(c, shop.npcMaps());
            if (map == 0 || c.k.map(map).map(MapFact::region).orElse(null) == null) {
                continue; // GM and event shops have no reachable placement
            }
            if (known != null && !PersonalHorizon.placeKnown(c.k, known, map)) {
                continue;
            }
            int distance = c.k.distance(c.ask.asker().mapId(), map);
            if (distance == Integer.MAX_VALUE && map / CONTINENT != c.ask.asker().mapId() / CONTINENT) {
                continue; // Amherst's shops from Victoria: no way back to Maple Island
            }
            if (best == null || distance < bestDistance || distance == bestDistance && shop.pitch() < best.pitch()) {
                best = shop;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    private Composed whereIs(Context c, Entity subject) {
        int asker = c.ask.asker().mapId();
        Map<String, String> facts = facts(c);
        return switch (subject.kind()) {
            case NPC -> {
                int map = nearestMap(c, knownFirst(c, c.k.npcMaps(subject.id())));
                if (map == 0) {
                    yield new Composed(Outcome.ANSWERED, line(c, "boundary", Map.of()), facts(c, "content.where",
                            subject.say() + " is not standing anywhere you can reach"));
                }
                put(facts, "content.where", subject.say() + " stands in " + placeFact(c, map));
                yield new Composed(Outcome.ANSWERED, npcWhere(c, subject.say(), map), facts);
            }
            case ROLE -> {
                int npc = nearestNpc(c, knownNpcs(c, subject.ids()));
                int map = nearestMap(c, knownFirst(c, c.k.npcMaps(npc)));
                put(facts, "content.where", "nearest " + subject.say() + ": " + npcSay(c, npc) + " in " + placeFact(c, map));
                String spot = spot(c, map);
                boolean bareSpot = spot.isEmpty() || spot.contains(subject.say()) || subject.say().contains(spot);
                Map<String, String> vars = Map.of("role", subject.say(), "npc", npcSay(c, npc), "town", townSay(c, map),
                        "spot", bareSpot ? "" : spot);
                boolean here = c.k.townOf(map) != 0 && c.k.townOf(map) == c.k.townOf(asker);
                yield new Composed(Outcome.ANSWERED, line(c, here ? (bareSpot ? "where.role.square" : "where.role.here")
                        : bareSpot ? "where.role.far.square" : "where.role.far", vars), facts);
            }
            case MOB -> {
                List<Integer> maps = c.k.spawnMaps(subject.id());
                if (maps.isEmpty()) {
                    Optional<NameIndex.Candidate> named = c.k.names().resolve(subject.say(), List.of(EntityKind.MAP)).best();
                    if (named.isPresent()) {
                        int lair = named.get().entity().id(); // a boss summoned in its own room ("zakum" -> zakums altar)
                        put(facts, "content.where", subject.say() + " is fought in " + placeFact(c, lair));
                        yield new Composed(Outcome.ANSWERED, line(c, close(c, lair) ? "where.map.close" : "where.map",
                                Map.of("map", mapSay(c, lair), "town", townSay(c, lair))), facts);
                    }
                    yield new Composed(Outcome.ANSWERED, line(c, "where.mob.none", Map.of()),
                            facts(c, "content.where", subject.say() + ": no known spawn"));
                }
                maps = knownSpawns(c, maps);
                if (maps.isEmpty()) { // heard of, never met: no place to name
                    yield new Composed(Outcome.DEFLECTED, line(c, "deflect.unseen", Map.of()),
                            facts(c, "content.boundary", "you have never seen where " + subject.say() + " are; say you don't know"));
                }
                int map = bestSpawn(c, subject.id(), maps);
                put(facts, "content.where", subject.say() + " spawn at " + placesFact(c, maps, map));
                List<ScriptSpawn> scripts = c.k.scriptSpawns(subject.id());
                if (!scripts.isEmpty() && scripts.get(0).viaNpc() != 0 && c.k.map(map).map(m -> !m.mobs().contains(subject.id())).orElse(true)) {
                    int town = c.k.townOf(map);
                    yield new Composed(Outcome.ANSWERED, line(c, "where.mob.script", Map.of("map", mapSay(c, town != 0 ? town : map),
                            "npc", npcSay(c, scripts.get(0).viaNpc()))), facts);
                }
                MobFact mob = c.k.mob(subject.id()).orElse(null);
                Map<String, String> vars = mobVars(mob, subject.say());
                vars.put("map", placeSay(c, map));
                vars.put("town", townSay(c, map));
                yield new Composed(Outcome.ANSWERED, line(c, mob != null && mob.boss() ? "where.mob.boss" : "where.mob", vars), facts);
            }
            case PQ -> {
                int lobby = subject.ids().getInt(0);
                put(facts, "content.where", subject.say() + ": lobby " + placeFact(c, lobby) + ", started by "
                        + npcSay(c, subject.npc()) + ", levels " + subject.minLevel() + "-" + subject.maxLevel());
                boolean keystone = lobby == CWKPQ_LOBBY && c.ask.bot().level() >= KEYSTONE_QUEST_LEVEL;
                if (keystone) {
                    put(facts, "content.item_source", KEYSTONE_FACT);
                }
                yield new Composed(Outcome.ANSWERED, line(c, keystone ? "where.pq.keystone" : "where.pq",
                        Map.of("pq", subject.say(), "town", townSay(c, lobby), "npc", npcSay(c, subject.npc()))), facts);
            }
            default -> {
                int map = nearestMap(c, knownFirst(c, subject.ids()));
                if (map == asker || subject.ids().contains(asker)) {
                    yield new Composed(Outcome.ANSWERED, line(c, "where.map.here", Map.of()), facts(c, "content.where",
                            "the asker is on " + subject.say()));
                }
                if (c.k.isTown(map) || inFreeMarket(map)) {
                    yield route(c, subject, null); // a town (or the fm): how to get there
                }
                put(facts, "content.where", subject.say() + ": " + placeFact(c, map));
                yield new Composed(Outcome.ANSWERED, line(c, close(c, map) ? "where.map.close" : "where.map",
                        Map.of("map", subject.say(), "town", townSay(c, map))), facts);
            }
        };
    }

    /** A short walk from the asker ("near {town}, its close" is only said of these). */
    private static boolean close(Context c, int map) {
        return c.k.distance(c.ask.asker().mapId(), map) <= CLOSE_WALK_MAPS;
    }

    private String npcWhere(Context c, String npc, int map) {
        int asker = c.ask.asker().mapId();
        String spot = spot(c, map);
        Map<String, String> vars = Map.of("npc", npc, "spot", spot, "town", townSay(c, map));
        if (map == asker) {
            return line(c, "where.npc.here", vars);
        }
        boolean sameTown = c.k.townOf(map) != 0 && c.k.townOf(map) == c.k.townOf(asker);
        if (sameTown) {
            return line(c, spot.isEmpty() ? "where.npc.square" : "where.npc.town", vars);
        }
        return line(c, spot.isEmpty() ? "where.npc.far.square" : "where.npc.far", vars);
    }

    private Composed route(Context c, Entity subject, Shape shape) {
        int from = c.ask.asker().mapId();
        if (shape != null && !shape.from().isEmpty()) {
            NameIndex.Resolution start = c.k.names().resolveSlot(shape.from(), List.of(EntityKind.MAP, EntityKind.REGION));
            if (start.resolved()) {
                from = nearestMap(c, start.best().get().entity().ids());
            }
        }
        int to = targetMap(c, subject);
        Map<String, String> facts = facts(c);
        if (inFreeMarket(to) && !inFreeMarket(from)) {
            return new Composed(Outcome.ANSWERED, line(c, "route.fm", Map.of("town", townSay(c, from))),
                    facts(c, "content.route", "every town has a free market portal; the free market entrance leads to 22 rooms"));
        }
        if (to == 0) {
            return new Composed(Outcome.ANSWERED, line(c, "route.none", Map.of()), facts(c, "content.route", "no known way to " + subject.say()));
        }
        int fromTown = c.k.townOf(from);
        int toTown = c.k.townOf(to);
        String dest = subject.kind() == EntityKind.MAP || subject.kind() == EntityKind.REGION ? subject.say() : mapSay(c, to);
        if (from == to || to == toTown && fromTown == toTown && toTown != 0) {
            return new Composed(Outcome.ANSWERED, line(c, "route.same", Map.of("town", mapSay(c, toTown == 0 ? to : toTown))),
                    facts(c, "content.route", "the asker is already in " + dest));
        }
        if (fromTown != 0 && fromTown == toTown) {
            return new Composed(Outcome.ANSWERED, line(c, "route.near", Map.of("town", mapSay(c, toTown), "dest", dest)),
                    facts(c, "content.route", dest + " is inside " + mapSay(c, toTown)));
        }
        List<Integer> hops = c.k.route(from, to);
        if (hops.isEmpty()) {
            return new Composed(Outcome.ANSWERED, line(c, "route.none", Map.of()), facts(c, "content.route", "no known way to " + dest));
        }
        List<int[]> rides = new ArrayList<>();
        for (int i = 0; i + 1 < hops.size(); i++) {
            Optional<TravelLeg> leg = c.k.travelLeg(hops.get(i), hops.get(i + 1));
            if (leg.isPresent() && !rideSay(leg.get()).isEmpty()) {
                rides.add(new int[]{i, hops.get(i), hops.get(i + 1)});
            }
        }
        String via = landmark(c, c.k.path(from, to), from, to);
        put(facts, "content.route", routeFact(c, hops, rides, dest));
        Map<String, String> vars = new HashMap<>();
        vars.put("town", mapSay(c, fromTown == 0 ? from : fromTown));
        vars.put("from", fromTown != 0 && fromTown == c.k.townOf(c.ask.asker().mapId()) ? "here" : vars.get("town"));
        vars.put("dest", dest);
        vars.put("via", via);
        String key;
        String plain = null; // the ride alone, when its tail cannot be said (a destination with no spoken name)
        if (rides.isEmpty()) {
            boolean taxi = fromTown != 0 && toTown != 0 && c.k.travelLeg(fromTown, toTown).map(l -> "taxi".equals(l.kind())).orElse(false);
            boolean close = c.k.path(from, to).size() <= CLOSE_WALK_MAPS;
            key = taxi && c.rng.nextBoolean() ? "route.taxi" : !via.isEmpty() ? "route.walk.via" : close ? "route.walk" : "route.walk.far";
        } else {
            int[] first = rides.get(0);
            TravelLeg leg = c.k.travelLeg(first[1], first[2]).orElseThrow();
            vars.put("ride", rideSay(leg));
            vars.put("a", boardSay(c, first[1], rideSay(leg)));
            vars.put("b", arrivalSay(c, first[2], to));
            int[] last = first;
            if (rides.size() >= 2) {
                last = rides.get(1);
                vars.put("ride2", rideSay(c.k.travelLeg(last[1], last[2]).orElseThrow()));
                vars.put("b2", arrivalSay(c, last[2], to));
            }
            // the last ride named lets off short of the destination ("boat to orbis" for el nath): say the rest
            boolean short_ = townOrSelf(c, last[2]) != townOrSelf(c, to) || rides.size() > 2;
            String after = short_ ? landmark(c, c.k.path(last[2], to), last[2], to) : "";
            vars.put("via", after.equals(dest) ? "" : after);
            String legs = rides.size() >= 2 ? "route.two" : "route.one";
            key = rides.size() > 2 ? "route.two.on" : !short_ ? legs : !vars.get("via").isEmpty() ? legs + ".then" : legs + ".walk";
            plain = legs;
        }
        String text = raw(c, key, vars);
        if (text == null && plain != null) {
            key = plain;
            text = raw(c, key, vars);
        }
        if (text == null) {
            return new Composed(Outcome.ANSWERED, line(c, "route.none", Map.of()), facts);
        }
        int targetLevel = c.k.map(to).map(MapFact::mobLevel).orElse(0);
        if (targetLevel > c.ask.asker().level() + FAR_TRIP_LEVELS) {
            text = clip(ReplyBank.phrase("route.far", c.voice, c.rng) + text);
        } else if (c.ask.asker().level() >= 10 && c.rng.nextDouble() < ROCK_CHANCE) {
            String rock = ReplyBank.phrase("route.rock", c.voice, c.rng);
            text = text.length() + rock.length() <= maxChars ? text + rock : text;
        }
        return new Composed(Outcome.ANSWERED, done(c, key, text), facts);
    }

    private String routeFact(Context c, List<Integer> hops, List<int[]> rides, String dest) {
        StringBuilder out = new StringBuilder("to ").append(dest).append(": ");
        for (int i = 0; i < hops.size(); i++) {
            out.append(i == 0 ? "" : " -> ").append(mapSay(c, hops.get(i)));
        }
        for (int[] ride : rides) {
            c.k.travelLeg(ride[1], ride[2]).ifPresent(leg -> out.append("; ").append(rideSay(leg)).append(" from ")
                    .append(boardSay(c, ride[1], rideSay(leg))).append(leg.cost() > 0 ? " (" + formatPrice(leg.cost()) + " mesos)" : ""));
        }
        return out.toString();
    }

    /**
     * The first named map series or town a walk passes ("orbis tower", "ellinia"), else the destination's own
     * town when the walk starts in another ("past sleepywood"); "" when none.
     */
    private String landmark(Context c, List<Integer> path, int from, int to) {
        for (int i = 1; i + 1 < path.size(); i++) {
            int map = path.get(i);
            Optional<Entity> series = c.k.names().seriesOf(map);
            if (series.isPresent() && i + 2 < path.size() && series.get().ids().contains(path.get(i + 1))) {
                return series.get().say();
            }
            if (c.k.isTown(map) && map != c.k.townOf(from) && map != c.k.townOf(to)) {
                return mapSay(c, map);
            }
        }
        int toTown = c.k.townOf(to);
        return toTown != 0 && toTown != to && toTown != c.k.townOf(from) ? mapSay(c, toTown) : "";
    }

    private Composed price(Context c, Entity item) {
        if (c.k.item(item.id()).map(ItemFact::cash).orElse(false)) {
            return answered(c, "cash", Map.of(), "content.price", item.say() + ": a Cash Shop item");
        }
        IntFunction<PriceQuote> lookup = prices != null ? prices : installedPrices;
        PriceQuote quote = lookup.apply(item.id());
        if (quote == null || !quote.known() || quote.price() <= 0) {
            Optional<ShopSource> shop = nearestShop(c, item);
            if (shop.isPresent() && shop.get().pitch() == 0 && shop.get().price() > 0) { // red pots: the store's price
                String p = formatPrice(shop.get().price());
                return new Composed(Outcome.ANSWERED, line(c, "price.shop", Map.of("p", p, "npc", npcSay(c, shop.get().npcId()))),
                        facts(c, "content.price", item.say() + ": " + p + " mesos at " + npcSay(c, shop.get().npcId())
                                + "'s shop; no free market sales seen"));
            }
            return new Composed(Outcome.ANSWERED, line(c, "price.unknown", Map.of()),
                    facts(c, "content.price", item.say() + ": no recent sales seen; say you don't know what it goes for"));
        }
        String p = formatPrice(quote.price());
        boolean set = RECHARGEABLE_PREFIX.contains(String.valueOf(item.id()).substring(0, 3));
        boolean scroll = item.id() / 10_000 == 204;
        String key = set ? "price.set" : scroll && c.rng.nextBoolean() ? "price.each" : "price.known";
        return new Composed(Outcome.ANSWERED, line(c, key, Map.of("p", p)), facts(c, "content.price",
                item.say() + ": about " + p + (set ? " a set" : "") + " in the free market lately (" + (quote.observations() > 0
                        ? quote.observations() + " sales seen" : "usual price") + ")"));
    }

    private Composed levelAnswer(Context c, Entity subject) {
        int asker = c.ask.asker().level();
        return switch (subject.kind()) {
            case MOB -> {
                int level = c.k.mob(subject.id()).map(MobFact::level).orElse(0);
                Map<String, String> vars = mobVars(c.k.mob(subject.id()).orElse(null), subject.say());
                vars.put("lvl5", String.valueOf(round5(level)));
                String key = Math.abs(level - asker) <= NEAR_LEVELS ? "level.mob.near"
                        : level > asker && level <= asker + FIT_ABOVE ? "level.mob.high" : "level.mob";
                yield new Composed(Outcome.ANSWERED, line(c, key, vars), facts(c, "content.level", subject.say() + ": level " + level));
            }
            case ITEM -> {
                int req = c.k.item(subject.id()).map(ItemFact::reqLevel).orElse(0);
                boolean worn = c.k.item(subject.id()).map(ItemFact::equip).orElse(false);
                yield new Composed(Outcome.ANSWERED, req == 0 ? line(c, "level.item.none", Map.of())
                        : line(c, worn ? "level.item" : "level.item.use", Map.of("req", String.valueOf(req), "item", subject.say())),
                        facts(c, "content.level", subject.say() + ": requires level " + req));
            }
            case PQ -> new Composed(Outcome.ANSWERED, line(c, "level.pq", Map.of("min", String.valueOf(subject.minLevel()),
                    "max", String.valueOf(subject.maxLevel()))), facts(c, "content.level", subject.say() + ": levels "
                    + subject.minLevel() + "-" + subject.maxLevel() + ", needs a party"));
            default -> {
                int level = levelOf(c, subject);
                yield level == 0 ? new Composed(Outcome.ANSWERED, line(c, "map.town", Map.of()), facts(c, "content.level", subject.say() + ": no monsters"))
                        : new Composed(Outcome.ANSWERED, line(c, "level.map", Map.of("lvl5", String.valueOf(round5(level)))),
                        facts(c, "content.level", subject.say() + ": monsters around level " + level));
            }
        };
    }

    private ContentAnswer train(Context c, Shape shape, int named) {
        int level = named > 0 ? named : c.ask.asker().level();
        if (shape.forOther() && named == 0) {
            if (remembering(c)) {
                setPending(c, new Pending(Kind.TRAIN_WHERE, shape, List.of(), c.ask.nowMs(), false, true));
            }
            return reply(c, Outcome.CLARIFY, shape, Kind.TRAIN_WHERE, null, null, line(c, "clarify.level", Map.of()), facts(c));
        }
        boolean own = named == 0 || named == c.ask.asker().level();
        String region = c.k.map(c.ask.asker().mapId()).map(MapFact::region).orElse(null);
        List<Integer> maps = personalSpots(c, level);
        if (maps != null) { // the spots it trained at, and the fields it knows, at that level
            if (maps.isEmpty()) {
                String key = level > c.ask.bot().level() ? own ? "train.high" : "train.high.other" : "train.unknown";
                return reply(c, Outcome.DEFLECTED, shape, Kind.TRAIN_WHERE, null, null, line(c, key, Map.of()),
                        facts(c, "content.boundary", "you don't know a training spot for level " + level + "; say so"));
            }
        } else if (!horizon.withinReach(knower(c), level)) {
            return reply(c, Outcome.DEFLECTED, shape, Kind.TRAIN_WHERE, null, null, line(c, own ? "train.high" : "train.high.other", Map.of()),
                    facts(c, "content.boundary", "training spots for level " + level + " are past you; say so"));
        } else {
            maps = c.k.trainingMaps(level, region, TRAINING_CANDIDATES);
            if (maps.isEmpty()) {
                maps = c.k.trainingMaps(level, null, TRAINING_CANDIDATES);
            }
            if (maps.isEmpty()) {
                return reply(c, Outcome.BOUNDARY, shape, Kind.TRAIN_WHERE, null, null, line(c, "train.none", Map.of()),
                        facts(c, "content.boundary", "no training map known for level " + level));
            }
        }
        List<Integer> ranked = new ArrayList<>(maps);
        int asker = c.ask.asker().mapId();
        // its own spots first (they may lie across the sea: those on the asker's side first), then the fields it knows
        boolean lived = known(c) != null;
        IntList trained = lived ? known(c).spots() : IntList.EMPTY;
        ranked.sort(Comparator.comparingInt((Integer map) -> lived && c.k.rides(asker, map) != 0 ? 1 : 0)
                .thenComparingInt(map -> trained.contains(map) ? 0 : 1)
                .thenComparingInt(map -> c.k.fieldCapacity(map) > 0 ? 0 : 1)
                .thenComparingInt(map -> hidden(c, map) ? 1 : 0) // a named field beats a Hidden Street nobody can find
                .thenComparingInt(map -> Math.abs(c.k.map(map).map(MapFact::mobLevel).orElse(level) - level) / TRAIN_ABOVE)
                .thenComparingInt(map -> c.k.distance(asker, map)));
        int pick = ranked.get(ranked.size() > 1 && c.rng.nextBoolean() ? 1 : 0);
        Entity map = c.k.names().entity(EntityKind.MAP, pick).orElseThrow();
        c.subject = map;
        Map<String, String> facts = facts(c);
        StringBuilder spots = new StringBuilder("training spots for level " + level + ": ");
        for (int i = 0; i < ranked.size() && i < FACT_SOURCES; i++) {
            spots.append(i == 0 ? "" : "; ").append(mapSay(c, ranked.get(i))).append(" (").append(mainMobSay(c, ranked.get(i)))
                    .append(", lv ").append(c.k.map(ranked.get(i)).map(MapFact::mobLevel).orElse(0)).append(')');
        }
        put(facts, "content.training", spots.toString());
        boolean victoria = "victoria".equals(region);
        String text;
        if (victoria && level >= KPQ_MIN && level < KPQ_MAX && named == 0 && c.rng.nextDouble() < KPQ_CHANCE) {
            text = line(c, "train.kpq", Map.of()); // "kpq till 30": not to someone who is already 30
        } else {
            Map<String, String> vars = Map.of("map", placeSay(c, pick), "mobs", mainMobSay(c, pick), "lvl", String.valueOf(level),
                    "town", townSay(c, pick));
            text = line(c, lived && c.k.rides(asker, pick) != 0 ? "train.far"
                    : named > 0 && named != c.ask.asker().level() ? "train.other" : "train", vars);
        }
        ContentAnswer limited = limits(c, shape, Kind.TRAIN_WHERE, map);
        return limited != null ? limited : reply(c, Outcome.ANSWERED, shape, Kind.TRAIN_WHERE, null, map, text, facts);
    }

    private ContentAnswer jobAdvance(Context c, Shape shape, AliasCatalog.JobBranch chosen) {
        int jobId = c.ask.asker().jobId();
        Optional<AliasCatalog.JobBranch> branch = Optional.ofNullable(chosen);
        if (branch.isEmpty() && !shape.topic().isEmpty()) {
            branch = c.k.names().jobWord(shape.topic());
            if (branch.isEmpty()) {
                return ContentAnswer.silent(shape); // "how do i become a better player"
            }
        }
        if (branch.isEmpty()) {
            branch = jobIn(c, NameIndex.normalize(shape.frame()));
        }
        if (branch.isEmpty()) {
            branch = c.k.names().branchOf(jobId);
        }
        if (branch.isEmpty() && jobId >= 1000) {
            int town = jobId < 2000 ? 130_000_000 : 140_000_000;
            return reply(c, Outcome.ANSWERED, shape, Kind.JOB_ADVANCE, null, null, line(c, "job.other", Map.of("town", mapSay(c, town))),
                    facts(c, "content.job", "Cygnus Knights advance in Ereve, Aran in Rien"));
        }
        if (branch.isEmpty()) {
            if (remembering(c)) {
                setPending(c, new Pending(Kind.JOB_ADVANCE, shape, List.of(), c.ask.nowMs(), true, false));
            }
            return reply(c, Outcome.CLARIFY, shape, Kind.JOB_ADVANCE, null, null, line(c, "job.ask", Map.of()), facts(c));
        }
        int advancement = shape.advancement();
        if (advancement == 0) {
            advancement = jobId == 0 || chosen != null || !shape.topic().isEmpty() ? 1 : jobId % 100 == 0 ? 2 : jobId % 10 == 0 ? 3 : 4;
            if (jobId % 10 >= 2 && jobId < 1000 && chosen == null && shape.topic().isEmpty()) {
                return reply(c, Outcome.ANSWERED, shape, Kind.JOB_ADVANCE, null, null, line(c, "job.maxed", Map.of()), facts(c));
            }
        }
        AliasCatalog.JobBranch job = branch.get();
        int npc = advancement <= 2 ? job.first() : advancement == 3 ? job.third() : job.fourth();
        int required = advancement == 1 && job.branch().equals("magician") ? MAGICIAN_FIRST_JOB : JOB_LEVELS[Math.min(4, advancement)];
        int map = nearestMap(c, c.k.npcMaps(npc));
        Entity instructor = c.k.names().entity(EntityKind.NPC, npc).orElseThrow();
        c.subject = instructor;
        Map<String, String> vars = Map.of("npc", instructor.say(), "town", townSay(c, map), "spot", spot(c, map),
                "req", String.valueOf(required));
        String text = c.ask.asker().level() < required ? line(c, "job.where.level", vars) : line(c, "job.where", vars);
        Map<String, String> facts = facts(c, "content.job", job.branch() + " advancement " + advancement + ": " + instructor.say()
                + " in " + placeFact(c, map) + ", from level " + required);
        ContentAnswer limited = limits(c, shape, Kind.JOB_ADVANCE, instructor);
        return limited != null ? limited : reply(c, Outcome.ANSWERED, shape, Kind.JOB_ADVANCE, null, instructor, text, facts);
    }

    private Optional<AliasCatalog.JobBranch> jobIn(Context c, String normalized) {
        String[] tokens = normalized.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            if (i + 1 < tokens.length) {
                Optional<AliasCatalog.JobBranch> two = c.k.names().jobWord(tokens[i] + " " + tokens[i + 1]);
                if (two.isPresent()) {
                    return two;
                }
            }
            Optional<AliasCatalog.JobBranch> one = c.k.names().jobWord(tokens[i]);
            if (one.isPresent()) {
                return one;
            }
        }
        return Optional.empty();
    }

    private ContentAnswer whereAmI(Context c, Shape shape) {
        int map = c.ask.asker().mapId();
        if (c.k.map(map).isEmpty()) {
            return ContentAnswer.silent(shape);
        }
        String town = townSay(c, map);
        return reply(c, Outcome.ANSWERED, shape, Kind.WHERE_AM_I, null, null, line(c, "where.am.i",
                Map.of("map", mapSay(c, map), "town", town.equals(mapSay(c, map)) ? "" : town)), // never "henesys, near henesys"
                facts(c, "content.where", "the asker is on " + placeFact(c, map)));
    }

    private ContentAnswer mapDrops(Context c, Shape shape) {
        MapFact map = c.k.map(c.ask.asker().mapId()).orElse(null);
        if (map == null || map.mobs().isEmpty()) {
            return reply(c, Outcome.ANSWERED, shape, Kind.MAP_DROPS, null, null, line(c, "map.drops.none", Map.of()), facts(c));
        }
        Entity mob = c.k.names().entity(EntityKind.MOB, mainMob(map)).orElseThrow();
        c.subject = mob;
        if (!knows(c, mob)) {
            return reply(c, Outcome.DEFLECTED, shape, Kind.MOB_DROPS, null, mob, line(c, deflectKey(c, mob, Kind.MOB_DROPS), Map.of()), facts(c));
        }
        Composed composed = mobDrops(c, mob);
        return reply(c, composed.outcome(), shape, Kind.MOB_DROPS, null, mob, composed.reply(), composed.facts());
    }

    private ContentAnswer mapGood(Context c, Shape shape) {
        MapFact map = c.k.map(c.ask.asker().mapId()).orElse(null);
        if (map == null) {
            return ContentAnswer.silent(shape);
        }
        int level = c.ask.asker().level();
        Map<String, String> facts = facts(c, "content.training", mapSay(c, map.id()) + ": monsters around level " + map.mobLevel()
                + ", the asker is level " + level);
        String text;
        if (map.town() || map.mobLevel() == 0) {
            text = line(c, "map.town", Map.of());
        } else if (map.mobLevel() < level - TRAIN_BELOW) {
            List<Integer> better = personalSpots(c, level);
            if (better == null) {
                better = c.k.trainingMaps(level, map.region(), 1);
            }
            text = line(c, "map.low", Map.of("better", better.isEmpty() ? "" : mapSay(c, better.get(0))));
        } else if (map.mobLevel() > level + TRAIN_ABOVE) {
            text = line(c, "map.high", Map.of());
        } else {
            Map<String, String> vars = mobVars(c.k.mob(mainMob(map)).orElse(null), mainMobSay(c, map.id()));
            text = line(c, "map.good", vars);
        }
        return reply(c, Outcome.ANSWERED, shape, Kind.MAP_GOOD, null, null, text, facts);
    }

    private ContentAnswer rules(Context c, Shape shape) {
        Map<String, String> facts = facts(c);
        boolean vague = known(c) != null && !veteran(c); // rate numbers are a veteran's talk (plan section 2)
        ServerRules.facts(c.rates, !vague).forEach((key, value) -> put(facts, key, value));
        String text = switch (shape.topic()) {
            case "rates" -> vague ? line(c, "rules.rates.vague" + rateAsked(shape.slot()), Map.of())
                    : line(c, "rules.rates" + rateAsked(shape.slot()), Map.of("x", String.valueOf(c.rates.exp()),
                    "x30", String.valueOf(Math.min(c.rates.exp(), EXP_CAP)), "d", String.valueOf(c.rates.drop()),
                    "m", String.valueOf(c.rates.meso()), "b", String.valueOf(c.rates.bossDrop())));
            case "gear" -> aboutGear(c, shape.slot()) ? line(c, "rules.gear", Map.of()) : null;
            case "pets" -> line(c, "rules.pets", Map.of());
            case "slots" -> line(c, "rules.slots", Map.of());
            default -> null;
        };
        return text == null ? ContentAnswer.silent(shape) : reply(c, Outcome.ANSWERED, shape, Kind.RULES, null, null, text, facts);
    }

    /** "whats the meso rate": the rate asked about (".exp", ".meso", ".drop", ".boss"); "" for all of them. */
    private static String rateAsked(String slot) {
        String asked = slot == null ? "" : slot.trim();
        if (asked.startsWith("boss")) {
            return ".boss";
        }
        return switch (asked) {
            case "exp", "xp" -> ".exp";
            case "meso", "mesos" -> ".meso";
            case "drop", "drops" -> ".drop";
            default -> "";
        };
    }

    /**
     * "can a warrior wear robes" is the gear rule; "can i use haste" or "can i use the elevator" is not: the thing
     * asked about must be gear (a gear word, or a name that resolves to an equip).
     */
    private static boolean aboutGear(Context c, String slot) {
        String thing = slot == null ? "" : NameIndex.normalize(slot);
        if (thing.isEmpty() || GEAR_WORDS.contains(thing)) {
            return true; // "why is my gear red", "can i wear anything"
        }
        NameIndex.Resolution item = c.k.names().resolveSlot(thing, ITEMS);
        return item.resolved() && c.k.item(item.best().orElseThrow().entity().id()).map(ItemFact::equip).orElse(false);
    }

    /** "is pig beach good exp": facts for the model about what the line names, no deterministic reply. */
    private ContentAnswer opinion(Context c, Shape shape) {
        Map<String, String> facts = facts(c);
        for (String part : shape.from().isEmpty() ? List.of(shape.slot()) : List.of(shape.slot(), shape.from())) {
            NameIndex.Resolution resolution = c.k.names().resolveSlot(part, ANY);
            if (!resolution.resolved()) {
                continue;
            }
            Entity entity = resolution.best().get().entity();
            if (!knows(c, entity)) {
                continue;
            }
            int level = levelOf(c, entity);
            put(facts, "content.about." + entity.say().replace(' ', '_'), entity.say() + ": " + entity.kind().name().toLowerCase(Locale.ROOT)
                    + (level > 0 ? ", level " + level : "") + (entity.kind() == EntityKind.MAP || entity.kind() == EntityKind.REGION
                    ? ", " + placeFact(c, nearestMap(c, entity.ids())) : ""));
        }
        return facts.size() <= 1 ? ContentAnswer.silent(shape)
                : new ContentAnswer(Outcome.FACTS_ONLY, Kind.OPINION, shape, null, null, null, facts);
    }

    // ---- knowledge helpers ------------------------------------------------------------------------

    private boolean knows(Context c, Entity e) {
        return grade(c, e, Kind.OPINION) == KnowledgeHorizon.Grade.FULL;
    }

    /**
     * How well the bot knows an entity. Places take their own level, or (towns, NPCs, shops: no monsters) the level
     * their region is known for, so a level-8 in Henesys does not know the way to Leafre; the hub towns everyone
     * hears about (Orbis, El Nath, Ludibrium, the FM) and the bot's home region are always known; bosses and PQs are
     * famous, known by name and rough place at any level.
     *
     * <p>With a known world (plan 7.3) what it lived and heard decides instead; a companion (unknown past) never knows less
     * than the level horizon, levels are talk everyone hears, and a merchant knows what anything goes for.
     */
    private KnowledgeHorizon.Grade grade(Context c, Entity e, Kind kind) {
        Responder bot = c.ask.bot();
        if (bot.ownRefs() != null && bot.ownRefs().contains(e.key())) {
            return KnowledgeHorizon.Grade.FULL;
        }
        int map = placeOf(c, e);
        int level = levelOf(c, e);
        boolean townKnown = false;
        String region = null;
        if (map != 0) {
            MapFact fact = c.k.map(map).orElse(null);
            int town = c.k.townOf(map);
            townKnown = inFreeMarket(map) || HUB_TOWNS.contains(town == 0 ? map : town);
            region = fact == null ? null : fact.region();
            if (level == 0) {
                level = c.k.regionLevel(region);
            }
        }
        boolean famous = e.kind() == EntityKind.PQ || e.kind() == EntityKind.MOB && c.k.mob(e.id()).map(MobFact::boss).orElse(false);
        KnowledgeHorizon.Grade prior = horizon.grade(knower(c), level, region, townKnown, false, famous);
        KnownWorld w = bot.known();
        boolean carried = e.kind() == EntityKind.ITEM && e.id() == TELE_ROCK; // every character gets one at level 10
        KnowledgeHorizon.Grade lived = w == null ? null : carried ? KnowledgeHorizon.Grade.FULL
                : PersonalHorizon.grade(c.k, w, e, townKnown, famous, c.rates);
        if (lived == null) {
            return prior;
        }
        if (w.past() == KnownWorld.Past.UNKNOWN || kind == Kind.LEVEL_OF) {
            return lived.ordinal() < prior.ordinal() ? lived : prior;
        }
        return kind == Kind.PRICE && w.prices() ? KnowledgeHorizon.Grade.FULL : lived;
    }

    /** Where an entity is for the horizon: a place's first map, an NPC's (or role's nearest NPC's) nearest map; 0 for none. */
    private static int placeOf(Context c, Entity e) {
        return switch (e.kind()) {
            case MAP, REGION, PQ -> e.ids().getInt(0);
            case NPC -> nearestMap(c, c.k.npcMaps(e.id()));
            case ROLE -> nearestMap(c, c.k.npcMaps(nearestNpc(c, e.ids())));
            default -> 0;
        };
    }

    // ---- personal knowledge (plan 7.3); every helper is the level horizon's rule without a known world -------------

    private static KnownWorld known(Context c) {
        return c.ask.bot().known();
    }

    private static boolean synthesized(Context c) {
        KnownWorld w = known(c);
        return w != null && w.past() == KnownWorld.Past.SYNTHESIZED;
    }

    private static boolean veteran(Context c) {
        KnownWorld w = known(c);
        return w != null && w.veteran();
    }

    private static boolean heardOnly(Context c, Entity e) {
        KnownWorld w = known(c);
        return w != null && w.heardOnly(e.kind(), e.id());
    }

    /** A drop row the bot vouches for: seen to drop or both known (an unknown past also by the level reach); else by reach. */
    private boolean sourceKnown(Context c, Source s) {
        KnownWorld w = known(c);
        boolean reach = horizon.withinReach(knower(c), s.mob().level());
        return w == null ? reach : PersonalHorizon.sourceKnown(c.k, w, s.drop(), s.chance()) || ownRef(c, s.drop())
                || w.past() == KnownWorld.Past.UNKNOWN && reach;
    }

    /** A drop row of a mob or item a resident's own quests and arcs are about: it knows those (plan 7.3, "FULL first"). */
    private static boolean ownRef(Context c, DropSource drop) {
        Set<Long> refs = c.ask.bot().ownRefs();
        return refs != null && !refs.isEmpty() && (refs.contains(Entity.simple(EntityKind.MOB, drop.mobId(), "", "").key())
                || refs.contains(Entity.simple(EntityKind.ITEM, drop.itemId(), "", "").key()));
    }

    /**
     * The maps among these the bot knows, when it knows any; else all of them. Always an {@link IntList}, as every map
     * list here is, so the hot loops over them stay monomorphic.
     */
    private static List<Integer> knownFirst(Context c, List<Integer> maps) {
        KnownWorld w = known(c);
        if (w == null) {
            return maps;
        }
        List<Integer> out = new ArrayList<>();
        for (int map : maps) {
            if (PersonalHorizon.placeKnown(c.k, w, map)) {
                out.add(map);
            }
        }
        return out.isEmpty() ? maps : IntList.copyOf(out);
    }

    /** {@link #knownFirst} for where a mob spawns, except that a synthesized past names no spawn it never saw (empty). */
    private static List<Integer> knownSpawns(Context c, List<Integer> maps) {
        if (!synthesized(c)) {
            return knownFirst(c, maps);
        }
        List<Integer> out = new ArrayList<>();
        for (int map : maps) {
            if (PersonalHorizon.placeKnown(c.k, known(c), map)) {
                out.add(map);
            }
        }
        return IntList.copyOf(out);
    }

    /** A role's NPCs that stand somewhere the bot knows, when there are any (a synthesized past); else all of them. */
    private static List<Integer> knownNpcs(Context c, List<Integer> npcs) {
        if (!synthesized(c)) {
            return npcs;
        }
        List<Integer> out = new ArrayList<>();
        for (int npc : npcs) {
            for (int map : c.k.npcMaps(npc)) {
                if (PersonalHorizon.placeKnown(c.k, known(c), map)) {
                    out.add(npc);
                    break;
                }
            }
        }
        return out.isEmpty() ? npcs : IntList.copyOf(out);
    }

    /**
     * Training candidates from what the bot lived (plan 7.3): its spots and the known fields of the level band, best first
     * by the usual ranking later. Null without a known world, or for an unknown past without a fitting spot of its own
     * (the level horizon answers then).
     */
    private List<Integer> personalSpots(Context c, int level) {
        KnownWorld w = known(c);
        if (w == null) {
            return null;
        }
        Set<Integer> out = new java.util.LinkedHashSet<>();
        for (int map : w.spots()) {
            if (trainingField(c, map, level)) {
                out.add(map);
            }
        }
        if (out.isEmpty() && w.past() == KnownWorld.Past.UNKNOWN) {
            return null;
        }
        for (int map : c.k.trainingMaps(level, null, PERSONAL_CANDIDATES)) {
            if (trainingField(c, map, level) && PersonalHorizon.placeKnown(c.k, w, map)) {
                out.add(map);
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean trainingField(Context c, int map, int level) {
        MapFact fact = c.k.map(map).orElse(null);
        return fact != null && !fact.town() && fact.forcedReturn() == 0 && fact.mobLevel() > 0
                && fact.mobLevel() >= level - TRAIN_BELOW && fact.mobLevel() <= level + TRAIN_ABOVE;
    }

    /**
     * "ask someone higher" only of what is past the bot's level; a thing it just never met is "no clue tbh" (no level
     * claim, which would be false for a low-level unknown). A price it does not know is "no idea what those go for". An
     * unknown past deflects only where the level horizon does, so it keeps the horizon's words.
     */
    private String deflectKey(Context c, Entity subject, Kind kind) {
        if (!synthesized(c)) {
            return "deflect";
        }
        if (kind == Kind.PRICE) {
            return "price.unknown";
        }
        // "never been there" only of a place or the way to one; an item or a mob is never seen or never fought
        String of = kind == Kind.WHERE_IS || kind == Kind.ROUTE ? "" : subject.kind() == EntityKind.ITEM ? ".item"
                : subject.kind() == EntityKind.MOB ? ".mob" : "";
        return (deflectLevel(c, subject) > c.ask.bot().level() ? "deflect" : "deflect.unseen") + of;
    }

    /** The level a thing is known for: an item's lowest meetable dropper (else its reqLevel), a place's monsters or region. */
    private int deflectLevel(Context c, Entity subject) {
        if (subject.kind() == EntityKind.ITEM) {
            int lowest = 0;
            for (DropSource drop : c.k.dropSources(subject.id())) {
                MobFact mob = drop.global() ? null : c.k.mob(drop.mobId()).orElse(null);
                if (meetable(c, drop, mob) && mob.level() > 0 && (lowest == 0 || mob.level() < lowest)) {
                    lowest = mob.level();
                }
            }
            if (lowest > 0) {
                return lowest;
            }
        }
        int level = levelOf(c, subject);
        int map = placeOf(c, subject);
        return level == 0 && map != 0 ? c.k.regionLevel(c.k.map(map).map(MapFact::region).orElse(null)) : level;
    }

    /** A dropper players can meet: placed, spawned by a script or an NPC, or a boss; not an event or PQ copy without a place. */
    private static boolean meetable(Context c, DropSource drop, MobFact mob) {
        return mob != null && !(drop.maps().isEmpty() && drop.viaNpc() == 0 && (mob.id() >= EVENT_MOB_ID
                || c.k.scriptSpawns(mob.id()).isEmpty() && !mob.boss()));
    }

    private int levelOf(Context c, Entity e) {
        return switch (e.kind()) {
            case MOB -> c.k.mob(e.id()).map(MobFact::level).orElse(0);
            case ITEM -> c.k.item(e.id()).map(ItemFact::reqLevel).orElse(0);
            case QUEST -> c.k.quest(e.id()).map(QuestFact::minLevel).orElse(0);
            case PQ -> e.minLevel();
            case MAP, REGION -> {
                int min = 0;
                for (int map : e.ids()) {
                    int level = c.k.map(map).map(MapFact::mobLevel).orElse(0);
                    min = level > 0 && (min == 0 || level < min) ? level : min;
                }
                yield min;
            }
            default -> 0;
        };
    }

    private static KnowledgeHorizon.Knower knower(Context c) {
        return new KnowledgeHorizon.Knower(c.ask.bot().level(), c.ask.bot().homeRegion());
    }

    private static int targetMap(Context c, Entity subject) {
        return switch (subject.kind()) {
            case MAP, REGION -> nearestMap(c, subject.ids());
            case PQ -> subject.ids().getInt(0);
            case NPC -> nearestMap(c, c.k.npcMaps(subject.id()));
            case ROLE -> nearestMap(c, c.k.npcMaps(nearestNpc(c, subject.ids())));
            case MOB -> {
                List<Integer> maps = knownSpawns(c, c.k.spawnMaps(subject.id()));
                yield maps.isEmpty() ? 0 : bestSpawn(c, subject.id(), maps);
            }
            default -> 0;
        };
    }

    /** The nearest map by route cost from the asker (lowest id when none is reachable); 0 for none. */
    private static int nearestMap(Context c, List<Integer> maps) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        int from = c.ask.asker().mapId();
        for (int map : maps) {
            int distance = c.k.distance(from, map);
            if (best == 0 || distance < bestDistance) {
                best = map;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int nearestNpc(Context c, List<Integer> npcs) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int npc : npcs) {
            int map = nearestMap(c, c.k.npcMaps(npc));
            int distance = map == 0 ? Integer.MAX_VALUE : c.k.distance(c.ask.asker().mapId(), map);
            if (best == 0 || distance < bestDistance) {
                best = npc;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** A mob's best spawn for the asker: open field maps first, then the nearest. */
    private static int bestSpawn(Context c, int mobId, List<Integer> maps) {
        int best = 0;
        long bestScore = Long.MAX_VALUE;
        for (int map : maps) {
            MapFact fact = c.k.map(map).orElse(null);
            if (fact == null) {
                continue;
            }
            long penalty = fact.region() == null || fact.forcedReturn() != 0 ? 1_000_000L : 0L;
            long score = penalty + Math.min(999_999L, c.k.distance(c.ask.asker().mapId(), map)) - (c.k.fieldCapacity(map) > 0 ? 5 : 0)
                    + (HIDDEN_STREET.equals(fact.street()) ? HIDDEN_PENALTY : 0);
            if (score < bestScore) {
                best = map;
                bestScore = score;
            }
        }
        return best == 0 ? maps.get(0) : best;
    }

    static int mainMob(MapFact map) {
        int best = map.mobs().getInt(0);
        int bestCount = -1;
        for (int i = 0; i < map.mobs().size(); i++) {
            if (map.mobCounts().getInt(i) > bestCount) {
                best = map.mobs().getInt(i);
                bestCount = map.mobCounts().getInt(i);
            }
        }
        return best;
    }

    private static String mainMobSay(Context c, int mapId) {
        MapFact map = c.k.map(mapId).orElse(null);
        return map == null || map.mobs().isEmpty() ? "" : c.k.names().say(EntityKind.MOB, mainMob(map));
    }

    private static int townOrSelf(Context c, int map) {
        int town = c.k.townOf(map);
        return town == 0 ? map : town;
    }

    private static String mapSay(Context c, int map) {
        return c.k.names().say(EntityKind.MAP, map);
    }

    private static String townSay(Context c, int map) {
        return mapSay(c, townOrSelf(c, map));
    }

    private static boolean hidden(Context c, int map) {
        return c.k.map(map).map(fact -> HIDDEN_STREET.equals(fact.street())).orElse(false);
    }

    /**
     * A map as a reply names it: a Hidden Street map gets its town ("breathing rock by sleepywood"), since nobody
     * finds "someone elses house" from its name alone.
     */
    private static String placeSay(Context c, int map) {
        String say = mapSay(c, map);
        int town = c.k.townOf(map);
        return hidden(c, map) && town != 0 && town != map && !say.isEmpty() ? say + " by " + mapSay(c, town) : say;
    }

    /** The interior a map is in its town ("department store", "bowman school"); "" for the town square itself. */
    private static String spot(Context c, int map) {
        int town = c.k.townOf(map);
        if (map == 0 || town == map) {
            return "";
        }
        String say = mapSay(c, map);
        for (String townName : town == 0 ? List.<String>of() : List.of(c.k.names().wzSay(town), mapSay(c, town))) {
            if (!townName.isEmpty() && say.startsWith(townName + " ")) {
                String rest = say.substring(townName.length() + 1);
                return rest.indexOf(' ') > 0 ? rest : say; // "henesys department store" -> "department store"; "henesys park" stays
            }
        }
        return say;
    }

    /**
     * Where a ride lets you off, as a town ("subway to nlc", not "to nlc subway station"): the arrival's own town,
     * which is the destination's only when the ride really ends there (the Orbis boat lands in Ellinia, not Henesys).
     */
    private static String arrivalSay(Context c, int arrival, int destination) {
        int town = c.k.townOf(arrival);
        if (town == 0) {
            String region = c.k.map(arrival).map(MapFact::region).orElse(null); // a station outside any town
            town = region != null && region.equals(c.k.map(destination).map(MapFact::region).orElse(null)) ? c.k.townOf(destination) : 0;
        }
        return mapSay(c, town == 0 ? arrival : town);
    }

    /** Where a ride boards: its authored name ("ellinia station"), or the town when that name repeats the ride ("kerning subway"). */
    private static String boardSay(Context c, int map, String ride) {
        String say = mapSay(c, map);
        return c.k.names().aliased(EntityKind.MAP, map) && (ride.isEmpty() || !say.contains(ride)) ? say : townSay(c, map);
    }

    private static String placeFact(Context c, int map) {
        String spot = spot(c, map);
        return (spot.isEmpty() ? "" : spot + ", ") + townSay(c, map);
    }

    /** The first map, then the other open-field maps nearest first, up to three names. */
    private static String placesFact(Context c, List<Integer> maps, int first) {
        StringBuilder out = new StringBuilder(mapSay(c, first));
        int listed = 1;
        List<Integer> nearest = new ArrayList<>(maps);
        nearest.sort(Comparator.comparingInt(map -> c.k.distance(c.ask.asker().mapId(), map)));
        for (int map : nearest) {
            if (map != first && listed < FACT_SOURCES && c.k.map(map).map(MapFact::region).orElse(null) != null) {
                String say = mapSay(c, map);
                if (out.indexOf(say) < 0) {
                    out.append(", ").append(say);
                    listed++;
                }
            }
        }
        return out.toString();
    }

    private static String npcSay(Context c, int npc) {
        return c.k.names().say(EntityKind.NPC, npc);
    }

    /** {mobs} plus the verbs that agree with it: a boss is one ("zakum drops em", "mushmom is at ..."). */
    private static Map<String, String> mobVars(MobFact mob, String say) {
        Map<String, String> vars = new HashMap<>();
        boolean one = mob != null && mob.boss();
        vars.put("mobs", say);
        vars.put("drop", one ? "drops" : "drop");
        vars.put("r", one ? "is" : "r");
        return vars;
    }

    private static String mobSay(Context c, MobFact mob) {
        return c.k.names().say(EntityKind.MOB, mob.id());
    }

    private static String itemSay(Context c, int item) {
        return c.k.names().say(EntityKind.ITEM, item);
    }

    /** Spoken ride names: the Orbis-Ludibrium line is a train, Ariant's a genie; instant hops are not rides. */
    private static String rideSay(TravelLeg leg) {
        int from = leg.fromMap() / 10_000_000;
        int to = leg.toMap() / 10_000_000;
        return switch (leg.kind()) {
            case "ship" -> from == 22 || to == 22 ? "train" : from == 26 || to == 26 ? "genie" : "boat";
            case "guide", "elevator", "taxi" -> "";
            case "world-tour" -> "world tour";
            default -> leg.kind();
        };
    }

    private static boolean inFreeMarket(int map) {
        return map >= FM_FIRST && map <= FM_LAST;
    }

    private static boolean isCharacterName(Context c, String slot) {
        Predicate<String> names = c.ask.characterName();
        return names != null && slot.split(" ").length <= 2 && names.test(slot.trim());
    }

    // ---- scoring ----------------------------------------------------------------------------------

    /** 1 inside [asker-10, asker+15], falling off linearly over the next 20 levels on either side. */
    static double fit(int askerLevel, int mobLevel) {
        int below = askerLevel - FIT_BELOW;
        int above = askerLevel + FIT_ABOVE;
        if (mobLevel >= below && mobLevel <= above) {
            return 1d;
        }
        int off = mobLevel < below ? below - mobLevel : mobLevel - above;
        return Math.max(0.05d, 1d - off / 20d);
    }

    private static double bucketWeight(double chance) {
        return chance >= COMMON ? 1d : chance >= SOMETIMES ? 0.7d : chance >= RARE ? 0.4d : 0.2d;
    }

    private static double distanceFactor(Context c, int map) {
        int distance = map == 0 ? Integer.MAX_VALUE : c.k.distance(c.ask.asker().mapId(), map);
        return distance == Integer.MAX_VALUE ? 0.3d : 1d / (1d + distance / 20d);
    }

    static String bucket(double chance) {
        return chance >= COMMON ? "common" : chance >= SOMETIMES ? "sometimes" : chance >= RARE ? "rare" : "super rare";
    }

    /**
     * {bucket}; a boss's drop is counted in runs ("takes a few runs"), not in how fast a farm fills up. A veteran quotes
     * the chance itself ("like 0.6%") on about half its answers, never below a hundredth of a percent.
     */
    private String bucketPhrase(Context c, double chance, boolean boss) {
        if (veteran(c) && chance >= EXACT_MIN && c.rng.nextBoolean()) {
            String exact = ReplyBank.line(boss ? "bucket.exact.boss" : "bucket.exact", c.voice, c.rng, maxChars,
                    Map.of("pct", percent(chance)));
            if (exact != null) {
                return exact;
            }
        }
        String key = chance >= COMMON ? "bucket.common" : chance >= SOMETIMES ? "bucket.sometimes"
                : chance >= RARE ? "bucket.rare" : "bucket.superrare";
        return ReplyBank.phrase(boss ? key + ".boss" : key, c.voice, c.rng);
    }

    /** A chance per kill as a veteran says it: "3%" from 1%, "0.6%" from 0.1%, "0.05%", else "<0.01%". */
    static String percent(double chance) {
        double pct = chance * 100;
        if (pct >= 1) {
            return Math.round(pct) + "%";
        }
        if (pct >= 0.1) {
            String one = String.format(Locale.ROOT, "%.1f", pct);
            return (one.endsWith(".0") ? one.substring(0, one.length() - 2) : one) + "%";
        }
        return pct >= 0.01 ? String.format(Locale.ROOT, "%.2f", pct) + "%" : "<0.01%";
    }

    static int round5(int level) {
        return Math.max(5, (level + 2) / 5 * 5);
    }

    // ---- replies and facts ------------------------------------------------------------------------

    private String line(Context c, String key, Map<String, String> vars) {
        return done(c, key, raw(c, key, vars));
    }

    /** The template alone, before the voice's finish: for replies that add a phrase first (route.far, a tele rock hint). */
    private String raw(Context c, String key, Map<String, String> vars) {
        return ReplyBank.line(key, c.voice, c.rng, maxChars, vars);
    }

    private String done(Context c, String key, String text) {
        return text == null ? null : ReplyBank.finish(text, c.voice, c.rng, maxChars, key.startsWith("clarify")
                || key.equals("thanks") || key.equals("repeat") || key.equals("budget") ? null : c.echo);
    }

    /** Keeps a combined reply (hedge + answer, answer + tele-rock hint) to the length limit, cutting at a word. */
    private String clip(String text) {
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf(' ', maxChars);
        return text.substring(0, cut > 0 ? cut : maxChars).replaceAll("[, ]+$", "");
    }

    /**
     * CWKPQ plan C2: where the Crimsonwood Keystone comes from, Lukan's quests (no drop table or shop has it, so the
     * item answers would say nothing drops it). A bot under the quests' level has not done them: "way past me".
     */
    private Composed keystone(Context c, Entity item) {
        if (c.ask.bot().level() < KEYSTONE_QUEST_LEVEL) {
            return new Composed(Outcome.DEFLECTED, line(c, "deflect.item", Map.of()), facts(c, "content.boundary",
                    item.say() + " comes from quests far above the speaker's level; say you don't know"));
        }
        return answered(c, "get.keystone", Map.of(), "content.item_source", KEYSTONE_FACT);
    }

    private Composed answered(Context c, String key, Map<String, String> vars, String factKey, String fact) {
        return new Composed(Outcome.ANSWERED, line(c, key, vars), facts(c, factKey, fact));
    }

    private ContentAnswer reply(Context c, Outcome outcome, Shape shape, Kind kind, NameIndex.Resolution resolution,
                                Entity subject, String text, Map<String, String> facts) {
        return new ContentAnswer(outcome, kind, shape, resolution, subject, text, facts);
    }

    private static Map<String, String> facts(Context c) {
        Map<String, String> facts = new LinkedHashMap<>();
        put(facts, "content.question", c.ask.line().trim());
        return facts;
    }

    private static Map<String, String> facts(Context c, String key, String value) {
        Map<String, String> facts = facts(c);
        put(facts, key, value);
        return facts;
    }

    private static void put(Map<String, String> facts, String key, String value) {
        if (key.length() <= FACT_CHARS && value != null && !value.isBlank()) {
            facts.put(key, value.length() <= FACT_CHARS ? value : value.substring(0, FACT_CHARS));
        }
    }

    private static String echo(String slot, NameIndex.Resolution resolution) {
        if (resolution == null || slot.isEmpty() || slot.split(" ").length > 3) {
            return null;
        }
        String how = resolution.best().map(NameIndex.Candidate::how).orElse("");
        return how.equals("alias") || how.equals("exact") || how.equals("scroll") ? slot : null;
    }

    /** "blue mushroom cap" + "green mushroom cap" -> "blue or green mushroom cap"; "glove att 60" + "glove att 10" -> "glove att 60 or 10". */
    static String alternatives(String a, String b) {
        String[] x = a.split(" ");
        String[] y = b.split(" ");
        int head = 0;
        while (head < x.length - 1 && head < y.length - 1 && x[head].equals(y[head])) {
            head++;
        }
        int tail = 0;
        while (tail < x.length - head - 1 && tail < y.length - head - 1
                && x[x.length - 1 - tail].equals(y[y.length - 1 - tail])) {
            tail++;
        }
        String prefix = String.join(" ", Arrays.copyOfRange(x, 0, head));
        String suffix = String.join(" ", Arrays.copyOfRange(x, x.length - tail, x.length));
        String mx = String.join(" ", Arrays.copyOfRange(x, head, x.length - tail));
        String my = String.join(" ", Arrays.copyOfRange(y, head, y.length - tail));
        return ((prefix.isEmpty() ? "" : prefix + " ") + mx + " or " + my + (suffix.isEmpty() ? "" : " " + suffix)).trim();
    }

    private static List<EntityKind> kindsFor(Kind kind) {
        return switch (kind) {
            case DROP_SOURCE -> ITEM_OR_MOB;
            case MOB_DROPS -> MOBS;
            case BUY_WHERE, PRICE -> kind == Kind.PRICE ? ITEMS : List.of(EntityKind.ITEM, EntityKind.ROLE);
            case GET_WHERE -> GETTABLE;
            case WHERE_IS -> WHERE;
            case ROUTE -> PLACES;
            case LEVEL_OF -> LEVELLED;
            default -> ANY;
        };
    }

    private static <K, V> void trim(Map<K, V> map) {
        Iterator<K> keys = map.keySet().iterator();
        while (map.size() > 16 && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    // ---- state ------------------------------------------------------------------------------------

    /** An ask-back waiting for the asker's reply. */
    private record Pending(Kind kind, Shape shape, List<Entity> candidates, long askedAt, boolean job, boolean level) {
    }

    /** One (asker, bot) conversation; guarded by its own monitor. */
    private static final class Memory {
        long lastAt;
        Outcome lastOutcome;
        Kind lastKind;
        Entity lastSubject;
        Pending pending;
        long windowStart;
        int answersInWindow;
        boolean budgetSaid;
        int turn;
        final Map<Long, Long> answeredAt = new LinkedHashMap<>();
        final Map<Long, String> answeredReply = new LinkedHashMap<>();
        final Set<Long> repeated = new HashSet<>();
        final Set<String> askedBack = new HashSet<>();
    }

    /** Everything one answer needs; the seed is (bot, asker, line, turn) so repeats vary but tests are stable. */
    private final class Context {
        final Ask ask;
        final WorldKnowledge k;
        final Memory m;
        final Rates rates;
        final Voice voice;
        SplittableRandom rng;
        final boolean remember;
        Entity subject;
        String echo;
        boolean hedged;
        /** The subject is known only by hearsay ("heard pigs drop em"). */
        boolean heard;
        long repeatKey;
        boolean budget;

        Context(Ask ask, WorldKnowledge k, Memory m, Rates rates, Voice voice, boolean remember) {
            this.ask = ask;
            this.k = k;
            this.m = m;
            this.rates = rates;
            this.voice = voice;
            this.remember = remember;
            this.rng = seeded(ask.line().hashCode());
        }

        /** Seeded by (bot, asker, subject, turn): the same question varies turn to turn, never by retyping. */
        SplittableRandom seeded(long subjectKey) {
            return new SplittableRandom((long) ask.bot().id() * 0x9E3779B97F4A7C15L ^ (long) ask.asker().id() * 0xC2B2AE3D27D4EB4FL
                    ^ subjectKey * 0x165667B19E3779F9L ^ m.turn);
        }
    }
}
