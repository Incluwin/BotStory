package server.living.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * quest-log-ids.yaml as shipped: every key is a shipped quest's id (a renamed key leaves its quest without a client
 * quest-log line), and every client id is used once, in Quest.wz's custom range 30000-32767.
 */
class QuestLogIdsShippedTest {

    @Test
    void everyQuestLogKeyIsAShippedQuestWithItsOwnClientId() {
        LivingContent content = ContentLoader.load(Path.of("scripts/config/living-world"));
        Map<String, Integer> ids = content.questLogIds();
        assertTrue(ids.size() > 200, "the shipped quest-log ids load: " + ids.size());

        List<String> orphans = ids.keySet().stream().filter(quest -> !content.quests().containsKey(quest)).toList();
        assertEquals(List.of(), orphans, "quest-log keys with no quest of that id");

        Map<Integer, String> owners = new HashMap<>();
        ids.forEach((quest, id) -> {
            assertTrue(id >= 30000 && id <= 32767, quest + " -> " + id + " is outside 30000-32767");
            String earlier = owners.putIfAbsent(id, quest);
            assertTrue(earlier == null, "client id " + id + " is both " + earlier + " and " + quest);
        });
    }
}
