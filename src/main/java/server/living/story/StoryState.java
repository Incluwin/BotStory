package server.living.story;

import server.living.config.Content.Story;
import server.living.config.Content.StoryChapter;
import server.living.config.Content.StoryRival;
import server.living.store.LivingStore.QuestStatus;

import java.util.Map;
import java.util.Set;

/**
 * Where one player is in one companion's personal arc (F6.1), computed from what is stored, with nothing of its own:
 * the player's friendship level with the companion, the flags on their bond ({@code living_bond.flags}) and the chapter
 * quests' rows. A chapter is LOCKED until the player owns the companion, has its friendship level and has done the
 * chapter before; then READY; TOLD once the companion has said so ({@link #toldFlag}); ACTIVE while its quest runs;
 * DONE once the quest is complete (its reward sets {@link #doneFlag}). Nothing is lost when a companion is released:
 * recruited again, it picks up where it was; another owner starts at the first chapter.
 */
public final class StoryState {
    public enum Stage { LOCKED, READY, TOLD, ACTIVE, DONE }

    /** The race chapter's id in flags and rows (F6.6). */
    public static final String RIVAL_CHAPTER = "rival";

    /**
     * What a stage is read from: whether the player owns the story's companion, their friendship level with it and the
     * flags on that bond, the player's rows for the story's quests, and the same for the race chapter's rival.
     */
    public record Facts(boolean owned, int friendship, Set<String> flags, Map<String, QuestStatus> quests,
                        boolean rivalOwned, int rivalFriendship, Set<String> rivalFlags) { }

    private StoryState() {
    }

    /** Set by a chapter quest's reward: the chapter is done. */
    public static String doneFlag(Story story, String chapterId) {
        return "story." + story.id() + "." + chapterId;
    }

    /** Set when the companion told its owner the chapter is open (once per chapter, never again at a login). */
    public static String toldFlag(Story story, String chapterId) {
        return doneFlag(story, chapterId) + ".told";
    }

    /** On both bonds once the two rivals raced, whoever's story it was in ("story.rivalry.dorran-fenna"). */
    public static String rivalryFlag(String personaA, String personaB) {
        return personaA.compareTo(personaB) <= 0 ? "story.rivalry." + personaA + "-" + personaB
                : "story.rivalry." + personaB + "-" + personaA;
    }

    public static Stage of(Story story, StoryChapter chapter, Facts facts) {
        QuestStatus status = facts.quests().get(chapter.quest());
        if (status == QuestStatus.COMPLETED || facts.flags().contains(doneFlag(story, chapter.id()))) {
            return Stage.DONE;
        }
        if (status == QuestStatus.ACTIVE) {
            return facts.owned() ? Stage.ACTIVE : Stage.LOCKED; // a released companion's chapter waits for it
        }
        if (!facts.owned() || facts.friendship() < chapter.friendship()) {
            return Stage.LOCKED;
        }
        int index = story.chapters().indexOf(chapter);
        if (index > 0 && of(story, story.chapters().get(index - 1), facts) != Stage.DONE) {
            return Stage.LOCKED;
        }
        return facts.flags().contains(toldFlag(story, chapter.id())) ? Stage.TOLD : Stage.READY;
    }

    /** The chapter the player is on: the first one not done; null once every chapter is. */
    public static StoryChapter current(Story story, Facts facts) {
        for (StoryChapter chapter : story.chapters()) {
            if (of(story, chapter, facts) != Stage.DONE) {
                return chapter;
            }
        }
        return null;
    }

    /**
     * The race chapter: open once the player owns both rivals, has the rival chapter's friendship level with each, and
     * has done the story's chapter it comes after; done once raced, from either story.
     */
    public static Stage rival(Story story, Facts facts) {
        StoryRival rival = story.rival();
        if (rival == null) {
            return Stage.LOCKED;
        }
        String raced = rivalryFlag(story.persona(), rival.persona());
        QuestStatus status = facts.quests().get(rival.quest());
        if (status == QuestStatus.COMPLETED || facts.flags().contains(raced) || facts.rivalFlags().contains(raced)) {
            return Stage.DONE;
        }
        boolean both = facts.owned() && facts.rivalOwned();
        if (status == QuestStatus.ACTIVE) {
            return both ? Stage.ACTIVE : Stage.LOCKED;
        }
        if (!both || facts.friendship() < rival.friendship() || facts.rivalFriendship() < rival.friendship()) {
            return Stage.LOCKED;
        }
        StoryChapter after = rival.after() == null ? null : story.chapter(rival.after());
        if (after != null && of(story, after, facts) != Stage.DONE) {
            return Stage.LOCKED;
        }
        return facts.flags().contains(toldFlag(story, RIVAL_CHAPTER)) ? Stage.TOLD : Stage.READY;
    }
}
