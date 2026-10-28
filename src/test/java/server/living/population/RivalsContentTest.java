package server.living.population;

import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;
import server.living.config.ContentLoader;
import server.living.population.PopulationContent.Intent;
import server.living.population.records.QuestLabels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rival crew's content (docs/BOT_CONCEPT_PLAN.md F7): rivals.yaml lists every boss event script and every quest
 * citizens run, each a real event script, with aliases nobody else uses; chatter/rivals.yaml loads clean, has lines for
 * every rival trigger, says only slots RivalCrews fills, keeps rival.beat on the megaphone (one slot of the shared cap)
 * and the whispered ones whispered.
 */
class RivalsContentTest {
    private static final Set<String> SLOTS = Set.of("name", "crew", "quest", "time", "theirs", "me");
    private static final Pattern SLOT = Pattern.compile("\\{([a-z]+)}");
    private static final List<String> TRIGGERS = List.of("rival.banter", "rival.beat", "rival.beat.whisper", "rival.congrats",
            "rival.join", "rival.leave.behind", "rival.leave.ahead", "rival.leave.behind.whisper",
            "rival.leave.ahead.whisper", "rival.help", "rival.reply", "rival.reply.yes", "rival.reply.no");

    private final PopulationContent content = PopulationLoader.load(Path.of("scripts/config/population"),
            ContentLoader.load(Path.of("scripts/config/living-world")));
    private final QuestLabels labels = QuestLabels.load(Path.of("scripts/config/population"));

    @Test
    void everyBossEventAndCitizenQuestIsARecordOfARealScript() {
        Set<String> listed = new HashSet<>();
        Set<String> aliases = new HashSet<>();
        for (QuestLabels.Quest quest : labels.all()) {
            assertTrue(listed.add(quest.script()), "listed twice: " + quest.script());
            assertTrue(Files.exists(Path.of("scripts/event", quest.script() + ".js")), quest.script());
            assertFalse(quest.label().isBlank(), quest.script());
            for (String alias : quest.aliases()) {
                assertTrue(aliases.add(alias.toLowerCase(Locale.ROOT)), "alias used twice: " + alias);
            }
        }
        for (String boss : new LivingWorldConfig().BOSS_EVENT_SCRIPTS.split(",")) {
            assertTrue(listed.contains(boss.trim()), "boss event " + boss + " is no record");
        }
        assertTrue(listed.containsAll(Set.of("KerningPQ", "HenesysPQ")), "citizens' own quests");
    }

    @Test
    void theLinesLoadWithoutAFinding() {
        List<String> findings = new ArrayList<>();
        for (String warning : content.warnings()) {
            if (warning.contains("rivals.yaml") || warning.contains("rival.")) {
                findings.add(warning);
            }
        }
        assertEquals(List.of(), findings);
    }

    @Test
    void everyTriggerHasLinesOnItsChannelWithSlotsTheCrewsFill() {
        for (String trigger : TRIGGERS) {
            List<Intent> intents = content.intents(trigger);
            assertFalse(intents.isEmpty(), trigger);
            for (Intent intent : intents) {
                String channel = trigger.endsWith(".whisper") || trigger.equals("rival.congrats") || trigger.equals("rival.help")
                        || trigger.startsWith("rival.reply") ? "whisper"
                        : trigger.equals("rival.beat") ? "smega" : trigger.equals("rival.banter") ? "map" : "guild";
                assertEquals(channel, intent.channel(), intent.id());
                List<String> lines = new ArrayList<>(intent.lines());
                intent.styles().values().forEach(lines::addAll);
                for (String line : lines) {
                    Matcher slot = SLOT.matcher(line);
                    while (slot.find()) {
                        assertTrue(SLOTS.contains(slot.group(1)), intent.id() + ": {" + slot.group(1) + "} in '" + line + "'");
                    }
                    assertFalse(line.contains("{") && !SLOT.matcher(line).find(), line);
                }
            }
        }
    }
}
