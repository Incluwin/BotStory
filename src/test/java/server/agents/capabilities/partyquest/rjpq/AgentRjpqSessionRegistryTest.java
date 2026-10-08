package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** One RJPQ index for both sides: four members, one run per operator and per member, lookups both ways. */
class AgentRjpqSessionRegistryTest {
    private final List<AgentRjpqSession> made = new ArrayList<>();

    @AfterEach
    void forget() {
        made.forEach(AgentRjpqSessionRegistry::remove);
    }

    private AgentRjpqSession session(AgentRjpqSide side, int operator, int... agents) {
        AgentRjpqSession session = new AgentRjpqSession(side, 1L, operator, 1_000L);
        session.addMember(operator, AgentRjpqMemberState.MemberType.HUMAN);
        for (int agent : agents) {
            session.addMember(agent, AgentRjpqMemberState.MemberType.AGENT);
        }
        made.add(session);
        return session;
    }

    @Test
    void onlyAFourMemberRunIsPublished() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentRjpqSessionRegistry.registerComplete(session(AgentRjpqSide.ZENUMIST, 90_001, 90_011, 90_012)));
        assertNull(AgentRjpqSessionRegistry.forOperator(90_001));
    }

    @Test
    void theOperatorAndTheMembersAreLookedUpApart() {
        AgentRjpqSession zenumist = session(AgentRjpqSide.ZENUMIST, 90_101, 90_111, 90_112, 90_113);
        AgentRjpqSession alcadno = session(AgentRjpqSide.ALCADNO, 90_201, 90_211, 90_212, 90_213);
        AgentRjpqSessionRegistry.registerComplete(zenumist);
        AgentRjpqSessionRegistry.registerComplete(alcadno);

        assertSame(zenumist, AgentRjpqSessionRegistry.forOperator(90_101));
        assertSame(zenumist, AgentRjpqSessionRegistry.forMember(90_112));
        assertSame(alcadno, AgentRjpqSessionRegistry.forMember(90_213));
        assertNull(AgentRjpqSessionRegistry.forOperator(90_112), "a member is not an operator");
    }

    @Test
    void aHeldOperatorOrMemberBlocksTheNextRun() {
        AgentRjpqSessionRegistry.registerComplete(session(AgentRjpqSide.ZENUMIST, 90_301, 90_311, 90_312, 90_313));

        assertEquals("operator-session", AgentRjpqSessionRegistry.registrationBlocker(90_301, List.of(1, 2)));
        assertEquals("member-90312", AgentRjpqSessionRegistry.registrationBlocker(90_399, List.of(7, 90_312)));
        assertEquals("", AgentRjpqSessionRegistry.registrationBlocker(90_399, List.of(7, 8)));
        assertThrows(IllegalStateException.class, () -> AgentRjpqSessionRegistry.registerComplete(
                session(AgentRjpqSide.ALCADNO, 90_398, 90_311, 90_396, 90_397)));
    }

    @Test
    void unindexingAMemberKeepsTheRunAndTheOperator() {
        AgentRjpqSession run = session(AgentRjpqSide.ALCADNO, 90_401, 90_411, 90_412, 90_413);
        AgentRjpqSessionRegistry.registerComplete(run);

        AgentRjpqSessionRegistry.unindexMember(run, 90_412);

        assertNull(AgentRjpqSessionRegistry.forMember(90_412));
        assertSame(run, AgentRjpqSessionRegistry.forMember(90_411));
        assertSame(run, AgentRjpqSessionRegistry.forOperator(90_401));
        AgentRjpqSessionRegistry.remove(run);
        assertNull(AgentRjpqSessionRegistry.forOperator(90_401));
        assertNull(AgentRjpqSessionRegistry.forMember(90_411));
    }
}
