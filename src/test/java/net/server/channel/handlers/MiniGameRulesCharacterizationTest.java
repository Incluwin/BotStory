package net.server.channel.handlers;

import net.packet.OutPacket;
import net.server.channel.handlers.PlayerInteractionHandler.Action;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.MiniGame;
import testutil.MiniGameRoom;
import testutil.MiniGameRoom.Player;
import testutil.Packets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the stock omok and match-card rules as players' packets drive them through {@link PlayerInteractionHandler}
 * (create, visit, ready, start, moves, card picks, give up, tie, skip, chat, expel, exit), so extracting the rules into
 * MiniGameRules changes nothing a client sees. Nothing here depends on the omok win scan's column range or on the board
 * being kept after a give-up: MiniGameOmokWinFixTest covers what Cosmic PR #294 changes.
 */
class MiniGameRulesCharacterizationTest {
    private final PlayerInteractionHandler handler = new PlayerInteractionHandler();
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

    // ---- create ----------------------------------------------------------------------------------

    @Test
    void creatingAnOmokRoomAnnouncesItsBalloonAndOpensTheOwnersWindow() {
        send(ann, createOmok("omok", null, 3));

        MiniGame game = ann.game();
        assertNotNull(game);
        assertEquals(List.of(game), room.games());
        assertTrue(game.isOmok());
        assertEquals(3, game.getPieceType());
        assertEquals("omok", game.getDescription());
        assertTrue(game.isOwner(ann.chr));
        assertEquals(List.of("addOmokBox(Ann,1,0)"), room.mapPackets);
        assertEquals(List.of("getMiniGame(client:Ann,game,true,3)"), ann.packets);
        verify(ann.chr).haveItem(4080003);
    }

    @Test
    void anOmokPieceTypeIsClampedToTheTwelveSets() {
        send(ann, createOmok("omok", null, 14));
        assertEquals(11, ann.game().getPieceType());
        verify(ann.chr).haveItem(4080011);
    }

    @Test
    void creatingARoomIsRefusedWithTheStockErrors() {
        when(ann.chr.isAlive()).thenReturn(false);
        send(ann, createOmok("omok", null, 0));
        assertEquals(List.of("getMiniRoomError(4)"), ann.packets);

        when(ann.chr.isAlive()).thenReturn(true);
        when(room.map.getFieldLimit()).thenReturn(0x80);
        room.clearPackets();
        send(ann, createOmok("omok", null, 0));
        assertEquals(List.of("getMiniRoomError(11)"), ann.packets);

        when(room.map.getFieldLimit()).thenReturn(0);
        when(ann.chr.getChalkboard()).thenReturn("selling");
        room.clearPackets();
        send(ann, createMatchCard("cards", null, 0));
        assertEquals(List.of("getMiniRoomError(13)"), ann.packets);

        when(ann.chr.getChalkboard()).thenReturn(null);
        when(ann.chr.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        room.clearPackets();
        send(ann, createOmok("omok", null, 0));
        assertEquals(List.of("getMiniRoomError(5)"), ann.packets);

        when(ann.chr.getEventInstance()).thenReturn(null);
        when(ann.chr.haveItem(anyInt())).thenReturn(false);
        room.clearPackets();
        send(ann, createMatchCard("cards", null, 0));
        assertEquals(List.of("getMiniRoomError(6)"), ann.packets);

        assertNull(ann.game());
        assertTrue(room.games().isEmpty());
        assertTrue(room.mapPackets.isEmpty());
    }

    @Test
    void aMatchCardRoomDealsPairsForItsBoardSize() {
        int[][] sizes = {{0, 6}, {1, 10}, {2, 15}, {7, 15}};
        for (int[] size : sizes) {
            Player owner = room.player("Owner" + size[0], 10 + size[0]);
            send(owner, createMatchCard("cards", null, size[0]));
            MiniGame game = owner.game();
            assertFalse(game.isOmok());
            assertEquals(size[1], game.getMatchesToWin());
            assertEquals(Math.min(size[0], 2), game.getPieceType());
            Map<Integer, Integer> counts = new HashMap<>();
            for (int slot = 0; slot < size[1] * 2; slot++) {
                counts.merge(game.getCardId(slot), 1, Integer::sum);
            }
            assertEquals(size[1], counts.size());
            assertTrue(counts.values().stream().allMatch(count -> count == 2));
            assertEquals(List.of("getMatchCard(client:" + owner.name + ",game,true," + Math.min(size[0], 2) + ")"),
                    owner.packets);
            verify(owner.chr).haveItem(4080100);
        }
        assertEquals(List.of("addMatchCardBox(Owner0,1,0)", "addMatchCardBox(Owner1,1,0)", "addMatchCardBox(Owner2,1,0)",
                "addMatchCardBox(Owner7,1,0)"), room.mapPackets);
    }

    // ---- visit -----------------------------------------------------------------------------------

    @Test
    void aVisitorWithTheRightPasswordSitsDownAndTheBalloonShowsTwo() {
        send(ann, createOmok("omok", "Secret", 2));
        MiniGame game = ann.game();
        room.clearPackets();

        send(bo, visit(game, "nope"));
        assertEquals(List.of("getMiniRoomError(22)"), bo.packets);
        assertNull(bo.game());

        room.clearPackets();
        send(bo, visit(game, "secret"));
        assertSame(game, bo.game());
        assertTrue(game.isVisitor(bo.chr));
        assertEquals(List.of("getMiniGameNewVisitor(game,Bo,1)"), ann.packets);
        assertEquals(List.of("addOmokBox(Ann,2,0)"), room.mapPackets);
        assertEquals(List.of("getMiniGame(client:Bo,game,false,2)"), bo.packets);

        Player cy = room.player("Cy", 3);
        send(cy, visit(game, "secret"));
        assertEquals(List.of("getMiniRoomError(2)"), cy.packets);
        assertNull(cy.game());
    }

    @Test
    void aMatchCardVisitorGetsTheCardWindow() {
        send(ann, createMatchCard("cards", null, 1));
        room.clearPackets();
        send(bo, visit(ann.game(), null));
        assertEquals(List.of("getMatchCardNewVisitor(game,Bo,1)"), ann.packets);
        assertEquals(List.of("addMatchCardBox(Ann,2,0)"), room.mapPackets);
        assertEquals(List.of("getMatchCard(client:Bo,game,false,1)"), bo.packets);
    }

    // ---- ready, start ----------------------------------------------------------------------------

    @Test
    void readyAndStartAreBroadcastAndTheBalloonShowsAGameOn() {
        MiniGame game = seatedOmok();
        room.clearPackets();

        send(bo, plain(Action.READY));
        assertEquals(List.of("getMiniGameReady(game)"), ann.packets);
        assertEquals(List.of("getMiniGameReady(game)"), bo.packets);
        send(bo, plain(Action.UN_READY));
        send(bo, plain(Action.READY));
        room.clearPackets();

        send(ann, plain(Action.START));
        assertTrue(game.isMatchInProgress());
        assertEquals(List.of("getMiniGameStart(game,1)"), ann.packets);
        assertEquals(List.of("getMiniGameStart(game,1)"), bo.packets);
        assertEquals(List.of("addOmokBox(Ann,2,1)"), room.mapPackets);
    }

    // ---- omok moves ------------------------------------------------------------------------------

    @Test
    void fiveInARowWinsForTheOwnerAndClearsTheBoard() {
        MiniGame game = startedOmok();
        for (int x = 0; x < 4; x++) {
            send(ann, move(x, 7, 1));
            send(bo, move(x, 9, 2));
        }
        room.clearPackets();
        send(bo, move(0, 7, 2)); // taken: nothing happens
        assertTrue(ann.packets.isEmpty());

        send(ann, move(4, 7, 1));
        assertEquals(List.of("getMiniGameMoveOmok(game,4,7,1)", "getMiniGameOwnerWin(game,false)"), ann.packets);
        assertEquals(List.of("getMiniGameMoveOmok(game,4,7,1)", "getMiniGameOwnerWin(game,false)"), bo.packets);
        assertEquals(List.of("addOmokBox(Ann,2,0)"), room.mapPackets);
        verify(ann.chr).setMiniGamePoints(bo.chr, 1, true);
        assertFalse(game.isMatchInProgress());
        assertEquals(0, game.getLoser());
        assertEquals(50, game.getOwnerScore());
        assertEquals(15, game.getVisitorScore());

        room.clearPackets();
        send(ann, move(0, 7, 1)); // the board was cleared
        assertEquals(List.of("getMiniGameMoveOmok(game,0,7,1)"), ann.packets);
    }

    @Test
    void theVisitorWinsOnADiagonalAndALineDownLeft() {
        MiniGame game = startedOmok();
        int[][] owner = {{0, 0}, {1, 0}, {2, 0}, {14, 14}};
        for (int i = 0; i < 4; i++) {
            send(ann, move(owner[i][0], owner[i][1], 1));
            send(bo, move(2 + i, 2 + i, 2));
        }
        room.clearPackets();
        send(bo, move(6, 6, 2));
        assertEquals(List.of("getMiniGameMoveOmok(game,6,6,2)", "getMiniGameVisitorWin(game,false)"), bo.packets);
        verify(ann.chr).setMiniGamePoints(bo.chr, 2, true);
        assertEquals(1, game.getLoser());

        startAgain(game);
        int[][] downLeft = {{10, 0}, {9, 1}, {8, 2}, {7, 3}, {6, 4}};
        room.clearPackets();
        for (int[] cell : downLeft) {
            send(ann, move(cell[0], cell[1], 1));
        }
        assertTrue(ann.packets.contains("getMiniGameOwnerWin(game,false)"));
    }

    @Test
    void aVerticalLineInTheLeftColumnsWins() {
        startedOmok();
        room.clearPackets();
        for (int y = 3; y < 8; y++) {
            send(ann, move(2, y, 1));
        }
        assertEquals("getMiniGameOwnerWin(game,false)", ann.packets.getLast());
        verify(ann.chr).setMiniGamePoints(bo.chr, 1, true);
    }

    @Test
    void fourInARowAndALineBrokenByTheOtherPieceDoNotWin() {
        MiniGame game = startedOmok();
        for (int x = 0; x < 4; x++) {
            send(ann, move(x, 5, 1));
        }
        send(bo, move(4, 5, 2));
        send(ann, move(5, 5, 1));
        assertTrue(game.isMatchInProgress());
        verify(ann.chr, never()).setMiniGamePoints(any(), anyInt(), anyBoolean());
    }

    // ---- give up, tie, skip ----------------------------------------------------------------------

    @Test
    void givingUpHandsTheOtherSideAForfeitWin() {
        MiniGame game = startedOmok();
        room.clearPackets();
        send(ann, plain(Action.GIVE_UP));
        assertEquals(List.of("getMiniGameVisitorWin(game,true)"), ann.packets);
        assertEquals(List.of("getMiniGameVisitorWin(game,true)"), bo.packets);
        verify(ann.chr).setMiniGamePoints(bo.chr, 2, true);
        assertEquals(50, game.getVisitorScore());
        assertEquals(-15, game.getOwnerScore());
        assertFalse(game.isMatchInProgress());

        room.clearPackets();
        send(bo, plain(Action.GIVE_UP)); // no game on: nothing
        assertTrue(ann.packets.isEmpty());

        startAgain(game);
        room.clearPackets();
        send(bo, plain(Action.GIVE_UP));
        assertEquals(List.of("getMiniGameOwnerWin(game,true)"), ann.packets);
        verify(ann.chr).setMiniGamePoints(bo.chr, 1, true);
    }

    @Test
    void aTieOfferGoesToTheOtherSideAndADenialStopsTheAskerOfferingAgain() {
        MiniGame game = startedOmok();
        room.clearPackets();
        send(ann, plain(Action.REQUEST_TIE));
        assertTrue(ann.packets.isEmpty());
        assertEquals(List.of("getMiniGameRequestTie(game)"), bo.packets);

        room.clearPackets();
        send(bo, answerTie(false));
        assertEquals(List.of("getMiniGameDenyTie(game)"), ann.packets);
        assertTrue(game.isTieDenied(ann.chr));
        assertTrue(game.isMatchInProgress());

        room.clearPackets();
        send(ann, plain(Action.REQUEST_TIE));
        assertTrue(bo.packets.isEmpty());
        send(bo, plain(Action.REQUEST_TIE));
        assertEquals(List.of("getMiniGameRequestTie(game)"), ann.packets);

        room.clearPackets();
        send(ann, answerTie(true));
        assertEquals(List.of("getMiniGameTie(game)"), ann.packets);
        assertEquals(List.of("getMiniGameTie(game)"), bo.packets);
        verify(ann.chr).setMiniGamePoints(bo.chr, 3, true);
        assertEquals(10, game.getOwnerScore());
        assertEquals(10, game.getVisitorScore());
        assertFalse(game.isMatchInProgress());

        startAgain(game);
        send(ann, plain(Action.REQUEST_TIE));
        send(bo, answerTie(true));
        assertEquals(10, game.getOwnerScore()); // a second tie within five minutes scores nothing
    }

    @Test
    void skippingIsBroadcastForEitherSide() {
        startedOmok();
        room.clearPackets();
        send(ann, plain(Action.SKIP));
        send(bo, plain(Action.SKIP));
        assertEquals(List.of("getMiniGameSkipOwner(game)", "getMiniGameSkipVisitor(game)"), ann.packets);
        assertEquals(List.of("getMiniGameSkipOwner(game)", "getMiniGameSkipVisitor(game)"), bo.packets);
    }

    @Test
    void roomChatReachesBothSeats() {
        seatedOmok();
        room.clearPackets();
        send(bo, chat("hi"));
        assertEquals(List.of("getPlayerShopChat(Bo,hi,false)"), ann.packets);
        assertEquals(List.of("getPlayerShopChat(Bo,hi,false)"), bo.packets);
    }

    // ---- match cards -----------------------------------------------------------------------------

    @Test
    void cardPicksShowTheOtherSideAndPairsScoreUntilTheBoardIsCleared() {
        send(ann, createMatchCard("cards", null, 0));
        MiniGame game = ann.game();
        send(bo, visit(game, null));
        send(bo, plain(Action.READY));
        room.clearPackets();
        send(ann, plain(Action.START));
        assertTrue(game.isMatchInProgress());
        assertEquals(List.of("getMatchCardStart(game,1)"), bo.packets);
        assertEquals(List.of("addMatchCardBox(Ann,2,1)"), room.mapPackets);

        List<int[]> pairs = pairs(game, 12);
        int[] miss = mismatch(game, 12);

        room.clearPackets();
        send(ann, card(1, miss[0]));
        assertTrue(ann.packets.isEmpty());
        assertEquals(List.of("getMatchCardSelect(game,1," + miss[0] + ",0,1)"), bo.packets);
        send(ann, card(0, miss[1]));
        assertEquals("getMatchCardSelect(game,0," + miss[1] + "," + miss[0] + ",0)", ann.packets.getLast());

        room.clearPackets();
        send(bo, card(1, miss[0]));
        assertTrue(bo.packets.isEmpty());
        assertEquals(List.of("getMatchCardSelect(game,1," + miss[0] + "," + miss[0] + ",1)"), ann.packets);
        send(bo, card(0, miss[1]));
        assertEquals("getMatchCardSelect(game,0," + miss[1] + "," + miss[0] + ",1)", bo.packets.getLast());

        // the owner finds four pairs and the visitor two: the owner wins on the last pair
        for (int i = 0; i < pairs.size(); i++) {
            Player picker = i < 4 ? ann : bo;
            room.clearPackets();
            send(picker, card(1, pairs.get(i)[0]));
            send(picker, card(0, pairs.get(i)[1]));
            String expected = "getMatchCardSelect(game,0," + pairs.get(i)[1] + "," + pairs.get(i)[0] + "," + (i < 4 ? 2 : 3) + ")";
            assertEquals(expected, picker.packets.get(picker.packets.size() - (i == pairs.size() - 1 ? 2 : 1)));
        }
        assertEquals("getMiniGameOwnerWin(game,false)", ann.packets.getLast());
        verify(ann.chr).setMiniGamePoints(bo.chr, 1, false);
        assertFalse(game.isMatchInProgress());
        assertEquals(List.of("addOmokBox(Ann,2,0)"), room.mapPackets);
    }

    @Test
    void anEvenSplitOfPairsIsATie() {
        send(ann, createMatchCard("cards", null, 0));
        MiniGame game = ann.game();
        send(bo, visit(game, null));
        send(ann, plain(Action.START));
        List<int[]> pairs = pairs(game, 12);
        for (int i = 0; i < pairs.size(); i++) {
            Player picker = i % 2 == 0 ? ann : bo;
            send(picker, card(1, pairs.get(i)[0]));
            send(picker, card(0, pairs.get(i)[1]));
        }
        assertEquals("getMiniGameTie(game)", ann.packets.getLast());
        verify(ann.chr).setMiniGamePoints(bo.chr, 3, false);
    }

    // ---- leaving ---------------------------------------------------------------------------------

    @Test
    void aVisitorLeavingMidGameForfeitsAndFreesTheSeat() {
        MiniGame game = startedOmok();
        room.clearPackets();
        send(bo, plain(Action.EXIT));
        verify(ann.chr).setMiniGamePoints(bo.chr, 1, true);
        assertEquals(List.of("getMiniGameOwnerWin(game,true)", "getMiniGameRemoveVisitor()"), ann.packets);
        assertEquals(List.of("getMiniGameOwnerWin(game,true)"), bo.packets);
        assertEquals(List.of("addOmokBox(Ann,2,0)", "addOmokBox(Ann,1,0)"), room.mapPackets);
        assertNull(bo.game());
        assertTrue(game.hasFreeSlot());
        assertSame(game, ann.game());
    }

    @Test
    void theOwnerLeavingClosesTheRoomForBoth() {
        MiniGame game = seatedOmok();
        room.clearPackets();
        send(ann, plain(Action.EXIT));
        assertEquals(List.of("removeMinigameBox(Ann)"), room.mapPackets);
        assertTrue(ann.packets.isEmpty());
        assertEquals(List.of("getMiniGameClose(true,3)"), bo.packets);
        assertNull(ann.game());
        assertNull(bo.game());
        assertNull(game.getOwner());
    }

    @Test
    void theOwnerCanExpelTheVisitor() {
        MiniGame game = seatedOmok();
        room.clearPackets();
        send(bo, plain(Action.EXPEL)); // only the owner may
        assertSame(game, bo.game());

        send(ann, plain(Action.EXPEL));
        assertNull(bo.game());
        assertEquals(List.of("getMiniGameClose(true,5)"), bo.packets);
        assertEquals(List.of("getMiniGameRemoveVisitor()"), ann.packets);
        assertTrue(game.hasFreeSlot());
    }

    @Test
    void leavingAfterTheGameTakesEffectWhenItEnds() {
        MiniGame game = startedOmok();
        send(bo, plain(Action.EXIT_AFTER_GAME));
        send(bo, plain(Action.CANCEL_EXIT_AFTER_GAME));
        send(ann, plain(Action.GIVE_UP));
        assertSame(game, bo.game());

        startAgain(game);
        send(bo, plain(Action.EXIT_AFTER_GAME));
        room.clearPackets();
        send(ann, plain(Action.GIVE_UP));
        assertNull(bo.game());
        assertEquals(List.of("getMiniGameVisitorWin(game,true)", "getMiniGameClose(true,4)"), bo.packets);

        send(bo, visit(game, null));
        startAgain(game);
        send(ann, plain(Action.EXIT_AFTER_GAME));
        room.clearPackets();
        send(bo, plain(Action.GIVE_UP));
        assertNull(ann.game());
        assertNull(bo.game());
        assertEquals(List.of("getMiniGameOwnerWin(game,true)", "getMiniGameClose(false,4)"), ann.packets);
        assertEquals(List.of("getMiniGameOwnerWin(game,true)", "getMiniGameClose(true,3)"), bo.packets);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private MiniGame seatedOmok() {
        send(ann, createOmok("omok", null, 0));
        send(bo, visit(ann.game(), null));
        return ann.game();
    }

    private MiniGame startedOmok() {
        MiniGame game = seatedOmok();
        send(bo, plain(Action.READY));
        send(ann, plain(Action.START));
        return game;
    }

    private void startAgain(MiniGame game) {
        send(bo, plain(Action.READY));
        send(ann, plain(Action.START));
        assertTrue(game.isMatchInProgress());
    }

    /** The slot pairs of the dealt deck, in slot order of their first card. */
    private static List<int[]> pairs(MiniGame game, int cards) {
        List<int[]> pairs = new ArrayList<>();
        boolean[] used = new boolean[cards];
        for (int a = 0; a < cards; a++) {
            for (int b = a + 1; b < cards && !used[a]; b++) {
                if (!used[b] && game.getCardId(a) == game.getCardId(b)) {
                    pairs.add(new int[]{a, b});
                    used[a] = true;
                    used[b] = true;
                }
            }
        }
        return pairs;
    }

    private static int[] mismatch(MiniGame game, int cards) {
        for (int b = 1; b < cards; b++) {
            if (game.getCardId(0) != game.getCardId(b)) {
                return new int[]{0, b};
            }
        }
        throw new AssertionError("a dealt deck always has two different cards");
    }

    private void send(Player player, Consumer<OutPacket> packet) {
        handler.handlePacket(Packets.buildInPacket(packet), player.client);
    }

    private static Consumer<OutPacket> createOmok(String description, String password, int type) {
        return create(1, description, password, type);
    }

    private static Consumer<OutPacket> createMatchCard(String description, String password, int type) {
        return create(2, description, password, type);
    }

    private static Consumer<OutPacket> create(int kind, String description, String password, int type) {
        return out -> {
            out.writeByte(Action.CREATE.getCode());
            out.writeByte(kind);
            out.writeString(description);
            out.writeBool(password != null);
            if (password != null) {
                out.writeString(password);
            }
            out.writeByte(type);
        };
    }

    private static Consumer<OutPacket> visit(MiniGame game, String password) {
        return out -> {
            out.writeByte(Action.VISIT.getCode());
            out.writeInt(game.getObjectId());
            out.writeByte(password != null ? 1 : 0);
            if (password != null) {
                out.writeString(password);
            }
        };
    }

    private static Consumer<OutPacket> plain(Action action) {
        return out -> out.writeByte(action.getCode());
    }

    private static Consumer<OutPacket> answerTie(boolean accept) {
        return out -> {
            out.writeByte(Action.ANSWER_TIE.getCode());
            out.writeBool(accept);
        };
    }

    private static Consumer<OutPacket> move(int x, int y, int type) {
        return out -> {
            out.writeByte(Action.MOVE_OMOK.getCode());
            out.writeInt(x);
            out.writeInt(y);
            out.writeByte(type);
        };
    }

    private static Consumer<OutPacket> card(int turn, int slot) {
        return out -> {
            out.writeByte(Action.SELECT_CARD.getCode());
            out.writeByte(turn);
            out.writeByte(slot);
        };
    }

    private static Consumer<OutPacket> chat(String text) {
        return out -> {
            out.writeByte(Action.CHAT.getCode());
            out.writeString(text);
        };
    }
}
