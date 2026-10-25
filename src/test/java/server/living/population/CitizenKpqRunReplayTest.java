package server.living.population;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.kpq.AgentKpqDefinition;
import server.agents.capabilities.partyquest.kpq.AgentKpqMemberState;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.capabilities.partyquest.kpq.AgentKpqSessionRegistry;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentSchedulerRuntime;
import server.agents.runtime.activity.AgentActivityAdmissionCoordinator;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.life.NPC;
import server.living.LivingWorld;
import server.living.roster.CompanionPartyQuestService;
import server.living.roster.CompanionRunLifecycle;
import server.living.roster.RosterService;

import java.awt.Point;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A citizen party's KPQ from the lobby to its day after, through the engine's own doors (docs/CITIZEN_PARTY_QUESTS_PLAN.md):
 * CitizenPartyQuests forms the party at Lakelis, KPQ's real citizen door admits it (AgentKpqAdmissionService, the
 * engagement and session registries, the lobby policy), and the run's end is replayed as KPQ's termination reports it
 * (the session leaves its registry, then AgentPartyQuestLifecycleRuntime.childFinished), through the living world's own
 * handback rule (CompanionRunLifecycle) back to the population. The stages between are the coordinator's
 * (CitizenRunCoordinatorTest covers what a citizen run changes there).
 */
class CitizenKpqRunReplayTest {
    private static final long MINUTE = 60_000L;

    private final CitizenLobbyFixture lobby = new CitizenLobbyFixture();
    private CitizenPartyQuests quests;
    private Character lia;
    private Character mira;
    private Character pip;

    @BeforeEach
    void citizensAtLakelisWithTheEnginesDoors() {
        lia = lobby.citizen(1, "Lia", 25);
        mira = lobby.citizen(2, "Mira", 23);
        pip = lobby.citizen(3, "Pip", 27);
        NPC lakelis = mock(NPC.class, withSettings().stubOnly());
        when(lakelis.getPosition()).thenReturn(new Point(-540, 421));
        when(lobby.lobbyMap.getNPCById(AgentKpqDefinition.ENTRY_NPC)).thenReturn(lakelis);
        AgentClientGateway clients = mock(AgentClientGateway.class, withSettings().stubOnly());
        lobby.open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        PartyQuestGateway instances = mock(PartyQuestGateway.class, withSettings().stubOnly()); // no lobby taken yet
        lobby.open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest).thenReturn(instances);
        AgentActivityAdmissionCoordinator admission = mock(AgentActivityAdmissionCoordinator.class, withSettings().stubOnly());
        when(admission.prepare(anyString(), any(), any(), anyString(), anyLong())).thenReturn(true);
        lobby.open(AgentActivityBootstrap.class).when(AgentActivityBootstrap::admission).thenReturn(admission);
        lobby.open(AgentCharacterGatewayRuntime.class).when(AgentCharacterGatewayRuntime::characters)
                .thenReturn(mock(CharacterGateway.class, withSettings().stubOnly()));
        lobby.open(AgentPrimitiveCapabilityGatewayRuntime.class).when(AgentPrimitiveCapabilityGatewayRuntime::gateway)
                .thenReturn(mock(PrimitiveCapabilityGateway.class, withSettings().stubOnly()));
        lobby.open(AgentSchedulerRuntime.class); // no lobby tick and no watchdog
        lobby.open(AgentTownLifeRuntime.class);
        lobby.open(AgentMovementCommandRuntime.class);
        quests = new CitizenPartyQuests(lobby.population, CitizenPqDoor.shipped(), new Random(7L));
        lobby.open(Population.class).when(Population::get).thenReturn(lobby.population);
        when(lobby.population.partyQuests()).thenReturn(quests);
        LivingWorld world = mock(LivingWorld.class, withSettings().stubOnly());
        when(world.roster()).thenReturn(mock(RosterService.class, withSettings().stubOnly()));
        AgentPartyQuestHandback.install(new CompanionRunLifecycle(world,
                mock(CompanionPartyQuestService.class, withSettings().stubOnly())));
    }

    @AfterEach
    void tearDown() {
        AgentPartyQuestHandback.install(null);
        AgentKpqSession session = AgentKpqSessionRegistry.forOperator(lia.getId());
        if (session != null) {
            AgentKpqSessionRegistry.remove(session);
        }
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(lia.getId());
        if (engagement != null) {
            AgentPartyQuestEngagementRegistry.remove(engagement);
        }
        lobby.close();
    }

    @Test
    void aPartyWithAnInvitedHumanGoesInThroughKpqsOwnAdmissionAndItsClearComesHome() {
        Character ann = lobby.human(10, "Ann", 24);
        quests.tick(0L);
        lobby.parties.accept(ann);
        quests.tick(10_000L);

        AgentKpqSession session = AgentKpqSessionRegistry.forOperator(lia.getId());
        assertNotNull(session, "KPQ's admission took the party in");
        assertTrue(session.citizenRun());
        assertEquals(AgentKpqSession.PartyOwnership.EXTERNAL, session.partyOwnership());
        assertEquals(lia.getId(), session.eventLeaderId());
        assertEquals(AgentKpqMemberState.MemberType.HUMAN, session.member(ann.getId()).memberType());
        assertEquals(Set.of(lia.getId(), mira.getId(), pip.getId(), ann.getId()), members(session));
        assertEquals(AgentPartyQuestHandback.Handback.RELEASE, AgentPartyQuestHandback.handback(mira.getId(), "kpq"));

        // KPQ's termination of a cleared run: the session leaves its registry, then the run is reported finished.
        AgentKpqSessionRegistry.remove(session);
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), true, "completed", 18 * MINUTE);
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(lia.getId()), "every citizen handed back, none held");

        quests.tick(18 * MINUTE + 5_000L);

        assertTrue(lobby.session(mira).journal.summary(300).contains("cleared KPQ with Lia, Pip, Ann"));
        assertTrue(lobby.triggers().contains("pq.clear"));
        assertTrue(lobby.said.stream().anyMatch(event -> event.trigger.equals("pq.thanks")
                && "Ann".equals(event.subjectName)));
        assertTrue(quests.status().contains("kpq 1 runs 1 cleared 0 failed"), quests.status());
        assertTrue(lobby.asked.containsAll(List.of("replan Lia", "replan Mira", "replan Pip")), lobby.asked::toString);
        assertFalse(quests.holds(lia.getId()));
    }

    @Test
    void anAllCitizenPartyGoesInAndItsClearComesHome() {
        Character tobi = lobby.citizen(4, "Tobi", 29);
        quests.tick(0L);

        AgentKpqSession session = AgentKpqSessionRegistry.forOperator(lia.getId());
        assertNotNull(session, "four citizens go in without a human");
        assertTrue(session.citizenRun());
        assertEquals(lia.getId(), session.eventLeaderId());
        assertEquals(Set.of(lia.getId(), mira.getId(), pip.getId(), tobi.getId()), members(session));
        assertTrue(session.members().stream()
                .allMatch(member -> member.memberType() == AgentKpqMemberState.MemberType.AGENT));
        AgentKpqSessionRegistry.remove(session);
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), true, "completed", 16 * MINUTE);

        quests.tick(16 * MINUTE);

        assertTrue(lobby.session(tobi).journal.summary(300).contains("cleared KPQ with Lia, Mira, Pip"),
                lobby.session(tobi).journal.summary(300));
        assertTrue(lobby.triggers().contains("pq.clear"));
        assertFalse(lobby.triggers().contains("pq.thanks"), "no human to thank");
        assertTrue(quests.status().contains("kpq 1 runs 1 cleared 0 failed"), quests.status());
        assertEquals(4, lobby.asked.stream().filter(line -> line.startsWith("replan ")).count());
        assertTrue(quests.coolingDown(lia.getId(), 17 * MINUTE));
    }

    @Test
    void anAllCitizenPartysFailedRunComesHomeAsAFailure() {
        Character tobi = lobby.citizen(4, "Tobi", 29);
        quests.tick(0L);

        AgentKpqSession session = AgentKpqSessionRegistry.forOperator(lia.getId());
        assertNotNull(session);
        assertEquals(Set.of(lia.getId(), mira.getId(), pip.getId(), tobi.getId()), members(session));
        AgentKpqSessionRegistry.remove(session);
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), false,
                "Stage 3 rejected every authored puzzle formation", 25 * MINUTE);

        quests.tick(25 * MINUTE);

        assertTrue(lobby.triggers().contains("pq.fail"));
        assertTrue(quests.recentRuns().getFirst().contains("(Stage 3 rejected every authored puzzle formation)"));
        assertTrue(lobby.session(tobi).journal.summary(300).contains("failed KPQ"));
        assertEquals(4, lobby.asked.stream().filter(line -> line.startsWith("replan ")).count());
        assertTrue(quests.coolingDown(tobi.getId(), 26 * MINUTE));
    }

    @Test
    void theLastLobbyAHumanCouldEnterIsNeverTaken() {
        lobby.citizen(4, "Tobi", 29);
        PartyQuestGateway instances = AgentPartyQuestGatewayRuntime.partyQuest();
        when(instances.openInstances(any(), any())).thenReturn(1); // a human party is in one of KPQ's two lobbies

        quests.tick(0L);

        assertNull(AgentKpqSessionRegistry.forOperator(lia.getId()));
        assertEquals(4, lobby.parties.memberIds(lia).size(), "the party stays at Lakelis, formed");
    }

    private static Set<Integer> members(AgentKpqSession session) {
        return session.members().stream().map(AgentKpqMemberState::characterId).collect(Collectors.toSet());
    }
}
