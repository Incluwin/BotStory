package server.living.population.coliseum;

import client.Character;
import org.junit.jupiter.api.Test;
import server.pvp.Glicko2;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The guild ladder (docs/WORLD_SYSTEMS_PLAN.md N5.3): a player fighting for its guild has guild mates fill its side
 * before its companions and never a stranger; the foe is another guild's team, never a pickup and never its own
 * guild; the guild book rates guild against guild, apart from the members' team ratings; and the background sims cut
 * citizens into guild teams first (the guild-team pick PVP_PLAN.md says was never seen live).
 */
class GuildLadderTest {
    private static final Glicko2.Rating TARGET = new Glicko2.Rating(1500, 100, 0.06);
    private static final int HUMAN = 100;

    private static TeamRules.Member member(int id, int level, int guild, double rating) {
        return new TeamRules.Member(id, level, guild, guild == 0 ? null : "Guild" + guild,
                new Glicko2.Rating(rating, 100, 0.06));
    }

    // ---- the player's side -----------------------------------------------------------------------------------------

    @Test
    void guildMatesFillTheSideBeforeCompanionsNearestLevelFirst() {
        List<TeamRules.Member> free = List.of(member(1, 70, 7, 1500), member(2, 62, 7, 1500), member(3, 66, 7, 1500),
                member(4, 61, 8, 1500), member(5, 60, 0, 1500));

        assertEquals(List.of(HUMAN, 2, 3), TeamRules.guildSide(HUMAN, 61, 7, 3, free, List.of(50, 51)).orElseThrow());
        assertEquals(List.of(HUMAN, 2), TeamRules.guildSide(HUMAN, 61, 7, 2, free, List.of(50)).orElseThrow());
    }

    @Test
    void companionsFillWhatGuildMatesLeaveAndNeverAStranger() {
        List<TeamRules.Member> free = List.of(member(1, 60, 7, 1500), member(4, 60, 8, 1500), member(5, 60, 0, 1500));

        assertEquals(List.of(HUMAN, 1, 50), TeamRules.guildSide(HUMAN, 60, 7, 3, free, List.of(50, 51)).orElseThrow());
        assertTrue(TeamRules.guildSide(HUMAN, 60, 7, 3, free, List.of()).isEmpty(),
                "one guild mate and no companion leave a seat: strangers 4 and 5 never fill it");
        assertTrue(TeamRules.guildSide(HUMAN, 60, 0, 2, free, List.of(50)).isEmpty(), "no guild, no guild match");
    }

    @Test
    void guildMatesOutsideTheLevelWindowStayOut() {
        List<TeamRules.Member> free = List.of(member(1, 60 + LadderRules.LEVEL_WINDOW + 1, 7, 1500));

        assertEquals(List.of(HUMAN, 50), TeamRules.guildSide(HUMAN, 60, 7, 2, free, List.of(50)).orElseThrow());
    }

    // ---- the foe -----------------------------------------------------------------------------------------------------

    @Test
    void theFoeIsAnotherGuildsTeamRatedNearest() {
        List<TeamRules.Member> free = List.of(member(1, 60, 7, 1500), member(2, 60, 7, 1500),
                member(3, 60, 8, 1900), member(4, 60, 8, 1950), member(5, 60, 9, 1520), member(6, 60, 9, 1480));

        TeamRules.Team foe = TeamRules.guildFoe(60, TARGET, 2, 7, free).orElseThrow();

        assertEquals(Set.of(5, 6), Set.copyOf(foe.members()));
        assertEquals("Guild9", foe.name());
    }

    @Test
    void noOtherFullGuildMeansNoMatchNotAPickupTeam() {
        List<TeamRules.Member> free = List.of(member(1, 60, 7, 1500), member(2, 60, 7, 1500),
                member(3, 60, 8, 1500), member(4, 60, 0, 1500), member(5, 60, 9, 1500));

        assertTrue(TeamRules.guildFoe(60, TARGET, 2, 7, free).isEmpty());
    }

    // ---- the guild book ----------------------------------------------------------------------------------------------

    @Test
    void theGuildBookRatesGuildAgainstGuildApartFromTheTeamBook() {
        LadderBookTest.MemoryStore guildStore = new LadderBookTest.MemoryStore();
        LadderBookTest.MemoryStore teamStore = new LadderBookTest.MemoryStore();
        LadderBook guilds = new LadderBook(guildStore);
        LadderBook teams = new LadderBook(teamStore);
        long now = 1_000_000_000_000L;

        LadderBook.Change[] changes = guilds.record(7, 9, LadderRules.Bracket.VETERAN, 1d, now);
        teams.recordTeams(List.of(HUMAN, 1), List.of(5, 6), LadderRules.Bracket.VETERAN, 1d, now);

        assertTrue(changes[0].after().rating().rating() > changes[0].before().rating().rating(), "guild 7 won");
        assertTrue(changes[1].after().rating().rating() < changes[1].before().rating().rating(), "guild 9 lost");
        assertEquals(1, guilds.row(7, LadderRules.Bracket.VETERAN).wins());
        assertEquals(1, guilds.row(9, LadderRules.Bracket.VETERAN).losses());
        assertEquals(0, teams.row(7, LadderRules.Bracket.VETERAN).wins() + teams.row(7, LadderRules.Bracket.VETERAN)
                .losses(), "a guild id is never a fighter in the team book");
        assertEquals(1, teams.row(HUMAN, LadderRules.Bracket.VETERAN).wins());
    }

    // ---- the background sims ---------------------------------------------------------------------------------------

    private static Character chr(int id, int guildId) {
        Character chr = mock(Character.class, withSettings().stubOnly());
        when(chr.getId()).thenReturn(id);
        when(chr.getGuildId()).thenReturn(guildId);
        return chr;
    }

    @Test
    void theSimsCutGuildTeamsFirstAndSeeThemAsGuilds() {
        List<Character> pool = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pool.add(chr(10 + i, 7));
            pool.add(chr(20 + i, 9));
        }
        pool.add(chr(30, 8)); // a guild short of a team
        pool.add(chr(31, 0));
        pool.add(chr(32, 0));

        List<List<Character>> teams = TeamLadder.teams(pool, 3, new Random(5L));

        assertEquals(3, teams.size());
        assertEquals(Set.of(7, 9), Set.of(TeamLadder.guildOf(teams.get(0)), TeamLadder.guildOf(teams.get(1))));
        assertEquals(0, TeamLadder.guildOf(teams.get(2)), "the rest is a pickup team");
    }
}
