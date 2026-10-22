package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.runtime.AgentRuntimeConfig;
import server.maps.MapItem;
import server.maps.MapObject;

import java.awt.Point;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h Step 2d: a companion run never applies the map-wide coupon vacuum. Where it would have (a coupon on the
 * companion's map) it notes the rescue once with applied=false, and the companion walks to the nearest coupon it may
 * loot and picks it up within the loot radius, as a player does. A stock run vacuums and notes applied=true as before.
 */
class AgentLmpqRescueGateTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(40, "Ben");
    private final Character mira = run.companion(41, "Mira");
    private final Character kes = run.companion(42, "Kes");
    private final MockedStatic<AgentLootEligibility> loot = mockStatic(AgentLootEligibility.class);
    private final LmpqLogLines lines = new LmpqLogLines(AgentLmpqRescue.class);
    private final MapItem coupon = mock(MapItem.class);

    AgentLmpqRescueGateTest() {
        loot.when(() -> AgentLootEligibility.canBotTargetLoot(any(), any(), any(), any(), anyLong()))
                .thenReturn(true);
        when(coupon.getObjectId()).thenReturn(800);
        when(coupon.getItemId()).thenReturn(AgentLmpqDefinition.COUPON);
        when(coupon.getPosition()).thenReturn(new Point(300, 145));
        when(run.map.getItems()).thenReturn(List.<MapObject>of(coupon));
        when(mira.getPosition()).thenReturn(new Point(-200, 145));
    }

    @AfterEach
    void close() {
        lines.close();
        loot.close();
        run.close();
    }

    private AgentLmpqSession working(boolean companionRun) {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, companionRun,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(1));
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(2));
        session.rooms().reserve(3, 41, 2_500L);
        session.member(41).assignTargetRoom(3);
        return session;
    }

    @Test
    void aCompanionWalksToTheCouponInsteadOfVacuumingIt() {
        AgentLmpqSession session = working(true);
        when(run.actions.groundPoint(run.map, new Point(300, 145))).thenReturn(new Point(300, 145));

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);
        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_500L);

        verify(run.partyQuests, never()).lootNearby(any(), anySet());
        verify(run.actions, org.mockito.Mockito.times(2)).navigate(run.entry(mira), new Point(300, 145), true);
        List<String> rescues = lines.startingWith("LMPQ rescue:");
        assertEquals(1, rescues.size(), rescues.toString());
        assertTrue(rescues.getFirst().contains("kind=VACUUM site=farm-loot") && rescues.getFirst()
                .contains("applied=false companionRun=true"), rescues.getFirst());
    }

    @Test
    void withinTheLootRadiusItPicksThatCouponUp() {
        AgentLmpqSession session = working(true);
        when(mira.getPosition()).thenReturn(new Point(300 - AgentRuntimeConfig.cfg.LOOT_RADIUS, 145));
        when(run.actions.lootItem(mira, 800, AgentRuntimeConfig.cfg.LOOT_RADIUS)).thenReturn(true);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        verify(run.actions).lootItem(mira, 800, AgentRuntimeConfig.cfg.LOOT_RADIUS);
        verify(run.partyQuests, never()).lootNearby(any(), anySet());
    }

    @Test
    void aCouponItMayNotLootIsLeftWhereItLies() {
        AgentLmpqSession session = working(true);
        loot.when(() -> AgentLootEligibility.canBotTargetLoot(any(), eq(mira), any(), eq(coupon), anyLong()))
                .thenReturn(false);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        verify(run.actions, never()).navigate(run.entry(mira), new Point(300, 145), true);
        verify(run.actions, never()).lootItem(any(), eq(800), org.mockito.ArgumentMatchers.anyInt());
        verify(run.partyQuests, never()).lootNearby(any(), anySet());
    }

    @Test
    void aStockRunVacuumsAsBefore() {
        AgentLmpqSession session = working(false);
        when(run.partyQuests.lootNearby(mira, Set.of(AgentLmpqDefinition.COUPON))).thenReturn(true);

        AgentLmpqCoordinator.tickMember(session, run.entry(mira), mira, 3_000L);

        verify(run.partyQuests).lootNearby(mira, Set.of(AgentLmpqDefinition.COUPON));
        List<String> rescues = lines.startingWith("LMPQ rescue:");
        assertEquals(1, rescues.size(), rescues.toString());
        assertTrue(rescues.getFirst().contains("applied=true companionRun=false"), rescues.getFirst());
    }
}
