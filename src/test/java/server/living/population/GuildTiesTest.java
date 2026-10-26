package server.living.population;

import org.junit.jupiter.api.Test;
import server.living.population.CitizenStore.GuildMeta;
import server.living.population.GuildTies.Side;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How close two citizen guilds are (docs/BOT_GUILD_ALLIANCES_PLAN.md): circles across both, cohort, homes, level ranges,
 * so alliances follow the guilds' relationships rather than a roll.
 */
class GuildTiesTest {
    private static final double EPSILON = 1e-9;

    @Test
    void twoGuildsSharingEverythingAreFullyTied() {
        Side a = side(meta(1, "evening", 20, 50), member(1, 11, "victoria"), member(2, 12, "victoria"));
        Side b = side(meta(2, "evening", 20, 50), member(3, 11, "victoria"), member(4, 12, "victoria"));
        assertEquals(1d, GuildTies.tie(a, b), EPSILON);
    }

    @Test
    void twoGuildsSharingNothingHaveNoTie() {
        Side a = side(meta(1, "evening", 10, 25), member(1, 11, "victoria"));
        Side b = side(meta(2, "morning", 60, 90), member(2, 12, "ossyria"));
        assertEquals(0d, GuildTies.tie(a, b), EPSILON);
    }

    @Test
    void eachPartWeighsAsThePlanSays() {
        Side base = side(meta(1, "evening", 10, 25), member(1, 0, "victoria"));
        assertEquals(GuildTies.COHORT_WEIGHT, GuildTies.tie(base, side(meta(2, "evening", 60, 90), member(2, 0, "ossyria"))),
                EPSILON);
        assertEquals(GuildTies.HOME_WEIGHT, GuildTies.tie(base, side(meta(2, "morning", 60, 90), member(2, 0, "victoria"))),
                EPSILON);
        assertEquals(GuildTies.LEVEL_WEIGHT, GuildTies.tie(base, side(meta(2, "morning", 10, 25), member(2, 0, "ossyria"))),
                EPSILON);
        Side oneCircle = side(meta(1, "evening", 10, 25), member(1, 7, "victoria"));
        assertEquals(GuildTies.CIRCLE_WEIGHT / GuildTies.CIRCLES_FULL,
                GuildTies.tie(oneCircle, side(meta(2, "morning", 60, 90), member(2, 7, "ossyria"))), EPSILON);
        assertEquals(1d, GuildTies.CIRCLE_WEIGHT + GuildTies.COHORT_WEIGHT + GuildTies.HOME_WEIGHT + GuildTies.LEVEL_WEIGHT,
                EPSILON);
    }

    @Test
    void sharedCirclesCountEachCircleOnceAndCapAtTwo() {
        List<Citizen> a = List.of(member(1, 5, "x"), member(2, 5, "x"), member(3, 6, "x"), member(4, 9, "x"), member(5, 0, "x"));
        List<Citizen> b = List.of(member(6, 5, "x"), member(7, 6, "x"), member(8, 9, "x"), member(9, 0, "x"));
        assertEquals(3, GuildTies.sharedCircles(a, b)); // circle 0 is no circle
        Side sa = side(meta(1, "", 1, 1), a.toArray(Citizen[]::new));
        Side sb = side(meta(2, "", 50, 50), b.toArray(Citizen[]::new));
        assertEquals(GuildTies.CIRCLE_WEIGHT + GuildTies.HOME_WEIGHT, GuildTies.tie(sa, sb), EPSILON);
    }

    @Test
    void homeOverlapIsTheSumOfTheSmallerShares() {
        List<Citizen> a = List.of(member(1, 0, "victoria"), member(2, 0, "victoria"), member(3, 0, "victoria"),
                member(4, 0, "ossyria"));
        List<Citizen> b = List.of(member(5, 0, "victoria"), member(6, 0, "ossyria"));
        assertEquals(0.75d, GuildTies.homeOverlap(a, b), EPSILON); // min(.75,.5) + min(.25,.5)
        assertEquals(0d, GuildTies.homeOverlap(a, List.of()), EPSILON);
    }

    @Test
    void levelOverlapIsTheSharedPartOfTheNarrowerRange() {
        assertEquals(1d, GuildTies.levelOverlap(meta(1, "", 10, 40), meta(2, "", 20, 30)), EPSILON);
        assertEquals(0.5d, GuildTies.levelOverlap(meta(1, "", 10, 30), meta(2, "", 20, 50)), EPSILON);
        assertEquals(0d, GuildTies.levelOverlap(meta(1, "", 10, 20), meta(2, "", 30, 50)), EPSILON);
        assertEquals(1d, GuildTies.levelOverlap(meta(1, "", 30, 30), meta(2, "", 30, 30)), EPSILON);
    }

    @Test
    void aBlankCohortIsNoSharedCohort() {
        assertTrue(GuildTies.sameCohort(meta(1, "evening", 1, 2), meta(2, "evening", 1, 2)));
        assertEquals(false, GuildTies.sameCohort(meta(1, "", 1, 2), meta(2, "", 1, 2)));
    }

    @Test
    void theMeanTieJudgesAGuildByTheOtherCitizenGuildsOnly() {
        Side a = side(meta(1, "evening", 20, 50), member(1, 11, "victoria"), member(2, 12, "victoria"));
        Side same = side(meta(2, "evening", 20, 50), member(3, 11, "victoria"), member(4, 12, "victoria"));
        Side far = side(meta(3, "morning", 80, 90), member(5, 0, "ossyria"));
        Map<Integer, Side> sides = Map.of(1, a, 2, same, 3, far);
        assertEquals(0.5d, CitizenAlliances.meanTie(sides, 1, List.of(1, 2, 3)), EPSILON);
        assertEquals(1d, CitizenAlliances.meanTie(sides, 1, List.of(1, 2)), EPSILON);
        assertEquals(1d, CitizenAlliances.meanTie(sides, 1, List.of(1)), EPSILON); // nobody to judge it by: it stays
        assertEquals(1d, CitizenAlliances.meanTie(sides, 1, List.of(1, 99)), EPSILON); // a player guild is not judged
    }

    @Test
    void theClosestPairFoundsFirstAndTheBiggerGuildLeads() {
        Side small = side(meta(1, "evening", 20, 50), member(1, 11, "victoria"));
        Side big = side(meta(2, "evening", 20, 50), member(2, 11, "victoria"), member(3, 12, "victoria"),
                member(4, 0, "victoria"));
        Side cousin = side(meta(3, "evening", 20, 50), member(5, 0, "ossyria")); // cohort and levels only: 0.35
        Side stranger = side(meta(4, "morning", 80, 90), member(6, 0, "ossyria"));
        Map<Integer, Side> sides = Map.of(1, small, 2, big, 3, cousin, 4, stranger);

        List<CitizenAlliances.Pair> pairs = CitizenAlliances.foundingPairs(sides, List.of(1, 2, 3, 4), 0.3d);

        assertEquals(new CitizenAlliances.Pair(2, 1, GuildTies.tie(small, big)), pairs.get(0));
        assertTrue(pairs.stream().noneMatch(pair -> pair.lead() == 4 || pair.other() == 4), "the stranger founds nothing");
        for (int i = 1; i < pairs.size(); i++) {
            assertTrue(pairs.get(i - 1).tie() >= pairs.get(i).tie(), "closest first");
        }
        assertEquals(List.of(), CitizenAlliances.foundingPairs(sides, List.of(1, 2, 3, 4), 1.01d), "none over a full tie");
    }

    static GuildMeta meta(int guildId, String cohort, int levelMin, int levelMax) {
        return new GuildMeta(guildId, "open", levelMin, levelMax, "", cohort);
    }

    static Side side(GuildMeta meta, Citizen... members) {
        return new Side(meta, new ArrayList<>(List.of(members)));
    }

    static Citizen member(int id, int circleId, String home) {
        return new Citizen(id, "Citizen" + id, "evening", circleId, 0, home, "hunter", "relaxed-v1", "test", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
