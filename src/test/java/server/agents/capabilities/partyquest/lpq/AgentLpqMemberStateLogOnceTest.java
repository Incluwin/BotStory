package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stage-7 or stage-9 ratz warning is logged once per member and run (2026-10-07: 433 WARN lines in 9 minutes). It
 * no longer shares the one intent slot with the stock intent line, which therefore is said once too (on purpose).
 */
class AgentLpqMemberStateLogOnceTest {
    @Test
    void theNoRangedPlanWarningIsLoggedOncePerMemberAndRun() {
        AgentLpqMemberState member = new AgentLpqMemberState(401, AgentLpqMemberState.MemberType.AGENT);

        assertTrue(member.claimLogOnce("stage7:ratz-no-ranged-plan"));
        assertFalse(member.claimLogOnce("stage7:ratz-no-ranged-plan"));
        assertTrue(member.claimIntentAnnouncement("stage7:top:0"));
        assertFalse(member.claimLogOnce("stage7:ratz-no-ranged-plan"), "another key claimed meanwhile");
        assertTrue(member.claimLogOnce("stage7:ratz-rejected:RANGE"), "another warning is its own");
        assertFalse(member.claimLogOnce(""));
        assertFalse(member.claimLogOnce(null));
    }

    @Test
    void theStockIntentLineIsSaidOnceWhileTheWarningIsLoggedOnce() {
        AgentLpqMemberState member = new AgentLpqMemberState(402, AgentLpqMemberState.MemberType.AGENT);

        assertTrue(member.claimIntentAnnouncement("stage7:top:0"));
        assertTrue(member.claimLogOnce("stage7:ratz-no-ranged-plan"));
        assertFalse(member.claimIntentAnnouncement("stage7:top:0"), "the 1.2 s repeat of the stock line is gone");
    }
}
