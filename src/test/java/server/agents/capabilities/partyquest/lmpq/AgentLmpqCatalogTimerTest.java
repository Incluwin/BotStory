package server.agents.capabilities.partyquest.lmpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LMPQ's catalog event timer is LudiMazePQ.js's own (15 minutes, handed to startEventTimer), so a clear-time estimate
 * weighs a lineup against the clock an LMPQ party really gets.
 */
class AgentLmpqCatalogTimerTest {
    private static final Pattern EVENT_TIME = Pattern.compile("(?m)^var eventTime = (\\d+);");

    @Test
    void lmpqsTimerIsLudiMazePqsEventTime() throws Exception {
        assertEquals(900_000L, AgentPartyQuestCatalog.require("lmpq").eventTimerMs());
        assertEquals(scriptTimerMs("LudiMazePQ"), AgentPartyQuestCatalog.require("lmpq").eventTimerMs());
    }

    private static long scriptTimerMs(String eventManagerName) throws Exception {
        String script = Files.readString(Path.of("scripts", "event", eventManagerName + ".js"));
        assertTrue(script.contains("eim.startEventTimer(eventTime * 60000)"), eventManagerName);
        Matcher matcher = EVENT_TIME.matcher(script);
        assertTrue(matcher.find(), eventManagerName + " declares no eventTime");
        return Long.parseLong(matcher.group(1)) * 60_000L;
    }
}
