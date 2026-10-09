package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static server.agents.capabilities.partyquest.ppq.PpqAdmitFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqAdmitFixture.room;

/**
 * PPQ Step 1 (plan 4.4): the public companion admit. Ben's own party of four to six beside Guon becomes a HUMAN_LEADER
 * session flagged as a companion run before it is published; three or seven are refused; a companion with a full Etc
 * tab is refused in its own words (Wu Yang's Feather needs the slot), a human with one is not; the stock lobby path
 * still needs six.
 */
class AgentPpqAdoptSeamTest {
    private PpqAdmitFixture fx = new PpqAdmitFixture();

    @AfterEach
    void close() {
        if (fx != null) {
            fx.close();
        }
    }

    /** A fresh party of Ben and three companions, plus {@code more}; the previous one is closed first. */
    private PpqAdmitFixture fresh(int more) {
        if (fx != null) {
            fx.close();
        }
        fx = new PpqAdmitFixture().withMore(more);
        return fx;
    }

    @Test
    void theCompanionFlagIsSetBeforeTheSessionIsPublished() {
        AtomicBoolean flaggedWhenPublished = new AtomicBoolean();
        try (MockedStatic<AgentPpqSessionRegistry> registry = mockStatic(AgentPpqSessionRegistry.class,
                CALLS_REAL_METHODS)) {
            registry.when(() -> AgentPpqSessionRegistry.registerComplete(any())).thenAnswer(call -> {
                AgentPpqSession session = call.getArgument(0);
                flaggedWhenPublished.set(session.companionAdopted());
                return call.callRealMethod();
            });

            AgentPpqAdmissionService.AdmissionResult result =
                    fx.keep(AgentPpqAdmissionService.admit(fx.ben, fx.ben, fx.party(), 7L, 1_000L));

            assertTrue(result.success(), result.message());
        }
        assertTrue(flaggedWhenPublished.get(), "flagged before registerComplete");
    }

    @Test
    void fourFiveAndSixAreAdmittedAsAHumanLedCompanionRun() {
        for (int more = 0; more <= 2; more++) {
            PpqAdmitFixture party = fresh(more);
            AgentPpqAdmissionService.AdmissionResult result =
                    party.keep(AgentPpqAdmissionService.admit(party.ben, party.ben, party.party(), 7L, 1_000L));

            assertTrue(result.success(), (4 + more) + ": " + result.message());
            AgentPpqSession session = result.session();
            assertEquals(4 + more, session.memberCount());
            assertTrue(session.companionAdopted());
            assertEquals(AgentPpqSession.Mode.HUMAN_LEADER, session.mode());
            assertEquals(BEN, session.eventLeaderId());
            assertFalse(session.skipChestRooms());
            assertSame(session, AgentPpqSessionRegistry.forOperator(BEN));
            assertNotNull(result.engagement());
        }
    }

    @Test
    void threeOrSevenAreRefused() {
        fx.without(fx.una);
        AgentPpqAdmissionService.AdmissionResult three =
                fx.keep(AgentPpqAdmissionService.admit(fx.ben, fx.ben, fx.party(), 7L, 1_000L));
        assertFalse(three.success());
        assertEquals("A PPQ companion run takes four to six members", three.message());

        PpqAdmitFixture seven = fresh(4);
        AgentPpqAdmissionService.AdmissionResult result =
                seven.keep(AgentPpqAdmissionService.admit(seven.ben, seven.ben, seven.party(), 7L, 1_000L));
        assertFalse(result.success());
        assertEquals("A PPQ companion run takes four to six members", result.message());
    }

    @Test
    void aCompanionWithAFullEtcTabIsRefusedInItsOwnWordsAHumanIsNot() {
        room(fx.tobi, client.inventory.InventoryType.ETC, 0);
        AgentPpqAdmissionService.AdmissionResult refused =
                fx.keep(AgentPpqAdmissionService.admit(fx.ben, fx.ben, fx.party(), 7L, 1_000L));
        assertFalse(refused.success());
        assertEquals("Tobi needs a free etc slot.", refused.message());
        assertNull(AgentPpqSessionRegistry.forOperator(BEN));

        room(fx.tobi, client.inventory.InventoryType.ETC, 1);
        room(fx.ben, client.inventory.InventoryType.ETC, 0);
        assertTrue(fx.keep(AgentPpqAdmissionService.admit(fx.ben, fx.ben, fx.party(), 7L, 1_000L)).success());
    }

    @Test
    void theChestRoomsCanBeSkippedAsTheTestCommandAsks() {
        AgentPpqAdmissionService.AdmissionResult result =
                fx.keep(AgentPpqAdmissionService.admit(fx.ben, fx.ben, fx.party(), true, 7L, 1_000L));
        assertTrue(result.session().skipChestRooms());
    }

    @Test
    void aMembersProblemKeepsTodaysWordsAndOrder() {
        org.mockito.Mockito.when(fx.mira.getLevel()).thenReturn(50);
        assertEquals("Mira must be level 55-100", AgentPpqAdmissionService.memberProblem(fx.mira));
        org.mockito.Mockito.when(fx.mira.getMapId()).thenReturn(100_000_000);
        assertEquals("Mira is not at PPQ", AgentPpqAdmissionService.memberProblem(fx.mira), "the map first");
        assertEquals("", AgentPpqAdmissionService.memberProblem(fx.tobi));
    }

    @Test
    void aCompanionThatCannotLeaveItsActivityRollsTheAdmissionBack() {
        org.mockito.Mockito.when(fx.admission.prepare(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(fx.tobiEntry), any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);

        AgentPpqAdmissionService.AdmissionResult result =
                fx.keep(AgentPpqAdmissionService.admit(fx.ben, fx.ben, fx.party(), 7L, 1_000L));

        assertFalse(result.success());
        assertEquals("Tobi could not leave its current activity", result.message());
        assertNull(AgentPpqSessionRegistry.forOperator(BEN), "nothing published");
        assertNull(AgentPpqSessionRegistry.forMember(fx.mira.getId()));
    }

    @Test
    void pinTheStockLobbyPathStillNeedsSix() {
        fx.withMore(1);
        AgentPpqAdmissionService.Validation five = AgentPpqAdmissionService.validate(fx.ben, fx.ben, fx.party());
        assertFalse(five.success());
        assertEquals("Agent-assisted PPQ requires six members", five.message());
        assertEquals(List.of(), five.members());
    }
}
