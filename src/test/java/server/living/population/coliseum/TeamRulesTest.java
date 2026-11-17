package server.living.population.coliseum;

import config.PvpConfig;
import org.junit.jupiter.api.Test;
import server.pvp.DuelSim;
import server.pvp.Glicko2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V6: who makes up a citizen team, where the sides stand, team ratings and the simulated team match. */
class TeamRulesTest {
    private static TeamRules.Member member(int id, int level, int guild, double rating) {
        return new TeamRules.Member(id, level, guild, guild == 0 ? null : "Guild" + guild,
                new Glicko2.Rating(rating, 100, 0.06));
    }

    private static final Glicko2.Rating TARGET = new Glicko2.Rating(1500, 100, 0.06);

    @Test
    void aGuildWithEnoughFreeMembersFightsAsOneTeam() {
        List<TeamRules.Member> free = List.of(member(1, 80, 0, 1500), member(2, 80, 0, 1500),
                member(3, 81, 7, 1700), member(4, 79, 7, 1650));

        TeamRules.Team team = TeamRules.pick(80, TARGET, 2, free).orElseThrow();

        assertEquals(List.of(4, 3), team.members());
        assertEquals("Guild7", team.name());
    }

    @Test
    void ofTwoGuildsTheOneRatedNearerIsPicked() {
        List<TeamRules.Member> free = List.of(member(1, 80, 5, 1900), member(2, 80, 5, 1950),
                member(3, 80, 6, 1520), member(4, 80, 6, 1480));

        assertEquals("Guild6", TeamRules.pick(80, TARGET, 2, free).orElseThrow().name());
    }

    @Test
    void withoutAFullGuildThePickupTeamIsTheNearestRatings() {
        List<TeamRules.Member> free = List.of(member(1, 80, 5, 1900), member(2, 80, 0, 1510),
                member(3, 80, 6, 1490), member(4, 80, 0, 1300));

        TeamRules.Team team = TeamRules.pick(80, TARGET, 2, free).orElseThrow();

        assertEquals(new HashSet<>(List.of(2, 3)), new HashSet<>(team.members()));
        assertEquals(TeamRules.PICKUP, team.name());
        assertEquals(1500, team.rating().rating(), 1e-9);
    }

    @Test
    void membersOutsideTheLevelWindowAreLeftOutAndTooFewMeansNone() {
        List<TeamRules.Member> free = List.of(member(1, 80, 0, 1500), member(2, 90, 0, 1500),
                member(3, 70, 0, 1500));

        assertEquals(Optional.empty(), TeamRules.pick(80, TARGET, 2, free));
        assertEquals(Optional.empty(), TeamRules.pick(80, TARGET, 1, free), "a team is at least 2");
    }

    @Test
    void aPlayerFieldsItselfAndUpToTwoCompanions() {
        assertEquals(1, TeamRules.fieldable(0));
        assertEquals(2, TeamRules.fieldable(1));
        assertEquals(3, TeamRules.fieldable(2));
        assertEquals(3, TeamRules.fieldable(5));
    }

    @Test
    void theSidesStartApartOnTheFloor() {
        for (int i = 0; i < TeamRules.MAX_SIZE; i++) {
            assertTrue(TeamRules.spot(0, i).x < TeamRules.spot(1, i).x);
            assertEquals(TeamRules.spot(0, 0).y, TeamRules.spot(1, i).y);
        }
    }

    @Test
    void aTeamRatesAsTheMeanOfItsMembers() {
        LadderBookTest.MemoryStore store = new LadderBookTest.MemoryStore();
        store.rows.put("1:VETERAN", new LadderBook.Row(1, LadderRules.Bracket.VETERAN,
                new Glicko2.Rating(1700, 60, 0.06), 5, 1, 1L));
        store.rows.put("2:VETERAN", new LadderBook.Row(2, LadderRules.Bracket.VETERAN,
                new Glicko2.Rating(1500, 80, 0.06), 3, 3, 1L));
        LadderBook book = new LadderBook(store);

        Glicko2.Rating team = book.teamRating(List.of(1, 2), LadderRules.Bracket.VETERAN, 1L);

        assertEquals(1600, team.rating(), 1e-9);
        assertEquals(Math.sqrt((60 * 60 + 80 * 80) / 2d), team.deviation(), 1);
    }

    @Test
    void aTeamMatchMovesEveryMembersRating() {
        LadderBookTest.MemoryStore store = new LadderBookTest.MemoryStore();
        LadderBook book = new LadderBook(store);

        List<LadderBook.Change> changes = book.recordTeams(List.of(1, 2, 3), List.of(4, 5, 6),
                LadderRules.Bracket.VETERAN, 1d, 1_700_000_000_000L);

        assertEquals(6, changes.size());
        for (int i = 0; i < 3; i++) {
            assertTrue(changes.get(i).delta() > 0, "winner " + i);
            assertTrue(changes.get(i + 3).delta() < 0, "loser " + i);
        }
        assertEquals(1, book.row(2, LadderRules.Bracket.VETERAN).wins());
        assertEquals(1, book.row(6, LadderRules.Bracket.VETERAN).losses());
        assertEquals(6, store.saves);
    }

    private static DuelSim.Fighter fighter(String name, double coefficient) {
        int hRef = 4000;
        double rawDps = 8000;
        return new DuelSim.Fighter(name, 70, 112, false, hRef, 3000, rawDps, coefficient, 200, 0, 0, 0, 1d, 0, 1d,
                hRef);
    }

    @Test
    void aMirrorTeamRoundIsEvenAndASideOfOneMoreWinsMostOfThem() {
        PvpConfig config = new PvpConfig();
        double coefficient = 4000 / (config.PVP_TTK_SECONDS * 8000);
        Random random = new Random(5);
        List<DuelSim.Fighter> two = List.of(fighter("a", coefficient), fighter("b", coefficient));
        List<DuelSim.Fighter> twoMore = List.of(fighter("c", coefficient), fighter("d", coefficient));
        List<DuelSim.Fighter> three = new ArrayList<>(twoMore);
        three.add(fighter("e", coefficient));

        int mirrorWins = 0;
        int bigWins = 0;
        int rounds = 2_000;
        for (int i = 0; i < rounds; i++) {
            DuelSim.Outcome mirror = DuelSim.teamRound(two, twoMore, config, random, 90);
            mirrorWins += mirror.winner() == 0 ? 1 : 0;
            assertTrue(mirror.seconds() <= 90, "a round ends at the bell");
            bigWins += DuelSim.teamRound(three, two, config, random, 90).winner() == 0 ? 1 : 0;
        }
        assertEquals(0.5d, mirrorWins / (double) rounds, 0.05d);
        assertTrue(bigWins / (double) rounds > 0.8d, "3 against 2 won " + bigWins + " of " + rounds);
    }

    @Test
    void aSimulatedTeamMatchIsABestOfThree() {
        PvpConfig config = new PvpConfig();
        List<DuelSim.Fighter> strong = List.of(fighter("s1", 1d), fighter("s2", 1d));
        List<DuelSim.Fighter> harmless = List.of(fighter("h1", 0d), fighter("h2", 0d));

        assertEquals(0, TeamLadder.simulatedMatch(strong, harmless, config, new Random(3)));
        assertEquals(1, TeamLadder.simulatedMatch(harmless, strong, config, new Random(3)));
    }
}
