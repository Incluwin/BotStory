package server.living.story;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.Content.Story;
import server.living.config.Content.StoryChapter;
import server.living.config.Content.StoryRival;
import server.living.resident.LivingTalk;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Personal story arcs (docs/BOT_CONCEPT_PLAN.md F6.1, F6.6): what a companion's arc offers its owner and when it says
 * so. The state is {@link StoryState}'s, read from the bond and the chapter quests' rows. When a chapter opens (the
 * owner's friendship with the companion rose to its level, or at their login), the companion says its {@code nudge} line
 * once, in party chat at a calm moment, followed by a pink hint; its card then offers the chapter. A chapter's quest is
 * offered by nothing else: it is in no persona's questLines, so the Resident's own window never lists it, and only its
 * owner sees it. The race chapter (F6.6) is offered from either rival's card. Runs on companion-ops except
 * {@link #cardOptions}, which a card builds on the I/O thread (reads only).
 */
public final class StoryArcs {
    private static final Logger log = LoggerFactory.getLogger(StoryArcs.class);
    /** The story sweep: nudges, scenes and their closings, attended errands (nothing handed over while none waits). */
    public static final long SWEEP_MS = 1000L;
    static final String SOMETHING = "Something on your mind?";
    static final String ABOUT_EARLIER = "About earlier...";
    static final String READY = "I'm ready. Let's go.";
    static final String SETTLE_IT = "Let's settle the bet.";

    private final LivingWorld world;
    private final StoryScenes scenes;
    private final ErrandAttend errands;
    /** Owner id -> persona ids whose companion may have a chapter to tell them about. */
    private final Map<Integer, Set<String>> pending = new ConcurrentHashMap<>();

    public StoryArcs(LivingWorld world) {
        this(world, new StoryScenes(world), new ErrandAttend(world));
    }

    StoryArcs(LivingWorld world, StoryScenes scenes) {
        this(world, scenes, new ErrandAttend(world));
    }

    StoryArcs(LivingWorld world, StoryScenes scenes, ErrandAttend errands) {
        this.world = world;
        this.scenes = scenes;
        this.errands = errands;
    }

    boolean on() {
        return world.config().PERSONAL_ARCS_ENABLED;
    }

    public StoryScenes scenes() {
        return scenes;
    }

    /** Attended job errands (F6.3, JOB_ERRAND_ATTEND_ENABLED): JobErrands.leave's seam and the follow guard's. */
    public ErrandAttend errands() {
        return errands;
    }

    /** True while the sweep has work (a nudge, a scene or its closing, an attended errand). */
    public boolean any() {
        return !pending.isEmpty() || scenes.any() || errands.any();
    }

    /** The story sweep, on companion-ops. */
    public void tick() {
        if (errands.any()) {
            errands.tick(); // its own switch: off, each goes alone
        }
        if (!on()) {
            pending.clear();
            if (scenes.any()) {
                scenes.tick(); // aborts any scene still running, then says so
            }
            return;
        }
        nudges();
        scenes.tick();
    }

    /**
     * {@code !story <player>} (GM 5): each story's chapters and race as that player stands with them (stage, the level
     * each needs, their friendship), a scene running, an attended errand. Read-only, on companion-ops.
     */
    public List<String> report(int playerId) {
        List<String> out = new ArrayList<>();
        out.add("Personal arcs " + (on() ? "on" : "OFF") + ", attended errands "
                + (world.config().JOB_ERRAND_ATTEND_ENABLED ? "on" : "OFF") + ".");
        for (Story story : world.content().stories().values()) {
            StoryState.Facts facts = facts(playerId, story);
            StringBuilder line = new StringBuilder(story.id()).append(" (").append(story.persona())
                    .append(facts.owned() ? ", on their roster" : ", not theirs").append(", friendship Lv ")
                    .append(facts.friendship()).append("):");
            for (StoryChapter chapter : story.chapters()) {
                line.append(' ').append(chapter.id()).append(" Lv ").append(chapter.friendship()).append(' ')
                        .append(StoryState.of(story, chapter, facts));
            }
            if (story.rival() != null) {
                line.append("; race with ").append(story.rival().persona()).append(" (Lv ").append(facts.rivalFriendship())
                        .append(facts.rivalOwned() ? "" : ", not theirs").append(") ").append(StoryState.rival(story, facts));
            }
            out.add(line.toString());
        }
        StoryScenes.Run run = scenes.run(playerId);
        if (run != null) {
            out.add("scene running: " + run.scene.id() + " (" + run.questId + "), cast " + run.cast.keySet() + ", beat "
                    + run.beat + "/" + run.beats.size());
        }
        out.addAll(errands.report(playerId));
        return out;
    }

    // ---- what the stage reads ------------------------------------------------------------------

    /** The stories this persona is in: its own, and any whose race chapter names it as the rival. */
    List<Story> storiesOf(String personaId) {
        List<Story> out = new ArrayList<>();
        for (Story story : world.content().stories().values()) {
            if (story.persona().equals(personaId)
                    || story.rival() != null && story.rival().persona().equals(personaId)) {
                out.add(story);
            }
        }
        return out;
    }

    StoryState.Facts facts(int ownerId, Story story) {
        Map<String, QuestStatus> quests = new HashMap<>();
        for (StoryChapter chapter : story.chapters()) {
            status(ownerId, chapter.quest(), quests);
        }
        StoryRival rival = story.rival();
        if (rival != null) {
            status(ownerId, rival.quest(), quests);
        }
        return new StoryState.Facts(world.roster().ownerOf(story.persona()) == ownerId,
                world.friendship().level(ownerId, story.persona()), flags(ownerId, story.persona()), quests,
                rival != null && world.roster().ownerOf(rival.persona()) == ownerId,
                rival == null ? 0 : world.friendship().level(ownerId, rival.persona()),
                rival == null ? Set.of() : flags(ownerId, rival.persona()));
    }

    private void status(int ownerId, String questId, Map<String, QuestStatus> out) {
        QuestState state = world.store().questState(ownerId, questId);
        if (state != null) {
            out.put(questId, state.status());
        }
    }

    private Set<String> flags(int ownerId, String personaId) {
        BondService bonds = world.bonds();
        var bond = bonds == null ? null : bonds.peek(ownerId, personaId);
        return bond == null ? Set.of() : Set.copyOf(bond.flags());
    }

    // ---- the card ------------------------------------------------------------------------------

    /**
     * The card's story options for this companion (empty while off): the chapter it has to tell, the scene its owner is
     * due to go to, and the race with its rival; each opens as the answer to the click.
     */
    public List<LivingTalk.Option> cardOptions(Character owner, Persona persona) {
        List<LivingTalk.Option> out = new ArrayList<>();
        if (!on() || persona == null) {
            return out;
        }
        for (Story story : storiesOf(persona.id())) {
            StoryState.Facts facts = facts(owner.getId(), story);
            if (story.persona().equals(persona.id())) {
                StoryChapter chapter = StoryState.current(story, facts);
                if (chapter != null) {
                    option(owner, persona, story, chapter.quest(), StoryState.of(story, chapter, facts), out,
                            () -> tell(owner, persona, story, chapter));
                }
            }
            if (story.rival() != null) {
                StoryRival rival = story.rival();
                option(owner, persona, story, rival.quest(), StoryState.rival(story, facts), out,
                        () -> tellRival(owner, persona, story));
            }
        }
        return out;
    }

    private void option(Character owner, Persona persona, Story story, String questId, StoryState.Stage stage,
                        List<LivingTalk.Option> out, Runnable tell) {
        boolean race = story.rival() != null && questId.equals(story.rival().quest());
        switch (stage) {
            case READY, TOLD -> out.add(new LivingTalk.Option(race
                    ? "About you and " + otherName(story, persona) + "..." : SOMETHING, tell));
            case ACTIVE -> {
                if (onScene(owner, questId)) {
                    out.add(new LivingTalk.Option(race ? SETTLE_IT : READY, () -> scenes.ask(owner, story, questId)));
                } else {
                    out.add(new LivingTalk.Option(ABOUT_EARLIER, () -> world.quests().turnIn(owner, persona)));
                }
            }
            default -> { }
        }
    }

    private String otherName(Story story, Persona persona) {
        String other = story.persona().equals(persona.id()) ? story.rival().persona() : story.persona();
        Persona named = world.content().persona(other);
        return named == null ? other : named.name();
    }

    private boolean onScene(Character owner, String questId) {
        QuestState state = world.store().questState(owner.getId(), questId);
        Quest quest = world.content().quest(questId);
        Step step = state == null || quest == null ? null : quest.step(state.stepIndex());
        return step != null && step.type() == StepType.SCENE;
    }

    /** A chapter's conversation; its last option starts it (and, before a scene, asks if they are ready). */
    void tell(Character owner, Persona persona, Story story, StoryChapter chapter) {
        Quest quest = world.content().quest(chapter.quest());
        if (quest == null || !world.quests().offerable(owner, persona, quest)) {
            return;
        }
        world.bonds().setFlag(owner.getId(), persona.id(), StoryState.toldFlag(story, chapter.id()));
        boolean scene = quest.steps().stream().anyMatch(step -> step.type() == StepType.SCENE);
        StoryTalk.open(world, owner, persona, chapter.talk(), chapter.choice(), scene ? StoryTalk.LETS_GO : StoryTalk.GO_ON,
                () -> begin(owner, persona, story, quest));
    }

    /** The race chapter, told by whichever rival's card it was opened from (its quest is the story persona's). */
    void tellRival(Character owner, Persona speaker, Story story) {
        Quest quest = world.content().quest(story.rival().quest());
        Persona giver = world.content().persona(story.persona());
        if (quest == null || giver == null || !world.quests().offerable(owner, giver, quest)) {
            return;
        }
        world.bonds().setFlag(owner.getId(), story.persona(), StoryState.toldFlag(story, StoryState.RIVAL_CHAPTER));
        StoryTalk.open(world, owner, speaker, story.rival().talk(), null, StoryTalk.LETS_GO,
                () -> begin(owner, giver, story, quest));
    }

    private void begin(Character owner, Persona giver, Story story, Quest quest) {
        world.quests().begin(owner, giver, quest);
        world.store().event(owner.getId(), giver.id(), "story.chapter", quest.id());
        if (onScene(owner, quest.id())) {
            scenes.ask(owner, story, quest.id()); // the answer to the same click: the yes/no to go now
        }
    }

    /**
     * An attended errand ended with the advancement in front of its owner (F6.3): at friendship Lv 9, the story's last
     * chapter, when it is the one open (READY or TOLD), is told now, once no window waits.
     */
    void capstoneAfterErrand(Character owner, Persona persona) {
        Story story = world.content().storyOf(persona.id());
        if (!on() || story == null || story.chapters().isEmpty() || !LivingTalk.mayShow(owner)) {
            return;
        }
        StoryChapter last = story.chapters().get(story.chapters().size() - 1);
        StoryState.Facts facts = facts(owner.getId(), story);
        StoryState.Stage stage = StoryState.of(story, last, facts);
        if (facts.friendship() >= last.friendship() && StoryState.current(story, facts) == last
                && (stage == StoryState.Stage.READY || stage == StoryState.Stage.TOLD)) {
            world.store().event(owner.getId(), persona.id(), "story.capstone.errand", story.id());
            tell(owner, persona, story, last);
        }
    }

    // ---- telling the owner a chapter is open ---------------------------------------------------

    /** The owner's friendship with a persona rose (BondService.addPoints): a chapter may have opened. */
    public void friendshipRose(int ownerId, String personaId) {
        if (on() && !storiesOf(personaId).isEmpty()) {
            pending.computeIfAbsent(ownerId, id -> ConcurrentHashMap.newKeySet()).add(personaId);
        }
    }

    /** The owner logged in: each companion of theirs with a story looks for an untold chapter (never one told before). */
    public void loggedIn(Character owner) {
        if (!on()) {
            return;
        }
        for (RosterEntry entry : world.roster().roster(owner)) {
            if (entry.state() != RosterState.RELEASED && !storiesOf(entry.personaId()).isEmpty()) {
                pending.computeIfAbsent(owner.getId(), id -> ConcurrentHashMap.newKeySet()).add(entry.personaId());
            }
        }
    }

    /** Each pending owner: a companion out with them, at a calm moment, says what it has to say, once. */
    void nudges() {
        for (Map.Entry<Integer, Set<String>> entry : new ArrayList<>(pending.entrySet())) {
            Character owner = world.roster().onlineCharacter(entry.getKey());
            if (owner == null) {
                pending.remove(entry.getKey());
                continue;
            }
            for (String personaId : new ArrayList<>(entry.getValue())) {
                if (nudge(owner, personaId)) {
                    entry.getValue().remove(personaId);
                }
            }
            if (entry.getValue().isEmpty()) {
                pending.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** True when nothing more waits for this persona (said, or nothing to say); false to look again at the next sweep. */
    boolean nudge(Character owner, String personaId) {
        Persona persona = world.content().persona(personaId);
        if (persona == null) {
            return true;
        }
        Story story = null;
        String chapterId = null;
        List<String> lines = List.of();
        for (Story candidate : storiesOf(personaId)) {
            StoryState.Facts facts = facts(owner.getId(), candidate);
            if (candidate.persona().equals(personaId)) {
                StoryChapter chapter = StoryState.current(candidate, facts);
                if (chapter != null && StoryState.of(candidate, chapter, facts) == StoryState.Stage.READY) {
                    story = candidate;
                    chapterId = chapter.id();
                    lines = chapter.nudge();
                    break;
                }
            }
            if (candidate.rival() != null && StoryState.rival(candidate, facts) == StoryState.Stage.READY) {
                story = candidate;
                chapterId = StoryState.RIVAL_CHAPTER;
                lines = candidate.rival().nudge();
                break;
            }
        }
        if (story == null || lines.isEmpty()) {
            return true;
        }
        RosterEntry row = world.roster().entry(owner.getId(), personaId);
        Character bot = row == null || row.state() != RosterState.ACTIVE ? null : world.roster().companion(row);
        if (bot == null || !calm(owner, bot)) {
            return false; // not out with them, or not now: the next sweep looks again
        }
        String line = lines.get(Math.floorMod(owner.getId(), lines.size()));
        if (!world.roster().sayInParty(bot, line)) {
            return false;
        }
        world.bonds().setFlag(owner.getId(), story.persona(), StoryState.toldFlag(story, chapterId));
        owner.dropMessage(5, persona.name() + " wants a word. Double-click " + objectPronoun(persona) + ".");
        world.store().event(owner.getId(), personaId, "story.nudge", story.id() + " " + chapterId);
        log.debug("Story nudge {} {} for {}", story.id(), chapterId, owner.getName());
        return true;
    }

    /**
     * A moment to bring it up: both on one open map (no event instance), no window waiting on the owner, and a town or
     * no monster within STORY_CALM_RANGE_PX of either.
     */
    boolean calm(Character owner, Character bot) {
        MapleMap map = owner.getMap();
        if (map == null || bot.getMap() != map || map.getEventInstance() != null || owner.getHp() <= 0
                || LivingTalk.awaitsReply(owner)) {
            return false;
        }
        if (map.isTown()) {
            return true;
        }
        double range = (double) world.config().STORY_CALM_RANGE_PX * world.config().STORY_CALM_RANGE_PX;
        return map.getMapObjectsInRange(owner.getPosition(), range, List.of(MapObjectType.MONSTER)).isEmpty()
                && map.getMapObjectsInRange(bot.getPosition(), range, List.of(MapObjectType.MONSTER)).isEmpty();
    }

    private static String objectPronoun(Persona persona) {
        String gender = persona.look() == null ? null : persona.look().gender();
        return "female".equalsIgnoreCase(gender) ? "her" : "male".equalsIgnoreCase(gender) ? "him" : "them";
    }
}
