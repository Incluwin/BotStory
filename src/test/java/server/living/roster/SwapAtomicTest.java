package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.party.JoinOutcome;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.LifecycleHarness.portalX;

/**
 * The atomic swap (docs/BOT_MANAGEMENT_PLAN.md flow 1, "RosterService"): with a full party the incoming companion is
 * loaded at its camp first, then under one cause token the outgoing one leaves and the incoming one joins on the seat
 * it gave up, so the party never passes 6 and never shows a hole; a failed join rolls all of it back.
 */
class SwapAtomicTest {
    private final LifecycleHarness game = new LifecycleHarness();
    private final List<Persona> out = new ArrayList<>();
    private Persona tobi;
    private Persona mira;

    @BeforeEach
    void fullParty() {
        String[] names = {"Tobi", "Hadrik", "Jinx", "Sadie", "Vex"};
        for (int i = 0; i < names.length; i++) {
            Persona persona = game.companion(names[i].toLowerCase(), names[i], 901 + i, RosterState.ACTIVE, LITH);
            game.outWithAnn(persona);
            out.add(persona);
        }
        tobi = out.get(0);
        mira = game.companion("mira", "Mira", 950, RosterState.BENCHED, LITH);
        game.roster();
        assertEquals(6, game.members.size());
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void theIncomingOneIsLoadedFirstThenTheyTradePlacesUnderOneCauseWithNoHole() {
        RosterResult result = game.roster().swap(game.ann, tobi, mira);

        assertTrue(result.ok(), RosterVoice.line(mira, result));
        assertEquals(List.of("spawn Mira at " + LITH + " " + portalX(LITH) + ",200", "leave Tobi", "join Mira"),
                game.calls.subList(0, 3), "loaded at camp before anyone leaves; the leave and the join are back to back");
        assertTrue(game.partySizes.stream().allMatch(size -> size <= CompanionSlots.PARTY_CAP), "never past 6: " + game.partySizes);
        assertEquals(List.of(5, 6), game.partySizes, "the one step down is the leave the join fills at once");
        assertEquals(List.of(RosterService.CAUSE, RosterService.CAUSE), game.causes.subList(0, 2));
        // the outgoing one walks out to its duty; the incoming one travels in the party, not following yet
        assertTrue(game.calls.contains("walk Tobi to " + portalX(HENESYS_FIELD) + ",200"));
        assertFalse(game.calls.contains("follow Mira"));
        assertEquals(RosterState.BENCHED, game.row(tobi).state());
        assertEquals(RosterState.TRAVELLING, game.row(mira).state());
        assertTrue(game.members.contains(950));
        assertFalse(game.members.contains(901));
        assertNull(game.slots.reservation("mira"), "a member now");
        assertNull(game.slots.reservation("tobi"));
        verify(game.residents).whisper(mira, game.ann, "on my way. ~60 s.");
        verify(game.budget).admitReplacing(eq(CompanionBudget.Kind.COMPANION), any(Character.class));
        verify(game.budget, never()).admit(eq(CompanionBudget.Kind.COMPANION), any(Character.class));

        game.runTimers(); // Tobi reaches his portal and goes; Mira's ETA ends

        assertTrue(game.calls.contains("remove Tobi"));
        assertEquals(RosterState.ACTIVE, game.row(mira).state());
        assertEquals("follow Mira", game.calls.get(game.calls.size() - 1));
        assertEquals(6, game.members.size());
    }

    @Test
    void aFailedJoinRollsBackAndTheOutgoingOneStays() {
        game.nextJoin = JoinOutcome.FAILED;

        RosterResult result = game.roster().swap(game.ann, tobi, mira);

        assertInstanceOf(RosterResult.Failed.class, result);
        assertEquals(List.of("spawn Mira at " + LITH + " " + portalX(LITH) + ",200", "leave Tobi", "join Mira FAILED",
                "remove Mira", "join Tobi"), game.calls);
        assertEquals(6, game.members.size());
        assertTrue(game.members.contains(901), "Tobi took his seat back");
        assertFalse(game.members.contains(950));
        assertTrue(game.partySizes.stream().allMatch(size -> size <= CompanionSlots.PARTY_CAP));
        assertEquals(RosterState.ACTIVE, game.row(tobi).state());
        assertEquals(RosterState.BENCHED, game.row(mira).state());
        assertNull(game.slots.reservation("mira"));
        assertNull(game.slots.reservation("tobi"));
        assertNull(game.leases.holder(950), "nothing incoming stays loaded");
        assertTrue(game.timers.isEmpty(), "no walk-out and no arrival");
    }

    @Test
    void aSwapIsRefusedAfterTheRunWhileEitherIsCommitted() {
        game.commitments.commit("kpq-1", ANN, 901);

        RosterResult result = game.roster().swap(game.ann, tobi, mira);

        assertInstanceOf(RosterResult.InEventInstance.class, result);
        assertEquals("Tobi: after the run.", RosterVoice.line(tobi, result));
        verify(game.residents).whisper(tobi, game.ann, RosterService.AFTER_THE_RUN_LINE);
        assertEquals(List.of(), game.calls);
        assertEquals(RosterState.ACTIVE, game.row(tobi).state());
    }

    @Test
    void anIncomingOneCampingOnTheOwnersMapWalksInFromThePortalAtOnce() {
        game.moveAnn(LITH, 1000, 200);
        out.forEach(game::placeWithAnn); // they stand with Ann in Lith Harbor now

        RosterResult result = game.roster().swap(game.ann, tobi, mira);

        assertTrue(result.ok());
        assertEquals(List.of("spawn Mira at " + LITH + " " + portalX(LITH) + ",200", "leave Tobi", "join Mira"),
                game.calls.subList(0, 3));
        assertTrue(game.calls.contains("walk Tobi to " + portalX(LITH) + ",200"));
        assertEquals("follow Mira", game.calls.get(game.calls.size() - 1), "it walks in from the portal at once");
        assertEquals(RosterState.ACTIVE, game.row(mira).state());
        assertTrue(game.partySizes.stream().allMatch(size -> size <= CompanionSlots.PARTY_CAP));
    }
}
