package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.follow.AgentFollowGuard;
import server.agents.capabilities.follow.AgentFollowMapSyncService;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.PERION;

/**
 * Portal-true follow (docs/BOT_MANAGEMENT_PLAN.md P2b, "Follow guards"): when an owner changes map, each companion
 * following them comes in where the owner came in (their position as they entered, recorded by the mapEntered hook),
 * not where the owner stands by then, one after another 300-900 ms apart, and then walks over. With the arrival not
 * known, and for plain Agents, a follower lands beside the owner as before; the instance guard still comes first.
 */
class PortalFollowTest {
    private static final Point CAME_IN_AT = new Point(250, 180);

    private final LifecycleHarness game = new LifecycleHarness();
    private final Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.ACTIVE, LITH);
    private final Persona mira = game.companion("mira", "Mira", 901, RosterState.ACTIVE, LITH);
    private final CompanionFollowGuard guard = new CompanionFollowGuard(game.world);
    private final List<String> sync = new ArrayList<>();

    @BeforeEach
    void outWithAnn() {
        game.outWithAnn(olwen);
        game.outWithAnn(mira);
        game.roster();
        AgentFollowGuard.install(guard);
    }

    @AfterEach
    void close() {
        AgentFollowGuard.install(null);
        game.close();
    }

    @Test
    void aFollowerComesInWhereItsOwnerCameInNotWhereTheyStandNow() {
        annComesInto(KERNING, CAME_IN_AT);
        game.moveAnn(KERNING, 900, 180); // and walks on

        AgentFollowGuard.Crossing crossing = guard.crossing(900, game.ann);

        assertEquals(CAME_IN_AT, crossing.at());
        assertTrue(syncFollow(olwen));
        assertEquals(List.of("hold"), sync, "it comes through on its turn, not at once");
    }

    @Test
    void followersComeThroughOneAfterAnother300To900MsApart() {
        long before = System.currentTimeMillis();
        annComesInto(KERNING, CAME_IN_AT);

        long first = guard.crossing(900, game.ann).notBeforeMs();
        long second = guard.crossing(901, game.ann).notBeforeMs();

        long after = System.currentTimeMillis();
        assertTrue(first >= before + 300L && first <= after + 900L, "the first a moment after its owner: " + (first - before));
        assertTrue(second - first >= 300L && second - first <= 900L, "then the next: " + (second - first));
        assertEquals(first, guard.crossing(900, game.ann).notBeforeMs(), "its turn is kept");
    }

    @Test
    void oneStillWaitingWhenItsOwnerMovesOnComesStraightToTheNewMap() {
        annComesInto(KERNING, CAME_IN_AT);
        guard.crossing(900, game.ann);

        annComesInto(PERION, new Point(-400, 60));

        assertEquals(new Point(-400, 60), guard.crossing(900, game.ann).at());
    }

    @Test
    void withTheArrivalNotKnownItLandsBesideItsOwnerAsBefore() {
        game.moveAnn(KERNING, 1300, 90); // no mapEntered seen for this map (a login, a test)

        assertNull(guard.crossing(900, game.ann));
        assertTrue(syncFollow(olwen));
        assertEquals(List.of("warp " + KERNING + " at 1300,90"), sync);
    }

    @Test
    void plainAgentsAndOthersFollowersKeepTheirPlacement() {
        annComesInto(KERNING, CAME_IN_AT);
        Character stranger = mock(Character.class);
        when(stranger.getId()).thenReturn(ANN + 1);

        assertNull(guard.crossing(950, game.ann), "a plain Agent following Ann");
        assertNull(guard.crossing(900, stranger), "Ann's companion following someone else");
    }

    @Test
    void theInstanceGuardStillComesFirst() {
        MapleMap stage = game.annEntersInstance(103000800);
        game.roster().ownerEnteredMap(game.ann, stage, CAME_IN_AT);

        assertTrue(syncFollow(olwen));

        assertEquals(List.of("hold"), sync, "not registered in the run: it waits outside");
    }

    @Test
    void turnsAreStaggeredFromTheLaterOfNowAndTheLastTurn() {
        Deque<Long> gaps = new ArrayDeque<>(List.of(300L, 900L, 450L));
        PortalFollow follow = new PortalFollow(gaps::pop);
        follow.entered(ANN, KERNING, CAME_IN_AT);

        assertEquals(1_300L, follow.crossing(ANN, KERNING, 900, 1_000L).notBeforeMs());
        assertEquals(2_200L, follow.crossing(ANN, KERNING, 901, 1_010L).notBeforeMs());
        assertEquals(1_300L, follow.crossing(ANN, KERNING, 900, 1_020L).notBeforeMs(), "asked again: the same turn");
        assertEquals(5_450L, follow.crossing(ANN, KERNING, 902, 5_000L).notBeforeMs(), "a late one goes a moment after now");
        assertNull(follow.crossing(ANN, PERION, 900, 5_000L), "another map: not known");

        follow.entered(ANN, PERION, null); // an arrival with no point is not known either

        assertNull(follow.crossing(ANN, KERNING, 900, 5_000L));
        assertNull(follow.crossing(ANN, PERION, 900, 5_000L));
    }

    private void annComesInto(int mapId, Point at) {
        game.moveAnn(mapId, at.x, at.y);
        game.roster().ownerEnteredMap(game.ann, game.map(mapId), at); // as the mapEntered hook does
    }

    private boolean syncFollow(Persona persona) {
        AgentRuntimeEntry runtime = new AgentRuntimeEntry(game.bot(persona), game.ann, null);
        AgentModeStateRuntime.setFollowing(runtime, true);
        return AgentFollowMapSyncService.syncFollowMap(runtime, runtime.bot(), game.ann,
                new AgentFollowMapSyncService.FollowMapSyncHooks(
                        (map, point) -> null,
                        (entry, agent) -> { },
                        (agent, map, point) -> sync.add("warp " + map.getId() + " at " + point.x + "," + point.y),
                        entry -> { },
                        (entry, agent) -> sync.add("hold")));
    }
}
