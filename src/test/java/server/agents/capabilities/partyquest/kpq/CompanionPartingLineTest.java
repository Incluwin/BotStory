package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * A companion run that ends early is explained by a companion in its own words; the engine's reason, with its ids
 * and phrasing, goes to the log and the run's failure only. A stock run still says the reason.
 */
class CompanionPartingLineTest {
    private static final long TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.LOCAL_RECOVERY_TIMEOUT_MS");
    private static final long PHASE_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.KPQ_PHASE_TIMEOUT_MS");

    private KpqRunFixture run;
    private Character ben;
    private Character ann;
    private Character mira;
    private Character pip;

    @BeforeEach
    void setUp() {
        run = new KpqRunFixture();
        ben = run.human(1, "Ben");
        ann = run.human(2, "Ann");
        mira = run.companion(3, "Mira");
        pip = run.companion(4, "Pip");
    }

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void eachKindIsSaidCasuallyAndAboutTheSpeakerWhenItWentDown() {
        AgentKpqCoordinator.Parting tobi = new AgentKpqCoordinator.Parting(
                AgentKpqCoordinator.Parting.Kind.WENT_DOWN, 4, "Tobi");
        assertEquals("Tobi went down, and we need three for this. let's head out.", tobi.line(3, false));
        assertEquals("sorry, i went down. we need three for this, so let's head out.", tobi.line(4, false));
        assertEquals("Tobi went down, so we can't finish this one. let's head out.", new AgentKpqCoordinator.Parting(
                AgentKpqCoordinator.Parting.Kind.DEFEATED, 4, "Tobi").line(3, false));
        assertEquals("Ann left, and we need three for this. let's head out.", new AgentKpqCoordinator.Parting(
                AgentKpqCoordinator.Parting.Kind.LEFT, 2, "Ann").line(3, false));
        // A name the party no longer knows is left out rather than replaced by an id.
        assertEquals("we can't finish this one. let's head out.", new AgentKpqCoordinator.Parting(
                AgentKpqCoordinator.Parting.Kind.GONE, 2, null).line(3, false));
        assertEquals("looks like this run's over. let's head back.",
                AgentKpqCoordinator.Parting.of(AgentKpqCoordinator.Parting.Kind.OVER).line(3, false));
    }

    @Test
    void aStockRunStillSaysTheReason() {
        assertEquals("KPQ stopped: Party member 2 disconnected or left the channel.",
                AgentKpqCoordinator.failureLine(false, "Party member 2 disconnected or left the channel",
                        AgentKpqCoordinator.Parting.GENERIC, 3, false));
        assertEquals("KPQ stopped: The event leader is no longer active.",
                AgentKpqCoordinator.failureLine(false, "The event leader is no longer active",
                        AgentKpqCoordinator.Parting.GENERIC, 3, true));
        assertEquals("we can't finish this one. let's head out.",
                AgentKpqCoordinator.failureLine(true, "Stage 2 formation movement exhausted for member 4",
                        AgentKpqCoordinator.Parting.GENERIC, 3, false));
    }

    @Test
    void pastTheClearEachKindIsAWrapUpNotALoss() {
        AgentKpqCoordinator.Parting tobi = new AgentKpqCoordinator.Parting(
                AgentKpqCoordinator.Parting.Kind.DEFEATED, 4, "Tobi");
        assertEquals("Tobi went down. let's wrap it up and head back.", tobi.line(3, true));
        assertEquals("sorry, i went down. let's wrap it up and head back.", tobi.line(4, true));
        assertEquals("Tobi went down. let's wrap it up and head back.", new AgentKpqCoordinator.Parting(
                AgentKpqCoordinator.Parting.Kind.WENT_DOWN, 4, "Tobi").line(3, true));
        assertEquals("Ann left. let's wrap it up and head back.", new AgentKpqCoordinator.Parting(
                AgentKpqCoordinator.Parting.Kind.LEFT, 2, "Ann").line(3, true));
        assertEquals("Ben dropped out. let's wrap it up and head back.", new AgentKpqCoordinator.Parting(
                AgentKpqCoordinator.Parting.Kind.GONE, 1, "Ben").line(3, true));
        assertEquals("let's wrap it up and head back.", new AgentKpqCoordinator.Parting(
                AgentKpqCoordinator.Parting.Kind.GONE, 2, null).line(3, true));
        assertEquals("let's wrap it up and head back.", AgentKpqCoordinator.Parting.GENERIC.line(3, true));
        assertEquals("we've been at this a while. let's wrap it up and head back.",
                AgentKpqCoordinator.Parting.of(AgentKpqCoordinator.Parting.Kind.TOO_LONG).line(3, true));
        assertEquals("looks like this run's over. let's head back.",
                AgentKpqCoordinator.Parting.of(AgentKpqCoordinator.Parting.Kind.OVER).line(3, true));
    }

    @Test
    void theEventEndingIsSaidWithoutTheEnginesReason() {
        // end(eim) sent everyone out, the timer for one.
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_2, ben, ann, mira, pip);
        for (Character member : new Character[]{ben, ann, mira, pip}) {
            run.outside(member, AgentKpqDefinition.EXIT_MAP);
        }

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals("The KPQ event instance disappeared or the leader left it", session.failure());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                mira, "looks like this run's over. let's head back."), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), startsWith("KPQ stopped")), never());
    }

    @Test
    void aCompanionLostAfterTheClearIsNamedNotNumbered() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.CLAIMING_REWARDS, ben, ann, mira, pip);
        for (Character member : new Character[]{ben, ann, mira}) {
            when(member.getMapId()).thenReturn(AgentKpqDefinition.BONUS_MAP);
        }
        run.withoutRuntimeEntry(pip);
        run.offline(pip);
        AgentKpqCoordinator.tick(session, 2_000L);
        run.outside(mira, AgentKpqDefinition.EXIT_MAP);

        AgentKpqCoordinator.tick(session, 3_000L);
        AgentKpqCoordinator.tick(session, 3_000L + TIMEOUT_MS);

        assertEquals("Party member 4 disconnected or left the channel", session.failure());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                mira, "Pip dropped out. let's wrap it up and head back."), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), startsWith("KPQ stopped")), never());
    }

    @Test
    void theLeaderLoggingOutOnTheBonusMapIsAWrapUpNotALoss() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.CLAIMING_REWARDS, ben, ann, mira, pip);
        for (Character member : new Character[]{ben, ann, mira, pip}) {
            when(member.getMapId()).thenReturn(AgentKpqDefinition.BONUS_MAP);
        }
        run.offline(ben);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals("The event leader is no longer active", session.failure());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                mira, "Ben dropped out. let's wrap it up and head back."), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), contains("can't finish")), never());
    }

    @Test
    void theClearCountsBeforeTheRunMovesOnToItsRewards() {
        // The leader's thread set 5stageclear; the next tick would have moved the run to CLAIMING_REWARDS.
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, ann, mira, pip);
        when(run.event.getProperty("5stageclear")).thenReturn("true");
        run.offline(ben);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                mira, "Ben dropped out. let's wrap it up and head back."), times(1));
    }

    @Test
    void anUnclearedStageStillSaysTheRunCannotBeFinished() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_5, ben, ann, mira, pip);
        run.offline(ben);

        AgentKpqCoordinator.tick(session, 2_000L);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                mira, "Ben dropped out, so we can't finish this one. let's head out."), times(1));
    }

    @Test
    void aStageOneThatTakesTooLongEndsInTheirWordsOnly() {
        AgentKpqSession session = run.session(true, AgentKpqSession.Phase.STAGE_1, ben, ann, mira, pip);

        AgentKpqCoordinator.tick(session, 2_000L + PHASE_TIMEOUT_MS);

        assertEquals(AgentKpqSession.Phase.FAILED, session.phase());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(
                mira, "we've been stuck too long. let's call it and head out."), times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), startsWith("No KPQ progress")), never());
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), startsWith("KPQ stopped")), never());
    }

    @Test
    void aRunThatIsNotAdoptedKeepsItsStockLines() {
        AgentKpqSession session = run.session(false, AgentKpqSession.Phase.STAGE_1, ben, ann, mira, pip);

        AgentKpqCoordinator.tick(session, 2_000L + PHASE_TIMEOUT_MS);

        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(any(), startsWith("No KPQ progress in stage 1")),
                times(1));
        run.dialogue.verify(() -> AgentKpqDialogue.sayMapNow(mira, "KPQ stopped: No KPQ progress for "
                + (PHASE_TIMEOUT_MS / 60_000L) + " minutes in stage 1."), times(1));
    }
}
