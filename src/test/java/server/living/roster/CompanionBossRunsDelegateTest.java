package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.expedition.boss.AgentBossRunDefinition;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.MemberResult;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Out;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.RunEnded;
import server.agents.capabilities.expedition.boss.AgentBossRunHost.Seat;
import server.agents.capabilities.expedition.boss.AgentBossRunService;
import server.expeditions.Expedition;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;

/**
 * Decision 8b in a boss run (P7 Part A step 12): Ann (Bossfriend) logs off inside the Easy Balrog fight while Ohboy, a
 * human of her party, is still in it. Her companions in the fight see it through for him: the Agent layer's run has
 * each stay with him from now on (AgentBossRunService.handTo), the first says so once in party chat, and they stay hers
 * (rows ACTIVE, still tied to her run key) until the run ends, when they park. Not before the fight is on (before
 * Start, or before the first companion is inside), not for a companion no longer in the fight (down and waiting
 * outside, on its way out, or out), and not when Ohboy is no player of its fight any more: those leave with her (the
 * park). A party quest's companions ("pq:") still go to the party-quest runs (CompanionRunLifecycle.delegate), as
 * LivingWorld installs both by key.
 */
class CompanionBossRunsDelegateTest {

    /** Ann, Ohboy and Ann's Isolde and Thane in the Easy Balrog fight (run 7); Ann leads the party. */
    @Nested
    class InTheEasyBalrogFight {
        private static final long SID = 7L;
        private static final String KEY = "boss:easy_balrog:7:" + ANN;
        private static final int OHBOY = 20;
        private static final int ISOLDE = 901;
        private static final int THANE = 902;

        private final LifecycleHarness game = new LifecycleHarness();
        private final FakeOpen open = new FakeOpen();
        /** The fight's event instance: Ann, Ohboy and her two companions are its players. */
        private final EventInstanceManager fight = mock(EventInstanceManager.class);
        private final Character ohboy = mock(Character.class);
        /** Every key the party-quest runs were asked about. */
        private final List<String> partyQuestAsked = new ArrayList<>();
        private Persona isolde;
        private Persona thane;
        private CompanionBossRuns runs;

        @BeforeEach
        void annOhboyAndHerTwoFightBalrog() {
            isolde = game.companion("isolde", "Isolde", ISOLDE, RosterState.ACTIVE, 105100100);
            thane = game.companion("thane", "Thane", THANE, RosterState.ACTIVE, 105100100);
            game.moveAnn(105100100, 617, 246);
            game.roster();
            game.outWithAnn(isolde);
            game.outWithAnn(thane);
            game.humans.add(OHBOY);
            game.members.add(OHBOY);
            when(ohboy.getId()).thenReturn(OHBOY);
            when(ohboy.getName()).thenReturn("Ohboy");
            when(fight.getPlayerById(ANN)).thenReturn(game.ann);
            when(fight.getPlayerById(OHBOY)).thenReturn(ohboy);
            for (int id : List.of(ISOLDE, THANE)) {
                Character bot = game.bots.get(id);
                when(fight.getPlayerById(id)).thenReturn(bot);
                when(bot.getEventInstance()).thenReturn(fight);
            }
            runs = new CompanionBossRuns(game.world, open, id -> null);
            assertTrue(runs.seat(seat(ISOLDE)) && runs.seat(seat(THANE)), "both signed up for Ann's run");
            open.underway.add(SID); // the leader pressed Start and the companions are in the fight
            open.inside.add(SID + ":" + ISOLDE);
            open.inside.add(SID + ":" + THANE);
            // as LivingWorld.start installs it: boss keys to the boss runs, every other key to the party-quest runs
            game.roster().installInstanceDelegation(CompanionBossRuns.byKey(runs,
                    (companion, heir, key) -> partyQuestAsked.add(key)));
        }

        @AfterEach
        void close() {
            game.close();
        }

        @Test
        void herCompanionsInTheFightSeeItThroughForOhboyAndTheFirstSaysSoOnce() {
            game.roster().onOwnerLogout(game.ann, fight);

            assertEquals(List.of("7 901 -> 20", "7 902 -> 20"), open.handedTo, "the run has both stay with Ohboy");
            assertEquals(List.of("Isolde: i'll see this through with you, Ohboy."), game.partyChat,
                    "once, not per companion");
            assertTrue(game.roster().finishingForFriend(ISOLDE) && game.roster().finishingForFriend(THANE));
            assertEquals(KEY, game.commitments.keyOf(ISOLDE), "still tied to Ann's run");
            assertEquals(KEY, game.commitments.keyOf(THANE));
            assertEquals(RosterState.ACTIVE, game.row(isolde).state(), "still Ann's");
            assertEquals(RosterState.ACTIVE, game.row(thane).state());
            assertEquals(2, game.store.events("companion.handoff").size());
            assertEquals(List.of(), game.calls, "nobody leaves the fight");
            assertEquals(List.of(), partyQuestAsked, "a boss key never reaches the party-quest runs");
        }

        @Test
        void whenTheRunEndsTheyLeaveOhboysPartyAndPark() {
            game.roster().onOwnerLogout(game.ann, fight);
            assertTrue(game.roster().finishingForFriend(ISOLDE) && game.roster().finishingForFriend(THANE));
            assertEquals(List.of(), game.calls, "both still in the fight");
            game.annOnline = false;

            runs.finish(new RunEnded(SID, "easy_balrog", true, true, "members-out", 1_000L, 61_000L, 4, List.of(
                    new MemberResult(ISOLDE, ANN, true, true, 0, Out.EXITED),
                    new MemberResult(THANE, ANN, true, true, 0, Out.EXITED))));

            assertFalse(game.commitments.isCommitted(ISOLDE) || game.commitments.isCommitted(THANE));
            assertEquals(List.of("leave Isolde", "remove Isolde", "leave Thane", "remove Thane"), game.calls);
            assertEquals(RosterState.PARKED, game.row(isolde).state());
            assertEquals(RosterState.PARKED, game.row(thane).state());
            assertFalse(game.roster().finishingForFriend(ISOLDE));
        }

        @Test
        void beforeTheFightIsOnNobodyIsHandedOverAndTheyLeaveWithHer() {
            open.underway.clear(); // the leader has not pressed Start, or nobody is inside yet

            assertFalse(runs.delegate(game.bots.get(ISOLDE), ohboy, KEY));
            game.roster().onOwnerLogout(game.ann, fight);

            assertEquals(List.of(), open.handedTo, "the run is not asked");
            assertTrue(game.partyChat.stream().noneMatch(line -> line.contains("see this through")),
                    game.partyChat.toString());
            assertFalse(game.roster().finishingForFriend(ISOLDE));
            assertFalse(game.commitments.isCommitted(ISOLDE), "the park lets her go");
            assertEquals(RosterState.PARKED, game.row(isolde).state());
            assertEquals(RosterState.PARKED, game.row(thane).state());
        }

        @Test
        void aCompanionNoLongerInTheFightIsNotHandedOver() {
            open.inside.remove(SID + ":" + ISOLDE); // she went down and waits outside (or is on her way out, or out)

            assertFalse(runs.delegate(game.bots.get(ISOLDE), ohboy, KEY));
            assertEquals(List.of(), open.handedTo, "the run is not asked");
            assertEquals(List.of(), game.partyChat);

            assertTrue(runs.delegate(game.bots.get(THANE), ohboy, KEY), "Thane is still in it");
            assertEquals(List.of("7 902 -> 20"), open.handedTo);
            assertEquals(List.of("Thane: i'll see this through with you, Ohboy."), game.partyChat);
        }

        @Test
        void ohboyNoLongerInTheFightIsNoHeir() {
            when(fight.getPlayerById(OHBOY)).thenReturn(null); // he went down and was raised outside, or walked out

            assertFalse(runs.delegate(game.bots.get(ISOLDE), ohboy, KEY));

            when(fight.getPlayerById(OHBOY)).thenReturn(ohboy);
            when(game.bots.get(THANE).getEventInstance()).thenReturn(null);
            assertFalse(runs.delegate(game.bots.get(THANE), ohboy, KEY), "Thane is in no fight");
            assertEquals(List.of(), open.handedTo, "the run is not asked");
            assertEquals(List.of(), game.partyChat);
        }

        @Test
        void aHandoverTheRunRefusesIsNotAgreedAndSaysNothing() {
            open.handToAnswer = false; // she turned to leave between the look and the handover

            assertFalse(runs.delegate(game.bots.get(ISOLDE), ohboy, KEY));
            assertEquals(List.of("7 901 -> 20"), open.handedTo);
            assertEquals(List.of(), game.partyChat, "no line for a handover that did not happen");

            open.handToAnswer = true;
            assertTrue(runs.delegate(game.bots.get(THANE), ohboy, KEY));
            assertEquals(List.of("Thane: i'll see this through with you, Ohboy."), game.partyChat,
                    "the line waits for the first handover that happens");
        }

        @Test
        void aRunWithNoPartOfAnnsKeptHereIsNotAgreed() {
            assertFalse(runs.delegate(game.bots.get(ISOLDE), ohboy, "boss:easy_balrog:8:" + ANN), "another run");
            assertFalse(runs.delegate(game.bots.get(ISOLDE), ohboy, null));

            runs.finish(new RunEnded(SID, "easy_balrog", false, false, "disbanded", 0L, 61_000L, 0, List.of()));
            assertFalse(runs.delegate(game.bots.get(ISOLDE), ohboy, KEY), "the run ended: Ann's part of it is gone");
            assertEquals(List.of(), open.handedTo);
        }

        @Test
        void theLiveViewOfTheAgentLayerHandsOverNoRunItWasNotGiven() {
            assertFalse(CompanionBossRuns.OpenRuns.LIVE.underway(Long.MIN_VALUE));
            assertFalse(CompanionBossRuns.OpenRuns.LIVE.inside(Long.MIN_VALUE, ISOLDE));
            assertFalse(CompanionBossRuns.OpenRuns.LIVE.handTo(Long.MIN_VALUE, ISOLDE, OHBOY));
        }

        private Seat seat(int agentId) {
            return new Seat("easy_balrog", SID, agentId, ANN);
        }
    }

    /**
     * Ann's Mira and Tobi in a KPQ run her friend Ben leads, with the boss runs installed beside the party-quest runs
     * by key (CompanionRunDelegationTest has the party-quest rules themselves).
     */
    @Nested
    class InAPartyQuestRun {
        private static final int BEN = 20;

        private final CompanionPqFixture fx = new CompanionPqFixture();
        private final LifecycleHarness game = fx.game;
        private final Character ben = mock(Character.class);
        /** The run's event instance, which still holds Ann as she logs off. */
        private final EventInstanceManager instance = mock(EventInstanceManager.class);
        private final FakeOpen open = new FakeOpen();

        @BeforeEach
        void benLeadsAnnAndHerTwoIntoKpq() {
            when(ben.getId()).thenReturn(BEN);
            when(ben.getName()).thenReturn("Ben");
            when(ben.getMap()).thenReturn(game.map(KERNING));
            when(ben.getMapId()).thenReturn(KERNING);
            when(ben.getParty()).thenReturn(game.party);
            when(ben.getEventInstance()).thenReturn(instance);
            CompanionPqFixture.stand(ben, 25, Job.PAGE);
            Client client = mock(Client.class);
            when(client.getChannel()).thenReturn(1);
            doAnswer(inv -> {
                ((Runnable) inv.getArgument(0)).run();
                return null;
            }).when(client).runOnIoThread(any());
            when(ben.getClient()).thenReturn(client);
            when(Server.getInstance().getWorld(0).getPlayerStorage().getCharacterById(BEN)).thenReturn(ben);
            game.humans.add(BEN);
            game.members.add(BEN);
            fx.companion("mira", "Mira", 900, 25, Job.HUNTER);
            fx.companion("tobi", "Tobi", 901, 26, Job.PAGE);
            when(game.party.getLeaderId()).thenReturn(BEN);
            fx.ready();
            // as LivingWorld.start installs it
            game.roster().installInstanceDelegation(CompanionBossRuns.byKey(
                    new CompanionBossRuns(game.world, open, id -> null), fx.lifecycle));
            fx.service.start(ben, fx.service.plan(ben, "kpq"));
            CompanionRun run = fx.service.runForSession(fx.doors.session.sessionId());
            for (int id : run.memberIds) {
                when(instance.getPlayerById(id)).thenReturn(id == ANN ? game.ann : id == BEN ? ben : game.bots.get(id));
            }
            when(instance.getLeaderId()).thenReturn(BEN);
        }

        @AfterEach
        void close() {
            fx.close();
        }

        @Test
        void aPartyQuestKeyStillReachesThePartyQuestRuns() {
            assertTrue(game.commitments.keyOf(900).startsWith("pq:kpq:"), game.commitments.keyOf(900));

            game.roster().onOwnerLogout(game.ann, instance);

            assertEquals(List.of("Mira: i'll see this through with you, Ben."), game.partyChat);
            assertTrue(game.roster().finishingForFriend(900) && game.roster().finishingForFriend(901));
            assertEquals(2, game.store.events("companion.handoff").size());
            assertEquals(List.of(), open.handedTo, "the boss runs hand nobody over");
        }
    }

    /** The Agent layer's open boss runs as a test sets them; every handover asked of it is logged. */
    private static final class FakeOpen implements CompanionBossRuns.OpenRuns {
        /** Runs whose fight is on: in the fight or past the clear (FIGHTING, POST_CLEAR). */
        final Set<Long> underway = new HashSet<>();
        /** "session:agent" for each member in the fight (INSIDE). */
        final Set<String> inside = new HashSet<>();
        /** "session agent -> heir" for every handover asked, in order. */
        final List<String> handedTo = new ArrayList<>();
        /** What the run answers a handover (false: the member is no longer in the fight by then). */
        boolean handToAnswer = true;

        @Override
        public boolean underway(long sessionId) {
            return underway.contains(sessionId);
        }

        @Override
        public boolean inside(long sessionId, int agentId) {
            return inside.contains(sessionId + ":" + agentId);
        }

        @Override
        public boolean handTo(long sessionId, int agentId, int heirId) {
            handedTo.add(sessionId + " " + agentId + " -> " + heirId);
            return handToAnswer;
        }

        // sign-up, the offer and the fight's server authority are not under test here

        @Override
        public boolean open(long sessionId) {
            return underway.contains(sessionId);
        }

        @Override
        public boolean waiting(long sessionId, int enlistedBy) {
            return false;
        }

        @Override
        public boolean registering(long sessionId) {
            return false;
        }

        @Override
        public boolean signedUpIn(EventInstanceManager event) {
            return false;
        }

        @Override
        public boolean heldByAnother(Expedition expedition, int ownerId) {
            return false;
        }

        @Override
        public boolean inRun(int agentId) {
            return false;
        }

        @Override
        public int waitingOrdinal(int agentId, int anchorId) {
            return -1;
        }

        @Override
        public AgentBossRunService.Enlisted enlist(Character owner, AgentBossRunDefinition definition,
                                                   Expedition expedition, List<Integer> agentIds, long nowMs) {
            throw new UnsupportedOperationException("no enlist here");
        }
    }
}
