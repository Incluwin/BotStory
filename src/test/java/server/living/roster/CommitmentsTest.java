package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Commitments (docs/BOT_MANAGEMENT_PLAN.md, "Core concepts"): companions tied to one event instance or expedition key.
 * While one is committed the roster answers "after the run" to a bench, a swap or an errand, and the party sync's
 * reconciliation of it waits; when the commitment ends every listener hears which members were let go.
 */
class CommitmentsTest {
    private final Commitments commitments = new Commitments();

    @Test
    void aCharacterIsCommittedToOneKeyAtATime() {
        assertTrue(commitments.commit("kpq-1", ANN, 900));
        assertTrue(commitments.commit("kpq-1", ANN, 900), "the same key again is kept");
        assertFalse(commitments.commit("zakum-2", ANN, 900), "another run cannot take it");
        assertFalse(commitments.commit(null, ANN, 901));
        assertFalse(commitments.commit("kpq-1", ANN, 0));

        assertTrue(commitments.isCommitted(900));
        assertFalse(commitments.isCommitted(901));
        assertEquals("kpq-1", commitments.keyOf(900));
        assertNull(commitments.keyOf(901));
        assertEquals(List.of(new Commitments.Member(900, ANN, "kpq-1")), commitments.members("kpq-1"));
    }

    @Test
    void aMemberLeavingTheRunEndsOnlyItsCommitment() {
        List<Commitments.Ended> heard = new ArrayList<>();
        commitments.addListener(heard::add);
        commitments.commit("kpq-1", ANN, 900);
        commitments.commit("kpq-1", ANN, 901);

        assertTrue(commitments.releaseMember(900));
        assertFalse(commitments.releaseMember(900), "released once");

        assertFalse(commitments.isCommitted(900));
        assertTrue(commitments.isCommitted(901));
        assertEquals(List.of(new Commitments.Ended("kpq-1", List.of(new Commitments.Member(900, ANN, "kpq-1")))), heard);
    }

    @Test
    void theRunEndingEndsEveryCommitmentToItsKey() {
        List<Commitments.Ended> heard = new ArrayList<>();
        commitments.addListener(heard::add);
        commitments.commit("kpq-1", ANN, 900);
        commitments.commit("kpq-1", 20, 950);
        commitments.commit("zakum-2", ANN, 901);

        List<Commitments.Member> released = commitments.releaseKey("kpq-1");

        assertEquals(2, released.size());
        assertFalse(commitments.isCommitted(900));
        assertFalse(commitments.isCommitted(950));
        assertTrue(commitments.isCommitted(901));
        assertEquals(1, heard.size());
        assertEquals("kpq-1", heard.get(0).key());
        assertEquals(2, heard.get(0).members().size());
        assertTrue(commitments.releaseKey("kpq-1").isEmpty());
        assertEquals(1, heard.size(), "nothing to hear when nothing was released");
    }

    @Test
    void oneFailingListenerDoesNotStopTheOthers() {
        List<String> heard = new ArrayList<>();
        commitments.addListener(ended -> {
            throw new IllegalStateException("boom");
        });
        commitments.addListener(ended -> heard.add(ended.key()));
        commitments.commit("kpq-1", ANN, 900);

        commitments.releaseMember(900);

        assertEquals(List.of("kpq-1"), heard);
    }

    @Test
    void theRosterAnswersAfterTheRunToABenchSwapOrErrandWhileCommitted() {
        try (LifecycleHarness game = new LifecycleHarness()) {
            Persona olwen = game.companion("olwen", "Olwen", 900, RosterState.ACTIVE, LITH);
            Persona mira = game.companion("mira", "Mira", 950, RosterState.BENCHED, LITH);
            game.outWithAnn(olwen);
            game.commitments.commit("kpq-1", ANN, 900);

            assertInstanceOf(RosterResult.InEventInstance.class, game.roster().bench(game.ann, olwen));
            assertInstanceOf(RosterResult.InEventInstance.class, game.roster().swap(game.ann, olwen, mira));
            assertInstanceOf(RosterResult.InEventInstance.class,
                    game.roster().delegate(game.ann, olwen, Assignment.FARM_MESO, 0, 0, LITH));

            assertEquals(RosterState.ACTIVE, game.row(olwen).state());
            assertEquals(List.of(), game.calls);
            assertEquals("Olwen: after the run.", RosterVoice.line(olwen, new RosterResult.InEventInstance()));
            verify(game.residents, org.mockito.Mockito.times(3)).whisper(olwen, game.ann, RosterService.AFTER_THE_RUN_LINE);

            game.commitments.releaseMember(900); // out of the run: a bench goes through again
            assertTrue(game.roster().bench(game.ann, olwen).ok());
            assertEquals(RosterState.BENCHED, game.row(olwen).state());
        }
    }

    @Test
    void thePartySyncLeavesACommittedCompanionAlone() {
        LivingWorld world = mock(LivingWorld.class);
        RosterService roster = mock(RosterService.class);
        when(world.commitments()).thenReturn(commitments);
        when(world.roster()).thenReturn(roster);
        LiveCrew crew = new LiveCrew(world);
        commitments.commit("kpq-1", ANN, 900);

        assertTrue(crew.committed(900), "a commitment, whatever the character's own event instance says");
        assertFalse(crew.committed(901));

        Character inARun = mock(Character.class);
        when(inARun.getEventInstance()).thenReturn(mock(scripting.event.EventInstanceManager.class));
        when(roster.onlineCharacter(ANN)).thenReturn(inARun);
        assertTrue(crew.committed(ANN), "a human in an event instance still counts, as before");
    }
}
