package server.living.story;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Story;
import server.living.config.Content.StoryChapter;
import server.living.config.Content.StoryRival;
import server.living.store.LivingStore.QuestStatus;
import server.living.story.StoryState.Facts;
import server.living.story.StoryState.Stage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Where a player is in a companion's personal arc (F6.1), and in its race chapter (F6.6), from stored facts only. */
class StoryStateTest {
    private static final Story FENNA = new Story("fenna-long-road", "fenna", List.of(
            chapter("one", 3, "fenna-story-1"), chapter("two", 6, "fenna-story-2"), chapter("three", 9, "fenna-story-3")),
            new StoryRival("dorran", 6, "two", "rivals-fenna-dorran", 31609, List.of("bet"), List.of()), Map.of(), "test");

    private static StoryChapter chapter(String id, int friendship, String quest) {
        return new StoryChapter(id, friendship, quest, 31600, List.of("nudge"), List.of(), null);
    }

    private final Set<String> flags = new HashSet<>();
    private final Set<String> rivalFlags = new HashSet<>();
    private final Map<String, QuestStatus> quests = new HashMap<>();

    private Facts facts(boolean owned, int friendship) {
        return facts(owned, friendship, false, 0);
    }

    private Facts facts(boolean owned, int friendship, boolean rivalOwned, int rivalFriendship) {
        return new Facts(owned, friendship, flags, quests, rivalOwned, rivalFriendship, rivalFlags);
    }

    @Test
    void aChapterOpensAtItsFriendshipLevelOnceThePlayerOwnsTheCompanion() {
        StoryChapter one = FENNA.chapters().get(0);
        assertEquals(Stage.LOCKED, StoryState.of(FENNA, one, facts(true, 2)));
        assertEquals(Stage.LOCKED, StoryState.of(FENNA, one, facts(false, 5)), "a player who does not own it: nothing");
        assertEquals(Stage.READY, StoryState.of(FENNA, one, facts(true, 3)));
        flags.add(StoryState.toldFlag(FENNA, "one"));
        assertEquals(Stage.TOLD, StoryState.of(FENNA, one, facts(true, 3)));
        quests.put("fenna-story-1", QuestStatus.ACTIVE);
        assertEquals(Stage.ACTIVE, StoryState.of(FENNA, one, facts(true, 3)));
        quests.put("fenna-story-1", QuestStatus.COMPLETED);
        assertEquals(Stage.DONE, StoryState.of(FENNA, one, facts(true, 3)));
    }

    @Test
    void chaptersOpenInOrderEvenAtAHigherLevel() {
        assertEquals(Stage.LOCKED, StoryState.of(FENNA, FENNA.chapters().get(1), facts(true, 10)), "chapter one first");
        assertEquals("one", StoryState.current(FENNA, facts(true, 10)).id());
        flags.add(StoryState.doneFlag(FENNA, "one")); // its reward flag alone counts as done
        assertEquals(Stage.READY, StoryState.of(FENNA, FENNA.chapters().get(1), facts(true, 10)));
        assertEquals(Stage.LOCKED, StoryState.of(FENNA, FENNA.chapters().get(2), facts(true, 10)));
        flags.add(StoryState.doneFlag(FENNA, "two"));
        flags.add(StoryState.doneFlag(FENNA, "three"));
        assertNull(StoryState.current(FENNA, facts(true, 10)), "every chapter told");
    }

    @Test
    void aReleasedCompanionKeepsItsPlacesAndAnActiveChapterWaitsForIt() {
        quests.put("fenna-story-1", QuestStatus.COMPLETED);
        quests.put("fenna-story-2", QuestStatus.ACTIVE);
        assertEquals(Stage.LOCKED, StoryState.of(FENNA, FENNA.chapters().get(1), facts(false, 7)), "released: it waits");
        assertEquals(Stage.ACTIVE, StoryState.of(FENNA, FENNA.chapters().get(1), facts(true, 7)), "recruited again");
        assertEquals(Stage.DONE, StoryState.of(FENNA, FENNA.chapters().get(0), facts(false, 7)));
    }

    @Test
    void theRaceOpensWithBothRivalsOwnedAtItsLevelAfterChapterTwo() {
        quests.put("fenna-story-1", QuestStatus.COMPLETED);
        assertEquals(Stage.LOCKED, StoryState.rival(FENNA, facts(true, 7, true, 7)), "chapter two not done");
        quests.put("fenna-story-2", QuestStatus.COMPLETED);
        assertEquals(Stage.LOCKED, StoryState.rival(FENNA, facts(true, 7, false, 7)), "the rival is not theirs");
        assertEquals(Stage.LOCKED, StoryState.rival(FENNA, facts(true, 7, true, 5)), "not friends enough with the rival");
        assertEquals(Stage.READY, StoryState.rival(FENNA, facts(true, 6, true, 6)));
        flags.add(StoryState.toldFlag(FENNA, StoryState.RIVAL_CHAPTER));
        assertEquals(Stage.TOLD, StoryState.rival(FENNA, facts(true, 6, true, 6)));
        rivalFlags.add(StoryState.rivalryFlag("fenna", "dorran")); // raced, as told from the rival's side
        assertEquals(Stage.DONE, StoryState.rival(FENNA, facts(true, 6, true, 6)));
    }

    @Test
    void theRivalryFlagIsTheSameWhicheverSideNamesIt() {
        assertEquals("story.rivalry.dorran-fenna", StoryState.rivalryFlag("fenna", "dorran"));
        assertEquals("story.rivalry.dorran-fenna", StoryState.rivalryFlag("dorran", "fenna"));
    }
}
