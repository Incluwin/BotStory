package server.living.resident;

import client.Character;

/**
 * What draws {@link LivingTalk}'s native pages (docs/BOT_MANAGEMENT_P10_PLAN.md, "Step 7: native pages"): the native
 * Crew window registers itself ({@link LivingTalk#nativeRenderer}). A talk opened with {@link LivingTalk#openNative} is
 * drawn here when {@link #draws} says so, else it is the NPC window.
 */
public interface NativeTalkRenderer {
    /** True when the player's client draws native pages now (its window speaks pages and may be used). */
    boolean draws(Character chr);

    /** Shows {@code page}; it replaces any page on screen. */
    void page(Character chr, NativeTalkPage page);

    /** Closes the page stamped {@code stamp} (0: whatever page is shown); the window shows the crew again. */
    void close(Character chr, int stamp);
}
