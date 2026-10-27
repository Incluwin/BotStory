package server.living.roster;

import client.Character;
import client.inventory.Item;
import client.processor.npc.DueyProcessor;
import config.LivingWorldConfig;
import constants.game.ExpTable;
import constants.inventory.ItemConstants;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Population;
import server.living.store.LivingStore.CharacterEvent;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import service.NoteService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Letters (docs/BOT_CONCEPT_PLAN.md F3): what happened while you were away, by in-game note and Duey. A few seconds
 * after a login that ends a logout (or the first since the boot), each companion with news since then writes its owner
 * a normal note in its own voice ({@link LetterVoice}), sent as itself: the level its banked EXP is ready for, what it
 * found by the rarity rule ({@link #notable}), job advances, camp moves, gifts, and deaths as a soft count. At most
 * LETTERS_MAX_NOTES a login, within the 100-unread cap; the companions past them share one crew letter. One pink line
 * sums it up, with who went home and which citizens asked after them, in place of the old "While you were away" lines.
 *
 * <p>The news is living_event_log: bench.report rows (BenchLedger.flushReports), job.advanced, letters.gift and
 * citizen.asked, read after the owner's newest letters.away (written at a real logout) or letters.told (written by a
 * digest) marker, so a restart loses none of it and nothing is told twice. A Resident at friendship
 * LETTERS_GIFT_FRIENDSHIP_LEVEL or more sometimes mails its owner one such find from its haul by Duey, with a message
 * ({@link #mailGifts}, on the ledger tick). LETTERS_ENABLED is the kill switch.
 */
public final class CompanionLetters {
    private static final Logger log = LoggerFactory.getLogger(CompanionLetters.class);
    static final String ASKED_EVENT = "citizen.asked";
    static final String GIFT_EVENT = "letters.gift";
    static final String JOB_ADVANCED_EVENT = "job.advanced";
    /** Written at a real logout: what is logged after it happened while the owner was away. */
    static final String AWAY_EVENT = "letters.away";
    /** Written by a digest: what is logged after it is not told yet. */
    static final String TOLD_EVENT = "letters.told";
    private static final List<String> NEWS = List.of(BenchLedger.REPORT_EVENT, JOB_ADVANCED_EVENT, GIFT_EVENT, ASKED_EVENT);
    private static final List<String> MARKERS = List.of(AWAY_EVENT, TOLD_EVENT);
    /**
     * Rows one digest reads at most, the newest: about two weeks of a full bench's (ten companions, a row each a ledger
     * tick); a longer absence leaves out its oldest news.
     */
    private static final int WINDOW_ROWS = 20000;
    /** A note's length, as a player's note is held to. */
    static final int NOTE_MAX = 200;
    /** A note's sender name: the notes table's VARCHAR(13). */
    private static final int SENDER_MAX = 13;
    /** Finds one letter names. */
    private static final int DROPS_NAMED = 2;
    /** Names the pink line gives before it counts the rest. */
    private static final int NAMES_SAID = 3;
    private static final long WEEK_MS = 7L * 24 * 3_600_000L;
    private static final int MAX_LEVEL = 200;

    /** Where notes go: the server's NoteService (a test installs its own). */
    interface Notes {
        int room(String receiver);

        boolean send(String message, String sender, String receiver);

        void show(Character receiver);
    }

    /** What the letters read of an item: its name, its required level, and whether the price index's top tenth holds it. */
    interface ItemFacts {
        String name(int itemId);

        int requiredLevel(int itemId);

        boolean topDecile(int itemId);
    }

    static final Notes LIVE_NOTES = new Notes() {
        @Override
        public int room(String receiver) {
            NoteService service = Server.getInstance().getNoteService();
            return service == null ? 0 : service.inboxRoom(receiver);
        }

        @Override
        public boolean send(String message, String sender, String receiver) {
            NoteService service = Server.getInstance().getNoteService();
            return service != null && service.sendNormal(message, sender, receiver);
        }

        @Override
        public void show(Character receiver) {
            NoteService service = Server.getInstance().getNoteService();
            if (service != null) {
                service.show(receiver);
            }
        }
    };

    static final ItemFacts LIVE_ITEMS = new ItemFacts() {
        @Override
        public String name(int itemId) {
            return CompanionView.itemName(itemId);
        }

        @Override
        public int requiredLevel(int itemId) {
            Integer level = ItemInformationProvider.getInstance().getEquipLevelReq(itemId);
            return level == null ? 0 : level;
        }

        @Override
        public boolean topDecile(int itemId) {
            Population population = Population.get();
            return population != null && population.market().topDecile(itemId);
        }
    };

    /** One sentence of a letter: its part in the voice catalog and the slots it fills. */
    record Fact(String part, Map<String, String> slots) { }

    /** What one companion did over the window. */
    static final class News {
        long exp;
        /** Its level at its first EXP credit of the window (0: none). */
        int startLevel;
        long meso;
        int deaths;
        int camp;
        int parcels;
        boolean campMoved;
        final Map<Integer, Integer> items = new LinkedHashMap<>();
        final List<String> jobs = new ArrayList<>();

        void add(BenchReport.Row row) {
            exp += Math.max(0L, row.exp());
            if (row.level() > 0 && (startLevel <= 0 || row.level() < startLevel)) {
                startLevel = row.level();
            }
            meso += Math.max(0L, row.meso());
            deaths += Math.max(0, row.deaths());
            camp = row.camp() > 0 ? row.camp() : camp;
            if (row.items() != null) {
                row.items().forEach((item, quantity) -> items.merge(item, quantity, Integer::sum));
            }
        }
    }

    private final LivingWorld world;
    private final RosterService roster;
    private final Random random;
    volatile Notes notes = LIVE_NOTES;
    volatile ItemFacts items = LIVE_ITEMS;
    private volatile LetterVoice voice;
    /** Owners whose digest is on its way: a logout meanwhile writes no away marker over their news. */
    private final Set<Integer> due = ConcurrentHashMap.newKeySet();
    /** Owners that logged in since the boot: the first login after it brings the letters, as one after a logout does. */
    private final Set<Integer> seen = ConcurrentHashMap.newKeySet();
    /** Player id -> when a citizen last asked after them. */
    private final Map<Integer, Long> askedAtMs = new ConcurrentHashMap<>();

    CompanionLetters(LivingWorld world, RosterService roster) {
        this(world, roster, new Random());
    }

    CompanionLetters(LivingWorld world, RosterService roster, Random random) {
        this.world = world;
        this.roster = roster;
        this.random = random;
    }

    public boolean on() {
        LivingWorldConfig config = world.config();
        return config != null && config.LETTERS_ENABLED;
    }

    void voice(LetterVoice catalog) {
        this.voice = catalog;
    }

    private LetterVoice voice() {
        LetterVoice loaded = voice;
        if (loaded == null) {
            loaded = LetterVoice.load(Path.of(world.config().CONTENT_DIR, LetterVoice.FILE));
            voice = loaded;
        }
        return loaded;
    }

    // ---- login and logout -------------------------------------------------------------------

    /**
     * A human logged in (RosterService.onOwnerLogin, before it calls the usual lineup): one that ends a logout, or their
     * first since the boot, gets their letters LETTERS_DELAY_MS later, after the login's own show of their notes, so
     * they are shown once. The call-back reports are spent now: a companion seated at once says nothing the letters say.
     */
    void loggedIn(Character owner, boolean afterLogout) {
        if (!on() || AgentPresence.isAgent(owner)) {
            return;
        }
        int ownerId = owner.getId();
        boolean first = seen.add(ownerId);
        if ((afterLogout || first) && due.add(ownerId)) {
            BenchLedger ledger = world.bench();
            if (ledger != null) {
                for (RosterEntry entry : roster.roster(owner)) {
                    ledger.takeReport(ownerId, entry.personaId(), 0);
                }
            }
            roster.later("letters " + ownerId, () -> deliver(ownerId), world.config().LETTERS_DELAY_MS);
        }
    }

    /**
     * A real logout (RosterService.onOwnerLogout): the bench news so far is written, and what is logged after the away
     * marker happened while they were away; a digest still on its way keeps its news.
     */
    void loggedOut(Character owner) {
        if (!on() || AgentPresence.isAgent(owner)) {
            return;
        }
        BenchLedger ledger = world.bench();
        if (ledger != null) {
            ledger.flushReports(owner.getId());
        }
        if (!due.contains(owner.getId())) {
            world.store().event(owner.getId(), null, AWAY_EVENT, "logged out");
        }
    }

    // ---- the digest ---------------------------------------------------------------------

    /** The letters of an owner online now: their news since the away or told marker, sent, shown and summed up. */
    void deliver(int ownerId) {
        due.remove(ownerId);
        Character owner = roster.onlineCharacter(ownerId);
        if (owner == null || !on()) {
            return; // gone again before they came: the news waits for the next login
        }
        BenchLedger ledger = world.bench();
        if (ledger != null) {
            ledger.flushReports(ownerId);
        }
        List<CharacterEvent> window = world.store().eventsAfterMarker(ownerId, NEWS, MARKERS, WINDOW_ROWS);
        if (window == null) {
            return; // the read failed: nothing is marked told
        }
        List<Persona> moved = roster.campMoved(owner);
        String wentHome = roster.awayReleases(owner);
        Map<String, News> news = gather(ownerId, window, moved);
        Set<String> askers = new LinkedHashSet<>();
        for (CharacterEvent event : window) {
            if (ASKED_EVENT.equals(event.kind()) && event.detail() != null && !event.detail().isBlank()) {
                askers.add(event.detail());
            }
        }
        int ownerLevel = Math.max(1, owner.getLevel());
        Map<Persona, List<Fact>> writers = new LinkedHashMap<>();
        for (RosterEntry entry : roster.roster(owner)) {
            News one = news.get(entry.personaId());
            Persona persona = one == null ? null : world.content().persona(entry.personaId());
            List<Fact> facts = persona == null ? List.of() : facts(one, entry, ownerLevel);
            if (!facts.isEmpty()) {
                writers.put(persona, facts);
            }
        }
        int sent = send(owner, writers);
        String summary = summary(writers.keySet(), sent, wentHome, askers);
        if (summary != null) {
            owner.dropMessage(5, summary);
        }
        if (!window.isEmpty() || !writers.isEmpty()) {
            world.store().event(ownerId, null, TOLD_EVENT, sent + " notes, " + writers.size() + " with news, "
                    + askers.size() + " asked");
        }
        if (ledger != null) {
            for (RosterEntry entry : roster.roster(owner)) {
                ledger.takeReport(ownerId, entry.personaId(), 0); // the letters told it: no call-back says it again
            }
        }
    }

    /** Each companion's news over the window, by persona id; a camp move it made counts too. */
    private Map<String, News> gather(int ownerId, List<CharacterEvent> window, List<Persona> moved) {
        Map<String, News> out = new LinkedHashMap<>();
        for (CharacterEvent event : window) {
            if (event.personaId() == null) {
                continue;
            }
            switch (event.kind()) {
                case BenchLedger.REPORT_EVENT -> {
                    BenchReport.Row row = BenchReport.decode(event.detail());
                    if (row != null) {
                        out.computeIfAbsent(event.personaId(), id -> new News()).add(row);
                    }
                }
                case JOB_ADVANCED_EVENT -> {
                    String job = spokenJob(event.detail());
                    if (job != null) {
                        out.computeIfAbsent(event.personaId(), id -> new News()).jobs.add(job);
                    }
                }
                case GIFT_EVENT -> out.computeIfAbsent(event.personaId(), id -> new News()).parcels++;
                default -> { }
            }
        }
        for (Persona persona : moved) {
            RosterEntry entry = world.store().rosterEntry(ownerId, persona.id());
            if (entry != null) {
                News one = out.computeIfAbsent(persona.id(), id -> new News());
                one.campMoved = true;
                one.camp = entry.campMapId();
            }
        }
        return out;
    }

    /** "crusader" of a job.advanced detail ("CRUSADER from Tylus at map 211000001"); null when it has none. */
    static String spokenJob(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String job = detail.trim().split(" ", 2)[0];
        return job.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** One companion's sentences, most important first: job, level, finds, parcels, camp move, deaths, meso. */
    List<Fact> facts(News news, RosterEntry entry, int ownerLevel) {
        List<Fact> out = new ArrayList<>();
        for (String job : news.jobs) {
            out.add(new Fact("job", Map.of("job", job)));
        }
        int level = world.store().characterLevel(world.residents().characterIdOf(entry.personaId()));
        int ready = readyLevel(level, entry.accruedExp());
        if (news.exp > 0L && level > 0 && ready > level) {
            out.add(new Fact("level", Map.of("level", String.valueOf(ready))));
        } else if (news.exp > 0L && news.startLevel > 0 && level > news.startLevel) {
            out.add(new Fact("levelup", Map.of("level", String.valueOf(level))));
        }
        String camp = news.camp > 0 ? CompanionView.placeName(news.camp) : "camp";
        String drops = drops(news.items, ownerLevel);
        if (drops != null) {
            out.add(new Fact("drops", Map.of("drops", drops, "camp", camp)));
        }
        if (news.parcels > 0) {
            out.add(new Fact("parcel", Map.of()));
        }
        if (news.campMoved) {
            out.add(new Fact("camp", Map.of("camp", camp)));
        }
        if (news.deaths > 0) {
            out.add(new Fact("deaths", Map.of("times", softCount(news.deaths))));
        }
        if (news.meso > 0L) {
            out.add(new Fact("meso", Map.of("meso", BenchReport.meso(news.meso))));
        }
        return out;
    }

    /** The level {@code banked} EXP takes a companion of {@code level} to, as its call applies it. */
    static int readyLevel(int level, long banked) {
        int out = Math.max(1, level);
        long left = Math.max(0L, banked);
        while (out < MAX_LEVEL && left >= ExpTable.getExpNeededForLevel(out)) {
            left -= ExpTable.getExpNeededForLevel(out);
            out++;
        }
        return out;
    }

    /** "once", "twice", "a few times": deaths without the exact count. */
    static String softCount(int deaths) {
        return deaths <= 1 ? "once" : deaths == 2 ? "twice" : "a few times";
    }

    /** The finds worth a letter, the dearest first: "a Scroll for Gloves for DEX 60% and 2 Steely Throwing-Knives". */
    private String drops(Map<Integer, Integer> found, int ownerLevel) {
        List<Map.Entry<Integer, Integer>> worth = new ArrayList<>();
        for (Map.Entry<Integer, Integer> item : found.entrySet()) {
            if (item.getValue() > 0 && notable(item.getKey(), ownerLevel, items, world.config().LETTERS_EQUIP_LEVEL_SPAN)) {
                worth.add(item);
            }
        }
        if (worth.isEmpty()) {
            return null;
        }
        worth.sort((a, b) -> Integer.compare(rank(b.getKey()), rank(a.getKey())));
        List<String> named = new ArrayList<>();
        for (Map.Entry<Integer, Integer> item : worth.subList(0, Math.min(DROPS_NAMED, worth.size()))) {
            String name = items.name(item.getKey());
            named.add(item.getValue() == 1 ? article(name) + " " + name : item.getValue() + " " + name);
        }
        return String.join(" and ", named);
    }

    /** Scrolls first, then what the index prices in its top tenth, then gear. */
    private int rank(int itemId) {
        return ItemConstants.isEquipScroll(itemId) ? 2 : items.topDecile(itemId) ? 1 : 0;
    }

    private static String article(String name) {
        return !name.isEmpty() && "aeiouAEIOU".indexOf(name.charAt(0)) >= 0 ? "an" : "a";
    }

    /**
     * The rarity rule: a find is news when it is an equip whose required level is within {@code span} levels of its
     * owner's, an equip scroll, or anything the price index holds in its top tenth.
     */
    static boolean notable(int itemId, int ownerLevel, ItemFacts facts, int span) {
        if (ItemConstants.isEquipment(itemId) && Math.abs(facts.requiredLevel(itemId) - ownerLevel) <= span) {
            return true;
        }
        return ItemConstants.isEquipScroll(itemId) || facts.topDecile(itemId);
    }

    // ---- delivery ---------------------------------------------------------------------

    /**
     * Sends the letters: one each while LETTERS_MAX_NOTES (and the room left under the unread cap) allow, the rest in one
     * crew letter by the first of them; then shows them. How many notes went out.
     */
    private int send(Character owner, Map<Persona, List<Fact>> writers) {
        if (writers.isEmpty()) {
            return 0;
        }
        int max = Math.min(world.config().LETTERS_MAX_NOTES, notes.room(owner.getName()));
        if (max <= 0) {
            return 0;
        }
        List<Persona> order = new ArrayList<>(writers.keySet());
        int own = order.size() <= max ? order.size() : max - 1;
        int sent = 0;
        for (Persona persona : order.subList(0, own)) {
            sent += notes.send(letter(persona, owner.getName(), writers.get(persona)), sender(persona), owner.getName()) ? 1 : 0;
        }
        if (own < order.size()) {
            List<Persona> crew = order.subList(own, order.size());
            sent += notes.send(crewLetter(owner.getName(), crew, writers), sender(crew.get(0)), owner.getName()) ? 1 : 0;
        }
        if (sent > 0) {
            notes.show(owner);
        }
        return sent;
    }

    private static String sender(Persona persona) {
        return persona.name().length() <= SENDER_MAX ? persona.name() : persona.name().substring(0, SENDER_MAX);
    }

    /** One companion's letter: its opening, its sentences (the least important left out past NOTE_MAX), its closing. */
    String letter(Persona persona, String ownerName, List<Fact> facts) {
        String style = LetterVoice.styleOf(persona);
        Map<String, String> slots = Map.of("owner", ownerName, "self", persona.name());
        List<String> sentences = new ArrayList<>();
        for (Fact fact : facts) {
            String sentence = say(fact.part(), style, slots, fact.slots());
            if (sentence != null) {
                sentences.add(sentence);
            }
        }
        return fit(say("open", style, slots, Map.of()), sentences, say("close", style, slots, Map.of()));
    }

    /**
     * The crew letter: its writer's own first sentence, then a sentence of each other companion's most important news,
     * as many as fit.
     */
    String crewLetter(String ownerName, List<Persona> crew, Map<Persona, List<Fact>> writers) {
        Persona writer = crew.get(0);
        String style = LetterVoice.styleOf(writer);
        Map<String, String> slots = Map.of("owner", ownerName, "self", writer.name());
        List<String> sentences = new ArrayList<>();
        String own = say(writers.get(writer).get(0).part(), style, slots, writers.get(writer).get(0).slots());
        if (own != null) {
            sentences.add(own);
        }
        String crewOpen = say("crew_open", style, slots, Map.of());
        if (crewOpen != null) {
            sentences.add(crewOpen);
        }
        for (Persona other : crew.subList(1, crew.size())) {
            Fact top = writers.get(other).get(0);
            Map<String, String> theirs = new LinkedHashMap<>(top.slots());
            theirs.put("name", other.name());
            String sentence = say("crew_" + top.part(), style, slots, theirs);
            if (sentence != null) {
                sentences.add(sentence);
            }
        }
        return fit(say("open", style, slots, Map.of()), sentences, say("close", style, slots, Map.of()));
    }

    /** {@code part}'s line in the style, its slots filled; null when the catalog has none. */
    private String say(String part, String style, Map<String, String> slots, Map<String, String> more) {
        String line = voice().line(part, style, random);
        if (line == null) {
            return null;
        }
        for (Map.Entry<String, String> slot : more.entrySet()) {
            line = line.replace("{" + slot.getKey() + "}", slot.getValue());
        }
        for (Map.Entry<String, String> slot : slots.entrySet()) {
            line = line.replace("{" + slot.getKey() + "}", slot.getValue());
        }
        return line;
    }

    /** The letter that fits NOTE_MAX: sentences dropped from the end (one kept), then the closing, then cut. */
    static String fit(String open, List<String> sentences, String close) {
        for (int keep = sentences.size(); keep >= Math.min(1, sentences.size()); keep--) {
            String text = join(open, sentences.subList(0, keep), close);
            if (text.length() <= NOTE_MAX) {
                return text;
            }
        }
        String text = join(open, sentences.subList(0, Math.min(1, sentences.size())), null);
        return text.length() <= NOTE_MAX ? text : text.substring(0, NOTE_MAX);
    }

    private static String join(String open, List<String> sentences, String close) {
        List<String> parts = new ArrayList<>();
        if (open != null) {
            parts.add(open);
        }
        parts.addAll(sentences);
        if (close != null) {
            parts.add(close);
        }
        return String.join(" ", parts);
    }

    /**
     * The one pink line: "While you were away, Casse and Hadrik wrote to you; Delmar went home to Aquarium; Ranna asked
     * after you." Null when there is nothing to say.
     */
    static String summary(Set<Persona> writers, int sent, String wentHome, Set<String> askers) {
        List<String> parts = new ArrayList<>();
        if (!writers.isEmpty()) {
            parts.add(sent > 0 ? names(writers.stream().map(Persona::name).toList()) + " wrote to you"
                    : "your crew has news, but your notes are full");
        }
        if (wentHome != null) {
            parts.add(wentHome);
        }
        if (!askers.isEmpty()) {
            parts.add(names(List.copyOf(askers)) + " asked after you");
        }
        return parts.isEmpty() ? null : "While you were away, " + String.join("; ", parts) + ".";
    }

    /** "Casse", "Casse and Hadrik", "Casse, Hadrik and Bo", "Casse, Hadrik, Bo and 2 others". */
    static String names(List<String> names) {
        if (names.size() == 1) {
            return names.get(0);
        }
        if (names.size() <= NAMES_SAID) {
            return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
        }
        int others = names.size() - NAMES_SAID;
        return String.join(", ", names.subList(0, NAMES_SAID)) + " and " + others + (others == 1 ? " other" : " others");
    }

    // ---- gifts by Duey ------------------------------------------------------------------

    /**
     * Each ledger tick (BenchLedger.tick, companion-ops): a benched Resident (hires keep no friendship) at friendship
     * LETTERS_GIFT_FRIENDSHIP_LEVEL or more sometimes ({@code LETTERS_GIFT_CHANCE}) mails its owner one find from its
     * haul by Duey, with a message in its voice, LETTERS_GIFTS_PER_WEEK at most each.
     */
    void mailGifts(List<RosterEntry> benched, long nowMs) {
        if (!on()) {
            return;
        }
        for (RosterEntry entry : benched) {
            if (LivingContent.isHire(entry.personaId()) || random.nextDouble() >= world.config().LETTERS_GIFT_CHANCE) {
                continue;
            }
            try {
                gift(entry, nowMs);
            } catch (RuntimeException failure) {
                log.warn("gift of {} to {} failed", entry.personaId(), entry.characterId(), failure);
            }
        }
    }

    /**
     * Mails one find of the companion's haul to its owner: the dearest the rarity rule picks, one piece of it (an equip
     * with drop-like stats, as sendIt mails one), never minted: it leaves the haul. The owner online gets Duey's live
     * notice. True when it went.
     */
    boolean gift(RosterEntry entry, long nowMs) {
        LivingWorldConfig config = world.config();
        int ownerId = entry.characterId();
        Persona persona = world.content().persona(entry.personaId());
        if (persona == null || LivingContent.isHire(persona.id())
                || world.friendship().level(ownerId, persona.id()) < config.LETTERS_GIFT_FRIENDSHIP_LEVEL
                || world.store().countEvents(ownerId, persona.id(), GIFT_EVENT, nowMs - WEEK_MS) >= config.LETTERS_GIFTS_PER_WEEK) {
            return false;
        }
        int ownerLevel = Math.max(1, world.store().characterLevel(ownerId));
        HaulItem pick = null;
        for (HaulItem stack : world.store().haul(ownerId, persona.id())) {
            if (stack.quantity() > 0 && notable(stack.itemId(), ownerLevel, items, config.LETTERS_EQUIP_LEVEL_SPAN)
                    && (pick == null || rank(stack.itemId()) > rank(pick.itemId()))) {
                pick = stack;
            }
        }
        if (pick == null) {
            return false;
        }
        String camp = entry.campMapId() > 0 ? CompanionView.placeName(entry.campMapId()) : "camp";
        String message = say("gift", LetterVoice.styleOf(persona), Map.of("self", persona.name()),
                Map.of("camp", camp, "item", items.name(pick.itemId())));
        Item item = RosterService.mailItem(roster.mailedEquips, pick.itemId(), 1);
        if (!DueyProcessor.dueyCreatePackage(item, 0, message, sender(persona), ownerId)) {
            return false;
        }
        world.store().removeHaul(ownerId, persona.id(), pick.itemId(), 1);
        world.store().recordTransfer(ownerId, persona.id(), pick.itemId(), 1);
        world.store().event(ownerId, persona.id(), GIFT_EVENT, pick.itemId() + " " + items.name(pick.itemId()));
        CompanionViews.invalidate(world, ownerId, persona.id());
        DueyProcessor.dueyNotify(roster.onlineCharacter(ownerId));
        return true;
    }

    // ---- citizens asking after an absent friend ----------------------------------------------

    /** True when no citizen asked after this player in the last LETTERS_ASK_GAP_HOURS (PersonMemories.askAfter). */
    public boolean mayAsk(int playerId, long nowMs) {
        Long last = askedAtMs.get(playerId);
        return on() && (last == null || nowMs - last >= world.config().LETTERS_ASK_GAP_HOURS * 3_600_000L);
    }

    /** A citizen asked after this offline player in its chatter ("anyone seen Ann?"): their next login's letters say so. */
    public void asked(int playerId, String citizenName, long nowMs) {
        askedAtMs.put(playerId, nowMs);
        world.store().event(playerId, null, ASKED_EVENT, citizenName);
    }
}
