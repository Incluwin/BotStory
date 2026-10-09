package server.agents.capabilities.partyquest.rjpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.MIRA;
import static server.agents.capabilities.partyquest.rjpq.RjpqStageFixture.TOBI;

/**
 * Stage five, Yulete's office: while he is there and the leader may still want him alone (the letter was read),
 * companions stand well back from him (his rule is 310 pixels); then they fight the Mutae and leave through out00, the
 * office's exit to the Lab Units, once it opens.
 */
class AgentRjpqOfficeTest {
    private final RjpqStageFixture fixture =
            new RjpqStageFixture(AgentRjpqSide.ZENUMIST, AgentRjpqDefinition.OFFICE_MAP);

    @AfterEach
    void close() {
        fixture.close();
    }

    private void exitReactor(int state) {
        fixture.reactors(fixture.reactor("rnj6_out", state, 364, 242));
    }

    @Test
    void whileYuleteIsThereAfterTheLetterCompanionsNearHimStandBackAndTheOthersStayPut() {
        exitReactor(0);
        fixture.portal(AgentRjpqDefinition.OFFICE_STAND_BACK_PORTAL, 0, -540, 200);
        fixture.properties.put("npcShocked", "1");
        fixture.properties.put("yuleteTalked", "0");
        fixture.at(MIRA, 0, 214);
        fixture.at(TOBI, -200, 214);

        fixture.tick(MIRA, 2_000L);
        fixture.tick(TOBI, 2_000L);

        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(-540, 200), true);
        verify(fixture.actions).stop(fixture.entry(TOBI));
        verify(fixture.actions, never()).grind(any(), any());
        assertEquals(List.of("if you want yulete, go talk to him now. we'll stay back."), fixture.said);
    }

    @Test
    void afterTheLeaderMetHimAloneTheyStillStandBackUntilHeLeaves() {
        exitReactor(0);
        fixture.portal(AgentRjpqDefinition.OFFICE_STAND_BACK_PORTAL, 0, -540, 200);
        fixture.properties.put("npcShocked", "1");
        fixture.properties.put("yuleteTalked", "1");
        fixture.at(MIRA, 0, 214);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.actions).navigate(fixture.entry(MIRA), new Point(-540, 200), true);
    }

    @Test
    void withoutTheLetterReadNobodyStandsBackAndTheyFightTheMutaeOnceThere() {
        exitReactor(0);
        fixture.at(MIRA, 200, 214);
        when(fixture.actions.liveMonsterCount(any(), any())).thenReturn(0, 30);

        fixture.tick(MIRA, 2_000L);
        verify(fixture.actions).stop(fixture.entry(MIRA));
        fixture.tick(MIRA, 2_100L);

        verify(fixture.actions).grind(fixture.entry(MIRA), AgentRjpqDefinition.OFFICE_MOB_IDS);
        verify(fixture.actions, never()).navigate(any(), any(), anyBoolean());
        assertTrue(fixture.said.isEmpty());
    }

    @Test
    void onceYuleteHasLeftTheyFightTheMutae() {
        exitReactor(0);
        fixture.properties.put("npcShocked", "1");
        fixture.properties.put("yuleteTalked", "-1");
        when(fixture.actions.liveMonsterCount(any(), any())).thenReturn(30);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.actions).grind(fixture.entry(MIRA), AgentRjpqDefinition.OFFICE_MOB_IDS);
    }

    @Test
    void whenTheExitOpensTheyLeaveThroughOutZeroZeroNotTheScriptSpot() {
        exitReactor(1);
        fixture.portal("pt00", 7, 68, 214);
        fixture.portal(AgentRjpqDefinition.OFFICE_EXIT_PORTAL, 2, 364, 240);
        fixture.at(MIRA, 360, 240);

        fixture.tick(MIRA, 2_000L);

        verify(fixture.partyQuest).enterPortal(fixture.character(MIRA), 2);
        verify(fixture.partyQuest, never()).enterPortal(any(), eq(7));
    }
}
