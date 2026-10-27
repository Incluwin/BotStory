package server.maps;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testutil.MiniGameRoom;
import testutil.MiniGameRoom.Player;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Cosmic PR #294 (decision D10, for everyone): the omok win scan covers the whole board in all four directions, never
 * wraps a row across the edge, and every finished game clears the board, not only a five-in-a-row.
 */
class MiniGameOmokWinFixTest {
    private MiniGameRoom room;
    private Player ann;
    private Player bo;
    private MiniGame game;

    @BeforeEach
    void setUp() {
        room = new MiniGameRoom();
        ann = room.player("Ann", 1);
        bo = room.player("Bo", 2);
        game = new MiniGame(ann.chr, "omok", "");
        game.setGameType(MiniGame.MiniGameType.OMOK);
        ann.chr.setMiniGame(game);
        game.addVisitor(bo.chr);
        bo.chr.setMiniGame(game);
        game.minigameMatchStarted();
    }

    @AfterEach
    void tearDown() {
        room.close();
    }

    @Test
    void aVerticalFiveInTheRightColumnsWins() {
        for (int y = 0; y < 5; y++) {
            game.setPiece(13, y, 1, ann.chr);
        }
        verify(ann.chr).setMiniGamePoints(bo.chr, 1, true);
        assertFalse(game.isMatchInProgress());
    }

    @Test
    void aVerticalFiveInTheBottomRightCornerWinsForTheVisitor() {
        for (int y = 10; y < 15; y++) {
            game.setPiece(14, y, 2, bo.chr);
        }
        verify(ann.chr).setMiniGamePoints(bo.chr, 2, true);
        assertEquals(1, game.getLoser());
    }

    @Test
    void aDiagonalIntoTheBottomRightCornerWins() {
        for (int i = 10; i < 15; i++) {
            game.setPiece(i, i, 1, ann.chr);
        }
        verify(ann.chr).setMiniGamePoints(bo.chr, 1, true);
    }

    @Test
    void aRowNeverWrapsAcrossTheEdge() {
        game.setPiece(12, 0, 1, ann.chr);
        game.setPiece(13, 0, 1, ann.chr);
        game.setPiece(14, 0, 1, ann.chr);
        game.setPiece(0, 1, 1, ann.chr);
        game.setPiece(1, 1, 1, ann.chr);
        game.setPiece(14, 4, 1, ann.chr); // and no diagonal down-left wraps either
        game.setPiece(0, 6, 1, ann.chr);
        assertTrue(game.isMatchInProgress());
        verify(ann.chr, never()).setMiniGamePoints(any(), anyInt(), anyBoolean());
    }

    @Test
    void aGameThatEndsByGivingUpLeavesAnEmptyBoard() {
        game.setPiece(7, 7, 1, ann.chr);
        game.minigameMatchVisitorWins(true);
        game.minigameMatchStarted();
        room.clearPackets();
        game.setPiece(7, 7, 2, bo.chr);
        assertEquals(List.of("getMiniGameMoveOmok(game,7,7,2)"), bo.packets);
    }

    @Test
    void aTieLeavesAnEmptyBoard() {
        game.setPiece(3, 3, 1, ann.chr);
        game.minigameMatchDraw();
        game.minigameMatchStarted();
        room.clearPackets();
        game.setPiece(3, 3, 1, ann.chr);
        assertEquals(List.of("getMiniGameMoveOmok(game,3,3,1)"), ann.packets);
    }
}
