package server.living.config;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The In Progress text of a Resident quest in the client's quest window: the giver, their accept line, then one line per
 * step with the live counts stock quests show ({@code #a<quest id><slot>#} kills from the quest record,
 * {@code #c<item id>#} what the inventory holds), pointing at the same Check.img/1 targets the Quest Helper counts.
 */
class QuestLogExportTextTest {
    private static final LivingContent CONTENT = ContentLoader.load(Path.of("scripts/config/living-world"));
    private static final String N = QuestLogExportMain.BREAK;
    private static final Pattern KILLS = Pattern.compile("#a(\\d+)#");
    private static final Pattern HELD = Pattern.compile("#c(\\d+)#");
    private static final Pattern MOB = Pattern.compile("#o(\\d+)#");

    private static String inProgress(String questId) {
        return (String) QuestLogExportMain.entry(CONTENT, CONTENT.quest(questId), CONTENT.questLogId(questId)).get("inProgress");
    }

    private static List<String> lines(String text) {
        return List.of(text.split(Pattern.quote(N), -1));
    }

    @Test
    void aLineBreakIsWrittenAsStockQuestTextWritesIt() {
        assertEquals("\\n", N, "a backslash and an n, as in the stock QuestInfo text");
    }

    @Test
    void theWatchShowsTheGiverTheirAcceptLineAndALiveKillCount() {
        assertEquals(30197, CONTENT.questLogId("edric-the-watch"));
        assertEquals("#bEdric (El Nath)#k" + N
                + "the cold field is past the icy path. the hectors hunt in pairs. don't be the third." + N
                + N
                + "Defeat #o5130104#: #r#a301971##k / 30" + N
                + "Report back to Edric", inProgress("edric-the-watch"));
    }

    @Test
    void theCaveOfTrialCountsKillsAndTeethAndNamesTheMapAndTheHandIn() {
        List<String> lines = lines(inProgress("sigrun-the-trial-cave"));

        assertEquals(List.of("Defeat #o4230108#: #r#a302001##k / 30", "Collect #t4000078#: #b#c4000078##k / 15",
                "Go to #m211042300#", "Bring Sigrun 15 #t4000078#"), lines.subList(lines.size() - 4, lines.size()));
    }

    @Test
    void twoMonstersCountInTheirOwnSlotsInCheckOrder() {
        Map<String, Object> entry = QuestLogExportMain.entry(CONTENT, CONTENT.quest("fenna-pig-problem"), 30014);
        List<String> lines = lines((String) entry.get("inProgress"));

        assertTrue(lines.contains("Defeat #o1210100#: #r#a300141##k / 20"));
        assertTrue(lines.contains("Defeat #o1210101#: #r#a300142##k / 10"));
        assertEquals(List.of(Map.of("id", 1210100, "count", 20), Map.of("id", 1210101, "count", 10)), entry.get("mobs"),
                "Check.img/1 mob 0 and 1 are slots 1 and 2");
    }

    @Test
    void aLabelledItemKeepsItsLabel() {
        List<String> lines = lines(inProgress("garrick-lost-badge"));

        assertTrue(lines.contains("Collect Garrick's unit badge: #b#c4000021##k / 1"), lines.toString());
        assertTrue(lines.contains("Bring Garrick 1 Garrick's unit badge"), lines.toString());
    }

    @Test
    void aQuestWithoutAnAcceptLineGoesStraightToItsSteps() {
        Quest quest = CONTENT.quests().values().stream()
                .filter(q -> CONTENT.persona(q.persona()) != null && CONTENT.persona(q.persona()).dialogue() != null
                        && !CONTENT.persona(q.persona()).dialogue().containsKey("questAccept." + q.id()))
                .findFirst().orElseThrow();
        List<String> lines = lines(inProgress(quest.id()));

        assertTrue(lines.get(0).startsWith("#b") && lines.get(0).endsWith(")#k"), lines.get(0));
        assertEquals("", lines.get(1), "a blank line, then the steps");
    }

    /** Every count in every shipped quest's text reads a target its Check.img/1 entry lists, in the right slot. */
    @Test
    void everyShippedQuestCountsWhatItsCheckEntryLists() {
        for (Quest quest : CONTENT.quests().values()) {
            int logId = CONTENT.questLogId(quest.id());
            String text = inProgress(quest.id());
            List<QuestLogTargets.Target> mobs = QuestLogTargets.mobs(quest);
            List<Integer> items = QuestLogTargets.items(quest).stream().map(QuestLogTargets.Target::id).toList();
            assertFalse(text.contains("\n") || text.contains("\r"), quest.id() + ": no raw line breaks");

            int codedKills = 0;
            for (String line : lines(text)) {
                Matcher kills = KILLS.matcher(line);
                if (kills.find()) {
                    codedKills++;
                    int code = Integer.parseInt(kills.group(1));
                    int slot = code % 10;
                    assertEquals(logId, code / 10, quest.id() + ": " + line);
                    assertTrue(slot >= 1 && slot <= mobs.size(), quest.id() + ": slot " + slot + " of " + mobs.size());
                    Matcher mob = MOB.matcher(line);
                    assertTrue(mob.find(), quest.id() + ": " + line);
                    assertEquals(mobs.get(slot - 1).id(), Integer.parseInt(mob.group(1)), quest.id() + ": " + line);
                }
                Matcher held = HELD.matcher(line);
                while (held.find()) {
                    assertTrue(items.contains(Integer.parseInt(held.group(1))), quest.id() + ": " + line);
                }
            }
            long killSteps = quest.steps().stream().filter(QuestLogExportTextTest::isKill).count();
            assertEquals(killSteps, codedKills, quest.id() + ": every kill step counts live");
        }
    }

    private static boolean isKill(Step step) {
        return step.type() == StepType.KILL_MOBS || step.type() == StepType.KILL_BOSS || step.type() == StepType.HUNT_WITH;
    }
}
