package server.agents.runtime;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.follow.AgentFollowGuard;
import server.agents.capabilities.movement.AgentFormationService;
import server.agents.capabilities.movement.AgentFormationStateRuntime;
import server.agents.capabilities.movement.AgentTargetSnapshot;
import server.agents.capabilities.movement.AgentTargetSnapshotService;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The follow seam's waiting spot (P6b HPQ step 6): while its anchor stands where the host keeps a spot clear (Tory's
 * ledge, which a party's companions crowded until they hid her), a following Agent's follow target is the spot the host
 * names, not the point beside the anchor; the host is asked only for a following Agent; with no rule installed, or a
 * rule that names no spot, it follows beside its anchor as before.
 */
class AgentFollowWaitSpotTest {
    private static final Point LEDGE = new Point(1434, 364);
    private static final Point PARK_FLOOR = new Point(1554, 574);

    private final List<String> asked = new ArrayList<>();

    @AfterEach
    void uninstall() {
        AgentFollowGuard.install(null);
    }

    @Test
    void aFollowerWaitsWhereTheHostSaysWhileItsAnchorStandsAtTheClearedSpot() {
        install(PARK_FLOOR);
        Character owner = character(100, "Ben", LEDGE);
        AgentRuntimeEntry entry = following(owner, character(200, "Casse", new Point(1300, 574)));

        AgentTargetSnapshot snapshot = capture(entry, owner);

        assertEquals(PARK_FLOOR, snapshot.followTargetPos());
        assertEquals(PARK_FLOOR, snapshot.primaryTargetPos());
        assertEquals("follow-target", snapshot.primaryTargetSource());
        assertEquals(new Point(1494, 364), snapshot.followBasePos(), "the formation is untouched");
        assertEquals(List.of("200 waits near Ben?"), asked);
    }

    @Test
    void noSpotFollowsBesideTheAnchorAsBefore() {
        install(null);
        Character owner = character(100, "Ben", LEDGE);
        AgentRuntimeEntry entry = following(owner, character(200, "Casse", new Point(1300, 574)));

        AgentTargetSnapshot snapshot = capture(entry, owner);

        assertEquals(new Point(1494, 364), snapshot.followTargetPos(), "the resolver's point beside the anchor");
        assertEquals(new Point(1494, 364), snapshot.primaryTargetPos());
        assertEquals(List.of("200 waits near Ben?"), asked);

        AgentFollowGuard.install(null);

        assertEquals(new Point(1494, 364), capture(entry, owner).followTargetPos(), "no rule installed");
        assertNull(AgentFollowGuard.waitsAt(200, owner));
    }

    @Test
    void anAgentThatIsNotFollowingIsNeverAsked() {
        install(PARK_FLOOR);
        Character owner = character(100, "Ben", LEDGE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(character(200, "Casse", new Point(1300, 574)), owner, null);
        AgentFormationStateRuntime.setFollowOffsetX(entry, 60);

        AgentTargetSnapshot snapshot = capture(entry, owner);

        assertEquals(List.of(), asked);
        assertEquals(new Point(1494, 364), snapshot.followTargetPos());
        assertEquals("owner-raw", snapshot.primaryTargetSource());
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void install(Point spot) {
        AgentFollowGuard.install(new AgentFollowGuard.Rule() {
            @Override
            public boolean holdsOffFollow(int agentCharacterId) {
                return false;
            }

            @Override
            public AgentFollowGuard.Crossing crossing(int agentCharacterId, Character anchor) {
                return null;
            }

            @Override
            public Point waitsAt(int agentCharacterId, Character anchor) {
                asked.add(agentCharacterId + " waits near " + anchor.getName() + "?");
                return spot == null ? null : new Point(spot);
            }
        });
    }

    private static AgentRuntimeEntry following(Character owner, Character bot) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, owner, null);
        AgentModeStateRuntime.setFollowing(entry, true);
        AgentFormationStateRuntime.setFollowOffsetX(entry, 60);
        return entry;
    }

    private static AgentTargetSnapshot capture(AgentRuntimeEntry entry, Character owner) {
        return AgentTargetSnapshotService.capture(entry, owner, AgentFormationService.defaultStagger(60, 120),
                (followBase, followAnchor, followAnchorPos, snapRange, map) -> new Point(followBase));
    }

    private static Character character(int id, String name, Point position) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.isLoggedinWorld()).thenReturn(true);
        when(character.getPosition()).thenReturn(new Point(position));
        when(character.getMapId()).thenReturn(100_000_200);
        when(character.getMap()).thenReturn(mock(MapleMap.class));
        return character;
    }
}
