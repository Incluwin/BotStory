package server.living.habits;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.living.LivingWorld;
import server.living.roster.CompanionExecutor;
import server.living.roster.DirectExecutor;
import server.living.roster.RosterService;
import server.living.store.LivingStore;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OwnerHabitBook (docs/BOT_CONCEPT_PLAN.md F2.2-F2.4): only an online human who owns companions is watched, while
 * owner habits are on; what is learned reaches the seams from memory once a minute. A learned potion point never goes
 * below the HP floor and never applies in boss content; LIKE_OWNER never skips a quest item; "forget my habits" leaves
 * nothing, in memory or stored.
 */
class OwnerHabitBookTest {
    private static final int OWNER = 77;
    private static final int STRANGER = 78;
    private static final int ARROW = 2060000;
    private static final int QUEST_ARROW = 2060099;

    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingWorld world = mock(LivingWorld.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private OwnerHabitBook book;

    @BeforeEach
    void wire() {
        config.OWNER_HABITS_ENABLED = true;
        when(world.config()).thenReturn(config);
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(store);
        CompanionExecutor inline = DirectExecutor.inline(); // built first: it stubs a mock of its own
        when(world.executor()).thenReturn(inline);
        when(roster.isOwner(OWNER)).thenReturn(true);
        when(store.saveOwnerHabits(anyInt(), anyString())).thenReturn(true);
        when(store.deleteOwnerHabits(anyInt())).thenReturn(true);
        book = new OwnerHabitBook(world, new OwnerHabitLines(Map.of(), Map.of(), Set.of()));
        book.questItem = itemId -> itemId == QUEST_ARROW;
        book.unitPrice = itemId -> 1;
    }

    private static Character human(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        return chr;
    }

    /** {@code count} drinks at {@code hp}% HP, each past the death window, then the minute's learning. */
    private void drinks(int count, int hp) {
        OwnerHabits habits = book.watched(OWNER);
        for (int i = 0; i < count; i++) {
            habits.drank(i * 60_000L, hp, -1, config.OWNER_HABIT_DEATH_WINDOW_MS);
        }
        habits.keepAllDrinks();
        book.tick();
    }

    @Test
    void onlyAnOnlineOwnerIsWatchedAndOnlyWhileOn() {
        book.loggedIn(human(STRANGER));
        assertNull(book.watched(STRANGER), "owns no companion");
        Character owner = human(OWNER);
        book.loggedIn(owner);
        assertNotNull(book.watched(OWNER));
        assertTrue(book.watches(owner));

        config.OWNER_HABITS_ENABLED = false;
        assertFalse(book.watches(owner), "the kill switch: nothing watched");
        assertNull(book.potionThresholds(OWNER, 0.7f, 0.5f), "nor used");
        config.OWNER_HABITS_ENABLED = true;

        book.watched(OWNER).lootSeen(ARROW, true, 1);
        book.loggedOut(owner);
        assertNull(book.watched(OWNER), "written and dropped at logout");
        verify(store).saveOwnerHabits(eq(OWNER), anyString());
    }

    @Test
    void aLearnedPotionPointNeverGoesBelowTheHpFloor() {
        book.loggedIn(human(OWNER));
        assertNull(book.potionThresholds(OWNER, 0.7f, 0.5f), "nothing learned: as configured");

        drinks(8, 30); // a daredevil: drinks at 30%
        float[] learned = book.potionThresholds(OWNER, 0.7f, 0.5f);
        assertEquals(0.45f, learned[0], 1e-6, "the floor, not 30%");
        assertEquals(0.5f, learned[1], 1e-6, "no MP point learned: the configured one");

        book.watched(OWNER).forget();
        drinks(8, 60);
        assertEquals(0.60f, book.potionThresholds(OWNER, 0.7f, 0.5f)[0], 1e-6, "above the floor: the owner's own point");
    }

    @Test
    void noneOfItAppliesInBossContent() {
        assertTrue(OwnerHabitBook.bossContent(null), "no character: as configured, to be safe");
        Character inRun = human(OWNER);
        when(inRun.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertTrue(OwnerHabitBook.bossContent(inRun), "a party quest, an expedition or a boss run");
        assertFalse(OwnerHabitBook.bossContent(human(OWNER)), "a plain field");
    }

    @Test
    void likeOwnerSkipsWhatTheOwnerLeavesButNeverAQuestItem() {
        book.loggedIn(human(OWNER));
        OwnerHabits habits = book.watched(OWNER);
        for (int i = 0; i < 12; i++) {
            habits.lootSeen(ARROW, false, 1);
        }
        book.tick();

        assertTrue(book.skips(OWNER, ARROW));
        assertTrue(book.skips(OWNER, ARROW + 1), "the whole category");
        assertFalse(book.skips(OWNER, QUEST_ARROW), "a quest item is never skipped, whatever its category");
        assertFalse(book.skips(OWNER, 4000019), "another category");
        assertFalse(book.skips(STRANGER, ARROW), "nothing learned of anyone else");
    }

    @Test
    void forgetMyHabitsLeavesNothingInMemoryOrStored() {
        book.loggedIn(human(OWNER));
        drinks(8, 60);
        assertNotNull(book.potionThresholds(OWNER, 0.7f, 0.5f));

        assertTrue(book.forget(OWNER));

        assertNull(book.potionThresholds(OWNER, 0.7f, 0.5f));
        assertEquals(OwnerHabitBook.Learned.NOTHING, book.learned(OWNER));
        assertEquals(new OwnerHabits().toJson(), book.watched(OWNER).toJson());
        verify(store).deleteOwnerHabits(OWNER);
    }
}
