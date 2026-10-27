package server.maps;

import client.Character;
import constants.id.ItemId;
import server.maps.MiniGame.MiniGameType;
import tools.PacketCreator;

/**
 * The omok and match-card rules, as PlayerInteractionHandler ran them inline: a player's packets and a bot playing
 * server-side (docs/BOT_CONCEPT_PLAN.md F5a) both call these, so a bot's room behaves exactly as a player's. Each step
 * sends the stock packets, refusals included, and tells the room's observer what happened. The steps of one room run
 * one at a time (the room's lock); closing a room stays with Character.closeMiniGame, as before.
 */
public final class MiniGameRules {
    /** A refusal's mini-room error codes (PacketCreator.getMiniRoomError); 0 is none. */
    public static final int OK = 0;

    private MiniGameRules() {
    }

    private static int establishMiniroomStatus(Character chr) {
        if (FieldLimit.CANNOTMINIGAME.check(chr.getMap().getFieldLimit())) {
            return 11;
        }
        if (chr.getChalkboard() != null) {
            return 13;
        }
        if (chr.getEventInstance() != null) {
            return 5;
        }
        return OK;
    }

    /** Opens an omok room with set {@code pieceType} (0-11, clamped); the refusal's error code, or OK. */
    public static int createOmok(Character chr, String description, String password, int pieceType) {
        int status = !chr.isAlive() ? 4 : establishMiniroomStatus(chr);
        if (status > 0) {
            chr.sendPacket(PacketCreator.getMiniRoomError(status));
            return status;
        }
        int type = Math.max(0, Math.min(11, pieceType));
        if (!chr.haveItem(ItemId.MINI_GAME_BASE + type)) {
            chr.sendPacket(PacketCreator.getMiniRoomError(6));
            return 6;
        }
        MiniGame game = new MiniGame(chr, description, password);
        chr.setMiniGame(game);
        game.setPieceType(type);
        game.setGameType(MiniGameType.OMOK);
        chr.getMap().addMapObject(game);
        chr.getMap().broadcastMessage(PacketCreator.addOmokBox(chr, 1, 0));
        game.sendOmok(chr.getClient(), type);
        return OK;
    }

    /** Opens a match-card room of 12, 20 or 30 cards ({@code size} 0, 1, 2, clamped); the refusal's code, or OK. */
    public static int createMatchCard(Character chr, String description, String password, int size) {
        int status = !chr.isAlive() ? 4 : establishMiniroomStatus(chr);
        if (status > 0) {
            chr.sendPacket(PacketCreator.getMiniRoomError(status));
            return status;
        }
        int type = Math.max(0, Math.min(2, size));
        if (!chr.haveItem(ItemId.MATCH_CARDS)) {
            chr.sendPacket(PacketCreator.getMiniRoomError(6));
            return 6;
        }
        MiniGame game = new MiniGame(chr, description, password);
        game.setPieceType(type);
        game.setMatchesToWin(type == 0 ? 6 : type == 1 ? 10 : 15);
        game.setGameType(MiniGameType.MATCH_CARD);
        chr.setMiniGame(game);
        chr.getMap().addMapObject(game);
        chr.getMap().broadcastMessage(PacketCreator.addMatchCardBox(chr, 1, 0));
        game.sendMatchCard(chr.getClient(), type);
        return OK;
    }

    /** Sits {@code chr} in {@code game}'s free seat; 1 for a closed room, 22 a wrong password, 2 a full room, else OK. */
    public static int visit(Character chr, MiniGame game, String password) {
        synchronized (game) {
            if (game.getOwner() == null) {
                chr.sendPacket(PacketCreator.getMiniRoomError(1));
                return 1;
            }
            if (!game.checkPassword(password)) {
                chr.sendPacket(PacketCreator.getMiniRoomError(22));
                return 22;
            }
            if (!game.hasFreeSlot() || game.isVisitor(chr)) {
                chr.sendPacket(PacketCreator.getMiniRoomError(2));
                return 2;
            }
            game.addVisitor(chr);
            chr.setMiniGame(game);
            switch (game.getGameType()) {
                case OMOK -> game.sendOmok(chr.getClient(), game.getPieceType());
                case MATCH_CARD -> game.sendMatchCard(chr.getClient(), game.getPieceType());
                default -> {
                }
            }
            return OK;
        }
    }

    public static void chat(Character chr, String text) {
        MiniGame game = chr.getMiniGame();
        if (game != null) {
            game.chat(chr.getClient(), text);
        }
    }

    public static void ready(Character chr, boolean ready) {
        MiniGame game = chr.getMiniGame();
        if (game == null) {
            return;
        }
        synchronized (game) {
            game.broadcast(ready ? PacketCreator.getMiniGameReady(game) : PacketCreator.getMiniGameUnReady(game));
            game.observe(observer -> observer.ready(game, ready));
        }
    }

    public static void start(Character chr) {
        MiniGame game = chr.getMiniGame();
        if (game == null) {
            return;
        }
        synchronized (game) {
            if (game.getGameType() == MiniGameType.OMOK) {
                game.minigameMatchStarted();
                game.broadcast(PacketCreator.getMiniGameStart(game, game.getLoser()));
                chr.getMap().broadcastMessage(PacketCreator.addOmokBox(game.getOwner(), 2, 1));
            } else if (game.getGameType() == MiniGameType.MATCH_CARD) {
                game.minigameMatchStarted();
                game.shuffleList();
                game.broadcast(PacketCreator.getMatchCardStart(game, game.getLoser()));
                chr.getMap().broadcastMessage(PacketCreator.addMatchCardBox(game.getOwner(), 2, 1));
            } else {
                return;
            }
            game.observe(observer -> observer.started(game));
        }
    }

    public static void giveUp(Character chr) {
        MiniGame game = chr.getMiniGame();
        if (game == null || game.getGameType() == MiniGameType.UNDEFINED) {
            return;
        }
        synchronized (game) {
            if (game.isOwner(chr)) {
                game.minigameMatchVisitorWins(true);
            } else {
                game.minigameMatchOwnerWins(true);
            }
        }
    }

    public static void requestTie(Character chr) {
        MiniGame game = chr.getMiniGame();
        if (game == null) {
            return;
        }
        synchronized (game) {
            if (game.isTieDenied(chr)) {
                return;
            }
            if (game.isOwner(chr)) {
                game.broadcastToVisitor(PacketCreator.getMiniGameRequestTie(game));
            } else {
                game.broadcastToOwner(PacketCreator.getMiniGameRequestTie(game));
            }
            game.observe(observer -> observer.tieOffered(game, chr));
        }
    }

    public static void answerTie(Character chr, boolean accept) {
        MiniGame game = chr.getMiniGame();
        if (game == null) {
            return;
        }
        synchronized (game) {
            if (accept) {
                game.minigameMatchDraw();
                return;
            }
            game.denyTie(chr);
            if (game.isOwner(chr)) {
                game.broadcastToVisitor(PacketCreator.getMiniGameDenyTie(game));
            } else {
                game.broadcastToOwner(PacketCreator.getMiniGameDenyTie(game));
            }
            game.observe(observer -> observer.tieDenied(game, chr));
        }
    }

    public static void skip(Character chr) {
        MiniGame game = chr.getMiniGame();
        if (game == null) {
            return;
        }
        synchronized (game) {
            if (game.isOwner(chr)) {
                game.broadcast(PacketCreator.getMiniGameSkipOwner(game));
            } else {
                game.broadcast(PacketCreator.getMiniGameSkipVisitor(game));
            }
            game.observe(observer -> observer.skipped(game, chr));
        }
    }

    /** Places a stone of {@code type} (1 or 2) at {@code x},{@code y}; a taken cell is ignored, as before. */
    public static void moveOmok(Character chr, int x, int y, int type) {
        MiniGame game = chr.getMiniGame();
        if (game == null) {
            return;
        }
        synchronized (game) {
            game.setPiece(x, y, type, chr);
        }
    }

    /** Turns card {@code slot} face up: {@code turn} 1 is a turn's first card, 0 its second. */
    public static void selectCard(Character chr, int turn, int slot) {
        MiniGame game = chr.getMiniGame();
        if (game == null) {
            return;
        }
        synchronized (game) {
            int firstslot = game.getFirstSlot();
            if (turn == 1) {
                game.setFirstSlot(slot);
                if (game.isOwner(chr)) {
                    game.broadcastToVisitor(PacketCreator.getMatchCardSelect(game, turn, slot, firstslot, turn));
                } else {
                    game.getOwner().sendPacket(PacketCreator.getMatchCardSelect(game, turn, slot, firstslot, turn));
                }
                game.observe(observer -> observer.cardTurned(game, chr, slot, game.getCardId(slot), true, slot, false));
                return;
            }
            boolean matched = game.getCardId(firstslot) == game.getCardId(slot);
            // told before the points: the last pair ends the match inside setOwnerPoints / setVisitorPoints
            game.observe(observer -> observer.cardTurned(game, chr, slot, game.getCardId(slot), false, firstslot, matched));
            if (matched) {
                if (game.isOwner(chr)) {
                    game.broadcast(PacketCreator.getMatchCardSelect(game, turn, slot, firstslot, 2));
                    game.setOwnerPoints();
                } else {
                    game.broadcast(PacketCreator.getMatchCardSelect(game, turn, slot, firstslot, 3));
                    game.setVisitorPoints();
                }
            } else if (game.isOwner(chr)) {
                game.broadcast(PacketCreator.getMatchCardSelect(game, turn, slot, firstslot, 0));
            } else {
                game.broadcast(PacketCreator.getMatchCardSelect(game, turn, slot, firstslot, 1));
            }
        }
    }

    /** The owner sends its visitor out. */
    public static void expel(Character chr) {
        MiniGame game = chr.getMiniGame();
        if (game != null && game.isOwner(chr)) {
            Character visitor = game.getVisitor();
            if (visitor != null) {
                visitor.closeMiniGame(false);
                visitor.sendPacket(PacketCreator.getMiniGameClose(true, 5));
            }
        }
    }

    /** Gets up, as the EXIT packet does: the owner closes its room, a visitor leaves (forfeiting a match on). */
    public static void leave(Character chr) {
        chr.closeMiniGame(false);
    }

    /** Leaves (or, unset, stays) when the match on now ends. */
    public static void leaveAfterGame(Character chr, boolean leave) {
        MiniGame game = chr.getMiniGame();
        if (game != null) {
            game.setQuitAfterGame(chr, leave);
        }
    }
}
