package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static server.agents.capabilities.partyquest.epq.AgentEpqMemberVerdicts.Verdict;

/**
 * Step 4.1: the rule table for a companion run's non-leader members, as an explicit expected table over both member
 * types: a vanished companion is missing, a vanished player inside the event is dropped; after the clear nothing is
 * asked of a dead or departed member; a departed companion is tapped out (kept, idle, when its host refuses); a corpse
 * waits with no time limit until no companion stands; a member up again is revived.
 */
class AgentEpqMemberVerdictTest {
    private static final AgentEpqMemberState.MemberType AGENT = AgentEpqMemberState.MemberType.AGENT;
    private static final AgentEpqMemberState.MemberType HUMAN = AgentEpqMemberState.MemberType.HUMAN;
    private static final AgentEpqSession.Phase STAGE = AgentEpqSession.Phase.STAGE_TWO;
    private static final long HOUR = 3_600_000L;

    private record Row(String what, AgentEpqMemberVerdicts.Facts facts, Verdict expected) { }

    /** A member in the event on stage 2, up, the leader in the event, two other companions standing. */
    private static AgentEpqMemberVerdicts.Facts facts(AgentEpqMemberState.MemberType type, boolean present, int hp,
                                                      boolean inEvent, boolean leaderIn, boolean cleared,
                                                      boolean departed, long downSince, boolean requested,
                                                      boolean refused, int standing, AgentEpqSession.Phase phase) {
        return new AgentEpqMemberVerdicts.Facts(phase, type, present, hp, inEvent, leaderIn, cleared, departed,
                downSince, requested, refused, standing);
    }

    private static AgentEpqMemberVerdicts.Facts up(AgentEpqMemberState.MemberType type) {
        return facts(type, true, 500, true, true, false, false, 0L, false, false, 2, STAGE);
    }

    @Test
    void theExpectedTable() {
        List<Row> rows = List.of(
                new Row("a companion up in the event", up(AGENT), Verdict.OK),
                new Row("a player up in the event", up(HUMAN), Verdict.OK),
                new Row("an unloaded companion", facts(AGENT, false, 0, false, true, false, false, 0L, false, false, 2,
                        STAGE), Verdict.MISSING),
                new Row("a player gone offline inside the event", facts(HUMAN, false, 0, false, true, false, false, 0L,
                        false, false, 2, STAGE), Verdict.DROP_HUMAN),
                new Row("a player gone offline before the entry", facts(HUMAN, false, 0, false, true, false, false, 0L,
                        false, false, 2, AgentEpqSession.Phase.ENTERING), Verdict.MISSING),
                new Row("a player gone offline after the clear", facts(HUMAN, false, 0, false, true, true, false, 0L,
                        false, false, 2, AgentEpqSession.Phase.BOSS), Verdict.IGNORE),
                new Row("a dead companion after the clear", facts(AGENT, true, 0, true, true, true, false, 1_000L,
                        false, false, 2, AgentEpqSession.Phase.BOSS), Verdict.IGNORE),
                new Row("a departed companion after the clear", facts(AGENT, true, 500, false, true, true, true, 0L,
                        false, false, 2, AgentEpqSession.Phase.BOSS), Verdict.IGNORE),
                new Row("a companion up after the clear", facts(AGENT, true, 500, true, true, true, false, 0L, false,
                        false, 2, AgentEpqSession.Phase.BOSS), Verdict.OK),
                new Row("a dead companion before the entry", facts(AGENT, true, 0, false, true, false, false, 0L,
                        false, false, 2, AgentEpqSession.Phase.PREPARING), Verdict.OK),
                new Row("the leader out of the event", facts(AGENT, true, 0, true, false, false, false, 1_000L, false,
                        false, 2, STAGE), Verdict.OK),
                new Row("a departed player", facts(HUMAN, true, 500, false, true, false, true, 0L, false, false, 2,
                        STAGE), Verdict.DROP_HUMAN),
                new Row("a departed companion", facts(AGENT, true, 500, false, true, false, true, 0L, false, false, 2,
                        STAGE), Verdict.REQUEST_TAP_OUT),
                new Row("a departed companion whose tap-out was taken", facts(AGENT, true, 500, false, true, false,
                        true, 0L, true, false, 2, STAGE), Verdict.IGNORE),
                new Row("a departed companion whose host refused", facts(AGENT, true, 500, false, true, false, true,
                        0L, true, true, 2, STAGE), Verdict.KEEP_DEPARTED),
                new Row("a dead companion held at the floor, another standing", facts(AGENT, true, 0, true, true,
                        false, false, 1_000L, false, false, 1, STAGE), Verdict.DOWN_WAIT),
                new Row("a companion down for an hour while another stands", facts(AGENT, true, 0, true, true, false,
                        false, 1_000L, false, false, 1, AgentEpqSession.Phase.BOSS), Verdict.DOWN_WAIT),
                new Row("the last companion down", facts(AGENT, true, 0, true, true, false, false, 1_000L, false,
                        false, 0, STAGE), Verdict.SHORT_HANDED),
                new Row("a dead player with no companion standing", facts(HUMAN, true, 0, true, true, false, false,
                        1_000L, false, false, 0, STAGE), Verdict.DOWN_WAIT),
                new Row("a companion up again", facts(AGENT, true, 300, true, true, false, false, 1_000L, false, false,
                        2, STAGE), Verdict.REVIVED),
                new Row("a player up again", facts(HUMAN, true, 300, true, true, false, false, 1_000L, false, false, 2,
                        STAGE), Verdict.REVIVED));
        for (Row row : rows) assertEquals(row.expected(), AgentEpqMemberVerdicts.verdict(row.facts()), row.what());
        assertEquals(Verdict.DOWN_WAIT, AgentEpqMemberVerdicts.verdict(facts(AGENT, true, 0, true, true, false, false,
                1_000L, false, false, 1, STAGE)), "no time limit: " + HOUR + " ms later the facts are the same");
    }

    @Test
    void insideTheEventIsStageOneThroughTheGolem() {
        for (AgentEpqSession.Phase phase : AgentEpqSession.Phase.values()) {
            boolean expected = phase.ordinal() >= AgentEpqSession.Phase.STAGE_ONE.ordinal()
                    && phase.ordinal() <= AgentEpqSession.Phase.BOSS.ordinal();
            assertEquals(expected, AgentEpqMemberVerdicts.insideEvent(phase), phase.name());
        }
    }
}
