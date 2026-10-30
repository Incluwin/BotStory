package server.living.config;

import server.knowledge.EntityKind;
import server.knowledge.KnowledgeLint;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Scene;
import server.living.config.Content.SceneBeat;
import server.living.config.Content.SceneFoe;
import server.living.config.Content.SceneSpawn;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.config.Content.Story;
import server.living.config.Content.StoryChapter;
import server.living.config.Content.StoryOption;
import server.living.config.Content.StoryRival;
import server.living.story.SceneEffect;
import server.living.story.StoryRivals;
import server.living.story.StoryState;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lints personal story arcs and their scenes (docs/BOT_CONCEPT_PLAN.md F6): every chapter reachable (friendship rising
 * inside 1-10, each chapter's quest asking for the one before), chapter quests offered only by their story, client
 * quest-log ids reserved inside F6's range and kept out of quest-log-ids.yaml until the client patch, scene maps and
 * monsters the server knows, lines that fit the client's chat balloon, speakers in the cast, effects from the safe list,
 * and race chapters between authored rivals. Called from {@link ContentLint#check}, so !world reload and MapleEdit's
 * save see it.
 */
final class StoryLint {
    /** F6's quest-log ids (docs/BOT_CONCEPT_PLAN.md section 12). */
    static final int LOG_ID_MIN = 31600;
    static final int LOG_ID_MAX = 31999;
    /** A chat balloon line, as living_world.RESIDENT_BUBBLE_MAX_CHARS (longer ones the client cuts). */
    static final int BALLOON_MAX = 70;
    /** One page of an NPC window. */
    static final int PAGE_MAX = 400;
    /** The slots a scene line may use: the owner's name, and in a race's outcome the speaker's share and the other's. */
    static final Set<String> SLOTS = Set.of("owner", "mine", "theirs");
    private static final Pattern SLOT = Pattern.compile("\\{([a-z]+)}");

    private StoryLint() {
    }

    static void check(LivingContent content, KnowledgeLint knowledge, List<String> out) {
        Set<Integer> logIds = new HashSet<>();
        Set<String> personas = new HashSet<>();
        Set<String> questLined = new HashSet<>();
        for (Persona persona : content.personas().values()) {
            questLined.addAll(persona.questLines());
        }
        for (Story story : content.stories().values()) {
            String where = story.file() + " story " + story.id();
            Persona persona = content.personas().get(story.persona());
            if (persona == null) {
                out.add("ERROR " + where + ": persona '" + story.persona() + "' not found");
                continue;
            }
            if (!persona.recruitable()) {
                out.add("ERROR " + where + ": " + persona.id() + " is not recruitable (a personal arc is a companion's)");
            }
            if (!personas.add(persona.id())) {
                out.add("ERROR " + where + ": " + persona.id() + " has a second story");
            }
            int previousLevel = 0;
            String previousQuest = null;
            for (int index = 0; index < story.chapters().size(); index++) {
                StoryChapter chapter = story.chapters().get(index);
                String chapterWhere = where + " chapter " + chapter.id();
                if (chapter.friendship() <= previousLevel || chapter.friendship() > server.living.bond.Friendship.MAX_LEVEL) {
                    out.add("ERROR " + chapterWhere + ": friendship " + chapter.friendship() + " must rise above "
                            + previousLevel + " and be at most " + server.living.bond.Friendship.MAX_LEVEL);
                }
                previousLevel = chapter.friendship();
                logId(chapterWhere, chapter.logId(), content, logIds, out);
                pages(chapterWhere, chapter.talk(), out);
                lines(chapterWhere + " nudge", chapter.nudge(), out);
                if (chapter.nudge().isEmpty()) {
                    out.add("ERROR " + chapterWhere + ": no nudge line (the companion never says the chapter is open)");
                }
                if (chapter.choice() != null) {
                    if (chapter.choice().options().isEmpty()) {
                        out.add("ERROR " + chapterWhere + ": a choice needs options");
                    }
                    for (StoryOption option : chapter.choice().options()) {
                        pages(chapterWhere + " option '" + option.label() + "'", option.reply(), out);
                    }
                }
                Quest quest = chapterQuest(content, story, chapter.quest(), chapter.friendship(), chapterWhere,
                        questLined, out);
                if (quest != null) {
                    if (!quest.rewards().flags().contains(StoryState.doneFlag(story, chapter.id()))) {
                        out.add("ERROR " + chapterWhere + ": quest " + quest.id() + " must reward the flag "
                                + StoryState.doneFlag(story, chapter.id()));
                    }
                    if (previousQuest != null && !quest.requires().questsCompleted().contains(previousQuest)) {
                        out.add("ERROR " + chapterWhere + ": quest " + quest.id() + " must require " + previousQuest
                                + " (the chapter before), or the chapter is not reachable in order");
                    }
                    boolean last = index == story.chapters().size() - 1;
                    if (last && quest.steps().stream().noneMatch(step -> step.type() == StepType.SCENE)) {
                        out.add("ERROR " + chapterWhere + ": the last chapter's quest needs a SCENE step (the capstone)");
                    }
                    scenes(content, knowledge, story, quest, chapterWhere, out);
                }
                previousQuest = chapter.quest();
            }
            if (story.rival() != null) {
                rival(content, knowledge, story, persona, questLined, logIds, out);
            }
            for (Map.Entry<String, List<String>> entry : story.lines().entrySet()) {
                lines(where + " lines." + entry.getKey(), entry.getValue(), out);
            }
        }
    }

    private static void rival(LivingContent content, KnowledgeLint knowledge, Story story, Persona persona,
                              Set<String> questLined, Set<Integer> logIds, List<String> out) {
        StoryRival rival = story.rival();
        String where = story.file() + " story " + story.id() + " rival";
        Persona other = content.personas().get(rival.persona());
        if (other == null || !other.recruitable()) {
            out.add("ERROR " + where + ": rival '" + rival.persona() + "' is not a recruitable persona");
            return;
        }
        if (!StoryRivals.rivals(persona, other)) {
            out.add("ERROR " + where + ": " + persona.id() + " and " + other.id() + " are not authored rivals");
        }
        if (rival.after() != null && story.chapter(rival.after()) == null) {
            out.add("ERROR " + where + ": after '" + rival.after() + "' is not a chapter of the story");
        }
        if (rival.nudge().isEmpty()) {
            out.add("ERROR " + where + ": no nudge line");
        }
        lines(where + " nudge", rival.nudge(), out);
        pages(where, rival.talk(), out);
        logId(where, rival.logId(), content, logIds, out);
        Quest quest = chapterQuest(content, story, rival.quest(), rival.friendship(), where, questLined, out);
        if (quest == null) {
            return;
        }
        Scene race = null;
        for (Step step : quest.steps()) {
            Scene scene = step.type() == StepType.SCENE ? content.scene(step.scene()) : null;
            if (scene != null && scene.beats().stream().anyMatch(SceneBeat::race)) {
                race = scene;
            }
        }
        if (race == null) {
            out.add("ERROR " + where + ": quest " + quest.id() + " needs a SCENE step whose scene has a race beat");
            return;
        }
        if (!race.cast().contains(rival.persona())) {
            out.add("ERROR " + where + ": the race scene " + race.id() + " must cast " + rival.persona());
        }
        for (String key : List.of("won." + persona.id(), "won." + rival.persona(), "even")) {
            if (!race.outcome().containsKey(key)) {
                out.add("ERROR " + where + ": the race scene " + race.id() + " has no outcome '" + key + "'");
            }
        }
        scenes(content, knowledge, story, quest, where, out);
    }

    /** The chapter's quest, checked: the story's persona gives it, at that friendship, offered only by the story. */
    private static Quest chapterQuest(LivingContent content, Story story, String questId, int friendship, String where,
                                      Set<String> questLined, List<String> out) {
        Quest quest = content.quest(questId);
        if (quest == null) {
            out.add("ERROR " + where + ": quest '" + questId + "' not found");
            return null;
        }
        if (!quest.persona().equals(story.persona())) {
            out.add("ERROR " + where + ": quest " + questId + " is given by " + quest.persona() + ", not " + story.persona());
        }
        if (quest.requires().minFriendship() != friendship) {
            out.add("ERROR " + where + ": quest " + questId + " requires friendship " + quest.requires().minFriendship()
                    + ", the chapter " + friendship);
        }
        if (quest.repeatable()) {
            out.add("ERROR " + where + ": quest " + questId + " is repeatable (a chapter happens once)");
        }
        if (questLined.contains(questId)) {
            out.add("ERROR " + where + ": quest " + questId + " is in a questLines (its story offers it, to the owner only)");
        }
        if (quest.steps().isEmpty() || quest.steps().get(0).type() != StepType.TALK) {
            out.add("ERROR " + where + ": quest " + questId + " must start with a TALK step (the conversation)");
        }
        for (Step step : quest.steps()) {
            if (step.type() != StepType.TALK && step.type() != StepType.SCENE) {
                out.add("ERROR " + where + ": quest " + questId + " step " + step.id() + " is " + step.type()
                        + "; a chapter has TALK and SCENE steps only");
            }
        }
        return quest;
    }

    private static void scenes(LivingContent content, KnowledgeLint knowledge, Story story, Quest quest, String where,
                               List<String> out) {
        for (Step step : quest.steps()) {
            if (step.type() != StepType.SCENE) {
                continue;
            }
            Scene scene = content.scene(step.scene());
            if (scene == null) {
                out.add("ERROR " + where + ": scene '" + step.scene() + "' not found");
                continue;
            }
            scene(content, knowledge, story, scene, out);
        }
    }

    private static void scene(LivingContent content, KnowledgeLint knowledge, Story story, Scene scene, List<String> out) {
        String where = scene.file() + " scene " + scene.id();
        if (!scene.cast().contains(story.persona())) {
            out.add("ERROR " + where + ": the cast must include " + story.persona());
        }
        for (String member : scene.cast()) {
            Persona persona = content.personas().get(member);
            if (persona == null || !persona.recruitable()) {
                out.add("ERROR " + where + ": cast '" + member + "' is not a recruitable persona (scenes are acted by companions)");
            }
        }
        if (scene.maxMinutes() < 1 || scene.maxMinutes() > 30) {
            out.add("ERROR " + where + ": maxMinutes " + scene.maxMinutes() + " must be 1-30");
        }
        if (knowledge != null) {
            knowledge.require(EntityKind.MAP, scene.mapId(), where, out);
        }
        boolean race = false;
        for (SceneBeat beat : scene.beats()) {
            race |= beat.race();
            beat(content, knowledge, scene, beat, where, false, out);
        }
        if (race) {
            if (scene.foes().isEmpty()) {
                out.add("ERROR " + where + ": a race needs foes");
            }
            int previous = 0;
            for (SceneFoe foe : scene.foes()) {
                if (foe.maxLevel() <= previous) {
                    out.add("ERROR " + where + ": foe bands must rise (maxLevel " + foe.maxLevel() + ")");
                }
                previous = foe.maxLevel();
                if (knowledge != null) {
                    knowledge.require(EntityKind.MOB, foe.mobId(), where + " foe", out);
                }
            }
            if (previous < 200) {
                out.add("ERROR " + where + ": the last foe band must reach maxLevel 200 (every racer has a foe)");
            }
        }
        for (Map.Entry<String, List<SceneBeat>> entry : scene.outcome().entrySet()) {
            for (SceneBeat beat : entry.getValue()) {
                beat(content, knowledge, scene, beat, where + " outcome " + entry.getKey(), true, out);
            }
        }
    }

    private static void beat(LivingContent content, KnowledgeLint knowledge, Scene scene, SceneBeat beat, String where,
                             boolean outcome, List<String> out) {
        if (beat.line() != null) {
            if (beat.by() == null || !scene.cast().contains(beat.by())) {
                out.add("ERROR " + where + ": line '" + beat.line() + "' is by '" + beat.by() + "', not someone in the cast");
            }
            if (beat.ifPresent() != null && !scene.cast().contains(beat.ifPresent())) {
                out.add("ERROR " + where + ": ifPresent '" + beat.ifPresent() + "' is not in the cast");
            }
            line(where, beat.line(), outcome, out);
        }
        if (beat.effect() != null) {
            SceneEffect effect = SceneEffect.of(beat.effect());
            if (effect == null) {
                out.add("ERROR " + where + ": effect '" + beat.effect() + "' is not on the safe list");
            } else if (effect.problem(beat) != null) {
                out.add("ERROR " + where + ": " + effect.problem(beat));
            }
        }
        for (SceneSpawn spawn : beat.spawn()) {
            if (spawn.count() < 1 || spawn.count() > 15) {
                out.add("ERROR " + where + ": spawn count " + spawn.count() + " must be 1-15");
            }
            if (knowledge != null) {
                knowledge.require(EntityKind.MOB, spawn.mobId(), where, out);
            }
        }
        if (beat.line() == null && beat.effect() == null && beat.spawn().isEmpty() && !beat.race() && beat.seconds() <= 0) {
            out.add("ERROR " + where + ": a beat that does nothing");
        }
    }

    private static void logId(String where, int id, LivingContent content, Set<Integer> seen, List<String> out) {
        if (id < LOG_ID_MIN || id > LOG_ID_MAX) {
            out.add("ERROR " + where + ": logId " + id + " outside F6's " + LOG_ID_MIN + "-" + LOG_ID_MAX);
        }
        if (!seen.add(id)) {
            out.add("ERROR " + where + ": logId " + id + " used twice");
        }
        if (content.questLogIds().containsValue(id)) {
            out.add("ERROR " + where + ": logId " + id + " is already in " + ContentLoader.QUEST_LOG_IDS_FILE);
        }
    }

    private static void lines(String where, List<String> lines, List<String> out) {
        for (String line : lines) {
            line(where, line, false, out);
        }
    }

    private static void line(String where, String line, boolean outcome, List<String> out) {
        if (line.isBlank()) {
            out.add("ERROR " + where + ": an empty line");
        }
        String shown = SLOT.matcher(line).replaceAll("xxxxxxxxxxxx"); // a name or a share, at its longest
        if (shown.length() > BALLOON_MAX) {
            out.add("ERROR " + where + ": '" + line + "' is " + shown.length() + " characters with its slots filled; a chat"
                    + " balloon holds " + BALLOON_MAX);
        }
        Matcher slot = SLOT.matcher(line);
        while (slot.find()) {
            String name = slot.group(1);
            if (!SLOTS.contains(name) || !outcome && !"owner".equals(name)) {
                out.add("ERROR " + where + ": unknown slot {" + name + "} in '" + line + "'");
            }
        }
    }

    private static void pages(String where, List<String> pages, List<String> out) {
        for (String page : pages) {
            if (page.isBlank() || page.length() > PAGE_MAX) {
                out.add("ERROR " + where + ": a window page must be 1-" + PAGE_MAX + " characters: '" + page + "'");
            }
        }
    }

    /** For a test: what the lint says about one content snapshot (no server knowledge). */
    static List<String> findings(LivingContent content) {
        List<String> out = new java.util.ArrayList<>();
        check(content, null, out);
        return out;
    }
}
