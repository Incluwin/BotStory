package server.living.population;

import org.junit.jupiter.api.Test;
import server.living.config.ContentLoader;
import server.living.population.PopulationContent.Intent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The needs board's lines (chatter/needs.yaml, docs/BOT_CONCEPT_PLAN.md F1) load clean: every "need.<kind>.*" posting
 * intent is idle or megaphone talk of a real kind, every reaction has lines, the slots are ones the ledger fills, and no
 * line asks to be whispered (citizens answer no whispers until F5c).
 */
class NeedsContentTest {
    private static final Set<String> SLOTS = Set.of("item", "what", "count", "price", "total", "for", "map", "name", "me");
    private static final Set<String> KINDS = Set.of("supply", "scroll", "gear");
    private static final Pattern SLOT = Pattern.compile("\\{([a-z]+)}");

    private final PopulationContent content = PopulationLoader.load(Path.of("scripts/config/population"),
            ContentLoader.load(Path.of("scripts/config/living-world")));

    @Test
    void theLinesLoadWithoutAFinding() {
        List<String> needs = new ArrayList<>();
        for (String warning : content.warnings()) {
            if (warning.contains("needs.yaml") || warning.contains("need.")) {
                needs.add(warning);
            }
        }
        assertEquals(List.of(), needs);
    }

    @Test
    void postingsAreOfARealKindAndSayOnlyWhatTheLedgerFills() {
        int postings = 0;
        for (Intent intent : content.intents()) {
            if (!"needs.yaml".equals(Path.of(intent.file()).getFileName().toString())) {
                continue;
            }
            if (intent.id().startsWith("need.")) {
                postings++;
                assertTrue(KINDS.contains(intent.id().split("\\.")[1]), intent.id());
                assertTrue(Set.of("dwell", "smega").contains(intent.trigger()), intent.id());
            }
            List<String> lines = new ArrayList<>(intent.lines());
            intent.styles().values().forEach(lines::addAll);
            for (String line : lines) {
                Matcher slot = SLOT.matcher(line);
                while (slot.find()) {
                    assertTrue(SLOTS.contains(slot.group(1)), intent.id() + ": {" + slot.group(1) + "} in '" + line + "'");
                }
                String lower = line.toLowerCase(Locale.ROOT);
                assertFalse(lower.contains("whisper") || lower.contains("pm me") || lower.contains(" w me"), line);
            }
        }
        assertEquals(9, postings, "map and megaphone lines for each kind");
        for (String trigger : List.of("need.whisper", "need.thanks", "need.got", "need.scroll.pass", "need.scroll.fail",
                "memory.greet.fulfilled")) {
            assertFalse(content.intents(trigger).isEmpty(), trigger);
        }
    }
}
