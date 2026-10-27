package server.maps;

import client.Character;
import server.maps.MiniGame.MiniGameResult;

/**
 * Sees what happens in one minigame room, as its two players' clients would: who sat down or left, ready, start, each
 * omok stone and each card turned face up, skips, tie offers, results and the close. A bot playing in the room learns
 * the game through it, since its own client drops every packet (docs/BOT_CONCEPT_PLAN.md F5). Calls come on whatever
 * thread changed the room, often with the room's lock held (MiniGameRules): an observer only records and schedules.
 * A card's id is given only when that card is turned face up, as the client shows it.
 */
public interface MiniGameObserver {
    default void visitorJoined(MiniGame game, Character visitor) {
    }

    default void visitorLeft(MiniGame game, Character visitor) {
    }

    default void ready(MiniGame game, boolean ready) {
    }

    default void started(MiniGame game) {
    }

    default void omokMoved(MiniGame game, Character mover, int x, int y, int type) {
    }

    /** {@code first}: the first card of a turn; else {@code matched} says whether it paired with {@code firstSlot}. */
    default void cardTurned(MiniGame game, Character chooser, int slot, int cardId, boolean first, int firstSlot,
                            boolean matched) {
    }

    default void skipped(MiniGame game, Character skipper) {
    }

    default void tieOffered(MiniGame game, Character asker) {
    }

    default void tieDenied(MiniGame game, Character answerer) {
    }

    /** A match is over: {@code ownerResult} is the owner's WIN, LOSS or TIE. */
    default void matchEnded(MiniGame game, MiniGameResult ownerResult, boolean forfeit) {
    }

    /** The room closes; its owner is still set. */
    default void closed(MiniGame game) {
    }
}
