package server.living.population.coliseum;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import config.PvpConfig;
import config.YamlConfig;
import constants.id.ItemId;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.economy.ledger.MesoAccount;
import server.economy.ledger.MesoFlows;
import server.living.population.CitizenSession;
import server.living.population.Population;
import server.pvp.DuelRegistry;
import server.pvp.DuelSim;
import server.pvp.PvpReference;
import tools.PacketCreator;

import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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
 * The weekly tournament (docs/PVP_PLAN.md V5). Each ladder bracket gets a knockout of {@code TOURNAMENT_SEATS} seats on
 * {@code TOURNAMENT_DAY} at {@code TOURNAMENT_HOUR}, server time, fought on {@code TOURNAMENT_CHANNEL}. Players sign up
 * with Cesar from Monday (paying {@code TOURNAMENT_FEE_PER_LEVEL} mesos a level) and take seats first; the best-rated
 * free citizens on that channel fill the rest and pay the same. Seeds go by ladder rating, and rounds start
 * {@code TOURNAMENT_ROUND_MINUTES} apart. A bout with a player in it is fought live on a closed arena floor; a citizen
 * bout is fought live when someone asked Cesar to watch, otherwise simulated ({@link DuelSim}). A drawn bout sends the
 * better seed on. Rounds are announced with server notices; the champion gets one more, the medal for a week, and a
 * citizen's own megaphone tells the rest.
 *
 * <p>Prizes ({@link TournamentRules#prize}): Trophies by how far a seat got, and half the entry pool to the champion,
 * a quarter to the finalist; the house keeps the last quarter. A player offline at the end is owed theirs, and Cesar
 * pays it on their next visit. A player who never showed for a bout wins nothing. Bouts do not move ladder ratings;
 * they are booked in {@code pvp_match} as kind TOURNAMENT.
 *
 * <p>Threads: the minute, the window and the GM command only queue starts; seats, rounds and pay change on the
 * coliseum's one-second step. A restart in the middle cancels the run, and players are owed their fees back.
 */
public final class Tournament {
    private static final Logger log = LoggerFactory.getLogger(Tournament.class);

    /** The champion's medal, held for {@code TOURNAMENT_MEDAL_DAYS} (an item that expires). */
    static final int MEDAL = 1142077;
    /** The letters' news kind (CompanionLetters): a player's result, told when they were away for it. */
    public static final String NEWS_EVENT = "coliseum.tournament";
    static final String MASTER = "Cesar";
    /** The longest letter a note holds. */
    static final int LETTER_MAX = 200;
    /** Signed-up players hear this long before the start. */
    static final long REMIND_MS = 5 * 60_000L;
    /** A start missed by more than this (the server was down) is skipped for the week. */
    static final long LATE_MS = 3 * 60 * 60_000L;
    /** Seated citizens are held at most this long (released when they go out or the run ends). */
    static final long HOLD_MS = 4 * 60 * 60_000L;

    /** What the tournament keeps; tests use an in-memory one. */
    interface Store {
        /** The week's row, made OPEN when there is none; empty only when storage is down. */
        Optional<TournamentStore.Week> ensure(String bracket, int weekOf, long startsAtMs);

        Optional<TournamentStore.Week> week(String bracket, int weekOf);

        List<TournamentStore.Seat> seats(long tournamentId);

        /** A player's sign-up (seed 0); false when they have one already or the write failed. */
        boolean signUp(long tournamentId, TournamentStore.Seat seat);

        /** Takes back a sign-up that is not seated yet; false when there was none. */
        boolean withdraw(long tournamentId, int characterId);

        /** Writes a seat at the start (seed, fee), new or the player's own sign-up. */
        void seat(long tournamentId, TournamentStore.Seat seat);

        /** How far a seat got and what it won; {@code paid} false when the winnings wait for its next visit. */
        void result(long tournamentId, int characterId, int reached, int trophies, long mesos, boolean paid);

        /** Winnings a player was offline for (paid 0), oldest first. */
        List<TournamentStore.Owed> owed(int characterId);

        /** Owed winnings handed over. */
        void paid(long tournamentId, int characterId);

        /** Tournaments a restart left RUNNING. */
        List<TournamentStore.Week> running();

        void state(long tournamentId, String state, long pool, int championId, int runnerUpId, long finishedAtMs);

        /** The latest finished tournament in a bracket. */
        Optional<TournamentStore.Week> lastDone(String bracket);
    }

    /** A bracket's tournament running now. Entrants are indices into {@link #seated}, seed order. */
    static final class Run {
        final TournamentStore.Week week;
        final LadderRules.Bracket bracket;
        final List<TournamentRules.Entrant> seated;
        final TournamentDraw draw;
        final long pool;
        final long roundMs;
        final boolean forced;
        final boolean[] fought;
        final Map<Integer, TournamentBout> live = new HashMap<>();
        final List<String> results = new ArrayList<>();
        long nextRoundAtMs;
        long roundStartedAtMs;
        boolean roundOpen;

        Run(TournamentStore.Week week, LadderRules.Bracket bracket, List<TournamentRules.Entrant> seated, int seats,
            long pool, long roundMs, boolean forced, long now) {
            this.week = week;
            this.bracket = bracket;
            this.seated = List.copyOf(seated);
            this.draw = new TournamentDraw(seats, seated.size());
            this.pool = pool;
            this.roundMs = roundMs;
            this.forced = forced;
            this.fought = new boolean[seated.size()];
            this.nextRoundAtMs = now;
        }

        String roundName() {
            return TournamentRules.roundName(draw.round(), draw.rounds());
        }

        boolean seats(int characterId) {
            for (TournamentRules.Entrant entrant : seated) {
                if (entrant.characterId() == characterId) {
                    return true;
                }
            }
            return false;
        }

        /** Still in: seated and not knocked out. */
        boolean stillIn(int characterId) {
            for (int i = 0; i < seated.size(); i++) {
                if (seated.get(i).characterId() == characterId) {
                    return draw.reached(i) == TournamentRules.NONE;
                }
            }
            return false;
        }
    }

    /**
     * A queued start: a bracket, and for a GM run its seats and round gap (0 = the config's) and a player seated
     * without signing up (0 = none), so a test can run again once this week's is over.
     */
    private record Start(LadderRules.Bracket bracket, boolean forced, int seats, int roundMinutes, int seatId) { }

    /** What a letter says about a tournament a player was away for (CompanionLetters). */
    public record Letter(String sender, String text) { }

    private final Coliseum coliseum;
    private final Store store;
    private final LadderStore matches;
    private final Map<LadderRules.Bracket, Run> runs = new EnumMap<>(LadderRules.Bracket.class);
    private final Queue<Start> starts = new ConcurrentLinkedQueue<>();
    /** watcher id -> the bracket whose next citizen bout they watch. */
    private final Map<Integer, LadderRules.Bracket> watching = new ConcurrentHashMap<>();
    private final Set<String> reminded = ConcurrentHashMap.newKeySet();
    private final Random random = new Random();
    private volatile boolean cleaned;

    Tournament(Coliseum coliseum, Store store, LadderStore matches) {
        this.coliseum = coliseum;
        this.store = store;
        this.matches = matches;
    }

    static boolean on() {
        return Coliseum.on() && YamlConfig.config.pvp.TOURNAMENT_ENABLED;
    }

    static DayOfWeek day() {
        return DayOfWeek.valueOf(YamlConfig.config.pvp.TOURNAMENT_DAY.trim().toUpperCase(Locale.ROOT));
    }

    static ZonedDateTime at(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
    }

    /** This week's start, as epoch millis. */
    static long startMs(long now) {
        int week = TournamentRules.weekOf(at(now));
        return TournamentRules.start(week, day(), YamlConfig.config.pvp.TOURNAMENT_HOUR, ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }

    static String when() {
        return TournamentRules.when(day(), YamlConfig.config.pvp.TOURNAMENT_HOUR);
    }

    // ---- what the rest of the coliseum asks --------------------------------------------------------------------------

    /** Nothing running or queued: the coliseum's step may stop. */
    synchronized boolean idle() {
        return runs.isEmpty() && starts.isEmpty();
    }

    /** A character seated in a running tournament and still in it (not free for the ladder or the gallery). */
    synchronized boolean busy(int characterId) {
        for (Run run : runs.values()) {
            if (run.stillIn(characterId)) {
                return true;
            }
            for (TournamentBout bout : run.live.values()) {
                if (bout.has(characterId) || bout.watchedBy(characterId)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- the minute -----------------------------------------------------------------------------------------------

    /** The social minute: a leftover RUNNING row is cancelled once, a reminder goes out, and a start is queued. */
    void minute(long now) {
        if (!cleaned) {
            cleaned = true;
            cancelLeftovers();
        }
        if (!on()) {
            return;
        }
        PvpConfig pvp = YamlConfig.config.pvp;
        int week = TournamentRules.weekOf(at(now));
        long start = startMs(now);
        if (now < start - REMIND_MS || now >= start + LATE_MS) {
            return;
        }
        for (LadderRules.Bracket bracket : LadderRules.Bracket.values()) {
            synchronized (this) {
                if (runs.containsKey(bracket)) {
                    continue;
                }
            }
            Optional<TournamentStore.Week> row = store.week(bracket.name(), week);
            if (now < start) {
                if (row.isPresent() && reminded.add(bracket + ":" + week)) {
                    remind(row.get(), pvp);
                }
                continue;
            }
            if (row.isEmpty() || TournamentStore.OPEN.equals(row.get().state())) {
                starts.add(new Start(bracket, false, 0, 0, 0));
                coliseum.startTicker();
            }
        }
    }

    private void remind(TournamentStore.Week week, PvpConfig pvp) {
        for (TournamentStore.Seat seat : store.seats(week.id())) {
            Character chr = seat.human() ? Coliseum.online(seat.characterId()) : null;
            if (chr != null) {
                chr.dropMessage(5, MASTER + ": the " + label(week.bracket()) + " tournament starts in "
                        + REMIND_MS / 60_000L + " minutes. Be on channel " + pvp.TOURNAMENT_CHANNEL
                        + ", out of any party quest: a seat that does not show forfeits.");
            }
        }
    }

    /** A restart left a run RUNNING: it is cancelled, and every player seated is owed their fee back. */
    private void cancelLeftovers() {
        for (TournamentStore.Week week : store.running()) {
            for (TournamentStore.Seat seat : store.seats(week.id())) {
                if (seat.human() && seat.fee() > 0) {
                    store.result(week.id(), seat.characterId(), TournamentRules.NONE, 0, seat.fee(), false);
                }
            }
            store.state(week.id(), TournamentStore.CANCELLED, week.pool(), 0, 0, System.currentTimeMillis());
            log.info("Tournament: {} week {} was running when the server stopped; cancelled, fees owed back",
                    week.bracket(), week.weekOf());
        }
    }

    // ---- signing up -------------------------------------------------------------------------------------------------

    /** A player signs up for their bracket's tournament this week (from Cesar's window, or a GM's command). */
    void signUp(Character player, boolean forced) {
        PvpConfig pvp = YamlConfig.config.pvp;
        Optional<LadderRules.Bracket> bracket = LadderRules.Bracket.of(player.getLevel());
        if (bracket.isEmpty()) {
            player.dropMessage(5, MASTER + ": the tournament starts at Lv " + LadderRules.Bracket.values()[0].minLevel + ".");
            return;
        }
        long now = System.currentTimeMillis();
        int week = TournamentRules.weekOf(at(now));
        if (!forced && !TournamentRules.signUpOpen(at(now), week, day(), pvp.TOURNAMENT_HOUR)) {
            player.dropMessage(5, MASTER + ": this week's tournament has started. Sign-up opens again on Monday.");
            return;
        }
        synchronized (this) {
            if (runs.containsKey(bracket.get())) {
                player.dropMessage(5, MASTER + ": the " + bracket.get().label + " tournament is under way.");
                return;
            }
        }
        Optional<TournamentStore.Week> row = store.ensure(bracket.get().name(), week, startMs(now));
        if (row.isEmpty() || !TournamentStore.OPEN.equals(row.get().state())) {
            player.dropMessage(5, MASTER + ": I can't take sign-ups for this week any more.");
            return;
        }
        List<TournamentStore.Seat> seats = store.seats(row.get().id());
        int players = 0;
        for (TournamentStore.Seat seat : seats) {
            if (seat.characterId() == player.getId()) {
                player.dropMessage(5, MASTER + ": you're signed up already. " + when() + ", channel "
                        + pvp.TOURNAMENT_CHANNEL + ".");
                return;
            }
            players += seat.human() ? 1 : 0;
        }
        if (players >= pvp.TOURNAMENT_SEATS) {
            player.dropMessage(5, MASTER + ": every seat in the " + bracket.get().label + " tournament is taken.");
            return;
        }
        long fee = forced || player.isGM() ? 0L : TournamentRules.fee(player.getLevel(), pvp.TOURNAMENT_FEE_PER_LEVEL);
        if (fee > 0 && player.getMeso() < fee) {
            player.dropMessage(5, MASTER + ": the seat is " + mesos(fee) + " mesos.");
            return;
        }
        if (fee > 0) {
            try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_FEE)) {
                player.gainMeso((int) -fee, true);
            }
        }
        if (!store.signUp(row.get().id(), new TournamentStore.Seat(player.getId(), player.getName(), true, 0, fee,
                TournamentRules.NONE, 0, 0L))) {
            if (fee > 0) {
                try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_FEE)) {
                    player.gainMeso((int) fee, true);
                }
            }
            player.dropMessage(5, MASTER + ": I couldn't write your name down. Try again in a moment.");
            return;
        }
        player.dropMessage(5, MASTER + ": you're in the " + bracket.get().label + " tournament, " + when()
                + " on channel " + pvp.TOURNAMENT_CHANNEL + ". Be there: a seat that does not show forfeits its fee.");
        log.info("Tournament: {} signed up for {} week {} ({} mesos){}", player.getName(), bracket.get(), week, fee,
                forced ? " (GM)" : "");
    }

    /** A player takes back a sign-up before the start; the fee comes back. */
    void withdraw(Character player) {
        Optional<LadderRules.Bracket> bracket = LadderRules.Bracket.of(player.getLevel());
        long now = System.currentTimeMillis();
        Optional<TournamentStore.Week> row = bracket.flatMap(b -> store.week(b.name(),
                TournamentRules.weekOf(at(now))));
        if (row.isEmpty() || !TournamentStore.OPEN.equals(row.get().state())) {
            player.dropMessage(5, MASTER + ": you have no sign-up to take back.");
            return;
        }
        long fee = 0;
        for (TournamentStore.Seat seat : store.seats(row.get().id())) {
            if (seat.characterId() == player.getId()) {
                fee = seat.fee();
            }
        }
        if (!store.withdraw(row.get().id(), player.getId())) {
            player.dropMessage(5, MASTER + ": you have no sign-up to take back.");
            return;
        }
        if (fee > 0) {
            try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_FEE)) {
                player.gainMeso((int) fee, true);
            }
        }
        player.dropMessage(5, MASTER + ": your name is off the list." + (fee > 0 ? " Here is your fee back." : ""));
    }

    /** Whether {@code player} is signed up for this week (for Cesar's window). */
    boolean signedUp(Character player) {
        Optional<LadderRules.Bracket> bracket = LadderRules.Bracket.of(player.getLevel());
        Optional<TournamentStore.Week> row = bracket.flatMap(b -> store.week(b.name(),
                TournamentRules.weekOf(at(System.currentTimeMillis()))));
        if (row.isEmpty()) {
            return false;
        }
        for (TournamentStore.Seat seat : store.seats(row.get().id())) {
            if (seat.characterId() == player.getId()) {
                return true;
            }
        }
        return false;
    }

    /** Watch the next citizen bout of a running tournament in {@code bracket} (or the player's own, else any). */
    void watch(Character player, LadderRules.Bracket bracket) {
        LadderRules.Bracket chosen = bracket;
        synchronized (this) {
            if (chosen == null) {
                chosen = LadderRules.Bracket.of(player.getLevel()).filter(runs::containsKey)
                        .orElse(runs.keySet().stream().findFirst().orElse(null));
            }
            if (chosen == null || !runs.containsKey(chosen)) {
                player.dropMessage(5, MASTER + ": no tournament is under way. They start " + when() + ".");
                return;
            }
            if (runs.get(chosen).seats(player.getId())) {
                player.dropMessage(5, MASTER + ": you are seated in it. Your bout will come.");
                return;
            }
        }
        if (player.getClient() == null || player.getClient().getChannel() != YamlConfig.config.pvp.TOURNAMENT_CHANNEL) {
            player.dropMessage(5, MASTER + ": the tournament is fought on channel "
                    + YamlConfig.config.pvp.TOURNAMENT_CHANNEL + ". Watch from there.");
            return;
        }
        watching.put(player.getId(), chosen);
        player.dropMessage(5, MASTER + ": you'll watch the next " + chosen.label + " bout from ringside. Stay on this"
                + " channel.");
    }

    /** Pays what a player was owed (offline at the end, or a cancelled run's fee); true when there was something. */
    boolean payOwed(Character player) {
        List<TournamentStore.Owed> owed = store.owed(player.getId());
        if (owed.isEmpty()) {
            return false;
        }
        for (TournamentStore.Owed one : owed) {
            if (one.trophies() > 0 && !InventoryManipulator.addById(player.getClient(), ItemId.EVENT_TROPHY,
                    (short) one.trophies())) {
                player.dropMessage(5, MASTER + ": your Etc tab is full. Make room for " + one.trophies()
                        + " Event Trophies and come back.");
                return true;
            }
            if (one.trophies() > 0) {
                player.sendPacket(PacketCreator.getShowItemGain(ItemId.EVENT_TROPHY, (short) one.trophies(), true));
            }
            if (one.mesos() > 0) {
                try (var ledgerScope = MesoFlows.as(one.reached() == TournamentRules.NONE ? MesoAccount.PVP_FEE
                        : MesoAccount.PVP_PRIZE)) {
                    player.gainMeso((int) Math.min(Integer.MAX_VALUE, one.mesos()), true);
                }
            }
            store.paid(one.tournamentId(), player.getId());
            player.dropMessage(5, MASTER + ": " + (one.reached() == TournamentRules.NONE
                    ? "the " + label(one.bracket()) + " tournament was called off; here is your fee back."
                    : "from the " + label(one.bracket()) + " tournament, where you were "
                    + TournamentRules.reachedWords(one.reached()) + ": " + prizeWords(one.trophies(), one.mesos()) + "."));
        }
        return true;
    }

    // ---- GM ---------------------------------------------------------------------------------------------------------

    /**
     * {@code !pvp tournament start <bracket> [seats] [minutes] [me]}: start now, even with the switch off;
     * {@code seatId} (the GM, with "me") takes a seat without signing up.
     */
    void startNow(LadderRules.Bracket bracket, int seats, int roundMinutes, int seatId) {
        starts.add(new Start(bracket, true, seats, roundMinutes, seatId));
        coliseum.startTicker();
    }

    synchronized List<String> status() {
        List<String> lines = new ArrayList<>();
        PvpConfig pvp = YamlConfig.config.pvp;
        lines.add("Tournament " + (on() ? "on" : "off") + ": " + when() + " on channel " + pvp.TOURNAMENT_CHANNEL
                + ", " + pvp.TOURNAMENT_SEATS + " seats, rounds " + pvp.TOURNAMENT_ROUND_MINUTES + " min apart; "
                + runs.size() + " running, " + starts.size() + " queued, " + watching.size() + " watching.");
        for (Run run : runs.values()) {
            lines.add(run.bracket.label + ": " + run.roundName() + (run.roundOpen ? " under way" : " next") + ", "
                    + remaining(run) + " still in, pool " + mesos(run.pool));
            for (TournamentBout bout : run.live.values()) {
                lines.add("  " + bout.status());
            }
        }
        return lines;
    }

    // ---- the step ---------------------------------------------------------------------------------------------------

    synchronized void step(long now) {
        for (Start start; (start = starts.poll()) != null; ) {
            try {
                if (!runs.containsKey(start.bracket())) {
                    begin(start, now);
                }
            } catch (RuntimeException failure) {
                log.warn("Tournament: starting {} failed", start.bracket(), failure);
            }
        }
        watching.keySet().removeIf(id -> Coliseum.online(id) == null);
        for (Run run : new ArrayList<>(runs.values())) {
            try {
                stepRun(run, now);
            } catch (RuntimeException failure) {
                log.warn("Tournament: {} failed; cancelling it", run.bracket, failure);
                cancel(run, "something went wrong", now);
            }
        }
    }

    /** The coliseum closes: every run is cancelled, its players owed their fees. */
    synchronized void stop() {
        long now = System.currentTimeMillis();
        for (Run run : new ArrayList<>(runs.values())) {
            cancel(run, "the arena closed", now);
        }
        starts.clear();
        watching.clear();
    }

    private void begin(Start start, long now) {
        PvpConfig pvp = YamlConfig.config.pvp;
        LadderRules.Bracket bracket = start.bracket();
        int seats = start.seats() > 0 ? start.seats() : pvp.TOURNAMENT_SEATS;
        long roundMs = (start.roundMinutes() > 0 ? start.roundMinutes() : pvp.TOURNAMENT_ROUND_MINUTES) * 60_000L;
        int weekOf = TournamentRules.weekOf(at(now));
        Optional<TournamentStore.Week> row = store.ensure(bracket.name(), weekOf, start.forced() ? now : startMs(now));
        if (start.forced() && row.isPresent() && !TournamentStore.OPEN.equals(row.get().state())) {
            // a GM test run once this week's is over: a week of its own, keyed below every real one
            row = store.ensure(bracket.name(), -(int) (now / 60_000L), now);
        }
        if (row.isEmpty() || !TournamentStore.OPEN.equals(row.get().state())) {
            log.info("Tournament: {} week {} is not open ({}); nothing to start", bracket, weekOf,
                    row.map(TournamentStore.Week::state).orElse("no row"));
            return;
        }
        TournamentStore.Week week = row.get();
        LadderBook book = coliseum.book();
        Map<Integer, TournamentStore.Seat> signedUp = new HashMap<>();
        List<TournamentRules.Entrant> players = new ArrayList<>();
        for (TournamentStore.Seat seat : store.seats(week.id())) {
            if (!seat.human()) {
                continue;
            }
            signedUp.put(seat.characterId(), seat);
            Character chr = Coliseum.online(seat.characterId());
            players.add(new TournamentRules.Entrant(seat.characterId(), seat.name(), chr == null ? 0 : chr.getLevel(),
                    true, book.rating(seat.characterId(), bracket, now).rating()));
        }
        Character extra = start.seatId() > 0 && !signedUp.containsKey(start.seatId()) ? Coliseum.online(start.seatId())
                : null;
        if (extra != null) {
            store.signUp(week.id(), new TournamentStore.Seat(extra.getId(), extra.getName(), true, 0, 0L,
                    TournamentRules.NONE, 0, 0L));
            signedUp.put(extra.getId(), new TournamentStore.Seat(extra.getId(), extra.getName(), true, 0, 0L,
                    TournamentRules.NONE, 0, 0L));
            players.add(new TournamentRules.Entrant(extra.getId(), extra.getName(), extra.getLevel(), true,
                    book.rating(extra.getId(), bracket, now).rating()));
        }
        Map<Integer, CitizenSession> sessions = new HashMap<>();
        List<TournamentRules.Entrant> citizens = new ArrayList<>();
        for (CitizenSession session : coliseum.population().presence().sessions()) {
            if (!coliseum.free(session, pvp.TOURNAMENT_CHANNEL, 0)) {
                continue;
            }
            Character agent = session.agent();
            long fee = TournamentRules.fee(agent.getLevel(), pvp.TOURNAMENT_FEE_PER_LEVEL);
            if (LadderRules.Bracket.of(agent.getLevel()).orElse(null) != bracket || agent.getMeso() < fee) {
                continue;
            }
            sessions.put(session.characterId, session);
            citizens.add(new TournamentRules.Entrant(session.characterId, agent.getName(), agent.getLevel(), false,
                    book.rating(session.characterId, bracket, now).rating()));
        }
        List<TournamentRules.Entrant> seated = TournamentRules.seat(players, citizens, seats);
        for (TournamentStore.Seat seat : signedUp.values()) {
            boolean in = seated.stream().anyMatch(e -> e.characterId() == seat.characterId());
            if (!in && seat.fee() > 0) {
                store.result(week.id(), seat.characterId(), TournamentRules.NONE, 0, seat.fee(), false);
            }
        }
        if (seated.size() < 2) {
            for (TournamentStore.Seat seat : signedUp.values()) {
                if (seat.fee() > 0) {
                    store.result(week.id(), seat.characterId(), TournamentRules.NONE, 0, seat.fee(), false);
                }
                Character chr = Coliseum.online(seat.characterId());
                if (chr != null) {
                    chr.dropMessage(5, MASTER + ": not enough fighters for the " + bracket.label + " tournament; it"
                            + " is off this week." + (seat.fee() > 0 ? " Talk to me for your fee." : ""));
                }
            }
            store.state(week.id(), TournamentStore.CANCELLED, 0, 0, 0, now);
            log.info("Tournament: {} week {} cancelled: {} fighter(s)", bracket, week.weekOf(), seated.size());
            return;
        }
        long pool = 0;
        for (int i = 0; i < seated.size(); i++) {
            TournamentRules.Entrant entrant = seated.get(i);
            long fee;
            if (entrant.human()) {
                fee = signedUp.get(entrant.characterId()).fee();
            } else {
                Character agent = sessions.get(entrant.characterId()).agent();
                fee = TournamentRules.fee(agent.getLevel(), pvp.TOURNAMENT_FEE_PER_LEVEL);
                try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_FEE)) {
                    agent.gainMeso((int) -fee, false);
                }
                coliseum.population().presence().hold(entrant.characterId(), now + HOLD_MS);
            }
            pool += fee;
            store.seat(week.id(), new TournamentStore.Seat(entrant.characterId(), entrant.name(), entrant.human(), i + 1,
                    fee, TournamentRules.NONE, 0, 0L));
        }
        Run run = new Run(week, bracket, seated, seats, pool, roundMs, start.forced(), now);
        runs.put(bracket, run);
        store.state(week.id(), TournamentStore.RUNNING, pool, 0, 0, 0L);
        StringBuilder draw = new StringBuilder();
        for (int i = 0; i < seated.size(); i++) {
            draw.append(i == 0 ? "" : ", ").append(i + 1).append(". ").append(seated.get(i).name());
        }
        notice("[Coliseum] The " + bracket.label + " tournament begins in Ariant: " + seated.size() + " fighters, a pool"
                + " of " + mesos(pool) + " mesos. Channel " + pvp.TOURNAMENT_CHANNEL + ".");
        for (TournamentRules.Entrant entrant : seated) {
            Character chr = entrant.human() ? Coliseum.online(entrant.characterId()) : null;
            if (chr != null) {
                chr.dropMessage(5, MASTER + ": the seeds: " + draw + ". Stay on channel " + pvp.TOURNAMENT_CHANNEL
                        + "; I call you when your bout is up.");
            }
        }
        log.info("Tournament: {} week {} begins: {} seated ({}), pool {}{}", bracket, week.weekOf(), seated.size(),
                draw, pool, start.forced() ? " (GM)" : "");
    }

    private void stepRun(Run run, long now) {
        if (!run.roundOpen) {
            if (now >= run.nextRoundAtMs) {
                openRound(run, now);
            }
            return;
        }
        for (Map.Entry<Integer, TournamentBout> entry : new ArrayList<>(run.live.entrySet())) {
            TournamentBout bout = entry.getValue();
            bout.step(coliseum, now);
            if (bout.done()) {
                run.live.remove(entry.getKey());
                TournamentDraw.Pair pair = run.draw.pairs().get(bout.pairIndex);
                TournamentRules.Entrant a = run.seated.get(pair.a());
                TournamentRules.Entrant b = run.seated.get(pair.b());
                if (bout.why().startsWith("called off") && !a.human() && !b.human()
                        && present(a, YamlConfig.config.pvp) && present(b, YamlConfig.config.pvp)) {
                    simulate(run, pair, a, b, now); // a watched citizen bout that never opened is fought unseen
                    continue;
                }
                bookLive(run, bout, now);
                decide(run, bout.pairIndex, bout.winnerSide(), bout.why());
            }
        }
        if (!run.draw.roundDone() || !run.live.isEmpty()) {
            return;
        }
        String round = run.roundName();
        if (!run.results.isEmpty()) {
            notice("[Coliseum] " + run.bracket.label + " " + round + ": " + String.join("; ", run.results) + ".");
        }
        run.results.clear();
        run.draw.next();
        run.roundOpen = false;
        if (run.draw.over()) {
            finish(run, now);
        } else {
            run.nextRoundAtMs = Math.max(now, run.roundStartedAtMs + run.roundMs);
            long minutes = Math.max(0, (run.nextRoundAtMs - now + 59_999L) / 60_000L);
            tellSeated(run, MASTER + ": the " + run.roundName() + " start" + (minutes == 0 ? "s now."
                    : "s in " + minutes + " minute" + (minutes == 1 ? "" : "s") + "."));
        }
    }

    private void openRound(Run run, long now) {
        PvpConfig pvp = YamlConfig.config.pvp;
        run.roundOpen = true;
        run.roundStartedAtMs = now;
        String round = run.roundName();
        List<String> pairings = new ArrayList<>();
        List<TournamentDraw.Pair> open = run.draw.open();
        for (TournamentDraw.Pair pair : open) {
            pairings.add(run.seated.get(pair.a()).name() + " v " + run.seated.get(pair.b()).name());
        }
        if (!pairings.isEmpty()) {
            notice("[Coliseum] " + run.bracket.label + " " + round + ": " + String.join(", ", pairings) + ".");
        }
        List<Integer> watchers = new ArrayList<>();
        watching.forEach((id, bracket) -> {
            if (bracket == run.bracket) {
                watchers.add(id);
            }
        });
        boolean watchedOne = false;
        for (TournamentDraw.Pair pair : open) {
            TournamentRules.Entrant a = run.seated.get(pair.a());
            TournamentRules.Entrant b = run.seated.get(pair.b());
            boolean aHere = present(a, pvp);
            boolean bHere = present(b, pvp);
            if (!aHere || !bHere) {
                for (TournamentRules.Entrant absent : aHere ? List.of(b) : bHere ? List.of(a) : List.of(a, b)) {
                    Character chr = absent.human() ? Coliseum.online(absent.characterId()) : null;
                    if (chr != null) {
                        chr.dropMessage(5, MASTER + ": your " + round + " bout was up and you weren't free on channel "
                                + pvp.TOURNAMENT_CHANNEL + ". You forfeit it.");
                    }
                }
                run.fought[pair.a()] |= aHere;
                run.fought[pair.b()] |= bHere;
                int side = aHere ? 0 : bHere ? 1 : TournamentRules.advancing(-1, pair.a() + 1, pair.b() + 1);
                decide(run, pair.index(), side, "a walkover");
                continue;
            }
            run.fought[pair.a()] = true;
            run.fought[pair.b()] = true;
            boolean live = a.human() || b.human();
            List<Integer> those = List.of();
            if (!live && !watchers.isEmpty() && !watchedOne) {
                live = true;
                watchedOne = true;
                those = watchers;
            }
            if (live) {
                TournamentBout bout = new TournamentBout(pair.index(), new int[] {a.characterId(), b.characterId()},
                        new String[] {a.name(), b.name()}, new boolean[] {a.human(), b.human()}, run.bracket, round,
                        coliseum.nextArenaId(), those, now);
                run.live.put(pair.index(), bout);
                for (TournamentRules.Entrant one : List.of(a, b)) {
                    Character chr = one.human() ? Coliseum.online(one.characterId()) : null;
                    if (chr != null) {
                        chr.dropMessage(5, MASTER + ": your " + round + " bout: " + a.name() + " against " + b.name()
                                + ". To the arena!");
                    }
                }
            } else {
                simulate(run, pair, a, b, now);
            }
        }
        if (!watchers.isEmpty() && !watchedOne && run.live.values().stream().noneMatch(bt -> !bt.watchers.isEmpty())) {
            for (TournamentBout bout : run.live.values()) {
                bout.watchers.addAll(watchers); // no citizen bout this round: they watch a player's
                break;
            }
        }
        log.info("Tournament: {} {} opens: {} live, {} decided", run.bracket, round, run.live.size(),
                open.size() - run.live.size());
    }

    /** Free to fight now: a player online on the tournament's channel, alive, out of events; a citizen loaded. */
    private boolean present(TournamentRules.Entrant entrant, PvpConfig pvp) {
        if (entrant.human()) {
            Character chr = Coliseum.online(entrant.characterId());
            return chr != null && chr.getClient() != null && chr.getClient().getChannel() == pvp.TOURNAMENT_CHANNEL
                    && chr.isAlive() && chr.getEventInstance() == null && !DuelRegistry.inDuel(chr.getId())
                    && !coliseum.inMatch(chr.getId()) && !coliseum.watchingGallery(chr.getId());
        }
        CitizenSession session = coliseum.population().presence().session(entrant.characterId());
        Character agent = session == null ? null : session.agent();
        return agent != null && session.loaded() && agent.isAlive() && agent.getMap() != null
                && agent.getEventInstance() == null && !DuelRegistry.inDuel(agent.getId());
    }

    private void simulate(Run run, TournamentDraw.Pair pair, TournamentRules.Entrant a, TournamentRules.Entrant b,
                          long now) {
        PvpConfig pvp = YamlConfig.config.pvp;
        Character ca = coliseum.population().presence().session(a.characterId()).agent();
        Character cb = coliseum.population().presence().session(b.characterId()).agent();
        PvpReference reference = PvpReference.get();
        int winner = Coliseum.simulatedMatch(DuelSim.fighterOf(ca, reference, pvp), DuelSim.fighterOf(cb, reference,
                pvp), pvp, random);
        try {
            matches.insertMatch(LadderStore.TOURNAMENT, now, 0, 0L, winner, "simulated " + run.roundName(), 0.5d,
                    List.of(new LadderStore.Fighter(a.characterId(), 0, false, winner == 1, 0, 0),
                            new LadderStore.Fighter(b.characterId(), 1, false, winner == 0, 0, 0)));
        } catch (RuntimeException failure) {
            log.warn("Tournament: booking {} against {} failed", a.name(), b.name(), failure);
        }
        decide(run, pair.index(), TournamentRules.advancing(winner, pair.a() + 1, pair.b() + 1),
                winner < 0 ? "a draw" : "best of three");
    }

    private void bookLive(Run run, TournamentBout bout, long now) {
        if (bout.why().startsWith("called off")) {
            return;
        }
        try {
            matches.insertMatch(LadderStore.TOURNAMENT, now, bout.arenaId, (now - bout.startedAtMs) / 1000L,
                    bout.winnerSide(), bout.why(), 0.5d, List.of(
                            new LadderStore.Fighter(bout.ids[0], 0, bout.human[0], bout.ko[0], bout.dealt[0],
                                    bout.taken[0]),
                            new LadderStore.Fighter(bout.ids[1], 1, bout.human[1], bout.ko[1], bout.dealt[1],
                                    bout.taken[1])));
        } catch (RuntimeException failure) {
            log.warn("Tournament: booking {} against {} failed", bout.names[0], bout.names[1], failure);
        }
    }

    /** Pair {@code index} is over: {@code side} (0 or 1; -1 for a draw, the better seed) goes on. */
    private void decide(Run run, int index, int side, String why) {
        TournamentDraw.Pair pair = run.draw.pairs().get(index);
        int through = TournamentRules.advancing(side, pair.a() + 1, pair.b() + 1);
        run.draw.decide(index, through);
        TournamentRules.Entrant winner = run.seated.get(through == 0 ? pair.a() : pair.b());
        TournamentRules.Entrant loser = run.seated.get(through == 0 ? pair.b() : pair.a());
        run.results.add(winner.name() + " beat " + loser.name() + (why.equals("best of three") ? ""
                : why.startsWith("best of three ") ? " " + why.substring("best of three ".length()) : " (" + why + ")"));
        if (!loser.human()) {
            coliseum.population().presence().release(loser.characterId());
        } else {
            Character chr = Coliseum.online(loser.characterId());
            if (chr != null) {
                chr.dropMessage(5, MASTER + ": you're out in the " + run.roundName() + ". Your prize comes when the"
                        + " tournament ends.");
            }
        }
    }

    /** The final is over: prizes, the medal, the notices, the letters' news. */
    private void finish(Run run, long now) {
        PvpConfig pvp = YamlConfig.config.pvp;
        int championIndex = run.draw.champion();
        int runnerUpIndex = run.draw.runnerUp();
        TournamentRules.Entrant champion = championIndex < 0 ? null : run.seated.get(championIndex);
        TournamentRules.Entrant runnerUp = runnerUpIndex < 0 ? null : run.seated.get(runnerUpIndex);
        int previousChampion = store.lastDone(run.bracket.name()).map(TournamentStore.Week::championId).orElse(0);
        for (int i = 0; i < run.seated.size(); i++) {
            TournamentRules.Entrant entrant = run.seated.get(i);
            int reached = run.draw.reached(i);
            TournamentRules.Prize prize = run.fought[i] ? TournamentRules.prize(reached, run.pool)
                    : new TournamentRules.Prize(0, 0L);
            boolean paid = true;
            int trophies = entrant.human() ? prize.trophies() : 0;
            if (entrant.human()) {
                Character chr = Coliseum.online(entrant.characterId());
                paid = chr != null && pay(chr, run, reached, trophies, prize.mesos());
                if (!run.fought[i] && chr != null) {
                    chr.dropMessage(5, MASTER + ": you never showed for a bout, so the " + run.bracket.label
                            + " tournament pays you nothing.");
                }
                if (champion != null) {
                    news(entrant.characterId(), run.bracket, reached, champion.name(), trophies, prize.mesos(), paid);
                }
            } else {
                CitizenSession session = coliseum.population().presence().session(entrant.characterId());
                if (prize.mesos() > 0 && session != null && session.agent() != null) {
                    try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_PRIZE)) {
                        session.agent().gainMeso((int) Math.min(Integer.MAX_VALUE, prize.mesos()), false);
                    }
                }
                coliseum.population().presence().release(entrant.characterId());
            }
            store.result(run.week.id(), entrant.characterId(), reached, trophies, prize.mesos(), paid);
        }
        if (champion != null && champion.human()) {
            crown(champion, previousChampion, pvp, now);
        }
        store.state(run.week.id(), TournamentStore.DONE, run.pool, champion == null ? 0 : champion.characterId(),
                runnerUp == null ? 0 : runnerUp.characterId(), now);
        runs.remove(run.bracket);
        watching.values().removeIf(b -> b == run.bracket);
        if (champion != null) {
            notice("[Coliseum] " + champion.name() + " is this week's " + run.bracket.label + " champion"
                    + (runnerUp == null ? "!" : ", beating " + runnerUp.name() + " in the final!"));
            megaphone(champion, runnerUp);
        }
        log.info("Tournament: {} week {} done: champion {}, runner-up {}, pool {}", run.bracket, run.week.weekOf(),
                champion == null ? "-" : champion.name(), runnerUp == null ? "-" : runnerUp.name(), run.pool);
    }

    /** A player's prize, now; false when it must wait (no room for the Trophies). */
    private boolean pay(Character chr, Run run, int reached, int trophies, long mesos) {
        if (chr.isGM() && !run.forced) {
            return true; // a GM in a real run plays for nothing
        }
        if (trophies > 0 && !InventoryManipulator.addById(chr.getClient(), ItemId.EVENT_TROPHY, (short) trophies)) {
            chr.dropMessage(5, MASTER + ": your Etc tab is full; I'll keep your prize until you come to me.");
            return false;
        }
        if (trophies > 0) {
            chr.sendPacket(PacketCreator.getShowItemGain(ItemId.EVENT_TROPHY, (short) trophies, true));
        }
        if (mesos > 0) {
            try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_PRIZE)) {
                chr.gainMeso((int) Math.min(Integer.MAX_VALUE, mesos), true);
            }
        }
        if (trophies > 0 || mesos > 0) {
            chr.dropMessage(5, MASTER + ": the " + run.bracket.label + " tournament is over and you were "
                    + TournamentRules.reachedWords(reached) + ": " + prizeWords(trophies, mesos) + ".");
        }
        return true;
    }

    /** The champion's medal for a week; last week's champion, if online, hands theirs back. */
    private void crown(TournamentRules.Entrant champion, int previousChampion, PvpConfig pvp, long now) {
        Character chr = Coliseum.online(champion.characterId());
        if (previousChampion != 0 && previousChampion != champion.characterId()) {
            Character previous = Coliseum.online(previousChampion);
            if (previous != null && previous.haveItemWithId(MEDAL, false)) { // a worn one runs out on its own
                InventoryManipulator.removeById(previous.getClient(), InventoryType.EQUIP, MEDAL, 1, false, false);
                previous.dropMessage(5, MASTER + ": " + champion.name() + " is the champion now; the medal goes to them.");
            }
        }
        if (chr == null || chr.haveItemWithId(MEDAL, true)) {
            return;
        }
        long expires = now + pvp.TOURNAMENT_MEDAL_DAYS * 24L * 60 * 60 * 1000;
        if (InventoryManipulator.addById(chr.getClient(), MEDAL, (short) 1, expires)) {
            chr.sendPacket(PacketCreator.getShowItemGain(MEDAL, (short) 1, true));
            chr.dropMessage(5, MASTER + ": the champion's medal is yours for " + pvp.TOURNAMENT_MEDAL_DAYS + " days.");
        } else {
            chr.dropMessage(5, MASTER + ": your Equip tab is full, so the champion's medal stays with me this week.");
        }
    }

    /** The champion's own megaphone when a citizen won; else the runner-up's, if a citizen (their own caps). */
    private void megaphone(TournamentRules.Entrant champion, TournamentRules.Entrant runnerUp) {
        TournamentRules.Entrant speaker = !champion.human() ? champion : runnerUp != null && !runnerUp.human()
                ? runnerUp : null;
        if (speaker == null) {
            return;
        }
        CitizenSession session = coliseum.population().presence().session(speaker.characterId());
        if (session == null) {
            return;
        }
        String trigger = speaker == champion ? "tournament.champion" : "tournament.runner_up";
        String other = speaker == champion ? (runnerUp == null ? "" : runnerUp.name()) : champion.name();
        var event = server.living.population.ChatterEngine.Event.of(trigger, session.mapId, session.channel)
                .self(session).subject(other, 0);
        String line = coliseum.population().chatter().compose(event);
        if (line != null && !coliseum.population().chatter().megaphone(session, line, false)) {
            log.debug("Tournament: {}'s megaphone was over its cap", speaker.name());
        }
    }

    /** The letters' news: written whether or not the player is here; told only if they were away for it. */
    private void news(int characterId, LadderRules.Bracket bracket, int reached, String champion, int trophies,
                      long mesos, boolean paid) {
        Population population = coliseum.population();
        if (population.world() == null || population.world().store() == null) {
            return;
        }
        population.world().store().event(characterId, null, NEWS_EVENT,
                bracket.name() + "|" + reached + "|" + champion + "|" + trophies + "|" + mesos + "|" + (paid ? 1 : 0));
    }

    /** The letter for a player who was away for one or more tournaments ({@link #NEWS_EVENT} details); null when none. */
    public static Letter letter(String ownerName, List<String> details) {
        List<String> lines = new ArrayList<>();
        boolean owed = false;
        for (String detail : details) {
            String[] parts = detail.split("\\|");
            if (parts.length < 6) {
                continue;
            }
            try {
                int reached = Integer.parseInt(parts[1]);
                int trophies = Integer.parseInt(parts[3]);
                long mesos = Long.parseLong(parts[4]);
                boolean paid = "1".equals(parts[5]);
                owed |= !paid && (trophies > 0 || mesos > 0);
                String bracket = label(parts[0]);
                String you = reached == TournamentRules.CHAMPION ? "you won it"
                        : "you were " + TournamentRules.reachedWords(reached) + " and " + parts[2] + " won it";
                lines.add("The " + bracket + " tournament: " + you + (trophies > 0 || mesos > 0 ? " (" + prizeWords(
                        trophies, mesos) + ")" : "") + ".");
            } catch (NumberFormatException bad) {
                // an old or broken row: skip it
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        String head = ownerName + ", ";
        String tail = owed ? " Your prize waits with me in Ariant." : " Your prize is in your bags.";
        // a note holds LETTER_MAX characters: drop the oldest tournaments first, so the newest and the tail survive
        while (lines.size() > 1 && (head + String.join(" ", lines) + tail).length() > LETTER_MAX) {
            lines.remove(0);
        }
        String body = String.join(" ", lines);
        int room = LETTER_MAX - head.length() - tail.length();
        if (body.length() > room) {
            body = body.substring(0, Math.max(0, room - 3)) + "...";
        }
        return new Letter(MASTER, head + body + tail);
    }

    private void cancel(Run run, String why, long now) {
        for (TournamentBout bout : new ArrayList<>(run.live.values())) {
            bout.callOff(coliseum, why);
        }
        run.live.clear();
        Map<Integer, Long> fees = new HashMap<>();
        for (TournamentStore.Seat seat : store.seats(run.week.id())) {
            fees.put(seat.characterId(), seat.fee());
        }
        for (TournamentRules.Entrant entrant : run.seated) {
            long fee = fees.getOrDefault(entrant.characterId(), 0L);
            if (!entrant.human()) {
                CitizenSession session = coliseum.population().presence().session(entrant.characterId());
                if (fee > 0 && session != null && session.agent() != null) {
                    try (var ledgerScope = MesoFlows.as(MesoAccount.PVP_FEE)) {
                        session.agent().gainMeso((int) fee, false); // a citizen's fee goes straight back
                    }
                }
                coliseum.population().presence().release(entrant.characterId());
                continue;
            }
            if (fee > 0) {
                store.result(run.week.id(), entrant.characterId(), TournamentRules.NONE, 0, fee, false);
            }
            Character chr = Coliseum.online(entrant.characterId());
            if (chr != null) {
                chr.dropMessage(5, MASTER + ": the " + run.bracket.label + " tournament is off (" + why + "). Talk to me"
                        + " for your fee.");
            }
        }
        store.state(run.week.id(), TournamentStore.CANCELLED, run.pool, 0, 0, now);
        runs.remove(run.bracket);
        log.info("Tournament: {} cancelled: {}", run.bracket, why);
    }

    // ---- the board ---------------------------------------------------------------------------------------------------

    /** The board's tournament page: this week's state a bracket, and the last champion of each. */
    String boardText(long now) {
        StringBuilder text = new StringBuilder("The weekly tournament: " + when() + " on channel "
                + YamlConfig.config.pvp.TOURNAMENT_CHANNEL + ".\r\n\r\n");
        int week = TournamentRules.weekOf(at(now));
        for (LadderRules.Bracket bracket : LadderRules.Bracket.values()) {
            text.append("#b").append(bracket.label).append("#k: ");
            Run run;
            synchronized (this) {
                run = runs.get(bracket);
            }
            if (run != null) {
                text.append(run.roundName()).append(" under way, ").append(remaining(run)).append(" still in.");
            } else {
                Optional<TournamentStore.Week> row = store.week(bracket.name(), week);
                if (row.isPresent() && TournamentStore.DONE.equals(row.get().state())) {
                    text.append("won by ").append(name(row.get().id(), row.get().championId())).append(", over ")
                            .append(name(row.get().id(), row.get().runnerUpId())).append(".");
                } else if (row.isPresent() && TournamentStore.CANCELLED.equals(row.get().state())) {
                    text.append("off this week.");
                } else {
                    int players = row.map(w -> (int) store.seats(w.id()).stream().filter(TournamentStore.Seat::human)
                            .count()).orElse(0);
                    text.append(players).append(" player").append(players == 1 ? "" : "s").append(" signed up.");
                }
                store.lastDone(bracket.name()).filter(w -> w.weekOf() != week || run != null).ifPresent(last ->
                        text.append(" Last champion: ").append(name(last.id(), last.championId())).append("."));
            }
            text.append("\r\n");
        }
        return text.toString();
    }

    private String name(long tournamentId, int characterId) {
        for (TournamentStore.Seat seat : store.seats(tournamentId)) {
            if (seat.characterId() == characterId) {
                return seat.name();
            }
        }
        return "nobody";
    }

    // ---- small helpers ----------------------------------------------------------------------------------------------

    private static int remaining(Run run) {
        int in = 0;
        for (int i = 0; i < run.seated.size(); i++) {
            in += run.draw.reached(i) == TournamentRules.NONE ? 1 : 0;
        }
        return in;
    }

    private void tellSeated(Run run, String line) {
        for (int i = 0; i < run.seated.size(); i++) {
            TournamentRules.Entrant entrant = run.seated.get(i);
            Character chr = entrant.human() && run.draw.reached(i) == TournamentRules.NONE
                    ? Coliseum.online(entrant.characterId()) : null;
            if (chr != null) {
                chr.dropMessage(5, line);
            }
        }
    }

    /** A server notice in every world (rounds and the champion). */
    private static void notice(String text) {
        try {
            for (World world : Server.getInstance().getWorlds()) {
                Server.getInstance().broadcastMessage(world.getId(), PacketCreator.serverNotice(6, text));
            }
        } catch (RuntimeException failure) {
            log.debug("Tournament notice failed: {}", text, failure);
        }
    }

    static String label(String bracketName) {
        for (LadderRules.Bracket bracket : LadderRules.Bracket.values()) {
            if (bracket.name().equals(bracketName)) {
                return bracket.label;
            }
        }
        return bracketName;
    }

    static String prizeWords(int trophies, long mesos) {
        List<String> parts = new ArrayList<>();
        if (trophies > 0) {
            parts.add(trophies + (trophies == 1 ? " Event Trophy" : " Event Trophies"));
        }
        if (mesos > 0) {
            parts.add(mesos(mesos) + " mesos");
        }
        return parts.isEmpty() ? "nothing" : String.join(" and ", parts);
    }

    private static String mesos(long amount) {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount);
    }
}
