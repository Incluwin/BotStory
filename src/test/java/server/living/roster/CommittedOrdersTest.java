package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.capabilities.dialogue.AgentChatRouteCoordinator;
import server.agents.commands.AgentReplyChannel;
import server.agents.integration.cosmic.CosmicAgentWhisperCommandBridge;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Orders to a companion committed to a run (P6): the run's controller moves it until the run ends, so the Crew window's
 * "Stick with me", "Wait here" and "Hunt here" reach no committed companion; it answers "after the run." instead. A
 * crew-wide order goes to the others, and when every one is committed one of them answers for all. Once the run let
 * them go, orders reach them again.
 */
class CommittedOrdersTest {
    private static final String RUN = "pq:kpq:kpq-1";

    private LifecycleHarness game;
    private Persona hadrik;
    private Persona tobi;
    private CompanionManager manager;
    private MockedStatic<CosmicAgentWhisperCommandBridge> whispers;
    private MockedStatic<AgentChatRouteCoordinator> chat;

    @BeforeEach
    void hadrikAndTobiInKpqWithAnn() {
        game = new LifecycleHarness();
        hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        tobi = game.companion("tobi", "Tobi", 902, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.outWithAnn(tobi);
        game.roster();
        when(game.world.directory().mayControl(any(), anyInt())).thenReturn(true);
        manager = new CompanionManager(game.world);
        whispers = mockStatic(CosmicAgentWhisperCommandBridge.class);
        chat = mockStatic(AgentChatRouteCoordinator.class);
        game.commitments.commit(RUN, ANN, 901);
    }

    @AfterEach
    void close() {
        chat.close();
        whispers.close();
        game.close();
    }

    @Test
    void aCommittedCompanionAnswersAfterTheRunAndIsNotMoved() {
        for (RosterResult result : List.of(manager.followMe(game.ann, hadrik), manager.waitHere(game.ann, hadrik),
                manager.huntHere(game.ann, hadrik))) {
            assertInstanceOf(RosterResult.InEventInstance.class, result);
            assertEquals("Hadrik: after the run.", RosterVoice.line(hadrik, result));
        }

        whispers.verifyNoInteractions();
        assertEquals(List.of(), game.calls);
        verify(game.residents, times(3)).whisper(hadrik, game.ann, "after the run.");
    }

    @Test
    void afterTheRunOrdersReachItAgain() {
        game.commitments.releaseKey(RUN);

        assertEquals("Hadrik sticks with you.", ((RosterResult.Ok) manager.followMe(game.ann, hadrik)).line());

        Character bot = game.bot(hadrik);
        whispers.verify(() -> CosmicAgentWhisperCommandBridge.handleWhisperToAgent(game.ann, bot, "follow me"));
    }

    @Test
    void aCrewOrderGoesToTheOthers() {
        RosterResult result = manager.followAll(game.ann);

        assertEquals("You tell Tobi: follow me.", ((RosterResult.Ok) result).line());
        assertEquals(List.of(902), crewOf(sentChat("follow me")));
        verify(game.residents, never()).whisper(any(), any(), anyString());
    }

    @Test
    void aCrewOrderWithEveryoneCommittedIsAnsweredOnce() {
        game.commitments.commit(RUN, ANN, 902);

        RosterResult result = manager.waitAll(game.ann);

        assertInstanceOf(RosterResult.InEventInstance.class, result);
        chat.verifyNoInteractions();
        verify(game.residents, times(1)).whisper(any(), any(), anyString());
        verify(game.residents).whisper(hadrik, game.ann, "after the run.");
    }

    /** The crew map of the one chat line {@code phrase} Ann said to her companions (party channel). */
    @SuppressWarnings("unchecked")
    private Map<Integer, List<AgentRuntimeEntry>> sentChat(String phrase) {
        ArgumentCaptor<Map<Integer, List<AgentRuntimeEntry>>> crew = ArgumentCaptor.forClass(Map.class);
        chat.verify(() -> AgentChatRouteCoordinator.handleChat(eq(game.ann), eq(phrase), eq(AgentReplyChannel.PARTY),
                crew.capture(), any(), any(), any(), any(), anyInt(), anyInt()));
        return crew.getValue();
    }

    private static List<Integer> crewOf(Map<Integer, List<AgentRuntimeEntry>> crew) {
        assertEquals(List.of(ANN), List.copyOf(crew.keySet()));
        return crew.get(ANN).stream().map(entry -> entry.bot().getId()).toList();
    }
}
