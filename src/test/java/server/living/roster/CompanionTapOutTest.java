package server.living.roster;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback.TapOut;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.PartyGateway;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.quest.QuestLedger;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A companion downed in a four-member run (P6, the tap-out): after its respawn put it on the exit map, the host takes
 * it out of the party. On companion-ops it waits there as a HOLD, saying "sorry, i'll wait outside." in party chat
 * before it leaves, still committed to the run; nothing is written on the thread that asked. With its owner gone it
 * only leaves the party. After the stage-5 clear, or for a session no companion run holds, the host refuses. The clear
 * counts even when a quest fails to count it. A death inside the run is counted for its record.
 */
class CompanionTapOutTest {
    private static final int EXIT = CompanionPqFixture.EXIT;

    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private Persona tobi;
    private CompanionRun run;

    @BeforeEach
    void tobiWentDownInStageTwo() {
        fx.companion("mira", "Mira", 900, 24, Job.HUNTER);
        tobi = fx.companion("tobi", "Tobi", 901, 27, Job.PAGE);
        fx.companion("pip", "Pip", 902, 26, Job.FP_WIZARD);
        fx.ready();
        run = fx.yes();
        game.annEntersInstance(103000802);
        game.bot(tobi).changeMap(game.map(EXIT), new Point(100, 200)); // its respawn put it outside the run
        PartyGateway gateway = AgentPartyGatewayRuntime.party();
        doAnswer(inv -> game.calls.add("party " + inv.getArgument(1))).when(gateway).sendPartyChat(any(), anyString());
        game.calls.clear();
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void itWaitsOutsideOnTheExitMapAndStaysCommitted() {
        List<Runnable> ops = fx.holdCompanionOps();

        assertTrue(fx.lifecycle.tapOut(tapOut(run.sessionId)));

        assertTrue(run.tappingOut.contains(901), "marked before its hold runs");
        assertEquals(List.of(), game.calls);
        CompanionPqFixture.drain(ops);
        assertEquals(List.of("stop Tobi", "party sorry, i'll wait outside.", "leave Tobi"), game.calls,
                "said before it leaves: a bot outside the party cannot party-chat");
        assertEquals(RosterState.HOLD, game.row(tobi).state());
        assertEquals(EXIT, game.row(tobi).holdMapId());
        assertFalse(game.members.contains(901));
        assertEquals(run.key, game.commitments.keyOf(901), "an unload of a session member would fail the run");
        assertTrue(run.tappedOut.contains(901));
    }

    @Test
    void noStoreWriteOnTheCallingThread() {
        List<Runnable> ops = fx.holdCompanionOps();
        clearInvocations(game.store.store);

        fx.lifecycle.tapOut(tapOut(run.sessionId));

        verifyNoInteractions(game.store.store);
        assertEquals(1, ops.size());
    }

    @Test
    void tapOutAfterTheClearIsRefused() {
        when(game.world.quests()).thenReturn(mock(QuestLedger.class));
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(game.world);
            LivingWorldHooks.partyQuestCleared(List.of(game.ann), "KerningPQ"); // clearPQ on the stage-5 clear
        }

        assertFalse(fx.lifecycle.tapOut(tapOut(run.sessionId)));
        assertEquals(RosterState.ACTIVE, game.row(tobi).state());
        assertTrue(game.members.contains(901));
    }

    @Test
    void theClearCountsEvenWhenAQuestFailsToCountIt() {
        QuestLedger quests = mock(QuestLedger.class);
        doThrow(new IllegalStateException("quest step could not be saved")).when(quests)
                .onPartyQuestCleared(any(), anyString());
        when(game.world.quests()).thenReturn(quests);
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(game.world);
            LivingWorldHooks.partyQuestCleared(List.of(game.ann), "KerningPQ");
        }

        assertTrue(run.cleared, "on the companions' records as cleared");
        assertFalse(fx.lifecycle.tapOut(tapOut(run.sessionId)));
    }

    @Test
    void ownerOfflineLeavesThePartyWithoutAHoldRow() {
        game.annOnline = false; // it finishes the run for a friend (decision 8b)

        assertTrue(fx.lifecycle.tapOut(tapOut(run.sessionId)));

        assertEquals(List.of("stop Tobi", "party sorry, i'll wait outside.", "leave Tobi"), game.calls);
        assertFalse(game.members.contains(901));
        assertEquals(RosterState.ACTIVE, game.row(tobi).state(), "it parks when the run ends");
    }

    @Test
    void unknownSessionIsRefused() {
        assertFalse(fx.lifecycle.tapOut(tapOut("kpq-somebody-else")));
        assertFalse(fx.lifecycle.tapOut(new TapOut("kpq", run.sessionId, 950, EXIT, 3_000L)), "not its companion");
        assertEquals(List.of(), game.calls);
    }

    @Test
    void aDeathInsideTheRunIsCountedAndOneOutsideIsNot() {
        when(game.world.contracts()).thenReturn(mock(ContractService.class));
        when(game.world.crewRotation()).thenReturn(mock(CrewRotation.class));
        Character mira = game.bots.get(900);
        when(mira.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        try (MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class)) {
            living.when(LivingWorld::get).thenReturn(game.world);
            LivingWorldHooks.playerDied(mira);
            LivingWorldHooks.playerDied(mira);
            LivingWorldHooks.playerDied(game.bot(tobi)); // outside the event already
        }

        assertEquals(Map.of(900, 2), run.deaths);
    }

    private static TapOut tapOut(String sessionId) {
        return new TapOut("kpq", sessionId, 901, EXIT, 3_000L);
    }
}
