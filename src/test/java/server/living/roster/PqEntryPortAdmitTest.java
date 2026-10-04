package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.hpq.AgentHpqAdmissionService;
import server.agents.capabilities.partyquest.hpq.AgentHpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * The live doors' admission (PqEntryPort.STOCK) asks the Agent layer of the quest it is given, and only that quest's:
 * KPQ's admission for "kpq", HPQ's for "hpq" (any case, spaces around it ignored), each with the leader as operator
 * and event leader, and hands back its success, its words and its session as they came. A key without an entrance
 * throws before any admission is asked.
 */
class PqEntryPortAdmitTest {
    private static final long SEED = 42L;
    private static final long NOW = 5_000L;

    private final Character leader = mock(Character.class);
    private final List<Character> members = List.of(leader, mock(Character.class), mock(Character.class));

    @Test
    void kpqAsksOnlyKpqsAdmissionWithTheLeaderAsOperatorAndEventLeader() {
        AgentKpqSession session = new AgentKpqSession(AgentKpqSession.Mode.PRODUCTION, SEED, 10, 3, NOW);
        try (MockedStatic<AgentKpqAdmissionService> kpq = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            kpq.when(() -> AgentKpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentKpqAdmissionService.AdmissionResult(true, "KPQ party admitted", session, null));

            PqEntryPort.Admitted admitted = PqEntryPort.STOCK.admit("kpq", leader, members, SEED, NOW);

            assertTrue(admitted.success());
            assertEquals("KPQ party admitted", admitted.message());
            assertSame(session, admitted.session());
            kpq.verify(() -> AgentKpqAdmissionService.admit(leader, leader, members, SEED, NOW));
            kpq.verifyNoMoreInteractions();
            hpq.verifyNoInteractions();
        }
    }

    @Test
    void hpqAsksOnlyHpqsAdmissionWithTheLeaderAsOperatorAndEventLeader() {
        AgentHpqSession session = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, SEED, 20, 3, NOW);
        try (MockedStatic<AgentKpqAdmissionService> kpq = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            hpq.when(() -> AgentHpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentHpqAdmissionService.AdmissionResult(true, "HPQ party admitted", session, null));

            PqEntryPort.Admitted admitted = PqEntryPort.STOCK.admit("hpq", leader, members, SEED, NOW);

            assertTrue(admitted.success());
            assertEquals("HPQ party admitted", admitted.message());
            assertSame(session, admitted.session());
            hpq.verify(() -> AgentHpqAdmissionService.admit(leader, leader, members, SEED, NOW));
            hpq.verifyNoMoreInteractions();
            kpq.verifyNoInteractions();
        }
    }

    @Test
    void theKeyIsReadInAnyCaseWithSpacesAroundIt() {
        AgentHpqSession session = new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, SEED, 20, 3, NOW);
        try (MockedStatic<AgentKpqAdmissionService> kpq = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            hpq.when(() -> AgentHpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentHpqAdmissionService.AdmissionResult(true, "HPQ party admitted", session, null));

            PqEntryPort.Admitted admitted = PqEntryPort.STOCK.admit(" HPQ ", leader, members, SEED, NOW);

            assertSame(session, admitted.session());
            hpq.verify(() -> AgentHpqAdmissionService.admit(leader, leader, members, SEED, NOW));
            kpq.verifyNoInteractions();
        }
    }

    @Test
    void aRefusalComesBackInItsQuestsAdmissionWords() {
        try (MockedStatic<AgentKpqAdmissionService> kpq = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            kpq.when(() -> AgentKpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentKpqAdmissionService.AdmissionResult(false, "Mira is not at the Kerning KPQ entrance",
                            null, null));
            hpq.when(() -> AgentHpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentHpqAdmissionService.AdmissionResult(false, "Mira must be level 10-255 for HPQ",
                            null, null));

            PqEntryPort.Admitted kpqRefused = PqEntryPort.STOCK.admit("kpq", leader, members, SEED, NOW);
            PqEntryPort.Admitted hpqRefused = PqEntryPort.STOCK.admit("hpq", leader, members, SEED, NOW);

            assertFalse(kpqRefused.success());
            assertEquals("Mira is not at the Kerning KPQ entrance", kpqRefused.message());
            assertNull(kpqRefused.session());
            assertFalse(hpqRefused.success());
            assertEquals("Mira must be level 10-255 for HPQ", hpqRefused.message());
            assertNull(hpqRefused.session());
        }
    }

    @Test
    void aKeyWithNoEntranceThrowsBeforeAnyAdmissionIsAsked() {
        try (MockedStatic<AgentKpqAdmissionService> kpq = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> PqEntryPort.STOCK.admit("zzpq", leader, members, SEED, NOW));

            assertEquals("no companion party-quest entrance for zzpq", refused.getMessage());
            kpq.verifyNoInteractions();
            hpq.verifyNoInteractions();
        }
    }
}
