package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A citizen run of HPQ (docs/CITIZEN_PARTY_QUESTS_PLAN.md round 2): a citizen leads citizens, and maybe a human who took
 * their invite, on HPQ's stock Agent-led path. What it adds there for a run with players in it: its Agents hold full rate
 * in the instance, it says none of the stock status lines, a downed citizen taps out through its host while three stand,
 * a human is asked for the cakes he picked up and is told to claim but never waited for, and its run counts against the
 * lobby caps the background director keeps.
 */
class HpqCitizenRunCoordinatorTest {
    private static final Point TORY = new Point(1434, 352);

    private final HpqRunFixture run = new HpqRunFixture();
    private final List<AgentPartyQuestHandback.TapOut> tapOuts = new ArrayList<>();
    private final Character lia = run.companion(1, "Lia", new Point(-183, -188));
    private final Character mira = run.companion(2, "Mira", new Point(-293, -188));
    private final Character pip = run.companion(3, "Pip", new Point(-73, -188));
    private final Character tobi = run.companion(4, "Tobi", new Point(-120, -188));

    @BeforeEach
    void theirHost() {
        run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        when(run.actions.npcPosition(any(), eq(AgentHpqDefinition.ENTRY_NPC))).thenReturn(new Point(TORY));
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                return AgentPartyQuestHandback.Handback.RELEASE;
            }

            @Override
            public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
                tapOuts.add(request);
                return true;
            }
        });
    }

    @AfterEach
    void tearDown() {
        AgentPartyQuestHandback.install(null);
        run.close();
    }

    private boolean fullRate(Character citizen) {
        return run.entry(citizen).simulationState().fullRateSimulationRequired();
    }

    @Test
    void itsCitizensHoldFullRateInTheInstanceAndAStockRunsDoNot() {
        AgentHpqSession session = run.citizenSession(AgentHpqSession.Phase.DEFENDING_BUNNY, lia, mira, pip);

        AgentHpqCoordinator.tick(session, 2_000L);

        for (Character citizen : List.of(lia, mira, pip)) {
            assertTrue(fullRate(citizen), citizen.getName());
        }
        AgentHpqSessionRegistry.remove(session);
        AgentHpqFullRateSimulation.releaseAll(session);
        AgentHpqSession stock = run.session(false, AgentHpqSession.Phase.DEFENDING_BUNNY, lia, mira, pip);
        AgentHpqCoordinator.tick(stock, 3_000L);
        assertFalse(fullRate(mira));
    }

    @Test
    void itGathersAtToryWithoutTheStockReadyLine() {
        AgentHpqSession session = run.citizenSession(AgentHpqSession.Phase.PREPARING, lia, mira, pip);
        for (Character citizen : List.of(lia, mira, pip)) {
            run.outside(citizen, AgentHpqDefinition.RECRUIT_MAP);
            run.moveTo(citizen, new Point(TORY.x - 50, TORY.y));
        }

        AgentHpqCoordinator.tick(session, 2_000L);
        AgentHpqCoordinator.tick(session, 8_000L);

        assertEquals(AgentHpqSession.Phase.ENTERING, session.phase(), "gathered, then in after the stock delay");
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
    }

    @Test
    void aStockRunStillSaysItsReadyLine() {
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.PREPARING, lia, mira, pip);
        for (Character citizen : List.of(lia, mira, pip)) {
            run.outside(citizen, AgentHpqDefinition.RECRUIT_MAP);
            run.moveTo(citizen, new Point(TORY.x - 50, TORY.y));
        }

        AgentHpqCoordinator.tick(session, 2_000L);

        verify(run.packets).broadcastChatText(lia, AgentHpqCoordinator.STOCK_READY_LINE, false, 0);
    }

    @Test
    void aDownedCitizenTapsOutAndTheRunGoesOnWithThree() {
        AgentHpqSession session = run.citizenSession(AgentHpqSession.Phase.DEFENDING_BUNNY, lia, mira, pip, tobi);

        when(tobi.getHp()).thenReturn(0);
        AgentHpqCoordinator.tick(session, 2_000L);
        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase(), "a down citizen pauses nothing");
        when(tobi.getHp()).thenReturn(500);
        run.outside(tobi, AgentHpqDefinition.EXIT_MAP);
        AgentHpqCoordinator.tick(session, 3_000L);

        assertEquals(List.of(new AgentPartyQuestHandback.TapOut("hpq", session.sessionId(), tobi.getId(),
                AgentHpqDefinition.EXIT_MAP, 3_000L)), tapOuts);
        run.leaveParty(tobi); // the population takes it out of the party
        AgentHpqCoordinator.tick(session, 4_000L);

        assertNull(session.member(tobi.getId()));
        assertEquals(3, session.memberCount());
        assertEquals(AgentHpqSession.Phase.DEFENDING_BUNNY, session.phase(), "four to three carries on");
    }

    @Test
    void aStockRunNeverAsksItsHostToTapOut() {
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.DEFENDING_BUNNY, lia, mira, pip, tobi);
        when(tobi.getHp()).thenReturn(0);
        AgentHpqCoordinator.tick(session, 2_000L);
        when(tobi.getHp()).thenReturn(500);
        run.outside(tobi, AgentHpqDefinition.EXIT_MAP);
        AgentHpqCoordinator.tick(session, 3_000L);

        assertTrue(tapOuts.isEmpty());
    }

    @Test
    void aHumanWithCakesIsAskedForThemOnce() {
        Character ann = run.human(5, "Ann", new Point(-150, -188));
        AgentHpqSession session = run.citizenSession(AgentHpqSession.Phase.DEFENDING_BUNNY, lia, mira, pip, ann);
        run.holds(ann, AgentHpqDefinition.RICE_CAKE, 3);

        AgentHpqCoordinator.tick(session, 2_000L);
        AgentHpqCoordinator.tick(session, 3_000L);

        // once over two ticks (said() verifies exactly one)
        run.said(session, "cakes-5", lia, "Ann drop the rice cakes by me pls, growlie only takes them from the leader");
    }

    @Test
    void aHumanIsToldToClaimAndTheCitizensGoWithoutWaiting() {
        Character ann = run.human(5, "Ann", new Point(0, 200));
        AgentHpqSession session = cleared(true, lia, mira, pip, ann);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.EXITING, session.phase(), "every citizen has its reward; Ann is not waited for");
        run.said(session, "claim-5", lia, "Ann talk to tory for ur reward, we're heading out");
        verify(ann, never()).dropMessage(anyInt(), any());
    }

    @Test
    void aStockRunWaitsForItsHumansClaim() {
        Character ann = run.human(5, "Ann", new Point(0, 200));
        AgentHpqSession session = cleared(false, lia, mira, pip, ann);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.CLAIMING_REWARD, session.phase());
        verify(ann).dropMessage(6, AgentHpqCoordinator.STOCK_CLAIM_LINE);
    }

    @Test
    void backAtToryItCompletesQuietlyAndKeepsItsParty() {
        AgentHpqSession session = run.citizenSession(AgentHpqSession.Phase.EXITING, lia, mira, pip);
        for (Character citizen : List.of(lia, mira, pip)) {
            run.outside(citizen, AgentHpqDefinition.RECRUIT_MAP);
            run.moveTo(citizen, new Point(TORY.x - 50, TORY.y));
        }

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.COMPLETED, session.phase());
        verify(run.packets, never()).broadcastChatText(any(), any(), anyBoolean(), anyInt());
        verify(run.party, never()).leaveCurrentParty(any());
        run.lifecycle.verify(() -> server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(true), any(), eq(2_000L), any()));
    }

    @Test
    void aCitizenRunCountsAgainstTheBackgroundLobbyCaps() {
        AgentClientGateway clients = mock(AgentClientGateway.class);
        try (MockedStatic<AgentClientGatewayRuntime> gateway = mockStatic(AgentClientGatewayRuntime.class)) {
            gateway.when(AgentClientGatewayRuntime::clients).thenReturn(clients);
            when(clients.world(any())).thenReturn(0);
            when(clients.channel(any())).thenReturn(1);
            assertTrue(AgentHpqLobbyPolicy.backgroundSlotAvailable(0, 1));

            run.citizenSession(AgentHpqSession.Phase.DEFENDING_BUNNY, lia, mira, pip);

            assertFalse(AgentHpqLobbyPolicy.backgroundSlotAvailable(0, 1), "one a channel, and it is the citizens'");
        }
    }

    /** The clear is in: everyone on the clear map, rewards frozen, every Agent's reward claimed. */
    private AgentHpqSession cleared(boolean citizen, Character... members) {
        for (Character member : members) run.onMap(member, AgentHpqDefinition.CLEAR_MAP);
        AgentHpqSession session = citizen
                ? run.citizenSession(AgentHpqSession.Phase.CLAIMING_REWARD, members)
                : run.session(false, AgentHpqSession.Phase.CLAIMING_REWARD, members);
        session.freezeRewardEligibility();
        session.markCleared(1_000L);
        for (Character member : members) {
            if (run.entries.containsKey(member.getId())) {
                assertTrue(session.beginRewardClaim(member.getId()));
                assertTrue(session.completeRewardClaim(member.getId()));
            }
        }
        return session;
    }
}
