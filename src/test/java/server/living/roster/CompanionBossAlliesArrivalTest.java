package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.boss.AgentBossRunMember;
import server.living.roster.CompanionBossAllies.State;
import server.living.roster.CompanionBossAllies.Trip;
import server.living.store.LivingStore.RosterEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static server.living.roster.BossAllyHarness.KEY;
import static server.living.roster.BossAllyHarness.ROOM;
import static server.living.roster.BossAllyHarness.SLEEPYWOOD_CAMP;
import static server.living.roster.BossAllyHarness.isLine;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * A bench ally's arrival (P7 Part B step 7, CompanionBossAllies.arrive): at its ETA it loads at top00 of the
 * expedition's own copy of Mu Young's room, on the expedition's channel, registers as an idle Agent that leads itself,
 * comes back whole, drinks by its own settings and takes its banked EXP and stall stock back (a live slice's order),
 * says it is here and, once, that it is low on potions, asking its crew for some. A stale timer does nothing; a member
 * no longer waiting to sign up (or a run past its sign-ups) turns back with a whisper, and one loaded elsewhere cannot
 * come; both let the lease, the commitment and the trip go.
 */
class CompanionBossAlliesArrivalTest {
    private static final int ORRIN = 911;

    private final BossAllyHarness h = new BossAllyHarness();
    private Trip trip;

    @BeforeEach
    void orrinIsOnHisWayDownFromUnderSleepywood() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.game.roster();
        h.allies.send(h.game.ann, "easy_balrog", List.of("orrin"), h.now);
        trip = h.allies.trip("orrin");
        assertEquals(State.TRAVELLING, trip.state);
        assertEquals(1, h.game.timers.size(), "its arrival");
    }

    @AfterEach
    void close() {
        h.close();
    }

    @Test
    void atItsEtaItLoadsAtTop00OnTheExpeditionsChannelAsAnIdleAgentAndSettlesIn() {
        h.game.runDueTimers();

        assertEquals(List.of("load " + ORRIN + " world 0 ch" + BossAllyHarness.CHANNEL + " at " + ROOM + " -65,-264"),
                h.ports.loads);
        assertTrue(h.game.calls.contains("register idle Orrin"), h.game.calls.toString());
        Character orrin = h.game.bots.get(ORRIN);
        verify(orrin).healHpMp();
        assertEquals(new CompanionSettingsApplier.Bound(ANN, "orrin"), h.game.roster().settings().boundTo(ORRIN),
                "it drinks by its own potion percentages");
        verify(h.game.world.bench()).applyAccrued(argThat((RosterEntry row) -> row.personaId().equals("orrin")),
                eq(orrin));
        verify(h.game.world.benchDuty()).reclaimStall(ANN, "orrin", orrin);
        assertEquals(State.HERE, trip.state);
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.EXPEDITION, ANN), h.game.leases.holder(ORRIN));
        assertEquals(KEY, h.game.commitments.keyOf(ORRIN));
    }

    @Test
    void itSaysItIsHereAndOnceThatItIsLowOnPotions() {
        h.ports.shortOfPotions.add(ORRIN);
        int generation = trip.generation;

        h.game.runDueTimers();
        h.allies.arrive(trip, generation); // the same timer again: it is here already

        assertEquals(2, h.mapChat.size(), h.mapChat.toString());
        assertTrue(isLine(BossRunLines.ALLY_HERE, null, h.mapChat.get(0).substring("Orrin: ".length())),
                h.mapChat.toString());
        assertTrue(isLine(BossRunLines.ALLY_LOW_POTS, null, h.mapChat.get(1).substring("Orrin: ".length())),
                h.mapChat.toString());
        assertEquals(1, h.ports.loads.size());
        // 2026-10-15 (P20 run 3): it asks its crew, and the potion share asks its owner's companions in the room
        assertEquals(List.of("crew Orrin easy_balrog"), h.ports.crewAsks);
    }

    @Test
    void anAllyWithEnoughPotionsOnlySaysItIsHere() {
        h.game.runDueTimers();

        assertEquals(1, h.mapChat.size(), h.mapChat.toString());
        assertTrue(isLine(BossRunLines.ALLY_HERE, null, h.mapChat.get(0).substring("Orrin: ".length())));
        assertEquals(List.of(), h.ports.crewAsks, "nothing to ask for");
    }

    @Test
    void aStaleTimerDoesNothing() {
        int generation = trip.generation;

        h.allies.arrive(trip, generation - 1);
        h.allies.arrive(trip, generation + 1);

        assertEquals(List.of(), h.ports.loads);
        assertEquals(State.TRAVELLING, trip.state);
        assertEquals(1, h.said("Orrin").size(), "only its ETA: " + h.whispers);
        assertEquals(List.of(), h.mapChat);
    }

    @Test
    void aMemberNoLongerWaitingToSignUpTurnsBackAndLetsItsLeaseGo() {
        h.open.phases.put(ORRIN, AgentBossRunMember.Phase.REFUSED); // refused NOT_HERE at the deadline, say

        h.game.runDueTimers();

        assertTurnedBack();
    }

    @Test
    void aRunNoLongerTakingSignUpsTurnsItBack() {
        h.open.registering = false; // the leader pressed Start

        h.game.runDueTimers();

        assertTurnedBack();
    }

    @Test
    void anAllyLoadedElsewhereCannotComeAndLetsEverythingGo() {
        h.ports.elsewhere.add(ORRIN);

        h.game.runDueTimers();

        assertEquals(List.of(), h.ports.loads);
        assertLetGo();
        assertEquals(2, h.said("Orrin").size(), h.whispers.toString());
        assertTrue(isLine(BossRunLines.ALLY_CANT_COME, null, h.said("Orrin").get(1)), h.whispers.toString());
    }

    @Test
    void aLoadThatFailsLetsEverythingGo() {
        h.ports.loadFails = true;

        h.game.runDueTimers();

        assertEquals(1, h.ports.loads.size());
        assertLetGo();
        assertTrue(isLine(BossRunLines.ALLY_CANT_COME, null, h.said("Orrin").get(1)), h.whispers.toString());
        verify(h.game.bots.get(ORRIN), never()).healHpMp();
    }

    private void assertTurnedBack() {
        assertEquals(List.of(), h.ports.loads);
        assertLetGo();
        assertEquals(2, h.said("Orrin").size(), h.whispers.toString());
        assertTrue(isLine(BossRunLines.ALLY_TURNED_BACK, null, h.said("Orrin").get(1)), h.whispers.toString());
    }

    private void assertLetGo() {
        assertNull(h.allies.trip("orrin"));
        assertNull(h.game.leases.holder(ORRIN));
        assertNull(h.game.commitments.keyOf(ORRIN));
        assertTrue(h.open.calls.contains("withdraw " + ORRIN), h.open.calls.toString());
        assertTrue(h.allies.picker(h.game.ann, "easy_balrog", h.now).row("orrin").offered(), "it can be called again");
    }
}
