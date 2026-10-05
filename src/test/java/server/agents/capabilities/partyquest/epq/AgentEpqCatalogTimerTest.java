package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EPQ's catalog timer is EllinPQ's own: 30 minutes, which the script hands to startEventTimer. */
class AgentEpqCatalogTimerTest {
    private static final Pattern EVENT_TIME = Pattern.compile("(?m)^var eventTime = (\\d+);");

    @Test
    void epqsTimerIsEllinPqsEventTime() throws Exception {
        assertEquals(1_800_000L, AgentPartyQuestCatalog.require("epq").eventTimerMs());

        String script = Files.readString(Path.of("scripts", "event", "EllinPQ.js"));
        assertTrue(script.contains("eim.startEventTimer(eventTime * 60000)"));
        Matcher matcher = EVENT_TIME.matcher(script);
        assertTrue(matcher.find(), "EllinPQ declares no eventTime");
        assertEquals(Long.parseLong(matcher.group(1)) * 60_000L, AgentPartyQuestCatalog.require("epq").eventTimerMs());
    }
}
