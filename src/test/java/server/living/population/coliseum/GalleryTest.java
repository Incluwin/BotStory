package server.living.population.coliseum;

import org.junit.jupiter.api.Test;
import server.pvp.Glicko2;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GalleryTest {
    private static LadderRules.Candidate c(int id, int level, double rating) {
        return new LadderRules.Candidate(id, level, new Glicko2.Rating(rating, 80, 0.06));
    }

    @Test
    void theBoutIsTheBestRatedAgainstItsNearestRatedFoe() {
        Optional<int[]> pair = Gallery.pair(List.of(c(1, 80, 1500), c(2, 82, 1720), c(3, 79, 1650), c(4, 81, 1400)));

        assertArrayEquals(new int[] {2, 3}, pair.orElseThrow());
    }

    @Test
    void aTopRatedWithNobodyWithinFiveLevelsGivesWayToTheNext() {
        Optional<int[]> pair = Gallery.pair(List.of(c(1, 99, 1900), c(2, 70, 1600), c(3, 72, 1550)));

        assertArrayEquals(new int[] {2, 3}, pair.orElseThrow(), "the Lv 99 has no one within five levels");
    }

    @Test
    void noPairWhenNobodyHasAFoe() {
        assertTrue(Gallery.pair(List.of(c(1, 60, 1500))).isEmpty());
        assertTrue(Gallery.pair(List.of(c(1, 60, 1500), c(2, 90, 1500))).isEmpty());
        assertTrue(Gallery.pair(List.of()).isEmpty());
    }
}
