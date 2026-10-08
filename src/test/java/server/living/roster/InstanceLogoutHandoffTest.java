package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.expeditions.Expedition;
import server.integration.AgentPresence;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * Decision 8b, the instance logout handoff (docs/BOT_MANAGEMENT_PLAN.md flow 3 step 11, flow 7 step 4), as a seam
 * with a delegation that always agrees (CompanionRunDelegationTest has the companion runs' own). Ann logs off inside
 * a run her friend Ben is still in: her companions committed to it finish it for Ben (their rows, their owner and their
 * commitments stay), and once the run ends for one (its commitment ends) it leaves Ben's party and parks (the first says
 * the goodbye nobody said at the logout), or walks back in to Ann when she is online again. With no human of her party
 * left in the run, no delegation installed, or a
 * companion not committed to the run, they leave with her under stock eim rules and their commitments end. A bench
 * ally in a boss run (P7 Part B) is handed further: to her party's heir, else to the expedition's leader, else to any
 * human still in the run ({@link InstanceLogoutHandoff#anyHuman}); never to her or an Agent.
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
        AgentPresence.install(null);
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
    void withEveryoneFinishingTheRunTheFirstToLeaveItSaysTheGoodbye() {
        game.roster().onOwnerLogout(game.ann, instance);
        assertEquals(List.of(), game.partyChat, "all of them stay in the run: nobody has said it yet");
        game.annOnline = false;

        game.commitments.releaseKey(RUN);

        assertEquals(List.of("Hadrik: gn!"), game.partyChat, "one goodbye, as they leave Ben's party");
    }

    @Test
    void aGoodbyeSaidAtTheLogoutIsNotSaidAgainAfterTheRun() {
        game.commitments.releaseMember(902); // Jinx is not in the run: it logs off with her and says it

        game.roster().onOwnerLogout(game.ann, instance);
        game.annOnline = false;
        game.commitments.releaseKey(RUN);

        assertEquals(List.of("Jinx: gn!"), game.partyChat);
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

    // ---- a bench ally's heir (P7 Part B) -------------------------------------------------------

    @Test
    void anAllysHeirIsHerPartysHeirThenTheExpeditionsLeaderThenTheFirstHumanInTheRun() {
        Character hadrikBot = game.bot(hadrik);
        AgentPresence.install(chr -> chr.getId() == 901 || chr.getId() == 902);
        when(ben.isLoggedin()).thenReturn(true);
        Character cid = human(21, "Cid"); // leads the expedition; in no party of Ann's
        Character dot = human(22, "Dot"); // only in the run
        Expedition expedition = mock(Expedition.class);
        when(expedition.getLeader()).thenReturn(cid);
        when(instance.getExpedition()).thenReturn(expedition);
        when(instance.getPlayers()).thenReturn(List.of(game.ann, hadrikBot, dot, cid, ben));

        assertEquals(ben, InstanceLogoutHandoff.anyHuman(game.ann, instance), "her party's heir first");

        game.members.remove((Integer) BEN); // Ben is still in the run, no longer in her party
        assertEquals(cid, InstanceLogoutHandoff.anyHuman(game.ann, instance), "then the expedition's leader");
        assertNull(InstanceLogoutHandoff.heir(game.ann, instance), "a party companion's heir is still her party's");

        when(instance.getPlayerById(21)).thenReturn(null); // Cid walked out of the run
        when(instance.getPlayers()).thenReturn(List.of(game.ann, hadrikBot, dot, ben));
        assertEquals(dot, InstanceLogoutHandoff.anyHuman(game.ann, instance), "then the first human in it");
    }

    @Test
    void anAllysHeirIsNeverTheOwnerNorAnAgentAndNoneWithNoOtherHumanInTheRun() {
        Character hadrikBot = game.bot(hadrik);
        AgentPresence.install(chr -> chr.getId() == 901 || chr.getId() == 902);
        game.members.remove((Integer) BEN);
        when(instance.getPlayerById(BEN)).thenReturn(null);
        when(instance.getPlayerById(ANN)).thenReturn(game.ann); // read before her disconnect took her out
        when(game.ann.isLoggedin()).thenReturn(true);
        when(hadrikBot.isLoggedin()).thenReturn(true);
        Expedition expedition = mock(Expedition.class);
        when(expedition.getLeader()).thenReturn(game.ann); // she leads it
        when(instance.getExpedition()).thenReturn(expedition);
        when(instance.getPlayers()).thenReturn(List.of(game.ann, hadrikBot));

        assertNull(InstanceLogoutHandoff.anyHuman(game.ann, instance), "only her and an Agent");

        when(expedition.getLeader()).thenReturn(hadrikBot); // a bot leads it (a GM's lobby)
        assertNull(InstanceLogoutHandoff.anyHuman(game.ann, instance));

        Character eve = human(23, "Eve");
        when(eve.isLoggedin()).thenReturn(false); // logging off herself
        when(instance.getPlayers()).thenReturn(List.of(game.ann, hadrikBot, eve));
        assertNull(InstanceLogoutHandoff.anyHuman(game.ann, instance));

        when(instance.getExpedition()).thenReturn(null); // a run of no expedition
        assertNull(InstanceLogoutHandoff.anyHuman(game.ann, instance));
        assertNull(InstanceLogoutHandoff.anyHuman(game.ann, null), "in no run");
    }

    /** A human player in the run, logged in. */
    private Character human(int id, String name) {
        Character human = mock(Character.class);
        when(human.getId()).thenReturn(id);
        when(human.getName()).thenReturn(name);
        when(human.isLoggedin()).thenReturn(true);
        when(instance.getPlayerById(id)).thenReturn(human);
        return human;
    }
}
