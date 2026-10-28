package server.living.mentor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mentors' words (scripts/config/living-world/mentor.yaml) as the code reads them: every key Mentorship says has a
 * default line, the authored voices are 3-5 and word only keys the defaults have, each line names only the
 * placeholders the code fills, a key that says a ContentAnswers fact has its plain twin, and party-chat lines are typed
 * as a player types (lower case, no full stop at the end).
 */
class MentorLinesTest {
    /** Every key Mentorship, its lead and its goodbye say. */
    static final List<String> KEYS = List.of("offer", "accept", "arrive", "lead.start", "lead.come", "lead.nudge",
            "lead.oneway", "lead.shanks", "lead.shanks.level", "lead.shanks.fare", "lead.deck", "lead.taxi", "lead.walk",
            "lead.instructor", "tip.low_hp", "tip.first_death", "tip.first_death.plain", "tip.lv8", "tip.lv10",
            "tip.lv10.plain", "tip.lv10.island", "tip.victoria", "tip.victoria.plain", "job.window", "job.picked",
            "job.unsure", "mute.ok", "unmute.ok", "goodbye", "goodbye.gift", "goodbye.last");
    private static final Set<String> PLACEHOLDERS = Set.of("name", "me", "fact", "branch", "town", "npc", "level");
    /** The windows (their speaker is the Bulletin Board); every other key is the mentor's own typing. */
    private static final Set<String> WINDOWS = Set.of("offer", "job.window");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)}");

    private final MentorLines lines = MentorLines.load(Path.of("scripts", "config", "living-world"));

    @Test
    void everyKeyTheCodeSaysHasADefault() {
        for (String key : KEYS) {
            assertFalse(lines.lines(null, key).isEmpty(), key);
        }
        assertEquals(Set.copyOf(KEYS), lines.keys(), "no line the code never says");
    }

    @Test
    void theVoicesAreThreeToFiveAndWordOnlyKnownKeys() {
        Set<String> styles = lines.styleIds();
        assertTrue(styles.size() >= 3 && styles.size() <= 5, styles.toString());
        for (String style : styles) {
            for (String key : KEYS) {
                assertFalse(lines.lines(style, key).isEmpty(), style + " says " + key + " (its own or the default)");
            }
        }
        assertNotNull(lines.style(1));
        assertEquals(lines.style(12_345), lines.style(12_345), "one voice per newcomer");
    }

    @Test
    void eachLineNamesOnlyThePlaceholdersTheCodeFillsAndFactKeysHaveTheirPlainTwin() {
        for (String style : withDefault()) {
            for (String key : KEYS) {
                for (String line : lines.lines(style, key)) {
                    Matcher matcher = PLACEHOLDER.matcher(line);
                    while (matcher.find()) {
                        assertTrue(PLACEHOLDERS.contains(matcher.group(1)), style + "/" + key + ": " + line);
                    }
                    if (line.contains("{fact}")) {
                        assertFalse(lines.lines(style, key + ".plain").isEmpty(), key + " has a .plain twin");
                    }
                    assertFalse(key.endsWith(".plain") && line.contains("{fact}"), key + " is the plain one: " + line);
                }
            }
        }
    }

    @Test
    void partyChatIsTypedAsAPlayerTypes() {
        for (String style : withDefault()) {
            for (String key : KEYS) {
                if (WINDOWS.contains(key)) {
                    continue;
                }
                for (String line : lines.lines(style, key)) {
                    String bare = PLACEHOLDER.matcher(line).replaceAll("");
                    assertEquals(bare.toLowerCase(java.util.Locale.ROOT), bare, style + "/" + key + " in lower case: " + line);
                    assertFalse(line.endsWith("."), style + "/" + key + " ends without a full stop: " + line);
                    assertTrue(line.length() <= 120, style + "/" + key + " fits a chat line: " + line);
                }
            }
        }
    }

    @Test
    void aLineIsFilledFromItsValues() {
        assertEquals("hi Ann, i'm Bo", MentorLines.fill("hi {name}, i'm {me}", Map.of("name", "Ann", "me", "Bo")));
        assertEquals("lv7", MentorLines.fill("lv{level}", Map.of("level", "7")));
    }

    private List<String> withDefault() {
        java.util.List<String> styles = new java.util.ArrayList<>();
        styles.add(null);
        styles.addAll(lines.styleIds());
        return styles;
    }
}
