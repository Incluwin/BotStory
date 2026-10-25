package server.agents.capabilities.partyquest.opq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A lounge runner counts the ten fragments of its subroom from its bag when it came in (beginLoungeCollection). The
 * coordinator's stall refresh (clearLocalWork) used to drop that baseline, so the next pass re-took it at the runner's
 * current bag: six collected, a stall, and the subroom then wanted ten more from boxes already broken.
 */
class AgentOpqLoungeSubroomCountTest {
    private static final int SUBROOM = AgentOpqDefinition.LOUNGE_ROOM_MAPS.get(3);

    @Test
    void aStallRefreshKeepsTheSubroomsCount() {
        AgentOpqMemberState runner = new AgentOpqMemberState(1, AgentOpqMemberState.MemberType.AGENT);
        runner.assign(AgentOpqMemberState.Role.LOUNGE_RUNNER, AgentOpqDefinition.Room.LOUNGE, SUBROOM);
        runner.beginLoungeCollection(SUBROOM, 0);

        runner.clearLocalWork();
        runner.beginLoungeCollection(SUBROOM, 6);

        assertEquals(6, runner.loungeCollected(SUBROOM, 6));
        assertEquals(10, runner.loungeCollected(SUBROOM, 10));
    }

    @Test
    void anotherSubroomCountsAfresh() {
        AgentOpqMemberState runner = new AgentOpqMemberState(1, AgentOpqMemberState.MemberType.AGENT);
        runner.assign(AgentOpqMemberState.Role.LOUNGE_RUNNER, AgentOpqDefinition.Room.LOUNGE, SUBROOM);
        runner.beginLoungeCollection(SUBROOM, 0);
        int next = AgentOpqDefinition.LOUNGE_ROOM_MAPS.get(0);

        runner.assign(AgentOpqMemberState.Role.LOUNGE_RUNNER, AgentOpqDefinition.Room.LOUNGE, next);
        runner.beginLoungeCollection(next, 10);

        assertEquals(0, runner.loungeCollected(next, 10));
        assertEquals(10, runner.loungeCollected(next, 20));
    }
}
