package server.living.resident;

import client.Character;
import client.inventory.Item;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.TimerManager;
import server.agents.capabilities.dialogue.AgentChatCommandClassifier;
import server.agents.commands.AgentDismissCommandService;
import server.agents.commands.AgentReplyChannel;
import server.agents.runtime.AgentInteractionRuntime;
import server.integration.AgentPresence;
import server.knowledge.ContentChat;
import server.knowledge.ContentQuestionDetector.Kind;
import server.knowledge.Decisions;
import server.knowledge.EntityKind;
import server.knowledge.IntList;
import server.knowledge.KnownWorld;
import server.knowledge.NameIndex;
import server.knowledge.PersonalKnowledge;
import server.knowledge.WorldKnowledge;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.LivingContent;
import server.living.phrases.PhraseMatcher;
import server.living.roster.CompanionChatVerbs;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionSlots;
import server.living.roster.HireOffers;
import server.living.roster.HiredHands;
import server.living.roster.RosterResult;
import server.living.roster.RosterVoice;
import server.living.roster.ui.CompanionMenus;
import server.living.roster.ui.HireScreens;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What happens when a player talks near or to a Resident, and the owner-side roster commands
 * (call, bench, swap, assignments, haul) that work whether the companion is materialized or not.
 * Deterministic patterns first; the resident intent judge only on a miss.
 */
public final class ResidentDialogue {
    private static final Logger log = LoggerFactory.getLogger(ResidentDialogue.class);
    private static final int PROXIMITY_PX = 320;
    /** The {@code knows:} word for "knows what things go for" (docs/SERVER_KNOWLEDGE_PLAN.md 7.7). */
    private static final String PRICES = "prices";

    private static final Pattern GREET = Pattern.compile("^(hi|hello|hey|yo|sup|hiya|greetings|good (morning|evening|day))( there)?( \\w+)?$");
    private static final Pattern ASK_QUEST = Pattern.compile("\\b(quest|work|job|task|errand|favou?r|anything (for me|to do|i can do)|need (help|a hand|anything)|got anything|help you|what do you need|any work)\\b");
    private static final Pattern ACCEPT = Pattern.compile("^(accept|ok|okay|sure|yes|yeah|yep|deal|fine|ill do it|i will do it|im in|count me in|lets do it|on it|alright)( .*)?$");
    private static final Pattern TURN_IN = Pattern.compile("\\b(done|finished|complete[d]?|here you go|here it is|brought (it|them|what you asked)|got (it|them)|turn in|i have (it|them)|delivered|take (it|this|them))\\b");
    private static final Pattern ASK_JOIN = Pattern.compile("\\b(join( me| us| my party)?|come with (me|us)|travel with me|hire|recruit|be my companion|party up|adventure with me|fight (with|beside) me|come along)\\b");
    private static final Pattern GOODBYE = Pattern.compile("^(bye|goodbye|later|cya|see ya|see you|farewell|gtg|leaving)( .*)?$");
    private static final Pattern WHO = Pattern.compile("\\b(who are you|whats your name|what is your name|about you|introduce yourself|your story|who r u)\\b");
    private static final Pattern THANKS = Pattern.compile("^(thanks|thank you|thx|ty|cheers|appreciate it)( .*)?$");
    private static final Pattern DECLINE = Pattern.compile("^(no|nah|nope|not now|maybe later|decline|pass|no thanks)( .*)?$");
    private static final Pattern PAY = Pattern.compile("^pay (\\d+)k?$");
    private static final Pattern ASK_ABOUT = Pattern.compile("^(tell me about|who is|what about|whats with|know anything about|heard about) (.+)$");
    private static final Pattern TALK = Pattern.compile("^(talk|lets talk|menu|options|speak|chat|talk to me|can we talk|got a minute|a word)$");

    /** Borrowed townsperson portraits for personas with neither portraitNpcId nor a generated one (portrait-npc-ids.yaml). */
    private static final Map<String, int[]> PORTRAITS = Map.ofEntries(
            Map.entry("lith-harbor", new int[] {1002000, 1002001, 1002002, 1002003, 1002005}),
            Map.entry("henesys", new int[] {1010100, 1012003, 1012106, 1012108, 1012109}),
            Map.entry("kerning-city", new int[] {1052002, 1052003, 1052004, 1052005, 1052006, 1052017, 1052102, 1052103}),
            Map.entry("perion", new int[] {1022002, 1022003, 1022004, 1022005}),
            Map.entry("ellinia", new int[] {1032002, 1032003, 1032100, 1032101, 1032102}),
            Map.entry("nautilus", new int[] {1092000, 1092001, 1092002, 1092003}),
            Map.entry("sleepywood", new int[] {1061000, 1061002, 1061003, 1061004}),
            Map.entry("ereve", new int[] {1101004, 1101005, 1101006, 1101007}),
            Map.entry("rien", new int[] {1200000, 1200001, 1200002}),
            Map.entry("orbis", new int[] {2010000, 2012012, 2012019, 2012011}),
            Map.entry("el-nath", new int[] {2020003, 2020006, 2020007, 2020002}),
            Map.entry("ludibrium", new int[] {2041004, 2040046, 9103001, 2041014}),
            Map.entry("leafre", new int[] {2080000, 2080002, 2080004, 2080005}),
            Map.entry("mu-lung", new int[] {2090006, 2090102, 2090103, 2090104}),
            Map.entry("herb-town", new int[] {2092000, 2093000, 2093001, 2093002}),
            Map.entry("ariant", new int[] {2100003, 2100005, 2100006, 2100007, 2100008}),
            Map.entry("magatia", new int[] {2110000, 2110001, 2111004, 2111005}),
            Map.entry("crimsonwood-keep", new int[] {9201107, 9201108, 9201109, 9201110, 9201111}),
            Map.entry("haunted-house", new int[] {9201136, 9201137, 9201138, 9201143}),
            Map.entry("bigger-ben", new int[] {9201058, 9201059, 9201081, 9201087}),
            Map.entry("valley-of-heroes", new int[] {9201104, 9201105}),
            Map.entry("omega-sector", new int[] {2050009, 2050010, 2050011, 2050012}),
            Map.entry("korean-folk-town", new int[] {2071000, 2071001, 2071002, 2071004, 2071007}),
            Map.entry("new-leaf-city", new int[] {9201053, 9201055, 9201058, 9201059, 9201060}),
            Map.entry("aquarium-hall", new int[] {2060001, 2060002, 2060003, 2060004}),
            Map.entry("aquarium-store", new int[] {2060001, 2060002, 2060003, 2060004}),
            Map.entry("singapore-cbd", new int[] {9270019, 9270020, 9270021, 9270023}),
            Map.entry("boat-quay-town", new int[] {9270022, 9270030}),
            Map.entry("mushroom-shrine", new int[] {9110002, 9110003, 9110004, 9110005, 9110006}),
            Map.entry("showa-town", new int[] {9120001, 9120003, 9120011, 9120017, 9120018}),
            Map.entry("kuala-lumpur", new int[] {9270054, 9270055, 9270056, 9270057}),
            Map.entry("kampung-village", new int[] {9270061, 9270062, 9270065}));

    public static int portraitOf(Persona persona) {
        int ownLook = renderedPortraitOf(persona);
        if (ownLook > 0) {
            return ownLook;
        }
        if (LivingContent.isHire(persona.id())) {
            return CompanionMenus.BOARD_NPC; // a hired citizen has no rendered portrait: never a borrowed townsperson
        }
        int[] town = PORTRAITS.getOrDefault(persona.homeTown(), new int[] {9010000});
        return town[Math.floorMod(persona.id().hashCode(), town.length)];
    }

    /** The NPC id whose portrait is this persona's own face (the configured or the rendered 9970000+ one); 0 when none. */
    public static int renderedPortraitOf(Persona persona) {
        if (persona.portraitNpcId() > 0) {
            return persona.portraitNpcId();
        }
        LivingWorld world = LivingWorld.get();
        return world == null ? 0 : world.content().portraitNpcId(persona.id());
    }

    private static final Pattern ROSTER = Pattern.compile("^(roster|who do i have|my companions|my crew|who travels with me|list companions)$");
    private static final Pattern CALL = Pattern.compile("^(call|summon|bring) (\\w+)$");
    private static final Pattern BENCH = Pattern.compile("^(bench|rest|dismiss|dismiss to camp) (\\w+)$");
    private static final Pattern PARK = Pattern.compile("^park (\\w+)$");
    private static final Pattern SWAP = Pattern.compile("^swap (\\w+) (for|with) (\\w+)$");
    private static final Pattern RELEASE = Pattern.compile("^(release|fire|let go) (\\w+)$");
    private static final Pattern EVERYONE_BENCH = Pattern.compile("^(everyone|all|everybody) (bench|rest|go to camp)$");
    private static final Pattern EVERYONE_PARK = Pattern.compile("^(park|park (everyone|all|everybody)|(everyone|all|everybody) park)$");
    private static final Pattern CALL_USUAL = Pattern.compile("^(call|bring) (the usual|everyone|all|everybody)$");
    private static final Pattern EVERYONE_KEYSTONE = Pattern.compile("^((everyone|all|everybody) )?get (your |a |the )?keystones$");
    private static final Pattern EVERYONE_TICKET = Pattern.compile("^((everyone|all|everybody) )?get (your |a |the )?(entrance )?tickets$");
    private static final Pattern FARM_ITEM = Pattern.compile("^farm (\\d+ )?(.+?)( x?(\\d+))?$");
    private static final Pattern WAGES = Pattern.compile("^(wages|pay wages|heres your pay|your pay|payday)( (\\d+)k?)?$");
    /**
     * Sell-now lines (live 2026-10-10): to a companion out with its owner they are the Agent's own sell-trash command,
     * never the bench job; to a benched or away one they are the job SELL, as "sell", "sell it all", "sell the haul"
     * and "sell and come back" always are.
     */
    private static final Set<String> SELL_NOW = Set.of("sell junk", "sell trash", "sell my junk", "sell my trash");
    /**
     * The phrase packs' commands an owner's map line may give one companion by its name last ({@link #agentCommandFor},
     * fourth deploy review 2026-10-10): what it does to itself, its own gear, settings and supplies, or answers about
     * itself, so a name after them can only be whom it is said to. Not the ones whose name last is often the verb's
     * object ("fame tamsin", "help tamsin", "attack tamsin", "guard tamsin", "greet tamsin", "trade tamsin", "go to",
     * "focus", "avoid", the formation and the planned tank and skill lines): those keep the untargeted Agent route.
     */
    private static final Set<String> NAME_LAST_COMMANDS = Set.of("follow me", "stop", "move here", "patrol", "stats",
            "inventory", "slots", "range", "build", "skills", "mesos", "speed", "exp", "scrolls", "pots", "buff list",
            "support on", "support off", "heals on", "heals off", "buff on", "buff off", "buff cheap", "buff max",
            "need hp pot", "need mp pot", "need ammo", "need pot", "any upgrades?", "need anything?", "sell trash",
            "autoequip", "unequip everything", "change build", "relog", "kite", "retreat at hp", "loot mode", "restock",
            "bank", "train until", "quiet", "what are you doing");
    /**
     * Words after which a name is the verb's object, not whom the line is said to ("wait for tamsin", "heals on
     * tamsin"): the phrase packs read some as tails ("for now" normalizes to "for"), so a whole command can end in one.
     */
    private static final Set<String> BEFORE_AN_OBJECT = Set.of("for", "with", "to", "at", "on", "in", "into", "onto",
            "behind", "near", "beside", "by", "after", "from", "of", "about", "toward", "towards", "around", "against",
            "past", "over", "under", "than", "like", "and", "or");
    /** A whispered call with a tail the roster's own call words do not list ("come back to me", "come back here"). */
    private static final Pattern RECALL = Pattern.compile("^(come|get) back( here| to me| with me| over here)?$");

    private final LivingWorld world;
    private final ResidentIntentJudge judge;
    private final Map<String, Deque<String>> recentTurns = new ConcurrentHashMap<>();

    public ResidentDialogue(LivingWorld world) {
        this.world = world;
        this.judge = new ResidentIntentJudge(world);
    }

    // ---- entry points --------------------------------------------------------------------

    /**
     * Map chat; true when a Resident or the roster took the message, or it was handed to the speaker's Agents (the
     * answer to their own logout question).
     */
    public boolean onMapChat(Character speaker, String text) {
        if (AgentPresence.isAgent(speaker)) {
            return false;
        }
        if (logoutTurn(speaker, text)) {
            return true;
        }
        String normalized = PhraseMatcher.normalize(text);
        if (normalized.isEmpty()) {
            return false;
        }
        if (rosterCommand(speaker, normalized)) {
            return true;
        }
        Target target = target(speaker, normalized, false);
        if (target == null) {
            return false;
        }
        return converse(speaker, target.persona, target.remainder, text);
    }

    /** Whisper to a persona by name, or an answer to a citizen's open offer to stay on; true when either took it. */
    public boolean onWhisper(Character speaker, String targetName, String text) {
        if (!AgentPresence.isAgent(speaker) && answeredOffer(speaker, targetName, text)) {
            return true;
        }
        if (AgentPresence.isAgent(speaker)) {
            return false;
        }
        Persona persona = world.content().personaByName(targetName);
        if (persona == null) {
            // hires are no content: the owner's hired citizen goes by its own name, as in map chat, so a whispered "come
            // back" reaches the roster (deploy review 2026-10-10: a hire on a live bench slice said "call me back first")
            persona = hiredHandNamed(speaker, targetName);
        }
        if (persona == null) {
            return false;
        }
        String normalized = PhraseMatcher.normalize(text);
        String command = addressedTo(persona, normalized);
        if (rosterCommandFor(speaker, persona, command, true) || recalled(speaker, persona, command)) {
            return true;
        }
        if (world.roster().ownerOf(persona.id()) == speaker.getId()) {
            return false; // companion commands travel the agent whisper bridge
        }
        return converse(speaker, persona, normalized, text) || true;
    }

    /** The whisper without its addressee's name, first or last ("tamsin come back", "come here tamsin"), as map chat. */
    private static String addressedTo(Persona persona, String normalized) {
        String name = persona.name().toLowerCase(Locale.ROOT);
        String[] words = normalized.split(" ");
        if (words.length < 2) {
            return normalized;
        }
        if (words[0].equals(name)) {
            return normalized.substring(name.length()).trim();
        }
        return words[words.length - 1].equals(name)
                ? normalized.substring(0, normalized.length() - name.length()).trim() : normalized;
    }

    /**
     * The owner's whispered call to a companion that is not out with them (fourth deploy review 2026-10-10): a call
     * whose tail the roster's call words do not list ("come back to me", "come back here"), or what its Agent would
     * read as "follow me" or "come here" ("get over here", "lets go", "come to me"), calls it. Before, such a whisper
     * went on to the whisper bridge, which a live bench slice refuses, and the companion whispered back "call me back
     * first". Out with its owner, those lines stay its Agent's. Only the owner's call reads the roster row.
     */
    private boolean recalled(Character owner, Persona persona, String command) {
        if (world.roster().cachedOwnerOf(persona.id()) != owner.getId()) {
            return false;
        }
        PhraseMatcher phrases = world.phrases();
        String canonical = phrases == null ? null : phrases.commandFor(command);
        boolean toOwner = ("follow me".equals(canonical) || "move here".equals(canonical))
                && AgentChatCommandClassifier.matchFollowTarget(command) == null; // not "follow hadrik"
        if (!RECALL.matcher(command).matches() && !toOwner) {
            return false;
        }
        RosterEntry entry = world.roster().entry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED || entry.state() == RosterState.ACTIVE) {
            return false;
        }
        world.chatVerbs().say(owner, "roster.call", () -> RosterVoice.line(persona, world.roster().call(owner, persona)));
        return true;
    }

    /**
     * A whispered answer to the citizen's open offer to stay on (P4, "want me to stick around?"): a no closes the offer
     * in its own words, as Decline does; a yes opens its terms when a window may open, else it says how to get them.
     * False when the speaker has no open offer from {@code targetName}, or the line is neither.
     */
    private boolean answeredOffer(Character speaker, String targetName, String text) {
        HireOffers offers = world.hireOffers();
        HiredHands hands = world.hiredHands();
        List<HireOffers.Offer> open = offers == null || hands == null ? List.of() : offers.pendingOffers(speaker);
        HireOffers.Offer offer = open.isEmpty() ? null : open.get(0);
        if (offer == null || !offer.name().equalsIgnoreCase(targetName)) {
            return false;
        }
        int citizenId = offer.citizenCharacterId();
        Persona persona = hands.persona(HiredHands.hireId(citizenId));
        if (persona == null) {
            return false;
        }
        String normalized = PhraseMatcher.normalize(text);
        if (DECLINE.matcher(normalized).matches()) {
            world.residents().whisper(persona, speaker, hands.voice(citizenId, "no worries. i'll be around."));
            int speakerId = speaker.getId();
            world.executor().submit("hire.decline " + citizenId, () -> offers.declined(speakerId, citizenId));
            return true;
        }
        if (!ACCEPT.matcher(normalized).matches()) {
            return false;
        }
        if (LivingTalk.mayShow(speaker)) {
            new HireScreens(world).terms(speaker, citizenId, HireScreens.Context.OFFER);
        } else {
            world.residents().whisper(persona, speaker, hands.voice(citizenId, "cool. double-click me and we'll shake on it."));
        }
        return true;
    }

    // ---- targeting -----------------------------------------------------------------------

    private record Target(Persona persona, String remainder) { }

    private Target target(Character speaker, String normalized, boolean requireName) {
        String[] words = normalized.split(" ");
        List<ResidentRuntime.Session> here = speaker.getMap() == null ? List.of()
                : world.residents().residentsIn(speaker.getMap().getId());
        for (ResidentRuntime.Session session : here) {
            String name = session.persona.name().toLowerCase(Locale.ROOT);
            int index = indexOf(words, name);
            if (index >= 0) {
                if (world.roster().ownerOf(session.persona.id()) == speaker.getId()) {
                    return null; // owner commands go to the companion runtime
                }
                return new Target(session.persona, without(words, index));
            }
        }
        for (Persona persona : world.content().personas().values()) {
            int index = indexOf(words, persona.name().toLowerCase(Locale.ROOT));
            if (index >= 0 && world.roster().ownerOf(persona.id()) != speaker.getId()) {
                return new Target(persona, without(words, index)); // named but not here: replies come by whisper
            }
        }
        if (requireName) {
            return null;
        }
        ResidentRuntime.Session nearest = null;
        double best = PROXIMITY_PX;
        for (ResidentRuntime.Session session : here) {
            Character agent = session.agent();
            if (agent == null || world.roster().ownerOf(session.persona.id()) == speaker.getId()) {
                continue;
            }
            double distance = agent.getPosition().distance(speaker.getPosition());
            if (distance < best) {
                best = distance;
                nearest = session;
            }
        }
        if (nearest != null && directed(normalized) && !openContentQuestion(normalized)) {
            return new Target(nearest.persona, normalized);
        }
        return null;
    }

    /**
     * A content question nobody was named in ("wheres the job advancement npc", "how do i join kpq") is for the
     * townsfolk and companions who know, not the nearest resident's quest and contract patterns.
     */
    private static boolean openContentQuestion(String normalized) {
        ContentChat knowledge = ContentChat.shared();
        if (!knowledge.on(ContentChat.Host.RESIDENTS) && !knowledge.on(ContentChat.Host.CITIZENS)
                && !knowledge.on(ContentChat.Host.COMPANIONS)) {
            return false; // knowledge off: no detector pass on every line near residents
        }
        Kind kind = ContentChat.kind(normalized);
        return kind != null && kind != Kind.THANKS && kind != Kind.FOLLOW_UP;
    }

    private static boolean directed(String normalized) {
        return GREET.matcher(normalized).matches() || ASK_QUEST.matcher(normalized).find()
                || ACCEPT.matcher(normalized).matches() || TURN_IN.matcher(normalized).find()
                || ASK_JOIN.matcher(normalized).find() || WHO.matcher(normalized).find()
                || THANKS.matcher(normalized).matches() || PAY.matcher(normalized).matches();
    }

    private static int indexOf(String[] words, String name) {
        for (int index = 0; index < words.length; index++) {
            if (words[index].equals(name)) {
                return index;
            }
        }
        return -1;
    }

    private static String without(String[] words, int index) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i != index) {
                out.append(words[i]).append(' ');
            }
        }
        return out.toString().trim();
    }

    // ---- conversation --------------------------------------------------------------------

    private boolean converse(Character speaker, Persona persona, String normalized, String original) {
        remember(speaker, persona, original);
        if (normalized.isEmpty() || GREET.matcher(normalized).matches()) {
            greet(speaker, persona);
            return true;
        }
        if (TALK.matcher(normalized).matches()) {
            openMenu(speaker, persona);
            return true;
        }
        Matcher pay = PAY.matcher(normalized);
        if (pay.matches()) {
            long amount = Long.parseLong(pay.group(1)) * (normalized.endsWith("k") ? 1000L : 1L);
            world.residents().reply(persona, speaker, world.contracts().pay(speaker, persona, amount));
            return true;
        }
        if (knows(speaker, persona, withoutName(original, persona.name()))) {
            return true; // before the patterns below, which read "job" in "job advancement" and "join" in "join kpq"
        }
        if (ACCEPT.matcher(normalized).matches()) {
            if (!world.quests().accept(speaker, persona)) {
                if (world.contracts().blocker(speaker, persona) == null
                        && world.bonds().peek(speaker.getId(), persona.id()) != null
                        && world.bonds().peek(speaker.getId(), persona.id()).contractState()
                        == server.living.store.LivingStore.ContractState.OFFERED) {
                    world.residents().reply(persona, speaker, world.contracts().askToJoin(speaker, persona));
                } else {
                    sayLine(speaker, persona, "idle", "nothing to accept right now.");
                }
            }
            return true;
        }
        if (TURN_IN.matcher(normalized).find()) {
            if (!world.quests().turnIn(speaker, persona)) {
                sayLine(speaker, persona, "nothingDue", "you don't owe me anything.");
            }
            return true;
        }
        if (ASK_JOIN.matcher(normalized).find()) {
            world.residents().reply(persona, speaker, world.contracts().askToJoin(speaker, persona));
            return true;
        }
        if (ASK_QUEST.matcher(normalized).find()) {
            if (!world.quests().offer(speaker, persona)) {
                if (world.quests().turnIn(speaker, persona)) {
                    return true;
                }
                sayLine(speaker, persona, "noWork", "nothing for you right now.");
            }
            return true;
        }
        if (WHO.matcher(normalized).find()) {
            sayLine(speaker, persona, "who", persona.name() + (persona.title().isEmpty() ? "" : ", " + persona.title()) + ".");
            world.bonds().bond(speaker, persona);
            return true;
        }
        Matcher about = ASK_ABOUT.matcher(normalized);
        if (about.matches() && (about.group(2).indexOf(' ') < 0 || longTopics(about.group(2)))) {
            askAbout(speaker, persona, about.group(2));
            return true;
        }
        if (THANKS.matcher(normalized).matches()) {
            sayLine(speaker, persona, "thanks", "sure.");
            return true;
        }
        if (GOODBYE.matcher(normalized).matches()) {
            sayLine(speaker, persona, "bye", "later.");
            return true;
        }
        if (DECLINE.matcher(normalized).matches()) {
            sayLine(speaker, persona, "declined", "suit yourself.");
            return true;
        }
        return judged(speaker, persona, normalized, original);
    }

    private boolean judged(Character speaker, Persona persona, String normalized, String original) {
        if (judge.overBudget(speaker.getId())) {
            sayLine(speaker, persona, "speakPlainly", "speak plainly, friend.");
            return true;
        }
        Optional<ResidentIntentJudge.Decision> decision = judge.judge(speaker, persona, original, normalized,
                new ArrayList<>(turns(speaker, persona)));
        if (decision.isEmpty()) {
            sayLine(speaker, persona, "idle", "hm.");
            return true;
        }
        ResidentIntentJudge.Decision d = decision.get();
        log.info("[typesafe-resident] speaker={} persona={} message=\"{}\" -> {} conf={} {}", speaker.getName(),
                persona.id(), original, d.intent(), String.format(Locale.ROOT, "%.2f", d.confidence()),
                d.act() ? "ACT" : d.ask() ? "ASK" : "IGNORE");
        if (d.act()) {
            switch (d.intent()) {
                case ASK_QUEST -> { if (!world.quests().offer(speaker, persona)) { sayLine(speaker, persona, "noWork", "nothing for you right now."); } }
                case ACCEPT -> { if (!world.quests().accept(speaker, persona)) { sayLine(speaker, persona, "idle", "nothing to accept right now."); } }
                case TURN_IN -> { if (!world.quests().turnIn(speaker, persona)) { sayLine(speaker, persona, "nothingDue", "you don't owe me anything."); } }
                case ASK_JOIN -> world.residents().reply(persona, speaker, world.contracts().askToJoin(speaker, persona));
                case ASK_ABOUT -> askAbout(speaker, persona, longTopics(aboutTopic(normalized)) ? aboutTopic(normalized) : lastWord(normalized));
                case GOODBYE -> sayLine(speaker, persona, "bye", "later.");
                case SMALL_TALK -> sayLine(speaker, persona, "smallTalk", "mm.");
                case NOT_FOR_ME -> { }
            }
            return true;
        }
        if (d.ask()) {
            String hint = switch (d.intent()) {
                case ASK_QUEST -> "looking for work? say 'quest'.";
                case ACCEPT -> "taking the job? say 'accept'.";
                case TURN_IN -> "done already? say 'done'.";
                case ASK_JOIN -> "want me along? say 'join'.";
                case ASK_ABOUT -> "asking about someone? say 'tell me about <name>'.";
                default -> null;
            };
            if (hint != null) {
                world.residents().reply(persona, speaker, hint);
                return true;
            }
        }
        sayLine(speaker, persona, "idle", "hm.");
        return true;
    }

    /**
     * Double-clicking your own companion opens its card and a Resident opens its NPC window, as clicking an
     * NPC does; a citizen whose offer to stay on with you is open shows its terms (P4), and so does a citizen who is your
     * guest in your party (its seat is yours: the way to hire without a Roster book); another player's companion, any
     * other citizen, a plain Agent and yourself keep the character info window.
     */
    public boolean onCharacterClicked(Character clicker, Character target) {
        if (AgentPresence.isAgent(clicker) || !AgentPresence.isAgent(target)) {
            return false;
        }
        switch (world.directory().resolve(clicker, target)) {
            case CompanionDirectory.Relation.OwnCompanion own -> {
                new CompanionMenus(world).card(clicker, own.entry());
                return true;
            }
            case CompanionDirectory.Relation.BorrowedCompanion borrowed -> {
                // lent to you by its owner (CompanionLoans): its borrowed card, else the stock char info
                return new CompanionMenus(world).borrowedCard(clicker, borrowed.personaId());
            }
            case CompanionDirectory.Relation.HireOfferPending offer -> {
                // its terms, or enableActions when no window may open now; char info's Party button is unaffected
                new HireScreens(world).terms(clicker, offer.characterId(), HireScreens.Context.OFFER);
                return true;
            }
            case CompanionDirectory.Relation.Citizen citizen -> {
                CompanionSlots.Reservation seat = world.slots().reservation(CompanionSlots.characterKey(citizen.characterId()));
                if (seat == null || seat.kind() != CompanionSlots.Kind.GUEST || seat.ownerId() != clicker.getId()) {
                    return false; // not your guest: the stock char info
                }
                new HireScreens(world).terms(clicker, citizen.characterId(), HireScreens.Context.HIRE_LIST);
                return true;
            }
            case CompanionDirectory.Relation.Resident resident -> {
                // a hired citizen let go is a citizen again: it offers no quests (stock char info)
                Persona persona = LivingContent.isHire(resident.personaId()) ? null : world.content().persona(resident.personaId());
                if (persona == null || world.roster().isOwned(persona.id())) {
                    return false;
                }
                openMenu(clicker, persona);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** The structured conversation: what this persona can do for the player right now. */
    public void openMenu(Character speaker, Persona persona) {
        world.bonds().bond(speaker, persona);
        List<LivingTalk.Option> options = new ArrayList<>();
        for (var quest : world.quests().available(speaker, persona)) {
            options.add(new LivingTalk.Option("#b" + quest.title() + "#k", () -> world.quests().offer(speaker, persona, quest)));
        }
        for (var state : world.quests().active(speaker)) {
            var quest = world.content().quest(state.questId());
            var step = quest == null ? null : quest.step(state.stepIndex());
            boolean sentHere = step != null && step.type() == StepType.TALK_TO && persona.id().equals(step.persona());
            if (quest != null && (quest.persona().equals(persona.id()) || sentHere)) {
                options.add(new LivingTalk.Option("#d" + quest.title() + " (in progress)#k", () -> {
                    if (!world.quests().turnIn(speaker, persona)) {
                        LivingTalk.open(speaker, portraitOf(persona))
                                .ok(quest.title() + ": " + world.quests().describe(quest.step(state.stepIndex()), state)).show();
                    }
                }));
            }
        }
        if (world.arcs().recruitable(persona) && world.roster().ownerOf(persona.id()) == 0) {
            options.add(new LivingTalk.Option("Would you travel with me?", () ->
                    world.residents().reply(persona, speaker, world.contracts().askToJoin(speaker, persona))));
        }
        if (!LivingContent.isHire(persona.id())) { // a hired citizen keeps no friendship
            options.add(new LivingTalk.Option("I brought you something.", () -> giftMenu(speaker, persona, null)));
        }
        options.add(new LivingTalk.Option("Who are you?", () -> sayLine(speaker, persona, "who",
                persona.name() + (persona.title().isEmpty() ? "" : ", " + persona.title()) + ".")));
        options.add(new LivingTalk.Option("Never mind.", () -> sayLine(speaker, persona, "bye", "later.")));
        List<String> greet = world.arcs().lines(persona, "greet");
        LivingTalk.open(speaker, portraitOf(persona))
                .menu((greet.isEmpty() ? "yes?" : pick(greet, speaker)) + "\r\n" + friendshipLine(speaker, persona), options)
                .show();
    }

    /** "#dFriendship Lv 4 Friend (35 points to Lv 5)#k" under a menu's greeting; empty before the first point. */
    public String friendshipLine(Character speaker, Persona persona) {
        var bond = world.bonds().peek(speaker.getId(), persona.id());
        return bond == null || bond.points() <= 0 ? "" : "#dFriendship " + world.friendship().describe(bond.points()) + "#k\r\n";
    }

    /**
     * What the player could give the persona (Friendship.giftable), one option per item; picking one hands it over.
     * {@code back} re-opens the window this came from (a companion's card), or null.
     */
    public void giftMenu(Character speaker, Persona persona, Runnable back) {
        List<LivingTalk.Option> options = new ArrayList<>();
        for (Item item : world.friendship().giftable(speaker)) {
            int itemId = item.getItemId();
            options.add(new LivingTalk.Option("#v" + itemId + "# #t" + itemId + "#",
                    () -> world.friendship().gift(speaker, persona, item)));
        }
        if (options.isEmpty()) {
            sayLine(speaker, persona, "gift.none", "you've got nothing to give. it's fine.");
            return;
        }
        LivingTalk talk = LivingTalk.open(speaker, portraitOf(persona))
                .menu("for me? what is it?\r\n" + friendshipLine(speaker, persona), options);
        if (back != null) {
            talk.back(back);
        }
        talk.show();
    }

    private void greet(Character speaker, Persona persona) {
        var bond = world.bonds().peek(speaker.getId(), persona.id());
        boolean first = bond == null;
        world.bonds().bond(speaker, persona);
        if (first) {
            world.bonds().addPoints(speaker, persona, 1, "met");
        }
        List<String> lines = world.arcs().lines(persona, first ? "greetFirst" : "greet");
        if (lines.isEmpty()) {
            lines = world.arcs().lines(persona, "greet");
        }
        world.residents().reply(persona, speaker, lines.isEmpty() ? "hey." : pick(lines, speaker));
        if (!world.quests().available(speaker, persona).isEmpty()) {
            speaker.dropMessage(5, persona.name() + " looks like they could use a hand. Ask about work.");
        }
    }

    private void askAbout(Character speaker, Persona persona, String topic) {
        String key = topic == null ? "" : topic.toLowerCase(Locale.ROOT);
        Persona other = world.content().personaByName(key);
        List<String> lines = world.arcs().lines(persona, "about." + (other == null ? key : other.id()));
        if (lines.isEmpty() && other == null && knows(speaker, persona, "where is " + key)) {
            return; // a game thing, not a person: what it is known for (where it is, where it drops)
        }
        if (lines.isEmpty()) {
            lines = world.arcs().lines(persona, "aboutUnknown");
        }
        world.residents().reply(persona, speaker, lines.isEmpty() ? "couldn't tell you." : pick(lines, speaker));
        if (other != null) {
            world.bonds().setFlag(speaker.getId(), persona.id(), "asked." + other.id());
        }
    }

    private void sayLine(Character speaker, Persona persona, String intent, String fallback) {
        List<String> lines = world.arcs().lines(persona, intent);
        world.residents().reply(persona, speaker, lines.isEmpty() ? fallback : pick(lines, speaker));
    }

    private static String pick(List<String> lines, Character speaker) {
        return lines.get(Math.floorMod(speaker.getId() + (int) (System.currentTimeMillis() / 9000L), lines.size()));
    }

    /**
     * A content question answered from server knowledge as this resident knows it (its level, its town's region,
     * everything its own quests mention), typed with a think-and-typing delay; false when the line is not one.
     */
    private boolean knows(Character speaker, Persona persona, String line) {
        ContentChat knowledge = ContentChat.shared();
        if (!knowledge.on(ContentChat.Host.RESIDENTS)) {
            return false;
        }
        ResidentRuntime.Session session = world.residents().session(persona.id());
        Character agent = session == null ? null : session.agent();
        List<ContentChat.Line> lines = knowledge.resident(session == null ? persona.id().hashCode() : session.characterId,
                agent == null ? persona.level().max() : agent.getLevel(), ContentChat.region(ResidentSpots.townMapId(persona.homeTown())),
                ownRefs(persona), speaker, line, System.currentTimeMillis());
        if (lines == null) {
            return false;
        }
        for (ContentChat.Line said : lines) {
            TimerManager.getInstance().schedule(TimerManager.SchedulerLane.LOW_PRIORITY,
                    () -> world.residents().reply(persona, speaker, said.text()), Math.max(1L, said.delayMs()));
        }
        return true;
    }

    /**
     * Its known world (docs/SERVER_KNOWLEDGE_PLAN.md 7.2): a past in Maple Island below 10, else around its home town; the
     * town itself and its persona's {@code knows:} list; a veteran when its strength is VETERAN or ELITE or it reached
     * knowledge.VETERAN_LEVEL; {@code knows: [prices]} knows what things go for.
     */
    void openKnowledge(Persona persona, int characterId, Character agent) {
        PersonalKnowledge personal = PersonalKnowledge.shared();
        if (!personal.on() || agent == null) {
            return;
        }
        try { // never fails the materialize: a failure here costs only what it knows
            int townMap = ResidentSpots.townMapId(persona.homeTown());
            String region = ContentChat.region(townMap);
            List<String> knows = new ArrayList<>(world.content().knows(persona.id()));
            boolean prices = knows.remove(PRICES);
            int level = agent.getLevel();
            boolean veteran = persona.strength() == Content.Strength.VETERAN || persona.strength() == Content.Strength.ELITE
                    || level >= YamlConfig.config.knowledge.VETERAN_LEVEL;
            personal.open(characterId, new PersonalKnowledge.Spec(KnownWorld.Past.SYNTHESIZED, persona.id().hashCode(), level,
                    band -> band < 10 ? "maple-island" : region, townMap == 0 ? IntList.EMPTY : IntList.of(townMap), knows, veteran,
                    prices));
        } catch (RuntimeException failure) {
            log.warn("Resident {}: opening its known world failed", persona.id(), failure);
        }
    }

    /** What the persona's own quests mention (mobs, items, maps, NPCs): always known to it. */
    private Set<Long> ownRefs(Persona persona) {
        Set<Long> refs = new HashSet<>();
        for (Quest quest : world.content().questsOf(persona.id())) {
            for (Step step : quest.steps()) {
                step.mobIds().forEach(mob -> refs.add(ContentChat.ref(EntityKind.MOB, mob)));
                step.mapIds().forEach(map -> refs.add(ContentChat.ref(EntityKind.MAP, map)));
                if (step.itemId() > 0) {
                    refs.add(ContentChat.ref(EntityKind.ITEM, step.itemId()));
                }
                if (step.take() != null) {
                    refs.add(ContentChat.ref(EntityKind.ITEM, step.take().itemId()));
                }
                for (String npc : new String[]{step.persona(), step.to()}) {
                    if (npc != null && npc.startsWith("npc:")) {
                        try {
                            refs.add(ContentChat.ref(EntityKind.NPC, Integer.parseInt(npc.substring(4))));
                        } catch (NumberFormatException ignored) {
                            // a malformed id names nothing
                        }
                    }
                }
            }
        }
        return refs;
    }

    /** The line as typed (punctuation kept, so "where?" stays a question) without the persona's name. */
    static String withoutName(String original, String name) {
        String out = original.replaceAll("(?i)(^|[^\\p{L}\\p{N}])" + Pattern.quote(name) + "(?=$|[^\\p{L}\\p{N}])", "$1");
        return out.replaceAll("^[\\s,.:;!]+", "").replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * A topic of several words ("who is the strongest here", "tell me about mia wolfe") is asked about only when it
     * names a persona or server knowledge can answer it for residents; otherwise the line keeps its old way to the
     * judged intent (one word, the last one).
     */
    boolean longTopics(String topic) {
        return ContentChat.shared().on(ContentChat.Host.RESIDENTS) || world.content().personaByName(topic) != null;
    }

    /** "tell me about steely throwing knives" -> "steely throwing knives"; otherwise the last word. */
    private static String aboutTopic(String normalized) {
        int about = normalized.lastIndexOf(" about ");
        return about >= 0 ? normalized.substring(about + " about ".length()) : lastWord(normalized);
    }

    private static String lastWord(String normalized) {
        String[] words = normalized.split(" ");
        return words[words.length - 1];
    }

    private void remember(Character speaker, Persona persona, String text) {
        Deque<String> turns = recentTurns.computeIfAbsent(speaker.getId() + ":" + persona.id(), k -> new ArrayDeque<>());
        synchronized (turns) {
            turns.addLast(text.length() > 80 ? text.substring(0, 80) : text);
            while (turns.size() > 2) {
                turns.removeFirst();
            }
        }
    }

    private Deque<String> turns(Character speaker, Persona persona) {
        return recentTurns.getOrDefault(speaker.getId() + ":" + persona.id(), new ArrayDeque<>());
    }

    // ---- roster commands -----------------------------------------------------------------

    /**
     * An untargeted "logout", then its "yes" or "no" ({@link CompanionChatVerbs#logoutTurn}): first, and on the raw
     * line (normalizing drops "yeah" and "ok" as fillers), so nothing nearby answers the "yes" instead. An owner who
     * also leads a plain Agent is asked by that Agent: the line goes straight to the Agent routes. True when the line
     * goes no further.
     */
    private boolean logoutTurn(Character owner, String text) {
        CompanionChatVerbs verbs = world.chatVerbs();
        CompanionChatVerbs.LogoutTurn logout = verbs.logoutTurn(owner, text.toLowerCase(Locale.ROOT).trim());
        if (logout == CompanionChatVerbs.LogoutTurn.CONFIRMED) {
            verbs.show(owner, "roster.logout", 5, () -> everyone(owner, entry -> outOrOnTheWay(owner, entry),
                    persona -> world.roster().bench(owner, persona)));
        } else if (logout == CompanionChatVerbs.LogoutTurn.TO_AGENTS) {
            AgentInteractionRuntime.handleLeaderChat(owner, text, AgentReplyChannel.MAP);
        }
        return logout != CompanionChatVerbs.LogoutTurn.NONE;
    }

    /** Every verb runs on companion-ops through {@link CompanionChatVerbs}; only the parsing happens on the chat thread. */
    private boolean rosterCommand(Character owner, String normalized) {
        CompanionChatVerbs verbs = world.chatVerbs();
        if (ROSTER.matcher(normalized).matches()) {
            verbs.show(owner, "roster.list", 6, () -> world.roster().summary(owner));
            return true;
        }
        Matcher m = CALL.matcher(normalized);
        if (m.matches()) {
            return owned(owner, m.group(2), persona -> verbs.say(owner, "roster.call",
                    () -> RosterVoice.line(persona, world.roster().call(owner, persona))));
        }
        m = BENCH.matcher(normalized);
        if (m.matches()) {
            return owned(owner, m.group(2), persona -> verbs.say(owner, "roster.bench",
                    () -> RosterVoice.line(persona, world.roster().bench(owner, persona))));
        }
        if (EVERYONE_PARK.matcher(normalized).matches() && world.roster().hasCompanions(owner)) {
            verbs.show(owner, "roster.park_all", 5, () -> everyone(owner, entry -> outOrOnTheWay(owner, entry),
                    persona -> world.roster().bench(owner, persona)));
            return true;
        }
        m = PARK.matcher(normalized);
        if (m.matches()) {
            return owned(owner, m.group(1), persona -> verbs.park(owner, persona));
        }
        m = SWAP.matcher(normalized);
        if (m.matches()) {
            Persona out = ownedPersona(owner, m.group(1));
            Persona in = ownedPersona(owner, m.group(3));
            if (out == null || in == null) {
                return false;
            }
            verbs.say(owner, "roster.swap", () -> RosterVoice.line(in, world.roster().swap(owner, out, in)));
            return true;
        }
        m = RELEASE.matcher(normalized);
        if (m.matches()) {
            return owned(owner, m.group(2), persona -> verbs.say(owner, "roster.release",
                    () -> world.contracts().dismiss(owner, persona)));
        }
        if (EVERYONE_BENCH.matcher(normalized).matches()) {
            verbs.show(owner, "roster.bench_all", 5, () -> everyone(owner, entry -> outOrOnTheWay(owner, entry),
                    persona -> world.roster().bench(owner, persona)));
            return true;
        }
        if (CALL_USUAL.matcher(normalized).matches()) {
            verbs.show(owner, "roster.call_all", 5, () -> everyone(owner, entry -> entry.state() != RosterState.ACTIVE,
                    persona -> world.roster().call(owner, persona)));
            return true;
        }
        if (EVERYONE_KEYSTONE.matcher(normalized).matches() && world.roster().hasCompanions(owner)) {
            verbs.show(owner, "roster.keystone_all", 5, () -> keystones(owner));
            return true;
        }
        if (EVERYONE_TICKET.matcher(normalized).matches() && world.roster().hasCompanions(owner)) {
            verbs.show(owner, "roster.ticket_all", 5, () -> tickets(owner));
            return true;
        }
        // "<name> <assignment or haul command>"
        String[] words = normalized.split(" ");
        if (words.length >= 2) {
            Persona persona = ownedPersona(owner, words[0]);
            if (persona != null) {
                return rosterCommandFor(owner, persona, normalized.substring(words[0].length()).trim(), false);
            }
            persona = ownedPersona(owner, words[words.length - 1]);
            if (persona != null) {
                String command = normalized.substring(0, normalized.length() - words[words.length - 1].length()).trim();
                return rosterCommandFor(owner, persona, command, false)
                        || agentCommandFor(owner, persona, command, normalized);
            }
        }
        return false;
    }

    /**
     * "&lt;Agent command&gt; &lt;name&gt;" in map chat (second deploy review 2026-10-10): the Agent routes take a name
     * only as the first word, and their phrase packs allow two unknown trailing words, so "stop tamsin", "heals off
     * tamsin" or "sell your junk tamsin" reached every companion out. A line the phrase packs read as an Agent command
     * goes to the named companion's Agent alone, as the line with the name first does (CompanionChatVerbs.toAgent).
     * To a companion that is not out with its owner it reaches no companion out: a sell-now command is the job SELL,
     * as "sell junk tamsin" is, and any other is nobody's: the owner is told it is not out (third deploy review
     * 2026-10-10: the line went nowhere, unanswered). "follow &lt;name&gt;" and "dismiss &lt;name&gt;" stay the
     * Agent routes' own.
     *
     * <p>Only a line whose words before the name are one whole command ({@link PhraseMatcher#wholeCommandFor}: no
     * unknown word, so the name is the line's one extra word) that takes no person ({@link #NAME_LAST_COMMANDS}), with
     * no preposition right before the name ({@link #BEFORE_AN_OBJECT}), is this companion's (fourth deploy review
     * 2026-10-10): "fame tamsin" made Tamsin fame her owner, not the crew fame her; "wait for tamsin" and "stay close
     * to tamsin" stopped only the laggard, or told the owner one on her way was not out while nobody waited; "stop
     * hadrik tamsin" stopped only Tamsin. Those, and the social lines that end with the name ("here you go tamsin",
     * "stop being silly tamsin", third deploy review 2026-10-10), go on to the untargeted Agent route as they always
     * did.
     */
    private boolean agentCommandFor(Character owner, Persona persona, String command, String line) {
        PhraseMatcher phrases = world.phrases();
        String canonical = phrases == null ? null : phrases.wholeCommandFor(command);
        String[] words = command.split(" ");
        if (canonical == null || !NAME_LAST_COMMANDS.contains(canonical)
                || BEFORE_AN_OBJECT.contains(words[words.length - 1])
                || AgentChatCommandClassifier.matchFollowTarget(line) != null
                || AgentDismissCommandService.dismissTarget(line) != null) {
            return false;
        }
        RosterEntry entry = world.roster().entry(owner.getId(), persona.id());
        if (entry != null && entry.state() == RosterState.ACTIVE) {
            world.chatVerbs().toAgent(owner, persona, command);
        } else if (SELL_NOW.contains(canonical)) {
            rosterCommandFor(owner, persona, canonical, false);
        } else {
            world.chatVerbs().say(owner, "roster.not_out", () -> persona.name() + " is not out with you right now.");
        }
        return true;
    }

    /** Companion-directed roster commands: assignments, camp, haul; {@code whispered}: the owner whispered the line to it. */
    private boolean rosterCommandFor(Character owner, Persona persona, String command, boolean whispered) {
        RosterEntry entry = world.roster().entry(owner.getId(), persona.id());
        if (entry == null || entry.state() == RosterState.RELEASED) {
            return false;
        }
        CompanionChatVerbs verbs = world.chatVerbs();
        int here = owner.getMapId(); // where the owner stands now, not when the verb runs
        switch (command) {
            case "come", "come here", "join me", "call", "come back", "get back here", "back to me", "return", "rejoin":
                verbs.say(owner, "roster.call", () -> RosterVoice.line(persona, world.roster().call(owner, persona))); return true;
            case "bench", "dismiss", "take a break", "go rest", "go to camp", "rest up":
                verbs.say(owner, "roster.bench", () -> RosterVoice.line(persona, world.roster().bench(owner, persona))); return true;
            case "park": verbs.park(owner, persona); return true;
            case "camp here", "make camp here":
                verbs.say(owner, "roster.camp", () -> RosterVoice.line(persona, world.roster().setCamp(owner, persona, here))); return true;
            case "what did you get", "show me the haul", "haul", "what do you have", "whats in the bag":
                verbs.show(owner, "roster.haul", 6, () -> world.roster().haulSummary(owner, persona)); return true;
            case "hand it over", "give me the haul", "give it here", "hand over the haul": verbs.say(owner, "roster.hand_over", () -> world.roster().handOver(owner, persona)); return true;
            case "send it", "mail it", "duey it", "send the haul": verbs.say(owner, "roster.send", () -> world.roster().sendIt(owner, persona)); return true;
            case "bank it", "deposit it", "bank the meso": verbs.say(owner, "roster.bank", () -> world.roster().bankIt(owner, persona)); return true;
            case "release", "youre free", "go home": verbs.say(owner, "roster.release", () -> world.contracts().dismiss(owner, persona)); return true;
            case "get your keystone", "get a keystone", "get the keystone", "go get your keystone", "go get a keystone",
                    "keystone": verbs.say(owner, "roster.keystone", () -> world.keystoneErrands().ask(owner, persona)); return true;
            case "get your ticket", "get a ticket", "get the ticket", "go get your ticket", "go get a ticket",
                    "get your entrance ticket", "get an entrance ticket", "ticket":
                verbs.say(owner, "roster.ticket", () -> world.ticketErrands().ask(owner, persona)); return true;
            default: break;
        }
        // "sell junk" to a companion out with its owner is the Agent's own sell-now command (plan 12.2 "Supplies and
        // loot"), so the bench job stays; benched or away it is the job SELL. A whisper goes on to the Agent bridge,
        // which has this one companion. A map line goes to its Agent from here: the Agent routes take a name only as the
        // first word, so a line naming it last reached every companion out (deploy review 2026-10-10)
        boolean sellNow = SELL_NOW.contains(command);
        if (sellNow && entry.state() == RosterState.ACTIVE) {
            if (whispered) {
                return false;
            }
            verbs.sellNow(owner, persona);
            return true;
        }
        // "<name> go farm meso [here]" delegates an active companion (plan 11.2); without "go" it sets the bench assignment
        boolean go = command.startsWith("go ");
        String job = go ? command.substring(3).trim() : command;
        int mapId = 0;
        if (go && job.endsWith(" here")) {
            job = job.substring(0, job.length() - " here".length()).trim();
            mapId = owner.getMapId();
        }
        // "go sell trash" is no errand (fourth deploy review 2026-10-10): the Agent route sells it now, as it always did
        Assignment assignment = sellNow ? Assignment.SELL : assignmentOf(job);
        int itemId = 0;
        int count = 0;
        if (assignment == null) {
            Matcher farm = FARM_ITEM.matcher(job);
            if (farm.matches()) {
                String itemText = farm.group(2).trim();
                count = farm.group(4) != null ? Integer.parseInt(farm.group(4)) : farm.group(1) != null ? Integer.parseInt(farm.group(1).trim()) : 0;
                itemId = itemFor(owner, persona, itemText);
                if (itemId < 0) {
                    return true; // it asked back which one
                }
                if (itemId == 0) {
                    owner.dropMessage(5, persona.name() + ": i don't know what '" + itemText + "' is.");
                    return true;
                }
                assignment = Assignment.FARM_ITEM;
            }
        }
        if (assignment != null) {
            Assignment duty = assignment;
            int item = itemId;
            int amount = count;
            int workMap = mapId;
            verbs.say(owner, go ? "roster.delegate" : "roster.assign", () -> RosterVoice.line(persona, go
                    ? world.roster().delegate(owner, persona, duty, item, amount, workMap)
                    : world.roster().assign(owner, persona, duty, item, amount)));
            return true;
        }
        Matcher wages = WAGES.matcher(command);
        if (wages.matches()) {
            if (wages.group(3) == null) {
                verbs.say(owner, "roster.wages", () -> world.roster().wagesStatus(owner, persona));
            } else {
                long amount = Long.parseLong(wages.group(3)) * (command.endsWith("k") ? 1000L : 1L);
                verbs.say(owner, "roster.pay_wages", () -> world.roster().payWages(owner, persona, amount));
            }
            return true;
        }
        return false;
    }

    /**
     * Runs {@code verb} (on companion-ops) for each companion {@code which} picks; one line each, except that the
     * leader cap is said once (the rest would only repeat it).
     */
    /** "everyone get tickets": each companion out or benched answers for its own Entrance Ticket to the Amorian Challenge. */
    private List<String> tickets(Character owner) {
        List<String> lines = new ArrayList<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona != null && (entry.state() == RosterState.ACTIVE || entry.state() == RosterState.BENCHED)) {
                lines.add(world.ticketErrands().ask(owner, persona));
            }
        }
        return lines;
    }

    /** "everyone get keystones": each companion out or benched answers for its own Crimsonwood Keystone. */
    private List<String> keystones(Character owner) {
        List<String> lines = new ArrayList<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona != null && (entry.state() == RosterState.ACTIVE || entry.state() == RosterState.BENCHED)) {
                lines.add(world.keystoneErrands().ask(owner, persona));
            }
        }
        return lines;
    }

    private List<String> everyone(Character owner, Predicate<RosterEntry> which,
                                  Function<Persona, RosterResult> verb) {
        List<String> lines = new ArrayList<>();
        boolean leaderCapSaid = false;
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona == null || !which.test(entry)) {
                continue;
            }
            RosterResult result = verb.apply(persona);
            if (result instanceof RosterResult.LeaderCapped) {
                if (leaderCapSaid) {
                    continue;
                }
                leaderCapSaid = true;
            }
            lines.add(RosterVoice.line(persona, result));
        }
        return lines;
    }

    /** Out with the owner, or called and on its way over: a logout, "park" and "everyone bench" send it to camp. */
    private boolean outOrOnTheWay(Character owner, RosterEntry entry) {
        return entry.state() == RosterState.ACTIVE
                || world.roster().travelMsLeft(owner.getId(), entry.personaId()) > 0L;
    }

    private static Assignment assignmentOf(String job) {
        return switch (job) {
            case "train", "level up", "grind" -> Assignment.TRAIN;
            case "farm meso", "make money", "earn meso", "farm mesos", "farm" -> Assignment.FARM_MESO;
            case "gather", "gather for my quests", "help with my quests", "farm my quest items" -> Assignment.GATHER;
            case "sell", "sell the haul", "sell it all", "sell junk", "sell and come back" -> Assignment.SELL;
            case "set up a stall", "run a stall", "open a stall", "sell in the fm" -> Assignment.STALL;
            case "rest", "do nothing", "take it easy" -> Assignment.REST;
            default -> null;
        };
    }

    private boolean owned(Character owner, String name, java.util.function.Consumer<Persona> action) {
        Persona persona = ownedPersona(owner, name);
        if (persona == null) {
            return false;
        }
        action.accept(persona);
        return true;
    }

    private Persona ownedPersona(Character owner, String name) {
        Persona persona = world.content().personaByName(name);
        if (persona == null) {
            persona = hiredHandNamed(owner, name); // hires are no content: a hired citizen goes by its own name
        }
        if (persona == null || world.roster().ownerOf(persona.id()) != owner.getId()) {
            return null;
        }
        return persona;
    }

    /** The owner's hired hand of that name, or null (no roster read for a player without companions). */
    private Persona hiredHandNamed(Character owner, String name) {
        if (!world.roster().hasCompanions(owner)) {
            return null;
        }
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona hire = LivingContent.isHire(entry.personaId()) ? world.content().persona(entry.personaId()) : null;
            if (hire != null && hire.name().equalsIgnoreCase(name)) {
                return hire;
            }
        }
        return null;
    }

    /**
     * The item a farm order names (docs/SERVER_KNOWLEDGE_PLAN.md 8.3): its id; 0 when unknown; -1 when it asked the owner
     * back which one. With server knowledge players' names and slang resolve ("steelies", "red pots"), and there is no
     * prefix scan (it turns "snail" into a cash pet); without it, {@link #itemIdFor} as before.
     */
    private static int itemFor(Character owner, Persona persona, String text) {
        WorldKnowledge k = Decisions.active();
        if (k == null) {
            return itemIdFor(text);
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notNumeric) {
            NameIndex.Resolution resolution = k.names().resolveSlot(text, List.of(EntityKind.ITEM));
            if (resolution.tie()) {
                owner.dropMessage(5, persona.name() + ": " + resolution.candidates().get(0).entity().say() + " or "
                        + resolution.candidates().get(1).entity().say() + "?");
                return -1;
            }
            return resolution.best().map(candidate -> candidate.entity().id()).orElse(0);
        }
    }

    private static int itemIdFor(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notNumeric) {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            String wanted = text.toLowerCase(Locale.ROOT);
            for (var pair : ii.getAllItems()) {
                if (pair.getRight() != null && pair.getRight().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return pair.getLeft();
                }
            }
            for (var pair : ii.getAllItems()) {
                if (pair.getRight() != null && pair.getRight().toLowerCase(Locale.ROOT).startsWith(wanted)) {
                    return pair.getLeft();
                }
            }
            return 0;
        }
    }
}
