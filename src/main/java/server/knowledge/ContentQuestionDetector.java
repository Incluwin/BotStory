package server.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which content question a chat line asks (docs/SERVER_KNOWLEDGE_PLAN.md 5.3), by regex only: nothing is
 * resolved here, so {@link #looksLikeContentQuestion} is cheap enough for every companion chat line and
 * {@link ContentAnswers} resolves the slot afterwards.
 *
 * <p>Two normalizations. The <b>frame form</b> ({@link #frame}) is for the question grammar: lowercase,
 * punctuation gone, chat spelling folded (u, r, ne1, wat, wer, lvl, shud, 2/4 between words), greetings
 * and "does anyone know" stripped from the front and "pls/ty/lol/anyone" from the end. It never
 * singularises, so "does", "was", "has" and "pls" keep their meaning. The <b>entity form</b>
 * ({@link NameIndex#normalize}) is applied only to the captured slot.
 *
 * <p>Lines that are trade calls (S&gt; B&gt; wts wtb "anyone selling"), questions about a person (you, your,
 * my, everyone), past tense ("who dropped this"), or companion commands never match.
 */
public final class ContentQuestionDetector {
    public enum Kind {
        DROP_SOURCE, MOB_DROPS, BUY_WHERE, GET_WHERE, WHERE_IS, ROUTE, TRAIN_WHERE, PRICE, LEVEL_OF, JOB_ADVANCE,
        WHERE_AM_I, MAP_DROPS, MAP_GOOD, RULES, OPINION, FOLLOW_UP, THANKS
    }

    /**
     * A question's shape before resolution.
     *
     * @param slot        the subject words as typed (frame form), "" when the kind has none
     * @param from        ROUTE: the start the asker named ("from henesys"), else ""
     * @param level       a level number in the line (TRAIN_WHERE "at 35", JOB_ADVANCE), 0 when none
     * @param advancement JOB_ADVANCE: 1-4 when named ("2nd job"), 0 when not
     * @param topic       RULES: rates, gear, pets, slots; JOB_ADVANCE: the class word after "become a"
     * @param follow      FOLLOW_UP: the kind to ask about the last subject, null for "and X" / "what about X"
     * @param weak        an unresolved slot stays silent ("nice drops" is not a mob question)
     * @param whoSells    "who sells X": a trade call in the Free Market, an NPC-shop question elsewhere
     * @param forOther    TRAIN_WHERE for someone else ("for my friend")
     * @param frame       the whole line in frame form (TRAIN_WHERE / JOB_ADVANCE look for class words in it)
     */
    public record Shape(Kind kind, String slot, String from, int level, int advancement, String topic, Kind follow,
                        boolean weak, boolean whoSells, boolean forOther, String frame) {
    }

    private record Rule(Kind kind, Pattern pattern, boolean weak, Kind follow) {
    }

    private static final Pattern MARKET = Pattern.compile(
            "^\\s*(?:s|b|t|r|wts|wtb|wtt|lf|lfm|lfg|j)\\s*>|^\\s*(?:wts|wtb|wtt|selling|buying|trading)\\b"
                    + "|^\\s*(?:anyone|any1|ne1|anybody|someone|who|whos|who is)\\s+(?:selling|buying|trading|got|has|have)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAD = Pattern.compile("^(?:(?:hey|hi|hello|hiya|heya|yo+|um+|uh+|so|ok|okay|oh|ah|guys"
            + "|quick q|quick question|question|noob question(?: but)?|random question|dumb question|stupid question|sorry|sry|wtf"
            + "|help|please|oi|erm|hm+|btw|lol|also|well|alright|kk|k|sup|excuse me|i have a question|can i ask|wait"
            + "|i was wondering|i wonder|wondering|i want to know|i wanna know|tell me|do you guys know"
            + "|(?:(?:does|do|can|could|would) )?(?:anyone|someone|anybody|somebody|you|any of you|yall)(?: here)?"
            + " (?:know|knows|tell me|remember)(?: if)?)(?: |$))+");
    private static final Pattern TRAIL = Pattern.compile("(?: (?:please|ty|thanks|thank you|lol|anyone|guys|tho|though|xd|haha"
            + "|again|man|bro|dude|lmao|thx|ok|kk|someone|anybody|or what|real quick|quick|lmk|asap|plz))+$");
    private static final Pattern GLUED_LEVEL = Pattern.compile("^(?:lvl|lv|level)(\\d{1,3})$");
    private static final Pattern LEVEL_NUMBER = Pattern.compile("(?<![\\d%])(\\d{1,3})(?![\\d%]|k\\b|m\\b|mil)");
    private static final Pattern ADVANCEMENT = Pattern.compile("\\b(1st|2nd|3rd|4th|first|second|third|fourth)(?: job)?\\b");
    private static final Pattern JOB_CUE = Pattern.compile("\\b(?:job ?adv\\w*|job advancement|advancement|advance|job change"
            + "|change jobs?|job instructor|job npc|job guy|(?:1st|2nd|3rd|4th|first|second|third|fourth) job"
            + "|become an? (?<job>[a-z]+(?: [a-z]+)?))\\b");
    private static final Pattern JOB_START = Pattern.compile("^(?:how|where|who|when|what level|which|is there|can i|should i|whats|what is)\\b");
    private static final Pattern TRAIN_WORD = Pattern.compile("\\b(?:train|training|level|leveling|levelling|grind|grinding|hunt|hunting|exp|xp)\\b");
    private static final Pattern PLACE_WORD = Pattern.compile("\\b(?:where|spot|spots|place|places|map|maps|area|areas)\\b"
            + "|^what (?:should|do|can) (?:i|you) (?:train|hunt|grind|level)\\b");
    /** "where can i hunt pigs" / "where to farm steelies" name a target: a WHERE_IS / DROP_SOURCE, not a training question. */
    private static final Pattern HUNT_TARGET = Pattern.compile("\\b(?:hunt|hunting|kill|killing|farm|farming)"
            + " (?!(?:at|for|in|on|around|as|now|rn|solo|alone|with|exp|xp|level|\\d{1,3})\\b)[a-z]");
    /** "where is the potion shop" is a WHERE_IS; "where is a good spot to train" is still a training question. */
    private static final Pattern WHERE_IS_THING = Pattern.compile("^where (?:is|are) (?!(?:a |an |the |some )?(?:good|best|decent|nice) )");
    private static final Pattern TRAIN_START = Pattern.compile("^(?:where|what|which|any|anyone|best|good|lf|looking for|recommend|suggest"
            + "|is there|should|ideas|need|know|decent|nice)\\b");
    /**
     * "nice exp map", "best place to train is ant tunnel": these openers make a statement unless something asks
     * (a "?", a level, "any" / "where"); with "is" or "here" in the line it is a statement about a place.
     */
    private static final Pattern STATEMENT_START = Pattern.compile("^(?:best|good|nice|decent)\\b");
    private static final Pattern STATEMENT_ASKS = Pattern.compile("\\b(?:any|where|\\d{1,3})\\b");
    private static final Pattern STATEMENT_SAYS = Pattern.compile("\\b(?:is|are|was|here)\\b");
    private static final Pattern ABOUT_THEM = Pattern.compile("\\b(?:you guys|yall|you all|are you|were you|did you|your|our)\\b");
    private static final Pattern FOR_OTHER = Pattern.compile("\\b(?:for )?(?:my|a) (?:friend|bro|brother|sister|sis|buddy|gf|bf|son|kid"
            + "|guildie|mate)\\b");
    /** A route "from here" starts at the asker's map. */
    private static final Set<String> HERE = Set.of("here", "this town", "this map", "this place", "where i am");
    private static final Pattern TRAILING_SLOT = Pattern.compile("(?: (?:at|located|located at|anywhere|around here|in town|here"
            + "|in this game|on this server|in victoria|npc|guy|person|now|these days|nowadays|usually|normally|from))+$");
    private static final Set<String> WERE_NEXT = Set.of("do", "is", "are", "can", "to", "should", "i", "does", "would", "could");
    private static final Set<String> UNITS = Set.of("att", "matt", "def", "dex", "int", "luk", "str", "hp", "mp", "level",
            "lv", "lvl", "jump", "speed", "acc", "g", "glove", "helm", "percent", "slots", "slot", "stars", "set", "sets");
    private static final Set<String> PRONOUNS = Set.of("you", "u", "yall", "everyone", "everybody", "it", "this", "that",
            "these", "those", "we", "us", "them", "they", "there", "here", "now", "me", "him", "her", "someone", "somebody",
            "anyone", "anybody", "people", "guys", "my", "your", "our", "his", "their", "its", "mine", "yours", "i", "im",
            "what", "which", "who", "he", "she", "next", "to");
    private static final Set<String> POSSESSIVE = Set.of("my", "your", "our", "his", "her", "their", "mine", "yours", "you");
    /** Slot openings that are never a thing: "how do i get out of the fm", "where is the rest of the party". */
    private static final Pattern SILENT_START = Pattern.compile("^(?:out of|rid of|rest of|other side|from)(?: |$)");
    /** Slots that are about the asker, not a thing: "how do i get out", "how do i get mesos", "how much exp". */
    private static final Set<String> SILENT = Set.of("out", "up", "down", "top", "the top", "bottom", "back", "home", "inside",
            "in", "on", "off", "started", "better", "good", "stronger", "strong", "rich", "money", "mesos", "meso", "exp",
            "xp", "nx", "fame", "level", "lvl", "job", "a job", "more", "some", "one", "any", "anything", "something",
            "everything", "nothing", "ap", "sp", "hp", "mp", "dmg", "damage", "str", "dex", "int", "luk", "longer", "much",
            "many", "far", "fast", "high", "buffed", "carried", "help", "a pt", "a party", "party", "pt", "guild", "friends",
            "gm", "married", "a guild", "range", "stats", "skills", "points", "rid of it", "rid of them", "there", "lost",
            "that", "it", "this", "them", "those", "these", "free stuff");
    private static final Map<String, String> FOLD = Map.ofEntries(Map.entry("u", "you"), Map.entry("ya", "you"),
            Map.entry("yu", "you"), Map.entry("r", "are"), Map.entry("ur", "your"), Map.entry("ne1", "anyone"),
            Map.entry("any1", "anyone"), Map.entry("sum1", "someone"), Map.entry("some1", "someone"),
            Map.entry("wat", "what"), Map.entry("wut", "what"), Map.entry("wht", "what"), Map.entry("wot", "what"),
            Map.entry("whut", "what"), Map.entry("whats", "what is"), Map.entry("wats", "what is"), Map.entry("wuts", "what is"),
            Map.entry("whatre", "what are"), Map.entry("wer", "where"), Map.entry("wher", "where"), Map.entry("whr", "where"),
            Map.entry("whre", "where"), Map.entry("wehre", "where"), Map.entry("wheree", "where"), Map.entry("wheres", "where is"),
            Map.entry("wers", "where is"), Map.entry("whers", "where is"), Map.entry("hw", "how"), Map.entry("hows", "how is"),
            Map.entry("whos", "who is"), Map.entry("lvl", "level"), Map.entry("lv", "level"), Map.entry("lvls", "levels"),
            Map.entry("lvling", "leveling"), Map.entry("lving", "leveling"), Map.entry("lvlin", "leveling"),
            Map.entry("lvlng", "leveling"), Map.entry("shud", "should"), Map.entry("shld", "should"), Map.entry("shoud", "should"),
            Map.entry("cud", "could"), Map.entry("cld", "could"), Map.entry("wud", "would"), Map.entry("doe", "does"),
            Map.entry("dose", "does"), Map.entry("plz", "please"), Map.entry("pls", "please"), Map.entry("plox", "please"),
            Map.entry("plzz", "please"), Map.entry("thnx", "thanks"), Map.entry("thnks", "thanks"), Map.entry("tnx", "thanks"),
            Map.entry("ppl", "people"), Map.entry("kno", "know"), Map.entry("knw", "know"), Map.entry("wich", "which"),
            Map.entry("whitch", "which"), Map.entry("wen", "when"), Map.entry("abt", "about"), Map.entry("prolly", "probably"),
            Map.entry("whered", "where did"), Map.entry("wru", "where are you"), Map.entry("wya", "where are you"),
            Map.entry("im", "i am"), Map.entry("teh", "the"), Map.entry("da", "the"), Map.entry("gonna", "going to"),
            Map.entry("wanna", "want to"), Map.entry("gotta", "got to"), Map.entry("hav", "have"), Map.entry("giv", "give"),
            Map.entry("bout", "about"), Map.entry("cn", "can"), Map.entry("cna", "can"), Map.entry("ccan", "can"));
    /** "X drops" with these in front is loot talk ("leave the drops", "nice drops"), not a mob's name. */
    private static final Set<String> LOOT_TALK = Set.of("leave", "grab", "pick", "get", "give", "keep", "take", "loot",
            "nice", "good", "rare", "bad", "no", "any", "more", "sweet", "great", "cool", "best", "all", "new", "free",
            "the", "dont", "stop", "ignore", "only", "check", "see", "boss", "my", "your", "his", "her", "those", "these");
    private static final Set<String> THANKS_WORDS = Set.of("ty", "thanks", "thank", "thx", "tysm", "tyvm", "tyty", "tyy");
    private static final Set<String> THANKS_FILL = Set.of("you", "so", "much", "man", "bro", "dude", "ok", "kk", "ah", "oh",
            "a", "lot", "alot", "very", "lol", "mate", "again", "sm", "for", "the", "help", "info", "please", "k");

    private static final Map<String, List<Rule>> RULES_BY_FIRST = new HashMap<>();
    private static final List<Rule> ANY_FIRST = new ArrayList<>();

    static {
        // follow-ups that only mean something right after an answer (pronoun subjects)
        rule(Kind.FOLLOW_UP, "^where (?:is|are) (?:that|it|those|they|that place|that map|there)$", false, Kind.WHERE_IS);
        rule(Kind.FOLLOW_UP, "^where (?:do|does) (?:they|those|it) (?:spawn|live|drop)$", false, Kind.WHERE_IS);
        rule(Kind.FOLLOW_UP, "^how (?:do|can|would) (?:i|we|you) (?:get|go) (?:there|to it|to that|to there|to that place|to them)$", false, Kind.ROUTE);
        rule(Kind.FOLLOW_UP, "^how (?:to|do i) get there$", false, Kind.ROUTE);
        rule(Kind.FOLLOW_UP, "^which way is (?:it|that)$", false, Kind.ROUTE);
        rule(Kind.FOLLOW_UP, "^what (?:level|lvl) (?:are|is) (?:they|it|that|those|them|these)$", false, Kind.LEVEL_OF);
        rule(Kind.FOLLOW_UP, "^(?:who|what) drops (?:it|that|those|them|these)$", false, Kind.DROP_SOURCE);
        rule(Kind.FOLLOW_UP, "^what (?:do|does) (?:they|those|it|that) drop$", false, Kind.MOB_DROPS);
        rule(Kind.FOLLOW_UP, "^how much (?:is|are) (?:it|that|those|they|them|these)(?: going for)?$", false, Kind.PRICE);
        rule(Kind.FOLLOW_UP, "^where (?:can|do) i (?:buy|get) (?:it|them|those|that|one|some)$", false, Kind.GET_WHERE);
        // deixis: the asker's own map
        rule(Kind.WHERE_AM_I, "^(?:where am i|where are we|what map is this|what map am i (?:in|on)|what is this (?:map|place)"
                + "|what place is this|where is this|where am i at)$", false, null);
        rule(Kind.MAP_DROPS, "^what drops (?:here|on this map|in here|around here)$", false, null);
        rule(Kind.MAP_DROPS, "^what do (?:these|they|those|the mobs|the monsters)(?: guys| mobs| monsters| things)? drop"
                + "(?: here| on this map| in here| around here)?$", false, null);
        rule(Kind.MAP_DROPS, "^(?:what|which) (?:mobs|monsters) (?:are|is) (?:here|on this map|in here)$", false, null);
        rule(Kind.MAP_DROPS, "^what (?:do|does) (?:the )?(?:mobs|monsters) (?:here|on this map) drop$", false, null);
        rule(Kind.MAP_GOOD, "^is (?:this map|this place|this spot|here)(?: a)? (?:good|decent|ok|any good|worth it|nice)"
                + "(?: spot| map| place)?(?: for (?:exp|training|leveling|me|my level|level \\d{1,3}))?(?: exp| to train| to level)?$", false, null);
        // a bare "is this good" is about an item being shown or a trade: only with a map / exp / training word
        rule(Kind.MAP_GOOD, "^is this(?: a)? (?:good|decent|ok|any good|worth it|nice)"
                + "(?:(?: spot| map| place)(?: for (?:exp|training|leveling|me|my level|level \\d{1,3}))?(?: exp| to train| to level)?"
                + "|(?: for (?:exp|training|leveling|my level|level \\d{1,3}))(?: exp| to train| to level)?| exp| to train| to level)$", false, null);
        rule(Kind.MAP_GOOD, "^(?:is the )?exp (?:good|decent|ok) (?:here|on this map)$", false, null);
        // server rules (asked directly)
        rule(Kind.RULES, "^(?:what is|what are|how much is|how high is|is|are)? ?(?:the|your|these|this)? ?(?:server )?"
                + "(?<x>exp|xp|drop|drops|meso|mesos|boss drop|boss drops)? ?rates?(?: here| on this server| like| now| at)?$", false, null);
        rule(Kind.RULES, "^how (?:much|many) (?:x|times) (?:is |are )?(?:the )?(?<x>exp|drop|meso)s?(?: rate)?$", false, null);
        rule(Kind.RULES, "^(?:is|are) (?:the )?(?<x>exp|drop|meso)s? (?:rate )?(?:high|low|boosted|good|bad|normal)(?: here)?$", false, null);
        rule(Kind.RULES, "^can (?:a |an |i as a |i as an |any |every )?(?<from>[a-z]+)? ?(?:wear|use|equip) (?<x>.+)$", false, null);
        rule(Kind.RULES, "^(?:does|do) (?:class|job|jobs|classes) (?:matter|reqs? matter)(?: for (?:gear|equips|items))?$", false, null);
        rule(Kind.RULES, "^why is (?:my |the |this )?(?:gear|item|equip|text|stat|weapon)s? (?:red|in red)$", false, null);
        rule(Kind.RULES, "^(?:do|does|will|can) (?:pets?|my pet) (?:expire|die|turn into (?:a )?dolls?|run out|stop working)(?: here)?$", false, null);
        rule(Kind.RULES, "^how (?:do|can) i (?:get|buy|expand|unlock) (?:more )?(?:inventory |inv |bag |item )?(?:slots|space|room)$", false, null);
        // routes
        rule(Kind.ROUTE, "^how (?:do|can|would|should|could|does|did) (?:i|we|you|one|people|someone) (?:get|go|travel|walk|reach)"
                + " (?:back )?to (?:the )?(?<x>.+?)(?: from (?<from>.+?))?$", false, null);
        rule(Kind.ROUTE, "^how (?:to|do i|i|we) (?:get|go|travel) (?:back )?to (?:the )?(?<x>.+?)(?: from (?<from>.+?))?$", false, null);
        rule(Kind.ROUTE, "^how (?:do|can) (?:i|you|we) reach (?:the )?(?<x>.+?)(?: from (?<from>.+?))?$", false, null);
        rule(Kind.ROUTE, "^(?:which|what) way (?:is|to|for) (?:the )?(?<x>.+?)(?: from (?<from>.+?))?$", false, null);
        rule(Kind.ROUTE, "^(?:what is )?(?:the )?(?:best|fastest|quickest|easiest) way (?:to get to|to go to|to) (?:the )?(?<x>.+?)"
                + "(?: from (?<from>.+?))?$", false, null);
        rule(Kind.ROUTE, "^is there (?:a |an )?(?:shortcut|faster way|quicker way|quick way|easy way|easier way) to (?:get to )?(?:the )?"
                + "(?<x>.+?)(?: from (?<from>.+?))?$", false, null);
        rule(Kind.ROUTE, "^how far is (?:the )?(?<x>.+?)(?: from (?<from>.+?))?$", false, null);
        rule(Kind.ROUTE, "^(?:directions|direction) to (?:the )?(?<x>.+)$", false, null);
        // prices
        rule(Kind.PRICE, "^(?:pc|price check|pricecheck|price on|price of|price for|prices on|prices for) (?:an? |the )?(?<x>.+)$", false, null);
        rule(Kind.PRICE, "^how much (?:is|are|for|do|does|would|will|can i get for|should i pay for|to pay for|does a|do they sell|is a|are the)"
                + " (?:an? |the )?(?<x>.+?)(?: going for| worth| sell for| selling for| sold for| go for| cost| costs| cost now| in fm"
                + "| at fm| in the fm| these days| now| usually| nowadays| each| a set| per set| for)*$", false, null);
        rule(Kind.PRICE, "^what is (?:the )?(?:price|going rate|market price|value|fm price) (?:of|for|on) (?:an? |the )?(?<x>.+?)$", false, null);
        rule(Kind.PRICE, "^what (?:do|does|are|is) (?<x>.+?) (?:go|going|sell|selling) for(?: now| these days| in fm| in the fm)?$", false, null);
        rule(Kind.PRICE, "^what (?:are|is) (?<x>.+?) (?:worth|going for)(?: now| these days)?$", false, null);
        anyRule(Kind.PRICE, "^(?<x>.+?) (?:price|prices|pc)$", true);
        // levels
        rule(Kind.LEVEL_OF, "^(?:what|which) level (?:is|are|for|do i need for|do you need for|to use|to wear|to equip|to kill|to do"
                + "|to enter|to hunt|to farm|can i (?:use|wear|equip|do|enter|hunt|kill|join)|should i be (?:for|to do|to hunt|to kill)"
                + "|is good for|are good for|is|are)(?: an?| the)? (?<x>.+?)(?: at| need| needed| required| req)?$", false, null);
        rule(Kind.LEVEL_OF, "^how (?:high|strong|hard) (?:is|are) (?:the )?(?<x>.+)$", false, null);
        anyRule(Kind.LEVEL_OF, "^(?<x>.+?) (?:what|which) level$", true);
        anyRule(Kind.LEVEL_OF, "^(?<x>.+?) level (?:req|requirement)$", true);
        // drops
        rule(Kind.MOB_DROPS, "^what (?:does|do|did|can|will|would)? ?(?:a |an |the )?(?<x>.+?) drop(?: here| on this map| around here)?$", false, null);
        rule(Kind.MOB_DROPS, "^what (?:can|will|do) (?:i|you) get from (?:a |an |the )?(?<x>.+)$", false, null);
        rule(Kind.MOB_DROPS, "^what (?:items|loot|stuff) (?:do|does) (?:a |an |the )?(?<x>.+?) drop$", false, null);
        anyRule(Kind.MOB_DROPS, "^(?<x>.+?) drops$", true);
        rule(Kind.DROP_SOURCE, "^(?:who|what|which)(?: mob| mobs| monster| monsters| boss| bosses| thing| things| one| ones)?"
                + " drops? (?:the )?(?<x>.+?)$", false, null);
        rule(Kind.DROP_SOURCE, "^(?:what|which) (?:mob|mobs|monster|monsters) (?:do|should|can) (?:i|you) (?:kill|hunt|farm) (?:for|to get) (?<x>.+)$", false, null);
        rule(Kind.DROP_SOURCE, "^where (?:does|do|can|did)? ?(?:the )?(?<x>.+?) drops?(?: from)?$", false, null);
        rule(Kind.DROP_SOURCE, "^where (?:can|do|should|could) (?:i|you|we|people) (?:farm|hunt for|loot) (?:the |some )?(?<x>.+)$", false, null);
        rule(Kind.DROP_SOURCE, "^where (?:to|2) (?:farm|hunt for|loot) (?:the |some )?(?<x>.+)$", false, null);
        rule(Kind.DROP_SOURCE, "^(?:does|do) (?:anything|any mobs?|any monsters?) drop (?<x>.+)$", false, null);
        anyRule(Kind.DROP_SOURCE, "^(?<x>.+?) drops? from (?:what|which|where)(?: mob| monster)?$", false);
        // buying and getting
        rule(Kind.BUY_WHERE, "^where (?:can|do|should|could|would) (?:i|you|we|people|one) (?:buy|purchase) (?:some |more |a |an )?(?<x>.+?)"
                + "(?: at| from(?: an? (?:npc|shop|store))?| anywhere| cheap| here| in town| around here)?$", false, null);
        rule(Kind.BUY_WHERE, "^where (?:to|2|i can|we can|i could) (?:buy|purchase) (?:some |more |a |an )?(?<x>.+?)(?: at| from| anywhere| cheap| here)?$", false, null);
        rule(Kind.BUY_WHERE, "^(?:who|which npc|what npc|which shop|what shop|which store|what store|any npc|any shop|does any npc"
                + "|does any shop|do any npcs|do any shops) (?:sells?|has|have) (?<x>.+?)(?: here| around here| in town| anywhere)?$", false, null);
        rule(Kind.BUY_WHERE, "^(?:can|could) (?:i|you) buy (?<x>.+?) (?:anywhere|from (?:an? )?(?:npc|shop|store)|in (?:a )?(?:shop|store)"
                + "|at (?:a )?(?:shop|store))$", false, null);
        rule(Kind.BUY_WHERE, "^(?:is|are) (?<x>.+?) sold (?:anywhere|in (?:a )?(?:shop|store)|by (?:an? )?npc)$", false, null);
        rule(Kind.BUY_WHERE, "^where (?:are|is) (?<x>.+?) sold$", false, null);
        rule(Kind.GET_WHERE, "^where (?:can|do|should|could|would|did) (?:i|you|we|people|one|players) (?:get|find|obtain|grab)"
                + " (?:some |more |a |an |the )?(?<x>.+?)(?: from| at| anywhere| around here| here)?$", false, null);
        rule(Kind.GET_WHERE, "^where (?:to|2|i can|we can|i could) (?:get|find|obtain) (?:some |more |a |an |the )?(?<x>.+?)$", false, null);
        rule(Kind.GET_WHERE, "^how (?:do|can|would|should) (?:i|you|we|one|people) (?:get|obtain|find|make) (?:some |more |a |an )?(?<x>.+?)$", false, null);
        rule(Kind.GET_WHERE, "^how (?:to|2) (?:get|obtain|find|make) (?:some |more |a |an )?(?<x>.+?)$", false, null);
        rule(Kind.GET_WHERE, "^where (?:does|do) (?:the )?(?<x>.+?) come from$", false, null);
        rule(Kind.GET_WHERE, "^(?:any |is there an? )?(?:good )?(?:place|spot) to get (?<x>.+)$", false, null);
        // where things are
        // "how do i join kpq" / "where do i start lpq": where the PQ (or place) is; an unresolved subject stays silent
        rule(Kind.WHERE_IS, "^(?:how|where) (?:do|can|should|would|could) (?:i|we|you) (?:join|start|do|enter|sign up for|get into)"
                + " (?:the |a |an )?(?<x>.+?)$", true, null);
        rule(Kind.WHERE_IS, "^where (?:is|are|was) (?:the |a |an |some )?(?<x>.+?)$", false, null);
        rule(Kind.WHERE_IS, "^where (?:the |a )?(?<x>.+?) (?:is|are)(?: at)?$", false, null);
        rule(Kind.WHERE_IS, "^where (?:do|does|can) (?:the )?(?<x>.+?) (?:spawn|spawns|live|lives|hang out|hangs out|stand|stay|usually spawn)$", false, null);
        rule(Kind.WHERE_IS, "^where (?:can|do|should|could) (?:i|you|we) (?:hunt|kill|fight|see|meet|talk to|catch) (?:the |some )?(?<x>.+?)$", false, null);
        rule(Kind.WHERE_IS, "^(?:which|what) map (?:is|are|has|have) (?:the )?(?<x>.+?)(?: in| on| at)?$", false, null);
        // opinions: facts for the model, no deterministic reply
        rule(Kind.OPINION, "^(?:is|are) (?<x>.+?) (?:good|worth(?: it)?|any good|decent|bad|ok|better than (?<from>.+?)|worth buying"
                + "|worth farming|good exp|good money|op)(?: exp| for .+| at .+| to train| to level| to farm)?$", false, null);
        // "and manas?" / "what about ilbis": the last question again with a new subject
        rule(Kind.FOLLOW_UP, "^(?:and|what about|how about|also|n) (?<x>.+?)$", true, null);
        // "where kerning": last of all (after "where am i"), and an unresolved word stays silent
        anyRule(Kind.WHERE_IS, "^where (?!(?:is|are|was|were|am|do|does|did|can|could|should|would|will|to|has|have|r)\\b)"
                + "(?<x>[a-z]+(?: [a-z]+)?)$", true);
    }

    private ContentQuestionDetector() {
    }

    /**
     * True when the line has the shape of a content question (regex only, nothing resolved). Never true for
     * commands, thanks, or follow-ups ("how do i get there", "and manas?"), which only mean something right
     * after an answer.
     */
    public static boolean looksLikeContentQuestion(String line) {
        Shape shape = shape(line);
        return shape != null && shape.kind() != Kind.THANKS && shape.kind() != Kind.FOLLOW_UP;
    }

    /** The question shape of a chat line, or null when it is not a content question. */
    public static Shape shape(String line) {
        if (line == null || line.isBlank() || MARKET.matcher(line).find()) {
            return null;
        }
        String folded = fold(line);
        if (isThanks(folded)) {
            return new Shape(Kind.THANKS, "", "", 0, 0, "", null, false, false, false, folded);
        }
        String frame = strip(folded);
        if (frame.isEmpty()) {
            return null;
        }
        Shape keyword = keywordShape(frame, line.indexOf('?') >= 0);
        if (keyword != null) {
            return keyword;
        }
        int space = frame.indexOf(' ');
        List<Rule> rules = RULES_BY_FIRST.get(space < 0 ? frame : frame.substring(0, space));
        Shape found = rules == null ? null : match(rules, frame);
        return found != null ? found : match(ANY_FIRST, frame);
    }

    /** The frame form: folded chat spelling, fillers stripped from both ends (see the class comment). */
    public static String frame(String line) {
        return strip(fold(line));
    }

    private static Shape match(List<Rule> rules, String frame) {
        for (Rule rule : rules) {
            Matcher m = rule.pattern().matcher(frame);
            if (!m.matches()) {
                continue;
            }
            String slot = group(m, "x");
            String from = group(m, "from");
            switch (rule.kind()) {
                case FOLLOW_UP, WHERE_AM_I, MAP_DROPS, MAP_GOOD -> {
                    if (rule.follow() == null && rule.kind() == Kind.FOLLOW_UP && !usableSlot(trimSlot(slot))) {
                        continue;
                    }
                    return new Shape(rule.kind(), trimSlot(slot), "", 0, 0, "", rule.follow(), rule.weak(), false, false, frame);
                }
                case RULES -> {
                    String topic = topic(frame, slot);
                    if (topic == null || from.equals("you")) {
                        continue;
                    }
                    return new Shape(Kind.RULES, slot, from, 0, 0, topic, null, false, false, false, frame);
                }
                default -> {
                }
            }
            slot = trimSlot(slot);
            from = HERE.contains(from) ? "" : trimSlot(from);
            if (rule.kind() == Kind.ROUTE && slot.matches("^(?:level|lvl|lv) ?\\d{1,3}.*|^\\d{1,3}$")) {
                return null; // "how do i get to level 30" is about levelling, not a place
            }
            if (!usableSlot(slot) || from.length() > 0 && !usableSlot(from) && rule.kind() != Kind.OPINION) {
                continue;
            }
            if (rule.weak() && (LOOT_TALK.contains(slot.split(" ")[0]) || slot.contains(" the ") || slot.endsWith(" the"))) {
                continue;
            }
            boolean whoSells = rule.kind() == Kind.BUY_WHERE && frame.startsWith("who ");
            return new Shape(rule.kind(), slot, from, 0, 0, "", null, rule.weak(), whoSells, false, frame);
        }
        return null;
    }

    /**
     * JOB_ADVANCE and TRAIN_WHERE are keyword shapes: "how do i 2nd job", "where do i job adv", "how do i become
     * a mage"; a training word + a place word + a question start ("good spot 4 lvl 30", "lf training spot 35").
     */
    private static Shape keywordShape(String frame, boolean asked) {
        if (ABOUT_THEM.matcher(frame).find()) {
            return null; // "what are you guys training on", "how did you 2nd job"
        }
        Matcher job = JOB_CUE.matcher(frame);
        if (job.find() && JOB_START.matcher(frame).find()) {
            Matcher adv = ADVANCEMENT.matcher(frame);
            int advancement = adv.find() ? switch (adv.group(1)) {
                case "1st", "first" -> 1;
                case "2nd", "second" -> 2;
                case "3rd", "third" -> 3;
                default -> 4;
            } : 0;
            String become = job.group("job");
            return new Shape(Kind.JOB_ADVANCE, "", "", 0, advancement, become == null ? "" : become, null, false, false,
                    false, frame);
        }
        boolean statement = STATEMENT_START.matcher(frame).find()
                && (!asked && !STATEMENT_ASKS.matcher(frame).find() || STATEMENT_SAYS.matcher(frame).find());
        if (TRAIN_WORD.matcher(frame).find() && PLACE_WORD.matcher(frame).find() && TRAIN_START.matcher(frame).find()
                && !statement && !WHERE_IS_THING.matcher(frame).find() && !HUNT_TARGET.matcher(frame).find()) {
            Matcher number = LEVEL_NUMBER.matcher(frame);
            int level = 0;
            while (number.find() && level == 0) {
                int value = Integer.parseInt(number.group(1));
                level = value >= 1 && value <= 250 ? value : 0;
            }
            return new Shape(Kind.TRAIN_WHERE, "", "", level, 0, "", null, false, false, FOR_OTHER.matcher(frame).find(), frame);
        }
        return null;
    }

    private static String topic(String frame, String slot) {
        if (frame.contains("rate")) {
            return "rates";
        }
        if (frame.matches(".*\\b(?:wear|use|equip|class|job|red)\\b.*")) {
            return frame.startsWith("can ") && (slot == null || slot.isEmpty() || !usableSlot(slot) && !slot.equals("anything")) ? null : "gear";
        }
        if (frame.contains("pet")) {
            return "pets";
        }
        if (frame.matches(".*\\b(?:slots|space|room)\\b.*")) {
            return "slots";
        }
        return null;
    }

    static boolean usableSlot(String slot) {
        if (slot == null || slot.isEmpty() || SILENT.contains(slot) || SILENT_START.matcher(slot).find()) {
            return false;
        }
        String[] tokens = slot.split(" ");
        if (tokens.length > 7 || PRONOUNS.contains(tokens[0])) {
            return false;
        }
        for (String token : tokens) {
            if (POSSESSIVE.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static String trimSlot(String slot) {
        if (slot == null) {
            return "";
        }
        return TRAILING_SLOT.matcher(slot.trim()).replaceAll("").trim();
    }

    private static String group(Matcher m, String name) {
        try {
            String value = m.group(name);
            return value == null ? "" : value;
        } catch (IllegalArgumentException noSuchGroup) {
            return "";
        }
    }

    /** Lowercase, punctuation to spaces, repeats collapsed, chat spelling folded; "2"/"4" only between words. */
    static String fold(String line) {
        StringBuilder spaced = new StringBuilder(line.length() + 4);
        for (int i = 0; i < line.length(); i++) {
            char c = Character.toLowerCase(line.charAt(i));
            if (c == '\'' || c == '’') {
                continue;
            }
            spaced.append(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '%' ? c : ' ');
        }
        String[] raw = spaced.toString().trim().split(" +");
        List<String> tokens = new ArrayList<>(raw.length + 2);
        for (String token : raw) {
            if (token.isEmpty()) {
                continue;
            }
            Matcher glued = GLUED_LEVEL.matcher(token);
            if (glued.matches()) {
                tokens.add("level");
                tokens.add(glued.group(1));
                continue;
            }
            String collapsed = NameIndex.collapseRepeats(token);
            String folded = FOLD.get(collapsed);
            if (folded == null && !collapsed.equals(token)) {
                folded = FOLD.get(token);
            }
            for (String part : (folded == null ? collapsed : folded).split(" ")) {
                tokens.add(part);
            }
        }
        if (tokens.size() > 1 && tokens.get(0).equals("were") && WERE_NEXT.contains(tokens.get(1))) {
            tokens.set(0, "where");
        }
        if (!tokens.isEmpty() && tokens.get(0).equals("n")) {
            tokens.set(0, "and");
        }
        for (int i = 1; i + 1 < tokens.size(); i++) {
            String token = tokens.get(i);
            String next = tokens.get(i + 1);
            boolean levelNext = next.equals("level") && i + 2 < tokens.size() && tokens.get(i + 2).matches("\\d{1,3}");
            if ((token.equals("2") || token.equals("4")) && isWord(tokens.get(i - 1)) && (isWord(next) || levelNext)) {
                tokens.set(i, token.equals("2") ? "to" : "for"); // "how 2 get 2 ludi", "spot 4 lvl 30"; never "lvl 2", "4 att"
            }
        }
        return String.join(" ", tokens);
    }

    private static boolean isWord(String token) {
        return !UNITS.contains(token) && token.chars().allMatch(Character::isLetter);
    }

    private static String strip(String folded) {
        String out = folded;
        String before;
        do {
            before = out;
            out = LEAD.matcher(out).replaceFirst("").trim();
            out = TRAIL.matcher(out).replaceFirst("").trim();
        } while (!out.equals(before) && !out.isEmpty());
        return out;
    }

    private static boolean isThanks(String folded) {
        boolean thanks = false;
        for (String token : folded.split(" ")) {
            if (THANKS_WORDS.contains(token)) {
                thanks = true;
            } else if (!THANKS_FILL.contains(token)) {
                return false;
            }
        }
        return thanks;
    }

    /** Indexes the rule by the first words its regex can start with ({@code ^where ...}, {@code ^(?:who|what) ...}). */
    private static void rule(Kind kind, String regex, boolean weak, Kind follow) {
        Rule rule = new Rule(kind, Pattern.compile(regex), weak, follow);
        Matcher first = Pattern.compile("^\\^\\(\\?:([a-z |]+)\\)(\\?)?|^\\^([a-z]+)").matcher(regex);
        if (!first.find() || first.group(2) != null) {
            ANY_FIRST.add(rule); // an optional first group: the line can start with anything
            return;
        }
        String words = first.group(1) != null ? first.group(1) : first.group(3);
        for (String word : words.split("\\|")) {
            RULES_BY_FIRST.computeIfAbsent(word.split(" ")[0], w -> new ArrayList<>()).add(rule);
        }
    }

    private static void anyRule(Kind kind, String regex, boolean weak) {
        ANY_FIRST.add(new Rule(kind, Pattern.compile(regex), weak, null));
    }
}
