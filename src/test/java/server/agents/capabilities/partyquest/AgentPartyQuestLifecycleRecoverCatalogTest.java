package server.agents.capabilities.partyquest;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A member taken out of a running party quest after its engagement index is gone is rehomed on the recovery map of
 * the quest its caller names, for every quest in the catalog (a quest added to it is covered without a new test).
 * The harness is AgentPartyQuestLifecycleRecoverDetachedTest's.
 */
class AgentPartyQuestLifecycleRecoverCatalogTest {
    private static final int OWNER = 742_100;
    private static final int COMPANION = 742_101;
    /** A map that is no quest's recovery map, so every quest's recovery moves the member. */
    private static final int ELSEWHERE = 999_999_999;

    private final AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
    private final Character companion = mock(Character.class);
    private final Character owner = mock(Character.class);
    private final MapGateway mapGateway = mock(MapGateway.class);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private MockedStatic<AgentTownLifeRuntime> townLife;

    @BeforeEach
    void setUp() {
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(COMPANION)).thenReturn(entry);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(COMPANION)).thenReturn(OWNER);
        open(AgentRuntimeIdentityRuntime.class).when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(companion);
        townLife = open(AgentTownLifeRuntime.class);
        townLife.when(() -> AgentTownLifeRuntime.requestLocal(any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new AgentTownLifeSessionResult(AgentTownLifeSessionResult.Status.STARTED, 0, ""));
        open(AgentMovementCommandRuntime.class);
        open(AgentMapGatewayRuntime.class).when(AgentMapGatewayRuntime::map).thenReturn(mapGateway);
        open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients)
                .thenReturn(mock(AgentClientGateway.class));
        CharacterGateway characters = mock(CharacterGateway.class);
        when(characters.findOnlineCharacterById(OWNER)).thenReturn(owner);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        when(companion.getId()).thenReturn(COMPANION);
        when(companion.getHp()).thenReturn(120);
        when(companion.getMapId()).thenReturn(ELSEWHERE);
    }

    @AfterEach
    void tearDown() {
        AgentPartyQuestHandback.install(null);
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    static List<AgentPartyQuestDefinition> definitions() {
        return AgentPartyQuestCatalog.definitions();
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("definitions")
    void anUnindexedMemberIsRehomedOnItsQuestsRecoveryMap(AgentPartyQuestDefinition definition) {
        AgentPartyQuestLifecycleRuntime.recoverDetachedMember(COMPANION, definition.questKey(), 2_000L);

        verify(mapGateway, times(1)).resolveMap(anyInt(), anyInt(), eq(definition.recoveryMapId()));
        verify(mapGateway, times(1)).resolveMap(anyInt(), anyInt(), anyInt());
        townLife.verify(() -> AgentTownLifeRuntime.requestLocal(
                eq(entry), eq(companion), any(), any(), anyLong(), anyInt()), times(1));
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }
}
