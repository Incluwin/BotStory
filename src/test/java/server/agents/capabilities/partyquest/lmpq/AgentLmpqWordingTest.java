package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.plans.AgentScriptItemActionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LMPQ base fix 7 (all modes): the stock lines name the right NPC and room. The clear NPC on Room 16 is Pietri
 * (9103000), not "Pierre"; the hand-over line names the run's rendezvous room (9 or 16), not always Room 9.
 */
class AgentLmpqWordingTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void theClearLineNamesPietri() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.CLEARING,
                ben, mira, kes, pel);
        for (Character member : List.of(ben, mira, kes, pel)) {
            when(member.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(AgentLmpqDefinition.CLEAR_ROOM));
        }

        AgentLmpqCoordinator.tickSession(session, 10_000L);

        verify(run.party).sendPartyChat(any(),
                eq("Ben, everyone is together. Please talk to Pietri to finish the maze."));
    }

    @Test
    void theHandOverLineNamesTheRendezvousRoom() {
        for (int room : new int[]{AgentLmpqDefinition.CLEAR_ROOM, AgentLmpqDefinition.RENDEZVOUS_ROOM}) {
            AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER,
                    AgentLmpqSession.Phase.PREPARING, ben, mira, kes, pel);
            session.setRendezvousRoom(room);
            session.transition(AgentLmpqSession.Phase.REGROUPING, 3_000L);
            when(ben.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(room));
            when(mira.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(room));
            when(mira.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(12);
            try (MockedStatic<AgentScriptItemActionService> items = mockStatic(AgentScriptItemActionService.class)) {
                items.when(() -> AgentScriptItemActionService.dropItem(any(), eq(InventoryType.ETC), anyInt(),
                        anyShort())).thenReturn(true);

                AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 10_000L);
            }

            verify(run.party).sendPartyChat(mira, "Coupons are beside you in Room " + room
                    + ". Please pick them up and confirm you have at least 30.");
            AgentLmpqSessionRegistry.remove(session);
        }
    }

    @Test
    void noLmpqSourceSaysPierre() throws Exception {
        try (var files = Files.list(LmpqSource.PACKAGE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertFalse(Files.readString(file).contains("Pierre"), file.toString());
            }
        }
    }
}
