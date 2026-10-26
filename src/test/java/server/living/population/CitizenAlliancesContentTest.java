package server.living.population;

import net.server.guild.GuildCharacter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.living.config.ContentLoader;
import server.living.population.PopulationContent.Alliances;
import server.living.population.PopulationContent.NamePattern;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alliance content in guild-names.yaml (docs/BOT_GUILD_ALLIANCES_PLAN.md): names Lenario would take, a line for every
 * moment the alliance check speaks, and only the slots it fills.
 */
class CitizenAlliancesContentTest {
    private static final Path ROOT = Path.of("scripts/config/population");
    private static final Path LIVING = Path.of("scripts/config/living-world");
    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z0-9]+)}");
    private static final Set<String> FILLED = Set.of("guild", "other", "alliance");

    private static PopulationContent content;
    private static Alliances alliances;

    @BeforeAll
    static void load() {
        content = PopulationLoader.load(ROOT, ContentLoader.load(LIVING));
        alliances = content.guildNames().alliances();
    }

    @Test
    void everyPatternDrawsNamesLenarioTakes() {
        assertFalse(alliances.patterns().isEmpty());
        for (NamePattern pattern : alliances.patterns()) {
            NameGenerator generator = new NameGenerator(List.of(pattern), content.guildNames().lists(), List.of(), List.of(),
                    List.of());
            Random random = new Random(pattern.pattern().hashCode());
            int fit = 0;
            for (int draw = 0; draw < 200; draw++) {
                String name = generator.next(random);
                assertFalse(name.contains("{"), pattern.pattern() + " names a list the guild names lack: " + name);
                if (name.length() >= 3 && name.length() <= 12 && !name.contains(" ")) {
                    fit++;
                }
            }
            assertTrue(fit >= 20, pattern.pattern() + " draws a name Lenario takes (no spaces, 12 letters at most) only "
                    + fit + " times in 200");
        }
    }

    @Test
    void everyMomentHasLinesAndOnlyTheSlotsTheCheckFills() {
        for (String moment : List.of("founded", "welcome", "leave", "accept", "decline", "invite")) {
            List<String> lines = alliances.lines(moment);
            assertFalse(lines.isEmpty(), "no " + moment + " line");
            for (String line : lines) {
                Set<String> slots = new HashSet<>();
                Matcher matcher = SLOT.matcher(line);
                while (matcher.find()) {
                    slots.add(matcher.group(1));
                }
                assertTrue(FILLED.containsAll(slots), moment + " line names a slot the check never fills: " + line);
            }
        }
        assertEquals(List.of(), alliances.lines("no-such-moment"));
    }

    @Test
    void anOfflineGuildMasterTakesAnAllianceRank() {
        GuildCharacter offline = new GuildCharacter(null, 7, 50, "Master", (byte) -1, 0, 100, 1, 3, false, 5);
        assertDoesNotThrow(() -> offline.setAllianceRank(1));
        assertEquals(1, offline.getAllianceRank());
    }
}
