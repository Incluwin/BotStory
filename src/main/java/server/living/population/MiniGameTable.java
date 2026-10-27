package server.living.population;

import client.Character;
import server.maps.MiniGame;
import server.maps.MiniGame.MiniGameResult;
import server.maps.MiniGameObserver;
import server.maps.MiniGameRules;

import java.util.Random;
import java.util.function.Consumer;

/**
 * One minigame room with a citizen in it (docs/BOT_CONCEPT_PLAN.md F5a): its citizen seats, the game as a player at
 * the table knows it (the stones on the board, the cards seen), and when each citizen acts. It watches the room
 * through its observer and plays through MiniGameRules, so a human across the table sees an ordinary player.
 *
 * <p>Turns, as the client keeps them (the server keeps none): the start packet's byte is the seat that does not open,
 * so a room's first game opens with its owner and every later one with the last game's loser (MiniGame.setLoser). In
 * omok the opener plays stone 1; a stone the other side places corrects that. In match cards a pair keeps the turn and
 * a miss passes it; the other side turning a first card is always its turn. Every observed step bumps the generation,
 * and a step scheduled before it is dropped.
 */
final class MiniGameTable implements MiniGameObserver {
    /** Runs a step later: the population's timer live, a queue in tests. */
    interface Clock {
        void after(long delayMs, Runnable step);
    }

    /** What the citizens' side hears from the table. */
    interface Listener {
        /** A match ended for a citizen seat; {@code opponent} is the other side's character. */
        void played(Seat seat, MiniGameResult result, Character opponent);

        /** A citizen seat is no longer in this room. */
        void left(Seat seat);

        /** A line for the room chat ({@code trigger} game.*), only while a human sits across the table. */
        void say(Seat seat, String trigger, Character human);

        Listener NONE = new Listener() {
            @Override
            public void played(Seat seat, MiniGameResult result, Character opponent) {
            }

            @Override
            public void left(Seat seat) {
            }

            @Override
            public void say(Seat seat, String trigger, Character human) {
            }
        };
    }

    /** A citizen in the room. */
    static final class Seat {
        final Character chr;
        /** 0..1: how well it plays (OmokAi, MatchCardMemory). */
        final double strength;
        /** Runs a step as this citizen: on its own Agent tick when external writes are routed there. */
        final Consumer<Runnable> runner;
        /** Its population session; null in tests. */
        final CitizenSession session;
        /** Matches it still wants to play here. */
        volatile int gamesLeft;
        MatchCardMemory memory;

        Seat(Character chr, double strength, int games, Consumer<Runnable> runner, CitizenSession session) {
            this.chr = chr;
            this.strength = strength;
            this.gamesLeft = games;
            this.runner = runner;
            this.session = session;
        }
    }

    /** Think times: the usual move takes {@code minMs}..{@code maxMs}, mostly near the short end. */
    record Pace(long minMs, long maxMs) {
    }

    private final MiniGame game;
    private final Clock clock;
    private final Random random;
    private final Pace pace;
    private final Listener listener;
    private Seat ownerSeat;
    private Seat visitorSeat;
    /** A citizen on its way into the free seat (MiniGameRules.visit tells the table before it returns). */
    private Seat expected;
    private int generation;
    private volatile long lastEventMs = System.currentTimeMillis();
    private boolean ownerToMove;
    private final int[] board = new int[OmokAi.CELLS];
    private int stones;
    /** The owner's omok stone this game. */
    private int ownerStone = 1;
    /** The first card of the turn on now, or -1. */
    private int firstSlot = -1;
    private int firstCard;

    private MiniGameTable(MiniGame game, Seat owner, Clock clock, Random random, Pace pace, Listener listener) {
        this.game = game;
        this.ownerSeat = owner;
        this.clock = clock;
        this.random = random;
        this.pace = pace;
        this.listener = listener;
    }

    /** Watches {@code game}: a citizen's own room ({@code owner} its seat) or a human's ({@code owner} null). */
    static MiniGameTable watch(MiniGame game, Seat owner, Clock clock, Random random, Pace pace, Listener listener) {
        MiniGameTable table = new MiniGameTable(game, owner, clock, random, pace, listener);
        game.setObserver(table);
        return table;
    }

    MiniGame game() {
        return game;
    }

    synchronized Seat ownerSeat() {
        return ownerSeat;
    }

    synchronized Seat visitorSeat() {
        return visitorSeat;
    }

    /** When anything last happened in the room. */
    long lastEventMs() {
        return lastEventMs;
    }

    /** Sits {@code seat} down in the free seat as a visitor; the refusal's error code, or MiniGameRules.OK. */
    int join(Seat seat, String password) {
        synchronized (this) {
            expected = seat;
        }
        int status = MiniGameRules.visit(seat.chr, game, password);
        synchronized (this) {
            expected = null;
        }
        return status;
    }

    /** {@code seat} gets up now: the owner closes its room, a visitor leaves it (a match on is forfeited, as stock). */
    void leave(Seat seat) {
        seat.runner.accept(() -> MiniGameRules.leave(seat.chr));
    }

    // ---- what the room tells the table -------------------------------------------------------

    @Override
    public synchronized void visitorJoined(MiniGame game, Character visitor) {
        bump();
        if (expected != null && expected.chr == visitor) {
            visitorSeat = expected;
            schedule(visitorSeat, think(2_000L, 6_000L), seat -> MiniGameRules.ready(seat.chr, true));
        } else if (ownerSeat != null && random.nextInt(100) < 60) {
            listener.say(ownerSeat, "game.hi", visitor);
        }
    }

    @Override
    public synchronized void visitorLeft(MiniGame game, Character visitor) {
        bump();
        if (visitorSeat != null && visitorSeat.chr == visitor) {
            Seat gone = visitorSeat;
            visitorSeat = null;
            listener.left(gone);
        }
    }

    @Override
    public synchronized void ready(MiniGame game, boolean ready) {
        bump();
        if (ready && ownerSeat != null && !game.isMatchInProgress()) {
            schedule(ownerSeat, think(1_500L, 4_000L), seat -> MiniGameRules.start(seat.chr));
        }
    }

    @Override
    public synchronized void started(MiniGame game) {
        bump();
        ownerToMove = game.getLoser() == 1;
        java.util.Arrays.fill(board, 0);
        stones = 0;
        ownerStone = ownerToMove ? 1 : 2;
        firstSlot = -1;
        int cards = game.isOmok() ? 0 : game.getMatchesToWin() * 2;
        for (Seat seat : seats()) {
            seat.memory = game.isOmok() ? null : MatchCardMemory.forStrength(cards, seat.strength, random);
        }
        nextTurn(think(1_000L, 3_000L));
    }

    @Override
    public synchronized void omokMoved(MiniGame game, Character mover, int x, int y, int type) {
        bump();
        int cell = y * OmokAi.SIZE + x;
        boolean byOwner = game.isOwner(mover);
        Seat across = byOwner ? visitorSeat : ownerSeat;
        if (across != null && seatOf(mover) == null && blockedFive(cell, 3 - type) && random.nextInt(100) < 35) {
            listener.say(across, "game.nice", mover); // the human took the cell its five needed
        }
        board[cell] = type;
        stones++;
        ownerStone = byOwner ? type : 3 - type;
        ownerToMove = !byOwner;
        nextTurn(omokThink());
    }

    @Override
    public synchronized void cardTurned(MiniGame game, Character chooser, int slot, int cardId, boolean first,
                                        int firstSlot, boolean matched) {
        bump();
        for (Seat seat : seats()) {
            if (seat.memory != null) {
                seat.memory.saw(slot, cardId);
            }
        }
        boolean byOwner = game.isOwner(chooser);
        ownerToMove = byOwner;
        if (first) {
            this.firstSlot = slot;
            this.firstCard = cardId;
            Seat seat = seatOf(chooser);
            if (seat != null) {
                schedule(seat, think(700L, 1_600L), this::secondCard);
            }
            return;
        }
        this.firstSlot = -1;
        if (matched) {
            for (Seat seat : seats()) {
                if (seat.memory != null) {
                    seat.memory.paired(firstSlot, slot);
                }
            }
            Seat across = byOwner ? visitorSeat : ownerSeat;
            if (across != null && seatOf(chooser) == null && random.nextInt(100) < 15) {
                listener.say(across, "game.nice", chooser);
            }
        } else {
            ownerToMove = !byOwner;
        }
        nextTurn(think(pace.minMs(), Math.max(pace.minMs(), pace.maxMs() / 2)));
    }

    @Override
    public synchronized void skipped(MiniGame game, Character skipper) {
        bump();
        ownerToMove = !game.isOwner(skipper);
        firstSlot = -1;
        nextTurn(think(1_000L, 3_000L));
    }

    @Override
    public synchronized void tieOffered(MiniGame game, Character asker) {
        bump();
        Seat answerer = game.isOwner(asker) ? visitorSeat : ownerSeat;
        if (answerer == null) {
            return;
        }
        // a crowded omok board is a fair draw; otherwise most players want to finish
        boolean accept = game.isOmok() && stones > 150 || random.nextInt(100) < 20;
        schedule(answerer, think(2_000L, 5_000L), seat -> MiniGameRules.answerTie(seat.chr, accept));
    }

    @Override
    public synchronized void matchEnded(MiniGame game, MiniGameResult ownerResult, boolean forfeit) {
        bump();
        firstSlot = -1;
        Character owner = game.getOwner();
        Character visitor = game.getVisitor();
        for (Seat seat : seats()) {
            boolean isOwner = seat == ownerSeat;
            MiniGameResult mine = isOwner ? ownerResult : flip(ownerResult);
            Character opponent = isOwner ? visitor : owner;
            seat.gamesLeft--;
            listener.played(seat, mine, opponent);
            if (opponent != null && seatOf(opponent) == null) {
                listener.say(seat, mine == MiniGameResult.WIN ? "game.win" : mine == MiniGameResult.LOSS ? "game.loss"
                        : "game.tie", opponent);
            }
        }
        Seat done = ownerSeat != null && ownerSeat.gamesLeft <= 0 ? ownerSeat
                : visitorSeat != null && visitorSeat.gamesLeft <= 0 ? visitorSeat : null;
        if (done != null) {
            Character opponent = done == ownerSeat ? visitor : owner;
            if (opponent != null && seatOf(opponent) == null) {
                listener.say(done, "game.bye", opponent);
            }
            schedule(done, think(3_000L, 7_000L), seat -> MiniGameRules.leave(seat.chr));
        } else if (visitorSeat != null) {
            schedule(visitorSeat, think(3_000L, 8_000L), seat -> MiniGameRules.ready(seat.chr, true));
        }
    }

    @Override
    public synchronized void closed(MiniGame game) {
        bump();
        game.setObserver(null);
        for (Seat seat : seats()) {
            listener.left(seat);
        }
        ownerSeat = null;
        visitorSeat = null;
    }

    // ---- the citizens' turns -----------------------------------------------------------------

    /** Schedules the move of the side whose turn it is, when that side is a citizen. */
    private void nextTurn(long delayMs) {
        Seat mover = ownerToMove ? ownerSeat : visitorSeat;
        if (mover != null && game.isMatchInProgress()) {
            schedule(mover, delayMs, game.isOmok() ? this::omokTurn : this::firstCard);
        }
    }

    private void omokTurn(Seat seat) {
        int cell;
        int stone;
        synchronized (this) {
            if (!myTurn(seat)) {
                return;
            }
            stone = seat == ownerSeat ? ownerStone : 3 - ownerStone;
            cell = OmokAi.choose(board.clone(), stone, seat.strength, random);
        }
        if (cell >= 0) {
            MiniGameRules.moveOmok(seat.chr, cell % OmokAi.SIZE, cell / OmokAi.SIZE, stone);
        } else {
            MiniGameRules.requestTie(seat.chr); // a full board: nobody can win
        }
    }

    private void firstCard(Seat seat) {
        int slot;
        synchronized (this) {
            if (!myTurn(seat) || firstSlot >= 0 || seat.memory == null) {
                return;
            }
            slot = seat.memory.first();
        }
        if (slot >= 0) {
            MiniGameRules.selectCard(seat.chr, 1, slot);
        }
    }

    private void secondCard(Seat seat) {
        int slot;
        synchronized (this) {
            if (!myTurn(seat) || firstSlot < 0 || seat.memory == null) {
                return;
            }
            slot = seat.memory.second(firstSlot, firstCard);
        }
        if (slot >= 0) {
            MiniGameRules.selectCard(seat.chr, 0, slot);
        }
    }

    private boolean myTurn(Seat seat) {
        return game.isMatchInProgress() && (ownerToMove ? ownerSeat : visitorSeat) == seat;
    }

    /** Runs {@code step} for {@code seat} after {@code delayMs}, unless anything happens in the room meanwhile. */
    private void schedule(Seat seat, long delayMs, Consumer<Seat> step) {
        int asked = generation;
        clock.after(delayMs, () -> {
            if (stale(asked)) {
                return;
            }
            seat.runner.accept(() -> {
                if (!stale(asked)) {
                    step.accept(seat);
                }
            });
        });
    }

    private synchronized boolean stale(int asked) {
        return asked != generation;
    }

    private void bump() {
        generation++;
        lastEventMs = System.currentTimeMillis();
    }

    // ---- small parts ---------------------------------------------------------------------------

    private Seat[] seats() {
        if (ownerSeat != null && visitorSeat != null) {
            return new Seat[]{ownerSeat, visitorSeat};
        }
        return ownerSeat != null ? new Seat[]{ownerSeat} : visitorSeat != null ? new Seat[]{visitorSeat} : new Seat[0];
    }

    private Seat seatOf(Character chr) {
        if (ownerSeat != null && ownerSeat.chr == chr) {
            return ownerSeat;
        }
        return visitorSeat != null && visitorSeat.chr == chr ? visitorSeat : null;
    }

    /** True when a stone of {@code side} on the empty {@code cell} would have made five. */
    private boolean blockedFive(int cell, int side) {
        if (board[cell] != 0) {
            return false;
        }
        board[cell] = side;
        boolean five = OmokAi.five(board, cell, side);
        board[cell] = 0;
        return five;
    }

    /** An omok move's think time: the first stones come quickly, a crowded board takes longer. */
    private long omokThink() {
        if (stones < 4) {
            return think(pace.minMs(), Math.max(pace.minMs(), pace.minMs() * 3));
        }
        return think(pace.minMs(), pace.maxMs());
    }

    /** A delay in {@code minMs}..{@code maxMs}, mostly near the short end, as people mostly answer quickly. */
    private long think(long minMs, long maxMs) {
        double u = random.nextDouble();
        return minMs + (long) ((maxMs - minMs) * u * u);
    }

    private static MiniGameResult flip(MiniGameResult result) {
        return result == MiniGameResult.WIN ? MiniGameResult.LOSS : result == MiniGameResult.LOSS ? MiniGameResult.WIN
                : MiniGameResult.TIE;
    }
}
