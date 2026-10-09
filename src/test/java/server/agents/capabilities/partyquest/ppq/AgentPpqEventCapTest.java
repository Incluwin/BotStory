package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * PPQ Step 1b.4 (D6; every mode): PiratePQ.js's stage timers run about 54 minutes end to end without the chest rooms
 * and about 70 with both, so a run still making progress at 60 minutes goes on; at 76 minutes the cap ends it. The runs
 * here are Agent-led with their human out of the event: the cap never fires while a human is inside.
 */
class AgentPpqEventCapTest {
    private static final long MINUTE = 60_000L;

    /** Mira leads; Ben, the human member, is not in the event. */
    private static PpqRunFixture agentsOnlyInside() {
        PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_TWO_MAP, AgentPpqSession.Mode.HUMAN_MEMBER);
        when(run.character(PpqRunFixture.BEN).getEventInstance()).thenReturn(null);
        return run;
    }

    /** Ticks every minute up to {@code minutes}, with progress each time (the stall watch never fires). */
    private static void runFor(PpqRunFixture run, int minutes) {
        for (int minute = 1; minute <= minutes && !run.session.terminal(); minute++) {
            long now = 1_000L + minute * MINUTE;
            run.session.markProgress(now);
            run.tick(now);
        }
    }

    @Test
    void anHourIntoAProgressingRunItGoesOn() {
        try (PpqRunFixture run = agentsOnlyInside()) {
            runFor(run, 60);

            assertFalse(run.session.terminal(), run.session.failure());
        }
    }

    @Test
    void seventySixMinutesInTheCapEndsIt() {
        try (PpqRunFixture run = agentsOnlyInside()) {
            runFor(run, 76);

            assertEquals("PPQ event timer expired", run.session.failure());
        }
    }
}
