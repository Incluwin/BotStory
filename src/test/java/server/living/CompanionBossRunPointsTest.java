package server.living;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.living.bond.BondService;
import server.living.bond.Friendship;
import server.living.config.Content.Persona;
import server.living.roster.CompanionExecutor;
import server.living.roster.DirectExecutor;
import server.living.store.LivingStore;
import server.maps.MapleMap;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P12 G12 (decision 23): a companion boss run gives friendship BOSS points once per run, at its clear. Its own boss
 * kills give none each; a boss killed in an event with no companion run, or on a field map, keeps today's per-kill
 * points under the daily cap.
 */
class CompanionBossRunPointsTest {
    private static final int ANN = 10;
    private static final int THANE = 901;
    private static final int MOB = 8_800_000;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final Friendship friendship = mock(Friendship.class);
    private final LivingStore store = mock(LivingStore.class);
    private final BondService bonds = mock(BondService.class);
    private final EventInstanceManager event = mock(EventInstanceManager.class);
    private final MapleMap map = mock(MapleMap.class);
    private final Monster boss = mock(Monster.class);
    private final CompanionExecutor ops = DirectExecutor.inline();
    private final ExecutorService lane = mock(ExecutorService.class);
    private MockedStatic<LivingWorld> living;
    private MockedStatic<AgentBossRunRegistry> runs;
    private MockedStatic<AgentRuntimeRegistry> agents;

    @BeforeEach
    void wire() {
        when(world.config()).thenReturn(config);
        when(world.friendship()).thenReturn(friendship);
        when(world.executor()).thenReturn(ops);
        when(world.killLane()).thenReturn(lane);
        when(world.store()).thenReturn(store);
        when(world.bonds()).thenReturn(bonds);
        when(boss.getId()).thenReturn(MOB);
        when(boss.isBoss()).thenReturn(true);
        when(map.getEventInstance()).thenReturn(event);
        living = mockStatic(LivingWorld.class);
        living.when(LivingWorld::get).thenReturn(world);
        runs = mockStatic(AgentBossRunRegistry.class);
        agents = mockStatic(AgentRuntimeRegistry.class);
    }

    @AfterEach
    void close() {
        agents.close();
        runs.close();
        living.close();
    }

    @Test
    void aBossKilledInsideACompanionBossRunGivesNoPointsForTheKill() {
        runs.when(() -> AgentBossRunRegistry.forEvent(event)).thenReturn(mock(AgentBossRunSession.class));

        LivingWorldHooks.mobKilled(boss, Set.of(ANN, THANE), map);

        verify(friendship, never()).onBossKilled(anyInt(), anySet(), any());
    }

    @Test
    void aBossKilledWithNoCompanionRunKeepsTheKillsPoints() {
        LivingWorldHooks.mobKilled(boss, Set.of(ANN, THANE), map);
        verify(friendship).onBossKilled(eq(MOB), eq(Set.of(ANN, THANE)), eq(map));

        when(map.getEventInstance()).thenReturn(null);
        LivingWorldHooks.mobKilled(boss, Set.of(ANN), map);
        verify(friendship).onBossKilled(eq(MOB), eq(Set.of(ANN)), eq(map));
    }

    @Test
    void aClearedRunBefriendsTheOwnerOnceWithEachCompanionUnderTheDailyCap() {
        Friendship real = new Friendship(world);
        Character ann = mock(Character.class);
        when(ann.getId()).thenReturn(ANN);
        Persona thane = persona("thane");
        Persona isolde = persona("isolde");
        when(store.countEvents(eq(ANN), eq("isolde"), anyString(), anyLong())).thenReturn(3);

        real.onBossRunCleared(ann, List.of(thane, isolde), "zakum");

        verify(store).event(ANN, "thane", "friend.boss", "boss run zakum");
        verify(bonds).addPoints(ann, thane, config.FRIENDSHIP_BOSS_POINTS, "boss run zakum");
        verify(bonds, never()).addPoints(eq(ann), eq(isolde), anyInt(), anyString());
    }

    private static Persona persona(String id) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        return persona;
    }
}
