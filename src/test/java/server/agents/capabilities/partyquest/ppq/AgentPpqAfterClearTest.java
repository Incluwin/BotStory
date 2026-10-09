package server.agents.capabilities.partyquest.ppq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.UNA;

/**
 * The PPQ reviews' after-clear findings (every mode): after the clear a run is bounded. clearPQ's warp to 925100600
 * (past PiratePQ.js's maxMapId) unregisters everyone, and Wu Yang's room has no exit: a companion with a full Etc tab
 * leaves it at once without its Feather, nobody holds the run open past AFTER_CLEAR_LIMIT_MS (5 minutes), and the
 * release takes any Agent still there to Guon's map. A companion run waits for its companions only, never a human,
 * and no run completes while its members are still on the boss map between setEventCleared and clearPQ's warp.
 */
class AgentPpqAfterClearTest {
    private static final long MINUTE = 60_000L;

    /** Everyone but {@code stay} took Wu Yang's exit: off the PPQ maps and out of the event. */
    private static void allOutBut(PpqRunFixture run, int stay) {
        for (int id : List.of(BEN, MIRA, TOBI, UNA)) {
            if (id == stay) continue;
            run.onMap(id, AgentPpqDefinition.RECRUIT_MAP);
            when(run.character(id).getEventInstance()).thenReturn(null);
        }
    }

    @Test
    void aCompanionWithAFullEtcTabLeavesWuYangsRoomWithoutItsFeather() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP);
             PpqLogLines lines = new PpqLogLines(AgentPpqCoordinator.class)) {
            PpqAdmitFixture.room(run.character(MIRA), client.inventory.InventoryType.ETC, 0);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), 5_000L);

            verify(run.maps).changeMapNear(eq(run.character(MIRA)), eq(run.recruit), any());
            verify(run.actions, never()).npcPosition(any(), eq(AgentPpqDefinition.RESCUE_NPC));
            assertEquals(List.of("PPQ feather skipped: session=" + run.session.sessionId() + " member=" + MIRA
                    + " reason=no-etc-room"), lines.startingWith("PPQ feather skipped:"));
        }
    }

    /** A full Etc tab holding Feather stacks of these sizes and owner tags. */
    private static void fullTabWithFeathers(PpqRunFixture run, int id, short quantity, String owner) {
        PpqAdmitFixture.room(run.character(id), client.inventory.InventoryType.ETC, 0);
        client.inventory.Item stack = org.mockito.Mockito.mock(client.inventory.Item.class);
        when(stack.getQuantity()).thenReturn(quantity);
        when(stack.getOwner()).thenReturn(owner);
        when(run.character(id).getInventory(client.inventory.InventoryType.ETC)
                .listById(AgentPpqDefinition.WU_YANG_FEATHER)).thenReturn(List.of(stack));
    }

    @Test
    void aFullEtcTabWithAFeatherStackStillHasRoomForOneMore() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP)) {
            fullTabWithFeathers(run, MIRA, (short) 3, "");

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), 5_000L);

            verify(run.actions).npcPosition(run.character(MIRA), AgentPpqDefinition.RESCUE_NPC);
            verify(run.maps, never()).changeMapNear(any(), any(), any());
        }
    }

    @Test
    void aFullFeatherStackWithNoFreeSlotIsNoRoom() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP)) {
            fullTabWithFeathers(run, MIRA, (short) 200, "");

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), 5_000L);

            verify(run.actions, never()).npcPosition(any(), eq(AgentPpqDefinition.RESCUE_NPC));
            verify(run.maps).changeMapNear(eq(run.character(MIRA)), eq(run.recruit), any());
        }
    }

    @Test
    void aGmTaggedFeatherStackIsNoRoom() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP)) {
            fullTabWithFeathers(run, MIRA, (short) 199, "Crewfriend"); // '!giveitem' tags the stack with the GM's name

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), 5_000L);

            verify(run.actions, never()).npcPosition(any(), eq(AgentPpqDefinition.RESCUE_NPC));
            verify(run.maps).changeMapNear(eq(run.character(MIRA)), eq(run.recruit), any());
        }
    }

    @Test
    void pinACompanionWithRoomAsksWuYangForItsFeather() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP)) {
            PpqAdmitFixture.room(run.character(MIRA), client.inventory.InventoryType.ETC, 1);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), run.character(MIRA), 5_000L);

            verify(run.actions).npcPosition(run.character(MIRA), AgentPpqDefinition.RESCUE_NPC);
            verify(run.maps, never()).changeMapNear(any(), any(), any());
        }
    }

    @Test
    void aCompanionStillByWuYangEndsTheRunAtTheLimitAndIsTakenOut() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class);
             PpqLogLines lines = new PpqLogLines(AgentPpqCoordinator.class)) {
            when(run.event.isEventCleared()).thenReturn(true);
            run.tick(2_000L);
            allOutBut(run, MIRA); // Wu Yang: 'Please make room in ETC.', over and over

            run.tick(2_000L + 5 * MINUTE - 1);
            assertFalse(run.session.terminal());
            run.tick(2_000L + 5 * MINUTE);

            assertEquals(AgentPpqSession.Phase.COMPLETED, run.session.phase());
            assertEquals(List.of("PPQ after clear: session=" + run.session.sessionId()
                    + " stillInside=true afterClearMs=300000 companionRun=true"), lines.startingWith("PPQ after clear:"));
            AgentPpqTerminationService.release(run.session, "", 2_000L + 5 * MINUTE, false);
            verify(run.maps).changeMapNear(eq(run.character(MIRA)), eq(run.recruit), any());
            verify(run.maps, never()).changeMapNear(eq(run.character(BEN)), any(), any());
        }
    }

    @Test
    void aCompanionRunNeverWaitsForAHumanByWuYang() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP)) {
            when(run.event.isEventCleared()).thenReturn(true);
            run.tick(2_000L);
            allOutBut(run, BEN);

            run.tick(3_000L);

            assertEquals(AgentPpqSession.Phase.COMPLETED, run.session.phase());
        }
    }

    @Test
    void anotherModeWaitsForAHumanByWuYangUpToTheLimit() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.CLEAR_MAP)) {
            when(run.event.isEventCleared()).thenReturn(true);
            run.tick(2_000L);
            allOutBut(run, BEN);

            run.tick(2_000L + MINUTE);
            assertFalse(run.session.terminal());
            run.tick(2_000L + 5 * MINUTE);

            assertEquals(AgentPpqSession.Phase.COMPLETED, run.session.phase());
        }
    }

    @Test
    void noRunCompletesWhileItsMembersAreStillOnTheBossMap() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.BOSS_MAP)) {
            // setEventCleared has run; clearPQ's warp to 925100600 has not.
            when(run.event.isEventCleared()).thenReturn(true);

            run.tick(2_000L);

            assertEquals(AgentPpqSession.Phase.CLEARING, run.session.phase());
        }
    }

    @Test
    void aStockReleaseAfterTheClearTakesItsAgentsOutOfWuYangsRoomButNotTheHuman() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.CLEAR_MAP);
             MockedStatic<AgentPartyQuestLifecycleRuntime> lifecycle = mockStatic(AgentPartyQuestLifecycleRuntime.class)) {
            when(run.event.isEventCleared()).thenReturn(true);
            run.session.complete(9_000L);
            run.onMap(UNA, AgentPpqDefinition.RECRUIT_MAP); // took Wu Yang's exit already

            AgentPpqTerminationService.release(run.session, "", 9_000L, false);

            for (int id : List.of(MIRA, TOBI)) {
                verify(run.maps).changeMapNear(eq(run.character(id)), eq(run.recruit), any());
            }
            verify(run.maps, never()).changeMapNear(eq(run.character(UNA)), any(), any());
            verify(run.maps, never()).changeMapNear(eq(run.character(BEN)), any(), any());
            verify(run.event, never()).dispose();
        }
    }
}
