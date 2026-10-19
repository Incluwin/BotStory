package server.mods;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * The equips only BotStory's WZ files have (resource {@value #RESOURCE}): 303 the server took from the Community
 * Repack and the 4 Evolving Rings, none of them in the Cosmic WZ files. A client on those files shows one as an empty
 * inventory slot and closes when a character equips or wears it, so while {@link ServerMods#BOTSTORY_WZ} is off the GM
 * item commands, the dressing room and SoloMapling's bots leave them out.
 */
public final class BotStoryWzEquips {
    static final String RESOURCE = "/mods/botstory-wz-equips.txt";

    private static final Set<Integer> IDS = load();

    private BotStoryWzEquips() {
    }

    /** Every listed id. */
    public static Set<Integer> ids() {
        return IDS;
    }

    public static boolean listed(int itemId) {
        return IDS.contains(itemId);
    }

    /** botstory_wz is off: the clients may be on the Cosmic WZ files. */
    public static boolean withholding() {
        return !ServerMods.get().isOn(ServerMods.BOTSTORY_WZ);
    }

    /** A listed equip while botstory_wz is off. */
    public static boolean withheld(int itemId) {
        return listed(itemId) && withholding();
    }

    /** The GM line for a withheld equip. */
    public static String refusal(int itemId) {
        return "Item " + itemId + " is only in BotStory's WZ files and server mod botstory_wz is off:"
                + " a client on the Cosmic WZ files closes on it.";
    }

    /** {@code items} without the listed equips while botstory_wz is off; {@code items} itself otherwise. */
    public static <T> List<T> filter(List<T> items, ToIntFunction<T> itemId) {
        if (!withholding()) {
            return items;
        }
        List<T> kept = new ArrayList<>(items.size());
        for (T item : items) {
            if (!listed(itemId.applyAsInt(item))) {
                kept.add(item);
            }
        }
        return kept.size() == items.size() ? items : Collections.unmodifiableList(kept);
    }

    /** One id per line, then anything; '#' starts a comment line. */
    static Set<Integer> parse(List<String> lines) {
        Set<Integer> ids = new HashSet<>();
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int end = text.indexOf(' ');
            ids.add(Integer.parseInt(end < 0 ? text : text.substring(0, end)));
        }
        return Collections.unmodifiableSet(ids);
    }

    private static Set<Integer> load() {
        try (InputStream in = BotStoryWzEquips.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
