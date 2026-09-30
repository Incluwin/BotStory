package server.agents.capabilities.partyquest.kpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestRuntime;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.capabilities.townlife.AgentTownLifeSessionResult;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.MapGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.runtime.activity.AgentActivityAdmissionCoordinator;
import server.agents.runtime.activity.AgentActivityBootstrap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * A companion lineup whose KPQ start fails after admission (P6 WP7, the host's abort): the real PRODUCTION admission
 * of a human leader and two companions at the Kerning entrance, then the stop through the first companion
 * (AgentPartyQuestRuntime.forceStop). With the host's FOLLOW_LEADER rule the stop hands each companion back to its
 * leader where it stands: no engagement or session keeps it, and nothing warps it into Kerning TownLife.
 */
class AgentKpqAdoptRollbackTest {
    private static final int BEN = 740_200;
    private static final int MIRA = 740_201;
    private static final int TOBI = 740_202;

    private final Character ben = member(BEN, "Ben");
    private final Character mira = member(MIRA, "Mira");
    private final Character tobi = member(TOBI, "Tobi");
    private final AgentRuntimeEntry miraEntry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
    private final AgentRuntimeEntry tobiEntry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private MockedStatic<AgentTownLifeRuntime> townLife;
    private MockedStatic<AgentMovementCommandRuntime> movement;
    private MockedStatic<AgentMapGatewayRuntime> maps;
    private AgentKpqAdmissionService.AdmissionResult admitted;

    @BeforeEach
    void benAndHisCompanionsAtTheEntrance() {
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        MockedStatic<AgentRuntimeIdentityRuntime> identity = open(AgentRuntimeIdentityRuntime.class);
        for (Object[] agent : new Object[][] {{MIRA, miraEntry, mira}, {TOBI, tobiEntry, tobi}}) {
            int id = (Integer) agent[0];
            AgentRuntimeEntry entry = (AgentRuntimeEntry) agent[1];
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
            registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(id)).thenReturn(BEN);
            identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(agent[2]);
        }
        PartyGateway party = mock(PartyGateway.class);
        when(party.snapshot(any())).thenReturn(new AgentPartySnapshot(9, List.of(
                new AgentPartyMemberSnapshot(BEN, "Ben", true, AgentKpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(MIRA, "Mira", false, AgentKpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(TOBI, "Tobi", false, AgentKpqDefinition.RECRUIT_MAP))));
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        AgentClientGateway clients = mock(AgentClientGateway.class);
        open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        AgentActivityAdmissionCoordinator admission = mock(AgentActivityAdmissionCoordinator.class);
        when(admission.prepare(anyString(), any(), any(), anyString(), anyLong())).thenReturn(true);
        open(AgentActivityBootstrap.class).when(AgentActivityBootstrap::admission).thenReturn(admission);
        CharacterGateway characters = mock(CharacterGateway.class);
        when(characters.findOnlineCharacterById(BEN)).thenReturn(ben);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        PrimitiveCapabilityGateway primitives = mock(PrimitiveCapabilityGateway.class);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(primitives);
        open(AgentSchedulerRuntime.class); // no lobby tick and no watchdog
        open(AgentKpqDialogue.class); // "Joining KPQ."
        townLife = open(AgentTownLifeRuntime.class); // the stock recovery, which must not be asked
        townLife.when(() -> AgentTownLifeRuntime.requestLocal(any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new AgentTownLifeSessionResult(AgentTownLifeSessionResult.Status.STARTED, 103_000_000, ""));
        movement = open(AgentMovementCommandRuntime.class);
        maps = open(AgentMapGatewayRuntime.class);
        maps.when(AgentMapGatewayRuntime::map).thenReturn(mock(MapGateway.class));
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
    }

    @AfterEach
    void tearDown() {
        AgentPartyQuestHandback.install(null);
        if (admitted != null) {
            AgentKpqSessionRegistry.remove(admitted.session());
            AgentPartyQuestEngagementRegistry.remove(admitted.engagement());
        }
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    @Test
    void theStopHandsEveryCompanionBackToItsLeaderAndNothingKeepsIt() {
        admitted = AgentKpqAdmissionService.admit(ben, ben, List.of(ben, mira, tobi), 7L, 1_000L);
        assertTrue(admitted.success(), admitted.message());

        AgentPartyQuestRuntime.forceStop(MIRA, "companion KPQ start failed", 2_000L);

        for (int id : List.of(BEN, MIRA, TOBI)) {
            assertNull(AgentPartyQuestEngagementRegistry.forMember(id), "engagement of " + id);
            assertNull(AgentKpqSessionRegistry.forMember(id), "session of " + id);
        }
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(BEN));
        assertNull(AgentKpqSessionRegistry.forOperator(BEN));
        movement.verify(() -> AgentMovementCommandRuntime.follow(miraEntry, ben));
        movement.verify(() -> AgentMovementCommandRuntime.follow(tobiEntry, ben));
        townLife.verify(() -> AgentTownLifeRuntime.requestLocal(any(), any(), any(), any(), anyLong(), anyInt()), never());
        maps.verify(AgentMapGatewayRuntime::map, never()); // never warped to the recovery map
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    private static Character member(int id, String name) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getMapId()).thenReturn(AgentKpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(25);
        when(member.getHp()).thenReturn(500);
        when(member.getInventory(InventoryType.USE)).thenReturn(mock(Inventory.class));
        when(member.getInventory(InventoryType.ETC)).thenReturn(mock(Inventory.class));
        return member;
    }
}
