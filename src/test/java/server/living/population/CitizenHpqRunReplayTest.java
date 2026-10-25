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
import server.agents.capabilities.partyquest.hpq.AgentHpqDefinition;
import server.agents.capabilities.partyquest.hpq.AgentHpqMemberState;
import server.agents.capabilities.partyquest.hpq.AgentHpqSession;
import server.agents.capabilities.partyquest.hpq.AgentHpqSessionRegistry;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A citizen party's HPQ from Henesys Park to its day after, through the engine's own doors (docs/CITIZEN_PARTY_QUESTS_
 * PLAN.md round 2): CitizenPartyQuests forms the party below Tory, HPQ's real citizen door admits it
 * (AgentHpqAdmissionService.admitCitizens, the engagement and session registries, HPQ's lobby policy), and the run's
 * end is replayed as HPQ's release reports it (the session leaves its registry, then
 * AgentPartyQuestLifecycleRuntime.childFinished), through the living world's own handback rule (CompanionRunLifecycle)
 * back to the population. The stages between are the coordinator's (HpqCitizenRunCoordinatorTest covers what a citizen
 * run changes there).
 */
class CitizenHpqRunReplayTest {
    private static final long MINUTE = 60_000L;

    private final CitizenLobbyFixture lobby = new CitizenLobbyFixture(CitizenLobbyFixture.HPQ);
    private final PartyQuestGateway instances = mock(PartyQuestGateway.class, withSettings().stubOnly());
    private CitizenPartyQuests quests;
    private Character lia;
    private Character mira;
    private Character pip;

    @BeforeEach
    void citizensBelowToryWithTheEnginesDoors() {
        lia = lobby.citizen(1, "Lia", 15);
        mira = lobby.citizen(2, "Mira", 13);
        pip = lobby.citizen(3, "Pip", 18);
        NPC tory = mock(NPC.class, withSettings().stubOnly());
        when(tory.getPosition()).thenReturn(new Point(-500, 421)); // where the fixture stands everyone
        when(lobby.lobbyMap.getNPCById(AgentHpqDefinition.ENTRY_NPC)).thenReturn(tory);
        AgentClientGateway clients = mock(AgentClientGateway.class, withSettings().stubOnly());
        when(clients.world(any())).thenReturn(0);
        when(clients.channel(any())).thenReturn(CitizenLobbyFixture.CHANNEL);
        lobby.open(AgentClientGatewayRuntime.class).when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        lobby.open(AgentPartyQuestGatewayRuntime.class).when(AgentPartyQuestGatewayRuntime::partyQuest)
                .thenReturn(instances); // no HPQ lobby taken yet
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
        lobby.config.PQ_RUN_QUESTS = "kpq,hpq";
    }

    @AfterEach
    void tearDown() {
        AgentPartyQuestHandback.install(null);
        AgentHpqSession session = AgentHpqSessionRegistry.forOperator(lia.getId());
        if (session != null) {
            AgentHpqSessionRegistry.remove(session);
        }
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forOperator(lia.getId());
        if (engagement != null) {
            AgentPartyQuestEngagementRegistry.remove(engagement);
        }
        lobby.close();
    }

    @Test
    void threeCitizensGoInWithoutAHumanAndTheirClearComesHome() {
        quests.tick(0L);
        quests.tick(CitizenPartyQuests.FILL_WAIT_MS); // three of a preferred four wait a while, then go in

        AgentHpqSession session = AgentHpqSessionRegistry.forOperator(lia.getId());
        assertNotNull(session, "HPQ's admission took the party in");
        assertTrue(session.citizenRun());
        assertFalse(session.companionAdopted());
        assertEquals(AgentHpqSession.PartyOwnership.EXTERNAL, session.partyOwnership());
        assertEquals(lia.getId(), session.eventLeaderId());
        assertEquals(Set.of(lia.getId(), mira.getId(), pip.getId()), members(session));
        assertEquals(AgentPartyQuestHandback.Handback.RELEASE, AgentPartyQuestHandback.handback(mira.getId(), "hpq"));

        // HPQ's release of a cleared run: the session leaves its registry, then the run is reported finished.
        AgentHpqSessionRegistry.remove(session);
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), true, "completed", 9 * MINUTE);
        assertNull(AgentPartyQuestEngagementRegistry.forOperator(lia.getId()), "every citizen handed back, none held");

        quests.tick(9 * MINUTE + 5_000L);

        assertTrue(lobby.session(pip).journal.summary(300).contains("cleared HPQ with Lia, Mira"),
                lobby.session(pip).journal.summary(300));
        assertTrue(lobby.triggers().contains("pq.clear"));
        assertTrue(quests.status().contains("hpq 1 runs 1 cleared 0 failed"), quests.status());
        assertTrue(lobby.asked.containsAll(List.of("replan Lia", "replan Mira", "replan Pip")), lobby.asked::toString);
        assertTrue(quests.coolingDown(lia.getId(), 10 * MINUTE));
    }

    @Test
    void aHumanWhoTookTheInviteGoesInAsAHumanMemberAndIsThanked() {
        Character ann = lobby.human(10, "Ann", 14);
        quests.tick(0L);
        lobby.parties.accept(ann);
        quests.tick(10_000L);

        AgentHpqSession session = AgentHpqSessionRegistry.forOperator(lia.getId());
        assertNotNull(session, "four, HPQ's preferred, go in at once");
        assertEquals(AgentHpqMemberState.MemberType.HUMAN, session.member(ann.getId()).memberType());
        assertEquals(Set.of(lia.getId(), mira.getId(), pip.getId(), ann.getId()), members(session));

        AgentHpqSessionRegistry.remove(session);
        AgentPartyQuestLifecycleRuntime.childFinished(session.sessionId(), true, "completed", 8 * MINUTE);
        quests.tick(8 * MINUTE + 5_000L);

        assertTrue(lobby.session(mira).journal.summary(300).contains("cleared HPQ with Lia, Pip, Ann"),
                lobby.session(mira).journal.summary(300));
        assertTrue(lobby.said.stream().anyMatch(event -> event.trigger.equals("pq.thanks")
                && "Ann".equals(event.subjectName)));
    }

    @Test
    void aHumanRunInOneOfTheTwoLobbiesKeepsTheCitizensOut() {
        when(instances.openInstances(any(), eq("HenesysPQ"))).thenReturn(1);

        quests.tick(0L);
        quests.tick(CitizenPartyQuests.FILL_WAIT_MS);

        assertNull(AgentHpqSessionRegistry.forOperator(lia.getId()));
        assertEquals(3, lobby.parties.memberIds(lia).size(), "the party stays below Tory, formed");
    }

    @Test
    void withHpqOutOfPqRunQuestsTheyOnlyGatherAndInvite() {
        lobby.config.PQ_RUN_QUESTS = "kpq";

        quests.tick(0L);
        quests.tick(CitizenPartyQuests.FILL_WAIT_MS);

        assertNull(AgentHpqSessionRegistry.forOperator(lia.getId()));
        assertFalse(quests.manages(lobby.session(lia)), "partyUp's lobby parties, as before");
    }

    private static Set<Integer> members(AgentHpqSession session) {
        return session.members().stream().map(AgentHpqMemberState::characterId).collect(Collectors.toSet());
    }
}
