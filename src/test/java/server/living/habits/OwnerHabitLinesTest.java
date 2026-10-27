package server.living.habits;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped owner-habit catalog (scripts/config/living-world/owner-habits.yaml, F2.4): every habit a companion
 * mentions has lines, in the lower-case player voice, filled only from that habit's own placeholder; the loot lines
 * name the common categories; scrolls, books and quest items are never learned as a skip.
 */
class OwnerHabitLinesTest {
    private static final Map<String, String> PLACEHOLDERS = Map.of("potion", "hp", "loot", "what", "sell", "item",
            "camp", "map", "skill", "skill");
    private static final Pattern BRACES = Pattern.compile("\\{(\\w+)}");

    private final OwnerHabitLines lines = OwnerHabitLines.load(Path.of("scripts/config/living-world"));

    @Test
    void everyHabitHasLinesFilledFromItsOwnPlaceholder() {
        Random random = new Random(1);
        for (Map.Entry<String, String> habit : PLACEHOLDERS.entrySet()) {
            for (int draw = 0; draw < 20; draw++) {
                String line = lines.line(habit.getKey(), Map.of(habit.getValue(), "X"), random);
                assertNotNull(line, habit.getKey());
                Matcher left = BRACES.matcher(line);
                assertFalse(left.find(), habit.getKey() + ": an unfilled placeholder in \"" + line + "\"");
                assertTrue(line.contains("X"), habit.getKey() + ": \"" + line + "\" names nothing");
                String first = line.substring(0, 1);
                assertTrue(first.equals("X") || first.equals(first.toLowerCase()), "lower-case voice: " + line);
            }
        }
    }

    @Test
    void theCommonCategoriesHaveNamesAndTheValuableOnesAreNeverSkipped() {
        assertEquals("arrows", lines.category(206));
        assertEquals("throwing stars", lines.category(207));
        assertEquals("ores", lines.category(401));
        for (int category : new int[]{204, 228, 229, 403}) { // scrolls, skill books, mastery books, quest items
            assertTrue(lines.neverSkips(category), String.valueOf(category));
        }
        assertFalse(lines.neverSkips(206));
    }
}
