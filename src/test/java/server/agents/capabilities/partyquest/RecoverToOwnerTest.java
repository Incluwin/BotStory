package server.agents.capabilities.partyquest;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.capabilities.townlife.AgentTownLifeSessionResult;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.MapGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** Recovery hands an owned companion back to its runtime leader instead of Kerning TownLife (P6 WP1). */
class RecoverToOwnerTest {
    private static final int OWNER = 740_100;
    private static final int COMPANION = 740_101;
    private static final int EXIT_MAP = 103_000_890;
    private static final long RECOVERY_RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime.RECOVERY_RETRY_MS");

    private final AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
    private final Character companion = mock(Character.class);
    private final Character owner = mock(Character.class);
    private final CharacterGateway characters = mock(CharacterGateway.class);
    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private MockedStatic<AgentTownLifeRuntime> townLife;
    private MockedStatic<AgentMovementCommandRuntime> movement;
    private MockedStatic<AgentMapGatewayRuntime> maps;
    private MockedStatic<AgentCharacterGatewayRuntime> gateway;
    private AgentPartyQuestEngagement engagement;

    @BeforeEach
    void setUp() {
        registry = mockStatic(AgentRuntimeRegistry.class);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        townLife = mockStatic(AgentTownLifeRuntime.class);
        movement = mockStatic(AgentMovementCommandRuntime.class);
        maps = mockStatic(AgentMapGatewayRuntime.class);
        gateway = mockStatic(AgentCharacterGatewayRuntime.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(COMPANION)).thenReturn(entry);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(COMPANION)).thenReturn(OWNER);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(companion);
        townLife.when(() -> AgentTownLifeRuntime.requestLocal(any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new AgentTownLifeSessionResult(
                        AgentTownLifeSessionResult.Status.STARTED, 103_000_000, ""));
        maps.when(AgentMapGatewayRuntime::map).thenReturn(mock(MapGateway.class));
        gateway.when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        when(characters.findOnlineCharacterById(OWNER)).thenReturn(owner);
        when(companion.getId()).thenReturn(COMPANION);
        when(companion.getName()).thenReturn("Tobi");
        when(companion.getHp()).thenReturn(120);
        when(companion.getMapId()).thenReturn(EXIT_MAP);

        engagement = new AgentPartyQuestEngagement(
                "kpq", AgentPartyQuestEngagement.Mode.PRODUCTION, 7L, OWNER, 4, 1_000L);
        engagement.addMember(OWNER, AgentPartyQuestEngagement.MemberType.HUMAN, 1_000L);
        engagement.addMember(COMPANION, AgentPartyQuestEngagement.MemberType.AGENT, 1_000L);
        AgentPartyQuestEngagementRegistry.register(engagement);
        engagement.beginRecovery("KPQ stopped", 2_000L);
    }

    @AfterEach
    void tearDown() {
        AgentPartyQuestHandback.install(null);
        AgentPartyQuestEngagementRegistry.remove(engagement);
        gateway.close();
        maps.close();
        movement.close();
        townLife.close();
        identity.close();
        registry.close();
    }

    @Test
    void followLeaderCompanionFollowsItsRuntimeLeaderAndIsNeverSentToKerning() {
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
        AtomicReference<Object> engagementAtFollow = new AtomicReference<>("follow never started");
        movement.when(() -> AgentMovementCommandRuntime.follow(entry, owner)).thenAnswer(call -> {
            engagementAtFollow.set(AgentPartyQuestEngagementRegistry.forMember(COMPANION));
            return null;
        });

        assertTrue(AgentPartyQuestLifecycleRuntime.recover(engagement, 2_000L));

        movement.verify(() -> AgentMovementCommandRuntime.follow(entry, owner), times(1));
        // Follow starts only once the engagement is gone, so the party-quest tick no longer owns the companion.
        assertNull(engagementAtFollow.get());
        maps.verify(AgentMapGatewayRuntime::map, never());
        verifyNoTownLifeRequest();
    }

    @Test
    void followLeaderWaitsWhileInTheEventOrDown() {
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
        when(companion.getHp()).thenReturn(0);

        assertFalse(AgentPartyQuestLifecycleRuntime.recover(engagement, 2_000L));

        assertEquals(AgentPartyQuestEngagement.State.RECOVERING, engagement.state());
        assertSame(engagement, AgentPartyQuestEngagementRegistry.forMember(COMPANION));
        assertTrue(engagement.diagnostics().contains("Handback deferred for Tobi: still in the event or down"));

        // Respawned but still inside the event: the retry defers again.
        when(companion.getHp()).thenReturn(120);
        when(companion.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertTrue(AgentPartyQuestLifecycleRuntime.tick(COMPANION, 3_000L));
        assertEquals(AgentPartyQuestEngagement.State.RECOVERING, engagement.state());

        // Out of the event: the next retry, not before, hands it back.
        when(companion.getEventInstance()).thenReturn(null);
        AgentPartyQuestLifecycleRuntime.tick(COMPANION, 3_000L + RECOVERY_RETRY_MS - 1L);
        assertEquals(AgentPartyQuestEngagement.State.RECOVERING, engagement.state());
        AgentPartyQuestLifecycleRuntime.tick(COMPANION, 3_000L + RECOVERY_RETRY_MS);

        assertEquals(AgentPartyQuestEngagement.State.CLOSED, engagement.state());
        assertNull(AgentPartyQuestEngagementRegistry.forMember(COMPANION));
        movement.verify(() -> AgentMovementCommandRuntime.follow(entry, owner), times(1));
        verifyNoTownLifeRequest();
    }

    @Test
    void releasedAgentIsOnlyStopped() {
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.RELEASE);

        assertTrue(AgentPartyQuestLifecycleRuntime.recover(engagement, 2_000L));

        movement.verify(() -> AgentMovementCommandRuntime.stop(entry), times(1));
        movement.verify(() -> AgentMovementCommandRuntime.follow(any(), any()), never());
        maps.verify(AgentMapGatewayRuntime::map, never());
        verifyNoTownLifeRequest();
    }

    @Test
    void offlineLeaderStops() {
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
        when(characters.findOnlineCharacterById(OWNER)).thenReturn(null);

        assertTrue(AgentPartyQuestLifecycleRuntime.recover(engagement, 2_000L));

        movement.verify(() -> AgentMovementCommandRuntime.stop(entry), times(1));
        movement.verify(() -> AgentMovementCommandRuntime.follow(any(), any()), never());
        verifyNoTownLifeRequest();
    }

    @Test
    void noRuleKeepsTownLifeRecovery() {
        when(companion.getMapId()).thenReturn(103_000_000);

        assertTrue(AgentPartyQuestLifecycleRuntime.recover(engagement, 2_000L));

        townLife.verify(() -> AgentTownLifeRuntime.requestLocal(
                any(), any(), any(), any(), anyLong(), anyInt()), times(1));
        movement.verify(() -> AgentMovementCommandRuntime.follow(any(), any()), never());
        movement.verify(() -> AgentMovementCommandRuntime.stop(any()), never());
    }

    @Test
    void detachedMemberHandedBackIsNeverWarped() {
        AgentPartyQuestHandback.install((id, questKey) ->
                "kpq".equals(questKey) ? AgentPartyQuestHandback.Handback.FOLLOW_LEADER : null);
        AgentPartyQuestEngagementRegistry.unindexMember(engagement, COMPANION);

        AgentPartyQuestLifecycleRuntime.recoverDetachedMember(COMPANION, 2_000L);

        movement.verify(() -> AgentMovementCommandRuntime.follow(entry, owner), times(1));
        maps.verify(AgentMapGatewayRuntime::map, never());
        verifyNoTownLifeRequest();

        // Still down or inside the event: it only stops, and is never warped out.
        when(companion.getHp()).thenReturn(0);
        AgentPartyQuestLifecycleRuntime.recoverDetachedMember(COMPANION, 2_100L);

        movement.verify(() -> AgentMovementCommandRuntime.stop(entry), times(1));
        movement.verify(() -> AgentMovementCommandRuntime.follow(entry, owner), times(1));
        maps.verify(AgentMapGatewayRuntime::map, never());
    }

    private void verifyNoTownLifeRequest() {
        townLife.verify(() -> AgentTownLifeRuntime.requestLocal(
                any(), any(), any(), any(), anyLong(), anyInt()), never());
    }
}
