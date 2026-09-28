package server.living.resident;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.Content.StepType;
import server.living.phrases.PhraseMatcher;
import server.living.roster.CompanionChatVerbs;
import server.living.roster.CompanionDirectory;
import server.living.roster.RosterResult;
import server.living.roster.RosterVoice;
import server.living.roster.ui.CompanionMenus;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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
    private static final Pattern ASK_ABOUT = Pattern.compile("^(tell me about|who is|what about|whats with|know anything about|heard about) (\\w+)$");
    private static final Pattern TALK = Pattern.compile("^(talk|lets talk|menu|options|speak|chat|talk to me|can we talk|got a minute|a word)$");

    /** Borrowed townsperson portraits for personas with neither portraitNpcId nor a generated one (portrait-npc-ids.yaml). */
    private static final Map<String, int[]> PORTRAITS = Map.of(
            "lith-harbor", new int[] {1002000, 1002001, 1002002, 1002003, 1002005},
            "henesys", new int[] {1010100, 1012003, 1012106, 1012108, 1012109},
            "kerning-city", new int[] {1052002, 1052003, 1052004, 1052005, 1052006, 1052017, 1052102, 1052103},
            "perion", new int[] {1022002, 1022003, 1022004, 1022005},
            "ellinia", new int[] {1032002, 1032003, 1032100, 1032101, 1032102},
            "nautilus", new int[] {1092000, 1092001, 1092002, 1092003},
            "sleepywood", new int[] {1061000, 1061002, 1061003, 1061004});

    public static int portraitOf(Persona persona) {
        int ownLook = renderedPortraitOf(persona);
        if (ownLook > 0) {
            return ownLook;
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
    private static final Pattern FARM_ITEM = Pattern.compile("^farm (\\d+ )?(.+?)( x?(\\d+))?$");
    private static final Pattern WAGES = Pattern.compile("^(wages|pay wages|heres your pay|your pay|payday)( (\\d+)k?)?$");

    private final LivingWorld world;
    private final ResidentIntentJudge judge;
    private final Map<String, Deque<String>> recentTurns = new ConcurrentHashMap<>();

    public ResidentDialogue(LivingWorld world) {
        this.world = world;
        this.judge = new ResidentIntentJudge(world);
    }

    // ---- entry points --------------------------------------------------------------------

    /** Map chat; true when a Resident or the roster took the message. */
    public boolean onMapChat(Character speaker, String text) {
        if (AgentPresence.isAgent(speaker)) {
            return false;
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

    /** Whisper to a persona by name; true when the target is a persona. */
    public boolean onWhisper(Character speaker, String targetName, String text) {
        Persona persona = world.content().personaByName(targetName);
        if (persona == null || AgentPresence.isAgent(speaker)) {
            return false;
        }
        String normalized = PhraseMatcher.normalize(text);
        if (rosterCommandFor(speaker, persona, normalized)) {
            return true;
        }
        if (world.roster().ownerOf(persona.id()) == speaker.getId()) {
            return false; // companion commands travel the agent whisper bridge
        }
        return converse(speaker, persona, normalized, text) || true;
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
        if (nearest != null && directed(normalized)) {
            return new Target(nearest.persona, normalized);
        }
        return null;
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
        if (about.matches()) {
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
                case ASK_ABOUT -> askAbout(speaker, persona, lastWord(normalized));
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
     * NPC does; another player's companion, a citizen, a plain Agent and yourself keep the character info window.
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
            case CompanionDirectory.Relation.Resident resident -> {
                Persona persona = world.content().persona(resident.personaId());
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
        options.add(new LivingTalk.Option("Who are you?", () -> sayLine(speaker, persona, "who",
                persona.name() + (persona.title().isEmpty() ? "" : ", " + persona.title()) + ".")));
        options.add(new LivingTalk.Option("Never mind.", () -> sayLine(speaker, persona, "bye", "later.")));
        List<String> greet = world.arcs().lines(persona, "greet");
        LivingTalk.open(speaker, portraitOf(persona))
                .menu((greet.isEmpty() ? "yes?" : pick(greet, speaker)) + "\r\n", options)
                .show();
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
            verbs.show(owner, "roster.park_all", 5, () -> everyone(owner, RosterState.ACTIVE, true,
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
            verbs.show(owner, "roster.bench_all", 5, () -> everyone(owner, RosterState.ACTIVE, true,
                    persona -> world.roster().bench(owner, persona)));
            return true;
        }
        if (CALL_USUAL.matcher(normalized).matches()) {
            verbs.show(owner, "roster.call_all", 5, () -> everyone(owner, RosterState.ACTIVE, false,
                    persona -> world.roster().call(owner, persona)));
            return true;
        }
        // "<name> <assignment or haul command>"
        String[] words = normalized.split(" ");
        if (words.length >= 2) {
            Persona persona = ownedPersona(owner, words[0]);
            if (persona != null) {
                return rosterCommandFor(owner, persona, normalized.substring(words[0].length()).trim());
            }
            persona = ownedPersona(owner, words[words.length - 1]);
            if (persona != null) {
                return rosterCommandFor(owner, persona, normalized.substring(0, normalized.length() - words[words.length - 1].length()).trim());
            }
        }
        return false;
    }

    /** Companion-directed roster commands: assignments, camp, haul. */
    private boolean rosterCommandFor(Character owner, Persona persona, String command) {
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
            default: break;
        }
        // "<name> go farm meso [here]" delegates an active companion (plan 11.2); without "go" it sets the bench assignment
        boolean go = command.startsWith("go ");
        String job = go ? command.substring(3).trim() : command;
        int mapId = 0;
        if (go && job.endsWith(" here")) {
            job = job.substring(0, job.length() - " here".length()).trim();
            mapId = owner.getMapId();
        }
        Assignment assignment = assignmentOf(job);
        int itemId = 0;
        int count = 0;
        if (assignment == null) {
            Matcher farm = FARM_ITEM.matcher(job);
            if (farm.matches()) {
                String itemText = farm.group(2).trim();
                count = farm.group(4) != null ? Integer.parseInt(farm.group(4)) : farm.group(1) != null ? Integer.parseInt(farm.group(1).trim()) : 0;
                itemId = itemIdFor(itemText);
                if (itemId <= 0) {
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

    /** Runs {@code verb} (on companion-ops) for each companion that is in {@code state} ({@code in}) or is not; one line each. */
    private List<String> everyone(Character owner, RosterState state, boolean in,
                                  Function<Persona, RosterResult> verb) {
        List<String> lines = new ArrayList<>();
        for (RosterEntry entry : world.roster().roster(owner)) {
            Persona persona = world.content().persona(entry.personaId());
            if (persona != null && (entry.state() == state) == in) {
                lines.add(RosterVoice.line(persona, verb.apply(persona)));
            }
        }
        return lines;
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
        if (persona == null || world.roster().ownerOf(persona.id()) != owner.getId()) {
            return null;
        }
        return persona;
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
