package server.living.quest;

import org.junit.jupiter.api.Test;
import server.knowledge.KnowledgeTestKit;
import server.living.LivingWorld;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Quest steps say where (docs/SERVER_KNOWLEDGE_PLAN.md 6.4): a map's name and the town it is near instead of its id,
 * an NPC's name and town instead of "NPC id"; the old text while server knowledge is not loaded.
 */
class QuestLedgerWhereTextTest {
    private static final QuestState STATE = new QuestState(1, "q", 0, 0, QuestStatus.ACTIVE, 0L, 0L, 0);

    @Test
    void stepsNameTheMapAndTheNpcsTown() throws Exception {
        QuestLedger ledger = new QuestLedger(mock(LivingWorld.class));
        Step visit = step(StepType.VISIT_MAP, List.of(104_010_001), null);
        Step talk = step(StepType.TALK_TO, List.of(), "npc:1012100");
        assertEquals("go to map 104010001", ledger.describe(visit, STATE));
        assertEquals("talk to NPC 1012100", ledger.describe(talk, STATE));
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            assertEquals("go to Pig Beach, near Lith Harbor", ledger.describe(visit, STATE));
            assertEquals("talk to Athena Pierce in Henesys", ledger.describe(talk, STATE));
        }
    }

    private static Step step(StepType type, List<Integer> maps, String persona) {
        return new Step("s", type, List.of(), 0, 0, List.of(), maps, null, persona, null, 0, 0, 0, null, null, null, null);
    }
}
