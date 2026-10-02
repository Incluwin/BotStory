package server.agents.capabilities.partyquest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A catalog event timer is the event script's own ({@code eventTime} minutes, which the script hands to
 * startEventTimer), so the clear-time estimate weighs a lineup against the clock the party really gets.
 */
class AgentPartyQuestCatalogTimerTest {
    private static final Pattern EVENT_TIME = Pattern.compile("(?m)^var eventTime = (\\d+);");

    @Test
    void kpqsTimerIsKerningPqsEventTime() throws Exception {
        assertEquals(scriptTimerMs("KerningPQ"), AgentPartyQuestCatalog.require("kpq").eventTimerMs());
        assertEquals(1_800_000L, AgentPartyQuestCatalog.require("kpq").eventTimerMs());
    }

    @Test
    void everyRecordedTimerMatchesItsScriptAndTheRestAreUnknown() throws Exception {
        for (AgentPartyQuestDefinition definition : AgentPartyQuestCatalog.definitions()) {
            if (definition.eventTimerMs() != 0L) {
                assertEquals(scriptTimerMs(definition.eventManagerName()), definition.eventTimerMs(),
                        definition.questKey());
            }
        }
    }

    @Test
    void theOlderConstructorRecordsNoTimerAndANegativeOneIsRejected() {
        AgentPartyQuestDefinition untimed = new AgentPartyQuestDefinition(
                "test", "TestPQ", 1, 2, 3, 4, 5, 10, 20, 3, 4);
        assertEquals(0L, untimed.eventTimerMs());
        assertThrows(IllegalArgumentException.class, () -> new AgentPartyQuestDefinition(
                "test", "TestPQ", 1, 2, 3, 4, 5, 10, 20, 3, 4, -1L));
    }

    private static long scriptTimerMs(String eventManagerName) throws Exception {
        String script = Files.readString(Path.of("scripts", "event", eventManagerName + ".js"));
        assertTrue(script.contains("eim.startEventTimer(eventTime * 60000)"), eventManagerName);
        Matcher matcher = EVENT_TIME.matcher(script);
        assertTrue(matcher.find(), eventManagerName + " declares no eventTime");
        return Long.parseLong(matcher.group(1)) * 60_000L;
    }
}
