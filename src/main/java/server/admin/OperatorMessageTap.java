package server.admin;

import client.Character;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lets the AdminControlPort observe chat-notice output of a headless operator character. A
 * {@code BotClient} discards packets, so the text a GM command prints would otherwise vanish.
 * {@link Character#dropMessage(int, String)} and {@link Character#yellowMessage(String)} offer
 * their text here; only characters with a registered tap pay more than a map lookup.
 */
public final class OperatorMessageTap {
    private static final Map<Integer, Consumer<String>> TAPS = new ConcurrentHashMap<>();

    private OperatorMessageTap() {
    }

    public static void install(int characterId, Consumer<String> sink) {
        TAPS.put(characterId, sink);
    }

    public static void remove(int characterId) {
        TAPS.remove(characterId);
    }

    public static void offer(Character chr, String message) {
        if (TAPS.isEmpty() || chr == null || message == null) {
            return;
        }
        Consumer<String> sink = TAPS.get(chr.getId());
        if (sink != null) {
            sink.accept(message);
        }
    }
}
