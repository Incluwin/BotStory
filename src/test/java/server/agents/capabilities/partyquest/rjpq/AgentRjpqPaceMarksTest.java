package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Reactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.BEN;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;

/**
 * Step 4: the run's own pass records its pace marks: each phase it reaches, both Dark Lab doors open, both files in the
 * leader's bag, the files handed in, and every time a member goes down.
 */
class AgentRjpqPaceMarksTest {
    private final RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.SPECIAL_LAB_MAP);

    @AfterEach
    void close() {
        fixture.close();
    }

    private static Reactor door(int state) {
        Reactor reactor = mock(Reactor.class);
        when(reactor.getState()).thenReturn((byte) state);
        return reactor;
    }

    @Test
    void theRunsPassMarksThePhaseTheDoorsTheFilesAndTheFallen() {
        MapleMap lab = mock(MapleMap.class);
        when(fixture.event.getMapInstance(AgentRjpqDefinition.SPECIAL_LAB_MAP)).thenReturn(lab);
        Reactor labOne = door(0);
        Reactor labTwo = door(1);
        when(lab.getReactorByName("rnj3_out1")).thenReturn(labOne);
        when(lab.getReactorByName("rnj3_out2")).thenReturn(labTwo);

        AgentRjpqCoordinator.tickSession(fixture.session, 2_000L);
        AgentRjpqPaceMarks.Snapshot first = fixture.session.pace().snapshot();
        assertEquals(2_000L, first.phase(AgentRjpqSession.Phase.KEYS));
        assertEquals(0L, first.at(AgentRjpqPaceMarks.Boundary.DOORS_OPEN));

        when(labOne.getState()).thenReturn((byte) 1);
        when(fixture.actions.itemCount(fixture.character(BEN), AgentRjpqDefinition.ALCADNO_FILES_ITEM_ID))
                .thenReturn(1);
        when(fixture.actions.itemCount(fixture.character(BEN), AgentRjpqDefinition.ZENUMIST_FILES_ITEM_ID))
                .thenReturn(1);
        when(fixture.character(TOBI).getHp()).thenReturn(0);
        AgentRjpqCoordinator.tickSession(fixture.session, 3_000L);
        fixture.properties.put("statusStg4", "1");
        when(fixture.character(BEN).getHp()).thenReturn(0);
        AgentRjpqCoordinator.tickSession(fixture.session, 4_000L);
        AgentRjpqCoordinator.tickSession(fixture.session, 5_000L);

        AgentRjpqPaceMarks.Snapshot marks = fixture.session.pace().snapshot();
        assertEquals(3_000L, marks.at(AgentRjpqPaceMarks.Boundary.DOORS_OPEN));
        assertEquals(3_000L, marks.at(AgentRjpqPaceMarks.Boundary.FILES_HELD));
        assertEquals(4_000L, marks.at(AgentRjpqPaceMarks.Boundary.FILES_IN));
        assertEquals(1, marks.deaths().get(TOBI));
        assertEquals(1, marks.deaths().get(BEN));
        assertTrue(fixture.session.leaderWentDown());
    }
}
