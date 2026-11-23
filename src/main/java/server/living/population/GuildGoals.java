package server.living.population;

import net.server.Server;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.population.CitizenStore.GuildMeta;
import server.living.population.PopulationContent.Lobby;
import server.living.population.needs.Need;
import server.living.population.needs.NeedStore;
import server.living.population.paper.PaperDesk;
import server.living.population.records.Clear;
import server.living.population.records.ClearStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Weekly guild goals (docs/WORLD_SYSTEMS_PLAN.md N5.2, {@code GUILD_GOALS_ENABLED}, decision D11). Each bot guild picks
 * one goal a week from its level band: clears of a party quest citizens run in that band, needs-board requests met by
 * its members, or coliseum match wins (the last two only with a player among its members: citizens alone never meet
 * a need or win a booked match). Progress counts members' real deeds that week from the rows they left
 * ({@code living_clear}, MET {@code living_need} rows, {@code pvp_match} wins), the player's included, read against
 * who is a member at the count: one who left stops counting from then on (progress never goes down). Meeting it pays
 * {@code GUILD_GOAL_GP} through {@code Server.gainGP} once (the {@code done} flag is set by the same conditional UPDATE
 * that decides the payout, so a restart or a second count never pays twice), a line in guild chat and a story for
 * the paper. One row a guild a week in {@code living_guild_goal}. Counted on the social minute every
 * {@code GUILD_GOAL_CHECK_MINUTES}; no timer of its own. New goal kinds (field-boss kills, N6) are one {@link Kind}
 * value, one {@code eligible} test and one {@code count} case.
 */
public final class GuildGoals {
    private static final Logger log = LoggerFactory.getLogger(GuildGoals.class);
    static final long DAY_MS = 86_400_000L;
    /** The coliseum's brackets start here (LadderRules.Bracket): a guild below it has no wins goal. */
    static final int COLISEUM_MIN_LEVEL = 30;

    /** What a guild sets out to do in a week. */
    public enum Kind {
        PQ_CLEARS, NEEDS_MET, COLISEUM_WINS;

        static Kind parse(String text) {
            for (Kind kind : values()) {
                if (kind.name().equals(text)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** A guild's goal for week {@code weekOf}: {@code detail} is what it is of (the lobby id of a PQ_CLEARS goal). */
    public record Goal(int guildId, int weekOf, Kind kind, String detail, int target, int progress, boolean done) {
        public Goal {
            detail = detail == null ? "" : detail;
        }

        Goal withProgress(int count) {
            return new Goal(guildId, weekOf, kind, detail, target, Math.max(progress, count), done);
        }

        Goal finished() {
            return new Goal(guildId, weekOf, kind, detail, target, progress, true);
        }
    }

    /** The goal rows. */
    interface Store {
        Goal load(int guildId, int weekOf);

        void insertIfAbsent(Goal goal, long nowMs);

        void progress(int guildId, int weekOf, int progress);

        /** Sets done on a row not yet done; true only for the call that set it (the one that pays). */
        boolean markDone(int guildId, int weekOf, long nowMs);
    }

    /** The rows deeds are counted from, read once a count. */
    interface Deeds {
        List<Clear> clears(long fromMs, long toMs);

        /** Requests that ended since {@code sinceMs} (MET ones count, by their {@code fulfilledBy}). */
        List<Need> needsEnded(long sinceMs);

        /** Match id -> the fighters on its winning side. */
        Map<Long, Set<Integer>> coliseumWins(long fromMs, long toMs);
    }

    /** The guilds: their members, names, a player among them, and the payout. */
    interface Guilds {
        Collection<GuildMeta> bots();

        /** Everyone in the guild now, online or not. */
        Set<Integer> members(int guildId);

        /** True when a player (not a citizen or companion) is a member. */
        boolean hasPlayer(int guildId);

        String name(int guildId);

        void gainGp(int guildId, int gp);

        /** An online citizen of the guild (its master first) says {@code trigger} with {goal}. */
        void announce(int guildId, String trigger, String goalText);

        /** The guild's citizen master posts the week's goal on the guild BBS (N5.5); nothing when a player leads it. */
        void notice(int guildId, String goalText);
    }

    private final Population population;
    private final Store store;
    private final Deeds deeds;
    private final Guilds guilds;
    /** "guildId:week" -> the goal as last read or counted. */
    private final Map<String, Goal> goals = new HashMap<>();
    private long lastCheckMs;

    GuildGoals(Population population) {
        this(population, new GuildGoalStore(), liveDeeds(), new LiveGuilds(population));
    }

    GuildGoals(Population population, Store store, Deeds deeds, Guilds guilds) {
        this.population = population;
        this.store = store;
        this.deeds = deeds;
        this.guilds = guilds;
    }

    private static Deeds liveDeeds() {
        ClearStore clears = new ClearStore();
        NeedStore needs = new NeedStore();
        return new Deeds() {
            @Override
            public List<Clear> clears(long fromMs, long toMs) {
                return clears.between(fromMs, toMs);
            }

            @Override
            public List<Need> needsEnded(long sinceMs) {
                return needs.endedSince(sinceMs);
            }

            @Override
            public Map<Long, Set<Integer>> coliseumWins(long fromMs, long toMs) {
                return GuildGoalStore.coliseumWins(fromMs, toMs);
            }
        };
    }

    boolean on() {
        return population.config().GUILD_GOALS_ENABLED;
    }

    /** Weeks since the epoch, Monday start (UTC), as the coliseum's weekly climb counts them. */
    static int week(long ms) {
        return (int) Math.floorDiv(Math.floorDiv(ms, DAY_MS) + 3, 7);
    }

    /** When week {@code weekOf} began (Monday 00:00 UTC). */
    static long weekStart(int weekOf) {
        return ((long) weekOf * 7L - 3L) * DAY_MS;
    }

    /** The social minute: a count every GUILD_GOAL_CHECK_MINUTES. */
    public void minute(long nowMs) {
        if (!on() || nowMs - lastCheckMs < population.config().GUILD_GOAL_CHECK_MINUTES * 60_000L) {
            return;
        }
        lastCheckMs = nowMs;
        try {
            check(nowMs);
        } catch (RuntimeException failure) {
            log.warn("guild goals: the count failed", failure);
        }
    }

    /** Sets this week's missing goals, counts every goal not done, and pays the ones that are met. Returns how many paid. */
    synchronized int check(long nowMs) {
        int weekOf = week(nowMs);
        long from = weekStart(weekOf);
        goals.keySet().removeIf(key -> !key.endsWith(":" + weekOf));
        List<Clear> clears = null;
        List<Need> needs = null;
        Map<Long, Set<Integer>> wins = null;
        int paid = 0;
        for (GuildMeta meta : guilds.bots()) {
            Goal goal = goal(meta, weekOf, nowMs);
            if (goal == null || goal.done()) {
                continue;
            }
            Set<Integer> members = guilds.members(meta.guildId());
            int count = switch (goal.kind()) {
                case PQ_CLEARS -> countClears(clears == null ? clears = deeds.clears(from, nowMs + 1) : clears,
                        goal.detail(), members);
                case NEEDS_MET -> countNeeds(needs == null ? needs = deeds.needsEnded(from) : needs, from, members);
                case COLISEUM_WINS -> countWins(wins == null ? wins = deeds.coliseumWins(from, nowMs + 1) : wins, members);
            };
            if (count > goal.progress()) {
                store.progress(meta.guildId(), weekOf, count);
                goal = goal.withProgress(count);
            }
            if (goal.progress() >= goal.target() && store.markDone(meta.guildId(), weekOf, nowMs)) {
                goal = goal.finished();
                pay(goal);
                paid++;
            }
            goals.put(meta.guildId() + ":" + weekOf, goal);
        }
        return paid;
    }

    /** This week's goal of the guild, read or set (the same pick after a restart: seeded by guild and week). */
    private Goal goal(GuildMeta meta, int weekOf, long nowMs) {
        String key = meta.guildId() + ":" + weekOf;
        Goal goal = goals.get(key);
        if (goal != null) {
            return goal;
        }
        goal = store.load(meta.guildId(), weekOf);
        if (goal == null) {
            Goal picked = pick(meta, weekOf);
            if (picked == null) {
                return null;
            }
            store.insertIfAbsent(picked, nowMs);
            goal = store.load(meta.guildId(), weekOf);
            if (goal == null) {
                return null; // not written (no database): nothing is counted or paid
            }
            guilds.announce(meta.guildId(), "guild.goal.new", text(goal));
            guilds.notice(meta.guildId(), text(goal));
            log.info("guild goals: {} sets {} for week {}", guilds.name(meta.guildId()), text(goal), weekOf);
        }
        goals.put(key, goal);
        return goal;
    }

    /** A goal its band and members can meet, seeded by guild and week; null when none fits. */
    Goal pick(GuildMeta meta, int weekOf) {
        List<Goal> options = new ArrayList<>();
        for (Lobby lobby : lobbies()) {
            if (lobby.levelMin() <= meta.levelMax() && lobby.levelMax() >= meta.levelMin()) {
                options.add(new Goal(meta.guildId(), weekOf, Kind.PQ_CLEARS, lobby.id(),
                        population.config().GUILD_GOAL_CLEARS, 0, false));
            }
        }
        if (guilds.hasPlayer(meta.guildId())) {
            options.add(new Goal(meta.guildId(), weekOf, Kind.NEEDS_MET, "", population.config().GUILD_GOAL_NEEDS, 0,
                    false));
            if (meta.levelMax() >= COLISEUM_MIN_LEVEL) {
                options.add(new Goal(meta.guildId(), weekOf, Kind.COLISEUM_WINS, "",
                        population.config().GUILD_GOAL_WINS, 0, false));
            }
        }
        if (options.isEmpty()) {
            return null;
        }
        return options.get(new java.util.SplittableRandom(((long) meta.guildId() << 32) ^ weekOf).nextInt(options.size()));
    }

    /** The lobbies citizens run a party quest from (PQ_RUN_QUESTS), one a quest. */
    private List<Lobby> lobbies() {
        Set<String> quests = CitizenPartyQuests.quests(population.config());
        Map<String, Lobby> byId = new LinkedHashMap<>();
        for (PopulationContent.Region region : population.content().regions().values()) {
            for (Lobby lobby : region.lobbies()) {
                if (quests.contains(lobby.id())) {
                    byId.putIfAbsent(lobby.id(), lobby);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Clears of {@code questId} ("kpq" for KerningPQ, "balrog" for any Balrog battle) with a member in them. */
    static int countClears(List<Clear> clears, String questId, Set<Integer> members) {
        int count = 0;
        for (Clear clear : clears) {
            if (!questOf(clear.quest()).equals(questId)) {
                continue;
            }
            for (Clear.Member member : clear.members()) {
                if (members.contains(member.id())) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** The lobby id of an event script: KerningPQ -> kpq, BalrogBattle_Easy -> balrog. */
    static String questOf(String script) {
        String name = PopulationHooks.questName(script);
        return script != null && script.toLowerCase(Locale.ROOT).startsWith("balrog") ? "balrog" : name;
    }

    /** Requests met since {@code from} whose last hand-in was a member's. */
    static int countNeeds(List<Need> needs, long from, Set<Integer> members) {
        int count = 0;
        for (Need need : needs) {
            if (need.state() == Need.State.MET && need.closedAtMs() >= from && members.contains(need.fulfilledBy())) {
                count++;
            }
        }
        return count;
    }

    /** Matches with a member on the winning side. */
    static int countWins(Map<Long, Set<Integer>> wins, Set<Integer> members) {
        int count = 0;
        for (Set<Integer> winners : wins.values()) {
            for (int id : winners) {
                if (members.contains(id)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** GP, a guild-chat line, the paper. */
    private void pay(Goal goal) {
        int gp = population.config().GUILD_GOAL_GP;
        if (gp > 0) {
            guilds.gainGp(goal.guildId(), gp);
        }
        String name = guilds.name(goal.guildId());
        guilds.announce(goal.guildId(), "guild.goal.met", text(goal));
        if (name != null) {
            PaperDesk.file("town.guildgoal", 40, Map.of("guild", name, "goal", text(goal)));
        }
        log.info("guild goals: {} met {} ({} of {}), +{} GP", name, text(goal), goal.progress(), goal.target(), gp);
    }

    /** How the goal is said, short enough for a BBS title after "Goal: ": "6 KPQ clears", "4 needs met", "4 coliseum wins". */
    String text(Goal goal) {
        return switch (goal.kind()) {
            case PQ_CLEARS -> goal.target() + " " + label(goal.detail()) + " clears";
            case NEEDS_MET -> goal.target() + " needs met";
            case COLISEUM_WINS -> goal.target() + " coliseum wins";
        };
    }

    private String label(String questId) {
        for (Lobby lobby : lobbies()) {
            if (lobby.id().equals(questId)) {
                return lobby.name();
            }
        }
        return questId.toUpperCase(Locale.ROOT);
    }

    /** {@code !citizens goals check}: a count now, whatever the minute; how many goals it paid. */
    public int checkNow() {
        return on() ? check(System.currentTimeMillis()) : 0;
    }

    /** One line a guild with a goal this week, for !citizens goals. */
    public synchronized List<String> status(long nowMs) {
        int weekOf = week(nowMs);
        List<String> out = new ArrayList<>();
        for (GuildMeta meta : guilds.bots()) {
            Goal goal = goals.containsKey(meta.guildId() + ":" + weekOf) ? goals.get(meta.guildId() + ":" + weekOf)
                    : store.load(meta.guildId(), weekOf);
            if (goal != null) {
                out.add(guilds.name(meta.guildId()) + ": " + text(goal) + ", " + goal.progress() + "/" + goal.target()
                        + (goal.done() ? " DONE" : ""));
            }
        }
        if (out.isEmpty()) {
            out.add("guild goals: none set for week " + weekOf + (on() ? "" : " (GUILD_GOALS_ENABLED is off)"));
        }
        return out;
    }

    /** The server's guilds as GuildGoals sees them. */
    private static final class LiveGuilds implements Guilds {
        private final Population population;

        LiveGuilds(Population population) {
            this.population = population;
        }

        private Guild guild(int guildId) {
            return Server.getInstance().getGuild(guildId, population.world().config().WORLD);
        }

        @Override
        public Collection<GuildMeta> bots() {
            return population.social().guildMetas().values();
        }

        @Override
        public Set<Integer> members(int guildId) {
            Set<Integer> ids = new HashSet<>();
            Guild guild = guild(guildId);
            if (guild != null) {
                for (GuildCharacter member : guild.getMembers()) {
                    ids.add(member.getId());
                }
            }
            return ids;
        }

        @Override
        public boolean hasPlayer(int guildId) {
            Guild guild = guild(guildId);
            var world = server.living.LivingWorld.get();
            if (guild == null || world == null || world.directory() == null) {
                return false;
            }
            for (GuildCharacter member : guild.getMembers()) {
                int id = member.getId();
                if (!population.isCitizen(id) && !world.directory().isClaimed(id) && !world.directory().isCompanion(id)) {
                    return true; // no citizen, Resident or companion: a player
                }
            }
            return false;
        }

        @Override
        public String name(int guildId) {
            Guild guild = guild(guildId);
            return guild == null ? null : guild.getName();
        }

        @Override
        public void gainGp(int guildId, int gp) {
            if (guild(guildId) != null) { // loaded into the server's cache, where gainGP finds it
                Server.getInstance().gainGP(guildId, gp);
            }
        }

        @Override
        public void notice(int guildId, String goalText) {
            Guild guild = guild(guildId);
            if (guild == null || !population.isCitizen(guild.getLeaderId())) {
                return; // a player's guild board is the player's
            }
            int leaderId = guild.getLeaderId();
            String text = "Our goal this week is " + goalText + ". Every clear, hand-in or win by a member counts, so"
                    + " bring a friend along. If we make it the guild gets GP and climbs the board in the guild hall.";
            var executor = population.world().executor();
            Runnable post = () -> GuildGoalStore.postNotice(guildId, leaderId, GuildGoalStore.NOTICE_PREFIX + goalText, text,
                    Server.getInstance().getCurrentTime());
            if (executor == null) {
                post.run();
            } else {
                executor.submit("guild.notice " + guildId, post);
            }
        }

        @Override
        public void announce(int guildId, String trigger, String goalText) {
            CitizenSession speaker = null;
            for (CitizenSession session : population.presence().sessions()) {
                var agent = session.virtual ? null : session.agent();
                if (agent != null && agent.getGuildId() == guildId
                        && (speaker == null || agent.getGuildRank() < speaker.agent().getGuildRank())) {
                    speaker = session;
                }
            }
            if (speaker == null) {
                return;
            }
            String line = population.chatter().compose(ChatterEngine.Event.of(trigger, speaker.mapId, speaker.channel)
                    .self(speaker).slot("goal", goalText));
            if (line != null) {
                population.chatter().guildSay(speaker, line, 2_000L, null);
            }
        }
    }
}
