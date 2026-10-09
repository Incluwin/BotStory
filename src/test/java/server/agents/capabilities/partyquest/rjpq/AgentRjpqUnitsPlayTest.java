package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.UNA;

/**
 * Stage six, the Lab Units: one room each and the leader's left free; in its room a companion climbs row by row,
 * learning each row by trying its portals (never from the event's combination), leaving out the columns its friends
 * found right on that row, and gives the leader his row in party chat once the other three rooms know it.
 */
class AgentRjpqUnitsPlayTest {
    private RjpqStageFixture fixture;

    private void hallway() {
        fixture = new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.LAB_TUNNEL_MAP);
        fixture.portal("pt00", 3, -468, 124);
        fixture.portal("pt01", 4, -178, 122);
        fixture.portal("pt02", 5, 234, 120);
        fixture.portal("pt03", 6, 548, 118);
    }

    private void room(int room) {
        fixture = new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.LAB_TUNNEL_MAP + room);
        when(fixture.partyQuest.enterPortal(any(), anyInt())).thenReturn(true);
        for (int row = 0; row < AgentRjpqDefinition.LAB_UNIT_ROWS; row++) {
            for (int column = 0; column < AgentRjpqDefinition.LAB_UNIT_COLUMNS; column++) {
                fixture.portal(AgentRjpqDefinition.labUnitPortalName(row, column),
                        AgentRjpqDefinition.labUnitPortalId(row, column), -298 + 165 * column,
                        AgentRjpqDefinition.LAB_UNIT_ROW_Y.get(row));
            }
        }
        fixture.portal(AgentRjpqDefinition.LAB_UNIT_EXIT_PORTAL, 9, 178, -3_042);
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void companionsTakeOneRoomEachTheNearestFreeOneAndLeaveOneForTheLeader() {
        hallway();
        fixture.at(MIRA, -460, 124);
        fixture.at(TOBI, -450, 124);
        fixture.at(UNA, -440, 124);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);
        fixture.tick(UNA, 2_000L);

        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 3);
        verify(fixture.actions).navigate(fixture.entry(TOBI), new Point(-178, 122), true);
        verify(fixture.actions).navigate(fixture.entry(UNA), new Point(234, 120), true);
        assertEquals(List.of("one room each. we'll leave one for you, Ben."), fixture.said);
    }

    @Test
    void theLastFreeRoomWaitsForTheLeaderUntilHeHasOne() {
        hallway();
        fixture.properties.put("statusStg5", "7");
        fixture.at(MIRA, 540, 118);

        fixture.tick(MIRA, 2_000L);
        verify(fixture.partyQuest, never()).enterPortal(any(), anyInt());

        when(fixture.character(BEN).getMapId()).thenReturn(926_100_301);
        fixture.tick(MIRA, 2_100L);
        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 6);
    }

    @Test
    void aRightPortalIsLearnedAndTheNextRowIsTriedFromWhereItLanded() {
        room(2);
        fixture.at(MIRA, -300, 125);
        fixture.tick(MIRA, 2_000L);
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(-298, -52), true);

        fixture.at(MIRA, -298, -52);
        fixture.tick(MIRA, 2_100L);
        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 15);

        fixture.at(MIRA, -298, -352);
        fixture.tick(MIRA, 3_000L);
        assertEquals(0, fixture.session.unitKnown(2, 0));
        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 19);
        verify(fixture.partyQuest, never()).property(any(), startsWith("stage6_comb"));
    }

    @Test
    void aWrongPortalIsNotTriedAgainAndAFriendsRightColumnIsLeftOut() {
        room(2);
        fixture.session.learnUnitRow(1, 0, 0);
        fixture.at(MIRA, -133, -52);
        fixture.tick(MIRA, 2_000L);
        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 16);

        fixture.at(MIRA, -302, 120);
        fixture.tick(MIRA, 3_000L);

        assertEquals(java.util.Set.of(1), fixture.session.unitTried(2, 0));
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(32, -52), true);
    }

    @Test
    void pastTheLastRowItLeavesThroughOutZeroZero() {
        room(3);
        fixture.at(MIRA, 170, -3_042);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 9);
    }

    @Test
    void theLeaderGetsHisRowInPartyChatOnceTheOtherThreeRoomsKnowIt() {
        room(1);
        fixture.session.learnUnitRow(1, 0, 2);
        fixture.session.learnUnitRow(2, 0, 0);
        fixture.session.learnUnitRow(3, 0, 3);
        when(fixture.character(BEN).getMapId()).thenReturn(926_100_304);
        fixture.at(BEN, -300, 125);
        fixture.at(MIRA, 170, -3_042);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(MIRA, 3_000L);

        verify(fixture.party).sendPartyChat(fixture.character(MIRA), "Ben, your first row should be the second from the left one.");
        verify(fixture.party, times(1)).sendPartyChat(any(), any());
    }

    @Test
    void anImpossibleRowForgetsTheOtherRoomsAnswersAndTriesAgain() {
        room(2);
        fixture.session.learnUnitRow(1, 3, 0);
        fixture.session.learnUnitRow(3, 3, 1);
        fixture.session.learnUnitRow(4, 3, 2);
        fixture.session.markUnitTried(2, 3, 3);
        fixture.at(MIRA, 0, -944);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(-298, -944), true);
        assertEquals(-1, fixture.session.unitKnown(1, 3));
    }
}
