package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LPQ base fix 3: damage to Alishar counts as stage-9 progress. While he lives nothing else in the boss phase marks
 * progress once the Black Ratz is dead, so a fight still dealing damage used to fail at the phase timeout
 * ("No LPQ progress before phase timeout", 10 minutes after the last mark).
 */
class AgentLpqStageNineProgressTest {
    private static final long PHASE_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqCoordinator.PHASE_TIMEOUT_MS");

    private static AgentLpqSession session() {
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 7L, 762_500, 6, 0L);
        session.addMember(762_500, AgentLpqMemberState.MemberType.HUMAN);
        session.addMember(762_501, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(762_500, 762_501);
        session.transition(AgentLpqSession.Phase.STAGE_9, 1_000L);
        return session;
    }

    @Test
    void onlyAFallInHpIsProgress() {
        AgentLpqSession session = session();

        assertFalse(session.observeStageNineBossHp(125_000L), "the first sight only sets the baseline");
        assertTrue(session.observeStageNineBossHp(124_000L));
        assertFalse(session.observeStageNineBossHp(124_000L), "no damage, no progress");
        assertFalse(session.observeStageNineBossHp(124_500L), "a heal is not progress");
        assertTrue(session.observeStageNineBossHp(124_400L), "the higher value became the baseline");

        session.transition(AgentLpqSession.Phase.BONUS, 2_000L);
        session.transition(AgentLpqSession.Phase.STAGE_9, 3_000L);
        assertFalse(session.observeStageNineBossHp(100_000L), "a phase change starts a new baseline");
    }

    @Test
    void aFightThatKeepsDealingDamageNeverTimesOut() {
        AgentLpqSession session = session();
        Monster alishar = mock(Monster.class);
        long hp = 125_000L;
        long last = 0L;
        // A slow fight (4.5k a minute, a quarter of Step 0's rate) of twenty minutes, sampled every 30 s.
        for (long nowMs = 5_000L; nowMs <= 1_205_000L; nowMs += 30_000L) {
            hp -= 2_250L;
            when(alishar.getHp()).thenReturn(hp);
            AgentLpqCoordinator.observeStageNineBossDamage(session, alishar, nowMs);
            assertTrue(nowMs - session.lastProgressAtMs() <= PHASE_TIMEOUT_MS, "timed out at " + nowMs);
            last = nowMs;
        }
        assertEquals(last, session.lastProgressAtMs(), "progress follows the last fall in HP");

        when(alishar.getHp()).thenReturn(hp);
        AgentLpqCoordinator.observeStageNineBossDamage(session, alishar, last + 30_000L);
        assertEquals(last, session.lastProgressAtMs(), "a stalled fight is not progress");
        AgentLpqCoordinator.observeStageNineBossDamage(session, null, last + 60_000L);
        assertEquals(last, session.lastProgressAtMs(), "no Alishar, nothing to observe");
    }

    @Test
    void theBossPassObservesAlisharBeforeOrderingTheMembers() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/partyquest/lpq/AgentLpqCoordinator.java"))
                .replace("\r\n", "\n");
        int start = source.indexOf("    private static void boss(");
        String boss = source.substring(start, source.indexOf("\n    }\n", start)).replaceAll("\\s+", " ");
        int resolved = boss.indexOf("boolean alisharAlive = alishar != null;");
        int observed = boss.indexOf("observeStageNineBossDamage(session, alishar, nowMs);");
        int loop = boss.indexOf("for (AgentLpqMemberState member : session.members())");
        assertTrue(resolved > 0 && observed > resolved && loop > observed,
                "observed right after Alishar is resolved and before the member loop");

        int helper = source.indexOf("    static void observeStageNineBossDamage(");
        String body = source.substring(helper, source.indexOf("\n    }\n", helper)).replaceAll("\\s+", " ");
        assertTrue(body.contains("if (alishar != null && session.observeStageNineBossHp(alishar.getHp())) {"
                + " session.markProgress(nowMs); }"), body);
    }
}
