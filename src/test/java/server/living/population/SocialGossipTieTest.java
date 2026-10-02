package server.living.population;

import org.junit.jupiter.api.Test;
import server.living.population.SocialFabric.Ties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Who gossips with whom (docs/SERVER_KNOWLEDGE_PLAN.md 7.5): party, circle on the same map and channel, guild; the closest tie wins. */
class SocialGossipTieTest {
    private static Ties ties(int id, int party, int circle, int guild, int channel, int map) {
        return new Ties(id, party, circle, guild, channel, map, 50);
    }

    @Test
    void eachGroupHasItsTie() {
        assertEquals(1.0, SocialFabric.tie(ties(1, 7, 0, 0, 1, 100), ties(2, 7, 0, 0, 2, 200)), "a party, wherever its members are");
        assertEquals(0.7, SocialFabric.tie(ties(1, 0, 3, 0, 1, 100), ties(2, 0, 3, 0, 1, 100)), "a circle on the same map and channel");
        assertEquals(0.0, SocialFabric.tie(ties(1, 0, 3, 0, 1, 100), ties(2, 0, 3, 0, 1, 101)), "a circle on another map");
        assertEquals(0.0, SocialFabric.tie(ties(1, 0, 3, 0, 1, 100), ties(2, 0, 3, 0, 2, 100)), "a circle on another channel");
        assertEquals(0.4, SocialFabric.tie(ties(1, 0, 0, 9, 1, 100), ties(2, 0, 0, 9, 3, 300)), "a guild, across maps");
        assertEquals(0.0, SocialFabric.tie(ties(1, 0, 0, 0, 1, 100), ties(2, 0, 0, 0, 1, 100)), "strangers on one map");
        assertEquals(0.0, SocialFabric.tie(ties(1, 4, 5, 6, 1, 100), ties(2, 8, 9, 10, 1, 100)), "different groups");
    }

    @Test
    void aPairInTwoGroupsTakesTheClosest() {
        assertEquals(1.0, SocialFabric.tie(ties(1, 7, 0, 9, 1, 100), ties(2, 7, 0, 9, 1, 100)), "party and guild");
        assertEquals(0.7, SocialFabric.tie(ties(1, 0, 3, 9, 1, 100), ties(2, 0, 3, 9, 1, 100)), "circle and guild");
    }
}
