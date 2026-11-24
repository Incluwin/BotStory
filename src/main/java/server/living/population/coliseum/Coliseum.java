package server.living.population.coliseum;

import client.BotClient;
import client.Character;
import config.PvpConfig;
import config.YamlConfig;
import constants.id.ItemId;
import client.inventory.manipulator.InventoryManipulator;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.living.population.ChatterEngine;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.PersonMemory;
import server.living.population.Population;
import server.living.population.PresenceScheduler;
import server.living.resident.LivingTalk;
import server.living.story.DuelGround;
import server.pvp.DuelField;
import server.pvp.DuelLog;
import server.pvp.DuelRegistry;
import server.pvp.DuelSim;
import server.pvp.Glicko2;
import server.pvp.PvpGrindMinutes;
import server.pvp.PvpReference;
import tools.PacketCreator;

import java.awt.Point;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;

/**
 * The coliseum ladder (docs/PVP_PLAN.md V4.1-V4.2). Cesar, the Arena Master on Ariant's main street, matches a human
 * Lv 30+ with the free citizen in its bracket, on its channel, within 5 levels, whose Glicko-2 rating is nearest
 * ({@link LadderRules#pick}); the human pays 20 mesos a level ({@link MesoAccount#PVP_FEE}). They fight best of three
 * 90 s rounds on a private copy of a Battle Arena floor under coliseum rules (arena tonics only, {@link DuelField.Rules}),
 * then both go back where they stood. Both ratings move ({@link LadderBook}), the human is paid
 * ({@link LadderRules#win}/{@link LadderRules#loss}) and the match is booked in {@code pvp_match} (kind LADDER).
 *
 * <p>Citizens have ratings too: each social minute {@code COLISEUM_SIM_PER_MINUTE} pairs of free citizens play a
 * simulated best of three ({@link DuelSim}), so the ladder moves while nobody watches.
 *
 * <p>Threads: the NPC window, the GM command and the minute only queue work; every match changes state on this class's
 * own one-second step, never under a character's lock. A fighter who leaves the arena forfeits; a citizen that is
 * gone by then (logged out) voids the match and the fee comes back.
 */
public final class Coliseum {
    private static final Logger log = LoggerFactory.getLogger(Coliseum.class);

    static final int ARIANT = 260000000;
    /** The Battle Arena floors (980010101/201/301); a match fights on a private copy of one, in turn. */
    static final int[] ARENAS = {980010101, 980010201, 980010301};
    /** Where the two stand on the arena's bottom floor (its footholds run x -920..312 at y about 255). */
    static final Point HUMAN_SPOT = new Point(-560, 230);
    static final Point CITIZEN_SPOT = new Point(-180, 230);
    static final long STEP_MS = 1_000L;
    /** The opponent's line, then this long before round one: a fighter mid-round says nothing. */
    static final long INTRO_MS = 4_000L;
    static final long BETWEEN_ROUNDS_MS = 5_000L;
    /** After the last round: the afterword is seen, then everyone goes back. */
    static final long AFTER_MS = 5_000L;
    /** A round that has not ended this long past its clock is stopped. */
    static final long ROUND_SLACK_MS = 15_000L;
    /** A citizen in a match is held this long at most (released when the match ends). */
    static final long HOLD_MS = 10 * 60_000L;
    /** Two citizens play each other's simulated match at most once in this long. */
    static final long SIM_REPEAT_MS = 60 * 60_000L;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    enum Stage { ENTERING, INTRO, FIGHTING, BETWEEN, AFTER }

    /** A human's ladder match. */
    static final class Match {
        final int humanId;
        final String humanName;
        final int accountId;
        final int citizenId;
        final String citizenName;
        final LadderRules.Bracket bracket;
        final double odds;
        final boolean forced;
        final long fee;
        final int arenaId;
        final long startedAtMs;
        Stage stage = Stage.ENTERING;
        DuelGround ground;
        DuelField field;
        volatile DuelField.Result result;
        LadderRules.Score score = LadderRules.Score.START;
        long nextAtMs;
        long deadlineMs;
        long humanDealt;
        long humanTaken;
        boolean humanKo;
        boolean citizenKo;
        int winnerSide = -1;
        String why = "";

        Match(Character human, CitizenSession citizen, LadderRules.Bracket bracket, double odds, boolean forced, long fee,
              int arenaId, long now) {
            this.humanId = human.getId();
            this.humanName = human.getName();
            this.accountId = human.getAccountID();
            this.citizenId = citizen.characterId;
            this.citizenName = citizen.agent().getName();
            this.bracket = bracket;
            this.odds = odds;
            this.forced = forced;
            this.fee = fee;
            this.arenaId = arenaId;
            this.startedAtMs = now;
        }
    }

    /** A human asking for a match (from Cesar's window or {@code !pvp coliseum}); chosen 0 means "find one". */
    private record Request(int humanId, int chosenCitizenId, boolean forced) { }

    private final Population population;
    private final LadderBook book;
    private final LadderStore store;
    private final ColiseumNpcs npcs;
    private final Quartermaster quartermaster = new Quartermaster();
    private final Gallery gallery = new Gallery(this);
    private final ColiseumBoard board;
    private final Tournament tournament;
    private final TeamLadder teams;
    private final SelfPlay selfPlay;
    private final Random random = new Random();
    private final Queue<Request> requests = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Match> byHuman = new ConcurrentHashMap<>();
    /** account id -> {day index, matches paid that day, wins paid EXP that day} */
    private final Map<Integer, long[]> days = new ConcurrentHashMap<>();
    /** "lowId:highId" -> when those two last played a simulated match. */
    private final Map<String, Long> simmed = new ConcurrentHashMap<>();
    private final Object tickerLock = new Object();
    private ScheduledFuture<?> ticker;
    private int nextArena;

    public Coliseum(Population population) {
        this(population, new LadderStore());
    }

    Coliseum(Population population, LadderStore store) {
        this.population = population;
        this.store = store;
        this.book = new LadderBook(store);
        this.board = new ColiseumBoard(book);
        this.npcs = new ColiseumNpcs(population);
        this.tournament = new Tournament(this, new TournamentStore(), store);
        this.teams = new TeamLadder(this, store, new LadderStore(LadderStore.TEAM_PREFIX));
        this.selfPlay = new SelfPlay(this);
        this.board.tournament(tournament::boardText);
    }

    /** Two citizens' decided match edges them toward rivalry (docs/WORLD_SYSTEMS_PLAN.md N2.1); a draw is none. */
    void matched(int aId, int bId, int winnerSide) {
        if (winnerSide >= 0 && population.ties() != null) {
            population.ties().matched(winnerSide == 0 ? aId : bId, winnerSide == 0 ? bId : aId);
        }
    }

    Population population() {
        return population;
    }

    /** A human in a ladder match of their own. */
    boolean inMatch(int characterId) {
        return byHuman.containsKey(characterId);
    }

    /** A human already in some match: a ladder or team match, a gallery seat, the tournament or any duel. */
    boolean busy(int characterId) {
        return byHuman.containsKey(characterId) || gallery.watching(characterId) || DuelRegistry.inDuel(characterId)
                || tournament.busy(characterId) || teams.inMatch(characterId);
    }

    /**
     * V6: queues a team fight for {@code human}: a team ladder match of {@code size} (2 or 3, {@code crewWar} false)
     * or a Crew War against its rival crew. {@code forced} lets a GM fight with the coliseum off, for no fee.
     */
    public void teamRequest(Character human, int size, boolean crewWar, boolean forced) {
        teams.request(human, size, crewWar ? TeamLadder.Kind.CREW_WAR : TeamLadder.Kind.TEAM, forced);
    }

    /**
     * N5.3: queues a guild match of {@code size} for {@code human}: it fights for its guild, guild mates filling its
     * side before its companions, against another guild's team, rated in the guild book. {@code forced}: a GM's.
     */
    public void guildRequest(Character human, int size, boolean forced) {
        teams.request(human, size, TeamLadder.Kind.GUILD, forced);
    }

    /** N5.3: the guild book's top {@code limit} of {@code human}'s bracket (or Veteran), for {@code !pvp coliseum guildtop}. */
    public List<String> guildTop(Character human, int limit) {
        LadderRules.Bracket bracket = LadderRules.Bracket.of(human == null ? 60 : human.getLevel())
                .orElse(LadderRules.Bracket.VETERAN);
        List<String> lines = new ArrayList<>();
        lines.add("Guild book, " + bracket.label + ":");
        int place = 1;
        for (LadderBook.Ranked ranked : teams.guildBook().top(bracket, limit)) {
            lines.add(place++ + ". " + ranked.name() + " " + Math.round(ranked.row().rating().rating()) + " ("
                    + ranked.row().wins() + "-" + ranked.row().losses() + ")");
        }
        return lines;
    }

    /** V6: the team matches being fought, for {@code !pvp coliseum status}. */
    public List<String> teamStatus() {
        return teams.status();
    }

    /** V6: {@code !pvp coliseum teamsim [n]}: n simulated citizen team matches now. */
    public int simulateTeams(int matches) {
        return teams.simulate(matches, System.currentTimeMillis());
    }

    /** A human watching a gallery bout. */
    boolean watchingGallery(int characterId) {
        return gallery.watching(characterId);
    }

    /** The next arena floor in turn (1, 2, 3, 1, ...). */
    synchronized int nextArenaId() {
        return ARENAS[Math.floorMod(nextArena++, ARENAS.length)];
    }

    public static boolean on() {
        PvpConfig pvp = YamlConfig.config.pvp;
        return pvp.PVP_ENABLED && pvp.COLISEUM_ENABLED;
    }

    LadderBook book() {
        return book;
    }

    /** The ladder's pages (V4.4), as the F1 Bulletin Board's "Coliseum" page shows them; {@code back} may be null. */
    public void board(Character player, int npc, Runnable back) {
        board.show(player, npc, back);
    }

    /**
     * Watch a citizen bout from the gallery (V4.4): {@code bracketWord} rookie, veteran or champion, or null for the
     * watcher's own bracket (else the busiest). {@code forced} lets a GM watch with the coliseum off.
     */
    public void watch(Character watcher, String bracketWord, boolean forced) {
        LadderRules.Bracket bracket = null;
        if (bracketWord != null) {
            for (LadderRules.Bracket each : LadderRules.Bracket.values()) {
                if (each.name().equalsIgnoreCase(bracketWord) || each.label.equalsIgnoreCase(bracketWord)) {
                    bracket = each;
                }
            }
        }
        gallery.request(watcher, bracket, forced);
        startTicker();
    }

    // ---- the minute -----------------------------------------------------------------------------------------------

    /** The social minute: Cesar stands (or goes when switched off), and citizens play their own ladder matches. */
    public void minute() {
        try {
            tournament.minute(System.currentTimeMillis()); // cancels a restart's leftover run even when off
            selfPlay.minute(System.currentTimeMillis()); // off-peak training bouts (docs/WORLD_SYSTEMS_PLAN.md I1.2)
            if (!on()) {
                if (npcs.any()) {
                    npcs.removeAll();
                }
                return;
            }
            npcs.ensure(true);
            long now = System.currentTimeMillis();
            int played = simulate(YamlConfig.config.pvp.COLISEUM_SIM_PER_MINUTE, now);
            int teamsPlayed = teams.simulate(YamlConfig.config.pvp.COLISEUM_TEAM_SIM_PER_MINUTE, now);
            if (played > 0 || teamsPlayed > 0) {
                log.debug("Coliseum: {} citizen ladder match(es), {} team match(es) simulated", played, teamsPlayed);
            }
        } catch (RuntimeException failure) {
            log.warn("Coliseum minute failed", failure);
        }
    }

    /** The population stops. */
    public void stop() {
        selfPlay.stop();
        tournament.stop();
        teams.stop();
        npcs.removeAll();
        gallery.stopAll("the arena closed");
        for (Match match : new ArrayList<>(byHuman.values())) {
            abort(match, "the arena closed", true, System.currentTimeMillis());
        }
    }

    // ---- Cesar ----------------------------------------------------------------------------------------------------

    /** A player clicked an NPC: Cesar opens the ladder (true); anything else is not ours. */
    public boolean clicked(Character player, int objectId) {
        ColiseumNpcs.Role role = npcs.roleOf(player, objectId);
        if (role == null || !on()) {
            return false;
        }
        if (role == ColiseumNpcs.Role.ARENA_MASTER) {
            talk(player);
            return true;
        }
        if (role == ColiseumNpcs.Role.QUARTERMASTER) {
            quartermaster.talk(player);
            return true;
        }
        return false;
    }

    /** {@code !pvp coliseum shop}: Sejan's window, wherever the GM stands and with the coliseum off. */
    public void shop(Character gm) {
        quartermaster.talk(gm);
    }

    private static final int CESAR = 2101014;

    void talk(Character player) {
        Optional<LadderRules.Bracket> bracket = LadderRules.Bracket.of(player.getLevel());
        tournament.payOwed(player);
        LivingTalk.Option watch = new LivingTalk.Option("#bWatch a match from the gallery#k",
                () -> watch(player, null, false));
        LivingTalk.Option weekly = new LivingTalk.Option("#bThe weekly tournament#k", () -> tournamentTalk(player));
        if (bracket.isEmpty()) {
            LivingTalk.open(player, CESAR).menu("The ladder starts at Lv 30. Train up and come back.\r\n\r\n"
                    + "Out there it is one against one, best of three, and no potions of your own. You can watch"
                    + " in the meantime.", List.of(watch, weekly, new LivingTalk.Option("#bThe ladder board#k",
                    () -> board.show(player, CESAR, () -> talk(player))))).show();
            return;
        }
        long now = System.currentTimeMillis();
        LadderBook.Row row = book.row(player.getId(), bracket.get());
        Glicko2.Rating rating = book.rating(player.getId(), bracket.get(), now);
        long fee = fee(player.getLevel());
        List<LivingTalk.Option> options = new ArrayList<>();
        options.add(new LivingTalk.Option("#bFight a ladder match#k (" + mesos(fee) + " mesos)",
                () -> LivingTalk.open(player, CESAR).yesNo("A ladder match costs #b" + mesos(fee) + " mesos#k, win or"
                        + " lose. Fight now?", () -> request(player, 0, false), () -> talk(player)).show()));
        options.add(new LivingTalk.Option("#bFight with my companions#k", () -> teamTalk(player)));
        options.add(watch);
        options.add(weekly);
        options.add(new LivingTalk.Option("#bThe ladder board#k", () -> board.show(player, CESAR, () -> talk(player))));
        options.add(new LivingTalk.Option("#bHow the ladder works#k", () -> rules(player)));
        LivingTalk.open(player, CESAR).menu("I'm Cesar. I keep Ariant's ladder.\r\n\r\nYour bracket: #b"
                + bracket.get().label + "#k (" + bracket.get().range() + "), rated #b" + Math.round(rating.rating())
                + "#k, " + row.wins() + "-" + row.losses() + ".", options).show();
    }

    /**
     * V6, team fights in Cesar's window: a 2v2 or 3v3 on the team ladder (as many of the player's companions as stand
     * with it), or a Crew War against its rival crew; the player's team rating for its bracket.
     */
    private void teamTalk(Character player) {
        Optional<LadderRules.Bracket> bracket = LadderRules.Bracket.of(player.getLevel());
        if (bracket.isEmpty()) {
            talk(player);
            return;
        }
        long now = System.currentTimeMillis();
        List<Character> companions = TeamLadder.companionsHere(player);
        LadderBook.Row row = teams.book().row(player.getId(), bracket.get());
        long fee = fee(player.getLevel());
        List<LivingTalk.Option> options = new ArrayList<>();
        int fieldable = TeamRules.fieldable(companions.size());
        for (int size = TeamRules.MIN_SIZE; size <= fieldable; size++) {
            int chosen = size;
            options.add(new LivingTalk.Option("#bA " + size + "v" + size + "#k (" + mesos(fee) + " mesos)",
                    () -> LivingTalk.open(player, CESAR).yesNo("A " + chosen + "v" + chosen + " costs #b" + mesos(fee)
                            + " mesos#k, win or lose. Fight now?", () -> teamRequest(player, chosen, false, false),
                            () -> teamTalk(player)).show()));
        }
        if (population.config().GUILD_LADDER_ENABLED && player.getGuild() != null) { // N5.3: fight for its guild
            String guild = player.getGuild().getName();
            for (int size = TeamRules.MIN_SIZE; size <= TeamRules.MAX_SIZE; size++) {
                int chosen = size;
                options.add(new LivingTalk.Option("#bA " + size + "v" + size + " for " + guild + "#k (" + mesos(fee)
                        + " mesos)", () -> LivingTalk.open(player, CESAR).yesNo("Your guild mates fill your side first,"
                        + " then your companions, against another guild. It costs #b" + mesos(fee) + " mesos#k. Fight"
                        + " for " + guild + "?", () -> guildRequest(player, chosen, false), () -> teamTalk(player))
                        .show()));
            }
        }
        if (population.rivals() != null && population.rivals().crewOfMain(player.getId()) != null
                && fieldable >= TeamRules.MIN_SIZE) {
            options.add(new LivingTalk.Option("#bA Crew War#k against "
                    + population.rivals().crewOfMain(player.getId()).name() + " (no fee)",
                    () -> teamRequest(player, TeamRules.MAX_SIZE, true, false)));
        }
        String head = "Bring your companions and fight as a team, " + TeamRules.MIN_SIZE + " or " + TeamRules.MAX_SIZE
                + " a side, under ladder rules. Pick one target together and it falls fast.\r\n\r\nYour team rating: #b"
                + Math.round(teams.book().teamRating(List.of(player.getId()), bracket.get(), now).rating()) + "#k, "
                + row.wins() + "-" + row.losses() + "."
                + (population.config().GUILD_LADDER_ENABLED && player.getGuild() != null ? " " + player.getGuild().getName()
                + "'s guild rating: #b" + Math.round(teams.guildBook().rating(player.getGuildId(), bracket.get(), now)
                .rating()) + "#k." : "");
        if (options.isEmpty()) {
            LivingTalk.open(player, CESAR).ok(head + "\r\n\r\n#rNone of your companions is standing with you.#k")
                    .back(() -> talk(player)).show();
            return;
        }
        LivingTalk.open(player, CESAR).menu(head, options).back(() -> talk(player)).show();
    }

    /** The tournament's page in Cesar's window: sign up or take it back, watch, the board, the rules. */
    private void tournamentTalk(Character player) {
        PvpConfig pvp = YamlConfig.config.pvp;
        List<LivingTalk.Option> options = new ArrayList<>();
        Optional<LadderRules.Bracket> bracket = LadderRules.Bracket.of(player.getLevel());
        if (Tournament.on() && bracket.isPresent()) {
            if (tournament.signedUp(player)) {
                options.add(new LivingTalk.Option("#bTake back my sign-up#k", () -> tournament.withdraw(player)));
            } else {
                String fee = mesos(TournamentRules.fee(player.getLevel(), pvp.TOURNAMENT_FEE_PER_LEVEL));
                options.add(new LivingTalk.Option("#bSign up#k (" + fee + " mesos)",
                        () -> LivingTalk.open(player, CESAR).yesNo("Signing up costs #b" + fee + " mesos#k. You get it"
                                + " back if you take your sign-up back before the tournament starts. Sign up?",
                                () -> tournament.signUp(player, false), () -> tournamentTalk(player)).show()));
            }
        }
        options.add(new LivingTalk.Option("#bWatch the tournament#k", () -> tournament.watch(player, null)));
        options.add(new LivingTalk.Option("#bHow it works#k", () -> LivingTalk.open(player, CESAR)
                .ok("Every Lv bracket has its own knockout, " + pvp.TOURNAMENT_SEATS + " seats. Players who sign up take"
                        + " theirs first; my best-rated fighters fill the rest, and the better your rating the easier"
                        + " your first bout. Each bout is a best of three under ladder rules, and a new round starts"
                        + " every " + pvp.TOURNAMENT_ROUND_MINUTES + " minutes.\r\n\r\nThe champion wins "
                        + TournamentRules.TROPHIES[3] + " Event Trophies, half the pool and my medal for "
                        + pvp.TOURNAMENT_MEDAL_DAYS + " days; the finalist " + TournamentRules.TROPHIES[2]
                        + " and a quarter; the semi-finalists " + TournamentRules.TROPHIES[1] + "; everyone else who"
                        + " fought " + TournamentRules.TROPHIES[0] + ".")
                .back(() -> tournamentTalk(player)).show()));
        LivingTalk.open(player, CESAR).menu((Tournament.on() ? "" : "#r(The tournament is closed for now.)#k\r\n\r\n")
                + tournament.boardText(System.currentTimeMillis()), options).back(() -> talk(player)).show();
    }

    private void rules(Character player) {
        PvpConfig pvp = YamlConfig.config.pvp;
        LivingTalk.open(player, CESAR)
                .say("I find you someone in your bracket within " + LadderRules.LEVEL_WINDOW + " levels, the nearest to"
                        + " your rating, and you fight on a closed arena floor. Best of three rounds, " + pvp.COLISEUM_ROUND_SECONDS
                        + " seconds each. A round still going at the bell goes to whoever kept more of their HP.")
                .ok("No potions of your own in there: you get " + pvp.COLISEUM_TONICS + " arena tonics a round, "
                        + pvp.COLISEUM_TONIC_PERCENT + "% HP and MP each, " + pvp.COLISEUM_TONIC_GAP_MS / 1000
                        + " s apart. Your potion key drinks one.\r\n\r\nA win pays " + LadderRules.WIN_TROPHIES
                        + " Event Trophies (and EXP for your first " + LadderRules.EXP_WINS_PER_DAY + " wins a day), a loss "
                        + LadderRules.LOSS_TROPHIES + ". I pay for " + LadderRules.DAILY_REWARDED + " matches a day.")
                .back(() -> talk(player)).show();
    }

    static long fee(int level) {
        return (long) YamlConfig.config.pvp.COLISEUM_FEE_PER_LEVEL * Math.max(1, level);
    }

    private static String mesos(long amount) {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount);
    }

    // ---- asking for a match ---------------------------------------------------------------------------------------

    /** Queues a match for {@code human}: with citizen {@code chosenCitizenId}, or the nearest rating when 0. */
    public void request(Character human, int chosenCitizenId, boolean forced) {
        if (human == null) {
            return;
        }
        requests.add(new Request(human.getId(), chosenCitizenId, forced));
        startTicker();
    }

    private void begin(Request request, long now) {
        Character human = online(request.humanId());
        if (human == null || !on() && !request.forced()) {
            return;
        }
        if (busy(human.getId())) {
            human.dropMessage(5, "You are in a match already.");
            return;
        }
        Optional<LadderRules.Bracket> bracket = LadderRules.Bracket.of(human.getLevel());
        if (bracket.isEmpty() || !human.isAlive() || human.getEventInstance() != null || human.getClient() == null) {
            human.dropMessage(5, "Cesar: the ladder is for the living, Lv 30 and up, and not from inside an event.");
            return;
        }
        Glicko2.Rating mine = book.rating(human.getId(), bracket.get(), now);
        CitizenSession opponent = request.chosenCitizenId() > 0 ? chosen(human, request.chosenCitizenId())
                : nearest(human, bracket.get(), mine, now);
        if (opponent == null) {
            human.dropMessage(5, request.chosenCitizenId() > 0
                    ? "Cesar: that one is not free to fight on this channel right now."
                    : "Cesar: nobody in your bracket near your rating is free right now. Try again in a minute.");
            return;
        }
        long fee = request.forced() || human.isGM() ? 0L : fee(human.getLevel());
        if (fee > 0 && human.getMeso() < fee) {
            human.dropMessage(5, "Cesar: the fee is " + mesos(fee) + " mesos.");
            return;
        }
        if (fee > 0) {
            try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_FEE)) {
                human.gainMeso((int) -fee, true);
            }
        }
        Character foe = opponent.agent();
        Glicko2.Rating theirs = book.rating(opponent.characterId, bracket.get(), now);
        double odds = Glicko2.winChance(mine, theirs);
        int arena = nextArenaId();
        Match match = new Match(human, opponent, bracket.get(), odds, request.forced(), fee, arena, now);
        population.presence().hold(opponent.characterId, now + HOLD_MS);
        byHuman.put(human.getId(), match);
        human.dropMessage(5, "Cesar: you fight " + foe.getName() + " (Lv " + foe.getLevel() + " " + jobName(foe)
                + "), rated " + Math.round(theirs.rating()) + ". To the arena!");
        log.info("Coliseum: {} (Lv {}, {}) against {} (Lv {}, {}) in {}, odds {}{}", human.getName(), human.getLevel(),
                Math.round(mine.rating()), foe.getName(), foe.getLevel(), Math.round(theirs.rating()), bracket.get(),
                String.format(Locale.ROOT, "%.2f", odds), request.forced() ? " (GM)" : "");
    }

    private CitizenSession chosen(Character human, int citizenId) {
        PresenceScheduler presence = population.presence();
        CitizenSession session = presence == null ? null : presence.session(citizenId);
        return free(session, human.getClient().getChannel(), human.getId()) ? session : null;
    }

    private CitizenSession nearest(Character human, LadderRules.Bracket bracket, Glicko2.Rating mine, long now) {
        Map<Integer, CitizenSession> byId = new HashMap<>();
        List<LadderRules.Candidate> candidates = new ArrayList<>();
        for (CitizenSession session : population.presence().sessions()) {
            if (!free(session, human.getClient().getChannel(), human.getId())) {
                continue;
            }
            Character agent = session.agent();
            byId.put(session.characterId, session);
            candidates.add(new LadderRules.Candidate(session.characterId, agent.getLevel(),
                    book.rating(session.characterId, bracket, now)));
        }
        return LadderRules.pick(human.getLevel(), mine, candidates).map(c -> byId.get(c.characterId())).orElse(null);
    }

    /** A loaded, unhired citizen nothing holds, alive, on {@code channel} (any channel when 0), out of every match. */
    boolean free(CitizenSession session, int channel, int humanId) {
        if (session == null || session.virtual || !session.loaded() || channel != 0 && session.channel != channel) {
            return false;
        }
        Character agent = session.agent();
        Citizen citizen = population.citizen(session.characterId);
        if (agent == null || citizen == null || citizen.hired() || agent.isGM() || !agent.isAlive()
                || agent.getMap() == null || agent.getEventInstance() != null || agent.getParty() != null
                || DuelRegistry.inDuel(agent.getId()) || population.presence().held(session)
                || gallery.fighting(session.characterId) || tournament.busy(session.characterId)
                || teams.fighting(session.characterId) || selfPlay.fighting(session.characterId)
                || !(agent.getClient() instanceof BotClient) || agent.getId() == humanId) {
            return false;
        }
        for (Match match : byHuman.values()) {
            if (match.citizenId == session.characterId) {
                return false;
            }
        }
        return true;
    }

    // ---- the step -------------------------------------------------------------------------------------------------

    void startTicker() {
        synchronized (tickerLock) {
            if (ticker == null) {
                ticker = TimerManager.getInstance().register(this::stepSafely, STEP_MS, STEP_MS);
            }
        }
    }

    private void stopTickerIfIdle() {
        synchronized (tickerLock) {
            if (byHuman.isEmpty() && requests.isEmpty() && gallery.idle() && tournament.idle() && teams.idle()
                    && selfPlay.idle() && ticker != null) {
                ticker.cancel(false);
                ticker = null;
            }
        }
    }

    private void stepSafely() {
        try {
            step(System.currentTimeMillis());
        } catch (Throwable failure) {
            log.warn("Coliseum step failed", failure);
        }
    }

    synchronized void step(long now) {
        for (Request request; (request = requests.poll()) != null; ) {
            try {
                begin(request, now);
            } catch (RuntimeException failure) {
                log.warn("Coliseum: starting a match for {} failed", request.humanId(), failure);
            }
        }
        for (Match match : new ArrayList<>(byHuman.values())) {
            try {
                stepOne(match, now);
            } catch (RuntimeException failure) {
                log.warn("Coliseum: {} against {} failed; closing it", match.humanName, match.citizenName, failure);
                abort(match, "something went wrong", true, now);
            }
        }
        gallery.step(now);
        tournament.step(now);
        teams.step(now);
        selfPlay.step(now);
        stopTickerIfIdle();
    }

    private void stepOne(Match match, long now) {
        if (match.stage == Stage.AFTER) {
            if (now >= match.nextAtMs) {
                close(match);
            }
            return;
        }
        Character human = online(match.humanId);
        CitizenSession session = population.presence().session(match.citizenId);
        Character citizen = session == null ? null : session.agent();
        if (citizen == null) {
            abort(match, match.citizenName + " is gone", true, now);
            return;
        }
        if (human == null) {
            if (match.stage == Stage.ENTERING) {
                abort(match, match.humanName + " logged out", false, now);
            } else {
                forfeit(match, now, "left the ladder");
            }
            return;
        }
        switch (match.stage) {
            case ENTERING -> enter(match, human, citizen, session, now);
            case INTRO, BETWEEN -> {
                if (left(match, human)) {
                    forfeit(match, now, "left the arena");
                } else if (match.ground.left(citizen.getId()) || citizen.getMap() != match.ground.map()) {
                    abort(match, match.citizenName + " left the arena", true, now);
                } else if (now >= match.nextAtMs) {
                    round(match, human, citizen, now);
                }
            }
            case FIGHTING -> fighting(match, session, now);
            case AFTER -> { } // handled above
        }
    }

    private boolean left(Match match, Character human) {
        return human == null || match.ground.left(human.getId()) || human.getMap() != match.ground.map();
    }

    private void enter(Match match, Character human, Character citizen, CitizenSession session, long now) {
        if (!human.isAlive() || human.getEventInstance() != null || human.getClient() == null) {
            abort(match, human.getName() + " cannot go in right now", true, now);
            return;
        }
        DuelGround ground = DuelGround.open(human, match.arenaId, ARIANT);
        if (ground == null) {
            abort(match, "no arena floor is free", true, now);
            return;
        }
        match.ground = ground;
        ground.enter(human, HUMAN_SPOT);
        ground.enter(citizen, CITIZEN_SPOT);
        LadderBook.Row row = book.row(citizen.getId(), match.bracket);
        Map<String, String> slots = Map.of("record", row.wins() + "-" + row.losses(),
                "rating", Long.toString(Math.round(book.rating(citizen.getId(), match.bracket, now).rating())),
                "bracket", match.bracket.label.toLowerCase(Locale.ROOT));
        say(session, row.played() == 0 ? "ladder.intro.new" : "ladder.intro", match.humanName, slots);
        match.stage = Stage.INTRO;
        match.nextAtMs = now + INTRO_MS;
    }

    private void round(Match match, Character human, Character citizen, long now) {
        PvpConfig pvp = YamlConfig.config.pvp;
        DuelField.Rules rules = new DuelField.Rules(pvp.COLISEUM_ROUND_SECONDS, pvp.COLISEUM_TONICS,
                pvp.COLISEUM_TONIC_PERCENT, pvp.COLISEUM_TONIC_GAP_MS);
        try {
            DuelField field = DuelRegistry.start(match.ground.map(), List.of(human), List.of(citizen), rules);
            match.field = field;
            match.result = null;
            field.onEnd(result -> match.result = result);
        } catch (IllegalStateException refused) {
            log.warn("Coliseum: round {} of {} against {} would not start: {}", match.score.played() + 1,
                    match.humanName, match.citizenName, refused.getMessage());
            abort(match, "the round would not start", true, now);
            return;
        }
        match.stage = Stage.FIGHTING;
        match.deadlineMs = now + (pvp.PVP_COUNTDOWN_SECONDS + pvp.COLISEUM_ROUND_SECONDS) * 1000L + ROUND_SLACK_MS;
        human.dropMessage(5, "Round " + (match.score.played() + 1) + " of " + LadderRules.ROUNDS + " against "
                + match.citizenName + ". Score " + match.score + ".");
    }

    private void fighting(Match match, CitizenSession session, long now) {
        DuelField.Result result = match.result;
        if (result == null) {
            if (now >= match.deadlineMs && match.field != null) {
                match.field.stop(); // the result comes back through onEnd
            }
            return;
        }
        boolean humanLeft = false;
        for (DuelLog.Row row : result.rows()) {
            if (row.charId() == match.humanId) {
                match.humanDealt += row.dealt();
                match.humanTaken += row.taken();
                match.humanKo |= row.ko();
                humanLeft |= row.left();
            } else if (row.charId() == match.citizenId) {
                match.citizenKo |= row.ko();
            }
        }
        if (humanLeft) {
            forfeit(match, now, "left the arena");
            return;
        }
        match.score = match.score.after(result.winnerSide());
        Character human = online(match.humanId);
        String roundLine = result.winnerSide() == 0 ? "You take round " + match.score.played()
                : result.winnerSide() == 1 ? match.citizenName + " takes round " + match.score.played()
                : "Round " + match.score.played() + " is a draw";
        if (human != null) {
            human.dropMessage(5, roundLine + ". Score " + match.score + ".");
        }
        if (match.score.over()) {
            finish(match, match.score.winner(), "best of three " + match.score, now);
            return;
        }
        if (result.winnerSide() >= 0) {
            say(session, result.winnerSide() == 1 ? "ladder.round.won" : "ladder.round.lost", match.humanName, Map.of());
        }
        match.stage = Stage.BETWEEN;
        match.nextAtMs = now + BETWEEN_ROUNDS_MS;
    }

    private void forfeit(Match match, long now, String why) {
        if (match.field != null && match.result == null) {
            match.field.stop();
        }
        log.info("Coliseum: {} {} against {}: a forfeit", match.humanName, why, match.citizenName);
        finish(match, 1, "forfeit: " + why, now);
    }

    /** The match is decided: ratings, pay, the book, the afterword; everyone goes back {@link #AFTER_MS} later. */
    private void finish(Match match, int winnerSide, String why, long now) {
        match.winnerSide = winnerSide;
        match.why = why;
        double humanScore = winnerSide == 0 ? 1d : winnerSide == 1 ? 0d : 0.5d;
        LadderBook.Change[] changes = book.record(match.humanId, match.citizenId, match.bracket, humanScore, now);
        Character human = online(match.humanId);
        boolean forfeited = why.startsWith("forfeit");
        if (human != null) {
            String verdict = winnerSide == 0 ? "You beat " + match.citizenName
                    : winnerSide == 1 ? match.citizenName + " beat you" : "You drew with " + match.citizenName;
            LadderBook.Row after = changes[0].after();
            human.dropMessage(5, "Ladder: " + verdict + " (" + match.score + "). Rating "
                    + Math.round(changes[0].before().rating().rating()) + " -> " + Math.round(after.rating().rating())
                    + " (" + match.bracket.label + ", " + after.wins() + "-" + after.losses() + ").");
            try {
                pay(human, match, winnerSide, forfeited, now);
            } catch (RuntimeException failure) {
                log.warn("Coliseum: paying {} failed", match.humanName, failure);
            }
        }
        try {
            List<LadderStore.Fighter> fighters = List.of(
                    new LadderStore.Fighter(match.humanId, 0, true, match.humanKo, match.humanDealt, match.humanTaken),
                    new LadderStore.Fighter(match.citizenId, 1, false, match.citizenKo, match.humanTaken,
                            match.humanDealt));
            store.insertMatch(now, match.arenaId, (now - match.startedAtMs) / 1000L, winnerSide, why, match.odds,
                    fighters);
        } catch (RuntimeException failure) {
            log.warn("Coliseum: booking {} against {} failed", match.humanName, match.citizenName, failure);
        }
        if (human != null && winnerSide == 0 && !forfeited && population.memories() != null) {
            population.memories().witnessed(human, PersonMemory.Kind.WON, "ladder"); // seen by the citizens there (N2.4)
        }
        CitizenSession session = population.presence().session(match.citizenId);
        if (session != null && !forfeited) {
            LadderBook.Row theirs = changes[1].after();
            say(session, winnerSide == 1 ? "ladder.win" : winnerSide == 0 ? "ladder.lose" : "ladder.draw",
                    match.humanName, Map.of("record", theirs.wins() + "-" + theirs.losses()));
        }
        log.info("Coliseum: {} against {} ended {} ({}): {} {} -> {}, {} {} -> {}", match.humanName, match.citizenName,
                winnerSide, why, match.humanName, Math.round(changes[0].before().rating().rating()),
                Math.round(changes[0].after().rating().rating()), match.citizenName,
                Math.round(changes[1].before().rating().rating()), Math.round(changes[1].after().rating().rating()));
        match.stage = Stage.AFTER;
        match.nextAtMs = forfeited ? now : now + AFTER_MS;
    }

    /** The human's pay for a finished match (none for a GM's own, a forfeit, or past the day's ten). */
    private void pay(Character human, Match match, int winnerSide, boolean forfeited, long now) {
        payLadder(human, winnerSide, match.odds, forfeited, match.forced, "ladder", now);
    }

    /**
     * A ladder match's pay to {@code human} (a 1v1 or, V6, a team match: one daily count for both): a win by the
     * {@code odds} it was fought at, a loss or draw the loss's Trophy; none for a forfeit, a GM's own (unless
     * {@code forced}), or past the day's {@link LadderRules#DAILY_REWARDED}.
     */
    void payLadder(Character human, int winnerSide, double odds, boolean forfeited, boolean forced, String what,
                   long now) {
        if (forfeited) {
            human.dropMessage(5, "A forfeit pays nothing.");
            return;
        }
        if (human.isGM() && !forced) {
            return;
        }
        long[] day = day(human.getAccountID(), now);
        if (day[1] >= LadderRules.DAILY_REWARDED) {
            human.dropMessage(5, "You've had " + LadderRules.DAILY_REWARDED + " rewarded ladder matches today; this one"
                    + " pays nothing.");
            return;
        }
        LadderRules.Pay pay = winnerSide == 0
                ? LadderRules.win(odds, (int) day[2], PvpGrindMinutes.get().expPerMinute(human.getLevel()))
                : LadderRules.loss();
        day[1]++;
        if (winnerSide == 0 && pay.exp() > 0) {
            day[2]++;
        }
        if (pay.exp() > 0) {
            human.gainExp((int) Math.min(Integer.MAX_VALUE, pay.exp()), true, true);
        }
        if (pay.trophies() > 0) {
            if (InventoryManipulator.addById(human.getClient(), ItemId.EVENT_TROPHY, (short) pay.trophies())) {
                human.sendPacket(PacketCreator.getShowItemGain(ItemId.EVENT_TROPHY, (short) pay.trophies(), true));
                human.dropMessage(5, "Cesar pays " + pay.trophies() + (pay.trophies() == 1 ? " Event Trophy." : " Event Trophies."));
            } else {
                human.dropMessage(5, "Your inventory is full, so the ladder's Trophies were lost.");
            }
        }
        log.info("Coliseum reward ({}): {} {} (odds {}): {} EXP, {} Trophies{}", what, human.getName(),
                winnerSide == 0 ? "won" : winnerSide == 1 ? "lost" : "drew", String.format(Locale.ROOT, "%.2f",
                        odds), pay.exp(), pay.trophies(), human.isGM() ? " (GM test)" : "");
    }

    private long[] day(int accountId, long now) {
        long index = now / DAY_MS;
        return days.compute(accountId, (id, old) -> old == null || old[0] != index ? new long[] {index, 0, 0} : old);
    }

    /** Called off: nothing is rated or paid, the fee (when {@code refund}) comes back, and everyone goes back. */
    private void abort(Match match, String why, boolean refund, long now) {
        if (match.field != null && match.result == null) {
            match.field.stop();
        }
        Character human = online(match.humanId);
        if (human != null) {
            human.dropMessage(5, "Cesar: the match is off (" + why + ")." + (refund && match.fee > 0
                    ? " Here is your fee back." : ""));
            if (refund && match.fee > 0) {
                try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_FEE)) {
                    human.gainMeso((int) match.fee, true);
                }
            }
        }
        log.info("Coliseum: {} against {} called off: {}", match.humanName, match.citizenName, why);
        close(match);
    }

    /** Everyone back where they stood; the citizen is let go. */
    private void close(Match match) {
        byHuman.remove(match.humanId, match);
        if (match.ground != null) {
            match.ground.close(null);
        }
        population.presence().release(match.citizenId);
    }

    // ---- citizens' own ladder -------------------------------------------------------------------------------------

    /**
     * Up to {@code max} simulated best-of-three matches between free citizens (any channel), each with the nearest
     * rating in its bracket within 5 levels that it has not played in the last hour. Returns how many were played.
     */
    int simulate(int max, long now) {
        if (max <= 0) {
            return 0;
        }
        simmed.values().removeIf(at -> now - at > SIM_REPEAT_MS);
        Map<LadderRules.Bracket, List<Character>> byBracket = new HashMap<>();
        for (CitizenSession session : population.presence().sessions()) {
            if (free(session, 0, 0)) {
                Character agent = session.agent();
                LadderRules.Bracket.of(agent.getLevel())
                        .ifPresent(b -> byBracket.computeIfAbsent(b, k -> new ArrayList<>()).add(agent));
            }
        }
        PvpConfig pvp = YamlConfig.config.pvp;
        PvpReference reference = PvpReference.get();
        int played = 0;
        for (Map.Entry<LadderRules.Bracket, List<Character>> entry : byBracket.entrySet()) {
            List<Character> pool = new ArrayList<>(entry.getValue());
            Collections.shuffle(pool, random);
            Set<Integer> used = new HashSet<>();
            for (Character a : pool) {
                if (played >= max) {
                    return played;
                }
                if (used.contains(a.getId())) {
                    continue;
                }
                LadderRules.Bracket bracket = entry.getKey();
                List<LadderRules.Candidate> candidates = new ArrayList<>();
                Map<Integer, Character> byId = new HashMap<>();
                for (Character b : pool) {
                    if (b != a && !used.contains(b.getId()) && !simmed.containsKey(pairKey(a.getId(), b.getId()))) {
                        candidates.add(new LadderRules.Candidate(b.getId(), b.getLevel(),
                                book.rating(b.getId(), bracket, now)));
                        byId.put(b.getId(), b);
                    }
                }
                Optional<LadderRules.Candidate> foe = LadderRules.pick(a.getLevel(), book.rating(a.getId(), bracket, now),
                        candidates);
                if (foe.isEmpty()) {
                    continue;
                }
                Character b = byId.get(foe.get().characterId());
                int winner = simulatedMatch(DuelSim.fighterOf(a, reference, pvp), DuelSim.fighterOf(b, reference, pvp),
                        pvp, random);
                book.record(a.getId(), b.getId(), bracket, winner == 0 ? 1d : winner == 1 ? 0d : 0.5d, now);
                matched(a.getId(), b.getId(), winner);
                used.add(a.getId());
                used.add(b.getId());
                simmed.put(pairKey(a.getId(), b.getId()), now);
                played++;
            }
        }
        return played;
    }

    /** A best of three between two sim fighters under the coliseum's round length: 0 (a), 1 (b) or -1 (a draw). */
    static int simulatedMatch(DuelSim.Fighter a, DuelSim.Fighter b, PvpConfig pvp, Random random) {
        LadderRules.Score score = LadderRules.Score.START;
        while (!score.over()) {
            score = score.after(DuelSim.round(a, b, pvp, random, pvp.COLISEUM_ROUND_SECONDS).winner());
        }
        return score.winner();
    }

    static String pairKey(int a, int b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    // ---- GM ---------------------------------------------------------------------------------------------------------

    /** {@code !pvp coliseum status}: the matches running and the day's numbers. */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        lines.add("Coliseum " + (on() ? "on" : "off") + ": " + byHuman.size() + " match(es) running, " + requests.size()
                + " queued.");
        for (Match match : byHuman.values()) {
            lines.add(match.humanName + " v " + match.citizenName + " (" + match.bracket.label + ", " + match.stage + ", "
                    + match.score + ", odds " + String.format(Locale.ROOT, "%.2f", match.odds) + ")");
        }
        lines.addAll(gallery.status());
        lines.addAll(tournament.status());
        return lines;
    }

    /**
     * {@code !pvp tournament start <bracket> [seats] [minutes] [me]}: a bracket's tournament now, with the switch off
     * too; with {@code me} the GM takes a seat. Returns what was queued, or why not.
     */
    public String tournamentStart(Character gm, String bracketWord, int seats, int minutes, boolean me) {
        LadderRules.Bracket bracket = bracket(bracketWord);
        if (bracket == null) {
            return "Which bracket: rookie, veteran or champion?";
        }
        if (seats != 0 && seats != 2 && seats != 4 && seats != 8) {
            return "Seats must be 2, 4 or 8.";
        }
        tournament.startNow(bracket, seats, minutes, me ? gm.getId() : 0);
        return "The " + bracket.label + " tournament starts on the next step" + (me ? ", with you seated." : ".");
    }

    /** {@code !pvp tournament signup}: the GM signs up for their bracket's tournament this week (no fee). */
    public void tournamentSignUp(Character gm) {
        tournament.signUp(gm, true);
    }

    /** {@code !pvp tournament watch [bracket]}. */
    public void tournamentWatch(Character gm, String bracketWord) {
        tournament.watch(gm, bracketWord == null ? null : bracket(bracketWord));
    }

    /** {@code !pvp tournament} with nothing else: Cesar's tournament page, wherever the GM stands. */
    public void tournamentTalk(Character gm, boolean asGm) {
        tournamentTalk(gm);
    }

    /** The tournament's status lines (also part of {@link #status}). */
    public List<String> tournamentStatus() {
        return tournament.status();
    }

    private static LadderRules.Bracket bracket(String word) {
        if (word == null) {
            return null;
        }
        for (LadderRules.Bracket each : LadderRules.Bracket.values()) {
            if (each.name().equalsIgnoreCase(word) || each.label.equalsIgnoreCase(word)) {
                return each;
            }
        }
        return null;
    }

    /** {@code !pvp coliseum rating <id>}: a character's row in its bracket. */
    public String rating(Character chr) {
        Optional<LadderRules.Bracket> bracket = LadderRules.Bracket.of(chr.getLevel());
        if (bracket.isEmpty()) {
            return chr.getName() + " is under Lv 30: no bracket.";
        }
        LadderBook.Row row = book.row(chr.getId(), bracket.get());
        Glicko2.Rating now = book.rating(chr.getId(), bracket.get(), System.currentTimeMillis());
        return chr.getName() + " (" + bracket.get().label + "): " + Math.round(now.rating()) + " +/- "
                + Math.round(now.deviation()) + ", " + row.wins() + "-" + row.losses();
    }

    /** {@code !pvp selfplay [n]}: up to {@code n} training bouts now, whatever the hour and the switch; how many began. */
    public int selfPlayNow(int n) {
        return selfPlay.begin(n, System.currentTimeMillis());
    }

    /** {@code !pvp selfplay status}. */
    public List<String> selfPlayStatus() {
        return selfPlay.status();
    }

    /** {@code !pvp coliseum sim [n]}: plays n citizen matches now; how many were played. */
    public int simulateNow(int count) {
        return simulate(count, System.currentTimeMillis());
    }

    // ---- small helpers --------------------------------------------------------------------------------------------

    void say(CitizenSession session, String trigger, String humanName, Map<String, String> slots) {
        ChatterEngine.Event event = ChatterEngine.Event.of(trigger, session.mapId, session.channel).self(session)
                .subject(humanName, 0);
        slots.forEach(event::slot);
        String line = population.chatter().compose(event);
        if (line != null) {
            population.chatter().sayNow(session, line);
        }
    }

    /** The job as the game names it ("White Knight", "Aran"; live check 2026-11-16 showed "Whiteknight"). */
    static String jobName(Character chr) {
        return server.living.roster.CompanionView.jobName(chr.getJob().getId());
    }

    static Character online(int characterId) {
        for (World world : Server.getInstance().getWorlds()) {
            Character chr = world.getPlayerStorage().getCharacterById(characterId);
            if (chr != null) {
                return chr;
            }
        }
        return null;
    }
}
