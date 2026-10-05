package server.agents.capabilities.partyquest.lpq;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import constants.skills.Archer;
import constants.skills.FPWizard;
import constants.skills.Magician;
import constants.skills.Rogue;
import constants.skills.Warrior;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ben, a human, leads his party of five companions at the Red Sign on 221024500, a lineup that covers LPQ's
 * capabilities: Mira (FP wizard: Teleport and Magic Claw), Kes (assassin: Dark Sight and Lucky Seven), Pel (hunter),
 * Hal (spearman: Power Strike) and Cas (magician: Energy Bolt). The real LPQ admission runs against mocked gateways,
 * with the host's FOLLOW_LEADER handback, no scheduler ticks and no TownLife.
 */
final class LpqAdmitFixture implements AutoCloseable {
    static final int BEN = 742_500;
    static final int MIRA = 742_501;
    static final int KES = 742_502;
    static final int PEL = 742_503;
    static final int HAL = 742_504;
    static final int CAS = 742_505;
    static final List<Integer> IDS = List.of(BEN, MIRA, KES, PEL, HAL, CAS);

    final Character ben = member(BEN, "Ben", null);
    final Character mira = member(MIRA, "Mira", Job.FP_WIZARD, FPWizard.TELEPORT, Magician.MAGIC_CLAW);
    final Character kes = member(KES, "Kes", Job.ASSASSIN, Rogue.DARK_SIGHT, Rogue.LUCKY_SEVEN);
    final Character pel = member(PEL, "Pel", Job.HUNTER, Archer.DOUBLE_SHOT);
    final Character hal = member(HAL, "Hal", Job.SPEARMAN, Warrior.POWER_STRIKE);
    final Character cas = member(CAS, "Cas", Job.MAGICIAN, Magician.ENERGY_BOLT);
    final Map<Integer, AgentRuntimeEntry> entries = new LinkedHashMap<>();
    final AgentActivityAdmissionCoordinator admission = mock(AgentActivityAdmissionCoordinator.class);
    final PartyGateway party = mock(PartyGateway.class);
    final AgentClientGateway clients = mock(AgentClientGateway.class);
    final CharacterGateway characters = mock(CharacterGateway.class);
    final PrimitiveCapabilityGateway primitives = mock(PrimitiveCapabilityGateway.class);
    final MockedStatic<AgentRuntimeRegistry> registry;
    final MockedStatic<AgentTownLifeRuntime> townLife;
    final MockedStatic<AgentMovementCommandRuntime> movement;
    final MockedStatic<AgentMapGatewayRuntime> maps;
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    LpqAdmitFixture() {
        registry = open(AgentRuntimeRegistry.class);
        MockedStatic<AgentRuntimeIdentityRuntime> identity = open(AgentRuntimeIdentityRuntime.class);
        for (Character agent : List.of(mira, kes, pel, hal, cas)) {
            AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class, RETURNS_DEEP_STUBS);
            entries.put(agent.getId(), entry);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(agent.getId())).thenReturn(entry);
            registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(agent.getId())).thenReturn(BEN);
            identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(agent);
        }
        when(party.snapshot(any())).thenReturn(benLeadsHisFive());
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(party);
        open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        when(admission.prepare(anyString(), any(), any(), anyString(), anyLong())).thenReturn(true);
        open(AgentActivityBootstrap.class).when(AgentActivityBootstrap::admission).thenReturn(admission);
        when(characters.findOnlineCharacterById(BEN)).thenReturn(ben);
        open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
        open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(primitives);
        open(AgentSchedulerRuntime.class); // no lobby tick and no watchdog
        townLife = open(AgentTownLifeRuntime.class); // the stock recovery, which must not be asked
        townLife.when(() -> AgentTownLifeRuntime.requestLocal(any(), any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new AgentTownLifeSessionResult(
                        AgentTownLifeSessionResult.Status.STARTED, AgentLpqDefinition.RECRUIT_MAP, ""));
        movement = open(AgentMovementCommandRuntime.class);
        maps = open(AgentMapGatewayRuntime.class);
        maps.when(AgentMapGatewayRuntime::map).thenReturn(mock(MapGateway.class));
        AgentPartyQuestHandback.install((id, questKey) -> AgentPartyQuestHandback.Handback.FOLLOW_LEADER);
    }

    AgentRuntimeEntry entry(Character agent) {
        return entries.get(agent.getId());
    }

    /** The party in its order: Ben first, then his five companions. */
    List<Character> party() {
        return List.of(ben, mira, kes, pel, hal, cas);
    }

    /** The authoritative party: Ben leads his five, all at the Red Sign. */
    static AgentPartySnapshot benLeadsHisFive() {
        return new AgentPartySnapshot(11, List.of(
                new AgentPartyMemberSnapshot(BEN, "Ben", true, AgentLpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(MIRA, "Mira", false, AgentLpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(KES, "Kes", false, AgentLpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(PEL, "Pel", false, AgentLpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(HAL, "Hal", false, AgentLpqDefinition.RECRUIT_MAP),
                new AgentPartyMemberSnapshot(CAS, "Cas", false, AgentLpqDefinition.RECRUIT_MAP)));
    }

    /** Removes whatever an admission registered for this party: its LPQ session, engagement and lobby. */
    @Override
    public void close() {
        AgentPartyQuestHandback.install(null);
        for (int id : IDS) {
            AgentLpqSessionRegistry.remove(AgentLpqSessionRegistry.forMember(id));
            AgentLpqSessionRegistry.remove(AgentLpqSessionRegistry.forOperator(id));
            AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forMember(id);
            if (engagement != null) AgentPartyQuestEngagementRegistry.remove(engagement);
            engagement = AgentPartyQuestEngagementRegistry.forOperator(id);
            if (engagement != null) AgentPartyQuestEngagementRegistry.remove(engagement);
        }
        for (var lobby : AgentPartyQuestLobbyRegistry.lobbies()) {
            if (lobby.memberIds().contains(BEN)) AgentPartyQuestLobbyRuntime.unregister(lobby.lobbyId(), 0L);
        }
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    /** A Lv45 party member at the Red Sign with a free ETC slot and these learned skills. */
    static Character member(int id, String name, Job job, int... skills) {
        Character member = mock(Character.class);
        when(member.getId()).thenReturn(id);
        when(member.getName()).thenReturn(name);
        when(member.getJob()).thenReturn(job);
        when(member.getMapId()).thenReturn(AgentLpqDefinition.RECRUIT_MAP);
        when(member.getLevel()).thenReturn(45);
        when(member.getHp()).thenReturn(1_500);
        for (int skill : skills) when(member.getSkillLevel(skill)).thenReturn(1);
        when(member.getInventory(InventoryType.USE)).thenReturn(mock(Inventory.class));
        when(member.getInventory(InventoryType.ETC)).thenReturn(mock(Inventory.class));
        return member;
    }
}
