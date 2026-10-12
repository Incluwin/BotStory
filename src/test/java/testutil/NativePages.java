package testutil;

import client.Character;
import server.living.resident.LivingTalk;
import server.living.resident.NativeTalkPage;
import server.living.resident.NativeTalkRenderer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A native page renderer for tests (P10 Step 7): it records every page LivingTalk draws and every page it closes, and
 * clicks a page's options as the native Crew window's PAGE_REPLY does. {@link #install} it before the screen under test
 * and {@link #uninstall} it after.
 */
public final class NativePages implements NativeTalkRenderer {
    /** False: the client draws no pages (protocol 1), so LivingTalk falls back to the NPC window. */
    public boolean draws = true;
    public final List<NativeTalkPage> pages = new ArrayList<>();
    public final List<Integer> closes = new ArrayList<>();

    public NativePages install() {
        LivingTalk.nativeRenderer(this);
        return this;
    }

    public void uninstall() {
        LivingTalk.clearNativeRenderer(this);
    }

    @Override
    public boolean draws(Character chr) {
        return draws;
    }

    @Override
    public void page(Character chr, NativeTalkPage page) {
        pages.add(page);
    }

    @Override
    public void close(Character chr, int stamp) {
        closes.add(stamp);
    }

    /** The page drawn last. */
    public NativeTalkPage last() {
        assertFalse(pages.isEmpty(), "no native page was drawn");
        return pages.get(pages.size() - 1);
    }

    /** The option labels of the page drawn last, in order. */
    public List<String> labels() {
        return labels(last());
    }

    public static List<String> labels(NativeTalkPage page) {
        return page.entries().stream().filter(NativeTalkPage.Entry::option).map(NativeTalkPage.Entry::label).toList();
    }

    /** The selection value of the option {@code label} on {@code page}. */
    public static int selection(NativeTalkPage page, String label) {
        return page.entries().stream().filter(entry -> entry.option() && entry.label().equals(label))
                .mapToInt(NativeTalkPage.Entry::selection).findFirst()
                .orElseThrow(() -> new AssertionError("no option '" + label + "' in " + page.entries()));
    }

    /** Clicks the option {@code label} on the page drawn last. */
    public void pick(Character chr, String label) {
        NativeTalkPage page = last();
        assertTrue(LivingTalk.respondNative(chr, page.stamp(), (byte) 1, selection(page, label)), label);
    }

    /** Answers the page drawn last with {@code action} (1 OK / Yes, 0 No / Prev, 255 Close). */
    public void answer(Character chr, int action) {
        assertTrue(LivingTalk.respondNative(chr, last().stamp(), (byte) action, -1));
    }

    /** Every option label of a paged menu, from the page drawn last through "More..." until it wraps (no paging labels). */
    public List<String> allLabels(Character chr) {
        List<String> all = new ArrayList<>();
        for (int guard = 0; guard < 20; guard++) {
            List<String> labels = labels();
            labels.stream().filter(label -> !label.equals("More...") && !label.equals("Back to the top..."))
                    .forEach(all::add);
            if (!labels.contains("More...")) {
                return all; // one page, or the last ("Back to the top...")
            }
            pick(chr, "More...");
        }
        throw new AssertionError("the menu never ended");
    }
}
