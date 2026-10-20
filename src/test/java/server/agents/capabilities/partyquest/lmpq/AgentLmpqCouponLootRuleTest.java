package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P6h Step 2d's loot rule: in a companion run, a coupon stack one of the run's companions dropped for the leader is
 * his; no companion loots it but the one that claimed it back before it expires. A stock run, the leader's own drop
 * and a monster's coupon are looted as before, and the 10-meso portal markers keep their rule. AgentLootEligibility
 * asks the rule for every drop (no edit of that shared file).
 */
class AgentLmpqCouponLootRuleTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(40, "Ben");
    private final Character mira = run.companion(41, "Mira");
    private final Character kes = run.companion(42, "Kes");

    @AfterEach
    void close() {
        run.close();
    }

    private static MapItem coupons(int objectId, int ownerId, boolean playerDrop) {
        MapItem drop = mock(MapItem.class);
        when(drop.getObjectId()).thenReturn(objectId);
        when(drop.getItemId()).thenReturn(AgentLmpqDefinition.COUPON);
        when(drop.getOwnerId()).thenReturn(ownerId);
        when(drop.isPlayerDrop()).thenReturn(playerDrop);
        return drop;
    }

    @Test
    void aStackDroppedForTheLeaderIsHisUntilAClaimerTakesItBack() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.REGROUPING, ben, mira, kes);
        MapItem stack = coupons(900, 41, true);

        assertTrue(AgentLmpqSessionRegistry.preservesPortalMarker(kes, stack), "Kes leaves Mira's stack alone");
        assertTrue(AgentLmpqSessionRegistry.preservesPortalMarker(mira, stack), "and so does Mira, who dropped it");

        assertTrue(session.beginClaim(900, 42));
        assertFalse(AgentLmpqSessionRegistry.preservesPortalMarker(kes, stack), "Kes claimed it back");
        assertTrue(AgentLmpqSessionRegistry.preservesPortalMarker(mira, stack));
    }

    @Test
    void theLeadersOwnDropAndAMonstersCouponAreLootedAsBefore() {
        run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true, AgentLmpqSession.Phase.FARMING, ben, mira, kes);

        assertFalse(AgentLmpqSessionRegistry.preservesPortalMarker(kes, coupons(901, 40, true)), "Ben's drop");
        assertFalse(AgentLmpqSessionRegistry.preservesPortalMarker(kes, coupons(902, 0, false)), "a monster's");
    }

    @Test
    void aStockRunLootsAsBefore() {
        run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.REGROUPING, ben, mira, kes);

        assertFalse(AgentLmpqSessionRegistry.preservesPortalMarker(kes, coupons(903, 41, true)));
    }

    @Test
    void thePortalMarkersKeepTheirRule() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes);
        MapItem marker = mock(MapItem.class);
        when(marker.getMeso()).thenReturn(AgentLmpqDefinition.ROOM_MARKER_MESOS);

        assertTrue(AgentLmpqSessionRegistry.preservesPortalMarker(kes, marker));
        session.transition(AgentLmpqSession.Phase.REGROUPING, 3_000L);
        assertFalse(AgentLmpqSessionRegistry.preservesPortalMarker(kes, marker));
    }

    @Test
    void lootEligibilityAsksTheRuleForEveryDropBeforeTheItemRules() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/server/agents/capabilities/looting/AgentLootEligibility.java"));
        int asks = source.indexOf("AgentLmpqSessionRegistry.preservesPortalMarker(bot, drop)");
        assertTrue(asks > 0, "AgentLootEligibility.rulesAllow asks it");
        assertTrue(asks < source.indexOf("AgentLmpqSessionRegistry.canLootCoupon(bot)"), "before the coupon rule");
    }
}
