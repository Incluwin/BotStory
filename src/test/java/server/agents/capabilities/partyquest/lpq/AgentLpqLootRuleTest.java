package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;

import java.awt.Point;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P6g LPQ C2.5 (loot rule): in a companion run a pass a companion dropped for the leader is his, a companion takes it
 * back only on its re-collect; Alishar's key is its carrier's to loot, never another companion's, and its own dropped
 * key only on a re-collect. A stock run answers exactly as the item-id rule (AgentLootEligibility's).
 */
class AgentLpqLootRuleTest {
    private static final int S3 = AgentLpqDefinition.stage(3).mapId();
    private static final int S9 = AgentLpqDefinition.stage(9).mapId();
    private final LpqTickFixture fx = new LpqTickFixture();

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void aHandOverPassIsTheLeadersUnlessItsRecollectIsDue() {
        List<Character> crew = fx.crew(S3, new Point(0, 0));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_3, crew.toArray(Character[]::new));
        Character kes = crew.get(2);
        MapItem handOver = fx.drop(S3, AgentLpqDefinition.PASS, 5, new Point(0, 0), true, crew.get(1).getId(), 1L);
        MapItem natural = fx.drop(S3, AgentLpqDefinition.PASS, 1, new Point(10, 0), false, 0, 1L);

        assertFalse(AgentLpqSessionRegistry.canLootExclusive(kes, handOver));
        assertTrue(AgentLpqSessionRegistry.canLootExclusive(kes, natural));
        session.beginRecollect(kes.getId(), handOver.getObjectId(), 0);
        assertTrue(AgentLpqSessionRegistry.canLootExclusive(kes, handOver), "the recollector");
        assertFalse(AgentLpqSessionRegistry.canLootExclusive(crew.get(3), handOver), "anyone else");
    }

    @Test
    void theKeyIsItsCarriersOnly() {
        List<Character> crew = fx.crew(S9, new Point(0, 184));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_9, crew.toArray(Character[]::new));
        Character kes = crew.get(2);
        MapItem key = fx.drop(S9, AgentLpqDefinition.BOSS_KEY, 1, new Point(980, 184), false, 0, 1L);

        assertFalse(AgentLpqSessionRegistry.canLootExclusive(kes, key), "no carrier yet");
        session.setKeyCarrierId(kes.getId());
        assertTrue(AgentLpqSessionRegistry.canLootExclusive(kes, key), "the carrier");
        assertFalse(AgentLpqSessionRegistry.canLootExclusive(crew.get(3), key), "another companion");

        MapItem dropped = fx.drop(S9, AgentLpqDefinition.BOSS_KEY, 1, new Point(0, 184), true, kes.getId(), 2L);
        assertFalse(AgentLpqSessionRegistry.canLootExclusive(kes, dropped), "its own hand-over drop");
        session.beginRecollect(kes.getId(), dropped.getObjectId(), 0);
        assertTrue(AgentLpqSessionRegistry.canLootExclusive(kes, dropped), "on its re-collect");
    }

    @Test
    void aStockRunAnswersAsTheItemIdRule() {
        List<Character> crew = fx.crew(S3, new Point(0, 0));
        AgentLpqSession session = fx.session(false, AgentLpqSession.Phase.STAGE_3, crew.toArray(Character[]::new));
        List<MapItem> drops = List.of(
                fx.drop(S3, AgentLpqDefinition.PASS, 5, new Point(0, 0), true, 2, 1L),
                fx.drop(S3, AgentLpqDefinition.PASS, 1, new Point(0, 0), false, 0, 1L),
                fx.drop(S3, AgentLpqDefinition.BOSS_KEY, 1, new Point(0, 0), false, 0, 1L),
                fx.drop(S3, AgentLpqDefinition.RED_POTION, 1, new Point(0, 0), false, 0, 1L));
        for (Character member : crew) {
            for (MapItem drop : drops) {
                assertEquals(AgentLpqSessionRegistry.canLootExclusive(member, drop.getItemId()),
                        AgentLpqSessionRegistry.canLootExclusive(member, drop), member.getName() + " " + drop.getItemId());
            }
        }
        session.beginCouponRegroup(3, 3_000L);
        for (MapItem drop : drops) {
            assertEquals(AgentLpqSessionRegistry.canLootExclusive(crew.get(2), drop.getItemId()),
                    AgentLpqSessionRegistry.canLootExclusive(crew.get(2), drop));
        }
    }

    @Test
    void aCompanionWalksPastAHandOverStackToTheNaturalPass() {
        List<Character> crew = fx.crew(S3, new Point(0, 0));
        AgentLpqSession session = fx.session(true, AgentLpqSession.Phase.STAGE_3, crew.toArray(Character[]::new));
        session.narrateOnce("s3-start");
        Character kes = crew.get(2);
        fx.moveTo(kes, new Point(1_000, 0));
        fx.drop(S3, AgentLpqDefinition.PASS, 5, new Point(1_020, 0), true, crew.get(1).getId(), 1L);
        MapItem natural = fx.drop(S3, AgentLpqDefinition.PASS, 1, new Point(1_500, 0), false, 0, 1L);

        assertTrue(AgentLpqCoordinatorAccess.collectNearestPass(fx, kes));

        verify(fx.actions).navigate(fx.entry(kes), natural.getPosition(), true);
        verify(kes, never()).pickupItem(org.mockito.ArgumentMatchers.any());
    }

    /** Reaches the coordinator's pass pickup as every stage objective uses it. */
    static final class AgentLpqCoordinatorAccess {
        static boolean collectNearestPass(LpqTickFixture fx, Character agent) {
            return AgentLpqCoordinator.collectNearestDrop(fx.entry(agent), agent,
                    java.util.Set.of(AgentLpqDefinition.PASS), new LinkedHashSet<>());
        }
    }
}
