package server.agents.runtime.activity.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.agents.runtime.activity.session.AgentActivityKind;
import server.agents.runtime.activity.world.AgentFileWorldDirectiveInbox;
import server.agents.runtime.activity.world.AgentFileWorldDirectorSessionStore;
import server.agents.runtime.activity.world.AgentWorldActivityRequestType;
import server.agents.runtime.activity.world.AgentWorldCompletionPolicy;
import server.agents.runtime.activity.world.AgentWorldDirective;
import server.agents.runtime.activity.world.AgentWorldDirectiveSource;
import server.agents.runtime.activity.world.AgentWorldDirectiveStatus;
import server.agents.runtime.activity.world.AgentWorldDirectiveType;
import server.agents.runtime.activity.world.AgentWorldDirectorMode;
import server.agents.runtime.activity.world.AgentWorldDirectorSession;
import server.agents.runtime.activity.world.AgentWorldInterruptionPolicy;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A directive poll that found nothing skips the file reads until a file changes or the full-read time is up. */
class AgentWorldDirectivePollStateTest {
    @TempDir Path directory;

    @Test
    void skipsOnlyWhileIdleAndUnchangedAndReadsInFullAgainInTime() {
        AgentWorldDirectivePollState poll = new AgentWorldDirectivePollState();
        assertFalse(poll.unchanged(7L, 1_000L, 30_000L)); // never polled: read the files

        poll.idle(7L, 1_000L);
        assertTrue(poll.unchanged(7L, 2_000L, 30_000L));
        assertFalse(poll.unchanged(8L, 2_000L, 30_000L)); // a file changed
        assertFalse(poll.unchanged(7L, 31_000L, 30_000L)); // the full read is due

        poll.idle(7L, 31_000L); // the same stamp keeps its first idle time, so the next full read is not pushed out
        assertFalse(poll.unchanged(7L, 31_001L, 30_000L));
        poll.idle(9L, 31_001L);
        assertTrue(poll.unchanged(9L, 40_000L, 30_000L));

        poll.busy(); // a directive pending, claimed or in progress: every poll reads the files
        assertFalse(poll.unchanged(9L, 40_001L, 30_000L));
    }

    @Test
    void stampsMoveWhenADirectiveOrTheSessionIsWritten() throws Exception {
        AgentFileWorldDirectiveInbox inbox = new AgentFileWorldDirectiveInbox(directory.resolve("directives"));
        AgentFileWorldDirectorSessionStore sessions =
                new AgentFileWorldDirectorSessionStore(directory.resolve("sessions"));
        assertEquals(0L, inbox.changeStamp(27));
        assertEquals(0L, sessions.changeStamp(27));

        sessions.save(AgentWorldDirectorSession.create(27, AgentWorldDirectorMode.AUTONOMOUS, 1_000L));
        long session = sessions.changeStamp(27);
        assertNotEquals(0L, session);

        AgentWorldDirective directive = new AgentWorldDirective(1, "policy:27:a:1", 27,
                AgentWorldDirectiveType.START_ACTIVITY, AgentWorldDirectiveSource.POLICY, null,
                AgentActivityKind.HUNTING, AgentWorldActivityRequestType.AUTHORED_PLAN, "plan-a", Map.of(),
                AgentWorldInterruptionPolicy.WAIT_FOR_SAFE_BOUNDARY, AgentWorldCompletionPolicy.REQUEST_NEXT_DECISION,
                100, 1_000L, 0L, "test");
        inbox.submit(directive, 1_000L);
        long submitted = inbox.changeStamp(27);
        assertNotEquals(0L, submitted);

        Thread.sleep(20L); // file timestamps on some file systems are coarse
        inbox.resolve(27, directive.directiveId(), AgentWorldDirectiveStatus.COMPLETED, "done", 2_000L);
        assertNotEquals(submitted, inbox.changeStamp(27));
        sessions.save(AgentWorldDirectorSession.create(27, AgentWorldDirectorMode.MANUAL, 2_000L));
        assertNotEquals(session, sessions.changeStamp(27));
    }
}
