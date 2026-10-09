package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PPQ Step 1 (plan 4.3): every stock mode still needs six members; a companion run takes four to six; an admission is
 * blocked by the operator's own session or a member another session holds; a member can be unindexed alone.
 */
class AgentPpqSessionRegistryTest {
    private static AgentPpqSession session(int operator, int size, boolean companion) {
        AgentPpqSession session = new AgentPpqSession(AgentPpqSession.Mode.HUMAN_LEADER, 1L, operator, false, 1_000L);
        session.addMember(operator, AgentPpqMemberState.MemberType.HUMAN);
        for (int i = 1; i < size; i++) {
            session.addMember(operator * 10 + i, AgentPpqMemberState.MemberType.AGENT);
        }
        session.setCompanionAdopted(companion);
        return session;
    }

    @Test
    void aStockSessionStillNeedsSixMembers() {
        assertThrows(IllegalArgumentException.class, () -> AgentPpqSessionRegistry.registerComplete(session(70, 5, false)));
        AgentPpqSession six = session(71, 6, false);
        AgentPpqSessionRegistry.registerComplete(six);
        AgentPpqSessionRegistry.remove(six);
    }

    @Test
    void aCompanionRunTakesFourToSix() {
        assertThrows(IllegalArgumentException.class, () -> AgentPpqSessionRegistry.registerComplete(session(72, 3, true)));
        for (int size = 4; size <= 6; size++) {
            AgentPpqSession run = session(73 + size, size, true);
            AgentPpqSessionRegistry.registerComplete(run);
            assertSame(run, AgentPpqSessionRegistry.forOperator(73 + size));
            AgentPpqSessionRegistry.remove(run);
        }
    }

    @Test
    void anAdmissionIsBlockedByTheOperatorsSessionOrAHeldMember() {
        AgentPpqSession run = session(80, 4, true);
        AgentPpqSessionRegistry.registerComplete(run);
        try {
            assertEquals("operator-session", AgentPpqSessionRegistry.registrationBlocker(80, List.of(1)));
            assertEquals("member-801", AgentPpqSessionRegistry.registrationBlocker(81, List.of(2, 801)));
            assertEquals("", AgentPpqSessionRegistry.registrationBlocker(81, List.of(2, 3)));

            AgentPpqSessionRegistry.unindexMember(run, 801);
            assertNull(AgentPpqSessionRegistry.forMember(801));
            assertSame(run, AgentPpqSessionRegistry.forMember(802));
        } finally {
            AgentPpqSessionRegistry.remove(run);
        }
    }
}
