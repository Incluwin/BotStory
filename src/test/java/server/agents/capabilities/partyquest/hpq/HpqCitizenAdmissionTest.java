package server.agents.capabilities.partyquest.hpq;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PartyGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.runtime.activity.AgentActivityAdmissionCoordinator;
import server.agents.runtime.activity.AgentActivityBootstrap;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The population adopts a citizen party that formed below Tory (CitizenPartyQuests): its citizen leader leads, the
 * party stays its own (a stock Agent-led run would own it), the leader picks the bonus as an Agent leader does, and the
 * session is a citizen run, never a companion run, from its first tick. A human who took the invite is a human member.
 */
class HpqCitizenAdmissionTest {
    private static final int LIA = 742_200;
    private static final int MIRA = 742_201;
    private static final int PIP = 742_202;
    private static final int ANN = 742_203;

    private final Character lia = member(LIA, "Lia");
    private final Character mira = member(MIRA, "Mira");
    private final Character pip = member(PIP, "Pip");
    private final Character ann = member(ANN, "Ann");
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final PartyGateway party = mock(PartyGateway.class);
    private AgentHpqAdmissionService.AdmissionResult admitted;

    @BeforeEach
    void citizensBelowTory() {
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        MockedStatic<AgentRuntimeIdentityRuntime> identity = open(AgentRuntimeIdentityRuntime.class);
        for (Character citizen : List.of(lia, mira, pip)) {
            AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(citizen.getId())).thenReturn(entry);
            identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(citizen);
        }
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        AgentClientGateway clients = mock(AgentClientGateway.class);
        open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        AgentActivityAdmissionCoordinator admission = mock(AgentActivityAdmissionCoordinator.class);
        when(admission.prepare(anyString(), any(), any(), anyString(), anyLong())).thenReturn(true);
        open(AgentActivityBootstrap.class).when(AgentActivityBootstrap::admission).thenReturn(admission);
        CharacterGateway characters = mock(CharacterGateway.class);
        when(characters.findOnlineCharacterById(ANN)).thenReturn(ann);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        PrimitiveCapabilityGateway primitives = mock(PrimitiveCapabilityGateway.class);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(primitives);
        open(AgentSchedulerRuntime.class); // no lobby tick and no watchdog
    }

    @AfterEach
    void tearDown() {
        if (admitted != null && admitted.session() != null) {
            AgentHpqSessionRegistry.remove(admitted.session());
        }
        if (admitted != null && admitted.engagement() != null) {
            AgentPartyQuestEngagementRegistry.remove(admitted.engagement());
        }
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    @Test
    void anAllCitizenPartyGoesInAsACitizenRunOfItsOwnParty() {
        party(lia, mira, pip);

        admitted = AgentHpqAdmissionService.admitCitizens(lia, List.of(lia, mira, pip), 7L, 1_000L);

        assertTrue(admitted.success(), admitted.message());
        AgentHpqSession session = admitted.session();
        assertTrue(session.citizenRun());
        assertFalse(session.companionAdopted(), "a citizen run is not a companion run: it runs the stock Agent-led path");
        assertFalse(AgentHpqCoordinator.playersRun(new AgentHpqSession(AgentHpqSession.Mode.PRODUCTION, 1L, 1, 3, 0L)));
        assertTrue(AgentHpqCoordinator.playersRun(session));
        assertEquals(AgentHpqSession.PartyOwnership.EXTERNAL, session.partyOwnership());
        assertEquals(AgentHpqBonusPolicy.defaultMode(), session.bonusMode());
        assertEquals(AgentHpqSession.Mode.PRODUCTION, session.mode());
        assertEquals(LIA, session.eventLeaderId());
        assertEquals(LIA, session.operatorId());
        assertEquals(LIA, session.executionAgentId());
    }

    @Test
    void aHumanWhoTookTheInviteGoesInAsAHumanMember() {
        party(lia, mira, pip, ann);

        admitted = AgentHpqAdmissionService.admitCitizens(lia, List.of(lia, mira, pip, ann), 7L, 1_000L);

        assertTrue(admitted.success(), admitted.message());
        assertEquals(AgentHpqMemberState.MemberType.HUMAN, admitted.session().member(ANN).memberType());
        assertEquals(AgentHpqMemberState.MemberType.AGENT, admitted.session().member(MIRA).memberType());
        assertEquals(LIA, admitted.session().eventLeaderId());
    }

    @Test
    void aCitizenAwayFromHenesysParkKeepsThePartyOut() {
        party(lia, mira, pip);
        when(pip.getMapId()).thenReturn(100_000_000);

        admitted = AgentHpqAdmissionService.admitCitizens(lia, List.of(lia, mira, pip), 7L, 1_000L);

        assertFalse(admitted.success());
        assertEquals("Pip is not at the Henesys PQ entrance", admitted.message());
    }

    @Test
    void theCompanionDeskStillAdoptsACompanionRun() {
        party(lia, mira, pip);

        admitted = AgentHpqAdmissionService.admit(lia, lia, List.of(lia, mira, pip), 7L, 1_000L);

        assertTrue(admitted.success(), admitted.message());
        assertTrue(admitted.session().companionAdopted());
        assertFalse(admitted.session().citizenRun());
    }

    /** The live party: the first member leads. */
    private void party(Character... members) {
        List<AgentPartyMemberSnapshot> snapshots = new ArrayList<>();
        for (Character member : members) {
            snapshots.add(new AgentPartyMemberSnapshot(member.getId(), member.getName(), snapshots.isEmpty(),
                    AgentHpqDefinition.RECRUIT_MAP));
        }
        when(party.snapshot(any())).thenReturn(new AgentPartySnapshot(32, snapshots));
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
        when(member.getMapId()).thenReturn(AgentHpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(15);
        when(member.getHp()).thenReturn(500);
        when(member.getInventory(InventoryType.USE)).thenReturn(mock(Inventory.class));
        when(member.getInventory(InventoryType.ETC)).thenReturn(mock(Inventory.class));
        return member;
    }
}
