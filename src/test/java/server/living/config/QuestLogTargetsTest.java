package server.living.config;

import org.junit.jupiter.api.Test;
import server.living.config.Content.Quest;
import server.living.config.QuestLogTargets.Target;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the client's quest log (Quest Helper, detail text counts) tracks for a living quest. */
class QuestLogTargetsTest {
    private static final LivingContent CONTENT = ContentLoader.load(Path.of("scripts/config/living-world"));

    @Test
    void aKillStepTracksItsFirstMonsterAndACollectStepItsItem() {
        Quest nightLog = CONTENT.quest("corvin-the-night-log"); // TALK, KILL_MOBS 30, COLLECT_ITEM 10, DELIVER
        Content.Step kill = nightLog.step(1);
        Content.Step collect = nightLog.step(2);

        assertEquals(List.of(new Target(kill.mobIds().get(0), kill.count())), QuestLogTargets.mobs(nightLog));
        assertEquals(List.of(new Target(collect.itemId(), collect.count())), QuestLogTargets.items(nightLog));
    }

    @Test
    void killsFollowTheLedgerStep() {
        Quest nightLog = CONTENT.quest("corvin-the-night-log");
        int zombie = nightLog.step(1).mobIds().get(0);

        assertEquals(Map.of(zombie, 0), QuestLogTargets.kills(nightLog, 0, 0), "before the kill step");
        assertEquals(Map.of(zombie, 12), QuestLogTargets.kills(nightLog, 1, 12), "during it");
        assertEquals(Map.of(zombie, 30), QuestLogTargets.kills(nightLog, 1, 45), "never past the count");
        assertEquals(Map.of(zombie, 30), QuestLogTargets.kills(nightLog, 2, 4), "after it, in full");
    }

    @Test
    void aQuestWithoutKillsOrCollectsTracksNothing() {
        Quest walk = CONTENT.quest("pim-cat-and-cook"); // TALK, TALK_TO, DELIVER
        assertTrue(QuestLogTargets.mobs(walk).isEmpty());
        assertTrue(QuestLogTargets.items(walk).isEmpty());
        assertTrue(QuestLogTargets.kills(walk, 1, 0).isEmpty());
    }

    @Test
    void eachKillStepReadsItsMonstersSlot() {
        Quest pigs = CONTENT.quest("fenna-pig-problem"); // TALK, KILL_MOBS Pig 20, KILL_MOBS Ribbon Pig 10, DELIVER

        assertEquals(0, QuestLogTargets.mobSlot(pigs, pigs.step(0)), "a talk step has no slot");
        assertEquals(1, QuestLogTargets.mobSlot(pigs, pigs.step(1)));
        assertEquals(2, QuestLogTargets.mobSlot(pigs, pigs.step(2)));
        assertEquals(0, QuestLogTargets.mobSlot(pigs, pigs.step(3)));
    }

    @Test
    void everyQuestFitsTheDetailTextsOneDigitSlots() {
        for (Quest quest : CONTENT.quests().values()) {
            assertTrue(QuestLogTargets.mobs(quest).size() <= QuestLogTargets.CODED_SLOTS, quest.id());
        }
    }

    @Test
    void everyQuestFitsTheClientsThreeDigitCounts() {
        for (Quest quest : CONTENT.quests().values()) {
            for (Target target : QuestLogTargets.mobs(quest)) {
                assertTrue(target.count() <= 999, quest.id() + " asks for " + target.count());
            }
        }
    }
}
