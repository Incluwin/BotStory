package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Decision 8b, the instance logout handoff (docs/BOT_MANAGEMENT_PLAN.md flow 3 step 11, flow 7 step 4), as a seam:
 * companions cannot enter instances before P6, whose PQ service installs the runtime-leader move. Ann logs off inside
 * a run her friend Ben is still in: her companions committed to it finish it for Ben (their rows, their owner and their
 * commitments stay), and once the run ends for one (its commitment ends) it leaves Ben's party and parks, or walks back
 * in to Ann when she is online again. With no human of her party left in the run, no delegation installed, or a
 * companion not committed to the run, they leave with her under stock eim rules and their commitments end.
 */
class InstanceLogoutHandoffTest {
    private static final int BEN = 20;
    private static final String RUN = "kpq:1";

    private final LifecycleHarness game = new LifecycleHarness();
    private final EventInstanceManager instance = mock(EventInstanceManager.class);
    private final Character ben = mock(Character.class);
    private final List<String> delegated = new ArrayList<>();
    private Persona hadrik;
    private Persona jinx;

    @BeforeEach
    void inARunWithBen() {
        hadrik = game.companion("hadrik", "Hadrik", 901, RosterState.ACTIVE, LITH);
        jinx = game.companion("jinx", "Jinx", 902, RosterState.ACTIVE, LITH);
        game.outWithAnn(hadrik);
        game.outWithAnn(jinx);
        game.humans.add(BEN);
        game.members.add(BEN);
        when(ben.getId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        when(instance.getPlayerById(BEN)).thenReturn(ben);
        Character hadrikBot = game.bot(hadrik);
        Character jinxBot = game.bot(jinx);
        when(instance.getPlayerById(901)).thenReturn(hadrikBot);
        when(instance.getPlayerById(902)).thenReturn(jinxBot);
        game.commitments.commit(RUN, ANN, 901);
        game.commitments.commit(RUN, ANN, 902);
        game.roster().installInstanceDelegation((companion, human, key) ->
                delegated.add(companion.getName() + " -> " + human.getName() + " for " + key));
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void herCompanionsFinishTheRunForTheFriendWhoRemains() {
        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(List.of("Hadrik -> Ben for " + RUN, "Jinx -> Ben for " + RUN), delegated);
        assertEquals(List.of(), game.calls, "nobody leaves the run");
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state(), "the roster owner and the row stay");
        assertTrue(game.commitments.isCommitted(901), "the commitment stays");
        assertTrue(game.roster().inFlight(ANN, "hadrik"), "the auditor leaves it alone meanwhile");
        assertEquals(2, game.store.events("companion.handoff").size());
    }

    @Test
    void whenTheRunEndsTheyLeaveThatPartyAndPark() {
        game.roster().onOwnerLogout(game.ann, instance);
        game.annOnline = false;

        game.commitments.releaseKey(RUN);

        assertEquals(List.of("leave Hadrik", "remove Hadrik", "leave Jinx", "remove Jinx"), game.calls);
        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        assertEquals(RosterState.PARKED, game.row(jinx).state());
        assertFalse(game.roster().inFlight(ANN, "hadrik"));
        assertTrue(game.timers.isEmpty(), "with Ann away nobody walks back in");
    }

    @Test
    void oneThatLeavesTheRunParksWhileTheOtherFinishesIt() {
        game.roster().onOwnerLogout(game.ann, instance);
        game.annOnline = false;

        game.commitments.releaseMember(901);

        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        assertEquals(RosterState.ACTIVE, game.row(jinx).state());
        assertTrue(game.commitments.isCommitted(902));
    }

    @Test
    void backOnlineBeforeTheRunEndsTheyWalkBackInToHerAfterIt() {
        game.roster().onOwnerLogout(game.ann, instance);
        game.roster().onOwnerLogin(game.ann); // back while the run goes on: they are still Ben's for it
        assertTrue(game.timers.isEmpty(), "nothing is pulled out of the run");

        game.commitments.releaseKey(RUN);
        game.calls.clear();
        game.runDueTimers();

        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertTrue(game.calls.contains("join Hadrik"), String.valueOf(game.calls));
    }

    @Test
    void withNoHumanOfHerPartyLeftInTheRunTheyLeaveWithHerAndTheirCommitmentsEnd() {
        game.members.remove((Integer) BEN); // her own party now
        when(instance.getPlayerById(BEN)).thenReturn(null);

        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(List.of(), delegated);
        assertEquals(List.of("remove Hadrik", "remove Jinx"), game.calls, "they log off with her, offline members");
        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        assertFalse(game.commitments.isCommitted(901));
        assertFalse(game.commitments.isCommitted(902));
    }

    @Test
    void aFriendNoLongerInTheRunIsNoHeir() {
        when(instance.getPlayerById(BEN)).thenReturn(null);

        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(List.of(), delegated);
        assertEquals(List.of("leave Hadrik", "remove Hadrik", "leave Jinx", "remove Jinx"), game.calls,
                "a party with Ben: they leave it");
        assertFalse(game.commitments.isCommitted(901));
    }

    @Test
    void withoutAnInstalledDelegationTheyLeaveUnderStockRules() {
        game.roster().installInstanceDelegation(null); // before P6

        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(RosterState.PARKED, game.row(hadrik).state());
        assertEquals(RosterState.PARKED, game.row(jinx).state());
        assertFalse(game.commitments.isCommitted(901));
    }

    @Test
    void aCompanionNotCommittedToTheRunLeaves() {
        game.commitments.releaseMember(902);

        game.roster().onOwnerLogout(game.ann, instance);

        assertEquals(List.of("Hadrik -> Ben for " + RUN), delegated);
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertEquals(RosterState.PARKED, game.row(jinx).state());
        assertEquals(List.of("Jinx: gn!"), game.partyChat);
    }

    @Test
    void theHeirIsTheLeaderWhenTheLeaderIsAHumanInTheRun() {
        Character cid = mock(Character.class);
        when(cid.getId()).thenReturn(21);
        game.humans.add(21);
        game.members.add(21);
        when(instance.getPlayerById(21)).thenReturn(cid);
        when(game.party.getLeaderId()).thenReturn(21);

        assertEquals(cid, InstanceLogoutHandoff.heir(game.ann, instance));
        when(game.party.getLeaderId()).thenReturn(ANN); // Ann leads: the first other human
        assertEquals(ben, InstanceLogoutHandoff.heir(game.ann, instance));
    }
}
