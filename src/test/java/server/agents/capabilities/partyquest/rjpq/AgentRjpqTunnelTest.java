package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;

/**
 * Stage two, the dark tunnel: nothing respawns, so the companions clear every Homun and Cyti and leave through pt00 as
 * soon as the last one falls and the stage counts it.
 */
class AgentRjpqTunnelTest {
    private final RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.TUNNEL_MAP);

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    void companionsFightOnlyTheTunnelsMonstersWhileAnyIsLeft() {
        when(fixture.actions.liveMonsterCount(any(), any())).thenReturn(2);
        fixture.properties.put("statusStg2", "1");

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);

        verify(fixture.actions).grind(fixture.entry(MIRA), AgentRjpqDefinition.TUNNEL_MOB_IDS);
        verify(fixture.actions).grind(fixture.entry(TOBI), AgentRjpqDefinition.TUNNEL_MOB_IDS);
        verify(fixture.partyQuest, never()).enterPortal(any(), anyInt());
        assertEquals(List.of("nothing in here comes back. let's clear it all."), fixture.said);
    }

    @Test
    void whenTheLastOneFallsTheyWalkOntoPtZeroZeroAndTakeIt() {
        when(fixture.actions.liveMonsterCount(any(), any())).thenReturn(0);
        fixture.properties.put("statusStg2", "1");
        fixture.exitPortal(2, 2_000, 208);
        fixture.at(MIRA, 1_200, 208);

        fixture.tick(MIRA, 2_000L);
        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(2_000, 208), true);
        verify(fixture.partyQuest, never()).enterPortal(any(), anyInt());

        fixture.at(MIRA, 1_990, 208);
        fixture.tick(MIRA, 2_100L);
        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 2);
    }

    @Test
    void noMonsterLeftButTheStageNotCountedYetTheyWait() {
        when(fixture.actions.liveMonsterCount(any(), any())).thenReturn(0);
        fixture.exitPortal(2, 2_000, 208);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.actions).stop(fixture.entry(MIRA));
        verify(fixture.actions, never()).navigate(any(), any(), anyBoolean());
    }
}
