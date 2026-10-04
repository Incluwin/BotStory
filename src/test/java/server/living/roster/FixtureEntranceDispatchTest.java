package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * A quest past HPQ goes through its FixtureEntrance at the fixture's doors, at its session's end and at the close:
 * admission registers the session the entrance admitted, the start reads that session, the end removes it from the
 * quest's registry and hands the lifecycle what the entrance says the Agent layer tells the host, and the close
 * removes it again. No quest past HPQ has a slot filled yet, so a test-only stub ("zzpq") stands in for one.
 */
class FixtureEntranceDispatchTest {
    @Test
    void aQuestPastHpqGoesThroughItsFixtureEntranceFromItsDoorsToTheClose() {
        Stub zz = new Stub();
        try (CompanionPqFixture fx = new CompanionPqFixture()) {
            fx.plays("zzpq", zz);
            Character mira = fx.game.bot(fx.companion("mira", "Mira", 900, 22, Job.HUNTER));
            Character tobi = fx.game.bot(fx.companion("tobi", "Tobi", 901, 23, Job.PAGE));
            fx.lifecycle = mock(CompanionRunLifecycle.class);

            PqEntryPort.Admitted admitted = fx.doors.admit("zzpq", fx.game.ann, List.of(fx.game.ann, mira, tobi), 7L,
                    1_000L);

            assertSame(zz.session, admitted.session());
            assertEquals("zz-1", fx.doors.sessionId("zzpq"), "the doors keep the session the entrance admitted");
            assertNull(fx.doors.session, "no KPQ session");
            assertNull(fx.doors.hpqSession, "nor an HPQ one");

            assertEquals(PqEntryPort.Start.STARTED, fx.doors.startInstance("zzpq", fx.game.ann));
            assertTrue(fx.doors.adoptedAtStart, "the start read the quest's own session");
            assertEquals("start zzpq", fx.game.calls.get(fx.game.calls.size() - 1));

            fx.pqKey = "zzpq"; // as atEntrance("zzpq") sets it once the quest has a PqEntrance
            fx.sessionEnds(true);

            assertEquals(List.of("admit 3 seed 7", "remove zz-1", "ended zz-1 true"), zz.calls);
            verify(fx.lifecycle).runEnded(zz.ended);
        }
        assertEquals(List.of("admit 3 seed 7", "remove zz-1", "ended zz-1 true", "remove zz-1"), zz.calls,
                "the close removes the session from the quest's registry, as it does KPQ's and HPQ's");
    }

    @Test
    void theFixtureNeverPlaysAQuestTwiceAndKeepsATestsQuestOutOfItsKeys() {
        try (CompanionPqFixture fx = new CompanionPqFixture()) {
            assertThrows(IllegalArgumentException.class, () -> fx.plays("kpq", new Stub()));
            assertThrows(IllegalArgumentException.class, () -> fx.plays("hpq", new Stub()));
            fx.plays("zzpq", new Stub());
            assertThrows(IllegalArgumentException.class, () -> fx.plays("zzpq", new Stub()));

            assertEquals(PqEntrance.keys(), CompanionPqFixture.keys());
        }
    }

    /** A quest past HPQ as a test plays it: one session, "zz-1", and a log of what the fixture asked of it. */
    private static final class Stub implements CompanionPqFixture.FixtureEntrance {
        final List<String> calls = new ArrayList<>();
        final AgentCompanionRunSession session = mock(AgentCompanionRunSession.class);
        AgentPartyQuestHandback.RunEnded ended;

        Stub() {
            when(session.sessionId()).thenReturn("zz-1");
            when(session.companionAdopted()).thenReturn(true);
        }

        @Override
        public Point npcAt() {
            calls.add("npcAt");
            return new Point(0, 0);
        }

        @Override
        public Point groundBelow(Point at) {
            calls.add("groundBelow");
            return null;
        }

        @Override
        public PqEntryPort.Admitted admit(CompanionPqFixture fixture, Character leader, List<Character> members,
                                          long seed, long nowMs) {
            calls.add("admit " + members.size() + " seed " + seed);
            return new PqEntryPort.Admitted(true, "ZZPQ party admitted", session);
        }

        @Override
        public void remove(AgentCompanionRunSession removed) {
            calls.add("remove " + removed.sessionId());
        }

        @Override
        public AgentPartyQuestHandback.RunEnded runEnded(AgentCompanionRunSession ending, boolean success,
                                                         AgentPqPace pace) {
            calls.add("ended " + ending.sessionId() + " " + success);
            ended = new AgentPartyQuestHandback.RunEnded("zzpq", ending.sessionId(), ANN, success,
                    success ? "completed" : "ZZPQ stopped", 5_000L, pace);
            return ended;
        }

        @Override
        public int exitMap() {
            return 0;
        }
    }
}
