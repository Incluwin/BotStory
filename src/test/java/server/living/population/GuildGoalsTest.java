package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.living.population.CitizenStore.GuildMeta;
import server.living.population.PopulationContent.Lobby;
import server.living.population.PopulationContent.Region;
import server.living.population.needs.Need;
import server.living.population.needs.NeedKind;
import server.living.population.records.Clear;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Weekly guild goals (docs/WORLD_SYSTEMS_PLAN.md N5.2): a goal from the guild's band, progress from the rows members'
 * deeds left (clears, met needs, coliseum wins, the player's included), paid once a week in GP however often it is
 * counted or the server restarts, and a member who left stops counting.
 */
class GuildGoalsTest {
    private static final long DAY = 86_400_000L;
    /** A Wednesday noon: week 2900's Monday is two and a half days earlier. */
    private static final int WEEK = 2900;
    private static final long NOW = GuildGoals.weekStart(WEEK) + 2 * DAY + DAY / 2;
    private static final int GUILD = 7;
    private static final int HUMAN = 10;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class, withSettings().stubOnly());
    private final MemoryStore store = new MemoryStore();
    private final FakeDeeds deeds = new FakeDeeds();
    private final FakeGuilds guilds = new FakeGuilds();

    @BeforeEach
    void setUp() {
        config.GUILD_GOALS_ENABLED = true;
        config.PQ_RUN_QUESTS = "kpq,hpq";
        PopulationContent content = mock(PopulationContent.class, withSettings().stubOnly());
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(content);
        when(content.regions()).thenReturn(Map.of("victoria", new Region("victoria", 10, 40, List.of(100000000),
                List.of(), List.of(new Lobby("hpq", 100000200, 10, 20, "HPQ"), new Lobby("kpq", 103000000, 21, 30, "KPQ")),
                910000000, 60, "")));
        guilds.metas.add(new GuildMeta(GUILD, "open", 18, 33, "", "evening"));
        guilds.members.put(GUILD, new HashSet<>(Set.of(1, 2, 3, HUMAN)));
    }

    private GuildGoals goals() {
        return new GuildGoals(population, store, deeds, guilds);
    }

    private static Clear clear(int id, String quest, long at, int... members) {
        List<Clear.Member> list = new ArrayList<>();
        for (int member : members) {
            list.add(new Clear.Member(member, member == HUMAN ? Clear.AS_PLAYER : Clear.AS_CITIZEN, "c" + member));
        }
        return new Clear(id, quest, "", 1, 0, 0L, at, 0L, Clear.Kind.CITIZEN, 0, list);
    }

    private static Need met(int id, int by, long closedAt) {
        return new Need(id, 50, NeedKind.SUPPLY, 2000002, 10, 10, 100, "", 0, Need.State.MET, 0, 0L, by, true,
                closedAt - DAY, 0L, closedAt);
    }

    /** The goal set for this week, forced to {@code kind} so the test says what it counts. */
    private void goalIs(GuildGoals.Kind kind, String detail, int target) {
        store.rows.put(GUILD + ":" + WEEK, new GuildGoals.Goal(GUILD, WEEK, kind, detail, target, 0, false));
    }

    @Test
    void aWeekStartsOnMondayUtc() {
        assertEquals(WEEK, GuildGoals.week(GuildGoals.weekStart(WEEK)));
        assertEquals(WEEK - 1, GuildGoals.week(GuildGoals.weekStart(WEEK) - 1));
        assertEquals(WEEK, GuildGoals.week(NOW));
        // 2026-11-23 (a Monday) 00:00 UTC starts a week
        long monday = java.time.LocalDate.of(2026, 11, 23).toEpochDay() * DAY;
        assertEquals(monday, GuildGoals.weekStart(GuildGoals.week(monday)));
    }

    @Test
    void aGoalIsPickedFromTheBandOncePerWeekAndTheSameAfterARestart() {
        guilds.player.remove(GUILD); // citizens only: a clears goal
        int paid = goals().check(NOW);

        assertEquals(0, paid);
        GuildGoals.Goal goal = store.rows.get(GUILD + ":" + WEEK);
        assertNotNull(goal);
        assertEquals(GuildGoals.Kind.PQ_CLEARS, goal.kind());
        assertTrue(Set.of("hpq", "kpq").contains(goal.detail()), "a quest of its band 18-33: " + goal.detail());
        assertEquals(config.GUILD_GOAL_CLEARS, goal.target());
        assertEquals(List.of(GUILD + " guild.goal.new " + goals().text(goal)), guilds.said);
        assertEquals(List.of(GUILD + " " + goals().text(goal)), guilds.notices, "and once on the guild BBS");

        GuildGoals.Goal again = goals().pick(guilds.metas.get(0), WEEK);
        assertEquals(goal.kind(), again.kind());
        assertEquals(goal.detail(), again.detail(), "seeded by guild and week: the same pick after a restart");
    }

    @Test
    void needsAndWinsGoalsOnlyForAGuildWithAPlayer() {
        guilds.player.remove(GUILD);
        Set<GuildGoals.Kind> kinds = new HashSet<>();
        for (int week = 0; week < 60; week++) {
            kinds.add(goals().pick(guilds.metas.get(0), week).kind());
        }
        assertEquals(Set.of(GuildGoals.Kind.PQ_CLEARS), kinds);

        guilds.player.add(GUILD);
        for (int week = 0; week < 60; week++) {
            kinds.add(goals().pick(guilds.metas.get(0), week).kind());
        }
        assertEquals(Set.of(GuildGoals.Kind.values()), kinds);
    }

    @Test
    void clearsWithAMemberCountTheHumansIncludedAndOthersDoNot() {
        goalIs(GuildGoals.Kind.PQ_CLEARS, "kpq", 3);
        long from = GuildGoals.weekStart(WEEK);
        deeds.clears.add(clear(1, "KerningPQ", from + DAY, 1, 40, 41)); // a citizen member
        deeds.clears.add(clear(2, "KerningPQ", from + DAY, HUMAN, 42)); // the human member
        deeds.clears.add(clear(3, "KerningPQ", from + DAY, 40, 41, 42)); // nobody of the guild
        deeds.clears.add(clear(4, "HenesysPQ", from + DAY, 1, 2)); // another quest

        goals().check(NOW);

        assertEquals(2, store.rows.get(GUILD + ":" + WEEK).progress());
        assertFalse(store.rows.get(GUILD + ":" + WEEK).done());
        assertTrue(guilds.gp.isEmpty());
    }

    @Test
    void meetingTheGoalPaysGpOnceHoweverOftenItIsCountedOrRestarted() {
        goalIs(GuildGoals.Kind.PQ_CLEARS, "kpq", 2);
        long from = GuildGoals.weekStart(WEEK);
        deeds.clears.add(clear(1, "KerningPQ", from + DAY, 1, 2));
        deeds.clears.add(clear(2, "KerningPQ", from + DAY, HUMAN, 3));

        GuildGoals first = goals();
        assertEquals(1, first.check(NOW));
        assertEquals(0, first.check(NOW + 60_000L));
        assertEquals(0, goals().check(NOW + 120_000L), "a restart reads done from the row");
        deeds.clears.add(clear(5, "KerningPQ", from + DAY, 1));
        assertEquals(0, goals().check(NOW + 180_000L));

        assertEquals(List.of(config.GUILD_GOAL_GP), guilds.gp.get(GUILD));
        assertTrue(store.rows.get(GUILD + ":" + WEEK).done());
        assertTrue(guilds.said.contains(GUILD + " guild.goal.met 2 KPQ clears"), guilds.said.toString());
    }

    @Test
    void nextWeekIsANewGoal() {
        goalIs(GuildGoals.Kind.PQ_CLEARS, "kpq", 1);
        deeds.clears.add(clear(1, "KerningPQ", GuildGoals.weekStart(WEEK) + DAY, 1));
        GuildGoals goals = goals();
        assertEquals(1, goals.check(NOW));

        long nextWeek = GuildGoals.weekStart(WEEK + 1) + DAY;
        goals.check(nextWeek);

        GuildGoals.Goal next = store.rows.get(GUILD + ":" + (WEEK + 1));
        assertNotNull(next);
        assertFalse(next.done());
        assertEquals(0, next.progress(), "last week's clear is last week's");
    }

    @Test
    void metNeedsCountByWhoHandedThemIn() {
        goalIs(GuildGoals.Kind.NEEDS_MET, "", 2);
        long from = GuildGoals.weekStart(WEEK);
        deeds.needs.add(met(1, HUMAN, from + DAY));
        deeds.needs.add(met(2, 99, from + DAY)); // a stranger handed it in
        deeds.needs.add(met(3, HUMAN, from - DAY)); // last week
        Need expired = new Need(4, 50, NeedKind.SUPPLY, 2000002, 10, 0, 100, "", 0, Need.State.EXPIRED, 0, 0L, HUMAN,
                true, from, 0L, from + DAY);
        deeds.needs.add(expired);

        goals().check(NOW);

        assertEquals(1, store.rows.get(GUILD + ":" + WEEK).progress());
    }

    @Test
    void coliseumWinsCountMatchesWithAMemberOnTheWinningSide() {
        goalIs(GuildGoals.Kind.COLISEUM_WINS, "", 2);
        deeds.wins.put(1L, Set.of(HUMAN, 2)); // one match, two members: one win
        deeds.wins.put(2L, Set.of(3));
        deeds.wins.put(3L, Set.of(77));

        assertEquals(1, goals().check(NOW));
        assertEquals(2, store.rows.get(GUILD + ":" + WEEK).progress());
    }

    @Test
    void aMemberWhoLeftStopsCountingButKeepsWhatWasCounted() {
        goalIs(GuildGoals.Kind.PQ_CLEARS, "kpq", 5);
        long from = GuildGoals.weekStart(WEEK);
        deeds.clears.add(clear(1, "KerningPQ", from + DAY, HUMAN, 40));
        GuildGoals goals = goals();
        goals.check(NOW);
        assertEquals(1, store.rows.get(GUILD + ":" + WEEK).progress());

        guilds.members.get(GUILD).remove(HUMAN); // the human left the guild
        deeds.clears.add(clear(2, "KerningPQ", from + 2 * DAY, HUMAN, 41));
        goals.check(NOW + 60_000L);

        assertEquals(1, store.rows.get(GUILD + ":" + WEEK).progress(), "their new clear does not count; the old one stays");
    }

    @Test
    void offMeansNothingIsSetCountedOrPaid() {
        config.GUILD_GOALS_ENABLED = false;
        deeds.clears.add(clear(1, "KerningPQ", NOW - DAY, 1));
        GuildGoals goals = goals();

        goals.minute(NOW);

        assertTrue(store.rows.isEmpty());
        assertEquals(0, goals.checkNow());
        assertTrue(guilds.gp.isEmpty());
    }

    @Test
    void theMinuteCountsOnlyEveryCheckInterval() {
        goalIs(GuildGoals.Kind.PQ_CLEARS, "kpq", 9);
        GuildGoals goals = goals();
        goals.minute(NOW);
        deeds.clears.add(clear(1, "KerningPQ", NOW - DAY, 1));
        goals.minute(NOW + 60_000L);
        assertEquals(0, store.rows.get(GUILD + ":" + WEEK).progress(), "not yet");
        goals.minute(NOW + config.GUILD_GOAL_CHECK_MINUTES * 60_000L);
        assertEquals(1, store.rows.get(GUILD + ":" + WEEK).progress());
    }

    @Test
    void everyGoalFitsTheGuildBoardsTitleLimit() {
        GuildGoals goals = goals();
        for (GuildGoals.Kind kind : GuildGoals.Kind.values()) {
            for (String detail : List.of("kpq", "hpq", "balrog")) {
                String title = GuildGoalStore.NOTICE_PREFIX + goals.text(new GuildGoals.Goal(GUILD, WEEK, kind, detail, 99, 0,
                        false));
                assertTrue(title.length() <= 25, title);
            }
        }
    }

    @Test
    void balrogBattlesCountForABalrogGoal() {
        assertEquals("balrog", GuildGoals.questOf("BalrogBattle_Easy"));
        assertEquals("kpq", GuildGoals.questOf("KerningPQ"));
    }

    // ---- fakes -------------------------------------------------------------------------------------------------------

    /** living_guild_goal in memory, with the same INSERT IGNORE, GREATEST and done = 0 rules. */
    static final class MemoryStore implements GuildGoals.Store {
        final Map<String, GuildGoals.Goal> rows = new HashMap<>();

        @Override
        public GuildGoals.Goal load(int guildId, int weekOf) {
            return rows.get(guildId + ":" + weekOf);
        }

        @Override
        public void insertIfAbsent(GuildGoals.Goal goal, long nowMs) {
            rows.putIfAbsent(goal.guildId() + ":" + goal.weekOf(), goal);
        }

        @Override
        public void progress(int guildId, int weekOf, int progress) {
            rows.computeIfPresent(guildId + ":" + weekOf, (key, goal) -> goal.withProgress(progress));
        }

        @Override
        public boolean markDone(int guildId, int weekOf, long nowMs) {
            GuildGoals.Goal goal = rows.get(guildId + ":" + weekOf);
            if (goal == null || goal.done()) {
                return false;
            }
            rows.put(guildId + ":" + weekOf, goal.finished());
            return true;
        }
    }

    static final class FakeDeeds implements GuildGoals.Deeds {
        final List<Clear> clears = new ArrayList<>();
        final List<Need> needs = new ArrayList<>();
        final Map<Long, Set<Integer>> wins = new HashMap<>();

        @Override
        public List<Clear> clears(long fromMs, long toMs) {
            return clears.stream().filter(c -> c.clearedAtMs() >= fromMs && c.clearedAtMs() < toMs).toList();
        }

        @Override
        public List<Need> needsEnded(long sinceMs) {
            return needs.stream().filter(n -> n.closedAtMs() > sinceMs).toList();
        }

        @Override
        public Map<Long, Set<Integer>> coliseumWins(long fromMs, long toMs) {
            return wins;
        }
    }

    static final class FakeGuilds implements GuildGoals.Guilds {
        final List<GuildMeta> metas = new ArrayList<>();
        final Map<Integer, Set<Integer>> members = new HashMap<>();
        final Set<Integer> player = new HashSet<>(Set.of(GUILD));
        final Map<Integer, List<Integer>> gp = new HashMap<>();
        final List<String> said = new ArrayList<>();
        final List<String> notices = new ArrayList<>();

        @Override
        public Collection<GuildMeta> bots() {
            return metas;
        }

        @Override
        public Set<Integer> members(int guildId) {
            return members.getOrDefault(guildId, Set.of());
        }

        @Override
        public boolean hasPlayer(int guildId) {
            return player.contains(guildId);
        }

        @Override
        public String name(int guildId) {
            return "Lumen";
        }

        @Override
        public void gainGp(int guildId, int amount) {
            gp.computeIfAbsent(guildId, id -> new ArrayList<>()).add(amount);
        }

        @Override
        public void announce(int guildId, String trigger, String goalText) {
            said.add(guildId + " " + trigger + " " + goalText);
        }

        @Override
        public void notice(int guildId, String goalText) {
            notices.add(guildId + " " + goalText);
        }
    }
}
