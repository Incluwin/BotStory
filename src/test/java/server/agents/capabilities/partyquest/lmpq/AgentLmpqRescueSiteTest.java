package server.agents.capabilities.partyquest.lmpq;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every LMPQ rescue the coordinator can apply logs an 'LMPQ rescue:' line (P6h LMPQ step 1, plan 1.8). A scan of the
 * lmpq main sources (comments stripped, whitespace-tolerant) pins it: the map-wide coupon pickup (lootNearby) lives
 * only in the vacuumCoupons primitive, which notes it; its call sites are the reviewed three, each once; and no other
 * rescue-shaped world call (a pose teleport, a kill with drops, an event re-registration, a drop removal, an inventory
 * grant) exists in the package, nor a map move outside the GM harness.
 */
class AgentLmpqRescueSiteTest {

    @Test
    void theMapWidePickupLivesOnlyInItsPrimitive() throws Exception {
        for (LmpqSource source : LmpqSource.all()) {
            Map<String, Integer> callers = source.callers("\\blootNearby\\s*\\(");
            if (source.name.equals("AgentLmpqCoordinator")) {
                assertEquals(Map.of("vacuumCoupons", 1), callers, source.name);
            } else {
                assertEquals(Map.of(), callers, source.name);
            }
        }
    }

    @Test
    void thePrimitiveNotesItsRescue() throws Exception {
        String body = LmpqSource.of("AgentLmpqCoordinator").body("vacuumCoupons");
        assertTrue(body.matches("(?s).*\\bAgentLmpqRescue\\s*\\.\\s*note\\s*\\(\\s*session\\s*,"
                + "\\s*AgentLmpqRescue\\s*\\.\\s*Kind\\s*\\.\\s*VACUUM\\s*,\\s*site\\b.*"), body);
    }

    @Test
    void theRescueSitesAreTheReviewedThreeEachOnce() throws Exception {
        LmpqSource coordinator = LmpqSource.of("AgentLmpqCoordinator");
        assertEquals(List.of("farm farm-loot", "farm farm-sweep", "regroup leader-regroup"),
                coordinator.labelledCalls("\\bvacuumCoupons\\s*\\(\\s*session\\s*,\\s*\"([a-z0-9-]+)\""));
        assertEquals(Map.of("farm", 2, "regroup", 1), coordinator.callers("\\bvacuumCoupons\\s*\\("));
    }

    @Test
    void noOtherRescueShapedWorldCallExists() throws Exception {
        for (LmpqSource source : LmpqSource.all()) {
            for (String forbidden : List.of("\\bstagePosition\\s*\\(", "\\bkillMonsterWithDrops\\s*\\(",
                    "\\bregisterPlayer\\s*\\(", "\\bremoveItemDrop\\s*\\(",
                    "\\binventory\\s*\\(\\s*\\)\\s*\\.\\s*addItem\\s*\\(")) {
                assertEquals(0, source.count(forbidden), source.name + " " + forbidden);
            }
            int moves = source.count("\\bchangeMapNear\\s*\\(");
            if (source.name.equals("AgentLmpqTestService")) {
                assertTrue(moves > 0, "the GM harness moves its fixtures and its spectator");
            } else {
                assertEquals(0, moves, source.name);
            }
        }
    }
}
