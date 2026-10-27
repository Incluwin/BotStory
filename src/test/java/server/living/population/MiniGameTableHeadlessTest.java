package server.living.population;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MiniGame;
import server.maps.MiniGameRules;
import testutil.MiniGameRoom;
import testutil.MiniGameRoom.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Citizens at a minigame table with no client at all (MiniGameTable through MiniGameRules): two citizens play a whole
 * omok game and a whole match-card game, and a human across the table gets a citizen that readies, starts and moves
 * only on its own turns. Steps run from a queue in time order instead of the population's timer.
 */
class MiniGameTableHeadlessTest {
    private static final MiniGameTable.Pace PACE = new MiniGameTable.Pace(1_000L, 8_000L);

    private record Step(long atMs, int order, Runnable run) {
    }

    private final PriorityQueue<Step> steps = new PriorityQueue<>((a, b) -> a.atMs() != b.atMs()
            ? Long.compare(a.atMs(), b.atMs()) : Integer.compare(a.order(), b.order()));
    private long clockMs;
    private int order;
    private final MiniGameTable.Clock clock = (delayMs, step) -> steps.add(new Step(clockMs + delayMs, order++, step));
    private final List<String> heard = new ArrayList<>();
    private final MiniGameTable.Listener listener = new MiniGameTable.Listener() {
        @Override
        public void played(MiniGameTable.Seat seat, server.maps.MiniGame.MiniGameResult result, client.Character opponent) {
            heard.add("played " + seat.chr.getName() + " " + result);
        }

        @Override
        public void left(MiniGameTable.Seat seat) {
            heard.add("left " + seat.chr.getName());
        }

        @Override
        public void say(MiniGameTable.Seat seat, String trigger, client.Character human) {
            heard.add("say " + seat.chr.getName() + " " + trigger);
        }
    };
    private MiniGameRoom room;
    private Player ann;
    private Player bo;

    @BeforeEach
    void setUp() {
        room = new MiniGameRoom();
        ann = room.player("Ann", 1);
        bo = room.player("Bo", 2);
    }

    @AfterEach
    void tearDown() {
        room.close();
    }

    @Test
    void twoCitizensPlayAWholeOmokGameAndTheOneWhoIsDoneGetsUp() {
        MiniGameTable.Seat owner = seat(ann, 0.5d, 1);
        MiniGameTable.Seat visitor = seat(bo, 0.3d, 3);
        assertEquals(MiniGameRules.OK, MiniGameRules.createOmok(ann.chr, "omok", "", 4));
        MiniGame game = ann.game();
        MiniGameTable table = MiniGameTable.watch(game, owner, clock, new Random(3L), PACE, listener);
        assertEquals(MiniGameRules.OK, table.join(visitor, ""));

        runAll();

        List<int[]> moves = omokMoves(ann.packets);
        assertTrue(moves.size() >= 9, "a real game: " + moves.size() + " stones");
        Set<Integer> cells = new HashSet<>();
        for (int i = 0; i < moves.size(); i++) {
            assertEquals(i % 2 == 0 ? 1 : 2, moves.get(i)[2], "the owner opens with stone 1 and turns alternate");
            assertTrue(cells.add(moves.get(i)[1] * 15 + moves.get(i)[0]), "never a taken cell");
        }
        int lastMove = lastIndexStarting(ann.packets, "getMiniGameMoveOmok");
        String result = ann.packets.get(lastMove + 1);
        assertTrue(result.equals("getMiniGameOwnerWin(game,false)") || result.equals("getMiniGameVisitorWin(game,false)"),
                "won by five in a row: " + result);
        verify(ann.chr, times(1)).setMiniGamePoints(eq(bo.chr), anyInt(), eq(true));
        assertEquals(2, heard.stream().filter(line -> line.startsWith("played ")).count());
        assertTrue(heard.stream().noneMatch(line -> line.startsWith("say ")), "no room chat between two citizens");
        assertNull(ann.game(), "the owner played its one game and closed the room");
        assertNull(bo.game());
        assertTrue(heard.containsAll(List.of("left Ann", "left Bo")));
    }

    @Test
    void twoCitizensPlayAWholeMatchCardGame() {
        MiniGameTable.Seat owner = seat(ann, 0.6d, 2);
        MiniGameTable.Seat visitor = seat(bo, 0.2d, 1);
        assertEquals(MiniGameRules.OK, MiniGameRules.createMatchCard(ann.chr, "cards", "", 0));
        MiniGame game = ann.game();
        MiniGameTable table = MiniGameTable.watch(game, owner, clock, new Random(5L), PACE, listener);
        assertEquals(MiniGameRules.OK, table.join(visitor, ""));

        runAll();

        List<String> seconds = ann.packets.stream().filter(line -> line.startsWith("getMatchCardSelect(game,0,")).toList();
        long pairs = seconds.stream().filter(line -> line.endsWith(",2)") || line.endsWith(",3)")).count();
        assertEquals(6, pairs, "every pair of the 12 cards found");
        Set<Integer> gone = new HashSet<>();
        for (String second : seconds) {
            String[] parts = second.substring(second.indexOf('(') + 1, second.length() - 1).split(",");
            int slot = Integer.parseInt(parts[2]);
            int first = Integer.parseInt(parts[3]);
            assertFalse(gone.contains(slot) || gone.contains(first), "a paired card is never turned again");
            assertTrue(slot != first);
            if (parts[4].equals("2") || parts[4].equals("3")) {
                gone.add(slot);
                gone.add(first);
            }
        }
        verify(ann.chr, times(1)).setMiniGamePoints(eq(bo.chr), anyInt(), eq(false));
        assertNotNull(ann.game(), "the owner wanted another game");
        assertNull(bo.game(), "the visitor played its one game and left");
        assertTrue(game.hasFreeSlot());
    }

    @Test
    void aCitizenOwnerReadsAHumanVisitorAndMovesOnlyOnItsOwnTurns() {
        MiniGameTable.Seat owner = seat(ann, 0.5d, 3);
        assertEquals(MiniGameRules.OK, MiniGameRules.createOmok(ann.chr, "omok", "", 0));
        MiniGame game = ann.game();
        MiniGameTable.watch(game, owner, clock, new Random(7L), PACE, listener);
        assertEquals(MiniGameRules.OK, MiniGameRules.visit(bo.chr, game, ""));
        runAll();
        assertFalse(game.isMatchInProgress(), "it waits for the human to ready");

        MiniGameRules.ready(bo.chr, true);
        runAll();
        assertTrue(game.isMatchInProgress(), "it starts the game once the human is ready");
        assertEquals(1, omokMoves(bo.packets).size(), "the owner opens");

        int[][] human = {{0, 0}, {14, 0}, {0, 14}};
        for (int[] cell : human) {
            MiniGameRules.moveOmok(bo.chr, cell[0], cell[1], 2);
            runAll();
        }
        List<int[]> moves = omokMoves(bo.packets);
        assertEquals(7, moves.size(), "one answer to each human stone");
        for (int i = 0; i < moves.size(); i++) {
            assertEquals(i % 2 == 0 ? 1 : 2, moves.get(i)[2]);
        }

        MiniGameRules.leave(bo.chr); // the human walks out mid-game: the citizen wins and stays
        runAll();
        assertTrue(heard.contains("played Ann WIN"));
        assertTrue(heard.contains("say Ann game.win"));
        assertNotNull(ann.game());
        assertTrue(game.hasFreeSlot());
    }

    @Test
    void aCitizenVisitorReadiesInAHumansRoomAndAnswersWithTheOtherStone() {
        assertEquals(MiniGameRules.OK, MiniGameRules.createOmok(ann.chr, "omok", "", 0));
        MiniGame game = ann.game();
        MiniGameTable table = MiniGameTable.watch(game, null, clock, new Random(9L), PACE, listener);
        assertEquals(MiniGameRules.OK, table.join(seat(bo, 0.4d, 2), ""));
        runAll();
        assertTrue(ann.packets.contains("getMiniGameReady(game)"), "the citizen readied");

        MiniGameRules.start(ann.chr);
        runAll();
        assertTrue(omokMoves(ann.packets).isEmpty(), "the human owner opens");
        MiniGameRules.moveOmok(ann.chr, 7, 7, 1);
        runAll();
        List<int[]> moves = omokMoves(ann.packets);
        assertEquals(2, moves.size());
        assertEquals(2, moves.get(1)[2], "the citizen plays the other stone");
    }

    @Test
    void aCitizenDoneWithItsGamesLeavesEvenWhenTheHumanReadiesAgainAtOnce() {
        MiniGameTable.Seat owner = seat(ann, 0.5d, 1);
        assertEquals(MiniGameRules.OK, MiniGameRules.createOmok(ann.chr, "omok", "", 0));
        MiniGame game = ann.game();
        MiniGameTable.watch(game, owner, clock, new Random(11L), PACE, listener);
        MiniGameRules.visit(bo.chr, game, "");
        MiniGameRules.ready(bo.chr, true);
        runAll();
        assertTrue(game.isMatchInProgress());

        MiniGameRules.giveUp(bo.chr);
        MiniGameRules.ready(bo.chr, true); // before the citizen got up after its "bye"
        runAll();

        assertNull(ann.game(), "it closed its room instead of starting another game");
        verify(ann.chr, times(1)).setMiniGamePoints(eq(bo.chr), anyInt(), eq(true));
        assertTrue(heard.contains("say Ann game.bye"));
    }

    @Test
    void aTieOfferDuringTheCitizensTurnNeverStallsTheGame() {
        MiniGameTable.Seat owner = seat(ann, 0.5d, 3);
        assertEquals(MiniGameRules.OK, MiniGameRules.createOmok(ann.chr, "omok", "", 0));
        MiniGame game = ann.game();
        MiniGameTable.watch(game, owner, clock, new Random(13L), PACE, listener);
        MiniGameRules.visit(bo.chr, game, "");
        MiniGameRules.ready(bo.chr, true);
        runAll();
        for (int round = 0; round < 3; round++) { // the citizen has four stones at most: no five yet
            MiniGameRules.moveOmok(bo.chr, round * 3, 14, 2);
            MiniGameRules.requestTie(bo.chr); // the citizen's move is pending: the offer drops it
            int before = omokMoves(bo.packets).size();
            runAll();
            boolean tied = bo.packets.contains("getMiniGameTie(game)");
            assertTrue(tied || omokMoves(bo.packets).size() == before + 1, "it answered and moved on");
            if (tied) {
                return;
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static MiniGameTable.Seat seat(Player player, double strength, int games) {
        return new MiniGameTable.Seat(player.chr, strength, games, Runnable::run, null);
    }

    private void runAll() {
        for (int guard = 0; !steps.isEmpty(); guard++) {
            assertTrue(guard < 20_000, "the table never settles");
            Step step = steps.poll();
            clockMs = step.atMs();
            step.run().run();
        }
    }

    /** The omok stones a player saw placed, in order: x, y, stone. */
    private static List<int[]> omokMoves(List<String> packets) {
        List<int[]> out = new ArrayList<>();
        for (String packet : packets) {
            if (packet.startsWith("getMiniGameMoveOmok(game,")) {
                String[] parts = packet.substring(packet.indexOf(',') + 1, packet.length() - 1).split(",");
                out.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
            }
        }
        return out;
    }

    private static int lastIndexStarting(List<String> packets, String prefix) {
        for (int i = packets.size() - 1; i >= 0; i--) {
            if (packets.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
