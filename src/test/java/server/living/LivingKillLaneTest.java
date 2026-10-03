package server.living;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.Monster;
import server.living.bond.ContractService;
import server.living.quest.QuestLedger;
import server.living.resident.ResidentRuntime;
import server.maps.MapleMap;

import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Kill credit leaves the thread that landed the kill, and only when a player or a Resident took part. */
class LivingKillLaneTest {
    private static final int PLAYER = 865;
    private static final int CITIZEN = 4001;
    private static final int COMPANION = 4002;

    private LivingWorld world;
    private ExecutorService lane;
    private QuestLedger quests;
    private ContractService contracts;
    private Monster monster;
    private MapleMap map;

    @BeforeEach
    void setUp() {
        world = mock(LivingWorld.class);
        lane = mock(ExecutorService.class);
        quests = mock(QuestLedger.class);
        contracts = mock(ContractService.class);
        ResidentRuntime residents = mock(ResidentRuntime.class);
        when(world.killLane()).thenReturn(lane);
        when(world.quests()).thenReturn(quests);
        when(world.contracts()).thenReturn(contracts);
        when(world.residents()).thenReturn(residents);
        when(residents.isPersonaCharacter(COMPANION)).thenReturn(true);
        monster = mock(Monster.class);
        when(monster.getId()).thenReturn(2230101);
        map = mock(MapleMap.class);
    }

    private void kill(Set<Integer> attackers) {
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class);
             MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class)) {
            living.when(LivingWorld::get).thenReturn(world);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv ->
                    (int) inv.getArgument(0) == PLAYER ? null : mock(AgentRuntimeEntry.class));
            LivingWorldHooks.mobKilled(monster, attackers, map);
        }
    }

    @Test
    void aPlayersKillIsCreditedOnTheLaneNotInline() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(lane).execute(any(Runnable.class));

        kill(Set.of(PLAYER));

        verify(lane).execute(any(Runnable.class));
        verify(quests).onMobKilled(eq(2230101), eq(Set.of(PLAYER)), eq(map));
        verify(contracts).onMobKilled(eq(2230101), eq(Set.of(PLAYER)), eq(map));
        verifyNoInteractions(map); // no map lookup on the killing thread
    }

    @Test
    void botsAloneQueueNothing() {
        kill(Set.of(CITIZEN));

        verify(lane, never()).execute(any(Runnable.class));
        verifyNoInteractions(quests, contracts);
    }

    @Test
    void aResidentCompanionsKillIsQueued() {
        kill(Set.of(CITIZEN, COMPANION));

        verify(lane).execute(any(Runnable.class));
    }
}
