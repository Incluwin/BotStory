package server.living.config;

import server.living.config.Content.Arc;
import server.living.config.Content.Contract;
import server.living.config.Content.Loadout;
import server.living.config.Content.Persona;
import server.living.config.Content.Phrase;
import server.living.config.Content.Quest;
import server.living.config.Content.Rumor;
import server.living.config.Content.Scene;
import server.living.config.Content.Story;
import server.living.config.Content.StoryChapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything loaded from the content directory, indexed by id. Immutable once built. A hired citizen's id
 * ({@code hire:<characterId>}, P4) is never content: {@link #persona} resolves it through the resolver HiredHands
 * installs, and {@link #personas} never lists it.
 */
public final class LivingContent {
    /** The persona id prefix of a hired citizen ({@code hire:<characterId>}); no authored persona may use it. */
    public static final String HIRE_PREFIX = "hire:";
    /** The contract every hired citizen works under (contracts/hired-hand.yaml); no persona lists it. */
    public static final String HIRED_HAND_CONTRACT = "hired-hand";
    /** How {@code hire:} ids resolve (HiredHands installs it; this package knows nothing of the roster); null: they don't. */
    private static volatile Function<String, Persona> hireResolver;

    private final Map<String, Persona> personas;
    private final Map<String, Persona> personasByName;
    private final Map<String, Quest> quests;
    private final Map<String, Contract> contracts;
    private final Map<String, Arc> arcs;
    private final Map<String, Rumor> rumors;
    private final Map<String, Loadout> loadouts;
    private final List<Phrase> phrases;
    /** quest id -> client quest-log id (Quest.wz), from quest-log-ids.yaml; empty until exported. */
    private final Map<String, Integer> questLogIds;
    /** persona id -> NPC id showing that Resident's own look (Npc.wz/String.wz), from portrait-npc-ids.yaml. */
    private final Map<String, Integer> portraitNpcIds;
    /** persona id -> its {@code knows:} names (plan 7.7), in file order; personas without one are absent. */
    private final Map<String, List<String>> personaKnows;
    /** Personal story arcs (F6) by id, and the scenes their SCENE steps stage. */
    private final Map<String, Story> stories;
    private final Map<String, Scene> scenes;
    /** A story chapter's quest id -> its story, and -> the client quest-log id it keeps until the client patch. */
    private final Map<String, Story> storyByQuest;
    private final Map<String, Integer> storyLogIds;
    private final List<String> warnings;

    LivingContent(Map<String, Persona> personas,
                  Map<String, Quest> quests,
                  Map<String, Contract> contracts,
                  Map<String, Arc> arcs,
                  Map<String, Rumor> rumors,
                  Map<String, Loadout> loadouts,
                  List<Phrase> phrases,
                  Map<String, Integer> questLogIds,
                  Map<String, Integer> portraitNpcIds,
                  List<String> warnings) {
        this(personas, quests, contracts, arcs, rumors, loadouts, phrases, questLogIds, portraitNpcIds, Map.of(), warnings);
    }

    LivingContent(Map<String, Persona> personas,
                  Map<String, Quest> quests,
                  Map<String, Contract> contracts,
                  Map<String, Arc> arcs,
                  Map<String, Rumor> rumors,
                  Map<String, Loadout> loadouts,
                  List<Phrase> phrases,
                  Map<String, Integer> questLogIds,
                  Map<String, Integer> portraitNpcIds,
                  Map<String, List<String>> personaKnows,
                  List<String> warnings) {
        this(personas, quests, contracts, arcs, rumors, loadouts, phrases, questLogIds, portraitNpcIds, personaKnows,
                Map.of(), Map.of(), warnings);
    }

    LivingContent(Map<String, Persona> personas,
                  Map<String, Quest> quests,
                  Map<String, Contract> contracts,
                  Map<String, Arc> arcs,
                  Map<String, Rumor> rumors,
                  Map<String, Loadout> loadouts,
                  List<Phrase> phrases,
                  Map<String, Integer> questLogIds,
                  Map<String, Integer> portraitNpcIds,
                  Map<String, List<String>> personaKnows,
                  Map<String, Story> stories,
                  Map<String, Scene> scenes,
                  List<String> warnings) {
        this.personas = Collections.unmodifiableMap(new LinkedHashMap<>(personas));
        Map<String, Persona> byName = new LinkedHashMap<>();
        for (Persona persona : personas.values()) {
            byName.put(persona.name().toLowerCase(Locale.ROOT), persona);
        }
        this.personasByName = Collections.unmodifiableMap(byName);
        this.quests = Collections.unmodifiableMap(new LinkedHashMap<>(quests));
        this.contracts = Collections.unmodifiableMap(new LinkedHashMap<>(contracts));
        this.arcs = Collections.unmodifiableMap(new LinkedHashMap<>(arcs));
        this.rumors = Collections.unmodifiableMap(new LinkedHashMap<>(rumors));
        this.loadouts = Collections.unmodifiableMap(new LinkedHashMap<>(loadouts));
        this.phrases = List.copyOf(phrases);
        this.questLogIds = Collections.unmodifiableMap(new LinkedHashMap<>(questLogIds));
        this.portraitNpcIds = Collections.unmodifiableMap(new LinkedHashMap<>(portraitNpcIds));
        Map<String, List<String>> knows = new LinkedHashMap<>();
        personaKnows.forEach((id, names) -> knows.put(id, List.copyOf(names)));
        this.personaKnows = Collections.unmodifiableMap(knows);
        this.stories = Collections.unmodifiableMap(new LinkedHashMap<>(stories));
        this.scenes = Collections.unmodifiableMap(new LinkedHashMap<>(scenes));
        Map<String, Story> byQuest = new LinkedHashMap<>();
        Map<String, Integer> logIds = new LinkedHashMap<>();
        for (Story story : stories.values()) {
            for (StoryChapter chapter : story.chapters()) {
                byQuest.put(chapter.quest(), story);
                logIds.put(chapter.quest(), chapter.logId());
            }
            if (story.rival() != null) {
                byQuest.put(story.rival().quest(), story);
                logIds.put(story.rival().quest(), story.rival().logId());
            }
        }
        this.storyByQuest = Collections.unmodifiableMap(byQuest);
        this.storyLogIds = Collections.unmodifiableMap(logIds);
        this.warnings = List.copyOf(warnings);
    }

    public static LivingContent empty() {
        return new LivingContent(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(), List.of());
    }

    public Map<String, Integer> questLogIds() {
        return questLogIds;
    }

    /** The Quest.wz id that mirrors this quest in the client quest log, or 0 when none is assigned. */
    public int questLogId(String questId) {
        Integer id = questId == null ? null : questLogIds.get(questId);
        return id == null ? 0 : id;
    }

    public Map<String, Integer> portraitNpcIds() {
        return portraitNpcIds;
    }

    /** The NPC id rendered from this Resident's own look, or 0 when none is generated yet. */
    public int portraitNpcId(String personaId) {
        Integer id = personaId == null ? null : portraitNpcIds.get(personaId);
        return id == null ? 0 : id;
    }

    public Map<String, Persona> personas() {
        return personas;
    }

    /** A persona's {@code knows:} names ("prices", "free market", "ellinia"), in file order; empty when it has none. */
    public List<String> knows(String personaId) {
        List<String> names = personaId == null ? null : personaKnows.get(personaId);
        return names == null ? List.of() : names;
    }

    /** An authored persona by id, or a hired citizen's synthetic one through the installed resolver (null without one). */
    public Persona persona(String id) {
        if (isHire(id)) {
            Function<String, Persona> resolver = hireResolver;
            return resolver == null ? null : resolver.apply(id);
        }
        return id == null ? null : personas.get(id);
    }

    /** True for a hired citizen's persona id ({@code hire:<characterId>}). */
    public static boolean isHire(String personaId) {
        return personaId != null && personaId.startsWith(HIRE_PREFIX);
    }

    /** The character id in a {@code hire:<characterId>} id; 0 for any other id. */
    public static int hireCharacterId(String personaId) {
        if (!isHire(personaId)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(personaId.substring(HIRE_PREFIX.length())));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    /** Installs how {@code hire:} ids resolve for every content snapshot (HiredHands, at start); null uninstalls. */
    public static void installHireResolver(Function<String, Persona> resolver) {
        hireResolver = resolver;
    }

    public Persona personaByName(String name) {
        return name == null ? null : personasByName.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, Quest> quests() {
        return quests;
    }

    public Quest quest(String id) {
        return id == null ? null : quests.get(id);
    }

    public List<Quest> questsOf(String personaId) {
        List<Quest> out = new ArrayList<>();
        for (Quest quest : quests.values()) {
            if (quest.persona().equals(personaId)) {
                out.add(quest);
            }
        }
        return out;
    }

    public Map<String, Contract> contracts() {
        return contracts;
    }

    public Contract contract(String id) {
        return id == null ? null : contracts.get(id);
    }

    public Map<String, Arc> arcs() {
        return arcs;
    }

    public Arc arc(String id) {
        return id == null ? null : arcs.get(id);
    }

    public Map<String, Rumor> rumors() {
        return rumors;
    }

    public Rumor rumor(String id) {
        return id == null ? null : rumors.get(id);
    }

    public Map<String, Loadout> loadouts() {
        return loadouts;
    }

    public Loadout loadout(String id) {
        return id == null ? null : loadouts.get(id);
    }

    public List<Phrase> phrases() {
        return phrases;
    }

    public Map<String, Story> stories() {
        return stories;
    }

    public Story story(String id) {
        return id == null ? null : stories.get(id);
    }

    /** The persona's personal story arc (F6), or null; a hire has none. */
    public Story storyOf(String personaId) {
        if (personaId == null || isHire(personaId)) {
            return null;
        }
        for (Story story : stories.values()) {
            if (story.persona().equals(personaId)) {
                return story;
            }
        }
        return null;
    }

    /** The story whose chapter (or race chapter) this quest is, or null for any other quest. */
    public Story storyOfQuest(String questId) {
        return questId == null ? null : storyByQuest.get(questId);
    }

    /** The client quest-log id a story chapter keeps for the client patch (not mirrored before it ships), or 0. */
    public int storyLogId(String questId) {
        Integer id = questId == null ? null : storyLogIds.get(questId);
        return id == null ? 0 : id;
    }

    public Map<String, Scene> scenes() {
        return scenes;
    }

    public Scene scene(String id) {
        return id == null ? null : scenes.get(id);
    }

    public List<String> warnings() {
        return warnings;
    }

    public String census() {
        int recruitable = 0;
        Map<String, Integer> perTown = new LinkedHashMap<>();
        for (Persona persona : personas.values()) {
            if (persona.recruitable()) {
                recruitable++;
            }
            perTown.merge(persona.homeTown(), 1, Integer::sum);
        }
        return "personas=" + personas.size() + " recruitable=" + recruitable + " towns=" + perTown
                + " quests=" + quests.size() + " contracts=" + contracts.size() + " arcs=" + arcs.size()
                + " rumors=" + rumors.size() + " loadouts=" + loadouts.size() + " phrases=" + phrases.size();
    }
}
