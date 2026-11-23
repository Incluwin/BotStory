package server.living.population.coliseum;

import client.Character;
import config.PvpConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.living.LivingWorld;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.living.population.rivals.Crew;
import server.living.population.rivals.RivalChallenges;
import server.living.story.DuelGround;
import server.living.store.LivingStore;
import server.pvp.DuelField;
import server.pvp.DuelLog;
import server.pvp.DuelRegistry;
import server.pvp.DuelSim;
import server.pvp.Glicko2;
import server.pvp.PvpReference;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Team fights (docs/PVP_PLAN.md V6): a player and its companions against citizens, 2v2 or 3v3, best of three under
 * coliseum rules on a private arena floor. Two kinds: a team ladder match (Cesar finds a citizen team, from one guild
 * when he can, rated nearest the player's team) and a Crew War (the player's rival crew answers, and the result counts
 * on the crew's head-to-head as a challenge does). Team ratings are kept apart from 1v1 (a {@code TEAM_} book): every
 * fighter has its own, a team rates as the mean. The companions run the duel brain on the player's side and pick their
 * target with it (TeamTactics); they get no pay. Citizen teams play simulated team matches in the background, so the
 * team ladder moves while players are away.
 */
final class TeamLadder {
    private static final Logger log = LoggerFactory.getLogger(TeamLadder.class);

    enum Kind { TEAM, CREW_WAR, GUILD }

    /** A team match a player is in. */
    static final class TeamMatch {
        final Kind kind;
        final int humanId;
        final String humanName;
        final int accountId;
        /** The player first, then its companions; and the citizens, in the order they stand. */
        final List<Integer> side0;
        final List<Integer> side1;
        final List<Integer> companions;
        final String foeName;
        final Crew crew;
        final LadderRules.Bracket bracket;
        final double odds;
        final boolean forced;
        final long fee;
        final int arenaId;
        final long startedAtMs;
        Coliseum.Stage stage = Coliseum.Stage.ENTERING;
        DuelGround ground;
        DuelField field;
        volatile DuelField.Result result;
        LadderRules.Score score = LadderRules.Score.START;
        long nextAtMs;
        long deadlineMs;
        /** character id -> {dealt, taken, knocked out (1) at least once} over the match. */
        final Map<Integer, long[]> totals = new HashMap<>();
        int winnerSide = -1;
        String why = "";
        /** A guild match (N5.3): the player's guild, the foe's, and the player's guild mates on side 0. */
        int guildId;
        int foeGuildId;
        List<Integer> mates = List.of();

        TeamMatch(Kind kind, Character human, List<Integer> side0, List<Integer> companions, List<Integer> side1,
                  String foeName, Crew crew, LadderRules.Bracket bracket, double odds, boolean forced, long fee,
                  int arenaId, long now) {
            this.kind = kind;
            this.humanId = human.getId();
            this.humanName = human.getName();
            this.accountId = human.getAccountID();
            this.side0 = List.copyOf(side0);
            this.companions = List.copyOf(companions);
            this.side1 = List.copyOf(side1);
            this.foeName = foeName;
            this.crew = crew;
            this.bracket = bracket;
            this.odds = odds;
            this.forced = forced;
            this.fee = fee;
            this.arenaId = arenaId;
            this.startedAtMs = now;
        }

        int size() {
            return side0.size();
        }

        String label() {
            return kind == Kind.CREW_WAR ? "Crew War" : kind == Kind.GUILD ? "guild " + size() + "v" + size()
                    : size() + "v" + size();
        }
    }

    private record Request(int humanId, int size, Kind kind, boolean forced) {
    }

    private final Coliseum coliseum;
    private final LadderBook book;
    /** The guild book (docs/WORLD_SYSTEMS_PLAN.md N5.3): one rating a guild, its ids guild ids. */
    private final LadderBook guildBook;
    private final LadderStore store;
    private final Random random = new Random();
    private final Queue<Request> requests = new ConcurrentLinkedQueue<>();
    private final Map<Integer, TeamMatch> byHuman = new ConcurrentHashMap<>();
    /** "lowId:highId" of two citizen teams' first members -> when they last played a simulated team match. */
    private final Map<String, Long> simmed = new ConcurrentHashMap<>();

    /** {@code matches} books the matches in pvp_match; {@code ratings} keeps the team ratings (a TEAM_ store). */
    TeamLadder(Coliseum coliseum, LadderStore matches, LadderBook.Store ratings) {
        this(coliseum, matches, ratings, LadderStore.guildBook());
    }

    /** {@code guildRatings}: the guild book's store (N5.3). */
    TeamLadder(Coliseum coliseum, LadderStore matches, LadderBook.Store ratings, LadderBook.Store guildRatings) {
        this.coliseum = coliseum;
        this.store = matches;
        this.book = new LadderBook(ratings);
        this.guildBook = new LadderBook(guildRatings);
    }

    LadderBook book() {
        return book;
    }

    LadderBook guildBook() {
        return guildBook;
    }

    private boolean guildLadderOn() {
        return population().config().GUILD_LADDER_ENABLED;
    }

    private Population population() {
        return coliseum.population();
    }

    boolean idle() {
        return byHuman.isEmpty() && requests.isEmpty();
    }

    boolean inMatch(int characterId) {
        return byHuman.containsKey(characterId);
    }

    /** A citizen or companion fighting a team match. */
    boolean fighting(int characterId) {
        for (TeamMatch match : byHuman.values()) {
            if (match.side0.contains(characterId) || match.side1.contains(characterId)) {
                return true;
            }
        }
        return false;
    }

    /** Queues a team match of {@code size} (2 or 3), or a Crew War (its size is what both sides can field). */
    void request(Character human, int size, Kind kind, boolean forced) {
        if (human != null) {
            requests.add(new Request(human.getId(), size, kind, forced));
            coliseum.startTicker();
        }
    }

    /** The companions standing with {@code human} that could fight beside it now, at most two. */
    static List<Character> companionsHere(Character human) {
        List<Character> here = new ArrayList<>();
        LivingWorld world = LivingWorld.get();
        if (world == null || world.roster() == null) {
            return here;
        }
        for (LivingStore.RosterEntry entry : world.roster().roster(human)) {
            Character bot = entry.state() == LivingStore.RosterState.ACTIVE ? world.roster().companion(entry) : null;
            if (here.size() < TeamRules.MAX_SIZE - 1 && bot != null && bot.isAlive() && bot.getMap() == human.getMap()
                    && bot.getEventInstance() == null && !DuelRegistry.inDuel(bot.getId())
                    && (world.commitments() == null || !world.commitments().isCommitted(bot.getId()))) {
                here.add(bot);
            }
        }
        return here;
    }

    // ---- starting one ---------------------------------------------------------------------------------------------

    void step(long now) {
        for (Request request; (request = requests.poll()) != null; ) {
            try {
                begin(request, now);
            } catch (RuntimeException failure) {
                log.warn("Team ladder: starting a match for {} failed", request.humanId(), failure);
            }
        }
        for (TeamMatch match : new ArrayList<>(byHuman.values())) {
            try {
                stepOne(match, now);
            } catch (RuntimeException failure) {
                log.warn("Team ladder: {} against {} failed; closing it", match.humanName, match.foeName, failure);
                abort(match, "something went wrong", true, now);
            }
        }
    }

    private void begin(Request request, long now) {
        Character human = Coliseum.online(request.humanId());
        if (human == null || !Coliseum.on() && !request.forced()) {
            return;
        }
        if (coliseum.busy(human.getId())) {
            human.dropMessage(5, "You are in a match already.");
            return;
        }
        Optional<LadderRules.Bracket> bracket = LadderRules.Bracket.of(human.getLevel());
        if (bracket.isEmpty() || !human.isAlive() || human.getEventInstance() != null || human.getClient() == null) {
            human.dropMessage(5, "Cesar: team fights are for the living, Lv 30 and up, and not from inside an event.");
            return;
        }
        List<Character> companions = companionsHere(human);
        int fieldable = TeamRules.fieldable(companions.size());
        if (fieldable < TeamRules.MIN_SIZE && request.kind() != Kind.GUILD) {
            human.dropMessage(5, "Cesar: a team fight needs a companion standing with you.");
            return;
        }
        int channel = human.getClient().getChannel();
        Crew crew = null;
        List<Integer> foes;
        String foeName;
        int size;
        List<Integer> guildSide = null;
        int foeGuildId = 0;
        if (request.kind() == Kind.GUILD) {
            // N5.3: the player fights for its guild, guild mates filling the side before its own companions
            if (!guildLadderOn() && !request.forced()) {
                human.dropMessage(5, "Cesar: guild matches are not on yet.");
                return;
            }
            int guildId = human.getGuildId();
            if (guildId <= 0 || human.getGuild() == null) {
                human.dropMessage(5, "Cesar: join a guild first, then fight for it.");
                return;
            }
            size = Math.max(TeamRules.MIN_SIZE, Math.min(TeamRules.MAX_SIZE, request.size()));
            List<TeamRules.Member> free = freeMembers(channel, human.getId(), bracket.get(), now);
            List<Integer> companionIds = new ArrayList<>();
            companions.forEach(c -> companionIds.add(c.getId()));
            Optional<List<Integer>> mine = TeamRules.guildSide(human.getId(), human.getLevel(), guildId, size, free,
                    companionIds);
            if (mine.isEmpty()) {
                human.dropMessage(5, "Cesar: " + human.getGuild().getName() + " can't field " + size + " in your bracket"
                        + " here right now: bring guild mates or companions on this channel.");
                return;
            }
            guildSide = mine.get();
            List<TeamRules.Member> others = new ArrayList<>(free);
            List<Integer> taken = guildSide;
            others.removeIf(member -> taken.contains(member.characterId()));
            Optional<TeamRules.Team> team = TeamRules.guildFoe(human.getLevel(),
                    book.teamRating(guildSide, bracket.get(), now), size, guildId, others);
            if (team.isEmpty()) {
                human.dropMessage(5, "Cesar: no other guild can send " + size + " in your bracket on this channel right"
                        + " now. Try again in a minute.");
                return;
            }
            foes = team.get().members();
            foeName = team.get().name();
            for (TeamRules.Member member : others) {
                if (member.characterId() == foes.get(0)) {
                    foeGuildId = member.guildId();
                }
            }
        } else if (request.kind() == Kind.CREW_WAR) {
            crew = population().rivals() == null ? null : population().rivals().crewOfMain(human.getId());
            if (crew == null) {
                human.dropMessage(5, "Cesar: no crew has a grudge with you yet. Make some rivals first.");
                return;
            }
            List<Integer> free = new ArrayList<>();
            if (coliseum.free(population().presence().session(crew.leaderId()), channel, human.getId())) {
                free.add(crew.leaderId()); // the leader speaks for the crew, so it fights when it can
            }
            for (int member : crew.members()) {
                if (member != crew.leaderId()
                        && coliseum.free(population().presence().session(member), channel, human.getId())) {
                    free.add(member);
                }
            }
            size = Math.min(fieldable, free.size());
            if (size < TeamRules.MIN_SIZE) {
                human.dropMessage(5, "Cesar: " + crew.name() + " can't send " + TeamRules.MIN_SIZE + " on this channel"
                        + " right now. Try again later.");
                return;
            }
            foes = new ArrayList<>(free.subList(0, size));
            foeName = crew.name();
        } else {
            size = Math.max(TeamRules.MIN_SIZE, Math.min(TeamRules.MAX_SIZE, request.size()));
            if (size > fieldable) {
                human.dropMessage(5, "Cesar: a " + size + "v" + size + " needs " + (size - 1) + " of your companions"
                        + " standing with you.");
                return;
            }
            List<Integer> mine = new ArrayList<>();
            mine.add(human.getId());
            companions.subList(0, size - 1).forEach(c -> mine.add(c.getId()));
            Optional<TeamRules.Team> team = TeamRules.pick(human.getLevel(),
                    book.teamRating(mine, bracket.get(), now), size, freeMembers(channel, human.getId(), bracket.get(),
                            now));
            if (team.isEmpty()) {
                human.dropMessage(5, "Cesar: I can't find " + size + " in your bracket free on this channel right now."
                        + " Try again in a minute.");
                return;
            }
            foes = team.get().members();
            foeName = team.get().name();
        }
        List<Integer> side0 = new ArrayList<>();
        List<Integer> companionIds = new ArrayList<>();
        List<Integer> mates = new ArrayList<>();
        if (guildSide != null) {
            side0.addAll(guildSide);
            for (Character companion : companions) {
                if (guildSide.contains(companion.getId())) {
                    companionIds.add(companion.getId());
                }
            }
            for (int id : guildSide) {
                if (id != human.getId() && !companionIds.contains(id)) {
                    mates.add(id);
                }
            }
        } else {
            side0.add(human.getId());
            companions.subList(0, size - 1).forEach(c -> {
                side0.add(c.getId());
                companionIds.add(c.getId());
            });
        }
        long fee = request.kind() == Kind.CREW_WAR || request.forced() || human.isGM() ? 0L
                : Coliseum.fee(human.getLevel());
        if (fee > 0 && human.getMeso() < fee) {
            human.dropMessage(5, "Cesar: the fee is " + mesos(fee) + " mesos.");
            return;
        }
        if (fee > 0) {
            try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_FEE)) {
                human.gainMeso((int) -fee, true);
            }
        }
        Glicko2.Rating ours = guildSide != null ? guildBook.rating(human.getGuildId(), bracket.get(), now)
                : book.teamRating(side0, bracket.get(), now);
        Glicko2.Rating theirs = guildSide != null ? guildBook.rating(foeGuildId, bracket.get(), now)
                : book.teamRating(foes, bracket.get(), now);
        double odds = Glicko2.winChance(ours, theirs);
        TeamMatch match = new TeamMatch(request.kind(), human, side0, companionIds, foes, foeName, crew, bracket.get(),
                odds, request.forced(), fee, coliseum.nextArenaId(), now);
        if (guildSide != null) {
            match.guildId = human.getGuildId();
            match.foeGuildId = foeGuildId;
            match.mates = List.copyOf(mates);
        }
        for (int foe : foes) {
            population().presence().hold(foe, now + Coliseum.HOLD_MS);
        }
        for (int mate : mates) {
            population().presence().hold(mate, now + Coliseum.HOLD_MS);
        }
        byHuman.put(human.getId(), match);
        human.dropMessage(5, request.kind() == Kind.CREW_WAR
                ? "Cesar: " + crew.name() + " answers your Crew War with " + names(foes) + ". To the arena!"
                : request.kind() == Kind.GUILD
                ? "Cesar: " + human.getGuild().getName() + " (" + names(side0) + ") against " + foeName + " ("
                + names(foes) + "), guilds rated " + Math.round(ours.rating()) + " and " + Math.round(theirs.rating())
                + ". To the arena!"
                : "Cesar: your " + match.label() + " is against " + foeName + " (" + names(foes) + "), rated "
                + Math.round(theirs.rating()) + ". To the arena!");
        log.info("Team ladder: {} {} {} against {} {} in {}, team ratings {} vs {}, odds {}{}", match.label(),
                human.getName(), names(side0), foeName, names(foes), bracket.get(), Math.round(ours.rating()),
                Math.round(theirs.rating()), String.format(Locale.ROOT, "%.2f", odds), request.forced() ? " (GM)" : "");
    }

    /** The free citizens on {@code channel} in {@code bracket} as team members: guild and team rating. */
    private List<TeamRules.Member> freeMembers(int channel, int humanId, LadderRules.Bracket bracket, long now) {
        List<TeamRules.Member> members = new ArrayList<>();
        for (CitizenSession session : population().presence().sessions()) {
            if (!coliseum.free(session, channel, humanId)) {
                continue;
            }
            Character agent = session.agent();
            if (LadderRules.Bracket.of(agent.getLevel()).orElse(null) != bracket) {
                continue;
            }
            var guild = agent.getGuild();
            members.add(new TeamRules.Member(agent.getId(), agent.getLevel(), agent.getGuildId(),
                    guild == null ? null : guild.getName(), book.rating(agent.getId(), bracket, now)));
        }
        return members;
    }

    // ---- the step -------------------------------------------------------------------------------------------------

    private void stepOne(TeamMatch match, long now) {
        if (match.stage == Coliseum.Stage.AFTER) {
            if (now >= match.nextAtMs) {
                close(match);
            }
            return;
        }
        Character human = Coliseum.online(match.humanId);
        if (human == null) {
            if (match.stage == Coliseum.Stage.ENTERING) {
                abort(match, match.humanName + " logged out", false, now);
            } else {
                forfeit(match, now, "left the arena");
            }
            return;
        }
        List<Character> ours = characters(match.side0);
        List<Character> theirs = characters(match.side1);
        if (ours.size() != match.side0.size()) {
            abort(match, "a companion is gone", true, now);
            return;
        }
        if (theirs.size() != match.side1.size()) {
            abort(match, "one of " + match.foeName + " is gone", true, now);
            return;
        }
        switch (match.stage) {
            case ENTERING -> enter(match, human, ours, theirs, now);
            case INTRO, BETWEEN -> {
                if (match.ground.left(human.getId()) || human.getMap() != match.ground.map()) {
                    forfeit(match, now, "left the arena");
                } else if (anyLeft(match, ours) || anyLeft(match, theirs)) {
                    abort(match, "someone left the arena", true, now);
                } else if (now >= match.nextAtMs) {
                    round(match, human, ours, theirs, now);
                }
            }
            case FIGHTING -> fighting(match, human, now);
            case AFTER -> { } // handled above
        }
    }

    private boolean anyLeft(TeamMatch match, List<Character> side) {
        for (Character chr : side) {
            if (match.ground.left(chr.getId()) || chr.getMap() != match.ground.map()) {
                return true;
            }
        }
        return false;
    }

    /** The match's companions and citizens as loaded characters, in order (a missing one is left out). */
    private List<Character> characters(List<Integer> ids) {
        List<Character> out = new ArrayList<>();
        for (int id : ids) {
            Character chr = Coliseum.online(id);
            if (chr == null) {
                CitizenSession session = population().presence().session(id);
                chr = session == null ? null : session.agent();
            }
            if (chr != null) {
                out.add(chr);
            }
        }
        return out;
    }

    private void enter(TeamMatch match, Character human, List<Character> ours, List<Character> theirs, long now) {
        if (!human.isAlive() || human.getEventInstance() != null || human.getClient() == null) {
            abort(match, human.getName() + " cannot go in right now", true, now);
            return;
        }
        DuelGround ground = DuelGround.open(human, match.arenaId, Coliseum.ARIANT);
        if (ground == null) {
            abort(match, "no arena floor is free", true, now);
            return;
        }
        match.ground = ground;
        for (int i = 0; i < ours.size(); i++) {
            ground.enter(ours.get(i), TeamRules.spot(0, i));
        }
        for (int i = 0; i < theirs.size(); i++) {
            ground.enter(theirs.get(i), TeamRules.spot(1, i));
        }
        CitizenSession speaker = population().presence().session(match.side1.get(0));
        if (speaker != null) {
            if (match.kind == Kind.CREW_WAR) {
                coliseum.say(speaker, "crewwar.intro", match.humanName, Map.of("crew", match.foeName,
                        "record", recordOf(match), "size", Integer.toString(match.size())));
            } else {
                coliseum.say(speaker, "team.intro", match.humanName, Map.of("team", match.foeName,
                        "size", Integer.toString(match.size())));
            }
        }
        match.stage = Coliseum.Stage.INTRO;
        match.nextAtMs = now + Coliseum.INTRO_MS;
    }

    private String recordOf(TeamMatch match) {
        RivalChallenges challenges = population().rivals() == null ? null : population().rivals().challenges();
        return challenges == null || match.crew == null ? "0-0" : challenges.recordOf(match.crew, match.humanId);
    }

    private void round(TeamMatch match, Character human, List<Character> ours, List<Character> theirs, long now) {
        PvpConfig pvp = YamlConfig.config.pvp;
        DuelField.Rules rules = new DuelField.Rules(pvp.COLISEUM_ROUND_SECONDS, pvp.COLISEUM_TONICS,
                pvp.COLISEUM_TONIC_PERCENT, pvp.COLISEUM_TONIC_GAP_MS);
        try {
            DuelField field = DuelRegistry.start(match.ground.map(), ours, theirs, rules);
            match.field = field;
            match.result = null;
            field.onEnd(result -> match.result = result);
        } catch (IllegalStateException refused) {
            log.warn("Team ladder: round {} of {} against {} would not start: {}", match.score.played() + 1,
                    match.humanName, match.foeName, refused.getMessage());
            abort(match, "the round would not start", true, now);
            return;
        }
        match.stage = Coliseum.Stage.FIGHTING;
        match.deadlineMs = now + (pvp.PVP_COUNTDOWN_SECONDS + pvp.COLISEUM_ROUND_SECONDS) * 1000L
                + Coliseum.ROUND_SLACK_MS;
        human.dropMessage(5, match.label() + " round " + (match.score.played() + 1) + " of " + LadderRules.ROUNDS
                + " against " + match.foeName + ". Score " + match.score + ".");
    }

    private void fighting(TeamMatch match, Character human, long now) {
        DuelField.Result result = match.result;
        if (result == null) {
            if (now >= match.deadlineMs && match.field != null) {
                match.field.stop(); // the result comes back through onEnd
            }
            return;
        }
        boolean humanLeft = false;
        for (DuelLog.Row row : result.rows()) {
            long[] total = match.totals.computeIfAbsent(row.charId(), id -> new long[3]);
            total[0] += row.dealt();
            total[1] += row.taken();
            total[2] |= row.ko() ? 1 : 0;
            humanLeft |= row.charId() == match.humanId && row.left();
        }
        if (humanLeft) {
            forfeit(match, now, "left the arena");
            return;
        }
        match.score = match.score.after(result.winnerSide());
        String roundLine = result.winnerSide() == 0 ? "Your team takes round " + match.score.played()
                : result.winnerSide() == 1 ? match.foeName + " takes round " + match.score.played()
                : "Round " + match.score.played() + " is a draw";
        human.dropMessage(5, roundLine + ". Score " + match.score + ".");
        if (match.score.over()) {
            finish(match, match.score.winner(), "best of three " + match.score, now);
            return;
        }
        CitizenSession speaker = population().presence().session(match.side1.get(0));
        if (speaker != null && result.winnerSide() >= 0) {
            coliseum.say(speaker, result.winnerSide() == 1 ? "ladder.round.won" : "ladder.round.lost", match.humanName,
                    Map.of());
        }
        match.stage = Coliseum.Stage.BETWEEN;
        match.nextAtMs = now + Coliseum.BETWEEN_ROUNDS_MS;
    }

    private void forfeit(TeamMatch match, long now, String why) {
        if (match.field != null && match.result == null) {
            match.field.stop();
        }
        log.info("Team ladder: {} {} against {}: a forfeit", match.humanName, why, match.foeName);
        finish(match, 1, "forfeit: " + why, now);
    }

    /** Decided: team ratings, pay, the book (a Crew War on the crew's record too), the afterword. */
    private void finish(TeamMatch match, int winnerSide, String why, long now) {
        match.winnerSide = winnerSide;
        match.why = why;
        boolean forfeited = why.startsWith("forfeit");
        double score0 = winnerSide == 0 ? 1d : winnerSide == 1 ? 0d : 0.5d;
        List<LadderBook.Change> changes = book.recordTeams(match.side0, match.side1, match.bracket, score0, now);
        LadderBook.Change mine = changes.get(0);
        LadderBook.Change[] guilds = match.kind == Kind.GUILD && match.foeGuildId > 0
                ? guildBook.record(match.guildId, match.foeGuildId, match.bracket, score0, now) : null;
        Character human = Coliseum.online(match.humanId);
        if (human != null && guilds != null) {
            human.dropMessage(5, "Guild rating " + Math.round(guilds[0].before().rating().rating()) + " -> "
                    + Math.round(guilds[0].after().rating().rating()) + " (" + match.bracket.label + ", "
                    + guilds[0].after().wins() + "-" + guilds[0].after().losses() + ").");
        }
        if (human != null) {
            String verdict = winnerSide == 0 ? "You beat " + match.foeName
                    : winnerSide == 1 ? match.foeName + " beat you" : "You drew with " + match.foeName;
            human.dropMessage(5, match.label() + ": " + verdict + " (" + match.score + "). Team rating "
                    + Math.round(mine.before().rating().rating()) + " -> " + Math.round(mine.after().rating().rating())
                    + " (" + match.bracket.label + ", " + mine.after().wins() + "-" + mine.after().losses() + ").");
            try {
                coliseum.payLadder(human, winnerSide, match.odds, forfeited, match.forced,
                        match.kind == Kind.CREW_WAR ? "crew war" : match.kind == Kind.GUILD ? "guild" : "team", now);
            } catch (RuntimeException failure) {
                log.warn("Team ladder: paying {} failed", match.humanName, failure);
            }
        }
        long seconds = (now - match.startedAtMs) / 1000L;
        String record = null;
        try {
            if (match.kind == Kind.CREW_WAR && population().rivals() != null) {
                List<RivalChallenges.CrewWarFighter> fighters = new ArrayList<>();
                match.side0.forEach(id -> fighters.add(crewWarFighter(match, id, 0)));
                match.side1.forEach(id -> fighters.add(crewWarFighter(match, id, 1)));
                record = population().rivals().challenges().crewWarFought(match.crew, match.humanId, match.humanName,
                        winnerSide, fighters, match.arenaId, seconds, "crew war " + why, match.odds,
                        match.side1.get(0), now);
            } else {
                List<LadderStore.Fighter> fighters = new ArrayList<>();
                match.side0.forEach(id -> fighters.add(ladderFighter(match, id, 0)));
                match.side1.forEach(id -> fighters.add(ladderFighter(match, id, 1)));
                store.insertMatch(match.kind == Kind.GUILD ? LadderStore.GUILD : LadderStore.TEAM, now, match.arenaId,
                        seconds, winnerSide, why, match.odds, fighters);
            }
        } catch (RuntimeException failure) {
            log.warn("Team ladder: booking {} against {} failed", match.humanName, match.foeName, failure);
        }
        CitizenSession speaker = population().presence().session(match.side1.get(0));
        if (speaker != null && !forfeited) {
            String trigger = match.kind == Kind.CREW_WAR
                    ? (winnerSide == 1 ? "crewwar.win" : winnerSide == 0 ? "crewwar.lose" : "ladder.draw")
                    : (winnerSide == 1 ? "team.win" : winnerSide == 0 ? "team.lose" : "ladder.draw");
            coliseum.say(speaker, trigger, match.humanName, Map.of("team", match.foeName, "crew", match.foeName,
                    "record", record == null ? "" : record));
        }
        log.info("Team ladder: {} {} against {} ended {} ({}), {} team rating {} -> {}", match.label(),
                match.humanName, match.foeName, winnerSide, why, match.humanName,
                Math.round(mine.before().rating().rating()), Math.round(mine.after().rating().rating()));
        match.stage = Coliseum.Stage.AFTER;
        match.nextAtMs = forfeited ? now : now + Coliseum.AFTER_MS;
    }

    private static RivalChallenges.CrewWarFighter crewWarFighter(TeamMatch match, int id, int side) {
        long[] total = match.totals.getOrDefault(id, new long[3]);
        return new RivalChallenges.CrewWarFighter(id, side, id == match.humanId, total[2] != 0, total[0], total[1]);
    }

    private static LadderStore.Fighter ladderFighter(TeamMatch match, int id, int side) {
        long[] total = match.totals.getOrDefault(id, new long[3]);
        return new LadderStore.Fighter(id, side, id == match.humanId, total[2] != 0, total[0], total[1]);
    }

    /** Called off: nothing rated or paid, the fee (when {@code refund}) back, everyone back. */
    private void abort(TeamMatch match, String why, boolean refund, long now) {
        if (match.field != null && match.result == null) {
            match.field.stop();
        }
        Character human = Coliseum.online(match.humanId);
        if (human != null) {
            human.dropMessage(5, "Cesar: the " + match.label() + " is off (" + why + ")." + (refund && match.fee > 0
                    ? " Here is your fee back." : ""));
            if (refund && match.fee > 0) {
                try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_FEE)) {
                    human.gainMeso((int) match.fee, true);
                }
            }
        }
        log.info("Team ladder: {} {} against {} called off: {}", match.label(), match.humanName, match.foeName, why);
        close(match);
    }

    /** Everyone back where they stood; the companions follow again and the citizens are let go. */
    private void close(TeamMatch match) {
        byHuman.remove(match.humanId, match);
        if (match.ground != null) {
            match.ground.close(match.companions);
        }
        match.side1.forEach(id -> population().presence().release(id));
        match.mates.forEach(id -> population().presence().release(id));
    }

    void stop() {
        for (TeamMatch match : new ArrayList<>(byHuman.values())) {
            abort(match, "the coliseum closed", true, System.currentTimeMillis());
        }
    }

    List<String> status() {
        List<String> lines = new ArrayList<>();
        for (TeamMatch match : byHuman.values()) {
            lines.add(match.label() + ": " + match.humanName + " " + names(match.side0) + " against " + match.foeName
                    + " " + names(match.side1) + ", " + match.stage + ", score " + match.score);
        }
        return lines;
    }

    // ---- citizens' own team ladder --------------------------------------------------------------------------------

    /**
     * Up to {@code max} simulated best-of-three team matches between free citizen teams (any channel): guild teams
     * first, then pickup teams, each against the team of its size rated nearest it that its first member's team has
     * not played in the last hour. Returns how many were played.
     */
    int simulate(int max, long now) {
        if (max <= 0) {
            return 0;
        }
        simmed.values().removeIf(at -> now - at > Coliseum.SIM_REPEAT_MS);
        Map<LadderRules.Bracket, List<Character>> byBracket = new HashMap<>();
        for (CitizenSession session : population().presence().sessions()) {
            if (coliseum.free(session, 0, 0)) {
                Character agent = session.agent();
                LadderRules.Bracket.of(agent.getLevel())
                        .ifPresent(b -> byBracket.computeIfAbsent(b, k -> new ArrayList<>()).add(agent));
            }
        }
        PvpConfig pvp = YamlConfig.config.pvp;
        PvpReference reference = PvpReference.get();
        int played = 0;
        for (Map.Entry<LadderRules.Bracket, List<Character>> entry : byBracket.entrySet()) {
            int size = TeamRules.MIN_SIZE + random.nextInt(TeamRules.MAX_SIZE - TeamRules.MIN_SIZE + 1);
            List<List<Character>> teams = teams(entry.getValue(), size, random);
            Set<Integer> used = new HashSet<>();
            LadderRules.Bracket bracket = entry.getKey();
            for (List<Character> a : teams) {
                if (played >= max) {
                    return played;
                }
                if (used.contains(a.get(0).getId())) {
                    continue;
                }
                double ratingA = book.teamRating(ids(a), bracket, now).rating();
                List<Character> foe = null;
                double gap = Double.MAX_VALUE;
                for (List<Character> b : teams) {
                    int key = b.get(0).getId();
                    if (b == a || used.contains(key)
                            || simmed.containsKey(Coliseum.pairKey(a.get(0).getId(), key))) {
                        continue;
                    }
                    double g = Math.abs(book.teamRating(ids(b), bracket, now).rating() - ratingA);
                    if (g < gap) {
                        gap = g;
                        foe = b;
                    }
                }
                if (foe == null) {
                    continue;
                }
                List<DuelSim.Fighter> simA = new ArrayList<>();
                List<DuelSim.Fighter> simB = new ArrayList<>();
                a.forEach(chr -> simA.add(DuelSim.fighterOf(chr, reference, pvp)));
                foe.forEach(chr -> simB.add(DuelSim.fighterOf(chr, reference, pvp)));
                int winner = simulatedMatch(simA, simB, pvp, random);
                double scoreA = winner == 0 ? 1d : winner == 1 ? 0d : 0.5d;
                book.recordTeams(ids(a), ids(foe), bracket, scoreA, now);
                int guildA = guildOf(a);
                int guildB = guildOf(foe);
                if (guildLadderOn() && guildA > 0 && guildB > 0 && guildA != guildB) {
                    guildBook.record(guildA, guildB, bracket, scoreA, now); // two guild teams: the guild book (N5.3)
                }
                used.add(a.get(0).getId());
                used.add(foe.get(0).getId());
                simmed.put(Coliseum.pairKey(a.get(0).getId(), foe.get(0).getId()), now);
                played++;
            }
        }
        return played;
    }

    /** The guild every member of {@code team} is in, or 0 for a pickup team. */
    static int guildOf(List<Character> team) {
        int guildId = team.isEmpty() ? 0 : team.get(0).getGuildId();
        for (Character chr : team) {
            if (chr.getGuildId() != guildId) {
                return 0;
            }
        }
        return Math.max(0, guildId);
    }

    /** {@code pool} cut into teams of {@code size}: one a guild that has as many, then the rest as pickup teams. */
    static List<List<Character>> teams(List<Character> pool, int size, Random random) {
        List<Character> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, random);
        Map<Integer, List<Character>> guilds = new LinkedHashMap<>();
        List<Character> rest = new ArrayList<>();
        for (Character chr : shuffled) {
            if (chr.getGuildId() > 0) {
                guilds.computeIfAbsent(chr.getGuildId(), id -> new ArrayList<>()).add(chr);
            } else {
                rest.add(chr);
            }
        }
        List<List<Character>> teams = new ArrayList<>();
        for (List<Character> guild : guilds.values()) {
            if (guild.size() >= size) {
                teams.add(new ArrayList<>(guild.subList(0, size)));
                rest.addAll(guild.subList(size, guild.size()));
            } else {
                rest.addAll(guild);
            }
        }
        for (int i = 0; i + size <= rest.size(); i += size) {
            teams.add(new ArrayList<>(rest.subList(i, i + size)));
        }
        return teams;
    }

    /** A best of three between two sim teams under the coliseum's round length: 0 (a), 1 (b) or -1 (a draw). */
    static int simulatedMatch(List<DuelSim.Fighter> a, List<DuelSim.Fighter> b, PvpConfig pvp, Random random) {
        LadderRules.Score score = LadderRules.Score.START;
        while (!score.over()) {
            score = score.after(DuelSim.teamRound(a, b, pvp, random, pvp.COLISEUM_ROUND_SECONDS).winner());
        }
        return score.winner();
    }

    private static List<Integer> ids(List<Character> team) {
        List<Integer> out = new ArrayList<>();
        team.forEach(chr -> out.add(chr.getId()));
        return out;
    }

    private static String names(List<Integer> ids) {
        Set<String> names = new LinkedHashSet<>();
        for (int id : ids) {
            Character chr = Coliseum.online(id);
            if (chr == null) {
                CitizenSession session = Population.get() == null ? null : Population.get().presence().session(id);
                chr = session == null ? null : session.agent();
            }
            names.add(chr == null ? "#" + id : chr.getName());
        }
        return String.join(", ", names);
    }

    private static String mesos(long amount) {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount);
    }
}
