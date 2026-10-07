package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C2.7 (revision P7): stock stage 7 only rallies after the combat and leaves the Rombards' passes to a vacuum
 * or an Agent leader's sweep. In a companion run the nearest companion walks to each natural pass and picks it up (a
 * pass dropped for the leader is never taken), and S7 tells who takes the ratz up on the ledges.
 */
class AgentLpqStageSevenLootTest {
    private static final int S7 = AgentLpqDefinition.stage(7).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();
    private List<Character> crew;

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void theNearestCompanionPicksUpEachNaturalPass() {
        fx.npcAt(AgentLpqDefinition.stage(7).npcId(), new Point(1, -211));
        crew = fx.crew(S7, new Point(-400, -211));
        fx.moveTo(crew.get(2), new Point(500, -211));
        fx.moveTo(crew.get(3), new Point(900, -211));
        MapItem nearKes = fx.drop(S7, AgentLpqDefinition.PASS, 1, new Point(520, -211), false, 0, 1_000L);
        MapItem nearPel = fx.drop(S7, AgentLpqDefinition.PASS, 1, new Point(910, -211), false, 0, 1_000L);
        MapItem farFromAll = fx.drop(S7, AgentLpqDefinition.PASS, 1, new Point(-420, -500), false, 0, 1_000L);
        MapItem forHim = fx.drop(S7, AgentLpqDefinition.PASS, 2, new Point(-410, -211), true, 2, 1_000L);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_7, crew.toArray(Character[]::new));
        when(fx.inventory.addItem(any(), anyInt(), anyShort())).thenReturn(true);

        fx.tick(session, 3_000L);

        verify(crew.get(2)).pickupItem(nearKes);
        verify(crew.get(3)).pickupItem(nearPel);
        verify(fx.actions).navigate(fx.entry(crew.get(1)), farFromAll.getPosition(), true);
        for (Character companion : crew.subList(1, 6)) verify(companion, never()).pickupItem(forHim);
        verify(fx.inventory, never()).addItem(any(), anyInt(), anyShort());
        assertEquals(1, crew.get(2).getItemQuantity(AgentLpqDefinition.PASS, false));
    }

    @Test
    void stageSevenNamesItsShootersAndKeepsHimAtTheBottom() {
        crew = fx.crew(S7, new Point(-400, -211));
        when(crew.get(2).getJob()).thenReturn(Job.HUNTER);
        when(crew.get(3).getJob()).thenReturn(Job.ASSASSIN);
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_7, crew.toArray(Character[]::new));
        fx.monster(S7, AgentLpqDefinition.ROMBARD, new Point(0, -211));
        when(fx.actions.liveMonsterCount(any(), any())).thenReturn(1);

        fx.tick(session, 3_000L);

        fx.said(session, "s7-start", crew.get(1), "Kes and Pel, take the ratz up on the ledges.\nthe rest of us wait"
                + " for rombard down here. Crewfriend, stay at the bottom with us.");
    }
}
