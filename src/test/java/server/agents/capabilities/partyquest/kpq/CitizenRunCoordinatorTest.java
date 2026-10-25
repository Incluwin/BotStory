package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.runtime.AgentRuntimeRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * A citizen run (docs/CITIZEN_PARTY_QUESTS_PLAN.md): a citizen leads citizens, and maybe a human who took their invite.
 * Live, the background director's citizen parties never got past stage 1: its Agents sat at a fifth of the combat rate
 * on unobserved maps, any death failed the run, and its narration was the engine's status lines. A citizen run holds
 * full rate in the instance, lets a downed member tap out as a companion does, and talks like players.
 */
class CitizenRunCoordinatorTest {
    private static final String BACK_IN_KERNING = "Party is back in Kerning and waiting for the next command.";

    private KpqRunFixture run;
    private final List<AgentPartyQuestHandback.TapOut> tapOuts = new ArrayList<>();
    private Character lia;
    private Character mira;
    private Character pip;
    private Character tobi;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        lia = run.companion(1, "Lia");
        mira = run.companion(2, "Mira");
        pip = run.companion(3, "Pip");
        tobi = run.companion(4, "Tobi");
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
        // Cloto has set this stage's answer, so each tick assigns and checks the formation.
        when(run.event.getProperty(AgentKpqDefinition.combinationStage(2).answerProperty())).thenReturn("answer");
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void aCitizenRunIsAPlayersRunAndAStockRunIsNot() {
        AgentKpqSession session = run.citizenSession(AgentKpqSession.Phase.STAGE_2, lia, mira, pip);
        assertTrue(AgentKpqCoordinator.playersRun(session));

        session.setCitizenRun(false);

        assertFalse(AgentKpqCoordinator.playersRun(session));
    }

    @Test
    void itsCitizensHoldFullRateInTheInstance() {
        AgentKpqSession session = run.citizenSession(AgentKpqSession.Phase.STAGE_2, lia, mira, pip, tobi);

        AgentKpqCoordinator.tick(session, 2_000L);

        for (Character citizen : List.of(lia, mira, pip, tobi)) {
            assertTrue(fullRate(citizen), citizen.getName());
        }
    }

    @Test
    void aStockRunOfTheSameAgentsKeepsTheMapsCadence() {
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_2, lia, mira, pip, tobi);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertFalse(fullRate(mira));
    }

    @Test
    void itsLeaderCallsTheFormationAsAPlayerDoes() {
        // Three on the ropes: the leader takes one too, so the formation is called on the first tick.
        AgentKpqSession session = run.citizenSession(AgentKpqSession.Phase.STAGE_2, lia, mira, pip);

        AgentKpqCoordinator.tick(session, 2_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(eq(lia), startsWith("Lia, grab rope ")), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(eq(lia), startsWith("Lia -> ")), never());
    }

    @Test
    void aDownedCitizenTapsOutAndTheRunGoesOnWithThree() {
        AgentKpqSession session = run.citizenSession(AgentKpqSession.Phase.STAGE_2, lia, mira, pip, tobi);
        AgentKpqCoordinator.tick(session, 2_000L);
        when(tobi.getHp()).thenReturn(0);
        AgentKpqCoordinator.tick(session, 3_000L);
        assertEquals(AgentKpqSession.Phase.STAGE_2, session.phase());
        assertTrue(session.member(4).down());

        when(tobi.getHp()).thenReturn(250);
        run.outside(tobi, AgentKpqDefinition.EXIT_MAP);
        AgentKpqCoordinator.tick(session, 8_000L);
        assertEquals(List.of(new AgentPartyQuestHandback.TapOut(
                "kpq", session.sessionId(), 4, AgentKpqDefinition.EXIT_MAP, 8_000L)), tapOuts);

        run.leaveParty(tobi);
        AgentKpqCoordinator.tick(session, 9_000L);

        assertEquals(3, session.memberCount());
        assertNull(AgentKpqSessionRegistry.forMember(4));
        assertEquals(AgentKpqSession.Phase.STAGE_2, session.phase());
    }

    @Test
    void everyoneOutsideCompletesItWithoutTheWaitingLine() {
        AgentKpqSession session = run.citizenSession(AgentKpqSession.Phase.EXITING, lia, mira, pip);
        for (Character citizen : List.of(lia, mira, pip)) {
            run.outside(citizen, AgentKpqDefinition.EXIT_MAP);
            run.withoutRuntimeEntry(citizen);
        }

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.COMPLETED, session.phase());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), eq(BACK_IN_KERNING)), never());
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(true), anyString(), anyLong(), any()), times(1));
    }

    private static boolean fullRate(Character agent) {
        return AgentRuntimeRegistry.findByAgentCharacterId(agent.getId()).simulationState()
                .fullRateSimulationRequired();
    }
}
