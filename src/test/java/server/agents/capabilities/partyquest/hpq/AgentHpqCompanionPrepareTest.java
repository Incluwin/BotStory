package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A companion run's leader clicks Tory himself (plan 2.1): while it prepares nobody walks to Tory and nobody announces
 * anything; the stock run still gathers its Agents beside her and says it is starting.
 */
class AgentHpqCompanionPrepareTest {
    private static final Point TORY = new Point(1434, 352);
    private final HpqRunFixture run = new HpqRunFixture();
    private final Character ben = run.human(1, "Ben", new Point(1400, 352));
    private final Character mira = run.companion(2, "Mira", new Point(900, 352));
    private final Character tobi = run.companion(3, "Tobi", new Point(950, 352));

    @BeforeEach
    void atHenesysPark() {
        for (Character member : new Character[] {ben, mira, tobi}) run.outside(member, AgentHpqDefinition.RECRUIT_MAP);
        when(run.actions.npcPosition(any(), eq(AgentHpqDefinition.ENTRY_NPC))).thenReturn(TORY);
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aCompanionRunWaitsForHisClickWithoutWalkingOrAnnouncing() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.PREPARING, ben, mira, tobi);

        AgentHpqCoordinator.tick(session, 2_000L);
        AgentHpqCoordinator.tick(session, 9_000L);

        assertEquals(AgentHpqSession.Phase.PREPARING, session.phase());
        verify(run.actions, never()).navigate(any(), any(), anyBoolean());
        verify(run.packets, never()).broadcastChatText(any(), anyString(), anyBoolean(), anyInt());
        run.saidNothing();
    }

    @Test
    void aCompanionRunGoesOnOnceHeStandsInTheEvent() {
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.PREPARING, ben, mira, tobi);
        run.onMap(ben, AgentHpqDefinition.STAGE_MAP);
        when(run.hpq.event(ben)).thenReturn(run.event);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.ENTERING, session.phase());
    }

    @Test
    void theStockRunStillWalksItsAgentsToTory() {
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.PREPARING, ben, mira, tobi);

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.actions).navigate(eq(run.entry(mira)), any(Point.class), eq(true));
        verify(run.actions).navigate(eq(run.entry(tobi)), any(Point.class), eq(true));
    }

    @Test
    void theStockRunAnnouncesItsStartOnceGatheredBesideTory() {
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.PREPARING, ben, mira, tobi);
        // Within NPC_APPROACH_PX of every lobby spot left of Tory (24 to 78 px left of her).
        run.moveTo(mira, new Point(TORY.x - 50, TORY.y));
        run.moveTo(tobi, new Point(TORY.x - 50, TORY.y));

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.packets).broadcastChatText(mira, "Party ready. Everyone is here. Starting HPQ in 5 seconds.",
                false, 0);
    }
}
