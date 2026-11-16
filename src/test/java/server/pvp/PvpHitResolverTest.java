package server.pvp;

import client.Character;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PvpHitResolverTest {
    private static Character at(int x, int y) {
        Character character = mock(Character.class);
        when(character.getPosition()).thenReturn(new Point(x, y));
        return character;
    }

    @Test
    void hurtboxStandsOnTheFootPointAndShrinksForHumans() {
        Rectangle body = PvpHitResolver.hurtbox(new Point(100, 0), 0);
        assertEquals(new Rectangle(81, -62, 38, 62), body);

        Rectangle inset = PvpHitResolver.hurtbox(new Point(100, 0), 6);
        assertEquals(26, inset.width);
        assertEquals(0, inset.y + inset.height);   // the feet stay where they are
    }

    @Test
    void aHumanMustBeInsideWhereItWasAndWhereItIsHeading() {
        PositionTrack track = new PositionTrack(1000);
        track.record(new Point(0, 0), 0);
        track.record(new Point(60, 0), 500);       // walking right, 60 px per report
        Character human = at(60, 0);
        PvpHitResolver.Candidate victim = new PvpHitResolver.Candidate(human, true, track);
        Rectangle swing = new Rectangle(20, -50, 60, 70);   // covers x 20..80: the last report, not where it's heading

        assertTrue(PvpHitResolver.reaches(swing, victim, false, 520, 600, 6));   // 20 ms on: still inside
        assertFalse(PvpHitResolver.reaches(swing, victim, false, 1000, 600, 6)); // 500 ms on: walked out of it
        assertTrue(PvpHitResolver.reaches(swing, victim, false, 2000, 600, 6));  // no report for 1.5 s: it stopped
    }

    @Test
    void aBotHitByAHumanCountsWhereItStoodDuringTheLagWindow() {
        PositionTrack track = new PositionTrack(1000);
        track.record(new Point(0, 0), 0);
        track.record(new Point(100, 0), 300);
        track.record(new Point(200, 0), 600);
        Character bot = at(200, 0);
        PvpHitResolver.Candidate victim = new PvpHitResolver.Candidate(bot, false, track);
        Rectangle swing = new Rectangle(-30, -50, 60, 70);   // where the bot was 600 ms ago

        assertTrue(PvpHitResolver.reaches(swing, victim, true, 600, 600, 6));
        assertFalse(PvpHitResolver.reaches(swing, victim, false, 600, 600, 6));   // a bot attacker gets no rewind
        assertFalse(PvpHitResolver.reaches(swing, victim, true, 1300, 600, 6));   // older than the window
    }

    @Test
    void victimsAreNearestFirstAndCapped() {
        Character near = at(50, 0);
        Character mid = at(120, 0);
        Character far = at(190, 0);
        Rectangle wide = new Rectangle(0, -60, 300, 80);
        List<PvpHitResolver.Candidate> candidates = List.of(
                new PvpHitResolver.Candidate(far, false, null),
                new PvpHitResolver.Candidate(near, false, null),
                new PvpHitResolver.Candidate(mid, false, null));

        List<PvpHitResolver.Candidate> met = PvpHitResolver.victims(wide, new Point(0, 0), candidates, 2,
                false, 0, 600, 6);

        assertEquals(2, met.size());
        assertEquals(near, met.get(0).character());
        assertEquals(mid, met.get(1).character());
    }

    @Test
    void theFallbackSwingReachesAheadOfTheFacing() {
        assertEquals(new Rectangle(100, -50, 80, 70), PvpHitResolver.fallbackBox(new Point(100, 0), false));
        assertEquals(new Rectangle(20, -50, 80, 70), PvpHitResolver.fallbackBox(new Point(100, 0), true));
    }
}
