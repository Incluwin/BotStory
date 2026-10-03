package server.agents.capabilities.partyquest;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.capabilities.townlife.AgentTownLifeSessionResult;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.MapGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A member taken out of a running party quest after its engagement index is gone is rehomed by the quest its caller
 * names: an HPQ member goes back to Henesys Park (100000200), and the older two-argument form keeps Kerning's
 * (103000000) recovery.
 */
class AgentPartyQuestLifecycleRecoverDetachedTest {
    private static final int OWNER = 742_100;
    private static final int COMPANION = 742_101;
    private static final int HPQ_EXIT_MAP = 910_010_300;

    private final AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
    private final Character companion = mock(Character.class);
    private final Character owner = mock(Character.class);
    private final MapGateway mapGateway = mock(MapGateway.class);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private MockedStatic<AgentTownLifeRuntime> townLife;
    private MockedStatic<AgentMovementCommandRuntime> movement;

    @BeforeEach
    void setUp() {
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(COMPANION)).thenReturn(entry);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(COMPANION)).thenReturn(OWNER);
        open(AgentRuntimeIdentityRuntime.class).when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(companion);
        townLife = open(AgentTownLifeRuntime.class);
        townLife.when(() -> AgentTownLifeRuntime.requestLocal(any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new AgentTownLifeSessionResult(AgentTownLifeSessionResult.Status.STARTED, 0, ""));
        movement = open(AgentMovementCommandRuntime.class);
        open(AgentMapGatewayRuntime.class).when(AgentMapGatewayRuntime::map).thenReturn(mapGateway);
        open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients)
                .thenReturn(mock(AgentClientGateway.class));
        CharacterGateway characters = mock(CharacterGateway.class);
        when(characters.findOnlineCharacterById(OWNER)).thenReturn(owner);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        when(companion.getId()).thenReturn(COMPANION);
        when(companion.getHp()).thenReturn(120);
        when(companion.getMapId()).thenReturn(HPQ_EXIT_MAP);
    }

    @AfterEach
    void tearDown() {
        AgentPartyQuestHandback.install(null);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    @Test
    void anUnindexedHpqMemberIsRehomedInHenesysPark() {
        AgentPartyQuestLifecycleRuntime.recoverDetachedMember(COMPANION, "hpq", 2_000L);

        verify(mapGateway).resolveMap(anyInt(), anyInt(), eq(100_000_200));
        verify(mapGateway, never()).resolveMap(anyInt(), anyInt(), eq(103_000_000));
        townLife.verify(() -> AgentTownLifeRuntime.requestLocal(
                eq(entry), eq(companion), any(), any(), anyLong(), anyInt()), times(1));
    }

    @Test
    void theTwoArgumentFormKeepsKerningsRecovery() {
        AgentPartyQuestLifecycleRuntime.recoverDetachedMember(COMPANION, 2_000L);

        verify(mapGateway).resolveMap(anyInt(), anyInt(), eq(103_000_000));
        verify(mapGateway, never()).resolveMap(anyInt(), anyInt(), eq(100_000_200));
    }

    @Test
    void theHostIsAskedAboutTheNamedQuest() {
        AgentPartyQuestHandback.install((id, questKey) ->
                "hpq".equals(questKey) ? AgentPartyQuestHandback.Handback.FOLLOW_LEADER : null);

        AgentPartyQuestLifecycleRuntime.recoverDetachedMember(COMPANION, "hpq", 2_000L);

        movement.verify(() -> AgentMovementCommandRuntime.follow(entry, owner), times(1));
        verify(mapGateway, never()).resolveMap(anyInt(), anyInt(), anyInt());
        townLife.verify(() -> AgentTownLifeRuntime.requestLocal(
                any(), any(), any(), any(), anyLong(), anyInt()), never());
    }

    @Test
    void aStillIndexedMemberIsRehomedByItsOwnEngagement() {
        AgentPartyQuestEngagement engagement = new AgentPartyQuestEngagement(
                "kpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, OWNER, 3, 1_000L);
        AgentPartyQuestEngagementRegistry.register(engagement);
        AgentPartyQuestEngagementRegistry.addAndIndexMember(
                engagement, COMPANION, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        try {
            AgentPartyQuestLifecycleRuntime.recoverDetachedMember(COMPANION, "hpq", 2_000L);

            verify(mapGateway).resolveMap(anyInt(), anyInt(), eq(103_000_000));
        } finally {
            AgentPartyQuestEngagementRegistry.remove(engagement);
        }
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }
}
