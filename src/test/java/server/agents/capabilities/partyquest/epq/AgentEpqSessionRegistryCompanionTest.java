package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry calls a companion host makes: registrationBlocker names what holds a party before an admit, in HPQ's
 * codes, and unindexMember forgets one member while the session and its other members stay registered.
 */
class AgentEpqSessionRegistryCompanionTest {
    private static final int OPERATOR = 752_100;
    private final List<AgentEpqSession> registered = new ArrayList<>();

    @AfterEach
    void tearDown() {
        registered.forEach(AgentEpqSessionRegistry::remove);
    }

    @Test
    void theBlockerNamesTheOperatorFirstThenTheFirstHeldMember() {
        assertEquals("", AgentEpqSessionRegistry.registrationBlocker(OPERATOR, List.of(752_101, 752_102)));
        assertEquals("", AgentEpqSessionRegistry.registrationBlocker(OPERATOR, null));

        register(session(OPERATOR, 752_101, 752_102, 752_103, 752_104));

        assertEquals("operator-session",
                AgentEpqSessionRegistry.registrationBlocker(OPERATOR, List.of(752_190, 752_191)));
        assertEquals("member-752103",
                AgentEpqSessionRegistry.registrationBlocker(752_199, List.of(752_190, 752_103, 752_102)));
        assertEquals("", AgentEpqSessionRegistry.registrationBlocker(752_199, List.of(752_190, 752_191)));
    }

    @Test
    void registerCompleteKeepsItsThreeRefusals() {
        AgentEpqSession first = register(session(OPERATOR, 752_101, 752_102, 752_103, 752_104));

        IllegalArgumentException incomplete = assertThrows(IllegalArgumentException.class,
                () -> AgentEpqSessionRegistry.registerComplete(session(752_199, 752_190, 752_191, 752_192)));
        assertEquals("complete 4-6 member EPQ session required", incomplete.getMessage());
        IllegalStateException operator = assertThrows(IllegalStateException.class,
                () -> AgentEpqSessionRegistry.registerComplete(
                        session(OPERATOR, 752_190, 752_191, 752_192, 752_193)));
        assertEquals("operator already owns EPQ", operator.getMessage());
        IllegalStateException member = assertThrows(IllegalStateException.class,
                () -> AgentEpqSessionRegistry.registerComplete(
                        session(752_199, 752_190, 752_191, 752_192, 752_104)));
        assertEquals("EPQ member already reserved", member.getMessage());
        assertSame(first, AgentEpqSessionRegistry.forMember(752_104));
    }

    @Test
    void unindexMemberForgetsOnlyThatMember() {
        AgentEpqSession session = register(session(OPERATOR, 752_101, 752_102, 752_103, 752_104));

        AgentEpqSessionRegistry.unindexMember(session, 752_103);

        assertNull(AgentEpqSessionRegistry.forMember(752_103));
        assertSame(session, AgentEpqSessionRegistry.forOperator(OPERATOR));
        assertTrue(AgentEpqSessionRegistry.sessions().contains(session));
        for (int id : List.of(752_101, 752_102, 752_104)) assertSame(session, AgentEpqSessionRegistry.forMember(id));
        assertEquals("", AgentEpqSessionRegistry.registrationBlocker(752_199, List.of(752_103)));

        AgentEpqSessionRegistry.unindexMember(null, 752_101); // no session: nothing happens
        assertSame(session, AgentEpqSessionRegistry.forMember(752_101));
    }

    @Test
    void unindexingAnotherSessionsMemberLeavesItIndexed() {
        AgentEpqSession session = register(session(OPERATOR, 752_101, 752_102, 752_103, 752_104));
        AgentEpqSession other = session(752_199, 752_190, 752_191, 752_192, 752_193);

        AgentEpqSessionRegistry.unindexMember(other, 752_102);

        assertSame(session, AgentEpqSessionRegistry.forMember(752_102));
    }

    @Test
    void removeAfterUnindexIsClean() {
        AgentEpqSession session = register(session(OPERATOR, 752_101, 752_102, 752_103, 752_104));
        AgentEpqSessionRegistry.unindexMember(session, 752_102);

        AgentEpqSessionRegistry.remove(session);

        assertNull(AgentEpqSessionRegistry.forOperator(OPERATOR));
        assertTrue(AgentEpqSessionRegistry.sessions().stream().noneMatch(found -> found == session));
        for (int id : List.of(752_101, 752_102, 752_103, 752_104)) assertNull(AgentEpqSessionRegistry.forMember(id));
        assertEquals("", AgentEpqSessionRegistry.registrationBlocker(OPERATOR, List.of(752_101, 752_102)));
    }

    private AgentEpqSession register(AgentEpqSession session) {
        AgentEpqSessionRegistry.registerComplete(session);
        registered.add(session);
        return session;
    }

    private static AgentEpqSession session(int operatorId, int... memberIds) {
        AgentEpqSession session = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, 7L, operatorId, 10L);
        for (int id : memberIds) session.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        session.setLeadership(memberIds[0], memberIds[0]);
        return session;
    }
}
