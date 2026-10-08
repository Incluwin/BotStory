package server.agents.capabilities.partyquest.opq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6h OPQ step 1 (C6): OPQ's catalog event timer is OrbisPQ.js's own (45 minutes, handed to startEventTimer), so a
 * clear-time estimate weighs a lineup against the clock an OPQ party really gets.
 */
class AgentOpqCatalogTimerTest {
    private static final Pattern EVENT_TIME = Pattern.compile("(?m)^var eventTime = (\\d+);");

    @Test
    void opqsTimerIsOrbisPqsEventTime() throws Exception {
        assertEquals(2_700_000L, AgentPartyQuestCatalog.require("opq").eventTimerMs());
        assertEquals(scriptTimerMs("OrbisPQ"), AgentPartyQuestCatalog.require("opq").eventTimerMs());
    }

    @Test
    void theRestOfTheRowIsUnchanged() {
        var opq = AgentPartyQuestCatalog.require("opq");
        assertEquals("OrbisPQ", opq.eventManagerName());
        assertEquals(5, opq.minimumPartySize());
        assertEquals(6, opq.maximumPartySize());
    }

    private static long scriptTimerMs(String eventManagerName) throws Exception {
        String script = Files.readString(Path.of("scripts", "event", eventManagerName + ".js"));
        assertTrue(script.contains("eim.startEventTimer(eventTime * 60000)"), eventManagerName);
        Matcher matcher = EVENT_TIME.matcher(script);
        assertTrue(matcher.find(), eventManagerName + " declares no eventTime");
        return Long.parseLong(matcher.group(1)) * 60_000L;
    }
}
