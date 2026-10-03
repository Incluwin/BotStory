package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.hpq.AgentHpqParting.Kind;
import server.life.Monster;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a companion run's companions put its early end (plan step 3): every kind before and after the clear (the
 * wrap-ups), SILENT for a run that never got going, GONE for an offline leader and DEFEATED for a downed one, the
 * ended event's cause (the Bunny, the timer, or neither), and the speaker: the first standing companion, else the
 * first companion even when it is down.
 */
class AgentHpqPartingTest {
    private static final int MIRA = 2;
    private static final int TOBI = 3;
    private static final Pattern NO_CAPITALS_BUT_NAMES = Pattern.compile("^[a-z0-9 ,.!'?\\-]*$");
    private final HpqRunFixture run = new HpqRunFixture();

    @AfterEach
    void tearDown() {
        run.close();
    }

    private static String before(Kind kind, String subject, int subjectId, int speakerId) {
        return new AgentHpqParting(kind, subjectId, subject).line(speakerId, false);
    }

    private static String after(Kind kind, String subject, int subjectId, int speakerId) {
        return new AgentHpqParting(kind, subjectId, subject).line(speakerId, true);
    }

    @Test
    void everyKindBeforeTheClear() {
        assertEquals("we can't finish this one. let's head out.", before(Kind.GENERIC, null, 0, MIRA));
        assertEquals("sorry, i went down. we need three for this, so let's head out.",
                before(Kind.WENT_DOWN, "Mira", MIRA, MIRA));
        assertEquals("Tobi went down, and we need three for this. let's head out.",
                before(Kind.WENT_DOWN, "Tobi", TOBI, MIRA));
        assertEquals("we're short now, and we need three for this. let's head out.",
                before(Kind.WENT_DOWN, null, 0, MIRA));
        assertEquals("Ann left, and we need three for this. let's head out.", before(Kind.LEFT, "Ann", 5, MIRA));
        assertEquals("we're short now, and we need three for this. let's head out.", before(Kind.LEFT, null, 0, MIRA));
        assertEquals("sorry, i went down. we can't finish this one, let's head out.",
                before(Kind.DEFEATED, "Mira", MIRA, MIRA));
        assertEquals("Ben went down, so we can't finish this one. let's head out.",
                before(Kind.DEFEATED, "Ben", 1, MIRA));
        assertEquals("we can't finish this one. let's head out.", before(Kind.DEFEATED, null, 0, MIRA));
        assertEquals("Ben dropped out, so we can't finish this one. let's head out.", before(Kind.GONE, "Ben", 1, MIRA));
        assertEquals("we can't finish this one. let's head out.", before(Kind.GONE, null, 0, MIRA));
        assertEquals("the moon bunny went home sick. we can't finish this one, let's head out.",
                before(Kind.BUNNY, null, 0, MIRA));
        assertEquals("the moon's setting, we ran out of time. let's head out.", before(Kind.TIMEOUT, null, 0, MIRA));
        assertEquals("we've been stuck too long. let's call it and head out.", before(Kind.TOO_LONG, null, 0, MIRA));
        assertNull(before(Kind.SILENT, "Ben", 1, MIRA), "a run that never got going ends without a word");
    }

    @Test
    void afterTheClearEveryKindIsAWrapUp() {
        assertEquals("let's wrap it up and head back.", after(Kind.GENERIC, null, 0, MIRA));
        assertEquals("sorry, i went down. let's wrap it up and head back.", after(Kind.WENT_DOWN, "Mira", MIRA, MIRA));
        assertEquals("Tobi went down. let's wrap it up and head back.", after(Kind.WENT_DOWN, "Tobi", TOBI, MIRA));
        assertEquals("let's wrap it up and head back.", after(Kind.WENT_DOWN, null, 0, MIRA));
        assertEquals("sorry, i went down. let's wrap it up and head back.", after(Kind.DEFEATED, "Mira", MIRA, MIRA));
        assertEquals("Ben went down. let's wrap it up and head back.", after(Kind.DEFEATED, "Ben", 1, MIRA));
        assertEquals("Ann left. let's wrap it up and head back.", after(Kind.LEFT, "Ann", 5, MIRA));
        assertEquals("let's wrap it up and head back.", after(Kind.LEFT, null, 0, MIRA));
        assertEquals("Ben dropped out. let's wrap it up and head back.", after(Kind.GONE, "Ben", 1, MIRA));
        assertEquals("let's wrap it up and head back.", after(Kind.GONE, null, 0, MIRA));
        assertEquals("let's wrap it up and head back.", after(Kind.BUNNY, null, 0, MIRA));
        assertEquals("let's wrap it up and head back.", after(Kind.TIMEOUT, null, 0, MIRA));
        assertEquals("we've been at this a while. let's wrap it up and head back.",
                after(Kind.TOO_LONG, null, 0, MIRA));
        assertNull(after(Kind.SILENT, null, 0, MIRA));
    }

    @Test
    void everyLineIsLowerCaseButTheNamesAndCarriesNoMarkup() {
        List<String> wrong = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (boolean pastTheClear : new boolean[] {false, true}) {
                for (Map.Entry<Integer, String> subject : Map.of(0, "", MIRA, "Mira", TOBI, "Tobi").entrySet()) {
                    String name = subject.getValue().isEmpty() ? null : subject.getValue();
                    String line = new AgentHpqParting(kind, subject.getKey(), name).line(MIRA, pastTheClear);
                    if (line == null) continue;
                    String unnamed = name == null ? line : line.replace(name, "x");
                    if (!NO_CAPITALS_BUT_NAMES.matcher(unnamed).matches()) wrong.add(kind + " " + line);
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void theEndedEventsCauseIsTheBunnyThenTheTimerThenNeither() {
        assertEquals(Kind.BUNNY, AgentHpqParting.eventEnded(true, 30_000L, 600_000L, 2_000L));
        assertEquals(Kind.BUNNY, AgentHpqParting.eventEnded(true, 599_000L, 600_000L, 2_000L));
        assertEquals(Kind.TIMEOUT, AgentHpqParting.eventEnded(false, 598_000L, 600_000L, 2_000L));
        assertEquals(Kind.GENERIC, AgentHpqParting.eventEnded(false, 597_999L, 600_000L, 2_000L));
    }

    @Test
    void anOfflineLeaderIsGoneAndTheFirstStandingCompanionSaysSo() {
        Character ben = run.human(1, "Ben", new Point(429, 83));
        Character mira = run.companion(MIRA, "Mira", new Point(-183, -188));
        Character tobi = run.companion(TOBI, "Tobi", new Point(-293, -188));
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);
        run.offline(ben);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "Ben dropped out, so we can't finish this one. let's head out.");
        verify(run.event).dispose();
    }

    @Test
    void aDownedLeaderIsDefeated() {
        Character ben = run.human(1, "Ben", new Point(429, 83));
        Character mira = run.companion(MIRA, "Mira", new Point(-183, -188));
        Character tobi = run.companion(TOBI, "Tobi", new Point(-293, -188));
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.COLLECTING_SEEDS, ben, mira, tobi);
        when(ben.getHp()).thenReturn(0);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "Ben went down, so we can't finish this one. let's head out.");
    }

    @Test
    void withEveryCompanionDownTheFirstCompanionStillSaysIt() {
        Character ben = run.human(1, "Ben", new Point(429, 83));
        Character ann = run.human(5, "Ann", new Point(429, 83));
        Character cal = run.human(6, "Cal", new Point(429, 83));
        Character mira = run.companion(MIRA, "Mira", new Point(-183, -188));
        Character tobi = run.companion(TOBI, "Tobi", new Point(-293, -188));
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, ann, cal, mira, tobi);
        when(mira.getHp()).thenReturn(0);
        when(tobi.getHp()).thenReturn(0);
        run.offline(ben);

        AgentHpqCoordinator.tick(session, 2_000L);

        run.said(session, "failed", mira, "Ben dropped out, so we can't finish this one. let's head out.");
    }

    @Test
    void aCompanionUpOutsideTheEventIsNotTheSpeaker() {
        Character ben = run.human(1, "Ben", new Point(429, 83));
        Character ann = run.human(5, "Ann", new Point(429, 83));
        Character mira = run.companion(MIRA, "Mira", new Point(-183, -188));
        Character tobi = run.companion(TOBI, "Tobi", new Point(-293, -188));
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, ann, mira, tobi);
        // Mira went down and respawned on the exile map; Tobi still stands by the Bunny.
        session.member(MIRA).markDown(1_500L, 0);
        run.outside(mira, AgentHpqDefinition.EXIT_MAP);
        when(ben.getHp()).thenReturn(0);

        AgentHpqCoordinator.tick(session, 2_000L);

        run.said(session, "failed", tobi, "Ben went down, so we can't finish this one. let's head out.");
    }

    @Test
    void aRunThatNeverGotGoingEndsWithoutAWord() {
        Character ben = run.human(1, "Ben", new Point(1_434, 352));
        Character mira = run.companion(MIRA, "Mira", new Point(1_400, 352));
        Character tobi = run.companion(TOBI, "Tobi", new Point(1_380, 352));
        for (Character member : new Character[] {ben, mira, tobi}) run.outside(member, AgentHpqDefinition.RECRUIT_MAP);
        AgentHpqSession preparing = run.session(true, AgentHpqSession.Phase.PREPARING, ben, mira, tobi);
        run.offline(ben);

        AgentHpqCoordinator.tick(preparing, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, preparing.phase());
        run.saidNothing();
    }

    @Test
    void aLeaderWhoDropsOutWhileThePartyEntersEndsItWithoutAWord() {
        Character ben = run.human(1, "Ben", new Point(0, 83));
        Character mira = run.companion(MIRA, "Mira", new Point(10, 83));
        Character tobi = run.companion(TOBI, "Tobi", new Point(20, 83));
        AgentHpqSession entering = run.session(true, AgentHpqSession.Phase.ENTERING, ben, mira, tobi);
        when(ben.getHp()).thenReturn(0);

        AgentHpqCoordinator.tick(entering, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, entering.phase());
        run.saidNothing();
    }

    @Test
    void theBunnyLostIsSaidAsTheBunnyGoingHomeSick() {
        Character ben = run.human(1, "Ben", new Point(429, 83));
        Character mira = run.companion(MIRA, "Mira", new Point(-183, -188));
        Character tobi = run.companion(TOBI, "Tobi", new Point(-293, -188));
        Monster bunny = run.monster(AgentHpqDefinition.MOON_BUNNY, HpqRunFixture.BUNNY_LANDING);
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);
        AgentHpqCoordinator.tick(session, 2_000L);

        run.kill(bunny);
        AgentHpqCoordinator.tick(session, 3_000L);

        run.said(session, "failed", mira, "the moon bunny went home sick. we can't finish this one, let's head out.");
    }

    @Test
    void anEventThatEndedAtItsTimerIsTheMoonSetting() {
        Character ben = run.human(1, "Ben", new Point(429, 83));
        Character mira = run.companion(MIRA, "Mira", new Point(-183, -188));
        Character tobi = run.companion(TOBI, "Tobi", new Point(-293, -188));
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);
        session.markProgress(590_000L);
        // The script's timer ran out and exiled the party.
        for (Character member : new Character[] {ben, mira, tobi}) run.outside(member, AgentHpqDefinition.EXIT_MAP);

        AgentHpqCoordinator.tick(session, 599_500L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.said(session, "failed", mira, "the moon's setting, we ran out of time. let's head out.");
    }

    @Test
    void anEventThatEndedEarlierIsJustOver() {
        Character ben = run.human(1, "Ben", new Point(429, 83));
        Character mira = run.companion(MIRA, "Mira", new Point(-183, -188));
        Character tobi = run.companion(TOBI, "Tobi", new Point(-293, -188));
        AgentHpqSession session = run.session(true, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);
        for (Character member : new Character[] {ben, mira, tobi}) run.outside(member, AgentHpqDefinition.EXIT_MAP);

        AgentHpqCoordinator.tick(session, 120_000L);

        run.said(session, "failed", mira, "we can't finish this one. let's head out.");
    }

    @Test
    void aStockRunSaysNothingNewAsItFails() {
        Character ben = run.human(1, "Ben", new Point(429, 83));
        Character mira = run.companion(MIRA, "Mira", new Point(-183, -188));
        Character tobi = run.companion(TOBI, "Tobi", new Point(-293, -188));
        AgentHpqSession session = run.session(false, AgentHpqSession.Phase.DEFENDING_BUNNY, ben, mira, tobi);
        run.offline(ben);

        AgentHpqCoordinator.tick(session, 2_000L);

        assertEquals(AgentHpqSession.Phase.FAILED, session.phase());
        run.saidNothing();
        verify(run.event).dispose();
        verify(run.party, never()).snapshot(any());
    }
}
