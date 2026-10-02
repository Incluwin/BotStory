package server.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules decisions and content lint share (docs/SERVER_KNOWLEDGE_PLAN.md 8.2), over the real file and the seed rows:
 * a wild monster is placed or revived from a placed one, never only script-spawned; an obtainable item is sold, a global
 * drop, or dropped by a wild monster outside a quest. Step 5 adds the undead share healers train by and the rides a trip
 * needs (a walkable trip off the Victoria roads gets a walking ETA).
 */
class WorldKnowledgeDecisionsTest {
    private final WorldKnowledge knowledge = SeedOverlay.knowledge();

    @Test
    void wildMonstersArePlacedOrRevivedFromPlacedOnes() {
        assertTrue(knowledge.wild(100100), "Snail is placed");
        assertTrue(knowledge.wild(3400004), "Yeti Doll revives from the placed 3400003 and has no placement of its own");
        assertFalse(knowledge.wild(9500317), "Kid Snowman: only Happy's script spawns it");
        assertFalse(knowledge.wild(9500318));
        assertFalse(knowledge.wild(9500319));
    }

    @Test
    void obtainableItemsAreSoldGlobalOrDroppedInTheOpen() {
        assertTrue(knowledge.obtainable(4000019), "Snail Shell drops from wild snails");
        assertTrue(knowledge.obtainable(4310000), "Perfect Pitch is a global drop");
        assertTrue(knowledge.obtainable(2000000), "Red Potion is sold");
        assertFalse(knowledge.obtainable(4003003), "Fairy Wing drops only from the script-spawned Happyville snowmen");
        // event and PQ gear is not the open world's (plan 8.4): placed, but only on event maps or as an instanced copy
        assertFalse(knowledge.obtainable(1102166), "Maple Cape: the anniversary Giant Cake only");
        assertFalse(knowledge.obtainable(1082256), "Andras Gloves: Astaroth's event map only");
        assertFalse(knowledge.obtainable(1072369), "Squishy Shoes: KPQ's King Slime and event copies only");
        assertTrue(knowledge.obtainable(1041123), "Purple Lucida: Pianus, in a field boss's room (a forced return too)");
    }

    @Test
    void partyQuestsKnowTheirLobbyAndEveryMapOfTheirInstance() {
        NameIndex names = knowledge.names();
        assertEquals("opq", names.pqAtLobby(200080101).orElseThrow().say(), "OPQ's instanced lobby");
        assertTrue(names.pqAtLobby(200000000).isEmpty());
        assertEquals("kpq", names.pqOf(103000804).orElseThrow().say(), "KPQ's last stage shares the first stage's exit");
        assertEquals("lpq", names.pqOf(922010900).orElseThrow().say());
        assertTrue(names.pqOf(103000000).isEmpty(), "the lobby is no stage");
        assertTrue(names.pqOf(100000005).isEmpty(), "Mushmom's room is no PQ");
    }

    @Test
    void undeadShareCountsSpawnPoints() {
        assertEquals(1.0, knowledge.undeadShare(105040302), 1e-9, "a Sleepywood dungeon of undead");
        assertEquals(1.0, knowledge.undeadShare(101030105), 1e-9);
        assertEquals(0.0, knowledge.undeadShare(100010000), 1e-9, "snails and mushrooms");
        assertEquals(0.0, knowledge.undeadShare(7777777), 1e-9, "unknown map");
    }

    @Test
    void ridesCountTheTravelLegsARouteNeeds() {
        assertEquals(0, knowledge.rides(200000000, 211000000), "Orbis to El Nath is on foot");
        assertTrue(knowledge.rides(200000000, 220000000) >= 1, "Ludibrium is a ship ride from Orbis");
        assertTrue(knowledge.rides(100000000, 200000000) >= 1, "Victoria to Orbis");
        assertEquals(0, knowledge.rides(100000000, 100000000));
        assertEquals(-1, knowledge.rides(100000000, 7777777));
    }
}
