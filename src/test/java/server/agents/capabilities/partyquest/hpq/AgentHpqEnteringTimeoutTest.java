package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;

/**
 * A companion run's entry is bounded by COMPANION_ENTRY_WAIT_MS, the 30 s '!hpqtest party prod' promises (plan 2.1):
 * the leader must click Tory within it, and once he is in, everyone must follow within it; three or more inside go on
 * without the absent ones, fewer end the run without a word.
 */
class AgentHpqEnteringTimeoutTest {
    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(0, 83));
    private final Character mira = run.companion(2, "Mira", new Point(10, 83));
    private final Character tobi = run.companion(3, "Tobi", new Point(20, 83));
    private final Character pip = run.companion(4, "Pip", new Point(30, 83));

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void theEntryWaitIsTheThirtySecondsTheAdoptionPromises() throws Exception {
        long waitMs = config.AgentTuning.longValue(
                "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.COMPANION_ENTRY_WAIT_MS");
        String testService = Files.readString(Path.of("src", "main", "java", "server", "agents", "capabilities",
                "partyquest", "hpq", "AgentHpqTestService.java"));

        assertEquals(30_000L, waitMs);
        assertTrue(testService.contains("Talk to Tory within " + waitMs / 1_000L + " seconds."));
    }

    @Test
    void aLeaderWhoNeverClicksToryEndsTheRunQuietlyAfterTheWait() {
        for (Character member : new Character[] {ben, mira, tobi}) run.outside(member, AgentHpqDefinition.RECRUIT_MAP);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.PREPARING, ben, mira, tobi);

        AgentHpqCoordinator.tick(session, 30_999L);
        assertEquals(AgentHpqSession.Phase.PREPARING, session.phase());

        AgentHpqCoordinator.tick(session, 31_000L);
        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.saidNothing();
    }

    @Test
    void everyoneInsideStartsTheSeedsAndTheCompanionsSaySo() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.ENTERING, ben, mira, tobi);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.COLLECTING_SEEDS, session.phase());
        run.said(session, "seeds-start", mira,
                "we'll get the seeds. stay near the middle and keep the monsters off the bunny when it comes out.");
        assertEquals("{Mira:40/0, Tobi:40/0}", session.potionsAtEntry());
    }

    @Test
    void afterTheWaitThreeInsideGoOnWithoutTheAbsentOne() {
        run.outside(pip, AgentHpqDefinition.RECRUIT_MAP);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.ENTERING, ben, mira, tobi, pip);

        AgentHpqCoordinator.tick(session, 30_999L);
        assertEquals(AgentHpqSession.Phase.ENTERING, session.phase());

        AgentHpqCoordinator.tick(session, 31_000L);

        assertEquals(AgentHpqSession.Phase.COLLECTING_SEEDS, session.phase());
        assertNull(session.member(pip.getId()));
        assertNull(AgentHpqSessionRegistry.forMember(pip.getId()));
        assertSame(session, AgentHpqSessionRegistry.forMember(mira.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(pip.getId(), "hpq", 31_000L));
    }

    @Test
    void afterTheWaitFewerThanThreeInsideEndTheRunQuietly() {
        run.outside(tobi, AgentHpqDefinition.RECRUIT_MAP);
        run.outside(pip, AgentHpqDefinition.RECRUIT_MAP);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.ENTERING, ben, mira, tobi, pip);

        AgentHpqCoordinator.tick(session, 31_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.saidNothing();
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.recoverDetachedMember(anyInt(), anyString(),
                anyLong()), never());
    }
}
